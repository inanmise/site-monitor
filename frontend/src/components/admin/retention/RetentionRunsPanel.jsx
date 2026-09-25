import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Download, RotateCcw } from 'lucide-react'
import { api, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import DateTimeRangePicker from '../../ui/DateTimeRangePicker.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { readPageSize, writePageSize } from '../../../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../../hooks/useUrlQuerySync.js'
import { fmtNum } from './PolicyRow.jsx'
import { Button } from '@/components/shadcn/button'

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
  const [page, setPage]     = useState(() => Math.max(0, readUrlInt('r_page', 1) - 1))
  const [size, setSize]     = useState(() => readPageSize('retention-runs', 20))
  const [rows, setRows]     = useState(null)
  const [total, setTotal]   = useState(0)
  const [error, setError]   = useState(null)
  const loadSeq = useRef(0)

  // Arama 300 ms debounce — her tuşta sunucuya gitmesin.
  useEffect(() => { const id = setTimeout(() => setQTerm(q), 300); return () => clearTimeout(id) }, [q])

  const params = useMemo(() => ({
    page, size, kind, failed: failedOnly, policyId: policyId || null,
    since: since || null, until: until || null, q: qTerm.trim() || null, sort, dir,
  }), [page, size, kind, failedOnly, policyId, since, until, qTerm, sort, dir])

  useUrlQuerySync({
    r_q: qTerm.trim() || null, r_kind: kind !== 'all' ? kind : null, r_failed: failedOnly ? '1' : null,
    r_policy: policyId || null, r_range: rangeKey !== 'all' ? rangeKey : null,
    r_since: rangeKey === 'custom' ? since || null : null, r_until: rangeKey === 'custom' ? until || null : null,
    r_sort: sort !== 'started_at' ? sort : null, r_dir: dir !== 'desc' ? dir : null,
    r_page: page > 0 ? page + 1 : null,
  })

  const load = useCallback(async () => {
    const seq = ++loadSeq.current
    try {
      const res = await api.admin.getRetentionRuns(params)
      if (seq !== loadSeq.current) return   // bayat yanıt — daha yeni bir istek yolda
      if (res?.success) { setRows(res.data ?? []); setTotal(res.total ?? (res.data?.length ?? 0)); setError(null) }
      else { setRows([]); setError(res?.error || t('settings.loadError')) }
    } catch (e) {
      if (seq !== loadSeq.current) return
      setRows([]); setError(e?.message || t('settings.loadError'))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params, t, refreshKey])
  useEffect(() => { load() }, [load])
  // Süzgeç/boyut değişince ilk sayfaya dön (sayfa 0 ise load dep'lerden fırlar; seq guard bayat yanıtı düşürür).
  useEffect(() => { setPage(0) }, [kind, failedOnly, policyId, since, until, qTerm, size, sort, dir])

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
    setSort('started_at'); setDir('desc'); setPage(0)
  }
  const isDefaultView = sort === 'started_at' && dir === 'desc' && page === 0

  const policyOptions = useMemo(() => [
    { value: '', label: t('ret.filterPolicyAll') },
    ...policies.map(p => ({ value: p.id, label: p.table || p.id })),
  ], [policies, t])

  const totalPages = Math.max(1, Math.ceil(total / size))
  const th = (key, label, cls = 'dbtcol-th') => (
    <th className={`${cls} ret-th-sort`} aria-sort={sort === key ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className="ret-th-btn" onClick={() => toggleSort(key)}>
        {label}{sort === key ? (dir === 'asc' ? ' ▲' : ' ▼') : ''}
      </button>
    </th>
  )

  return (
    <div className="ret-runs">
      <div className="ret-runs-toolbar">
        <SegmentedControl value={kind} onChange={setKind} ariaLabel={t('ret.filterKind')}
          options={KINDS.map(k => ({ value: k, label: k === 'all' ? t('ret.filterKindAll') : k === 'real' ? t('ret.kindReal') : k === 'dry' ? t('ret.kindDry') : t('ret.kindHold') }))} />
        <label className="ret-runs-check">
          <input type="checkbox" checked={failedOnly} onChange={e => setFailedOnly(e.target.checked)} /> {t('ret.filterFailed')}
        </label>
        <SearchableSelect value={policyId} onChange={v => setPolicyId(v || '')} options={policyOptions}
          searchThreshold={4} ariaLabel={t('ret.filterPolicy')} />
        <input className="filter-input" value={q} onChange={e => setQ(e.target.value)}
          placeholder={t('ret.searchPlaceholder')} aria-label={t('ret.searchPlaceholder')} />
        <SegmentedControl value={rangeKey} onChange={applyRange} ariaLabel={t('ret.filterRange')}
          options={RANGES.map(k => ({ value: k, label: k === 'all' ? t('ret.rangeAll') : k === 'custom' ? t('ret.rangeCustom') : t('ret.rangeDays', k) }))} />
        {rangeKey === 'custom' && (
          <DateTimeRangePicker from={since ? new Date(since) : new Date(Date.now() - 29 * 864e5)}
            to={until ? new Date(until) : new Date()} onApply={applyCustom} />
        )}
        <span className="ret-runs-spacer" />
        {!isDefaultView && (
          <Button type="button" variant="outline" size="sm" onClick={backToLatest}><RotateCcw size={13} /> {t('ret.backToLatest')}</Button>
        )}
        <Button asChild variant="outline" size="sm">
          <a href={api.admin.getRetentionRunsCsvUrl({ ...params, page: undefined, size: undefined })} download>
            <Download size={13} /> {t('ret.exportCsv')}
          </a>
        </Button>
      </div>

      {error && <AlertBanner tone="danger">{error}</AlertBanner>}
      {!rows ? <LoadingBlock label={t('settings.loading')} className="ret-loading" size={16} />
        : rows.length === 0 ? <p className="field-hint">{t('ret.historyEmpty')}</p> : (
          <div className="health-table-wrap">
            <table className="health-dbtable">
              <thead><tr>
                {th('started_at', t('ret.colWhen'))}
                <th className="dbtcol-th">{t('ret.colKind')}</th>
                {th('total_deleted', t('ret.colDeleted'), 'dbtcol-th-num')}
                {th('failed_count', t('ret.colFailed'), 'dbtcol-th-num')}
                {th('duration_ms', t('ret.colDuration'), 'dbtcol-th-num')}
                <th className="dbtcol-th">{t('ret.colBy')}</th>
                <th className="dbtcol-th">{t('ret.colTopTables')}</th>
              </tr></thead>
              <tbody>
                {rows.map(r => <RunRow key={r.id} r={r} holdOn={holdOn} t={t} />)}
              </tbody>
            </table>
          </div>
        )}
      {rows && total > 0 && (
        <PaginationBar page={page + 1} totalPages={totalPages} totalItems={total}
          rangeStart={total === 0 ? 0 : page * size + 1} rangeEnd={Math.min((page + 1) * size, total)}
          pageSize={size} onPageChange={p => setPage(p - 1)}
          onPageSizeChange={n => { setSize(n); writePageSize('retention-runs', n) }} />
      )}
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
  return (
    <>
      <tr>
        <td className="sys-mono sys-small">{formatDateSec(r.started_at)}</td>
        <td>{r.hold_active ? t('ret.kindHold') : r.dry_run ? t('ret.kindDry') : t('ret.kindReal')}</td>
        <td className="dbtcol-num-cell sys-mono">{fmtNum(r.total_deleted)}</td>
        <td className={`dbtcol-num-cell sys-mono${r.failed_count > 0 ? ' sys-err-text' : ''}`}>{r.failed_count}</td>
        <td className="dbtcol-num-cell sys-mono">{r.duration_ms} ms</td>
        <td className="sys-small sys-muted">{r.triggered_by || t('ret.byScheduler')}</td>
        <td className="sys-small sys-muted">
          {top.map(i => `${i.table} ${fmtNum(i.rows)}`).join(' · ') || '—'}
          {errs.map(i => (
            <span key={'e' + i.policy_id} className="ret-item-badge ret-item-badge--err" title={`${i.policy_id}: ${i.error}`}>
              {i.table} · {t('ret.errBadge')}
            </span>
          ))}
          {skipped.length > 0 && (
            <span className="ret-item-badge ret-item-badge--skip" title={skipped.map(i => `${i.policy_id}: ${i.skipped}`).join('\n')}>
              {t('ret.skipBadge', skipped.length)}
            </span>
          )}
        </td>
      </tr>
      {why && (
        <tr className="ret-why-zero-row"><td colSpan={7} className="ret-why-zero">{why}</td></tr>
      )}
    </>
  )
}
