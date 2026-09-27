import { LoadingBlock } from './ui/Progress.jsx'
import { useState, useEffect, useRef, useId } from 'react'
import { api, formatDate } from '../api/client'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import MarkdownEditor from './ui/MarkdownEditor.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import { MONITOR_GUIDES } from './monitorGuides.js'
import { BookOpen, Plus, Pencil, Trash2, Save, ChevronRight, ChevronDown, CircleHelp, Lock, MessageSquareText } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader, CardTitle, CardAction } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Field, FieldDescription, FieldLabel } from '@/components/shadcn/field'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'

/**
 * Hedef-bazlı (type = HTTP|KEYWORD|PING|…, target = url/host) "Rehber & Notlar" — iki kart:
 *  1. REHBER: üstte türün yerleşik "bu izleme ne yapar / form nasıl doldurulur" belgesi (`monitorGuides.js`,
 *     katlanır — sayfa başlığındaki MonitorGuideButton ile aynı içerik), altında takımın kendi rehberi
 *     (alarm gelince ne yapılır) — markdown, düzenlenebilir.
 *  2. NOTLAR: yapılandırılmış not günlüğü (Sorun / Yapılan işlem / Kök neden / Bakılacak yerler); yazar + zaman,
 *     düzenle/sil; Ctrl/⌘+Enter kaydeder, Esc vazgeçer.
 * Detay modalına lazy yüklenir. Çizim shadcn: Card, Collapsible, Field + Textarea, Badge, AlertBanner, StatusBlock.
 * `canManage=false` (yalnız görüntüleyen) → düzenleme/ekleme/silme düğmeleri çizilmez, kilit notu görünür.
 * Test kancaları: `data-slot="note-howto|note-guide|note-list|note-form|note-card"`.
 */
const EMPTY = { problem: '', action_taken: '', root_cause: '', refs: '' }

export default function MonitorNotes({ type, target, canManage = true }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const toast = useToast()
  const [guide, setGuide] = useState(null)
  const [notes, setNotes] = useState([])
  const [loading, setLoading] = useState(true)

  const [editingGuide, setEditingGuide] = useState(false)
  const [guideDraft, setGuideDraft] = useState('')
  const [savingGuide, setSavingGuide] = useState(false)

  const [form, setForm] = useState(EMPTY)
  const [adding, setAdding] = useState(false)
  const [editId, setEditId] = useState(null)
  const [saving, setSaving] = useState(false)

  useEffect(() => { load()   }, [type, target])

  // D12: type/target hızla değişirse eskinin geç yanıtı yeni hedefin notlarını ezmesin.
  const loadSeq = useRef(0)
  async function load() {
    if (!target) { setLoading(false); return }
    const seq = ++loadSeq.current
    setLoading(true)
    try {
      const res = await api.monitoring.getMonitorNotes(type, target)
      if (seq !== loadSeq.current) return
      if (res?.success) { setGuide(res.data.guide || null); setNotes(res.data.notes || []) }
      else toast.error(res?.error || 'Error')
    } finally {
      setLoading(false)
    }
  }

  async function saveGuide() {
    setSavingGuide(true)
    try {
      const res = await api.monitoring.saveMonitorGuide(type, target, guideDraft)
      if (res?.success) { setGuide(res.data); setEditingGuide(false); toast.success(t('mnote.guideSaved')) }
      else toast.error(res?.error || 'Error')
    } finally {
      setSavingGuide(false)
    }
  }

  function startAdd() { setForm(EMPTY); setEditId(null); setAdding(true) }
  function startEdit(n) {
    setForm({ problem: n.problem || '', action_taken: n.action_taken || '', root_cause: n.root_cause || '', refs: n.refs || '' })
    setEditId(n.id); setAdding(true)
  }
  function cancelForm() { setAdding(false); setEditId(null); setForm(EMPTY) }

  async function saveNote() {
    if (!form.problem.trim()) { toast.error(t('mnote.problemRequired')); return }
    if (saving) return
    setSaving(true)
    try {
      const res = editId
        ? await api.monitoring.updateMonitorNote(editId, form)
        : await api.monitoring.addMonitorNote({ type, target, ...form })
      if (res?.success) { toast.success(editId ? t('mnote.saved') : t('mnote.added')); cancelForm(); load() }
      else toast.error(res?.error || 'Error')
    } finally {
      setSaving(false)
    }
  }

  /** Klavye: Ctrl/⌘+Enter kaydeder, Escape vazgeçer (form alanlarının herhangi birinden). */
  function onFormKeyDown(e) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); saveNote() }
    else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancelForm() }
  }

  // Escape FORMU kapatır, pencereyi değil (2026-09-27 regresyon BF3). Not formu bir ModalShell (Radix Dialog) içinde;
  // Radix'in Escape dinleyicisi BELGEDE capture evresinde koştuğu için yukarıdaki onKeyDown'un stopPropagation'ı hiç
  // işe yaramıyor, Escape tüm detay penceresini kapatıp taslağı götürüyordu. Bu dinleyici `window` capture'da —
  // belgeden ÖNCE koşar; `preventDefault` Radix'e "işlendi" der (DismissableLayer defaultPrevented'da kapatmaz).
  // Hook erken return'ün (`if (loading)`) ÜSTÜNDE kalmalı.
  const formId = useId()
  useEffect(() => {
    if (!adding) return undefined
    const onKey = (e) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      if (!e.target?.closest?.(`[data-note-form="${formId}"]`)) return
      e.preventDefault()
      setAdding(false); setEditId(null); setForm(EMPTY)
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [adding, formId])

  // Takım rehberi düzenleyicisi (2026-09-27): aynı Radix sorunu — Escape tüm pencereyi kapatıp uzun Markdown taslağını
  // götürüyordu. Burada Escape YALNIZ pencerenin kapanmasını durdurur; taslak kalır, çıkış "Vazgeç" düğmesiyle
  // (tek tuşla uzun metni silmek not formundaki kısa alanlardan farklı olarak veri kaybı olur).
  const guideFormId = useId()
  useEffect(() => {
    if (!editingGuide) return undefined
    const onKey = (e) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      if (!e.target?.closest?.(`[data-guide-form="${guideFormId}"]`)) return
      e.preventDefault()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [editingGuide, guideFormId])

  async function del(n) {
    if (!await showConfirm({
      title: t('mnote.delete'), message: t('mnote.deleteConfirm'),
      confirmText: t('mnote.delete'), variant: 'danger',
    })) return
    const res = await api.monitoring.deleteMonitorNote(n.id)
    if (res?.success) { toast.success(t('mnote.deleted')); load() }
    else toast.error(res?.error || 'Error')
  }

  if (loading) return <LoadingBlock label={t('modal.loading')} />

  return (
    <div className="flex flex-col gap-4 py-1">
      {/* ── Rehber: yerleşik tür belgesi (katlanır) + takım rehberi ── */}
      <Card data-slot="note-guide" className="gap-3 rounded-lg px-3.5 py-3 shadow-none">
        <CardHeader className="flex items-center justify-between gap-2.5 px-0">
          <CardTitle className="inline-flex items-center gap-1.5 text-sm font-bold"><BookOpen size={15} aria-hidden="true" /> {t('mnote.guideTitle')}</CardTitle>
          {canManage && !editingGuide && (
            <CardAction>
              <Button variant="secondary" size="sm" className="pointer-coarse:h-10"
                onClick={() => { setGuideDraft(guide?.guide || ''); setEditingGuide(true) }}>
                <Pencil size={12} aria-hidden="true" /> {t('mnote.edit')}
              </Button>
            </CardAction>
          )}
        </CardHeader>
        <CardContent className="flex flex-col gap-3 px-0">
          <HowToGuide type={type} />
          {editingGuide ? (
            <div data-guide-form={guideFormId} className="flex flex-col gap-3">
              <MarkdownEditor value={guideDraft} onChange={setGuideDraft} editable height={240} />
              <div className="flex justify-end gap-2">
                <Button variant="secondary" size="sm" onClick={() => setEditingGuide(false)}>{t('mnote.cancel')}</Button>
                <Button size="sm" onClick={saveGuide} disabled={savingGuide} aria-busy={savingGuide || undefined}>
                  <Save size={12} aria-hidden="true" /> {savingGuide ? t('mnote.saving') : t('mnote.save')}
                </Button>
              </div>
            </div>
          ) : guide?.guide ? (
            <div data-slot="note-team-guide" className="rounded-lg border bg-muted/30 px-3.5 py-3">
              <div className="mb-1.5 text-[11px] font-bold tracking-[.04em] text-muted-foreground uppercase">{t('mnote.teamGuideTitle')}</div>
              <div className="mnote-md"><MarkdownEditor value={guide.guide} editable={false} /></div>
              {guide.updated_by && (
                <div className="mt-2 text-xs text-muted-foreground">{t('mnote.updatedBy')}: {guide.updated_by} · {fmt(guide.updated_at)}</div>
              )}
            </div>
          ) : (
            <StatusBlock tone="neutral" icon={MessageSquareText} description={t('mnote.guideEmpty')}
              className="rounded-lg border border-dashed py-4 text-sm md:py-4" />
          )}
        </CardContent>
      </Card>

      {/* ── Notlar ── */}
      <Card data-slot="note-list" className="gap-3 rounded-lg px-3.5 py-3 shadow-none">
        <CardHeader className="flex items-center justify-between gap-2.5 px-0">
          <CardTitle className="inline-flex items-center gap-1.5 text-sm font-bold">
            <MessageSquareText size={15} aria-hidden="true" /> {t('mnote.notesTitle')}
            <Badge variant="secondary" data-slot="note-count" className="h-5 min-w-5 rounded-full px-1.5 text-[11px] font-bold tabular-nums">{notes.length}</Badge>
          </CardTitle>
          {canManage && !adding && (
            <CardAction>
              <Button size="sm" className="pointer-coarse:h-10" onClick={startAdd}>
                <Plus size={12} aria-hidden="true" /> {t('mnote.addNote')}
              </Button>
            </CardAction>
          )}
        </CardHeader>
        <CardContent className="flex flex-col gap-2.5 px-0">
          {!canManage && (
            <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground">
              <Lock aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('mnote.readOnly')}
            </p>
          )}

          {adding && (
            <Card data-slot="note-form" data-note-form={formId} onKeyDown={onFormKeyDown}
              className="gap-2.5 rounded-lg border-dashed border-primary bg-primary/5 px-3.5 py-3 shadow-none">
              <NoteField label={t('mnote.fProblem')} required value={form.problem} autoFocus
                onChange={v => setForm(f => ({ ...f, problem: v }))} />
              <NoteField label={t('mnote.fAction')} value={form.action_taken}
                onChange={v => setForm(f => ({ ...f, action_taken: v }))} />
              <NoteField label={t('mnote.fRoot')} value={form.root_cause}
                onChange={v => setForm(f => ({ ...f, root_cause: v }))} />
              <NoteField label={t('mnote.fRefs')} value={form.refs}
                onChange={v => setForm(f => ({ ...f, refs: v }))} />
              <FieldDescription className="text-xs">{t('mnote.mdHint')} · {t('mnote.submitHint')}</FieldDescription>
              <div className="flex flex-wrap justify-end gap-2">
                <Button variant="secondary" size="sm" className="pointer-coarse:h-10" onClick={cancelForm}>{t('mnote.cancel')}</Button>
                <Button size="sm" className="pointer-coarse:h-10" onClick={saveNote} disabled={saving || !form.problem.trim()} aria-busy={saving || undefined}>
                  {saving ? t('mnote.saving') : (editId ? t('mnote.save') : t('mnote.addNote'))}
                </Button>
              </div>
            </Card>
          )}

          {notes.length === 0 && !adding && (
            <StatusBlock tone="neutral" icon={MessageSquareText} description={t('mnote.notesEmpty')}
              className="rounded-lg border border-dashed py-4 text-sm md:py-4" />
          )}

          {notes.map(n => (
            <NoteCard key={n.id} n={n} onEdit={canManage ? startEdit : null} onDelete={canManage ? del : null} />
          ))}
        </CardContent>
      </Card>
    </div>
  )
}

/**
 * Türün yerleşik rehberi — `monitorGuides.js` (TR/EN markdown), katlanır. Sayfa başlığındaki "Nasıl doldurulur?"
 * düğmesiyle aynı içerik; pencereden çıkmadan okunur. Tür için belge yoksa hiç çizilmez.
 */
function HowToGuide({ type }) {
  const t = useT()
  const { lang } = useLanguage()
  const [open, setOpen] = useState(false)
  const guide = MONITOR_GUIDES[String(type || '').toLowerCase()]
  if (!guide) return null
  const md = guide[lang] || guide.tr || guide.en
  return (
    <Collapsible open={open} onOpenChange={setOpen} data-slot="note-howto" className="overflow-hidden rounded-lg border bg-violet-500/5">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost"
          className="h-auto min-h-10 w-full justify-start gap-2 rounded-none px-3.5 py-2 text-left text-[13px] font-semibold whitespace-normal hover:bg-violet-500/5 dark:hover:bg-violet-500/10">
          <CircleHelp size={15} aria-hidden="true" className="shrink-0 text-violet-600 dark:text-violet-400" />
          <span className="min-w-0 flex-1">{t('mnote.howtoTitle')}</span>
          <ChevronDown size={15} aria-hidden="true"
            className={cn('shrink-0 text-muted-foreground transition-transform duration-200 motion-reduce:transition-none', open && 'rotate-180')} />
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="flex flex-col gap-2 px-3.5 pb-3">
          <AlertBanner tone="info" className="my-0 py-2 text-xs">{t('mnote.howtoTip')}</AlertBanner>
          <div className="mnote-md max-h-[50vh] overflow-y-auto pr-1 text-sm leading-relaxed"><MarkdownEditor value={md} editable={false} /></div>
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}

function NoteField({ label, value, onChange, required, autoFocus }) {
  const id = useId()
  return (
    <Field role={undefined} className="gap-1">
      <FieldLabel htmlFor={id} className="gap-1 text-[12.5px] font-semibold text-muted-foreground">
        {label}{required && <span data-slot="field-required" className="text-destructive">*</span>}
      </FieldLabel>
      <Textarea id={id} rows={3} value={value} spellCheck={false} autoFocus={autoFocus} onChange={e => onChange(e.target.value)}
        className="min-h-0 md:text-[13px]" />
    </Field>
  )
}

function NoteRow({ label, value }) {
  if (!value) return null
  return (
    <div className="my-1.5">
      <div className="mb-0.5 text-[11.5px] font-bold tracking-[.02em] text-muted-foreground uppercase">{label}</div>
      <div className="mnote-md"><MarkdownEditor value={value} editable={false} /></div>
    </div>
  )
}

// Tek not — akordiyon (shadcn Collapsible): varsayılan kapalı; başlıkta problem özeti + yazar·tarih,
// tetiğe basınca alanlar açılır. Düzenle/Sil başlıkta hep görünür — tetiğin KARDEŞİ oldukları için
// (içinde değil) basmak akordiyonu açmaz; eskiden role="button" başlığın içinde iç içe duruyorlardı.
// onEdit/onDelete null ise (yalnız görüntüleyen) düğmeler çizilmez.
function NoteCard({ n, onEdit, onDelete }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const title = n.problem || t('mnote.fProblem')
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <Card data-slot="note-card" className="gap-0 rounded-lg px-3 py-2.5 shadow-none">
        <div className={cn('flex items-center justify-between gap-2.5', open && 'mb-2')}>
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost"
              className="group/note h-auto min-w-0 flex-1 justify-start gap-2 px-1 py-0.5 text-left font-normal hover:bg-transparent pointer-coarse:min-h-10 dark:hover:bg-transparent">
              <ChevronRight size={15} aria-hidden="true"
                className={cn('shrink-0 text-muted-foreground transition-transform duration-150 motion-reduce:transition-none', open && 'rotate-90')} />
              <span className="flex min-w-0 flex-1 flex-col gap-px">
                <span className="truncate text-[13px] font-semibold group-hover/note:text-primary">{title}</span>
                <span className="truncate text-xs text-muted-foreground">
                  {n.author_name || n.author_username} · {fmt(n.created_at)}
                  {n.updated_at ? ` · ${t('mnote.edited')}` : ''}
                </span>
              </span>
            </Button>
          </CollapsibleTrigger>
          {(onEdit || onDelete) && (
            <span className="inline-flex shrink-0 gap-1.5">
              {onEdit && (
                <SimpleTooltip content={t('mnote.edit')}>
                  <Button variant="secondary" size="icon-sm" className="pointer-coarse:size-10" aria-label={t('a11y.rowAction', title, t('mnote.edit'))}
                    onClick={() => onEdit(n)}><Pencil size={12} aria-hidden="true" /></Button>
                </SimpleTooltip>
              )}
              {onDelete && (
                <SimpleTooltip content={t('mnote.delete')}>
                  <Button variant="destructive" size="icon-sm" className="pointer-coarse:size-10" aria-label={t('a11y.rowAction', title, t('mnote.delete'))}
                    onClick={() => onDelete(n)}><Trash2 size={12} aria-hidden="true" /></Button>
                </SimpleTooltip>
              )}
            </span>
          )}
        </div>
        <CollapsibleContent>
          <NoteRow label={t('mnote.fProblem')} value={n.problem} />
          <NoteRow label={t('mnote.fAction')} value={n.action_taken} />
          <NoteRow label={t('mnote.fRoot')} value={n.root_cause} />
          <NoteRow label={t('mnote.fRefs')} value={n.refs} />
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}

function fmt(iso) {
  if (!iso) return ''
  try { return formatDate(iso) } catch { return iso }
}
