import { useT } from '../../../i18n/index.jsx'
import { formatDateOnly, formatDateSec } from '../../../api/client'
import TeamBadge from '../../ui/TeamBadge.jsx'
import KebabMenu from '../../ui/KebabMenu.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { SystemRoleBadge } from '../ToneBadge.jsx'
import { TH, TD, DataTable, SortTh, Pill, AuthSourceBadge } from '../HealthUi.jsx'
import { AccountBadges, DirField, PresenceAvatar, PresenceLine, TourPill, useDirFormat } from './DirectoryParts.jsx'
import { nextSort, splitDisplayName, userKey } from './directoryModel.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı Dizini listesi (2026-09-28). Geniş ekranda (lg+) shadcn Table: kişi hücresi (durum noktalı avatar + ad +
 * e-posta + kullanıcı adı · departman), rol ve kaynak, takım (TeamBadge), son görülme (çevrimiçiyse "boşta X dk"),
 * son giriş (yöntem + son girişten beri başarısız deneme), hesap ve tur, oluşturulma (xl), işlem menüsü; ad, son
 * görülme, son giriş ve oluşturulma başlıkları SIRALANIR (`aria-sort`). Dar ekranda (telefon + tablet) kart listesi:
 * başlıkta kişi + durum, altında iki sütunlu `dl`, köşede işlem menüsü.
 *
 * Satıra / karta tıklamak ayrıntı panelini açar. Klavye: ad düğmesi (tek Tab durağı); kartta bu düğme "stretched
 * button" (MonitorCard deseni) — kartın her yeri tıklanır, içteki etkileşimli öğeler (takım rozeti, menü) örtünün
 * üstünde. Tabloda satır tıklaması yalnız fare kolaylığı; içteki düğme/bağlantıdan gelen tıklama satırı AÇMAZ.
 */

/**
 * Satır tıklaması satırı AÇMAMALI mı? (1) Portal'dan React ağacı üzerinden kabaran tıklama — işlem menüsü öğesi, takım
 * rozetinin açtığı üye penceresi — satırın DOM'unda değildir; (2) satırdaki bir kontrol (düğme, bağlantı, menü);
 * (3) kullanıcı satırdaki metni SEÇİYOR (e-posta kopyalamak için sürükleme tıklama sayılmaz).
 */
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

function PersonCell({ row, self, onOpen, stretched = false }) {
  const t = useT()
  const { name, dept } = splitDisplayName(row.display_name, row.department)
  const shown = name || row.username
  return (
    <div className="flex min-w-0 items-start gap-2.5">
      <PresenceAvatar row={row} className="mt-0.5" />
      <div className="min-w-0 flex-1">
        <div className="flex min-w-0 items-center gap-1.5">
          <Button type="button" variant="link" data-part="name" onClick={() => onOpen(row)} title={row.display_name || row.username}
            aria-label={t('udir.openFor', shown)}
            className={cn('h-auto min-w-0 justify-start p-0 text-left text-[1em] font-semibold text-foreground underline-offset-2 hover:text-primary',
              stretched && 'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50')}>
            <span className="truncate">{shown}</span>
          </Button>
          {self && <Pill className="shrink-0">{t('uact.selfSession')}</Pill>}
        </div>
        <div data-part="email" className="truncate font-mono text-[11px] text-muted-foreground" title={row.email || ''}>{row.email || '—'}</div>
        <div data-part="meta" className="truncate text-[11px] text-muted-foreground" title={dept || ''}>
          <span className="font-mono">{row.username}</span>{dept && <> · {dept}</>}
        </div>
      </div>
    </div>
  )
}

/** Son giriş: göreli zaman (tam zaman `title`da) + yöntem + son girişten beri başarısız deneme; hiç girmediyse hap. */
export function SignInSummary({ row }) {
  const { t, rel } = useDirFormat()
  if (!row.last_login_at) return <Pill tone="never" status="never">{t('uact.st.never')}</Pill>
  const failed = Number(row.failed_since_login) || 0
  const method = row.last_login_method ? (/remember/i.test(row.last_login_method) ? t('uact.methodRemember') : row.last_login_method) : null
  return (
    <span className="flex min-w-0 flex-col items-start">
      <span className="tabular-nums" title={formatDateSec(row.last_login_at)}>{rel(row.last_login_at)}</span>
      {method && <span className="truncate text-xs text-muted-foreground">{method}</span>}
      {failed > 0 && <span data-failed={failed} className="text-xs font-medium text-destructive">{t('udir.failedSince', failed)}</span>}
    </span>
  )
}

function TeamCell({ row, className }) {
  const extra = [...new Set((row.team_ids || []).map(String))].filter((id) => id !== String(row.team_id ?? ''))
  const t = useT()
  if (!row.team_name && !extra.length) return <span className="text-muted-foreground">—</span>
  return (
    <span className="inline-flex min-w-0 max-w-full flex-wrap items-center gap-1">
      {row.team_name && <TeamBadge teamId={row.team_id} teamName={row.team_name} className={className} />}
      {extra.length > 0 && (
        <span className="inline-flex flex-wrap items-center gap-1 text-[11px] text-muted-foreground" title={t('uact.dirExtraTeams', extra.length)}>
          {extra.slice(0, 2).map((id) => <TeamBadge key={id} teamId={id} size={11} className={className} />)}
          {extra.length > 2 ? `+${extra.length - 2}` : ''}
        </span>
      )}
    </span>
  )
}

function RoleSource({ row }) {
  const t = useT()
  return (
    <span className="flex min-w-0 flex-col items-start gap-1">
      <span className="flex flex-wrap items-center gap-1">
        {row.system_role ? <SystemRoleBadge role={row.system_role} /> : <span className="text-muted-foreground">—</span>}
        <AuthSourceBadge source={row.auth_source} localLabel={t('usr.authLocal')} />
      </span>
      {row.org_role && <span className="max-w-full truncate text-[11px] text-muted-foreground">{t('usr.orgRoleVal.' + row.org_role)}</span>}
    </span>
  )
}

function RowMenu({ row, busy, menuFor }) {
  const t = useT()
  if (busy) return <Spinner size={14} inline label={t('app.loading')} />
  return <KebabMenu items={menuFor(row)} label={t('uact.colAction')} rowLabel={row.username} />
}

function DirTable({ rows, sort, onSort, selectedKey, onOpen, menuFor, busyKeys, username }) {
  const t = useT()
  const sortTh = (col, label, className) => (
    <SortTh label={label} active={sort.col === col} dir={sort.dir} onSort={() => onSort(nextSort(sort, col))} className={className} />
  )
  return (
    <DataTable testId="udir-table" fixed className="text-[0.86em]">
      <TableHeader><TableRow className="hover:bg-transparent">
        {/* Genişlikler (table-fixed, yatay kaydırma yok): lg 26+14+13+14+15+12+6, xl 23+13+12+12+13+12+9+6 = 100 */}
        {sortTh('name', t('udir.colPerson'), 'w-[26%] xl:w-[23%]')}
        <TableHead className={cn(TH, 'w-[14%] xl:w-[13%]')}>{t('udir.colRoleSource')}</TableHead>
        <TableHead className={cn(TH, 'w-[13%] xl:w-[12%]')}>{t('uact.colTeam')}</TableHead>
        {sortTh('last_seen', t('uact.colLastSeen'), 'w-[14%] xl:w-[12%]')}
        {sortTh('last_login', t('udir.lastSignIn'), 'w-[15%] xl:w-[13%]')}
        <TableHead className={cn(TH, 'w-[12%]')}>{t('udir.colAccountTour')}</TableHead>
        {sortTh('created', t('uact.colCreated'), 'hidden xl:table-cell xl:w-[9%]')}
        <TableHead className={cn(TH, 'w-[6%] text-right')}>{t('uact.colAction')}</TableHead>
      </TableRow></TableHeader>
      <TableBody>{rows.map((r) => {
        const key = userKey(r)
        const self = !!username && key === String(username).toLowerCase()
        const cell = cn(TD, 'overflow-hidden py-2 align-top')
        return (
          <TableRow key={key} data-user={r.username} data-online={r.online ? 'true' : undefined} data-self={self ? 'true' : undefined}
            data-state={selectedKey === key ? 'selected' : undefined}
            onClick={(e) => { if (!ignoreRowClick(e)) onOpen(r) }}
            className="cursor-pointer">
            <TableCell data-label={t('udir.colPerson')} className={cell}><PersonCell row={r} self={self} onOpen={onOpen} /></TableCell>
            <TableCell data-label={t('udir.colRoleSource')} className={cell}><RoleSource row={r} /></TableCell>
            <TableCell data-label={t('uact.colTeam')} className={cell}><TeamCell row={r} /></TableCell>
            <TableCell data-label={t('uact.colLastSeen')} className={cn(cell, 'text-xs')}><PresenceLine row={r} /></TableCell>
            <TableCell data-label={t('udir.lastSignIn')} className={cn(cell, 'text-xs')}><SignInSummary row={r} /></TableCell>
            <TableCell data-label={t('udir.colAccountTour')} className={cell}>
              <span className="flex flex-col items-start gap-1">
                <AccountBadges row={r} className="max-w-full [&>[data-slot=badge]]:max-w-full [&>[data-slot=badge]]:text-left [&>[data-slot=badge]]:whitespace-normal" />
                <TourPill row={r} withLabel={false} />
              </span>
            </TableCell>
            <TableCell data-label={t('uact.colCreated')} className={cn(cell, 'hidden text-xs xl:table-cell')}>
              {r.created_at ? <span className="tabular-nums" title={formatDateSec(r.created_at)}>{formatDateOnly(r.created_at)}</span> : '—'}
            </TableCell>
            <TableCell data-label={t('uact.colAction')} className={cn(cell, 'text-right')}>
              <RowMenu row={r} busy={!!busyKeys?.has(key)} menuFor={menuFor} />
            </TableCell>
          </TableRow>
        )
      })}</TableBody>
    </DataTable>
  )
}

function DirCards({ rows, selectedKey, onOpen, menuFor, busyKeys, username }) {
  const t = useT()
  const { dur } = useDirFormat()
  return (
    <ul data-slot="udir-cards" aria-label={t('uact.dirTitle')} className="grid grid-cols-1 gap-2.5 md:grid-cols-2">
      {rows.map((r) => {
        const key = userKey(r)
        const self = !!username && key === String(username).toLowerCase()
        return (
          <li key={key} className="min-w-0">
            <Card data-slot="udir-card" data-user={r.username} data-online={r.online ? 'true' : undefined} data-self={self ? 'true' : undefined}
              data-state={selectedKey === key ? 'selected' : undefined}
              className="relative h-full gap-3 px-4 py-3.5 shadow-xs transition-colors hover:border-primary/50 data-[state=selected]:border-primary data-[state=selected]:ring-1 data-[state=selected]:ring-primary/30">
              <div className="flex min-w-0 items-start gap-2">
                <div className="min-w-0 flex-1"><PersonCell row={r} self={self} onOpen={onOpen} stretched /></div>
                <div className="relative z-10 -mt-1 -mr-2 shrink-0"><RowMenu row={r} busy={!!busyKeys?.has(key)} menuFor={menuFor} /></div>
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                {r.system_role && <SystemRoleBadge role={r.system_role} />}
                <AuthSourceBadge source={r.auth_source} localLabel={t('usr.authLocal')} />
                <TeamCell row={r} className="z-10" />
                <AccountBadges row={r} />
              </div>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-2.5 border-t pt-3">
                <DirField label={t('uact.colLastSeen')}><PresenceLine row={r} /></DirField>
                <DirField label={t('udir.lastSignIn')}><SignInSummary row={r} /></DirField>
                {r.online && <DirField label={t('udir.sessionLength')}>{dur((r.duration_min || 0) * 60)}</DirField>}
                {r.online && <DirField label={t('udir.expiresIn')}>{r.expires_in_sec != null ? dur(r.expires_in_sec) : '—'}</DirField>}
                <DirField label={t('uact.colCreated')}>{r.created_at ? formatDateOnly(r.created_at) : '—'}</DirField>
                <DirField label={t('uact.colTour')}><TourPill row={r} withLabel={false} /></DirField>
              </dl>
            </Card>
          </li>
        )
      })}
    </ul>
  )
}

export default function DirectoryList({ wide, ...props }) {
  return wide ? <DirTable {...props} /> : <DirCards {...props} />
}
