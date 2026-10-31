import { useEffect, useState } from 'react'
import { BookmarkPlus } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { DbTypeBadge } from './dbqueryParts.jsx'
import { serverFieldErrors } from './connectionModel.js'
import { ruleSummary } from './ruleModel.js'

/**
 * "Şablon olarak kaydet" (Veritabanı Sorgu İzleme, 2026-10-31) — formdaki sorgu + amaç + kurallar takımın şablon
 * kütüphanesine. Ad zorunlu; 409 ad çakışması ve takım sınırı (`TEMPLATE_LIBRARY_LIMIT`) alanın altında / bantta; sorgu
 * reddi (400 DBQUERY_SQL_REJECTED) ihlalleriyle bantta. Neyin kaydedileceği pencerede özetlenir (bağlantı / parola
 * KAYDEDİLMEZ — şablon takım belgesidir).
 */
export default function SaveTemplateDialog({ open, onOpenChange, teamId, draft, onSaved }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [errors, setErrors] = useState({})
  const [banner, setBanner] = useState(null)
  const [saving, setSaving] = useState(false)
  const rules = Array.isArray(draft?.rules) ? draft.rules : []

  useEffect(() => {
    if (!open) return
    setName(''); setDescription(''); setErrors({}); setBanner(null)
  }, [open])

  const close = () => { if (!saving) onOpenChange?.(false) }

  const save = async () => {
    setBanner(null)
    if (!name.trim()) { setErrors({ name: t('dbq.tpl.v.nameRequired') }); return }
    if (!String(draft?.sql_text || '').trim()) { setBanner({ title: t('dbq.tpl.saveFailedTitle'), body: t('dbq.tpl.v.sqlRequired') }); return }
    setSaving(true)
    const body = {
      team_id: teamId, name: name.trim(), description: description.trim(), db_type: draft?.db_type ?? null,
      sql_text: draft?.sql_text ?? '', purpose: draft?.purpose ?? '', rules, rule_logic: draft?.rule_logic === 'ALL' ? 'ALL' : 'ANY',
    }
    let res
    try { res = await api.monitoring.createDbQueryTemplate(body) } catch (e) { res = { success: false, error: e?.message } }
    setSaving(false)
    if (res?.success) {
      toast.success(t('dbq.tpl.saved', body.name))
      onSaved?.(res.data || null)
      onOpenChange?.(false)
      return
    }
    const fe = serverFieldErrors(res)
    if (res?.code === 'DUPLICATE_RECORD' && !fe.name) fe.name = res.error || t('dbq.tpl.v.nameTaken')
    setErrors(fe)
    if (!fe.name && !fe.description) {
      const list = Array.isArray(res?.violations) ? res.violations : []
      setBanner({
        title: res?.code === 'TEMPLATE_LIBRARY_LIMIT' ? t('dbq.tpl.limitTitle') : t('dbq.tpl.saveFailedTitle'),
        body: res?.error || res?.message || t('dbq.tpl.saveFailedBody'),
        list: list.map((v) => v.message || v.code),
      })
    }
  }

  return (
    <ModalShell open={!!open} onClose={close} busy={saving} size="md" scrollBody icon={BookmarkPlus} title={t('dbq.tpl.saveTitle')}
      dismissOnBackdrop={false}
      footer={(
        <div className="flex w-full flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button type="button" variant="outline" onClick={close} disabled={saving} className="pointer-coarse:h-10">{t('dbq.tpl.cancel')}</Button>
          <Button type="button" onClick={save} disabled={saving} aria-busy={saving || undefined} className="pointer-coarse:h-10" data-slot="dbq-tpl-save">
            {saving && <Spinner size={14} inline decorative />}{t('dbq.tpl.save')}
          </Button>
        </div>
      )}>
      <div data-slot="dbq-tpl-save-dialog" className="flex min-w-0 flex-col gap-1">
        {banner && (
          <AlertBanner tone="danger" title={banner.title}>
            <p className="m-0">{banner.body}</p>
            {banner.list?.length > 0 && <ul className="mt-1 mb-0 list-disc pl-5">{banner.list.map((m, i) => <li key={i}>{m}</li>)}</ul>}
          </AlertBanner>
        )}
        <Field label={t('dbq.tpl.name')} required name="name" error={errors.name}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} value={name} disabled={saving} className="pointer-coarse:h-10"
              onChange={(e) => { setName(e.target.value); setErrors((s) => ({ ...s, name: undefined })) }} autoComplete="off" />
          )}
        </Field>
        <Field label={t('dbq.tpl.description')} name="description" error={errors.description}>
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} rows={2} value={description} disabled={saving}
              onChange={(e) => setDescription(e.target.value)} />
          )}
        </Field>
        <div className="flex flex-col gap-1.5 rounded-md border bg-muted/30 p-3">
          <span className="text-xs font-semibold text-muted-foreground">{t('dbq.tpl.willSave')}</span>
          <div className="flex flex-wrap items-center gap-1.5">
            {draft?.db_type ? <DbTypeBadge type={draft.db_type} /> : <span className="text-xs text-muted-foreground">{t('dbq.tpl.noDbType')}</span>}
            <span className="text-xs text-muted-foreground">{t('dbq.tpl.ruleCount', rules.length)}</span>
          </div>
          {rules.length > 0 && (
            <ul className="m-0 list-disc pl-5 text-xs">{rules.map((r, i) => <li key={r.id || i} className="[overflow-wrap:anywhere]">{ruleSummary(r, t, lang)}</li>)}</ul>
          )}
          <p className="m-0 text-xs text-muted-foreground">{t('dbq.tpl.notSaved')}</p>
        </div>
      </div>
    </ModalShell>
  )
}
