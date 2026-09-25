import { SidebarProvider } from '@/components/shadcn/sidebar'

/**
 * shadcn Sidebar bağlamı: Nav ve InboxBell (SidebarMenuButton) `useSidebar()` kullanır ve sağlayıcı
 * DIŞINDA çizilemez. Uygulamada App.jsx `SidebarProvider` ile sarar; bileşeni tek başına sınayan
 * testler de aynısını yapar. `open` varsayılan açık (geniş kenar çubuğu).
 */
export function withSidebar(ui, { open = true } = {}) {
  return <SidebarProvider open={open} onOpenChange={() => {}}>{ui}</SidebarProvider>
}
