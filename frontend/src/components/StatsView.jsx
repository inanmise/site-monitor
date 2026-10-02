import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Grid3x3, Inbox, Plus, SearchX } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { usePagination } from '../hooks/usePagination.js'
import { readUrlParam, useUrlQuerySync } from '../hooks/useUrlQuerySync.js'
import { downloadCsv, stampedName } from '../utils/csvExport.js'
import { useElementWidthState } from '../hooks/useElementWidth.js'
import CollapsibleSection from './ui/CollapsibleSection.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import ExecutiveSummary from './ExecutiveSummary.jsx'
import StatsOverview from './stats/StatsOverview.jsx'
import TeamTierMatrix from './stats/TeamTierMatrix.jsx'
import StatsToolbar from './stats/StatsToolbar.jsx'
import CertList from './stats/CertList.jsx'
import {
  PAGE_URL, PROD_TIERS, TIERS, applyFilters, buildTeams, domainTeamMap, issuerOptions, matrixRows, resolveTeam, sortCerts,
  stateFromUrl, statsCsv, toUrlMapping,
} from './stats/statsModel.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'

/**
 * İstatistikler (Raporlar → İstatistikler, `?tab=stats`) — 2026-09-28 shadcn + mobil web yeniden tasarımı.
 *
 * Yapı (üstten alta): Genel bakış (KPI kutucukları = tablo süzgeci · kalan süre dağılımı · yaklaşan bitişler) →
 * Operasyon özeti (ExecutiveSummary: filo sağlığı / alarm / SLA gezinme kutucukları + takım karşılaştırması) → Takım ×
 * katman matrisi (katlanır; hücre = takım + katman + seviye süzgeci) → Sertifikalar (süzgeç çubuğu, çipler, tablo /
 * telefonda kart, standart sayfalama, CSV).
 *
 * Durum URL'de (`st_*` — PAGE_STATE_PREFIXES; sekme değişince App temizler): KPI `st_k`, arama `st_q` (gecikmeli),
 * sağlayıcı `st_iss`, takım `st_team`, katman `st_tier`, seviye `st_lvl`, sıralama `st_sort`, matris açık `st_mx`,
 * tüm katmanlar `st_mxall`, sayfa `st_page`/`st_ps` (usePagination). Yenileme / paylaşılan bağlantı aynı görünümü açar.
 *
 * Süzgeç koyan her etkileşim (KPI, matris hücresi, takım satırı, "Tabloda göster") tablo görünür değilse onu kaydırıp
 * gösterir — telefonda tablo birkaç ekran aşağıda kalıyordu, dokunuşun sonucu görünmüyordu.
 *
 * App sözleşmesi DEĞİŞMEDİ: `certs` (/api/certificates), `teamStats` (/api/stats/teams), `onRowClick(domain)` sertifika
 * penceresini açar, `canAddDomain` + `onAddDomain`. `loading` (isteğe bağlı): ilk veri gelene kadar iskelet — yoksa boş
 * liste yalancı "sertifika yok" gösterirdi.
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

export default function StatsView({ certs = [], teamStats, onRowClick, onAddDomain, canAddDomain = false, loading = false }) {
  const t = useT()
  const isMobile = useIsMobile()

  // ── Süzgeç durumu (URL `st_*`) ──────────────────────────────────────────────────────────────────
  const [init] = useState(() => stateFromUrl(readUrlParam))
  const [kpi, setKpi] = useState(init.kpi)
  const [qInput, setQInput] = useState(init.q)
  const [q, setQ] = useState(init.q)
  const [issuers, setIssuers] = useState(init.issuers)
  const [teamId, setTeamId] = useState(init.team)
  const [tier, setTier] = useState(init.tier)
  const [level, setLevel] = useState(init.level)
  const [sort, setSort] = useState(init.sort)
  const [matrixOpen, setMatrixOpen] = useState(init.matrixOpen)
  const [allTiers, setAllTiers] = useState(init.allTiers)
  useEffect(() => {
    if (qInput === q) return undefined
    const id = setTimeout(() => setQ(qInput), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [qInput, q])
  useUrlQuerySync(toUrlMapping({ kpi, q, issuers, team: teamId, tier, level, sort, matrixOpen, allTiers }))

  // ── Türetme ─────────────────────────────────────────────────────────────────────────────────────
  const teams = useMemo(() => buildTeams(teamStats), [teamStats])
  const dtm = useMemo(() => domainTeamMap(teams), [teams])
  const team = useMemo(() => resolveTeam(teamId, teams, certs), [teamId, teams, certs])
  const base = useMemo(() => ({ kpi, q, issuers, team, tier, level }), [kpi, q, issuers, team, tier, level])
  const filtered = useMemo(() => sortCerts(applyFilters(certs, base, dtm), sort), [certs, base, dtm, sort])
  const issuerOpts = useMemo(() => issuerOptions(certs, applyFilters(certs, { ...base, issuers: [] }, dtm)), [certs, base, dtm])
  const mxRows = useMemo(() => matrixRows(certs, teams, allTiers ? TIERS : PROD_TIERS), [certs, teams, allTiers])
  const pager = usePagination(filtered, {
    listKey: 'stats-table', preset: 'page', url: PAGE_URL,
    resetDeps: [kpi, q, issuers.join('|'), teamId, tier, level, sort],
  })

  // ── Yerleşim: kart/tablo kararı KAP genişliğinden (tablette kenar çubuğu açıkken içerik dar) ─────────────
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
  const onExecTeam = (id) => onTeam({ id })
  const onShowUpcoming = () => { setKpi('d30'); setSort('days_remaining|asc'); reveal() }
  const clearSearch = () => { setQInput(''); setQ('') }
  const clearAll = () => { setKpi(null); clearSearch(); setIssuers([]); clearWidget() }
  const exportCsv = () => downloadCsv(stampedName('certificate-statistics'), statsCsv(filtered, t, dtm))

  const chips = [
    ...(kpi ? [{ key: 'kpi', label: t(`stv.kpi.${kpi}`), onRemove: () => setKpi(null) }] : []),
    ...(team ? [{ key: 'team', label: t('stv.chip.team', team.name), onRemove: () => setTeamId(null) }] : []),
    ...(tier != null ? [{ key: 'tier', label: t('stv.chip.tier', tier === 0 ? t('tier.descNone') : `T${tier}`), onRemove: () => setTier(null) }] : []),
    ...(level ? [{ key: 'level', label: t('stv.chip.level', t(`ts.${level}`)), onRemove: () => setLevel(null) }] : []),
    ...(issuers.length ? [{ key: 'issuer', label: t('stv.chip.issuer', issuers.join(', ')), onRemove: () => setIssuers([]) }] : []),
    ...(q.trim() ? [{ key: 'q', label: `“${q.trim()}”`, onRemove: clearSearch }] : []),
  ]
  const addDomain = canAddDomain && onAddDomain
    ? <Button type="button" variant="success" className="max-sm:h-10 pointer-coarse:h-10" onClick={onAddDomain}><Plus aria-hidden="true" />{t('inv.addBtn')}</Button>
    : null

  if (loading && certs.length === 0) return <StatsSkeleton label={t('stv.loading')} />
  if (certs.length === 0) {
    return (
      <StatusBlock tone="neutral" icon={Inbox} title={t('stv.emptyTitle')} description={t('empty.hintCerts')} actions={addDomain}
        className="rounded-xl border border-dashed" />
    )
  }

  return (
    <div data-slot="stats-view" className="flex min-w-0 flex-col gap-6">
      <StatsOverview certs={certs} kpi={kpi} onKpi={onKpi} onClearKpi={onClearKpi} onOpen={(d) => onRowClick?.(d)} onShowUpcoming={onShowUpcoming} />

      <ExecutiveSummary onOpenTeam={onExecTeam} />

      {/* Takım × katman — ui/CollapsibleSection (projenin tek katlanır şeridi); açık/kapalı URL'de */}
      <CollapsibleSection open={matrixOpen} onOpenChange={setMatrixOpen} icon={Grid3x3} label={t('stv.mxTitle')}
        hint={mxRows.length ? t('stv.mxHint', mxRows.length) : t('stv.mxHintEmpty')}
        toggleLabel={matrixOpen ? t('stv.mxHide') : t('stv.mxShow')} contentClassName="pt-3" data-slot="stats-matrix-section">
        <div ref={setMatrixEl} className="min-w-0">
          <TeamTierMatrix rows={mxRows} allTiers={allTiers} onAllTiers={setAllTiers} narrow={narrowMatrix}
            sel={{ teamId, tier, level }} onCell={onCell} onTeam={onTeam} onTier={onTier} />
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
          <CertList rows={pager.pageItems} narrow={narrowList} dtm={dtm} onOpen={(d) => onRowClick?.(d)} sort={sort} onSort={setSort} />
        )}
        <PaginationBar {...pager} />
      </section>
    </div>
  )
}
