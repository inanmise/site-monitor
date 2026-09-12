import { useEffect, useState } from 'react'
import { Users, ShieldAlert, Clock, Eye, LogOut, Check, UserX } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api, formatDateSec } from '../../../api/client'
import ModalShell from '../../ui/ModalShell.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { splitFlags, relTime, splitDuration, tabLabel, loginStatus } from './uactModel.js'

/** Kullanıcı / Oturum paneli modalları (2026-09-13). Hepsi ModalShell (odak tuzağı, Escape, scroll kilidi). */

function loc(r) { return [r?.city, r?.country].filter(Boolean).join(', ') || '—' }
function shortUa(ua) {
  if (!ua) return '—'
  if (/Edg\//.test(ua)) return 'Edge'
  if (/OPR\/|Opera/.test(ua)) return 'Opera'
  if (/Chrome\//.test(ua)) return 'Chrome'
  if (/Firefox\//.test(ua)) return 'Firefox'
  if (/Safari\//.test(ua)) return 'Safari'
  if (/curl\//i.test(ua)) return 'curl'
  return ua.split(' ')[0] || '—'
}
function osOf(ua) {
  if (!ua) return ''
  if (/Windows/.test(ua)) return 'Windows'
  if (/Mac OS|Macintosh/.test(ua)) return 'macOS'
  if (/Android/.test(ua)) return 'Android'
  if (/iPhone|iPad/.test(ua)) return 'iOS'
  if (/Linux/.test(ua)) return 'Linux'
  return ''
}

/** Isı haritası hücresi → o saatteki girişler. */
export function HeatCellModal({ cell, onClose, onUser }) {
  const t = useT()
  const labels = t('uact.weekdays').split(',')
  const list = (cell.cells && cell.cells[`${cell.weekday}-${cell.hour}`]) || []
  const hh = String(cell.hour).padStart(2, '0')
  return (
    <ModalShell open onClose={onClose} title={`${labels[cell.weekday] || ''} ${hh}:00–${hh}:59 · ${list.length}`} icon={Clock} size="lg">
      {cell.label && <p className="field-hint">{cell.label}</p>}
      {list.length === 0 ? <div className="sys-muted">{t('uact.noLogins')}</div> : (
        <div className="health-table-wrap">
          <table className="health-dbtable uact-table">
            <thead><tr><th className="dbtcol-th">{t('uact.colTime')}</th><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th">{t('uact.colIp')}</th><th className="dbtcol-th">{t('uact.colOutcome')}</th><th className="dbtcol-th">{t('uact.colReason')}</th></tr></thead>
            <tbody>{list.map((r, i) => (
              <tr key={i}>
                <td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                <td>{r.actor ? <button type="button" className="uact-link" onClick={() => onUser?.(r.actor)}><UserBadge username={r.actor} inline size="sm" /></button> : '—'}</td>
                <td className="sys-mono sys-small">{r.ip || '—'} <span className="sys-muted">{loc(r)}</span></td>
                <td className="sys-small">{r.outcome === 'SUCCESS' ? <span className="sys-ok-text">{r.outcome}</span> : <span className="sys-err-text">{r.outcome || '—'}</span>}</td>
                <td className="sys-small">{r.reason || '—'}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </ModalShell>
  )
}

/** KPI kartı drill-down: active | logins | failed | anomalies | unique_users | dormant. */
export function KpiDetailModal({ detail, data, onClose, onUser, winLabel }) {
  const t = useT()
  const kind = detail.kind
  const rows = kind === 'active' ? (data.active_users || []) : (data.details?.[kind] || [])
  const isUsers = kind === 'unique_users', isDormant = kind === 'dormant', isActive = kind === 'active'
  const isFailed = kind === 'failed', isAnom = kind === 'anomalies'
  const rel = (iso) => { const r = relTime(iso); return r ? t(`uact.rel.${r.unit}`, r.n) : '—' }
  return (
    <ModalShell open onClose={onClose} title={`${detail.title} · ${rows.length}`} icon={isDormant ? UserX : isAnom ? ShieldAlert : Users} size="lg">
      <p className="field-hint">{isDormant ? t('uact.dormantHint') : isActive ? t('uact.kpiLive') : winLabel}</p>
      {rows.length === 0 ? <div className="sys-muted">{isDormant ? t('uact.noDormant') : t('uact.noLogins')}</div> : (
        <div className="health-table-wrap">
          <table className="health-dbtable uact-table">
            {isActive ? (<>
              <thead><tr><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th">{t('uact.colTeam')}</th><th className="dbtcol-th">{t('uact.colLoginAt')}</th><th className="dbtcol-th">{t('uact.colLastTab')}</th><th className="dbtcol-th">{t('uact.colLocation')}</th></tr></thead>
              <tbody>{rows.map((u) => (
                <tr key={u.username}><td><button type="button" className="uact-link" onClick={() => onUser?.(u)}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /></button></td>
                  <td>{u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : '—'}</td><td className="sys-mono sys-small">{u.login_at ? formatDateSec(u.login_at) : '—'}</td>
                  <td className="sys-small">{u.last_tab ? tabLabel(u.last_tab, t) : '—'}</td><td className="sys-small">{u.ip || '—'} <span className="sys-muted">{loc(u)}</span></td></tr>
              ))}</tbody>
            </>) : isDormant ? (<>
              <thead><tr><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th">{t('uact.colRole')}</th><th className="dbtcol-th">{t('uact.colTeam')}</th><th className="dbtcol-th">{t('uact.colLastLogin')}</th><th className="dbtcol-th">{t('uact.colAuthSource')}</th></tr></thead>
              <tbody>{rows.map((u) => (
                <tr key={u.username}><td><button type="button" className="uact-link" onClick={() => onUser?.(u)}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /></button></td>
                  <td className="sys-small">{u.system_role || '—'}</td><td>{u.team_name ? <TeamBadge teamName={u.team_name} /> : '—'}</td>
                  <td className="sys-small">{u.last_login_at ? <span title={formatDateSec(u.last_login_at)}>{rel(u.last_login_at)}</span> : <span className="uact-pill uact-st--never">{t('uact.st.never')}</span>}</td>
                  <td className="sys-small">{u.auth_source || '—'}</td></tr>
              ))}</tbody>
            </>) : isUsers ? (<>
              <thead><tr><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th dbtcol-th-num">{t('uact.colLogins')}</th><th className="dbtcol-th">{t('uact.colLastLogin')}</th></tr></thead>
              <tbody>{rows.map((r, i) => <tr key={i}><td><button type="button" className="uact-link" onClick={() => onUser?.({ username: r.username })}><UserBadge username={r.username} inline size="sm" /></button></td><td className="dbtcol-num-cell">{r.logins}</td><td className="sys-mono sys-small">{r.last_login ? formatDateSec(r.last_login) : '—'}</td></tr>)}</tbody>
            </>) : (<>
              <thead><tr><th className="dbtcol-th">{t('uact.colTime')}</th><th className="dbtcol-th">{t('uact.colUser')}</th><th className="dbtcol-th">{t('uact.colIp')}</th>{isAnom && <th className="dbtcol-th">{t('uact.colFlags')}</th>}{(isFailed || isAnom) && <th className="dbtcol-th">{t('uact.colOutcome')}</th>}{isFailed && <th className="dbtcol-th">{t('uact.colReason')}</th>}</tr></thead>
              <tbody>{rows.map((r, i) => (
                <tr key={i}><td className="sys-mono sys-small">{r.time ? formatDateSec(r.time) : '—'}</td>
                  <td>{r.actor ? <button type="button" className="uact-link" onClick={() => onUser?.({ username: r.actor })}><UserBadge username={r.actor} inline size="sm" /></button> : '—'}</td>
                  <td className="sys-mono sys-small">{r.ip || '—'} <span className="sys-muted">{loc(r)}</span></td>
                  {isAnom && <td>{splitFlags(r.flags).map((f) => <span key={f} className="uact-flag">{t(`uact.anom_${f}`)}</span>)}</td>}
                  {(isFailed || isAnom) && <td className="sys-small">{r.outcome === 'SUCCESS' ? <span className="sys-ok-text">{r.outcome}</span> : <span className="sys-err-text">{r.outcome || '—'}</span>}</td>}
                  {isFailed && <td className="sys-small">{r.reason || '—'}</td>}</tr>
              ))}</tbody>
            </>)}
          </table>
        </div>
      )}
    </ModalShell>
  )
}

/** Oturum / kullanıcı detayı: kimlik (gizlilik #14), oturum, giriş geçmişi, kaynak, 30 günlük zaman çizelgesi (#3). */
export function SessionDetailModal({ row, full, isAdmin, globalAdmin, self, activeSet, onClose, onTerminate, onAck, ackBusy }) {
  const t = useT()
  const u = full || row
  const [timeline, setTimeline] = useState(null)
  const [tlError, setTlError] = useState(false)
  const [revealUa, setRevealUa] = useState(false)
  useEffect(() => {
    let alive = true
    setTimeline(null); setTlError(false)
    api.admin.getUserTimeline(u.username, 20).then((r) => { if (!alive) return; if (r?.success) setTimeline(r.data); else setTlError(true) }).catch(() => { if (alive) setTlError(true) })
    return () => { alive = false }
  }, [u.username])
  const field = (label, value, mono) => (
    <div className="show-field" key={label}><span className="show-field-label">{label}</span><span className={`show-field-value${mono ? ' show-field-mono' : ''}`}>{value || '—'}</span></div>
  )
  const rel = (iso) => { const r = relTime(iso); return r ? t(`uact.rel.${r.unit}`, r.n) : '—' }
  const dur = (sec) => { const d = splitDuration(sec); return d.h > 0 ? `${d.h} ${t('chg.unitHour')} ${String(d.m).padStart(2, '0')} ${t('chg.unitMin')}` : `${d.m} ${t('chg.unitMin')}` }
  const status = loginStatus(u, activeSet || new Set())
  const isLive = status === 'active'
  return (
    <ModalShell open onClose={onClose} title={u.username} icon={Users} size="lg" scrollBody
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose}>{t('app.dismiss')}</button>
        {isAdmin && isLive && !self && <button type="button" className="btn btn-danger" onClick={() => onTerminate?.(u.username)}><LogOut size={14} /> {t('uact.terminate')}</button>}
      </>}>
      <div className="uact-detail-head">
        <span className={`uact-pill uact-st--${status}`}>{t(`uact.st.${status}`)}</span>
        {u.system_role && <span className="show-badge show-badge-port">{u.system_role}</span>}
        {self && <span className="uact-pill">{t('uact.selfSession')}</span>}
      </div>

      <div className="show-section-header">{t('uact.detailUser')}</div>
      <div className="show-grid-2">
        {field(t('uact.colUser'), u.username, true)}
        {field(t('uact.detailDisplayName'), u.display_name)}
        {field(t('uact.detailEmail'), u.email, true)}
        {globalAdmin ? field(t('uact.detailEmployeeId'), u.employee_id, true) : field(t('uact.detailEmployeeId'), t('uact.masked'))}
        {field(t('uact.colRole'), u.system_role)}
        {field(t('uact.detailOrgRole'), u.org_role)}
        {field(t('uact.colTeam'), u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : null)}
        {field(t('uact.colAuthSource'), u.auth_source)}
      </div>

      {isLive && (<>
        <div className="show-section-header">{t('uact.detailSession')}</div>
        <div className="show-grid-2">
          {field(t('uact.colLoginAt'), u.login_at ? formatDateSec(u.login_at) : '—', true)}
          {field(t('uact.colDuration'), dur((u.duration_min || 0) * 60))}
          {field(t('uact.detailLastSeen'), u.last_seen ? `${formatDateSec(u.last_seen)} · ${rel(u.last_seen)}` : '—', true)}
          {field(t('uact.colIdle'), u.idle_sec >= 0 ? dur(u.idle_sec) : '—')}
          {field(t('uact.colExpires'), u.expires_in_sec != null ? dur(u.expires_in_sec) : '—')}
          {field(t('uact.colLastTab'), u.last_tab ? `${tabLabel(u.last_tab, t)}${u.last_tab_at ? ' · ' + rel(u.last_tab_at) : ''}` : '—')}
        </div>
        <div className="show-section-header">{t('uact.detailSource')}</div>
        <div className="show-grid-2">
          {field(t('uact.colIp'), u.ip, true)}
          {field(t('uact.colLocation'), loc(u))}
          {field(t('uact.detailOrg'), u.org)}
          {field(t('uact.colBrowser'), `${shortUa(u.user_agent)}${osOf(u.user_agent) ? ' · ' + osOf(u.user_agent) : ''}`)}
        </div>
        <div className="show-field show-field-full">
          <span className="show-field-label">{t('uact.detailUserAgent')}</span>
          {revealUa ? <span className="show-field-value show-field-mono">{u.user_agent || '—'}</span>
            : <button type="button" className="uact-link sys-small" onClick={() => setRevealUa(true)}><Eye size={12} /> {t('uact.reveal')}</button>}
        </div>
      </>)}

      <div className="show-section-header">{t('uact.detailLoginHistory')}</div>
      <div className="show-grid-2">
        {field(t('uact.colLastLogin'), u.last_login_at ? formatDateSec(u.last_login_at) : '—', true)}
        {field(t('uact.detailLoginMethod'), u.last_login_method)}
        {field(t('uact.colPrevLogin'), u.prev_login_at ? formatDateSec(u.prev_login_at) : '—', true)}
        {field(t('uact.detailPrevIp'), u.prev_login_ip, true)}
        {field(t('uact.colLastFailed'), u.last_failed_at ? formatDateSec(u.last_failed_at) : '—', true)}
        {field(t('uact.detailFailedIp'), u.last_failed_ip, true)}
        {field(t('uact.colFailedCount'), String(u.failed_since_login ?? 0))}
      </div>

      <div className="show-section-header">{t('uact.timelineTitle')}</div>
      {tlError && <AlertBanner tone="warning">{t('uact.loadError')}</AlertBanner>}
      {!timeline && !tlError && <div className="sys-muted sys-small">…</div>}
      {timeline && (<>
        <p className="field-hint">{t('uact.timelineStats', timeline.logins ?? 0, timeline.failed ?? 0, timeline.distinct_ips ?? 0)}</p>
        {(timeline.events || []).length === 0 ? <div className="sys-muted sys-small">{t('uact.noEvents')}</div> : (
          <ul className="uact-timeline">
            {timeline.events.map((e) => (
              <li key={e.id ?? e.time} className={`uact-tl${e.outcome === 'SUCCESS' ? ' is-ok' : ' is-bad'}${e.flags ? ' is-anom' : ''}`}>
                <span className="uact-tl-dot" />
                <span className="uact-tl-time sys-mono sys-small">{e.time ? formatDateSec(e.time) : '—'}</span>
                <span className="uact-tl-body">
                  <span className={e.outcome === 'SUCCESS' ? 'sys-ok-text' : 'sys-err-text'}>{e.outcome || '—'}</span>
                  {e.reason && <span className="sys-muted"> · {e.reason}</span>}
                  <span className="sys-muted sys-small"> · {e.ip || '—'} {loc(e) !== '—' ? `(${loc(e)})` : ''} · {shortUa(e.user_agent)}</span>
                  {splitFlags(e.flags).map((f) => <span key={f} className="uact-flag" title={t(`uact.flagHelp.${f}`)}>{t(`uact.anom_${f}`)}</span>)}
                  {e.flags && (e.ack
                    ? <span className="uact-ack"><Check size={12} /> {e.ack.by} · {rel(e.ack.at)}</span>
                    : <button type="button" className="uact-link sys-small" disabled={ackBusy === e.id} onClick={() => onAck?.(e, true)}>{t('uact.ack')}</button>)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </>)}
    </ModalShell>
  )
}

/** Gerekçeli sonlandırma (#10). */
export function TerminateModal({ target, busy, onClose, onConfirm }) {
  const t = useT()
  const [reason, setReason] = useState('')
  return (
    <ModalShell open onClose={onClose} title={t('uact.terminateTitle', target)} icon={LogOut} busy={busy}
      footer={<>
        <button type="button" className="btn btn-secondary" disabled={busy} onClick={onClose}>{t('inv.cancel')}</button>
        <button type="button" className="btn btn-danger" disabled={busy} onClick={() => onConfirm(reason.trim())}>{busy ? t('uact.terminating') : t('uact.terminate')}</button>
      </>}>
      <AlertBanner tone="warning">{t('uact.terminateConfirm', target)}</AlertBanner>
      <label className="full-width"><span>{t('uact.terminateReason')}</span><input className="input" maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t('uact.terminateReasonPh')} /></label>
      <p className="field-hint">{t('uact.terminateAudit')}</p>
    </ModalShell>
  )
}

/** Anomali onayı (#3) — not ile. */
export function AckModal({ row, busy, onClose, onConfirm }) {
  const t = useT()
  const [note, setNote] = useState('')
  return (
    <ModalShell open onClose={onClose} title={t('uact.ackTitle')} icon={ShieldAlert} busy={busy}
      footer={<>
        <button type="button" className="btn btn-secondary" disabled={busy} onClick={onClose}>{t('inv.cancel')}</button>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onConfirm(note.trim())}><Check size={14} /> {t('uact.ack')}</button>
      </>}>
      <p className="field-hint">{row.actor} · {row.time ? formatDateSec(row.time) : '—'} · {splitFlags(row.flags).map((f) => t(`uact.anom_${f}`)).join(', ')}</p>
      <label className="full-width"><span>{t('uact.ackNote')}</span><textarea className="input" rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} /></label>
    </ModalShell>
  )
}
