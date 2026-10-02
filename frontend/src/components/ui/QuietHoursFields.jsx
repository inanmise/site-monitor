import { Moon, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import Field from './Field.jsx'
import SegmentedControl from './SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { QUIET_DAYS, EMPTY_QUIET, quietIsSet } from '../../utils/quietHours.js'
import { cn } from '@/lib/utils'

/**
 * Sessiz saat alanları (2026-10-01, onaylı öneri 15) — takım düzenleme penceresi ve kişisel push kartı AYNI bileşeni kullanır.
 * Başlangıç / bitiş (Europe/Istanbul, gece yarısını geçebilir), günler (pencerenin başladığı gün; hepsi = her gün) ve
 * pencerede hemen giden en düşük seviye. KRİTİK her zaman gider — ipucu bunu söyler.
 *
 * `value` = utils/quietHours form değeri; `onChange(next, changedKey)`. Hatalar alanın altında: `fieldProps(key)` →
 * `{ name, error }` (useFormErrors.fieldProps ya da eşdeğeri); `keys` takım (`quiet_start`…) / kişi (`start`…) eşlemesi.
 * Mobil: tek sütun → sm'de iki sütun; gün düğmeleri sarar, dokunmatikte 40 px.
 */
export default function QuietHoursFields({
  value = EMPTY_QUIET, onChange, fieldProps = () => ({}), keys = { start: 'quiet_start', end: 'quiet_end', days: 'quiet_days' },
  levelHintKey = 'quiet.levelHint', disabled = false, className = '',
}) {
  const t = useT()
  const set = (patch, key) => onChange?.({ ...value, ...patch }, key)
  const active = quietIsSet(value)
  return (
    <div data-slot="quiet-hours-fields" className={cn('flex min-w-0 flex-col gap-1', className)}>
      <div className="grid grid-cols-1 gap-x-3 sm:grid-cols-2">
        <Field label={t('quiet.start')} {...fieldProps(keys.start)}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="time" step={300} value={value.start} disabled={disabled}
              aria-describedby={describedBy} aria-invalid={invalid} className="pointer-coarse:h-10"
              onChange={(e) => set({ start: e.target.value }, keys.start)} />
          )}
        </Field>
        <Field label={t('quiet.end')} {...fieldProps(keys.end)}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="time" step={300} value={value.end} disabled={disabled}
              aria-describedby={describedBy} aria-invalid={invalid} className="pointer-coarse:h-10"
              onChange={(e) => set({ end: e.target.value }, keys.end)} />
          )}
        </Field>
      </div>
      <Field label={t('quiet.days')} hint={t('quiet.daysHint')} {...fieldProps(keys.days)}>
        {({ id, describedBy, invalid }) => (
          <ToggleGroup id={id} type="multiple" variant="outline" spacing={1} disabled={disabled}
            aria-label={t('quiet.days')} aria-describedby={describedBy} aria-invalid={invalid}
            value={value.days} onValueChange={(v) => set({ days: QUIET_DAYS.filter((d) => v.includes(d)) }, keys.days)}
            className="flex flex-wrap justify-start">
            {QUIET_DAYS.map((d) => (
              <ToggleGroupItem key={d} value={d} data-day={d} aria-label={t('quiet.dayLong.' + d)}
                className="min-w-11 pointer-coarse:h-10">
                {t('quiet.day.' + d)}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
      </Field>
      <div className="mb-3.5 flex min-w-0 flex-col gap-1.5">
        <span className="text-sm font-semibold">{t('quiet.minLevel')}</span>
        <SegmentedControl value={value.minLevel || 'HIGH'} ariaLabel={t('quiet.minLevel')}
          onChange={(v) => set({ minLevel: v }, 'minLevel')}
          className="flex-wrap"
          options={[
            { value: 'HIGH', label: t('quiet.level.HIGH'), disabled },
            { value: 'CRITICAL', label: t('quiet.level.CRITICAL'), disabled },
          ]} />
        <span className="text-xs text-muted-foreground">{t(levelHintKey)}</span>
      </div>
      {active && (
        <div>
          <Button type="button" variant="outline" size="sm" disabled={disabled} data-slot="quiet-clear"
            className="pointer-coarse:h-10" onClick={() => onChange?.({ ...EMPTY_QUIET, days: [...QUIET_DAYS] }, 'clear')}>
            <X aria-hidden="true" /> {t('quiet.clear')}
          </Button>
        </div>
      )}
    </div>
  )
}

/** Bölüm başlığı ikonu — çağıranlar aynı ikonu kullansın. */
export const QuietIcon = Moon
