/**
 * Nöbet Rutinleri — hafta görünümü (`or_view=week`). Haftanın günleri (kapsayıcı ≥ 760 px tablo, daha darda kart): durum,
 * `tamamlanan/zorunlu`, açılan sorun, nöbetçi, tamamlayan · saat (geç). Altında haftalık ve bu hafta görünen aylık görevler
 * (çalışma izni varsa buradan da işaretlenir — bağlam günü haftanın bugüne kadarki son iş günü), "Sonraki nöbetçiye not" ve
 * önceki nöbetin devir özeti (salt okunur).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ChevronLeft, ChevronRight, CalendarDays, RefreshCw, TriangleAlert, CalendarRange, ArrowUpRight, Lock } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { useLatestCallback } from '../../hooks/useLatestCallback.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import {
  EDIT_WINDOW_DAYS, addDays, codeOf, findItem, handoverWritable, hmOf, isoWeekday, istanbulDayOfStamp, keyOf, keyboardOrder, replaceItem,
  sectionKey, shortDate, stepKey, thrownResult,
} from './routineModel.js'
import { DayStatusBadge, PersonName, serverText } from './routineUi.jsx'
import { useItemActions } from './useItemActions.js'
import { useRoutineKeys } from './routineKeys.js'
import { refreshRoutineBadge } from './useRoutineBadge.js'
import TaskRow, { openAllLinks, returnFocusToRow, rowCheckSelector } from './TaskRow.jsx'
import SectionCard from './SectionCard.jsx'
import RoutineBand from './RoutineBand.jsx'
import HandoverPanel from './HandoverPanel.jsx'
import HandoverNote from './HandoverNote.jsx'

const NOOP = () => {}

/** Gün tablosuna geçiş genişliği (kapsayıcı). */
export const WEEK_TABLE_MIN_PX = 760

/**
 * Haftalık / aylık maddenin işaretleneceği bağlam günü: haftanın bugüne kadarki (gelecek olmayan) son iş günü; aylık maddede
 * yalnız maddenin ayına düşen günler. `minDate` verilirse ondan eski gün bağlam olamaz (sunucu 7 günlük düzenleme penceresinin
 * dışındaki günü DAY_LOCKED ile reddeder). Uygun gün yoksa null (madde buradan işaretlenmez).
 */
export function weekContextDate(days, item, minDate = null) {
  const list = Array.isArray(days) ? days : []
  let best = null
  for (const d of list) {
    if (!d?.workday || d.status === 'FUTURE' || !d.date) continue
    if (minDate && d.date < minDate) continue
    if (item?.period_kind === 'MONTH' && item.period_key && !String(d.date).startsWith(item.period_key)) continue
    if (!best || d.date > best) best = d.date
  }
  return best
}

export default function WeekView({ teamId, week = null, onWeekChange, onOpenDay, meName = null, canEditTemplate = false, onEditTemplate }) {
  const t = useT()
  const toast = useToast()
  const [data, setDataState] = useState(null)
  const [state, setState] = useState('loading')
  const [err, setErr] = useState(null)
  const [panel, setPanel] = useState(null)
  const [collapsed, setCollapsed] = useState(() => new Set())
  // Sunucunun bugünü (yanıt zarfının anından, İstanbul günü): eski haftada bugünün satırı yok, pencere kuralı buna dayanır
  const [serverToday, setServerToday] = useState(null)
  const dataRef = useRef(null)
  const seq = useRef(0)
  const abort = useRef(null)
  const [wrapRef, width] = useElementWidth()

  const setData = useCallback((fn) => {
    setDataState((prev) => {
      const next = typeof fn === 'function' ? fn(prev) : fn
      dataRef.current = next
      return next
    })
  }, [])

  const load = useCallback(async ({ silent = false } = {}) => {
    const my = ++seq.current
    abort.current?.abort()
    const ac = new AbortController()
    abort.current = ac
    if (!silent) { setState('loading'); setErr(null) }
    let res
    try { res = await api.oncallRoutines.week(teamId, week || undefined, { signal: ac.signal }) } catch (e) {
      res = thrownResult(e)
    }
    if (my !== seq.current || res === null) return null
    if (res?.success && res.data) {
      // Bugünün satırı varsa o; yoksa yanıt zarfının anı (İstanbul günü); ikisi de yoksa bilinmiyor (kısıt konmaz, sunucu karar verir)
      setServerToday((res.data.days || []).find((d) => d?.today)?.date || istanbulDayOfStamp(res.timestamp) || null)
      setData(res.data); setState('ok'); return res.data
    }
    if (!silent || !dataRef.current) { setState('error'); setErr(res) }
    return null
  }, [setData, teamId, week])

  useEffect(() => {
    setDataState(null)
    dataRef.current = null
    setPanel(null)
    load()
    return () => abort.current?.abort()
  }, [load])

  const days = useMemo(() => data?.days || [], [data?.days])
  // Hafta yanıtında sunucu saati yok: gecikme rozetleri maddenin kendi `overdue` alanından + okuma anından
  const nowMs = useMemo(() => (data ? Date.now() : 0), [data])
  const todayRow = days.find((d) => d.today) || null
  const canWork = !!data?.can_work
  const minDate = serverToday ? addDays(serverToday, -EDIT_WINDOW_DAYS) : null
  const minRef = useRef(minDate)
  minRef.current = minDate
  const dateOf = useCallback((item) => weekContextDate(dataRef.current?.days, item, minRef.current), [])
  const fallbackDate = weekContextDate(days, { period_kind: 'WEEK' }, minDate)
  // Haftanın geçmiş iş günleri var ama hepsi düzenleme penceresinin dışında → salt okunur + neden
  const tooOld = !!minDate && !fallbackDate && days.some((d) => d.workday && d.status !== 'FUTURE' && d.date < minDate)

  const actions = useItemActions({
    teamId, contextDate: fallbackDate, sheetRef: dataRef, setSheet: setData, me: meName,
    reload: () => load({ silent: true }), onChanged: () => refreshRoutineBadge(), contextDateOf: dateOf,
  })

  const onToggle = useLatestCallback((item, st) => { if (canWork && dateOf(item)) actions.setState(item, st) })
  const onSkip = useLatestCallback((item, reason) => { if (canWork && dateOf(item)) actions.setState(item, 'SKIPPED', { skipReason: reason }) })
  const onPanel = useLatestCallback((key, p) => {
    setPanel(p ? { key, panel: p } : null)
    if (!p) returnFocusToRow(key)
  })
  const onNoteSaved = useLatestCallback((_key, d) => { if (d?.item) setData((s) => replaceItem(s, d.item)) })
  const onClearNote = useLatestCallback(async (item) => {
    let res
    try {
      res = await api.oncallRoutines.saveItemNote(teamId, {
        date: dateOf(item), task_id: item.task_id, period_kind: item.period_kind, period_key: item.period_key,
        note: '', note_version: item.note_version || 0,
      })
    } catch (e) { res = thrownResult(e) }
    if (res === null) return
    if (res?.success) setData((s) => replaceItem(s, res.data?.item || { ...item, note: '', note_version: (item.note_version || 0) + 1 }))
    else toast.error(serverText(res, t, 'or.note.saveFailed'), { details: res })
  })
  const onProblemCreated = useLatestCallback((d) => { if (d?.item) setData((s) => replaceItem(s, d.item)); refreshRoutineBadge() })
  const onImagesDone = useLatestCallback(() => {})
  const onEditTemplateRow = useLatestCallback((item) => onEditTemplate?.(item.task_id))

  const onOpenChange = useCallback((kind, id, open) => {
    setCollapsed((prev) => {
      const next = new Set(prev)
      const k = sectionKey(kind, id)
      if (open) next.delete(k); else next.add(k)
      return next
    })
  }, [])

  const renderRow = useCallback((item) => {
    const key = keyOf(item)
    const ctx = weekContextDate(days, item, minDate)
    return (
      <TaskRow key={key} item={item} today={todayRow?.date || null} nowMs={nowMs} contextDate={ctx} teamId={teamId}
        editable={canWork && !!ctx} canEditTemplate={canEditTemplate} panel={panel?.key === key ? panel.panel : null}
        onToggle={onToggle} onSkip={onSkip} onPanel={onPanel} onNoteSaved={onNoteSaved} onClearNote={onClearNote}
        onProblemCreated={onProblemCreated} onImagesDone={onImagesDone} onEditTemplate={onEditTemplateRow} />
    )
  }, [canEditTemplate, canWork, days, onClearNote, onEditTemplateRow, onImagesDone, onNoteSaved, onPanel, onProblemCreated,
    onSkip, onToggle, panel, teamId, todayRow?.date, nowMs, minDate])

  // Klavye: j / k / x / n / p / s / o (gün kısayolları bu görünümde yok)
  const order = useMemo(() => keyboardOrder(data, { collapsed }), [data, collapsed])
  const focusRow = (key) => { if (!key) return; const el = document.querySelector(rowCheckSelector(key)); if (el) { el.focus(); el.scrollIntoView?.({ block: 'nearest' }) } }
  const keyActions = useRef(null)
  keyActions.current = {
    next: (k) => focusRow(stepKey(order, k, 1)),
    prev: (k) => focusRow(stepKey(order, k, -1)),
    toggle: (k) => { const it = k && findItem(dataRef.current, k); if (!it || !canWork || !dateOf(it)) return false; onToggle(it, it.state === 'DONE' ? 'PENDING' : 'DONE') },
    note: (k) => { if (!k) return false; setPanel({ key: k, panel: 'note' }) },
    problem: (k) => { if (!k || !canWork) return false; setPanel({ key: k, panel: 'problem' }) },
    skip: (k) => { if (!k || !canWork) return false; setPanel({ key: k, panel: 'skip' }) },
    links: (k) => { const it = k && findItem(dataRef.current, k); if (!it?.links?.length) return false; openAllLinks(it.links) },
  }
  useRoutineKeys(state === 'ok', keyActions)

  const wk = data?.week
  const shift = (dir) => {
    const base = wk?.key || week
    if (base) onWeekChange?.(addDays(base, dir * 7))
  }
  const isCurrent = !!todayRow
  // Bu hafta ya da tümü gelecek günlerden oluşan bir haftadan ileri gidilmez (işaretlenecek bir şey yok — gün görünümü gibi)
  const atFuture = isCurrent || (days.length > 0 && days.every((d) => d.status === 'FUTURE'))
  const groups = (data?.groups || []).filter((g) => (g.sections || []).some((s) => (s.items || []).length))
  const prevHandover = data?.handover_previous

  return (
    <div data-slot="or-week" data-week={wk?.key || ''} className="@container/page flex flex-col gap-4">
      <Card className="flex-row flex-wrap items-center justify-between gap-3 rounded-xl px-4 py-3">
        <div className="flex min-w-0 items-center gap-3">
          <span aria-hidden="true" className="inline-flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <CalendarRange className="size-4" />
          </span>
          <div className="flex min-w-0 flex-col">
            <h2 className="m-0 text-base font-semibold" data-slot="or-week-range">
              {wk ? t('or.wk.range', shortDate(wk.from), shortDate(wk.to, true)) : t('or.wk.loading')}
            </h2>
            <p className="m-0 text-xs text-muted-foreground">{isCurrent ? t('or.wk.current') : t('or.wk.other')}</p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" aria-label={t('or.wk.prev')}
            data-slot="or-week-nav-prev" onClick={() => shift(-1)} disabled={!wk}>
            <ChevronLeft aria-hidden="true" />
          </Button>
          <Button type="button" variant="outline" className="pointer-coarse:h-10" data-slot="or-week-nav-current"
            onClick={() => onWeekChange?.(null)} disabled={!week}>
            <CalendarDays aria-hidden="true" />{t('or.wk.thisWeek')}
          </Button>
          <Button type="button" variant="outline" size="icon" className="pointer-coarse:size-10" aria-label={t('or.wk.next')}
            data-slot="or-week-nav-next" onClick={() => shift(1)} disabled={!wk || atFuture}>
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
      </Card>

      {state === 'loading' && !data ? (
        <div className="flex flex-col gap-3" aria-busy="true" data-slot="or-week-loading">
          <Skeleton className="h-56 w-full rounded-xl" />
          <Skeleton className="h-40 w-full rounded-xl" />
        </div>
      ) : state === 'error' && !data ? (
        <div data-slot="or-load-failed"><StatusBlock tone="danger" icon={TriangleAlert} role="alert" details={err} className="rounded-xl border"
          title={t('or.wk.loadFailedTitle')} description={serverText(err, t, codeOf(err) === 'NETWORK_ERROR' ? 'or.day.loadFailedNetwork' : 'or.wk.loadFailed')}
          actions={<Button type="button" onClick={() => load()} data-slot="or-load-retry"><RefreshCw aria-hidden="true" />{t('or.retry')}</Button>} /></div>
      ) : data ? (
        <>
          <section aria-labelledby="or-week-days-title" ref={wrapRef} className="flex flex-col gap-2" data-slot="or-week-days">
            <h3 id="or-week-days-title" className="m-0 text-sm font-semibold">{t('or.wk.daysTitle')}</h3>
            {width >= WEEK_TABLE_MIN_PX || width === 0
              ? <WeekTable days={days} byDay={data.responsible_by_day || {}} onOpenDay={onOpenDay} />
              : <WeekCards days={days} byDay={data.responsible_by_day || {}} onOpenDay={onOpenDay} />}
          </section>

          {canWork && tooOld && (
            <AlertBanner tone="info" icon={Lock} className="mb-0">
              <span data-slot="or-week-locked" data-reason="TOO_OLD">{t('or.lock.TOO_OLD')}</span>
            </AlertBanner>
          )}
          {groups.length > 0 ? groups.map((g) => (
            <RoutineBand key={g.kind} group={g} date={fallbackDate || wk?.to}>
              {(g.sections || []).filter((s) => (s.items || []).length).map((s) => (
                <SectionCard key={s.id} kind={g.kind} section={s} date={fallbackDate || wk?.to} today={todayRow?.date || null}
                  nowMs={nowMs} editable={false} open={!collapsed.has(sectionKey(g.kind, s.id))} onOpenChange={onOpenChange}
                  pendingOnly={false} busy={false} onCompleteSection={NOOP} renderRow={renderRow} />
              ))}
            </RoutineBand>
          )) : (
            <p className="m-0 rounded-xl border px-4 py-3 text-sm text-muted-foreground" data-slot="or-week-no-period">{t('or.wk.noPeriodTasks')}</p>
          )}

          <div className="grid gap-3 @min-[900px]/page:grid-cols-2">
            {data.handover_current && (
              <HandoverNote teamId={teamId} handoverCurrent={data.handover_current}
                canWrite={canWork && !tooOld && handoverWritable(data.handover_current, serverToday)}
                onSaved={(d) => { const h = d?.handover || d; if (h) setData((s) => (s ? { ...s, handover_current: { ...s.handover_current, ...h } } : s)) }} />
            )}
            {prevHandover && (
              <HandoverPanel teamId={teamId} handover={{ ...prevHandover, show: true, can_ack: false }} isResponsibleMe={false} />
            )}
          </div>
        </>
      ) : null}
    </div>
  )
}

function dayLabel(t, d) {
  const wd = isoWeekday(d.date)
  return `${t(`or.weekday.${wd}`)} ${shortDate(d.date)}`
}

function ProgressText({ d }) {
  const t = useT()
  if (!d.workday) return <span className="text-muted-foreground">—</span>
  if (d.required == null || d.status === 'FUTURE') return <span className="text-muted-foreground">—</span>
  return <span className="tabular-nums" aria-label={t('or.wk.progressAria', d.resolved ?? 0, d.required)}>{`${d.resolved ?? 0}/${d.required}`}</span>
}

function CompletedText({ d }) {
  const t = useT()
  if (!d.completed_by_name) return <span className="text-muted-foreground">—</span>
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5 [overflow-wrap:anywhere]">
      <span>{d.completed_by_name}{hmOf(d.completed_at) ? ` · ${hmOf(d.completed_at)}` : ''}</span>
      {d.completed_late && <Badge variant="outline" className="h-5 rounded-md px-1.5 text-[11px] font-normal">{t('or.wk.late')}</Badge>}
    </span>
  )
}

function Responsible({ d, byDay }) {
  const t = useT()
  const r = byDay[d.date]
  const name = r?.name || d.responsible_name
  if (!name) return <span className="text-muted-foreground">{t('or.wk.noResponsible')}</span>
  return <PersonName name={name} isMe={!!r?.is_me} />
}

function WeekTable({ days, byDay, onOpenDay }) {
  const t = useT()
  return (
    <div className="overflow-hidden rounded-xl border">
      <Table className="table-fixed" data-slot="or-week-table">
        <TableHeader>
          <TableRow>
            <TableHead className="w-[18%] whitespace-normal">{t('or.wk.colDay')}</TableHead>
            <TableHead className="w-[17%] whitespace-normal">{t('or.wk.colStatus')}</TableHead>
            <TableHead className="w-[11%] whitespace-normal">{t('or.wk.colProgress')}</TableHead>
            <TableHead className="w-[9%] whitespace-normal">{t('or.wk.colProblems')}</TableHead>
            <TableHead className="w-[19%] whitespace-normal">{t('or.wk.colResponsible')}</TableHead>
            <TableHead className="w-[19%] whitespace-normal">{t('or.wk.colCompleted')}</TableHead>
            <TableHead className="w-[7%]"><span className="sr-only">{t('or.wk.colOpen')}</span></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {days.map((d) => (
            <TableRow key={d.date} data-slot="or-week-row" data-date={d.date} data-status={d.status} data-today={d.today || undefined}
              className={cn(!d.workday && 'text-muted-foreground', d.today && 'bg-primary/5')}>
              <TableCell className="whitespace-normal">
                <span className="font-medium">{dayLabel(t, d)}</span>
                {d.today && <Badge variant="secondary" className="ml-1.5 h-5 rounded-md px-1.5 text-[11px]">{t('or.wk.today')}</Badge>}
                {d.holiday_name && <p className="m-0 text-xs [overflow-wrap:anywhere]">{d.holiday_name}</p>}
              </TableCell>
              <TableCell className="whitespace-normal"><DayStatusBadge status={d.status} /></TableCell>
              <TableCell><ProgressText d={d} /></TableCell>
              <TableCell className="tabular-nums">{d.workday ? (d.problems_opened ?? 0) : '—'}</TableCell>
              <TableCell className="whitespace-normal [overflow-wrap:anywhere]">{d.workday ? <Responsible d={d} byDay={byDay} /> : '—'}</TableCell>
              <TableCell className="whitespace-normal"><CompletedText d={d} /></TableCell>
              <TableCell className="text-right">
                {d.workday && d.status !== 'FUTURE' && (
                  <Button type="button" variant="ghost" size="icon" className="size-8 pointer-coarse:size-10" data-slot="or-week-open-day"
                    aria-label={t('or.wk.openDay', dayLabel(t, d))} onClick={() => onOpenDay?.(d.date)}>
                    <ArrowUpRight aria-hidden="true" />
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function WeekCards({ days, byDay, onOpenDay }) {
  const t = useT()
  return (
    <ul className="m-0 grid list-none gap-2 p-0" data-slot="or-week-cards">
      {days.map((d) => (
        <li key={d.date} data-slot="or-week-row" data-date={d.date} data-status={d.status} data-today={d.today || undefined}
          className={cn('flex flex-col gap-2 rounded-xl border px-3 py-2.5', !d.workday && 'text-muted-foreground', d.today && 'border-primary/40 bg-primary/5')}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex min-w-0 flex-wrap items-center gap-1.5">
              <span className="font-medium">{dayLabel(t, d)}</span>
              {d.today && <Badge variant="secondary" className="h-5 rounded-md px-1.5 text-[11px]">{t('or.wk.today')}</Badge>}
            </div>
            <DayStatusBadge status={d.status} />
          </div>
          {d.holiday_name && <p className="m-0 text-xs [overflow-wrap:anywhere]">{d.holiday_name}</p>}
          {d.workday && (
            <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
              <dt className="text-muted-foreground">{t('or.wk.colProgress')}</dt><dd className="m-0"><ProgressText d={d} /></dd>
              <dt className="text-muted-foreground">{t('or.wk.colProblems')}</dt><dd className="m-0 tabular-nums">{d.problems_opened ?? 0}</dd>
              <dt className="text-muted-foreground">{t('or.wk.colResponsible')}</dt><dd className="m-0 [overflow-wrap:anywhere]"><Responsible d={d} byDay={byDay} /></dd>
              <dt className="text-muted-foreground">{t('or.wk.colCompleted')}</dt><dd className="m-0"><CompletedText d={d} /></dd>
            </dl>
          )}
          {d.workday && d.status !== 'FUTURE' && (
            <Button type="button" variant="outline" size="sm" className="w-fit pointer-coarse:h-10" data-slot="or-week-open-day"
              onClick={() => onOpenDay?.(d.date)}>
              {t('or.wk.openDayShort')}<ArrowUpRight aria-hidden="true" />
            </Button>
          )}
        </li>
      ))}
    </ul>
  )
}
