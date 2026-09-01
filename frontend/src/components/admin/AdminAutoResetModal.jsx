import { useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { KeyRound } from 'lucide-react'

/**
 * Admin auto-reset modal. The admin re-proves their own password; the
 * backend then generates a 10-char temporary password, emails it to the
 * target user, and flags the user with must_change_password=true so the
 * next login forces a password change.
 *
 * The plaintext temp password is never shown in the UI — it only travels
 * via email.
 */
export default function AdminAutoResetModal({ targetUser, onClose, onSuccess }) {
  const t = useT()
  const [adminPwd, setAdminPwd] = useState('')
  const [saving, setSaving]     = useState(false)
  const [msg, setMsg]           = useState(null)

  if (!targetUser) return null

  async function submit() {
    if (!adminPwd) { setMsg(t('usr.pwdAdminConfirmRequired')); return }
    setSaving(true)
    try {
      const res = await api.admin.autoResetPassword(targetUser.id, adminPwd)
      if (res?.success) {
        onSuccess?.(res.email_status)
        onClose()
      } else {
        const err = res?.error || ''
        if (/Invalid admin password|FORBIDDEN/i.test(err) || res?.status === 403) {
          setMsg(t('usr.pwdWrongAdmin'))
        } else if (/no email/i.test(err)) {
          setMsg(t('usr.autoResetNoEmail'))
        } else {
          setMsg(err || 'Error')
        }
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-box" onClick={(e) => e.stopPropagation()}>
        <div className="modal-icon-hdr modal-icon-hdr--user">
          <div className="modal-icon-hdr-badge"><KeyRound size={20} /></div>
          <h3>{t('usr.autoResetTitle')}</h3>
        </div>
        <p className="field-hint" style={{ marginTop: 0 }}>
          {t('usr.autoResetHint', targetUser.email || '—')}
        </p>
        <div className="form-grid">
          <label className="full-width">
            <span>{t('usr.pwdAdminConfirm')} <span className="req-star">*</span></span>
            <input type="password" value={adminPwd}
              onChange={(e) => setAdminPwd(e.target.value)}
              autoFocus autoComplete="current-password" />
          </label>
        </div>
        {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onClose}>{t('usr.cancel')}</button>
          <button className="btn btn-primary" onClick={submit} disabled={saving || !adminPwd}>
            {saving ? t('usr.saving') : t('usr.autoResetSend')}
          </button>
        </div>
      </div>
    </div>
  )
}
