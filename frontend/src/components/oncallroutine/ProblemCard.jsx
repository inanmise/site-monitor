import { useEffect, useId, useRef, useState } from 'react'
import { CheckCircle2, Pencil, RotateCcw, Trash2 } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { Textarea } from '@/components/shadcn/textarea'
import { Spinner } from '@/components/shadcn/spinner'
import { cn } from '@/lib/utils'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import ProblemImages, { serverText } from './ProblemImages.jsx'
import ProblemIncidentButton, { IncidentChip } from './ProblemIncidentButton.jsx'
import { SEVERITY_ICON, SeverityToggle, problemNoteProblem } from './ProblemComposer.jsx'
import { codeOf, istanbulParts, shortDate } from './routineModel.js'

/** Çözüm notu üst sınırı (sunucu: ≤ 2000). */
export const RESOLUTION_NOTE_MAX = 2000
/** Not bu kadar satırı / karakteri geçerse kapalı gösterilir ("Devamı"). */
const NOTE_FOLD_LINES = 6
const NOTE_FOLD_CHARS = 480
const SEVERITY_VARIANT = Object.freeze({ HIGH: 'destructive', MEDIUM: 'warning', LOW: 'outline' })
const ACTION = 'pointer-coarse:min-h-10'

/** Sunucu UTC damgası → "24.11 09:42" (İstanbul). Okunamazsa ''. */
export function stampOf(iso) {
  if (typeof iso !== 'string' || !iso) return ''
  const ms = Date.parse(/[zZ]$|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`)
  if (!Number.isFinite(ms)) return ''
  const p = istanbulParts(ms)
  return `${shortDate(p.date)} ${p.hm}`
}

/** Not kapalı başlasın mı (uzun not "Devamı" ile açılır). */
export function noteIsLong(note) {
  const s = String(note ?? '')
  return s.length > NOTE_FOLD_CHARS || s.split('\n').length > NOTE_FOLD_LINES
}

/** Önem rozeti — Yüksek tehlike, Orta dikkat, Düşük çerçeve; metin + simge (renk tek başına bilgi taşımaz). */
export function SeverityBadge({ severity, className }) {
  const t = useT()
  const Icon = SEVERITY_ICON[severity]
  if (!Icon) return null
  return (
    <Badge variant={SEVERITY_VARIANT[severity]} data-slot="or-problem-severity-badge" data-severity={severity}
      className={cn('gap-1 rounded-md font-semibold', className)}>
      <Icon aria-hidden="true" />{t(`or.sev.${severity}`)}
    </Badge>
  )
}

/** Yanıttan sorun (PATCH `{problem}` ya da doğrudan PROBLEM) — yoksa null. */
function problemOf(res) {
  const d = res?.data?.problem ?? res?.data
  return d && typeof d === 'object' && d.id != null ? d : null
}

/** Açık sorunun yaş metni ("bugün açıldı" / "2 gündür açık"). */
function ageText(t, days) {
  const n = Number(days)
  if (!Number.isFinite(n) || n < 0) return null
  if (n === 0) return t('or.prob.ageToday')
  return n === 1 ? t('or.prob.ageDays.one', n) : t('or.prob.ageDays', n)
}

/**
 * SORUN KARTI (2026-11-26) — Açık sorunlar panelinin satırı. Önem rozeti, görev / bölüm, yaş, not (uzunsa "Devamı"),
 * çözüm notu, görseller (küçük resim → büyük görünüm), bağlı olay kaydı, kim / ne zaman. Eylemler satır İÇİNDE (pencere
 * yok): "Çözüldü" (isteğe bağlı çözüm notu → onay; ardından "Geri al"), "Yeniden aç", "Düzenle" (önem + not), "Olay kaydı
 * oluştur", "Sil" (yalnız `can_delete`, onaylı).
 *
 * Her yazma sürüm denetimlidir (`version`); başkası arada değiştirdiyse (409 `CONCURRENT_MODIFICATION`) yanıttaki güncel
 * hâl hemen gösterilir ve kim değiştirdiği söylenir; açık düzenleme kutusundaki metin korunur (yeniden denenebilir).
 *
 * Props: `{ teamId, teamName, problem, canWork, onChange(problem), onDelete(id), highlighted }`.
 */
export default function ProblemCard({ teamId, teamName, problem, canWork = false, onChange = () => {}, onDelete = () => {}, highlighted = false }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const baseId = useId()
  const editable = !!canWork && problem?.can_edit !== false
  const resolved = problem?.status === 'RESOLVED'
  const [expanded, setExpanded] = useState(false)
  const [mode, setMode] = useState(null)              // null | 'resolve' | 'edit'
  const [busy, setBusy] = useState(null)              // süren işlem: 'resolve' | 'reopen' | 'edit' | 'delete'
  const [resolveNote, setResolveNote] = useState('')
  const [resolveProblem, setResolveProblem] = useState(null)
  const [editSeverity, setEditSeverity] = useState(problem?.severity || 'MEDIUM')
  const [editNote, setEditNote] = useState(problem?.note || '')
  const [editProblem, setEditProblem] = useState(null)
  const alive = useRef(true)
  const resolveRef = useRef(null)
  const editRef = useRef(null)
  const problemRef = useRef(problem)
  problemRef.current = problem
  const cbRef = useRef({ onChange, onDelete })
  cbRef.current = { onChange, onDelete }

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])
  useEffect(() => {
    if (mode === 'resolve') resolveRef.current?.focus()
    if (mode === 'edit') editRef.current?.focus()
  }, [mode])

  if (!problem || problem.id == null) return null

  /**
   * Sürüm denetimli güncelleme. Başarı → güncel sorun; ret → null (bildirim / alan hatası burada verilir).
   * `onField(alanlar)` sunucunun alan hatalarını açık kutuya yazar.
   */
  async function patch(kind, body, { onField } = {}) {
    const cur = problemRef.current
    setBusy(kind)
    let res
    try {
      res = await api.oncallRoutines.updateProblem(cur.id, { version: cur.version, ...body })
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    }
    if (alive.current) setBusy(null)
    if (res === null) return null
    if (res?.success) {
      const next = problemOf(res) || { ...cur, ...body }
      cbRef.current.onChange(next)
      return next
    }
    const code = codeOf(res)
    if (code === 'CONCURRENT_MODIFICATION') {
      const current = res.current && typeof res.current === 'object' && res.current.id != null ? res.current : null
      if (current) {
        cbRef.current.onChange(current)
        const who = current.updated_by_name || current.resolved_by_name
        toast.info(who ? t('or.prob.changedBy', who) : t('or.prob.changedMeanwhile'))
      } else {
        toast.error(serverText(res, t, 'or.prob.changedMeanwhile'), { details: res })
      }
      return null
    }
    if (code === 'PROBLEM_NOT_FOUND') {
      cbRef.current.onDelete(cur.id)
      toast.info(t('or.prob.goneMeanwhile'))
      return null
    }
    const fields = serverFieldErrors(res)
    if (fields && onField) { onField(fields); return null }
    if (isUnsureResult(res)) { toast.error(t('or.prob.unsureUpdate'), { details: res }); return null }
    toast.error(serverText(res, t, 'or.prob.saveRefused'), { details: res })
    return null
  }

  /** "Geri al" (çözüldü bildiriminden): kart listeden kalkmış olsa da çalışır. */
  async function undoResolve(resolvedProblem) {
    let res
    try {
      res = await api.oncallRoutines.updateProblem(resolvedProblem.id, { version: resolvedProblem.version, status: 'OPEN' })
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    }
    if (res === null) return
    if (res?.success) {
      cbRef.current.onChange(problemOf(res) || { ...resolvedProblem, status: 'OPEN', resolved_by_name: null, resolved_at: null })
      return
    }
    if (codeOf(res) === 'CONCURRENT_MODIFICATION' && res.current?.id != null) {
      cbRef.current.onChange(res.current)
      toast.info(t('or.prob.undoChanged'))
      return
    }
    toast.error(serverText(res, t, 'or.prob.saveRefused'), { details: res })
  }

  async function confirmResolve() {
    const text = resolveNote.trim()
    if (text.length > RESOLUTION_NOTE_MAX) {
      setResolveProblem(t('or.prob.resolutionTooLong', new Intl.NumberFormat(lang === 'en' ? 'en-GB' : 'tr-TR').format(RESOLUTION_NOTE_MAX)))
      return
    }
    setResolveProblem(null)
    const next = await patch('resolve', { status: 'RESOLVED', resolution_note: text || null }, {
      onField: (f) => setResolveProblem(f.resolution_note || f.status || Object.values(f)[0]),
    })
    if (!next) return
    if (alive.current) { setMode(null); setResolveNote('') }
    toast.action(t('or.prob.resolvedToast'), { label: t('or.prob.undo'), onAction: () => undoResolve(next), type: 'success' })
  }

  async function reopen() {
    const next = await patch('reopen', { status: 'OPEN' })
    if (next) toast.success(t('or.prob.reopenedToast'))
  }

  function startEdit() {
    setEditSeverity(problem.severity || 'MEDIUM')
    setEditNote(problem.note || '')
    setEditProblem(null)
    setMode('edit')
  }

  async function saveEdit() {
    const text = editNote.trim()
    const bad = problemNoteProblem(text, t, lang)
    if (bad) { setEditProblem(bad); editRef.current?.focus(); return }
    const body = {}
    if (editSeverity !== problem.severity) body.severity = editSeverity
    if (text !== String(problem.note ?? '').trim()) body.note = text
    if (!Object.keys(body).length) { setMode(null); return }    // değişiklik yok: istek gitmez
    setEditProblem(null)
    const next = await patch('edit', body, { onField: (f) => setEditProblem(f.note || f.severity || Object.values(f)[0]) })
    if (next && alive.current) setMode(null)
  }

  async function remove() {
    const yes = await showConfirm({
      title: t('or.prob.deleteTitle'), message: t('or.prob.deleteBody'),
      confirmText: t('or.prob.delete'), cancelText: t('or.prob.cancel'), variant: 'danger',
    })
    if (!yes) return
    const id = problemRef.current.id
    setBusy('delete')
    let res
    try {
      res = await api.oncallRoutines.deleteProblem(id)
    } catch {
      res = { success: false, status: 0, code: 'NETWORK_ERROR' }
    }
    if (alive.current) setBusy(null)
    if (res === null) return
    if (res?.success || codeOf(res) === 'PROBLEM_NOT_FOUND') {
      cbRef.current.onDelete(id)
      toast.success(t('or.prob.deleted'))
      return
    }
    toast.error(isUnsureResult(res) ? t('or.prob.unsureDelete') : serverText(res, t, 'or.prob.deleteRefused'), { details: res })
  }

  const formKeys = (onSubmit, onCancel) => (e) => {
    if (e.isComposing || e.keyCode === 229) return
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); e.stopPropagation(); onSubmit() }
    else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onCancel() }
  }

  const note = String(problem.note ?? '')
  const long = noteIsLong(note)
  const age = resolved ? null : ageText(t, problem.age_days)
  const createdStamp = stampOf(problem.created_at)
  const updatedStamp = stampOf(problem.updated_at)
  const resolvedStamp = stampOf(problem.resolved_at)
  const noteId = `${baseId}-note`
  const sevLabelId = `${baseId}-sev`
  const anyBusy = busy != null

  return (
    <article data-slot="or-problem" data-id={problem.id} data-severity={problem.severity} data-status={problem.status}
      data-highlighted={highlighted ? 'true' : undefined} aria-busy={anyBusy ? 'true' : undefined}
      className={cn('flex min-w-0 scroll-mt-24 flex-col gap-2.5 rounded-lg border bg-card p-3 transition-shadow',
        resolved && 'bg-muted/30', highlighted && 'ring-2 ring-primary/50')}>
      <header className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <SeverityBadge severity={problem.severity} />
        {resolved && (
          <Badge variant="outline" data-slot="or-problem-resolved-badge"
            className="gap-1 rounded-md border-success/30 bg-success/10 font-semibold text-success">
            <CheckCircle2 aria-hidden="true" />{t('or.prob.resolvedBadge')}
          </Badge>
        )}
        {age && <span className="text-xs text-muted-foreground tabular-nums" data-slot="or-problem-age">{age}</span>}
        {problem.day && (
          <span className="text-xs text-muted-foreground tabular-nums">{t('or.prob.foundOn', shortDate(problem.day, true))}</span>
        )}
      </header>

      <p className="text-sm font-medium [overflow-wrap:anywhere]" data-slot="or-problem-task">
        {problem.task_title
          ? <>{problem.task_title}{problem.section_title && <span className="font-normal text-muted-foreground"> · {problem.section_title}</span>}</>
          : <span className="text-muted-foreground">{t('or.prob.generalLabel')}</span>}
      </p>

      {mode !== 'edit' && (
        <div className="flex min-w-0 flex-col items-start gap-1">
          <p id={noteId} data-slot="or-problem-note-text"
            className={cn('w-full text-sm whitespace-pre-wrap [overflow-wrap:anywhere]', long && !expanded && 'line-clamp-6')}>
            {note}
          </p>
          {long && (
            <Button type="button" variant="link" size="sm" aria-expanded={expanded} aria-controls={noteId}
              data-slot="or-problem-note-more" className="h-auto p-0 text-xs pointer-coarse:min-h-10"
              onClick={() => setExpanded((v) => !v)}>
              {expanded ? t('or.prob.less') : t('or.prob.more')}
            </Button>
          )}
        </div>
      )}

      {resolved && problem.resolution_note && (
        <div className="rounded-md bg-success/10 px-2.5 py-2" data-slot="or-problem-resolution">
          <p className="text-xs font-medium text-success">{t('or.prob.resolutionLabel')}</p>
          <p className="text-sm whitespace-pre-wrap [overflow-wrap:anywhere]">{problem.resolution_note}</p>
        </div>
      )}

      <ProblemImages problem={problem} canWork={editable} onChange={(p) => cbRef.current.onChange(p)} />

      {problem.incident && (
        <div className="flex min-w-0 flex-wrap items-center gap-2"><IncidentChip incident={problem.incident} /></div>
      )}

      {mode === 'edit' && (
        <div className="flex min-w-0 flex-col gap-2 rounded-md border bg-muted/30 p-2.5" data-slot="or-problem-edit-form"
          onKeyDown={formKeys(saveEdit, () => setMode(null))}>
          <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
            <span id={sevLabelId} className="text-xs font-medium text-muted-foreground">{t('or.prob.severity')}</span>
            <SeverityToggle value={editSeverity} onChange={setEditSeverity} labelledBy={sevLabelId}
              disabled={busy === 'edit'} slot="or-problem-edit-severity" />
          </div>
          <Label htmlFor={`${baseId}-edit`} className="text-xs text-muted-foreground">{t('or.prob.noteLabel')}</Label>
          <Textarea ref={editRef} id={`${baseId}-edit`} value={editNote} rows={3} disabled={busy === 'edit'}
            data-slot="or-problem-edit-note" aria-invalid={editProblem ? 'true' : undefined}
            className="min-h-20 resize-y [overflow-wrap:anywhere]"
            onChange={(e) => { setEditNote(e.target.value); if (editProblem) setEditProblem(null) }} />
          {editProblem && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert">{editProblem}</p>}
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" size="sm" variant="ghost" className={ACTION} disabled={busy === 'edit'} onClick={() => setMode(null)}>
              {t('or.prob.cancel')}
            </Button>
            <Button type="button" size="sm" className={ACTION} disabled={busy === 'edit'} data-slot="or-problem-edit-save" onClick={saveEdit}>
              {busy === 'edit' && <Spinner />}{t('or.prob.save')}
            </Button>
          </div>
        </div>
      )}

      {mode === 'resolve' && (
        <div className="flex min-w-0 flex-col gap-2 rounded-md border bg-muted/30 p-2.5" data-slot="or-problem-resolve-form"
          onKeyDown={formKeys(confirmResolve, () => setMode(null))}>
          <Label htmlFor={`${baseId}-res`} className="text-xs text-muted-foreground">{t('or.prob.resolutionPrompt')}</Label>
          <Textarea ref={resolveRef} id={`${baseId}-res`} value={resolveNote} rows={2} disabled={busy === 'resolve'}
            data-slot="or-problem-resolution-note" placeholder={t('or.prob.resolutionPlaceholder')}
            aria-invalid={resolveProblem ? 'true' : undefined} className="min-h-16 resize-y [overflow-wrap:anywhere]"
            onChange={(e) => { setResolveNote(e.target.value); if (resolveProblem) setResolveProblem(null) }} />
          {resolveProblem && <p className="text-sm text-destructive [overflow-wrap:anywhere]" role="alert">{resolveProblem}</p>}
          <div className="flex flex-wrap justify-end gap-2">
            <Button type="button" size="sm" variant="ghost" className={ACTION} disabled={busy === 'resolve'} onClick={() => setMode(null)}>
              {t('or.prob.cancel')}
            </Button>
            <Button type="button" size="sm" className={ACTION} disabled={busy === 'resolve'} data-slot="or-problem-resolve-confirm"
              onClick={confirmResolve}>
              {busy === 'resolve' ? <Spinner /> : <CheckCircle2 aria-hidden="true" />}{t('or.prob.resolveConfirm')}
            </Button>
          </div>
        </div>
      )}

      <footer className="flex min-w-0 flex-col gap-2">
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="or-problem-meta">
          {problem.created_by_name && t('or.prob.createdMeta', problem.created_by_name, createdStamp)}
          {problem.updated_by_name && updatedStamp && <> · {t('or.prob.updatedMeta', problem.updated_by_name, updatedStamp)}</>}
          {resolved && problem.resolved_by_name && <> · {t('or.prob.resolvedMeta', problem.resolved_by_name, resolvedStamp)}</>}
        </p>
        {editable && mode == null && (
          <div className="flex min-w-0 flex-wrap items-center gap-2" data-slot="or-problem-actions">
            {resolved ? (
              <Button type="button" size="sm" variant="outline" className={ACTION} disabled={anyBusy}
                data-slot="or-problem-reopen" onClick={reopen}>
                {busy === 'reopen' ? <Spinner /> : <RotateCcw aria-hidden="true" />}{t('or.prob.reopen')}
              </Button>
            ) : (
              <Button type="button" size="sm" variant="outline" className={ACTION} disabled={anyBusy}
                data-slot="or-problem-resolve" onClick={() => { setResolveProblem(null); setMode('resolve') }}>
                <CheckCircle2 aria-hidden="true" />{t('or.prob.resolve')}
              </Button>
            )}
            <Button type="button" size="sm" variant="ghost" className={ACTION} disabled={anyBusy}
              data-slot="or-problem-edit" onClick={startEdit}>
              <Pencil aria-hidden="true" />{t('or.prob.edit')}
            </Button>
            {!problem.incident && (
              <ProblemIncidentButton teamId={teamId} teamName={teamName} problem={problem} disabled={anyBusy}
                onChange={(p) => cbRef.current.onChange(p)} />
            )}
            {problem.can_delete === true && (
              <Button type="button" size="sm" variant="ghost" disabled={anyBusy} data-slot="or-problem-delete"
                className={cn('text-destructive hover:text-destructive', ACTION)} onClick={remove}>
                {busy === 'delete' ? <Spinner /> : <Trash2 aria-hidden="true" />}{t('or.prob.delete')}
              </Button>
            )}
          </div>
        )}
      </footer>
    </article>
  )
}
