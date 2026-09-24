import { useState, useEffect, useMemo, Fragment } from 'react'
import { History, ChevronDown, ChevronRight, RefreshCw } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/** Olay türü → rozet sınıfı (Denetim Kaydı sayfasıyla aynı dağarcık). */
function badgeClass(action) {
  const a = String(action || '')
  if (a.endsWith('CREATE') || a.endsWith('MEMBER_ADD')) return 'ev-create'
  if (a.endsWith('DELETE') || a.endsWith('MEMBER_REMOVE') || a === 'ACCOUNT_LOCKED') return 'ev-delete'
  if (a.endsWith('UPDATE') || a.includes('UNLOCK') || a.includes('NOTIFICATIONS') || a.includes('ACCESS')) return 'ev-edit'
  return 'ev-other'
}

function parseChanges(raw) {
  if (!raw) return null
  try { const o = JSON.parse(raw); return o && typeof o === 'object' && !Array.isArray(o) ? o : null } catch { return null }
}
/** Fark biçimi mi ({"alan":{"from":…,"to":…}}) — içerikten karar (eski kayıtlar da doğru okunsun). */
function isDiffShape(obj) {
  const vals = Object.values(obj)
  return vals.length > 0 && vals.every(v => v && typeof v === 'object' && !Array.isArray(v) && ('from' in v || 'to' in v))
}
function fmt(t, v) {
  if (v === true) return t('ng.histYes')
  if (v === false) return t('ng.histNo')
  if (v === null || v === undefined || v === '') return t('ng.histEmptyValue')
  if (Array.isArray(v)) return v.join(', ')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

/**
 * Yönetim Paneli "Değişiklik Geçmişi" (2026-09-20, v2): eşik / eskalasyon kişisi / takım / kullanıcı.
 *
 * <p>v1 liste görünümüydü, sayfalama yoktu ve USER kaynağı giriş kayıtlarıyla doluyordu. v2: sunucu sayfalı
 * (PaginationBar), olay türü süzgeç çipleri (sunucunun beyaz listesi), tablo satırında özet fark ("rol: PO → TECH"),
 * satır açılınca tam fark tablosu / anlık görüntü, bir kayda süzme (satırdan "Geçmiş"). KAPALI başlar: her
 * açılışta denetim sorgusu atmak ekranı asıl işi için açan kullanıcıya bedava yük bindirir.
 */
export default function AdminChangeHistory({ resource, filter = null, onClearFilter, canView = true }) {
  const t = useT()
  const [open, setOpen] = useState(!!filter)
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(25)
  const [types, setTypes] = useState([])        // seçili olay türleri (boş = hepsi)
  const [expanded, setExpanded] = useState(null)
  const [nonce, setNonce] = useState(0)

  useEffect(() => { if (filter) { setOpen(true); setPage(1) } }, [filter?.id])   // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!open || !canView) return
    let cancelled = false
    setLoading(true); setError(null)
    api.admin.history(resource, filter?.id ?? null, { page: page - 1, size, types })
      .then(res => {
        if (cancelled) return
        if (res?.success) setData(res)
        else setError(res?.error ?? t('ng.histError'))
      })
      .catch(e => { if (!cancelled) setError(e?.message ?? String(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [open, resource, filter?.id, canView, page, size, types, nonce])   // eslint-disable-line react-hooks/exhaustive-deps

  const allTypes = useMemo(() => data?.types || [], [data])
  const items = data?.items || []
  const total = Number(data?.total ?? 0)
  const totalPages = Math.max(1, Number(data?.total_pages ?? 1))

  if (!canView) return null

  const actLabel = (a) => { const k = `hist.act.${a}`; const s = t(k); return s === k ? String(a || '').toLowerCase().replace(/_/g, ' ') : s }
  const fieldLabel = (f) => { const k = `hist.f.${f}`; const s = t(k); return s === k ? f : s }
  const toggleType = (ty) => { setPage(1); setTypes(prev => prev.includes(ty) ? prev.filter(x => x !== ty) : [...prev, ty]) }

  return (
    <div className="admin-section ach" data-testid="admin-history">
      <div className="admin-section-header ng-hist-header">
        <div>
          <h3>{t('hist.title')}</h3>
          <p className="section-desc">{t(`hist.desc.${resource}`)}</p>
        </div>
        <div className="hdr-actions">
          {open && <Button variant="secondary" size="sm" onClick={() => setNonce(n => n + 1)} title={t('hist.refresh')} aria-label={t('hist.refresh')}><RefreshCw size={14} /></Button>}
          <Button variant="secondary" onClick={() => { if (open) { setOpen(false); onClearFilter?.() } else setOpen(true) }}>
            <History size={15} aria-hidden="true" /> {open ? t('ng.histHide') : t('ng.histShow')}
          </Button>
        </div>
      </div>

      {open && (
        <>
          <div className="ach-toolbar">
            {filter && (
              <span className="ach-filter">
                {t('ng.histFilterOn').replace('{name}', filter.name)}
                <Button type="button" variant="secondary" size="sm" onClick={onClearFilter}>{t('ng.histFilterClear')}</Button>
              </span>
            )}
            {allTypes.length > 0 && (
              <div className="ach-chips" role="group" aria-label={t('hist.typeFilter')}>
                <button type="button" className={`ach-chip${types.length === 0 ? ' is-active' : ''}`} onClick={() => { setTypes([]); setPage(1) }}>{t('hist.allTypes')}</button>
                {allTypes.map(ty => (
                  <button type="button" key={ty} className={`ach-chip${types.includes(ty) ? ' is-active' : ''}`} aria-pressed={types.includes(ty)} onClick={() => toggleType(ty)}>
                    {actLabel(ty.replace(/^(THRESHOLD|CONTACT|TEAM|USER)_/, ''))}
                  </button>
                ))}
              </div>
            )}
          </div>

          {error && <AlertBanner tone="danger" title={t('ng.histError')}>{error}</AlertBanner>}

          <div className="audit-table-wrap ach-table-wrap">
            {loading && <LoadingBlock label={t('ng.histLoading')} className="audit-loading" />}
            <table className="audit-table ach-table">
              <thead>
                <tr>
                  <th className="aud-col-chevron"><span className="sr-only">{t('audit.colDetail')}</span></th>
                  <th>{t('audit.colTime')}</th>
                  <th>{t('hist.colAction')}</th>
                  <th>{t('audit.colActor')}</th>
                  <th>{t('hist.colTarget')}</th>
                  <th>{t('hist.colChange')}</th>
                  <th data-col="ip">{t('audit.colIp')}</th>
                </tr>
              </thead>
              <tbody>
                {!loading && items.length === 0 && (
                  <tr><td colSpan={7} className="audit-empty">{filter ? t('ng.histEmptyGroup') : t('ng.histEmpty')}</td></tr>
                )}
                {items.map(r => {
                  const parsed = parseChanges(r.changes)
                  const diff = parsed && isDiffShape(parsed) ? parsed : null
                  const isOpen = expanded === r.id
                  const summary = diff
                    ? Object.entries(diff).slice(0, 2).map(([f, c]) => `${fieldLabel(f)}: ${fmt(t, c.from)} → ${fmt(t, c.to)}`).join(' · ')
                    : parsed ? Object.keys(parsed).slice(0, 3).map(fieldLabel).join(', ') : ''
                  const more = diff ? Object.keys(diff).length - 2 : parsed ? Object.keys(parsed).length - 3 : 0
                  return (
                    <Fragment key={r.id}>
                      <tr className={`aud-row${isOpen ? ' is-selected' : ''}`} aria-expanded={isOpen}
                        tabIndex={parsed ? 0 : undefined}
                        aria-label={parsed ? t('a11y.toggleRow', formatDateSec(r.at)) : undefined}
                        onClick={() => parsed && setExpanded(isOpen ? null : r.id)}
                        onKeyDown={(e) => { if (e.target === e.currentTarget && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); parsed && setExpanded(isOpen ? null : r.id) } }}
                        style={{ cursor: parsed ? 'pointer' : 'default' }}>
                        <td className="aud-col-chevron">{parsed ? (isOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />) : null}</td>
                        <td className="audit-cell-time">{formatDateSec(r.at)}</td>
                        <td><span className={`audit-event-badge ${badgeClass(r.event_type)}`} title={r.event_type}>{actLabel(r.action)}</span></td>
                        <td>{r.actor ? <UserBadge username={r.actor} inline size="sm" /> : '—'}</td>
                        <td>
                          <span className="ach-target">{r.name || `#${r.resource_id}`}</span>
                          {r.team_name && <div className="audit-sub"><TeamBadge teamId={r.team_id} teamName={r.team_name} size={11} /></div>}
                        </td>
                        <td className="ach-summary">
                          {summary ? <span title={summary}>{summary}{more > 0 ? <span className="audit-sub"> +{more}</span> : null}</span> : <span className="audit-sub">—</span>}
                        </td>
                        <td className="audit-mono" data-col="ip">{r.ip || '—'}</td>
                      </tr>
                      {isOpen && parsed && (
                        <tr className="ach-detail-row">
                          <td colSpan={7}>
                            {diff ? (
                              <table className="audit-diff-table">
                                <thead><tr><th>{t('audit.diffField')}</th><th>{t('audit.diffFrom')}</th><th>{t('audit.diffTo')}</th></tr></thead>
                                <tbody>
                                  {Object.entries(diff).map(([f, c]) => (
                                    <tr key={f}><td className="audit-diff-field">{fieldLabel(f)}</td><td className="audit-diff-from">{fmt(t, c.from)}</td><td className="audit-diff-to">{fmt(t, c.to)}</td></tr>
                                  ))}
                                </tbody>
                              </table>
                            ) : (
                              <div className="ng-hist-detail">
                                <div className="audit-sub">{String(r.action).endsWith('DELETE') ? t('ng.histSnapshotOld') : t('ng.histSnapshotNew')}</div>
                                <ul className="ng-hist-fields">
                                  {Object.entries(parsed).map(([f, v]) => (
                                    <li key={f}><span className="audit-diff-field">{fieldLabel(f)}</span><span className="audit-mono">{fmt(t, v)}</span></li>
                                  ))}
                                </ul>
                              </div>
                            )}
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>

          {total > 0 && (
            <PaginationBar page={page} totalPages={totalPages} totalItems={total}
              rangeStart={(page - 1) * size + 1} rangeEnd={Math.min(page * size, total)}
              pageSize={size} onPageChange={setPage} onPageSizeChange={(s) => { setSize(s); setPage(1) }} />
          )}
          {data?.truncated && <p className="field-hint">{t('ng.histTruncated').replace('{n}', total)}</p>}
          {Number(data?.hidden) > 0 && <p className="field-hint">{t('ng.histHidden').replace('{n}', data.hidden)}</p>}
          <p className="field-hint">{t('ng.histRetentionNote')}</p>
        </>
      )}
    </div>
  )
}
