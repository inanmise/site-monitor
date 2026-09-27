import { useEffect, useState } from 'react'
import { Info, AlertTriangle, AlertOctagon, X, ArrowUpRight } from 'lucide-react'
import { useBranding } from '../contexts/BrandingProvider.jsx'
import { useT } from '../i18n/index.jsx'
import { safeHref } from '../utils/safeHref.js'
import { Alert } from '@/components/shadcn/alert'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

const DISMISS_KEY = 'sm.banner.dismissedVersion'
/** Hero (giriş sonrası öne çıkan kart) oturumda BİR kez gösterilir. */
const HERO_KEY = 'sm.banner.heroShown'
const HERO_MS = 1500

/**
 * Ton → ikon + shadcn Alert varyantı + ikon rozetinin zemini + etiket anahtarı. Ton ZEMİN, KENAR ve İKON
 * rozetiyle taşınır — sol renk şeridi YOK (kullanıcı kuralı 2026-09-26; eski .ann-bar 4px sol vurgu taşıyordu).
 */
const TONES = {
  INFO:     { Icon: Info,          variant: 'info',    badge: 'bg-blue-600 dark:bg-blue-500',   labelKey: 'branding.toneInfo' },
  WARNING:  { Icon: AlertTriangle, variant: 'warning', badge: 'bg-amber-600 dark:bg-amber-500', labelKey: 'branding.toneWarning' },
  CRITICAL: { Icon: AlertOctagon,  variant: 'danger',  badge: 'bg-red-600 dark:bg-red-500',     labelKey: 'branding.toneCritical' },
}

function readDismissed() {
  try { return localStorage.getItem(DISMISS_KEY) } catch { return null }
}
function writeDismissed(version) {
  try { localStorage.setItem(DISMISS_KEY, String(version)) } catch { /* in-memory state yeterli */ }
}

/**
 * Kurumsal duyuru şeridi — içerik kolonunun üstünde yapışkan (sol menünün SAĞINDA kalır).
 * Kullanıcı X ile kapatabilir; kapatılan VERSİYON saklanır: admin metni güncelleyince
 * banner-version arttığından şerit herkese yeniden görünür.
 *
 * {@code heroOnMount} verildiğinde (giriş anı) duyuru önce ortada bir kart olarak
 * {@link HERO_MS} kadar durur, sonra üst şeride toplanır — oturumda yalnız bir kez.
 */
export default function AnnouncementBanner({ heroOnMount = false }) {
  const { branding } = useBranding()
  const t = useT()
  const [dismissed, setDismissed] = useState(readDismissed)
  const [hero, setHero] = useState(false)

  const enabled = branding.banner_enabled === true || branding.banner_enabled === 'true'
  const text = branding.banner_text || ''
  const version = String(branding.banner_version ?? 0)
  const visible = enabled && !!text.trim() && dismissed !== version

  useEffect(() => {
    if (!heroOnMount || !visible) return
    let shown = null
    try { shown = sessionStorage.getItem(HERO_KEY) } catch { /* yoksay */ }
    if (shown === version) return
    try { sessionStorage.setItem(HERO_KEY, version) } catch { /* yoksay */ }
    setHero(true)
  }, [heroOnMount, visible, version])

  // Kapanma zamanlayıcısı AYRI efektte (2026-09-26): gösterim kararıyla aynı efektteyken bağımlılık değişimi ya da
  // StrictMode'un çift çağrısı zamanlayıcıyı temizliyor, ikinci çalışma ise "zaten gösterildi" diye erken dönüyordu —
  // hero kart ekranda ASILI kalıyordu. Burada zamanlayıcı yalnız `hero` açıldığında kurulur.
  useEffect(() => {
    if (!hero) return undefined
    const id = setTimeout(() => setHero(false), HERO_MS)
    return () => clearTimeout(id)
  }, [hero])

  if (!visible) return null

  const tone = TONES[branding.banner_tone] || TONES.INFO
  const { Icon } = tone
  const rawLink = branding.banner_link || ''
  // Şema beyaz listesi (2026-09-27 regresyon BD1 kardeşi): yalnız http/https/mailto (ve göreli) adres bağlantı olur;
  // `javascript:` / `data:` gibi bir değer href'e yazılmaz, etiketi düz metin kalır.
  const link = safeHref(rawLink)
  const linkLabel = branding.banner_link_label || rawLink
  const close = () => { writeDismissed(version); setDismissed(version); setHero(false) }

  const body = (big) => (
    <>
      <span aria-hidden="true"
        className={cn('inline-flex shrink-0 items-center justify-center text-white', tone.badge,
          big ? 'size-9 rounded-[10px]' : 'size-7 rounded-lg')}>
        <Icon className="size-[15px]" />
      </span>
      <span className="flex min-w-0 flex-1 flex-wrap items-baseline gap-x-2.5 gap-y-0.5">
        <span className={cn('font-extrabold tracking-[.09em] uppercase opacity-80', big ? 'text-[11px]' : 'text-[10.5px]')}>{t(tone.labelKey)}</span>
        <span className="font-medium [overflow-wrap:anywhere]">{text}</span>
      </span>
      {link && (
        <Button asChild variant="outline" size="sm"
          className="max-w-[45%] min-w-0 shrink border-current bg-transparent font-bold text-current shadow-none hover:bg-current/10 hover:text-current dark:border-current dark:bg-transparent">
          <a href={link} target="_blank" rel="noopener noreferrer">
            <span className="truncate">{linkLabel}</span><ArrowUpRight aria-hidden="true" className="size-[13px]" />
          </a>
        </Button>
      )}
      {rawLink && !link && (
        <span data-slot="announcement-link-inert" className="max-w-[45%] min-w-0 shrink truncate font-bold">{linkLabel}</span>
      )}
      <Button type="button" variant="ghost" size="icon-sm" aria-label={t('app.close')} onClick={close}
        className="shrink-0 text-current opacity-65 hover:bg-current/10 hover:text-current hover:opacity-100">
        <X aria-hidden="true" />
      </Button>
    </>
  )

  return (
    <>
      {/* Tam genişlikte YAPIŞKAN şerit (içerik kolonunun üstü): shadcn Alert tonlu zemin + alt kenar. Kart değil —
          köşe yuvarlatma ve yan kenarlar yok; ton rozet ikonu + zemin + kenarla okunur. Katman z-40: içerikteki
          yapışkan başlıkların (≤ z-6) üstünde, telefondaki kenar çubuğu Sheet'inin (z-50) ALTINDA — eski z-150
          açılan mobil menünün üstüne biniyordu. */}
      <Alert variant={tone.variant} role="status" data-slot="announcement-bar" data-tone={tone.variant}
        className={cn('sticky top-0 z-40 flex items-center gap-2.5 rounded-none border-x-0 border-t-0 px-3.5 py-2 text-[13px] leading-[1.45] sm:gap-3 sm:px-6 sm:py-2.5 sm:text-[13.5px]',
          hero && 'invisible')}>
        {body(false)}
      </Alert>
      {hero && (
        <div data-slot="announcement-hero-overlay" onClick={() => setHero(false)}
          className="fixed inset-0 z-[var(--z-announce)] flex items-center justify-center bg-slate-900/50 p-4 backdrop-blur-sm animate-in fade-in-0 motion-reduce:animate-none sm:p-6">
          <Alert variant={tone.variant} role={undefined} data-slot="announcement-hero" onClick={e => e.stopPropagation()}
            className="flex max-w-[640px] items-center gap-3.5 rounded-2xl px-5 py-4 text-[15px] leading-normal shadow-2xl animate-in zoom-in-95 fade-in-0 motion-reduce:animate-none sm:px-[22px] sm:py-5">
            {body(true)}
          </Alert>
        </div>
      )}
    </>
  )
}
