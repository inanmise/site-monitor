import { useId } from 'react'
import { BellRing, Building2, CircleCheck, Gauge, ListChecks, Radar, Users, UsersRound } from 'lucide-react'
import { useDateLocale, useT } from '../../i18n/index.jsx'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import HintPopover from '../ui/HintPopover.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { cn } from '@/lib/utils'
import { buildTiles, hasUsage } from './usageStatsModel.js'

/** Kutucuk ikonu + ton (lucide; giriş sayfası vitrin çipleriyle aynı aile). */
const ICONS = {
  healthy: { Icon: CircleCheck, panel: 'text-emerald-400', surface: 'text-emerald-600 dark:text-emerald-400' },
  checks: { Icon: ListChecks, panel: 'text-sky-400', surface: 'text-sky-600 dark:text-sky-400' },
  alerts: { Icon: BellRing, panel: 'text-amber-400', surface: 'text-amber-600 dark:text-amber-400' },
  teams: { Icon: Building2, panel: 'text-violet-400', surface: 'text-violet-600 dark:text-violet-400' },
  users: { Icon: Users, panel: 'text-sky-300', surface: 'text-sky-700 dark:text-sky-300' },
  // Çevrimiçi göstergesiyle (kenar çubuğu, OnlineUsersIndicator) aynı ikon — aynı kavram.
  online: { Icon: UsersRound, panel: 'text-emerald-400', surface: 'text-emerald-600 dark:text-emerald-400' },
  availability: { Icon: Gauge, panel: 'text-indigo-300', surface: 'text-indigo-600 dark:text-indigo-400' },
  monitored: { Icon: Radar, panel: 'text-sky-400', surface: 'text-sky-600 dark:text-sky-400' },
}

/**
 * Ton: `panel` = giriş sayfasının HER temada koyu tanıtım paneli (--lp-* jetonları); `surface` = tema jetonlu zemin
 * (telefon/tablette formun altı — açık ve koyu temada).
 */
const TONE = {
  panel: {
    card: 'border-white/10 bg-white/[0.04] text-white',
    hover: 'hover:border-white/20 hover:bg-white/[0.07]',
    label: 'text-(--lp-fg-muted) hover:text-white',
    sub: 'text-(--lp-fg-muted)',
    title: 'text-(--lp-fg-strong)',
    hint: 'text-(--lp-fg-faint)',
    skeleton: 'bg-white/10',
    ring: 'focus-visible:after:ring-white/40',
    track: 'bg-white/10',
  },
  surface: {
    card: 'bg-card',
    hover: 'hover:bg-accent/50',
    label: 'text-muted-foreground hover:text-foreground',
    sub: 'text-muted-foreground',
    title: 'text-foreground',
    hint: 'text-muted-foreground',
    skeleton: '',
    ring: 'focus-visible:after:ring-ring/50',
    track: '',
  },
}

/**
 * Kart kabuğu — gerçek kutucuk ve iskelet AYNI ölçüde (yükleme bitince yerleşim zıplamasın): etiket (16) + değer (32) +
 * alt satır (16) + aralıklar + dolgu = 96 px taban; içerik üstten hizalı (aynı satırdaki kutucuklarda etiket ve değer
 * aynı hizada).
 */
const TILE = 'relative min-h-24 min-w-0 justify-start gap-1 rounded-xl px-3 py-3 shadow-none transition-colors'
/** Öndeki "sağlıklı izleme" kutucuğu tam satır: telefonda 2, tablet/geniş ekranda 3 sütunu kaplar (ızgara boşluksuz dolar). */
const HERO = 'col-span-2 sm:col-span-3'

/**
 * Giriş sayfası "Kullanım istatistikleri" şeridi (2026-10-04, kullanıcı isteği) — shadcn Card kutucukları, lucide ikonlar,
 * tabular rakamlar, yerel binlik ayırıcı. Her kutucuk bir tanım listesi öğesi (`dt` etiket + `dd` değer); etiket
 * `ui/HintPopover` tetiğidir ve tetik `::after` örtüsüyle TÜM kartı kaplar ("stretched button") — kısa açıklama fare,
 * klavye ve dokunmatikte açılır, dokunma hedefi kartın kendisi (≥ 40 px).
 *
 * <p>Kipler: `loading` → aynı ölçüde iskelet kutucuklar (+ ekran okuyucuya durum metni); kullanım alanları yanıtta
 * varsa yedi kutucuk (önde tam satır "sağlıklı izleme" + sağlıklı oranı çubuğu, ardından izleme ve kullanıcı üçlüleri —
 * telefonda 2, tablet/geniş ekranda 3 sütun; ızgara boşluksuz dolar); yoksa (Marka ayarı kapalı / eski sunucu) eski iki
 * rakam — `usageOnly` yerleşiminde HİÇ çizilmez (telefonda eski davranış: rakam gösterilmez). Değer yoksa "—". Yanıt
 * hiç gelmediyse (`stats` null) şerit "—" ile.
 *
 * @param stats    `/api/public-stats` verisi ya da null
 * @param loading  ilk yanıt bekleniyor
 * @param tone     `panel` | `surface`
 * @param placement test/e2e kancası (`data-placement`)
 * @param usageOnly eski (iki rakam) kipte hiç çizme
 */
export default function UsageStats({ stats, loading = false, tone = 'surface', placement, usageOnly = false, className }) {
  const t = useT()
  const locale = useDateLocale()
  const titleId = useId()
  const tn = TONE[tone] ?? TONE.surface
  // Yanıt yoksa (hata) kullanım kipi varsayılır — sunucu ayarı bilinmiyor; şerit "—" ile çizilir.
  const usage = loading || !stats || hasUsage(stats)
  if (!usage && usageOnly) return null

  const tiles = buildTiles(stats, { t, locale, usage })
  const grid = usage ? 'grid-cols-2 sm:grid-cols-3' : 'max-w-md grid-cols-2'

  return (
    <section aria-labelledby={usage ? titleId : undefined} aria-label={usage ? undefined : t('login.usage.title')}
      data-slot={usage ? 'login-usage' : 'login-hero-stats'} data-placement={placement}
      data-state={loading ? 'loading' : 'ready'} aria-busy={loading || undefined} className={cn('w-full min-w-0', className)}>
      {usage && (
        <div className="mb-2 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
          <h2 id={titleId} className={cn('text-sm font-semibold tracking-tight', tn.title)}>{t('login.usage.title')}</h2>
          <p className={cn('text-xs', tn.hint)}>{t('login.usage.live')}</p>
        </div>
      )}
      {loading && <span role="status" className="sr-only">{t('login.usage.loading')}</span>}
      <dl className={cn('grid gap-2', grid)}>
        {tiles.map((tile) => (loading
          ? <SkeletonTile key={tile.key} tone={tone} hero={tile.hero} />
          : <Tile key={tile.key} tile={tile} tone={tone} noData={t('login.usage.noData')} />))}
      </dl>
    </section>
  )
}

function Tile({ tile, tone, noData }) {
  const tn = TONE[tone] ?? TONE.surface
  const meta = ICONS[tile.key] ?? ICONS.monitored
  const { Icon } = meta
  return (
    <Card data-slot="usage-tile" data-stat={tile.key} data-empty={tile.value == null ? 'true' : undefined}
      data-hero={tile.hero ? 'true' : undefined} className={cn(TILE, tile.hero && HERO, tn.card, tn.hover)}>
      <dt className="min-w-0">
        {/* shadcn Button (HintPopover tetiği): `has-[>svg]:px-*` boyut sınıfı p-0'ı ezmesin diye ayrıca sıfırlanır */}
        <HintPopover content={tile.tip} side="top" align="start"
          triggerClassName={cn(
            'static max-w-full justify-start gap-1.5 rounded-md text-left text-xs leading-4 font-medium whitespace-normal',
            'has-[>svg]:px-0 pointer-coarse:min-h-0 focus-visible:ring-0',
            'after:absolute after:inset-0 after:z-0 after:rounded-xl focus-visible:after:ring-[3px]',
            tn.label, tn.ring)}>
          <Icon aria-hidden="true" className={cn('size-3.5 shrink-0', meta[tone] ?? meta.surface)} />
          <span className="min-w-0">{tile.label}</span>
        </HintPopover>
      </dt>
      <dd className="flex min-w-0 flex-col gap-1">
        <span className="flex min-w-0 flex-wrap items-baseline gap-x-1.5">
          {tile.value != null ? (
            <span data-slot="usage-value" className="text-2xl leading-8 font-semibold tracking-tight tabular-nums">{tile.value}</span>
          ) : (
            <span data-slot="usage-value" className="text-2xl leading-8 font-semibold">
              <span aria-hidden="true">—</span><span className="sr-only">{noData}</span>
            </span>
          )}
          {tile.unit && <span data-slot="usage-unit" className={cn('text-xs leading-4 tabular-nums', tn.sub)}>{tile.unit}</span>}
        </span>
        {tile.caption && <span data-slot="usage-caption" className={cn('text-xs leading-4 tabular-nums', tn.sub)}>{tile.caption}</span>}
        {/* Sağlıklı oranı çubuğu (yalnız hero) — oran hemen üstte METİN olarak yazılı; çubuk ekran okuyucuya ikinci
            kez duyurulmaz (decorative). Proje sarmalayıcısı ui/Progress (shadcn Progress). */}
        {tile.hero && tile.ratio != null && (
          <div className="mt-1" data-slot="usage-ratio">
            <ProgressBar value={tile.ratio} max={100} size="sm" tone="ok" decorative className={tn.track} />
          </div>
        )}
      </dd>
    </Card>
  )
}

function SkeletonTile({ tone, hero = false }) {
  const tn = TONE[tone] ?? TONE.surface
  return (
    <Card data-slot="usage-tile-skeleton" aria-hidden="true" className={cn(TILE, hero && HERO, tn.card)}>
      <dt><Skeleton className={cn('h-4 w-24 max-w-full', tn.skeleton)} /></dt>
      <dd className="flex flex-col gap-1">
        <Skeleton className={cn('h-8 w-16', tn.skeleton)} />
        <Skeleton className={cn('h-4 w-20 max-w-full', tn.skeleton)} />
        {hero && <Skeleton className={cn('mt-1 h-1 w-full', tn.skeleton)} />}
      </dd>
    </Card>
  )
}
