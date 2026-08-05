import { Link2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'

/**
 * "Bağlantıyı Kopyala" — adres çubuğundaki URL zaten her an güncel (useUrlQuerySync);
 * bu buton yalnız kopyalar ve toast gösterir. Clipboard API yoksa/reddederse geçici
 * textarea + execCommand fallback'i dener.
 */
export default function CopyLinkButton({ className = 'btn btn-secondary btn-sm', iconOnly = false }) {
  const t = useT()
  const toast = useToast()

  async function copy() {
    const url = window.location.href
    try {
      await navigator.clipboard.writeText(url)
      toast.success(t('share.copied'))
      return
    } catch { /* http origin / izin yok → fallback */ }
    try {
      const ta = document.createElement('textarea')
      ta.value = url
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      if (ok) { toast.success(t('share.copied')); return }
    } catch { /* execCommand da yok */ }
    toast.error(url)   // son çare: URL'i göster, kullanıcı elle kopyalar
  }

  return (
    <button type="button" className={className} onClick={copy}
      title={t('share.copyLink')} aria-label={t('share.copyLink')}>
      <Link2 size={14} />{iconOnly ? null : <> {t('share.copyLink')}</>}
    </button>
  )
}
