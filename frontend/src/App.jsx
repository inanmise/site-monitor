import { useState, useEffect, useCallback, useRef } from 'react'
import { ChevronUp, ChevronDown } from 'lucide-react'
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
import RenewalAdvice from './components/RenewalAdvice'
import AdminPanel from './components/admin/AdminPanel'
import AlertHistory from './components/admin/AlertHistory'
import InventoryManager from './components/admin/InventoryManager'
import AuditLogViewer from './components/admin/AuditLogViewer'
import WeakAlgorithmReport from './components/admin/WeakAlgorithmReport'
import SystemHealth from './components/admin/SystemHealth'
import ActivityLog from './components/ActivityLog'
import HelpPage from './components/HelpPage'
import UptimePage from './components/UptimePage'
import PortMonitorPage from './components/PortMonitorPage'
import DnsMonitorPage from './components/DnsMonitorPage'

const INACTIVITY_MS   = Number(import.meta.env.VITE_INACTIVITY_MS   ?? 300_000)
const WARN_BEFORE_MS  = Number(import.meta.env.VITE_WARN_BEFORE_MS  ?? 60_000)


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
  const [teamStats, setTeamStats] = useState(null)
  const [statsVisible, setStatsVisible] = useState(true)
  const [search, setSearch] = useState('')
  const [sortOrder, setSortOrder] = useState('default')
  const [modalDomain, setModalDomain] = useState(null)
  const [newDomain, setNewDomain] = useState('')
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
    const [certsRes, statsRes, silentRes, teamStatsRes] = await Promise.all([
      api.getCertificates(), api.getStats(), api.getSilentAlertDomains(), api.getTeamStats(),
    ])
    if (certsRes?.success) { setCerts(certsRes.data); setLastUpdate(certsRes.timestamp) }
    if (statsRes?.success) setStats(statsRes.data)
    if (silentRes?.success) setSilentAlertDomains(new Set(silentRes.data))
    if (teamStatsRes?.success) setTeamStats(teamStatsRes.data)
  }, [])

  useEffect(() => {
    if (user) {
      loadData()
      const interval = setInterval(loadData, Number(import.meta.env.VITE_DATA_REFRESH_MS ?? 300_000))
      return () => clearInterval(interval)
    }
  }, [user, loadData])

  useEffect(() => {
    if (user && tab === 'warnings') {
      api.getWarnings().then((res) => { if (res?.success) setWarnings(res.data) })
    }
  }, [user, tab])

  useEffect(() => {
    if (tab === 'dashboard') setStatsVisible(true)
  }, [tab])

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
    if (!newDomain.trim()) return
    let domain = newDomain.trim()
    domain = domain.replace(/^https?:\/\//i, '')
    domain = domain.split('/')[0]
    if (domain.includes(':')) domain = domain.split(':')[0]
    if (!domain) return
    await api.checkDomain(domain)
    setNewDomain('')
    setTimeout(loadData, 1000)
  }

  function handleLogin(userData) {
    setTab('dashboard')
    setUser(userData.username)
    setSystemRole(userData.system_role || 'USER')
    setTeamId(userData.team_id ?? null)
    setTeamName(userData.team_name ?? null)
  }

  if (!authChecked) return <div className="loading" style={{ marginTop: 80, textAlign: 'center' }}>{t('app.loading')}</div>
  if (!user) return <Login onLogin={handleLogin} />

  const STAT_FILTER_FN = {
    total:      () => true,
    valid:      (c) => !c.warning && c.status !== 'error',
    warning:    (c) => c.warning === true && c.status !== 'error',
    error:      (c) => c.status === 'error',
    expiring30: (c) => c.days_remaining != null && c.days_remaining >= 0 && c.days_remaining <= 30,
    expired:    (c) => c.days_remaining != null && c.days_remaining < 0,
  }
  const STAT_FILTER_LABEL = {
    total: t('stat.total'), valid: t('stat.valid'), warning: t('stat.warning'),
    error: t('stat.error'), expiring30: t('stat.expiring30'), expired: t('stat.expired'),
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
    const tier = c.tier ?? 99
    const isProblematic = c.status === 'error' || c.warning
    const statusPri = c.status === 'error' ? 0 : 1
    if (isProblematic) return tier * 10 + statusPri
    return 1000 + tier
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

      <Nav activeTab={tab} onTabChange={setTab} username={user} teamName={teamName} systemRole={systemRole} onLogout={handleLogout} />

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
            <input className="search-box" type="text" placeholder={t('app.searchPlaceholder')} value={search}
              onChange={(e) => { setSearch(e.target.value); setDashPage(1) }} />
            <div className="add-domain-section">
              <input className="domain-input" type="text" placeholder={t('app.newDomainPlaceholder')}
                value={newDomain} onChange={(e) => setNewDomain(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleAddDomain()} />
              <button className="btn btn-success" onClick={handleAddDomain}>{t('app.checkBtn')}</button>
            </div>
          </div>

          {tab === 'dashboard' && (
            <div className="stats-section">
              <div className="stats-collapse-bar">
                <span className="stats-collapse-label">{t('app.statistics')}</span>
                <button
                  className="stats-collapse-btn"
                  onClick={() => setStatsVisible((v) => !v)}
                  title={statsVisible ? t('app.collapseStats') : t('app.expandStats')}
                >
                  {statsVisible ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
                </button>
              </div>
              <StatsPanel stats={stats} visible={statsVisible}
                onStatClick={handleStatClick} activeFilter={statsFilter} />
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
                        <CertificateCard key={cert.domain} cert={cert} onClick={setModalDomain}
                          hasSilentAlert={silentAlertDomains.has(cert.domain)} />
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
                <StatsView certs={certs} teamStats={teamStats} onRowClick={setModalDomain} />
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
                      <CertificateCard key={cert.domain} cert={cert} onClick={setModalDomain}
                        hasSilentAlert={silentAlertDomains.has(cert.domain)} />
                    ))}
                  </div>
                )}
              </div>
            )}

            {tab === 'all' && (
              <div className="tab-content active">
                <h2>{t('app.allTitle')}</h2>
                <CertificatesTable onRowClick={setModalDomain} />
              </div>
            )}

            {tab === 'renewal' && (
              <div className="tab-content active">
                <h2>{t('app.renewalTitle')}</h2>
                <RenewalAdvice />
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

            {tab === 'health' && systemRole === 'ADMIN' && (
              <div className="tab-content active">
                <h2>{t('app.healthTitle')}</h2>
                <SystemHealth />
              </div>
            )}

            {tab === 'help'   && <HelpPage />}
            {tab === 'uptime' && <UptimePage />}
            {tab === 'port'   && <PortMonitorPage />}
            {tab === 'dns'    && <DnsMonitorPage />}
          </div>

          <footer className="footer">
            <p>{t('app.lastUpdate')} {lastUpdate ? formatDate(lastUpdate) : t('app.neverUpdated')}</p>
          </footer>

        </div>
      </main>

      <CertificateModal domain={modalDomain} onClose={() => setModalDomain(null)} />
    </div>
  )
}
