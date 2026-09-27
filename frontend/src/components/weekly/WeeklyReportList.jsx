import { AlertTriangle, ArrowUpDown, FilePenLine, MessageSquare, Search, Undo2, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { relativeTime } from '../admin/audit/auditFormat.js'
import UserBadge from '../ui/UserBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { isoWeekLabel } from './weeklyModel.js'
import { ScoreBadge, SortTh, WeeklyStatusBadge } from './WeeklyListExtras.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/*
 * Haftalık rapor girişleri listesi (2026-09-27 yeniden tasarım): geniş kapta shadcn Table, dar kapta (telefon ya da
 * kenar çubuğu açık tablet, ~440 px) kartlar. Satır/kart raporu açar; işlemler tek KebabMenu'de (adı satırı ayırır).
 * Test kancaları: data-testid="wr-table", kartta li[data-status], data-slot="wr-score" | "wr-status" | "wr-toolbar" |
 * "wr-chip" | "wr-count", yorum sayacı [data-comments].
 */

const SORTS = ['week|desc', 'week|asc', 'updated|desc', 'score|desc', 'status|asc', 'team|asc']

/** Arama + süzgeç çipleri + (dar kapta) sıralama menüsü. Seçiciler (takım/yıl/hafta) `filters` düğümü olarak gelir. */
export function WeeklyListToolbar({ q, onQ, filters, chips = [], onClearAll, shown, total, sort, onSort, showSort }) {
  const t = useT()
  const sortKey = SORTS.includes(sort) ? sort : 'week|desc'
  return (
    <div data-slot="wr-toolbar" className="flex min-w-0 flex-col gap-2 print:hidden">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <InputGroup className="w-full sm:w-72 pointer-coarse:h-10">
          <InputGroupInput type="search" value={q} placeholder={t('wr.searchPh')} aria-label={t('wr.searchPh')}
            className="[&::-webkit-search-cancel-button]:hidden"
            onChange={(e) => onQ(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Escape' && q) { e.preventDefault(); e.stopPropagation(); onQ('') } }} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          {q && (
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" onClick={() => onQ('')} aria-label={t('wr.clearSearch')} title={t('wr.clearSearch')}>
                <X aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          )}
        </InputGroup>
        {filters}
        {showSort && (
          <DropdownMenu modal={false}>
            <DropdownMenuTrigger asChild>
              <Button type="button" variant="outline" size="sm" className="h-9 pointer-coarse:h-10" data-slot="wr-sort">
                <ArrowUpDown aria-hidden="true" />{t('wr.sortBy', t(`wr.sort.${sortKey.replace('|', '_')}`))}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="z-(--z-menu) w-56">
              <DropdownMenuLabel>{t('flt.sort')}</DropdownMenuLabel>
              <DropdownMenuSeparator />
              <DropdownMenuRadioGroup value={sortKey} onValueChange={onSort}>
                {SORTS.map((k) => <DropdownMenuRadioItem key={k} value={k}>{t(`wr.sort.${k.replace('|', '_')}`)}</DropdownMenuRadioItem>)}
              </DropdownMenuRadioGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
        {total > 0 && (
          <span role="status" data-slot="wr-count" className="w-full text-xs text-muted-foreground sm:ml-auto sm:w-auto">{t('wr.shownCount', shown, total)}</span>
        )}
      </div>
      {chips.length > 0 && (
        <div role="group" aria-label={t('wr.activeFilters')} className="flex min-w-0 flex-wrap items-center gap-1.5">
          {chips.map((c) => (
            <Button key={c.key} type="button" variant="secondary" size="xs" data-slot="wr-chip" data-chip={c.key}
              className="h-7 max-w-full rounded-full pr-1.5 font-normal pointer-coarse:h-9"
              aria-label={t('wr.removeFilter', c.label)} title={t('wr.removeFilter', c.label)} onClick={c.onRemove}>
              <span className="truncate">{c.label}</span><X aria-hidden="true" />
            </Button>
          ))}
          <Button type="button" variant="link" size="xs" className="h-7 px-1 pointer-coarse:h-9" onClick={onClearAll}>{t('app.clearFilters')}</Button>
        </div>
      )}
    </div>
  )
}

/**
 * Tablo / kart listesi. `rows` = görünen sayfa, `allRows` = süzülmüş tüm liste ("tümünü seç" onun üzerinde).
 * `menuItems(r)` KebabMenu öğeleri (sayfanın eylemleri), `teamLabel(tid)` / `weekText(r)` çizim yardımcıları.
 */
export default function WeeklyReportList({
  rows, allRows, narrow, isAdmin, selectedIds, onToggle, onToggleAll, teamLabel, weekText, sort, onSort, onOpen, menuItems, mailProblem,
}) {
  const t = useT()
  const rel = (iso) => (iso ? relativeTime(iso, t) : null)
  const time = (iso) => (iso ? <time dateTime={iso} title={formatDate(iso)} className="whitespace-nowrap tabular-nums">{rel(iso)}</time> : '—')
  const rowName = (r) => `${weekText(r)} · ${teamLabel(r.team_id)}`

  const menu = (r) => <KebabMenu label={t('wr.actions')} rowLabel={weekText(r)} items={menuItems(r)} />
  // Ad = HAFTA + TAKIM: toplu "onayla ve gönder" onayı yalnız ADET söylüyor; adsız kutuyla ekran okuyucu
  // kullanıcısı yanlış haftayı gönderebilirdi (2026-09-25, R5).
  const rowCheckbox = (r) => (
    <Checkbox checked={selectedIds.has(r.id)} onCheckedChange={() => onToggle(r.id)}
      aria-label={t('bulk.selectOneFor', rowName(r))} />
  )
  const flags = (r) => (
    <>
      {r.reject_note && r.status !== 'APPROVED' && (
        <span className="inline-flex text-destructive" title={t('wr.rejectFlagTitle')}>
          <Undo2 aria-hidden="true" className="size-3.5" /><span className="sr-only">{t('wr.rejectFlagTitle')}</span>
        </span>
      )}
      {mailProblem(r.last_mail_status) && (
        <span className="inline-flex items-center gap-1 text-[.78em] font-bold text-destructive" title={r.last_mail_status}>
          <AlertTriangle aria-hidden="true" className="size-3" /> {t('wr.sendError')}
        </span>
      )}
    </>
  )
  const editing = (r) => r.editing_by && (
    <span className="inline-flex flex-wrap items-center gap-1 text-[.78em] text-muted-foreground">
      <FilePenLine aria-hidden="true" className="size-3" /> <UserBadge username={r.editing_by} inline size="sm" nameOnly /> {t('wr.editingNow')}
    </span>
  )
  const comments = (r) => (r.comment_count > 0
    ? (
      <Badge variant="secondary" data-comments={r.comment_count} title={t('wr.cm.badge', r.comment_count)} className="h-5 gap-1 px-1.5 text-[11px] tabular-nums">
        <MessageSquare aria-hidden="true" />{r.comment_count}
      </Badge>
    )
    : <span className="text-muted-foreground">—</span>)
  const week = (r) => (
    <span className="flex min-w-0 flex-col">
      <span className="font-semibold whitespace-nowrap">{weekText(r)}</span>
      <span className="font-mono text-[11px] text-muted-foreground">{isoWeekLabel(r.report_year, r.week_no)}</span>
    </span>
  )
  const rowKey = (e, fn) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); fn() } }
  const TH = 'font-semibold'

  if (!narrow) {
    return (
      <div className="min-w-0 overflow-hidden rounded-lg border bg-card">
        <Table data-testid="wr-table" data-tour="wr-table" className="text-[0.88em]">
          <TableHeader className="bg-muted/50">
            <TableRow className="hover:bg-transparent">
              {isAdmin && (
                <TableHead className={cn(TH, 'w-9')}>
                  <Checkbox title={t('wr.selectAll')} aria-label={t('wr.selectAll')}
                    checked={allRows.length > 0 && allRows.every((r) => selectedIds.has(r.id))}
                    onCheckedChange={() => onToggleAll(allRows)} />
                </TableHead>
              )}
              <SortTh col="week" label={t('wr.colWeek')} sort={sort} onSort={onSort} />
              <SortTh col="team" label={t('wr.team')} sort={sort} onSort={onSort} />
              <SortTh col="status" label={t('wr.statusCol')} sort={sort} onSort={onSort} />
              <SortTh col="score" label={t('wr.colScore')} sort={sort} onSort={onSort} />
              <SortTh col="updated" label={t('wr.colUpdated')} sort={sort} onSort={onSort} />
              <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('wr.colComments')}</TableHead>
              <TableHead className={cn(TH, 'hidden xl:table-cell')}>{t('wr.colAuthor')}</TableHead>
              <TableHead className={cn(TH, 'hidden 2xl:table-cell')}>{t('wr.colApproved')}</TableHead>
              <TableHead className={cn(TH, 'w-12')}><span className="sr-only">{t('wr.actions')}</span></TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id} tabIndex={0} data-status={r.status} data-state={selectedIds.has(r.id) ? 'selected' : undefined}
                aria-label={t('a11y.openRow', rowName(r))}
                className="cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
                onClick={() => onOpen(r)} onKeyDown={(e) => rowKey(e, () => onOpen(r))}>
                {isAdmin && <TableCell className="w-9" onClick={(e) => e.stopPropagation()}>{rowCheckbox(r)}</TableCell>}
                <TableCell>{week(r)}</TableCell>
                <TableCell className="max-w-[12rem]"><TeamBadge teamId={r.team_id} teamName={String(teamLabel(r.team_id) ?? '')} /></TableCell>
                <TableCell className="whitespace-normal">
                  <div className="flex min-w-0 flex-col items-start gap-1">
                    <span className="inline-flex flex-wrap items-center gap-1.5"><WeeklyStatusBadge status={r.status} sentAt={r.sent_at} />{flags(r)}</span>
                    {editing(r)}
                  </div>
                </TableCell>
                <TableCell><ScoreBadge score={r.score} title={t('wr.scoreTitle')} /></TableCell>
                <TableCell>
                  <span className="flex min-w-0 flex-col items-start gap-0.5">
                    {r.updated_by ? <UserBadge username={r.updated_by} inline size="sm" nameOnly /> : '—'}
                    <span className="text-[.85em] text-muted-foreground">{time(r.updated_at)}</span>
                  </span>
                </TableCell>
                <TableCell className="hidden lg:table-cell">{comments(r)}</TableCell>
                <TableCell className="hidden xl:table-cell">{r.created_by ? <UserBadge username={r.created_by} inline size="sm" nameOnly /> : '—'}</TableCell>
                <TableCell className="hidden 2xl:table-cell">
                  {r.approved_by ? (
                    <span className="flex min-w-0 flex-col items-start gap-0.5">
                      <UserBadge username={r.approved_by} inline size="sm" nameOnly />
                      <span className="text-[.85em] text-muted-foreground">{r.sent_at ? t('wr.sentRel', rel(r.sent_at)) : time(r.approved_at)}</span>
                    </span>
                  ) : '—'}
                </TableCell>
                <TableCell className="text-right print:hidden" onClick={(e) => e.stopPropagation()}>{menu(r)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }

  // Dar kap: kart listesi — başlık düğmesi raporu açar (ilk düğme), alt şeritte seçim + işlemler (≥ 40 px).
  return (
    <ul data-tour="wr-table" className="flex list-none flex-col gap-2 p-0">
      {rows.map((r) => (
        <li key={r.id} data-status={r.status}
          className={cn('min-w-0 overflow-hidden rounded-lg border bg-card', selectedIds.has(r.id) && 'border-primary bg-primary/5')}>
          <Button type="button" variant="ghost" onClick={() => onOpen(r)}
            className="h-auto w-full flex-col items-stretch gap-2 rounded-none px-3 py-2.5 text-left font-normal whitespace-normal">
            <span className="flex min-w-0 items-start justify-between gap-2">
              {week(r)}
              <ScoreBadge score={r.score} title={t('wr.scoreTitle')} />
            </span>
            <span className="flex min-w-0 flex-wrap items-center gap-1.5">
              <TeamBadge teamId={r.team_id} teamName={String(teamLabel(r.team_id) ?? '')} static />
              <WeeklyStatusBadge status={r.status} sentAt={r.sent_at} />
              {flags(r)}
              {r.comment_count > 0 && comments(r)}
            </span>
            {editing(r)}
            <span className="flex flex-wrap items-center gap-x-1.5 text-[0.8em] text-muted-foreground">
              {t('wr.colUpdated')}: {r.updated_by ? <UserBadge username={r.updated_by} inline size="sm" nameOnly /> : '—'} · {time(r.updated_at)}
            </span>
          </Button>
          <div className="flex min-w-0 items-center justify-between gap-2 border-t px-3 py-1">
            {isAdmin ? <span className="inline-flex size-10 items-center justify-center">{rowCheckbox(r)}</span> : <span />}
            {menu(r)}
          </div>
        </li>
      ))}
    </ul>
  )
}
