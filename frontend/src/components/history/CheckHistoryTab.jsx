import { useEffect, useMemo, useState } from 'react'
import { BellRing, BellOff, Calendar, Download } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { api, formatDateSec, formatDateOnly } from '../../api/client'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import DateTimeRangePicker from '../ui/DateTimeRangePicker.jsx'
import DensityStrip from './DensityStrip.jsx'
import useCheckHistory from './useCheckHistory.js'
import useUrlQuerySync from '../../hooks/useUrlQuerySync.js'
import { LoadingBlock } from '../ui/Progress.jsx'

/**
 * Kontrol Geçmişi v2 — TÜM izleme türlerinin paylaşılan geçmiş sekmesi.
 * Eski desen (7 sayfada kopyalanan range-buton + client-side sayfalama + 500 kayıt tavanı) yerine:
 * segmented aralık + özel tarih/saat aralığı + durum filtresi chip'leri + yoğunluk şeridi (tıkla→zoom)
 * + gün ayırıcıları + çıkarımsal alarm işaret satırları + server-side sayfalama + CSV + canlı yenileme.
 *
 * Tip-özel olan yalnız üç şey dışarıdan gelir: kolon başlıkları (columns), satır hücreleri (renderRow)
 * ve grid sınıfı (gridClass — örn. domain'in 7 kolonlu 'dom-rt-grid'i).
 */
export default function CheckHistoryTab({
  kind, monitorId, listKey,
  presets = [1, 7, 15, 30], defaultPreset = 1,
  filterMode = 'fail',                       // 'fail' | 'changed' | 'none'
  columns = [], gridClass = '', renderRow,
  extraParams = null,                        // uptime-http: { port }
  csv = true, live = true, urlSync = true,
  onCounts = null,                           // modal başlık özeti için {total, fail} bildirimi
  range = null, onRangeChange = null,        // kontrollü aralık (Uptime: tek picker iki kolonu sürer)
}) {
  const t = useT()
  const h = useCheckHistory({ kind, id: monitorId, listKey, presets, defaultPreset, filterMode, extraParams,
    live: range ? false : live, fixed: range })
  const [showPicker, setShowPicker] = useState(false)
  const fixedMode = !!range

  useEffect(() => { if (h.counts) onCounts?.(h.counts) },   // sayfa üstbilgisi (%OK / toplam / hata)
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [h.counts?.total, h.counts?.fail])

  // ── URL senkronu: range + hfrom/hto/hst (paylaşılabilir link) — modal kapanınca temizlenir ──
  useUrlQuerySync({
    range: fixedMode || h.preset === defaultPreset ? null : String(h.preset),
    hfrom: !fixedMode && h.preset === 'custom' && h.customFrom ? h.customFrom.toISOString().slice(0, 19) : null,
    hto:   !fixedMode && h.preset === 'custom' && h.customTo   ? h.customTo.toISOString().slice(0, 19)   : null,
    hst:   h.status === 'all' ? null : h.status,
  }, { enabled: urlSync && !fixedMode })
  useEffect(() => () => {
    if (!urlSync) return
    try {   // unmount: bu bileşenin paramları URL'de kalmasın (sekme-değişimi temizliğini beklemeden)
      const url = new URL(window.location.href)
      let changed = false
      for (const k of ['range', 'hfrom', 'hto', 'hst']) {
        if (url.searchParams.has(k)) { url.searchParams.delete(k); changed = true }
      }
      if (changed) {
        const qs = url.searchParams.toString()
        window.history.replaceState(window.history.state, '', url.pathname + (qs ? `?${qs}` : '') + url.hash)
      }
    } catch { /* en iyi çaba */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── Retention/clamp bildirimi: istenen from, dönen range.from'dan gerideyse kırpılmıştır ──
  const clampedFrom = useMemo(() => {
    if (!h.range?.from) return null
    let requested
    if (h.preset === 'custom' && h.customFrom) requested = h.customFrom.toISOString().slice(0, 19)
    else if (typeof h.preset === 'number') {
      requested = new Date(Date.now() - h.preset * 86400000).toISOString().slice(0, 19)
    } else return null
    // 2 saatlik tolerans — saat farkları/istek gecikmesi sahte uyarı üretmesin
    return (h.range.from.localeCompare(requested) > 0
        && (new Date(h.range.from + 'Z') - new Date(requested + 'Z')) > 2 * 3600000)
      ? h.range.from : null
  }, [h.range, h.preset, h.customFrom])

  // ── Sayfa satırları: gün ayırıcıları + alarm işaret satırları serpiştirilmiş ──
  const rows = useMemo(() => {
    const items = h.items
    const events = []
    for (const a of h.alerts) {
      if (a.created_at) events.push({ ts: a.created_at, ev: 'triggered', a })
      if (a.resolved_at) events.push({ ts: a.resolved_at, ev: 'resolved', a })
    }
    events.sort((x, y) => String(y.ts).localeCompare(String(x.ts)))
    const out = []
    let ei = 0
    let lastDay = null
    const itemTs = (c) => c.checked_at || c.checkedAt || ''
    // Sayfa penceresi dışındaki işaretler atlanır (başka sayfaya aittir); 1. sayfada en-üst pencere açık.
    if (items.length > 0 && h.page > 1) {
      while (ei < events.length && String(events[ei].ts).localeCompare(itemTs(items[0])) > 0) ei++
    }
    items.forEach((c, i) => {
      while (ei < events.length && String(events[ei].ts).localeCompare(itemTs(c)) >= 0) {
        out.push({ type: events[ei].ev, key: `ev-${events[ei].a.id}-${events[ei].ev}`, alert: events[ei].a, ts: events[ei].ts })
        ei++
      }
      const day = itemTs(c).slice(0, 10)
      if (day && day !== lastDay) { out.push({ type: 'day', key: `day-${day}`, day }); lastDay = day }
      out.push({ type: 'item', key: `it-${itemTs(c)}#${i}`, item: c, index: i })
    })
    return out
  }, [h.items, h.alerts, h.page])

  const totalPages = Math.max(1, Math.ceil(h.total / h.pageSize))
  const rangeStart = h.total === 0 ? 0 : (h.page - 1) * h.pageSize + 1
  const rangeEnd = Math.min(h.page * h.pageSize, h.total)

  const presetOptions = [
    ...presets.map(d => ({ value: d, label: t(`hist.range${d}d`) })),
    { value: 'custom', label: t('hist.rangeCustom'), icon: Calendar },
  ]

  const filterChips = filterMode !== 'none' && (
    <div className="hist-chips" role="group" aria-label={t('hist.filterLabel')}>
      <button type="button" className={`hist-chip${h.status === 'all' ? ' active' : ''}`}
        onClick={() => h.setStatus('all')}>
        {t('hist.filterAll')}<span className="hist-chip-count">{Number(h.counts.total).toLocaleString()}</span>
      </button>
      <button type="button"
        className={`hist-chip hist-chip--fail${h.status !== 'all' ? ' active' : ''}`}
        onClick={() => h.setStatus(filterMode)}>
        {t(filterMode === 'changed' ? 'hist.filterChanged' : 'hist.filterFail')}
        <span className="hist-chip-count">{Number(h.counts.fail).toLocaleString()}</span>
      </button>
    </div>
  )

  return (
    <div className="hist-root">
      <div className="hist-toolbar">
        {!fixedMode && (
          <SegmentedControl ariaLabel={t('hist.rangeLabel')} options={presetOptions}
            value={h.preset} onChange={(v) => { h.setPreset(v); setShowPicker(v === 'custom') }} />
        )}
        {filterChips}
        <span className="hist-toolbar-spacer" />
        {h.liveActive && <span className="hist-live" title={t('hist.liveHint')}><span className="hist-live-dot" />{t('hist.live')}</span>}
        {csv && h.total > 0 && (
          <a className="hist-csv-btn" href={api.monitoring.getCheckHistoryCsvUrl(kind, monitorId, h.csvParams)}
            download title={t('hist.exportCsvHint')}>
            <Download size={13} /> CSV
          </a>
        )}
      </div>

      {!fixedMode && (h.preset === 'custom' && (showPicker || !h.customFrom)) && (
        <DateTimeRangePicker
          from={h.customFrom ?? new Date(Date.now() - 86400000)}
          to={h.customTo ?? new Date()}
          onApply={(f, to) => { h.setCustomRange(f, to); setShowPicker(false) }} />
      )}

      {clampedFrom && (
        <div className="hist-clamp-note">{t('hist.clampedNotice', formatDateSec(clampedFrom))}</div>
      )}

      {/* Saklama şeffaflığı: veri hangi tarihten beri tutuluyor, elde en yeni kayıt hangisi.
          Süre Ayarlar → Veri Saklama'dan değişince bu satır anında güncellenir. */}
      {h.oldestAt && (
        <div className="hist-retention-note">
          {t('hist.retentionInfo', formatDateSec(h.oldestAt), h.retentionDays ?? '—',
             h.newestAt ? formatDateSec(h.newestAt) : '—')}
        </div>
      )}

      <DensityStrip buckets={h.buckets} zoomed={!fixedMode && h.preset === 'custom'}
        onZoom={(fromIso, toIso) => {
          if (fixedMode) { onRangeChange?.(new Date(fromIso + 'Z'), new Date(toIso + 'Z')); return }
          h.setCustomRange(new Date(fromIso + 'Z'), new Date(toIso + 'Z')); setShowPicker(false)
        }}
        onReset={() => h.setPreset(defaultPreset)} />

      {h.loading && h.items.length === 0 ? (
        <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
      ) : h.items.length === 0 ? (
        <LoadingBlock label={t('hist.noData')} className="upt-modal-loading" />
      ) : (
        <div className="upt-rt-list hist-list">
          <div className={`upt-rt-grid upt-rt-head ${gridClass}`}>
            {columns.map((c, i) => <span key={i}>{c}</span>)}
          </div>
          {rows.map(r => {
            if (r.type === 'day') {
              return <div key={r.key} className="hist-day-sep">{formatDateOnly(r.day + 'T00:00:00')}</div>
            }
            if (r.type === 'triggered' || r.type === 'resolved') {
              const a = r.alert
              const triggered = r.type === 'triggered'
              return (
                <div key={r.key}
                  className={`hist-alert-row ${triggered ? 'hist-alert-row--triggered' : 'hist-alert-row--resolved'}`}
                  title={a.message || ''}>
                  {triggered ? <BellRing size={13} /> : <BellOff size={13} />}
                  <span className="hist-alert-text">
                    {t(triggered ? 'hist.alertTriggered' : 'hist.alertResolved')}
                    <span className="hist-alert-meta"> · {a.alert_type}{a.alert_level ? ` · ${a.alert_level}` : ''}</span>
                  </span>
                  <span className="hist-alert-time">{formatDateSec(r.ts)}</span>
                </div>
              )
            }
            return (
              <div key={r.key} className={`upt-rt-grid ${gridClass}`}>
                {renderRow(r.item, { index: r.index })}
              </div>
            )
          })}
          <PaginationBar compact
            page={h.page} totalPages={totalPages} totalItems={h.total}
            rangeStart={rangeStart} rangeEnd={rangeEnd}
            pageSize={h.pageSize}
            onPageChange={h.setPage} onPageSizeChange={h.setPageSize} />
        </div>
      )}
    </div>
  )
}
