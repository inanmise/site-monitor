import { useState } from 'react'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import {
  LayoutDashboard, AlertTriangle, FileText,
  RefreshCw, ClipboardList, Settings, User, Globe, LogOut,
  Sun, Moon, ChevronLeft, ChevronRight, Server, Activity, ShieldAlert, BarChart3, Bell, BookOpen,
  Wifi, Radio, Network, Search,
} from 'lucide-react'
import CertMonitorLogo from './ui/CertMonitorLogo.jsx'

export default function Nav({ activeTab, onTabChange, username, teamName, systemRole, onLogout }) {
  const t = useT()
  const { toggle } = useLanguage()
  const { theme, toggle: toggleTheme } = useTheme()
  const isAdmin    = systemRole === 'ADMIN'
  const isAudit    = systemRole === 'AUDIT'

  const GROUPS = [
    {
      labelKey: null,
      tabs: [
        { id: 'dashboard', Icon: LayoutDashboard, labelKey: 'nav.dashboard', show: true },
        { id: 'stats',     Icon: BarChart3,       labelKey: 'nav.stats',     show: true },
        { id: 'warnings',  Icon: AlertTriangle,   labelKey: 'nav.warnings',  show: true },
        { id: 'all',       Icon: FileText,        labelKey: 'nav.all',       show: true },
        { id: 'renewal',   Icon: RefreshCw,       labelKey: 'nav.renewal',   show: true },
        { id: 'domains',   Icon: Globe,           labelKey: 'nav.domains',   show: true },
      ],
    },
    {
      labelKey: 'nav.groupMonitoring',
      tabs: [
        { id: 'uptime', Icon: Wifi,    labelKey: 'nav.uptime', show: true },
        { id: 'ping',   Icon: Radio,   labelKey: 'nav.ping',   show: true },
        { id: 'port',   Icon: Network, labelKey: 'nav.port',   show: true },
        { id: 'dns',    Icon: Search,  labelKey: 'nav.dns',    show: true },
      ],
    },
    {
      labelKey: 'nav.groupLogs',
      tabs: [
        { id: 'activity',     Icon: ClipboardList, labelKey: 'nav.activity',     show: true    },
        { id: 'alerthistory', Icon: Bell,          labelKey: 'nav.alertHistory', show: isAdmin },
      ],
    },
    {
      labelKey: 'nav.groupAdmin',
      tabs: [
        { id: 'admin',   Icon: Settings,    labelKey: 'nav.admin',   show: true               },
        { id: 'system',  Icon: Server,      labelKey: 'nav.system',  show: isAdmin || isAudit },
        { id: 'weakalgo',Icon: ShieldAlert, labelKey: 'nav.weakAlgo',show: isAdmin || isAudit },
        { id: 'health',  Icon: Activity,    labelKey: 'nav.health',  show: isAdmin            },
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
              <span className="sb-brand-sub">Enterprise</span>
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
          return (
            <div key={gi} className="sb-group">
              {group.labelKey && open && (
                <div className="sb-group-label">{t(group.labelKey)}</div>
              )}
              {group.labelKey && !open && gi > 0 && (
                <div className="sb-group-rule" />
              )}
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
          )
        })}
      </nav>

      {/* ── Footer: user, theme toggle, lang toggle, logout ── */}
      <div className="sb-foot">
        {open && (
          <div className="sb-user">
            <User size={14} />
            <div className="sb-user-info">
              <span className="sb-user-name">{username}</span>
              {teamName && <span className="sb-team-name">{teamName}</span>}
              {systemRole === 'ADMIN' && <span className="sb-role-badge">ADMIN</span>}
            </div>
          </div>
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
          onClick={onLogout}
          title={!open ? t('nav.logout') : undefined}
        >
          <LogOut size={15} />
          {open && <span>{t('nav.logout')}</span>}
        </button>
      </div>

    </aside>
  )
}
