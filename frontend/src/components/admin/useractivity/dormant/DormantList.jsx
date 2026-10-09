import { useT } from '../../../../i18n/index.jsx'
import { formatDateOnly, formatDateSec } from '../../../../api/client'
import TeamBadge from '../../../ui/TeamBadge.jsx'
import ToneBadge, { SystemRoleBadge } from '../../ToneBadge.jsx'
import { TH, TD, DataTable, SortTh, Pill, AuthSourceBadge } from '../../HealthUi.jsx'
import { DirField, PresenceAvatar, useDirFormat } from '../DirectoryParts.jsx'
import { splitDisplayName } from '../directoryModel.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Atıl hesap listesi (2026-10-09). Geniş ekranda (lg+) shadcn Table: kişi (avatar + ad + kullanıcı adı · bölüm),
 * hareketsizlik (gün + dilim rozeti; hiç girmemişse hap + hesap yaşı / "yeni hesap"), son giriş, takım, rol ve kaynak,
 * oluşturulma (xl). Kişi, hareketsizlik ve takım başlıkları SIRALANIR (`aria-sort`). Dar ekranda kart listesi.
 * Satıra / karta tıklamak kullanıcı ayrıntısını açar (`onOpen` → panelin oturum / kullanıcı detayı); kartta ad düğmesi
 * "stretched button" (kartın her yeri tıklanır), takım rozeti örtünün üstünde. SOL RENKLİ ŞERİT YOK.
 * Test kancaları: tablo `data-testid="dormant-table"`, kart `data-slot="dormant-card"`, satır/kart `data-user`,
 * `data-bucket`.
 */

/** Dilim → rozet tonu (uzadıkça kırmızılaşır). */
const BUCKET_TONE = { d30: 'muted', d90: 'warning', d180: 'warning', d365: 'danger' }

/** Satır tıklaması yalnız fare kolaylığı: içteki düğme/bağlantıdan, portal'dan ya da metin seçerken AÇMAZ. */
function ignoreRowClick(e) {
  const target = e.target instanceof Element ? e.target : null
  if (!target || !e.currentTarget.contains(target)) return true
  if (target.closest('button, a, input, select, textarea, [role=menuitem], [role=button]')) return true
  try {
    const sel = window.getSelection?.()
    if (sel && String(sel).trim() && e.currentTarget.contains(sel.anchorNode)) return true
  } catch { /* seçim API'si yok */ }
  return false
}

function PersonCell({ row, onOpen, stretched = false }) {
  const t = useT()
  const { name, dept } = splitDisplayName(row.display_name, row.department)
  const shown = name || row.username
  return (
    <div className="flex min-w-0 items-start gap-2.5">
      <PresenceAvatar row={row} className="mt-0.5" />
      <div className="min-w-0 flex-1">
        <Button type="button" variant="link" data-part="name" onClick={() => onOpen(row)} title={row.display_name || row.username}
          aria-label={t('udir.openFor', shown)}
          className={cn('h-auto min-w-0 max-w-full justify-start p-0 text-left text-[1em] font-semibold text-foreground underline-offset-2 hover:text-primary',
            stretched && 'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')}>
          <span className="truncate">{shown}</span>
        </Button>
        <div data-part="meta" className="truncate text-[11px] text-muted-foreground" title={dept || ''}>
          <span className="font-mono">{row.username}</span>{dept && <> · {dept}</>}
        </div>
      </div>
    </div>
  )
}

/** Hareketsizlik: "142 gün" + dilim rozeti; hiç girmemişse hap + hesap yaşı (30 günden yeniyse "yeni hesap"). */
export function InactiveCell({ row, daysText, align = 'start' }) {
  const t = useT()
  const box = cn('flex min-w-0 flex-col gap-1', align === 'end' ? 'items-end text-right' : 'items-start')
  if (row.days == null) {
    return (
      <span className={box}>
        <Pill tone="never" status="never">{t('uact.st.never')}</Pill>
        {row.is_new
          ? <ToneBadge tone="info" data-new-account="" title={t('dorm.newAccountHint')}>{t('dorm.newAccount')}</ToneBadge>
          : row.age != null && <span className="text-xs text-muted-foreground tabular-nums">{t('dorm.accountAge', daysText(row.age))}</span>}
      </span>
    )
  }
  return (
    <span className={box}>
      <span className="font-semibold tabular-nums">{daysText(row.days)}</span>
      <ToneBadge tone={BUCKET_TONE[row.bucket] || 'muted'} data-bucket-badge={row.bucket}>{t(`dorm.bucket.${row.bucket}`)}</ToneBadge>
    </span>
  )
}

function LastSignIn({ row }) {
  const { rel } = useDirFormat()
  if (!row.last_login_at) return <span className="text-muted-foreground">—</span>
  return (
    <span className="flex min-w-0 flex-col items-start">
      <span className="tabular-nums" title={formatDateSec(row.last_login_at)}>{formatDateOnly(row.last_login_at)}</span>
      <span className="text-xs text-muted-foreground tabular-nums">{rel(row.last_login_at)}</span>
    </span>
  )
}

function TeamCell({ row, className }) {
  if (!row.team_name) return <span className="text-muted-foreground">—</span>
  return <TeamBadge teamId={row.team_id} teamName={row.team_name} className={className} />
}

function RoleSource({ row, roleLabel }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-wrap items-center gap-1">
      {row.system_role ? <SystemRoleBadge role={row.system_role}>{roleLabel ? roleLabel(row.system_role) : row.system_role}</SystemRoleBadge> : <span className="text-muted-foreground">—</span>}
      <AuthSourceBadge source={row.source} localLabel={t('usr.authLocal')} />
    </span>
  )
}

function DormantTable({ rows, sort, onSort, onOpen, roleLabel, daysText }) {
  const t = useT()
  const th = (label, active, dir, next, className) => (
    <SortTh label={label} active={active} dir={dir} onSort={() => onSort(next)} className={className} />
  )
  const idleActive = sort === 'idle_desc' || sort === 'idle_asc'
  return (
    <DataTable testId="dormant-table" fixed className="text-[0.86em]">
      <TableHeader><TableRow className="hover:bg-transparent">
        {/* table-fixed, yatay kaydırma yok: lg 30+17+15+19+19 = 100; xl 27+15+14+17+17+10 = 100 */}
        {th(t('udir.colPerson'), sort === 'name_asc', 'asc', 'name_asc', 'w-[30%] xl:w-[27%]')}
        {th(t('dorm.colInactive'), idleActive, sort === 'idle_asc' ? 'asc' : 'desc', sort === 'idle_desc' ? 'idle_asc' : 'idle_desc', 'w-[17%] xl:w-[15%]')}
        <TableHead className={cn(TH, 'w-[15%] xl:w-[14%]')}>{t('udir.lastSignIn')}</TableHead>
        {th(t('uact.colTeam'), sort === 'team_asc', 'asc', 'team_asc', 'w-[19%] xl:w-[17%]')}
        <TableHead className={cn(TH, 'w-[19%] xl:w-[17%]')}>{t('udir.colRoleSource')}</TableHead>
        <TableHead className={cn(TH, 'hidden xl:table-cell xl:w-[10%]')}>{t('uact.colCreated')}</TableHead>
      </TableRow></TableHeader>
      <TableBody>{rows.map((r) => {
        const cell = cn(TD, 'overflow-hidden py-2 align-top')
        return (
          <TableRow key={r.username} data-user={r.username} data-bucket={r.bucket}
            onClick={(e) => { if (!ignoreRowClick(e)) onOpen(r) }} className="cursor-pointer">
            <TableCell data-label={t('udir.colPerson')} className={cell}><PersonCell row={r} onOpen={onOpen} /></TableCell>
            <TableCell data-label={t('dorm.colInactive')} className={cell}><InactiveCell row={r} daysText={daysText} /></TableCell>
            <TableCell data-label={t('udir.lastSignIn')} className={cn(cell, 'text-xs')}><LastSignIn row={r} /></TableCell>
            <TableCell data-label={t('uact.colTeam')} className={cell}><TeamCell row={r} /></TableCell>
            <TableCell data-label={t('udir.colRoleSource')} className={cell}><RoleSource row={r} roleLabel={roleLabel} /></TableCell>
            <TableCell data-label={t('uact.colCreated')} className={cn(cell, 'hidden text-xs xl:table-cell')}>
              {r.created_at ? <span className="tabular-nums" title={formatDateSec(r.created_at)}>{formatDateOnly(r.created_at)}</span> : '—'}
            </TableCell>
          </TableRow>
        )
      })}</TableBody>
    </DataTable>
  )
}

function DormantCards({ rows, onOpen, roleLabel, daysText }) {
  const t = useT()
  return (
    <ul data-slot="dormant-cards" aria-label={t('dorm.listLabel')} className="m-0 grid list-none grid-cols-1 gap-2.5 p-0 md:grid-cols-2">
      {rows.map((r) => (
        <li key={r.username} className="min-w-0">
          <Card data-slot="dormant-card" data-user={r.username} data-bucket={r.bucket}
            className="relative h-full gap-3 px-4 py-3.5 shadow-xs transition-colors hover:border-primary/50">
            <div className="flex min-w-0 items-start gap-2">
              <div className="min-w-0 flex-1"><PersonCell row={r} onOpen={onOpen} stretched /></div>
              <div className="shrink-0 text-sm"><InactiveCell row={r} daysText={daysText} align="end" /></div>
            </div>
            <div className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm">
              <RoleSource row={r} roleLabel={roleLabel} />
              {r.team_name && <span className="relative z-10 inline-flex min-w-0 max-w-full"><TeamCell row={r} /></span>}
            </div>
            <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-2.5 border-t pt-3">
              <DirField label={t('udir.lastSignIn')}><LastSignIn row={r} /></DirField>
              <DirField label={t('uact.colCreated')}>{r.created_at ? formatDateOnly(r.created_at) : '—'}</DirField>
            </dl>
          </Card>
        </li>
      ))}
    </ul>
  )
}

export default function DormantList({ wide, ...props }) {
  return wide ? <DormantTable {...props} /> : <DormantCards {...props} />
}
