import { useState, useEffect } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import MultiTeamSelect from '../ui/MultiTeamSelect.jsx'
import { UserCog, ChevronRight } from 'lucide-react'
import DeviceHistoryPanel from '../DeviceHistoryPanel.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'

/** Modal başlık rozetinde kullanıcının LDAP fotoğrafı; yoksa ikona düşer. */
export function ModalHeaderAvatar({ userId, children }) {
  const [err, setErr] = useState(false)
  useEffect(() => { setErr(false) }, [userId])
  if (!userId || err) return children
  return (
    <img className="modal-icon-hdr-photo" alt=""
      src={`/api/users/${userId}/photo`} onError={() => setErr(true)} />
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

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--user">
          <div className="modal-icon-hdr-badge">
            <ModalHeaderAvatar userId={user?.id}><UserCog size={20} /></ModalHeaderAvatar>
          </div>
          <h3>{readOnly ? t('usr.viewTitle') : t('usr.editTitle')}</h3>
        </div>
        {/* form-grid--top: "Takım" uyarı ipucu altta dururken alanlar karşılıklı hizalı kalsın */}
        <div className="form-grid form-grid--top">
          <label>{t('usr.formUsername')}
            <input value={form.username} disabled />
          </label>
          <label>{t('usr.formDisplay')}
            <input value={form.display_name} disabled={readOnly}
              onChange={(e) => setForm({ ...form, display_name: e.target.value })} />
          </label>
          <label>
            <span>{t('usr.formEmail')} {!readOnly && <span className="req-star">*</span>}</span>
            <input type="email" value={form.email} disabled={readOnly}
              onChange={(e) => setForm({ ...form, email: e.target.value })} />
          </label>
          <label>{t('usr.formEmployeeId')}
            <input value={form.employee_id} disabled={readOnly}
              onChange={(e) => setForm({ ...form, employee_id: e.target.value })} />
          </label>
          <label>{t('usr.formRole')}
            <SearchableSelect
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
          </label>
          <label>{t('usr.orgRole')}
            <SearchableSelect
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
          </label>
          <label>
            <span>{t('usr.teamsLabel')} {!readOnly && form.system_role !== 'ADMIN' && <span className="req-star">*</span>}</span>
            <MultiTeamSelect
              value={form.team_ids}
              onChange={ids => setForm({ ...form, team_ids: ids.map(Number) })}
              placeholder={t('usr.teamsPlaceholder')}
              searchThreshold={2}
              disabled={readOnly}
              options={(teams || []).map(team => ({ value: team.id, label: team.name }))}
            />
            {!readOnly && form.system_role !== 'ADMIN' && form.team_ids.length === 0 && (
              <span className="field-hint field-hint--warn">{t('usr.teamsRequired')}</span>
            )}
          </label>
          {/* AD'den eşlenen profil alanları (LDAP kullanıcısında bir sonraki login'de tazelenir) */}
          <label>{t('usr.formFirstName')}
            <input value={form.first_name} disabled={readOnly} onChange={(e) => setForm({ ...form, first_name: e.target.value })} />
          </label>
          <label>{t('usr.formLastName')}
            <input value={form.last_name} disabled={readOnly} onChange={(e) => setForm({ ...form, last_name: e.target.value })} />
          </label>
          <label>{t('usr.colTitle')}
            <input value={form.title} disabled={readOnly} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          </label>
          <label>{t('usr.colPhone')}
            <input value={form.phone} disabled={readOnly} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          </label>
          <label>{t('usr.colDept')}
            <input value={form.department} disabled={readOnly} onChange={(e) => setForm({ ...form, department: e.target.value })} />
          </label>
          <label>{t('usr.formCompanyLevel')}
            <input value={form.company_level} disabled={readOnly} onChange={(e) => setForm({ ...form, company_level: e.target.value })} />
          </label>
          <label>{t('usr.colMudurluk')}
            <input value={form.mudurluk_name} disabled={readOnly} onChange={(e) => setForm({ ...form, mudurluk_name: e.target.value })} />
          </label>
          <label>{t('usr.colManager')}
            <input value={form.manager_sicil} disabled={readOnly} onChange={(e) => setForm({ ...form, manager_sicil: e.target.value })} />
          </label>
          <label className="checkbox-label">
            <input type="checkbox" checked={form.active} disabled={readOnly}
              onChange={(e) => setForm({ ...form, active: e.target.checked })} />
            {t('usr.formActive')}
          </label>
        </div>
        {/* Cihaz Gecmisi (K8) — SALT-OKUNUR. Yetkisi olmayana HIC cizilmez; aksi halde
            bolumu acan kisi 403 alir ve bunu bir hata sanardi. */}
        {canSeeDevices && user?.id && (
          <div className="usr-devices">
            <button type="button" className={`dev-collapse${devicesOpen ? ' is-open' : ''}`}
              aria-expanded={devicesOpen} onClick={() => setDevicesOpen(o => !o)}>
              <ChevronRight size={15} className="dev-collapse-caret" aria-hidden="true" />
              {t('dev.adminSectionTitle')}
            </button>
            {devicesOpen && <DeviceHistoryPanel userId={user.id} />}
          </div>
        )}
        {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
        <div className="modal-actions">
          {readOnly ? (
            <>
              <button className="btn btn-secondary" onClick={onClose}>{t('usr.close')}</button>
              {onEdit && <button className="btn btn-primary" onClick={onEdit}>{t('usr.edit')}</button>}
            </>
          ) : (
            <>
              <button className="btn btn-secondary" onClick={onClose}>{t('usr.cancel')}</button>
              <button className="btn btn-primary" onClick={save}
                disabled={saving || !form.username.trim() || !form.email.trim() || (form.system_role !== 'ADMIN' && form.team_ids.length === 0)}>
                {saving ? t('usr.saving') : t('usr.save')}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
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
