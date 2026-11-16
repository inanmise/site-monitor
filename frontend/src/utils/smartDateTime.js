/**
 * Yazılabilir tarih + saat alanının saf kuralları (2026-11-17, kullanıcı isteği: "Olay & Hata Geçmişi'nde yeni olay eklerken
 * tarih alanlarına çok zor giriş yapılıyor; çok daha efektif ve üst düzey bir tarih seçimi").
 *
 * Değer sözleşmesi `ui/DateTimeField` ile AYNI: dışarıya proje UTC ISO dizgesi (`yyyy-MM-dd'T'HH:mm:ss`, bölge eki yok) verilir;
 * kullanıcı YEREL saat yazar / seçer (tarayıcı — kurumda Europe/Istanbul), saklarken UTC'ye çevrilir.
 *
 * Yazım kuralları (`parseTyped`, tek satır, en çok {@link MAX_TYPED} karakter, yalnız sınırlı ve doğrusal düzenli ifadeler):
 *   • "16.11.2026 14:30", "16/11/2026 14:30", "16-11-26 1430", "16.11 14:30" (yıl yoksa bu yıl), "16.11.2026" (saat yoksa
 *     mevcut değerin saati, o da yoksa 00:00)
 *   • "2026-11-16 14:30", "2026-11-16T14:30:00" (ISO; YEREL okunur — kullanıcı yazdı)
 *   • "14:30", "14.30", "1430", "9:05", "14" (yalnız saat → mevcut değerin günü, o da yoksa bugün)
 *   • "şimdi" / "now"; "bugün 14:30" / "today 14:30"; "dün 09:00" / "yesterday 9:00" (gün sözcüğü yalnızsa mevcut saat)
 *   Takvimde olmayan gün (31.02) ya da aralık dışı saat (25:00) → `{ error }`; tahmin YOK.
 */

const pad = (n) => String(n).padStart(2, '0')

/** Yazılabilir alanın en uzun girdisi — daha uzunu ayrıştırılmaz (sınırlı iş). */
export const MAX_TYPED = 40

/** Dakika listesinin adımı (saat listesi 00–23; dakika 00, 05 … 55 — tam dakika yazarak girilir). */
export const MINUTE_STEP = 5

/** Hızlı seçimlerin "gelecek" sayılmadan önceki payı (saat kayması / yazma süresi). */
export const FUTURE_SLACK_MS = 60_000

/** JS Date (an) → proje UTC ISO dizgesi (saniye 00). */
export function toIso(d) {
  if (!(d instanceof Date) || Number.isNaN(d.getTime())) return ''
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
    + `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:00`
}

/** Saklanan UTC ISO → Date (bölge eki yoksa UTC kabul edilir — `DateTimeField.parseIso` ile aynı). Boş / bozuk → null. */
export function parseIso(value) {
  if (!value) return null
  const s = String(value)
  const withZ = /[zZ]$|[+-]\d\d:?\d\d$/.test(s) ? s : s + 'Z'
  const d = new Date(withZ)
  return Number.isNaN(d.getTime()) ? null : d
}

/** Date → alanda gösterilen YEREL metin "dd.MM.yyyy HH:mm". */
export function formatLocal(d) {
  if (!(d instanceof Date) || Number.isNaN(d.getTime())) return ''
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Dakikaya yuvarla (saniye / milisaniye sıfır) — kopya döner. */
export function floorMinute(d) {
  const c = new Date(d)
  c.setSeconds(0, 0)
  return c
}

/** Türkçe büyük-küçük harf katlaması + boşluk sadeleştirme (İ → i, I → ı). */
function normalize(text) {
  return String(text ?? '').trim().toLocaleLowerCase('tr-TR').replace(/\s+/g, ' ')
}

const NOW_WORDS = new Set(['şimdi', 'simdi', 'now'])
const TODAY_WORDS = new Set(['bugün', 'bugun', 'today'])
const YESTERDAY_WORDS = new Set(['dün', 'dun', 'yesterday'])

/** "14:30" / "14.30" / "1430" / "9:05" / "14" (+ isteğe bağlı ":ss") → [saat, dakika] ya da null. */
export function parseTime(text) {
  const m = /^(\d{1,2})(?:[:.]?(\d{2}))?(?::\d{2})?$/.exec(text)
  if (!m) return null
  const h = Number(m[1])
  const mi = m[2] == null ? 0 : Number(m[2])
  return h < 24 && mi < 60 ? [h, mi] : null
}

/** Gerçek takvim günü mü (31.02 değil) — yerel Date kurar; değilse null. */
function realDate(y, mo, d, h, mi) {
  if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || d < 1 || d > 31) return null
  const dt = new Date(y, mo - 1, d, h, mi, 0, 0)
  return dt.getFullYear() === y && dt.getMonth() === mo - 1 && dt.getDate() === d ? dt : null
}

/**
 * Yazılan metni çözer. Dönüş:
 *   `{ empty: true }` — boş; `{ date, complete }` — çözüldü (`complete`: gün VE saat açıkça yazıldı ya da "şimdi" — yazarken
 *   anında kaydedilebilir; yalnız gün / yalnız saat odak çıkınca kaydedilir); `{ error: true }` — anlaşılamadı.
 * `base` (Date | null): eksik kalan parçanın (saat ya da gün) alınacağı mevcut değer; `now`: bugünün kaynağı (test için).
 */
export function parseTyped(text, { now = new Date(), base = null } = {}) {
  const raw = String(text ?? '')
  if (raw.length > MAX_TYPED) return { error: true }
  const s = normalize(raw)
  if (!s) return { empty: true }
  if (NOW_WORDS.has(s)) return { date: floorMinute(now), complete: true }

  const baseHm = base ? [base.getHours(), base.getMinutes()] : null

  // "bugün 14:30" / "dün" / "yesterday 9:00"
  const word = /^(\S+)(?: (.+))?$/.exec(s)
  if (word && (TODAY_WORDS.has(word[1]) || YESTERDAY_WORDS.has(word[1]))) {
    const day = new Date(now)
    if (YESTERDAY_WORDS.has(word[1])) day.setDate(day.getDate() - 1)
    const hm = word[2] == null ? (baseHm || [0, 0]) : parseTime(word[2])
    if (!hm) return { error: true }
    const d = realDate(day.getFullYear(), day.getMonth() + 1, day.getDate(), hm[0], hm[1])
    return d ? { date: d, complete: word[2] != null } : { error: true }
  }

  // ISO: 2026-11-16, 2026-11-16 14:30, 2026-11-16T14:30:00
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[t ](\d{1,2}(?::\d{2}){0,2}))?$/.exec(s)
  if (iso) {
    const hm = iso[4] == null ? (baseHm || [0, 0]) : parseTime(iso[4])
    if (!hm) return { error: true }
    const d = realDate(Number(iso[1]), Number(iso[2]), Number(iso[3]), hm[0], hm[1])
    return d ? { date: d, complete: iso[4] != null } : { error: true }
  }

  // Gün önce: 16.11.2026 14:30 · 16/11/26 1430 · 16-11 14.30 · 16.11.2026
  const dmy = /^(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2}|\d{4}))?(?: (.+))?$/.exec(s)
  if (dmy) {
    const hm = dmy[4] == null ? (baseHm || [0, 0]) : parseTime(dmy[4])
    if (!hm) return { error: true }
    const d = dmy[3] == null
      ? nearestYear(Number(dmy[1]), Number(dmy[2]), hm, now)
      : realDate(dmy[3].length === 2 ? 2000 + Number(dmy[3]) : Number(dmy[3]), Number(dmy[2]), Number(dmy[1]), hm[0], hm[1])
    if (d) return { date: d, complete: dmy[4] != null }
    // "14.30" tek başına geçerli bir gün değil (30. ay yok) → saat olarak okunur; "16.11" gibi geçerli gün ise GÜN kalır
    if (dmy[3] == null && dmy[4] == null && s.includes('.')) return timeOnly(s, base, now)
    return { error: true }
  }

  return timeOnly(s, base, now)
}

/**
 * Yıl yazılmadıysa bu yıl ya da geçen yıl — "şimdi"ye en YAKIN olanı (olay geçmişte olur: ocakta yazılan "31.12" geçen yılın
 * 31 Aralık'ıdır, neredeyse bir yıl sonrası değil). İkisi de takvimde yoksa (29.02) null.
 */
function nearestYear(day, month, hm, now) {
  const y = now.getFullYear()
  const cur = realDate(y, month, day, hm[0], hm[1])
  const prev = realDate(y - 1, month, day, hm[0], hm[1])
  if (!cur || !prev) return cur || prev
  return Math.abs(cur.getTime() - now.getTime()) <= Math.abs(now.getTime() - prev.getTime()) ? cur : prev
}

/**
 * Yazarken ANINDA kaydedilebilir mi: gün (ya da gün sözcüğü) ile birlikte dakikası iki haneyle yazılmış bir saat ("14:30",
 * "9.05", "1430"), yıl yazıldıysa dört hane; ya da "şimdi". Yarım yazılmış saat ("…2026 9", "…2026 143") ve yarım yıl
 * ("16.11.20 14:30" — yıl yeniden yazılırken) anında kaydedilmez; odak çıkınca ya da Enter ile çözülür (gözden kaçan yarım
 * değer kaydedilip kalmasın — 2026-11-17 inceleme).
 */
export function liveReady(text) {
  const raw = String(text ?? '')
  if (raw.length > MAX_TYPED) return false
  const s = normalize(raw)
  if (NOW_WORDS.has(s)) return true
  if (!/(?:^|[ t])(?:\d{1,2}[:.]\d{2}|\d{4})(?::\d{2})?$/.test(s)) return false
  if (/^\d{1,2}[./-]\d{1,2}[./-]\d{1,3}(?: |$)/.test(s)) return false
  const r = parseTyped(raw)
  return !!(r.date && r.complete)
}

/** Yalnız saat: mevcut değerin günü, yoksa bugün. */
function timeOnly(s, base, now) {
  const hm = parseTime(s)
  if (!hm) return { error: true }
  const day = base || now
  const d = realDate(day.getFullYear(), day.getMonth() + 1, day.getDate(), hm[0], hm[1])
  return d ? { date: d, complete: false } : { error: true }
}

/**
 * Hızlı seçimler. `anchor` (Date | null) verilirse "oluştan +N dk" türü (tespit / çözüm zamanı), yoksa "N dk önce" türü
 * (oluş zamanı). Her öğe `{ key, minutes?, at, disabled }`: gelecekteki an (> şimdi + pay) ve `min` öncesi seçilemez —
 * olay geçmişte olur, sıra kuralını form ayrıca denetler.
 */
export function presetsFor({ anchor = null, min = null, now = new Date() } = {}) {
  const base = floorMinute(now)
  const items = anchor
    ? [{ key: 'now' }, ...[5, 15, 30, 60, 120].map((m) => ({ key: 'after', minutes: m }))]
    : [{ key: 'now' }, ...[15, 30, 60, 120].map((m) => ({ key: 'ago', minutes: m })), { key: 'yesterday' }]
  return items.map((p) => {
    let at
    if (p.key === 'now') at = base
    else if (p.key === 'ago') at = new Date(base.getTime() - p.minutes * 60_000)
    else if (p.key === 'yesterday') at = new Date(base.getTime() - 24 * 60 * 60_000)
    else at = new Date(floorMinute(anchor).getTime() + p.minutes * 60_000)
    const future = at.getTime() > now.getTime() + FUTURE_SLACK_MS
    const beforeMin = !!(min && at.getTime() < floorMinute(min).getTime())
    return { ...p, at, disabled: future || beforeMin, future }
  })
}

/** Saat listesi (00–23) ve dakika listesi (00, 05 … 55; seçili dakika adımda değilse o da eklenir, sıralı). */
export function hourOptions() {
  return Array.from({ length: 24 }, (_, h) => h)
}
export function minuteOptions(selected = null) {
  const list = Array.from({ length: 60 / MINUTE_STEP }, (_, i) => i * MINUTE_STEP)
  if (selected != null && Number.isInteger(selected) && selected >= 0 && selected < 60 && !list.includes(selected)) {
    list.push(selected)
    list.sort((a, b) => a - b)
  }
  return list
}

/** Klavye adımı: ↑ / ↓ 5 dk, Shift ile 1 saat. Değer yoksa null (adımlanacak bir şey yok). */
export function stepValue(d, key, shift) {
  if (!d) return null
  const dir = key === 'ArrowUp' ? 1 : key === 'ArrowDown' ? -1 : 0
  if (!dir) return null
  return new Date(floorMinute(d).getTime() + dir * (shift ? 60 : MINUTE_STEP) * 60_000)
}
