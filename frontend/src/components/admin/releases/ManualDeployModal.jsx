import { useEffect, useRef, useState } from 'react'
import { PenLine } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import Field from '../../ui/Field.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { NOTE_MAX, manualBody, validateManual } from './releaseModel.js'
import { Button } from '@/components/shadcn/button'
import { FieldLegend, FieldSet } from '@/components/shadcn/field'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'

/**
 * Elle dağıtım kaydı (K8) — yalnız `canEdit`. ModalShell + ui/Field; iki bölüm ("Dağıtım" / "İzlenebilirlik"),
 * alan hataları gönderimde (ya da alandan çıkınca) — sunucu kuralıyla aynı doğrulama (`validateManual`).
 * Sunucu hatası pencerenin üstünde AlertBanner; başarıda toast + yenileme. Emek biriktiren form: örtü tıklaması
 * kapatmaz. Uçta düzenleme (PUT) YOK — kayıt yalnız eklenir/silinir.
 */
const EMPTY = { environment: '', version: '', at: '', commit: '', helm: '', note: '' }

export default function ManualDeployModal({ open, onClose, onSaved, defaultEnv = '', environments = [] }) {
  const t = useT()
  const toast = useToast()
  const [form, setForm] = useState(EMPTY)
  const [touched, setTouched] = useState({})
  const [submitted, setSubmitted] = useState(false)
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState(null)

  // Sıfırlama YALNIZ açılışta (kapalı → açık) — 2026-09-27 regresyon B4: `defaultEnv` pencere AÇIKKEN değişince
  // (ortam/sürüm verisi sonradan gelince ya da yenilenince) form BÜTÜNÜYLE sıfırlanıyor, yazılanlar gidiyordu.
  const wasOpen = useRef(false)
  useEffect(() => {
    if (open && !wasOpen.current) {
      setForm({ ...EMPTY, environment: defaultEnv || '' })
      setTouched({}); setSubmitted(false); setErr(null)
    }
    wasOpen.current = open
  }, [open])   // eslint-disable-line react-hooks/exhaustive-deps
  // Varsayılan ortam açılıştan SONRA gelirse yalnız BOŞ ortam alanını doldurur; kullanıcının yazdığına dokunmaz.
  useEffect(() => {
    if (!open || !defaultEnv) return
    setForm((p) => (p.environment ? p : { ...p, environment: defaultEnv }))
  }, [open, defaultEnv])

  const errors = validateManual(form)
  const show = (k) => ((submitted || touched[k]) && errors[k] ? t(errors[k]) : undefined)
  const set = (k, v) => setForm((p) => ({ ...p, [k]: v }))
  const blur = (k) => () => setTouched((p) => ({ ...p, [k]: true }))

  async function save() {
    setSubmitted(true)
    if (Object.keys(errors).length) return
    setSaving(true); setErr(null)
    try {
      const res = await api.admin.createDeployment(manualBody(form))
      if (res?.success) { toast.success(t('deploy.saved')); onSaved?.(); onClose() }
      else setErr(res?.error || t('deploy.saveError'))
    } catch (e) {
      setErr(e?.message || String(e))
    } finally {
      setSaving(false)
    }
  }

  const envHint = environments.length ? t('deploy.envHint', environments.join(', ')) : t('deploy.envFormat')

  return (
    <ModalShell open={open} onClose={onClose} title={t('deploy.manualTitle')} icon={PenLine} size="md" busy={saving}
      scrollBody dismissOnBackdrop={false}
      footer={<>
        <Button type="button" variant="outline" className="h-10 sm:h-9" onClick={onClose} disabled={saving}>{t('deploy.cancel')}</Button>
        <Button type="button" className="h-10 sm:h-9" onClick={save} disabled={saving} aria-busy={saving || undefined}>
          {saving && <Spinner decorative size={14} />}{t('deploy.save')}
        </Button>
      </>}>
      <p className="mb-3 text-xs text-muted-foreground">{t('deploy.manualHint')}</p>
      {err && <AlertBanner tone="danger" role="alert" title={t('deploy.saveError')}>{String(err)}</AlertBanner>}

      <FieldSet className="mb-2 gap-3">
        <FieldLegend variant="label" className="mb-0 text-xs font-bold tracking-wide text-muted-foreground uppercase data-[variant=label]:text-xs">{t('deploy.manual.secWhat')}</FieldLegend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Field label={t('deploy.manualEnv')} required hint={envHint} error={show('environment')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={form.environment} placeholder="prod"
                autoCapitalize="none" autoCorrect="off" spellCheck={false} maxLength={40}
                onChange={(e) => set('environment', e.target.value.toLowerCase())} onBlur={blur('environment')} />
            )}
          </Field>
          <Field label={t('deploy.manualVersion')} required hint={t('deploy.versionHint')} error={show('version')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} className="font-mono" value={form.version}
                placeholder="20.53.2" inputMode="decimal" autoCapitalize="none" spellCheck={false}
                onChange={(e) => set('version', e.target.value)} onBlur={blur('version')} />
            )}
          </Field>
        </div>
        <Field label={t('deploy.manualAt')} required hint={t('deploy.atHint')} error={show('at')} className="mb-0">
          {() => (
            <DateTimeField value={form.at} placeholder={t('deploy.manualAt')}
              onChange={(v) => { set('at', v || ''); setTouched((p) => ({ ...p, at: true })) }} />
          )}
        </Field>
      </FieldSet>

      <FieldSet className="mt-4 gap-3">
        <FieldLegend variant="label" className="mb-0 text-xs font-bold tracking-wide text-muted-foreground uppercase data-[variant=label]:text-xs">{t('deploy.manual.secTrace')}</FieldLegend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
          <Field label={t('deploy.manualCommit')} error={show('commit')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} className="font-mono" value={form.commit}
                maxLength={40} autoCapitalize="none" spellCheck={false}
                onChange={(e) => set('commit', e.target.value.trim())} onBlur={blur('commit')} />
            )}
          </Field>
          <Field label={t('deploy.manualHelm')} error={show('helm')} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} className="font-mono" value={form.helm}
                inputMode="numeric" maxLength={9}
                onChange={(e) => set('helm', e.target.value.replace(/[^0-9]/g, ''))} onBlur={blur('helm')} />
            )}
          </Field>
        </div>
        <Field label={t('deploy.manualNote')} required hint={t('deploy.noteCount', form.note.trim().length, NOTE_MAX)} error={show('note')} className="mb-0">
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} rows={3} maxLength={NOTE_MAX}
              value={form.note} placeholder={t('deploy.notePlaceholder')}
              onChange={(e) => set('note', e.target.value)} onBlur={blur('note')} />
          )}
        </Field>
      </FieldSet>
    </ModalShell>
  )
}
