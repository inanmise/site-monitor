import { useT } from '../../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { scaleModel } from './thresholdModel.js'

/**
 * Seviye renkleri — AlertHistory ile aynı önem jetonları (`--severity-*`). Yazı rengi zemine göre
 * seçildi (turuncu/amber zeminde beyaz yazı okunmuyordu). "Alarm yok" dilimi bilinçli olarak SAKİN
 * (yumuşak yeşil): göz alarm bölgesine gitsin.
 */
export const LEVEL_FILL = {
  critical: 'bg-(--severity-critical) text-white',
  high: 'bg-(--severity-high) text-zinc-950',
  warning: 'bg-(--severity-warn) text-zinc-950',
  ok: 'bg-success/20 text-green-800 dark:bg-success/25 dark:text-green-300',
}
/** Lejant/döşeme noktası — dolgu tonuyla aynı renk. */
export const LEVEL_DOT = {
  critical: 'bg-(--severity-critical)',
  high: 'bg-(--severity-high)',
  warning: 'bg-(--severity-warn)',
  ok: 'bg-success',
}

/** "7 gün" / "1 day" */
export function daysText(t, n) {
  return n === 1 ? t('thr.oneDay') : t('thr.nDays', n)
}

/** Dilimin okunur gün aralığı: "≤ 7 gün", "8–15 gün", "> 30 gün", boşsa "hiç devreye girmez". */
export function rangeText(t, seg) {
  if (seg.empty) return t('thr.range.unused')
  if (seg.level === 'critical') return seg.to <= 0 ? t('thr.range.expiry') : t('thr.range.upTo', daysText(t, seg.to))
  if (seg.level === 'ok') return t('thr.range.over', daysText(t, seg.from - 1))
  if (seg.from === seg.to) return daysText(t, seg.to)
  return t('thr.range.between', seg.from, seg.to)
}

/**
 * GÖRSEL ÖLÇEK — kalan gün ekseni (0 = sertifikanın bittiği gün) üzerinde dört renkli dilim:
 * Kritik · Yüksek · Uyarı · Alarm yok. Sınırlarda gün işaretleri; yakın işaretler ikinci satıra iner
 * (telefonda üst üste binmesin). Dar dilimde ad gizlenir (kap sorgusu), lejant her zaman tam bilgiyi taşır.
 *
 * <p>`linear` (düzenleme penceresi): dilimler gerçek orantıda — üstüne binen üç başparmaklı kaydırıcı
 * sınırlarla hizalı kalsın diye. Kartta dar dilimlere asgari genişlik verilir (1 günlük kritik dilim de görünür).
 *
 * Test kancaları: `data-slot="threshold-scale"`, dilimler `threshold-scale-segment` + `data-level/from/to`.
 */
export default function ThresholdScale({ values, axisMax, linear = false, className, barClassName, children }) {
  const t = useT()
  const { segments, ticks, axisMax: end } = scaleModel(values, axisMax, { linear })
  const aria = t('thr.scaleAria', segments.map(s => `${t(`thr.lv.${s.level}`)}: ${rangeText(t, s)}`).join('; '))
  const twoRows = ticks.some(tk => tk.row === 1)
  return (
    <div data-slot="threshold-scale" data-axis-max={end} className={cn('flex min-w-0 flex-col gap-1', className)}>
      <div className="relative">
        <div role="img" aria-label={aria} className={cn('flex h-7 w-full gap-0.5 overflow-hidden rounded-md', barClassName)}>
          {segments.filter(s => !s.empty && s.pct > 0).map(s => (
            <div key={s.level} data-slot="threshold-scale-segment" data-level={s.level}
              data-from={s.from} data-to={s.to ?? ''} style={{ flexGrow: s.pct }}
              className={cn('@container flex min-w-0 basis-0 items-center justify-center', LEVEL_FILL[s.level])}>
              <span aria-hidden="true" className="hidden truncate px-1.5 text-[11px] font-semibold @min-[4.5rem]:inline">
                {t(`thr.lv.${s.level}`)}
              </span>
            </div>
          ))}
        </div>
        {children}
      </div>
      <div aria-hidden="true" className={cn('relative text-[11px] leading-none text-muted-foreground tabular-nums', twoRows ? 'h-7' : 'h-4')}>
        {ticks.map(tk => (
          <span key={`${tk.value}-${tk.pos}`} data-slot="threshold-scale-tick"
            style={{ left: `${tk.pos}%` }}
            className={cn('absolute whitespace-nowrap',
              tk.row === 1 ? 'top-3.5' : 'top-0.5',
              tk.pos <= 1 ? 'translate-x-0' : tk.pos >= 97 ? '-translate-x-full' : '-translate-x-1/2')}>
            {tk.value}
          </span>
        ))}
        <span className="absolute top-0.5 right-0 whitespace-nowrap">{t('thr.axisUnit')}</span>
      </div>
    </div>
  )
}

/**
 * Seviye lejantı — her seviye için nokta + ad + gün aralığı rozeti (+ varsa "şu an N alan").
 * `counts` önizleme uç noktasının `current` sayımı; yoksa sayı satırı çizilmez.
 */
export function LevelLegend({ values, counts, className }) {
  const t = useT()
  const { segments } = scaleModel(values)
  return (
    <ul data-slot="threshold-legend" className={cn('grid list-none grid-cols-2 gap-x-3 gap-y-2.5 @lg:grid-cols-4', className)}>
      {segments.map(s => {
        const n = counts ? counts[s.level] : null
        return (
          <li key={s.level} data-slot="threshold-level" data-level={s.level} data-empty={s.empty ? 'true' : undefined}
            className={cn('flex min-w-0 items-start gap-2', s.empty && 'opacity-60')}>
            <span aria-hidden="true" className={cn('mt-1 size-2.5 shrink-0 rounded-full', LEVEL_DOT[s.level])} />
            <div className="flex min-w-0 flex-col gap-1">
              <div className="flex flex-wrap items-center gap-x-1.5 gap-y-1">
                <span className="text-sm font-medium">{t(`thr.lv.${s.level}`)}</span>
                <Badge variant="outline" data-slot="threshold-range" className="font-normal tabular-nums">{rangeText(t, s)}</Badge>
              </div>
              {n != null && (
                <span data-slot="threshold-count" className="text-xs text-muted-foreground tabular-nums">
                  {n === 1 ? t('thr.now1') : t('thr.nowN', n)}
                </span>
              )}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
