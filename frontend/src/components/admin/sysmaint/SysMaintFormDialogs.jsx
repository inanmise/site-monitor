import { useEffect, useId, useMemo, useState } from 'react'
import { CalendarClock, Power, Users, Wrench } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import MultiTeamSelect from '../../ui/MultiTeamSelect.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { useFormErrors } from '../../../hooks/useFormErrors.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Switch } from '@/components/shadcn/switch'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { FieldLegend, FieldSet } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import {
  DEFAULT_OPTIONS, defaultPlanForm, defaultStartNowForm, fieldOf, formFromWindow, hasRecipients, planPayload,
  startNowPayload, validatePlan,
} from './sysmaintModel.js'

/**
 * Sistem Bakımı formları (2026-10-02, kullanıcı kararı) — shadcn ModalShell + ui/Field; doğrulama hatası ALANIN ALTINDA
 * (`useFormErrors`: ilk hatalı alana kaydırır/odaklar; sunucunun 400 + `field` yanıtı da aynı yere düşer).
 *
 *  • {@link SysMaintPlanDialog} — planla / düzenle: başlangıç–bitiş (İstanbul), uyarı süresi, duyuru süresi, "Bildirimler
 *    bakım boyunca sussun", TR/EN mesaj + iletişim, e-posta duyurusu (tüm aktif kullanıcılar / takım adresleri +
 *    düzeltme e-postası + "Bakım bitince de e-posta gönder"), etki özeti.
 *  • {@link SysMaintStartNowDialog} — hemen bakıma al: geri sayım (0 = 10 sn'lik pencere) + süre + susturma + mesaj +
 *    yalnız bitişte giden "tamamlandı" e-postasının alıcıları; gönderim AlertDialog onayıyla (etki özeti).
 *
 * "Bakım bitince de e-posta gönder" (2026-10-02, kullanıcı isteği): varsayılan işaretli; alıcı seçilmeden devre dışı
 * (ipucuyla) — alıcısız pencere için sunucu zaten göndermez.
 *
 * Test kancaları: `data-slot="sysmaint-plan-form"`, `sysmaint-start-form`, `sysmaint-impact`, `sysmaint-submit`,
 * `sysmaint-email`, `sysmaint-email-on-end`.
 */

/** Etki özeti — "Şu an N kullanıcı oturum açık; bunların M'i global yönetici değil ve bakım başlayınca çıkış yapacak." */
export function ImpactBanner({ impact, tone = 'info' }) {
  const t = useT()
  if (!impact) return null
  return (
    <div data-slot="sysmaint-impact">
      <AlertBanner tone={tone} icon={Users} className="mb-0">
        {t('sysmaint.impact', impact.live_sessions ?? 0, impact.affected_sessions ?? 0)}
      </AlertBanner>
    </div>
  )
}

/** İstanbul tarih + saat çifti (tarih: shadcn Calendar; saat: shadcn Input type=time). Hata tarih alanının altında. */
function LocalDateTime({ label, date, time, onDate, onTime, fieldProps, required = true }) {
  const t = useT()
  const timeId = useId()
  return (
    <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-start gap-x-3">
      <Field label={label} required={required} hint={t('sysmaint.form.istanbulHint')} className="min-w-0" {...fieldProps}>
        {({ id, describedBy, invalid }) => (
          <DateTimeField dateOnly id={id} value={date} onChange={onDate} invalid={invalid} describedBy={describedBy} />
        )}
      </Field>
      <div className="flex flex-col gap-1.5 pt-0">
        <Label htmlFor={timeId} className="font-semibold">{t('sysmaint.form.time')}</Label>
        <Input id={timeId} type="time" step={60} value={time} onChange={(e) => onTime(e.target.value)}
          className="h-10 w-[7.5rem]" aria-invalid={fieldProps?.error ? true : undefined} />
      </div>
    </div>
  )
}

/** Mesaj + iletişim + susturma (iki formda ortak). */
function CommonFields({ form, set, fe }) {
  const t = useT()
  const muteId = useId()
  return (
    <>
      <div className="mb-3.5 flex items-start gap-3 rounded-lg border p-3" data-slot="sysmaint-mute">
        <Switch id={muteId} checked={!!form.mute} onCheckedChange={(v) => set({ mute: v === true })} className="mt-0.5" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <Label htmlFor={muteId} className="cursor-pointer font-semibold">{t('sysmaint.form.mute')}</Label>
          <span className="text-xs text-muted-foreground">{form.mute ? t('sysmaint.form.muteOn') : t('sysmaint.form.muteOff')}</span>
        </div>
      </div>
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
        <Field label={t('sysmaint.form.messageTr')} hint={t('sysmaint.form.messageHint')} {...fe.fieldProps('message_tr')}>
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} rows={3} maxLength={1000} value={form.messageTr} aria-describedby={describedBy} aria-invalid={invalid}
              placeholder={t('sysmaint.form.messageTrPh')}
              onChange={(e) => { set({ messageTr: e.target.value }); fe.clear('message_tr') }} />
          )}
        </Field>
        <Field label={t('sysmaint.form.messageEn')} hint={t('sysmaint.form.messageHint')} {...fe.fieldProps('message_en')}>
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} rows={3} maxLength={1000} value={form.messageEn} aria-describedby={describedBy} aria-invalid={invalid}
              placeholder={t('sysmaint.form.messageEnPh')}
              onChange={(e) => { set({ messageEn: e.target.value }); fe.clear('message_en') }} />
          )}
        </Field>
      </div>
      <Field label={t('sysmaint.form.contact')} hint={t('sysmaint.form.contactHint')} {...fe.fieldProps('contact')}>
        {({ id, describedBy, invalid }) => (
          <Input id={id} maxLength={300} value={form.contact} aria-describedby={describedBy} aria-invalid={invalid}
            placeholder={t('sysmaint.form.contactPh')}
            onChange={(e) => { set({ contact: e.target.value }); fe.clear('contact') }} />
        )}
      </Field>
    </>
  )
}

/** "Bakım bitince de e-posta gönder" — alıcı seçilmeden devre dışı; ipucu durumu söyler. */
function EmailOnEndOption({ form, set }) {
  const t = useT()
  const id = useId()
  const hintId = useId()
  const enabled = hasRecipients(form)
  return (
    <div className="flex min-h-10 items-start gap-2 py-1" data-slot="sysmaint-email-on-end" data-enabled={enabled ? 'true' : 'false'}>
      <Checkbox id={id} checked={!!form.emailOnEnd} disabled={!enabled} aria-describedby={hintId} className="mt-0.5"
        onCheckedChange={(v) => set({ emailOnEnd: v === true })} />
      <div className="flex min-w-0 flex-col gap-0.5">
        <Label htmlFor={id} className={cn('cursor-pointer font-normal', !enabled && 'cursor-not-allowed opacity-70')}>
          {t('sysmaint.form.emailOnEnd')}
        </Label>
        <span id={hintId} className="text-xs text-muted-foreground">
          {enabled ? t('sysmaint.form.emailOnEndHint') : t('sysmaint.form.emailOnEndNoRecipients')}
        </span>
      </div>
    </div>
  )
}

/**
 * E-posta alıcıları (tüm aktif kullanıcılar / takım adresleri) + seçenekler. `corrections`: planlama formunda düzeltme
 * e-postası anahtarı da çizilir; "hemen bakıma al"da yalnız bitiş e-postası vardır.
 */
function EmailFields({ form, set, fe, recipients, title, hint, corrections = false }) {
  const t = useT()
  const emailAllId = useId()
  const correctionsId = useId()
  const teamsLabelId = useId()
  const teamOptions = useMemo(() => (recipients?.teams || []).map((tm) => ({
    value: tm.id, label: tm.email ? tm.name : `${tm.name} — ${t('sysmaint.form.noTeamEmail')}`,
  })), [recipients, t])
  return (
    <FieldSet className="mt-1 min-w-0 gap-2.5 rounded-lg border p-3" data-slot="sysmaint-email">
      <FieldLegend variant="label" className="mb-0 px-1 font-semibold">{title}</FieldLegend>
      <p className="m-0 text-xs text-muted-foreground">{hint}</p>
      <div className="flex min-h-10 items-center gap-2">
        <Checkbox id={emailAllId} checked={!!form.emailAllUsers} onCheckedChange={(v) => set({ emailAllUsers: v === true })} />
        <Label htmlFor={emailAllId} className="cursor-pointer font-normal">
          {t('sysmaint.form.emailAll', recipients?.active_users_with_email ?? 0)}
        </Label>
      </div>
      <div className="flex min-w-0 flex-col gap-1.5">
        <span id={teamsLabelId} className="text-sm font-medium">{t('sysmaint.form.emailTeams')}</span>
        <MultiTeamSelect value={form.emailTeamIds} onChange={(ids) => { set({ emailTeamIds: ids }); fe.clear('email_team_ids') }}
          options={teamOptions} ariaLabelledBy={teamsLabelId} placeholder={t('sysmaint.form.emailTeamsPh')} />
        {fe.errors.email_team_ids && <span className="text-xs text-destructive">{fe.errors.email_team_ids}</span>}
      </div>
      {corrections && (
        <div className="flex min-h-10 items-center gap-2">
          <Checkbox id={correctionsId} checked={!!form.emailCorrections}
            onCheckedChange={(v) => set({ emailCorrections: v === true })} />
          <Label htmlFor={correctionsId} className="cursor-pointer font-normal">{t('sysmaint.form.emailCorrections')}</Label>
        </div>
      )}
      <EmailOnEndOption form={form} set={set} />
    </FieldSet>
  )
}

/** Sunucu yanıtı → alan hatası (400 + field) ya da tost. true = başarılı. */
function handleResult(res, fe, toast, t, okKey) {
  if (res?.success) {
    toast.success(t(okKey))
    return true
  }
  const field = fieldOf(res?.field)
  if (field) fe.check({ [field]: res?.error || t('sysmaint.err.generic') })
  else toast.error(res?.error || t('sysmaint.err.generic'))
  return false
}

/**
 * Planla / düzenle. `window` verilirse düzenleme (yalnız başlamadan önce — sunucu da reddeder).
 * `serverNowMs`: varsayılan saatler ve geçmiş-zaman kontrolü SUNUCU saatine göre.
 */
export function SysMaintPlanDialog({ open, onClose, onSaved, window: editing, serverNowMs, options = DEFAULT_OPTIONS,
  recipients, impact, windows = [] }) {
  const t = useT()
  const toast = useToast()
  const fe = useFormErrors(open)
  const [form, setForm] = useState(() => (editing ? formFromWindow(editing) : defaultPlanForm(serverNowMs, options)))
  const [busy, setBusy] = useState(false)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  // Pencere her açılışta taze form (düzenlenen kayıt / sunucu saati)
  useEffect(() => {
    if (open) setForm(editing ? formFromWindow(editing) : defaultPlanForm(serverNowMs, options))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, editing?.id])

  async function submit() {
    const errs = validatePlan(form, serverNowMs, windows, editing?.id ?? null, options.max_duration_hours ?? 72)
    const msgs = Object.fromEntries(Object.entries(errs).map(([k, v]) => [k, t(v)]))
    if (fe.check(msgs)) return
    setBusy(true)
    try {
      const body = planPayload(form)
      const res = editing ? await api.systemMaintenance.update(editing.id, body) : await api.systemMaintenance.schedule(body)
      if (handleResult(res, fe, toast, t, editing ? 'sysmaint.toast.updated' : 'sysmaint.toast.scheduled')) onSaved?.(res.data)
    } finally {
      setBusy(false)
    }
  }

  const warnOptions = (options.warn_minutes || DEFAULT_OPTIONS.warn_minutes).map((m) => ({ value: m, label: t('sysmaint.minutesShort', m) }))

  return (
    <ModalShell open={open} onClose={onClose} busy={busy} size="lg" scrollBody dismissOnBackdrop={false}
      icon={CalendarClock} title={editing ? t('sysmaint.form.editTitle') : t('sysmaint.form.planTitle')}
      footer={(
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="min-h-10">{t('app.cancel')}</Button>
          <Button type="button" onClick={submit} disabled={busy} aria-busy={busy || undefined} data-slot="sysmaint-submit" className="min-h-10">
            {busy ? <Spinner size={15} inline decorative /> : <CalendarClock aria-hidden="true" />}
            {editing ? t('sysmaint.form.saveEdit') : t('sysmaint.form.savePlan')}
          </Button>
        </>
      )}>
      <div data-slot="sysmaint-plan-form" className="flex min-w-0 flex-col">
        <ImpactBanner impact={impact} />
        <div className="mt-3.5 grid grid-cols-1 gap-x-6 lg:grid-cols-2">
          <LocalDateTime label={t('sysmaint.form.start')} date={form.startDate} time={form.startTime}
            onDate={(v) => { set({ startDate: v }); fe.clear('start_local') }}
            onTime={(v) => { set({ startTime: v }); fe.clear('start_local') }} fieldProps={fe.fieldProps('start_local')} />
          <LocalDateTime label={t('sysmaint.form.end')} date={form.endDate} time={form.endTime}
            onDate={(v) => { set({ endDate: v }); fe.clear('end_local') }}
            onTime={(v) => { set({ endTime: v }); fe.clear('end_local') }} fieldProps={fe.fieldProps('end_local')} />
        </div>
        <div className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
          <Field label={t('sysmaint.form.warn')} hint={t('sysmaint.form.warnHint')} {...fe.fieldProps('warn_minutes')}>
            {() => (
              <SegmentedControl value={Number(form.warnMinutes)} onChange={(v) => set({ warnMinutes: v })} options={warnOptions}
                ariaLabel={t('sysmaint.form.warn')} />
            )}
          </Field>
          <Field label={t('sysmaint.form.announce')} hint={t('sysmaint.form.announceHint')} {...fe.fieldProps('announce_hours')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={String(form.announceHours)} className="h-10 w-full"
                onChange={(e) => set({ announceHours: Number(e.target.value) })}>
                {(options.announce_hours || DEFAULT_OPTIONS.announce_hours).map((h) => (
                  <NativeSelectOption key={h} value={String(h)}>
                    {h === 0 ? t('sysmaint.form.announceOff') : t('sysmaint.hoursBefore', h)}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            )}
          </Field>
        </div>
        <CommonFields form={form} set={set} fe={fe} />

        <EmailFields form={form} set={set} fe={fe} recipients={recipients} corrections
          title={t('sysmaint.form.emailTitle')} hint={t('sysmaint.form.emailHint')} />
      </div>
    </ModalShell>
  )
}

/** "Hemen bakıma al" — gönderim AlertDialog onayıyla (etki özeti). */
export function SysMaintStartNowDialog({ open, onClose, onSaved, options = DEFAULT_OPTIONS, impact, recipients }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const fe = useFormErrors(open)
  const [form, setForm] = useState(defaultStartNowForm)
  const [busy, setBusy] = useState(false)
  const set = (patch) => setForm((f) => ({ ...f, ...patch }))

  useEffect(() => { if (open) setForm(defaultStartNowForm()) }, [open])

  const countdownLabel = (m) => (Number(m) === 0 ? t('sysmaint.form.countdownNow') : t('sysmaint.minutesShort', m))

  async function submit() {
    const ok = await showConfirm({
      title: t('sysmaint.confirm.startTitle'),
      message: [
        t('sysmaint.confirm.startBody', countdownLabel(form.countdownMinutes), t('sysmaint.minutesShort', form.durationMinutes)),
        t('sysmaint.impact', impact?.live_sessions ?? 0, impact?.affected_sessions ?? 0),
        form.mute ? t('sysmaint.form.muteOn') : t('sysmaint.form.muteOff'),
      ].join('\n\n'),
      variant: 'warning',
      confirmText: t('sysmaint.confirm.startOk'),
      cancelText: t('app.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.systemMaintenance.startNow(startNowPayload(form))
      if (handleResult(res, fe, toast, t, 'sysmaint.toast.started')) onSaved?.(res.data)
    } finally {
      setBusy(false)
    }
  }

  return (
    <ModalShell open={open} onClose={onClose} busy={busy} size="md" scrollBody dismissOnBackdrop={false}
      icon={Power} title={t('sysmaint.form.startTitle')}
      footer={(
        <>
          <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="min-h-10">{t('app.cancel')}</Button>
          <Button type="button" variant="destructive" onClick={submit} disabled={busy} aria-busy={busy || undefined}
            data-slot="sysmaint-submit" className="min-h-10">
            {busy ? <Spinner size={15} inline decorative /> : <Wrench aria-hidden="true" />}{t('sysmaint.form.startSubmit')}
          </Button>
        </>
      )}>
      <div data-slot="sysmaint-start-form" className="flex min-w-0 flex-col">
        <ImpactBanner impact={impact} tone="warning" />
        <div className="mt-3.5 grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <Field label={t('sysmaint.form.countdown')} hint={t('sysmaint.form.countdownHint')} {...fe.fieldProps('countdown_minutes')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={String(form.countdownMinutes)} className="h-10 w-full"
                onChange={(e) => { set({ countdownMinutes: Number(e.target.value) }); fe.clear('countdown_minutes') }}>
                {(options.countdown_minutes || DEFAULT_OPTIONS.countdown_minutes).map((m) => (
                  <NativeSelectOption key={m} value={String(m)}>{countdownLabel(m)}</NativeSelectOption>
                ))}
              </NativeSelect>
            )}
          </Field>
          <Field label={t('sysmaint.form.duration')} {...fe.fieldProps('duration_minutes')}>
            {({ id, describedBy }) => (
              <NativeSelect id={id} aria-describedby={describedBy} value={String(form.durationMinutes)} className="h-10 w-full"
                onChange={(e) => { set({ durationMinutes: Number(e.target.value) }); fe.clear('duration_minutes') }}>
                {(options.duration_minutes || DEFAULT_OPTIONS.duration_minutes).map((m) => (
                  <NativeSelectOption key={m} value={String(m)}>{t('sysmaint.minutesShort', m)}</NativeSelectOption>
                ))}
              </NativeSelect>
            )}
          </Field>
        </div>
        <CommonFields form={form} set={set} fe={fe} />
        <EmailFields form={form} set={set} fe={fe} recipients={recipients}
          title={t('sysmaint.form.startEmailTitle')} hint={t('sysmaint.form.startEmailHint')} />
      </div>
    </ModalShell>
  )
}
