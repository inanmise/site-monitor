import { ChevronRight, FileBadge, LayoutDashboard, Radar } from 'lucide-react'
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemMedia, ItemTitle } from '@/components/shadcn/item'
import { useT } from '../../i18n/index.jsx'

/** Sık kullanılan üç hedef — Pano, Sertifikalar, İzleme. Etiketler kenar çubuğuyla aynı anahtarlardan gelir. */
export const QUICK_LINKS = Object.freeze([
  { tab: 'dashboard', Icon: LayoutDashboard, labelKey: 'nav.dashboard', descKey: 'notFound.dest.dashboard' },
  { tab: 'all', Icon: FileBadge, labelKey: 'nav.all', descKey: 'notFound.dest.certs' },
  { tab: 'monitoring', Icon: Radar, labelKey: 'nav.monitoringOverview', descKey: 'notFound.dest.monitoring' },
])

/**
 * Hedef bağlantıları (shadcn Item, `asChild` → gerçek `<a href>`): yeni sekmede açılabilir, klavyeyle gezilir.
 * `onNavigate` verilirse (uygulama içi panel) tıklama SPA içinde sekme değiştirir; verilmezse (404 sayfası) tam
 * sayfa gezintisi — oturum yoksa giriş sayfası açılır, girişten sonra derin bağlantı uygulanır.
 */
export default function NotFoundQuickLinks({ onNavigate, headingId }) {
  const t = useT()
  return (
    <nav aria-labelledby={headingId} data-slot="nf-links" className="w-full text-left">
      <h2 id={headingId} className="mb-2 text-sm font-medium text-muted-foreground">{t('notFound.popular')}</h2>
      <ItemGroup className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {QUICK_LINKS.map(({ tab, Icon, labelKey, descKey }) => (
          <Item key={tab} asChild variant="outline" size="sm" className="min-h-14 bg-card">
            <a href={tab === 'dashboard' ? '/' : `/?tab=${tab}`} data-slot="nf-link" data-tab={tab}
              onClick={onNavigate ? (e) => { e.preventDefault(); onNavigate(tab) } : undefined}>
              <ItemMedia variant="icon"><Icon /></ItemMedia>
              <ItemContent className="min-w-0">
                <ItemTitle className="truncate">{t(labelKey)}</ItemTitle>
                <ItemDescription className="line-clamp-none">{t(descKey)}</ItemDescription>
              </ItemContent>
              <ItemActions className="sm:hidden"><ChevronRight className="size-4 text-muted-foreground" aria-hidden="true" /></ItemActions>
            </a>
          </Item>
        ))}
      </ItemGroup>
    </nav>
  )
}
