import { useId, useMemo } from 'react'
import { ArrowLeft, Globe, Home, LifeBuoy } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Separator } from '@/components/shadcn/separator'
import { useT, useLanguage } from '../i18n/index.jsx'
import { useBranding, useAppVersion } from '../contexts/BrandingProvider.jsx'
import BrandLogo from '../components/BrandLogo.jsx'
import NotFoundArt from '../components/notfound/NotFoundArt.jsx'
import NotFoundQuickLinks from '../components/notfound/NotFoundQuickLinks.jsx'
import { Spinner } from '../components/ui/Progress.jsx'
import { usePageMeta } from '../hooks/usePageMeta.js'
import { pageMetaKey } from '../utils/pageMeta.js'

/** Gösterilen adres üst sınırı (tam hâli `title`'da). */
const MAX_SHOWN = 120

/** İstenen adres — okunabilir (yüzde kodu çözülmüş) ama ham metin; React metin düğümü olarak basılır (HTML değil). */
export function requestedPath(loc = globalThis.location) {
  const raw = `${loc?.pathname ?? '/'}${loc?.search ?? ''}`
  let shown = raw
  try { shown = decodeURI(raw) } catch { /* bozuk yüzde kodu: ham hâli göster */ }
  return { full: shown, shown: shown.length > MAX_SHOWN ? `${shown.slice(0, MAX_SHOWN)}…` : shown }
}

/**
 * Markalı 404 sayfası (2026-10-08, kullanıcı isteği: "tamamen SiteMonitor'e özel, shadcn, mobil uyumlu").
 *
 * `main.jsx` bilinmeyen bir yolda (`/foo`, `/x/y`) App yerine bunu çizer — oturum açılışı (`/api/me`) BEKLENMEZ, sayfa
 * herkese açıktır; sunucu aynı yanıtı HTTP 404 ile verir (SpaNotFoundAdvice). Tema, dil ve beyaz-etiket marka adı
 * uygulamanın geri kalanıyla aynı sağlayıcılardan gelir.
 *
 * Eylemler: "Ana sayfaya dön" (/), "Geri" (yalnız sekmenin geçmişi varsa), "Yardım" (/?tab=help — oturum yoksa giriş
 * sayfası açılır, girişten sonra Yardım'a gidilir) ve sık kullanılan üç hedef.
 */
export default function NotFoundPage() {
  const t = useT()
  const { lang, toggle, pending } = useLanguage()
  const { get } = useBranding()
  const version = useAppVersion()
  const appName = get('app_name', 'SiteMonitor')
  const headingId = useId()
  const linksId = useId()
  usePageMeta(pageMetaKey('notFound'))
  const path = useMemo(() => requestedPath(), [])
  const canGoBack = typeof window !== 'undefined' && window.history.length > 1

  return (
    <div data-slot="not-found-page" className="relative isolate flex min-h-dvh flex-col overflow-x-hidden bg-background text-foreground">
      {/* Marka ışıması: mor (gövde) üstte, yeşil (yaprak) altta — düşük opaklık, açık ve koyu temada yalnız dekor */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 bg-[radial-gradient(70%_45%_at_50%_0%,rgb(129_51_135/0.14),transparent_70%),radial-gradient(60%_40%_at_50%_100%,rgb(100_166_79/0.12),transparent_70%)]" />

      <header className="flex items-center justify-between gap-3 px-4 py-3 sm:px-8 sm:py-5">
        <a href="/" aria-label={t('notFound.brandHome', appName)} data-slot="nf-brand"
          className="inline-flex min-h-10 min-w-0 items-center gap-2 rounded-md font-semibold tracking-tight outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50">
          <BrandLogo status="ok" size={32} />
          <span className="truncate">{appName}</span>
        </a>
        <Button type="button" variant="ghost" size="lg" onClick={toggle} disabled={!!pending}
          aria-busy={pending ? true : undefined} data-slot="nf-lang">
          {pending ? <Spinner size={16} inline label={t('nav.langLoading')} /> : <Globe aria-hidden="true" />}
          {lang === 'tr' ? 'English' : 'Türkçe'}
        </Button>
      </header>

      <main aria-labelledby={headingId} className="flex flex-1 items-start justify-center px-4 pt-2 pb-10 sm:items-center sm:px-8">
        <div className="flex w-full max-w-3xl flex-col items-center gap-6 text-center">
          <NotFoundArt />
          <div className="flex flex-col items-center gap-3">
            <Badge variant="outline" className="font-mono">{t('notFound.badge')}</Badge>
            <h1 id={headingId} className="text-2xl font-semibold tracking-tight text-balance sm:text-4xl">{t('notFound.title')}</h1>
            <p className="max-w-prose text-base text-pretty text-muted-foreground sm:text-lg">{t('notFound.body')}</p>
            <p className="flex w-full max-w-full min-w-0 flex-col items-center gap-1 text-xs text-muted-foreground sm:flex-row sm:justify-center sm:gap-2">
              <span>{t('notFound.requested')}</span>
              <code data-slot="nf-requested" title={path.full}
                className="block max-w-full truncate rounded-md border bg-muted px-2 py-1 font-mono text-[13px] text-foreground">
                {path.shown}
              </code>
            </p>
          </div>

          <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:justify-center">
            <Button asChild size="lg">
              <a href="/" data-slot="nf-home"><Home aria-hidden="true" />{t('notFound.home')}</a>
            </Button>
            {canGoBack && (
              <Button type="button" size="lg" variant="outline" onClick={() => window.history.back()} data-slot="nf-back">
                <ArrowLeft aria-hidden="true" />{t('notFound.back')}
              </Button>
            )}
            <Button asChild size="lg" variant="outline">
              <a href="/?tab=help" data-slot="nf-help"><LifeBuoy aria-hidden="true" />{t('notFound.help')}</a>
            </Button>
          </div>

          <Separator className="max-w-md" />
          <NotFoundQuickLinks headingId={linksId} />
        </div>
      </main>

      <footer className="px-4 pb-6 text-center text-xs text-pretty text-muted-foreground sm:px-8">
        <p>{t('notFound.footer')}</p>
        <p className="mt-1 tabular-nums">{appName} v{version}</p>
      </footer>
    </div>
  )
}
