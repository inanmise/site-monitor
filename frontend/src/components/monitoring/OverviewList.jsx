// İzleme Panosu — izleme listesi (2026-10-01 yeniden tasarım). Görünüm LİSTE KABININ genişliğine göre seçilir (sayfa
// değil — kenar çubuğu açıkken 1024 px ekranda içerik ~740 px'tir): kap ≥ 720 px → tablo, daha dar → kartlar (telefon,
// tablet). Tablo sütunları da kap genişliğine göre (`@container/list`) açılır: Durum · İzleme · Başarı · Alarm · Eylemler
// her zaman; Son kontrol ≥ 56rem; Yanıt ≥ 64rem; Tür + Takım ≥ 72rem (1440 px ekran + açık kenar çubuğu ≈ 67rem kap). Gizlenen sütunun bilgisi İzleme hücresinin meta satırında
// görünür (aynı sorgularla `@…/list:hidden`), süzgeçleri de "Süzgeçler" panelinde — hiçbir bilgi/eylem kaybolmaz.
import { ShieldCheck } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { AlertLevelBadge } from '../admin/alerts/AlertBadges.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { OverviewColumnHead } from './OverviewColumnFilters.jsx'
import { LastCheck, ResponseValue, RowActions, StatusBadge, TypeLabel, UptimeMeter } from './OverviewParts.jsx'

/** Tablo görünümüne geçiş eşiği (liste kabı genişliği, px). */
export const TABLE_MIN_WIDTH = 720

function InvBadge({ className }) {
  const t = useT()
  return (
    <Badge variant="outline" data-slot="mo-inv-inactive" className={className ?? 'text-[11px]'} title={t('mo.invInactiveTip')}>
      {t('mo.invInactive')}
    </Badge>
  )
}

function AckedMark({ row }) {
  const t = useT()
  if (!row.open_acknowledged || !Number(row.open_alerts)) return null
  return (
    <Badge variant="secondary" className="h-5 gap-1 px-1.5 text-[10px]" title={t('mo.att.ackedTip')}>
      <ShieldCheck aria-hidden="true" />{t('mo.att.acked')}
    </Badge>
  )
}

export function OverviewTable({ rows, nowMs, sort, onSort, groups }) {
  const t = useT()
  return (
    <div className="overflow-x-auto rounded-lg border bg-card">
      <Table data-slot="mo-table">
        <TableHeader>
          <TableRow className="bg-muted/40 hover:bg-muted/40">
            <OverviewColumnHead label={t('mo.col.status')} sortKey="status" sort={sort} onSort={onSort} filter={groups.status} />
            <OverviewColumnHead label={t('mo.col.monitor')} sortKey="name" sort={sort} onSort={onSort} filter={groups.monitor} />
            <OverviewColumnHead label={t('mo.col.type')} sortKey="type" sort={sort} onSort={onSort} filter={groups.type} className="hidden @6xl/list:table-cell" />
            <OverviewColumnHead label={t('mo.col.team')} sortKey="team" sort={sort} onSort={onSort} filter={groups.team} className="hidden @6xl/list:table-cell" />
            <OverviewColumnHead label={t('mo.col.lastCheck')} sortKey="last" sort={sort} onSort={onSort} filter={groups.last} className="hidden @4xl/list:table-cell" />
            <OverviewColumnHead label={t('mo.col.success')} sortKey="uptime" sort={sort} onSort={onSort} filter={groups.checks} />
            <OverviewColumnHead label={t('mo.col.response')} sortKey="response" sort={sort} onSort={onSort} className="hidden @5xl/list:table-cell" />
            <OverviewColumnHead label={t('mo.col.alert')} sortKey="alert" sort={sort} onSort={onSort} filter={groups.alert} />
            <TableHead className="text-right">{t('mo.col.actions')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={`${r.type}-${r.id}`} data-slot="mo-row" data-type={r.type} data-status={r.status}>
              <TableCell className="align-top"><StatusBadge status={r.status} /></TableCell>
              <TableCell className="align-top whitespace-normal">
                {/* Genişlik kaba göre (cqw): uzun ad/hedef/hata kırpılır, diğer sütunlar ekranda kalır */}
                <div className="w-[clamp(11rem,22cqw,22rem)] min-w-0">
                <div className="truncate font-semibold" title={r.name}>{r.name}</div>
                {r.target && r.target !== r.name && <div className="truncate font-mono text-xs text-muted-foreground" title={r.target}>{r.target}</div>}
                {r.last_error && r.status === 'down' && <div className="line-clamp-1 text-xs text-destructive" title={r.last_error}>{r.last_error}</div>}
                {r.inventory_inactive && <InvBadge className="mt-1 text-[11px]" />}
                {/* Gizli sütunların bilgisi — kap daraldıkça burada görünür */}
                <div className="mt-1 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground @6xl/list:hidden">
                  <TypeLabel type={r.type} className="@6xl/list:hidden" />
                  {r.team_name && <span className="inline-flex max-w-full min-w-0"><TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} /></span>}
                  <LastCheck row={r} nowMs={nowMs} className="@4xl/list:hidden" />
                  <span className="inline-flex @5xl/list:hidden"><ResponseValue row={r} inline /></span>
                </div>
                </div>
              </TableCell>
              <TableCell className="hidden align-top @6xl/list:table-cell"><TypeLabel type={r.type} className="text-xs" /></TableCell>
              <TableCell className="hidden align-top @6xl/list:table-cell">
                <div className="flex max-w-[11rem] min-w-0">
                  {r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} /> : <span className="text-muted-foreground">—</span>}
                </div>
              </TableCell>
              <TableCell className="hidden align-top text-xs @4xl/list:table-cell"><LastCheck row={r} nowMs={nowMs} /></TableCell>
              <TableCell className="align-top"><UptimeMeter row={r} className="w-32" /></TableCell>
              <TableCell className="hidden align-top @5xl/list:table-cell"><ResponseValue row={r} /></TableCell>
              <TableCell className="align-top">
                {r.open_alert_level ? (
                  <div className="flex flex-col items-start gap-1">
                    <AlertLevelBadge level={r.open_alert_level} className="text-[0.75em]" />
                    <AckedMark row={r} />
                  </div>
                ) : <span className="text-xs text-muted-foreground">—</span>}
              </TableCell>
              <TableCell className="text-right align-top"><RowActions row={r} className="justify-end" /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

export function OverviewCards({ rows, nowMs }) {
  const t = useT()
  return (
    <ul className="m-0 grid list-none grid-cols-1 gap-2.5 p-0 @xl/list:grid-cols-2" data-slot="mo-list">
      {rows.map((r) => (
        <li key={`${r.type}-${r.id}`} className="min-w-0">
          <Card data-slot="mo-row" data-type={r.type} data-status={r.status} className="h-full min-w-0 gap-0 py-0 shadow-xs">
            <CardContent className="flex h-full min-w-0 flex-col gap-2.5 p-3.5">
              <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                <StatusBadge status={r.status} />
                {r.open_alert_level && <AlertLevelBadge level={r.open_alert_level} className="text-[0.7em]" />}
                <AckedMark row={r} />
                <Badge variant="outline" className="ml-auto max-w-full gap-1 text-[11px] font-normal"><TypeLabel type={r.type} iconClassName="size-3" /></Badge>
              </div>
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold" title={r.name}>{r.name}</div>
                {r.target && r.target !== r.name && <div className="truncate font-mono text-xs text-muted-foreground" title={r.target}>{r.target}</div>}
                {r.inventory_inactive && <InvBadge className="mt-1 text-[11px]" />}
              </div>
              <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                {r.team_name && <TeamBadge teamId={r.team_id} teamName={r.team_name} size={12} />}
                <LastCheck row={r} nowMs={nowMs} />
                <ResponseValue row={r} inline />
              </div>
              <UptimeMeter row={r} />
              {r.last_error && r.status === 'down' && <p className="m-0 line-clamp-2 text-xs text-destructive [overflow-wrap:anywhere]">{r.last_error}</p>}
              <RowActions row={r} labels className="mt-auto w-full sm:w-auto" />
            </CardContent>
          </Card>
        </li>
      ))}
      {rows.length === 0 && <li className="text-sm text-muted-foreground">{t('mo.emptyFiltered')}</li>}
    </ul>
  )
}
