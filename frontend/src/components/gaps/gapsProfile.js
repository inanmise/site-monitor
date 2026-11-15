// Eksikler penceresi — PROFİL kuralları (2026-11-15 zenginleştirme, A paketi). SAF: React yok, ağ yok.
//
// Bugüne dek yalnız "e-posta yok / bozuk" ve "telefon yok" bakılıyordu. Eklenenler:
//   • telefon VAR ama kodla girişte eşleşmeyecek biçimde (sunucunun OtpContactMatcher.normalizePhone kuralının ikizi),
//   • önem BAĞLAMA göre: kişi nöbet çizelgesindeyse, 7/24 ekibindeyse ya da kodla girişte telefon isteniyorsa yüksek,
//   • kişisel push tercihleri: tamamen kapalı, uzun süreli erteleme, nöbetçiyken yalnız kritik alarm.
// Bilinmeyen bağlam (kaynak okunamadı) önemi değiştirmez ve tek başına eksik ÜRETMEZ (yanlış uyarı yok).

/** Karşılaştırılan hane sayısı — sunucu `OtpContactMatcher.PHONE_DIGITS`. */
export const PHONE_DIGITS = 10
/** İşlenecek en uzun telefon girdisi — sunucu `OtpContactMatcher.MAX_PHONE_INPUT` (daha uzunu eşleşmez). */
export const MAX_PHONE_INPUT = 64
/** Push ertelemesi bundan uzun sürecekse "uzun erteleme" sayılır. */
export const SNOOZE_LONG_MS = 3 * 24 * 60 * 60 * 1000

/** Profil eksik kodları (sıra = ekrandaki sıra). */
export const PROFILE_CODES = Object.freeze([
  'PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE', 'PROFILE_PHONE_INVALID',
  'PROFILE_PUSH_OFF', 'PROFILE_PUSH_SNOOZED', 'PROFILE_PUSH_CRITICAL_ONLY',
])
/** Kişinin kendisinin düzeltebildiği (kişisel push tercihleri) kodlar — "Push tercihlerim"e gider. */
export const SELF_FIX_CODES = Object.freeze(['PROFILE_PUSH_OFF', 'PROFILE_PUSH_SNOOZED', 'PROFILE_PUSH_CRITICAL_ONLY'])
/** Kullanıcı kaydında (yönetici / AD) düzenlenen kodlar — yönetici olmayana "kime sormalı" söylenir. */
export const CONTACT_CODES = Object.freeze(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE', 'PROFILE_PHONE_INVALID'])
/** Önemi artıran bağlam nedenleri (ekranda çip). */
export const CONTEXT_REASONS = Object.freeze(['ONCALL', 'NOC', 'OTP_PHONE', 'OTP_EMAIL'])

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const has = (o, k) => isObj(o) && Object.prototype.hasOwnProperty.call(o, k)

/**
 * Telefonu karşılaştırılabilir 10 haneye indirger — sunucu `OtpContactMatcher.normalizePhone`'un ikizi: yalnız rakamlar,
 * baştaki `0090` ya da (geride en az 10 hane kalıyorsa) `90`, ardından baştaki tek `0` düşer; en az 10 hane yoksa `null`.
 * ASCII dışı bir rakam (tam genişlik, Arap-Hint) görülürse `undefined` döner = BİLİNMİYOR (sunucu onları ASCII'ye çevirir;
 * istemci çevirmez, yanlış "geçersiz" demesin). Döngü girdinin uzunluğuyla sınırlı (≤ 64).
 */
export function normalizePhone(raw) {
  if (typeof raw !== 'string' || raw.length > MAX_PHONE_INPUT) return null
  let d = ''
  for (const ch of raw) {
    if (ch >= '0' && ch <= '9') d += ch
    else if (/\p{Nd}/u.test(ch)) return undefined
  }
  let s = d
  if (s.startsWith('0090')) s = s.slice(4)
  else if (s.startsWith('90') && s.length >= PHONE_DIGITS + 2) s = s.slice(2)
  if (s.startsWith('0')) s = s.slice(1)
  if (s.length < PHONE_DIGITS) return null
  return s.slice(s.length - PHONE_DIGITS)
}

/** Telefonun durumu: `missing` (boş) | `invalid` (kodla girişte eşleşmez) | `ok` | `unknown` (değerlendirilemedi). */
export function phoneState(phone) {
  if (typeof phone !== 'string' || !phone.trim()) return 'missing'
  const n = normalizePhone(phone)
  if (n === undefined) return 'unknown'
  return n === null ? 'invalid' : 'ok'
}

/**
 * Yalnız açıkça kullanılamaz adres eksik sayılır: boş, '@' yok / birden çok, yerel ya da alan kısmı boş, boşluk içeriyor.
 * Sunucunun kuralından daha katı DEĞİL (ör. `ayse@localhost` eksik sayılmaz — yanlış uyarı yok).
 */
export function deliverable(email) {
  if (typeof email !== 'string') return false
  const e = email.trim()
  const at = e.indexOf('@')
  return at > 0 && at === e.lastIndexOf('@') && at < e.length - 1 && !/\s/.test(e)
}

/**
 * Önem bağlamı. Her alan `true` | `false` | `null` (bilinmiyor). `onCall` = kişi ETKİN bir nöbet çizelgesinin üyesi,
 * `noc` = 7/24 izleme ekibinde, `otpPhone` / `otpEmail` = kodla girişte telefon / e-posta isteniyor.
 */
export function makeContext({ onCall = null, noc = null, otpPhone = null, otpEmail = null } = {}) {
  const tri = (v) => (v === true ? true : v === false ? false : null)
  return { onCall: tri(onCall), noc: tri(noc), otpPhone: tri(otpPhone), otpEmail: tri(otpEmail) }
}

/** Telefon eksiğinin önemi: bir bağlam doğruysa YÜKSEK, hepsi bilinen yanlışsa DÜŞÜK, aksi hâlde ORTA (bugünkü değer). */
export function phoneSeverity(ctx) {
  const c = makeContext(ctx)
  const list = [c.onCall, c.noc, c.otpPhone]
  if (list.some((v) => v === true)) return 'HIGH'
  if (list.every((v) => v === false)) return 'LOW'
  return 'MEDIUM'
}

/** Telefon eksiğini açıklayan bağlam nedenleri (yalnız doğru olanlar). */
export function phoneReasons(ctx) {
  const c = makeContext(ctx)
  return [c.onCall && 'ONCALL', c.noc && 'NOC', c.otpPhone && 'OTP_PHONE'].filter(Boolean)
}

function parseMs(v) {
  if (typeof v !== 'string' || !v) return null
  const ms = Date.parse(v)
  return Number.isFinite(ms) ? ms : null
}

/**
 * Profil eksikleri. `profile` = taze `/me` yanıtı (alan yanıtta HİÇ yoksa bilinmiyor sayılır — eksik denmez); `ctx` önem
 * bağlamı; `prefs` = `/me/push-preferences` (null = okunmadı → push eksikleri yok, çağıran "okunamadı" der); `now` ms.
 * Her eksik `{ key, category:'profile', code, severity, scope, reasons }` — `scope` 'p' (kullanıcı kaydı) ya da 'pp' (push).
 */
export function profileGaps(profile, ctx = {}, prefs = null, now = Date.now()) {
  if (!isObj(profile)) return []
  const c = makeContext(ctx)
  const out = []
  if (has(profile, 'email') && !deliverable(profile.email)) {
    out.push({ key: 'PROFILE_NO_EMAIL', category: 'profile', code: 'PROFILE_NO_EMAIL', severity: 'HIGH', scope: 'p',
      reasons: c.otpEmail ? ['OTP_EMAIL'] : [] })
  }
  if (has(profile, 'phone')) {
    const st = phoneState(profile.phone)
    if (st === 'missing' || st === 'invalid') {
      const code = st === 'missing' ? 'PROFILE_NO_PHONE' : 'PROFILE_PHONE_INVALID'
      out.push({ key: code, category: 'profile', code, severity: phoneSeverity(c), scope: 'p', reasons: phoneReasons(c) })
    }
  }
  if (isObj(prefs)) {
    const important = c.onCall === true || c.noc === true
    const reasons = [c.onCall && 'ONCALL', c.noc && 'NOC'].filter(Boolean)
    if (prefs.opt_out === true) {
      out.push({ key: 'PROFILE_PUSH_OFF', category: 'profile', code: 'PROFILE_PUSH_OFF', severity: important ? 'MEDIUM' : 'LOW', scope: 'pp', reasons })
    } else {
      const until = parseMs(prefs.snooze_until)
      const base = parseMs(prefs.server_now) ?? now
      if (prefs.snooze_active === true && until !== null && until - base > SNOOZE_LONG_MS) {
        out.push({ key: 'PROFILE_PUSH_SNOOZED', category: 'profile', code: 'PROFILE_PUSH_SNOOZED', severity: important ? 'MEDIUM' : 'LOW',
          scope: 'pp', reasons, until: prefs.snooze_until })
      }
      if (c.onCall === true && String(prefs.min_level || '').toUpperCase() === 'CRITICAL') {
        out.push({ key: 'PROFILE_PUSH_CRITICAL_ONLY', category: 'profile', code: 'PROFILE_PUSH_CRITICAL_ONLY', severity: 'MEDIUM',
          scope: 'pp', reasons: ['ONCALL'] })
      }
    }
  }
  return out
}

/**
 * "Kime sormalı" (yönetici olmayan için): birincil takımın lideri — takım dizininden (`/api/teams/directory`). Lider kişinin
 * kendisiyse ya da yoksa `null` (genel metin). `myUserId` ve `leader_id` aynı biçimde gelir (opak ya da sayı) — dize olarak
 * karşılaştırılır.
 */
export function askWho(directory, primaryTeamId, myUserId) {
  if (!Array.isArray(directory) || primaryTeamId == null) return null
  const team = directory.find((t) => isObj(t) && String(t.id) === String(primaryTeamId))
  if (!team) return null
  const name = typeof team.leader_display_name === 'string' ? team.leader_display_name.trim() : ''
  if (!name) return null
  if (myUserId != null && team.leader_id != null && String(team.leader_id) === String(myUserId)) return null
  return { leaderName: name, teamName: typeof team.name === 'string' ? team.name : null }
}
