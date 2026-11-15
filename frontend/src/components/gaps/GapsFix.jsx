import { lazy, Suspense, useEffect, useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
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
  const [users, setUsers] = useState(null)        // null = okunuyor; dizi = okundu
  const [usersFailed, setUsersFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const kind = fix?.kind
  const team = fix?.team

  // Eskalasyon kişisi seçicisi: Takımlar ekranının listesi (/admin/users — çağıranın kapsamına süzülmüş, sunucunun kabul
  // edeceği kişiler); etkin olanlar. OKUNAMAZSA (hata, süre, beklenmeyen gövde) BOŞ LİSTE DEĞİL (2026-11-15, prod kapısı:
  // seçici boş açılıp "önce kullanıcı ekleyin" diyordu — okuma hatası "kullanıcı yok" gibi görünüyordu): ayrı hata
  // penceresi + "Yeniden dene".
  useEffect(() => {
    if (kind !== 'contact') return undefined
    let alive = true
    // Üst süre: gövde takılsa da en geç LOAD_DEADLINE_MS sonra hata durumuna geçer (kullanıcı beklemede kalmaz)
    Promise.resolve().then(() => withDeadline(api.admin.getUsers(), LOAD_DEADLINE_MS)).then((res) => {
      if (!alive) return
      if (res === DEADLINE || !res?.success || !Array.isArray(res.data)) { setUsersFailed(true); return }
      setUsers(res.data.filter((u) => u && u.active !== false))
    }).catch(() => { if (alive) setUsersFailed(true) })
    return () => { alive = false }
  }, [kind, attempt])

  const retryUsers = () => { setUsers(null); setUsersFailed(false); setAttempt((a) => a + 1) }

  if (!team) return null
  return (
    <Suspense fallback={null}>
      {kind === 'group' && (
        <TeamBulkDefaultGroup open teams={[team]} onClose={onClose} onDone={onChanged}
          onCreateGroup={(teamId) => onNavigate?.({ tab: 'admin', params: { g_tab: 'notifyGroups', g_team: String(teamId) } })} />
      )}
      {kind === 'contact' && usersFailed && (
        // Okuma hatası "kullanıcı yok" DEĞİLDİR: seçici açılmaz, neden + yeniden deneme (dokunmatikte ≥ 40 px)
        <Dialog open onOpenChange={(o) => { if (!o) onClose() }}>
          <DialogContent data-slot="gaps-fix-users-failed" className="sm:max-w-md" aria-describedby={undefined}>
            <DialogHeader>
              <DialogTitle>{t('gaps.inlineFix.contact')}</DialogTitle>
            </DialogHeader>
            <AlertBanner tone="danger" className="mb-0" title={t('gaps.fixUsersFailedTitle')}>
              {t('gaps.fixUsersFailed')}
            </AlertBanner>
            <DialogFooter>
              <Button type="button" variant="ghost" className="pointer-coarse:min-h-10" onClick={onClose}>{t('gaps.close')}</Button>
              <Button type="button" data-slot="gaps-fix-users-retry" className="pointer-coarse:min-h-10" onClick={retryUsers}>{t('gaps.retry')}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      )}
      {kind === 'contact' && !usersFailed && users === null && (
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
      {kind === 'contact' && !usersFailed && users !== null && (
        <TeamBulkContact open teams={[team]} users={users} canManage={() => true} onClose={onClose} onDone={onChanged} />
      )}
      {kind === 'oncall' && (
        <OnCallScheduleModal team={team} onClose={onClose} onSaved={onChanged} />
      )}
    </Suspense>
  )
}
