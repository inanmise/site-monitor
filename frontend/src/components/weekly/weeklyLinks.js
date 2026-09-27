/**
 * Haftalık rapor takip bağlantıları — saf kurallar (2026-09-27, kullanıcı isteği: "takip linkleri kapalı/kompakt
 * dursun; kullanıcı linki girer, sonra tıklanabilir bir öğe olarak kalır, açık URL görünmez").
 *
 * Saklanan veri DEĞİŞMEDİ: her alan (`item1.tracking_url`, `item2.incidents_url` …) tek bir URL dizesi. Çipin etiketi
 * ve türü yalnız istemcide URL'den TÜRETİLİR (etiket için ayrı bir API alanı yok).
 *
 * - {@link normaliseLink}: kullanıcı girdisini doğrular — yalnız http/https; boşluk, `javascript:` ve diğer şemalar
 *   reddedilir; şemasız ama alan adı görünümlü girdi (`jira.example.com/x`) `https://` ile tamamlanır.
 * - {@link linkLabel}: Jira tarzı anahtar (`ABC-123`) varsa o; yoksa `alan-adı › son yol parçası`; yoksa alan adı.
 * - {@link linkKind}: ticket / doc / repo / generic — alan adı + yol desenine göre (ikon seçimi).
 */

const SCHEME = /^[a-z][a-z0-9+.-]*:/i
const HOSTLIKE = /^[^\s/?#]+\.[^\s/?#]+([/?#].*)?$/
const ISSUE_KEY = /\b([A-Z][A-Z0-9]{1,9}-\d{1,7})\b/

/**
 * Kullanıcı girdisi → `{ ok: true, url }` ya da `{ ok: false, error }` (`error` i18n anahtarı).
 * Boş girdi `{ ok: true, url: '' }` — alanı temizlemek geçerli bir işlem.
 */
export function normaliseLink(input) {
  const raw = String(input ?? '').trim()
  if (!raw) return { ok: true, url: '' }
  if (/\s/.test(raw)) return { ok: false, error: 'wr.link.errSpace' }
  let candidate = raw
  if (!SCHEME.test(candidate)) {
    if (candidate.startsWith('//')) candidate = 'https:' + candidate
    else if (HOSTLIKE.test(candidate)) candidate = 'https://' + candidate
    else return { ok: false, error: 'wr.link.errFormat' }
  }
  let u
  try { u = new URL(candidate) } catch { return { ok: false, error: 'wr.link.errFormat' } }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return { ok: false, error: 'wr.link.errScheme' }
  // Noktasız kurum içi ad (`http://jira/…`) açık şemayla geçerli; şemasız girdide HOSTLIKE zaten nokta ister.
  if (!u.hostname) return { ok: false, error: 'wr.link.errFormat' }
  // Kullanıcının yazdığı biçim saklanır (URL.href yolu kodlar / sona "/" ekler) — yalnız şema tamamlanmış olabilir.
  return { ok: true, url: candidate }
}

/** Saklanan değer tıklanabilir bir http(s) bağlantısı mı? (eski raporlarda şemasız değerler olabilir) */
export function isHttpLink(value) {
  const v = String(value ?? '').trim()
  if (!v || /\s/.test(v)) return false
  try {
    const u = new URL(v)
    return (u.protocol === 'http:' || u.protocol === 'https:') && !!u.hostname
  } catch { return false }
}

function parse(value) {
  const v = String(value ?? '').trim()
  if (!v) return null
  try { return new URL(SCHEME.test(v) ? v : 'https://' + v) } catch { return null }
}

/** Kısa alan adı: `www.` düşer. */
export function linkHost(value) {
  const u = parse(value)
  return u ? u.hostname.replace(/^www\./i, '') : ''
}

const clip = (s, n = 28) => (s.length > n ? s.slice(0, n - 1) + '…' : s)

/**
 * Etiket parçaları: `{ key }` (Jira tarzı anahtar) ya da `{ host, tail }` — çip dar alanda alan adını kırpar, ayırt
 * edici son parçayı (tail) korur. Yalnız sayı olan son parça (`…/merge_requests/42`) bir öncekiyle birleşir.
 */
export function linkLabelParts(value) {
  const u = parse(value)
  if (!u) return { host: clip(String(value ?? '').trim(), 32), tail: '' }
  let path = u.pathname
  try { path = decodeURIComponent(u.pathname) } catch { /* ham yol */ }
  const key = ISSUE_KEY.exec(path) || ISSUE_KEY.exec(u.search)
  if (key) return { key: key[1] }
  const host = linkHost(value)
  const segs = path.split('/').filter(Boolean)
  let seg = segs.pop()
  if (seg && /^\d+$/.test(seg) && segs.length) seg = `${segs.pop()}/${seg}`
  if (seg) return { host, tail: clip(seg, 24) }
  const q = [...u.searchParams.entries()][0]
  if (q) return { host, tail: clip(`${q[0]}=${q[1]}`, 24) }
  return { host, tail: '' }
}

/** Çipte görünen etiket — URL'nin kendisi değil. */
export function linkLabel(value) {
  const p = linkLabelParts(value)
  if (p.key) return p.key
  return p.tail ? `${p.host} › ${p.tail}` : p.host
}

const KIND_RULES = [
  ['ticket', /(^|\.)(jira|servicenow|service-now|youtrack|redmine|itsm|otrs|zendesk)\b|\/browse\/|\/issues?\b|\/tickets?\b|\/incidents?\b|\/problems?\b|[?&](filter|jql)=/i],
  ['repo', /(^|\.)(github|gitlab|bitbucket|gitea)\b|\/_git\/|\/repos?\/|\/merge_requests\/|\/pull\//i],
  ['doc', /(^|\.)(confluence|wiki|docs|sharepoint|notion)\b|\/wiki\/|\/display\/|\/pages\/|\.(pdf|docx?|xlsx?|pptx?)$/i],
]

/** Bağlantı türü (ikon): ticket | repo | doc | generic. */
export function linkKind(value) {
  const u = parse(value)
  if (!u) return 'generic'
  const probe = `${u.hostname}${u.pathname}${u.search}`
  for (const [kind, re] of KIND_RULES) if (re.test(probe)) return kind
  return 'generic'
}
