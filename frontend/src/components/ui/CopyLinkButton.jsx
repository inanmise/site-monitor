import { Link2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useToast } from './Toast.jsx'
import { copyText } from '../../utils/copyText.js'

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
export default function CopyLinkButton({ className = 'btn btn-secondary btn-sm', iconOnly = false, url = null }) {
  const t = useT()
  const toast = useToast()

  async function copy(e) {
    if (e) e.stopPropagation()
    const target = url || window.location.href
    if (await copyText(target)) toast.success(t('share.copied'))
    else toast.error(target)   // son çare: URL'i göster, kullanıcı elle kopyalar
  }

  return (
    <button type="button" className={className} onClick={copy}
      title={t('share.copyLink')} aria-label={t('share.copyLink')}>
      <Link2 size={14} />{iconOnly ? null : <> {t('share.copyLink')}</>}
    </button>
  )
}
