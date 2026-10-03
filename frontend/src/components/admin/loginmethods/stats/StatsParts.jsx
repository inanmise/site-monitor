import { Info, Minus, TrendingDown, TrendingUp } from 'lucide-react'
import { useT } from '../../../../i18n/index.jsx'
import HintPopover from '../../../ui/HintPopover.jsx'
import { cn } from '@/lib/utils'
import { fmtPct } from './loginStatsModel.js'

/**
 * Giriş istatistikleri ortak parçaları (2026-10-03): metrik TANIMI ipucu (dokun-gör — HintPopover; yalnız-hover bilgi
 * yok) ve önceki döneme göre DEĞİŞİM satırı (ok + metin; renk tonu iyi / kötü / nötr, renk tek taşıyıcı değil).
 */
export function InfoHint({ text, label }) {
  return (
    <HintPopover content={text}
      triggerClassName="rounded-full p-1 text-muted-foreground hover:text-foreground max-lg:size-10 pointer-coarse:size-10">
      <Info aria-hidden="true" className="size-3.5" />
      <span className="sr-only">{label}</span>
    </HintPopover>
  )
}

const TONE = { good: 'text-success', bad: 'text-destructive', neutral: 'text-muted-foreground' }

/** `d` = loginStatsModel.delta(...) sonucu; null → "önceki dönemde veri yok". */
export function DeltaLine({ d, locale, className }) {
  const t = useT()
  if (!d) {
    return <span data-slot="lm-delta" data-dir="none" className={cn('text-xs text-muted-foreground', className)}>{t('lm.stats.delta.none')}</span>
  }
  const Icon = d.dir === 'up' ? TrendingUp : d.dir === 'down' ? TrendingDown : Minus
  let text
  if (d.points != null) {
    text = t('lm.stats.pp', `${d.points > 0 ? '+' : ''}${d.points.toLocaleString(locale)}`)
  } else if (d.pct != null) {
    text = `${d.pct > 0 ? '+' : ''}${fmtPct(d.pct, locale, 0)}`
  } else {
    text = `${d.diff > 0 ? '+' : ''}${d.diff.toLocaleString(locale)}`
  }
  return (
    <span data-slot="lm-delta" data-dir={d.dir} data-tone={d.tone}
      className={cn('flex min-w-0 flex-wrap items-center gap-x-1 text-xs', TONE[d.tone] || TONE.neutral, className)}>
      <span className="inline-flex items-center gap-1 whitespace-nowrap">
        <Icon aria-hidden="true" className="size-3.5 shrink-0" />
        <span className="tabular-nums">{text}</span>
      </span>
      <span className="text-muted-foreground">{t('lm.stats.delta.vsPrev')}</span>
    </span>
  )
}
