import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'

/**
 * Envanter sayfası saf modeli (2026-09-12, envanter zenginleştirme #1/#4/#5/#9/#12/#13/#6):
 * filtre · sıralama · sütun kataloğu · çakışma sezgisi · CSV ayrıştırma · görünüm (localStorage).
 * React yok — hepsi vitest'te tek başına sınanır.
 */

/** Sütun kataloğu — `fixed` her zaman, `def` varsayılan açık. Anahtar sıralama için de kullanılır. */
export const INVENTORY_COLUMNS = [
  { key: 'domain',       labelKey: 'inv.colDomain',       fixed: true,  sort: (r) => r.domain || '' },
  { key: 'port',         labelKey: 'inv.colPort',         def: true,    sort: (r) => r.port ?? 443 },
  { key: 'tier',         labelKey: 'inv.colTier',         def: true,    sort: (r) => r.tier ?? 9 },
  { key: 'team',         labelKey: 'inv.colTeam',         def: true,    sort: (r) => (r.team_name || '').toLowerCase() },
  { key: 'ug_team',      labelKey: 'inv.colUgTeam',       def: false,   sort: (r) => (r.ug_team_name || '').toLowerCase() },
  { key: 'cert',         labelKey: 'inv.colCert',         def: true,    sort: (r) => certRank(r) },
  { key: 'days',         labelKey: 'inv.colDays',         def: true,    sort: (r) => r.cert_days_remaining ?? 99999 },
  { key: 'checked',      labelKey: 'inv.colLastCheck',    def: false,   sort: (r) => r.cert_checked_at || '' },
  { key: 'group',        labelKey: 'inv.colGroup',        def: false,   sort: (r) => (r.group_name || '').toLowerCase() },
  { key: 'contacts',     labelKey: 'inv.colContacts',     def: true,    sort: (r) => filledContacts(r).length },
  { key: 'flags',        labelKey: 'inv.colFlags',        def: true,    sort: (r) => activeFlags(r).length },
  { key: 'domain_exp',   labelKey: 'inv.colDomainExpiry', def: false,   sort: (r) => r.domain_expiry || '9999' },
  { key: 'interval',     labelKey: 'inv.colInterval',     def: false,   sort: (r) => r.check_interval_hours ?? 0 },
  { key: 'tags',         labelKey: 'inv.colTags',         def: false,   sort: (r) => (r.tags || '').toLowerCase() },
  { key: 'updated',      labelKey: 'inv.colUpdated',      def: false,   sort: (r) => r.updated_at || '' },
  { key: 'active',       labelKey: 'inv.colActive',       fixed: true,  sort: (r) => (r.deleted_at ? 2 : r.active ? 0 : 1) },
]

export const FLAG_ICONS = {
  netscaler: 'server', waf_enabled: 'shield', openshift: 'cloud', ssl_pinning: 'lock', jks_keystore: 'key',
  ev_certificate: 'badge-check', internal_cert: 'building', external_vendor: 'handshake', in_use: 'circle-check',
  use_proxy: 'route', action_required: 'alert-triangle', server_update: 'refresh-cw', transferred_to_sy: 'arrow-right-left',
}

export function filledContacts(r) {
  return CONTACT_FIELDS.filter(({ key }) => (r?.[key] ?? '').toString().trim() !== '')
}
export function activeFlags(r) {
  return INVENTORY_FLAGS.filter(({ key }) => !!r?.[key])
}

/** Sertifika durumu sıralama ağırlığı: hata > kritik > yüksek > uyarı > geçerli > bilinmiyor. */
export function certRank(r) {
  const s = (r?.cert_status || '').toLowerCase()
  if (s === 'error') return 0
  if (s === 'critical') return 1
  if (s === 'high') return 2
  if (s === 'warning') return 3
  if (s === 'valid' || s === 'ok') return 5
  return 4
}

export const EMPTY_FILTERS = Object.freeze({
  q: '', team: '', ugTeam: '', tier: '', group: '', notifGroup: '', cert: '', contacts: '', flags: [], domainExp: '', hygiene: '',
})

/** URL parametreleri ← filtre (i_ öneki, PAGE_STATE_PREFIXES'te). Boş değer null → param silinir. */
export function filtersToParams(f) {
  return {
    i_q: f.q || null, i_team: f.team || null, i_ug: f.ugTeam || null, i_tier: f.tier || null, i_group: f.group || null,
    i_ng: f.notifGroup || null, i_cert: f.cert || null, i_contacts: f.contacts || null,
    i_flags: f.flags?.length ? f.flags.join(',') : null, i_dexp: f.domainExp || null, i_hy: f.hygiene || null,
  }
}
export function paramsToFilters(read) {
  return {
    q: read('i_q', ''), team: read('i_team', ''), ugTeam: read('i_ug', ''), tier: read('i_tier', ''), group: read('i_group', ''),
    notifGroup: read('i_ng', ''), cert: read('i_cert', ''), contacts: read('i_contacts', ''),
    flags: (read('i_flags', '') || '').split(',').filter(Boolean), domainExp: read('i_dexp', ''), hygiene: read('i_hy', ''),
  }
}
export function hasActiveFilter(f) {
  return !!(f.q || f.team || f.ugTeam || f.tier || f.group || f.notifGroup || f.cert || f.contacts || f.flags?.length || f.domainExp || f.hygiene)
}

function daysUntil(iso) {
  if (!iso) return null
  const d = new Date(/^\d{4}-\d{2}-\d{2}$/.test(iso) ? iso + 'T00:00:00Z' : (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : iso + 'Z'))
  if (Number.isNaN(d.getTime())) return null
  return Math.floor((d.getTime() - Date.now()) / 86400000)
}

/**
 * @param items   envanter satırları (silinmişler dâhil; durum süzgeci çağıranda)
 * @param f       filtre nesnesi (EMPTY_FILTERS şekli)
 * @param hygiene { domain → Set(codes) } — hijyen süzgeci için (opsiyonel)
 */
export function applyFilters(items, f, hygiene = null) {
  const q = (f.q || '').trim().toLowerCase()
  const flags = f.flags || []
  return items.filter((r) => {
    if (q) {
      const hay = [r.domain, r.description, r.tags, r.owner, r.purchased_by, r.group_name, r.team_name, r.ug_team_name,
        ...CONTACT_FIELDS.map(({ key }) => r[key])].filter(Boolean).join(' ').toLowerCase()
      if (!hay.includes(q)) return false
    }
    if (f.team && String(r.team_id ?? '') !== String(f.team)) return false
    if (f.ugTeam && String(r.ug_team_id ?? '') !== String(f.ugTeam)) return false
    if (f.tier === 'none' ? r.tier != null : (f.tier && String(r.tier ?? '') !== String(f.tier))) return false
    if (f.group === 'none' ? !!r.group_name : (f.group && (r.group_name || '') !== f.group)) return false
    if (f.notifGroup === 'none' ? r.notification_group_id != null : (f.notifGroup && String(r.notification_group_id ?? '') !== String(f.notifGroup))) return false
    if (f.cert) {
      const s = (r.cert_status || '').toLowerCase()
      if (f.cert === 'never' ? !!r.cert_status : f.cert === 'problem' ? !['error', 'critical', 'high', 'warning'].includes(s) : s !== f.cert) return false
    }
    if (f.contacts === 'none' && filledContacts(r).length > 0) return false
    if (f.contacts === 'partial' && !(filledContacts(r).length > 0 && filledContacts(r).length < CONTACT_FIELDS.length)) return false
    if (f.contacts === 'full' && filledContacts(r).length < CONTACT_FIELDS.length) return false
    for (const k of flags) if (!r[k]) return false
    if (f.domainExp) {
      const d = daysUntil(r.domain_expiry)
      if (f.domainExp === 'unknown' ? d != null : (d == null || d > Number(f.domainExp))) return false
    }
    if (f.hygiene) {
      const codes = hygiene?.[r.domain]
      if (!codes || !codes.has(f.hygiene)) return false
    }
    return true
  })
}

/** `sort` = 'key|asc' | 'key|desc'; bilinmeyen anahtar → girdi sırası. */
export function sortItems(items, sort) {
  if (!sort) return items
  const [key, dir] = String(sort).split('|')
  const col = INVENTORY_COLUMNS.find((c) => c.key === key)
  if (!col) return items
  const mul = dir === 'desc' ? -1 : 1
  return [...items].sort((a, b) => {
    const va = col.sort(a), vb = col.sort(b)
    if (va === vb) return (a.domain || '').localeCompare(b.domain || '')
    return (va > vb ? 1 : -1) * mul
  })
}

/**
 * Çakışma / örtüşme sezgisi (#12) — yalnız öneri, hiçbir şeyi değiştirmez:
 *  - wildcard: "*.a.com" kaydı varken "x.a.com" da ayrı kayıt
 *  - www: "a.com" ile "www.a.com" ikisi de kayıtlı
 *  - port: aynı host farklı portla iki kez (bilinçli olabilir; yalnız bilgi)
 */
export function detectOverlaps(items) {
  const live = items.filter((r) => !r.deleted_at && r.domain)
  const byDomain = new Map(live.map((r) => [r.domain.toLowerCase(), r]))
  const wildcard = [], www = [], port = []
  const seenHost = new Map()
  for (const r of live) {
    const d = r.domain.toLowerCase()
    if (d.startsWith('*.')) {
      const base = d.slice(2)
      for (const o of live) {
        const od = o.domain.toLowerCase()
        if (od !== d && od.endsWith('.' + base) && od.slice(0, -(base.length + 1)).indexOf('.') < 0) wildcard.push({ domain: od, by: d })
      }
    }
    if (d.startsWith('www.') && byDomain.has(d.slice(4))) www.push({ domain: d, by: d.slice(4) })
    const hostOnly = d.replace(/:\d+$/, '')
    const key = hostOnly
    const prev = seenHost.get(key)
    if (prev && (prev.port ?? 443) !== (r.port ?? 443)) port.push({ domain: d, by: `${prev.domain}:${prev.port ?? 443}` })
    else seenHost.set(key, r)
  }
  return { wildcard, www, port, total: wildcard.length + www.length + port.length }
}

// ── Görünüm (sütunlar, sıralama, yoğunluk) + kayıtlı görünümler (#13) — localStorage ─────────────
export const VIEW_KEY = 'inventory-view'
export const SAVED_VIEWS_KEY = 'inventory-saved-views'
export function defaultCols() { return INVENTORY_COLUMNS.filter((c) => c.fixed || c.def).map((c) => c.key) }
export function readView() {
  try { const v = JSON.parse(localStorage.getItem(VIEW_KEY) || 'null'); return v && typeof v === 'object' ? v : {} } catch { return {} }
}
export function writeView(patch) {
  try { localStorage.setItem(VIEW_KEY, JSON.stringify({ ...readView(), ...patch })) } catch { /* yoksay */ }
}
export function readSavedViews() {
  try { const v = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY) || '[]'); return Array.isArray(v) ? v : [] } catch { return [] }
}
export function writeSavedViews(list) {
  try { localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(list.slice(0, 20))) } catch { /* yoksay */ }
}

// ── CSV içe aktarma (#6): istemcide ayrıştır, başlıkları snake_case anahtarlara eşle ─────────────
export const IMPORT_COLUMNS = [
  'domain', 'port', 'team', 'ug_team', 'tier', 'active', 'group', 'description', 'owner', 'tags',
  'purchased_by', 'svc_mgmt_contact', 'app_dev_contact', 'iis_admin_contact', 'waf_admin_contact',
  ...INVENTORY_FLAGS.map(({ key }) => key), 'change_description',
]

/** RFC-4180'e yakın: tırnaklı hücre, çift tırnak kaçışı, CRLF; ayırıcı otomatik (',' ya da ';'). */
export function parseCsv(text) {
  const src = String(text || '').replace(/^\uFEFF/, '')
  const firstLine = src.split(/\r?\n/, 1)[0] || ''
  const sep = (firstLine.match(/;/g) || []).length > (firstLine.match(/,/g) || []).length ? ';' : ','
  const rows = []; let row = []; let cell = ''; let inQ = false
  for (let i = 0; i < src.length; i++) {
    const ch = src[i]
    if (inQ) {
      if (ch === '"') { if (src[i + 1] === '"') { cell += '"'; i++ } else inQ = false }
      else cell += ch
    } else if (ch === '"') inQ = true
    else if (ch === sep) { row.push(cell); cell = '' }
    else if (ch === '\n' || ch === '\r') { if (ch === '\r' && src[i + 1] === '\n') i++; row.push(cell); rows.push(row); row = []; cell = '' }
    else cell += ch
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row) }
  return rows.filter((r) => r.some((c) => c.trim() !== ''))
}

/**
 * Başlık → anahtar: snake_case anahtar birebir; yerelleştirilmiş dışa aktarma başlıkları `labels`
 * ({başlık: anahtar}) ile; eşleşmeyen başlık `unknown`'a düşer (sunucuya gitmez).
 */
export function mapCsv(rows, labels = {}) {
  if (!rows.length) return { rows: [], unknown: [], columns: [] }
  const norm = (h) => String(h || '').trim().toLowerCase().replace(/\s+/g, '_')
  const labelMap = Object.fromEntries(Object.entries(labels).map(([k, v]) => [norm(k), v]))
  const header = rows[0].map((h) => { const n = norm(h); return IMPORT_COLUMNS.includes(n) ? n : (labelMap[n] || null) })
  const unknown = rows[0].filter((h, i) => header[i] == null).map((h) => String(h).trim())
  const out = rows.slice(1).map((r) => {
    const o = {}
    header.forEach((k, i) => { if (k && r[i] != null && String(r[i]).trim() !== '') o[k] = String(r[i]).trim() })
    return o
  }).filter((o) => o.domain)
  return { rows: out, unknown, columns: header.filter(Boolean) }
}

/** İndirilebilir şablon: başlık satırı + bir örnek. */
export function importTemplateCsv() {
  const example = { domain: 'www.example.com', port: '443', team: 'Takım A', tier: '1', active: 'evet', group: '', svc_mgmt_contact: 'ops@example.com', netscaler: 'evet', waf_enabled: 'hayır' }
  return IMPORT_COLUMNS.join(',') + '\r\n' + IMPORT_COLUMNS.map((k) => example[k] ?? '').join(',') + '\r\n'
}
