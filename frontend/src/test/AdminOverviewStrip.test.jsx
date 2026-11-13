import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AdminOverviewStrip, { GAP_REFRESH_MIN_MS } from '../components/admin/AdminOverviewStrip.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { overview: vi.fn(), teamStats: vi.fn(), getContacts: vi.fn(), getUsers: vi.fn() } }),
}))
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır)
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
import { api } from '../api/client'

/** Özet şeridi (2026-09-20): sayaçlar + uyarılar; uyarı çipi sekmeye SÜZGEÇLİ atlar. */
describe('AdminOverviewStrip', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.overview.mockResolvedValue({ success: true, data: {
      dormant_days: 90,
      counts: { teams: 3, teams_active: 2, users: 12, users_active: 10, admins: 1, contacts: 4, contacts_webhook: 1, groups: 2,
        threshold_tiers: 1, threshold_default: { warning: 30, high: 15, critical: 7 } },
      warnings: [{ code: 'USER_NEVER_LOGGED_IN', count: 3, tab: 'users' }, { code: 'SINGLE_ADMIN', count: 1, tab: 'users' }, { code: 'TEAM_NO_LEADER', count: 1, tab: 'teams' }],
    } })
  })

  it('sayaçlar ve uyarılar çizilir; uyarı tıklaması sekme + süzgeç verir', async () => {
    const jump = vi.fn()
    render(<AdminOverviewStrip isAdmin onJump={jump} />)
    expect(await screen.findByText('12')).toBeInTheDocument()
    expect(screen.getByText('30/15/7')).toBeInTheDocument()
    fireEvent.click(screen.getByText(/Tek aktif ADMIN|Only one active ADMIN/))
    expect(jump).toHaveBeenCalledWith('users', { g_role: 'ADMIN' })
    fireEvent.click(screen.getByText(/1 takımın lideri yok|1 teams have no leader/))
    expect(jump).toHaveBeenCalledWith('teams', {})
    // KPI tıklaması süzgeçsiz sekme
    fireEvent.click(screen.getByText('4').closest('button'))
    expect(jump).toHaveBeenCalledWith('contacts', {})
  })

  it('shadcn: sayaçlar ve uyarılar Button; ağır uyarı (tek ADMIN) danger, diğerleri warning tonunda', async () => {
    render(<AdminOverviewStrip isAdmin onJump={() => {}} />)
    await screen.findByText('12')
    const kpi = screen.getByText('12').closest('button')
    expect(kpi).toHaveAttribute('data-slot', 'button')
    expect(kpi).toHaveAttribute('data-kpi', 'users')
    const severe = screen.getByText(/Tek aktif ADMIN|Only one active ADMIN/).closest('button')
    expect(severe).toHaveAttribute('data-slot', 'button')
    expect(severe).toHaveAttribute('data-tone', 'danger')
    expect(screen.getByText(/1 takımın lideri yok|1 teams have no leader/).closest('button')).toHaveAttribute('data-tone', 'warning')
    // Legacy `.aov-*` sınıfları kalmadı (App.css katmansız — shadcn Button'u ezerdi)
    expect(document.querySelector('[class*="aov-"]')).toBeNull()
  })

  // 2026-11-12 (#2): kullanıcı etkinliği uyarıları (kilitli/atıl/hiç) TEK "Dikkat isteyen kullanıcılar" kutucuğunda
  // toplanır (ayrı çipler YOK — 2026-09-27 kararının ruhu) ve Kullanıcılar sekmesine süzgeçli gider.
  it('kullanıcı etkinliği uyarıları tek "Dikkat isteyen kullanıcılar" kutucuğunda toplanır', async () => {
    const jump = vi.fn()
    api.admin.overview.mockResolvedValue({ success: true, data: { counts: { teams: 1, users: 2, contacts: 0, groups: 0 },
      warnings: [{ code: 'USER_NEVER_LOGGED_IN', count: 1 }, { code: 'USER_DORMANT', count: 2 }, { code: 'USER_LOCKED', count: 1 }] } })
    const { container } = render(<AdminOverviewStrip isAdmin onJump={jump} />)
    const chip = await screen.findByText(/dikkat istiyor|need attention/i)
    expect(chip.closest('button')).toHaveAttribute('data-warning', 'USER_ATTENTION')
    // Tek kutucuk — ayrı USER_LOCKED/DORMANT/NEVER çipi yok (toplanmış)
    const codes = [...container.querySelectorAll('[data-warning]')].map(b => b.getAttribute('data-warning'))
    expect(codes).toEqual(['USER_ATTENTION'])
    // 2026-11-13 hata taraması: üç tür birden → süzgeçsiz sekme (sayı 4, süzgeç tek türü göstermez); kırılım kutucukta
    expect(chip.closest('button').querySelector('[data-slot="overview-health-parts"]').textContent)
      .toMatch(/1 (kilitli|locked).*1 (hiç giriş yapmamış|never signed in).*2 (atıl|dormant)/)
    fireEvent.click(chip)
    expect(jump).toHaveBeenCalledWith('users', {})
  })

  it('admin değilse eşik KPI\\u0027si yok; uyarı yoksa "temiz" satırı; hata → hiç çizilmez', async () => {
    api.admin.overview.mockResolvedValue({ success: true, data: { counts: { teams: 1, users: 2, contacts: 0, groups: 0, threshold_default: { warning: 30, high: 15, critical: 7 } }, warnings: [] } })
    // Kapsam boşluğu girdileri okunabilir (takım varlıksız → boşluk 0) — "temiz" yeşili ancak boşluk BİLİNİNCE
    api.admin.teamStats.mockResolvedValue({ success: true, data: { 1: { monitors: 0, domains: 0 } } })
    api.admin.getContacts.mockResolvedValue({ success: true, data: [] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: [] })
    render(<AdminOverviewStrip isAdmin={false} onJump={() => {}} />)
    expect(await screen.findByText(/Sağlık uyarısı yok|No health warnings/)).toBeInTheDocument()
    expect(screen.queryByText('30/15/7')).toBeNull()
    api.admin.overview.mockResolvedValue({ success: false })
    const { container } = render(<AdminOverviewStrip isAdmin onJump={() => {}} />)
    await new Promise(r => setTimeout(r, 20))
    expect(container.querySelector('[data-testid="admin-overview"]')).toBeNull()
  })
})

/** 2026-11-13 hata taraması: eskalasyon kapsam boşluğu bilinmezken yeşil "temiz" yok; sekme değişiminde tazelenir. */
describe('AdminOverviewStrip — kapsam boşluğu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = false
    api.admin.overview.mockResolvedValue({ success: true, data: { counts: { teams: 1, users: 2, contacts: 1, groups: 0 }, warnings: [] } })
    api.admin.teamStats.mockResolvedValue({ success: true, data: { 1: { monitors: 3, domains: 0 } } })
    api.admin.getContacts.mockResolvedValue({ success: true, data: [{ id: 1, team_id: 1, user_id: 9, min_alert_level: 'WARNING', active: true }] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: [{ id: 9, active: false }] })
  })
  afterEach(() => {
    for (const k of ['teamStats', 'getContacts', 'getUsers']) api.admin[k].mockReset()
  })

  it('kullanıcı listesi okunamazsa pasif kişi etkin sayılmaz: boşluk bilinmiyor → kutucuk yok, nötr metin (yeşil değil)', async () => {
    api.admin.getUsers.mockResolvedValue({ success: false })
    render(<AdminOverviewStrip isAdmin onJump={() => {}} />)
    const el = await screen.findByText(/eskalasyon kapsamı denetlenemedi|escalation coverage couldn.t be checked/)
    expect(el.closest('[data-slot="overview-health-unknown"]')).toHaveAttribute('data-state', 'unknown')
    expect(screen.queryByText(/^Sağlık uyarısı yok$|^No health warnings$/)).toBeNull()
    expect(document.querySelector('[data-warning="ESCALATION_GAP"]')).toBeNull()
  })

  it('pasif kişiye bağlı tek kişi → gerçek boşluk; sekme değişince (refreshKey) girdiler en az 60 sn arayla yeniden okunur', async () => {
    const { rerender } = render(<AdminOverviewStrip isAdmin onJump={() => {}} refreshKey="contacts" />)
    expect(await screen.findByText(/1 takımda kritik eskalasyon boşluğu|1 team\(s\) with a critical escalation gap/)).toBeInTheDocument()
    expect(api.admin.getContacts).toHaveBeenCalledTimes(1)
    // Kişi etkinleşti (aktif kullanıcıya bağlandı). 60 sn dolmadan sekme değişimi ağır uçları YENİDEN okumaz (son inceleme)
    api.admin.getUsers.mockResolvedValue({ success: true, data: [{ id: 9, active: true }] })
    const realNow = Date.now
    rerender(<AdminOverviewStrip isAdmin onJump={() => {}} refreshKey="teams" />)
    await new Promise((r) => setTimeout(r, 20))
    expect(api.admin.getContacts).toHaveBeenCalledTimes(1)
    // ... 60 sn sonra sekme değişimi boşluğu tazeler
    const t0 = realNow()
    const spy = vi.spyOn(Date, 'now').mockImplementation(() => t0 + GAP_REFRESH_MIN_MS + 1000)
    rerender(<AdminOverviewStrip isAdmin onJump={() => {}} refreshKey="contacts" />)
    await waitFor(() => expect(api.admin.getContacts).toHaveBeenCalledTimes(2))
    spy.mockRestore()
    await waitFor(() => expect(document.querySelector('[data-warning="ESCALATION_GAP"]')).toBeNull())
    expect(await screen.findByText(/^Sağlık uyarısı yok$|^No health warnings$/)).toBeInTheDocument()
  })
})

/** Telefon (2026-09-26, mweb): uyarılar ekranı yemesin — ağırlar önce, ilk 2 görünür, "+N" ile tümü açılır. */
describe('AdminOverviewStrip — telefon', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mobile.on = true
    api.admin.overview.mockResolvedValue({ success: true, data: {
      counts: { teams: 3, users: 12, contacts: 4, groups: 2 },
      warnings: [{ code: 'CONTACT_INACTIVE', count: 2 }, { code: 'TEAM_NO_LEADER', count: 1 }, { code: 'SINGLE_ADMIN', count: 1 }],
    } })
  })

  it('ağır uyarı öne alınır; yalnız 2 çip + "+1" düğmesi; basınca hepsi görünür ve tıklanabilir kalır', async () => {
    const jump = vi.fn()
    const { container } = render(<AdminOverviewStrip isAdmin onJump={jump} />)
    await screen.findByText('12')
    const chips = () => [...container.querySelectorAll('[data-warning]')].map((b) => b.getAttribute('data-warning'))
    // Ağır (SINGLE_ADMIN) önce; sonra model sırası (takım → grup → kişi): TEAM_NO_LEADER, CONTACT_INACTIVE
    expect(chips()).toEqual(['SINGLE_ADMIN', 'TEAM_NO_LEADER'])
    fireEvent.click(screen.getByRole('button', { name: /^\+1 (more|daha)$/ }))
    expect(chips()).toEqual(['SINGLE_ADMIN', 'TEAM_NO_LEADER', 'CONTACT_INACTIVE'])
    fireEvent.click(screen.getByText(/1 takımın lideri yok|1 teams have no leader/))
    expect(jump).toHaveBeenCalledWith('teams', {})
    mobile.on = false
  })
})
