import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils'

const sampleRow = {
  id: 5, refCode: 'LIR-2026-000005', username: 'N12345', messageSummary: 'Cannot login',
  ipAddress: '1.2.3.4', reportedAt: '2026-07-23T10:00:00', resolvedAt: '2026-07-23T12:00:00',
  status: 'RESOLVED', imageCount: 2,
}
const sampleDetail = {
  id: 5, refCode: 'LIR-2026-000005', username: 'N12345', errorText: 'HTTP 423',
  message: 'Cannot login at all', ipAddress: '1.2.3.4', userAgent: 'curl/8', status: 'OPEN',
  reportedAt: '2026-07-23T10:00:00', resolvedBy: null, resolvedAt: null, resolutionNote: null,
  imageCount: 2, images: ['data:image/png;base64,AAAA', 'data:image/png;base64,BBBB'],
  mailHistory: [
    { mailType: 'REPORT_ADMIN', from: 'noreply@certmonitor', to: 'admin@akbank.com', cc: null, subject: 'Konu R', body: "<p>rapor govdesi</p><img src='cid:shot0'>", status: 'SENT', error: null, forced: true, sentAt: '2026-07-23T10:00:05' },
    { mailType: 'REPORTER_ACK', from: 'noreply@certmonitor', to: 'user@akbank.com', cc: null, subject: 'Konu A', body: '<p>onay govdesi</p>', status: 'FAILED: 550', error: 'FAILED: 550 mailbox unavailable', forced: false, sentAt: '2026-07-23T10:00:06' },
  ],
}

vi.mock('../api/client', () => ({
  api: {
    admin: {
      getLoginIssues: vi.fn(async () => ({
        success: true, data: [sampleRow], total: 1, counts: { OPEN: 1, IN_PROGRESS: 0, RESOLVED: 0 },
      })),
      getLoginIssue: vi.fn(async () => ({ success: true, data: sampleDetail })),
      updateLoginIssueStatus: vi.fn(async () => ({
        success: true, data: { ...sampleDetail, status: 'RESOLVED', resolutionNote: 'done', resolvedBy: 'admin' },
      })),
    },
  },
}))

// Bileşen artık issues.login-reports/view iznine göre kendini gate'liyor (izinsiz → temiz mesaj, spinner değil).
// Test provider sarmıyor → canView'i hoisted bayrakla kontrol et (varsayılan true; noAccess testinde false).
const perm = vi.hoisted(() => ({ allow: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => perm.allow, canEdit: () => perm.allow, canExecute: () => perm.allow, perms: {}, refresh: () => {} }),
}))
import { api } from '../api/client'
import LoginIssueReports from '../components/admin/LoginIssueReports.jsx'

describe('LoginIssueReports', () => {
  beforeEach(() => { vi.clearAllMocks(); perm.allow = true })

  it('izin yoksa: temiz "yetkiniz yok" mesajı + getLoginIssues çağrılmaz (kalıcı spinner yok)', async () => {
    perm.allow = false
    render(<LoginIssueReports />)
    expect(await screen.findByText(/permission to view this page/i)).toBeInTheDocument()
    expect(api.admin.getLoginIssues).not.toHaveBeenCalled()
  })

  it('sayaçları ve tabloyu yükler', async () => {
    render(<LoginIssueReports />)
    expect(await screen.findByText('Issue Reports')).toBeInTheDocument()
    expect(screen.getByText('LIR-2026-000005')).toBeInTheDocument()
    expect(api.admin.getLoginIssues).toHaveBeenCalled()
  })

  it('satıra tıklayınca detay modalı açılır ve 2 resmi gösterir', async () => {
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await waitFor(() => expect(api.admin.getLoginIssue).toHaveBeenCalledWith(5))
    expect(await screen.findByText('Cannot login at all')).toBeInTheDocument()
    expect(document.querySelectorAll('.modal-box img').length).toBe(2)
  })

  it('çözüm notu olmadan Resolve → api çağrılmaz', async () => {
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await screen.findByText('Cannot login at all')
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    expect(api.admin.updateLoginIssueStatus).not.toHaveBeenCalled()
  })

  it('not ile Resolve → updateLoginIssueStatus doğru payload ile çağrılır', async () => {
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await screen.findByText('Cannot login at all')
    const ta = document.querySelector('.modal-box textarea')
    fireEvent.change(ta, { target: { value: 'reset the account' } })
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    await waitFor(() => expect(api.admin.updateLoginIssueStatus).toHaveBeenCalledWith(5, {
      status: 'RESOLVED', resolutionNote: 'reset the account',
    }))
  })

  it('İşleme Al → yazılan not payloadda gönderilir (kaybolmaz)', async () => {
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await screen.findByText('Cannot login at all')
    const ta = document.querySelector('.modal-box textarea')
    fireEvent.change(ta, { target: { value: 'kullanıcıyla iletişime geçildi' } })
    fireEvent.click(screen.getByRole('button', { name: 'Take In Progress' }))
    await waitFor(() => expect(api.admin.updateLoginIssueStatus).toHaveBeenCalledWith(5, {
      status: 'IN_PROGRESS', resolutionNote: 'kullanıcıyla iletişime geçildi',
    }))
  })

  it('detay açılınca mevcut not textarea\'ya önyüklenir', async () => {
    api.admin.getLoginIssue.mockResolvedValueOnce({
      success: true, data: { ...sampleDetail, status: 'IN_PROGRESS', resolutionNote: 'mevcut çalışma notu' },
    })
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await screen.findByText('Cannot login at all')
    const ta = document.querySelector('.modal-box textarea')
    expect(ta.value).toBe('mevcut çalışma notu')
  })

  it('mail geçmişi paneli: gönderilen mailleri tür + durumla listeler; zaman YEREL (Istanbul) gösterilir', async () => {
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await screen.findByText('Cannot login at all')
    expect(screen.getByText('Sent Emails')).toBeInTheDocument()
    expect(screen.getByText(/Admin notification/)).toBeInTheDocument()
    expect(screen.getByText(/Reporter acknowledgment/)).toBeInTheDocument()
    expect(screen.getByText('Sent')).toBeInTheDocument()          // SENT → rozet
    expect(screen.getByText('Failed')).toBeInTheDocument()        // FAILED → rozet
    expect(screen.getByText(/550 mailbox unavailable/)).toBeInTheDocument()  // hata satırı
    // 10:00 UTC → Europe/Istanbul 13:00 (yerel saate çevrildi; ham UTC değil)
    expect(screen.getAllByText('2026-07-23 13:00').length).toBeGreaterThan(0)
  })

  it('mail satırına tıklayınca gönderen + konu + gövde (iframe); cid görsel data-URL ile gösterilir', async () => {
    render(<LoginIssueReports />)
    fireEvent.click(await screen.findByText('LIR-2026-000005'))
    await screen.findByText('Cannot login at all')
    fireEvent.click(screen.getByText(/Admin notification/))
    expect(await screen.findByText('Konu R')).toBeInTheDocument()                 // Konu
    expect(screen.getAllByText('noreply@certmonitor').length).toBeGreaterThan(0)  // Gönderen
    const iframe = document.querySelector('iframe[title="mail-0"]')
    expect(iframe).not.toBeNull()
    const srcdoc = iframe.getAttribute('srcdoc')
    expect(srcdoc).toContain('rapor govdesi')                        // gövde
    expect(srcdoc).toContain('data:image/png;base64,AAAA')          // cid:shot0 → images[0] data-URL
    expect(srcdoc).not.toContain('cid:shot0')                       // kırık cid referansı kalmaz
  })

  it('ana tabloda "Çözülme Tarihi" kolonu — resolvedAt yerel saatle gösterilir', async () => {
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    // 12:00 UTC → Europe/Istanbul 15:00 (yeni "Çözülme Tarihi" kolonu; benzersiz değer)
    expect(screen.getByText('2026-07-23 15:00')).toBeInTheDocument()
  })

  it('arama: metin girince getLoginIssues q ile çağrılır', async () => {
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    const search = screen.getByPlaceholderText(/search error/i)
    fireEvent.change(search, { target: { value: 'locked' } })
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'locked' })))
  })

  it('sayfalama: total > size → sonraki sayfa getLoginIssues page:1 ile çağrılır', async () => {
    api.admin.getLoginIssues.mockResolvedValue({
      success: true, data: [sampleRow], total: 45, counts: { OPEN: 0, IN_PROGRESS: 0, RESOLVED: 45 },
    })
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    const next = await screen.findByRole('button', { name: /next/i })
    fireEvent.click(next)
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1 })))
  })
})
