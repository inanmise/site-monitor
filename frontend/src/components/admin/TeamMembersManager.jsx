import { useState, useEffect, useMemo } from 'react'
import { UserPlus, UserMinus, Users } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Takım üyelerini tek yerden yönet (2026-09-20): listele, ekle, çıkar. Eskiden üyelik yalnız kullanıcı
 * formundan (takım kutusu) değişiyordu — "bu takıma kimler üye, birini ekle" için kullanıcı kullanıcı
 * gezmek gerekiyordu. Sunucu: POST/DELETE /teams/{id}/members (kullanıcının son takımı çıkarılamaz).
 */
export default function TeamMembersManager({ team, users = [], canManage, onClose, onChanged }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [members, setMembers] = useState(null)
  const [error, setError] = useState(null)
  const [pick, setPick] = useState('')
  const [busy, setBusy] = useState(false)

  async function load() {
    try {
      const res = await api.admin.getTeamUsers(team.id)
      if (res?.success) { setMembers(res.data || []); setError(null) }
      else setError(res?.error || t('team.membersLoadError'))
    } catch (e) { setError(e?.message || t('team.membersLoadError')) }
  }
  useEffect(() => { load() }, [team.id])   // eslint-disable-line react-hooks/exhaustive-deps

  const memberIds = useMemo(() => new Set((members || []).map(m => m.id)), [members])
  const candidates = useMemo(() => users.filter(u => !memberIds.has(u.id) && u.active !== false), [users, memberIds])

  async function add() {
    if (!pick) return
    setBusy(true)
    try {
      const res = await api.admin.addTeamMember(team.id, Number(pick))
      if (res?.success) { toast.success(t('team.memberAdded')); setPick(''); await load(); onChanged?.() }
      else toast.error(res?.error || 'Error')
    } finally { setBusy(false) }
  }

  async function remove(u) {
    const ok = await showConfirm({
      title: t('team.memberRemoveTitle'),
      message: t('team.memberRemoveMsg', u.display_name || u.username, team.name),
      confirmText: t('team.memberRemove'),
      variant: 'danger',
    })
    if (!ok) return
    setBusy(true)
    try {
      const res = await api.admin.removeTeamMember(team.id, u.id)
      if (res?.success) { toast.success(t('team.memberRemoved')); await load(); onChanged?.() }
      else toast.error(res?.error || 'Error')
    } finally { setBusy(false) }
  }

  return (
    <ModalShell open onClose={onClose} title={t('team.membersTitle', team.name)} icon={Users} size="md" busy={busy}
      footer={<Button variant="secondary" onClick={onClose}>{t('team.close')}</Button>}>
      {error && <AlertBanner tone="danger" role="alert">{error}</AlertBanner>}
      {members == null && !error && <LoadingBlock label={t('team.membersLoading')} size={16} />}
      {members && (
        <>
          {canManage && (
            <div className="tmm-add">
              <SearchableSelect value={pick} onChange={setPick} placeholder={t('team.memberPick')} ariaLabel={t('team.memberPick')}
                searchThreshold={2}
                options={[{ value: '', label: t('team.memberPick') }, ...candidates.map(u => ({ value: String(u.id), label: `${u.display_name || u.username} (${u.username})` }))]} />
              <Button onClick={add} disabled={!pick || busy}><UserPlus size={14} /> {t('team.memberAdd')}</Button>
            </div>
          )}
          {members.length === 0 ? <p className="field-hint">{t('team.membersEmpty')}</p> : (
            <ul className="tmm-list" data-testid="team-members">
              {members.map(u => (
                <li key={u.id} className="tmm-row">
                  <UserBadge displayName={u.display_name} username={u.username} email={u.email} inline size="sm" />
                  <span className="tmm-meta">
                    {u.system_role && <span className="role-badge">{u.system_role}</span>}
                    {u.team_id === team.id && <span className="field-hint">{t('team.memberPrimary')}</span>}
                  </span>
                  {canManage && (
                    <Button variant="secondary" size="sm" className="tmm-remove" onClick={() => remove(u)} disabled={busy}
                      title={t('team.memberRemove')}
                      aria-label={`${u.display_name || u.username} — ${t('team.memberRemove')}`}><UserMinus size={13} /></Button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </ModalShell>
  )
}
