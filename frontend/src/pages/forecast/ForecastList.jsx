import { ArrowDown, ArrowUp, ArrowUpDown, CalendarPlus, ExternalLink, Link2, Play } from 'lucide-react'
import { formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { isHoliday, isWeekend, lastBusinessDay } from '../forecastModel.js'
import KebabMenu from '../../components/ui/KebabMenu.jsx'
import TeamBadge from '../../components/ui/TeamBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { CLS_INK, ClsBadge, PlanBadge, TierBadge, relativeDays } from './forecastUi.jsx'

/**
 * Yaklaşan bitişler listesi (2026-09-27): md+ shadcn Table (sıralanabilir başlıklar, `aria-sort`), telefonda kartlar —
 * jsdom ve testler tabloyu görür, `isMobile` (768) kart görünümüne çevirir. Satır eylemleri: Planla (görünür düğme,
 * adı alan adını taşır) + KebabMenu (sertifikayı aç · şimdi kontrol et · satır bağlantısı).
 * Test kancaları: satır `data-slot="fc-row"` (+ `data-cls`, `data-domain`), kart `data-slot="fc-card"`,
 * alan adı düğmesi `data-slot="fc-exp-domain"`.
 */
// Sütun görünürlüğü KAP genişliğine göre (Tailwind container query, `@container` tablo sarmalayıcısında): kenar
// çubuğu açıkken görünüm alanı kırılma noktası yanıltıyordu (1440'ta eylem sütunu kırpılıyordu). Gizlenen sütunun
// bilgisi satırda kalır: en geç tarihi bitiş hücresinde, veren alan adı alt satırında.
const COLS = [
  { key: 'domain', label: 'forecast.csvDomain', sort: 'domain' },
  { key: 'expiry', label: 'forecast.colExpiry', sort: 'expiry', cls: 'whitespace-nowrap' },
  { key: 'urgency', label: 'forecast.colUrgency', sort: 'urgency' },
  { key: 'renewBy', label: 'forecast.csvRenewBy', cls: 'hidden whitespace-nowrap @4xl:table-cell' },
  { key: 'issuer', label: 'forecast.csvIssuer', sort: 'issuer', cls: 'hidden @6xl:table-cell' },
  { key: 'plan', label: 'forecast.colPlan' },
  { key: 'actions', label: 'tbl.actions', cls: 'w-px text-right' },
]

function RenewBy({ r, t }) {
  if (!r.renew_by_key) return <span className="text-muted-foreground">—</span>
  const off = isWeekend(r.renew_by_key) || isHoliday(r.renew_by_key)
  return (
    <span className="inline-flex flex-wrap items-center gap-x-1 tabular-nums" title={t('forecast.renewByTip', r.lead_days ?? '')}>
      <b className={cn('font-semibold', r.window === 'late' && 'text-amber-700 dark:text-amber-400')}>{formatDateOnly(r.renew_by_key)}</b>
      {off && <span className="text-xs text-amber-700 dark:text-amber-400" title={t('forecast.offDayTip', formatDateOnly(lastBusinessDay(r.renew_by_key)))}>→ {formatDateOnly(lastBusinessDay(r.renew_by_key))}</span>}
    </span>
  )
}

function rowMenu(r, t, { onOpen, onCheckNow, onCopyLink, busy }) {
  return (
    <KebabMenu rowLabel={r.domain} label={t('tbl.actions')} items={[
      { label: t('renewal.openCert'), icon: <ExternalLink aria-hidden="true" />, onClick: () => onOpen(r.domain) },
      { label: t('inv.checkNow'), icon: <Play aria-hidden="true" />, onClick: () => onCheckNow(r.domain), hidden: !!busy?.has(r.domain) },
      { label: t('forecast.copyRowLink'), icon: <Link2 aria-hidden="true" />, onClick: () => onCopyLink(r.domain) },
    ]} />
  )
}

/** `cards`: kart görünümü (telefon ya da dar kap — karar çağıranda, kap genişliğinden). */
export default function ForecastList({ rows, cards, sort, onSort, busyDomains, onOpen, onCheckNow, onPlan, onCopyLink }) {
  const t = useT()
  const handlers = { onOpen, onCheckNow, onCopyLink, busy: busyDomains }   // busyDomains: Set<alan adı> (eşzamanlı kontroller)
  const planLabel = (r) => (r.renewal_plan_state === 'planned' ? t('forecast.editPlan') : t('forecast.planRenewal'))

  if (cards) {
    return (
      <div data-slot="fc-cards" className="flex min-w-0 flex-col gap-2.5">
        {rows.map((r) => (
          <Card key={r.domain} data-slot="fc-card" data-cls={r.cls} data-domain={r.domain} className="gap-2 py-3">
            <CardHeader className="gap-1 px-4">
              <CardTitle className="min-w-0 text-sm">
                <Button type="button" variant="link" data-slot="fc-exp-domain" className="h-auto min-w-0 max-w-full justify-start p-0 text-left font-semibold break-all whitespace-normal text-foreground hover:text-primary"
                  onClick={() => onOpen(r.domain)}>{r.domain}</Button>
              </CardTitle>
              <CardDescription className="flex flex-wrap items-center gap-1.5">
                <ClsBadge cls={r.cls} t={t} /><TierBadge tier={r.tier} />
                {r.team_name && <TeamBadge teamId={r.team_id} teamName={r.team_name} />}
              </CardDescription>
              <CardAction>{rowMenu(r, t, handlers)}</CardAction>
            </CardHeader>
            <CardContent className="grid grid-cols-2 gap-x-3 gap-y-1.5 px-4 text-xs">
              <div className="min-w-0"><div className="text-muted-foreground">{t('forecast.colExpiry')}</div><div className="tabular-nums">{r.expiry_key ? formatDateOnly(r.expiry_key) : '—'}</div>{r.days_remaining != null && <div className={cn('font-semibold', CLS_INK[r.cls])}>{relativeDays(r.days_remaining, t)}</div>}</div>
              <div className="min-w-0"><div className="text-muted-foreground">{t('forecast.csvRenewBy')}</div><RenewBy r={r} t={t} /></div>
              <div className="min-w-0"><div className="text-muted-foreground">{t('forecast.csvIssuer')}</div><div className="truncate" title={r.issuer_cn || undefined}>{r.issuer_cn || '—'}</div></div>
              <div className="min-w-0"><div className="text-muted-foreground">{t('forecast.colPlan')}</div><PlanBadge row={r} t={t} className="mt-0.5" />{r.renewal_plan_state === 'planned' && r.renewal_planned_note && <div className="mt-0.5 truncate text-muted-foreground">{r.renewal_planned_note}</div>}{!r.renewal_plan_state || r.renewal_plan_state === 'none' ? <span className="text-muted-foreground">—</span> : null}</div>
              {r.cls === 'unreachable' && r.error && <div className="col-span-2 break-all text-destructive">{r.error}</div>}
            </CardContent>
            <CardFooter className="gap-2 px-4">
              <Button type="button" variant="outline" size="sm" className="h-10 flex-1" onClick={() => onOpen(r.domain)}><ExternalLink aria-hidden="true" />{t('renewal.openCert')}</Button>
              <Button type="button" variant="secondary" size="sm" className="h-10 flex-1" aria-label={t('forecast.planTitle', r.domain)} onClick={() => onPlan(r)}><CalendarPlus aria-hidden="true" />{planLabel(r)}</Button>
            </CardFooter>
          </Card>
        ))}
      </div>
    )
  }

  const sortBtn = (col) => {
    const active = sort.key === col.sort
    const Icon = active ? (sort.dir === 'desc' ? ArrowDown : ArrowUp) : ArrowUpDown
    return (
      <Button type="button" variant="ghost" size="xs" className="-ml-2 h-7 gap-1 font-semibold text-muted-foreground hover:text-foreground"
        aria-label={t('forecast.sortBy', t(col.label))} onClick={() => onSort(col.sort)}>
        {t(col.label)}<Icon aria-hidden="true" className={cn('size-3', !active && 'opacity-50')} />
      </Button>
    )
  }

  return (
    <div className="@container overflow-hidden rounded-lg border">
      <Table data-slot="fc-table" className="text-[.86em]">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            {COLS.map((col) => (
              <TableHead key={col.key} className={cn('h-9 bg-muted/60 text-muted-foreground', col.cls)}
                aria-sort={col.sort ? (sort.key === col.sort ? (sort.dir === 'desc' ? 'descending' : 'ascending') : 'none') : undefined}>
                {col.sort ? sortBtn(col) : col.key === 'actions' ? <span className="sr-only">{t(col.label)}</span> : t(col.label)}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.domain} data-slot="fc-row" data-cls={r.cls} data-domain={r.domain}>
              <TableCell className="max-w-[15rem] min-w-0 align-top @5xl:max-w-[20rem]">
                <div className="flex min-w-0 flex-col gap-1">
                  <Button type="button" variant="link" data-slot="fc-exp-domain" className="h-auto min-w-0 max-w-full justify-start p-0 font-medium text-foreground hover:text-primary"
                    title={r.domain} onClick={() => onOpen(r.domain)}><span className="truncate">{r.domain}</span></Button>
                  <div className="flex min-w-0 flex-wrap items-center gap-1 text-xs text-muted-foreground">
                    <TierBadge tier={r.tier} />
                    {r.team_name && <TeamBadge teamId={r.team_id} teamName={r.team_name} />}
                    {r.group_name && <span className="truncate">· {r.group_name}</span>}
                  </div>
                  {r.issuer_cn && <span className="truncate text-xs text-muted-foreground @6xl:hidden" title={r.issuer_cn}>{r.issuer_cn}</span>}
                </div>
              </TableCell>
              <TableCell className="align-top whitespace-nowrap tabular-nums">
                <div>{r.expiry_key ? formatDateOnly(r.expiry_key) : '—'}</div>
                {r.days_remaining != null && <div className={cn('text-xs font-semibold', CLS_INK[r.cls])}>{relativeDays(r.days_remaining, t)}</div>}
                {r.renew_by_key && <div className="text-xs text-muted-foreground @4xl:hidden">{t('forecast.renewBy')} <RenewBy r={r} t={t} /></div>}
                {r.cls === 'unreachable' && r.error && <div className="max-w-[14rem] truncate text-xs text-destructive" title={r.error}>{r.error}</div>}
              </TableCell>
              <TableCell className="align-top"><ClsBadge cls={r.cls} t={t} /></TableCell>
              <TableCell className="hidden align-top @4xl:table-cell"><RenewBy r={r} t={t} /></TableCell>
              <TableCell className="hidden max-w-[12rem] truncate align-top @6xl:table-cell" title={r.issuer_cn || undefined}>{r.issuer_cn || '—'}</TableCell>
              <TableCell className="max-w-[13rem] align-top">
                <div className="flex min-w-0 flex-col gap-0.5">
                  <PlanBadge row={r} t={t} />
                  {r.renewal_plan_state === 'planned' && r.renewal_planned_note && <span className="truncate text-xs text-muted-foreground" title={r.renewal_planned_note}>{r.renewal_planned_note}</span>}
                  {(!r.renewal_plan_state || r.renewal_plan_state === 'none') && <span className="text-muted-foreground">—</span>}
                </div>
              </TableCell>
              <TableCell className="align-top">
                <div className="flex items-center justify-end gap-2">
                  <Button type="button" variant="secondary" size="icon-sm" title={planLabel(r)} aria-label={t('forecast.planTitle', r.domain)} onClick={() => onPlan(r)}><CalendarPlus aria-hidden="true" /></Button>
                  {rowMenu(r, t, handlers)}
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
