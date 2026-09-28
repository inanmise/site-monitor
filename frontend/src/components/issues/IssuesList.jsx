import { MessageSquare, Image as ImageIcon, ArrowLeftRight } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { Card } from '@/components/shadcn/card'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'
import { IssueStatusBadge, SourceBadge, CategoryBadge, UnreadDot, ImpactChips } from './IssueBadges.jsx'
import { fmtDate, fmtRelative, groupBySignature } from './issuesModel.js'

const TH = 'h-9 px-3 text-[0.74em] font-semibold tracking-wide text-muted-foreground uppercase'
const ROW = 'cursor-pointer outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary'

/** Yükleme iskeleti — gerçek satır/kart yüksekliğinde. */
export function ListSkeleton({ phone = false }) {
  return (
    <div data-slot="issues-skeleton" aria-hidden="true" className={cn('flex flex-col gap-2', !phone && 'rounded-lg border bg-card p-2')}>
      {[0, 1, 2, 3, 4].map((k) => <Skeleton key={k} className={phone ? 'h-[112px] w-full rounded-xl' : 'h-14 w-full'} />)}
    </div>
  )
}

/** Satır ↑/↓ gezinme + Enter/Space açma (yalnız satırın kendisinde — hücredeki düğmeler kendi işini yapar). */
function onRowKey(e, open) {
  if (e.target !== e.currentTarget) return
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); return }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const sib = e.key === 'ArrowDown' ? e.currentTarget.nextElementSibling : e.currentTarget.previousElementSibling
    if (sib && typeof sib.focus === 'function') sib.focus()
  }
}

/** Satır/kart altındaki meta çipleri: kaynak, önem, etkiler (en çok 2 + "+N"), görsel, yorum, bağlı çökme kaydı. */
function MetaChips({ row, t, withComments }) {
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
      <SourceBadge source={row.source} />
      <CategoryBadge category={row.category} />
      <ImpactChips row={row} compact />
      {row.imageCount > 0 && (
        <span className="inline-flex items-center gap-0.5" title={t('issues.imagesCount', row.imageCount)}>
          <ImageIcon aria-hidden="true" className="size-3.5" /><span className="tabular-nums">{row.imageCount}</span>
          <span className="sr-only">{t('issues.imagesCount', row.imageCount)}</span>
        </span>
      )}
      {withComments && row.commentCount > 0 && (
        <span className="inline-flex items-center gap-0.5" title={t('issues.commentsCount', row.commentCount)}>
          <MessageSquare aria-hidden="true" className="size-3.5" /><span className="tabular-nums">{row.commentCount}</span>
          <span className="sr-only">{t('issues.commentsCount', row.commentCount)}</span>
        </span>
      )}
      {row.linkedReference && (
        <span className="inline-flex items-center gap-0.5 font-mono" title={t('loginIssues.linkedRefHint')}>
          <ArrowLeftRight aria-hidden="true" className="size-3" />{row.linkedReference}
        </span>
      )}
    </span>
  )
}

/**
 * Kayıt listesi — md+ shadcn Table, telefonda kart yığını. Satır/kart detayı açar; satır klavyeyle odaklanır
 * (Enter/Space açar, ↑/↓ gezer). Okunmamış yönetici yanıtı olan kayıt kalın + nokta (`data-unread`). Açık kayıt
 * TÜM zeminle vurgulanır (sol şerit YOK). Yönetici görünümünde bildiren sütunu (UserBadge).
 */
export default function IssuesList({ rows, admin = false, phone = false, selectedId, onOpen, nowMs = Date.now() }) {
  const t = useT()

  if (phone) {
    return (
      <ul data-slot="issue-list" className="m-0 flex list-none flex-col gap-2 p-0">
        {rows.map((r) => {
          const selected = String(selectedId) === String(r.id)
          return (
            <li key={r.id} className="min-w-0">
              <Card data-slot="issue-card" data-status={r.status} data-unread={r.unread || undefined} data-selected={selected || undefined}
                className={cn('relative min-w-0 gap-0 overflow-hidden py-0 shadow-xs transition-colors hover:border-primary/50',
                  selected && 'border-primary ring-2 ring-primary/40')}>
                <div className="flex min-w-0 items-center gap-2 px-3 pt-2.5">
                  {r.unread && <UnreadDot />}
                  <span className="font-mono text-xs text-muted-foreground">{r.refCode}</span>
                  <IssueStatusBadge status={r.status} className="ml-auto" />
                </div>
                {/* Kartın GERÇEK düğmesi — ::after tüm kartı örter (stretched button; MonitorCard deseni). */}
                <Button type="button" variant="ghost" data-issue-open onClick={() => onOpen(r)}
                  aria-label={t('issues.openReport', r.refCode || r.id)}
                  className={cn('mt-1.5 h-auto w-full min-w-0 justify-start rounded-none px-3 py-0 text-left text-[14px] leading-snug whitespace-normal text-foreground',
                    r.unread ? 'font-bold' : 'font-medium',
                    'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
                    'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')}>
                  <span className="line-clamp-2 min-w-0 [overflow-wrap:anywhere]">{r.messageSummary || '—'}</span>
                </Button>
                <div className="mt-2 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5 border-t px-3 py-2 text-xs text-muted-foreground">
                  <MetaChips row={r} t={t} withComments={!admin} />
                  <span className="ml-auto whitespace-nowrap" title={fmtDate(r.lastActivityAt)}>{fmtRelative(r.lastActivityAt, t, nowMs)}</span>
                </div>
                {admin && r.username && (
                  <div className="border-t px-3 py-1.5 text-xs"><UserBadge username={r.username} inline size="sm" /></div>
                )}
              </Card>
            </li>
          )
        })}
      </ul>
    )
  }

  return (
    <div data-slot="issue-list" className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-[11.5rem]')}>{t('loginIssues.colRef')}</TableHead>
            <TableHead className={cn(TH, 'w-[8.5rem]')}>{t('loginIssues.colStatus')}</TableHead>
            <TableHead className={cn(TH, 'min-w-[18rem]')}>{t('issues.colReport')}</TableHead>
            {admin && <TableHead className={cn(TH, 'hidden lg:table-cell')}>{t('loginIssues.reporterLabel')}</TableHead>}
            <TableHead className={cn(TH, 'hidden whitespace-nowrap lg:table-cell')}>{t('loginIssues.colReportedAt')}</TableHead>
            <TableHead className={cn(TH, 'whitespace-nowrap')}>{t('myIssues.colLastActivity')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => {
            const selected = String(selectedId) === String(r.id)
            const open = () => onOpen(r)
            return (
              <TableRow key={r.id} data-slot="issue-row" data-status={r.status} data-unread={r.unread || undefined} data-issue-id={r.id}
                data-state={selected ? 'selected' : undefined} tabIndex={0}
                aria-label={t('issues.openReport', r.refCode || r.id)}
                className={cn(ROW, selected && 'bg-primary/10 outline outline-2 -outline-offset-2 outline-primary/60 hover:bg-primary/15')}
                onClick={open} onKeyDown={(e) => onRowKey(e, open)}>
                <TableCell className="whitespace-nowrap">
                  <span className="inline-flex items-center gap-1.5">
                    {r.unread ? <UnreadDot /> : <span aria-hidden="true" className="inline-block size-2" />}
                    <span className={cn('font-mono text-xs', r.unread && 'font-bold')}>{r.refCode}</span>
                    {/* Kopyala satırı AÇMAZ */}
                    <span className="inline-flex" onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
                      <CopyButton value={r.refCode} variant="ghost" buttonSize="icon-xs" size={12}
                        label={t('issues.copyRef', r.refCode)} copiedLabel={t('irf.copied')}
                        className="text-muted-foreground opacity-60 hover:opacity-100 focus-visible:opacity-100" />
                    </span>
                  </span>
                </TableCell>
                <TableCell>
                  <IssueStatusBadge status={r.status} />
                  {r.status === 'RESOLVED' && r.resolvedAt && (
                    <span className="mt-0.5 block text-[0.82em] whitespace-nowrap text-muted-foreground" title={fmtDate(r.resolvedAt)}>{fmtDate(r.resolvedAt)}</span>
                  )}
                </TableCell>
                <TableCell className="max-w-[34rem] min-w-[18rem] whitespace-normal">
                  <div className={cn('line-clamp-2 [overflow-wrap:anywhere]', r.unread ? 'font-bold' : 'font-medium')} title={r.messageSummary}>{r.messageSummary || '—'}</div>
                  <div className="mt-1"><MetaChips row={r} t={t} withComments={!admin} /></div>
                </TableCell>
                {admin && (
                  <TableCell className="hidden max-w-[14rem] lg:table-cell">
                    {r.username ? <UserBadge username={r.username} inline size="sm" /> : <span className="text-muted-foreground">—</span>}
                  </TableCell>
                )}
                <TableCell className="hidden whitespace-nowrap text-muted-foreground lg:table-cell">{fmtDate(r.reportedAt)}</TableCell>
                <TableCell className="whitespace-nowrap">
                  <time dateTime={r.lastActivityAt || undefined} title={fmtDate(r.lastActivityAt)}>{fmtRelative(r.lastActivityAt, t, nowMs)}</time>
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}

/**
 * Yönetici triyajı — yüklenen sayfayı hata imzasına göre gruplar (aynı hata kaç kullanıcıda, kaç kez). Satır en
 * yeni kaydın ayrıntısını açar. Geniş kapsam için sayfa boyutu büyütülür (not görünür metin).
 */
export function SignatureGroups({ rows, onOpen }) {
  const t = useT()
  const groups = groupBySignature(rows)
  return (
    <div data-slot="issue-groups" className="overflow-hidden rounded-lg border bg-card">
      <Table className="text-[0.88em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={TH}>{t('loginIssues.colSignature')}</TableHead>
            <TableHead className={cn(TH, 'w-20')}>{t('loginIssues.colGroupCount')}</TableHead>
            <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('loginIssues.colGroupUsers')}</TableHead>
            <TableHead className={cn(TH, 'hidden sm:table-cell')}>{t('loginIssues.colSource')}</TableHead>
            <TableHead className={cn(TH, 'whitespace-nowrap')}>{t('loginIssues.colReportedAt')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {groups.map((g) => {
            const open = () => onOpen({ id: g.latestId })
            return (
              <TableRow key={g.sig} tabIndex={0} className={ROW} title={t('loginIssues.groupOpenHint')}
                aria-label={t('a11y.openRow', g.sig || String(g.count))}
                onClick={open} onKeyDown={(e) => onRowKey(e, open)}>
                <TableCell className="max-w-[26rem] font-mono text-[12.5px] break-all whitespace-normal">{g.sig || '—'}</TableCell>
                <TableCell className="font-semibold tabular-nums">{g.count}</TableCell>
                <TableCell className="hidden whitespace-normal md:table-cell">{g.users.join(', ') || '—'}</TableCell>
                <TableCell className="hidden sm:table-cell"><span className="inline-flex flex-wrap gap-1">{g.sources.map((s) => <SourceBadge key={s} source={s} />)}</span></TableCell>
                <TableCell className="whitespace-nowrap">{fmtDate(g.latestAt)}</TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
