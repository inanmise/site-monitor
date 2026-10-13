import { Grid3x3, LayoutGrid, TableProperties } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { TYPE_META } from '../monitoring/overviewMeta.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import WidgetCard from './WidgetCard.jsx'
import { useWidgetData } from './useWidgetData.js'
import { Empty, LINK_RESET, More, fetchOverview, fetchStatus, linkTo, slotAttr } from './widgetParts.jsx'
import { MATRIX_NO_TEAM, matrixCellFocus, serviceGrid, teamTypeMatrix, typeHeat } from './myDashModel.js'

/**
 * Grafana tarzı ızgaralar (2026-10-13, kullanıcı isteği: "sorun varsa kırmızı, uyarı turuncu, yoksa yeşil; Grafana
 * stili"): hizmet durum ızgarası (status panel), izleme türü ısı haritası ve takım × tür matrisi. Hücre zemini DOLU renk
 * (duvardan okunur); rengin anlamı her hücrede METİNLE de yazılır (yalnız renk değil). Mevcut uçlar — kapsam aynı.
 */

/** Dolu hücre zemini — WidgetCard.STAT_SURFACE ile aynı palet (≥ 4.5:1 metin), bilinmeyen gri. */
const CELL = {
  danger:  'bg-red-700 text-white dark:bg-red-800',
  warning: 'bg-amber-400 text-amber-950 dark:bg-amber-500',
  success: 'bg-green-700 text-white dark:bg-green-800',
  info:    'bg-sky-700 text-white dark:bg-sky-800',
  muted:   'bg-muted text-foreground',
}
/** Üzerine gelince TON KORUNUR (ghost Button'ın `hover:bg-accent` gri vurgusu "bilinmiyor" gibi okunuyordu — inceleme). */
const CELL_HOVER = {
  danger:  'hover:bg-red-800 dark:hover:bg-red-900 hover:text-white',
  warning: 'hover:bg-amber-300 dark:hover:bg-amber-400 hover:text-amber-950',
  success: 'hover:bg-green-800 dark:hover:bg-green-900 hover:text-white',
  info:    'hover:bg-sky-800 dark:hover:bg-sky-900 hover:text-white',
  muted:   'hover:bg-muted/70 dark:hover:bg-muted/70 hover:text-foreground',
}
const CELL_FOCUS = 'focus-visible:ring-2 focus-visible:ring-ring'

/** Tıklanabilir ya da düz hücre — tıklanabilirken shadcn Button (data-slot korunur: kenarlık sıfırlaması). */
function Cell({ tone, onClick, hint, label, className, slot, children }) {
  const cls = cn('min-w-0 rounded-md', CELL[tone] || CELL.muted, className)
  if (!onClick) return <div className={cls} data-slot={slot} data-tone={tone} aria-label={label} role={label ? 'img' : undefined}>{children}</div>
  return (
    <Button type="button" variant="ghost" onClick={onClick} title={hint} aria-label={label} {...slotAttr(slot)} data-tone={tone} data-link="true"
      className={cn(LINK_RESET, cls, CELL_HOVER[tone] || CELL_HOVER.muted, CELL_FOCUS)}>
      {children}
    </Button>
  )
}

// ── Hizmet durum ızgarası ───────────────────────────────────────────────────────────────────────────────────────

const GRID_LIMIT = { sm: 12, md: 24, lg: 48 }
const GRID_LIMIT_TV = { sm: 9, md: 18, lg: 36 }

export function ServiceGridWidget({ frame, refreshKey }) {
  const t = useT()
  const { canView } = usePermissions()
  const { data, loading, error, at } = useWidgetData(fetchStatus, { refreshKey })
  const limit = (frame.tv ? GRID_LIMIT_TV : GRID_LIMIT)[frame.widget.size] || 12
  const g = serviceGrid(data, limit)
  const s = g.summary
  // Sorunlu hizmet → İzleme Panosu o takımın çalışmayan / gecikmiş izlemeleriyle; diğerleri Durum Sayfası
  const linkOf = (x) => {
    const problem = x.state === 'major_outage' || x.state === 'partial_outage' || x.state === 'degraded'
    // İzleme Panosu izleme okuma izni ister (bu kutu istemez): izin yoksa Durum Sayfası
    if (problem && x.monitors_visible === true && canView('monitoring.read')) {
      return linkTo(frame, 'monitoring', { mo_team: x.team_id != null ? String(x.team_id) : MATRIX_NO_TEAM, mo_status: 'down,stale' })
    }
    return linkTo(frame, 'status')
  }
  return (
    <WidgetCard frame={frame} Icon={LayoutGrid} title={t('mydash.w.service_grid')} tab="status" loading={loading} error={error} at={at}
      tone={s.tone} toneLabel={s.total ? t('sp.state.' + s.shownState) : null}>
      {g.total === 0 ? <Empty tone="muted" text={t('mydash.status.none')} tv={frame.tv} /> : (
        <>
          <ul className={cn('m-0 grid list-none gap-1.5 p-0', frame.tv ? 'grid-cols-2 @md:grid-cols-3 @2xl:grid-cols-4' : 'grid-cols-2 @md:grid-cols-3 @2xl:grid-cols-4 @5xl:grid-cols-6')}
            data-slot="mydash-grid-services">
            {g.tiles.map((x) => {
              const name = x.name || t('mydash.status.ungrouped')
              const state = t('sp.state.' + x.state)
              return (
                <li key={x.key} className="min-w-0" data-slot="mydash-grid-service" data-state={x.state}>
                  <Cell tone={x.tone} onClick={linkOf(x)} hint={t('mydash.linkHint')} className="flex h-full min-h-14 w-full flex-col items-start justify-start gap-0.5 px-2.5 py-2 text-left"
                    label={`${name}${x.team_name ? ` · ${x.team_name}` : ''}: ${state}`}>
                    <span className={cn('block font-semibold leading-tight [overflow-wrap:anywhere]', frame.tv ? 'text-base' : 'text-sm')}>{name}</span>
                    {x.team_name && <span className="block text-xs leading-tight opacity-90 [overflow-wrap:anywhere]">{x.team_name}</span>}
                    <span className="mt-auto block pt-0.5 text-xs font-medium opacity-90">{state}</span>
                  </Cell>
                </li>
              )
            })}
          </ul>
          <More n={g.more} t={t} />
        </>
      )}
    </WidgetCard>
  )
}

// ── İzleme türü ısı haritası ────────────────────────────────────────────────────────────────────────────────────

/** Hücrenin durum metni (rengin yanında yazı): en kötü sayaç. */
function healthText(t, c) {
  if (c.down > 0) return t('mydash.monitor.downCount', c.down)
  if (c.stale > 0) return t('mydash.monitor.staleCount', c.stale)
  if (c.active <= 0) return t('mo.status.paused')
  if (c.unknown > 0) return t('mydash.heat.unknownCount', c.unknown)
  return t('mydash.heat.allUp')
}

export function TypeHeatmapWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchOverview, { refreshKey })
  const h = typeHeat(data)
  const toneLabel = h.down ? t('mydash.monitor.downCount', h.down) : h.stale ? t('mydash.monitor.staleCount', h.stale)
    : h.tone === 'success' ? t('mydash.allClear') : null
  return (
    <WidgetCard frame={frame} Icon={Grid3x3} title={t('mydash.w.type_heatmap')} tab="monitoring" loading={loading} error={error} at={at}
      tone={h.cells.length ? h.tone : 'muted'} toneLabel={h.cells.length ? toneLabel : null}>
      {h.cells.length === 0 ? <Empty tone="muted" text={t('mydash.heat.none')} tv={frame.tv} /> : (
        <ul className="m-0 grid list-none grid-cols-2 gap-1.5 p-0 @xs:grid-cols-3 @3xl:grid-cols-5" data-slot="mydash-heat">
          {h.cells.map((c) => {
            const meta = TYPE_META[c.type]
            const TIcon = meta?.Icon
            const label = meta ? t(meta.labelKey) : c.type
            const status = c.down > 0 ? 'down' : c.stale > 0 ? 'stale' : null
            return (
              <li key={c.type} className="min-w-0" data-slot="mydash-heat-cell" data-type={c.type}>
                <Cell tone={c.tone} hint={t('mydash.linkHint')} label={`${label}: ${healthText(t, c)}`}
                  onClick={linkTo(frame, 'monitoring', status ? { mo_type: c.type, mo_status: status } : { mo_type: c.type })}
                  className="flex h-full min-h-16 w-full flex-col items-start justify-start gap-1 px-2.5 py-2 text-left">
                  <span className="flex min-w-0 items-start gap-1.5">
                    {TIcon && <TIcon className="mt-0.5 size-4 shrink-0" aria-hidden="true" />}
                    <span className={cn('min-w-0 font-semibold leading-tight [overflow-wrap:anywhere]', frame.tv ? 'text-base' : 'text-sm')}>{label}</span>
                  </span>
                  <span className="mt-auto block text-xs font-medium">{healthText(t, c)}</span>
                  <span className="block text-xs opacity-90 tabular-nums">{t('mydash.heat.healthyOf', c.healthy, c.active)}</span>
                </Cell>
              </li>
            )
          })}
        </ul>
      )}
    </WidgetCard>
  )
}

// ── Takım × izleme türü matrisi ─────────────────────────────────────────────────────────────────────────────────

const MATRIX_LIMIT = { sm: 6, md: 10, lg: 20 }
const MATRIX_LIMIT_TV = { sm: 4, md: 6, lg: 8 }

/** Hücrede yazan sayı — bağlantının açtığı süzülmüş listedeki satır sayısı (matrixCellFocus). */
const cellCount = (c) => matrixCellFocus(c).count

function Legend({ t }) {
  const items = [['danger', 'mo.status.down'], ['warning', 'mo.status.stale'], ['success', 'mo.status.up'], ['muted', 'mydash.matrix.legendGrey']]
  return (
    <ul className="m-0 flex list-none flex-wrap gap-x-3 gap-y-1 p-0 text-xs text-muted-foreground" data-slot="mydash-matrix-legend">
      {items.map(([tone, key]) => (
        <li key={tone} className="flex items-center gap-1.5">
          <span className={cn('inline-block size-3 rounded-sm', CELL[tone])} aria-hidden="true" />{t(key)}
        </li>
      ))}
    </ul>
  )
}

export function TeamMatrixWidget({ frame, refreshKey }) {
  const t = useT()
  const { data, loading, error, at } = useWidgetData(fetchOverview, { refreshKey })
  const limit = (frame.tv ? MATRIX_LIMIT_TV : MATRIX_LIMIT)[frame.widget.size] || 6
  const m = teamTypeMatrix(data, limit)
  const teamName = (r) => (r.key === MATRIX_NO_TEAM ? t('mydash.matrix.noTeam') : r.name || t('mydash.matrix.teamUnnamed', r.key))
  const typeLabel = (type) => (TYPE_META[type] ? t(TYPE_META[type].labelKey) : type)
  const cellText = (c) => (c ? healthText(t, c) : t('mydash.matrix.none'))
  const cellLink = (r, type, c) => linkTo(frame, 'monitoring', { mo_team: r.key, mo_type: type, mo_status: matrixCellFocus(c).status })
  const toneLabel = m.problemTeams ? t('mydash.matrix.problemTeams', m.problemTeams) : m.tone === 'success' ? t('mydash.allClear') : null
  return (
    <WidgetCard frame={frame} Icon={TableProperties} title={t('mydash.w.team_matrix')} tab="monitoring" loading={loading} error={error} at={at}
      tone={m.tone} toneLabel={m.rows.length ? toneLabel : null}>
      {m.rows.length === 0 ? <Empty tone="muted" text={t('mydash.heat.none')} tv={frame.tv} /> : (
        <>
          {/* Geniş kap: matris (takım satırı × tür sütunu). Dar kap: takım kartı + tür çipleri — yatay kaydırma yok */}
          <div className="hidden min-w-0 @2xl:block" data-slot="mydash-matrix">
            <div role="table" aria-label={t('mydash.w.team_matrix')} className="grid min-w-0 gap-1"
              style={{ gridTemplateColumns: `minmax(9rem, 1.6fr) repeat(${m.types.length}, minmax(3.25rem, 1fr))` }}>
              <div role="row" className="contents">
                <span role="columnheader" className="self-end px-1 pb-1 text-xs font-medium text-muted-foreground">{t('mydash.matrix.team')}</span>
                {m.types.map((type) => {
                  const TIcon = TYPE_META[type]?.Icon
                  return (
                    <span role="columnheader" key={type} className="flex min-w-0 flex-col items-center gap-0.5 self-end px-0.5 pb-1 text-center text-[11px] font-medium leading-tight text-muted-foreground">
                      {TIcon && <TIcon className="size-4 shrink-0" aria-hidden="true" />}
                      <span className="[overflow-wrap:anywhere]">{typeLabel(type)}</span>
                    </span>
                  )
                })}
              </div>
              {m.rows.map((r) => (
                <div role="row" key={r.key} className="contents" data-slot="mydash-matrix-row" data-team={r.key} data-tone={r.worst}>
                  <span role="rowheader" className={cn('flex min-w-0 items-center px-1 font-medium leading-tight [overflow-wrap:anywhere]', frame.tv ? 'text-base' : 'text-sm')}>
                    {teamName(r)}
                  </span>
                  {m.types.map((type) => {
                    const c = r.cells[type]
                    if (!c) return <span role="cell" key={type} className="grid min-h-10 place-items-center rounded-md border border-dashed border-border text-xs text-muted-foreground" aria-label={`${teamName(r)} · ${typeLabel(type)}: ${t('mydash.matrix.none')}`}>—</span>
                    return (
                      <span role="cell" key={type} className="min-w-0">
                        <Cell tone={c.tone} onClick={cellLink(r, type, c)} hint={t('mydash.linkHint')} slot="mydash-matrix-cell"
                          label={`${teamName(r)} · ${typeLabel(type)}: ${cellText(c)}`}
                          className="flex h-full min-h-10 w-full items-center justify-center px-1 py-1.5 text-center text-sm font-semibold tabular-nums">
                          {cellCount(c)}
                        </Cell>
                      </span>
                    )
                  })}
                </div>
              ))}
            </div>
          </div>
          <ul className="m-0 flex list-none flex-col gap-2 p-0 @2xl:hidden" data-slot="mydash-matrix-cards">
            {m.rows.map((r) => (
              <li key={r.key} className="flex min-w-0 flex-col gap-1.5 rounded-lg border border-border p-2" data-slot="mydash-matrix-card" data-team={r.key} data-tone={r.worst}>
                <span className="font-medium leading-tight [overflow-wrap:anywhere]">{teamName(r)}</span>
                <span className="flex flex-wrap gap-1.5">
                  {m.types.filter((type) => r.cells[type]).map((type) => {
                    const c = r.cells[type]
                    const TIcon = TYPE_META[type]?.Icon
                    return (
                      <Cell key={type} tone={c.tone} onClick={cellLink(r, type, c)} hint={t('mydash.linkHint')} slot="mydash-matrix-chip"
                        label={`${teamName(r)} · ${typeLabel(type)}: ${cellText(c)}`}
                        className="inline-flex min-h-10 items-center gap-1.5 px-2 py-1 text-xs font-semibold">
                        {TIcon && <TIcon className="size-3.5 shrink-0" aria-hidden="true" />}
                        <span className="[overflow-wrap:anywhere]">{typeLabel(type)}</span>
                        <span className="rounded bg-black/15 px-1.5 py-0.5 tabular-nums">{cellCount(c)}</span>
                      </Cell>
                    )
                  })}
                </span>
              </li>
            ))}
          </ul>
          <More n={m.more} t={t} />
          {!frame.tv && <Legend t={t} />}
        </>
      )}
    </WidgetCard>
  )
}

/** Kutu → ikon eşlemesi (widgets.jsx WIDGETS kaydına eklenir). */
export const GRID_WIDGETS = {
  service_grid: { Icon: LayoutGrid,      Component: ServiceGridWidget },
  type_heatmap: { Icon: Grid3x3,         Component: TypeHeatmapWidget },
  team_matrix:  { Icon: TableProperties, Component: TeamMatrixWidget },
}
