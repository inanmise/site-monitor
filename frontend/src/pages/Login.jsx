import { useState, useEffect } from 'react'
import { api } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { ShieldCheck, Lock, Globe, Bell, BarChart3, RefreshCw } from 'lucide-react'

const STORAGE_KEY = 'cert-monitor-remembered-user'

function CertMonitorLogo() {
  return (
    <svg viewBox="0 0 160 160" fill="none" xmlns="http://www.w3.org/2000/svg" className="lp-logo-svg">
      <circle cx="80" cy="80" r="72" stroke="rgba(99,179,237,0.18)" strokeWidth="2" />
      <circle cx="80" cy="80" r="58" stroke="rgba(99,179,237,0.12)" strokeWidth="1.5" />
      <path
        d="M80 18 L122 36 L122 82 C122 112 104 130 80 142 C56 130 38 112 38 82 L38 36 Z"
        fill="url(#shieldGrad)"
        stroke="rgba(147,210,255,0.35)"
        strokeWidth="1.5"
      />
      <line x1="56" y1="72" x2="62" y2="72" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="62" y1="72" x2="62" y2="64" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="62" y1="64" x2="70" y2="64" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="98" y1="72" x2="94" y2="72" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="94" y1="72" x2="94" y2="64" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="94" y1="64" x2="90" y2="64" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="56" y1="96" x2="60" y2="96" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="60" y1="96" x2="60" y2="104" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="98" y1="96" x2="96" y2="96" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <line x1="96" y1="96" x2="96" y2="104" stroke="rgba(147,210,255,0.5)" strokeWidth="1.5" strokeLinecap="round"/>
      <rect x="62" y="80" width="36" height="28" rx="5" fill="white" fillOpacity="0.15" stroke="white" strokeOpacity="0.6" strokeWidth="1.5"/>
      <path
        d="M69 80 L69 72 C69 63 91 63 91 72 L91 80"
        stroke="white"
        strokeWidth="3.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
        strokeOpacity="0.85"
      />
      <circle cx="80" cy="91" r="4.5" fill="white" fillOpacity="0.9"/>
      <rect x="78" y="91" width="4" height="7" rx="1.5" fill="white" fillOpacity="0.9"/>
      <circle cx="110" cy="48" r="12" fill="#10b981" stroke="#0f172a" strokeWidth="2"/>
      <path d="M104.5 48 L108 52 L115.5 44" stroke="white" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
      <circle cx="56" cy="72" r="2" fill="#63b3ed" fillOpacity="0.7"/>
      <circle cx="70" cy="64" r="2" fill="#63b3ed" fillOpacity="0.7"/>
      <circle cx="90" cy="64" r="2" fill="#63b3ed" fillOpacity="0.7"/>
      <circle cx="60" cy="104" r="2" fill="#63b3ed" fillOpacity="0.7"/>
      <circle cx="96" cy="104" r="2" fill="#63b3ed" fillOpacity="0.7"/>
      <defs>
        <linearGradient id="shieldGrad" x1="38" y1="18" x2="122" y2="142" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#2563eb"/>
          <stop offset="100%" stopColor="#1e40af"/>
        </linearGradient>
      </defs>
    </svg>
  )
}

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
  const [lockout, setLockout] = useState(0)   // seconds remaining in rate-limit block

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
      } else if (data.wait_seconds) {
        setLockout(data.wait_seconds)
        setError('')
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
            <CertMonitorLogo />
            <h1 className="lp-brand-name">CertMonitor</h1>
            <p className="lp-brand-tagline">{t('login.tagline')}</p>
          </div>

          <ul className="lp-features">
            {FEATURES.map(({ Icon, key }) => (
              <li key={key} className="lp-feature-item">
                <span className="lp-feature-icon"><Icon size={16} /></span>
                <span>{t(key)}</span>
              </li>
            ))}
          </ul>

          <div className="lp-left-footer">
            <span>v{__APP_VERSION__} &nbsp;·&nbsp; &copy; {new Date().getFullYear()} CertMonitor</span>
            <button type="button" className="lp-lang-btn" onClick={toggleLang}>
              <Globe size={13} />
              {lang === 'tr' ? 'English' : 'Türkçe'}
            </button>
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

          {/* Form */}
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

            {lockout > 0 && (
              <div className="lp-lockout" role="alert">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>
                {t('login.rateLimited', lockout)}
              </div>
            )}

            {error && lockout === 0 && (
              <div className="lp-error" role="alert">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                {error}
              </div>
            )}

            <button type="submit" className="lp-btn" disabled={loading || lockout > 0}>
              {loading
                ? <><span className="lp-spinner" /> {t('login.loading')}</>
                : lockout > 0
                  ? <><span className="lp-spinner lp-spinner--wait" /> {t('login.waitBtn', lockout)}</>
                  : <><Lock size={16} /> {t('login.submit')}</>
              }
            </button>
          </form>

          <p className="lp-help">{t('login.help')}</p>
        </div>
      </div>
    </div>
  )
}
