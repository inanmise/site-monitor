/**
 * SSL Kontrol sekmesinin SAF modeli (2026-09-28 yeniden tasarım) — `GET /api/check-preview/{domain}` yanıtını
 * (snake_case tel biçimi, CertificateCheckerService + CertificateController.putPreviewAssessment) okunur kontrol
 * satırlarına, tek cümlelik hükme ve yaprak → ara → kök zincir dizisine çevirir.
 *
 * <p><b>Kural YAZMAZ.</b> Hostname / protokol / şifre / ileri gizlilik / imza / anahtar hükmü sunucudan gelir
 * (`assessment.*`, CertificateHealthRules — kart rozeti, Sağlık sekmesi ve Zayıf Algoritma Raporu ile aynı kural).
 * Burada yalnız ölçülmüş durum etiketleri (trust_status, chain_status, revocation_status, days_remaining, warning,
 * hsts) satıra çevrilir. Eski panel jokeri istemcide gevşek eşliyordu (*.example.com → a.b.example.com "kapsanıyor")
 * ve kart aynı sertifikaya "Güvensiz" derken sekme yeşil gösteriyordu.
 *
 * <p><b>UNKNOWN, FAIL DEĞİLDİR:</b> ölçülemeyen (OCSP'ye ulaşılamadı, HSTS isteği düştü) satır gri çizilir ve hükmü
 * bozmaz. Metinler i18n anahtarı + argüman olarak döner; bileşen çevirir (model `t` almaz).
 */
import { revocationReason } from '../../utils/revocationInfo.js'

/** Satır durumu — `data-status` değeri ve simge/ton seçimi. */
export const ST = Object.freeze({ OK: 'ok', WARN: 'warn', FAIL: 'fail', UNKNOWN: 'unknown', INFO: 'info' })

/**
 * Sonuç DOSYADAN yüklenen (manuel) bir sertifikanın çevrim-dışı değerlendirmesi mi (2026-10-07) — `via: 'upload'` /
 * `manual: true` (ManualCertificateEvaluationService). Ağa özgü satırlar (DNS, protokol, şifre, PFS, HSTS) ve alan adı
 * eşleşmesi bu sonuçta ÖLÇÜLMEZ: "bilinmiyor" diye çizilmez, hiç çizilmez. Ağ sonucu hiçbir zaman bu işareti taşımaz.
 */
export const isUploadResult = (data) => data?.via === 'upload' || data?.manual === true

const RULE = { OK: ST.OK, WARN: ST.WARN, FAIL: ST.FAIL, UNKNOWN: ST.UNKNOWN, NA: ST.UNKNOWN }

/** CertificateHealthRules.Status adı → satır durumu; tanınmayan/eksik değer UNKNOWN. */
export function ruleStatus(v) {
  return RULE[String(v ?? '').trim().toUpperCase()] ?? ST.UNKNOWN
}

const HEX2 = /^[0-9a-fA-F]{2}$/

/**
 * RFC 2253 ayırt edici ad (X500Principal.getName()) → { CN, O, OU, L, ST, C, … } (ilk değer kazanır).
 * Kaçışlı virgül (`O=Example\, Ltd`), tırnaklı değer ve `\C3\BC` gibi UTF-8 onaltılık kaçışlar çözülür — eski
 * `[^,]+` düzenli ifadesi "Example\" diye keserdi.
 */
export function parseDn(dn) {
  const out = {}
  if (typeof dn !== 'string' || !dn.trim()) return out
  let key = ''
  let val = ''
  let inKey = true
  let quoted = false
  let bytes = []
  const flush = () => {
    if (!bytes.length) return
    try { val += new TextDecoder().decode(new Uint8Array(bytes)) } catch { val += bytes.map((b) => String.fromCharCode(b)).join('') }
    bytes = []
  }
  const commit = () => {
    flush()
    const k = key.trim().toUpperCase()
    if (k && !(k in out)) out[k] = val.trim()
    key = ''; val = ''; inKey = true; quoted = false
  }
  for (let i = 0; i < dn.length; i++) {
    const c = dn[i]
    if (inKey) {
      if (c === '=') inKey = false
      else if (c === ',' || c === '+' || c === ';') key = ''
      else key += c
      continue
    }
    if (c === '\\' && i + 1 < dn.length) {
      const hex = dn.slice(i + 1, i + 3)
      if (HEX2.test(hex)) { bytes.push(parseInt(hex, 16)); i += 2; continue }
      flush(); val += dn[i + 1]; i += 1; continue
    }
    flush()
    if (c === '"') { quoted = !quoted; continue }
    if (!quoted && (c === ',' || c === '+' || c === ';')) { commit(); continue }
    val += c
  }
  commit()
  return out
}

/** "AB12CD…" → "AB:12:CD:…" (okunur gösterim; kopyalanan değer sunucunun döndürdüğü ham metin kalır). */
export function colonHex(hex) {
  if (typeof hex !== 'string' || !hex) return ''
  const clean = hex.replace(/[^0-9a-fA-F]/g, '')
  if (!clean || clean.length % 2) return hex
  return clean.toUpperCase().match(/.{2}/g).join(':')
}

/** "TLSv1.3" → "TLS 1.3"; "TLSv1" (Java'nın TLS 1.0 adı) → "TLS 1.0". */
export function prettyTls(v) {
  if (typeof v !== 'string' || !v.trim()) return null
  const m = v.trim().match(/^TLSv?(\d)(?:\.(\d))?$/i)
  if (m) return `TLS ${m[1]}.${m[2] ?? '0'}`
  return v.trim()
}

/** Hostname kapsaması: sunucu hükmü (`assessment.hostname`), yoksa `security_flags`; ikisi de yoksa bilinmiyor. */
export function hostnameStatus(data) {
  const a = data?.assessment
  if (a && a.hostname != null) return ruleStatus(a.hostname)
  if (Array.isArray(data?.security_flags)) {
    if (data.security_flags.includes('HOSTNAME_MISMATCH')) return ST.FAIL
    return Array.isArray(data.san) && data.san.length ? ST.OK : ST.UNKNOWN
  }
  return ST.UNKNOWN
}

function num(v) { return typeof v === 'number' && Number.isFinite(v) ? v : null }

function row(key, status, textKey, args = [], extra = {}) {
  return { key, status, textKey, args, ...extra }
}

/** Zincirde yalnız yaprak var ve yaprak kendinden imzalı DEĞİL → ara sertifikalar gönderilmemiş. */
function leafOnly(data) {
  const chain = Array.isArray(data?.chain) ? data.chain : []
  return chain.length === 1 && chain[0]?.is_leaf && !chain[0]?.is_root
}

/**
 * Kontrol grupları. Her satır: { key, status, textKey, args, value?, mono?, advice? } — `advice` satırı (HSTS, ileri
 * gizlilik, CBC şifre) sunucu SERTLEŞTİRME önerisidir; hükmü "sorun" yapmaz, ayrı sayılır.
 *
 * <p>Dosyadan yüklenen sertifikada (`isUploadResult`) "Bağlantı" grubu ve alan adı eşleşmesi satırı YOKTUR (takip adı
 * bir host adı değil, el sıkışma yok); zincir / iptal metinleri dosyaya göre yazılır. Ağ sonucu birebir aynı.
 */
export function buildSslGroups(data) {
  const d = data || {}
  const a = d.assessment || {}
  const domain = d.domain || ''
  const days = num(d.days_remaining)
  const tls = prettyTls(d.tls_version)
  const upload = isUploadResult(d)

  // ── Bağlantı ──
  const conn = []
  if (d.resolved_ip) conn.push(row('dns', ST.OK, 'sslv.dns.ok', [domain, d.resolved_ip], { value: d.resolved_ip, mono: true }))
  else conn.push(row('dns', ST.UNKNOWN, d.via === 'proxy' ? 'sslv.dns.proxy' : 'sslv.dns.unknown', [domain]))

  const proto = a.protocol != null ? ruleStatus(a.protocol) : ST.UNKNOWN
  conn.push(row('protocol', proto,
    proto === ST.OK ? (a.protocol_latest ? 'sslv.protocol.latest' : 'sslv.protocol.ok')
      : proto === ST.FAIL ? 'sslv.protocol.fail' : 'sslv.protocol.unknown',
    [tls ?? ''], { value: tls }))

  const cipher = a.cipher != null ? ruleStatus(a.cipher) : ST.UNKNOWN
  conn.push(row('cipher', cipher, `sslv.cipher.${cipher}`, [], {
    value: d.cipher_suite || null, mono: true, advice: cipher === ST.WARN,
  }))

  const pfs = a.pfs != null ? ruleStatus(a.pfs) : ST.UNKNOWN
  conn.push(row('pfs', pfs === ST.WARN ? ST.WARN : pfs, `sslv.pfs.${pfs === ST.WARN ? ST.UNKNOWN : pfs}`, [], { advice: true }))

  if (d.hsts === true) conn.push(row('hsts', ST.OK, 'sslv.hsts.ok', [], { advice: true }))
  else if (d.hsts === false) conn.push(row('hsts', ST.WARN, 'sslv.hsts.missing', [], { advice: true }))
  else conn.push(row('hsts', ST.UNKNOWN, 'sslv.hsts.unknown', [], { advice: true }))

  // ── Sertifika ──
  const cert = []
  if (days == null) cert.push(row('expiry', ST.UNKNOWN, 'sslv.expiry.unknown'))
  else if (days < 0) cert.push(row('expiry', ST.FAIL, 'sslv.expiry.expired', [Math.abs(days)], { value: days }))
  else if (days === 0) cert.push(row('expiry', ST.WARN, 'sslv.expiry.today', [], { value: days }))
  else if (d.warning === true) cert.push(row('expiry', ST.WARN, 'sslv.expiry.soon', [days], { value: days }))
  else cert.push(row('expiry', ST.OK, 'sslv.expiry.ok', [days], { value: days }))

  if (!upload) {
    const host = hostnameStatus(d)
    cert.push(row('hostname', host, `sslv.host.${host === ST.WARN ? ST.UNKNOWN : host}`, [domain]))
  }

  const sig = a.signature != null ? ruleStatus(a.signature) : ST.UNKNOWN
  cert.push(row('signature', sig, `sslv.sig.${sig === ST.WARN ? ST.UNKNOWN : sig}`, [],
    { value: d.signature_algorithm || null, mono: true }))

  const key = a.key_size != null ? ruleStatus(a.key_size) : ST.UNKNOWN
  const keyText = d.public_key_algorithm
    ? `${d.public_key_algorithm}${num(d.public_key_size) > 0 ? ` ${d.public_key_size}` : ''}` : null
  cert.push(row('key', key, `sslv.key.${key === ST.WARN ? ST.UNKNOWN : key}`, [], { value: keyText, mono: true }))

  // ── Güven ve zincir ──
  const trust = []
  const issuer = d.issuer_cn || d.issuer || parseDn(d.issuer_dn).CN || ''
  const ts = String(d.trust_status || '').toUpperCase()
  if (ts === 'TRUSTED') trust.push(row('trust', ST.OK, 'sslv.trust.ok', [issuer]))
  else if (ts === 'UNTRUSTED') trust.push(row('trust', ST.FAIL, 'sslv.trust.fail', [issuer]))
  else trust.push(row('trust', ST.UNKNOWN, 'sslv.trust.unknown', [issuer]))

  const chainLen = Array.isArray(d.chain) ? d.chain.length : 0
  const cs = String(d.chain_status || '').toUpperCase()
  if (cs === 'BROKEN') trust.push(row('chain', ST.FAIL, 'sslv.chain.broken', [chainLen], { value: chainLen || null }))
  else if ((cs === 'VALID' || cs === 'REVOKED') && leafOnly(d)) trust.push(row('chain', ST.WARN, 'sslv.chain.leafOnly', [], { value: chainLen }))
  else if (cs === 'VALID' || cs === 'REVOKED') trust.push(row('chain', ST.OK, upload ? 'sslv.m.chain.ok' : 'sslv.chain.ok', [chainLen], { value: chainLen || null }))
  else trust.push(row('chain', ST.UNKNOWN, upload ? 'sslv.m.chain.unknown' : 'sslv.chain.unknown'))

  // İptal (2026-10-08): "bilinmiyor" NEDENİNE göre ayrılır. Sertifikada OCSP/CRL adresi yoksa denetlenecek bir şey
  // yoktur → bilgi satırı (INFO: sorun sayılmaz, "denetlenemedi" sayacına ve paydaya girmez); "OCSP/CRL hizmetine
  // ulaşılamadı" yalnız adres var ama yanıt alınamadıysa söylenir.
  const rs = String(d.revocation_status || '').toUpperCase()
  const why = revocationReason(d)
  if (rs === 'VALID') trust.push(row('revocation', ST.OK, 'sslv.rev.ok'))
  else if (rs === 'REVOKED') trust.push(row('revocation', ST.FAIL, 'sslv.rev.fail'))
  else if (why === 'NO_ENDPOINTS') trust.push(row('revocation', ST.INFO, 'sslv.rev.noEndpoints'))
  else if (why === 'UNSUPPORTED_SCHEME') trust.push(row('revocation', ST.UNKNOWN, 'sslv.rev.ldapOnly'))
  else if (why === 'NO_ISSUER') trust.push(row('revocation', ST.UNKNOWN, 'sslv.rev.noIssuer'))
  else if (why === 'NOT_CHECKED') trust.push(row('revocation', ST.UNKNOWN, 'sslv.rev.notChecked'))
  else if (why === 'UNREACHABLE') trust.push(row('revocation', ST.UNKNOWN, 'sslv.rev.unknown'))
  else trust.push(row('revocation', ST.UNKNOWN, upload ? 'sslv.m.rev.unknown' : 'sslv.rev.unknown'))

  return [
    { key: 'cert', rows: cert },
    { key: 'trust', rows: trust },
    ...(upload ? [] : [{ key: 'conn', rows: conn }]),
  ]
}

/** Hükmü sorun sırasına dizer: süre, hostname, güven, iptal, zincir, protokol, şifre, imza, anahtar. */
const SEVERITY_ORDER = ['expiry', 'hostname', 'trust', 'revocation', 'chain', 'protocol', 'cipher', 'signature', 'key']
const bySeverity = (a, b) => SEVERITY_ORDER.indexOf(a.key) - SEVERITY_ORDER.indexOf(b.key)

/**
 * Tek bakışta hüküm. `tone`: fail (en az bir sorun) · warn (sorun yok ama dikkat) · ok · unknown (kalan gün bile
 * ölçülemedi — hüküm yok). `problems` / `attention`
 * satırları (önem sırasıyla) hükmün gerekçesidir; `advice` sayısı sertleştirme önerileridir (hükmü değiştirmez).
 */
export function buildVerdict(groups) {
  const rows = groups.flatMap((g) => g.rows)
  const problems = rows.filter((r) => r.status === ST.FAIL && !r.advice).sort(bySeverity)
  const attention = rows.filter((r) => r.status === ST.WARN && !r.advice).sort(bySeverity)
  const advice = rows.filter((r) => r.advice && (r.status === ST.WARN || r.status === ST.FAIL))
  let tone = problems.length ? ST.FAIL : attention.length ? ST.WARN : ST.OK
  // Kalan gün bile bilinmiyorsa "her şey yolunda" DENMEZ (kanıt yokluğu ≠ sağlıklı) — hüküm verilemedi.
  const expiry = rows.find((r) => r.key === 'expiry')
  if (tone === ST.OK && (!expiry || expiry.status === ST.UNKNOWN)) tone = ST.UNKNOWN
  return {
    tone, problems, attention, advice,
    // Bilgi satırı (INFO — ör. sertifikada iptal adresi yok) bir kontrol değildir: paydaya girmez.
    total: rows.filter((r) => r.status !== ST.INFO).length,
    passed: rows.filter((r) => r.status === ST.OK).length,
    unknown: rows.filter((r) => r.status === ST.UNKNOWN).length,
  }
}

/**
 * Zincir dizisi: yaprak (üst düzey alanlardan — SAN, parmak izi, anahtar yalnız orada) → ara sertifikalar → kök.
 * `rootSent` false ise sunucu kökü göndermemiş demektir (olağan: istemci kendi güven deposundan tamamlar).
 *
 * <p>Dosyadan yüklenen sertifika (`upload: true`, 2026-10-07): zincir dosyadaki parçalardan kurulur ve AYNI kartlarla
 * çizilir. Takip edilen baş bir CA sertifikasıysa (ör. truststore'daki ara + kök) ilk kartın rolü "Sunucu sertifikası"
 * değil, gerçek rolüdür (ara / kök). `rootSent` false = kök dosyada yok.
 */
export function buildChain(data) {
  const d = data || {}
  const upload = isUploadResult(d)
  const chain = (Array.isArray(d.chain) ? d.chain : []).slice().sort((x, y) => (x.position ?? 0) - (y.position ?? 0))
  const leafRaw = chain.find((c) => c.is_leaf) || null
  const subj = parseDn(d.subject_dn)
  const iss = parseDn(d.issuer_dn)
  const days = num(d.days_remaining)
  const headRole = upload && d.is_ca === true ? (leafRaw?.is_root ? 'root' : 'intermediate') : 'leaf'
  const nodes = [{
    role: headRole,
    cn: d.subject || subj.CN || d.domain || '',
    org: subj.O || null,
    issuerCn: d.issuer_cn || iss.CN || null,
    issuerOrg: d.issuer || iss.O || null,
    notBefore: d.not_before || null,
    notAfter: d.not_after || null,
    days,
    expired: days != null && days < 0,
    selfSigned: !!leafRaw?.is_root,
    serial: d.serial_number || null,
    fingerprint: d.fingerprint || null,
    sigAlg: d.signature_algorithm || null,
    keyAlg: d.public_key_algorithm || null,
    keySize: num(d.public_key_size) > 0 ? d.public_key_size : null,
    san: Array.isArray(d.san) ? d.san.filter(Boolean) : [],
    certType: d.cert_type || null,
    subjectDn: d.subject_dn || null,
    issuerDn: d.issuer_dn || null,
    keyUsage: Array.isArray(d.key_usage) ? d.key_usage : [],
    extKeyUsage: Array.isArray(d.ext_key_usage) ? d.ext_key_usage : [],
    ocspUrl: d.ocsp_url || null,
    crlUrl: d.crl_url || null,
  }]
  for (const c of chain) {
    if (c.is_leaf) continue
    const s = parseDn(c.subject)
    const i = parseDn(c.issuer)
    nodes.push({
      role: c.is_root ? 'root' : 'intermediate',
      cn: s.CN || c.subject || '',
      org: s.O || null,
      issuerCn: i.CN || c.issuer || null,
      issuerOrg: i.O || null,
      notBefore: c.not_before || null,
      notAfter: c.not_after || null,
      days: c.expired ? null : num(c.days_remaining),
      expired: !!c.expired,
      selfSigned: !!c.is_root,
      serial: c.serial_number || null,
      fingerprint: null,
      sigAlg: c.signature_algorithm || null,
      san: [],
      subjectDn: c.subject || null,
      issuerDn: c.issuer || null,
    })
  }
  return { nodes, rootSent: nodes.some((n) => n.role === 'root' || n.selfSigned), upload }
}

/**
 * Analiz girdisinin (sihirbaz İnceleme adımı) zincir görünümü verisi: sunucunun çevrim-dışı önizlemesi (`entry.preview` —
 * SSL sekmesiyle aynı sonuç haritası) varsa o; yoksa (eski sunucu / önizleme kurulamadı) girdinin kendi alanlarından
 * aynı biçim kurulur — görünüm her durumda SslChainView.
 */
export function entryChainData(entry) {
  const e = entry || {}
  if (e.preview && typeof e.preview === 'object' && Array.isArray(e.preview.chain)) return e.preview
  const links = Array.isArray(e.chain) ? e.chain : []
  return {
    via: 'upload', manual: true, domain: e.suggested_key || e.cn || '',
    subject: e.cn || null, subject_dn: e.subject_dn || null, issuer_dn: e.issuer_dn || null, issuer: null,
    not_before: e.not_before || null, not_after: e.not_after || null, days_remaining: num(e.days_remaining),
    serial_number: e.serial_number || null, fingerprint: e.ref || null, signature_algorithm: e.signature_algorithm || null,
    public_key_algorithm: e.key_alg || null, public_key_size: num(e.key_size), san: Array.isArray(e.san) ? e.san : [],
    key_usage: e.key_usage, ext_key_usage: e.ext_key_usage, cert_type: e.cert_type || null, is_ca: !!e.is_ca,
    trust_status: e.trust_status || null,
    chain: [
      { position: 0, is_leaf: true, is_root: !!e.self_signed, subject: e.subject_dn || '', issuer: e.issuer_dn || '' },
      ...links.map((c, i) => {
        const d = num(c.days_remaining)
        return {
          position: i + 1, is_leaf: false, is_root: !!c.is_ca && !!c.subject && c.subject === c.issuer,
          subject: c.subject || '', issuer: c.issuer || '', not_after: c.not_after || null,
          days_remaining: d, expired: d != null && d < 0,
        }
      }),
    ],
  }
}

/** Bağlantı hatası özeti (status = error): sınıf anahtarı + aşama + çözülen IP'ler + deneme sayısı. */
export function buildConnectionError(data) {
  const d = data || {}
  const cls = String(d.error_class || '').toUpperCase()
  const known = ['DNS', 'NETWORK', 'SSL', 'BLOCKED']
  return {
    classKey: `sslv.err.class.${known.includes(cls) ? cls : 'UNKNOWN'}`,
    stage: d.error_stage || null,
    ips: Array.isArray(d.resolved_ips) ? d.resolved_ips.filter(Boolean) : [],
    attempts: num(d.attempts_total),
    message: d.error || null,
  }
}
