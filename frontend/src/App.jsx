import { useState, useEffect, useCallback, useRef } from 'react'
import { api, formatDate } from './api/client'
import { useDialog } from './components/ui/Dialog.jsx'
import { useT } from './i18n/index.jsx'
import Login from './pages/Login'
import Nav from './components/Nav'
import StatsPanel from './components/StatsPanel'
import CertificateCard from './components/CertificateCard'
import CertificatesTable from './components/CertificatesTable'
import CertificateModal from './components/CertificateModal'
import RenewalAdvice from './components/RenewalAdvice'
import AdminPanel from './components/admin/AdminPanel'
import ActivityLog from './components/ActivityLog'

const INACTIVITY_MS = 10 * 60 * 1000
const WARN_BEFORE_MS = 60 * 1000

export default function App() {
  const { showConfirm } = useDialog()
  const t = useT()
  const [user, setUser] = useState(null)
  const [authChecked, setAuthChecked] = useState(false)
  const [tab, setTab] = useState('dashboard')
  const [certs, setCerts] = useState([])
  const [warnings, setWarnings] = useState([])
  const [stats, setStats] = useState(null)
  const [statsVisible, setStatsVisible] = useState(false)
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
  const [activityRefreshKey, setActivityRefreshKey] = useState(0)

  const PAGE_SIZE = 12

  const logoutTimer = useRef(null)
  const warnTimer = useRef(null)
  const countdownInterval = useRef(null)
  const refreshPollRef = useRef(null)

  useEffect(() => {
    api.getMe().then((res) => {
      if (res?.success) setUser(res.username)
      setAuthChecked(true)
    }).catch(() => setAuthChecked(true))
  }, [])

  useEffect(() => {
    if (!user) return

    const doAutoLogout = async () => {
      clearInterval(countdownInterval.current)
      await api.logout()
      setUser(null)
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
    const [certsRes, statsRes] = await Promise.all([api.getCertificates(), api.getStats()])
    if (certsRes?.success) { setCerts(certsRes.data); setLastUpdate(certsRes.timestamp) }
    if (statsRes?.success) setStats(statsRes.data)
  }, [])

  useEffect(() => {
    if (user) {
      loadData()
      const interval = setInterval(loadData, 5 * 60 * 1000)
      return () => clearInterval(interval)
    }
  }, [user, loadData])

  useEffect(() => {
    if (user && tab === 'warnings') {
      api.getWarnings().then((res) => { if (res?.success) setWarnings(res.data) })
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
    setUser(null)
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
        const statsRes = await api.getStats()
        if (statsRes?.success) setStats(statsRes.data)
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

  if (!authChecked) return <div className="loading" style={{ marginTop: 80, textAlign: 'center' }}>{t('app.loading')}</div>
  if (!user) return <Login onLogin={setUser} />

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

  const sorted = [...filtered].sort((a, b) => {
    if (sortOrder === 'asc') return (a.days_remaining ?? 999999) - (b.days_remaining ?? 999999)
    if (sortOrder === 'desc') return (b.days_remaining ?? -1) - (a.days_remaining ?? -1)
    return (a.warning ? 0 : 1) - (b.warning ? 0 : 1)
  })

  const totalPages = Math.max(1, Math.ceil(sorted.length / PAGE_SIZE))
  const safePage   = Math.min(dashPage, totalPages)
  const pageCerts  = sorted.slice((safePage - 1) * PAGE_SIZE, safePage * PAGE_SIZE)

  return (
    <div className="app-layout">

      {inactivityWarning && (
        <div className="inactivity-warning">
          <span dangerouslySetInnerHTML={{ __html: t('app.inactivityWarn', `<strong>${countdown}</strong>`) }} />
          <button onClick={() => setInactivityWarning(false)}>{t('app.stayLoggedIn')}</button>
        </div>
      )}

      <Nav activeTab={tab} onTabChange={setTab} username={user} onLogout={handleLogout} />

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
            <button className="btn btn-secondary" onClick={() => setStatsVisible((v) => !v)}>{t('app.statistics')}</button>
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
            <div className="sort-controls">
              <label>{t('app.sortLabel')}</label>
              <select className="sort-select" value={sortOrder} onChange={(e) => setSortOrder(e.target.value)}>
                <option value="default">{t('app.sortDefault')}</option>
                <option value="asc">{t('app.sortAsc')}</option>
                <option value="desc">{t('app.sortDesc')}</option>
              </select>
              <label>{t('app.statusLabel')}</label>
              <select className="sort-select" value={statusFilter} onChange={(e) => { setStatusFilter(e.target.value); setDashPage(1) }}>
                <option value="all">{t('app.all')}</option>
                <option value="valid">{t('app.valid')}</option>
                <option value="warning">{t('app.warning')}</option>
                <option value="error">{t('app.error')}</option>
              </select>
              <label>{t('app.expiryLabel')}</label>
              <select className="sort-select" value={expiryFilter} onChange={(e) => { setExpiryFilter(e.target.value); setDashPage(1) }}>
                <option value="all">{t('app.all')}</option>
                <option value="days7">{t('app.days7')}</option>
                <option value="days30">{t('app.days30')}</option>
                <option value="days90">{t('app.days90')}</option>
                <option value="expired">{t('app.expired')}</option>
              </select>
            </div>
          )}

          <StatsPanel stats={stats} visible={statsVisible}
            onStatClick={handleStatClick} activeFilter={statsFilter} />

          <div className="content">
            {tab === 'dashboard' && (
              <div className="tab-content active">
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
                        <CertificateCard key={cert.domain} cert={cert} onClick={setModalDomain} />
                      ))}
                    </div>
                    {totalPages > 1 && (
                      <div className="pagination">
                        <button
                          className="page-btn"
                          disabled={safePage <= 1}
                          onClick={() => setDashPage(safePage - 1)}
                        >{t('app.prevPage')}</button>
                        <span className="page-info">
                          {t('app.pageInfo', (safePage - 1) * PAGE_SIZE + 1, Math.min(safePage * PAGE_SIZE, sorted.length), sorted.length)}
                        </span>
                        <button
                          className="page-btn"
                          disabled={safePage >= totalPages}
                          onClick={() => setDashPage(safePage + 1)}
                        >{t('app.nextPage')}</button>
                      </div>
                    )}
                  </>
                )}
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
                      <CertificateCard key={cert.domain} cert={cert} onClick={setModalDomain} />
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

            {tab === 'admin' && (
              <div className="tab-content active">
                <h2>{t('app.adminTitle')}</h2>
                <AdminPanel onInventoryChange={loadData} />
              </div>
            )}
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
