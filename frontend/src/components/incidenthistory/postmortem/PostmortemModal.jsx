import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  BellRing, CheckCircle2, CircleDot, ClipboardList, Download, Eye, FileText, ListChecks, MessageSquare, Plus, Send, Siren, Timer, UserCheck, XCircle,
} from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { downloadText } from '../../../utils/csvExport.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import MarkdownEditor from '../../ui/MarkdownEditor.jsx'
import { LoadingBlock, Spinner } from '../../ui/Progress.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'
import { ActionForm, ActionRow } from './ActionItemParts.jsx'
import {
  MAX_SECTION_CHARS, SECTIONS, STATUSES, STATUS_TONE, actionCounts, buildMarkdown, changedFields, formFromPostmortem, isDirty,
  markdownFileName, sectionErrors, timelineText,
} from './postmortemModel.js'

const TL_ICON = {
  occurred: Siren, detected: Eye, recorded: FileText, resolved: CheckCircle2,
  alert_opened: BellRing, alert_notified: Send, alert_acknowledged: UserCheck, alert_resolved: CheckCircle2, alert_comment: MessageSquare,
  action_added: ListChecks, action_done: CheckCircle2, action_cancelled: XCircle, postmortem_published: ClipboardList,
}
const TL_INK = {
  occurred: 'border-destructive/40 bg-destructive/10 text-destructive',
  alert_opened: 'border-destructive/40 bg-destructive/10 text-destructive',
  resolved: 'border-success/40 bg-success/10 text-success',
  alert_resolved: 'border-success/40 bg-success/10 text-success',
  action_done: 'border-success/40 bg-success/10 text-success',
  postmortem_published: 'border-primary/40 bg-primary/10 text-primary',
}

/**
 * Postmortem penceresi (2026-10-11): olay kaydının suçlamasız postmortem raporu, aksiyonları ve otomatik zaman çizelgesi.
 * <ul>
 *   <li><b>Rapor</b>: on bölümlük şablon (rapor yoksa olay kaydından ön dolu), durum Taslak / İncelemede / Yayımlandı;
 *       yazar düzenler, diğerleri işlenmiş markdown okur. Sürüm denetimi: araya başkası kaydettiyse uyarı + yeniden yükle.</li>
 *   <li><b>Aksiyonlar</b>: ekle / düzenle / sil (yazar), durum (yazar ya da sahibi — anında kaydedilir).</li>
 *   <li><b>Zaman çizelgesi</b>: olay + bağlı alarm (açıldı / ilk bildirim / sahiplenildi / kapandı / yorumlar) + aksiyonlar.</li>
 * </ul>
 * Markdown olarak indirilebilir. Kaydedilmemiş değişiklikle kapatmak onay ister. Adlar sarar (kesilmez).
 */
export default function PostmortemModal({ incident, onClose, onChanged }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [data, setData] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [form, setForm] = useState(null)
  const [original, setOriginal] = useState(null)
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [errors, setErrors] = useState({})
  const [tab, setTab] = useState('report')
  const [people, setPeople] = useState([])
  const [editing, setEditing] = useState(null)        // null | 'new' | action
  const [actionBusy, setActionBusy] = useState(null)  // aksiyon id ya da 'form'
  const changed = useRef(false)
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    setLoadError(null)
    let res = null
    try { res = await api.incidents.postmortem(incident.id) } catch (e) { res = { success: false, error: e?.message } }
    if (my !== seq.current) return
    if (res?.success && res.data) {
      setData(res.data)
      const f = formFromPostmortem(res.data.postmortem)
      setForm(f)
      setOriginal(f)
      setConflict(false)
      setErrors({})
    } else setLoadError(res?.error || t('pm.loadError'))
  }, [incident.id, t])

  useEffect(() => { load() }, [load])

  const canEdit = !!data?.can_edit
  useEffect(() => {
    if (!canEdit) return
    let alive = true
    api.users.directory().then((res) => {
      if (!alive || !res?.success) return
      const list = Array.isArray(res.data) ? res.data : []
      setPeople(list.map((p) => ({ value: String(p.id), label: p.display_name || p.username || String(p.id) }))
        .sort((a, b) => a.label.localeCompare(b.label, 'tr', { sensitivity: 'base' })))
    }).catch(() => {})
    return () => { alive = false }
  }, [canEdit])

  const pm = data?.postmortem
  const actions = useMemo(() => (Array.isArray(data?.actions) ? data.actions : []), [data])
  const timeline = Array.isArray(data?.timeline) ? data.timeline : []
  const today = data?.today
  const counts = actionCounts(actions, today)
  const dirty = form && original ? isDirty(form, original) : false

  const close = async () => {
    if (dirty) {
      const ok = await showConfirm({ title: t('pm.discardTitle'), message: t('pm.discardMsg'),
        confirmText: t('pm.discard'), cancelText: t('dlg.cancel'), variant: 'warning' })
      if (!ok) return
    }
    if (changed.current) onChanged?.()
    onClose?.()
  }

  const set = (k, v) => { setForm((f) => ({ ...f, [k]: v })); setErrors((e) => (e[k] ? { ...e, [k]: undefined } : e)) }

  async function save() {
    const errs = sectionErrors(form)
    if (Object.keys(errs).length) {
      setErrors(errs)
      setTab('report')
      return
    }
    const body = { ...changedFields(form, original, !!pm?.exists), version: pm?.version ?? null }
    setSaving(true)
    let res = null
    try { res = await api.incidents.savePostmortem(incident.id, body) } catch (e) { res = { success: false, error: e?.message } } finally { setSaving(false) }
    if (res?.success && res.data) {
      const f = formFromPostmortem(res.data)
      setData((d) => ({ ...d, postmortem: res.data }))
      setForm(f)
      setOriginal(f)
      changed.current = true
      toast.success(t('pm.saved'))
      return
    }
    if (res?.errorInfo?.status === 409 || res?.code === 'CONCURRENT_MODIFICATION') { setConflict(true); return }
    if (res?.fields) setErrors(Object.fromEntries(Object.keys(res.fields).map((k) => [k, res.fields[k]])))
    toast.error(res?.error || t('pm.saveError'))
  }

  const reloadAfterConflict = async () => {
    if (dirty) {
      const ok = await showConfirm({ title: t('pm.reloadTitle'), message: t('pm.reloadMsg'),
        confirmText: t('pm.reload'), cancelText: t('dlg.cancel'), variant: 'warning' })
      if (!ok) return
    }
    load()
  }

  // ── Aksiyonlar ──
  const replaceAction = (a) => setData((d) => ({ ...d, actions: d.actions.map((x) => (x.id === a.id ? { ...x, ...a } : x)) }))

  async function submitAction(body) {
    const isEdit = editing && editing !== 'new'
    // Düzenlemede gövde yalnız değişen alanları taşır; hiçbir şey değişmediyse istek atılmaz (boş güncelleme, sahte "kaydedildi"
    // ve gereksiz denetim satırı yok — 2026-10-12). Form kapanır.
    if (isEdit && body && Object.keys(body).length === 0) {
      setEditing(null)
      return null
    }
    setActionBusy('form')
    let res = null
    try {
      res = isEdit ? await api.incidents.updateAction(editing.id, body) : await api.incidents.addAction(incident.id, body)
    } catch (e) { res = { success: false, error: e?.message } } finally { setActionBusy(null) }
    if (res?.success && res.data) {
      if (isEdit) replaceAction(res.data)
      else setData((d) => ({ ...d, actions: [...d.actions, res.data] }))
      setEditing(null)
      changed.current = true
      toast.success(isEdit ? t('pm.actionSaved') : t('pm.actionAdded'))
      return null
    }
    if (!res?.fields) toast.error(res?.error || t('pm.actionError'))
    return res
  }

  async function changeStatus(a, status) {
    if (status === a.status) return
    setActionBusy(a.id)
    let res = null
    try { res = await api.incidents.updateAction(a.id, { status }) } catch (e) { res = { success: false, error: e?.message } } finally { setActionBusy(null) }
    if (res?.success && res.data) {
      replaceAction(res.data)
      changed.current = true
      toast.success(t('pm.statusSaved', t('pm.astatus.' + status)))
    } else toast.error(res?.error || t('pm.actionError'))
  }

  async function removeAction(a) {
    const ok = await showConfirm({ title: t('pm.deleteTitle'), message: t('pm.deleteMsg', a.title),
      confirmText: t('pm.deleteConfirm'), cancelText: t('dlg.cancel'), variant: 'danger' })
    if (!ok) return
    setActionBusy(a.id)
    let res = null
    try { res = await api.incidents.deleteAction(a.id) } catch (e) { res = { success: false, error: e?.message } } finally { setActionBusy(null) }
    if (res?.success) {
      setData((d) => ({ ...d, actions: d.actions.filter((x) => x.id !== a.id) }))
      changed.current = true
      toast.success(t('pm.actionDeleted'))
    } else toast.error(res?.error || t('pm.actionError'))
  }

  const exportMd = () => {
    const md = buildMarkdown({ incident, postmortem: { ...pm, ...form }, actions, timeline, t, fmt: formatDate })
    downloadText(markdownFileName(incident), md, 'text/markdown;charset=utf-8')
  }

  const footer = (
    <div className="flex w-full flex-wrap items-center justify-end gap-2">
      <Button type="button" variant="outline" onClick={exportMd} disabled={!data} className="pointer-coarse:h-10 max-sm:flex-1" data-slot="pm-export">
        <Download aria-hidden="true" />{t('pm.export')}
      </Button>
      <Button type="button" variant="ghost" onClick={close} className="pointer-coarse:h-10 max-sm:flex-1">{t('app.close')}</Button>
      {canEdit && (
        <Button type="button" onClick={save} disabled={!dirty || saving} aria-busy={saving || undefined}
          className="pointer-coarse:h-10 max-sm:w-full" data-slot="pm-save">
          {saving && <Spinner size={14} inline decorative />}{t('pm.save')}
        </Button>
      )}
    </div>
  )

  return (
    <ModalShell open onClose={close} title={t('pm.title', incident.title || '#' + incident.id)} icon={ClipboardList} size="xl"
      scrollBody footer={footer} dismissOnBackdrop={false}>
      <div data-slot="pm-modal" className="flex min-w-0 flex-col gap-4">
        {loadError && !data && (
          <StatusBlock tone="danger" title={t('pm.loadError')} description={loadError}
            actions={<Button type="button" variant="outline" onClick={load}>{t('pm.retry')}</Button>} />
        )}
        {!data && !loadError && <LoadingBlock label={t('pm.loading')} />}
        {data && form && (
          <Tabs value={tab} onValueChange={setTab} className="min-w-0 gap-4">
            <TabsList className="h-auto w-full flex-wrap justify-start sm:w-fit">
              <TabsTrigger value="report" className="pointer-coarse:h-10" data-slot="pm-tab-report">{t('pm.tabReport')}</TabsTrigger>
              <TabsTrigger value="actions" className="gap-1.5 pointer-coarse:h-10" data-slot="pm-tab-actions">
                {t('pm.tabActions')}
                <span className={cn('rounded-full px-1.5 text-xs tabular-nums', counts.overdue ? 'bg-destructive/15 text-destructive' : 'bg-muted text-muted-foreground')}>
                  {counts.open}/{counts.total}
                </span>
              </TabsTrigger>
              <TabsTrigger value="timeline" className="pointer-coarse:h-10" data-slot="pm-tab-timeline">{t('pm.tabTimeline')}</TabsTrigger>
            </TabsList>

            <TabsContent value="report" forceMount hidden={tab !== 'report'} className="flex min-w-0 flex-col gap-4">
              <AlertBanner tone="info" title={t('pm.blamelessTitle')} className="mb-0">{t('pm.blameless')}</AlertBanner>
              {conflict && (
                <div data-slot="pm-conflict">
                  <AlertBanner tone="warning" role="alert" title={t('pm.conflictTitle')} className="mb-0"
                    actions={<Button type="button" variant="outline" size="sm" onClick={reloadAfterConflict}>{t('pm.reload')}</Button>}>
                    {t('pm.conflictBody')}
                  </AlertBanner>
                </div>
              )}
              <div className="flex flex-wrap items-center gap-3 rounded-lg border bg-card p-3" data-slot="pm-status">
                <span className="text-sm font-semibold">{t('pm.status')}</span>
                {canEdit ? (
                  <SegmentedControl value={form.status} onChange={(v) => set('status', v)} ariaLabel={t('pm.status')}
                    options={STATUSES.map((s) => ({ value: s, label: t('pm.status.' + s) }))} />
                ) : <ToneBadge tone={STATUS_TONE[form.status]}>{t('pm.status.' + form.status)}</ToneBadge>}
                <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                  {pm?.exists
                    ? (pm.published_at ? t('pm.publishedMeta', pm.published_by || '—', formatDate(pm.published_at))
                      : t('pm.updatedMeta', pm.updated_by || '—', formatDate(pm.updated_at)))
                    : t('pm.notSavedYet')}
                </span>
              </div>
              {SECTIONS.map((k) => (
                <section key={k} className="flex min-w-0 flex-col gap-1.5" data-slot="pm-section" data-key={k} data-field={k}>
                  <label htmlFor={`pm-${k}`} className="text-sm font-semibold">{t('pm.sec.' + k)}</label>
                  <p className="m-0 -mt-1 text-xs text-muted-foreground" id={`pm-${k}-hint`}>{t('pm.sec.' + k + 'Hint')}</p>
                  {canEdit ? (
                    <>
                      <Textarea id={`pm-${k}`} value={form[k]} rows={k === 'summary' || k === 'root_cause' ? 5 : 3}
                        aria-describedby={`pm-${k}-hint${errors[k] ? ` pm-${k}-err` : ''}`} aria-invalid={errors[k] ? true : undefined}
                        onChange={(e) => set(k, e.target.value)} placeholder={t('pm.sectionPh')} className="min-h-20 text-sm" />
                      {errors[k] && (
                        <p id={`pm-${k}-err`} className="m-0 text-xs font-medium text-destructive" role="alert">
                          {typeof errors[k] === 'string' ? errors[k] : t('pm.err.sectionLong', MAX_SECTION_CHARS)}
                        </p>
                      )}
                    </>
                  ) : (form[k] || '').trim()
                    ? <div className="min-w-0 text-sm"><MarkdownEditor value={form[k]} editable={false} /></div>
                    : <p className="m-0 rounded-lg border border-dashed px-3 py-2 text-sm text-muted-foreground">{t('pm.empty')}</p>}
                </section>
              ))}
            </TabsContent>

            <TabsContent value="actions" forceMount hidden={tab !== 'actions'} className="flex min-w-0 flex-col gap-3">
              <div className="flex flex-wrap items-center gap-2 text-sm" data-slot="pm-action-counts">
                <ToneBadge tone="info">{t('pm.count.open', counts.open)}</ToneBadge>
                <ToneBadge tone={counts.overdue ? 'danger' : 'muted'}>{t('pm.count.overdue', counts.overdue)}</ToneBadge>
                <ToneBadge tone="success">{t('pm.count.done', counts.done)}</ToneBadge>
                {canEdit && editing !== 'new' && (
                  <Button type="button" size="sm" className="ml-auto pointer-coarse:h-10" onClick={() => setEditing('new')} data-slot="pm-add-action">
                    <Plus aria-hidden="true" />{t('pm.addAction')}
                  </Button>
                )}
              </div>
              {editing === 'new' && (
                <ActionForm initial={null} people={people} busy={actionBusy === 'form'} onSubmit={submitAction} onCancel={() => setEditing(null)} />
              )}
              {actions.length === 0 && editing !== 'new' ? (
                <StatusBlock icon={ListChecks} title={t('pm.noActions')} description={canEdit ? t('pm.noActionsHint') : undefined}
                  className="rounded-lg border border-dashed py-8" />
              ) : (
                <ul className="m-0 flex list-none flex-col gap-2 p-0" data-slot="pm-actions">
                  {actions.map((a) => (editing && editing !== 'new' && editing.id === a.id ? (
                    <li key={a.id}>
                      <ActionForm initial={a} people={people} busy={actionBusy === 'form'} onSubmit={submitAction} onCancel={() => setEditing(null)} />
                    </li>
                  ) : (
                    <ActionRow key={a.id} action={a} today={today} canWrite={canEdit} busy={actionBusy === a.id}
                      onStatus={changeStatus} onEdit={(x) => setEditing(x)} onDelete={removeAction} />
                  )))}
                </ul>
              )}
            </TabsContent>

            <TabsContent value="timeline" forceMount hidden={tab !== 'timeline'} className="min-w-0">
              {timeline.length === 0 ? (
                <StatusBlock icon={Timer} title={t('pm.noTimeline')} className="rounded-lg border border-dashed py-8" />
              ) : (
                <ol data-slot="pm-timeline" className="relative m-0 flex list-none flex-col gap-2.5 p-0 pl-10 before:absolute before:top-4 before:bottom-4 before:left-[15px] before:w-px before:bg-border">
                  {timeline.map((e, i) => {
                    const Icon = TL_ICON[e.kind] || CircleDot
                    return (
                      <li key={`${e.kind}-${e.at}-${i}`} data-slot="pm-timeline-item" data-kind={e.kind} className="relative min-w-0">
                        <span aria-hidden="true" className={cn('absolute top-0.5 -left-10 grid size-8 place-items-center rounded-full border bg-background',
                          TL_INK[e.kind] || 'border-border text-muted-foreground')}>
                          <Icon className="size-4" />
                        </span>
                        <div className="min-w-0 rounded-lg border bg-card px-3 py-2">
                          <p className="m-0 text-sm font-medium [overflow-wrap:anywhere]">{timelineText(e, t)}</p>
                          <p className="m-0 mt-0.5 text-xs text-muted-foreground tabular-nums">{formatDate(e.at)}</p>
                        </div>
                      </li>
                    )
                  })}
                </ol>
              )}
              <p className="mt-3 mb-0 text-xs text-muted-foreground">{t('pm.timelineNote')}</p>
            </TabsContent>
          </Tabs>
        )}
      </div>
    </ModalShell>
  )
}
