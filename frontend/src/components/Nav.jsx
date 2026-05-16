import { useState } from 'react'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useTheme } from '../i18n/theme.jsx'
import {
  ShieldCheck, LayoutDashboard, AlertTriangle, FileText,
  RefreshCw, ClipboardList, Settings, User, Globe, LogOut,
  Sun, Moon, ChevronLeft, ChevronRight,
} from 'lucide-react'

export default function Nav({ activeTab, onTabChange, username, onLogout }) {
  const t = useT()
  const { toggle } = useLanguage()
  const { theme, toggle: toggleTheme } = useTheme()

  const TABS = [
    { id: 'dashboard', Icon: LayoutDashboard, labelKey: 'nav.dashboard' },
    { id: 'warnings',  Icon: AlertTriangle,   labelKey: 'nav.warnings' },
    { id: 'all',       Icon: FileText,        labelKey: 'nav.all' },
    { id: 'renewal',   Icon: RefreshCw,       labelKey: 'nav.renewal' },
    { id: 'activity',  Icon: ClipboardList,   labelKey: 'nav.activity' },
    { id: 'admin',     Icon: Settings,        labelKey: 'nav.admin' },
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
          <span className="sb-logo"><ShieldCheck size={22} color="#3b82f6" /></span>
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
        {TABS.map(({ id, Icon, labelKey }) => (
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
      </nav>

      {/* ── Footer: user, theme toggle, lang toggle, logout ── */}
      <div className="sb-foot">
        {open && (
          <div className="sb-user">
            <User size={14} />
            <span className="sb-user-name">{username}</span>
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
