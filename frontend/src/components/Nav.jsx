import { useState, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import {
  LayoutDashboard, AlertTriangle, FileText,
  RefreshCw, ClipboardList, Settings, User, Globe, LogOut, Lock,
  Sun, Moon, ChevronLeft, ChevronRight, ChevronDown, ChevronUp, Server, Activity, ShieldAlert, BarChart3, Bell, BookOpen,
  Wifi, Network, Search, TrendingDown, Database, UserCheck, ShieldCheck, CalendarDays,
} from 'lucide-react'
import CertMonitorLogo from './ui/CertMonitorLogo.jsx'

export default function Nav({ activeTab, onTabChange, username, teamName, systemRole, onLogout, onChangePassword }) {
  const t = useT()
  const { toggle } = useLanguage()
  const { theme, toggle: toggleTheme } = useTheme()
  const isAdmin     = systemRole === 'ADMIN'
  const isTeamAdmin = systemRole === 'TEAM_ADMIN'
  const isAudit     = systemRole === 'AUDIT'

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
        { id: 'forecast',      Icon: TrendingDown, labelKey: 'nav.forecast',      show: true },
        { id: 'renewal',       Icon: RefreshCw,    labelKey: 'nav.renewal',       show: true },
        { id: 'renewal-guide', Icon: BookOpen,     labelKey: 'nav.renewalGuide',  show: true },
      ],
    },
    {
      labelKey: 'nav.groupMonitoring',
      tabs: [
        { id: 'health', Icon: Activity, labelKey: 'nav.health', show: true },
        { id: 'uptime', Icon: Wifi,     labelKey: 'nav.uptime', show: true },
        { id: 'port',   Icon: Network,  labelKey: 'nav.port',   show: true },
        { id: 'dns',    Icon: Search,   labelKey: 'nav.dns',    show: true },
      ],
    },
    {
      labelKey: 'nav.groupAlerts',
      tabs: [
        { id: 'warnings',     Icon: AlertTriangle, labelKey: 'nav.warnings',     show: true },
        { id: 'alerthistory', Icon: Bell,          labelKey: 'nav.alertHistory', show: true },
      ],
    },
    {
      labelKey: 'nav.groupReports',
      tabs: [
        { id: 'stats',         Icon: BarChart3,    labelKey: 'nav.stats',         show: true },
        { id: 'weakalgo',      Icon: ShieldAlert,  labelKey: 'nav.weakAlgo',      show: true },
        { id: 'weeklyreports', Icon: CalendarDays, labelKey: 'nav.weeklyReports', show: true },
      ],
    },
    {
      labelKey: 'nav.groupLogs',
      tabs: [
        { id: 'activity',   Icon: ClipboardList, labelKey: 'nav.activity',   show: true               },
        { id: 'myactivity', Icon: UserCheck,     labelKey: 'nav.myActivity', show: true               },
        { id: 'system',     Icon: Server,        labelKey: 'nav.system',     show: isAdmin || isAudit },
      ],
    },
    {
      labelKey: 'nav.groupAdmin',
      tabs: [
        { id: 'admin',         Icon: Settings,    labelKey: 'nav.admin',         show: true    },
        { id: 'permissions',   Icon: ShieldCheck, labelKey: 'nav.permissions',   show: isAdmin },
        { id: 'sqlplayground', Icon: Database,    labelKey: 'nav.sqlPlayground', show: isAdmin },
      ],
    },
    {
      labelKey: null,
      tabs: [
        { id: 'help', Icon: BookOpen, labelKey: 'nav.help', show: true },
      ],
    },
  ]

  const [open, setOpen] = useState(() =>
    localStorage.getItem('sidebar-open') !== 'false'
  )

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
    localStorage.setItem('sidebar-open', String(next))
  }

  return (
    <aside className={`sb${open ? '' : ' sb-closed'}`}>

      {/* ── Logo area ── */}
      <div className="sb-head">
        <div className="sb-brand">
          <span className="sb-logo"><CertMonitorLogo variant="icon" size={26} /></span>
          {open && (
            <div className="sb-brand-text">
              <span className="sb-brand-name">CertMonitor</span>
              <span className="sb-brand-sub">ENTERPRISE</span>
              <span className="sb-brand-version">v{__APP_VERSION__}</span>
            </div>
          )}
        </div>
        <button className="sb-toggle" onClick={toggleSidebar} title={open ? t('nav.collapse') : t('nav.expand')}>
          {open ? <ChevronLeft size={14} /> : <ChevronRight size={14} />}
        </button>
      </div>

      {/* ── Nav items ── */}
      <nav className="sb-nav">
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
            onClick={() => setUserMenuOpen(v => !v)}
            title={!open ? t('nav.userSettings') : undefined}
          >
            <User size={14} />
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
            {username === 'admin' && (
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
          </div>,
          document.body
        )}
        <button
          className="sb-logout"
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
