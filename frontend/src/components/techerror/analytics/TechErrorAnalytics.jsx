import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { ArrowLeft, ChartLine, CircleAlert, Download, Info, ListFilter, Radio } from 'lucide-react'
import { api } from '../../../api/client'
import { useLanguage, useT } from '../../../i18n/index.jsx'
import PageHeader from '../../ui/PageHeader.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { SeriesKey } from '../../responsechart/SeriesKey.jsx'
import { readUrlParam, useUrlQuerySync } from '../../../hooks/useUrlQuerySync.js'
import { useElementWidth } from '../../../hooks/useElementWidth.js'
import { downloadCsv, toCsv } from '../../../utils/csvExport.js'
import { matchesSearchText } from '../../../utils/monitorFilters.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardHeader } from '@/components/shadcn/card'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Command, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ChartContainer, ChartTooltip, ComposedChart, Line, XAxis, YAxis, CartesianGrid } from '@/components/shadcn/chart'
import { cn } from '@/lib/utils'
import { trendTicks } from '../techErrorModel.js'
import {
  DEFAULT_MONTHS, MAX_FIELDS, MAX_LINES, MONTH_CHOICES, TABLE_MIN_PX, TE_AN_FIELDS, TE_AN_MONTHS, TE_AN_TEAM, axisLabel,
  buildChart, channelCards, csvFileName, csvTable, fieldOptions, formatCell, isLonePoint, monthFullLabel, monthShortLabel,
  normalizeMonths, normalizeTeam, parseFieldsParam, requestParams, serializeFields, tableRows, toggleField, valueCount,
} from './analyticsModel.js'

const ALL = '__all__'
// Çizim kenar boşlukları (px) ve Y ekseni genişliği — ay adlarının sığıp sığmadığı çizim genişliğine göre seçilir
const MARGIN = { top: 8, right: 12, bottom: 0, left: 0 }
const Y_AXIS_W = 56

/**
 * KANAL ANALİZİ (2026-10-18, R22 — kullanıcı onayı: "takımın kanallarında aynı kimlikli alanlar 12 ay × kanal tablo ve
 * grafik"). `?tab=channelreports&te_view=analytics`, adres anahtarları `te_an_team` / `te_an_months` / `te_an_fields`.
 *
 * <p>Araç çubuğu: takım, dönem (6 / 12 / 24 bitmiş ay), en çok 4 alan (kanalların GÜNCEL şablonlarındaki sayı / yüzde
 * alanları). Her alan için bir kart: kanal başına çizgi (en çok 8; diğerleri "N kanal daha"), değeri olmayan ay BOŞLUK
 * (0 değil), yalnız başına kalan değer nokta; altında ay × kanal tablosu (kap ≥ 760 px; değilse kanal kartları), hücre o
 * raporu açar, gönderilmemiş raporun değeri soluk; görünen tablonun CSV'si. Okuma hatası "veri yok" değil — tekrar dene.
 * Değerler raporlara girilen / kaydedilen rakamlar (sunucu yeniden hesaplamaz).
 */
export default function TechErrorAnalytics({ onBack, onOpenReport }) {
  const t = useT()
  const { lang } = useLanguage()
  const [team, setTeam] = useState(() => normalizeTeam(readUrlParam(TE_AN_TEAM, '')))
  const [months, setMonths] = useState(() => normalizeMonths(readUrlParam(TE_AN_MONTHS, DEFAULT_MONTHS)))
  const [fields, setFields] = useState(() => parseFieldsParam(readUrlParam(TE_AN_FIELDS, '')))   // null = sunucu varsayılanı
  const [state, setState] = useState({ loading: true, error: null, status: null, details: null, data: null })
  const seq = useRef(0)

  useUrlQuerySync({
    [TE_AN_TEAM]: team,
    [TE_AN_MONTHS]: months === DEFAULT_MONTHS ? null : String(months),
    [TE_AN_FIELDS]: serializeFields(fields),
  })

  const load = useCallback(async () => {
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true }))
    try {
      const res = await api.techErrors.analytics(requestParams({ team, months, fields }))
      if (my !== seq.current) return
      if (res?.success && res.data) {
        setState({ loading: false, error: null, status: null, details: null, data: res.data })
      } else if (res?.status === 404 && team) {
        // Paylaşılan bağlantıdaki takım görünmüyor (ya da silindi): süzgeç kalkar, kurum kapsamı yeniden okunur
        setTeam(null)
      } else {
        setState((s) => ({ ...s, loading: false, error: res?.error || t('te.loadError'), status: res?.status ?? null, details: res?.errorInfo }))
      }
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('te.loadError'), status: 0, details: e?.errorInfo }))
    }
  }, [team, months, fields, t])
  useEffect(() => { load() }, [load])

  const data = state.data
  const shownFields = useMemo(() => (Array.isArray(data?.fields) ? data.fields : []), [data])
  const available = useMemo(() => (Array.isArray(data?.available_fields) ? data.available_fields : []), [data])
  const known = useMemo(() => new Set([...available.map((f) => f.id), ...shownFields.map((f) => f.id)]), [available, shownFields])
  // Seçim: kullanıcının listesi (sunucunun tanımadığı kimlik sayılmaz) ya da sunucunun çözdüğü varsayılan
  const selected = useMemo(() => (fields ? fields.filter((id) => known.has(id)) : shownFields.map((f) => f.id)),
    [fields, known, shownFields])
  const teams = useMemo(() => (Array.isArray(data?.teams) ? data.teams : []), [data])
  const teamOptions = useMemo(() => [{ value: ALL, label: t('te.allTeams') },
    ...teams.map((tm) => ({ value: String(tm.id), label: tm.name || `#${tm.id}` }))], [teams, t])
  const channels = Array.isArray(data?.channels) ? data.channels : []
  const monthList = Array.isArray(data?.months) ? data.months : []

  const back = (
    <Button type="button" variant="ghost" size="sm" onClick={onBack} className="w-fit pointer-coarse:min-h-10" data-slot="te-back">
      <ArrowLeft aria-hidden="true" /> {t('te.backToChannels')}
    </Button>
  )
  const header = <PageHeader icon={ChartLine} title={t('te.an.title')} description={t('te.an.subtitle')} />

  const toolbar = data && (
    <Card data-slot="te-an-toolbar" className="py-3">
      <CardContent className="flex flex-col gap-3 px-3 sm:px-4">
        <div className="flex flex-wrap items-end gap-3">
          {teams.length > 1 && (
            <div className="flex min-w-0 w-full flex-col gap-1.5 sm:w-72">
              <span id="te-an-team-label" className="text-xs font-medium text-muted-foreground">{t('te.teamFilter')}</span>
              <SearchableSelect ariaLabelledBy="te-an-team-label" value={team || ALL} options={teamOptions} wrapLabels
                onChange={(v) => setTeam(v === ALL ? null : normalizeTeam(v))} />
            </div>
          )}
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground" aria-hidden="true">{t('te.an.months')}</span>
            <SegmentedControl ariaLabel={t('te.an.months')} value={months} onChange={(v) => setMonths(normalizeMonths(v))}
              options={MONTH_CHOICES.map((m) => ({ value: m, label: t('te.an.monthsOption', m) }))} />
          </div>
          <div className="flex min-w-0 flex-col gap-1.5">
            <span className="text-xs font-medium text-muted-foreground">{t('te.an.fields')}</span>
            <FieldPicker options={available} selected={selected}
              onToggle={(id) => setFields((cur) => {
                const base = cur ? cur.filter((x) => known.has(x)) : selected
                const next = toggleField(base, id)
                return next === base ? cur : next             // değişmediyse istek yok (son alan / sınır dolu)
              })} />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
          {monthList.length > 0 && (
            <span data-slot="te-an-period">
              {t('te.an.periodNote', monthFullLabel(monthList[0], lang), monthFullLabel(monthList[monthList.length - 1], lang), channels.length)}
            </span>
          )}
          <span data-slot="te-an-sent-note">{t('te.an.notSentLegend')}</span>
        </div>
      </CardContent>
    </Card>
  )

  let body
  if (state.loading && !data) {
    body = (
      <div role="status" aria-live="polite" className="flex flex-col gap-3" data-slot="te-an-loading">
        <span className="sr-only">{t('te.an.loading')}</span>
        <Skeleton className="h-16 w-full rounded-xl motion-reduce:animate-none" />
        <Skeleton className="h-72 w-full rounded-xl motion-reduce:animate-none" />
      </div>
    )
  } else if (state.error && !data) {
    const forbidden = state.status === 403
    body = (
      <StatusBlock tone={forbidden ? 'neutral' : 'danger'} icon={CircleAlert}
        title={forbidden ? t('te.forbiddenTitle') : t('te.loadErrorTitle')}
        description={forbidden ? t('te.forbiddenBody') : state.error} details={state.details}
        actions={forbidden ? null : <Button type="button" variant="outline" onClick={load} data-slot="te-an-retry">{t('te.retry')}</Button>} />
    )
  } else if (data && channels.length === 0) {
    body = <StatusBlock tone="neutral" icon={Radio} title={t('te.an.emptyTitle')} description={t('te.an.emptyBody')} />
  } else if (data && shownFields.length === 0) {
    body = <StatusBlock tone="neutral" icon={ListFilter} title={t('te.an.noFieldsTitle')} description={t('te.an.noFieldsBody')} />
  } else if (data) {
    body = (
      <div className="flex flex-col gap-4" data-slot="te-an-fields-list" aria-busy={state.loading || undefined}>
        {shownFields.map((f) => <FieldPanel key={f.id} field={f} data={data} lang={lang} onOpenReport={onOpenReport} />)}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-slot="te-analytics">
      {back}
      {header}
      {toolbar}
      {state.error && data && (
        <AlertBanner tone="danger" title={t('te.an.staleTitle')}
          actions={<Button type="button" variant="outline" size="sm" onClick={load} className="pointer-coarse:min-h-10">{t('te.retry')}</Button>}>
          {state.error}
        </AlertBanner>
      )}
      {data?.truncated && monthList.length > 0 && (
        <div data-slot="te-an-truncated">
          <AlertBanner tone="info" icon={Info} title={t('te.an.truncatedTitle', monthList.length)}>
            {t('te.an.truncatedBody', monthList.length, data.channel_count ?? channels.length, data.months_requested ?? months)}
          </AlertBanner>
        </div>
      )}
      {body}
    </div>
  )
}

/** Alan seçici — Popover + Command (çoklu, en çok MAX_FIELDS; son alan kaldırılamaz). Adlar SARAR, kırpılmaz. */
function FieldPicker({ options, selected, onToggle }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const opts = fieldOptions(options, selected)
  const visible = opts.filter((o) => matchesSearchText(query, [o.label, o.id]))
  const names = opts.filter((o) => o.checked).map((o) => o.label)
  const full = selected.length >= MAX_FIELDS
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <Popover open={open} onOpenChange={(next) => { setOpen(next); if (!next) setQuery('') }}>
        <PopoverTrigger asChild>
          <Button type="button" variant="outline" size="sm" className="h-auto min-h-8 w-fit pointer-coarse:min-h-10" data-slot="te-an-field-picker">
            <ListFilter aria-hidden="true" />
            {t('te.an.fieldsPick')}
            <Badge variant="secondary" className="rounded-sm px-1.5 font-mono tabular-nums" aria-hidden="true">{selected.length}/{MAX_FIELDS}</Badge>
            <span className="sr-only">{t('te.an.fieldsSelected', selected.length)}</span>
          </Button>
        </PopoverTrigger>
        <PopoverContent align="start" className="z-(--z-menu) w-[min(24rem,calc(100vw-2rem))] p-0" data-slot="te-an-field-list">
          <Command shouldFilter={false} loop>
            <CommandInput placeholder={t('te.an.fieldsSearch')} value={query} onValueChange={setQuery} />
            <CommandList aria-multiselectable="true">
              {visible.length === 0 && (
                <div className="py-6 text-center text-sm text-muted-foreground" role="status">{t('te.an.noResult')}</div>
              )}
              {visible.length > 0 && (
                <CommandGroup>
                  {visible.map((o) => (
                    <CommandItem key={o.id} value={`f:${o.id}`} disabled={o.disabled} aria-checked={o.checked}
                      data-checked={o.checked ? 'true' : undefined} data-field={o.id}
                      onMouseDown={(e) => e.preventDefault()} onSelect={() => onToggle(o.id)}
                      className="items-start pointer-coarse:min-h-10">
                      <Checkbox checked={o.checked} tabIndex={-1} aria-hidden="true" className="pointer-events-none mt-0.5" />
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span className="whitespace-normal [overflow-wrap:anywhere]">{o.label}</span>
                        <span className="text-xs text-muted-foreground">
                          {t(o.type === 'percent' ? 'te.an.type.percent' : 'te.an.type.number')} · {t('te.an.fieldChannels', o.channels)}
                        </span>
                      </span>
                    </CommandItem>
                  ))}
                </CommandGroup>
              )}
            </CommandList>
            <p className="m-0 border-t px-3 py-2 text-xs text-muted-foreground" data-slot="te-an-field-limit">
              {full ? t('te.an.fieldLimit', MAX_FIELDS) : t('te.an.fieldsHint', MAX_FIELDS)}
            </p>
          </Command>
        </PopoverContent>
      </Popover>
      {names.length > 0 && (
        <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-an-field-names">{names.join(' · ')}</span>
      )}
    </div>
  )
}

/** Bir alanın kartı: başlık + CSV, çizgi grafiği + anahtar, ay × kanal tablosu ya da kanal kartları. */
function FieldPanel({ field, data, lang, onOpenReport }) {
  const t = useT()
  const uid = useId()
  const [showHidden, setShowHidden] = useState(false)
  const [wrapRef, width] = useElementWidth()
  const chart = useMemo(() => buildChart(data, field.id, MAX_LINES), [data, field.id])
  const count = useMemo(() => valueCount(data, field.id), [data, field.id])
  const cards = width > 0 && width < TABLE_MIN_PX
  const typeLabel = t(field.type === 'percent' ? 'te.an.type.percent' : 'te.an.type.number')

  function exportCsv() {
    const { headers, rows } = csvTable(data, field, lang, t('te.an.colMonth'))
    downloadCsv(csvFileName(field, data?.months), toCsv(headers, rows))
  }

  return (
    <Card data-slot="te-an-field" data-field={field.id} className="min-w-0 gap-3">
      <CardHeader className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-1">
          <h3 id={`${uid}-t`} className="m-0 text-base font-semibold leading-snug [overflow-wrap:anywhere]" data-slot="te-an-field-title">
            {field.label}
          </h3>
          <span className="text-xs text-muted-foreground">{typeLabel} · {field.id}</span>
        </div>
        <Button type="button" variant="outline" size="sm" onClick={exportCsv} className="pointer-coarse:min-h-10" data-slot="te-an-csv"
          aria-label={t('te.an.csvFor', field.label)}>
          <Download aria-hidden="true" /> {t('te.an.csv')}
        </Button>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-3">
        {count === 0 ? (
          <p className="m-0 rounded-md bg-muted/40 px-3 py-6 text-center text-sm text-muted-foreground" data-slot="te-an-novalues">
            {t('te.an.noValues')}
          </p>
        ) : (
          <FieldChart field={field} chart={chart} lang={lang} titleId={`${uid}-t`} />
        )}
        <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1.5 p-0 text-xs" data-slot="te-an-legend" aria-label={t('te.an.legend')}>
          {chart.series.map((s) => (
            <li key={s.key} className="inline-flex min-w-0 items-start gap-1.5" data-slot="te-an-legend-item">
              <span className="mt-1"><SeriesKey shape={s.dashed ? 'dashed' : 'line'} color={s.color} /></span>
              <span className="min-w-0 [overflow-wrap:anywhere]">
                <span className="text-foreground">{s.label}</span>
                {s.team && <span className="text-muted-foreground"> · {s.team}</span>}
              </span>
            </li>
          ))}
        </ul>
        {chart.hidden.length > 0 && (
          <div className="flex flex-col gap-2" data-slot="te-an-hidden">
            <Button type="button" variant="ghost" size="sm" className="w-fit pointer-coarse:min-h-10" aria-expanded={showHidden}
              onClick={() => setShowHidden((v) => !v)} data-slot="te-an-hidden-toggle">
              {showHidden ? t('te.an.moreChannelsHide') : t('te.an.moreChannels', chart.hidden.length)}
            </Button>
            {showHidden && (
              <div className="flex flex-col gap-1 text-sm">
                <p className="m-0 text-xs text-muted-foreground">{t('te.an.moreNote', MAX_LINES)}</p>
                <ul className="m-0 flex list-none flex-wrap gap-x-4 gap-y-1 p-0" data-slot="te-an-hidden-list">
                  {chart.hidden.map((h) => (
                    <li key={h.id} className="min-w-0 [overflow-wrap:anywhere]">
                      {h.name}{h.team && <span className="text-muted-foreground"> · {h.team}</span>}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
        <div ref={wrapRef} className="min-w-0" data-slot="te-an-table-wrap" data-layout={cards ? 'cards' : 'table'}>
          {cards
            ? <ChannelCards data={data} field={field} lang={lang} onOpenReport={onOpenReport} />
            : <MonthTable data={data} field={field} lang={lang} onOpenReport={onOpenReport} />}
        </div>
      </CardContent>
    </Card>
  )
}

function FieldChart({ field, chart, lang, titleId }) {
  const t = useT()
  const [boxRef, boxWidth] = useElementWidth()
  const plotWidth = boxWidth ? boxWidth - MARGIN.left - MARGIN.right - Y_AXIS_W : 0
  const ticks = useMemo(() => trendTicks(chart.rows, lang, plotWidth), [chart.rows, lang, plotWidth])
  const rows = useMemo(() => chart.rows.map((r, i) => ({ ...r, tick: ticks[i]?.label || '', tickYear: ticks[i]?.year || '' })),
    [chart.rows, ticks])
  const config = useMemo(() => Object.fromEntries(chart.series.map((s) => [s.key, { label: s.label, color: s.color }])), [chart.series])
  const loneDot = (s) => function LoneDot(props) {
    const { cx, cy, index } = props
    const k = `${s.key}-${index}`
    if (cx == null || cy == null || !isLonePoint(rows, s.key, index)) return <g key={k} />
    return <circle key={k} data-slot="te-an-dot" cx={cx} cy={cy} r={3} fill={s.color} stroke="none" />
  }
  return (
    <figure className="m-0 flex min-w-0 flex-col gap-1" aria-labelledby={titleId} data-slot="te-an-chart">
      <figcaption className="sr-only">{t('te.an.chartLabel', field.label)}</figcaption>
      <div ref={boxRef} className="w-full">
        <ChartContainer config={config} className="aspect-auto h-[220px] w-full sm:h-[260px]" aria-hidden="true">
          <ComposedChart data={rows} margin={MARGIN}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="key" tickLine={false} axisLine={false} interval={0} height={32} tick={<MonthTick rows={rows} />} />
            <YAxis width={Y_AXIS_W} tickCount={4} tickLine={false} axisLine={false} tick={{ fontSize: 10 }}
              allowDecimals={field.type === 'percent'} tickFormatter={(v) => axisLabel(v, field.type, lang)} />
            <ChartTooltip isAnimationActive={false} cursor={{ stroke: 'var(--muted-foreground)', strokeOpacity: 0.35, strokeWidth: 1 }}
              content={<ChartTip series={chart.series} field={field} lang={lang} />} />
            {chart.series.map((s) => (
              <Line key={s.key} dataKey={s.key} type="linear" stroke={s.color} strokeWidth={1.75}
                strokeDasharray={s.dashed ? '5 3' : undefined} dot={loneDot(s)} activeDot={{ r: 3 }}
                connectNulls={false} isAnimationActive={false} />
            ))}
          </ComposedChart>
        </ChartContainer>
      </div>
    </figure>
  )
}

/** Ay adı; yıl değişen ilk görünen ayın altında yıl (dar grafikte bazı aylar atlanır). */
function MonthTick({ x, y, payload, rows }) {
  const d = rows?.[payload?.index]
  if (!d?.tick) return null
  return (
    <g transform={`translate(${x},${y})`}>
      <text textAnchor="middle" fontSize={10} className="fill-muted-foreground" data-slot="te-an-tick">
        <tspan x={0} dy={10}>{d.tick}</tspan>
        {d.tickYear && <tspan x={0} dy={12}>{d.tickYear}</tspan>}
      </text>
    </g>
  )
}

function ChartTip({ active, payload, series, field, lang }) {
  if (!active || !Array.isArray(payload) || !payload.length) return null
  const row = payload.find((x) => x?.payload)?.payload
  if (!row) return null
  const items = series.map((s) => ({ ...s, v: row[s.key] })).filter((s) => typeof s.v === 'number').sort((a, b) => b.v - a.v)
  return (
    <div data-slot="chart-tooltip" className="grid max-w-[20rem] min-w-[10rem] gap-1 rounded-lg border border-border/60 bg-background px-2.5 py-2 text-xs shadow-xl">
      <div className="font-semibold text-foreground">{monthFullLabel(row, lang)}</div>
      {items.map((s) => (
        <div key={s.key} className="flex items-start gap-2">
          <span className="mt-0.5"><SeriesKey shape={s.dashed ? 'dashed' : 'line'} color={s.color} /></span>
          <span className="min-w-0 flex-1 text-muted-foreground [overflow-wrap:anywhere]">{s.label}</span>
          <span className="font-semibold text-foreground tabular-nums">{formatCell(s.v, field.type, lang)}</span>
        </div>
      ))}
    </div>
  )
}

/** Hücre değeri: raporu varsa o raporu açan bağlantı; gönderilmemiş raporun değeri soluk (ekran okuyucuya "gönderilmedi"). */
function CellValue({ cell, channel, month, lang, onOpenReport, align = 'right' }) {
  const t = useT()
  const label = monthFullLabel(month, lang)
  if (!cell.report) {
    return (
      <span className={cn('tabular-nums text-muted-foreground', align === 'right' && 'block text-right')} data-slot="te-an-cell" data-state="none">
        <span aria-hidden="true">—</span>
        <span className="sr-only">{t('te.an.noReport')}</span>
      </span>
    )
  }
  const sent = cell.report.sent
  const valueText = cell.value == null ? t('te.an.noValue') : cell.text
  const name = `${valueText}${sent ? '' : ` (${t('te.an.notSent')})`} — ${t('te.an.openReport', channel.name, label)}`
  return (
    <Button type="button" variant="link" size="sm" onClick={() => onOpenReport?.(cell.report.id, channel.id)} aria-label={name}
      data-slot="te-an-cell" data-state={sent ? 'sent' : 'unsent'} data-report={cell.report.id}
      className={cn('h-auto min-h-8 px-1 tabular-nums pointer-coarse:min-h-10', align === 'right' && 'ml-auto',
        sent ? 'text-foreground' : 'text-muted-foreground italic')}>
      {cell.text}
    </Button>
  )
}

function MonthTable({ data, field, lang, onOpenReport }) {
  const t = useT()
  const channels = data?.channels || []
  const rows = tableRows(data, field, lang)
  return (
    <div className="overflow-hidden rounded-xl border" data-slot="te-an-table">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="sticky left-0 z-10 w-28 bg-card align-bottom">{t('te.an.colMonth')}</TableHead>
            {channels.map((ch) => (
              <TableHead key={ch.id} className="whitespace-normal align-bottom text-right" data-channel={ch.id}>
                <div className="ml-auto flex min-w-32 max-w-56 flex-col items-end py-1">
                  <span className="text-foreground [overflow-wrap:anywhere]">{ch.name}</span>
                  {ch.team_name && <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">{ch.team_name}</span>}
                </div>
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.key} data-slot="te-an-row" data-month={r.key}>
              <TableCell className="sticky left-0 z-10 bg-card font-medium whitespace-nowrap">{monthShortLabel(r, lang)}</TableCell>
              {r.cells.map((c, i) => (
                <TableCell key={c.channelId} className="text-right">
                  <CellValue cell={c} channel={channels[i]} month={r} lang={lang} onOpenReport={onOpenReport} />
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

function ChannelCards({ data, field, lang, onOpenReport }) {
  const cards = channelCards(data, field, lang)
  return (
    <div className="grid gap-3 sm:grid-cols-2" data-slot="te-an-cards">
      {cards.map((c) => (
        <div key={c.id} className="flex min-w-0 flex-col gap-2 rounded-xl border p-3" data-slot="te-an-card" data-channel={c.id}>
          <div className="flex min-w-0 flex-col gap-1">
            <span className="font-semibold leading-snug [overflow-wrap:anywhere]">{c.name}</span>
            {c.team_name && <TeamBadge teamId={c.team_id} teamName={c.team_name} static wrap />}
          </div>
          <ul className="m-0 flex list-none flex-col divide-y p-0 text-sm">
            {c.items.map((it) => (
              <li key={it.key} className="flex items-center justify-between gap-2 py-0.5" data-slot="te-an-card-row">
                <span className="text-muted-foreground">{monthShortLabel(it, lang)}</span>
                <CellValue cell={it} channel={c} month={it} lang={lang} onOpenReport={onOpenReport} />
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}
