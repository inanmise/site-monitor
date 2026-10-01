// İzleme Panosu — küçük ortak parçalar (2026-10-01 yeniden tasarım): durum rozeti, durum dağılım çubuğu + lejant,
// başarı oranı ölçeri, yanıt süresi, tür etiketi, satır eylemleri ve "canlı" tazelik göstergesi. Hepsi shadcn
// (Badge / Button / Progress ailesi); renkler jeton + `dark:` karşılığı; dokunma hedefleri `pointer-coarse:` ile 40 px.
import { useEffect, useState } from 'react'
import { BellRing, ExternalLink, Gauge as GaugeIcon, Timer } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { formatPercent } from '../../i18n/dateLocale.js'
import { ProgressBar } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { STATUS_META, TYPE_META } from './overviewMeta.js'
import { rowUptime, uptimeTone, isSlow, formatMs, formatAge, fullTime } from './overviewModel.js'

export function StatusBadge({ status, className }) {
  const t = useT()
  const meta = STATUS_META[status] ?? STATUS_META.unknown
  return (
    <Badge variant="outline" data-slot="mo-status" data-status={status}
      className={cn('gap-1 rounded-full font-semibold whitespace-nowrap', meta.badge, className)}>
      <meta.Icon aria-hidden="true" className="size-3" />{t(meta.labelKey)}
    </Badge>
  )
}

/** Tür ikonu + menü etiketi (satır içi). */
export function TypeLabel({ type, className, iconClassName }) {
  const t = useT()
  const meta = TYPE_META[type]
  return (
    <span className={cn('inline-flex min-w-0 items-center gap-1', className)}>
      {meta && <meta.Icon aria-hidden="true" className={cn('size-3.5 shrink-0 text-muted-foreground', iconClassName)} />}
      <span className="truncate">{meta ? t(meta.labelKey) : type}</span>
    </span>
  )
}

/** Yüzde metni (tek ondalık, yerel ayırıcı ve yüzde sırası: TR "%99,5", EN "99.5%"). */
export function pctText(v, locale) {
  if (v == null) return null
  return formatPercent(v >= 99.95 && v < 100 ? (99.9).toLocaleString(locale) : v.toLocaleString(locale, { maximumFractionDigits: 1 }))
}

/**
 * Durum dağılımı — tek çubukta sağlıklı / sorunlu / gecikmiş / bilinmiyor / duraklatılmış oranı (Datadog "monitor
 * status summary"). Parça genişliği `flex-grow` = adet (yüzde hesabı yok); sıfır olan parça çizilmez. Lejant öğeleri
 * `onPick` verilirse düğmedir (o duruma süzer), değilse düz metin. Çubuk `role="img"` + tam cümle ad.
 */
export function StatusDistribution({ items, onPick, active = [], label, size = 'md', className, legendClassName }) {
  const t = useT()
  const locale = useDateLocale()
  const total = items.reduce((s, x) => s + x.count, 0)
  const summary = items.map((x) => `${t(STATUS_META[x.status].labelKey)} ${x.count}`).join(', ')
  return (
    <div className={cn('flex min-w-0 flex-col gap-2', className)} data-slot="mo-distribution">
      <div role="img" aria-label={label ? `${label}: ${summary}` : summary}
        className={cn('flex w-full gap-0.5 overflow-hidden rounded-full bg-muted', size === 'sm' ? 'h-1.5' : 'h-2.5')}>
        {total > 0 && items.filter((x) => x.count > 0).map((x) => (
          <span key={x.status} data-status={x.status} className={cn('h-full min-w-1', STATUS_META[x.status].bar)}
            style={{ flexGrow: x.count, flexBasis: 0 }} />
        ))}
      </div>
      {legendClassName !== false && (
        <div className={cn('flex flex-wrap items-center gap-x-1 gap-y-1', legendClassName)} data-slot="mo-legend">
          {items.map((x) => {
            const meta = STATUS_META[x.status]
            const body = (
              <>
                <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', meta.bar)} />
                <span className="text-muted-foreground">{t(meta.labelKey)}</span>
                <span className="font-semibold text-foreground tabular-nums">{x.count.toLocaleString(locale)}</span>
              </>
            )
            return onPick ? (
              <Button key={x.status} type="button" variant="ghost" size="xs" data-slot="mo-legend-item" data-status={x.status}
                aria-pressed={active.length === 1 && active[0] === x.status} disabled={x.count === 0}
                aria-label={t('mo.legend.filter', t(meta.labelKey), x.count)}
                onClick={() => onPick(x.status)}
                className="h-7 gap-1.5 px-2 font-normal aria-pressed:bg-accent pointer-coarse:h-10">
                {body}
              </Button>
            ) : (
              <span key={x.status} className="inline-flex items-center gap-1.5 px-1 text-xs">{body}</span>
            )
          })}
        </div>
      )}
    </div>
  )
}

/** Pencere başarı oranı ölçeri: yüzde + koşum/başarısız + ince çubuk (ton eşikleri tür kartıyla aynı). */
export function UptimeMeter({ row, className, compact = false }) {
  const t = useT()
  const locale = useDateLocale()
  const pct = rowUptime(row)
  const tone = uptimeTone(pct)
  const checks = Number(row.checks_window || 0), failed = Number(row.failed_window || 0)
  return (
    <div data-slot="mo-uptime" data-tone={tone || 'none'} className={cn('flex min-w-0 flex-col gap-1', className)}
      title={t('mo.uptime.tip', checks.toLocaleString(locale), failed.toLocaleString(locale))}>
      <div className="flex items-baseline justify-between gap-2 text-xs">
        <span className={cn('font-semibold tabular-nums', tone === 'crit' ? 'text-destructive' : tone === 'warn' ? 'text-amber-700 dark:text-amber-300' : 'text-foreground')}>
          {pct == null ? t('mo.card.noRuns') : pctText(pct, locale)}
        </span>
        {!compact && checks > 0 && (
          <span className="text-[11px] text-muted-foreground tabular-nums">
            {t('mo.row.checks', checks.toLocaleString(locale), failed.toLocaleString(locale))}
          </span>
        )}
      </div>
      {pct != null && <ProgressBar value={pct} size="sm" tone={tone} decorative className="h-1" />}
    </div>
  )
}

/** Son yanıt süresi + pencere ortalaması + "yavaş" işareti. Değer yoksa tire. */
export function ResponseValue({ row, className, inline = false }) {
  const t = useT()
  const locale = useDateLocale()
  const last = formatMs(row.response_ms, t, locale)
  const avg = formatMs(row.avg_response_ms_window, t, locale)
  const slow = isSlow(row)
  if (!last && !avg) return <span className={cn('text-xs text-muted-foreground', className)}>—</span>
  return (
    <span data-slot="mo-response" data-slow={slow || undefined}
      className={cn('inline-flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5 text-xs', !inline && 'sm:flex-col sm:items-start', className)}>
      <span className="inline-flex items-center gap-1 tabular-nums">
        {inline && <Timer aria-hidden="true" className="size-3 text-muted-foreground" />}
        <span className={cn('font-medium', slow && 'text-amber-700 dark:text-amber-300')}>{last ?? '—'}</span>
        {slow && (
          <Badge variant="warning" className="h-4 gap-0.5 px-1 text-[10px]" title={t('mo.resp.slowTip', avg)}>
            <GaugeIcon aria-hidden="true" />{t('mo.resp.slow')}
          </Badge>
        )}
      </span>
      {avg && <span className="text-[11px] text-muted-foreground tabular-nums">{t('mo.resp.avg', avg)}</span>}
    </span>
  )
}

/** Son kontrol: göreli yaş ("5 dk önce") + tam damga `title`'da; gecikmişte amber. */
export function LastCheck({ row, nowMs, className, icon = false }) {
  const t = useT()
  const locale = useDateLocale()
  const age = formatAge(row.last_checked_at, nowMs, t)
  return (
    <span className={cn('inline-flex items-center gap-1 whitespace-nowrap', row.status === 'stale' && 'text-amber-700 dark:text-amber-300', className)}
      title={fullTime(row.last_checked_at, locale) || undefined}>
      {icon && <span aria-hidden="true" className="sr-only" />}
      {age ?? <span className="text-muted-foreground">{t('mo.card.never')}</span>}
    </span>
  )
}

/**
 * Satır eylemleri: izleme sayfasında aç (arama ile) · açık alarm varsa Alarm Geçmişi. Erişilebilir ad satırı taşır
 * (`mo.row.open` / `mo.row.alerts`, rowAccessibleNames kapısı). `labels`: dar kartta metinli düğmeler.
 */
export function RowActions({ row, labels = false, className }) {
  const t = useT()
  const meta = TYPE_META[row.type]
  return (
    <div className={cn('flex shrink-0 items-center gap-1.5', className)}>
      {Number(row.open_alerts) > 0 && (
        <Button type="button" variant="outline" size={labels ? 'sm' : 'icon-sm'} className={cn('pointer-coarse:h-10', labels ? 'flex-1 sm:flex-none' : 'pointer-coarse:w-10')}
          aria-label={t('mo.row.alerts', row.name)} title={t('mo.row.alerts', row.name)}
          onClick={() => navigateTo('alerthistory', { view: 'open', src: row.type, q: row.target })}>
          <BellRing aria-hidden="true" />{labels && t('mo.row.alertsShort')}
        </Button>
      )}
      <Button type="button" variant="outline" size={labels ? 'sm' : 'icon-sm'} className={cn('pointer-coarse:h-10', labels ? 'flex-1 sm:flex-none' : 'pointer-coarse:w-10')}
        aria-label={t('mo.row.open', row.name)} title={t('mo.row.open', row.name)}
        onClick={() => navigateTo(meta?.tab || 'http', { q: row.target })}>
        <ExternalLink aria-hidden="true" />{labels && t('mo.row.openShort')}
      </Button>
    </div>
  )
}

/**
 * "Canlı" tazelik göstergesi — sunucunun veriyi ÜRETTİĞİ an (`generated_at`; sunucu belleği 30 sn'ye kadar paylaşır)
 * üzerinden "12 sn önce". 5 sn'de bir kendini tazeler (yalnız bu küçük parça yeniden çizilir). Son yenileme başarısızsa
 * amber nokta + "yenilenemedi". Yoklama aralığı `title`'da.
 */
export function LiveIndicator({ generatedAt, fetchedAt, failed = false, loading = false }) {
  const t = useT()
  const locale = useDateLocale()
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 5000)
    return () => clearInterval(id)
  }, [])
  const base = generatedAt || (fetchedAt ? fetchedAt.toISOString().slice(0, 19) : null)
  const age = formatAge(base, Math.max(now, fetchedAt ? fetchedAt.getTime() : 0), t)
  return (
    <Badge variant="outline" data-slot="mo-live" data-state={failed ? 'error' : loading ? 'loading' : 'live'}
      className="h-6 gap-1.5 rounded-full px-2 font-normal text-muted-foreground" title={t('mo.live.tip', fullTime(base, locale))}>
      <span aria-hidden="true" className="relative flex size-2">
        {!failed && <span className="absolute inline-flex size-full animate-ping rounded-full bg-success opacity-60 motion-reduce:animate-none" />}
        <span className={cn('relative inline-flex size-2 rounded-full', failed ? 'bg-amber-500' : 'bg-success')} />
      </span>
      <span>{failed ? t('mo.live.failed', age ?? '—') : t('mo.live.updated', age ?? '—')}</span>
    </Badge>
  )
}
