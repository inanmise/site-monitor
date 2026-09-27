import { useId, useRef } from 'react'
import { Plus } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { TIERS } from './thresholdModel.js'

/**
 * Tier KAPSAMI özeti — hangi tier kendi eşiğini kullanıyor, hangisi varsayılana düşüyor (+ alan sayıları),
 * yanında "Tier eşiği ekle" menüsü: YALNIZ kendi satırı olmayan tier'lar listelenir (sunucu tier başına tek
 * satıra izin verir). Seçim, varsayılandan ön-dolu düzenleme penceresini açar.
 *
 * @param overrides  Set<tier> — kendi satırı olan tier'lar
 * @param counts     { [tier]: alan sayısı } (önizlemeden; bilinmeyen tier yok sayılır)
 * @param unclassified  tier'sız alan sayısı ya da null
 */
export default function TierCoverage({ overrides, counts, unclassified, canEdit, phone, onAdd }) {
  const t = useT()
  const pickedRef = useRef(false)
  const titleId = useId()
  const free = TIERS.filter(n => !overrides.has(n))
  const countText = (n) => (counts[n] != null ? ` (${counts[n]})` : '')

  return (
    <div data-slot="threshold-coverage"
      className="flex flex-col gap-3 rounded-lg border bg-muted/30 p-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 flex-col gap-1.5">
        <span id={titleId} className="text-xs font-semibold text-muted-foreground">{t('thr.coverageTitle')}</span>
        <ul aria-labelledby={titleId} className="flex list-none flex-wrap gap-1.5">
          {TIERS.map(n => {
            const own = overrides.has(n)
            return (
              <li key={n}>
                <ToneBadge tone={own ? 'info' : 'muted'} data-slot="threshold-coverage-tier" data-tier={n} data-override={own ? 'true' : 'false'}
                  className="font-medium tabular-nums">
                  {t('thr.tierShort', n)} · {own ? t('thr.covOwn') : t('thr.covDefault')}{countText(n)}
                </ToneBadge>
              </li>
            )
          })}
          {unclassified != null && (
            <li>
              <ToneBadge tone="muted" data-slot="threshold-coverage-tier" data-tier="none" className="font-medium tabular-nums">
                {t('thr.covUnclassified')} · {t('thr.covDefault')} ({unclassified})
              </ToneBadge>
            </li>
          )}
        </ul>
      </div>

      {canEdit && (free.length > 0 ? (
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size={phone ? 'lg' : 'default'} data-slot="threshold-add"
              className={phone ? 'w-full' : 'shrink-0'}>
              <Plus aria-hidden="true" /> {t('thr.addTier')}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" collisionPadding={8}
            className="z-(--z-menu) w-[min(22rem,calc(100vw-2rem))]"
            onCloseAutoFocus={(e) => {
              // Seçim pencere açtıysa odak pencerede kalsın (KebabMenu kalıbı)
              const picked = pickedRef.current
              pickedRef.current = false
              if (picked) e.preventDefault()
            }}>
            <DropdownMenuLabel className="text-xs font-semibold text-muted-foreground">{t('thr.pickTitle')}</DropdownMenuLabel>
            {free.map(n => (
              <DropdownMenuItem key={n} data-tier={n} className="flex-col items-start gap-0.5 py-2"
                onSelect={() => { pickedRef.current = true; onAdd(n) }}>
                <span className="font-medium">{t(`inv.tier${n}`)}</span>
                <span className="text-xs text-muted-foreground">
                  {counts[n] != null ? t('thr.pickHint', counts[n]) : t('thr.pickHintNoCount')}
                </span>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <span className="text-xs text-muted-foreground">{t('thr.allTiersCovered')}</span>
      ))}
    </div>
  )
}
