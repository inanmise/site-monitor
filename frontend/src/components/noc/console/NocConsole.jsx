import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Headset, ListChecks, PhoneCall, PhoneMissed, RadioTower, RefreshCw, Search, Siren, X } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { useIsMobile } from '../../../hooks/use-mobile.js'
import { readUrlParam, useUrlQuerySync } from '../../../hooks/useUrlQuerySync.js'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { useElementWidthState } from '../../../hooks/useElementWidth.js'
import PageHeader from '../../ui/PageHeader.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import MonitorStatsBar from '../../MonitorStatsBar.jsx'
import { unwrap } from '../nocModel.js'
import NocConsoleList from './NocConsoleList.jsx'
import NocCallDialog from './NocCallDialog.jsx'
import {
  DEFAULT_SIZE, FILTER_DEFAULTS, LEVELS, REFRESH_MS, SIZE_OPTIONS, WINDOWS, activeChips, activeTile, consoleParams,
  filtersFromUrl, filtersToUrl, tilePatch, typeOptions,
} from './nocConsoleModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Toggle } from '@/components/shadcn/toggle'

/** İzleme ailesi → menüdeki tür adı (Alarm Geçmişi kategori süzgeciyle aynı adlar). */
const FAMILY_KEY = {
  http: 'nav.http', ping: 'nav.ping', port: 'nav.port', dns: 'nav.dns', domain: 'nav.domainmon', keyword: 'nav.keyword',
  page: 'nav.page', pagespeed: 'nav.pagespeed', scripted: 'nav.scripted', cert: 'nav.groupCertificates', other: 'noc.con.family.other',
}
const familyLabel = (t, key) => t(FAMILY_KEY[key] || 'noc.con.family.other')

/**
 * **7/24 Konsolu** (`?tab=noc&n_view=console`, 2026-10-04, kullanıcı isteği): "tüm şirkette ne tür alarmlar ve bildirimler
 * var; bildirimleri direkt görüp ilgili takımların aranmasını sağlamalı; giden bildirimlerin 7/24 ekibine de gittiğini
 * gözlemleyebilmeli; 7/24'e giden alarmları filtreleyebilmeli".
 *
 * <p>Yapı: PageHeader (son güncelleme, Yenile) → KPI kartları (açık · 7/24'e giden · aranmamış · son 1 saatte arandı —
 * kartlar süzgeç) → süzgeçler (pencere 1 sa / 24 sa / 7 g, "7/24'e gidenler", takım, seviye, tür, arandı / aranmadı,
 * durum, arama) → liste (geniş kapta tablo, dar kapta kart) + sunucu sayfalaması → "Ara" penceresi (arama kartı + kayıt
 * formu). Veri kurum geneli; süzme/sayfalama SUNUCUDA (bellekli). 30 sn'de bir sessiz yenileme (`useVisibleInterval`,
 * gizli sekmede durur; saniyelik durum YOK). URL `n_c*` (PAGE_STATE_PREFIXES `n_`).
 */
export default function NocConsole({ canWrite: canWriteProp = false }) {
  const t = useT()
  const isMobile = useIsMobile()
  const [listWidth, listRef] = useElementWidthState()
  // Tablo ~900 px ister (sabit sütunlar 768 px + alarm sütunu); daha dar kapta (telefon, kenar çubuklu tablet) kartlar
  const narrow = isMobile || (listWidth > 0 && listWidth < 900)

  const [filters, setFilters] = useState(() => filtersFromUrl(readUrlParam))
  // Sayfalama standardı (useServerPagination): süzgeç değişince sayfa 1 (mount'ta değil — derin bağlantının `n_cpage`'i
  // korunur), boyut `listKey` ile kalıcı, URL `n_cpage` / `n_cps`, çubuk `{...sp.bar}`.
  const sp = useServerPagination({ listKey: 'noc-console', defaultSize: DEFAULT_SIZE, sizeOptions: SIZE_OPTIONS,
    resetDeps: [filters], url: { pageKey: 'n_cpage', sizeKey: 'n_cps' }, apiBase: 1 })   // apiPage 1-tabanlı → consoleParams 0-tabanlıya çevirir
  const { setTotal } = sp   // kararlı referans (yükleme callback'inin bağımlılığı)
  const patch = useCallback((p) => { setFilters((f) => ({ ...f, ...p })) }, [])
  const [draftQ, setDraftQ] = useState(filters.q)
  useEffect(() => { setDraftQ(filters.q) }, [filters.q])
  useEffect(() => {
    const v = draftQ.trim()
    if (v === filters.q) return undefined
    const id = setTimeout(() => patch({ q: v }), 300)
    return () => clearTimeout(id)
  }, [draftQ]) // eslint-disable-line react-hooks/exhaustive-deps

  useUrlQuerySync(filtersToUrl(filters))

  const [data, setData] = useState({ res: null, error: null, at: null })
  const [refreshing, setRefreshing] = useState(false)
  const [nowMs, setNowMs] = useState(() => Date.now())
  const [callRow, setCallRow] = useState(null)
  const seq = useRef(0)
  const alive = useRef(true)

  const load = useCallback(async ({ fresh = false, manual = false } = {}) => {
    const id = ++seq.current
    if (manual) setRefreshing(true)
    try {
      const r = unwrap(await api.noc.console(consoleParams(filters, sp.apiPage, sp.pageSize, fresh)))
      if (!alive.current || id !== seq.current) return
      if (r.ok) {
        setData({ res: r.data || {}, error: null, at: Date.now() })
        setTotal(Number(r.data?.total) || 0)   // hata yanıtında dokunulmaz: son bilinen toplam kalır
      }
      else setData((d) => ({ ...d, error: r.error || t('noc.con.loadError') }))
    } catch (e) {
      if (alive.current && id === seq.current) setData((d) => ({ ...d, error: e?.message || t('noc.con.loadError') }))
    } finally {
      if (manual && alive.current) setRefreshing(false)
      if (alive.current) setNowMs(Date.now())
    }
  }, [filters, sp.apiPage, sp.pageSize, setTotal, t])

  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false }
  }, [load])
  // Sessiz otomatik yenileme — gizli sekmede durur, geri gelince bir kez tazeler (proje yoklama deseni).
  useVisibleInterval(() => load(), REFRESH_MS, false)

  const res = data.res
  const items = Array.isArray(res?.items) ? res.items : null
  const kpis = res?.kpis || {}
  const facets = useMemo(() => res?.facets || {}, [res])
  const total = Number(res?.total) || 0
  const canWrite = res ? res.can_write === true : canWriteProp

  const tiles = [
    { key: 'open', Icon: Siren, cls: 'critical', value: kpis.open ?? 0, label: t('noc.con.kpi.open'),
      sub: t('noc.con.kpi.openSub', kpis.open_critical ?? 0), hint: t('noc.con.kpi.openHint') },
    { key: 'noc_sent', Icon: Headset, cls: 'alert', value: kpis.noc_sent ?? 0, label: t('noc.con.kpi.nocSent'), hint: t('noc.con.kpi.nocSentHint') },
    { key: 'not_called', Icon: PhoneMissed, cls: 'warning', value: kpis.not_called ?? 0, label: t('noc.con.kpi.notCalled'), hint: t('noc.con.kpi.notCalledHint') },
    { key: 'called_last_hour', Icon: PhoneCall, cls: 'valid', value: kpis.called_last_hour ?? 0, label: t('noc.con.kpi.calledHour'), hint: t('noc.con.kpi.calledHourHint') },
  ]

  const teamOpts = useMemo(() => [{ value: '', label: t('noc.con.allTeams') },
    ...(Array.isArray(facets.teams) ? facets.teams : []).map((tm) => ({ value: String(tm.id), label: `${tm.name} (${tm.count})` }))], [facets.teams, t])
  const typeOpts = useMemo(() => typeOptions(facets), [facets])
  const teamLabel = (id) => (Array.isArray(facets.teams) ? facets.teams : []).find((tm) => String(tm.id) === String(id))?.name ?? id
  const chips = activeChips(filters)
  const chipLabel = (c) => {
    switch (c.key) {
      case 'team': return t('noc.con.chip.team', teamLabel(c.value))
      case 'level': return t(`alh.level.${String(c.value).toLowerCase()}`)
      case 'type': return familyLabel(t, c.value)
      case 'noc': return t(`noc.con.noc.${c.value}`)
      case 'called': return t(`noc.con.called.${c.value}`)
      case 'state': return t(`noc.con.state.${c.value}`)
      case 'q': return `“${c.value}”`
      default: return String(c.value)
    }
  }
  const clearAll = () => { setDraftQ(''); setFilters({ ...FILTER_DEFAULTS, window: filters.window }) }

  const loading = items === null && !data.error

  return (
    <div data-slot="noc-console" className="flex min-w-0 flex-col gap-4">
      <PageHeader icon={RadioTower} title={t('noc.con.title')} description={t('noc.con.desc')} className="mb-0"
        meta={(
          <>
            {res?.truncated && (
              <Badge variant="outline" data-slot="noc-con-truncated" className="font-normal text-amber-700 dark:text-amber-300">
                {t('noc.con.truncated', res.max_rows ?? 1000)}
              </Badge>
            )}
            {data.at && <span data-slot="noc-con-updated">{t('app.lastUpdate')} {formatDate(new Date(data.at).toISOString())}</span>}
          </>
        )}
        actions={(
          <Button type="button" variant="outline" onClick={() => load({ fresh: true, manual: true })} disabled={refreshing}
            aria-busy={refreshing || undefined} title={t('app.refresh')} aria-label={t('app.refresh')} className="sm:pointer-coarse:min-h-10">
            <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />
            <span className="hidden md:inline">{t('app.refresh')}</span>
          </Button>
        )} />

      {data.error && (
        <AlertBanner tone="danger" role="alert" title={t('noc.con.loadError')} className="mb-0"
          actions={<Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={() => load({ manual: true })}><RefreshCw aria-hidden="true" />{t('noc.retry')}</Button>}>
          {String(data.error)}{items ? ` · ${t('noc.staleShown')}` : ''}
        </AlertBanner>
      )}
      {res && !canWrite && (
        <AlertBanner tone="info" className="mb-0" title={t('noc.con.readOnlyTitle')}>{t('noc.con.readOnlyBody')}</AlertBanner>
      )}

      {loading && (
        <div aria-busy="true" data-slot="noc-con-skeleton" className="flex flex-col gap-3">
          <span role="status" className="sr-only">{t('noc.con.loading')}</span>
          <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">{Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-20 rounded-lg" />)}</div>
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-14 rounded-lg" />)}
        </div>
      )}

      {res && (
        <>
          <div className="[&>[data-slot=stats-panel]]:mb-0">
            <MonitorStatsBar items={tiles} activeFilter={activeTile(filters)}
              onStatClick={(k) => { const p = tilePatch(k, filters); if (p) patch(p) }} />
          </div>

          <div data-slot="noc-con-toolbar" className="flex min-w-0 flex-col gap-2">
            <div className="flex min-w-0 flex-wrap items-center gap-2">
              <SegmentedControl ariaLabel={t('noc.con.window')} value={filters.window}
                onChange={(v) => patch({ window: v })} itemClassName="h-10 sm:h-9 sm:pointer-coarse:h-10"
                options={WINDOWS.map((w) => ({ value: w, label: t(`noc.con.win.${w}`) }))} />
              <Toggle variant="outline" size="sm" data-slot="noc-con-sent-toggle" pressed={filters.noc === 'sent'}
                onPressedChange={(on) => patch({ noc: on ? 'sent' : '' })} className="h-10 gap-1.5 sm:h-9 sm:pointer-coarse:h-10">
                <Headset aria-hidden="true" />{t('noc.con.sentOnly')}
              </Toggle>
              <InputGroup className="w-full sm:ml-auto sm:w-64">
                <InputGroupInput type="search" data-page-search="" placeholder={t('noc.con.search')} aria-label={t('noc.con.search')}
                  value={draftQ} onChange={(e) => setDraftQ(e.target.value)}
                  onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); patch({ q: draftQ.trim() }) } }} />
                <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              </InputGroup>
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:flex lg:flex-wrap lg:items-center">
              <span className="min-w-0 lg:w-56 [&_[role=combobox]]:h-10 sm:[&_[role=combobox]]:h-9">
                <SearchableSelect value={filters.team} onChange={(v) => patch({ team: v || '' })} options={teamOpts}
                  searchThreshold={6} ariaLabel={t('noc.con.team')} placeholder={t('noc.con.allTeams')} />
              </span>
              <FilterSelect label={t('noc.con.level')} value={filters.level} onChange={(v) => patch({ level: v })}
                options={[['', t('noc.con.allLevels')], ...LEVELS.map((l) => [l, t(`alh.level.${l.toLowerCase()}`)])]} />
              <FilterSelect label={t('noc.con.type')} value={filters.type} onChange={(v) => patch({ type: v })}
                options={[['', t('noc.con.allTypes')], ...typeOpts.map((o) => [o.key, `${familyLabel(t, o.key)} (${o.count})`])]} />
              <FilterSelect label={t('noc.con.calledLabel')} value={filters.called} onChange={(v) => patch({ called: v })}
                options={[['', t('noc.con.called.all')], ['no', t('noc.con.called.no')], ['yes', t('noc.con.called.yes')]]} />
              <FilterSelect label={t('noc.con.stateLabel')} value={filters.state} onChange={(v) => patch({ state: v })}
                options={[['', t('noc.con.state.all')], ['open', t('noc.con.state.open')], ['resolved', t('noc.con.state.resolved')]]} />
            </div>
            {chips.length > 0 && (
              <div data-slot="noc-con-chips" role="group" aria-label={t('noc.con.activeFilters')} className="flex flex-wrap items-center gap-1.5">
                {chips.map((c) => {
                  const label = chipLabel(c)
                  return (
                    <Badge key={c.key} variant="outline" data-filter={c.key} className="h-10 gap-1 rounded-full bg-primary/5 pr-1 pl-2.5 font-medium sm:h-7 sm:pointer-coarse:h-11">
                      <span className="max-w-[16rem] truncate">{label}</span>
                      <Button type="button" variant="ghost" size="icon-xs" className="size-10 rounded-full sm:size-5 sm:pointer-coarse:size-10"
                        aria-label={t('noc.con.removeFilter', label)} onClick={() => { if (c.key === 'q') setDraftQ(''); patch(c.patch) }}>
                        <X aria-hidden="true" className="size-3" />
                      </Button>
                    </Badge>
                  )
                })}
                <Button type="button" variant="link" size="xs" className="h-7 px-1 text-muted-foreground pointer-coarse:h-10" onClick={clearAll}>
                  {t('app.clearFilters')}
                </Button>
              </div>
            )}
          </div>

          <section ref={listRef} aria-label={t('noc.con.listLabel')} className="flex min-w-0 flex-col gap-3">
            {items.length === 0 ? (
              <StatusBlock tone="neutral" icon={ListChecks} className="rounded-xl border border-dashed"
                title={chips.length ? t('noc.con.noneFiltered') : t('noc.con.empty')}
                description={chips.length ? t('empty.hintFilter') : t('noc.con.emptyDesc')}
                actions={chips.length ? <Button type="button" variant="outline" size="sm" className="h-10 sm:h-8 sm:pointer-coarse:h-10" onClick={clearAll}>{t('app.clearFilters')}</Button> : null} />
            ) : (
              <NocConsoleList items={items} narrow={narrow} nowMs={nowMs} onCall={setCallRow} />
            )}
            {total > 0 && (
              <PaginationBar {...sp.bar} />
            )}
          </section>
        </>
      )}

      {callRow && (
        <NocCallDialog row={callRow} open onClose={() => { setCallRow(null); load() }} onChanged={() => load()} />
      )}
    </div>
  )
}

/** Etiketli küçük seçici (NativeSelect) — telefonda tam genişlik, 40 px. */
function FilterSelect({ label, value, onChange, options }) {
  const id = useId()
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      <Label htmlFor={id} className="shrink-0 text-xs font-semibold text-muted-foreground">{label}</Label>
      <NativeSelect id={id} value={value} onChange={(e) => onChange(e.target.value)} className="h-10 w-full min-w-0 sm:h-9 sm:pointer-coarse:h-10">
        {options.map(([v, l]) => <NativeSelectOption key={v || 'all'} value={v}>{l}</NativeSelectOption>)}
      </NativeSelect>
    </span>
  )
}
