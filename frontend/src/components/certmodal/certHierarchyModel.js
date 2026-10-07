/**
 * Tarayıcı gibi sertifika HİYERARŞİSİ görünümünün SAF modeli (2026-10-07, kullanıcı isteği: elle yüklenen sertifikayı
 * Chrome / Firefox / Windows "Sertifika Hiyerarşisi" gibi kök → ara → yaprak alt alta görmek).
 *
 * <p>Girdi `GET /api/manual-certs/{id}/versions/{vid}/chain` yanıtının `nodes` dizisidir (snake_case, KÖK İLK —
 * ManualCertificateHierarchy). Model kural YAZMAZ: rol, "kök dosyada yok" işareti, kalan gün sunucudan gelir; burada
 * yalnız camelCase'e çevrilir, derinliğe göre sıralanır, açılışta seçilecek düğüm ve geçerlilik tonu bulunur.
 * Metinler bileşende `t()` ile kurulur (model `t` almaz).
 */

/** Konu / düzenleyen ad parçalarının gösterim sırası (tarayıcının "Details" bölmesi gibi). */
export const NAME_PARTS = Object.freeze(['cn', 'o', 'ou', 'l', 'st', 'c'])

const ROLES = new Set(['root', 'intermediate', 'leaf'])

function num(v) { return typeof v === 'number' && Number.isFinite(v) ? v : null }
function str(v) { return typeof v === 'string' && v.trim() ? v.trim() : null }
function list(v) { return Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim()) : [] }

function parts(p) {
  const src = p && typeof p === 'object' ? p : {}
  const out = {}
  for (const k of NAME_PARTS) out[k] = str(src[k])
  return out
}

/** Düğümün listedeki adı: CN → O → tam DN → boş (bileşen "—" yazar). */
export function nodeLabel(node) {
  return node?.subject?.cn || node?.subject?.o || node?.subjectDn || ''
}

/**
 * Sunucu düğümü → görünüm düğümü. Tanınmayan rol `intermediate` sayılır (kural sunucuda; burada yalnız güvenli varsayılan).
 */
export function normalizeNode(raw, index = 0) {
  const r = raw && typeof raw === 'object' ? raw : {}
  const days = num(r.days_remaining)
  const node = {
    role: ROLES.has(r.role) ? r.role : 'intermediate',
    depth: num(r.depth) ?? index,
    head: r.head === true,
    subject: parts(r.subject),
    issuer: parts(r.issuer),
    subjectDn: str(r.subject_dn),
    issuerDn: str(r.issuer_dn),
    serial: str(r.serial_number),
    notBefore: str(r.not_before),
    notAfter: str(r.not_after),
    days,
    expired: r.expired === true || (days != null && days < 0),
    notYetValid: r.not_yet_valid === true,
    sigAlg: str(r.signature_algorithm),
    keyAlg: str(r.public_key_algorithm),
    keySize: num(r.public_key_size) > 0 ? r.public_key_size : null,
    san: list(r.san),
    keyUsage: list(r.key_usage),
    extKeyUsage: list(r.ext_key_usage),
    isCa: r.is_ca === true,
    pathLength: num(r.path_length),
    selfSigned: r.self_signed === true,
    issuerMissing: r.issuer_missing === true,
    sha256: str(r.sha256_fingerprint),
    sha1: str(r.sha1_fingerprint),
    pem: str(r.pem),
  }
  node.label = nodeLabel(node)
  return node
}

/**
 * Görünüm verisi: düğümler KÖK İLK (derinliğe göre; eşitlikte gelen sıra korunur) + `issuerMissing` (en üstteki düğüm
 * kendinden imzalı değil → kök dosyada yok, istemci kendi güven deposundan tamamlar).
 */
export function buildHierarchy(rawNodes) {
  const nodes = (Array.isArray(rawNodes) ? rawNodes : [])
    .map((n, i) => ({ n: normalizeNode(n, i), i }))
    .sort((a, b) => (a.n.depth - b.n.depth) || (a.i - b.i))
    .map(({ n }) => n)
  const top = nodes[0] || null
  return {
    nodes,
    issuerMissing: !!top && top.issuerMissing,
    missingIssuerDn: top && top.issuerMissing ? top.issuerDn : null,
  }
}

/**
 * Açılışta seçilecek düğümün sırası. `initial`: sayı (doğrudan sıra) ya da rol adı (`leaf` | `intermediate` | `root`).
 * İstenen rol yoksa (ör. truststore'daki ara + kök: yaprak yok) takip edilen baş (`head`), o da yoksa EN ALTTAKİ düğüm.
 */
export function initialIndex(nodes, initial = 'leaf') {
  const arr = Array.isArray(nodes) ? nodes : []
  if (!arr.length) return -1
  if (typeof initial === 'number' && Number.isInteger(initial)) return Math.min(Math.max(initial, 0), arr.length - 1)
  const byRole = arr.findIndex((n) => n.role === initial)
  if (byRole >= 0) return byRole
  const head = arr.findIndex((n) => n.head)
  return head >= 0 ? head : arr.length - 1
}

/**
 * Geçerlilik tonu — SSL sekmesinin zincir kartlarıyla (SslChainView `daysBadge`) AYNI eşikler: dolmuş · ≤ 14 gün kritik ·
 * ≤ 30 gün uyarı · üstü sağlıklı. Henüz başlamamış geçerlilik ayrı ton. Kalan gün bilinmiyorsa null (rozet yok).
 */
export function validityTone(node) {
  if (!node) return null
  if (node.expired) return 'expired'
  if (node.notYetValid) return 'pending'
  if (node.days == null) return null
  if (node.days <= 14) return 'crit'
  if (node.days <= 30) return 'warn'
  return 'ok'
}

/** Ad parçası satırları (boş olanlar düşer): `[{ key: 'cn', value }]` — tarayıcı "Details" sırası. */
export function nameRows(p) {
  const src = p && typeof p === 'object' ? p : {}
  return NAME_PARTS.filter((k) => src[k]).map((k) => ({ key: k, value: src[k] }))
}

/**
 * İndirme dosya adı: `<CN>.pem` — joker `*` → `wildcard`, dosya adına uymayan karakterler `_`, baş/son ayraçlar atılır,
 * en çok 100 karakter; ad yoksa `certificate.pem`.
 */
export function pemFileName(node) {
  const base = String(node?.subject?.cn || node?.subject?.o || '')
    .replace(/\*/g, 'wildcard')
    .replace(/[^A-Za-z0-9._-]+/g, '_')
    .replace(/^[._-]+|[._-]+$/g, '')
    .slice(0, 100)
  return `${base || 'certificate'}.pem`
}

/**
 * Düğümün TEK açık sertifikasını PEM olarak indirir (istemci tarafı Blob; ağ isteği yok). Özel anahtar hiçbir zaman
 * saklanmadığı için yanıt da taşımaz. Tarayıcı dışı ortamda sessizce false döner.
 */
export function downloadNodePem(node) {
  if (!node?.pem) return false
  try {
    const pem = node.pem.endsWith('\n') ? node.pem : `${node.pem}\n`
    const blob = new Blob([pem], { type: 'application/x-pem-file' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = pemFileName(node)
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => { try { URL.revokeObjectURL(url) } catch { /* yok */ } }, 1000)
    return true
  } catch {
    return false
  }
}

/**
 * SSL sekmesinin çevrim-dışı önizlemesinden (`/check-preview`, manuel kayıt) hiyerarşi hedefi: kayıt + GÜNCEL sürüm
 * kimliği (sunucu 2026-10-07'den beri `inventory_id` + `manual_version_id` ekler). Eksikse null → bileşen kaydı alan
 * adından bulur.
 */
export function previewTarget(data) {
  const inv = data?.inventory_id
  const ver = data?.manual_version_id
  return inv != null && ver != null ? { inventoryId: inv, versionId: ver } : null
}
