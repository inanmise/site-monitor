import { Pencil, Copy } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { CheckNowButton, CheckRunningStrip } from './ui/CheckRunning.jsx'

/**
 * İzleme kartının sağ alt köşesindeki eylem düğmeleri: Şimdi Kontrol Et / Düzenle / Kopyala.
 *
 * <p>Bu blok beş izleme sayfasında (domain/http/keyword/page/ping) birebir aynıydı; tek fark
 * i18n anahtarlarının öneki ({@code http.check} / {@code dom.check} …), o da prop olarak
 * dışarı alındı. Sentetik İzleme sayfası KAPSAM DIŞI: orada kontrol düğmesinin ek bir koşulu
 * var (k6 kurulu mu) ve "hiç çalışmadı" metni farklı — zorla birleştirmek o farkları gizlerdi.
 * (Düğmenin KENDİSİ yine de paylaşılıyor: {@link CheckNowButton}. Böylece farklar bozulmadan
 * görünüm ve "çalışıyor" davranışı on sayfada tek yerden geliyor.)
 *
 * <p>Kritik ayrıntı — <b>{@code stopPropagation} şart</b>: kart gövdesinin kendi
 * {@code onClick}'i detay modalını açıyor. Sarmalayıcıdaki durdurma olmazsa "Düzenle"ye basmak
 * hem düzenleme formunu hem detay modalını açar; kullanıcı üst üste iki pencere görür. Beş
 * kopyada yaşayan bir ayrıntıydı ve testi yoktu.
 *
 * <p>Kontrol çalışırken düğme kilitlenir VE döner; yanında geçen süre sayacı belirir. Eskiden
 * tek geri bildirim düğmenin grileşmesiydi: sayfa hızı gibi 12–15 saniyelik bir kontrolde
 * kullanıcı hiçbir şey olmadığını sanıp tekrar tıklıyordu.
 *
 * @param {boolean} running               bu monitör ŞU AN kontrol ediliyor
 * @param {Function} onCheck              "Şimdi Kontrol Et"
 * @param {Function} onEdit               "Düzenle"
 * @param {Function} onDuplicate          "Kopyala"
 * @param {string} checkTitle             kontrol düğmesinin ipucu metni (sayfaya özgü i18n)
 * @param {string} editTitle              düzenle düğmesinin ipucu metni (sayfaya özgü i18n)
 */
export default function MonitorCardActions({
  running, onCheck, onEdit, onDuplicate, checkTitle, editTitle,
  // Kontrol dugmesi PASIF olabilmeli: senaryo izlemesinde k6 yoksa calistirmak anlamsiz.
  // Bu tek fark yuzunden ScriptedMonitorPage bloğun tamamini kopyalamisti.
  checkDisabled = false,
}) {
  const t = useT()
  return (
    <span className="mon-actions" onClick={e => e.stopPropagation()}>
      <CheckRunningStrip running={running} />
      <CheckNowButton running={running} disabled={checkDisabled} onClick={onCheck} title={checkTitle} />
      <button type="button" className="mon-act mon-act--edit"
        onClick={onEdit} title={editTitle} aria-label={editTitle}><Pencil size={13} /></button>
      <button type="button" className="mon-act mon-act--copy"
        onClick={onDuplicate} title={t('mon.duplicate')} aria-label={t('mon.duplicate')}><Copy size={13} /></button>
    </span>
  )
}
