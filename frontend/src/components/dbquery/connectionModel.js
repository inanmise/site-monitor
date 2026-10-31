// Veritabanı Sorgu İzleme — kayıtlı bağlantı formunun ve sağlık panelinin SAF modeli (2026-10-31). Tel biçimi
// dbquery-api.md §2. Kesin kurallar: parola ASLA gösterilmez / doldurulmaz (API yalnız `password_set` döner); boş parola
// "değişmez" demektir; bilinmeyen sağlık durumu asla yeşil değildir.

export const DB_TYPES = ['POSTGRESQL', 'MSSQL', 'ORACLE']
export const DEFAULT_PORT = { POSTGRESQL: 5432, MSSQL: 1433, ORACLE: 1521 }
export const PG_SSL_MODES = ['disable', 'prefer', 'require', 'verify-full']
export const ORACLE_MODES = ['SERVICE', 'SID']
export const HEALTH_STATES = ['ok', 'failing', 'untested', 'unused']
export const CONNECT_TIMEOUT = { min: 1000, max: 30000, def: 10000 }
const DB_NAME = /^[A-Za-z0-9_.$#-]{1,128}$/

/** Sağlık durumu — sunucunun dört değerinden biri; başka her şey (yok, okunamadı) "unknown". */
export function healthState(profile) {
  const s = profile?.health?.state
  return HEALTH_STATES.includes(s) ? s : 'unknown'
}

/** Rozet tonu — yalnız sunucunun "ok" dediği yeşil; bilinmeyen nötr (asla yeşil değil). */
export const HEALTH_TONE = { ok: 'success', failing: 'danger', untested: 'warning', unused: 'muted', unknown: 'muted' }

/** "db.example.com:5432/orders" · MSSQL örneği "db\\SQL01:1433/orders" · Oracle SID "db:1521:ORCL". */
export function serverLabel(p) {
  if (!p) return ''
  const host = p.host || '?'
  const inst = p.db_type === 'MSSQL' && p.mssql_instance ? `\\${p.mssql_instance}` : ''
  const port = p.port != null && p.port !== '' ? `:${p.port}` : ''
  const db = p.database_name ? p.database_name : ''
  if (p.db_type === 'ORACLE' && p.oracle_mode === 'SID') return `${host}${port}${db ? `:${db}` : ''}`
  return `${host}${inst}${port}${db ? `/${db}` : ''}`
}

/** Boş form — yeni bağlantı. */
export function emptyForm(teamId = null, dbType = 'POSTGRESQL') {
  return {
    id: null, team_id: teamId, name: '', db_type: dbType, host: '', port: String(DEFAULT_PORT[dbType]), database_name: '',
    oracle_mode: 'SERVICE', mssql_instance: '', username: '', password: '', password_set: false, password_clear: false,
    ssl_mode: 'prefer', mssql_encrypt: true, mssql_trust_server_certificate: false,
    connect_timeout_ms: String(CONNECT_TIMEOUT.def), description: '',
  }
}

/** Kayıtlı profil → düzenleme formu. Parola alanı BOŞ başlar (asla doldurulmaz). */
export function formFromProfile(p) {
  const f = emptyForm(p?.team_id ?? null, DB_TYPES.includes(p?.db_type) ? p.db_type : 'POSTGRESQL')
  return {
    ...f,
    id: p?.id ?? null,
    name: p?.name ?? '',
    host: p?.host ?? '',
    port: p?.port != null ? String(p.port) : f.port,
    database_name: p?.database_name ?? '',
    oracle_mode: ORACLE_MODES.includes(p?.oracle_mode) ? p.oracle_mode : 'SERVICE',
    mssql_instance: p?.mssql_instance ?? '',
    username: p?.username ?? '',
    password: '',
    password_set: p?.password_set === true,
    password_clear: false,
    ssl_mode: PG_SSL_MODES.includes(p?.ssl_mode) ? p.ssl_mode : f.ssl_mode,
    mssql_encrypt: p?.mssql_encrypt !== false,
    mssql_trust_server_certificate: p?.mssql_trust_server_certificate === true,
    connect_timeout_ms: p?.connect_timeout_ms != null ? String(p.connect_timeout_ms) : f.connect_timeout_ms,
    description: p?.description ?? '',
  }
}

/** DB türü değişimi: port, türün varsayılanıysa yeni türün varsayılanına geçer (elle yazılan port korunur). */
export function changeDbType(form, dbType) {
  if (form.db_type === dbType) return form
  const wasDefault = !form.port || form.port === String(DEFAULT_PORT[form.db_type])
  return { ...form, db_type: dbType, port: wasDefault ? String(DEFAULT_PORT[dbType]) : form.port }
}

const intOrNull = (v) => (v === '' || v == null ? null : (/^\d+$/.test(String(v).trim()) ? Number(String(v).trim()) : NaN))

/**
 * Form → API gövdesi. Türe ait olmayan alanlar gönderilmez; parola yalnız yazıldıysa; `password_clear` yalnız işaretliyse.
 * `withId` (kaydetmeden deneme, düzenleme): kayıtlı parolayla denensin diye kimlik eklenir.
 */
export function payloadFromForm(form, { withId = false } = {}) {
  const t = form.db_type
  const body = {
    team_id: form.team_id, name: String(form.name || '').trim(), db_type: t, host: String(form.host || '').trim(),
    port: intOrNull(form.port), database_name: String(form.database_name || '').trim(), username: String(form.username || '').trim(),
    connect_timeout_ms: intOrNull(form.connect_timeout_ms), description: String(form.description || '').trim(),
  }
  if (t === 'ORACLE') body.oracle_mode = form.oracle_mode
  if (t === 'MSSQL') {
    body.mssql_instance = String(form.mssql_instance || '').trim()
    body.mssql_encrypt = !!form.mssql_encrypt
    body.mssql_trust_server_certificate = !!form.mssql_trust_server_certificate
  }
  if (t === 'POSTGRESQL') body.ssl_mode = form.ssl_mode
  if (form.password) body.password = form.password
  if (form.password_clear && !form.password) body.password_clear = true
  if (withId && form.id != null) body.id = form.id
  return body
}

/**
 * Bağlantının HEDEFİNİ (ve güvenlik ayarını) belirleyen alanlar — sunucunun `targetChanges` listesiyle aynı küme. Düzenlemede
 * biri değişince hepsi birlikte gönderilir (sunucu birleşik kaydı bütün olarak doğrular; tür değişimi türe ait alanları
 * temizler) ve kayıtlı parola yeni hedefe gitmez kuralı (`password`) sunucuda tetiklenir.
 */
export const TARGET_KEYS = Object.freeze(['db_type', 'host', 'port', 'database_name', 'oracle_mode', 'mssql_instance', 'username',
  'ssl_mode', 'mssql_encrypt', 'mssql_trust_server_certificate'])

/**
 * Düzenleme gövdesi (DÜŞÜK 7, "yalnız değişen alanlar"): yüklenen hâlden DEĞİŞEN alanlar; hedef alanlarından biri
 * değiştiyse türün TÜM hedef alanları; parola yalnız yazıldıysa, `password_clear` yalnız işaretliyse. Aynı bağlantıyı açık
 * tutan iki kişiden ikincisinin kaydı, birincinin değiştirdiği başka alanları sessizce eski değere döndürmez. Değişiklik yoksa
 * `{}` (çağıran istek atmaz).
 */
export function changedConnectionPayload(form, baseline) {
  const next = payloadFromForm(form)
  const base = payloadFromForm(baseline || {})
  const same = (k) => JSON.stringify(next[k]) === JSON.stringify(base[k])
  const out = {}
  for (const k of Object.keys(next)) {
    if (k === 'password' || k === 'password_clear') continue
    if (!same(k)) out[k] = next[k]
  }
  if (TARGET_KEYS.some((k) => k in out || (k in base && !(k in next)))) {
    for (const k of TARGET_KEYS) if (k in next) out[k] = next[k]
  }
  if (next.password) out.password = next.password
  if (next.password_clear) out.password_clear = true
  return out
}

/**
 * İstemci doğrulaması → `{ alan: kod }` (`dbq.conn.v.<kod>`). Sunucu asıl doğrulayıcıdır (SSRF, ad tekilliği…).
 * Parola ZORUNLU DEĞİL (O1): sunucu parolasız bağlantıyı destekler — yeni bağlantıda boş = parolasız, düzenlemede boş =
 * kayıtlı parola değişmez, "Kayıtlı parolayı kaldır" = parolasız. Hedef değişince yeni parolayı sunucu ister (alan hatası).
 */
export function validateConnection(form) {
  const out = {}
  if (!String(form.name || '').trim()) out.name = 'name_required'
  if (!DB_TYPES.includes(form.db_type)) out.db_type = 'db_type'
  if (!String(form.host || '').trim()) out.host = 'host_required'
  else if (/[\s/\\?#@]/.test(String(form.host).trim())) out.host = 'host_invalid'
  const port = intOrNull(form.port)
  if (port == null) out.port = 'port_required'
  else if (!Number.isInteger(port) || port < 1 || port > 65535) out.port = 'port_range'
  const db = String(form.database_name || '').trim()
  if (!db) out.database_name = form.db_type === 'ORACLE' ? 'service_required' : 'database_required'
  else if (!DB_NAME.test(db)) out.database_name = 'database_invalid'
  if (form.db_type === 'MSSQL' && form.mssql_instance && !/^[A-Za-z0-9_$#-]{1,64}$/.test(String(form.mssql_instance).trim())) {
    out.mssql_instance = 'instance_invalid'
  }
  if (!String(form.username || '').trim()) out.username = 'username_required'
  const ct = intOrNull(form.connect_timeout_ms)
  if (ct == null) out.connect_timeout_ms = 'timeout_required'
  else if (!Number.isInteger(ct) || ct < CONNECT_TIMEOUT.min || ct > CONNECT_TIMEOUT.max) out.connect_timeout_ms = 'timeout_range'
  return out
}

/** Sunucu yanıtındaki alan hataları → `{ alan: ileti }` (VALIDATION_FAILED `fields` / `field` + `error`). */
export function serverFieldErrors(res) {
  if (!res || res.success !== false) return {}
  const out = {}
  if (res.fields && typeof res.fields === 'object') {
    for (const [k, v] of Object.entries(res.fields)) if (v) out[k] = String(v)
  }
  if (res.field && !out[res.field]) out[res.field] = res.error || res.message || ''
  return out
}
