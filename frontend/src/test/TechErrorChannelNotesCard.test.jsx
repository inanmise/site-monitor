import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

/**
 * "Kanal notları" kartı (2026-11-20) — rapor düzenleyicisinin başında ve Görsel paneli penceresinin tepesinde SALT OKUNUR,
 * katlanır kart:
 *  - yalnız rapor yanıtındaki `channel_notes.readable === true` ise çizilir (anahtar yoksa / okunamaz ise kart HİÇ yok);
 *  - kapalı başlar, kapalıyken istek atmaz; not kart İLK açıldığında okunur; açık / kapalı durumu rapor başına hatırlanır;
 *  - `unavailable` ya da okunamayan not "not yok" DEĞİLDİR: "okunamadı" + "Tekrar dene";
 *  - düzenleyebilen için "Notu düzenle" / "Not yaz" — rapor düzenleyicisinin ayrılma kapısından geçip Notlar sekmesine gider;
 *  - memo: rapor düzenleyicisinde yazarken kart yeniden çizilmez.
 * Ölçü (çizim sayısı): kart her çizimde `noteCardState`'i bir kez çağırır.
 */
const renders = vi.hoisted(() => ({ card: 0 }))
vi.mock('../components/techerror/notes/noteModel.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, noteCardState: (meta) => { renders.card += 1; return mod.noteCardState(meta) } }
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), template: vi.fn(), channelNote: vi.fn(),
      channelNoteImageUrl: (id) => `/api/tech-error-reports/channel-notes/images/${id}`,
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ChannelNotesCard from '../components/techerror/notes/ChannelNotesCard.jsx'
import { loadNotePart } from '../components/techerror/notes/lazyParts.jsx'
import ImagesPanelPage from '../components/techerror/capture/ImagesPanelPage.jsx'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const META = { readable: true, exists: true, empty: false, updated_at: '2026-11-18T08:30:00Z', updated_by_name: 'Ayşe Yılmaz', image_count: 1 }
const EMPTY = { readable: true, exists: false, empty: true, updated_at: null, updated_by_name: null, image_count: 0 }
const BODY = '# Veri kaynağı\n\nQlik › Kanal özeti ekranından alınır.\n\n![Qlik](te-note-img:12)'
const NOTE = {
  channel_id: 10, exists: true, version: 4, body: BODY, char_count: BODY.length, updated_at: '2026-11-18T08:30:00Z',
  updated_by_name: 'Ayşe Yılmaz', created_at: '2026-11-01T08:00:00Z',
  images: [{ id: 12, file_name: 'qlik.png', content_type: 'image/png', width: 1200, height: 600, size_bytes: 1000, linked: true }],
  image_bytes: 1000, can_edit: true, shared_access: false,
}
const ok = (data) => ({ success: true, status: 200, data })
const card = () => document.querySelector('[data-slot="te-notes-card"]')
const toggle = () => card().querySelector('[data-slot="te-section-toggle"]')
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)

beforeAll(async () => {
  await loadNotePart('view')
}, 120000)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem(LANG_STORAGE_KEY, 'tr')
  te().channelNote.mockReset()
  te().channelNote.mockResolvedValue(ok(NOTE))
  renders.card = 0
})

describe('ChannelNotesCard', () => {
  it.each([
    ['anahtar yok', undefined],
    ['null', null],
    ['okunamaz (yalnız görüntüleme paylaşımı)', { readable: false }],
    ['readable alanı yok', { exists: true, empty: false }],
  ])('kart çizilmez ve istek atılmaz: %s', async (_ad, meta) => {
    render(<ChannelNotesCard reportId={100} channelId={10} meta={meta} canEdit onEdit={() => {}} />)
    await act(async () => {})
    expect(card()).toBeNull()
    expect(document.querySelector('[data-slot="te-section-toggle"]')).toBeNull()
    expect(te().channelNote).not.toHaveBeenCalled()
  })

  it('kapalı başlar ve kapalıyken not OKUNMAZ; ilk açılışta okunur, kapanıp açılınca yeniden okunmaz; durum rapor başına hatırlanır', async () => {
    render(<ChannelNotesCard reportId={100} channelId={10} meta={META} />)
    expect(card()).toHaveAttribute('data-open', 'false')
    expect(card()).toHaveAttribute('data-kind', 'note')
    expect(within(card()).getByRole('heading', { level: 3, name: 'Kanal notları' })).toBeInTheDocument()
    await act(async () => {})
    expect(te().channelNote).not.toHaveBeenCalled()
    expect(localStorage.getItem('sm.te.open.rn.100')).toBeNull()

    fireEvent.click(toggle())
    expect(card()).toHaveAttribute('data-open', 'true')
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(te().channelNote).toHaveBeenCalledTimes(1)
    expect(te().channelNote).toHaveBeenCalledWith(10)
    expect(within(card()).getByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(slot('te-notes-card-meta').textContent).toMatch(/^Son güncelleme: 18\.11\.2026 \d{2}:\d{2} · Ayşe Yılmaz$/)
    // Kart içinde notun başlıkları kartın h3'ünün ALTINDA (h4) kalır; görsel aynı kökenli adresten çizilir
    expect(within(card()).getByRole('heading', { level: 4, name: 'Veri kaynağı' })).toBeInTheDocument()
    expect(card().querySelector('img[data-slot="te-note-img"]').getAttribute('src')).toBe('/api/tech-error-reports/channel-notes/images/12')
    expect(JSON.parse(localStorage.getItem('sm.te.open.rn.100'))).toEqual({ ids: ['channel-notes'] })
    // Salt okunur: düzenleme bağlantısı verilmediyse yok
    expect(slot('te-notes-card-edit')).toBeNull()

    fireEvent.click(toggle())
    expect(card()).toHaveAttribute('data-open', 'false')
    fireEvent.click(toggle())
    expect(card()).toHaveAttribute('data-open', 'true')
    await act(async () => {})
    expect(te().channelNote).toHaveBeenCalledTimes(1)
    expect(card()).toHaveAttribute('data-note-state', 'note')
  })

  it('açık bırakılan kart sonraki açılışta açık gelir ve notu okur; başka raporun kaydı ayrıdır', async () => {
    localStorage.setItem('sm.te.open.rn.100', JSON.stringify({ ids: ['channel-notes'] }))
    const first = render(<ChannelNotesCard reportId={100} channelId={10} meta={META} />)
    expect(card()).toHaveAttribute('data-open', 'true')
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(te().channelNote).toHaveBeenCalledTimes(1)
    first.unmount()
    render(<ChannelNotesCard reportId={101} channelId={10} meta={META} />)
    expect(card()).toHaveAttribute('data-open', 'false')
    await act(async () => {})
    expect(te().channelNote).toHaveBeenCalledTimes(1)
  })

  it('özet "unavailable" ise "not yok" DENMEZ: "okunamadı" + "Tekrar dene" notu okur', async () => {
    render(<ChannelNotesCard reportId={100} channelId={10} meta={{ readable: true, unavailable: true }} canEdit onEdit={() => {}} />)
    expect(card()).toHaveAttribute('data-kind', 'unknown')
    fireEvent.click(toggle())
    expect(card()).toHaveAttribute('data-note-state', 'unread')
    expect(slot('te-notes-card-unread')).toHaveTextContent('Kanal notları okunamadı.')
    expect(slot('te-notes-card-empty')).toBeNull()
    expect(slot('te-notes-card-write')).toBeNull()                       // okunamayan nota "Not yaz" önerilmez
    expect(te().channelNote).not.toHaveBeenCalled()
    fireEvent.click(slot('te-notes-card-retry'))
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(within(card()).getByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(te().channelNote).toHaveBeenCalledTimes(1)
  })

  it('not yokken: okuyana "henüz not yok" (istek yok); düzenleyebilene "Not yaz" bağlantısı', async () => {
    const first = render(<ChannelNotesCard reportId={100} channelId={10} meta={EMPTY} />)
    fireEvent.click(toggle())
    expect(card()).toHaveAttribute('data-note-state', 'empty')
    expect(slot('te-notes-card-empty')).toHaveTextContent('Bu kanal için henüz not yok.')
    expect(slot('te-notes-card-write')).toBeNull()
    await act(async () => {})
    expect(te().channelNote).not.toHaveBeenCalled()
    first.unmount()

    const onEdit = vi.fn()
    render(<ChannelNotesCard reportId={100} channelId={10} meta={EMPTY} canEdit onEdit={onEdit} />)
    expect(card()).toHaveAttribute('data-open', 'true')                   // az önce açık bırakıldı
    expect(slot('te-notes-card-empty')).toHaveTextContent('Verinin nereden, nasıl alındığını yazarsanız')
    fireEvent.click(within(card()).getByRole('button', { name: 'Not yaz' }))
    expect(onEdit).toHaveBeenCalledTimes(1)
  })

  it('metni boş kayıtlı not (exists + empty) da "henüz not yok"tur', () => {
    render(<ChannelNotesCard reportId={100} channelId={10} meta={{ ...META, empty: true }} />)
    fireEvent.click(toggle())
    expect(card()).toHaveAttribute('data-note-state', 'empty')
    expect(te().channelNote).not.toHaveBeenCalled()
  })

  it('not okunamazsa (5xx / reddedilen söz) hata + sunucunun metni + "Tekrar dene"; eski pod "geçici olarak kullanılamıyor"', async () => {
    te().channelNote
      .mockResolvedValueOnce({ success: false, status: 503, code: 'DB_UNAVAILABLE', error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
      .mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı.'))
      .mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'İstenen kaynak bulunamadı.' })
      .mockResolvedValueOnce(ok(NOTE))
    render(<ChannelNotesCard reportId={100} channelId={10} meta={META} />)
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'unread'))
    expect(slot('te-notes-card-unread')).toHaveTextContent('Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.')
    expect(slot('te-notes-card-empty')).toBeNull()

    fireEvent.click(slot('te-notes-card-retry'))
    await waitFor(() => expect(slot('te-notes-card-unread')).toHaveTextContent('Sunucuya ulaşılamadı.'))

    fireEvent.click(slot('te-notes-card-retry'))
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'unsupported'))
    expect(slot('te-notes-card-unavailable')).toHaveTextContent('Sunucu güncelleniyor olabilir.')
    expect(within(card()).queryByText('İstenen kaynak bulunamadı.')).toBeNull()

    fireEvent.click(slot('te-notes-card-retry'))
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(te().channelNote).toHaveBeenCalledTimes(4)
  })

  it('özet "not var" derken sunucu boş not dönerse (arada silindi) "henüz not yok" gösterilir', async () => {
    te().channelNote.mockResolvedValue(ok({ ...NOTE, exists: false, version: 0, body: '', images: [] }))
    render(<ChannelNotesCard reportId={100} channelId={10} meta={META} />)
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'empty'))
    expect(slot('te-notes-card-empty')).toHaveTextContent('Bu kanal için henüz not yok.')
  })

  it('düzenleyebilene "Notu düzenle"; not başka yerde güncellenince (özet değişti) açık kart yeniden okur', async () => {
    const onEdit = vi.fn()
    const view = render(<ChannelNotesCard reportId={100} channelId={10} meta={META} canEdit onEdit={onEdit} />)
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    fireEvent.click(within(card()).getByRole('button', { name: 'Notu düzenle' }))
    expect(onEdit).toHaveBeenCalledTimes(1)

    te().channelNote.mockResolvedValue(ok({ ...NOTE, version: 5, body: 'Güncel yol: yeni rapor aracı', updated_at: '2026-11-20T09:00:00Z', updated_by_name: 'Can Demir' }))
    view.rerender(<ChannelNotesCard reportId={100} channelId={10} meta={{ ...META, updated_at: '2026-11-20T09:00:00Z', updated_by_name: 'Can Demir' }} canEdit onEdit={onEdit} />)
    expect(await within(card()).findByText('Güncel yol: yeni rapor aracı')).toBeInTheDocument()
    expect(te().channelNote).toHaveBeenCalledTimes(2)
    expect(slot('te-notes-card-meta')).toHaveTextContent('Can Demir')
  })

  it('memo: eşit özetle üst bileşen yeniden çizilince kart çizilmez; özet değişince çizilir', async () => {
    const onEdit = vi.fn()
    function Host({ meta, tick }) {
      return (
        <>
          <span data-testid="tick">{tick}</span>
          <ChannelNotesCard reportId={100} channelId={10} meta={meta} canEdit onEdit={onEdit} />
        </>
      )
    }
    const view = render(<Host meta={META} tick={0} />)
    await act(async () => {})
    const before = renders.card
    expect(before).toBeGreaterThan(0)
    view.rerender(<Host meta={{ ...META }} tick={1} />)
    view.rerender(<Host meta={{ ...META }} tick={2} />)
    expect(screen.getByTestId('tick')).toHaveTextContent('2')
    expect(renders.card).toBe(before)
    view.rerender(<Host meta={{ ...META, image_count: 9 }} tick={3} />)
    expect(renders.card).toBe(before + 1)
  })

  it('geç gelen yanıt atılır: kart sökülünce süren okuma durumu yazmaz (uyarı / çökme yok)', async () => {
    let finish
    te().channelNote.mockReturnValue(new Promise((r) => { finish = r }))
    const view = render(<ChannelNotesCard reportId={100} channelId={10} meta={META} />)
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'loading'))
    view.unmount()
    await act(async () => { finish(ok(NOTE)) })
    expect(card()).toBeNull()
  })
})

describe('rapor düzenleyicisinde "Kanal notları" kartı', () => {
  function setup(extra = {}) {
    openAllSections()
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    const ch = channel(10, 'Mobil', { can_read_notes: true, can_edit_notes: true })
    te().overview.mockResolvedValue(overview())
    te().reports.mockImplementation(async () => ({ success: true, data: { channel: ch, reports: [], default_period: { year: 2026, month: 9 }, channel_notes: META } }))
    te().report.mockResolvedValue(reportResponse({ channel: ch, channel_notes: META, ...extra }))
  }

  it('rapor yanıtında `channel_notes` yoksa (eski sunucu) kart yoktur', async () => {
    setup()
    te().report.mockResolvedValue(reportResponse())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(card()).toBeNull()
    expect(te().channelNote).not.toHaveBeenCalled()
  })

  it('kart bölümlerden ÖNCE ve (bölümlerin hepsi açıkken de) kapalı çizilir; açınca not okunur', async () => {
    setup()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(card()).toHaveAttribute('data-open', 'false')                 // bölümler açık (fixture) ama kart kendi kaydını tutar
    const firstSection = document.querySelector('[data-section="summary"]')
    expect(firstSection).not.toBeNull()
    expect(card().compareDocumentPosition(firstSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await act(async () => {})
    expect(te().channelNote).not.toHaveBeenCalled()

    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(te().channelNote).toHaveBeenCalledWith(10)
    expect(within(card()).getByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(JSON.parse(localStorage.getItem('sm.te.open.rn.100'))).toEqual({ ids: ['channel-notes'] })
  })

  it('"Notu düzenle" temiz raporda sormadan kanalın Notlar sekmesine götürür', async () => {
    setup()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    fireEvent.click(within(card()).getByRole('button', { name: 'Notu düzenle' }))
    const tab = await screen.findByRole('tab', { name: /Notlar/ })
    await waitFor(() => expect(tab).toHaveAttribute('data-state', 'active'))
    await waitFor(() => expect(window.location.search).toContain('te_tab=notes'))
    expect(window.location.search).toContain('te_view=channel')
    expect(window.location.search).toContain('te_ch=10')
    expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull()
  })

  it('kaydedilmemiş raporda "Notu düzenle" önce sorar (ayrılma kapısı); "Sayfada kal" raporda bırakır', async () => {
    setup()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1500' } })
    fireEvent.click(within(card()).getByRole('button', { name: 'Notu düzenle' }))
    const dlg = await screen.findByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Sayfada kal' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Kaydedilmemiş değişiklikler var' })).toBeNull())
    expect(screen.getByRole('heading', { name: TITLE })).toBeInTheDocument()
    expect(window.location.search).toContain('te_view=report')
  })

  it('düzenleme yetkisi yoksa (can_edit_notes yok) kartta düzenleme bağlantısı yoktur', async () => {
    setup({ channel: channel(10, 'Mobil', { can_read_notes: true, can_edit_notes: false }) })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(slot('te-notes-card-edit')).toBeNull()
  })

  it('raporda yazarken kart yeniden çizilmez (memo + kararlı prop\'lar)', async () => {
    setup()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    await act(async () => {})
    const before = renders.card
    const input = screen.getByLabelText('Toplam işlem adedi')
    fireEvent.change(input, { target: { value: '1' } })
    fireEvent.change(input, { target: { value: '15' } })
    fireEvent.change(input, { target: { value: '150' } })
    await waitFor(() => expect(screen.getByLabelText('Toplam işlem adedi')).toHaveValue('150'))
    expect(renders.card).toBe(before)
  })
})

describe('Görsel paneli penceresinde "Kanal notları" kartı', () => {
  it('görsel bölümlerinin ÜSTÜNDE, salt okunur (düzenleme bağlantısı yok); açınca not okunur', async () => {
    te().report.mockResolvedValue(reportResponse({ channel: channel(10, 'Mobil', { can_read_notes: true, can_edit_notes: true }), channel_notes: META }))
    render(<ImagesPanelPage reportId="100" />)
    await screen.findByRole('heading', { level: 1, name: TITLE })
    expect(card()).toHaveAttribute('data-open', 'false')
    const firstSlot = document.querySelector('[data-slot="te-image-slot"]')
    expect(firstSlot).not.toBeNull()
    expect(card().compareDocumentPosition(firstSlot) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    fireEvent.click(toggle())
    await waitFor(() => expect(card()).toHaveAttribute('data-note-state', 'note'))
    expect(within(card()).getByText('Qlik › Kanal özeti ekranından alınır.')).toBeInTheDocument()
    expect(slot('te-notes-card-edit')).toBeNull()
    expect(slot('te-notes-card-write')).toBeNull()
  })

  it('`channel_notes` yoksa ya da okunamaz ise panelde kart yoktur', async () => {
    te().report.mockResolvedValue(reportResponse({ channel_notes: { readable: false } }))
    render(<ImagesPanelPage reportId="100" />)
    await screen.findByRole('heading', { level: 1, name: TITLE })
    expect(card()).toBeNull()
    expect(te().channelNote).not.toHaveBeenCalled()
  })
})
