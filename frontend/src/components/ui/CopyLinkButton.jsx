import { Link2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'
import { copyText } from '../../utils/copyText.js'
import { Button } from '@/components/shadcn/button'

/**
 * "Bağlantıyı Kopyala". Kopyalama kademeleri utils/copyText'te (clipboard API →
 * textarea+execCommand → başarısız); burada yalnız geri bildirim var.
 *
 * <p><b>İki kip.</b> `url` VERİLMEZSE adres çubuğundaki URL kopyalanır — modal açıkken
 * useUrlQuerySync onu zaten güncel tutuyor (eski ve tek kullanım buydu). `url` VERİLİRSE
 * o mutlak bağlantı kopyalanır: kart üzerindeki düğme modalı AÇMADAN "şu monitöre git"
 * bağlantısını üretmek zorunda, o yüzden adres çubuğuna güvenemez (kart görünürken
 * adres çubuğunda liste sayfası ve kullanıcının o anki filtreleri yazıyor).
 *
 * <p><b>stopPropagation KOŞULSUZ.</b> Bu düğme tıklanabilir bir kartın İÇİNDE yaşıyor ve
 * kartın kendi onClick'i detay modalını açıyor; durdurulmazsa "bağlantıyı kopyala" aynı
 * anda modalı da açardı. Modal başlığındaki eski kullanımda zararsızdır.
 */
/**
 * Kopyalama eylemi düğmesiz: menü öğesinden de çağrılabilsin (telefonda sayfa başlığının "Diğer" menüsü,
 * monitoring/MonitorPageHeader). `copy(url?)` — url verilmezse adres çubuğu; geri bildirim aynı toast.
 */
export function useCopyLink() {
  const t = useT()
  const toast = useToast()
  return async function copyLink(url = null) {
    const target = url || window.location.href
    if (await copyText(target)) toast.success(t('share.copied'))
    else toast.error(target)   // son çare: URL'i göster, kullanıcı elle kopyalar
  }
}

/**
 * `targetName` (isteğe bağlı, 2026-09-27 a11y A1): düğme bir LİSTE/IZGARA öğesinin içindeyse (izleme kartı) hedefin adı —
 * erişilebilir ad "<hedef> — Bağlantıyı kopyala" olur; 50 kartlık ızgarada 50 özdeş "Bağlantıyı kopyala" duyulmaz.
 * Görünen `title` kısa kalır. Verilmezse eski ad (sayfa başlığı / tek pencere kullanımları).
 */
export default function CopyLinkButton({ className, variant = 'secondary', size, iconOnly = false, url = null, targetName = null }) {
  const t = useT()
  const copyLink = useCopyLink()

  async function copy(e) {
    if (e) e.stopPropagation()
    await copyLink(url)
  }

  const label = targetName ? t('a11y.rowAction', targetName, t('share.copyLink')) : t('share.copyLink')
  return (
    <Button type="button" variant={variant} size={size ?? (iconOnly ? 'icon-sm' : 'sm')} className={className} onClick={copy}
      title={t('share.copyLink')} aria-label={label}>
      <Link2 size={14} />{iconOnly ? null : <> {t('share.copyLink')}</>}
    </Button>
  )
}
