import { useId } from 'react'
import { LayoutDashboard, LifeBuoy } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Badge } from '@/components/shadcn/badge'
import { useT } from '../../i18n/index.jsx'
import NotFoundArt from './NotFoundArt.jsx'
import NotFoundQuickLinks from './NotFoundQuickLinks.jsx'

/**
 * Uygulama içi "Sayfa bulunamadı" paneli (2026-10-08). Oturum açıkken adresteki `?tab=` tanınmazsa (eski e-posta
 * bağlantısı, elle yazılmış adres) içerik alanında görünür — eskiden sessizce Pano açılıyordu ve kullanıcı neden
 * başka bir sayfada olduğunu anlamıyordu. `restricted`: sekme var ama hesabın rolüne kapalı (eskiden boş sayfa).
 * Kenar çubuğu ve üst bar yerinde kalır; "Panoya git" SPA içinde gezinir (geçmişe kayıt bırakır).
 */
export default function NotFoundPanel({ kind = 'notFound', requested = '', onNavigate }) {
  const t = useT()
  const headingId = useId()
  const linksId = useId()
  const restricted = kind === 'restricted'
  const go = (tab) => onNavigate?.(tab)
  return (
    <section data-slot="not-found-panel" data-kind={kind} aria-labelledby={headingId}
      className="mx-auto w-full max-w-3xl py-4 sm:py-8">
      <Card className="items-center gap-5 px-4 py-8 text-center sm:px-10 sm:py-10">
        <NotFoundArt variant={restricted ? 'restricted' : 'notFound'} compact />
        <div className="flex max-w-xl flex-col items-center gap-2">
          {!restricted && <Badge variant="outline" className="font-mono">{t('notFound.badge')}</Badge>}
          <h2 id={headingId} data-slot="not-found-title" className="text-xl font-semibold tracking-tight text-balance sm:text-2xl">
            {restricted ? t('notFound.restricted.title') : t('notFound.title')}
          </h2>
          <p className="text-sm text-pretty text-muted-foreground sm:text-base">
            {restricted ? t('notFound.restricted.body') : t('notFound.inApp.body')}
          </p>
          {requested && (
            <p className="flex max-w-full min-w-0 flex-wrap items-center justify-center gap-1.5 text-xs text-muted-foreground">
              <span>{t('notFound.requested')}</span>
              <code data-slot="nf-requested" title={`?tab=${requested}`}
                className="max-w-full truncate rounded-md border bg-muted px-1.5 py-0.5 font-mono text-foreground">
                ?tab={requested}
              </code>
            </p>
          )}
        </div>
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:justify-center">
          <Button size="lg" onClick={() => go('dashboard')} data-slot="nf-dashboard">
            <LayoutDashboard aria-hidden="true" />{t('notFound.inApp.dashboard')}
          </Button>
          <Button size="lg" variant="outline" onClick={() => go('help')} data-slot="nf-help">
            <LifeBuoy aria-hidden="true" />{t('notFound.help')}
          </Button>
        </div>
        <NotFoundQuickLinks onNavigate={go} headingId={linksId} />
      </Card>
    </section>
  )
}
