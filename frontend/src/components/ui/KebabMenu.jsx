import { useRef } from 'react'
import { Menu } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'

/**
 * Tablo "İşlem" sütunu / kart başlığı için 3-çizgili (hamburger) menü. Butonları tek bir
 * açılır menüde toplar. items: [{ label, icon?, onClick, danger?, hidden? }].
 * İç uygulama shadcn DropdownMenu (Radix); dış API değişmedi.
 *
 * <h3>Neden PORTAL</h3>
 * Menü `document.body`'ye portal'lanır (Radix Portal). Şablon kartlarında
 * (`.sc-tpl-card:hover { transform: translateY(-1px) }`) menü kartın İÇİNDE çizildiğinde
 * `transform`'lu ata fixed konumlandırmayı viewport'tan koparıyordu — "bazen açılmıyor, bazen
 * başka yerde açılıyor" (2026-08-21). Portal atayı devreden çıkarır. Konum/viewport'a sığdırma
 * artık Radix Popper'da (altta yer yoksa üste, sağda yer yoksa sola çevrilir; kaydırmada izler).
 *
 * <h3>modal={false}</h3>
 * Modal kipte Radix body'ye `pointer-events: none` koyar: açık bir menü varken BAŞKA bir satırın
 * tetiğine basmak yalnız ilk menüyü kapatır, ikincisini açmazdı (iki tık). Eski davranış — başka
 * tetiğe basınca eskisi kapanır, yenisi AYNI basışta açılır, ekranda hep tek menü — korunur.
 *
 * <h3>placement</h3>
 * `'bottom'` (varsayılan): butonun ALTINA, sağ kenarları hizalı — tablo "İşlem" sütununun
 * alışılmış davranışı. `'right'`: butonun SAĞINA, üst kenarları hizalı. İkincisi kart
 * ızgaralarında gerekiyor: aşağı açılan menü kartın kendi içeriğini örtüyor ve kullanıcı
 * hangi kaydın menüsünü açtığını göremiyordu. Sağda yer yoksa sola düşer.
 *
 * <h3>z-index</h3>
 * shadcn varsayılanı `z-50` legacy modal scrim'inin (--z-modal 2000+) ALTINDA kalır → menü
 * ModalShell içindeki tablolarda görünmüyor, "tıklanmıyor" sanılıyordu (2026-09-20 Kullanıcı
 * Dizini). Token: --z-menu (modal ve Dialog üstü, toast altı).
 *
 * Satır tıklaması: menü içindeki tıklamalar React ağacında çağıranın hücresine kabarır; satırı
 * açmaması için çağıran hücre yayılımı durdurur (CertificatesTable `onClick={stop}`) — eskisiyle
 * aynı sözleşme.
 * Görünür item yoksa hiçbir şey render etmez (yetkisiz kullanıcıda boş hücre).
 */
export default function KebabMenu({ items = [], label = 'İşlemler', rowLabel = null, placement = 'bottom' }) {
  const selectedRef = useRef(false)   // kapanış bir EYLEM seçiminden mi geldi (odak dönüşü için)
  const visible = (items || []).filter((it) => it && !it.hidden)
  if (visible.length === 0) return null

  const right = placement === 'right'

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        {/* aria-label SATIRI ayırt eder, title kısa kalır: 200 satırlık bir tabloda ekran
            okuyucu 200 kez "İşlemler, menü" okuyordu — hangi kaydın silme menüsünde olunduğu
            duyulmuyordu. rowLabel verilmezse davranış eskisiyle birebir aynı. */}
        {/* print:hidden — eski sarmalayıcı (.wr-menu-wrap) yazdırmada gizleniyordu; aynı davranış. */}
        <Button variant="ghost" size="icon-sm" className="size-7 text-muted-foreground print:hidden"
          title={label} aria-label={rowLabel ? `${rowLabel} — ${label}` : label}>
          <Menu aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        side={right ? 'right' : 'bottom'}
        align={right ? 'start' : 'end'}
        collisionPadding={8}
        className="z-(--z-menu)"
        onCloseAutoFocus={(e) => {
          // Radix kapanışta odağı tetiğe döndürür (bir tık sonra, setTimeout). Seçilen eylem bir
          // modal açtıysa (Düzenle, Geçmiş…) odak o anda modalın ilk alanındadır; tetiğe çekmek
          // onu çalar ve klavye kullanıcısı scrim'in arkasına düşerdi. Yalnız EYLEM seçiminden
          // sonra ve odak gerçekten bir yere taşındıysa engellenir; Escape / dışarı tıklama
          // kapanışları Radix'in varsayılanıyla aynen çalışır.
          const picked = selectedRef.current
          selectedRef.current = false
          const a = document.activeElement
          if (picked && a && a !== document.body) e.preventDefault()
        }}
      >
        {visible.map((it, i) => (
          <DropdownMenuItem key={i} variant={it.danger ? 'destructive' : 'default'}
            onSelect={() => { selectedRef.current = true; it.onClick() }}>
            {it.icon}{it.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
