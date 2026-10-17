import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { unzipSync, strFromU8 } from 'fflate'
import { channel } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kanalın yıl arşivi — ZIP (2026-10-17, C12): kanal görünümünün rapor araç çubuğunda "Yıl arşivi (ZIP)" menüsü; seçilen
// yılın PDF'leri SIRAYLA alınır (429 → 3 sn sonra yeniden), tarayıcıda paketlenir; durum satırı "PDF'ler hazırlanıyor… n / N"
// + Vazgeç; eksik aylar missing.txt + bildirim; iptalde hiçbir şey indirilmez. Paylaşımla okuyan da görür.
const dl = vi.hoisted(() => ({ calls: [] }))
vi.mock('../utils/csvExport.js', async (orig) => ({ ...(await orig()), downloadBlob: (name, blob) => { dl.calls.push({ name, blob }) } }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), createReport: vi.fn(), report: vi.fn(), deleteReport: vi.fn(),
      pdfBlob: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const MONTHS = ['', 'Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']
const REPORTS = [
  ...[9, 3, 1].map((m) => ({ id: 2600 + m, year: 2026, month: m, status: 'draft', title: `Mobil — ${MONTHS[m]} 2026`, can_delete: true })),
  ...[12, 11].map((m) => ({ id: 2500 + m, year: 2025, month: m, status: 'sent', title: `Mobil — ${MONTHS[m]} 2025`, can_delete: false })),
]
const pdfOf = (id) => ({ success: true, blob: new Blob([`%PDF-1.4 rapor ${id}`], { type: 'application/pdf' }), name: `mobil-teknik-hata-${id}.pdf` })
const unzipped = async (blob) => unzipSync(new Uint8Array(await blob.arrayBuffer()))

function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

async function openMenu() {
  render(<TechErrorReportsPage />)
  const trigger = await screen.findByRole('button', { name: /Yıl arşivi \(ZIP\)/ })
  expect(trigger).toHaveAttribute('data-slot', 'te-year-zip')
  pressMenuTrigger(trigger)
  return trigger
}

beforeEach(() => {
  vi.clearAllMocks()
  dl.calls = []
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil Şube'), reports: REPORTS,
    default_period: { year: 2026, month: 9 } } })
})
afterEach(() => { vi.useRealTimers() })

describe('kanal yıl arşivi (ZIP)', () => {
  it('menü raporu olan yılları sayılarıyla listeler (en yeni önce); paylaşımla okuyanda da görünür', async () => {
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil Şube', { can_edit: false, can_report: false, shared_access: true }),
      reports: REPORTS, default_period: { year: 2026, month: 9 } } })
    await openMenu()
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((i) => i.textContent.trim())).toEqual(['2026 — 3 rapor', '2025 — 2 rapor'])
    expect(items[0]).toHaveAttribute('data-year', '2026')
  })

  it('PDF\'ler SIRAYLA (ay sırasıyla, tek istek); ZIP sunucunun adlarıyla, baytlar aynı; durum satırı ilerler; başarı bildirimi', async () => {
    const gates = { 2601: deferred(), 2603: deferred(), 2609: deferred() }
    let inFlight = 0
    let maxInFlight = 0
    te().pdfBlob.mockImplementation(async (id) => {
      inFlight++
      maxInFlight = Math.max(maxInFlight, inFlight)
      await gates[id].promise
      inFlight--
      return pdfOf(id)
    })
    await openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: '2026 — 3 rapor' }))
    const status = document.querySelector('[data-slot="te-year-zip-status"]')
    expect(status).toHaveAttribute('aria-live', 'polite')
    await waitFor(() => expect(status).toHaveTextContent('2026 arşivi · PDF\'ler hazırlanıyor… 1 / 3'))
    // Süren arşivde tetik odaklanabilir kalır, menü öğeleri kapalı (ikinci arşiv başlamaz)
    const trigger = screen.getByRole('button', { name: /Yıl arşivi \(ZIP\)/ })
    expect(trigger).not.toBeDisabled()
    expect(trigger).toHaveAttribute('data-running', 'true')
    pressMenuTrigger(trigger)
    expect(await screen.findByText(/Bir yıl arşivi hazırlanıyor/)).toBeInTheDocument()
    const busyItem = screen.getByRole('menuitem', { name: '2025 — 2 rapor' })
    expect(busyItem).toHaveAttribute('data-disabled')
    fireEvent.click(busyItem)
    pressMenuTrigger(trigger)
    expect(te().pdfBlob).toHaveBeenCalledTimes(1)
    expect(te().pdfBlob.mock.calls[0][0]).toBe(2601)
    expect(te().pdfBlob.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
    await act(async () => { gates[2601].resolve() })
    await waitFor(() => expect(status).toHaveTextContent('2 / 3'))
    expect(te().pdfBlob.mock.calls.map((c) => c[0])).toEqual([2601, 2603])
    await act(async () => { gates[2603].resolve() })
    await act(async () => { gates[2609].resolve() })
    await waitFor(() => expect(dl.calls).toHaveLength(1))
    expect(maxInFlight).toBe(1)
    expect(te().pdfBlob.mock.calls.map((c) => c[0])).toEqual([2601, 2603, 2609])
    expect(dl.calls[0].name).toBe('mobil-sube-2026-teknik-hata-raporlari.zip')
    expect(dl.calls[0].blob.type).toBe('application/zip')
    const files = await unzipped(dl.calls[0].blob)
    expect(Object.keys(files).sort()).toEqual(['mobil-teknik-hata-2601.pdf', 'mobil-teknik-hata-2603.pdf', 'mobil-teknik-hata-2609.pdf'])
    expect(strFromU8(files['mobil-teknik-hata-2603.pdf'])).toBe('%PDF-1.4 rapor 2603')
    expect(await screen.findByText('2026 arşivi indirildi (3 raporun PDF\'i).')).toBeInTheDocument()
    await waitFor(() => expect(status).toHaveTextContent(''))
    expect(screen.getByRole('button', { name: /Yıl arşivi \(ZIP\)/ })).not.toHaveAttribute('data-running')
  })

  it('429 → 3 sn bekleyip aynı raporu yeniden ister, sonra başarı; bekleme durum satırında söylenir', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    te().pdfBlob
      .mockResolvedValueOnce({ success: false, status: 429, error: 'Şu anda başka raporların PDF\'i hazırlanıyor.' })
      .mockImplementation(async (id) => pdfOf(id))
    await openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: '2025 — 2 rapor' }))
    const status = document.querySelector('[data-slot="te-year-zip-status"]')
    await waitFor(() => expect(status).toHaveTextContent(/Sunucu başka PDF'ler hazırlıyor.*1 \/ 2/))
    expect(te().pdfBlob).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    await waitFor(() => expect(dl.calls).toHaveLength(1))
    expect(te().pdfBlob.mock.calls.map((c) => c[0])).toEqual([2511, 2511, 2512])
    const files = await unzipped(dl.calls[0].blob)
    expect(Object.keys(files)).toHaveLength(2)
    expect(Object.keys(files)).not.toContain('missing.txt')
  })

  it('Vazgeç: süren istek dönünce durur, kalan PDF\'ler istenmez, hiçbir şey indirilmez', async () => {
    const gate = deferred()
    te().pdfBlob.mockImplementation(async (id, { signal }) => {
      await gate.promise
      return signal?.aborted ? { success: false, aborted: true } : pdfOf(id)
    })
    await openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: '2026 — 3 rapor' }))
    const cancel = await screen.findByRole('button', { name: 'Vazgeç' })
    expect(cancel).toHaveAttribute('data-slot', 'te-year-zip-cancel')
    fireEvent.click(cancel)
    expect(document.querySelector('[data-slot="te-year-zip-status"]')).toHaveTextContent('İptal ediliyor…')
    await act(async () => { gate.resolve() })
    expect(await screen.findByText('Yıl arşivi iptal edildi; hiçbir dosya indirilmedi.')).toBeInTheDocument()
    expect(te().pdfBlob).toHaveBeenCalledTimes(1)
    expect(dl.calls).toEqual([])
  })

  it('bir kısmı alınamazsa başarılılarla ZIP + missing.txt; bildirim eksik ayları söyler (sessiz yarım arşiv yok)', async () => {
    te().pdfBlob.mockImplementation(async (id) => (id === 2603
      ? { success: false, status: 500, error: 'PDF üretilemedi; birkaç dakika sonra yeniden deneyin.' }
      : { ...pdfOf(id), name: null }))
    await openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: '2026 — 3 rapor' }))
    await waitFor(() => expect(dl.calls).toHaveLength(1))
    const files = await unzipped(dl.calls[0].blob)
    // Sunucu adı gelmezse <kanal>-<yıl>-<ay>.pdf
    expect(Object.keys(files).sort()).toEqual(['missing.txt', 'mobil-sube-2026-01.pdf', 'mobil-sube-2026-09.pdf'])
    const missing = strFromU8(files['missing.txt'])
    expect(missing).toContain('Bu arşive eklenemeyen raporlar — Mobil Şube, 2026')
    expect(missing).toContain('- Mart 2026 — PDF üretilemedi; birkaç dakika sonra yeniden deneyin.')
    expect(await screen.findByText(/ZIP indirildi, ancak 3 rapordan yalnız 2 tanesi içinde\. Eksik aylar: Mart 2026\./)).toBeInTheDocument()
  })

  it('hiçbiri alınamazsa ZIP yok, bildirim denenen ayları söyler', async () => {
    te().pdfBlob.mockResolvedValue({ success: false, status: 403, error: 'Bu raporu görme izniniz yok.' })
    await openMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: '2025 — 2 rapor' }))
    expect(await screen.findByText(/2025 yılının hiçbir raporunun PDF'i alınamadı; arşiv oluşturulmadı\. Denenen aylar: Kasım 2025, Aralık 2025\./)).toBeInTheDocument()
    expect(dl.calls).toEqual([])
  })

  it('raporu olmayan kanalda menü yok', async () => {
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'), reports: [], default_period: { year: 2026, month: 9 } } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil' })
    expect(screen.queryByRole('button', { name: /Yıl arşivi/ })).toBeNull()
    expect(within(document.body).queryByText(/arşivi ·/)).toBeNull()
  })
})
