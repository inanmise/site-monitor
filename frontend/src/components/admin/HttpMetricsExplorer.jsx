import { useState, useEffect, useMemo, useId, useCallback } from 'react'
import { CircleAlert, Inbox, Save } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useIsWide } from '../../hooks/useIsWide.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import HttpExplorerToolbar from './httpmetrics/HttpExplorerToolbar.jsx'
import HttpTrafficChart from './httpmetrics/HttpTrafficChart.jsx'
import HttpStatusCodes from './httpmetrics/HttpStatusCodes.jsx'
import HttpEndpointTable from './httpmetrics/HttpEndpointTable.jsx'
import HttpEndpointDetail from './httpmetrics/HttpEndpointDetail.jsx'
import { Tile } from './httpmetrics/HttpParts.jsx'
import { useHttpOverview } from './httpmetrics/useHttpOverview.js'
import {
  METHOD_ORDER, activeFilterCount, countForClasses, endpointCsv, errTone, filterEndpoints, fmtInt, fmtPct, fmtRate,
  isLiveRange, makeMs, msTone, sortEndpoints, widerRange,
} from './httpmetrics/httpMetricsModel.js'

const RETENTION_KEY = 'site.monitor.metrics.http.retention-days'

/**
 * İstek Gezgini (2026-09-28 yeniden tasarım) — uygulamanın kendi API isteklerinin dakikalık kalıcı serisi üzerinde:
 * yapışkan süzgeç çubuğu (aralık + uç / yöntem / durum sınıfı; telefonda alt Sheet; etkin süzgeç çipleri; otomatik
 * yenileme + son güncelleme + elle yenile), özet kutucukları, durum sınıfına göre hacim + süre grafiği (seri aç/kapa),
 * durum kodu dağılımı, uç tablosu (sıralama, arama, sayfalama, CSV; telefonda/tablette kart) ve seçili ucun ayrıntı
 * paneli. Veri TEK çağrıyla (`/http-metrics/overview`); yenileme aralık/süzgeç/sıralama/seçimi KORUR (state burada).
 * Saklama süresi ayarı en altta (Genel Ayarlar anahtarı; kapsamlı müdürde salt okunur).
 *
 * @param initialFocus `{ endpoint }` — Sistem Sağlığı bölümünden bir uçla açıldıysa: o uca süzülü, son 24 saat.
 */
export default function HttpMetricsExplorer({ initialFocus = null }) {
  const t = useT()
  const toast = useToast()
  const phone = useIsMobile()
  const wide = useIsWide(1024)
  const focusEp = initialFocus?.endpoint || ''
  const [range, setRange] = useState(() => (focusEp ? { type: 'rel', minutes: 1440, key: '24h' } : { type: 'rel', minutes: 60, key: '1h' }))
  const [filters, setFilters] = useState(() => ({ endpoint: focusEp, methods: [], classes: [] }))
  const [q, setQ] = useState('')
  const [sort, setSort] = useState({ key: 'count', dir: 'desc' })
  const [selected, setSelected] = useState(null)
  const [auto, setAuto] = useState(true)
  const fmt = useMemo(() => makeMs({ ms: t('rtc.unit.ms'), sec: t('rtc.unit.s') }), [t])
  const { view, loading, error, stale, refreshing, reload } = useHttpOverview({ range, endpoint: filters.endpoint, methods: filters.methods, auto })
  const data = view?.data || null

  // ── Saklama süresi (Genel Ayarlar) — yetki yoksa sessiz → kutu gizli; GLOBAL_ONLY anahtar müdürde salt okunur ──
  const [retention, setRetention] = useState(null)
  const [retentionLocked, setRetentionLocked] = useState(false)
  const [savingRet, setSavingRet] = useState(false)
  const retentionId = useId()
  useEffect(() => {
    api.admin.getGeneralSettings().then((res) => {
      if (!res?.success) return
      const list = res.data?.settings ?? res.data ?? []
      const row = Array.isArray(list) ? list.find((s) => s.key === RETENTION_KEY) : null
      if (row) { setRetention(String(row.value ?? row.default ?? '7')); setRetentionLocked(!!row.read_only) }
    }).catch(() => {})
  }, [])
  async function saveRetention() {
    const d = parseInt(retention, 10)
    if (!Number.isFinite(d) || d < 1) { toast.error(t('http.exp.retentionInvalid')); return }
    setSavingRet(true)
    try {
      try {
        const res = await api.admin.saveGeneralSettings({ values: { [RETENTION_KEY]: String(d) } })
        if (res?.success) toast.success(t('http.exp.retentionSaved', d))
        else toast.error(res?.error || t('http.exp.saveError'))
      } catch { toast.error(t('http.exp.saveError')) }
    } finally {
      setSavingRet(false)
    }
  }

  // ── Türetimler ──
  const allEndpoints = useMemo(() => data?.endpoints ?? [], [data])
  const rows = useMemo(() => sortEndpoints(filterEndpoints(allEndpoints, { ...filters, q }), sort), [allEndpoints, filters, q, sort])
  const endpointOptions = useMemo(() => {
    const names = allEndpoints.map((e) => e.endpoint)
    return filters.endpoint && !names.includes(filters.endpoint) ? [filters.endpoint, ...names] : names
  }, [allEndpoints, filters.endpoint])
  const methodOptions = useMemo(() => {
    const seen = new Set([...allEndpoints.map((e) => e.method).filter(Boolean), ...filters.methods])
    return [...METHOD_ORDER.filter((m) => seen.has(m)), ...[...seen].filter((m) => !METHOD_ORDER.includes(m))]
  }, [allEndpoints, filters.methods])
  const selectedRow = selected ? allEndpoints.find((e) => e.endpoint === selected) || null : null

  const downloadCsv = useCallback(() => {
    const csv = endpointCsv(rows, t)
    try {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const el = document.createElement('a')
      el.href = url
      el.download = `http-istekleri-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(el)
      el.click()
      el.remove()
      setTimeout(() => { try { URL.revokeObjectURL(url) } catch { /* bellek tarayıcıda sayfayla birlikte serbest kalır */ } }, 0)
    } catch { toast.error(t('hreq.tbl.csvError')) }
  }, [rows, t, toast])

  const s = data?.summary
  const cls = filters.classes
  const rangeLabel = range?.type === 'abs' ? `${String(range.from).replace('T', ' ')} → ${String(range.to).replace('T', ' ')}` : t(`range.${range?.key || '1h'}`)
  const wider = widerRange(range)
  const nFilters = activeFilterCount(filters)

  return (
    <div data-testid="hme-panel" className="flex min-w-0 flex-col gap-3">
      <HttpExplorerToolbar t={t} phone={phone} range={range} onRange={setRange} filters={filters} onFilters={setFilters}
        endpointOptions={endpointOptions} methodOptions={methodOptions}
        live={auto} liveAvailable={isLiveRange(range)} onLive={setAuto} updatedAt={view?.at} busy={loading} onRefresh={reload}
        shownCount={rows.length} />

      {error && view && (
        <AlertBanner tone="warning" className="mb-0" title={t('hreq.err.title')}
          actions={<Button type="button" variant="secondary" size="sm" className="max-lg:h-10 pointer-coarse:h-10" onClick={reload}>{t('db.retry')}</Button>}>
          {t('hreq.stale')}
          {/* sunucunun kendi iletisi (ör. doğrulama) ek satırda — bayat veri açıklaması kalır (2026-09-28c ek-4) */}
          {error.server && error.message ? <span className="mt-0.5 block text-xs">{error.message}</span> : null}
        </AlertBanner>
      )}

      {!view && error && (
        <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('hreq.err.title')}
          description={error.server && error.message ? error.message : t('hreq.err.desc')}
          actions={<Button type="button" variant="secondary" className="max-lg:h-10 pointer-coarse:h-10" onClick={reload}>{t('db.retry')}</Button>} />
      )}

      {!view && !error && (
        <div data-slot="hreq-skeleton" role="status" aria-live="polite" className="flex flex-col gap-3">
          <span className="sr-only">{t('modal.loading')}</span>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
            {Array.from({ length: 6 }, (_, i) => <Skeleton key={i} className="h-[76px] rounded-lg motion-reduce:animate-none" />)}
          </div>
          <Skeleton className="h-[360px] rounded-xl motion-reduce:animate-none" />
        </div>
      )}

      {data && s && (
        <div data-slot="hreq-body" aria-busy={loading || undefined}
          className={cn('flex min-w-0 flex-col gap-3 transition-opacity motion-reduce:transition-none', stale && 'opacity-60')}>
          {data.clamped && <AlertBanner tone="info" className="mb-0">{t('hreq.chart.clamped')}</AlertBanner>}
          {data.capped && <AlertBanner tone="warning" className="mb-0">{t('hreq.chart.capped')}</AlertBanner>}

          <div role="group" aria-label={t('hreq.kpi.label')} data-slot="hreq-tiles" className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
            <Tile t={t} id="total" label={t('hreq.kpi.total')} value={fmtInt(countForClasses(s, cls))}
              sub={cls.length ? t('hreq.kpi.ofTotal', fmtInt(s.total)) : t('hreq.kpi.rateSub', fmtRate(s.reqPerMin))} hint={t('hreq.hint.totalRange')} />
            <Tile t={t} id="errors" label={t('hreq.kpi.errorRate')} value={fmtPct(s.errorRate)} tone={errTone(s.errorRate, s.total)}
              sub={t('hreq.kpi.errorsSplit', fmtInt(s.errors), fmtInt(s.classes['5xx']))} hint={t('hreq.hint.errorRateRange')} />
            <Tile t={t} id="avg" label={t('hreq.kpi.avg')} value={fmt.value(s.avg)} tone={msTone(s.avg)}
              sub={s.p50 != null ? t('hreq.kpi.p50Sub', fmt.value(s.p50)) : null} hint={t('hreq.hint.avgRange')} />
            <Tile t={t} id="p95" label={t('hreq.kpi.p95')} value={fmt.value(s.p95)} tone={msTone(s.p95)}
              sub={s.p99 != null ? t('hreq.kpi.p99Sub', fmt.value(s.p99)) : null} hint={t('hreq.hint.p95')} />
            <Tile t={t} id="max" label={t('hreq.kpi.max')} value={fmt.value(s.max)}
              sub={s.min != null && s.total > 0 ? t('hreq.kpi.minSub', fmt.value(s.min)) : null} hint={t('hreq.hint.max')} />
            <Tile t={t} id="endpoints" label={t('hreq.kpi.endpoints')} value={fmtInt(data.endpointsTotal)}
              sub={t('hreq.kpi.endpointsErr', fmtInt(allEndpoints.filter((e) => e.errors > 0).length))} hint={t('hreq.hint.endpoints')} />
          </div>
          {cls.length > 0 && <p className="m-0 text-xs text-muted-foreground">{t('hreq.f.statusNote')}</p>}

          {s.total === 0 ? (
            <Card className="gap-0 py-0 shadow-xs">
              <StatusBlock icon={Inbox} title={t('hreq.chart.empty')} description={t('hreq.chart.emptyHint')}
                actions={<>
                  {wider && <Button type="button" variant="outline" className="max-lg:h-10 pointer-coarse:h-10" onClick={() => setRange(wider)}>{t('hreq.chart.widen', t(`range.${wider.key}`))}</Button>}
                  {nFilters > 0 && <Button type="button" variant="ghost" className="max-lg:h-10 pointer-coarse:h-10" onClick={() => setFilters({ endpoint: '', methods: [], classes: [] })}>{t('hreq.f.clearAll')}</Button>}
                </>} />
            </Card>
          ) : (
            <HttpTrafficChart t={t} model={data} fmt={fmt} statusFilter={cls} busy={refreshing}
              summaryText={t('hreq.chart.summary', rangeLabel, fmtInt(s.total), fmtPct(s.errorRate), fmt.value(s.avg), fmt.value(s.p95))} />
          )}

          <HttpStatusCodes t={t} summary={s} codes={data.codes} statusFilter={cls} />

          <HttpEndpointTable t={t} rows={rows} totalCount={allEndpoints.length} truncatedTotal={data.truncated ? data.endpointsTotal : 0}
            wide={wide} fmt={fmt} q={q} onQ={setQ} sort={sort} onSort={setSort} selected={selected} onSelect={setSelected}
            onCsv={downloadCsv} resetKey={JSON.stringify([filters, q, sort, range])} />
        </div>
      )}

      {retention != null && (
        <Card data-testid="hme-retention" className="flex-row flex-wrap items-center gap-x-3 gap-y-2 px-3 py-3 text-sm shadow-none sm:px-4">
          <Label htmlFor={retentionId} className="font-normal text-muted-foreground">{t('http.exp.retention')}</Label>
          <Input id={retentionId} type="number" min="1" max="365" value={retention} className="h-10 w-20 lg:h-9 pointer-coarse:h-10"
            disabled={retentionLocked} onChange={(e) => setRetention(e.target.value)} />
          <span className="text-muted-foreground">{t('http.exp.days')}</span>
          {!retentionLocked && (
            <Button type="button" size="sm" className="h-10 lg:h-9 pointer-coarse:h-10" onClick={saveRetention} disabled={savingRet} aria-busy={savingRet || undefined}>
              <Save aria-hidden="true" />{t('http.exp.save')}
            </Button>
          )}
          <p className="m-0 basis-full text-xs text-muted-foreground">{retentionLocked ? t('hreq.ret.locked') : t('hreq.ret.hint')}</p>
        </Card>
      )}

      <HttpEndpointDetail t={t} phone={phone} endpoint={selected} row={selectedRow} params={view?.params} refreshToken={view?.rev}
        fmt={fmt} onClose={() => setSelected(null)}
        onFocus={(ep) => { setFilters((f) => ({ ...f, endpoint: ep })); setSelected(null) }} />
    </div>
  )
}
