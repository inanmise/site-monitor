import { useEffect, useId, useMemo, useState } from 'react'
import { CalendarClock, Repeat, Wrench } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import Field, { FieldError } from '../ui/Field.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import MaintenanceTargetPicker from '../monitoring/MaintenanceTargetPicker.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { Textarea } from '@/components/shadcn/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { DEFAULT_TZ, computeStatus, nextOccurrence, parseIso, toIso } from './maintenanceSchedule.js'
import { StatusBadge, useScheduleText } from './maintenanceUi.jsx'

export const DOW = [1, 2, 3, 4, 5, 6, 7]   // Pzt..Paz (ISO)
export const DURATION_PRESETS = [30, 60, 120, 240]
const FREQUENCIES = ['DAILY', 'WEEKLY', 'MONTHLY']
const TZ_LIST = (() => {
  try { return Intl.supportedValuesOf('timeZone') } catch { return [DEFAULT_TZ, 'UTC', 'Europe/London', 'America/New_York'] }
})()

export const EMPTY_FORM = {
  name: '', description: '', allMonitors: false, targets: [], timezone: DEFAULT_TZ,
  mode: 'once', startAt: '', endAt: '', durationMinutes: 60, recurrence: 'WEEKLY', daysOfWeek: [], dayOfMonth: 1,
}

/** Sunucu penceresi → form durumu (düzenleme). */
export function formFromWindow(w) {
  const start = parseIso(w.start_at)
  const dur = Number(w.duration_minutes) >= 1 ? Number(w.duration_minutes) : 60
  const rec = w.recurrence && w.recurrence !== 'NONE' ? w.recurrence : null
  return {
    name: w.name || '', description: w.description || '', allMonitors: !!w.all_monitors,
    targets: (w.targets || []).map((x) => (x.type && x.type !== '?' ? `${x.type}:${x.target}` : x.target)).filter(Boolean),
    timezone: w.timezone || DEFAULT_TZ, mode: rec ? 'recurring' : 'once',
    startAt: w.start_at || '', endAt: start != null ? toIso(start + dur * 60_000) : '', durationMinutes: dur,
    recurrence: rec || 'WEEKLY',
    daysOfWeek: w.days_of_week ? String(w.days_of_week).split(',').map(Number).filter((n) => n >= 1 && n <= 7) : [],
    dayOfMonth: Number(w.day_of_month) >= 1 ? Number(w.day_of_month) : 1,
  }
}

/** Seçici kimliği (`tür:hedef`) → sunucunun beklediği {type, target, name}. Seçenek listede yoksa kimlik yine ayrılır. */
export function targetObjs(values, options) {
  return values.map((tg) => {
    const o = options.find((x) => x.value === tg)
    if (o) return { type: o.type, target: o.target, name: o.name }
    const i = String(tg).indexOf(':')
    return i > 0 ? { type: tg.slice(0, i), target: tg.slice(i + 1), name: tg.slice(i + 1) } : { type: '?', target: tg, name: tg }
  })
}

/** Formun süre (dk) değeri: tek seferlikte bitiş − başlangıç; tekrarlayanda alan. */
export function effectiveMinutes(f) {
  if (f.mode === 'once') {
    const s = parseIso(f.startAt), e = parseIso(f.endAt)
    return s != null && e != null ? Math.round((e - s) / 60_000) : null
  }
  return Number(f.durationMinutes)
}

/** Form → önizleme penceresi (özet cümlesi, sıradaki oluşum, durum). */
export function previewWindow(f) {
  const minutes = effectiveMinutes(f)
  return {
    start_at: f.startAt, timezone: f.timezone, duration_minutes: minutes != null && minutes >= 1 ? minutes : 60, active: true,
    recurrence: f.mode === 'recurring' ? f.recurrence : 'NONE',
    days_of_week: f.mode === 'recurring' && f.recurrence === 'WEEKLY' ? f.daysOfWeek.join(',') : null,
    day_of_month: f.mode === 'recurring' && f.recurrence === 'MONTHLY' ? Number(f.dayOfMonth) : null,
  }
}

/** Doğrulama — alan → i18n anahtarı. Boş nesne = geçerli. */
export function validateForm(f) {
  const e = {}
  if (!f.name.trim()) e.name = 'mw.nameRequired'
  if (!f.allMonitors && f.targets.length === 0) e.targets = 'mw.targetsRequired'
  if (!f.startAt) e.start = 'mw.startRequired'
  if (f.mode === 'once') {
    if (!f.endAt) e.end = 'mw.endRequired'
    else if (f.startAt && !(parseIso(f.endAt) > parseIso(f.startAt))) e.end = 'mw.endAfterStart'
  } else {
    if (!(Number(f.durationMinutes) >= 1)) e.duration = 'mw.durationRequired'
    if (f.recurrence === 'WEEKLY' && f.daysOfWeek.length === 0) e.weekdays = 'mw.weekdayRequired'
    if (f.recurrence === 'MONTHLY' && !(Number(f.dayOfMonth) >= 1 && Number(f.dayOfMonth) <= 31)) e.dayOfMonth = 'mw.dayOfMonthRequired'
  }
  return e
}

/** Form → API gövdesi (sunucu sözleşmesi: camelCase istek). */
export function buildPayload(f, options) {
  const minutes = effectiveMinutes(f)
  const rec = f.mode === 'recurring' ? f.recurrence : 'NONE'
  return {
    name: f.name.trim(), description: f.description?.trim() || null, allMonitors: f.allMonitors,
    targets: f.allMonitors ? [] : targetObjs(f.targets, options),
    timezone: f.timezone, startAt: f.startAt, durationMinutes: Math.max(1, Number(minutes) || 1), recurrence: rec,
    daysOfWeek: rec === 'WEEKLY' ? f.daysOfWeek.join(',') : null,
    dayOfMonth: rec === 'MONTHLY' ? Number(f.dayOfMonth) : null,
  }
}

/** "Tüm monitörler" — shadcn Checkbox + bağlı etiket. */
export function AllMonitorsCheckbox({ checked, onChange, label }) {
  const id = useId()
  return (
    <div className="flex items-center gap-2">
      <Checkbox id={id} checked={!!checked} onCheckedChange={(v) => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer font-normal">{label}</Label>
    </div>
  )
}

/** Süre ön ayarları (30/60/120/240 dk) + özel dakika — shadcn ToggleGroup (tekli) + Input. */
export function DurationPicker({ minutes, onChange, error, label }) {
  const t = useT()
  const { minutes: fmt } = useScheduleText()
  const preset = DURATION_PRESETS.includes(Number(minutes)) ? String(minutes) : ''
  return (
    <Field label={label} error={error}>
      {({ id, describedBy, invalid }) => (
        <div className="flex flex-wrap items-center gap-2">
          <ToggleGroup type="single" variant="outline" spacing={1} value={preset} aria-label={label}
            onValueChange={(v) => { if (v) onChange(Number(v)) }} className="flex-wrap">
            {DURATION_PRESETS.map((m) => (
              <ToggleGroupItem key={m} value={String(m)} data-slot="mw-duration-preset"
                className="min-w-14 px-2.5 data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                {fmt(m)}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <Input id={id} type="number" min="1" inputMode="numeric" aria-invalid={invalid} aria-describedby={describedBy}
            aria-label={t('mw.customMinutes')} placeholder={t('mw.customMinutes')} value={minutes ?? ''}
            onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))} className="w-36" />
        </div>
      )}
    </Field>
  )
}

/**
 * Bakım penceresi düzenleyicisi — ui/ModalShell (shadcn Dialog). Emek biriktiren form: kenara tıklamak KAPATMAZ,
 * Escape ve X kapatır; gövde kayar, altlık sabit. Doğrulama alan altında (Field `error`), sunucu hatası AlertBanner.
 *
 * Akış: tek seferlik (başlangıç + bitiş, süre ön ayarları bitişi kurar) ya da tekrarlayan (sıklık, haftanın günleri /
 * ayın günü, ilk oluşum + süre). Canlı özet: düz sözcüklerle zamanlama + sıradaki oluşum + durum önizlemesi.
 * `onSave(payload)` API sonucunu döner; başarısızsa hata burada gösterilir, pencere açık kalır.
 */
export default function MaintenanceEditor({ open, mode, initial, monitorOptions, saving, onSave, onClose }) {
  const t = useT()
  const { sentence, dateTimeOf } = useScheduleText()
  const [form, setForm] = useState(EMPTY_FORM)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  const onceId = useId(), recurId = useId()

  useEffect(() => {
    if (!open) return
    setForm(initial || EMPTY_FORM)
    setErrors({})
    setServerError(null)
  }, [open, initial])

  const set = (patch) => setForm((f) => ({ ...f, ...(typeof patch === 'function' ? patch(f) : patch) }))
  const setStart = (iso) => set((f) => {
    // Tek seferlikte bitiş, mevcut süreyi koruyarak başlangıçla birlikte kayar (bitiş elle seçilmediyse de tutarlı kalır).
    const s = parseIso(iso), prevS = parseIso(f.startAt), prevE = parseIso(f.endAt)
    const keep = prevS != null && prevE != null && prevE > prevS ? prevE - prevS : Number(f.durationMinutes) * 60_000
    return { startAt: iso, endAt: s != null && f.mode === 'once' ? toIso(s + Math.max(60_000, keep)) : f.endAt }
  })
  const setMode = (m) => set((f) => {
    if (m === f.mode) return {}
    if (m === 'once') {
      const s = parseIso(f.startAt)
      return { mode: m, endAt: s != null ? toIso(s + Math.max(1, Number(f.durationMinutes) || 60) * 60_000) : f.endAt }
    }
    const mins = effectiveMinutes(f)
    return { mode: m, durationMinutes: mins != null && mins >= 1 ? mins : f.durationMinutes }
  })
  const setMinutes = (m) => set((f) => {
    if (f.mode === 'once') {
      const s = parseIso(f.startAt)
      return { durationMinutes: m, endAt: s != null && Number(m) >= 1 ? toIso(s + Number(m) * 60_000) : f.endAt }
    }
    return { durationMinutes: m }
  })

  const preview = useMemo(() => previewWindow(form), [form])
  const now = Date.now()
  const next = useMemo(() => (form.startAt ? nextOccurrence(preview, now) : null), [preview, now, form.startAt])
  const status = form.startAt ? computeStatus(preview, now) : null
  const summary = form.startAt ? sentence(preview) : null

  async function submit() {
    const e = validateForm(form)
    setErrors(e)
    if (Object.keys(e).length) { setServerError(null); return }
    setServerError(null)
    const res = await onSave(buildPayload(form, monitorOptions))
    if (res && !res.success) setServerError(res.error || t('mw.saveError'))
  }

  const err = (k) => (errors[k] ? t(errors[k]) : undefined)
  const FORM_GRID = 'grid grid-cols-1 gap-x-4 sm:grid-cols-2'
  const CHOICE = 'flex min-w-0 cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2.5 text-left font-normal has-[[data-state=checked]]:border-primary has-[[data-state=checked]]:bg-primary/5'

  return (
    <ModalShell open={open} onClose={onClose} busy={saving} dismissOnBackdrop={false} scrollBody
      title={mode === 'new' ? t('mw.modalNew') : t('mw.modalEdit')} icon={Wrench} size="md"
      footer={<>
        <Button variant="secondary" onClick={onClose}>{t('mw.cancel')}</Button>
        <Button onClick={submit} disabled={saving} aria-busy={saving || undefined}>{saving ? '…' : t('mw.save')}</Button>
      </>}>
      {serverError && <AlertBanner tone="danger" role="alert" title={t('mw.saveError')}>{serverError}</AlertBanner>}
      {Object.keys(errors).length > 0 && !serverError && <AlertBanner tone="warning" role="alert">{t('mw.fixErrors')}</AlertBanner>}
      <div className={FORM_GRID}>
        <Field label={t('mw.name')} required error={err('name')} className="sm:col-span-2">
          {({ id, describedBy, invalid }) => <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.name} onChange={(e) => set({ name: e.target.value })} />}
        </Field>
        <Field label={t('mw.description')} className="sm:col-span-2">
          {({ id }) => <Textarea id={id} rows={2} value={form.description} onChange={(e) => set({ description: e.target.value })} />}
        </Field>

        {/* Monitör seçimi */}
        <fieldset data-slot="mw-targets" className="mb-3.5 flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3.5 py-3 sm:col-span-2"
          aria-invalid={errors.targets ? true : undefined}>
          <legend className="px-1 text-[0.9em] font-bold">{t('mw.monitorsTitle')}</legend>
          <AllMonitorsCheckbox checked={form.allMonitors} label={t('mw.allMonitorsOpt')} onChange={(v) => set({ allMonitors: v })} />
          {!form.allMonitors && (
            // Önce tür, sonra o türün monitörleri (2026-09-17): düz liste hangi türü durdurduğunu göstermiyordu.
            <MaintenanceTargetPicker options={monitorOptions} value={form.targets}
              onChange={(v) => set({ targets: v })} typeLabel={(ty) => t('mw.type.' + ty)} />
          )}
          {errors.targets && <FieldError>{t(errors.targets)}</FieldError>}
        </fieldset>

        {/* Zamanlama */}
        <fieldset data-slot="mw-schedule" className="flex min-w-0 flex-col gap-3 rounded-lg border px-3.5 py-3 sm:col-span-2">
          <legend className="px-1 text-[0.9em] font-bold">{t('mw.scheduleTitle')}</legend>
          <RadioGroup value={form.mode} onValueChange={setMode} aria-label={t('mw.scheduleTitle')} className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <Label htmlFor={onceId} className={CHOICE}>
              <RadioGroupItem id={onceId} value="once" className="mt-0.5" />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="flex items-center gap-1.5 font-semibold"><CalendarClock size={14} aria-hidden="true" />{t('mw.mode.once')}</span>
                <span className="text-xs leading-snug text-muted-foreground">{t('mw.mode.onceHint')}</span>
              </span>
            </Label>
            <Label htmlFor={recurId} className={CHOICE}>
              <RadioGroupItem id={recurId} value="recurring" className="mt-0.5" />
              <span className="flex min-w-0 flex-col gap-0.5">
                <span className="flex items-center gap-1.5 font-semibold"><Repeat size={14} aria-hidden="true" />{t('mw.mode.recurring')}</span>
                <span className="text-xs leading-snug text-muted-foreground">{t('mw.mode.recurringHint')}</span>
              </span>
            </Label>
          </RadioGroup>

          {form.mode === 'recurring' && (
            <Field label={t('mw.frequency')}>
              {() => (
                <SegmentedControl value={form.recurrence} onChange={(v) => set({ recurrence: v })} ariaLabel={t('mw.frequency')}
                  options={FREQUENCIES.map((r) => ({ value: r, label: t('mw.rec.' + r) }))} />
              )}
            </Field>
          )}
          {form.mode === 'recurring' && form.recurrence === 'WEEKLY' && (
            <Field label={t('mw.weekdays')} required error={err('weekdays')}>
              {({ describedBy }) => (
                // Haftanın günleri — çoklu seçim (shadcn ToggleGroup; her gün aria-pressed)
                <ToggleGroup type="multiple" variant="outline" spacing={1} aria-label={t('mw.weekdays')} aria-describedby={describedBy}
                  className="w-full flex-wrap" value={form.daysOfWeek.map(String)}
                  onValueChange={(v) => set({ daysOfWeek: v.map(Number).sort((a, b) => a - b) })}>
                  {DOW.map((d) => (
                    <ToggleGroupItem key={d} value={String(d)} data-slot="mw-dow"
                      className="min-w-11 data-[state=on]:border-primary data-[state=on]:bg-primary data-[state=on]:text-primary-foreground">
                      {t('mw.dow.' + d)}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              )}
            </Field>
          )}
          {form.mode === 'recurring' && form.recurrence === 'MONTHLY' && (
            <Field label={t('mw.dayOfMonth')} required error={err('dayOfMonth')} className="sm:max-w-xs">
              {({ id, describedBy, invalid }) => <Input id={id} type="number" min="1" max="31" inputMode="numeric" aria-describedby={describedBy} aria-invalid={invalid}
                value={form.dayOfMonth} onChange={(e) => set({ dayOfMonth: Number(e.target.value) })} />}
            </Field>
          )}

          <div className={FORM_GRID}>
            <Field label={form.mode === 'once' ? t('mw.start') : t('mw.firstOccurrence')} required error={err('start')}>
              {() => <DateTimeField value={form.startAt} onChange={setStart} placeholder={t('mw.start')} />}
            </Field>
            {form.mode === 'once' ? (
              <Field label={t('mw.end')} required error={err('end')}>
                {() => <DateTimeField value={form.endAt} onChange={(v) => set({ endAt: v })} placeholder={t('mw.end')} min={form.startAt || undefined} />}
              </Field>
            ) : (
              <Field label={t('mw.timezone')}>
                {({ id }) => <SearchableSelect id={id} value={form.timezone} onChange={(v) => set({ timezone: v })}
                  options={TZ_LIST.map((z) => ({ value: z, label: z }))} searchThreshold={2} />}
              </Field>
            )}
            <div className="sm:col-span-2">
              <DurationPicker label={t('mw.durationLabel')} minutes={form.mode === 'once' ? (effectiveMinutes(form) ?? '') : form.durationMinutes}
                onChange={setMinutes} error={err('duration')} />
            </div>
            {form.mode === 'once' && (
              <Field label={t('mw.timezone')} className="sm:col-span-2">
                {({ id }) => <SearchableSelect id={id} value={form.timezone} onChange={(v) => set({ timezone: v })}
                  options={TZ_LIST.map((z) => ({ value: z, label: z }))} searchThreshold={2} />}
              </Field>
            )}
          </div>

          {/* Canlı özet */}
          <div data-slot="mw-summary" className="flex min-w-0 flex-col gap-1 rounded-md bg-muted/50 px-3 py-2 text-[0.86em] leading-relaxed">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-bold">{t('mw.summaryLabel')}</span>
              {status && <StatusBadge status={status} />}
            </div>
            <div className={cn(!summary && 'text-muted-foreground')}>{summary || t('mw.summaryEmpty')}</div>
            {summary && (
              <div className="text-muted-foreground">
                {next ? `${t('mw.nextOccurrence')}: ${dateTimeOf(next.start, form.timezone)}` : (status !== 'active' ? t('mw.sched.noFuture') : '')}
                {' · '}{t('mw.timezoneNote', form.timezone)}
              </div>
            )}
          </div>
        </fieldset>
      </div>
    </ModalShell>
  )
}
