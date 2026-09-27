import { Pencil, Copy, Trash2, Play } from 'lucide-react'
import { useMonitorCard } from './monitoring/MonitorCard.jsx'
import { Spinner } from './ui/Progress.jsx'
import { useT } from '../i18n/index.jsx'
import { CheckNowButton, CheckRunningStrip, MON_ACT, MON_ACT_TONE } from './ui/CheckRunning.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import KebabMenu from './ui/KebabMenu.jsx'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * İzleme kartının sağ alt köşesindeki eylem düğmeleri: Şimdi Kontrol Et / Düzenle / Kopyala.
 * Çizim shadcn Button (`MON_ACT` ikon düğmesi dili — MonitorModalActions ile AYNI), ipuçları shadcn Tooltip.
 *
 * <p>Bu blok beş izleme sayfasında (domain/http/keyword/page/ping) birebir aynıydı; tek fark
 * i18n anahtarlarının öneki ({@code http.check} / {@code dom.check} …), o da prop olarak
 * dışarı alındı. Sentetik İzleme sayfası KAPSAM DIŞI: orada kontrol düğmesinin ek bir koşulu
 * var (k6 kurulu mu) ve "hiç çalışmadı" metni farklı — zorla birleştirmek o farkları gizlerdi.
 * (Düğmenin KENDİSİ yine de paylaşılıyor: {@link CheckNowButton}. Böylece farklar bozulmadan
 * görünüm ve "çalışıyor" davranışı on sayfada tek yerden geliyor.)
 *
 * <p>Kritik ayrıntı — <b>{@code stopPropagation} şart</b>: tıklanabilir bir kabın (kart/satır)
 * içinde durduğunda kabın kendi {@code onClick}'i detay modalını açar. Sarmalayıcıdaki durdurma
 * olmazsa "Düzenle"ye basmak hem düzenleme formunu hem detay modalını açar; kullanıcı üst üste iki
 * pencere görür. (İzleme kartı artık "stretched button" deseninde — eylemler örtünün üstünde —
 * ama bileşen kabın ne olduğunu varsaymaz.)
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
 * @param {Function} onDelete             "Sil" — verilmezse düğme çizilmez (yetkisi olmayan
 *                                        ya da silinemeyen satırda sayfa undefined geçirir)
 * @param {boolean} deleting              bu satır ŞU AN siliniyor (çift tık koruması)
 * @param {string} deleteTitle            sil düğmesinin ipucu metni (sayfaya özgü i18n)
 * @param {string} rowLabel               kartın kimliği (alan adı / URL / host:port / ad) — düğmelerin
 *                                        ERİŞİLEBİLİR ADINA eklenir (KebabMenu `rowLabel` deseni). Yoksa
 *                                        50 kartlık ızgarada 50 özdeş "Sil" duyuluyordu (2026-09-25, R15).
 *                                        İpucu kısa kalır; ad satırı ayırır.
 * @param {Function} onResume             "Sürdür" — kart DURAKLATILMIŞSA (MonitorCard `inactive`) etiketli birincil düğme
 *                                        olarak çizilir; etkin kartta hiç görünmez. Sayfa `useMonitorResume` ile verir
 *                                        (kullanıcı isteği 2026-09-26: tüm izleme sayfalarında varsayılan).
 * @param {boolean} resuming              bu kart ŞU AN yeniden etkinleştiriliyor (çift tık koruması + dönen simge)
 * @param {boolean} phoneMenu             (isteğe bağlı, 2026-09-27 Alan Adı kartı; varsayılan KAPALI — diğer sayfalarda
 *                                        çıktı aynı) telefonda (< 640 px) Düzenle / Kopyala / Sil ikonları gizlenir, yerine
 *                                        tek "Diğer işlemler" menüsü (ui/KebabMenu, adı satırı taşır: Düzenle · Kopyala ·
 *                                        `menuItems` · Sil) çizilir; Sürdür ve Şimdi kontrol et görünür kalır — sertifika
 *                                        kartının telefon düzeniyle aynı. Geniş ekranda davranış değişmez.
 * @param {Array} menuItems               telefon menüsüne eklenen öğeler (KebabMenu `items` biçimi) — yalnız `phoneMenu` ile
 */
/** Dokunmatik işaretçide (telefon/tablet) ikon düğmeleri 40 px dokunma hedefi alır; farede yoğun 28 px kalır. */
const TOUCH = 'pointer-coarse:size-10'

export default function MonitorCardActions({
  running, onCheck, onEdit, onDuplicate, checkTitle, editTitle, rowLabel,
  // Kontrol dugmesi PASIF olabilmeli: senaryo izlemesinde k6 yoksa calistirmak anlamsiz.
  // Bu tek fark yuzunden ScriptedMonitorPage bloğun tamamini kopyalamisti.
  checkDisabled = false,
  // Silme BU BİLEŞENİN İÇİNDE. Dışarıda kardeş olarak çizilseydi yukarıdaki `stopPropagation`
  // sarmalayıcısının DIŞINDA kalırdı: silmeye basmak kart gövdesinin `onClick`ini de tetikler,
  // kullanıcı hem onay diyaloğunu hem detay modalını görürdü — Düzenle için düzeltilen kusurun
  // YIKICI eylemdeki hâli. Port/DNS bunu kendi ek sarmalayıcısıyla çözüyordu; artık tek sarmalayıcı.
  onDelete, deleting = false, deleteTitle,
  onResume, resuming = false,
  phoneMenu = false, menuItems = [],
}) {
  const t = useT()
  const { inactive } = useMonitorCard()
  const named = (label) => (rowLabel ? t('a11y.rowAction', rowLabel, label) : label)
  const phoneHidden = phoneMenu ? 'max-sm:hidden' : null
  return (
    <span data-slot="monitor-card-actions" className="inline-flex flex-wrap items-center justify-end gap-1.5" onClick={e => e.stopPropagation()}>
      {inactive && onResume && (
        // Duraklatılmış kartın ANA eylemi: metinli, birincil, en başta. Dokunmatikte 40 px.
        <Button type="button" size="sm" data-slot="monitor-resume" disabled={resuming} onClick={onResume}
          className="h-7 gap-1.5 px-2.5 text-xs pointer-coarse:h-10" aria-label={named(t('mon.resume'))}>
          {resuming ? <Spinner size={13} inline decorative /> : <Play aria-hidden="true" className="size-3.5" />}
          {t('mon.resume')}
        </Button>
      )}
      <CheckRunningStrip running={running} />
      <CheckNowButton running={running} disabled={checkDisabled} onClick={onCheck} title={checkTitle} rowLabel={rowLabel} className={TOUCH} />
      <SimpleTooltip content={editTitle}>
        <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, TOUCH, MON_ACT_TONE.edit, phoneHidden)}
          onClick={onEdit} aria-label={named(editTitle)}><Pencil size={13} aria-hidden="true" /></Button>
      </SimpleTooltip>
      <SimpleTooltip content={t('mon.duplicate')}>
        <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, TOUCH, MON_ACT_TONE.copy, phoneHidden)}
          onClick={onDuplicate} aria-label={named(t('mon.duplicate'))}><Copy size={13} aria-hidden="true" /></Button>
      </SimpleTooltip>
      {onDelete && (
        <SimpleTooltip content={deleteTitle}>
          <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, TOUCH, MON_ACT_TONE.danger, phoneHidden)}
            disabled={deleting} onClick={onDelete} aria-label={named(deleteTitle)}><Trash2 size={13} aria-hidden="true" /></Button>
        </SimpleTooltip>
      )}
      {phoneMenu && (
        <span data-slot="monitor-card-more" className="sm:hidden">
          <KebabMenu rowLabel={rowLabel} label={t('alh.moreActions')} items={[
            { label: editTitle, icon: <Pencil aria-hidden="true" />, onClick: onEdit },
            { label: t('mon.duplicate'), icon: <Copy aria-hidden="true" />, onClick: onDuplicate },
            ...(menuItems || []),
            onDelete && { label: deleteTitle, icon: <Trash2 aria-hidden="true" />, onClick: onDelete, danger: true, hidden: !!deleting },
          ].filter(Boolean)} />
        </span>
      )}
    </span>
  )
}
