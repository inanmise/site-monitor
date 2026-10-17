import { useId, useMemo, useState } from 'react'
import { Copy, Globe, LayoutTemplate } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import Field from '../ui/Field.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { RadioGroup, RadioGroupItem } from '@/components/shadcn/radio-group'
import { FieldContent, FieldDescription, FieldLabel, FieldTitle, Field as ShField } from '@/components/shadcn/field'
import { cn } from '@/lib/utils'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'
import { LIMITS } from './techErrorModel.js'

/** Sunucuyla aynı teslim edilebilirlik kuralı (TechErrorReportSender.DELIVERABLE). */
const EMAIL_RE = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]{2,}$/

/**
 * Kanal aç / düzenle. Açarken şablon üç yoldan gelir: varsayılan (Türkçe), varsayılan (İngilizce) ya da görülebilen
 * başka bir kanalın şablonunun KOPYASI — seçim, erişim paylaşımı penceresiyle aynı shadcn "seçim kartları" (RadioGroup +
 * Field; ikon, başlık, açıklama; 2026-10-17, kullanıcı isteği: düz radyo düğmeleri proje geneline uymuyordu). Düzenleme
 * yalnız değişen alanları gönderir (ad / açıklama).
 */
export default function ChannelDialog({ mode, channel = null, teams = [], channels = [], defaultTeam = null, onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const editable = teams.filter((tm) => tm.can_edit && tm.active !== false)
  const [teamId, setTeamId] = useState(() => {
    if (mode === 'edit') return String(channel?.team_id ?? '')
    const pref = editable.find((tm) => String(tm.id) === String(defaultTeam))
    return String(pref?.id ?? editable[0]?.id ?? '')
  })
  const [name, setName] = useState(channel?.name || '')
  const [description, setDescription] = useState(channel?.description || '')
  // Yönetici (executive) alıcıları (2026-10-17, S6): yalnız takımı yönetenler yazar; doluysa gönderim PO onayına bağlı
  const [execEmails, setExecEmails] = useState(channel?.executive_emails || '')
  const [source, setSource] = useState('tr')
  const [copyFrom, setCopyFrom] = useState('')
  const sourceLabelId = useId()
  const [errors, setErrors] = useState({})
  const [busy, setBusy] = useState(false)

  const teamOptions = useMemo(() => editable.map((tm) => ({ value: String(tm.id), label: tm.name || `#${tm.id}` })), [editable])
  const canManage = mode === 'edit' ? !!channel?.can_manage : !!editable.find((tm) => String(tm.id) === String(teamId))?.can_manage
  const copyOptions = useMemo(() => channels.map((c) => ({
    value: String(c.id), label: c.team_name ? `${c.name} — ${c.team_name}` : c.name,
  })), [channels])

  function validate() {
    const e = {}
    if (mode === 'create' && !teamId) e.team_id = t('te.err.teamRequired')
    if (!name.trim()) e.name = t('te.err.nameRequired')
    else if (name.trim().length > LIMITS.channelName) e.name = t('te.err.nameTooLong')
    if (description.trim().length > LIMITS.description) e.description = t('te.err.descriptionTooLong')
    if (mode === 'create' && source === 'copy' && !copyFrom) e.copy_from = t('te.err.copyRequired')
    if (canManage && execEmails.trim()) {
      const parts = execEmails.split(/[,;]/).map((p) => p.trim()).filter(Boolean)
      const bad = parts.find((p) => !EMAIL_RE.test(p))
      if (bad) e.executive_emails = t('te.err.execEmailInvalid', bad)
      else if (parts.length > 20) e.executive_emails = t('te.err.execEmailsTooMany')
    }
    setErrors(e)
    return Object.keys(e).length === 0
  }

  async function submit(ev) {
    ev?.preventDefault?.()
    if (busy || !validate()) return
    setBusy(true)
    try {
      let res
      if (mode === 'create') {
        const body = { team_id: Number(teamId), name: name.trim(), description: description.trim() || null }
        if (source === 'copy') body.copy_from = Number(copyFrom)
        else body.lang = source
        if (canManage && execEmails.trim()) body.executive_emails = execEmails.trim()
        res = await api.techErrors.createChannel(body)
      } else {
        const body = {}
        if (name.trim() !== (channel?.name || '')) body.name = name.trim()
        if ((description.trim() || '') !== (channel?.description || '')) body.description = description.trim() || null
        if (canManage && execEmails.trim() !== (channel?.executive_emails || '')) body.executive_emails = execEmails.trim() || null
        if (Object.keys(body).length === 0) { onClose(); return }
        res = await api.techErrors.updateChannel(channel.id, body)
      }
      if (res?.success) {
        toast.success(mode === 'create' ? t('te.channelCreated', name.trim()) : t('te.channelSaved'))
        onSaved(res.data)
        return
      }
      if (res?.code === 'CHANNEL_EXISTS') setErrors({ name: res.error })
      else if (res?.field && ['name', 'description', 'team_id', 'copy_from', 'executive_emails'].includes(res.field)) setErrors({ [res.field]: res.error })
      else toast.error(res?.error || t('te.saveError'), { details: res?.errorInfo })
    } catch (e) {
      toast.error(e?.message || t('te.saveError'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o && !busy) onClose() }}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-xl" data-slot="te-channel-dialog">
        <form onSubmit={submit} noValidate className="flex flex-col gap-1">
          <DialogHeader className="mb-3">
            <DialogTitle>{mode === 'create' ? t('te.newChannel') : t('te.editChannel')}</DialogTitle>
            <DialogDescription>{mode === 'create' ? t('te.newChannelHint') : t('te.editChannelHint')}</DialogDescription>
          </DialogHeader>

          {mode === 'create' ? (
            <Field label={t('te.field.team')} required error={errors.team_id} name="team_id">
              {({ id }) => (
                <SearchableSelect id={id} value={teamId} options={teamOptions} wrapLabels ariaLabel={t('te.field.team')}
                  onChange={(v) => { setTeamId(String(v)); setErrors((x) => ({ ...x, team_id: undefined })) }} />
              )}
            </Field>
          ) : (
            <p className="mb-3 text-sm text-muted-foreground [overflow-wrap:anywhere]">
              {t('te.field.team')}: <span className="font-medium text-foreground">{channel?.team_name || '—'}</span>
            </p>
          )}

          <Field label={t('te.field.channelName')} required error={errors.name} name="name" hint={t('te.field.channelNameHint')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} className="pointer-coarse:h-10" value={name} maxLength={LIMITS.channelName + 20} aria-describedby={describedBy} aria-invalid={invalid}
                autoFocus onChange={(e) => { setName(e.target.value); setErrors((x) => ({ ...x, name: undefined })) }} />
            )}
          </Field>

          <Field label={t('te.field.description')} error={errors.description} name="description">
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} value={description} rows={2} aria-describedby={describedBy} aria-invalid={invalid}
                onChange={(e) => { setDescription(e.target.value); setErrors((x) => ({ ...x, description: undefined })) }} />
            )}
          </Field>

          {canManage ? (
            <Field label={t('te.ch.execEmails')} hint={t('te.ch.execEmailsHint')} error={errors.executive_emails} name="executive_emails">
              {({ id, describedBy, invalid }) => (
                <Textarea id={id} value={execEmails} rows={2} aria-describedby={describedBy} aria-invalid={invalid}
                  placeholder={t('te.ch.execPlaceholder')}
                  onChange={(e) => { setExecEmails(e.target.value); setErrors((x) => ({ ...x, executive_emails: undefined })) }} />
              )}
            </Field>
          ) : mode === 'edit' && channel?.executive_emails ? (
            <p className="mb-3 text-sm text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-exec-locked">
              {t('te.ch.execEmails')}: <span className="font-medium text-foreground">{channel.executive_emails}</span> · {t('te.ch.execEmailsLocked')}
            </p>
          ) : null}

          {mode === 'create' && (
            <div className="mb-3 flex min-w-0 flex-col gap-2" data-field="copy_from" data-slot="te-source">
              <p id={sourceLabelId} className="m-0 text-sm font-semibold">{t('te.field.templateSource')}</p>
              <RadioGroup value={source} onValueChange={(v) => { setSource(v); setErrors((x) => ({ ...x, copy_from: undefined })) }}
                aria-labelledby={sourceLabelId} className="grid grid-cols-1 gap-2 sm:grid-cols-2" data-slot="te-source-options">
                {[
                  { value: 'tr', Icon: LayoutTemplate, title: t('te.source.tr'), hint: t('te.source.trHint') },
                  { value: 'en', Icon: Globe, title: t('te.source.en'), hint: t('te.source.enHint') },
                  ...(copyOptions.length ? [{ value: 'copy', Icon: Copy, title: t('te.source.copy'), hint: t('te.source.copyHint') }] : []),
                ].map((o) => {
                  const rid = `${sourceLabelId}-${o.value}`
                  return (
                    <FieldLabel key={o.value} htmlFor={rid} className={cn('w-full cursor-pointer', o.value === 'copy' && 'sm:col-span-2')}>
                      <ShField orientation="horizontal" data-source={o.value} className="min-h-10 items-start gap-2 px-3! py-2!">
                        <RadioGroupItem value={o.value} id={rid} className="mt-0.5" />
                        <FieldContent className="gap-0.5">
                          <FieldTitle className="flex items-center gap-1.5 text-sm">
                            <o.Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />{o.title}
                          </FieldTitle>
                          <FieldDescription className="m-0 text-xs leading-snug">{o.hint}</FieldDescription>
                        </FieldContent>
                      </ShField>
                    </FieldLabel>
                  )
                })}
              </RadioGroup>
              {source === 'copy' && (
                <div className="mt-1 flex flex-col gap-1.5" data-slot="te-source-copy">
                  <SearchableSelect value={copyFrom} options={copyOptions} wrapLabels ariaLabel={t('te.field.copyFrom')}
                    placeholder={t('te.field.copyFromPlaceholder')}
                    onChange={(v) => { setCopyFrom(String(v)); setErrors((x) => ({ ...x, copy_from: undefined })) }} />
                  {errors.copy_from && <p className="m-0 text-sm text-destructive" role="alert">{errors.copy_from}</p>}
                </div>
              )}
            </div>
          )}

          <DialogFooter className="mt-2 flex-col-reverse gap-2 sm:flex-row">
            <Button type="button" variant="outline" onClick={onClose} disabled={busy} className="pointer-coarse:min-h-10">{t('te.cancel')}</Button>
            <Button type="submit" disabled={busy} data-slot="te-channel-save" className="pointer-coarse:min-h-10">
              {mode === 'create' ? t('te.createChannel') : t('te.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
