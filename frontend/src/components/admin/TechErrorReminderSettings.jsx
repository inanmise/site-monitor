import { useEffect, useMemo, useRef, useState } from 'react'
import { BellRing, CircleAlert, Mail, RefreshCw, Stamp } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { CheckboxRow, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { normalizeIds, teamOptions } from './noc/operatorTeamsModel.js'
import {
  REM_INT_FIELDS, changedFields, isFormDirty, isUnsupportedResponse, normalizeReminders, toForm, validateForm,
} from './techErrorReminderModel.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

const TOUCH = 'pointer-coarse:min-h-10'

/**
 * Ayarlar → Teknik hata raporu → "Hatırlatmalar ve posta sınırları" (12. sürüm, 2026-10-17 — kullanıcı onayı C9 / C10 /
 * E21). Dönem hatırlatması alan takımlar (seçim yok = KAPALI), ayın günü, tekrar aralığı, push; onay bekleyen rapor
 * hatırlatması; e-posta boyut sınırı ve gövde görsel bütçesi. Hepsi GLOBAL_ONLY: kapsamlı müdür salt okunur görür (kayıt
 * çubuğu yok). Değerler sayfanın okuma dalgasıyla gelir (`state` = `reminderState`: ok / unsupported / failed); kayıt
 * AYRI uca gider — `PUT /api/admin/tech-error-reports/settings/reminders` — ve YALNIZ değişen alanları gönderir; değişiklik
 * yoksa istek yok. Eski pod bu yolu tanımaz (404 / 405, hiçbir şey yazmaz): kart "sunucu henüz desteklemiyor" der ve salt
 * okunur olur (inceleme 2026-10-18: ortak `PUT /settings` gövdesindeki eksik `team_ids` eski pod'da görünürlüğü siliyordu).
 *
 * Sayfayla eşgüdüm (inceleme 2026-10-18): `onSaving(true|false)` — sayfa kayıt sürerken Yenile'yi kapatır ve kayıt
 * başında okuma sırasını ilerletir (kayıttan önce başlamış bir okuma kayıttan sonra gelirse eski değeri "kayıtlı"
 * göstermesin); `onSaved(next)` — sayfanın kayıtlı görüntüsü güncellenir; `onUnsupported()` — kayıt eski pod'a düştü.
 * Alan hataları alanın altında (istemci kuralları sunucuyla aynı; sunucunun `field` hatası da alana düşer).
 *
 * Yerleşim KABA göre (container query): Ayarlar içerik sütunu 768 px tablette ≈ 350 px (sabit kenar çubuğu) — orada tek
 * sütun, dizüstünde (≈ 560 px) iki sütun; telefonda tek sütun. Test kancaları `tes-rem-*`.
 */
export default function TechErrorReminderSettings({
  state, teams = [], readOnly = false, onDirtyChange, onSaving, onSaved, onUnsupported, onRetry,
}) {
  const t = useT()
  const toast = useToast()
  const data = state?.data || null
  const [saved, setSaved] = useState(() => data)
  const [form, setForm] = useState(() => (data ? toForm(data) : null))
  const [saving, setSaving] = useState(false)
  const fe = useFormErrors(null)
  const alive = useRef(true)
  const savedRef = useRef(saved)
  savedRef.current = saved

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  // Üst bileşen yeniden okuduğunda (Yenile): kayıtlı taban güncellenir, kaydedilmemiş taslak korunur
  useEffect(() => {
    if (!data) return
    const prev = savedRef.current
    setSaved(data)
    setForm((cur) => (cur && prev && isFormDirty(prev, cur) ? cur : toForm(data)))
  }, [data])

  const unsupported = state?.status === 'unsupported'
  const locked = readOnly || unsupported || saved?.read_only === true
  // Eski pod'a düşen kayıtta taslak yerinde kalır ve "kaydedilmedi" sayılır (ayrılırken onay sorulur)
  const dirty = !!saved && !!form && isFormDirty(saved, form)
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])

  const options = useMemo(() => teamOptions(teams, form?.team_ids || [], t('noc.ot.inactiveTeam')), [teams, form, t])
  const limits = saved?.limits

  if (!state) return null

  function set(key, value) {
    setForm((f) => ({ ...f, [key]: value }))
    fe.clear(key)
    if (key === 'max_mail_mb') fe.clear('mail_inline_mb')
  }

  async function save() {
    if (saving || locked || !saved || !form) return
    const errs = validateForm(form, limits)
    const map = {}
    for (const k of REM_INT_FIELDS) map[k] = errs[k] ? t(errs[k].key, ...errs[k].params) : false
    if (fe.check(map)) return
    const body = changedFields(saved, form)
    if (Object.keys(body).length === 0) return
    setSaving(true)
    onSaving?.(true)
    try {
      const res = await api.admin.saveTechErrorReminderSettings(body)
      if (!alive.current) return
      if (isUnsupportedResponse(res)) {
        // Eski pod: hiçbir şey yazılmadı — kart notu söyler, taslak yerinde kalır (yeni pod'a düşünce Yenile + Kaydet)
        onUnsupported?.()
        return
      }
      if (!res || res.success === false) {
        const field = res?.field
        if (field && (REM_INT_FIELDS.includes(field) || field === 'reminder_team_ids' || field === 'reminder_push')) {
          fe.check({ [field]: res.error || t('te.set.saveError') })
        } else {
          toast.error(res?.error || t('te.set.saveError'), { details: res?.errorInfo })
        }
        return
      }
      const next = normalizeReminders(res.data) || { ...saved, ...appliedValues(body) }
      setSaved(next)
      // Kayıt sırasında yazılan yeni değişiklik silinmez: gönderilen alanlar kaydedildi, diğerleri taslakta kalır
      setForm((cur) => {
        const sent = toForm(next)
        const merged = { ...cur }
        for (const k of Object.keys(body)) {
          if (k === 'reminder_team_ids') merged.team_ids = sent.team_ids
          else merged[k] = sent[k]
        }
        return merged
      })
      onSaved?.(next)
      toast.success(t('te.rem.saved'))
    } catch (e) {
      toast.error(e?.message || t('te.set.saveError'))
    } finally {
      if (alive.current) setSaving(false)
      onSaving?.(false)
    }
  }

  function discard() {
    if (saved) setForm(toForm(saved))
    fe.reset()
  }

  const retryButton = onRetry && (
    <Button type="button" variant="outline" size="sm" onClick={onRetry} className={cn('h-9 sm:h-8', TOUCH)} data-slot="tes-rem-retry">
      <RefreshCw aria-hidden="true" /> {t('te.retry')}
    </Button>
  )

  const notes = (
    <>
      {unsupported && (
        <div data-slot="tes-rem-unsupported">
          <AlertBanner tone="info" role="status" className="mb-0" title={t('te.rem.unsupportedTitle')}>{t('te.rem.unsupported')}</AlertBanner>
        </div>
      )}
      {state.status === 'failed' && data && (
        <div data-slot="tes-rem-stale">
          <AlertBanner tone="warning" className="mb-0" title={t('te.rem.staleTitle')} actions={retryButton}>
            {state.error ? String(state.error) : t('te.rem.stale')}
          </AlertBanner>
        </div>
      )}
    </>
  )

  if (!saved || !form) {
    return (
      <div data-slot="tes-reminders" data-state={state.status} className="min-w-0">
        <SettingsSection title={t('te.rem.title')} description={t('te.rem.desc')} contentClassName="flex flex-col gap-4">
          {notes}
          {state.status === 'failed' && (
            <div data-slot="tes-rem-unreadable">
              <StatusBlock tone="danger" role="alert" icon={CircleAlert} title={t('te.rem.unreadable')}
                description={state.error ? String(state.error) : t('te.rem.unreadableBody')} className="py-6" actions={retryButton} />
            </div>
          )}
        </SettingsSection>
      </div>
    )
  }

  const numberField = (key, labelKey, hintKey) => {
    const l = limits?.[key] || { min: 0, max: 99 }
    return (
      <Field label={t(labelKey)} hint={t(hintKey, l.min, l.max)} className="mb-0 min-w-0" {...fe.fieldProps(key)}>
        {({ id, describedBy, invalid }) => (
          <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} type="number" inputMode="numeric"
            min={l.min} max={l.max} step={1} value={form[key]} disabled={locked || saving}
            onChange={(e) => set(key, e.target.value)} data-slot={`tes-rem-${key.replace(/_/g, '-')}`}
            className="h-10 w-full max-w-48" />
        )}
      </Field>
    )
  }

  return (
    <div data-slot="tes-reminders" data-state={state.status} className="min-w-0">
      <SettingsSection title={t('te.rem.title')} description={t('te.rem.desc')} contentClassName="@container flex flex-col gap-5">
        {notes}
        {/* 1 — Dönem hatırlatması */}
        <section data-slot="tes-rem-period" aria-labelledby="tes-rem-period-h" className="flex min-w-0 flex-col gap-4">
          <h5 id="tes-rem-period-h" className="m-0 flex items-center gap-2 text-sm font-semibold">
            <BellRing aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> {t('te.rem.groupPeriod')}
          </h5>
          <div data-touch-picker="" className="min-w-0">
            <Field label={t('te.rem.teams')} hint={t('te.rem.teamsHint')} className="mb-0" {...fe.fieldProps('reminder_team_ids')}>
              {({ id }) => (
                <MultiTeamSelect id={id} value={form.team_ids} options={options} disabled={locked || saving}
                  ariaLabel={t('te.rem.teams')} placeholder={t('te.set.selectTeams')}
                  onChange={(ids) => set('team_ids', normalizeIds(ids))} />
              )}
            </Field>
          </div>
          {form.team_ids.length === 0 && (
            <p className="m-0 text-sm text-muted-foreground" data-slot="tes-rem-off">{t('te.rem.off')}</p>
          )}
          <div className="grid grid-cols-1 gap-4 @md:grid-cols-2">
            {numberField('reminder_day', 'te.rem.day', 'te.rem.dayHint')}
            {numberField('reminder_repeat_days', 'te.rem.repeat', 'te.rem.repeatHint')}
          </div>
          <div className="flex min-w-0 flex-col gap-1" data-slot="tes-rem-push">
            <CheckboxRow checked={!!form.reminder_push} onChange={(v) => set('reminder_push', v)} disabled={locked || saving}
              label={t('te.rem.push')} className="pointer-coarse:min-h-10" />
            <p className="m-0 pl-6 text-xs text-muted-foreground">{t('te.rem.pushHint')}</p>
          </div>
        </section>

        <Separator />

        {/* 2 — Onay hatırlatması */}
        <section data-slot="tes-rem-approval" aria-labelledby="tes-rem-approval-h" className="flex min-w-0 flex-col gap-4">
          <h5 id="tes-rem-approval-h" className="m-0 flex items-center gap-2 text-sm font-semibold">
            <Stamp aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> {t('te.rem.groupApproval')}
          </h5>
          <div className="grid grid-cols-1 gap-4 @md:grid-cols-2">
            {numberField('approval_reminder_days', 'te.rem.approval', 'te.rem.approvalHint')}
          </div>
        </section>

        <Separator />

        {/* 3 — E-posta sınırları */}
        <section data-slot="tes-rem-mail" aria-labelledby="tes-rem-mail-h" className="flex min-w-0 flex-col gap-4">
          <h5 id="tes-rem-mail-h" className="m-0 flex items-center gap-2 text-sm font-semibold">
            <Mail aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /> {t('te.rem.groupMail')}
          </h5>
          <div className="grid grid-cols-1 gap-4 @md:grid-cols-2">
            {numberField('max_mail_mb', 'te.rem.maxMail', 'te.rem.maxMailHint')}
            {numberField('mail_inline_mb', 'te.rem.inline', 'te.rem.inlineHint')}
          </div>
        </section>

        {!locked && (
          <div data-slot="tes-rem-save">
            <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={discard} />
          </div>
        )}
      </SettingsSection>
    </div>
  )
}

/** Yanıt bloğu gelmezse gönderilen değerler kayıtlı sayılır. */
function appliedValues(body) {
  const out = { ...body }
  if (body.reminder_team_ids) {
    out.team_ids = body.reminder_team_ids
    delete out.reminder_team_ids
  }
  return out
}
