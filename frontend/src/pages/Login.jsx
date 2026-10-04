import { useState, useEffect, useId, useRef, useCallback } from 'react'
import { api } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useBranding, useAppVersion } from '../contexts/BrandingProvider.jsx'
import BrandLogo from '../components/BrandLogo.jsx'
import LoginHelpDialog from '../components/issues/report/LoginHelpDialog.jsx'
import { ShieldAlert, ShieldCheck, Lock, Globe, Activity, Radio, Network, Server, Search, Gauge, Bell, BellRing, AlertTriangle, FileText, BarChart3, TrendingUp, Wrench, ScanSearch, FlaskConical, Zap, X, Info, Eye, EyeOff, AlertCircle, UserX, KeyRound } from 'lucide-react'
import { isAccountInactivePayload } from '../utils/accountInactive.js'
import { isMaintenancePayload, lastWindow } from '../utils/systemMaintenance.js'
import MaintenanceLoginCard from '../components/maintenance/MaintenanceLoginCard.jsx'
// shadcn/ui (feature/shadcn-ui): giriş formu ve sorun bildirimi penceresi gerçek shadcn bileşenleri
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Spinner } from '../components/ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Alert, AlertDescription } from '@/components/shadcn/alert'
// Kodla giriş (push / e-posta tek kullanımlık kod, 2026-10-02): ana form DEĞİŞMEZ — altına "veya" + açık yöntemlerin
// düğmeleri; tıklanınca aynı kart kod akışına geçer.
import OtpLoginFlow, { availableChannels } from '../components/login/OtpLoginFlow.jsx'
import OtpMethodButtons from '../components/login/OtpMethodButtons.jsx'
// Kullanım istatistikleri (2026-10-04): sağlıklı izleme (İzleme Panosu ile aynı sayı), 24 sa koşum/alarm, 7 gün
// erişilebilirlik, takım, aktif kullanıcı, şu an çevrimiçi — shadcn Card kutucukları, dokun-gör açıklamalı.
import UsageStats from '../components/login/UsageStats.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'

// App.jsx logout temizliği de bu anahtarı kullanır — tek kaynak buradan export edilir.
export const REMEMBER_KEY = 'site-monitor-remembered-user'
const STORAGE_KEY = REMEMBER_KEY

// Tarayıcı depolaması engelliyse (gizli pencere / site verisi kapalı) erişim ATAR — giriş ekranı çökmemeli
// (2026-09-27, S7). "Beni hatırla" yalnız bir kolaylık: okunamazsa boş, yazılamazsa sessizce atlanır.
function readRemembered() { try { return localStorage.getItem(STORAGE_KEY) } catch { return null } }
function writeRemembered(username) { try { if (username) localStorage.setItem(STORAGE_KEY, username); else localStorage.removeItem(STORAGE_KEY) } catch { /* yoksay */ } }

/**
 * @param sessionExpired  oturum düştü bildirimi (`?session=expired`)
 * @param accountInactive hesap pasife alındı bildirimi (`?session=inactive` ya da açılışta 401 ACCOUNT_INACTIVE, 2026-10-02)
 * @param maintenanceEnded oturum sistem bakımı nedeniyle kapatıldı (`?session=maintenance` ya da açılışta 401 MAINTENANCE,
 *                         2026-10-02) — bakım kartı "oturumunuz sonlandırıldı" kipinde
 */
/** Giriş sayfasının bakım durumu yoklaması (ms) — oturumsuz public uç, `no-store`. */
const MAINT_POLL_MS = 60_000
/** Kullanım istatistikleri yoklaması (ms) — sunucu belleğiyle aynı pencere (60 sn); gizli sekmede durur. */
const STATS_POLL_MS = 60_000

export default function Login({ onLogin, sessionExpired = false, accountInactive = false, maintenanceEnded = false }) {
  const t = useT()
  // langPending: İngilizce sözlük (ayrı chunk) iniyor — dil düğmesi kısa süre meşgul görünür (2026-10-02, öneri 22)
  const { lang, toggle: toggleLang, pending: langPending } = useLanguage()
  // Branding (beyaz etiket): dolu değer varsa onu, boşsa i18n varsayılanını kullan (auth ÖNCESİ public).
  const { get: brand, branding } = useBranding()
  const appVersion = useAppVersion()   // sunucudan; gömülü değer yalnız yedek
  const saved = readRemembered()
  const [username, setUsername] = useState(saved || '')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(!!saved)
  const [error, setError]     = useState('')
  // Hata türü (2026-10-02): pasif hesap mesajı "yanlış parola"dan ayrı görünür (data-code + ikon).
  const [errorCode, setErrorCode] = useState(null)
  const [loading, setLoading] = useState(false)
  const [showPass, setShowPass] = useState(false)
  const [lockout, setLockout]           = useState(0)    // seconds remaining
  const [permanentLock, setPermanentLock] = useState(false) // admin must unlock
  const [confirmActiveSession, setConfirmActiveSession] = useState(false) // başka yerde aktif oturum onayı
  const sessionDlgId = useId()   // aktif oturum onayı (alertdialog) başlık/açıklama bağları
  const [stats, setStats] = useState(null)   // kullanım istatistikleri — gerçek veriden (public endpoint)
  const [statsLoading, setStatsLoading] = useState(true)   // ilk yanıta kadar iskelet (yerleşim zıplamasın)
  // Sistem Bakım Modu (2026-10-02): public uçtan bakım durumu (yaklaşan / süren) — kart; ilk çizimde sekmenin son bildiği
  // pencere (oturum kesilince taşınır) kullanılır, uç gelince tazelenir.
  const [maint, setMaint] = useState(() => (maintenanceEnded ? lastWindow() : null))
  // "Sorun bildir" penceresi — LoginHelpDialog (public /api/login-help, IP oran sınırlı; 2026-09-27 yeniden tasarım:
  // bölümlü rehberli form, sürükle-bırak/yapıştır görsel, satır içi doğrulama, net hata sebebi + tekrar dene).
  // Açılışta giriş formundaki kullanıcı adı taşınır.
  const [helpOpen, setHelpOpen] = useState(false)
  const [helpUser, setHelpUser] = useState('')
  // Giriş yöntemleri (public uç) + açık kod akışı ({ channel } | null) — 2026-10-02
  const [methods, setMethods] = useState(null)
  const [otp, setOtp] = useState(null)
  const otpChannels = availableChannels(methods)
  function openHelp() {
    setHelpUser(username.trim())
    setHelpOpen(true)
  }

  // Açılışta + dakikada bir (gizli sekmede durur, dönünce bir kez tazeler). Tazelemede iskelet GÖSTERİLMEZ — eski
  // rakamlar yerinde kalır; hata şeridi bozmaz (son bilinen değerler ya da "—").
  const aliveRef = useRef(true)
  useEffect(() => { aliveRef.current = true; return () => { aliveRef.current = false } }, [])
  const loadStats = useCallback(async () => {
    try {
      const r = await api.getPublicStats?.()
      if (aliveRef.current && r?.success && r.data && typeof r.data === 'object') setStats(r.data)
    } catch { /* istatistiksiz de login çalışır */ }
    finally { if (aliveRef.current) setStatsLoading(false) }
  }, [])
  useVisibleInterval(loadStats, STATS_POLL_MS)

  // Bakım durumu: açılışta + dakikada bir (bakım biterse kart kendiliğinden kalkar). Hata kartı bozmaz.
  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const r = await api.getSystemMaintenanceStatus?.()
        if (alive && r?.success && r.data && typeof r.data === 'object' && !Array.isArray(r.data)) setMaint(r.data)
      } catch { /* bakım bilgisi olmadan da giriş çalışır */ }
    }
    load()
    const id = setInterval(load, MAINT_POLL_MS)
    return () => { alive = false; clearInterval(id) }
  }, [])

  // Giriş yöntemleri: yalnız yapılandırma (hangi kod yöntemi açık, süreler). Okunamazsa yalnız şifre formu çizilir.
  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const r = await api.getLoginMethods?.()
        if (alive && r?.success) setMethods(r)
      } catch { /* yöntemler olmadan da şifreyle giriş çalışır */ }
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
    setErrorCode(null)
    setLoading(true)
    try {
      const data = await api.login(username, password, rememberMe)
      if (data.success) {
        writeRemembered(rememberMe ? username : null)
        onLogin(data)
      } else if (isAccountInactivePayload(data)) {
        // 403 ACCOUNT_INACTIVE — sunucu bunu YALNIZ kimlik bilgisi doğruysa döner; yanlış parola genel mesajda kalır.
        setErrorCode('ACCOUNT_INACTIVE')
        setError(t('login.accountInactiveError'))
      } else if (isMaintenancePayload(data)) {
        // 403 MAINTENANCE (2026-10-02) — bakımda yalnız global yöneticiler girer; kart pencere bilgisiyle tazelenir.
        setErrorCode('MAINTENANCE')
        setError(t('sysmaint.login.error'))
        if (data.maintenance && typeof data.maintenance === 'object') setMaint(data.maintenance)
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
      } else if (data.error_code === 'LDAP_LOGIN_DISABLED') {
        // LDAP ile giriş kapalı (2026-10-02) — yerel olmayan her ad AYNI yanıtı alır; altta kod yöntemleri durur.
        setErrorCode('LDAP_LOGIN_DISABLED')
        setError(t('login.ldapDisabled'))
      } else {
        setError(data.error || t('login.failed'))
      }
    } catch {
      setError(t('login.serverError'))
    } finally {
      setLoading(false)
    }
  }

  /** Kod akışında başarılı giriş — şifre girişinin başarı dalıyla aynı (beni hatırla adı + onLogin). */
  function onOtpSuccess(data, { username: name, remember }) {
    writeRemembered(remember ? name : null)
    onLogin(data)
  }

  // Onay sonrası: force_login=true ile tekrar dene → backend diğer oturumu düşürüp girişi tamamlar.
  async function confirmAndLogin() {
    setLoading(true)
    setError('')
    setErrorCode(null)
    try {
      const data = await api.login(username, password, rememberMe, true)
      if (data.success) {
        writeRemembered(rememberMe ? username : null)
        onLogin(data)
      } else if (isAccountInactivePayload(data)) {
        setConfirmActiveSession(false)
        setErrorCode('ACCOUNT_INACTIVE')
        setError(t('login.accountInactiveError'))
      } else if (isMaintenancePayload(data)) {
        setConfirmActiveSession(false)
        setErrorCode('MAINTENANCE')
        setError(t('sysmaint.login.error'))
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
            {/* Kullanım istatistikleri (2026-10-04, kullanıcı kararı: "Raporlama" sütununun ALTINDAKİ alanda) — sabit
                pazarlama değeri değil, /api/public-stats'tan gerçek veri. Yalnız geniş ekranda (≥1024 px): daha darda vitrin
                sütunları gizli ve panel başlık bandına iner; orada şerit formun ALTINDA çizilir (aşağıda). Ayar kapalıyken
                aynı yerde eski iki rakam (izlenen hedef + erişilebilirlik). */}
            <UsageStats stats={stats} loading={statsLoading} tone="panel" placement="panel" className="mt-2 hidden lg:block" />
          </div>

          {/* Alt: footer */}
          <div className="lp-bottom">
            <div className="lp-footer">
              <span className="lp-footer-meta">
                {brand('footer_text', `v${appVersion} · © ${new Date().getFullYear()} ${brand('app_name', 'SiteMonitor')}`)}
              </span>
              <Button type="button" variant="ghost" size="sm" className="lp-lang-btn" onClick={toggleLang}
                disabled={!!langPending} aria-busy={langPending ? true : undefined}>
                {langPending ? <Spinner size={16} inline label={t('nav.langLoading')} /> : <Globe />}
                {lang === 'tr' ? 'English' : 'Türkçe'}
              </Button>
            </div>
          </div>
        </div>
      </div>

      {/* ── Sağ panel: giriş formu (shadcn Input / Label / Button / Alert) ── */}
      <div className="lp-right">
        {/* Geniş ekranda form görünüm alanında ORTALI ve yapışkan (2026-10-04): sol panel kullanım istatistikleriyle uzadı;
            sütun sayfa boyu olduğundan eski dikey ortalama formu kısa dizüstü ekranlarda aşağı itiyordu. Telefonda akış aynı. */}
        <div className="lp-form-wrap lg:sticky lg:top-10 lg:min-h-[calc(100svh-5rem)] lg:self-start lg:justify-center">

          {/* Oturum düştüğünde (401 → hard reload) gösterilen bilgi bildirimi (AUTH-1) */}
          {sessionExpired && (
            <Alert role="status" aria-live="polite">
              <Info />
              <AlertDescription>{t('login.sessionExpired')}</AlertDescription>
            </Alert>
          )}

          {/* Sistem Bakım Modu (2026-10-02): yaklaşan / süren bakım ya da bakım nedeniyle kapatılan oturum */}
          <MaintenanceLoginCard status={maint} sessionEnded={maintenanceEnded} />

          {/* Hesap pasife alındı (2026-10-02): oturum yönetici tarafından kapatıldı ya da çerez pasif hesaba ait */}
          {accountInactive && (
            <Alert variant="warning" role="status" aria-live="polite" data-slot="login-account-inactive">
              <UserX />
              <AlertDescription>{t('login.accountInactive')}</AlertDescription>
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
            // alertdialog adını başlığından, açıklamasını metninden alır (2026-09-27 a11y A5 — eskiden adsızdı)
            <div className="lp-blocked" role="alertdialog" aria-live="polite"
              aria-labelledby={`${sessionDlgId}-title`} aria-describedby={`${sessionDlgId}-desc`}>
              <div className="lp-blocked-icon">
                <ShieldAlert size={28} aria-hidden="true" />
              </div>
              <h3 id={`${sessionDlgId}-title`} className="lp-blocked-title">{t('login.activeSessionTitle')}</h3>
              <p id={`${sessionDlgId}-desc`} className="lp-blocked-desc">{t('login.activeSessionDesc')}</p>
              <div className="lp-blocked-actions">
                <Button className="w-full" onClick={confirmAndLogin} disabled={loading}>
                  {loading
                    ? <><Spinner size={16} inline decorative /> {t('login.loading')}</>
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
          ) : otp ? (
            /* Kodla giriş akışı (2026-10-02) — aynı kartta, ana formun yerine */
            <OtpLoginFlow methods={methods} initialUsername={username.trim()} initialChannel={otp.channel}
              initialRemember={rememberMe} onSuccess={onOtpSuccess} onCancel={() => setOtp(null)}
              onMaintenance={(m) => setMaint(m)} />
          ) : (
            <>
            {/* Normal giriş formu */}
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

              {/* Beni hatırla — shadcn Checkbox + Label (giriş sayfasında TooltipProvider yok; ipucu gerekmiyor) */}
              <div className="inline-flex items-center gap-2 text-sm">
                <Checkbox id="lp-remember" checked={rememberMe} onCheckedChange={(v) => setRememberMe(v === true)} />
                <Label htmlFor="lp-remember" className="cursor-pointer font-normal select-none">{t('login.rememberMe')}</Label>
              </div>

              {error && (
                <Alert variant="destructive" data-code={errorCode || undefined}>
                  {errorCode === 'ACCOUNT_INACTIVE' ? <UserX /> : errorCode === 'MAINTENANCE' ? <Wrench />
                    : errorCode === 'LDAP_LOGIN_DISABLED' ? <KeyRound /> : <AlertCircle />}
                  <AlertDescription>{error}</AlertDescription>
                </Alert>
              )}

              <Button type="submit" size="lg" className="w-full" disabled={loading}>
                {loading
                  ? <><Spinner size={16} inline decorative /> {t('login.loading')}</>
                  : <><Lock /> {brand('signin_label', t('login.submit'))}</>
                }
              </Button>

              {/* "AD hesabınızla da girebilirsiniz" ipucu LDAP girişi KAPALIYKEN yanıltıcı olurdu (2026-10-02) */}
              {methods?.ldap !== false && <p className="lp-ldap-hint">{t('login.ldapHint')}</p>}
            </form>

            {/* Alternatif: kodla giriş — yalnız AÇIK yöntemler (ince "veya" ayıracı + küçük düğmeler) */}
            <OtpMethodButtons channels={otpChannels} onPick={(ch) => setOtp({ channel: ch })} />
            </>
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

          {/* Sorun bildir penceresi — rehberli form, telefonda tam ekran (components/issues/report/LoginHelpDialog). */}
          <LoginHelpDialog open={helpOpen} onClose={() => setHelpOpen(false)} initialUsername={helpUser} />
        </div>
      </div>

      {/* Telefon / tablet (<1024 px): vitrin sütunları ("Raporlama" dahil) gizli olduğundan kullanım istatistikleri SAYFANIN
          EN ALTINDA, formun altında (form ilk ekranda kalır; telefonda 2, tablette 3 sütun). Geniş ekranda gizli — orada şerit
          sol panelde "Raporlama" sütununun altında. Kullanım kipi kapalıysa aynı yerde eski iki rakam; istek düşerse
          kutucuklar "—" ile çizilir (şerit kaybolmaz). */}
      <section className="w-full border-t px-6 pt-8 pb-10 lg:hidden" data-slot="login-usage-section"
        aria-label={t('login.usage.title')}>
        <UsageStats stats={stats} loading={statsLoading} tone="surface" placement="bottom"
          className="mx-auto max-w-[380px] sm:max-w-2xl" />
      </section>
    </div>
  )
}
