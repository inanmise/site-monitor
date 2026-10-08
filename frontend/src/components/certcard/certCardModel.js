import { toUtc } from '../../utils/localDay.js'
import { dateLocale } from '../../i18n/dateLocale.js'
import {
  chainState, intermediateDays, isDeploymentGap, isNameMismatch, isRevoked, isUntrusted,
} from '../../pages/warnings/warningsModel.js'

/**
 * Pano (Genel Bakış) / Uyarılar SERTİFİKA KARTI — saf model (2026-09-27 yeniden tasarım). React yok: ton, geçerlilik
 * süresi, anahtar/imza özeti, güven/zincir gerekçeleri ve yenileme planı burada; kart ve testler aynı sözlüğü paylaşır.
 *
 * <p><b>Eşikler UYDURULMAZ.</b> Ton sunucunun `alert_level`'ından gelir (CertificateService.computeAlertLevel: hata >
 * dolmuş > ≤ kritik gün > ≤ yüksek gün > uyarı); yalnız eski önbellek satırında (alan yoksa) kartın eski düşüşü
 * uygulanır (≤ 7 kritik, ≤ 15 yüksek, `warning` bayrağı). Güven/zincir gerekçeleri Uyarılar sayfasının modelinden
 * (`pages/warnings/warningsModel.js`) — iki yüzey aynı kusura aynı adı verir.
 */

const DAY_MS = 86400000

/** Kart tonu — `valid | warning | high | critical | expired | error` (kökte `data-status`). */
export const TONES = ['valid', 'warning', 'high', 'critical', 'expired', 'error']

const hasDays = (d) => d !== null && d !== undefined && Number.isFinite(Number(d))

/**
 * Pasif (izlemesi durdurulmuş) kart tonu (2026-10-08). Sunucu `paused: true` yalnız `/certificates/paused` satırına
 * yazar; kart kırmızı/sarı tonla dikkat çekmez — bilgi son kontrolden, bayat olabilir. Durum/kalan gün metni yine son
 * kontrolün tonundan ({@link baseTone}) okunur.
 */
export const PAUSED_TONE = 'paused'
export const isPausedCert = (cert) => cert?.paused === true

/** Kart tonu: pasif kayıt `paused`; aksi hâlde {@link baseTone}. */
export function certTone(cert) {
  return isPausedCert(cert) ? PAUSED_TONE : baseTone(cert)
}

/** Sunucu hükmü (`alert_level`) varsa o; yoksa kartın eski düşüşü (bkz. dosya başı). Pasif kartta son kontrolün tonu. */
export function baseTone(cert) {
  const c = cert || {}
  const al = c.alert_level
  if (al) return TONES.includes(al) ? al : 'valid'
  const d = c.days_remaining
  if (c.status === 'error') return 'error'
  if (hasDays(d) && d < 0) return 'expired'
  if (hasDays(d) && d <= 7) return 'critical'
  if (hasDays(d) && d <= 15) return 'high'
  if (c.warning === true) return 'warning'
  return 'valid'
}

/** Durum rozetinin i18n anahtarı (sözcükle durum). */
export const TONE_LABEL = {
  valid: 'card.valid', warning: 'card.warning', high: 'card.high', critical: 'card.critical',
  expired: 'tbl.statusExpired', error: 'card.error', paused: 'certcard.paused',
}

/**
 * Yalnız tarih ("02/10/2026") — `api/client` formatDateOnly ile AYNI çıktı; burada ayrıca durur çünkü istemci modülünü
 * tümüyle mock'layan App düzeyi testler (DashboardPlatformFilter) o yardımcıyı taşımıyor ve kart çökmemeli.
 */
export function dateOnly(iso) {
  if (!iso) return '—'
  try {
    return new Date(toUtc(iso)).toLocaleDateString(dateLocale(), { year: 'numeric', month: '2-digit', day: '2-digit' })
  } catch {
    return String(iso).substring(0, 10)
  }
}

function ms(iso) {
  if (!iso) return null
  const v = Date.parse(toUtc(iso))
  return Number.isFinite(v) ? v : null
}

/**
 * Geçerlilik süresi: `{ total, remaining, pct }` — toplam gün (not_before → not_after), KALAN gün (0..total) ve kalan
 * yüzde. Başlangıç/bitiş yoksa ya da kalan gün bilinmiyorsa null (çubuk çizilmez).
 */
export function validityOf(cert) {
  const start = ms(cert?.not_before)
  const end = ms(cert?.not_after)
  const d = cert?.days_remaining
  if (start == null || end == null || end <= start || !hasDays(d)) return null
  const total = Math.max(1, Math.round((end - start) / DAY_MS))
  const remaining = Math.max(0, Math.min(total, Number(d)))
  return { total, remaining, pct: Math.round((remaining / total) * 100) }
}

/** Son 30 gün içinde başlamış sertifika = yeni yenilenmiş → kaç gün önce (hata kartında yok). */
export function renewedDaysAgo(cert, tone, now = Date.now()) {
  if (tone === 'error') return null
  const start = ms(cert?.not_before)
  if (start == null) return null
  const age = now - start
  return age >= 0 && age <= 30 * DAY_MS ? Math.floor(age / DAY_MS) : null
}

/** DN içinden bir alan (örn. O=...). */
export function parseDn(dn, field) {
  const m = typeof dn === 'string' ? dn.match(new RegExp(`(?:^|,)\\s*${field}=([^,]+)`)) : null
  return m ? m[1].trim() : null
}

/** Veren (CA) adı: DN'deki kuruluş (O), yoksa CA CN'i; hiçbiri yoksa null. */
export function issuerNameOf(cert) {
  return parseDn(cert?.issuer_dn, 'O') || cert?.issuer_cn || cert?.issuer || null
}

/** Anahtar özeti: "RSA 2048", "EC 256"; algoritma yoksa null. */
export function keyLabelOf(cert) {
  const alg = cert?.public_key_algorithm
  if (!alg) return null
  return `${alg}${cert.public_key_size ? ` ${cert.public_key_size}` : ''}`
}

/** İmza algoritmasının kısa adı: "SHA256withRSA" → "SHA-256", "SHA1withRSA" → "SHA-1"; tanınmayan ad olduğu gibi. */
export function sigShort(sig) {
  if (!sig) return null
  const s = String(sig)
  const m = /^SHA-?(\d+)/i.exec(s)
  if (m) return `SHA-${m[1]}`
  if (/^MD5/i.test(s)) return 'MD5'
  return s
}

/**
 * Güven / zincir gerekçeleri — önem sırasıyla `{ key, tone, hint, n? }`. `key` Uyarılar sayfasının `attn.r.*` metnine,
 * `hint` dokunmatikte de açılan açıklamanın anahtarına gider. `isWeak`: zayıf-algoritma verisi (undefined = bilinmiyor
 * → gerekçe yok; yokluk "güçlü" demek değildir). Süre gerekçeleri (dolmuş, N gün) burada YOK — onları kahraman panel
 * ve durum rozeti söylüyor.
 */
export function reasonsOf(cert, isWeak) {
  const out = []
  if (!cert) return out
  if (isRevoked(cert)) out.push({ key: 'revoked', tone: 'bad', hint: 'hlth.row.revocation.desc' })
  if (isUntrusted(cert)) out.push({ key: 'untrusted', tone: 'bad', hint: 'hlth.row.trust.desc' })
  if (isNameMismatch(cert)) out.push({ key: 'hostname', tone: 'bad', hint: 'hlth.row.sanMatch.desc' })
  const ch = chainState(cert)
  if (ch) out.push({ key: ch === 'BROKEN' ? 'chainBroken' : 'chainIncomplete', tone: 'high', hint: 'hlth.row.chain.desc' })
  if (isDeploymentGap(cert)) out.push({ key: 'deployment', tone: 'high', hint: 'certcard.hint.deployment' })
  const id = intermediateDays(cert)
  if (id != null) out.push({ key: 'intermediate', tone: 'warn', hint: 'hlth.row.intermediate.desc', n: Math.max(0, id) })
  if (isWeak === true) out.push({ key: 'weak', tone: 'weak', hint: 'certcard.hint.weak' })
  return out
}

/**
 * Zengin görünümdeki sağlık bulgusu → aynı kusuru anlatan gerekçe anahtarları. Gerekçe çipi zaten görünüyorsa bulgu
 * çipi tekrar çizilmez (aynı kartta iki "Zincir kırık" olmasın).
 */
export const FINDING_REASON = {
  revocation: ['revoked'], trust: ['untrusted'], sanMatch: ['hostname'], chain: ['chainBroken', 'chainIncomplete'],
  intermediate: ['intermediate'], signature: ['weak'], keySize: ['weak'],
}

/** Standart dışı port (443 değil) → gösterilecek port; standart ya da yoksa null. */
export function nonStandardPort(cert) {
  const p = Number(cert?.port)
  return Number.isInteger(p) && p > 0 && p !== 443 ? p : null
}

/**
 * Yenileme planı çipi: `{ kind: 'plan', plan }` (plan var), `{ kind: 'cta' }` ("Yenileme planla" kısayolu) ya da null.
 * `known`: plan verisi bu kart için yüklendi mi (bilinmiyorsa kısayol gösterilmez — var olan planın üstüne boş form
 * açtırmasın). Kısayol yalnız ≤ 30 gün kalan (ya da dolmuş) sertifikada ve plan eylemi verildiyse.
 */
export function planChipOf(cert, plan, known, canPlan) {
  if (plan && plan.planned_at) return { kind: 'plan', plan }
  const d = cert?.days_remaining
  if (known && canPlan && certTone(cert) !== 'error' && hasDays(d) && d <= 30) return { kind: 'cta' }
  return null
}

/** Plan çipinin durumu: done | overdue | planned. */
export const planState = (plan) => (plan?.done ? 'done' : plan?.overdue ? 'overdue' : 'planned')
