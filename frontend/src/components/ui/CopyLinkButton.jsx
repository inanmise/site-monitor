import { Link2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'
import { copyText } from '../../utils/copyText.js'

/**
 * "Bağlantıyı Kopyala" — adres çubuğundaki URL zaten her an güncel (useUrlQuerySync);
 * bu buton yalnız kopyalar ve toast gösterir. Kopyalama kademeleri utils/copyText'te
 * (clipboard API → textarea+execCommand → başarısız); burada yalnız geri bildirim var.
 */
export default function CopyLinkButton({ className = 'btn btn-secondary btn-sm', iconOnly = false }) {
  const t = useT()
  const toast = useToast()

  async function copy() {
    const url = window.location.href
    if (await copyText(url)) toast.success(t('share.copied'))
    else toast.error(url)   // son çare: URL'i göster, kullanıcı elle kopyalar
  }

  return (
    <button type="button" className={className} onClick={copy}
      title={t('share.copyLink')} aria-label={t('share.copyLink')}>
      <Link2 size={14} />{iconOnly ? null : <> {t('share.copyLink')}</>}
    </button>
  )
}
