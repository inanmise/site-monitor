import { useState, useEffect, useMemo } from 'react'
import { UserCog, Shield, BellRing, Users, History, Mail, KeyRound } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import NotificationGroupHistory from './NotificationGroupHistory.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'

/**
 * Kullanıcı detay kartı (2026-09-20): "bu kişi ne görür, ne alır, son ne yaptı" tek ekranda.
 * Profil + takım üyelikleri + son giriş/kaynak/kilitler + eskalasyon kayıtları + etkin yetkiler
 * (PermissionMatrix'ten role göre çözümlenmiş) + push kararı (birincil takım, YÜKSEK) + son 10 denetim olayı.
 * Yetki/push/denetim ayakları yalnız global ADMIN'e yüklenir (kişisel veri ve global ayar).
 */
export default function UserDetailPanel({ user, teams = [], isAdmin, onClose, onEdit }) {
  const t = useT()
  const [contacts, setContacts] = useState(null)
  const [matrix, setMatrix] = useState(null)
  const [push, setPush] = useState(null)
  const [hist, setHist] = useState(null)
  const [loading, setLoading] = useState(true)

  const teamIds = useMemo(() => {
    const ids = user.team_ids ?? user.teamIds ?? []
    const set = new Set(ids.map(Number))
    if (user.team_id != null) set.add(Number(user.team_id))
    return Array.from(set)
  }, [user])
  const teamMap = useMemo(() => Object.fromEntries((teams || []).map(x => [Number(x.id), x.name])), [teams])

  useEffect(() => {
    let alive = true
    setLoading(true)
    const jobs = [
      api.admin.getContacts().then(r => { if (alive && r?.success) setContacts((r.data || []).filter(c => Number(c.user_id) === Number(user.id))) }).catch(() => {}),
    ]
    if (isAdmin) {
      jobs.push(api.admin.getPermissionMatrix().then(r => { if (alive && r?.success) setMatrix({ catalog: r.catalog || [], grants: r.grants || [] }) }).catch(() => {}))
      jobs.push(api.admin.history('USER', user.id, 10).then(r => { if (alive && r?.success) setHist(r) }).catch(() => {}))
      if (user.team_id != null && api.admin.userPush?.explain) {
        jobs.push(Promise.resolve(api.admin.userPush.explain(user.team_id, 'HIGH'))
          .then(r => { if (!alive || !r?.success) return; const m = (r.members || r.data?.members || []).find(x => x.username === user.username); setPush(m || null) })
          .catch(() => {}))
      }
    }
    Promise.all(jobs).finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [user.id, isAdmin])   // eslint-disable-line react-hooks/exhaustive-deps

  // Etkin yetkiler: rolün izinli (resource, action) çiftleri — matris tanımı üzerinden.
  const perms = useMemo(() => {
    if (!matrix) return null
    const role = user.system_role
    const out = []
    for (const r of matrix.catalog) {
      const acts = (r.actions || []).filter(a => matrix.grants.some(g => g.role === role && g.resource_key === r.resource_key && g.action === a && g.allowed))
      if (acts.length) out.push({ key: r.resource_key, group: r.group, actions: acts })
    }
    return out
  }, [matrix, user.system_role])

  const footer = (
    <>
      <button className="btn btn-secondary" onClick={onClose}>{t('team.close')}</button>
      {onEdit && <button className="btn btn-primary" onClick={onEdit}>{t('usr.edit')}</button>}
    </>
  )
  const label = (k) => { const s = t(`perm.res.${k}`); return s === `perm.res.${k}` ? k : s }

  return (
    <ModalShell open onClose={onClose} title={t('usr.viewTitle')} icon={UserCog} size="lg" footer={footer} scrollBody>
      <div className="udp" data-testid="user-detail">
        <div className="udp-head">
          <UserBadge displayName={user.display_name} username={user.username} email={user.email} userId={user.id} size="md" />
          <div className="udp-badges">
            <span className={`role-badge${user.system_role === 'ADMIN' ? ' role-admin' : ''}`}>{user.system_role}</span>
            {user.org_role && <span className={`badge-role badge-role-${user.org_role}`}>{t('usr.orgRoleVal.' + user.org_role)}</span>}
            <span className={user.active ? 'badge badge-ok' : 'badge badge-err'}>{user.active ? t('usr.active') : t('usr.inactive')}</span>
            <span className="badge" title={t('usr.authSourceTitle')}>{user.auth_source === 'LDAP' ? 'LDAP' : t('usr.authLocal')}</span>
          </div>
        </div>

        <div className="udp-grid">
          <section className="udp-block">
            <h4><KeyRound size={14} /> {t('usr.detailAccess')}</h4>
            <dl className="udp-dl">
              <dt>{t('usr.colLastLogin')}</dt><dd>{user.last_login_at ? `${formatDateSec(user.last_login_at)}${user.last_login_method ? ` · ${user.last_login_method}` : ''}` : t('usr.neverLoggedIn')}</dd>
              <dt>{t('usr.formEmployeeId')}</dt><dd>{user.employee_id || '—'}</dd>
              <dt>{t('usr.colEmail')}</dt><dd>{user.email || '—'}</dd>
              <dt>{t('usr.detailLocks')}</dt>
              <dd>{[user.role_locked && t('usr.lockRole'), user.team_locked && t('usr.lockTeam'), user.org_role_locked && t('usr.lockOrg'), user.permanent_lock && t('usr.permLocked')].filter(Boolean).join(' · ') || '—'}</dd>
            </dl>
          </section>

          <section className="udp-block">
            <h4><Users size={14} /> {t('usr.detailTeams')}</h4>
            {teamIds.length === 0 ? <p className="field-hint">{t('usr.detailNoTeam')}</p> : (
              <div className="udp-teams">
                {teamIds.map(id => (
                  <span key={id} className="udp-team">
                    <TeamBadge teamId={id} teamName={teamMap[id] || `#${id}`} size={12} />
                    {Number(user.team_id) === id && <span className="field-hint">{t('team.memberPrimary')}</span>}
                  </span>
                ))}
              </div>
            )}
          </section>

          <section className="udp-block">
            <h4><Mail size={14} /> {t('usr.detailContacts')}</h4>
            {contacts == null ? <span className="field-hint">…</span> : contacts.length === 0 ? <p className="field-hint">{t('usr.detailNoContacts')}</p> : (
              <ul className="udp-list">
                {contacts.map(c => (
                  <li key={c.id}>
                    <span className="role-badge">{c.role}</span> ≥ {c.min_alert_level}
                    {c.team_id && <> · <TeamBadge teamId={c.team_id} teamName={teamMap[Number(c.team_id)] || `#${c.team_id}`} size={11} /></>}
                    {c.webhook_url && <span className="badge badge-ok">{c.webhook_type}</span>}
                    {!c.active && <span className="badge badge-err">{t('ec.inactive')}</span>}
                  </li>
                ))}
              </ul>
            )}
          </section>

          {isAdmin && (
            <section className="udp-block">
              <h4><BellRing size={14} /> {t('usr.detailPush')}</h4>
              {user.team_id == null ? <p className="field-hint">{t('usr.detailNoTeam')}</p>
                : push == null ? <p className="field-hint">{loading ? '…' : t('usr.detailPushUnknown')}</p>
                : <p><span className={`up-decision up-decision--${push.decision}`}>{t('userpush.decision.' + push.decision)}</span>
                    {push.group && <span className="field-hint"> · {push.group}{push.min_level ? ` · ≥ ${push.min_level}` : ''}</span>}</p>}
            </section>
          )}

          {isAdmin && (
            <section className="udp-block udp-block--wide">
              <h4><Shield size={14} /> {t('usr.detailPerms', user.system_role)}</h4>
              {perms == null ? (loading ? <LoadingBlock label="…" size={14} /> : <p className="field-hint">—</p>) : perms.length === 0 ? <p className="field-hint">{t('usr.detailNoPerms')}</p> : (
                <ul className="udp-perms">
                  {perms.map(p => (
                    <li key={p.key} title={label(p.key) !== p.key ? label(p.key) : ''}><span className="udp-perm-key audit-mono">{p.key}</span> <span className="field-hint">{p.actions.join(' · ')}</span></li>
                  ))}
                </ul>
              )}
            </section>
          )}

          {isAdmin && (
            <section className="udp-block udp-block--wide">
              <h4><History size={14} /> {t('usr.detailHistory')}</h4>
              {hist == null ? (loading ? <LoadingBlock label="…" size={14} /> : <p className="field-hint">—</p>) : (
                <NotificationGroupHistory rows={hist.items || []} truncated={false} hidden={0} loading={false} error={null}
                  fieldPrefix="hist.f" actPrefix="hist.act" nameOf={(r) => r.name || `#${r.resource_id}`} />
              )}
            </section>
          )}
        </div>
      </div>
    </ModalShell>
  )
}
