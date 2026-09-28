import { Pencil, Clock } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { SeverityBadge, StatusBadge, SlaBadge, CsvChips } from './HistoryBadges.jsx'
import { durationOf, formatMinutes, relativeFrom } from './incidentHistoryModel.js'

const TH = 'h-10 px-3 text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase'
/** Örtünün (kart düğmesinin ::after'ı) ÜSTÜNDE kalması gereken etkileşimli bölge (MonitorCard deseni). */
const LAYER = 'relative z-10'

/** İlk yükleme iskeleti — gerçek satır/kart yüksekliğinde (yerleşim zıplamaz). */
export function HistorySkeleton({ phone = false }) {
  const t = useT()
  return (
    <div data-slot="ih-skeleton" role="status" aria-label={t('inc.loading')}
      className={cn('flex flex-col gap-2', !phone && 'rounded-xl border bg-card p-2')}>
      {[0, 1, 2, 3, 4, 5].map((k) => <Skeleton key={k} aria-hidden="true" className={phone ? 'h-[152px] w-full rounded-xl' : 'h-14 w-full'} />)}
    </div>
  )
}

/** Süre hücresi: kayıtlı ya da hesaplanan süre; çözülmemişse "sürüyor". */
function Duration({ r, nowMs }) {
  const t = useT()
  const d = durationOf(r, nowMs)
  if (d.minutes == null) return <span className="text-muted-foreground">—</span>
  return (
    <span className={cn('inline-flex items-center gap-1 whitespace-nowrap tabular-nums', d.ongoing && 'font-semibold text-destructive')}>
      <Clock aria-hidden="true" className="size-3.5 shrink-0 opacity-70" />
      {d.ongoing ? t('inc.ongoingFor', formatMinutes(d.minutes, t)) : formatMinutes(d.minutes, t)}
    </span>
  )
}

/** Başlığın altındaki ikincil satır: kategori · kanal(lar) · servis/domain(ler) (+N kısaltmalı). */
function MetaLine({ r, className }) {
  const t = useT()
  return (
    <span className={cn('flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground', className)}>
      <span className="font-medium text-foreground/80">{t('inc.cat' + r.category) || r.category}</span>
      {r.channel && <><span aria-hidden="true">·</span><CsvChips value={r.channel} max={2} /></>}
      {r.service && <><span aria-hidden="true">·</span><CsvChips value={r.service} max={1} chipClassName="max-w-[12rem] font-mono text-[11px]" /></>}
    </span>
  )
}

/**
 * Sonuç listesi — geniş ekranda shadcn Table, telefonda kart yığını. Satır/kart tıklaması ayrıntıyı açar; satır klavyeyle
 * odaklanır (Enter/Space açar, ↑/↓ satırlar arasında gezer). Seçim kutusunun ve düzenle düğmesinin adı satırı ayırır.
 * Düşük öncelikli sütunlar dar ekranda gizlenir (Takım/Süre lg, Kaydeden xl). Seçili (açık ayrıntı) satır TÜM zeminle
 * vurgulanır; kartta sol renk şeridi YOK.
 */
export default function HistoryList({
  rows, phone = false, allowManage = false, selected, onToggle, onToggleAll, onOpen, onEdit, activeId, nowMs = Date.now(),
}) {
  const t = useT()
  const dateLocale = useDateLocale()
  const allOnPage = rows.length > 0 && rows.every((r) => selected.has(r.id))
  const someOnPage = !allOnPage && rows.some((r) => selected.has(r.id))
  const name = (r) => r.title || String(r.id)

  const selectBox = (r, className) => (
    <Checkbox checked={selected.has(r.id)} onCheckedChange={() => onToggle(r.id)} className={className}
      aria-label={t('bulk.selectOneFor', name(r))} />
  )
  const editButton = (r, big = false) => (
    <Button type="button" variant="outline" size={big ? 'icon' : 'icon-sm'} title={t('inc.edit')} className={big ? 'size-10' : undefined}
      aria-label={t('a11y.rowAction', name(r), t('inc.edit'))}
      onClick={(e) => { e.stopPropagation(); onEdit(r) }}>
      <Pencil aria-hidden="true" />
    </Button>
  )
  const when = (r) => (
    <span className="flex flex-col leading-tight">
      <span className="whitespace-nowrap tabular-nums">{formatDate(r.occurred_at)}</span>
      <span className="text-xs text-muted-foreground">{relativeFrom(r.occurred_at, dateLocale, nowMs)}</span>
    </span>
  )

  if (phone) {
    return (
      // Kap sorgusu: geniş tablette (kenar çubuğu kapalı) iki sütun, dar alanda tek sütun.
      <div className="@container min-w-0">
      <ul data-slot="ih-card-list" className="m-0 grid list-none grid-cols-1 gap-2 p-0 @2xl:grid-cols-2">
        {rows.map((r) => {
          const isSel = selected.has(r.id)
          const active = activeId != null && String(activeId) === String(r.id)
          return (
            <li key={r.id} className="min-w-0">
              <Card data-slot="ih-card" data-incident-id={r.id} data-status={r.status} data-selected={isSel || undefined}
                className={cn('relative h-full min-w-0 gap-0 overflow-hidden py-0 shadow-xs transition-colors',
                  isSel && 'border-primary bg-primary/5', active && 'ring-2 ring-primary/40')}>
                <div className="flex min-w-0 flex-wrap items-center gap-1.5 px-3 pt-3">
                  <SeverityBadge severity={r.severity} />
                  <StatusBadge status={r.status} />
                  {r.sla_breached && <SlaBadge />}
                  <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground" title={formatDate(r.occurred_at)}>
                    {relativeFrom(r.occurred_at, dateLocale, nowMs)}
                  </span>
                </div>
                {/* Kartın GERÇEK düğmesi — ::after tüm kartı örter (stretched button); odak halkası örtüde. */}
                <Button type="button" variant="ghost" onClick={() => onOpen(r)} aria-label={t('a11y.openRow', name(r))}
                  className={cn('mt-1.5 h-auto w-full min-w-0 justify-start rounded-none px-3 py-0 text-left text-[15px] leading-snug font-bold whitespace-normal text-foreground',
                    'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
                    'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')}>
                  <span className="line-clamp-3 min-w-0 [overflow-wrap:anywhere]">{r.title}</span>
                </Button>
                <MetaLine r={r} className="mt-1.5 mb-3 px-3" />
                <div className="mt-auto flex min-w-0 items-center gap-2 border-t px-2 py-1">
                  {allowManage && (
                    <Label className={cn(LAYER, 'size-10 shrink-0 cursor-pointer justify-center')}>{selectBox(r)}</Label>
                  )}
                  <span className={cn('flex min-w-0 flex-1 items-center gap-2 text-xs', !allowManage && 'pl-1')}>
                    {r.team_name
                      ? <TeamBadge static teamId={r.team_id} teamName={r.team_name} className="min-w-0" />
                      : <span className="text-muted-foreground">{t('inc.noTeam')}</span>}
                    <span className="ml-auto text-muted-foreground"><Duration r={r} nowMs={nowMs} /></span>
                  </span>
                  {allowManage && <span className={cn(LAYER, 'shrink-0')}>{editButton(r, true)}</span>}
                </div>
              </Card>
            </li>
          )
        })}
      </ul>
      </div>
    )
  }

  /** ↑/↓ satırlar arasında odak gezdirir; Enter/Space açar (yalnız satırın kendisinde — hücredeki düğmeler kendi işini yapar). */
  const onRowKey = (e, r) => {
    if (e.target !== e.currentTarget) return
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOpen(r); return }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const sib = e.key === 'ArrowDown' ? e.currentTarget.nextElementSibling : e.currentTarget.previousElementSibling
      if (sib && typeof sib.focus === 'function') sib.focus()
    }
  }

  return (
    <div data-slot="ih-table" className="overflow-hidden rounded-xl border bg-card">
      <Table className="text-[0.9em]">
        <TableHeader className="bg-muted/40">
          <TableRow className="hover:bg-transparent">
            {allowManage && (
              <TableHead className={cn(TH, 'w-10')}>
                <Checkbox checked={allOnPage ? true : someOnPage ? 'indeterminate' : false} onCheckedChange={onToggleAll}
                  aria-label={t('inc.selectAll')} title={t('inc.selectAll')} />
              </TableHead>
            )}
            <TableHead className={TH}>{t('inc.colIncident')}</TableHead>
            <TableHead className={TH}>{t('inc.colStatus')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('inc.colTeam')}</TableHead>
            <TableHead className={TH}>{t('inc.colStarted')}</TableHead>
            <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('inc.colDuration')}</TableHead>
            <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('inc.colRecordedBy')}</TableHead>
            {allowManage && <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('inc.edit')}</span></TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => {
            const active = activeId != null && String(activeId) === String(r.id)
            return (
              <TableRow key={r.id} tabIndex={0} data-incident-id={r.id}
                data-state={selected.has(r.id) ? 'selected' : undefined} data-active={active || undefined}
                aria-label={t('a11y.openRow', name(r))}
                className={cn('cursor-pointer outline-none focus-visible:bg-muted/60 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary',
                  active && 'bg-primary/5')}
                onClick={() => onOpen(r)} onKeyDown={(e) => onRowKey(e, r)}>
                {allowManage && <TableCell className="align-top" onClick={(e) => e.stopPropagation()}>{selectBox(r, 'mt-1')}</TableCell>}
                <TableCell className="max-w-[30rem] min-w-[16rem] align-top whitespace-normal">
                  <div className="flex min-w-0 flex-col gap-1">
                    <span className="flex min-w-0 items-start gap-2">
                      <SeverityBadge severity={r.severity} className="mt-px shrink-0" />
                      <span className="min-w-0 font-semibold [overflow-wrap:anywhere]">{r.title}</span>
                    </span>
                    <MetaLine r={r} />
                  </div>
                </TableCell>
                <TableCell className="align-top">
                  <div className="flex flex-col items-start gap-1">
                    <StatusBadge status={r.status} />
                    {r.sla_breached && <SlaBadge />}
                  </div>
                </TableCell>
                <TableCell className="hidden align-top lg:table-cell">
                  {r.team_name ? <span onClick={(e) => e.stopPropagation()}><TeamBadge teamId={r.team_id} teamName={r.team_name} /></span> : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell className="align-top">{when(r)}</TableCell>
                <TableCell className="hidden align-top lg:table-cell"><Duration r={r} nowMs={nowMs} /></TableCell>
                <TableCell className="hidden max-w-[12rem] align-top xl:table-cell">
                  {r.created_by ? <UserBadge username={r.created_by} inline size="sm" nameOnly /> : <span className="text-muted-foreground">—</span>}
                </TableCell>
                {allowManage && <TableCell className="text-right align-top" onClick={(e) => e.stopPropagation()}>{editButton(r)}</TableCell>}
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
