import { useEffect, useMemo, useRef, useState } from 'react'
import { BookmarkPlus, RefreshCw } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import Field, { FieldError } from '../../ui/Field.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import TagInput from '../../ui/TagInput.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { Textarea } from '@/components/shadcn/textarea'
import {
  SAVED_DESC_MAX, SAVED_TAGS_MAX, SAVED_TAG_LEN, SAVED_TITLE_MAX, normalizeTags, savedBody, savedUpdateBody, validateSaved,
} from './sqlLibraryModel.js'

const CLIENT_ERR = {
  required: 'sql.saved.err.titleRequired', tooLong: 'sql.saved.err.tooLong', tooMany: 'sql.saved.err.tagsTooMany',
  tagTooLong: 'sql.saved.err.tagTooLong', sqlRequired: 'sql.saved.err.sqlRequired', sqlTooLong: 'sql.saved.err.sqlTooLong',
}

/**
 * Sorgu kaydet / düzenle (2026-11-13, D1). Yeni: başlık (≤ 120, sahip içinde benzersiz), açıklama, etiketler (≤ 10 × 30),
 * paylaşım, SQL (sekmeden) + parametre TÜRLERİ (değer asla). Düzenleme: yalnız DEĞİŞEN alanlar + `version`; değişiklik yoksa
 * istek gönderilmez. 409 `CONCURRENT_MODIFICATION` → "başkası değiştirdi" bandı + güncel sürümü yükle (girilenler korunur);
 * 409 `SQL_SAVED_TITLE_EXISTS` başlığın altında; 400 `sql_text` (salt-okunur / hassas kolon kuralı) bantta; `SQL_SAVED_LIMIT` bantta.
 *
 * @param mode 'create' | 'update'
 * @param base güncellenen kayıt (update) — `{ id, title, description, tags, shared, sql_text, version }`
 */
export default function SaveQueryDialog({ open, mode = 'create', base, sql, paramTypes, onClose, onSaved }) {
  const t = useT()
  const toast = useToast()
  const [form, setForm] = useState({ title: '', description: '', tags: '', shared: false })
  const [errors, setErrors] = useState({})
  const [banner, setBanner] = useState(null)   // { tone, text, conflict? }
  const [saving, setSaving] = useState(false)
  const [current, setCurrent] = useState(base || null)
  const titleRef = useRef(null)

  useEffect(() => {
    if (!open) return
    setCurrent(base || null)
    setForm(mode === 'update' && base
      ? { title: base.title ?? '', description: base.description ?? '', tags: (base.tags || []).join(', '), shared: Boolean(base.shared) }
      : { title: '', description: '', tags: '', shared: false })
    setErrors({})
    setBanner(null)
  }, [open, mode, base])

  const formFull = useMemo(() => ({ ...form, tags: normalizeTags(form.tags), sql_text: sql ?? '' }), [form, sql])
  const set = (k, v) => { setForm((f) => ({ ...f, [k]: v })); setErrors((e) => (e[k] ? { ...e, [k]: undefined } : e)) }
  const errText = (k) => {
    const e = errors[k]
    if (!e) return undefined
    return CLIENT_ERR[e] ? t(CLIENT_ERR[e], k === 'title' ? SAVED_TITLE_MAX : k === 'description' ? SAVED_DESC_MAX : k === 'tags' ? SAVED_TAGS_MAX : SAVED_TAG_LEN) : e
  }

  const reloadServer = async () => {
    if (!current?.id) return
    const r = await api.admin.sqlSavedGet(current.id)
    if (r?.success) {
      const row = r.data ?? r
      setCurrent(row)
      setBanner({ tone: 'info', text: t('sql.saved.reloaded') })
    } else setBanner({ tone: 'danger', text: r?.error || t('sql.saved.loadError') })
  }

  const save = async () => {
    const v = validateSaved(formFull)
    if (v) {
      setErrors(v)
      if (v.sql_text) setBanner({ tone: 'danger', text: t(CLIENT_ERR[v.sql_text], 100000) })
      return
    }
    setSaving(true)
    setBanner(null)
    try {
      let r
      if (mode === 'update' && current?.id != null) {
        const body = savedUpdateBody(formFull, current, paramTypes)
        if (!body) { toast.info(t('sql.saved.noChange')); onClose?.(); return }
        r = await api.admin.sqlSavedUpdate(current.id, body)
      } else {
        r = await api.admin.sqlSavedCreate(savedBody(formFull, paramTypes))
      }
      if (r?.success) {
        const row = r.data ?? r.saved ?? null
        toast.success(mode === 'update' ? t('sql.saved.updated') : t('sql.saved.created'))
        onSaved?.(row && typeof row === 'object' ? row : null)
        onClose?.()
        return
      }
      const code = r?.code ?? r?.errorInfo?.code
      const field = r?.field || (r?.fields ? Object.keys(r.fields)[0] : null)
      if (code === 'CONCURRENT_MODIFICATION') setBanner({ tone: 'warning', text: t('sql.saved.conflict'), conflict: true })
      else if (code === 'SQL_SAVED_TITLE_EXISTS' || field === 'title') { setErrors((e) => ({ ...e, title: r.error || t('sql.saved.titleExists') })); titleRef.current?.focus() }
      else if (field && ['description', 'tags'].includes(field)) setErrors((e) => ({ ...e, [field]: r.error }))
      else if (r?.status === 404 || r?.status === 405) setBanner({ tone: 'danger', text: t('sql.saved.unsupported') })
      else setBanner({ tone: 'danger', text: r?.error || t('sql.saved.saveError') })
    } catch (e) {
      setBanner({ tone: 'danger', text: e?.message || t('sql.saved.saveError') })
    } finally {
      setSaving(false)
    }
  }

  const footer = (
    <div className="flex w-full flex-wrap justify-end gap-2 [&>*]:flex-1 sm:[&>*]:flex-none">
      <Button type="button" variant="outline" onClick={onClose} disabled={saving}>{t('sql.saved.cancel')}</Button>
      <Button type="button" onClick={save} disabled={saving} aria-busy={saving || undefined} data-slot="sql-save-submit">
        <BookmarkPlus /> {mode === 'update' ? t('sql.saved.saveChanges') : t('sql.saved.save')}
      </Button>
    </div>
  )

  return (
    <ModalShell open={open} onClose={onClose} title={mode === 'update' ? t('sql.saved.editTitle') : t('sql.saved.newTitle')}
      icon={BookmarkPlus} size="md" scrollBody footer={footer} dismissOnBackdrop={false} busy={saving}>
      <div className="flex flex-col gap-3" data-slot="sql-save-dialog">
        {banner && (
          <div data-slot="sql-save-banner">
            <AlertBanner tone={banner.tone} className="mb-0" role={banner.tone === 'danger' ? 'alert' : 'status'}
              actions={banner.conflict ? (
                <Button type="button" variant="outline" size="sm" className="bg-background pointer-coarse:h-10" onClick={reloadServer}>
                  <RefreshCw /> {t('sql.saved.loadLatest')}
                </Button>
              ) : null}>
              {banner.text}
            </AlertBanner>
          </div>
        )}
        <Field label={t('sql.saved.fTitle')} required name="title" error={errText('title')}>
          {({ id, describedBy, invalid }) => (
            <Input ref={titleRef} id={id} value={form.title} maxLength={SAVED_TITLE_MAX + 20} aria-describedby={describedBy} aria-invalid={invalid}
              onChange={(e) => set('title', e.target.value)} />
          )}
        </Field>
        <Field label={t('sql.saved.fDesc')} name="description" error={errText('description')} hint={t('sql.saved.fDescHint', SAVED_DESC_MAX)}>
          {({ id, describedBy, invalid }) => (
            <Textarea id={id} rows={3} value={form.description} aria-describedby={describedBy} aria-invalid={invalid}
              onChange={(e) => set('description', e.target.value)} />
          )}
        </Field>
        <div data-field="tags" className="flex flex-col gap-1">
          <TagInput label={t('sql.saved.fTags')} value={form.tags} onChange={(v) => set('tags', v)} placeholder={t('sql.saved.fTagsHint')} />
          {errText('tags') && <FieldError>{errText('tags')}</FieldError>}
        </div>
        <div className="flex items-start gap-3 rounded-lg border p-3">
          <Switch id="sql-save-shared" checked={form.shared} onCheckedChange={(v) => set('shared', v)} />
          <span className="flex min-w-0 flex-col gap-0.5">
            <Label htmlFor="sql-save-shared">{t('sql.saved.fShared')}</Label>
            <span className="text-xs text-muted-foreground">{t('sql.saved.fSharedHint')}</span>
          </span>
        </div>
        {paramTypes && Object.keys(paramTypes).length > 0 && (
          <p className="m-0 text-xs text-muted-foreground">{t('sql.saved.paramsNote', Object.keys(paramTypes).length)}</p>
        )}
      </div>
    </ModalShell>
  )
}
