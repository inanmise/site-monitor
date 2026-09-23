import { LoadingBlock } from './ui/Progress.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { createPortal } from 'react-dom'
import { api } from '../api/client'
import { useT, useDateLocale } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { rcMeta, durationMs, formatDuration, formatIncidentTime } from '../utils/incidentMeta.js'
import { Siren, RefreshCw, Trash2, MessageSquare, X, ExternalLink,
  CheckCircle2, Send, Info, ArrowUp, ArrowDown } from 'lucide-react'

const BANNER_KEY = 'incidents-banner-dismissed'
const SORTABLE = { started: 'started', status: 'status', severity: 'severity', rootCause: 'type' }

export default function IncidentsPage({ systemRole }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const isAdmin = systemRole === 'ADMIN'

  const [rows, setRows] = useState([])
  const [total, setTotal] = useState(0)
  const [typeCounts, setTypeCounts] = useState({})
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [filters, setFilters] = useState({ status: 'all', rootCause: '', q: '', since: '', until: '' })
  const [sort, setSort] = useState({ by: '', dir: 'desc' })   // by='' → sunucu varsayılanı (ongoing-first)
  const [nowMs, setNowMs] = useState(Date.now())
  const [commentsFor, setCommentsFor] = useState(null)        // açık yorum modalı için incident
  const [highlightId, setHighlightId] = useState(null)        // e-posta derin linkiyle gelen olay
  const [bannerOpen, setBannerOpen] = useState(() => {
    try { return localStorage.getItem(BANNER_KEY) !== 'true' } catch { return true }
  })
  const searchRef = useRef(null)
  const reqIdRef = useRef(0)

  const load = useCallback(async () => {
    const myId = ++reqIdRef.current
    setLoading(true)
    try {
      const res = await api.monitoring.incidents.list({
        status: filters.status === 'all' ? '' : filters.status,
        rootCause: filters.rootCause, q: filters.q, since: filters.since, until: filters.until,
        sort: sort.by || undefined, dir: sort.dir, page, size,
      })
      if (myId !== reqIdRef.current) return   // yalnız EN SON isteğin yanıtını uygula — bayat yanıt grid'i ezmesin (M4)
      if (res?.success) { setRows(res.data ?? []); setTotal(res.total ?? 0); setTypeCounts(res.type_counts ?? {}) }
    } finally {
      setLoading(false)
    }
  }, [filters, sort, page, size])

  useEffect(() => { load() }, [load])

  // E-postadaki "Olay detayını görüntüle / Olaya yorum yap" derin linkleri:
  // ?incident=<id>[&action=comment]. Olay sayfalı listede olmayabilir → tekil uçtan çekilir.
  // action tüketildikten sonra URL'den silinir (PAGE_STATE_PARAMS'ta değil; kalsaydı modal
  // her sekme geçişinde yeniden açılırdı).
  useEffect(() => {
    let id = null, action = null
    try {
      const p = new URLSearchParams(window.location.search)
      id = p.get('incident')
      action = p.get('action')
    } catch { /* yoksay */ }
    if (!id) return
    let alive = true
    api.monitoring.incidents.get(id).then(res => {
      if (!alive) return
      if (res?.success && res.data) {
        setHighlightId(String(res.data.id))
        if (action === 'comment') setCommentsFor(res.data)
      } else if (res && res.success === false) {
        toast.error(res.error || t('inc.deepLinkFail'))
      }
      try {
        const url = new URL(window.location.href)
        url.searchParams.delete('action')
        window.history.replaceState({}, '', url)
      } catch { /* yoksay */ }
    })
    return () => { alive = false }
  }, [])   // yalnız ilk açılış — sonraki gezinmelerde param zaten temizlenmiş olur

  // Filtre/boyut değişince sayfayı 1'e al — page ile AYNI güncellemede (ayrı effect + çift yükleme/bayat-yarış yerine, M4).
  const patchFilters = useCallback((patch) => { setFilters(f => ({ ...f, ...patch })); setPage(0) }, [])
  const resetFilters = useCallback(() => { setFilters({ status: 'all', rootCause: '', q: '', since: '', until: '' }); setPage(0) }, [])
  const changeSize   = useCallback((n) => { setSize(n); setPage(0) }, [])
  // Ongoing süreleri canlı tutmak için 1sn tick (yalnız ongoing satır varsa).
  useEffect(() => {
    if (!rows.some(r => r.status === 'ongoing')) return
    const i = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(i)
  }, [rows])

  const totalPages = Math.max(1, Math.ceil(total / size))
  const dismissBanner = () => { setBannerOpen(false); try { localStorage.setItem(BANNER_KEY, 'true') } catch { /* yoksay */ } }

  function toggleSort(col) {
    const key = SORTABLE[col]
    setSort(s => s.by === key ? { by: key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { by: key, dir: 'desc' })
  }
  /** Ekran okuyucuya siralama YONU: gorsel ok (sortIcon) tek basina bu bilgiyi tasimiyordu. */
  const ariaSort = (col) => (sort.by === SORTABLE[col]
    ? (sort.dir === 'asc' ? 'ascending' : 'descending')
    : 'none')
  const sortIcon = (col) => sort.by === SORTABLE[col]
    ? (sort.dir === 'asc' ? <ArrowUp size={12} /> : <ArrowDown size={12} />) : null

  // Root-cause filtre pill'leri — type_counts'tan (canlı sayı). "Tümü" + tipler.
  const pills = useMemo(() => {
    const entries = Object.entries(typeCounts).sort((a, b) => b[1] - a[1])
    return entries
  }, [typeCounts])

  async function del(inc) {
    if (!await showConfirm({
      title: t('incov.delete'), message: t('incov.deleteConfirm'),
      confirmText: t('incov.delete'), variant: 'danger',
    })) return
    const res = await api.monitoring.incidents.remove(inc.id)
    if (!res?.success) { toast.error(res?.error || t('incov.deleteError')); return }
    toast.success(t('incov.deleted'))
    load()
  }

  // Olay → kaynağına git: monitör alarmı ise ilgili monitör sekmesi + odak (?monitor=);
  // cert alarmı (monitor_id yok) ise pano, o domaine filtreli (?domain=). Böylece "nerede kontrol ediliyor" belli olur.
  function incidentHref(inc) {
    const m = inc.monitor
    if (m?.monitor_id != null && m?.tab) return `?tab=${encodeURIComponent(m.tab)}&monitor=${encodeURIComponent(m.monitor_id)}`
    const dom = inc.domain || m?.name || ''
    return `?tab=${encodeURIComponent(m?.tab || 'dashboard')}${dom ? `&domain=${encodeURIComponent(dom)}` : ''}`
  }
  function goToIncident(inc) { window.location.assign(incidentHref(inc)) }

  function monitorCell(m) {
    if (!m) return <span className="inc-mon-name">—</span>
    const name = m.name || '—'
    if (m.monitor_id != null && m.tab) {
      const href = `?tab=${encodeURIComponent(m.tab)}&monitor=${encodeURIComponent(m.monitor_id)}`
      return <a className="inc-mon-link" href={href} title={name} onClick={e => e.stopPropagation()}>{name} <ExternalLink size={11} /></a>
    }
    return <span className="inc-mon-name" title={name}>{name}</span>
  }

  function rootCauseCell(rc) {
    if (!rc) return null
    const meta = rcMeta(rc.category)
    return (
      <span className="inc-rc">
        <span className="inc-rc-code" style={{ background: meta.color }}>{rc.code}</span>
        <span className="inc-rc-label">{t(meta.key)}</span>
      </span>
    )
  }

  const hasFilters = filters.status !== 'all' || filters.rootCause || filters.q || filters.since || filters.until

  return (
    <div className="inc-page">
      <div className="upt-header">
        <div>
          <h2 className="upt-title"><Siren size={20} style={{ verticalAlign: '-4px', marginRight: 6 }} />{t('incov.title')}</h2>
          <p className="upt-subtitle">{t('incov.subtitle')}</p>
        </div>
        <div className="upt-header-right">
          <button className="btn btn-sm upt-refresh-btn" onClick={load}><RefreshCw size={14} />{t('incov.refresh')}</button>
        </div>
      </div>

      {bannerOpen && (
        <div className="inc-banner" role="note">
          <Info size={18} />
          <div className="inc-banner-text">
            <strong>{t('incov.bannerTitle')}</strong>
            <span>{t('incov.bannerText')}</span>
          </div>
          <button className="inc-banner-close" onClick={dismissBanner} title={t('incov.dismiss')} aria-label={t('incov.dismiss')}><X size={16} /></button>
        </div>
      )}

      {/* Filtreler */}
      <div className="inc-filters">
        <select className="filter-select" value={filters.status} onChange={e => patchFilters({ status: e.target.value })}>
          <option value="all">{t('incov.statusAll')}</option>
          <option value="ongoing">{t('incov.ongoing')}</option>
          <option value="resolved">{t('incov.resolved')}</option>
        </select>
        <input ref={searchRef} className="filter-input" type="text" placeholder={t('incov.searchPlaceholder')}
          defaultValue={filters.q}
          onKeyDown={e => { if (e.key === 'Enter') patchFilters({ q: e.target.value.trim() }) }}
          onBlur={e => { const v = e.target.value.trim(); if (v !== filters.q) patchFilters({ q: v }) }} />
        <input className="filter-input inc-date" type="date" value={filters.since} title={t('incov.since')}
          onChange={e => patchFilters({ since: e.target.value })} />
        <input className="filter-input inc-date" type="date" value={filters.until} title={t('incov.until')}
          onChange={e => patchFilters({ until: e.target.value })} />
        {hasFilters && (
          <button className="btn btn-sm btn-secondary" onClick={() => { resetFilters(); if (searchRef.current) searchRef.current.value = '' }}>
            {t('incov.clearFilters')}
          </button>
        )}
      </div>

      {/* Root-cause pill'leri */}
      {pills.length > 0 && (
        <div className="inc-pills">
          <button className={`inc-pill${!filters.rootCause ? ' is-active' : ''}`} onClick={() => patchFilters({ rootCause: '' })}>
            {t('incov.allCauses')} <span className="inc-pill-count">{total}</span>
          </button>
          {pills.map(([type, count]) => (
            <button key={type} className={`inc-pill${filters.rootCause === type ? ' is-active' : ''}`}
              onClick={() => patchFilters({ rootCause: filters.rootCause === type ? '' : type })}>
              {t(`incov.type.${type}`) !== `incov.type.${type}` ? t(`incov.type.${type}`) : type} <span className="inc-pill-count">{count}</span>
            </button>
          ))}
        </div>
      )}

      {loading ? (
        <LoadingBlock label={t('tbl.loading')} fullWidth />
      ) : rows.length === 0 ? (
        <div className="inc-empty">
          <div className="inc-empty-art"><Siren size={54} /></div>
          <h3 className="inc-empty-title">{t('incov.emptyTitle')}</h3>
          <p className="inc-empty-text">{hasFilters ? t('incov.emptyFiltered') : t('incov.emptyText')}</p>
        </div>
      ) : (
        <>
          <div className="admin-table-wrap">
            <table className="admin-table inc-table">
              <thead>
                <tr>
                  <th className="inc-th-sort" tabIndex={0} aria-sort={ariaSort('status')}
                      onClick={() => toggleSort('status')}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSort('status') } }}>{t('incov.colStatus')} {sortIcon('status')}</th>
                  <th>{t('incov.colMonitor')}</th>
                  <th>{t('incov.colTeam')}</th>
                  <th className="inc-th-sort" tabIndex={0} aria-sort={ariaSort('rootCause')}
                      onClick={() => toggleSort('rootCause')}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSort('rootCause') } }}>{t('incov.colRootCause')} {sortIcon('rootCause')}</th>
                  <th>{t('incov.colComments')}</th>
                  <th className="inc-th-sort" tabIndex={0} aria-sort={ariaSort('started')}
                      onClick={() => toggleSort('started')}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleSort('started') } }}>{t('incov.colStarted')} {sortIcon('started')}</th>
                  <th>{t('incov.colDuration')}</th>
                  <th className="inc-th-actions">{t('incov.colActions')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(inc => (
                  <tr key={inc.id}
                    className={`inc-row-link${inc.status === 'ongoing' ? ' inc-row-ongoing' : ''}`
                      + (highlightId === String(inc.id) ? ' inc-row-linked' : '')}
                    onClick={() => goToIncident(inc)}
                    role="link" tabIndex={0}
                    onKeyDown={e => { if (e.key === 'Enter') goToIncident(inc) }}
                    title={t('incov.openDetail')}>
                    <td>
                      {inc.status === 'ongoing'
                        ? <span className="inc-status inc-status--ongoing"><span className="inc-dot" />{t('incov.ongoing')}</span>
                        : <span className="inc-status inc-status--resolved"><CheckCircle2 size={14} />{t('incov.resolved')}</span>}
                    </td>
                    <td>{monitorCell(inc.monitor)}</td>
                    {/* Takım (2026-09-18): rozet tıklanınca üye modali; satır tıklaması detaya gitmesin */}
                    <td onClick={e => e.stopPropagation()}>{(inc.team_name || inc.team_id != null) ? <TeamBadge teamId={inc.team_id} teamName={inc.team_name} /> : <span className="inv-muted">—</span>}</td>
                    <td>{rootCauseCell(inc.root_cause)}</td>
                    <td>
                      <button className="inc-comments-btn" onClick={e => { e.stopPropagation(); setCommentsFor(inc) }}>
                        <MessageSquare size={13} />{t('incov.comments', inc.comment_count ?? 0)}
                      </button>
                    </td>
                    <td className="inc-started" title={inc.started_at}>{formatIncidentTime(inc.started_at, dateLocale)}</td>
                    <td className="inc-duration">
                      {formatDuration(durationMs(inc.started_at, inc.resolved_at, nowMs), t)}
                      {inc.status === 'resolved' && inc.resolved_at && (
                        <span className="inc-resolved-at" title={inc.resolved_at}>
                          {t('incov.resolvedAt')} {formatIncidentTime(inc.resolved_at, dateLocale)}
                        </span>
                      )}
                    </td>
                    <td className="inc-th-actions">
                      {isAdmin && (
                        <button className="inc-del-btn" title={t('incov.delete')} onClick={e => { e.stopPropagation(); del(inc) }}><Trash2 size={13} /></button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Standart sayfalama çubuğu (2026-09-12, #17): sayfaya git + boyut seçici, diğer listelerle aynı */}
          <PaginationBar page={page + 1} totalPages={totalPages} totalItems={total}
            rangeStart={total ? page * size + 1 : 0} rangeEnd={Math.min(total, (page + 1) * size)}
            pageSize={size} sizeOptions={[10, 20, 50, 100]}
            onPageChange={(p) => setPage(p - 1)} onPageSizeChange={(n) => changeSize(Number(n))} />
        </>
      )}

      {commentsFor && createPortal(
        <CommentThread incident={commentsFor} onClose={() => setCommentsFor(null)}
          onChanged={(delta) => {
            setRows(rs => rs.map(r => r.id === commentsFor.id ? { ...r, comment_count: Math.max(0, (r.comment_count ?? 0) + delta) } : r))
          }} />,
        document.body,
      )}
    </div>
  )
}

// ── Yorum dizisi modalı ──────────────────────────────────────────────────────
function CommentThread({ incident, onClose, onChanged }) {
  const t = useT()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const [comments, setComments] = useState([])
  const [loading, setLoading] = useState(true)
  const [body, setBody] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await api.monitoring.incidents.comments(incident.id)
      if (res?.success) setComments(res.data ?? [])
    } finally {
      setLoading(false)
    }
  }, [incident.id])
  useEffect(() => { load() }, [load])

  async function add() {
    const text = body.trim()
    if (!text) return
    setSaving(true)
    try {
      const res = await api.monitoring.incidents.addComment(incident.id, text)
      if (!res?.success) { toast.error(res?.error || t('incov.commentError')); return }
      setBody(''); onChanged?.(1); load()
    } finally {
      setSaving(false)
    }
  }
  async function remove(id) {
    const res = await api.monitoring.incidents.deleteComment(id)
    if (!res?.success) { toast.error(res?.error || t('incov.commentError')); return }
    onChanged?.(-1); load()
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box inc-cmt-modal" onClick={e => e.stopPropagation()} style={{ maxWidth: 560, width: '92vw' }}>
        <div className="modal-icon-hdr modal-icon-hdr--port">
          <div className="modal-icon-hdr-badge"><MessageSquare size={20} /></div>
          <h3>{t('incov.commentsTitle')}</h3>
        </div>
        <div className="inc-cmt-sub">{incident.monitor?.name || incident.domain}</div>

        <div className="inc-cmt-list">
          {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth />
            : comments.length === 0 ? <div className="inc-cmt-empty">{t('incov.noComments')}</div>
            : comments.map(c => (
              <div key={c.id} className="inc-cmt">
                <div className="inc-cmt-head">
                  <span className="inc-cmt-author">{c.author_name || c.author_username || '—'}</span>
                  <span className="inc-cmt-time">{formatIncidentTime(c.created_at, dateLocale)}</span>
                  <button className="inc-cmt-del" title={t('incov.delete')} onClick={() => remove(c.id)}><Trash2 size={12} /></button>
                </div>
                <div className="inc-cmt-body">{c.body}</div>
              </div>
            ))}
        </div>

        <div className="inc-cmt-add">
          <textarea rows={2} value={body} placeholder={t('incov.commentPlaceholder')}
            onChange={e => setBody(e.target.value)} />
          <button className="btn btn-primary" disabled={saving || !body.trim()} onClick={add}><Send size={14} />{t('incov.addComment')}</button>
        </div>
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('incov.close')}</button>
        </div>
      </div>
    </div>
  )
}
