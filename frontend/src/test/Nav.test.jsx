import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import Nav from '../components/Nav.jsx'
import MobileTopBar from '../components/nav/MobileTopBar.jsx'

// shadcn Sidebar (sidebar-07 "collapses to icons" deseni, 2026-09-26 yeniden tasarım): Nav SidebarProvider
// bağlamında çizilir (App.jsx sarar); bölümler Collapsible + SidebarMenuSub, ikon kipinde yana açılan
// DropdownMenu; kullanıcı menüsü shadcn DropdownMenu (Radix: tetik pointerdown ile açılır, öğeler menuitem).

const DEFAULT_PROPS = {
  activeTab: 'dashboard',
  onTabChange: vi.fn(),
  username: 'testuser',
  onLogout: vi.fn(),
}

const sectionTrigger = (container, key) => container.querySelector(`[data-nav-section-trigger="${key}"]`)
const tabButton = (container, id) => container.querySelector(`[data-tour="nav-tab-${id}"]`)
/** lucide-react ikonunun adı (`lucide-<ad>` sınıfı) — ikonun gerçekten çizildiğini ve hangisi olduğunu söyler. */
const iconName = (el) => {
  const svg = el?.querySelector('svg')
  if (!svg) return null
  return [...svg.classList].find((c) => c.startsWith('lucide-') && !c.endsWith('-icon')) || 'lucide'
}
function openUserMenu(container) {
  pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
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
  it('renders the main tabs of every section', () => {
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
   * DEFAULT_PROPS'ta systemRole/globalAdmin YOK -> siradan kullanici. Kapi geri kapatilirsa bu test kirilir.
   */
  it('shows Monitor Changes to a plain team user (no admin props)', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(screen.getByText('Monitor Changes')).toBeInTheDocument()
  })

  /**
   * Kullanici karari (2026-09-25): Denetim Logu da herkese acik — ekip uyeleri takim arkadaslarinin
   * kayitlarini TAM ayrintiyla gorur (kapsami uc belirler).
   */
  it('shows the Audit Log to a plain team user (team-scoped on the server)', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(screen.getByText('Audit Log')).toBeInTheDocument()
  })

  it('keeps the admin-only / permission-gated tabs hidden from a plain user', () => {
    const { container, unmount } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(tabButton(container, 'sqlplayground')).toBeNull()        // yalnız global admin
    // Sorun Bildirimleri HERKESE açık (2026-09-26): düz kullanıcı kendi bildirimlerini izler, yönetici tam listeyi görür
    expect(tabButton(container, 'login-issues')).not.toBeNull()
    expect(tabButton(container, 'weeklyreports')).toBeNull()        // takım modülü kapalı
    unmount()
    const r2 = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" globalAdmin weeklyReportsVisible />))
    expect(tabButton(r2.container, 'sqlplayground')).not.toBeNull()
    expect(tabButton(r2.container, 'weeklyreports')).not.toBeNull()
  })

  it('marks the active tab (data-active + aria-current="page") and only that one', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} activeTab="warnings" />))
    const warningsBtn = screen.getByRole('button', { name: /^Warnings$/ })
    expect(warningsBtn).toHaveAttribute('data-active', 'true')
    expect(warningsBtn).toHaveAttribute('aria-current', 'page')
    const dashboardBtn = screen.getByRole('button', { name: /^Dashboard$/ })
    expect(dashboardBtn).toHaveAttribute('data-active', 'false')
    expect(dashboardBtn).not.toHaveAttribute('aria-current')
    expect(container.querySelectorAll('[aria-current="page"]')).toHaveLength(1)
  })

  it('calls onTabChange with tab id when a tab is clicked', () => {
    const onTabChange = vi.fn()
    render(withSidebar(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />))
    fireEvent.click(screen.getByRole('button', { name: /^Dashboard$/ }))
    expect(onTabChange).toHaveBeenCalledWith('dashboard')
  })

  // 2026-09-25 kullanıcı isteği: sol üstteki marka (logo + ad) Pano'ya götürür.
  it('marka (logo ve ad) tıklanınca Pano açılır; adın tekrar eden Tab durağı yok', () => {
    const onTabChange = vi.fn()
    render(withSidebar(<Nav {...DEFAULT_PROPS} activeTab="warnings" onTabChange={onTabChange} />))
    fireEvent.click(screen.getByRole('button', { name: /go to the dashboard|Pano'ya git/ }))
    expect(onTabChange).toHaveBeenLastCalledWith('dashboard')
    onTabChange.mockClear()
    fireEvent.click(screen.getByText('SiteMonitor', { selector: 'span' }))
    expect(onTabChange).toHaveBeenLastCalledWith('dashboard')
    expect(screen.getAllByRole('button', { name: /go to the dashboard|Pano'ya git/ })).toHaveLength(1)
    expect(screen.getByTestId('nav-brand')).toHaveAccessibleName(/go to the dashboard/)
  })

  it('calls onTabChange with "admin" when Admin Panel is clicked (after opening its section)', () => {
    const onTabChange = vi.fn()
    render(withSidebar(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />))
    // Kapalı bölümün öğeleri erişilebilirlik ağacında YOK (hidden) — önce bölüm açılır
    expect(screen.queryByRole('button', { name: /Admin Panel/ })).toBeNull()
    const mgmt = screen.getByRole('button', { name: /^(Management|Yönetim)$/ })
    expect(mgmt).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(mgmt)
    expect(mgmt).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(screen.getByRole('button', { name: /Admin Panel/ }))
    expect(onTabChange).toHaveBeenCalledWith('admin')
  })

  it('displays the username', () => {
    render(withSidebar(<Nav {...DEFAULT_PROPS} username="alice" />))
    expect(screen.getByText(/alice/)).toBeInTheDocument()
  })

  it('the search box is a BUTTON (not a text field) that opens the command palette; Ctrl K is shown', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    const search = container.querySelector('[data-tour="nav-search"]')
    expect(search.tagName).toBe('BUTTON')
    expect(search).toHaveAccessibleName(/Quick search|Hızlı ara/)
    expect(within(search).getByText('Ctrl K')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="sidebar"] input')).toBeNull()
    const onPalette = vi.fn()
    window.addEventListener('sm:palette', onPalette)
    fireEvent.click(search)
    window.removeEventListener('sm:palette', onPalette)
    expect(onPalette).toHaveBeenCalledTimes(1)
    // Palet kenar çubuğunun DIŞINDA yaşar ve olayla açılır
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('Notifications is a normal menu button (no outline box) in the header', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    const bell = screen.getByRole('button', { name: /^(Notifications|Bildirimler)$/ })
    expect(bell).toHaveAttribute('data-slot', 'sidebar-menu-button')
    expect(container.querySelector('[data-tour="nav-inbox"]')).toContainElement(bell)
    expect(container.querySelector('[data-sidebar="header"]')).toContainElement(bell)
  })

  it('exposes ONE navigation landmark that holds the menu', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    const navs = screen.getAllByRole('navigation')
    expect(navs).toHaveLength(1)
    expect(navs[0]).toHaveAccessibleName(/Navigation menu|Gezinme menüsü/)
    expect(navs[0]).toBe(container.querySelector('[data-tour="nav-groups"]'))
  })

  /**
   * Ayarlar girdisi 2026-09-10'a kadar `username === 'admin'` sabit kapısıyla çiziliyordu. Kapı artık rol —
   * backend'in requireNotScopedAdmin kuralıyla aynı hizada. Kullanıcı adı 'admin' bile olsa rol yoksa girdi yok.
   */
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

// ── Menü yapısı + ikonlar (2026-09-26 yeniden tasarım) ──
describe('Nav — nav-main yapısı ve ikonlar (2026-09-26)', () => {
  const SECTIONS = ['certificates', 'monitoring', 'alerts', 'reports', 'logs', 'management']
  /** App.jsx VALID_TABS'ın kenar çubuğunda duranları ('settings' kullanıcı menüsünde). */
  const TABS = ['dashboard', 'all', 'domains', 'uptime', 'forecast', 'renewal', 'renewal-guide',
    'http', 'ping', 'port', 'dns', 'domain', 'keyword', 'page', 'pagespeed', 'scripted',
    'warnings', 'incidents', 'maintenance', 'alerthistory', 'storms', 'noc', 'stats', 'weakalgo', 'weeklyreports', 'incident-history',
    'activity', 'myactivity', 'system', 'monitorchanges', 'admin', 'health', 'permissions', 'sqlplayground', 'login-issues', 'help']

  function renderAll(props = {}) {
    // Tüm sekmeler görünsün: global admin + haftalık rapor modülü (login-issues izin sağlayıcısına bağlı → ayrıca)
    return render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" globalAdmin weeklyReportsVisible {...props} />))
  }

  it('her üst bölüm kendi ikonuyla bir menü düğmesidir; bölümler arasında ikon tekrarı yok', () => {
    const { container } = renderAll()
    const icons = SECTIONS.map((k) => {
      const trig = sectionTrigger(container, k)
      expect(trig, k).not.toBeNull()
      expect(trig.tagName).toBe('BUTTON')
      expect(trig.getAttribute('data-sidebar')).toBe('menu-button')   // data-slot'u Collapsible tetiği ezer (asChild)
      expect(trig).toHaveAttribute('aria-expanded')
      return iconName(trig)
    })
    icons.push(iconName(tabButton(container, 'dashboard')))
    expect(icons.every(Boolean)).toBe(true)
    expect(new Set(icons).size).toBe(icons.length)
  })

  it('kenar çubuğundaki her sekmenin bir ikonu var ve hiçbir iki sekme aynı ikonu paylaşmıyor', () => {
    const { container } = renderAll()
    const present = TABS.filter((id) => tabButton(container, id))
    // login-issues da herkese açık (2026-09-26) → HEPSİ çizilir
    expect(present).toEqual(TABS)
    const icons = present.map((id) => [id, iconName(tabButton(container, id))])
    const missing = icons.filter(([, n]) => !n).map(([id]) => id)
    expect(missing).toEqual([])
    const names = icons.map(([, n]) => n)
    const dupes = names.filter((n, i) => names.indexOf(n) !== i)
    expect(dupes).toEqual([])
  })

  it('bölüm ve sekme ikonları aynı düzende ikon tekrarı yapmaz (bölüm ikonu alt sekmede kullanılmaz)', () => {
    const { container } = renderAll()
    const sectionIcons = new Set(SECTIONS.map((k) => iconName(sectionTrigger(container, k))))
    const clash = TABS.filter((id) => tabButton(container, id) && sectionIcons.has(iconName(tabButton(container, id))))
    expect(clash).toEqual([])
  })

  it('etkin sekmenin bölümü kendiliğinden açılır, diğerleri kapalı; sekme değişince açık bölüm izler', () => {
    const { container, rerender } = renderAll({ activeTab: 'http' })
    expect(sectionTrigger(container, 'monitoring')).toHaveAttribute('aria-expanded', 'true')
    for (const k of SECTIONS.filter((x) => x !== 'monitoring')) expect(sectionTrigger(container, k)).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: /^HTTP \/ Website$/ })).toHaveAttribute('aria-current', 'page')

    rerender(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" globalAdmin weeklyReportsVisible activeTab="incidents" />))
    expect(sectionTrigger(container, 'alerts')).toHaveAttribute('aria-expanded', 'true')
    expect(sectionTrigger(container, 'monitoring')).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: /^Incidents$/ })).toHaveAttribute('aria-current', 'page')
  })

  it('kapalı bölümün sekmeleri DOM\'da kalır (ürün turu kancaları) ama erişilebilirlik ağacında yok', () => {
    const { container } = renderAll({ activeTab: 'dashboard' })
    const http = tabButton(container, 'http')
    expect(http).not.toBeNull()
    expect(http.closest('[hidden]')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /^HTTP \/ Website$/ })).toBeNull()
  })

  it('Yardım ana menüden ayrı, alttaki ikincil grupta', () => {
    const { container } = renderAll()
    const help = tabButton(container, 'help')
    const groups = [...container.querySelectorAll('[data-sidebar="group"]')]
    expect(groups.length).toBe(2)
    expect(groups[1]).toContainElement(help)
    expect(groups[0]).not.toContainElement(help)
    expect(within(groups[0]).getByText(/^Platform$/)).toBeInTheDocument()
  })

  /** Tur 2 (2026-09-26): panel bölümleri ayrışsın — başlık / ana grup / ikincil grup (Yardım) / alt bilgi. */
  it('başlık, ana grup, Yardım grubu ve alt bilgi ayrı bloklar; Yardım grubunun önünde ayraç var', () => {
    const { container } = renderAll()
    const header = container.querySelector('[data-sidebar="header"]')
    const nav = container.querySelector('[data-tour="nav-groups"]')
    const footer = container.querySelector('[data-sidebar="footer"]')
    expect(header).toContainElement(container.querySelector('[data-tour="nav-search"]'))
    expect(nav).not.toContainElement(container.querySelector('[data-tour="nav-search"]'))
    expect(footer).toContainElement(container.querySelector('[data-tour="nav-user"]'))
    const helpGroup = tabButton(container, 'help').closest('[data-sidebar="group"]')
    expect(helpGroup.querySelector('[data-sidebar="separator"]')).not.toBeNull()
    expect(within(nav).getByText(/^Platform$/)).toHaveAttribute('data-sidebar', 'group-label')
  })

  it('sub-menu tabs are buttons (as today), not links', () => {
    const { container } = renderAll({ activeTab: 'http' })
    const http = tabButton(container, 'http')
    expect(http.tagName).toBe('BUTTON')
    expect(http).toHaveAttribute('type', 'button')
    expect(http.getAttribute('data-slot')).toBe('sidebar-menu-sub-button')
    expect(container.querySelector('[data-slot="sidebar"] a[href]')).toBeNull()
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
    // Başlık tıklanabilir bir kontrol DEĞİL — klavye odağına girmez; alt menünün (SidebarMenuSub) içinde durur
    heads.forEach((h) => {
      expect(h.tagName).not.toBe('BUTTON'); expect(h.getAttribute('role')).toBe('presentation')
      expect(h.closest('[data-sidebar="menu-sub"]')).not.toBeNull()
    })
  })

  it('İzleme grubunun dokuz sekmesinin hiçbiri kaybolmaz', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    for (const id of ['http', 'ping', 'port', 'dns', 'domain', 'keyword', 'page', 'pagespeed', 'scripted']) {
      expect(tabButton(container, id)).toBeTruthy()
    }
  })
})

/**
 * Kullanıcı menüsü yeniden tasarımı (2026-09-27, kullanıcı isteği: profesyonel + okunaklı + mobil): başlık kartı,
 * Hesap / Yardım ve destek / Tercihler grupları, tema-dil alt menüde radyo, en altta yıkıcı Çıkış. Sorun Bildir artık
 * Yardım grubunda (2026-09-23'teki "Ayarlar'ın hemen altında" yerleşimi bu gruplamayla bilinçli olarak değişti).
 */
describe('Nav — kullanıcı menüsü (2026-09-27 yeniden tasarım)', () => {
  const PROFILE = { user_id: 7, display_name: 'Ayşe Yılmaz', first_name: 'Ayşe', last_name: 'Yılmaz', email: 'ayse.yilmaz@example.com' }
  const LOGIN = { prev_login_at: '2026-09-26T08:15:00', failed_before_login: 2 }
  const menu = () => screen.getAllByRole('menu')[0]          // ana menü (alt menü açıkken ikinci bir role=menu daha vardır)
  const groupsOf = (root) => [...root.querySelectorAll('[data-slot="user-menu-group"]')].map((g) => g.getAttribute('data-group'))
  afterEach(() => { try { localStorage.clear() } catch { /* yok */ } })

  it('başlık kartı: "Oturum açan", ad soyad, e-posta, rol + takım rozeti, son giriş; tetik tam adı ve erişilebilir adı taşır', () => {
    const { container } = render(withSidebar(
      <Nav {...DEFAULT_PROPS} username="ayilmaz" profile={PROFILE} systemRole="ADMIN" teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} loginInfo={LOGIN} />))
    const trigger = container.querySelector('[data-tour="nav-user"]')
    expect(trigger).toHaveAccessibleName(/^(User menu|Kullanıcı menüsü)$/)
    expect(trigger.getAttribute('data-slot')).toBe('user-menu-trigger')
    expect(within(trigger).getByText('Ayşe Yılmaz')).toBeInTheDocument()
    openUserMenu(container)
    const head = menu().querySelector('[data-slot="user-menu-header"]')
    expect(head).toHaveTextContent(/Signed in as|Oturum açan/)
    expect(within(head).getByText('Ayşe Yılmaz')).toBeInTheDocument()
    expect(within(head).getByText('ayse.yilmaz@example.com')).toBeInTheDocument()
    expect(head.querySelector('[data-slot="badge"][data-role="ADMIN"]')).toHaveTextContent(/^(Admin|Yönetici)$/)
    expect(head.querySelector('[data-slot="team-badge"]')).toHaveTextContent('Takım A')
    expect(head).toHaveTextContent(/Last sign-in|Son giriş/)
    expect(head).toHaveTextContent(/2 /)                         // başarısız deneme uyarısı (giriş güvenliği sinyali korunur)
    expect(head.textContent).not.toMatch(/telefon|phone|sicil/i)
  })

  it('profil yoksa kullanıcı adı ad olarak yazılır; ikinci satır yok', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} username="alice" />))
    openUserMenu(container)
    const head = menu().querySelector('[data-slot="user-menu-header"]')
    expect(within(head).getByText('alice')).toBeInTheDocument()
    expect(within(head).queryAllByText('alice')).toHaveLength(1)
  })

  it('gruplar sırayla Hesap → Yardım → Tercihler; Ayarlar yalnız ADMIN ve Hesap grubunda; Sorun Bildir ve kılavuz Yardım grubunda', () => {
    const r1 = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" />))
    openUserMenu(r1.container)
    expect(groupsOf(menu())).toEqual(['account', 'help', 'preferences'])
    const account = menu().querySelector('[data-group="account"]')
    expect(within(account).getByRole('menuitem', { name: /^(Settings|Ayarlar)$/ })).toBeInTheDocument()
    expect(within(account).getByRole('menuitem', { name: /Change Password|Şifremi Değiştir/ })).toBeInTheDocument()
    const help = menu().querySelector('[data-group="help"]')
    expect(within(help).getByRole('menuitem', { name: /Report a Problem|Sorun Bildir/ })).toBeInTheDocument()
    expect(within(help).getByRole('menuitem', { name: /User guide|Kullanım kılavuzu/ })).toBeInTheDocument()
    expect(within(help).getByRole('menuitem', { name: /Product tour|Ürün turu/ })).toBeInTheDocument()
    // Alt bilgide tema/dil/sorun bildir metni YOK — hepsi menüde
    expect(r1.container.querySelector('[data-sidebar="footer"]').textContent).not.toMatch(/Report a Problem|Sorun Bildir|Theme|Tema|Language|Dil\b/)
    r1.unmount()
    const r2 = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="TEAM_ADMIN" />))
    openUserMenu(r2.container)
    expect(screen.queryByRole('menuitem', { name: /^(Settings|Ayarlar)$/ })).toBeNull()
    expect(groupsOf(menu())).toEqual(['account', 'help', 'preferences'])
  })

  it('Kullanım kılavuzu Yardım sekmesine gider', () => {
    const onTabChange = vi.fn()
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />))
    openUserMenu(container)
    fireEvent.click(screen.getByRole('menuitem', { name: /User guide|Kullanım kılavuzu/ }))
    expect(onTabChange).toHaveBeenCalledWith('help')
  })

  it('Tema alt menüsü: etkin seçenek işaretli; Koyu seçilince tema anında değişir, menü açık kalır', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    openUserMenu(container)
    const sub = menu().querySelector('[data-slot="user-menu-theme"]')
    expect(sub).toHaveTextContent(/Theme|Tema/)
    expect(sub).toHaveTextContent(/Light|Açık/)                  // geçerli değer satırda
    fireEvent.keyDown(sub, { key: 'ArrowRight' })
    expect(screen.getByRole('menuitemradio', { name: /^(Light|Açık)$/ })).toHaveAttribute('aria-checked', 'true')
    const dark = screen.getByRole('menuitemradio', { name: /^(Dark|Koyu)$/ })
    expect(dark).toHaveAttribute('aria-checked', 'false')
    fireEvent.click(dark)
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(menu()).toBeInTheDocument()                                              // menü açık kaldı
    expect(menu().querySelector('[data-slot="user-menu-theme"]')).toHaveTextContent(/Dark|Koyu/)
  })

  it('Dil alt menüsü: etkin dil işaretli (English); Türkçe seçilince arayüz Türkçeye geçer', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    openUserMenu(container)
    const sub = menu().querySelector('[data-slot="user-menu-language"]')
    expect(sub).toHaveTextContent('English')
    fireEvent.keyDown(sub, { key: 'ArrowRight' })
    expect(screen.getByRole('menuitemradio', { name: 'English' })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Türkçe' }))
    expect(container.querySelector('[data-tour="nav-user"]')).toHaveAccessibleName('Kullanıcı menüsü')
  })

  it('kısayol ipucu yalnız gerçek kısayolu olan öğede (kenar çubuğu Ctrl B); seçilince kenar çubuğu daralır', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    openUserMenu(container)
    const hints = menu().querySelectorAll('[data-slot="dropdown-menu-shortcut"]')
    expect(hints).toHaveLength(1)
    const item = screen.getByRole('menuitem', { name: /Collapse sidebar|Kenar çubuğunu daralt/ })
    expect(item).toContainElement(hints[0])
    expect(hints[0]).toHaveTextContent('Ctrl B')
  })

  it('Çıkış en sonda ve yıkıcı; ayrı "Çıkış" satırı yok; seçilince onLogout', () => {
    const onLogout = vi.fn()
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} onLogout={onLogout} />))
    expect(screen.queryByRole('button', { name: /Log out|Logout|Çıkış Yap/ })).toBeNull()
    expect(container.querySelector('[data-sidebar="footer"]').textContent).not.toMatch(/Log out|Çıkış Yap/)
    openUserMenu(container)
    const items = screen.getAllByRole('menuitem')
    const last = items[items.length - 1]
    expect(last).toHaveAccessibleName(/^(Log out|Çıkış Yap)$/)
    expect(last).toHaveAttribute('data-variant', 'destructive')
    expect(last.getAttribute('data-slot')).toBe('user-menu-logout')
    fireEvent.click(last)
    expect(onLogout).toHaveBeenCalledTimes(1)
  })

  it('rol rozeti: ADMIN / TEAM_ADMIN / AUDIT rozetli, sıradan kullanıcı rozetsiz', () => {
    const badge = (c) => c.querySelector('[data-tour="nav-user"] [data-slot="badge"]')
    const r1 = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="ADMIN" />))
    expect(badge(r1.container)).toHaveTextContent(/^(Admin|Yönetici)$/)
    r1.unmount()
    const r2 = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="TEAM_ADMIN" />))
    expect(badge(r2.container)).toHaveTextContent(/^(Team admin|Takım yöneticisi)$/)
    r2.unmount()
    const r3 = render(withSidebar(<Nav {...DEFAULT_PROPS} systemRole="AUDIT" />))
    expect(badge(r3.container)).toHaveTextContent(/^(Auditor|Denetçi)$/)
    r3.unmount()
    const r4 = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    expect(badge(r4.container)).toBeNull()
  })
})

/**
 * İkon (daraltılmış) kipi (2026-09-26): alt listeler görünmez → her bölüm düğmesi yana açılan bir menüdür
 * (klavyeyle açılır, öğe seçince gezinir); her öğenin ipucu var; etkin sekmeyi içeren bölüm vurgulanır.
 * (R17, 2026-09-25: daraltılmışken görünmez-ama-odaklanabilir işlevsiz durak kalmasın — artık görünmez düğme YOK,
 * bölüm düğmesinin kendisi işlevli.)
 */
describe('Nav — ikon kipi: ipuçları ve yana açılan bölüm menüsü', () => {
  it('bölüm düğmesi menü açar (aria-haspopup=menu); öğe seçilince onTabChange', () => {
    const onTabChange = vi.fn()
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} onTabChange={onTabChange} />, { open: false }))
    const mon = sectionTrigger(container, 'monitoring')
    expect(mon).toHaveAttribute('aria-haspopup', 'menu')
    expect(mon).not.toHaveAttribute('tabindex', '-1')
    pressMenuTrigger(mon)
    const menu = screen.getByRole('menu')
    expect(within(menu).getByText(/^(Availability|Erişilebilirlik)$/)).toBeInTheDocument()
    expect(within(menu).getAllByRole('menuitem')).toHaveLength(10)   // 9 tür + İzleme Panosu (2026-09-30)
    fireEvent.click(within(menu).getByRole('menuitem', { name: /^Ping$/ }))
    expect(onTabChange).toHaveBeenCalledWith('ping')
  })

  it('bölüm menüsü klavyeyle açılır (Enter) ve etkin sekme işaretlidir', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} activeTab="dns" />, { open: false }))
    const mon = sectionTrigger(container, 'monitoring')
    expect(mon).toHaveAttribute('data-active', 'true')                // etkin sekmeyi içeren bölüm vurgulu
    expect(sectionTrigger(container, 'alerts')).toHaveAttribute('data-active', 'false')
    mon.focus()
    fireEvent.keyDown(mon, { key: 'Enter' })
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /^DNS$/ })).toHaveAttribute('aria-current', 'page')
  })

  it('her öğenin ipucu var: odaklanınca adı ipucu olarak görünür (Pano, bölümler, arama, kullanıcı)', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />, { open: false }))
    const cases = [
      [tabButton(container, 'dashboard'), /^Dashboard$/],
      [sectionTrigger(container, 'alerts'), /^Alerts$/],
      [container.querySelector('[data-tour="nav-search"]'), /^Quick search$/],
      [tabButton(container, 'help'), /^Help$/],
    ]
    for (const [el, rx] of cases) {
      act(() => { el.focus() })
      expect(screen.getByRole('tooltip')).toHaveTextContent(rx)
      act(() => { el.blur() })
    }
  })

  it('genişken ipuçları çizilmez (etiket zaten görünür)', () => {
    const { container } = render(withSidebar(<Nav {...DEFAULT_PROPS} />))
    act(() => { tabButton(container, 'dashboard').focus() })
    expect(screen.queryByRole('tooltip')).toBeNull()
  })
})

describe('Nav — telefon (Sheet) ve mobil üst çubuk', () => {
  const W = window.innerWidth
  afterEach(() => { window.innerWidth = W })

  function renderPhone(props = {}) {
    window.innerWidth = 390
    const onTabChange = props.onTabChange || vi.fn()
    const utils = render(withSidebar(
      <>
        <MobileTopBar onTabChange={onTabChange} username="u1" />
        <Nav {...DEFAULT_PROPS} onTabChange={onTabChange} {...props} />
      </>,
    ))
    return { ...utils, onTabChange }
  }

  it('üst çubuk: menü düğmesi + marka + bildirim; zil TEK örnek (çekmecede değil)', () => {
    const { onTabChange } = renderPhone()
    const bar = document.querySelector('[data-slot="mobile-top-bar"]')
    expect(within(bar).getByRole('button', { name: /^(Open menu|Menüyü aç)$/ })).toHaveAttribute('aria-expanded', 'false')
    expect(within(bar).getByRole('button', { name: /^(Notifications|Bildirimler)$/ })).toBeInTheDocument()
    fireEvent.click(within(bar).getByRole('button', { name: /go to the dashboard|Pano'ya git/ }))
    expect(onTabChange).toHaveBeenCalledWith('dashboard')
    fireEvent.click(within(bar).getByRole('button', { name: /^(Open menu|Menüyü aç)$/ }))
    const sheet = screen.getByRole('dialog')
    expect(within(sheet).getByRole('button', { name: /^Dashboard$/ })).toBeInTheDocument()
    // Çekmece (Radix modal) açıkken dışarısı aria-hidden → gizlileri de say: zil yine TEK
    expect(screen.getAllByRole('button', { name: /^(Notifications|Bildirimler)$/, hidden: true })).toHaveLength(1)
    expect(within(sheet).queryByRole('button', { name: /^(Notifications|Bildirimler)$/ })).toBeNull()
  })

  it('çekmecede bir sekmeye dokunmak gezinir ve çekmeceyi kapatır; bölüm aç/kapa kapatmaz', () => {
    const { onTabChange } = renderPhone()
    fireEvent.click(screen.getByRole('button', { name: /^(Open menu|Menüyü aç)$/ }))
    const sheet = screen.getByRole('dialog')
    fireEvent.click(within(sheet).getByRole('button', { name: /^(Alerts|Alarmlar)$/ }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()                  // bölüm açıldı, çekmece açık
    fireEvent.click(within(sheet).getByRole('button', { name: /^Incidents$/ }))
    expect(onTabChange).toHaveBeenCalledWith('incidents')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  /** 2026-09-27: telefonda kullanıcı menüsü açılır menü DEĞİL, başparmağa yakın ALT sayfa (Sheet). */
  it('kullanıcı menüsü telefonda ALT sayfa olarak açılır; Ayarlar seçilince sayfa VE çekmece kapanır, sekme değişir', () => {
    const PROFILE = { display_name: 'Ayşe Yılmaz', email: 'ayse.yilmaz@example.com' }
    const { onTabChange } = renderPhone({ systemRole: 'ADMIN', profile: PROFILE })
    fireEvent.click(screen.getByRole('button', { name: /^(Open menu|Menüyü aç)$/ }))
    const navSheet = screen.getByRole('dialog')
    const trigger = within(navSheet).getByRole('button', { name: /^(User menu|Kullanıcı menüsü)$/ })
    expect(trigger).toHaveAttribute('aria-haspopup', 'dialog')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(trigger)
    expect(screen.queryByRole('menu')).toBeNull()                                   // açılır menü yok
    const sheet = document.querySelector('[data-slot="user-menu-sheet"]')
    expect(sheet).not.toBeNull()
    expect(sheet).toHaveAttribute('role', 'dialog')
    expect(within(sheet).getByText('Ayşe Yılmaz')).toBeInTheDocument()
    expect(within(sheet).getByText('ayse.yilmaz@example.com')).toBeInTheDocument()
    expect([...sheet.querySelectorAll('[data-slot="user-menu-group"]')].map((g) => g.getAttribute('data-group'))).toEqual(['account', 'help', 'preferences'])
    // Tema / dil bölümlü denetim (etkin olan basılı), Çıkış en altta ve yıkıcı
    expect(within(sheet).getByRole('group', { name: /^(Theme|Tema)$/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /^(Light|Açık)$/ })).toHaveAttribute('aria-pressed', 'true')
    expect(within(sheet).getByRole('button', { name: 'English' })).toHaveAttribute('aria-pressed', 'true')
    const rows = within(sheet).getAllByRole('button')
    expect(rows[rows.length - 1]).toHaveAccessibleName(/^(Log out|Çıkış Yap)$/)
    expect(rows[rows.length - 1]).toHaveAttribute('data-variant', 'destructive')
    fireEvent.click(within(sheet).getByRole('button', { name: /^(Settings|Ayarlar)$/ }))
    expect(onTabChange).toHaveBeenCalledWith('settings')
    expect(document.querySelector('[data-slot="user-menu-sheet"]')).toBeNull()
    expect(screen.queryAllByRole('dialog')).toHaveLength(0)                          // çekmece de kapandı
  })

  it('telefonda Çıkış alt sayfadan: onLogout çağrılır, iki sayfa da kapanır; sıradan kullanıcıda Ayarlar yok', () => {
    const onLogout = vi.fn()
    renderPhone({ onLogout })
    fireEvent.click(screen.getByRole('button', { name: /^(Open menu|Menüyü aç)$/ }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: /^(User menu|Kullanıcı menüsü)$/ }))
    const sheet = document.querySelector('[data-slot="user-menu-sheet"]')
    expect(within(sheet).queryByRole('button', { name: /^(Settings|Ayarlar)$/ })).toBeNull()
    fireEvent.click(within(sheet).getByRole('button', { name: /^(Log out|Çıkış Yap)$/ }))
    expect(onLogout).toHaveBeenCalledTimes(1)
    expect(screen.queryAllByRole('dialog')).toHaveLength(0)
  })

  it('ürün turu: sm:nav-reveal "*" yalnız çekmeceyi açar; sm:nav-conceal çekmeceyi kapatır (zil/yardım adımı örtü altında kalmasın)', () => {
    localStorage.removeItem('nav-section-open')   // önceki testin açtığı bölüm kalıcı; bu test "hiç bölüm açılmadı"yı ölçer
    renderPhone()
    expect(screen.queryByRole('dialog')).toBeNull()
    act(() => { window.dispatchEvent(new CustomEvent('sm:nav-reveal', { detail: { tab: '*' } })) })
    const sheet = screen.getByRole('dialog')
    expect(within(sheet).getByRole('button', { name: /^Dashboard$/ })).toBeInTheDocument()
    // '*' hiçbir bölümü açmaz → Alarmlar bölümünün alt sekmeleri görünmez
    expect(within(sheet).queryByRole('button', { name: /^Incidents$/ })).toBeNull()
    act(() => { window.dispatchEvent(new CustomEvent('sm:nav-conceal')) })
    expect(screen.queryByRole('dialog')).toBeNull()
    // Zil üst çubukta, artık örtüsüz ve erişilebilir
    const bar = document.querySelector('[data-slot="mobile-top-bar"]')
    expect(within(bar).getByRole('button', { name: /^(Notifications|Bildirimler)$/ })).toBeVisible()
  })
})
