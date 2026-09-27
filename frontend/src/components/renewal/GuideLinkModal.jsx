import { useId, useMemo, useState } from 'react'
import { BookOpen, Eye, FolderOpen, Globe, Link2, Mail, Trash2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useDialog } from '../ui/Dialog.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { GuideLinkCard } from './GuideParts.jsx'
import { linkKind, validateLinkForm } from './guideSteps.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'

/**
 * Kaynak bağlantısı ekle / düzenle penceresi (2026-09-26, kullanıcı: "Link Ekle ekranını shadcn ile daha iyi bir UI
 * olarak yeniden tasarlayalım"). ui/ModalShell (kaydırılan gövde, sabit altlık, telefonda tam boy) + ui/Field:
 *  - Başlık (zorunlu), Adres (zorunlu; https://…, \\sunucu\paylaşım ağ yolu, mailto:) + canlı hedef satırı
 *    (tür simgesi + ana bilgisayar + tür rozeti), Kategori (var olanlar listede, yeni ad yazılabilir — SearchableSelect
 *    creatable), Sıra, Açıklama.
 *  - Satır içi doğrulama (dokunulan alanda ya da gönderimde), sunucu hatası pencere içinde AlertBanner.
 *  - Canlı önizleme: rehberdeki kart (GuideLinkCard) — geniş ekranda yan sütun, telefonda katlanır bölüm.
 *  - Düzenlemede Sil (useDialog onayı). Enter ile gönderim (altlıktaki Kaydet `form=` ile forma bağlı).
 * API çağrıları ve yetki kapısı çağıranda (CertRenewalGuide): `onSave(payload)` / `onDelete(link)` sonucu döner.
 */
const KIND_ICON = { web: Globe, unc: FolderOpen, mail: Mail, other: Link2 }

const toForm = (link) => ({
  category: link?.category || '', title: link?.title || '', url: link?.url || '', description: link?.description || '',
  // API yanıtı SNAKE_CASE: `link.sortOrder` daima undefined'dı → düzenleme formu sırayı göstermiyordu.
  sortOrder: link?.sort_order ?? 0,
})

export default function GuideLinkModal({ open, mode = 'add', link = null, categories = [], onClose, onSave, onDelete }) {
  const t = useT()
  const isMobile = useIsMobile()
  const { showConfirm } = useDialog()
  const formId = useId()
  const [form, setForm] = useState(() => toForm(link))
  const [touched, setTouched] = useState({})
  const [submitted, setSubmitted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [previewOpen, setPreviewOpen] = useState(false)

  const errors = useMemo(() => validateLinkForm(form), [form])
  const shown = (k) => (submitted || touched[k]) && errors[k] ? errors[k] : null
  const errText = (k) => {
    const code = shown(k)
    if (!code) return undefined
    if (k === 'url') return code === 'required' ? t('guide.errUrlRequired') : t('guide.errUrlInvalid')
    return k === 'title' ? t('guide.errTitle') : t('guide.errCategory')
  }
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))
  const touch = (k) => () => setTouched((x) => ({ ...x, [k]: true }))

  const kind = linkKind(form.url)
  const KindIcon = KIND_ICON[kind.kind]
  const categoryOptions = useMemo(() => {
    const names = [...new Set([...categories, form.category].map((c) => String(c || '').trim()).filter(Boolean))]
    return names.sort((a, b) => a.localeCompare(b)).map((c) => ({ value: c, label: c }))
  }, [categories, form.category])
  const previewLink = { id: 'preview', ...form, title: form.title.trim() || t('guide.previewTitle'), url: form.url.trim() || 'https://example.com', description: form.description.trim() }

  async function submit(e) {
    e?.preventDefault()
    setSubmitted(true)
    if (Object.keys(errors).length) return
    setBusy(true); setError(null)
    try {
      const payload = {
        category: form.category.trim(), title: form.title.trim(), url: form.url.trim(), description: form.description.trim(),
        // Uç @RequestBody GuideLink (Jackson SNAKE_CASE): camelCase anahtar sessizce düşüyordu.
        sort_order: Number(form.sortOrder) || 0,
      }
      const res = await onSave(payload)
      if (!res?.success) setError(res?.error || t('guide.saveError'))
    } catch (err) {
      setError(err?.message || t('guide.saveError'))
    } finally { setBusy(false) }
  }

  async function remove() {
    const ok = await showConfirm({
      title: t('guide.deleteTitle'), message: t('guide.deleteMsg', link.title), variant: 'danger',
      confirmText: t('guide.deleteConfirm'), cancelText: t('guide.deleteCancel'),
    })
    if (!ok) return
    setBusy(true); setError(null)
    try {
      const res = await onDelete(link)
      if (!res?.success) setError(res?.error || t('guide.saveError'))
    } catch (err) {
      setError(err?.message || t('guide.saveError'))
    } finally { setBusy(false) }
  }

  const preview = (
    <div data-slot="guide-link-preview" className="flex min-w-0 flex-col gap-2">
      <p className="text-xs text-muted-foreground">{t('guide.previewHint')}</p>
      <GuideLinkCard t={t} link={previewLink} isAdmin={false} />
    </div>
  )

  return (
    <ModalShell open={open} onClose={onClose} icon={BookOpen} busy={busy} size="lg" scrollBody dismissOnBackdrop={false}
      title={mode === 'add' ? t('guide.addTitle') : t('guide.editTitle')}
      footer={<>
        {mode === 'edit' && (
          <Button type="button" variant="ghost" className="mr-auto text-destructive hover:bg-destructive/10 hover:text-destructive pointer-coarse:h-10"
            onClick={remove} disabled={busy}>
            <Trash2 aria-hidden="true" />{t('guide.delete')}
          </Button>
        )}
        <Button type="button" variant="secondary" className="pointer-coarse:h-10" onClick={onClose} disabled={busy}>{t('guide.cancel')}</Button>
        <Button type="submit" form={formId} className="pointer-coarse:h-10" disabled={busy} aria-busy={busy || undefined}>
          {busy ? t('guide.saving') : t('guide.save')}
        </Button>
      </>}>
      <form id={formId} onSubmit={submit} noValidate data-slot="guide-link-form"
        className="grid min-w-0 grid-cols-1 gap-5 md:grid-cols-[minmax(0,1fr)_18rem]">
        <div className="flex min-w-0 flex-col gap-1">
          <Field label={t('guide.formTitle')} required error={errText('title')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} value={form.title} onChange={set('title')} onBlur={touch('title')} autoFocus
                placeholder={t('guide.formTitlePh')} aria-describedby={describedBy} aria-invalid={invalid} maxLength={200} />
            )}
          </Field>
          <Field label={t('guide.formUrl')} required hint={t('guide.formUrlHint')} error={errText('url')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} value={form.url} onChange={set('url')} onBlur={touch('url')} inputMode="url" spellCheck={false}
                placeholder={t('guide.formUrlPh')} aria-describedby={describedBy} aria-invalid={invalid} />
            )}
          </Field>
          {/* Canlı hedef satırı: tür simgesi + ana bilgisayar + tür rozeti (adres değiştikçe güncellenir) */}
          {form.url.trim() && !shown('url') && (
            <div data-slot="guide-link-target" data-kind={kind.kind} className="-mt-1 mb-3 flex min-w-0 flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <KindIcon aria-hidden="true" className="size-3.5 shrink-0 text-primary" />
              <span>{t('guide.target')}</span>
              <span className="min-w-0 truncate font-mono text-foreground">{kind.host || '—'}</span>
              <Badge variant="secondary" className="font-normal">{t(`guide.kind.${kind.kind}`)}</Badge>
            </div>
          )}
          <div className="grid grid-cols-1 gap-x-3 sm:grid-cols-[minmax(0,1fr)_7rem]">
            <Field label={t('guide.formCategory')} required hint={t('guide.formCategoryHint')} error={errText('category')}>
              {({ id }) => (
                <div data-slot="guide-link-category" className="[&_[role=combobox]]:w-full">
                  <SearchableSelect id={id} value={form.category} options={categoryOptions} creatable searchThreshold={1}
                    placeholder={t('guide.formCategoryPh')} ariaLabel={t('guide.formCategory')}
                    onChange={(v) => { setForm((f) => ({ ...f, category: String(v ?? '') })); setTouched((x) => ({ ...x, category: true })) }} />
                </div>
              )}
            </Field>
            <Field label={t('guide.formSortOrder')} hint={t('guide.formSortOrderHint')}>
              {({ id, describedBy }) => <Input id={id} type="number" inputMode="numeric" value={form.sortOrder} onChange={set('sortOrder')} aria-describedby={describedBy} />}
            </Field>
          </div>
          <Field label={t('guide.formDescription')}>
            {({ id }) => <Textarea id={id} rows={3} maxLength={500} value={form.description} onChange={set('description')} />}
          </Field>
          {error && <AlertBanner tone="danger" role="alert" className="mb-0">{error}</AlertBanner>}
        </div>

        {/* Önizleme: geniş ekranda yan sütun; telefonda katlanır bölüm (pencere kısa kalsın) */}
        {isMobile ? (
          <CollapsibleSection data-slot="guide-link-preview-toggle" open={previewOpen} onOpenChange={setPreviewOpen} icon={Eye}
            label={t('guide.preview')} toggleLabel={t('guide.showPreview')} contentClassName="pt-3">
            {preview}
          </CollapsibleSection>
        ) : (
          <aside data-slot="guide-link-preview-aside" aria-label={t('guide.preview')} className="min-w-0 rounded-xl border bg-muted/30 p-3">
            <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              <Eye aria-hidden="true" className="size-3.5" />{t('guide.preview')}
            </p>
            {preview}
          </aside>
        )}
      </form>
    </ModalShell>
  )
}
