import { Mail, Smartphone } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import Field from '../ui/Field.jsx'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { EMAIL_MAX_LENGTH, PHONE_MAX_LENGTH, formatPhoneInput } from '../../utils/otpContact.js'

/**
 * Kodla giriş isteğinde KİŞİ BİLGİSİ alanı (2026-10-03, kullanıcı isteği) — push için "Kayıtlı cep telefonu", e-posta
 * için "Kayıtlı e-posta adresi". Giriş akışı ({@link OtpLoginFlow}) ve ayar sayfasının canlı önizlemesi AYNI bileşeni
 * çizer (önizleme gerçeğiyle birebir).
 *
 * - Telefon: `type="tel"`, `inputMode="tel"`, `autoComplete="tel"`; düz rakam yazılırken hafif gruplama
 *   ("0532 123 45 67"), `+ ( ) -` içeren biçimler AYNEN korunur (yapıştırma dahil). İmleç sonda değilse dokunulmaz.
 * - E-posta: `type="email"`, `autoComplete="email"`, otomatik büyük harf / yazım denetimi kapalı.
 * - Kısa yardım metni + alan-bazlı hata (`ui/Field` — `data-field` = `phone` / `email`; useFormErrors odaklar).
 * - Mobil: tam genişlik, 40 px yükseklik, girdi telefonda 16 px (iOS yakınlaştırmaz).
 *
 * Test kancaları: `data-slot="otp-contact"` + `data-kind`.
 */
export default function OtpContactField({ kind, value, onChange, error, autoFocus = false, readOnly = false }) {
  const t = useT()
  const isPhone = kind === 'phone'
  const Icon = isPhone ? Smartphone : Mail

  function handleChange(e) {
    const raw = e.target.value
    if (!isPhone) { onChange?.(raw); return }
    const caret = e.target.selectionStart
    const atEnd = caret == null || caret >= raw.length
    onChange?.(atEnd ? formatPhoneInput(raw) : raw)
  }

  return (
    <Field name={kind} error={error} className="mb-0"
      label={isPhone ? t('otp.phone.label') : t('otp.email.label')}
      hint={isPhone ? t('otp.phone.hint') : t('otp.email.hint')}>
      {({ id, describedBy, invalid }) => (
        <InputGroup className="h-10" data-slot="otp-contact" data-kind={kind}>
          <InputGroupInput id={id} value={value ?? ''} onChange={handleChange}
            type={isPhone ? 'tel' : 'email'} inputMode={isPhone ? 'tel' : 'email'}
            autoComplete={isPhone ? 'tel' : 'email'} autoCapitalize="off" autoCorrect="off" spellCheck={false}
            maxLength={isPhone ? PHONE_MAX_LENGTH : EMAIL_MAX_LENGTH}
            placeholder={isPhone ? t('otp.phone.placeholder') : t('otp.email.placeholder')}
            aria-describedby={describedBy} aria-invalid={invalid} autoFocus={autoFocus} readOnly={readOnly} />
          <InputGroupAddon><Icon aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
      )}
    </Field>
  )
}
