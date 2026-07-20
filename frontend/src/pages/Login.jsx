import { useState, useEffect } from 'react'
import { api } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { ShieldAlert, ShieldCheck, Lock, Globe, Activity, Radio, Network, Server, Search, Gauge, Bell, AlertTriangle, FileText, Wrench, X } from 'lucide-react'

const STORAGE_KEY = 'cert-monitor-remembered-user'

export default function Login({ onLogin, sessionExpired = false }) {
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
  const [confirmActiveSession, setConfirmActiveSession] = useState(false) // başka yerde aktif oturum onayı

  useEffect(() => {
    if (lockout <= 0) return
    const id = setTimeout(() => setLockout(s => Math.max(0, s - 1)), 1000)
    return () => clearTimeout(id)
  }, [lockout])

  // Bento kutuları — izleme türleri (flagship: Sertifika geniş + yeşil vurgu). tint = kategori ikon rengi.
  const CAPS = [
    { Icon: ShieldCheck, key: 'login.capCert',    desc: 'login.capCertDesc',    tint: '#4ade80', flag: true, wide: true },
    { Icon: Server,      key: 'login.capDns',     desc: 'login.capDnsDesc',     tint: '#60a5fa' },
    { Icon: Activity,    key: 'login.capHttp',    desc: 'login.capHttpDesc',    tint: '#f59e0b' },
    { Icon: Network,     key: 'login.capPort',    desc: 'login.capPortDesc',    tint: '#a78bfa' },
    { Icon: Radio,       key: 'login.capPing',    desc: 'login.capPingDesc',    tint: '#22d3ee' },
    { Icon: Globe,       key: 'login.capDomain',  desc: 'login.capDomainDesc',  tint: '#34d399' },
    { Icon: Search,      key: 'login.capKeyword', desc: 'login.capKeywordDesc', tint: '#f472b6' },
    { Icon: Gauge,       key: 'login.capUptime',  desc: 'login.capUptimeDesc',  tint: '#818cf8' },
  ]
  // Operasyon grubu — izleme dışı yetenekler (kompakt çipler).
  const OPS = [
    { Icon: Bell,          key: 'login.opsAlarm' },
    { Icon: AlertTriangle, key: 'login.opsIncident' },
    { Icon: FileText,      key: 'login.opsReport' },
    { Icon: Wrench,        key: 'login.opsMaintenance' },
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
      } else if (data.error_code === 'ACTIVE_SESSION_EXISTS') {
        // Başka yerde aktif oturum var — kullanıcıya onay sor (diğerini düşürmeden).
        setConfirmActiveSession(true)
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

  // Onay sonrası: force_login=true ile tekrar dene → backend diğer oturumu düşürüp girişi tamamlar.
  async function confirmAndLogin() {
    setLoading(true)
    setError('')
    try {
      const data = await api.login(username, password, rememberMe, true)
      if (data.success) {
        if (rememberMe) {
          localStorage.setItem(STORAGE_KEY, username)
        } else {
          localStorage.removeItem(STORAGE_KEY)
        }
        onLogin(data)
      } else {
        setConfirmActiveSession(false)
        setError(data.error || t('login.failed'))
      }
    } catch {
      setConfirmActiveSession(false)
      setError(t('login.serverError'))
    } finally {
      setLoading(false)
    }
  }

  function cancelActiveSession() {
    setConfirmActiveSession(false)
    setPassword('')
  }

  return (
    <div className="lp-root">

      {/* ── Sol panel: executive marka & mesaj ── */}
      <div className="lp-left">
        {/* Dekoratif konsantrik halkalar — içeriğin arkasında */}
        <svg className="lp-bg" viewBox="0 0 520 900" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
          <g>
            <circle cx="470" cy="150" r="70" />
            <circle cx="470" cy="150" r="150" />
            <circle cx="470" cy="150" r="240" />
            <circle cx="470" cy="150" r="340" />
            <circle cx="470" cy="150" r="450" />
            <circle cx="470" cy="150" r="570" />
          </g>
        </svg>
        <span className="lp-accent-dot" aria-hidden="true" />

        <div className="lp-left-inner">
          {/* Üst: wordmark + ENTERPRISE rozeti */}
          <div className="lp-top">
            <span className="lp-wordmark">CertMonitor</span>
            <span className="lp-badge">ENTERPRISE</span>
          </div>

          {/* Orta: mesaj + izleme yetenekleri (bento) + operasyon grubu (çipler) */}
          <div className="lp-center">
            <h1 className="lp-headline">{t('login.leftHeadline')}</h1>
            <div className="lp-caps-title">{t('login.capsTitle')}</div>
            <div className="lp-bento">
              {CAPS.map(({ Icon, key, desc, tint, flag, wide }, i) => (
                <div
                  key={key}
                  className={`lp-tile${flag ? ' lp-tile--flag' : ''}${wide ? ' lp-tile--wide' : ''}`}
                  style={{ '--tile-tint': tint, animationDelay: `${i * 55}ms` }}
                >
                  {flag && <span className="lp-tile-live" />}
                  <Icon size={18} className="lp-tile-icon" />
                  <span className="lp-tile-label">{t(key)}</span>
                  <span className="lp-tile-desc">{t(desc)}</span>
                </div>
              ))}
            </div>
            <div className="lp-caps-title">{t('login.opsTitle')}</div>
            <div className="lp-ops">
              {OPS.map(({ Icon, key }, i) => (
                <span key={key} className="lp-chip" style={{ animationDelay: `${(CAPS.length + i) * 55}ms` }}>
                  <Icon size={14} />
                  {t(key)}
                </span>
              ))}
            </div>
          </div>

          {/* Alt: hero istatistikler + footer */}
          <div className="lp-bottom">
            <div className="lp-stats">
              <div className="lp-stat">
                <span className="lp-stat-num">500+</span>
                <span className="lp-stat-lbl">{t('login.domainsMonitored')}</span>
              </div>
              <span className="lp-stat-sep" />
              <div className="lp-stat">
                <span className="lp-stat-num">99.9%</span>
                <span className="lp-stat-lbl">{t('login.uptime')}</span>
              </div>
            </div>
            <div className="lp-footer">
              <span className="lp-footer-meta">v{__APP_VERSION__} &nbsp;·&nbsp; &copy; {new Date().getFullYear()} CertMonitor</span>
              <button type="button" className="lp-lang-btn" onClick={toggleLang}>
                <Globe size={13} />
                {lang === 'tr' ? 'English' : 'Türkçe'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* ── Sağ panel: giriş formu ── */}
      <div className="lp-right">
        <div className="lp-form-wrap">

          {/* Oturum düştüğünde (401 → hard reload) gösterilen bilgi bildirimi (AUTH-1) */}
          {sessionExpired && (
            <div className="lp-notice" role="status" aria-live="polite">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              {t('login.sessionExpired')}
            </div>
          )}

          {/* Üst açıklama */}
          <div className="lp-intro">
            <div className="lp-intro-badge">
              <Lock size={13} />
              <span>{t('login.secureBadge')}</span>
            </div>
            <h2 className="lp-intro-title">{t('login.heading')}</h2>
            <p className="lp-intro-desc">{t('login.desc')}</p>
          </div>

          {/* Başka yerde aktif oturum — onay ekranı */}
          {confirmActiveSession ? (
            <div className="lp-blocked" role="alertdialog" aria-live="polite">
              <div className="lp-blocked-icon">
                <ShieldAlert size={36} />
              </div>
              <h3 className="lp-blocked-title">{t('login.activeSessionTitle')}</h3>
              <p className="lp-blocked-desc">{t('login.activeSessionDesc')}</p>
              <button className="lp-btn" onClick={confirmAndLogin} disabled={loading}>
                {loading
                  ? <><span className="lp-spinner" /> {t('login.loading')}</>
                  : <><Lock size={16} /> {t('login.activeSessionConfirm')}</>
                }
              </button>
              <button type="button" className="lp-btn lp-btn--ghost" onClick={cancelActiveSession} disabled={loading}>
                <X size={16} /> {t('login.activeSessionCancel')}
              </button>
            </div>

          ) : permanentLock ? (
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
