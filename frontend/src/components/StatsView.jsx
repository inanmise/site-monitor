import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { CalendarPlus, Grid3x3, Inbox, Info, Plus, RefreshCw, SearchX } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { usePagination } from '../hooks/usePagination.js'
import { readUrlParam, useUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { downloadCsv, stampedName } from '../utils/csvExport.js'
import { clientFailure } from '../utils/errorMessages.js'
import { useElementWidthState } from '../hooks/useElementWidth.js'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import { useToast } from './ui/Toast.jsx'
import ExecutiveSummary from './ExecutiveSummary.jsx'
import StatsOverview from './stats/StatsOverview.jsx'
import TeamTierMatrix from './stats/TeamTierMatrix.jsx'
import StatsToolbar from './stats/StatsToolbar.jsx'
import CertList from './stats/CertList.jsx'
import StatsHeaderBar from './stats/StatsHeaderBar.jsx'
import ExpiryHistogram from './stats/ExpiryHistogram.jsx'
import AlarmTrendCard from './stats/AlarmTrendCard.jsx'
import MonitorTypesSummary from './stats/MonitorTypesSummary.jsx'
import SecurityPostureCard from './stats/SecurityPostureCard.jsx'
import CompositionCard from './stats/CompositionCard.jsx'
import TeamScorecard from './stats/TeamScorecard.jsx'
import { HealthSeriesCard, RenewalTimelinessCard } from './stats/TrendCards.jsx'
import { useStatsSources } from './stats/useStatsSources.js'
import {
  NO_TEAM, PAGE_URL, PROD_TIERS, TIERS, applyFilters, buildTeams, domainTeamMap, issuerOptions, kpiCounts, matrixRows, resolveTeam,
  sortCerts, stateFromUrl, statsCsv, toUrlMapping, weekWindows, withTeamless,
} from './stats/statsModel.js'
import { freshestCommon, securityCounts, sortScorecard, teamScorecard } from './stats/statsInsights.js'
import { matrixSheetRows, scorecardSheetRows, summaryRows, tableSheetRows } from './stats/statsExportModel.js'
import { formatDateOnly } from '../api/client'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'

/**
 * İstatistikler (Raporlar → İstatistikler, `?tab=stats`) — 2026-09-28 shadcn + mobil web yeniden tasarımı; 2026-11-13
 * zenginleştirme (16 madde).
 *
 * Yapı (üstten alta): üst çubuk (son güncelleme · Yenile · kayıtlı görünümler · Excel / PDF) → Genel bakış (KPI = tablo
 * süzgeci · kalan süre dağılımı · yaklaşan bitişler) → 12 haftalık bitiş çubuğu → Operasyon özeti (filo sağlığı / açık
 * alarm / SLA + en kötü SLA) → alarm eğilimi + "ne değişti" · izleme türleri → güvenlik duruşu · sağlayıcı / katman →
 * zamanında yenileme · 90 günlük sağlık → takım karnesi → takım × katman matrisi → sertifika tablosu (satır eylemleri).
 *
 * Durum URL'de (`st_*` — PAGE_STATE_PREFIXES; sekme değişince App temizler; kayıtlı görünüm = `VIEW_SPECS.stats`): KPI
 * `st_k`, arama `st_q`, sağlayıcı `st_iss`, takım `st_team` (sayı ya da `none` = takımsız / pasif takım), katman `st_tier`,
 * seviye `st_lvl`, güvenlik `st_sec`, hafta `st_wk`, sıralama `st_sort`, matris `st_mx` / `st_mxall`, sayfa `st_page` /
 * `st_ps`. Süzgeç koyan her etkileşim tablo görünür değilse onu kaydırıp gösterir.
 *
 * App sözleşmesi: `certs`, `teamStats` (+ `teamStatsStatus` loading | failed | ok), `onRowClick(domain)`, `canAddDomain` +
 * `onAddDomain`, `loading`, `lastUpdate` + `onRefresh` (App'in veri dalgası), satır eylemleri için Pano'nun kapıları
 * (`cardActions`, `canPlan`, `onPlanRenewal`, `cardExtras`). Sertifikası olmayan kullanıcı da alarm / SLA / izleme özetini
 * görür (eskiden sayfa boş kutuyla bitiyordu).
 */
const SEARCH_DEBOUNCE_MS = 250
const NARROW_PX = 640

function StatsSkeleton({ label }) {
  return (
    <div aria-busy="true" data-slot="stats-skeleton" className="flex min-w-0 flex-col gap-4">
      <span role="status" className="sr-only">{label}</span>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
        {Array.from({ length: 7 }, (_, i) => <Skeleton key={i} className="h-24 rounded-lg" />)}
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Skeleton className="h-64 rounded-xl" /><Skeleton className="h-64 rounded-xl" />
      </div>
      <Skeleton className="h-9 w-full sm:w-2/3" />
      {Array.from({ length: 5 }, (_, i) => <Skeleton key={i} className="h-12 rounded-lg" />)}
    </div>
  )
}

/** Gün anahtarı (İstanbul) — hafta pencereleri gün değişince yeniden kurulsun, her çizimde değil. */
function istanbulToday() {
  return new Date(Date.now() + 3 * 3_600_000).toISOString().slice(0, 10)
}

export default function StatsView({
  certs = [], teamStats, teamStatsStatus, onRowClick, onAddDomain, canAddDomain = false, loading = false,
  lastUpdate = null, onRefresh, cardActions, canPlan, onPlanRenewal, cardExtras,
}) {
  const t = useT()
  const toast = useToast()
  const isMobile = useIsMobile()
  const perm = usePermissions()

  // ── Süzgeç durumu (URL `st_*`) ──────────────────────────────────────────────────────────────────
  const [init] = useState(() => stateFromUrl(readUrlParam))
  const [kpi, setKpi] = useState(init.kpi)
  const [qInput, setQInput] = useState(init.q)
  const [q, setQ] = useState(init.q)
  const [issuers, setIssuers] = useState(init.issuers)
  const [teamId, setTeamId] = useState(init.team)
  const [tier, setTier] = useState(init.tier)
  const [level, setLevel] = useState(init.level)
  const [sec, setSec] = useState(init.sec)
  const [week, setWeek] = useState(init.week)
  const [sort, setSort] = useState(init.sort)
  const [matrixOpen, setMatrixOpen] = useState(init.matrixOpen)
  const [allTiers, setAllTiers] = useState(init.allTiers)
  useEffect(() => {
    if (qInput === q) return undefined
    const id = setTimeout(() => setQ(qInput), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [qInput, q])
  useUrlQuerySync(toUrlMapping({ kpi, q, issuers, team: teamId, tier, level, sort, matrixOpen, allTiers, sec, week }))

  // ── Sayfanın kendi kaynakları (izne bağlı) ───────────────────────────────────────────────────────
  const permsReady = perm.loaded || perm.failed
  const [healthTeam, setHealthTeam] = useState(null)
  const src = useStatsSources({
    permsLoaded: permsReady,
    canMonitoring: perm.canView('monitoring.read'),
    canAlerts: perm.canView('alerts.read'),
    canDataQuality: perm.canView('data_quality.view'),
    healthTeam,
  })
  const exec = src.exec.data
  const onHealthTeam = (v) => { setHealthTeam(v); src.loadHealth(v) }

  // ── Türetme ─────────────────────────────────────────────────────────────────────────────────────
  const baseTeams = useMemo(() => buildTeams(teamStats), [teamStats])
  const teams = useMemo(() => withTeamless(baseTeams, certs, t('stv.teamless')), [baseTeams, certs, t])
  const dtm = useMemo(() => domainTeamMap(teams), [teams])
  const team = useMemo(() => resolveTeam(teamId, teams, certs), [teamId, teams, certs])
  const today = istanbulToday()
  const windows = useMemo(() => weekWindows(), [today])   // eslint-disable-line react-hooks/exhaustive-deps
  const weekWin = week != null ? windows[week] ?? null : null
  const base = useMemo(() => ({ kpi, q, issuers, team, tier, level, sec, week: weekWin }), [kpi, q, issuers, team, tier, level, sec, weekWin])
  const filtered = useMemo(() => sortCerts(applyFilters(certs, base, dtm), sort), [certs, base, dtm, sort])
  const issuerOpts = useMemo(() => issuerOptions(certs, applyFilters(certs, { ...base, issuers: [] }, dtm)), [certs, base, dtm])
  const mxRows = useMemo(() => matrixRows(certs, teams, allTiers ? TIERS : PROD_TIERS), [certs, teams, allTiers])
  const slaBlock = exec?.sla && !exec.sla.error_msg ? exec.sla : null
  // Kapsamlı (kurumun tamamını görmeyen) kullanıcıda `/certificates` yalnız SY dilimini getirir; sunucu özetleri (yönetici
  // özeti, yenileme oranı, sağlık eğrisi) SY ∪ UG sayar. Sayılar GERÇEKTEN ayrışabildiğinde (görülen bir takımın UG alanı
  // listede yok) kısa bir not (inceleme 2026-11-13). Kapsam bilinmiyorsa (eski sunucu) not yok.
  const ugBeyondList = useMemo(() => {
    if (perm.scope?.monitoring_all !== false) return false
    const lean = teamStats?.mode === 'domains' && Array.isArray(teamStats.teams) ? teamStats.teams : null
    if (!lean) return false
    const have = new Set((certs || []).map((c) => c.domain))
    return lean.some((tm) => (tm.ug_domains || []).some((d) => !have.has(d)))
  }, [perm.scope, teamStats, certs])
  const scRows = useMemo(() => teamScorecard({
    teams, certs, alerts: src.teamAlerts.data, sla: slaBlock, dq: src.dq.data,
  }), [teams, certs, src.teamAlerts.data, slaBlock, src.dq.data])
  const pager = usePagination(filtered, {
    listKey: 'stats-table', preset: 'page', url: PAGE_URL,
    resetDeps: [kpi, q, issuers.join('|'), teamId, tier, level, sort, sec, week],
  })

  // ── Yerleşim: kart/tablo kararı KAP genişliğinden ───────────────────────────────────────────────
  const [listWidth, setListWidthEl] = useElementWidthState()
  const [matrixWidth, setMatrixEl] = useElementWidthState()
  const listEl = useRef(null)
  const setListNode = useCallback((el) => { listEl.current = el; setListWidthEl(el) }, [setListWidthEl])
  const narrowList = isMobile || (listWidth > 0 && listWidth < NARROW_PX)
  const narrowMatrix = isMobile || (matrixWidth > 0 && matrixWidth < NARROW_PX)

  /** Tablo görünür değilse (telefonda birkaç ekran aşağıda) başına kaydır; hareket azaltılmışsa animasyonsuz. */
  const reveal = useCallback(() => {
    const run = () => {
      const el = listEl.current
      if (!el || typeof el.scrollIntoView !== 'function') return
      const top = el.getBoundingClientRect().top
      if (top >= 0 && top < (window.innerHeight || 0) * 0.5) return
      let reduce = false
      try { reduce = !!window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches } catch { /* eski tarayıcı */ }
      el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
    }
    if (typeof window.requestAnimationFrame === 'function') window.requestAnimationFrame(run)
    else setTimeout(run, 0)
  }, [])

  // ── Eylemler ────────────────────────────────────────────────────────────────────────────────────
  const clearWidget = () => { setTeamId(null); setTier(null); setLevel(null) }
  const onKpi = (k) => {
    if (kpi === k) { setKpi(null); return }
    setKpi(k); reveal()
  }
  const onClearKpi = () => { setKpi(null); reveal() }
  const sameTeam = (id) => teamId != null && String(teamId) === String(id)
  const onCell = (r, tr, l) => {
    if (sameTeam(r.id) && tier === tr && level === l) { clearWidget(); return }
    setTeamId(String(r.id)); setTier(tr); setLevel(l); reveal()
  }
  const onTeam = (r) => {
    if (sameTeam(r.id) && tier == null && level == null) { setTeamId(null); return }
    setTeamId(String(r.id)); setTier(null); setLevel(null); reveal()
  }
  const onTier = (r, tr) => {
    if (sameTeam(r.id) && tier === tr && level == null) { clearWidget(); return }
    setTeamId(String(r.id)); setTier(tr); setLevel(null); reveal()
  }
  const onScoreTeam = (r) => onTeam({ id: r.teamless ? NO_TEAM : r.id })
  const onSecFilter = (v) => { setSec(v); if (v) reveal() }
  const onWeekFilter = (v) => { setWeek(v); if (v != null) reveal() }
  const onIssuerFilter = (list) => { setIssuers(list); if (list.length) reveal() }
  const onTierFilter = (v) => { setTier(v); if (v != null) reveal() }
  const onShowUpcoming = () => { setKpi('d30'); setSort('days_remaining|asc'); reveal() }
  const clearSearch = () => { setQInput(''); setQ('') }
  const clearAll = () => { setKpi(null); clearSearch(); setIssuers([]); clearWidget(); setSec(null); setWeek(null) }
  const exportCsv = () => downloadCsv(stampedName('certificate-statistics'), statsCsv(filtered, t, dtm))

  const [refreshing, setRefreshing] = useState(false)
  const refresh = async () => {
    setRefreshing(true)
    try { await Promise.allSettled([Promise.resolve(onRefresh?.()), src.reload(true)]) } finally { setRefreshing(false) }
  }
  const updatedAt = freshestCommon(lastUpdate, src.loadedAt)

  const weekRange = (w) => t('stx.weekRange', formatDateOnly(w.start), formatDateOnly(new Date(Date.parse(`${w.end}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10)))
  const secLabel = (v) => (v.startsWith('g:')
    ? t('stx.chip.grade', v === 'g:none' ? t('stx.secUngraded') : v.slice(2))
    : t('stx.chip.sec', t(`stx.sec.${v}`)))
  const chips = [
    ...(kpi ? [{ key: 'kpi', label: t(`stv.kpi.${kpi}`), onRemove: () => setKpi(null) }] : []),
    ...(team ? [{ key: 'team', label: t('stv.chip.team', team.name || t('stv.teamUnknown')), onRemove: () => setTeamId(null) }] : []),
    ...(tier != null ? [{ key: 'tier', label: t('stv.chip.tier', tier === 0 ? t('tier.descNone') : `T${tier}`), onRemove: () => setTier(null) }] : []),
    ...(level ? [{ key: 'level', label: t('stv.chip.level', t(`ts.${level}`)), onRemove: () => setLevel(null) }] : []),
    ...(sec ? [{ key: 'sec', label: secLabel(sec), onRemove: () => setSec(null) }] : []),
    ...(weekWin ? [{ key: 'week', label: t('stx.chip.week', weekRange(weekWin)), onRemove: () => setWeek(null) }] : []),
    ...(issuers.length ? [{ key: 'issuer', label: t('stv.chip.issuer', issuers.join(', ')), onRemove: () => setIssuers([]) }] : []),
    ...(q.trim() ? [{ key: 'q', label: `“${q.trim()}”`, onRemove: clearSearch }] : []),
  ]

  // ── Satır eylemleri: Pano kartıyla AYNI kapılar (App) ──────────────────────────────────────────────
  const rowActions = useMemo(() => {
    if (typeof cardActions !== 'function' && typeof onPlanRenewal !== 'function') return undefined
    return (c) => {
      const items = []
      const a = typeof cardActions === 'function' && !c.paused ? cardActions(c) : null
      if (a?.onCheckNow) {
        items.push({ label: a.checking ? t('stx.checking') : t('stx.checkNow'), icon: <RefreshCw aria-hidden="true" />,
          disabled: !!a.checking, onClick: () => a.onCheckNow() })
      }
      if (typeof onPlanRenewal === 'function' && (typeof canPlan !== 'function' || canPlan(c) !== false)) {
        const plan = cardExtras?.[c.domain]?.renewal ?? null
        items.push({ label: plan ? t('forecast.editPlan') : t('forecast.planRenewal'), icon: <CalendarPlus aria-hidden="true" />,
          onClick: () => onPlanRenewal(c, plan) })
      }
      return items
    }
  }, [cardActions, canPlan, onPlanRenewal, cardExtras, t])

  // ── Dışa aktarım (Excel / PDF, tembel modül) ──────────────────────────────────────────────────────
  const [exporting, setExporting] = useState(null)
  const nameOf = (r) => (r.teamless ? t('stv.teamless') : r.name || t('stv.teamUnknown'))
  const doExport = async (kind) => {
    setExporting(kind)
    try {
      const mod = await import('./stats/statsExport.js')
      const sheets = {
        summary: summaryRows({ t, kpi: kpiCounts(certs), exec, security: securityCounts(certs), renewal: src.renewal.data,
          filters: chips.map((c) => c.label).join(' · ') || null }),
        matrix: matrixSheetRows(mxRows, t, nameOf),
        table: tableSheetRows(filtered, t, dtm),
        scorecard: scorecardSheetRows(sortScorecard(scRows, 'health', 'asc'), t, nameOf,
          { alerts: !!src.teamAlerts.data, sla: !!slaBlock, dq: !!src.dq.data }),
      }
      const ok = kind === 'xlsx' ? mod.exportStatsXlsx(sheets, t) : await mod.exportStatsPdf(sheets, t)
      if (ok === false) toast.error(t('stx.exportFailed'))
    } catch (e) {
      const f = clientFailure(e, { fallback: t('stx.exportFailed'), chunk: t('stx.exportFailed') })
      toast.error(f.message, { details: f.details })
    } finally { setExporting(null) }
  }

  const addDomain = canAddDomain && onAddDomain
    ? <Button type="button" variant="success" className="max-sm:h-10 pointer-coarse:h-10" onClick={onAddDomain}><Plus aria-hidden="true" />{t('inv.addBtn')}</Button>
    : null

  if (loading && certs.length === 0) return <StatsSkeleton label={t('stv.loading')} />
  const hasCerts = certs.length > 0
  // Bölüm başına "Tekrar dene" yalnız o kaynağı `fresh` ile okur (hata taraması 2026-11-13: tüm sayfayı bellekten okuyordu)
  const retryExec = () => src.retry('exec'), retryNoise = () => src.retry('noise')
  const retryOverview = () => src.retry('overview'), retryRenewal = () => src.retry('renewal')

  return (
    <div data-slot="stats-view" className="flex min-w-0 flex-col gap-6">
      <StatsHeaderBar updatedAt={updatedAt} busy={refreshing || src.busy} onRefresh={refresh} onExport={doExport} exporting={exporting} />

      {hasCerts ? (
        <>
          <StatsOverview certs={certs} kpi={kpi} onKpi={onKpi} onClearKpi={onClearKpi} onOpen={(d) => onRowClick?.(d)} onShowUpcoming={onShowUpcoming} />
          <ExpiryHistogram certs={certs} windows={windows} week={week} onWeek={onWeekFilter} />
        </>
      ) : (
        <StatusBlock tone="neutral" icon={Inbox} title={t('stv.emptyTitle')} description={t('empty.hintCerts')} actions={addDomain}
          className="rounded-xl border border-dashed" />
      )}

      <ExecutiveSummary source={src.exec} onRetry={retryExec} canMonitoring={perm.canView('monitoring.read')} />
      {ugBeyondList && (
        <p data-slot="stats-ug-note" className="-mt-3 mb-0 flex items-start gap-2 text-xs text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('stx.ugNote')}
        </p>
      )}

      <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-2">
        <AlarmTrendCard source={src.noise} onRetry={retryNoise} />
        <MonitorTypesSummary source={src.overview} onRetry={retryOverview} />
      </div>

      {hasCerts && (
        <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-2">
          <SecurityPostureCard certs={certs} sec={sec} onSec={onSecFilter} />
          <CompositionCard certs={certs} issuers={issuers} onIssuer={onIssuerFilter} tier={tier} onTier={onTierFilter} />
        </div>
      )}

      <div className="grid min-w-0 grid-cols-1 gap-4 xl:grid-cols-2">
        <RenewalTimelinessCard source={src.renewal} onRetry={retryRenewal} />
        <HealthSeriesCard source={src.health} onRetry={() => src.retry('health')} team={healthTeam} onTeam={onHealthTeam} />
      </div>

      <TeamScorecard rows={scRows} onTeam={onScoreTeam} selectedTeam={teamId}
        alertsKnown={!!src.teamAlerts.data} slaKnown={Array.isArray(slaBlock?.by_team)} dqKnown={!!src.dq.data}
        status={teamStatsStatus || 'ok'} onRetry={onRefresh ? refresh : undefined} />

      {hasCerts && (
        <>
          {/* Takım × katman — ui/CollapsibleSection (projenin tek katlanır şeridi); açık/kapalı URL'de */}
          <CollapsibleSection open={matrixOpen} onOpenChange={setMatrixOpen} icon={Grid3x3} label={t('stv.mxTitle')}
            hint={mxRows.length ? t('stv.mxHint', mxRows.length) : teamStatsStatus === 'failed' ? t('stx.mxFailed') : t('stv.mxHintEmpty')}
            toggleLabel={matrixOpen ? t('stv.mxHide') : t('stv.mxShow')} contentClassName="pt-3" data-slot="stats-matrix-section">
            <div ref={setMatrixEl} className="min-w-0">
              <TeamTierMatrix rows={mxRows} allTiers={allTiers} onAllTiers={setAllTiers} narrow={narrowMatrix}
                sel={{ teamId, tier, level }} onCell={onCell} onTeam={onTeam} onTier={onTier}
                status={teamStatsStatus || 'ok'} onRetry={onRefresh ? refresh : undefined} />
            </div>
          </CollapsibleSection>

          <section ref={setListNode} data-slot="stats-list" aria-label={t('stv.listTitle')} className="flex min-w-0 scroll-mt-20 flex-col gap-3">
            <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
              <div className="flex min-w-0 flex-col gap-0.5">
                <h2 data-slot="stats-section-title" className="m-0 text-base font-semibold">{t('stv.listTitle')}</h2>
                <p className="m-0 text-sm text-muted-foreground">{t('stv.listDesc')}</p>
              </div>
              {addDomain}
            </div>
            <StatsToolbar q={qInput} onQ={setQInput} kpi={kpi} onKpi={setKpi} issuerOpts={issuerOpts} issuers={issuers} onIssuers={setIssuers}
              sort={sort} onSort={setSort} chips={chips} onClearAll={clearAll} shown={filtered.length} total={certs.length} onExport={exportCsv} />
            {filtered.length === 0 ? (
              <StatusBlock tone="neutral" icon={SearchX} title={t('stv.noResults')} description={t('stv.noResultsHint')}
                className="rounded-xl border border-dashed"
                actions={(
                  <>
                    {chips.length > 0 && <Button type="button" variant="outline" className="max-sm:h-10 pointer-coarse:h-10" onClick={clearAll}>{t('app.clearFilters')}</Button>}
                    {addDomain}
                  </>
                )} />
            ) : (
              <CertList rows={pager.pageItems} narrow={narrowList} dtm={dtm} onOpen={(d) => onRowClick?.(d)} sort={sort} onSort={setSort}
                rowActions={rowActions} />
            )}
            <PaginationBar {...pager} />
          </section>
        </>
      )}
    </div>
  )
}
