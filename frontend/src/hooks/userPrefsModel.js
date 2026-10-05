import { isKnownTheme } from '../theme/themes.js'

/**
 * Kişisel tercihler — SAF model (React yok; hooks/useUserPrefs.js bunu kullanır, birim testte doğrudan sınanır).
 * 2026-10-02, onaylı öneri 23: "Tarayıcıdaki mevcut tercihler ilk girişte sunucuya taşınır. Yeni seçenekleri kullanmayan
 * için hiçbir şey değişmez."
 *
 * <p><b>Sunucu belgesi</b> (`GET/PUT /api/me/preferences`, backend `UserPreferencesService`):
 * `{ favorites: [{type, id, name?}], landingTab, savedViews: {liste: [{name, params}]}, local: {anahtar: metin} }`.
 * PUT kısmidir: üst düzey anahtar DEĞİŞTİRİLİR; `local` GİRDİ BAZINDA birleşir (null = sil).
 *
 * <p><b>Aynalanan localStorage anahtarları</b> ({@link LOCAL_PREF_KEYS} + {@link LOCAL_PREF_PREFIXES}) — yalnız gerçek
 * kullanıcı tercihleri; backend `UserPreferencesService.LOCAL_KEYS/LOCAL_PREFIXES` ile BİREBİR aynı (kapı:
 * `test/userPrefsWhitelistSync.test.js`):
 * <ul>
 *   <li>Kenar çubuğu: `sidebar-open` (açık/daraltılmış).</li>
 *   <li>Liste başına sayfa boyutu: `sm.pageSize.<liste>` (usePagination / useCheckHistory).</li>
 *   <li>Tüm Sertifikalar tablo görünümü + adlı ön ayarlar: `certtable-view`, `certtable-presets`.</li>
 *   <li>Denetim Kaydı kayıtlı görünümleri + özet paneli: `sm.audit.savedViews`, `sm.audit.insightsOpen`.</li>
 *   <li>Envanter görünümü (sütunlar/sıralama) + kayıtlı görünümler + katlanan bantlar: `inventory-view`,
 *       `inventory-saved-views`, `inv-hygiene-open`, `inv-stats-open`.</li>
 *   <li>Katlanan paneller: `today-panel-open`, `cfg-health-open`, `wr-thisweek-open`, `wr-completion-open`,
 *       `wr-help-open`, `sm.userpush.sections`.</li>
 *   <li>Görünüm seçimleri: `renewal-view`, `renewal-guide-platform`, `sm.incidents.view`, `incidents-banner-dismissed`,
 *       `sm.warnings.view`, `uptime-scope`.</li>
 *   <li>"Şimdi Kontrol Et" takım seçimi: `sm.checkRun.teams`, `sm.checkRun.teams.<tür>`.</li>
 *   <li>Tema (2026-10-05, ürün kararı — "kullanıcının şema seçimlerini hatırlayalım"): `site-monitor-theme`. Değer bilinen
 *       bir tema kimliği olmalı ({@link isSyncableValue}); girişte sunucu kazanır ve App.jsx ThemeProvider'a
 *       `reloadChoice()` ile hemen uygular (yeniden yükleme yok). Giriş öncesi ve çıkıştan sonra cihazın değeri geçerli.</li>
 * </ul>
 * BİLİNÇLİ OLARAK DIŞARIDA: oturum/kimlik bayrakları (`site-monitor-remembered-user`, `sm.session.active`,
 * `sm.storage.owner`), dil (sağlayıcı girişten ÖNCE okur ve EN sözlüğü ayrı parça — sunucudan yazmak ekranı yenilemeye
 * dek ayrıştırırdı),
 * taslaklar (`wr.draft.*`), kişisel "son kullanılanlar" (`sm.palette.recent`, `sm.dexp.recent` — çıkışta silinir), tur
 * aynası (`sm.tour` — zaten sunucuda), sürüm/duyuru damgaları, bildirim kutusunun okundu/temizlendi kümeleri (`inbox-seen:`,
 * `inbox-dismissed:` — her açılışta yazılan, 500 kimliğe kadar büyüyen durum; belge sınırını zorlar ve her etkileşimde
 * PUT üretirdi), yenileme kılavuzunun işaretleri (ilerleme durumu, tercih değil), oturumluk sessionStorage anahtarları,
 * kenar çubuğu bölüm akordiyonu (`nav-section-open` — her sekme geçişinde kendiliğinden yazılır). Kart yoğunluğu tercihi
 * tarayıcıya hiç yazılmaz (kullanıcı kararı 2026-09-27) — burada da EKLENMEDİ.
 */

export const LOCAL_PREF_KEYS = Object.freeze([
  'sidebar-open', 'today-panel-open',
  'certtable-view', 'certtable-presets',
  'sm.audit.savedViews', 'sm.audit.insightsOpen',
  'inventory-view', 'inventory-saved-views', 'inv-hygiene-open', 'inv-stats-open',
  'cfg-health-open',
  'wr-thisweek-open', 'wr-completion-open', 'wr-help-open',
  'sm.userpush.sections',
  'renewal-view', 'renewal-guide-platform',
  'sm.incidents.view', 'incidents-banner-dismissed',
  'sm.warnings.view', 'uptime-scope',
  'sm.checkRun.teams',
  // Tema (2026-10-05, ürün kararı): kişinin seçimi sonraki oturumlarda (başka cihazda da) aynı temayla açılır.
  'site-monitor-theme',
])

/** Tema tercihinin localStorage anahtarı — ThemeProvider (i18n/theme.jsx) ile aynı. */
export const THEME_PREF_KEY = 'site-monitor-theme'

/**
 * Değer doğrulayıcıları: aynalanan bir anahtarın DEĞERİ de geçerli olmalı. Tema yalnız bilinen bir kimlik olabilir
 * (sunucu `UserPreferencesService` bilinmeyeni yok sayar) — bilinmeyen değer ne yüklenir ne de sunucudan yerele yazılır.
 */
const VALUE_RULES = {
  [THEME_PREF_KEY]: (v) => isKnownTheme(v),
}

/** Anahtar + değer birlikte aynalanabilir mi (beyaz liste + değer kuralı). */
export function isSyncableValue(key, value) {
  const rule = VALUE_RULES[key]
  return !rule || value == null || rule(value)
}

export const LOCAL_PREF_PREFIXES = Object.freeze(['sm.pageSize.', 'sm.checkRun.teams.'])

/** Sunucu sınırları (backend `UserPreferencesService` sabitleriyle aynı). */
export const MAX_LOCAL_VALUE = 16 * 1024
export const MAX_FAVORITES = 200
export const MAX_VIEWS_PER_LIST = 50
export const MAX_VIEW_NAME = 60

/** Favori olabilen izleme türleri — sekme kimlikleriyle aynı. */
export const MONITOR_TYPES = Object.freeze(['http', 'ping', 'port', 'dns', 'domain', 'keyword', 'page', 'pagespeed', 'scripted'])

const SUFFIX_RE = /^[A-Za-z0-9._:-]{1,80}$/
const LANDING_RE = /^[a-z][a-z0-9-]{0,39}$/

/** localStorage anahtarı sunucuya aynalanır mı. */
export function isSyncedLocalKey(key) {
  if (typeof key !== 'string' || !key) return false
  if (LOCAL_PREF_KEYS.includes(key)) return true
  return LOCAL_PREF_PREFIXES.some((p) => key.startsWith(p) && SUFFIX_RE.test(key.slice(p.length)))
}

/** Tarayıcıdaki aynalanan tercihlerin anlık görüntüsü: { anahtar: metin }. Depo yoksa/kapalıysa {}. */
export function readLocalSnapshot(storage) {
  const out = {}
  if (!storage) return out
  try {
    for (let i = 0; i < storage.length; i++) {
      const k = storage.key(i)
      if (!isSyncedLocalKey(k)) continue
      const v = storage.getItem(k)
      if (typeof v === 'string' && v.length <= MAX_LOCAL_VALUE && isSyncableValue(k, v)) out[k] = v
    }
  } catch { /* depo erişilemez — boş görüntü */ }
  return out
}

/** Sunucunun `local` bölümünden yalnız geçerli girdiler (bilinmeyen tema değeri dahil geçersiz değerler atılır). */
export function cleanServerLocal(local) {
  const out = {}
  if (!local || typeof local !== 'object' || Array.isArray(local)) return out
  for (const [k, v] of Object.entries(local)) {
    if (isSyncedLocalKey(k) && typeof v === 'string' && v.length <= MAX_LOCAL_VALUE && isSyncableValue(k, v)) out[k] = v
  }
  return out
}

/**
 * Girişteki birleştirme planı — SUNUCU KAZANIR, tarayıcı boşlukları doldurur:
 * - `toWrite`: sunucuda olup tarayıcıda farklı/eksik olanlar → localStorage'a yazılır (ekranlar oradan okur).
 * - `toUpload`: tarayıcıda olup sunucuda OLMAYANLAR → tek PUT ile yüklenir (ilk girişte "taşıma"; boş sunucuda hepsi).
 * - `skip` (giriş ile belge gelişi arasında kullanıcının değiştirdiği anahtarlar): sunucu EZMEZ; tarayıcıdaki hâli
 *   yüklenir (silinmişse null).
 */
export function planHydration(serverLocal, snapshot, skip = new Set()) {
  const server = cleanServerLocal(serverLocal)
  const browser = snapshot || {}
  const toWrite = {}
  const toUpload = {}
  for (const [k, v] of Object.entries(server)) {
    if (skip.has(k)) continue
    if (browser[k] !== v) toWrite[k] = v
  }
  for (const [k, v] of Object.entries(browser)) {
    if (skip.has(k)) { if (server[k] !== v) toUpload[k] = v; continue }
    if (!(k in server)) toUpload[k] = v
  }
  for (const k of skip) {
    if (!(k in browser) && k in server && isSyncedLocalKey(k)) toUpload[k] = null
  }
  return { toWrite, toUpload, server }
}

// ── Favoriler ───────────────────────────────────────────────────────────────────────────────────

export function favKey(type, id) { return `${type}:${id}` }

/** Geçerli favori dizisi (tür beyaz listesi, pozitif tamsayı kimlik, tekil). */
export function normalizeFavorites(list) {
  if (!Array.isArray(list)) return []
  const seen = new Set()
  const out = []
  for (const f of list) {
    const type = String(f?.type ?? '')
    const id = Number(f?.id)
    if (!MONITOR_TYPES.includes(type) || !Number.isInteger(id) || id < 1) continue
    const key = favKey(type, id)
    if (seen.has(key)) continue
    seen.add(key)
    const name = typeof f?.name === 'string' && f.name.trim() ? f.name.trim() : null
    out.push(name ? { type, id, name } : { type, id })
  }
  return out
}

export function isFavorite(list, type, id) {
  const n = Number(id)
  return Array.isArray(list) && list.some((f) => f.type === type && Number(f.id) === n)
}

/**
 * Favoriyi ekler/çıkarır → { list, on } ; tavan doluysa ekleme yapılmaz (`full: true`). Yeni favori SONA eklenir
 * (komut paletinde eklenme sırası); varsayılan kart sırası (utils/monitorSort.js) favoriden ETKİLENMEZ — favori bir
 * süzgeç/kısayoldur, sıralama değil (ürün kararı).
 */
export function toggleFavorite(list, { type, id, name }) {
  const cur = normalizeFavorites(list)
  const n = Number(id)
  if (isFavorite(cur, type, n)) return { list: cur.filter((f) => !(f.type === type && f.id === n)), on: false, full: false }
  if (cur.length >= MAX_FAVORITES) return { list: cur, on: false, full: true }
  const label = typeof name === 'string' && name.trim() ? name.trim().slice(0, 300) : null
  return { list: [...cur, label ? { type, id: n, name: label } : { type, id: n }], on: true, full: false }
}

// ── Kayıtlı görünümler ──────────────────────────────────────────────────────────────────────────

export function viewsFor(savedViews, listKey) {
  const list = savedViews && typeof savedViews === 'object' ? savedViews[listKey] : null
  return Array.isArray(list) ? list.filter((v) => v && typeof v.name === 'string' && v.name.trim()) : []
}

const sameName = (a, b) => String(a).trim().toLocaleLowerCase('tr') === String(b).trim().toLocaleLowerCase('tr')

function withList(savedViews, listKey, list) {
  const next = { ...(savedViews && typeof savedViews === 'object' ? savedViews : {}) }
  if (list.length) next[listKey] = list
  else delete next[listKey]
  return next
}

/** Aynı adlı görünüm varsa YERİNE geçer (konumu korunur), yoksa sona eklenir; tavan doluysa `full`. */
export function upsertView(savedViews, listKey, name, params) {
  const clean = String(name || '').trim().slice(0, MAX_VIEW_NAME)
  if (!clean) return { savedViews, full: false, replaced: false }
  const list = viewsFor(savedViews, listKey)
  const at = list.findIndex((v) => sameName(v.name, clean))
  const view = { name: clean, params: { ...(params || {}) } }
  if (at >= 0) {
    const next = [...list]; next[at] = view
    return { savedViews: withList(savedViews, listKey, next), full: false, replaced: true }
  }
  if (list.length >= MAX_VIEWS_PER_LIST) return { savedViews, full: true, replaced: false }
  return { savedViews: withList(savedViews, listKey, [...list, view]), full: false, replaced: false }
}

/** Yeniden adlandır; yeni ad başka bir görünümde varsa `conflict`. */
export function renameView(savedViews, listKey, oldName, newName) {
  const clean = String(newName || '').trim().slice(0, MAX_VIEW_NAME)
  const list = viewsFor(savedViews, listKey)
  const at = list.findIndex((v) => v.name === oldName)
  if (!clean || at < 0) return { savedViews, conflict: false }
  if (list.some((v, i) => i !== at && sameName(v.name, clean))) return { savedViews, conflict: true }
  const next = [...list]; next[at] = { ...list[at], name: clean }
  return { savedViews: withList(savedViews, listKey, next), conflict: false }
}

export function deleteView(savedViews, listKey, name) {
  return withList(savedViews, listKey, viewsFor(savedViews, listKey).filter((v) => v.name !== name))
}

/**
 * URL'deki sayfa-durumu parametrelerinden görünüm parametrelerini seçer. `keys` tam adlar, `prefix` önekli aile
 * (ör. `mo_`); `exclude` geçici durumlar (açık ayrıntı, pencere, sayfa numarası). Boş değerler alınmaz.
 */
export function pickParams(search, { keys = [], prefix = null, exclude = [] } = {}) {
  const out = {}
  let sp
  try { sp = new URLSearchParams(search) } catch { return out }
  for (const [k, v] of sp.entries()) {
    if (v == null || v === '' || exclude.includes(k)) continue
    if (keys.includes(k) || (prefix && k.startsWith(prefix))) out[k] = v
  }
  return out
}

/**
 * Kayıtlı görünüm alan tanımları — hangi URL parametreleri bir "görünüm"dür. Sayfa numarası / boyutu, açık ayrıntı
 * (`monitor`, `mtab`, `alert`, `ih_id`) ve pencereler (`mo_dlg`) GEÇİCİDİR, görünüme girmez.
 */
export const VIEW_SPECS = Object.freeze({
  /** Dokuz izleme türü sayfası (utils/monitorFilters.monitorUrlState + Alan Adı'nın sort / dq'su). */
  monitor: Object.freeze({ keys: ['team', 'via', 'group', 'tag', 'q', 'stat', 'sort', 'dq'] }),
  /** İzleme Panosu (`mo_*`). */
  monitoring: Object.freeze({ prefix: 'mo_', exclude: ['mo_dlg'] }),
  /** Alarm Geçmişi (alertHistoryModel.URL_KEYS + görünüm sekmesi). */
  alerthistory: Object.freeze({ keys: ['view', 'type', 'q', 'level', 'team', 'ack', 'from', 'to', 'range', 'src', 'sort', 'noc'] }),
  /** Olay & Hata Geçmişi (`ih_*`). */
  'incident-history': Object.freeze({ prefix: 'ih_', exclude: ['ih_id', 'ih_page', 'ih_ps'] }),
})

/** İki parametre kümesi aynı mı (sıra önemsiz). */
export function sameParams(a, b) {
  const x = a || {}, y = b || {}
  const kx = Object.keys(x), ky = Object.keys(y)
  return kx.length === ky.length && kx.every((k) => String(x[k]) === String(y[k]))
}

// ── Açılış sekmesi ──────────────────────────────────────────────────────────────────────────────

/** Saklanan açılış sekmesi izinli listede mi; değilse null (Pano açılır). */
export function resolveLandingTab(value, allowedIds) {
  if (typeof value !== 'string' || !LANDING_RE.test(value)) return null
  return Array.isArray(allowedIds) && allowedIds.includes(value) ? value : null
}
