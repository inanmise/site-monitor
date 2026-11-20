import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, templateResponse } from './helpers/techErrorFixtures.js'

/**
 * Kanal notları — bağımsız ön yüz incelemesinin düzeltmeleri (2026-11-20):
 *  - eski (çözülmemiş) yedek bandı açıkken yazılan taslak; çakışma, "en son hâli yükle", uç yokluğu ve ayrılmada
 *    KOŞULSUZ yedeklenir — düzenleyici yeniden kurulunca yazılan metin kaybolmaz, bant onu önerir;
 *  - kayıt sürerken yazılan ya da kaydedilemeyen taslağın yedeği kayıt bitince yeniden kurulur;
 *  - kayıt sürerken yüklenen görsel kayıt yanıtının (eski) listesiyle silinmez;
 *  - sürüm penceresi yeniden açılınca önceki seçimin ayrıntısını istemez;
 *  - kaydedilmemiş iş varken sekmeye odak dönmesi soruyu döngüye sokmaz (yalnız tıklama sekme değiştirir).
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
      reports: vi.fn(), template: vi.fn(), channelNote: vi.fn(), saveChannelNote: vi.fn(), uploadChannelNoteImage: vi.fn(),
      deleteChannelNoteImage: vi.fn(), channelNoteVersions: vi.fn(), channelNoteVersion: vi.fn(),
      channelNoteImageUrl: (id) => `/api/tech-error-reports/channel-notes/images/${id}`,
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { OWNER_KEY } from '../utils/personalStorage.js'
import ChannelView from '../components/techerror/ChannelView.jsx'
import ChannelNotesTab from '../components/techerror/notes/ChannelNotesTab.jsx'
import ChannelNoteEditor, { NOTE_BACKUP_DELAY_MS } from '../components/techerror/notes/ChannelNoteEditor.jsx'
import { loadNotePart } from '../components/techerror/notes/lazyParts.jsx'
import { readNoteBackup, writeNoteBackup } from '../components/techerror/notes/noteModel.js'

const te = () => api.techErrors
const CH = channel(10, 'Mobil', { can_read_notes: true, can_edit_notes: true })
const BODY = '# Veri kaynağı\n\nQlik › Kanal özeti ekranından alınır.'
const LIMITS = { max_chars: 60000, max_images: 40, max_image_bytes: 5242880, max_total_bytes: 41943040, unlinked_keep_days: 30, max_versions: 20 }
const OLD_BACKUP = 'Geçen haftadan kalmış ESKİ taslak'

function note(extra = {}) {
  return {
    channel_id: 10, exists: true, version: 4, body: BODY, char_count: BODY.length, updated_at: '2026-11-18T08:30:00Z',
    updated_by_name: 'Ayşe Yılmaz', created_at: '2026-11-01T08:00:00Z', images: [], image_bytes: 0, limits: LIMITS,
    can_edit: true, shared_access: false, ...extra,
  }
}
function image(id, extra = {}) {
  return {
    id, file_name: `gorsel-${id}.png`, content_type: 'image/png', width: 800, height: 400, size_bytes: 2048,
    uploaded_at: '2026-11-20T08:00:00Z', linked: false, ...extra,
  }
}
const ok = (data) => ({ success: true, status: 200, data })
const CONFLICT = {
  success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Kayıt siz düzenlerken değişti.',
  current_version: 5, updated_by_name: 'Can Demir', updated_at: '2026-11-20T09:00:00Z',
}
function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const textarea = () => screen.getByLabelText('Kanal notu (Markdown)')
const type = (value) => fireEvent.change(textarea(), { target: { value } })
const png = (name = 'ekran-qlik.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })

async function openTabEditor() {
  render(<ChannelNotesTab channelId={10} channel={CH} onDirtyChange={vi.fn()} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Düzenle' }))
  await screen.findByLabelText('Kanal notu (Markdown)')
}
function renderEditor(noteExtra = {}) {
  const handlers = { onSaved: vi.fn(), onImagesChange: vi.fn(), onDirtyChange: vi.fn(), onReloadLatest: vi.fn(), onClose: vi.fn() }
  const view = render(<ChannelNoteEditor channelId={10} note={note(noteExtra)} {...handlers} />)
  return { ...view, ...handlers }
}

beforeAll(async () => {
  await Promise.all([loadNotePart('view'), loadNotePart('editor')])
}, 120000)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  for (const fn of [te().channelNote, te().saveChannelNote, te().uploadChannelNoteImage, te().channelNoteVersions, te().channelNoteVersion]) fn.mockReset()
  te().channelNote.mockResolvedValue(ok(note()))
  prepareImage.mockReset()
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
})

describe('eski yedek bandı açıkken yazılan taslak kaybolmaz (inceleme #2)', () => {
  it('bant açık → yaz → 409: yedek YENİ metindir ve bant kapanır; "En son hâli yükle" sonrası bant yeni metni önerir', async () => {
    writeNoteBackup(10, { version: 3, body: OLD_BACKUP })
    te().saveChannelNote.mockResolvedValueOnce(CONFLICT)
    await openTabEditor()
    expect(slot('te-note-backup')).not.toBeNull()                       // eski taslak önerildi, kullanıcı yanıtlamadı
    type('Yeni yazdığım metin')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-conflict')).not.toBeNull())
    expect(readNoteBackup(10)).toMatchObject({ version: 4, body: 'Yeni yazdığım metin' })
    expect(slot('te-note-backup')).toBeNull()

    te().channelNote.mockResolvedValueOnce(ok(note({ version: 5, body: 'Can Demir\'in metni', updated_by_name: 'Can Demir' })))
    fireEvent.click(slot('te-note-load-latest'))
    await waitFor(() => expect(textarea().value).toBe('Can Demir\'in metni'))
    // Düzenleyici yeniden kuruldu: bant ESKİ taslağı değil, az önce yazılan metni önerir
    expect(slot('te-note-backup')).not.toBeNull()
    fireEvent.click(slot('te-note-backup-restore'))
    expect(textarea().value).toBe('Yeni yazdığım metin')
  })

  it('bant açık → yaz → uç yok (eski pod): yedek yeni metindir', async () => {
    writeNoteBackup(10, { version: 3, body: OLD_BACKUP })
    te().saveChannelNote.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'İstenen kaynak bulunamadı.' })
    await openTabEditor()
    type('Uç yokken yazılan metin')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-unavailable')).not.toBeNull())
    expect(readNoteBackup(10)).toMatchObject({ body: 'Uç yokken yazılan metin' })
    expect(slot('te-note-backup')).toBeNull()
  })

  it('bant açık → yaz → kaydetmeden ayrıl: yedek yeni metindir; dönüşte bant onu önerir', async () => {
    localStorage.setItem(OWNER_KEY, 'ayse.yilmaz')
    writeNoteBackup(10, { version: 3, body: OLD_BACKUP })
    await openTabEditor()
    type('Ayrılırken yazdığım metin')
    fireEvent.click(slot('te-note-close'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).getByRole('button', { name: 'Kaydetmeden ayrıl' }))
    await waitFor(() => expect(document.querySelector('[data-slot="te-notes-tab"]')).toHaveAttribute('data-state', 'read'))
    expect(readNoteBackup(10)).toMatchObject({ body: 'Ayrılırken yazdığım metin' })
    fireEvent.click(screen.getByRole('button', { name: 'Düzenle' }))
    await screen.findByLabelText('Kanal notu (Markdown)')
    fireEvent.click(slot('te-note-backup-restore'))
    expect(textarea().value).toBe('Ayrılırken yazdığım metin')
  })

  it('bant açıkken hiçbir şey yazılmadan ayrılmak eski taslağı silmez', async () => {
    localStorage.setItem(OWNER_KEY, 'ayse.yilmaz')
    writeNoteBackup(10, { version: 3, body: OLD_BACKUP })
    await openTabEditor()
    fireEvent.click(slot('te-note-close'))
    await waitFor(() => expect(document.querySelector('[data-slot="te-notes-tab"]')).toHaveAttribute('data-state', 'read'))
    expect(readNoteBackup(10)).toMatchObject({ body: OLD_BACKUP })
  })
})

describe('kayıt çevresinde yedek boşluğu yok (inceleme #3)', () => {
  it('başarılı kayıt sürerken yazılan metin kayıttan sonra yeniden yedeklenir', async () => {
    const pending = deferred()
    te().saveChannelNote.mockReturnValueOnce(pending.promise)
    renderEditor()
    type('A')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-status')).toHaveTextContent('Kaydediliyor…'))
    type('AB')
    // Kayıt sürerken yazılan metnin yedeği zamanında yazılır; kayıt yanıtı gelince bu yedek silinir…
    await waitFor(() => expect(readNoteBackup(10)).toMatchObject({ version: 4, body: 'AB' }), { timeout: NOTE_BACKUP_DELAY_MS + 2000 })
    await act(async () => { pending.resolve(ok(note({ version: 5, body: 'A', changed: true }))) })
    await waitFor(() => expect(slot('te-note-status')).toHaveTextContent('Kaydedilmemiş değişiklikler var'))
    expect(textarea().value).toBe('AB')
    // …ama taslak hâlâ kaydedilmemiş: yedek yeni sürümün üzerine yeniden yazılır (eskiden yedeksiz kalıyordu)
    await waitFor(() => expect(readNoteBackup(10)).toMatchObject({ version: 5, body: 'AB' }), { timeout: NOTE_BACKUP_DELAY_MS + 2000 })
  })

  it('kaydedilemeyen (ağ hatası) taslağın yedeği kayıt bitince yeniden kurulur', async () => {
    te().saveChannelNote.mockRejectedValueOnce(Object.assign(new Error('Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.'), { status: 0 }))
    renderEditor()
    type('Ağ kopukken yazılan')
    // Kayıt, yedek zamanlayıcısı dolmadan başlar (zamanlayıcıyı iptal eder)
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-save-problem')).not.toBeNull())
    await waitFor(() => expect(readNoteBackup(10)).toMatchObject({ version: 4, body: 'Ağ kopukken yazılan' }), { timeout: NOTE_BACKUP_DELAY_MS + 2000 })
  })
})

describe('kayıt sürerken yüklenen görsel listeden düşmez (inceleme #4)', () => {
  it('kayıt yanıtında olmayan, kayıt sırasında eklenen görsel listede kalır; toplam ve üst bileşen birleşik listeyi alır', async () => {
    const pending = deferred()
    te().saveChannelNote.mockReturnValueOnce(pending.promise)
    te().uploadChannelNoteImage.mockResolvedValueOnce(ok({ image: image(77, { file_name: 'kayit-sirasinda.png' }), ref: 'te-note-img:77' }))
    const { onImagesChange } = renderEditor({ images: [image(5, { linked: true })], image_bytes: 2048 })
    type('Giriş metni')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-status')).toHaveTextContent('Kaydediliyor…'))
    // Kayıt sürerken görsel yüklenir ve listeye girer
    const input = slot('te-image-actions').querySelector('input[type="file"]')
    fireEvent.change(input, { target: { files: [png('kayit-sirasinda.png')] } })
    await waitFor(() => expect(document.querySelector('[data-slot="te-note-image-row"][data-image-id="77"]')).not.toBeNull())
    // Yanıt kayıt anında okunmuş listeyi taşır (77 yok)
    await act(async () => { pending.resolve(ok(note({ version: 5, body: 'Giriş metni', images: [image(5, { linked: true })], image_bytes: 2048, changed: true }))) })
    await waitFor(() => expect(slot('te-note-status')).toHaveTextContent('Kaydedilmemiş değişiklikler var'))
    expect(document.querySelector('[data-slot="te-note-image-row"][data-image-id="77"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="te-note-image-row"][data-image-id="5"]')).not.toBeNull()
    expect(slot('te-note-images-totals')).toHaveTextContent('2 / 40 görsel · 4 KB / 40 MB')
    const [list, bytes] = onImagesChange.mock.calls.at(-1)
    expect(list.map((i) => i.id)).toEqual([77, 5])
    expect(bytes).toBe(4096)
  })
})

describe('sürüm penceresi yeniden açılınca gereksiz istek yok (inceleme #9)', () => {
  const VERSIONS = [
    { version: 4, saved_at: '2026-11-18T08:30:00Z', saved_by_name: 'Ayşe Yılmaz', char_count: 1, image_count: 1 },
    { version: 3, saved_at: '2026-11-10T08:30:00Z', saved_by_name: 'Can Demir', char_count: 21, image_count: 2 },
  ]
  it('eski sürüm seçilip kapatılırsa yeniden açılışta yalnız gösterilen (en yeni) sürüm istenir; sayımlar tekil / çoğul', async () => {
    te().channelNoteVersions.mockResolvedValue(ok({ versions: VERSIONS, max_versions: 20 }))
    te().channelNoteVersion.mockImplementation(async (_id, v) => ok({ version: v, body: `Sürüm ${v} metni`, saved_at: '2026-11-10T08:30:00Z', saved_by_name: 'Can Demir' }))
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    render(<ChannelNotesTab channelId={10} channel={CH} onDirtyChange={vi.fn()} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Version history' }))
    let dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(te().channelNoteVersion).toHaveBeenCalledWith(10, 4))
    const counts = [...dlg.querySelectorAll('[data-slot="te-note-version-counts"]')].map((el) => el.textContent)
    expect(counts).toEqual(['1 character · 1 image', '21 characters · 2 images'])
    fireEvent.click(dlg.querySelector('[data-slot="te-note-version-item"][data-version="3"]'))
    await waitFor(() => expect(te().channelNoteVersion).toHaveBeenCalledWith(10, 3))
    fireEvent.keyDown(dlg, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    te().channelNoteVersion.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Version history' }))
    dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-version-preview"]')).not.toBeNull())
    expect(te().channelNoteVersion.mock.calls).toEqual([[10, 4]])
  })
})

describe('kanal sayfası — kaydedilmemiş işte sekmeye odak dönmesi soruyu döngüye sokmaz', () => {
  function renderView() {
    te().reports.mockResolvedValue({ success: true, data: { channel: CH, reports: [], default_period: { year: 2026, month: 9 } } })
    te().template.mockResolvedValue(templateResponse(CH))
    const handlers = { onTabChange: vi.fn(), onBack: vi.fn(), onOpenReport: vi.fn() }
    render(<ChannelView channelId="10" tab="notes" {...handlers} />)
    return handlers
  }

  it('kirliyken sekmenin odak alması sekme değiştirmez ve sormaz; tıklama sorar, "Sayfada kal" bir kez kapanır', async () => {
    const v = renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Düzenle' }))
    await screen.findByLabelText('Kanal notu (Markdown)')
    type('Kaydedilmemiş not')
    await waitFor(() => expect(slot('te-note-editor')).toHaveAttribute('data-dirty', 'true'))
    const reports = screen.getByRole('tab', { name: /Raporlar/ })
    act(() => { reports.focus() })
    await act(async () => {})
    expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull()
    expect(v.onTabChange).not.toHaveBeenCalled()

    fireEvent.mouseDown(reports, { button: 0 })
    const dlg = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Sayfada kal' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    await act(async () => {})
    expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull()
    expect(v.onTabChange).not.toHaveBeenCalled()
  })
})
