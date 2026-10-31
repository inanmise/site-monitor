import { AlertTriangle, CalendarClock, DatabaseZap, Rows3, Timer, Unplug } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { monitorDeepLink } from '../../utils/monitorDeepLink.js'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import MaintenanceBadge from '../ui/MaintenanceBadge.jsx'
import MonitorSpark from '../ui/MonitorSpark.jsx'
import MonitorCardMeta from '../MonitorCardMeta.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import {
  MonitorCard, MonitorCardHeader, MonitorCardTop, MonitorCardTitle, MonitorCardContent, MonitorCardFooter,
  MonitorCardRich, MonitorCardPending, MonitorAlarmIcon, CARD_LAYER, CARD_COPY,
} from '../monitoring/MonitorCard.jsx'
import {
  breachLevelKey, dbTypeLabel, durationText, istanbulTime, metricValueText, rowCountText, scheduleSummary, untilText,
} from './dbqueryPageModel.js'

/**
 * Veritabanı Sorgu İzleme KARTI (11. tür, 2026-10-31) — paylaşılan MonitorCard ailesi üzerinde (shadcn, mobil duyarlı).
 *
 * <p>Kartın soruları: "hangi bağlantıda, ne için, son çalıştırma ne dedi, ne zaman yine çalışacak?". Başlık izlemenin ADI
 * (alarm anahtarı — takım içinde tekil) ve SARAR (adlar kesilmez); altında bağlantı rozeti (veritabanı türü + bağlantı adı;
 * bağlantı okunamıyor / silinmiş → uyarı rozeti) ve amaç satırı (en çok iki satır — tam metin ayrıntı penceresinde).
 * Ölçü kutuları: ölçülen değer, dönen satır sayısı ("≥ N" sınırlıysa), süre ve sonraki çalışma (göreli + İstanbul saati).
 * Eşik aşımında seviye rozeti (Uyarı / Kritik) durum rozetinin yanında. SATIR VERİSİ KARTTA YOK (ürün kuralı).
 *
 * <p>Paylaşılan standart parçalar SAYFADAN gelir (`selection` = toplu seçim kutusu, `meta` = MonitorCardMeta, `actions` =
 * MonitorCardActions); kart yalnız düzeni taşır. Sol renk şeridi YOK (kalıcı kural) — durum rozetle; FAIL tüm kenarla,
 * alarm MonitorCard'ın dış çizgisiyle, duraklatılmış kart kesik kenarla.
 *
 * <p>Test kancaları: bağlantı `data-slot="dbq-connection"` (+ `data-missing`), amaç `dbq-purpose`, ölçüler `dbq-metrics`
 * (öğe `dbq-metric` + `data-metric` value|rows|duration|next), değer yuvaları `dbq-*-value`, seviye `dbq-level`
 * (+ `data-level`), hata `dbq-error`, bekleme `dbq-pending`, zamanlama `dbq-schedule`, Kompakt özet `dbq-compact`.
 */
export default function DbQueryMonitorCard({
  monitor: m, status = 'unknown', badge, onOpen,
  selection, meta, actions,
  spark, sla, slaTarget, slaDays,
  density = 'rich', running = false, canEdit = false,
}) {
  const t = useT()
  const compact = density === 'compact'
  const down = status === 'down'
  const levelKey = m.status === 'BREACH' ? breachLevelKey(m.breach_level) : null
  const alarmLabel = `${t('dbq.card.activeAlarm')}${m.alarm_level ? ' — ' + m.alarm_level : ''}`
  const rel = m.last_check ? relativeTime(m.last_check, t) : null
  return (
    <MonitorCard status={status} density={density} running={running} alarm={!!m.active_alarm} inactive={m.active === false}
      noc={{ type: 'DBQUERY', monitor: m, rowLabel: m.name, canEdit }}
      className={cn(down && !m.active_alarm && m.active !== false && 'border-destructive/45', compact && 'pt-3 sm:pt-3.5')}>
      <MonitorCardHeader>
        <MonitorCardTop className={compact ? 'mb-2' : undefined} end={
          <CopyLinkButton iconOnly url={monitorDeepLink('dbquery', m.id)} targetName={m.name} variant="ghost" size="icon-xs" className={CARD_COPY} />
        }>
          {selection}
          {badge}
          {levelKey && (
            <Badge variant={m.breach_level === 'CRITICAL' ? 'destructive' : 'warning'} data-slot="dbq-level" data-level={m.breach_level}
              className="px-2 py-[3px] text-[11px] font-bold">{t(levelKey)}</Badge>
          )}
          <MonitorAlarmIcon monitor={m} label={alarmLabel} />
          <span className={CARD_LAYER}><MaintenanceBadge target={m.name} /></span>
        </MonitorCardTop>
        {/* Ad SARAR (adlar kesilmez — kalıcı kural): MonitorCardTitle'ın tek satır kırpması burada kapatılır. */}
        <MonitorCardTitle onOpen={onOpen} label={t('mon.openDetailFor', m.name)} title={m.name}
          className="mb-1.5 whitespace-normal [&>span]:whitespace-normal [&>span]:[overflow-wrap:anywhere]">{m.name}</MonitorCardTitle>
        <ConnectionLine monitor={m} />
        {!compact && m.purpose && (
          <p data-slot="dbq-purpose" className="mt-1.5 line-clamp-2 min-w-0 text-xs leading-snug text-muted-foreground [overflow-wrap:anywhere]">
            {m.purpose}
          </p>
        )}
      </MonitorCardHeader>

      <MonitorCardContent className="pt-2.5">
        {compact
          ? <CompactSummary monitor={m} />
          : (
            <MonitorCardRich>
              {meta && (
                <div className={cn(CARD_LAYER, 'mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5')}>{meta}</div>
              )}
              {m.status === 'FAIL' && m.error && (
                <p data-slot="dbq-error" className="mb-2.5 flex min-w-0 items-start gap-1.5 text-xs leading-snug text-destructive">
                  <AlertTriangle aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                  <span className="line-clamp-2 min-w-0 [overflow-wrap:anywhere]">{m.error}</span>
                </p>
              )}
              {!m.last_check && <MonitorCardPending slot="dbq-pending" />}
              <Metrics monitor={m} />
              <p data-slot="dbq-schedule" className="mb-2 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground">
                <CalendarClock aria-hidden="true" className="mt-px size-3.5 shrink-0" />
                <span className="min-w-0 [overflow-wrap:anywhere]">{scheduleSummary(m, t)}</span>
              </p>
              <div className={CARD_LAYER}>
                <MonitorSpark rowLabel={m.name} spark={spark} sla={sla} slaTarget={slaTarget} slaDays={slaDays} />
              </div>
            </MonitorCardRich>
          )}
      </MonitorCardContent>

      <MonitorCardFooter actions={actions || null} className="mt-auto">
        {m.last_check ? (
          <time dateTime={m.last_check} title={formatDateSec(m.last_check)} className={CARD_LAYER}>
            {rel || formatDateSec(m.last_check)}
          </time>
        ) : ''}
      </MonitorCardFooter>
    </MonitorCard>
  )
}

/** Bağlantı rozeti: veritabanı türü + bağlantı adı (sarar); bağlantı okunamıyor / silinmiş → uyarı tonu. */
function ConnectionLine({ monitor: m }) {
  const t = useT()
  const p = m.profile
  if (!p || m.profile_missing) {
    return (
      <p data-slot="dbq-connection" data-missing="true" className="flex min-w-0 items-center gap-1.5 text-xs font-medium text-amber-700 dark:text-amber-300">
        <Unplug aria-hidden="true" className="size-3.5 shrink-0" />
        <span className="min-w-0 [overflow-wrap:anywhere]">{t('dbq.card.noConnection')}</span>
      </p>
    )
  }
  return (
    <p data-slot="dbq-connection" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
      <Badge variant="outline" className="shrink-0 gap-1 px-1.5 py-0 text-[10.5px] font-semibold">
        <DatabaseZap aria-hidden="true" className="size-3" />{dbTypeLabel(p.db_type)}
      </Badge>
      <span className="min-w-0 font-medium text-foreground [overflow-wrap:anywhere]">{p.name}</span>
    </p>
  )
}

/** Zengin kartın ölçü kutuları — değer, satır, süre, sonraki çalışma. Etkileşimsiz: tıklama detayı açar. */
function Metrics({ monitor: m }) {
  const t = useT()
  const until = m.active === false ? null : untilText(m.next_run_at, t)
  const nextAt = istanbulTime(m.next_run_at)
  const items = [
    { key: 'value', label: t('dbq.card.value'), value: metricValueText(m.metric_value), Icon: null },
    { key: 'rows', label: t('dbq.card.rows'), value: rowCountText(m.row_count, m.row_count_capped), Icon: Rows3 },
    { key: 'duration', label: t('dbq.card.duration'), value: durationText(m.duration_ms, t), Icon: Timer, warn: !!m.slow },
    {
      key: 'next', label: t('dbq.card.nextRun'),
      value: m.active === false ? t('dbq.card.pausedShort') : (until || '—'),
      sub: m.active === false ? null : nextAt,
      Icon: CalendarClock,
    },
  ]
  return (
    <div className="@container mb-2.5">
      <div data-slot="dbq-metrics" role="group" aria-label={t('dbq.card.metrics')} className="grid grid-cols-2 gap-2 @xl:grid-cols-4">
        {items.map((it) => (
          <div key={it.key} data-slot="dbq-metric" data-metric={it.key} data-warn={it.warn ? 'true' : undefined}
            className={cn('flex min-w-0 flex-col rounded-lg border bg-muted/40 px-2.5 py-2 dark:bg-muted/25',
              it.warn && 'border-amber-500/45 bg-amber-500/5 dark:bg-amber-500/10')}>
            <span className="text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase [overflow-wrap:anywhere]">{it.label}</span>
            <span data-slot={`dbq-${it.key}-value`}
              className={cn('mt-1 min-w-0 text-[15px] leading-tight font-semibold text-foreground tabular-nums [overflow-wrap:anywhere]',
                it.warn && 'text-amber-700 dark:text-amber-400')}>{it.value}</span>
            {it.sub && <span className="mt-0.5 text-[10.5px] leading-tight text-muted-foreground tabular-nums [overflow-wrap:anywhere]">{it.sub}</span>}
            {it.warn && <span className="sr-only">{t('dbq.card.slowSr')}</span>}
          </div>
        ))}
      </div>
    </div>
  )
}

/** Kompakt gövde: tek satır özet (değer · satır · süre) + yalnız takım rozeti; hiç çalışmamışsa bekleme satırı. */
function CompactSummary({ monitor: m }) {
  const t = useT()
  return (
    <>
      {m.last_check ? (
        <p data-slot="dbq-compact" className="mb-2 flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5 text-xs text-muted-foreground tabular-nums">
          <span><span className="font-semibold text-foreground">{metricValueText(m.metric_value)}</span> {t('dbq.card.valueShort')}</span>
          <span aria-hidden="true">·</span>
          <span>{t('dbq.card.rowsShort', rowCountText(m.row_count, m.row_count_capped))}</span>
          <span aria-hidden="true">·</span>
          <span className={cn(m.slow && 'font-semibold text-amber-700 dark:text-amber-400')}>{durationText(m.duration_ms, t)}</span>
        </p>
      ) : (
        <MonitorCardPending slot="dbq-pending" compact className="mt-0 mb-2.5" />
      )}
      {m.team_name && (
        <div className={cn(CARD_LAYER, 'mb-2')}>
          <MonitorCardMeta monitor={{ team_id: m.team_id, team_name: m.team_name }} />
        </div>
      )}
    </>
  )
}
