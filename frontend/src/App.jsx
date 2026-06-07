import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { ChevronDown, BarChart3, AlertOctagon, X, Wifi, CheckCircle, Clock } from 'lucide-react'
import { api, formatDate } from './api/client'
import { useDialog } from './components/ui/Dialog.jsx'
import { useT } from './i18n/index.jsx'
import SearchableSelect from './components/ui/SearchableSelect.jsx'
import Login from './pages/Login'
import Nav from './components/Nav'
import StatsPanel from './components/StatsPanel'
import StatsView from './components/StatsView'
import CertificateCard from './components/CertificateCard'
import CertificatesTable from './components/CertificatesTable'
import CertificateModal from './components/CertificateModal'
import CaDiversityModal from './components/CaDiversityModal'
import RenewalAdvice from './components/RenewalAdvice'
import CertRenewalGuide from './components/CertRenewalGuide.jsx'
import PasswordChangeModal from './components/admin/PasswordChangeModal.jsx'
import AdminPanel from './components/admin/AdminPanel'
import AlertHistory from './components/admin/AlertHistory'
import InventoryManager from './components/admin/InventoryManager'
import AuditLogViewer from './components/admin/AuditLogViewer'
import WeakAlgorithmReport from './components/admin/WeakAlgorithmReport'
import SystemHealth from './components/admin/SystemHealth'
import SqlPlayground from './components/admin/SqlPlayground'
import ActivityLog from './components/ActivityLog'
import HelpPage from './components/HelpPage'
import UptimePage from './components/UptimePage'
import PortMonitorPage from './components/PortMonitorPage'
import DnsMonitorPage from './components/DnsMonitorPage'
import ExpiryForecastPage from './pages/ExpiryForecastPage'

const INACTIVITY_MS   = Number(import.meta.env.VITE_INACTIVITY_MS   ?? 300_000)
const WARN_BEFORE_MS  = Number(import.meta.env.VITE_WARN_BEFORE_MS  ?? 60_000)

function formatDurationShort(ms) {
  if (ms == null || ms < 0) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60)   return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60)   return `${m} dk ${s % 60} sn`
  const h = Math.floor(m / 60)
  return `${h} sa ${m % 60} dk`
}


export default function App() {
  const { showConfirm } = useDialog()
  const t = useT()
  const [user, setUser] = useState(null)
  const [systemRole, setSystemRole] = useState('USER')
  const [teamId, setTeamId] = useState(null)
  const [teamName, setTeamName] = useState(null)
  const [authChecked, setAuthChecked] = useState(false)
  const [tab, setTab] = useState('dashboard')
  const [certs, setCerts] = useState([])
  const [warnings, setWarnings] = useState([])
  const [stats, setStats] = useState(null)
  const [networkStatus, setNetworkStatus] = useState(null)
  const [networkBannerDismissed, setNetworkBannerDismissed] = useState(false)
  const [outageHistory, setOutageHistory] = useState([])
  const [teamStats, setTeamStats] = useState(null)
  const [weakAlgStats, setWeakAlgStats] = useState(null)
  const [statsVisible, setStatsVisible] = useState(false)
  const [selfPwdModalOpen, setSelfPwdModalOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [sortOrder, setSortOrder] = useState('default')
  const [modalCert, setModalCert] = useState(null)
  const [caModal, setCaModal]     = useState(false)
  const [newDomain,    setNewDomain]    = useState('')
  const [checkLoading, setCheckLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshProgress, setRefreshProgress] = useState(null)
  const [lastUpdate, setLastUpdate] = useState(null)
  const [inactivityWarning, setInactivityWarning] = useState(false)
  const [countdown, setCountdown] = useState(60)
  const [statsFilter, setStatsFilter] = useState(null)
  const [statusFilter, setStatusFilter] = useState('all')
  const [expiryFilter, setExpiryFilter] = useState('all')
  const [dashPage, setDashPage] = useState(1)
  const [pageSize, setPageSize] = useState(12)
  const [activityRefreshKey, setActivityRefreshKey] = useState(0)
  const [silentAlertDomains, setSilentAlertDomains] = useState(new Set())
  const [mailFailureDomains, setMailFailureDomains] = useState(new Set())
  const [adminInitialTab, setAdminInitialTab] = useState(null)
  const [smtpPreFilterDomain, setSmtpPreFilterDomain] = useState(null)
  const [openSmtpModalOnLoad, setOpenSmtpModalOnLoad] = useState(false)

  const logoutTimer = useRef(null)
  const warnTimer = useRef(null)
  const countdownInterval = useRef(null)
  const refreshPollRef = useRef(null)

  useEffect(() => {
    api.getMe().then((res) => {
      if (res?.success) {
        setUser(res.username)
        setSystemRole(res.system_role || 'USER')
        setTeamId(res.team_id ?? null)
        setTeamName(res.team_name ?? null)
      }
      setAuthChecked(true)
    }).catch(() => setAuthChecked(true))
  }, [])

  useEffect(() => {
    if (!user) return

    const doAutoLogout = async () => {
      clearInterval(countdownInterval.current)
      await api.logout()
      localStorage.removeItem('cert-monitor-remembered-user')
      setUser(null)
      setSystemRole('USER')
      setTeamId(null)
      setTeamName(null)
      setInactivityWarning(false)
    }

    const resetTimer = () => {
      clearTimeout(logoutTimer.current)
      clearTimeout(warnTimer.current)
      clearInterval(countdownInterval.current)
      setInactivityWarning(false)

      warnTimer.current = setTimeout(() => {
        setInactivityWarning(true)
        setCountdown(60)
        countdownInterval.current = setInterval(() => {
          setCountdown((prev) => {
            if (prev <= 1) {
              clearInterval(countdownInterval.current)
              return 0
            }
            return prev - 1
          })
        }, 1000)
      }, INACTIVITY_MS - WARN_BEFORE_MS)

      logoutTimer.current = setTimeout(doAutoLogout, INACTIVITY_MS)
    }

    const events = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'click']
    events.forEach((e) => document.addEventListener(e, resetTimer, { passive: true }))
    resetTimer()

    return () => {
      clearTimeout(logoutTimer.current)
      clearTimeout(warnTimer.current)
      clearInterval(countdownInterval.current)
      events.forEach((e) => document.removeEventListener(e, resetTimer))
    }
  }, [user])

  const loadData = useCallback(async () => {
    const [certsRes, statsRes, silentRes, teamStatsRes, weakRes, netRes, mailFailRes] = await Promise.all([
      api.getCertificates(), api.getStats(), api.getSilentAlertDomains(), api.getTeamStats(),
      api.admin.getWeakAlgorithms(),
      api.getNetworkStatus(),
      api.getMailFailureDomains(),
    ])
    if (certsRes?.success) { setCerts(certsRes.data); setLastUpdate(certsRes.timestamp) }
    if (statsRes?.success) setStats(statsRes.data)
    if (silentRes?.success) setSilentAlertDomains(new Set(silentRes.data))
    if (mailFailRes?.success) setMailFailureDomains(new Set(mailFailRes.data ?? []))
    if (teamStatsRes?.success) setTeamStats(teamStatsRes.data)
    if (weakRes?.success) setWeakAlgStats(weakRes)
    if (netRes?.success) {
      setNetworkStatus(prev => {
        // Reset dismissal flag when a new outage starts (alarm transitions false -> true)
        if (netRes.data?.alarm && !prev?.alarm) setNetworkBannerDismissed(false)
        return netRes.data
      })
    }
  }, [])

  useEffect(() => {
    if (user) {
      loadData()
      const interval = setInterval(loadData, Number(import.meta.env.VITE_DATA_REFRESH_MS ?? 300_000))
      return () => clearInterval(interval)
    }
  }, [user, loadData])

  // Lightweight 60s poll just for network outage status — keeps banner in sync
  // without waiting for the 5-minute full data refresh
  useEffect(() => {
    if (!user) return
    const tick = async () => {
      const res = await api.getNetworkStatus()
      if (res?.success) {
        setNetworkStatus(prev => {
          if (res.data?.alarm && !prev?.alarm) setNetworkBannerDismissed(false)
          return res.data
        })
      }
    }
    const id = setInterval(tick, 60_000)
    return () => clearInterval(id)
  }, [user])

  useEffect(() => {
    if (user && tab === 'warnings') {
      api.getWarnings().then((res) => { if (res?.success) setWarnings(res.data) })
      api.getNetworkOutageHistory(50).then((res) => {
        if (res?.success && Array.isArray(res.events)) setOutageHistory(res.events)
      })
    }
  }, [user, tab])


  async function handleLogout() {
    const ok = await showConfirm({
      title: t('app.logoutTitle'),
      message: t('app.logoutMsg'),
      variant: 'logout',
      confirmText: t('app.logoutConfirm'),
      cancelText: t('app.cancel'),
    })
    if (!ok) return
    clearTimeout(logoutTimer.current)
    clearTimeout(warnTimer.current)
    clearInterval(countdownInterval.current)
    clearInterval(refreshPollRef.current)
    await api.logout()
    localStorage.removeItem('cert-monitor-remembered-user')
    setUser(null)
    setSystemRole('USER')
    setTeamId(null)
    setTeamName(null)
    setInactivityWarning(false)
    setRefreshing(false)
  }

  async function handleRefresh() {
    if (refreshing) return
    setRefreshing(true)
    setRefreshProgress(null)

    const triggerTime = new Date().toISOString().substring(0, 19)
    await api.runScheduler()
    setActivityRefreshKey(k => k + 1)

    const POLL_MS   = 2000
    const MAX_MS    = 5 * 60 * 1000
    const startedAt = Date.now()

    clearInterval(refreshPollRef.current)
    refreshPollRef.current = setInterval(async () => {
      const [certsRes, statusRes] = await Promise.all([
        api.getCertificates(),
        api.getSchedulerStatus(),
      ])

      if (certsRes?.success) {
        setCerts(certsRes.data)
        setLastUpdate(certsRes.timestamp)
        const updated = certsRes.data.filter(
          (c) => c.checked_at && c.checked_at >= triggerTime
        ).length
        setRefreshProgress({ checked: updated, total: certsRes.data.length })
      }

      setActivityRefreshKey(k => k + 1)

      const done     = !statusRes?.data?.running
      const timedOut = Date.now() - startedAt > MAX_MS

      if (done || timedOut) {
        clearInterval(refreshPollRef.current)
        const [statsRes, silentRes] = await Promise.all([api.getStats(), api.getSilentAlertDomains()])
        if (statsRes?.success) setStats(statsRes.data)
        if (silentRes?.success) setSilentAlertDomains(new Set(silentRes.data))
        setRefreshing(false)
        setRefreshProgress(null)
      }
    }, POLL_MS)
  }

  async function handleAddDomain() {
    if (!newDomain.trim() || checkLoading) return
    let domain = newDomain.trim()
    domain = domain.replace(/^https?:\/\//i, '')
    domain = domain.split('/')[0]
    if (domain.includes(':')) domain = domain.split(':')[0]
    if (!domain) return
    setCheckLoading(true)
    try {
      const res = await api.checkDomainPreview(domain)
      setNewDomain('')
      if (res?.data) {
        setModalCert({ ...res.data, domain: res.data.domain || domain, _preview: true })
      }
    } finally {
      setCheckLoading(false)
    }
  }

  function handleLogin(userData) {
    setTab('dashboard')
    setUser(userData.username)
    setSystemRole(userData.system_role || 'USER')
    setTeamId(userData.team_id ?? null)
    setTeamName(userData.team_name ?? null)
  }

  const weakDomainSet = useMemo(
    () => new Set((weakAlgStats?.data ?? []).map(d => d.domain)),
    [weakAlgStats]
  )

  const issuerStats = useMemo(() => {
    const reachable = certs.filter(c => c.status !== 'error')
    if (reachable.length === 0) return null
    const counts = {}
    for (const c of reachable) {
      const key = c.issuer || c.issuer_cn || 'Unknown'
      counts[key] = (counts[key] ?? 0) + 1
    }
    const entries = Object.entries(counts).sort((a, b) => b[1] - a[1])
    const [dominantIssuer, dominantCount] = entries[0]
    const dominantPct = Math.round((dominantCount / reachable.length) * 100)
    return { uniqueCount: entries.length, dominantIssuer, dominantCount, dominantPct }
  }, [certs])

  if (!authChecked) return <div className="loading" style={{ marginTop: 80, textAlign: 'center' }}>{t('app.loading')}</div>
  if (!user) return <Login onLogin={handleLogin} />

  const STAT_FILTER_FN = {
    total:      () => true,
    valid:      (c) => !c.warning && c.status !== 'error',
    critical:   (c) => c.alert_level ? c.alert_level === 'critical' : (c.warning === true && c.days_remaining != null && c.days_remaining <= 7),
    high:       (c) => c.alert_level ? c.alert_level === 'high'     : (c.warning === true && c.days_remaining != null && c.days_remaining > 7 && c.days_remaining <= 15),
    warning:    (c) => c.alert_level ? c.alert_level === 'warning' : (c.warning === true && c.status !== 'error'),
    error:      (c) => c.status === 'error',
    expiring7:  (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 7,
    expiring30: (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 30,
    expired:    (c) => c.days_remaining != null && c.days_remaining < 0,
    weak:       (c) => weakDomainSet.has(c.domain),
  }
  const STAT_FILTER_LABEL = {
    total: t('stat.total'), valid: t('stat.valid'), critical: t('stat.critical'), high: t('stat.high'),
    warning: t('stat.warning'), error: t('stat.error'), expiring7: t('stat.expiring7'), expiring30: t('stat.expiring30'), expired: t('stat.expired'),
    weak: t('stat.weak'),
  }

  function handleStatClick(key) {
    const next = statsFilter === key ? null : key
    setStatsFilter(next)
    setDashPage(1)
    setTab('dashboard')
  }

  const STATUS_FILTER_FN = {
    all:     () => true,
    valid:   (c) => !c.warning && c.status !== 'error',
    warning: (c) => c.warning === true && c.status !== 'error',
    error:   (c) => c.status === 'error',
  }
  const EXPIRY_FILTER_FN = {
    all:      () => true,
    expired:  (c) => c.days_remaining != null && c.days_remaining < 0,
    days7:    (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 7,
    days30:   (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 30,
    days90:   (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 90,
  }

  const statFn   = statsFilter ? STAT_FILTER_FN[statsFilter] : null
  const statusFn = STATUS_FILTER_FN[statusFilter] ?? (() => true)
  const expiryFn = EXPIRY_FILTER_FN[expiryFilter] ?? (() => true)

  const filtered = certs.filter((c) => {
    if (statFn   && !statFn(c))   return false
    if (!statusFn(c))              return false
    if (!expiryFn(c))              return false
    if (!search)                   return true
    const s = search.toLowerCase()
    return c.domain?.toLowerCase().includes(s) || c.issuer?.toLowerCase().includes(s) || c.subject?.toLowerCase().includes(s)
  })

  function defaultPriority(c) {
    const al = c.alert_level
    const days = c.days_remaining
    const isError    = al ? al === 'error'    : c.status === 'error'
    const isExpired  = !isError && (al ? al === 'expired'  : (days != null && days < 0))
    const isCritical = !isError && !isExpired && (al ? al === 'critical' : (days != null && days <= 7))
    const isHigh     = !isError && !isExpired && !isCritical && (al ? al === 'high'     : (days != null && days <= 15))
    const isWarning  = !isError && !isExpired && !isCritical && !isHigh && (al ? al === 'warning'  : c.warning === true)
    if (isError)             return 0
    if (isExpired || isCritical) return 1
    if (isHigh)              return 2
    if (isWarning)           return 3
    return 4
  }

  const sorted = [...filtered].sort((a, b) => {
    if (sortOrder === 'asc')  return (a.days_remaining ?? 999999) - (b.days_remaining ?? 999999)
    if (sortOrder === 'desc') return (b.days_remaining ?? -1) - (a.days_remaining ?? -1)
    const pd = defaultPriority(a) - defaultPriority(b)
    if (pd !== 0) return pd
    return (a.days_remaining ?? 999999) - (b.days_remaining ?? 999999)
  })

  const totalPages = pageSize === 0 ? 1 : Math.max(1, Math.ceil(sorted.length / pageSize))
  const safePage   = Math.min(dashPage, totalPages)
  const pageCerts  = pageSize === 0 ? sorted : sorted.slice((safePage - 1) * pageSize, safePage * pageSize)

  function getPageNumbers() {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i + 1)
    const pages = new Set([1, totalPages, safePage, safePage - 1, safePage + 1].filter(p => p >= 1 && p <= totalPages))
    const sorted_ = [...pages].sort((a, b) => a - b)
    const result = []
    for (let i = 0; i < sorted_.length; i++) {
      if (i > 0 && sorted_[i] - sorted_[i - 1] > 1) result.push('...')
      result.push(sorted_[i])
    }
    return result
  }

  return (
    <div className="app-layout">

      {inactivityWarning && (
        <div className="inactivity-warning">
          <span dangerouslySetInnerHTML={{ __html: t('app.inactivityWarn', `<strong>${countdown}</strong>`) }} />
          <button onClick={() => setInactivityWarning(false)}>{t('app.stayLoggedIn')}</button>
        </div>
      )}

      <Nav activeTab={tab} onTabChange={setTab} username={user} teamName={teamName} systemRole={systemRole}
        onLogout={handleLogout} onChangePassword={() => setSelfPwdModalOpen(true)} />

      {selfPwdModalOpen && user && (
        <PasswordChangeModal
          mode="self-change"
          targetUser={{ id: null, username: user }}
          onClose={() => setSelfPwdModalOpen(false)}
        />
      )}

      <main className="app-main">
        <div className="app-body">

          <div className="controls">
            <button className="btn btn-primary" onClick={handleRefresh} disabled={refreshing}>
              {refreshing
                ? refreshProgress
                  ? t('app.checkedOf', refreshProgress.checked, refreshProgress.total)
                  : t('app.starting')
                : t('app.checkNow')}
            </button>
            <div className="add-domain-section">
              <input className="domain-input" type="text" placeholder={t('app.newDomainPlaceholder')}
                value={newDomain} onChange={(e) => setNewDomain(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleAddDomain()} />
              <button className="btn btn-success" onClick={handleAddDomain} disabled={checkLoading}>
                {checkLoading ? t('app.checkingDomain') : t('app.checkBtn')}
              </button>
            </div>
          </div>

          {networkStatus?.alarm && !networkBannerDismissed && (
            <div className="network-outage-banner" role="alert">
              <AlertOctagon size={20} />
              <div className="network-outage-text">
                <strong>{t('app.networkOutageTitle')}</strong>
                <span>{t('app.networkOutageDesc',
                  networkStatus.detected_at ? formatDate(networkStatus.detected_at) : '—')}</span>
              </div>
              <button
                type="button"
                className="network-outage-close"
                onClick={() => setNetworkBannerDismissed(true)}
                title={t('app.dismiss')}
                aria-label={t('app.dismiss')}
              >
                <X size={16} />
              </button>
            </div>
          )}

          {tab === 'dashboard' && (
            <div className="stats-section">
              <div
                className="stats-collapse-bar"
                onClick={() => setStatsVisible((v) => !v)}
                title={statsVisible ? t('app.collapseStats') : t('app.expandStats')}
              >
                <span className="stats-collapse-icon"><BarChart3 size={18} /></span>
                <span className="stats-collapse-label">{t('app.statistics')}</span>
                {!statsVisible && (
                  <span className="stats-collapse-hint">{t('app.expandStats')}</span>
                )}
                <span className={`stats-collapse-chevron${statsVisible ? ' open' : ''}`}>
                  <ChevronDown size={18} />
                </span>
              </div>
              <StatsPanel stats={stats} visible={statsVisible}
                onStatClick={handleStatClick} activeFilter={statsFilter}
                weakStats={weakAlgStats} issuerStats={issuerStats}
                onCaClick={() => setCaModal(true)} />
            </div>
          )}

          <div className="content">
            {tab === 'dashboard' && (
              <div className="tab-content active">
                <div className="sort-controls sort-bar">
                  <label>{t('app.sortLabel')}</label>
                  <SearchableSelect
                    value={sortOrder}
                    onChange={v => { setSortOrder(v); setDashPage(1) }}
                    options={[
                      { value: 'default', label: t('app.sortDefault') },
                      { value: 'asc',     label: t('app.sortAsc') },
                      { value: 'desc',    label: t('app.sortDesc') },
                    ]}
                  />
                  <label>{t('app.statusLabel')}</label>
                  <SearchableSelect
                    value={statusFilter}
                    onChange={v => { setStatusFilter(v); setDashPage(1) }}
                    options={[
                      { value: 'all',     label: t('app.all') },
                      { value: 'valid',   label: t('app.valid') },
                      { value: 'warning', label: t('app.warning') },
                      { value: 'error',   label: t('app.error') },
                    ]}
                  />
                  <label>{t('app.expiryLabel')}</label>
                  <SearchableSelect
                    value={expiryFilter}
                    onChange={v => { setExpiryFilter(v); setDashPage(1) }}
                    options={[
                      { value: 'all',     label: t('app.all') },
                      { value: 'expired', label: t('app.expired') },
                      { value: 'days7',   label: t('app.days7') },
                      { value: 'days30',  label: t('app.days30') },
                      { value: 'days90',  label: t('app.days90') },
                    ]}
                  />
                  <input
                    className="sort-bar-search"
                    type="text"
                    placeholder={t('app.searchPlaceholder')}
                    value={search}
                    onChange={(e) => { setSearch(e.target.value); setDashPage(1) }}
                  />
                  {search && (
                    <button
                      type="button"
                      className="sort-bar-search-clear"
                      onClick={() => { setSearch(''); setDashPage(1) }}
                      title={t('app.clearFilter')}
                    >
                      ✕
                    </button>
                  )}
                </div>
                <div className="dashboard-header">
                  <h2>{t('app.dashTitle')}</h2>
                  {statsFilter && (
                    <div className="stats-filter-bar">
                      <span>
                        {t('app.filterPrefix')} <strong>{STAT_FILTER_LABEL[statsFilter]}</strong>
                        {t('app.filterCerts', filtered.length)}
                      </span>
                      <button className="stats-filter-clear" onClick={() => setStatsFilter(null)}>
                        {t('app.clearFilter')}
                      </button>
                    </div>
                  )}
                </div>
                {sorted.length === 0 ? (
                  <div className="loading">
                    {statsFilter ? t('app.noFilterCerts', STAT_FILTER_LABEL[statsFilter]) : t('app.noCerts')}
                  </div>
                ) : (
                  <>
                    <div className="cards-container">
                      {pageCerts.map((cert) => (
                        <CertificateCard key={cert.domain} cert={cert} onClick={(d) => setModalCert(certs.find(c => c.domain === d) ?? null)}
                          hasSilentAlert={silentAlertDomains.has(cert.domain)}
                          hasMailFailure={mailFailureDomains.has(cert.domain)}
                          onMailFailureClick={() => {
                            setTab('health')
                            setSmtpPreFilterDomain(cert.domain)
                            setOpenSmtpModalOnLoad(true)
                          }}
                          isWeak={weakAlgStats != null ? weakDomainSet.has(cert.domain) : undefined} />
                      ))}
                    </div>
                    {(pageSize === 0 || sorted.length > pageSize) && <div className="dash-pagination">
                      <div className="dash-page-sizer">
                        <span className="dash-page-sizer-label">{t('app.perPage')}</span>
                        {[10, 25, 50].map(n => (
                          <button
                            key={n}
                            className={`dash-size-btn${pageSize === n ? ' active' : ''}`}
                            onClick={() => { setPageSize(n); setDashPage(1) }}
                          >{n}</button>
                        ))}
                        <button
                          className={`dash-size-btn${pageSize === 0 ? ' active' : ''}`}
                          onClick={() => { setPageSize(0); setDashPage(1) }}
                        >{t('app.all')}</button>
                      </div>

                      {totalPages > 1 && (
                        <div className="dash-page-nav">
                          <button className="page-btn" disabled={safePage <= 1}
                            onClick={() => setDashPage(1)}>«</button>
                          <button className="page-btn" disabled={safePage <= 1}
                            onClick={() => setDashPage(safePage - 1)}>{t('app.prevPage')}</button>

                          {getPageNumbers().map((p, i) =>
                            p === '...'
                              ? <span key={`dot-${i}`} className="dash-page-dots">…</span>
                              : <button
                                  key={p}
                                  className={`page-btn${safePage === p ? ' page-btn-active' : ''}`}
                                  onClick={() => setDashPage(p)}
                                >{p}</button>
                          )}

                          <button className="page-btn" disabled={safePage >= totalPages}
                            onClick={() => setDashPage(safePage + 1)}>{t('app.nextPage')}</button>
                          <button className="page-btn" disabled={safePage >= totalPages}
                            onClick={() => setDashPage(totalPages)}>»</button>
                        </div>
                      )}

                      <span className="dash-page-info">
                        {pageSize === 0
                          ? t('app.pageInfo', 1, sorted.length, sorted.length)
                          : t('app.pageInfo', (safePage - 1) * pageSize + 1, Math.min(safePage * pageSize, sorted.length), sorted.length)}
                      </span>
                    </div>}
                  </>
                )}
              </div>
            )}

            {tab === 'stats' && (
              <div className="tab-content active">
                <h2>{t('app.statsTitle')}</h2>
                <StatsView certs={certs} teamStats={teamStats} onRowClick={(d) => setModalCert(certs.find(c => c.domain === d) ?? null)} />
              </div>
            )}

            {tab === 'warnings' && (
              <div className="tab-content active">
                <h2>{t('app.warningsTitle')}</h2>
                {warnings.length === 0 ? (
                  <div className="loading">{t('app.noWarnings')}</div>
                ) : (
                  <div className="cards-container">
                    {warnings.map((cert) => (
                      <CertificateCard key={cert.domain} cert={cert} onClick={(d) => setModalCert(certs.find(c => c.domain === d) ?? null)}
                        hasSilentAlert={silentAlertDomains.has(cert.domain)}
                        hasMailFailure={mailFailureDomains.has(cert.domain)}
                        onMailFailureClick={() => {
                          setTab('admin')
                          setAdminInitialTab('health')
                          setSmtpPreFilterDomain(cert.domain)
                          setOpenSmtpModalOnLoad(true)
                        }} />
                    ))}
                  </div>
                )}

                <div className="network-outage-history-section">
                  <h3 className="section-subtitle">
                    <Wifi size={18} /> {t('app.networkOutageHistoryTitle')}
                  </h3>
                  {outageHistory.length === 0 ? (
                    <div className="loading muted">{t('app.networkOutageHistoryEmpty')}</div>
                  ) : (
                    <div className="alert-history-cards">
                      {outageHistory.map(ev => {
                        const ratePct = ev.error_rate != null ? Math.round(ev.error_rate * 100) : null
                        const thresholdPct = ev.threshold != null ? Math.round(ev.threshold * 100) : null
                        const healthy = (ev.total_checks ?? 0) - (ev.network_errors ?? 0)
                        return (
                          <div key={ev.id} className="alert-history-card">
                            <div className="ahc-stripe" style={{ background: ev.status === 'ONGOING' ? '#dc2626' : '#10b981' }} />
                            <div className="ahc-body">
                              <div className="ahc-top">
                                <strong className="ahc-domain">
                                  {ev.status === 'ONGOING' ? t('app.outageOngoing') : t('app.outageResolved')}
                                </strong>
                                {ratePct != null && (
                                  <span className="ahc-days" style={{ background:'#fee2e2', color:'#991b1b' }}>
                                    {ratePct}% {t('app.outageErrorRate')}
                                  </span>
                                )}
                              </div>

                              <div className="ahc-timeline">
                                <div className="ahc-tl-item">
                                  <span className="ahc-tl-icon"><AlertOctagon size={13} /></span>
                                  <div>
                                    <div className="ahc-tl-label">{t('app.outageDetected')}</div>
                                    <div className="ahc-tl-val">{formatDate(ev.detected_at)}</div>
                                  </div>
                                </div>
                                <div className="ahc-tl-item ahc-tl-resolve">
                                  <span className="ahc-tl-icon"><CheckCircle size={13} /></span>
                                  <div>
                                    <div className="ahc-tl-label">{t('app.outageResolvedAt')}</div>
                                    <div className="ahc-tl-val">
                                      {ev.resolved_at
                                        ? formatDate(ev.resolved_at)
                                        : <em style={{ color: '#dc2626' }}>{t('app.outageStillActive')}</em>}
                                    </div>
                                  </div>
                                </div>
                                <div className="ahc-tl-item">
                                  <span className="ahc-tl-icon"><Clock size={13} /></span>
                                  <div>
                                    <div className="ahc-tl-label">{t('app.outageDuration')}</div>
                                    <div className="ahc-tl-val">
                                      {ev.duration_ms ? formatDurationShort(ev.duration_ms) : '—'}
                                    </div>
                                  </div>
                                </div>
                              </div>

                              <div className="outage-stats-grid">
                                <div className="outage-stat">
                                  <div className="outage-stat-label">{t('app.outageStatTotal')}</div>
                                  <div className="outage-stat-val">{ev.total_checks ?? '—'}</div>
                                </div>
                                <div className="outage-stat outage-stat-error">
                                  <div className="outage-stat-label">{t('app.outageStatErrors')}</div>
                                  <div className="outage-stat-val">{ev.network_errors ?? '—'}</div>
                                </div>
                                <div className="outage-stat outage-stat-ok">
                                  <div className="outage-stat-label">{t('app.outageStatHealthy')}</div>
                                  <div className="outage-stat-val">{healthy}</div>
                                </div>
                                <div className="outage-stat">
                                  <div className="outage-stat-label">{t('app.outageStatRate')}</div>
                                  <div className="outage-stat-val">{ratePct != null ? `${ratePct}%` : '—'}</div>
                                </div>
                                <div className="outage-stat">
                                  <div className="outage-stat-label">{t('app.outageStatThreshold')}</div>
                                  <div className="outage-stat-val">{thresholdPct != null ? `${thresholdPct}%` : '—'}</div>
                                </div>
                              </div>

                              <div className="outage-cause">
                                {t('app.outageCauseDesc', ev.network_errors ?? 0, ev.total_checks ?? 0,
                                   ratePct ?? 0, thresholdPct ?? 0)}
                              </div>
                            </div>
                          </div>
                        )
                      })}
                    </div>
                  )}
                </div>
              </div>
            )}

            {tab === 'all' && (
              <div className="tab-content active">
                <h2>{t('app.allTitle')}</h2>
                <CertificatesTable onRowClick={(d) => setModalCert(certs.find(c => c.domain === d) ?? null)} />
              </div>
            )}

            {tab === 'renewal' && (
              <div className="tab-content active">
                <h2>{t('app.renewalTitle')}</h2>
                <RenewalAdvice />
              </div>
            )}

            {tab === 'renewal-guide' && (
              <div className="tab-content active">
                <CertRenewalGuide isAdmin={systemRole === 'ADMIN'} />
              </div>
            )}

            {tab === 'activity' && (
              <div className="tab-content active">
                <h2>{t('app.activityTitle')}</h2>
                <ActivityLog refreshTrigger={activityRefreshKey} />
              </div>
            )}

            {tab === 'alerthistory' && systemRole === 'ADMIN' && (
              <div className="tab-content active">
                <h2>{t('app.alertHistoryTitle')}</h2>
                <AlertHistory />
              </div>
            )}

            {tab === 'domains' && (
              <div className="tab-content active">
                <InventoryManager onInventoryChange={loadData} systemRole={systemRole} />
              </div>
            )}

            {tab === 'admin' && (
              <div className="tab-content active">
                <h2>{t('app.adminTitle')}</h2>
                <AdminPanel systemRole={systemRole} />
              </div>
            )}

            {tab === 'system' && (systemRole === 'ADMIN' || systemRole === 'AUDIT') && (
              <div className="tab-content active">
                <h2>{t('app.systemTitle')}</h2>
                <AuditLogViewer />
              </div>
            )}

            {tab === 'weakalgo' && (systemRole === 'ADMIN' || systemRole === 'AUDIT') && (
              <div className="tab-content active">
                <h2>{t('app.weakAlgoTitle')}</h2>
                <WeakAlgorithmReport />
              </div>
            )}

            {tab === 'sqlplayground' && systemRole === 'ADMIN' && (
              <div className="tab-content active">
                <h2>{t('app.sqlPlaygroundTitle')}</h2>
                <SqlPlayground />
              </div>
            )}

            {tab === 'health' && systemRole === 'ADMIN' && (
              <div className="tab-content active">
                <h2>{t('app.healthTitle')}</h2>
                <SystemHealth
                  preFilterDomain={smtpPreFilterDomain}
                  openSmtpModalOnLoad={openSmtpModalOnLoad}
                  onSmtpPreFilterConsumed={() => {
                    setSmtpPreFilterDomain(null)
                    setOpenSmtpModalOnLoad(false)
                  }}
                />
              </div>
            )}

            {tab === 'help'     && <HelpPage />}
            {tab === 'uptime'   && <UptimePage />}
            {tab === 'port'     && <PortMonitorPage />}
            {tab === 'dns'      && <DnsMonitorPage />}
            {tab === 'forecast' && <ExpiryForecastPage />}
          </div>

          <footer className="footer">
            <p>{t('app.lastUpdate')} {lastUpdate ? formatDate(lastUpdate) : t('app.neverUpdated')}</p>
          </footer>

        </div>
      </main>

      <CertificateModal domain={modalCert?.domain} alertLevel={modalCert?.alert_level} initialData={modalCert?._preview ? modalCert : undefined} previewMode={!!modalCert?._preview} currentUser={user} currentUserRole={systemRole} onClose={() => setModalCert(null)} />
      {caModal && <CaDiversityModal certs={certs} onClose={() => setCaModal(false)} />}
    </div>
  )
}
