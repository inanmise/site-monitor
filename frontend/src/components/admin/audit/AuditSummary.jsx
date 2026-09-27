import { useState } from 'react'
import { Activity, Ban, BarChart3, ShieldAlert, ShieldX, UserX } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import CollapsibleSection from '../../ui/CollapsibleSection.jsx'
import { ProgressBar } from '../../ui/Progress.jsx'
import { eventLabel, OUTCOME_KEYS } from './auditFormat.js'
import { EMPTY_FILTERS, sameFilters } from './auditFilters.js'
import { Button } from '@/components/shadcn/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { ChartContainer, ChartTooltip, ChartTooltipContent, AreaChart, Area, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'

/**
 * Özet kartları — YALNIZ `/audit/stats`'ın döndürdüğü veriden (uydurma sayı yok). Hepsi 7 günlük kayan pencere
 * (sunucunun penceresiyle aynı); kart süzgeci de `range=7d` kurar ki listedeki sayı kartla tutarlı olsun. Kartın
 * "etkin" durumu süzgeçten TÜRETİLİR (ayrı state yok — derin bağlantıda da doğru).
 *
 * Etiket/ipucu anahtarları DÜZ string (i18n-used-keys kapısı).
 */
export const SUMMARY_TILES = [
  { key: 'events', Icon: Activity, cls: 'total', labelKey: 'audit.tile.events', hintKey: 'audit.tile.eventsHint',
    value: s => s.total_7d, sub: s => s.total_24h, filter: { range: '7d' } },
  { key: 'failed', Icon: UserX, cls: 'error', labelKey: 'audit.tile.failedSignIns', hintKey: 'audit.tile.failedHint',
    value: s => s.failed_logins_7d, sub: s => s.failed_logins_24h, filter: { range: '7d', eventType: 'LOGIN_FAILED' } },
  { key: 'blocked', Icon: Ban, cls: 'warning', labelKey: 'audit.tile.blocked', hintKey: 'audit.tile.blockedHint',
    value: s => countOf(s.by_outcome_7d, 'BLOCKED'), filter: { range: '7d', outcome: 'BLOCKED' } },
  { key: 'denied', Icon: ShieldX, cls: 'critical', labelKey: 'audit.tile.denied', hintKey: 'audit.tile.deniedHint',
    value: s => countOf(s.by_event_type_7d, 'ACCESS_DENIED'), filter: { range: '7d', eventType: 'ACCESS_DENIED' } },
  { key: 'anomalies', Icon: ShieldAlert, cls: 'alert', labelKey: 'audit.tile.anomalies', hintKey: 'audit.tile.anomaliesHint',
    value: s => s.anomalies_7d, sub: s => s.anomalies_24h, filter: { range: '7d', anomalyOnly: true } },
]

/** Dağılım listesinden anahtarın sayısı; liste yoksa null (kart "—" değil 0 gösterir: sunucu satırı yoksa 0'dır). */
function countOf(list, key) {
  if (!Array.isArray(list)) return null
  return list.find(i => i.key === key)?.count ?? 0
}

export function tileFilter(tile) { return { ...EMPTY_FILTERS, ...tile.filter } }

export function AuditStatTiles({ stats, filters, onApply }) {
  const t = useT()
  if (!stats) {
    return (
      <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5" aria-hidden="true">
        {SUMMARY_TILES.map(tile => <Skeleton key={tile.key} className="h-24 rounded-lg" />)}
      </div>
    )
  }
  const active = SUMMARY_TILES.find(tile => sameFilters(filters, tileFilter(tile)))?.key ?? null
  const items = SUMMARY_TILES.map(tile => {
    const sub = tile.sub ? tile.sub(stats) : null
    return {
      key: tile.key, Icon: tile.Icon, cls: tile.cls, label: t(tile.labelKey), hint: t(tile.hintKey),
      value: tile.value(stats) ?? 0, sub: sub != null ? t('audit.tile.last24h', sub) : undefined,
    }
  })
  return (
    <MonitorStatsBar items={items} activeFilter={active}
      onStatClick={(key) => {
        const tile = SUMMARY_TILES.find(x => x.key === key)
        onApply(active === key ? { ...EMPTY_FILTERS } : tileFilter(tile))
      }} />
  )
}

/** Dağılım kartı — satır etiketi süzgeç düğmesi, çubuk görsel eş (decorative), sayı metin. */
function DistCard({ title, items, labelOf, onPick }) {
  const t = useT()
  if (!items?.length) return null
  const max = Math.max(...items.map(i => i.count), 1)
  return (
    <Card className="min-w-0 gap-2 py-3 shadow-none">
      <CardHeader className="px-3.5">
        <CardTitle className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</CardTitle>
      </CardHeader>
      <CardContent className="px-3.5">
        <ul className="flex list-none flex-col gap-0.5">
          {items.slice(0, 6).map(i => {
            const label = labelOf(i.key) || '—'
            return (
              <li key={i.key} className="grid grid-cols-[minmax(0,44%)_minmax(0,1fr)_auto] items-center gap-2 text-sm">
                <Button type="button" variant="link" size="sm" disabled={!i.key}
                  className="h-10 min-w-0 justify-start px-0 font-normal text-foreground sm:h-7"
                  title={i.key} aria-label={t('audit.filterByValue', label, i.count)}
                  onClick={() => onPick(i.key)}>
                  <span className="truncate">{label}</span>
                </Button>
                <ProgressBar value={i.count} max={max} size="sm" decorative />
                <span className="min-w-[3ch] text-right text-muted-foreground tabular-nums">{i.count}</span>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}

function DensityChart({ data, title, countLabel }) {
  if (!data || data.length < 2) return null
  const rows = data.map(d => ({ day: (d.key || '').slice(5), count: d.count }))
  const config = { count: { label: countLabel, color: 'var(--primary)' } }
  return (
    <Card className="min-w-0 gap-2 py-3 shadow-none md:col-span-2 xl:col-span-3">
      <CardHeader className="px-3.5">
        <CardTitle className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</CardTitle>
      </CardHeader>
      <CardContent className="px-2">
        <ChartContainer config={config} className="aspect-auto h-[140px] w-full">
          <AreaChart data={rows} margin={{ top: 6, right: 8, bottom: 0, left: -18 }}>
            <defs>
              <linearGradient id="auditDensityFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="var(--primary)" stopOpacity={0.35} />
                <stop offset="100%" stopColor="var(--primary)" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="day" tickLine={false} tick={{ fontSize: 10 }} interval="preserveStartEnd" minTickGap={16} />
            <YAxis allowDecimals={false} width={34} tickLine={false} tick={{ fontSize: 10 }} />
            <ChartTooltip content={<ChartTooltipContent indicator="line" />} />
            <Area type="monotone" dataKey="count" stroke="var(--primary)" strokeWidth={2} fill="url(#auditDensityFill)" />
          </AreaChart>
        </ChartContainer>
      </CardContent>
    </Card>
  )
}

const INSIGHTS_KEY = 'sm.audit.insightsOpen'
function readOpen() { try { return localStorage.getItem(INSIGHTS_KEY) === '1' } catch { return false } }
function writeOpen(v) { try { localStorage.setItem(INSIGHTS_KEY, v ? '1' : '0') } catch { /* gizli mod — yalnız bu oturum */ } }

/**
 * "Eğilim ve dağılım" — katlanır (ui/CollapsibleSection; açık/kapalı tarayıcıda hatırlanır). 14 günlük hacim +
 * en sık olay türleri / sonuçlar / en etkin kullanıcılar; her satır o değere süzer (7 günlük pencereyle).
 */
export function AuditInsights({ stats, onApply }) {
  const t = useT()
  const [open, setOpen] = useState(readOpen)
  if (!stats) return null
  const has = stats.by_day_14d?.length >= 2 || stats.by_event_type_7d?.length || stats.top_actors_7d?.length
  if (!has) return null
  const pick = (patch) => onApply({ ...EMPTY_FILTERS, range: '7d', ...patch })
  return (
    <CollapsibleSection open={open} onOpenChange={(v) => { setOpen(v); writeOpen(v) }} icon={BarChart3}
      label={t('audit.insights')} hint={t('audit.insightsHint')} contentClassName="pt-3">
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
        <DensityChart data={stats.by_day_14d} title={t('audit.densityTitle')} countLabel={t('audit.densityCount')} />
        <DistCard title={t('audit.distEvents')} items={stats.by_event_type_7d}
          labelOf={(k) => eventLabel(k, t)} onPick={(k) => pick({ eventType: k })} />
        <DistCard title={t('audit.distOutcomes')} items={stats.by_outcome_7d}
          labelOf={(k) => (OUTCOME_KEYS[k] ? t(OUTCOME_KEYS[k]) : k)} onPick={(k) => pick({ outcome: k })} />
        <DistCard title={t('audit.distActors')} items={stats.top_actors_7d}
          labelOf={(k) => k} onPick={(k) => pick({ actor: k })} />
      </div>
    </CollapsibleSection>
  )
}
