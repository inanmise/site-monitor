import { ListChecks } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * İzleme sayfalarının araç çubuğundaki TOPLU kontrol düğmesi — panodaki "Şimdi Kontrol Et"in
 * karşılığı.
 *
 * <p><b>Neden nötr görünüyor:</b> pano düğmesi birincil (shadcn Button default), çünkü orada tek birincil
 * eylem odur. İzleme sayfalarında mavi düğme zaten "Yeni Monitör"; ikinci bir mavi düğme
 * hangisinin ana eylem olduğunu belirsizleştirirdi. Bu yüzden sınıf yanındaki "Yenile" ile
 * BİREBİR aynı (shadcn Button outline + sm); ikon farkı yeter. {@code RefreshCw}
 * bilerek kullanılmıyor — o simgeyi komşusu taşıyor.
 *
 * <p>Etiket SAYIYI taşır ("Şimdi Kontrol Et (12)"). İki sebep: karttaki tekil düğmenin
 * erişilebilir adı da "Şimdi kontrol et" ve çıplak etiket ikisini ayırt edilemez kılıyordu;
 * ayrıca sayı, basmadan önce "kaç izlemeyi tetikleyeceğim" sorusunu cevaplıyor.
 *
 * @param {number} count  kaç izleme kontrol edilebilir — 0 ise düğme HİÇ çizilmez
 */
export default function CheckAllButton({ count, running, done = 0, total = 0, onClick }) {
  const t = useT()
  // Kalıcı gri bir düğme yerine YÜZEY YOK: yetkisi olmayan (ör. ADMIN olmayan DNS kullanıcısı)
  // ya da listesi boş olan kullanıcıya basılamayan bir düğme göstermek bilgi değil gürültüdür.
  // Sayfaların `canWrite` kapılı düğmeleri de aynı davranıyor.
  if (!count) return null
  return (
    <Button
      type="button"
      variant="outline" size="sm"
      onClick={onClick}
      disabled={running}
      aria-busy={running || undefined}
      title={t('mon.checkAllTitle', count)}
    >
      <ListChecks size={14} />
      {running ? t('app.checkedOf', done, total) : t('mon.checkAll', count)}
    </Button>
  )
}
