import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { page: {} } })),
      getPageMonitors:   vi.fn(),
      getPageHistory:    vi.fn(),
      getPageIssues:     vi.fn(),
      getConfirmations:  vi.fn(() => Promise.resolve({ success: true, data: [] })),
      createPageMonitor: vi.fn(),
      updatePageMonitor: vi.fn(),
      deletePageMonitor: vi.fn(),
      triggerPageCheck:  vi.fn(),
      testPage:          vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  },
}))
import { api } from '../api/client'

const monitor = {
  id: 1, name: 'Akbank', url: 'https://www.akbank.com/', mode: 'SINGLE_PAGE',
  group_name: 'X Sistemleri', team_name: 'SY-A', status: 'DEGRADED',
  broken_resources: 2, mixed_content_count: 0, total_resources: 12, active: true, checked_at: '2026-06-24T00:00:00',
}

describe('PageMonitorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [monitor] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  it('izleme kartını (url) listeler', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    expect(await screen.findByText('https://www.akbank.com/')).toBeInTheDocument()
  })

  it('Yeni modal açılır (form alanları görünür)', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    expect(screen.getByPlaceholderText('https://example.com')).toBeInTheDocument()
  })

  it('Test butonu testPage çağırır ve sonucu gösterir', async () => {
    // Arka plan kartı OK olsun → "Degraded" yalnız test-sonucu banner'ında görünsün (tekil eşleşme).
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, status: 'OK' }] })
    api.monitoring.testPage.mockResolvedValue({
      success: true,
      data: { status: 'DEGRADED', total_resources: 10, broken_resources: 2, mixed_content_count: 0, http_status: 200 },
    })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /new monitor|yeni izleme/i }))
    fireEvent.change(screen.getByPlaceholderText('https://example.com'), { target: { value: 'https://x.example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /^test$|test et/i }))
    await waitFor(() => expect(api.monitoring.testPage).toHaveBeenCalled())
    expect(await screen.findByText(/degraded|bozulmuş/i)).toBeInTheDocument()
  })

  it('karta tıkla → detay modalında Sorunlar + Grafik sekmeleri; issues yüklenir', async () => {
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: /issues|sorunlar/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /chart|grafik/i })).toBeInTheDocument()
  })

  it('istatistik panosu: OK/DEGRADED/DOWN ayrımı + filtreleme', async () => {
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, url: 'https://a.example.com', status: 'OK',       active: true },
      { id: 2, url: 'https://b.example.com', status: 'DEGRADED', active: true },
      { id: 3, url: 'https://c.example.com', status: 'DOWN',     active: true, active_alarm: true, alarm_acknowledged: false, alarm_level: 'CRITICAL' },
    ] })
    const { container } = render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    await screen.findByText('https://a.example.com')

    expect(container.querySelector('.stats-panel')).toBeNull()
    fireEvent.click(container.querySelector('.stats-collapse-bar'))
    expect(container.querySelector('.stats-panel')).not.toBeNull()

    expect(container.querySelector('.stat-item-total .stat-value').textContent).toBe('3')
    expect(container.querySelector('.stat-item-valid .stat-value').textContent).toBe('1')     // OK
    expect(container.querySelector('.stat-item-warning .stat-value').textContent).toBe('1')   // DEGRADED
    expect(container.querySelector('.stat-item-critical .stat-value').textContent).toBe('1')  // DOWN

    fireEvent.click(container.querySelector('.stat-item-warning'))
    await waitFor(() => expect(screen.queryByText('https://a.example.com')).not.toBeInTheDocument())
    expect(screen.getByText('https://b.example.com')).toBeInTheDocument()
    expect(screen.queryByText('https://c.example.com')).not.toBeInTheDocument()

    fireEvent.click(container.querySelector('.stat-item-warning'))
    await screen.findByText('https://a.example.com')
  })

  // ── Sorun satırından "Hariç tut" aksiyonu ──────────────────────────────────
  const brokenIssue = {
    id: 11, monitor_id: 1, resource_url: 'https://voting.institutionalinvestor.com/welcome',
    resource_type: 'LINK', source_page: 'https://www.akbank.com/', issue_type: 'BROKEN',
    first_party: false, http_status: null, duration_ms: 100, checked_at: '2026-08-03T19:45:36',
  }

  it('yönetilebilir monitörde sorun satırında "Hariç tut" butonu; prompt onayı → yalnız excludePatterns ile PUT (ekleyerek)', async () => {
    const managed = { ...monitor, team_id: 5, exclude_patterns: '/ads/' }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [brokenIssue] })
    api.monitoring.updatePageMonitor.mockResolvedValue({ success: true, data: { ...managed,
      exclude_patterns: '/ads/\nhttps://voting.institutionalinvestor.com/welcome' } })

    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    const btn = await screen.findByRole('button', { name: /hariç tut$|^exclude$/i })
    fireEvent.click(btn)

    // Prompt açıldı, giriş değeri = kaynak URL'i (düzenlenebilir)
    const input = document.querySelector('.dlg-input')
    expect(input).not.toBeNull()
    expect(input.value).toBe('https://voting.institutionalinvestor.com/welcome')
    fireEvent.click(document.querySelector('.dlg-btn-confirm'))

    await waitFor(() => expect(api.monitoring.updatePageMonitor).toHaveBeenCalledWith(1, {
      excludePatterns: '/ads/\nhttps://voting.institutionalinvestor.com/welcome',
    }))
  })

  it('alarm kapsamı kolonu: alert_timeout kapalı monitörde TIMEOUT satırı "Kapsam dışı"; 3P açıkken ölü LINK "Alarmda"', async () => {
    const managed = { ...monitor, team_id: 5, alert_timeout: false, alert_third_party: true }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [
      { ...brokenIssue, id: 21, issue_type: 'TIMEOUT', resource_type: 'IFRAME',
        resource_url: 'https://www.googletagmanager.com/ns.html', duration_ms: 10007 },
      { ...brokenIssue, id: 22 },   // ölü 3P LINK (BROKEN, http_status null)
    ] })
    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())

    const out = await screen.findAllByText(/kapsam dışı|out of scope/i)
    expect(out.length).toBe(1)   // yalnız TIMEOUT satırı (alert_timeout kapalı)
    const inScope = screen.getAllByText(/^alarmda$|^in scope$/i)
    expect(inScope.length).toBe(1)   // ölü 3P LINK — yeni kural + 3P açık → alarmda
  })

  it('tarama turu başlık bandı: iki farklı checked_at → iki run başlığı', async () => {
    const managed = { ...monitor, team_id: 5 }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [
      { ...brokenIssue, id: 31, checked_at: '2026-08-03T20:23:57' },
      { ...brokenIssue, id: 32, checked_at: '2026-08-03T20:23:57' },
      { ...brokenIssue, id: 33, checked_at: '2026-08-03T20:17:35' },
    ] })
    render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    await screen.findAllByText(/googletagmanager|voting\.institutionalinvestor/)

    const hdrs = document.querySelectorAll('.page-run-hdr')
    expect(hdrs.length).toBe(2)
    expect(hdrs[0].textContent).toMatch(/2 sorun|2 issues/)
    expect(hdrs[1].textContent).toMatch(/1 sorun|1 issues/)
  })

  it('mevcut hariç desenle eşleşen satırda buton disabled; yönetilemeyen kullanıcıda hiç yok', async () => {
    // Desen '/welcome' → kaynak URL'i contains ile eşleşir → buton disabled
    const managed = { ...monitor, team_id: 5, exclude_patterns: '/welcome' }
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [managed] })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [brokenIssue] })

    const { unmount } = render(<PageMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    const already = await screen.findByRole('button', { name: /zaten hariç|already matches/i })
    expect(already).toBeDisabled()
    unmount()

    // Yönetilemeyen: USER + farklı takım → aksiyon butonu render edilmez
    vi.clearAllMocks()
    api.monitoring.getPageMonitors.mockResolvedValue({ success: true, data: [{ ...managed, team_id: 9 }] })
    api.monitoring.getPageHistory.mockResolvedValue({ success: true, data: { checks: [], total: 0, down: 0 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [brokenIssue] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { page: {} } })
    api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [] })
    render(<PageMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(api.monitoring.getPageMonitors).toHaveBeenCalled())
    fireEvent.click(screen.getByText('https://www.akbank.com/'))
    await waitFor(() => expect(api.monitoring.getPageIssues).toHaveBeenCalled())
    await screen.findByText(/voting\.institutionalinvestor\.com/)
    expect(screen.queryByRole('button', { name: /hariç tut$|^exclude$|zaten hariç|already matches/i })).toBeNull()
  })
})
