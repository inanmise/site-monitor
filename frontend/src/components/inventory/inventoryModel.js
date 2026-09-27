import { INVENTORY_FLAGS } from '../../utils/inventoryFlags.js'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import { mergeNewDefaultCols } from '../../utils/columnPrefs.js'

/**
 * Envanter sayfası saf modeli (2026-09-12, envanter zenginleştirme #1/#4/#5/#9/#12/#13/#6):
 * filtre · sıralama · sütun kataloğu · çakışma sezgisi · CSV ayrıştırma · görünüm (localStorage).
 * React yok — hepsi vitest'te tek başına sınanır.
 */

/** Sütun kataloğu — `fixed` her zaman, `def` varsayılan açık. Anahtar sıralama için de kullanılır. */
export const INVENTORY_COLUMNS = [
  { key: 'domain',       labelKey: 'inv.colDomain',       fixed: true,  sort: (r) => r.domain || '' },
  { key: 'port',         labelKey: 'inv.colPort',         def: false,   sort: (r) => r.port ?? 443 },   // 2026-09-27: 443 dışı port alan adı hücresinde rozet; ayrı sütun isteğe bağlı
  { key: 'tier',         labelKey: 'inv.colTier',         def: true,    sort: (r) => r.tier ?? 9 },
  { key: 'team',         labelKey: 'inv.colTeam',         def: true,    sort: (r) => (r.team_name || '').toLowerCase() },
  { key: 'ug_team',      labelKey: 'inv.colUgTeam',       def: false,   sort: (r) => (r.ug_team_name || '').toLowerCase() },
  { key: 'cert',         labelKey: 'inv.colCert',         def: false,   sort: (r) => certRank(r) },   // 2026-09-27: durum rozeti varsayılan olarak "Kalan gün" hücresinde; ayrı sütun isteğe bağlı
  { key: 'days',         labelKey: 'inv.colDays',         def: true,    sort: (r) => r.cert_days_remaining ?? 99999 },
  { key: 'checked',      labelKey: 'inv.colLastCheck',    def: true,    sort: (r) => r.cert_checked_at || '' },   // 2026-09-27 varsayılan açık
  { key: 'group',        labelKey: 'inv.colGroup',        def: false,   sort: (r) => (r.group_name || '').toLowerCase() },   // 2026-09-27: grup çipi alan adı hücresinde; ayrı sütun isteğe bağlı
  { key: 'contacts',     labelKey: 'inv.colContacts',     def: true,    sort: (r) => filledContacts(r).length },
  { key: 'flags',        labelKey: 'inv.colFlags',        def: false,   sort: (r) => activeFlags(r).length },   // 2026-09-27: bayraklar çekmecede; sütun isteğe bağlı
  { key: 'domain_exp',   labelKey: 'inv.colDomainExpiry', def: false,   sort: (r) => r.domain_expiry || '9999' },
  { key: 'interval',     labelKey: 'inv.colInterval',     def: false,   sort: (r) => r.check_interval_hours ?? 0 },
  { key: 'tags',         labelKey: 'inv.colTags',         def: false,   sort: (r) => (r.tags || '').toLowerCase() },
  { key: 'platform',     labelKey: 'inv.colPlatform',     def: true,    sort: (r) => (r.platform || 'zz').toLowerCase() },   // 2026-09-22
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
  q: '', team: '', ugTeam: '', tier: '', group: '', notifGroup: '', cert: '', contacts: '', flags: [], domainExp: '', hygiene: '', proxy: '',   // proxy: '' | 'on' | 'off' (2026-09-22)
  // Kolon süzgeçleri (2026-09-22, kullanıcı isteği): tablo başlığının altındaki satır. Aynı nesnede yaşar ki URL/kayıtlı
  // görünüm/Temizle hepsini birlikte taşısın. team/tier/group/cert/contacts/domainExp/ugTeam kolonları üstteki süzgeçleri PAYLAŞIR.
  domain: '', port: '', days: '', checked: '', flag: '', interval: '', tag: '', updated: '', active: '', platform: '',
})

/** URL parametreleri ← filtre (i_ öneki, PAGE_STATE_PREFIXES'te). Boş değer null → param silinir. */
export function filtersToParams(f) {
  return {
    i_q: f.q || null, i_team: f.team || null, i_ug: f.ugTeam || null, i_tier: f.tier || null, i_group: f.group || null,
    i_ng: f.notifGroup || null, i_cert: f.cert || null, i_contacts: f.contacts || null,
    i_flags: f.flags?.length ? f.flags.join(',') : null, i_dexp: f.domainExp || null, i_hy: f.hygiene || null, i_proxy: f.proxy || null,
    i_dom: f.domain || null, i_port: f.port || null, i_days: f.days || null, i_chk: f.checked || null, i_flag: f.flag || null,
    i_int: f.interval || null, i_tag: f.tag || null, i_upd: f.updated || null, i_act: f.active || null, i_plat: f.platform || null,
  }
}
export function paramsToFilters(read) {
  return {
    q: read('i_q', ''), team: read('i_team', ''), ugTeam: read('i_ug', ''), tier: read('i_tier', ''), group: read('i_group', ''),
    notifGroup: read('i_ng', ''), cert: read('i_cert', ''), contacts: read('i_contacts', ''),
    flags: (read('i_flags', '') || '').split(',').filter(Boolean), domainExp: read('i_dexp', ''), hygiene: read('i_hy', ''), proxy: read('i_proxy', ''),
    domain: read('i_dom', ''), port: read('i_port', ''), days: read('i_days', ''), checked: read('i_chk', ''), flag: read('i_flag', ''),
    interval: read('i_int', ''), tag: read('i_tag', ''), updated: read('i_upd', ''), active: read('i_act', ''), platform: read('i_plat', ''),
  }
}
export function hasActiveFilter(f) {
  return !!(f.q || f.team || f.ugTeam || f.tier || f.group || f.notifGroup || f.cert || f.contacts || f.flags?.length || f.domainExp || f.hygiene || f.proxy
    || f.domain || f.port || f.days || f.checked || f.flag || f.interval || f.tag || f.updated || f.active || f.platform)
}

export function daysUntil(iso) {
  if (!iso) return null
  const d = new Date(/^\d{4}-\d{2}-\d{2}$/.test(iso) ? iso + 'T00:00:00Z' : (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : iso + 'Z'))
  if (Number.isNaN(d.getTime())) return null
  return Math.floor((d.getTime() - Date.now()) / 86400000)
}

// ── Özet kartları (2026-09-27 yeniden tasarım) — her kart mevcut bir süzgece eşlenir, YENİ süzgeç anahtarı yoktur ──
// `status` = durum süzgeci (aktif/pasif/silinmiş), `filters` = süzgeç nesnesine yama. Tek doğruluk kaynağı: kart
// basınca yama uygulanır, kartın "basılı" hâli de AYNI yamadan türetilir (URL/kayıtlı görünümle gelen süzgeç de kartı
// basılı gösterir). Sıra ekrandaki sırayla aynı.
export const TILES = [
  { key: 'total' },
  { key: 'active',     status: 'active' },
  { key: 'valid',      filters: { cert: 'valid' } },
  { key: 'expiring',   filters: { days: '30' } },
  { key: 'expired',    filters: { days: 'expired' } },
  { key: 'errors',     filters: { cert: 'error' } },
  { key: 'noContacts', filters: { contacts: 'none' } },
  { key: 'noPlatform', filters: { platform: 'none' } },
  { key: 'tier1',      filters: { tier: '1' } },
  { key: 'inactive',   status: 'inactive' },
  { key: 'deleted',    status: 'deleted' },
]

/** Kart sayaçları — canlı (silinmemiş) kayıtlar üstünden; `deleted` çöp kutusu. Kesin kural: `applyFilters` ile aynı. */
export function tileCounts(items) {
  const live = (items || []).filter((r) => !r.deleted_at)
  const c = { total: live.length, active: 0, inactive: 0, valid: 0, expiring: 0, expired: 0, errors: 0, noContacts: 0, noPlatform: 0, tier1: 0, deleted: (items || []).length - live.length }
  for (const r of live) {
    if (r.active) c.active++; else c.inactive++
    const s = (r.cert_status || '').toLowerCase()
    const d = r.cert_days_remaining
    if (s === 'valid') c.valid++   // `cert: 'valid'` süzgeciyle birebir (kart sayısı = basınca görünen satır)
    if (s === 'error') c.errors++
    if (d != null && d < 0) c.expired++
    if (d != null && d >= 0 && d <= 30) c.expiring++
    if (filledContacts(r).length === 0) c.noContacts++
    if (!r.platform) c.noPlatform++
    if (String(r.tier ?? '') === '1') c.tier1++
  }
  return c
}

/** Basılı kart: durum süzgeci ya da süzgeç nesnesi kartın yamasını birebir taşıyorsa (ilk eşleşen). */
export function activeTile(statusFilter, filters) {
  for (const tile of TILES) {
    if (tile.status) { if (statusFilter === tile.status) return tile.key; continue }
    if (tile.filters && Object.entries(tile.filters).every(([k, v]) => (filters?.[k] ?? '') === v)) return tile.key
  }
  return null
}

/**
 * Kart basışı → { statusFilter, filters }. Basılı karta yeniden basmak yamasını geri alır; başka karta basmak öncekini
 * geri alıp yenisini uygular (kartlar arasında TEK seçim); `total` her kart yamasını kaldırır.
 */
export function applyTile(key, statusFilter, filters) {
  const cur = activeTile(statusFilter, filters)
  let status = statusFilter
  let next = { ...filters }
  const undo = (tile) => {
    if (!tile) return
    if (tile.status) status = 'default'
    for (const k of Object.keys(tile.filters || {})) next[k] = EMPTY_FILTERS[k] ?? ''
  }
  undo(TILES.find((tl) => tl.key === cur))
  if (key === 'total') {
    // "Toplam": durum kartı VE eşleşen her süzgeç kartı birlikte kalkar (ikisi aynı anda basılı olabilir: URL/görünüm)
    for (const tile of TILES) {
      if (tile.status) { if (status === tile.status) status = 'default'; continue }
      if (tile.filters && Object.entries(tile.filters).every(([k, v]) => (next[k] ?? '') === v)) undo(tile)
    }
    return { statusFilter: status, filters: next }
  }
  if (key === cur) return { statusFilter: status, filters: next }
  const tile = TILES.find((tl) => tl.key === key)
  if (tile?.status) status = tile.status
  if (tile?.filters) next = { ...next, ...tile.filters }
  return { statusFilter: status, filters: next }
}

/** Faset sayaçları: DURUM süzgecinden geçmiş satırlardan (kolon seçenekleriyle aynı kaynak) → { tier, platform, group, tag, cert, team }. */
export function facetCounts(items) {
  const bump = (m, k) => { if (k == null || k === '') return; m[k] = (m[k] || 0) + 1 }
  const out = { tier: {}, platform: {}, group: {}, tag: {}, cert: {}, team: {} }
  for (const r of items || []) {
    bump(out.tier, r.tier != null ? String(r.tier) : 'none')
    bump(out.platform, r.platform || 'none')
    bump(out.group, r.group_name || 'none')
    for (const tg of new Set(tagList(r.tags).map((x) => x.toLowerCase()))) bump(out.tag, tg)
    const s = (r.cert_status || '').toLowerCase()
    bump(out.cert, s ? (s === 'ok' ? 'valid' : s) : 'never')
    if (['error', 'critical', 'high', 'warning'].includes(s)) bump(out.cert, 'problem')
    bump(out.team, r.team_id != null ? String(r.team_id) : '')
  }
  return out
}

/** Etkin süzgeç çipleri: boş olmayan her süzgeç anahtarı (arama dâhil; bayraklar tek tek). Etiketleme bileşende. */
export function activeFilterChips(f) {
  const out = []
  for (const [k, v] of Object.entries(f || {})) {
    if (k === 'flags') { for (const flag of v || []) out.push({ key: 'flags', value: flag }); continue }
    if (v != null && v !== '') out.push({ key: k, value: String(v) })
  }
  return out
}

/** Bir çipi kaldır → yeni süzgeç nesnesi (bayrakta yalnız o bayrak düşer). */
export function removeFilterChip(f, chip) {
  if (chip.key === 'flags') return { ...f, flags: (f.flags || []).filter((x) => x !== chip.value) }
  return { ...f, [chip.key]: EMPTY_FILTERS[chip.key] ?? '' }
}

/** ISO zaman damgasından bu yana geçen saat; yoksa/bozuksa null. */
function ageHours(iso) {
  if (!iso) return null
  const d = new Date(/[zZ]$|[+-]\d{2}:?\d{2}$/.test(iso) ? iso : iso + 'Z')
  if (Number.isNaN(d.getTime())) return null
  return (Date.now() - d.getTime()) / 3600000
}
function tagList(csv) { return String(csv || '').split(',').map((x) => x.trim()).filter(Boolean) }

/**
 * Kolon süzgeçlerinin seçenek kaynağı (2026-09-22): DURUM süzgecinden geçmiş satırlardan türer (o an tabloda olabilecekler),
 * böylece açılır listelerde hiç eşleşmeyecek değer yoktur. Saf; etiketleme bileşende (i18n).
 */
export function columnFilterOptions(items) {
  const ports = new Set(), teams = new Map(), ugTeams = new Map(), groups = new Set(), intervals = new Set(), tags = new Map(), platforms = new Set()
  for (const r of items) {
    ports.add(String(r.port ?? 443))
    if (r.team_id != null) teams.set(String(r.team_id), r.team_name || String(r.team_id))
    if (r.ug_team_id != null) ugTeams.set(String(r.ug_team_id), r.ug_team_name || String(r.ug_team_id))
    if (r.group_name) groups.add(r.group_name)
    if (r.check_interval_hours != null) intervals.add(String(r.check_interval_hours))
    if (r.platform) platforms.add(r.platform)
    for (const tg of tagList(r.tags)) { const k = tg.toLowerCase(); if (!tags.has(k)) tags.set(k, tg) }
  }
  const byLabel = (a, b) => a.label.localeCompare(b.label)
  return {
    ports: [...ports].sort((a, b) => Number(a) - Number(b)),
    teams: [...teams].map(([value, label]) => ({ value, label })).sort(byLabel),
    ugTeams: [...ugTeams].map(([value, label]) => ({ value, label })).sort(byLabel),
    groups: [...groups].sort((a, b) => a.localeCompare(b)),
    intervals: [...intervals].sort((a, b) => Number(a) - Number(b)),
    tags: [...tags.values()].sort((a, b) => a.localeCompare(b)),
    platforms: [...platforms].sort(),
  }
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
      const hay = [r.domain, r.description, r.tags, r.owner, r.purchased_by, r.platform, r.platform_detail, r.group_name, r.team_name, r.ug_team_name,
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
    // Vekil süzgeci (2026-09-22): 'on' → yalnız "Proxy üzerinden kontrol et = Evet"; 'off' → Hayır (null da Hayır)
    if (f.proxy === 'on' && !r.use_proxy) return false
    if (f.proxy === 'off' && !!r.use_proxy) return false
    if (f.domainExp) {
      const d = daysUntil(r.domain_expiry)
      if (f.domainExp === 'unknown' ? d != null : (d == null || d > Number(f.domainExp))) return false
    }
    // ── Kolon süzgeçleri (2026-09-22) ──
    if (f.domain && !(r.domain || '').toLowerCase().includes(f.domain.trim().toLowerCase())) return false
    if (f.port && String(r.port ?? 443) !== String(f.port)) return false
    if (f.days) {
      const d = r.cert_days_remaining
      if (f.days === 'unknown' ? d != null : f.days === 'expired' ? !(d != null && d < 0) : (d == null || d < 0 || d > Number(f.days))) return false
    }
    if (f.checked) {
      const age = ageHours(r.cert_checked_at)
      if (f.checked === 'never' ? age != null : (age == null || age > Number(f.checked))) return false
    }
    if (f.flag && !r[f.flag]) return false
    if (f.interval && (f.interval === 'global' ? r.check_interval_hours != null : String(r.check_interval_hours ?? '') !== String(f.interval))) return false
    if (f.tag && !tagList(r.tags).some((x) => x.toLowerCase() === f.tag.toLowerCase())) return false
    if (f.updated) {
      const age = ageHours(r.updated_at)
      if (age == null || age > Number(f.updated)) return false
    }
    if (f.active === 'yes' && !r.active) return false
    if (f.platform === 'none' ? !!r.platform : (f.platform && (r.platform || '') !== f.platform)) return false
    if (f.active === 'no' && !!r.active) return false
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

/**
 * Bu düzeltmeden (2026-09-22) önce yazılmış görünümlerde `colsKnown` yok — o tarihte katalogda
 * olan anahtarlar. DONDURULMUŞ: yeni sütun buraya EKLENMEZ, yoksa eski kullanıcı onu hiç görmez
 * ("platform" sütununun başına gelen buydu).
 */
export const LEGACY_KNOWN_COLS = Object.freeze([
  'domain', 'port', 'tier', 'team', 'ug_team', 'cert', 'days', 'checked', 'group',
  'contacts', 'flags', 'domain_exp', 'interval', 'tags', 'updated', 'active',
])
export function colKeys() { return INVENTORY_COLUMNS.map((c) => c.key) }
/** Kayıtlı/adlı görünümün sütunları + kullanıcının hiç görmediği yeni varsayılanlar (bkz. columnPrefs.js). */
export function restoreCols(cols, known) {
  if (!Array.isArray(cols) || cols.length === 0) return defaultCols()
  return mergeNewDefaultCols(cols, INVENTORY_COLUMNS, known || LEGACY_KNOWN_COLS)
}
export function readCols() { const v = readView(); return restoreCols(v.cols, v.colsKnown) }
/** Sütun seçimini yazarken o anki katalog da saklanır ki sonraki yeni sütun "yeni" sayılsın. */
export function writeCols(cols) { writeView({ cols, colsKnown: colKeys() }) }
export function readSavedViews() {
  try { const v = JSON.parse(localStorage.getItem(SAVED_VIEWS_KEY) || '[]'); return Array.isArray(v) ? v : [] } catch { return [] }
}
export function writeSavedViews(list) {
  try { localStorage.setItem(SAVED_VIEWS_KEY, JSON.stringify(list.slice(0, 20))) } catch { /* yoksay */ }
}

// ── CSV içe aktarma (#6): istemcide ayrıştır, başlıkları snake_case anahtarlara eşle ─────────────
export const IMPORT_COLUMNS = [
  'domain', 'port', 'team', 'ug_team', 'tier', 'active', 'group', 'description', 'owner', 'tags',
  'purchased_by', 'platform', 'platform_detail', 'svc_mgmt_contact', 'app_dev_contact', 'iis_admin_contact', 'waf_admin_contact',
  ...INVENTORY_FLAGS.map(({ key }) => key), 'change_description',
  // 7/24 izleme ekibi (2026-09-27): noc_notify (evet/hayır/1/0), noc_groups (grup ADLARI `;`/`|`; `-` = varsayılana dön)
  'noc_notify', 'noc_groups',
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
