import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { AlertCircle, ArrowLeft, Info, KeyRound, Lock, Mail, RotateCw, ShieldAlert, Smartphone, UserX, Wrench, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { isAccountInactivePayload } from '../../utils/accountInactive.js'
import { isMaintenancePayload } from '../../utils/systemMaintenance.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Alert, AlertDescription, AlertTitle } from '@/components/shadcn/alert'
import { Spinner } from '../ui/Progress.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import Field from '../ui/Field.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import { contactKindOf, isPlausibleEmail, isPlausiblePhone } from '../../utils/otpContact.js'
import OtpCodeInput, { OTP_LENGTH } from './OtpCodeInput.jsx'
import OtpCountdown, { secondsLeft } from './OtpCountdown.jsx'
import OtpContactField from './OtpContactField.jsx'

/** Kanal → simge (push: telefon, e-posta: zarf). */
export const CHANNEL_ICON = { push: Smartphone, email: Mail }

/** Kullanılabilir kanallar — giriş sayfasının public `login-methods` yanıtından. */
export function availableChannels(methods) {
  const out = []
  if (methods?.otp_push) out.push('push')
  if (methods?.otp_email) out.push('email')
  return out
}

/** Doğrulama/istek yanıtı → ekranda gösterilecek hata ({kind, attemptsLeft?}); başarı ve 409 burada değil. */
export function otpErrorOf(res) {
  if (!res || res.networkError) return { kind: 'network' }
  const code = res.code || res.error_code
  if (code === 'OTP_INVALID') return { kind: 'invalid', attemptsLeft: Number(res.attempts_left ?? 0) }
  if (code === 'OTP_EXPIRED') return { kind: 'expired' }
  if (code === 'OTP_LOCKED') return { kind: 'locked' }
  if (code === 'OTP_RATE_LIMITED' || res.status === 429) return { kind: 'rateLimited' }
  if (code === 'OTP_METHOD_DISABLED') return { kind: 'methodDisabled' }
  if (code === 'USERNAME_REQUIRED') return { kind: 'usernameRequired' }
  if (code === 'PHONE_REQUIRED') return { kind: 'phoneRequired' }
  if (code === 'EMAIL_REQUIRED') return { kind: 'emailRequired' }
  if (isAccountInactivePayload(res)) return { kind: 'inactive' }
  if (isMaintenancePayload(res)) return { kind: 'maintenance' }
  if (res.status === 423) return { kind: 'accountLocked' }
  return { kind: 'server' }
}

/** Hata türü → metin. `t()` çağrıları LİTERAL (i18n-used-keys kapısı). */
function errorText(t, err) {
  switch (err?.kind) {
    case 'invalid': return t('otp.err.invalid', err.attemptsLeft)
    case 'expired': return t('otp.err.expired')
    case 'locked': return t('otp.err.locked')
    case 'rateLimited': return t('otp.err.rateLimited')
    case 'methodDisabled': return t('otp.err.methodDisabled')
    case 'usernameRequired': return t('otp.err.usernameRequired')
    case 'inactive': return t('login.accountInactiveError')
    case 'maintenance': return t('sysmaint.login.error')
    case 'accountLocked': return t('otp.err.accountLocked')
    case 'network': return t('login.serverError')
    default: return t('login.serverError')
  }
}

/** Kod yeniden istemek gereken hata mı (kod kutusu kapanır, "Yeni kod iste" öne çıkar). */
const NEEDS_NEW_CODE = new Set(['expired', 'locked'])

/** Kanal adı (bilgi metni / başlık) — literal anahtarlar. */
function channelTitle(t, ch) { return ch === 'email' ? t('otp.title.email') : t('otp.title.push') }
function sentInfo(t, ch) { return ch === 'email' ? t('otp.sentInfo.email') : t('otp.sentInfo.push') }
/** İstek adımının açıklaması — kişi bilgisi isteniyorsa onu da söyler (literal anahtarlar). */
function requestDesc(t, kind) {
  if (kind === 'phone') return t('otp.desc.phone')
  if (kind === 'email') return t('otp.desc.email')
  return t('otp.desc')
}

/** İstek adımının alan doğrulaması → hata haritası (useFormErrors.check); sunucu eşleşmeyi ayrıca ve sessizce karar verir. */
export function validateOtpRequest({ username, kind, phone, email }, t) {
  const p = String(phone ?? '').trim()
  const m = String(email ?? '').trim()
  return {
    username: !String(username ?? '').trim() && t('otp.err.usernameRequired'),
    phone: kind === 'phone' && (!p ? t('otp.err.phoneRequired') : !isPlausiblePhone(p) && t('otp.err.phoneInvalid')),
    email: kind === 'email' && (!m ? t('otp.err.emailRequired') : !isPlausibleEmail(m) && t('otp.err.emailInvalid')),
  }
}

/**
 * Kodla giriş akışı (2026-10-02, kullanıcı isteği) — giriş kartının İÇİNDE, ana formun yerine geçer (ana form değişmez):
 *
 *  1. **İstek:** kullanıcı adı (ana formdaki değer önceden dolar) + kanal (iki yöntem de açıksa seçici) + KİŞİ BİLGİSİ
 *     (2026-10-03: ayar açıksa push için "Kayıtlı cep telefonu", e-posta için "Kayıtlı e-posta adresi" — `OtpContactField`;
 *     kanal değişince kullanıcı adı ve iki değer korunur, doğru alan görünür) + beni hatırla → "Kod gönder". Alan
 *     doğrulaması alanın yanında (useFormErrors: zorunlu, makul telefon uzunluğu / e-posta biçimi), ilk hatalıya odak;
 *     Enter gönderir. Sunucu yanıtı HER kullanıcı için aynıdır (eşleşmeme dahil) — ekran da "hesabınız uygunsa …
 *     gönderildi" der, eşleşip eşleşmediğini asla ima etmez.
 *  2. **Kod:** 6 kutulu giriş (yapıştırma, ok tuşları, tamamlanınca otomatik doğrulama), dairesel geri sayım (son 10 sn
 *     uyarı tonu; ekran okuyucuya seyrek duyuru), "Kod gelmedi mi? Yeniden gönder (N sn)", "Şifreyle giriş yap".
 *     Hatalar: yanlış kod + kalan deneme, süre doldu → yeni kod, kilit → yeni kod, pasif / bakım / hesap kilidi.
 *  3. **Onay (409):** başka yerde oturum — onaylanırsa AYNI kod `forceLogin` ile yeniden gönderilir (sunucu isteği
 *     onay süresince tutar).
 *
 * Mobil: tek sütun, düğmeler tam genişlik (≥ 40 px), kutular kart genişliğine sığar (`min-w-0`, 6 eşit sütun).
 * Test kancaları: `data-slot="otp-flow"` + `data-step`, `otp-send`, `otp-verify`, `otp-resend`, `otp-back`, `otp-error`
 * (`data-kind`), `otp-sent-info`, `otp-confirm`, `otp-contact` (`data-kind` phone / email), `otp-desc`; alanlar
 * `data-field` = `username` / `phone` / `email`.
 */
export default function OtpLoginFlow({ methods, initialUsername = '', initialChannel = 'push', initialRemember = false,
  onSuccess, onCancel, onMaintenance }) {
  const t = useT()
  const channels = availableChannels(methods)
  const [step, setStep] = useState('request')
  const [username, setUsername] = useState(initialUsername)
  const [channel, setChannel] = useState(channels.includes(initialChannel) ? initialChannel : (channels[0] || 'push'))
  const [remember, setRemember] = useState(!!initialRemember)
  const [challenge, setChallenge] = useState(null)   // { id, total, expiresAt, resendAt }
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(null)             // 'send' | 'verify' | null
  const [error, setError] = useState(null)           // { kind, attemptsLeft? }
  // Kişi bilgisi (2026-10-03): kanal başına ayrı değer — kanal değişince kullanıcının yazdığı kaybolmaz.
  const [phone, setPhone] = useState('')
  const [email, setEmail] = useState('')
  // Sunucu "zorunlu" dedi ama sayfa eski yapılandırmayla açılmıştı (ayar arada açıldı) → alan yine de çizilsin.
  const [forced, setForced] = useState({})
  const fe = useFormErrors()
  const [now, setNow] = useState(() => Date.now())
  const codeRef = useRef(null)
  const ids = useId()
  const codeHintId = `${ids}-hint`
  const errorId = `${ids}-err`
  const contactKind = contactKindOf(methods, channel) || (forced[channel] ? (channel === 'email' ? 'email' : 'phone') : null)

  // Tek zamanlayıcı: kod adımında saniyede iki kez (geri sayım + yeniden gönder sayacı aynı saatten).
  useEffect(() => {
    if (step !== 'code' && step !== 'confirm') return undefined
    const id = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(id)
  }, [step])

  // Kanal listesi değişirse (public uç yenilendi) seçili kanal geçerli kalsın.
  useEffect(() => {
    if (channels.length && !channels.includes(channel)) setChannel(channels[0])
  }, [channels.join(','), channel])   // eslint-disable-line react-hooks/exhaustive-deps

  const left = challenge ? secondsLeft(challenge.expiresAt, now) : 0
  const expired = !!challenge && left === 0
  const resendLeft = challenge ? secondsLeft(challenge.resendAt, now) : 0
  const blocked = expired || NEEDS_NEW_CODE.has(error?.kind)

  const sendCode = useCallback(async (e) => {
    e?.preventDefault?.()
    if (busy) return
    const name = username.trim()
    const kind = contactKind
    const contact = kind === 'phone' ? phone.trim() : kind === 'email' ? email.trim() : ''
    // Alan doğrulaması ALANIN YANINDA (tost yok); ilk hatalı alana odak. Yeniden gönderimde de aynı değerler gider.
    if (fe.check(validateOtpRequest({ username: name, kind, phone, email }, t))) {
      setStep('request')
      return
    }
    setBusy('send')
    setError(null)
    try {
      // Kişi bilgisi yalnız istendiğinde gönderilir; ekran eşleşip eşleşmediğini ASLA bilmez (yanıt her durumda aynı).
      const res = kind
        ? await api.loginOtp.request(name, channel, { [kind]: contact })
        : await api.loginOtp.request(name, channel)
      if (res?.success && res.challenge_id) {
        const at = Date.now()
        const total = Number(res.expires_in) || 45
        setChallenge({ id: res.challenge_id, total, expiresAt: at + total * 1000,
          resendAt: at + (Number(res.resend_in) || 30) * 1000 })
        setNow(at)
        setCode('')
        setStep('code')
      } else {
        const err = otpErrorOf(res)
        if (err.kind === 'usernameRequired') {
          setStep('request')
          fe.check({ username: t('otp.err.usernameRequired') })
        } else if (err.kind === 'phoneRequired' || err.kind === 'emailRequired') {
          const ch = err.kind === 'phoneRequired' ? 'push' : 'email'
          setForced((f) => ({ ...f, [ch]: true }))
          setStep('request')
          fe.check(err.kind === 'phoneRequired' ? { phone: t('otp.err.phoneRequired') } : { email: t('otp.err.emailRequired') })
        } else setError(err)
      }
    } catch {
      setError({ kind: 'network' })
    } finally {
      setBusy(null)
    }
  }, [busy, username, channel, contactKind, phone, email, fe, t])

  function changeChannel(next) {
    setChannel(next)
    setError(null)
    fe.clear('phone')
    fe.clear('email')
  }

  async function verify(value, force = false) {
    const v = String(value ?? code)
    if (busy || !challenge || v.length !== OTP_LENGTH || (blocked && !force)) return
    setBusy('verify')
    setError(null)
    try {
      const res = await api.loginOtp.verify(challenge.id, v, remember, force)
      if (res?.success) {
        onSuccess?.(res, { username: username.trim(), remember })
        return
      }
      if (res?.status === 409 || res?.error_code === 'ACTIVE_SESSION_EXISTS') {
        setStep('confirm')
        return
      }
      const err = otpErrorOf(res)
      if (err.kind === 'maintenance' && res?.maintenance && typeof res.maintenance === 'object') onMaintenance?.(res.maintenance)
      setStep('code')
      setError(err)
      if (err.kind === 'invalid') {
        setCode('')
        requestAnimationFrame?.(() => codeRef.current?.focus())
      }
    } catch {
      setStep('code')
      setError({ kind: 'network' })
    } finally {
      setBusy(null)
    }
  }

  function backToRequest() {
    setStep('request')
    setChallenge(null)
    setCode('')
    setError(null)
  }

  const ChannelIcon = CHANNEL_ICON[channel] || KeyRound
  const sending = busy === 'send'
  const verifying = busy === 'verify'

  return (
    <div data-slot="otp-flow" data-step={step} className="flex w-full min-w-0 flex-col gap-4">
      <div className="flex min-w-0 items-start gap-3">
        <span aria-hidden="true" className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <ChannelIcon className="size-5" />
        </span>
        <div className="flex min-w-0 flex-col gap-0.5">
          <h3 className="text-base leading-tight font-semibold">{channelTitle(t, channel)}</h3>
          <p className="text-sm text-muted-foreground" data-slot="otp-desc">{step === 'request' ? requestDesc(t, contactKind) : t('otp.desc')}</p>
        </div>
      </div>

      {step === 'request' && (
        <form onSubmit={sendCode} className="flex min-w-0 flex-col gap-4" noValidate>
          <Field label={t('login.username')} className="mb-0" {...fe.fieldProps('username')}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} className="h-10" type="text" value={username} autoComplete="username"
                autoCapitalize="off" autoCorrect="off" spellCheck={false}
                onChange={(e) => { setUsername(e.target.value); fe.clear('username') }}
                placeholder={t('login.userPlaceholder')} aria-invalid={invalid}
                aria-describedby={describedBy} autoFocus={!initialUsername} />
            )}
          </Field>

          {channels.length > 1 && (
            <div className="flex flex-col gap-1.5">
              <span className="text-sm font-semibold">{t('otp.channel')}</span>
              {/* Tam genişlik; telefonda / dokunmatikte 40 px hedef (2026-10-03) */}
              <SegmentedControl value={channel} onChange={changeChannel} ariaLabel={t('otp.channel')}
                className="w-full" itemClassName="h-9 flex-1 max-sm:h-10 pointer-coarse:h-10"
                options={[{ value: 'push', label: t('otp.channel.push'), icon: Smartphone },
                  { value: 'email', label: t('otp.channel.email'), icon: Mail }]} />
            </div>
          )}

          {/* Kişi bilgisi (2026-10-03): kanal başına kayıtlı cep telefonu / e-posta — kod yalnız eşleşirse gider. */}
          {contactKind === 'phone' && (
            <OtpContactField key="phone" kind="phone" value={phone} error={fe.errors.phone || undefined}
              autoFocus={!!initialUsername} onChange={(v) => { setPhone(v); fe.clear('phone') }} />
          )}
          {contactKind === 'email' && (
            <OtpContactField key="email" kind="email" value={email} error={fe.errors.email || undefined}
              autoFocus={!!initialUsername} onChange={(v) => { setEmail(v); fe.clear('email') }} />
          )}

          <div className="inline-flex min-h-10 items-center gap-2 text-sm">
            <Checkbox id={`${ids}-rem`} checked={remember} onCheckedChange={(v) => setRemember(v === true)} />
            <Label htmlFor={`${ids}-rem`} className="cursor-pointer font-normal select-none">{t('login.rememberMe')}</Label>
          </div>

          {error && (
            <Alert variant="destructive" data-slot="otp-error" data-kind={error.kind}>
              <AlertCircle />
              <AlertDescription>{errorText(t, error)}</AlertDescription>
            </Alert>
          )}

          <Button type="submit" size="lg" className="w-full" disabled={sending} aria-busy={sending || undefined} data-slot="otp-send">
            {sending ? <><Spinner size={16} inline decorative /> {t('otp.sending')}</> : <><ChannelIcon /> {t('otp.send')}</>}
          </Button>
          <Button type="button" variant="ghost" className="min-h-10 w-full" onClick={onCancel} data-slot="otp-back">
            <ArrowLeft /> {t('otp.backToPassword')}
          </Button>
        </form>
      )}

      {step === 'code' && challenge && (
        <form className="flex min-w-0 flex-col gap-4" noValidate
          onSubmit={(e) => { e.preventDefault(); verify(code) }}>
          <Alert variant="info" role="status" data-slot="otp-sent-info">
            <Info />
            <AlertDescription>
              {sentInfo(t, channel)}
              <span className="mt-0.5 block text-xs opacity-90">{t('otp.securityNote')}</span>
            </AlertDescription>
          </Alert>

          <div className="flex flex-col gap-2">
            <Label htmlFor={`${ids}-code`}>{t('otp.codeLabel')}</Label>
            <OtpCodeInput ref={codeRef} id={`${ids}-code`} value={code} autoFocus
              onChange={(v) => { setCode(v); if (error?.kind === 'invalid') setError(null) }}
              onComplete={(v) => verify(v)}
              disabled={blocked || verifying}
              invalid={error?.kind === 'invalid'}
              label={t('otp.codeLabel')}
              describedBy={[codeHintId, error ? errorId : null].filter(Boolean).join(' ')} />
            <p id={codeHintId} className="text-xs text-muted-foreground">{t('otp.codeHint')}</p>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3">
            <OtpCountdown expiresAt={challenge.expiresAt} total={challenge.total} now={now} />
            <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{username.trim()}</span>
          </div>

          {(error || expired) && (
            <Alert variant="destructive" id={errorId} data-slot="otp-error" data-kind={error?.kind || 'expired'}>
              {error?.kind === 'inactive' ? <UserX /> : error?.kind === 'maintenance' ? <Wrench /> : <AlertCircle />}
              <AlertDescription>{error ? errorText(t, error) : t('otp.expiredNow')}</AlertDescription>
            </Alert>
          )}

          {!blocked && (
            <Button type="submit" size="lg" className="w-full" disabled={code.length !== OTP_LENGTH || verifying}
              aria-busy={verifying || undefined} data-slot="otp-verify">
              {verifying ? <><Spinner size={16} inline decorative /> {t('otp.verifying')}</> : <><Lock /> {t('otp.verify')}</>}
            </Button>
          )}

          <Button type="button" variant={blocked ? 'default' : 'outline'} size={blocked ? 'lg' : 'default'}
            className="min-h-10 w-full whitespace-normal" disabled={resendLeft > 0 || sending}
            onClick={() => sendCode()} data-slot="otp-resend" data-wait={resendLeft || undefined}>
            {sending ? <Spinner size={16} inline decorative /> : <RotateCw />}
            {blocked
              ? (resendLeft > 0 ? t('otp.newCodeIn', resendLeft) : t('otp.newCode'))
              : (resendLeft > 0 ? t('otp.resendIn', resendLeft) : t('otp.resendNow'))}
          </Button>

          <div className="flex flex-col gap-1 sm:flex-row sm:justify-between">
            <Button type="button" variant="link" className="min-h-10 px-0" onClick={backToRequest} data-slot="otp-change-user">
              {contactKind ? t('otp.changeDetails') : t('otp.changeUser')}
            </Button>
            <Button type="button" variant="link" className="min-h-10 px-0" onClick={onCancel} data-slot="otp-back">
              <ArrowLeft /> {t('otp.backToPassword')}
            </Button>
          </div>
        </form>
      )}

      {step === 'confirm' && (
        <div role="alertdialog" aria-labelledby={`${ids}-ct`} aria-describedby={`${ids}-cd`} data-slot="otp-confirm"
          className="flex flex-col gap-4">
          <Alert variant="warning">
            <ShieldAlert />
            <AlertTitle id={`${ids}-ct`}>{t('login.activeSessionTitle')}</AlertTitle>
            <AlertDescription id={`${ids}-cd`}>{t('login.activeSessionDesc')}</AlertDescription>
          </Alert>
          <Button type="button" size="lg" className="w-full" disabled={verifying} onClick={() => verify(code, true)}
            data-slot="otp-confirm-ok">
            {verifying ? <><Spinner size={16} inline decorative /> {t('login.loading')}</> : <><Lock /> {t('login.activeSessionConfirm')}</>}
          </Button>
          <Button type="button" variant="outline" className="min-h-10 w-full" disabled={verifying} onClick={onCancel}>
            <X /> {t('login.activeSessionCancel')}
          </Button>
        </div>
      )}
    </div>
  )
}
