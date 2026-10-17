/**
 * "Panom" — kişisel pano + TV / duvar ekranı kipi (2026-10-11, kullanıcı kararı: "Yeni 'Panom' sekmesi"). Saf model:
 * kutu kataloğu, yerleşim (sunucu tercihi `dashboard` = {v:1, widgets:[{id,type,size}]}), düzenleme işlemleri,
 * TV sayfalaması ve kutu özetleri. Hesap yok — sunucunun mevcut uçlarından gelen sayılar yalnız sınıflanır.
 *
 * <p>Sunucu (UserPreferencesService.normalizeDashboard) yalnız BİÇİMİ denetler: en çok {@link MAX_WIDGETS} kutu, kimlik
 * `[a-z0-9-]`, tür `[a-z_]`, boyut sm|md|lg. Bilinmeyen tür (daha yeni sürümde eklenmiş kutu) ya da yetkisi olmayan kutu
 * SAKLANIR ama çizilmez — düzenleme işlemleri listenin tamamı üzerinde çalışır, gizli kutular kaybolmaz.
 *
 * <p>2026-10-13: kutu başına isteğe bağlı görünüm `style: 'stat'` (Grafana "stat panel": tüm zemin durum renginde, ortada
 * büyük sayı). Sunucu yalnız `stat`ı saklar; bilinmeyen değeri düşürür. TV / duvar ekranında renkli panel VARSAYILAN.
 */

import { zonedMs } from '../../utils/relativeTime.js'
import { STAT_DEFS, STAT_PRESET_DEFS } from './statDefs.js'
import { TV_DEFAULT_DENSITY, normalizeDensity } from './tvLayout.js'

export const MAX_WIDGETS = 24
export const SIZES = Object.freeze(['sm', 'md', 'lg'])

/**
 * Kutu kataloğu — SIRA "Kutu ekle" penceresinin sırasıdır. `perm` = görmek için gereken izin anahtarı (view), `tab` =
 * "Sayfayı aç" hedefi (yoksa düğme yok), `size` = eklenince boyutu. Kapı izinleri sunucudaki uçlarla aynı:
 * açık alarmlar ve fırtına `alerts.read`, izleme sağlığı `monitoring.read`, nöbet `oncall.read`, bekçi `system_health.read`,
 * veri kalitesi `data_quality.view` (2026-10-12: alarm / izleme kutuları da uçlarının iznini ister).
 */
export const WIDGET_CATALOG = Object.freeze([
  // 7/24 Komuta (2026-10-13): durum lambası + aranmayı bekleyenler önde. Konsol kutusu 7/24 konsolunu açabilenlere
  // ({@link NOC_CONSOLE}: 7/24 izleme ekibi üyesi ya da global görüntüleyici — NocPage ile aynı kural).
  { type: 'noc_light',       size: 'sm', tab: 'alerthistory', perm: 'alerts.read' },
  { type: 'noc_uncalled',    size: 'md', tab: 'noc',          perm: ['alerts.read', '@noc_console'] },
  { type: 'alerts_open',     size: 'md', tab: 'alerthistory', perm: 'alerts.read' },
  { type: 'monitor_health',  size: 'md', tab: 'monitoring',   perm: 'monitoring.read' },
  { type: 'cert_expiring',   size: 'sm', tab: 'warnings',     perm: null },
  { type: 'status_services', size: 'md', tab: 'status',       perm: null },
  { type: 'storms',          size: 'sm', tab: 'storms',       perm: 'alerts.read' },
  { type: 'oncall',          size: 'sm', tab: null,           perm: 'oncall.read' },
  { type: 'activity',        size: 'md', tab: 'activity',     perm: null },
  { type: 'watchdog',        size: 'sm', tab: 'health',       perm: 'system_health.read' },
  { type: 'data_quality',    size: 'sm', tab: 'dataquality',  perm: 'data_quality.view' },
  { type: 'maintenance',     size: 'sm', tab: 'maintenance',  perm: 'maintenance.view' },
  { type: 'incidents_open',  size: 'md', tab: 'incident-history', perm: 'incidents.view' },
  // Grafana tarzı ızgaralar (2026-10-13): renkli kutucuk / ısı haritası / matris — kendileri zaten renkli panel olduğundan
  // "renkli panel" (stat) görünümleri yok, TV'de ızgara olarak kalırlar
  { type: 'service_grid',    size: 'md', tab: 'status',       perm: null },
  { type: 'type_heatmap',    size: 'sm', tab: 'monitoring',   perm: 'monitoring.read' },
  { type: 'team_matrix',     size: 'lg', tab: 'monitoring',   perm: 'monitoring.read' },
  // Renkli panel kutuları (2026-10-13): tek gösterge, her zaman renkli panel — "Kutu ekle"de ayrı grup (statDefs.js)
  ...STAT_DEFS.map((d) => ({ type: d.type, size: 'sm', tab: d.tab, perm: d.perm, group: 'stat' })),
])

/**
 * "Renkli panel duvarı" hazır düzeni: renkli panel kutuları küçük boyutta (TV / duvar ekranı için). `preset: false` tanımlar
 * (R14: kanal raporu paneli) dışarıda — hazır düzen sonradan eklenen panelle değişmez, o panel "Kutu ekle"den alınır.
 */
export const STAT_PRESET = Object.freeze(STAT_PRESET_DEFS.map((d) => ({ type: d.type, size: 'sm' })))

/** Sahte izin: 7/24 konsolunu açabilir mi (sayfa `canView`'u sarar; izin matrisi anahtarı DEĞİL). */
export const NOC_CONSOLE = '@noc_console'

/**
 * Sahte izin (R14 inceleme 2026-10-17): kanal teknik hata raporu modülü bu kullanıcının takımlarına AÇIK mı —
 * `/api/me/permissions` `scope.channel_reports` (menü girişiyle aynı kural: yalnız açık `false` kapalıdır; eski sunucu
 * `scope` göndermezse sunulur). İzin matrisi anahtarı DEĞİL.
 */
export const CHANNEL_REPORTS = '@channel_reports'

/**
 * 7/24 komuta düzeni (2026-10-13, kullanıcı isteği "7/24 ekibi önünde olsun"): tek tıkla uygulanan hazır yerleşim. Sıra 3
 * sütunda boşluksuz satırlar verir (küçük + orta çiftleri). Yetkisi olmayan kutu düşer.
 */
export const NOC_PRESET = Object.freeze([
  { type: 'noc_light', size: 'sm' }, { type: 'noc_uncalled', size: 'md' },
  { type: 'alerts_open', size: 'md' }, { type: 'maintenance', size: 'sm' },
  { type: 'monitor_health', size: 'md' }, { type: 'storms', size: 'sm' },
  { type: 'incidents_open', size: 'md' }, { type: 'oncall', size: 'sm' },
  { type: 'status_services', size: 'lg' },
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
  const need = Array.isArray(e.perm) ? e.perm : [e.perm]
  return need.every((p) => !p || !!(canView && canView(p)))
}

export function widgetId(type) { return String(type).replace(/_/g, '-') }

export function defaultLayout(canView) {
  return DEFAULT_TYPES.filter((type) => typeVisible(type, canView))
    .map((type) => ({ id: widgetId(type), type, size: BY_TYPE.get(type).size }))
}

const ID_RE = /^[a-z0-9-]{1,40}$/
const TYPE_RE = /^[a-z_]{1,40}$/
/** Kutu görünümleri (sunucu UserPreferencesService.WIDGET_STYLES ile aynı). Alan yok = ayrıntılı (klasik) görünüm. */
export const STYLES = Object.freeze(['stat'])

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
    out.push(STYLES.includes(w.style) ? { id, type, size, style: w.style } : { id, type, size })
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
  return {
    v: 1,
    widgets: (list || []).slice(0, MAX_WIDGETS)
      .map(({ id, type, size, style }) => (STYLES.includes(style) ? { id, type, size, style } : { id, type, size })),
  }
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

/** Kutu görünümü: `'stat'` renkli panel, `null` ayrıntılı (alan silinir). Bilinmeyen değer → liste aynen döner. */
export function setWidgetStyle(list, id, style) {
  if (style != null && !STYLES.includes(style)) return list
  return (list || []).map((w) => {
    if (w.id !== id) return w
    const rest = { ...w }
    delete rest.style
    return style ? { ...rest, style } : rest
  })
}

/**
 * Hazır düzen: önayar listesinden görünür kutular (yeni kimlikler), ardından mevcut listedeki ÇİZİLEMEYEN kutular
 * (yetkisi şimdilik yok / daha yeni sürümün kutusu) — gizli kutular düzen değişince kaybolmaz.
 */
export function presetLayout(preset, list, canView) {
  const out = []
  for (const p of preset || []) {
    if (!BY_TYPE.has(p.type) || !typeVisible(p.type, canView) || out.some((w) => w.type === p.type)) continue
    out.push({ id: widgetId(p.type), type: p.type, size: SIZES.includes(p.size) ? p.size : BY_TYPE.get(p.type).size })
  }
  for (const w of list || []) {
    if (typeVisible(w.type, canView) || out.some((x) => x.type === w.type || x.id === w.id)) continue
    out.push(w)
  }
  return out.slice(0, MAX_WIDGETS)
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
  return x.every((w, i) => w.id === y[i].id && w.type === y[i].type && w.size === y[i].size && (w.style || null) === (y[i].style || null))
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
/** `panels`: TV'de kutular renkli panel (`stat`, varsayılan — uzaktan tek bakışta okunur) ya da ayrıntılı (`detail`). */
export const TV_PANELS = Object.freeze(['stat', 'detail'])
export const TV_DEFAULT = Object.freeze({ interval: 30, panels: 'stat', density: TV_DEFAULT_DENSITY })
const TV_KEY = 'sm.mydash.tv'

/** TV ayarı (dönüş aralığı + panel görünümü) — izleyici başına kolaylık, yalnız bu tarayıcıda (eşitlenmez). */
export function readTvSettings() {
  try {
    const raw = JSON.parse(localStorage.getItem(TV_KEY) || 'null')
    const interval = Number(raw?.interval)
    return {
      interval: TV_INTERVALS.includes(interval) ? interval : TV_DEFAULT.interval,
      panels: TV_PANELS.includes(raw?.panels) ? raw.panels : TV_DEFAULT.panels,
      density: normalizeDensity(raw?.density),
    }
  } catch {
    return { ...TV_DEFAULT }
  }
}

export function writeTvSettings(s) {
  try {
    localStorage.setItem(TV_KEY, JSON.stringify({
      interval: s.interval, panels: TV_PANELS.includes(s.panels) ? s.panels : TV_DEFAULT.panels,
      density: normalizeDensity(s.density),
    }))
  } catch { /* depolama kapalı */ }
}

/** Kutu renkli panel olarak mı çizilir: kutunun kendi görünümü ya da TV'nin "renkli paneller" ayarı. */
export function statStyleOf(widget, tv, tvPanels) {
  return widget?.style === 'stat' || (!!tv && tvPanels !== 'detail')
}

/** Renkli panel (tek büyük sayı) görünümü OLMAYAN kutular — liste / ızgara; TV'de okunur yükseklik ister. */
export const DETAIL_ONLY_TYPES = Object.freeze(new Set(['oncall', 'activity', 'service_grid', 'type_heatmap', 'team_matrix']))

/**
 * TV'de kutu ayrıntılı mı çizilecek (liste / ızgara — tek hücreye sığmaz): renkli paneli hiç olmayan tür ya da renkli panel
 * kapalıyken (ayar "ayrıntılı" ve kutunun kendi görünümü stat değil) normal kutu. Renkli panel kutuları (stat_*) hep paneldir.
 */
export function tvDetail(widget, tvPanels) {
  const type = widget?.type
  if (DETAIL_ONLY_TYPES.has(type)) return true
  if (catalogEntry(type)?.group === 'stat') return false
  return !statStyleOf(widget, true, tvPanels)
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
  // `visible:false` = alarm okuma yetkisi yok (sunucu boş özet döner) — "sorun yok" DEĞİL (2026-10-12)
  const hidden = data?.visible === false
  return {
    total, ...levels, unacked, items: items.slice(0, limit), hidden,
    tone: hidden ? 'muted' : levels.critical > 0 ? 'danger' : total > 0 ? 'warning' : 'success',
  }
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
    // Yeşil yalnız ölçülmüş ve sorunsuz filoda: etkin izleme yoksa ya da ölçülmemiş (unknown) varsa nötr (2026-10-12)
    types, tone: down > 0 ? 'danger' : stale > 0 ? 'warning' : active > 0 && unknown === 0 ? 'success' : 'muted',
  }
}

/** Sertifikalar (`/api/stats`): bitiş pencereleri + en acil alan adları (kritik/süresi dolmuş). */
export function certSummary(s, limit = 5) {
  const expired = n(s?.expired), exp7 = n(s?.expiring_in_7_days), exp30 = n(s?.expiring_in_30_days)
  const urgent = [...new Set([...(s?.expired_domains || []), ...(s?.critical_domains || [])].map(String))]
  return {
    total: n(s?.total_certificates), valid: n(s?.valid_count), expired, exp7, exp30, errors: n(s?.error_count),
    urgent: urgent.slice(0, limit), urgentMore: Math.max(0, urgent.length - limit),
    // Son taraması başarısız sertifika varsa ya da hiç sertifika yoksa "sorun yok" denmez (2026-10-12)
    tone: expired > 0 || exp7 > 0 ? 'danger' : exp30 > 0 || n(s?.error_count) > 0 ? 'warning' : n(s?.total_certificates) > 0 ? 'success' : 'muted',
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
  // En kötü durum `no_data`dan başlar (2026-10-12): hepsi veri yok → "çalışıyor" değil "veri yok"
  const worst = services.reduce((acc, s) => (STATE_RANK[s?.state] > STATE_RANK[acc] ? s.state : acc), 'no_data')
  // "Bütün hizmetler çalışıyor" yalnız hepsi gerçekten çalışırken — veri yok / bakım varken değil
  const allOk = counts.operational > 0 && counts.operational === services.length
  const shownState = worst === 'operational' && !allOk ? 'no_data' : worst
  return {
    total: services.length, counts, problems: problems.slice(0, limit), problemsMore: Math.max(0, problems.length - limit),
    problemCount: problems.length, worst, allOk, shownState,
    tone: worst === 'major_outage' || worst === 'partial_outage' ? 'danger' : worst === 'degraded' ? 'warning'
      : worst === 'maintenance' ? 'info' : allOk ? 'success' : 'muted',
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

// ── 7/24 Komuta özetleri (2026-10-13) ───────────────────────────────────────────────────────────────────────

/**
 * Durum lambası (`/api/me/open-alerts`): kritik/yüksek açık alarm → kırmızı, yalnız uyarı/diğer → turuncu, açık alarm yok →
 * yeşil. Okuma yetkisi yoksa (`visible:false`) gri — "sorun yok" DEĞİL. En eski açılış sunucudan (`oldest_*_at`; eski
 * sunucu göndermezse null — tahmin edilmez).
 */
export function lightSummary(data) {
  const a = alertsSummary(data, 0)
  const severe = a.critical + a.high
  return {
    hidden: a.hidden, total: a.total, severe, warn: a.warning + a.other, unacked: a.unacked,
    oldest: typeof data?.oldest_open_at === 'string' ? data.oldest_open_at : null,
    oldestSevere: typeof data?.oldest_severe_at === 'string' ? data.oldest_severe_at : null,
    tone: a.hidden ? 'muted' : severe > 0 ? 'danger' : a.total > 0 ? 'warning' : 'success',
  }
}

/** 7/24'e gidip bu kadar dakika aranmayan alarm kırmızı (istemci kuralı; sunucuda arama süresi ayarı yok). */
export const NOC_CALL_SLA_MIN = 15

/**
 * Aranmayı bekleyenler (`/api/noc/console?noc=sent&called=no&state=open`, ilk + gerekirse SON sayfa): en uzun bekleyen
 * önce. Bekleme 7/24'e gönderilişten (`noc.sent_at`; yoksa alarm açılışı) sayılır. Sunucunun süzgeci yine de satırda
 * doğrulanır (çözülmüş / aranmış satır sayılmaz), iki sayfa kesişirse satır bir kez sayılır. Toplam okunandan fazlaysa
 * gecikmiş sayısı ALT SINIRDIR (`overdueAtLeast`). Konsol listesi sunucuda kesildiyse (`truncated`, en eski açık
 * alarmlar düşer) `partial`: bekleyen yok görünse de YEŞİL verilmez.
 */
export function nocUncalledSummary(d, nowMs = Date.now(), slaMin = NOC_CALL_SLA_MIN) {
  const seen = new Set()
  const raw = (Array.isArray(d?.items) ? d.items : []).filter((it) => {
    if (!it || it.id == null) return !!it
    if (seen.has(it.id)) return false
    seen.add(it.id)
    return true
  })
  const rows = raw
    .filter((it) => it && it.resolved !== true && n(it.call_count) === 0)
    .map((it) => {
      const sentAt = it.noc?.sent_at || it.created_at || null
      const ms = zonedMs(sentAt)
      const waitMin = Number.isFinite(ms) ? Math.max(0, Math.floor((nowMs - ms) / 60000)) : null
      return { ...it, sentAt, waitMin, overdue: waitMin != null && waitMin >= slaMin }
    })
    .sort((a, b) => (b.waitMin ?? -1) - (a.waitMin ?? -1) || n(a.id) - n(b.id))
  const total = Math.max(n(d?.total), rows.length)
  const overdue = rows.filter((r) => r.overdue).length
  const partial = d?.truncated === true
  return {
    // Atlanan satır (okunmayan orta sayfalar) ya da sunucuda kesilen en eskiler → gecikmiş sayısı alt sınır ("N+")
    total, rows, overdue, overdueAtLeast: (total > raw.length || partial) && overdue > 0, partial,
    oldestSentAt: rows.length ? rows[0].sentAt : null,
    tone: total === 0 ? (partial ? 'muted' : 'success') : overdue > 0 ? 'danger' : 'warning',
  }
}

/**
 * Bakım pencereleri (`/api/status-page` `maintenance`): süren bakım → turuncu (o izlemelerin alarmları susturulur), yoksa
 * yeşil. `unavailable` = sunucu pencereleri okuyamadı → kutu hata durumunda (yeşil değil). Bakımdaki izleme sayısı görülebilen
 * hizmetlerin `maintenance` toplamı.
 */
export function maintenanceSummary(sp) {
  const m = sp?.maintenance || {}
  const active = Array.isArray(m.active) ? m.active : []
  const upcoming = Array.isArray(m.upcoming) ? m.upcoming : []
  // Görülebilen = toplam − gizli (inceleme 2026-10-13: gizli pencere renge girip "yok" metniyle çelişiyordu; olay kutusuyla aynı kural)
  const activeTotal = Math.max(n(m.active_total) - n(m.active_hidden), active.length)
  const upcomingTotal = Math.max(n(m.upcoming_total) - n(m.upcoming_hidden), upcoming.length)
  const services = Array.isArray(sp?.services) ? sp.services : []
  // Hizmet listesi kurum geneli; izlemeleri yalnız `monitors_visible` hizmetlerde görülebilir (inceleme 2026-10-13 tur 2)
  const monitorsInMaintenance = services.reduce((acc, x) => acc + (x?.monitors_visible === true ? n(x?.maintenance) : 0), 0)
  return {
    active, upcoming, activeTotal, upcomingTotal, monitorsInMaintenance,
    hidden: n(m.active_hidden) + n(m.upcoming_hidden),
    unavailable: m.unavailable === true,
    tone: activeTotal > 0 ? 'warning' : 'success',
  }
}

const SEVERE = new Set(['CRITICAL', 'HIGH'])

/**
 * Açık olaylar (`/api/status-page` `incidents.active`, önem sırasıyla): görülebilen kritik/yüksek → kırmızı, başka açık olay
 * → turuncu, yoksa yeşil. Sayılar görülebilen kayıtlardan (`active_visible`); başka takımlarınki yalnız `hidden` sayısıdır.
 * `unavailable` = olaylar okunamadı → kutu hata durumunda.
 */
export function incidentsSummary(sp) {
  const inc = sp?.incidents || {}
  const rows = Array.isArray(inc.active) ? inc.active : []
  const visible = Math.max(n(inc.active_visible), rows.length)
  // Satırlar en çok 50; kritik/yüksek sayısı satırlardan — önem sırasıyla geldiği için tavan aşılsa da ilk satırlar ağır olanlar
  const severe = rows.filter((r) => SEVERE.has(String(r?.severity || '').toUpperCase())).length
  return {
    rows, visible, severe, hidden: n(inc.active_hidden), unavailable: inc.active_unavailable === true,
    tone: severe > 0 ? 'danger' : visible > 0 ? 'warning' : 'success',
  }
}

// ── Grafana tarzı ızgaralar (2026-10-13) ────────────────────────────────────────────────────────────────────

/** Hizmet durumu → ton (durum sayfasıyla aynı anlam). */
export const SERVICE_TONE = Object.freeze({
  major_outage: 'danger', partial_outage: 'danger', degraded: 'warning', maintenance: 'info', operational: 'success', no_data: 'muted',
})
const TR_COLLATOR = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })

/**
 * Hizmet ızgarası (`/api/status-page` services): her hizmet bir kutucuk, EN KÖTÜ durum önce, sonra takım + ad (Türkçe
 * sıralama). Bilinmeyen durum `no_data` sayılır (asla yeşil değil). `limit` kadar kutucuk, kalanı `more`.
 */
export function serviceGrid(sp, limit = 24) {
  const services = (Array.isArray(sp?.services) ? sp.services : []).filter((x) => x && typeof x === 'object')
  const tiles = services.map((x) => {
    const state = SERVICE_TONE[x.state] ? x.state : 'no_data'
    return { ...x, state, tone: SERVICE_TONE[state] }
  }).sort((a, b) => (STATE_RANK[b.state] - STATE_RANK[a.state])
    || TR_COLLATOR.compare(String(a.team_name || ''), String(b.team_name || ''))
    || TR_COLLATOR.compare(String(a.name || ''), String(b.name || '')))
  const cap = Math.max(0, limit | 0)
  return { tiles: tiles.slice(0, cap), more: Math.max(0, tiles.length - cap), total: tiles.length, summary: statusSummary(sp, 0) }
}

/** İzleme sayaçlarından ton: çalışmıyor → kırmızı, gecikmiş → turuncu, ölçülmemiş / yalnız duraklatılmış → gri, aksi yeşil. */
export function healthTone({ active = 0, down = 0, stale = 0, unknown = 0 } = {}) {
  if (down > 0) return 'danger'
  if (stale > 0) return 'warning'
  if (active <= 0 || unknown > 0) return 'muted'
  return 'success'
}

/**
 * İzleme türü ısı haritası (`/api/monitoring/overview` types): her tür bir hücre, en kötü durumunun renginde. Hiç izlemesi
 * olmayan tür (toplam 0) çizilmez; yalnız duraklatılmış izlemeleri olan tür gri. Sıra sunucunun (katalog) sırası.
 */
export function typeHeat(ov) {
  const cells = (Array.isArray(ov?.types) ? ov.types : [])
    .filter((x) => x && n(x.total) - n(x.deleted) > 0)
    .map((x) => {
      const c = { type: String(x.type), active: n(x.active), down: n(x.down), stale: n(x.stale), unknown: n(x.unknown), paused: n(x.paused) }
      return { ...c, healthy: Math.max(0, c.active - c.down - c.stale - c.unknown), tone: healthTone(c) }
    })
  const down = cells.reduce((a, c) => a + c.down, 0)
  const stale = cells.reduce((a, c) => a + c.stale, 0)
  const unknown = cells.reduce((a, c) => a + c.unknown, 0)
  const active = cells.reduce((a, c) => a + c.active, 0)
  return { cells, down, stale, unknown, active, problemTypes: cells.filter((c) => c.tone === 'danger' || c.tone === 'warning').length,
    tone: healthTone({ active, down, stale, unknown }) }
}

/**
 * Matris hücresinin sayısı ve bağlantı durumu: hücrede yazan sayı, bağlantının açtığı (takım + tür + durum süzülmüş)
 * İzleme Panosu listesindeki satır sayısıyla aynıdır (inceleme 2026-10-13). Çalışmıyor → gecikmiş → ölçülmedi → sağlıklı →
 * duraklatılmış.
 */
export function matrixCellFocus(c) {
  if (!c) return { status: null, count: 0 }
  if (c.down > 0) return { status: 'down', count: c.down }
  if (c.stale > 0) return { status: 'stale', count: c.stale }
  if (c.unknown > 0) return { status: 'unknown', count: c.unknown }
  if (c.active > 0) return { status: 'up', count: c.active }
  return { status: 'paused', count: c.paused }
}

/** Takımsız izlemelerin anahtarı (İzleme Panosu `mo_team` süzgeciyle aynı: overviewFilters.NO_TEAM). */
export const MATRIX_NO_TEAM = '__none'
const TONE_RANK = Object.freeze({ danger: 0, warning: 1, muted: 2, success: 3 })

/**
 * Takım × izleme türü matrisi (`/api/monitoring/overview` monitors): silinmiş ve envanterden çıkmış (taranmayan) satırlar
 * sayılmaz. Hücre = o takımın o türdeki izlemeleri; ton `healthTone`. Sütunlar yalnız en az bir izlemesi olan türler
 * (sunucunun tür sırasıyla). Takımlar en kötü hücreye göre (kırmızı → turuncu → gri → yeşil), sonra ad; takımsız en sonda.
 */
export function teamTypeMatrix(ov, limit = 10) {
  const order = (Array.isArray(ov?.types) ? ov.types : []).map((x) => String(x?.type))
  const teams = new Map()
  const used = new Set()
  for (const r of Array.isArray(ov?.monitors) ? ov.monitors : []) {
    // Envanterden çıkmış (taranmayan) satır İzleme Panosu'nda "duraklatılmış" listelenir (sunucu: active=false, status
    // paused) — matris de duraklatılmış sayar; atlanınca hücre sayısı bağlantının listesinden az çıkıyordu (inceleme tur 2)
    if (!r || r.deleted === true || !r.type) continue
    const key = r.team_id != null ? String(r.team_id) : MATRIX_NO_TEAM
    let tm = teams.get(key)
    if (!tm) { tm = { key, name: r.team_name || null, cells: {} }; teams.set(key, tm) }
    const type = String(r.type)
    used.add(type)
    const c = tm.cells[type] || (tm.cells[type] = { total: 0, active: 0, down: 0, stale: 0, unknown: 0, paused: 0 })
    c.total += 1
    if (r.active === true) {
      c.active += 1
      if (r.status === 'down') c.down += 1
      else if (r.status === 'stale') c.stale += 1
      else if (r.status !== 'up') c.unknown += 1
    } else c.paused += 1
  }
  const types = [...order.filter((t) => used.has(t)), ...[...used].filter((t) => !order.includes(t)).sort()]
  const rows = [...teams.values()].map((tm) => {
    for (const c of Object.values(tm.cells)) c.tone = healthTone(c)
    // Takımın tonu yalnız ETKİN izlemesi olan hücrelerden (yalnız duraklatılmış tür, sağlıklı takımı griye çekmez — inceleme
    // tur 2); hiç etkin izlemesi yoksa gri ve en sonda
    const liveCells = Object.values(tm.cells).filter((c) => c.active > 0)
    const live = liveCells.length > 0
    const worst = live ? liveCells.map((c) => c.tone).reduce((w, t) => (TONE_RANK[t] < TONE_RANK[w] ? t : w), 'success') : 'muted'
    const problems = Object.values(tm.cells).reduce((a, c) => a + c.down + c.stale, 0)
    return { ...tm, worst, problems, live }
  // En kötü ton ÖNCE; takımsız satır yalnız aynı tondakiler arasında sonda (inceleme 2026-10-13: kırmızı takımsız satır satır
  // tavanının altında kalıp "+N" içinde kayboluyordu — sahipsiz alarm kimseye gitmez, en çok görünmesi gereken o); yalnız
  // duraklatılmış takımlar en sonda
  }).sort((a, b) => (a.live ? TONE_RANK[a.worst] : 4) - (b.live ? TONE_RANK[b.worst] : 4)
    || (a.key === MATRIX_NO_TEAM) - (b.key === MATRIX_NO_TEAM) || b.problems - a.problems
    || TR_COLLATOR.compare(String(a.name || a.key), String(b.name || b.key)))
  const cap = Math.max(0, limit | 0)
  const problemTeams = rows.filter((r) => r.worst === 'danger' || r.worst === 'warning').length
  // Genel ton etkin izlemesi olan takımlardan (tümüyle duraklatılmış takım "her şey yolunda"yı griye çekmez — ısı haritasıyla aynı)
  const live = rows.filter((r) => r.live)
  const tone = live.length === 0 ? 'muted' : live.some((r) => r.worst === 'danger') ? 'danger'
    : live.some((r) => r.worst === 'warning') ? 'warning' : live.every((r) => r.worst === 'success') ? 'success' : 'muted'
  return { types, rows: rows.slice(0, cap), more: Math.max(0, rows.length - cap), totalTeams: rows.length, problemTeams, tone }
}
