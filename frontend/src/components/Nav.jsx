import {
  useState, useEffect, useRef } from 'react'
import { useT } from '../i18n/index.jsx'
// Komut paleti (cmdk) ilk Ctrl/⌘+K ya da `sm:palette` isteğinde yüklenir — başlatıcı ilk basışı da karşılar (2026-10-09).
import CommandPaletteLauncher from './CommandPaletteLauncher.jsx'
import KeyboardShortcuts from './KeyboardShortcuts.jsx'
import { SHORTCUTS_EVENT } from '../utils/keyboardShortcuts.js'
import InboxBell from './InboxBell.jsx'
import {
  LayoutDashboard, ShieldCheck, FileBadge, Boxes, FileKey2, MonitorCheck, CalendarRange, RefreshCw, BookOpenText,
  Activity, Globe, Radio, EthernetPort, Waypoints, CalendarClock, TextSearch, FileCheck, Gauge, Workflow,
  BellRing, TriangleAlert, Siren, Wrench, History, Headset,
  ChartColumn, ChartPie, ShieldAlert, FileChartColumn, FileClock,
  ScrollText, Logs, UserCheck, Fingerprint, FilePenLine,
  Settings2, Building2, HeartPulse, KeyRound, Database, MessageSquareWarning,
  LifeBuoy, Search, Users, Radar, CloudLightning, SignalHigh, ClipboardCheck, Presentation,
} from 'lucide-react'
import IssueReportModal from './IssueReportModal.jsx'
import ModalShell from './ui/ModalShell.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { useTour } from './tour/TourProvider.jsx'
import NavBrand from './nav/NavBrand.jsx'
import NavMain, { NavLeafItem } from './nav/NavMain.jsx'
import NavUser from './nav/NavUser.jsx'
import { useOpenAlerts } from '../hooks/useOpenAlerts.js'
import { NAV_ITEM } from './nav/navStyles.js'
// shadcn Sidebar (sidebar-07 "collapses to icons" deseni): marka + arama + bildirim / ana menü / kullanıcı
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarHeader,
  SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarRail, SidebarSeparator, useSidebar,
} from '@/components/shadcn/sidebar'
import { Badge } from '@/components/shadcn/badge'
import { Kbd } from '@/components/shadcn/kbd'

/** Açık bölüm (tek-açık akordeon) — bölüm ANAHTARI saklanır; eski dizin tabanlı anahtarlar temizlenir. */
const OPEN_KEY = 'nav-section-open'
const LEGACY_KEYS = ['nav-group-open', 'nav-groups-open']
function readOpenSection() {
  try {
    for (const k of LEGACY_KEYS) localStorage.removeItem(k)
    return localStorage.getItem(OPEN_KEY) || null
  } catch { return null }
}
function writeOpenSection(key) {
  try { if (key) localStorage.setItem(OPEN_KEY, key); else localStorage.removeItem(OPEN_KEY) } catch { /* depolama yok */ }
}

export default function Nav({ activeTab, onTabChange, username, teamName, myTeams = [], systemRole, globalAdmin, onLogout, onChangePassword, globalStatus = 'ok', loginInfo = null, profile = null, weeklyReportsVisible = false }) {
  const t = useT()
  const tour = useTour()   // ürün turu: kullanıcı menüsünden yeniden başlat + sm:nav-reveal (2026-09-13)
  // Açık/daraltılmış durum SidebarProvider'da (App.jsx; localStorage 'sidebar-open' ile kalıcı).
  const { state, setOpen, isMobile, setOpenMobile } = useSidebar()
  const collapsed = state === 'collapsed' && !isMobile
  const [issueOpen, setIssueOpen] = useState(false)   // kalıcı "Sorun Bildir" modalı
  // Modal ilk açılışta bağlanır, sonra kapalı hâliyle bağlı kalır (kapanış animasyonu/odak iadesi aynı) — 2026-10-09.
  // Kodu açılış paketinde kalır: ErrorBoundary çökme ekranı onu parça indirmeden açabilmeli.
  const [issueMounted, setIssueMounted] = useState(false)
  const [teamsOpen, setTeamsOpen] = useState(false)
  // Faz 3b: global-only sekmeler yalnız global admin'e; scoped müdür (ADMIN) görmez.
  const isGlobalAdmin = !!globalAdmin

  // ── Menü yapısı: üst bölümler (kendi ikonlarıyla) + alt sekmeler. İkon seti TEK: lucide, 16 px, çizgi 2.
  //    Aynı düzeyde iki farklı öğe aynı ikonu taşımaz. Görünürlük kuralları `show` alanında.
  const SECTIONS = [
    { id: 'dashboard', Icon: LayoutDashboard, labelKey: 'nav.dashboard', show: true },
    {
      key: 'certificates', Icon: ShieldCheck, labelKey: 'nav.groupCertificates',
      items: [
        { id: 'all',           Icon: FileBadge,     labelKey: 'nav.all',           show: true },
        { id: 'domains',       Icon: Boxes,         labelKey: 'nav.domains',       show: true },
        // Manuel Sertifikalar (2026-10-06): dosyadan yüklenen sertifikalar — Envanter gibi herkese açık (kapsam sunucuda)
        { id: 'manualcerts',   Icon: FileKey2,      labelKey: 'nav.manualCerts',   show: true },
        // Durum İzleme yalnız sertifika envanteri domainlerini izler (/uptime/overview → inventoryRepo) → Sertifikalar grubunda.
        { id: 'uptime',        Icon: MonitorCheck,  labelKey: 'nav.uptime',        show: true },
        { id: 'forecast',      Icon: CalendarRange, labelKey: 'nav.forecast',      show: true },
        { id: 'renewal',       Icon: RefreshCw,     labelKey: 'nav.renewal',       show: true },
        { id: 'renewal-guide', Icon: BookOpenText,  labelKey: 'nav.renewalGuide',  show: true },
      ],
    },
    {
      key: 'monitoring', Icon: Activity, labelKey: 'nav.groupMonitoring',
      // Ara başlıklar (2026-09-23): `section` taşıyan sekme, önüne tıklanmayan küçük bir başlık çizer —
      // türler cevapladıkları soruya göre: ayakta mı / adres doğru mu / sayfa doğru ve hızlı mı.
      items: [
        // İzleme Panosu (2026-09-30): 9 türün tek ekranda durumu — bölümün ilk sırasında, ara başlıksız.
        { id: 'monitoring', Icon: Radar,        labelKey: 'nav.monitoringOverview', show: true },
        // Kurum içi Durum Sayfası (2026-10-01): hizmet düzeyinde özet — oturum açmış HERKES (operasyon dışı takımlar, yönetim)
        { id: 'status',    Icon: SignalHigh,    labelKey: 'nav.statusPage', show: true },
        { id: 'http',      Icon: Globe,         labelKey: 'nav.http',      show: true, section: 'nav.secAvailability' },
        { id: 'ping',      Icon: Radio,     labelKey: 'nav.ping',      show: true },
        { id: 'port',      Icon: EthernetPort,  labelKey: 'nav.port',      show: true },
        { id: 'dns',       Icon: Waypoints,     labelKey: 'nav.dns',       show: true, section: 'nav.secDomainDns' },
        { id: 'domain',    Icon: CalendarClock, labelKey: 'nav.domainmon', show: true },
        { id: 'keyword',   Icon: TextSearch,    labelKey: 'nav.keyword',   show: true, section: 'nav.secContent' },
        { id: 'page',      Icon: FileCheck,     labelKey: 'nav.page',      show: true },
        { id: 'pagespeed', Icon: Gauge,         labelKey: 'nav.pagespeed', show: true },
        { id: 'scripted',  Icon: Workflow,      labelKey: 'nav.scripted',  show: true },
      ],
    },
    {
      key: 'alerts', Icon: BellRing, labelKey: 'nav.groupAlerts',
      items: [
        { id: 'warnings',     Icon: TriangleAlert, labelKey: 'nav.warnings',     show: true },
        { id: 'incidents',    Icon: Siren,         labelKey: 'nav.incidents',    show: true },
        { id: 'maintenance',  Icon: Wrench,        labelKey: 'nav.maintenance',  show: true },
        { id: 'alerthistory', Icon: History,       labelKey: 'nav.alertHistory', show: true },
        // Alarm Fırtınası (2026-09-30): takım bazlı eşik yakınlığı, açık fırtınalar, geçmiş ve analiz — görüş kapsamlı
        { id: 'storms',       Icon: CloudLightning, labelKey: 'nav.storms',       show: true },
        // 7/24 Kapsamı (2026-09-27): hangi izlemeler gece kesintisinde 7/24 izleme ekibine gitmiyor — herkese açık, görüş kapsamlı
        { id: 'noc',          Icon: Headset,       labelKey: 'nav.noc',          show: true },
      ],
    },
    {
      key: 'reports', Icon: ChartColumn, labelKey: 'nav.groupReports',
      items: [
        { id: 'stats',            Icon: ChartPie,        labelKey: 'nav.stats',           show: true },
        { id: 'weakalgo',         Icon: ShieldAlert,     labelKey: 'nav.weakAlgo',        show: true },
        // Veri Kalitesi (2026-10-10): takım veri kalitesi puanı + düzeltme listesi — herkese açık, görüş kapsamlı (sunucu)
        { id: 'dataquality',      Icon: ClipboardCheck,  labelKey: 'nav.dataQuality',     show: true },
        // Haftalık Raporlar modülü takım bazlı açılır (2026-09-16) — varsayılan KAPALI, sekme hiç çizilmez.
        { id: 'weeklyreports',    Icon: FileChartColumn, labelKey: 'nav.weeklyReports',   show: weeklyReportsVisible },
        { id: 'incident-history', Icon: FileClock,       labelKey: 'nav.incidentHistory', show: true },
        // Aylık Yönetici Özeti (2026-10-10): kurum geneli rapor — yalnız global yönetici + AUDIT (uç ayrıca izin ister)
        { id: 'executive',        Icon: Presentation,    labelKey: 'nav.executiveSummary', show: systemRole === 'ADMIN' || systemRole === 'AUDIT' },
      ],
    },
    {
      key: 'logs', Icon: ScrollText, labelKey: 'nav.groupLogs',
      items: [
        { id: 'activity',   Icon: Logs,        labelKey: 'nav.activity',   show: true },
        { id: 'myactivity', Icon: UserCheck,   labelKey: 'nav.myActivity', show: true },
        // Denetim Logu (2026-09-25, kullanıcı kararı): HERKESE açık — admin/AUDIT sistem geneli, diğerleri ekip
        // arkadaşlarının kayıtları (tam ayrıntı). Kapsamı uç belirler (AuditController.auditReadScope).
        { id: 'system',     Icon: Fingerprint, labelKey: 'nav.system',     show: true },
        // Denetim konsolunun YANINDA: ikisi de "kim ne yaptı" sorusuna bakar — biri güvenlik kaydına (audit_log),
        // diğeri izleme yapılandırmasının ürün geçmişine. İkisi de herkese açık ve ekip kapsamlı.
        { id: 'monitorchanges', Icon: FilePenLine, labelKey: 'nav.monitorChanges', show: true },
      ],
    },
    {
      key: 'management', Icon: Settings2, labelKey: 'nav.groupAdmin',
      items: [
        { id: 'admin',         Icon: Building2,  labelKey: 'nav.admin',         show: true },
        { id: 'health',        Icon: HeartPulse, labelKey: 'nav.health',        show: true },
        // Yetki Yönetimi (2026-09-25): herkes OKUR (kimin neye yetkisi var), yalnız global admin değiştirir.
        { id: 'permissions',   Icon: KeyRound,   labelKey: 'nav.permissions',   show: true },
        { id: 'sqlplayground', Icon: Database,   labelKey: 'nav.sqlPlayground', show: isGlobalAdmin },
        // Login Sorun Bildirimleri — yalnız issues.login-reports/view izniyle
        { id: 'login-issues',  Icon: MessageSquareWarning, labelKey: 'nav.loginIssues', show: true },   // herkes: kendi bildirimleri (2026-09-26); yönetici tam liste (izin sayfada)
      ],
    },
  ]
  // İkincil menü (shadcn `nav-secondary`): alt kısımda, ana menüden ayrı.
  const HELP = { id: 'help', Icon: LifeBuoy, labelKey: 'nav.help', show: true }

  const sections = SECTIONS
    .map((s) => (s.items ? { ...s, items: s.items.filter((it) => it.show) } : s))
    .filter((s) => (s.items ? s.items.length > 0 : s.show))
  const sectionsRef = useRef(sections)
  sectionsRef.current = sections
  const sectionOf = (tabId) => sectionsRef.current.find((s) => s.items?.some((it) => it.id === tabId))?.key ?? null

  // Palet için görünür sekmeler (etiketleriyle) — menüyle aynı görünürlük kuralları ve sıra.
  const paletteTabs = [...sections.flatMap((s) => (s.items || [s])), HELP].map((tb) => ({ id: tb.id, label: t(tb.labelKey) }))

  const [openSection, setOpenSection] = useState(readOpenSection)
  const openOnly = (key) => { setOpenSection(key); writeOpenSection(key) }

  // Etkin sekmenin bölümü kendiliğinden açılır (Pano/Yardım gibi yapraklarda açık bölüm korunur).
  useEffect(() => {
    const key = sectionOf(activeTab)
    if (key) openOnly(key)
  }, [activeTab])

  // Tur motoru bir sekmeyi göstermek istediğinde kenar çubuğunu ve o sekmenin bölümünü açar (2026-09-13).
  // `tab: '*'` = yalnız menüyü aç (kullanıcı kartı adımı). `sm:nav-conceal` = telefonda çekmeceyi kapat: menü
  // DIŞINDAKİ hedefler (üst çubuktaki zil, yardım düğmesi) açık çekmecenin örtüsü altında kalıyordu (2026-09-26).
  useEffect(() => {
    const onReveal = (e) => {
      const tabId = e?.detail?.tab
      if (!tabId) return
      if (isMobile) setOpenMobile(true)
      else if (state === 'collapsed') setOpen(true)
      const key = tabId === '*' ? null : sectionOf(tabId)
      if (key) openOnly(key)
    }
    const onConceal = () => { if (isMobile) setOpenMobile(false) }
    window.addEventListener('sm:nav-reveal', onReveal)
    window.addEventListener('sm:nav-conceal', onConceal)
    return () => {
      window.removeEventListener('sm:nav-reveal', onReveal)
      window.removeEventListener('sm:nav-conceal', onConceal)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, isMobile])

  function toggleSection(key) {
    setOpenSection((prev) => {
      const next = prev === key ? null : key
      writeOpenSection(next)
      return next
    })
  }

  // Mobil çekmece (shadcn Sheet): bir hedef seçilince kapanır. Bölüm aç/kapa ve tema/dil kapatmaz.
  const closeMobile = () => { if (isMobile) setOpenMobile(false) }
  function go(id, extra) {
    if (extra === undefined) onTabChange(id); else onTabChange(id, extra)   // sürüm popover'ı ek parametre verir
    closeMobile()
  }
  // İzleme menüsü rozetleri (2026-09-30): görüş kapsamındaki açık alarmlar, izleme türü başına; dakikada bir yoklanır.
  const openAlerts = useOpenAlerts()
  const goAlerts = (tabId, alertId) => {
    const params = { view: 'open', src: tabId }
    if (alertId != null) params.alert = String(alertId)
    go('alerthistory', params)
  }
  function logout() {
    writeOpenSection(null)
    closeMobile()
    onLogout()
  }

  return (
    <>
      <Sidebar collapsible="icon">
        {/* ── Başlık: marka (Pano) + hızlı arama + bildirimler; alt kenarla ana menüden ayrılır ── */}
        <SidebarHeader className="gap-1.5 border-b border-sidebar-border">
          <NavBrand onHome={() => go('dashboard')} onTabChange={go} collapsed={collapsed} isMobile={isMobile} globalStatus={globalStatus} />
          <SidebarMenu className="gap-1">
            {/* Komut paleti tetiği (2026-09-12, #1): Ctrl+K — alan / izleme / takım / sekme tek kutuda.
                Diğer satırlarla aynı düğme görünümü (yazı alanı değil); ikon kipinde yalın kutucuk. */}
            <SidebarMenuItem>
              <SidebarMenuButton data-tour="nav-search" tooltip={t('palette.title')} aria-label={t('palette.title')}
                onClick={() => { closeMobile(); window.dispatchEvent(new CustomEvent('sm:palette')) }}
                className={NAV_ITEM}>
                <Search aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate">{t('palette.trigger')}</span>
                <Kbd className="ml-auto bg-background/70 group-data-[collapsible=icon]:hidden">Ctrl K</Kbd>
              </SidebarMenuButton>
            </SidebarMenuItem>
            {/* Bildirim kutusu (2026-09-12, #2). Telefonda zil mobil üst çubukta (MobileTopBar) — tek örnek.
                Satır görünümü yalnız className ile (InboxBell'in kendisi başka bir ajanın elinde). */}
            {!isMobile && (
              <SidebarMenuItem data-tour="nav-inbox">
                <InboxBell username={username} className={NAV_ITEM}
                  badgeClassName="peer-data-[size=default]/menu-button:top-2.5 right-2" />
              </SidebarMenuItem>
            )}
          </SidebarMenu>
        </SidebarHeader>

        {/* ── Ana menü (tek gezinme bölgesi) + ikincil menü (Yardım, ayraçla) ── */}
        <SidebarContent data-tour="nav-groups" role="navigation" aria-label={t('nav.sidebarTitle')}>
          <NavMain label={t('nav.groupPlatform')} sections={sections} activeTab={activeTab} openSection={openSection}
            onToggle={toggleSection} onSelect={go} collapsed={collapsed}
            alertCounts={openAlerts.visible ? openAlerts.byTab : null} onGoAlerts={goAlerts} />
          <SidebarGroup className="mt-auto pt-0">
            <SidebarSeparator className="mx-0 mb-2" />
            <SidebarGroupContent>
              <SidebarMenu>
                <NavLeafItem item={HELP} active={activeTab === HELP.id} onSelect={go} />
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        </SidebarContent>

        {/* ── Alt: kullanıcı kartı (çıkış menünün içinde) ── */}
        <SidebarFooter className="border-t border-sidebar-border">
          <NavUser username={username} profile={profile} teamName={teamName} myTeams={myTeams} systemRole={systemRole} loginInfo={loginInfo}
            isMobile={isMobile} onGo={go}
            onOpenTeams={() => { closeMobile(); setTeamsOpen(true) }}
            onReportIssue={() => { closeMobile(); setIssueMounted(true); setIssueOpen(true) }}
            onChangePassword={() => { closeMobile(); onChangePassword?.() }}
            onStartTour={() => { closeMobile(); tour.start('main') }}
            onShowShortcuts={() => { closeMobile(); window.dispatchEvent(new CustomEvent(SHORTCUTS_EVENT)) }}
            onLogout={logout} />
        </SidebarFooter>
        <SidebarRail />
      </Sidebar>

      {/* Kenar çubuğunun DIŞINDA: telefonda çekmece kapanınca içeriği DOM'dan çıkar — palet ve pencereler yaşasın. */}
      <CommandPaletteLauncher tabs={paletteTabs} onTabChange={go} globalAdmin={globalAdmin} systemRole={systemRole} />
      {/* Genel klavye kısayolları (öneri 24): paletle AYNI sekme listesi → `g`+harf yalnız açık sekmelere gider */}
      <KeyboardShortcuts tabs={paletteTabs} onTabChange={go} />
      {issueMounted && <IssueReportModal open={issueOpen} onClose={() => setIssueOpen(false)} />}
      <ModalShell open={teamsOpen} onClose={() => setTeamsOpen(false)} title={t('nav.myTeamsTitle')} icon={Users} size="sm">
        <p className="text-sm text-muted-foreground">{t('nav.myTeamsHint')}</p>
        <ul className="mt-3 flex flex-col gap-2">
          {myTeams.map((tm) => (
            <li key={tm.id} className="flex items-center gap-2">
              <TeamBadge teamId={tm.id} teamName={tm.name} size={14} />
              {teamName && tm.name === teamName && <Badge variant="secondary">{t('nav.primaryTeam')}</Badge>}
            </li>
          ))}
        </ul>
      </ModalShell>
    </>
  )
}
