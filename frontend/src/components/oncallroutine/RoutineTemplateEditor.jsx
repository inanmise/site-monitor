import { memo, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowUp, Check, ChevronRight, ChevronsDownUp, ChevronsUpDown, FilePlus2, FolderInput, GripVertical, History,
  Import, ListTodo, Plus, RotateCcw, Save, Sparkles, Trash2, X,
} from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import Field from '../ui/Field.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Switch } from '@/components/shadcn/switch'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Spinner } from '@/components/shadcn/spinner'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/shadcn/empty'
import { cn } from '@/lib/utils'
import { useStickyBottomBar } from '../../hooks/useStickyBottomBar.js'
import { useUnsavedWork } from '../../utils/unsavedWork.js'
import { personalKey, storageOwner } from '../../utils/personalStorage.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { isUnsureResult } from '../../utils/unsureResult.js'
import { hasOpenDialog } from '../techerror/capture/panelWindow.js'
import { SIGNAL_KEYS, codeOf, errorKey, thrownResult } from './routineModel.js'
import {
  FREQUENCIES, MONTHLY_RULES, NTHS, WEEKDAYS, addSection, addTask, applyImport, canonicalJson, collectIds, countActive,
  countTasks, dirtyCount, emptyTemplate, errorCountsBySection, errorSectionId, errorTaskId, insertTask, isDirty,
  isSectionLevelKey, istanbulStamp, locateTask, mapServerErrors, moveSection, moveTask, moveTaskTo, normalizeTemplate,
  pasteUrlIntoLink, reidSections, removeSection, removeTask, resolveLimits, taskErrorSignature, taskSummary, toWire,
  toggleSignal, updateSection, updateTask, validateTemplate, visibleErrors, windowLabel, withFrequency, withMonthlyRule,
} from './templateEditorModel.js'
import WikiImportDialog from './WikiImportDialog.jsx'
import TemplateVersionsDialog from './TemplateVersionsDialog.jsx'

/**
 * NÖBET RUTİNİ ŞABLON DÜZENLEYİCİSİ (2026-11-26, `or_view=template`; sayfa bunu tembel yükler). Takımın kontrol listesi:
 * sıralı bölümler, her bölümde sıralı görevler (başlık, sıklık, zorunlu / etkin, saat aralığı, açıklama, bağlantılar,
 * canlı sayaçlar). Kayıt sürüm denetimlidir (409 → "son hâli yükle", taslak önce kişisel kayda yedeklenir); hiçbir
 * değişiklik sessizce ezilmez. Kaydedilmemiş taslak bu tarayıcıda yedeklenir (`sm.or.tpl.<takım>`, 1 sn sonra) ve
 * yeniden açılışta ÖNERİLİR — kendiliğinden uygulanmaz.
 *
 * <p>Hızlı akış: "Görev ekle" yeni başlığa odaklanır; ↑ ↓ (dokunmatikte 40 px) ve "Bölüme taşı" menüsü her yerde; fareyle
 * sürükle-bırak yalnız ince işaretçide (tutamak dokunmatikte çizilmez). Ctrl/⌘+S kaydeder (açık pencere yokken).
 * Doğrulama sunucu kurallarının aynası: geçersiz içerik hemen, "boş bırakılamaz" ancak kayıt denemesinden sonra görünür.
 */
export const TPL_BACKUP_PREFIX = 'sm.or.tpl.'

const backupKey = (teamId) => personalKey(`${TPL_BACKUP_PREFIX}${teamId}`)

export function readTemplateBackup(teamId) {
  try {
    const raw = globalThis.localStorage?.getItem(backupKey(teamId))
    if (!raw) return null
    const v = JSON.parse(raw)
    return v && typeof v === 'object' && v.template && Array.isArray(v.template.sections) ? v : null
  } catch {
    return null
  }
}

function writeBackup(teamId, payload) {
  // Sahip işareti yoksa (çıkış / oturum kesildi) hiçbir şey yazılmaz — kullanıcısız anahtarda içerik kalmasın
  if (!storageOwner()) return false
  try { globalThis.localStorage?.setItem(backupKey(teamId), JSON.stringify(payload)); return true } catch { return false }
}

function dropBackup(teamId) {
  try { globalThis.localStorage?.removeItem(backupKey(teamId)) } catch { /* depolama yok */ }
}

/** Ctrl/⌘+S (Alt / Shift yok). Latin olmayan düzende fiziksel S tuşu da sayılır. */
function isSaveKey(e) {
  if (!e || !(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return false
  const key = typeof e.key === 'string' ? e.key : ''
  if (key === 's' || key === 'S') return true
  return e.code === 'KeyS' && !/^[a-z]$/i.test(key)
}

const WRAP_BADGE = 'h-auto whitespace-normal rounded-md overflow-visible'
const ICON_BTN = 'pointer-coarse:size-10'

const windowText = windowLabel

// ── Satır parçaları ─────────────────────────────────────────────────────────────────────────────────────────────────

function InlineError({ id, children }) {
  if (!children) return null
  return <p id={id} className="text-xs text-destructive [overflow-wrap:anywhere]">{children}</p>
}

/** Satır içi seçici (hafta günü / aylık kural …) + alan hatası; `data-field` odak kancası. */
function ParamSelect({ name, error, label, value, onChange, disabled, children }) {
  const id = useId()
  return (
    <div data-field={name} className="flex flex-col gap-1">
      <NativeSelect value={value ?? ''} disabled={disabled} aria-label={label} aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-e` : undefined} className="min-w-[9rem]" onChange={(e) => onChange(e.target.value)}>
        {children}
      </NativeSelect>
      <InlineError id={`${id}-e`}>{error}</InlineError>
    </div>
  )
}

function FrequencyFields({ task, tp, errors, locked, onChange }) {
  const t = useT()
  const err = (k) => errors[`${tp}.${k}`]
  const options = FREQUENCIES.map((f) => ({ value: f, label: t(`or.freq.${f}`), disabled: locked }))
  const weekdayOptions = WEEKDAYS.map((d) => <NativeSelectOption key={d} value={d}>{t(`or.weekday.${d}`)}</NativeSelectOption>)
  return (
    <div className="flex flex-wrap items-start gap-x-3 gap-y-2">
      <div data-slot="or-tpl-frequency" data-field={`${tp}.frequency`} data-value={task.frequency}>
        <SegmentedControl ariaLabel={t('or.tpl.frequency')} value={task.frequency} options={options}
          onChange={(f) => onChange(withFrequency(task, f), ['frequency', 'weekday', 'monthly_rule', 'month_day', 'nth'])} />
        <InlineError>{err('frequency')}</InlineError>
      </div>
      {task.frequency === 'WEEKLY' && (
        <ParamSelect name={`${tp}.weekday`} error={err('weekday')} label={t('or.tpl.weekday')} value={task.weekday ?? 5}
          disabled={locked} onChange={(v) => onChange({ ...task, weekday: Number(v) }, ['weekday'])}>
          {weekdayOptions}
        </ParamSelect>
      )}
      {task.frequency === 'MONTHLY' && (
        <>
          <ParamSelect name={`${tp}.monthly_rule`} error={err('monthly_rule')} label={t('or.tpl.monthlyRule')}
            value={task.monthly_rule || 'FIRST_WORKDAY'} disabled={locked}
            onChange={(v) => onChange(withMonthlyRule(task, v), ['monthly_rule', 'month_day', 'nth', 'weekday'])}>
            {MONTHLY_RULES.map((r) => <NativeSelectOption key={r} value={r}>{t(`or.monthly.${r}`)}</NativeSelectOption>)}
          </ParamSelect>
          {task.monthly_rule === 'DAY' && (
            <ParamSelect name={`${tp}.month_day`} error={err('month_day')} label={t('or.tpl.monthDay')} value={task.month_day ?? 1}
              disabled={locked} onChange={(v) => onChange({ ...task, month_day: Number(v) }, ['month_day'])}>
              {Array.from({ length: 31 }, (_, i) => i + 1).map((d) => (
                <NativeSelectOption key={d} value={d}>{t('or.tpl.monthDayText', d)}</NativeSelectOption>
              ))}
            </ParamSelect>
          )}
          {task.monthly_rule === 'NTH_WEEKDAY' && (
            <>
              <ParamSelect name={`${tp}.nth`} error={err('nth')} label={t('or.tpl.nth')} value={task.nth ?? 1} disabled={locked}
                onChange={(v) => onChange({ ...task, nth: Number(v) }, ['nth'])}>
                {NTHS.map((n) => <NativeSelectOption key={n} value={n}>{t(`or.nth.${n}`)}</NativeSelectOption>)}
              </ParamSelect>
              <ParamSelect name={`${tp}.weekday`} error={err('weekday')} label={t('or.tpl.weekday')} value={task.weekday ?? 1}
                disabled={locked} onChange={(v) => onChange({ ...task, weekday: Number(v) }, ['weekday'])}>
                {weekdayOptions}
              </ParamSelect>
            </>
          )}
        </>
      )}
    </div>
  )
}

/** Saat aralığı (bölüm ya da görev): iki `time` alanı; boş = yok. */
function WindowFields({ prefix, from, to, errors, locked, onChange, hint }) {
  const t = useT()
  return (
    <>
      <Field label={t('or.tpl.windowFromLabel')} name={`${prefix}.window_from`} error={errors[`${prefix}.window_from`]} className="mb-0">
        {({ id, describedBy, invalid }) => (
          <Input id={id} type="time" value={from || ''} disabled={locked} aria-describedby={describedBy} aria-invalid={invalid}
            onChange={(e) => onChange({ window_from: e.target.value || null }, ['window_from', 'window_to'])} />
        )}
      </Field>
      <Field label={t('or.tpl.windowToLabel')} name={`${prefix}.window_to`} error={errors[`${prefix}.window_to`]} hint={hint}
        className="mb-0">
        {({ id, describedBy, invalid }) => (
          <Input id={id} type="time" value={to || ''} disabled={locked} aria-describedby={describedBy} aria-invalid={invalid}
            onChange={(e) => onChange({ window_to: e.target.value || null }, ['window_from', 'window_to'])} />
        )}
      </Field>
    </>
  )
}

function LinksEditor({ task, tp, errors, locked, limits, onChange }) {
  const t = useT()
  const links = task.links || []
  const filled = links.filter((l) => (l.url || '').trim() || (l.label || '').trim()).length
  const full = links.length >= limits.links_per_task
  const patch = (i, p) => onChange({ links: links.map((l, j) => (j === i ? { ...l, ...p } : l)) }, [`links.${i}.url`, `links.${i}.label`, 'links'])
  return (
    <div data-slot="or-tpl-links" data-field={`${tp}.links`} className="flex flex-col gap-2 @lg:col-span-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold">{t('or.tpl.links')}</span>
        <span className="text-xs tabular-nums text-muted-foreground">{t('or.tpl.countOf', filled, limits.links_per_task)}</span>
      </div>
      {links.length === 0 && <p className="text-xs text-muted-foreground">{t('or.tpl.linksEmpty')}</p>}
      {links.map((l, i) => (
        <div key={i} data-slot="or-tpl-link" data-index={i}
          className="grid grid-cols-1 gap-2 rounded-md border border-dashed border-border p-2 @md:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)_auto] @md:items-start">
          <Field name={`${tp}.links.${i}.label`} error={errors[`${tp}.links.${i}.label`]} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} value={l.label} disabled={locked} aria-label={t('or.tpl.linkLabelN', i + 1)}
                placeholder={t('or.tpl.linkLabelPh')} aria-describedby={describedBy} aria-invalid={invalid}
                onChange={(e) => patch(i, { label: e.target.value })} />
            )}
          </Field>
          <Field name={`${tp}.links.${i}.url`} error={errors[`${tp}.links.${i}.url`]} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="url" inputMode="url" value={l.url} disabled={locked} aria-label={t('or.tpl.linkUrlN', i + 1)}
                placeholder="https://" aria-describedby={describedBy} aria-invalid={invalid} spellCheck={false}
                onPaste={(e) => {
                  const next = pasteUrlIntoLink(l, e.clipboardData?.getData('text/plain'))
                  if (next) { e.preventDefault(); patch(i, next) }
                }}
                onChange={(e) => patch(i, { url: e.target.value })} />
            )}
          </Field>
          {!locked && (
            <Button type="button" variant="ghost" size="icon-sm" className={cn(ICON_BTN, 'justify-self-end')} data-slot="or-tpl-link-remove"
              aria-label={t('or.tpl.linkRemoveN', i + 1)} onClick={() => onChange({ links: links.filter((_, j) => j !== i) }, ['links'])}>
              <X aria-hidden="true" />
            </Button>
          )}
        </div>
      ))}
      <InlineError>{errors[`${tp}.links`]}</InlineError>
      {!locked && (
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" data-slot="or-tpl-link-add" disabled={full}
            onClick={() => onChange({ links: [...links, { label: '', url: '' }] }, ['links'])}>
            <Plus aria-hidden="true" /> {t('or.tpl.linkAdd')}
          </Button>
          <span className="text-xs text-muted-foreground">{full ? t('or.tpl.linksFull', limits.links_per_task) : t('or.tpl.linkPasteHint')}</span>
        </div>
      )}
    </div>
  )
}

function SignalsPicker({ task, tp, errors, locked, limits, signalDefs, onChange }) {
  const t = useT()
  const selected = task.signals || []
  const max = limits.signals_per_task
  return (
    <div data-slot="or-tpl-signals" data-field={`${tp}.signals`} className="flex flex-col gap-2 @lg:col-span-2">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-semibold">{t('or.tpl.signals')}</span>
        <span className="text-xs tabular-nums text-muted-foreground">{t('or.tpl.countOf', selected.length, max)}</span>
      </div>
      <p className="text-xs text-muted-foreground">{t('or.tpl.signalsHint')}</p>
      <div className="grid grid-cols-1 gap-2 @md:grid-cols-2">
        {signalDefs.map((d) => {
          const on = selected.includes(d.key)
          const blocked = !on && selected.length >= max
          return (
            <Button key={d.key} type="button" variant="outline" aria-pressed={on} disabled={locked || blocked}
              data-slot="or-tpl-signal" data-signal={d.key} data-selected={on ? 'true' : 'false'}
              className={cn('h-auto min-h-10 w-full flex-col items-start gap-0.5 whitespace-normal px-3 py-2 text-left',
                on && 'border-primary bg-primary/5')}
              onClick={() => onChange(toggleSignal(task, d.key, max), ['signals'])}>
              <span className="flex items-center gap-1.5 text-sm font-medium">
                {on ? <Check aria-hidden="true" className="text-primary" /> : <Plus aria-hidden="true" className="text-muted-foreground" />}
                <span className="[overflow-wrap:anywhere]">{t(`or.sig.${d.key}.label`)}</span>
              </span>
              <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">{t(`or.sig.${d.key}.desc`)}</span>
              {d.hidden && <span className="text-xs font-normal text-muted-foreground">{t('or.tpl.signalHiddenNote')}</span>}
            </Button>
          )
        })}
      </div>
      {!locked && selected.length >= max && <p className="text-xs text-muted-foreground">{t('or.tpl.signalsFull', max)}</p>}
      <InlineError>{errors[`${tp}.signals`]}</InlineError>
    </div>
  )
}

/**
 * Görev satırı (memo): başlık, sıklık, zorunlu / etkin, ↑ ↓, "Bölüme taşı" menüsü; ayrıntılar (açıklama, saat, bağlantı,
 * sayaç) katlanır. Karşılaştırma hata İMZASIYLA — hata haritası her çizimde yeni nesnedir.
 */
const TaskRow = memo(function TaskRow({
  task, sectionId, index, count, errors, locked, detailsOpen, dropPos, sectionOptions, signalDefs, limits, actions,
}) {
  const t = useT()
  const uid = useId()
  const tp = `template.${sectionId}.${task.id}`
  const title = task.title.trim() || t('or.tpl.untitledTask')
  const summary = taskSummary(task)
  const onChange = (patch, keys) => actions.patchTask(sectionId, task.id, patch, keys)
  const moveItems = sectionOptions.filter((s) => s.id !== sectionId).map((s) => ({
    label: t('or.tpl.moveToSection', s.title || t('or.tpl.untitledSection')),
    icon: <FolderInput aria-hidden="true" />,
    onClick: () => actions.moveTaskTo(task.id, s.id, null),
  }))
  return (
    <li data-slot="or-tpl-task" data-task={task.id} data-active={task.active ? 'true' : 'false'}
      className={cn('relative rounded-lg border border-border bg-card p-3 @container', !task.active && 'bg-muted/40')}
      onDragOver={(e) => actions.dragOverTask(sectionId, task.id, e)}
      onDrop={(e) => actions.dropOnTask(sectionId, task.id, e)}>
      {dropPos === 'before' && <span aria-hidden="true" className="pointer-events-none absolute inset-x-2 -top-1.5 h-0.5 rounded-full bg-primary" />}
      {dropPos === 'after' && <span aria-hidden="true" className="pointer-events-none absolute inset-x-2 -bottom-1.5 h-0.5 rounded-full bg-primary" />}
      <div className="flex items-start gap-2">
        {!locked && (
          <span draggable aria-hidden="true" title={t('or.tpl.dragHint')} data-slot="or-tpl-drag"
            className="mt-2.5 hidden shrink-0 cursor-grab text-muted-foreground active:cursor-grabbing pointer-fine:flex"
            onDragStart={(e) => actions.dragStart(task.id, e)} onDragEnd={actions.dragEnd}>
            <GripVertical className="size-4" />
          </span>
        )}
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <Field name={`${tp}.title`} error={errors[`${tp}.title`]} className="mb-0">
            {({ id, describedBy, invalid }) => (
              <Input id={id} value={task.title} disabled={locked} aria-label={t('or.tpl.taskTitleN', index + 1)}
                placeholder={t('or.tpl.taskTitlePh')} aria-describedby={describedBy} aria-invalid={invalid}
                className="font-medium" onChange={(e) => onChange({ title: e.target.value }, ['title'])} />
            )}
          </Field>
          <FrequencyFields task={task} tp={tp} errors={errors} locked={locked} onChange={(next, keys) => onChange(next, keys)} />
          <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
            <div className="flex items-center gap-2">
              <Switch id={`${uid}-req`} checked={task.required} disabled={locked} data-slot="or-tpl-required"
                onCheckedChange={(v) => onChange({ required: v }, ['required'])} />
              <Label htmlFor={`${uid}-req`} className="text-sm font-normal">{t('or.tpl.required')}</Label>
            </div>
            <div className="flex items-center gap-2">
              <Switch id={`${uid}-act`} checked={task.active} disabled={locked} data-slot="or-tpl-active"
                onCheckedChange={(v) => onChange({ active: v }, ['active'])} />
              <Label htmlFor={`${uid}-act`} className="text-sm font-normal">{t('or.tpl.active')}</Label>
            </div>
            {!task.active && <Badge variant="outline" className={WRAP_BADGE}>{t('or.tpl.inactiveBadge')}</Badge>}
          </div>
        </div>
        {!locked && (
          <div className="flex shrink-0 items-center gap-0.5">
            <Button type="button" variant="ghost" size="icon-sm" className={ICON_BTN} data-slot="or-tpl-move-up" data-scope="task"
              disabled={index === 0} aria-label={t('or.tpl.taskUp', title)} onClick={() => actions.moveTask(task.id, -1)}>
              <ArrowUp aria-hidden="true" />
            </Button>
            <Button type="button" variant="ghost" size="icon-sm" className={ICON_BTN} data-slot="or-tpl-move-down" data-scope="task"
              disabled={index >= count - 1} aria-label={t('or.tpl.taskDown', title)} onClick={() => actions.moveTask(task.id, 1)}>
              <ArrowDown aria-hidden="true" />
            </Button>
            <KebabMenu label={t('or.tpl.taskMenu')} rowLabel={title} items={[
              ...moveItems,
              { label: t('or.tpl.taskDelete'), icon: <Trash2 aria-hidden="true" />, danger: true,
                onClick: () => actions.removeTask(sectionId, task.id) },
            ]} />
          </div>
        )}
      </div>
      <Collapsible open={detailsOpen} onOpenChange={(o) => actions.toggleDetails(task.id, o)}>
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" data-slot="or-tpl-task-details"
              className="-ms-2 h-8 gap-1.5 px-2 text-muted-foreground pointer-coarse:h-10">
              <ChevronRight aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', detailsOpen && 'rotate-90')} />
              {t('or.tpl.details')}
            </Button>
          </CollapsibleTrigger>
          {summary.description && <Badge variant="outline" className={WRAP_BADGE}>{t('or.tpl.sumDescription')}</Badge>}
          {summary.links > 0 && <Badge variant="outline" className={WRAP_BADGE}>{t(summary.links === 1 ? 'or.tpl.sumLinks.one' : 'or.tpl.sumLinks', summary.links)}</Badge>}
          {summary.signals > 0 && <Badge variant="outline" className={WRAP_BADGE}>{t(summary.signals === 1 ? 'or.tpl.sumSignals.one' : 'or.tpl.sumSignals', summary.signals)}</Badge>}
          {summary.window && <Badge variant="outline" className={WRAP_BADGE}>{windowText(t, task.window_from, task.window_to)}</Badge>}
        </div>
        <CollapsibleContent forceMount hidden={!detailsOpen} data-slot="or-tpl-task-body"
          className="mt-2 grid grid-cols-1 gap-3 border-t border-border pt-3 @lg:grid-cols-2">
          <Field label={t('or.tpl.description')} name={`${tp}.description`} error={errors[`${tp}.description`]}
            hint={t('or.tpl.countOf', (task.description || '').length, limits.description)} className="mb-0 @lg:col-span-2">
            {({ id, describedBy, invalid }) => (
              <Textarea id={id} rows={3} value={task.description} disabled={locked} aria-describedby={describedBy}
                aria-invalid={invalid} placeholder={t('or.tpl.descriptionPh')}
                onChange={(e) => onChange({ description: e.target.value }, ['description'])} />
            )}
          </Field>
          <WindowFields prefix={tp} from={task.window_from} to={task.window_to} errors={errors} locked={locked}
            hint={t('or.tpl.taskWindowHint')} onChange={(p, keys) => onChange(p, keys)} />
          <LinksEditor task={task} tp={tp} errors={errors} locked={locked} limits={limits} onChange={onChange} />
          <SignalsPicker task={task} tp={tp} errors={errors} locked={locked} limits={limits} signalDefs={signalDefs} onChange={onChange} />
        </CollapsibleContent>
      </Collapsible>
    </li>
  )
}, (a, b) => a.task === b.task && a.sectionId === b.sectionId && a.index === b.index && a.count === b.count
  && a.errSig === b.errSig && a.locked === b.locked && a.detailsOpen === b.detailsOpen && a.dropPos === b.dropPos
  && a.sectionOptions === b.sectionOptions && a.signalDefs === b.signalDefs && a.limits === b.limits && a.actions === b.actions)

function SectionCard({
  section, index, count, open, errors, errCount, locked, sectionOptions, signalDefs, limits, actions, details, drop,
}) {
  const t = useT()
  const sp = `template.${section.id}`
  const title = section.title.trim() || t('or.tpl.untitledSection')
  const tasks = section.tasks || []
  const active = tasks.filter((x) => x.active).length
  const win = windowText(t, section.window_from, section.window_to)
  const endDrop = drop?.sectionId === section.id
  return (
    <Card data-slot="or-tpl-section" data-section={section.id} data-open={open ? 'true' : 'false'}
      className="gap-0 overflow-visible py-0 shadow-none">
      <Collapsible open={open} onOpenChange={(o) => actions.toggleSection(section.id, o)}>
        <div className="flex items-start gap-1 p-1.5">
          <h3 className="min-w-0 flex-1 text-base font-semibold leading-snug">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" data-slot="or-tpl-section-toggle"
                className="h-auto min-h-0 w-full items-start justify-start gap-2 whitespace-normal rounded-lg px-2.5 py-2.5 text-left text-base font-semibold leading-snug has-[>svg]:px-2.5 pointer-coarse:min-h-10">
                <ChevronRight aria-hidden="true"
                  className={cn('mt-[3px] size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-90')} />
                <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{title}</span>
              </Button>
            </CollapsibleTrigger>
          </h3>
          {!locked && (
            <div className="flex min-h-[2.625rem] shrink-0 items-center gap-0.5">
              <Button type="button" variant="ghost" size="icon-sm" className={ICON_BTN} data-slot="or-tpl-move-up" data-scope="section"
                disabled={index === 0} aria-label={t('or.tpl.sectionUp', title)} onClick={() => actions.moveSection(section.id, -1)}>
                <ArrowUp aria-hidden="true" />
              </Button>
              <Button type="button" variant="ghost" size="icon-sm" className={ICON_BTN} data-slot="or-tpl-move-down" data-scope="section"
                disabled={index >= count - 1} aria-label={t('or.tpl.sectionDown', title)} onClick={() => actions.moveSection(section.id, 1)}>
                <ArrowDown aria-hidden="true" />
              </Button>
              <Button type="button" variant="ghost" size="icon-sm" className={cn(ICON_BTN, 'text-destructive hover:text-destructive')}
                data-slot="or-tpl-section-delete" aria-label={t('or.tpl.sectionDelete', title)}
                onClick={() => actions.removeSection(section.id)}>
                <Trash2 aria-hidden="true" />
              </Button>
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-1.5 pe-3 pb-2.5 ps-10">
          <Badge variant="secondary" className={WRAP_BADGE} data-slot="or-tpl-section-count">
            {t(tasks.length === 1 ? 'or.tpl.taskCount.one' : 'or.tpl.taskCount', tasks.length)}
            {active !== tasks.length && ` · ${t('or.tpl.activeCount', active)}`}
          </Badge>
          {win && <Badge variant="outline" className={WRAP_BADGE}>{win}</Badge>}
          {!open && errCount > 0 && (
            <Badge variant="destructive" className={WRAP_BADGE} data-slot="or-tpl-section-invalid">
              {t(errCount === 1 ? 'or.tpl.invalidCount.one' : 'or.tpl.invalidCount', errCount)}
            </Badge>
          )}
        </div>
        <CollapsibleContent forceMount hidden={!open} data-slot="or-tpl-section-body" className="@container border-t border-border px-3 pb-3 pt-3 sm:px-4">
          <div className="grid grid-cols-1 gap-3 @lg:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)]">
            <Field label={t('or.tpl.sectionTitle')} name={`${sp}.title`} error={errors[`${sp}.title`]} className="mb-0">
              {({ id, describedBy, invalid }) => (
                <Input id={id} value={section.title} disabled={locked} aria-describedby={describedBy} aria-invalid={invalid}
                  placeholder={t('or.tpl.sectionTitlePh')} onChange={(e) => actions.patchSection(section.id, { title: e.target.value }, ['title'])} />
              )}
            </Field>
            <WindowFields prefix={sp} from={section.window_from} to={section.window_to} errors={errors} locked={locked}
              hint={t('or.tpl.sectionWindowHint')} onChange={(p, keys) => actions.patchSection(section.id, p, keys)} />
          </div>
          <InlineError>{errors[`${sp}.tasks`]}</InlineError>
          {tasks.length > 0 ? (
            <ol data-slot="or-tpl-tasks" className="mt-3 flex flex-col gap-3">
              {tasks.map((task, i) => (
                <TaskRow key={task.id} task={task} sectionId={section.id} index={i} count={tasks.length} errors={errors}
                  errSig={taskErrorSignature(errors, task.id)} locked={locked} detailsOpen={details.has(task.id)}
                  dropPos={drop?.taskId === task.id ? drop.pos : null} sectionOptions={sectionOptions}
                  signalDefs={signalDefs} limits={limits} actions={actions} />
              ))}
            </ol>
          ) : (
            <p data-slot="or-tpl-tasks-empty" className="mt-3 rounded-md border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground"
              onDragOver={(e) => actions.dragOverSection(section.id, e)} onDrop={(e) => actions.dropOnSection(section.id, e)}>
              {t('or.tpl.noTasks')}
            </p>
          )}
          {!locked && (
            <div className={cn('mt-3 rounded-md', endDrop && 'outline-2 outline-dashed outline-primary')}
              onDragOver={(e) => actions.dragOverSection(section.id, e)} onDrop={(e) => actions.dropOnSection(section.id, e)}>
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" data-slot="or-tpl-add-task"
                disabled={tasks.length >= limits.tasks_per_section} onClick={() => actions.addTask(section.id)}>
                <Plus aria-hidden="true" /> {t('or.tpl.addTask')}
              </Button>
            </div>
          )}
        </CollapsibleContent>
      </Collapsible>
    </Card>
  )
}

// ── Düzenleyici ─────────────────────────────────────────────────────────────────────────────────────────────────────

const DETAIL_PROPS = ['description', 'window_from', 'window_to', 'links', 'signals']

/**
 * @param {{teamId: number|string, teamName?: string, canEdit: boolean, focusTaskId?: string|null,
 *          onSaved?: (templateVersion: number) => void, onDirtyChange?: (dirty: boolean) => void}} props
 */
export default function RoutineTemplateEditor({
  teamId, teamName, canEdit, focusTaskId = null, onSaved = () => {}, onDirtyChange = () => {}, meName = null,
}) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const tRef = useRef(t)
  tRef.current = t
  const toastRef = useRef(toast)
  toastRef.current = toast
  const confirmRef = useRef(showConfirm)
  confirmRef.current = showConfirm

  const [load, setLoad] = useState({ loading: true, error: null, info: null })
  const [meta, setMeta] = useState(null)
  const [base, setBase] = useState(null)
  const [draft, setDraft] = useState(null)
  const [source, setSource] = useState('EDITOR')
  const [saving, setSaving] = useState(false)
  const [showRequired, setShowRequired] = useState(false)
  const [serverErrors, setServerErrors] = useState({})
  const [generalError, setGeneralError] = useState(null)
  const [conflict, setConflict] = useState(null)
  const [backup, setBackup] = useState(null)
  // Geri yüklenen yedek ESKİ bir sürüme dayanıyorsa o sürüm (kaydetmeden önce sorulur; yoksa null)
  const [restoredFrom, setRestoredFrom] = useState(null)
  const [collapsed, setCollapsed] = useState(() => new Set())
  const [details, setDetails] = useState(() => new Set())
  const [importOpen, setImportOpen] = useState(false)
  const [versionsOpen, setVersionsOpen] = useState(false)
  const [starting, setStarting] = useState(null)
  const [drop, setDropState] = useState(null)
  const dropRef = useRef(null)
  const setDrop = useCallback((v) => {
    const next = typeof v === 'function' ? v(dropRef.current) : v
    if (next === dropRef.current) return
    dropRef.current = next
    setDropState(next)
  }, [])

  const usedIds = useRef(new Set())
  const draftRef = useRef(draft)
  draftRef.current = draft
  const seq = useRef(0)
  const ctrl = useRef(null)
  const dragId = useRef(null)
  const rootRef = useRef(null)
  const pendingFocus = useRef(null)
  const wroteBackup = useRef(false)
  const stickyRef = useStickyBottomBar()

  const editable = !!canEdit && meta?.canEditTemplate !== false
  const locked = !editable || saving
  const limits = useMemo(() => resolveLimits(meta?.limits), [meta?.limits])
  const signalDefs = useMemo(() => {
    const defs = Array.isArray(meta?.signalDefs) && meta.signalDefs.length
      ? meta.signalDefs.filter((d) => d && typeof d.key === 'string')
      : SIGNAL_KEYS.map((key) => ({ key, perm: null, hidden: false }))
    return defs.map((d) => ({ key: d.key, perm: d.perm ?? null, hidden: !!d.hidden }))
  }, [meta?.signalDefs])
  const signalKeys = useMemo(() => signalDefs.map((d) => d.key), [signalDefs])

  const loadTemplate = useCallback(async () => {
    const my = ++seq.current
    ctrl.current?.abort()
    const c = typeof AbortController !== 'undefined' ? new AbortController() : null
    ctrl.current = c
    setLoad({ loading: true, error: null, info: null })
    let res
    try {
      res = await api.oncallRoutines.template(teamId, c ? { signal: c.signal } : undefined)
    } catch (e) {
      if (my !== seq.current) return
      setLoad({ loading: false, error: thrownResult(e).error || tRef.current('or.tpl.loadFailed'), info: errorInfoOf(e) })
      return
    }
    if (my !== seq.current || res === null) return
    if (!res?.success || !res.data) {
      setLoad({ loading: false, error: res?.error || tRef.current('or.tpl.loadFailed'), info: errorInfoOf(res) })
      return
    }
    const d = res.data
    const tpl = d.template ? normalizeTemplate(d.template) : null
    const baseTpl = tpl && tpl.sections.length ? tpl : null
    collectIds(tpl, usedIds.current)
    const m = {
      configured: d.configured !== false && d.template != null,
      version: Number.isInteger(d.template_version) ? d.template_version : 0,
      updatedBy: d.template_updated_by_name || null,
      updatedAt: d.template_updated_at || null,
      unreadable: d.template_unreadable === true,
      canEditTemplate: d.can_edit_template !== false,
      limits: d.limits || null,
      signalDefs: Array.isArray(d.signal_defs) ? d.signal_defs : null,
    }
    setMeta(m)
    setBase(baseTpl)
    setDraft(baseTpl)
    setSource('EDITOR')
    setConflict(null)
    setServerErrors({})
    setGeneralError(null)
    setShowRequired(false)
    setDrop(null)
    setRestoredFrom(null)
    const b = canEdit && m.canEditTemplate ? readTemplateBackup(teamId) : null
    if (b) {
      collectIds(b.template, usedIds.current)
      if (baseTpl && canonicalJson(normalizeTemplate(b.template)) === canonicalJson(baseTpl)) {
        dropBackup(teamId)
        setBackup(null)
      } else {
        // Öneri bekliyor: taslak temiz olsa da "yazdığım yedek" sayılıp silinmez (yeniden açılışta yine önerilir)
        wroteBackup.current = false
        setBackup(b)
      }
    } else setBackup(null)
    setLoad({ loading: false, error: null, info: null })
  }, [teamId, canEdit, setDrop])

  useEffect(() => {
    loadTemplate()
    return () => { seq.current += 1; ctrl.current?.abort() }
  }, [loadTemplate])

  useEffect(() => { collectIds(draft, usedIds.current) }, [draft])

  const dirty = useMemo(() => isDirty(base, draft), [base, draft])
  const changes = useMemo(() => (dirty ? dirtyCount(base, draft) : 0), [dirty, base, draft])

  // Ana sayfa ve "ana pencerede aç" istekleri taslağı sessizce bırakmasın
  // Geri çağrılar ref'ten okunur: üst bileşen her çizimde yeni işlev verse de etki yeniden koşmaz (döngü olmaz)
  const dirtyCb = useRef(onDirtyChange)
  dirtyCb.current = onDirtyChange
  const savedCb = useRef(onSaved)
  savedCb.current = onSaved
  useEffect(() => { dirtyCb.current?.(dirty) }, [dirty])
  useEffect(() => () => dirtyCb.current?.(false), [])
  useUnsavedWork(dirty && editable)

  // Yerel yedek (1 sn sonra); taslak temizlenince bu sayfanın yazdığı yedek silinir. Yedeğin sürümü taslağın DAYANDIĞI
  // sürümdür: eski bir yedek geri yüklendiyse o eski sürüm (yeniden açılışta da "eski sürüme dayanıyor" uyarısı + onay kalır)
  const draftBase = restoredFrom ?? meta?.version ?? 0
  const backupState = useRef({})
  backupState.current = { teamId, editable, dirty, draft, source, version: draftBase }
  useEffect(() => {
    if (!editable) return undefined
    if (!dirty) {
      if (wroteBackup.current) { dropBackup(teamId); wroteBackup.current = false }
      return undefined
    }
    const h = setTimeout(() => {
      if (writeBackup(teamId, { v: 1, version: draftBase, source, at: Date.now(), template: toWire(draft) })) {
        wroteBackup.current = true
      }
    }, 1000)
    return () => clearTimeout(h)
  }, [draft, dirty, editable, teamId, source, draftBase])

  const flushBackup = useCallback(() => {
    const s = backupState.current
    if (!s.editable || !s.dirty || !s.draft) return
    if (writeBackup(s.teamId, { v: 1, version: s.version, source: s.source, at: Date.now(), template: toWire(s.draft) })) {
      wroteBackup.current = true
    }
  }, [])
  useEffect(() => () => flushBackup(), [flushBackup])
  useEffect(() => {
    if (!dirty || !editable) return undefined
    const h = (e) => { flushBackup(); e.preventDefault(); e.returnValue = '' }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [dirty, editable, flushBackup])

  // Doğrulama + gösterilecek hatalar (istemci önce, sonra sunucu)
  const allErrors = useMemo(() => (draft ? validateTemplate(draft, limits, { signalKeys }) : {}), [draft, limits, signalKeys])
  const errors = useMemo(() => {
    const out = {}
    for (const [k, e] of Object.entries(visibleErrors(allErrors, showRequired))) out[k] = t(e.k, ...(e.a || []))
    for (const [k, msg] of Object.entries(serverErrors)) if (!out[k]) out[k] = msg
    return out
  }, [allErrors, showRequired, serverErrors, t])
  const errCounts = useMemo(() => errorCountsBySection(errors), [errors])
  const invalidTotal = Object.keys(allErrors).length

  const clearServer = useCallback((keys) => {
    if (!keys?.length) return
    setServerErrors((m) => {
      let changed = false
      const out = {}
      for (const [k, v] of Object.entries(m)) {
        if (keys.some((p) => k === p || k.startsWith(`${p}.`))) { changed = true; continue }
        out[k] = v
      }
      return changed ? out : m
    })
  }, [])

  // Sabit eylem nesnesi (memo'lu satırlar yeniden çizilmesin): durum güncellemeleri işlevsel, metinler ref'ten
  const actions = useMemo(() => {
    const focusField = (key) => { pendingFocus.current = { key } }
    return {
      patchTask(sectionId, taskId, patch, keys = []) {
        setDraft((d) => (d ? updateTask(d, taskId, patch) : d))
        clearServer(keys.map((k) => `template.${sectionId}.${taskId}.${k}`))
      },
      patchSection(sectionId, patch, keys = []) {
        setDraft((d) => (d ? updateSection(d, sectionId, patch) : d))
        clearServer(keys.map((k) => `template.${sectionId}.${k}`))
      },
      addTask(sectionId) {
        const cur = draftRef.current
        if (!cur) return
        const r = addTask(cur, sectionId, usedIds.current)
        setDraft(r.template)
        setCollapsed((c) => { if (!c.has(sectionId)) return c; const n = new Set(c); n.delete(sectionId); return n })
        focusField(`template.${sectionId}.${r.id}.title`)
      },
      removeTask(sectionId, taskId) {
        const cur = draftRef.current
        const loc = cur ? locateTask(cur, taskId) : null
        if (!loc) return
        setDraft((d) => (d ? removeTask(d, taskId) : d))
        clearServer([`template.${sectionId}.${taskId}`])
        const tt = tRef.current
        toastRef.current.action(tt('or.tpl.taskRemoved', loc.task.title.trim() || tt('or.tpl.untitledTask')), {
          label: tt('or.tpl.undo'),
          onAction: () => setDraft((d) => (d ? insertTask(d, loc.sectionId, loc.index, loc.task) : d)),
        })
      },
      moveTask(taskId, dir) { setDraft((d) => (d ? moveTask(d, taskId, dir) : d)) },
      moveTaskTo(taskId, sectionId, beforeId) {
        setDraft((d) => (d ? moveTaskTo(d, taskId, sectionId, beforeId) : d))
        setCollapsed((c) => { if (!c.has(sectionId)) return c; const n = new Set(c); n.delete(sectionId); return n })
      },
      moveSection(sectionId, dir) { setDraft((d) => (d ? moveSection(d, sectionId, dir) : d)) },
      async removeSection(sectionId) {
        const cur = draftRef.current
        const sec = cur?.sections.find((s) => s.id === sectionId)
        if (!sec) return
        const tt = tRef.current
        const n = (sec.tasks || []).length
        const name = sec.title.trim() || tt('or.tpl.untitledSection')
        if (n > 0) {
          const ok = await confirmRef.current({
            title: tt('or.tpl.sectionDeleteTitle'),
            message: tt(n === 1 ? 'or.tpl.sectionDeleteBody.one' : 'or.tpl.sectionDeleteBody', name, n),
            confirmText: tt('or.tpl.sectionDeleteConfirm'),
            variant: 'danger',
          })
          if (!ok) return
        }
        setDraft((d) => (d ? removeSection(d, sectionId) : d))
        clearServer([`template.${sectionId}`])
      },
      toggleSection(sectionId, open) {
        setCollapsed((c) => {
          if (open === !c.has(sectionId)) return c
          const n = new Set(c)
          if (open) n.delete(sectionId); else n.add(sectionId)
          return n
        })
      },
      toggleDetails(taskId, open) {
        setDetails((s) => {
          if (open === s.has(taskId)) return s
          const n = new Set(s)
          if (open) n.add(taskId); else n.delete(taskId)
          return n
        })
      },
      dragStart(taskId, e) {
        dragId.current = taskId
        try {
          e.dataTransfer.effectAllowed = 'move'
          e.dataTransfer.setData('text/plain', taskId)
          const row = e.currentTarget?.closest?.('[data-slot="or-tpl-task"]')
          if (row && typeof e.dataTransfer.setDragImage === 'function') e.dataTransfer.setDragImage(row, 16, 16)
        } catch { /* eski tarayıcı */ }
      },
      dragEnd() { dragId.current = null; setDrop(null) },
      dragOverTask(sectionId, taskId, e) {
        if (!dragId.current || dragId.current === taskId) return
        e.preventDefault()
        const r = e.currentTarget.getBoundingClientRect()
        const pos = e.clientY < r.top + r.height / 2 ? 'before' : 'after'
        setDrop((d) => (d?.taskId === taskId && d.pos === pos ? d : { taskId, pos }))
      },
      dropOnTask(sectionId, taskId, e) {
        const id = dragId.current
        if (!id) return
        e.preventDefault()
        let before = taskId
        const d = dropRef.current
        if (d?.taskId === taskId && d.pos === 'after') {
          const sec = draftRef.current?.sections.find((s) => s.id === sectionId)
          const rest = (sec?.tasks || []).filter((x) => x.id !== id)
          const at = rest.findIndex((x) => x.id === taskId)
          before = at >= 0 && at + 1 < rest.length ? rest[at + 1].id : null
        }
        setDrop(null)
        dragId.current = null
        if (id !== taskId) setDraft((d) => (d ? moveTaskTo(d, id, sectionId, before) : d))
      },
      dragOverSection(sectionId, e) {
        if (!dragId.current) return
        e.preventDefault()
        setDrop((d) => (d?.sectionId === sectionId ? d : { sectionId }))
      },
      dropOnSection(sectionId, e) {
        const id = dragId.current
        if (!id) return
        e.preventDefault()
        dragId.current = null
        setDrop(null)
        setDraft((d) => (d ? moveTaskTo(d, id, sectionId, null) : d))
      },
    }
  }, [clearServer, setDrop])

  // `focusTaskId`: o görevin bölümü açılır, başlığına kaydırılır ve odaklanılır (bir kez). Bekleyen
  // odak etkisinden ÖNCE durur: aynı çizimde tüketilir (bölüm zaten açıksa yeni çizim olmaz, odak kaybolmazdı)
  const focusedFor = useRef(null)
  useEffect(() => {
    if (!focusTaskId || !draft || focusedFor.current === focusTaskId) return
    const loc = locateTask(draft, focusTaskId)
    if (!loc) return
    focusedFor.current = focusTaskId
    setCollapsed((c) => { if (!c.has(loc.sectionId)) return c; const n = new Set(c); n.delete(loc.sectionId); return n })
    pendingFocus.current = { key: `template.${loc.sectionId}.${focusTaskId}.title` }
  }, [focusTaskId, draft])

  // Bekleyen odak (yeni görev / bölüm, `focusTaskId`, hatalı alan) — çizimden sonra
  useEffect(() => {
    const p = pendingFocus.current
    if (!p || !rootRef.current) return
    const host = rootRef.current.querySelector(`[data-field="${p.key}"]`)
    if (!host) return
    pendingFocus.current = null
    const el = host.matches('input, textarea, select, button') ? host
      : host.querySelector('input:not([disabled]), textarea:not([disabled]), select:not([disabled]), button:not([disabled])') || host
    try { el.scrollIntoView?.({ block: 'center' }) } catch { /* jsdom */ }
    try { el.focus({ preventScroll: true }) } catch { /* odaklanamaz */ }
  })

  const sectionOptionsKey = JSON.stringify((draft?.sections || []).map((s) => [s.id, s.title]))
  const sectionOptions = useMemo(
    () => JSON.parse(sectionOptionsKey).map(([id, title]) => ({ id, title: String(title || '').trim() })),
    [sectionOptionsKey],
  )

  /** Hatalı alanların bölümlerini ve ayrıntılarını açar, ilk hatalıya odaklanır. */
  const revealErrors = useCallback((keys) => {
    const secs = new Set()
    const tasks = new Set()
    for (const k of keys) {
      const s = errorSectionId(k)
      if (s) secs.add(s)
      const tid = errorTaskId(k)
      if (tid && !isSectionLevelKey(k)) {
        const prop = k.split('.')[3]
        if (DETAIL_PROPS.includes(prop)) tasks.add(tid)
      }
    }
    if (secs.size) setCollapsed((c) => { const n = new Set(c); for (const s of secs) n.delete(s); return n })
    if (tasks.size) setDetails((d) => { const n = new Set(d); for (const x of tasks) n.add(x); return n })
    const first = keys.find((k) => k !== 'template') || (keys.includes('template') ? 'template' : null)
    if (first) pendingFocus.current = { key: first }
  }, [])

  const saving$ = useRef(false)
  const save = useCallback(async () => {
    if (!editable || saving$.current || !draft || !dirty || conflict) return
    if (invalidTotal > 0) {
      setShowRequired(true)
      revealErrors(Object.keys(allErrors))
      return
    }
    // Eski sürüme dayanan yedek geri yüklendiyse: kaydetmek o arada yapılan değişikliğin ÜZERİNE yazar → önce sorulur
    if (restoredFrom != null && restoredFrom !== (meta?.version ?? 0)) {
      saving$.current = true
      let ok = false
      try {
        ok = await showConfirm({
          title: t('or.tpl.staleSaveTitle'),
          message: t('or.tpl.staleSaveBody', restoredFrom, meta?.version ?? 0, meta?.updatedBy || '—'),
          confirmText: t('or.tpl.staleSaveConfirm'),
          variant: 'warning',
        })
      } finally { saving$.current = false }
      if (!ok) return
    }
    saving$.current = true
    setSaving(true)
    setGeneralError(null)
    try {
      let res
      try {
        res = await api.oncallRoutines.saveTemplate(teamId, { version: meta?.version ?? 0, template: toWire(draft), source })
      } catch (e) {
        toast.error(isUnsureResult(e) ? t('or.tpl.saveUnsure') : (e?.message || t('or.tpl.saveFailed')), { details: e })
        return
      }
      if (res === null) return
      if (res?.success) {
        const d = res.data || {}
        dropBackup(teamId)
        wroteBackup.current = false
        setBackup(null)
        setShowRequired(false)
        setServerErrors({})
        if (d.changed === false) {
          setBase(draft)
          toast.info(t('or.tpl.noChange'))
          return
        }
        const saved = d.template ? normalizeTemplate(d.template) : draft
        const version = Number.isInteger(d.template_version) ? d.template_version : (meta?.version ?? 0) + 1
        collectIds(saved, usedIds.current)
        setBase(saved)
        setDraft(saved)
        setSource('EDITOR')
        setRestoredFrom(null)
        // Kaydeden = ben: sunucunun döndürdüğü ad / an; yoksa oturumdaki ad ve yanıtın sunucu anı (önceki düzenleyenin adı
        // benim saatimle yazılmaz)
        setMeta((m) => ({ ...(m || {}), configured: true, version, updatedBy: d.template_updated_by_name ?? meName ?? null,
          updatedAt: d.template_updated_at ?? res.timestamp ?? new Date().toISOString(), unreadable: false }))
        toast.success(t('or.tpl.saved', version))
        savedCb.current?.(version)
        return
      }
      const code = codeOf(res) || errorInfoOf(res)?.code
      if (code === 'CONCURRENT_MODIFICATION') {
        setConflict({ by: res.updated_by_name || null, at: res.updated_at || null, version: res.current_version ?? null })
        return
      }
      if (code === 'VALIDATION_FAILED' || errorInfoOf(res)?.status === 400) {
        const { fields, general } = mapServerErrors(res)
        setServerErrors(fields)
        setGeneralError(general || (Object.keys(fields).length ? null : res?.error || t('or.tpl.saveFailed')))
        revealErrors(Object.keys(fields).length ? Object.keys(fields) : ['template'])
        return
      }
      if (isUnsureResult(res)) {
        toast.error(t('or.tpl.saveUnsure'), { details: res })
        return
      }
      toast.error(res?.error || (errorKey(code) ? t(errorKey(code)) : t('or.tpl.saveFailed')), { details: res })
    } finally {
      saving$.current = false
      setSaving(false)
    }
  }, [editable, draft, dirty, conflict, invalidTotal, allErrors, revealErrors, teamId, meta?.version, meta?.updatedBy, source, toast, t,
    restoredFrom, showConfirm, meName])

  // Ctrl/⌘+S — düzenleyici açıkken, açık pencere yokken
  const saveRef = useRef(save)
  saveRef.current = save
  useEffect(() => {
    if (!editable) return undefined
    const h = (e) => {
      if (e.isComposing || !isSaveKey(e) || hasOpenDialog()) return
      e.preventDefault()
      saveRef.current?.()
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [editable])

  const discard = useCallback(async () => {
    if (!dirty || saving) return
    const ok = await showConfirm({
      title: t('or.tpl.discardTitle'),
      message: t(changes === 1 ? 'or.tpl.discardBody.one' : 'or.tpl.discardBody', changes),
      confirmText: t('or.tpl.discardConfirm'),
      variant: 'warning',
    })
    if (!ok) return
    setDraft(base)
    setSource('EDITOR')
    setShowRequired(false)
    setServerErrors({})
    setGeneralError(null)
    setRestoredFrom(null)
    dropBackup(teamId)
    wroteBackup.current = false
  }, [dirty, saving, showConfirm, t, changes, base, teamId])

  const loadLatest = useCallback(() => {
    // Taslak önce yedeklenir → yeniden okumadan sonra "geri yükle" önerisi olarak gelir
    if (draft) writeBackup(teamId, { v: 1, version: draftBase, source, at: Date.now(), template: toWire(draft) })
    loadTemplate()
  }, [draft, teamId, draftBase, source, loadTemplate])

  const restoreBackup = useCallback(() => {
    if (!backup) return
    const tpl = normalizeTemplate(backup.template)
    collectIds(tpl, usedIds.current)
    setDraft(tpl.sections.length ? tpl : null)
    setSource(['EDITOR', 'IMPORT', 'SAMPLE', 'RESTORE'].includes(backup.source) ? backup.source : 'EDITOR')
    const bv = Number(backup.version)
    setRestoredFrom(Number.isInteger(bv) && bv !== (meta?.version ?? 0) ? bv : null)
    setBackup(null)
  }, [backup, meta?.version])

  const discardBackup = useCallback(() => {
    dropBackup(teamId)
    wroteBackup.current = false
    setBackup(null)
  }, [teamId])

  const startSample = useCallback(async () => {
    if (starting) return
    setStarting('sample')
    try {
      const res = await api.oncallRoutines.sampleTemplate(lang === 'en' ? 'en' : 'tr')
      if (res === null) return
      if (res?.success && res.data?.template) {
        const sections = reidSections(normalizeTemplate(res.data.template).sections, usedIds.current)
        setDraft({ v: 1, sections })
        setSource('SAMPLE')
      } else {
        toast.error(res?.error || t('or.tpl.sampleFailed'), { details: res })
      }
    } catch (e) {
      toast.error(e?.message || t('or.tpl.sampleFailed'), { details: e })
    } finally {
      setStarting(null)
    }
  }, [starting, lang, toast, t])

  const startBlank = useCallback(() => {
    const s = addSection(emptyTemplate(), usedIds.current, t('or.tpl.defaultSectionTitle'))
    const r = addTask(s.template, s.id, usedIds.current)
    setDraft(r.template)
    setSource('EDITOR')
    pendingFocus.current = { key: `template.${s.id}.${r.id}.title` }
  }, [t])

  const addNewSection = useCallback(() => {
    const cur = draftRef.current || emptyTemplate()
    const r = addSection(cur, usedIds.current, '')
    setDraft(r.template)
    pendingFocus.current = { key: `template.${r.id}.title` }
  }, [])

  const onImport = useCallback(({ mode, sections }) => {
    const r = applyImport(draftRef.current || emptyTemplate(), sections, mode, usedIds.current)
    setDraft(r.template)
    setSource('IMPORT')
    setImportOpen(false)
    const n = (sections || []).reduce((a, s) => a + (s.tasks || []).length, 0)
    toast.success(t(n === 1 ? 'or.tpl.imported.one' : 'or.tpl.imported', n))
  }, [toast, t])

  const onLoadVersion = useCallback(async (template, version) => {
    if (dirty) {
      const ok = await showConfirm({
        title: t('or.tpl.loadVersionTitle'),
        message: t('or.tpl.loadVersionBody', version),
        confirmText: t('or.tpl.loadVersionConfirm'),
        variant: 'warning',
      })
      if (!ok) return
    }
    const tpl = normalizeTemplate(template)
    collectIds(tpl, usedIds.current)
    setDraft(tpl.sections.length ? tpl : null)
    setSource('RESTORE')
    setVersionsOpen(false)
    toast.info(t('or.tpl.versionLoaded', version))
  }, [dirty, showConfirm, t, toast])

  const allOpen = (draft?.sections || []).every((s) => !collapsed.has(s.id))
  const setAllOpen = (open) => setCollapsed(open ? new Set() : new Set((draft?.sections || []).map((s) => s.id)))

  // ── Çizim ──

  if (load.loading && !meta) {
    return (
      <div data-slot="or-tpl-loading" className="flex flex-col gap-3" aria-busy="true">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-28 w-full rounded-xl" />
        <Skeleton className="h-28 w-full rounded-xl" />
      </div>
    )
  }
  if (load.error && !meta) {
    return (
      <div data-slot="or-tpl-load-failed">
        <StatusBlock tone="danger" title={t('or.tpl.loadFailedTitle')} description={load.error} details={load.info || undefined}
          actions={<Button type="button" onClick={loadTemplate} className="pointer-coarse:h-10"><RotateCcw aria-hidden="true" /> {t('or.tpl.retry')}</Button>} />
      </div>
    )
  }

  const metaStamp = istanbulStamp(meta?.updatedAt)
  const versionsButton = (
    <Button type="button" variant="ghost" className="pointer-coarse:h-10" data-slot="or-tpl-versions" onClick={() => setVersionsOpen(true)}>
      <History aria-hidden="true" /> {t('or.tpl.versions')}
    </Button>
  )
  const versionsInBar = editable && !!draft
  const sections = draft?.sections || []
  const totalTasks = countTasks(draft)
  const activeTasks = countActive(draft)

  return (
    <div ref={rootRef} data-slot="or-tpl-editor" data-editable={editable ? 'true' : 'false'} className="@container flex flex-col gap-4">
      <div className="flex flex-col gap-3 @2xl:flex-row @2xl:items-start @2xl:justify-between">
        <div className="min-w-0">
          <h2 className="text-base font-semibold">{t('or.tpl.heading')}</h2>
          <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="or-tpl-meta">
            {[
              teamName || null,
              meta?.configured ? t('or.tpl.versionN', meta.version) : t('or.tpl.notConfigured'),
              meta?.updatedBy && metaStamp ? t('or.tpl.savedBy', meta.updatedBy, `${metaStamp.date} ${metaStamp.hm}`) : null,
              draft ? t('or.tpl.totals', sections.length, totalTasks, activeTasks) : null,
            ].filter(Boolean).join(' · ')}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {editable && draft && (
            <Button type="button" variant="outline" className="pointer-coarse:h-10" data-slot="or-tpl-import" disabled={saving}
              onClick={() => setImportOpen(true)}>
              <Import aria-hidden="true" /> {t('or.tpl.importWiki')}
            </Button>
          )}
          {sections.length > 1 && (
            <Button type="button" variant="outline" className="pointer-coarse:h-10" data-slot="or-tpl-expand-all"
              onClick={() => setAllOpen(!allOpen)}>
              {allOpen ? <ChevronsDownUp aria-hidden="true" /> : <ChevronsUpDown aria-hidden="true" />}
              {allOpen ? t('or.tpl.collapseAll') : t('or.tpl.expandAll')}
            </Button>
          )}
          {!versionsInBar && meta?.configured && versionsButton}
        </div>
      </div>

      {!editable && (
        <div data-slot="or-tpl-readonly">
          <AlertBanner tone="info" className="mb-0">{t('or.tpl.readOnly')}</AlertBanner>
        </div>
      )}
      {meta?.unreadable && (
        <div data-slot="or-tpl-unreadable">
          <AlertBanner tone="warning" title={t('or.tpl.unreadableTitle')} className="mb-0">{t('or.tpl.unreadableBody')}</AlertBanner>
        </div>
      )}
      {conflict && (
        <div data-slot="or-tpl-conflict">
          <AlertBanner tone="warning" role="alert" className="mb-0" title={t('or.tpl.conflictTitle')}
            actions={(
              <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" data-slot="or-tpl-conflict-load" onClick={loadLatest}>
                <RotateCcw aria-hidden="true" /> {t('or.tpl.loadLatest')}
              </Button>
            )}>
            {(() => {
              const st = istanbulStamp(conflict.at)
              return conflict.by && st ? t('or.tpl.conflictBody', conflict.by, `${st.date} ${st.hm}`) : t('or.tpl.conflictBodyPlain')
            })()}
          </AlertBanner>
        </div>
      )}
      {backup && editable && (
        <div data-slot="or-tpl-backup">
          <AlertBanner tone="info" className="mb-0" title={t('or.tpl.backupTitle')}
            actions={(
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" className="pointer-coarse:h-10" data-slot="or-tpl-backup-restore" onClick={restoreBackup}>
                  {t('or.tpl.backupRestore')}
                </Button>
                <Button type="button" size="sm" variant="outline" className="pointer-coarse:h-10" data-slot="or-tpl-backup-discard" onClick={discardBackup}>
                  {t('or.tpl.backupDiscard')}
                </Button>
              </div>
            )}>
            {(() => {
              const at = Number(backup.at)
              const st = Number.isFinite(at) ? istanbulStamp(new Date(at).toISOString()) : null
              const body = st ? t('or.tpl.backupBody', `${st.date} ${st.hm}`) : t('or.tpl.backupBodyPlain')
              const bv = Number(backup.version)
              const older = Number.isInteger(bv) && bv !== (meta?.version ?? 0)
              return older ? (
                <>
                  {body}{' '}
                  <span data-slot="or-tpl-backup-older" className="font-medium">{t('or.tpl.backupOlder', bv, meta?.version ?? 0)}</span>
                </>
              ) : body
            })()}
          </AlertBanner>
        </div>
      )}
      {restoredFrom != null && dirty && editable && !backup && (
        <div data-slot="or-tpl-stale-base">
          <AlertBanner tone="warning" className="mb-0" title={t('or.tpl.staleBaseTitle')}>
            {t('or.tpl.staleBaseBody', restoredFrom, meta?.version ?? 0)}
          </AlertBanner>
        </div>
      )}
      {(generalError || errors.template) && (
        <div data-slot="or-tpl-error" data-field="template" tabIndex={-1} className="outline-none">
          <AlertBanner tone="danger" className="mb-0" title={t('or.tpl.invalidTitle')}>{errors.template || generalError}</AlertBanner>
        </div>
      )}

      {!draft ? (
        <Empty data-slot="or-empty" className="rounded-xl border border-dashed border-border bg-card">
          <EmptyHeader className="max-w-lg">
            <EmptyMedia variant="icon"><ListTodo aria-hidden="true" /></EmptyMedia>
            <EmptyTitle>{t('or.tpl.emptyTitle')}</EmptyTitle>
            <EmptyDescription>{editable ? t('or.tpl.emptyBody') : t('or.tpl.emptyReadOnly')}</EmptyDescription>
          </EmptyHeader>
          {editable && (
            <EmptyContent className="max-w-none flex-col items-stretch gap-2 sm:flex-row sm:justify-center">
              <Button type="button" data-slot="or-empty-sample" disabled={!!starting} onClick={startSample} className="pointer-coarse:h-10">
                {starting === 'sample' ? <Spinner /> : <Sparkles aria-hidden="true" />} {t('or.tpl.startSample')}
              </Button>
              <Button type="button" variant="outline" data-slot="or-empty-wiki" disabled={!!starting} onClick={() => setImportOpen(true)}
                className="pointer-coarse:h-10">
                <Import aria-hidden="true" /> {t('or.tpl.startWiki')}
              </Button>
              <Button type="button" variant="outline" data-slot="or-empty-blank" disabled={!!starting} onClick={startBlank}
                className="pointer-coarse:h-10">
                <FilePlus2 aria-hidden="true" /> {t('or.tpl.startBlank')}
              </Button>
            </EmptyContent>
          )}
        </Empty>
      ) : (
        <>
          <div data-slot="or-tpl-sections" className="flex flex-col gap-3">
            {sections.map((s, i) => (
              <SectionCard key={s.id} section={s} index={i} count={sections.length} open={!collapsed.has(s.id)} errors={errors}
                errCount={errCounts.get(s.id) || 0} locked={locked} sectionOptions={sectionOptions} signalDefs={signalDefs}
                limits={limits} actions={actions} details={details} drop={drop} />
            ))}
          </div>
          {editable && (
            <div>
              <Button type="button" variant="outline" className="pointer-coarse:h-10" data-slot="or-tpl-add-section"
                disabled={locked || sections.length >= limits.sections} onClick={addNewSection}>
                <Plus aria-hidden="true" /> {t('or.tpl.addSection')}
              </Button>
            </div>
          )}
        </>
      )}

      {editable && draft && (
        <div ref={stickyRef} data-slot="or-tpl-savebar" data-dirty={dirty ? 'true' : 'false'}
          className="sticky bottom-0 z-10 flex flex-wrap items-center gap-2 rounded-xl border border-border bg-card/95 px-3 py-2.5 pb-[max(0.625rem,env(safe-area-inset-bottom))] shadow-sm backdrop-blur">
          <span className="mr-auto inline-flex min-w-0 items-center gap-2 text-sm font-medium" data-slot="or-tpl-dirty" aria-live="polite">
            {dirty
              ? <><span aria-hidden="true" className="size-2.5 shrink-0 rounded-full bg-warning" />{t(changes === 1 ? 'or.tpl.dirtyCount.one' : 'or.tpl.dirtyCount', changes)}</>
              : <><Check aria-hidden="true" className="size-4 text-muted-foreground" />{t('or.tpl.allSaved')}</>}
          </span>
          {showRequired && invalidTotal > 0 && (
            <span className="text-xs text-destructive" data-slot="or-tpl-invalid">
              {t(invalidTotal === 1 ? 'or.tpl.fixFields.one' : 'or.tpl.fixFields', invalidTotal)}
            </span>
          )}
          {versionsButton}
          <Button type="button" variant="outline" className="pointer-coarse:h-10" data-slot="or-tpl-discard" disabled={!dirty || saving}
            onClick={discard}>
            {t('or.tpl.discard')}
          </Button>
          <Button type="button" className="pointer-coarse:h-10" data-slot="or-tpl-save" disabled={!dirty || saving || !!conflict}
            aria-busy={saving || undefined} title={t('or.tpl.saveHint')} onClick={save}>
            {saving ? <Spinner /> : <Save aria-hidden="true" />} {saving ? t('or.tpl.saving') : t('or.tpl.save')}
          </Button>
        </div>
      )}

      <WikiImportDialog open={importOpen} onClose={() => setImportOpen(false)} draftSections={sections}
        lang={lang === 'en' ? 'en' : 'tr'} limits={limits} onImport={onImport} />
      <TemplateVersionsDialog open={versionsOpen} onClose={() => setVersionsOpen(false)} teamId={teamId}
        currentVersion={meta?.version ?? null} canLoad={editable} onLoadVersion={onLoadVersion} />
    </div>
  )
}
