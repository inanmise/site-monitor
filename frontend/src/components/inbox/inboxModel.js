import { Bell, Siren, CheckCircle2, Wrench, CalendarDays, ClipboardX } from 'lucide-react'

/**
 * Bildirim kutusu modeli (2026-09-26, v3 yeniden tasarım) — saf yardımcılar, React yok.
 *
 * - Tür → ikon + tonlu kutucuk (kartlarda sol şerit YOK; tür rengi yalnız ikon kutucuğunda).
 * - Okundu / temizlendi kümeleri localStorage'da, kullanıcı adına göre ayrık (v2 anahtarları KORUNDU:
 *   `inbox-seen:<kullanıcı>` / `inbox-dismissed:<kullanıcı>` — yeniden tasarım kullanıcının durumunu sıfırlamaz).
 * - Güne göre gruplama (Yaklaşan / Bugün / Dün / Daha önce) YEREL saatle; sunucu sırası grup içinde korunur.
 * - Göreli zaman Intl.RelativeTimeFormat ile (TR/EN yereli `useDateLocale`'den).
 */
export const KIND_META = {
  alert_open:         { icon: Siren,        tile: 'bg-destructive/10 text-destructive' },
  alert_resolved:     { icon: CheckCircle2, tile: 'bg-success/12 text-success' },
  maintenance_active: { icon: Wrench,       tile: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  maintenance_soon:   { icon: Wrench,       tile: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  weekly_due:         { icon: CalendarDays, tile: 'bg-primary/10 text-primary' },
  exception_expired:  { icon: ClipboardX,   tile: 'bg-orange-500/15 text-orange-700 dark:text-orange-300' },
}
const DEFAULT_META = { icon: Bell, tile: 'bg-muted text-muted-foreground' }
export const kindMeta = (kind) => KIND_META[kind] || DEFAULT_META

/** Uyarı seviyesi rozeti tonu (shadcn Badge secondary üstüne; açık/koyu temada okunur). */
export const LEVEL_TONE = {
  critical: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
  high: 'bg-orange-100 text-orange-800 dark:bg-orange-950 dark:text-orange-300',
  warning: 'bg-yellow-100 text-yellow-800 dark:bg-yellow-950 dark:text-yellow-300',
}

export const STORE = (u) => `inbox-seen:${u || 'anon'}`
export const STORE_DISMISSED = (u) => `inbox-dismissed:${u || 'anon'}`
export function readSet(k) { try { return new Set(JSON.parse(localStorage.getItem(k) || '[]')) } catch { return new Set() } }
export function writeSet(k, set) { try { localStorage.setItem(k, JSON.stringify([...set].slice(-500))) } catch { /* yoksay */ } }

/** Sunucu zamanları çıplak UTC (`YYYY-MM-DDTHH:mm:ss`) gelir; 'Z' eklenerek okunur. */
export function toMs(iso) {
  if (!iso) return NaN
  return new Date(iso + (iso.endsWith('Z') ? '' : 'Z')).getTime()
}

/** Satırın olay zamanı: `at` (olay), yoksa başlangıç/bitiş. */
export function itemTime(it) {
  return it?.at || it?.started_at || it?.ended_at || it?.created_at || null
}

/** Süre metni: "12 dk" / "3 sa 12 dk" / "2 g 5 sa". */
export function fmtDuration(ms, t) {
  if (!Number.isFinite(ms) || ms < 0) return ''
  const m = Math.floor(ms / 60000)
  if (m < 60) return t('inbox.durMin', m)
  const h = Math.floor(m / 60)
  if (h < 48) return t('inbox.durHour', h, m % 60)
  return t('inbox.durDay', Math.floor(h / 24), h % 24)
}

const MIN = 60_000, HOUR = 3_600_000, DAY = 86_400_000

/** Göreli zaman: "az önce" / "12 dakika önce" / "3 saat önce" / "2 gün önce" / "3 saat sonra" (yerel Intl). */
export function relativeTime(iso, t, locale, now = Date.now()) {
  const ms = toMs(iso)
  if (!Number.isFinite(ms)) return ''
  const diff = ms - now
  const abs = Math.abs(diff)
  if (abs < MIN) return t('inbox.justNow')
  const [unitMs, unit] = abs < HOUR ? [MIN, 'minute'] : abs < DAY ? [HOUR, 'hour'] : abs < 30 * DAY ? [DAY, 'day']
    : abs < 365 * DAY ? [30 * DAY, 'month'] : [365 * DAY, 'year']
  // Yuvarlama mutlak değerde (Math.round(-1.5) = -1 → "1 ay önce" yerine "2 ay önce")
  const n = Math.round(abs / unitMs)
  try {
    return new Intl.RelativeTimeFormat(locale, { numeric: 'always' }).format(diff < 0 ? -n : n, unit)
  } catch {
    return ''
  }
}

export const GROUP_ORDER = ['upcoming', 'today', 'yesterday', 'earlier']

/** Yerel takvim gününe göre grup anahtarı. Gelecek (yaklaşan bakım, bugün ilerideki rapor saati) → 'upcoming'. */
export function dayGroup(iso, now = Date.now()) {
  const ms = toMs(iso)
  if (!Number.isFinite(ms)) return 'earlier'
  const startOf = (x) => { const d = new Date(x); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime() }
  const days = Math.round((startOf(now) - startOf(ms)) / DAY)
  if (days < 0) return 'upcoming'
  if (days === 0) return ms > now ? 'upcoming' : 'today'
  if (days === 1) return 'yesterday'
  return 'earlier'
}

/** [{ key, items }] — yalnız dolu gruplar, GROUP_ORDER sırasıyla; grup içinde gelen sıra (sunucu önceliği) korunur. */
export function groupByDay(items, now = Date.now()) {
  const buckets = new Map(GROUP_ORDER.map((k) => [k, []]))
  for (const it of items || []) buckets.get(dayGroup(itemTime(it), now)).push(it)
  return GROUP_ORDER.map((key) => ({ key, items: buckets.get(key) })).filter((g) => g.items.length > 0)
}
