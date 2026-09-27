import { Siren, RefreshCw, SquareKanban, List } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

/**
 * Sayfa başlığı — ad + amaç, canlı özet satırı ("N açık · M onaylı · son 24 saatte K çözüldü"), Yenile ve
 * Pano | Liste görünüm anahtarı (ui/SegmentedControl = shadcn ToggleGroup). Telefonda alt alta, eylemler sağda sarar.
 */
export default function IncidentsHeader({ summary, view, onView, onRefresh, loading }) {
  const t = useT()
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0">
        <h2 className="flex items-center gap-1.5 text-lg font-bold"><Siren aria-hidden="true" className="size-5 shrink-0" />{t('incov.title')}</h2>
        <p className="mt-1 max-w-[78ch] text-xs text-muted-foreground">{t('incov.subtitle')}</p>
        {summary
          ? (
            <p data-slot="incidents-live" aria-live="polite" className="mt-1.5 text-xs font-medium text-foreground/80 tabular-nums">
              {t('incov.live', summary.open, summary.ack, summary.resolved24)}
            </p>
          )
          : <Skeleton className="mt-1.5 h-4 w-64" aria-hidden="true" />}
      </div>
      <div className="flex flex-wrap items-center gap-2 sm:justify-end">
        <SegmentedControl ariaLabel={t('incov.view')} value={view} onChange={onView}
          options={[
            { value: 'board', label: t('incov.viewBoard'), icon: SquareKanban },
            { value: 'list', label: t('incov.viewList'), icon: List },
          ]} />
        <Button variant="outline" size="sm" onClick={onRefresh} aria-busy={loading || undefined} className="h-8">
          <RefreshCw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />{t('incov.refresh')}
        </Button>
      </div>
    </div>
  )
}
