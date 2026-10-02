import { useT } from '../../i18n/index.jsx'
import { useBranding } from '../../contexts/BrandingProvider.jsx'
import BrandLogo from '../BrandLogo.jsx'
import VersionChip from '../VersionChip.jsx'
import OnlineUsersIndicator from './OnlineUsersIndicator.jsx'
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarTrigger } from '@/components/shadcn/sidebar'
import { cn } from '@/lib/utils'

/**
 * Kenar çubuğu başlığı — shadcn `team-switcher` görünümünde marka bloğu (2026-09-26).
 *
 * Logo yuvarlak köşeli karede, yanında uygulama adı ve altında küçük sürüm satırı. Bloğun tamamı Pano'ya
 * götürür (2026-09-25 kullanıcı isteği; `data-testid="nav-brand"`, ad `nav.goHome`). Sürüm çipi AYRI bir
 * düğmedir (popover): düğme içinde düğme olmasın diye marka düğmesinin ikinci satırı boş bırakılır ve çip
 * o satırın üstüne kardeş olarak konumlanır. İkon kipinde yalnız logo görünür; aç/kapa düğmesi altına iner.
 */
export default function NavBrand({ onHome, onTabChange, collapsed, isMobile, globalStatus = 'ok' }) {
  const t = useT()
  const { get: brand, branding } = useBranding()
  const appName = brand('app_name', 'SiteMonitor')
  const toggleLabel = isMobile ? t('app.close') : collapsed ? t('nav.expand') : t('nav.collapse')

  return (
    <SidebarMenu>
      <SidebarMenuItem className="flex items-center gap-1 group-data-[collapsible=icon]:flex-col">
        <SidebarMenuButton size="lg" onClick={onHome} data-testid="nav-brand"
          aria-label={t('nav.goHome', appName)} tooltip={t('nav.goHome', appName)}
          className="min-w-0 flex-1 gap-2.5">
          <span className="flex aspect-square size-8 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-background shadow-xs ring-1 ring-sidebar-border dark:bg-sidebar-accent">
            {branding.logo_data
              /* Kurum logosu (çoğu zaman geniş wordmark): kareye sığdırılır */
              ? <img src={branding.logo_data} alt="" className="size-7 object-contain" />
              /* Durum-duyarlı marka logosu — ad metni yanında kalır (renk tek sinyal değil) */
              : <BrandLogo status={globalStatus} size={22} />}
          </span>
          <span className="grid min-w-0 flex-1 text-left leading-tight">
            <span className="truncate text-sm font-semibold text-sidebar-accent-foreground">{appName}</span>
            {/* Sürüm çipinin yeri (çip kardeş düğme; aşağıda) */}
            <span aria-hidden="true" className="h-4" />
          </span>
        </SidebarMenuButton>
        {/* Telefonda çipin dokunma alanı görünmez bir ::after ile 40 px'e büyür (shadcn SidebarMenuAction kalıbı) */}
        <div className={cn('absolute bottom-[5px] left-[3.125rem] group-data-[collapsible=icon]:hidden',
          isMobile && '[&_button]:relative [&_button]:after:absolute [&_button]:after:-inset-3')}>
          <VersionChip onTabChange={onTabChange} />
        </div>
        {/* Çevrimiçi kullanıcılar (2026-10-02): logonun sağında; ikon kipinde logonun altında, sayı rozetle */}
        <OnlineUsersIndicator collapsed={collapsed} isMobile={isMobile} />
        <SidebarTrigger className={cn('shrink-0 text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground', isMobile ? 'size-10' : 'size-8')}
          title={toggleLabel} aria-label={toggleLabel} />
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
