/**
 * Alarm iletimi — SAF model (2026-11-05, kullanıcı isteği: "bir takım istediği bir izleme tipi için veya belirli bir izleme
 * için oluşacak alarmların başka bir takıma da gitmesi için başka takımı veya o takımdan belirli üyeleri ek olarak
 * ekleyebilmeli").
 *
 * Kural sunucuda (`AlertForwardService` / `AlertForwardController`); burada yalnız sunucu satırları camelCase'e çevrilir,
 * durum / özet / süzgeç / sıralama hesaplanır ve kayıt gövdeleri kurulur. Tür anahtarları Java `MonitorTypeCatalog.ORDER`
 * ile aynı sıra (`alertForwardModel.test.js` Java kaynağını okur).
 *
 * Bilinmeyen ≠ sorunsuz: sunucunun gönderemediği adres sayısı (`null`) "okunamadı" olarak taşınır, 0 sayılmaz.
 */
import { changedFields } from '../../../utils/changedFields.js'
import { foldSearchText } from '../../../utils/monitorFilters.js'

/** Kapsam: sahip takımın tüm izlemeleri | seçilen türler | seçilen izlemeler. */
export const SCOPE_KINDS = Object.freeze(['ALL', 'TYPES', 'MONITORS'])
/** Alıcı: takımın tamamı (takım adresi + push kuralları) | takımın seçilen üyeleri. */
export const RECIPIENT_KINDS = Object.freeze(['TEAM', 'MEMBERS'])
/** İzleme türleri — Java `MonitorTypeCatalog.ORDER` (cert = envanter kaydı: sertifika + erişilebilirlik alarmları). */
export const FORWARD_TYPES = Object.freeze(['cert', 'domain', 'http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted', 'dbquery'])
/** Sunucu durumları — öncelik sırası sunucuda (paused > expired > recipient_inactive > no_targets > no_recipients > active). */
export const FORWARD_STATES = Object.freeze(['active', 'paused', 'expired', 'recipient_inactive', 'no_targets', 'no_recipients'])
/** "Dikkat" kutusuna sayılan durumlar (iletim kayıtlı ama kimseye ulaşmıyor / ulaşamaz). */
export const ATTENTION_STATES = Object.freeze(['recipient_inactive', 'no_targets', 'no_recipients'])
/** Durum süzgeci değerleri (URL `g_fstate`, KPI kutuları). */
export const STATE_FILTERS = Object.freeze(['active', 'paused', 'expired', 'attention'])
/** Kapsam / alıcı türü süzgeç değerleri (URL `g_fscope`, `g_frcpt`). */
export const SCOPE_FILTERS = Object.freeze(['all', 'types', 'monitors'])
export const RECIPIENT_FILTERS = Object.freeze(['team', 'members'])
/** Sunucu sınırları — yanıtta `limits` yoksa bunlar (sözleşme). */
export const DEFAULT_LIMITS = Object.freeze({ perTeam: 100, members: 50, monitors: 200, note: 500 })
/** Satırda görünen en çok kapsam çipi; fazlası "+N daha" ile açılır (açılınca HEPSİ). */
export const SCOPE_CHIP_PREVIEW = 4
/** "Yakında bitiyor" eşiği (gün). */
export const EXPIRING_DAYS = 7

/** Tür → i18n etiket anahtarı (izleme menüsünün adları; cert kendi adıyla). */
export const TYPE_LABEL_KEYS = Object.freeze({
  cert: 'fwd.type.cert',
  domain: 'nav.domainmon',
  http: 'nav.http',
  ping: 'nav.ping',
  port: 'nav.port',
  dns: 'nav.dns',
  keyword: 'nav.keyword',
  page: 'nav.page',
  pagespeed: 'nav.pagespeed',
  scripted: 'nav.scripted',
  dbquery: 'nav.dbquery',
})

const DAY_MS = 86_400_000

function str(v) { return typeof v === 'string' && v.trim() ? v.trim() : null }
function bool(v) { return typeof v === 'boolean' ? v : null }
function int(v) { return Number.isInteger(v) && v >= 0 ? v : null }
const idKey = (v) => (v == null ? '' : String(v))

/**
 * Sayılı metin: sayı 1 ise `<anahtar>.one` (EN tekil — "1 monitor"), değilse anahtarın kendisi. `.one` anahtarı iki dilde
 * de vardır (TR'de aynı metin; parite kapısı) — Aktivite sayfasının kalıbı.
 */
export function plural(t, key, n, ...rest) {
  return Number(n) === 1 ? t(`${key}.one`, n, ...rest) : t(key, n, ...rest)
}

/** Tür etiketi (bilinmeyen tür ham anahtarıyla — kaybolmaz). */
export function typeLabel(type, t) {
  const key = TYPE_LABEL_KEYS[type]
  return key ? t(key) : t('fwd.type.unknown', String(type ?? '—'))
}

/** "<tür>:<id>" (cert için id envanter kimliği). */
export function monitorKey(m) { return `${m?.type ?? ''}:${idKey(m?.id)}` }

/** Yanıt gövdesi (`{success, data}` ya da düz). */
export function payloadOf(res) {
  if (!res || typeof res !== 'object') return null
  return res.data && typeof res.data === 'object' && !Array.isArray(res.data) ? res.data : res
}

/** Sunucu sınırları → görünüm. */
export function normalizeLimits(raw) {
  const r = raw && typeof raw === 'object' ? raw : {}
  const pos = (v, d) => (Number.isInteger(v) && v > 0 ? v : d)
  return {
    perTeam: pos(r.per_team, DEFAULT_LIMITS.perTeam),
    members: pos(r.members, DEFAULT_LIMITS.members),
    monitors: pos(r.monitors, DEFAULT_LIMITS.monitors),
    note: pos(r.note, DEFAULT_LIMITS.note),
  }
}

function normalizeMonitor(raw) {
  const m = raw && typeof raw === 'object' ? raw : {}
  return {
    type: str(m.type) || '',
    id: m.id ?? null,
    name: str(m.name),
    target: str(m.target),
    active: bool(m.active),
    // true = silinmiş, false = var, null = izleme dizini okunamadı (bilinmiyor — "silinmiş" DENMEZ)
    missing: bool(m.missing),
  }
}

function normalizeMember(raw) {
  const m = raw && typeof raw === 'object' ? raw : {}
  return {
    userId: m.user_id ?? null,
    displayName: str(m.display_name),
    active: bool(m.active),
    inTeam: bool(m.in_team),
  }
}

/** Sunucu satırı → görünüm satırı. */
export function normalizeForward(raw) {
  const r = raw && typeof raw === 'object' ? raw : {}
  const types = Array.isArray(r.scope_types) ? [...new Set(r.scope_types.filter((x) => typeof x === 'string' && x))] : []
  const order = (x) => { const i = FORWARD_TYPES.indexOf(x); return i < 0 ? FORWARD_TYPES.length : i }
  const f = {
    id: r.id ?? null,
    version: Number.isInteger(r.version) ? r.version : null,
    ownerTeamId: r.owner_team_id ?? null,
    ownerTeamName: str(r.owner_team_name),
    ownerTeamActive: bool(r.owner_team_active),
    scopeKind: SCOPE_KINDS.includes(r.scope_kind) ? r.scope_kind : null,
    scopeTypes: types.sort((a, b) => order(a) - order(b) || a.localeCompare(b)),
    scopeMonitors: (Array.isArray(r.scope_monitors) ? r.scope_monitors : []).map(normalizeMonitor),
    recipientKind: RECIPIENT_KINDS.includes(r.recipient_kind) ? r.recipient_kind : null,
    recipientTeamId: r.recipient_team_id ?? null,
    recipientTeamName: str(r.recipient_team_name),
    recipientTeamActive: bool(r.recipient_team_active),
    recipientMembers: (Array.isArray(r.recipient_members) ? r.recipient_members : []).map(normalizeMember),
    // null = sunucu sayamadı ("okunamadı") — 0 ile karışmaz
    recipientAddressCount: int(r.recipient_address_count),
    enabled: r.enabled !== false,
    expiresOn: str(r.expires_on),
    note: str(r.note),
    createdAt: str(r.created_at),
    createdBy: str(r.created_by),
    updatedAt: str(r.updated_at),
    updatedBy: str(r.updated_by),
    canEdit: r.can_edit === true,
  }
  f.state = FORWARD_STATES.includes(r.state) ? r.state : deriveState(f)
  return f
}

/** Bugün (İstanbul) "yyyy-MM-dd". */
export function todayIstanbul(now = Date.now()) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit' })
    .format(new Date(now))
}

function dayNumber(ymd) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(ymd || '')
  return m ? Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) / DAY_MS : null
}

/** Bitişe kalan gün (bitiş günü İstanbul gününün SONU; bugün son gün = 0). Süresiz → null. */
export function daysLeft(expiresOn, now = Date.now()) {
  const end = dayNumber(expiresOn)
  const today = dayNumber(todayIstanbul(now))
  return end == null || today == null ? null : Math.round(end - today)
}

/** Sunucu durumu yoksa (eski / eksik yanıt) aynı öncelikle türetilir. */
export function deriveState(f, now = Date.now()) {
  if (!f) return 'active'
  if (f.enabled === false) return 'paused'
  const d = daysLeft(f.expiresOn, now)
  if (d != null && d < 0) return 'expired'
  if (f.recipientTeamActive === false) return 'recipient_inactive'
  if (f.scopeKind === 'MONITORS' && f.scopeMonitors.length > 0 && f.scopeMonitors.every((m) => m.missing === true)) return 'no_targets'
  if (f.recipientKind === 'MEMBERS' && f.recipientMembers.length > 0
    && !f.recipientMembers.some((m) => m.active !== false && m.inTeam !== false)) return 'no_recipients'
  return 'active'
}

/**
 * Dikkat: iletim kayıtlı ama alarm ulaşmıyor (alıcı takım pasif, izleme kalmadı, alıcı kalmadı) ya da etkin iletimin
 * hiç e-posta adresi yok (yalnız push gider). Adres sayısı okunamadıysa (null) dikkat SAYILMAZ — bilinmiyor.
 */
export function needsAttention(f) {
  if (!f) return false
  if (ATTENTION_STATES.includes(f.state)) return true
  return f.state === 'active' && f.recipientAddressCount === 0
}

/** Yakında bitiyor (etkin, bitişe ≤ 7 gün). */
export function expiringSoon(f, now = Date.now()) {
  if (!f || f.state !== 'active') return false
  const d = daysLeft(f.expiresOn, now)
  return d != null && d >= 0 && d <= EXPIRING_DAYS
}

/** KPI özeti. "Etkin" = gönderen iletimler (adres 0 olsa da push gider); "Dikkat" ayrıca sayılır (örtüşebilir). */
export function summarize(list) {
  const out = { total: 0, active: 0, paused: 0, expired: 0, attention: 0 }
  for (const f of Array.isArray(list) ? list : []) {
    out.total++
    if (f.state === 'active') out.active++
    if (f.state === 'paused') out.paused++
    if (f.state === 'expired') out.expired++
    if (needsAttention(f)) out.attention++
  }
  return out
}

/** Durum süzgeci: `active|paused|expired|attention` ya da sunucu durumlarından biri. */
export function matchesState(f, state) {
  if (!state) return true
  if (state === 'attention') return needsAttention(f)
  return f.state === state
}

/** Aranan metinler: takımlar, üyeler, izleme adları / hedefleri, tür adları, not, ekleyen. */
export function searchValues(f, t) {
  const out = [f.ownerTeamName, f.recipientTeamName, f.note, f.createdBy]
  for (const m of f.recipientMembers) out.push(m.displayName)
  for (const m of f.scopeMonitors) { out.push(m.name); out.push(m.target) }
  if (t) for (const ty of f.scopeTypes) out.push(typeLabel(ty, t))
  return out
}

/**
 * Süzgeç: `q` (Türkçe katlamalı serbest metin), `team` (sahip takım), `scope` (all|types|monitors), `rcpt` (team|members),
 * `state` (bkz. {@link matchesState}). `t` verilirse tür adlarında da aranır.
 */
export function filterForwards(list, { q = '', team = '', scope = '', rcpt = '', state = '' } = {}, t = null) {
  const needle = foldSearchText(q).trim()
  return (Array.isArray(list) ? list : []).filter((f) => {
    if (team && idKey(f.ownerTeamId) !== String(team)) return false
    if (scope && (f.scopeKind || '').toLowerCase() !== scope) return false
    if (rcpt && (f.recipientKind || '').toLowerCase() !== rcpt) return false
    if (!matchesState(f, state)) return false
    if (needle && !searchValues(f, t).some((v) => v != null && foldSearchText(v).includes(needle))) return false
    return true
  })
}

const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/** Sıralama: sahip takım A→Z (Türkçe), sonra alıcı takım, sonra kimlik. */
export function sortForwards(list) {
  return [...(Array.isArray(list) ? list : [])].sort((a, b) =>
    collator.compare(a.ownerTeamName || '', b.ownerTeamName || '')
    || collator.compare(a.recipientTeamName || '', b.recipientTeamName || '')
    || (Number(a.id) || 0) - (Number(b.id) || 0))
}

/** Sahip takım seçeneği listesi: iletimi olan takımlar + yönetebildiği takımlar (Takımlar menüsünden gelen boş takım da). */
export function ownerTeamOptions(list, manageable) {
  const m = new Map()
  for (const f of Array.isArray(list) ? list : []) {
    if (f.ownerTeamId != null && !m.has(idKey(f.ownerTeamId))) m.set(idKey(f.ownerTeamId), f.ownerTeamName || idKey(f.ownerTeamId))
  }
  for (const tm of Array.isArray(manageable) ? manageable : []) {
    if (tm?.id != null && !m.has(idKey(tm.id))) m.set(idKey(tm.id), tm.name || idKey(tm.id))
  }
  return [...m.entries()].sort((a, b) => collator.compare(a[1], b[1]))
}

/** İzleme öğesinin görünen adı: silinmiş → "Silinmiş izleme (tür)"; dizin okunamadı ve ad yok → "Durumu okunamayan izleme". */
export function monitorLabel(m, t) {
  if (m?.missing === true) return t('fwd.scope.missingMonitor', typeLabel(m.type, t))
  if (m?.name || m?.target) return m.name || m.target
  return m?.missing == null ? t('fwd.scope.unknownMonitor', typeLabel(m?.type, t)) : monitorKey(m)
}

/**
 * Kapsam çipleri: ALL → tek "Tüm izlemeler"; TYPES → tür adları; MONITORS → izleme adları (silinmiş izleme "silinmiş …",
 * dizin okunamadıysa "durumu okunamayan" — silinmiş sayılmaz). `{ key, kind, type, label, missing, unknown, paused }`.
 */
export function scopeChips(f, t) {
  if (!f) return []
  if (f.scopeKind === 'ALL') return [{ key: 'all', kind: 'all', type: null, label: t('fwd.scope.allShort'), missing: false, unknown: false, paused: false }]
  if (f.scopeKind === 'TYPES') {
    return f.scopeTypes.map((ty) => ({ key: `t:${ty}`, kind: 'type', type: ty, label: typeLabel(ty, t), missing: false, unknown: false, paused: false }))
  }
  if (f.scopeKind === 'MONITORS') {
    return f.scopeMonitors.map((m) => ({
      key: `m:${monitorKey(m)}`,
      kind: 'monitor',
      type: m.type,
      label: monitorLabel(m, t),
      missing: m.missing === true,
      unknown: m.missing == null,
      paused: m.active === false && m.missing !== true,
    }))
  }
  return []
}

/** Kapsamın tek satırlık özeti ("Tüm izlemeler" / "HTTP, Ping" / "3 izleme"). */
export function scopeSummary(f, t) {
  if (!f) return '—'
  if (f.scopeKind === 'ALL') return t('fwd.scope.allShort')
  if (f.scopeKind === 'TYPES') return f.scopeTypes.length ? f.scopeTypes.map((ty) => typeLabel(ty, t)).join(', ') : '—'
  if (f.scopeKind === 'MONITORS') return plural(t, 'fwd.scope.monitorsN', f.scopeMonitors.length)
  return '—'
}

/** Alıcının tek satırlık özeti ("Mobil — tüm takım" / "Mobil — 3 üye"). */
export function recipientSummary(f, t) {
  if (!f) return '—'
  const team = f.recipientTeamName || t('fwd.unknownTeam')
  return f.recipientKind === 'MEMBERS'
    ? plural(t, 'fwd.rcpt.membersSummary', f.recipientMembers.length, team)
    : t('fwd.rcpt.teamSummary', team)
}

// ── Form ─────────────────────────────────────────────────────────────────────────────────────────────

/** Yeni iletim formu. */
export function emptyForm(ownerTeamId = null) {
  return {
    ownerTeamId: ownerTeamId ?? null,
    scopeKind: 'ALL',
    scopeTypes: [],
    scopeMonitors: [],   // [{ type, id, name, target, active, missing }]
    recipientKind: 'TEAM',
    recipientTeamId: null,
    recipientUserIds: [],
    enabled: true,
    expiresOn: '',
    note: '',
    grantView: false,
  }
}

/** Düzenleme formu. */
export function formOf(f) {
  return {
    ownerTeamId: f.ownerTeamId,
    scopeKind: f.scopeKind || 'ALL',
    scopeTypes: [...f.scopeTypes],
    scopeMonitors: f.scopeMonitors.map((m) => ({ ...m })),
    recipientKind: f.recipientKind || 'TEAM',
    recipientTeamId: f.recipientTeamId,
    recipientUserIds: f.recipientMembers.map((m) => m.userId).filter((v) => v != null),
    enabled: f.enabled !== false,
    expiresOn: f.expiresOn || '',
    note: f.note || '',
    grantView: false,
  }
}

const sortedTypes = (list) => [...new Set(list || [])].sort((a, b) => FORWARD_TYPES.indexOf(a) - FORWARD_TYPES.indexOf(b))
const sortedIds = (list) => [...new Set((list || []).filter((v) => v != null).map(String))].sort()
const sortedMonitorKeys = (list) => [...new Set((list || []).map(monitorKey))].sort()

/** Karşılaştırma / gövde biçimi (dizi alanları sıralı; görünmeyen kapsam / alıcı alanı boş). */
function bodyShape(form) {
  const kind = form.scopeKind
  return {
    scope_kind: kind,
    scope_types: kind === 'TYPES' ? sortedTypes(form.scopeTypes) : [],
    scope_monitors: kind === 'MONITORS' ? sortedMonitorKeys(form.scopeMonitors) : [],
    recipient_kind: form.recipientKind,
    recipient_team_id: form.recipientTeamId ?? null,
    recipient_user_ids: form.recipientKind === 'MEMBERS' ? sortedIds(form.recipientUserIds) : [],
    enabled: form.enabled !== false,
    expires_on: form.expiresOn || '',
    note: (form.note || '').trim(),
  }
}

/** "tür:id" anahtarını formdaki öğeyle gövde nesnesine çevirir (`{type, id}`; id sayı ya da metin aynen). */
function monitorRefs(form, keys) {
  const byKey = new Map((form.scopeMonitors || []).map((m) => [monitorKey(m), m]))
  return keys.map((k) => { const m = byKey.get(k); return { type: m.type, id: m.id } })
}

/** Kimlik değerleri formdaki asıl değerleriyle (opak kimlik metni / global yöneticide sayı — ASLA Number()'a çevrilmez). */
function userRefs(form, ids) {
  const byKey = new Map((form.recipientUserIds || []).map((v) => [String(v), v]))
  return ids.map((k) => byKey.get(k) ?? k)
}

/** POST gövdesi — tüm alanlar (yeni kayıt). */
export function createPayload(form) {
  const s = bodyShape(form)
  const out = {
    owner_team_id: form.ownerTeamId,
    scope_kind: s.scope_kind,
    scope_types: s.scope_types,
    scope_monitors: monitorRefs(form, s.scope_monitors),
    recipient_kind: s.recipient_kind,
    recipient_team_id: s.recipient_team_id,
    recipient_user_ids: userRefs(form, s.recipient_user_ids),
  }
  if (s.expires_on) out.expires_on = s.expires_on
  if (s.note) out.note = s.note
  if (form.grantView) out.grant_view = true
  return out
}

/** Düzenlemede alıcılar değişti mi (yeni alıcı takım ya da listeye eklenen üye) — bilgi postası / erişim kutusu için. */
export function hasNewRecipients(original, form) {
  if (!original) return true
  if (idKey(original.recipientTeamId) !== idKey(form.recipientTeamId)) return true
  if (original.recipientKind !== form.recipientKind) return true
  if (form.recipientKind !== 'MEMBERS') return false
  const before = new Set(original.recipientMembers.map((m) => idKey(m.userId)))
  return (form.recipientUserIds || []).some((v) => !before.has(idKey(v)))
}

/**
 * PUT gövdesi — YALNIZ değişen alanlar (eşzamanlı düzenleme diğerini ezmesin); değişiklik yoksa BOŞ nesne (istek atılmaz).
 * `version` biliniyorsa (ve bir şey değiştiyse) eklenir. `grant_view` yalnız yeni alıcı varken anlamlıdır.
 */
export function updatePayload(original, form) {
  const before = bodyShape(formOf(original))
  const after = bodyShape(form)
  const diff = changedFields(after, before)
  if (Object.keys(diff).length === 0) return {}
  const out = { ...diff }
  if ('scope_monitors' in out) out.scope_monitors = monitorRefs(form, after.scope_monitors)
  if ('recipient_user_ids' in out) out.recipient_user_ids = userRefs(form, after.recipient_user_ids)
  // Alıcı türü / takımı değişince kapsamlı alan da birlikte gider (sunucu yeni alıcıyı tek seferde doğrulasın)
  if (('recipient_kind' in out || 'recipient_team_id' in out) && form.recipientKind === 'MEMBERS' && !('recipient_user_ids' in out)) {
    out.recipient_user_ids = userRefs(form, after.recipient_user_ids)
  }
  if ('recipient_kind' in out && !('recipient_team_id' in out)) out.recipient_team_id = after.recipient_team_id
  if ('scope_kind' in out) {
    if (form.scopeKind === 'TYPES' && !('scope_types' in out)) out.scope_types = after.scope_types
    if (form.scopeKind === 'MONITORS' && !('scope_monitors' in out)) out.scope_monitors = monitorRefs(form, after.scope_monitors)
  }
  if (form.grantView && hasNewRecipients(original, form)) out.grant_view = true
  if (original.version != null) out.version = original.version
  return out
}

/**
 * Form doğrulaması (istemci ilk süzgeci; asıl kural sunucuda) → alan → `[i18n anahtarı, …parametreler]`.
 * Düzenlemede değişmeyen alan yeniden doğrulanmaz (silinmiş bir izlemenin öğesi kalabilir — sözleşme).
 */
export function formErrors(form, { isNew = true, limits = DEFAULT_LIMITS, original = null } = {}) {
  const e = {}
  const changed = isNew || !original ? null : bodyShape(formOf(original))
  const now = bodyShape(form)
  const touched = (k) => changed == null || JSON.stringify(changed[k]) !== JSON.stringify(now[k])
  if (isNew && (form.ownerTeamId == null || form.ownerTeamId === '')) e.ownerTeamId = ['fwd.err.owner']
  if (!SCOPE_KINDS.includes(form.scopeKind)) e.scopeKind = ['fwd.err.scope']
  if (form.scopeKind === 'TYPES' && (touched('scope_types') || touched('scope_kind'))) {
    if (now.scope_types.length === 0) e.scopeTypes = ['fwd.err.typesRequired']
  }
  if (form.scopeKind === 'MONITORS' && (touched('scope_monitors') || touched('scope_kind'))) {
    if (now.scope_monitors.length === 0) e.scopeMonitors = ['fwd.err.monitorsRequired']
    else if (now.scope_monitors.length > limits.monitors) e.scopeMonitors = ['fwd.err.monitorsMax', limits.monitors]
  }
  if (!RECIPIENT_KINDS.includes(form.recipientKind)) e.recipientKind = ['fwd.err.recipientKind']
  if (form.recipientTeamId == null || form.recipientTeamId === '') e.recipientTeamId = ['fwd.err.recipientTeam']
  else if (idKey(form.recipientTeamId) === idKey(form.ownerTeamId)) e.recipientTeamId = ['fwd.err.sameTeam']
  if (form.recipientKind === 'MEMBERS' && (touched('recipient_user_ids') || touched('recipient_kind') || touched('recipient_team_id'))) {
    if (now.recipient_user_ids.length === 0) e.recipientUserIds = ['fwd.err.membersRequired']
    else if (now.recipient_user_ids.length > limits.members) e.recipientUserIds = ['fwd.err.membersMax', limits.members]
  }
  if (now.note.length > limits.note) e.note = ['fwd.err.note', limits.note]
  return e
}

/** Sunucu alan adı → form alanı (400 `VALIDATION_FAILED` + `field`). */
export const FIELD_MAP = Object.freeze({
  owner_team_id: 'ownerTeamId',
  scope_kind: 'scopeKind',
  scope_types: 'scopeTypes',
  scope_monitors: 'scopeMonitors',
  recipient_kind: 'recipientKind',
  recipient_team_id: 'recipientTeamId',
  recipient_user_ids: 'recipientUserIds',
  expires_on: 'expiresOn',
  note: 'note',
  grant_view: 'grantView',
  enabled: 'enabled',
})

/**
 * Derin bağ `g_fnew`: "<tür>:<id>" ya da "cert:<alanadı>" → `{ type, id, domain }`. Bilinmeyen tür / boş → null.
 * Kimlik rakamsa sayı (izleme listesindeki kimlikle eşleşsin), değilse metin; cert'te rakam dışı değer alan adıdır.
 */
export function parseNewRef(raw) {
  const s = typeof raw === 'string' ? raw.trim() : ''
  const i = s.indexOf(':')
  if (i <= 0 || i === s.length - 1 || s.length > 300) return null
  const type = s.slice(0, i).toLowerCase()
  const rest = s.slice(i + 1).trim()
  if (!FORWARD_TYPES.includes(type) || !rest) return null
  if (/^[0-9]+$/.test(rest)) return { type, id: Number(rest), domain: null }
  if (type === 'cert') return { type, id: null, domain: rest.toLowerCase() }
  return { type, id: rest, domain: null }
}

/** Derin bağın izleme listesindeki karşılığı (cert'te alan adıyla da). */
export function findMonitorForRef(items, ref) {
  if (!ref) return null
  for (const m of Array.isArray(items) ? items : []) {
    if (m.type !== ref.type) continue
    if (ref.id != null && idKey(m.id) === idKey(ref.id)) return m
    if (ref.domain && foldSearchText(m.name || m.target) === foldSearchText(ref.domain)) return m
  }
  return null
}

/** İzleme seçici listesi (`/alert-forwards/monitors`) → görünüm; tür sırası + ad (Türkçe) sıralı. */
export function normalizeMonitorOptions(items) {
  const list = (Array.isArray(items) ? items : []).map(normalizeMonitor).filter((m) => m.type && m.id != null)
  const order = (x) => { const i = FORWARD_TYPES.indexOf(x); return i < 0 ? FORWARD_TYPES.length : i }
  return list.sort((a, b) => order(a.type) - order(b.type) || collator.compare(a.name || a.target || '', b.name || b.target || ''))
}

/** İzleme seçici süzgeci: tür + Türkçe katlamalı arama (ad / hedef). */
export function filterMonitorOptions(items, { type = '', q = '' } = {}) {
  const needle = foldSearchText(q).trim()
  return (Array.isArray(items) ? items : []).filter((m) => {
    if (type && m.type !== type) return false
    if (needle && ![m.name, m.target].some((v) => v && foldSearchText(v).includes(needle))) return false
    return true
  })
}

/** Takım üyeleri (`/teams/{id}/members`) → seçilebilir AKTİF üyeler, ada göre. */
export function memberOptions(members) {
  return (Array.isArray(members) ? members : [])
    .filter((u) => u && u.active !== false && u.id != null)
    .map((u) => ({
      userId: u.id,
      displayName: str(u.display_name) || str(u.username) || String(u.id),
      username: str(u.username),
      title: str(u.title),
      department: str(u.department),
      email: str(u.email),
    }))
    .sort((a, b) => collator.compare(a.displayName, b.displayName))
}

/** Üye arama süzgeci (ad, kullanıcı adı, unvan, birim, e-posta). */
export function filterMemberOptions(list, q) {
  const needle = foldSearchText(q).trim()
  if (!needle) return list
  return list.filter((m) => [m.displayName, m.username, m.title, m.department, m.email]
    .some((v) => v && foldSearchText(v).includes(needle)))
}

/** Yanıttaki `grant` / `info` özetinin bildirim metinleri (toast) — `{ ok: [...], warn: [...] }`. */
export function resultNotes(data, t) {
  const ok = []
  const warn = []
  const info = data?.info
  if (info && typeof info === 'object') {
    const emails = Number(info.emails) || 0
    const push = Number(info.push) || 0
    if (emails + push > 0) ok.push(t('fwd.toast.info', emails, push))
    else warn.push(t('fwd.toast.infoNone'))
  }
  const g = data?.grant
  if (g && typeof g === 'object' && g.requested) {
    if (Number(g.created) > 0) ok.push(plural(t, 'fwd.toast.grantCreated', Number(g.created)))
    if (Number(g.existing) > 0) ok.push(plural(t, 'fwd.toast.grantExisting', Number(g.existing)))
    if (Number(g.failed) > 0) warn.push(str(g.message) || plural(t, 'fwd.toast.grantFailed', Number(g.failed)))
  }
  return { ok, warn }
}
