import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'

/** Şifreleme anahtarı (SITE_MONITOR_SECRET_KEY) ayarlı değilken parola girilen
 *  sayfalarda (SMTP/LDAP) gösterilen uyarı. secret_key_set=false iken render edilir.
 *  Çizim ui/AlertBanner (shadcn Alert, `warning` tonu). */
export default function SecretKeyWarning() {
  const t = useT()
  return (
    <AlertBanner tone="warning" className="mb-4">
      {t('settings.secretKeyWarn')}
    </AlertBanner>
  )
}
