import { useId, useRef, useState } from 'react'
import { Bell } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { useIsMobile } from '../hooks/use-mobile.js'
import { navigateTo } from '../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Sheet, SheetContent } from '@/components/shadcn/sheet'
import { SidebarMenuBadge, SidebarMenuButton } from '@/components/shadcn/sidebar'
import { cn } from '@/lib/utils'
import InboxPanel from './inbox/InboxPanel.jsx'
import { useInbox } from './inbox/useInbox.js'

export { fmtDuration } from './inbox/inboxModel.js'

/**
 * Bildirim kutusu (2026-09-12 #2 · v2 2026-09-20 · v3 2026-09-26 shadcn yeniden tasarım): Nav'daki zil — açık alarm,
 * son 24 saatte çözülen, bakım penceresi (aktif / yaklaşan), bugün son giriş günüyse eksik haftalık rapor, süresi
 * dolan istisna. Durum ve veri `inbox/useInbox`, gövde `inbox/InboxPanel`, satır `inbox/InboxRow`.
 *
 * <p>v3: masaüstünde/tablette zile bağlı Popover (≈420 px, en çok 70vh, kendi kaydırması); telefonda
 * (`useIsMobile`, <768) tam yükseklikte sağdan Sheet. Sekmeler Okunmamış / Tümü / Geçmiş, güne göre gruplama,
 * satır eylemleri (okundu say / izlemeye git / temizle); çoklu seçim kaldırıldı — "Tümünü temizle" ve
 * "Temizlenenleri göster" başlıktaki diğer işlemler menüsünde. Okundu / temizlendi kümeleri v2 anahtarlarıyla
 * localStorage'da. Açılınca odak panele, kapanınca zile (Radix). Zil `role="dialog"` adını başlıktan alır.
 *
 * @param variant  'sidebar' (varsayılan): kenar çubuğu menü öğesi + SidebarMenuBadge sayacı (ikon kipinde nokta);
 *                 'icon': mobil üst çubuk ikon düğmesi (40 px) + köşe sayacı (2026-09-26 kenar çubuğu yeniden tasarımı).
 * @param className / badgeClassName  yalnız 'sidebar' varyantı: Nav satır görünümünü (kutucuklu ikon, 40 px) ve rozet
 *                 konumunu dışarıdan giydirir (tur 2, 2026-09-26) — tetik dışında hiçbir şey değişmez.
 */
export default function InboxBell({ username, variant = 'sidebar', className, badgeClassName }) {
  const t = useT()
  const mobile = useIsMobile()
  const [open, setOpen] = useState(false)
  const [view, setView] = useState('unread')
  const inbox = useInbox(username, { open, view })
  const btnRef = useRef(null)
  const titleId = useId()
  const panelId = useId()
  const unread = inbox.unreadCount
  const tip = unread ? t('inbox.unread', unread) : t('inbox.title')

  function go(it, target) {
    inbox.markRead([it.key])
    setOpen(false)
    if (target === 'monitor' && it.monitor_tab) navigateTo(it.monitor_tab, it.monitor_params || undefined)
    else navigateTo(it.tab, it.params)
  }
  // Açılışta odak panelin köküne (başlık duyurulur, ilk düğme "seçilmiş" görünmez); kapanışta zile. Popover'da Radix
  // tetiğe döndürür; Sheet'te tetik yok (açılış elle) ve Safari tıklamada düğmeye odak vermez → açıkça zile.
  const focusPanel = (e) => { e.preventDefault(); document.getElementById(panelId)?.focus() }
  const focusBell = (e) => { e.preventDefault(); btnRef.current?.focus() }

  // Telefonda tetik Sheet'i kendi tıklamasıyla açar; masaüstünde Radix PopoverTrigger açar/kapatır
  // (ikisi birden bağlanırsa aynı tıklamada iki kez çevrilip hiç açılmazdı).
  const toggle = mobile ? () => setOpen((o) => !o) : undefined
  const wrap = (node) => (mobile ? node : <PopoverTrigger asChild>{node}</PopoverTrigger>)

  const trigger = variant === 'icon' ? (
    // Mobil üst çubuk: ikon düğme; sayaç köşede (yalnız görsel — sayı ipucunda/başlıkta)
    <Button ref={btnRef} type="button" variant="ghost" size="icon" className="relative size-10" onClick={toggle}
      aria-label={t('inbox.title')} aria-expanded={open} title={tip}>
      <Bell aria-hidden="true" className="size-5" />
      {unread > 0 && (
        <span aria-hidden="true" data-slot="inbox-count"
          className="absolute top-1 right-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] leading-none font-semibold text-white tabular-nums ring-2 ring-background">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Button>
  ) : (
    // Tetik: shadcn SidebarMenuButton (daraltılmış kenar çubuğunda ipucu); ikon kipinde rozet gizlenir → zilin köşesinde
    // nokta. data-slot açıkça verilir: PopoverTrigger (asChild) kendi kancasını geçirir, Nav satır sözleşmesi bu kancaya bakar.
    <SidebarMenuButton ref={btnRef} data-slot="sidebar-menu-button" className={cn('relative', className)} onClick={toggle}
      aria-label={t('inbox.title')} aria-expanded={open} tooltip={tip}>
      <Bell aria-hidden="true" />
      {unread > 0 && (
        <span aria-hidden="true" data-slot="inbox-dot"
          className="absolute top-1.5 left-[1.3rem] hidden size-2 rounded-full bg-destructive ring-2 ring-sidebar group-data-[collapsible=icon]:block" />
      )}
      <span>{t('inbox.title')}</span>
    </SidebarMenuButton>
  )

  const panel = (sheet) => (
    <InboxPanel inbox={inbox} view={view} onViewChange={setView} mobile={mobile} sheet={sheet} titleId={titleId} panelId={panelId}
      onOpenItem={(it) => go(it, 'alert')} onOpenMonitor={(it) => go(it, 'monitor')} />
  )

  return (
    <>
      <Popover open={open && !mobile} onOpenChange={setOpen}>
        {wrap(trigger)}
        {variant === 'sidebar' && unread > 0 && (
          <SidebarMenuBadge aria-hidden="true" className={cn('rounded-full bg-destructive text-white peer-hover/menu-button:text-white', badgeClassName)}>
            {unread > 99 ? '99+' : unread}
          </SidebarMenuBadge>
        )}
        {/* Masaüstü / tablet: zile bağlı popover — kenar çubuğundan sağa, üst çubuktan aşağı açılır */}
        <PopoverContent side={variant === 'icon' ? 'bottom' : 'right'} align={variant === 'icon' ? 'end' : 'start'}
          sideOffset={variant === 'icon' ? 8 : 12} collisionPadding={8} aria-labelledby={titleId} onOpenAutoFocus={focusPanel}
          className="z-(--z-menu) flex max-h-[min(70vh,40rem)] w-[min(26.25rem,calc(100vw-1rem))] flex-col overflow-hidden p-0 shadow-lg">
          {panel(false)}
        </PopoverContent>
      </Popover>
      {/* Telefon: tam yükseklikte sağ Sheet (yapışkan başlık, kayan liste, güvenli alan dolgusu) */}
      <Sheet open={open && mobile} onOpenChange={setOpen}>
        <SheetContent side="right" showCloseButton={false} onOpenAutoFocus={focusPanel} onCloseAutoFocus={focusBell}
          className="flex h-[100dvh] max-h-[100dvh] w-full flex-col gap-0 p-0 pb-[env(safe-area-inset-bottom)] sm:max-w-md">
          {panel(true)}
        </SheetContent>
      </Sheet>
    </>
  )
}
