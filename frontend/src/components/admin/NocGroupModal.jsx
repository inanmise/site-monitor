import { useEffect, useState } from 'react'
import { Headset, Save } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import EmailChipsInput from '../noc/EmailChipsInput.jsx'
import { MAX_EMAILS, MAX_GROUP_DESC, MAX_GROUP_NAME, unwrap, validateGroup } from '../noc/nocModel.js'
import { NocSwitchRow } from '../noc/nocUi.jsx'
import { helpLabel } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'

/**
 * 7/24 grubu ekle/düzenle penceresi (Ayarlar → 7/24 İzleme Ekibi, 2026-09-27). ModalShell (Dialog): ad, açıklama,
 * çoklu e-posta çip girişi (yapıştır → doğrula → tekilleştir; geçersiz adres başına hata), Aktif ve Varsayılan
 * anahtarları. Kaydet düğmesi alt çubukta (uzun listede de görünür, `scrollBody`). Emek biriktiren form: örtüye
 * basmak kapatmaz. Sunucu hatası pencereyi KAPATMAZ (yazılanlar kaybolmasın).
 *
 * Yardım anahtarları bu dosyada LİTERAL — `settings-help-coverage` kapısı `components/admin` altını tarar.
 */
const EMPTY = { name: '', description: '', emails: [], invalid: [], active: true, isDefault: false }

function fromGroup(g) {
  if (!g) return { ...EMPTY }
  return {
    name: g.name || '', description: g.description || '', emails: Array.isArray(g.emails) ? [...g.emails] : [],
    invalid: [], active: g.active !== false, isDefault: !!g.is_default,
  }
}

export default function NocGroupModal({ open, group = null, takenNames = [], onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const [form, setForm] = useState(() => fromGroup(group))
  const [errors, setErrors] = useState({})
  const [saving, setSaving] = useState(false)

  // Pencere her açılışta düzenlenen grubun GÜNCEL hâliyle başlar.
  useEffect(() => { if (open) { setForm(fromGroup(group)); setErrors({}) } }, [open, group])

  const set = (patch) => setForm((f) => ({ ...f, ...patch }))
  const editing = group?.id != null
  // Hata anahtarı → metin (sayı yer tutucusu anahtara göre; i18n yer tutucuları {0}'dan ardışık)
  const emailError = errors.emails === 'noc.errInvalidPending' ? t('noc.errInvalidPending', form.invalid.length)
    : errors.emails === 'noc.errTooMany' ? t('noc.errTooMany', MAX_EMAILS)
      : errors.emails ? t(errors.emails) : null

  async function save() {
    const errs = validateGroup(form, takenNames)
    setErrors(errs)
    if (Object.keys(errs).length) return
    setSaving(true)
    try {
      const body = {
        name: form.name.trim(), description: form.description.trim(), emails: form.emails,
        active: !!form.active, isDefault: !!form.isDefault,
      }
      const res = editing ? await api.admin.noc.updateGroup(group.id, body) : await api.admin.noc.createGroup(body)
      const r = unwrap(res)
      if (!r.ok) { toast.error(r.error || t('noc.gSaveError')); return }
      toast.success(editing ? t('noc.gUpdatedToast', body.name) : t('noc.gCreatedToast', body.name))
      onSaved?.(r.data)
    } catch (e) {
      toast.error(e?.message || t('noc.gSaveError'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <ModalShell open={open} onClose={onClose} busy={saving} size="md" scrollBody dismissOnBackdrop={false} icon={Headset}
      title={editing ? t('noc.gEditTitle', group.name) : t('noc.gNewTitle')}
      footer={(
        <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button type="button" variant="outline" className="h-10 sm:h-9 sm:pointer-coarse:h-10" onClick={onClose} disabled={saving}>{t('app.cancel')}</Button>
          <Button type="button" className="h-10 sm:h-9 sm:pointer-coarse:h-10" onClick={save} disabled={saving} aria-busy={saving || undefined} data-action="noc-group-save">
            {saving ? <Spinner size={15} inline decorative /> : <Save aria-hidden="true" />}{saving ? t('settings.saving') : t('noc.gSave')}
          </Button>
        </div>
      )}>
      <div className="flex min-w-0 flex-col gap-1 pt-1">
        <Field label={t('noc.gName')} required error={errors.name ? t(errors.name, MAX_GROUP_NAME) : null}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid}
              className="h-10 sm:h-9 sm:pointer-coarse:h-10" value={form.name} maxLength={MAX_GROUP_NAME} autoComplete="off" placeholder={t('noc.gNamePh')}
              onChange={(e) => set({ name: e.target.value })} />
          )}
        </Field>
        <Field label={t('noc.gDescription')} hint={t('noc.gDescriptionHint')}>
          {({ id, describedBy }) => (
            <Input id={id} aria-describedby={describedBy} className="h-10 sm:h-9 sm:pointer-coarse:h-10" value={form.description} maxLength={MAX_GROUP_DESC}
              placeholder={t('noc.gDescriptionPh')} onChange={(e) => set({ description: e.target.value })} />
          )}
        </Field>
        <Field label={helpLabel(t('noc.gEmails'), 'help.noc.groupEmails')} required hint={t('noc.gEmailsHint')}
          error={emailError}>
          {({ id, describedBy, invalid }) => (
            <EmailChipsInput id={id} describedBy={describedBy} ariaInvalid={invalid} emails={form.emails} invalid={form.invalid}
              onChange={({ emails, invalid: bad }) => {
                set({ emails, invalid: bad })
                // Hata metni kullanıcı düzelttikçe tazelensin (ör. son geçersiz adres kaldırılınca kaybolsun)
                if (errors.emails) setErrors((e) => ({ ...e, emails: undefined }))
              }} />
          )}
        </Field>
        <div className="flex flex-col gap-3">
          <NocSwitchRow checked={!!form.active} onChange={(v) => set({ active: v })} label={t('noc.gActiveLabel')}
            helpKey="help.noc.groupActive" hint={t('noc.gActiveHint')} />
          <NocSwitchRow checked={!!form.isDefault} onChange={(v) => set({ isDefault: v })} label={t('noc.gDefaultLabel')}
            helpKey="help.noc.groupDefault" hint={t('noc.gDefaultHint')} />
          {form.isDefault && !form.active && (
            <AlertBanner tone="warning" className="mb-0">{t('noc.gDefaultInactiveWarn')}</AlertBanner>
          )}
        </div>
      </div>
    </ModalShell>
  )
}
