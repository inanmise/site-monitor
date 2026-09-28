import { AlertOctagon, WifiOff } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { LEVEL_TEXT, bucketOf, levelOfCert } from './statsModel.js'

/**
 * İstatistikler ekranının ortak küçük parçaları (2026-09-28): seviye / kalan gün / katman rozetleri ve ton sözlükleri.
 * Durum HER ZAMAN metin + renk (renk tek başına anlam taşımaz; dolmuş ve hata ayrıca ikonlu). Kartlarda sol şerit YOK.
 *
 * Test kancaları: `data-slot="stats-level"` + `data-status`, `data-slot="stats-days"` + `data-bucket`,
 * `data-slot="stats-tier"`.
 */

/** Seviye rozeti tonu (Tüm Sertifikalar / Vade Takvimi ile aynı aile). */
export const LEVEL_TONE = {
  valid: 'bg-success/15 text-success dark:bg-success/20',
  warning: 'bg-amber-500/15 text-amber-800 dark:text-amber-300',
  high: 'bg-orange-500/15 text-orange-700 dark:text-orange-300',
  critical: 'bg-red-500/15 text-red-700 dark:text-red-300',
  expired: 'bg-destructive text-white dark:bg-destructive/80',
  error: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
}

/** Seviye mürekkebi (matris başlığı noktası). */
export const LEVEL_DOT = {
  valid: 'bg-success', warning: 'bg-amber-500', high: 'bg-orange-500', critical: 'bg-red-600',
  expired: 'bg-rose-900 dark:bg-rose-500', error: 'bg-zinc-400',
}

/**
 * Matris ısı tonu — seviye × adım (1–3; sütundaki en büyük değere oran). Yalnız zemin (kenarlık yok → Chromium'un
 * yarı saydam köşe bozulması tetiklenmez), metin her adımda okunur kalır.
 */
export const HEAT = {
  valid: ['', 'bg-success/10 text-success', 'bg-success/20 text-success', 'bg-success/30 text-green-800 dark:text-green-200'],
  warning: ['', 'bg-amber-500/10 text-amber-800 dark:text-amber-300', 'bg-amber-500/20 text-amber-800 dark:text-amber-200', 'bg-amber-500/35 text-amber-900 dark:text-amber-100'],
  high: ['', 'bg-orange-500/10 text-orange-700 dark:text-orange-300', 'bg-orange-500/20 text-orange-800 dark:text-orange-200', 'bg-orange-500/35 text-orange-900 dark:text-orange-100'],
  critical: ['', 'bg-red-500/10 text-red-700 dark:text-red-300', 'bg-red-500/20 text-red-800 dark:text-red-200', 'bg-red-500/35 text-red-900 dark:text-red-100'],
  expired: ['', 'bg-rose-900/10 text-rose-900 dark:bg-rose-500/15 dark:text-rose-300', 'bg-rose-900/20 text-rose-900 dark:bg-rose-500/25 dark:text-rose-200', 'bg-rose-900/80 text-white dark:bg-rose-600/70'],
  error: ['', 'bg-zinc-500/10 text-zinc-700 dark:text-zinc-300', 'bg-zinc-500/20 text-zinc-800 dark:text-zinc-200', 'bg-zinc-500/35 text-zinc-900 dark:text-zinc-100'],
}

/** Kalan gün rozeti tonu — kova (30/14/7 gün pencereleri). */
const DAYS_TONE = {
  ok: 'bg-muted text-foreground',
  d30: 'bg-amber-500/15 text-amber-800 dark:text-amber-300',
  d14: 'bg-orange-500/15 text-orange-700 dark:text-orange-300',
  d7: 'bg-red-500/15 text-red-700 dark:text-red-300',
  expired: 'bg-destructive text-white dark:bg-destructive/80',
  error: 'bg-transparent text-muted-foreground',
  unknown: 'bg-transparent text-muted-foreground',
}

/**
 * Dağılım grafiği renkleri — sıralı aciliyet rampası (yeşil = sağlıklı, sonra sarı → turuncu → kırmızı → koyu gül).
 * Koyu tema kendi adımlarını alır (koyu yüzeyde okunur). Renk tek başına anlam taşımaz: gösterge etiketli + sayılı.
 *
 * Tek kaynak: değişkenler grafiği ve göstergeyi saran KARTA yazılır (`BUCKET_VARS`); grafik yapılandırması
 * (`--color-<kova>`) ve gösterge kareleri (`SWATCH`) aynı değişkeni okur — iki yerde ayrı renk yazılmaz.
 */
export const BUCKET_VARS = cn(
  '[--stats-ok:#16a34a] [--stats-d30:#fbbf24] [--stats-d14:#f97316] [--stats-d7:#dc2626] [--stats-expired:#881337]',
  'dark:[--stats-ok:#22c55e] dark:[--stats-d14:#fb923c] dark:[--stats-d7:#ef4444] dark:[--stats-expired:#be123c]',
)
export const BUCKET_COLOR_VAR = {
  ok: 'var(--stats-ok)', d30: 'var(--stats-d30)', d14: 'var(--stats-d14)', d7: 'var(--stats-d7)', expired: 'var(--stats-expired)',
}
export const SWATCH = {
  ok: 'bg-(--stats-ok)', d30: 'bg-(--stats-d30)', d14: 'bg-(--stats-d14)', d7: 'bg-(--stats-d7)', expired: 'bg-(--stats-expired)',
}

const TIER_TONE = { 1: 'bg-indigo-600 text-white', 2: 'bg-sky-600 text-white', 3: 'bg-cyan-600 text-white', 4: 'bg-zinc-500 text-white' }

export function TierBadge({ tier, className }) {
  if (tier == null) return null
  return (
    <Badge data-slot="stats-tier" className={cn('rounded px-1.5 text-[11px] font-extrabold', TIER_TONE[tier] ?? TIER_TONE[4], className)}>
      T{tier}
    </Badge>
  )
}

export function LevelBadge({ cert, level: forced, className }) {
  const t = useT()
  const level = forced ?? levelOfCert(cert)
  const Icon = level === 'error' ? WifiOff : level === 'expired' ? AlertOctagon : null
  return (
    <Badge variant="secondary" data-slot="stats-level" data-status={level} className={cn('gap-1 font-semibold', LEVEL_TONE[level], className)}>
      {Icon && <Icon aria-hidden="true" />}{t(LEVEL_TEXT[level])}
    </Badge>
  )
}

/** "12 gün içinde" / "bugün doluyor" / "3 gün önce doldu" / "—". */
export function relativeDays(days, t) {
  if (days == null) return '—'
  if (days < 0) return t('inv.expiredAgo', -days)
  if (days === 0) return t('forecast.dueToday')
  return t('forecast.inDays', days)
}

/** Kısa birimli metin: "Bugün" / "1 gün" / "12 gün" / "3 gün önce doldu". */
function unitDays(days, t) {
  if (days == null) return '—'
  if (days < 0) return t('inv.expiredAgo', -days)
  if (days === 0) return t('stv.today')
  return days === 1 ? t('stv.day1') : t('stv.daysN', days)
}

/**
 * Kalan gün rozeti. `long`: telefon kartında tam metin ("12 gün içinde"); `unit`: kısa birimli ("12 gün", "Bugün" —
 * yaklaşan bitişler listesi); varsayılan tabloda sayı (başlık "Kalan gün"), tam metin ekran okuyucuya ve `title`'a.
 */
export function DaysBadge({ cert, long = false, unit = false, className }) {
  const t = useT()
  const days = cert?.days_remaining
  const bucket = bucketOf(cert)
  const full = relativeDays(days, t)
  const short = days == null ? '—' : days < 0 ? `−${-days}` : String(days)
  return (
    <Badge variant="secondary" data-slot="stats-days" data-bucket={bucket} title={full}
      className={cn('min-w-9 justify-center font-bold tabular-nums', DAYS_TONE[bucket], className)}>
      {long ? full : unit ? unitDays(days, t) : <><span aria-hidden="true">{short}</span><span className="sr-only">{full}</span></>}
    </Badge>
  )
}
