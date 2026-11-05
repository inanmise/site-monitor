import { History, Siren, TriangleAlert } from 'lucide-react'
import { cn } from '@/lib/utils'
import HintPopover from '../ui/HintPopover.jsx'
import { alertTypeLabel } from '../../utils/alertTypeMeta.js'
import { formatDuration } from '../../utils/incidentMeta.js'
import { fieldLabel } from '../history/changeFields.js'
import { shortDateTime } from './responseChartModel.js'

/** Kısa liste: grafiğin altında en çok bu kadar çip; fazlası "+N" notuyla (tümü zaten grafikte çizili). */
export const MAX_EVENT_CHIPS = 8

const CHIP = 'h-auto min-h-8 max-w-full justify-start gap-1.5 rounded-md border border-border bg-background px-2 py-1 text-left text-xs font-medium '
  + 'whitespace-normal text-foreground hover:bg-muted pointer-coarse:min-h-10'

/**
 * Grafiğin OLAY listesi (2026-11-05, dbq2 C2): alarm dönemleri ve değişiklik işaretleri — her biri dokun-gör açıklamalı
 * çip (fare, klavye ve dokunmatik; `HintPopover`). Grafikteki gölgeli dönemler / numaralı kesikli çizgiler bu listeyle
 * eşleşir (değişiklik numarası aynı). Alarm: seviye + tür + başlangıç – bitiş (ya da "sürüyor") + süre; değişiklik: zaman +
 * değişen ALANLARIN ADLARI (değer yok — sunucu göndermez). Test kancaları: `data-slot="chart-events"`, `chart-event`
 * (+ `data-kind` alarm|change, `data-tone`, `data-open`), fazlalık notu `chart-events-more`.
 */
export default function ChartEventList({ t, events, showAlarms = true, showChanges = true }) {
  const alarms = showAlarms ? (events?.alarms || []) : []
  const changes = showChanges ? (events?.changes || []) : []
  const unreadable = [events?.alarmsUnreadable && t('chart.ev.alarmsUnreadable'), events?.changesUnreadable && t('chart.ev.changesUnreadable')].filter(Boolean)
  if (!alarms.length && !changes.length && !unreadable.length) return null
  const shownAlarms = alarms.slice(-MAX_EVENT_CHIPS)
  const shownChanges = changes.slice(-MAX_EVENT_CHIPS)
  const hiddenCount = (alarms.length - shownAlarms.length) + (changes.length - shownChanges.length)
    + (showAlarms ? events?.moreAlarms || 0 : 0) + (showChanges ? events?.moreChanges || 0 : 0)
  return (
    <div data-slot="chart-events" className="flex min-w-0 flex-col gap-1.5 border-t pt-2">
      <span className="text-xs font-semibold text-muted-foreground">{t('chart.ev.title')}</span>
      {(shownAlarms.length > 0 || shownChanges.length > 0) && <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0">
        {shownAlarms.map((a) => <li key={a.key} className="min-w-0 max-w-full"><AlarmChip t={t} a={a} /></li>)}
        {shownChanges.map((c) => <li key={c.key} className="min-w-0 max-w-full"><ChangeChip t={t} c={c} /></li>)}
      </ul>}
      {unreadable.map((u) => <span key={u} data-slot="chart-events-unreadable" className="text-xs text-muted-foreground">{u}</span>)}
      {hiddenCount > 0 && (
        <span data-slot="chart-events-more" className="text-xs text-muted-foreground">{t('chart.ev.more', hiddenCount)}</span>
      )}
    </div>
  )
}

function AlarmChip({ t, a }) {
  const Icon = a.tone === 'critical' ? Siren : TriangleAlert
  const levelText = t(a.tone === 'critical' ? 'chart.ev.alarmCritical' : 'chart.ev.alarmWarning')
  const start = shortDateTime(a.from)
  const end = a.open ? t('chart.ev.ongoing') : shortDateTime(a.to)
  const dur = a.durationMs == null ? null : formatDuration(a.durationMs, t)
  const content = (
    <span className="flex flex-col gap-0.5">
      <strong>{levelText}{a.type ? ` — ${alertTypeLabel(t, a.type)}` : ''}</strong>
      <span>{t('chart.ev.alarmSpan', start, end)}</span>
      {dur && <span>{t('chart.ev.duration', dur)}</span>}
      {a.clippedStart && <span className="text-muted-foreground">{t('chart.ev.clippedStart')}</span>}
      {a.clippedEnd && <span className="text-muted-foreground">{t('chart.ev.clippedEnd')}</span>}
    </span>
  )
  return (
    <HintPopover content={content} triggerClassName={CHIP} data-kind="alarm" data-tone={a.tone} data-open={a.open ? 'true' : 'false'}
      aria-label={`${levelText}: ${t('chart.ev.alarmSpan', start, end)}`}>
      <span data-slot="chart-event" data-kind="alarm" data-tone={a.tone} className="inline-flex min-w-0 items-center gap-1.5">
        <Icon aria-hidden="true" className={cn('size-3.5 shrink-0', a.tone === 'critical' ? 'text-destructive' : 'text-amber-600 dark:text-amber-400')} />
        <span className="min-w-0 [overflow-wrap:anywhere]">{levelText} · {start} – {end}</span>
      </span>
    </HintPopover>
  )
}

function ChangeChip({ t, c }) {
  const at = shortDateTime(c.at)
  const names = c.fields.map((f) => fieldLabel(t, f))
  const list = names.length ? names.join(', ') + (c.moreFields ? ` ${t('chart.ev.moreFields', c.moreFields)}` : '') : t('chart.ev.noFields')
  const content = (
    <span className="flex flex-col gap-0.5">
      <strong>{t('chart.ev.changeN', c.n)} · {at}</strong>
      <span>{t(`chart.ev.event.${c.event || 'UPDATE'}`)}</span>
      <span className="[overflow-wrap:anywhere]">{t('chart.ev.changedFields', list)}</span>
      <span className="text-muted-foreground">{t('chart.ev.changeHint')}</span>
    </span>
  )
  return (
    <HintPopover content={content} triggerClassName={CHIP} data-kind="change" aria-label={`${t('chart.ev.changeN', c.n)}: ${at}`}>
      <span data-slot="chart-event" data-kind="change" className="inline-flex min-w-0 items-center gap-1.5">
        <History aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
        <span className="min-w-0 [overflow-wrap:anywhere]">{t('chart.ev.changeN', c.n)} · {at}</span>
      </span>
    </HintPopover>
  )
}
