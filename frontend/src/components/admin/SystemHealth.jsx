import { useState, useEffect, useCallback, useMemo, useRef, Fragment, lazy, Suspense } from 'react'
import { dateLocale } from '../../i18n/dateLocale.js'
import { api, formatDate, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval'
import { CheckCircle, XCircle, MinusCircle, HelpCircle, Mail, ChevronRight, Check, Server, Database, Globe, Cpu, ChevronDown, Users, LogIn, ShieldAlert, UserCheck } from 'lucide-react'
import MiniChart from './MiniChart'
import ChartModal from './ChartModal'
import HeartbeatHistoryModal from './HeartbeatHistoryModal'
import LoginHeatmap from './LoginHeatmap'
import DateTimeField from '../ui/DateTimeField.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'

import UserBadge from '../ui/UserBadge.jsx'   // proje-geneli ortak kullanıcı rozeti (avatar + ad-soyad)
import { mailPreviewSrcDoc, mailLogoVariant } from '../../utils/mailPreview.js'
import { Spinner, ProgressBar, LoadingBlock } from '../ui/Progress.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { Rocket } from 'lucide-react'
const DeploymentHistoryPanel = lazy(() => import('./DeploymentHistoryPanel.jsx'))   // yalnız bölüm açılınca
const LoginActivityChart = lazy(() => import('./LoginActivityChart.jsx'))   // recharts → tembel yükle (bundle hafif)
const HttpMetricsExplorer = lazy(() => import('./HttpMetricsExplorer.jsx'))  // recharts → tembel yükle

function SmtpStatusCell({ row, t }) {
  const cfg = {
    SENT:    { Icon: CheckCircle,  cls: 'smtp-kind-sent',    key: 'health.statusSent' },
    FAILED:  { Icon: XCircle,      cls: 'smtp-kind-failed',  key: 'health.statusFailed' },
    SKIPPED: { Icon: MinusCircle,  cls: 'smtp-kind-skipped', key: 'health.statusSkipped' },
    UNKNOWN: { Icon: HelpCircle,   cls: 'smtp-kind-skipped', key: 'health.statusUnknown' },
  }
  const c = cfg[row.kind] ?? cfg.UNKNOWN
  return (
    <div>
      <span className={`smtp-kind-badge ${c.cls}`}>
        <c.Icon size={11} />{t(c.key)}
      </span>
      {row.error && (
        <div className="smtp-log-error sys-err-text sys-small">{row.error}</div>
      )}
    </div>
  )
}

// Haftalık erişilebilirlik gönderim durumunu SmtpStatusCell'in beklediği "kind"e indirger.
function waKind(status) {
  if (!status) return 'UNKNOWN'
  if (status === 'SENT') return 'SENT'
  if (status.startsWith('FAILED')) return 'FAILED'
  return 'SKIPPED' // NO_RECIPIENT vb.
}

function triggerLabel(trigger, t) {
  const map = {
    INITIAL:       t('health.triggerInitial'),
    ESCALATION:    t('health.triggerEscalation'),
    DAILY_REALERT: t('health.triggerDailyRealert'),
    MANUAL:        t('health.triggerManual'),
    RESOLUTION:    t('health.triggerResolution'),
  }
  return map[trigger] ?? trigger
}

function fmsDuration(ms) {
  if (!ms || ms <= 0) return '—'
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} sn`
  const m = Math.floor(ms / 60000)
  const s = Math.round((ms % 60000) / 1000)
  return `${m} dk ${s} sn`
}

// Kullanıcı/oturum izleme yardımcıları
function shortUa(ua) {
  if (!ua) return '—'
  if (/Edg\//.test(ua)) return 'Edge'
  if (/OPR\/|Opera/.test(ua)) return 'Opera'
  if (/Chrome\//.test(ua)) return 'Chrome'
  if (/Firefox\//.test(ua)) return 'Firefox'
  if (/Safari\//.test(ua)) return 'Safari'
  if (/curl\//i.test(ua)) return 'curl'
  return ua.split(' ')[0] || '—'
}
function fmtMins(m) {
  const n = Number(m) || 0
  if (n < 60) return `${n} dk`
  const h = Math.floor(n / 60), mm = n % 60
  return `${h} sa ${mm} dk`
}
function locStr(country, city) {
  const parts = [city, country].filter(Boolean)
  return parts.length ? parts.join(', ') : '—'
}

export default function SystemHealth({ systemRole, globalAdmin = false, preFilterDomain, openSmtpModalOnLoad, onSmtpPreFilterConsumed }) {
  const isAdmin = systemRole === 'ADMIN'
  // Kullanıcı/oturum izleme yalnız global admin veya AUDIT'e açık (backend requireSystemRead ile aynı).
  // Sağlık sekmesi herkese görünür; yetkisiz kullanıcıda bu bölümü hiç çağırma/gösterme → 403/"Yüklenemedi" olmaz.
  const canViewUserActivity = !!globalAdmin || systemRole === 'AUDIT'
  const t = useT()
  const { showConfirm } = useDialog()
  const [health, setHealth]           = useState(null)
  const [metrics, setMetrics]         = useState([])
  const [httpMetrics, setHttpMetrics] = useState(null)
  const [poolCardRefreshing, setPoolCardRefreshing] = useState(false)
  const [poolLastRefreshed, setPoolLastRefreshed]   = useState(null)
  const [uactRefreshing, setUactRefreshing]         = useState(false)
  const [hbRefreshing, setHbRefreshing] = useState(false)
  const [hbModalOpen, setHbModalOpen] = useState(false)
  // varsayılan: tüm akordiyon kapalı; `?sec=releases` derin-linki (sürüm çipi) o bölümü açık getirir.
  const [openSection, setOpenSection] = useState(() => (readUrlParam('sec', '') === 'releases' ? 'releases' : null))
  const toggleSection = (key) => setOpenSection(prev => prev === key ? null : key)
  const sysVisible  = openSection === 'sys'
  const releasesVisible = openSection === 'releases'
  // Aynı sekmedeyken (Sistem Sağlığı açıkken çipten "Dağıtım geçmişi") App `sec` param'ını olayla iletir.
  useEffect(() => {
    const on = (e) => { const s = e?.detail?.sec; if (s === 'releases') setOpenSection('releases') }
    window.addEventListener('sm:tab-params', on)
    return () => window.removeEventListener('sm:tab-params', on)
  }, [])
  const { canView: canViewRes, canEdit: canEditRes } = usePermissions()
  const canViewReleases = canViewRes('release_history.read')
  const dbVisible   = openSection === 'db'
  const httpVisible = openSection === 'http'
  const cpuVisible  = openSection === 'cpu'
  const usersVisible = openSection === 'users'
  const [smtpPeriod, setSmtpPeriod]   = useState('7d')
  const [loading, setLoading]         = useState(true)
  const [releasing, setReleasing]     = useState(false)
  const [triggering, setTriggering]   = useState(false)
  const fastPollRef  = useRef(null)
  const seenRunning  = useRef(false)
  const [msg, setMsg]                 = useState(null)
  const [modalChart, setModalChart]   = useState(null)
  const [httpExpOpen, setHttpExpOpen] = useState(false)
  // HTTP İstek Gezgini modali — Escape ile kapat (ChartModal deseni).
  useEffect(() => {
    if (!httpExpOpen) return
    const onKey = e => { if (e.key === 'Escape') setHttpExpOpen(false) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [httpExpOpen])
  const [smtpModal, setSmtpModal]     = useState(false)
  const [smtpLogs, setSmtpLogs]       = useState(null)
  const [smtpLoading, setSmtpLoading] = useState(false)
  const [selectedLog, setSelectedLog] = useState(null)
  const [smtpFilters, setSmtpFilters] = useState({ from: '', to: '', subject: '', status: '', domain: '' })
  // Haftalık erişilebilirlik gönderim logları (kart → modal)
  const [waLogsModal, setWaLogsModal]   = useState(false)
  const [waLogs, setWaLogs]             = useState(null)
  const [waLogsLoading, setWaLogsLoading] = useState(false)
  const [waLogItem, setWaLogItem]       = useState(null)
  const [loadErrors, setLoadErrors]   = useState({ health: false, metrics: false, http: false, db: false, users: false })
  const [userActivity, setUserActivity] = useState(null)
  const [uactSort, setUactSort]       = useState({ col: 'duration_min', dir: 'desc' })
  const [terminatingUser, setTerminatingUser] = useState(null)
  const [sessionDetail, setSessionDetail] = useState(null) // tıklanan aktif oturum detay modalı
  const [showActiveList, setShowActiveList] = useState(false) // "Aktif Oturum" kartı → kişi listesi modalı
  const [heatCell, setHeatCell] = useState(null) // ısı haritası hücresi {weekday,hour} → o saatteki girişler
  const [expRole, setExpRole] = useState(null)   // rol drill-down (açık rol)
  const [expTeam, setExpTeam] = useState(null)   // takım drill-down (açık takım index)
  const [weekIdx, setWeekIdx] = useState(0)      // heatmap hafta navigasyonu (0=bu hafta .. 2=2 hafta önce)
  const [kpiDetail, setKpiDetail] = useState(null) // KPI kartı drill-down {title, kind}
  // Giriş trendi: esnek aralık (1g/7g/30g) + istenen güne gitme (saatlik) — zoom/navigasyon
  const [trendDays, setTrendDays] = useState(7)    // 1 | 7 | 30
  const [trendDate, setTrendDate] = useState('')   // 'yyyy-mm-dd' seçili gün (saatlik); boşsa aralık modu
  const [trendCustom, setTrendCustom] = useState(null)   // {from,to} UTC ISO — özel aralık ("x gün x saat")
  const [trendPreset, setTrendPreset] = useState(null)   // null | '1h' | '6h' — hızlı küçük-aralık (dakika bazlı)
  const [trendShowCustom, setTrendShowCustom] = useState(false)
  const [trendData, setTrendData] = useState(null) // { buckets, granularity }
  const [trendLoading, setTrendLoading] = useState(false)
  // Veritabanı analitiği (executive): pencere (1/7/30) + payload
  const [dbDays, setDbDays] = useState(7)
  const [dbData, setDbData] = useState(null)
  const [dbLoading, setDbLoading] = useState(false)
  const [dbKpiDetail, setDbKpiDetail] = useState(null) // DB KPI kartı drill-down {title, kind}

  const load = useCallback(async () => {
    // allSettled: bir endpoint çökse de diğerleri yüklensin; her bölüm
    // kendi hata durumunu loadErrors üzerinden gösterir.
    const [healthRes, metricsRes, httpRes, uactRes] = await Promise.allSettled([
      api.admin.getSystemHealth(),
      api.admin.getMetrics(),
      api.admin.getHttpMetrics(),
      // Kullanıcı/oturum izleme yalnız yetkili (global admin/AUDIT) için — aksi halde hiç çağırma,
      // böylece yetkisiz kullanıcıda 403 → "Yüklenemedi: Kullanıcılar" çıkmaz.
      canViewUserActivity ? api.admin.getUserActivity() : Promise.resolve(null),
    ])
    const errs = { health: false, metrics: false, http: false, users: false }
    if (healthRes.status === 'fulfilled' && healthRes.value?.success) {
      setHealth(healthRes.value.data); setPoolLastRefreshed(new Date())
    } else { errs.health = true }
    if (metricsRes.status === 'fulfilled' && metricsRes.value?.success) {
      setMetrics(metricsRes.value.data)
    } else { errs.metrics = true }
    if (httpRes.status === 'fulfilled' && httpRes.value?.success) {
      setHttpMetrics(httpRes.value.data)
    } else { errs.http = true }
    if (canViewUserActivity) {
      if (uactRes.status === 'fulfilled' && uactRes.value?.success) {
        setUserActivity(uactRes.value.data)
      } else { errs.users = true }
    }
    setLoadErrors(errs)
    setLoading(false)
  }, [canViewUserActivity])

  useVisibleInterval(load, 30000)   // gizli sekmede polling durur
  useEffect(() => () => { if (fastPollRef.current) clearInterval(fastPollRef.current) }, [])   // scan fast-poll temizliği

  // Giriş trendi: seçilen aralık/gün için esnek seriyi çek. Tarih seçiliyse o günün saatlik
  // dağılımı (00:00–24:00); değilse son N gün (1g→saatlik, 7g/30g→günlük). UTC ISO gönderilir.
  const loadTrend = useCallback(async () => {
    if (!canViewUserActivity) return
    setTrendLoading(true)
    try {
      const iso = d => d.toISOString().slice(0, 19)
      let fromD, toD, gran
      if (trendPreset) {
        const hrs = trendPreset === '1h' ? 1 : 6
        toD = new Date(); fromD = new Date(toD.getTime() - hrs * 3_600_000); gran = 'minute'   // hızlı küçük-aralık → dakika
      } else if (trendCustom) {
        fromD = new Date(trendCustom.from + 'Z'); toD = new Date(trendCustom.to + 'Z')
        const span = toD - fromD
        // Adaptif granülerlik: aralık küçüldükçe daha ince kova — ≤6 saat → DAKİKA, ≤2 gün → saat, üstü → gün.
        gran = span <= 6 * 3_600_000 ? 'minute' : span <= 2 * 86_400_000 ? 'hour' : 'day'
      } else if (trendDate) {
        fromD = new Date(`${trendDate}T00:00:00`)          // yerel gün başı
        toD   = new Date(`${trendDate}T23:59:59`)          // AYNI gün sonu (ertesi güne taşmaz)
        gran  = 'hour'
      } else if (trendDays === 1) {
        toD = new Date(); fromD = new Date(toD.getTime() - 86_400_000); gran = 'hour'
      } else {
        toD = new Date(); fromD = new Date(toD.getTime() - trendDays * 86_400_000); gran = 'day'
      }
      const res = await api.admin.getLoginSeries(iso(fromD), iso(toD), gran)
      if (res?.success) setTrendData(res.data)
    } finally {
      setTrendLoading(false)
    }
  }, [canViewUserActivity, trendDays, trendDate, trendCustom, trendPreset])

  useEffect(() => { if (usersVisible) loadTrend() }, [usersVisible, loadTrend])

  // Veritabanı analitiği — DB bölümü açıkken / pencere değişince çek
  const loadDbAnalytics = useCallback(async () => {
    setDbLoading(true)
    try {
      const res = await api.admin.getDbAnalytics(dbDays)
      if (res?.success) setDbData(res.data)
    } finally {
      setDbLoading(false)
    }
  }, [dbDays])

  useEffect(() => { if (dbVisible) loadDbAnalytics() }, [dbVisible, loadDbAnalytics])

  const refreshHeartbeat = useCallback(async () => {
    setHbRefreshing(true)
    try {
      const res = await api.admin.triggerHeartbeat()
      if (res?.success) setHealth(prev => ({ ...prev, heartbeat: res.data }))
    } finally {
      setHbRefreshing(false)
    }
  }, [])

  const handleTerminateSession = useCallback(async (username) => {
    if (!username) return
    // Tarayıcı-varsayılanı kutu DEĞİL: proje diyaloğu (tasarım sistemi + hedefin adı).
    if (!await showConfirm({
      title: t('uact.terminate'), message: t('uact.terminateConfirm', username),
      confirmText: t('uact.terminate'), variant: 'danger',
    })) return
    setTerminatingUser(username)
    const res = await api.admin.terminateUserSession(username)
    if (res?.success) {
      const uact = await api.admin.getUserActivity()
      if (uact?.success) setUserActivity(uact.data)
      setMsg(t('uact.terminated', username))
    }
    setTerminatingUser(null)
  }, [t])

  const refreshPool = useCallback(async () => {
    setPoolCardRefreshing(true)
    try {
      const res = await api.admin.getSystemHealth()
      if (res?.success) {
        setHealth(prev => ({ ...prev, ...res.data }))
        setPoolLastRefreshed(new Date())
      }
    } finally {
      setPoolCardRefreshing(false)
    }
  }, [])

  // Kullanıcı Etkinliği'ni manuel tazele (aktif oturum/sayaçlar/grafikler) — buton tıklamasıyla.
  const refreshUserActivity = useCallback(async () => {
    setUactRefreshing(true)
    try {
      const res = await api.admin.getUserActivity()
      if (res?.success) setUserActivity(res.data)
    } finally {
      setUactRefreshing(false)
    }
  }, [])

  // (Kaldırıldı) 60 sn'lik refreshPool auto-timer — 30 sn'lik `load` zaten getSystemHealth'i
  // (havuz dahil) çekiyordu; yinelenen poll'du. Manuel "havuz yenile" butonu (refreshPool) duruyor.

  const openSmtpModal = useCallback(async (overrides) => {
    setSmtpModal(true)
    setSmtpLogs(null)
    setSmtpFilters({ from: '', to: '', subject: '', status: '', domain: '', ...(overrides ?? {}) })
    setSmtpLoading(true)
    try {
      const days = parseInt(smtpPeriod) || 30
      const res = await api.admin.getSmtpLogs(days)
      setSmtpLogs(res?.success ? res.data : [])
    } finally {
      setSmtpLoading(false)
    }
  }, [smtpPeriod])

  const openWaLogsModal = useCallback(async () => {
    setWaLogsModal(true)
    setWaLogs(null)
    setWaLogsLoading(true)
    try {
      const res = await api.admin.getWeeklyAvailHistory(100, true)
      setWaLogs(res?.success ? res.data : [])
    } finally {
      setWaLogsLoading(false)
    }
  }, [])

  const openWaItem = useCallback(async (row) => {
    const res = await api.admin.getWeeklyAvailHistoryItem(row.id)
    if (res?.success) setWaLogItem(res.data)
  }, [])

  useEffect(() => {
    if (openSmtpModalOnLoad) {
      openSmtpModal(preFilterDomain ? { domain: preFilterDomain } : undefined)
      onSmtpPreFilterConsumed?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openSmtpModalOnLoad, preFilterDomain])

  const closeSmtpModal = () => {
    setSmtpModal(false)
    setSmtpFilters({ from: '', to: '', subject: '', status: '', domain: '' })
  }

  const filteredSmtpLogs = useMemo(() => {
    if (!smtpLogs) return null
    const f = smtpFilters
    const ci = s => (s ?? '').toString().toLowerCase()
    return smtpLogs.filter(l => {
      if (f.from    && !ci(l.sender_email).includes(ci(f.from))) return false
      if (f.to      && !(ci(l.recipient_email) + ' ' + ci(l.recipient_name)).includes(ci(f.to))) return false
      if (f.subject && !ci(l.subject).includes(ci(f.subject))) return false
      if (f.status  && l.kind !== f.status) return false
      if (f.domain  && !ci(l.domain).includes(ci(f.domain))) return false
      return true
    })
  }, [smtpLogs, smtpFilters])

  const handleForceRelease = async () => {
    if (!await showConfirm({
      title: t('sys.forceRelease'), message: t('sys.lockReleaseConfirm'),
      confirmText: t('sys.forceRelease'), variant: 'danger',
    })) return
    setReleasing(true)
    // try/finally ŞART: request() ağ hatasında THROW ediyor (client.js) ve bayrak
    // temizlenmezse düğme remount'a kadar kilitli kalır — kullanıcı için "buton bozuldu".
    try {
      const res = await api.admin.forceReleaseLock()
      setMsg(res?.success ? t('sys.lockReleased') : t('sys.error'))
    } catch (e) {
      setMsg(t('sys.error'))
    } finally {
      setReleasing(false)
    }
    load()
  }

  const startScanPoll = useCallback((prevLastRun) => {
    if (fastPollRef.current) clearInterval(fastPollRef.current)
    seenRunning.current = false
    fastPollRef.current = setInterval(async () => {
      const res = await api.admin.getSystemHealth()
      if (!res?.success) return
      setHealth(res.data)
      const isRunning   = res.data?.scheduler?.running
      const newLastRun  = res.data?.scan?.last_run
      if (isRunning) seenRunning.current = true
      // Tamamlandı: running false'a döndü VEYA last_run değişti (hızlı tarama)
      const done = (seenRunning.current && !isRunning) ||
                   (newLastRun && newLastRun !== prevLastRun)
      if (done) {
        clearInterval(fastPollRef.current)
        fastPollRef.current = null
        load()
      }
    }, 2000)
    setTimeout(() => {
      if (fastPollRef.current) { clearInterval(fastPollRef.current); fastPollRef.current = null }
    }, 300_000)
  }, [load])

  const handleForceRun = async () => {
    setTriggering(true)
    try {
      await api.runScheduler()
      setMsg(t('sys.checkTriggered'))
    } catch (e) {
      setMsg(t('sys.error'))
    } finally {
      setTriggering(false)   // bkz. handleForceRelease: ağ hatası bayrağı sızdırmasın
    }
    startScanPoll(health?.scan?.last_run)
  }

  if (loading && !health) {
    return <LoadingBlock label={t('sys.loading')} className="sys-loading" />
  }

  const { scheduler, lock, pool, executor_pool, memory, scan, scan_alarm, smtp, heartbeat,
          domain_expiry: domainExpiry, weekly_availability: weeklyAvail } = health || {}
  const isRunning = scheduler?.running

  // Domain-expiry (RDAP) veri kaynağı durumu
  const domSource = domainExpiry?.source || 'IDLE'
  const domBadgeClass = domSource === 'RDAP' ? 'sys-badge-free'
    : domSource === 'FALLBACK' ? 'sys-badge-warn'
    : domSource === 'NONE' ? 'sys-badge-locked' : 'sys-badge-idle'
  const domSourceLabel = domSource === 'RDAP' ? t('health.domSrcRdap')
    : domSource === 'FALLBACK' ? t('health.domSrcFallback')
    : domSource === 'NONE' ? t('health.domSrcNone') : t('health.domSrcIdle')

  // Haftalık erişilebilirlik scheduler durumu
  const waEnabled = weeklyAvail?.enabled
  const waRunning = weeklyAvail?.running
  const waBadgeClass = waRunning ? 'sys-badge-running' : waEnabled ? 'sys-badge-free' : 'sys-badge-idle'
  const waBadgeText  = waRunning ? t('sys.running') : waEnabled ? t('waSched.active') : t('waSched.paused')

  const smtpData = smtp?.periods?.[smtpPeriod] ?? smtp ?? {}
  const smtpRate = smtpData.rate ?? 100
  const smtpRateClass = smtpRate >= 99 ? 'sys-ok-text' : smtpRate >= 95 ? 'sys-warn-text' : 'sys-err-text'
  const smtpHasAlarm = !!smtpData.alarm

  // Compute active alarms for banner — SMTP alarm reflects the currently selected period
  const alarms = []
  if (scan_alarm) alarms.push(t('health.scanAlarm'))
  if (smtpHasAlarm) alarms.push(t('health.smtpAlarmFor', t(`health.smtpPeriod${smtpPeriod}`)))
  if (heartbeat?.alarm) alarms.push(t('health.hbAlarm'))
  if (health?.network?.alarm) alarms.push(t('health.networkAlarm'))
  if (domainExpiry?.alarm) alarms.push(t('health.domAlarm'))

  const hbMinutes = heartbeat?.minutes_since ?? -1
  const hbOk = hbMinutes >= 0 && hbMinutes <= 15

  const poolUsePct = pool?.max_size > 0 ? Math.round((pool.active / pool.max_size) * 100) : 0
  const poolIconClass = pool?.waiting > 0 ? 'pool-icon-alarm' : poolUsePct > 80 ? 'pool-icon-warn' : 'pool-icon-ok'
  const memIconClass = !memory ? 'mem-icon-ok' : memory.used_pct > 85 ? 'mem-icon-alarm' : memory.used_pct > 65 ? 'mem-icon-warn' : 'mem-icon-ok'

  const failedSections = Object.entries(loadErrors)
    .filter(([, v]) => v)
    .map(([k]) => t(`health.part${k.charAt(0).toUpperCase() + k.slice(1)}`))

  return (
    <div className="sys-health">
      {alarms.length > 0 && (
        <div className="health-alarm-banner">
          <span className="health-alarm-icon">⚠</span>
          <strong>{t('health.alarmBanner')}:</strong> {alarms.join(' • ')}
        </div>
      )}

      {failedSections.length > 0 && !loading && (
        <div className="health-alarm-banner" role="alert" style={{ background: '#fff5f5', borderLeft: '4px solid var(--danger)' }}>
          <span className="health-alarm-icon">✕</span>
          <strong>{t('health.loadErrorTitle')}:</strong> {failedSections.join(', ')}.&nbsp;
          <button type="button" className="btn btn-secondary" style={{ marginLeft: 8 }} onClick={load}>
            {t('err.reload')}
          </button>
        </div>
      )}

      {msg && (
        <div className="sys-msg" onClick={() => setMsg(null)}>
          {msg} <span className="sys-msg-close">✕</span>
        </div>
      )}

      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('sys')}
          title={sysVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Server size={18} /></span>
          <span className="stats-collapse-label">{t('health.sectionSystem')}</span>
          {!sysVisible && (
            <span className="stats-collapse-hint">{t('health.sectionShow', t('health.sectionSystem'))}</span>
          )}
          <span className={`stats-collapse-chevron${sysVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {sysVisible && (
      <div className="sys-grid">

        {/* Scheduler card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`sched-icon ${isRunning ? 'sched-icon-running' : 'sched-icon-idle'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10"/>
                <polyline points="12 6 12 12 16 14"/>
              </svg>
              <h3>{t('sys.schedulerTitle')}</h3>
            </div>
            <span className={`sys-badge ${isRunning ? 'sys-badge-running' : 'sys-badge-idle'}`}>
              {isRunning ? t('sys.running') : t('sys.idle')}
            </span>
          </div>
          <dl className="sys-dl">
            <dt>{t('sys.lastRun')}</dt>
            <dd>{scheduler?.last_run ? formatDate(scheduler.last_run) : t('sys.never')}</dd>
            <dt>{t('sys.nextRun')}</dt>
            <dd>{scheduler?.next_run ? formatDate(scheduler.next_run) : '—'}</dd>
            {health?.build && (
              <>
                <dt>{t('sys.buildVersion')}</dt>
                <dd className="sys-mono">v{health.build.version}{health.build.environment ? <span className="sys-muted"> · {health.build.environment}</span> : null}</dd>
                <dt>{t('sys.buildCommit')}</dt>
                <dd className="sys-mono">{health.build.commit || '—'}</dd>
              </>
            )}
            <dt>{isRunning ? t('sys.currentRunId') : t('sys.lastRunId')}</dt>
            <dd className="sys-mono">
              {isRunning
                ? (scheduler?.current_run_id || '—')
                : (scheduler?.last_run_id || '—')}
            </dd>
            <dt>{t('sys.instanceId')}</dt>
            <dd className="sys-mono sys-small">{scheduler?.instance_id}</dd>
            <dt>{t('sys.activeDomains')}</dt>
            <dd>{scheduler?.active_domains}</dd>
          </dl>
          {isAdmin && (
            <button
              className="btn-primary sys-action-btn"
              disabled={isRunning || triggering}
              onClick={handleForceRun}
            >
              {triggering ? t('sys.triggering') : t('sys.forceRun')}
            </button>
          )}
        </div>

        {/* Lock card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`lock-icon ${lock?.held ? 'lock-icon-held' : 'lock-icon-free'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                <path d="M7 11V7a5 5 0 0110 0v4"/>
              </svg>
              <h3>{t('sys.lockTitle')}</h3>
            </div>
            <span className={`sys-badge ${lock?.held ? 'sys-badge-locked' : 'sys-badge-free'}`}>
              {lock?.held ? t('sys.locked') : t('sys.free')}
            </span>
          </div>
          {lock?.held ? (
            <>
              <dl className="sys-dl">
                <dt>{t('sys.lockedBy')}</dt>
                <dd className="sys-mono sys-small">{lock.locked_by}</dd>
                <dt>{t('sys.lockedUntil')}</dt>
                <dd>{formatDate(lock.locked_until)}</dd>
                <dt>{t('sys.heldByMe')}</dt>
                <dd>{lock.held_by_me ? t('sys.yes') : t('sys.no')}</dd>
              </dl>
              {!lock.held_by_me && isAdmin && (
                <button
                  className="btn-danger sys-action-btn"
                  disabled={releasing}
                  onClick={handleForceRelease}
                >
                  {releasing ? t('sys.releasing') : t('sys.forceRelease')}
                </button>
              )}
            </>
          ) : (
            <p className="sys-free-msg">{t('sys.lockFree')}</p>
          )}
        </div>

        {/* Weekly availability email scheduler card */}
        {weeklyAvail && (
          <div className="sys-card sys-card-clickable" onClick={openWaLogsModal} title={t('waLogs.clickHint')}>
            <div className="sys-card-header">
              <div className="hb-title-row">
                <svg className={`sched-icon ${waEnabled ? 'sched-icon-running' : 'sched-icon-idle'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                  <rect x="2" y="4" width="20" height="16" rx="2"/>
                  <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7"/>
                </svg>
                <h3>{t('waSched.title')}</h3>
              </div>
              <span className={`sys-badge ${waBadgeClass}`}>{waBadgeText}</span>
            </div>
            <dl className="sys-dl">
              <dt>{t('waSched.schedule')}</dt>
              <dd>{t('waSched.scheduleVal')}</dd>
              <dt>{t('sys.nextRun')}</dt>
              <dd>{waEnabled ? (weeklyAvail.next_run ? formatDate(weeklyAvail.next_run) : '—') : t('waSched.pausedShort')}</dd>
              <dt>{t('sys.lastRun')}</dt>
              <dd>{weeklyAvail.last_run_at ? formatDate(weeklyAvail.last_run_at) : t('sys.never')}</dd>
              {weeklyAvail.last_run_at && (
                <>
                  <dt>{t('waSched.lastResult')}</dt>
                  <dd>
                    <span className="sys-ok-text">{weeklyAvail.last_run_sent ?? 0}/{weeklyAvail.last_run_teams ?? 0} {t('waSched.sent')}</span>
                    {(weeklyAvail.last_run_failed ?? 0) > 0 && (
                      <span className="sys-err-text"> · {weeklyAvail.last_run_failed} {t('waSched.failed')}</span>
                    )}
                    {(weeklyAvail.last_run_no_recipient ?? 0) > 0 && (
                      <span className="sys-warn-text"> · {weeklyAvail.last_run_no_recipient} {t('waSched.noRecipient')}</span>
                    )}
                  </dd>
                  <dt>{t('waSched.reportedWeek')}</dt>
                  <dd>{weeklyAvail.last_run_week || '—'}</dd>
                </>
              )}
            </dl>
          </div>
        )}

        {/* Pool card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`pool-icon ${poolIconClass}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <ellipse cx="12" cy="5" rx="9" ry="3"/>
                <path d="M21 12c0 1.66-4 3-9 3S3 13.66 3 12"/>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
              </svg>
              <h3>{t('sys.poolTitle')}</h3>
            </div>
            <button
              className="sys-card-refresh-btn"
              onClick={refreshPool}
              disabled={poolCardRefreshing}
              title={t('sys.poolRefresh')}
              aria-label={t('sys.poolRefresh')}
            >
              <span className={poolCardRefreshing ? 'spin' : ''}>↻</span>
            </button>
          </div>
          {poolLastRefreshed && (
            <div className="pool-updated-at">
              {t('sys.poolUpdatedAt').replace('{t}',
                poolLastRefreshed.toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit', second: '2-digit' })
              )}
            </div>
          )}
          {pool ? (
            <>
              <ProgressBar value={pool.active} max={pool.max_size} size="sm"
                label={t('sys.poolActive')} />
              <dl className="sys-dl">
                <dt>{t('sys.poolActive')}</dt>
                <dd>{pool.active}</dd>
                <dt>{t('sys.poolIdle')}</dt>
                <dd>{pool.idle}</dd>
                <dt>{t('sys.poolTotal')}</dt>
                <dd>{pool.total}</dd>
                <dt>{t('sys.poolWaiting')}</dt>
                <dd className={pool.waiting > 0 ? 'sys-warn-text' : ''}>{pool.waiting}</dd>
                <dt>{t('sys.poolMax')}</dt>
                <dd>{pool.max_size}</dd>
              </dl>
            </>
          ) : (
            <p className="sys-free-msg">{t('sys.poolUnavailable')}</p>
          )}
        </div>

        {/* Memory card */}
        <div className="sys-card">
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`mem-icon ${memIconClass}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="4" y="4" width="16" height="16" rx="2" ry="2"/>
                <rect x="9" y="9" width="6" height="6"/>
                <line x1="9" y1="1" x2="9" y2="4"/>
                <line x1="15" y1="1" x2="15" y2="4"/>
                <line x1="9" y1="20" x2="9" y2="23"/>
                <line x1="15" y1="20" x2="15" y2="23"/>
                <line x1="20" y1="9" x2="23" y2="9"/>
                <line x1="20" y1="14" x2="23" y2="14"/>
                <line x1="1" y1="9" x2="4" y2="9"/>
                <line x1="1" y1="14" x2="4" y2="14"/>
              </svg>
              <h3>{t('sys.memTitle')}</h3>
            </div>
            {memory && (
              <span className={`sys-badge ${memory.used_pct > 85 ? 'sys-badge-locked' : memory.used_pct > 65 ? 'sys-badge-warn' : 'sys-badge-free'}`}>
                {memory.used_pct}%
              </span>
            )}
          </div>
          {memory ? (
            <>
              {/* Eşik rengi CSS'e taşındı: --pg-fill token'ı ::-webkit-progress-value tarafından okunur. */}
              <ProgressBar value={memory.used_pct} max={100} size="sm" showValue
                label={t('sys.memUsed')}
                className={memory.used_pct > 85 ? 'pg-bar--crit' : memory.used_pct > 65 ? 'pg-bar--warn' : ''} />
              <dl className="sys-dl">
                <dt>{t('sys.memUsed')}</dt>
                <dd>{memory.used_mb} MB</dd>
                <dt>{t('sys.memFree')}</dt>
                <dd>{memory.free_mb} MB</dd>
                <dt>{t('sys.memTotal')}</dt>
                <dd>{memory.total_mb} MB</dd>
                <dt>{t('sys.memMax')}</dt>
                <dd>{memory.max_mb} MB</dd>
              </dl>
            </>
          ) : null}
        </div>

        {/* Scan card */}
        <div className={`sys-card${scan_alarm ? ' sys-card-alarm' : ''}`}>
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`scan-icon ${scan_alarm ? 'scan-icon-alarm' : 'scan-icon-ok'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="10"/>
                <circle cx="12" cy="12" r="6"/>
                <circle cx="12" cy="12" r="2"/>
                <line x1="12" y1="2" x2="12" y2="12"/>
              </svg>
              <h3>{t('health.scanTitle')}</h3>
            </div>
            {scan_alarm && (
              <span className="sys-badge sys-badge-locked">⚠ {t('health.scanAlarm')}</span>
            )}
          </div>
          <dl className="sys-dl">
            <dt>{t('health.lastScan')}</dt>
            <dd>{scan?.last_run ? formatDate(scan.last_run) : t('sys.never')}</dd>
            <dt>{t('health.scanDuration')}</dt>
            <dd>{fmsDuration(scan?.duration_ms)}</dd>
            <dt>{t('health.scanTotal')}</dt>
            <dd>{scan?.total ?? 0}</dd>
            <dt>{t('health.scanWarn')}</dt>
            <dd className={scan?.warnings > 0 ? 'sys-warn-text' : ''}>{scan?.warnings ?? 0}</dd>
            <dt>{t('health.scanErr')}</dt>
            <dd className={scan?.errors > 0 ? 'sys-err-text' : ''}>{scan?.errors ?? 0}</dd>
          </dl>
        </div>

        {/* SMTP card */}
        <div
          className={`sys-card sys-card-clickable${smtpHasAlarm ? ' sys-card-alarm' : ''}`}
          onClick={openSmtpModal}
          title={t('health.smtpClickHint')}
        >
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`smtp-envelope ${smtpHasAlarm ? 'smtp-envelope-alarm' : 'smtp-envelope-ok'}`} viewBox="0 0 24 24" width="20" height="20" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <rect x="2" y="4" width="20" height="16" rx="2"/>
                <polyline points="2,4 12,13 22,4"/>
              </svg>
              <h3>{t('health.smtpTitle')}</h3>
            </div>
            <span className={`sys-badge ${smtpHasAlarm ? 'sys-badge-locked' : 'sys-badge-free'}`}>
              <span className={smtpRateClass}>%{smtpRate}</span>
            </span>
          </div>
          {smtpHasAlarm && (
            <div className="health-card-alarm-msg">
              {t('health.smtpAlarmFor', t(`health.smtpPeriod${smtpPeriod}`))}
            </div>
          )}
          <div className="smtp-stream-wrap">
            <div className={`smtp-stream-line ${smtpHasAlarm ? 'smtp-stream-alarm' : 'smtp-stream-ok'}`} />
            {[0, 1, 2, 3].map(i => (
              <div
                key={i}
                className={`smtp-stream-dot ${smtpHasAlarm ? 'smtp-stream-alarm' : 'smtp-stream-ok'}`}
                style={{ animationDelay: `${i * 0.55}s` }}
              />
            ))}
          </div>
          <div className="smtp-period-pills" onClick={e => e.stopPropagation()}>
            {['1d', '7d', '15d', '30d'].map(p => (
              <button
                key={p}
                type="button"
                className={`smtp-period-pill${smtpPeriod === p ? ' is-selected' : ''}`}
                onClick={() => setSmtpPeriod(p)}
              >
                {t(`health.smtpPeriod${p}`)}
              </button>
            ))}
          </div>
          <dl className="sys-dl">
            <dt>{t('health.smtpSent')}</dt>
            <dd>{smtpData.sent ?? 0} / {smtpData.attempted ?? smtpData.total ?? 0}</dd>
            <dt>{t('health.smtpRate')}</dt>
            <dd className={smtpRateClass}>%{smtpRate}</dd>
            <dt></dt>
            <dd className="sys-small sys-muted">{t('health.smtpPeriodActive', t(`health.smtpPeriod${smtpPeriod}`))}</dd>
          </dl>
          <button
            type="button"
            className="smtp-log-cta"
            onClick={(e) => { e.stopPropagation(); openSmtpModal() }}
          >
            <Mail size={14} />
            <span>{t('health.smtpClickHint')}</span>
            <span className="smtp-log-cta-period">{t(`health.smtpPeriod${smtpPeriod}`)}</span>
            <ChevronRight size={14} className="smtp-log-cta-arrow" />
          </button>
        </div>

        {/* Heartbeat card */}
        <div
          className={`sys-card hb-card-clickable${heartbeat?.alarm ? ' sys-card-alarm' : ''}`}
          onClick={() => setHbModalOpen(true)}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setHbModalOpen(true) } }}
          title={t('health.hbHistoryHint')}
        >
          <div className="sys-card-header">
            <div className="hb-title-row">
              <svg className={`hb-heart ${hbOk ? 'hb-heart-ok' : 'hb-heart-alarm'}`} viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
                <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/>
              </svg>
              <h3>{t('health.hbTitle')}</h3>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }} onClick={(e) => e.stopPropagation()}>
              {isAdmin && (
                <button
                  className="sys-card-refresh-btn"
                  onClick={refreshHeartbeat}
                  disabled={hbRefreshing}
                  title={t('sys.poolRefresh')}
                  aria-label={t('sys.poolRefresh')}
                >
                  <span className={hbRefreshing ? 'spin' : ''}>↻</span>
                </button>
              )}
              <span className={`sys-badge ${hbOk ? 'sys-badge-free' : 'sys-badge-locked'}`}>
                {hbOk ? '✓' : '⚠'}
              </span>
            </div>
          </div>
          {heartbeat?.alarm && (
            <div className="health-card-alarm-msg">{t('health.hbAlarm')}</div>
          )}
          <div className="hb-ecg-wrap">
            <svg className={`hb-ecg-svg ${hbOk ? 'hb-ecg-ok' : 'hb-ecg-alarm'}`} viewBox="0 0 400 44" preserveAspectRatio="none" aria-hidden="true">
              <polyline points="0,22 50,22 57,19 63,22 78,22 84,4 90,40 96,4 102,22 116,14 131,22 200,22 250,22 257,19 263,22 278,22 284,4 290,40 296,4 302,22 316,14 331,22 380,22" fill="none" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/>
              {hbOk ? (
                <g transform="translate(388, 22)">
                  <circle r="10" className="hb-terminal-bg-ok" />
                  <path d="M -4 0 L -1 3 L 5 -4" className="hb-terminal-tick" />
                </g>
              ) : (
                <g transform="translate(388, 22)">
                  <circle r="10" className="hb-terminal-bg-err" />
                  <path d="M -4 -4 L 4 4 M 4 -4 L -4 4" className="hb-terminal-cross" />
                </g>
              )}
            </svg>
          </div>
          <dl className="sys-dl">
            <dt>{t('health.hbLast')}</dt>
            <dd>{heartbeat?.last_heartbeat ? formatDate(heartbeat.last_heartbeat) : t('sys.never')}</dd>
            <dt></dt>
            <dd className={hbOk ? 'sys-ok-text' : 'sys-err-text'}>
              {hbMinutes >= 0
                ? t('health.hbMinutes').replace('{n}', hbMinutes)
                : t('health.hbAlarm')}
            </dd>
          </dl>
          {heartbeat?.recent?.length > 0 && (
            <div className="hb-recent">
              <div className="hb-recent-title">{t('health.hbRecent')}</div>
              <ul className="hb-recent-list">
                {heartbeat.recent.map((ts, i) => (
                  <li key={ts} className="hb-recent-item">
                    <span className={`hb-dot ${i === 0 ? 'hb-dot-ok' : 'hb-dot-prev'}`} />
                    <span className="hb-recent-ts">{formatDate(ts)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        {/* Task Queue card — certCheckExecutor metrics */}
        {executor_pool && (
          <div className={`sys-card${(executor_pool.queue_size ?? 0) > (executor_pool.queue_capacity ?? 1) * 0.8 ? ' sys-card-alarm' : ''}`}>
            <div className="sys-card-header">
              <div className="hb-title-row">
                <span className="hb-heart hb-heart-ok" style={{ fontSize: 18 }}>⚡</span>
                <h3>{t('health.queueTitle')}</h3>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <button
                  className="sys-card-refresh-btn"
                  onClick={refreshPool}
                  disabled={poolCardRefreshing}
                  title={t('sys.poolRefresh')}
                  aria-label={t('sys.poolRefresh')}
                >
                  <span className={poolCardRefreshing ? 'spin' : ''}>↻</span>
                </button>
                <span className={`sys-badge ${(executor_pool.queue_size ?? 0) === 0 ? 'sys-badge-free' : 'sys-badge-locked'}`}>
                  {executor_pool.queue_size ?? 0}
                </span>
              </div>
            </div>
            {/* 2026-09-10: havuz/kuyruk artık Ayarlar'dan canlı — kart bunu söylesin, ops Helm'e koşmasın. */}
            <p className="section-desc" style={{ marginTop: 0 }}>{t('health.queueTuneHint')}</p>
            <dl className="sys-dl">
              <dt title={t('health.queueTooltip', executor_pool.max_pool_size ?? executor_pool.core_pool_size ?? 0)}>
                {t('health.queuePending')}
                <span className="queue-info-ind" aria-hidden="true">ⓘ</span>
              </dt>
              <dd>
                <strong>{executor_pool.queue_size ?? 0}</strong> / {executor_pool.queue_capacity ?? 0}
                <span className="queue-status-ind">
                  {(executor_pool.queue_size ?? 0) === 0
                    ? <Check size={14} className="queue-status-ok" strokeWidth={3} />
                    : <Spinner size={14} inline decorative />}
                </span>
              </dd>

              <dt>{t('health.queueActive')}</dt>
              <dd>
                {executor_pool.active_count ?? 0} / {executor_pool.pool_size ?? 0}
                <span className="queue-status-ind">
                  {(executor_pool.active_count ?? 0) === 0
                    ? <Check size={14} className="queue-status-ok" strokeWidth={3} />
                    : <Spinner size={14} inline decorative />}
                </span>
              </dd>

              <dt>{t('health.queueThreads')}</dt>
              <dd>{t('health.queueMinMax')
                    .replace('{min}', executor_pool.core_pool_size ?? 0)
                    .replace('{max}', executor_pool.max_pool_size ?? 0)}</dd>

              <dt>{t('health.queueCompleted')}</dt>
              <dd>{executor_pool.completed_tasks ?? 0}</dd>

              {executor_pool.caller_runs != null && (
                <>
                  <dt title={t('health.queueCallerRunsTooltip')}>{t('health.queueCallerRuns')}</dt>
                  <dd className={executor_pool.caller_runs > 0 ? 'queue-callerruns-hot' : undefined}>{executor_pool.caller_runs}</dd>
                </>
              )}

              {executor_pool.jvm_start_time && (
                <>
                  <dt title={t('health.queueSinceStartTooltip')}>{t('health.queueSinceStart')}</dt>
                  <dd>{formatDate(executor_pool.jvm_start_time)}</dd>
                </>
              )}
            </dl>
            <ProgressBar value={executor_pool.queue_size ?? 0}
              max={Math.max(1, executor_pool.queue_capacity ?? 1)} size="sm"
              label={t('health.queueTitle')} />
            {/* Doygunluk uyarısı: havuz doluyken sweep sessizce yavaşlıyordu (CallerRuns); şimdi kartta yazar. */}
            {(executor_pool.saturated || (executor_pool.queue_size ?? 0) > (executor_pool.queue_capacity ?? 1) * 0.8) && (
              <div className="queue-saturated" role="status">{t('health.queueSaturated')}</div>
            )}
          </div>
        )}

        {/* Domain-expiry (RDAP) source card */}
        {domainExpiry && (
          <div className={`sys-card${domainExpiry.alarm ? ' sys-card-alarm' : ''}`}>
            <div className="sys-card-header">
              <div className="hb-title-row">
                <Globe size={18} className={domSource === 'RDAP' ? 'sys-ok-text' : domSource === 'NONE' ? 'sys-err-text' : 'sys-warn-text'} aria-hidden="true" />
                <h3>{t('health.domTitle')}</h3>
              </div>
              <span className={`sys-badge ${domBadgeClass}`}>
                {domSource === 'RDAP' ? '✓' : domSource === 'NONE' ? '✕' : domSource === 'FALLBACK' ? '⚠' : '–'}
              </span>
            </div>
            {domainExpiry.alarm && (
              <div className="health-card-alarm-msg">{t('health.domAlarm')}</div>
            )}
            <dl className="sys-dl">
              <dt>{t('health.domSource')}</dt>
              <dd className={domSource === 'RDAP' ? 'sys-ok-text' : domSource === 'NONE' ? 'sys-err-text' : 'sys-warn-text'}>
                {domSourceLabel}
              </dd>
              <dt>{t('health.domLastOk')}</dt>
              <dd>{domainExpiry.last_success ? formatDate(domainExpiry.last_success) : t('sys.never')}</dd>
              {domainExpiry.reason && (
                <>
                  <dt>{t('health.domReason')}</dt>
                  <dd className="sys-err-text">{domainExpiry.reason}</dd>
                </>
              )}
            </dl>
          </div>
        )}

      </div>
        )}
      </div>

      {/* HTTP request metrics */}
      {httpMetrics && (
        <div className="stats-section">
          <div
            className="stats-collapse-bar"
            onClick={() => toggleSection('http')}
            title={httpVisible ? t('app.collapseStats') : t('app.expandStats')}
          >
            <span className="stats-collapse-icon"><Globe size={18} /></span>
            <span className="stats-collapse-label">{t('http.shortTitle')}</span>
            {!httpVisible && (
              <span className="stats-collapse-hint">{t('health.sectionShow', t('http.shortTitle'))}</span>
            )}
            <span className={`stats-collapse-chevron${httpVisible ? ' open' : ''}`}>
              <ChevronDown size={18} />
            </span>
          </div>
          {httpVisible && (
        <div className="metrics-section">
          <h3 className="metrics-title">{t('http.title')}</h3>

          <div className="http-stats-row">
            <div className="http-stat">
              <span className="http-stat-val">{httpMetrics.summary?.total_requests ?? 0}</span>
              <span className="http-stat-lbl">{t('http.totalReqs')}</span>
            </div>
            <div className="http-stat">
              <span className={`http-stat-val ${(httpMetrics.summary?.error_rate_pct ?? 0) > 5 ? 'http-stat-err' : ''}`}>
                {httpMetrics.summary?.error_rate_pct ?? 0}%
              </span>
              <span className="http-stat-lbl">{t('http.errorRate')}</span>
            </div>
            <div className="http-stat">
              <span className="http-stat-val">{httpMetrics.summary?.avg_ms ?? 0} ms</span>
              <span className="http-stat-lbl">{t('http.avgMs')}</span>
            </div>
            <div className="http-stat">
              <span className="http-stat-val">{httpMetrics.summary?.max_ms ?? 0} ms</span>
              <span className="http-stat-lbl">{t('http.maxMs')}</span>
            </div>
          </div>

          <div className="metrics-grid">
            <MiniChart
              label={t('http.reqPerMin')}
              unit=""
              color="#4f9cf9"
              data={(httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.count }))}
              onClick={() => setHttpExpOpen(true)}
            />
            <MiniChart
              label={t('http.avgDuration')}
              unit=" ms"
              color="#f59e0b"
              data={(httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.avg_ms }))}
              onClick={() => setHttpExpOpen(true)}
            />
            <MiniChart
              label={t('http.errorsPerMin')}
              unit=""
              color="#ef4444"
              data={(httpMetrics.history ?? []).map(b => ({ ts: b.ts, value: b.errors }))}
              onClick={() => setHttpExpOpen(true)}
            />
          </div>

        </div>
          )}
        </div>
      )}

      {/* JVM / CPU metrics */}
      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('cpu')}
          title={cpuVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Cpu size={18} /></span>
          <span className="stats-collapse-label">{t('health.sectionCpu')}</span>
          {!cpuVisible && (
            <span className="stats-collapse-hint">{t('health.sectionShow', t('health.sectionCpu'))}</span>
          )}
          <span className={`stats-collapse-chevron${cpuVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {cpuVisible && (
      <div className="metrics-section">
        <h3 className="metrics-title">{t('sys.metricsTitle')}</h3>
        <div className="metrics-grid">
          <MiniChart
            label={t('sys.cpuProcess')}
            unit="%"
            maxY={100}
            color="#4f9cf9"
            data={metrics.map(p => ({ ts: p.ts, value: p.cpu_process }))}
            onClick={() => setModalChart({
              label: t('sys.cpuProcess'), unit: '%', color: '#4f9cf9', maxY: 100,
              data: metrics.map(p => ({ ts: p.ts, value: p.cpu_process })),
            })}
          />
          <MiniChart
            label={t('sys.heapPct')}
            unit="%"
            maxY={100}
            color="#10b981"
            data={metrics.map(p => ({ ts: p.ts, value: p.heap_pct }))}
            onClick={() => setModalChart({
              label: t('sys.heapPct'), unit: '%', color: '#10b981', maxY: 100,
              data: metrics.map(p => ({ ts: p.ts, value: p.heap_pct })),
            })}
          />
          <MiniChart
            label={t('sys.threads')}
            unit=""
            color="#a78bfa"
            data={metrics.map(p => ({ ts: p.ts, value: p.threads }))}
            onClick={() => setModalChart({
              label: t('sys.threads'), unit: '', color: '#a78bfa',
              data: metrics.map(p => ({ ts: p.ts, value: p.threads })),
            })}
          />
        </div>
      </div>
        )}
      </div>

      {/* DB section — response time + table stats */}
      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('db')}
          title={dbVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Database size={18} /></span>
          <span className="stats-collapse-label">{t('health.dbTitle')}</span>
          {!dbVisible && (
            <span className="stats-collapse-hint">{t('health.sectionShow', t('health.dbTitle'))}</span>
          )}
          <span className={`stats-collapse-chevron${dbVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {dbVisible && (
      <div className="metrics-section">
        {(() => {
          const dd = dbData
          const sum = dd?.summary || {}
          const pgss = !!sum.pgss
          const winLbl = dbDays === 1 ? t('uact.range1d') : dbDays === 7 ? t('uact.range7d') : t('uact.range30d')
          const gran = dbDays === 1 ? 'hour' : 'day'
          const kpi2 = (key, Icon, val, label, sub, variant, onClick) => (
            <div key={key}
              className={`uact-kpi${variant ? ' uact-kpi--' + variant : ''}${onClick ? ' is-clickable' : ''}`}
              onClick={onClick} title={onClick ? t('uact.detailHint') : undefined}>
              <span className="uact-kpi-icon"><Icon size={16} /></span>
              <span className="uact-kpi-val">{val}</span>
              <span className="uact-kpi-lbl">{label}</span>
              {sub ? <span className="uact-kpi-sub">{sub}</span> : null}
            </div>
          )
          const tbl = (head, body) => (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>{head}</tr></thead><tbody>{body}</tbody>
            </table></div>
          )
          const empty = (n) => <tr><td colSpan={n} className="sys-muted">—</td></tr>
          const sqlCell = (s) => <td className="sys-mono sys-small" style={{ maxWidth: 440, whiteSpace: 'normal', wordBreak: 'break-word' }}>{s || '—'}</td>
          const sc = (arr) => (dd?.[arr] || [])
          return (
            <div className="uact-exec">
              {/* Hero */}
              <div className="uact-hero">
                <div className="uact-hero-title">
                  <span className="uact-hero-eyebrow">{t('health.dbTitle')}</span>
                  <span className="uact-hero-h">{t('db.heroTitle')}</span>
                </div>
                <div className="uact-hero-live" style={{ cursor: 'default' }}>
                  <Database size={14} />{sum.db_size || '—'} · {sum.active_connections ?? '—'} {t('db.conn')}
                  {pgss && <span className="show-badge show-badge-port" style={{ marginLeft: 6 }}>pg_stat_statements</span>}
                </div>
              </div>

              {/* Aralık */}
              <div className="chart-range-bar" style={{ alignItems: 'center' }}>
                {[1, 7, 30].map(d => (
                  <button key={d} type="button" className={`chart-range-btn ${dbDays === d ? 'chart-range-btn-active' : ''}`}
                    onClick={() => setDbDays(d)}>{t(`uact.range${d}d`)}</button>
                ))}
                {dbLoading && <Spinner size={14} inline decorative />}
              </div>

              {/* KPI — kartlara tıkla → detay modalı (veriler dbData içinden) */}
              <div className="uact-kpi-grid">
                {kpi2('q', Database, sum.queries ?? 0, t('db.kpiQueries'), winLbl, undefined, () => setDbKpiDetail({ title: t('db.kpiQueries'), kind: 'queries' }))}
                {kpi2('avg', Cpu, (sum.avg_ms ?? 0) + ' ms', t('db.kpiAvg'), winLbl, undefined, () => setDbKpiDetail({ title: t('db.kpiAvg'), kind: 'queries', sort: 'dur' }))}
                {kpi2('slow', Server, (sum.max_ms ?? 0) + ' ms', t('db.kpiSlowest'), winLbl, undefined, () => setDbKpiDetail({ title: t('db.kpiSlowest'), kind: 'slowest' }))}
                {kpi2('fail', XCircle, sum.failed ?? 0, t('db.kpiFailed'), winLbl, (sum.failed ?? 0) > 0 ? 'danger' : undefined, () => setDbKpiDetail({ title: t('db.kpiFailed'), kind: 'failed' }))}
                {kpi2('conn', Globe, sum.active_connections ?? '—', t('db.connActive'), t('db.kpiNow'), 'ok', () => setDbKpiDetail({ title: t('db.connActive'), kind: 'connections' }))}
                {kpi2('size', Database, sum.db_size || '—', t('db.connSize'), t('db.kpiNow'), undefined, () => setDbKpiDetail({ title: t('db.connSize'), kind: 'sizes' }))}
              </div>

              {!dd ? <div className="sys-muted sys-small" style={{ padding: '8px 2px' }}>{t('sys.loading')}</div> : (
                <>
                  {/* Sorgu zaman serisi */}
                  <h3 className="metrics-title">{t('db.secSeries')}</h3>
                  <div className="metrics-grid">
                    <MiniChart label={t('db.queriesPer')} unit="" color="#4f9cf9" gran={gran}
                      data={sc('series').map(b => ({ ts: b.ts, value: b.count }))}
                      onClick={() => setModalChart({ label: t('db.queriesPer'), unit: '', color: '#4f9cf9', gran, data: sc('series').map(b => ({ ts: b.ts, value: b.count })) })} />
                    <MiniChart label={t('db.avgMsPer')} unit=" ms" color="#f59e0b" gran={gran}
                      data={sc('series').map(b => ({ ts: b.ts, value: b.avg_ms }))}
                      onClick={() => setModalChart({ label: t('db.avgMsPer'), unit: ' ms', color: '#f59e0b', gran, data: sc('series').map(b => ({ ts: b.ts, value: b.avg_ms })) })} />
                  </div>

                  {/* Top kullanıcılar */}
                  <h3 className="metrics-title">{t('db.secTopUsers')}</h3>
                  {tbl(<>
                    <th className="dbtcol-th">{t('uact.colUser')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colQueries')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colAvgMs')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colFailed')}</th>
                    <th className="dbtcol-th">{t('uact.colLastLogin')}</th>
                  </>, sc('top_users').length ? sc('top_users').map((r, i) => (
                    <tr key={i}>
                      <td className="sys-mono">{r.username}</td>
                      <td className="dbtcol-num-cell">{r.queries}</td>
                      <td className="dbtcol-num-cell">{r.avg_ms}</td>
                      <td className={`dbtcol-num-cell ${r.failed > 0 ? 'sys-err-text' : ''}`}>{r.failed || 0}</td>
                      <td className="sys-mono sys-small">{r.last ? formatDateSec(r.last) : '—'}</td>
                    </tr>
                  )) : empty(5))}

                  {/* Top SQL */}
                  <h3 className="metrics-title">{t('db.secTopSql')}<span className="sys-muted sys-small" style={{ fontWeight: 500, letterSpacing: 0, textTransform: 'none' }}>· {pgss ? t('db.srcPgss') : t('db.srcPlayground')}</span></h3>
                  {tbl(<>
                    <th className="dbtcol-th">SQL</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colCalls')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colAvgMs')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colMaxMs')}</th>
                  </>, sc('top_sql').length ? sc('top_sql').map((r, i) => (
                    <tr key={i}>{sqlCell(r.sql)}
                      <td className="dbtcol-num-cell">{r.calls}</td>
                      <td className="dbtcol-num-cell">{r.avg_ms}</td>
                      <td className="dbtcol-num-cell">{r.max_ms}</td>
                    </tr>
                  )) : empty(4))}

                  {/* En yavaş sorgular */}
                  <h3 className="metrics-title">{t('db.secSlowest')}</h3>
                  {pgss ? tbl(<>
                    <th className="dbtcol-th">SQL</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colAvgMs')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colMaxMs')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colCalls')}</th>
                  </>, sc('slowest_sql').length ? sc('slowest_sql').map((r, i) => (
                    <tr key={i}>{sqlCell(r.sql)}
                      <td className="dbtcol-num-cell">{r.avg_ms}</td>
                      <td className="dbtcol-num-cell">{r.max_ms}</td>
                      <td className="dbtcol-num-cell">{r.calls}</td>
                    </tr>
                  )) : empty(4)) : tbl(<>
                    <th className="dbtcol-th">SQL</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colDurMs')}</th>
                    <th className="dbtcol-th">{t('uact.colUser')}</th>
                    <th className="dbtcol-th">{t('uact.colTime')}</th>
                  </>, sc('slowest_sql').length ? sc('slowest_sql').map((r, i) => (
                    <tr key={i}>{sqlCell(r.sql)}
                      <td className="dbtcol-num-cell">{r.duration_ms}</td>
                      <td className="sys-mono">{r.username || '—'}</td>
                      <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                    </tr>
                  )) : empty(4))}

                  {/* Hatalı sorgular */}
                  <h3 className="metrics-title">{t('db.secFailed')}</h3>
                  {tbl(<>
                    <th className="dbtcol-th">{t('uact.colTime')}</th>
                    <th className="dbtcol-th">{t('uact.colUser')}</th>
                    <th className="dbtcol-th">SQL</th>
                    <th className="dbtcol-th">{t('uact.colReason')}</th>
                  </>, sc('failed').length ? sc('failed').map((r, i) => (
                    <tr key={i}>
                      <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                      <td className="sys-mono">{r.username || '—'}</td>
                      {sqlCell(r.sql)}
                      <td className="sys-small sys-err-text">{r.error || '—'}</td>
                    </tr>
                  )) : empty(4))}

                  {/* En çok kullanılan tablolar */}
                  <h3 className="metrics-title">{t('db.secTopTables')}</h3>
                  {tbl(<>
                    <th className="dbtcol-th">{t('health.dbTable')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colReads')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('db.colWrites')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('health.dbRows')}</th>
                  </>, sc('top_tables').length ? sc('top_tables').map((r, i) => (
                    <tr key={i}>
                      <td className="sys-mono">{r.table_name}</td>
                      <td className="dbtcol-num-cell">{Number(r.reads ?? 0).toLocaleString()}</td>
                      <td className="dbtcol-num-cell">{Number(r.writes ?? 0).toLocaleString()}</td>
                      <td className="dbtcol-num-cell">{Number(r.row_count ?? 0).toLocaleString()}</td>
                    </tr>
                  )) : empty(4))}

                  {/* Tablo boyutları */}
                  <h3 className="metrics-title">{t('db.secSizes')}</h3>
                  {tbl(<>
                    <th className="dbtcol-th">{t('health.dbTable')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('health.dbRows')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('health.dbTableSize')}</th>
                    <th className="dbtcol-th dbtcol-th-num">{t('health.dbTotalSize')}</th>
                  </>, sc('table_sizes').length ? sc('table_sizes').map((r, i) => (
                    <tr key={i}>
                      <td className="sys-mono">{r.table_name}</td>
                      <td className="dbtcol-num-cell">{Number(r.row_count ?? 0).toLocaleString()}</td>
                      <td className="dbtcol-num-cell sys-muted">{r.table_size}</td>
                      <td className="dbtcol-num-cell">{r.total_size}</td>
                    </tr>
                  )) : empty(4))}

                  {/* Bağlantılar */}
                  <h3 className="metrics-title">{t('db.secConn')}</h3>
                  <div className="uact-kpi-grid">
                    {kpi2('ca', Globe, dd?.connections?.active ?? '—', t('db.connActive'))}
                    {kpi2('cmx', Globe, dd?.connections?.max ?? '—', t('db.connMax'))}
                    {kpi2('csz', Database, dd?.connections?.db_size || '—', t('db.connSize'))}
                    {kpi2('crp', Cpu, (dd?.connections?.response_ms ?? '—') + ' ms', t('db.connResp'))}
                  </div>
                </>
              )}
            </div>
          )
        })()}
      </div>
        )}
      </div>

      {/* Kullanıcı / Oturum izleme — yalnız yetkili (global admin/AUDIT) görür */}
      {canViewUserActivity && (
      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('users')}
          title={usersVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Users size={18} /></span>
          <span className="stats-collapse-label">{t('uact.section')}</span>
          {!usersVisible && (
            <span className="stats-collapse-hint">{t('health.sectionShow', t('uact.section'))}</span>
          )}
          <span className={`stats-collapse-chevron${usersVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {usersVisible && (
        <div className="metrics-section">
          {loadErrors.users && <div className="sys-msg">{t('uact.loadError')}</div>}
          {userActivity && (() => {
            const ua = userActivity
            const sum = ua.summary || {}
            const active = ua.active_users || []
            const anomalies = ua.anomalies || {}
            const roleTeam = ua.role_team || {}
            const heatmaps = ua.heatmaps || []
            const dayLabels = t('uact.weekdays').split(',')
            const fmtHeatRange = (hm) => {
              if (!hm.from || !hm.to) return ''
              const from = new Date(hm.from + 'Z')
              const endIncl = new Date(new Date(hm.to + 'Z').getTime() - 86_400_000) // to exclusive → son gün
              const f = d => d.toLocaleDateString([], { day: '2-digit', month: '2-digit' })
              return `${f(from)} – ${f(endIncl)}`
            }
            const weekBase = (i) => i === 0 ? t('uact.weekThis') : i === 1 ? t('uact.weekPrev1') : i === 2 ? t('uact.weekPrev2') : t('uact.weekPrev3')
            const uSort = (col) => setUactSort(s => s.col === col
              ? { col, dir: s.dir === 'asc' ? 'desc' : 'asc' }
              : { col, dir: col === 'username' ? 'asc' : 'desc' })
            const uArrow = (col) => uactSort.col === col ? (uactSort.dir === 'asc' ? ' ↑' : ' ↓') : ''
            const sortedActive = [...active].sort((a, b) => {
              const av = a[uactSort.col], bv = b[uactSort.col]
              const cmp = typeof av === 'number' ? av - bv : String(av ?? '').localeCompare(String(bv ?? ''))
              return uactSort.dir === 'asc' ? cmp : -cmp
            })
            const kpi = (key, Icon, val, label, sub, variant, onClick) => (
              <div key={key}
                className={`uact-kpi${variant ? ' uact-kpi--' + variant : ''}${onClick ? ' is-clickable' : ''}`}
                onClick={onClick} title={onClick ? t('uact.detailHint') : undefined}>
                <span className="uact-kpi-icon"><Icon size={16} /></span>
                <span className="uact-kpi-val">{val}</span>
                <span className="uact-kpi-lbl">{label}</span>
                {sub ? <span className="uact-kpi-sub">{sub}</span> : null}
              </div>
            )
            return (
              <div className="uact-exec">
                {/* Hero özet bandı */}
                <div className="uact-hero">
                  <div className="uact-hero-title">
                    <span className="uact-hero-eyebrow">{t('uact.section')}</span>
                    <span className="uact-hero-h">{t('uact.heroTitle')}</span>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <button type="button" className="uact-hero-live" onClick={() => setShowActiveList(true)} title={t('uact.activeCardHint')}>
                      <span className="uact-hero-dot" />{sum.active_count ?? 0} {t('uact.activeNow')}
                    </button>
                    <button type="button" className="sys-card-refresh-btn" onClick={refreshUserActivity} disabled={uactRefreshing} title={t('uact.refresh')}>
                      <span className={uactRefreshing ? 'spin' : ''}>↻</span>
                    </button>
                  </div>
                </div>

                {/* KPI özet kartları */}
                <div className="uact-kpi-grid">
                  {kpi('active', Users, sum.active_count ?? 0, t('uact.activeNow'), t('uact.kpiLive'), 'ok', () => setShowActiveList(true))}
                  {kpi('logins', LogIn, sum.logins_24h ?? 0, t('uact.logins24h'), t('uact.kpi24h'), undefined, () => setKpiDetail({ title: t('uact.logins24h'), kind: 'logins' }))}
                  {kpi('failed', XCircle, sum.failed_24h ?? 0, t('uact.failed24h'), t('uact.kpi24h'), (sum.failed_24h ?? 0) > 0 ? 'danger' : undefined, () => setKpiDetail({ title: t('uact.failed24h'), kind: 'failed' }))}
                  {kpi('anom', ShieldAlert, sum.anomalies_24h ?? 0, t('uact.anomalies24h'), t('uact.kpi24h'), (sum.anomalies_24h ?? 0) > 0 ? 'danger' : undefined, () => setKpiDetail({ title: t('uact.anomalies24h'), kind: 'anomalies' }))}
                  {kpi('uniq', UserCheck, sum.unique_users_24h ?? 0, t('uact.uniqueUsers24h'), t('uact.kpi24h'), undefined, () => setKpiDetail({ title: t('uact.uniqueUsers24h'), kind: 'unique_users' }))}
                </div>

                {/* Login trendi — esnek aralık (1g/7g/30g) + istenen güne gitme (saatlik) + zoom */}
                <h3 className="metrics-title">{t('uact.trendTitle')}</h3>
                <div className="chart-range-bar" style={{ alignItems: 'center', flexWrap: 'wrap' }}>
                  {['1h', '6h'].map(h => (
                    <button key={h} type="button"
                      className={`chart-range-btn ${trendPreset === h ? 'chart-range-btn-active' : ''}`}
                      onClick={() => { setTrendDate(''); setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(h) }}>
                      {t(`uact.range${h}`)}
                    </button>
                  ))}
                  {[1, 7, 30].map(d => (
                    <button key={d} type="button"
                      className={`chart-range-btn ${!trendDate && !trendCustom && !trendPreset && trendDays === d ? 'chart-range-btn-active' : ''}`}
                      onClick={() => { setTrendDate(''); setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(null); setTrendDays(d) }}>
                      {t(`uact.range${d}d`)}
                    </button>
                  ))}
                  <span className="sys-muted sys-small">·</span>
                  <DateTimeField dateOnly clearable className="dtf-inline" value={trendDate}
                    onChange={(v) => { setTrendCustom(null); setTrendShowCustom(false); setTrendPreset(null); setTrendDate(v) }}
                    placeholder={t('uact.gotoDay')} />
                  <button type="button" className={`chart-range-btn ${trendCustom ? 'chart-range-btn-active' : ''}`}
                    onClick={() => setTrendShowCustom(s => !s)}>{t('chart.custom')}</button>
                  {trendLoading && <Spinner size={14} inline decorative />}
                </div>
                {trendShowCustom && (
                  <div style={{ margin: '8px 0' }}>
                    <DateTimeRangePicker
                      from={trendCustom ? new Date(trendCustom.from + 'Z') : new Date(Date.now() - 7 * 86_400_000)}
                      to={trendCustom ? new Date(trendCustom.to + 'Z') : new Date()}
                      onApply={(f, to) => { setTrendDate(''); setTrendDays(7); setTrendPreset(null); setTrendCustom({ from: f.toISOString().slice(0, 19), to: to.toISOString().slice(0, 19) }) }} />
                  </div>
                )}
                {(() => {
                  const buckets = trendData?.buckets || []
                  const gran = trendData?.granularity || 'day'
                  return buckets.length === 0
                    ? <div className="sys-muted sys-small" style={{ padding: '8px 2px' }}>{t('uact.noLogins')}</div>
                    : <Suspense fallback={<div className="sys-muted sys-small" style={{ padding: 8 }}>…</div>}>
                        <LoginActivityChart buckets={buckets} gran={gran} />
                      </Suspense>
                })()}

                {/* Peak ısı haritası — tek hafta (BÜYÜK) + hafta navigasyonu (geriye 3 hafta) */}
                <h3 className="metrics-title">{t('uact.peakTitle')}</h3>
                {heatmaps.length > 0 ? (() => {
                  const wi = Math.min(weekIdx, heatmaps.length - 1)
                  const hm = heatmaps[wi]
                  const navBtn = (disabled) => ({
                    padding: '5px 14px', fontSize: '.82rem', fontWeight: 600, borderRadius: 6,
                    border: '1px solid var(--border, #cbd5e1)', background: 'var(--bg-subtle, #f8fafc)',
                    cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.4 : 1,
                  })
                  const olderDisabled = wi >= heatmaps.length - 1   // daha eski yok
                  const newerDisabled = wi <= 0                     // bu haftadan yenisi yok
                  return (
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginBottom: 8 }}>
                        <button type="button" disabled={olderDisabled} style={navBtn(olderDisabled)}
                          onClick={() => setWeekIdx(Math.min(heatmaps.length - 1, wi + 1))}>← {t('uact.prevWeek')}</button>
                        <button type="button" disabled={newerDisabled} style={navBtn(newerDisabled)}
                          onClick={() => setWeekIdx(Math.max(0, wi - 1))}>{t('uact.nextWeek')} →</button>
                      </div>
                      <LoginHeatmap matrix={hm.matrix || []} failed={hm.failed || []} max={hm.max || 0}
                        dayLabels={dayLabels} title={weekBase(wi)} hourLabel={fmtHeatRange(hm)}
                        todayDow={hm.today_dow ?? -1} rowTotals={hm.row_totals || []} colTotals={hm.col_totals || []} total={hm.total || 0}
                        onCellClick={(weekday, hour) => setHeatCell({ weekday, hour, cells: hm.cells || {}, label: `${weekBase(wi)} · ${fmtHeatRange(hm)}` })} />
                    </div>
                  )
                })() : <div className="sys-muted sys-small">{t('uact.noLogins')}</div>}

                {/* Aktif kullanıcılar */}
                <h3 className="metrics-title">{t('uact.activeListTitle')} ({active.length})</h3>
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th" onClick={() => uSort('username')}>{t('uact.colUser')}<span className="dbt-arrow">{uArrow('username')}</span></th>
                      <th className="dbtcol-th">{t('uact.colRole')}</th>
                      <th className="dbtcol-th">{t('uact.colTeam')}</th>
                      <th className="dbtcol-th" onClick={() => uSort('login_at')}>{t('uact.colLoginAt')}<span className="dbt-arrow">{uArrow('login_at')}</span></th>
                      <th className="dbtcol-th dbtcol-th-num" onClick={() => uSort('duration_min')}>{t('uact.colDuration')}<span className="dbt-arrow">{uArrow('duration_min')}</span></th>
                      <th className="dbtcol-th">{t('uact.colLocation')}</th>
                      <th className="dbtcol-th">{t('uact.colBrowser')}</th>
                      {isAdmin && <th className="dbtcol-th">{t('uact.colAction')}</th>}
                    </tr></thead>
                    <tbody>
                      {sortedActive.length === 0 && (
                        <tr><td colSpan={isAdmin ? 8 : 7} className="sys-muted">{t('uact.noActive')}</td></tr>
                      )}
                      {sortedActive.map(u => (
                        <tr key={u.username} className="uact-row-click" style={{ cursor: 'pointer' }}
                          title={t('uact.detailHint')} onClick={() => setSessionDetail(u)}>
                          <td><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /></td>
                          <td>{u.system_role || '—'}</td>
                          <td>{u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : '—'}</td>
                          <td className="sys-mono sys-small">{u.login_at ? formatDateSec(u.login_at) : '—'}</td>
                          <td className="dbtcol-num-cell">{fmtMins(u.duration_min)}</td>
                          <td className="sys-small">{u.ip ? <span className="sys-mono">{u.ip}</span> : '—'}{u.ip ? <span className="sys-muted"> {locStr(u.country, u.city)}</span> : null}</td>
                          <td className="sys-small">{shortUa(u.user_agent)}</td>
                          {isAdmin && (
                            <td>
                              <button className="health-db-refresh-btn" disabled={terminatingUser === u.username}
                                onClick={(e) => { e.stopPropagation(); handleTerminateSession(u.username) }}>
                                {terminatingUser === u.username ? t('uact.terminating') : t('uact.terminate')}
                              </button>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {/* Top kullanıcılar */}
                <h3 className="metrics-title">{t('uact.topUsersTitle')}</h3>
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th">{t('uact.colUser')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.colLogins')}</th>
                      <th className="dbtcol-th">{t('uact.colLastLogin')}</th>
                    </tr></thead>
                    <tbody>
                      {(ua.top_users || []).map(r => (
                        <tr key={r.username}>
                          <td><UserBadge username={r.username} userId={r.user_id} displayName={r.display_name} /></td>
                          <td className="dbtcol-num-cell">{r.logins}</td>
                          <td className="sys-mono sys-small">{r.last_login ? formatDateSec(r.last_login) : '—'}</td>
                        </tr>
                      ))}
                      {(ua.top_users || []).length === 0 && <tr><td colSpan={3} className="sys-muted">—</td></tr>}
                    </tbody>
                  </table>
                </div>

                {/* Giriş durumu — TÜM kullanıcılar. Yukarıdaki tablolar audit penceresinden
                    (7 gün) beslenir ve 180 günlük budamaya tabidir; bu tablo kullanıcı satırından
                    okunduğu için "hiç girmemiş" ve "şu anda parolası deneniyor" hesapları da gösterir. */}
                <h3 className="metrics-title">{t('uact.loginStatusTitle')}</h3>
                <div className="sys-muted sys-small">{t('uact.loginStatusHint')}</div>
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th">{t('uact.colUser')}</th>
                      <th className="dbtcol-th">{t('uact.colLastLogin')}</th>
                      <th className="dbtcol-th">{t('uact.colPrevLogin')}</th>
                      <th className="dbtcol-th">{t('uact.colLastFailed')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.colFailedCount')}</th>
                    </tr></thead>
                    <tbody>
                      {(ua.login_status || []).map(r => (
                        <tr key={r.username} className="uact-row-click" style={{ cursor: 'pointer' }}
                            title={t('uact.detailHint')} onClick={() => setSessionDetail(r)}>
                          <td><UserBadge username={r.username} userId={r.user_id} displayName={r.display_name} /></td>
                          <td className="sys-mono sys-small">{r.last_login_at ? formatDateSec(r.last_login_at) : '—'}</td>
                          <td className="sys-mono sys-small">{r.prev_login_at ? formatDateSec(r.prev_login_at) : '—'}</td>
                          <td className="sys-mono sys-small">{r.last_failed_at ? formatDateSec(r.last_failed_at) : '—'}</td>
                          <td className="dbtcol-num-cell">{r.failed_since_login ?? 0}</td>
                        </tr>
                      ))}
                      {(ua.login_status || []).length === 0 && <tr><td colSpan={5} className="sys-muted">—</td></tr>}
                    </tbody>
                  </table>
                </div>

                {/* Top kaynaklar */}
                <h3 className="metrics-title">{t('uact.topSourcesTitle')}</h3>
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th">{t('uact.colIp')}</th>
                      <th className="dbtcol-th">{t('uact.colLocation')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.colTotal')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.success')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.failed')}</th>
                    </tr></thead>
                    <tbody>
                      {(ua.top_sources || []).map(r => (
                        <tr key={r.ip}>
                          <td className="sys-mono">{r.ip}{r.reverse_dns && <span className="sys-small sys-muted" style={{ display: 'block' }}>{r.reverse_dns}</span>}</td>
                          <td className="sys-small">{locStr(r.country, r.city)}</td>
                          <td className="dbtcol-num-cell">{r.total}</td>
                          <td className="dbtcol-num-cell sys-ok-text">{r.success}</td>
                          <td className="dbtcol-num-cell sys-err-text">{r.failed}</td>
                        </tr>
                      ))}
                      {(ua.top_sources || []).length === 0 && <tr><td colSpan={5} className="sys-muted">—</td></tr>}
                    </tbody>
                  </table>
                </div>

                {/* Anomaliler */}
                <h3 className="metrics-title">{t('uact.anomaliesTitle')} ({anomalies.total ?? 0})</h3>
                <div className="uact-kpi-grid">
                  {['OFF_HOURS', 'UNUSUAL_IP', 'GEO_VELOCITY', 'BRUTE_FORCE', 'RATE_LIMITED'].map(k =>
                    kpi(k, ShieldAlert, anomalies.counts?.[k] ?? 0, t(`uact.anom_${k}`), null,
                        (anomalies.counts?.[k] ?? 0) > 0 ? 'danger' : undefined))}
                </div>
                {(anomalies.recent || []).length > 0 && (
                  <div className="health-table-wrap">
                    <table className="health-dbtable">
                      <thead><tr>
                        <th className="dbtcol-th">{t('uact.colTime')}</th>
                        <th className="dbtcol-th">{t('uact.colUser')}</th>
                        <th className="dbtcol-th">{t('uact.colIp')}</th>
                        <th className="dbtcol-th">{t('uact.colLocation')}</th>
                        <th className="dbtcol-th">{t('uact.colFlags')}</th>
                        <th className="dbtcol-th">{t('uact.colOutcome')}</th>
                      </tr></thead>
                      <tbody>
                        {anomalies.recent.map((r, i) => (
                          <tr key={i}>
                            <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                            <td>{r.actor ? <UserBadge username={r.actor} inline size="sm" /> : '—'}</td>
                            <td className="sys-mono sys-small">{r.ip || '—'}</td>
                            <td className="sys-small">{locStr(r.country, r.city)}</td>
                            <td className="sys-small">{r.flags || '—'}</td>
                            <td className="sys-small">{r.outcome || '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                {/* Rol / Takım kırılımı */}
                <h3 className="metrics-title">{t('uact.roleTeamTitle')}</h3>
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th">{t('uact.colRole')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.colLogins')}</th>
                    </tr></thead>
                    <tbody>
                      {(roleTeam.by_role || []).map(r => (
                        <Fragment key={r.role}>
                          <tr style={r.users?.length ? { cursor: 'pointer' } : undefined}
                            onClick={r.users?.length ? () => setExpRole(x => x === r.role ? null : r.role) : undefined}>
                            <td>{r.users?.length ? (expRole === r.role ? '▾ ' : '▸ ') : ''}{r.role}</td>
                            <td className="dbtcol-num-cell">{r.count}</td>
                          </tr>
                          {expRole === r.role && r.users?.length > 0 && (
                            <tr><td colSpan={2} style={{ padding: '4px 10px', background: 'var(--bg-subtle, #f8fafc)' }}>
                              {r.users.map(u => <div key={u.username} style={{ padding: '3px 0' }}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} count={u.count} /></div>)}
                            </td></tr>
                          )}
                        </Fragment>
                      ))}
                      {(roleTeam.by_role || []).length === 0 && <tr><td colSpan={2} className="sys-muted">—</td></tr>}
                    </tbody>
                  </table>
                </div>
                <div className="health-table-wrap">
                  <table className="health-dbtable">
                    <thead><tr>
                      <th className="dbtcol-th">{t('uact.colTeam')}</th>
                      <th className="dbtcol-th dbtcol-th-num">{t('uact.colLogins')}</th>
                    </tr></thead>
                    <tbody>
                      {(roleTeam.by_team || []).map((r, i) => (
                        <Fragment key={i}>
                          <tr style={r.users?.length ? { cursor: 'pointer' } : undefined}
                            onClick={r.users?.length ? () => setExpTeam(x => x === i ? null : i) : undefined}>
                            <td>{r.users?.length ? (expTeam === i ? '▾ ' : '▸ ') : ''}{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : '—'}</td>
                            <td className="dbtcol-num-cell">{r.count}</td>
                          </tr>
                          {expTeam === i && r.users?.length > 0 && (
                            <tr><td colSpan={2} style={{ padding: '4px 10px', background: 'var(--bg-subtle, #f8fafc)' }}>
                              {r.users.map(u => <div key={u.username} style={{ padding: '3px 0' }}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} count={u.count} /></div>)}
                            </td></tr>
                          )}
                        </Fragment>
                      ))}
                      {(roleTeam.by_team || []).length === 0 && <tr><td colSpan={2} className="sys-muted">—</td></tr>}
                    </tbody>
                  </table>
                </div>
              </div>
            )
          })()}
        </div>
        )}
      </div>
      )}

      <p className="sys-refresh-note">↻ {t('sys.autoRefresh')}</p>

      <ChartModal chart={modalChart} onClose={() => setModalChart(null)} />

      {httpExpOpen && (
        <div className="chart-modal-overlay" onClick={e => { if (e.target === e.currentTarget) setHttpExpOpen(false) }}>
          <div className="chart-modal chart-modal--wide" role="dialog" aria-modal="true">
            <div className="chart-modal-hdr">
              <h2 className="chart-modal-title">{t('http.exp.title')}</h2>
              <button className="chart-modal-close" onClick={() => setHttpExpOpen(false)} aria-label={t('app.close')}>✕</button>
            </div>
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />}><HttpMetricsExplorer /></Suspense>
          </div>
        </div>
      )}

      {/* Isı haritası hücresi → o hafta-günü/saatteki girişler */}
      {heatCell && (() => {
        const labels = t('uact.weekdays').split(',')
        const key = `${heatCell.weekday}-${heatCell.hour}`
        const list = (heatCell.cells && heatCell.cells[key]) || []
        const hh = String(heatCell.hour).padStart(2, '0')
        return (
          <div className="modal-overlay" onClick={() => setHeatCell(null)}>
            <div className="modal-box modal-show" onClick={e => e.stopPropagation()}>
              <div className="show-header">
                <div className="show-header-title">
                  <span className="show-domain">{labels[heatCell.weekday] || ''} {hh}:00–{hh}:59</span>
                  {heatCell.label && <span className="sys-muted sys-small">{heatCell.label}</span>}
                  <span className="show-badge show-badge-port">{list.length}</span>
                </div>
                <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setHeatCell(null)}>✕</button>
              </div>
              <div className="show-body">
                {list.length === 0 ? (
                  <div className="sys-muted">{t('uact.noLogins')}</div>
                ) : (
                  <div className="health-table-wrap">
                    <table className="health-dbtable">
                      <thead><tr>
                        <th className="dbtcol-th">{t('uact.colTime')}</th>
                        <th className="dbtcol-th">{t('uact.colUser')}</th>
                        <th className="dbtcol-th">{t('uact.colIp')}</th>
                        <th className="dbtcol-th">{t('uact.colLocation')}</th>
                        <th className="dbtcol-th">{t('uact.colOutcome')}</th>
                        <th className="dbtcol-th">{t('uact.colReason')}</th>
                      </tr></thead>
                      <tbody>
                        {list.map((r, i) => (
                          <tr key={i}>
                            <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                            <td>{r.actor ? <UserBadge username={r.actor} inline size="sm" /> : '—'}</td>
                            <td className="sys-mono sys-small">{r.ip || '—'}</td>
                            <td className="sys-small">{locStr(r.country, r.city)}</td>
                            <td className="sys-small">{r.outcome === 'SUCCESS'
                              ? <span className="sys-ok-text">{r.outcome}</span>
                              : <span className="sys-err-text">{r.outcome || '—'}</span>}</td>
                            <td className="sys-small">{r.reason || '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          </div>
        )
      })()}

      {/* KPI kartı drill-down — Login/Başarısız/Anomali/Tekil kullanıcı (son 24 saat) */}
      {kpiDetail && (() => {
        const rows = userActivity?.details?.[kpiDetail.kind] || []
        const isUsers = kpiDetail.kind === 'unique_users'
        const isFailed = kpiDetail.kind === 'failed'
        const isAnom = kpiDetail.kind === 'anomalies'
        return (
          <div className="modal-overlay" onClick={() => setKpiDetail(null)}>
            <div className="modal-box modal-show" onClick={e => e.stopPropagation()}>
              <div className="show-header">
                <div className="show-header-title">
                  <span className="show-domain">{kpiDetail.title}</span>
                  <span className="sys-muted sys-small">{t('uact.kpi24h')}</span>
                  <span className="show-badge show-badge-port">{rows.length}</span>
                </div>
                <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setKpiDetail(null)}>✕</button>
              </div>
              <div className="show-body">
                {rows.length === 0 ? (
                  <div className="sys-muted">{t('uact.noLogins')}</div>
                ) : isUsers ? (
                  <div className="health-table-wrap">
                    <table className="health-dbtable">
                      <thead><tr>
                        <th className="dbtcol-th">{t('uact.colUser')}</th>
                        <th className="dbtcol-th dbtcol-th-num">{t('uact.colLogins')}</th>
                        <th className="dbtcol-th">{t('uact.colLastLogin')}</th>
                      </tr></thead>
                      <tbody>
                        {rows.map((r, i) => (
                          <tr key={i}>
                            <td className="sys-mono">{r.username}</td>
                            <td className="dbtcol-num-cell">{r.logins}</td>
                            <td className="sys-mono sys-small">{r.last_login ? formatDateSec(r.last_login) : '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="health-table-wrap">
                    <table className="health-dbtable">
                      <thead><tr>
                        <th className="dbtcol-th">{t('uact.colTime')}</th>
                        <th className="dbtcol-th">{t('uact.colUser')}</th>
                        <th className="dbtcol-th">{t('uact.colIp')}</th>
                        <th className="dbtcol-th">{t('uact.colLocation')}</th>
                        {isAnom && <th className="dbtcol-th">{t('uact.colFlags')}</th>}
                        {(isFailed || isAnom) && <th className="dbtcol-th">{t('uact.colOutcome')}</th>}
                        {isFailed && <th className="dbtcol-th">{t('uact.colReason')}</th>}
                      </tr></thead>
                      <tbody>
                        {rows.map((r, i) => (
                          <tr key={i}>
                            <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                            <td>{r.actor ? <UserBadge username={r.actor} inline size="sm" /> : '—'}</td>
                            <td className="sys-mono sys-small">{r.ip || '—'}</td>
                            <td className="sys-small">{locStr(r.country, r.city)}</td>
                            {isAnom && <td className="sys-small">{r.flags || '—'}</td>}
                            {(isFailed || isAnom) && <td className="sys-small">{r.outcome === 'SUCCESS'
                              ? <span className="sys-ok-text">{r.outcome}</span>
                              : <span className="sys-err-text">{r.outcome || '—'}</span>}</td>}
                            {isFailed && <td className="sys-small">{r.reason || '—'}</td>}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          </div>
        )
      })()}

      {/* Veritabanı Analitiği KPI kartı → detay modalı (veriler dbData içinden) */}
      {dbKpiDetail && (() => {
        const dd = dbData
        const k = dbKpiDetail.kind
        const pgss = !!dd?.summary?.pgss
        const winLbl = dbDays === 1 ? t('uact.range1d') : dbDays === 7 ? t('uact.range7d') : t('uact.range30d')
        const wrapStyle = { maxWidth: 440, whiteSpace: 'normal', wordBreak: 'break-word' }
        let count = 0
        let body = <div className="sys-muted">—</div>
        if (k === 'queries') {
          let rows = dd?.recent_queries || []
          if (dbKpiDetail.sort === 'dur') rows = [...rows].sort((a, b) => (b.duration_ms ?? 0) - (a.duration_ms ?? 0))
          count = rows.length
          if (rows.length) body = (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">{t('uact.colTime')}</th>
                <th className="dbtcol-th">{t('uact.colUser')}</th>
                <th className="dbtcol-th">SQL</th>
                <th className="dbtcol-th dbtcol-th-num">{t('db.colDurMs')}</th>
              </tr></thead>
              <tbody>{rows.map((r, i) => (
                <tr key={i}>
                  <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                  <td className="sys-mono">{r.username || '—'}</td>
                  <td className={`sys-mono sys-small ${r.success === false ? 'sys-err-text' : ''}`} style={wrapStyle}>{r.sql || '—'}</td>
                  <td className="dbtcol-num-cell">{r.duration_ms ?? '—'}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )
        } else if (k === 'slowest') {
          const rows = dd?.slowest_sql || []
          count = rows.length
          if (rows.length) body = pgss ? (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">SQL</th>
                <th className="dbtcol-th dbtcol-th-num">{t('db.colAvgMs')}</th>
                <th className="dbtcol-th dbtcol-th-num">{t('db.colMaxMs')}</th>
                <th className="dbtcol-th dbtcol-th-num">{t('db.colCalls')}</th>
              </tr></thead>
              <tbody>{rows.map((r, i) => (
                <tr key={i}>
                  <td className="sys-mono sys-small" style={wrapStyle}>{r.sql || '—'}</td>
                  <td className="dbtcol-num-cell">{r.avg_ms}</td>
                  <td className="dbtcol-num-cell">{r.max_ms}</td>
                  <td className="dbtcol-num-cell">{r.calls}</td>
                </tr>
              ))}</tbody>
            </table></div>
          ) : (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">SQL</th>
                <th className="dbtcol-th dbtcol-th-num">{t('db.colDurMs')}</th>
                <th className="dbtcol-th">{t('uact.colUser')}</th>
                <th className="dbtcol-th">{t('uact.colTime')}</th>
              </tr></thead>
              <tbody>{rows.map((r, i) => (
                <tr key={i}>
                  <td className="sys-mono sys-small" style={wrapStyle}>{r.sql || '—'}</td>
                  <td className="dbtcol-num-cell">{r.duration_ms}</td>
                  <td className="sys-mono">{r.username || '—'}</td>
                  <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )
        } else if (k === 'failed') {
          const rows = dd?.failed || []
          count = rows.length
          if (rows.length) body = (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">{t('uact.colTime')}</th>
                <th className="dbtcol-th">{t('uact.colUser')}</th>
                <th className="dbtcol-th">SQL</th>
                <th className="dbtcol-th">{t('uact.colReason')}</th>
              </tr></thead>
              <tbody>{rows.map((r, i) => (
                <tr key={i}>
                  <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                  <td className="sys-mono">{r.username || '—'}</td>
                  <td className="sys-mono sys-small" style={wrapStyle}>{r.sql || '—'}</td>
                  <td className="sys-small sys-err-text">{r.error || '—'}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )
        } else if (k === 'sizes') {
          const rows = dd?.table_sizes || []
          count = rows.length
          if (rows.length) body = (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">{t('health.dbTable')}</th>
                <th className="dbtcol-th dbtcol-th-num">{t('health.dbRows')}</th>
                <th className="dbtcol-th dbtcol-th-num">{t('health.dbTableSize')}</th>
                <th className="dbtcol-th dbtcol-th-num">{t('health.dbTotalSize')}</th>
              </tr></thead>
              <tbody>{rows.map((r, i) => (
                <tr key={i}>
                  <td className="sys-mono">{r.table_name}</td>
                  <td className="dbtcol-num-cell">{Number(r.row_count ?? 0).toLocaleString()}</td>
                  <td className="dbtcol-num-cell sys-muted">{r.table_size}</td>
                  <td className="dbtcol-num-cell">{r.total_size}</td>
                </tr>
              ))}</tbody>
            </table></div>
          )
        } else if (k === 'connections') {
          const c = dd?.connections || {}
          const items = [
            [t('db.connActive'), c.active ?? '—'],
            [t('db.connMax'), c.max ?? '—'],
            [t('db.connSize'), c.db_size || '—'],
            [t('db.connResp'), (c.response_ms ?? '—') + ' ms'],
          ]
          count = items.length
          body = (
            <div className="health-table-wrap"><table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">{t('db.connMetric')}</th>
                <th className="dbtcol-th dbtcol-th-num">{t('db.connValue')}</th>
              </tr></thead>
              <tbody>{items.map(([lbl, val], i) => (
                <tr key={i}><td className="sys-small">{lbl}</td><td className="dbtcol-num-cell sys-mono">{val}</td></tr>
              ))}</tbody>
            </table></div>
          )
        }
        return (
          <div className="modal-overlay" onClick={() => setDbKpiDetail(null)}>
            <div className="modal-box modal-show" onClick={e => e.stopPropagation()}>
              <div className="show-header">
                <div className="show-header-title">
                  <span className="show-domain">{dbKpiDetail.title}</span>
                  <span className="sys-muted sys-small">{winLbl}</span>
                  <span className="show-badge show-badge-port">{count}</span>
                </div>
                <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setDbKpiDetail(null)}>✕</button>
              </div>
              <div className="show-body">{body}</div>
            </div>
          </div>
        )
      })()}

      {/* "Aktif Oturum" kartı → oturumdaki kişiler listesi modalı */}
      {showActiveList && (() => {
        const list = userActivity?.active_users || []
        return (
          <div className="modal-overlay" onClick={() => setShowActiveList(false)}>
            <div className="modal-box modal-show" onClick={e => e.stopPropagation()}>
              <div className="show-header">
                <div className="show-header-title">
                  <span className="show-domain">{t('uact.activeListTitle')}</span>
                  <span className="show-badge show-badge-port">{list.length}</span>
                </div>
                <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setShowActiveList(false)}>✕</button>
              </div>
              <div className="show-body">
                {list.length === 0 ? (
                  <div className="sys-muted">{t('uact.noActive')}</div>
                ) : (
                  <div className="health-table-wrap">
                    <table className="health-dbtable">
                      <thead><tr>
                        <th className="dbtcol-th">{t('uact.colUser')}</th>
                        <th className="dbtcol-th">{t('uact.colRole')}</th>
                        <th className="dbtcol-th">{t('uact.colTeam')}</th>
                        <th className="dbtcol-th">{t('uact.colLoginAt')}</th>
                        <th className="dbtcol-th dbtcol-th-num">{t('uact.colDuration')}</th>
                        <th className="dbtcol-th">{t('uact.colLocation')}</th>
                      </tr></thead>
                      <tbody>
                        {list.map(u => (
                          <tr key={u.username} className="uact-row-click" style={{ cursor: 'pointer' }}
                            title={t('uact.detailHint')}
                            onClick={() => setSessionDetail(u)}>
                            <td><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /></td>
                            <td>{u.system_role || '—'}</td>
                            <td>{u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : '—'}</td>
                            <td className="sys-mono sys-small">{u.login_at ? formatDateSec(u.login_at) : '—'}</td>
                            <td className="dbtcol-num-cell">{fmtMins(u.duration_min)}</td>
                            <td className="sys-small">{u.ip ? <span className="sys-mono">{u.ip}</span> : '—'}{u.ip ? <span className="sys-muted"> {locStr(u.country, u.city)}</span> : null}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          </div>
        )
      })()}

      {/* Aktif oturum detay modalı — satıra tıklayınca tüm bilgiler */}
      {sessionDetail && (() => {
        const u = sessionDetail
        const field = (label, value, mono) => (
          <div className="show-field" key={label}>
            <span className="show-field-label">{label}</span>
            <span className={`show-field-value${mono ? ' show-field-mono' : ''}`}>{value || '—'}</span>
          </div>
        )
        return (
          <div className="modal-overlay" style={{ zIndex: 2100 }} onClick={() => setSessionDetail(null)}>
            <div className="modal-box modal-show" onClick={e => e.stopPropagation()}>
              <div className="show-header">
                <div className="show-header-title">
                  <span className="show-domain">{u.username}</span>
                  {u.system_role && <span className="show-badge show-badge-port">{u.system_role}</span>}
                </div>
                <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setSessionDetail(null)}>✕</button>
              </div>
              <div className="show-body">
                <div className="show-section-header">{t('uact.detailUser')}</div>
                <div className="show-grid-2">
                  {field(t('uact.colUser'), u.username, true)}
                  {field(t('uact.detailDisplayName'), u.display_name)}
                  {field(t('uact.detailEmail'), u.email, true)}
                  {field(t('uact.detailEmployeeId'), u.employee_id, true)}
                  {field(t('uact.colRole'), u.system_role)}
                  {field(t('uact.detailOrgRole'), u.org_role)}
                  {field(t('uact.colTeam'), u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : null)}
                </div>

                <div className="show-section-header">{t('uact.detailSession')}</div>
                <div className="show-grid-2">
                  {field(t('uact.colLoginAt'), u.login_at ? formatDateSec(u.login_at) : '—', true)}
                  {field(t('uact.colDuration'), fmtMins(u.duration_min))}
                  {field(t('uact.detailLastSeen'), u.last_seen ? formatDateSec(u.last_seen) : '—', true)}
                </div>

                {/* Giriş geçmişi — kullanıcı satırından (audit budamasından bağımsız). Alan adları
                    aktif-oturum satırlarıyla AYNI olduğu için bu modal iki listeyi de render eder. */}
                <div className="show-section-header">{t('uact.detailLoginHistory')}</div>
                <div className="show-grid-2">
                  {field(t('uact.colLastLogin'), u.last_login_at ? formatDateSec(u.last_login_at) : '—', true)}
                  {field(t('uact.detailLoginMethod'), u.last_login_method)}
                  {field(t('uact.colPrevLogin'), u.prev_login_at ? formatDateSec(u.prev_login_at) : '—', true)}
                  {field(t('uact.detailPrevIp'), u.prev_login_ip, true)}
                  {field(t('uact.colLastFailed'), u.last_failed_at ? formatDateSec(u.last_failed_at) : '—', true)}
                  {field(t('uact.detailFailedIp'), u.last_failed_ip, true)}
                  {field(t('uact.colFailedCount'), String(u.failed_since_login ?? 0))}
                </div>

                <div className="show-section-header">{t('uact.detailSource')}</div>
                <div className="show-grid-2">
                  {field(t('uact.colIp'), u.ip, true)}
                  {field(t('uact.colLocation'), locStr(u.country, u.city))}
                  {field(t('uact.detailOrg'), u.org)}
                  {field(t('uact.colBrowser'), shortUa(u.user_agent))}
                </div>
                <div className="show-field show-field-full">
                  <span className="show-field-label">{t('uact.detailUserAgent')}</span>
                  <span className="show-field-value show-field-mono">{u.user_agent || '—'}</span>
                </div>

                {isAdmin && (
                  <button className="health-db-refresh-btn" style={{ marginTop: 14 }}
                    disabled={terminatingUser === u.username}
                    onClick={() => { handleTerminateSession(u.username); setSessionDetail(null) }}>
                    {t('uact.terminate')}
                  </button>
                )}
              </div>
            </div>
          </div>
        )
      })()}

      {selectedLog && (
        <div className="smtp-detail-overlay" onClick={() => setSelectedLog(null)}>
          <div className="smtp-detail-panel" onClick={e => e.stopPropagation()}>
            <div className="smtp-detail-header">
              <div className="smtp-detail-header-left">
                <Mail size={17} className="smtp-detail-mail-icon" />
                <span>{t('health.emailDetail')}</span>
              </div>
              <button type="button" className="smtp-modal-close" aria-label={t('app.dismiss')} onClick={() => setSelectedLog(null)}>✕</button>
            </div>
            <div className="smtp-detail-meta">
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailFrom')}</span>
                <span className="sys-muted">{selectedLog.sender_email}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailTo')}</span>
                <span>
                  <strong>{selectedLog.recipient_name}</strong>
                  {selectedLog.recipient_email && (
                    <span className="sys-muted"> &lt;{selectedLog.recipient_email}&gt;</span>
                  )}
                </span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.smtpLogSubject')}</span>
                <span className="smtp-detail-subject">{selectedLog.subject}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.smtpLogDate')}</span>
                <span className="sys-mono">{formatDate(selectedLog.sent_at)}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailTrigger')}</span>
                <span className={`smtp-trigger-badge smtp-trigger-${selectedLog.trigger?.toLowerCase()}`}>
                  {triggerLabel(selectedLog.trigger, t)}
                </span>
                <SmtpStatusCell row={selectedLog} t={t} />
              </div>
            </div>
            <div className="smtp-detail-body-label">{t('health.emailDetailBody')}</div>
            <iframe
              className="smtp-detail-iframe"
              srcDoc={mailPreviewSrcDoc(
                selectedLog.message ?? `<p style="color:#9ca3af;font-family:sans-serif">${t('health.emailDetailNoBody')}</p>`,
                // Bu listede alarm SEVİYESİ taşınmıyor (yalnız trigger var) → CRITICAL mailler de
                // "warning" logosuyla önizlenir. Renk yaklaşık, logonun görünmesi kesin.
                { logoVariant: mailLogoVariant({ trigger: selectedLog.trigger }) },
              )}
              sandbox=""
              title={selectedLog.subject}
            />
          </div>
        </div>
      )}

      {hbModalOpen && (
        <HeartbeatHistoryModal onClose={() => setHbModalOpen(false)} />
      )}

      {/* Haftalık erişilebilirlik gönderim logları (SMTP status modalıyla aynı yapı) */}
      {waLogsModal && (
        <div className="smtp-modal-overlay" onClick={() => setWaLogsModal(false)}>
          <div className="smtp-modal" onClick={e => e.stopPropagation()}>
            <div className="smtp-modal-header">
              <h3>{t('waLogs.title')}</h3>
              <button type="button" className="smtp-modal-close" aria-label={t('app.dismiss')} onClick={() => setWaLogsModal(false)}>✕</button>
            </div>
            {waLogsLoading ? (
              <LoadingBlock label={t('sys.loading')} className="smtp-modal-loading" />
            ) : (waLogs?.length ?? 0) === 0 ? (
              <div className="smtp-modal-empty">{t('waLogs.empty')}</div>
            ) : (
              <div className="smtp-modal-body">
                <table className="smtp-log-table">
                  <thead>
                    <tr>
                      <th>{t('health.smtpLogDate')}</th>
                      <th>{t('waLogs.colTeam')}</th>
                      <th>{t('waLogs.colRecipients')}</th>
                      <th>{t('waLogs.colType')}</th>
                      <th>{t('health.smtpLogStatus')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {waLogs.map(row => (
                      <tr key={row.id} className="smtp-log-row" onClick={() => openWaItem(row)}>
                        <td className="smtp-log-date sys-mono">{formatDate(row.sent_at)}</td>
                        <td>{row.team || '—'}</td>
                        <td>
                          <div className="smtp-log-email sys-muted sys-small" title={row.to || ''}>{row.to || '—'}</div>
                          {row.cc && <div className="smtp-log-email sys-muted sys-small" title={row.cc}>CC: {row.cc}</div>}
                        </td>
                        <td><span className="sys-small">{row.trigger === 'WEEKLY_AVAILABILITY_TEST' ? t('waLogs.test') : t('waLogs.scheduled')}</span></td>
                        <td><SmtpStatusCell row={{ kind: waKind(row.status), error: row.status?.startsWith('FAILED') ? row.status : null }} t={t} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Haftalık erişilebilirlik mail gövdesi (satır detayı) */}
      {waLogItem && (
        <div className="smtp-detail-overlay" onClick={() => setWaLogItem(null)}>
          <div className="smtp-detail-panel" onClick={e => e.stopPropagation()}>
            <div className="smtp-detail-header">
              <div className="smtp-detail-header-left">
                <Mail size={17} className="smtp-detail-mail-icon" />
                <span>{t('waLogs.detailTitle')}</span>
              </div>
              <button type="button" className="smtp-modal-close" aria-label={t('app.dismiss')} onClick={() => setWaLogItem(null)}>✕</button>
            </div>
            <div className="smtp-detail-meta">
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.emailDetailTo')}</span>
                <span><strong>{waLogItem.team}</strong>{waLogItem.to && <span className="sys-muted"> &lt;{waLogItem.to}&gt;</span>}</span>
              </div>
              {waLogItem.cc && (
                <div className="smtp-detail-meta-row">
                  <span className="smtp-detail-label">CC</span>
                  <span className="sys-muted">{waLogItem.cc}</span>
                </div>
              )}
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.smtpLogSubject')}</span>
                <span className="smtp-detail-subject">{waLogItem.subject}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('health.smtpLogDate')}</span>
                <span className="sys-mono">{formatDate(waLogItem.sent_at)}</span>
              </div>
              <div className="smtp-detail-meta-row">
                <span className="smtp-detail-label">{t('waLogs.colType')}</span>
                <span className="sys-small">{waLogItem.trigger === 'WEEKLY_AVAILABILITY_TEST' ? t('waLogs.test') : t('waLogs.scheduled')}</span>
                <SmtpStatusCell row={{ kind: waKind(waLogItem.status), error: waLogItem.status?.startsWith('FAILED') ? waLogItem.status : null }} t={t} />
              </div>
            </div>
            <div className="smtp-detail-body-label">{t('health.emailDetailBody')}</div>
            {/* Haftalık erişilebilirlik raporu daima "ok" logo varyantıyla gönderilir (varsayılan). */}
            <iframe className="smtp-detail-iframe" srcDoc={mailPreviewSrcDoc(waLogItem.html)} sandbox="" title={waLogItem.subject} />
          </div>
        </div>
      )}

      {smtpModal && (
        <div className="smtp-modal-overlay" onClick={closeSmtpModal}>
          <div className="smtp-modal" onClick={e => e.stopPropagation()}>
            <div className="smtp-modal-header">
              <h3>{t('health.smtpLogsTitle')}</h3>
              <span className="smtp-modal-period">{t(`health.smtpPeriod${smtpPeriod}`)}</span>
              <button type="button" className="smtp-modal-close" aria-label={t('app.dismiss')} onClick={closeSmtpModal}>✕</button>
            </div>

            {smtpLoading ? (
              <LoadingBlock label={t('sys.loading')} className="smtp-modal-loading" />
            ) : smtpLogs?.length === 0 ? (
              <div className="smtp-modal-empty">{t('health.smtpNoErrors')}</div>
            ) : (
              <div className="smtp-modal-body">
                <table className="smtp-log-table">
                  <thead>
                    <tr>
                      <th>{t('health.smtpLogDate')}</th>
                      <th>{t('health.smtpLogDomain')}</th>
                      <th>{t('health.smtpLogFrom')}</th>
                      <th>{t('health.smtpLogTo')}</th>
                      <th>{t('health.smtpLogSubject')}</th>
                      <th>{t('health.smtpLogStatus')}</th>
                    </tr>
                    <tr className="smtp-log-filter-row">
                      <th />
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterDomain')}
                          value={smtpFilters.domain}
                          onChange={e => setSmtpFilters(s => ({ ...s, domain: e.target.value }))}
                        />
                      </th>
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterFrom')}
                          value={smtpFilters.from}
                          onChange={e => setSmtpFilters(s => ({ ...s, from: e.target.value }))}
                        />
                      </th>
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterTo')}
                          value={smtpFilters.to}
                          onChange={e => setSmtpFilters(s => ({ ...s, to: e.target.value }))}
                        />
                      </th>
                      <th>
                        <input
                          type="text"
                          placeholder={t('health.smtpFilterSubject')}
                          value={smtpFilters.subject}
                          onChange={e => setSmtpFilters(s => ({ ...s, subject: e.target.value }))}
                        />
                      </th>
                      <th>
                        <select
                          value={smtpFilters.status}
                          onChange={e => setSmtpFilters(s => ({ ...s, status: e.target.value }))}
                        >
                          <option value="">{t('health.smtpFilterStatusAll')}</option>
                          <option value="SENT">{t('health.smtpFilterStatusSent')}</option>
                          <option value="FAILED">{t('health.smtpFilterStatusFailed')}</option>
                          <option value="SKIPPED">{t('health.smtpFilterStatusSkipped')}</option>
                        </select>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredSmtpLogs?.length === 0 ? (
                      <tr className="smtp-log-empty-row">
                        <td colSpan={6}>{t('health.smtpLogNoMatch')}</td>
                      </tr>
                    ) : (
                      filteredSmtpLogs?.map(row => (
                        <tr key={row.id} className="smtp-log-row" onClick={() => setSelectedLog(row)}>
                          <td className="smtp-log-date sys-mono">{formatDate(row.sent_at)}</td>
                          <td className="smtp-log-domain sys-mono sys-small" title={row.domain || ''}>{row.domain || '—'}</td>
                          <td className="smtp-log-from sys-mono sys-small" title={row.sender_email || ''}>{row.sender_email || '—'}</td>
                          <td>
                            <div className="smtp-log-recipient">{row.recipient_name || '—'}</div>
                            <div className="smtp-log-email sys-muted sys-small" title={row.recipient_email || ''}>{row.recipient_email}</div>
                          </td>
                          <td className="smtp-log-subject" title={row.subject || ''}>{row.subject}</td>
                          <td><SmtpStatusCell row={row} t={t} /></td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Sürüm & Dağıtım — release_history.read (ADMIN + AUDIT). EN SONDA: SystemHealth.test.jsx SECTIONS sırası. */}
      {canViewReleases && (
      <div className="stats-section">
        <div
          className="stats-collapse-bar"
          onClick={() => toggleSection('releases')}
          title={releasesVisible ? t('app.collapseStats') : t('app.expandStats')}
        >
          <span className="stats-collapse-icon"><Rocket size={18} /></span>
          <span className="stats-collapse-label">{t('deploy.section')}</span>
          {!releasesVisible && (
            <span className="stats-collapse-hint">{t('health.sectionShow', t('deploy.section'))}</span>
          )}
          <span className={`stats-collapse-chevron${releasesVisible ? ' open' : ''}`}>
            <ChevronDown size={18} />
          </span>
        </div>
        {releasesVisible && (
        <div className="metrics-section">
          <Suspense fallback={<LoadingBlock />}>
            <DeploymentHistoryPanel canEdit={canEditRes('release_history.edit')} />
          </Suspense>
        </div>
        )}
      </div>
      )}
    </div>
  )
}
