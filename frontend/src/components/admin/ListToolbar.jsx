import { Search, X } from 'lucide-react'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'

/**
 * Yönetim listelerinin araç çubuğu parçaları — shadcn karşılıkları. Eski `.invtb` ailesinin
 * (`.invtb-search` / `.invtb-filters` / `.invtb-f`) yerine; legacy sınıf taşımaz (App.css
 * katmansız olduğundan `.invtb-search input` gibi kurallar shadcn alanını ezerdi).
 */

/** İkonlu arama kutusu — shadcn InputGroup (büyüteç + doluysa temizle düğmesi). */
export function ToolbarSearch({ value, onChange, placeholder, ariaLabel, clearLabel, type = 'search', className = '' }) {
  return (
    <InputGroup className={cn('h-8 max-w-[420px] min-w-0 flex-[1_1_260px]', className)}>
      <InputGroupInput type={type} value={value} placeholder={placeholder} aria-label={ariaLabel}
        onChange={(e) => onChange(e.target.value)} />
      <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
      {value && (
        <InputGroupAddon align="inline-end">
          <InputGroupButton size="icon-xs" onClick={() => onChange('')} aria-label={clearLabel}>
            <X />
          </InputGroupButton>
        </InputGroupAddon>
      )}
    </InputGroup>
  )
}

/** Açılır süzgeç paneli — kenarlıklı ızgara; `aria-label`lı grup. */
export function FilterPanel({ label, className = '', children }) {
  return (
    <div role="group" aria-label={label}
      className={cn('mt-2.5 grid grid-cols-[repeat(auto-fit,minmax(190px,1fr))] gap-x-3.5 gap-y-2.5 rounded-lg border bg-card px-3.5 py-3 border-border', className)}>
      {children}
    </div>
  )
}

/** Süzgeç alanı — etiket + kontrol (shadcn Label sarmalayıcı; kontrol kendi `ariaLabel`ını da taşır). */
export function FilterField({ label, className = '', children }) {
  return (
    <Label className={cn('flex min-w-0 flex-col items-stretch gap-1 text-[0.86em] leading-normal font-normal text-muted-foreground', className)}>
      <span className="font-semibold">{label}</span>
      {children}
    </Label>
  )
}
