import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import AuditLogViewer from '../components/admin/AuditLogViewer.jsx'

// Denetim konsolu — api mock'lu. Preset/bütünlük/diff dilden bağımsız (regex TR|EN) doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAuditLogs: vi.fn(),
      getAuditStats: vi.fn(),
      getAuditIntegrity: vi.fn(),
      getAuditResourceHistory: vi.fn(),
      auditExportUrl: vi.fn(() => 'http://x/export'),
    },
  }),
}))
import { api } from '../api/client'

const row = (over) => ({
  id: 1, event_time: '2026-07-28T10:00:00', event_type: 'USER_UPDATE', actor: 'alice',
  actor_role: 'ADMIN', ip_address: '1.2.3.4', resource_type: 'USER', resource_id: '5',
  outcome: 'SUCCESS', changes: '{"systemRole":{"from":"USER","to":"ADMIN"}}', ...over,
})

describe('AuditLogViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.admin.getAuditStats.mockResolvedValue({ success: true, data: { total_24h: 5 } })
    api.admin.getAuditLogs.mockResolvedValue({ success: true, data: [row()], total: 1, page: 0 })
    api.admin.getAuditIntegrity.mockResolvedValue({ success: true, data: { ok: true, checked: 42 } })
    api.admin.getAuditResourceHistory.mockResolvedValue({ success: true, data: [row(), row({ id: 2, event_type: 'MONITOR_UPDATE' })] })
  })

  it('denetim satırını gösterir + before/after diff genişletilebilir', async () => {
    render(<AuditLogViewer />)
    expect(await screen.findByText('USER_UPDATE')).toBeInTheDocument()
    // Değişiklik sayısı toggle'ına tıkla → diff tablosu from/to gösterir
    const { container } = render(<AuditLogViewer />)
    await screen.findAllByText('USER_UPDATE')
    const toggle = container.querySelector('.audit-detail-toggle')
    if (toggle) {
      fireEvent.click(toggle)
      expect(container.querySelector('.audit-diff-to')).not.toBeNull()
    }
  })

  it('preset (Güvenlik olayları) → getAuditLogs BLOCKED filtresiyle çağrılır', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText(/Güvenlik olaylar|Security events/))
    await waitFor(() => {
      const last = api.admin.getAuditLogs.mock.calls.at(-1)[0]
      expect(last.outcome).toBe('BLOCKED')
    })
  })

  it('bütünlüğü doğrula → sağlam zincir rozeti', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText(/Bütünlüğü doğrula|Verify integrity/))
    await waitFor(() => expect(api.admin.getAuditIntegrity).toHaveBeenCalled())
    expect(await screen.findByText(/Zincir sağlam|Chain intact/)).toBeInTheDocument()
  })

  it('CSV dışa aktarma → export URL açılır', async () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText('CSV'))
    expect(api.admin.auditExportUrl).toHaveBeenCalledWith('csv', expect.any(Object))
    expect(openSpy).toHaveBeenCalled()
    openSpy.mockRestore()
  })

  it('preset filtresi URL query paramına yansır (derin-link)', async () => {
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    fireEvent.click(screen.getByText(/Güvenlik olaylar|Security events/))
    await waitFor(() => expect(window.location.search).toContain('a_outcome=BLOCKED'))
  })

  it('görünüm kaydet → chip belirir + tıklayınca filtre uygulanır (localStorage)', async () => {
    const { container } = render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    const nameInput = screen.getByPlaceholderText(/Görünüm adı|View name/)
    fireEvent.change(nameInput, { target: { value: 'Benim görünümüm' } })
    fireEvent.click(screen.getByText(/^Görünümü kaydet$|^Save view$/))
    expect(await screen.findByText('Benim görünümüm')).toBeInTheDocument()
    // localStorage'a yazıldı
    expect(JSON.parse(localStorage.getItem('auditSavedViews'))[0].name).toBe('Benim görünümüm')
    // chip'e tıkla → yeniden yükleme tetiklenir
    const before = api.admin.getAuditLogs.mock.calls.length
    fireEvent.click(container.querySelector('.audit-view-name'))
    await waitFor(() => expect(api.admin.getAuditLogs.mock.calls.length).toBeGreaterThan(before))
  })

  it('kaynak zaman-çizelgesi düğmesi → drawer kaynak geçmişini yükler', async () => {
    const { container } = render(<AuditLogViewer />)
    await screen.findByText('USER_UPDATE')
    const tlBtn = container.querySelector('.audit-timeline-btn')
    expect(tlBtn).not.toBeNull()
    fireEvent.click(tlBtn)
    await waitFor(() => expect(api.admin.getAuditResourceHistory).toHaveBeenCalledWith('USER', '5', 100))
    expect(container.querySelector('.audit-timeline-drawer')).not.toBeNull()
    expect(await screen.findByText('MONITOR_UPDATE')).toBeInTheDocument()
  })
})
