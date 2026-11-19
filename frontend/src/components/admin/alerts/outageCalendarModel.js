/**
 * Takım kesinti takvimi (2026-11-19) — Alarm Geçmişi "Takvim" görünümünün SAF modeli (React'siz, test edilebilir).
 *
 * Sunucu sözleşmesi (`GET /api/admin/alerts/outage-calendar?team=<id>&month=yyyy-MM`, OutageCalendarController):
 * `{ month, team_id, team_name, days: [{ day: 'yyyy-MM-dd', outage_minutes: number|null, alarms, worst_level, no_data? }],
 *   total_minutes, truncated, retention_days?, covered_from?, oldest_month? }`. Gün = Europe/Istanbul takvim günü;
 * `outage_minutes` null → gelecek gün ya da `no_data: true` (çözülmüş alarmlar saklama süresi dolunca silinir — o gün
 * "bilinmiyor"dur, asla "kesinti yok" değil; inceleme 2026-11-19). `oldest_month` = verisi olan en eski ay.
 *
 * URL: `cal=1` takvim görünümü (yok = liste), `calm=yyyy-MM` gösterilen ay (yok = bu ay, İstanbul), takım mevcut `team`
 * anahtarında (listeyle ortak). Anahtarlar uygulamanın PAGE_STATE_PARAMS listesinde.
 *
 * Saat dilimi: İstanbul sabit UTC+3 (yaz saati yok) — "bu ay" ve "bugün" tarayıcının saat diliminden BAĞIMSIZ hesaplanır.
 */

export const CAL_KEY = 'cal'
export const CAL_MONTH_KEY = 'calm'
/** Sunucuyla aynı sınır (OutageCalendarController.MAX_MONTHS_BACK). */
export const MAX_MONTHS_BACK = 24
const IST_OFFSET_MS = 3 * 3_600_000
const MONTH_RX = /^(\d{4})-(0[1-9]|1[0-2])$/
const DAY_RX = /^(\d{4})-(\d{2})-(\d{2})$/

const pad = (n) => String(n).padStart(2, '0')

/** İstanbul'da şu anki ay (yyyy-MM). */
export function currentMonth(nowMs = Date.now()) {
  const d = new Date(nowMs + IST_OFFSET_MS)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`
}

/** İstanbul'da bugün (yyyy-MM-dd). */
export function todayKey(nowMs = Date.now()) {
  const d = new Date(nowMs + IST_OFFSET_MS)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

export const isMonth = (v) => MONTH_RX.test(String(v || ''))

/** Ay kaydırma: ('2026-01', -1) → '2025-12'. Geçersiz ay → null. */
export function shiftMonth(month, delta) {
  const m = MONTH_RX.exec(String(month || ''))
  if (!m) return null
  const idx = Number(m[1]) * 12 + (Number(m[2]) - 1) + delta
  return `${Math.floor(idx / 12)}-${pad((idx % 12) + 1)}`
}

/** a − b ay farkı (ikisi de geçerli olmalı). */
function monthDiff(a, b) {
  const ma = MONTH_RX.exec(a), mb = MONTH_RX.exec(b)
  return (Number(ma[1]) * 12 + Number(ma[2])) - (Number(mb[1]) * 12 + Number(mb[2]))
}

/** Seçilebilir en eski ay (bu ay − 24). */
export function oldestMonth(nowMs = Date.now()) {
  return shiftMonth(currentMonth(nowMs), -MAX_MONTHS_BACK)
}

/** URL'deki ay → geçerli ve aralıktaysa kendisi, değilse bu ay. */
export function clampMonth(v, nowMs = Date.now()) {
  const cur = currentMonth(nowMs)
  if (!isMonth(v)) return cur
  if (monthDiff(v, cur) > 0) return cur
  if (monthDiff(cur, v) > MAX_MONTHS_BACK) return cur
  return v
}

/**
 * Önceki aya gidilebilir mi: 24 ay sınırı ve — sunucu bildirdiyse — verisi olan en eski ay (`oldestMonth`; daha eskisi
 * tamamen "veri yok" olurdu).
 */
export const canGoPrev = (month, nowMs = Date.now(), oldest = null) => isMonth(month)
  && monthDiff(currentMonth(nowMs), month) < MAX_MONTHS_BACK
  && !(isMonth(oldest) && monthDiff(month, oldest) <= 0)
export const canGoNext = (month, nowMs = Date.now()) => isMonth(month) && monthDiff(month, currentMonth(nowMs)) < 0

/**
 * Ay ızgarası — Pazartesi ilk gün; her hafta 7 öğe: gün anahtarı (yyyy-MM-dd) ya da `null` (önceki / sonraki ayın boş
 * hücresi). Takvim aritmetiği UTC üzerinde (yerel saat dilimi karışmaz).
 */
export function monthGrid(month) {
  const m = MONTH_RX.exec(String(month || ''))
  if (!m) return []
  const y = Number(m[1]), mo = Number(m[2])
  const first = new Date(Date.UTC(y, mo - 1, 1))
  const days = new Date(Date.UTC(y, mo, 0)).getUTCDate()
  const lead = (first.getUTCDay() + 6) % 7   // Pazartesi = 0
  const cells = []
  for (let i = 0; i < lead; i++) cells.push(null)
  for (let d = 1; d <= days; d++) cells.push(`${m[1]}-${m[2]}-${pad(d)}`)
  while (cells.length % 7 !== 0) cells.push(null)
  const weeks = []
  for (let i = 0; i < cells.length; i += 7) weeks.push(cells.slice(i, i + 7))
  return weeks
}

/** Gün anahtarının ayın günü numarası ('2026-11-05' → 5). */
export const dayNumber = (key) => Number(DAY_RX.exec(String(key || ''))?.[3] ?? 0)

/** Gün anahtarı → UTC öğlen Date (yerel biçimlendirmede gün kaymasın — Intl'de timeZone: 'UTC' ile kullanılır). */
export function dayDate(key) {
  const m = DAY_RX.exec(String(key || ''))
  return m ? new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12)) : null
}

/** Hafta günü kısa adları (Pazartesi ilk) — dile göre Intl; 2024-01-01 bir Pazartesidir. */
export function weekdayNames(locale, style = 'short') {
  const out = []
  for (let i = 0; i < 7; i++) {
    const d = new Date(Date.UTC(2024, 0, 1 + i, 12))
    try { out.push(new Intl.DateTimeFormat(locale, { weekday: style, timeZone: 'UTC' }).format(d)) } catch { out.push(String(i + 1)) }
  }
  return out
}

/** "Kasım 2026" — dile göre. */
export function monthTitle(month, locale) {
  const m = MONTH_RX.exec(String(month || ''))
  if (!m) return ''
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, 15, 12))
  try {
    const s = new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(d)
    return s.charAt(0).toLocaleUpperCase(locale) + s.slice(1)
  } catch { return month }
}

/** "12 Kasım 2025" — dile göre, yıllı (saklama sınırı tarihi için). */
export function fullDayTitle(key, locale) {
  const d = dayDate(key)
  if (!d) return String(key || '')
  try { return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(d) } catch { return key }
}

/** "12 Kasım" — dile göre (gün adı için ayrı). */
export function dayTitle(key, locale) {
  const d = dayDate(key)
  if (!d) return String(key || '')
  try { return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(d) } catch { return key }
}

/**
 * Dakika → okunur süre: 0 → "0 dk", 45 → "45 dk", 135 → "2 sa 15 dk", 120 → "2 sa", 1440 → "24 sa". Saat üstü birim
 * yok (toplamda da saat — "744 sa"); `null` / geçersiz → null (bilinmiyor; çağıran "—" yazar).
 */
export function formatMinutes(min, t) {
  if (min == null || !Number.isFinite(Number(min)) || Number(min) < 0) return null
  const m = Math.round(Number(min))
  if (m < 60) return t('alh.cal.dur.m', m)
  const h = Math.floor(m / 60)
  const rest = m % 60
  return rest === 0 ? t('alh.cal.dur.h', h) : t('alh.cal.dur.hm', h, rest)
}

/**
 * Renk kovası: `future` (değer yok), `zero` (kesinti yok — NÖTR, yeşil değil), 1 (≤ 15 dk), 2 (≤ 60), 3 (≤ 240), 4 (> 240).
 */
export function bucketOf(min) {
  if (min == null || !Number.isFinite(Number(min))) return 'future'
  const m = Number(min)
  if (m <= 0) return 'zero'
  if (m <= 15) return 1
  if (m <= 60) return 2
  if (m <= 240) return 3
  return 4
}

/** Günün kovası: verisi yoksa `nodata` (saklama süresi — tıklanamaz, nötr), aksi {@link bucketOf}. */
export const dayBucket = (d) => (d?.noData ? 'nodata' : bucketOf(d?.minutes))

/** Kova → yıkıcı (destructive) rengin kart zeminine karışım yüzdesi — metin her kovada ön plan renginde okunur. */
export const BUCKET_MIX = { 1: 18, 2: 30, 3: 44, 4: 60 }

const LEVELS = ['CRITICAL', 'HIGH', 'WARNING']

/** Sunucu yanıtını doğrular / sadeleştirir; tanınmayan yanıt → null (çağıran "okunamadı" der, asla boş takvim değil). */
export function calendarFrom(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.days) || !isMonth(data.month)) return null
  const days = new Map()
  for (const d of data.days) {
    if (!d || !DAY_RX.test(String(d.day || ''))) continue
    const raw = d.outage_minutes
    const minutes = raw == null || !Number.isFinite(Number(raw)) ? null : Math.max(0, Math.round(Number(raw)))
    const alarms = Number.isFinite(Number(d.alarms)) ? Math.max(0, Math.round(Number(d.alarms))) : 0
    const worst = LEVELS.includes(d.worst_level) ? d.worst_level : null
    const noData = d.no_data === true
    days.set(String(d.day), { day: String(d.day), minutes: noData ? null : minutes, alarms: noData ? 0 : alarms, worst: noData ? null : worst, noData })
  }
  const total = Number.isFinite(Number(data.total_minutes)) ? Math.max(0, Math.round(Number(data.total_minutes))) : null
  const retention = Number(data.retention_days)
  return {
    month: String(data.month),
    teamId: data.team_id != null ? String(data.team_id) : null,
    teamName: data.team_name ? String(data.team_name) : null,
    days,
    totalMinutes: total,
    truncated: data.truncated === true,
    retentionDays: Number.isFinite(retention) && retention > 0 ? Math.round(retention) : null,
    coveredFrom: DAY_RX.test(String(data.covered_from || '')) ? String(data.covered_from) : null,
    oldestMonth: isMonth(data.oldest_month) ? String(data.oldest_month) : null,
  }
}

/** Özet: toplam dakika, kesintili gün sayısı, en uzun gün (eşitlikte en erken). */
export function summarize(cal) {
  if (!cal) return null
  let outageDays = 0
  let worst = null
  let sum = 0
  for (const d of cal.days.values()) {
    if (d.minutes == null) continue
    sum += d.minutes
    if (d.minutes > 0) {
      outageDays++
      if (!worst || d.minutes > worst.minutes || (d.minutes === worst.minutes && d.day < worst.day)) worst = d
    }
  }
  let noDataDays = 0
  for (const d of cal.days.values()) if (d.noData) noDataDays++
  return { totalMinutes: cal.totalMinutes ?? sum, outageDays, worstDay: worst, noDataDays }
}

/** Kesintili günler listesi (dar ekran) — tarih sırasıyla, yalnız dakikası > 0 olanlar. */
export function outageDayList(cal) {
  if (!cal) return []
  return [...cal.days.values()].filter((d) => d.minutes != null && d.minutes > 0).sort((a, b) => (a.day < b.day ? -1 : 1))
}

/** Güne tıklama → liste görünümünün süzgeçleri: takım + o gün (aralıkta AKTİF olanlar — önceki günden devreden de) + Tümü. */
export function dayListFilters(teamId, dayKey) {
  return { team: teamId == null ? '' : String(teamId), from: dayKey, to: dayKey, range: 'active' }
}

/**
 * Varsayılan takım: URL'deki takım (listede varsa) → birincil takım → üye olunan ilk takım → listedeki ilk takım. Takım
 * listesi yokken (yükleniyor) URL / birincil değer olduğu gibi döner; hiçbiri yoksa ''.
 */
export function defaultTeam({ urlTeam, primaryTeamId, myTeamIds, teams }) {
  const ids = Array.isArray(teams) ? teams.map((tm) => String(tm?.id)) : null
  const has = (v) => v != null && v !== '' && (ids == null || ids.includes(String(v)))
  if (has(urlTeam)) return String(urlTeam)
  if (has(primaryTeamId)) return String(primaryTeamId)
  for (const id of Array.isArray(myTeamIds) ? myTeamIds : []) if (has(id)) return String(id)
  return ids && ids.length > 0 ? ids[0] : ''
}

/**
 * Eski sunucu (uç yok): 405, kendi kodu olmayan 404 ya da `/alerts/{id}` sayısal kimliğine düşen 400 (`INVALID_PARAMETER`
 * ya da kodsuz). Bu sunucunun 400'ü `VALIDATION_FAILED`, 404'ü `TEAM_NOT_FOUND` taşır — onlar gerçek yanıttır.
 */
export function isUnsupportedAnswer(res) {
  const info = res?.errorInfo || {}
  const st = Number(info.status ?? res?.status)
  const code = String(info.code ?? res?.code ?? '')
  if (st === 405) return true
  if (st === 404) return code === '' || code === 'NOT_FOUND'
  if (st === 400) return code === '' || code === 'INVALID_PARAMETER'
  return false
}
