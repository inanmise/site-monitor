import {
  AlertOctagon, BellOff, CalendarCheck, CalendarPlus, ExternalLink, Flame, Hourglass, KeyRound, Link2, Link2Off, MailWarning,
  Play, Server, ShieldAlert, ShieldX, Stethoscope, TextSearch, Wifi, WifiOff, Wrench, CalendarClock, Boxes,
} from 'lucide-react'
import { formatDate, formatDateOnly } from '../../api/client'
import { csvRows } from '../../utils/csv.js'
import { useT } from '../../i18n/index.jsx'
import KebabMenu from '../../components/ui/KebabMenu.jsx'
import { Spinner } from '../../components/ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Dikkat Gerektiren Sertifikalar — ORTAK küçük parçalar (2026-09-27): gerekçe çipleri, plan çipi, kritiklik rozeti,
 * sonraki adım metni + birincil eylem düğmesi, satır menüsü, grup başlığı sözlüğü. Durum yok; liste (tablo / kart) ve
 * kart görünümü şeridi aynı parçaları kullanır.
 *
 * Test kancaları: çip `data-slot="attn-reason"` + `data-reason`, plan çipi `data-slot="attn-plan"`, eylem düğmesi
 * `data-slot="attn-next-action"` + `data-action`, sonraki adım metni `data-slot="attn-next"` + `data-next`.
 */

/** Çip tonları — zemin + mürekkep, koyu karşılıklarıyla (renkli sol şerit YOK; ton çipin kendisinde). */
export const TONE = {
  bad: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  high: 'bg-orange-500/15 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300',
  warn: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  weak: 'bg-purple-500/15 text-purple-700 dark:bg-purple-500/20 dark:text-purple-300',
  info: 'bg-primary/10 text-primary dark:bg-primary/20',
}

const REASON_ICON = {
  error: WifiOff, expired: AlertOctagon, expired1: AlertOctagon, today: Flame, days1: Flame, days: Hourglass,
  revoked: ShieldX, untrusted: ShieldAlert, hostname: ShieldAlert, deployment: Server, chainBroken: Link2Off,
  chainIncomplete: Link2Off, intermediate: Link2, weak: KeyRound, mail: MailWarning, silent: BellOff,
}

/** Grup sözlüğü: ikon + başlık ikon rengi. */
export const GROUP_META = {
  now: { icon: Flame, ink: 'text-destructive', chip: TONE.bad },
  config: { icon: Wrench, ink: 'text-orange-600 dark:text-orange-400', chip: TONE.high },
  soon: { icon: CalendarClock, ink: 'text-amber-600 dark:text-amber-400', chip: TONE.warn },
}

const CHIP = 'h-auto min-w-0 max-w-full gap-1 rounded-md border-0 px-1.5 py-0.5 text-[11px] font-semibold whitespace-normal shadow-none [&>svg]:size-3'

/** Tek gerekçe çipinin metni (CSV de kullanır). */
export function reasonLabel(reason, row, t) {
  const base = t(`attn.r.${reason.key}`, reason.n)
  if (reason.key === 'weak' && row?.public_key_algorithm) {
    return `${base} · ${row.public_key_algorithm}${row.public_key_size ? ` ${row.public_key_size}` : ''}`
  }
  return base
}

/**
 * Gerekçe çipleri. `onMail` verilirse e-posta çipi DÜĞMEDİR (SMTP günlüğüne atlar); `omit` gizlenecek anahtarlar
 * (kart görünümünde kartın kendi gösterdikleri).
 */
export function ReasonChips({ reasons, row, onMail, omit, className }) {
  const t = useT()
  const list = omit ? reasons.filter((r) => !omit.includes(r.key)) : reasons
  if (!list.length) return null
  return (
    <ul data-slot="attn-reasons" aria-label={t('attn.col.why')} className={cn('m-0 flex min-w-0 list-none flex-wrap items-center gap-1 p-0', className)}>
      {list.map((r) => {
        const Icon = REASON_ICON[r.key]
        const label = reasonLabel(r, row, t)
        if (r.key === 'mail' && onMail) {
          return (
            <li key={r.key} className="min-w-0">
              <Button type="button" variant="ghost" size="xs" data-slot="attn-reason" data-reason={r.key}
                title={t('card.mailFailureTooltip')} aria-label={t('a11y.rowAction', row.domain, t('attn.act.mail'))}
                onClick={() => onMail(row.domain)}
                className={cn(CHIP, TONE[r.tone], 'hover:bg-destructive/20 hover:text-destructive pointer-coarse:min-h-8 dark:hover:bg-destructive/30')}>
                {Icon && <Icon aria-hidden="true" />}{label}
              </Button>
            </li>
          )
        }
        return (
          <li key={r.key} className="min-w-0">
            <Badge variant="secondary" data-slot="attn-reason" data-reason={r.key} className={cn(CHIP, TONE[r.tone])}>
              {Icon && <Icon aria-hidden="true" />}{label}
            </Badge>
          </li>
        )
      })}
    </ul>
  )
}

/** Planlı yenileme çipi (Pano kart eklerinden). */
export function PlanChip({ plan, className }) {
  const t = useT()
  if (!plan?.planned_at) return null
  const tone = plan.done ? 'bg-success/15 text-success dark:bg-success/20' : plan.overdue ? TONE.bad : 'bg-violet-500/15 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300'
  const key = plan.done ? 'attn.planDone' : plan.overdue ? 'attn.planOverdue' : 'attn.planned'
  return (
    <Badge variant="secondary" data-slot="attn-plan" data-state={plan.done ? 'done' : plan.overdue ? 'overdue' : 'planned'}
      title={[plan.by, plan.note].filter(Boolean).join(' · ') || undefined} className={cn(CHIP, tone, className)}>
      <CalendarCheck aria-hidden="true" />{t(key, formatDateOnly(plan.planned_at))}
    </Badge>
  )
}

const TIER = { 1: 'bg-indigo-600 text-white', 2: 'bg-sky-600 text-white', 3: 'bg-cyan-600 text-white', 4: 'bg-zinc-500 text-white' }
/** Kritiklik rozeti (Pano kartıyla aynı renkler). */
export function TierBadge({ tier }) {
  if (!tier) return null
  return <Badge data-slot="attn-tier" className={cn('rounded px-1.5 text-[11px] font-extrabold', TIER[tier] ?? TIER[4])}>T{tier}</Badge>
}

/** Sonraki adım metni. */
export function nextText(next, t) {
  return t(`attn.next.${next.key}`, next.date ? formatDateOnly(next.date) : undefined)
}

export function NextStepText({ next, className }) {
  const t = useT()
  return <p data-slot="attn-next" data-next={next.key} className={cn('m-0 text-xs leading-snug text-muted-foreground', className)}>{nextText(next, t)}</p>
}

const ACTION_ICON = { check: Play, health: Stethoscope, plan: CalendarPlus, open: ExternalLink }

/** Birincil eylemin görünür metni. */
export function actionLabel(action, item, t) {
  if (action === 'check') return t('inv.checkNow')
  if (action === 'health') return t('attn.act.health')
  if (action === 'plan') return item.plan?.planned_at && !item.plan?.done ? t('forecast.editPlan') : t('forecast.planRenewal')
  return t('renewal.openCert')
}

/**
 * Sonraki adımın BİRİNCİL düğmesi — adı alan adını taşır (a11y.rowAction). `h` = satır eylemleri
 * `{ onOpen, onHealth, onPlan, onCheck, isChecking }`. Varsayılan görünüm outline: tabloda sütun boyu dolu mavi
 * düğme duvarı olmasın (aciliyeti grup başlığı ve çipler taşır); telefon kartı "Hemen müdahale"de dolu çizer.
 */
export function NextActionButton({ item, h, className, size = 'sm', variant }) {
  const t = useT()
  const { row, next } = item
  const Icon = ACTION_ICON[next.action] ?? ExternalLink
  const label = actionLabel(next.action, item, t)
  const checking = next.action === 'check' && !!h.isChecking?.(row)
  const run = () => {
    if (next.action === 'check') h.onCheck(row)
    else if (next.action === 'health') h.onHealth(row.domain)
    else if (next.action === 'plan') h.onPlan(row, item.plan)
    else h.onOpen(row.domain)
  }
  return (
    <Button type="button" size={size} variant={variant ?? 'outline'}
      data-slot="attn-next-action" data-action={next.action} disabled={checking} aria-busy={checking || undefined}
      aria-label={t('a11y.rowAction', row.domain, label)} title={nextText(next, t)} onClick={run}
      className={cn('pointer-coarse:h-10', className)}>
      {checking ? <Spinner decorative inline /> : <Icon aria-hidden="true" />}{label}
    </Button>
  )
}

/** Satır menüsü: aç · bulgular · planla · şimdi kontrol et · envanterde aç · e-posta hatası. */
export function RowMenu({ item, h }) {
  const t = useT()
  const { row } = item
  const planned = item.plan?.planned_at && !item.plan?.done
  return (
    <KebabMenu rowLabel={row.domain} label={t('tbl.actions')} items={[
      { label: t('renewal.openCert'), icon: <ExternalLink aria-hidden="true" />, onClick: () => h.onOpen(row.domain) },
      { label: t('attn.act.health'), icon: <Stethoscope aria-hidden="true" />, onClick: () => h.onHealth(row.domain), hidden: !h.onHealth },
      { label: planned ? t('forecast.editPlan') : t('forecast.planRenewal'), icon: <CalendarPlus aria-hidden="true" />, onClick: () => h.onPlan(row, item.plan), hidden: !h.onPlan },
      { label: t('inv.checkNow'), icon: <Play aria-hidden="true" />, onClick: () => h.onCheck(row), hidden: !h.onCheck || !!h.isChecking?.(row) },
      { label: t('attn.act.inventory'), icon: <Boxes aria-hidden="true" />, onClick: () => h.onInventory(row.domain) },
      { label: t('attn.act.mail'), icon: <MailWarning aria-hidden="true" />, onClick: () => h.onMail(row.domain), hidden: !h.onMail || !item.reasons.some((r) => r.key === 'mail') },
    ]} />
  )
}

/** Boş/sonuçsuz durum ikonları (sayfa kullanır). */
export const EMPTY_ICONS = { filtered: TextSearch, outage: Wifi }

const CSV_COLS = ['domain', 'section', 'reasons', 'next', 'days', 'expires', 'team', 'tier', 'plan', 'issuer', 'checked', 'error']

/**
 * CSV gövdesi (UTF-8 BOM + CRLF — Excel beklentisi): süzülmüş + sıralı TÜM öğeler (yalnız görünen sayfa değil).
 * Hücre kaçışı + formül nötrleme + CRLF birleştirme tek yerde (`utils/csv.js` csvRows — öneri 29, bayt aynı).
 */
export function attentionCsv(items, t) {
  const head = CSV_COLS.map((k) => t(`attn.csv.${k}`))
  const body = items.map(({ row, group, reasons, next, plan }) => [
    row.domain, t(`attn.group.${group}`), reasons.map((r) => reasonLabel(r, row, t)).join('; '), nextText(next, t),
    row.days_remaining ?? '', row.not_after ? formatDateOnly(row.not_after) : '', row.team_name || '', row.tier ? `T${row.tier}` : '',
    plan?.planned_at ? formatDateOnly(plan.planned_at) : '', row.issuer_cn || row.issuer || '', row.checked_at ? formatDate(row.checked_at) : '',
    row.error || '',
  ])
  return '﻿' + csvRows([head, ...body])
}
