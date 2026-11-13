import { personalKey } from '../../../utils/personalStorage.js'
import { extractParams } from './sqlParams.js'

/**
 * SQL Playground çoklu sekme + taslak (2026-11-13, B3) — saf model + kişisel depolama.
 *
 * En çok {@link MAX_TABS} sekme; kalıcı taslak toplamı ≤ {@link MAX_TABS_BYTES} (UTF-16 karakter × 2 değil, JSON metin
 * uzunluğu — tarayıcı kotası karakter sayar). Depolama anahtarı `sm.sql.tabs:<kullanıcı>` (`personalStorage`
 * PERSONAL_PREFIXES — çıkışta / kullanıcı değişince silinir; sunucuya eşitlenmez). Kalıcı olan yalnız: başlık, SQL,
 * parametre değer / türleri, bağlı kayıtlı sorgu (kimlik + sürüm). Sonuçlar, plan ve karşılaştırma geçmişi BELLEKTE.
 *
 * <p>İki tarayıcı sekmesi aynı anahtara yazar: yazmadan önce depodaki güncel durum okunur ve BU sayfanın hiç görmediği
 * sekmeler (başka tarayıcı sekmesinin açtıkları) korunur ({@link mergeForeignTabs}); bu sayfanın gördüğü ama kapattığı
 * sekmeler düşer. Aynı sekmeyi iki sayfa düzenlerse son yazan kazanır.
 */
export const TABS_KEY = 'sm.sql.tabs'
export const MAX_TABS = 10
export const MAX_TABS_BYTES = 200 * 1024
export const MAX_TITLE = 120
/** Sekme başına saklanan parametre değeri sayısı — önce sorguda GEÇEN adlar (yazarken oluşan ara adlar budanır). */
export const MAX_STORED_PARAMS = 40

function rid() {
  try { if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID() } catch { /* yok */ }
  return `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`
}

/** Yeni sekme. `n` = görünen sıra numarası ("Sorgu n"). */
export function newTab(n, init = {}) {
  return {
    id: rid(), n: Number.isFinite(n) && n > 0 ? n : 1,
    title: typeof init.title === 'string' ? init.title.slice(0, MAX_TITLE) : '',
    sql: typeof init.sql === 'string' ? init.sql : '',
    params: init.params && typeof init.params === 'object' ? init.params : {},
    savedId: init.savedId ?? null, savedVersion: init.savedVersion ?? null, savedSql: init.savedSql ?? null,
    savedMine: init.savedMine ?? null,
  }
}

/** Bir sonraki sıra numarası (kullanılanların en büyüğü + 1). */
export function nextNumber(tabs) {
  return (tabs || []).reduce((m, t) => Math.max(m, Number(t.n) || 0), 0) + 1
}

/** İlk durum: tek boş sekme. */
export function initialTabs() {
  const t = newTab(1)
  return { tabs: [t], active: t.id }
}

function cleanTab(t) {
  if (!t || typeof t !== 'object' || typeof t.id !== 'string' || !t.id) return null
  const params = {}
  if (t.params && typeof t.params === 'object') {
    // Sınır sorgudaki MEVCUT adlara önce uygulanır: eski / ara adlar 40'ı doldurup gerçek parametrenin değerini düşürmesin
    const present = new Set(typeof t.sql === 'string' ? extractParams(t.sql) : [])
    const entries = Object.entries(t.params)
    const ordered = [...entries.filter(([k]) => present.has(k)), ...entries.filter(([k]) => !present.has(k))]
    for (const [k, v] of ordered.slice(0, MAX_STORED_PARAMS)) {
      if (/^[A-Za-z_][A-Za-z0-9_]{0,39}$/.test(k) && v && typeof v === 'object') {
        params[k] = { value: typeof v.value === 'string' ? v.value.slice(0, 1000) : '', type: typeof v.type === 'string' ? v.type : 'text' }
      }
    }
  }
  return {
    id: t.id.slice(0, 64), n: Number.isFinite(t.n) && t.n > 0 ? Math.floor(t.n) : 1,
    title: typeof t.title === 'string' ? t.title.slice(0, MAX_TITLE) : '',
    sql: typeof t.sql === 'string' ? t.sql : '', params,
    savedId: Number.isFinite(Number(t.savedId)) && t.savedId != null ? Number(t.savedId) : null,
    savedVersion: Number.isFinite(Number(t.savedVersion)) && t.savedVersion != null ? Number(t.savedVersion) : null,
    savedSql: typeof t.savedSql === 'string' ? t.savedSql : null,
    savedMine: typeof t.savedMine === 'boolean' ? t.savedMine : null,
  }
}

/** Depolanmış metni okur — bozuk / boş / şemaya uymayan → null. Sekme sayısı ve kimlikler doğrulanır. */
export function parseTabs(raw) {
  if (typeof raw !== 'string' || !raw) return null
  let data
  try { data = JSON.parse(raw) } catch { return null }
  if (!data || data.v !== 1 || !Array.isArray(data.tabs)) return null
  const seen = new Set()
  const tabs = []
  for (const t of data.tabs.slice(0, MAX_TABS)) {
    const c = cleanTab(t)
    if (c && !seen.has(c.id)) { seen.add(c.id); tabs.push(c) }
  }
  if (!tabs.length) return null
  const active = tabs.some((t) => t.id === data.active) ? data.active : tabs[0].id
  return { tabs, active }
}

/**
 * Kalıcı metin. Toplam {@link MAX_TABS_BYTES}'ı aşarsa `{ ok: false, size }` (taslak YAZILMAZ — önceki kayıt kalır,
 * arayüz uyarır).
 */
export function serializeTabs(state) {
  const tabs = (state?.tabs || []).slice(0, MAX_TABS).map(cleanTab).filter(Boolean)
  const text = JSON.stringify({ v: 1, active: state?.active ?? null, tabs })
  if (text.length > MAX_TABS_BYTES) return { ok: false, size: text.length, text: null }
  return { ok: true, size: text.length, text }
}

function store() {
  try { return globalThis.localStorage || null } catch { return null }
}

/** Kişisel depodan oku (kullanıcı anahtarlı). */
export function loadTabs(owner) {
  try { return parseTabs(store()?.getItem(personalKey(TABS_KEY, owner)) ?? null) } catch { return null }
}

/**
 * Başka tarayıcı sekmesinin sekmelerini korur: depodaki (`stored`) sekmelerden bu sayfanın HİÇ görmediği (`known`
 * dışında) ve kendi durumunda olmayanlar bizimkilerin arkasına eklenir (en çok {@link MAX_TABS}). Etkin sekme bizimki.
 */
export function mergeForeignTabs(ours, stored, known) {
  if (!stored?.tabs?.length || !known) return ours
  const own = new Set((ours?.tabs || []).map((t) => t.id))
  const foreign = stored.tabs.filter((t) => !own.has(t.id) && !known.has(t.id))
  if (!foreign.length) return ours
  return { ...ours, tabs: [...ours.tabs, ...foreign].slice(0, MAX_TABS) }
}

/**
 * Kişisel depoya yaz. `known` (bu sayfanın gördüğü sekme kimlikleri) verilirse depodaki güncel durum okunup başka
 * tarayıcı sekmesinin sekmeleri korunur. @returns {{ ok: boolean, size: number }}
 */
export function saveTabs(state, owner, { known = null } = {}) {
  let next = state
  if (known) {
    try { next = mergeForeignTabs(state, parseTabs(store()?.getItem(personalKey(TABS_KEY, owner)) ?? null), known) } catch { next = state }
  }
  const r = serializeTabs(next)
  if (!r.ok) return { ok: false, size: r.size }
  try { store()?.setItem(personalKey(TABS_KEY, owner), r.text); return { ok: true, size: r.size } } catch { return { ok: false, size: r.size } }
}

// ── Saf dönüşümler (sekme durumu `{ tabs, active }`) ────────────────────────────────────────────────────────────

export function addTab(state, init = {}) {
  if ((state?.tabs?.length ?? 0) >= MAX_TABS) return state
  const t = newTab(nextNumber(state.tabs), init)
  return { tabs: [...state.tabs, t], active: t.id }
}

export function closeTab(state, id) {
  const idx = state.tabs.findIndex((t) => t.id === id)
  if (idx < 0) return state
  const tabs = state.tabs.filter((t) => t.id !== id)
  if (!tabs.length) return initialTabs()
  const active = state.active === id ? tabs[Math.min(idx, tabs.length - 1)].id : state.active
  return { tabs, active }
}

export function closeOthers(state, id) {
  const keep = state.tabs.find((t) => t.id === id)
  return keep ? { tabs: [keep], active: keep.id } : state
}

export function updateTab(state, id, patch) {
  let changed = false
  const tabs = state.tabs.map((t) => {
    if (t.id !== id) return t
    const next = typeof patch === 'function' ? patch(t) : { ...t, ...patch }
    if (next !== t) changed = true
    return next
  })
  return changed ? { ...state, tabs } : state
}

export function duplicateTab(state, id) {
  const src = state.tabs.find((t) => t.id === id)
  if (!src || state.tabs.length >= MAX_TABS) return state
  const t = newTab(nextNumber(state.tabs), { sql: src.sql, params: { ...src.params }, title: src.title })
  const idx = state.tabs.findIndex((x) => x.id === id)
  const tabs = [...state.tabs.slice(0, idx + 1), t, ...state.tabs.slice(idx + 1)]
  return { tabs, active: t.id }
}

/** Kayıtlı sorgu zaten bir sekmede açık mı (aynı kimlik) — yeni sekme yerine ona geçilir. */
export function findSavedTab(state, savedId) {
  return state.tabs.find((t) => t.savedId != null && String(t.savedId) === String(savedId)) || null
}

/** Sekmenin kayıtlı sürümden farkı var mı (kayıtsız sekmede içerik varsa true). */
export function tabDirty(tab) {
  if (!tab) return false
  if (tab.savedId == null) return Boolean(tab.sql?.trim())
  return (tab.sql ?? '') !== (tab.savedSql ?? '')
}
