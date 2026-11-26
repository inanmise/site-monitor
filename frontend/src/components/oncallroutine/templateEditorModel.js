/**
 * Nöbet Rutinleri — şablon düzenleyicisinin SAF modeli (2026-11-26). React ve ağa bağlı değildir.
 *
 * <p>İçerik: taslak işlemleri (bölüm / görev ekle, sil, yeniden adlandır, yukarı / aşağı, başka bölüme taşı), yeni kimlik
 * üretimi (`s-<zaman36><rastgele4>` / `t-…`; taslakta ya da yüklenen asılda bulunan — silinmiş olanlar dahil — bir kimlik
 * ASLA yeniden kullanılmaz), sunucu kurallarının (sözleşme §2.11) istemci aynası — alan hatası anahtarları sunucununkiyle
 * AYNI (`template.<bölüm>.<özellik>`, `template.<bölüm>.<görev>.<özellik>`, `template.<bölüm>.<görev>.links.<sıra>.url`),
 * kanonik karşılaştırma ("kaydedilmemiş değişiklik" + sayısı), bağlantı yardımcıları, sıklık parametreleri, wiki içe
 * aktarımının taslağa uygulanması ve sunucu 400 alan hatalarının alanlara eşlenmesi.
 *
 * <p>Garanti sunucudadır; buradaki doğrulama anında geri bildirim içindir. Sınırlar GET `/template` yanıtının `limits`
 * nesnesinden okunur (yoksa sözleşmedeki sayılar).
 */
import { fold } from '../../utils/keywordFold.js'
import { serverFieldErrors } from '../../utils/formErrors.js'
import { diffTemplates } from './routineTemplateDiff.js'

/** Sözleşme §2.11 sınırları (sunucu `limits` gelmezse). */
export const DEFAULT_LIMITS = Object.freeze({
  sections: 30,
  tasks: 300,
  active_tasks: 200,
  tasks_per_section: 100,
  links_per_task: 20,
  links_total: 1000,
  title: 200,
  section_title: 120,
  description: 2000,
  signals_per_task: 4,
  url: 2000,
  label: 200,
})

/** Sunucu `limits` nesnesini varsayılanlarla birleştirir (yalnız pozitif tam sayılar alınır). */
export function resolveLimits(server) {
  const out = { ...DEFAULT_LIMITS }
  if (server && typeof server === 'object') {
    for (const k of Object.keys(DEFAULT_LIMITS)) {
      const v = Number(server[k])
      if (Number.isInteger(v) && v > 0) out[k] = v
    }
  }
  return out
}

export const FREQUENCIES = Object.freeze(['DAILY', 'WEEKLY', 'MONTHLY'])
export const MONTHLY_RULES = Object.freeze(['FIRST_WORKDAY', 'LAST_WORKDAY', 'DAY', 'NTH_WEEKDAY'])
export const WEEKDAYS = Object.freeze([1, 2, 3, 4, 5, 6, 7])
export const NTHS = Object.freeze([1, 2, 3, 4, 5])
/** Kayıt kaynağı ipucu (sunucu sürüm satırına yazar). */
export const SAVE_SOURCES = Object.freeze(['EDITOR', 'IMPORT', 'SAMPLE', 'RESTORE'])
export const DEFAULT_WEEKDAY = 5
export const ID_RE = /^[a-z0-9][a-z0-9_-]{0,39}$/
const HM_RE = /^([01]\d|2[0-3]):([0-5]\d)$/
const SECTION_PROPS = new Set(['title', 'window_from', 'window_to', 'tasks'])

/** Bölümün / görevin şablon kimliklerinin tamamı. */
export function collectIds(tpl, into = new Set()) {
  for (const s of tpl?.sections || []) {
    if (s?.id) into.add(s.id)
    for (const t of s?.tasks || []) if (t?.id) into.add(t.id)
  }
  return into
}

function rand4() {
  try {
    const a = new Uint32Array(1)
    globalThis.crypto.getRandomValues(a)
    return (a[0] % 1679616).toString(36).padStart(4, '0')
  } catch {
    return Math.floor(Math.random() * 1679616).toString(36).padStart(4, '0')
  }
}

/**
 * Yeni kimlik: `<önek>-<zaman36><rastgele4>`; `taken` kümesindeyse yeniden üretilir (en çok 50 deneme, sonra sayaçlı
 * sonek). Üretilen kimlik kümeye EKLENİR — aynı oturumda ikinci kez verilmez.
 */
export function newId(prefix, taken = new Set()) {
  const p = prefix === 's' ? 's' : 't'
  for (let i = 0; i < 50; i++) {
    const id = `${p}-${Date.now().toString(36)}${rand4()}`
    if (!taken.has(id) && ID_RE.test(id)) { taken.add(id); return id }
  }
  let n = taken.size + 1
  let id = `${p}-${Date.now().toString(36)}${n}`
  while (taken.has(id) && n < taken.size + 1000) { n += 1; id = `${p}-${Date.now().toString(36)}${n}` }
  taken.add(id)
  return id
}

/** Kimlik üreteci (wiki içe aktarımı ve örnek şablon için) — aynı `taken` kümesini paylaşır. */
export function makeIdFactory(taken = new Set()) {
  return (prefix) => newId(prefix, taken)
}

const strOr = (v, d = '') => (typeof v === 'string' ? v : d)
const intOrNull = (v) => {
  const n = typeof v === 'string' && v.trim() !== '' ? Number(v) : v
  return Number.isInteger(n) ? n : null
}
const hmOrNull = (v) => (typeof v === 'string' && v.trim() !== '' ? v.trim() : null)

/** Görev biçimi (eksik alanlar varsayılanla). Bilinmeyen anahtarlar düşer. */
export function normalizeTask(t) {
  const freq = FREQUENCIES.includes(t?.frequency) ? t.frequency : 'DAILY'
  return {
    id: strOr(t?.id),
    title: strOr(t?.title),
    description: strOr(t?.description),
    links: (Array.isArray(t?.links) ? t.links : [])
      .filter((l) => l && typeof l === 'object')
      .map((l) => ({ label: strOr(l.label), url: strOr(l.url) })),
    signals: (Array.isArray(t?.signals) ? t.signals : []).filter((s) => typeof s === 'string'),
    required: t?.required !== false,
    active: t?.active !== false,
    frequency: freq,
    weekday: intOrNull(t?.weekday),
    monthly_rule: MONTHLY_RULES.includes(t?.monthly_rule) ? t.monthly_rule : null,
    month_day: intOrNull(t?.month_day),
    nth: intOrNull(t?.nth),
    window_from: hmOrNull(t?.window_from),
    window_to: hmOrNull(t?.window_to),
  }
}

export function normalizeSection(s) {
  return {
    id: strOr(s?.id),
    title: strOr(s?.title),
    window_from: hmOrNull(s?.window_from),
    window_to: hmOrNull(s?.window_to),
    tasks: (Array.isArray(s?.tasks) ? s.tasks : []).filter((t) => t && typeof t === 'object').map(normalizeTask),
  }
}

/** Sunucu şablonunu düzenlenebilir biçime getirir; null / bozuk → boş şablon. */
export function normalizeTemplate(tpl) {
  const sections = Array.isArray(tpl?.sections) ? tpl.sections.filter((s) => s && typeof s === 'object') : []
  return { v: 1, sections: sections.map(normalizeSection) }
}

export function emptyTemplate() {
  return { v: 1, sections: [] }
}

export function newTask(taken, patch = {}) {
  return normalizeTask({ id: newId('t', taken), required: true, active: true, frequency: 'DAILY', ...patch })
}

export function newSection(taken, title = '') {
  return { id: newId('s', taken), title, window_from: null, window_to: null, tasks: [] }
}

const singleLine = (s) => strOr(s).replace(/[\r\n]+/g, ' ')
const lf = (s) => strOr(s).replace(/\r\n?/g, '\n').split('\u0000').join('')

/**
 * Taslağın sunucuya giden biçimi: başlıklar tek satır + kırpılmış, boş saatler null, sıklık yalnız kendi parametrelerini
 * taşır, tamamen boş bağlantı satırları düşer. Kanonik karşılaştırmanın da temelidir.
 */
export function toWire(tpl) {
  const sections = (tpl?.sections || []).map((s) => ({
    id: s.id,
    title: singleLine(s.title).trim(),
    window_from: hmOrNull(s.window_from),
    window_to: hmOrNull(s.window_to),
    tasks: (s.tasks || []).map((t) => {
      const f = FREQUENCIES.includes(t.frequency) ? t.frequency : 'DAILY'
      const rule = f === 'MONTHLY' ? (t.monthly_rule || null) : null
      return {
        id: t.id,
        title: singleLine(t.title).trim(),
        description: lf(t.description).trim(),
        links: (t.links || [])
          .map((l) => ({ label: singleLine(l?.label).trim(), url: strOr(l?.url).trim() }))
          .filter((l) => l.label || l.url),
        signals: [...new Set((t.signals || []).filter((k) => typeof k === 'string'))],
        required: t.required !== false,
        active: t.active !== false,
        frequency: f,
        weekday: f === 'WEEKLY' || (f === 'MONTHLY' && rule === 'NTH_WEEKDAY') ? intOrNull(t.weekday) : null,
        monthly_rule: rule,
        month_day: rule === 'DAY' ? intOrNull(t.month_day) : null,
        nth: rule === 'NTH_WEEKDAY' ? intOrNull(t.nth) : null,
        window_from: hmOrNull(t.window_from),
        window_to: hmOrNull(t.window_to),
      }
    }),
  }))
  return { v: 1, sections }
}

/** Kanonik JSON (kaydedilmemiş değişiklik denetimi). */
export function canonicalJson(tpl) {
  return tpl ? JSON.stringify(toWire(tpl)) : ''
}

/** Taslak kaydedilene göre değişti mi (asıl yoksa — ilk kayıt — taslak varsa değişmiş sayılır). */
export function isDirty(base, draft) {
  if (!draft) return false
  if (!base) return true
  return canonicalJson(base) !== canonicalJson(draft)
}

/** Kaydedilmemiş değişiklik sayısı (fark kalemleri). */
export function dirtyCount(base, draft) {
  if (!draft) return 0
  return diffTemplates(base ? toWire(base) : null, toWire(draft)).count
}

export function countTasks(tpl) {
  let n = 0
  for (const s of tpl?.sections || []) n += (s.tasks || []).length
  return n
}

export function countActive(tpl) {
  let n = 0
  for (const s of tpl?.sections || []) for (const t of s.tasks || []) if (t.active !== false) n += 1
  return n
}

export function countLinks(tpl) {
  let n = 0
  for (const s of tpl?.sections || []) {
    for (const t of s.tasks || []) for (const l of t.links || []) if (strOr(l?.url).trim() || strOr(l?.label).trim()) n += 1
  }
  return n
}

// ── Taslak işlemleri (değişmez kopyalar) ────────────────────────────────────────────────────────────────────────────

const mapSections = (tpl, fn) => ({ ...tpl, sections: (tpl?.sections || []).map(fn) })

export function addSection(tpl, taken, title = '') {
  const section = newSection(taken, title)
  return { template: { ...tpl, sections: [...(tpl?.sections || []), section] }, id: section.id }
}

export function updateSection(tpl, sectionId, patch) {
  return mapSections(tpl, (s) => (s.id === sectionId ? { ...s, ...(typeof patch === 'function' ? patch(s) : patch) } : s))
}

export function renameSection(tpl, sectionId, title) {
  return updateSection(tpl, sectionId, { title })
}

export function removeSection(tpl, sectionId) {
  return { ...tpl, sections: (tpl?.sections || []).filter((s) => s.id !== sectionId) }
}

function moveInList(list, index, dir) {
  const j = index + dir
  if (index < 0 || j < 0 || j >= list.length) return list
  const out = [...list]
  const [x] = out.splice(index, 1)
  out.splice(j, 0, x)
  return out
}

/** Bölümü bir yukarı (−1) / aşağı (+1) taşır; uçta değişmez. */
export function moveSection(tpl, sectionId, dir) {
  const list = tpl?.sections || []
  const out = moveInList(list, list.findIndex((s) => s.id === sectionId), dir)
  return out === list ? tpl : { ...tpl, sections: out }
}

/** Görevin bulunduğu bölüm ve sırası. */
export function locateTask(tpl, taskId) {
  const sections = tpl?.sections || []
  for (let i = 0; i < sections.length; i++) {
    const j = (sections[i].tasks || []).findIndex((t) => t.id === taskId)
    if (j >= 0) return { sectionIndex: i, sectionId: sections[i].id, index: j, task: sections[i].tasks[j] }
  }
  return null
}

export function addTask(tpl, sectionId, taken, patch = {}) {
  const task = newTask(taken, patch)
  return { template: updateSection(tpl, sectionId, (s) => ({ tasks: [...(s.tasks || []), task] })), id: task.id }
}

/** Görevi belirli bir sıraya geri koyar (silme geri alma). Bölüm yoksa ilk bölüme, o da yoksa değişmez. */
export function insertTask(tpl, sectionId, index, task) {
  const sections = tpl?.sections || []
  const target = sections.some((s) => s.id === sectionId) ? sectionId : sections[0]?.id
  if (!target || locateTask(tpl, task.id)) return tpl
  return updateSection(tpl, target, (s) => {
    const tasks = [...(s.tasks || [])]
    tasks.splice(Math.max(0, Math.min(index, tasks.length)), 0, task)
    return { tasks }
  })
}

export function updateTask(tpl, taskId, patch) {
  return mapSections(tpl, (s) => {
    const i = (s.tasks || []).findIndex((t) => t.id === taskId)
    if (i < 0) return s
    const tasks = [...s.tasks]
    tasks[i] = { ...tasks[i], ...(typeof patch === 'function' ? patch(tasks[i]) : patch) }
    return { ...s, tasks }
  })
}

export function removeTask(tpl, taskId) {
  return mapSections(tpl, (s) => ((s.tasks || []).some((t) => t.id === taskId) ? { ...s, tasks: s.tasks.filter((t) => t.id !== taskId) } : s))
}

/** Görevi kendi bölümünde bir yukarı / aşağı taşır. */
export function moveTask(tpl, taskId, dir) {
  const loc = locateTask(tpl, taskId)
  if (!loc) return tpl
  return updateSection(tpl, loc.sectionId, (s) => ({ tasks: moveInList(s.tasks, loc.index, dir) }))
}

/**
 * Görevi başka (ya da aynı) bölüme taşır: `beforeTaskId` verilirse onun önüne, yoksa bölümün sonuna. Sürükle-bırak ve
 * "Bölüme taşı" menüsü bunu kullanır.
 */
export function moveTaskTo(tpl, taskId, targetSectionId, beforeTaskId = null) {
  const loc = locateTask(tpl, taskId)
  if (!loc || !(tpl.sections || []).some((s) => s.id === targetSectionId) || beforeTaskId === taskId) return tpl
  const without = removeTask(tpl, taskId)
  return updateSection(without, targetSectionId, (s) => {
    const tasks = [...(s.tasks || [])]
    const at = beforeTaskId ? tasks.findIndex((t) => t.id === beforeTaskId) : -1
    tasks.splice(at >= 0 ? at : tasks.length, 0, loc.task)
    return { tasks }
  })
}

/** Sıklık değişimi: yeni sıklığın varsayılan parametreleri, ötekiler temizlenir. */
export function withFrequency(task, frequency) {
  if (!FREQUENCIES.includes(frequency)) return task
  if (frequency === 'WEEKLY') {
    return { ...task, frequency, weekday: WEEKDAYS.includes(task.weekday) ? task.weekday : DEFAULT_WEEKDAY, monthly_rule: null, month_day: null, nth: null }
  }
  if (frequency === 'MONTHLY') {
    const rule = MONTHLY_RULES.includes(task.monthly_rule) ? task.monthly_rule : 'FIRST_WORKDAY'
    return withMonthlyRule({ ...task, frequency }, rule)
  }
  return { ...task, frequency, weekday: null, monthly_rule: null, month_day: null, nth: null }
}

/** Aylık kural değişimi: DAY → ayın günü (varsayılan 1), NTH_WEEKDAY → kaçıncı (1) + gün (1). */
export function withMonthlyRule(task, rule) {
  if (!MONTHLY_RULES.includes(rule)) return task
  if (rule === 'DAY') return { ...task, monthly_rule: rule, month_day: intOrNull(task.month_day) ?? 1, nth: null, weekday: null }
  if (rule === 'NTH_WEEKDAY') {
    return { ...task, monthly_rule: rule, month_day: null, nth: NTHS.includes(task.nth) ? task.nth : 1, weekday: WEEKDAYS.includes(task.weekday) ? task.weekday : 1 }
  }
  return { ...task, monthly_rule: rule, month_day: null, nth: null, weekday: null }
}

// ── Bağlantılar ─────────────────────────────────────────────────────────────────────────────────────────────────────

const BAD_URI_CHARS = /[\s"<>\\^`{|}]/

/**
 * Bağlantı adresinin sorunu (null = geçerli): `empty`, `too_long` (uzunluk ÖNCE — ayrıştırmadan), `scheme` (yalnız
 * http / https), `userinfo` (`kullanıcı:parola@` yasak), `invalid` (ayrıştırılamaz / boşluk / Java URI'nin reddettiği
 * karakter / bozuk yüzde kodlaması / ana makine yok).
 */
export function linkUrlProblem(raw, max = DEFAULT_LIMITS.url) {
  const s = strOr(raw).trim()
  if (!s) return 'empty'
  if (s.length > max) return 'too_long'
  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(s)
  if (!scheme) return 'invalid'
  const sch = scheme[1].toLowerCase()
  if (sch !== 'http' && sch !== 'https') return 'scheme'
  if (!s.slice(scheme[0].length).startsWith('//')) return 'invalid'
  // Yetki bölümü: "//" sonrası ilk "/", "?" ya da "#"e kadar
  const rest = s.slice(scheme[0].length + 2)
  let end = rest.length
  for (const c of ['/', '?', '#']) {
    const i = rest.indexOf(c)
    if (i >= 0 && i < end) end = i
  }
  if (rest.slice(0, end).includes('@')) return 'userinfo'
  if (BAD_URI_CHARS.test(s)) return 'invalid'
  if (/%(?![0-9A-Fa-f]{2})/.test(s)) return 'invalid'
  let u
  try { u = new URL(s) } catch { return 'invalid' }
  if (u.username || u.password) return 'userinfo'
  if (!u.hostname) return 'invalid'
  return null
}

/** Önerilen etiket: ana makine + yol (sorgu ve parça yok), ≤ 200 karakter. Geçersiz adres → boş. */
export function suggestLabel(raw, max = DEFAULT_LIMITS.label) {
  if (linkUrlProblem(raw) !== null) return ''
  try {
    const u = new URL(strOr(raw).trim())
    const path = u.pathname && u.pathname !== '/' ? u.pathname.replace(/\/+$/, '') : ''
    let label = `${u.host}${path}`
    try { label = decodeURI(label) } catch { /* bozuk kodlama — ham kalır */ }
    return label.length > max ? `${label.slice(0, max - 1)}…` : label
  } catch {
    return ''
  }
}

/**
 * Boş bir satıra adres yapıştırma: adres alanı doldurulur, etiket boşsa ana makine + yol önerilir. Satır boş değilse
 * ya da metin geçerli bir adres değilse null (çağıran olağan yapıştırmaya bırakır).
 */
export function pasteUrlIntoLink(link, text) {
  const s = strOr(text).trim()
  if (!s || strOr(link?.url).trim() || linkUrlProblem(s) !== null) return null
  return { label: strOr(link?.label).trim() ? link.label : suggestLabel(s), url: s }
}

export function addLink(task) {
  return { ...task, links: [...(task.links || []), { label: '', url: '' }] }
}

export function updateLink(task, index, patch) {
  const links = [...(task.links || [])]
  if (index < 0 || index >= links.length) return task
  links[index] = { ...links[index], ...patch }
  return { ...task, links }
}

export function removeLink(task, index) {
  return { ...task, links: (task.links || []).filter((_, i) => i !== index) }
}

/** Canlı sayaç seç / bırak — `max`'ı aşan ekleme yapılmaz. */
export function toggleSignal(task, key, max = DEFAULT_LIMITS.signals_per_task) {
  const cur = Array.isArray(task.signals) ? task.signals : []
  if (cur.includes(key)) return { ...task, signals: cur.filter((k) => k !== key) }
  if (cur.length >= max) return task
  return { ...task, signals: [...cur, key] }
}

// ── Doğrulama ───────────────────────────────────────────────────────────────────────────────────────────────────────

const err = (k, a = [], required = false) => ({ k, a, required })
const URL_PROBLEM_KEY = {
  empty: 'or.tpl.v.urlEmpty', too_long: 'or.tpl.v.urlTooLong', scheme: 'or.tpl.v.urlScheme',
  userinfo: 'or.tpl.v.urlUserinfo', invalid: 'or.tpl.v.urlInvalid',
}

function checkTitle(errors, key, raw, max) {
  const s = strOr(raw)
  if (/[\r\n]/.test(s)) errors[key] = err('or.tpl.v.singleLine')
  else if (!s.trim()) errors[key] = err('or.tpl.v.required', [], true)
  else if (s.trim().length > max) errors[key] = err('or.tpl.v.tooLong', [max])
}

function checkWindow(errors, prefix, from, to) {
  const f = hmOrNull(from)
  const t = hmOrNull(to)
  if (f && !HM_RE.test(f)) errors[`${prefix}.window_from`] = err('or.tpl.v.time')
  if (t && !HM_RE.test(t)) errors[`${prefix}.window_to`] = err('or.tpl.v.time')
  if (f && t && HM_RE.test(f) && HM_RE.test(t) && f >= t) errors[`${prefix}.window_to`] = err('or.tpl.v.windowOrder')
}

/**
 * Taslağı sunucu kurallarına göre denetler. Dönen harita: alan anahtarı → `{ k: i18n anahtarı, a: argümanlar,
 * required }` (`required` = yalnız "boş bırakılamaz"; arayüz bunları ancak kayıt denemesinden sonra gösterir).
 * `signalKeys` verilirse bilinmeyen sayaç da hatadır.
 */
export function validateTemplate(tpl, limits = DEFAULT_LIMITS, { signalKeys = null } = {}) {
  const L = resolveLimits(limits)
  const errors = {}
  const sections = tpl?.sections || []
  if (sections.length === 0) errors.template = err('or.tpl.v.sectionsMin', [], true)
  else if (sections.length > L.sections) errors.template = err('or.tpl.v.sectionsMax', [L.sections, sections.length])
  const total = countTasks(tpl)
  const active = countActive(tpl)
  const links = countLinks(tpl)
  if (!errors.template && total > L.tasks) errors.template = err('or.tpl.v.tasksMax', [L.tasks, total])
  if (!errors.template && active > L.active_tasks) errors.template = err('or.tpl.v.activeMax', [L.active_tasks, active])
  if (!errors.template && links > L.links_total) errors.template = err('or.tpl.v.linksTotal', [L.links_total, links])
  const seen = new Set()
  let badIds = false
  const known = signalKeys ? new Set(signalKeys) : null
  for (const s of sections) {
    if (!ID_RE.test(strOr(s.id)) || seen.has(s.id)) badIds = true
    seen.add(s.id)
    const sp = `template.${s.id}`
    checkTitle(errors, `${sp}.title`, s.title, L.section_title)
    checkWindow(errors, sp, s.window_from, s.window_to)
    const tasks = s.tasks || []
    if (tasks.length > L.tasks_per_section) errors[`${sp}.tasks`] = err('or.tpl.v.sectionTasksMax', [L.tasks_per_section, tasks.length])
    for (const t of tasks) {
      if (!ID_RE.test(strOr(t.id)) || seen.has(t.id)) badIds = true
      seen.add(t.id)
      const tp = `${sp}.${t.id}`
      checkTitle(errors, `${tp}.title`, t.title, L.title)
      if (lf(t.description).trim().length > L.description) errors[`${tp}.description`] = err('or.tpl.v.tooLong', [L.description])
      const f = t.frequency
      if (!FREQUENCIES.includes(f)) errors[`${tp}.frequency`] = err('or.tpl.v.frequency', [], true)
      else if (f === 'WEEKLY' && !WEEKDAYS.includes(intOrNull(t.weekday))) errors[`${tp}.weekday`] = err('or.tpl.v.weekday', [], true)
      else if (f === 'MONTHLY') {
        const rule = t.monthly_rule
        if (!MONTHLY_RULES.includes(rule)) errors[`${tp}.monthly_rule`] = err('or.tpl.v.monthlyRule', [], true)
        else if (rule === 'DAY') {
          const d = intOrNull(t.month_day)
          if (d == null || d < 1 || d > 31) errors[`${tp}.month_day`] = err('or.tpl.v.monthDay', [], true)
        } else if (rule === 'NTH_WEEKDAY') {
          if (!NTHS.includes(intOrNull(t.nth))) errors[`${tp}.nth`] = err('or.tpl.v.nth', [], true)
          if (!WEEKDAYS.includes(intOrNull(t.weekday))) errors[`${tp}.weekday`] = err('or.tpl.v.weekday', [], true)
        }
      }
      checkWindow(errors, tp, t.window_from, t.window_to)
      const rows = t.links || []
      const filled = rows.filter((l) => strOr(l?.url).trim() || strOr(l?.label).trim())
      if (filled.length > L.links_per_task) errors[`${tp}.links`] = err('or.tpl.v.linksMax', [L.links_per_task])
      rows.forEach((l, i) => {
        if (!strOr(l?.url).trim() && !strOr(l?.label).trim()) return
        const prob = linkUrlProblem(l?.url, L.url)
        if (prob) errors[`${tp}.links.${i}.url`] = err(URL_PROBLEM_KEY[prob], prob === 'too_long' ? [L.url] : [], prob === 'empty')
        if (singleLine(l?.label).trim().length > L.label) errors[`${tp}.links.${i}.label`] = err('or.tpl.v.tooLong', [L.label])
      })
      const sig = Array.isArray(t.signals) ? t.signals : []
      if (sig.length > L.signals_per_task) errors[`${tp}.signals`] = err('or.tpl.v.signalsMax', [L.signals_per_task])
      else if (new Set(sig).size !== sig.length || (known && sig.some((k) => !known.has(k)))) {
        errors[`${tp}.signals`] = err('or.tpl.v.signalUnknown')
      }
    }
  }
  if (badIds && !errors.template) errors.template = err('or.tpl.v.ids')
  return errors
}

/** Gösterilecek hatalar: geçersiz içerik her zaman, "boş" hataları yalnız kayıt denemesinden sonra. */
export function visibleErrors(errors, showRequired) {
  if (showRequired) return errors
  const out = {}
  for (const [k, v] of Object.entries(errors || {})) if (!v.required) out[k] = v
  return out
}

/** Hata anahtarının bölüm kimliği (`template.<bölüm>…`); genel `template` → null. */
export function errorSectionId(key) {
  const parts = strOr(key).split('.')
  return parts[0] === 'template' && parts.length >= 3 ? parts[1] : null
}

/** Hata anahtarının görev kimliği (görev düzeyi değilse null). */
export function errorTaskId(key) {
  const parts = strOr(key).split('.')
  // `template.<bölüm>.<özellik>` (3 parça) bölüm düzeyidir; görev düzeyi en az 4 parça
  if (parts[0] !== 'template' || parts.length < 4) return null
  return parts[2]
}

/** Anahtar bölüm düzeyinde mi (`template.<bölüm>.title|window_from|window_to|tasks`). */
export function isSectionLevelKey(key) {
  const parts = strOr(key).split('.')
  return parts[0] === 'template' && parts.length === 3 && SECTION_PROPS.has(parts[2])
}

/** Bölüm başına hata sayısı (kapalı bölüm başlığında rozet). */
export function errorCountsBySection(errors) {
  const out = new Map()
  for (const k of Object.keys(errors || {})) {
    const s = errorSectionId(k)
    if (s) out.set(s, (out.get(s) || 0) + 1)
  }
  return out
}

/** Görev başına hata imzası (memo'lu satırların karşılaştırması için kararlı dize). */
export function taskErrorSignature(errors, taskId) {
  const marker = `.${taskId}.`
  const keys = Object.keys(errors || {}).filter((k) => k.includes(marker)).sort()
  return keys.length ? keys.map((k) => `${k}=${errors[k]?.k || errors[k]}`).join('|') : ''
}

/**
 * Sunucu 400 yanıtındaki alan hataları → `{ alan: ileti }` (yalnız `template…` anahtarları) + genel ileti. Alan hatası
 * yoksa `general` sunucunun metnidir.
 */
export function mapServerErrors(res) {
  const all = serverFieldErrors(res) || {}
  const fields = {}
  let general = null
  for (const [k, v] of Object.entries(all)) {
    if (k === 'template' || !k.startsWith('template.')) general = general || v
    else fields[k] = v
  }
  if (!Object.keys(fields).length && !general && typeof res?.error === 'string') general = res.error
  return { fields, general }
}

// ── Wiki içe aktarımının taslağa uygulanması ────────────────────────────────────────────────────────────────────────

const foldTitle = (s) => fold(singleLine(s).trim()).replace(/\s+/g, ' ')

/** İçe aktarılan bölümleri yeni kimliklerle kopyalar (taslakta / asılda olan ya da birbiriyle çakışan kimlik değişir). */
export function reidSections(sections, taken = new Set()) {
  const out = []
  for (const s of sections || []) {
    const sid = !s?.id || taken.has(s.id) || !ID_RE.test(s.id) ? newId('s', taken) : (taken.add(s.id), s.id)
    const tasks = []
    for (const t of s?.tasks || []) {
      const tid = !t?.id || taken.has(t.id) || !ID_RE.test(t.id) ? newId('t', taken) : (taken.add(t.id), t.id)
      tasks.push({ ...normalizeTask(t), id: tid })
    }
    out.push({ ...normalizeSection({ ...s, tasks: [] }), id: sid, tasks })
  }
  return out
}

/**
 * İçe aktarımı taslağa uygular. `append`: aynı (Türkçe katlanmış) başlıklı bölüme görevler eklenir, yoksa bölüm sona
 * eklenir. `replace`: taslak bu bölümlerle değişir (`removedTasks` = silinen görev sayısı).
 */
export function applyImport(draft, sections, mode = 'append', taken = new Set()) {
  collectIds(draft, taken)
  const fresh = reidSections(sections, taken)
  const base = draft && Array.isArray(draft.sections) ? draft : emptyTemplate()
  if (mode === 'replace' || base.sections.length === 0) {
    return { template: { v: 1, sections: fresh }, removedTasks: mode === 'replace' ? countTasks(base) : 0 }
  }
  const out = base.sections.map((s) => ({ ...s, tasks: [...(s.tasks || [])] }))
  const byTitle = new Map()
  out.forEach((s, i) => { const k = foldTitle(s.title); if (k && !byTitle.has(k)) byTitle.set(k, i) })
  for (const s of fresh) {
    const k = foldTitle(s.title)
    if (k && byTitle.has(k)) out[byTitle.get(k)].tasks.push(...s.tasks)
    else {
      out.push(s)
      if (k) byTitle.set(k, out.length - 1)
    }
  }
  return { template: { ...base, sections: out }, removedTasks: 0 }
}

/** Birleşmiş şablonun sınır sorunları (içe aktar düğmesi bunlarla kilitlenir). `[{ k, a }]`. */
export function importLimitProblems(tpl, limits = DEFAULT_LIMITS) {
  const L = resolveLimits(limits)
  const out = []
  const sections = tpl?.sections || []
  if (sections.length > L.sections) out.push(err('or.tpl.v.sectionsMax', [L.sections, sections.length]))
  const total = countTasks(tpl)
  if (total > L.tasks) out.push(err('or.tpl.v.tasksMax', [L.tasks, total]))
  const active = countActive(tpl)
  if (active > L.active_tasks) out.push(err('or.tpl.v.activeMax', [L.active_tasks, active]))
  const links = countLinks(tpl)
  if (links > L.links_total) out.push(err('or.tpl.v.linksTotal', [L.links_total, links]))
  for (const s of sections) {
    const n = (s.tasks || []).length
    if (n > L.tasks_per_section) { out.push(err('or.tpl.v.sectionTasksMax', [L.tasks_per_section, n])); break }
  }
  return out
}

// ── Gösterim yardımcıları ───────────────────────────────────────────────────────────────────────────────────────────

const ISTANBUL_OFFSET_MS = 3 * 60 * 60 * 1000

/** UTC ISO damgası (bölgesiz olabilir) → İstanbul `{ date: 'dd.MM.yyyy', hm: 'HH:mm' }`; okunamazsa null. */
export function istanbulStamp(iso) {
  if (typeof iso !== 'string' || !iso.trim()) return null
  const s = iso.trim()
  const t = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : `${s}Z`)
  if (!Number.isFinite(t)) return null
  const d = new Date(t + ISTANBUL_OFFSET_MS).toISOString()
  return { date: `${d.slice(8, 10)}.${d.slice(5, 7)}.${d.slice(0, 4)}`, hm: d.slice(11, 16) }
}

/** Bir görevin kısa özeti için sayılar (kapalı ayrıntı satırı). */
export function taskSummary(task) {
  const links = (task.links || []).filter((l) => strOr(l?.url).trim()).length
  return {
    links,
    signals: (task.signals || []).length,
    description: !!strOr(task.description).trim(),
    window: !!(hmOrNull(task.window_from) || hmOrNull(task.window_to)),
  }
}

/**
 * Sıklığın okunur metni (`t` çağıranın çeviri işlevi). Düzenleyici, sürüm farkı ve önizleme aynı metni kullanır.
 * `f` = görev ya da `frequencyOf` çıktısı.
 */
export function frequencyLabel(t, f) {
  if (!f) return ''
  if (f.frequency === 'WEEKLY') return `${t('or.freq.WEEKLY')} · ${t(`or.weekday.${f.weekday ?? DEFAULT_WEEKDAY}`)}`
  if (f.frequency === 'MONTHLY') {
    if (f.monthly_rule === 'DAY') return `${t('or.freq.MONTHLY')} · ${t('or.tpl.monthDayText', f.month_day ?? '?')}`
    if (f.monthly_rule === 'NTH_WEEKDAY') {
      return `${t('or.freq.MONTHLY')} · ${t(`or.nth.${f.nth ?? 1}`)} ${t(`or.weekday.${f.weekday ?? 1}`)}`
    }
    return `${t('or.freq.MONTHLY')} · ${t(`or.monthly.${f.monthly_rule || 'FIRST_WORKDAY'}`)}`
  }
  return t('or.freq.DAILY')
}

/** Saat aralığının okunur metni; aralık yoksa boş. */
export function windowLabel(t, from, to) {
  const a = hmOrNull(from)
  const b = hmOrNull(to)
  if (a && b) return t('or.tpl.windowRange', a, b)
  if (b) return t('or.tpl.windowUntil', b)
  if (a) return t('or.tpl.windowFrom', a)
  return ''
}

// ── Takım rutin ayarları (`settings`, §2.12) ────────────────────────────────────────────────────────────────────────

export const SETTINGS_MAX_TIMES = 4
const NOTICE_KEYS = ['completion_notice', 'handover_notice']

/** Sunucu varsayılanları (§2.12). */
export const DEFAULT_SETTINGS = Object.freeze({
  enabled: true,
  workdays: [1, 2, 3, 4, 5],
  skip_holidays: true,
  day_start: '09:00',
  reminders: { enabled: true, times: ['16:30', '17:30'], section_deadlines: true, due_morning: true },
  completion_notice: { enabled: true, mail: true, push: true },
  handover_notice: { enabled: true, mail: true, push: true },
})

const boolOr = (v, d) => (typeof v === 'boolean' ? v : d)
export const isHm = (v) => typeof v === 'string' && HM_RE.test(v.trim())

/** Gevşek okuma: geçersiz değer → varsayılan (sunucunun okuma kuralı). Her zaman tam, bağımsız bir nesne döner. */
export function normalizeSettings(raw) {
  const r = raw && typeof raw === 'object' ? raw : {}
  const d = DEFAULT_SETTINGS
  const wd = Array.isArray(r.workdays)
    ? [...new Set(r.workdays.filter((x) => Number.isInteger(x) && x >= 1 && x <= 7))].sort((a, b) => a - b)
    : null
  const rem = r.reminders && typeof r.reminders === 'object' ? r.reminders : {}
  const times = Array.isArray(rem.times)
    ? [...new Set(rem.times.filter(isHm).map((x) => x.trim()))].sort().slice(0, SETTINGS_MAX_TIMES)
    : [...d.reminders.times]
  const notice = (key) => {
    const n = r[key] && typeof r[key] === 'object' ? r[key] : {}
    return { enabled: boolOr(n.enabled, d[key].enabled), mail: boolOr(n.mail, d[key].mail), push: boolOr(n.push, d[key].push) }
  }
  return {
    enabled: boolOr(r.enabled, d.enabled),
    workdays: wd && wd.length ? wd : [...d.workdays],
    skip_holidays: boolOr(r.skip_holidays, d.skip_holidays),
    day_start: isHm(r.day_start) ? r.day_start.trim() : d.day_start,
    reminders: {
      enabled: boolOr(rem.enabled, d.reminders.enabled),
      times,
      section_deadlines: boolOr(rem.section_deadlines, d.reminders.section_deadlines),
      due_morning: boolOr(rem.due_morning, d.reminders.due_morning),
    },
    completion_notice: notice('completion_notice'),
    handover_notice: notice('handover_notice'),
  }
}

/** Karşılaştırma biçimi: saatler kırpılır ve sıralanır (boş satırlar sonda kalır — eklenmiş boş satır da değişikliktir). */
function canonSettings(s) {
  const times = (s?.reminders?.times || []).map((x) => String(x ?? '').trim())
  const filled = times.filter(Boolean).sort()
  const blanks = times.filter((x) => !x)
  return {
    ...s,
    workdays: [...(s?.workdays || [])].sort((a, b) => a - b),
    day_start: String(s?.day_start ?? '').trim(),
    reminders: { ...s?.reminders, times: [...filled, ...blanks] },
  }
}

/**
 * Kayıt gövdesi: yalnız DEĞİŞEN alanlar (iç içe nesnelerde yalnız değişen alt anahtarlar). Saat listesi sıralı ve tekil
 * gönderilir. Hiçbir şey değişmediyse boş nesne.
 */
export function settingsPatch(base, form) {
  const b = canonSettings(normalizeSettings(base))
  const f = canonSettings(form || {})
  const out = {}
  for (const k of ['enabled', 'workdays', 'skip_holidays', 'day_start']) {
    if (JSON.stringify(f[k]) !== JSON.stringify(b[k])) out[k] = f[k]
  }
  for (const k of ['reminders', ...NOTICE_KEYS]) {
    const sub = {}
    for (const [kk, v] of Object.entries(f[k] || {})) {
      if (JSON.stringify(v) !== JSON.stringify(b[k]?.[kk])) sub[kk] = v
    }
    if (Object.keys(sub).length) out[k] = sub
  }
  if (out.reminders?.times) out.reminders.times = [...new Set(out.reminders.times.filter(Boolean))]
  return out
}

/** Değişen ayarların istemci doğrulaması (sunucu kuralının aynası): `settings.<yol>` → `{ k, a }`. */
export function validateSettings(form, patch = null) {
  const p = patch || form || {}
  const f = form || {}
  const out = {}
  if ('workdays' in p && !(f.workdays || []).length) out['settings.workdays'] = err('or.set.v.workdaysMin')
  if ('day_start' in p && !isHm(f.day_start)) out['settings.day_start'] = err('or.set.v.time')
  if (p.reminders && 'times' in p.reminders) {
    const raw = (f.reminders?.times || []).map((x) => String(x ?? '').trim())
    if (raw.length > SETTINGS_MAX_TIMES) out['settings.reminders.times'] = err('or.set.v.timesMax', [SETTINGS_MAX_TIMES])
    else if (raw.some((x) => !isHm(x))) out['settings.reminders.times'] = err('or.set.v.timesInvalid')
    else if (new Set(raw).size !== raw.length) out['settings.reminders.times'] = err('or.set.v.timesDuplicate')
  }
  for (const k of NOTICE_KEYS) {
    if (!p[k]) continue
    const n = f[k] || {}
    if (n.enabled && !n.mail && !n.push) out[`settings.${k}.mail`] = err('or.set.v.channelRequired')
  }
  return out
}
