import { ArrowDown, ArrowUp, ArrowUpDown, Clock } from 'lucide-react'
import { formatDate, formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { relTime } from '../certtable/certTableModel.js'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { issuerOf } from './statsModel.js'
import { DaysBadge, LevelBadge, TierBadge } from './statsUi.jsx'

/**
 * Sertifika listesi (2026-09-28): geniş kapta shadcn Table — satırın tamamı sertifika penceresini açar (Enter/Space
 * da), başlıktan sıralama (aynı durum, toolbar menüsüyle eş), düşük öncelikli sütunlar KAP genişliğine göre gizlenir
 * (@container); dar kapta (telefon / kenar çubuklu dar tablet) kart listesi — "stretched button" deseni: kartın
 * tamamı tek düğme, takım rozeti üstte ayrı düğme. Kartlarda sol renk şeridi YOK; durum rozetle.
 *
 * Takım: sorumlu (SY) ve uygulama (UG) takımları `ui/TeamBadge` — rozet hücresi satır tıklamasına SIZMAZ (üye
 * penceresindeki tıklama da React ağacında satıra kabarcıklanırdı). Kalan gün: aciliyet rozeti (tabular sayı, tam
 * metin `title` + ekran okuyucu). Son kontrol: göreli, tam zaman `title`'da.
 *
 * Test kancaları: `data-slot="stats-table"`, satır `stats-row` + `data-domain`, kart `stats-card` + `data-domain`,
 * sıralama başlığı `stats-sort-head` (+ `aria-sort` başlık hücresinde).
 */
const SORTABLE = { domain: 'domain', days: 'days_remaining', issuer: 'issuer', checked: 'checked_at' }
const TH = 'h-10 bg-muted/60 px-3 text-xs font-semibold text-muted-foreground'
const stop = (e) => e.stopPropagation()

export default function CertList({ rows, narrow, dtm, onOpen, sort, onSort }) {
  if (narrow) return <CertCards rows={rows} dtm={dtm} onOpen={onOpen} />
  return <CertTable rows={rows} dtm={dtm} onOpen={onOpen} sort={sort} onSort={onSort} />
}

function teamsFor(cert, dtm) {
  const tm = dtm?.get(cert.domain)
  const sy = tm?.sy?.length ? tm.sy.map((name) => ({ name, id: name === cert.team_name ? cert.team_id : undefined }))
    : cert.team_name ? [{ name: cert.team_name, id: cert.team_id }] : []
  const ug = (tm?.ug ?? []).filter((n) => !sy.some((s) => s.name === n)).map((name) => ({ name }))
  return { sy, ug }
}

/**
 * Telefon kartında rozetin dokunma alanı (görünür boyut değişmez): TeamBadge'in `pointer-coarse:` genişletmesinin
 * ekran genişliğine bağlı eşi — işaretçi türü algılanamayan tarayıcıda da 40 px. Rozetler kartta YAN YANA dizilir
 * (dikey genişletmeler birbirine binmesin).
 */
const PHONE_TOUCH = 'max-sm:overflow-visible max-sm:after:absolute max-sm:after:inset-x-0 max-sm:after:-inset-y-3'

function TeamsCell({ cert, dtm, row = false, className }) {
  const t = useT()
  const { sy, ug } = teamsFor(cert, dtm)
  if (!sy.length && !ug.length) return <span className="text-muted-foreground">—</span>
  const badgeCls = row ? PHONE_TOUCH : undefined
  return (
    <span className={cn(row ? 'flex min-w-0 flex-wrap items-center gap-x-4 gap-y-3' : 'flex min-w-0 flex-col gap-0.5', className)}>
      {sy.map((s) => (
        <span key={`sy-${s.name}`} className="inline-flex min-w-0 items-center gap-1">
          <span className="text-[10px] font-bold text-muted-foreground" title={t('sv.colSyTeam')}>{t('stv.sy')}</span>
          <TeamBadge teamId={s.id} teamName={s.name} className={badgeCls} />
        </span>
      ))}
      {ug.map((u) => (
        <span key={`ug-${u.name}`} className="inline-flex min-w-0 items-center gap-1">
          <span className="text-[10px] font-bold text-muted-foreground" title={t('sv.colUgTeam')}>{t('stv.ug')}</span>
          <TeamBadge teamName={u.name} className={badgeCls} />
        </span>
      ))}
    </span>
  )
}

function Checked({ iso }) {
  const t = useT()
  if (!iso) return <span className="text-muted-foreground">—</span>
  const rel = relTime(iso)
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap" title={formatDate(iso)}>
      <Clock aria-hidden="true" className="size-3 shrink-0 text-muted-foreground" />
      {rel ? t(`tbl.rel.${rel.unit}`, rel.n) : formatDate(iso)}
      <span className="sr-only">({formatDate(iso)})</span>
    </span>
  )
}

function SortHead({ col, label, sort, onSort, className }) {
  const t = useT()
  const key = SORTABLE[col]
  const [cur, dir] = String(sort || '').split('|')
  const active = cur === key
  const next = active && dir === 'asc' ? 'desc' : 'asc'
  const Icon = !active ? ArrowUpDown : dir === 'asc' ? ArrowUp : ArrowDown
  return (
    <TableHead className={cn(TH, className)} aria-sort={active ? (dir === 'asc' ? 'ascending' : 'descending') : undefined}>
      <Button type="button" variant="ghost" size="sm" data-slot="stats-sort-head" data-col={col}
        className="-ml-2 h-8 gap-1 px-2 text-xs font-semibold text-muted-foreground hover:text-foreground"
        title={t('stv.sortCol', label)} onClick={() => onSort(`${key}|${next}`)}>
        {label}<Icon aria-hidden="true" className={cn('size-3.5', !active && 'opacity-50')} />
      </Button>
    </TableHead>
  )
}

function CertTable({ rows, dtm, onOpen, sort, onSort }) {
  const t = useT()
  const sh = { sort, onSort }
  return (
    <div className="@container overflow-hidden rounded-lg border bg-card">
      <Table data-slot="stats-table" className="text-sm">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <SortHead col="domain" label={t('stv.colCert')} {...sh} />
            <TableHead className={TH}>{t('tbl.colStatus')}</TableHead>
            <SortHead col="days" label={t('tbl.colDays')} {...sh} />
            <TableHead className={cn(TH, 'hidden @2xl:table-cell')}>{t('tbl.colExpiry')}</TableHead>
            <SortHead col="issuer" label={t('tbl.colIssuer')} className="hidden @3xl:table-cell" {...sh} />
            <TableHead className={cn(TH, 'hidden @4xl:table-cell')}>{t('tbl.colTeam')}</TableHead>
            <SortHead col="checked" label={t('tbl.colChecked')} className="hidden @5xl:table-cell" {...sh} />
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((c) => (
            <TableRow key={c.domain} data-slot="stats-row" data-domain={c.domain} tabIndex={0}
              aria-label={t('a11y.openRow', c.domain)}
              className="cursor-pointer focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary"
              onClick={() => onOpen(c.domain)}
              onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onOpen(c.domain) } }}>
              <TableCell className="max-w-[22rem] min-w-[12rem] px-3 py-2.5 align-top whitespace-normal">
                <div className="flex min-w-0 flex-col gap-1">
                  <span className="font-semibold break-all">{c.domain}</span>
                  <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                    <TierBadge tier={c.tier} />
                    <span className="@3xl:hidden min-w-0 truncate" title={issuerOf(c)}>{issuerOf(c)}</span>
                    <span className="@2xl:hidden tabular-nums">{t('stv.expiresOn', formatDateOnly(c.not_after))}</span>
                  </span>
                  {c.error && <span className="line-clamp-2 text-xs [overflow-wrap:anywhere] text-destructive" title={c.error}>{c.error}</span>}
                </div>
              </TableCell>
              <TableCell className="px-3 py-2.5 align-top"><LevelBadge cert={c} /></TableCell>
              <TableCell className="px-3 py-2.5 align-top"><DaysBadge cert={c} /></TableCell>
              <TableCell className="hidden px-3 py-2.5 align-top tabular-nums @2xl:table-cell">{formatDateOnly(c.not_after)}</TableCell>
              <TableCell className="hidden max-w-[16rem] px-3 py-2.5 align-top @3xl:table-cell">
                <span className="block truncate" title={issuerOf(c)}>{issuerOf(c) || '—'}</span>
              </TableCell>
              <TableCell className="hidden max-w-[14rem] px-3 py-2 align-top @4xl:table-cell" onClick={stop} onKeyDown={stop}>
                <TeamsCell cert={c} dtm={dtm} />
              </TableCell>
              <TableCell className="hidden px-3 py-2.5 align-top text-muted-foreground @5xl:table-cell"><Checked iso={c.checked_at} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function CertCards({ rows, dtm, onOpen }) {
  const t = useT()
  return (
    <ul className="m-0 flex min-w-0 list-none flex-col gap-2.5 p-0">
      {rows.map((c) => (
        <li key={c.domain} className="min-w-0">
          <Card data-slot="stats-card" data-domain={c.domain} className="relative min-w-0 gap-2 px-4 py-3 shadow-none">
            <div className="flex min-w-0 items-start justify-between gap-2">
              <LevelBadge cert={c} />
              <DaysBadge cert={c} long />
            </div>
            <Button type="button" variant="ghost" data-slot="stats-card-open" aria-label={t('card.openDetailFor', c.domain)}
              onClick={() => onOpen(c.domain)}
              className={cn(
                'h-auto min-h-10 w-full min-w-0 justify-start rounded-none p-0 text-left text-[15px] leading-snug font-semibold whitespace-normal text-foreground',
                'hover:bg-transparent hover:text-foreground dark:hover:bg-transparent',
                'focus-visible:ring-0 after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px] focus-visible:after:ring-ring/50',
              )}>
              <span className="min-w-0 break-all">{c.domain}</span>
            </Button>
            <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <TierBadge tier={c.tier} />
              <span className="min-w-0 truncate" title={issuerOf(c)}>{issuerOf(c) || '—'}</span>
            </div>
            <div className="relative z-10 min-w-0 self-start py-1 text-sm"><TeamsCell cert={c} dtm={dtm} row /></div>
            <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <span className="tabular-nums">{t('stv.expiresOn', formatDateOnly(c.not_after))}</span>
              <Checked iso={c.checked_at} />
            </div>
            {c.error && <p className="m-0 text-xs [overflow-wrap:anywhere] text-destructive">{c.error}</p>}
          </Card>
        </li>
      ))}
    </ul>
  )
}
