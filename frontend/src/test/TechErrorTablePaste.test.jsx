import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, TEMPLATE, openAllSections } from './helpers/techErrorFixtures.js'

// Excel'den tabloya yapıştırma (2026-11-20, C5): odaklı hücreye yapıştırılan sekmeli / satırlı metin o hücreden başlayarak
// satır satır dolar; sayı hücresi metni YAZILMIŞ GİBİ işler (geçersiz değer yazımdaki hatayı verir); tablonun dışına düşen
// değerler sayılır; tek değer bugünkü gibi tarayıcıya bırakılır.
const widthCtl = vi.hoisted(() => ({ value: 1200 }))
vi.mock('../hooks/useElementWidth.js', async (orig) => {
  const real = await orig()
  return { ...real, useElementWidth: () => [() => {}, widthCtl.value] }
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), template: vi.fn(), saveReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const TPL = {
  ...TEMPLATE,
  sections: [
    TEMPLATE.sections[0],
    { id: 'sla', kind: 'table', title: '{kanal} SLA Erişilebilirlik Tablosu', columns: [
      { id: 'functions', label: 'Fonksiyon adedi', type: 'number' },
      { id: 'availability', label: 'Erişilebilirlik', type: 'percent' },
      { id: 'note', label: 'Not', type: 'text' },
    ], rows: [
      { id: 'total', label: 'Toplam', group: true },
      { id: 'critical', label: 'SLA dahil ve kritik' },
    ] },
  ],
}
const cell = (row, col) => document.getElementById(`te-c-sla-${row}-${col}`)
const note = () => document.querySelector('[data-slot="te-table-paste-note"]')
const paste = (el, text) => fireEvent.paste(el, { clipboardData: { getData: (type) => (type === 'text/plain' ? text : '') } })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse({ template: TPL }))
})

async function openEditor() {
  render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
}

describe.each([['tablo', 1200], ['kartlar', 500]])('C5 — Excel\'den yapıştırma (%s)', (_name, width) => {
  beforeEach(() => { widthCtl.value = width })

  it('odaklı hücreden satır satır; geçersiz değer yazımdaki hatayı verir; kaç hücre dolduğu söylenir', async () => {
    await openEditor()
    const start = cell('total', 'functions')
    start.focus()
    const notPrevented = paste(start, '12\t99,5\tNot yazısı\r\n7\tabc\r\n')
    expect(notPrevented).toBe(false)                                    // tarayıcının yapıştırması engellendi
    await waitFor(() => expect(cell('total', 'functions')).toHaveValue('12'))
    expect(cell('total', 'availability')).toHaveValue('99,5')
    expect(cell('total', 'note')).toHaveValue('Not yazısı')
    expect(cell('critical', 'functions')).toHaveValue('7')
    // Geçersiz değer: metin yazıldığı gibi kalır, alanın hatası yazımdakiyle aynı
    expect(cell('critical', 'availability')).toHaveValue('abc')
    expect(cell('critical', 'availability')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Geçerli bir sayı yazın (ör. 1.250.000 ya da 0,42).')).toBeInTheDocument()
    expect(note()).toHaveTextContent('5 hücre yapıştırıldı.')
    expect(note()).toHaveAttribute('data-outside', '0')
  })

  it('tablonun dışına düşen değerler alınmaz ve sayılır', async () => {
    await openEditor()
    const start = cell('critical', 'availability')
    start.focus()
    paste(start, '1\t2\t3\n4\t5\n')
    await waitFor(() => expect(cell('critical', 'availability')).toHaveValue('1'))
    expect(cell('critical', 'note')).toHaveValue('2')
    expect(note()).toHaveTextContent('2 hücre yapıştırıldı. 3 değer tablonun dışında kaldı ve alınmadı.')
    expect(note()).toHaveAttribute('data-outside', '3')
    // Odak hücresinin üstü / solu hiç değişmedi
    expect(cell('total', 'functions')).toHaveValue('')
    expect(cell('critical', 'functions')).toHaveValue('')
  })

  it('sınırda kesilen metin hücreleri ve okunmayan uzun metin notta söylenir (sessiz kalmaz)', async () => {
    await openEditor()
    const start = cell('total', 'note')
    start.focus()
    const long = 'x'.repeat(250)
    paste(start, `${long}\n${long}\n`)
    await waitFor(() => expect(cell('total', 'note')).toHaveValue('x'.repeat(200)))
    expect(cell('critical', 'note')).toHaveValue('x'.repeat(200))
    expect(note()).toHaveTextContent('2 hücre yapıştırıldı. 2 metin hücresi 200 karakterde kesildi.')
    expect(note()).toHaveAttribute('data-truncated', '2')
    expect(note()).toHaveAttribute('data-cut', 'false')
    // 200.000 karakteri aşan metnin kalanı okunmaz ve söylenir
    paste(cell('total', 'functions'), `1\t2\n${'9'.repeat(200_001)}`)
    await waitFor(() => expect(note()).toHaveAttribute('data-cut', 'true'))
    expect(note()).toHaveTextContent('Yapıştırılan metin çok uzundu; ilk 200.000 karakterden sonrası okunmadı.')
  })

  it('tek değer bugünkü gibi: yapıştırma engellenmez, not yazılmaz', async () => {
    await openEditor()
    const el = cell('total', 'functions')
    el.focus()
    expect(paste(el, '1250\n')).toBe(true)
    expect(note()).toBeEmptyDOMElement()
  })
})

describe('C5 — salt okunur', () => {
  it('salt okunur tabloda yapıştırma işlenmez', async () => {
    widthCtl.value = 1200
    te().report.mockResolvedValue(reportResponse({ template: TPL, can_edit: false }))
    await openEditor()
    expect(note()).toBeNull()
    const ro = document.getElementById('te-c-sla-total-functions')
    expect(paste(ro, '1\t2')).toBe(true)
  })
})
