/**
 * 7/24 KONSOLU (2026-10-04) — saf model (React'siz, test edilebilir).
 *
 * Sunucu sözleşmesi (`GET /api/noc/console`, NocConsoleService): `{ window, generated_at, kpis: { open, open_critical,
 * noc_sent, not_called, called_last_hour }, facets: { teams: [{id, name, count}], types: {aile: n}, levels: {...} },
 * items: [...], total, page (0 tabanlı), size, truncated, max_rows, can_write }`. Satır: `{ id, alert_type, family, level,
 * noc_level, domain, message, created_at, resolved, resolved_at, acknowledged, storm_id, team_id, team_name, ug_team_id,
 * ug_team_name, monitor: { type, tab, id, name }, channels: { email_sent, email_failed, webhook_sent, push_sent,
 * push_failed, noc }, noc: { sent_at, via_storm, groups } | null, call_count, last_call: {...} | null, can_call }`.
 *
 * URL anahtarları `n_` önekli (PAGE_STATE_PREFIXES — sekme değişince temizlenir): `n_view` (console|coverage) ·
 * `n_cw` pencere · `n_cteam` · `n_clvl` · `n_ctype` · `n_cnoc` · `n_ccall` · `n_cst` · `n_cq` · `n_cpage` / `n_cps`.
 */

export const WINDOWS = ['1h', '24h', '7d']
export const DEFAULT_WINDOW = '24h'
export const LEVELS = ['CRITICAL', 'HIGH', 'WARNING']
export const NOC_VALUES = ['sent', 'not_sent']
export const CALLED_VALUES = ['yes', 'no']
export const STATE_VALUES = ['open', 'resolved']
export const SIZE_OPTIONS = [25, 50, 100]
export const DEFAULT_SIZE = 25
/** Otomatik tazeleme aralığı — sunucu belleği 15 sn; 30 sn'de bir sessiz yenileme (gizli sekmede durur). */
export const REFRESH_MS = 30_000

export const URL_KEYS = Object.freeze({
  window: 'n_cw', team: 'n_cteam', level: 'n_clvl', type: 'n_ctype', noc: 'n_cnoc', called: 'n_ccall', state: 'n_cst', q: 'n_cq',
})
export const FILTER_DEFAULTS = Object.freeze({ window: DEFAULT_WINDOW, team: '', level: '', type: '', noc: '', called: '', state: '', q: '' })

/** URL → süzgeçler; tanınmayan değer varsayılana düşer. */
export function filtersFromUrl(read) {
  const f = { ...FILTER_DEFAULTS }
  for (const [k, key] of Object.entries(URL_KEYS)) {
    const v = read(key, '')
    if (v) f[k] = String(v)
  }
  if (!WINDOWS.includes(f.window)) f.window = DEFAULT_WINDOW
  if (f.level && !LEVELS.includes(f.level)) f.level = ''
  if (f.noc && !NOC_VALUES.includes(f.noc)) f.noc = ''
  if (f.called && !CALLED_VALUES.includes(f.called)) f.called = ''
  if (f.state && !STATE_VALUES.includes(f.state)) f.state = ''
  if (f.team && !/^\d+$/.test(f.team)) f.team = ''
  return f
}

/** Süzgeçler → URL eşlemesi (varsayılan değer yazılmaz). */
export function filtersToUrl(f) {
  const out = {}
  for (const [k, key] of Object.entries(URL_KEYS)) {
    const v = f?.[k]
    out[key] = v && v !== FILTER_DEFAULTS[k] ? String(v) : null
  }
  return out
}

/** İstek parametreleri — `page` 1 tabanlı arayüz sayfası (sunucu 0 tabanlı). */
export function consoleParams(f, page = 1, size = DEFAULT_SIZE, fresh = false) {
  const p = { window: f.window || DEFAULT_WINDOW, page: Math.max(0, (Number(page) || 1) - 1), size }
  if (f.team) p.team_id = f.team
  if (f.level) p.level = f.level
  if (f.type) p.type = f.type
  if (f.noc) p.noc = f.noc
  if (f.called) p.called = f.called
  if (f.state) p.state = f.state
  if (f.q && f.q.trim()) p.q = f.q.trim()
  if (fresh) p.fresh = true
  return p
}

/**
 * KPI kartı → süzgeç yaması. Kart TOGGLE: etkin kart yeniden tıklanınca süzgeç kalkar. "Son bir saatte arandı" kartının
 * SAYISI kurum genelindeki son bir saatlik aramalardır; tıklanınca arama kaydı olan alarmlar listelenir (satır süzgecinde
 * arama zamanı yok — en yeni arama satırda görünür).
 */
export const TILE_FILTERS = Object.freeze({
  open: { state: 'open' },
  noc_sent: { noc: 'sent' },
  not_called: { state: 'open', noc: 'sent', called: 'no' },
  called_last_hour: { called: 'yes' },
})
const CLEAR = { state: '', noc: '', called: '' }

/** Süzgeçlere göre etkin KPI kartı (yoksa null) — en özelden genele. */
export function activeTile(f) {
  if (f.state === 'open' && f.noc === 'sent' && f.called === 'no') return 'not_called'
  if (f.noc === 'sent' && !f.state && !f.called) return 'noc_sent'
  if (f.state === 'open' && !f.noc && !f.called) return 'open'
  if (f.called === 'yes' && !f.noc && !f.state) return 'called_last_hour'
  return null
}

/** Kart tıklaması → süzgeç yaması (etkin karta tıklamak süzgeci kaldırır). */
export function tilePatch(key, f) {
  if (!TILE_FILTERS[key]) return null
  return activeTile(f) === key ? { ...CLEAR } : { ...CLEAR, ...TILE_FILTERS[key] }
}

/** Etkin süzgeç çipleri — [{ key, value, patch }] (pencere çip değildir; her zaman seçicide görünür). */
export function activeChips(f) {
  const out = []
  for (const k of ['team', 'level', 'type', 'noc', 'called', 'state', 'q']) {
    if (f[k]) out.push({ key: k, value: f[k], patch: { [k]: '' } })
  }
  return out
}

/** Derin bağlantılar — alarm detayı (Alarm Geçmişi) ve izleme (SSL → Pano + alan adı; diğerleri tür sekmesi + kimlik). */
export function alertHref(row) {
  return `?tab=alerthistory&alert=${encodeURIComponent(row?.id ?? '')}`
}
export function monitorTarget(row) {
  const m = row?.monitor || {}
  if (m.type === 'SSL' && row?.domain) return { tab: 'dashboard', params: { domain: row.domain, open: 'cert' } }
  if (m.tab && m.id != null) return { tab: m.tab, params: { monitor: String(m.id) } }
  return null
}
export function monitorHref(row) {
  const target = monitorTarget(row)
  if (!target) return null
  const qs = new URLSearchParams({ tab: target.tab, ...target.params })
  return `?${qs.toString()}`
}

/** Satırın kanal özeti — rozet sırası sabit: takım e-postası, webhook, kişisel push, 7/24. */
export function channelSummary(row) {
  const c = row?.channels || {}
  return [
    { key: 'email', sent: Number(c.email_sent) || 0, failed: Number(c.email_failed) || 0 },
    { key: 'webhook', sent: Number(c.webhook_sent) || 0, failed: 0 },
    { key: 'push', sent: Number(c.push_sent) || 0, failed: Number(c.push_failed) || 0 },
    { key: 'noc', sent: c.noc ? 1 : 0, failed: 0 },
  ]
}

/** Arama formunun beklediği uyarı biçimi (Alarm Geçmişi satırıyla aynı adlar). */
export function toCallAlert(row) {
  if (!row) return null
  return {
    id: row.id, domain: row.domain, alert_type: row.alert_type, alert_level: row.level,
    created_at: row.created_at, team_id: row.team_id, team_name: row.team_name,
  }
}

/** Satırın "durumu" — liste anahtarı + ekran okuyucu: aranmadı (7/24'e gitti, açık, arama yok) en acil. */
export function rowUrgency(row) {
  if (row?.resolved) return 'resolved'
  if (row?.noc && !(Number(row.call_count) > 0)) return 'needs_call'
  if (Number(row?.call_count) > 0) return 'called'
  return 'open'
}

/** Tür süzgeci seçenekleri — yüzey sayılarından (aile anahtarı → sayı), sayıya göre azalan. */
export function typeOptions(facets) {
  const types = facets?.types && typeof facets.types === 'object' ? facets.types : {}
  return Object.entries(types).map(([key, count]) => ({ key, count: Number(count) || 0 })).sort((a, b) => b.count - a.count || a.key.localeCompare(b.key))
}
