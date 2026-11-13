import { useEffect, useMemo, useState } from 'react'
import { UserPlus } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import BulkPreviewDialog from '../bulk/BulkPreviewDialog.jsx'
import { LEVELS, ROLES, contactCreatePlan, summarizeResult } from '../bulk/bulkModel.js'
import { userRefValue } from '../../../utils/userRef'

/**
 * Takımlar → toplu düzeltme: "eskalasyon kişisi ekle" (2026-11-13, yönetim zenginleştirmesi (h)). Bir kullanıcıyı (rol +
 * eşik) seçili takımların HER birine eskalasyon kişisi olarak ekler — "eskalasyon kişisi yok" / "kritik alarmda kimse
 * yok" boşluğunun toplu düzeltmesi. Varsayılan eşik UYARI: tek kişi her seviyeyi karşılar (kapsam matrisinin önerisi).
 *
 * Kişi her takımın KENDİ kaydıdır (eskalasyon kişileri yalnız kendi takımının alarmını alır — 2026-09-28 kuralı): bu bir
 * paylaşım değil, takım başına ayrı satır açar. Aynı kullanıcının o takımda kaydı varsa atlanır (mevcut eşik gösterilir).
 * Kişi listesi okunamazsa önizleme "sunucu denetleyecek" der. Sunucu satır satır karar verir (`/admin/contacts/bulk-create`).
 */
export default function TeamBulkContact({ open, onClose, teams = [], users = [], canManage, onDone }) {
  const t = useT()
  const [contacts, setContacts] = useState(null)
  const [contactsFailed, setContactsFailed] = useState(false)
  const [userId, setUserId] = useState('')
  const [role, setRole] = useState('TECH')
  const [level, setLevel] = useState('WARNING')
  const [fieldErrors, setFieldErrors] = useState({})

  useEffect(() => {
    if (!open) return undefined
    let alive = true
    setUserId(''); setRole('TECH'); setLevel('WARNING'); setFieldErrors({}); setContacts(null); setContactsFailed(false)
    Promise.resolve(api.admin.getContacts()).then((res) => {
      if (!alive) return
      if (res?.success) setContacts(res.data ?? []); else setContactsFailed(true)
    }).catch(() => { if (alive) setContactsFailed(true) })
    return () => { alive = false }
  }, [open])

  const plan = useMemo(() => contactCreatePlan(teams, contacts, userId || null, { canManage, level }),
    [teams, contacts, userId, canManage, level])

  async function confirm() {
    const res = await api.admin.bulkCreateContacts({
      team_ids: teams.map((tm) => tm.id), user_id: userRefValue(userId), role, min_alert_level: level,
    })
    if (res?.success) return summarizeResult(res.data)
    if (['user_id', 'role', 'min_alert_level'].includes(res?.field)) { setFieldErrors({ [res.field]: res.error }); return false }
    throw new Error(res?.error || t('adm.bulk.requestFailed'))
  }

  const levelLabel = (l) => t(`ec.level.${l.toLowerCase()}`)
  const roleLabel = { PO: t('ec.role.po'), TECH: t('ec.role.tech'), MANAGER: t('ec.role.manager'), CLEVEL: t('ec.role.clevel') }
  const clear = (k) => setFieldErrors((prev) => { const n = { ...prev }; delete n[k]; return n })

  return (
    <BulkPreviewDialog open={open} onClose={onClose} title={t('team.bulk.ec.title')} icon={UserPlus}
      description={t('team.bulk.ec.desc')} plan={plan} confirmDisabled={!userId}
      confirmLabel={t('team.bulk.ec.confirm', plan.change + plan.check)} onConfirm={confirm} onFinished={onDone}>
      <div className="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
        <Field label={t('contact.user')} required className="sm:col-span-2" name="user_id" error={fieldErrors.user_id}
          hint={users.length === 0 ? t('team.noUsersHint') : t('team.bulk.ec.userHint')} hintTone={users.length === 0 ? 'warn' : undefined}>
          {({ id }) => (
            <SearchableSelect wrapLabels id={id} value={userId} onChange={(v) => { setUserId(v); clear('user_id') }} searchThreshold={2}
              ariaLabel={t('contact.user')} placeholder={t('contact.selectUser')}
              options={[{ value: '', label: t('contact.selectUser') },
                ...users.map((u) => ({ value: String(u.id), label: `${u.display_name || u.username} (${u.email || u.username})` }))]} />
          )}
        </Field>
        <Field label={t('ec.formRole')} name="role" error={fieldErrors.role}>
          {({ id }) => (
            <SearchableSelect id={id} value={role} onChange={(v) => { setRole(v); clear('role') }} searchThreshold={99}
              ariaLabel={t('ec.formRole')} options={ROLES.map((r) => ({ value: r, label: roleLabel[r] }))} />
          )}
        </Field>
        <Field label={t('ec.formLevel')} name="min_alert_level" error={fieldErrors.min_alert_level} hint={t('team.bulk.ec.levelHint')}>
          {({ id }) => (
            <SearchableSelect id={id} value={level} onChange={(v) => { setLevel(v); clear('min_alert_level') }} searchThreshold={99}
              ariaLabel={t('ec.formLevel')} options={LEVELS.map((l) => ({ value: l, label: levelLabel(l) }))} />
          )}
        </Field>
      </div>
      {contactsFailed && <AlertBanner tone="warning" className="mb-0">{t('team.bulk.ec.contactsUnknown')}</AlertBanner>}
    </BulkPreviewDialog>
  )
}
