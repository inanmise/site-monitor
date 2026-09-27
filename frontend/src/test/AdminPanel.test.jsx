import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, act, within, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import AdminPanel from '../components/admin/AdminPanel.jsx'
import { api } from '../api/client'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }),
    },
  }),
}))

vi.mock('../components/admin/EscalationContacts.jsx', () => ({
  default: (p) => (
    <div data-testid="escalation-contacts">EscalationContacts
      {p.onOpenSimulator && <button type="button" onClick={() => p.onOpenSimulator({ g_team: '5' })}>open-simulator</button>}
    </div>
  ),
}))
vi.mock('../components/admin/RecipientSimulator.jsx', () => ({
  default: (p) => (
    <div data-testid="who-notified" data-default-team={String(p.defaultTeamId)} data-admin={String(p.isAdmin)}>
      <button type="button" onClick={() => p.onNavigate('contacts', { g_team: '5' })}>go-contacts</button>
    </div>
  ),
}))
vi.mock('../components/admin/AlertThresholds.jsx', () => ({
  default: () => <div data-testid="alert-thresholds">AlertThresholds</div>,
}))
vi.mock('../components/admin/TeamManager.jsx', () => ({
  default: () => <div data-testid="team-manager">TeamManager</div>,
}))
vi.mock('../components/admin/UserManager.jsx', () => ({
  default: () => <div data-testid="user-manager">UserManager</div>,
}))

/** Radix sekme tetiği `click` ile değil fare basışıyla (mousedown) seçilir. */
const pick = (name) => pressMenuTrigger(screen.getByRole('tab', { name }))

describe('AdminPanel', () => {
  it('non-admin sees contacts, teams, users (read-only) but not thresholds', async () => {
    await act(async () => { render(<AdminPanel />) })
    expect(screen.getByRole('tab', { name: 'Escalation Contacts' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Teams' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Users' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Thresholds' })).not.toBeInTheDocument()
    // Yalnız yöneticinin grubu (Sertifika Yönetimi) de hiç çizilmez.
    expect(screen.queryByText('Certificate Management')).not.toBeInTheDocument()
  })

  it('renders all 6 tabs under their group labels with admin role (shadcn Tabs)', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    const list = screen.getByRole('tablist')
    expect(list).toHaveAttribute('data-slot', 'tabs-list')
    expect(within(list).getAllByRole('tab').map((x) => x.textContent))
      .toEqual(['Thresholds', 'Escalation Contacts', 'Notification Groups', 'Who gets notified?', 'Teams', 'Users'])
    // Grup başlıkları görünür ama sekme listesinde sekme DEĞİL (aria-hidden).
    for (const g of ['Certificate Management', 'Notifications & Alerts', 'Organization']) {
      expect(within(list).getByText(g)).toHaveAttribute('aria-hidden', 'true')
    }
  })

  it('shows EscalationContacts by default for non-admin', async () => {
    await act(async () => { render(<AdminPanel />) })
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-thresholds')).not.toBeInTheDocument()
  })

  it('shows AlertThresholds by default for admin', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    expect(screen.getByTestId('alert-thresholds')).toBeInTheDocument()
    expect(screen.queryByTestId('escalation-contacts')).not.toBeInTheDocument()
  })

  it('contacts tab is selected by default for non-admin', async () => {
    await act(async () => { render(<AdminPanel />) })
    expect(screen.getByRole('tab', { name: 'Escalation Contacts' })).toHaveAttribute('aria-selected', 'true')
  })

  it('switches to EscalationContacts when contacts tab is clicked (admin)', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    pick('Escalation Contacts')
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-thresholds')).not.toBeInTheDocument()
    // İçerik, seçili sekmeye bağlı sekme paneli içinde
    const panel = screen.getByRole('tabpanel')
    expect(panel).toContainElement(screen.getByTestId('escalation-contacts'))
    expect(panel.getAttribute('aria-labelledby')).toBe(screen.getByRole('tab', { name: 'Escalation Contacts' }).id)
  })

  it('switches to AlertThresholds when thresholds tab is clicked (admin)', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    pick('Escalation Contacts')
    pick('Thresholds')
    expect(screen.getByTestId('alert-thresholds')).toBeInTheDocument()
  })

  it('active tab updates when switching tabs', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    pick('Escalation Contacts')
    expect(screen.getByRole('tab', { name: 'Escalation Contacts' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Thresholds' })).toHaveAttribute('aria-selected', 'false')
  })

  it('keyboard: Enter on a focused tab activates it', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    const users = screen.getByRole('tab', { name: 'Users' })
    users.focus()
    fireEvent.keyDown(users, { key: 'Enter' })
    expect(screen.getByTestId('user-manager')).toBeInTheDocument()
  })
})

/**
 * Tam genişlik gruplu şerit (2026-09-26, kullanıcı: "sayfanın yalnız bir kısmını kullanıyor"): tek tablist,
 * her grup etiket + hap kabı; telefonda ayrı seçim kutusu YOK — aynı sekmeler sarılarak tam genişlik kalır.
 */
describe('AdminPanel — tam genişlik gruplu şerit', () => {
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('tek tablist tam genişlik (w-full, sarmalı), her grup kendi kabında; adminOnly süzgeci korunur', async () => {
    await act(async () => { render(<AdminPanel />) })
    const list = screen.getByRole('tablist')
    expect(list.className).toMatch(/(^|\s)w-full(\s|$)/)
    expect(list.className).toMatch(/flex-wrap/)
    expect(list.className).not.toMatch(/w-max|overflow-x-auto/)
    const groups = list.querySelectorAll('[data-slot="admin-tab-group"]')
    expect([...groups].map((g) => g.getAttribute('data-group'))).toEqual(['admin.groupNotify', 'admin.groupOrg'])   // Sertifika grubu yöneticiye özel
    // Grup sekme sayısıyla orantılı büyür; sekmeler grubun İÇİNDE
    expect(groups[0].style.flexGrow).toBe('3')
    expect(within(groups[0]).getAllByRole('tab').map((x) => x.textContent)).toEqual(['Escalation Contacts', 'Notification Groups', 'Who gets notified?'])
    expect(screen.queryByRole('combobox')).toBeNull()
    // Panel, seçili sekmeye Radix ile bağlı
    expect(screen.getByRole('tabpanel').getAttribute('aria-labelledby')).toBe(screen.getByRole('tab', { name: 'Escalation Contacts' }).id)
  })

  it('klavye: ok tuşu GRUPLAR arasında da gezinir (tek tablist) ve seçim g_tab yazar', async () => {
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    const first = screen.getByRole('tab', { name: 'Thresholds' })
    first.focus()
    fireEvent.keyDown(first, { key: 'ArrowRight' })
    // Radix odağı setTimeout ile taşır (RovingFocusGroup focusFirst) → beklenir
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'Escalation Contacts' })))
    pick('Users')
    expect(screen.getByTestId('user-manager')).toBeInTheDocument()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('g_tab')).toBe('users'), { timeout: 2000 })
  })
})

/** Alt sekme + süzgeçler URL'de (g_*): derin bağlantı ve yenileme ilk sekmeye düşmez (2026-09-20). */
describe('AdminPanel — g_tab derin bağlantı', () => {
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('g_tab=users ile açılınca Kullanıcılar sekmesi aktif', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=users')
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    expect(screen.getByTestId('user-manager')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Users' })).toHaveAttribute('aria-selected', 'true')
  })

  it('yetkisiz sekme (TEAM_ADMIN + g_tab=thresholds) varsayılana iner', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=thresholds')
    await act(async () => { render(<AdminPanel systemRole="TEAM_ADMIN" />) })
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(screen.queryByTestId('alert-thresholds')).not.toBeInTheDocument()
  })

  it('g_tab=whoNotified derin bağlantısı (yönetici olmayan da) simülatörü açar', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=whoNotified&g_team=7&g_level=CRITICAL')
    await act(async () => { render(<AdminPanel systemRole="TEAM_ADMIN" />) })
    expect(screen.getByTestId('who-notified')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Who gets notified?' })).toHaveAttribute('aria-selected', 'true')
    // Simülatörün kendi g_* anahtarları sekme açılışında SİLİNMEZ (paylaşılan senaryo bağlantısı)
    expect(new URLSearchParams(window.location.search).get('g_team')).toBe('7')
    expect(new URLSearchParams(window.location.search).get('g_level')).toBe('CRITICAL')
  })

  it('sekme değişince önceki sekmenin g_* süzgeçleri URL parametrelerinden silinir, g_tab kalır', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=users&g_role=ADMIN&g_q=ali')
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    pick('Teams')
    const sp = new URLSearchParams(window.location.search)
    expect(sp.get('g_role')).toBeNull()
    expect(sp.get('g_q')).toBeNull()
    expect(sp.get('tab')).toBe('admin')
    expect(screen.getByTestId('team-manager')).toBeInTheDocument()
  })
})

/**
 * "Kim bilgilendirilir?" ayrı sekme (2026-09-27): Bildirim & Alarmlar grubunda kişiler + gruplardan SONRA
 * (kur → doğrula); kişiler sayfasındaki bağlantı ve simülatörün "kuruluma git" düğmeleri AdminPanel.jump ile
 * (window hilesi yok) sekme değiştirir ve takımı g_team olarak taşır.
 */
describe('AdminPanel — Who gets notified? tab', () => {
  afterEach(() => { window.history.replaceState(null, '', '/') })
  const sp = () => new URLSearchParams(window.location.search)

  it('non-admin sees it in the Notifications group; selecting it mounts the simulator', async () => {
    await act(async () => { render(<AdminPanel systemRole="USER" />) })
    const notify = screen.getByRole('tablist').querySelector('[data-group="admin.groupNotify"]')
    expect(within(notify).getByRole('tab', { name: 'Who gets notified?' })).toBeInTheDocument()
    pick('Who gets notified?')
    expect(screen.getByTestId('who-notified')).toHaveAttribute('data-admin', 'false')
    expect(screen.queryByTestId('escalation-contacts')).toBeNull()
    await waitFor(() => expect(sp().get('g_tab')).toBe('whoNotified'), { timeout: 2000 })
  })

  it('contacts page link switches to the tab and carries the team (g_team) via jump', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=contacts&g_q=ali')
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    fireEvent.click(screen.getByRole('button', { name: 'open-simulator' }))
    expect(screen.getByTestId('who-notified')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Who gets notified?' })).toHaveAttribute('aria-selected', 'true')
    expect(sp().get('g_team')).toBe('5')
    expect(sp().get('g_q')).toBeNull()   // kişiler sayfasının süzgeci taşınmaz
  })

  it('simulator next-step button jumps back to contacts with the team filter', async () => {
    window.history.replaceState(null, '', '/?tab=admin&g_tab=whoNotified')
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    fireEvent.click(screen.getByRole('button', { name: 'go-contacts' }))
    expect(screen.getByTestId('escalation-contacts')).toBeInTheDocument()
    expect(sp().get('g_team')).toBe('5')
  })

  it('defaultTeamId: a non-admin with exactly one team gets it preselected; an admin does not', async () => {
    api.admin.getTeams.mockResolvedValueOnce({ success: true, data: [{ id: 4, name: 'Takım A' }] })
    window.history.replaceState(null, '', '/?tab=admin&g_tab=whoNotified')
    let view
    await act(async () => { view = render(<AdminPanel systemRole="USER" />) })
    await waitFor(() => expect(screen.getByTestId('who-notified')).toHaveAttribute('data-default-team', '4'))
    view.unmount()
    api.admin.getTeams.mockResolvedValueOnce({ success: true, data: [{ id: 4, name: 'Takım A' }] })
    const calls = api.admin.getTeams.mock.calls.length
    await act(async () => { render(<AdminPanel systemRole="ADMIN" />) })
    await waitFor(() => expect(api.admin.getTeams.mock.calls.length).toBe(calls + 1))
    await act(async () => {})
    expect(screen.getByTestId('who-notified')).toHaveAttribute('data-default-team', '')
  })
})
