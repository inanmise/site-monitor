import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * Kanal notu görselleri (2026-11-20). Görsel, rapor görsel yuvalarıyla AYNI üç yoldan gelir (Görsel yükle → Ekrandan al →
 * Panodan yapıştır — aynı düğmeler, aynı sıra) + yazma alanına yapıştırma / sürükle-bırak. Hepsi tek hatta varır:
 * `prepareImage` → yükleme → imlece KENDİ satırında `![ad](te-note-img:<kimlik>)`.
 *  - yazma alanına yapıştırma sırası: Office / Excel tablosu → görsel → metin;
 *  - hazırlama hatası türünü söyler; kilit çakışmasında AYNI dosya bir kez yeniden gönderilir; geçici retlerde hazır
 *    görsel bekletilir ("Yeniden dene"), sınır retlerinde bekletilmez;
 *  - "Bu notun görselleri": yalnız KULLANILMAYAN görselde "Sil" (onaylı); 409 NOTE_IMAGE_IN_USE listeyi tazeler.
 */
vi.mock('../components/ui/mdEditor/LazyMdEditor.jsx', () => ({
  default: function FakeMdEditor(props) {
    const tp = props.textareaProps || {}
    return (
      <textarea aria-label={tp['aria-label'] || 'md'} data-slot={tp['data-slot']} value={props.value}
        onChange={(e) => props.onChange(e.target.value)} />
    )
  },
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      channelNote: vi.fn(), saveChannelNote: vi.fn(), uploadChannelNoteImage: vi.fn(), deleteChannelNoteImage: vi.fn(),
      channelNoteVersions: vi.fn(), channelNoteVersion: vi.fn(),
      channelNoteImageUrl: (id) => `/api/tech-error-reports/channel-notes/images/${id}`,
    },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { ImagePrepError, prepareImage } from '../components/techerror/imagePrep.js'
import { captureSession } from '../components/techerror/capture/captureSession.js'
import { SLOT_ACTION_CLASS } from '../components/techerror/capture/SlotCapture.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ChannelNoteEditor from '../components/techerror/notes/ChannelNoteEditor.jsx'

const te = () => api.techErrors
const LIMITS = { max_chars: 60000, max_images: 40, max_image_bytes: 5242880, max_total_bytes: 41943040, unlinked_keep_days: 30, max_versions: 20 }
const png = (name = 'ekran-qlik.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const ok = (data) => ({ success: true, status: 200, data })

function image(id, extra = {}) {
  return {
    id, file_name: `gorsel-${id}.png`, content_type: 'image/png', width: 800, height: 400, size_bytes: 102400,
    uploaded_at: '2026-11-19T08:00:00Z', linked: false, ...extra,
  }
}
function note(extra = {}) {
  return {
    channel_id: 10, exists: true, version: 4, body: 'Giriş\n\nSon', char_count: 10, updated_at: '2026-11-18T08:30:00Z',
    updated_by_name: 'Ayşe Yılmaz', created_at: '2026-11-01T08:00:00Z', images: [], image_bytes: 0, limits: LIMITS,
    can_edit: true, shared_access: false, ...extra,
  }
}
const uploaded = (id, file) => ok({ image: image(id, { file_name: file.name, size_bytes: 2048 }), ref: `te-note-img:${id}` })

const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const textarea = () => screen.getByLabelText('Kanal notu (Markdown)')
const fileInput = () => slot('te-image-actions').querySelector('input[type="file"]')
const rows = () => [...document.querySelectorAll('[data-slot="te-note-image-row"]')]
const row = (id) => document.querySelector(`[data-slot="te-note-image-row"][data-image-id="${id}"]`)
/** İmleci yazma alanında `at` konumuna koyar (düzenleyici imleci tuş / fare / seçim olayından okur). */
function caretAt(at) {
  const ta = textarea()
  ta.focus()
  ta.setSelectionRange(at, at)
  fireEvent.keyUp(ta)
}
/** Görsel alanının ANA eylem düğmelerinin görünen adları, DOM sırasıyla (alan adı ekran okuyucu ekidir, atılır). */
function mainActions() {
  return [...slot('te-image-main-actions').querySelectorAll('button')].map((b) => b.textContent.split(':')[0].trim())
}
function renderEditor(noteExtra = {}, props = {}) {
  const handlers = { onSaved: vi.fn(), onImagesChange: vi.fn(), onDirtyChange: vi.fn(), onReloadLatest: vi.fn(), onClose: vi.fn() }
  const view = render(<ChannelNoteEditor channelId={10} note={note(noteExtra)} {...handlers} {...props} />)
  return { ...view, ...handlers }
}
const clip = (extra = {}) => ({ items: [], files: [], types: [], getData: () => '', ...extra })
const imageItem = (file) => ({ kind: 'file', type: file.type, getAsFile: () => file })

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  for (const fn of [te().channelNote, te().saveChannelNote, te().uploadChannelNoteImage, te().deleteChannelNoteImage]) fn.mockReset()
  prepareImage.mockReset()
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
  te().uploadChannelNoteImage.mockImplementation(async (_id, file) => uploaded(501, file))
  window.innerWidth = 1024
})
afterEach(() => {
  captureSession.stop('test')
  delete navigator.mediaDevices
  if (navigator.clipboard) delete navigator.clipboard.read
  window.innerWidth = 1024
})

describe('kanal notu — görsel ekleme', () => {
  it('"Görsel yükle": hazırla → yükle → imlecin bulunduğu yere KENDİ satırında eklenir; görsel listeye girer', async () => {
    const { onImagesChange } = renderEditor()
    caretAt(5)                                                            // "Giriş|"
    const file = png()
    fireEvent.change(fileInput(), { target: { files: [file] } })
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(1))
    expect(prepareImage).toHaveBeenCalledWith(file)
    expect(te().uploadChannelNoteImage).toHaveBeenCalledWith(10, file)
    await waitFor(() => expect(textarea().value).toBe('Giriş\n\n![ekran-qlik](te-note-img:501)\n\nSon'))
    expect(await screen.findByText('Görsel yüklendi ve metne eklendi.')).toBeInTheDocument()
    // Liste: yeni görsel en üstte; taslakta kullanıldığı için "Kullanılmıyor" DEĞİL ve silinemez
    await waitFor(() => expect(rows()).toHaveLength(1))
    expect(row(501)).toHaveTextContent('ekran-qlik.png')
    // Başvuru kümesi ertelenmiş değerle yenilenir (yazarken liste her tuşta çizilmesin diye)
    await waitFor(() => expect(row(501)).toHaveAttribute('data-unused', 'false'))
    expect(within(row(501)).queryByText('Kullanılmıyor')).toBeNull()
    expect(row(501).querySelector('[data-slot="te-note-image-delete"]')).toBeNull()
    expect(slot('te-note-images-totals')).toHaveTextContent('1 / 40 görsel · 2 KB / 40 MB')
    expect(onImagesChange).toHaveBeenLastCalledWith([expect.objectContaining({ id: 501 })], 2048)
    // Not henüz kaydedilmedi: taslak kirli
    expect(slot('te-note-save')).toBeEnabled()
    expect(te().saveChannelNote).not.toHaveBeenCalled()
    expect(slot('te-note-img-problem')).toBeNull()
  })

  it('imleç bilinmiyorsa görsel metnin sonuna eklenir; ikinci görsel birincinin ardına ayrı paragraf olur', async () => {
    renderEditor({ body: 'Giriş' })
    te().uploadChannelNoteImage
      .mockImplementationOnce(async (_id, file) => uploaded(1, file))
      .mockImplementationOnce(async (_id, file) => uploaded(2, file))
    fireEvent.change(fileInput(), { target: { files: [png('a.png')] } })
    await waitFor(() => expect(textarea().value).toBe('Giriş\n\n![a](te-note-img:1)\n'))
    await waitFor(() => expect(slot('te-image-upload')).toBeEnabled())
    fireEvent.change(fileInput(), { target: { files: [png('b.png')] } })
    await waitFor(() => expect(textarea().value).toBe('Giriş\n\n![a](te-note-img:1)\n\n![b](te-note-img:2)\n'))
    expect(rows().map((r) => r.dataset.imageId)).toEqual(['2', '1'])
  })

  it('yazma alanına yapıştırılan görsel pano adıyla aynı hattan yüklenir ve imlece eklenir', async () => {
    renderEditor()
    const ta = textarea()
    ta.focus()
    ta.setSelectionRange(5, 5)
    const file = png('image.png')
    fireEvent.paste(ta, { clipboardData: clip({ items: [imageItem(file)], types: ['Files'] }) })
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(1))
    const sent = prepareImage.mock.calls[0][0]
    expect(sent.type).toBe('image/png')
    expect(sent.name).toMatch(/^kanal-notu-pano-\d{8}-\d{6}\.png$/)
    expect(te().uploadChannelNoteImage).toHaveBeenCalledWith(10, sent)
    await waitFor(() => expect(textarea().value).toMatch(/^Giriş\n\n!\[kanal-notu-pano-\d{8}-\d{6}\]\(te-note-img:501\)\n\nSon$/))
  })

  it('yapıştırma sırası: Excel tablosu (pano hem tablo hem görsel taşısa da) tabloya çevrilir — görsel yüklenmez', async () => {
    renderEditor({ body: '' })
    const ta = textarea()
    ta.focus()
    const html = '<table><tr><th>Alan</th><th>Kaynak</th></tr><tr><td>Toplam işlem</td><td>Qlik</td></tr></table>'
    fireEvent.paste(ta, { clipboardData: clip({
      items: [imageItem(png('excel.png'))], types: ['text/html', 'text/plain', 'Files'],
      getData: (type) => (type === 'text/html' ? html : 'Alan\tKaynak\nToplam işlem\tQlik'),
    }) })
    await waitFor(() => expect(textarea().value).toContain('| Alan | Kaynak |'))
    expect(textarea().value).toContain('| Toplam işlem | Qlik |')
    expect(await screen.findByText('Tablo eklendi')).toBeInTheDocument()
    await act(async () => {})
    expect(prepareImage).not.toHaveBeenCalled()
    expect(te().uploadChannelNoteImage).not.toHaveBeenCalled()
  })

  it('düz metin yapıştırması tarayıcıya bırakılır (engellenmez, yükleme yok); görsel olmayan dosya türünü söyleyen hata yazar', async () => {
    renderEditor()
    const ta = textarea()
    const plain = fireEvent.paste(ta, { clipboardData: clip({ items: [{ kind: 'string', type: 'text/plain' }], types: ['text/plain'], getData: () => 'düz metin' }) })
    expect(plain).toBe(true)                                              // preventDefault çağrılmadı
    // Dosya + metin (ör. dosya yöneticisinden kopyalanan ad): metin yapıştırılır
    const pdf = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'rapor.pdf', { type: 'application/pdf' })
    const withText = fireEvent.paste(ta, { clipboardData: clip({ items: [{ kind: 'file', type: pdf.type, getAsFile: () => pdf }], types: ['Files', 'text/plain'] }) })
    expect(withText).toBe(true)
    await act(async () => {})
    expect(prepareImage).not.toHaveBeenCalled()

    // Yalnız dosya (PDF): yükleme hattına gider, hazırlama türünü söyler; yükleme yapılmaz, metin değişmez
    prepareImage.mockImplementationOnce(async () => { throw new ImagePrepError('te.img.errPdf', []) })
    const only = fireEvent.paste(ta, { clipboardData: clip({ items: [{ kind: 'file', type: pdf.type, getAsFile: () => pdf }], types: ['Files'] }) })
    expect(only).toBe(false)
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('PDF dosyası görsel yuvasına yüklenemez.'))
    expect(prepareImage).toHaveBeenCalledWith(pdf)                        // kendi adıyla (yeniden adlandırılmaz)
    expect(te().uploadChannelNoteImage).not.toHaveBeenCalled()
    expect(textarea().value).toBe('Giriş\n\nSon')
    expect(slot('te-image-kept')).toBeNull()                              // aynı dosya yine aynı sonucu verir: bekletilmez
  })

  it('yazma alanına bırakılan dosya yüklenir; dosya olmayan sürükleme yok sayılır', async () => {
    renderEditor()
    const pane = slot('te-note-write-pane')
    fireEvent.drop(pane, { dataTransfer: { files: [], types: ['text/plain'] } })
    await act(async () => {})
    expect(prepareImage).not.toHaveBeenCalled()
    const file = png('birakilan.png')
    fireEvent.dragOver(pane, { dataTransfer: { files: [file], types: ['Files'] } })
    fireEvent.drop(pane, { dataTransfer: { files: [file], types: ['Files'] } })
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledWith(10, file))
    await waitFor(() => expect(textarea().value).toContain('![birakilan](te-note-img:501)'))
  })

  it('görsel alanı odaktayken Ctrl+V de aynı hatta varır (alan adı ve kısayol ekran okuyucuya söylenir)', async () => {
    renderEditor()
    const zone = screen.getByRole('group', { name: 'Kanal notu görsel yuvası — Ctrl+V ile görsel yapıştırabilirsiniz' })
    expect(zone).toBe(slot('te-note-image-zone'))
    expect(zone).toHaveAttribute('tabindex', '0')
    const file = png('image.png')
    fireEvent.paste(zone, { clipboardData: clip({ items: [imageItem(file)], types: ['Files'] }) })
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0].name).toMatch(/^kanal-notu-pano-\d{8}-\d{6}\.png$/)
  })

  it('yükleme sürerken ikinci görsel yüklenmez: kısa ipucu, tek yükleme', async () => {
    let finish
    prepareImage.mockImplementationOnce(() => new Promise((r) => { finish = r }))
    renderEditor()
    const first = png('bir.png')
    fireEvent.change(fileInput(), { target: { files: [first] } })
    await waitFor(() => expect(slot('te-image-upload')).toHaveTextContent('Hazırlanıyor…'))
    expect(slot('te-image-upload')).toBeDisabled()
    expect(slot('te-note-image-zone')).toHaveAttribute('aria-busy', 'true')
    fireEvent.paste(textarea(), { clipboardData: clip({ items: [imageItem(png('iki.png'))], types: ['Files'] }) })
    expect(await screen.findByText('Bu yuvaya yükleme sürüyor; bitince yeniden deneyin.')).toBeInTheDocument()
    await act(async () => { finish({ file: first, info: {} }) })
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(1))
    expect(prepareImage).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(slot('te-image-upload')).toBeEnabled())
    expect(slot('te-note-image-zone')).not.toHaveAttribute('aria-busy')
  })

  it('hazırlamada küçültülen / çevrilen görsel bunu söyler (not ve bildirim)', async () => {
    prepareImage.mockImplementationOnce(async (file) => ({ file, info: { converted: true, fromKind: 'WebP', toKind: 'PNG' } }))
    renderEditor()
    fireEvent.change(fileInput(), { target: { files: [png('donusen.png')] } })
    await waitFor(() => expect(slot('te-note-img-note')).not.toBeNull())
    expect(slot('te-note-img-note').textContent).toMatch(/WebP/)
    expect(slot('te-note-img-note').textContent).toMatch(/PNG/)
  })
})

describe('kanal notu — yükleme hataları', () => {
  it('kilit çakışmasında AYNI hazır dosya bir kez yeniden gönderilir (yeniden hazırlanmaz)', async () => {
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage
      .mockResolvedValueOnce({ success: false, status: 503, code: 'LOCK_CONFLICT', error: 'İşlem tamamlanmamış olabilir; birkaç saniye sonra yeniden deneyin.', retry_after_seconds: 0.01 })
      .mockImplementationOnce(async (_id, file) => uploaded(77, file))
    renderEditor()
    const file = png('kilit.png')
    fireEvent.change(fileInput(), { target: { files: [file] } })
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(2))
    expect(te().uploadChannelNoteImage.mock.calls[0][1]).toBe(file)
    expect(te().uploadChannelNoteImage.mock.calls[1][1]).toBe(file)
    expect(prepareImage).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(textarea().value).toContain('(te-note-img:77)'))
    expect(slot('te-note-img-problem')).toBeNull()
    expect(slot('te-image-kept')).toBeNull()
  })

  it('kilit iki kez sürerse sunucunun metni yazar ve hazır görsel bekletilir: "Yeniden dene" aynı dosyayı gönderir', async () => {
    const lock = { success: false, status: 503, code: 'LOCK_CONFLICT', error: 'İşlem tamamlanmamış olabilir; birkaç saniye sonra yeniden deneyin.', retry_after_seconds: 0.01 }
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage.mockResolvedValueOnce(lock).mockResolvedValueOnce(lock).mockImplementationOnce(async (_id, file) => uploaded(78, file))
    renderEditor()
    const file = png('kilit2.png')
    fireEvent.change(fileInput(), { target: { files: [file] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('İşlem tamamlanmamış olabilir'))
    expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(2)
    expect(textarea().value).toBe('Giriş\n\nSon')
    const kept = await waitFor(() => {
      const el = slot('te-image-kept')
      expect(el).not.toBeNull()
      return el
    })
    expect(kept).toHaveTextContent('Hazırladığınız görsel bekletiliyor')
    fireEvent.click(slot('te-image-retry'))
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(3))
    expect(te().uploadChannelNoteImage.mock.calls[2][1]).toBe(file)
    expect(prepareImage).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(textarea().value).toContain('(te-note-img:78)'))
    expect(slot('te-image-kept')).toBeNull()
    expect(slot('te-note-img-problem')).toBeNull()
  })

  it.each([
    ['NOTE_IMAGE_LIMIT', 409, 'Bu notun görsel sayısı sınırına ulaşıldı.'],
    ['NOTE_IMAGE_TOTAL_LIMIT', 409, 'Bu notun görselleri toplam boyut sınırına ulaştı.'],
  ])('sınır reddi %s: ne yapılacağını söyleyen metin; görsel BEKLETİLMEZ, metin değişmez', async (code, status, text) => {
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage.mockResolvedValue({ success: false, status, code })       // sunucu metni yoksa çevrilmiş yedek
    renderEditor()
    fireEvent.change(fileInput(), { target: { files: [png()] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent(text))
    expect(slot('te-image-kept')).toBeNull()
    expect(textarea().value).toBe('Giriş\n\nSon')
    expect(rows()).toHaveLength(0)
    expect(slot('te-image-upload')).toBeEnabled()
  })

  it('sunucunun kendi metni varsa o yazar (sınır reddinde de)', async () => {
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage.mockResolvedValue({ success: false, status: 409, code: 'NOTE_IMAGE_LIMIT', error: 'Bu kanalın notunda 40 görsel sınırına ulaşıldı; kullanılmayan görselleri silin.' })
    renderEditor()
    fireEvent.change(fileInput(), { target: { files: [png()] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Bu kanalın notunda 40 görsel sınırına ulaşıldı; kullanılmayan görselleri silin.'))
  })

  it('hız sınırı (429): bekleme süresi söylenir, hazır görsel bekletilir; "Görseli bırak" vazgeçer', async () => {
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage.mockResolvedValue({ success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 12 })
    renderEditor()
    fireEvent.change(fileInput(), { target: { files: [png()] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Çok hızlı görsel yüklediniz. 12 saniye sonra yeniden deneyin.'))
    await waitFor(() => expect(slot('te-image-kept')).not.toBeNull())
    fireEvent.click(slot('te-image-kept-drop'))
    expect(slot('te-image-kept')).toBeNull()
    expect(slot('te-note-img-problem')).toBeNull()
  })

  it('eski pod (genel 404) ve ağ hatası: ne olduğunu söyleyen metin + hazır görsel bekletilir', async () => {
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage
      .mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'İstenen kaynak bulunamadı.' })
      .mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı. Bağlantınızı kontrol edin.'))
    renderEditor()
    fireEvent.change(fileInput(), { target: { files: [png()] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Sunucu güncelleniyor olabilir.'))
    expect(screen.queryByText('İstenen kaynak bulunamadı.')).toBeNull()
    await waitFor(() => expect(slot('te-image-kept')).not.toBeNull())
    fireEvent.click(slot('te-image-retry'))
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Sunucuya ulaşılamadı. Bağlantınızı kontrol edin.'))
    await waitFor(() => expect(slot('te-image-kept')).not.toBeNull())
    expect(prepareImage).toHaveBeenCalledTimes(1)
  })

  it('doğrulama reddi (400) bekletilmez; beklenmeyen hazırlama hatası "tarayıcıda işlenemedi" der', async () => {
    te().uploadChannelNoteImage.mockReset()
    te().uploadChannelNoteImage.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', error: 'Görsel 5 MB sınırını aşıyor.' })
    renderEditor()
    fireEvent.change(fileInput(), { target: { files: [png()] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Görsel 5 MB sınırını aşıyor.'))
    expect(slot('te-image-kept')).toBeNull()

    prepareImage.mockImplementationOnce(async () => { throw new RangeError('Array buffer allocation failed') })
    await waitFor(() => expect(slot('te-image-upload')).toBeEnabled())
    fireEvent.change(fileInput(), { target: { files: [png('buyuk.png')] } })
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Görsel bu tarayıcıda işlenemedi'))
    expect(document.body.textContent).not.toContain('Array buffer allocation failed')
    expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(1)
  })
})

describe('kanal notu — üç yol, aynı düğmeler', () => {
  it('pano ve ekran paylaşımı varken: Görsel yükle → Ekrandan al → Panodan yapıştır (rapor görsel yuvasıyla aynı sıra ve sınıf)', () => {
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getDisplayMedia: vi.fn() } })
    if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {} })
    navigator.clipboard.read = vi.fn()
    renderEditor()
    expect(mainActions()).toEqual(['Görsel yükle', 'Ekrandan al', 'Panodan yapıştır'])
    expect(slot('te-image-actions')).toHaveAttribute('data-count', '3')
    const buttons = [...slot('te-image-main-actions').querySelectorAll('button')]
    expect(buttons.map((b) => b.dataset.slot)).toEqual(['te-image-upload', 'te-image-snip', 'te-image-paste'])
    for (const b of buttons) for (const cls of SLOT_ACTION_CLASS.split(/\s+/).filter(Boolean)) expect(b.className).toContain(cls)
    // Düğme adları alanı da söyler (ekran okuyucu)
    expect(screen.getByRole('button', { name: 'Görsel yükle: Kanal notu' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ekrandan al: Kanal notu' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Panodan yapıştır: Kanal notu' })).toBeInTheDocument()
  })

  it('desteklenmeyen tarayıcıda yalnız "Görsel yükle" (bozuk düğme yok)', () => {
    renderEditor()
    expect(mainActions()).toEqual(['Görsel yükle'])
    expect(slot('te-image-actions')).toHaveAttribute('data-count', '1')
    expect(slot('te-image-snip')).toBeNull()
    expect(slot('te-image-paste')).toBeNull()
  })

  it('"Panodan yapıştır" panodaki görseli aynı hattan yükler', async () => {
    if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {} })
    navigator.clipboard.read = vi.fn(async () => [{ types: ['image/png'], getType: async () => new Blob([new Uint8Array([1, 2])], { type: 'image/png' }) }])
    renderEditor()
    fireEvent.click(slot('te-image-paste'))
    await waitFor(() => expect(te().uploadChannelNoteImage).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0].name).toMatch(/^kanal-notu-pano-\d{8}-\d{6}\.png$/)
    await waitFor(() => expect(textarea().value).toContain('(te-note-img:501)'))
  })
})

describe('kanal notu — "Bu notun görselleri"', () => {
  const IMAGES = [image(1, { linked: true }), image(2, { linked: false, file_name: 'kullanilmayan-ekran-goruntusu.png' }), image(3, { linked: true })]
  const BODY = 'A\n\n![bir](te-note-img:1)\n\n![üç](te-note-img:3)'

  it('yalnız KULLANILMAYAN görselde rozet ve "Sil"; taslaktan çıkarılan (kayıtlı metinde duran) görsel "kaydedince silinebilir" der', async () => {
    renderEditor({ body: BODY, images: IMAGES, image_bytes: 307200 })
    expect(rows()).toHaveLength(3)
    expect(slot('te-note-images-totals')).toHaveTextContent('3 / 40 görsel · 300 KB / 40 MB')
    expect(row(1)).toHaveAttribute('data-unused', 'false')
    expect(row(1).querySelector('[data-slot="te-note-image-delete"]')).toBeNull()
    expect(row(2)).toHaveAttribute('data-unused', 'true')
    expect(within(row(2)).getByText('Kullanılmıyor')).toBeInTheDocument()
    expect(within(row(2)).getByRole('button', { name: 'Sil: kullanilmayan-ekran-goruntusu.png' })).toBeInTheDocument()
    expect(row(3).querySelector('[data-slot="te-note-image-pending"]')).toBeNull()
    expect(screen.getByText('Metinde kullanılmayan görseller 30 gün sonra kendiliğinden silinir.')).toBeInTheDocument()
    // Küçük resimler aynı kökenli adresten; adlar kesilmez
    expect(row(2).querySelector('img').getAttribute('src')).toBe('/api/tech-error-reports/channel-notes/images/2')
    expect(row(2).innerHTML).not.toMatch(/\btruncate\b/)

    // Taslaktan 3 çıkarılır: kayıtlı metinde hâlâ başvurulu → silinemez, önce kaydedilmeli
    fireEvent.change(textarea(), { target: { value: 'A\n\n![bir](te-note-img:1)' } })
    await waitFor(() => expect(row(3).querySelector('[data-slot="te-note-image-pending"]')).not.toBeNull())
    expect(row(3)).toHaveTextContent('Taslaktan çıkarıldı. Notu kaydedince silinebilir.')
    expect(row(3).querySelector('[data-slot="te-note-image-delete"]')).toBeNull()
    expect(row(3)).toHaveAttribute('data-unused', 'false')
  })

  it('"Metne ekle" görselin markdown\'ını imlece ekler; eklenen görsel artık "kullanılmıyor" değildir', async () => {
    renderEditor({ body: 'Giriş\n\nSon', images: [image(2, { file_name: 'filtreler.png' })] })
    expect(row(2)).toHaveAttribute('data-unused', 'true')
    caretAt(5)
    fireEvent.click(within(row(2)).getByRole('button', { name: 'Metne ekle: filtreler.png' }))
    expect(textarea().value).toBe('Giriş\n\n![filtreler](te-note-img:2)\n\nSon')
    await waitFor(() => expect(row(2)).toHaveAttribute('data-unused', 'false'))
    expect(row(2).querySelector('[data-slot="te-note-image-delete"]')).toBeNull()
    expect(te().uploadChannelNoteImage).not.toHaveBeenCalled()
  })

  it('"Sil" onay ister; vazgeçilirse silinmez; onaylanınca silinir, liste ve toplam güncellenir', async () => {
    te().deleteChannelNoteImage.mockResolvedValue(ok({ deleted: true }))
    const { onImagesChange } = renderEditor({ body: BODY, images: IMAGES, image_bytes: 307200 })
    fireEvent.click(row(2).querySelector('[data-slot="te-note-image-delete"]'))
    let dlg = await screen.findByRole('dialog', { name: 'Görsel silinsin mi?' })
    expect(dlg).toHaveTextContent('“kullanilmayan-ekran-goruntusu.png” kalıcı olarak silinecek.')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Görsel silinsin mi?' })).toBeNull())
    expect(te().deleteChannelNoteImage).not.toHaveBeenCalled()
    expect(rows()).toHaveLength(3)

    fireEvent.click(row(2).querySelector('[data-slot="te-note-image-delete"]'))
    dlg = await screen.findByRole('dialog', { name: 'Görsel silinsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(te().deleteChannelNoteImage).toHaveBeenCalledWith(10, 2))
    await waitFor(() => expect(rows()).toHaveLength(2))
    expect(row(2)).toBeNull()
    expect(slot('te-note-images-totals')).toHaveTextContent('2 / 40 görsel · 200 KB / 40 MB')
    expect(await screen.findByText('“kullanilmayan-ekran-goruntusu.png” silindi.')).toBeInTheDocument()
    expect(onImagesChange).toHaveBeenLastCalledWith([expect.objectContaining({ id: 1 }), expect.objectContaining({ id: 3 })], 204800)
    // Görsel silmek notun metnini / kirli durumunu değiştirmez
    expect(slot('te-note-save')).toBeDisabled()
  })

  it('409 NOTE_IMAGE_IN_USE: ne yapılacağı söylenir ve liste sunucudakiyle tazelenir (görsel artık "kullanılıyor")', async () => {
    te().deleteChannelNoteImage.mockResolvedValue({ success: false, status: 409, code: 'NOTE_IMAGE_IN_USE' })
    te().channelNote.mockResolvedValue(ok(note({ body: BODY, images: [image(1, { linked: true }), image(2, { linked: true }), image(3, { linked: true })], image_bytes: 307200 })))
    renderEditor({ body: BODY, images: IMAGES, image_bytes: 307200 })
    fireEvent.click(row(2).querySelector('[data-slot="te-note-image-delete"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel silinsin mi?' })).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Bu görsel kayıtlı notta kullanılıyor. Önce metinden çıkarıp notu kaydedin, sonra silin.'))
    await waitFor(() => expect(te().channelNote).toHaveBeenCalledWith(10))
    await waitFor(() => expect(row(2).querySelector('[data-slot="te-note-image-delete"]')).toBeNull())
    expect(rows()).toHaveLength(3)
    // Taslak yeniden okunan nottan ETKİLENMEZ (yalnız görsel listesi tazelenir)
    expect(textarea().value).toBe(BODY)
  })

  it('silme hatası (ağ / 5xx) görseli listeden DÜŞÜRMEZ; hata alanın altında', async () => {
    te().deleteChannelNoteImage.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı.')).mockResolvedValueOnce({ success: false, status: 500 })
    renderEditor({ body: BODY, images: IMAGES })
    fireEvent.click(row(2).querySelector('[data-slot="te-note-image-delete"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel silinsin mi?' })).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Sunucuya ulaşılamadı.'))
    expect(rows()).toHaveLength(3)
    await waitFor(() => expect(row(2).querySelector('[data-slot="te-note-image-delete"]')).toBeEnabled())
    fireEvent.click(row(2).querySelector('[data-slot="te-note-image-delete"]'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Görsel silinsin mi?' })).getByRole('button', { name: 'Sil' }))
    await waitFor(() => expect(slot('te-note-img-problem')).toHaveTextContent('Görsel silinemedi.'))
    expect(rows()).toHaveLength(3)
    expect(te().channelNote).not.toHaveBeenCalled()
  })

  it('kayıt yanıtı görsel listesini (linked bayrakları) yeniler: kaydedilen görsel "kullanılıyor" olur', async () => {
    te().saveChannelNote.mockImplementation(async (_id, { body }) => ok(note({
      version: 5, body, images: [image(2, { linked: true, file_name: 'filtreler.png' })], image_bytes: 102400, changed: true,
    })))
    const { onSaved } = renderEditor({ body: 'Giriş', images: [image(2, { file_name: 'filtreler.png' })], image_bytes: 102400 })
    fireEvent.click(within(row(2)).getByRole('button', { name: 'Metne ekle: filtreler.png' }))
    await waitFor(() => expect(slot('te-note-save')).toBeEnabled())
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenCalledWith(10, { version: 4, body: 'Giriş\n\n![filtreler](te-note-img:2)\n' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    // Kayıttan sonra metinden çıkarılırsa "önce kaydet" der (artık kayıtlı metinde başvurulu)
    await waitFor(() => expect(slot('te-note-save')).toBeDisabled())
    fireEvent.change(textarea(), { target: { value: 'Giriş' } })
    await waitFor(() => expect(row(2).querySelector('[data-slot="te-note-image-pending"]')).not.toBeNull())
    expect(row(2).querySelector('[data-slot="te-note-image-delete"]')).toBeNull()
  })

  it('görsel yokken boş durum; düzenleme yetkisi yoksa düzenleyici hiç çizilmez', () => {
    const first = renderEditor()
    expect(slot('te-note-images-empty')).toHaveTextContent('Bu nota henüz görsel yüklenmedi.')
    expect(slot('te-note-images-totals')).toHaveTextContent('0 / 40 görsel · 0 KB / 40 MB')
    first.unmount()
    renderEditor({ can_edit: false })
    expect(slot('te-note-editor')).toBeNull()
    expect(slot('te-image-upload')).toBeNull()
  })
})
