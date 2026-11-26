/**
 * Nöbet Rutinleri — görev satırı. Hızlı akış: kutuya (ya da başlığa) tek tık = yapıldı ↔ bekliyor; not, sorun ve atlama
 * satırın İÇİNDE açılır (pencere yok). Bağlantılar çip, "Tümünü aç" hepsini yeni sekmede açar (açılır pencere engeli
 * satırın altında söylenir). Yapılan görev üstü çizilmez, renkli şerit yoktur: onay işareti + soluk başlık.
 *
 * `memo` + kararlı işlevler: bir satırda işaret ya da yazı diğer satırları yeniden çizmez.
 */
import { memo, useId, useRef, useState } from 'react'
import {
  CircleCheck, ExternalLink, StickyNote, TriangleAlert, History, PencilLine, SkipForward, Eraser, CircleAlert, Clock3,
  Undo2, Ban,
} from 'lucide-react'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { safeHref } from '../../utils/safeHref.js'
import { SKIP_REASON_MAX, SKIP_REASON_MIN, hmOf, isoWeekday, itemTiming, keyOf, shortDate } from './routineModel.js'
import SignalChips from './SignalChips.jsx'
import TaskNote from './TaskNote.jsx'
import { PersonName } from './routineUi.jsx'
import ProblemComposer from './ProblemComposer.jsx'

/** Bağlantı etiketi boşsa adresin sunucu + yolu (sorgu dizesi gösterilmez). */
export function linkLabel(link) {
  if (link?.label && String(link.label).trim()) return String(link.label).trim()
  try {
    const u = new URL(String(link?.url || ''))
    return `${u.host}${u.pathname === '/' ? '' : u.pathname}`
  } catch { return String(link?.url || '') }
}

/** Satırın DOM kimliği (klavye gezintisi / odak). */
export const rowCheckSelector = (key) => `[data-slot="or-task-check"][data-key="${CSS.escape ? CSS.escape(key) : key}"]`

/** Odak dönüşünün yeniden denemeleri: panel kayıttan SONRA (zaman uyumsuz) kapanırsa kaldırılması sonraki görevde işlenir. */
export const FOCUS_RETURN_TRIES = 6
export const FOCUS_RETURN_STEP_MS = 50

/**
 * Satırın paneli (atla / not / sorun) kapanınca odak satıra döner — onay kutusuna, o kapalıysa satırın ilk etkin düğmesine
 * (klavyeyle hızlı tik akışı `j` / `k` kaldığı yerden sürsün). Kullanıcı odağı başka bir yere taşıdıysa dokunulmaz. Odak
 * hâlâ kapanan panelin İÇİNDEYSE (kayıt bitince kapanan not / sorun: kaldırma henüz işlenmedi) sınırlı sayıda yeniden bakılır.
 */
export function returnFocusToRow(key, doc = globalThis.document) {
  if (!key || !doc) return
  const esc = CSS.escape ? CSS.escape(key) : key
  const step = (left, delay) => setTimeout(() => {
    const a = doc.activeElement
    if (a && a !== doc.body && a.isConnected) {
      if (left > 0 && a.closest?.('[data-slot="or-task-panel"],[data-slot="or-skip"]')?.closest?.(`[data-slot="or-task"][data-key="${esc}"]`)) {
        step(left - 1, FOCUS_RETURN_STEP_MS)
      }
      return
    }
    const check = doc.querySelector(rowCheckSelector(key))
    const target = check && !check.disabled
      ? check
      : doc.querySelector(`[data-slot="or-task"][data-key="${esc}"] button:not([disabled])`)
    target?.focus?.()
  }, delay)
  step(FOCUS_RETURN_TRIES, 0)
}

/** Açıklama uzun mu ("Devamı" düğmesi). */
const longText = (s) => !!s && (s.length > 220 || s.split('\n').length > 3)

/**
 * Bütün bağlantıları açar. `window.open(url, '_blank')` engellenirse null döner (noopener ile her zaman null döndüğü için
 * kullanılmaz; açılan pencerenin `opener`'ı elle kesilir). Dönüş: engellenen bağlantı sayısı.
 */
export function openAllLinks(links, win = globalThis.window) {
  let blocked = 0
  for (const l of links || []) {
    const href = safeHref(l?.url, ['http:', 'https:'])
    if (!href) continue
    let w = null
    try { w = win.open(href, '_blank') } catch { w = null }
    if (!w) { blocked += 1; continue }
    try { w.opener = null } catch { /* çapraz köken */ }
  }
  return blocked
}

function TaskRowImpl({
  item, today, nowMs, contextDate, teamId, editable, canEditTemplate = false, signals = null, signalsLoading = false, showSignals = true,
  panel = null, onToggle, onSkip, onPanel, onNoteSaved, onClearNote, onProblemCreated, onImagesDone, onHistory,
  onEditTemplate,
}) {
  const t = useT()
  const uid = useId()
  const key = keyOf(item)
  const [descOpen, setDescOpen] = useState(false)
  const [blocked, setBlocked] = useState(0)
  const [skipText, setSkipText] = useState('')
  const [skipErr, setSkipErr] = useState(null)
  const skipRef = useRef(null)
  const { overdue, notBefore } = itemTiming(item, { today, nowMs })
  const done = item.state === 'DONE'
  const skipped = item.state === 'SKIPPED'
  const missed = item.state === 'MISSED'
  const links = Array.isArray(item.links) ? item.links : []
  const sigKeys = Array.isArray(item.signals) ? item.signals : []
  const problems = Number(item.problems_open) || 0
  const hasNote = !!(item.note && String(item.note).trim())
  const canTouch = !!editable
  const titleId = `${uid}-title`
  const checkId = `${uid}-check`
  const metaId = `${uid}-meta`

  const togglePanel = (p) => onPanel(key, panel === p ? null : p)
  const submitSkip = () => {
    const r = skipText.trim()
    if (r.length < SKIP_REASON_MIN) { setSkipErr(t('or.task.skipTooShort', SKIP_REASON_MIN)); skipRef.current?.focus(); return }
    if (r.length > SKIP_REASON_MAX) { setSkipErr(t('or.task.skipTooLong', SKIP_REASON_MAX)); return }
    setSkipErr(null)
    setSkipText('')
    onSkip(item, r)
    onPanel(key, null)
  }

  const weekday = isoWeekday(item.due_day)
  const kindChip = item.period_kind === 'WEEK'
    ? t('or.task.dueWeekday', t(`or.weekdayShort.${weekday}`), shortDate(item.due_day))
    : item.period_kind === 'MONTH' ? t('or.task.dueMonth', shortDate(item.due_day)) : null

  const menu = [
    canTouch && !skipped && { label: t('or.task.skip'), icon: <SkipForward aria-hidden="true" />, onClick: () => onPanel(key, 'skip') },
    canTouch && (skipped || missed) && { label: t('or.task.markPending'), icon: <Undo2 aria-hidden="true" />, onClick: () => onToggle(item, 'PENDING') },
    canTouch && hasNote && { label: t('or.task.clearNote'), icon: <Eraser aria-hidden="true" />, onClick: () => onClearNote(item) },
    onHistory && { label: t('or.task.history'), icon: <History aria-hidden="true" />, onClick: () => onHistory(item) },
    canEditTemplate && !item.removed && { label: t('or.task.editTemplate'), icon: <PencilLine aria-hidden="true" />, onClick: () => onEditTemplate(item) },
  ].filter(Boolean)

  return (
    <li data-slot="or-task" data-key={key} data-state={item.state} data-overdue={overdue || undefined}
      data-required={item.required ? 'true' : 'false'} data-removed={item.removed || undefined}
      className={cn('group/task rounded-lg px-2 py-2.5 transition-colors @md:px-3',
        'focus-within:bg-muted/50 hover:bg-muted/40',
        panel && 'bg-muted/40')}>
      <div className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 @md:grid-cols-[auto_minmax(0,1fr)_auto]">
        <div className="pt-0.5">
          <Checkbox
            id={checkId}
            data-slot="or-task-check" data-key={key}
            checked={done}
            disabled={!canTouch}
            aria-labelledby={titleId}
            aria-describedby={metaId}
            onCheckedChange={() => onToggle(item, done ? 'PENDING' : 'DONE')}
            className={cn('size-5 rounded-md pointer-coarse:after:-inset-[11px]',
              'data-[state=checked]:border-success data-[state=checked]:bg-success data-[state=checked]:text-white',
              overdue && !done && 'border-destructive/70')}
          />
        </div>

        <div className="min-w-0">
          <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            <label id={titleId} htmlFor={checkId}
              className={cn('cursor-pointer text-sm leading-snug font-medium [overflow-wrap:anywhere] select-text',
                (done || skipped) && 'text-muted-foreground', item.removed && 'text-muted-foreground italic',
                !canTouch && 'cursor-default')}>
              {item.title}
            </label>
            {!item.required && (
              <Badge variant="outline" className="h-auto min-h-5 rounded-md px-1.5 text-[11px] font-normal whitespace-normal text-muted-foreground">
                {t('or.task.optional')}
              </Badge>
            )}
            {kindChip && (
              <Badge variant="outline" data-slot="or-task-due" className="h-auto rounded-md px-1.5 text-[11px] font-normal whitespace-normal">
                {kindChip}
              </Badge>
            )}
            {item.snapshot_differs && (
              <HintPopover content={t('or.task.snapshotDiffers')} contentLabel={t('or.task.snapshotDiffersLabel')}
                triggerClassName="text-[11px] text-muted-foreground underline decoration-dotted underline-offset-2">
                {t('or.task.snapshotDiffersShort')}
              </HintPopover>
            )}
          </div>

          {item.description && (
            <div className="mt-1">
              <p data-slot="or-task-desc" className={cn('m-0 text-xs leading-relaxed whitespace-pre-wrap text-muted-foreground [overflow-wrap:anywhere]',
                !descOpen && longText(item.description) && 'line-clamp-3')}>
                {item.description}
              </p>
              {longText(item.description) && (
                <Button type="button" variant="link" size="sm" className="h-auto p-0 text-xs pointer-coarse:min-h-10"
                  aria-expanded={descOpen} onClick={() => setDescOpen((v) => !v)}>
                  {descOpen ? t('or.task.less') : t('or.task.more')}
                </Button>
              )}
            </div>
          )}

          {links.length > 0 && (
            <div data-slot="or-task-links" className="mt-2 flex flex-wrap items-center gap-1.5">
              {links.map((l, i) => {
                const href = safeHref(l?.url, ['http:', 'https:'])
                const label = linkLabel(l)
                return href ? (
                  <Button key={i} asChild variant="outline" size="sm"
                    className="h-auto min-h-7 max-w-full gap-1.5 rounded-md px-2 py-1 text-xs font-normal whitespace-normal pointer-coarse:min-h-10">
                    <a href={href} target="_blank" rel="noopener noreferrer" data-slot="or-task-link">
                      <ExternalLink aria-hidden="true" className="size-3 shrink-0" />
                      <span className="text-left [overflow-wrap:anywhere]">{label}</span>
                    </a>
                  </Button>
                ) : (
                  <span key={i} className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{label}</span>
                )
              })}
              {links.length > 1 && (
                <Button type="button" variant="ghost" size="sm" data-slot="or-task-open-links"
                  className="h-7 px-2 text-xs text-primary pointer-coarse:h-10"
                  onClick={() => setBlocked(openAllLinks(links))}>
                  {t('or.task.openAll', links.length)}
                </Button>
              )}
            </div>
          )}
          {blocked > 0 && (
            <p data-slot="or-links-blocked" role="status" className="mt-1.5 mb-0 flex items-start gap-1.5 text-xs text-amber-800 dark:text-amber-200">
              <Ban aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
              <span>{t('or.task.popupBlocked')}</span>
            </p>
          )}

          {showSignals && sigKeys.length > 0 && (
            <div className="mt-2"><SignalChips keys={sigKeys} signals={signals} loading={signalsLoading} /></div>
          )}

          <div id={metaId} data-slot="or-task-meta" className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
            {done && (
              <span className="inline-flex flex-wrap items-center gap-1 text-success">
                <CircleCheck aria-hidden="true" className="size-3.5" />
                <span className="sr-only">{t('or.task.doneSr')}</span>
                <PersonName name={item.state_by_name} isMe={!!item.state_by_me} className="text-foreground/80" />
                {item.state_at && <span className="tabular-nums text-muted-foreground">· {hmOf(item.state_at)}</span>}
              </span>
            )}
            {skipped && (
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <Badge variant="warning" className="h-auto min-h-5 rounded-md px-1.5 text-[11px] whitespace-normal">{t('or.task.skipped')}</Badge>
                <span className="[overflow-wrap:anywhere]">{item.skip_reason}</span>
                {(item.state_by_name || item.state_by_me) && <span className="[overflow-wrap:anywhere]">· {item.state_by_name || t('or.me')}</span>}
              </span>
            )}
            {missed && <Badge variant="outline" className="h-auto min-h-5 rounded-md border-destructive/40 px-1.5 text-[11px] whitespace-normal text-destructive">{t('or.task.missed')}</Badge>}
            {overdue && !done && (
              <Badge variant="destructive" data-slot="or-task-overdue" className="h-auto min-h-5 rounded-md px-1.5 text-[11px] whitespace-normal"
                title={item.window_to ? t('or.task.overdueTitle', item.window_to) : undefined}>
                <CircleAlert aria-hidden="true" />
                {item.window_to && item.due_day === today ? t('or.task.overdueAt', item.window_to) : t('or.task.overdue')}
              </Badge>
            )}
            {notBefore && !done && (
              <span className="inline-flex items-center gap-1" data-slot="or-task-not-before"><Clock3 aria-hidden="true" className="size-3.5" />{item.window_from ? t('or.task.notBefore', item.window_from) : t('or.task.dueLater')}</span>
            )}
            {!overdue && !done && !skipped && item.window_to && item.period_kind === 'DAY' && item.due_day === today && (
              <span className="tabular-nums">{t('or.task.until', item.window_to)}</span>
            )}
            {item.removed && <span className="italic">{t('or.task.removed')}</span>}
            {!done && !skipped && !missed && !overdue && !notBefore && !item.removed && !(item.window_to && item.due_day === today) && (
              <span className="sr-only">{t('or.task.pendingSr')}</span>
            )}
          </div>

          {hasNote && panel !== 'note' && (
            <Button type="button" variant="outline" data-slot="or-task-note-text" disabled={!canTouch}
              title={t('or.task.noteEdit')} onClick={() => togglePanel('note')}
              className="mt-1.5 flex h-auto w-full flex-col items-start gap-0.5 rounded-md border-dashed bg-muted/30 px-2.5 py-1.5 text-left text-xs font-normal whitespace-pre-wrap text-foreground/90 disabled:opacity-100">
              <span className="w-full [overflow-wrap:anywhere]">{item.note}</span>
              <span className="text-[11px] text-muted-foreground">{[item.note_by_name, hmOf(item.note_at)].filter(Boolean).join(' · ')}</span>
            </Button>
          )}
        </div>

        <div className="col-start-2 mt-2 flex flex-wrap items-start gap-0.5 @md:col-start-3 @md:row-start-1 @md:mt-0 @md:flex-nowrap">
          <Button type="button" variant="ghost" size="sm" data-slot="or-task-note-toggle" aria-expanded={panel === 'note'}
            aria-label={hasNote ? t('or.task.noteEdit') : t('or.task.noteAdd')} disabled={!canTouch && !hasNote}
            onClick={() => togglePanel('note')}
            className={cn('relative h-8 gap-1.5 px-2 text-xs text-muted-foreground pointer-coarse:h-10', panel === 'note' && 'bg-accent text-foreground')}>
            <StickyNote aria-hidden="true" />
            <span className="@md:sr-only @lg:not-sr-only">{t('or.task.note')}</span>
            {hasNote && <span aria-hidden="true" className="absolute top-1.5 right-1.5 size-1.5 rounded-full bg-primary" />}
          </Button>
          <Button type="button" variant="ghost" size="sm" data-slot="or-task-problem" aria-expanded={panel === 'problem'}
            aria-label={problems > 0 ? t('or.task.problemWithCount', problems) : t('or.task.problem')} disabled={!editable}
            onClick={() => togglePanel('problem')}
            className={cn('h-8 gap-1.5 px-2 text-xs text-muted-foreground pointer-coarse:h-10',
              problems > 0 && 'text-amber-700 dark:text-amber-300', panel === 'problem' && 'bg-accent text-foreground')}>
            <TriangleAlert aria-hidden="true" />
            <span className="@md:sr-only @lg:not-sr-only">{t('or.task.problem')}</span>
            {problems > 0 && <span className="tabular-nums font-semibold">{problems}</span>}
          </Button>
          <KebabMenu items={menu} rowLabel={item.title} label={t('or.task.more')} />
        </div>
      </div>

      {panel === 'skip' && canTouch && (
        <div className="mt-2 ml-8 flex flex-col gap-1.5 @md:ml-8" data-slot="or-skip">
          <div className="flex flex-col gap-2 @md:flex-row">
            <Input ref={skipRef} autoFocus data-slot="or-skip-reason" value={skipText} maxLength={SKIP_REASON_MAX}
              aria-label={t('or.task.skipReasonLabel', item.title)} aria-invalid={!!skipErr || undefined}
              placeholder={t('or.task.skipPlaceholder')}
              onChange={(e) => { setSkipText(e.target.value); if (skipErr) setSkipErr(null) }}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); submitSkip() }
                if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onPanel(key, null) }
              }}
              className="h-9 text-sm pointer-coarse:h-10 @md:flex-1" />
            <div className="flex gap-2">
              <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={submitSkip} data-slot="or-skip-save">{t('or.task.skipSave')}</Button>
              <Button type="button" size="sm" variant="ghost" className="pointer-coarse:h-10" onClick={() => onPanel(key, null)}>{t('or.cancel')}</Button>
            </div>
          </div>
          {skipErr && <p className="m-0 text-xs text-destructive" role="alert">{skipErr}</p>}
        </div>
      )}

      {panel === 'note' && (
        <div className="mt-2 @md:ml-8" data-slot="or-task-panel">
          <TaskNote teamId={teamId} contextDate={contextDate} item={item} readOnly={!canTouch}
            onSaved={(data) => onNoteSaved(key, data)} onClose={() => onPanel(key, null)} />
        </div>
      )}

      {panel === 'problem' && editable && (
        <div className="mt-2 @md:ml-8" data-slot="or-task-panel">
          <ProblemComposer teamId={teamId} date={contextDate} item={item} compact
            onCreated={onProblemCreated} onImagesDone={onImagesDone} onClose={() => onPanel(key, null)} />
        </div>
      )}
    </li>
  )
}

/** Tek canlı sayacın görünür alanları aynı mı (her yoklamada yeni nesne gelir; değer değişmediyse satır yeniden çizilmez). */
function sameSignal(a, b) {
  if (a === b) return true
  if (!a || !b) return false
  return a.value === b.value && a.error === b.error && a.partial === b.partial && a.tone === b.tone && a.hidden === b.hidden
    && (a.link?.tab ?? null) === (b.link?.tab ?? null) && JSON.stringify(a.link?.params ?? null) === JSON.stringify(b.link?.params ?? null)
}

/**
 * Satırın yeniden çizilip çizilmeyeceği: sayfa düzeyindeki `signals` haritasından yalnız BU satırın anahtarları, `nowMs`'ten
 * yalnız gecikme / "henüz değil" durumu karşılaştırılır (30 sn'lik saat ve 60 sn'lik sayaç yenilemesi tüm satırları çizmez).
 */
export function sameRowProps(a, b) {
  const ka = Object.keys(a)
  const kb = Object.keys(b)
  if (ka.length !== kb.length) return false
  for (const k of kb) {
    if (k === 'signals' || k === 'nowMs') continue
    if (a[k] !== b[k]) return false
  }
  const keys = Array.isArray(b.item?.signals) ? b.item.signals : []
  if (keys.length && a.showSignals !== false) {
    if ((a.signals == null) !== (b.signals == null)) return false
    for (const k of keys) if (!sameSignal(a.signals?.[k], b.signals?.[k])) return false
  }
  if (a.nowMs !== b.nowMs) {
    const ta = itemTiming(a.item, { today: a.today, nowMs: a.nowMs })
    const tb = itemTiming(b.item, { today: b.today, nowMs: b.nowMs })
    if (ta.overdue !== tb.overdue || ta.notBefore !== tb.notBefore) return false
  }
  return true
}

const TaskRow = memo(TaskRowImpl, sameRowProps)
export default TaskRow
