import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, createEvent } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import CaptureCropDialog, { LIVE_SCROLL_KEYS } from '../components/techerror/capture/CaptureCropDialog.jsx'

// Kırpma penceresi (2026-10-18): kare kutuya sığdırılır, seçim kaynak pikselleriyle tutulur. Fare / kalem / dokunma ile
// sürükleyerek yeni seçim, içinden taşıma, tutamakla boyutlandırma; klavyeyle oklar / Shift / Alt; Enter yükler.

const FRAME = { width: 1600, height: 900, canvas: null, uniform: false }

function setup(props = {}) {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  const onRetake = vi.fn()
  const view = render(<CaptureCropDialog open frame={{ ...FRAME, canvas: document.createElement('canvas'), ...props.frame }}
    label="Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri" onConfirm={onConfirm} onCancel={onCancel} onRetake={onRetake} {...props} />)
  const stage = () => document.querySelector('[data-slot="te-cap-stage"]')
  // Ekranda 800 × 450 (ölçek 0,5): jsdom yerleşim yapmaz — sahnenin kutusu verilir
  const box = (el) => { el.getBoundingClientRect = () => ({ left: 100, top: 50, width: 800, height: 450, right: 900, bottom: 500 }) }
  return { view, stage, box, onConfirm, onCancel, onRetake }
}

const at = (x, y) => ({ clientX: 100 + x, clientY: 50 + y, pointerId: 1, pointerType: 'mouse', button: 0 })

beforeEach(() => {
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('kırpma penceresi', () => {
  it('başlık yuva adını taşır; başlangıçta tamamı seçili; ölçü kaynak pikseliyle; karenin kendi tuvali sahnede', async () => {
    const { stage } = setup()
    expect(await screen.findByRole('dialog', { name: 'Ekrandan al: Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri' })).toBeInTheDocument()
    expect(stage()).toHaveAttribute('data-rect', '0,0,1600,900')
    expect(document.querySelector('[data-slot="te-cap-size"]')).toHaveTextContent('Seçim: 1600 × 900 piksel · görüntü 1600 × 900')
    expect(document.querySelector('[data-slot="te-cap-size-live"]')).toHaveTextContent('Seçim: 1600 × 900 piksel · görüntü 1600 × 900')
    expect(document.querySelector('[data-slot="te-cap-frame"]')).not.toBeNull()
    expect(document.querySelectorAll('[data-handle]')).toHaveLength(8)
    // Tamamı seçiliyken "Tümünü seç" kapalı
    expect(screen.getByRole('button', { name: /Tümünü seç/ })).toBeDisabled()
    // Klavye odağı sahnede (oklarla hemen kullanılabilir)
    await waitFor(() => expect(document.activeElement).toBe(stage()))
  })

  it('fare: tamamı seçiliyken sürüklemek YENİ seçim çizer; tıklama (hareketsiz) seçimi değiştirmez', async () => {
    const { stage, box } = setup()
    await screen.findByRole('dialog')
    box(stage())
    fireEvent.pointerDown(stage(), at(10, 10))
    fireEvent.pointerUp(stage(), at(10, 10))
    expect(stage()).toHaveAttribute('data-rect', '0,0,1600,900')
    fireEvent.pointerDown(stage(), at(100, 50))
    fireEvent.pointerMove(stage(), at(200, 120))
    fireEvent.pointerMove(stage(), at(300, 200))
    // Görünen ölçü sürüklerken canlı; ekran okuyucunun canlı bölgesi yalnız sürükleme bitince (inceleme 2026-10-18)
    const size = () => document.querySelector('[data-slot="te-cap-size"]')
    const live = () => document.querySelector('[data-slot="te-cap-size-live"]')
    expect(size()).toHaveTextContent('Seçim: 400 × 300 piksel · görüntü 1600 × 900')
    expect(size()).toHaveAttribute('aria-hidden', 'true')
    expect(live()).toHaveAttribute('aria-live', 'polite')
    expect(live()).toHaveTextContent('Seçim: 1600 × 900 piksel')
    fireEvent.pointerUp(stage(), at(300, 200))
    expect(stage()).toHaveAttribute('data-rect', '200,100,400,300')
    expect(live()).toHaveTextContent('Seçim: 400 × 300 piksel · görüntü 1600 × 900')
    // Klavye değişikliği hemen söylenir
    fireEvent.keyDown(stage(), { key: 'ArrowRight' })
    await waitFor(() => expect(live()).toHaveTextContent('Seçim: 400 × 300 piksel'))
    fireEvent.keyDown(stage(), { key: 'ArrowRight', altKey: true })
    await waitFor(() => expect(live()).toHaveTextContent('Seçim: 401 × 300 piksel'))
  })

  it('içinden sürükleyerek taşır; tutamakla boyutlandırır; dokunma ile de çalışır', async () => {
    const { stage, box } = setup()
    await screen.findByRole('dialog')
    box(stage())
    fireEvent.pointerDown(stage(), at(100, 50))
    fireEvent.pointerMove(stage(), at(300, 200))
    fireEvent.pointerUp(stage(), at(300, 200))
    // Taşı: seçimin içinden (+50, +25 ekran = +100, +50 kaynak)
    fireEvent.pointerDown(stage(), at(200, 100))
    fireEvent.pointerMove(stage(), at(250, 125))
    fireEvent.pointerUp(stage(), at(250, 125))
    expect(stage()).toHaveAttribute('data-rect', '300,150,400,300')
    // Sağ alt tutamak (dokunma): +25, +10 ekran = +50, +20 kaynak
    const se = document.querySelector('[data-handle="se"]')
    fireEvent.pointerDown(se, { ...at(350, 225), pointerType: 'touch' })
    fireEvent.pointerMove(stage(), { ...at(375, 235), pointerType: 'touch' })
    fireEvent.pointerUp(stage(), { ...at(375, 235), pointerType: 'touch' })
    expect(stage()).toHaveAttribute('data-rect', '300,150,450,320')
    // Sol kenar tutamağı: karşı kenarı geçemez, en az 16 kalır
    const w = document.querySelector('[data-handle="w"]')
    fireEvent.pointerDown(w, at(150, 150))
    fireEvent.pointerMove(stage(), at(800, 150))
    fireEvent.pointerUp(stage(), at(800, 150))
    expect(stage()).toHaveAttribute('data-rect', '734,150,16,320')
    // Farenin sağ tuşu seçim başlatmaz
    fireEvent.pointerDown(stage(), { ...at(10, 10), button: 2 })
    fireEvent.pointerMove(stage(), at(400, 400))
    expect(stage()).toHaveAttribute('data-rect', '734,150,16,320')
  })

  it('klavye: oklar 1 px, Shift 10 px taşır; Alt + oklar boyutlandırır; Enter seçili alanı yükler', async () => {
    const { stage, onConfirm } = setup()
    await screen.findByRole('dialog')
    const s = stage()
    fireEvent.keyDown(s, { key: 'ArrowLeft', altKey: true, shiftKey: true })   // genişlik 1590
    fireEvent.keyDown(s, { key: 'ArrowUp', altKey: true })                    // yükseklik 899
    expect(s).toHaveAttribute('data-rect', '0,0,1590,899')
    fireEvent.keyDown(s, { key: 'ArrowRight', shiftKey: true })
    fireEvent.keyDown(s, { key: 'ArrowRight' })
    fireEvent.keyDown(s, { key: 'ArrowDown' })
    expect(s).toHaveAttribute('data-rect', '10,1,1590,899')
    fireEvent.keyDown(s, { key: 'ArrowRight' })   // sağ kenarda durur
    expect(s).toHaveAttribute('data-rect', '10,1,1590,899')
    fireEvent.keyDown(s, { key: 'Enter' })
    expect(onConfirm).toHaveBeenCalledWith({ x: 10, y: 1, w: 1590, h: 899 })
    // "Tümünü seç"
    fireEvent.click(screen.getByRole('button', { name: /Tümünü seç/ }))
    expect(s).toHaveAttribute('data-rect', '0,0,1600,900')
    fireEvent.click(screen.getByRole('button', { name: /Bu alanı yükle/ }))
    expect(onConfirm).toHaveBeenLastCalledWith({ x: 0, y: 0, w: 1600, h: 900 })
  })

  it('yeniden çekim: yeni kare küçükse seçim korunur ama yeni kareye sığdırılır', async () => {
    const onConfirm = vi.fn()
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    const view = render(<CaptureCropDialog open frame={frame} label="Yuva" onConfirm={onConfirm} onCancel={() => {}} onRetake={() => {}} />)
    await screen.findByRole('dialog')
    const s = () => document.querySelector('[data-slot="te-cap-stage"]')
    fireEvent.keyDown(s(), { key: 'ArrowLeft', altKey: true, shiftKey: true })
    fireEvent.keyDown(s(), { key: 'ArrowRight', shiftKey: true })
    expect(s()).toHaveAttribute('data-rect', '10,0,1590,900')
    view.rerender(<CaptureCropDialog open frame={{ width: 1280, height: 720, canvas: document.createElement('canvas') }} label="Yuva"
      onConfirm={onConfirm} onCancel={() => {}} onRetake={() => {}} />)
    await waitFor(() => expect(s()).toHaveAttribute('data-rect', '0,0,1280,720'))
    expect(s()).toHaveAttribute('data-source', '1280x720')
  })

  it('canlı özellik verilmeyen (eski) çağrıda canlı çubuk, katman ve "Canlıya dön" yoktur; yardım metni bugünkü', async () => {
    setup()
    const dlg = await screen.findByRole('dialog')
    expect(document.querySelector('[data-slot="te-cap-live-bar"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-cap-pad"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /Canlıya dön|Dondur|Kaydırmayı etkinleştir/ })).toBeNull()
    expect(screen.getByRole('button', { name: /Yeniden çek/ })).toBeInTheDocument()
    expect(dlg).toHaveTextContent('Yüklenecek alanı sürükleyerek seçin')
    expect(document.querySelector('[data-slot="te-cap-stage"]')).not.toHaveAttribute('data-live')
  })

  it('boş (tek renk) kare uyarısı; yeniden çek / vazgeç düğmeleri; meşgulken kapanmaz', async () => {
    const { onRetake, onCancel, view } = setup({ frame: { uniform: true } })
    await screen.findByRole('dialog')
    expect(screen.getByText('Görüntü boş geldi')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Yeniden çek/ }))
    expect(onRetake).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Vazgeç' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
    view.rerender(<CaptureCropDialog open busy frame={{ ...FRAME, canvas: document.createElement('canvas') }} label="Yuva"
      onConfirm={() => {}} onCancel={onCancel} onRetake={onRetake} error="Seçilen alan hazırlanamadı." />)
    expect(screen.getByRole('alert')).toHaveTextContent('Seçilen alan hazırlanamadı.')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: /Hazırlanıyor/ })).toBeDisabled()
  })
})

// CANLI KİP (2026-11-19, kullanıcı isteği: "paylaşılan ekranda aşağı yukarı gidebilmeliyim, istediğim noktayı seçip
// alabilmeliyim"): sekme paylaşımında sahne canlıdır; tekerlek katmanı (te-cap-pad) çağırana verilir; seçime başlamak
// (basış) ya da "Dondur" görüntüyü dondurur; "Canlıya dön" yeniden canlıya alır. 2026-11-20 (kullanıcı isteği: "faresi
// olmayan da aşağı yukarı gidebilmeli"): canlıyken ok / sayfa tuşları DONDURMAZ — tarayıcı tuşla kaydırmaya izin vermez,
// klavye ipucu vurgulanır ve sesli söylenir; dokunmatik yüzey de kaydırır.
describe('kırpma penceresi — canlı kip', () => {
  function live(props = {}) {
    const cb = { onFreeze: vi.fn(), onResume: vi.fn(), onEnableScroll: vi.fn(), onPad: vi.fn(), onActivity: vi.fn() }
    const s = setup({ live: true, canLive: true, scroll: 'on', ...cb, ...props })
    const bar = () => document.querySelector('[data-slot="te-cap-live-bar"]')
    const status = () => document.querySelector('[data-slot="te-cap-live-status"]')
    const pad = () => document.querySelector('[data-slot="te-cap-pad"]')
    return { ...s, ...cb, bar, status, pad }
  }

  it('canlıyken: "Canlı" rozeti, canlı yardım metni, tekerlek katmanı çağırana verilir; "Yeniden çek" yok, "Dondur" var', async () => {
    const { bar, status, pad, onPad, stage } = live()
    const dlg = await screen.findByRole('dialog')
    expect(bar()).toHaveAttribute('data-live', 'true')
    expect(bar()).toHaveAttribute('data-scroll', 'on')
    expect(document.querySelector('[data-slot="te-cap-live-badge"]')).toHaveTextContent('Canlı')
    expect(dlg).toHaveTextContent('Paylaşılan sekme canlı gösteriliyor. Fare tekerleğiyle ya da dokunmatik yüzeyde iki parmakla aşağı yukarı kaydırıp')
    expect(status()).toHaveTextContent('Fare tekerleği ya da dokunmatik yüzey (iki parmakla kaydırma) paylaşılan sekmeyi buradan kaydırır.')
    expect(status()).toHaveAttribute('aria-live', 'polite')
    expect(stage()).toHaveAttribute('data-live', 'true')
    // Katman sahnenin içinde, ekran okuyucudan gizli; çağıran öğeyi alır (tekerlek iletimi ona bağlanır)
    expect(pad()).not.toBeNull()
    expect(pad()).toHaveAttribute('aria-hidden', 'true')
    expect(stage().contains(pad())).toBe(true)
    expect(onPad).toHaveBeenCalledWith(pad())
    expect(screen.queryByRole('button', { name: /Yeniden çek/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Canlıya dön/ })).toBeNull()
    expect(screen.getByRole('button', { name: /Dondur/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeNull()
    // Klavye kullanıcısına canlı kipin tuş davranışı söylenir (sahnenin açıklaması)
    const keys = document.getElementById(stage().getAttribute('aria-describedby'))
    expect(keys).toHaveTextContent('Görüntü canlı: ok tuşları paylaşılan sekmeyi buradan kaydıramaz ve görüntüyü dondurmaz. '
      + 'Klavyeyle kaydırmak için paylaşılan sekmeye geçin; klavyeyle alan seçmek için önce “Dondur”a basın.')
  })

  it('tekerlekle kaydırma yalnız GERÇEKTEN açıkken vaat edilir: izin yok / desteklenmiyor iken yardım metni tekerlekten söz etmez', async () => {
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    const props = { open: true, frame, label: 'Yuva', live: true, canLive: true, onConfirm: () => {}, onCancel: () => {} }
    const view = render(<CaptureCropDialog {...props} scroll="ask" />)
    const dlg = await screen.findByRole('dialog')
    for (const scroll of ['checking', 'ask', 'asking', 'failed', 'denied', 'unsupported']) {
      view.rerender(<CaptureCropDialog {...props} scroll={scroll} />)
      expect(dlg).toHaveTextContent('İstediğiniz yer görününce yüklenecek alanı sürükleyerek seçin')
      expect(dlg).not.toHaveTextContent('Fare tekerleğiyle ya da dokunmatik yüzeyde')
      // Klavye ipucu da tekerlek / dokunmatik yüzey vaat etmez (yalnız paylaşılan sekmeye geçme yolu)
      expect(document.querySelector('[data-slot="te-cap-keys-hint"]')).toHaveTextContent('Klavyeyle kaydırmak için paylaşılan sekmeye geçin')
      expect(document.querySelector('[data-slot="te-cap-keys-hint"]')).not.toHaveTextContent('dokunmatik yüzey')
    }
    view.rerender(<CaptureCropDialog {...props} scroll="on" />)
    expect(dlg).toHaveTextContent('Fare tekerleğiyle ya da dokunmatik yüzeyde iki parmakla aşağı yukarı kaydırıp')
    expect(document.querySelector('[data-slot="te-cap-keys-hint"]'))
      .toHaveTextContent('buradan yalnız fare tekerleği ve dokunmatik yüzey (iki parmak) kaydırır')
    // Donukken sahne açıklamasında canlı notu ve klavye ipucu yok
    view.rerender(<CaptureCropDialog {...props} live={false} />)
    const stage = document.querySelector('[data-slot="te-cap-stage"]')
    expect(document.getElementById(stage.getAttribute('aria-describedby'))).not.toHaveTextContent('Görüntü canlı')
    expect(document.querySelector('[data-slot="te-cap-keys-hint"]')).toBeNull()
  })

  it('kaydırma durumu metne çevrilir; "Kaydırmayı etkinleştir" yalnız izin sorulacakken / açılamadığında', async () => {
    const table = [
      ['checking', 'Paylaşılan sekme canlı gösteriliyor.', false],
      ['asking', 'Kaydırma izni bekleniyor: tarayıcının sorusuna “İzin ver” deyin.', false],
      ['ask', 'Paylaşılan sekmeyi buradan kaydırmak için tarayıcı bir kez izin ister.', true],
      ['failed', 'Kaydırma açılamadı.', true],
      ['denied', 'Kaydırma izni verilmedi.', false],
      ['unsupported', 'Bu tarayıcı paylaşılan sekmeyi buradan kaydıramıyor', false],
      ['bilinmeyen-durum', 'Paylaşılan sekme canlı gösteriliyor.', false],
    ]
    const { view, status, onEnableScroll } = live({ scroll: 'checking' })
    await screen.findByRole('dialog')
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    for (const [scroll, text, button] of table) {
      view.rerender(<CaptureCropDialog open frame={frame} label="Yuva" live canLive scroll={scroll} onEnableScroll={onEnableScroll}
        onConfirm={() => {}} onCancel={() => {}} />)
      expect(status()).toHaveTextContent(text)
      expect(!!screen.queryByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBe(button)
    }
    view.rerender(<CaptureCropDialog open frame={frame} label="Yuva" live canLive scroll="ask" onEnableScroll={onEnableScroll}
      onConfirm={() => {}} onCancel={() => {}} />)
    const enable = screen.getByRole('button', { name: /Kaydırmayı etkinleştir/ })
    enable.focus()
    fireEvent.click(enable)
    expect(onEnableScroll).toHaveBeenCalledTimes(1)
    // Düğme basılınca kalkar (izin bekleniyor): klavye odağı sahneye geçer, gövdeye düşmez
    expect(document.activeElement).toBe(document.querySelector('[data-slot="te-cap-stage"]'))
  })

  it('sahneye basmak görüntüyü ÖNCE dondurur (bir kez), sonra seçim bugünkü gibi çizilir', async () => {
    const { stage, box, pad, onFreeze, onActivity } = live()
    await screen.findByRole('dialog')
    box(stage())
    // Basış katmana düşer, olay sahneye çıkar
    fireEvent.pointerDown(pad(), at(100, 50))
    expect(onFreeze).toHaveBeenCalledTimes(1)
    expect(onActivity).toHaveBeenCalled()
    fireEvent.pointerMove(stage(), at(300, 200))
    fireEvent.pointerUp(stage(), at(300, 200))
    expect(stage()).toHaveAttribute('data-rect', '200,100,400,300')
    // Sürükleme boyunca yeniden dondurma istenmez (tek basış = tek dondurma; çağıranın dondurması zaten tekrarsızdır)
    expect(onFreeze).toHaveBeenCalledTimes(1)
  })

  it('canlıyken ok / sayfa tuşları DONDURMAZ ve seçimi oynatmaz: klavye ipucu vurgulanır, ekran okuyucuya her basışta söylenir', async () => {
    const a = live()
    await screen.findByRole('dialog')
    const hint = () => document.querySelector('[data-slot="te-cap-keys-hint"]')
    const said = () => document.querySelector('[data-slot="te-cap-keys-live"]')
    // Baştan görünür (vurgusuz); sesli bölge boş
    expect(hint()).toHaveTextContent('Klavyeyle kaydırmak için paylaşılan sekmeye geçin (örneğin Ctrl+Tab)')
    expect(hint()).not.toHaveAttribute('data-flash')
    expect(said()).toHaveAttribute('aria-live', 'assertive')
    expect(said().textContent).toBe('')
    const texts = []
    for (const key of LIVE_SCROLL_KEYS) {
      const ev = createEvent.keyDown(a.stage(), { key })
      fireEvent(a.stage(), ev)
      expect(ev.defaultPrevented).toBe(true)   // sayfa / pencere de kaymasın
      texts.push(said().textContent)
    }
    expect(a.onFreeze).not.toHaveBeenCalled()
    expect(a.stage()).toHaveAttribute('data-rect', '0,0,1600,900')
    expect(a.stage()).toHaveAttribute('data-live', 'true')
    expect(hint()).toHaveAttribute('data-flash', 'true')
    expect(said()).toHaveTextContent('Ok tuşları paylaşılan sekmeyi buradan kaydıramaz (tarayıcı kısıtı).')
    // Aynı tuşa art arda basılınca da yeniden okunur: metin her basışta değişir
    for (let i = 1; i < texts.length; i++) expect(texts[i]).not.toBe(texts[i - 1])
    // Alt / Shift ile oklar da canlıyken seçim yapmaz (önce "Dondur")
    fireEvent.keyDown(a.stage(), { key: 'ArrowLeft', altKey: true, shiftKey: true })
    expect(a.onFreeze).not.toHaveBeenCalled()
    expect(a.stage()).toHaveAttribute('data-rect', '0,0,1600,900')
    // Kaydırma tuşu olmayan tuş (harf, Tab) hiçbir şey yapmaz, engellenmez
    const other = createEvent.keyDown(a.stage(), { key: 'a' })
    fireEvent(a.stage(), other)
    expect(other.defaultPrevented).toBe(false)
    a.view.unmount()
  })

  it('donunca klavye ipucu kalkar, vurgu sıfırlanır; oklar yeniden seçimi oynatır', async () => {
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    const props = { open: true, frame, label: 'Yuva', canLive: true, scroll: 'on', onConfirm: () => {}, onCancel: () => {} }
    const view = render(<CaptureCropDialog {...props} live />)
    await screen.findByRole('dialog')
    const stage = () => document.querySelector('[data-slot="te-cap-stage"]')
    fireEvent.keyDown(stage(), { key: 'ArrowDown' })
    expect(document.querySelector('[data-slot="te-cap-keys-hint"]')).toHaveAttribute('data-flash', 'true')
    view.rerender(<CaptureCropDialog {...props} live={false} />)
    expect(document.querySelector('[data-slot="te-cap-keys-hint"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-cap-keys-live"]').textContent).toBe('')
    fireEvent.keyDown(stage(), { key: 'ArrowLeft', altKey: true })
    expect(stage()).toHaveAttribute('data-rect', '0,0,1599,900')
    // Canlıya dönünce ipucu vurgusuz geri gelir
    view.rerender(<CaptureCropDialog {...props} live />)
    expect(document.querySelector('[data-slot="te-cap-keys-hint"]')).not.toHaveAttribute('data-flash')
  })

  it('"Dondur" dondurur ve odağı sahneye verir; Enter canlıyken yüklemeyi çağırana bırakır (çağıran dondurur)', async () => {
    const b = live()
    await screen.findByRole('dialog')
    fireEvent.click(screen.getByRole('button', { name: /Dondur/ }))
    expect(b.onFreeze).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(document.activeElement).toBe(b.stage()))
    b.view.unmount()
    const c = live()
    await screen.findByRole('dialog')
    fireEvent.keyDown(c.stage(), { key: 'Enter' })
    expect(c.onConfirm).toHaveBeenCalledWith({ x: 0, y: 0, w: 1600, h: 900 })
    expect(c.onFreeze).not.toHaveBeenCalled()
  })

  it('canlıyken tekerlek, basış ve ok tuşu çağırana "kullanıcı burada" der (boşta sayacı için); donukken tekerlek demez', async () => {
    const a = live()
    await screen.findByRole('dialog')
    a.box(a.stage())
    fireEvent.wheel(a.pad(), { deltaY: 120 })
    expect(a.onActivity).toHaveBeenCalledTimes(1)
    fireEvent.pointerDown(a.pad(), at(100, 50))
    fireEvent.pointerUp(a.stage(), at(100, 50))
    expect(a.onActivity).toHaveBeenCalledTimes(2)
    fireEvent.keyDown(a.stage(), { key: 'ArrowLeft', altKey: true })
    expect(a.onActivity).toHaveBeenCalledTimes(3)
    a.view.unmount()
    const onActivity = vi.fn()
    setup({ canLive: true, onActivity })
    await screen.findByRole('dialog')
    fireEvent.wheel(document.querySelector('[data-slot="te-cap-stage"]'), { deltaY: 120 })
    expect(onActivity).not.toHaveBeenCalled()
  })

  it('donukken (sekme paylaşımı): "Donduruldu" rozeti, katman yok, "Canlıya dön" var; canlıya dönünce seçim tüm kareye döner', async () => {
    const onResume = vi.fn()
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    const props = { open: true, frame, label: 'Yuva', canLive: true, onResume, onConfirm: () => {}, onCancel: () => {}, onRetake: () => {} }
    const view = render(<CaptureCropDialog {...props} live={false} />)
    await screen.findByRole('dialog')
    const stage = () => document.querySelector('[data-slot="te-cap-stage"]')
    expect(document.querySelector('[data-slot="te-cap-live-badge"]')).toHaveTextContent('Donduruldu')
    expect(document.querySelector('[data-slot="te-cap-live-status"]')).toHaveTextContent('Görüntü donduruldu; alanı seçip yükleyin.')
    expect(document.querySelector('[data-slot="te-cap-pad"]')).toBeNull()
    // Sekme paylaşımında "Yeniden çek" yerine "Canlıya dön" (aynı iş: yeni görüntü + kaydırma)
    expect(screen.queryByRole('button', { name: /Yeniden çek/ })).toBeNull()
    fireEvent.keyDown(stage(), { key: 'ArrowLeft', altKey: true, shiftKey: true })
    expect(stage()).toHaveAttribute('data-rect', '0,0,1590,900')
    const resume = screen.getByRole('button', { name: /Canlıya dön/ })
    resume.focus()
    fireEvent.click(resume)
    expect(onResume).toHaveBeenCalledTimes(1)
    // Düğme canlıya geçince kalkar: odak sahnede kalır (klavyeyle hemen "Dondur" / Enter)
    expect(document.activeElement).toBe(stage())
    view.rerender(<CaptureCropDialog {...props} live />)
    expect(document.activeElement).toBe(stage())
    await waitFor(() => expect(stage()).toHaveAttribute('data-rect', '0,0,1600,900'))
    expect(document.querySelector('[data-slot="te-cap-live-badge"]')).toHaveTextContent('Canlı')
  })

  it('canlıyken kaynağın ölçüsü değişirse seçim tüm kare kalır (donukken yeni kareye sığdırılırdı)', async () => {
    const frame = { ...FRAME, canvas: document.createElement('canvas') }
    const props = { open: true, label: 'Yuva', live: true, canLive: true, scroll: 'on', onConfirm: () => {}, onCancel: () => {} }
    const view = render(<CaptureCropDialog {...props} frame={frame} />)
    await screen.findByRole('dialog')
    const stage = () => document.querySelector('[data-slot="te-cap-stage"]')
    view.rerender(<CaptureCropDialog {...props} frame={{ ...frame, width: 1280, height: 720 }} />)
    await waitFor(() => expect(stage()).toHaveAttribute('data-rect', '0,0,1280,720'))
    expect(stage()).toHaveAttribute('data-source', '1280x720')
  })

  it('pencere / tüm ekran paylaşımı: canlı değil; neden buradan kaydırılamadığı yazar, "Yeniden çek" kalır', async () => {
    setup({ scrollHint: true })
    await screen.findByRole('dialog')
    expect(document.querySelector('[data-slot="te-cap-live-bar"]')).toHaveAttribute('data-live', 'false')
    expect(document.querySelector('[data-slot="te-cap-live-status"]')).toHaveTextContent(
      'Pencere ya da tüm ekran paylaşımında görüntü buradan kaydırılamaz (tarayıcı kısıtı).')
    expect(document.querySelector('[data-slot="te-cap-live-badge"]')).toBeNull()
    expect(screen.getByRole('button', { name: /Yeniden çek/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Canlıya dön|Dondur/ })).toBeNull()
  })

  it('paylaşım pencere açıkken bittiyse bunu söyler; son görüntü seçilip yüklenebilir', async () => {
    const { onConfirm } = setup({ liveEnded: true })
    await screen.findByRole('dialog')
    expect(document.querySelector('[data-slot="te-cap-live-status"]')).toHaveTextContent(
      'Ekran paylaşımı sona erdi; son görüntü donduruldu. Alanı seçip yükleyebilirsiniz.')
    expect(screen.queryByRole('button', { name: /Canlıya dön|Dondur/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Bu alanı yükle/ }))
    expect(onConfirm).toHaveBeenCalledWith({ x: 0, y: 0, w: 1600, h: 900 })
  })

  it('boş kare uyarısı canlıyken gösterilmez (görüntü değişiyor); donunca sekme paylaşımına özgü metinle gösterilir', async () => {
    const frame = { ...FRAME, uniform: true, canvas: document.createElement('canvas') }
    const props = { open: true, frame, label: 'Yuva', canLive: true, onConfirm: () => {}, onCancel: () => {} }
    const view = render(<CaptureCropDialog {...props} live scroll="on" />)
    await screen.findByRole('dialog')
    expect(screen.queryByText('Görüntü boş geldi')).toBeNull()
    view.rerender(<CaptureCropDialog {...props} live={false} />)
    expect(screen.getByText('Görüntü boş geldi')).toBeInTheDocument()
    expect(screen.getByText(/“Canlıya dön”e basıp yeniden deneyin/)).toBeInTheDocument()
  })

  it('meşgulken (yükleme hazırlanıyor) canlı kip düğmeleri kapalıdır', async () => {
    live({ scroll: 'ask', busy: true })
    await screen.findByRole('dialog')
    expect(screen.getByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Dondur/ })).toBeDisabled()
  })
})
