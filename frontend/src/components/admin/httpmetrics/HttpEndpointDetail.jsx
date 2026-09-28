import { useCallback, useEffect, useRef, useState } from 'react'
import { Filter, SearchX } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { api } from '../../../api/client'
import CopyButton from '../../ui/CopyButton.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import { shortDateTime } from '../../responsechart/responseChartModel.js'
import { cn } from '@/lib/utils'
import { EndpointLabel, OVER_MODAL_Z, Tile } from './HttpParts.jsx'
import HttpTrafficChart from './HttpTrafficChart.jsx'
import HttpStatusCodes from './HttpStatusCodes.jsx'
import { errTone, fmtInt, fmtPct, msTone, normCodes, normOverview, normSummary } from './httpMetricsModel.js'

/**
 * Uç ayrıntısı — İstek Gezgini'nin ÜSTÜNDE açılan Sheet (geniş ekranda sağdan, telefonda alttan). O ucun özet
 * kutucukları (tablo satırından), kendi zaman serisi (durum sınıfı + süre, `…/http-metrics/series?endpoint=`) ve
 * durum kodu dağılımı. Gezgin yenilenince (elle / otomatik) ayrıntı da AYNI aralıkla yeniden yüklenir; seçim korunur.
 * Yükleniyor / hata (+ tekrar dene) / uç aralıkta yok durumları ui ailesiyle.
 */
export default function HttpEndpointDetail({ t, phone, endpoint, row, params, refreshToken, fmt, onClose, onFocus }) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const seq = useRef(0)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  const load = useCallback(async () => {
    if (!endpoint || !params?.from) return
    const my = ++seq.current
    setLoading(true)
    try {
      const res = await api.admin.getHttpMetricsSeries(params.from, params.to, endpoint)
      if (my !== seq.current || !alive.current) return
      if (res?.success) {
        const d = res.data || {}
        const m = normOverview({ ...d, endpoints: [] })
        setData({ ...m, summary: normSummary(d.summary), codes: normCodes(d.status_codes) })
        setError(false)
      } else setError(true)
    } catch {
      if (my === seq.current && alive.current) setError(true)
    } finally {
      if (my === seq.current && alive.current) setLoading(false)
    }
  }, [endpoint, params?.from, params?.to])

  // Uç değişince önceki ucun verisi GÖSTERİLMEZ (başka ucun grafiği bir an bile bu başlıkla görünmesin).
  useEffect(() => { setData(null); setError(false) }, [endpoint])
  useEffect(() => { load() }, [load, refreshToken])

  const open = !!endpoint
  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side={phone ? 'bottom' : 'right'} data-slot="hreq-detail" data-endpoint={endpoint || undefined}
        overlayClassName={OVER_MODAL_Z}
        className={cn(OVER_MODAL_Z, 'gap-0 p-0', phone ? 'max-h-[92dvh] rounded-t-xl' : 'w-full sm:max-w-xl')}>
        <SheetHeader className="gap-3 border-b p-4 pr-14">
          <SheetTitle className="text-sm font-semibold text-muted-foreground">{t('hreq.d.title')}</SheetTitle>
          <SheetDescription className="sr-only">{t('hreq.d.desc')}</SheetDescription>
          {endpoint && (
            <div className="flex items-start gap-2">
              <EndpointLabel endpoint={endpoint} method={row?.method} path={row?.path} className="min-w-0 flex-1 [&_span.font-mono]:text-sm" />
              <CopyButton value={endpoint} label={t('hreq.d.copy')} copiedLabel={t('hreq.d.copied')} buttonSize="icon-sm"
                className="shrink-0 max-lg:size-10 pointer-coarse:size-10" />
            </div>
          )}
          {endpoint && (
            <Button type="button" variant="outline" size="sm" className="h-9 self-start max-lg:h-10 pointer-coarse:h-10" onClick={() => onFocus(endpoint)}>
              <Filter aria-hidden="true" />{t('hreq.d.focus')}
            </Button>
          )}
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          {!row ? (
            <StatusBlock icon={SearchX} title={t('hreq.d.gone')} className="py-8" />
          ) : (
            <div role="group" aria-label={t('hreq.kpi.label')} className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              <Tile t={t} id="d-count" label={t('hreq.col.requests')} value={fmtInt(row.count)} sub={t('hreq.kpi.errorsSub', fmtInt(row.errors))} />
              <Tile t={t} id="d-err" label={t('hreq.kpi.errorRate')} value={fmtPct(row.errorRate)} tone={errTone(row.errorRate, row.count)} />
              <Tile t={t} id="d-avg" label={t('hreq.kpi.avg')} value={fmt.value(row.avg)} tone={msTone(row.avg)}
                sub={row.p50 != null ? t('hreq.kpi.p50Sub', fmt.value(row.p50)) : null} />
              <Tile t={t} id="d-p95" label={t('hreq.kpi.p95')} value={fmt.value(row.p95)} tone={msTone(row.p95)}
                sub={row.p99 != null ? t('hreq.kpi.p99Sub', fmt.value(row.p99)) : null} />
              <Tile t={t} id="d-max" label={t('hreq.kpi.max')} value={fmt.value(row.max)} />
              <Tile t={t} id="d-seen" label={t('hreq.col.lastSeen')} value={row.lastSeen ? shortDateTime(row.lastSeen) : '—'} valueClassName="text-base" />
            </div>
          )}
          {error && !loading ? (
            <StatusBlock tone="danger" role="alert" title={t('hreq.d.loadError')} className="py-6"
              actions={<Button type="button" variant="secondary" size="sm" className="max-lg:h-10 pointer-coarse:h-10" onClick={load}>{t('db.retry')}</Button>} />
          ) : !data ? (
            <LoadingBlock label={t('modal.loading')} />
          ) : (
            <>
              {/* Kırpma / eksen tavanı GÖRÜNÜR (gezginle aynı bantlar; 2026-09-28c ek-2) */}
              {data.clamped && <AlertBanner tone="info" className="mb-0">{t('hreq.chart.clamped')}</AlertBanner>}
              {data.capped && <AlertBanner tone="warning" className="mb-0">{t('hreq.chart.capped')}</AlertBanner>}
              <HttpTrafficChart t={t} model={data} fmt={fmt} busy={loading} summaryText={t('hreq.d.desc')} />
              <HttpStatusCodes t={t} summary={data.summary} codes={data.codes} />
            </>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
