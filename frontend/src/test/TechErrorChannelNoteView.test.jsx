import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Kanal notunun KİLİTLİ görüntüleyicisi (2026-11-20). Not kullanıcı yazısıdır ve başka takımlarla paylaşılabilir:
 *  - görsel yalnız `te-note-img:<kimlik>` VE kimlik notun görsel listesindeyse çizilir; başka her adres için <img> HİÇ
 *    çizilmez (uzak sunucuya istek gitmez) — yerinde bilgi kutusu;
 *  - ham HTML çalışmaz, düz metin görünür;
 *  - bağlantı yalnız güvenli şemayla tıklanır (yeni sekme + rel);
 *  - başlıklar bir düzey iner; GFM tablo / görev listesi çizilir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: { channelNoteImageUrl: (id) => `/api/tech-error-reports/channel-notes/images/${id}` },
  }),
}))
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ChannelNoteView, { noteUrlTransform } from '../components/techerror/notes/ChannelNoteView.jsx'

const IMAGES = [
  { id: 12, file_name: 'qlik-ana-ekran.png', content_type: 'image/png', width: 1200, height: 600, size_bytes: 120000, linked: true },
  { id: 13, file_name: 'filtreler.png', content_type: 'image/png', width: 800, height: 400, size_bytes: 80000, linked: true },
]
const view = () => document.querySelector('[data-slot="te-note-view"]')
const show = (body, props = {}) => render(<ChannelNoteView body={body} images={IMAGES} {...props} />)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
})

describe('ChannelNoteView — görseller', () => {
  it('dış kaynaklı görsel ÇİZİLMEZ: <img> yok (istek gitmez), yerinde bilgi kutusu ve alt metin', () => {
    show('Önce\n\n![takip pikseli](https://example.com/a.png)\n\nSonra')
    expect(view().querySelector('img')).toBeNull()
    const box = view().querySelector('[data-slot="te-note-img-blocked"]')
    expect(box).not.toBeNull()
    expect(box.textContent).toContain('Dış kaynaklı görsel gösterilmez')
    expect(box.textContent).toContain('takip pikseli')
    // Adresin kendisi DOM'da hiçbir öznitelikte yer almaz
    expect(view().innerHTML).not.toContain('example.com')
    expect(screen.getByText('Önce')).toBeInTheDocument()
    expect(screen.getByText('Sonra')).toBeInTheDocument()
  })

  it.each([
    ['http', '![x](http://example.com/a.png)'],
    ['protokolsüz', '![x](//example.com/a.png)'],
    ['data:', '![x](data:image/png;base64,iVBORw0KGgo=)'],
    ['göreli', '![x](/api/tech-error-reports/images/5)'],
    ['aynı kökenli tam adres', '![x](/api/tech-error-reports/channel-notes/images/12)'],
    ['blob:', '![x](blob:https://example.com/abc)'],
    ['başvuru + sorgu', '![x](te-note-img:12?boyut=1)'],
    ['19 rakam', '![x](te-note-img:1234567890123456789)'],
    ['büyük harf önek', '![x](TE-NOTE-IMG:12)'],
    ['başvuru biçimli tanım', '![x][r]\n\n[r]: https://example.com/a.png'],
  ])('not görseli olmayan adres (%s) için <img> çizilmez', (_ad, body) => {
    show(body)
    expect(view().querySelector('img')).toBeNull()
    expect(view().querySelector('[data-slot="te-note-img-blocked"]')).not.toBeNull()
  })

  it('listedeki not görseli aynı kökenli adresten, boyutu ayrılmış ve alt metniyle çizilir', () => {
    show('![Qlik ana ekranı](te-note-img:12)')
    const img = view().querySelector('img[data-slot="te-note-img"]')
    expect(img).not.toBeNull()
    expect(img.getAttribute('src')).toBe('/api/tech-error-reports/channel-notes/images/12')
    expect(img.getAttribute('alt')).toBe('Qlik ana ekranı')
    expect(img.getAttribute('width')).toBe('1200')
    expect(img.getAttribute('height')).toBe('600')
    expect(img.getAttribute('loading')).toBe('lazy')
    expect(img.dataset.imageId).toBe('12')
    expect(screen.getByRole('button', { name: 'Görseli büyüt: Qlik ana ekranı' })).toBeInTheDocument()
  })

  it('baştaki sıfırlar aynı kimliktir; alt metin yoksa dosya adı kullanılır', () => {
    show('![](te-note-img:0013)')
    const img = view().querySelector('img[data-slot="te-note-img"]')
    expect(img.getAttribute('src')).toBe('/api/tech-error-reports/channel-notes/images/13')
    expect(img.getAttribute('alt')).toBe('filtreler.png')
  })

  it('listede olmayan kimlik "görsel artık yok" kutusudur — <img> çizilmez', () => {
    show('![silinmiş](te-note-img:999)')
    expect(view().querySelector('img')).toBeNull()
    const box = view().querySelector('[data-slot="te-note-img-missing"]')
    expect(box.textContent).toContain('Görsel artık yok')
    expect(box.textContent).toContain('silinmiş')
  })

  it('görsel listesi yokken (images tanımsız) hiçbir not görseli çizilmez', () => {
    render(<ChannelNoteView body={'![a](te-note-img:12)'} />)
    expect(view().querySelector('img')).toBeNull()
    expect(view().querySelector('[data-slot="te-note-img-missing"]')).not.toBeNull()
  })

  it('yüklenemeyen görsel: "yüklenemedi" + "Tekrar dene" görseli yeniden çizer', () => {
    show('![Qlik](te-note-img:12)')
    fireEvent.error(view().querySelector('img[data-slot="te-note-img"]'))
    expect(view().querySelector('img')).toBeNull()
    const failed = view().querySelector('[data-slot="te-note-img-failed"]')
    expect(failed.textContent).toContain('Görsel yüklenemedi')
    fireEvent.click(within(failed).getByRole('button', { name: 'Tekrar dene' }))
    expect(view().querySelector('img[data-slot="te-note-img"]')).not.toBeNull()
    expect(view().querySelector('[data-slot="te-note-img-failed"]')).toBeNull()
  })

  it('görsele tıklayınca büyük görünüm açılır; kapanınca görsel yerinde durur', async () => {
    show('![Qlik ana ekranı](te-note-img:12)')
    fireEvent.click(screen.getByRole('button', { name: 'Görseli büyüt: Qlik ana ekranı' }))
    const box = await waitFor(() => {
      const el = document.querySelector('[data-slot="te-image-lightbox"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(within(box).getByText('Qlik ana ekranı')).toBeInTheDocument()
    expect(box.querySelector('[data-slot="te-image-lightbox-img"]').getAttribute('src')).toBe('/api/tech-error-reports/channel-notes/images/12')
    fireEvent.click(box.querySelector('[data-slot="te-image-lightbox-close"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="te-image-lightbox"]')).toBeNull())
    expect(view().querySelector('img[data-slot="te-note-img"]')).not.toBeNull()
  })
})

describe('ChannelNoteView — ham HTML ve bağlantılar', () => {
  it('ham HTML çalışmaz: düz metin olarak görünür (script / iframe / img öğesi oluşmaz)', () => {
    show([
      '<script>window.__noteXss = 1</script>',
      '',
      'Satır içi <img src="https://example.com/a.png" onerror="window.__noteXss = 2"> deneme',
      '',
      '<iframe src="https://example.com/"></iframe>',
      '',
      '<a href="javascript:alert(1)">tıkla</a>',
    ].join('\n'))
    expect(view().querySelector('script')).toBeNull()
    expect(view().querySelector('iframe')).toBeNull()
    expect(view().querySelector('img')).toBeNull()
    expect(view().querySelector('a')).toBeNull()
    expect(view().textContent).toContain('<script>window.__noteXss = 1</script>')
    expect(view().textContent).toContain('<iframe src="https://example.com/"></iframe>')
    expect(window.__noteXss).toBeUndefined()
  })

  it('http(s) ve mailto bağlantısı yeni sekmede, rel="noopener noreferrer" ile açılır', () => {
    show('[Qlik](https://qlik.example.com/app/1) · [yaz](mailto:ekip@example.com) · <https://oto.example.com/x>')
    const links = [...view().querySelectorAll('a')]
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['https://qlik.example.com/app/1', 'mailto:ekip@example.com', 'https://oto.example.com/x'])
    for (const a of links) {
      expect(a.getAttribute('target')).toBe('_blank')
      expect(a.getAttribute('rel')).toBe('noopener noreferrer')
    }
  })

  it.each([
    ['javascript:', '[tıkla](javascript:alert(1))'],
    ['JaVaScRiPt:', '[tıkla](JaVaScRiPt:alert(1))'],
    ['data:', '[tıkla](data:text/html;base64,PHNjcmlwdD4=)'],
    ['vbscript:', '[tıkla](vbscript:msgbox(1))'],
    ['not görseli öneki', '[tıkla](te-note-img:12)'],
  ])('güvensiz şema (%s) tıklanamaz: düz metin kalır', (_ad, body) => {
    show(body)
    expect(view().querySelector('a')).toBeNull()
    expect(view().querySelector('[data-slot="te-note-link-plain"]').textContent).toBe('tıkla')
  })

  it('sayfa içi bağlantı (#…) aynı sekmede kalır', () => {
    show('[aşağı](#bolum)')
    const a = view().querySelector('a')
    expect(a.getAttribute('href')).toBe('#bolum')
    expect(a.hasAttribute('target')).toBe(false)
  })

  it('dipnot (GFM): başvuru ve geri dönüş bağlantıları birbirinin hedefini bulur (id korunur, sayfa içi)', () => {
    show('Kaynak tablosu[^1] burada.\n\n[^1]: Qlik › Kanal özeti ekranı.')
    const ref = view().querySelector('a[data-footnote-ref], sup a')
    const back = view().querySelector('a[href^="#user-content-fnref"]')
    expect(ref).not.toBeNull()
    expect(back).not.toBeNull()
    // Başvuru bağlantısı geri dönüş bağlantısının hedefidir; geri dönüşün de hedefi (dipnot maddesi) vardır
    expect(ref.id).toBeTruthy()
    expect(back.getAttribute('href')).toBe(`#${ref.id}`)
    expect(document.getElementById(ref.getAttribute('href').slice(1))).not.toBeNull()
    for (const a of [ref, back]) expect(a.hasAttribute('target')).toBe(false)
    // Geri dönüş bağlantısının erişilebilir adı korunur
    expect(back.getAttribute('aria-label')).toBeTruthy()
  })

  it('bağlantı içindeki not görseli düğme değildir (etkileşimli öğe iç içe geçmez): düz <img>, tıklayınca bağlantı açılır', () => {
    show('[![Ana ekran](te-note-img:12)](https://qlik.example.com/app/1)')
    const a = view().querySelector('a')
    expect(a.getAttribute('href')).toBe('https://qlik.example.com/app/1')
    const img = a.querySelector('img[data-slot="te-note-img"]')
    expect(img).not.toBeNull()
    expect(img).toHaveAttribute('data-in-link', 'true')
    expect(a.querySelector('button')).toBeNull()
    // Bağlantı dışındaki görsel büyütme düğmesi olarak kalır
    show('![Ana ekran](te-note-img:12)')
    expect(document.querySelectorAll('[data-slot="te-note-img-open"]').length).toBeGreaterThanOrEqual(1)
  })

  it('bağlantı içinde yüklenemeyen görselin kutusunda "Tekrar dene" düğmesi yoktur', () => {
    show('[![Ana ekran](te-note-img:12)](https://qlik.example.com/app/1)')
    fireEvent.error(view().querySelector('img[data-slot="te-note-img"]'))
    const a = view().querySelector('a')
    expect(a.querySelector('[data-slot="te-note-img-failed"]')).not.toBeNull()
    expect(a.querySelector('button')).toBeNull()
  })

  it('noteUrlTransform: görselde yalnız kanonik not başvurusu, bağlantıda güvenli şema; ötekiler boş', () => {
    const img = { tagName: 'img' }
    const a = { tagName: 'a' }
    expect(noteUrlTransform('te-note-img:007', 'src', img)).toBe('te-note-img:7')
    expect(noteUrlTransform(' te-note-img:12 ', 'src', img)).toBe('te-note-img:12')
    expect(noteUrlTransform('https://example.com/a.png', 'src', img)).toBe('')
    expect(noteUrlTransform('te-note-img:12', 'srcSet', img)).toBe('')
    expect(noteUrlTransform('https://example.com/', 'href', a)).toBe('https://example.com/')
    expect(noteUrlTransform('javascript:alert(1)', 'href', a)).toBe('')
    expect(noteUrlTransform('https://example.com/p', 'ping', a)).toBe('')
    expect(noteUrlTransform('https://example.com/', 'cite', { tagName: 'blockquote' })).toBe('')
    expect(noteUrlTransform('https://example.com/a.mp4', 'src', { tagName: 'video' })).toBe('')
    expect(noteUrlTransform('https://example.com/', 'href', undefined)).toBe('')
  })
})

describe('ChannelNoteView — yapı', () => {
  it('başlıklar bir düzey iner: # → h3, ## → h4, ###### → h6 (sayfanın başlık sırası bozulmaz)', () => {
    show('# Veri kaynağı\n\n## Adımlar\n\n### Ayrıntı\n\n###### En alt')
    expect(view().querySelector('h1')).toBeNull()
    expect(view().querySelector('h2')).toBeNull()
    expect(screen.getByRole('heading', { level: 3, name: 'Veri kaynağı' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 4, name: 'Adımlar' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 5, name: 'Ayrıntı' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 6, name: 'En alt' })).toBeInTheDocument()
  })

  it('headingBase=4 (kart içi): # → h4', () => {
    show('# Veri kaynağı\n\n## Adımlar', { headingBase: 4 })
    expect(view().querySelector('h3')).toBeNull()
    expect(screen.getByRole('heading', { level: 4, name: 'Veri kaynağı' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 5, name: 'Adımlar' })).toBeInTheDocument()
  })

  it('GFM tablo, görev listesi, üstü çizili, kod ve liste çizilir', () => {
    show([
      '| Alan | Kaynak |',
      '| --- | --- |',
      '| Toplam işlem | Qlik › Kanal özeti |',
      '',
      '- [x] Süzgeç seçildi',
      '- [ ] Tarih aralığı girildi',
      '',
      '1. Bir',
      '2. İki',
      '',
      '~~eski yol~~ `SELECT 1`',
      '',
      '```',
      'kod bloğu',
      '```',
    ].join('\n'))
    const table = view().querySelector('[data-slot="te-note-table"]')
    expect(table).not.toBeNull()
    expect(within(table).getByRole('columnheader', { name: 'Alan' })).toBeInTheDocument()
    expect(within(table).getByRole('cell', { name: 'Qlik › Kanal özeti' })).toBeInTheDocument()
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(2)
    expect(boxes[0]).toBeDisabled()
    expect(boxes[0].getAttribute('aria-label')).toBe('Tamamlandı')
    expect(boxes[1].getAttribute('aria-label')).toBe('Tamamlanmadı')
    expect(view().querySelector('ol')).not.toBeNull()
    expect(view().querySelector('del').textContent).toBe('eski yol')
    expect(view().querySelector('pre').textContent).toContain('kod bloğu')
    // Ham <input> çizilmez (görev kutusu shadcn Checkbox'tır)
    expect(view().querySelector('input')).toBeNull()
  })

  it('boş / dizgi olmayan gövde boş görünümdür (çökmez)', () => {
    const { rerender } = show('')
    expect(view().textContent).toBe('')
    rerender(<ChannelNoteView body={null} images={IMAGES} />)
    expect(view().textContent).toBe('')
    rerender(<ChannelNoteView body={undefined} images={null} />)
    expect(view()).not.toBeNull()
  })

  it('uzun ad ve adresler kesilmez: kök sarar (overflow-wrap) ve truncate sınıfı yoktur', () => {
    show(`Dijital Kanallar Uygulama Geliştirme (Bireysel / Mobil) — https://qlik.example.com/${'cok-uzun-yol/'.repeat(12)}`)
    expect(view().className).toContain('[overflow-wrap:anywhere]')
    expect(view().innerHTML).not.toMatch(/\btruncate\b|line-clamp/)
  })
})
