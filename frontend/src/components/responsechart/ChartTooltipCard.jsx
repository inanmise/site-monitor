import { CheckCircle2, XCircle } from 'lucide-react'
import { formatDate } from '../../api/client'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { bucketEndLabel, formatLoss } from './responseChartModel.js'
import { SeriesKey } from './SeriesKey.jsx'

/** İpucu satırı: solda seri anahtarı + ad (ikincil), sağda değer (vurgulu) — değer önde, ad arkada. */
function Row({ label, value, keyShape, color }) {
  return (
    <div data-slot="chart-tip-row" className="flex items-center gap-2">
      {keyShape ? <SeriesKey shape={keyShape} color={color} /> : <span aria-hidden="true" className="w-3.5 shrink-0" />}
      <span className="min-w-0 flex-1 truncate text-muted-foreground">{label}</span>
      <span className="font-semibold text-foreground tabular-nums">{value}</span>
    </div>
  )
}

/**
 * Grafik ipucu (recharts `content`). Kovanın tam zamanı (kurum saati — uygulamanın `formatDate`'i), değer(ler),
 * yardımcı seri (ping paket kaybı / sertifika kalan gün), kontrol sayısı ve durum. Seri ucu kova başına HATA
 * METNİ taşımaz (yalnız başarısız sayısı) — metin uydurulmaz, ayrıntının yeri (Kontrol Geçmişi) söylenir.
 *
 * Tek kontrollük kovada ortalama/p95/min–maks aynı sayıdır; dördünü yazmak "dört ayrı veri" izlenimi verir →
 * tek satır. Boşluk noktası (`gap`) ipucu üretmez.
 */
export function TooltipCard({ active, payload, t, fmt, bucketMs, title, aux, threshold }) {
  if (!active || !Array.isArray(payload) || payload.length === 0) return null
  const p = payload.find((x) => x?.payload)?.payload
  if (!p || p.gap) return null
  const single = p.count <= 1
  const ranged = !(single && bucketMs <= 60_000)
  const over = threshold != null && p.avg != null && p.avg > threshold
  return (
    <div data-slot="chart-tooltip" className="grid w-max max-w-[17rem] min-w-[11rem] gap-1.5 rounded-lg border border-border/60 bg-background px-3 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground tabular-nums">
        {formatDate(p.ts)}{ranged && <span className="font-normal text-muted-foreground"> – {bucketEndLabel(p.t, bucketMs)}</span>}
      </div>
      <div className="grid gap-1">
        {p.avg == null ? (
          <Row label={title} value={t('rtc.tip.noValue')} />
        ) : single ? (
          <Row label={title} value={fmt.value(p.avg)} keyShape="line" color="var(--color-avg)" />
        ) : (
          <>
            <Row label={t('chart.avg')} value={fmt.value(p.avg)} keyShape="line" color="var(--color-avg)" />
            {p.p95 != null && <Row label={t('chart.p95')} value={fmt.value(p.p95)} keyShape="dashed" color="var(--color-p95)" />}
            {p.min != null && p.max != null && (
              <Row label={t('chart.minmax')} value={`${fmt.value(p.min)} – ${fmt.value(p.max)}`} keyShape="area" color="var(--color-band)" />
            )}
          </>
        )}
        {aux === 'loss' && p.loss != null && (
          <Row label={t('chart.packetLoss')} value={formatLoss(p.loss)} keyShape="line" color="var(--color-aux)" />
        )}
        {aux === 'days' && p.days != null && (
          <Row label={t('modal.daysRemain')} value={`${p.days} ${t('chart.unitDays')}`} keyShape="line" color="var(--color-aux)" />
        )}
        <Row label={t('rtc.tip.checks')} value={p.count.toLocaleString()} />
      </div>
      <div className={cn('flex items-center gap-1.5 border-t pt-1.5 font-medium', p.down > 0 ? 'text-destructive' : 'text-success')}>
        {p.down > 0 ? <XCircle aria-hidden="true" className="size-3.5 shrink-0" /> : <CheckCircle2 aria-hidden="true" className="size-3.5 shrink-0" />}
        <span>{p.down > 0 ? t('rtc.tip.failed', p.down, p.count) : t('rtc.tip.allOk')}</span>
      </div>
      {p.down > 0 && <div className="text-[11px] leading-snug text-muted-foreground">{t('rtc.tip.historyHint')}</div>}
      {over && <Badge variant="warning" className="justify-self-start">{t('rtc.tip.aboveThreshold')}</Badge>}
    </div>
  )
}
