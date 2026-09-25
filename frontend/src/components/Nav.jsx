import { Fragment, useState, useEffect } from 'react'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import CommandPalette from './CommandPalette.jsx'
import InboxBell from './InboxBell.jsx'
import { LayoutDashboard, AlertTriangle, FileText, RefreshCw, ClipboardList, Settings, User, Globe, LogOut, Lock, Sun, Moon, ChevronDown, ChevronsUpDown, Server, Activity, ShieldAlert, BarChart3, Bell, BookOpen, History, Wifi, Network, Search, TrendingDown, Database, UserCheck, Users, ShieldCheck, CalendarDays, ListChecks, Target, Radio, Siren, Wrench, LifeBuoy, Gauge, ScanSearch, FlaskConical, Bug, MonitorSmartphone, Compass } from 'lucide-react'
import BrandLogo from './BrandLogo.jsx'
import IssueReportModal from './IssueReportModal.jsx'
import { LastLoginPopoverLines } from './LastLoginInfo.jsx'
import ModalShell from './ui/ModalShell.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import VersionChip from './VersionChip.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { useTour } from './tour/TourProvider.jsx'
// shadcn Sidebar (feature/shadcn-ui): kenar çubuğu, grup akordeonu, kullanıcı menüsü
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel,
  SidebarHeader, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarRail, SidebarTrigger, useSidebar,
} from '@/components/shadcn/sidebar'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { Kbd } from '@/components/shadcn/kbd'

export default function Nav({ activeTab, onTabChange, username, teamName, myTeams = [], systemRole, globalAdmin, onLogout, onChangePassword, globalStatus = 'ok', loginInfo = null, weeklyReportsVisible = false }) {
  const t = useT()
  const { toggle } = useLanguage()
  const { theme, toggle: toggleTheme } = useTheme()
  const tour = useTour()   // ürün turu: kullanıcı menüsünden yeniden başlat + sm:nav-reveal (2026-09-13)
  const { get: brand, branding } = useBranding()
  const { canView } = usePermissions()
  // Açık/daraltılmış durum SidebarProvider'da (App.jsx; localStorage 'sidebar-open' ile kalıcı).
  const { state, setOpen, isMobile, setOpenMobile } = useSidebar()
  const collapsed = state === 'collapsed' && !isMobile
  const isAdmin     = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const [issueOpen, setIssueOpen] = useState(false)   // kalıcı "Sorun Bildir" modalı
  // Faz 3b: global-only sekmeler yalnız global admin'e; scoped müdür (ADMIN) görmez.
  const isGlobalAdmin = !!globalAdmin

  const GROUPS = [
    {
      labelKey: null,
      tabs: [
        { id: 'dashboard', Icon: LayoutDashboard, labelKey: 'nav.dashboard', show: true },
      ],
    },
    {
      labelKey: 'nav.groupCertificates',
      tabs: [
        { id: 'all',           Icon: FileText,     labelKey: 'nav.all',           show: true },
        { id: 'domains',       Icon: Globe,        labelKey: 'nav.domains',       show: true },
        // Durum İzleme yalnız sertifika envanteri domainlerini izler (/uptime/overview → inventoryRepo) → Sertifikalar grubunda.
        { id: 'uptime',        Icon: Wifi,         labelKey: 'nav.uptime',        show: true },
        { id: 'forecast',      Icon: TrendingDown, labelKey: 'nav.forecast',      show: true },
        { id: 'renewal',       Icon: RefreshCw,    labelKey: 'nav.renewal',       show: true },
        { id: 'renewal-guide', Icon: BookOpen,     labelKey: 'nav.renewalGuide',  show: true },
      ],
    },
    {
      labelKey: 'nav.groupMonitoring',
      // Ara başlıklar (2026-09-23): `section` taşıyan sekme, önüne tıklanmayan bir alt başlık çizer —
      // türler cevapladıkları soruya göre: ayakta mı / adres doğru mu / sayfa doğru ve hızlı mı.
      tabs: [
        { id: 'http',    Icon: Globe,    labelKey: 'nav.http',    show: true, section: 'nav.secAvailability' },
        { id: 'ping',    Icon: Radio,    labelKey: 'nav.ping',    show: true },
        { id: 'port',   Icon: Network,  labelKey: 'nav.port',   show: true },
        { id: 'dns',     Icon: Search,   labelKey: 'nav.dns',     show: true, section: 'nav.secDomainDns' },
        { id: 'domain',  Icon: CalendarDays, labelKey: 'nav.domainmon', show: true },
        { id: 'keyword', Icon: Target,   labelKey: 'nav.keyword', show: true, section: 'nav.secContent' },
        { id: 'page',    Icon: ScanSearch, labelKey: 'nav.page',  show: true },
        { id: 'pagespeed', Icon: Gauge, labelKey: 'nav.pagespeed', show: true },
        { id: 'scripted', Icon: FlaskConical, labelKey: 'nav.scripted', show: true },
      ],
    },
    {
      labelKey: 'nav.groupAlerts',
      tabs: [
        { id: 'warnings',     Icon: AlertTriangle, labelKey: 'nav.warnings',     show: true },
        { id: 'incidents',    Icon: Siren,         labelKey: 'nav.incidents',    show: true },
        { id: 'maintenance',  Icon: Wrench,        labelKey: 'nav.maintenance',  show: true },
        { id: 'alerthistory', Icon: Bell,          labelKey: 'nav.alertHistory', show: true },
      ],
    },
    {
      labelKey: 'nav.groupReports',
      tabs: [
        { id: 'stats',         Icon: BarChart3,    labelKey: 'nav.stats',         show: true },
        { id: 'weakalgo',      Icon: ShieldAlert,  labelKey: 'nav.weakAlgo',      show: true },
        // Haftalık Raporlar modülü takım bazlı açılır (2026-09-16) — varsayılan KAPALI, sekme hiç çizilmez.
        { id: 'weeklyreports', Icon: CalendarDays, labelKey: 'nav.weeklyReports', show: weeklyReportsVisible },
        { id: 'incident-history', Icon: ListChecks, labelKey: 'nav.incidentHistory', show: true },
      ],
    },
    {
      labelKey: 'nav.groupLogs',
      tabs: [
        { id: 'activity',   Icon: ClipboardList, labelKey: 'nav.activity',   show: true               },
        { id: 'myactivity', Icon: UserCheck,     labelKey: 'nav.myActivity', show: true               },
        // Denetim Logu (2026-09-25, kullanıcı kararı): HERKESE açık — admin/AUDIT sistem geneli, diğerleri ekip
        // arkadaşlarının kayıtları (tam ayrıntı). Kapsamı uç belirler (AuditController.auditReadScope).
        { id: 'system',     Icon: Server,        labelKey: 'nav.system',     show: true },
        // Denetim konsolunun YANINDA: ikisi de "kim ne yaptı" sorusuna bakar — biri güvenlik
        // kaydına (audit_log), diğeri izleme yapılandırmasının ürün geçmişine.
        //
        // İkisi de herkese açık ve ikisi de ekip kapsamlı: Denetim Logu güvenlik kaydıdır (ekipte aktör
        // üyeliğiyle süzülür), bu ekran ürün geçmişidir (izlemenin takımı VEYA değişikliği yapan ekip üyesi).
        { id: 'monitorchanges', Icon: History,   labelKey: 'nav.monitorChanges', show: true },
      ],
    },
    {
      labelKey: 'nav.groupAdmin',
      tabs: [
        { id: 'admin',         Icon: Settings,    labelKey: 'nav.admin',         show: true    },
        { id: 'health',        Icon: Activity,    labelKey: 'nav.health',        show: true    },
        // Yetki Yönetimi (2026-09-25): herkes OKUR (kimin neye yetkisi var), yalnız global admin değiştirir.
        { id: 'permissions',   Icon: ShieldCheck, labelKey: 'nav.permissions',   show: true },
        { id: 'sqlplayground', Icon: Database,    labelKey: 'nav.sqlPlayground', show: isGlobalAdmin },
        // Login Sorun Bildirimleri — yalnız issues.login-reports/view izniyle (Yardım'ın hemen üstünde)
        { id: 'login-issues',  Icon: LifeBuoy,    labelKey: 'nav.loginIssues',   show: canView('issues.login-reports') },
      ],
    },
    {
      labelKey: null,
      tabs: [
        { id: 'help', Icon: BookOpen, labelKey: 'nav.help', show: true },
      ],
    },
  ]

  // Palet için görünür sekmeler (etiketleriyle) — GROUPS ile aynı show kuralları.
  const paletteTabs = GROUPS.flatMap((g) => g.tabs.filter((tb) => tb.show).map((tb) => ({ id: tb.id, label: t(tb.labelKey) })))

  const [openGroup, setOpenGroup] = useState(() => {
    try {
      const saved = localStorage.getItem('nav-group-open')
      if (saved !== null && saved !== '') {
        const n = Number(saved)
        if (Number.isInteger(n)) return n
      }
      localStorage.removeItem('nav-groups-open')
    } catch {}
    return null
  })

  // Çok takımlı kullanıcı (2026-09-18): kutuda tek (birincil) takım yerine "N takım" etiketi; menüdeki
  // "Dahil olduğum takımlar" tüm üyelikleri listeler (TeamBadge → üye modali).
  const multiTeam = Array.isArray(myTeams) && myTeams.length > 1
  const [teamsOpen, setTeamsOpen] = useState(false)
  const [photoErr, setPhotoErr] = useState(false)

  useEffect(() => {
    for (let gi = 0; gi < GROUPS.length; gi++) {
      const g = GROUPS[gi]
      if (g.labelKey && g.tabs.some(tab => tab.id === activeTab)) {
        setOpenGroup(gi)
        try { localStorage.setItem('nav-group-open', String(gi)) } catch {}
        return
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTab])

  // Tur motoru bir sekmeyi göstermek istediğinde kenar çubuğunu ve o sekmenin grubunu açar (2026-09-13)
  useEffect(() => {
    const onReveal = (e) => {
      const tabId = e?.detail?.tab
      if (!tabId) return
      if (isMobile) setOpenMobile(true)
      else if (state === 'collapsed') setOpen(true)
      const gi = GROUPS.findIndex((g) => g.labelKey && g.tabs.some((tb) => tb.id === tabId))
      if (gi >= 0) { setOpenGroup(gi); try { localStorage.setItem('nav-group-open', String(gi)) } catch { /* depolama yok */ } }
    }
    window.addEventListener('sm:nav-reveal', onReveal)
    return () => window.removeEventListener('sm:nav-reveal', onReveal)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state, isMobile])

  function toggleGroup(gi) {
    setOpenGroup(prev => {
      const next = prev === gi ? null : gi
      try {
        if (next === null) localStorage.removeItem('nav-group-open')
        else localStorage.setItem('nav-group-open', String(next))
      } catch {}
      return next
    })
  }

  function go(id) {
    onTabChange(id)
    if (isMobile) setOpenMobile(false)   // mobil çekmece: seçimden sonra kapanır (shadcn Sheet)
  }

  const initials = String(username || '?').slice(0, 2).toUpperCase()

  const renderItems = (visibleTabs) => (
    <SidebarMenu>
      {visibleTabs.map(({ id, Icon, labelKey, section }) => (
        <Fragment key={id}>
          {/* Ara başlık: tıklanmaz; daraltılmış (ikon) kipte gizlenir */}
          {section && (
            <li role="presentation" data-nav-section="" className="px-2 pt-2 pb-0.5 text-xs text-sidebar-foreground/60 group-data-[collapsible=icon]:hidden">
              {t(section)}
            </li>
          )}
          <SidebarMenuItem>
            <SidebarMenuButton
              isActive={activeTab === id}
              tooltip={t(labelKey)}
              data-tour={`nav-tab-${id}`}
              onClick={() => go(id)}
              className="data-[active=true]:[&>svg]:text-primary"
            >
              <Icon />
              <span>{t(labelKey)}</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </Fragment>
      ))}
    </SidebarMenu>
  )

  return (
    <Sidebar collapsible="icon">
      {/* ── Logo alanı (branding: kurum logosu/adı doluysa onlar) ── */}
      <SidebarHeader>
        <div className="flex items-center gap-2 px-1 py-1 group-data-[collapsible=icon]:flex-col group-data-[collapsible=icon]:px-0">
          {branding.logo_data ? (
            /* Özel logo (genelde geniş wordmark) dar ikon yuvasına sığmaz → daraltılmışken küçük ölçek */
            <img src={branding.logo_data} alt={brand('app_name', 'SiteMonitor')}
                 className="h-6 max-w-[150px] object-contain object-left group-data-[collapsible=icon]:max-w-8" />
          ) : (
            /* Durum-duyarlı marka logosu — yanında ad/sürüm metinleri korunur (renk tek sinyal değil) */
            <BrandLogo status={globalStatus} size={collapsed ? 24 : 32} />
          )}
          <div className="flex min-w-0 flex-1 flex-col leading-tight group-data-[collapsible=icon]:hidden">
            <span className="truncate text-sm font-semibold text-sidebar-accent-foreground">{brand('app_name', 'SiteMonitor')}</span>
            <VersionChip onTabChange={onTabChange} />
          </div>
          <SidebarTrigger className="text-muted-foreground" title={collapsed ? t('nav.expand') : t('nav.collapse')} aria-label={collapsed ? t('nav.expand') : t('nav.collapse')} />
        </div>
        <SidebarMenu>
          {/* Komut paleti tetiği (2026-09-12, #1): Ctrl+K — alan / izleme / takım / sekme tek kutuda */}
          <SidebarMenuItem>
            <SidebarMenuButton variant="outline" data-tour="nav-search" tooltip={t('palette.title')} aria-label={t('palette.title')}
              onClick={() => window.dispatchEvent(new CustomEvent('sm:palette'))} className="text-muted-foreground">
              <Search />
              <span>{t('palette.trigger')}</span>
              <Kbd className="ml-auto group-data-[collapsible=icon]:hidden">Ctrl K</Kbd>
            </SidebarMenuButton>
          </SidebarMenuItem>
          {/* Bildirim kutusu (2026-09-12, #2): açık alarm / çözülen / bakım / haftalık son giriş / dolan istisna */}
          <SidebarMenuItem data-tour="nav-inbox">
            <InboxBell username={username} />
          </SidebarMenuItem>
        </SidebarMenu>
        <CommandPalette tabs={paletteTabs} onTabChange={onTabChange} />
      </SidebarHeader>

      {/* ── Menü grupları: tek-açık akordeon (Collapsible) ── */}
      <SidebarContent data-tour="nav-groups">
        {GROUPS.map((group, gi) => {
          const visibleTabs = group.tabs.filter((tab) => tab.show)
          if (visibleTabs.length === 0) return null
          if (!group.labelKey) {
            return (
              <SidebarGroup key={gi} className="py-1">
                <SidebarGroupContent>{renderItems(visibleTabs)}</SidebarGroupContent>
              </SidebarGroup>
            )
          }
          // Daraltılmış (ikon) kipte başlık görünmez → bütün grupların ikonları görünür kalmalı
          const isOpen = collapsed || openGroup === gi
          return (
            <Collapsible key={gi} open={isOpen} onOpenChange={() => { if (!collapsed) toggleGroup(gi) }} className="group/collapsible">
              <SidebarGroup className="py-1">
                <SidebarGroupLabel asChild>
                  {/* Daraltılmış kipte başlık görünmez (opacity-0) ve bir şey yapmaz (onOpenChange
                      yutuluyor) — ama odaklanabilir kalıyordu: klavyede 7 görünmez, işlevsiz durak.
                      Tab sırasından ve erişilebilirlik ağacından çıkar (2026-09-25, R17). CSS tarafı
                      sidebar.jsx'te (invisible). */}
                  <CollapsibleTrigger className="w-full text-[11px] font-semibold tracking-wider uppercase"
                    tabIndex={collapsed ? -1 : undefined} aria-hidden={collapsed || undefined}>
                    {t(group.labelKey)}
                    <ChevronDown className="ml-auto size-3.5 transition-transform group-data-[state=closed]/collapsible:-rotate-90" />
                  </CollapsibleTrigger>
                </SidebarGroupLabel>
                {/* forceMount: kapalı grubun öğeleri DOM'da kalır — ürün turu `data-tour` kancalarını bulabilsin.
                    Radix forceMount'ta içeriği HEP açık çizer; kapalıyken gizlemek bizim işimiz → `hidden`
                    (görünmez + erişilebilirlik ağacı dışı). */}
                <CollapsibleContent forceMount hidden={!isOpen}>
                  <SidebarGroupContent>{renderItems(visibleTabs)}</SidebarGroupContent>
                </CollapsibleContent>
              </SidebarGroup>
            </Collapsible>
          )
        })}
      </SidebarContent>

      {/* ── Alt: kullanıcı menüsü (DropdownMenu) + çıkış ── */}
      <SidebarFooter>
        <SidebarMenu>
          <SidebarMenuItem>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <SidebarMenuButton size="lg" variant="outline" data-tour="nav-user" tooltip={t('nav.userSettings')}
                  className="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground">
                  <Avatar className="size-8 rounded-lg">
                    {!photoErr && <AvatarImage src="/api/me/photo" alt="" onError={() => setPhotoErr(true)} />}
                    <AvatarFallback className="rounded-lg">{photoErr ? <User className="size-4" /> : initials}</AvatarFallback>
                  </Avatar>
                  <span className="grid min-w-0 flex-1 text-left leading-tight">
                    <span className="flex items-center gap-1.5">
                      <span className="truncate font-semibold">{username}</span>
                      {isAdmin && <Badge className="h-4 px-1 text-[10px]">ADMIN</Badge>}
                      {isTeamAdmin && <Badge className="h-4 px-1 text-[10px]">TEAM ADMIN</Badge>}
                    </span>
                    {multiTeam
                      ? <span className="flex items-center gap-1 truncate text-xs text-muted-foreground" title={myTeams.map(tm => tm.name).join(', ')}><Users className="size-3" /> {t('nav.teamsCount', myTeams.length)}</span>
                      : teamName && <span className="truncate text-xs text-muted-foreground">{teamName}</span>}
                  </span>
                  <ChevronsUpDown className="ml-auto size-4" />
                </SidebarMenuButton>
              </DropdownMenuTrigger>
              <DropdownMenuContent side={isMobile ? 'bottom' : 'right'} align="end" sideOffset={6} className="w-60">
                <DropdownMenuLabel className="text-xs text-muted-foreground">{t('nav.userSettings')}</DropdownMenuLabel>
                {/* Giriş bilgisi — menü veri ÇEKMEZ, değerler App state'inden prop ile gelir. */}
                <LastLoginPopoverLines info={loginInfo} />
                <DropdownMenuSeparator />
                <DropdownMenuGroup>
                  {multiTeam && (
                    <DropdownMenuItem onSelect={() => setTeamsOpen(true)}>
                      <Users /> {t('nav.myTeams', myTeams.length)}
                    </DropdownMenuItem>
                  )}
                  {/* Son giriş satırlarının doğal devamı: tüm cihaz geçmişi (Etkinliklerim) */}
                  <DropdownMenuItem onSelect={() => go('myactivity')}>
                    <MonitorSmartphone /> {t('dev.navLink')}
                  </DropdownMenuItem>
                  {/* Ayarlar: rol ADMIN — global admin VE kapsamlı müdür (2026-09-10 ürün kararı; müdür için
                      sır yüzeyleri AdminSettings içinde kilitli, backend GLOBAL_ONLY/requireNotScopedAdmin uygular). */}
                  {isAdmin && (
                    <DropdownMenuItem onSelect={() => go('settings')}>
                      <Settings /> {t('nav.settings')}
                    </DropdownMenuItem>
                  )}
                  {/* Kalıcı "Sorun Bildir" — çökme OLMAYAN sorunlar (yanlış veri, yavaşlık, görsel bozukluk) */}
                  <DropdownMenuItem onSelect={() => setIssueOpen(true)}>
                    <Bug /> {t('nav.reportIssue')}
                  </DropdownMenuItem>
                  <DropdownMenuItem onSelect={() => onChangePassword?.()}>
                    <Lock /> {t('nav.changePassword')}
                  </DropdownMenuItem>
                  {/* Ürün turu (2026-09-13): kapatan kullanıcı istediğinde yeniden bulabilsin */}
                  <DropdownMenuItem onSelect={() => tour.start('main')}>
                    <Compass /> {t('tour.restart')}
                  </DropdownMenuItem>
                </DropdownMenuGroup>
                <DropdownMenuSeparator />
                {/* Tema ve dil — menü AÇIK kalır (preventDefault); değişiklik anında görünsün, geri alınabilsin. */}
                <DropdownMenuItem onSelect={(e) => { e.preventDefault(); toggleTheme() }}>
                  {theme === 'dark' ? <Sun /> : <Moon />} {theme === 'dark' ? t('nav.lightMode') : t('nav.darkMode')}
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={(e) => { e.preventDefault(); toggle() }}>
                  <Globe /> {t('nav.langSwitch')}
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarMenuItem>
          <SidebarMenuItem>
            <SidebarMenuButton tooltip={t('nav.logout')} className="hover:bg-destructive/10 hover:text-destructive"
              onClick={() => {
                try { localStorage.removeItem('nav-group-open') } catch {}
                onLogout()
              }}>
              <LogOut />
              <span>{t('nav.logout')}</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
        <IssueReportModal open={issueOpen} onClose={() => setIssueOpen(false)} />
        <ModalShell open={teamsOpen} onClose={() => setTeamsOpen(false)} title={t('nav.myTeamsTitle')} icon={Users} size="sm">
          <p className="text-sm text-muted-foreground">{t('nav.myTeamsHint')}</p>
          <ul className="mt-3 flex flex-col gap-2">
            {myTeams.map(tm => (
              <li key={tm.id} className="flex items-center gap-2">
                <TeamBadge teamId={tm.id} teamName={tm.name} size={14} />
                {teamName && tm.name === teamName && <Badge variant="secondary">{t('nav.primaryTeam')}</Badge>}
              </li>
            ))}
          </ul>
        </ModalShell>
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  )
}
