import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Tek seçimli düğme grubu — `ui/SegmentedControl` ile aynı erişilebilirlik sözleşmesi (role="group" + aria-pressed
 * düğmeler; etkin öğeye yeniden basmak seçimi BOŞALTMAZ), ama shadcn ToggleGroup'un `outline` görünümü ve
 * DOKUNMATİKTE 40 px hedef (`pointer-coarse:h-10`). SQL Playground araç çubukları için (tuval üstü denetimler).
 *
 * options: [{ value, label, icon?, ariaLabel?, hideLabel? }] — `hideLabel`: yalnız ikon (ad `ariaLabel`/`label`'dan).
 */
export default function ChoiceToggle({ value, onChange, options, ariaLabel, className }) {
  return (
    <ToggleGroup type="single" variant="outline" size="sm" role="group" aria-label={ariaLabel}
      value={value} onValueChange={(v) => { if (v && v !== value) onChange(v) }}
      className={cn('bg-background', className)}>
      {options.map((o) => {
        const Icon = o.icon
        return (
          <ToggleGroupItem key={o.value} value={o.value} role="button" aria-pressed={o.value === value} aria-checked={undefined}
            aria-label={o.hideLabel ? (o.ariaLabel || o.label) : o.ariaLabel}
            className="gap-1.5 px-2.5 text-xs font-medium text-muted-foreground data-[state=on]:bg-accent data-[state=on]:text-foreground pointer-coarse:h-10">
            {Icon && <Icon aria-hidden="true" className="size-3.5" />}
            {!o.hideLabel && o.label}
          </ToggleGroupItem>
        )
      })}
    </ToggleGroup>
  )
}
