import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import { UserCog, ChevronRight, Compass } from 'lucide-react'
import DeviceHistoryPanel from '../DeviceHistoryPanel.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { CheckboxRow } from './SettingsControls.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/** Modal başlık rozetinde kullanıcının LDAP fotoğrafı (shadcn Avatar); yüklenemezse ikona düşer. */
export function ModalHeaderAvatar({ userId, children }) {
  if (!userId) return children
  return (
    <Avatar className="size-10 rounded-[10px]">
      <AvatarImage alt="" src={`/api/users/${userId}/photo`} className="object-cover" />
      <AvatarFallback className="rounded-[10px] bg-primary/10 text-primary">{children}</AvatarFallback>
    </Avatar>
  )
}

/**
 * Edit-only user modal — reused by TeamManager member cards so that an admin
 * can update a user without leaving the Teams tab. Mirrors the edit branch of
 * UserManager.jsx; for "add" use UserManager directly.
 */
export default function UserEditModal({ user, teams, onClose, onSaved, readOnly = false, onEdit }) {
  const t = useT()
  const toast = useToast()
  const [tourBusy, setTourBusy] = useState(false)
  async function resetTour() {
    setTourBusy(true)
    try {
      const r = await api.admin.resetUserTour(user.id)
      if (r?.success === false) toast.error(r?.error || t('usr.tourResetFailed')); else toast.success(t('usr.tourResetDone'))
    } catch (e) { toast.error(e?.message || t('usr.tourResetFailed')) } finally { setTourBusy(false) }
  }
  const [form, setForm] = useState(toForm(user))
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)
  // Cihaz gecmisi KAPALI baslar: panel acilinca iki sorgu atiyor ve bu bilgiye nadiren
  // bakiliyor — her modal acilista sormak herkesin isini yavaslatirdi.
  const [devicesOpen, setDevicesOpen] = useState(false)
  const { canView } = usePermissions()
  const canSeeDevices = canView('audit_log.read')

  useEffect(() => { setForm(toForm(user)); setMsg(null) }, [user])

  if (!user) return null

  async function save() {
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      setMsg(t('usr.emailInvalid'))
      return
    }
    setSaving(true)
    try {
      const payload = {
        username:     form.username.trim(),
        display_name: form.display_name,
        email:        form.email,
        employee_id:  form.employee_id,
        system_role:  form.system_role,
        team_ids:     form.team_ids,
        team_id:      form.team_ids[0] ?? null,
        org_role:     form.org_role || null,
        active:       form.active,
        first_name:    form.first_name,
        last_name:     form.last_name,
        title:         form.title,
        phone:         form.phone,
        department:    form.department,
        company_level: form.company_level,
        mudurluk_name: form.mudurluk_name,
        manager_sicil: form.manager_sicil,
      }
      const res = await api.admin.updateUser(user.id, payload)
      if (res?.success) {
        toast.success(t('usr.saved'))
        onSaved?.(res.data ?? null)
        onClose?.()
      } else {
        setMsg(res?.error || 'Error')
      }
    } finally {
      setSaving(false)
    }
  }

  // 2026-09-10: takım üye kartlarından (TeamMembersModal = ModalShell) açılınca bu pencere üye
  // modalının ÜSTÜNDE kalmalı. ModalShell body'ye portal'lar; üstte açılan pencere sonra eklenir
  // ve aynı katmanda kazanır (iç içe ağaçta ayrıca derinlik başına +10). Escape yalnız en üstteki
  // katmanı kapatır (Radix katman yığını).
  const editable = (key, labelKey) => (
    <Field label={t(labelKey)}>
      {({ id }) => (
        <Input id={id} value={form[key]} disabled={readOnly} onChange={(e) => setForm({ ...form, [key]: e.target.value })} />
      )}
    </Field>
  )
  const teamsMissing = !readOnly && form.system_role !== 'ADMIN' && form.team_ids.length === 0

  return (
    <ModalShell open onClose={onClose} size="md"
      title={(
        <span className="flex items-center gap-2.5">
          <ModalHeaderAvatar userId={user?.id}><UserCog size={20} aria-hidden="true" /></ModalHeaderAvatar>
          {readOnly ? t('usr.viewTitle') : t('usr.editTitle')}
        </span>
      )}
      footer={readOnly ? (
        <>
          <Button variant="secondary" onClick={onClose}>{t('usr.close')}</Button>
          {onEdit && <Button onClick={onEdit}>{t('usr.edit')}</Button>}
        </>
      ) : (
        <>
          <Button variant="secondary" onClick={onClose}>{t('usr.cancel')}</Button>
          <Button onClick={save} aria-busy={saving || undefined}
            disabled={saving || !form.username.trim() || !form.email.trim() || (form.system_role !== 'ADMIN' && form.team_ids.length === 0)}>
            {saving ? t('usr.saving') : t('usr.save')}
          </Button>
        </>
      )}>
      {/* items-start: "Takım" uyarı ipucu altta dururken alanlar karşılıklı hizalı kalsın */}
      <div className="grid grid-cols-1 items-start gap-x-3 sm:grid-cols-2">
        <Field label={t('usr.formUsername')}>
          {({ id }) => <Input id={id} value={form.username} disabled />}
        </Field>
        {editable('display_name', 'usr.formDisplay')}
        <Field label={t('usr.formEmail')} required={!readOnly}>
          {({ id }) => (
            <Input id={id} type="email" value={form.email} disabled={readOnly}
              onChange={(e) => setForm({ ...form, email: e.target.value })} />
          )}
        </Field>
        {editable('employee_id', 'usr.formEmployeeId')}
        <Field label={t('usr.formRole')}>
          {({ id }) => (
            <SearchableSelect id={id}
              value={form.system_role}
              onChange={v => setForm({ ...form, system_role: v })}
              disabled={readOnly}
              options={[
                { value: 'USER',       label: 'USER' },
                { value: 'TEAM_ADMIN', label: 'TEAM_ADMIN' },
                { value: 'AUDIT',      label: 'AUDIT' },
                { value: 'ADMIN',      label: 'ADMIN' },
              ]}
            />
          )}
        </Field>
        <Field label={t('usr.orgRole')}>
          {({ id }) => (
            <SearchableSelect id={id}
              value={form.org_role}
              onChange={v => setForm({ ...form, org_role: v })}
              disabled={readOnly}
              options={[
                { value: '',              label: t('usr.orgRoleNone') },
                { value: 'TECH',          label: t('usr.orgRoleVal.TECH') },
                { value: 'PO',            label: t('usr.orgRoleVal.PO') },
                { value: 'MANAGER',       label: t('usr.orgRoleVal.MANAGER') },
                { value: 'BOLUM_BASKANI', label: t('usr.orgRoleVal.BOLUM_BASKANI') },
                { value: 'CLEVEL',        label: t('usr.orgRoleVal.CLEVEL') },
              ]}
            />
          )}
        </Field>
        <Field label={t('usr.teamsLabel')} required={!readOnly && form.system_role !== 'ADMIN'}
          hint={teamsMissing ? t('usr.teamsRequired') : undefined} hintTone="warn">
          {({ id }) => (
            <MultiTeamSelect id={id}
              value={form.team_ids}
              onChange={ids => setForm({ ...form, team_ids: ids.map(Number) })}
              placeholder={t('usr.teamsPlaceholder')}
              searchThreshold={2}
              disabled={readOnly}
              options={(teams || []).map(team => ({ value: team.id, label: team.name }))}
            />
          )}
        </Field>
        {/* AD'den eşlenen profil alanları (LDAP kullanıcısında bir sonraki login'de tazelenir) */}
        {editable('first_name', 'usr.formFirstName')}
        {editable('last_name', 'usr.formLastName')}
        {editable('title', 'usr.colTitle')}
        {editable('phone', 'usr.colPhone')}
        {editable('department', 'usr.colDept')}
        {editable('company_level', 'usr.formCompanyLevel')}
        {editable('mudurluk_name', 'usr.colMudurluk')}
        {editable('manager_sicil', 'usr.colManager')}
        <CheckboxRow className="col-span-full mb-3.5" checked={!!form.active} disabled={readOnly} label={t('usr.formActive')}
          onChange={(v) => setForm({ ...form, active: v })} />
      </div>
      {/* Ürün turu sıfırlama (2026-09-13): kullanıcı bir sonraki girişte karşılama kartını yeniden görür */}
      {!readOnly && user?.id && (
        <div className="mt-2.5 flex items-center gap-2 text-[0.9em]">
          <Compass size={14} aria-hidden="true" />
          <span>{t('usr.tourLabel')}</span>
          <Button type="button" variant="secondary" size="sm" className="ml-auto" disabled={tourBusy} onClick={resetTour}>{tourBusy ? t('usr.saving') : t('usr.tourReset')}</Button>
        </div>
      )}
      {/* Cihaz Gecmisi (K8) — SALT-OKUNUR. Yetkisi olmayana HIC cizilmez; aksi halde
          bolumu acan kisi 403 alir ve bunu bir hata sanardi. Kapalıyken panel HİÇ çizilmez
          (açılınca iki sorgu atıyor) — Collapsible içeriği kapalıyken DOM'da yok. */}
      {canSeeDevices && user?.id && (
        <Collapsible open={devicesOpen} onOpenChange={setDevicesOpen} className="mt-3.5 border-t pt-3 border-border">
          <CollapsibleTrigger asChild>
            <Button type="button" variant="ghost" size="sm" className="-ml-2 font-semibold">
              <ChevronRight size={15} aria-hidden="true"
                className={cn('text-muted-foreground transition-transform motion-reduce:transition-none', devicesOpen && 'rotate-90')} />
              {t('dev.adminSectionTitle')}
            </Button>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <DeviceHistoryPanel userId={user.id} />
          </CollapsibleContent>
        </Collapsible>
      )}
      {msg && <AlertBanner tone="danger" className="mt-2">{msg}</AlertBanner>}
    </ModalShell>
  )
}

function toForm(user) {
  return {
    username:     user?.username || '',
    display_name: user?.display_name || '',
    email:        user?.email || '',
    employee_id:  user?.employee_id || '',
    system_role:  user?.system_role || 'USER',
    team_ids:     user?.team_ids ?? user?.teamIds ?? (user?.team_id != null ? [user.team_id] : []),
    org_role:     user?.org_role || '',
    active:       user?.active !== false,
    first_name:    user?.first_name || '',
    last_name:     user?.last_name || '',
    title:         user?.title || '',
    phone:         user?.phone || '',
    department:    user?.department || '',
    company_level: user?.company_level || '',
    mudurluk_name: user?.mudurluk_name || '',
    manager_sicil: user?.manager_sicil || '',
  }
}
