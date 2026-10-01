// İzleme Panosu — tür kartı (2026-10-01 yeniden tasarım). Üstte tür (tıklanınca listeyi türe süzer) + "sayfayı aç";
// ortada pencere başarı oranı (büyük rakam + ton çubuğu) ve ağırlıklı ortalama yanıt; altta şu anki durum sayıları
// (sorunlu / gecikmiş / açık alarm) ve son kontrol. Kart sayıları tür sayfasıyla EŞLEŞİR: "aktif + duraklatılmış" =
// sayfadaki izleme sayısı; envanterden çıkarılmış (taramanın atladığı, sayfanın listelemediği) eski satırlar ayrı çipte.
// Kartta SOL ŞERİT YOK — ton ikon kutusunda ve `data-tone`'da; seçili kart tüm çerçeveyle vurgulanır.
import { ArrowUpRight, CheckCircle2, Clock, Trash2, Unplug } from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { TYPE_META, successPct } from './overviewMeta.js'
import { formatAge, formatMs, fullTime, uptimeTone } from './overviewModel.js'
import { pctText } from './OverviewParts.jsx'

function Stat({ label, value, tone }) {
  return (
    <div className="flex min-w-0 flex-col items-center rounded-md bg-muted/40 px-1 py-1.5">
      <span className={cn('text-lg leading-none font-bold tabular-nums',
        tone === 'bad' ? 'text-destructive' : tone === 'warn' ? 'text-amber-700 dark:text-amber-300' : 'text-foreground')}>{value}</span>
      <span className="mt-1 max-w-full truncate text-[10px] font-semibold tracking-wide text-muted-foreground uppercase">{label}</span>
    </div>
  )
}

/** Kartın tonu: sorunlu ya da kritik alarm → kötü; açık alarm ya da gecikmiş → uyarı; aksi iyi. */
export function typeTone(type) {
  return Number(type.down || 0) > 0 || Number(type.open_critical || 0) > 0 ? 'bad'
    : Number(type.open_alerts || 0) > 0 || Number(type.stale || 0) > 0 ? 'warn' : 'ok'
}

export default function OverviewTypeCard({ type, active, nowMs, windowLabel, onSelect, onOpen }) {
  const t = useT()
  const locale = useDateLocale()
  const meta = TYPE_META[type.type]
  if (!meta) return null
  const pct = successPct(type)
  const tone = typeTone(type)
  const down = Number(type.down || 0), open = Number(type.open_alerts || 0), stale = Number(type.stale || 0)
  const inv = Number(type.inventory_inactive || 0)
  const pausedShown = Math.max(0, Number(type.paused || 0) - inv)
  const avg = formatMs(type.avg_response_ms_window, t, locale)
  const age = formatAge(type.last_checked_at, nowMs, t)
  const pTone = uptimeTone(pct)
  return (
    <Card data-slot="mo-type-card" data-type={type.type} data-tone={tone} data-active={active || undefined}
      className={cn('min-w-0 gap-0 py-0 shadow-xs transition-colors motion-reduce:transition-none hover:border-foreground/20',
        active && 'border-primary ring-2 ring-primary/30')}>
      <CardContent className="flex h-full min-w-0 flex-col gap-3 p-3.5 sm:p-4">
        <div className="flex items-start gap-2">
          <Button type="button" variant="ghost" onClick={() => onSelect(type.type)} aria-pressed={active}
            aria-label={t('mo.card.filterBy', t(meta.labelKey))}
            className="h-auto min-w-0 flex-1 justify-start gap-2.5 px-1 py-1 text-left whitespace-normal">
            <span className={cn('grid size-9 shrink-0 place-items-center rounded-lg',
              tone === 'bad' ? 'bg-destructive/10 text-destructive' : tone === 'warn' ? 'bg-amber-500/10 text-amber-700 dark:text-amber-300' : 'bg-primary/10 text-primary')}>
              <meta.Icon aria-hidden="true" className="size-5" />
            </span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-semibold">{t(meta.labelKey)}</span>
              <span className="block text-xs font-normal text-muted-foreground">{t('mo.card.counts', type.active ?? 0, pausedShown)}</span>
            </span>
          </Button>
          <Button type="button" variant="outline" size="icon-sm" aria-label={t('mo.card.open', t(meta.labelKey))} title={t('mo.card.open', t(meta.labelKey))}
            onClick={() => onOpen(meta.tab)} className="shrink-0 pointer-coarse:size-10">
            <ArrowUpRight aria-hidden="true" />
          </Button>
        </div>

        <div className="flex items-end justify-between gap-3">
          <div className="min-w-0">
            <div data-slot="mo-type-success" className={cn('text-2xl leading-none font-bold tracking-tight tabular-nums',
              pTone === 'crit' ? 'text-destructive' : pTone === 'warn' ? 'text-amber-700 dark:text-amber-300' : 'text-foreground')}>
              {pct == null ? '—' : pctText(pct, locale)}
            </div>
            <div className="mt-1 truncate text-[11px] text-muted-foreground">
              {pct == null ? t('mo.card.noRuns') : t('mo.card.successIn', windowLabel)} · {t('mo.card.checks', Number(type.checks_window || 0).toLocaleString(locale))}
            </div>
          </div>
          {avg && (
            <div className="shrink-0 text-right">
              <div data-slot="mo-type-avg" className="text-sm leading-none font-semibold tabular-nums">{avg}</div>
              <div className="mt-1 text-[11px] text-muted-foreground">{t('mo.card.avgResp')}</div>
            </div>
          )}
        </div>
        {/* Değer hemen üstte METİN olarak görünüyor → çubuk süs (ekran okuyucu iki kez okumasın) */}
        <ProgressBar value={pct ?? 0} size="sm" tone={pct == null ? undefined : pTone} decorative
          className={cn('h-1.5', pct == null && 'opacity-40')} />

        <div className="grid grid-cols-3 gap-2 text-center">
          <Stat label={t('mo.card.down')} value={down} tone={down > 0 ? 'bad' : 'muted'} />
          <Stat label={t('mo.card.stale')} value={stale} tone={stale > 0 ? 'warn' : 'muted'} />
          <Stat label={t('mo.card.alerts')} value={open} tone={open > 0 ? 'bad' : 'muted'} />
        </div>

        <div className="mt-auto flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
          <span className="inline-flex items-center gap-1" title={fullTime(type.last_checked_at, locale) || undefined}>
            <Clock aria-hidden="true" className="size-3" />{age ?? t('mo.card.never')}
          </span>
          {Number(type.resolved_window || 0) > 0 && <span className="inline-flex items-center gap-1 text-success"><CheckCircle2 aria-hidden="true" className="size-3" />{t('mo.card.resolved', type.resolved_window)}</span>}
          {Number(type.deleted || 0) > 0 && <span className="inline-flex items-center gap-1"><Trash2 aria-hidden="true" className="size-3" />{t('mo.card.deleted', type.deleted)}</span>}
          {inv > 0 && (
            <Badge variant="outline" data-slot="mo-type-inv" className="h-5 gap-1 px-1.5 text-[10px] font-normal" title={t('mo.invInactiveTip')}>
              <Unplug aria-hidden="true" />{t('mo.card.invInactive', inv)}
            </Badge>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
