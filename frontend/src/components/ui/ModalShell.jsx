import { createContext, useContext, useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/shadcn/button'
import {
  Dialog, DialogClose, DialogContent, DialogFooter, DialogHeader, DialogPortal, DialogTitle,
} from '@/components/shadcn/dialog'
import { useT } from '../../i18n/index.jsx'

/**
 * Modal KABUĞU — davranış, içerik değil. Yeni modallar buradan kurulur.
 * İç uygulama shadcn Dialog (Radix); dış API (prop'lar) değişmedi.
 *
 * Neden var: repodaki modallar `.modal-overlay` + `<div role="dialog">` elle kuruyor ve her
 * biri farklı bir alt küme uyguluyor — kimi Escape'i dinliyor, kimi dinlemiyor; hiçbirinde
 * odak tuzağı, odak iadesi ya da arka plan scroll kilidi yok. IssueReportModal'da bunların
 * tamamı eksikti (çökme sonrası açılan bir modaldan Tab ile arkadaki ölü ekrana düşülüyordu).
 *
 * Sağladıkları:
 *   • body'ye portal (Radix Portal) — ağaçtaki yerine bağlı z-index/overflow sürprizi olmaz
 *   • role="dialog" + aria-modal + aria-labelledby (DialogTitle otomatik bağlanır)
 *   • Escape — Radix katman yığını: iç içe kabuklarda, üstte açılan onay penceresinde (useDialog)
 *     ya da açık bir açılır menüde tuşu YALNIZ en üstteki katman alır. Eski `openShells` kayıt
 *     defteri ve `.dlg-overlay` sınıf kontrolü bunun yerine geçti. İşlenen Escape
 *     `preventDefault` ile işaretlenir → useEscapeKey'li eski detay modalları alttan kapanmaz.
 *   • Odak — açılışta ilk odaklanabilir öğeye (X; `autoFocus` taşıyan alan varsa o), kapanışta
 *     tetikleyiciye geri; Tab/Shift+Tab döngüsü kabukta kalır (FocusScope loop)
 *   • Arka plan scroll kilidi — sayaçlı (iç içe modalda erken açılmaz)
 *   • Katman — derinliğe göre z-index, iç kabuk dıştakinin üstünde kalır
 *
 * NEDEN `modal={false}` + kendi scrim'imiz: Radix'in modal kipi body'ye `pointer-events: none`
 * koyar ve odağı DOM kapsamına göre hapseder. Kabuğun içinden body'ye portal'lanan HAM öğeler
 * (TeamMembersModal üstünde açılan UserEditModal, HelpTip balonu, takvim açılır penceresi,
 * ng-mailpop) bu yüzden tıklanamaz ve odak alamaz hâle gelirdi — form yazılamazdı. Eski kabuğun
 * sözleşmesi zaten "scrim sayfayı örter + Tab kabukta döner + aria-modal" idi; non-modal Radix +
 * kendi scrim'imiz onu birebir korur. Dış tıklama/odak kaybı Radix'e kapattırılmaz
 * (`onInteractOutside` yutulur); kapatma yolları scrim, X ve Escape'tir.
 *
 * `busy` iken kapatma yolları (Escape, scrim tıklaması, X) devre dışıdır — gönderim sürerken
 * yanlışlıkla kapatmayı önler.
 *
 * `dismissOnBackdrop={false}` scrim tıklamasını TAMAMEN kapatır (Escape ve X açık kalır).
 * İçinde uzun emek biriken formlar için: yanlışlıkla kenara tıklamak yazılanların hepsini
 * götürüyordu ve geri alma yolu yok. Varsayılan `true` — mevcut modalların davranışı değişmesin.
 *
 * `closeOnNavigate` (isteğe bağlı, varsayılan KAPALI — 2026-09-28, Ek 3/2): App düzeyinde yaşayan (sekmeden bağımsız)
 * bir pencerenin İÇİNDEN başlayan uygulama içi gezinme (`navigateTo` → `sm:navigate`: 7/24 göstergesinin "7/24
 * Kapsamı'nda gör"ü, kesinti çizelgesi, alarm olay kartı, takım üyeleri penceresinin kapsam bağlantısı…) pencereyi
 * kapatır — yoksa sekme pencerenin ARKASINDA değişir, pencere yeni ekranın üstünde açık kalırdı. "İçeriden" = gezinme,
 * kabuğun REACT ağacındaki bir tıklamanın işlenişi sırasında yayıldı (portal'lı açılır pencereler / iç içe kabuklar
 * dâhil — React olayı ağaç boyunca kabarcıklanır). Dışarıdan gelen gezinme (derin bağlantı, palet, Geri) pencereye
 * DOKUNMAZ. Sayfayla birlikte sökülen pencerelere (izleme detayları) gerekmez: sekme değişince zaten kapanırlar.
 *
 * `scrollBody` uzun formlar içindir: kutuya yükseklik tavanı koyar, YALNIZ gövdeyi kaydırır ve
 * `footer`'ı daima görünür tutar. Düğmeler bu modda `footer` ile verilmeli — gövdenin içine
 * konursa onlar da kaydırma alanında kalır ve amaç boşa gider.
 *
 * Yerleşim notları (eski hatalar tekrarlanmasın):
 *   • Genişlik sınıfları cn/twMerge ile shadcn'in `sm:max-w-lg`'sinin YERİNE geçer; kutuda artık
 *     legacy `.modal-box` sınıfı YOK. Eskiden `.modal-box`'ın max-width'i aynı özgüllükte ve
 *     sonra geldiği için lg/xl/full hiç uygulanmıyordu; App.css katmansız olduğundan legacy bir
 *     sınıf bugün de Tailwind'i ezerdi.
 *   • shadcn içeriği viewport'a SABİT ortalanır: tavansız uzun içerik ekranın üstünden/altından
 *     taşar ve kaydırılamaz. Bu yüzden scrollBody kapalıyken de kutu viewport yüksekliğiyle
 *     sınırlı ve kendi içinde kayar (eskiden bu işi `.modal-overlay`ın kaydırması görüyordu).
 */

/**
 * İç içe derinlik REACT AĞACINDAN okunur, effect sırasından değil. React effect'leri
 * çocuktan ebeveyne çalıştırır: aynı anda mount edilen iki iç içe kabukta içteki ÖNCE
 * kaydolur, dolayısıyla "en son kaydolan en üsttedir" varsayımı ters sonuç verirdi.
 */
const DepthCtx = createContext(0)

// Katman tabanı = App.css `--z-modal` (2000). SAYI olarak tutuluyor: derinlik başına +10 eklenir
// (UserEditModal'ın `.modal-overlay--top` 2100'ü bu aralığın üstünde kalır). Scrim taban
// değerde, kutu +1'de — iç içe portal'larda body'ye ekleme sırası tersine dönebilir.
const MODAL_Z = 2000
const DEPTH_STEP = 10

// Scroll kilidi sayacı: iç içe modalda dıştaki hâlâ açıkken kilit açılmamalı.
let scrollLocks = 0
let savedOverflow = ''

// Genişlikler eski kabukla aynı (460/620/900/1140/96vw); dar ekranda 1rem kenar payı korunur.
const SIZE_CLASS = {
  sm: 'sm:max-w-[min(460px,calc(100%-2rem))]',
  md: 'sm:max-w-[min(620px,calc(100%-2rem))]',
  lg: 'sm:max-w-[min(900px,calc(100%-2rem))]',
  xl: 'sm:max-w-[min(1140px,calc(100%-2rem))]',
  full: 'sm:max-w-[min(96vw,calc(100%-2rem))]',
}

// scrollBody: kutu sütun-flex + tavan; gövde `min-h-0` ŞART — flex öğesinin varsayılan
// `min-height:auto`'su içeriğe göre büyür ve overflow hiç devreye girmez (altlık aşağı kaçar).
const SCROLL_BOX = 'flex max-h-[min(88vh,calc(100dvh-2rem))] flex-col overflow-hidden'
const SCROLL_BODY = '-mr-1 min-h-0 flex-1 overflow-y-auto pr-1'
const PLAIN_BOX = 'max-h-[calc(100dvh-2rem)] overflow-y-auto'

// Telefon (< 640 px, 2026-10-09): shadcn'in 24 px iç boşluğu ve 2 × 16 px kenarı 360 px ekranda içeriğe ~264 px
// bırakıyordu → 16 px iç boşluk, 2 × 8 px kenar. sm+ HİÇ değişmez. Çağıranın VARYANTSIZ kendi iç boşluğu / genişlik
// tavanı varsa (ör. SchemaDiagramModal `p-3 max-w-[…]`) eklenmez: twMerge farklı varyantları çakışma saymaz ve
// `max-sm:p-4` telefonda çağıranın `p-3`'ünü ezerdi. `max-sm:` önekli çağıran sınıfları (PHONE_FULLSCREEN…) twMerge
// ile zaten bunun yerine geçer.
const PHONE_PAD = 'max-sm:p-4'
const PHONE_WIDTH = 'max-sm:max-w-[calc(100%-1rem)]'
const OWN_PAD = /(?:^|\s)p[xytrblse]?-/
const OWN_MAX_W = /(?:^|\s)max-w-/
function phoneBoxClass(className) {
  const cls = typeof className === 'string' ? className : ''
  return cn(!OWN_PAD.test(cls) && PHONE_PAD, !OWN_MAX_W.test(cls) && PHONE_WIDTH)
}

/**
 * Düz metin başlık bir span'e sarılır: flex DialogTitle içindeki çıplak metin düğümü anonim flex öğesi olur ve en
 * uzun kelimesinden (uzun alan adı) dar KÜÇÜLEMEZ → X'in altına girip telefonda yatay kaydırma açıyordu.
 * Öğe olarak verilen başlık (ör. UploadWizard'ın `truncate` span'i) olduğu gibi kalır — çağıran kendi kısaltmasını yönetir.
 */
function titleNode(title) {
  if (typeof title === 'string' || typeof title === 'number') {
    return <span data-slot="modal-shell-title-text" className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
  }
  return title
}

/*
 * Ek (isteğe bağlı) kancalar — varsayılanlarıyla eski davranış birebir aynı:
 *   • `headerExtra`     başlık satırında, başlık ile kapat düğmesi arasında çizilir (ör. "Kaydediliyor… N sn"
 *                       şeridi, detay penceresinin eylem düğmeleri).
 *   • `hideClose`       yerleşik X çizilmez — çağıran kendi kapatmasını `headerExtra` içinde veriyorsa
 *                       (MonitorModalActions'ın X'i) iki kapat düğmesi olmasın.
 *   • `bodyRef`         kaydırılan gövdenin ref'i (useModalScrollHint "devamı için kaydırın" ipucu).
 *   • `dismissOnEscape` false → Escape pencereyi KAPATMAZ (emek biriktiren izleme formları eskiden de
 *                       Escape ile kapanmıyordu). Üstte açık bir liste/menü Escape'i yine önce kendisi alır.
 */
export default function ModalShell({
  open, onClose, title, icon: Icon, size = 'md', busy = false,
  dismissOnBackdrop = true, dismissOnEscape = true, scrollBody = false,
  closeLabel, footer, children, className = '',
  headerExtra = null, hideClose = false, bodyRef, closeOnNavigate = false,
}) {
  const t = useT()
  const parentDepth = useContext(DepthCtx)

  // İçeriden gezinmede kapan (`closeOnNavigate`). Bayrak tıklamanın YAKALAMA evresinde kalkar ve olay turu bitince
  // (setTimeout 0) iner: tıklama işleyicisindeki eşzamanlı `navigateTo` onu görür, sonraki dış gezinme görmez.
  // Mikro görev YETMEZ — React yakalama ve kabarcık evrelerini ayrı kök dinleyicilerinde koşar, arada mikro görevler boşalır.
  const navFromInside = useRef(false)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  useEffect(() => {
    if (!open || !closeOnNavigate) return undefined
    const onNav = () => { if (navFromInside.current) onCloseRef.current?.() }
    window.addEventListener('sm:navigate', onNav)
    return () => window.removeEventListener('sm:navigate', onNav)
  }, [open, closeOnNavigate])
  const markInside = closeOnNavigate ? () => {
    navFromInside.current = true
    setTimeout(() => { navFromInside.current = false }, 0)
  } : undefined

  // Odak iadesi: kapanışta tetikleyiciye. Radix yalnız kendi DialogTrigger'ına döner (burada
  // tetik yok), bu yüzden iade kabukta; açılıştaki odağı Radix FocusScope verir.
  useEffect(() => {
    if (!open) return
    const previous = document.activeElement
    return () => {
      if (previous && typeof previous.focus === 'function' && document.contains(previous)) {
        previous.focus()
      }
    }
  }, [open])

  // Arka plan scroll kilidi (sayaçlı).
  useEffect(() => {
    if (!open) return
    if (scrollLocks === 0) {
      savedOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
    }
    scrollLocks += 1
    return () => {
      scrollLocks -= 1
      if (scrollLocks === 0) document.body.style.overflow = savedOverflow
    }
  }, [open])

  if (!open) return null

  const z = MODAL_Z + parentDepth * DEPTH_STEP

  return (
    <DepthCtx.Provider value={parentDepth + 1}>
      <Dialog open modal={false} onOpenChange={(next) => { if (!next && !busy) onClose?.() }}>
        {/* Scrim: Radix non-modal kipte DialogOverlay çizmez; görünüm shadcn örtüsüyle aynı.
            Yalnız scrim'in KENDİSİNE tıklama kapatır (kutudan başlayan sürükleme kapatmaz). */}
        <DialogPortal>
          <div
            data-slot="dialog-overlay"
            aria-hidden="true"
            className="fixed inset-0 bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
            style={{ zIndex: z }}
            onClick={(e) => {
              if (!dismissOnBackdrop || busy) return
              if (e.target === e.currentTarget) onClose?.()
            }}
          />
        </DialogPortal>
        <DialogContent
          showCloseButton={false}
          aria-modal="true"
          data-size={size}
          data-scroll-body={scrollBody ? 'true' : undefined}
          style={{ zIndex: z + 1 }}
          className={cn(SIZE_CLASS[size] ?? SIZE_CLASS.md, scrollBody ? SCROLL_BOX : PLAIN_BOX, phoneBoxClass(className), className)}
          // Dış tıklama / odağın dışarı (toast, portal'lı menü, üstte açılan ham modal) kayması
          // kabuğu KAPATMAZ — eski sözleşme: yalnız scrim, X ve Escape.
          onInteractOutside={(e) => e.preventDefault()}
          onEscapeKeyDown={(e) => { if (busy || !dismissOnEscape) e.preventDefault() }}
          // Radix tetiksiz kapanışta odağı hiçbir yere vermez; iade yukarıdaki effect'te.
          onCloseAutoFocus={(e) => e.preventDefault()}
          onClickCapture={markInside}
        >
          <DialogHeader className="shrink-0 flex-row items-center justify-between gap-3 text-left">
            <DialogTitle className="flex min-w-0 items-center gap-2 leading-snug">
              {Icon && <Icon size={18} aria-hidden="true" className="shrink-0" />}
              {titleNode(title)}
            </DialogTitle>
            {headerExtra}
            {!hideClose && (
              <DialogClose asChild>
                <Button type="button" variant="ghost" size="icon-sm" disabled={busy}
                  className="-my-1 -mr-2 shrink-0 text-muted-foreground pointer-coarse:size-10"
                  aria-label={closeLabel || t('app.close')}>
                  <X aria-hidden="true" />
                </Button>
              </DialogClose>
            )}
          </DialogHeader>

          <div data-slot="modal-shell-body" ref={bodyRef} className={cn('min-w-0', scrollBody && SCROLL_BODY)}>
            {children}
          </div>

          {footer && <DialogFooter className="shrink-0 border-t pt-4">{footer}</DialogFooter>}
        </DialogContent>
      </Dialog>
    </DepthCtx.Provider>
  )
}
