import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { BellRing, CalendarDays, Clock, Mail, PauseCircle, Plus, RotateCcw, Smartphone, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import { SettingsSaveBar } from '../admin/SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Input } from '@/components/shadcn/input'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { useUnsavedWork } from '../../utils/unsavedWork.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { focusFormError, serverFieldErrors } from '../../utils/formErrors.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { codeOf, errorKey, thrownResult } from './routineModel.js'
import {
  SETTINGS_MAX_TIMES, WEEKDAYS, istanbulStamp, normalizeSettings, settingsPatch, validateSettings,
} from './templateEditorModel.js'

/**
 * Takımın NÖBET RUTİNİ AYARLARI (2026-11-26, `or_view=settings`): çalışma günleri, resmî tatiller, gün başlangıcı (o günün
 * kimin nöbet günü olduğunu belirler), hatırlatmalar (saatler, bölüm bitişi, sabah vadesi gelenler), tamamlama ve devir
 * bildirimleri (e-posta / push), rutini duraklatma. Kayıt YALNIZ değişen alanları + `version` gönderir (eşzamanlı kayıt
 * 409 alır — kimin ne zaman kaydettiği söylenir, son hâl yüklenebilir). Yetkisi olmayan aynı ekranı salt okunur görür.
 *
 * @param {{teamId: number|string, canEdit: boolean, onSaved?: () => void}} props
 */
function SwitchRow({ id, slot, label, hint, checked, disabled, onChange, field, error }) {
  return (
    <div data-field={field} className="flex flex-col gap-1">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <Label htmlFor={id} className="text-sm font-medium leading-snug">{label}</Label>
          {hint && <p id={`${id}-h`} className="mt-0.5 text-xs text-muted-foreground">{hint}</p>}
        </div>
        <Switch id={id} data-slot={slot} checked={!!checked} disabled={disabled} aria-describedby={hint ? `${id}-h` : undefined}
          aria-invalid={error ? true : undefined} onCheckedChange={(v) => onChange(v === true)} className="mt-0.5 shrink-0" />
      </div>
      {error && <p data-slot="or-set-error" className="text-xs text-destructive">{error}</p>}
    </div>
  )
}

function SectionCard({ icon: Icon, title, description, children, slot }) {
  return (
    <Card data-slot={slot} className="gap-0 py-0 shadow-none">
      <div className="flex items-start gap-3 border-b border-border px-4 py-3">
        <span aria-hidden="true" className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
          <Icon className="size-4" />
        </span>
        <div className="min-w-0">
          <h3 className="text-sm font-semibold">{title}</h3>
          {description && <p className="text-xs text-muted-foreground">{description}</p>}
        </div>
      </div>
      <div className="flex flex-col gap-4 px-4 py-4">{children}</div>
    </Card>
  )
}

export default function RoutineTeamSettings({ teamId, canEdit, onSaved = () => {}, meName = null }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const uid = useId()
  const [load, setLoad] = useState({ loading: true, error: null, info: null })
  const [base, setBase] = useState(null)
  const [form, setForm] = useState(null)
  const [meta, setMeta] = useState({ version: 0, by: null, at: null, canEditTemplate: true })
  const [saving, setSaving] = useState(false)
  const [errors, setErrors] = useState({})
  const [conflict, setConflict] = useState(null)
  const seq = useRef(0)
  const ctrl = useRef(null)
  const rootRef = useRef(null)
  const tRef = useRef(t)
  tRef.current = t

  const loadSettings = useCallback(async () => {
    const my = ++seq.current
    ctrl.current?.abort()
    const c = typeof AbortController !== 'undefined' ? new AbortController() : null
    ctrl.current = c
    setLoad({ loading: true, error: null, info: null })
    let res
    try {
      res = await api.oncallRoutines.template(teamId, c ? { signal: c.signal } : undefined)
    } catch (e) {
      if (my !== seq.current) return
      setLoad({ loading: false, error: thrownResult(e).error || tRef.current('or.set.loadFailed'), info: errorInfoOf(e) })
      return
    }
    if (my !== seq.current || res === null) return
    if (!res?.success || !res.data) {
      setLoad({ loading: false, error: res?.error || tRef.current('or.set.loadFailed'), info: errorInfoOf(res) })
      return
    }
    const d = res.data
    const s = normalizeSettings(d.settings)
    setBase(s)
    setForm(s)
    setMeta({
      version: Number.isInteger(d.settings_version) ? d.settings_version : 0,
      by: d.settings_updated_by_name || null,
      at: d.settings_updated_at || null,
      canEditTemplate: d.can_edit_template !== false,
    })
    setErrors({})
    setConflict(null)
    setLoad({ loading: false, error: null, info: null })
  }, [teamId])

  useEffect(() => {
    loadSettings()
    return () => { seq.current += 1; ctrl.current?.abort() }
  }, [loadSettings])

  const editable = !!canEdit && meta.canEditTemplate
  const locked = !editable || saving
  const patch = useMemo(() => (base && form ? settingsPatch(base, form) : {}), [base, form])
  const dirty = Object.keys(patch).length > 0

  useUnsavedWork(dirty && editable)
  useEffect(() => {
    if (!dirty || !editable) return undefined
    const h = (e) => { e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty, editable])

  const clearErr = (...keys) => setErrors((m) => {
    if (!keys.some((k) => m[k])) return m
    const n = { ...m }
    for (const k of keys) delete n[k]
    return n
  })
  const set = (fn, ...keys) => { setForm((f) => (f ? fn(f) : f)); clearErr(...keys) }
  const setSub = (group, key, value, ...keys) => set((f) => ({ ...f, [group]: { ...f[group], [key]: value } }), ...keys)

  const toggleWorkday = (d, on) => set((f) => {
    const s = new Set(f.workdays)
    if (on) s.add(d); else s.delete(d)
    return { ...f, workdays: [...s].sort((a, b) => a - b) }
  }, 'settings.workdays')

  const times = form?.reminders?.times || []
  const setTime = (i, v) => setSub('reminders', 'times', times.map((x, j) => (j === i ? v : x)), 'settings.reminders.times')
  const removeTime = (i) => setSub('reminders', 'times', times.filter((_, j) => j !== i), 'settings.reminders.times')
  const focusTime = useRef(null)
  const addTime = () => {
    if (times.length >= SETTINGS_MAX_TIMES) return
    focusTime.current = times.length
    setSub('reminders', 'times', [...times, ''], 'settings.reminders.times')
  }
  useEffect(() => {
    const i = focusTime.current
    if (i == null) return
    focusTime.current = null
    rootRef.current?.querySelector(`[data-slot="or-set-reminder-time"][data-index="${i}"]`)?.focus?.()
  })

  const togglePause = async (enabledNext) => {
    if (!enabledNext) {
      const ok = await showConfirm({
        title: t('or.set.pauseTitle'),
        message: t('or.set.pauseBody'),
        confirmText: t('or.set.pauseConfirm'),
        variant: 'warning',
      })
      if (!ok) return
    }
    set((f) => ({ ...f, enabled: enabledNext }), 'settings.enabled')
  }

  const showErrors = (map) => {
    setErrors(map)
    setTimeout(() => focusFormError(map, rootRef.current), 0)
  }

  const save = async () => {
    if (!editable || saving || !dirty || conflict) return
    const v = validateSettings(form, patch)
    if (Object.keys(v).length) {
      const m = {}
      for (const [k, e] of Object.entries(v)) m[k] = t(e.k, ...(e.a || []))
      showErrors(m)
      return
    }
    setSaving(true)
    try {
      let res
      try {
        res = await api.oncallRoutines.saveSettings(teamId, { version: meta.version, settings: patch })
      } catch (e) {
        toast.error(isUnsureResult(e) ? t('or.set.saveUnsure') : (e?.message || t('or.set.saveFailed')), { details: e })
        return
      }
      if (res === null) return
      if (res?.success) {
        const d = res.data || {}
        if (d.changed === false) {
          setBase(form)
          toast.info(t('or.set.noChange'))
          return
        }
        const saved = d.settings ? normalizeSettings(d.settings) : normalizeSettings(form)
        setBase(saved)
        setForm(saved)
        setErrors({})
        setMeta((m) => ({
          ...m,
          version: Number.isInteger(d.settings_version) ? d.settings_version : m.version + 1,
          // Kaydeden = ben (sunucunun adı / anı; yoksa oturumdaki ad ve yanıtın sunucu anı)
          by: d.settings_updated_by_name ?? meName ?? null,
          at: d.settings_updated_at ?? res.timestamp ?? new Date().toISOString(),
        }))
        toast.success(t('or.set.saved'))
        onSaved()
        return
      }
      const code = codeOf(res) || errorInfoOf(res)?.code
      if (code === 'CONCURRENT_MODIFICATION') {
        setConflict({ by: res.updated_by_name || null, at: res.updated_at || null })
        return
      }
      const fields = serverFieldErrors(res)
      if (fields) {
        showErrors(fields)
        return
      }
      if (isUnsureResult(res)) {
        toast.error(t('or.set.saveUnsure'), { details: res })
        return
      }
      toast.error(res?.error || (errorKey(code) ? t(errorKey(code)) : t('or.set.saveFailed')), { details: res })
    } finally {
      setSaving(false)
    }
  }

  const discard = () => {
    setForm(base)
    setErrors({})
  }

  if (load.loading && !form) {
    return (
      <div data-slot="or-settings-loading" className="flex flex-col gap-3" aria-busy="true">
        <Skeleton className="h-36 w-full rounded-xl" />
        <Skeleton className="h-36 w-full rounded-xl" />
      </div>
    )
  }
  if (load.error && !form) {
    return (
      <div data-slot="or-settings-load-failed">
        <StatusBlock tone="danger" title={t('or.set.loadFailedTitle')} description={load.error} details={load.info || undefined}
          actions={<Button type="button" onClick={loadSettings} className="pointer-coarse:h-10"><RotateCcw aria-hidden="true" /> {t('or.tpl.retry')}</Button>} />
      </div>
    )
  }
  if (!form) return null

  const stamp = istanbulStamp(meta.at)
  const notice = (key, title, hint) => {
    const n = form[key]
    return (
      <div data-slot={`or-set-${key.replace('_', '-')}`} className="flex flex-col gap-3">
        <SwitchRow id={`${uid}-${key}-on`} label={title} hint={hint} checked={n.enabled} disabled={locked}
          onChange={(v) => setSub(key, 'enabled', v, `settings.${key}.mail`)} />
        <div data-field={`settings.${key}.mail`} className={cn('flex flex-col gap-2 ps-1', !n.enabled && 'opacity-60')}>
          <div className="flex flex-wrap gap-x-5 gap-y-2">
            <label className="flex min-h-10 items-center gap-2 text-sm">
              <Checkbox checked={n.mail} disabled={locked || !n.enabled}
                onCheckedChange={(v) => setSub(key, 'mail', v === true, `settings.${key}.mail`)} />
              <Mail aria-hidden="true" className="size-4 text-muted-foreground" /> {t('or.set.channelMail')}
            </label>
            <label className="flex min-h-10 items-center gap-2 text-sm">
              <Checkbox checked={n.push} disabled={locked || !n.enabled}
                onCheckedChange={(v) => setSub(key, 'push', v === true, `settings.${key}.mail`)} />
              <Smartphone aria-hidden="true" className="size-4 text-muted-foreground" /> {t('or.set.channelPush')}
            </label>
          </div>
          {errors[`settings.${key}.mail`] && <p data-slot="or-set-error" className="text-xs text-destructive">{errors[`settings.${key}.mail`]}</p>}
        </div>
      </div>
    )
  }

  return (
    <div ref={rootRef} data-slot="or-settings" data-editable={editable ? 'true' : 'false'} className="@container flex flex-col gap-4">
      <div className="min-w-0">
        <h2 className="text-base font-semibold">{t('or.set.heading')}</h2>
        {(meta.by || stamp) && (
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {meta.by && stamp ? t('or.tpl.savedBy', meta.by, `${stamp.date} ${stamp.hm}`) : t('or.set.neverSaved')}
          </p>
        )}
      </div>

      {!editable && (
        <div data-slot="or-set-readonly">
          <AlertBanner tone="info" className="mb-0">{t('or.set.readOnly')}</AlertBanner>
        </div>
      )}
      {conflict && (
        <div data-slot="or-set-conflict">
          <AlertBanner tone="warning" role="alert" className="mb-0" title={t('or.tpl.conflictTitle')}
            actions={(
              <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" data-slot="or-set-conflict-load" onClick={loadSettings}>
                <RotateCcw aria-hidden="true" /> {t('or.tpl.loadLatest')}
              </Button>
            )}>
            {(() => {
              const st = istanbulStamp(conflict.at)
              return conflict.by && st ? t('or.set.conflictBody', conflict.by, `${st.date} ${st.hm}`) : t('or.set.conflictBodyPlain')
            })()}
          </AlertBanner>
        </div>
      )}
      {!form.enabled && (
        <div data-slot="or-set-paused">
          <AlertBanner tone="warning" className="mb-0" title={t('or.set.pausedTitle')}>{t('or.set.pausedBody')}</AlertBanner>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 @3xl:grid-cols-2">
        <SectionCard slot="or-set-days" icon={CalendarDays} title={t('or.set.daysTitle')} description={t('or.set.daysDesc')}>
          <div data-field="settings.workdays" className="flex flex-col gap-2">
            <span id={`${uid}-wd`} className="text-sm font-medium">{t('or.set.workdays')}</span>
            <div role="group" aria-labelledby={`${uid}-wd`} className="flex flex-wrap gap-2">
              {WEEKDAYS.map((d) => {
                const on = form.workdays.includes(d)
                return (
                  <label key={d} data-slot="or-set-workday" data-day={d} data-selected={on ? 'true' : 'false'}
                    className={cn('flex min-h-10 cursor-pointer items-center gap-2 rounded-lg border px-3 text-sm',
                      on ? 'border-primary bg-primary/5' : 'border-border', locked && 'cursor-default opacity-70')}>
                    <Checkbox checked={on} disabled={locked} aria-label={t(`or.weekday.${d}`)}
                      onCheckedChange={(v) => toggleWorkday(d, v === true)} />
                    <span aria-hidden="true">{t(`or.weekdayShort.${d}`)}</span>
                  </label>
                )
              })}
            </div>
            {errors['settings.workdays'] && <p data-slot="or-set-error" className="text-xs text-destructive">{errors['settings.workdays']}</p>}
          </div>
          <SwitchRow id={`${uid}-hol`} slot="or-set-holidays" label={t('or.set.skipHolidays')} hint={t('or.set.skipHolidaysHint')}
            checked={form.skip_holidays} disabled={locked}
            onChange={(v) => set((f) => ({ ...f, skip_holidays: v }), 'settings.skip_holidays')} />
          <Field label={t('or.set.dayStart')} name="settings.day_start" error={errors['settings.day_start']} hint={t('or.set.dayStartHint')}
            className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="time" value={form.day_start} disabled={locked} aria-describedby={describedBy} aria-invalid={invalid}
                className="w-36" onChange={(e) => set((f) => ({ ...f, day_start: e.target.value }), 'settings.day_start')} />
            )}
          </Field>
        </SectionCard>

        <SectionCard slot="or-set-reminders" icon={Clock} title={t('or.set.remindersTitle')} description={t('or.set.remindersDesc')}>
          <SwitchRow id={`${uid}-rem`} slot="or-set-reminders-on" label={t('or.set.remindersOn')} checked={form.reminders.enabled}
            disabled={locked} onChange={(v) => setSub('reminders', 'enabled', v, 'settings.reminders.enabled')} />
          <div data-field="settings.reminders.times" className={cn('flex flex-col gap-2', !form.reminders.enabled && 'opacity-60')}>
            <span id={`${uid}-times`} className="text-sm font-medium">{t('or.set.reminderTimes')}</span>
            {times.length === 0 && <p className="text-xs text-muted-foreground">{t('or.set.noTimes')}</p>}
            <div role="group" aria-labelledby={`${uid}-times`} className="flex flex-wrap gap-2">
              {times.map((tm, i) => (
                <div key={i} className="flex items-center gap-1">
                  <Input type="time" value={tm} data-slot="or-set-reminder-time" data-index={i} disabled={locked || !form.reminders.enabled}
                    aria-label={t('or.set.reminderTimeN', i + 1)} aria-invalid={errors['settings.reminders.times'] ? true : undefined}
                    className="w-32" onChange={(e) => setTime(i, e.target.value)} />
                  {editable && (
                    <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10" data-slot="or-set-reminder-remove"
                      disabled={locked || !form.reminders.enabled} aria-label={t('or.set.reminderRemoveN', i + 1)} onClick={() => removeTime(i)}>
                      <X aria-hidden="true" />
                    </Button>
                  )}
                </div>
              ))}
            </div>
            {editable && (
              <div>
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" data-slot="or-set-reminder-add"
                  disabled={locked || !form.reminders.enabled || times.length >= SETTINGS_MAX_TIMES} onClick={addTime}>
                  <Plus aria-hidden="true" /> {t('or.set.reminderAdd')}
                </Button>
              </div>
            )}
            <p className="text-xs text-muted-foreground">{t('or.set.reminderTimesHint', SETTINGS_MAX_TIMES)}</p>
            {errors['settings.reminders.times'] && <p data-slot="or-set-error" className="text-xs text-destructive">{errors['settings.reminders.times']}</p>}
          </div>
          <SwitchRow id={`${uid}-dl`} label={t('or.set.sectionDeadlines')} hint={t('or.set.sectionDeadlinesHint')}
            checked={form.reminders.section_deadlines} disabled={locked || !form.reminders.enabled}
            onChange={(v) => setSub('reminders', 'section_deadlines', v)} />
          <SwitchRow id={`${uid}-dm`} label={t('or.set.dueMorning')} hint={t('or.set.dueMorningHint')}
            checked={form.reminders.due_morning} disabled={locked || !form.reminders.enabled}
            onChange={(v) => setSub('reminders', 'due_morning', v)} />
        </SectionCard>

        <SectionCard slot="or-set-notices" icon={BellRing} title={t('or.set.noticesTitle')} description={t('or.set.noticesDesc')}>
          {notice('completion_notice', t('or.set.completionNotice'), t('or.set.completionNoticeHint'))}
          {notice('handover_notice', t('or.set.handoverNotice'), t('or.set.handoverNoticeHint'))}
        </SectionCard>

        <SectionCard slot="or-set-pause" icon={PauseCircle} title={t('or.set.pauseSection')} description={t('or.set.pauseDesc')}>
          <SwitchRow id={`${uid}-en`} slot="or-set-enabled" field="settings.enabled" label={t('or.set.enabled')}
            hint={form.enabled ? t('or.set.enabledHintOn') : t('or.set.enabledHintOff')} checked={form.enabled} disabled={locked}
            error={errors['settings.enabled']} onChange={togglePause} />
        </SectionCard>
      </div>

      {editable && (
        <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={discard} disabled={!!conflict} />
      )}
    </div>
  )
}
