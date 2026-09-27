import { useEffect, useState } from 'react'
import { Play } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field, { FieldError } from '../ui/Field.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import MaintenanceTargetPicker from '../monitoring/MaintenanceTargetPicker.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { AllMonitorsCheckbox, DurationPicker, targetObjs } from './MaintenanceEditor.jsx'
import { countText, useScheduleText } from './maintenanceUi.jsx'

const EMPTY_QUICK = { name: '', allMonitors: false, targets: [], minutes: 30 }

/**
 * Hızlı pencere ("X'i 30/60/120 dk sustur") — ad isteğe bağlı, hedefler ya da tümü, süre ön ayarları.
 * Şu andan başlar (sunucu `quick` ucu). Sunucu hatası AlertBanner; hedef seçilmemişse alan hatası.
 */
export default function MaintenanceQuickModal({ open, monitorOptions, saving, onStart, onClose }) {
  const t = useT()
  const { timeOf } = useScheduleText()
  const [form, setForm] = useState(EMPTY_QUICK)
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  useEffect(() => { if (open) { setForm(EMPTY_QUICK); setErrors({}); setServerError(null) } }, [open])
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  async function submit() {
    const e = {}
    if (!form.allMonitors && form.targets.length === 0) e.targets = 'mw.targetsRequired'
    if (!(Number(form.minutes) >= 1)) e.duration = 'mw.durationRequired'
    setErrors(e)
    if (Object.keys(e).length) return
    setServerError(null)
    const res = await onStart({
      name: form.name?.trim() || null, allMonitors: form.allMonitors,
      targets: form.allMonitors ? [] : targetObjs(form.targets, monitorOptions), minutes: Number(form.minutes),
    })
    if (res && !res.success) setServerError(res.error || t('mw.saveError'))
  }

  const scope = form.allMonitors ? t('mw.allMonitors') : countText(t, form.targets.length, 'mw.targetsOne', 'mw.targetsCount')
  const endsAt = Number(form.minutes) >= 1 ? timeOf(Date.now() + Number(form.minutes) * 60_000, Intl.DateTimeFormat().resolvedOptions().timeZone) : null

  return (
    <ModalShell open={open} onClose={onClose} busy={saving} dismissOnBackdrop={false} scrollBody
      // md (620 px): hedef seçicinin iki sütunu (tür listesi 190 px + monitörler) sm kabukta (460 px) sıkışıp taşıyordu
      title={t('mw.quickTitle')} icon={Play} size="md"
      footer={<>
        <Button variant="secondary" onClick={onClose}>{t('mw.cancel')}</Button>
        <Button onClick={submit} disabled={saving} aria-busy={saving || undefined}><Play aria-hidden="true" />{saving ? '…' : t('mw.startNow')}</Button>
      </>}>
      {serverError && <AlertBanner tone="danger" role="alert" title={t('mw.saveError')}>{serverError}</AlertBanner>}
      <p className="mt-0 mb-3 text-[0.86em] text-muted-foreground">{t('mw.quickHint')}</p>
      <Field label={t('mw.name')}>
        {({ id }) => <Input id={id} value={form.name} placeholder={t('mw.quickNamePh')} onChange={(e) => set({ name: e.target.value })} />}
      </Field>
      <fieldset data-slot="mw-targets" className="mb-3.5 flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3.5 py-3">
        <legend className="px-1 text-[0.9em] font-bold">{t('mw.monitorsTitle')}</legend>
        <AllMonitorsCheckbox checked={form.allMonitors} label={t('mw.allMonitorsOpt')} onChange={(v) => set({ allMonitors: v })} />
        {!form.allMonitors && (
          <MaintenanceTargetPicker options={monitorOptions} value={form.targets}
            onChange={(v) => set({ targets: v })} typeLabel={(ty) => t('mw.type.' + ty)} />
        )}
        {errors.targets && <FieldError>{t(errors.targets)}</FieldError>}
      </fieldset>
      <DurationPicker label={t('mw.durationLabel')} minutes={form.minutes} onChange={(m) => set({ minutes: m })}
        error={errors.duration ? t(errors.duration) : undefined} />
      <div data-slot="mw-summary" className="rounded-md bg-muted/50 px-3 py-2 text-[0.86em] leading-relaxed">
        <span className="font-bold">{t('mw.summaryLabel')}:</span> {endsAt ? t('mw.quickSummary', scope, endsAt) : t('mw.durationRequired')}
      </div>
    </ModalShell>
  )
}
