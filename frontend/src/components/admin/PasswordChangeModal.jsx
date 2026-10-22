import { useId, useState } from 'react'
import { KeyRound } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'

/// Index by score (0..4). Score 0 is mapped to 'weak' since once a user has
// typed anything, "weak" is more useful than a blank label.
const STRENGTH_LABELS = ['weak', 'weak', 'fair', 'good', 'strong']
const STRENGTH_BG = ['bg-destructive', 'bg-destructive', 'bg-amber-500', 'bg-blue-500', 'bg-success']
const STRENGTH_INK = ['text-destructive', 'text-destructive', 'text-amber-600 dark:text-amber-400', 'text-blue-600 dark:text-blue-400', 'text-success']

/**
 * Parola uzunluk kuralı — sunucunun VARSAYILANLARIYLA aynı (`site.monitor.password.min-length` 6 / `max-length` 64,
 * application.properties; kapı: passwordLengthRule.test.js). Eskiden istemci 6–10 diyor ve alanlara `maxLength={10}`
 * koyuyordu: yapıştırılan / parola yöneticisinin ürettiği 12+ karakterlik parola iki alanda da SESSİZCE 10 karaktere
 * kesiliyor, eşleşiyor ve sunucuya ilk 10 karakter yazılıyordu (sonraki giriş tam parolayla başarısız — UIA-H1). Kurulum
 * sınırı değiştirdiyse sunucunun kendi "çok kısa / çok uzun" iletisindeki sayı gösterilir (`serverLengthMessage`).
 */
export const PWD_MIN_LENGTH = 6
export const PWD_MAX_LENGTH = 64

/** Sunucunun "Password too short (min N chars)" / "Password too long (max N chars)" iletisi → yerelleştirilmiş metin. */
export function serverLengthMessage(err, t) {
  const s = String(err || '')
  const short = /too short/i.test(s)
  if (!short && !/too long/i.test(s)) return null
  const n = /(?:min|max)\D{0,3}(\d{1,4})/i.exec(s)?.[1]
  if (short) return t('pwd.tooShort', n || PWD_MIN_LENGTH)
  return t('pwd.tooLong', n || PWD_MAX_LENGTH)
}

function passwordStrength(pwd) {
  if (!pwd) return 0
  let s = 0
  if (pwd.length >= 6) s++
  if (/[a-z]/.test(pwd) && /[A-Z]/.test(pwd)) s++
  if (/[0-9]/.test(pwd)) s++
  if (/[^a-zA-Z0-9]/.test(pwd)) s++
  return s
}

/** Dört parçalı güç göstergesi — parçalar süs (aria-hidden), okunur olan etiket metni. */
function PwdStrengthMeter({ pwd, t, id }) {
  if (!pwd) return null
  const score = passwordStrength(pwd)
  return (
    <div data-slot="pwd-strength" data-score={score} className="flex items-center gap-2.5">
      <div className="flex flex-1 gap-1" aria-hidden="true">
        {[1, 2, 3, 4].map(i => (
          <span key={i} className={cn('h-1 flex-1 rounded-full', i <= score ? STRENGTH_BG[score] : 'bg-border')} />
        ))}
      </div>
      <span id={id} className={cn('text-xs font-semibold', STRENGTH_INK[score])}>
        {t(`usr.pwdStrength.${STRENGTH_LABELS[score]}`)}
      </span>
    </div>
  )
}

/**
 * Shared password-change modal (ui/ModalShell — shadcn Dialog).
 *
 * `mode='admin-reset'` — admin rewriting another user's password; verify
 *                        field asks for the admin's own password.
 * `mode='self-change'` — any user changing their own password; verify
 *                        field asks for the user's current password.
 * `mode='forced-change'` — the app is locked until a new password is chosen: no close button,
 *                        backdrop click and Escape are ignored (mustChangePassword lock).
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
  const strengthId = useId()

  const isForced       = mode === 'forced-change'
  const titleKey       = isForced ? 'usr.forcedPwdTitle'     : 'usr.selfPwdTitle'
  const verifyLabelKey = isForced ? 'usr.forcedPwdVerify'    : 'usr.pwdSelfVerify'
  const verifyHintKey  = isForced ? 'usr.forcedPwdHint'      : 'usr.pwdSelfVerifyHint'
  const wrongVerifyKey = 'usr.pwdSelfVerifyWrong'

  const lengthOk = newPwd.length >= PWD_MIN_LENGTH && newPwd.length <= PWD_MAX_LENGTH

  async function submit() {
    if (!verifyPwd) { setMsg(t('usr.pwdAdminConfirmRequired')); return }
    if (!lengthOk) { setMsg(t('pwd.lengthRule', PWD_MIN_LENGTH, PWD_MAX_LENGTH)); return }
    if (newPwd !== confirmPwd) { setMsg(t('usr.pwdMismatch')); return }
    setSaving(true)
    try {
      const res = await api.me.changePassword(verifyPwd, newPwd)
      if (res?.success) {
        onSuccess?.()
        onClose()
      } else {
        const err = res?.error || ''
        const lengthMsg = serverLengthMessage(err, t)
        if (/FORBIDDEN|Current password|Invalid admin/i.test(err) || res?.status === 403) {
          setMsg(t(wrongVerifyKey))
        } else if (/recently used/i.test(err)) {
          setMsg(t('usr.pwdReused'))
        } else if (lengthMsg) {
          setMsg(lengthMsg)
        } else {
          setMsg(err || t('pwd.changeFailed'))
        }
      }
    } catch (e) {
      // Ağ kesintisi (request() ApiError fırlatır, POST yeniden denenmez): eskiden yakalanmamış söz reddiydi — düğme
      // dönmeyi bırakıyor, zorunlu değişim ekranındaki kullanıcı hiçbir ileti görmüyordu (UIA-H11).
      setMsg(e?.message || t('login.serverError'))
    } finally {
      setSaving(false)
    }
  }

  const mismatch = !!confirmPwd && newPwd !== confirmPwd
  const tooLong = newPwd.length > PWD_MAX_LENGTH

  return (
    <ModalShell open onClose={onClose} title={t(titleKey, targetUser.username)} icon={KeyRound} size="sm" busy={saving}
      hideClose={isForced} dismissOnBackdrop={!isForced} dismissOnEscape={!isForced}
      footer={<>
        {!isForced && (
          <Button variant="secondary" onClick={onClose}>{t('usr.cancel')}</Button>
        )}
        <Button onClick={submit} aria-busy={saving || undefined}
          disabled={saving || !verifyPwd || !lengthOk || newPwd !== confirmPwd}>
          {saving ? t('usr.saving') : t('usr.pwdSave')}
        </Button>
      </>}>
      <p className="mb-3 text-xs text-muted-foreground">{t(verifyHintKey)}</p>
      <Field label={t(verifyLabelKey)} required>
        {({ id }) => (
          <Input id={id} type="password" value={verifyPwd} onChange={(e) => setVerifyPwd(e.target.value)}
            autoFocus autoComplete="current-password" />
        )}
      </Field>
      {/* maxLength YOK: tarayıcı fazlasını sessizce keserdi (parola değişirdi); fazla uzunluk görünür uyarıyla söylenir */}
      <Field label={t('usr.formNewPwd')} required hintTone={tooLong ? 'warn' : undefined}
        hint={tooLong ? t('pwd.tooLong', PWD_MAX_LENGTH) : t('pwd.lengthRule', PWD_MIN_LENGTH, PWD_MAX_LENGTH)}>
        {({ id, describedBy }) => (
          <>
            <Input id={id} type="password" value={newPwd} aria-invalid={tooLong || undefined}
              aria-describedby={[describedBy, newPwd ? strengthId : null].filter(Boolean).join(' ') || undefined}
              onChange={(e) => setNewPwd(e.target.value)} autoComplete="new-password" />
            <PwdStrengthMeter pwd={newPwd} t={t} id={strengthId} />
          </>
        )}
      </Field>
      <Field label={t('usr.formConfirmPwd')} required hint={mismatch ? t('usr.pwdMismatch') : undefined} hintTone="warn"
        className="mb-0">
        {({ id, describedBy }) => (
          <Input id={id} type="password" value={confirmPwd} aria-describedby={describedBy} aria-invalid={mismatch || undefined}
            onChange={(e) => setConfirmPwd(e.target.value)} autoComplete="new-password" />
        )}
      </Field>
      {msg && <AlertBanner tone="danger" role="alert" className="mt-3 mb-0">{msg}</AlertBanner>}
    </ModalShell>
  )
}
