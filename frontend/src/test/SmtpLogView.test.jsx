import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('../contexts/TeamDirectoryProvider.jsx', () => ({
  useTeamDirectory: () => ({ byId: {}, open: () => {} }),
}))
// recharts: jsdom'da ResponsiveContainer 0×0 ölçer, grafik çizilmez; sarmalayıcıyı geçirip veriyi DOM'a düşür.
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div data-testid="chart">{children}</div>,
  BarChart: ({ children, data, onClick }) => <div data-testid="barchart" onClick={() => onClick?.({ activePayload: [{ payload: data?.[0] }] })}>{data?.length ?? 0} kova{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null, CartesianGrid: () => null, Tooltip: () => null,
}))

const { smtpLog } = vi.hoisted(() => ({
  smtpLog: { search: vi.fn(), summary: vi.fn(), export: vi.fn(), detail: vi.fn(), resend: vi.fn() },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: { admin: { smtpLog } },
}))
import SmtpLogView from '../components/admin/SmtpLogView.jsx'

const SUMMARY = {
  from: '2026-09-12T12:00:00', to: null, granularity: 'day',
  kpi: { total: 6, sent: 2, failed: 3, skipped: 1, queued: 0, success_rate: 40.0, last_sent_at: '2026-09-19T11:55:00', last_failed_at: '2026-09-19T11:59:00', failed_recipients: 3 },
  timeline: [{ bucket: '2026-09-18', sent: 1, failed: 0, skipped: 0 }, { bucket: '2026-09-19', sent: 1, failed: 3, skipped: 1 }],
  teams: [{ team_id: 2, team_name: 'Takım B', total: 3, sent: 1, failed: 1, skipped: 1, success_rate: 50.0 }, { team_id: 1, team_name: 'Takım A', total: 2, sent: 1, failed: 1, skipped: 0, success_rate: 50.0 }],
  error_classes: [{ error_class: 'RECIPIENT', count: 1 }, { error_class: 'TIMEOUT', count: 1 }, { error_class: 'AUTH', count: 1 }],
  triggers: [{ trigger: 'INITIAL', count: 2 }],
  top_recipients: [{ recipient_email: 'ops@example.com', recipient_name: 'Ops', total: 2, failed: 1, last_at: '2026-09-19T11:56:00', last_failed_at: '2026-09-19T11:56:00' }],
  top_domains: [{ domain: 'a.example.com', team_id: 1, team_name: 'Takım A', total: 2, failed: 1, last_failed_at: '2026-09-19T11:56:00' }],
}
const ROWS = [
  { id: 5, alert_event_id: null, sent_at: '2026-09-19T11:59:00', recipient_name: 'Admin', recipient_email: 'admin@example.com', subject: 'SMTP test', kind: 'FAILED', error: '535 Authentication failed', error_class: 'AUTH', trigger: 'MANUAL', team_id: null, team_name: null, domain: null },
  { id: 2, alert_event_id: 10, sent_at: '2026-09-19T11:56:00', recipient_name: 'Ops', recipient_email: 'ops@example.com', subject: '[CRITICAL] a.example.com down', kind: 'FAILED', error: '550 5.1.1 mailbox unavailable', error_class: 'RECIPIENT', trigger: 'ESCALATION', team_id: 1, team_name: 'Takım A', domain: 'a.example.com' },
  { id: 1, alert_event_id: 10, sent_at: '2026-09-19T11:55:00', recipient_name: 'Ops', recipient_email: 'ops@example.com', subject: '[CRITICAL] a.example.com down', kind: 'SENT', error: '', error_class: null, trigger: 'INITIAL', team_id: 1, team_name: 'Takım A', domain: 'a.example.com' },
]
const DETAIL = { ...ROWS[1], email_status: 'FAILED: 550 5.1.1 mailbox unavailable', sender_email: 'noreply@example.com', message: '<html><body>gövde</body></html>', alert_level: 'CRITICAL',
  alert: { id: 10, domain: 'a.example.com', type: 'HTTP_DOWN', level: 'CRITICAL', resolved: false },
  chain: [{ id: 2, sent_at: '2026-09-19T11:56:00', trigger: 'ESCALATION', recipient_email: 'ops@example.com', kind: 'FAILED' }, { id: 1, sent_at: '2026-09-19T11:55:00', trigger: 'INITIAL', recipient_email: 'ops@example.com', kind: 'SENT' }] }

/** SMTP Gönderim Logu v2 (2026-09-19): sunucu taraflı süzgeç/sayfa, KPI tıklaması, kırılım tıklaması, detay + zincir, yeniden gönderim, CSV. */
describe('SmtpLogView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=health&view=smtp')
    smtpLog.summary.mockResolvedValue({ success: true, data: SUMMARY })
    smtpLog.search.mockImplementation((p) => Promise.resolve({ success: true, data: p.status === 'FAILED' ? ROWS.filter((r) => r.kind === 'FAILED') : ROWS, total: p.status === 'FAILED' ? 2 : 6, page: p.page, size: p.size }))
    smtpLog.detail.mockResolvedValue({ success: true, data: DETAIL })
    smtpLog.resend.mockResolvedValue({ success: true, sent: true, status: 'SENT', new_log_id: 99 })
    smtpLog.export.mockResolvedValue({ success: true, data: ROWS, count: 3, capped: false })
  })

  it('yükler: KPI şeridi, kırılımlar, tablo satırları; özet + arama AYNI paramla (from 7 gün, to açık uçlu)', async () => {
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('550 5.1.1 mailbox unavailable')
    expect(smtpLog.summary).toHaveBeenCalledTimes(1)
    const sp = smtpLog.summary.mock.calls[0][0], rp = smtpLog.search.mock.calls[0][0]
    expect(sp.from).toBe(rp.from); expect(sp.to).toBeNull(); expect(rp.page).toBe(0); expect(rp.size).toBe(25)
    expect(sp.from).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    expect(screen.getByText('%40')).toBeInTheDocument()                    // başarı oranı
    expect(document.querySelector('.rn-count').textContent).toMatch(/6 kayıt|6 records/)
    expect(screen.getAllByText(/Kimlik doğrulama|Authentication/).length).toBeGreaterThan(0)   // hata sınıfı çubuğu + satır rozeti
    expect(screen.getByText('2 kova')).toBeInTheDocument()                     // grafik verisi
    expect(screen.getAllByText('Takım A').length).toBeGreaterThan(0)
    expect(document.querySelectorAll('.sml-table tbody tr')).toHaveLength(3)
  })

  it('KPI "Başarısız" tıklanınca status=FAILED ile yeniden sorgular (2 satır), tekrar tıklayınca süzgeç kalkar; hata sınıfı çubuğu errorClass geçirir', async () => {
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('550 5.1.1 mailbox unavailable')
    fireEvent.click(screen.getByRole('button', { name: /3\s*Başarısız|3\s*Failed/ }))
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'FAILED', page: 0 })))
    await waitFor(() => expect(document.querySelectorAll('.sml-table tbody tr')).toHaveLength(2))
    await waitFor(() => expect(window.location.search).toContain('m_status=FAILED'))   // useUrlQuerySync 300 ms debounce
    fireEvent.click(screen.getByRole('button', { name: /3\s*Başarısız|3\s*Failed/ }))
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ status: '' })))
    fireEvent.click(screen.getByRole('button', { name: /Zaman aşımı|^Timeout/ }))
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ errorClass: 'TIMEOUT' })))
    // Filtreleri temizle
    fireEvent.click(screen.getByRole('button', { name: /Filtreleri temizle|Clear filters/ }))
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ errorClass: '', status: '' })))
  })

  it('takım kırılımı satırı teamId süzer; alan satırı domain çipi ekler ve çip kaldırılınca süzgeç düşer', async () => {
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('550 5.1.1 mailbox unavailable')
    fireEvent.click(within(document.querySelector('.sml-grid')).getByRole('button', { name: 'Takım B' }))
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ teamId: '2' })))
    fireEvent.click(within(document.querySelector('.sml-grid')).getByRole('button', { name: 'a.example.com' }))
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ domain: 'a.example.com' })))
    const chip = await screen.findByRole('button', { name: /Sertifika: a.example.com|Certificate: a.example.com/ })
    fireEvent.click(chip)
    await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ domain: '' })))
  })

  it('arama kutusu 300 ms sonra q geçirir; sütun başlığı sıralamayı değiştirir; grafik çubuğu o güne süzer (özel aralık)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      render(<SmtpLogView onBack={() => {}} />)
      await screen.findByText('550 5.1.1 mailbox unavailable')
      fireEvent.change(screen.getByPlaceholderText(/Alıcı, konu|Search recipient/), { target: { value: 'mailbox' } })
      await act(async () => { await vi.advanceTimersByTimeAsync(350) })
      await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'mailbox' })))
      fireEvent.click(screen.getByRole('button', { name: /^Alıcı$|^Recipient$/ }))
      await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'recipient,asc' })))
      fireEvent.click(screen.getByTestId('barchart'))
      await waitFor(() => expect(smtpLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-09-18T00:00:00', to: '2026-09-18T23:59:59' })))
      await act(async () => { await vi.advanceTimersByTimeAsync(350) })
      expect(window.location.search).toContain('m_range=custom')
    } finally { vi.useRealTimers() }
  })

  it('satır tıklaması detay modalını açar: ham hata, alarm rozeti, zincir (2), önizleme iframe; zincirdeki diğer satıra geçilir; "Alarmı aç" sm:navigate', async () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('550 5.1.1 mailbox unavailable')
    fireEvent.click(screen.getAllByText('[CRITICAL] a.example.com down', { selector: 'td' })[0])
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(smtpLog.detail).toHaveBeenCalledWith(2))
    await within(dlg).findByText('FAILED: 550 5.1.1 mailbox unavailable')
    expect(within(dlg).getByText(/Aynı alarmın gönderimleri \(2\)|Deliveries for the same alert \(2\)/)).toBeInTheDocument()
    expect(within(dlg).getByText(/^açık$|^open$/)).toBeInTheDocument()
    expect(dlg.querySelector('iframe.smtp-detail-iframe')).not.toBeNull()
    await waitFor(() => expect(window.location.search).toContain('m_id=2'))
    // zincirde ilk gönderime geç
    fireEvent.click(within(dlg).getAllByRole('button').find((b) => b.className.includes('sml-chain-btn') && !b.disabled))
    await waitFor(() => expect(smtpLog.detail).toHaveBeenLastCalledWith(1))
    fireEvent.click(within(dlg).getByRole('button', { name: /Alarmı aç|Open alert/ }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'alerthistory', params: { incident: 10 } })
    window.removeEventListener('sm:navigate', nav)
  })

  it('yeniden gönder: yalnız FAILED satırda düğme; onay diyaloğu → api.resend → başarı toast + liste tazelenir; sunucu reddi (ALERT_RESOLVED) hata toast', async () => {
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('550 5.1.1 mailbox unavailable')
    const resendBtns = document.querySelectorAll('.sml-resend')
    expect(resendBtns).toHaveLength(2)                                   // 3 satırın 2'si FAILED
    fireEvent.click(resendBtns[1])                                        // satır #2 (ops@example.com)
    const dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toContain('ops@example.com')
    fireEvent.click(within(dlg).getByRole('button', { name: /Yeniden gönder|Resend/ }))
    await waitFor(() => expect(smtpLog.resend).toHaveBeenCalledWith(2))
    await screen.findByText(/Yeniden gönderildi \(log #99\)|Resent \(log #99\)/)
    await waitFor(() => expect(smtpLog.search.mock.calls.length).toBeGreaterThanOrEqual(2))
    smtpLog.resend.mockResolvedValueOnce({ success: false, error: 'ALERT_RESOLVED' })
    fireEvent.click(document.querySelectorAll('.sml-resend')[0])
    const dlg2 = await screen.findByRole('dialog')
    fireEvent.click(within(dlg2).getByRole('button', { name: /Yeniden gönder|Resend/ }))
    await screen.findByText(/Alarm çözülmüş|The alert is resolved/)
  })

  it('CSV: export ucu süzgeçle çağrılır, dosya indirilir (BOM + başlık satırı), toast satır sayısını söyler', async () => {
    const createObjectURL = vi.fn(() => 'blob:x'); const revoke = vi.fn()
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true })
    Object.defineProperty(URL, 'revokeObjectURL', { value: revoke, configurable: true })
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('550 5.1.1 mailbox unavailable')
    fireEvent.click(screen.getByRole('button', { name: /CSV/ }))
    await waitFor(() => expect(smtpLog.export).toHaveBeenCalledWith(expect.objectContaining({ sort: 'sent_at,desc' })))
    await screen.findByText(/3 satır dışa aktarıldı|3 rows exported/)
    const blob = createObjectURL.mock.calls[0][0]
    const bytes = new Uint8Array(await blob.arrayBuffer())
    expect([bytes[0], bytes[1], bytes[2]]).toEqual([0xEF, 0xBB, 0xBF])       // UTF-8 BOM (Excel); TextDecoder BOM'u yuttuğu için bayt düzeyinde
    const text = new TextDecoder().decode(bytes)
    expect(text.split('\r\n')).toHaveLength(4)                            // başlık + 3 satır
    expect(text).toMatch(/Alıcı reddi|Recipient rejected/)
    click.mockRestore()
  })

  it('geri düğmesi onBack; initial (kart periyodu/derin bağlantı) süzgeçleri kurar: range 24h + status FAILED + domain', async () => {
    const onBack = vi.fn()
    render(<SmtpLogView onBack={onBack} initial={{ range: '24h', status: 'FAILED', domain: 'a.example.com' }} />)
    await waitFor(() => expect(smtpLog.search).toHaveBeenCalledWith(expect.objectContaining({ status: 'FAILED', domain: 'a.example.com' })))
    const from = new Date(smtpLog.search.mock.calls[0][0].from + 'Z')
    expect(Date.now() - from.getTime()).toBeLessThan(24 * 3600e3 + 60e3)
    expect(Date.now() - from.getTime()).toBeGreaterThan(24 * 3600e3 - 60e3)
    fireEvent.click(screen.getByRole('button', { name: /Sistem Sağlığı|System Health/ }))
    expect(onBack).toHaveBeenCalled()
  })

  it('uç hata → uyarı bandı; boş sonuç → durum bloğu', async () => {
    smtpLog.summary.mockResolvedValueOnce({ success: false, error: 'boom' })
    render(<SmtpLogView onBack={() => {}} />)
    await screen.findByText('boom')
    smtpLog.summary.mockResolvedValue({ success: true, data: { ...SUMMARY, kpi: { total: 0 }, teams: [], error_classes: [], top_recipients: [], top_domains: [], timeline: [] } })
    smtpLog.search.mockResolvedValue({ success: true, data: [], total: 0 })
    fireEvent.click(screen.getByRole('button', { name: /^Yenile$|^Refresh$/ }))
    await screen.findByText(/Filtreyle eşleşen kayıt bulunamadı|No records match the filter/)
  })
})
