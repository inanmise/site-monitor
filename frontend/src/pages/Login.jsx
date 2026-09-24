import { useState, useEffect, useRef } from 'react'
import { api } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useBranding, useAppVersion } from '../contexts/BrandingProvider.jsx'
import BrandLogo from '../components/BrandLogo.jsx'
import { downscaleImage } from '../utils/imageDownscale.js'
import { ShieldAlert, ShieldCheck, Lock, Globe, Activity, Radio, Network, Server, Search, Gauge, Bell, BellRing, AlertTriangle, FileText, BarChart3, TrendingUp, Wrench, ScanSearch, FlaskConical, Zap, X, Info, Eye, EyeOff, AlertCircle, ImagePlus } from 'lucide-react'
// shadcn/ui (feature/shadcn-ui): giriş formu ve sorun bildirimi penceresi gerçek shadcn bileşenleri
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { Badge } from '@/components/shadcn/badge'
import { Alert, AlertDescription } from '@/components/shadcn/alert'
import { Dialog, DialogClose, DialogContent, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'

// App.jsx logout temizliği de bu anahtarı kullanır — tek kaynak buradan export edilir.
export const REMEMBER_KEY = 'site-monitor-remembered-user'
const STORAGE_KEY = REMEMBER_KEY

export default function Login({ onLogin, sessionExpired = false }) {
  const t = useT()
  const { lang, toggle: toggleLang } = useLanguage()
  // Branding (beyaz etiket): dolu değer varsa onu, boşsa i18n varsayılanını kullan (auth ÖNCESİ public).
  const { get: brand, branding } = useBranding()
  const appVersion = useAppVersion()   // sunucudan; gömülü değer yalnız yedek
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
  // Escape / dış tıklama kapatması shadcn Dialog'dan (Radix) gelir — elle keydown dinleyicisi yok.
  function closeHelp() { setHelpOpen(false) }

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
    try {
      const r = await api.sendLoginHelp({
        username: helpUser.trim(),
        email: helpEmail.trim(),
        errorText: helpErrorText.trim(),
        message: helpMsg.trim(),
        images: helpShots,
      })
      if (r?.success) { setHelpRef(r.reference || ''); setHelpSent(true) }
      else { setHelpErr(helpReason(r)); setHelpErrDetail(helpDetail(r)) }
    } finally {
      setHelpSending(false)
    }
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
        // Ikon Zap: bu vitrinde Gauge uptime'a ait, sayfa hizi onunla karismasin.
        { Icon: Zap,         key: 'login.capPagespeed', desc: 'login.capPagespeedDesc', tint: '#fb923c' },
        { Icon: Gauge,       key: 'login.capUptime',  desc: 'login.capUptimeDesc',  tint: '#818cf8' },
      ],
    },
    {
      Icon: BellRing, key: 'login.pillarAlert', desc: 'login.pillarAlertDesc', tint: '#f59e0b',
      items: [
        { Icon: Bell,          key: 'login.opsAlarm',       tint: '#f59e0b' },
        { Icon: AlertTriangle, key: 'login.opsIncident',    tint: '#f87171' },
        { Icon: Wrench,        key: 'login.opsMaintenance', tint: '#a1a1aa' },
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

      {/* ── Sol panel: tanıtım (shadcn "authentication" örneğindeki koyu zinc panel — her temada koyu) ── */}
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
                    <Icon size={16} className="lp-pillar-icon" />
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
                        <ItemIcon size={12} />
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
                {brand('footer_text', `v${appVersion} · © ${new Date().getFullYear()} ${brand('app_name', 'SiteMonitor')}`)}
              </span>
              <Button type="button" variant="ghost" size="sm" className="lp-lang-btn" onClick={toggleLang}>
                <Globe />
                {lang === 'tr' ? 'English' : 'Türkçe'}
              </Button>
            </div>
          </div>
        </div>
      </div>

      {/* ── Sağ panel: giriş formu (shadcn Input / Label / Button / Alert) ── */}
      <div className="lp-right">
        <div className="lp-form-wrap">

          {/* Oturum düştüğünde (401 → hard reload) gösterilen bilgi bildirimi (AUTH-1) */}
          {sessionExpired && (
            <Alert role="status" aria-live="polite">
              <Info />
              <AlertDescription>{t('login.sessionExpired')}</AlertDescription>
            </Alert>
          )}

          {/* Üst açıklama (branding override'lı) */}
          <div className="lp-intro">
            {branding.logo_data ? (
              <img src={branding.logo_data} alt={brand('app_name', 'SiteMonitor')} className="lp-intro-custom-logo" />
            ) : (
              /* Nötr marka logosu — auth öncesi durum GÖSTERİLMEZ (BRAND.md) */
              <BrandLogo status="ok" size={72} style={{ display: 'block' }} />
            )}
            <Badge variant="secondary" className="lp-intro-badge">
              <Lock />
              {t('login.secureBadge')}
            </Badge>
            <h2 className="lp-intro-title">{brand('login_title', t('login.heading'))}</h2>
            <p className="lp-intro-desc">{brand('login_subtitle', t('login.desc'))}</p>
          </div>

          {/* Başka yerde aktif oturum — onay ekranı */}
          {confirmActiveSession ? (
            <div className="lp-blocked" role="alertdialog" aria-live="polite">
              <div className="lp-blocked-icon">
                <ShieldAlert size={28} />
              </div>
              <h3 className="lp-blocked-title">{t('login.activeSessionTitle')}</h3>
              <p className="lp-blocked-desc">{t('login.activeSessionDesc')}</p>
              <div className="lp-blocked-actions">
                <Button className="w-full" onClick={confirmAndLogin} disabled={loading}>
                  {loading
                    ? <><span className="lp-spinner" /> {t('login.loading')}</>
                    : <><Lock /> {t('login.activeSessionConfirm')}</>
                  }
                </Button>
                <Button type="button" variant="outline" className="w-full" onClick={cancelActiveSession} disabled={loading}>
                  <X /> {t('login.activeSessionCancel')}
                </Button>
              </div>
            </div>

          ) : permanentLock ? (
            <div className="lp-blocked lp-blocked--permanent" role="alert">
              <div className="lp-blocked-icon lp-blocked-icon--permanent">
                <ShieldAlert size={28} />
              </div>
              <h3 className="lp-blocked-title lp-blocked-title--permanent">{t('login.permLockedTitle')}</h3>
              <p className="lp-blocked-desc">{t('login.permLockedDesc')}</p>
              <ul className="lp-blocked-reasons">
                <li>{t('login.permLockedReason1')}</li>
                <li>{t('login.permLockedReason2')}</li>
              </ul>
              <div className="lp-blocked-perm-badge">
                <Lock size={15} />
                {t('login.permLockedBadge')}
              </div>
              <p className="lp-blocked-help">{t('login.permLockedHelp')}</p>
            </div>

          ) : lockout > 0 ? (
            <div className="lp-blocked" role="alert" aria-live="polite">
              <div className="lp-blocked-icon">
                <ShieldAlert size={28} />
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
                <Label htmlFor="lp-user">{brand('username_label', t('login.username'))}</Label>
                <Input
                  id="lp-user"
                  className="h-10"
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
                <Label htmlFor="lp-pass">{t('login.password')}</Label>
                <div className="lp-pass-wrap">
                  <Input
                    id="lp-pass"
                    className="h-10"
                    type={showPass ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    placeholder={t('login.passPlaceholder')}
                    required
                    autoComplete="current-password"
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    className="lp-eye"
                    onClick={() => setShowPass(p => !p)}
                    tabIndex={-1}
                    aria-label={showPass ? t('login.hidePass') : t('login.showPass')}
                  >
                    {showPass ? <EyeOff /> : <Eye />}
                  </Button>
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
                <Alert variant="destructive">
                  <AlertCircle />
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              )}

              <Button type="submit" size="lg" className="w-full" disabled={loading}>
                {loading
                  ? <><span className="lp-spinner" /> {t('login.loading')}</>
                  : <><Lock /> {brand('signin_label', t('login.submit'))}</>
                }
              </Button>

              <p className="lp-ldap-hint">{t('login.ldapHint')}</p>
            </form>
          )}

          {/* Yardım: sorun bildirimi — tıklanınca pop-up açılır, sistem yöneticisine mail gider.
              Soru ile bağlantı AYRI SATIRLARDA: ikisi tek satırda akarken bağlantı, kart
              genişliğine ve dilin metin uzunluğuna göre bazen yanda bazen altta kalıyordu.
              Soruyu blok yapmak konumu her genişlikte ve her dilde sabitler. */}
          <p className="lp-help">
            <span className="lp-help-text">{t('login.helpText')}</span>
            <Button type="button" variant="link" className="lp-help-link" onClick={openHelp}>
              {t('login.helpLink')}
            </Button>
          </p>

          {/* shadcn Dialog (Radix): Escape / dış tıklama / odak tuzağı bileşenden gelir */}
          <Dialog open={helpOpen} onOpenChange={(open) => { if (!open) closeHelp() }}>
            <DialogContent showCloseButton={false} aria-describedby={undefined}
                           className="lp-help-dialog max-h-[90vh] overflow-y-auto sm:max-w-lg">
              <DialogHeader>
                <DialogTitle>{t('login.helpTitle')}</DialogTitle>
              </DialogHeader>
              <DialogClose asChild>
                <Button type="button" variant="ghost" size="icon-sm" className="lp-dialog-close" aria-label={t('app.close')}>
                  <X />
                </Button>
              </DialogClose>
              {helpSent ? (
                <div className="lp-help-sent">
                  <p role="status">
                    {helpRef ? t('login.helpSentRef').replace('{0}', helpRef) : t('login.helpSent')}
                  </p>
                  {helpRef && <div className="lp-help-ref">{helpRef}</div>}
                  <Button type="button" variant="outline" onClick={closeHelp}>
                    {t('login.helpClose')}
                  </Button>
                </div>
              ) : (
                <div className="lp-form">
                  <div className="lp-field">
                    <Label htmlFor="lp-help-user">{t('login.helpUsername')} *</Label>
                    <Input id="lp-help-user" type="text" maxLength={100}
                      value={helpUser} onChange={(e) => setHelpUser(e.target.value)} />
                    {!helpUser.trim() && <span className="lp-field-hint">{t('login.helpUsernameReq')}</span>}
                  </div>
                  <div className="lp-field">
                    <Label htmlFor="lp-help-email">{t('login.helpEmail')} *</Label>
                    <Input id="lp-help-email" type="email" maxLength={255}
                      value={helpEmail} placeholder={t('login.helpEmailPlaceholder')}
                      onChange={(e) => setHelpEmail(e.target.value)} />
                    {!helpEmail.trim() && <span className="lp-field-hint">{t('login.helpEmailReq')}</span>}
                  </div>
                  <div className="lp-field">
                    <Label htmlFor="lp-help-errtext">{t('login.helpErrorText')}</Label>
                    <Textarea id="lp-help-errtext" rows={2} maxLength={2000}
                      value={helpErrorText} placeholder={t('login.helpErrorTextPlaceholder')}
                      onChange={(e) => setHelpErrorText(e.target.value)} />
                  </div>
                  <div className="lp-field">
                    <Label htmlFor="lp-help-msg">{t('login.helpDesc')} *</Label>
                    <Textarea id="lp-help-msg" rows={7} maxLength={5000} className="lp-help-msg"
                      value={helpMsg} placeholder={t('login.helpMsgPlaceholder')}
                      onChange={(e) => setHelpMsg(e.target.value)} />
                    <span className="lp-char-count">{helpMsg.length}/5000</span>
                  </div>
                  <div className="lp-field">
                    <Label>
                      {t('login.helpShot')}
                      <span className="lp-char-count">{helpShots.length}/{MAX_SHOTS}</span>
                    </Label>
                    <div>
                      <Button type="button" variant="outline" size="sm"
                        onClick={() => shotRef.current?.click()} disabled={helpShots.length >= MAX_SHOTS}>
                        <ImagePlus /> {t('login.helpShotChoose')}
                      </Button>
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
                    <span className="lp-field-hint">{t('login.helpShotHint')}</span>
                  </div>
                  {helpErr && (
                    <Alert variant="destructive">
                      <AlertCircle />
                      <AlertDescription>
                        <div>{helpErr}</div>
                        {helpErrDetail && (
                          <>
                            <Button type="button" variant="link" className="lp-help-link"
                              onClick={() => setHelpErrShowDetail((v) => !v)}>
                              {helpErrShowDetail ? t('login.helpErrDetailHide') : t('login.helpErrDetailShow')}
                            </Button>
                            {helpErrShowDetail && <div className="lp-err-detail">{helpErrDetail}</div>}
                          </>
                        )}
                      </AlertDescription>
                    </Alert>
                  )}
                  <Button type="button" className="w-full" onClick={sendHelp}
                    disabled={helpSending || !helpUser.trim() || !EMAIL_RE.test(helpEmail.trim()) || !helpMsg.trim()}>
                    {helpSending
                      ? <><span className="lp-spinner" /> {t('login.helpSending')}</>
                      : t('login.helpSend')}
                  </Button>
                </div>
              )}
            </DialogContent>
          </Dialog>
        </div>
      </div>
    </div>
  )
}
