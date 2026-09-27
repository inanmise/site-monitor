import { useEffect, useState } from 'react'
import { Users, ShieldAlert, Clock, Eye, LogOut, Check, UserX, Compass, ExternalLink, UserCog } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api, formatDateSec } from '../../../api/client'
import ModalShell from '../../ui/ModalShell.jsx'
import TeamBadge from '../../ui/TeamBadge.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import UserDetailPanel from '../UserDetailPanel.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import { splitFlags, relTime, splitDuration, tabLabel, loginStatus } from './uactModel.js'
import Field from '../../ui/Field.jsx'
import ToneBadge, { SystemRoleBadge, OrgRoleBadge } from '../ToneBadge.jsx'
import { TH, TH_NUM, TD, TD_NUM, MUTED_SM, DataTable, Pill, FlagBadge, LinkButton, KvField, KV_GRID, KvSection, AuthSourceBadge } from '../HealthUi.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/** Kullanıcı / Oturum paneli modalları (2026-09-13). Hepsi ModalShell (odak tuzağı, Escape, scroll kilidi).
 *  Çizim shadcn: Table / Badge / Button / Input / Textarea + ortak parçalar ../HealthUi.jsx. */

const MONO_SM = 'font-mono text-xs'
/** Giriş sonucu metni (SUCCESS yeşil, diğerleri kırmızı). */
function Outcome({ value }) {
  return <span className={value === 'SUCCESS' ? 'text-success' : 'text-destructive'}>{value || '—'}</span>
}

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
      {cell.label && <p className={MUTED_SM}>{cell.label}</p>}
      {list.length === 0 ? <div className="text-muted-foreground">{t('uact.noLogins')}</div> : (
        <DataTable>
          <TableHeader><TableRow>
            <TableHead className={TH}>{t('uact.colTime')}</TableHead><TableHead className={TH}>{t('uact.colUser')}</TableHead>
            <TableHead className={TH}>{t('uact.colIp')}</TableHead><TableHead className={TH}>{t('uact.colOutcome')}</TableHead>
            <TableHead className={TH}>{t('uact.colReason')}</TableHead>
          </TableRow></TableHeader>
          <TableBody>{list.map((r, i) => (
            <TableRow key={i}>
              <TableCell className={cn(TD, MONO_SM)}>{r.time ? formatDateSec(r.time) : '—'}</TableCell>
              <TableCell className={TD}>{r.actor ? <LinkButton onClick={() => onUser?.(r.actor)}><UserBadge username={r.actor} inline size="sm" /></LinkButton> : '—'}</TableCell>
              <TableCell className={cn(TD, MONO_SM)}>{r.ip || '—'} <span className="text-muted-foreground">{loc(r)}</span></TableCell>
              <TableCell className={cn(TD, 'text-xs')}><Outcome value={r.outcome} /></TableCell>
              <TableCell className={cn(TD, 'text-xs')}>{r.reason || '—'}</TableCell>
            </TableRow>
          ))}</TableBody>
        </DataTable>
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
  // 2026-09-20: giriş / anomali / tekil kullanıcı satırlarında ad + rol + takım (olay satırı taşımıyorsa login_status'tan)
  const byName = new Map((data.login_status || []).map((u) => [String(u.username || '').toLowerCase(), u]))
  const who = (name, r = {}) => { const u = byName.get(String(name || '').toLowerCase()) || {}; return { ...u, ...Object.fromEntries(Object.entries(r).filter(([, v]) => v != null)) } }
  const userCell = (name, r) => { const u = who(name, r); return name
    ? <LinkButton onClick={() => onUser?.({ username: name, ...u })}><UserBadge username={name} userId={u.user_id} displayName={u.display_name} inline nameOnly size="sm" /></LinkButton>
    : '—' }
  const teamCell = (name, r) => { const u = who(name, r); return u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : <span className="text-muted-foreground">—</span> }
  const head = (...cols) => <TableHeader><TableRow>{cols.filter(Boolean).map(([label, num]) => <TableHead key={label} className={num ? TH_NUM : TH}>{label}</TableHead>)}</TableRow></TableHeader>
  return (
    <ModalShell open onClose={onClose} title={`${detail.title} · ${rows.length}`} icon={isDormant ? UserX : isAnom ? ShieldAlert : Users} size="lg">
      <p className={MUTED_SM}>{isDormant ? t('uact.dormantHint') : isActive ? t('uact.kpiLive') : winLabel}</p>
      {rows.length === 0 ? <div className="text-muted-foreground">{isDormant ? t('uact.noDormant') : t('uact.noLogins')}</div> : (
        <DataTable>
          {isActive ? (<>
            {head([t('uact.colUser')], [t('uact.colTeam')], [t('uact.colLoginAt')], [t('uact.colLastTab')], [t('uact.colLocation')])}
            <TableBody>{rows.map((u) => (
              <TableRow key={u.username}>
                <TableCell className={TD}><LinkButton onClick={() => onUser?.(u)}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /></LinkButton></TableCell>
                <TableCell className={TD}>{u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : '—'}</TableCell>
                <TableCell className={cn(TD, MONO_SM)}>{u.login_at ? formatDateSec(u.login_at) : '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs')}>{u.last_tab ? tabLabel(u.last_tab, t) : '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs')}>{u.ip || '—'} <span className="text-muted-foreground">{loc(u)}</span></TableCell>
              </TableRow>
            ))}</TableBody>
          </>) : isDormant ? (<>
            {head([t('uact.colUser')], [t('uact.colRole')], [t('uact.colTeam')], [t('uact.colLastLogin')], [t('uact.colAuthSource')])}
            <TableBody>{rows.map((u) => (
              <TableRow key={u.username}>
                <TableCell className={TD}><LinkButton onClick={() => onUser?.(u)}><UserBadge username={u.username} userId={u.user_id} displayName={u.display_name} /></LinkButton></TableCell>
                <TableCell className={cn(TD, 'text-xs')}>{u.system_role || '—'}</TableCell>
                <TableCell className={TD}>{u.team_name ? <TeamBadge teamName={u.team_name} /> : '—'}</TableCell>
                <TableCell className={cn(TD, 'text-xs')}>{u.last_login_at ? <span title={formatDateSec(u.last_login_at)}>{rel(u.last_login_at)}</span> : <Pill tone="never" status="never">{t('uact.st.never')}</Pill>}</TableCell>
                <TableCell className={cn(TD, 'text-xs')}>{u.auth_source || '—'}</TableCell>
              </TableRow>
            ))}</TableBody>
          </>) : isUsers ? (<>
            {head([t('uact.colUser')], [t('uact.colTeam')], [t('uact.colAuthSource')], [t('uact.colLogins'), true], [t('uact.colLastLogin')])}
            <TableBody>{rows.map((r, i) => (
              <TableRow key={i}>
                <TableCell className={TD}>{userCell(r.username)}</TableCell><TableCell className={TD}>{teamCell(r.username)}</TableCell>
                <TableCell className={cn(TD, 'text-xs')}>{who(r.username).auth_source || '—'}</TableCell>
                <TableCell className={TD_NUM}>{r.logins}</TableCell>
                <TableCell className={cn(TD, MONO_SM)}>{r.last_login ? formatDateSec(r.last_login) : '—'}</TableCell>
              </TableRow>
            ))}</TableBody>
          </>) : (<>
            {head([t('uact.colTime')], [t('uact.colUser')], [t('uact.colTeam')], [t('uact.colIp')], !isAnom && [t('uact.colBrowser')], isAnom && [t('uact.colFlags')], (isFailed || isAnom) && [t('uact.colOutcome')], isFailed && [t('uact.colReason')])}
            <TableBody>{rows.map((r, i) => (
              <TableRow key={i}>
                <TableCell className={cn(TD, MONO_SM)}>{r.time ? formatDateSec(r.time) : '—'}</TableCell>
                <TableCell className={TD}>{userCell(r.actor, r)}</TableCell>
                <TableCell className={TD}>{teamCell(r.actor, r)}</TableCell>
                <TableCell className={cn(TD, MONO_SM)}>{r.ip || '—'} <span className="text-muted-foreground">{loc(r)}</span></TableCell>
                {!isAnom && <TableCell className={cn(TD, 'text-xs')}>{r.user_agent ? `${shortUa(r.user_agent)}${osOf(r.user_agent) ? ' · ' + osOf(r.user_agent) : ''}` : '—'}</TableCell>}
                {isAnom && <TableCell className={TD}>{splitFlags(r.flags).map((f) => <FlagBadge key={f} flag={f}>{t(`uact.anom_${f}`)}</FlagBadge>)}</TableCell>}
                {(isFailed || isAnom) && <TableCell className={cn(TD, 'text-xs')}><Outcome value={r.outcome} /></TableCell>}
                {isFailed && <TableCell className={cn(TD, 'text-xs')}>{r.reason || '—'}</TableCell>}
              </TableRow>
            ))}</TableBody>
          </>)}
        </DataTable>
      )}
    </ModalShell>
  )
}

/** Oturum / kullanıcı detayı: kimlik (gizlilik #14), oturum, giriş geçmişi, kaynak, 30 günlük zaman çizelgesi (#3). */
export function SessionDetailModal({ row, full, isAdmin, globalAdmin, self, activeSet, onClose, onTerminate, onAck, ackBusy, onRefresh, teams = [] }) {
  const t = useT()
  const toast = useToast()
  const u = full || row
  // 2026-09-20: hesap bölümü (oluşturulma, kilit, ünvan/departman, tur), takım üyelikleri, tam kullanıcı kartı + eylemler
  const [fullCard, setFullCard] = useState(false)
  const [tourBusy, setTourBusy] = useState(false)
  const teamList = (teams || []).map((x) => ({ id: x.team_id ?? x.id, name: x.team_name ?? x.name })).filter((x) => x.id != null && x.name)
  const extraTeams = (u.team_ids || []).filter((id) => String(id) !== String(u.team_id ?? ''))
  async function resetTour() {
    if (u.user_id == null) return
    setTourBusy(true)
    try {
      const r = await api.admin.resetUserTour(u.user_id)
      if (r?.success === false) toast.error(r?.error || t('usr.tourResetFailed')); else { toast.success(t('usr.tourResetDone')); onRefresh?.() }
    } catch (e) { toast.error(e?.message || t('usr.tourResetFailed')) } finally { setTourBusy(false) }
  }
  const [timeline, setTimeline] = useState(null)
  const [tlError, setTlError] = useState(false)
  const [revealUa, setRevealUa] = useState(false)
  useEffect(() => {
    let alive = true
    setTimeline(null); setTlError(false)
    api.admin.getUserTimeline(u.username, 20).then((r) => { if (!alive) return; if (r?.success) setTimeline(r.data); else setTlError(true) }).catch(() => { if (alive) setTlError(true) })
    return () => { alive = false }
  }, [u.username])
  const field = (label, value, mono) => <KvField key={label} label={label} value={value} mono={mono} />
  const rel = (iso) => { const r = relTime(iso); return r ? t(`uact.rel.${r.unit}`, r.n) : '—' }
  const dur = (sec) => { const d = splitDuration(sec); return d.h > 0 ? `${d.h} ${t('chg.unitHour')} ${String(d.m).padStart(2, '0')} ${t('chg.unitMin')}` : `${d.m} ${t('chg.unitMin')}` }
  const status = loginStatus(u, activeSet || new Set())
  const isLive = status === 'active'
  return (
    <ModalShell open onClose={onClose} title={u.username} icon={Users} size="lg" scrollBody
      footer={<>
        <Button type="button" variant="secondary" onClick={onClose}>{t('app.dismiss')}</Button>
        {isAdmin && u.user_id != null && <Button type="button" variant="secondary" onClick={() => setFullCard(true)}><UserCog size={14} /> {t('uact.fullCard')}</Button>}
        {isAdmin && <Button type="button" variant="secondary" onClick={() => { onClose?.(); navigateTo('admin', { g_tab: 'users', g_q: u.username }) }}><ExternalLink size={14} /> {t('uact.actOpenAdmin')}</Button>}
        {isAdmin && u.user_id != null && (u.tour_status || 'none') !== 'none' && <Button type="button" variant="secondary" disabled={tourBusy} onClick={resetTour}><Compass size={14} /> {tourBusy ? t('usr.saving') : t('usr.tourReset')}</Button>}
        {isAdmin && isLive && !self && <Button type="button" variant="destructive" onClick={() => onTerminate?.(u.username)}><LogOut size={14} /> {t('uact.terminate')}</Button>}
      </>}>
      {fullCard && <UserDetailPanel user={{ ...u, id: u.user_id, team_ids: u.team_ids || [] }} teams={teamList} isAdmin={globalAdmin} onClose={() => setFullCard(false)} />}
      <div className="mb-2 flex flex-wrap items-center gap-1.5">
        <Pill tone={status} status={status}>{t(`uact.st.${status}`)}</Pill>
        {u.system_role && <SystemRoleBadge role={u.system_role} />}
        {u.org_role && <OrgRoleBadge role={u.org_role}>{t('usr.orgRoleVal.' + u.org_role)}</OrgRoleBadge>}
        {u.auth_source && <AuthSourceBadge source={u.auth_source} localLabel={t('usr.authLocal')} />}
        {u.active === false && <ToneBadge tone="danger">{t('usr.inactive')}</ToneBadge>}
        {u.permanent_lock && <ToneBadge tone="danger">{t('usr.permLocked')}</ToneBadge>}
        {self && <Pill>{t('uact.selfSession')}</Pill>}
      </div>

      <KvSection>{t('uact.detailUser')}</KvSection>
      <div className={KV_GRID}>
        {field(t('uact.colUser'), u.username, true)}
        {field(t('uact.detailDisplayName'), u.display_name)}
        {field(t('uact.detailEmail'), u.email, true)}
        {globalAdmin ? field(t('uact.detailEmployeeId'), u.employee_id, true) : field(t('uact.detailEmployeeId'), t('uact.masked'))}
        {field(t('uact.colRole'), u.system_role)}
        {field(t('uact.detailOrgRole'), u.org_role)}
        {field(t('uact.colTeam'), u.team_name ? <TeamBadge teamId={u.team_id} teamName={u.team_name} /> : null)}
        {field(t('uact.colAuthSource'), u.auth_source)}
        {field(t('uact.detailTitle'), [u.title, u.department].filter(Boolean).join(' · '))}
        {field(t('uact.detailExtraTeams'), extraTeams.length > 0 ? <span className="inline-flex flex-wrap gap-1">{extraTeams.map((id) => <TeamBadge key={id} teamId={id} size={11} />)}</span> : null)}
      </div>

      <KvSection>{t('uact.detailAccount')}</KvSection>
      <div className={KV_GRID}>
        {field(t('uact.colCreated'), u.created_at ? formatDateSec(u.created_at) : '—', true)}
        {field(t('uact.detailAccountState'), [u.active === false ? t('usr.inactive') : t('usr.active'), u.permanent_lock ? t('usr.permLocked') : null].filter(Boolean).join(' · '))}
        {field(t('uact.detailLastSeen'), (u.last_seen || u.last_seen_at) ? `${formatDateSec(u.last_seen || u.last_seen_at)} · ${rel(u.last_seen || u.last_seen_at)}` : '—', true)}
        {field(t('uact.colTour'), <Pill tone={u.tour_status || 'none'} status={`tour-${u.tour_status || 'none'}`}>{t(`uact.tour.${u.tour_status || 'none'}`)}{u.tour_at ? <span className="font-normal opacity-80"> · {formatDateSec(u.tour_at)}</span> : null}</Pill>)}
      </div>

      {isLive && (<>
        <KvSection>{t('uact.detailSession')}</KvSection>
        <div className={KV_GRID}>
          {field(t('uact.colLoginAt'), u.login_at ? formatDateSec(u.login_at) : '—', true)}
          {field(t('uact.colDuration'), dur((u.duration_min || 0) * 60))}
          {field(t('uact.detailLastSeen'), u.last_seen ? `${formatDateSec(u.last_seen)} · ${rel(u.last_seen)}` : '—', true)}
          {field(t('uact.colIdle'), u.idle_sec >= 0 ? dur(u.idle_sec) : '—')}
          {field(t('uact.colExpires'), u.expires_in_sec != null ? dur(u.expires_in_sec) : '—')}
          {field(t('uact.colLastTab'), u.last_tab ? `${tabLabel(u.last_tab, t)}${u.last_tab_at ? ' · ' + rel(u.last_tab_at) : ''}` : '—')}
        </div>
        <KvSection>{t('uact.detailSource')}</KvSection>
        <div className={KV_GRID}>
          {field(t('uact.colIp'), u.ip, true)}
          {field(t('uact.colLocation'), loc(u))}
          {field(t('uact.detailOrg'), u.org)}
          {field(t('uact.colBrowser'), `${shortUa(u.user_agent)}${osOf(u.user_agent) ? ' · ' + osOf(u.user_agent) : ''}`)}
        </div>
        <div className="mt-2.5">
          {revealUa ? <KvField label={t('uact.detailUserAgent')} value={u.user_agent} mono full />
            : (
              <div className="flex flex-col gap-[3px]">
                <span className="text-[10px] font-bold tracking-wide text-muted-foreground">{t('uact.detailUserAgent')}</span>
                <LinkButton className="ml-0 self-start text-xs" onClick={() => setRevealUa(true)}><Eye size={12} /> {t('uact.reveal')}</LinkButton>
              </div>
            )}
        </div>
      </>)}

      <KvSection>{t('uact.detailLoginHistory')}</KvSection>
      <div className={KV_GRID}>
        {field(t('uact.colLastLogin'), u.last_login_at ? formatDateSec(u.last_login_at) : '—', true)}
        {field(t('uact.detailLoginMethod'), u.last_login_method)}
        {field(t('uact.colPrevLogin'), u.prev_login_at ? formatDateSec(u.prev_login_at) : '—', true)}
        {field(t('uact.detailPrevIp'), u.prev_login_ip, true)}
        {field(t('uact.colLastFailed'), u.last_failed_at ? formatDateSec(u.last_failed_at) : '—', true)}
        {field(t('uact.detailFailedIp'), u.last_failed_ip, true)}
        {field(t('uact.colFailedCount'), String(u.failed_since_login ?? 0))}
      </div>

      <KvSection>{t('uact.timelineTitle')}</KvSection>
      {tlError && <AlertBanner tone="warning">{t('uact.loadError')}</AlertBanner>}
      {!timeline && !tlError && <div className={MUTED_SM}>…</div>}
      {timeline && (<>
        <p className={MUTED_SM}>{t('uact.timelineStats', timeline.logins ?? 0, timeline.failed ?? 0, timeline.distinct_ips ?? 0)}</p>
        {(timeline.events || []).length === 0 ? <div className={MUTED_SM}>{t('uact.noEvents')}</div> : (
          <ul className="mt-1.5 flex list-none flex-col border-l-2 border-border pl-3.5">
            {timeline.events.map((e) => {
              const ok = e.outcome === 'SUCCESS'
              return (
                <li key={e.id ?? e.time} data-tl={ok ? 'ok' : 'bad'} data-anom={e.flags ? 'true' : undefined}
                  className={cn('relative flex flex-wrap items-baseline gap-2 py-[5px]', e.flags && 'rounded-md bg-destructive/5')}>
                  <span aria-hidden="true" className={cn('absolute top-2.5 -left-[20px] size-2.5 rounded-full border-2 border-card', ok ? 'bg-success' : 'bg-destructive')} />
                  <span className={MONO_SM}>{e.time ? formatDateSec(e.time) : '—'}</span>
                  <span className="min-w-0 flex-1">
                    <Outcome value={e.outcome} />
                    {e.reason && <span className="text-muted-foreground"> · {e.reason}</span>}
                    <span className={MUTED_SM}> · {e.ip || '—'} {loc(e) !== '—' ? `(${loc(e)})` : ''} · {shortUa(e.user_agent)}</span>
                    {splitFlags(e.flags).map((f) => <FlagBadge key={f} flag={f} title={t(`uact.flagHelp.${f}`)}>{t(`uact.anom_${f}`)}</FlagBadge>)}
                    {e.flags && (e.ack
                      ? <span className="inline-flex items-center gap-1 text-xs text-success"><Check size={12} /> {e.ack.by} · {rel(e.ack.at)}</span>
                      : <LinkButton className="text-xs" disabled={ackBusy === e.id} onClick={() => onAck?.(e, true)}>{t('uact.ack')}</LinkButton>)}
                  </span>
                </li>
              )
            })}
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
        <Button type="button" variant="secondary" disabled={busy} onClick={onClose}>{t('inv.cancel')}</Button>
        <Button type="button" variant="destructive" disabled={busy} onClick={() => onConfirm(reason.trim())}>{busy ? t('uact.terminating') : t('uact.terminate')}</Button>
      </>}>
      <AlertBanner tone="warning">{t('uact.terminateConfirm', target)}</AlertBanner>
      <Field label={t('uact.terminateReason')} hint={t('uact.terminateAudit')} className="mt-3">
        {({ id, describedBy }) => <Input id={id} aria-describedby={describedBy} maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t('uact.terminateReasonPh')} />}
      </Field>
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
        <Button type="button" variant="secondary" disabled={busy} onClick={onClose}>{t('inv.cancel')}</Button>
        <Button type="button" disabled={busy} onClick={() => onConfirm(note.trim())}><Check size={14} /> {t('uact.ack')}</Button>
      </>}>
      <p className={cn(MUTED_SM, 'mb-2')}>{row.actor} · {row.time ? formatDateSec(row.time) : '—'} · {splitFlags(row.flags).map((f) => t(`uact.anom_${f}`)).join(', ')}</p>
      <Field label={t('uact.ackNote')}>
        {({ id, describedBy }) => <Textarea id={id} aria-describedby={describedBy} rows={3} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
    </ModalShell>
  )
}
