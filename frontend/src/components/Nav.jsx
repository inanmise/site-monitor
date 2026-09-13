import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import CommandPalette from './CommandPalette.jsx'
import InboxBell from './InboxBell.jsx'
import { LayoutDashboard, AlertTriangle, FileText, RefreshCw, ClipboardList, Settings, User, Globe, LogOut, Lock, Sun, Moon, ChevronLeft, ChevronRight, ChevronDown, ChevronUp, Server, Activity, ShieldAlert, BarChart3, Bell, BookOpen, History, Wifi, Network, Search, TrendingDown, Database, UserCheck, ShieldCheck, CalendarDays, ListChecks, Target, Radio, Siren, Wrench, LifeBuoy, Gauge, ScanSearch, FlaskConical, Bug, MonitorSmartphone, Compass } from 'lucide-react'
import BrandLogo from './BrandLogo.jsx'
import IssueReportModal from './IssueReportModal.jsx'
import { LastLoginPopoverLines } from './LastLoginInfo.jsx'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import VersionChip from './VersionChip.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { useTour } from './tour/TourProvider.jsx'

export default function Nav({ activeTab, onTabChange, username, teamName, systemRole, globalAdmin, onLogout, onChangePassword, globalStatus = 'ok', loginInfo = null }) {
  const t = useT()
  const { toggle } = useLanguage()
  const { theme, toggle: toggleTheme } = useTheme()
  const tour = useTour()   // ürün turu: kullanıcı menüsünden yeniden başlat + sm:nav-reveal (2026-09-13)
  const { get: brand, branding } = useBranding()
  // Sürüm SUNUCUDAN gelir (VersionChip → useAppVersion); çip tıklanınca yayın/dağıtım popover'ı açılır.
  const { canView } = usePermissions()
  const isAdmin     = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const isAudit     = systemRole === 'AUDIT'
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
      tabs: [
        { id: 'http',    Icon: Globe,    labelKey: 'nav.http',    show: true },
        { id: 'domain',  Icon: CalendarDays, labelKey: 'nav.domainmon', show: true },
        { id: 'port',   Icon: Network,  labelKey: 'nav.port',   show: true },
        { id: 'dns',     Icon: Search,   labelKey: 'nav.dns',     show: true },
        { id: 'keyword', Icon: Target,   labelKey: 'nav.keyword', show: true },
        { id: 'ping',    Icon: Radio,    labelKey: 'nav.ping',    show: true },
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
        { id: 'weeklyreports', Icon: CalendarDays, labelKey: 'nav.weeklyReports', show: true },
        { id: 'incident-history', Icon: ListChecks, labelKey: 'nav.incidentHistory', show: true },
      ],
    },
    {
      labelKey: 'nav.groupLogs',
      tabs: [
        { id: 'activity',   Icon: ClipboardList, labelKey: 'nav.activity',   show: true               },
        { id: 'myactivity', Icon: UserCheck,     labelKey: 'nav.myActivity', show: true               },
        { id: 'system',     Icon: Server,        labelKey: 'nav.system',     show: isGlobalAdmin || isAudit },
        // Denetim konsolunun YANINDA: ikisi de "kim ne yaptı" sorusuna bakar — biri güvenlik
        // kaydına (audit_log), diğeri izleme yapılandırmasının ürün geçmişine.
        //
        // İkisi AYNI kapıda DEĞİL, olmamalı da: Denetim Logu sistem-genelidir (tüm takımlar,
        // tüm kullanıcılar) ve admin/AUDIT'te kalır. Bu ekran ürün geçmişidir ve takım-kapsamlı
        // sorgulanır — herkes kendi takımının değişikliklerini görür. Uç zaten viewTeamIds ile
        // sınırlıyor; menüyü gizlemek yalnız kullanıcıyı kendi verisinden mahrum bırakırdı.
        { id: 'monitorchanges', Icon: History,   labelKey: 'nav.monitorChanges', show: true },
      ],
    },
    {
      labelKey: 'nav.groupAdmin',
      tabs: [
        { id: 'admin',         Icon: Settings,    labelKey: 'nav.admin',         show: true    },
        { id: 'health',        Icon: Activity,    labelKey: 'nav.health',        show: true    },
        { id: 'permissions',   Icon: ShieldCheck, labelKey: 'nav.permissions',   show: isGlobalAdmin },
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

  // Depolama kapalıysa (kurumsal politika/gizli mod) çıplak erişim render'ı düşürürdü; varsayılan açık.
  const [open, setOpen] = useState(() => {
    try { return localStorage.getItem('sidebar-open') !== 'false' } catch { return true }
  })

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

  const [userMenuOpen, setUserMenuOpen] = useState(false)
  const [userMenuPos, setUserMenuPos] = useState(null)
  const [photoErr, setPhotoErr] = useState(false)
  const userTriggerRef = useRef(null)
  const userPopoverRef = useRef(null)

  useEffect(() => {
    if (!userMenuOpen) { setUserMenuPos(null); return }
    if (userTriggerRef.current) {
      const r = userTriggerRef.current.getBoundingClientRect()
      setUserMenuPos({
        left: Math.round(r.right + 6),
        bottom: Math.round(window.innerHeight - r.bottom),
      })
    }
    function onDocClick(e) {
      const t = e.target
      const insideTrigger = userTriggerRef.current && userTriggerRef.current.contains(t)
      const insidePopover = userPopoverRef.current && userPopoverRef.current.contains(t)
      if (!insideTrigger && !insidePopover) setUserMenuOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [userMenuOpen])

  useEffect(() => {
    for (let gi = 0; gi < GROUPS.length; gi++) {
      const g = GROUPS[gi]
      if (g.labelKey && g.tabs.some(tab => tab.id === activeTab)) {
        setOpenGroup(gi)
        try { localStorage.setItem('nav-group-open', String(gi)) } catch {}
        return
      }
    }
  }, [activeTab])

  // Tur motoru bir sekmeyi göstermek istediğinde kenar çubuğunu ve o sekmenin grubunu açar (2026-09-13)
  useEffect(() => {
    const onReveal = (e) => {
      const tabId = e?.detail?.tab
      if (!tabId) return
      if (!open) { setOpen(true); try { localStorage.setItem('sidebar-open', 'true') } catch { /* depolama yok */ } }
      const gi = GROUPS.findIndex((g) => g.labelKey && g.tabs.some((tb) => tb.id === tabId))
      if (gi >= 0) { setOpenGroup(gi); try { localStorage.setItem('nav-group-open', String(gi)) } catch { /* depolama yok */ } }
    }
    window.addEventListener('sm:nav-reveal', onReveal)
    return () => window.removeEventListener('sm:nav-reveal', onReveal)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

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

  function toggleSidebar() {
    const next = !open
    setOpen(next)
    try { localStorage.setItem('sidebar-open', String(next)) } catch { /* depolama yok */ }
  }

  return (
    <aside className={`sb${open ? '' : ' sb-closed'}`}>

      {/* ── Logo area (branding: kurum logosu/adı doluysa onlar) ── */}
      <div className="sb-head">
        {branding.logo_data ? (
          /* Özel logo (genelde geniş wordmark) dar ikon yuvasına sığmaz → DİKEY istif:
             logo üstte tek başına, ad/rozet/versiyon altında. Daraltılmış sidebar'da yalnız
             küçük ölçekli logo gösterilir. */
          <div className="sb-brand" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: 6, minWidth: 0 }}>
            <img src={branding.logo_data} alt={brand('app_name', 'SiteMonitor')}
                 style={{ height: 24, maxWidth: open ? 150 : 34, objectFit: 'contain', objectPosition: 'left' }} />
            {open && (
              <div className="sb-brand-text">
                <span className="sb-brand-name">{brand('app_name', 'SiteMonitor')}</span>
                <VersionChip onTabChange={onTabChange} />
              </div>
            )}
          </div>
        ) : (
          <div className="sb-brand">
            {/* Durum-duyarlı marka logosu — yanında ad/rozet metinleri korunur (renk tek sinyal değil) */}
            <span className="sb-logo"><BrandLogo status={globalStatus} size={open ? 32 : 26} /></span>
            {open && (
              <div className="sb-brand-text">
                <span className="sb-brand-name">{brand('app_name', 'SiteMonitor')}</span>
                <VersionChip onTabChange={onTabChange} />
              </div>
            )}
          </div>
        )}
        <button className="sb-toggle" onClick={toggleSidebar} title={open ? t('nav.collapse') : t('nav.expand')}>
          {open ? <ChevronLeft size={14} /> : <ChevronRight size={14} />}
        </button>
      </div>

      {/* Komut paleti tetiği (2026-09-12, #1): Ctrl+K — alan / izleme / takım / sekme tek kutuda */}
      <button type="button" data-tour="nav-search" className={`sb-search${open ? '' : ' sb-search--mini'}`}
        onClick={() => window.dispatchEvent(new CustomEvent('sm:palette'))} title={t('palette.title')} aria-label={t('palette.title')}>
        <Search size={14} aria-hidden="true" />
        {open && <><span className="sb-search-text">{t('palette.trigger')}</span><kbd className="sb-search-kbd">Ctrl K</kbd></>}
      </button>
      <CommandPalette tabs={paletteTabs} onTabChange={onTabChange} />
      {/* Bildirim kutusu (2026-09-12, #2): açık alarm / çözülen / bakım / haftalık son giriş / dolan istisna */}
      <div data-tour="nav-inbox" className="sb-inbox-wrap"><InboxBell username={username} compact={!open} /></div>

      {/* ── Nav items ── */}
      <nav className="sb-nav" data-tour="nav-groups">
        {GROUPS.map((group, gi) => {
          const visibleTabs = group.tabs.filter((tab) => tab.show)
          if (visibleTabs.length === 0) return null
          const hasLabel    = !!group.labelKey
          const isGroupOpen = openGroup === gi
          const collapsed   = hasLabel && open && !isGroupOpen
          return (
            <div key={gi} className="sb-group">
              {hasLabel && open && (
                <button className="sb-group-header" onClick={() => toggleGroup(gi)}>
                  <span className="sb-group-header-text">{t(group.labelKey).toLocaleUpperCase('en-US')}</span>
                  <ChevronDown size={11} className={`sb-group-chevron${isGroupOpen ? '' : ' sb-group-chevron-closed'}`} />
                </button>
              )}
              {hasLabel && !open && gi > 0 && (
                <div className="sb-group-rule" />
              )}
              <div className={`sb-group-items${collapsed ? ' sb-group-items-collapsed' : ''}`}>
                {visibleTabs.map(({ id, Icon, labelKey }) => (
                  <button
                    key={id}
                    className={`sb-item${activeTab === id ? ' sb-active' : ''}`}
                    data-tour={`nav-tab-${id}`}
                    onClick={() => onTabChange(id)}
                    title={!open ? t(labelKey) : undefined}
                  >
                    <span className="sb-icon"><Icon size={18} /></span>
                    {open && <span className="sb-label">{t(labelKey)}</span>}
                  </button>
                ))}
              </div>
            </div>
          )
        })}
      </nav>

      {/* ── Footer: user settings, theme toggle, lang toggle, logout ── */}
      <div className="sb-foot">
        <div className="sb-user-wrap">
          <button
            ref={userTriggerRef}
            className={`sb-user-trigger${userMenuOpen ? ' is-open' : ''}`}
            data-tour="nav-user"
            onClick={() => setUserMenuOpen(v => !v)}
            title={!open ? t('nav.userSettings') : undefined}
          >
            {photoErr
              ? <User size={14} />
              : <img className="sb-user-photo" alt="" src="/api/me/photo" onError={() => setPhotoErr(true)} />}
            {open && (
              <div className="sb-user-info">
                <span className="sb-user-name">{username}</span>
                {teamName && <span className="sb-team-name">{teamName}</span>}
                {isAdmin && <span className="sb-role-badge">ADMIN</span>}
                {isTeamAdmin && <span className="sb-role-badge">TEAM ADMIN</span>}
              </div>
            )}
            {open && <ChevronUp size={12} className="sb-user-chevron" />}
          </button>
        </div>
        {userMenuOpen && userMenuPos && createPortal(
          <div
            ref={userPopoverRef}
            className="sb-user-popover"
            style={{ left: userMenuPos.left, bottom: userMenuPos.bottom }}
          >
            <div className="sb-user-popover-hdr">{t('nav.userSettings')}</div>
            {/* Giriş bilgisi — popover veri ÇEKMEZ, değerler App state'inden prop ile gelir. */}
            <LastLoginPopoverLines info={loginInfo} />
            {/* Popover son girisi gosteriyor; dogal devami "peki tum gecmis?" —
                kullaniciyi Etkinliklerim'deki Cihaz Gecmisi gorunumune goturur. */}
            <button
              className="sb-user-popover-item"
              onClick={() => { setUserMenuOpen(false); onTabChange('myactivity') }}
            >
              <MonitorSmartphone size={14} />
              <span>{t('dev.navLink')}</span>
            </button>
            {/* Ayarlar: rol ADMIN — global admin VE kapsamlı müdür (2026-09-10 ürün kararı; müdür için
                sır yüzeyleri AdminSettings içinde kilitli, backend GLOBAL_ONLY/requireNotScopedAdmin uygular). */}
            {isAdmin && (
              <button
                className="sb-user-popover-item"
                onClick={() => { setUserMenuOpen(false); onTabChange('settings') }}
              >
                <Settings size={14} />
                <span>{t('nav.settings')}</span>
              </button>
            )}
            <button
              className="sb-user-popover-item"
              onClick={() => { setUserMenuOpen(false); onChangePassword?.() }}
            >
              <Lock size={14} />
              <span>{t('nav.changePassword')}</span>
            </button>
            {/* Ürün turu (2026-09-13): kapatan kullanıcı istediğinde yeniden bulabilsin */}
            <button
              className="sb-user-popover-item"
              onClick={() => { setUserMenuOpen(false); tour.start('main') }}
            >
              <Compass size={14} />
              <span>{t('tour.restart')}</span>
            </button>
          </div>,
          document.body
        )}
        {/* Kalıcı "Sorun Bildir" girişi — çökme OLMAYAN sorunlar için (yanlış veri, yavaşlık,
            görsel bozukluk). Çökme durumunda ErrorBoundary kendi butonunu gösterir. */}
        <button
          className="sb-logout"
          onClick={() => setIssueOpen(true)}
          title={!open ? t('nav.reportIssue') : undefined}
        >
          <Bug size={15} />
          {open && <span>{t('nav.reportIssue')}</span>}
        </button>
        <IssueReportModal open={issueOpen} onClose={() => setIssueOpen(false)} />
        <button
          className="sb-logout"
          data-tour="nav-theme"
          onClick={toggleTheme}
          title={!open ? (theme === 'dark' ? t('nav.lightMode') : t('nav.darkMode')) : undefined}
        >
          {theme === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
          {open && <span>{theme === 'dark' ? t('nav.lightMode') : t('nav.darkMode')}</span>}
        </button>
        <button
          className="sb-logout"
          onClick={toggle}
          title={!open ? t('nav.langSwitch') : undefined}
          style={{ fontSize: open ? '.78em' : undefined }}
        >
          <Globe size={15} />
          {open && <span>{t('nav.langSwitch')}</span>}
        </button>
        <button
          className="sb-logout"
          onClick={() => {
            try { localStorage.removeItem('nav-group-open') } catch {}
            onLogout()
          }}
          title={!open ? t('nav.logout') : undefined}
        >
          <LogOut size={15} />
          {open && <span>{t('nav.logout')}</span>}
        </button>
      </div>

    </aside>
  )
}
