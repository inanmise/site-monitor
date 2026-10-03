import { useId, useMemo, useState } from 'react'
import { Info, KeyRound, MonitorSmartphone } from 'lucide-react'
import { FixedLangProvider, loadLanguage, useLanguage, useT } from '../../../i18n/index.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { Alert, AlertDescription } from '@/components/shadcn/alert'
import { Label } from '@/components/shadcn/label'
import OtpMethodButtons from '../../login/OtpMethodButtons.jsx'
import OtpCodeInput from '../../login/OtpCodeInput.jsx'
import OtpCountdown from '../../login/OtpCountdown.jsx'
import { SettingsSection } from '../SettingsControls.jsx'

/** Önizlemenin göstereceği kanallar — KAYDEDİLMEMİŞ form değerinden; push yalnız ağ geçidi de yapılandırılmışsa. */
export function previewChannels(form, status) {
  const out = []
  if (form?.push_enabled && status?.push_gateway_configured) out.push('push')
  if (form?.email_enabled) out.push('email')
  return out
}

/** Önizleme sahnesi — dil sağlayıcısının İÇİNDE çizilir (metinler seçili dilde). */
function Stage({ channels, ttl, ldapOff }) {
  const t = useT()
  // Statik saat: geri sayım "45 sn" gibi tam süreyi gösterir, zamanlayıcı kurulmaz.
  const at = useMemo(() => Date.now(), [])
  const codeId = useId()
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
            <OtpMethodButtons channels={channels} onPick={() => {}} />
            <div className="flex flex-col gap-3 rounded-lg border bg-background p-3 sm:p-4">
              <Alert variant="info">
                <Info />
                <AlertDescription>{channels[0] === 'email' ? t('otp.sentInfo.email') : t('otp.sentInfo.push')}</AlertDescription>
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
 * kod adımı: bilgi metni, 6 kutu, geri sayım) GERÇEK bileşenlerle, KAYDEDİLMEMİŞ form değerleriyle; TR/EN geçişi arayüz
 * dilini değiştirmez ({@link FixedLangProvider}). Hiçbir istek atmaz.
 *
 * Test kancaları: `data-slot="lm-preview"` (`data-lang`), `lm-preview-stage`.
 */
export default function LoginMethodsPreview({ form, status }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const [lang, setLang] = useState(uiLang === 'en' ? 'en' : 'tr')
  const [loading, setLoading] = useState(false)
  const channels = previewChannels(form, status)
  const ttl = channels[0] === 'email' ? Number(form?.email_ttl_seconds) || 45 : Number(form?.push_ttl_seconds) || 45

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
            <Stage channels={channels} ttl={ttl} ldapOff={form?.ldap_enabled === false} />
          </div>
        </FixedLangProvider>
      </div>
    </SettingsSection>
  )
}
