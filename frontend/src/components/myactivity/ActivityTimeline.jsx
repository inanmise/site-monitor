import { useMemo } from 'react'
import {
  LogIn, LogOut, ShieldX, Ban, KeyRound, ShieldAlert, Plus, Pencil, Trash2, FlaskConical, Download, Activity,
  ChevronDown, ExternalLink,
} from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import DiffTable from '../admin/audit/DiffTable.jsx'
import { eventLabel } from '../admin/audit/auditFormat.js'
import { OutcomeBadge } from '../admin/ToneBadge.jsx'
import {
  sentence, eventKind, deviceText, locationText, reasonText, parseFlags, flagLabel, isAuthEvent, recordLink,
  diffOf, detailOf, fmtValue, relTime, clockTime, groupByDay, dayLabel, EV,
} from './activityModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Etkinliklerim — GÜNE göre gruplanmış dikey zaman çizelgesi (yapışkan gün başlıkları "Bugün / Dün / 24 Eylül").
 *
 * Her kayıt tek bir tam genişlik düğme (shadcn Collapsible tetiği): tür ikonu, insan cümlesi, sonuç rozeti (yalnız
 * BAŞARISIZ/ENGELLENEN — başarı olağan durumdur, rozet istisnayı işaret eder), meta (cihaz · konum · IP), saat
 * (tam zaman ipucunda ve açılımda — ipucu dokunmatikte açılmaz). Açılım: tam zaman, ham tür, IP + kopyala, konum,
 * ağ sağlayıcı, ham tarayıcı bilgisi, kayıt (+ ilgili ekrana git), işaretler, işlem kimliği, alan farkı (DiffTable)
 * ve giriş satırlarında "Bu girişi ben yapmadım". Kartta sol renk şeridi YOK; ton ikon dairesinde ve rozette.
 *
 * Telefonda aynı çizelge tek sütun: satır ≥ 48 px, uzun değerler sarar (`[overflow-wrap:anywhere]`).
 */

const KIND = {
  signin:   { Icon: LogIn,        tone: 'bg-success/10 text-success' },
  failed:   { Icon: ShieldX,      tone: 'bg-destructive/10 text-destructive' },
  blocked:  { Icon: Ban,          tone: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  signout:  { Icon: LogOut,       tone: 'bg-muted text-muted-foreground' },
  password: { Icon: KeyRound,     tone: 'bg-primary/10 text-primary' },
  denied:   { Icon: ShieldAlert,  tone: 'bg-destructive/10 text-destructive' },
  create:   { Icon: Plus,         tone: 'bg-primary/10 text-primary' },
  update:   { Icon: Pencil,       tone: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  delete:   { Icon: Trash2,       tone: 'bg-destructive/10 text-destructive' },
  test:     { Icon: FlaskConical, tone: 'bg-sky-500/15 text-sky-700 dark:text-sky-300' },
  export:   { Icon: Download,     tone: 'bg-orange-500/15 text-orange-700 dark:text-orange-300' },
  other:    { Icon: Activity,     tone: 'bg-muted text-muted-foreground' },
}

/** Tür ikonu dairesi (çizelge noktası; "Neler yaptınız" kartı da kullanır). */
export function EventIcon({ row, className }) {
  const kind = KIND[eventKind(row)] || KIND.other
  return (
    <span aria-hidden="true" className={cn('inline-flex size-8 shrink-0 items-center justify-center rounded-full', kind.tone, className)}>
      <kind.Icon className="size-4" />
    </span>
  )
}

/** Sonuç kodu → i18n (literal çağrılar). */
function outcomeText(outcome, t) {
  if (outcome === 'SUCCESS') return t('audit.outcome.success')
  if (outcome === 'FAILURE') return t('audit.outcome.failure')
  if (outcome === 'BLOCKED') return t('audit.outcome.blocked')
  return outcome || '—'
}

/** Meta satırı parçaları "·" ile; boş parça düşer, ayraç ortada kalmaz. */
function MetaLine({ parts, className }) {
  const items = parts.filter(Boolean)
  if (!items.length) return null
  return (
    <span className={cn('flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-0.5', className)}>
      {items.map((p, i) => (
        <span key={i} className="inline-flex min-w-0 items-center gap-1.5 [overflow-wrap:anywhere]">
          {i > 0 && <span aria-hidden="true" className="text-muted-foreground/60">·</span>}
          {p}
        </span>
      ))}
    </span>
  )
}

/** Açılımdaki tek bir bilgi. `wide` iki sütunu kaplar (ham tarayıcı bilgisi). */
function Fact({ label, wide, children }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', wide && 'sm:col-span-2')}>
      <dt className="text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase">{label}</dt>
      <dd className="min-w-0 [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

function EntryDetail({ row, onReport }) {
  const t = useT()
  const when = formatDateSec(row.event_time)
  const rel = relTime(row.event_time, t)
  const reason = reasonText(row, t)
  const loc = locationText(row, t)
  const dev = deviceText(row.ua_summary, t)
  const flags = parseFlags(row.anomaly_flags)
  const link = recordLink(row)
  const diff = diffOf(row)
  const detail = diff ? null : detailOf(row)
  const record = [row.resource_type, row.resource_id].filter(Boolean).join(' · ')
  const canReport = onReport && (row.event_type === EV.SIGN_IN || row.event_type === EV.SIGN_IN_FAILED) && row.id != null
  const touch = 'pointer-coarse:size-10'

  return (
    <div data-slot="activity-detail" className="mx-1 mt-1 mb-3 flex min-w-0 flex-col gap-3 rounded-lg border bg-muted/30 p-3 text-[0.86em] sm:ml-12">
      <dl className="grid min-w-0 grid-cols-1 gap-x-6 gap-y-2.5 sm:grid-cols-2">
        <Fact label={t('myact.d.when')}>
          <span className="tabular-nums">{when}</span>{rel && <span className="text-muted-foreground"> · {rel}</span>}
        </Fact>
        <Fact label={t('myact.d.event')}>
          {eventLabel(row.event_type, t)} <code className="ml-1 rounded bg-muted px-1 py-0.5 font-mono text-[0.85em] text-muted-foreground">{row.event_type}</code>
        </Fact>
        <Fact label={t('myact.d.outcome')}>
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <OutcomeBadge outcome={row.outcome}>{outcomeText(row.outcome, t)}</OutcomeBadge>
            {reason && <span>{reason}</span>}
          </span>
        </Fact>
        {row.ip_address && (
          <Fact label={t('audit.colIp')}>
            <span className="inline-flex flex-wrap items-center gap-1.5">
              <span className="font-mono">{row.ip_address}</span>
              <CopyButton value={row.ip_address} label={t('dev.copyIp')} copiedLabel={t('err.copied')} className={touch} />
            </span>
            {row.ip_reverse_host && <span className="block font-mono text-[0.9em] break-all text-muted-foreground">{row.ip_reverse_host}</span>}
          </Fact>
        )}
        {(loc || row.ip_org) && (
          <Fact label={t('audit.colGeo')}>
            {[loc, row.ip_org && row.ip_org !== 'Internal' ? row.ip_org : null].filter(Boolean).join(' · ') || '—'}
          </Fact>
        )}
        {dev && <Fact label={t('audit.colDevice')}>{dev}</Fact>}
        {record && (
          <Fact label={t('myact.d.record')}>
            <span className="inline-flex max-w-full flex-wrap items-center gap-1.5">
              <span className="font-mono text-[0.92em] break-all">{record}</span>
              {link && (
                <Button type="button" variant="outline" size="xs" className="pointer-coarse:h-10"
                  aria-label={t('a11y.rowAction', row.resource_id, t('myact.open'))}
                  onClick={() => navigateTo(link.tab, link.params)}>
                  <ExternalLink aria-hidden="true" /> {t('myact.open')}
                </Button>
              )}
            </span>
          </Fact>
        )}
        {flags.length > 0 && (
          <Fact label={t('myact.d.flags')}>
            <span className="flex flex-wrap gap-1">
              {flags.map((f) => <Badge key={f} variant="warning" data-flag={f}>{flagLabel(f, t)}</Badge>)}
            </span>
          </Fact>
        )}
        {row.correlation_id && (
          <Fact label={t('audit.correlationId')}>
            <span className="inline-flex flex-wrap items-center gap-1.5">
              <span className="font-mono text-[0.92em] break-all">{row.correlation_id}</span>
              <CopyButton value={row.correlation_id} label={t('myact.copyRef')} copiedLabel={t('err.copied')} className={touch} />
            </span>
          </Fact>
        )}
        {row.user_agent && (
          <Fact wide label={t('dev.rawUa')}>
            <span className="font-mono text-[0.9em] break-all text-muted-foreground">{row.user_agent}</span>
          </Fact>
        )}
      </dl>

      {diff && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <div className="text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase">
            {t('audit.sectionChanges')} <span className="font-normal normal-case">({diff.length})</span>
          </div>
          <div className="max-w-full overflow-x-auto">
            <DiffTable fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
              rows={diff.map(([field, from, to]) => [field, field, fmtValue(from), fmtValue(to)])} />
          </div>
        </div>
      )}
      {detail?.obj && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <div className="text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('audit.sectionDetail')}</div>
          <dl className="grid grid-cols-[minmax(80px,30%)_1fr] gap-x-3 gap-y-1">
            {Object.entries(detail.obj).map(([k, v]) => (
              <div key={k} className="contents">
                <dt className="text-muted-foreground">{k}</dt>
                <dd className="min-w-0 font-mono text-[0.92em] [overflow-wrap:anywhere]">{fmtValue(v)}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
      {detail?.text && (
        <div className="flex min-w-0 flex-col gap-1.5">
          <div className="text-[0.78em] font-semibold tracking-wide text-muted-foreground uppercase">{t('audit.sectionDetail')}</div>
          <p className="m-0 [overflow-wrap:anywhere] whitespace-pre-wrap">{detail.text}</p>
        </div>
      )}

      {canReport && (
        <div className="flex flex-wrap items-center gap-2 border-t pt-3">
          <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10 hover:border-destructive hover:text-destructive"
            aria-label={t('a11y.rowAction', when, t('dev.reportAction'))} onClick={() => onReport(row)}>
            <ShieldAlert aria-hidden="true" /> {t('dev.reportAction')}
          </Button>
          <span className="text-[0.92em] text-muted-foreground">{t('myact.reportHint')}</span>
        </div>
      )}
    </div>
  )
}

function TimelineEntry({ row, open, onOpenChange, onReport, locale }) {
  const t = useT()
  const { text, target } = sentence(row, t)
  const bad = row.outcome && row.outcome !== 'SUCCESS'
  // Mesai dışı işareti hemen her akşam işleminde var — satırda gürültü olur; yalnız açılımda gösterilir.
  const flags = parseFlags(row.anomaly_flags).filter((f) => f !== 'OFF_HOURS')
  const meta = [
    isAuthEvent(row) ? null : deviceText(row.ua_summary, t),
    locationText(row, t),
    row.ip_address ? <span className="font-mono">{row.ip_address}</span> : null,
  ]
  const exact = formatDateSec(row.event_time)
  const rel = relTime(row.event_time, t)

  return (
    <li data-slot="activity-entry" data-event={row.event_type} data-outcome={row.outcome || undefined} className="min-w-0 py-0.5">
      <Collapsible open={open} onOpenChange={onOpenChange}>
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost"
            className="h-auto min-h-12 w-full items-start justify-start gap-3 rounded-lg px-2 py-2.5 text-left font-normal whitespace-normal hover:bg-accent/60 data-[state=open]:bg-accent/40 dark:hover:bg-accent/40">
            <EventIcon row={row} className="mt-0.5" />
            <span className="flex min-w-0 flex-1 flex-col gap-1">
              <span className="flex min-w-0 flex-wrap items-baseline gap-x-2 gap-y-0.5 leading-snug">
                <span data-slot="activity-sentence" className="min-w-0 font-medium [overflow-wrap:anywhere]">{text}</span>
                {target && <span className="min-w-0 text-muted-foreground [overflow-wrap:anywhere]">{target}</span>}
              </span>
              {(bad || flags.length > 0 || meta.some(Boolean)) && (
                <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[0.82em] text-muted-foreground">
                  {bad && <OutcomeBadge outcome={row.outcome}>{outcomeText(row.outcome, t)}</OutcomeBadge>}
                  {flags.map((f) => <Badge key={f} variant="warning" data-flag={f}>{flagLabel(f, t)}</Badge>)}
                  <MetaLine parts={meta} />
                </span>
              )}
            </span>
            <span className="flex shrink-0 items-center gap-1 pt-0.5 text-[0.82em] text-muted-foreground tabular-nums">
              <SimpleTooltip content={rel ? `${exact} · ${rel}` : exact}>
                <time dateTime={row.event_time}>{clockTime(row.event_time, locale)}</time>
              </SimpleTooltip>
              <ChevronDown aria-hidden="true"
                className={cn('size-4 transition-transform duration-200 motion-reduce:transition-none', open && 'rotate-180')} />
            </span>
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          <EntryDetail row={row} onReport={onReport} />
        </CollapsibleContent>
      </Collapsible>
    </li>
  )
}

/**
 * @param rows      gösterilecek satırlar (sunucu sırası: yeni → eski)
 * @param openId    açık kaydın id'si
 * @param onToggle  (id) => void
 * @param onReport  (row) => void — giriş satırlarında "Bu girişi ben yapmadım"
 * @param busy      yenileniyor (liste yerinde kalır, soluklaşır)
 */
export default function ActivityTimeline({ rows, openId, onToggle, onReport, busy = false }) {
  const t = useT()
  const locale = useDateLocale()
  const days = useMemo(() => groupByDay(rows), [rows])
  return (
    <ol data-slot="activity-timeline" aria-label={t('myact.timeline')} aria-busy={busy || undefined}
      className={cn('m-0 flex list-none flex-col gap-3 p-0 transition-opacity', busy && 'opacity-60')}>
      {days.map((day) => (
        <li key={day.key} data-slot="activity-day" className="min-w-0">
          {/* Yapışkan gün başlığı — telefonda üst çubuğun (h-14) altında durur */}
          <h3 data-slot="activity-day-head"
            className="sticky top-14 z-[2] m-0 flex items-center gap-2 border-b bg-card/95 px-2 py-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase backdrop-blur-sm md:top-0">
            <span>{dayLabel(day.key, t, locale)}</span>
            <Badge variant="secondary" className="h-4 px-1.5 text-[10px] tabular-nums">{day.rows.length}</Badge>
          </h3>
          <ol className="m-0 flex list-none flex-col p-0 pt-1">
            {day.rows.map((row, i) => {
              const id = row.id ?? `${day.key}-${i}`
              return (
                <TimelineEntry key={id} row={row} locale={locale} open={openId === id}
                  onOpenChange={() => onToggle(id)} onReport={onReport} />
              )
            })}
          </ol>
        </li>
      ))}
    </ol>
  )
}
