import { AlertTriangle, CheckCircle2, Info, XCircle } from 'lucide-react'
import HintPopover from '../ui/HintPopover.jsx'
import { cn } from '@/lib/utils'
import { dateLocale } from '../../i18n/dateLocale.js'
import { formatAvailability, formatLoss, shortDateTime, tones } from './responseChartModel.js'

// Aciliyet yalnız jetonla + simgeyle (renk tek başına anlam taşımaz; ekran okuyucuya metin olarak da söylenir).
const TONE_INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', crit: 'text-destructive' }
const TONE_ICON = { ok: CheckCircle2, warn: AlertTriangle, crit: XCircle }

/**
 * Özet kutucuğu. Açıklama DOKUN-GÖR (`ui/HintPopover`): kutucuğun tamamı shadcn Button → fare, klavye ve
 * dokunmatikte açılır (Tooltip telefonda açılmaz — yalnız-hover bilgi YOK kuralı). Erişilebilir ad = etiket +
 * değer + (varsa) ton metni + alt satır. Sol renkli şerit YOK (kullanıcı kuralı 2026-09-26).
 */
function Kpi({ id, label, value, sub, hint, tone = 'neutral', t, className }) {
  const Icon = TONE_ICON[tone]
  return (
    <HintPopover content={hint} data-slot="chart-tile" data-kpi={id} data-tone={tone}
      triggerClassName={cn(
        'flex h-full w-full min-w-0 flex-col items-start justify-start gap-1 rounded-lg border bg-card px-3 py-2.5 text-left font-normal whitespace-normal shadow-xs',
        'transition-[border-color,box-shadow] hover:border-ring/60 hover:bg-card motion-reduce:transition-none dark:hover:bg-card',
        className,
      )}>
      <span className="flex w-full items-center gap-1 text-xs font-medium text-muted-foreground">
        <span className="min-w-0 truncate">{label}</span>
        <Info aria-hidden="true" className="ml-auto size-3 shrink-0 opacity-50" />
      </span>
      <span className="flex max-w-full min-w-0 items-center gap-1.5">
        <span data-slot="chart-tile-value" className={cn('truncate text-lg leading-tight font-bold text-foreground', TONE_INK[tone])}>{value}</span>
        {Icon && tone !== 'ok' && <Icon aria-hidden="true" className={cn('size-4 shrink-0', TONE_INK[tone])} />}
        {(tone === 'warn' || tone === 'crit') && <span className="sr-only">({t(`rtc.tone.${tone}`)})</span>}
      </span>
      {sub && <span className="w-full truncate text-xs text-muted-foreground tabular-nums">{sub}</span>}
    </HintPopover>
  )
}

/**
 * Pencerenin özet kutucukları: ortalama (tam pencerede + medyan), p95 (tam ya da "tepe"), en yüksek (+ en düşük),
 * erişilebilirlik (+ başarılı/toplam kontrol), başarısız kontrol (+ son başarısızlık) ve türe özgü yardımcı ölçü
 * (ping: ortalama paket kaybı, sertifika: son kontroldeki kalan gün). Süre ölçümü olmayan pencerede (eski
 * sertifika kayıtları) süre kutucukları hiç çizilmez — "—" dolu kutu kalabalık yapar, bilgi taşımaz.
 */
export default function ChartKpis({ t, stats, fmt, raw, aux, threshold }) {
  const tone = tones(stats, threshold)
  const locale = dateLocale()
  const items = []
  if (stats.avg != null) {
    items.push({ id: 'avg', label: t('chart.avg'), value: fmt.value(stats.avg), tone: tone.avg,
      sub: stats.median != null ? t('rtc.kpi.median', fmt.value(stats.median)) : null,
      hint: raw ? t('rtc.hint.avgRaw') : t('rtc.hint.avgBucket') })
    items.push({ id: 'p95', label: stats.p95Peak ? t('rtc.kpi.p95Peak') : t('chart.p95'), value: fmt.value(stats.p95), tone: tone.p95,
      hint: stats.p95Peak ? t('rtc.hint.p95Peak') : t('rtc.hint.p95Raw') })
    items.push({ id: 'max', label: t('rtc.kpi.max'), value: fmt.value(stats.max), tone: tone.max,
      sub: t('rtc.kpi.minSub', fmt.value(stats.min)), hint: t('rtc.hint.max') })
  }
  items.push({ id: 'availability', label: t('rtc.kpi.availability'), value: formatAvailability(stats.availability, stats.failed),
    tone: tone.availability, sub: t('rtc.kpi.checksSub', stats.passed.toLocaleString(locale), stats.samples.toLocaleString(locale)),
    hint: t('rtc.hint.availability') })
  items.push({ id: 'failed', label: t('rtc.kpi.failed'), value: stats.failed.toLocaleString(locale), tone: tone.failed,
    sub: stats.lastFailTs ? t('rtc.kpi.lastFail', shortDateTime(stats.lastFailTs)) : null, hint: t('rtc.hint.failed') })
  if (aux === 'loss' && stats.loss != null) {
    items.push({ id: 'loss', label: t('rtc.kpi.loss'), value: formatLoss(stats.loss), tone: tone.loss, hint: t('rtc.hint.loss') })
  }
  if (aux === 'days' && stats.days != null) {
    items.push({ id: 'days', label: t('rtc.kpi.days'), value: `${stats.days} ${t('chart.unitDays')}`, tone: tone.days,
      sub: t('rtc.kpi.daysSub'), hint: t('rtc.hint.days') })
  }
  const n = items.length
  return (
    <div role="group" aria-label={t('rtc.kpi.label')} data-slot="chart-tiles"
      className={cn('grid grid-cols-2 gap-2', n === 2 ? 'sm:grid-cols-2' : 'sm:grid-cols-3', n === 5 && 'lg:grid-cols-5', n >= 6 && 'lg:grid-cols-6')}>
      {items.map((it, i) => (
        <Kpi key={it.id} {...it} t={t}
          // Telefonda tek sayıdaki son kutucuk satırı doldurur (boş yarım hücre kalmasın).
          className={n % 2 === 1 && i === n - 1 ? 'max-sm:col-span-2' : undefined} />
      ))}
    </div>
  )
}
