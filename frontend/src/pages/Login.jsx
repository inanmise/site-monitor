import { useState, useEffect, useRef } from 'react'
// "Sisli Ege" sağ panel tipografisi — self-host (@fontsource, CDN yok); yalnız login yükler.
// Ağırlık CSS'leri woff2 + latin/latin-ext subset'lerini unicode-range ile içerir (TR glifleri dahil).
import '@fontsource/cinzel/400.css'
import '@fontsource/cinzel/500.css'
import '@fontsource/josefin-sans/300.css'
import '@fontsource/josefin-sans/400.css'
import '@fontsource/josefin-sans/600.css'
import { api } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import BrandLogo from '../components/BrandLogo.jsx'
import { downscaleImage } from '../utils/imageDownscale.js'
import { ShieldAlert, ShieldCheck, Lock, Globe, Activity, Radio, Network, Server, Search, Gauge, Bell, BellRing, AlertTriangle, FileText, BarChart3, TrendingUp, Wrench, ScanSearch, FlaskConical, X } from 'lucide-react'

// App.jsx logout temizliği de bu anahtarı kullanır — tek kaynak buradan export edilir.
export const REMEMBER_KEY = 'site-monitor-remembered-user'
const STORAGE_KEY = REMEMBER_KEY

export default function Login({ onLogin, sessionExpired = false }) {
  const t = useT()
  const { lang, toggle: toggleLang } = useLanguage()
  // Branding (beyaz etiket): dolu değer varsa onu, boşsa i18n varsayılanını kullan (auth ÖNCESİ public).
  const { get: brand, branding } = useBranding()
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
  const [stats, setStats] = useState(null)   // hero istatistikleri — gerçek veriden (public endpoint)
  // "Sorun bildir" pop-up'ı — sistem yöneticisine mail (public endpoint, IP rate-limit'li).
  // Kullanıcı adı ZORUNLU; hata mesajı + ekran görüntüsü (png/jpeg, küçültülüp inline gömülür) opsiyonel.
  const [helpOpen, setHelpOpen] = useState(false)
  const [helpUser, setHelpUser] = useState('')
  const [helpEmail, setHelpEmail] = useState('')
  const [helpErrorText, setHelpErrorText] = useState('')
  const [helpMsg, setHelpMsg] = useState('')
  const [helpShots, setHelpShots] = useState([])    // data-URL dizisi (≤5) — önizleme + payload
  const [helpSending, setHelpSending] = useState(false)
  const [helpSent, setHelpSent] = useState(false)
  const [helpRef, setHelpRef] = useState('')        // başarıda dönen referans no (LIR-...)
  const [helpErr, setHelpErr] = useState('')        // kullanıcıya gösterilen NET sebep
  const [helpErrDetail, setHelpErrDetail] = useState('')   // "Detay gör" ile açılan teknik hata
  const [helpErrShowDetail, setHelpErrShowDetail] = useState(false)
  const shotRef = useRef(null)

  const MAX_SHOTS = 5
  const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

  function openHelp() {
    setHelpUser(username.trim())
    setHelpEmail(''); setHelpErrorText(''); setHelpMsg(''); setHelpShots([]); setHelpErr(''); setHelpSent(false); setHelpRef('')
    setHelpOpen(true)
  }
  function closeHelp() { setHelpOpen(false) }

  useEffect(() => {
    if (!helpOpen) return
    const onKey = (e) => { if (e.key === 'Escape') closeHelp() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [helpOpen])

  async function onShotChosen(e) {
    const files = Array.from(e.target.files || [])
    e.target.value = ''
    if (!files.length) return
    setHelpErr('')
    const room = MAX_SHOTS - helpShots.length
    if (room <= 0) { setHelpErr(t('login.helpShotMaxErr').replace('{n}', String(MAX_SHOTS))); return }
    const picked = files.slice(0, room)
    if (files.length > room) setHelpErr(t('login.helpShotMaxErr').replace('{n}', String(MAX_SHOTS)))
    const added = []
    for (const file of picked) {
      if (!['image/png', 'image/jpeg'].includes(file.type)) { setHelpErr(t('login.helpShotTypeErr')); continue }
      const processed = await downscaleImage(file, { maxDim: 1600, targetBytes: 400 * 1024 })
      if (processed.size > 1024 * 1024) { setHelpErr(t('login.helpShotSizeErr')); continue }
      const dataUrl = await new Promise((res) => {
        const reader = new FileReader()
        reader.onload = () => res(String(reader.result))
        reader.readAsDataURL(processed)
      })
      added.push(dataUrl)
    }
    if (added.length) setHelpShots((prev) => [...prev, ...added].slice(0, MAX_SHOTS))
  }

  function removeShot(idx) {
    setHelpShots((prev) => prev.filter((_, i) => i !== idx))
    setHelpErr('')
  }

  // Sonuçtan kullanıcıya NET sebep üret (ağ / oran / kapalı / sunucu / validasyon).
  function helpReason(r) {
    if (!r) return t('login.helpError')
    if (r.networkError) return t('login.helpErrNetwork')
    if (r.status === 429) return t('login.helpErrRate')
    if (r.status === 404) return t('login.helpErrDisabled')
    if (r.status >= 500) return t('login.helpErrServer')
    return r.error || t('login.helpError')   // 400 validasyon → sunucunun döndüğü mesaj
  }
  // "Detay gör" ile açılan teknik satır: NETWORK / HTTP kodu / sunucu mesajı.
  function helpDetail(r) {
    if (!r) return ''
    const parts = []
    if (r.networkError) parts.push('NETWORK')
    if (r.status) parts.push('HTTP ' + r.status)
    if (r.error) parts.push(r.error)
    return parts.join(' · ')
  }

  async function sendHelp() {
    if (!helpUser.trim() || !helpMsg.trim() || helpSending) return
    if (!EMAIL_RE.test(helpEmail.trim())) { setHelpErr(t('login.helpEmailInvalid')); setHelpErrDetail(''); return }
    setHelpSending(true); setHelpErr(''); setHelpErrDetail(''); setHelpErrShowDetail(false)
    const r = await api.sendLoginHelp({
      username: helpUser.trim(),
      email: helpEmail.trim(),
      errorText: helpErrorText.trim(),
      message: helpMsg.trim(),
      images: helpShots,
    })
    if (r?.success) { setHelpRef(r.reference || ''); setHelpSent(true) }
    else { setHelpErr(helpReason(r)); setHelpErrDetail(helpDetail(r)) }
    setHelpSending(false)
  }

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const r = await api.getPublicStats?.()
        if (alive && r?.success) setStats(r.data)
      } catch { /* istatistiksiz de login çalışır */ }
    })()
    return () => { alive = false }
  }, [])

  useEffect(() => {
    if (lockout <= 0) return
    const id = setTimeout(() => setLockout(s => Math.max(0, s - 1)), 1000)
    return () => clearTimeout(id)
  }, [lockout])

  // Üç sütunlu değer önermesi — İzle / Uyar / Raporla. Her sütun: başlık + tek cümle + kompakt çipler.
  // tint = sütun/çip vurgu rengi; flagship Sertifika çipi yeşil canlı vurgusunu korur.
  const PILLARS = [
    {
      Icon: Activity, key: 'login.pillarMonitor', desc: 'login.pillarMonitorDesc', tint: '#4ade80',
      items: [
        { Icon: ShieldCheck, key: 'login.capCert',    desc: 'login.capCertDesc',    tint: '#4ade80', flag: true },
        { Icon: Server,      key: 'login.capDns',     desc: 'login.capDnsDesc',     tint: '#60a5fa' },
        { Icon: Activity,    key: 'login.capHttp',    desc: 'login.capHttpDesc',    tint: '#f59e0b' },
        { Icon: Network,     key: 'login.capPort',    desc: 'login.capPortDesc',    tint: '#a78bfa' },
        { Icon: Radio,       key: 'login.capPing',    desc: 'login.capPingDesc',    tint: '#22d3ee' },
        { Icon: Globe,       key: 'login.capDomain',  desc: 'login.capDomainDesc',  tint: '#34d399' },
        { Icon: Search,      key: 'login.capKeyword', desc: 'login.capKeywordDesc', tint: '#f472b6' },
        // Sayfa Bütünlüğü ve Sentetik (k6) burada YOKTU: metin "10 izleme türü" derken çipler
        // yalnız sekizini gösteriyordu. İkisi de tam birer izleme türü (kendi sayfası, alarmları
        // ve haftalık raporu var) — Nav'da ve katalogda duruyorlardı, eksik olan yalnız bu vitrindi.
        { Icon: ScanSearch,  key: 'login.capPage',     desc: 'login.capPageDesc',     tint: '#2dd4bf' },
        { Icon: FlaskConical, key: 'login.capScripted', desc: 'login.capScriptedDesc', tint: '#c084fc' },
        { Icon: Gauge,       key: 'login.capUptime',  desc: 'login.capUptimeDesc',  tint: '#818cf8' },
      ],
    },
    {
      Icon: BellRing, key: 'login.pillarAlert', desc: 'login.pillarAlertDesc', tint: '#f59e0b',
      items: [
        { Icon: Bell,          key: 'login.opsAlarm',       tint: '#f59e0b' },
        { Icon: AlertTriangle, key: 'login.opsIncident',    tint: '#f87171' },
        { Icon: Wrench,        key: 'login.opsMaintenance', tint: '#94a3b8' },
      ],
    },
    {
      Icon: BarChart3, key: 'login.pillarReport', desc: 'login.pillarReportDesc', tint: '#60a5fa',
      items: [
        { Icon: FileText,   key: 'login.opsReport',   tint: '#60a5fa' },
        { Icon: BarChart3,  key: 'login.repStats',    tint: '#22d3ee' },
        { Icon: TrendingUp, key: 'login.repForecast', tint: '#34d399' },
      ],
    },
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
          {/* Üst: nötr marka logosu (beyaz-etiket logo yoksa) + wordmark + ENTERPRISE rozeti */}
          <div className="lp-top">
            {!branding.logo_data && <BrandLogo status="ok" size={32} />}
            <span className="lp-wordmark">{brand('app_name', 'SiteMonitor')}</span>
            <span className="lp-badge">ENTERPRISE</span>
          </div>

          {/* Orta: slogan + destek cümlesi + üç sütunlu değer önermesi (İzle / Uyar / Raporla) */}
          <div className="lp-center">
            <h1 className="lp-headline">{t('login.tagline')}</h1>
            <p className="lp-subline">{t('login.leftHeadline')}</p>
            <div className="lp-pillars">
              {PILLARS.map(({ Icon, key, desc, tint, items }, pi) => (
                <div key={key} className="lp-pillar" style={{ '--pillar-tint': tint, animationDelay: `${pi * 90}ms` }}>
                  <div className="lp-pillar-head">
                    <Icon size={17} className="lp-pillar-icon" />
                    <span className="lp-pillar-title">{t(key)}</span>
                  </div>
                  <p className="lp-pillar-desc">{t(desc)}</p>
                  <div className="lp-pillar-items">
                    {items.map(({ Icon: ItemIcon, key: ik, desc: idesc, tint: itint, flag }) => (
                      <span
                        key={ik}
                        className={`lp-chip${flag ? ' lp-chip--flag' : ''}`}
                        style={{ '--tile-tint': itint }}
                        title={idesc ? t(idesc) : undefined}
                      >
                        {flag && <span className="lp-chip-live" />}
                        <ItemIcon size={13} />
                        {t(ik)}
                      </span>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Alt: hero istatistikler + footer */}
          <div className="lp-bottom">
            {/* Hero istatistikleri — sabit pazarlama değeri değil, /api/public-stats'tan gerçek veri. */}
            <div className="lp-stats">
              <div className="lp-stat">
                <span className="lp-stat-num">
                  {stats?.monitored_targets != null ? String(stats.monitored_targets) : '—'}
                </span>
                <span className="lp-stat-lbl">{t('login.domainsMonitored')}</span>
              </div>
              <span className="lp-stat-sep" />
              <div className="lp-stat">
                <span className="lp-stat-num">
                  {stats?.availability_pct != null ? `${stats.availability_pct}%` : '—'}
                </span>
                <span className="lp-stat-lbl">{t('login.uptime')}</span>
              </div>
            </div>
            <div className="lp-footer">
              <span className="lp-footer-meta">
                {brand('footer_text', `v${__APP_VERSION__} · © ${new Date().getFullYear()} ${brand('app_name', 'SiteMonitor')}`)}
              </span>
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

          {/* Üst açıklama (branding override'lı) */}
          <div className="lp-intro">
            {branding.logo_data ? (
              <img src={branding.logo_data} alt={brand('app_name', 'SiteMonitor')}
                   style={{ maxHeight: 40, maxWidth: 200, display: 'block', margin: '0 auto 10px' }} />
            ) : (
              /* Nötr marka logosu — auth öncesi durum GÖSTERİLMEZ (BRAND.md); yatayda ortalı */
              <BrandLogo status="ok" size={96} style={{ display: 'block', margin: '0 auto 10px' }} />
            )}
            <div className="lp-intro-badge">
              <Lock size={13} />
              <span>{t('login.secureBadge')}</span>
            </div>
            <h2 className="lp-intro-title">{brand('login_title', t('login.heading'))}</h2>
            <p className="lp-intro-desc">{brand('login_subtitle', t('login.desc'))}</p>
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
                <label className="lp-label" htmlFor="lp-user">{brand('username_label', t('login.username'))}</label>
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
                  : <><Lock size={16} /> {brand('signin_label', t('login.submit'))}</>
                }
              </button>

              <p className="lp-ldap-hint">{t('login.ldapHint')}</p>
            </form>
          )}

          {/* Yardım: sorun bildirimi — tıklanınca pop-up açılır, sistem yöneticisine mail gider.
              Soru ile bağlantı AYRI SATIRLARDA: ikisi tek satırda akarken bağlantı, kart
              genişliğine ve dilin metin uzunluğuna göre bazen yanda bazen altta kalıyordu.
              Soruyu blok yapmak konumu her genişlikte ve her dilde sabitler. */}
          <p className="lp-help">
            <span className="lp-help-text">{t('login.helpText')}</span>
            <button type="button" className="lp-help-link" onClick={openHelp}>
              {t('login.helpLink')}
            </button>
          </p>
          {/* position:fixed overlay — .lp-root İÇİNDE render edilir ki Odyssey CSS değişkenleri çözülsün */}
          {helpOpen && (
            <div className="lp-modal-overlay" onClick={closeHelp}>
              <div className="lp-modal" role="dialog" aria-modal="true"
                   aria-label={t('login.helpTitle')} onClick={(e) => e.stopPropagation()}>
                <div className="lp-modal-head">
                  <h3 className="lp-modal-title">{t('login.helpTitle')}</h3>
                  <button type="button" className="lp-modal-close" aria-label="close" onClick={closeHelp}>
                    <X size={16} />
                  </button>
                </div>
                {helpSent ? (
                  <div className="lp-field" style={{ gap: 14, alignItems: 'center', textAlign: 'center', padding: '14px 0' }}>
                    <p className="lp-help" role="status" style={{ margin: 0 }}>
                      {helpRef ? t('login.helpSentRef').replace('{0}', helpRef) : t('login.helpSent')}
                    </p>
                    {helpRef && <div className="lp-label" style={{ fontSize: '1.1rem', letterSpacing: '.04em' }}>{helpRef}</div>}
                    <button type="button" className="lp-btn lp-btn--ghost" style={{ marginTop: 0 }} onClick={closeHelp}>
                      {t('login.helpClose')}
                    </button>
                  </div>
                ) : (
                  <>
                    <div className="lp-field">
                      <label className="lp-label" htmlFor="lp-help-user">{t('login.helpUsername')} *</label>
                      <input id="lp-help-user" className="lp-input" type="text" maxLength={100}
                        value={helpUser} onChange={(e) => setHelpUser(e.target.value)} />
                      {!helpUser.trim() && <span className="lp-help" style={{ textAlign: 'left', margin: 0 }}>{t('login.helpUsernameReq')}</span>}
                    </div>
                    <div className="lp-field">
                      <label className="lp-label" htmlFor="lp-help-email">{t('login.helpEmail')} *</label>
                      <input id="lp-help-email" className="lp-input" type="email" maxLength={255}
                        value={helpEmail} placeholder={t('login.helpEmailPlaceholder')}
                        onChange={(e) => setHelpEmail(e.target.value)} />
                      {!helpEmail.trim() && <span className="lp-help" style={{ textAlign: 'left', margin: 0 }}>{t('login.helpEmailReq')}</span>}
                    </div>
                    <div className="lp-field">
                      <label className="lp-label" htmlFor="lp-help-errtext">{t('login.helpErrorText')}</label>
                      <textarea id="lp-help-errtext" className="lp-input" rows={2} maxLength={2000}
                        value={helpErrorText} placeholder={t('login.helpErrorTextPlaceholder')}
                        onChange={(e) => setHelpErrorText(e.target.value)} />
                    </div>
                    <div className="lp-field">
                      <label className="lp-label" htmlFor="lp-help-msg">{t('login.helpDesc')} *</label>
                      <textarea id="lp-help-msg" className="lp-input" rows={7} maxLength={5000}
                        style={{ resize: 'vertical', minHeight: 120 }}
                        value={helpMsg} placeholder={t('login.helpMsgPlaceholder')}
                        onChange={(e) => setHelpMsg(e.target.value)} />
                      <span className="lp-char-count">{helpMsg.length}/5000</span>
                    </div>
                    <div className="lp-field">
                      <label className="lp-label">
                        {t('login.helpShot')}
                        <span className="lp-char-count" style={{ position: 'static', marginLeft: 8 }}>{helpShots.length}/{MAX_SHOTS}</span>
                      </label>
                      <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                        <button type="button" className="lp-btn lp-btn--ghost" style={{ width: 'auto', marginTop: 0, padding: '9px 16px' }}
                          onClick={() => shotRef.current?.click()} disabled={helpShots.length >= MAX_SHOTS}>
                          {t('login.helpShotChoose')}
                        </button>
                        <input ref={shotRef} type="file" accept="image/png,image/jpeg" multiple
                          style={{ display: 'none' }} onChange={onShotChosen} />
                      </div>
                      {helpShots.length > 0 && (
                        <div className="lp-shot-grid">
                          {helpShots.map((src, i) => (
                            <div className="lp-shot-item" key={i}>
                              <img src={src} alt={`screenshot ${i + 1}`} className="lp-shot-thumb" />
                              <button type="button" className="lp-shot-x" aria-label={t('login.helpShotRemove')}
                                title={t('login.helpShotRemove')} onClick={() => removeShot(i)}>×</button>
                            </div>
                          ))}
                        </div>
                      )}
                      <span className="lp-help" style={{ textAlign: 'left', margin: 0 }}>{t('login.helpShotHint')}</span>
                    </div>
                    {helpErr && (
                      <div className="lp-error" role="alert">
                        <div>{helpErr}</div>
                        {helpErrDetail && (
                          <>
                            <button type="button" className="lp-help-link" style={{ marginTop: 6 }}
                              onClick={() => setHelpErrShowDetail((v) => !v)}>
                              {helpErrShowDetail ? t('login.helpErrDetailHide') : t('login.helpErrDetailShow')}
                            </button>
                            {helpErrShowDetail && (
                              <div style={{ marginTop: 6, fontFamily: 'monospace', fontSize: 12, opacity: .85, wordBreak: 'break-word' }}>
                                {helpErrDetail}
                              </div>
                            )}
                          </>
                        )}
                      </div>
                    )}
                    <button type="button" className="lp-btn" onClick={sendHelp}
                      disabled={helpSending || !helpUser.trim() || !EMAIL_RE.test(helpEmail.trim()) || !helpMsg.trim()}>
                      {helpSending
                        ? <><span className="lp-spinner" /> {t('login.helpSending')}</>
                        : t('login.helpSend')}
                    </button>
                  </>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
