import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { ArrowUp, ArrowDown, ChevronDown, Inbox, Play, Eye, Bell, History, Pencil, Link2, Copy, Clock } from 'lucide-react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import KebabMenu from './ui/KebabMenu.jsx'
import ReadOnlyBadge from './ui/ReadOnlyBadge.jsx'
import { normalizeScope } from './ui/TeamScopeSwitch.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import HelpTip from './ui/HelpTip.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import { LoadingBlock, ProgressBar } from './ui/Progress.jsx'
import { useServerPagination } from '../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../hooks/useUrlQuerySync.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { copyText } from '../utils/copyText.js'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'
import CertTableToolbar from './certtable/CertTableToolbar.jsx'
import CertBulkBar from './certtable/CertBulkBar.jsx'
import CertFilterRow from './certtable/CertFilterRow.jsx'   // kolon süzgeç satırı (2026-09-22)
import { TABLE_COLUMNS, COLUMN_BY_KEY, STATUS_OPTIONS, EMPTY_FILTERS, LEVEL_TEXT, URL_KEYS,
  readView, writeView, readPresets, writePresets, savePreset, readCols, writeCols, normalizeCols, csvColumnsFor,
  filtersFromUrl, toQuery, toUrlMapping, levelOf, trustOf, lifetimePct, isStale, relTime, shortFp } from './certtable/certTableModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'

export { TABLE_COLUMNS }

const LIST_KEY = 'certificates-table'
const REFRESH_MS = 300_000
/** Radix menü radyo değeri boş dize olamasın diye "Tümü" seçeneğinin iç değeri. */
const ALL = '__all__'

/**
 * Hüküm → görünüm tonu (eski .status-* noktası + .cf-opt-* renkleri). Satırdaki durum rozeti `data-status`
 * taşır (valid|warning|critical|error) — testler ve tur dilden bağımsız olarak buna bağlanır.
 */
const LEVEL_STATUS = { error: 'error', expired: 'critical', critical: 'critical', high: 'warning', warning: 'warning', valid: 'valid' }
const STATUS_DOT = { valid: 'bg-success', warning: 'bg-warning', critical: 'bg-destructive', error: 'bg-destructive' }
const LIFE_TONE = { valid: 'ok', warning: 'warn', critical: 'crit', error: 'crit' }
const OPT_TONE = {
  expired: 'text-destructive', critical: 'text-destructive', high: 'text-orange-600 dark:text-orange-400',
  warning: 'text-amber-600 dark:text-amber-400', valid: 'text-success', error: 'text-destructive',
}
const TRUST_TONE = {
  ok: 'bg-success/15 text-success dark:bg-success/20',
  partial: 'bg-muted text-muted-foreground',
  unknown: 'border-border bg-transparent text-muted-foreground',
  bad: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
}
const TIER_TONE = { 1: 'bg-indigo-600 text-white', 2: 'bg-sky-600 text-white', 3: 'bg-cyan-600 text-white', 4: 'bg-zinc-500 text-white' }

/*
 * Telefonda (<640 px) tablo KART listesine döner (eski App.css `@media (max-width:640px) .ct-table` kuralının
 * Tailwind karşılığı): başlık gizlenir, her satır kenarlı bir kart, her hücre "ETİKET … değer" satırı —
 * etiket `data-label`'dan ::before ile. Geniş ekranda shadcn Table (yatay kaydırmalı kap).
 */
const M_TABLE = 'max-sm:block'
const M_ROW = 'max-sm:mb-2 max-sm:block max-sm:rounded-lg max-sm:border max-sm:bg-card max-sm:px-2.5 max-sm:py-2 max-sm:hover:bg-card'
const M_CELL = cn(
  'max-sm:flex max-sm:w-full max-sm:items-center max-sm:justify-between max-sm:gap-2.5 max-sm:px-0 max-sm:py-[3px] max-sm:text-right max-sm:whitespace-normal',
  'max-sm:before:flex-[0_0_40%] max-sm:before:text-left max-sm:before:text-[10.5px] max-sm:before:font-bold max-sm:before:tracking-[.06em] max-sm:before:text-muted-foreground max-sm:before:uppercase max-sm:before:content-[attr(data-label)]',
)
const M_CELL_PLAIN = 'max-sm:flex max-sm:w-auto max-sm:justify-end max-sm:px-0 max-sm:py-[3px]'

/**
 * Tüm Sertifikalar (2026-09-13 zenginleştirme): sunucu sayfalı liste + facet'li süzgeçler + URL eşitleme
 * (`c_*`) + başlıktan sıralama + satır seçimi/toplu işlem + satır menüsü + kayıtlı görünüm/ön ayarlar +
 * CSV + sessiz tazeleme (App'in 5 dk yenilemesi ve "Şimdi Kontrol Et" `refreshKey` ile buraya düşer;
 * eskiden tablo kendi state'inde bayat kalıyordu).
 *
 * Çizim shadcn: Table ailesi, Checkbox, Badge, DropdownMenu (durum süzgeci), sayfalama ui/PaginationBar
 * (sunucu sayfalı: 1-tabanlı sayfa, standart boyut listesi, boyut tercihi `sm.pageSize.certificates-table`).
 *
 * props: onRowClick(domain, tab?), refreshKey, onCheckNow(domain), checkingDomain, onEdit(domain),
 *        canManage, globalAdmin, onRefresh(), onOpenReadOnly(row, tab?) — başka takımın satırı (org geneli
 *        görünürlük, 2026-09-26): satır Pano listesinde olmadığı için pencere satırın kendisiyle açılır.
 */
export default function CertificatesTable({ onRowClick, refreshKey, onCheckNow, checkingDomain, onEdit, canManage = false, globalAdmin = false, onRefresh, onOpenReadOnly }) {
  const t = useT()
  const toast = useToast()
  // "Takımlarım | Tüm takımlar": URL (c_scope) > kayıtlı görünüm > takımlarım; anahtar yalnız sunucu `visible_to_all` derse
  const [scope, setScopeRaw] = useState(() => normalizeScope(readUrlParam('c_scope', null) || readView()?.scope || 'mine'))
  const [visibleToAll, setVisibleToAll] = useState(false)

  const [filters, setFilters] = useState(() => {
    const f = filtersFromUrl(readUrlParam)
    // ?domain= (e-posta / palet bağlantısı) → alan süzgeci; URL'de durum yoksa kayıtlı görünümün durumu
    if (!f.domain) f.domain = readUrlParam('domain', '') || ''
    if (!readUrlParam(URL_KEYS.status, null)) { const v = readView()?.filterStatus; if (STATUS_OPTIONS.some((o) => o.value === v)) f.status = v }
    return f
  })
  const [sortBy, setSortBy] = useState(() => readUrlParam('c_sort', null) || readView()?.sortBy || 'priority|asc')
  // Sayfalama standardı (2026-09-26): `c_page`/`c_ps` adresi kancada (ps ön ayar listesine karşı doğrulanır), debounce'lu
  // süzgeç ya da sıralama DEĞİŞİNCE sayfa 1 (aynı render'da, tek istek), boyut kalıcı. API 1-tabanlı.
  const [queryFilters, setQueryFilters] = useState(filters)   // metin alanları 300 ms debounce'lu kopya
  const sp = useServerPagination({ listKey: LIST_KEY, preset: 'page', resetDeps: [queryFilters, sortBy, scope],
    url: { pageKey: 'c_page', sizeKey: 'c_ps' }, apiBase: 1 })
  const { apiPage: page, pageSize: perPage, bind: bindTotal } = sp
  const [cols, setCols] = useState(readCols)   // kayıtlı seçim + hiç görülmemiş yeni varsayılan sütunlar (2026-09-22)
  const [density, setDensity] = useState(() => readView()?.density === 'compact' ? 'compact' : 'comfortable')
  const [colFilters, setColFilters] = useState(() => !!readView()?.colFilters)   // kolon süzgeç satırı açık mı (2026-09-22)
  const [presets, setPresets] = useState(() => readPresets())

  const [certs, setCerts] = useState([])
  const [pagination, setPagination] = useState({ current_page: 1, total: 0, total_pages: 1 })
  const [facets, setFacets] = useState(null)
  const [shared, setShared] = useState({})
  const [loading, setLoading] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState(null)
  const [selected, setSelected] = useState(() => new Set())
  const loadSeq = useRef(0)
  const teamNamesRef = useRef({})

  // Metin süzgeçleri her tuşta istek atmasın; öteki süzgeçler hemen uygulanır.
  useEffect(() => {
    // fp (parmak izi) da bir METİN alanı (CertFilterRow "fingerprint" kolonu) — listede unutulunca
    // gecikme 0'a düşüyor ve her tuş vuruşu facet hesaplayan /certificates/list sorgusu atıyordu.
    const TEXT_KEYS = ['domain', 'issuer', 'fp']
    const textChanged = TEXT_KEYS.some((k) => filters[k] !== queryFilters[k])
    const id = setTimeout(() => setQueryFilters(filters), textChanged ? 300 : 0)
    return () => clearTimeout(id)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters])

  const load = useCallback(async ({ silent = false } = {}) => {
    const seq = ++loadSeq.current
    if (!silent) setLoading(true)
    try {
      const data = await api.getCertificatesPaginated(toQuery(queryFilters, { page, perPage, sortBy, scope }))
      if (seq !== loadSeq.current) return   // bayat yanıt
      if (data?.success) {
        setCerts(data.data || [])
        setPagination(data.pagination || { current_page: 1, total: 0, total_pages: 1 })
        bindTotal(data)   // { pagination: { total } } zarfı
        setVisibleToAll(!!data.visible_to_all)
        // Sunucu isteği daraltmışsa (ayar kapalı / izin yok) anahtar GERÇEKTE uygulanan kapsamı gösterir.
        if (data.scope && normalizeScope(data.scope) !== scope) setScopeRaw(normalizeScope(data.scope))
        setFacets(data.facets ?? null)
        for (const tm of data.facets?.teams || []) teamNamesRef.current[String(tm.id)] = tm.name   // çip etiketi: facet boşalsa da ad kalsın
        setShared(data.shared ?? {})
        setError(null)
      } else {
        setError(data?.error || t('tbl.loadError'))
      }
    } catch (e) {
      if (seq === loadSeq.current) setError(e?.message || t('tbl.loadError'))
    } finally {
      if (seq === loadSeq.current) { setLoading(false); setLoaded(true) }
    }
  }, [queryFilters, page, perPage, sortBy, scope, t, bindTotal])
  const loadRef = useRef(load)
  loadRef.current = load

  useEffect(() => { load() }, [load])
  // App tazelemesi ("Şimdi Kontrol Et", 5 dk döngü) → satırlar yerinde kalarak sessiz tazeleme
  const firstKey = useRef(true)
  useEffect(() => {
    if (firstKey.current) { firstKey.current = false; return }
    loadRef.current({ silent: true })
  }, [refreshKey])
  useVisibleInterval(() => loadRef.current({ silent: true }), REFRESH_MS, false)

  useUrlQuerySync(toUrlMapping(filters, { sortBy, scope }))
  // Sayfa değişince görünmeyen seçim kalmasın (toplu işlem görünmeyen satıra uygulanmasın)
  useEffect(() => { setSelected(new Set()) }, [page])

  // ── Süzgeç / sıralama / görünüm eylemleri ──
  function updateFilters(next) { setFilters(next); setSelected(new Set()) }
  function changeScope(v) { const n = normalizeScope(v); setScopeRaw(n); writeView({ scope: n }); setSelected(new Set()) }
  // Başka takımın satırı (can_manage=false) toplu işleme SEÇİLEMEZ; "tümünü seç" ve sayaçlar onları atlar.
  const manageable = (c) => c?.can_manage !== false
  const selectableCerts = certs.filter(manageable)
  function reset() {
    updateFilters({ ...EMPTY_FILTERS })
    setSortBy('priority|asc')
    writeView({ filterStatus: '', sortBy: 'priority|asc' })
  }
  function changeSort(v) { setSortBy(v); writeView({ sortBy: v }) }
  function headerSort(key) {
    const [k, d] = sortBy.split('|')
    changeSort(`${key}|${k === key && d === 'asc' ? 'desc' : 'asc'}`)
  }
  function changeCols(next) { const n = normalizeCols(next); setCols(n); writeCols(n) }
  function changeDensity(d) { setDensity(d); writeView({ density: d }) }
  function changeColFilters(v) { setColFilters(v); writeView({ colFilters: v }) }
  function selectStatus(val) { updateFilters({ ...filters, status: val }); writeView({ filterStatus: val }) }
  function savePresetNamed(name) {
    const next = savePreset(presets, { name, filters, sortBy, cols })
    setPresets(next); writePresets(next); toast.success(t('tbl.presetSaved', name))
  }
  function applyPreset(p) {
    updateFilters({ ...EMPTY_FILTERS, ...(p.filters || {}) })
    if (p.sortBy) changeSort(p.sortBy)
    if (Array.isArray(p.cols) && p.cols.length) changeCols(p.cols)
  }
  function deletePreset(name) { const next = presets.filter((p) => p.name !== name); setPresets(next); writePresets(next) }

  // ── Seçim ──
  const toggleSel = (d) => setSelected((s) => { const n = new Set(s); if (n.has(d)) n.delete(d); else n.add(d); return n })
  const toggleAllPage = () => setSelected((s) => {
    const all = selectableCerts.length > 0 && selectableCerts.every((c) => s.has(c.domain))
    const n = new Set(s); selectableCerts.forEach((c) => (all ? n.delete(c.domain) : n.add(c.domain))); return n
  })

  function download(name, csv) {
    try {
      const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { /* jsdom */ }
  }
  async function copyRowLink(domain) {
    const u = new URL(window.location.href)
    for (const p of Object.values(URL_KEYS)) u.searchParams.delete(p)
    u.searchParams.delete('c_page'); u.searchParams.set('tab', 'all'); u.searchParams.set('c_q', domain)
    if (await copyText(u.toString())) toast.success(t('share.copied')); else toast.error(u.toString())
  }

  const exportUrl = api.certExportUrl(toQuery(queryFilters, { page, perPage, sortBy, scope }), csvColumnsFor(cols))   // CSV ekrandaki kapsamı taşır
  const activeStatusLabel = t(STATUS_OPTIONS.find((o) => o.value === filters.status)?.labelKey ?? 'tbl.filterAll')
  const [sortKey, sortDir] = sortBy.split('|')
  const p = pagination
  const hasFilters = Object.keys(EMPTY_FILTERS).some((k) => filters[k] !== EMPTY_FILTERS[k])
  const showSelect = true   // seçim: kontrol herkese açık (/check yalnız oturum ister); yönetim eylemleri rol kapılı
  const helpBullets = useMemo(() => [t('tbl.how1'), t('tbl.how2'), t('tbl.how3'), t('tbl.how4'), t('tbl.how5')], [t])
  const compact = density === 'compact'
  const headCls = cn('bg-muted/60 text-[.85em] font-medium text-muted-foreground', compact ? 'h-8 px-2' : 'h-10 px-2.5')

  function headerFor(key) {
    const c = COLUMN_BY_KEY[key]
    if (key === 'status') {
      return (
        <TableHead key={key} data-col={key} className={headCls}
          aria-sort={sortKey === 'priority' ? (sortDir === 'desc' ? 'descending' : 'ascending') : 'none'}>
          <div className="inline-flex items-center gap-0.5" data-tour="ct-status">
            {/* Durum süzgeci — shadcn DropdownMenu (radyo grubu + facet sayaçları); eski elle .cf-menu yerine */}
            <DropdownMenu modal={false}>
              <DropdownMenuTrigger asChild>
                <Button type="button" variant="ghost" size="sm" title={t('tbl.filterTitle')} data-active={filters.status ? 'true' : undefined}
                  className={cn('-ml-2 h-7 gap-1 px-2 font-medium text-muted-foreground', filters.status && 'text-primary hover:text-primary')}>
                  {t('tbl.colStatus')}
                  {filters.status && <span className="font-semibold"> · {activeStatusLabel}</span>}
                  <ChevronDown aria-hidden="true" className="size-3 opacity-70" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" collisionPadding={8} className="z-(--z-menu) min-w-56">
                <DropdownMenuLabel className="text-xs text-muted-foreground">{t('tbl.filterTitle')}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuRadioGroup value={filters.status || ALL} onValueChange={(v) => selectStatus(v === ALL ? '' : v)}>
                  {STATUS_OPTIONS.map((opt) => (
                    <DropdownMenuRadioItem key={opt.value || ALL} value={opt.value || ALL}
                      className={cn(opt.value && OPT_TONE[opt.value], filters.status === opt.value && 'font-semibold')}>
                      {opt.icon && <span aria-hidden="true" className="w-4 shrink-0 text-center text-[.9em]">{opt.icon}</span>}
                      <span>{t(opt.labelKey)}</span>
                      {facets?.levels && opt.value && (
                        <span className="ml-auto pl-3 text-xs font-semibold tabular-nums text-muted-foreground">{facets.levels[opt.value] ?? 0}</span>
                      )}
                    </DropdownMenuRadioItem>
                  ))}
                </DropdownMenuRadioGroup>
              </DropdownMenuContent>
            </DropdownMenu>
            <HelpTip helpKey="tbl.help.status" label={t('tbl.colStatus')} />
          </div>
        </TableHead>
      )
    }
    if (!c.sort) return <TableHead key={key} data-col={key} className={headCls}>{t(c.labelKey)}</TableHead>
    const on = sortKey === c.sort
    return (
      <TableHead key={key} data-col={key} className={headCls} aria-sort={on ? (sortDir === 'desc' ? 'descending' : 'ascending') : 'none'}>
        <Button type="button" variant="ghost" size="sm" onClick={() => headerSort(c.sort)}
          className="-ml-2 h-7 gap-1 px-2 font-medium whitespace-nowrap text-muted-foreground hover:text-foreground">
          {t(c.labelKey)} {on ? (sortDir === 'desc' ? <ArrowDown aria-hidden="true" className="size-3" /> : <ArrowUp aria-hidden="true" className="size-3" />) : null}
        </Button>
      </TableHead>
    )
  }

  return (
    <div data-slot="cert-table" data-density={density} className="min-w-0">
      <MonitorHowBox bullets={helpBullets} title={t('tbl.howTitle')} />
      <CertTableToolbar
        filters={filters} onFilter={updateFilters} onReset={reset} facets={facets}
        cols={cols} onCols={changeCols} density={density} onDensity={changeDensity}
        colFilters={colFilters} onColFilters={changeColFilters}
        sortBy={sortBy} onSort={changeSort} presets={presets} onSavePreset={savePresetNamed}
        onApplyPreset={applyPreset} onDeletePreset={deletePreset} exportUrl={exportUrl} total={p.total} teamNames={teamNamesRef.current}
        scope={scope} onScope={changeScope} visibleToAll={visibleToAll} />

      <CertBulkBar selected={selected} rows={selectableCerts} cols={cols} shared={shared} canManage={canManage} globalAdmin={globalAdmin}
        onClear={() => setSelected(new Set())} onToggleAll={toggleAllPage} onDone={() => { onRefresh?.(); loadRef.current({ silent: true }) }} download={download} />

      {error && !certs.length ? (
        <StatusBlock tone="danger" icon={Inbox} title={t('tbl.loadError')} description={error}
          actions={<Button type="button" variant="secondary" size="sm" onClick={() => load()}>{t('tbl.retry')}</Button>} />
      ) : !loaded ? (
        <LoadingBlock label={t('tbl.loading')} fullWidth />
      ) : certs.length === 0 && !colFilters ? (
        /* Kolon süzgeç satırı açıkken tablo AYAKTA kalır (aşağıda tbody içinde "eşleşme yok" satırı):
           tabloyu kaldırmak süzgeç satırını da götürüyor, kullanıcı ne yazdığını göremiyor ve o hücreyi
           temizleyemiyordu. Envanterde aynı bug 3284c40e ile düzeltilmişti — kardeş yüzeye taşındı. */
        <StatusBlock tone="neutral" icon={Inbox} title={t('tbl.noCerts')} description={hasFilters ? t('empty.hintFilter') : t('empty.hintCerts')}
          actions={hasFilters ? <Button type="button" variant="secondary" size="sm" onClick={reset}>{t('tbl.reset')}</Button> : null} />
      ) : (
        <div className="relative rounded-lg border bg-card max-sm:border-0 max-sm:bg-transparent" aria-busy={loading}>
          {/* Sessiz olmayan yeniden yükleme: üstte ince belirsiz şerit (eski .ct-loading-line), satırlar soluk */}
          {loading && <div className="absolute inset-x-0 top-0 z-[2]"><ProgressBar size="sm" decorative /></div>}
          <Table className={M_TABLE}>
            <TableHeader className="max-sm:hidden">
              <TableRow className="hover:bg-transparent">
                {showSelect && (
                  <TableHead data-col="select" data-tour="ct-select" className={cn(headCls, 'w-8 text-center')}>
                    <Checkbox aria-label={t('bulk.selectAll')} checked={selectableCerts.length > 0 && selectableCerts.every((c) => selected.has(c.domain))}
                      disabled={selectableCerts.length === 0} onCheckedChange={toggleAllPage} />
                  </TableHead>
                )}
                {cols.map(headerFor)}
                <TableHead data-col="actions" className={cn(headCls, 'w-11 text-right')}><span className="sr-only">{t('tbl.actions')}</span></TableHead>
              </TableRow>
              {colFilters && <CertFilterRow filters={filters} onFilter={updateFilters} cols={cols} facets={facets} teamNames={teamNamesRef.current} showSelect={showSelect} pageRows={certs} />}
            </TableHeader>
            <TableBody className={cn('max-sm:block', loading && 'opacity-55 transition-opacity motion-reduce:transition-none')}>
              {certs.map((cert, i) => (
                <CertRow key={cert.domain} cert={cert} cols={cols} shared={shared[cert.domain] ?? 1} tourId={i === 0 ? 'ct-row-menu' : undefined}
                  compact={compact}
                  selected={selected.has(cert.domain)} onToggle={showSelect ? toggleSel : null}
                  onOpen={onRowClick} onCheckNow={onCheckNow} checking={checkingDomain === cert.domain}
                  onEdit={canManage ? onEdit : null} onCopyLink={copyRowLink}
                  readOnly={!manageable(cert)} onOpenReadOnly={onOpenReadOnly} showTeam={cols.includes('team')}
                  onSameCert={(fp) => updateFilters({ ...filters, fp })} />
              ))}
              {certs.length === 0 && (
                <TableRow data-slot="table-empty-row" className="hover:bg-transparent max-sm:block">
                  <TableCell colSpan={99} className="px-3 py-5 text-center max-sm:block">
                    <div className="flex flex-wrap items-center justify-center gap-2.5 text-muted-foreground">
                      <Inbox aria-hidden="true" className="size-3.5" />
                      <span>{t('tbl.noMatch')}</span>
                      {hasFilters && <Button type="button" variant="secondary" size="sm" onClick={reset}>{t('tbl.reset')}</Button>}
                    </div>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      )}
      {error && certs.length > 0 && <div className="mt-2 mb-2.5 text-[.84em] text-destructive" role="status">{t('tbl.refreshFailed')}</div>}

      {/* Sunucu sayfalı: sayfa 1-tabanlı, boyut listesi standart (PAGE_SIZE_OPTIONS), tercih kalıcı. */}
      <PaginationBar {...sp.bar} />
    </div>
  )
}

function CertRow({ cert, cols, shared, selected, onToggle, onOpen, onCheckNow, checking, onEdit, onCopyLink, onSameCert, tourId, compact,
  readOnly = false, onOpenReadOnly = null, showTeam = true }) {
  const t = useT()
  const level = levelOf(cert)
  // Başka takımın satırı: pencere satırın kendisiyle (Pano listesinde yok) ve salt okunur açılır.
  const open = (tab) => {
    if (readOnly && onOpenReadOnly) onOpenReadOnly(cert, tab)
    else if (tab) onOpen(cert.domain, tab)
    else onOpen(cert.domain)
  }
  const status = LEVEL_STATUS[level] ?? 'valid'
  const statusText = t(LEVEL_TEXT[level] ?? 'tbl.statusValid')
  const days = cert.days_remaining
  const life = lifetimePct(cert)
  const stale = isStale(cert)
  const trust = trustOf(cert)
  const rel = relTime(cert.checked_at)
  const sanList = Array.isArray(cert.san) ? cert.san : []
  const stop = (e) => e.stopPropagation()
  const cellCls = cn(compact ? 'px-2 py-1 text-[.84em]' : 'px-2.5 py-2.5 text-[.9em]', M_CELL)

  const menu = [
    { label: t('tbl.actDetail'), icon: <Eye size={13} />, onClick: () => open() },
    // Salt okunur satırda kontrol / alarmlar / düzenle YOK (alarmlar takım kapsamlı; yazma sunucuda reddedilir)
    onCheckNow && !readOnly ? { label: checking ? t('tbl.actChecking') : t('tbl.actCheck'), icon: <Play size={13} />, onClick: () => { if (!checking) onCheckNow(cert.domain) } } : null,
    !readOnly ? { label: t('tbl.actAlerts'), icon: <Bell size={13} />, onClick: () => open('alerts') } : null,
    { label: t('tbl.actHistory'), icon: <History size={13} />, onClick: () => open('history') },
    onEdit && !readOnly ? { label: t('tbl.actEdit'), icon: <Pencil size={13} />, onClick: () => onEdit(cert.domain) } : null,
    shared > 1 && cert.fingerprint ? { label: t('tbl.actSameCert', shared), icon: <Copy size={13} />, onClick: () => onSameCert(cert.fingerprint) } : null,
    { label: t('share.copyLink'), icon: <Link2 size={13} />, onClick: () => onCopyLink(cert.domain) },
  ].filter(Boolean)

  const sharedBtn = (withTitle) => (
    <Button type="button" variant="outline" size="xs" data-cert-shared="true"
      className="h-auto rounded-full px-1.5 py-px text-[.78em] font-bold text-muted-foreground shadow-none hover:border-primary hover:text-primary"
      onClick={(e) => { stop(e); onSameCert(cert.fingerprint) }} title={withTitle ? t('tbl.sharedTitle', shared) : undefined}>
      ×{shared}
    </Button>
  )
  const mono = 'font-mono text-[.92em]'

  const cell = (key) => {
    const label = t(COLUMN_BY_KEY[key].labelKey)
    const td = (content, cls, extra = {}) => <TableCell key={key} data-label={label} className={cn(cellCls, cls)} {...extra}>{content}</TableCell>
    switch (key) {
      case 'domain': return td((
        <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5 max-sm:justify-end">
          <strong className="max-sm:[overflow-wrap:anywhere]">{cert.domain}</strong>
          {cert.tier && <Badge data-slot="cert-tier" className={cn('rounded px-1.5 text-[10.5px] font-extrabold', TIER_TONE[cert.tier] ?? TIER_TONE[4])}>T{cert.tier}</Badge>}
          {shared > 1 && sharedBtn(true)}
          {readOnly && <ReadOnlyBadge className="max-sm:w-full max-sm:justify-end" teamId={showTeam ? undefined : cert.team_id} teamName={showTeam ? undefined : cert.team_name} />}
        </span>
      ))
      case 'issuer': return td(cert.issuer_cn || cert.issuer || 'N/A')
      case 'subject': return td(cert.subject || 'N/A')
      case 'team': return td(cert.team_name ? <TeamBadge teamId={cert.team_id} teamName={cert.team_name} /> : '—')
      case 'expiry': return td(formatDate(cert.not_after))
      case 'days': return td((
        <span className="inline-flex flex-col items-start gap-1 max-sm:items-end">
          <strong>{days ?? 'N/A'}</strong>
          {life != null && !compact && (
            <span className="block w-20 max-sm:w-16" title={t('tbl.lifeTitle', life)}>
              <ProgressBar value={life} size="sm" decorative tone={LIFE_TONE[status]} />
            </span>
          )}
        </span>
      ), 'min-w-[70px] max-sm:min-w-0')
      case 'status': return td((
        <span className="inline-flex flex-wrap items-center gap-1.5 max-sm:justify-end">
          <span data-slot="cert-level" data-status={status} data-level={level} className="inline-flex items-center gap-1.5">
            <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', STATUS_DOT[status])} />
            {statusText}
          </span>
          {isInsecure(cert) && (
            <Badge variant="secondary" data-slot="cert-insecure" title={securityTitle(cert, t)}
              className="bg-destructive/10 text-[.85em] font-bold text-destructive dark:bg-destructive/20">
              {t('cert.sec.insecure')}
            </Badge>
          )}
        </span>
      ))
      case 'trust': return td((
        <Badge variant="outline" data-slot="cert-trust" data-tone={trust.tone}
          title={trust.issues.map((i) => t(`tbl.trust.${i}`)).join(' · ') || t(`tbl.trust.${trust.tone}`)}
          className={cn('border-transparent text-[.85em] font-semibold', TRUST_TONE[trust.tone])}>
          {trust.tone === 'bad' ? trust.issues.map((i) => t(`tbl.trust.${i}`)).join(', ') : t(`tbl.trust.${trust.tone}`)}
        </Badge>
      ))
      case 'san': return td(sanList.length, undefined, { title: sanList.join('\n') })
      case 'shared': return td(shared > 1 ? sharedBtn(false) : '—')
      case 'key': return td(cert.public_key_algorithm ? `${cert.public_key_algorithm}${cert.public_key_size ? ' ' + cert.public_key_size : ''}` : '—', mono)
      case 'signature': return td(cert.signature_algorithm || '—', mono)
      case 'port': return td(cert.port ?? 443)
      case 'tier': return td(cert.tier ? `T${cert.tier}` : '—')
      case 'via': return td(cert.via === 'proxy' ? t('card.viaProxy') : cert.via ? t('card.viaDirect') : '—')
      case 'tls': return td(cert.tls_mode_used || '—', mono)
      case 'intermediate': return td(cert.intermediate_days_remaining ?? '—',
        cert.intermediate_days_remaining != null && cert.intermediate_days_remaining < (days ?? Infinity) ? 'font-bold text-amber-700 dark:text-amber-400' : undefined)
      case 'notBefore': return td(formatDate(cert.not_before))
      case 'fingerprint': return td(shortFp(cert.fingerprint) || '—', mono, { title: cert.fingerprint || '' })
      case 'serial': return td(shortFp(cert.serial_number) || '—', mono, { title: cert.serial_number || '' })
      case 'checked': return td((
        <span className="inline-flex flex-wrap items-center gap-1.5 max-sm:justify-end">
          {rel ? t(`tbl.rel.${rel.unit}`, rel.n) : formatDate(cert.checked_at)}
          {stale && (
            <Badge variant="warning" data-slot="cert-stale" title={t('tbl.staleTitle', cert.check_interval_hours || 1)} className="text-[.85em]">
              <Clock aria-hidden="true" /> {t('tbl.stale')}
            </Badge>
          )}
        </span>
      ), undefined, { title: formatDate(cert.checked_at) })
      default: return td(null)
    }
  }

  return (
    <TableRow data-domain={cert.domain} data-state={selected ? 'selected' : undefined} data-readonly={readOnly ? 'true' : undefined} tabIndex={0}
      className={cn('cursor-pointer focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-primary', M_ROW)}
      onClick={() => open()}
      onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); open() } }}>
      {onToggle && (
        <TableCell className={cn('w-8 text-center', compact ? 'px-2 py-1' : 'px-2.5 py-2.5', M_CELL_PLAIN)} onClick={stop}>
          {/* Salt okunur satır seçilemez (toplu işlem yok) — hücre hizalama için boş kalır */}
          {!readOnly && <Checkbox checked={selected} onCheckedChange={() => onToggle(cert.domain)} aria-label={t('bulk.selectOneFor', cert.domain)} />}
        </TableCell>
      )}
      {cols.map(cell)}
      <TableCell className={cn('w-11 text-right', compact ? 'px-2 py-1' : 'px-2.5 py-2', M_CELL_PLAIN)} onClick={stop} onKeyDown={stop} data-tour={tourId}>
        <KebabMenu items={menu} label={t('tbl.actions')} rowLabel={cert.domain} />
      </TableCell>
    </TableRow>
  )
}
