import { useState, useEffect, useCallback, useRef, useMemo, lazy, Suspense } from 'react'
import { Database, Globe, HeartPulse, Plug, Rocket, Server, Users, Zap } from 'lucide-react'
import { api } from '../../api/client'
import { formatPercent } from '../../i18n/dateLocale.js'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { readUrlParam } from '../../hooks/useUrlQuerySync.js'
import ChartModal from './ChartModal'
import HeartbeatHistoryModal from './HeartbeatHistoryModal'
import SmtpLogView from './SmtpLogView.jsx'   // SMTP Gönderim Logu v2 — tam sayfa alt görünüm (2026-09-19)
import PushLogView from './PushLogView.jsx'   // Webhook Push Gönderim Logu — tam sayfa alt görünüm (2026-09-19)
import UserActivityPanel from './useractivity/UserActivityPanel.jsx'   // Kullanıcı / Oturum paneli
import DbAnalyticsPanel from './DbAnalyticsPanel.jsx'   // Veritabanı Analitiği (2026-09-26 yeniden tasarım)
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { SECTION_KEYS, deriveOverall, deriveKpis, sectionLevels, executorStats, poolStats, relTime, formatDurationShort } from './health/healthModel.js'
import { HealthSection, HealthSkeleton } from './health/HealthParts.jsx'
import { HealthStatusBanner, HealthStickyBar, HealthKpiGrid } from './health/HealthStatusBanner.jsx'
import SystemSection from './health/SystemSection.jsx'
import SchedulerSection from './health/SchedulerSection.jsx'
import IntegrationsSection from './health/IntegrationsSection.jsx'
import HeartbeatSection from './health/HeartbeatSection.jsx'
import HttpSection from './health/HttpSection.jsx'
import WeeklyAvailLogsModal from './health/WeeklyAvailLogsModal.jsx'
import HttpExplorerModal from './httpmetrics/HttpExplorerModal.jsx'   // İstek Gezgini penceresi (gezgin içeride tembel yüklenir)
const DeploymentHistoryPanel = lazy(() => import('./DeploymentHistoryPanel.jsx'))   // yalnız bölüm açılınca

/**
 * Sistem Sağlığı (2026-09-27 yeniden tasarım — operasyon durum konsolu).
 *
 * Üstte genel durum bandı (Sağlıklı / Bozulma / Kritik + sebepler → bölüme atlar) ve KPI ızgarası (tıklanınca ilgili
 * bölüm açılır ve oraya kaydırılır); altında katlanır bölümler (ui/CollapsibleSection, `?sec=` derin bağlantısı,
 * `sm:tab-params` olayı). Seviye kararları `health/healthModel.js`'te (saf), parçalar `health/*`. Bölümler:
 * Uygulama ve JVM · Zamanlayıcı ve yürütücüler · Veritabanı (DbAnalyticsPanel) · Entegrasyonlar · Heartbeat ·
 * HTTP istekleri · Kullanıcı/Oturum · Sürüm & Dağıtım (EN SONDA, 2026-09-11 kararı; not en altta 2026-09-12).
 */
const SECTION_META = {
  sys: { icon: Server, label: 'health.sectionApp' },
  sched: { icon: Zap, label: 'health.sectionSched' },
  db: { icon: Database, label: 'health.dbTitle' },
  integrations: { icon: Plug, label: 'health.sectionIntegrations' },
  heartbeat: { icon: HeartPulse, label: 'health.hbTitle' },
  http: { icon: Globe, label: 'http.shortTitle' },
  users: { icon: Users, label: 'uact.section' },
  releases: { icon: Rocket, label: 'deploy.section' },
}
const ORDER = ['sys', 'sched', 'db', 'integrations', 'heartbeat', 'http', 'users', 'releases']

function initialSection() {
  const sec = readUrlParam('sec', '')
  if (SECTION_KEYS.includes(sec)) return sec
  // Paylaşılan bir kullanıcı-etkinliği bağlantısı (u_* süzgeçleri) o bölümü açık getirir (QA ISSUE-001).
  try { return [...new URLSearchParams(window.location.search).keys()].some((k) => k.startsWith('u_')) ? 'users' : null } catch { return null }
}

export default function SystemHealth({ systemRole, globalAdmin = false, username, preFilterDomain, openSmtpModalOnLoad, onSmtpPreFilterConsumed }) {
  const isAdmin = systemRole === 'ADMIN'
  // 2026-09-19 (ürün kararı): HER bölüm her kademeye açık — Kullanıcı/Oturum da. Yazma (anomali onayı, oturum
  // sonlandırma) global admin / AUDIT'te kalır.
  const canViewUserActivity = true
  const canActUserActivity = !!globalAdmin || systemRole === 'AUDIT'
  const t = useT()
  const isMobile = useIsMobile()
  const { showConfirm } = useDialog()
  const { canView: canViewRes, canEdit: canEditRes } = usePermissions()
  const canViewReleases = canViewRes('release_history.read')

  const [health, setHealth] = useState(null)
  const [metrics, setMetrics] = useState([])
  const [httpMetrics, setHttpMetrics] = useState(null)
  const [userActivity, setUserActivity] = useState(null)
  const [configChecks, setConfigChecks] = useState(null)   // yapılandırma sağlığı (LDAP) — yalnız global admin
  const [loadErrors, setLoadErrors] = useState({ health: false, metrics: false, http: false, users: false })
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [paused, setPaused] = useState(false)
  const [lastChecked, setLastChecked] = useState(null)
  const [msg, setMsg] = useState(null)

  const [openSection, setOpenSection] = useState(initialSection)
  const sectionRefs = useRef({})
  // Tek açık bölüm: alttaki bir bölüm açılınca üstteki kapanır ve dokunulan başlık yukarı kayıp gözden kaçıyordu
  // (2026-10-09) → AÇILAN bölümün başlığı görünüme getirilir (goSection gibi; azaltılmış harekette animasyonsuz).
  // Açık mı, kabuk çizildikten sonra bölümün Collapsible kökünden okunur — kapatılan bölüm için kaydırma yok.
  const toggleSection = useCallback((key) => {
    setOpenSection((prev) => (prev === key ? null : key))
    setTimeout(() => {
      try {
        const el = sectionRefs.current[key]
        if (el?.querySelector?.(':scope > [data-state]')?.getAttribute('data-state') !== 'open') return
        const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
        el.scrollIntoView?.({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
      } catch { /* jsdom */ }
    }, 30)
  }, [])
  /** KPI / sebep tıklaması: bölümü aç ve oraya kaydır. */
  const goSection = useCallback((key) => {
    setOpenSection(key)
    setTimeout(() => { try { sectionRefs.current[key]?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }) } catch { /* jsdom */ } }, 30)
  }, [])

  const [poolCardRefreshing, setPoolCardRefreshing] = useState(false)
  const [poolLastRefreshed, setPoolLastRefreshed] = useState(null)
  const [uactRefreshing, setUactRefreshing] = useState(false)
  const [hbRefreshing, setHbRefreshing] = useState(false)
  const [hbModalOpen, setHbModalOpen] = useState(false)
  const [releasing, setReleasing] = useState(false)
  const [triggering, setTriggering] = useState(false)
  const fastPollRef = useRef(null)   // { timer } — tarama hızlı yoklamasının KUŞAĞI (null = durdu)
  const watchdogRef = useRef(null)   // id ref'te: iptal edilebilir, unmount'ta temizlenir, kuşak kontrolü
  const seenRunning = useRef(false)
  const [modalChart, setModalChart] = useState(null)
  const [httpExp, setHttpExp] = useState(null)   // İstek Gezgini: null = kapalı | { endpoint? } (bölümden bir uçla açılış)
  const [waLogsModal, setWaLogsModal] = useState(false)

  // SMTP / Push tam sayfa alt görünümleri (`?view=smtp|push`, süzgeçler `m_*` / `p_*`).
  const [view, setView] = useState(() => { const v = readUrlParam('view', ''); return v === 'smtp' || v === 'push' ? v : null })
  const [smtpPeriod, setSmtpPeriod] = useState('7d')
  const [smtpInitial, setSmtpInitial] = useState(null)
  const [pushPeriod, setPushPeriod] = useState('7d')
  const [pushKpi, setPushKpi] = useState(null)
  const [pushInitial, setPushInitial] = useState(null)

  // Veritabanı analitiği: pencere (1/7/30) + payload — bölüm açılınca / pencere değişince
  const [dbDays, setDbDays] = useState(7)
  const [dbData, setDbData] = useState(null)
  const [dbLoading, setDbLoading] = useState(false)
  const [dbError, setDbError] = useState(null)
  const [dbUpdatedAt, setDbUpdatedAt] = useState(null)
  // BF2: pencere seçici yüklenirken açık — 7 → 30 → 1 hızlı tıklanınca ağır 30 günlük yanıt EN SON gelip "1 gün"
  // seçiliyken 30 günlük kovaları çiziyordu. Yalnız EN SON isteğin yanıtı + bayrak temizliği uygulanır.
  const dbSeq = useRef(0)

  // Aynı sekmedeyken App `sec`/`view` param'larını olayla iletir (çipten "Dağıtım geçmişi", SMTP derin bağlantısı).
  useEffect(() => {
    const on = (e) => {
      const s = e?.detail?.sec; if (SECTION_KEYS.includes(s)) setOpenSection(s)
      if (e?.detail?.view === 'smtp') {
        const d = e.detail
        setSmtpInitial({ range: d.m_range, status: d.m_status, domain: d.m_domain, recipient: d.m_rcpt, teamId: d.m_team, errorClass: d.m_cls, q: d.m_q })
        setView('smtp')
      }
      if (e?.detail?.view === 'push') {
        const d = e.detail
        setPushInitial({ range: d.p_range, status: d.p_status, username: d.p_user, teamId: d.p_team, errorClass: d.p_cls, level: d.p_level, q: d.p_q })
        setView('push')
      }
    }
    window.addEventListener('sm:tab-params', on)
    return () => window.removeEventListener('sm:tab-params', on)
  }, [])

  const load = useCallback(async () => {
    // allSettled: bir uç çökse de diğerleri yüklensin; her bölüm kendi hatasını loadErrors üzerinden gösterir.
    const [healthRes, metricsRes, httpRes, uactRes, cfgRes] = await Promise.allSettled([
      api.admin.getSystemHealth(),
      api.admin.getMetrics(),
      api.admin.getHttpMetrics(),
      canViewUserActivity ? api.admin.getUserActivity() : Promise.resolve(null),
      // Yapılandırma sağlığı ucu global yöneticiye açık (settings.general edit); LDAP kartı yalnız oradan beslenir.
      globalAdmin ? api.admin.getConfigHealth() : Promise.resolve(null),
    ])
    const errs = { health: false, metrics: false, http: false, users: false }
    if (healthRes.status === 'fulfilled' && healthRes.value?.success) {
      setHealth(healthRes.value.data); setPoolLastRefreshed(new Date())
    } else { errs.health = true }
    if (metricsRes.status === 'fulfilled' && metricsRes.value?.success) setMetrics(metricsRes.value.data)
    else errs.metrics = true
    if (httpRes.status === 'fulfilled' && httpRes.value?.success) setHttpMetrics(httpRes.value.data)
    else errs.http = true
    if (canViewUserActivity) {
      if (uactRes.status === 'fulfilled' && uactRes.value?.success) setUserActivity(uactRes.value.data)
      else errs.users = true
    }
    if (globalAdmin && cfgRes.status === 'fulfilled' && cfgRes.value?.success) {
      const checks = cfgRes.value.data?.checks
      setConfigChecks(Array.isArray(checks) ? checks : null)
    }
    setLoadErrors(errs)
    setLastChecked(new Date())
    setLoading(false)
  }, [canViewUserActivity, globalAdmin])

  useVisibleInterval(load, paused ? 0 : 30000)   // gizli sekmede polling durur; Duraklat → aralık kapanır
  useEffect(() => () => {                         // scan fast-poll + watchdog temizliği
    if (fastPollRef.current) { clearTimeout(fastPollRef.current.timer); fastPollRef.current = null }   // uçuştaki yanıt da yok sayılır
    if (watchdogRef.current) clearTimeout(watchdogRef.current)
  }, [])

  const refreshNow = useCallback(async () => {
    setRefreshing(true)
    try { await load() } finally { setRefreshing(false) }
  }, [load])

  // Hata YUTULMAZ (2026-09-26): reddedilen istek panelde bant + "Tekrar dene" olarak görünür.
  const dbVisible = openSection === 'db'
  const loadDbAnalytics = useCallback(async () => {
    const my = ++dbSeq.current
    setDbLoading(true); setDbError(null)
    try {
      const res = await api.admin.getDbAnalytics(dbDays)
      if (my !== dbSeq.current) return   // bayat yanıt — daha yeni bir pencere istendi
      if (res?.success) { setDbData(res.data); setDbUpdatedAt(new Date()) }
      else setDbError(res?.error || true)
    } catch (e) { if (my === dbSeq.current) setDbError(e?.message || true) }
    finally { if (my === dbSeq.current) setDbLoading(false) }
  }, [dbDays])
  useEffect(() => { if (dbVisible) loadDbAnalytics() }, [dbVisible, loadDbAnalytics])

  const refreshHeartbeat = useCallback(async () => {
    setHbRefreshing(true)
    try {
      const res = await api.admin.triggerHeartbeat()
      if (res?.success) setHealth((prev) => ({ ...prev, heartbeat: res.data }))
    } finally { setHbRefreshing(false) }
  }, [])

  const refreshPool = useCallback(async () => {
    setPoolCardRefreshing(true)
    try {
      const res = await api.admin.getSystemHealth()
      if (res?.success) { setHealth((prev) => ({ ...prev, ...res.data })); setPoolLastRefreshed(new Date()) }
    } finally { setPoolCardRefreshing(false) }
  }, [])

  const refreshUserActivity = useCallback(async () => {
    setUactRefreshing(true)
    try {
      const res = await api.admin.getUserActivity()
      if (res?.success) setUserActivity(res.data)
    } finally { setUactRefreshing(false) }
  }, [])

  const replaceUrl = (mutate) => {
    try {
      const url = new URL(window.location.href)
      mutate(url.searchParams)
      const qs = url.searchParams.toString()
      window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
    } catch { /* history yoksay */ }
  }

  /** SMTP kartı / rozet / derin bağlantı → alt görünüm. overrides: { domain, status, range } (SmtpLogView initial). */
  const openSmtpModal = useCallback((overrides) => {
    const o = overrides && typeof overrides === 'object' && !('nativeEvent' in overrides) ? overrides : {}
    // Kart periyodu (1d/7d/15d/30d) sayfanın aralığına taşınır; 15d karşılığı yok → 30d.
    const range = o.range || ({ '1d': '24h', '7d': '7d', '15d': '30d', '30d': '30d' }[smtpPeriod] || '7d')
    setSmtpInitial({ range, ...o })
    setView('smtp')
    replaceUrl((p) => p.set('view', 'smtp'))
  }, [smtpPeriod])

  const openPushView = useCallback((overrides) => {
    const o = overrides && typeof overrides === 'object' && !('nativeEvent' in overrides) ? overrides : {}
    setPushInitial({ range: pushPeriod, ...o })
    setView('push')
    replaceUrl((p) => p.set('view', 'push'))
  }, [pushPeriod])

  // Kart KPI'sı: seçili periyot için push özeti (60 sn önbellekli uç; görünürken 60 sn'de bir tazelenir).
  // Yalnız EN SON isteğin yanıtı uygulanır: periyot hızlı değişince (ya da aralık tiki) geç gelen eski periyodun özeti
  // seçili periyodun KPI'sını ezmesin.
  const pushKpiSeq = useRef(0)
  const loadPushKpi = useCallback(async () => {
    const seq = ++pushKpiSeq.current
    try {
      const ms = pushPeriod === '24h' ? 24 * 3600e3 : pushPeriod === '30d' ? 30 * 86400e3 : 7 * 86400e3
      const r = await api.admin.pushLog.summary({ from: new Date(Date.now() - ms).toISOString().slice(0, 19) })
      if (seq === pushKpiSeq.current) setPushKpi(r?.success ? (r.data?.kpi || {}) : null)
    } catch { if (seq === pushKpiSeq.current) setPushKpi(null) }
  }, [pushPeriod])
  useEffect(() => { loadPushKpi() }, [loadPushKpi])
  useVisibleInterval(loadPushKpi, 60_000, false)

  const closeSubView = useCallback(() => {
    setView(null); setSmtpInitial(null); setPushInitial(null)
    replaceUrl((p) => { p.delete('view'); for (const k of [...p.keys()]) if (k.startsWith('m_') || k.startsWith('p_')) p.delete(k) })
  }, [])

  useEffect(() => {
    if (openSmtpModalOnLoad) {
      openSmtpModal(preFilterDomain ? { domain: preFilterDomain } : undefined)
      onSmtpPreFilterConsumed?.()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openSmtpModalOnLoad, preFilterDomain])

  const handleForceRelease = async () => {
    if (!await showConfirm({ title: t('sys.forceRelease'), message: t('sys.lockReleaseConfirm'), confirmText: t('sys.forceRelease'), variant: 'danger' })) return
    setReleasing(true)
    // try/finally ŞART: request() ağ hatasında THROW eder; bayrak temizlenmezse düğme remount'a kadar kilitli kalır.
    try {
      const res = await api.admin.forceReleaseLock()
      setMsg(res?.success ? t('sys.lockReleased') : t('sys.error'))
    } catch { setMsg(t('sys.error')) }
    finally { setReleasing(false) }
    load()
  }

  /**
   * Tarama hızlı yoklaması (2 sn) — ZİNCİRLİ setTimeout (2026-10-09): bir sonraki istek ancak öncekinin yanıtından
   * sonra planlanır. Eskiden setInterval + await, yavaş sunucuda (GET 90 sn zaman aşımı) 5 dk boyunca ~45 ağır isteği
   * üst üste biriktirebiliyordu. Kuşak nesnesi: yeni tarama / watchdog / unmount eskisini durdurur, uçuştaki yanıt yok sayılır.
   */
  const startScanPoll = useCallback((prevLastRun) => {
    if (fastPollRef.current) clearTimeout(fastPollRef.current.timer)
    if (watchdogRef.current) clearTimeout(watchdogRef.current)
    seenRunning.current = false
    const gen = { timer: null }
    const alive = () => fastPollRef.current === gen
    const stop = () => { if (alive()) { clearTimeout(gen.timer); fastPollRef.current = null } }
    const step = async () => {
      if (!alive()) return
      let res = null
      try { res = await api.admin.getSystemHealth() } catch { res = null }
      if (!alive()) return
      if (res?.success) {
        setHealth(res.data)
        const isRunning = res.data?.scheduler?.running
        const newLastRun = res.data?.scan?.last_run
        if (isRunning) seenRunning.current = true
        // Tamamlandı: running false'a döndü VEYA last_run değişti (hızlı tarama)
        const done = (seenRunning.current && !isRunning) || (newLastRun && newLastRun !== prevLastRun)
        if (done) { stop(); load(); return }
      }
      gen.timer = setTimeout(step, 2000)
    }
    fastPollRef.current = gen
    gen.timer = setTimeout(step, 2000)
    // KUŞAK KONTROLÜ: watchdog yalnız KENDİ yoklamasını durdurur (eski tarama #1'in watchdog'u #2'yi susturuyordu).
    watchdogRef.current = setTimeout(stop, 300_000)
  }, [load])

  const handleForceRun = async () => {
    setTriggering(true)
    try {
      await api.runScheduler()
      setMsg(t('sys.checkTriggered'))
    } catch { setMsg(t('sys.error')) }
    finally { setTriggering(false) }
    startScanPoll(health?.scan?.last_run)
  }

  // ── Türetimler (saf model) ──
  const overall = useMemo(() => deriveOverall({ health, httpMetrics, pushKpi, smtpPeriod, configChecks, loadErrors }),
    [health, httpMetrics, pushKpi, smtpPeriod, configChecks, loadErrors])
  const kpis = useMemo(() => (health ? deriveKpis({ health, httpMetrics, metrics, pushKpi, smtpPeriod, configChecks }) : null),
    [health, httpMetrics, metrics, pushKpi, smtpPeriod, configChecks])
  const levels = useMemo(() => sectionLevels(overall.reasons), [overall])
  const sectionLabel = useCallback((key) => t(SECTION_META[key]?.label || key), [t])

  if (loading && !health) return <HealthSkeleton label={t('sys.loading')} />

  if (view === 'smtp') {
    return (
      <div data-slot="system-health" className="py-1">
        <SmtpLogView key={JSON.stringify(smtpInitial)} initial={smtpInitial} onBack={closeSubView} />
      </div>
    )
  }
  if (view === 'push') {
    return (
      <div data-slot="system-health" className="py-1">
        <PushLogView key={JSON.stringify(pushInitial)} initial={pushInitial} onBack={closeSubView} />
      </div>
    )
  }

  const failedSections = Object.entries(loadErrors).filter(([, v]) => v)
    .map(([k]) => t(`health.part${k.charAt(0).toUpperCase() + k.slice(1)}`))

  // Kapalı bölüm özetleri (başlık ipucu)
  const ex = executorStats(health?.executor_pool)
  const pl = poolStats(health?.pool, health?.db_ms)
  const summaries = {
    sys: [health?.build?.version && `v${health.build.version}`, health?.build?.environment, health?.memory && `${t('sys.memTitle')} ${formatPercent(health.memory.used_pct)}`].filter(Boolean).join(' · '),
    sched: [health?.scheduler?.running ? t('sys.running') : t('sys.idle'), relTime(health?.scheduler?.next_run, t), ex && t('health.queueShort', ex.queue, ex.cap)].filter(Boolean).join(' · '),
    db: pl ? [`${pl.active} / ${pl.max}`, pl.ms != null && `${pl.ms} ms`].filter(Boolean).join(' · ') : '',
    integrations: kpis ? t('health.kpiIntegrationsSub', kpis.integrations.ok, kpis.integrations.total) : '',
    heartbeat: health?.heartbeat ? (health.heartbeat.minutes_since >= 0 ? t('health.hbMinutes').replace('{n}', health.heartbeat.minutes_since) : t('health.hbAlarm')) : '',
    http: httpMetrics?.summary ? [`${Number(httpMetrics.summary.total_requests ?? 0).toLocaleString()} ${t('http.totalReqs').toLowerCase()}`, `${formatPercent(httpMetrics.summary.error_rate_pct ?? 0)} ${t('http.errorRate').toLowerCase()}`, `${httpMetrics.summary.avg_ms ?? 0} ms`].join(' · ') : '',
    users: '', releases: '',
  }
  const refreshProps = { t, level: overall.level, lastChecked, paused, onTogglePause: () => setPaused((p) => !p), onRefresh: refreshNow, refreshing }

  const section = (key, children) => {
    const m = SECTION_META[key]
    return (
      <HealthSection key={key} id={key} icon={m.icon} label={sectionLabel(key)} level={key === 'users' || key === 'releases' ? null : levels[key]}
        summary={summaries[key] || undefined} open={openSection === key} onToggle={toggleSection} t={t}
        sectionRef={(el) => { sectionRefs.current[key] = el }}>
        {children}
      </HealthSection>
    )
  }
  const content = {
    sys: () => <SystemSection t={t} health={health} metrics={metrics} metricsError={loadErrors.metrics} onRetry={refreshNow} onOpenChart={setModalChart} />,
    sched: () => (
      <SchedulerSection t={t} health={health} isAdmin={isAdmin} poolLastRefreshed={poolLastRefreshed} poolRefreshing={poolCardRefreshing} onRefreshPool={refreshPool}
        triggering={triggering} onForceRun={handleForceRun} releasing={releasing} onForceRelease={handleForceRelease} />
    ),
    db: () => (
      <DbAnalyticsPanel data={dbData} loading={dbLoading} error={dbError} days={dbDays} onDaysChange={setDbDays} onRefresh={loadDbAnalytics} updatedAt={dbUpdatedAt}
        appPool={health?.pool} />
    ),
    integrations: () => (
      <IntegrationsSection t={t} health={health} pushKpi={pushKpi} configChecks={configChecks} globalAdmin={!!globalAdmin}
        smtpPeriod={smtpPeriod} onSmtpPeriod={setSmtpPeriod} pushPeriod={pushPeriod} onPushPeriod={setPushPeriod}
        onOpenSmtp={openSmtpModal} onOpenPush={openPushView} onOpenWeeklyLogs={() => setWaLogsModal(true)} />
    ),
    heartbeat: () => (
      <HeartbeatSection t={t} heartbeat={health?.heartbeat} isAdmin={isAdmin} refreshing={hbRefreshing} onRefresh={refreshHeartbeat} onOpenHistory={() => setHbModalOpen(true)} />
    ),
    http: () => <HttpSection t={t} httpMetrics={httpMetrics} error={loadErrors.http} onRetry={refreshNow}
      onOpenExplorer={(focus) => setHttpExp(focus && typeof focus === 'object' && !('nativeEvent' in focus) ? focus : {})} />,
    users: () => (
      <UserActivityPanel data={userActivity} error={loadErrors.users} refreshing={uactRefreshing} onRefresh={refreshUserActivity}
        isAdmin={isAdmin} globalAdmin={!!globalAdmin} canAck={canActUserActivity} username={username} onTerminated={refreshUserActivity} />
    ),
    releases: () => (
      <Suspense fallback={<LoadingBlock />}>
        <DeploymentHistoryPanel canEdit={canEditRes('release_history.edit')} />
      </Suspense>
    ),
  }
  const visible = ORDER.filter((k) => (k === 'users' ? canViewUserActivity : k === 'releases' ? canViewReleases : true))

  return (
    <div data-slot="system-health" className="flex flex-col gap-3 py-1">
      {isMobile && <HealthStickyBar {...refreshProps} />}

      <HealthStatusBanner {...refreshProps} reasons={overall.reasons} onGoSection={goSection} sectionLabel={sectionLabel} hideChip={isMobile} />

      {failedSections.length > 0 && !loading && (
        <AlertBanner tone="danger" role="alert" title={t('health.loadErrorTitle')} className="mb-0"
          actions={<Button type="button" variant="secondary" size="sm" onClick={refreshNow}>{t('err.reload')}</Button>}>
          {failedSections.join(', ')}.
        </AlertBanner>
      )}

      {msg && (
        <AlertBanner tone="info" className="mb-0" onDismiss={() => setMsg(null)} dismissLabel={t('app.close')}>{msg}</AlertBanner>
      )}

      <HealthKpiGrid t={t} kpis={kpis} onGoSection={goSection} sectionLabel={sectionLabel} />

      {visible.map((k) => section(k, openSection === k ? content[k]() : null))}

      <ChartModal chart={modalChart} onClose={() => setModalChart(null)} />

      {httpExp && <HttpExplorerModal t={t} focus={httpExp} onClose={() => setHttpExp(null)} />}

      {hbModalOpen && <HeartbeatHistoryModal onClose={() => setHbModalOpen(false)} />}

      {waLogsModal && <WeeklyAvailLogsModal t={t} onClose={() => setWaLogsModal(false)} />}

      {/* Sayfa geneli not — EN ALTTA (2026-09-12, kullanıcı: bölümler arasında sahipsiz görünüyordu). */}
      <p data-testid="sys-refresh-note" className="mt-1 text-center text-xs text-muted-foreground">
        ↻ {paused ? t('health.autoRefreshPaused') : t('sys.autoRefresh')}{health?.build?.uptime_seconds != null && ` · ${t('deploy.uptime')}: ${formatDurationShort(health.build.uptime_seconds, t)}`}
      </p>
    </div>
  )
}
