import { useEffect, useState } from 'react'
import { AlertCircle } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import { LoadingBlock } from '../../ui/Progress.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { Button } from '@/components/shadcn/button'

const levelClass = (lvl) => ({ WARNING: 'warning', HIGH: 'high', CRITICAL: 'critical' })[lvl] ?? 'unknown'

/**
 * Takım kırılımı hücresi → o takımın ilgili alarmları, SAYFALI (2026-09-18, kullanıcı isteği).
 *
 * <p>Sunucu tarafı sayfalama: hücredeki sayı yüzlerce olabilir; liste {@code /admin/alerts} ucundan
 * takım + kova parametreleriyle sayfa sayfa çekilir. Kova → sorgu eşlemesi
 * {@code AlertTeamStatsService} ile AYNI kural: açık = tarihten bağımsız; kapandı = son pencere içinde
 * AÇILIP çözülmüş; 7/30 gün = pencerede açılanlar (açık+kapalı).
 *
 * @param {{team_id:number, team_name?:string}} cell.team
 * @param {'open'|'closed'|'last7'|'last30'} cell.bucket
 * @param {number} cell.windowDays  istatistik penceresi (sunucudan)
 */
export function bucketQuery(bucket, windowDays = 30, now = Date.now()) {
  const iso = (d) => new Date(now - d * 86400000).toISOString().slice(0, 19)
  if (bucket === 'open') return { resolved: false }
  if (bucket === 'closed') return { resolved: true, since: iso(windowDays) }
  if (bucket === 'last7') return { since: iso(7) }
  return { since: iso(windowDays) }
}

export default function AlertTeamCellModal({ cell, onClose, onOpenAlert }) {
  const t = useT()
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(25)
  const [state, setState] = useState({ loading: true, error: null, rows: [], total: 0 })

  useEffect(() => {
    let alive = true
    setState((s) => ({ ...s, loading: true, error: null }))
    api.admin.getAlerts({ teamId: cell.team.team_id, page: page - 1, size: pageSize, ...bucketQuery(cell.bucket, cell.windowDays) })
      .then((r) => { if (!alive) return; if (r?.success) setState({ loading: false, error: null, rows: r.data || [], total: Number(r.total) || 0 }); else setState({ loading: false, error: r?.error || t('mon.loadError'), rows: [], total: 0 }) })
      .catch((e) => { if (alive) setState({ loading: false, error: String(e?.message || e), rows: [], total: 0 }) })
    return () => { alive = false }
  }, [cell, page, pageSize, t])

  const totalPages = Math.max(1, Math.ceil(state.total / pageSize))
  const title = `${cell.team.team_name || t('alhts.unassigned')} · ${t('alhts.bucket.' + cell.bucket)} (${cell.count})`
  return (
    <ModalShell open onClose={onClose} title={title} icon={AlertCircle} size="lg" scrollBody
      footer={<Button type="button" variant="secondary" onClick={onClose}>{t('app.close')}</Button>}>
      {state.loading && state.rows.length === 0 ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : null}
      {state.error && <AlertBanner tone="danger" title={t('mon.loadError')} role="alert">{state.error}</AlertBanner>}
      {!state.error && !state.loading && state.rows.length === 0 && <div className="alh-ts-empty">{t('alhts.cellEmpty')}</div>}
      {state.rows.length > 0 && (
        <table className="admin-table alh-cell-table">
          <thead><tr>
            <th>{t('alhts.cellLevel')}</th><th>{t('alhts.cellTarget')}</th>
            <th>{t('alhts.cellOpened')}</th><th>{t('alhts.cellStatus')}</th>
          </tr></thead>
          <tbody>{state.rows.map((a) => (
            <tr key={a.id} className="alh-cell-row">
              <td><span className={`alert-level-badge alh-cell-lvl alh-lvl-bg--${levelClass(a.alert_level)}`}>{a.alert_level}</span></td>
              <td>
                {onOpenAlert
                  ? <button type="button" className="inv-domain" title={a.domain} onClick={() => onOpenAlert(a)}>{a.domain}</button>
                  : <span className="alh-cell-target" title={a.domain}>{a.domain}</span>}
                <div className="inv-dim alh-cell-msg" title={a.message || ''}>{alertTypeLabel(t, a.alert_type)}{a.message ? ` · ${a.message}` : ''}</div>
              </td>
              <td className="inv-dim">{formatDate(a.created_at)}</td>
              <td>{a.resolved
                ? <><span className="badge badge-ok">{t('alhts.colClosed')}</span>{a.resolved_at && <div className="inv-dim alh-cell-msg">{formatDate(a.resolved_at)}</div>}</>
                : <span className="badge badge-err">{t('alhts.colOpen')}</span>}</td>
            </tr>
          ))}</tbody>
        </table>
      )}
      {state.total > 0 && (
        <PaginationBar page={page} totalPages={totalPages} totalItems={state.total}
          rangeStart={(page - 1) * pageSize + 1} rangeEnd={Math.min(page * pageSize, state.total)}
          pageSize={pageSize} sizeOptions={[10, 25, 50, 100]}
          onPageChange={setPage} onPageSizeChange={(n) => { setPageSize(n); setPage(1) }} />
      )}
    </ModalShell>
  )
}
