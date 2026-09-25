import { useMemo, useState } from 'react'
import { LogIn, XCircle, ShieldAlert, Search, Download, Users, Globe, Clock, MapPin } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { formatDateSec } from '../../../api/client'
import ModalShell from '../../ui/ModalShell.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { toCsv, downloadCsv, stampedName } from '../../../utils/csvExport.js'
import { splitFlags, FLAG_KEYS } from './uactModel.js'
import { Button } from '@/components/shadcn/button'

/**
 * Giriş / başarısız giriş / anomali KPI kartlarının drill-down'ı (2026-09-20, kullanıcı bildirimi: "login kartına
 * tıklayınca açılan sayfa çok basic"). Özet şeridi (toplam, tekil kullanıcı, tekil IP, takım, mesai dışı, başarısız),
 * süzgeçler (metin: kullanıcı / IP / kuruluş; takım; sonuç; bayrak), sayfalı tablo (zaman, kullanıcı + rol, takım,
 * kaynak, IP + konum + kuruluş, tarayıcı, sonuç + sebep, bayraklar), CSV. Satırdaki kullanıcı → oturum detayı (onUser).
 * Veri: details.<kind> (backend eventRows — aktör adı/rol/takım/kaynak, olay türü, kuruluş, tarayıcı ile zenginleştirilmiş).
 */
export function shortUa(ua) {
  if (!ua) return '—'
  if (/Edg\//.test(ua)) return 'Edge'
  if (/OPR\/|Opera/.test(ua)) return 'Opera'
  if (/Chrome\//.test(ua)) return 'Chrome'
  if (/Firefox\//.test(ua)) return 'Firefox'
  if (/Safari\//.test(ua)) return 'Safari'
  if (/curl\//i.test(ua)) return 'curl'
  return ua.split(' ')[0] || '—'
}
export function osOf(ua) {
  if (!ua) return ''
  if (/Windows/.test(ua)) return 'Windows'
  if (/Mac OS|Macintosh/.test(ua)) return 'macOS'
  if (/Android/.test(ua)) return 'Android'
  if (/iPhone|iPad/.test(ua)) return 'iOS'
  if (/Linux/.test(ua)) return 'Linux'
  return ''
}
const loc = (r) => [r?.city, r?.country].filter(Boolean).join(', ')

export function eventMatches(r, f) {
  if (f.team && String(r.team_id ?? '') !== String(f.team)) return false
  if (f.outcome === 'SUCCESS' && r.outcome !== 'SUCCESS') return false
  if (f.outcome === 'FAILURE' && r.outcome === 'SUCCESS') return false
  if (f.flag && !splitFlags(r.flags).includes(f.flag)) return false
  if (f.q) {
    const q = f.q.toLowerCase()
    if (![r.actor, r.display_name, r.ip, r.org, r.city, r.country, r.reason, r.team_name].some((v) => v && String(v).toLowerCase().includes(q))) return false
  }
  return true
}

export function eventsCsv(rows, t) {
  const head = [t('uact.colTime'), t('uact.colUser'), t('uact.detailDisplayName'), t('uact.colRole'), t('uact.colTeam'), t('uact.colAuthSource'), t('uact.colIp'), t('uact.colLocation'), t('uact.detailOrg'), t('uact.colBrowser'), t('uact.colOutcome'), t('uact.colReason'), t('uact.colFlags')]
  return toCsv(head, rows.map((r) => [r.time, r.actor, r.display_name, r.system_role, r.team_name, r.auth_source, r.ip, loc(r), r.org, r.user_agent ? `${shortUa(r.user_agent)} ${osOf(r.user_agent)}`.trim() : '', r.outcome, r.reason, r.flags]))
}

export default function EventListModal({ kind, title, rows: rowsIn, byName = new Map(), winLabel, onClose, onUser }) {
  const t = useT()
  const isAnom = kind === 'anomalies', isFailed = kind === 'failed'
  const [f, setF] = useState({ q: '', team: '', outcome: '', flag: '' })
  const patch = (p) => setF((x) => ({ ...x, ...p }))
  // eski (dar) satırlar için login_status'tan ad/rol/takım tamamlanır
  const all = useMemo(() => (rowsIn || []).map((r) => { const u = byName.get(String(r.actor || '').toLowerCase()) || {}; return { ...u, ...Object.fromEntries(Object.entries(r).filter(([, v]) => v != null)) } }), [rowsIn, byName])
  const rows = useMemo(() => all.filter((r) => eventMatches(r, f)), [all, f])
  const pager = usePagination(rows, { listKey: `uact-events-${kind}`, defaultSize: 25, resetDeps: [f] })
  const stats = useMemo(() => ({
    total: all.length,
    users: new Set(all.map((r) => String(r.actor || '').toLowerCase()).filter(Boolean)).size,
    ips: new Set(all.map((r) => r.ip).filter(Boolean)).size,
    teams: new Set(all.map((r) => r.team_id).filter((x) => x != null)).size,
    offHours: all.filter((r) => splitFlags(r.flags).includes('OFF_HOURS')).length,
    failed: all.filter((r) => r.outcome && r.outcome !== 'SUCCESS').length,
    ldap: all.filter((r) => r.auth_source === 'LDAP').length,
  }), [all])
  const opt = (arr) => [{ value: '', label: t('uact.filterAny') }, ...arr]
  const teamOptions = useMemo(() => { const m = new Map(); for (const r of all) if (r.team_id != null && r.team_name) m.set(String(r.team_id), r.team_name); return opt([...m.entries()].sort((a, b) => a[1].localeCompare(b[1])).map(([value, label]) => ({ value, label }))) }, [all]) // eslint-disable-line react-hooks/exhaustive-deps
  const flagOptions = opt(FLAG_KEYS.map((k) => ({ value: k, label: t(`uact.anom_${k}`) })))
  const outcomeOptions = opt([{ value: 'SUCCESS', label: t('uact.success') }, { value: 'FAILURE', label: t('uact.failed') }])
  const Icon = isAnom ? ShieldAlert : isFailed ? XCircle : LogIn
  const chip = (key, val, label, on, onClick, tone) => (
    <button key={key} type="button" className={`udir-chip${on ? ' is-on' : ''}${tone ? ' udir-chip--' + tone : ''}`} onClick={onClick} disabled={!onClick}><b>{val}</b> {label}</button>
  )

  return (
    <ModalShell open onClose={onClose} title={`${title} · ${rows.length}${rows.length !== all.length ? ' / ' + all.length : ''}`} icon={Icon} size="xl" scrollBody
      footer={<>
        <Button type="button" variant="secondary" onClick={() => downloadCsv(stampedName(kind), eventsCsv(rows, t))}><Download size={14} /> {t('uact.exportEvents')}</Button>
        <Button type="button" onClick={onClose}>{t('app.dismiss')}</Button>
      </>}>
      <p className="field-hint">{winLabel}{all.length >= 500 ? ` · ${t('uact.eventsCapped', 500)}` : ''}</p>
      <div className="udir-stats" data-testid="evl-stats">
        {chip('all', stats.total, t('uact.dirAll'), !f.outcome && !f.flag, () => patch({ outcome: '', flag: '' }))}
        {chip('users', stats.users, t('uact.uniqueUsers'), false, null)}
        {chip('ips', stats.ips, t('uact.uniqueIps'), false, null)}
        {chip('teams', stats.teams, t('uact.colTeam'), false, null)}
        {chip('ldap', stats.ldap, 'LDAP', false, null)}
        {!isAnom && chip('failed', stats.failed, t('uact.failed'), f.outcome === 'FAILURE', () => patch({ outcome: f.outcome === 'FAILURE' ? '' : 'FAILURE' }), stats.failed > 0 ? 'danger' : undefined)}
        {chip('off', stats.offHours, t('uact.anom_OFF_HOURS'), f.flag === 'OFF_HOURS', () => patch({ flag: f.flag === 'OFF_HOURS' ? '' : 'OFF_HOURS' }), stats.offHours > 0 ? 'warn' : undefined)}
      </div>
      <div className="uact-filters udir-filters">
        <label className="invtb-f udir-q"><span>{t('uact.dirSearch')}</span><span className="udir-q-wrap"><Search size={13} aria-hidden="true" /><input className="input" value={f.q} onChange={(e) => patch({ q: e.target.value })} placeholder={t('uact.eventsSearchPh')} aria-label={t('uact.dirSearch')} /></span></label>
        <label className="invtb-f"><span>{t('uact.colTeam')}</span><SearchableSelect ariaLabel={t('uact.colTeam')} value={f.team} onChange={(v) => patch({ team: v })} options={teamOptions} searchThreshold={6} /></label>
        {!isAnom && <label className="invtb-f"><span>{t('uact.colOutcome')}</span><SearchableSelect ariaLabel={t('uact.colOutcome')} value={f.outcome} onChange={(v) => patch({ outcome: v })} options={outcomeOptions} searchThreshold={99} /></label>}
        <label className="invtb-f"><span>{t('uact.colFlags')}</span><SearchableSelect ariaLabel={t('uact.colFlags')} value={f.flag} onChange={(v) => patch({ flag: v })} options={flagOptions} searchThreshold={99} /></label>
        {(f.q || f.team || f.outcome || f.flag) && <Button type="button" variant="secondary" size="sm" onClick={() => setF({ q: '', team: '', outcome: '', flag: '' })}>{t('uact.filterClear')}</Button>}
      </div>
      {rows.length === 0 ? <StatusBlock tone="neutral" icon={Users} title={t('uact.noLogins')} /> : (
        <div className="health-table-wrap uact-table-wrap">
          <table className="health-dbtable uact-table evl-table" data-testid="evl-table">
            <thead><tr>
              <th className="dbtcol-th">{t('uact.colTime')}</th>
              <th className="dbtcol-th">{t('uact.colUser')}</th>
              <th className="dbtcol-th">{t('uact.colTeam')}</th>
              <th className="dbtcol-th">{t('uact.colAuthSource')}</th>
              <th className="dbtcol-th">{t('uact.colIp')}</th>
              <th className="dbtcol-th">{t('uact.colBrowser')}</th>
              <th className="dbtcol-th">{t('uact.colOutcome')}</th>
              <th className="dbtcol-th">{t('uact.colFlags')}</th>
            </tr></thead>
            <tbody>{pager.pageItems.map((r, i) => {
              const ok = r.outcome === 'SUCCESS'
              return (
                <tr key={r.id ?? `${r.time}-${i}`} className={`uact-row${!ok && r.outcome ? ' evl-row--bad' : ''}`}>
                  <td data-label={t('uact.colTime')} className="sys-mono sys-small"><Clock size={11} aria-hidden="true" /> {r.time ? formatDateSec(r.time) : '—'}</td>
                  <td data-label={t('uact.colUser')}>{r.actor ? <button type="button" className="uact-link udir-name" onClick={() => onUser?.({ username: r.actor, ...r })}><UserBadge username={r.actor} userId={r.user_id} displayName={r.display_name} inline nameOnly size="sm" /></button> : '—'}{/* yalnız ad soyad (2026-09-21 kullanıcı bildirimi) — rol/olay türü hücreyi kalabalıklaştırıyordu */}</td>
                  <td data-label={t('uact.colTeam')}>{r.team_name ? <TeamBadge teamId={r.team_id} teamName={r.team_name} /> : <span className="sys-muted">—</span>}</td>
                  <td data-label={t('uact.colAuthSource')}>{r.auth_source ? <span className={`udir-src${r.auth_source === 'LDAP' ? ' udir-src--ldap' : ''}`}>{r.auth_source === 'LDAP' ? 'LDAP' : t('usr.authLocal')}</span> : '—'}</td>
                  <td data-label={t('uact.colIp')} className="sys-small"><span className="sys-mono">{r.ip || '—'}</span>{(loc(r) || r.org) && <span className="udir-sub"><MapPin size={10} aria-hidden="true" /> {[loc(r), r.org].filter(Boolean).join(' · ')}</span>}</td>
                  <td data-label={t('uact.colBrowser')} className="sys-small" title={r.user_agent || ''}>{r.user_agent ? <><Globe size={11} aria-hidden="true" /> {shortUa(r.user_agent)}{osOf(r.user_agent) ? <span className="sys-muted"> · {osOf(r.user_agent)}</span> : null}</> : '—'}</td>
                  <td data-label={t('uact.colOutcome')} className="sys-small">{r.outcome ? <span className={ok ? 'sys-ok-text' : 'sys-err-text'}>{ok ? t('uact.success') : r.outcome}</span> : '—'}{r.reason ? <span className="udir-sub">{r.reason}</span> : null}</td>
                  <td data-label={t('uact.colFlags')}>{splitFlags(r.flags).length ? splitFlags(r.flags).map((k) => <span key={k} className="uact-flag" title={t(`uact.flagHelp.${k}`)}>{t(`uact.anom_${k}`)}</span>) : <span className="sys-muted">—</span>}</td>
                </tr>
              )
            })}</tbody>
          </table>
        </div>
      )}
      {rows.length > 0 && <PaginationBar {...pager} compact />}
    </ModalShell>
  )
}
