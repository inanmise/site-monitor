import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { ArrowUp, ArrowDown, Inbox, Play, Eye, Bell, History, Pencil, Link2, Copy, Clock } from 'lucide-react'
import { api, formatDate } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import KebabMenu from './ui/KebabMenu.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import HelpTip from './ui/HelpTip.jsx'
import MonitorHowBox from './ui/MonitorHowBox.jsx'
import { LoadingBlock, ProgressBar } from './ui/Progress.jsx'
import { readPageSize, writePageSize } from '../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../hooks/useUrlQuerySync.js'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { copyText } from '../utils/copyText.js'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'
import CertTableToolbar from './certtable/CertTableToolbar.jsx'
import CertBulkBar from './certtable/CertBulkBar.jsx'
import CertFilterRow from './certtable/CertFilterRow.jsx'   // kolon süzgeç satırı (2026-09-22)
import { TABLE_COLUMNS, COLUMN_BY_KEY, STATUS_OPTIONS, EMPTY_FILTERS, LEVEL_CLASS, LEVEL_TEXT, URL_KEYS,
  readView, writeView, readPresets, writePresets, savePreset, readCols, writeCols, normalizeCols, csvColumnsFor,
  filtersFromUrl, toQuery, toUrlMapping, levelOf, trustOf, lifetimePct, isStale, relTime, shortFp } from './certtable/certTableModel.js'

export { TABLE_COLUMNS }

const LIST_KEY = 'certificates-table'
/** Ömür çubuğu dolgu tonu: seviye sınıfı → ProgressBar eşik sınıfı (.pg-bar--ok/warn/crit) */
const LIFE_TONE = { 'status-valid': 'pg-bar--ok', 'status-warning': 'pg-bar--warn', 'status-critical': 'pg-bar--crit', 'status-error': 'pg-bar--crit' }
const REFRESH_MS = 300_000

/**
 * Tüm Sertifikalar (2026-09-13 zenginleştirme): sunucu sayfalı liste + facet'li süzgeçler + URL eşitleme
 * (`c_*`) + başlıktan sıralama + satır seçimi/toplu işlem + satır menüsü + kayıtlı görünüm/ön ayarlar +
 * CSV + sessiz tazeleme (App'in 5 dk yenilemesi ve "Şimdi Kontrol Et" `refreshKey` ile buraya düşer;
 * eskiden tablo kendi state'inde bayat kalıyordu).
 *
 * props: onRowClick(domain, tab?), refreshKey, onCheckNow(domain), checkingDomain, onEdit(domain),
 *        canManage, globalAdmin, onRefresh()
 */
export default function CertificatesTable({ onRowClick, refreshKey, onCheckNow, checkingDomain, onEdit, canManage = false, globalAdmin = false, onRefresh }) {
  const t = useT()
  const toast = useToast()
  const defaultPerPage = readPageSize(LIST_KEY)

  const [filters, setFilters] = useState(() => {
    const f = filtersFromUrl(readUrlParam)
    // ?domain= (e-posta / palet bağlantısı) → alan süzgeci; URL'de durum yoksa kayıtlı görünümün durumu
    if (!f.domain) f.domain = readUrlParam('domain', '') || ''
    if (!readUrlParam(URL_KEYS.status, null)) { const v = readView()?.filterStatus; if (STATUS_OPTIONS.some((o) => o.value === v)) f.status = v }
    return f
  })
  const [queryFilters, setQueryFilters] = useState(filters)   // metin alanları 300 ms debounce'lu kopya
  const [sortBy, setSortBy] = useState(() => readUrlParam('c_sort', null) || readView()?.sortBy || 'priority|asc')
  const [page, setPage] = useState(() => readUrlInt('c_page', 1))
  const [perPage, setPerPage] = useState(() => readUrlInt('c_ps', null) || defaultPerPage)
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
  const [statusDropOpen, setStatusDropOpen] = useState(false)
  const statusDropRef = useRef(null)
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

  useEffect(() => {
    const onDown = (e) => { if (statusDropRef.current && !statusDropRef.current.contains(e.target)) setStatusDropOpen(false) }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [])

  const load = useCallback(async ({ silent = false } = {}) => {
    const seq = ++loadSeq.current
    if (!silent) setLoading(true)
    try {
      const data = await api.getCertificatesPaginated(toQuery(queryFilters, { page, perPage, sortBy }))
      if (seq !== loadSeq.current) return   // bayat yanıt
      if (data?.success) {
        setCerts(data.data || [])
        setPagination(data.pagination || { current_page: 1, total: 0, total_pages: 1 })
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
  }, [queryFilters, page, perPage, sortBy, t])
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

  useUrlQuerySync(toUrlMapping(filters, { page, perPage, sortBy, defaultPerPage }))

  // ── Süzgeç / sıralama / görünüm eylemleri ──
  function updateFilters(next) { setFilters(next); setPage(1); setSelected(new Set()) }
  function reset() {
    updateFilters({ ...EMPTY_FILTERS })
    setSortBy('priority|asc'); setPerPage(readPageSize(LIST_KEY)); setStatusDropOpen(false)
    writeView({ filterStatus: '', sortBy: 'priority|asc' })
  }
  function changeSort(v) { setSortBy(v); setPage(1); writeView({ sortBy: v }) }
  function headerSort(key) {
    const [k, d] = sortBy.split('|')
    changeSort(`${key}|${k === key && d === 'asc' ? 'desc' : 'asc'}`)
  }
  function changeCols(next) { const n = normalizeCols(next); setCols(n); writeCols(n) }
  function changeDensity(d) { setDensity(d); writeView({ density: d }) }
  function changeColFilters(v) { setColFilters(v); writeView({ colFilters: v }) }
  function selectStatus(val) { updateFilters({ ...filters, status: val }); setStatusDropOpen(false); writeView({ filterStatus: val }) }
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
    const all = certs.every((c) => s.has(c.domain))
    const n = new Set(s); certs.forEach((c) => (all ? n.delete(c.domain) : n.add(c.domain))); return n
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

  const exportUrl = api.certExportUrl(toQuery(queryFilters, { page, perPage, sortBy }), csvColumnsFor(cols))
  const activeStatusLabel = t(STATUS_OPTIONS.find((o) => o.value === filters.status)?.labelKey ?? 'tbl.filterAll')
  const [sortKey, sortDir] = sortBy.split('|')
  const p = pagination
  const hasFilters = Object.keys(EMPTY_FILTERS).some((k) => filters[k] !== EMPTY_FILTERS[k])
  const showSelect = true   // seçim: kontrol herkese açık (/check yalnız oturum ister); yönetim eylemleri rol kapılı
  const helpBullets = useMemo(() => [t('tbl.how1'), t('tbl.how2'), t('tbl.how3'), t('tbl.how4'), t('tbl.how5')], [t])

  function headerFor(key) {
    const c = COLUMN_BY_KEY[key]
    if (key === 'status') {
      return (
        <th key={key} data-col={key} aria-sort={sortKey === 'priority' ? (sortDir === 'desc' ? 'descending' : 'ascending') : 'none'}>
          <div className="cf-wrap" ref={statusDropRef} data-tour="ct-status">
            <button type="button" className={`cf-th-btn${filters.status ? ' cf-active' : ''}`} onClick={() => setStatusDropOpen((v) => !v)} title={t('tbl.filterTitle')}>
              {t('tbl.colStatus')}
              {filters.status && <span className="cf-active-label"> · {activeStatusLabel}</span>}
              <span className="cf-arrow">{statusDropOpen ? '▲' : '▼'}</span>
            </button>
            <HelpTip helpKey="tbl.help.status" label={t('tbl.colStatus')} />
            {statusDropOpen && (
              <div className="cf-menu">
                <div className="cf-menu-title">{t('tbl.filterTitle')}</div>
                {STATUS_OPTIONS.map((opt) => (
                  <div key={opt.value} role="button" tabIndex={0}
                    className={`cf-opt${opt.cls ? ' ' + opt.cls : ''}${filters.status === opt.value ? ' cf-opt-selected' : ''}`}
                    onClick={() => selectStatus(opt.value)} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectStatus(opt.value) } }}>
                    <span className="cf-opt-check">{filters.status === opt.value ? '✔' : ''}</span>
                    {opt.icon && <span className="cf-opt-icon">{opt.icon}</span>}
                    <span>{t(opt.labelKey)}</span>
                    {facets?.levels && opt.value && <span className="cf-opt-count">{facets.levels[opt.value] ?? 0}</span>}
                  </div>
                ))}
              </div>
            )}
          </div>
        </th>
      )
    }
    if (!c.sort) return <th key={key} data-col={key}>{t(c.labelKey)}</th>
    const on = sortKey === c.sort
    return (
      <th key={key} data-col={key} aria-sort={on ? (sortDir === 'desc' ? 'descending' : 'ascending') : 'none'}>
        <button type="button" className="inv-th-btn ct-th-btn" onClick={() => headerSort(c.sort)}>
          {t(c.labelKey)} {on ? (sortDir === 'desc' ? <ArrowDown size={11} /> : <ArrowUp size={11} />) : null}
        </button>
      </th>
    )
  }

  return (
    <div className={`ct-wrap${density === 'compact' ? ' is-compact' : ''}`}>
      <MonitorHowBox bullets={helpBullets} title={t('tbl.howTitle')} />
      <CertTableToolbar
        filters={filters} onFilter={updateFilters} onReset={reset} facets={facets}
        cols={cols} onCols={changeCols} density={density} onDensity={changeDensity}
        colFilters={colFilters} onColFilters={changeColFilters}
        sortBy={sortBy} onSort={changeSort} presets={presets} onSavePreset={savePresetNamed}
        onApplyPreset={applyPreset} onDeletePreset={deletePreset} exportUrl={exportUrl} total={p.total} teamNames={teamNamesRef.current} />

      <CertBulkBar selected={selected} rows={certs} cols={cols} shared={shared} canManage={canManage} globalAdmin={globalAdmin}
        onClear={() => setSelected(new Set())} onToggleAll={toggleAllPage} onDone={() => { onRefresh?.(); loadRef.current({ silent: true }) }} download={download} />

      {error && !certs.length ? (
        <StatusBlock tone="danger" icon={Inbox} title={t('tbl.loadError')} description={error}
          actions={<button type="button" className="btn btn-sm btn-secondary" onClick={() => load()}>{t('tbl.retry')}</button>} />
      ) : !loaded ? (
        <LoadingBlock label={t('tbl.loading')} fullWidth />
      ) : certs.length === 0 && !colFilters ? (
        /* Kolon süzgeç satırı açıkken tablo AYAKTA kalır (aşağıda tbody içinde "eşleşme yok" satırı):
           tabloyu kaldırmak süzgeç satırını da götürüyor, kullanıcı ne yazdığını göremiyor ve o hücreyi
           temizleyemiyordu. Envanterde aynı bug 3284c40e ile düzeltilmişti — kardeş yüzeye taşındı. */
        <StatusBlock tone="neutral" icon={Inbox} title={t('tbl.noCerts')} description={hasFilters ? t('empty.hintFilter') : t('empty.hintCerts')}
          actions={hasFilters ? <button type="button" className="btn btn-sm btn-secondary" onClick={reset}>{t('tbl.reset')}</button> : null} />
      ) : (
        <div className={`table-scroll ct-scroll${loading ? ' is-loading' : ''}`} aria-busy={loading}>
          {loading && <div className="ct-loading-line" aria-hidden="true" />}
          <table className="certificates-table ct-table">
            <thead>
              <tr>
                {showSelect && (
                  <th data-col="select" className="ct-td-select" data-tour="ct-select">
                    <input type="checkbox" aria-label={t('bulk.selectAll')} checked={certs.length > 0 && certs.every((c) => selected.has(c.domain))} onChange={toggleAllPage} />
                  </th>
                )}
                {cols.map(headerFor)}
                <th data-col="actions" className="ct-td-actions"><span className="sr-only">{t('tbl.actions')}</span></th>
              </tr>
              {colFilters && <CertFilterRow filters={filters} onFilter={updateFilters} cols={cols} facets={facets} teamNames={teamNamesRef.current} showSelect={showSelect} pageRows={certs} />}
            </thead>
            <tbody>
              {certs.map((cert, i) => (
                <TableRow key={cert.domain} cert={cert} cols={cols} shared={shared[cert.domain] ?? 1} tourId={i === 0 ? 'ct-row-menu' : undefined}
                  selected={selected.has(cert.domain)} onToggle={showSelect ? toggleSel : null}
                  onOpen={onRowClick} onCheckNow={onCheckNow} checking={checkingDomain === cert.domain}
                  onEdit={canManage ? onEdit : null} onCopyLink={copyRowLink}
                  onSameCert={(fp) => updateFilters({ ...filters, fp })} />
              ))}
              {certs.length === 0 && (
                <tr className="inv-row-empty">
                  <td colSpan={99}>
                    <div className="inv-empty-inline">
                      <Inbox size={14} />
                      <span>{t('tbl.noMatch')}</span>
                      {hasFilters && <button type="button" className="btn btn-sm btn-secondary" onClick={reset}>{t('tbl.reset')}</button>}
                    </div>
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      {error && certs.length > 0 && <div className="ct-error-line" role="status">{t('tbl.refreshFailed')}</div>}

      <PaginationBar
        page={p.current_page} totalPages={p.total_pages} totalItems={p.total}
        rangeStart={p.total === 0 ? 0 : (p.current_page - 1) * perPage + 1}
        rangeEnd={Math.min(p.current_page * perPage, p.total)}
        pageSize={perPage}
        onPageChange={(n) => { setPage(n); setSelected(new Set()) }}
        onPageSizeChange={(n) => { setPerPage(n); setPage(1); writePageSize(LIST_KEY, n); writeView({ perPage: n }) }}
      />
    </div>
  )
}

function TableRow({ cert, cols, shared, selected, onToggle, onOpen, onCheckNow, checking, onEdit, onCopyLink, onSameCert, tourId }) {
  const t = useT()
  const level = levelOf(cert)
  const statusClass = LEVEL_CLASS[level] ?? 'status-valid'
  const statusText = t(LEVEL_TEXT[level] ?? 'tbl.statusValid')
  const days = cert.days_remaining
  const life = lifetimePct(cert)
  const stale = isStale(cert)
  const trust = trustOf(cert)
  const rel = relTime(cert.checked_at)
  const sanList = Array.isArray(cert.san) ? cert.san : []
  const stop = (e) => e.stopPropagation()

  const menu = [
    { label: t('tbl.actDetail'), icon: <Eye size={13} />, onClick: () => onOpen(cert.domain) },
    onCheckNow ? { label: checking ? t('tbl.actChecking') : t('tbl.actCheck'), icon: <Play size={13} />, onClick: () => { if (!checking) onCheckNow(cert.domain) } } : null,
    { label: t('tbl.actAlerts'), icon: <Bell size={13} />, onClick: () => onOpen(cert.domain, 'alerts') },
    { label: t('tbl.actHistory'), icon: <History size={13} />, onClick: () => onOpen(cert.domain, 'history') },
    onEdit ? { label: t('tbl.actEdit'), icon: <Pencil size={13} />, onClick: () => onEdit(cert.domain) } : null,
    shared > 1 && cert.fingerprint ? { label: t('tbl.actSameCert', shared), icon: <Copy size={13} />, onClick: () => onSameCert(cert.fingerprint) } : null,
    { label: t('share.copyLink'), icon: <Link2 size={13} />, onClick: () => onCopyLink(cert.domain) },
  ].filter(Boolean)

  const cell = (key) => {
    const label = t(COLUMN_BY_KEY[key].labelKey)
    switch (key) {
      case 'domain': return (
        <td key={key} data-label={label} className="ct-td-domain">
          <strong>{cert.domain}</strong>
          {cert.tier && <span className={`cc-tier cc-tier-${cert.tier} ct-tier`}>T{cert.tier}</span>}
          {shared > 1 && (
            <button type="button" className="ct-shared" onClick={(e) => { stop(e); onSameCert(cert.fingerprint) }} title={t('tbl.sharedTitle', shared)}>×{shared}</button>
          )}
        </td>)
      case 'issuer': return <td key={key} data-label={label}>{cert.issuer_cn || cert.issuer || 'N/A'}</td>
      case 'subject': return <td key={key} data-label={label}>{cert.subject || 'N/A'}</td>
      case 'team': return <td key={key} data-label={label}>{cert.team_name ? <TeamBadge teamId={cert.team_id} teamName={cert.team_name} /> : '—'}</td>
      case 'expiry': return <td key={key} data-label={label}>{formatDate(cert.not_after)}</td>
      case 'days': return (
        <td key={key} data-label={label} className="ct-td-days">
          <strong>{days ?? 'N/A'}</strong>
          {life != null && (
            <span className="ct-life" title={t('tbl.lifeTitle', life)}>
              <ProgressBar value={life} size="sm" decorative className={LIFE_TONE[statusClass] || 'pg-bar--ok'} />
            </span>
          )}
        </td>)
      case 'status': return (
        <td key={key} data-label={label}>
          <span className={`table-status ${statusClass}`}></span>{statusText}
          {isInsecure(cert) && <span className="cc-pill cc-pill-error ct-pill" title={securityTitle(cert, t)}>{t('cert.sec.insecure')}</span>}
        </td>)
      case 'trust': return (
        <td key={key} data-label={label}>
          <span className={`ct-trust ct-trust--${trust.tone}`} title={trust.issues.map((i) => t(`tbl.trust.${i}`)).join(' · ') || t(`tbl.trust.${trust.tone}`)}>
            {trust.tone === 'bad' ? trust.issues.map((i) => t(`tbl.trust.${i}`)).join(', ') : t(`tbl.trust.${trust.tone}`)}
          </span>
        </td>)
      case 'san': return <td key={key} data-label={label} title={sanList.join('\n')}>{sanList.length}</td>
      case 'shared': return <td key={key} data-label={label}>{shared > 1 ? <button type="button" className="ct-shared" onClick={(e) => { stop(e); onSameCert(cert.fingerprint) }}>×{shared}</button> : '—'}</td>
      case 'key': return <td key={key} data-label={label} className="wa-mono">{cert.public_key_algorithm ? `${cert.public_key_algorithm}${cert.public_key_size ? ' ' + cert.public_key_size : ''}` : '—'}</td>
      case 'signature': return <td key={key} data-label={label} className="wa-mono">{cert.signature_algorithm || '—'}</td>
      case 'port': return <td key={key} data-label={label}>{cert.port ?? 443}</td>
      case 'tier': return <td key={key} data-label={label}>{cert.tier ? `T${cert.tier}` : '—'}</td>
      case 'via': return <td key={key} data-label={label}>{cert.via === 'proxy' ? t('card.viaProxy') : cert.via ? t('card.viaDirect') : '—'}</td>
      case 'tls': return <td key={key} data-label={label} className="wa-mono">{cert.tls_mode_used || '—'}</td>
      case 'intermediate': return (
        <td key={key} data-label={label} className={cert.intermediate_days_remaining != null && cert.intermediate_days_remaining < (days ?? Infinity) ? 'ct-warn-cell' : ''}>
          {cert.intermediate_days_remaining ?? '—'}
        </td>)
      case 'notBefore': return <td key={key} data-label={label}>{formatDate(cert.not_before)}</td>
      case 'fingerprint': return <td key={key} data-label={label} className="wa-mono" title={cert.fingerprint || ''}>{shortFp(cert.fingerprint) || '—'}</td>
      case 'serial': return <td key={key} data-label={label} className="wa-mono" title={cert.serial_number || ''}>{shortFp(cert.serial_number) || '—'}</td>
      case 'checked': return (
        <td key={key} data-label={label} title={formatDate(cert.checked_at)}>
          {rel ? t(`tbl.rel.${rel.unit}`, rel.n) : formatDate(cert.checked_at)}
          {stale && <span className="ct-stale" title={t('tbl.staleTitle', cert.check_interval_hours || 1)}><Clock size={11} /> {t('tbl.stale')}</span>}
        </td>)
      default: return <td key={key} data-label={label} />
    }
  }

  return (
    <tr data-domain={cert.domain} className={selected ? 'is-selected' : ''} tabIndex={0}
      onClick={() => onOpen(cert.domain)}
      onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); onOpen(cert.domain) } }}>
      {onToggle && (
        <td className="ct-td-select" onClick={stop}>
          <input type="checkbox" checked={selected} onChange={() => onToggle(cert.domain)} aria-label={t('bulk.selectOne')} />
        </td>
      )}
      {cols.map(cell)}
      <td className="ct-td-actions" onClick={stop} onKeyDown={stop} data-tour={tourId}>
        <KebabMenu items={menu} label={t('tbl.actions')} />
      </td>
    </tr>
  )
}
