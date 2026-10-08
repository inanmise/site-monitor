/**
 * HSTS politikasının arayüz modeli (2026-10-08, kullanıcı: "HSTS çok bilinen bir konu değil — ne işe yarıyor, neden
 * missing, eklemezse ne olur, preload / includeSubDomains gibi parametreler var mı, eklenmeli mi?").
 *
 * <p>Girdi: Sağlık satırının `evidence.hsts_policy`'si (backend `CertificateAppLayerProbe.policyJson`):
 * `{ header, max_age, include_subdomains, preload, http_redirects_to_https? }`. SAF işlevler — metin kurmaz, i18n
 * anahtarı + argüman döner. Eşikler backend `CertificateHealthService.HSTS_MIN_MAX_AGE_SECONDS` ile AYNI.
 */

/** max-age alt sınırı: 180 gün (altı "kısa"). */
export const HSTS_MIN_SECONDS = 15_552_000
/** preload listesi şartı ve önerilen değer: 1 yıl. */
export const HSTS_ONE_YEAR = 31_536_000

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : (v != null && v !== '' && Number.isFinite(Number(v)) ? Number(v) : null))

/** Politikayı güvenli biçimde normalleştirir; yoksa null. */
export function normalizePolicy(p) {
  if (!p || typeof p !== 'object') return null
  const header = typeof p.header === 'string' && p.header.trim() ? p.header.trim() : null
  return {
    header,
    maxAge: num(p.max_age),
    includeSubDomains: p.include_subdomains === true,
    preload: p.preload === true,
    redirect: typeof p.http_redirects_to_https === 'boolean' ? p.http_redirects_to_https : null,
  }
}

/** max-age hükmü: missing (başlık yok/geçersiz) · off (0) · short (< 180 gün) · ok (180 gün – 1 yıl) · good (≥ 1 yıl). */
export function maxAgeVerdict(policy) {
  if (!policy?.header || policy.maxAge == null) return 'missing'
  if (policy.maxAge <= 0) return 'off'
  if (policy.maxAge < HSTS_MIN_SECONDS) return 'short'
  if (policy.maxAge < HSTS_ONE_YEAR) return 'ok'
  return 'good'
}

/** preload listesine kabul şartlarından EKSİK olanlar (anahtar listesi): maxAge · includeSub · redirect. */
export function preloadMissing(policy) {
  if (!policy) return []
  const out = []
  if (policy.maxAge == null || policy.maxAge < HSTS_ONE_YEAR) out.push('maxAge')
  if (!policy.includeSubDomains) out.push('includeSub')
  if (policy.redirect === false) out.push('redirect')
  return out
}

/**
 * "Ne yapmalı" önerileri — öncelik sırasıyla `{ key, args?, tone }`. tone: warn (düzeltilmeli) · info (değerlendirin) · ok.
 * Başlık hiç yoksa yalnız "ekleyin" (+ yönlendirme yoksa o) — diğer yönergeler henüz anlamsız.
 */
export function hstsAdvice(policy) {
  if (!policy) return []
  const v = maxAgeVerdict(policy)
  const out = []
  if (!policy.header) out.push({ key: 'hsts.adv.add', tone: 'warn' })
  else if (v === 'missing') out.push({ key: 'hsts.adv.invalid', tone: 'warn' })
  else if (v === 'off') out.push({ key: 'hsts.adv.disabled', tone: 'warn' })
  else if (v === 'short') out.push({ key: 'hsts.adv.raise', args: [Math.floor(policy.maxAge / 86_400)], tone: 'warn' })
  if (policy.redirect === false) out.push({ key: 'hsts.adv.redirect', tone: 'warn' })
  if (policy.header && v !== 'missing' && v !== 'off') {
    if (!policy.includeSubDomains) out.push({ key: 'hsts.adv.includeSub', tone: 'info' })
    const miss = preloadMissing(policy)
    if (policy.preload && miss.length) out.push({ key: 'hsts.adv.preloadIneligible', reqs: miss, tone: 'warn' })
    else if (!policy.preload) out.push({ key: 'hsts.adv.preloadOptional', tone: 'info' })
  }
  if (!out.some((a) => a.tone === 'warn') && policy.header && (v === 'good' || v === 'ok')) out.unshift({ key: 'hsts.adv.ok', tone: 'ok' })
  return out
}
