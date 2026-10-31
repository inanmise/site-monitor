import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { CalendarClock, CalendarOff, CalendarX2, Clock, ExternalLink, Moon, Plus, Repeat, Timer, Trash2, X } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { Badge } from '@/components/shadcn/badge'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import Field, { FieldError } from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import {
  CRON_PRESETS, DAYS, INTERVAL_PRESETS, MAX_WINDOWS, UNIT_SECONDS, addSkipDate, describeCron, durationText, formatDay,
  formatRun, intervalProblem, newWindow, previewBody, splitInterval, windowKind, windowProblems, windowSummary,
} from './scheduleModel.js'

/** Sunucu zamanlama hata kodları (`DbQuerySchedule.CODE_*`). */
export const SCHEDULE_CODES = {
  INTERVAL: 'DBQUERY_INTERVAL_INVALID', CRON_INVALID: 'DBQUERY_CRON_INVALID', CRON_TOO_FREQUENT: 'DBQUERY_CRON_TOO_FREQUENT',
  WINDOW: 'DBQUERY_WINDOW_INVALID', SKIP_DATE: 'DBQUERY_SKIP_DATE_INVALID', MODE: 'DBQUERY_SCHEDULE_MODE_INVALID',
  NEVER_RUNS: 'DBQUERY_SCHEDULE_NEVER_RUNS',
}
/** Koda göre ek ipucu (ileti her zaman sunucudan; ipucu yalnız gerektiğinde). */
const CODE_HINT = { DBQUERY_CRON_TOO_FREQUENT: 'dbq.sched.hint.tooFrequent', DBQUERY_CRON_INVALID: 'dbq.sched.hint.cronInvalid' }

/** Önizleme isteği bekleme süresi (ms). */
export const SCHEDULE_PREVIEW_DEBOUNCE_MS = 500
const CONTROL = 'h-9 pointer-coarse:h-10'
const FULL_SELECT = '[&>[data-slot=native-select-wrapper]]:w-full'

/**
 * Zamanlama düzenleyicisi (Veritabanı Sorgu İzleme, 2026-10-31). Kip: Aralık (hazır değerler 1 dk … 1 gün + özel) ya da
 * Cron (5 / 6 alan, makrolar, hazır şablonlar, İNSAN DİLİNDE açıklama — istemci çözemezse "açıklanamadı", sunucu doğrular).
 * İzinli pencereler (boş = her an) ve yasak pencereler (gün çipleri + saat aralığı; bitiş ≤ başlangıç gece yarısını aşar),
 * "tatil takviminde çalışma", ek tarihler (≤ 100). Saat dilimi Europe/Istanbul. Canlı önizleme: değer değişince 500 ms
 * sonra `POST /dbquery/schedule-preview` → "Sonraki 10 çalışma" (İstanbul saati), etkin aralık, "hiç çalışmaz" uyarısı;
 * sunucu hataları alanın altında (çağıranın `fieldErrors`'ı önceliklidir). Geç yanıt sıra numarasıyla düşürülür.
 *
 * Sunucu iletisi (`errors[].message`, istek dilinde) HER ZAMAN gösterilir; kod (`DbQuerySchedule.CODE_*`) yalnız yeri ve
 * gerekirse ek ipucunu seçer: `DBQUERY_SCHEDULE_MODE_INVALID` kip seçicinin altında; `DBQUERY_SCHEDULE_NEVER_RUNS`
 * (CRON'da `cron_expression`, INTERVAL'de `schedule`) önizlemedeki "hiç çalışmaz" bandında TEK kez (alanın altında
 * tekrarlanmaz); `DBQUERY_CRON_TOO_FREQUENT` / `DBQUERY_CRON_INVALID` cron alanının altında + kısa ipucu.
 * `onChange(patch)` yalnız değişen alanları taşır.
 */
export default function ScheduleEditor({ value, onChange, fieldErrors, disabled = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const v = value || {}
  const mode = v.schedule_mode === 'CRON' ? 'CRON' : 'INTERVAL'
  const preview = useSchedulePreview(v)
  const codes = preview.codes || {}
  // Önizlemenin "hiç çalışmaz" ve "kip geçersiz" iletileri kendi yerlerinde çizilir — alan altında tekrarlanmaz
  const own = {}
  for (const [f, m] of Object.entries(preview.errors || {})) {
    if (codes[f] === SCHEDULE_CODES.NEVER_RUNS || codes[f] === SCHEDULE_CODES.MODE) continue
    own[f] = m
  }
  const errs = { ...own, ...(fieldErrors || {}) }
  const modeError = errs.schedule_mode
    || Object.entries(preview.errors || {}).find(([f]) => codes[f] === SCHEDULE_CODES.MODE)?.[1] || null
  const neverRunsMessage = Object.entries(preview.errors || {}).find(([f]) => codes[f] === SCHEDULE_CODES.NEVER_RUNS)?.[1] || null
  const cronHint = !fieldErrors?.cron_expression && CODE_HINT[codes.cron_expression] ? t(CODE_HINT[codes.cron_expression]) : null
  const set = (patch) => { if (!disabled) onChange?.(patch) }

  return (
    <div data-slot="dbq-schedule-editor" data-mode={mode} className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-col gap-1.5" data-field="schedule_mode">
        <span className="text-sm font-semibold">{t('dbq.sched.modeLabel')}</span>
        <SegmentedControl value={mode} onChange={(m) => set({ schedule_mode: m })} ariaLabel={t('dbq.sched.modeLabel')}
          className="w-full sm:w-auto" itemClassName="flex-1 sm:flex-none"
          options={[
            { value: 'INTERVAL', label: t('dbq.sched.mode.INTERVAL'), icon: Repeat, disabled },
            { value: 'CRON', label: t('dbq.sched.mode.CRON'), icon: CalendarClock, disabled },
          ]} />
        {modeError && <FieldError>{modeError}</FieldError>}
      </div>

      {mode === 'INTERVAL'
        ? <IntervalPicker seconds={v.interval_seconds} disabled={disabled} error={errs.interval_seconds} onChange={(s) => set({ interval_seconds: s })} />
        : <CronInput expr={v.cron_expression ?? ''} disabled={disabled} codeHint={cronHint}
          error={errs.cron_expression && errs.cron_expression !== neverRunsMessage ? errs.cron_expression : null} onChange={(c) => set({ cron_expression: c })} />}

      <WindowList kind="windows" list={v.windows} disabled={disabled} errs={errs} onChange={(w) => set({ windows: w })} />
      <WindowList kind="blackouts" list={v.blackouts} disabled={disabled} errs={errs} onChange={(w) => set({ blackouts: w })} />

      <HolidayToggle checked={!!v.skip_holidays} disabled={disabled} onChange={(b) => set({ skip_holidays: b })} />
      <SkipDates dates={v.skip_dates} disabled={disabled} error={errs.skip_dates} lang={lang} onChange={(d) => set({ skip_dates: d })} />

      <PreviewPanel preview={preview} error={errs.schedule && errs.schedule !== neverRunsMessage ? errs.schedule : null} neverRunsMessage={neverRunsMessage} lang={lang} t={t} mode={mode} />
    </div>
  )
}

/** Canlı önizleme durumu — idle | loading | ok | unknown. */
function useSchedulePreview(v) {
  const body = previewBody(v)
  const key = JSON.stringify(body)
  const seq = useRef(0)
  const [state, setState] = useState({ state: 'idle', reason: null, data: null, errors: null, codes: null, message: null })
  useEffect(() => {
    const n = ++seq.current
    if (body.schedule_mode === 'CRON' && !String(body.cron_expression || '').trim()) {
      setState({ state: 'idle', reason: 'cronEmpty', data: null, errors: null, codes: null, message: null })
      return undefined
    }
    if (body.schedule_mode === 'INTERVAL' && intervalProblem(body.interval_seconds)) {
      setState({ state: 'idle', reason: 'interval', data: null, errors: null, codes: null, message: null })
      return undefined
    }
    setState((s) => ({ ...s, state: 'loading' }))
    const ctrl = new AbortController()
    const timer = setTimeout(async () => {
      let next
      try {
        const res = await api.monitoring.dbQuerySchedulePreview(body, { signal: ctrl.signal })
        if (n !== seq.current || ctrl.signal.aborted) return
        if (res?.success && res.data && typeof res.data === 'object') {
          const errors = {}
          const codes = {}
          for (const e of Array.isArray(res.data.errors) ? res.data.errors : []) {
            if (e?.field && !errors[e.field]) { errors[e.field] = e.message || e.code; codes[e.field] = e.code || null }
          }
          next = { state: 'ok', reason: null, data: res.data, errors, codes, message: null }
        } else {
          const errors = {}
          const codes = {}
          if (res?.field) { errors[res.field] = res.error || res.message; codes[res.field] = res.code || null }
          next = { state: 'unknown', reason: null, data: null, errors, codes, message: res?.error || res?.message || null }
        }
      } catch (e) {
        if (n !== seq.current || ctrl.signal.aborted) return
        next = { state: 'unknown', reason: null, data: null, errors: null, codes: null, message: e?.message || null }
      }
      setState(next)
    }, SCHEDULE_PREVIEW_DEBOUNCE_MS)
    return () => { clearTimeout(timer); ctrl.abort() }
    // anahtar = gövdenin içeriği (nesne kimliği değil)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state
}

/** Aralık seçici — hazır değerler + "Özel" (sayı + birim). */
function IntervalPicker({ seconds, disabled, error, onChange }) {
  const t = useT()
  const preset = INTERVAL_PRESETS.includes(Number(seconds))
  const [custom, setCustom] = useState(!preset && seconds != null)
  const split = splitInterval(seconds)
  const [amount, setAmount] = useState(String(split.amount))
  const [unit, setUnit] = useState(split.unit)
  const problem = intervalProblem(seconds)
  const amountId = useId()

  // Dışarıdan değişen değer (şablon / geri yükleme) özel kutuyu eşitler
  useEffect(() => {
    if (seconds == null) return
    const n = Number(String(amount).replace(',', '.'))
    if (Number.isFinite(n) && Math.round(n * UNIT_SECONDS[unit]) === Number(seconds)) return   // kutudaki değer zaten bu
    const s = splitInterval(seconds)
    setAmount(String(s.amount))
    setUnit(s.unit)
    if (!INTERVAL_PRESETS.includes(Number(seconds))) setCustom(true)
    // yalnız dışarıdan gelen değer — kutunun kendi yazımı (amount / unit) bilinçli dışarıda
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seconds])

  const emitCustom = (a, u) => {
    const n = Number(String(a).replace(',', '.'))
    onChange(Number.isFinite(n) && n > 0 ? Math.round(n * UNIT_SECONDS[u]) : null)
  }
  const selected = custom ? 'custom' : (preset ? String(Number(seconds)) : '')

  return (
    <div data-field="interval_seconds" className="flex min-w-0 flex-col gap-2">
      <span className="inline-flex items-center gap-1.5 text-sm font-semibold"><Timer aria-hidden="true" className="size-4" />{t('dbq.sched.intervalLabel')}</span>
      <ToggleGroup type="single" variant="outline" spacing={1} value={selected} disabled={disabled}
        aria-label={t('dbq.sched.intervalLabel')} className="flex w-full flex-wrap"
        onValueChange={(val) => {
          if (!val) return
          if (val === 'custom') { setCustom(true); emitCustom(amount, unit); return }
          setCustom(false)
          onChange(Number(val))
        }}>
        {INTERVAL_PRESETS.map((s) => (
          <ToggleGroupItem key={s} value={String(s)} className="h-9 px-3 pointer-coarse:h-10">{durationText(s, t)}</ToggleGroupItem>
        ))}
        <ToggleGroupItem value="custom" className="h-9 px-3 pointer-coarse:h-10">{t('dbq.sched.custom')}</ToggleGroupItem>
      </ToggleGroup>
      {custom && (
        <div className="flex flex-wrap items-end gap-2">
          <div className="flex flex-col gap-1">
            <Label htmlFor={amountId} className="text-xs text-muted-foreground">{t('dbq.sched.customAmount')}</Label>
            <Input id={amountId} inputMode="decimal" value={amount} disabled={disabled} aria-invalid={problem || error ? true : undefined}
              className={cn(CONTROL, 'w-28 tabular-nums')} onChange={(e) => { setAmount(e.target.value); emitCustom(e.target.value, unit) }} />
          </div>
          <div className={FULL_SELECT}>
            <NativeSelect value={unit} disabled={disabled} aria-label={t('dbq.sched.customUnit')} className={cn(CONTROL, 'w-32')}
              onChange={(e) => { setUnit(e.target.value); emitCustom(amount, e.target.value) }}>
              {Object.keys(UNIT_SECONDS).map((u) => <NativeSelectOption key={u} value={u}>{t(`dbq.sched.unit.${u}`)}</NativeSelectOption>)}
            </NativeSelect>
          </div>
        </div>
      )}
      <p className="m-0 text-xs text-muted-foreground">{t('dbq.sched.intervalHint')}</p>
      {(error || problem) && <FieldError>{error || t(`dbq.sched.v.${problem}`)}</FieldError>}
    </div>
  )
}

/** Cron alanı + hazır şablonlar + insan dilinde açıklama. */
function CronInput({ expr, disabled, error, codeHint, onChange }) {
  const t = useT()
  const desc = useMemo(() => (expr.trim() ? describeCron(expr, t) : null), [expr, t])
  return (
    <div className="flex min-w-0 flex-col gap-2">
      <Field label={t('dbq.sched.cronLabel')} required name="cron_expression" error={error} hint={t('dbq.sched.cronHint')} className="mb-0">
        {({ id, describedBy, invalid }) => (
          <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} disabled={disabled} value={expr}
            spellCheck={false} autoComplete="off" autoCapitalize="off" placeholder="0 9 * * 1-5"
            className={cn(CONTROL, 'font-mono')} onChange={(e) => onChange(e.target.value)} />
        )}
      </Field>
      {error && codeHint && <p data-slot="dbq-cron-code-hint" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{codeHint}</p>}
      {expr.trim() && (
        <p data-slot="dbq-cron-desc" data-explained={desc ? 'true' : 'false'}
          className={cn('m-0 rounded-md border px-3 py-2 text-sm [overflow-wrap:anywhere]', desc ? 'bg-muted/40' : 'text-muted-foreground')}>
          <span className="font-semibold">{t('dbq.sched.cronMeans')} </span>{desc || t('dbq.sched.cronUnexplained')}
        </p>
      )}
      <div className="flex flex-col gap-1.5">
        <span className="text-xs font-semibold text-muted-foreground">{t('dbq.sched.cronPresets')}</span>
        <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
          {CRON_PRESETS.map((p) => (
            <li key={p.key}>
              <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onChange(p.expr)}
                aria-pressed={expr.trim() === p.expr} className="h-auto min-h-8 flex-col items-start gap-0 py-1 text-left whitespace-normal pointer-coarse:min-h-10">
                <span>{t(`dbq.sched.cronPreset.${p.key}`)}</span>
                <code className="font-mono text-[0.75rem] text-muted-foreground">{p.expr}</code>
              </Button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

/** İzinli / yasak pencere listesi. */
function WindowList({ kind, list, disabled, errs, onChange }) {
  const t = useT()
  const rows = Array.isArray(list) ? list : []
  const Icon = kind === 'blackouts' ? CalendarX2 : Clock
  const update = (i, w) => onChange(rows.map((r, j) => (j === i ? w : r)))
  const atMax = rows.length >= MAX_WINDOWS
  return (
    <section data-slot={`dbq-${kind}`} data-field={kind} className="flex min-w-0 flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-col gap-0.5">
        <span className="inline-flex items-center gap-1.5 text-sm font-semibold"><Icon aria-hidden="true" className="size-4" />{t(`dbq.sched.${kind}.title`)}</span>
        <span className="text-xs text-muted-foreground">{t(`dbq.sched.${kind}.hint`)}</span>
      </div>
      {rows.length === 0 && <p className="m-0 text-xs text-muted-foreground italic">{t(`dbq.sched.${kind}.empty`)}</p>}
      <ul className="m-0 flex list-none flex-col gap-2 p-0">
        {rows.map((w, i) => (
          <li key={i}>
            <WindowRow kind={kind} index={i} w={w} disabled={disabled} serverErr={errs[`${kind}[${i}]`] || errs[`${kind}[${i}].days`] || errs[`${kind}[${i}].from`] || errs[`${kind}[${i}].to`]}
              onChange={(nw) => update(i, nw)} onRemove={() => onChange(rows.filter((_, j) => j !== i))} />
          </li>
        ))}
      </ul>
      <div>
        <Button type="button" variant="outline" size="sm" disabled={disabled || atMax} className="pointer-coarse:h-10"
          onClick={() => onChange([...rows, newWindow()])}>
          <Plus aria-hidden="true" />{t(`dbq.sched.${kind}.add`)}
        </Button>
      </div>
      {errs[kind] && <FieldError>{errs[kind]}</FieldError>}
    </section>
  )
}

function WindowRow({ kind, index, w, disabled, serverErr, onChange, onRemove }) {
  const t = useT()
  const fromId = useId(), toId = useId()
  const p = windowProblems(w)
  const wk = windowKind(w)
  const label = t(`dbq.sched.${kind}.rowLabel`, index + 1)
  return (
    <div data-slot="dbq-window" data-kind={wk || 'invalid'} className="flex min-w-0 flex-col gap-2 rounded-md bg-muted/30 p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <ToggleGroup type="multiple" variant="outline" spacing={1} disabled={disabled} aria-label={t('dbq.sched.daysOf', label)}
          value={(w.days || []).map(String)} className="flex flex-wrap"
          onValueChange={(vals) => onChange({ ...w, days: vals.map(Number).sort((a, b) => a - b) })}>
          {DAYS.map((d) => (
            <ToggleGroupItem key={d} value={String(d)} aria-label={t(`dbq.sched.dayLong.${d}`)}
              className="h-9 min-w-11 px-2 text-xs pointer-coarse:h-10">{t(`dbq.sched.day.${d}`)}</ToggleGroupItem>
          ))}
        </ToggleGroup>
        <Button type="button" variant="ghost" size="icon" disabled={disabled} onClick={onRemove}
          aria-label={t('dbq.sched.removeWindow', label)} className="ml-auto size-9 text-destructive hover:text-destructive pointer-coarse:size-10">
          <Trash2 aria-hidden="true" />
        </Button>
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <div className="flex flex-col gap-1">
          <Label htmlFor={fromId} className="text-xs text-muted-foreground">{t('dbq.sched.from')}</Label>
          <Input id={fromId} type="time" step={60} value={w.from || ''} disabled={disabled} aria-invalid={p.from ? true : undefined}
            className={cn(CONTROL, 'w-[8rem]')} onChange={(e) => onChange({ ...w, from: e.target.value })} />
        </div>
        <div className="flex flex-col gap-1">
          <Label htmlFor={toId} className="text-xs text-muted-foreground">{t('dbq.sched.to')}</Label>
          <Input id={toId} type="time" step={60} value={w.to || ''} disabled={disabled} aria-invalid={p.to ? true : undefined}
            className={cn(CONTROL, 'w-[8rem]')} onChange={(e) => onChange({ ...w, to: e.target.value })} />
        </div>
        <p data-slot="dbq-window-summary" className="m-0 min-w-0 flex-1 basis-48 pb-2 text-xs [overflow-wrap:anywhere]">
          {wk === 'overnight' && <Moon aria-hidden="true" className="mr-1 inline size-3.5 text-indigo-600 dark:text-indigo-300" />}
          {windowSummary(w, t)}
        </p>
      </div>
      {wk === 'overnight' && <p className="m-0 text-xs text-muted-foreground">{t('dbq.sched.overnightHint', w.from, w.to)}</p>}
      {(p.days || p.from || p.to || serverErr) && (
        <FieldError>{serverErr || t(`dbq.sched.v.${p.days || p.from || p.to}`)}</FieldError>
      )}
    </div>
  )
}

/** "Tatil takviminde çalışma" — takvim yeni sekmede açılır (form kaybolmasın). */
function HolidayToggle({ checked, disabled, onChange }) {
  const t = useT()
  const id = useId()
  return (
    <div data-slot="dbq-skip-holidays" className="flex flex-col gap-1.5 rounded-lg border p-3">
      <div className="flex min-h-9 items-center gap-2 pointer-coarse:min-h-10">
        <Switch id={id} checked={checked} disabled={disabled} onCheckedChange={(b) => onChange(b === true)} />
        <Label htmlFor={id} className="cursor-pointer self-stretch font-semibold">
          <CalendarOff aria-hidden="true" className="size-4" />{t('dbq.sched.skipHolidays')}
        </Label>
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t('dbq.sched.skipHolidaysHint')}</p>
      <div>
        <Button asChild variant="link" size="sm" className="h-auto px-0 pointer-coarse:min-h-10">
          <a href="/?tab=settings&sec=holidays" target="_blank" rel="noopener noreferrer">
            {t('dbq.sched.openHolidays')}<ExternalLink aria-hidden="true" />
          </a>
        </Button>
      </div>
    </div>
  )
}

/** Ek tarihler — seçilen gün listeye eklenir; çiplerden kaldırılır. */
function SkipDates({ dates, disabled, error, lang, onChange }) {
  const t = useT()
  const list = Array.isArray(dates) ? dates : []
  const [pick, setPick] = useState('')
  const [problem, setProblem] = useState(null)
  const add = (day) => {
    const res = addSkipDate(list, day)
    setProblem(res.problem)
    setPick('')
    if (!res.problem) onChange(res.list)
  }
  return (
    <section data-slot="dbq-skip-dates" data-field="skip_dates" className="flex min-w-0 flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-col gap-0.5">
        <span className="text-sm font-semibold">{t('dbq.sched.skipDates')}</span>
        <span className="text-xs text-muted-foreground">{t('dbq.sched.skipDatesHint')}</span>
      </div>
      <div className="max-w-xs">
        <DateTimeField dateOnly value={pick} onChange={(d) => (d ? add(d) : setPick(''))} disabled={disabled || list.length >= 100}
          placeholder={t('dbq.sched.addDate')} />
      </div>
      {list.length > 0 && (
        <ul className="m-0 flex list-none flex-wrap gap-1.5 p-0">
          {list.map((d) => (
            <li key={d}>
              <Badge variant="outline" className="h-auto gap-1 rounded-md py-0.5 pr-0.5 pl-2 font-normal whitespace-normal">
                <span>{formatDay(d, lang)}</span>
                <Button type="button" variant="ghost" size="icon-xs" disabled={disabled}
                  aria-label={t('dbq.sched.removeDate', formatDay(d, lang))}
                  className="text-muted-foreground hover:text-destructive pointer-coarse:after:absolute pointer-coarse:after:-inset-2 relative"
                  onClick={() => onChange(list.filter((x) => x !== d))}>
                  <X aria-hidden="true" />
                </Button>
              </Badge>
            </li>
          ))}
        </ul>
      )}
      <span className="text-xs text-muted-foreground tabular-nums">{t('dbq.sched.datesCount', list.length, 100)}</span>
      {(error || problem) && <FieldError>{error || t(`dbq.sched.v.${problem}`)}</FieldError>}
    </section>
  )
}

/** Sonraki 10 çalışma + etkin aralık + "hiç çalışmaz". */
function PreviewPanel({ preview, error, neverRunsMessage, lang, t, mode }) {
  const d = preview.data
  const runs = Array.isArray(d?.next) ? d.next : []
  return (
    <section data-slot="dbq-schedule-preview" data-state={preview.state} data-field="schedule"
      className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-3" aria-live="polite">
      <span className="inline-flex items-center gap-1.5 text-sm font-semibold"><CalendarClock aria-hidden="true" className="size-4" />{t('dbq.sched.previewTitle')}</span>
      {preview.state === 'idle' && (
        <p className="m-0 text-xs text-muted-foreground">
          {preview.reason === 'cronEmpty' ? t('dbq.sched.previewNeedsCron') : t('dbq.sched.previewNeedsInterval')}
        </p>
      )}
      {preview.state === 'loading' && <p className="m-0 text-xs text-muted-foreground">{t('dbq.sched.previewLoading')}</p>}
      {preview.state === 'unknown' && (
        <p data-slot="dbq-schedule-preview-unknown" className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
          {t('dbq.sched.previewUnavailable')}{preview.message ? ` — ${preview.message}` : ''}
        </p>
      )}
      {preview.state === 'ok' && (d?.never_runs || neverRunsMessage) && (
        <AlertBanner tone="warning" title={t('dbq.sched.neverRunsTitle')} className="mb-0">
          <span data-slot="dbq-schedule-never-runs" className="[overflow-wrap:anywhere]">{neverRunsMessage || t('dbq.sched.neverRunsBody')}</span>
        </AlertBanner>
      )}
      {preview.state === 'ok' && !d?.never_runs && !neverRunsMessage && d?.valid === false && runs.length === 0 && (
        <p className="m-0 text-xs text-muted-foreground">{t('dbq.sched.previewInvalid')}</p>
      )}
      {preview.state === 'ok' && runs.length > 0 && (
        <>
          <ol data-slot="dbq-next-runs" className="m-0 grid list-none grid-cols-1 gap-1 p-0 sm:grid-cols-2">
            {runs.slice(0, 10).map((iso, i) => (
              <li key={`${iso}-${i}`} className="flex items-baseline gap-2 rounded-md bg-muted/40 px-2 py-1 text-sm tabular-nums">
                <span className="w-5 shrink-0 text-right text-xs text-muted-foreground">{i + 1}.</span>
                <span className="[overflow-wrap:anywhere]">{formatRun(iso, lang)}</span>
              </li>
            ))}
          </ol>
          <p className="m-0 text-xs text-muted-foreground">
            {t('dbq.sched.tzNote')}
            {d?.effective_interval_seconds > 0 && mode === 'CRON' ? ` · ${t('dbq.sched.effective', durationText(d.effective_interval_seconds, t))}` : ''}
          </p>
        </>
      )}
      {error && <FieldError>{error}</FieldError>}
    </section>
  )
}
