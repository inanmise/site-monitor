import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, templateResponse } from './helpers/techErrorFixtures.js'

/**
 * Kanal notları (2026-11-20) — kanal sayfasındaki "Notlar" sekmesi ve düzenleyici:
 *  - sekme yalnız `can_read_notes !== false` iken görünür; uçlar yalnız o zaman çağrılır;
 *  - okunamayan not hiçbir zaman "not yok" sayılmaz (eski pod = "geçici olarak kullanılamıyor", hata = "okunamadı");
 *  - açık "Kaydet" (otomatik kayıt yok), sürümle gönderilir; 409'da taslak korunur + "En son hâli yükle";
 *  - kayıt sürerken yazılan metin kaybolmaz; kaydedilmemiş taslak tarayıcıda yedeklenir, KENDİLİĞİNDEN uygulanmaz;
 *  - ayrılma kapısı (sekme değişimi, düzenlemeyi kapatma) kaydedilmemiş taslakta sorar;
 *  - sürüm geçmişi: liste → önizleme → "Düzenleyiciye yükle" (şimdiki sürümün üzerine taslak).
 * Ağır markdown editörü yerine düz bir textarea (kütüphanenin çizimi değil, düzenleyicinin davranışı sınanır).
 */
vi.mock('../components/ui/mdEditor/LazyMdEditor.jsx', () => ({
  default: function FakeMdEditor(props) {
    const tp = props.textareaProps || {}
    return (
      <textarea aria-label={tp['aria-label'] || 'md'} data-slot={tp['data-slot']} placeholder={tp.placeholder}
        value={props.value} onChange={(e) => props.onChange(e.target.value)} />
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
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { OWNER_KEY } from '../utils/personalStorage.js'
import ChannelView from '../components/techerror/ChannelView.jsx'
import ChannelNotesTab from '../components/techerror/notes/ChannelNotesTab.jsx'
import { loadNotePart } from '../components/techerror/notes/lazyParts.jsx'
import { noteBackupKey, readNoteBackup, writeNoteBackup } from '../components/techerror/notes/noteModel.js'

const te = () => api.techErrors
const CH = channel(10, 'Mobil', { can_read_notes: true, can_edit_notes: true })
const BODY = '# Veri kaynağı\n\nQlik › Kanal özeti ekranından alınır.'
const LIMITS = { max_chars: 60000, max_images: 40, max_image_bytes: 5242880, max_total_bytes: 41943040, unlinked_keep_days: 30, max_versions: 20 }

function note(extra = {}) {
  return {
    channel_id: 10, exists: true, version: 4, body: BODY, char_count: BODY.length, updated_at: '2026-11-18T08:30:00Z',
    updated_by_name: 'Ayşe Yılmaz', created_at: '2026-11-01T08:00:00Z', images: [], image_bytes: 0, limits: LIMITS,
    can_edit: true, shared_access: false, ...extra,
  }
}
const ok = (data) => ({ success: true, status: 200, data })
const fail = (status, code, error = 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.') => ({ success: false, status, code, error })
function deferred() {
  let resolve
  const promise = new Promise((res) => { resolve = res })
  return { promise, resolve }
}

const tab = () => document.querySelector('[data-slot="te-notes-tab"]')
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const textarea = () => screen.getByLabelText('Kanal notu (Markdown)')
const type = (value) => fireEvent.change(textarea(), { target: { value } })

function renderTab(props = {}) {
  const onDirtyChange = vi.fn()
  const view = render(<ChannelNotesTab channelId={10} channel={CH} onDirtyChange={onDirtyChange} {...props} />)
  return { ...view, onDirtyChange }
}
async function openEditor(props) {
  const view = renderTab(props)
  fireEvent.click(await screen.findByRole('button', { name: 'Düzenle' }))
  await screen.findByLabelText('Kanal notu (Markdown)')
  return view
}

// Tembel parçalar (görüntüleyici + düzenleyici) bir kez iner; testler soğuk parça süresine takılmasın
beforeAll(async () => {
  await Promise.all([loadNotePart('view'), loadNotePart('editor')])
}, 120000)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  te().channelNote.mockReset()
  te().saveChannelNote.mockReset()
  te().channelNoteVersions.mockReset()
  te().channelNoteVersion.mockReset()
  te().channelNote.mockResolvedValue(ok(note()))
})

describe('Notlar sekmesi — okuma kipi ve durumlar', () => {
  it('notu kilitli görüntüleyiciyle çizer; son güncelleme satırı, "Düzenle" ve "Sürüm geçmişi"', async () => {
    renderTab()
    expect(tab()).toHaveAttribute('data-state', 'loading')
    expect(await screen.findByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'read')
    expect(te().channelNote).toHaveBeenCalledTimes(1)
    expect(te().channelNote).toHaveBeenCalledWith(10)
    // Notun kendi başlığı bir düzey iner: sekmenin "Kanal notları" h3'ünün altında h3 olarak kalır (h1 çizilmez)
    expect(screen.getByRole('heading', { level: 3, name: 'Veri kaynağı' })).toBeInTheDocument()
    expect(tab().querySelector('h1')).toBeNull()
    expect(slot('te-note-meta').textContent).toMatch(/^Son güncelleme: 18\.11\.2026 \d{2}:\d{2} · Ayşe Yılmaz · sürüm 4$/)
    expect(screen.getByRole('button', { name: 'Düzenle' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sürüm geçmişi' })).toBeInTheDocument()
    expect(slot('te-note-shared')).toBeNull()
    // Okuma kipinde düzenleyici çizilmez
    expect(screen.queryByLabelText('Kanal notu (Markdown)')).toBeNull()
  })

  it('paylaşımla okuyan: "Düzenle" yok, "salt okunur" rozeti; sürüm geçmişinde "Düzenleyiciye yükle" yok', async () => {
    te().channelNote.mockResolvedValue(ok(note({ can_edit: false, shared_access: true })))
    te().channelNoteVersions.mockResolvedValue(ok({ versions: [{ version: 4, saved_at: '2026-11-18T08:30:00Z', saved_by_name: 'Ayşe Yılmaz', char_count: 50, image_count: 0 }], max_versions: 20 }))
    te().channelNoteVersion.mockResolvedValue(ok({ version: 4, body: BODY, saved_at: '2026-11-18T08:30:00Z', saved_by_name: 'Ayşe Yılmaz' }))
    renderTab({ channel: channel(10, 'Mobil', { can_read_notes: true, can_edit_notes: false, shared_access: true }) })
    await screen.findByText('Qlik › Kanal özeti ekranından alınır.')
    expect(screen.queryByRole('button', { name: 'Düzenle' })).toBeNull()
    expect(slot('te-note-shared')).toHaveTextContent('Paylaşımla erişim — salt okunur')
    fireEvent.click(screen.getByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-version-preview"]')).not.toBeNull())
    expect(within(dlg).queryByRole('button', { name: 'Düzenleyiciye yükle' })).toBeNull()
  })

  it('not yokken: düzenleyebilen "Not yaz" + yol gösteren maddeleri görür; okuyan yalnız "henüz not yok"; sürüm geçmişi düğmesi yok', async () => {
    te().channelNote.mockResolvedValue(ok(note({ exists: false, version: 0, body: '', updated_at: null, updated_by_name: null })))
    const first = renderTab()
    expect(await screen.findByText('Bu kanal için henüz not yok')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'empty')
    expect(screen.getByRole('button', { name: 'Not yaz' })).toBeInTheDocument()
    expect(slot('te-note-guide').querySelectorAll('li')).toHaveLength(3)
    expect(screen.queryByRole('button', { name: 'Sürüm geçmişi' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Düzenle' })).toBeNull()
    first.unmount()

    te().channelNote.mockResolvedValue(ok(note({ exists: false, version: 0, body: '', can_edit: false })))
    renderTab()
    expect(await screen.findByText(/Kanalın takımı henüz bir not yazmadı/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Not yaz' })).toBeNull()
    expect(slot('te-note-guide')).toBeNull()
  })

  it('metni boş (yalnız boşluk) kayıtlı not da "henüz not yok"tur ama sürüm geçmişi açılabilir', async () => {
    te().channelNote.mockResolvedValue(ok(note({ body: '  \n ' })))
    renderTab()
    expect(await screen.findByText('Bu kanal için henüz not yok')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sürüm geçmişi' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Not yaz' })).toBeInTheDocument()
  })

  it('"Not yaz" boş düzenleyiciyi açar; ilk kayıt sürüm 0 ile gider', async () => {
    te().channelNote.mockResolvedValue(ok(note({ exists: false, version: 0, body: '', updated_at: null, updated_by_name: null })))
    te().saveChannelNote.mockImplementation(async (_id, { body }) => ok(note({ version: 1, body, changed: true })))
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'Not yaz' }))
    expect((await screen.findByLabelText('Kanal notu (Markdown)')).value).toBe('')
    expect(slot('te-note-secrets-hint')).toHaveTextContent('Parola, belirteç (token) ya da müşteri verisi yazmayın.')
    type('İlk not')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenCalledWith(10, { version: 0, body: 'İlk not' }))
  })

  it('can_read_notes === false: uçlar HİÇ çağrılmaz, "bu paylaşımla görünmüyor" durumu', async () => {
    renderTab({ channel: channel(10, 'Mobil', { can_read_notes: false, shared_access: true }) })
    expect(tab()).toHaveAttribute('data-state', 'hidden')
    expect(screen.getByText('Notlar bu paylaşımla görünmüyor')).toBeInTheDocument()
    await act(async () => {})
    expect(te().channelNote).not.toHaveBeenCalled()
  })

  it('403 (sunucu notları paylaşmıyor) da gizli durumdur — "not yok" denmez', async () => {
    te().channelNote.mockResolvedValue(fail(403, 'CHANNEL_NOTES_NOT_SHARED', 'Kanal notları bu paylaşımla görüntülenemez.'))
    renderTab()
    await waitFor(() => expect(tab()).toHaveAttribute('data-state', 'hidden'))
    expect(screen.queryByText('Bu kanal için henüz not yok')).toBeNull()
  })

  it.each([
    ['genel 404 (bilinmeyen /api yolu)', fail(404, 'NOT_FOUND', 'İstenen kaynak bulunamadı.')],
    ['kodsuz 404', fail(404, undefined, 'İstenen kaynak bulunamadı.')],
    ['405', fail(405, 'METHOD_NOT_ALLOWED', 'Bu işlem desteklenmiyor.')],
  ])('eski pod (%s): "geçici olarak kullanılamıyor" — sunucunun genel metni yazılmaz; "Tekrar dene" okur', async (_ad, res) => {
    te().channelNote.mockResolvedValueOnce(res).mockResolvedValueOnce(ok(note()))
    renderTab()
    expect(await screen.findByText('Notlar geçici olarak kullanılamıyor')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'unsupported')
    expect(screen.queryByText(res.error)).toBeNull()
    expect(screen.queryByText('Bu kanal için henüz not yok')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(te().channelNote).toHaveBeenCalledTimes(2)
  })

  it('kodlu 404 CHANNEL_NOT_FOUND: "kanal bulunamadı" (eski pod sayılmaz, yeniden dene yok)', async () => {
    te().channelNote.mockResolvedValue(fail(404, 'CHANNEL_NOT_FOUND', 'Kanal bulunamadı.'))
    renderTab()
    expect(await screen.findByText('Kanal bulunamadı ya da görme yetkiniz yok')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'notFound')
    expect(screen.queryByRole('button', { name: 'Tekrar dene' })).toBeNull()
  })

  it('okuma hatası (5xx / ağ / reddedilen söz) "not yok" değildir: hata + sunucunun metni + "Tekrar dene"', async () => {
    te().channelNote.mockResolvedValueOnce(fail(503, 'DB_UNAVAILABLE')).mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı.')).mockResolvedValueOnce(ok(note()))
    renderTab()
    expect(await screen.findByText('Kanal notu okunamadı')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'failed')
    expect(screen.getByText('Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.')).toBeInTheDocument()
    expect(screen.queryByText('Bu kanal için henüz not yok')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Not yaz' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByText('Sunucuya ulaşılamadı.')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'failed')
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
  })

  it('başarılı ama bozuk yanıt (data yok) da hatadır', async () => {
    te().channelNote.mockResolvedValue({ success: true, status: 200, data: null })
    renderTab()
    await waitFor(() => expect(tab()).toHaveAttribute('data-state', 'failed'))
    expect(screen.queryByText('Bu kanal için henüz not yok')).toBeNull()
  })

  it('geç gelen yanıt atılır: kanal değişince eski kanalın notu yenisinin üzerine yazılmaz', async () => {
    const slow = deferred()
    te().channelNote.mockImplementation((id) => (id === 10 ? slow.promise : Promise.resolve(ok(note({ channel_id: 20, body: 'POS kanalının notu' })))))
    const view = renderTab()
    view.rerender(<ChannelNotesTab channelId={20} channel={channel(20, 'POS', { can_read_notes: true })} onDirtyChange={view.onDirtyChange} />)
    expect(await screen.findByText('POS kanalının notu')).toBeInTheDocument()
    await act(async () => { slow.resolve(ok(note({ body: 'Mobil kanalının GEÇ gelen notu' }))) })
    expect(screen.getByText('POS kanalının notu')).toBeInTheDocument()
    expect(screen.queryByText('Mobil kanalının GEÇ gelen notu')).toBeNull()
  })

  it('StrictMode (kök): çift etki notu bir kez gösterir, yüklenmede takılmaz', async () => {
    render(<ChannelNotesTab channelId={10} channel={CH} onDirtyChange={() => {}} />, { reactStrictMode: true })
    expect(await screen.findByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(tab()).toHaveAttribute('data-state', 'read')
    expect(screen.getAllByText('Qlik › Kanal özeti ekranından alınır.')).toHaveLength(1)
  })
})

describe('Not düzenleyici — kayıt', () => {
  it('Düzenle → yaz → Kaydet: sürümle gönderilir; temizken Kaydet kapalı; kapatınca okuma kipi yeni hâli gösterir (yeniden okuma yok)', async () => {
    te().saveChannelNote.mockImplementation(async (_id, { version, body }) => ok(note({
      version: version + 1, body, updated_by_name: 'Can Demir', updated_at: '2026-11-20T09:00:00Z', changed: true,
    })))
    const { onDirtyChange } = await openEditor()
    expect(tab()).toHaveAttribute('data-state', 'edit')
    expect(textarea().value).toBe(BODY)
    expect(slot('te-note-save')).toBeDisabled()
    expect(slot('te-note-discard')).toBeDisabled()
    expect(slot('te-note-status')).toHaveTextContent('Tüm değişiklikler kaydedildi')
    expect(slot('te-note-chars')).toHaveTextContent(`${BODY.length} / 60.000 karakter`)

    type(`${BODY}\n\nYeni adım.`)
    expect(slot('te-note-save')).toBeEnabled()
    expect(slot('te-note-status')).toHaveTextContent('Kaydedilmemiş değişiklikler var')
    expect(slot('te-note-editor')).toHaveAttribute('data-dirty', 'true')
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(true))

    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenCalledTimes(1))
    expect(te().saveChannelNote).toHaveBeenCalledWith(10, { version: 4, body: `${BODY}\n\nYeni adım.` })
    expect(await screen.findByText('Not kaydedildi.')).toBeInTheDocument()
    await waitFor(() => expect(slot('te-note-save')).toBeDisabled())
    expect(slot('te-note-status')).toHaveTextContent('Tüm değişiklikler kaydedildi')
    await waitFor(() => expect(onDirtyChange).toHaveBeenLastCalledWith(false))
    expect(readNoteBackup(10)).toBeNull()

    // Temiz düzenleyici sormadan kapanır
    fireEvent.click(slot('te-note-close'))
    await waitFor(() => expect(tab()).toHaveAttribute('data-state', 'read'))
    expect(screen.getByText('Yeni adım.')).toBeInTheDocument()
    expect(slot('te-note-meta').textContent).toMatch(/Can Demir · sürüm 5$/)
    expect(te().channelNote).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Düzenle' })).toHaveFocus())
  })

  it('Ctrl+S ve ⌘+S kaydeder; temizken göndermez; satır sonları LF olarak gider', async () => {
    te().saveChannelNote.mockImplementation(async (_id, { version, body }) => ok(note({ version: version + 1, body, changed: true })))
    await openEditor()
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true })
    await act(async () => {})
    expect(te().saveChannelNote).not.toHaveBeenCalled()

    type('Satır 1\r\nSatır 2')
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true })
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenCalledWith(10, { version: 4, body: 'Satır 1\nSatır 2' }))
    await waitFor(() => expect(slot('te-note-save')).toBeDisabled())

    type('Satır 1\nSatır 2\nSatır 3')
    fireEvent.keyDown(textarea(), { key: 'S', metaKey: true })
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenLastCalledWith(10, { version: 5, body: 'Satır 1\nSatır 2\nSatır 3' }))
    // Ctrl+Shift+S / Ctrl+Alt+S kayıt DEĞİLDİR
    await waitFor(() => expect(slot('te-note-save')).toBeDisabled())
    type('başka')
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true, shiftKey: true })
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true, altKey: true })
    await act(async () => {})
    expect(te().saveChannelNote).toHaveBeenCalledTimes(2)
  })

  it('değişiklik yoksa sunucu "changed: false" der: "yeni sürüm oluşturulmadı" bildirimi', async () => {
    te().saveChannelNote.mockResolvedValue(ok(note({ body: `${BODY}\n`, changed: false })))
    await openEditor()
    type(`${BODY}\n`)
    fireEvent.click(slot('te-note-save'))
    expect(await screen.findByText('Not zaten bu hâliyle kayıtlı; yeni sürüm oluşturulmadı.')).toBeInTheDocument()
    expect(screen.queryByText('Not kaydedildi.')).toBeNull()
  })

  it('kayıt sürerken yazılan metin kaybolmaz; sonraki kayıt YENİ sürümün üzerine gider', async () => {
    const pending = deferred()
    te().saveChannelNote.mockReturnValueOnce(pending.promise)
    await openEditor()
    type('A')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-status')).toHaveTextContent('Kaydediliyor…'))
    // Kayıt sürerken ikinci kayıt başlamaz
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true })
    type('AB')
    await act(async () => { pending.resolve(ok(note({ version: 5, body: 'A', changed: true }))) })
    await waitFor(() => expect(slot('te-note-status')).toHaveTextContent('Kaydedilmemiş değişiklikler var'))
    expect(textarea().value).toBe('AB')
    expect(te().saveChannelNote).toHaveBeenCalledTimes(1)
    te().saveChannelNote.mockResolvedValueOnce(ok(note({ version: 6, body: 'AB', changed: true })))
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenLastCalledWith(10, { version: 5, body: 'AB' }))
  })

  it('409 çakışma: kim / ne zaman kaydettiği söylenir, taslak korunur ve yedeklenir; "En son hâli yükle" sonrası taslak geri yüklenip kaydedilir', async () => {
    te().saveChannelNote.mockResolvedValueOnce({
      success: false, status: 409, code: 'CONCURRENT_MODIFICATION', error: 'Kayıt siz düzenlerken değişti.',
      current_version: 5, updated_by_name: 'Can Demir', updated_at: '2026-11-20T09:00:00Z',
    })
    await openEditor()
    type('Benim taslağım')
    fireEvent.click(slot('te-note-save'))
    const banner = await waitFor(() => {
      const el = slot('te-note-conflict')
      expect(el).not.toBeNull()
      return el
    })
    expect(banner).toHaveTextContent('Not siz düzenlerken değişti')
    expect(banner.textContent).toMatch(/Can Demir, 20\.11\.2026 \d{2}:\d{2} tarihinde bu notu kaydetti; değişikliğiniz kaydedilmedi/)
    expect(textarea().value).toBe('Benim taslağım')
    expect(slot('te-note-save')).toBeDisabled()                       // en son hâl yüklenmeden üzerine yazılamaz
    expect(slot('te-note-status')).toHaveTextContent('Not başkası tarafından değiştirildi — kaydedilmedi')
    expect(readNoteBackup(10)).toMatchObject({ version: 4, body: 'Benim taslağım' })
    // Ctrl+S de göndermez
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true })
    await act(async () => {})
    expect(te().saveChannelNote).toHaveBeenCalledTimes(1)

    te().channelNote.mockResolvedValueOnce(ok(note({ version: 5, body: 'Can Demir\'in metni', updated_by_name: 'Can Demir' })))
    fireEvent.click(slot('te-note-load-latest'))
    await waitFor(() => expect(textarea().value).toBe('Can Demir\'in metni'))
    expect(slot('te-note-conflict')).toBeNull()
    // Taslak kendiliğinden uygulanmaz: "geri yükle" önerilir (daha eski sürümün üzerine yazıldığı söylenir)
    const backup = slot('te-note-backup')
    expect(backup).toHaveTextContent('Kaydedilmemiş bir taslak bulundu')
    expect(backup).toHaveTextContent('DAHA ESKİ')
    fireEvent.click(slot('te-note-backup-restore'))
    expect(textarea().value).toBe('Benim taslağım')
    expect(slot('te-note-backup')).toBeNull()

    te().saveChannelNote.mockResolvedValueOnce(ok(note({ version: 6, body: 'Benim taslağım', changed: true })))
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenLastCalledWith(10, { version: 5, body: 'Benim taslağım' }))
    await waitFor(() => expect(readNoteBackup(10)).toBeNull())
  })

  it('çakışmada kim / ne zaman bilinmiyorsa genel metin; "Değişiklikleri geri al" çakışmayı kapatır', async () => {
    te().saveChannelNote.mockResolvedValueOnce({ success: false, status: 409, error: 'Kayıt siz düzenlerken değişti.' })
    await openEditor()
    type('taslak')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-conflict')).not.toBeNull())
    expect(slot('te-note-conflict')).toHaveTextContent('Başka biri bu notu kaydetti; değişikliğiniz kaydedilmedi.')
    fireEvent.click(slot('te-note-discard'))
    expect(textarea().value).toBe(BODY)
    expect(slot('te-note-conflict')).toBeNull()
    expect(slot('te-note-save')).toBeDisabled()
  })

  it('uç yoksa (eski pod) kayıt "geçici olarak kullanılamıyor" der, taslak korunur ve yedeklenir; kanal kalktıysa ayrı metin', async () => {
    te().saveChannelNote
      .mockResolvedValueOnce(fail(404, 'NOT_FOUND', 'İstenen kaynak bulunamadı.'))
      .mockResolvedValueOnce(fail(404, 'CHANNEL_NOT_FOUND', 'Kanal bulunamadı.'))
    await openEditor()
    type('Kaybolmaması gereken taslak')
    fireEvent.click(slot('te-note-save'))
    const box = await waitFor(() => {
      const el = slot('te-note-unavailable')
      expect(el).not.toBeNull()
      return el
    })
    expect(box).toHaveAttribute('data-kind', 'old')
    expect(box).toHaveTextContent('Notlar geçici olarak kullanılamıyor')
    expect(box).toHaveTextContent('sunucu güncelleniyor olabilir')
    expect(screen.queryByText('İstenen kaynak bulunamadı.')).toBeNull()
    expect(textarea().value).toBe('Kaybolmaması gereken taslak')
    expect(readNoteBackup(10)).toMatchObject({ body: 'Kaybolmaması gereken taslak' })
    expect(slot('te-note-save')).toBeEnabled()                        // birkaç dakika sonra yeniden kaydedilebilir

    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-unavailable')).toHaveAttribute('data-kind', 'gone'))
    expect(slot('te-note-unavailable')).toHaveTextContent('kanal bulunamadı ya da düzenleme yetkiniz kalktı')
    expect(textarea().value).toBe('Kaybolmaması gereken taslak')
  })

  it('sunucunun `body` alan hatası alanın altında; başka hata ayrı bantta — ikisinde de taslak durur', async () => {
    te().saveChannelNote
      .mockResolvedValueOnce({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'body', fields: { body: 'x' }, error: 'Not en çok 60.000 karakter olabilir.' })
      .mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR', 'Beklenmeyen bir sorun oluştu; birkaç dakika sonra yeniden deneyin.'))
      .mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı.'))
    await openEditor()
    type('taslak')
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-body-problem')).toHaveTextContent('Not en çok 60.000 karakter olabilir.'))
    expect(slot('te-note-save-problem')).toBeNull()

    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-save-problem')).not.toBeNull())
    expect(slot('te-note-save-problem')).toHaveTextContent('Not kaydedilemedi; taslağınız duruyor')
    expect(slot('te-note-save-problem')).toHaveTextContent('Beklenmeyen bir sorun oluştu')
    expect(slot('te-note-body-problem')).toBeNull()

    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(slot('te-note-save-problem')).toHaveTextContent('Sunucuya ulaşılamadı.'))
    expect(textarea().value).toBe('taslak')
    expect(slot('te-note-save')).toBeEnabled()
  })

  it('metin sınırı aşılınca Kaydet kapanır, sayaç ve satır içi uyarı söyler (Ctrl+S de göndermez)', async () => {
    te().channelNote.mockResolvedValue(ok(note({ limits: { ...LIMITS, max_chars: 10 }, body: 'kısa' })))
    await openEditor()
    type('12345678901')
    expect(slot('te-note-chars')).toHaveAttribute('data-over', 'true')
    expect(slot('te-note-chars')).toHaveTextContent('11 / 10 karakter')
    expect(slot('te-note-too-long')).toHaveTextContent('Not en çok 10 karakter olabilir.')
    expect(slot('te-note-save')).toBeDisabled()
    fireEvent.keyDown(textarea(), { key: 's', ctrlKey: true })
    await act(async () => {})
    expect(te().saveChannelNote).not.toHaveBeenCalled()
    type('1234567890')
    expect(slot('te-note-too-long')).toBeNull()
    expect(slot('te-note-save')).toBeEnabled()
  })

  it('önizleme notun kilitli görüntüleyicisidir; yazma alanı gizlenirken bağlı kalır', async () => {
    await openEditor()
    type('# Taslak başlık\n\n![x](https://example.com/a.png)')
    fireEvent.click(screen.getByRole('button', { name: 'Önizleme' }))
    const pv = slot('te-note-preview')
    expect(within(pv).getByRole('heading', { level: 3, name: 'Taslak başlık' })).toBeInTheDocument()
    expect(pv.querySelector('img')).toBeNull()                         // dış görsel önizlemede de çizilmez
    expect(pv.querySelector('[data-slot="te-note-img-blocked"]')).not.toBeNull()
    expect(slot('te-note-write-pane')).toHaveAttribute('hidden')
    expect(slot('te-note-textarea').value).toBe('# Taslak başlık\n\n![x](https://example.com/a.png)')
    fireEvent.click(screen.getByRole('button', { name: 'Yaz' }))
    expect(slot('te-note-preview')).toBeNull()
    expect(slot('te-note-write-pane')).not.toHaveAttribute('hidden')
    // Boş taslağın önizlemesi
    type('   ')
    fireEvent.click(screen.getByRole('button', { name: 'Önizleme' }))
    expect(slot('te-note-preview')).toHaveTextContent('Önizlenecek metin yok.')
  })
})

describe('Not düzenleyici — yerel taslak yedeği ve ayrılma', () => {
  it('bu tarayıcıda bulunan taslak KENDİLİĞİNDEN uygulanmaz: "Geri yükle" / "Taslağı sil"', async () => {
    writeNoteBackup(10, { version: 4, body: 'Yarım kalan taslak' })
    await openEditor()
    expect(textarea().value).toBe(BODY)
    const banner = slot('te-note-backup')
    expect(banner).toHaveTextContent('Kaydedilmemiş bir taslak bulundu')
    expect(banner).not.toHaveTextContent('DAHA ESKİ')                  // aynı sürümün üzerine yazılmış
    fireEvent.click(slot('te-note-backup-discard'))
    expect(slot('te-note-backup')).toBeNull()
    expect(readNoteBackup(10)).toBeNull()
    expect(textarea().value).toBe(BODY)
  })

  it('"Geri yükle" taslağı düzenleyiciye alır (kaydedilmemiş olarak)', async () => {
    writeNoteBackup(10, { version: 4, body: 'Yarım kalan taslak' })
    await openEditor()
    fireEvent.click(slot('te-note-backup-restore'))
    expect(textarea().value).toBe('Yarım kalan taslak')
    expect(slot('te-note-backup')).toBeNull()
    expect(slot('te-note-save')).toBeEnabled()
    expect(te().saveChannelNote).not.toHaveBeenCalled()
  })

  it('kayıtlı metne eşit (gereksiz) yedek sessizce silinir; bozuk yedek yok sayılır', async () => {
    writeNoteBackup(10, { version: 3, body: BODY.replace(/\n/g, '\r\n') })
    const first = await openEditor()
    expect(slot('te-note-backup')).toBeNull()
    expect(readNoteBackup(10)).toBeNull()
    first.unmount()
    localStorage.setItem(noteBackupKey(10), '{bozuk')
    await openEditor()
    expect(slot('te-note-backup')).toBeNull()
    expect(textarea().value).toBe(BODY)
  })

  it('düzenleyici kaydedilmemiş taslakla kapanırken son hâli yedekler (oturum sahibi varken); dönüşte "geri yükle" önerilir', async () => {
    localStorage.setItem(OWNER_KEY, 'ayse.yilmaz')
    await openEditor()
    type('Yarıda bırakılan not')
    fireEvent.click(slot('te-note-close'))
    const confirm = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    expect(confirm).toHaveTextContent('taslak bu tarayıcıda saklanır')
    // Sayfada kal: düzenleyici ve taslak yerinde
    fireEvent.click(within(confirm).getByRole('button', { name: 'Sayfada kal' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    expect(textarea().value).toBe('Yarıda bırakılan not')

    fireEvent.click(slot('te-note-close'))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).getByRole('button', { name: 'Kaydetmeden ayrıl' }))
    await waitFor(() => expect(tab()).toHaveAttribute('data-state', 'read'))
    expect(readNoteBackup(10)).toMatchObject({ version: 4, body: 'Yarıda bırakılan not' })
    // Not sunucuda değişmedi: okuma kipi kayıtlı metni gösterir
    expect(screen.getByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Düzenle' }))
    await screen.findByLabelText('Kanal notu (Markdown)')
    expect(textarea().value).toBe(BODY)
    expect(slot('te-note-backup')).toHaveTextContent('Kaydedilmemiş bir taslak bulundu')
  })

  it('"Değişiklikleri geri al" taslağı kayıtlı metne döndürür ve bu sayfanın yazdığı yedeği siler', async () => {
    await openEditor()
    type('silinecek taslak')
    writeNoteBackup(10, { version: 4, body: 'silinecek taslak' })          // yazarken oluşan yedek
    fireEvent.click(slot('te-note-discard'))
    expect(textarea().value).toBe(BODY)
    expect(readNoteBackup(10)).toBeNull()
    expect(slot('te-note-save')).toBeDisabled()
  })
})

describe('Not sürüm geçmişi', () => {
  const VERSIONS = [
    { version: 4, saved_at: '2026-11-18T08:30:00Z', saved_by_name: 'Ayşe Yılmaz', char_count: BODY.length, image_count: 0 },
    { version: 3, saved_at: '2026-11-10T08:30:00Z', saved_by_name: 'Can Demir', char_count: 21, image_count: 1 },
  ]
  const OLD = 'Eski yol: rapor aracı'
  beforeEach(() => {
    te().channelNoteVersions.mockResolvedValue(ok({ versions: VERSIONS, max_versions: 20 }))
    te().channelNoteVersion.mockImplementation(async (_id, v) => ok({
      version: v, body: v === 3 ? OLD : BODY, saved_at: VERSIONS.find((x) => x.version === v)?.saved_at, saved_by_name: VERSIONS.find((x) => x.version === v)?.saved_by_name,
    }))
  })

  it('liste (en yeni önce, "Şu anki" rozeti) → sürüm önizlemesi → "Düzenleyiciye yükle": ŞİMDİKİ sürümün üzerine taslak', async () => {
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-note-version-item"]')).toHaveLength(2))
    expect(te().channelNoteVersions).toHaveBeenCalledWith(10)
    const items = [...dlg.querySelectorAll('[data-slot="te-note-version-item"]')]
    expect(items.map((el) => el.dataset.version)).toEqual(['4', '3'])
    expect(items[0]).toHaveTextContent('Şu anki')
    expect(items[1]).not.toHaveTextContent('Şu anki')
    expect(items[1]).toHaveTextContent('Can Demir')
    expect(items[1]).toHaveTextContent('21 karakter · 1 görsel')
    // En yeni sürüm seçili gelir ve okunur
    await waitFor(() => expect(te().channelNoteVersion).toHaveBeenCalledWith(10, 4))
    expect(dlg.querySelector('[data-slot="te-note-versions-pruned"]')).toBeNull()

    fireEvent.click(items[1])
    const preview = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="te-note-version-preview"]')
      expect(el).toHaveTextContent(OLD)
      return el
    })
    expect(preview.querySelector('[data-slot="te-note-view"]')).not.toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenleyiciye yükle' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Not sürüm geçmişi' })).toBeNull())
    expect((await screen.findByLabelText('Kanal notu (Markdown)')).value).toBe(OLD)
    expect(screen.getByText('Sürüm 3 taslak olarak yüklendi')).toBeInTheDocument()
    expect(slot('te-note-save')).toBeEnabled()
    expect(te().saveChannelNote).not.toHaveBeenCalled()              // sunucuya bir şey yazılmadı

    te().saveChannelNote.mockResolvedValueOnce(ok(note({ version: 5, body: OLD, changed: true })))
    fireEvent.click(slot('te-note-save'))
    await waitFor(() => expect(te().saveChannelNote).toHaveBeenCalledWith(10, { version: 4, body: OLD }))
    await waitFor(() => expect(screen.queryByText('Sürüm 3 taslak olarak yüklendi')).toBeNull())
  })

  it('düzenleyicide kaydedilmemiş değişiklik varken sürüm yüklemek onay ister; vazgeçilirse taslak korunur', async () => {
    await openEditor()
    type('Kirli taslak')
    fireEvent.click(slot('te-note-versions-open'))
    const dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    fireEvent.click(await waitFor(() => {
      const el = dlg.querySelector('[data-slot="te-note-version-item"][data-version="3"]')
      expect(el).not.toBeNull()
      return el
    }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-version-preview"]')).toHaveTextContent(OLD))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenleyiciye yükle' }))
    const confirm = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    expect(slot('te-note-textarea').value).toBe('Kirli taslak')
    expect(screen.getByRole('dialog', { name: 'Not sürüm geçmişi' })).toBeInTheDocument()

    fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenleyiciye yükle' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).getByRole('button', { name: 'Sürümü yükle' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Not sürüm geçmişi' })).toBeNull())
    expect(textarea().value).toBe(OLD)
    expect(await screen.findByText('Sürüm 3 düzenleyiciye yüklendi. Kaydederseniz yeni bir sürüm olur.')).toBeInTheDocument()
  })

  it('sürüm listesi okunamazsa "yok" denmez: hata + "Tekrar dene"; eski pod "geçici olarak kullanılamıyor"', async () => {
    te().channelNoteVersions.mockReset()
    te().channelNoteVersions
      .mockResolvedValueOnce(fail(503, 'DB_UNAVAILABLE'))
      .mockResolvedValueOnce(fail(404, 'NOT_FOUND', 'İstenen kaynak bulunamadı.'))
      .mockResolvedValueOnce(ok({ versions: VERSIONS, max_versions: 20 }))
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    const dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    const failed = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="te-note-versions-failed"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(failed).toHaveAttribute('data-state', 'failed')
    expect(failed).toHaveTextContent('Not sürümleri okunamadı')
    expect(dlg.querySelector('[data-slot="te-note-versions-empty"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-versions-failed"]')).toHaveAttribute('data-state', 'unsupported'))
    expect(dlg).toHaveTextContent('Notlar geçici olarak kullanılamıyor')
    expect(within(dlg).queryByText('İstenen kaynak bulunamadı.')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(dlg.querySelectorAll('[data-slot="te-note-version-item"]')).toHaveLength(2))
  })

  it('tek sürüm okunamazsa önizleme yerine hata + "Tekrar dene"; boş liste ayrı durumdur; sınırda "eskiler silindi" notu', async () => {
    te().channelNoteVersion.mockReset()
    te().channelNoteVersion
      .mockResolvedValueOnce(fail(500, 'INTERNAL_ERROR', 'Beklenmeyen bir sorun oluştu.'))
      .mockResolvedValueOnce(ok({ version: 4, body: BODY, saved_at: VERSIONS[0].saved_at, saved_by_name: 'Ayşe Yılmaz' }))
    const first = renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    let dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    expect(await within(dlg).findByText('Sürüm okunamadı')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-note-version-preview"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Tekrar dene' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-version-preview"]')).toHaveTextContent('Qlik › Kanal özeti ekranından alınır.'))
    first.unmount()

    // Sınıra ulaşmış liste (20 satır, en eskisi sürüm 5): daha eskileri budanmıştır
    const many = Array.from({ length: 20 }, (_, i) => ({ version: 24 - i, saved_at: '2026-11-18T08:30:00Z', saved_by_name: 'Kişi', char_count: 10, image_count: 0 }))
    te().channelNoteVersions.mockResolvedValue(ok({ versions: many, max_versions: 20 }))
    te().channelNoteVersion.mockResolvedValue(ok({ version: 24, body: 'x', saved_at: '2026-11-18T08:30:00Z', saved_by_name: 'Kişi' }))
    te().channelNote.mockResolvedValue(ok(note({ version: 24 })))
    const second = renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-versions-pruned"]')).not.toBeNull())
    second.unmount()

    te().channelNoteVersions.mockResolvedValue(ok({ versions: [], max_versions: 20 }))
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'Sürüm geçmişi' }))
    dlg = await screen.findByRole('dialog', { name: 'Not sürüm geçmişi' })
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-note-versions-empty"]')).not.toBeNull())
    expect(dlg).toHaveTextContent('Henüz kayıtlı sürüm yok')
  })
})

describe('Kanal sayfası — "Notlar" sekmesi ve ayrılma kapısı', () => {
  function renderView(ch, props = {}) {
    te().reports.mockResolvedValue({ success: true, data: { channel: ch, reports: [], default_period: { year: 2026, month: 9 } } })
    te().template.mockResolvedValue(templateResponse(ch))
    const handlers = { onTabChange: vi.fn(), onBack: vi.fn(), onOpenReport: vi.fn() }
    const view = render(<ChannelView channelId="10" tab="notes" {...handlers} {...props} />)
    return { ...view, ...handlers }
  }

  it('sekme can_read_notes === false iken yoktur; bayrak hiç yoksa (eski sunucu) görünür', async () => {
    const hidden = renderView(channel(10, 'Mobil', { can_read_notes: false, shared_access: true }), { tab: 'reports' })
    await screen.findByRole('tab', { name: /Raporlar/ })
    await waitFor(() => expect(te().reports).toHaveBeenCalled())
    await screen.findByRole('tab', { name: /Gönderim günlüğü/ })
    await waitFor(() => expect(document.querySelector('[data-slot="te-tab-notes"]')).toBeNull())
    expect(te().channelNote).not.toHaveBeenCalled()
    hidden.unmount()

    renderView(channel(10, 'Mobil'), { tab: 'reports' })                 // bayrak yok
    expect(await screen.findByRole('tab', { name: /Notlar/ })).toBeInTheDocument()
    // Sekme seçili değilken not ucu çağrılmaz
    expect(te().channelNote).not.toHaveBeenCalled()
  })

  it('"Notlar" sekmesi seçiliyken not okunur (kanal kimliğiyle) ve sekme etkindir', async () => {
    renderView(CH)
    expect(await screen.findByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /Notlar/ })).toHaveAttribute('data-state', 'active')
    expect(te().channelNote).toHaveBeenCalledWith('10')
  })

  it('kaydedilmemiş notla sekme değişimi sorar: "Sayfada kal" kalır, "Kaydetmeden ayrıl" geçer (taslak tarayıcıda saklanır)', async () => {
    localStorage.setItem(OWNER_KEY, 'ayse.yilmaz')
    const v = renderView(CH)
    fireEvent.click(await screen.findByRole('button', { name: 'Düzenle' }))
    await screen.findByLabelText('Kanal notu (Markdown)')
    type('Sekme değişince kaybolmasın')
    await waitFor(() => expect(slot('te-note-editor')).toHaveAttribute('data-dirty', 'true'))

    fireEvent.mouseDown(screen.getByRole('tab', { name: /Raporlar/ }), { button: 0 })
    let dlg = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    expect(dlg).toHaveTextContent('Notta kaydetmediğiniz değişiklikler var.')
    expect(v.onTabChange).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Sayfada kal' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    expect(v.onTabChange).not.toHaveBeenCalled()
    expect(textarea().value).toBe('Sekme değişince kaybolmasın')

    fireEvent.mouseDown(screen.getByRole('tab', { name: /Raporlar/ }), { button: 0 })
    dlg = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kaydetmeden ayrıl' }))
    await waitFor(() => expect(v.onTabChange).toHaveBeenCalledWith('reports'))
    // Üst bileşen sekmeyi değiştirince düzenleyici söker: taslak yedeklenir
    v.rerender(<ChannelView channelId="10" tab="reports" onTabChange={v.onTabChange} onBack={v.onBack} onOpenReport={v.onOpenReport} />)
    await waitFor(() => expect(readNoteBackup(10)).toMatchObject({ body: 'Sekme değişince kaybolmasın' }))
  })

  it('temiz notla sekme değişimi sormaz', async () => {
    const v = renderView(CH)
    fireEvent.click(await screen.findByRole('button', { name: 'Düzenle' }))
    await screen.findByLabelText('Kanal notu (Markdown)')
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Şablon/ }), { button: 0 })
    await waitFor(() => expect(v.onTabChange).toHaveBeenCalledWith('template'))
    expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull()
  })
})
