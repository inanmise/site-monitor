import { zonedMs } from '../../../../utils/relativeTime.js'

/**
 * Giriş istatistikleri ekranının SAF modeli (2026-10-03, Giriş Yöntemleri → İstatistikler). Sunucu verisini
 * (`/api/admin/login-methods/stats*`) ekran biçimine çevirir; React yok → doğrudan test edilir.
 */

/** Kanal sırası (sunucu `LoginChannel` ile aynı) ve grafik renkleri (tema jetonları; koyu temada da okunur). */
export const CHANNELS = ['LDAP', 'LOCAL', 'OTP_PUSH', 'OTP_EMAIL', 'REMEMBER_ME']
export const CHANNEL_COLOR = {
  LDAP: 'var(--chart-1)',
  LOCAL: 'var(--chart-5)',
  OTP_PUSH: 'var(--chart-2)',
  OTP_EMAIL: 'var(--chart-3)',
  REMEMBER_ME: 'var(--muted-foreground)',
  OTHER: 'var(--border)',
  failed: 'var(--destructive)',
}

/** Dönem seçenekleri (gün); varsayılan 7 (URL'ye yazılmaz). */
export const PERIODS = [1, 7, 30, 90]
export const DEFAULT_PERIOD = 7

/** URL değeri → geçerli dönem. */
export function periodOf(v) {
  const n = Number(v)
  return PERIODS.includes(n) ? n : DEFAULT_PERIOD
}

/** Kanal adı → i18n anahtarı (literal, i18n-used-keys kapısı görebilsin). */
export const CHANNEL_KEYS = {
  LDAP: 'lm.stats.ch.LDAP',
  LOCAL: 'lm.stats.ch.LOCAL',
  OTP_PUSH: 'lm.stats.ch.OTP_PUSH',
  OTP_EMAIL: 'lm.stats.ch.OTP_EMAIL',
  REMEMBER_ME: 'lm.stats.ch.REMEMBER_ME',
  UNKNOWN: 'lm.stats.ch.UNKNOWN',
  OTHER: 'lm.stats.ch.OTHER',
}

export function channelLabel(ch, t) {
  const key = CHANNEL_KEYS[ch]
  return key ? t(key) : String(ch ?? '—')
}

/**
 * Hesabın "son giriş yöntemi" (app_users.last_login_method: PASSWORD / REMEMBER_ME / OTP_PUSH / OTP_EMAIL) → kanal:
 * PASSWORD, hesap kaynağına göre LDAP ya da yerel şifredir.
 */
export function channelOfLoginMethod(method, authSource) {
  const m = String(method || '').toUpperCase()
  if (!m) return null
  if (m === 'PASSWORD' || m === 'LOCAL') return String(authSource || '').toUpperCase() === 'LDAP' ? 'LDAP' : 'LOCAL'
  return CHANNELS.includes(m) ? m : null
}

/** Neden kodu → etiket; sözlükte yoksa ham kod (sessiz anahtar sızıntısı yok). */
export function reasonLabel(code, t) {
  if (!code) return '—'
  const key = `lm.stats.reason.${code}`
  const s = t(key)
  return s === key ? String(code) : s
}

/** Kod isteği bastırma nedeni → etiket; yoksa ham kod. */
export function suppressedLabel(code, t) {
  if (!code) return '—'
  const key = `lm.stats.sup.${code}`
  const s = t(key)
  return s === key ? String(code) : s
}

/** Kayıtlı ayarlar + durum → kanal açık mı ("Kapalı" rozeti). Veri yoksa null (rozet çizilmez). */
export function enabledChannels(settings, status) {
  if (!settings) return null
  return {
    LDAP: settings.ldap_enabled !== false,
    LOCAL: true,
    OTP_PUSH: !!settings.push_enabled && !!status?.push_gateway_configured,
    OTP_EMAIL: !!settings.email_enabled,
    REMEMBER_ME: true,
  }
}

/** Sayı biçimi (yerel ayara göre binlik ayırıcı). */
export function fmtNum(n, locale) {
  const v = Number(n)
  return Number.isFinite(v) ? v.toLocaleString(locale) : '—'
}

/** Oran (0–1) → yüzde metni; null → "—". */
export function fmtPct(r, locale, digits = 1) {
  if (r == null || !Number.isFinite(Number(r))) return '—'
  return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: digits }).format(Number(r))
}

/**
 * Önceki döneme göre değişim: sayılarda yüzde (`pct`), oranda yüzde PUAN (`points`). `good`: artış iyi mi (başarısızda
 * artış kötü). Önceki değer yoksa / 0 ise `pct` null (sonsuz artış yazılmaz).
 */
export function delta(cur, prev, { rate = false, good = 'up' } = {}) {
  const c = Number(cur)
  const p = Number(prev)
  if (cur == null || prev == null || !Number.isFinite(c) || !Number.isFinite(p)) return null
  const diff = c - p
  const dir = diff > 0 ? 'up' : diff < 0 ? 'down' : 'flat'
  const tone = dir === 'flat' || good === 'none' ? 'neutral' : (dir === good ? 'good' : 'bad')
  if (rate) return { dir, tone, points: Math.round(diff * 1000) / 10 }
  return { dir, tone, pct: p === 0 ? null : diff / p, diff }
}

/** İstanbul saatiyle kova etiketi: saatlikte "14:00", günlükte "03 Eki". `full`: ipucu için tam tarih. */
function bucketLabels(ts, hourly, locale) {
  const d = new Date(zonedMs(ts))
  if (Number.isNaN(d.getTime())) return null
  const tz = 'Europe/Istanbul'
  const label = hourly
    ? new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: tz }).format(d)
    : new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'short', timeZone: tz }).format(d)
  const full = hourly
    ? new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: tz }).format(d)
    : new Intl.DateTimeFormat(locale, { weekday: 'short', day: '2-digit', month: 'long', year: 'numeric', timeZone: tz }).format(d)
  return { label, full }
}

/**
 * Seri → grafik noktaları. BOZUK kayıt (dize olmayan / çözülemeyen `ts`) DÜŞER — grafik çökmez (ResponseTimeChart
 * deseni). Sayısal olmayan değer 0 sayılır. Her noktada kanal başarıları + `OTHER` + `failed` + `success` (toplam).
 */
export function chartPoints(series, granularity, locale) {
  const hourly = granularity === 'hour'
  const out = []
  for (const p of Array.isArray(series) ? series : []) {
    if (!p || typeof p !== 'object' || typeof p.ts !== 'string') continue
    const lab = bucketLabels(p.ts, hourly, locale)
    if (!lab) continue
    const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)
    const point = { ts: p.ts, label: lab.label, full: lab.full, OTHER: num(p.OTHER), failed: num(p.failed) }
    let success = point.OTHER
    for (const ch of CHANNELS) { point[ch] = num(p[ch]); success += point[ch] }
    point.success = success
    out.push(point)
  }
  return out
}

/** Kullanıcı ayrıntısının küçük trendi: `{ts, success, failed}` → etiketli noktalar (bozuk kayıt düşer). */
export function miniPoints(series, granularity, locale) {
  const hourly = granularity === 'hour'
  const out = []
  for (const p of Array.isArray(series) ? series : []) {
    if (!p || typeof p !== 'object' || typeof p.ts !== 'string') continue
    const lab = bucketLabels(p.ts, hourly, locale)
    if (!lab) continue
    const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)
    out.push({ ts: p.ts, label: lab.label, full: lab.full, success: num(p.success), failed: num(p.failed) })
  }
  return out
}

/** Grafikte çizilecek seriler: verisi olan kanallar (sıra sabit) + kanalsız varsa + başarısız. */
export function activeSeries(points) {
  const has = (k) => points.some((p) => p[k] > 0)
  const keys = CHANNELS.filter(has)
  if (has('OTHER')) keys.push('OTHER')
  keys.push('failed')
  return keys
}

/** Kullanıcı tablosu satırı → toplam başarılı. */
export function successTotal(row) {
  if (row?.success_total != null) return Number(row.success_total) || 0
  return CHANNELS.reduce((s, ch) => s + (Number(row?.success?.[ch]) || 0), 0)
}

/** CSV başlık + satırları (`utils/csvExport.toCsv` ile indirilir). */
export function usersCsv(items, t, locale) {
  const headers = [
    t('lm.stats.users.col.user'), t('lm.stats.users.col.name'), t('lm.stats.users.col.team'), t('lm.stats.users.col.source'),
    t('lm.stats.users.col.active'),
    ...CHANNELS.map((ch) => channelLabel(ch, t)),
    t('lm.stats.users.col.success'), t('lm.stats.users.col.failed'), t('lm.stats.users.col.rate'),
    t('lm.stats.users.col.last'), t('lm.stats.users.col.lastChannel'), t('lm.stats.users.col.lastFail'),
    t('lm.stats.users.col.lastFailReason'),
  ]
  const rows = (items || []).map((r) => [
    r.username, r.display_name || '', r.team_name || '', r.source || '', r.active === false ? t('lm.stats.passive') : t('lm.stats.active'),
    ...CHANNELS.map((ch) => Number(r.success?.[ch]) || 0),
    successTotal(r), Number(r.failed) || 0, fmtPct(r.success_rate, locale),
    r.last_success?.at || '', r.last_success?.channel ? channelLabel(r.last_success.channel, t) : '',
    r.last_failure?.at || '', r.last_failure?.reason ? reasonLabel(r.last_failure.reason, t) : '',
  ])
  return { headers, rows }
}

/**
 * Kullanıcının giriş istatistiklerine DERİN BAĞLANTI paramları (Ayarlar → Giriş Yöntemleri → İstatistikler + ayrıntı
 * Sheet'i) — `navigateTo('settings', loginStatsParams(ad))`. Yalnız global yöneticiye gösterilir (sayfa da ona açık).
 */
export function loginStatsParams(username) {
  return { sec: 'loginmethods', lm_tab: 'stats', lm_user: username }
}

/** Kullanıcı dizini / zaman çizelgesi kanal özeti `{LDAP: {success, failed}}` → sıralı çipler (boşlar atlanır). */
export function channelChips(channels) {
  if (!channels || typeof channels !== 'object') return []
  return CHANNELS.filter((ch) => channels[ch]).map((ch) => ({
    channel: ch, success: Number(channels[ch].success) || 0, failed: Number(channels[ch].failed) || 0,
  })).filter((c) => c.success + c.failed > 0)
}
