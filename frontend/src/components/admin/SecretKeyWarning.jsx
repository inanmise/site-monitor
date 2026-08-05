import { AlertTriangle } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'

/** Şifreleme anahtarı (SITE_MONITOR_SECRET_KEY) ayarlı değilken parola girilen
 *  sayfalarda (SMTP/LDAP) gösterilen uyarı. secret_key_set=false iken render edilir. */
export default function SecretKeyWarning() {
  const t = useT()
  return (
    <div className="settings-warn">
      <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: 1 }} />
      <span>{t('settings.secretKeyWarn')}</span>
    </div>
  )
}
