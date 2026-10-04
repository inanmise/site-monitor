import { useState } from 'react'
import { BellRing, ChevronDown, ChevronUp, CloudLightning, Clock, Info } from 'lucide-react'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { formatIncidentTime } from '../../../utils/incidentMeta.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import UserBadge from '../../ui/UserBadge.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import ToneBadge from '../ToneBadge.jsx'
import StormDetailModal from '../../storm/StormDetailModal.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { statusLabel } from './alertHistoryModel.js'
import {
  OUTCOME_TONE, recipientTone, stormPushAt, stormPushHeadline, stormPushOutcome, stormPushReason, stormPushTriggerKey,
} from './stormPushModel.js'

/**
 * Fırtına push'u ↔ alarm bağı (2026-10-04, kullanıcı isteği: "push fırtınaya devredilse bile fırtına ile giden push mesajı
 * ilgili alarmla ilişkilendirilsin — alarmın geçmişinden ne zaman iletildiğini göreyim").
 *
 * - `StormPushSection`: detayın Bildirimler alanındaki "Fırtına push'u" bloğu — alarmı kapsayan her toplu fırtına push'u
 *   (sonuç rozeti, kaç kişiye iletildiği, ilk/son iletim, tahmin rozeti + açıklaması), açılınca alıcı listesi; "henüz fırtına
 *   push'u gitmedi" (fırtına sürüyor, alarm açılış push'undan sonra katıldı) ve boş durum.
 * - `StormPushTimelineEntry`: zaman çizelgesi olayı ("Fırtına #12 push'u iletildi · 12 kişi · 14:05"), alıcılara açılır.
 * - Fırtına numarası mevcut fırtına ayrıntısını (StormDetailModal) açar. Sol renk şeridi YOK; durum rozet + `data-outcome`.
 * Test kancaları: `data-slot="alert-storm-push"`, `sp-card` (+ `data-outcome`, `data-inferred`), `sp-pending`, `sp-empty`,
 * `sp-recipient` (+ `data-status`), `sp-recipients-table` / `sp-recipients-cards`, `sp-storm-link`, `sp-timeline`.
 */

/** Liste kabı bu genişliğin altında kart görünümüne geçer (telefonda detay penceresi ~360 px). */
const TABLE_MIN = 480
const TOUCH = 'max-sm:min-h-10 pointer-coarse:min-h-10'

/** Fırtına numarası → mevcut fırtına ayrıntısı. */
export function StormLink({ stormId, className }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  if (stormId == null) return null
  return (
    <>
      <Button type="button" variant="outline" size="sm" data-slot="sp-storm-link" data-storm-id={stormId}
        aria-label={t('alh.sp.openStorm', stormId)} title={t('alh.sp.openStorm', stormId)}
        onClick={(e) => { e.stopPropagation(); setOpen(true) }}
        className={cn('h-7 gap-1 rounded-full border-violet-500/40 px-2.5 text-xs font-semibold text-violet-700 hover:bg-violet-500/10 dark:text-violet-300',
          'max-sm:h-10 pointer-coarse:h-10', className)}>
        <CloudLightning aria-hidden="true" className="size-3.5" />{t('alh.sp.stormLink', stormId)}
      </Button>
      {open && <StormDetailModal stormId={stormId} onClose={() => setOpen(false)} />}
    </>
  )
}

/** "tahmini" rozeti + açıklama (Popover — dokunmatikte de açılır). */
function InferredBadge() {
  const t = useT()
  return (
    <span className="inline-flex items-center" data-slot="sp-inferred">
      {/* Açıklama tek cümle — ayar yardımı (HelpTip, üç satır sözleşmesi) değil; dokunmatikte de açılan HintPopover. */}
      <HintPopover content={t('alh.sp.inferredHint')} aria-label={t('alh.sp.inferred')}>
        <Badge variant="outline" className="border-dashed font-normal text-muted-foreground">
          {t('alh.sp.inferred')}<Info aria-hidden="true" className="size-3" />
        </Badge>
      </HintPopover>
    </span>
  )
}

/** Alıcı listesi — geniş kapta tablo, dar kapta kart (kap genişliği; jsdom = 0 → tablo). */
export function StormPushRecipients({ item }) {
  const t = useT()
  const locale = useDateLocale()
  const [ref, width] = useElementWidth()
  const rows = item?.recipients ?? []
  const wide = width === 0 || width >= TABLE_MIN
  const when = (r) => (r.sent_at || r.created_at ? formatIncidentTime(r.sent_at || r.created_at, locale) : '—')
  let body
  if (rows.length === 0) {
    body = (
      <p data-slot="sp-no-recipients" className="m-0 text-xs text-muted-foreground">
        {t('alh.sp.noRecipients', stormPushReason(t, item) || '—')}
      </p>
    )
  } else if (wide) {
    body = (
      <div className="overflow-x-auto rounded-md border">
        <Table data-slot="sp-recipients-table" className="table-fixed text-sm">
          <TableHeader className="bg-muted/50">
            <TableRow>
              <TableHead className="px-2">{t('alh.sp.col.person')}</TableHead>
              <TableHead className="w-[170px] px-2">{t('alh.sp.col.status')}</TableHead>
              <TableHead className="w-[150px] px-2">{t('alh.sp.col.time')}</TableHead>
              <TableHead className="w-[70px] px-2 text-right">{t('alh.sp.col.attempts')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id ?? r.username} data-slot="sp-recipient" data-status={r.status}>
                <TableCell className="min-w-0 px-2 py-1.5">
                  <span className="flex min-w-0 items-center gap-1.5">
                    <UserBadge username={r.username} displayName={r.display_name} size="sm" inline nameOnly />
                    <span className="truncate font-mono text-xs text-muted-foreground">{r.username}</span>
                  </span>
                </TableCell>
                <TableCell className="px-2 py-1.5">
                  <ToneBadge tone={recipientTone(r.status)} title={r.status} className="h-auto max-w-full text-left whitespace-normal">{statusLabel(t, r.status)}</ToneBadge>
                </TableCell>
                <TableCell className="px-2 py-1.5 text-xs whitespace-nowrap text-muted-foreground tabular-nums" title={r.sent_at || r.created_at || undefined}>{when(r)}</TableCell>
                <TableCell className="px-2 py-1.5 text-right text-xs tabular-nums">{r.attempts ?? 0}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  } else {
    body = (
      <ul data-slot="sp-recipients-cards" className="m-0 flex list-none flex-col gap-1.5 p-0">
        {rows.map((r) => (
          <li key={r.id ?? r.username} data-slot="sp-recipient" data-status={r.status}
            className="flex min-w-0 flex-col gap-1 rounded-md border px-2.5 py-2 text-sm">
            <span className="flex min-w-0 items-center justify-between gap-2">
              <span className="min-w-0 truncate"><UserBadge username={r.username} displayName={r.display_name} size="sm" inline nameOnly /></span>
              <ToneBadge tone={recipientTone(r.status)} title={r.status} className="h-auto max-w-[60%] shrink-0 text-left whitespace-normal">{statusLabel(t, r.status)}</ToneBadge>
            </span>
            <span className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
              <span className="font-mono">{r.username}</span>
              <span className="tabular-nums">{when(r)}</span>
              {Number(r.attempts) > 1 && <span>{t('alh.sp.col.attempts')}: {r.attempts}</span>}
            </span>
          </li>
        ))}
      </ul>
    )
  }
  return <div ref={ref} className="min-w-0">{body}</div>
}

/** Açılır alıcı düğmesi + liste (zaman çizelgesi ve kart ortak). */
function RecipientsToggle({ item, open, onOpenChange, children }) {
  const t = useT()
  const n = item?.recipient_total ?? (item?.recipients?.length ?? 0)
  // Kişi satırı yoksa (kanal kararı) açılacak bir şey yok — neden zaten satırda yazılı.
  if (!n) return null
  return (
    <Collapsible open={open} onOpenChange={onOpenChange} className="min-w-0">
      <CollapsibleTrigger asChild>
        <Button type="button" variant="ghost" size="sm" data-slot="sp-toggle" aria-expanded={open}
          className={cn('-ml-2 h-8 gap-1 px-2 text-xs text-muted-foreground', TOUCH)}>
          {open ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
          {open ? t('alh.sp.hideRecipients') : t('alh.sp.showRecipients', n)}
        </Button>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex min-w-0 flex-col gap-2 pt-1">{children}</CollapsibleContent>
    </Collapsible>
  )
}

/** Zaman çizelgesi olayı — başlık + fırtına bağlantısı + zaman; alıcılara açılır. */
export function StormPushTimelineEntry({ item }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const outcome = stormPushOutcome(item)
  const reason = stormPushReason(t, item)
  const at = stormPushAt(item)
  return (
    <div data-slot="sp-timeline" data-outcome={outcome} data-trigger={item.trigger} className="flex min-w-0 flex-col gap-1">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <span className="text-sm font-semibold [overflow-wrap:anywhere]">{stormPushHeadline(t, item)}</span>
        <StormLink stormId={item.storm_id} />
        <Badge variant="outline" className="text-[0.7em]">{t(stormPushTriggerKey(item.trigger))}</Badge>
        {item.inferred && <InferredBadge />}
        <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground" title={at || undefined}>
          {at ? formatIncidentTime(at, locale) : t('alh.ev.timeUnknown')}
        </span>
      </div>
      {reason && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('alh.sp.reason', reason)}</p>}
      <RecipientsToggle item={item} open={open} onOpenChange={setOpen}>
        <StormPushRecipients item={item} />
      </RecipientsToggle>
    </div>
  )
}

/** Bloktaki tek bildirim kartı. */
function StormPushCard({ item }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const outcome = stormPushOutcome(item)
  const reason = stormPushReason(t, item)
  const at = stormPushAt(item)
  const fmt = (iso) => (iso ? formatIncidentTime(iso, locale) : '—')
  return (
    <Card data-slot="sp-card" data-outcome={outcome} data-trigger={item.trigger} data-inferred={item.inferred ? 'true' : 'false'}
      className="min-w-0 gap-2 px-3 py-2.5 shadow-none">
      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
        <ToneBadge tone={OUTCOME_TONE[outcome] || 'muted'} className="font-bold">{t(`alh.sp.outcome.${outcome}`)}</ToneBadge>
        <Badge variant="outline">{t(stormPushTriggerKey(item.trigger))}{item.day ? ` · ${item.day}` : ''}</Badge>
        <StormLink stormId={item.storm_id} />
        {item.inferred && <InferredBadge />}
        <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground tabular-nums" title={at || undefined}>{fmt(at)}</span>
      </div>
      <p className="m-0 text-sm font-medium [overflow-wrap:anywhere]">{stormPushHeadline(t, item)}</p>
      {reason && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('alh.sp.reason', reason)}</p>}
      <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
        {item.first_sent_at && (
          <div className="flex min-w-0 gap-1.5"><dt className="text-muted-foreground">{t('alh.sp.firstSent')}</dt><dd className="m-0 tabular-nums">{fmt(item.first_sent_at)}</dd></div>
        )}
        {item.last_sent_at && item.last_sent_at !== item.first_sent_at && (
          <div className="flex min-w-0 gap-1.5"><dt className="text-muted-foreground">{t('alh.sp.lastSent')}</dt><dd className="m-0 tabular-nums">{fmt(item.last_sent_at)}</dd></div>
        )}
        {item.covered_alarms != null && (
          <div className="flex min-w-0 gap-1.5"><dt className="text-muted-foreground">{t('alh.sp.coveredLabel')}</dt><dd data-slot="sp-covered" className="m-0 tabular-nums">{item.covered_alarms}</dd></div>
        )}
      </dl>
      {item.message && (
        <p data-slot="sp-message" className="m-0 rounded-md bg-muted/50 px-2 py-1 text-xs whitespace-pre-wrap [overflow-wrap:anywhere]">
          <span className="font-semibold">{t('alh.sp.message')}: </span>{item.message}
        </p>
      )}
      <RecipientsToggle item={item} open={open} onOpenChange={setOpen}>
        <StormPushRecipients item={item} />
      </RecipientsToggle>
    </Card>
  )
}

/** "Henüz fırtına push'u gitmedi" — fırtına sürüyor, alarm açılış push'undan sonra katıldı. */
function PendingNote({ p }) {
  const t = useT()
  const locale = useDateLocale()
  return (
    <div data-slot="sp-pending" data-storm-id={p.storm_id}
      className="flex min-w-0 flex-wrap items-start gap-2 rounded-md border border-dashed px-3 py-2 text-sm">
      <Clock aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="font-semibold">{t('alh.sp.pending', p.storm_id)}</span>
        <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
          {t('alh.sp.pendingDetail', p.next_realert_at ? formatIncidentTime(p.next_realert_at, locale) : '—')}
        </span>
      </div>
      <StormLink stormId={p.storm_id} />
    </div>
  )
}

/**
 * Detayın "Fırtına push'u" bloğu. `data` = {@link normalizeStormPush} çıktısı; `loading` = teslimatlar yükleniyor.
 */
export function StormPushSection({ data, loading = false }) {
  const t = useT()
  const items = data?.items ?? []
  const pending = data?.pending ?? []
  return (
    <section data-slot="alert-storm-push" aria-label={t('alh.sp.title')} className="mb-3 flex min-w-0 flex-col gap-2 rounded-lg border p-3">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <BellRing aria-hidden="true" className="size-4 text-violet-600 dark:text-violet-300" />
        <h4 className="m-0 text-sm font-semibold">{t('alh.sp.title')}</h4>
        {!loading && <Badge variant="secondary" className="rounded-full">{t('alh.sp.count', items.length)}</Badge>}
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t('alh.sp.intro')}</p>
      {loading && <Skeleton className="h-12 w-full rounded-lg" />}
      {!loading && pending.map((p) => <PendingNote key={`pending-${p.storm_id}`} p={p} />)}
      {!loading && items.length === 0 && pending.length === 0 && (
        <p data-slot="sp-empty" className="m-0 flex items-start gap-1.5 text-sm text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span>{!data?.handedOver && data?.pushIndividual ? t('alh.sp.individual') : t('alh.sp.none')}</span>
        </p>
      )}
      {!loading && items.map((it) => <StormPushCard key={`${it.push_key}|${it.team_id ?? ''}`} item={it} />)}
    </section>
  )
}
