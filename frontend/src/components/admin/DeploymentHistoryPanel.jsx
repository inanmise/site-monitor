import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Check, DatabaseBackup, Download, Grid3x3, History, List, PenLine, RefreshCw, Rocket, ScrollText, SearchX } from 'lucide-react'
import { api, formatDate, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import ReleaseNotesPanel from '../ReleaseNotesPanel.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { usePagination } from '../../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { bumpIcon } from '../../utils/releaseUi.js'
import { TH } from './HealthUi.jsx'
import CurrentReleaseCard from './releases/CurrentReleaseCard.jsx'
import DeployKpis from './releases/DeployKpis.jsx'
import DeployTimeline from './releases/DeployTimeline.jsx'
import DeployRecords from './releases/DeployRecords.jsx'
import DeployToolbar, { ActiveChips } from './releases/DeployToolbar.jsx'
import ManualDeployModal from './releases/ManualDeployModal.jsx'
import { CurrentBadge, EnvBadge } from './releases/DeployBadges.jsx'
import {
  RANGES, SORTS, SOURCES, TIMELINE_KINDS, VIEWS,
  countByKind, deployStats, filterTransitions, rangeSince, toApiIso, transitionDurations,
} from './releases/releaseModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/**
 * Sistem Sağlığı → "Sürüm & Dağıtım" (K1, K8, K10; shadcn yeniden tasarımı 2026-09-27).
 *
 * <p>Düzen: koşan sürüm kartı (`releases/CurrentReleaseCard`) → göstergeler (`DeployKpis`) → görünüm sekmeleri
 * (URL `d_view`): **Zaman çizelgesi** (ay gruplu kartlar, istemci süzgeçli, sayfalı), **Kayıtlar** (sunucu sayfalı
 * ham kayıtlar — geniş kapta tablo, dar kapta kart; açılınca yüklenir), **Sürüm notları** (imaja gömülü yayın dizini,
 * paylaşılan `ReleaseNotesPanel`), **Ortamlar** (sürüm × ortam matrisi, açılınca yüklenir). Süzgeç çubuğu
 * (`DeployToolbar`) zaman çizelgesi ve kayıtlar için ortaktır; telefonda "Süzgeçler (n)" Sheet'i.
 *
 * <p>Yerleşim KAP genişliğine göre (`@container/deploy` + `useNarrowContainer`): Sistem Sağlığı 768 px tablette
 * kenar çubuğu açıkken ~440 px içerik bırakır — görünüm alanı kırılma noktası orada yanıltır.
 *
 * <p>Yazma eylemleri (elle kayıt / geri doldurma / elle kaydı silme) yalnız `canEdit` (`release_history.edit`).
 * URL param'ları `d_` önekiyle (PAGE_STATE_PREFIXES) — uygulamanın `tab`/`sec` anahtarlarına dokunmaz. Boş/hatalı
 * yanıtta çökmez: her istek `res?.success` ile okunur; hata "kayıt yok" gibi gösterilmez (yeniden dene).
 */

const pick = (list, v, fallback) => (list.includes(v) ? v : fallback)

/** Kap `limit` px'ten darsa true (telefon, kenar çubuğu açık tablet). jsdom'da genişlik 0 → dar sayılmaz. */
function useNarrowContainer(ref, limit = 640) {
  const [narrow, setNarrow] = useState(false)
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return undefined
    const check = () => setNarrow(el.clientWidth > 0 && el.clientWidth < limit)
    check()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => ro.disconnect()
  }, [ref, limit])
  return narrow
}

function TimelineSkeleton({ label }) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col gap-2.5 pl-10">
      <span className="sr-only">{label}</span>
      {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-24 w-full rounded-lg motion-reduce:animate-none" />)}
    </div>
  )
}

export default function DeploymentHistoryPanel({ canEdit = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const rootRef = useRef(null)
  const narrow = useNarrowContainer(rootRef)

  // ── Görünüm + süzgeç durumu (URL `d_*`) ─────────────────────────────────────────────────────────────
  const [view, setView] = useState(() => pick(VIEWS, readUrlParam('d_view', 'timeline'), 'timeline'))
  const [env, setEnv] = useState(() => readUrlParam('d_env', ''))
  const [source, setSource] = useState(() => pick(SOURCES, readUrlParam('d_source', ''), ''))
  const [kinds, setKinds] = useState(() => String(readUrlParam('d_kind', '')).split(',').filter((k) => TIMELINE_KINDS.includes(k)))
  const [range, setRange] = useState(() => pick(RANGES, readUrlParam('d_range', ''), ''))
  const [presetSince, setPresetSince] = useState(() => rangeSince(readUrlParam('d_range', '')))
  const [from, setFrom] = useState(() => readUrlParam('d_since', ''))
  const [to, setTo] = useState(() => readUrlParam('d_until', ''))
  const [q, setQ] = useState(() => readUrlParam('d_q', ''))
  const [qTerm, setQTerm] = useState(() => readUrlParam('d_q', '').trim())
  const [sort, setSort] = useState(() => pick(SORTS, readUrlParam('d_sort', 'started_at'), 'started_at'))
  const [dir, setDir] = useState(() => (readUrlParam('d_dir', 'desc') === 'asc' ? 'asc' : 'desc'))
  const [manualOpen, setManualOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [envList, setEnvList] = useState([])

  useEffect(() => { const id = setTimeout(() => setQTerm(q.trim()), 300); return () => clearTimeout(id) }, [q])

  const since = range === 'custom' ? toApiIso(from) : range ? presetSince : ''
  const until = range === 'custom' ? toApiIso(to) : ''
  const kindsKey = kinds.join(',')

  useUrlQuerySync({
    d_view: view === 'timeline' ? null : view,
    d_env: env || null, d_source: source || null, d_q: qTerm || null, d_kind: kindsKey || null,
    d_range: range || null, d_since: range === 'custom' ? from || null : null, d_until: range === 'custom' ? to || null : null,
    d_sort: sort === 'started_at' ? null : sort, d_dir: dir === 'desc' ? null : dir,
  })

  const mergeEnvs = useCallback((list) => {
    if (!Array.isArray(list)) return
    setEnvList((prev) => {
      const next = [...prev]
      for (const e of list) if (e && !next.includes(e)) next.push(e)
      return next.length === prev.length ? prev : next
    })
  }, [])

  // ── Koşan sürüm ────────────────────────────────────────────────────────────────────────────────────
  const [version, setVersion] = useState({ data: null, error: null })
  useEffect(() => {
    let alive = true
    setVersion((v) => ({ data: v.data, error: null }))
    Promise.resolve(api.system?.getVersion?.())
      .then((r) => {
        if (!alive) return
        if (r?.success) setVersion({ data: r.data && typeof r.data === 'object' ? r.data : {}, error: null })
        else setVersion((v) => ({ data: v.data, error: r?.error || true }))
      })
      .catch((e) => { if (alive) setVersion((v) => ({ data: v.data, error: e?.message || true })) })
    return () => { alive = false }
  }, [refreshKey])

  // ── Zaman çizelgesi + özet (seçili ortam; boş → koşan ortam) ─────────────────────────────────────────
  const [tl, setTl] = useState({ data: null, error: null, env: null })
  useEffect(() => {
    let alive = true
    // Ortam değişince eski ortamın verisi gösterilmez (iskelet); yenilemede eski veri kalır.
    setTl((p) => ({ data: p.env === env ? p.data : null, error: null, env }))
    Promise.resolve(api.admin?.getDeploymentTimeline?.(env || undefined))
      .then((r) => {
        if (!alive) return
        if (r?.success) {
          const data = r.data && typeof r.data === 'object' && !Array.isArray(r.data) ? r.data : {}
          setTl({ data, error: null, env })
          mergeEnvs(data.environments)
        } else setTl((p) => ({ ...p, error: r?.error || true }))
      })
      .catch((e) => { if (alive) setTl((p) => ({ ...p, error: e?.message || true })) })
    return () => { alive = false }
  }, [env, refreshKey, mergeEnvs])

  const tlData = tl.data || {}
  const transitions = useMemo(() => (Array.isArray(tl.data?.transitions) ? tl.data.transitions : []), [tl.data])
  const durations = useMemo(() => transitionDurations(transitions), [transitions])
  const stats = useMemo(() => deployStats(transitions), [transitions])
  const filtered = useMemo(
    () => filterTransitions(transitions, { source, kinds, since, until, q: qTerm }),
    [transitions, source, kindsKey, since, until, qTerm],   // eslint-disable-line react-hooks/exhaustive-deps
  )
  const kindCounts = useMemo(
    () => countByKind(filterTransitions(transitions, { source, since, until, q: qTerm })),
    [transitions, source, since, until, qTerm],
  )
  const pager = usePagination(filtered, { listKey: 'deploy-timeline', preset: 'panel',
    resetDeps: [env, source, kindsKey, since, until, qTerm], url: { pageKey: 'd_tpage', sizeKey: 'd_tps' } })

  // ── Kayıtlar (sunucu sayfalı; yalnız görünüm açıkken) ───────────────────────────────────────────────
  // Sayfalama standardı (2026-09-26): panel ön ayarı (25), sıfırlama yalnız süzgeç DEĞERİ değişince (mount'ta
  // değil — `d_page` derin bağlantısı korunur), URL d_page / d_ps. API 1-tabanlı.
  const sp = useServerPagination({ listKey: 'deployments', preset: 'panel', resetDeps: [env, source, qTerm, since, until, sort, dir],
    url: { pageKey: 'd_page', sizeKey: 'd_ps' }, apiBase: 1 })
  const { apiPage: page, pageSize: size, bind: bindTotal } = sp
  const params = useMemo(() => ({
    env: env || null, source: source || null, q: qTerm || null, since: since || null, until: until || null, page, size, sort, dir,
  }), [env, source, qTerm, since, until, page, size, sort, dir])
  const [rec, setRec] = useState({ rows: null, error: null })
  const loadSeq = useRef(0)
  useEffect(() => {
    if (view !== 'table') return
    const seq = ++loadSeq.current
    setRec((p) => ({ ...p, error: null }))
    Promise.resolve(api.admin?.getDeployments?.(params))
      .then((r) => {
        if (seq !== loadSeq.current) return
        if (r?.success) { setRec({ rows: Array.isArray(r.data) ? r.data : [], error: null }); bindTotal(r); mergeEnvs(r.environments) }
        else setRec((p) => ({ ...p, error: r?.error || true }))
      })
      .catch((e) => { if (seq === loadSeq.current) setRec((p) => ({ ...p, error: e?.message || true })) })
  }, [view, params, refreshKey, bindTotal, mergeEnvs])

  // ── Sürüm × ortam matrisi (görünüm açılınca bir kez) ───────────────────────────────────────────────
  const [matrix, setMatrix] = useState({ data: null, error: null })
  const loadMatrix = useCallback((all = false) => {
    setMatrix((m) => ({ ...m, error: null }))
    Promise.resolve(api.admin?.getDeploymentMatrix?.(all))
      .then((r) => { if (r?.success) setMatrix({ data: r.data || {}, error: null }); else setMatrix((m) => ({ ...m, error: r?.error || true })) })
      .catch((e) => setMatrix((m) => ({ ...m, error: e?.message || true })))
  }, [])
  useEffect(() => { if (view === 'matrix' && !matrix.data && !matrix.error) loadMatrix(false) }, [view, matrix.data, matrix.error, loadMatrix])

  const refresh = () => { setMatrix({ data: null, error: null }); setRefreshKey((k) => k + 1) }
  const errText = (e, fallbackKey) => (e === true ? t(fallbackKey) : String(e))

  // ── Süzgeç değişimi ────────────────────────────────────────────────────────────────────────────────
  function applyFilters(patch) {
    if ('q' in patch) setQ(patch.q)
    if ('env' in patch) setEnv(patch.env)
    if ('source' in patch) setSource(patch.source)
    if ('kinds' in patch) setKinds(patch.kinds)
    if ('range' in patch) {
      setRange(patch.range)
      setPresetSince(rangeSince(patch.range))
      if (patch.range !== 'custom') { setFrom(''); setTo('') }
    }
    if ('from' in patch) setFrom(patch.from)
    if ('to' in patch) setTo(patch.to)
  }
  function clearAll() {
    setQ(''); setQTerm(''); setEnv(''); setSource(''); setKinds([]); setRange(''); setFrom(''); setTo('')
  }
  function toggleSort(key) {
    if (sort === key) setDir((d) => (d === 'desc' ? 'asc' : 'desc'))
    else { setSort(key); setDir(key === 'started_at' ? 'desc' : 'asc') }
  }

  const rangeLabel = range === 'custom'
    ? `${from ? formatDate(from) : '…'} – ${to ? formatDate(to) : '…'}`
    : range ? t('deploy.range.' + range) : ''
  const chips = [
    qTerm && { key: 'q', label: `${t('deploy.searchLabel')}: ${qTerm}`, onRemove: () => { setQ(''); setQTerm('') } },
    env && { key: 'env', label: `${t('deploy.env')}: ${env}`, onRemove: () => setEnv('') },
    source && { key: 'source', label: `${t('deploy.source')}: ${t('version.source.' + source)}`, onRemove: () => setSource('') },
    view === 'timeline' && kinds.length > 0 && {
      key: 'kind', label: `${t('deploy.col.kind')}: ${kinds.map((k) => t('version.kind.' + k)).join(', ')}`, onRemove: () => setKinds([]),
    },
    range && { key: 'range', label: `${t('deploy.range')}: ${rangeLabel}`, onRemove: () => applyFilters({ range: '' }) },
  ].filter(Boolean)
  const activeCount = [env, source, view === 'timeline' && kinds.length > 0, range].filter(Boolean).length

  // ── Yazma eylemleri ────────────────────────────────────────────────────────────────────────────────
  async function backfill() {
    const n = tlData.backfillCandidates ?? 0
    const targetEnv = env || tlData.environment || version.data?.environment || ''
    if (!n) { toast.success(t('deploy.backfillNone')); return }
    const ok = await showConfirm({
      title: t('deploy.backfillConfirmTitle'), message: t('deploy.backfillConfirm', n, targetEnv),
      variant: 'primary', confirmText: t('deploy.backfill'), cancelText: t('deploy.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const r = await api.admin.backfillDeployments(targetEnv || null)
      if (r?.success) { toast.success(t('deploy.backfillDone', r.data?.inserted ?? 0, r.data?.skippedExisting ?? 0)); refresh() }
      else toast.error(r?.error || t('deploy.loadError'))
    } catch (e) { toast.error(e?.message || String(e)) }
    finally { setBusy(false) }
  }

  async function del(row) {
    const ok = await showConfirm({
      title: t('deploy.delete'), message: t('deploy.deleteConfirm', row.environment, row.version),
      variant: 'danger', confirmText: t('deploy.delete'), cancelText: t('deploy.cancel'),
    })
    if (!ok) return
    try {
      const r = await api.admin.deleteDeployment(row.id)
      if (r?.success) { toast.success(t('deploy.deleted')); refresh() }
      else toast.error(r?.error || t('deploy.loadError'))
    } catch (e) { toast.error(e?.message || String(e)) }
  }

  const live = version.data?.live
    || (tlData.environment && tlData.environment === version.data?.environment ? tlData.current : null)
  const csvUrl = api.admin?.getDeploymentsCsvUrl?.({
    env: env || null, source: source || null, q: qTerm || null, since: since || null, until: until || null, sort, dir,
  }) || '#'
  const listView = view === 'timeline' || view === 'table'
  const tlLoading = !tl.data && !tl.error
  const touch = 'h-10 @2xl/deploy:h-8'
  // Boş durumda eylem düğmesi TEKRARLANMAZ: geri doldurma / elle kayıt başlık satırında hep görünür (canEdit).
  const noMatch = (
    <StatusBlock tone="neutral" icon={SearchX} title={t('deploy.noMatch')} description={t('deploy.noMatchHint')} className="py-8"
      actions={<Button type="button" variant="outline" className={touch} onClick={clearAll}>{t('deploy.clearFilters')}</Button>} />
  )
  const retryBanner = (e) => (
    <AlertBanner tone="danger" role="alert" title={t('deploy.loadError')} className="mb-0"
      actions={<Button type="button" variant="secondary" size="sm" onClick={refresh}>{t('deploy.retry')}</Button>}>
      {errText(e, 'deploy.loadErrorHint')}
    </AlertBanner>
  )
  const tabCount = (n) => (n == null ? null : (
    <Badge variant="secondary" className="ml-0.5 h-5 min-w-5 rounded-full px-1.5 text-[11px] tabular-nums">{n}</Badge>
  ))

  return (
    <div ref={rootRef} data-slot="deploy-panel" data-narrow={narrow ? 'true' : undefined} className="@container/deploy flex min-w-0 flex-col gap-4">
      <CurrentReleaseCard version={version.data} error={version.error ? errText(version.error, 'deploy.loadErrorHint') : null}
        live={live} transitions={transitions} onRetry={refresh} onOpenNotes={() => setView('releases')} />

      <DeployKpis summary={tlData.summary} stats={tl.data ? stats : null} env={tlData.environment} loading={tlLoading} />

      <Tabs value={view} onValueChange={(v) => v && setView(v)} className="min-w-0 gap-3">
        <div className="flex min-w-0 flex-col gap-2 @3xl/deploy:flex-row @3xl/deploy:items-center @3xl/deploy:justify-between">
          <div className="-mx-1 min-w-0 overflow-x-auto px-1 pb-0.5">
            <TabsList aria-label={t('deploy.view.label')} className="w-max group-data-[orientation=horizontal]/tabs:h-auto">
              <TabsTrigger value="timeline" data-tab="timeline" className="min-h-10 px-3 @2xl/deploy:min-h-8">
                <History aria-hidden="true" />{t('deploy.timelineTitle')}{tl.data ? tabCount(filtered.length) : null}
              </TabsTrigger>
              <TabsTrigger value="table" data-tab="table" className="min-h-10 px-3 @2xl/deploy:min-h-8">
                <List aria-hidden="true" />{t('deploy.view.table')}{view === 'table' && sp.total != null ? tabCount(sp.total) : null}
              </TabsTrigger>
              <TabsTrigger value="releases" data-tab="releases" className="min-h-10 px-3 @2xl/deploy:min-h-8">
                <ScrollText aria-hidden="true" />{t('deploy.view.releases')}
              </TabsTrigger>
              <TabsTrigger value="matrix" data-tab="matrix" className="min-h-10 px-3 @2xl/deploy:min-h-8">
                <Grid3x3 aria-hidden="true" />{t('deploy.view.matrix')}
              </TabsTrigger>
            </TabsList>
          </div>
          <div data-slot="deploy-actions" className="flex min-w-0 flex-wrap items-center gap-2 @3xl/deploy:justify-end">
            <Button type="button" variant="outline" size="icon" className="size-10 shrink-0 @2xl/deploy:size-8"
              onClick={refresh} title={t('deploy.refresh')} aria-label={t('deploy.refresh')}>
              <RefreshCw aria-hidden="true" />
            </Button>
            {listView && (
              <Button asChild variant="outline" className={cn(touch, 'flex-1 @3xl/deploy:flex-none')}>
                <a href={csvUrl} download="deployment-history.csv"><Download aria-hidden="true" /> {t('deploy.csv')}</a>
              </Button>
            )}
            {canEdit && (
              <>
                <Button type="button" variant="outline" className={cn(touch, 'flex-1 @3xl/deploy:flex-none')} onClick={backfill} disabled={busy}>
                  <DatabaseBackup aria-hidden="true" /> {t('deploy.backfill')}
                  {tlData.backfillCandidates > 0 && <>{' '}<Badge variant="secondary" className="rounded-full px-1.5 tabular-nums">{tlData.backfillCandidates}</Badge></>}
                </Button>
                <Button type="button" className={cn(touch, 'flex-1 @3xl/deploy:flex-none')} onClick={() => setManualOpen(true)}>
                  <PenLine aria-hidden="true" /> {t('deploy.manualAdd')}
                </Button>
              </>
            )}
          </div>
        </div>

        {listView && (
          <div className="flex min-w-0 flex-col gap-2">
            <DeployToolbar narrow={narrow} environments={envList} showKinds={view === 'timeline'} kindCounts={kindCounts}
              filters={{ q, env, source, kinds, range, from, to }} onChange={applyFilters} activeCount={activeCount} onClearAll={clearAll} />
            <ActiveChips chips={chips} onClearAll={clearAll} />
          </div>
        )}

        <TabsContent value="timeline" className="flex min-w-0 flex-col gap-3">
          {tl.error && retryBanner(tl.error)}
          {tlLoading && <TimelineSkeleton label={t('deploy.loading')} />}
          {tl.data && transitions.length === 0 && (
            <StatusBlock tone="neutral" icon={Rocket} title={t('deploy.empty')} description={t('deploy.emptyHint')} className="py-8" />
          )}
          {tl.data && transitions.length > 0 && filtered.length === 0 && noMatch}
          {filtered.length > 0 && (
            <>
              <p data-slot="deploy-timeline-caption" className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                <EnvBadge env={tlData.environment} />
                <span>{t('deploy.shownOf', filtered.length, transitions.length)}</span>
                {tlData.restartCount > 0 && <span>· {t('deploy.restartsFolded', tlData.restartCount)}</span>}
                {!env && <span>· {t('deploy.timelineEnvNote')}</span>}
              </p>
              <DeployTimeline items={pager.pageItems} durations={durations} canEdit={canEdit} onDelete={del} />
              <PaginationBar {...pager} />
            </>
          )}
        </TabsContent>

        <TabsContent value="table" className="flex min-w-0 flex-col gap-3">
          {rec.error && retryBanner(rec.error)}
          {rec.rows == null && !rec.error && <TimelineSkeleton label={t('deploy.loading')} />}
          {rec.rows && rec.rows.length === 0 && !rec.error && (chips.length ? noMatch : (
            <StatusBlock tone="neutral" icon={History} title={t('deploy.empty')} description={t('deploy.emptyHint')} className="py-8" />
          ))}
          {rec.rows && rec.rows.length > 0 && (
            <DeployRecords rows={rec.rows} narrow={narrow} canEdit={canEdit} onDelete={del} sort={sort} dir={dir} onSort={toggleSort} />
          )}
          {rec.rows && <PaginationBar {...sp.bar} />}
        </TabsContent>

        <TabsContent value="releases" className="min-w-0">
          <ReleaseNotesPanel />
        </TabsContent>

        <TabsContent value="matrix" className="flex min-w-0 flex-col gap-2.5">
          <p className="text-xs text-muted-foreground">{t('deploy.matrixHint')}</p>
          {matrix.error && (
            <AlertBanner tone="danger" role="alert" title={t('deploy.loadError')} className="mb-0"
              actions={<Button type="button" variant="secondary" size="sm" onClick={() => loadMatrix(false)}>{t('deploy.retry')}</Button>}>
              {errText(matrix.error, 'deploy.loadErrorHint')}
            </AlertBanner>
          )}
          {!matrix.data && !matrix.error && <Skeleton className="h-48 w-full rounded-lg motion-reduce:animate-none" />}
          {matrix.data && (
            <ReleaseMatrix data={matrix.data} running={version.data?.version} t={t}
              showAll={matrix.data.truncated && (
                <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  {t('deploy.matrixTruncated')}
                  <Button type="button" variant="outline" className={touch} onClick={() => loadMatrix(true)}>{t('deploy.matrixShowAll')}</Button>
                </div>
              )} />
          )}
        </TabsContent>
      </Tabs>

      <ManualDeployModal open={manualOpen} onClose={() => setManualOpen(false)} onSaved={refresh} environments={envList}
        defaultEnv={env || tlData.environment || version.data?.environment || ''} />
    </div>
  )
}

/**
 * Sürüm × ortam matrisi: her sürümün her ortamda İLK canlıya alındığı an (BACKFILL hariç). İlk sütun yatay
 * kaydırmada sabit (telefon); koşan sürüm "şu an" rozetiyle. Test kancası: `data-slot="deploy-matrix"`.
 * ("Tümünü göster" çağıranda — sayfalama kapısının DISCLOSURES kaydı bu dosyayı gösterir.)
 */
function ReleaseMatrix({ data, running, showAll, t }) {
  const envs = Array.isArray(data.environments) ? data.environments : []
  const releases = Array.isArray(data.releases) ? data.releases : []
  if (!releases.length) {
    return <StatusBlock tone="neutral" icon={Grid3x3} title={t('releases.empty')} description={t('releases.noIndex')} className="py-8" />
  }
  const STICKY = 'sticky left-0 z-[1] bg-card'
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <div className="overflow-hidden rounded-lg border bg-card">
        <Table data-slot="deploy-matrix" className="text-[0.86em]">
          <TableHeader className="bg-muted/50">
            <TableRow className="hover:bg-transparent">
              <TableHead className={cn(TH, STICKY, 'bg-muted')}>{t('deploy.col.version')}</TableHead>
              <TableHead className={cn(TH, 'hidden @2xl/deploy:table-cell')}>{t('version.released')}</TableHead>
              {envs.map((e) => <TableHead key={e} className={TH}>{e}</TableHead>)}
            </TableRow>
          </TableHeader>
          <TableBody>
            {releases.map((r) => {
              const BumpIcon = bumpIcon(r.bump)
              return (
                <TableRow key={r.version} data-never={r.neverDeployed ? 'true' : undefined}>
                  <TableCell className={cn('px-3 py-2', STICKY)}>
                    <span className="flex flex-wrap items-center gap-1.5">
                      <span className={cn('font-mono font-bold', r.neverDeployed && 'text-muted-foreground')}>v{r.version}</span>
                      {r.bump && <BumpIcon role="img" aria-label={t('version.bump.' + r.bump)} className="size-3.5 text-muted-foreground" />}
                      {running && r.version === running && <CurrentBadge />}
                    </span>
                  </TableCell>
                  <TableCell className="hidden px-3 py-2 font-mono text-xs @2xl/deploy:table-cell" title={r.releasedAt ? formatDateSec(r.releasedAt) : undefined}>
                    {r.releasedAt ? formatDate(r.releasedAt) : '—'}
                  </TableCell>
                  {envs.map((e) => {
                    const at = r.deployedIn?.[e]
                    return (
                      <TableCell key={e} className="px-3 py-2 text-xs whitespace-nowrap" title={at ? formatDateSec(at) : undefined}>
                        {at ? (
                          <span className="inline-flex items-center gap-1 font-mono text-success">
                            <Check aria-hidden="true" className="size-3.5" />{formatDate(at)}
                          </span>
                        ) : <span className="text-muted-foreground">{r.neverDeployed ? t('deploy.matrixNever') : '—'}</span>}
                      </TableCell>
                    )
                  })}
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
      {showAll}
    </div>
  )
}
