import { Pencil, Copy, Trash2, X } from 'lucide-react'
import { CheckNowButton, CheckRunningStrip, MON_ACT, MON_ACT_TONE } from './CheckRunning.jsx'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

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
 * <p>Görsel dil kartlarınkiyle birebir (shadcn Button + `MON_ACT` ikon düğmesi dili,
 * `CheckNowButton`), çünkü detay modalının başlığı kartla AYNI açık yüzeyde duruyor. Sertifika
 * modalı ayrı kalır: onun başlığı koyu degrade ve kendi `modal-header-act` sınıfını kullanır —
 * aynı DESEN, farklı zemin.
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
  // `closeClassName` (DNS modalı `dns-modal-close` veriyordu) geriye uyum için hâlâ KABUL edilir
  // ama yok sayılır: kapatma artık shadcn Button ve `.upt-modal-close`/`.dns-modal-close` App.css
  // kuralları (katmansız) onun Tailwind stilini ezerdi. Dokuz türün kapatması da aynı görünür.
  children,
}) {
  const t = useT()
  return (
    <div data-slot="monitor-modal-actions" className="flex shrink-0 items-center gap-1.5">
      {/* Şerit düğmelerin SOLUNDA: göz zaten az önce tıklanan yerde. Koşmuyorken null döner. */}
      <CheckRunningStrip running={running} />
      {onCheck && (
        <CheckNowButton running={running} disabled={checkDisabled} onClick={onCheck} title={checkTitle} />
      )}
      {onEdit && (
        <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, MON_ACT_TONE.edit)}
          onClick={onEdit} title={editTitle} aria-label={editTitle}><Pencil size={13} /></Button>
      )}
      {onDuplicate && (
        <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, MON_ACT_TONE.copy)}
          onClick={onDuplicate} title={t('mon.duplicate')} aria-label={t('mon.duplicate')}><Copy size={13} /></Button>
      )}
      {onDelete && (
        <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, MON_ACT_TONE.danger)}
          onClick={onDelete} disabled={deleting} title={deleteTitle} aria-label={deleteTitle}><Trash2 size={13} /></Button>
      )}
      {children}
      {/* Kapatma kendi bölmesinde: sertifika modalında yıkıcı düğmeye değdiği görüldü. */}
      <Separator orientation="vertical" className="mx-0.5 data-[orientation=vertical]:h-5" />
      <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground hover:text-foreground"
        onClick={onClose} aria-label={closeLabel || t('dns.close')}><X className="size-[18px]" /></Button>
    </div>
  )
}
