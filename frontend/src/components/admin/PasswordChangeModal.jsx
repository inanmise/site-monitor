import { useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'

// Index by score (0..4). Score 0 is mapped to 'weak' since once a user has
// typed anything, "weak" is more useful than a blank label.
const STRENGTH_LABELS = ['weak', 'weak', 'fair', 'good', 'strong']
const STRENGTH_COLORS = ['#dc2626', '#dc2626', '#f59e0b', '#3b82f6', '#16a34a']

function passwordStrength(pwd) {
  if (!pwd) return 0
  let s = 0
  if (pwd.length >= 6) s++
  if (/[a-z]/.test(pwd) && /[A-Z]/.test(pwd)) s++
  if (/[0-9]/.test(pwd)) s++
  if (/[^a-zA-Z0-9]/.test(pwd)) s++
  return s
}

function PwdStrengthMeter({ pwd, t }) {
  if (!pwd) return null
  const score = passwordStrength(pwd)
  return (
    <div className="pwd-strength">
      <div className="pwd-strength-bars">
        {[1, 2, 3, 4].map(i => (
          <span key={i} className="pwd-strength-bar"
            style={{ background: i <= score ? STRENGTH_COLORS[score] : '#e2e8f0' }} />
        ))}
      </div>
      <span className="pwd-strength-label" style={{ color: STRENGTH_COLORS[score] }}>
        {t(`usr.pwdStrength.${STRENGTH_LABELS[score]}`)}
      </span>
    </div>
  )
}

/**
 * Shared password-change modal.
 *
 * `mode='admin-reset'` — admin rewriting another user's password; verify
 *                        field asks for the admin's own password.
 * `mode='self-change'` — any user changing their own password; verify
 *                        field asks for the user's current password.
 *
 * Both flows go through the same UserService.changePassword(4-arg) overload
 * on the server, so length / history / archive rules are identical.
 */
export default function PasswordChangeModal({ mode, targetUser, onClose, onSuccess }) {
  const t = useT()
  const [verifyPwd, setVerifyPwd]   = useState('')
  const [newPwd, setNewPwd]         = useState('')
  const [confirmPwd, setConfirmPwd] = useState('')
  const [saving, setSaving]         = useState(false)
  const [msg, setMsg]               = useState(null)

  const isSelf       = mode === 'self-change'
  const isForced     = mode === 'forced-change'
  const usesSelfApi  = isSelf || isForced
  const titleKey       = isForced ? 'usr.forcedPwdTitle'     : (isSelf ? 'usr.selfPwdTitle'       : 'usr.pwdTitle')
  const verifyLabelKey = isForced ? 'usr.forcedPwdVerify'    : (isSelf ? 'usr.pwdSelfVerify'      : 'usr.pwdAdminConfirm')
  const verifyHintKey  = isForced ? 'usr.forcedPwdHint'      : (isSelf ? 'usr.pwdSelfVerifyHint'  : 'usr.pwdAdminConfirmHint')
  const wrongVerifyKey = isForced ? 'usr.pwdSelfVerifyWrong' : (isSelf ? 'usr.pwdSelfVerifyWrong' : 'usr.pwdWrongAdmin')

  async function submit() {
    if (!verifyPwd) { setMsg(t('usr.pwdAdminConfirmRequired')); return }
    if (newPwd.length < 6 || newPwd.length > 10) { setMsg(t('usr.pwdLengthRule')); return }
    if (newPwd !== confirmPwd) { setMsg(t('usr.pwdMismatch')); return }
    setSaving(true)
    const res = usesSelfApi
      ? await api.me.changePassword(verifyPwd, newPwd)
      : await api.admin.resetPassword(targetUser.id, newPwd, verifyPwd)
    setSaving(false)
    if (res?.success) {
      onSuccess?.()
      onClose()
    } else {
      const err = res?.error || ''
      if (/Invalid admin password|FORBIDDEN|Current password/i.test(err) || res?.status === 403) {
        setMsg(t(wrongVerifyKey))
      } else if (/recently used/i.test(err)) {
        setMsg(t('usr.pwdReused'))
      } else if (/too short|too long/i.test(err)) {
        setMsg(t('usr.pwdLengthRule'))
      } else {
        setMsg(err || 'Error')
      }
    }
  }

  return (
    <div className="modal-overlay" onClick={isForced ? undefined : onClose}>
      <div className="modal-box" onClick={(e) => e.stopPropagation()}>
        <h3>{t(titleKey, targetUser.username)}</h3>
        <p className="field-hint" style={{ marginTop: -6 }}>{t(verifyHintKey)}</p>
        <div className="form-grid">
          <label className="full-width">
            <span>{t(verifyLabelKey)} <span className="req-star">*</span></span>
            <input type="password" value={verifyPwd}
              onChange={(e) => setVerifyPwd(e.target.value)}
              autoFocus autoComplete="current-password" />
          </label>
          <label className="full-width">
            <span>{t('usr.formNewPwd')} <span className="req-star">*</span></span>
            <input type="password" value={newPwd} maxLength={10}
              onChange={(e) => setNewPwd(e.target.value)} autoComplete="new-password" />
            <PwdStrengthMeter pwd={newPwd} t={t} />
          </label>
          <label className="full-width">
            <span>{t('usr.formConfirmPwd')} <span className="req-star">*</span></span>
            <input type="password" value={confirmPwd} maxLength={10}
              onChange={(e) => setConfirmPwd(e.target.value)} autoComplete="new-password" />
            {confirmPwd && newPwd !== confirmPwd && (
              <span className="field-hint field-hint--warn">{t('usr.pwdMismatch')}</span>
            )}
          </label>
        </div>
        {msg && <div className="alert-msg alert-msg--err" style={{ marginTop: 8 }}>{msg}</div>}
        <div className="modal-actions">
          {!isForced && (
            <button className="btn btn-secondary" onClick={onClose}>{t('usr.cancel')}</button>
          )}
          <button className="btn btn-primary" onClick={submit}
            disabled={saving || !verifyPwd || newPwd.length < 6 || newPwd.length > 10 || newPwd !== confirmPwd}>
            {saving ? t('usr.saving') : t('usr.pwdSave')}
          </button>
        </div>
      </div>
    </div>
  )
}
