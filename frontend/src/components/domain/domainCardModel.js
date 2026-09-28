/**
 * Alan Adı (Domain) Süre Bitişi izleme KARTI modeli — saf yardımcılar (React yok; kart, sayfa ve testler paylaşır).
 *
 * <p>Satır alanları (GET /monitoring/domain → MonitoringController.enrichDomain, snake_case): kimlik `id`, `name`,
 * `domain` (kayıtlı alan adı, eTLD+1; IDN punycode), `team_id`/`team_name`, `group_name`, `tags`, `active`,
 * `warning_days`/`critical_days` (izlemenin kendi eşikleri), açık alarm `active_alarm` + `alarm_level` +
 * `alarm_acknowledged`, yenileme planı `renewal_planned_at` (yyyy-MM-dd) + `renewal_planned_by` + `renewal_planned_note` +
 * `renewal_overdue`; son kontrol: `status` OK|WARNING|CRITICAL|UNKNOWN, `source` RDAP|RDAP_REGISTRY|WHOIS|NONE,
 * `whois_provider`, `days_remaining`, `expiry_date` (WHOIS'te yalnız tarih, RDAP'ta zaman damgası), `registration_date`,
 * `last_changed`, `registrar` + `registrar_iana_id`, `dnssec` signed|unsigned, `status_codes` (EPP), `nameservers`,
 * `ns_resolves`, `transfer_lock` BOTH|SERVER|CLIENT|NONE|UNKNOWN, `blacklist_status` LISTED|CLEAN|UNKNOWN|SKIPPED +
 * `blacklist_detail`, `changed` + `change_detail`, `error`, `checked_at`.
 *
 * <p>Satırda OLMAYANLAR (kart uydurmaz — API boşlukları): kayıt sahibi (registrant), registrar'daki otomatik yenileme
 * ayarı (yalnız EPP `autoRenewPeriod` görünür), ayrı bir "kilit" alanı dışındaki registrar kilitleri (EPP kodlarından
 * okunur), WHOIS/RDAP ham yanıtı.
 *
 * <p><b>Eşikler UYDURULMAZ.</b> Kalan gün tonu sayfanın bugünkü {@link daysTone} kovalarıdır (dolmuş · ≤ 7 · ≤ 30);
 * durum rozeti sunucunun `status` hükmüdür ({@link statusKey}); "Yenileme planla" kısayolu izlemenin kendi uyarı
 * eşiğini (`warning_days`, yoksa sayfanın 30 günlük "yakında" süzgeci) kullanır.
 */
import { domainLife, eppKey, eppLabel } from '../../utils/domainEpp.js'
import { localDayKey } from '../../utils/localDay.js'
import { dateLocale } from '../../i18n/dateLocale.js'

/** Sayfanın "yakında dolacak" süzgeci / istatistik kartı ve kartın plan kısayolu için TEK kaynak (gün). */
export const SOON_DAYS = 30

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Bitiş tarihi gösterimi — hem WHOIS date-only ("2029-10-26") hem RDAP datetime ("...Z") güvenli. */
export function fmtExpiry(iso) {
  if (!iso) return '—'
  const s = String(iso).length <= 10 ? iso + 'T00:00:00Z' : (iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z')
  const d = new Date(s)
  return isNaN(d.getTime()) ? String(iso).substring(0, 10)
    : d.toLocaleDateString(dateLocale(), { year: 'numeric', month: '2-digit', day: '2-digit' })
}

/**
 * Bitişin YEREL gün anahtarı ('yyyy-MM-dd') — kartta görünen tarihle ({@link fmtExpiry}) aynı gün. RDAP zaman damgası
 * UTC'dir ("2027-03-15T23:59:59Z" = İstanbul'da 16 Mart); `.substring(0, 10)` bir gün erken düşerdi.
 */
export function expiryKey(m) {
  return m?.expiry_date ? localDayKey(String(m.expiry_date)) : null
}

/** .tr WHOIS kaynak anahtarı → okunur etiket (cevabı hangi kaynak verdi). */
export const WHOIS_PROVIDER_LABEL = { isimtescil: 'isimtescil.net', trabis: 'trabis.gov.tr', trabis43: 'whois:43' }

/** Kaynak rozeti metni: WHOIS ise ve sağlayıcı biliniyorsa "WHOIS · isimtescil.net", değilse ham kaynak. */
export function sourceTag(source, provider) {
  if (!source) return null
  const p = provider && WHOIS_PROVIDER_LABEL[provider]
  return (source === 'WHOIS' && p) ? `WHOIS · ${p}` : source
}

/** Kartın kaynak etiketi — "NONE" (hiçbir kaynak yanıt vermedi) etiket olarak yazılmaz; kahraman panel bunu anlatır. */
export function cardSourceTag(m) {
  return m?.source && m.source !== 'NONE' ? sourceTag(m.source, m.whois_provider) : null
}

/** Kaynak ailesi — açıklama metnini seçer: rdap | whois | null. */
export function sourceFamily(source) {
  if (!source || source === 'NONE') return null
  return String(source).toUpperCase().startsWith('RDAP') ? 'rdap' : String(source).toUpperCase() === 'WHOIS' ? 'whois' : null
}

/** Kalan gün tonu (eski satır içi daysColor hex'leri) — geçmiş / kritik / uyarı / normal. */
export function daysTone(d) {
  if (d == null) return 'text-muted-foreground'
  if (d < 0) return 'text-red-700 dark:text-red-400'
  if (d <= 7) return 'text-destructive'
  if (d <= 30) return 'text-orange-600 dark:text-orange-400'
  return 'text-foreground'
}

/** Kahraman panelin tonu — {@link daysTone} ile AYNI kovalar: unknown | expired | critical | warning | ok. */
export function expiryTone(days) {
  const d = num(days)
  if (d == null) return 'unknown'
  if (d < 0) return 'expired'
  if (d <= 7) return 'critical'
  if (d <= 30) return 'warning'
  return 'ok'
}

/** Sunucu durumu → paylaşılan kart sözlüğü (up | warn | down | unknown). */
export function statusKey(s) {
  if (s === 'OK') return 'up'
  if (s === 'WARNING') return 'warn'
  if (s === 'CRITICAL') return 'down'
  return 'unknown'
}

/**
 * Alarm seviyesi durumla AYNIYSA (Kritik durum + Kritik alarm) ayrı alarm rozeti yeni bilgi taşımaz — alarm işareti
 * durum rozetinin içine katlanır (sayfanın `cardStatusBadge`'i); farklıysa ayrı rozet kalır.
 */
export function alarmMatchesStatus(m) {
  return !!(m?.active_alarm && m.alarm_level && m.alarm_level === m.status)
}

/**
 * Kayıt dönemi: `{ total, elapsed, remaining, pct, start }` — başlangıç son güncelleme (çoğunlukla son yenileme; yoksa
 * oluşturma), bitiş `expiry_date` (bkz. utils/domainEpp.domainLife). KALAN gün sunucunun `days_remaining`'inden
 * (0..total'a kırpılır) — büyük sayı ile çubuğun altındaki metin aynı günü söyler. Tarihler yoksa null.
 */
export function lifeOf(m, now = Date.now()) {
  const start = m?.last_changed || m?.registration_date || null
  const life = domainLife(start, m?.expiry_date, now)
  if (!life) return null
  const d = num(m?.days_remaining)
  const remaining = d == null ? Math.max(0, life.total - life.elapsed) : Math.max(0, Math.min(life.total, d))
  return { ...life, remaining, start }
}

/** Transfer kilidi: `{ state, tone }` — BOTH/SERVER/CLIENT = ok, NONE = bad, diğerleri (UNKNOWN/null) = muted. */
export function lockOf(m) {
  const raw = String(m?.transfer_lock || '').toUpperCase()
  const state = ['BOTH', 'SERVER', 'CLIENT', 'NONE'].includes(raw) ? raw : 'UNKNOWN'
  return { state, tone: state === 'NONE' ? 'bad' : state === 'UNKNOWN' ? 'muted' : 'ok' }
}

/** DNSSEC: 'signed' | 'unsigned' | null (bilinmiyor → rozet çizilmez; kayıt sekmesi nedenini anlatır). */
export function dnssecOf(m) {
  const v = String(m?.dnssec || '').toLowerCase()
  return v === 'signed' || v === 'unsigned' ? v : null
}

/**
 * Kara liste: izleme kapalıysa (SKIPPED) ya da alan yoksa null — kapalı bir şeyi "temiz" göstermek yanlış iddia olur.
 * `{ status, tone, lists }` — LISTED = bad (liste adları `blacklist_detail` satırları), CLEAN = ok, UNKNOWN = muted.
 */
export function blacklistOf(m) {
  const s = String(m?.blacklist_status || '').toUpperCase()
  if (!s || s === 'SKIPPED') return null
  // Sunucu kanıtı `;` ile ayırır ("zen.spamhaus.org=192.0.2.1 → 127.0.0.2; bl.spamcop.net=…" — DnsblCheckerService);
  // yalnız satır sonuyla bölmek üç listeyi "1 liste" sayıyordu (2026-09-28, detay penceresinde görüldü). Satır sonu da ayraç.
  const lists = String(m?.blacklist_detail || '').split(/;|\r?\n/).map((x) => x.trim()).filter(Boolean)
  if (s === 'LISTED') return { status: s, tone: 'bad', lists }
  if (s === 'CLEAN') return { status: s, tone: 'ok', lists: [] }
  return { status: 'UNKNOWN', tone: 'muted', lists: [] }
}

/** Ad sunucuları: `{ list, count, resolves }` — `resolves` null = ölçülmedi (bilinmeyen "çözülmüyor" sayılmaz). */
export function nsOf(m) {
  const list = (Array.isArray(m?.nameservers) ? m.nameservers : String(m?.nameservers || '').split(','))
    .map((s) => String(s).trim()).filter(Boolean)
  const resolves = m?.ns_resolves === true ? true : m?.ns_resolves === false ? false : null
  return { list, count: list.length, resolves }
}

// Sunucunun EPP sınıflaması ile AYNI kümeler (DomainCheckerService.EPP_CRITICAL / EPP_WARN): kritik kod durumu CRITICAL,
// uyarı kodu WARNING yapar. Kart yalnız ÇİPİ tonlar ve önce gösterir — hüküm yine sunucunun `status`'ü.
const EPP_CRITICAL = new Set(['redemptionperiod', 'pendingdelete', 'serverhold', 'clienthold'])
const EPP_WARN = new Set(['autorenewperiod', 'pendingrenew'])

/** EPP kodu tonu: bad (kritik) | warn (uyarı) | info (bilgi — kilitler vb.). */
export function eppTone(code) {
  const k = eppKey(code)
  return EPP_CRITICAL.has(k) ? 'bad' : EPP_WARN.has(k) ? 'warn' : 'info'
}

const TONE_RANK = { bad: 0, warn: 1, info: 2 }

/**
 * EPP çipleri: `{ shown, rest }` — önem sırasıyla (kritik → uyarı → bilgi; aynı tonda sunucunun sırası korunur) ilk
 * `max` kod çip, kalanı "+N". "client hold" gibi alan adını DNS'ten düşüren bir kod "+N"in arkasına saklanmaz.
 * Öğe: `{ code, key, label, tone }`.
 */
export function eppChips(codes, max = 4) {
  const list = (Array.isArray(codes) ? codes : []).map((c) => String(c ?? '').trim()).filter(Boolean)
    .map((code, i) => ({ code, key: eppKey(code), label: eppLabel(code), tone: eppTone(code), i }))
    .sort((a, b) => (TONE_RANK[a.tone] - TONE_RANK[b.tone]) || (a.i - b.i))
    .map((x) => ({ code: x.code, key: x.key, label: x.label, tone: x.tone }))
  return { shown: list.slice(0, max), rest: list.slice(max) }
}

/**
 * Yenileme planı çipi: `{ kind: 'plan', state, at, by, note }` (plan var; state planned | overdue), `{ kind: 'cta' }`
 * ("Yenileme planla" kısayolu) ya da null. Kısayol yalnız plan yokken, plan eylemi verildiyse (`canPlan` — izlemeyi
 * yönetebilen) ve kalan gün izlemenin UYARI eşiğinin (`warning_days`; yoksa {@link SOON_DAYS}) içindeyse ya da
 * dolmuşsa. Bitişi bilinmeyen kartta kısayol yok (neyin planlandığı belli değil).
 */
export function planChipOf(m, canPlan) {
  if (m?.renewal_planned_at) {
    return {
      kind: 'plan', state: m.renewal_overdue ? 'overdue' : 'planned', at: m.renewal_planned_at,
      by: m.renewal_planned_by || null, note: m.renewal_planned_note || null,
    }
  }
  const d = num(m?.days_remaining)
  const limit = num(m?.warning_days) > 0 ? num(m.warning_days) : SOON_DAYS
  return canPlan && d != null && d <= limit ? { kind: 'cta' } : null
}

const firstLine = (s) => String(s ?? '').split(/\r?\n/)[0].trim()

/**
 * Bitişi bilinmeyen kartın NEDENİ: `{ kind: 'never' }` (hiç kontrol yok), `{ kind: 'error', detail }` (sorgu hatası —
 * ilk satır), `{ kind: 'nodata', source }` (kaynak yanıt verdi ama bitiş tarihi yok; `source` null = hiçbir kaynak).
 */
export function unknownReasonOf(m) {
  if (!m?.checked_at) return { kind: 'never' }
  const err = firstLine(m.error)
  if (err) return { kind: 'error', detail: err }
  return { kind: 'nodata', source: cardSourceTag(m) }
}
