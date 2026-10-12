/**
 * Ağdan kontrol edilen sertifikanın hiyerarşi ÖZETİ — SAF model (2026-10-12, kullanıcı isteği: otomatik çekilen
 * sertifikalarda da tarayıcı gibi Root / Intermediate / Leaf + her birinin bitiş tarihi).
 *
 * <p>Girdi `GET /api/certificates/{domain}/hierarchy` yanıtının `data`'sıdır (NetworkCertificateHierarchy). Düğümler
 * certHierarchyModel ile çizilir; burada yalnız üstteki özet kartının alanları ve "yola girmeyen" sertifikalar
 * normalleştirilir. Kural yazılmaz: kök kaynağı, sıra ve fazladan sertifikalar sunucudan gelir. Bilinmeyen değer
 * "bulunamadı" ya da "doğru" SAYILMAZ — `rootSource: 'unknown'`, `orderOk: null`.
 */

import { parseDn } from './sslModel.js'

export const ROOT_SOURCES = Object.freeze(['trust_store', 'server', 'self_signed', 'missing'])

function num(v) { return typeof v === 'number' && Number.isFinite(v) ? v : null }
function str(v) { return typeof v === 'string' && v.trim() ? v.trim() : null }

/**
 * Kök kaynağı. Sunucu en üst düğümde verenin EKSİK olduğunu söylüyorsa (`issuer_missing: true`) her zaman "missing" —
 * başka bir alan ne derse desin (depodaki ara sertifika "kök" sayılmaz). "Bulunamadı" yalnız bu açık işaretle; alan eksik /
 * tanınmayan değer bilinmiyordur (boşluk "bulunamadı" sayılmaz).
 */
export function rootSourceOf(data) {
  if (!data || typeof data !== 'object') return 'unknown'
  if (data.issuer_missing === true) return 'missing'
  if (ROOT_SOURCES.includes(data.root_source) && data.root_source !== 'missing') return data.root_source
  return 'unknown'
}

/** Kök yoksa ne eksik: `intermediate` (sunucu ara sertifikayı göndermedi) · `anchor` (depodaki ara sertifikada bitiyor) · `root`. */
export function missingKindOf(data) {
  const k = data?.missing_kind
  return k === 'intermediate' || k === 'anchor' || k === 'root' ? k : (data?.issuer_missing === true ? 'root' : null)
}

/** Ad: DN'deki CN, yoksa O, yoksa tam DN (doğrusal ayrıştırıcı — sslModel.parseDn; düzenli ifade yok). */
export function dnLabel(dn) {
  const s = str(dn)
  if (!s) return ''
  const p = parseDn(s)
  return p.CN || p.O || s
}

/** Özet kartının alanları. */
export function hierarchySummary(data) {
  const d = data && typeof data === 'object' ? data : {}
  const extras = (Array.isArray(d.extra_certificates) ? d.extra_certificates : [])
    .filter((e) => e && typeof e === 'object')
    .map((e) => {
      const days = num(e.days_remaining)
      return {
        label: dnLabel(e.subject_dn) || '—',
        subjectDn: str(e.subject_dn),
        notAfter: str(e.not_after),
        days,
        expired: e.expired === true || (days != null && days < 0),
      }
    })
  return {
    presented: num(d.presented_count),
    rootSource: rootSourceOf(d),
    // Kök güven deposunda mı (sunucu gönderdi diye güvenilir SAYILMAZ); bilinmiyorsa null
    rootTrusted: typeof d.root_trusted === 'boolean' ? d.root_trusted : null,
    missingKind: missingKindOf(d),
    missingIssuer: dnLabel(d.missing_issuer_dn) || null,
    trustStore: d.trust_store === 'system' || d.trust_store === 'corporate' ? d.trust_store : null,
    orderOk: typeof d.order_ok === 'boolean' ? d.order_ok : null,
    viaProxy: d.via === 'proxy',
    port: num(d.port),
    checkedAt: str(d.checked_at),
    extras,
  }
}

/**
 * Kök durumu → rozet tonu. Yeşil YALNIZ kök güven deposunda olduğu biliniyorsa (`rootTrusted === true`): sunucunun
 * gönderdiği ama depoda olmayan kök ya da bilinmeyen güven sarı. Depodaki ara sertifikada biten zincir sarı, eksik kök /
 * ara sertifika kırmızı.
 */
export function rootSourceTone(summaryOrSource) {
  const s = typeof summaryOrSource === 'string' ? { rootSource: summaryOrSource } : (summaryOrSource || {})
  if (s.rootSource === 'missing') return s.missingKind === 'anchor' ? 'warning' : 'destructive'
  if (s.rootSource === 'self_signed') return 'warning'
  if (s.rootSource === 'unknown') return 'secondary'
  if (s.rootSource === 'trust_store') return 'success'
  if (s.rootSource === 'server') return s.rootTrusted === true ? 'success' : 'warning'
  return 'secondary'
}

/** Kök rozetinin metin anahtarı (`chier.net.root.*`). */
export function rootLabelKey(summary) {
  const s = summary?.rootSource
  if (s === 'missing') return summary.missingKind === 'intermediate' ? 'chier.net.root.missing_intermediate'
    : summary.missingKind === 'anchor' ? 'chier.net.root.anchor' : 'chier.net.root.missing'
  if (s === 'server' && summary.rootTrusted !== true) return 'chier.net.root.server_untrusted'
  return `chier.net.root.${ROOT_SOURCES.includes(s) ? s : 'unknown'}`
}

/** Kök durumunun açıklama anahtarı (`chier.net.why.*`). */
export function rootWhyKey(summary) {
  const s = summary?.rootSource
  if (s === 'trust_store') return summary.trustStore === 'corporate' ? 'chier.net.why.corporate' : 'chier.net.why.system'
  if (s === 'server') return summary.rootTrusted === true ? 'chier.net.why.server' : 'chier.net.why.serverUntrusted'
  if (s === 'self_signed') return 'chier.net.why.self_signed'
  if (s === 'missing') return summary.missingKind === 'intermediate' ? 'chier.net.why.missingIntermediate'
    : summary.missingKind === 'anchor' ? 'chier.net.why.anchor' : 'chier.net.why.missing'
  return null
}

/** Ağacın üstündeki "kök yok" yer tutucusunun anahtarı. */
export function missingPlaceholderKey(summary) {
  if (summary?.missingKind === 'intermediate') return 'chier.net.intermediateMissing'
  if (summary?.missingKind === 'anchor') return 'chier.net.anchorTop'
  return 'chier.net.rootMissing'
}
