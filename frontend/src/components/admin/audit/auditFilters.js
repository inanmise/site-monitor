import { toApiTime } from '../../../utils/apiTime.js'

/**
 * Denetim Logu süzgeç modeli — SAF (React yok). Ekranın süzgeç durumu, adres çubuğundaki `a_`
 * önekli anahtarlar, sunucu parametreleri ve etkin süzgeç çipleri buradan türer; tek yerde test edilir.
 *
 * <p><b>Zaman aralığı.</b> `range` hazır pencere (`1h|24h|7d|30d`) ya da `custom`. Hazır pencere
 * adreste GÖRELİ saklanır (`a_range=24h`) ve `since` İSTEK ANINDA hesaplanır — paylaşılan bağlantı
 * ertesi gün açıldığında da "son 24 saat" demek olsun (eskiden özet kartları mutlak `a_since` yazıyordu).
 * Eski bağlantılar bozulmaz: `a_range` yok ama `a_since`/`a_until` varsa özel aralık sayılır.
 *
 * <p>Sunucu sözleşmesi (`AuditController#listAudit`): `eventType` CSV (çoklu), `outcome` TEK değer,
 * `actor` LIKE, `ip` / `resourceType` / `resourceId` tam eşleşme, `since`/`until` UTC ISO.
 */

export const URL_PREFIX = 'a_'

export const EMPTY_FILTERS = Object.freeze({
  q: '', actor: '', eventType: '', outcome: '', ip: '', resourceType: '', resourceId: '',
  range: '', since: '', until: '', anomalyOnly: false,
})

const KEYS = Object.keys(EMPTY_FILTERS)

export const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']

/** Hazır pencereler (kayan): etiket anahtarı DÜZ string (i18n-used-keys kapısı görebilsin). */
export const RANGE_PRESETS = [
  { key: '1h', ms: 3_600_000, labelKey: 'audit.range.1h' },
  { key: '24h', ms: 86_400_000, labelKey: 'audit.range.24h' },
  { key: '7d', ms: 7 * 86_400_000, labelKey: 'audit.range.7d' },
  { key: '30d', ms: 30 * 86_400_000, labelKey: 'audit.range.30d' },
]
const RANGE_KEYS = new Set(['', 'custom', ...RANGE_PRESETS.map(r => r.key)])

/** Hazır görünümler — sık denetim senaryoları (eski "preset" düğmeleri; anahtarlar aynı). */
export const QUICK_VIEWS = [
  { key: 'security', filter: { outcome: 'BLOCKED' } },
  { key: 'authz', filter: { eventType: 'PERMISSION_UPDATE,PERMISSION_RESET,USER_UPDATE,TEAM_UPDATE' } },
  { key: 'failed', filter: { eventType: 'LOGIN_FAILED' } },
  { key: 'config', filter: { eventType: 'THRESHOLD_CREATE,THRESHOLD_UPDATE,CONTACT_CREATE,CONTACT_UPDATE,CONTACT_DELETE,GENERAL_SETTINGS_SAVE,SMTP_SETTINGS_SAVE,STORM_SETTINGS_SAVE' } },
]

/** Herhangi bir kaynaktan (adres, kayıtlı görünüm, çip) gelen nesneyi geçerli süzgece çevirir. */
export function normalizeFilters(raw) {
  const f = { ...EMPTY_FILTERS }
  if (raw && typeof raw === 'object') {
    for (const k of KEYS) {
      const v = raw[k]
      if (v == null) continue
      f[k] = k === 'anomalyOnly' ? (v === true || v === '1' || v === 'true') : String(v)
    }
  }
  if (!RANGE_KEYS.has(f.range)) f.range = ''
  if (!f.range && (f.since || f.until)) f.range = 'custom'
  if (f.range !== 'custom') { f.since = ''; f.until = '' }
  if (f.outcome && !OUTCOMES.includes(f.outcome)) f.outcome = ''
  f.eventType = splitTypes(f.eventType).join(',')
  return f
}

/** Adresten (`a_*`) süzgeç — ilk render'da okunur (derin bağlantı). */
export function readUrlFilters(search = typeof window !== 'undefined' ? window.location.search : '') {
  const p = new URLSearchParams(search)
  const raw = {}
  for (const k of KEYS) {
    const v = p.get(URL_PREFIX + k)
    if (v != null) raw[k] = v
  }
  return normalizeFilters(raw)
}

/** Süzgeci adrese yazar (replaceState — geçmişi şişirmez); boş değerler SİLİNİR. Diğer paramlara dokunmaz. */
export function writeUrlFilters(f) {
  try {
    const p = new URLSearchParams(window.location.search)
    for (const k of KEYS) {
      const v = f[k]
      const has = k === 'anomalyOnly' ? !!v : (v != null && v !== '')
      if (has) p.set(URL_PREFIX + k, k === 'anomalyOnly' ? '1' : String(v))
      else p.delete(URL_PREFIX + k)
    }
    const qs = p.toString()
    const next = qs ? `${window.location.pathname}?${qs}` : window.location.pathname
    if (next !== window.location.pathname + window.location.search) {
      window.history.replaceState(window.history.state, '', next + window.location.hash)
    }
  } catch { /* history hız sınırı / kısıtlı ortam — adres eşlemesi en iyi çaba */ }
}

export function splitTypes(csv) {
  return [...new Set(String(csv || '').split(',').map(s => s.trim()).filter(Boolean))]
}

/** Süzgeç → `api.admin.getAuditLogs` / `auditExportUrl` parametreleri. Hazır pencere İSTEK ANINDA çözülür. */
export function toApiParams(f, now = Date.now()) {
  const out = {
    q: f.q.trim(), actor: f.actor.trim(), eventType: f.eventType, outcome: f.outcome, ip: f.ip.trim(),
    resourceType: f.resourceType, resourceId: f.resourceId, anomalyOnly: !!f.anomalyOnly, since: '', until: '',
  }
  const preset = RANGE_PRESETS.find(r => r.key === f.range)
  if (preset) out.since = toApiTime(new Date(now - preset.ms))
  else if (f.range === 'custom') { out.since = f.since; out.until = f.until }
  return out
}

/** İki süzgeç aynı mı (kart/görünüm "etkin" durumu türetilir, ayrı state TUTULMAZ). */
export function sameFilters(a, b) {
  const x = normalizeFilters(a)
  const y = normalizeFilters(b)
  return KEYS.every(k => x[k] === y[k])
}

/** Etkin süzgeç grubu sayısı ("Filtreler (3)"). Kaynak türü + kimliği TEK grup sayılır. */
export function activeFilterCount(f) {
  let n = 0
  if (f.q.trim()) n++
  if (f.actor.trim()) n++
  if (f.ip.trim()) n++
  if (f.eventType) n++
  if (f.outcome) n++
  if (f.resourceType || f.resourceId) n++
  if (f.range) n++
  if (f.anomalyOnly) n++
  return n
}

/**
 * Etkin süzgeç çipleri: `{ key, label, clear(f) → yeni süzgeç }`. Etiketleri çağıran çözer
 * (`labels`: t + olay/sonuç etiketleyicileri) — modül i18n'e bağlanmaz.
 */
export function filterChips(f, { t, eventLabel, outcomeLabel, fmtTime }) {
  const chips = []
  const drop = (patch) => (cur) => normalizeFilters({ ...cur, ...patch })
  if (f.q.trim()) chips.push({ key: 'q', label: t('audit.chip.search', f.q.trim()), clear: drop({ q: '' }) })
  const types = splitTypes(f.eventType)
  if (types.length) {
    const first = eventLabel(types[0])
    chips.push({ key: 'eventType', clear: drop({ eventType: '' }),
      label: types.length > 1 ? t('audit.chip.events', first, types.length - 1) : t('audit.chip.event', first) })
  }
  if (f.outcome) chips.push({ key: 'outcome', label: t('audit.chip.outcome', outcomeLabel(f.outcome)), clear: drop({ outcome: '' }) })
  if (f.actor.trim()) chips.push({ key: 'actor', label: t('audit.chip.actor', f.actor.trim()), clear: drop({ actor: '' }) })
  if (f.ip.trim()) chips.push({ key: 'ip', label: t('audit.chip.ip', f.ip.trim()), clear: drop({ ip: '' }) })
  if (f.resourceType || f.resourceId) {
    const res = [f.resourceType, f.resourceId].filter(Boolean).join(':')
    chips.push({ key: 'resource', label: t('audit.chip.resource', res), clear: drop({ resourceType: '', resourceId: '' }) })
  }
  if (f.range) {
    const preset = RANGE_PRESETS.find(r => r.key === f.range)
    const text = preset ? t(preset.labelKey)
      : t('audit.chip.custom', f.since ? fmtTime(f.since) : '…', f.until ? fmtTime(f.until) : '…')
    chips.push({ key: 'range', label: t('audit.chip.range', text), clear: drop({ range: '', since: '', until: '' }) })
  }
  if (f.anomalyOnly) chips.push({ key: 'anomalyOnly', label: t('audit.anomalyOnly'), clear: drop({ anomalyOnly: false }) })
  return chips
}
