import { useMemo, useState } from 'react'
import { FolderInput, Star, Trash2 } from 'lucide-react'
import { api } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import Field from '../../ui/Field.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import BulkSelectionBar, { BulkButton } from './BulkSelectionBar.jsx'
import BulkPreviewDialog from './BulkPreviewDialog.jsx'
import { planGroups, summarizeResult } from './bulkModel.js'

const ICONS = { delete: Trash2, set_team: FolderInput, make_default: Star }

/**
 * Bildirim grupları — toplu işlem şeridi + önizleme penceresi (2026-11-13, yönetim zenginleştirmesi (d)).
 *
 * Eylemler: kalıcı sil (yalnız KULLANILMAYAN ve varsayılan OLMAYAN gruplar), takıma taşı (kullanılmayan, varsayılan
 * olmayan, aynı adın hedefte olmadığı gruplar), varsayılan yap (takım başına bir grup). Etkinleştir / pasifleştir YOK:
 * `active=false` eski yumuşak silmenin izidir, "grubu kapat" anlamı yoktur (sunucu da kabul etmez).
 *
 * Önizleme kullanım sayılarını (`usageCounts`) kullanır; sayılar okunamadıysa satır "sunucu denetleyecek" olur —
 * "kullanılmıyor" diye silmeye yönlendirmez. Sunucu silmeden / taşımadan hemen önce kullanımı yeniden denetler.
 */
export default function GroupBulkActions({ selected = [], groups = [], counts = null, onClear, onDone, teamOptions = [], teamNameOf }) {
  const t = useT()
  const [action, setAction] = useState(null)
  const [teamId, setTeamId] = useState('')
  const [fieldError, setFieldError] = useState(null)

  const open = (a) => { setAction(a); setTeamId(''); setFieldError(null) }
  const plan = useMemo(() => action ? planGroups(selected, action, { teamId, counts, groups }) : null,
    [selected, action, teamId, counts, groups])

  async function confirm() {
    const body = { action, ids: selected.map((g) => g.id) }
    if (action === 'set_team') body.team_id = Number(teamId)
    const res = await api.notificationGroups.bulk(body)
    if (res?.success) return summarizeResult(res.data)
    if (res?.field === 'team_id') { setFieldError(res.error); return false }
    throw new Error(res?.error || t('adm.bulk.requestFailed'))
  }

  const titles = { delete: t('ng.bulk.deleteTitle'), set_team: t('ng.bulk.teamTitle'), make_default: t('ng.bulk.defaultTitle') }
  const descs = { delete: t('ng.bulk.deleteDesc'), set_team: t('ng.bulk.teamDesc'), make_default: t('ng.bulk.defaultDesc') }

  return (
    <>
      <BulkSelectionBar count={selected.length} onClear={onClear} slot="ng-bulk-bar">
        <BulkButton variant="destructive" onClick={() => open('delete')} data-action="delete"><Trash2 aria-hidden="true" /> {t('ng.bulk.delete')}</BulkButton>
        {teamOptions.length > 0 && (
          <BulkButton onClick={() => open('set_team')} data-action="set_team"><FolderInput aria-hidden="true" /> {t('ng.bulk.setTeam')}</BulkButton>
        )}
        <BulkButton onClick={() => open('make_default')} data-action="make_default"><Star aria-hidden="true" /> {t('ng.bulk.makeDefault')}</BulkButton>
      </BulkSelectionBar>

      <BulkPreviewDialog open={!!action} onClose={() => setAction(null)} title={titles[action] || ''} icon={ICONS[action]}
        description={descs[action]} plan={plan} destructive={action === 'delete'}
        confirmLabel={t(action === 'delete' ? 'ng.bulk.confirmDelete' : 'ng.bulk.confirm', plan ? plan.change + plan.check : 0)}
        confirmDisabled={action === 'set_team' && !teamId}
        onConfirm={confirm} onFinished={() => { onClear?.(); onDone?.() }} teamNameOf={teamNameOf}>
        {action === 'delete' && <AlertBanner tone="warning" className="mb-0">{t('ng.bulk.deleteWarn')}</AlertBanner>}
        {action === 'set_team' && (
          <Field label={t('ng.bulk.targetTeam')} required hint={t('ng.bulk.teamHint')} error={fieldError || undefined} name="team_id">
            {({ id }) => (
              <SearchableSelect wrapLabels id={id} value={teamId} onChange={(v) => { setTeamId(v); setFieldError(null) }} searchThreshold={4}
                ariaLabel={t('ng.bulk.targetTeam')} placeholder={t('ng.pickTeam')}
                options={teamOptions.map((tm) => ({ value: String(tm.id), label: tm.name }))} />
            )}
          </Field>
        )}
      </BulkPreviewDialog>
    </>
  )
}
