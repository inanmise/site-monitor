/**
 * "Panom" — kişisel pano + TV / duvar ekranı kipi (2026-10-11, kullanıcı kararı: "Yeni 'Panom' sekmesi"). Saf model:
 * kutu kataloğu, yerleşim (sunucu tercihi `dashboard` = {v:1, widgets:[{id,type,size}]}), düzenleme işlemleri,
 * TV sayfalaması ve kutu özetleri. Hesap yok — sunucunun mevcut uçlarından gelen sayılar yalnız sınıflanır.
 *
 * <p>Sunucu (UserPreferencesService.normalizeDashboard) yalnız BİÇİMİ denetler: en çok {@link MAX_WIDGETS} kutu, kimlik
 * `[a-z0-9-]`, tür `[a-z_]`, boyut sm|md|lg. Bilinmeyen tür (daha yeni sürümde eklenmiş kutu) ya da yetkisi olmayan kutu
 * SAKLANIR ama çizilmez — düzenleme işlemleri listenin tamamı üzerinde çalışır, gizli kutular kaybolmaz.
 */

export const MAX_WIDGETS = 24
export const SIZES = Object.freeze(['sm', 'md', 'lg'])

/**
 * Kutu kataloğu — SIRA "Kutu ekle" penceresinin sırasıdır. `perm` = görmek için gereken izin anahtarı (view), `tab` =
 * "Sayfayı aç" hedefi (yoksa düğme yok), `size` = eklenince boyutu. Kapı izinleri sunucudaki uçlarla aynı:
 * fırtına `alerts.read`, nöbet `oncall.read`, bekçi `system_health.read`, veri kalitesi `data_quality.view`.
 */
export const WIDGET_CATALOG = Object.freeze([
  { type: 'alerts_open',     size: 'md', tab: 'alerthistory', perm: null },
  { type: 'monitor_health',  size: 'md', tab: 'monitoring',   perm: null },
  { type: 'cert_expiring',   size: 'sm', tab: 'warnings',     perm: null },
  { type: 'status_services', size: 'md', tab: 'status',       perm: null },
  { type: 'storms',          size: 'sm', tab: 'storms',       perm: 'alerts.read' },
  { type: 'oncall',          size: 'sm', tab: null,           perm: 'oncall.read' },
  { type: 'activity',        size: 'md', tab: 'activity',     perm: null },
  { type: 'watchdog',        size: 'sm', tab: 'health',       perm: 'system_health.read' },
  { type: 'data_quality',    size: 'sm', tab: 'dataquality',  perm: 'data_quality.view' },
])

const BY_TYPE = new Map(WIDGET_CATALOG.map((w) => [w.type, w]))

export function catalogEntry(type) { return BY_TYPE.get(type) || null }

/**
 * Varsayılan pano — tercih yokken (ya da "Varsayılana dön"). Yetkisi olmayan kutu `canView` ile düşer. Sıra 3 sütunda
 * orta + küçük çiftleri verir (boşluksuz satırlar): alarmlar + sertifika, izleme + nöbet, hizmet + fırtına, aktivite.
 */
export const DEFAULT_TYPES = Object.freeze(['alerts_open', 'cert_expiring', 'monitor_health', 'oncall', 'status_services', 'storms', 'activity'])

/** Kutu türü bu kullanıcıya görünür mü? `canView(perm)` PermissionsProvider'dan. */
export function typeVisible(type, canView) {
  const e = BY_TYPE.get(type)
  if (!e) return false
  return !e.perm || !!(canView && canView(e.perm))
}

export function widgetId(type) { return String(type).replace(/_/g, '-') }

export function defaultLayout(canView) {
  return DEFAULT_TYPES.filter((type) => typeVisible(type, canView))
    .map((type) => ({ id: widgetId(type), type, size: BY_TYPE.get(type).size }))
}

const ID_RE = /^[a-z0-9-]{1,40}$/
const TYPE_RE = /^[a-z_]{1,40}$/

/**
 * Sunucudan gelen belge → temiz liste (biçimi bozuk satır, yinelenen kimlik ya da tür, tavan aşımı düşer). Belge yoksa
 * ya da `widgets` dizi değilse null — çağıran varsayılana düşer. Boş dizi GEÇERLİDİR (kullanıcı hepsini kaldırdı).
 */
export function normalizeLayout(doc) {
  if (!doc || typeof doc !== 'object' || !Array.isArray(doc.widgets)) return null
  const out = []
  const ids = new Set()
  const types = new Set()
  for (const w of doc.widgets) {
    if (!w || typeof w !== 'object') continue
    const id = String(w.id ?? ''), type = String(w.type ?? ''), size = String(w.size ?? '')
    if (!ID_RE.test(id) || !TYPE_RE.test(type) || !SIZES.includes(size)) continue
    if (ids.has(id) || types.has(type)) continue
    ids.add(id); types.add(type)
    out.push({ id, type, size })
    if (out.length >= MAX_WIDGETS) break
  }
  return out
}

/** Etkin liste: kayıtlı yerleşim varsa o, yoksa varsayılan. */
export function effectiveLayout(doc, canView) {
  return normalizeLayout(doc) ?? defaultLayout(canView)
}

/** Çizilecek kutular: bilinen tür + yetki. */
export function visibleWidgets(list, canView) {
  return (list || []).filter((w) => typeVisible(w.type, canView))
}

export function toDoc(list) {
  return { v: 1, widgets: (list || []).slice(0, MAX_WIDGETS).map(({ id, type, size }) => ({ id, type, size })) }
}

/** Eklenebilecek türler: görünür ve henüz panoda olmayanlar (her tür bir kez — her kutu kendi verisini çeker). */
export function addableTypes(list, canView) {
  const have = new Set((list || []).map((w) => w.type))
  return WIDGET_CATALOG.filter((e) => typeVisible(e.type, canView) && !have.has(e.type)).map((e) => e.type)
}

export function addWidget(list, type) {
  const e = BY_TYPE.get(type)
  const cur = list || []
  if (!e || cur.some((w) => w.type === type) || cur.length >= MAX_WIDGETS) return cur
  let id = widgetId(type)
  for (let i = 2; cur.some((w) => w.id === id); i++) id = `${widgetId(type)}-${i}`
  return [...cur, { id, type, size: e.size }]
}

export function removeWidget(list, id) { return (list || []).filter((w) => w.id !== id) }

export function resizeWidget(list, id, size) {
  if (!SIZES.includes(size)) return list
  return (list || []).map((w) => (w.id === id ? { ...w, size } : w))
}

/**
 * Bir sonraki/önceki GÖRÜNÜR kutuyla yer değiştir (`dir` −1 | +1). Gizli kutular araya girmez, yerlerini korur.
 * Kenardaysa liste aynen döner.
 */
export function moveWidget(list, id, dir, isVisible = () => true) {
  const cur = list || []
  const from = cur.findIndex((w) => w.id === id)
  if (from < 0 || (dir !== -1 && dir !== 1)) return cur
  let to = from + dir
  while (to >= 0 && to < cur.length && !isVisible(cur[to])) to += dir
  if (to < 0 || to >= cur.length) return cur
  const next = cur.slice()
  ;[next[from], next[to]] = [next[to], next[from]]
  return next
}

/** Sürükle-bırak: `id` kutusunu `targetId` kutusunun yerine koy (hedef ve arkası bir kaydırılır). */
export function moveWidgetTo(list, id, targetId) {
  const cur = list || []
  if (id === targetId) return cur
  const from = cur.findIndex((w) => w.id === id)
  const to = cur.findIndex((w) => w.id === targetId)
  if (from < 0 || to < 0) return cur
  const next = cur.slice()
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

export function sameLayout(a, b) {
  const x = a || [], y = b || []
  if (x.length !== y.length) return false
  return x.every((w, i) => w.id === y[i].id && w.type === y[i].type && w.size === y[i].size)
}

// ── Izgara + TV sayfalaması ─────────────────────────────────────────────────────────────────────────────────

/** Kap genişliğine göre sütun sayısı (Tailwind kap sorgularıyla aynı eşikler: @2xl 42rem, @4xl 56rem, @7xl 80rem). */
export function columnsFor(width) {
  const w = Number(width) || 0
  if (w >= 1280) return 4
  if (w >= 896) return 3
  if (w >= 672) return 2
  return 1
}

/** Boyut → sütun açıklığı (sm 1, md 2, lg tüm satır), sütun sayısıyla sınırlı. */
export function spanOf(size, cols) {
  const c = Math.max(1, cols | 0)
  if (size === 'lg') return c
  if (size === 'md') return Math.min(2, c)
  return 1
}

/**
 * TV sayfaları: ızgaranın YOĞUN (`grid-auto-flow: row dense`) yerleşimini taklit eder — her kutu sayfanın sığdığı İLK
 * satırına girer (önceki satırın sonundaki boşluk dolar); hiçbir satıra sığmıyor ve `rows` doluysa yeni sayfa. Tek kutu
 * her zaman en az bir sayfa alır. Boş liste → []. Sayfa ızgarası da `dense` — bu hesap onun satır sayısıyla aynı.
 */
export function paginate(widgets, cols, rows) {
  const c = Math.max(1, cols | 0), r = Math.max(1, rows | 0)
  const pages = []
  let page = [], used = []
  for (const w of widgets || []) {
    const s = spanOf(w.size, c)
    let i = used.findIndex((u) => u + s <= c)
    if (i < 0 && used.length >= r && page.length) { pages.push(page); page = []; used = [] }
    if (i < 0) { used.push(0); i = used.length - 1 }
    used[i] += s
    page.push(w)
  }
  if (page.length) pages.push(page)
  return pages
}

/** TV satır yüksekliği tahmini (px): kutu başlığı + büyük sayı + kutucuklar + kısa liste rahat sığsın. */
export const TV_ROW_PX = 440
const TV_CHROME_PX = 96   // üst çubuk + iç boşluk

/** TV ekranında sayfa başına satır: yüksekliğe göre 1–3 (800 px → 1, 1080 px → 2, 1440 px → 3). */
export function rowsFor(height) {
  const h = Number(height) || 0
  return Math.min(3, Math.max(1, Math.floor((h - TV_CHROME_PX) / TV_ROW_PX)))
}

/** Yüzde metni dile göre: TR "%95,5", EN "95.5%". */
export function formatPct(v, lang, digits = 1) {
  if (v == null || !Number.isFinite(Number(v))) return '—'
  const num = Number(v).toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR', { maximumFractionDigits: digits })
  return lang === 'en' ? `${num}%` : `%${num}`
}

export const TV_INTERVALS = Object.freeze([0, 15, 30, 60, 120])
export const TV_DEFAULT = Object.freeze({ interval: 30 })
const TV_KEY = 'sm.mydash.tv'

/** TV ayarı (dönüş aralığı) — izleyici başına kolaylık, yalnız bu tarayıcıda (eşitlenmez). */
export function readTvSettings() {
  try {
    const raw = JSON.parse(localStorage.getItem(TV_KEY) || 'null')
    const interval = Number(raw?.interval)
    return { interval: TV_INTERVALS.includes(interval) ? interval : TV_DEFAULT.interval }
  } catch {
    return { ...TV_DEFAULT }
  }
}

export function writeTvSettings(s) {
  try { localStorage.setItem(TV_KEY, JSON.stringify({ interval: s.interval })) } catch { /* depolama kapalı */ }
}

// ── Kutu özetleri (sunucu yanıtı → ekran) ────────────────────────────────────────────────────────────────────

const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)

/** Açık alarmlar (`/api/me/open-alerts`): seviye toplamları + en yeni öğeler (tüm türlerden, yeniden eskiye). */
export function alertsSummary(data, limit = 6) {
  const tabs = data && typeof data.tabs === 'object' && data.tabs ? data.tabs : {}
  const levels = { critical: 0, high: 0, warning: 0, other: 0 }
  let unacked = 0
  const items = []
  for (const [family, tab] of Object.entries(tabs)) {
    for (const k of Object.keys(levels)) levels[k] += n(tab?.levels?.[k])
    unacked += n(tab?.unacked)
    for (const it of Array.isArray(tab?.items) ? tab.items : []) items.push({ ...it, family })
  }
  items.sort((a, b) => String(b.created_at || '').localeCompare(String(a.created_at || '')) || n(b.id) - n(a.id))
  const total = n(data?.total)
  return { total, ...levels, unacked, items: items.slice(0, limit), tone: levels.critical > 0 ? 'danger' : total > 0 ? 'warning' : 'success' }
}

/** İzleme sağlığı (`/api/monitoring/overview`): toplamlar + sorunlu türler önce. */
export function monitorSummary(ov) {
  const t = ov?.totals || {}
  const active = n(t.active), down = n(t.down), stale = n(t.stale), unknown = n(t.unknown)
  const healthy = Math.max(0, active - down - stale - unknown)
  const types = (Array.isArray(ov?.types) ? ov.types : [])
    .filter((x) => n(x.active) > 0)
    .map((x) => ({ type: x.type, active: n(x.active), down: n(x.down), stale: n(x.stale), openAlerts: n(x.open_alerts) }))
    .sort((a, b) => (b.down - a.down) || (b.stale - a.stale) || String(a.type).localeCompare(String(b.type)))
  const rate = t.success_rate_window == null ? null : Number(t.success_rate_window)
  return {
    active, down, stale, unknown, healthy, paused: n(t.paused), successRate: Number.isFinite(rate) ? rate : null,
    healthyPct: active > 0 ? Math.round((healthy * 1000) / active) / 10 : null,
    types, tone: down > 0 ? 'danger' : stale > 0 ? 'warning' : 'success',
  }
}

/** Sertifikalar (`/api/stats`): bitiş pencereleri + en acil alan adları (kritik/süresi dolmuş). */
export function certSummary(s, limit = 5) {
  const expired = n(s?.expired), exp7 = n(s?.expiring_in_7_days), exp30 = n(s?.expiring_in_30_days)
  const urgent = [...new Set([...(s?.expired_domains || []), ...(s?.critical_domains || [])].map(String))]
  return {
    total: n(s?.total_certificates), valid: n(s?.valid_count), expired, exp7, exp30, errors: n(s?.error_count),
    urgent: urgent.slice(0, limit), urgentMore: Math.max(0, urgent.length - limit),
    tone: expired > 0 || exp7 > 0 ? 'danger' : exp30 > 0 ? 'warning' : 'success',
  }
}

export const STATE_RANK = Object.freeze({ major_outage: 5, partial_outage: 4, degraded: 3, maintenance: 2, operational: 1, no_data: 0 })
const PROBLEM = new Set(['major_outage', 'partial_outage', 'degraded'])

/** Hizmet durumu (`/api/status-page`): görülebilen hizmetlerin durum sayıları + sorunlu hizmetler (en ağır önce). */
export function statusSummary(sp, limit = 5) {
  const services = Array.isArray(sp?.services) ? sp.services : []
  const counts = { operational: 0, degraded: 0, partial_outage: 0, major_outage: 0, maintenance: 0, no_data: 0 }
  for (const s of services) if (s && counts[s.state] != null) counts[s.state] += 1
  const problems = services.filter((s) => PROBLEM.has(s?.state))
    .sort((a, b) => (STATE_RANK[b.state] - STATE_RANK[a.state]) || String(a.since || '').localeCompare(String(b.since || '')))
  const worst = services.reduce((acc, s) => (STATE_RANK[s?.state] > STATE_RANK[acc] ? s.state : acc), services.length ? 'operational' : 'no_data')
  return {
    total: services.length, counts, problems: problems.slice(0, limit), problemsMore: Math.max(0, problems.length - limit), worst,
    tone: worst === 'major_outage' || worst === 'partial_outage' ? 'danger' : worst === 'degraded' ? 'warning' : services.length ? 'success' : 'muted',
  }
}

/** Fırtınalar (`/api/monitoring/storm/status`): fırtınadaki / eşiğe yakın takımlar. */
export function stormSummary(st, limit = 5) {
  const t = st?.totals || {}
  const teams = (Array.isArray(st?.teams) ? st.teams : [])
    .filter((x) => x?.status === 'STORM' || x?.status === 'NEAR')
    .sort((a, b) => (a.status === b.status ? 0 : a.status === 'STORM' ? -1 : 1) || n(b.window_targets) - n(a.window_targets))
  const storming = n(t.storming)
  return {
    enabled: st?.settings?.enabled !== false, storming, near: n(t.near), open: n(t.open_storms),
    teams: teams.slice(0, limit), teamsMore: Math.max(0, teams.length - limit),
    tone: storming > 0 ? 'danger' : n(t.near) > 0 ? 'warning' : 'success',
  }
}

/** Nöbetçiler: `/api/oncall/current` satırları + takım adları (dizin) → takım adına göre sıralı. */
export function oncallRows(current, teams) {
  const names = new Map((teams || []).map((x) => [String(x.id), x.name]))
  return (Array.isArray(current) ? current : [])
    .map((r) => ({ ...r, team_name: names.get(String(r.team_id)) || null }))
    .sort((a, b) => String(a.team_name || '').localeCompare(String(b.team_name || ''), 'tr', { sensitivity: 'base', numeric: true }))
}

/** Bekçi genel durumu → ton (watchdogModel.OVERALL_TONE ile aynı eşleme). */
export const WATCHDOG_TONE = Object.freeze({ UP: 'success', DEGRADED: 'warning', DOWN: 'danger', DISABLED: 'muted', UNKNOWN: 'muted' })

/** Veri kalitesi bandı → ton. */
export function qualityTone(band) {
  if (band === 'GOOD' || band === 'EXCELLENT') return 'success'
  if (band === 'NEEDS_ATTENTION') return 'warning'
  if (band === 'POOR') return 'danger'
  return 'muted'
}
