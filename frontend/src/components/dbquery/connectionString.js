// Veritabanı Sorgu İzleme — "Bağlantı dizesi yapıştır" (2026-11-05, A1) SAF ayrıştırıcısı. İstemcide çalışır, sunucuya
// hiçbir şey gitmez. Desteklenen biçimler:
//   • jdbc:postgresql://h[:p][/db][?sslmode=…&user=…]          • postgres://user[:parola]@h[:p]/db[?sslmode=…]
//   • jdbc:sqlserver://h[\örnek][:p];databaseName=…;encrypt=…;trustServerCertificate=…   • jdbc:jtds:sqlserver://h[:p][/db][;…]
//   • jdbc:oracle:thin:[kullanıcı/parola]@//h:p/servis · @h:p:SID · @h:p/servis · @tcps://h:p/servis · @(DESCRIPTION=…)
//   • anahtar=değer; (ADO.NET / Npgsql / ODP.NET: Server=…;Database=…;User Id=…  ·  Host=…;Port=…;Database=…)
//   • h:p/db · h:p · h/db (tür yok — porttan TÜR ÖNERİSİ)
// Kesin kural: dizedeki PAROLA asla alanlara taşınmaz (yalnız `hadPassword` bayrağı — çağıran uyarır ve kutuyu temizler).
// Doğrusal tarama: girdi önce `MAX_CONN_STRING` ile sınırlanır, iç içe nicelik içeren düzenli ifade yok.

import { DEFAULT_PORT, ORACLE_TCPS_PORT, PG_SSL_MODES, changeDbType, setOracleTcps } from './connectionModel.js'

/** Yapıştırılan dizenin üst sınırı (karakter) — daha uzunu ayrıştırılmaz. */
export const MAX_CONN_STRING = 2000

/** Ayrıştırma hata kodları (`dbq.conn.paste.err.<KOD>`). */
export const PASTE_ERRORS = Object.freeze(['EMPTY', 'TOO_LONG', 'UNRECOGNIZED', 'NO_HOST'])
/** Not kodları (`dbq.conn.paste.note.<KOD>`) — alan doldurulmadı ya da varsayıma gidildi. */
export const PASTE_NOTES = Object.freeze(['MULTI_HOST', 'SSL_MODE_IGNORED', 'PORT_INVALID', 'ENCRYPT_IGNORED', 'DESCRIPTION_PARTIAL'])

/** Doldurulabilen form alanları — "şunlar dolduruldu" listesinin sırası. */
export const FILLABLE = Object.freeze(['db_type', 'host', 'port', 'mssql_instance', 'database_name', 'oracle_mode', 'oracle_tcps',
  'username', 'ssl_mode', 'mssql_encrypt', 'mssql_trust_server_certificate'])

/** Porttan tür tahmini (yalnız ÖNERİ — tür değiştirilmez). */
const PORT_TYPE = { 5432: 'POSTGRESQL', 1433: 'MSSQL', 1521: 'ORACLE', [ORACLE_TCPS_PORT]: 'ORACLE' }

const HOST_OK = /^[A-Za-z0-9._-]+$/
const IPV6_OK = /^[0-9A-Fa-f:.]+$/

function result(over = {}) {
  return { ok: false, error: null, db_type: null, typeSource: null, fields: {}, hadPassword: false, notes: [], ...over }
}

function decode(s) {
  try { return decodeURIComponent(s) } catch { return s }
}

function stripQuotes(s) {
  if (s.length >= 2 && ((s[0] === '"' && s.at(-1) === '"') || (s[0] === "'" && s.at(-1) === "'"))) return s.slice(1, -1).trim()
  return s
}

/** Büyük / küçük harf duyarsız önek eşleşmesi. */
const startsI = (s, prefix) => s.slice(0, prefix.length).toLowerCase() === prefix

/** Port metni → sayı (1–65535) ya da null; geçersizse not düşülür. */
function portOf(text, notes) {
  const s = String(text ?? '').trim()
  if (!s) return null
  if (/^\d{1,5}$/.test(s)) {
    const n = Number(s)
    if (n >= 1 && n <= 65535) return n
  }
  notes.push({ code: 'PORT_INVALID', value: s.slice(0, 20) })
  return null
}

/** Sunucu adı / IP → geçerliyse temiz değer, değilse null. IPv6 köşeli ayraçsız saklanır. */
function hostOf(text) {
  let s = String(text ?? '').trim()
  if (s.startsWith('[') && s.endsWith(']')) s = s.slice(1, -1)
  if (!s || s.length > 253) return null
  if (HOST_OK.test(s) || (s.includes(':') && IPV6_OK.test(s))) return s
  return null
}

/**
 * "h[:p]" ya da "[v6]:p" → { host, port }. `sep` port ayıracı (':' — MSSQL ADO.NET için ',').
 * Çoklu sunucu ("h1:5432,h2:5432") → ilki + MULTI_HOST notu.
 */
function hostPort(text, notes, { sep = ':', multi = ',' } = {}) {
  let s = String(text ?? '').trim()
  if (multi && sep !== multi && s.includes(multi)) {
    notes.push({ code: 'MULTI_HOST' })
    s = s.slice(0, s.indexOf(multi))
  }
  if (s.startsWith('[')) {
    const close = s.indexOf(']')
    if (close < 0) return { host: null, port: null }
    const host = hostOf(s.slice(0, close + 1))
    const rest = s.slice(close + 1)
    return { host, port: rest.startsWith(sep) ? portOf(rest.slice(1), notes) : null }
  }
  const i = s.lastIndexOf(sep)
  if (i > 0 && (sep !== ':' || s.indexOf(':') === i)) {
    return { host: hostOf(s.slice(0, i)), port: portOf(s.slice(i + 1), notes) }
  }
  return { host: hostOf(s), port: null }
}

/** "a=b&c=d" ya da "a=b;c=d" → { anahtar(küçük harf, boşluksuz): değer }. Değer çözülmez (çağıran karar verir). */
function params(text, sep) {
  const out = {}
  for (const part of String(text || '').split(sep)) {
    const eq = part.indexOf('=')
    if (eq <= 0) continue
    const k = part.slice(0, eq).trim().toLowerCase().replace(/\s+/g, '')
    if (k && !(k in out)) out[k] = part.slice(eq + 1).trim()
  }
  return out
}

const TRUE_WORDS = new Set(['true', 'yes', '1', 'mandatory', 'strict'])
const FALSE_WORDS = new Set(['false', 'no', '0', 'optional'])
function boolOf(v, notes) {
  const s = String(v ?? '').trim().toLowerCase()
  if (TRUE_WORDS.has(s)) return true
  if (FALSE_WORDS.has(s)) return false
  if (s) notes.push({ code: 'ENCRYPT_IGNORED', value: s.slice(0, 20) })
  return undefined
}

/** PostgreSQL sslmode → formun dört kipinden biri; desteklenmeyen (verify-ca, allow …) not düşülür, alan doldurulmaz. */
function sslModeOf(v, notes) {
  const s = String(v ?? '').trim().toLowerCase().replace(/_/g, '-')
  if (!s) return undefined
  const norm = s === 'verifyfull' ? 'verify-full' : s
  if (PG_SSL_MODES.includes(norm)) return norm
  notes.push({ code: 'SSL_MODE_IGNORED', value: s.slice(0, 20) })
  return undefined
}

function setIf(fields, key, value) {
  if (value !== undefined && value !== null && value !== '') fields[key] = value
}

// ── PostgreSQL ────────────────────────────────────────────────────────────────────────────────────────────────────

/** "//[kullanıcı[:parola]@]h[:p][/db][?…]" (JDBC ve URI). */
function parsePgUrl(rest, r) {
  if (!rest.startsWith('//')) return result({ error: 'UNRECOGNIZED' })
  let s = rest.slice(2)
  const q = s.indexOf('?')
  const query = q >= 0 ? s.slice(q + 1) : ''
  if (q >= 0) s = s.slice(0, q)
  const slash = s.indexOf('/')
  const authority = slash >= 0 ? s.slice(0, slash) : s
  const db = slash >= 0 ? decode(s.slice(slash + 1)) : ''
  let hp = authority
  const at = authority.lastIndexOf('@')
  if (at >= 0) {
    const info = authority.slice(0, at)
    hp = authority.slice(at + 1)
    const colon = info.indexOf(':')
    if (colon >= 0) r.hadPassword = true
    setIf(r.fields, 'username', decode(colon >= 0 ? info.slice(0, colon) : info))
  }
  const { host, port } = hostPort(hp, r.notes)
  if (!host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
  r.fields.host = host
  setIf(r.fields, 'port', port)
  setIf(r.fields, 'database_name', db)
  const p = params(query, '&')
  if ('password' in p) r.hadPassword = true
  if (p.user && !r.fields.username) setIf(r.fields, 'username', decode(p.user))
  const ssl = sslModeOf(p.sslmode ? decode(p.sslmode) : '', r.notes)
  if (ssl) r.fields.ssl_mode = ssl
  else if (!p.sslmode && (p.ssl === 'true' || p.ssl === '1')) r.fields.ssl_mode = 'require'
  return { ...r, ok: true, db_type: 'POSTGRESQL', typeSource: 'explicit' }
}

// ── SQL Server ────────────────────────────────────────────────────────────────────────────────────────────────────

/** Ortak MSSQL özellikleri (JDBC / jTDS / ADO.NET) → alanlar. */
function applyMssqlProps(p, r) {
  if ('password' in p || 'pwd' in p) r.hadPassword = true
  setIf(r.fields, 'database_name', p.databasename ?? p.database ?? p.initialcatalog)
  setIf(r.fields, 'username', p.user ?? p.username ?? p.userid ?? p.uid)
  if (p.instancename ?? p.instance) setIf(r.fields, 'mssql_instance', p.instancename ?? p.instance)
  if (p.encrypt != null) { const b = boolOf(p.encrypt, r.notes); if (b !== undefined) r.fields.mssql_encrypt = b }
  if (p.trustservercertificate != null) {
    const b = boolOf(p.trustservercertificate, r.notes)
    if (b !== undefined) r.fields.mssql_trust_server_certificate = b
  }
}

/** "h[\örnek][:p]" → alanlara. */
function applyMssqlServer(server, r, { sep = ':' } = {}) {
  let s = String(server || '').trim()
  if (startsI(s, 'tcp:')) s = s.slice(4)
  let instance = null
  const bs = s.indexOf('\\')
  if (bs > 0) {
    // "h\örnek:p" ya da "h\örnek,p"
    const after = s.slice(bs + 1)
    const cut = after.search(sep === ',' ? /,/ : /:/)
    instance = cut >= 0 ? after.slice(0, cut) : after
    s = s.slice(0, bs) + (cut >= 0 ? after.slice(cut) : '')
  }
  const { host, port } = hostPort(s, r.notes, { sep, multi: sep === ',' ? null : ',' })
  if (host) r.fields.host = host
  setIf(r.fields, 'port', port)
  if (instance && /^[A-Za-z0-9_$#-]{1,64}$/.test(instance)) r.fields.mssql_instance = instance
}

function parseMssqlJdbc(rest, r) {
  if (!rest.startsWith('//')) return result({ error: 'UNRECOGNIZED' })
  const s = rest.slice(2)
  const semi = s.indexOf(';')
  const server = semi >= 0 ? s.slice(0, semi) : s
  const p = params(semi >= 0 ? s.slice(semi + 1) : '', ';')
  applyMssqlServer(server || p.servername || '', r)
  if (!r.fields.host && p.servername) applyMssqlServer(p.servername, r)
  if (p.portnumber && r.fields.port == null) setIf(r.fields, 'port', portOf(p.portnumber, r.notes))
  applyMssqlProps(p, r)
  if (!r.fields.host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
  return { ...r, ok: true, db_type: 'MSSQL', typeSource: 'explicit' }
}

function parseJtds(rest, r) {
  if (!rest.startsWith('//')) return result({ error: 'UNRECOGNIZED' })
  let s = rest.slice(2)
  const semi = s.indexOf(';')
  const p = params(semi >= 0 ? s.slice(semi + 1) : '', ';')
  if (semi >= 0) s = s.slice(0, semi)
  const slash = s.indexOf('/')
  if (slash >= 0) { setIf(r.fields, 'database_name', s.slice(slash + 1)); s = s.slice(0, slash) }
  applyMssqlServer(s, r)
  applyMssqlProps(p, r)
  if (!r.fields.host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
  return { ...r, ok: true, db_type: 'MSSQL', typeSource: 'explicit' }
}

// ── Oracle ────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * "(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=h)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=s)))" → alanlar. Yalnız ilk
 * HOST / PORT / PROTOCOL ve SERVICE_NAME ya da SID okunur; birden çok ADDRESS → MULTI_HOST notu.
 */
function parseOracleDescriptor(desc, r) {
  const s = desc.replace(/\s+/g, '')
  const pick = (key) => {
    const at = s.toUpperCase().indexOf(`(${key}=`)
    if (at < 0) return null
    const from = at + key.length + 2
    const end = s.indexOf(')', from)
    return end > from ? s.slice(from, end) : null
  }
  const up = s.toUpperCase()
  const first = up.indexOf('(HOST=')
  if (first >= 0 && up.indexOf('(HOST=', first + 1) >= 0) r.notes.push({ code: 'MULTI_HOST' })
  const host = hostOf(pick('HOST'))
  if (!host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
  r.fields.host = host
  setIf(r.fields, 'port', portOf(pick('PORT'), r.notes))
  const proto = (pick('PROTOCOL') || '').toUpperCase()
  if (proto === 'TCPS') r.fields.oracle_tcps = true
  else if (proto === 'TCP') r.fields.oracle_tcps = false
  const service = pick('SERVICE_NAME')
  const sid = pick('SID')
  if (service) { r.fields.database_name = service; r.fields.oracle_mode = 'SERVICE' }
  else if (sid) { r.fields.database_name = sid; r.fields.oracle_mode = 'SID' }
  else r.notes.push({ code: 'DESCRIPTION_PARTIAL' })
  return { ...r, ok: true, db_type: 'ORACLE', typeSource: 'explicit' }
}

function parseOracleThin(rest, r) {
  const at = rest.indexOf('@')
  if (at < 0) return result({ error: 'UNRECOGNIZED' })
  const cred = rest.slice(0, at)
  if (cred) {
    const slash = cred.indexOf('/')
    if (slash >= 0) r.hadPassword = true
    setIf(r.fields, 'username', slash >= 0 ? cred.slice(0, slash) : cred)
  }
  let s = rest.slice(at + 1).trim()
  if (s.startsWith('(')) return parseOracleDescriptor(s, r)
  if (startsI(s, 'tcps://')) { r.fields.oracle_tcps = true; s = s.slice(7) }
  else if (startsI(s, 'tcp://')) { r.fields.oracle_tcps = false; s = s.slice(6) }
  else if (s.startsWith('//')) s = s.slice(2)
  const q = s.indexOf('?')
  if (q >= 0) s = s.slice(0, q)
  // "h:p/servis" · "h/servis" · "h:p:SID"
  const slash = s.indexOf('/')
  if (slash >= 0) {
    const { host, port } = hostPort(s.slice(0, slash), r.notes)
    if (!host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
    r.fields.host = host
    setIf(r.fields, 'port', port)
    const service = s.slice(slash + 1).split(':')[0]
    if (service) { r.fields.database_name = service; r.fields.oracle_mode = 'SERVICE' }
  } else {
    const parts = s.split(':')
    const host = hostOf(parts[0])
    if (!host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
    r.fields.host = host
    if (parts.length >= 2) setIf(r.fields, 'port', portOf(parts[1], r.notes))
    if (parts.length >= 3 && parts[2]) { r.fields.database_name = parts[2]; r.fields.oracle_mode = 'SID' }
  }
  return { ...r, ok: true, db_type: 'ORACLE', typeSource: 'explicit' }
}

// ── Anahtar=değer (ADO.NET / Npgsql / ODP.NET) ───────────────────────────────────────────────────────────────────────

function parseKeyValue(s, r) {
  const p = params(s, ';')
  if ('password' in p || 'pwd' in p) r.hadPassword = true
  const server = p.server ?? p.datasource ?? p.address ?? p.addr ?? p.networkaddress
  if (p.host != null) {
    // Npgsql: Host=…;Port=…;Database=…;Username=…;SSL Mode=…
    const { host, port } = hostPort(p.host, r.notes)
    if (!host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
    r.fields.host = host
    setIf(r.fields, 'port', p.port ? portOf(p.port, r.notes) : port)
    setIf(r.fields, 'database_name', p.database)
    setIf(r.fields, 'username', p.username ?? p.userid ?? p.user ?? p.uid)
    const ssl = sslModeOf(p.sslmode, r.notes)
    if (ssl) r.fields.ssl_mode = ssl
    return { ...r, ok: true, db_type: 'POSTGRESQL', typeSource: 'explicit' }
  }
  if (server == null) return result({ error: 'UNRECOGNIZED', hadPassword: r.hadPassword })
  const sv = server.trim()
  if (sv.startsWith('(') || (sv.includes('/') && !sv.includes('\\') && !sv.includes(','))) {
    // ODP.NET: Data Source=h:p/servis ya da (DESCRIPTION=…)
    const res = sv.startsWith('(') ? parseOracleDescriptor(sv, r) : parseOracleThin(`@//${sv}`, r)
    if (res.ok) setIf(res.fields, 'username', p.userid ?? p.user ?? p.uid)
    return res
  }
  applyMssqlServer(server, r, { sep: ',' })
  applyMssqlProps(p, r)
  if (!r.fields.host) return result({ error: 'NO_HOST', hadPassword: r.hadPassword })
  return { ...r, ok: true, db_type: 'MSSQL', typeSource: 'explicit' }
}

// ── Türsüz: h:p/db ──────────────────────────────────────────────────────────────────────────────────────────────────

function parseBare(s, r) {
  let rest = s
  let db = ''
  const q = rest.indexOf('?')
  if (q >= 0) rest = rest.slice(0, q)
  const slash = rest.indexOf('/')
  if (slash >= 0) { db = rest.slice(slash + 1); rest = rest.slice(0, slash) }
  if (rest.includes('@') || /\s/.test(rest)) return result({ error: 'UNRECOGNIZED' })
  const { host, port } = hostPort(rest, r.notes)
  if (!host) return result({ error: 'UNRECOGNIZED' })
  r.fields.host = host
  setIf(r.fields, 'port', port)
  setIf(r.fields, 'database_name', db)
  const hinted = port != null ? PORT_TYPE[port] : null
  return { ...r, ok: true, db_type: hinted || null, typeSource: hinted ? 'port' : null }
}

/** Ayrıştırma başarısızken bile dizede parola var mı (uyarı + kutuyu temizleme için; sınırlı girdi). */
export function containsPassword(text) {
  const s = String(text || '').slice(0, MAX_CONN_STRING)
  if (/(^|[;?&\s])(password|pwd)\s*=/i.test(s)) return true
  if (/\/\/[^/@\s:]+:[^/@\s]*@/.test(s)) return true
  return /thin:[^@/\s]+\/[^@\s]+@/i.test(s)
}

/**
 * Bağlantı dizesi → `{ ok, error, db_type, typeSource ('explicit' | 'port' | null), fields, hadPassword, notes }`.
 * `fields` yalnız dizede bulunan alanları taşır; parola ASLA `fields`'e girmez.
 */
export function parseConnectionString(raw) {
  const text = String(raw ?? '')
  if (text.length > MAX_CONN_STRING) return result({ error: 'TOO_LONG', hadPassword: containsPassword(text) })
  const s = stripQuotes(text.replace(/[\r\n\t]+/g, ' ').trim())
  if (!s) return result({ error: 'EMPTY' })
  const r = result()
  let out
  if (startsI(s, 'jdbc:postgresql:')) out = parsePgUrl(s.slice(16), r)
  else if (startsI(s, 'postgresql://')) out = parsePgUrl(s.slice(11), r)
  else if (startsI(s, 'postgres://')) out = parsePgUrl(s.slice(9), r)
  else if (startsI(s, 'jdbc:sqlserver:')) out = parseMssqlJdbc(s.slice(15), r)
  else if (startsI(s, 'jdbc:jtds:sqlserver:')) out = parseJtds(s.slice(20), r)
  else if (startsI(s, 'jdbc:oracle:thin:')) out = parseOracleThin(s.slice(17), r)
  // Tanınmayan şema (mysql://, jdbc:db2: …) — sunucu adı gibi okunup çöp doldurmasın
  else if (startsI(s, 'jdbc:') || s.includes('://')) out = result({ error: 'UNRECOGNIZED' })
  else if (s.includes('=') && (s.includes(';') || /^[A-Za-z ]+=/.test(s))) out = parseKeyValue(s, r)
  else out = parseBare(s, r)
  if (!out.ok && !out.hadPassword && containsPassword(s)) out = { ...out, hadPassword: true }
  return out
}

/** Alanın hangi türe ait olduğu — türü tutmayan alan uygulanmaz. */
const FIELD_TYPE = {
  ssl_mode: 'POSTGRESQL', mssql_instance: 'MSSQL', mssql_encrypt: 'MSSQL', mssql_trust_server_certificate: 'MSSQL',
  oracle_mode: 'ORACLE', oracle_tcps: 'ORACLE',
}

/**
 * Ayrıştırma sonucunu forma uygular → `{ form, filled: [alan], typeChanged, suggestType }`.
 * Açık türlü dize (JDBC / URI / anahtar=değer) formun türünü değiştirir (port varsayılansa yeni türün varsayılanına
 * geçer); türsüz dizede porttan çıkan tür yalnız ÖNERİLİR (`suggestType`). Parola, `password_set` ve `password_clear`
 * hiçbir koşulda değişmez.
 */
export function applyConnectionString(form, parsed) {
  if (!parsed?.ok) return { form, filled: [], typeChanged: false, suggestType: null }
  let next = form
  const filled = []
  let typeChanged = false
  let suggestType = null
  if (parsed.db_type && parsed.db_type !== form.db_type) {
    if (parsed.typeSource === 'explicit') { next = changeDbType(next, parsed.db_type); typeChanged = true; filled.push('db_type') }
    else suggestType = parsed.db_type
  }
  const type = next.db_type
  const f = parsed.fields || {}
  if (type === 'ORACLE' && typeof f.oracle_tcps === 'boolean' && !!next.oracle_tcps !== f.oracle_tcps) {
    next = setOracleTcps(next, f.oracle_tcps)
    filled.push('oracle_tcps')
  }
  const patch = {}
  for (const key of FILLABLE) {
    if (key === 'db_type' || key === 'oracle_tcps' || !(key in f)) continue
    if (FIELD_TYPE[key] && FIELD_TYPE[key] !== type) continue
    const v = key === 'port' ? String(f.port) : f[key]
    patch[key] = v
    filled.push(key)
  }
  // Açık türlü dizede port yoksa ve tür değişmediyse port olduğu gibi kalır (changeDbType varsayılanı zaten taşır)
  next = { ...next, ...patch }
  const order = (k) => FILLABLE.indexOf(k)
  return { form: next, filled: [...new Set(filled)].sort((a, b) => order(a) - order(b)), typeChanged, suggestType }
}

/** Önerilen türü uygular (porttan tahmin) — port varsayılansa yeni türün varsayılanına geçer. */
export function applySuggestedType(form, type) {
  return DEFAULT_PORT[type] ? changeDbType(form, type) : form
}
