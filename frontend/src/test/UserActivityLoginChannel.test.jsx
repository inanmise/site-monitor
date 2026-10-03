import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import UserDirectoryDetail from '../components/admin/useractivity/UserDirectoryDetail.jsx'
import { SessionDetailModal } from '../components/admin/useractivity/UactModals.jsx'

const { apiMock, navMock } = vi.hoisted(() => ({
  apiMock: { admin: { getUserTimeline: vi.fn(), resetUserTour: vi.fn() } },
  navMock: vi.fn(),
}))
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => (s ?? '').slice(0, 10) }))
vi.mock('../utils/navigate.js', () => ({ navigateTo: navMock, default: navMock }))

/**
 * Sistem Sağlığı → Kullanıcı/Oturum giriş geçmişi (2026-10-03): son giriş yöntemi KANAL rozetiyle (PASSWORD → hesap
 * kaynağına göre LDAP / yerel), zaman çizelgesinde satır başına kanal + 30 günlük kanal özeti çipleri; GLOBAL yöneticiye
 * "Giriş istatistikleri" bağlantısı (Ayarlar → Giriş Yöntemleri → İstatistikler + kişinin ayrıntısı). Görüntüleme yetkisi
 * DEĞİŞMEDİ (bağlantı yalnız global yöneticide). Yer tutucu adlar.
 */
const ROW = {
  username: 'USER-B', display_name: 'Kullanici B', auth_source: 'LDAP', last_login_method: 'PASSWORD',
  last_login_at: '2026-10-03T08:00:00', online: false, active: true, system_role: 'USER',
}

describe('Kullanıcı Dizini ayrıntısı — giriş kanalı', () => {
  beforeEach(() => vi.clearAllMocks())

  it('son giriş yöntemi kanal rozeti (PASSWORD + LDAP hesabı → LDAP); global yöneticide istatistik bağlantısı', () => {
    render(<UserDirectoryDetail open row={ROW} ctx={{ globalAdmin: true, isAdmin: true, username: 'ADMIN' }}
      onClose={() => {}} onCopy={() => {}} onHistory={() => {}} />)
    const sheet = document.querySelector('[data-slot="udir-detail"]')
    expect(sheet.querySelector('[data-slot="login-channel"]')).toHaveAttribute('data-channel', 'LDAP')
    fireEvent.click(sheet.querySelector('[data-slot="udir-login-stats"]'))
    expect(navMock).toHaveBeenCalledWith('settings', { sec: 'loginmethods', lm_tab: 'stats', lm_user: 'USER-B' })
  })

  it('kapsamlı müdür / denetçi: bağlantı YOK; kod yöntemi aynen kanal (OTP_EMAIL)', () => {
    render(<UserDirectoryDetail open row={{ ...ROW, last_login_method: 'OTP_EMAIL' }} ctx={{ globalAdmin: false, isAdmin: true }}
      onClose={() => {}} onCopy={() => {}} onHistory={() => {}} />)
    const sheet = document.querySelector('[data-slot="udir-detail"]')
    expect(sheet.querySelector('[data-slot="login-channel"]')).toHaveAttribute('data-channel', 'OTP_EMAIL')
    expect(sheet.querySelector('[data-slot="udir-login-stats"]')).toBeNull()
  })
})

describe('Oturum / kullanıcı detayı — zaman çizelgesi kanalları', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.admin.getUserTimeline.mockResolvedValue({ success: true, data: {
      logins: 3, failed: 1, distinct_ips: 1, identity_masked: false,
      channels: { LDAP: { success: 2, failed: 1 }, REMEMBER_ME: { success: 1, failed: 0 }, OTP_PUSH: { success: 0, failed: 0 } },
      events: [
        { id: 3, time: '2026-10-03T08:00:00', outcome: 'SUCCESS', channel: 'REMEMBER_ME', channel_estimated: false, ip: '192.0.2.5' },
        { id: 2, time: '2026-10-02T08:00:00', outcome: 'FAILURE', reason: 'BAD_PASSWORD: attempt #1/5', channel: 'LDAP', channel_estimated: false, ip: '192.0.2.5' },
        { id: 1, time: '2026-09-20T08:00:00', outcome: 'SUCCESS', channel: 'LDAP', channel_estimated: true, ip: '192.0.2.5' },
      ],
    } })
  })

  it('satır başına kanal rozeti (tahmini işaretli) + kanal özeti çipleri (boş kanal yok); global yöneticide bağlantı', async () => {
    const onClose = vi.fn()
    render(<SessionDetailModal row={ROW} isAdmin globalAdmin activeSet={new Set()} onClose={onClose} />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelectorAll('[data-tl] [data-slot="login-channel"]')).toHaveLength(3))
    const badges = [...dlg.querySelectorAll('[data-tl] [data-slot="login-channel"]')]
    expect(badges.map((b) => b.getAttribute('data-channel'))).toEqual(['REMEMBER_ME', 'LDAP', 'LDAP'])
    expect(badges[2]).toHaveAttribute('data-estimated', 'true')
    const chips = dlg.querySelector('[data-slot="uact-channel-chips"]')
    expect([...chips.querySelectorAll('[data-channel]')].map((c) => c.getAttribute('data-channel'))).toEqual(['LDAP', 'REMEMBER_ME'])
    expect(chips).toHaveTextContent(/LDAP: 2/)
    // son giriş yöntemi alanı da kanal rozeti
    expect(within(dlg).getAllByText('LDAP').length).toBeGreaterThan(0)
    fireEvent.click(dlg.querySelector('[data-slot="uact-login-stats"]'))
    expect(onClose).toHaveBeenCalled()
    expect(navMock).toHaveBeenCalledWith('settings', { sec: 'loginmethods', lm_tab: 'stats', lm_user: 'USER-B' })
  })

  it('global olmayan yönetici: istatistik bağlantısı yok; eski sunucu (kanal alanı yok) → rozet / çip çizilmez', async () => {
    apiMock.admin.getUserTimeline.mockResolvedValueOnce({ success: true, data: { logins: 1, failed: 0, distinct_ips: 1,
      events: [{ id: 1, time: '2026-10-03T08:00:00', outcome: 'SUCCESS', ip: '192.0.2.5' }] } })
    render(<SessionDetailModal row={{ ...ROW, last_login_method: null }} isAdmin globalAdmin={false} activeSet={new Set()} onClose={() => {}} />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelectorAll('[data-tl]')).toHaveLength(1))
    expect(dlg.querySelector('[data-tl] [data-slot="login-channel"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="uact-channel-chips"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="uact-login-stats"]')).toBeNull()
  })
})
