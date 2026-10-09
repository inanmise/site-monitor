import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { daysFromToday } from '../../utils/localDay.js'

/**
 * Envanter kaydı detay görünümünün SAF modeli (2026-09-28 yeniden tasarım): bağlantı güvenliği, e-posta ayrıştırma,
 * eksik alan (hijyen) çıkarımı, etiket anahtarları. React yok — vitest'te tek başına sınanır. Sunum
 * `InventoryDetailParts.jsx` + `InventoryDetails.jsx`'te.
 */

/** Serbest metin içindeki e-posta belirteci — uyarı e-postası şablonundaki EMAIL_IN_TEXT ile aynı gevşeklik. */
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/

/**
 * "Ad Soyad - ad.soyad@example.com" → { before: 'Ad Soyad - ', addr: 'ad.soyad@example.com', after: '' }.
 * Adres yoksa null (değer düz metin kalır — "Ad Soyad (izinde)" meşru bir değerdir).
 */
export function splitEmail(value) {
  const raw = String(value ?? '').trim()
  if (!raw) return null
  const m = raw.match(EMAIL_RE)
  if (!m) return null
  return { before: raw.slice(0, m.index), addr: m[0], after: raw.slice(m.index + m[0].length) }
}

/**
 * Yalnız http(s) bağlantısı üretir: şema http/https değilse (javascript:, data:, vbscript:, ftp:, göreli yol…) null.
 * `URL` ayrıştırıcısı büyük/küçük harf, baştaki boşluk ve kaçış hilelerini (`JaVaScRiPt:`, `java\tscript:`) normalize
 * ettiği için protokol karşılaştırması ayrıştırılmış nesne üstünden yapılır — dize önekine BAKILMAZ.
 */
export function safeHttpUrl(value) {
  const s = String(value ?? '').trim()
  if (!s) return null
  try {
    const u = new URL(s)
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.href : null
  } catch {
    return null
  }
}

// Metin içinde yalnız açık şemalı http(s) adresleri aranır; şemasız "www.x" bilinçli olarak bağlantı olmaz
// (e-posta şablonundaki endpointLink kuralı: şemasız host linklenmez).
const URL_IN_TEXT = /https?:\/\/[^\s<>"'`]+/gi
// Cümle sonu noktalama bağlantıya dahil edilmez: "bkz. https://wiki.example.com/x." → nokta metin kalır.
// Sondan geriye tek geçiş (2026-10-09): eski `/[.,;:!?)\]}'"]+$/` ifadesi ortasında uzun noktalama dizisi olan bir
// adreste O(N²) geri izliyordu (açıklamayı açan her kullanıcının sekmesi donardı). Sonuç aynı: en uzun noktalama soneki.
const TRAILING_PUNCT = new Set(['.', ',', ';', ':', '!', '?', ')', ']', '}', "'", '"'])
const stripTrailingPunct = (s) => {
  let e = s.length
  while (e > 0 && TRAILING_PUNCT.has(s[e - 1])) e--
  return e === s.length ? s : s.slice(0, e)
}

/**
 * Serbest metni düz metin ve güvenli bağlantı parçalarına böler. Bağlantısız metin TEK bir metin parçası döner
 * (çağıran onu olduğu gibi çizer — metin sorguları bölünmez).
 * @returns {Array<{type:'text', value:string} | {type:'url', value:string, href:string}>}
 */
export function splitLinks(text) {
  const src = String(text ?? '')
  const out = []
  let last = 0
  for (const m of src.matchAll(URL_IN_TEXT)) {
    const value = stripTrailingPunct(m[0])
    const href = safeHttpUrl(value)
    if (!href) continue
    if (m.index > last) out.push({ type: 'text', value: src.slice(last, m.index) })
    out.push({ type: 'url', value, href })
    last = m.index + value.length
  }
  if (last < src.length) out.push({ type: 'text', value: src.slice(last) })
  if (out.length === 0) out.push({ type: 'text', value: src })
  return out
}

/**
 * Kaydın sitesi: `https://<alan>[:port]/`. Joker (`*.example.com`) ya da ayrıştırılamayan alan için null — tıklanınca
 * bir yere varmayacak bağlantı çizilmez.
 */
export function siteUrl(record) {
  const d = String(record?.domain ?? '').trim()
  if (!d || d.includes('*') || /[\s/\\@]/.test(d)) return null
  const port = Number(record?.port) || 443
  return safeHttpUrl(`https://${d}${port !== 443 ? `:${port}` : ''}/`)
}

/** Dolu sorumlu alanları (CONTACT_FIELDS sırası). */
export function filledContactFields(record) {
  return CONTACT_FIELDS.filter(({ key }) => String(record?.[key] ?? '').trim() !== '')
}

/** Açık / kapalı operasyonel bayraklar (INVENTORY_FLAGS sırası — 13'ü de her zaman bu iki listeden birinde). */
export function splitFlags(record) {
  const on = []
  const off = []
  for (const f of INVENTORY_FLAGS) (record?.[f.key] ? on : off).push(f)
  return { on, off }
}

/**
 * Kayıttan okunabilen eksik alanlar — Envanter sayfasının hijyen bandı (InventoryHygieneBand) ile AYNI kodlar ve
 * etiketler (`inv.hy.<kod>`): takım, kritiklik, sorumlular. Platform hijyen raporunda kod değil ama özet kartında
 * "Platformsuz" sayılır; burada `no_platform` olarak eklenir. Kontrol verisine bağlı kodlar (hata, bayat, dolmuş…)
 * bu uçta yok — onlar Sertifika sekmelerinde.
 */
export function missingFields(record) {
  if (!record) return []
  const out = []
  if (record.team_id == null) out.push('no_team')
  if (record.tier == null || record.tier === '') out.push('no_tier')
  if (filledContactFields(record).length === 0) out.push('no_contacts')
  if (!record.platform) out.push('no_platform')
  return out
}

/** Eksik alan kodunun etiket anahtarı. */
export const MISSING_LABEL_KEY = {
  no_team: 'inv.hy.no_team',
  no_tier: 'inv.hy.no_tier',
  no_contacts: 'inv.hy.no_contacts',
  no_platform: 'inv.det.hyNoPlatform',
}

/** Sorumlu doluluk tonu — tablo hücresiyle (ContactsCell) aynı eşik: hiç yok kırmızı, kısmi amber, tam yeşil. */
export function contactsTone(n, total = CONTACT_FIELDS.length) {
  return n === 0 ? 'bad' : n < total ? 'warn' : 'ok'
}

/** Kontrol sıklığı etiket anahtarı (form seçenekleriyle aynı); bilinmeyen/boş → genel zamanlama. */
export function intervalLabelKey(hours) {
  return ({ 1: 'inv.interval1h', 6: 'inv.interval6h', 12: 'inv.interval12h', 24: 'inv.interval24h', 168: 'inv.interval168h' })[hours]
    || 'inv.intervalInherit'
}

/** TLS kipi etiket anahtarı; boş → genel ayar. */
export function tlsModeLabelKey(mode) {
  return mode === 'browser' ? 'inv.tlsModeBrowser' : mode === 'default' ? 'inv.tlsModeDefault' : 'inv.tlsModeInherit'
}

/** "prod, web ,, api" → ['prod', 'web', 'api']. */
export function tagsOf(record) {
  return String(record?.tags ?? '').split(',').map((x) => x.trim()).filter(Boolean)
}

/**
 * "Bildirim grubu" satırı (2026-09-28): grup seçilmemiş → takım varsayılanı (`default`); sunucu adı çözdüyse ad (`named`);
 * kimlik var ama ad yok → `missing` (kimlik + "bulunamadı" açıklaması). Sunucu adı YALNIZ grup kayıt için gerçekten
 * uygulanıyorsa (aktif + kaydın takımına ait) ve çağıran onu okuyabiliyorsa yazar (AdminController
 * `applyNotificationGroupNames` — grup ucunun 404 deseni: başka takımın grubunun var olduğu bile söylenmez).
 */
export function notificationGroupView(record) {
  const id = record?.notification_group_id
  if (id == null || id === '') return { kind: 'default', id: null, name: null }
  const name = String(record?.notification_group_name ?? '').trim()
  return name ? { kind: 'named', id, name } : { kind: 'missing', id, name: null }
}

/** "Sertifika ve yenileme" bölümünde gösterilecek bir şey var mı (yoksa bölüm tümüyle gizlenir). */
export function hasRenewalInfo(record) {
  return !!(record?.expected_fingerprint || record?.expected_subject || record?.renewal_planned_at || record?.domain_expiry)
}

/**
 * Bir tarihe (YYYY-MM-DD ya da UTC ISO, `Z`siz) kalan gün; bozuk/boş → null. Geçmiş negatif. Yalnız tarih = YEREL takvim
 * günü farkı (bugün 0, yarın 1) — utils/localDay `daysFromToday` (Ek 3/1: eskiden UTC gece yarısından bir gün eksik).
 */
export function daysFromNow(iso, now = Date.now()) {
  return daysFromToday(iso, now)
}

/**
 * "Envanterde aç" derin bağlantısının parametreleri: Envanter ekranı `domain` ile kaydın panelini açar
 * (InventoryManager). Başka takımın salt okunur kaydı yalnız "tüm takımlar" kapsamında listelendiği için o durumda
 * `i_scope=all` da taşınır; sunucu org geneli görünürlük kapalıysa kapsamı kendisi `mine`'a düşürür.
 */
export function inventoryDeepLinkParams(record) {
  const params = { domain: record.domain }
  if (record.can_manage === false) params.i_scope = 'all'
  return params
}
