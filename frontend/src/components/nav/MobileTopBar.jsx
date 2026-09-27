import { useT } from '../../i18n/index.jsx'
import { useBranding } from '../../contexts/BrandingProvider.jsx'
import BrandLogo from '../BrandLogo.jsx'
import InboxBell from '../InboxBell.jsx'
import { SidebarTrigger, useSidebar } from '@/components/shadcn/sidebar'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { Search } from 'lucide-react'

/**
 * Mobil üst çubuk (<768 px, 2026-09-26): menü düğmesi + marka (Pano'ya götürür) + bildirimler. Kenar çubuğuyla
 * aynı yüzeyde (`bg-sidebar`), böylece çekmece açıldığında panel ve çubuk tek parça okunur.
 *
 * Kenar çubuğu telefonda Sheet'tir ve kapalıyken içeriği DOM'da değildir; bildirim zili bu yüzden telefonda
 * burada, masaüstünde kenar çubuğunda yaşar — `isMobile` ile TEK örnek (iki örnek iki ayrı okundu durumu tutardı).
 * Dokunma hedefleri 40 px.
 */
export default function MobileTopBar({ onTabChange, username }) {
  const t = useT()
  const { isMobile, openMobile } = useSidebar()
  const { get: brand, branding } = useBranding()
  const appName = brand('app_name', 'SiteMonitor')

  return (
    <header data-slot="mobile-top-bar"
      className="sticky top-0 z-30 flex h-14 shrink-0 items-center gap-1 border-b border-sidebar-border bg-sidebar/95 px-2 text-sidebar-foreground backdrop-blur-sm md:hidden print:hidden">
      <SidebarTrigger className="size-10 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground [&_svg]:size-5"
        aria-label={t('nav.openMenu')} title={t('nav.openMenu')} aria-expanded={openMobile} />
      <Separator orientation="vertical" className="mx-0.5 bg-sidebar-border data-[orientation=vertical]:h-5" />
      <Button type="button" variant="ghost" onClick={() => onTabChange('dashboard')} aria-label={t('nav.goHome', appName)}
        className="h-10 min-w-0 gap-2 px-2 text-base font-semibold text-sidebar-accent-foreground hover:bg-sidebar-accent">
        {branding.logo_data
          ? <img src={branding.logo_data} alt="" className="h-6 max-w-24 object-contain" />
          : <BrandLogo size={24} />}
        <span className="truncate">{appName}</span>
      </Button>
      {/* Komut paleti (2026-09-26): telefonda arama kutusu kenar çubuğu çekmecesinde kalıyordu → zilin yanında ikon düğme (Ctrl+K eşdeğeri). */}
      <Button type="button" variant="ghost" size="icon" onClick={() => window.dispatchEvent(new CustomEvent('sm:palette'))}
        aria-label={t('palette.title')} title={t('palette.title')}
        className="ml-auto size-10 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground [&_svg]:size-5">
        <Search aria-hidden="true" />
      </Button>
      {isMobile && (
        <span data-tour="nav-inbox" className="inline-flex">
          <InboxBell username={username} variant="icon" />
        </span>
      )}
    </header>
  )
}
