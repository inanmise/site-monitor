import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Download, RotateCcw } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import DateTimeRangePicker from '../../ui/DateTimeRangePicker.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { useServerPagination } from '../../../hooks/useServerPagination.js'
import { useUrlQuerySync, readUrlParam } from '../../../hooks/useUrlQuerySync.js'
import { fmtNum } from './PolicyRow.jsx'
import { DataTable, SortTh, TH, TD, TD_NUM } from '../HealthUi.jsx'
import ToneBadge from '../ToneBadge.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import { ToolbarSearch } from '../ListToolbar.jsx'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Label } from '@/components/shadcn/label'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Veri Saklama → Son çalışmalar: sunucu-taraflı sayfalama, süzgeç (tür / yalnız hatalı / politika /
 * tarih / metin), sıralama, CSV ve "neden 0 silindi" tanısı.
 *
 * <p>Eskiden tek seferlik `getRetentionRuns(10)` ile ilk 10 satır çekiliyor, kalem hataları ve
 * atlanma sebepleri (items[].error / items[].skipped) uçtan geliyor ama hiç çizilmiyordu — "10 gündür
 * 0 siliniyor" sorusu ekrandan cevaplanamıyordu (2026-09-10). URL param'ları `r_` önekiyle yaşar
 * (PAGE_STATE_PREFIXES); uygulamanın `tab` anahtarına dokunulmaz.
 */
const KINDS = ['all', 'real', 'dry', 'hold']
const RANGES = ['all', '7', '30', 'custom']
const SORTS = ['started_at', 'total_deleted', 'failed_count', 'duration_ms']

function isoLocalDay(d, endOfDay) {
  const x = new Date(d)
  if (endOfDay) x.setHours(23, 59, 59, 0); else x.setHours(0, 0, 0, 0)
  return x.toISOString().slice(0, 19)
}

/**
 * `since`/`until` BÖLGESİZ UTC dizesidir (`isoLocalDay` → toISOString().slice(0, 19)). `new Date(x)` bölgesiz tarih-saati
 * YEREL saat sayar → seçici günü saat farkı kadar kayık açıyordu (İstanbul'da 1 Ekim 00:00 seçimi 30 Eylül 21:00 görünürdü).
 * MonitorChangesConsole ile aynı: 'Z' eklenir; zaten 'Z' / ±hh:mm taşıyan ya da saatsiz değer olduğu gibi ayrıştırılır.
 */
function parseUtc(s) {
  const str = String(s)
  const zoned = /(?:[zZ]|[+-]\d{2}:?\d{2})$/.test(str)
  return new Date(zoned || !str.includes('T') ? str : `${str}Z`)
}

export default function RetentionRunsPanel({ policies = [], holdOn = false, refreshKey = 0 }) {
  const t = useT()
  const [q, setQ]           = useState(() => readUrlParam('r_q', ''))
  const [qTerm, setQTerm]   = useState(q)
  const [kind, setKind]     = useState(() => KINDS.includes(readUrlParam('r_kind', 'all')) ? readUrlParam('r_kind', 'all') : 'all')
  const [failedOnly, setFailedOnly] = useState(() => readUrlParam('r_failed', '') === '1')
  const [policyId, setPolicyId]     = useState(() => readUrlParam('r_policy', ''))
  const [rangeKey, setRangeKey]     = useState(() => RANGES.includes(readUrlParam('r_range', 'all')) ? readUrlParam('r_range', 'all') : 'all')
  const [since, setSince]   = useState(() => readUrlParam('r_since', ''))
  const [until, setUntil]   = useState(() => readUrlParam('r_until', ''))
  const [sort, setSort]     = useState(() => SORTS.includes(readUrlParam('r_sort', 'started_at')) ? readUrlParam('r_sort', 'started_at') : 'started_at')
  const [dir, setDir]       = useState(() => readUrlParam('r_dir', 'desc') === 'asc' ? 'asc' : 'desc')
  const [rows, setRows]     = useState(null)
  const [error, setError]   = useState(null)
  const loadSeq = useRef(0)

  // Arama 300 ms debounce — her tuşta sunucuya gitmesin.
  useEffect(() => { const id = setTimeout(() => setQTerm(q), 300); return () => clearTimeout(id) }, [q])

  // Sayfalama standardı (2026-09-26). Eskiden `useEffect(() => setPage(0), [süzgeçler…, size])` MOUNT'ta da
  // koşup `r_page` derin bağlantısını ilk render'da 1'e düşürüyordu; varsayılan 20 boyut listesinde yoktu.
  // Kanca: panel ön ayarı (25), sıfırlama yalnız süzgeç/sıralama DEĞERİ değişince, URL r_page / r_ps.
  const sp = useServerPagination({ listKey: 'retention-runs', preset: 'panel',
    resetDeps: [kind, failedOnly, policyId, since, until, qTerm, sort, dir],
    url: { pageKey: 'r_page', sizeKey: 'r_ps' }, apiBase: 0 })
  const { apiPage: page, pageSize: size } = sp

  const params = useMemo(() => ({
    page, size, kind, failed: failedOnly, policyId: policyId || null,
    since: since || null, until: until || null, q: qTerm.trim() || null, sort, dir,
  }), [page, size, kind, failedOnly, policyId, since, until, qTerm, sort, dir])

  useUrlQuerySync({
    r_q: qTerm.trim() || null, r_kind: kind !== 'all' ? kind : null, r_failed: failedOnly ? '1' : null,
    r_policy: policyId || null, r_range: rangeKey !== 'all' ? rangeKey : null,
    r_since: rangeKey === 'custom' ? since || null : null, r_until: rangeKey === 'custom' ? until || null : null,
    r_sort: sort !== 'started_at' ? sort : null, r_dir: dir !== 'desc' ? dir : null,
  })

  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    try {
      const res = await api.admin.getRetentionRuns(params)
      if (seq !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) { setRows(res.data ?? []); sp.setTotal(res.total ?? (res.data?.length ?? 0)); setError(null) }
      else { setRows([]); setError(res?.error || t('settings.loadError')) }
    } catch (e) {
      if (seq !== loadSeq.current) return
      setRows([]); setError(e?.message || t('settings.loadError'))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params, t, refreshKey])
  useEffect(() => { load() }, [load])
  // Süzgeç/boyut değişince ilk sayfaya dönüşü useServerPagination yapar (değer karşılaştırmalı, mount'ta değil).

  function applyRange(k) {
    setRangeKey(k)
    if (k === 'all') { setSince(''); setUntil('') }
    else if (k === 'custom') { /* seçici tarihleri uygulayınca yazılır */ }
    else {
      const days = Number(k)
      setSince(isoLocalDay(new Date(Date.now() - (days - 1) * 864e5), false))
      setUntil(isoLocalDay(new Date(), true))
    }
  }
  function applyCustom(from, to) {
    setSince(from ? isoLocalDay(from, false) : ''); setUntil(to ? isoLocalDay(to, true) : '')
  }
  function toggleSort(key) {
    if (sort === key) setDir(d => (d === 'desc' ? 'asc' : 'desc'))
    else { setSort(key); setDir('desc') }
  }
  function backToLatest() {
    setSort('started_at'); setDir('desc'); sp.reset()
  }
  const isDefaultView = sort === 'started_at' && dir === 'desc' && sp.page === 1
  // Özel aralık seçicisinin uçları KARARLI (2026-09-27 regresyon B1): tarih yokken her çizimde `new Date()`
  // geçmek seçicinin taslağını panelin her yeniden çiziminde (yükleme, arama) sıfırlıyordu.
  const customOpen = rangeKey === 'custom'
  const pickerRange = useMemo(() => (customOpen ? {
    from: since ? parseUtc(since) : new Date(Date.now() - 29 * 864e5),
    to: until ? parseUtc(until) : new Date(),
  } : null), [customOpen, since, until])

  const policyOptions = useMemo(() => [
    { value: '', label: t('ret.filterPolicyAll') },
    ...policies.map(p => ({ value: p.id, label: p.table || p.id })),
  ], [policies, t])

  // Sıralanabilir başlık — ortak HealthUi SortTh (shadcn TableHead + ghost Button, aria-sort)
  const th = (key, label, numeric = false) => (
    <SortTh label={label} numeric={numeric} active={sort === key} dir={dir} onSort={() => toggleSort(key)} />
  )

  return (
    <div data-slot="ret-runs" className="flex min-w-0 flex-col gap-2.5">
      {/* Araç çubuğu — mobil-önce: sarar; arama telefonda tam genişlik */}
      <div className="flex flex-wrap items-center gap-2">
        <SegmentedControl value={kind} onChange={setKind} ariaLabel={t('ret.filterKind')} className="max-w-full flex-wrap"
          options={KINDS.map(k => ({ value: k, label: k === 'all' ? t('ret.filterKindAll') : k === 'real' ? t('ret.kindReal') : k === 'dry' ? t('ret.kindDry') : t('ret.kindHold') }))} />
        <FailedOnly checked={failedOnly} onChange={setFailedOnly} label={t('ret.filterFailed')} />
        <div className="w-full min-w-0 sm:w-52">
          <SearchableSelect value={policyId} onChange={v => setPolicyId(v || '')} options={policyOptions}
            searchThreshold={4} ariaLabel={t('ret.filterPolicy')} />
        </div>
        <ToolbarSearch value={q} onChange={setQ} placeholder={t('ret.searchPlaceholder')} ariaLabel={t('ret.searchPlaceholder')}
          clearLabel={t('app.clear')} className="w-full max-w-none sm:w-auto sm:max-w-xs" />
        <SegmentedControl value={rangeKey} onChange={applyRange} ariaLabel={t('ret.filterRange')} className="max-w-full flex-wrap"
          options={RANGES.map(k => ({ value: k, label: k === 'all' ? t('ret.rangeAll') : k === 'custom' ? t('ret.rangeCustom') : t('ret.rangeDays', k) }))} />
        {pickerRange && (
          <DateTimeRangePicker from={pickerRange.from} to={pickerRange.to} onApply={applyCustom} />
        )}
        <span className="hidden flex-1 sm:block" />
        {!isDefaultView && (
          <Button type="button" variant="outline" size="sm" onClick={backToLatest}><RotateCcw aria-hidden="true" /> {t('ret.backToLatest')}</Button>
        )}
        <Button asChild variant="outline" size="sm">
          <a href={api.admin.getRetentionRunsCsvUrl({ ...params, page: undefined, size: undefined })} download>
            <Download aria-hidden="true" /> {t('ret.exportCsv')}
          </a>
        </Button>
      </div>

      {error && <AlertBanner tone="danger">{error}</AlertBanner>}
      {!rows ? <LoadingBlock label={t('settings.loading')} size={16} />
        : rows.length === 0 ? <p className="text-xs text-muted-foreground">{t('ret.historyEmpty')}</p> : (
          <DataTable>
            <TableHeader className="bg-muted/50">
              <TableRow className="hover:bg-transparent">
                {th('started_at', t('ret.colWhen'))}
                <TableHead className={TH}>{t('ret.colKind')}</TableHead>
                {th('total_deleted', t('ret.colDeleted'), true)}
                {th('failed_count', t('ret.colFailed'), true)}
                {th('duration_ms', t('ret.colDuration'), true)}
                <TableHead className={cn(TH, 'hidden md:table-cell')}>{t('ret.colBy')}</TableHead>
                <TableHead className={TH}>{t('ret.colTopTables')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map(r => <RunRow key={r.id} r={r} holdOn={holdOn} t={t} />)}
            </TableBody>
          </DataTable>
        )}
      {rows && <PaginationBar {...sp.bar} />}
    </div>
  )
}

/** "Yalnız hatalı" süzgeci — shadcn Checkbox + bağlı etiket. */
function FailedOnly({ checked, onChange, label }) {
  const id = useId()
  return (
    <div className="flex min-h-8 items-center gap-2">
      <Checkbox id={id} checked={checked} onCheckedChange={v => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer text-sm font-normal">{label}</Label>
    </div>
  )
}

/** Tek koşum satırı + "neden 0" tanısı (hold / opt-in kapalı / uygun kayıt yok). */
function RunRow({ r, t }) {
  const items = r.items || []
  const top = items.filter(i => i.rows > 0).sort((a, b) => b.rows - a.rows).slice(0, 3)
  const errs = items.filter(i => i.error)
  const skipped = items.filter(i => i.skipped)
  const zero = (r.total_deleted ?? 0) === 0 && (r.failed_count ?? 0) === 0
  let why = null
  if (zero) {
    if (r.hold_active) why = t('ret.whyZeroHold')
    else if (items.length > 0 && skipped.length === items.length && skipped.every(i => i.skipped === 'opt-in-kapali')) why = t('ret.whyZeroOptIn')
    else if (items.length > 0) {
      const cut = items.map(i => i.cutoff).filter(Boolean).sort()[0]
      why = t('ret.whyZeroNothing', cut ? formatDateSec(cut) : '—')
    }
  }
  // Kalem rozetlerinin ayrıntısı (hata metni / atlanma sebebi) dokunmatikte de okunur: ui/HintPopover.
  const errTitle = (i) => `${i.policy_id}: ${i.error}`
  const skipTitle = skipped.map(i => `${i.policy_id}: ${i.skipped}`).join('\n')
  return (
    <>
      <TableRow>
        <TableCell className={cn(TD, 'font-mono text-xs whitespace-nowrap')}>{formatDateSec(r.started_at)}</TableCell>
        <TableCell className={TD}>{r.hold_active ? t('ret.kindHold') : r.dry_run ? t('ret.kindDry') : t('ret.kindReal')}</TableCell>
        <TableCell className={cn(TD_NUM, 'font-mono')}>{fmtNum(r.total_deleted)}</TableCell>
        <TableCell className={cn(TD_NUM, 'font-mono', r.failed_count > 0 && 'font-semibold text-destructive')}>{r.failed_count}</TableCell>
        <TableCell className={cn(TD_NUM, 'font-mono')}>{r.duration_ms} ms</TableCell>
        <TableCell className={cn(TD, 'hidden text-xs text-muted-foreground md:table-cell')}>{r.triggered_by || t('ret.byScheduler')}</TableCell>
        <TableCell className={cn(TD, 'text-xs text-muted-foreground')}>
          <span className="inline-flex min-w-0 flex-wrap items-center gap-1.5">
            <span className="[overflow-wrap:anywhere]">{top.map(i => `${i.table} ${fmtNum(i.rows)}`).join(' · ') || '—'}</span>
            {errs.map(i => (
              <HintPopover key={'e' + i.policy_id} content={errTitle(i)}>
                <ToneBadge tone="danger" data-item="error" title={errTitle(i)}>{i.table} · {t('ret.errBadge')}</ToneBadge>
              </HintPopover>
            ))}
            {skipped.length > 0 && (
              <HintPopover content={skipTitle}>
                <ToneBadge tone="warning" data-item="skipped" title={skipTitle}>{t('ret.skipBadge', skipped.length)}</ToneBadge>
              </HintPopover>
            )}
          </span>
        </TableCell>
      </TableRow>
      {why && (
        <TableRow data-why-zero="" className="hover:bg-transparent">
          <TableCell colSpan={7} className="bg-muted/40 px-3 py-1.5 text-xs whitespace-normal text-muted-foreground italic">{why}</TableCell>
        </TableRow>
      )}
    </>
  )
}
