/**
 * HTTP/Website kontrol hatası tanısı — `http_checks.error_detail` JSON'unun okunması (2026-09-22).
 *
 * Backend (`HttpFailureDiagnostics`) yalnız BAŞARISIZ satıra yapısal tanı yazar: evre (POLICY/DNS/CONNECT/
 * TLS/REQUEST/RESPONSE/REDIRECT), tür (CONNECT_TIMEOUT, DNS_UNRESOLVED, …), kaynak/hedef IP:port, çözümlenen
 * IP'ler, vekil, zaman aşımı/bekleme, yönlendirmeler, istisna zinciri. Burası saf: JSON'u güvenle çözer ve
 * evre şeridinin durumlarını türetir. Eski (tanısız) satırlar null döner — arayüz bunu açıkça söyler.
 */

/** Şeritte gösterilen evreler, sırayla. POLICY (SSRF/yapılandırma) ve REDIRECT şeride girmez: ilki isteğin
 *  hiç atılmadığı bir ön kapıdır, ikincisi RESPONSE evresinde yaşanır (zincir ayrıca listelenir). */
export const STRIP_PHASES = ['DNS', 'CONNECT', 'TLS', 'REQUEST', 'RESPONSE']

/** Bir kontrol satırının tanısını çözer. Yoksa/bozuksa null. */
export function parseHttpDiag(check) {
  const raw = check?.error_detail
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  if (typeof raw !== 'string' || !raw.trim()) return null
  try {
    const d = JSON.parse(raw)
    return d && typeof d === 'object' ? d : null
  } catch {
    return null
  }
}

/** Türün şeritteki evresi. TOO_MANY_REDIRECTS RESPONSE'ta, politika türleri şeride düşmez (null). */
export function stripPhaseOf(detail) {
  const p = detail?.phase
  if (!p) return null
  if (p === 'REDIRECT') return 'RESPONSE'
  if (p === 'POLICY') return null
  return STRIP_PHASES.includes(p) ? p : null
}

/**
 * Şerit durumları: takılan evre 'stuck', öncekiler 'done', sonrakiler 'skipped'. TLS yalnız https'te anlamlı —
 * http hedefte 'na'. Politika hatasında (istek hiç atılmadı) hepsi 'skipped'.
 */
export function phaseStates(detail) {
  const stuck = stripPhaseOf(detail)
  const https = String(detail?.scheme || '').toLowerCase() === 'https'
  const out = {}
  let passed = stuck != null
  for (const ph of STRIP_PHASES) {
    if (ph === 'TLS' && !https) { out[ph] = 'na'; continue }
    if (stuck == null) { out[ph] = 'skipped'; continue }
    if (ph === stuck) { out[ph] = 'stuck'; passed = false; continue }
    out[ph] = passed ? 'done' : 'skipped'
  }
  return out
}

/** "kaynak → hedef:port" satırı için parçalar; bilinmeyenler '—'. Vekil yolunda TCP hedefi vekildir. */
export function routeOf(detail) {
  if (!detail) return null
  const viaProxy = detail.via === 'proxy' && detail.proxy
  return {
    from: detail.local_ip || '—',
    to: viaProxy ? detail.proxy : (detail.target_ip || detail.host || '—'),
    port: viaProxy ? null : (detail.port ?? null),
    viaProxy: !!viaProxy,
    behind: viaProxy ? `${detail.host || '—'}:${detail.port ?? '—'}` : null,
  }
}

/** Bekleme/zaman aşımı ilişkisi: zaman aşımına dayandı mı (bekleme ≥ ayarın %90'ı)? */
export function hitTimeout(detail) {
  const e = Number(detail?.elapsed_ms), t = Number(detail?.timeout_ms)
  if (!Number.isFinite(e) || !Number.isFinite(t) || t <= 0) return false
  return e >= t * 0.9
}
