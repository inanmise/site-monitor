import { X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { BULK_MAX } from './bulkModel.js'

/**
 * Yönetim listelerinin toplu işlem şeridi (2026-11-13) — seçim varken görünür: "N seçili" + eylem düğmeleri + "Seçimi
 * temizle". Takımlar sekmesindeki `tm-bulk-bar` görünümüyle aynı (birincil çerçeve, sararak dizilir; dokunmatikte 40 px).
 * Eylemler çağırandan `children` olarak gelir (her biri önizleme penceresi açar — doğrudan yazmaz).
 */
export default function BulkSelectionBar({ count, onClear, busy = false, slot = 'bulk-bar', capped = false, children }) {
  const t = useT()
  if (!count) return null
  return (
    <div data-slot={slot} role="region" aria-label={t('adm.bulk.barLabel')} aria-busy={busy || undefined}
      className="flex flex-wrap items-center gap-2 rounded-lg border border-primary bg-primary/5 px-3 py-2">
      <span className="mr-1 font-bold" aria-live="polite">{t('adm.bulk.selected', count)}</span>
      {capped && (
        <span data-slot="bulk-cap-note" role="status" className="basis-full text-xs text-amber-800 dark:text-amber-300 [overflow-wrap:anywhere]">
          {t('adm.bulk.capNote', BULK_MAX)}
        </span>
      )}
      {children}
      <Button variant="ghost" size="sm" className="max-sm:h-10 pointer-coarse:h-10" onClick={onClear} disabled={busy}
        data-slot="bulk-clear">
        <X aria-hidden="true" /> {t('adm.bulk.clear')}
      </Button>
    </div>
  )
}

/** Şeritteki eylem düğmesi — tutarlı boy (dokunmatikte 40 px). */
export function BulkButton({ children, className = '', ...rest }) {
  return (
    <Button variant="secondary" size="sm" className={cn('max-sm:h-10 pointer-coarse:h-10', className)} {...rest}>{children}</Button>
  )
}
