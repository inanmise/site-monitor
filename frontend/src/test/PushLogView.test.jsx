import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div data-testid="chart">{children}</div>,
  BarChart: ({ children, data, onClick }) => <div data-testid="barchart" onClick={() => onClick?.({ activePayload: [{ payload: data?.[0] }] })}>{data?.length ?? 0} kova{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null, CartesianGrid: () => null, Tooltip: () => null,
}))
const { pushLog } = vi.hoisted(() => ({ pushLog: { search: vi.fn(), summary: vi.fn(), export: vi.fn(), detail: vi.fn(), requeue: vi.fn() } }))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: { admin: { pushLog, smtpLog: {} } },
}))
import PushLogView from '../components/admin/PushLogView.jsx'

const SUMMARY = {
  granularity: 'day',
  kpi: { total: 6, sent: 1, failed: 2, pending: 1, blocked: 1, skipped: 1, success_rate: 33.3, last_sent_at: '2026-09-19T11:55:00', failed_users: 2 },
  timeline: [{ bucket: '2026-09-18', sent: 1, failed: 0, pending: 0, skipped: 0 }, { bucket: '2026-09-19', sent: 0, failed: 2, pending: 2, skipped: 1 }],
  teams: [{ team_id: 2, team_name: 'Takım B', total: 3, sent: 0, failed: 1, pending: 2, success_rate: 0 }, { team_id: 1, team_name: 'Takım A', total: 2, sent: 1, failed: 1, pending: 0, success_rate: 50 }],
  error_classes: [{ error_class: 'SERVER', count: 1 }, { error_class: 'TIMEOUT', count: 1 }],
  top_users: [{ username: 'u3', display_name: 'Üç Kullanıcı', total: 2, failed: 1, last_failed_at: '2026-09-19T11:56:00' }],
  top_monitors: [{ monitor_type: 'HTTP', monitor_id: 1, monitor_name: 'gw', total: 3, failed: 1 }],
  levels: [{ level: 'HIGH', total: 4, sent: 0, failed: 1 }, { level: 'CRITICAL', total: 2, sent: 1, failed: 1 }],
  triggers: [{ trigger: 'INITIAL', count: 6 }],
}
const ROWS = [
  { id: 2, alert_event_id: 10, trigger: 'INITIAL', monitor_type: 'HTTP', monitor_name: 'api', team_id: 1, team_name: 'Takım A', alert_level: 'CRITICAL', username: 'u2', display_name: 'İki', title: 'Site Monitor', status: 'FAILED', http_status: 500, error: 'Internal error', attempts: 3, at: '2026-09-19T11:54:00', kind: 'FAILED', error_class: 'SERVER', batch_id: 'b1' },
  { id: 6, alert_event_id: 12, trigger: 'ESCALATION', monitor_type: 'PING', monitor_name: 'gw', team_id: 2, team_name: 'Takım B', alert_level: 'HIGH', username: 'u1', display_name: 'Bir', status: 'RATE_LIMITED', at: '2026-09-19T11:59:00', kind: 'BLOCKED' },
  { id: 1, alert_event_id: 10, trigger: 'INITIAL', monitor_type: 'HTTP', monitor_name: 'api', team_id: 1, team_name: 'Takım A', alert_level: 'CRITICAL', username: 'u1', display_name: 'Bir', status: 'SENT', http_status: 200, at: '2026-09-19T11:55:00', kind: 'SENT', notification_id: 'n-1', batch_id: 'b1' },
]
const DETAIL = { ...ROWS[0], created_at: '2026-09-19T11:54:00', message: 'KRİTİK api down', raw_response: '{"error":"boom"}',
  batch: [{ id: 1, username: 'u1', display_name: 'Bir', kind: 'SENT', status: 'SENT', http_status: 200 }, { id: 2, username: 'u2', display_name: 'İki', kind: 'FAILED', status: 'FAILED', http_status: 500 }] }

/** Webhook Push Gönderim Logu (2026-09-19): KPI/tablo, durum ve seviye süzgeçleri, detay (batch), yeniden kuyruk, CSV. */
describe('PushLogView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=health&view=push')
    pushLog.summary.mockResolvedValue({ success: true, data: SUMMARY })
    pushLog.search.mockImplementation((p) => Promise.resolve({ success: true, data: p.status ? ROWS.filter((r) => r.kind === p.status) : ROWS, total: p.status ? ROWS.filter((r) => r.kind === p.status).length : 6 }))
    pushLog.detail.mockResolvedValue({ success: true, data: DETAIL })
    pushLog.requeue.mockResolvedValue({ success: true, queued: true })
    pushLog.export.mockResolvedValue({ success: true, data: ROWS, count: 3, capped: false })
  })

  it('yükler: KPI (kuyrukta/engellendi dâhil), kırılımlar, satırlar; özet + arama aynı from', async () => {
    render(<PushLogView onBack={() => {}} />)
    await screen.findByText('Internal error')
    expect(pushLog.summary.mock.calls[0][0].from).toBe(pushLog.search.mock.calls[0][0].from)
    expect(screen.getByText('%33.3')).toBeInTheDocument()
    expect(document.querySelector('.rn-count').textContent).toMatch(/6 kayıt|6 records/)
    expect(screen.getAllByText(/Sunucu hatası \(5xx\)|Server error \(5xx\)/).length).toBeGreaterThan(0)
    expect(document.querySelectorAll('.sml-table tbody tr')).toHaveLength(3)
    expect(screen.getByText(/3 deneme|3 attempts/)).toBeInTheDocument()
    expect(screen.getAllByText(/HTTP 500/).length).toBeGreaterThan(0)   // 'HTTP 500 · 3 deneme' aynı düğümde
    // 2026-09-25 (R15): satır eylemlerinin ADI kaydı ayırır (izleme + zaman) — yeniden kuyruğa alma
    // YAN ETKİLİ ve her satırda aynı adla duyuluyordu. İpucu kısa kalır.
    const detailNames = screen.getAllByRole('button', { name: /— (Push Detayı|Push Details)$/ }).map((b) => b.getAttribute('aria-label'))
    expect(detailNames).toHaveLength(3)
    expect(new Set(detailNames).size).toBe(3)
    expect(detailNames).toContain(`api · 2026-09-19 11:54 — ${detailNames[0].endsWith('Details') ? 'Push Details' : 'Push Detayı'}`)
  })

  it('KPI Engellendi → status=BLOCKED; seviye kırılımı → level; alıcı satırı → username çipi; filtreleri temizle', async () => {
    render(<PushLogView onBack={() => {}} />)
    await screen.findByText('Internal error')
    fireEvent.click(screen.getByRole('button', { name: /1\s*Engellendi|1\s*Blocked/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'BLOCKED' })))
    await waitFor(() => expect(document.querySelectorAll('.sml-table tbody tr')).toHaveLength(1))
    // kırılım paneli v2 (2026-09-21): satırdaki RAKAM tıklanır — Toplam → yalnız boyut (durum süzgeci kalkar)
    const rowOf = (re) => [...document.querySelectorAll('.pbp-row')].find((r) => re.test(r.textContent))
    fireEvent.click(within(rowOf(/HIGH/)).getByRole('button', { name: /Toplam|Total/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ level: 'HIGH', status: '' })))
    fireEvent.click(within(rowOf(/Üç Kullanıcı/)).getByRole('button', { name: /Toplam|Total/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ username: 'u3' })))
    expect(screen.getByRole('button', { name: /Alıcı: u3|Recipient: u3/ })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Filtreleri temizle|Clear filters/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ status: '', level: '', username: '' })))
  })

  it('detay: mesaj, ham yanıt, batch alıcıları (2), "Alarmı aç"; yeniden kuyruk onay → api.requeue → toast', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(<PushLogView onBack={() => {}} />)
    await screen.findByText('Internal error')
    fireEvent.click(document.querySelectorAll('.sml-resend')[0])           // satır #2 FAILED → yeniden kuyruk
    const confirm = await screen.findByRole('dialog')
    fireEvent.click(within(confirm).getByRole('button', { name: /Yeniden kuyruğa al|Requeue/ }))
    await waitFor(() => expect(pushLog.requeue).toHaveBeenCalledWith(2))
    await screen.findByText(/Yeniden kuyruğa alındı|Requeued/)
    fireEvent.click(screen.getAllByText('api')[0].closest('tr'))
    const dlg = await screen.findByRole('dialog')
    await within(dlg).findByText('KRİTİK api down')
    expect(within(dlg).getByText('{"error":"boom"}')).toBeInTheDocument()
    expect(within(dlg).getByText(/Aynı toplu isteğin alıcıları \(2\)|Recipients in the same batch \(2\)/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /Alarmı aç|Open alert/ }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { incident: 10 } })
    window.removeEventListener('sm:navigate', nav)
  })

  it('CSV dışa aktar + geri düğmesi + initial süzgeçleri', async () => {
    Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:x'), configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const onBack = vi.fn()
    render(<PushLogView onBack={onBack} initial={{ range: '24h', status: 'FAILED' }} />)
    await waitFor(() => expect(pushLog.search).toHaveBeenCalledWith(expect.objectContaining({ status: 'FAILED' })))
    await screen.findByText('Internal error')
    fireEvent.click(screen.getByRole('button', { name: /CSV/ }))
    await screen.findByText(/3 satır dışa aktarıldı|3 rows exported/)
    fireEvent.click(screen.getByRole('button', { name: /Sistem Sağlığı|System Health/ }))
    expect(onBack).toHaveBeenCalled()
    click.mockRestore()
  })

  it('takım / alıcı rozetine tıklamak satır detayını AÇMAZ (2026-09-20 kullanıcı bildirimi); hata sınıfları paneli kaldırıldı, mini tablolar sabit yerleşimli', async () => {
    render(<PushLogView onBack={() => {}} />)
    await screen.findByText('Internal error')
    const row = document.querySelector('.sml-table tbody tr.smtp-log-row')
    const stop = row.querySelector('.sml-stop')
    expect(stop).toBeTruthy()
    fireEvent.click(stop)
    expect(pushLog.detail).not.toHaveBeenCalled()
    fireEvent.click(row)
    await waitFor(() => expect(pushLog.detail).toHaveBeenCalled())
    expect(screen.queryByText(/^Hata sınıfları$|^Error classes$/)).toBeNull()
    expect(document.querySelector('.pbp-row .pbp-num')).toBeTruthy()   // kırılım paneli v2
  })

  it('kırılım paneli v2: takım satırında "Başarısız" rakamı → teamId + status=FAILED; ikinci tıklama süzgeci kaldırır; izleme satırı q ile süzer; başlık daraltılır', async () => {
    render(<PushLogView onBack={() => {}} />)
    await screen.findByText('Internal error')
    const rowOf = (re) => [...document.querySelectorAll('.pbp-row')].find((r) => re.test(r.textContent))
    const teamRow = rowOf(/Takım A/)
    expect(teamRow.querySelector('.pbp-bar')).toBeTruthy()
    fireEvent.click(within(teamRow).getByRole('button', { name: /Başarısız|Failed/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ teamId: '1', status: 'FAILED' })))
    await waitFor(() => expect(rowOf(/Takım A/).className).toContain('is-active'))
    expect(within(rowOf(/Takım A/)).getByRole('button', { name: /Başarısız|Failed/ }).className).toContain('is-on')
    fireEvent.click(within(rowOf(/Takım A/)).getByRole('button', { name: /Başarısız|Failed/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ teamId: '', status: '' })))
    // izleme satırı: Gönderildi → q + SENT
    fireEvent.click(within(rowOf(/HTTPgw/)).getByRole('button', { name: /Gönderildi|Sent/ }))
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'gw', status: 'SENT' })))
    // panel daraltma
    const head = document.querySelector('[data-testid=pbp] .pbp-head')
    fireEvent.click(head)
    expect(head.getAttribute('aria-expanded')).toBe('false')
    expect(head.closest('[data-testid=pbp]').querySelector('.pbp-list')).toBeNull()
  })
})
