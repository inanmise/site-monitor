import { useId, useMemo, useState } from 'react'
import { Info, KeyRound, Mail, MonitorSmartphone, Smartphone } from 'lucide-react'
import { FixedLangProvider, loadLanguage, useLanguage, useT } from '../../../i18n/index.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import Field from '../../ui/Field.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Alert, AlertDescription } from '@/components/shadcn/alert'
import { Label } from '@/components/shadcn/label'
import { Input } from '@/components/shadcn/input'
import OtpMethodButtons from '../../login/OtpMethodButtons.jsx'
import OtpCodeInput from '../../login/OtpCodeInput.jsx'
import OtpCountdown from '../../login/OtpCountdown.jsx'
import OtpContactField from '../../login/OtpContactField.jsx'
import { contactKindOf } from '../../../utils/otpContact.js'
import { SettingsSection } from '../SettingsControls.jsx'

/** Önizlemenin göstereceği kanallar — KAYDEDİLMEMİŞ form değerinden; push yalnız ağ geçidi de yapılandırılmışsa. */
export function previewChannels(form, status) {
  const out = []
  if (form?.push_enabled && status?.push_gateway_configured) out.push('push')
  if (form?.email_enabled) out.push('email')
  return out
}

/** Kaydedilmemiş formdan giriş sayfasının public bayrakları (kişi bilgisi alanı hangi kanalda çizilecek). */
export function previewMethods(form) {
  return { push_requires_phone: !!form?.push_require_phone, email_requires_email: !!form?.email_require_email }
}

/** Önizleme sahnesi — dil sağlayıcısının İÇİNDE çizilir (metinler seçili dilde). */
function Stage({ channels, form, ldapOff }) {
  const t = useT()
  // Statik saat: geri sayım "45 sn" gibi tam süreyi gösterir, zamanlayıcı kurulmaz.
  const at = useMemo(() => Date.now(), [])
  const codeId = useId()
  // İki kanal açıksa önizlemede de seçilebilir (gerçek akıştaki "Kod nereye gelsin?" gibi); liste değişirse ilkine düşer.
  const [picked, setPicked] = useState(null)
  const active = channels.includes(picked) ? picked : channels[0]
  const kind = contactKindOf(previewMethods(form), active)
  const ttl = active === 'email' ? Number(form?.email_ttl_seconds) || 45 : Number(form?.push_ttl_seconds) || 45
  return (
    <div className="flex w-full min-w-0 flex-col gap-4">
      {ldapOff && (
        <Alert variant="destructive" data-slot="lm-preview-ldap-off">
          <KeyRound />
          <AlertDescription>{t('login.ldapDisabled')}</AlertDescription>
        </Alert>
      )}
      {channels.length === 0
        ? <p className="text-sm text-muted-foreground" data-slot="lm-preview-none">{t('lm.preview.none')}</p>
        : (
          <>
            <OtpMethodButtons channels={channels} onPick={setPicked} />
            {/* İstek adımı (2026-10-03): kullanıcı adı + kanal + kişi bilgisi alanı — giriş akışıyla AYNI bileşen */}
            <div data-slot="lm-preview-request" data-channel={active} data-contact={kind || 'none'}
              className="flex min-w-0 flex-col gap-3 rounded-lg border bg-background p-3 sm:p-4">
              <p className="m-0 text-sm text-muted-foreground">
                {kind === 'phone' ? t('otp.desc.phone') : kind === 'email' ? t('otp.desc.email') : t('otp.desc')}
              </p>
              <Field label={t('login.username')} className="mb-0">
                {({ id }) => <Input id={id} className="h-10" readOnly value="" placeholder={t('login.userPlaceholder')} />}
              </Field>
              {channels.length > 1 && (
                <SegmentedControl value={active} onChange={setPicked} ariaLabel={t('otp.channel')}
                  className="w-full" itemClassName="h-9 flex-1 max-sm:h-10 pointer-coarse:h-10"
                  options={[{ value: 'push', label: t('otp.channel.push'), icon: Smartphone },
                    { value: 'email', label: t('otp.channel.email'), icon: Mail }]} />
              )}
              {kind && <OtpContactField key={kind} kind={kind} value="" readOnly onChange={() => {}} />}
            </div>
            <div className="flex flex-col gap-3 rounded-lg border bg-background p-3 sm:p-4">
              <Alert variant="info">
                <Info />
                <AlertDescription>{active === 'email' ? t('otp.sentInfo.email') : t('otp.sentInfo.push')}</AlertDescription>
              </Alert>
              <div className="flex flex-col gap-2">
                <Label htmlFor={codeId}>{t('otp.codeLabel')}</Label>
                <OtpCodeInput id={codeId} value="421" onChange={() => {}} label={t('otp.codeLabel')} />
              </div>
              <OtpCountdown expiresAt={at + ttl * 1000} total={ttl} now={at} />
            </div>
          </>
        )}
    </div>
  )
}

/**
 * Giriş ekranı CANLI önizlemesi (2026-10-02) — giriş sayfasının alt kısmı (LDAP kapalı uyarısı, "veya" + kod düğmeleri,
 * istek adımı — 2026-10-03: kullanıcı adı + kanal seçici + "Kayıtlı cep telefonu" / "Kayıtlı e-posta adresi" alanı, anahtar
 * açıksa —, kod adımı: bilgi metni, 6 kutu, geri sayım) GERÇEK bileşenlerle, KAYDEDİLMEMİŞ form değerleriyle; TR/EN geçişi
 * arayüz dilini değiştirmez ({@link FixedLangProvider}). Hiçbir istek atmaz.
 *
 * Test kancaları: `data-slot="lm-preview"` (`data-lang`), `lm-preview-stage`, `lm-preview-request` (`data-channel`,
 * `data-contact` phone / email / none).
 */
export default function LoginMethodsPreview({ form, status }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [lang, setLang] = useState(uiLang === 'en' ? 'en' : 'tr')
  const [loading, setLoading] = useState(false)
  const channels = previewChannels(form, status)

  async function changeLang(next) {
    if (next === 'en') {
      setLoading(true)
      try { await loadLanguage('en') } catch { /* sözlük inmedi: TR yedeğiyle çizilir */ }
      setLoading(false)
    }
    setLang(next)
  }

  return (
    <SettingsSection title={<span className="inline-flex items-center gap-2"><MonitorSmartphone aria-hidden="true" className="size-4" />{t('lm.preview.title')}</span>}
      description={t('lm.preview.desc')} contentClassName="flex min-w-0 flex-col gap-3">
      <div data-slot="lm-preview" data-lang={lang} className="flex min-w-0 flex-col gap-3">
        <div className="flex items-center justify-end gap-2">
          {loading && <Spinner size={14} inline label={t('nav.langLoading')} />}
          <SegmentedControl value={lang} onChange={changeLang} ariaLabel={t('lm.preview.lang')}
            options={[{ value: 'tr', label: 'TR' }, { value: 'en', label: 'EN' }]} />
        </div>
        <FixedLangProvider lang={lang}>
          <div data-slot="lm-preview-stage" role="group" aria-label={t('lm.preview.stage')}
            className="mx-auto w-full max-w-sm min-w-0 overflow-hidden rounded-lg border bg-muted/40 p-3 sm:p-4">
            <Stage channels={channels} form={form} ldapOff={form?.ldap_enabled === false} />
          </div>
        </FixedLangProvider>
      </div>
    </SettingsSection>
  )
}
