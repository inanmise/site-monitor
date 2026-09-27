import { useState } from 'react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { KeyRound } from 'lucide-react'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'

/**
 * Admin auto-reset modal. The admin re-proves their own password; the
 * backend then generates a 10-char temporary password, emails it to the
 * target user, and flags the user with must_change_password=true so the
 * next login forces a password change.
 *
 * The plaintext temp password is never shown in the UI — it only travels
 * via email. Çizim: ui/ModalShell (shadcn Dialog) + ui/Field + shadcn Input.
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
    <ModalShell open onClose={onClose} size="sm" icon={KeyRound} title={t('usr.autoResetTitle')}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose}>{t('usr.cancel')}</Button>
          <Button onClick={submit} disabled={saving || !adminPwd} aria-busy={saving || undefined}>
            {saving ? t('usr.saving') : t('usr.autoResetSend')}
          </Button>
        </>
      )}>
      <p className="mb-3 text-sm text-muted-foreground">
        {t('usr.autoResetHint', targetUser.email || '—')}
      </p>
      <Field label={t('usr.pwdAdminConfirm')} required>
        {({ id }) => (
          <Input id={id} type="password" value={adminPwd}
            onChange={(e) => setAdminPwd(e.target.value)}
            autoFocus autoComplete="current-password" />
        )}
      </Field>
      {msg && <AlertBanner tone="danger" className="mt-2">{msg}</AlertBanner>}
    </ModalShell>
  )
}
