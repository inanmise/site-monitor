import { Play, Pencil, Copy } from 'lucide-react'
import { useT } from '../i18n/index.jsx'

/**
 * İzleme kartının sağ alt köşesindeki eylem düğmeleri: Şimdi Kontrol Et / Düzenle / Kopyala.
 *
 * <p>Bu blok beş izleme sayfasında (domain/http/keyword/page/ping) birebir aynıydı; tek fark
 * i18n anahtarlarının öneki ({@code http.check} / {@code dom.check} …), o da prop olarak
 * dışarı alındı. Sentetik İzleme sayfası KAPSAM DIŞI: orada kontrol düğmesinin ek bir koşulu
 * var (k6 kurulu mu) ve "hiç çalışmadı" metni farklı — zorla birleştirmek o farkları gizlerdi.
 *
 * <p>Kritik ayrıntı — <b>{@code stopPropagation} şart</b>: kart gövdesinin kendi
 * {@code onClick}'i detay modalını açıyor. Sarmalayıcıdaki durdurma olmazsa "Düzenle"ye basmak
 * hem düzenleme formunu hem detay modalını açar; kullanıcı üst üste iki pencere görür. Beş
 * kopyada yaşayan bir ayrıntıydı ve testi yoktu.
 *
 * <p>Kontrol düğmesi o satır kontrol edilirken devre dışı kalır (çift tetikleme yok).
 *
 * @param {number|string|null} checking   şu an kontrol edilen monitörün id'si
 * @param {number|string} monitorId       bu satırın id'si
 * @param {Function} onCheck              "Şimdi Kontrol Et"
 * @param {Function} onEdit               "Düzenle"
 * @param {Function} onDuplicate          "Kopyala"
 * @param {string} checkTitle             kontrol düğmesinin ipucu metni (sayfaya özgü i18n)
 * @param {string} editTitle              düzenle düğmesinin ipucu metni (sayfaya özgü i18n)
 */
export default function MonitorCardActions({
  checking, monitorId, onCheck, onEdit, onDuplicate, checkTitle, editTitle,
}) {
  const t = useT()
  return (
    <span style={{ display: 'flex', gap: 6 }} onClick={e => e.stopPropagation()}>
      <button className="btn btn-sm mon-btn-check" disabled={checking === monitorId}
        onClick={onCheck} title={checkTitle}><Play size={12} /></button>
      <button className="btn btn-sm mon-btn-edit"
        onClick={onEdit} title={editTitle}><Pencil size={12} /></button>
      <button className="btn btn-sm mon-btn-edit"
        onClick={onDuplicate} title={t('mon.duplicate')} aria-label={t('mon.duplicate')}><Copy size={12} /></button>
    </span>
  )
}
