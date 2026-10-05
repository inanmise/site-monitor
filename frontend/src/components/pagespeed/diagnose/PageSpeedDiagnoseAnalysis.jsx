import { Gauge, Waypoints, Route, Weight, Hourglass, Shapes, Info } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import { Metric } from '../../http/diagnose/HttpDiagnosePath.jsx'
import { Kv, KvList, SectionTitle } from '../../http/diagnose/HttpDiagnoseParts.jsx'
import { formatBytes, pathTitle, routeText } from '../../http/diagnose/httpDiagnoseModel.js'
import { cn } from '@/lib/utils'
import {
  METRIC_LABEL, STATUS_KEY, formatValue, metricRows, phaseRows, resourceRows, statusTone,
} from './pageSpeedDiagnoseModel.js'

/**
 * Sayfa Hızı uçtan uca tanılamasının "NEDEN YAVAŞ?" bölümü (2026-10-05) — hükmün hemen altında:
 * <ul>
 *   <li>eşik ↔ ölçülen: her metrik için ölçülen değer, eşik işareti ve fark (aşan kırmızı) — çubuk süsleyici, bilgi metinde;</li>
 *   <li>yol başına faz süreleri (ana belge: DNS, TCP, vekil, TLS, ilk bayt, indirme) yol gösterici sınırlarla;</li>
 *   <li>izlemenin ölçümünün künyesi (toplam, HTML, sunucu süresi, ağırlık, istek, inmeyen, tembel atlanan);</li>
 *   <li>en ağır ve en yavaş 5 kaynak + tür kırılımı.</li>
 * </ul>
 * Çubuklar Tailwind (konum + genişlik birlikte gerektiği için ui/ProgressBar karşılamaz, `HttpDiagnoseParts.TimingWaterfall`
 * deseni); telefonda satırlar daralır, hiçbir şey yatay taşmaz.
 */
export default function PageSpeedDiagnoseAnalysis({ data }) {
  const t = useT()
  const ps = data?.pagespeed
  if (!ps) return null
  const m = ps.measured || null
  const metrics = metricRows(ps)
  const routes = Array.isArray(ps.routes) ? ps.routes : []
  return (
    <section data-slot="psdx-analysis" data-status={m?.status || ''} className="flex min-w-0 flex-col gap-4 rounded-xl border bg-card px-4 py-3.5">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <SectionTitle icon={Gauge}>{t('psdx.analysis.title')}</SectionTitle>
        {m?.status && (
          <ToneBadge tone={statusTone(m.status)} data-slot="psdx-recorded" data-status={m.status} className="font-semibold">
            {t('psdx.analysis.recorded')}: {t(STATUS_KEY[m.status] || 'pspd.statusUnknown')}
          </ToneBadge>
        )}
      </div>

      {ps.analyzed === false ? (
        <p data-slot="psdx-unanalyzed" className="text-sm text-muted-foreground">{t('psdx.analysis.unanalyzed')}</p>
      ) : (
        <>
          <div className="flex min-w-0 flex-col gap-2">
            <SectionTitle icon={Gauge} className="text-[13px]">{t('psdx.analysis.thresholds')}</SectionTitle>
            <ol data-slot="psdx-metrics" className="flex min-w-0 flex-col gap-2.5" aria-label={t('psdx.analysis.thresholds')}>
              {metrics.map((r) => <MetricRow key={r.key} row={r} />)}
            </ol>
          </div>

          <div className="flex min-w-0 flex-col gap-2">
            <SectionTitle icon={Hourglass} className="text-[13px]">{t('psdx.analysis.phases')}</SectionTitle>
            <div className={cn('grid min-w-0 grid-cols-1 gap-3', routes.length > 1 && 'md:grid-cols-2')}>
              {routes.map((r) => <RoutePhases key={r.key} route={r} limits={ps.phase_limits} />)}
            </div>
            <p className="text-xs text-muted-foreground">{t('psdx.analysis.phasesHint')}</p>
          </div>

          {m && (
            <KvList>
              <Kv label={t('psdx.kv.total')}><span className="tabular-nums">{formatValue(m.total_ms, 'ms')}</span></Kv>
              <Kv label={t('psdx.kv.html')}><span className="tabular-nums">{formatValue(m.html_ms, 'ms')}</span></Kv>
              <Kv label={t('psdx.kv.server')}>
                <span className="tabular-nums">{formatValue(m.server_ms ?? m.ttfb_ms, 'ms')}</span>
              </Kv>
              <Kv label={t('psdx.kv.weight')}>
                <span className="tabular-nums">{formatBytes(m.total_bytes)}{m.bytes_truncated ? ` · ${t('psdx.kv.lowerBound')}` : ''}</span>
              </Kv>
              <Kv label={t('psdx.kv.requests')}>
                <span className="tabular-nums">{m.request_count ?? '—'}{m.failed_count ? ` · ${t('psdx.kv.failed', m.failed_count)}` : ''}</span>
              </Kv>
              {m.skipped_lazy > 0 && <Kv label={t('psdx.kv.lazy')}>{t('psdx.kv.lazyValue', m.skipped_lazy)}</Kv>}
            </KvList>
          )}

          <div className="grid min-w-0 grid-cols-1 gap-3 md:grid-cols-2">
            <ResourceList slot="psdx-heaviest" icon={Weight} title={t('psdx.analysis.heaviest')} rows={resourceRows(ps.heaviest)} sizeFirst />
            <ResourceList slot="psdx-slowest" icon={Hourglass} title={t('psdx.analysis.slowest')} rows={resourceRows(ps.slowest)} />
          </div>

          {Array.isArray(ps.by_type) && ps.by_type.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5">
              <SectionTitle icon={Shapes} className="text-[13px]">{t('psdx.analysis.byType')}</SectionTitle>
              <div data-slot="psdx-by-type" className="flex min-w-0 flex-wrap gap-1.5">
                {ps.by_type.map((x) => (
                  <ToneBadge key={x.type} tone="muted" data-type={x.type} className="font-mono">
                    {x.type} · {x.count} · {formatBytes(x.bytes)}
                  </ToneBadge>
                ))}
              </div>
            </div>
          )}
          <p className="flex items-start gap-1.5 text-xs text-muted-foreground">
            <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            <span className="min-w-0">{t('psdx.analysis.note')}</span>
          </p>
        </>
      )}
    </section>
  )
}

/** Eşik ↔ ölçülen satırı: ad + ölçülen / eşik metni + durum rozeti; çubukta ölçülen dolgu ve eşik çizgisi. */
function MetricRow({ row }) {
  const t = useT()
  const limitText = row.limit != null ? formatValue(row.limit, row.unit) : t('psdx.metric.unset')
  return (
    <li data-slot="psdx-metric" data-key={row.key} data-breached={row.breached ? 'true' : 'false'} className="flex min-w-0 flex-col gap-1">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1 text-xs">
        <span className="font-semibold">{t(METRIC_LABEL[row.key])}</span>
        <span className="flex min-w-0 flex-wrap items-center gap-1.5">
          <span className={cn('tabular-nums', row.breached && 'font-semibold text-destructive')}>
            {t('psdx.metric.measured', formatValue(row.value, row.unit))}
          </span>
          <span className="text-muted-foreground tabular-nums">· {t('psdx.metric.limit', limitText)}</span>
          {row.limit != null && (
            <ToneBadge tone={row.breached ? 'danger' : 'success'} data-slot="psdx-metric-state">
              {row.breached
                ? `${t('psdx.metric.breached')}${row.over != null ? ` +${formatValue(row.over, row.unit)}` : ''}`
                : t('psdx.metric.ok')}
            </ToneBadge>
          )}
        </span>
      </div>
      <div aria-hidden="true" className="relative min-w-0">
        <ProgressBar value={row.pct} decorative size="md" tone={row.breached ? 'crit' : undefined} className="rounded-full" />
        {row.limitPct != null && (
          <span className="absolute inset-y-0 w-0.5 bg-foreground/70" style={{ left: `calc(${row.limitPct}% - 1px)` }} />
        )}
      </div>
    </li>
  )
}

/** Bir yolun faz süreleri — her faz için ad, çubuk (sınırın 1,5 katı tam), süre ve sınır. */
function RoutePhases({ route, limits }) {
  const t = useT()
  const rows = phaseRows(route, limits)
  return (
    <div data-slot="psdx-route" data-path={route.key} data-route={route.route} className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5">
      <div className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs">
        {route.route === 'proxy' ? <Waypoints aria-hidden="true" className="size-3.5 shrink-0" /> : <Route aria-hidden="true" className="size-3.5 shrink-0" />}
        <span className="font-semibold">{pathTitle(route.key, t)}</span>
        <span className="text-muted-foreground">· {routeText(route.route, t)}</span>
        {route.http_status != null && <ToneBadge tone={route.http_status >= 400 ? 'danger' : 'muted'}>HTTP {route.http_status}</ToneBadge>}
      </div>
      {!rows.length ? <p className="text-xs text-muted-foreground">{t('httpdx.timing.none')}</p> : (
        <ol className="flex min-w-0 flex-col gap-1.5" aria-label={t('psdx.analysis.phases')}>
          {rows.map((p) => (
            <li key={p.key} data-slot="psdx-phase" data-phase={p.key} data-over={p.over ? 'true' : 'false'}
              className="grid min-w-0 grid-cols-[minmax(0,7rem)_minmax(0,1fr)_auto] items-center gap-2 text-xs sm:grid-cols-[minmax(0,9rem)_minmax(0,1fr)_auto]">
              <span className={cn('truncate', p.over && 'font-semibold text-destructive')} title={t(`httpdx.timing.${p.key}`)}>
                {t(`httpdx.timing.${p.key}`)}
              </span>
              <div aria-hidden="true" className="min-w-0">
                <ProgressBar value={Math.max(p.pct, p.ms > 0 ? 1 : 0)} decorative size="md" tone={p.over ? 'crit' : undefined} className="rounded-full" />
              </div>
              <span className={cn('text-right tabular-nums', p.over ? 'font-semibold text-destructive' : 'text-muted-foreground')}>
                {p.ms} ms{p.limit != null && <span className="sr-only"> · {t('psdx.metric.limit', `${p.limit} ms`)}</span>}
              </span>
            </li>
          ))}
        </ol>
      )}
      <div className="flex min-w-0 flex-wrap gap-x-6 gap-y-2">
        <Metric label={t('psdx.route.raw')} value={formatValue(route.total_ms, 'ms')} slot="raw" />
        {route.measured_total_ms != null && <Metric label={t('psdx.route.measured')} value={formatValue(route.measured_total_ms, 'ms')} slot="measured" />}
        {route.body_bytes != null && <Metric label={t('psdx.route.html')} value={formatBytes(route.body_bytes)} slot="html" />}
      </div>
    </div>
  )
}

/** En ağır / en yavaş kaynaklar — kart satırları (tablo yok → telefonda yatay kayma yok). */
function ResourceList({ slot, icon, title, rows, sizeFirst = false }) {
  const t = useT()
  return (
    <div data-slot={slot} className="flex min-w-0 flex-col gap-1.5">
      <SectionTitle icon={icon} className="text-[13px]">{title}</SectionTitle>
      {!rows.length ? <p className="text-xs text-muted-foreground">{t('psdx.analysis.noResources')}</p> : (
        <ol className="flex min-w-0 flex-col divide-y overflow-hidden rounded-md border text-xs">
          {rows.map((r) => (
            <li key={r.i} data-slot="psdx-resource" className="flex min-w-0 flex-col gap-0.5 px-2.5 py-1.5">
              <span className="min-w-0 font-mono break-all">{r.url || '—'}</span>
              <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-muted-foreground tabular-nums">
                <span className="font-mono">{r.type}</span>
                <span className={cn(sizeFirst && 'font-semibold text-foreground')}>{formatBytes(r.bytes)}{r.truncated ? '+' : ''}</span>
                <span className={cn(!sizeFirst && 'font-semibold text-foreground')}>{r.ms != null ? `${r.ms} ms` : '—'}</span>
                <span>{t(r.thirdParty ? 'pspd.thirdParty' : 'pspd.firstParty')}</span>
                {r.failed && <ToneBadge tone="danger">{t('psdx.res.failed')}{r.status != null ? ` · HTTP ${r.status}` : ''}</ToneBadge>}
              </span>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
