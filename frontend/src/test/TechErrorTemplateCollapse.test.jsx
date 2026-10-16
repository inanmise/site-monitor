import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, templateResponse, TEMPLATE } from './helpers/techErrorFixtures.js'

// Şablon düzenleyicisinde katlanır bölümler (2026-10-16, kullanıcı kararı): bölümler kapalı başlar, açılanlar kanal
// başına hatırlanır (`sm.te.open.t.<kanal>`), "Tümünü aç / kapat", kullanıcının eklediği bölüm açık gelir (yükleme /
// yedekten geri yükleme ile gelenler kapalı), hatası olan bölüm kendiliğinden açılır ve kapalıyken başlıkta hatalı alan
// sayısı görünür; salt okuyan ve kayıt sürerken de bölümler açılıp kapanır (alanlar kilitli kalır); kapalı bölüm taşınır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { rememberSectionsOpen } from '../components/techerror/SectionCollapse.jsx'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const KEY = 'sm.te.open.t.10'
const IDS = TEMPLATE.sections.map((s) => s.id)   // summary, issues, trend-note, charts

const card = (id) => document.querySelector(`[data-slot="te-tpl-section"][data-section="${id}"]`)
const toggle = (id) => card(id).querySelector('[data-slot="te-section-toggle"]')
const order = () => [...document.querySelectorAll('[data-slot="te-tpl-section"]')].map((el) => el.getAttribute('data-section'))
const openOf = () => Object.fromEntries(IDS.map((id) => [id, card(id)?.getAttribute('data-open')]))
const stored = () => JSON.parse(localStorage.getItem(KEY) || 'null')

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

async function openEditor() {
  const view = render(<TechErrorReportsPage />)
  await screen.findByText('Rapor başlığı ve dili')
  await waitFor(() => expect(card('summary')).not.toBeNull())
  return view
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_tab=template')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().template.mockImplementation(async (id) => templateResponse(channel(Number(id), 'Mobil')))
  te().saveTemplate.mockImplementation(async (id, tpl) => ({ success: true, data: { template: tpl, version: 4 } }))
})

describe('şablon düzenleyici — katlanır bölümler', () => {
  it('bölümler kapalı başlar; başlık kartı hep açık; aç / kapat düğmesinin adı bölümün başlığı + türü', async () => {
    await openEditor()
    expect(openOf()).toEqual({ summary: 'false', issues: 'false', 'trend-note': 'false', charts: 'false' })
    for (const id of IDS) expect(toggle(id)).toHaveAttribute('aria-expanded', 'false')
    // Başlık kartı katlanmaz: rapor başlığı her zaman düzenlenebilir
    const head = document.querySelector('[data-slot="te-tpl-head"]')
    expect(head.querySelector('[data-slot="te-section-toggle"]')).toBeNull()
    expect(screen.getByRole('textbox', { name: 'Rapor başlığı' })).toBeInTheDocument()
    // Kapalı bölümün içeriği erişilebilirlik ağacında değil (tarayıcıdaki gibi)
    expect(screen.queryAllByRole('textbox', { name: 'Bölüm başlığı' })).toHaveLength(0)
    // Ad: çözümlenmiş başlık (yer tutucular dolu) + tür rozeti
    expect(screen.getByRole('button', { name: /^Grafikler hakkında Metin$/ })).toBe(toggle('trend-note'))
    expect(screen.getByRole('button', { name: /^Mobil Kanalı — .+ Özeti Göstergeler$/ })).toBe(toggle('summary'))
    // Taşı / sil kapalı bölümde de görünür
    expect(within(card('charts')).getByRole('button', { name: '“Grafikler” yukarı taşı' })).not.toBeDisabled()
  })

  it('açılan bölüm bu kanal için hatırlanır (sayfa yeniden kurulunca da açık); diğerleri kapalı kalır', async () => {
    const view = await openEditor()
    fireEvent.click(toggle('charts'))
    expect(card('charts')).toHaveAttribute('data-open', 'true')
    expect(toggle('charts')).toHaveAttribute('aria-expanded', 'true')
    expect(within(card('charts')).getByRole('textbox', { name: 'Bölüm başlığı' })).toHaveValue('Grafikler')
    expect(stored()).toEqual({ ids: ['charts'] })

    view.unmount()
    await openEditor()
    expect(openOf()).toEqual({ summary: 'false', issues: 'false', 'trend-note': 'false', charts: 'true' })

    // Kapatılınca kayıttan da düşer
    fireEvent.click(toggle('charts'))
    expect(card('charts')).toHaveAttribute('data-open', 'false')
    expect(stored()).toEqual({ ids: [] })
  })

  it('kaydedilmiş açık bölümler okunur (başka kanalınkiler değil)', async () => {
    rememberSectionsOpen('t', 10, ['issues'])
    rememberSectionsOpen('t', 20, ['summary', 'charts'])
    await openEditor()
    expect(openOf()).toEqual({ summary: 'false', issues: 'true', 'trend-note': 'false', charts: 'false' })
  })

  it('"Tümünü aç" hepsini açar ve "Tümünü kapat" olur; "hepsi açık"ken bir bölüm kapatılınca diğerleri açık kalır', async () => {
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: 'Tümünü aç' }))
    expect(openOf()).toEqual({ summary: 'true', issues: 'true', 'trend-note': 'true', charts: 'true' })
    expect(stored()).toEqual({ all: true })
    expect(screen.getAllByRole('textbox', { name: 'Bölüm başlığı' })).toHaveLength(4)

    fireEvent.click(toggle('issues'))
    expect(openOf()).toEqual({ summary: 'true', issues: 'false', 'trend-note': 'true', charts: 'true' })
    // Biri kapalı → düğme yine "Tümünü aç"
    fireEvent.click(screen.getByRole('button', { name: 'Tümünü aç' }))
    expect(card('issues')).toHaveAttribute('data-open', 'true')

    fireEvent.click(screen.getByRole('button', { name: 'Tümünü kapat' }))
    expect(openOf()).toEqual({ summary: 'false', issues: 'false', 'trend-note': 'false', charts: 'false' })
    expect(stored()).toEqual({ ids: [] })
    expect(screen.getByRole('button', { name: 'Tümünü aç' })).toBeInTheDocument()
  })

  it('kullanıcının eklediği bölüm açık gelir; kayıt yanıtıyla yeniden kurulan bölümler açılmaz', async () => {
    await openEditor()
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Bölüm ekle' }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Metin' }))
    const texts = document.querySelectorAll('[data-slot="te-tpl-section"][data-kind="text"]')
    expect(texts).toHaveLength(2)
    const added = texts[1]
    expect(added).toHaveAttribute('data-open', 'true')
    expect(within(added).getByRole('textbox', { name: 'Bölüm başlığı' })).toHaveValue('Not')
    // Mevcut bölümler kapalı kalır
    expect(openOf()).toEqual({ summary: 'false', issues: 'false', 'trend-note': 'false', charts: 'false' })

    // Kayıt yanıtı taslağı değiştirir (gen artar): yeni bölüm yine açık (hatırlandı), diğerleri kapalı
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    expect(await screen.findByText('Tüm değişiklikler kaydedildi')).toBeInTheDocument()
    const addedId = added.getAttribute('data-section')
    expect(card(addedId)).toHaveAttribute('data-open', 'true')
    expect(openOf()).toEqual({ summary: 'false', issues: 'false', 'trend-note': 'false', charts: 'false' })
  })

  it('yedekten geri yüklenen şablonun yeni bölümü kendiliğinden açılmaz (dışarıdan gelen taslak)', async () => {
    const extra = { id: 'extra-note', kind: 'text', title: 'Yedekteki not', text: 'x' }
    localStorage.setItem('sm.te.tpl.10', JSON.stringify({ version: 3, template: { ...TEMPLATE, sections: [...TEMPLATE.sections, extra] }, at: Date.now() }))
    await openEditor()
    fireEvent.click(await screen.findByRole('button', { name: 'Geri yükle' }))
    await waitFor(() => expect(card('extra-note')).not.toBeNull())
    expect(card('extra-note')).toHaveAttribute('data-open', 'false')
    expect(toggle('extra-note')).toHaveAttribute('aria-expanded', 'false')
  })

  it('kayıt hatası: hatalı bölüm kendiliğinden açılır; kapatılınca başlıkta hatalı alan sayısı; kaydet yine açar', async () => {
    await openEditor()
    // Bölüm başlığını sil, sonra bölümü kapat (kullanıcı başka yere geçer)
    fireEvent.click(toggle('trend-note'))
    fireEvent.change(within(card('trend-note')).getByRole('textbox', { name: 'Bölüm başlığı' }), { target: { value: '' } })
    // Başlık boşsa düğme "Başlıksız bölüm" der
    expect(toggle('trend-note')).toHaveAccessibleName(/^Başlıksız bölüm Metin$/)
    fireEvent.click(toggle('trend-note'))
    expect(card('trend-note')).toHaveAttribute('data-open', 'false')
    // Göstergeler bölümünde alan adı da boş (kapalı bölümde iki hata)
    fireEvent.click(toggle('summary'))
    const label = within(card('summary')).getAllByRole('textbox', { name: 'Alan adı' })[0]
    fireEvent.change(label, { target: { value: '' } })
    fireEvent.click(toggle('summary'))
    expect(card('summary')).toHaveAttribute('data-open', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(te().saveTemplate).not.toHaveBeenCalled()
    // Hatalı iki bölüm açıldı, hatasız olanlar kapalı
    await waitFor(() => expect(card('trend-note')).toHaveAttribute('data-open', 'true'))
    expect(openOf()).toEqual({ summary: 'true', issues: 'false', 'trend-note': 'true', charts: 'false' })
    expect(within(card('trend-note')).getByText('Bölüm başlığı boş olamaz; PDF\'te bu başlık görünür.')).toBeInTheDocument()
    expect(within(card('summary')).getByText('Alan adı boş olamaz; raporda ve PDF\'te bu ad görünür.')).toBeInTheDocument()
    // Açıkken sayı rozeti yok
    expect(document.querySelector('[data-slot="te-section-invalid"]')).toBeNull()

    // Kullanıcı kapatırsa hata sürdükçe kapalı kalır, başlıkta sayı görünür
    fireEvent.click(toggle('trend-note'))
    expect(card('trend-note')).toHaveAttribute('data-open', 'false')
    expect(within(card('trend-note')).getByText('1 alan düzeltilmeli')).toBeInTheDocument()
    fireEvent.click(toggle('summary'))
    expect(within(card('summary')).getByText('1 alan düzeltilmeli')).toBeInTheDocument()
    expect(within(card('issues')).queryByText(/alan düzeltilmeli/)).toBeNull()

    // Yeniden kaydet → hatalı bölümler yine açılır
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    expect(card('trend-note')).toHaveAttribute('data-open', 'true')
    expect(card('summary')).toHaveAttribute('data-open', 'true')
    expect(te().saveTemplate).not.toHaveBeenCalled()

    // Düzeltilince hata kalkar; kapalı bölümde sayı da kalkar
    fireEvent.change(within(card('trend-note')).getByRole('textbox', { name: 'Bölüm başlığı' }), { target: { value: 'Notlar' } })
    fireEvent.click(toggle('trend-note'))
    expect(within(card('trend-note')).queryByText(/alan düzeltilmeli/)).toBeNull()
  })

  it('salt okuyan kullanıcı bölümleri açıp kapatır; alanlar ve taşı / sil kilitli kalır', async () => {
    te().template.mockImplementation(async () => templateResponse(channel(10, 'Mobil', { can_edit: false })))
    await openEditor()
    expect(screen.getByText('Şablonu yalnız görüntülüyorsunuz')).toBeInTheDocument()
    expect(toggle('issues')).not.toBeDisabled()
    fireEvent.click(toggle('issues'))
    expect(card('issues')).toHaveAttribute('data-open', 'true')
    expect(within(card('issues')).getByRole('textbox', { name: 'Bölüm başlığı' })).toBeDisabled()
    expect(within(card('issues')).getByRole('button', { name: /yukarı taşı$/ })).toBeDisabled()
    expect(within(card('issues')).getByRole('button', { name: /aşağı taşı$/ })).toBeDisabled()
    expect(screen.getByLabelText('Rapor başlığı')).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Tümünü aç' }))
    expect(openOf()).toEqual({ summary: 'true', issues: 'true', 'trend-note': 'true', charts: 'true' })
    for (const input of screen.getAllByRole('textbox', { name: 'Bölüm başlığı' })) expect(input).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Bölüm ekle' })).toBeNull()
  })

  it('kayıt sürerken bölümler açılıp kapanır; alanlar, taşı / sil ve "Bölüm ekle" kayıt bitene kadar kapalı', async () => {
    const pending = deferred()
    te().saveTemplate.mockImplementationOnce(() => pending.promise)
    await openEditor()
    fireEvent.change(screen.getByLabelText('Rapor başlığı'), { target: { value: '{kanal} Kanalı Teknik Hata Raporu' } })
    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())

    expect(screen.getByLabelText('Rapor başlığı')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Bölüm ekle' })).toBeDisabled()
    expect(toggle('charts')).not.toBeDisabled()
    fireEvent.click(toggle('charts'))
    expect(card('charts')).toHaveAttribute('data-open', 'true')
    expect(within(card('charts')).getByRole('textbox', { name: 'Bölüm başlığı' })).toBeDisabled()
    expect(within(card('charts')).getByRole('button', { name: '“Grafikler” yukarı taşı' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Tümünü aç' })).not.toBeDisabled()

    pending.resolve({ success: true, data: { template: { ...TEMPLATE, title: '{kanal} Kanalı Teknik Hata Raporu' }, version: 4 } })
    await waitFor(() => expect(screen.getByLabelText('Rapor başlığı')).not.toBeDisabled())
    expect(within(card('charts')).getByRole('textbox', { name: 'Bölüm başlığı' })).not.toBeDisabled()
    expect(within(card('charts')).getByRole('button', { name: '“Grafikler” yukarı taşı' })).not.toBeDisabled()
    // Kayıt yanıtı açık bölümü kapatmaz
    expect(card('charts')).toHaveAttribute('data-open', 'true')
  })

  it('kapalı bölüm başlıktaki düğmelerle taşınır; kapalı kalır ve yeni sıra kaydedilir', async () => {
    await openEditor()
    expect(order()).toEqual(['summary', 'issues', 'trend-note', 'charts'])
    fireEvent.click(within(card('charts')).getByRole('button', { name: '“Grafikler” yukarı taşı' }))
    expect(order()).toEqual(['summary', 'issues', 'charts', 'trend-note'])
    expect(card('charts')).toHaveAttribute('data-open', 'false')
    fireEvent.click(within(card('summary')).getByRole('button', { name: /aşağı taşı$/ }))
    expect(order()).toEqual(['issues', 'summary', 'charts', 'trend-note'])
    expect(openOf()).toEqual({ summary: 'false', issues: 'false', 'trend-note': 'false', charts: 'false' })

    fireEvent.click(screen.getByRole('button', { name: 'Şablonu kaydet' }))
    await waitFor(() => expect(te().saveTemplate).toHaveBeenCalled())
    expect(te().saveTemplate.mock.calls[0][1].sections.map((s) => s.id)).toEqual(['issues', 'summary', 'charts', 'trend-note'])
  })
})
