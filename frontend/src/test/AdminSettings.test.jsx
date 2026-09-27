import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Telefon kipi: dikey sekme menüsü yerine NativeSelect (jsdom medya sorgusu görmez → kanca mock'lanır).
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

// 16 alt bölümün TAMAMI stub'lanıyor — aksi halde her panel mount'ta gerçek API'ye gider.
// (AdminPanel.test.jsx'teki desen.) Fabrikalar satır içi: vi.mock dosyanın en üstüne
// hoist edilir, dışarıdaki bir yardımcıya erişemez.
vi.mock('../components/admin/GeneralSettings', () => ({ default: () => <div data-testid="sec-general" /> }))
vi.mock('../components/admin/BrandingSettings', () => ({ default: () => <div data-testid="sec-branding" /> }))
vi.mock('../components/admin/MonitorGroups', () => ({ default: () => <div data-testid="sec-monitorgroups" /> }))
vi.mock('../components/admin/PlatformSettings.jsx', () => ({ default: () => <div data-testid="sec-platforms" /> }))
vi.mock('../components/admin/SmtpSettings', () => ({ default: () => <div data-testid="sec-smtp" /> }))
vi.mock('../components/admin/WeeklyAvailabilitySettings', () => ({ default: () => <div data-testid="sec-weeklyavail" /> }))
vi.mock('../components/admin/WeeklyReportAccessSettings', () => ({ default: () => <div data-testid="sec-weeklyreports" /> }))
vi.mock('../components/admin/CertInventoryReportSettings', () => ({ default: () => <div data-testid="sec-certinvreport" /> }))
vi.mock('../components/admin/StormSettings', () => ({ default: () => <div data-testid="sec-storm" /> }))
vi.mock('../components/admin/UserPushSettings', () => ({ default: () => <div data-testid="sec-userpush" /> }))
// 7/24 İzleme Ekibi (2026-09-27): readOnly prop'u stub'a yazılır — kapsamlı müdürde salt okunur geçtiği sınanır
vi.mock('../components/admin/NocSettings.jsx', () => ({ default: ({ readOnly }) => <div data-testid="sec-noc" data-readonly={String(!!readOnly)} /> }))
vi.mock('../components/admin/LoginAnomalySettings', () => ({ default: () => <div data-testid="sec-loginanomaly" /> }))
vi.mock('../components/admin/LdapSettings', () => ({ default: () => <div data-testid="sec-ldap" /> }))
vi.mock('../components/admin/DomainDiagnostics', () => ({ default: () => <div data-testid="sec-domaindiag" /> }))
vi.mock('../components/admin/RetentionSettings', () => ({ default: () => <div data-testid="sec-retention" /> }))
vi.mock('../components/admin/DatabaseInfo', () => ({ default: () => <div data-testid="sec-database" /> }))
vi.mock('../components/admin/SecretTools', () => ({ default: () => <div data-testid="sec-secrets" /> }))
// Emniyet kemeri: bir stub kaçarsa gerçek fetch yerine mock'a düşsün. Yapılandırma sağlığı ucu
// ELLE: gezintideki durum noktaları ve başlık çipleri bu veriden türer.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { getConfigHealth: vi.fn(() => Promise.resolve({ success: false })) } }),
  getRecentFailures: () => [],
  formatDate: (s) => String(s ?? ''),
}))

import { api } from '../api/client'
import AdminSettings from '../components/admin/AdminSettings.jsx'

function setUrl(search) {
  window.history.replaceState({}, '', search ? `/?${search}` : '/')
}

/** Tablet kipi (768–1023 px): kabuk medya sorgusunu doğrudan matchMedia'dan okur → burada mock'lanır. */
function withTablet(on) {
  const orig = window.matchMedia
  window.matchMedia = (q) => ({ ...orig(q), matches: on && q.includes('768px') && q.includes('1023px') })
  return () => { window.matchMedia = orig }
}

describe('AdminSettings — sekme semantiği, klavye ve derin bağlantı', () => {
  beforeEach(() => setUrl(''))

  it('ARIA sekme deseni: 17 tab (2026-09-22: + Platformlar; 2026-09-27: + 7/24 İzleme Ekibi), tekil aria-selected, panele bağlı', () => {
    render(<AdminSettings />)
    // shadcn Tabs (Radix) — dikey liste
    const list = screen.getByRole('tablist', { name: /^settings$/i })
    expect(list).toHaveAttribute('data-slot', 'tabs-list')
    expect(list).toHaveAttribute('aria-orientation', 'vertical')
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(17)
    expect(tabs.filter(t => t.getAttribute('aria-selected') === 'true')).toHaveLength(1)

    const panel = screen.getByRole('tabpanel')
    const selected = tabs.find(t => t.getAttribute('aria-selected') === 'true')
    expect(selected.getAttribute('aria-controls')).toBe(panel.id)
    expect(panel.getAttribute('aria-labelledby')).toBe(selected.id)
  })

  it('roving tabindex: grup TEK Tab durağı; girişte odak seçili sekmeye gider', () => {
    render(<AdminSettings />)
    const list = screen.getByRole('tablist')
    const tabs = screen.getAllByRole('tab')
    // Radix RovingFocusGroup: ilk girişte durak listenin kendisi (tabindex=0), sekmelerin hepsi -1;
    // odak bir sekmeye geçince durak o sekme olur. Her iki durumda da Tab sırasında TEK öğe var.
    const stops = [list, ...tabs].filter(el => el.getAttribute('tabindex') === '0')
    expect(stops).toHaveLength(1)
    expect(tabs.filter(t => t.getAttribute('tabindex') === '-1').length).toBeGreaterThanOrEqual(tabs.length - 1)
    // Klavyeyle gruba girilince odak seçili sekmeye (Genel) yönlenir
    act(() => { list.focus() })
    const selected = tabs.find(t => t.getAttribute('aria-selected') === 'true')
    expect(document.activeElement).toBe(selected)
    expect(tabs.filter(t => t.getAttribute('tabindex') === '0')).toEqual([selected])
  })

  it('ok tuşu ODAĞI taşır ama paneli DEĞİŞTİRMEZ (manuel aktivasyon)', async () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    tabs[0].focus()
    fireEvent.keyDown(tabs[0], { key: 'ArrowDown' })

    // Radix odağı bir sonraki tikte taşır
    await waitFor(() => expect(document.activeElement).toBe(tabs[1]))
    expect(tabs[0].getAttribute('aria-selected')).toBe('true')   // seçim yerinde
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()
    expect(screen.queryByTestId('sec-branding')).toBeNull()
  })

  it('Home/End odağı uçlara taşır, ArrowUp başta sona sarar', async () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    tabs[0].focus()
    fireEvent.keyDown(tabs[0], { key: 'End' })
    await waitFor(() => expect(document.activeElement).toBe(tabs.at(-1)))
    fireEvent.keyDown(tabs.at(-1), { key: 'Home' })
    await waitFor(() => expect(document.activeElement).toBe(tabs[0]))
    fireEvent.keyDown(tabs[0], { key: 'ArrowUp' })
    await waitFor(() => expect(document.activeElement).toBe(tabs.at(-1)))
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()   // seçim hâlâ yerinde
  })

  it('Enter/Space odaklı sekmeyi aktive eder', () => {
    render(<AdminSettings />)
    const tabs = screen.getAllByRole('tab')
    // İNDEKS DEĞİL etiket (2026-09-16): araya bölüm eklendikçe indeksli seçim sessizce başka sekmeyi tıklıyordu.
    const ldapTab = tabs.find((x) => /LDAP/.test(x.textContent))
    ldapTab.focus()
    fireEvent.keyDown(ldapTab, { key: 'Enter' })
    expect(screen.getByTestId('sec-ldap')).toBeInTheDocument()
    expect(ldapTab.getAttribute('aria-selected')).toBe('true')
    const storm = tabs.find((x) => /Storm/.test(x.textContent))
    fireEvent.keyDown(storm, { key: ' ' })
    expect(screen.getByTestId('sec-storm')).toBeInTheDocument()
  })

  it('fare basışı sekmeyi seçer (Radix: mousedown)', () => {
    render(<AdminSettings />)
    pressMenuTrigger(screen.getAllByRole('tab').find((x) => /Branding|Marka/.test(x.textContent)))
    expect(screen.getByTestId('sec-branding')).toBeInTheDocument()
  })

  it('?sec=ldap ile doğrudan LDAP bölümü açılır (derin bağlantı)', () => {
    setUrl('tab=settings&sec=ldap')
    render(<AdminSettings />)
    expect(screen.getByTestId('sec-ldap')).toBeInTheDocument()
    expect(screen.queryByTestId('sec-general')).toBeNull()
  })

  it('bilinmeyen ?sec= değeri varsayılana düşer (whitelist)', () => {
    setUrl('sec=zzz-yok')
    render(<AdminSettings />)
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()
  })

  it('bölüm değişince URL\'e ?sec= yazılır; varsayılana dönünce param SİLİNİR', async () => {
    render(<AdminSettings />)
    pressMenuTrigger(screen.getAllByRole('tab').find((x) => /Veri Saklama|Retention/.test(x.textContent)))
    await waitFor(() => expect(window.location.search).toContain('sec=retention'), { timeout: 2000 })

    pressMenuTrigger(screen.getAllByRole('tab')[0])    // general = varsayılan
    await waitFor(() => expect(window.location.search).not.toContain('sec='), { timeout: 2000 })
  })

  it('sekme geçişinde temizlenen paramlar arasında sec de var', async () => {
    const { PAGE_STATE_PARAMS } = await import('../hooks/useUrlQuerySync.js')
    // Aksi halde bayat bir ?sec=ldap başka sekmeye taşınırdı.
    expect(PAGE_STATE_PARAMS).toContain('sec')
  })
})

/**
 * Tam sayfa yeniden tasarım (2026-09-27): ui/PageHeader başlığı, GRUPLU yapışkan gezinti (Platform /
 * Bildirimler / Güvenlik ve erişim / Veri ve bakım), bölüm arama kutusu, yapılandırma sağlığından türeyen
 * durum noktaları; tablette yatay `line` şerit.
 */
describe('AdminSettings — tam sayfa kabuk: başlık, gruplu gezinti, arama, durum noktaları', () => {
  beforeEach(() => { setUrl(''); vi.clearAllMocks() })

  it('PageHeader: h2 başlık + açıklama + "17 bölüm" çipi; içerik paneli tam genişlik (w-full, tavan yok)', () => {
    render(<AdminSettings />)
    const header = document.querySelector('[data-slot="page-header"]')
    expect(header).not.toBeNull()
    expect(within(header).getByRole('heading', { level: 2, name: /^(Settings|Ayarlar)$/ })).toBeInTheDocument()
    expect(header.querySelector('[data-slot="page-description"]')).not.toBeNull()
    expect(within(header).getByText(/17 (sections|bölüm)/)).toBeInTheDocument()
    const panel = screen.getByRole('tabpanel')
    expect(panel.className).toMatch(/\bw-full\b/)
    expect(panel.className).not.toMatch(/max-w-\[/)
  })

  it('gezinti DÖRT gruba ayrılır (platform / notifications / security / data) ve her sekme ikon taşır', () => {
    render(<AdminSettings />)
    const groups = [...document.querySelectorAll('[data-slot="settings-nav-group"]')].map((g) => g.getAttribute('data-group'))
    expect(groups).toEqual(['platform', 'notifications', 'security', 'data'])
    // Gruplar tek tablist'in içinde: klavye gezintisi gruplar arasında kopmaz
    expect(screen.getAllByRole('tablist')).toHaveLength(1)
    const security = document.querySelector('[data-slot="settings-nav-group"][data-group="security"]')
    expect(within(security).getAllByRole('tab').map((t) => t.getAttribute('data-id'))).toEqual(['loginanomaly', 'ldap', 'secrets'])
    for (const tab of screen.getAllByRole('tab')) expect(tab.querySelector('svg')).not.toBeNull()
  })

  it('bölüm arama kutusu sekmeleri süzer, boş grubu gizler; eşleşme yoksa not; temizleyince hepsi döner', () => {
    render(<AdminSettings />)
    const search = screen.getByRole('searchbox', { name: /Search sections|Bölüm ara/ })
    fireEvent.change(search, { target: { value: 'ldap' } })
    expect(screen.getAllByRole('tab')).toHaveLength(1)
    expect(screen.getAllByRole('tab')[0]).toHaveAttribute('data-id', 'ldap')
    expect(document.querySelectorAll('[data-slot="settings-nav-group"]')).toHaveLength(1)
    // Seçim değişmedi (arama yalnız listeyi süzer)
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()

    fireEvent.change(search, { target: { value: 'zzz-yok' } })
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(screen.getByText(/No matching sections|Eşleşen bölüm yok/)).toBeInTheDocument()

    fireEvent.change(search, { target: { value: '' } })
    expect(screen.getAllByRole('tab')).toHaveLength(17)
  })

  it('yapılandırma sağlığı verisi → ilgili sekmede durum noktası (en kötü durum) + başlıkta sayaç çipi; ek istek yok', async () => {
    api.admin.getConfigHealth.mockResolvedValueOnce({ success: true, data: {
      overall: 'bad', bad: 1, warn: 1, ok: 3,
      checks: [
        { key: 'smtp', tab: 'smtp', status: 'bad', detail: 'tested_fail:2026-09-12T08:00:00' },
        { key: 'base_url', tab: 'general', status: 'warn', detail: 'localhost' },
        { key: 'admin_email', tab: 'general', status: 'ok', detail: 'ready' },
        { key: 'push', tab: 'userpush', status: 'ok', detail: 'ready' },
        { key: 'unowned', tab: 'inventory', status: 'warn', detail: '3' },   // ayar bölümü değil → nokta yok
      ],
    } })
    render(<AdminSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="settings-nav-status"][data-status="bad"]')).not.toBeNull())
    const smtp = screen.getByRole('tab', { name: /SMTP/ })
    expect(smtp.querySelector('[data-slot="settings-nav-status"]')).toHaveAttribute('data-status', 'bad')
    const general = screen.getAllByRole('tab')[0]
    expect(general.querySelector('[data-slot="settings-nav-status"]')).toHaveAttribute('data-status', 'warn')
    expect(screen.getByRole('tab', { name: /Webhook/ }).querySelector('[data-slot="settings-nav-status"]')).toBeNull()
    // Toplamda yalnız iki nokta (inventory sekmesi ayar bölümü değil)
    expect(document.querySelectorAll('[data-slot="settings-nav-status"]')).toHaveLength(2)
    // Başlık çipleri kartla AYNI veriden (tek istek)
    const meta = document.querySelector('[data-slot="page-meta"]')
    expect(within(meta).getByText(/1 (problem|sorun)/)).toBeInTheDocument()
    expect(within(meta).getByText(/1 (warning|uyarı)/)).toBeInTheDocument()
    expect(api.admin.getConfigHealth).toHaveBeenCalledTimes(1)
  })

  it('kapsamlı müdür (globalAdmin=false): sağlık kartı ve durum noktaları YOK (uç yalnız global admin)', () => {
    render(<AdminSettings globalAdmin={false} />)
    expect(document.querySelector('[data-slot="config-health"]')).toBeNull()
    expect(api.admin.getConfigHealth).not.toHaveBeenCalled()
  })

  it('tablet (768–1023 px): yatay `line` şerit — tek tablist, 17 sekme, kendi kaydırma kabında', () => {
    const restore = withTablet(true)
    try {
      render(<AdminSettings />)
      const list = screen.getByRole('tablist', { name: /^settings$/i })
      expect(list).toHaveAttribute('aria-orientation', 'horizontal')
      expect(list).toHaveAttribute('data-variant', 'line')
      expect(list.closest('[data-slot="settings-rail"]')).not.toBeNull()
      expect(screen.getAllByRole('tab')).toHaveLength(17)
      expect(screen.queryByRole('searchbox')).toBeNull()   // arama kutusu yalnız masaüstü listesinde
      pressMenuTrigger(screen.getByRole('tab', { name: /LDAP/ }))
      expect(screen.getByTestId('sec-ldap')).toBeInTheDocument()
    } finally { restore() }
  })
})

/**
 * 2026-09-10 ürün kararı: kapsamlı müdür (globalAdmin=false) Ayarlar'ı görür; sır taşıyan dört
 * bölüm (SMTP, LDAP, Veritabanı, Secret Decryptor) bileşen yerine "yalnız global yönetici" notu
 * çizer (backend requireNotScopedAdmin ile 403'ler — 403 dolu ekran yerine açık not).
 */
describe('AdminSettings — kapsamlı müdür kilitleri', () => {
  const clickTab = (re) => pressMenuTrigger(screen.getByRole('tab', { name: re }))

  it('müdür: SMTP/LDAP/Veritabanı/Secret bölümleri not gösterir, bileşeni çizmez', () => {
    render(<AdminSettings globalAdmin={false} />)
    for (const [re, tid] of [[/SMTP/, 'sec-smtp'], [/LDAP/, 'sec-ldap'], [/Database/, 'sec-database'], [/Secret/, 'sec-secrets']]) {
      clickTab(re)
      expect(screen.getByTestId('settings-global-only')).toBeInTheDocument()
      expect(screen.queryByTestId(tid), `${tid} müdüre çizildi`).toBeNull()
    }
  })

  it('müdür: operasyonel bölümler (Genel, Storm, Data Retention) normal çizilir', () => {
    render(<AdminSettings globalAdmin={false} />)
    expect(screen.getByTestId('sec-general')).toBeInTheDocument()
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
    clickTab(/Alert Storm/)
    expect(screen.getByTestId('sec-storm')).toBeInTheDocument()
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
  })

  it('7/24 İzleme Ekibi Bildirimler grubunda; müdüre KİLİTLİ değil ama salt okunur, global admine yazılabilir', () => {
    const r1 = render(<AdminSettings globalAdmin={false} />)
    const notif = document.querySelector('[data-slot="settings-nav-group"][data-group="notifications"]')
    expect(within(notif).getAllByRole('tab').map((x) => x.getAttribute('data-id'))).toContain('noc')
    clickTab(/24\/7 Monitoring Team|7\/24 İzleme Ekibi/)
    expect(screen.getByTestId('sec-noc')).toHaveAttribute('data-readonly', 'true')
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
    r1.unmount()
    render(<AdminSettings />)
    clickTab(/24\/7 Monitoring Team|7\/24 İzleme Ekibi/)
    expect(screen.getByTestId('sec-noc')).toHaveAttribute('data-readonly', 'false')
  })

  it('global admin (varsayılan prop): SMTP bileşeni çizilir, not yok', () => {
    render(<AdminSettings />)
    clickTab(/SMTP/)
    expect(screen.getByTestId('sec-smtp')).toBeInTheDocument()
    expect(screen.queryByTestId('settings-global-only')).toBeNull()
  })
})

/** Telefon (< md, 2026-09-26 mweb): 17 bölümlük dikey menü yerine tek, GRUPLU NativeSelect. */
describe('AdminSettings — telefon bölüm seçicisi', () => {
  beforeEach(() => { setUrl(''); mobile.on = true })
  afterEach(() => { mobile.on = false })

  it('sekme listesi yok; 17 seçenekli, 4 optgroup\'lu NativeSelect bölümü değiştirir, panel ona bağlı', () => {
    render(<AdminSettings />)
    expect(screen.queryByRole('tablist')).toBeNull()
    const select = screen.getByRole('combobox', { name: /^settings$/i })
    expect(select).toHaveAttribute('data-slot', 'native-select')
    expect(select.options).toHaveLength(17)
    expect(select.querySelectorAll('optgroup')).toHaveLength(4)
    expect([...select.querySelectorAll('optgroup')].map((g) => g.label)).toEqual(
      expect.arrayContaining([expect.stringMatching(/Platform/), expect.stringMatching(/Notifications|Bildirimler/)]))
    fireEvent.change(select, { target: { value: 'ldap' } })
    expect(screen.getByTestId('sec-ldap')).toBeInTheDocument()
    expect(screen.getByRole('tabpanel').getAttribute('aria-labelledby')).toBe(select.id)
  })
})
