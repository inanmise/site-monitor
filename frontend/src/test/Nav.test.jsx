import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import Nav from '../components/Nav.jsx'

// shadcn Sidebar (feature/shadcn-ui): Nav artık SidebarProvider bağlamında çizilir (App.jsx sarar);
// kullanıcı menüsü shadcn DropdownMenu (Radix: tetik pointerdown ile açılır, öğeler menuitem).

const DEFAULT_PROPS = {
  activeTab: 'dashboard',
  onTabChange: vi.fn(),
  username: 'testuser',
  onLogout: vi.fn(),
}

describe('Nav — sürüm çipi (2026-09-11)', () => {
  it('sürüm bir DÜĞME olarak çizilir (popover tetikleyici)', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    const chip = container.querySelector('[data-slot="popover-trigger"]')
    expect(chip).not.toBeNull()
    expect(chip.tagName).toBe('BUTTON')
    expect(chip.getAttribute('aria-haspopup')).toBe('dialog')
    expect(chip.getAttribute('aria-expanded')).toBe('false')
  })
})

describe('Nav', () => {
  it('renders all 6 tabs', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Warnings')).toBeInTheDocument()
    expect(screen.getByText('All Certificates')).toBeInTheDocument()
    expect(screen.getByText('Renewal Advice')).toBeInTheDocument()
    expect(screen.getByText('Activity Log')).toBeInTheDocument()
    expect(screen.getByText('Admin Panel')).toBeInTheDocument()
  })

  /**
   * Kullanici istegi (2026-08-27): Izleme Degisiklikleri ekrani TUM takim kullanicilarina acildi.
   * Onceden `isGlobalAdmin || isAudit` kapisindaydi. Uc zaten viewTeamIds ile sinirliyor; menuyu
   * gizlemek kullaniciyi yalniz KENDI takiminin verisinden mahrum birakiyordu.
   *
   * DEFAULT_PROPS'ta systemRole/globalAdmin YOK -> siradan kullanici. Kapi geri kapatilirsa
   * bu test kirilir.
   */
  it('shows Monitor Changes to a plain team user (no admin props)', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(screen.getByText('Monitor Changes')).toBeInTheDocument()
  })

  /**
   * Kullanici karari (2026-09-25): Denetim Logu da herkese acik — ekip uyeleri takim arkadaslarinin
   * kayitlarini TAM ayrintiyla gorur (kapsami uc belirler). Eski "plain team user'dan gizli" iddiasi
   * bilincli olarak TERS cevrildi; kapi geri kapatilirsa bu test kirilir.
   */
  it('shows the Audit Log to a plain team user (team-scoped on the server)', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(screen.getByText('Audit Log')).toBeInTheDocument()
  })

  it('marks the active tab (shadcn SidebarMenuButton data-active)', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} activeTab="warnings" />))
    const warningsBtn = screen.getByRole('button', { name: /Warnings/ })
    expect(warningsBtn).toHaveAttribute('data-active', 'true')
    const dashboardBtn = screen.getByRole('button', { name: /Dashboard/ })
    expect(dashboardBtn).toHaveAttribute('data-active', 'false')
  })

  it('calls onTabChange with tab id when a tab is clicked', () => {
    const onTabChange = vi.fn()
    render(withSidebar(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />))
    fireEvent.click(screen.getByRole('button', { name: /Dashboard/ }))
    expect(onTabChange).toHaveBeenCalledWith('dashboard')
  })

  it('calls onTabChange with "admin" when Admin Panel is clicked (after opening its group)', () => {
    const onTabChange = vi.fn()
    render(withSidebar(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />))
    // Kapalı grubun öğeleri erişilebilirlik ağacında YOK (hidden) — önce grup açılır
    expect(screen.queryByRole('button', { name: /Admin Panel/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Management|Yönetim/i }))
    fireEvent.click(screen.getByRole('button', { name: /Admin Panel/ }))
    expect(onTabChange).toHaveBeenCalledWith('admin')
  })

  it('displays the username', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} username="alice" />))
    expect(screen.getByText(/alice/)).toBeInTheDocument()
  })

  it('calls onLogout when logout button is clicked', () => {
    const onLogout = vi.fn()
    render(withSidebar(<Nav {...DEFAULT_PROPS} onLogout={onLogout} />))
    fireEvent.click(screen.getByRole('button', { name: /Logout/ }))
    expect(onLogout).toHaveBeenCalledTimes(1)
  })

  /**
   * Ayarlar girdisi 2026-09-10'a kadar `username === 'admin'` sabit kapısıyla çiziliyordu:
   * bootstrap dışı global admin hesapları (AD'den gelen, farklı kullanıcı adlı) retention/SMTP/LDAP
   * ekranına hiç ulaşamıyordu (BUG_RAPORU_7 açık madde). Kapı artık global_admin bayrağı —
   * backend'in requireNotScopedAdmin kuralıyla aynı hizada. Kullanıcı adı 'admin' bile olsa
   * bayrak yoksa girdi yok (isim, yetki kanıtı DEĞİLDİR).
   */
  function openUserMenu(container) {
    pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
  }

  it('shows the Settings entry to a global admin whose username is NOT "admin"', () => {
    const onTabChange = vi.fn()
    const { container } = render(withSidebar(
      <Nav {...DEFAULT_PROPS} username="ops.lead" systemRole="ADMIN" globalAdmin onTabChange={onTabChange} />
    ))
    openUserMenu(container)
    fireEvent.click(screen.getByRole('menuitem', { name: /Settings/ }))
    expect(onTabChange).toHaveBeenCalledWith('settings')
  })

  it('shows the Settings entry to a scoped ADMIN (müdür) too — 2026-09-10 product decision', () => {
    // globalAdmin YOK, rol ADMIN: kapsamlı müdür. Sır yüzeyleri AdminSettings içinde kilitli
    // (AdminSettings.test), backend GLOBAL_ONLY/requireNotScopedAdmin uygular; giriş görünür.
    const onTabChange = vi.fn()
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} username="mudur" systemRole="ADMIN" onTabChange={onTabChange} />))
    openUserMenu(container)
    fireEvent.click(screen.getByRole('menuitem', { name: /Settings/ }))
    expect(onTabChange).toHaveBeenCalledWith('settings')
  })

  it('hides the Settings entry from non-ADMIN roles, even if named "admin"', () => {
    const { container, unmount } = render(withSidebar(<Nav {...DEFAULT_PROPS} username="admin" />))
    openUserMenu(container)
    expect(screen.getAllByRole('menuitem').length).toBeGreaterThan(0)   // menü gerçekten açık (vakum değil)
    expect(screen.queryByRole('menuitem', { name: /Settings/ })).toBeNull()
    unmount()

    const r2 = render(withSidebar(<Nav {...DEFAULT_PROPS} username="admin" systemRole="TEAM_ADMIN" />))
    openUserMenu(r2.container)
    expect(screen.getAllByRole('menuitem').length).toBeGreaterThan(0)
    expect(screen.queryByRole('menuitem', { name: /Settings/ })).toBeNull()
  })
})

// ── Çok takımlı kullanıcı kutusu (2026-09-18): tek takım yerine "N takım" + popup listesi ──
describe('Nav — çok takımlı kullanıcı', () => {
  const TEAMS = [{ id: 5, name: 'Takım A' }, { id: 9, name: 'Takım B' }]

  it('tek takım: takım adı aynen yazılır, "N takım" etiketi ve menü girişi YOK', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />))
    const trigger = container.querySelector('[data-tour="nav-user"]')
    expect(within(trigger).getByText('Takım A')).toBeInTheDocument()
    expect(within(trigger).queryByText(/\d+ (teams|takım)/)).toBeNull()
    pressMenuTrigger(trigger)
    expect(screen.queryByText(/My teams|Dahil olduğum takımlar/i)).toBeNull()
  })

  it('2+ takım: kutuda "2 teams" etiketi; menü girişi tüm takımları listeler ve birincili işaretler', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} teamName="Takım A" myTeams={TEAMS} />))
    const trigger = container.querySelector('[data-tour="nav-user"]')
    const label = within(trigger).getByText(/2 (teams|takım)/)
    expect(label.title).toBe('Takım A, Takım B')
    pressMenuTrigger(trigger)
    fireEvent.click(screen.getByRole('menuitem', { name: /My teams \(2\)|Dahil olduğum takımlar \(2\)/i }))
    const dialog = screen.getByRole('dialog')
    const items = within(dialog).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toContain('Takım A')
    expect(items[0].textContent).toMatch(/PRIMARY|BİRİNCİL/i)
    expect(items[1].textContent).toContain('Takım B')
    expect(items[1].textContent).not.toMatch(/PRIMARY|BİRİNCİL/i)
  })
})

describe('Nav — İzleme ara başlıkları (2026-09-23)', () => {
  it('üç ara başlık sırayla çizilir; her biri kendi ilk sekmesinin HEMEN önünde durur', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    const heads = [...container.querySelectorAll('[data-nav-section]')]
    expect(heads.map((h) => h.textContent)).toEqual(
      [expect.stringMatching(/Erişilebilirlik|Availability/), expect.stringMatching(/Alan Adı ve DNS|Domains and DNS/), expect.stringMatching(/İçerik ve Deneyim|Content and experience/)])
    expect(heads.map((h) => h.nextElementSibling.querySelector('[data-tour]').getAttribute('data-tour')))
      .toEqual(['nav-tab-http', 'nav-tab-dns', 'nav-tab-keyword'])
    // Başlık tıklanabilir bir kontrol DEĞİL — klavye odağına girmez
    heads.forEach((h) => { expect(h.tagName).not.toBe('BUTTON'); expect(h.getAttribute('role')).toBe('presentation') })
  })

  it('İzleme grubunun dokuz sekmesinin hiçbiri kaybolmaz', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    for (const id of ['http', 'ping', 'port', 'dns', 'domain', 'keyword', 'page', 'pagespeed', 'scripted']) {
      expect(container.querySelector(`[data-tour="nav-tab-${id}"]`)).toBeTruthy()
    }
  })
})

describe('Nav — kullanıcı menüsü (2026-09-23)', () => {
  it('tema, dil ve Sorun Bildir alt bilgide DEĞİL, kullanıcı menüsünde; Sorun Bildir Ayarlar\'ın hemen altında', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" />))
    const foot = container.querySelector('[data-sidebar="footer"]')
    expect(foot).not.toBeNull()
    expect(foot.textContent).not.toMatch(/Sorun Bildir|Report a Problem|Koyu Mod|Dark Mode|Açık Mod|Light Mode|Switch to English|Türkçeye Geç/)
    pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
    const labels = screen.getAllByRole('menuitem').map((b) => b.textContent.trim())
    const settingsAt = labels.findIndex((l) => /^(Ayarlar|Settings)$/.test(l))
    expect(settingsAt).toBeGreaterThanOrEqual(0)
    expect(labels[settingsAt + 1]).toMatch(/Sorun Bildir|Report a Problem/)
    expect(labels.some((l) => /Koyu Mod|Dark Mode|Açık Mod|Light Mode/.test(l))).toBe(true)
    expect(labels.some((l) => /Switch to English|Türkçeye Geç/.test(l))).toBe(true)
  })
})

/**
 * 2026-09-25 (R17): daraltılmış (ikon) kipte grup başlıkları görünmez (opacity-0) ve bir şey yapmaz
 * (onOpenChange yutulur) — ama odaklanabilir kalıyordu: klavye kullanıcısı Tab'la 7 görünmez, işlevsiz
 * durakta dolaşıyordu. Daraltılınca Tab sırasından ve erişilebilirlik ağacından çıkmalı; genişken
 * normal düğme olarak kalmalı (grup açma/kapama onlarla yapılıyor).
 */
describe('Nav — daraltılmış kenar çubuğu grup başlıkları (2026-09-25)', () => {
  const groupTriggers = (container) => [...container.querySelectorAll('[data-sidebar="group-label"]')]

  it('daraltılınca grup başlıkları Tab sırasından ve erişilebilirlik ağacından çıkar', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" />, { open: false }))
    const triggers = groupTriggers(container)
    expect(triggers.length).toBeGreaterThan(3)
    for (const b of triggers) {
      expect(b.tagName).toBe('BUTTON')
      expect(b).toHaveAttribute('tabindex', '-1')
      expect(b).toHaveAttribute('aria-hidden', 'true')
    }
    // Sekmelerin kendisi (ikonlar) erişilebilir kalır.
    expect(screen.getByRole('button', { name: /Dashboard/ })).toBeInTheDocument()
  })

  it('genişken grup başlıkları normal, odaklanabilir düğmedir', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" />))
    const triggers = groupTriggers(container)
    expect(triggers.length).toBeGreaterThan(3)
    for (const b of triggers) {
      expect(b).not.toHaveAttribute('tabindex', '-1')
      expect(b).not.toHaveAttribute('aria-hidden')
    }
  })
})
