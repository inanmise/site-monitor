import { useEffect, useState } from 'react'
import { useT } from '../../../i18n/index.jsx'
import { api } from '../../../api/client'
import SearchableSelect from '../../ui/SearchableSelect.jsx'
import TagInput from '../../ui/TagInput.jsx'
import NotificationGroupSelect from '../../ui/NotificationGroupSelect.jsx'
import NocNotifyField from '../../noc/forms/NocNotifyField.jsx'
import { FormField, FormGrid, FormSection, LabelSlot } from '../../monitoring/MonitorForm.jsx'
import { Input } from '@/components/shadcn/input'

/**
 * Sihirbazın "Yeni takip kaydı" envanter alanları (2026-10-06) — Envanter ekleme formuyla (InventoryFormModal) AYNI
 * kurallar ve AYNI paylaşılan seçiciler: takım (zorunlu), grup (zorunlu; takımın "cert" grupları, yeni ad yazılabilir),
 * etiketler (zorunlu; takımın kullandığı etiketler öneri), kritiklik katmanı, açıklama, sahip, platform (+ ayrıntı),
 * bildirim grubu ve 7/24 izleme ekibi. Ağa özgü alanlar (port, vekil, TLS kipi, zaman aşımı, sıklık) YOK.
 *
 * Doğrulama alanın altında (`fe.fieldProps`, hooks/useFormErrors); takım listesi çağırandan (USER = üyesi olduğu
 * takımlar). Tek takımı olan kullanıcıda takım kendiliğinden seçilir.
 */
export default function TrackingFields({ form, onField, teams, fe, canOpenSettings = false }) {
  const t = useT()
  const [teamGroups, setTeamGroups] = useState([])
  const [teamTags, setTeamTags] = useState([])
  const [platforms, setPlatforms] = useState([])

  useEffect(() => {
    let alive = true
    Promise.resolve(api.admin?.listPlatforms?.())
      .then((r) => { if (alive) setPlatforms(r?.success ? (r.data || []) : []) })
      .catch(() => { if (alive) setPlatforms([]) })
    return () => { alive = false }
  }, [])

  // Tek takımlı kullanıcı: kayıt o takımla açılır.
  useEffect(() => {
    if (!form.team_id && Array.isArray(teams) && teams.length === 1) onField('team_id', String(teams[0].id))
  }, [teams]) // eslint-disable-line react-hooks/exhaustive-deps -- yalnız takım listesi gelince

  useEffect(() => {
    if (!form.team_id) { setTeamGroups([]); setTeamTags([]); return undefined }
    let alive = true
    Promise.resolve(api.monitoring?.listGroups?.(form.team_id, 'cert'))
      .then((r) => { if (alive) setTeamGroups(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamGroups([]) })
    Promise.resolve(api.monitoring?.listTags?.(form.team_id))
      .then((r) => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [form.team_id])

  const set = (k) => (v) => { onField(k, v); fe.clear(k) }

  return (
    <FormGrid>
      <FormField label={t('inv.formTeam')} required {...fe.fieldProps('team_id')}>
        {({ id }) => (
          <SearchableSelect id={id} value={form.team_id} onChange={set('team_id')} placeholder={t('inv.selectTeam')} searchThreshold={2}
            options={[{ value: '', label: t('inv.selectTeam') }, ...(teams || []).map((tm) => ({ value: tm.id, label: tm.name }))]} />
        )}
      </FormField>

      <FormField label={t('inv.formTier')}>
        {({ id }) => (
          <SearchableSelect id={id} value={form.tier ?? ''} onChange={(v) => onField('tier', v ? Number(v) : null)}
            options={[
              { value: '', label: t('inv.tierNone') },
              { value: '1', label: t('inv.tier1') }, { value: '2', label: t('inv.tier2') },
              { value: '3', label: t('inv.tier3') }, { value: '4', label: t('inv.tier4') },
            ]} />
        )}
      </FormField>

      <FormField label={t('inv.formGroup')} required {...fe.fieldProps('group_name')}>
        {({ id }) => (
          <SearchableSelect id={id} value={form.group_name} onChange={set('group_name')} placeholder={t('inv.noGroup')}
            disabled={!form.team_id} creatable onCreate={() => {}} searchThreshold={2}
            options={[{ value: '', label: t('inv.noGroup') }, ...teamGroups.map((g) => ({ value: g.name, label: g.name }))]} />
        )}
      </FormField>

      {/* NotificationGroupSelect kendi <label>'ını çizer — LabelSlot ona dikey alan düzenini verir. */}
      <LabelSlot>
        <NotificationGroupSelect teamId={form.team_id} value={form.notification_group_id} onChange={(v) => onField('notification_group_id', v)} />
      </LabelSlot>

      <FormSection title={t('inv.formTags')} required hint={t('inv.tagsHint')} {...fe.fieldProps('tags')}>
        <TagInput value={form.tags} onChange={set('tags')} placeholder={t('mon.tagsPlaceholder')} suggestions={teamTags} />
      </FormSection>

      <FormField label={t('inv.formDesc')} full>
        {({ id }) => (
          <Input id={id} value={form.description} maxLength={500} placeholder={t('mcert.track.descPh')}
            onChange={(e) => onField('description', e.target.value)} />
        )}
      </FormField>

      <FormField label={t('mcert.track.owner')} hint={t('mcert.track.ownerHint')}>
        {({ id, describedBy }) => (
          <Input id={id} aria-describedby={describedBy} value={form.owner} maxLength={200} autoComplete="off"
            onChange={(e) => onField('owner', e.target.value)} />
        )}
      </FormField>

      <FormField label={t('inv.formPlatform')} hint={t('inv.formPlatformHint')}>
        {({ id, describedBy }) => (
          <div className="flex min-w-0 flex-col gap-1.5">
            <SearchableSelect id={id} value={form.platform || ''} onChange={(v) => onField('platform', v)} searchThreshold={6}
              options={[{ value: '', label: t('inv.platformNone') },
                ...platforms.map((p) => ({ value: p.code, label: p.name, title: p.description || undefined, hint: p.description || undefined }))]} />
            <Input className="h-8" value={form.platform_detail} maxLength={160} placeholder={t('inv.formPlatformDetailPh')}
              aria-label={t('inv.formPlatformDetail')} aria-describedby={describedBy}
              onChange={(e) => onField('platform_detail', e.target.value)} />
          </div>
        )}
      </FormField>

      <NocNotifyField type="SSL" checked={form.noc_notify} groupIds={form.noc_group_ids} canOpenSettings={canOpenSettings}
        onChange={(patch) => {
          if ('nocNotify' in patch) onField('noc_notify', patch.nocNotify)
          if ('nocGroupIds' in patch) onField('noc_group_ids', patch.nocGroupIds)
        }} />
    </FormGrid>
  )
}
