import { lazy, Suspense, useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { DEADLINE, LOAD_DEADLINE_MS, withDeadline } from './gapsModel.js'
import { Button } from '@/components/shadcn/button'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/shadcn/dialog'

// Takımlar ekranının mevcut düzeltme pencereleri — aynı sunucu kuralları, ayrı parça (pencere açılışını büyütmesin)
const TeamBulkDefaultGroup = lazy(() => import('../admin/teams/TeamBulkDefaultGroup.jsx'))
const TeamBulkContact = lazy(() => import('../admin/teams/TeamBulkContact.jsx'))
const OnCallScheduleModal = lazy(() => import('../admin/oncall/OnCallScheduleModal.jsx'))

/**
 * Eksikler penceresinden yerinde düzeltme (2026-11-15, E paketi). Eksikler penceresi bu sırada GİZLİDİR (iç içe pencere
 * odak tuzağı çakışmasın); kapanınca `onClose`, bir değişiklik yapıldıysa `onChanged` (çağıran taze okuma yapar).
 * `fix` = { kind: 'contact' | 'group' | 'oncall', team: { id, name } }.
 */
export default function GapsFix({ fix, onClose, onChanged, onNavigate }) {
  const t = useT()
  const [users, setUsers] = useState(null)
  const kind = fix?.kind
  const team = fix?.team

  // Eskalasyon kişisi seçicisi: Takımlar ekranının listesi (/admin/users — çağıranın kapsamına süzülmüş, sunucunun kabul
  // edeceği kişiler); etkin olanlar. Okunamazsa boş liste — pencere "kullanıcı yok" ipucunu verir.
  useEffect(() => {
    if (kind !== 'contact') return undefined
    let alive = true
    // Üst süre: gövde takılsa da en geç LOAD_DEADLINE_MS sonra boş listeyle açılır (kullanıcı beklemede kalmaz)
    Promise.resolve().then(() => withDeadline(api.admin.getUsers(), LOAD_DEADLINE_MS)).then((res) => {
      if (!alive) return
      const list = res !== DEADLINE && res?.success && Array.isArray(res.data) ? res.data : []
      setUsers(list.filter((u) => u && u.active !== false))
    }).catch(() => { if (alive) setUsers([]) })
    return () => { alive = false }
  }, [kind])

  if (!team) return null
  return (
    <Suspense fallback={null}>
      {kind === 'group' && (
        <TeamBulkDefaultGroup open teams={[team]} onClose={onClose} onDone={onChanged}
          onCreateGroup={(teamId) => onNavigate?.({ tab: 'admin', params: { g_tab: 'notifyGroups', g_team: String(teamId) } })} />
      )}
      {kind === 'contact' && users === null && (
        // Kişi listesi okunurken görünür bekleme penceresi (eksikler penceresi gizli; boş ekran bırakılmaz), kapatılabilir
        <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
          <DialogContent data-slot="gaps-fix-loading" className="sm:max-w-sm" aria-describedby={undefined}>
            <DialogHeader>
              <DialogTitle>{t('gaps.inlineFix.contact')}</DialogTitle>
            </DialogHeader>
            <LoadingBlock label={t('gaps.fixLoading')} className="py-6" />
            <DialogFooter>
              <Button type="button" variant="ghost" className="pointer-coarse:min-h-10" onClick={onClose}>{t('gaps.close')}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
      {kind === 'contact' && users !== null && (
        <TeamBulkContact open teams={[team]} users={users} canManage={() => true} onClose={onClose} onDone={onChanged} />
      )}
      {kind === 'oncall' && (
        <OnCallScheduleModal team={team} onClose={onClose} onSaved={onChanged} />
      )}
    </Suspense>
  )
}
