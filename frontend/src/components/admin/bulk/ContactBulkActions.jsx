import { useMemo, useState } from 'react'
import { BellRing, CircleCheck, CirclePause, Users } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import Field from '../../ui/Field.jsx'
import BulkSelectionBar, { BulkButton } from './BulkSelectionBar.jsx'
import BulkPreviewDialog from './BulkPreviewDialog.jsx'
import { LEVELS, planContacts, summarizeResult } from './bulkModel.js'

const ICONS = { activate: CircleCheck, deactivate: CirclePause, set_level: BellRing, set_team: Users }

/**
 * Eskalasyon kişileri — toplu işlem şeridi + önizleme penceresi (2026-11-13, yönetim zenginleştirmesi (d)).
 * Eylemler: etkinleştir · pasifleştir · seviye ata · takıma ata (takım seçimi yalnız ADMIN rolüne — tekil formdaki
 * `teamPickable` kuralı; TEAM_ADMIN kişiyi taşıyamaz, sunucu da reddeder). Onay penceresi seçimin tahmini sonucunu
 * (değişecek / atlanacak + neden / sunucu denetleyecek) listeler; sonuç sunucunun satır satır yanıtıdır.
 *
 * @param selected   seçili kişi kayıtları (görünen listeden)
 * @param canManage  (teamId) → true | false | null — `bulkModel.manageScope`
 * @param teamOptions taşıma hedefi olabilecek takımlar [{ id, name }] (yönetim kapsamı süzülmüş)
 */
export default function ContactBulkActions({ selected = [], onClear, onDone, canManage, isAdmin = false, teamOptions = [], teamNameOf, capped = false }) {
  const t = useT()
  const [action, setAction] = useState(null)
  const [level, setLevel] = useState('')
  const [teamId, setTeamId] = useState('')
  const [fieldError, setFieldError] = useState(null)

  const open = (a) => { setAction(a); setLevel(''); setTeamId(''); setFieldError(null) }
  const plan = useMemo(() => action ? planContacts(selected, action, { level, teamId, canManage }) : null,
    [selected, action, level, teamId, canManage])
  const needsParam = action === 'set_level' ? !level : action === 'set_team' ? !teamId : false

  async function confirm() {
    const body = { action, ids: selected.map((c) => c.id) }
    if (action === 'set_level') body.min_alert_level = level
    if (action === 'set_team') body.team_id = Number(teamId)
    const res = await api.admin.bulkContacts(body)
    if (res?.success) return summarizeResult(res.data)
    if (res?.field === 'min_alert_level' || res?.field === 'team_id') { setFieldError({ field: res.field, message: res.error }); return false }
    throw new Error(res?.error || t('adm.bulk.requestFailed'))
  }

  const levelLabel = (l) => t(`ec.level.${l.toLowerCase()}`)
  const titles = {
    activate: t('ec.bulk.activateTitle'), deactivate: t('ec.bulk.deactivateTitle'),
    set_level: t('ec.bulk.levelTitle'), set_team: t('ec.bulk.teamTitle'),
  }
  const descs = {
    activate: t('ec.bulk.activateDesc'), deactivate: t('ec.bulk.deactivateDesc'),
    set_level: t('ec.bulk.levelDesc'), set_team: t('ec.bulk.teamDesc'),
  }

  return (
    <>
      <BulkSelectionBar count={selected.length} onClear={onClear} slot="ec-bulk-bar" capped={capped}>
        <BulkButton onClick={() => open('activate')} data-action="activate"><CircleCheck aria-hidden="true" /> {t('ec.bulk.activate')}</BulkButton>
        <BulkButton onClick={() => open('deactivate')} data-action="deactivate"><CirclePause aria-hidden="true" /> {t('ec.bulk.deactivate')}</BulkButton>
        <BulkButton onClick={() => open('set_level')} data-action="set_level"><BellRing aria-hidden="true" /> {t('ec.bulk.setLevel')}</BulkButton>
        {isAdmin && teamOptions.length > 0 && (
          <BulkButton onClick={() => open('set_team')} data-action="set_team"><Users aria-hidden="true" /> {t('ec.bulk.setTeam')}</BulkButton>
        )}
      </BulkSelectionBar>

      <BulkPreviewDialog open={!!action} onClose={() => setAction(null)} title={titles[action] || ''} icon={ICONS[action]}
        description={descs[action]} plan={plan} destructive={action === 'deactivate'}
        confirmLabel={t('ec.bulk.confirm', plan ? plan.change + plan.check : 0)} confirmDisabled={needsParam}
        onConfirm={confirm} onFinished={() => { onClear?.(); onDone?.() }} teamNameOf={teamNameOf}>
        {action === 'set_level' && (
          <Field label={t('ec.formLevel')} required error={fieldError?.field === 'min_alert_level' ? fieldError.message : undefined}
            name="min_alert_level">
            {({ id }) => (
              <SearchableSelect id={id} value={level} onChange={(v) => { setLevel(v); setFieldError(null) }} searchThreshold={99}
                ariaLabel={t('ec.formLevel')} placeholder={t('ec.bulk.pickLevel')}
                options={LEVELS.map((l) => ({ value: l, label: levelLabel(l) }))} />
            )}
          </Field>
        )}
        {action === 'set_team' && (
          <Field label={t('ec.formTeam')} required hint={t('ec.bulk.teamHint')}
            error={fieldError?.field === 'team_id' ? fieldError.message : undefined} name="team_id">
            {({ id }) => (
              <SearchableSelect wrapLabels id={id} value={teamId} onChange={(v) => { setTeamId(v); setFieldError(null) }} searchThreshold={4}
                ariaLabel={t('ec.formTeam')} placeholder={t('ec.pickTeam')}
                options={teamOptions.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
            )}
          </Field>
        )}
      </BulkPreviewDialog>
    </>
  )
}
