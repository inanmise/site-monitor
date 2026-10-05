import { Eye, Check, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import ThemeSwatch from './ThemeSwatch.jsx'
import { themeName } from './themeLabels.js'
import { Button } from '@/components/shadcn/button'

/**
 * Tema önizleme şeridi (2026-10-05) — Ayarlar → Görünüm → Temalar'da "Önizle" denince tema YALNIZ bu sekmede ve
 * saklanmadan uygulanır; şerit hangi sayfada olunursa olsun görünür kalır (yapışkan; telefonda üst çubuğun altında) ve
 * önizlemeyi bitirmenin tek, açık yolunu verir. Tema listede açıksa "Bu temayı kullan" kişinin seçimi yapar.
 */
export default function ThemePreviewBar() {
  const t = useT()
  const { preview, themes, endPreview, setTheme } = useTheme()
  if (!preview) return null
  const name = themeName(t, preview)
  const enabled = themes.some((th) => th.id === preview)
  return (
    <div role="status" data-slot="theme-preview-bar" data-theme-id={preview}
      className="sticky top-14 z-20 flex flex-wrap items-center gap-2 border-b border-primary/40 bg-card/95 px-3 py-2 text-sm text-card-foreground shadow-sm backdrop-blur md:top-0 sm:px-6 print:hidden">
      <Eye aria-hidden="true" className="size-4 shrink-0 text-primary" />
      <ThemeSwatch id={preview} className="size-5" />
      <span className="min-w-0 flex-1 basis-48">{t('theme.preview.bar', name)}</span>
      <div className="flex flex-wrap items-center gap-2">
        {enabled && (
          <Button type="button" size="sm" variant="outline" className="max-md:h-10 pointer-coarse:h-10" onClick={() => setTheme(preview)}>
            <Check aria-hidden="true" /> {t('theme.preview.use')}
          </Button>
        )}
        <Button type="button" size="sm" data-slot="theme-preview-end" className="max-md:h-10 pointer-coarse:h-10" onClick={endPreview}>
          <X aria-hidden="true" /> {t('theme.preview.end')}
        </Button>
      </div>
    </div>
  )
}
