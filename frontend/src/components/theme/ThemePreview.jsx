import { useT } from '../../i18n/index.jsx'
import { schemeOf } from '../../theme/themes.js'
import { cn } from '@/lib/utils'

/**
 * Canlı mini önizleme (Ayarlar → Görünüm → Temalar) — kendi `data-theme` / `data-scheme` kabında, YALNIZ jeton
 * sınıflarıyla çizilir (bg-background, bg-card, bg-primary, text-success …), yani renkler o temanın gerçek CSS
 * değerleridir: üst çubuk (kenar çubuğu yüzeyi), kart, birincil düğme, durum rozetleri ve grafik şeridi.
 * `dark:` yardımcısı KULLANILMAZ — şema varyantı sayfanın şemasına bakar, iç içe önizlemede yanılırdı.
 * Süs: ekran okuyucudan gizli (kartın adı/açıklaması metin olarak yanında).
 */
export default function ThemePreview({ id, className }) {
  const t = useT()
  return (
    <div aria-hidden="true" data-slot="theme-preview" data-theme={id} data-scheme={schemeOf(id)}
      className={cn('flex h-32 min-w-0 flex-col overflow-hidden rounded-lg border border-border bg-background text-foreground', className)}>
      <div className="flex h-6 shrink-0 items-center gap-1.5 border-b border-sidebar-border bg-sidebar px-2">
        <span className="size-2 rounded-full bg-primary" />
        <span className="h-1.5 w-10 rounded-full bg-sidebar-foreground/40" />
        <span className="h-1.5 w-6 rounded-full bg-sidebar-foreground/25" />
        <span className="ml-auto h-1.5 w-5 rounded-full bg-muted-foreground/40" />
      </div>
      <div className="flex min-h-0 flex-1 gap-2 p-2">
        <div className="flex min-w-0 flex-1 flex-col gap-1 rounded-md border border-border bg-card p-1.5 text-card-foreground">
          <span className="truncate text-[11px] leading-tight font-semibold">{t('themes.sample.title')}</span>
          <span className="truncate text-[10px] leading-tight text-muted-foreground">{t('themes.sample.sub')}</span>
          <span className="flex min-w-0 flex-wrap gap-1 overflow-hidden">
            <span className="rounded-full bg-success/15 px-1.5 text-[9px] leading-4 font-semibold text-success">{t('themes.sample.ok')}</span>
            <span className="rounded-full bg-warning/15 px-1.5 text-[9px] leading-4 font-semibold text-warning">{t('themes.sample.warn')}</span>
            <span className="rounded-full bg-destructive/15 px-1.5 text-[9px] leading-4 font-semibold text-destructive">{t('themes.sample.crit')}</span>
          </span>
          <span className="mt-auto flex min-w-0 items-center gap-1">
            <span className="truncate rounded bg-primary px-1.5 text-[10px] leading-5 font-medium text-primary-foreground">{t('themes.sample.action')}</span>
            <span className="truncate rounded border border-border px-1.5 text-[10px] leading-5">{t('themes.sample.secondary')}</span>
          </span>
        </div>
        <div className="flex w-9 shrink-0 items-end gap-0.5 pb-0.5">
          <span className="h-[45%] flex-1 rounded-sm bg-chart-1" />
          <span className="h-[70%] flex-1 rounded-sm bg-chart-2" />
          <span className="h-[35%] flex-1 rounded-sm bg-chart-3" />
          <span className="h-[55%] flex-1 rounded-sm bg-chart-4" />
          <span className="h-[85%] flex-1 rounded-sm bg-chart-5" />
        </div>
      </div>
    </div>
  )
}
