import { useState, useEffect } from 'react'
import { api } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { ShieldAlert, ShieldCheck, Lock, Globe, Bell, BarChart3, RefreshCw } from 'lucide-react'
import CertMonitorLogo from '../components/ui/CertMonitorLogo.jsx'

const STORAGE_KEY = 'cert-monitor-remembered-user'

export default function Login({ onLogin }) {
  const t = useT()
  const { lang, toggle: toggleLang } = useLanguage()
  const saved = localStorage.getItem(STORAGE_KEY)
  const [username, setUsername] = useState(saved || '')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(!!saved)
  const [error, setError]     = useState('')
  const [loading, setLoading] = useState(false)
  const [showPass, setShowPass] = useState(false)
  const [lockout, setLockout]           = useState(0)    // seconds remaining
  const [permanentLock, setPermanentLock] = useState(false) // admin must unlock

  useEffect(() => {
    if (lockout <= 0) return
    const id = setTimeout(() => setLockout(s => Math.max(0, s - 1)), 1000)
    return () => clearTimeout(id)
  }, [lockout])

  const FEATURES = [
    { Icon: ShieldCheck, key: 'login.feat1' },
    { Icon: Bell,        key: 'login.feat2' },
    { Icon: Globe,       key: 'login.feat3' },
    { Icon: RefreshCw,   key: 'login.feat4' },
    { Icon: BarChart3,   key: 'login.feat5' },
  ]

  useEffect(() => {
    if (saved) {
      document.getElementById('lp-pass')?.focus()
    }
  }, [])

  async function handleSubmit(e) {
    e.preventDefault()
    if (lockout > 0) return
    setError('')
    setLoading(true)
    try {
      const data = await api.login(username, password, rememberMe)
      if (data.success) {
        if (rememberMe) {
          localStorage.setItem(STORAGE_KEY, username)
        } else {
          localStorage.removeItem(STORAGE_KEY)
        }
        onLogin(data)
      } else if (data.locked) {
        setPermanentLock(true)
        setLockout(0)
        setError('')
      } else if (data.wait_seconds) {
        setLockout(data.wait_seconds)
        setError('')
      } else if (data.error_code === 'TEMP_PASSWORD_EXPIRED') {
        setError(t('auth.tempPasswordExpired'))
      } else {
        setError(data.error || t('login.failed'))
      }
    } catch {
      setError(t('login.serverError'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="lp-root">

      {/* ── Sol panel: marka & özellikler ── */}
      <div className="lp-left">
        <div className="lp-left-inner">
          <div className="lp-brand">
            <div className="lp-brand-logo-row">
              <CertMonitorLogo variant="login" className="lp-logo-svg" />
              <span className="lp-blink-dot" title="System operational" />
            </div>
            <div className="lp-brand-headline">
              <span className="lp-meet">{t('login.meet')}</span>
              <h1 className="lp-brand-name">CertMonitor</h1>
            </div>
            <p className="lp-brand-tagline">{t('login.leftTagline')}</p>
          </div>

          <ul className="lp-features">
            {FEATURES.map(({ Icon, key }) => (
              <li key={key} className="lp-feature-item">
                <span className="lp-feature-icon"><Icon size={16} /></span>
                <span>{t(key)}</span>
              </li>
            ))}
          </ul>

          <div className="lp-social-proof">
            <span className="lp-social-stat"><strong>500+</strong> {t('login.domainsMonitored')}</span>
            <span className="lp-social-divider">·</span>
            <span className="lp-social-stat"><strong>99.9%</strong> {t('login.uptime')}</span>
          </div>

          <div className="lp-left-footer">
            <div className="lp-footer-info">
              <span className="lp-enterprise-badge">ENTERPRISE</span>
              <span>v{__APP_VERSION__} &nbsp;·&nbsp; &copy; {new Date().getFullYear()} CertMonitor</span>
            </div>
            <button type="button" className="lp-lang-btn" onClick={toggleLang}>
              <Globe size={13} />
              {lang === 'tr' ? 'English' : 'Türkçe'}
            </button>
          </div>
        </div>

        <div className="lp-left-bottom">
          <p className="lp-left-bottom-text">{t('login.footerTagline')}</p>
          <div className="lp-left-bottom-features">
            <span>🔒 {t('login.ftSSL')}</span>
            <span>🌐 {t('login.ftPorts')}</span>
            <span>⚡ {t('login.ftAlerts')}</span>
            <span>🔄 {t('login.ftCron')}</span>
          </div>
        </div>

        <div className="lp-deco lp-deco-1" />
        <div className="lp-deco lp-deco-2" />
        <div className="lp-deco lp-deco-3" />
      </div>

      {/* ── Sağ panel: giriş formu ── */}
      <div className="lp-right">
        <div className="lp-form-wrap">

          {/* Üst açıklama */}
          <div className="lp-intro">
            <div className="lp-intro-badge">
              <Lock size={13} />
              <span>{t('login.secureBadge')}</span>
            </div>
            <h2 className="lp-intro-title">{t('login.heading')}</h2>
            <p className="lp-intro-desc">{t('login.desc')}</p>
          </div>

          {/* Kalıcı kilit ekranı */}
          {permanentLock ? (
            <div className="lp-blocked lp-blocked--permanent" role="alert">
              <div className="lp-blocked-icon lp-blocked-icon--permanent">
                <ShieldAlert size={36} />
              </div>
              <h3 className="lp-blocked-title lp-blocked-title--permanent">{t('login.permLockedTitle')}</h3>
              <p className="lp-blocked-desc">{t('login.permLockedDesc')}</p>
              <ul className="lp-blocked-reasons">
                <li>{t('login.permLockedReason1')}</li>
                <li>{t('login.permLockedReason2')}</li>
              </ul>
              <div className="lp-blocked-perm-badge">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="18" height="18"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                {t('login.permLockedBadge')}
              </div>
              <p className="lp-blocked-help">{t('login.permLockedHelp')}</p>
            </div>

          ) : lockout > 0 ? (
            <div className="lp-blocked" role="alert" aria-live="polite">
              <div className="lp-blocked-icon">
                <ShieldAlert size={36} />
              </div>
              <h3 className="lp-blocked-title">{t('login.blockedTitle')}</h3>
              <p className="lp-blocked-desc">{t('login.blockedDesc')}</p>
              <ul className="lp-blocked-reasons">
                <li>{t('login.blockedReason1')}</li>
                <li>{t('login.blockedReason2')}</li>
                <li>{t('login.blockedReason3')}</li>
              </ul>
              <div className="lp-blocked-timer">
                <div className="lp-blocked-count">{lockout}</div>
                <div className="lp-blocked-unit">{t('login.blockedUnit')}</div>
              </div>
              <p className="lp-blocked-hint">{t('login.blockedHint')}</p>
            </div>
          ) : (
            /* Normal giriş formu */
            <form onSubmit={handleSubmit} className="lp-form">
              <div className="lp-field">
                <label className="lp-label" htmlFor="lp-user">{t('login.username')}</label>
                <input
                  id="lp-user"
                  className="lp-input"
                  type="text"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  placeholder={t('login.userPlaceholder')}
                  required
                  autoFocus
                  autoComplete="username"
                />
              </div>

              <div className="lp-field">
                <label className="lp-label" htmlFor="lp-pass">{t('login.password')}</label>
                <div className="lp-pass-wrap">
                  <input
                    id="lp-pass"
                    className="lp-input"
                    type={showPass ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    placeholder={t('login.passPlaceholder')}
                    required
                    autoComplete="current-password"
                  />
                  <button
                    type="button"
                    className="lp-eye"
                    onClick={() => setShowPass(p => !p)}
                    tabIndex={-1}
                    aria-label={showPass ? t('login.hidePass') : t('login.showPass')}
                  >
                    {showPass
                      ? <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" width="18" height="18"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                      : <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" width="18" height="18"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                    }
                  </button>
                </div>
              </div>

              <label className="lp-remember">
                <input
                  type="checkbox"
                  checked={rememberMe}
                  onChange={e => setRememberMe(e.target.checked)}
                />
                <span>{t('login.rememberMe')}</span>
              </label>

              {error && (
                <div className="lp-error" role="alert">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                  {error}
                </div>
              )}

              <button type="submit" className="lp-btn" disabled={loading}>
                {loading
                  ? <><span className="lp-spinner" /> {t('login.loading')}</>
                  : <><Lock size={16} /> {t('login.submit')}</>
                }
              </button>

              <p className="lp-ldap-hint">{t('login.ldapHint')}</p>
            </form>
          )}

          <p className="lp-help">{t('login.help')}</p>
        </div>
      </div>
    </div>
  )
}
