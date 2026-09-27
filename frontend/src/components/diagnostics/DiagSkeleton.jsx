import { useT } from '../../i18n/index.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Skeleton } from '@/components/shadcn/skeleton'
import { STEP_KEYS } from './diagModel.js'

/**
 * Koşu sürerken yer tutucu: adım şeridi + iki kart iskeleti, gerçek yerleşimle aynı boyutta (zıplama yok),
 * ekran okuyucuya `role="status"` ilerleme notu. shadcn Skeleton + Spinner.
 */
export default function DiagSkeleton() {
  const t = useT()
  return (
    <div data-slot="diag-skeleton" className="flex flex-col gap-4">
      <div role="status" className="flex items-start gap-2 rounded-lg border bg-muted/40 px-3 py-2.5 text-sm">
        <Spinner size={16} inline decorative className="mt-0.5" />
        <div className="min-w-0">
          <div className="font-medium">{t('diag.running')}</div>
          <div className="text-xs text-muted-foreground">{t('diag.runningHint')}</div>
        </div>
      </div>
      <div className="flex flex-col gap-2 md:flex-row md:items-center" aria-hidden="true">
        {STEP_KEYS.map((k) => <Skeleton key={k} className="h-11 w-full md:h-12" />)}
      </div>
      <div className="grid grid-cols-1 gap-3 lg:grid-cols-2" aria-hidden="true">
        <Skeleton className="h-36 w-full" />
        <Skeleton className="h-36 w-full" />
      </div>
    </div>
  )
}
