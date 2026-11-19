import { useEffect, useMemo, useRef, useState } from 'react'
import { CalendarDays, ChevronLeft, ChevronRight, RefreshCcw, RotateCcw, TriangleAlert, Users } from 'lucide-react'
import { api } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import {
  monthGrid, monthTitle, dayTitle, dayNumber, weekdayNames, todayKey, currentMonth, shiftMonth, canGoPrev, canGoNext,
  formatMinutes, bucketOf, BUCKET_MIX, calendarFrom, summarize, outageDayList, defaultTeam, isUnsupportedAnswer,
} from './outageCalendarModel.js'

/**
 * Takım kesinti takvimi (2026-11-19) — Alarm Geçmişi "Takvim" görünümü (tembel parça). Bir ayın her İstanbul gününde
 * takımın izlemelerinden en az birinin KESİNTİ alarmında olduğu süre (çakışmalar bir kez; uyarı / yavaşlık / değişiklik
 * alarmları sayılmaz). Güne basınca liste görünümü o takım + o günle süzülür (`onPickDay`).
 *
 * Renkler tema jetonlarından (`--destructive` kart zeminine karıştırılır, metin her kovada ön plan renginde okunur);
 * kesintisiz gün NÖTR (yeşil değil), gelecek gün soluk ve tıklanamaz. Okunamayan veri "kesinti yok" DEĞİLDİR: hata
 * bloğu + yeniden dene; `truncated` uyarı satırı. Geç gelen yanıt sıra numarasıyla düşürülür.
 *
 * Dar ekranda (kap < 42rem) hücreler yalnız gün numarası + renk taşır; ayrıntı ızgaranın altındaki "Kesintili günler"
 * listesindedir (dokunmatikte ipucu açılmaz). Geniş ekranda hücre süreyi, alarm sayısını ve en kötü seviyeyi yazar.
 *
 * @param teams       görülebilir takımlar ([{id, name}], Alarm Geçmişi'nin zaten yüklediği liste)
 * @param teamsState  'loading' | 'ok' | 'failed'
 * @param teamId      URL'deki takım (`team`) — boşsa varsayılan takım
 * @param month       gösterilen ay (yyyy-MM, doğrulanmış)
 */
export default function OutageCalendar({
  teams = [], teamsState = 'ok', teamId = '', primaryTeamId = null, myTeamIds = null,
  month, onMonthChange, onTeamChange, onPickDay, nowMs: nowOverride = null,
}) {
  const t = useT()
  const locale = useDateLocale()
  const nowMs = nowOverride ?? Date.now()
  const team = defaultTeam({ urlTeam: teamId, primaryTeamId, myTeamIds, teams: teamsState === 'ok' ? teams : null })

  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [unsupported, setUnsupported] = useState(false)
  const [nonce, setNonce] = useState(0)
  const seq = useRef(0)

  useEffect(() => {
    if (!team || !month) return undefined
    const my = ++seq.current
    setLoading(true)
    setError(null)
    setUnsupported(false)
    // Takım / ay değişince eski veri HEMEN kalkar (başka takımın ayı yeni başlık altında görünmesin); Yenile'de kalır.
    setData((d) => (d && d.teamId === String(team) && d.month === month ? d : null))
    Promise.resolve().then(() => api.admin.getOutageCalendar({ team, month }))
      .then((res) => {
        if (my !== seq.current) return
        const cal = res?.success ? calendarFrom(res.data) : null
        if (cal) { setData(cal); return }
        setData(null)
        if (res && isUnsupportedAnswer(res)) setUnsupported(true)
        else setError(res?.error || t('alh.cal.unreadableText'))
      })
      .catch((e) => { if (my === seq.current) { setData(null); setError(e?.message || t('alh.cal.unreadableText')) } })
      .finally(() => { if (my === seq.current) setLoading(false) })
    return undefined
  }, [team, month, nonce]) // eslint-disable-line react-hooks/exhaustive-deps

  const weeks = useMemo(() => monthGrid(month), [month])
  const weekdays = useMemo(() => weekdayNames(locale, 'short'), [locale])
  const summary = useMemo(() => summarize(data), [data])
  const outageDays = useMemo(() => outageDayList(data), [data])
  const today = todayKey(nowMs)
  const teamOptions = useMemo(() => (teams || []).map((tm) => ({ value: String(tm.id), label: tm.name })), [teams])
  const teamName = data?.teamName || teams.find((tm) => String(tm.id) === String(team))?.name || null

  const levelText = (lvl) => (lvl === 'CRITICAL' ? t('alh.statCritical') : lvl === 'HIGH' ? t('alh.statHigh') : lvl === 'WARNING' ? t('alh.statWarning') : null)
  const alarmsText = (n) => t(n === 1 ? 'alh.cal.alarmsOne' : 'alh.cal.alarms', n)
  const dayAria = (d) => {
    const head = dayTitle(d.day, locale)
    if (!d.minutes && !d.alarms) return `${t('alh.cal.aria.none', head)} ${t('alh.cal.aria.open')}`
    const parts = [d.minutes ? t('alh.cal.aria.minutes', formatMinutes(d.minutes, t)) : t('alh.cal.noOutage'), alarmsText(d.alarms)]
    if (d.worst) parts.push(t('alh.cal.aria.level', levelText(d.worst)))
    return `${head}: ${parts.join(', ')}. ${t('alh.cal.aria.open')}`
  }

  const header = (
    <div className="flex min-w-0 flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
      <div className="flex min-w-0 flex-col gap-1">
        <h2 className="flex items-center gap-2 text-base font-semibold"><CalendarDays aria-hidden="true" className="size-4 shrink-0" />{t('alh.cal.title')}</h2>
        <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('alh.cal.desc')}</p>
      </div>
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <div className="min-w-0 flex-1 basis-full sm:basis-auto sm:w-64 sm:flex-none" data-slot="outage-cal-team">
          <SearchableSelect value={team} onChange={(v) => { if (v) onTeamChange?.(String(v)) }} options={teamOptions}
            ariaLabel={t('alh.cal.team')} searchThreshold={6} disabled={teamsState !== 'ok' || teamOptions.length === 0}
            placeholder={teamName || t('alh.cal.team')} wrapLabels />
        </div>
        <div className="flex items-center gap-1" role="group" aria-label={t('alh.cal.monthNav')}>
          <Button type="button" variant="outline" size="icon" aria-label={t('alh.cal.prev')} title={t('alh.cal.prev')}
            data-slot="outage-cal-prev" disabled={!canGoPrev(month, nowMs)} onClick={() => onMonthChange?.(shiftMonth(month, -1))}>
            <ChevronLeft aria-hidden="true" />
          </Button>
          <span data-slot="outage-cal-month" aria-live="polite" className="min-w-32 px-1 text-center text-sm font-semibold tabular-nums">{monthTitle(month, locale)}</span>
          <Button type="button" variant="outline" size="icon" aria-label={t('alh.cal.next')} title={t('alh.cal.next')}
            data-slot="outage-cal-next" disabled={!canGoNext(month, nowMs)} onClick={() => onMonthChange?.(shiftMonth(month, 1))}>
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
        <Button type="button" variant="outline" data-slot="outage-cal-this-month"
          disabled={month === currentMonth(nowMs)} onClick={() => onMonthChange?.(currentMonth(nowMs))}>
          {t('alh.cal.thisMonth')}
        </Button>
        <Button type="button" variant="outline" size="icon" aria-label={t('alh.cal.refresh')} title={t('alh.cal.refresh')}
          data-slot="outage-cal-refresh" disabled={!team} aria-busy={loading || undefined} onClick={() => setNonce((n) => n + 1)}>
          <RefreshCcw aria-hidden="true" className={cn(loading && 'motion-safe:animate-spin')} />
        </Button>
      </div>
    </div>
  )

  let body
  if (!team) {
    body = teamsState === 'loading'
      ? <CalendarSkeleton />
      : teamsState === 'failed'
        ? <StatusBlock tone="danger" icon={Users} title={t('alh.cal.teamsUnreadable')} description={t('alh.cal.teamsUnreadableText')} className="py-10" />
        : <StatusBlock tone="neutral" icon={Users} title={t('alh.cal.noTeams')} description={t('alh.cal.noTeamsText')} className="py-10" />
  } else if (unsupported) {
    body = <StatusBlock tone="neutral" icon={CalendarDays} title={t('alh.cal.unsupported')} description={t('alh.cal.unsupportedText')} className="py-10" />
  } else if (error && !data) {
    body = (
      <StatusBlock tone="danger" icon={TriangleAlert} title={t('alh.cal.unreadable')} description={error} className="py-10" role="alert"
        actions={<Button type="button" variant="outline" data-slot="outage-cal-retry" onClick={() => setNonce((n) => n + 1)}><RotateCcw aria-hidden="true" />{t('alh.cal.retry')}</Button>} />
    )
  } else if (!data) {
    body = <CalendarSkeleton />
  } else {
    body = (
      <div className={cn('flex min-w-0 flex-col gap-3 transition-opacity', loading && 'opacity-60')} aria-busy={loading || undefined}>
        {error && (
          <AlertBanner tone="danger" role="alert" title={t('alh.cal.unreadable')} className="mb-0"
            actions={<Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => setNonce((n) => n + 1)}><RotateCcw aria-hidden="true" />{t('alh.cal.retry')}</Button>}>
            {error}
          </AlertBanner>
        )}
        {data.truncated && (
          <AlertBanner tone="warning" className="mb-0" title={t('alh.cal.truncated')}>{t('alh.cal.truncatedText')}</AlertBanner>
        )}
        <p data-slot="outage-cal-summary" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
          <span className="font-semibold [overflow-wrap:anywhere]">{teamName}</span>
          <span>{t('alh.cal.total', formatMinutes(summary.totalMinutes, t) ?? '—')}</span>
          <span className="text-muted-foreground">{t('alh.cal.outageDays', summary.outageDays)}</span>
          {summary.worstDay && (
            <span className="text-muted-foreground">{t('alh.cal.longest', dayTitle(summary.worstDay.day, locale), formatMinutes(summary.worstDay.minutes, t))}</span>
          )}
        </p>

        <div className="@container min-w-0" data-slot="outage-cal-grid-wrap">
          <div role="group" aria-label={t('alh.cal.gridAria', monthTitle(month, locale), teamName || '')} data-slot="outage-cal-grid"
            className="grid grid-cols-7 gap-1 @xl:gap-1.5">
            {weekdays.map((w, i) => (
              <div key={`w${i}`} aria-hidden="true" className="pb-0.5 text-center text-xs font-medium text-muted-foreground">{w}</div>
            ))}
            {weeks.flat().map((key, i) => {
              if (!key) return <div key={`e${i}`} aria-hidden="true" className="min-h-10 @2xl:min-h-20" />
              const d = data.days.get(key) || { day: key, minutes: null, alarms: 0, worst: null }
              const bucket = bucketOf(d.minutes)
              const isToday = key === today
              if (bucket === 'future') {
                return (
                  <div key={key} data-slot="outage-cal-day" data-day={key} data-bucket="future"
                    className="flex min-h-10 flex-col rounded-md border border-dashed bg-muted/30 p-1 text-muted-foreground @2xl:min-h-20 @2xl:p-1.5">
                    <span className="text-xs font-semibold tabular-nums">{dayNumber(key)}</span>
                    <span className="sr-only">{t('alh.cal.aria.future', dayTitle(key, locale))}</span>
                  </div>
                )
              }
              const mix = BUCKET_MIX[bucket]
              return (
                <Button key={key} type="button" variant="ghost" data-slot="outage-cal-day" data-day={key} data-bucket={String(bucket)}
                  data-today={isToday || undefined} aria-label={dayAria(d)} onClick={() => onPickDay?.(team, key)}
                  style={mix ? { backgroundColor: `color-mix(in srgb, var(--destructive) ${mix}%, var(--card))` } : undefined}
                  className={cn(
                    'h-auto min-h-10 min-w-0 flex-col items-stretch justify-start gap-0.5 whitespace-normal rounded-md border p-1 text-left font-normal text-foreground hover:text-foreground @2xl:min-h-20 @2xl:p-1.5',
                    'pointer-coarse:min-h-11 hover:ring-2 hover:ring-ring/40',
                    !mix && 'bg-card hover:bg-muted/50',
                    isToday && 'ring-2 ring-primary',
                  )}>
                  <span className="text-xs font-semibold tabular-nums">{dayNumber(key)}</span>
                  <span className="hidden min-w-0 flex-col gap-0.5 @2xl:flex" aria-hidden="true">
                    {d.minutes > 0
                      ? <span className="text-sm font-semibold leading-tight [overflow-wrap:anywhere]">{formatMinutes(d.minutes, t)}</span>
                      : <span className="text-xs leading-tight text-muted-foreground [overflow-wrap:anywhere]">{t('alh.cal.noOutage')}</span>}
                    {d.alarms > 0 && (
                      <span className="text-xs leading-tight [overflow-wrap:anywhere]">
                        {alarmsText(d.alarms)}{d.worst ? ` · ${levelText(d.worst)}` : ''}
                      </span>
                    )}
                  </span>
                </Button>
              )
            })}
          </div>

          <ul data-slot="outage-cal-list" aria-label={t('alh.cal.listTitle')} className="mt-3 flex flex-col gap-1.5 @2xl:hidden">
            {outageDays.length === 0 ? (
              <li className="text-sm text-muted-foreground">{t('alh.cal.listEmpty')}</li>
            ) : outageDays.map((d) => (
              <li key={d.day}>
                <Button type="button" variant="outline" data-slot="outage-cal-list-day" data-day={d.day} onClick={() => onPickDay?.(team, d.day)}
                  className="h-auto min-h-10 w-full justify-between gap-2 whitespace-normal py-2 text-left font-normal">
                  <span className="flex min-w-0 items-center gap-2">
                    <span aria-hidden="true" className="size-3 shrink-0 rounded-sm border"
                      style={{ backgroundColor: `color-mix(in srgb, var(--destructive) ${BUCKET_MIX[bucketOf(d.minutes)] ?? 0}%, var(--card))` }} />
                    <span className="whitespace-nowrap font-medium">{dayTitle(d.day, locale)}</span>
                  </span>
                  <span className="text-right text-sm [overflow-wrap:anywhere]">
                    <span className="font-semibold">{formatMinutes(d.minutes, t)}</span>
                    <span className="text-muted-foreground"> · {alarmsText(d.alarms)}{d.worst ? ` · ${levelText(d.worst)}` : ''}</span>
                  </span>
                </Button>
              </li>
            ))}
          </ul>
        </div>

        <div data-slot="outage-cal-legend" className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
          <span className="font-medium">{t('alh.cal.legend.title')}</span>
          <LegendSwatch label={t('alh.cal.legend.zero')} className="bg-card" />
          {[[1, t('alh.cal.legend.b1')], [2, t('alh.cal.legend.b2')], [3, t('alh.cal.legend.b3')], [4, t('alh.cal.legend.b4')]].map(([b, label]) => (
            <LegendSwatch key={b} label={label} style={{ backgroundColor: `color-mix(in srgb, var(--destructive) ${BUCKET_MIX[b]}%, var(--card))` }} />
          ))}
          <LegendSwatch label={t('alh.cal.legend.future')} className="border-dashed bg-muted/30" />
        </div>
      </div>
    )
  }

  return (
    <Card data-slot="outage-calendar" data-team={team || undefined} data-month={month} className="min-w-0 gap-4 p-3 sm:p-4">
      {header}
      {body}
    </Card>
  )
}

function LegendSwatch({ label, className, style }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span aria-hidden="true" className={cn('inline-block size-3 rounded-sm border', className)} style={style} />
      <span>{label}</span>
    </span>
  )
}

function CalendarSkeleton() {
  return (
    <div aria-hidden="true" data-slot="outage-cal-skeleton" className="grid grid-cols-7 gap-1">
      {Array.from({ length: 35 }, (_, i) => <Skeleton key={i} className="h-10 rounded-md sm:h-16" />)}
    </div>
  )
}
