import { Pencil, Copy, Trash2, X } from 'lucide-react'
import { CheckNowButton, CheckRunningStrip } from './CheckRunning.jsx'
import { useT } from '../../i18n/index.jsx'

/**
 * Detay modalının sağ üstündeki hızlı eylemler — dokuz izleme türünde TEK yerden.
 *
 * <p><b>Neden var.</b> Detayı açan kişi kontrol koşturmak ya da ayarı düzeltmek için modalı
 * kapatıp karta dönmek zorundaydı; eylemler yalnız kartta vardı. Sertifika modalına eklenince
 * ortaya bir tutarsızlık çıktı: aynı iş bir türde başlıktan, sekizinde yalnız karttan
 * yapılıyordu. Bu bileşen o farkı kapatır.
 *
 * <p><b>Sözleşme: modal başlığı, KARTIN eylemlerini yansıtır — fazlasını değil.</b> Her tür
 * yalnız kendi kartında olan eylemi geçirir (Port/DNS'te Sil de var, diğerlerinde yok).
 * Böylece "standart" görünüm birliği olur, türlere olmayan yetenek uydurmak olmaz. Eylemin
 * KENDİSİ de paylaşılmaz: tetikleyiciyi sayfa verir, burada yalnız sunum durur —
 * {@link MonitorCardActions} ile aynı bölüşüm.
 *
 * <p>Görsel dil kartlarınkiyle birebir (`mon-act` ikon düğmeleri, `CheckNowButton`), çünkü
 * detay modalının başlığı kartla AYNI açık yüzeyde duruyor. Sertifika modalı ayrı kalır: onun
 * başlığı koyu degrade ve kendi `modal-header-act` sınıfını kullanır — aynı DESEN, farklı zemin.
 *
 * @param {boolean}  running       bu monitör şu anda kontrol ediliyor (şerit + düğme kilidi)
 * @param {Function} onCheck       "Şimdi kontrol et" — verilmezse düğme çizilmez
 * @param {boolean}  checkDisabled çalışma dışı bir sebeple kapalı (ör. k6 kurulu değil)
 * @param {Function} onDelete      verilmezse Sil çizilmez (yalnız kartında Sil olan türler)
 * @param {Function} onClose       modalı kapatır — ZORUNLU
 * @param {ReactNode} children     sayfaya özgü ek düğme (ör. bağlantı kopyala); ayraçtan önce
 */
export default function MonitorModalActions({
  running = false, onCheck, checkDisabled = false, checkTitle,
  onEdit, editTitle,
  onDuplicate,
  onDelete, deleting = false, deleteTitle,
  onClose, closeLabel,
  // DNS modalı kendi kapatma sınıfını taşıyor (`dns-modal-close`); geri kalan sekiz tür
  // `upt-modal-close` kullanıyor. Tek fark bu olduğu için sınıf prop, bileşen değil.
  closeClassName = 'upt-modal-close',
  children,
}) {
  const t = useT()
  return (
    <div className="upt-modal-actions">
      {/* Şerit düğmelerin SOLUNDA: göz zaten az önce tıklanan yerde. Koşmuyorken null döner. */}
      <CheckRunningStrip running={running} />
      {onCheck && (
        <CheckNowButton running={running} disabled={checkDisabled} onClick={onCheck} title={checkTitle} />
      )}
      {onEdit && (
        <button type="button" className="mon-act mon-act--edit" onClick={onEdit}
          title={editTitle} aria-label={editTitle}><Pencil size={13} /></button>
      )}
      {onDuplicate && (
        <button type="button" className="mon-act mon-act--copy" onClick={onDuplicate}
          title={t('mon.duplicate')} aria-label={t('mon.duplicate')}><Copy size={13} /></button>
      )}
      {onDelete && (
        <button type="button" className="mon-act mon-act--danger" onClick={onDelete} disabled={deleting}
          title={deleteTitle} aria-label={deleteTitle}><Trash2 size={13} /></button>
      )}
      {children}
      {/* Kapatma kendi bölmesinde: sertifika modalında yıkıcı düğmeye değdiği görüldü. */}
      <span className="upt-modal-actions-sep" aria-hidden="true" />
      <button type="button" className={closeClassName} onClick={onClose}
        aria-label={closeLabel || t('dns.close')}><X size={18} /></button>
    </div>
  )
}
