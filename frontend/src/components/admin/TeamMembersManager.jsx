import { useState, useEffect, useMemo } from 'react'
import { UserPlus, UserMinus, Users, UserX, Lock } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { SystemRoleBadge } from './ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { userRefValue } from '../../utils/userRef'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'

/** ADMIN / AUDIT hesabının takımlarına yalnız GLOBAL yönetici dokunur (sunucu `requireCanAdministerTarget`, AUTH-6). */
const GLOBAL_ONLY_ROLES = new Set(['ADMIN', 'AUDIT'])

/**
 * Takım üyelerini tek yerden yönet (2026-09-20): listele, ekle, çıkar. Eskiden üyelik yalnız kullanıcı
 * formundan (takım kutusu) değişiyordu — "bu takıma kimler üye, birini ekle" için kullanıcı kullanıcı
 * gezmek gerekiyordu. Sunucu: POST/DELETE /teams/{id}/members (kullanıcının son takımı çıkarılamaz).
 */
export default function TeamMembersManager({ team, users = [], canManage, globalAdmin, onClose, onChanged }) {
  const t = useT()
  const { scope } = usePermissions()
  // Global yönetici mi (K1): prop verilmişse o, yoksa izin anlık görüntüsünün scope.global_admin'i; bilinmiyor = değil
  const isGlobal = globalAdmin === undefined ? scope?.global_admin === true : !!globalAdmin
  // Sunucunun reddedeceği hedef (2026-10-22, A3): global olmayan yönetici ADMIN / AUDIT hesabını ekleyemez / çıkaramaz
  const adminOnly = (u) => !isGlobal && GLOBAL_ONLY_ROLES.has(String(u?.system_role || '').toUpperCase())
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
  // Pasif üyeler SONDA ve belirgin "Pasif" rozetiyle (20.166, UIA-A7 — CLAUDE.md "Team member lists show passive members
  // last"): takım eksikleri "pasif üyesi var" diye buraya yönlendiriyor; kimin çıkarılacağı görünsün. Sıra kararlı.
  const ordered = useMemo(() => (members || []).map((m, i) => [m, i])
    .sort((a, b) => ((a[0].active === false) - (b[0].active === false)) || (a[1] - b[1])).map(([m]) => m), [members])
  const candidates = useMemo(() => users.filter(u => !memberIds.has(u.id) && u.active !== false && !adminOnly(u)),
    [users, memberIds, isGlobal])   // eslint-disable-line react-hooks/exhaustive-deps

  async function add() {
    if (!pick) return
    setBusy(true)
    try {
      const res = await api.admin.addTeamMember(team.id, userRefValue(pick))
      if (res?.success) { toast.success(t('team.memberAdded')); setPick(''); await load(); onChanged?.() }
      else toast.error(res?.error || 'Error')
    } catch (e) { toast.error(e?.message || t('team.saveError')) } finally { setBusy(false) }
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
    } catch (e) { toast.error(e?.message || t('team.saveError')) } finally { setBusy(false) }
  }

  return (
    <ModalShell open onClose={onClose} title={t('team.membersTitle', team.name)} icon={Users} size="md" busy={busy}
      footer={<Button variant="secondary" onClick={onClose}>{t('team.close')}</Button>}>
      {error && <AlertBanner tone="danger" role="alert">{error}</AlertBanner>}
      {members == null && !error && <LoadingBlock label={t('team.membersLoading')} size={16} />}
      {members && (
        <>
          {canManage && (
            <div className="mb-3 flex items-center gap-2">
              <span className="min-w-0 flex-auto">
                <SearchableSelect value={pick} onChange={setPick} placeholder={t('team.memberPick')} ariaLabel={t('team.memberPick')}
                  searchThreshold={2}
                  options={[{ value: '', label: t('team.memberPick') }, ...candidates.map(u => ({ value: String(u.id), label: `${u.display_name || u.username} (${u.username})` }))]} />
              </span>
              <Button onClick={add} disabled={!pick || busy}><UserPlus size={14} /> {t('team.memberAdd')}</Button>
            </div>
          )}
          {canManage && !isGlobal && (members.some(adminOnly) || users.some((u) => !memberIds.has(u.id) && u.active !== false && adminOnly(u))) && (
            <p className="-mt-1 mb-3 text-xs text-muted-foreground" data-slot="team-member-admin-hint">{t('team.memberAdminOnly')}</p>
          )}
          {members.length === 0 ? <p className="text-xs text-muted-foreground">{t('team.membersEmpty')}</p> : (
            <ul className="flex list-none flex-col gap-1.5" data-testid="team-members">
              {ordered.map(u => (
                <li key={u.id} data-inactive={u.active === false ? 'true' : undefined}
                  className="flex flex-wrap items-center gap-2.5 rounded-lg border px-2 py-1.5 border-border">
                  <UserBadge displayName={u.display_name} username={u.username} email={u.email} inline size="sm" />
                  {u.active === false && (
                    <Badge variant="destructive" data-slot="team-member-inactive" title={t('team.memberInactiveTip')}>
                      <UserX aria-hidden="true" /> {t('team.memberInactive')}
                    </Badge>
                  )}
                  <span className="ml-auto inline-flex items-center gap-1.5">
                    {u.system_role && <SystemRoleBadge role={u.system_role} />}
                    {u.team_id === team.id && <span className="text-xs text-muted-foreground">{t('team.memberPrimary')}</span>}
                  </span>
                  {canManage && adminOnly(u) && (
                    <span data-slot="team-member-admin-locked" title={t('team.memberAdminOnly')}
                      className="inline-flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                      <Lock aria-hidden="true" className="size-3.5" />{t('team.memberAdminLocked')}
                    </span>
                  )}
                  {canManage && !adminOnly(u) && (
                    <SimpleTooltip content={t('team.memberRemove')}>
                      <Button variant="secondary" size="icon-sm" className="shrink-0" onClick={() => remove(u)} disabled={busy}
                        aria-label={t('a11y.rowAction', u.display_name || u.username, t('team.memberRemove'))}><UserMinus size={13} /></Button>
                    </SimpleTooltip>
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
