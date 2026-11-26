/**
 * Nöbet Rutinleri — şablon sürümleri arası FARK (2026-11-26). Saf: React ve ağa bağlı değildir.
 *
 * <p>İki şablon (`{ sections: [{ id, title, window_from, window_to, tasks: [...] }] }`) karşılaştırılır; kimlikler
 * kalıcıdır (`s-…` / `t-…`), eşleştirme kimlikle yapılır. Sonuç: bölümler ve görevler için eklenen / silinen / adı
 * değişen / taşınan, görevlerde sıklık, zorunlu, etkin, açıklama, zaman aralığı, bağlantılar ve canlı sayaçlar.
 *
 * <p>GİZLİLİK: bağlantılar yalnız ETİKET + ANA MAKİNE (host) olarak döner — tam adres asla (bağlantılarda belirteç
 * olabilir; sürüm geçmişi takımın herkesine açık). "Taşındı": ortak öğelerin sırası en uzun artan alt dizi (LIS) ile
 * karşılaştırılır — bir görev yer değiştirince yalnız o görev "taşındı" sayılır, kaydırılan komşuları sayılmaz.
 */

const MAX_URL = 2000

/** Bağlantının ana makinesi (gösterim için); okunamazsa boş. Tam adres hiçbir yerde döndürülmez. */
export function linkHost(url) {
  if (typeof url !== 'string') return ''
  const s = url.trim()
  if (!s || s.length > MAX_URL) return ''
  try {
    const u = new URL(s)
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.hostname : ''
  } catch {
    return ''
  }
}

const str = (v) => (typeof v === 'string' ? v : '')
const sectionsOf = (tpl) => (Array.isArray(tpl?.sections) ? tpl.sections.filter((s) => s && typeof s === 'object') : [])
const tasksOf = (sec) => (Array.isArray(sec?.tasks) ? sec.tasks.filter((t) => t && typeof t === 'object') : [])
const titleOf = (x) => str(x?.title).trim()
const nz = (v) => (v === undefined || v === '' ? null : v)

/** Görevin sıklık imzası — yalnız o sıklığın kullandığı parametreler. */
export function frequencyOf(task) {
  const f = ['DAILY', 'WEEKLY', 'MONTHLY'].includes(task?.frequency) ? task.frequency : 'DAILY'
  if (f === 'WEEKLY') return { frequency: f, weekday: nz(task.weekday) ?? 5 }
  if (f === 'MONTHLY') {
    const rule = nz(task.monthly_rule) ?? 'FIRST_WORKDAY'
    if (rule === 'DAY') return { frequency: f, monthly_rule: rule, month_day: nz(task.month_day) }
    if (rule === 'NTH_WEEKDAY') return { frequency: f, monthly_rule: rule, nth: nz(task.nth), weekday: nz(task.weekday) }
    return { frequency: f, monthly_rule: rule }
  }
  return { frequency: f }
}

const sameJson = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const windowOf = (x) => ({ from: nz(x?.window_from), to: nz(x?.window_to) })
const urlKey = (url) => str(url).trim()
const linkView = (l) => ({ label: str(l?.label).trim(), host: linkHost(l?.url) })

function linksOf(task) {
  const out = []
  for (const l of Array.isArray(task?.links) ? task.links : []) {
    if (!l || typeof l !== 'object') continue
    const k = urlKey(l.url)
    if (!k && !str(l.label).trim()) continue
    out.push(l)
  }
  return out
}

/**
 * En uzun artan alt dizinin (LIS) elemanlarının KONUMLARI — O(n log n). `values` sayı dizisi.
 * @returns {Set<number>} LIS'te kalan konumlar
 */
export function lisPositions(values) {
  const n = values.length
  const tails = []          // tails[k] = uzunluğu k+1 olan dizinin son elemanının konumu
  const prev = new Array(n).fill(-1)
  for (let i = 0; i < n; i++) {
    let lo = 0
    let hi = tails.length
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (values[tails[mid]] < values[i]) lo = mid + 1
      else hi = mid
    }
    if (lo > 0) prev[i] = tails[lo - 1]
    tails[lo] = i
  }
  const keep = new Set()
  let k = tails.length ? tails[tails.length - 1] : -1
  let guard = 0
  while (k >= 0 && guard++ <= n) {
    keep.add(k)
    k = prev[k]
  }
  return keep
}

/** `nextIds` sırasında, `prevIds`'te de olan öğelerden yer değiştirenler (LIS dışında kalanlar). */
function movedAmong(prevIds, nextIds) {
  const pos = new Map(prevIds.map((id, i) => [id, i]))
  const common = nextIds.filter((id) => pos.has(id))
  const keep = lisPositions(common.map((id) => pos.get(id)))
  return common.filter((_, i) => !keep.has(i))
}

function emptyDiff() {
  return {
    sections: { added: [], removed: [], renamed: [], moved: [], window: [] },
    tasks: {
      added: [], removed: [], renamed: [], moved: [], frequency: [], required: [], active: [], description: [],
      window: [], links: [], signals: [],
    },
    count: 0,
  }
}

/**
 * İki şablonun farkı. `prev` null / boş olabilir (ilk sürüm → her şey "eklendi").
 * @returns {{sections: object, tasks: object, count: number}}
 */
export function diffTemplates(prev, next) {
  const d = emptyDiff()
  const ps = sectionsOf(prev)
  const ns = sectionsOf(next)
  const psById = new Map(ps.map((s) => [s.id, s]))
  const nsById = new Map(ns.map((s) => [s.id, s]))

  // ── Bölümler ──
  for (const s of ns) {
    const p = psById.get(s.id)
    if (!p) { d.sections.added.push({ id: s.id, title: titleOf(s) }); continue }
    if (titleOf(p) !== titleOf(s)) d.sections.renamed.push({ id: s.id, from: titleOf(p), to: titleOf(s) })
    if (!sameJson(windowOf(p), windowOf(s))) d.sections.window.push({ id: s.id, title: titleOf(s), from: windowOf(p), to: windowOf(s) })
  }
  for (const p of ps) if (!nsById.has(p.id)) d.sections.removed.push({ id: p.id, title: titleOf(p) })
  for (const id of movedAmong(ps.map((s) => s.id), ns.map((s) => s.id))) {
    d.sections.moved.push({ id, title: titleOf(nsById.get(id)) })
  }

  // ── Görevler ──
  const pTasks = new Map()      // id → { task, sectionId }
  for (const s of ps) for (const t of tasksOf(s)) if (!pTasks.has(t.id)) pTasks.set(t.id, { task: t, sectionId: s.id })
  const nTasks = new Map()
  for (const s of ns) for (const t of tasksOf(s)) if (!nTasks.has(t.id)) nTasks.set(t.id, { task: t, sectionId: s.id })
  const secTitle = (id, map) => titleOf(map.get(id))

  for (const s of ns) {
    for (const t of tasksOf(s)) {
      const entry = pTasks.get(t.id)
      const title = titleOf(t)
      if (!entry) { d.tasks.added.push({ id: t.id, title, section: titleOf(s) }); continue }
      const p = entry.task
      if (titleOf(p) !== title) d.tasks.renamed.push({ id: t.id, from: titleOf(p), to: title, section: titleOf(s) })
      if (entry.sectionId !== s.id) {
        d.tasks.moved.push({ id: t.id, title, from_section: secTitle(entry.sectionId, psById), to_section: titleOf(s) })
      }
      if (!sameJson(frequencyOf(p), frequencyOf(t))) d.tasks.frequency.push({ id: t.id, title, from: frequencyOf(p), to: frequencyOf(t) })
      if ((p.required !== false) !== (t.required !== false)) d.tasks.required.push({ id: t.id, title, to: t.required !== false })
      if ((p.active !== false) !== (t.active !== false)) d.tasks.active.push({ id: t.id, title, to: t.active !== false })
      if (str(p.description).trim() !== str(t.description).trim()) d.tasks.description.push({ id: t.id, title })
      if (!sameJson(windowOf(p), windowOf(t))) d.tasks.window.push({ id: t.id, title, from: windowOf(p), to: windowOf(t) })
      const pl = linksOf(p)
      const nl = linksOf(t)
      const pKeys = new Map(pl.map((l) => [urlKey(l.url), l]))
      const nKeys = new Map(nl.map((l) => [urlKey(l.url), l]))
      const added = nl.filter((l) => !pKeys.has(urlKey(l.url))).map(linkView)
      const removed = pl.filter((l) => !nKeys.has(urlKey(l.url))).map(linkView)
      const relabeled = nl.filter((l) => pKeys.has(urlKey(l.url)) && str(pKeys.get(urlKey(l.url)).label).trim() !== str(l.label).trim())
        .map((l) => ({ from: str(pKeys.get(urlKey(l.url)).label).trim(), to: str(l.label).trim(), host: linkHost(l.url) }))
      if (added.length || removed.length || relabeled.length) d.tasks.links.push({ id: t.id, title, added, removed, relabeled })
      const psig = new Set(Array.isArray(p.signals) ? p.signals : [])
      const nsig = new Set(Array.isArray(t.signals) ? t.signals : [])
      const sAdd = [...nsig].filter((k) => !psig.has(k))
      const sRem = [...psig].filter((k) => !nsig.has(k))
      if (sAdd.length || sRem.length) d.tasks.signals.push({ id: t.id, title, added: sAdd, removed: sRem })
    }
  }
  for (const [id, { task, sectionId }] of pTasks) {
    if (!nTasks.has(id)) d.tasks.removed.push({ id, title: titleOf(task), section: secTitle(sectionId, psById) })
  }
  // Aynı bölümde kalan görevlerin sıra değişimi
  const crossMoved = new Set(d.tasks.moved.map((m) => m.id))
  for (const s of ns) {
    const p = psById.get(s.id)
    if (!p) continue
    const prevIds = tasksOf(p).map((t) => t.id).filter((id) => nTasks.get(id)?.sectionId === s.id)
    const nextIds = tasksOf(s).map((t) => t.id).filter((id) => pTasks.get(id)?.sectionId === s.id)
    for (const id of movedAmong(prevIds, nextIds)) {
      if (crossMoved.has(id)) continue
      const title = titleOf(nTasks.get(id)?.task)
      d.tasks.moved.push({ id, title, from_section: titleOf(s), to_section: titleOf(s) })
    }
  }

  let count = 0
  for (const group of [d.sections, d.tasks]) for (const list of Object.values(group)) count += list.length
  d.count = count
  return d
}

/** Farkın kategori başına sayısı (liste özeti için) ve boş mu. */
export function diffCounts(diff) {
  const out = {}
  for (const [scope, group] of Object.entries(diff?.sections ? { sections: diff.sections, tasks: diff.tasks } : {})) {
    for (const [k, list] of Object.entries(group)) out[`${scope}.${k}`] = list.length
  }
  return out
}

/** Fark boş mu (hiçbir değişiklik yok). */
export function isEmptyDiff(diff) {
  return !diff || diff.count === 0
}
