import { useState, useEffect, useRef, Fragment } from 'react'
import {
  History, ChevronDown, ChevronRight, RefreshCw, Search, Users, UserCog, Contact, SlidersHorizontal, Mail, Forward,
  Share2, CalendarClock, ShieldOff, X,
} from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import MaskedValue from '../ui/MaskedValue.jsx'
import { EventBadge } from './ToneBadge.jsx'
import DiffTable from './audit/DiffTable.jsx'
import ChangeChipList from '../history/ChangeChipList.jsx'
import { useServerPagination } from '../../hooks/useServerPagination.js'
import { useElementWidth } from '../../hooks/useElementWidth.js'
import { useUrlQuerySync, readUrlParam } from '../../hooks/useUrlQuerySync.js'
import { relTimeOrRaw as relTime } from '../../utils/relativeTime.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { eventKind, parseChanges, isDiffShape, fmtValue } from './historyFormat.js'
import { FEED_AREAS, FEED_DAYS, DEFAULT_DAYS, normDays, parseAreas, toggleArea, layoutFor, rowPayload } from './adminChangeFeedModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Toggle } from '@/components/shadcn/toggle'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

const AREA_ICON = {
  teams: Users, users: UserCog, contacts: Contact, thresholds: SlidersHorizontal,
  groups: Mail, forwards: Forward, sharing: Share2, oncall: CalendarClock,
}
const TH = 'h-9 px-3 text-[0.75em] tracking-wide text-muted-foreground uppercase whitespace-normal'
const TD = 'px-3 py-2 align-top whitespace-normal'
const HINT = 'text-xs text-muted-foreground'
/** Alan süzgeç çipi — shadcn Toggle (hap biçimi, basılıyken birincil ton); dokunmada 40 px. */
const CHIP = 'h-8 gap-1.5 rounded-full px-3 text-[0.8em] font-semibold text-muted-foreground hover:bg-transparent hover:text-foreground data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary pointer-coarse:h-10'
const SUMMARY_LIMIT = 2
const ACTOR_DEBOUNCE_MS = 400

/**
 * Yönetim → Denetim → Değişiklik akışı (2026-11-13, yönetim zenginleştirmesi #2): takımlar, kullanıcılar, eskalasyon
 * kişileri, eşikler, bildirim grupları, alarm iletimi, erişim paylaşımı ve nöbet değişiklikleri TEK "kim neyi ne zaman
 * değiştirdi" akışında. Sunucu alan başına izin ve kapsamı uygular; arayüz yalnız sunucunun döndürdüğü alanları çip
 * olarak gösterir. Süzgeçler URL'de (`g_ca` alanlar, `g_cd` gün, `g_cu` kişi). Liste kabı ≥ 760 px tablo, altı kart.
 *
 * <p>Test kancaları: `data-slot="change-feed|cf-area|cf-days|cf-actor|cf-row|cf-area-badge|cf-detail|cf-total"`,
 * satırda `data-area`.
 */
export default function AdminChangeFeed() {
  const t = useT()
  const [areas, setAreas] = useState(() => parseAreas(readUrlParam('g_ca', '')))
  const [days, setDays] = useState(() => normDays(readUrlParam('g_cd', String(DEFAULT_DAYS))))
  const [actorInput, setActorInput] = useState(() => readUrlParam('g_cu', ''))
  const [actor, setActor] = useState(() => readUrlParam('g_cu', '').trim())
  useEffect(() => {
    const id = setTimeout(() => setActor(actorInput.trim()), ACTOR_DEBOUNCE_MS)
    return () => clearTimeout(id)
  }, [actorInput])
  useUrlQuerySync({ g_ca: areas.join(','), g_cd: days === DEFAULT_DAYS ? '' : String(days), g_cu: actor })

  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)          // { status, message } | null
  const [expanded, setExpanded] = useState(null)
  const [nonce, setNonce] = useState(0)
  const sp = useServerPagination({ listKey: 'admin-change-feed', preset: 'panel', resetDeps: [areas.join(','), days, actor], apiBase: 0 })
  const { apiPage, pageSize } = sp
  const seq = useRef(0)
  const [listRef, width] = useElementWidth()

  useEffect(() => {
    const my = ++seq.current
    setLoading(true)
    api.admin.historyFeed({ areas, actor, days, page: apiPage, size: pageSize })
      .then(res => {
        if (my !== seq.current) return
        if (res?.success) { setData(res); setError(null); sp.bind(res) }
        else setError({ status: errorInfoOf(res)?.status, message: res?.error || t('cf.error') })
      })
      .catch(e => { if (my === seq.current) setError({ status: errorInfoOf(e)?.status, message: e?.message || t('cf.error') }) })
      .finally(() => { if (my === seq.current) setLoading(false) })
  }, [areas.join(','), days, actor, apiPage, pageSize, nonce])   // eslint-disable-line react-hooks/exhaustive-deps

  const available = Array.isArray(data?.areas) ? FEED_AREAS.filter(a => data.areas.includes(a)) : []
  const items = data?.items || []
  const total = Number(data?.total ?? 0)
  const now = Date.now()
  const layout = layoutFor(width)

  const areaLabel = (a) => t(`cf.area.${a}`)
  const actLabel = (a) => { const k = `hist.act.${a}`; const s = t(k); return s === k ? String(a || '').toLowerCase().replace(/_/g, ' ') : s }
  const fieldLabel = (f) => { const k = `hist.f.${f}`; const s = t(k); return s === k ? f : s }

  const model = (r) => {
    const payload = rowPayload(r, parseChanges, isDiffShape)
    const all = !payload ? [] : payload.kind === 'diff'
      ? Object.entries(payload.data).map(([f, c]) => {
        const from = fmtValue(t, c?.from), to = fmtValue(t, c?.to)
        return { key: f, label: fieldLabel(f), diff: true, from, to, full: `${fieldLabel(f)}: ${from} → ${to}` }
      })
      : Object.keys(payload.data).map(f => ({ key: f, label: fieldLabel(f), diff: false, full: `${fieldLabel(f)}: ${fmtValue(t, payload.data[f])}` }))
    const limit = payload?.kind === 'diff' ? SUMMARY_LIMIT : SUMMARY_LIMIT + 1
    return { payload, chips: all.slice(0, limit), more: Math.max(0, all.length - limit) }
  }

  const detail = (r, m) => (m.payload.kind === 'diff' ? (
    <DiffTable fieldLabel={t('audit.diffField')} fromLabel={t('audit.diffFrom')} toLabel={t('audit.diffTo')}
      rows={Object.entries(m.payload.data).map(([f, c]) => [f, fieldLabel(f), fmtValue(t, c?.from), fmtValue(t, c?.to)])} />
  ) : (
    <div className="flex flex-col gap-1 text-xs">
      <div className="text-muted-foreground">{String(r.action).endsWith('DELETE') ? t('ng.histSnapshotOld') : t('ng.histSnapshotNew')}</div>
      <ul className="mt-1 ml-1 flex list-none flex-col gap-0.5 p-0">
        {Object.entries(m.payload.data).map(([f, v]) => (
          <li key={f} className="flex flex-wrap gap-x-2"><span className="font-semibold">{fieldLabel(f)}</span><span className="font-mono break-all">{fmtValue(t, v)}</span></li>
        ))}
      </ul>
    </div>
  ))

  const AreaBadge = ({ area }) => {
    const Icon = AREA_ICON[area] || History
    return (
      <Badge variant="outline" data-slot="cf-area-badge" data-area={area}
        className="h-auto max-w-full gap-1 overflow-visible rounded-md py-0.5 whitespace-normal text-muted-foreground">
        <Icon aria-hidden="true" className="size-3 shrink-0" /> <span className="[overflow-wrap:anywhere]">{areaLabel(area)}</span>
      </Badge>
    )
  }
  const target = (r) => (
    <span className="inline-flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
      <span className="font-semibold [overflow-wrap:anywhere]">{r.name || `#${r.resource_id}`}</span>
      {r.team_name && r.team_name !== r.name && <TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} wrap />}
    </span>
  )
  const actorCell = (r) => (r.actor
    ? <UserBadge username={r.actor} inline size="sm" systemLabel={t('audit.systemActor')} />
    : <span className="text-muted-foreground">—</span>)
  const toggleRow = (r, m) => { if (m.payload) setExpanded(prev => (prev === r.id ? null : r.id)) }

  const forbidden = error?.status === 403
  const firstLoad = loading && !data && !error
  const emptyNow = !loading && !error && data && items.length === 0
  const filtered = areas.length > 0 || days !== DEFAULT_DAYS || !!actor

  const table = (
    <div className={cn('overflow-hidden rounded-lg border bg-card', loading && items.length > 0 && 'opacity-70')} aria-busy={loading || undefined}>
      <Table className="table-fixed text-[0.82em]">
        <TableHeader className="bg-muted/50">
          <TableRow className="hover:bg-transparent">
            <TableHead className={cn(TH, 'w-9')}><span className="sr-only">{t('audit.colDetail')}</span></TableHead>
            <TableHead className={cn(TH, 'w-[13%]')}>{t('audit.colTime')}</TableHead>
            <TableHead className={cn(TH, 'w-[16%]')}>{t('audit.colActor')}</TableHead>
            <TableHead className={cn(TH, 'w-[15%]')}>{t('cf.colArea')}</TableHead>
            <TableHead className={cn(TH, 'w-[13%]')}>{t('hist.colAction')}</TableHead>
            <TableHead className={cn(TH, 'w-[18%]')}>{t('hist.colTarget')}</TableHead>
            <TableHead className={TH}>{t('hist.colChange')}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {firstLoad && Array.from({ length: 5 }, (_, i) => (
            <TableRow key={`sk${i}`} className="hover:bg-transparent" data-skeleton>
              {[4, 16, 24, 20, 16, 28, 40].map((w, j) => (
                <TableCell key={j} className={TD}><Skeleton className="h-4" style={{ width: `${w * 4}px`, maxWidth: '100%' }} /></TableCell>
              ))}
            </TableRow>
          ))}
          {items.map(r => {
            const m = model(r)
            const isOpen = expanded === r.id
            return (
              <Fragment key={r.id}>
                <TableRow data-slot="cf-row" data-area={r.area} aria-expanded={m.payload ? isOpen : undefined}
                  data-state={isOpen ? 'selected' : undefined} tabIndex={m.payload ? 0 : undefined}
                  aria-label={m.payload ? t('a11y.toggleRow', formatDateSec(r.at)) : undefined}
                  onClick={() => toggleRow(r, m)}
                  onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); toggleRow(r, m) } }}
                  className={cn('outline-none focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary',
                    'data-[state=selected]:bg-primary/10', m.payload ? 'cursor-pointer' : 'cursor-default')}>
                  <TableCell className={cn(TD, 'text-muted-foreground')}>{m.payload ? (isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />) : null}</TableCell>
                  <TableCell className={cn(TD, 'tabular-nums')}>
                    <SimpleTooltip content={formatDateSec(r.at)}><time dateTime={r.at} className="cursor-default">{relTime(r.at, t, now)}</time></SimpleTooltip>
                  </TableCell>
                  <TableCell className={TD}>{actorCell(r)}</TableCell>
                  <TableCell className={TD}><AreaBadge area={r.area} /></TableCell>
                  <TableCell className={TD}><EventBadge kind={eventKind(r.action)} title={r.event_type}>{actLabel(r.action)}</EventBadge></TableCell>
                  <TableCell className={TD}>{target(r)}</TableCell>
                  <TableCell className={TD}><ChangeChipList entries={m.chips} more={m.more} wrap empty={<span className="text-muted-foreground">—</span>} /></TableCell>
                </TableRow>
                {isOpen && m.payload && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={7} data-slot="cf-detail" className="bg-muted/40 py-2.5 pr-3.5 pb-3 pl-10 whitespace-normal">
                      <div className="mb-1.5 text-xs text-muted-foreground tabular-nums">
                        {formatDateSec(r.at)}{r.identity_masked === true ? <> · <MaskedValue /></> : r.ip ? ` · ${r.ip}` : ''}
                      </div>
                      {detail(r, m)}
                    </TableCell>
                  </TableRow>
                )}
              </Fragment>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )

  const cards = (
    <ul className={cn('flex list-none flex-col gap-2 p-0', loading && items.length > 0 && 'opacity-70')} aria-busy={loading || undefined}>
      {firstLoad && Array.from({ length: 3 }, (_, i) => (
        <li key={`sk${i}`} data-skeleton>
          <Card className="gap-2 px-3 py-3 shadow-none"><Skeleton className="h-3.5 w-28" /><Skeleton className="h-5 w-40" /><Skeleton className="h-5 w-full" /></Card>
        </li>
      ))}
      {items.map(r => {
        const m = model(r)
        const isOpen = expanded === r.id
        return (
          <li key={r.id} data-slot="cf-row" data-area={r.area}>
            <Card data-state={isOpen ? 'selected' : undefined} className="gap-2 px-3 py-2.5 shadow-none data-[state=selected]:border-primary/40">
              <div className="flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1 text-xs text-muted-foreground">
                <time dateTime={r.at} className="shrink-0 tabular-nums">{relTime(r.at, t, now)}</time>
                <span className="min-w-0 [overflow-wrap:anywhere]">{actorCell(r)}</span>
              </div>
              <div className="flex min-w-0 flex-wrap items-center gap-2 text-sm">
                <AreaBadge area={r.area} />
                <EventBadge kind={eventKind(r.action)} title={r.event_type}>{actLabel(r.action)}</EventBadge>
                {target(r)}
              </div>
              <ChangeChipList entries={m.chips} more={m.more} wrap empty={<span className="text-muted-foreground">—</span>} />
              {m.payload && (
                <Button type="button" variant="ghost" size="sm" className="-ml-2 h-10 self-start text-muted-foreground"
                  aria-expanded={isOpen} aria-label={t('a11y.toggleRow', formatDateSec(r.at))} onClick={() => toggleRow(r, m)}>
                  {isOpen ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />} {t('hist.colChange')}
                </Button>
              )}
              {isOpen && m.payload && (
                <div data-slot="cf-detail" className="flex min-w-0 flex-col gap-2 border-t pt-2 text-xs">
                  <span className="text-muted-foreground tabular-nums">{formatDateSec(r.at)}{r.identity_masked === true ? <> · <MaskedValue /></> : r.ip ? ` · ${r.ip}` : ''}</span>
                  <div className="min-w-0 overflow-x-auto">{detail(r, m)}</div>
                </div>
              )}
            </Card>
          </li>
        )
      })}
    </ul>
  )

  return (
    <section data-slot="change-feed" className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h3 className="m-0 text-lg leading-tight font-semibold">{t('cf.title')}</h3>
          <p className="m-0 mt-0.5 text-sm text-muted-foreground">{t('cf.desc')}</p>
        </div>
        {!forbidden && (
          <Button variant="secondary" className="shrink-0 self-start pointer-coarse:h-10" onClick={() => setNonce(n => n + 1)} disabled={loading}>
            <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin')} /> {t('cf.refresh')}
          </Button>
        )}
      </div>

      {forbidden ? (
        <StatusBlock tone="neutral" icon={ShieldOff} title={t('cf.noAccessTitle')} description={t('cf.noAccessBody')} />
      ) : (
        <>
          <Card className="gap-3 px-3 py-3 shadow-none sm:px-4">
            {available.length > 1 && (
              <div className="flex flex-wrap gap-1.5" role="group" aria-label={t('cf.areaFilter')} data-slot="cf-areas">
                <Toggle variant="outline" size="sm" className={CHIP} pressed={areas.length === 0} onPressedChange={() => setAreas([])}>
                  {t('cf.allAreas')}
                </Toggle>
                {available.map(a => {
                  const Icon = AREA_ICON[a] || History
                  return (
                    <Toggle key={a} variant="outline" size="sm" className={CHIP} data-slot="cf-area" data-area={a}
                      pressed={areas.includes(a)} onPressedChange={() => setAreas(prev => toggleArea(prev, a, available))}>
                      <Icon aria-hidden="true" /> {areaLabel(a)}
                    </Toggle>
                  )
                })}
              </div>
            )}
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-[minmax(0,14rem)_minmax(0,1fr)_auto] sm:items-center">
              <NativeSelect value={String(days)} onChange={(e) => setDays(normDays(e.target.value))} data-slot="cf-days"
                aria-label={t('cf.range')} className="h-10 w-full min-w-0 md:h-9 pointer-coarse:h-10">
                {FEED_DAYS.map(d => <NativeSelectOption key={d} value={String(d)}>{t(`cf.days.${d}`)}</NativeSelectOption>)}
              </NativeSelect>
              <InputGroup className="h-10 min-w-0 md:h-9 pointer-coarse:h-10">
                <InputGroupInput type="search" className="pointer-coarse:h-10" value={actorInput} onChange={(e) => setActorInput(e.target.value)} data-slot="cf-actor"
                  placeholder={t('cf.actorPlaceholder')} aria-label={t('cf.actorLabel')} autoComplete="off" maxLength={200}
                  name="change-feed-actor" data-1p-ignore data-lpignore="true" data-form-type="other" />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
              {filtered && (
                <Button type="button" variant="ghost" className="justify-self-start pointer-coarse:h-10"
                  onClick={() => { setAreas([]); setDays(DEFAULT_DAYS); setActorInput(''); setActor('') }}>
                  <X aria-hidden="true" /> {t('cf.clear')}
                </Button>
              )}
            </div>
            {data && !error && (
              <p className={cn(HINT, 'm-0')} data-slot="cf-total" aria-live="polite">{t('cf.total', total)}</p>
            )}
          </Card>

          {error && (
            <AlertBanner tone="danger" title={t('cf.error')} className="mb-0">
              <span className="flex flex-wrap items-center gap-2">
                <span className="[overflow-wrap:anywhere]">{error.message}</span>
                <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => setNonce(n => n + 1)}>{t('cf.retry')}</Button>
              </span>
            </AlertBanner>
          )}

          <div ref={listRef} className="min-w-0">
            {emptyNow ? (
              <StatusBlock tone="neutral" icon={History} title={filtered ? t('cf.emptyFiltered') : t('cf.empty')} />
            ) : (!error || items.length > 0) && (layout === 'table' ? table : cards)}
          </div>

          {total > 0 && <PaginationBar {...sp.bar} />}
          {data?.truncated && <p className={cn(HINT, 'm-0')}>{t('cf.truncated', total)}</p>}
          {Number(data?.hidden) > 0 && <p className={cn(HINT, 'm-0')}>{t('cf.hidden', data.hidden)}</p>}
          <p className={cn(HINT, 'm-0')}>{t('ng.histRetentionNote')}</p>
        </>
      )}
    </section>
  )
}
