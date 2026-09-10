import { useEffect, useState } from 'react'
import { Users, Mail, ShieldAlert } from 'lucide-react'
import ModalShell from './ModalShell.jsx'
import TeamMemberCards from './TeamMemberCards.jsx'
import { LoadingBlock } from './Progress.jsx'
import AlertBanner from './AlertBanner.jsx'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'

/**
 * Takım üyeleri modalı — takım adı tıklanınca her yüzeyden açılır. Varsayılan yükleyici
 * kurum-geneli /api/teams/{id}/members (beyaz-listeli projeksiyon); yönetim ekranı (TeamManager)
 * kendi kapsamlı yükleyicisini ve canManage/onEditUser'ı verir.
 *
 * Üst şerit: lider rozeti, takım e-postası (mailto), üye sayısı. İki sekme: Üyeler / Eskalasyon
 * kişileri (o takımın aktif eskalasyon kontağı — "kime gider?" sorusu tek ekranda).
 */
export default function TeamMembersModal({ team, open, onClose, canManage = false, onEditUser, loadMembers, usersById, managerLabelFor, refreshKey = 0 }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, data: null, error: null })
  const [tab, setTab] = useState('members')

  useEffect(() => {
    if (!open || !team?.id) return
    let alive = true
    setState({ loading: true, data: null, error: null })
    const loader = loadMembers || ((id) => api.teams.members(id))
    Promise.resolve()
      .then(() => loader(team.id))
      .then(res => {
        if (!alive) return
        if (res?.success) setState({ loading: false, data: res.data, error: null })
        else setState({ loading: false, data: null, error: res?.error || t('team.membersLoadError') })
      })
      .catch(e => { if (alive) setState({ loading: false, data: null, error: e?.message || t('team.membersLoadError') }) })
    return () => { alive = false }
  }, [open, team?.id, loadMembers, refreshKey, t])

  if (!open || !team) return null
  const info = state.data?.team || team
  const members = state.data?.members || []
  const contacts = state.data?.escalation_contacts || []
  const leaderName = info.leader_display_name
    || members.find(m => info.leader_id != null && Number(m.id) === Number(info.leader_id))?.display_name
    || null

  return (
    <ModalShell open={open} onClose={onClose} title={t('team.membersModalTitle', team.name || info.name || '')} icon={Users} size="lg" scrollBody>
      <div className="tmm-head">
        {leaderName && <span className="tmm-chip tmm-leader"><Users size={12} /> {t('team.leaderBadge')}: <strong>{leaderName}</strong></span>}
        {info.email && <a className="tmm-chip" href={`mailto:${info.email}`}><Mail size={12} /> {info.email}</a>}
        {!state.loading && <span className="tmm-chip tmm-count">{t('team.membersCount', members.length)}</span>}
      </div>
      <div className="tmm-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'members'} className={`tmm-tab${tab === 'members' ? ' is-active' : ''}`} onClick={() => setTab('members')}>
          <Users size={13} /> {t('team.tabMembers')}
        </button>
        <button type="button" role="tab" aria-selected={tab === 'escalation'} className={`tmm-tab${tab === 'escalation' ? ' is-active' : ''}`} onClick={() => setTab('escalation')}>
          <ShieldAlert size={13} /> {t('team.tabEscalation')}{contacts.length ? ` (${contacts.length})` : ''}
        </button>
      </div>
      {state.error && <AlertBanner tone="danger">{state.error}</AlertBanner>}
      {state.loading ? <LoadingBlock label={t('team.loadingMembers')} size={16} /> : tab === 'members' ? (
        <TeamMemberCards members={members} usersById={usersById} leaderId={info.leader_id}
          canManage={canManage} onSelect={onEditUser} managerLabelFor={managerLabelFor} />
      ) : (
        contacts.length === 0 ? <span className="field-hint">{t('team.noEscalation')}</span> : (
          <table className="health-dbtable tmm-esc-table">
            <thead><tr>
              <th className="dbtcol-th">{t('team.escName')}</th>
              <th className="dbtcol-th">{t('team.escEmail')}</th>
              <th className="dbtcol-th">{t('team.escRole')}</th>
              <th className="dbtcol-th">{t('team.escMinLevel')}</th>
            </tr></thead>
            <tbody>
              {contacts.map(c => (
                <tr key={c.id}>
                  <td>{c.name || '—'}</td>
                  <td>{c.email ? <a href={`mailto:${c.email}`}>{c.email}</a> : '—'}</td>
                  <td>{c.role || '—'}</td>
                  <td>{c.min_alert_level || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}
    </ModalShell>
  )
}
