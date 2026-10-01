import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { useT } from '../i18n/index.jsx'

/**
 * Haftalık erişilebilirlik e-postası gönderim logu (2026-10-01 shadcn yeniden tasarımı): son çalışma özeti, süzgeç
 * işlevli sayım kutuları, arama / takım / tür süzgeçleri, kayıt sayısı, gün gruplu liste, CSV, ayrıntı penceresi
 * (hata nedeni, alıcılar, önizleme genişliği, önceki / sonraki), yükleme hatası + "Tekrar dene".
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: withApiFallback({ admin: { getWeeklyAvailHistory: vi.fn(), getWeeklyAvailHistoryItem: vi.fn() } }),
}))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: vi.fn() }))
import { api } from '../api/client'
import { downloadCsv } from '../utils/csvExport.js'
import WeeklyAvailLogsModal from '../components/admin/health/WeeklyAvailLogsModal.jsx'
import { waKind, waError, filterWa, waCounts, groupByDay, lastRun, istDay, splitAddrs } from '../components/admin/health/weeklyLogModel.js'

const ROWS = [
  { id: 9, sent_at: '2026-09-28T07:30:00', team: 'Ödeme', to: 'odeme@example.com, lider@example.com', cc: 'mudur@example.com', subject: 'Haftalık rapor — Ödeme', status: 'SENT', trigger: 'WEEKLY_AVAILABILITY' },
  { id: 8, sent_at: '2026-09-28T07:30:05', team: 'Kart', to: 'kart@example.com', cc: null, subject: 'Haftalık rapor — Kart', status: 'FAILED: relay denied', trigger: 'WEEKLY_AVAILABILITY' },
  { id: 7, sent_at: '2026-09-28T07:30:09', team: 'Ağ', to: '', cc: null, subject: 'Haftalık rapor — Ağ', status: 'NO_RECIPIENT', trigger: 'WEEKLY_AVAILABILITY' },
  { id: 6, sent_at: '2026-09-25T21:10:00', team: 'Ödeme', to: 'ben@example.com', cc: null, subject: 'TEST — Haftalık rapor', status: 'SENT', trigger: 'WEEKLY_AVAILABILITY_TEST' },
  { id: 5, sent_at: '2026-09-21T07:30:00', team: 'Kart', to: 'kart@example.com', cc: null, subject: 'Haftalık rapor — Kart', status: 'SENT', trigger: 'WEEKLY_AVAILABILITY' },
]
const detailOf = (id) => ({ ...ROWS.find((r) => r.id === id), html: '<p>rapor</p>' })

function Harness({ onClose = () => {} }) {
  const t = useT()
  return <WeeklyAvailLogsModal t={t} onClose={onClose} />
}
const dataRows = () => [...document.querySelectorAll('[data-slot="wa-row"]')]
const kpi = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)

describe('Haftalık e-posta gönderim logu — model', () => {
  it('durum, hata nedeni, adresler, İstanbul günü, süzgeç, sayım, gün grupları ve son çalışma', () => {
    expect([waKind('SENT'), waKind('FAILED: x'), waKind('NO_RECIPIENT'), waKind(null)]).toEqual(['SENT', 'FAILED', 'SKIPPED', 'UNKNOWN'])
    expect(waError({ status: 'FAILED: relay denied' })).toBe('relay denied')
    expect(waError({ status: 'SENT' })).toBeNull()
    expect(splitAddrs('a@x.com, b@x.com; c@x.com ,')).toEqual(['a@x.com', 'b@x.com', 'c@x.com'])
    expect(istDay('2026-09-28T22:30:00')).toBe('2026-09-29')   // UTC 22:30 = İstanbul ertesi gün 01:30
    expect(filterWa(ROWS, { kind: 'SENT' }).map((r) => r.id)).toEqual([9, 6, 5])
    expect(filterWa(ROWS, { type: 'test' }).map((r) => r.id)).toEqual([6])
    expect(filterWa(ROWS, { type: 'scheduled', team: 'Kart' }).map((r) => r.id)).toEqual([8, 5])
    expect(filterWa(ROWS, { q: 'LİDER' }).map((r) => r.id)).toEqual([9])   // Türkçe büyük harf duyarsız
    expect(waCounts(ROWS)).toMatchObject({ total: 5, SENT: 3, FAILED: 1, SKIPPED: 1, test: 1, rate: 75 })
    expect(groupByDay(ROWS).map((g) => [g.key, g.rows.length])).toEqual([['2026-09-28', 3], ['2026-09-26', 1], ['2026-09-21', 1]])
    const run = lastRun(ROWS)
    expect(run.day).toBe('2026-09-28')
    expect(run.counts).toMatchObject({ total: 3, SENT: 1, FAILED: 1, SKIPPED: 1 })
    expect(lastRun([ROWS[3]])).toBeNull()   // yalnız test → zamanlanmış çalışma yok
  })
})

describe('Haftalık e-posta gönderim logu — pencere', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getWeeklyAvailHistory.mockResolvedValue({ success: true, data: ROWS })
    api.admin.getWeeklyAvailHistoryItem.mockImplementation((id) => Promise.resolve({ success: true, data: detailOf(id) }))
  })

  it('son çalışma özeti, sayım kutuları, gün gruplu tablo (konu sütunu) ve hata nedeni satırda', async () => {
    render(<Harness />)
    await waitFor(() => expect(dataRows()).toHaveLength(5))
    expect(api.admin.getWeeklyAvailHistory).toHaveBeenCalledWith(100, true)
    const last = document.querySelector('[data-slot="wa-last-run"]')
    expect(last).toHaveAttribute('data-tone', 'bad')
    expect(last.textContent).toMatch(/Teams: 3/)
    expect(last.textContent).toMatch(/Success: 50%/)
    expect(kpi('total').querySelector('[data-slot="stat-value"]').textContent).toBe('5')
    expect(kpi('FAILED').querySelector('[data-slot="stat-value"]').textContent).toBe('1')
    expect(kpi('test').querySelector('[data-slot="stat-value"]').textContent).toBe('1')
    expect([...document.querySelectorAll('[data-slot="wa-day"]')].map((d) => d.getAttribute('data-day'))).toEqual(['2026-09-28', '2026-09-26', '2026-09-21'])
    const failed = dataRows().find((r) => r.getAttribute('data-kind') === 'FAILED')
    expect(failed.textContent).toMatch(/relay denied/)
    expect(failed.textContent).toMatch(/Haftalık rapor — Kart/)
    expect(dataRows()[0].textContent).toMatch(/\+1 more/)   // iki alıcı → ilk adres + "+1"
    expect(screen.getByText(/Showing 5 of 5 records/)).toBeInTheDocument()
  })

  it('sayım kutusu durumu süzer (ikinci tık kaldırır); arama, takım ve tür süzgeçleri; temizle', async () => {
    render(<Harness />)
    await waitFor(() => expect(dataRows()).toHaveLength(5))
    fireEvent.click(kpi('FAILED'))
    await waitFor(() => expect(dataRows().map((r) => r.getAttribute('data-id'))).toEqual(['8']))
    expect(kpi('FAILED')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(kpi('FAILED'))
    await waitFor(() => expect(dataRows()).toHaveLength(5))
    fireEvent.click(kpi('test'))
    await waitFor(() => expect(dataRows().map((r) => r.getAttribute('data-id'))).toEqual(['6']))
    fireEvent.click(kpi('total'))
    await waitFor(() => expect(dataRows()).toHaveLength(5))
    fireEvent.change(screen.getByRole('combobox', { name: 'Team' }), { target: { value: 'Kart' } })
    await waitFor(() => expect(dataRows().map((r) => r.getAttribute('data-id'))).toEqual(['8', '5']))
    fireEvent.click(screen.getByRole('button', { name: 'Scheduled' }))
    fireEvent.change(screen.getByRole('textbox', { name: /search team, recipient or subject/i }), { target: { value: 'nomatch' } })
    await waitFor(() => expect(screen.getByText('No delivery matches the filters')).toBeInTheDocument())
    fireEvent.click(screen.getAllByRole('button', { name: /clear filters/i })[0])
    await waitFor(() => expect(dataRows()).toHaveLength(5))
  })

  it('kayıt sayısı seçimi sunucudan yeniden ister; CSV görünen satırları indirir', async () => {
    render(<Harness />)
    await waitFor(() => expect(dataRows()).toHaveLength(5))
    fireEvent.change(document.querySelector('[data-slot="wa-limit"]'), { target: { value: '250' } })
    await waitFor(() => expect(api.admin.getWeeklyAvailHistory).toHaveBeenLastCalledWith(250, true))
    fireEvent.click(kpi('SENT'))
    await waitFor(() => expect(dataRows()).toHaveLength(3))
    fireEvent.click(screen.getByRole('button', { name: /download the 3 shown records as csv/i }))
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    const [name, csv] = downloadCsv.mock.calls[0]
    expect(name).toMatch(/^weekly-email-delivery-log-\d{8}-\d{4}\.csv$/)
    const lines = csv.slice(1).trim().split('\r\n')
    expect(lines).toHaveLength(4)
    expect(lines[0]).toBe('Date,Team,Recipients,CC,Subject,Type,Status,Error / reason')
  })

  it('satır → ayrıntı (ikinci pencere): hata bandı, alıcılar, önizleme genişliği; önceki / sonraki kayıt', async () => {
    render(<Harness />)
    await waitFor(() => expect(dataRows()).toHaveLength(5))
    fireEvent.click(dataRows().find((r) => r.getAttribute('data-id') === '8'))
    await waitFor(() => expect(document.querySelectorAll('[role="dialog"]').length).toBe(2))
    const detail = await waitFor(() => { const el = document.querySelector('[data-slot="wa-detail"]'); expect(el).not.toBeNull(); return el })
    expect(api.admin.getWeeklyAvailHistoryItem).toHaveBeenCalledWith(8)
    expect(detail).toHaveAttribute('data-kind', 'FAILED')
    expect(within(detail).getByText('Delivery failed')).toBeInTheDocument()
    expect(within(detail).getByText('relay denied')).toBeInTheDocument()
    expect(within(detail).getByText('kart@example.com')).toBeInTheDocument()
    const iframe = detail.querySelector('iframe[data-slot="wa-preview"]')
    expect(iframe).toHaveAttribute('sandbox', 'allow-popups allow-popups-to-escape-sandbox')
    expect(iframe.getAttribute('srcdoc')).toMatch(/<base target="_blank">/)
    fireEvent.click(within(detail).getByRole('button', { name: 'Phone' }))
    expect(detail.querySelector('iframe')).toHaveAttribute('data-view', 'phone')
    const dlg = screen.getAllByRole('dialog').at(-1)
    expect(within(dlg).getByText('2 of 5')).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Next record' }))
    await waitFor(() => expect(api.admin.getWeeklyAvailHistoryItem).toHaveBeenLastCalledWith(7))
    await waitFor(() => expect(document.querySelector('[data-slot="wa-detail"]')).toHaveAttribute('data-kind', 'SKIPPED'))
    expect(screen.getByText('Not sent: no recipient')).toBeInTheDocument()
    fireEvent.click(within(screen.getAllByRole('dialog').at(-1)).getByRole('button', { name: 'Previous record' }))
    await waitFor(() => expect(api.admin.getWeeklyAvailHistoryItem).toHaveBeenLastCalledWith(8))
  })

  it('yükleme hatası boş liste gibi yutulmaz: bant + "Tekrar dene"; boş arşiv StatusBlock', async () => {
    api.admin.getWeeklyAvailHistory.mockRejectedValueOnce(new Error('ağ yok')).mockResolvedValueOnce({ success: true, data: [] })
    render(<Harness />)
    expect(await screen.findByText('ağ yok')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No deliveries yet.')).toBeInTheDocument()
    expect(screen.queryByText('ağ yok')).toBeNull()
  })
})
