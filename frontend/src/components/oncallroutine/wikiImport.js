/**
 * Nöbet Rutinleri — wiki / markdown / HTML İÇE AKTARIMI (2026-11-26, sözleşme §7). Saf: React ve ağa bağlı değildir;
 * yalnız tarayıcının `DOMParser`'ını kullanır (yoksa düz metin yolu). Sunucu bu dosyanın çıktısını hiç görmez —
 * düzenleyici taslağa yazar, kayıt olağan şablon PUT'udur ve sunucu onu katı denetler.
 *
 * <p>SINIRLI ve DOĞRUSAL: girdi ≤ 1 000 000 karakter (fazlası `TOO_LARGE`), DOM düğümü ≤ 50 000 (yinelemeli yığın,
 * özyineleme yok), satır ≤ 20 000; 4 000 karakterden uzun satır atlanır (düzenli ifadeler kısa satırlarda koşar).
 * Çıktı tavanları: bölüm ≤ 30, görev ≤ 300 (≤ 200 etkin; fazlası pasif gelir), bölüm başına görev ≤ 100, görev başına
 * bağlantı ≤ 20; aşılan her şey `stats` içinde SAYILIR — hiçbir şey sessizce düşmez. Başlık ≤ 200 (bölüm ≤ 120),
 * sözcük sınırında kesilir + "…".
 *
 * <p>Yalnız http / https bağlantıları alınır (ötekiler `dropped_links`); adres kuralı düzenleyicininkiyle aynı
 * (`templateEditorModel.linkUrlProblem`: kullanıcı:parola@ yok, ≤ 2000 karakter).
 */
import { fold } from '../../utils/keywordFold.js'
import { DEFAULT_LIMITS, linkUrlProblem, makeIdFactory } from './templateEditorModel.js'

export const WIKI_MAX_INPUT = 1_000_000
export const WIKI_MAX_NODES = 50_000
export const WIKI_MAX_LINES = 20_000
export const WIKI_MAX_LINE_CHARS = 4000
export const WIKI_CAPS = Object.freeze({
  sections: 30,
  tasks: 300,
  active: 200,
  sectionTasks: 100,
  links: 20,
  title: 200,
  sectionTitle: 120,
  description: 2000,
  plainHeading: 120,
})

const GENERAL_TITLE = { tr: 'Genel', en: 'General' }

/** Satır kuralları (§7.2) — kısa satırlarda koşar. */
const HASH_HEADING_RE = /^#{1,6}\s+(.+)/
const LIST_ITEM_RE = /^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$/

/** Hafta günü adları (katlanmış, uzun önce — "cumartesi" "cuma"dan, "pazartesi" "pazar"dan önce denenir). */
const WEEKDAY_NAMES = [
  ['pazartesi', 1], ['cumartesi', 6], ['wednesday', 3], ['çarşamba', 3], ['perşembe', 4], ['thursday', 4], ['saturday', 6],
  ['tuesday', 2], ['monday', 1], ['friday', 5], ['sunday', 7], ['pazar', 7], ['cuma', 5], ['sali', 2],
].map(([n, d]) => [fold(n), d])
const WEEKDAY_ALT = WEEKDAY_NAMES.map(([n]) => n).join('|')
const EVERY_WEEKDAY_RE = new RegExp(`(?:^|[^\\p{L}])(?:her|every)\\s+(${WEEKDAY_ALT})`, 'u')
const MONTHLY_RE = /(?:^|[^\p{L}])aylik|monthly/u
const LETTER_RE = /\p{L}/u

const emptyStats = () => ({
  sections: 0,
  tasks: 0,
  links: 0,
  dropped_links: 0,
  skipped_lines: 0,
  truncated_titles: 0,
  description_lines: 0,
  capped: { sections: 0, tasks: 0, links: 0, active: 0, description: 0 },
  node_limit: false,
})

// ── Metin yardımcıları ──────────────────────────────────────────────────────────────────────────────────────────────

const collapse = (s) => String(s ?? '').replace(/\s+/g, ' ').trim()

/** Satır içi markdown işaretlerini kaldırır (kalın / italik / kod). */
function cleanInline(s) {
  return collapse(String(s ?? '').split('**').join('').split('__').join('').split('`').join(''))
}

/** Sözcük sınırında keser + "…"; `onCut` kesildiyse çağrılır. */
export function cutTitle(raw, max, onCut) {
  const s = collapse(raw)
  if (s.length <= max) return s
  onCut?.()
  const room = s.slice(0, max - 1)
  const sp = room.lastIndexOf(' ')
  const head = sp >= Math.floor(max / 2) ? room.slice(0, sp) : room
  return `${head.replace(/[\s,;:.-]+$/, '')}…`
}

/** Ana makine + yol (sorgu yok), ≤ 200 karakter — etiketi olmayan bağlantının adı. */
export function hostPath(url) {
  try {
    const u = new URL(url)
    const path = u.pathname && u.pathname !== '/' ? u.pathname.replace(/\/+$/, '') : ''
    let s = `${u.host}${path}`
    try { s = decodeURI(s) } catch { /* ham kalır */ }
    return s.length > DEFAULT_LIMITS.label ? `${s.slice(0, DEFAULT_LIMITS.label - 1)}…` : s
  } catch {
    return ''
  }
}

/**
 * `[etiket](adres)` bağlantılarını çıkarır: metin etiketlere indirgenir. DOĞRUSAL — sonraki `]` / `)` konumları
 * önbelleklenir ("[[[[…" girdisinde ikinci dereceye düşmez).
 */
export function extractMarkdownLinks(text) {
  const s = String(text ?? '')
  const links = []
  let out = ''
  let i = 0
  let close = -2   // bir sonraki ']' (yeniden hesap yalnız geçilince)
  let paren = -2   // bir sonraki ')'
  while (i < s.length) {
    const open = s.indexOf('[', i)
    if (open < 0) { out += s.slice(i); break }
    if (close !== -1 && close <= open) close = s.indexOf(']', open + 1)
    if (close < 0) { out += s.slice(i); break }
    if (s[close + 1] === '(') {
      if (paren !== -1 && paren <= close + 1) paren = s.indexOf(')', close + 2)
      if (paren < 0) { out += s.slice(i); break }
      const label = s.slice(open + 1, close)
      const inner = s.slice(close + 2, paren).trim()
      const sp = inner.search(/\s/)
      const url = sp >= 0 ? inner.slice(0, sp) : inner
      out += s.slice(i, open) + label
      links.push({ label: cleanInline(label), url: url.replace(/^<|>$/g, '') })
      i = paren + 1
    } else {
      out += s.slice(i, open + 1)
      i = open + 1
    }
  }
  return { text: out, links }
}

const URL_END = new Set([' ', '\t', '\n', '\r', ')', ']', '>', '<', '"', "'"])

/** Çıplak `http(s)://…` adresleri ve kalan metin. Doğrusal. */
export function extractBareUrls(text) {
  const s = String(text ?? '')
  const urls = []
  let rest = ''
  let i = 0
  while (i < s.length) {
    const at = s.indexOf('http', i)
    if (at < 0) { rest += s.slice(i); break }
    const lower = s.slice(at, at + 8).toLowerCase()
    const isUrl = lower.startsWith('https://') || lower.startsWith('http://')
    const leftOk = at === 0 || !LETTER_RE.test(s[at - 1])
    if (!isUrl || !leftOk) { rest += s.slice(i, at + 4); i = at + 4; continue }
    let j = at
    while (j < s.length && !URL_END.has(s[j])) j++
    let url = s.slice(at, j)
    const trail = /[.,;:!?]+$/.exec(url)
    if (trail) { url = url.slice(0, -trail[0].length); j -= trail[0].length }
    urls.push(url)
    rest += s.slice(i, at)
    i = j
  }
  return { urls, rest }
}

/** Katlanmış metinde, solunda harf olmayan ilk hafta günü adı (aynı konumda uzun ad kazanır). */
export function findWeekday(folded) {
  const s = String(folded ?? '')
  let best = null
  for (const [name, day] of WEEKDAY_NAMES) {
    let from = 0
    let guard = 0
    while (from <= s.length && guard++ < 1000) {
      const i = s.indexOf(name, from)
      if (i < 0) break
      if (i === 0 || !LETTER_RE.test(s[i - 1])) {
        if (!best || i < best.at) best = { at: i, day }
        break
      }
      from = i + 1
    }
  }
  return best ? best.day : null
}

/** "her <gün>" / "every <day>" geçiyorsa o gün; yoksa null. */
export function everyWeekday(folded) {
  const m = EVERY_WEEKDAY_RE.exec(String(folded ?? ''))
  if (!m) return null
  const hit = WEEKDAY_NAMES.find(([n]) => n === m[1])
  return hit ? hit[1] : null
}

/** Sıklık çıkarımı (§7.4). */
export function inferFrequency(sectionTitle, taskTitle) {
  const st = fold(collapse(sectionTitle))
  const tt = fold(collapse(taskTitle))
  const every = everyWeekday(tt)
  if (every) return { frequency: 'WEEKLY', weekday: every, monthly_rule: null }
  if (st.includes('haftal') || st.includes('weekly')) {
    return { frequency: 'WEEKLY', weekday: findWeekday(tt) ?? findWeekday(st) ?? 5, monthly_rule: null }
  }
  if (MONTHLY_RE.test(st)) return { frequency: 'MONTHLY', weekday: null, monthly_rule: 'FIRST_WORKDAY' }
  return { frequency: 'DAILY', weekday: null, monthly_rule: null }
}

// ── Ham yapı (bölüm → görev → bağlantı / açıklama satırı) ───────────────────────────────────────────────────────────

function createBuilder(lang, stats) {
  const sections = []
  let cur = null
  let task = null
  const ensure = () => {
    if (!cur) {
      cur = { title: GENERAL_TITLE[lang] || GENERAL_TITLE.tr, tasks: [], fromHeading: false }
      sections.push(cur)
    }
    return cur
  }
  const addLink = (t, label, url) => {
    const u = String(url ?? '').trim()
    if (linkUrlProblem(u) !== null) { stats.dropped_links += 1; return }
    t.links.push({ label: cleanInline(label), url: u })
  }
  return {
    sections,
    section(title) {
      cur = { title: cleanInline(title).replace(/:\s*$/, ''), tasks: [], fromHeading: true }
      sections.push(cur)
      task = null
    },
    task(title, links = []) {
      const s = ensure()
      let t = cleanInline(title)
      const valid = []
      for (const l of links) {
        if (linkUrlProblem(String(l.url ?? '').trim()) !== null) { stats.dropped_links += 1; continue }
        valid.push(l)
      }
      if (!t && valid.length) t = valid[0].label || hostPath(valid[0].url)
      if (!t) { stats.skipped_lines += 1; task = null; return null }
      task = { title: t, links: [], desc: [] }
      for (const l of valid) task.links.push({ label: cleanInline(l.label), url: String(l.url).trim() })
      s.tasks.push(task)
      return task
    },
    current() { return task },
    link(t, label, url) { addLink(t, label, url) },
    description(t, text) {
      const s = cleanInline(text)
      if (s) { t.desc.push(s); stats.description_lines += 1 }
    },
  }
}

/** Alt madde (görevin altındaki derin madde): bağlantı(lar) ya da açıklama satırı. */
function applySubItem(b, t, text, stats) {
  const md = extractMarkdownLinks(text)
  const bare = extractBareUrls(md.text)
  if (md.links.length) {
    for (const l of md.links) b.link(t, l.label || hostPath(l.url), l.url)
    for (const u of bare.urls) b.link(t, hostPath(u), u)
    return
  }
  if (bare.urls.length) {
    const leftover = cleanInline(bare.rest).replace(/^[\s:–—-]+|[\s:–—-]+$/g, '')
    for (const u of bare.urls) b.link(t, bare.urls.length === 1 && leftover ? leftover : hostPath(u), u)
    return
  }
  if (cleanInline(text)) b.description(t, text)
  else stats.skipped_lines += 1
}

// ── Markdown / düz metin (§7.2) ─────────────────────────────────────────────────────────────────────────────────────

/** Tüm satırı kalın başlık mı (`**X**` / `__X__`, ardından isteğe bağlı ":" ve boşluk). Doğrusal eşdeğer. */
export function boldHeadingText(line) {
  const s = String(line ?? '')
  for (const mark of ['**', '__']) {
    if (!s.startsWith(mark)) continue
    let end = s.length
    while (end > 0 && /\s/.test(s[end - 1])) end--
    if (end > 0 && s[end - 1] === ':') end--
    if (end - mark.length < mark.length + 1) continue
    if (s.slice(end - mark.length, end) !== mark) continue
    const inner = s.slice(mark.length, end - mark.length)
    if (inner.length >= 1) return inner
  }
  return null
}

function indentOf(ws) {
  let n = 0
  for (const c of ws) n += c === '\t' ? 4 : 1
  return n
}

function parseText(text, b, stats) {
  const lines = String(text ?? '').split(/\r\n|\r|\n/)
  if (lines.length > WIKI_MAX_LINES) {
    for (let i = WIKI_MAX_LINES; i < lines.length; i++) if (lines[i].trim()) stats.skipped_lines += 1
    lines.length = WIKI_MAX_LINES
  }
  // Satırları sınıflandır
  const entries = []
  const kinds = new Array(lines.length).fill(null)
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i]
    if (!raw.trim()) continue
    if (raw.length > WIKI_MAX_LINE_CHARS) { kinds[i] = { type: 'skip' }; continue }
    const h = HASH_HEADING_RE.exec(raw)
    if (h) { kinds[i] = { type: 'heading', title: h[1].replace(/\s#+\s*$/, '') }; continue }
    const bold = boldHeadingText(raw)
    if (bold != null) { kinds[i] = { type: 'heading', title: bold }; continue }
    const li = LIST_ITEM_RE.exec(raw)
    if (li) { kinds[i] = { type: 'item', indent: indentOf(li[1]), text: li[4] }; continue }
    kinds[i] = { type: 'plain', text: raw }
  }
  // Düz satır başlığı: girintisiz, ≤ 120 karakter, sonraki dolu satır liste maddesi
  let nextKind = null
  for (let i = lines.length - 1; i >= 0; i--) {
    const k = kinds[i]
    if (!k) continue
    if (k.type === 'plain') {
      const t = k.text.trim()
      k.heading = !/^\s/.test(k.text) && t.length <= WIKI_CAPS.plainHeading && nextKind === 'item'
    }
    nextKind = k.type
  }
  for (const k of kinds) {
    if (!k) continue
    if (k.type === 'skip') stats.skipped_lines += 1
    else if (k.type === 'heading') entries.push({ type: 'heading', title: k.title })
    else if (k.type === 'item') entries.push(k)
    else if (k.heading) entries.push({ type: 'heading', title: k.text })
    else stats.skipped_lines += 1
  }
  // Bölüm bölüm: en küçük girintideki madde görev, daha derini görevin altı
  let items = []
  const flush = () => {
    if (!items.length) return
    let min = Infinity
    for (const it of items) min = Math.min(min, it.indent)
    for (const it of items) {
      const t = b.current()
      if (it.indent === min || !t) {
        const md = extractMarkdownLinks(it.text)
        b.task(md.text, md.links)
      } else {
        applySubItem(b, t, it.text, stats)
      }
    }
    items = []
  }
  for (const e of entries) {
    if (e.type === 'heading') { flush(); b.section(e.title) } else items.push(e)
  }
  flush()
}

// ── HTML (Azure DevOps wiki kopyası, §7.3) ──────────────────────────────────────────────────────────────────────────

const HEADINGS = new Set(['H1', 'H2', 'H3', 'H4', 'H5', 'H6'])
const LISTS = new Set(['UL', 'OL'])
const DROP = ['script', 'style', 'template', 'noscript']
const SKIP_COUNTED = new Set(['TABLE', 'IMG', 'PRE', 'BLOCKQUOTE', 'FIGURE', 'DL', 'SVG', 'IFRAME', 'VIDEO', 'AUDIO', 'CANVAS'])
const BLOCKISH = new Set(['P', 'DIV', 'BR', 'SECTION', 'ARTICLE', 'TR', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6'])
const TEXT = 3
const ELEMENT = 1

function makeBudget(stats) {
  let left = WIKI_MAX_NODES
  return () => {
    if (left <= 0) { stats.node_limit = true; return false }
    left -= 1
    return true
  }
}

/** `p` / `div` yalnız kalın metin mi taşıyor (boşluk + `br` dışında yalnız `strong` / `b`)? Başlık metni ya da null. */
function boldOnlyText(el) {
  let found = false
  let text = ''
  for (const n of el.childNodes) {
    if (n.nodeType === TEXT) {
      if (n.nodeValue.trim()) return null
      continue
    }
    if (n.nodeType !== ELEMENT) continue
    const tag = n.tagName
    if (tag === 'BR') continue
    if (tag !== 'STRONG' && tag !== 'B') return null
    const s = collapse(n.textContent)
    if (s) { found = true; text += (text ? ' ' : '') + s }
  }
  return found ? collapse(text).replace(/:\s*$/, '') : null
}

/**
 * Bir `li`'nin KENDİ içeriği: iç listeler hariç metin, bağlantılar (`a[href]`) ve iç listeler. Yinelemeli, düğüm bütçeli.
 */
function ownContent(li, take) {
  let text = ''
  const anchors = []
  const nested = []
  const stack = [...li.childNodes].reverse()
  while (stack.length) {
    if (!take()) break
    const n = stack.pop()
    if (n.nodeType === TEXT) { text += n.nodeValue; continue }
    if (n.nodeType !== ELEMENT) continue
    const tag = n.tagName
    if (LISTS.has(tag)) { nested.push(n); continue }
    if (tag === 'INPUT' || DROP.includes(tag.toLowerCase())) continue
    if (BLOCKISH.has(tag)) text += ' '
    if (tag === 'A') {
      const href = n.getAttribute('href')
      if (href != null) anchors.push({ label: collapse(n.textContent), url: href.trim() })
    }
    const kids = n.childNodes
    for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i])
  }
  return { text: collapse(text), anchors, nested }
}

function processTaskLi(li, b, stats, take) {
  const own = ownContent(li, take)
  const t = b.task(own.text, own.anchors)
  // İç listeler: bağlantı ya da açıklama satırı (görev yoksa ilk madde görev olur)
  const queue = [...own.nested]
  let guard = 0
  while (queue.length && guard++ < WIKI_MAX_NODES) {
    const list = queue.shift()
    for (const child of list.children) {
      if (!take()) return
      if (child.tagName === 'LI') {
        const sub = ownContent(child, take)
        const owner = b.current()
        if (!owner) {
          b.task(sub.text, sub.anchors)
        } else if (sub.anchors.length) {
          for (const a of sub.anchors) b.link(owner, a.label || hostPath(a.url), a.url)
        } else if (sub.text) {
          b.description(owner, sub.text)
        }
        queue.push(...sub.nested)
      } else if (LISTS.has(child.tagName)) {
        queue.push(child)
      }
    }
  }
  return t
}

function parseHtml(html, b, stats) {
  let doc
  try { doc = new DOMParser().parseFromString(String(html), 'text/html') } catch { return false }
  if (!doc?.body) return false
  for (const sel of DROP) for (const n of [...doc.querySelectorAll(sel)]) n.remove()
  const take = makeBudget(stats)
  const stack = [...doc.body.childNodes].reverse()
  while (stack.length) {
    if (!take()) break
    const n = stack.pop()
    if (n.nodeType === TEXT) {
      if (n.nodeValue.trim()) stats.skipped_lines += 1
      continue
    }
    if (n.nodeType !== ELEMENT) continue
    const tag = n.tagName
    if (HEADINGS.has(tag)) { b.section(collapse(n.textContent)); continue }
    if (tag === 'P' || tag === 'DIV') {
      const h = boldOnlyText(n)
      if (h != null) { b.section(h); continue }
      if (tag === 'P') {
        if (collapse(n.textContent)) stats.skipped_lines += 1
        continue
      }
    }
    if (LISTS.has(tag)) {
      for (const child of n.children) {
        if (!take()) break
        if (child.tagName === 'LI') processTaskLi(child, b, stats, take)
        else if (LISTS.has(child.tagName)) stack.push(child)
      }
      continue
    }
    if (tag === 'LI') { processTaskLi(n, b, stats, take); continue }
    if (SKIP_COUNTED.has(tag)) {
      if (tag === 'IMG' || collapse(n.textContent)) stats.skipped_lines += 1
      continue
    }
    if (tag === 'BR' || tag === 'HR' || tag === 'INPUT') continue
    const kids = n.childNodes
    for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i])
  }
  return true
}

// ── Sonuç ───────────────────────────────────────────────────────────────────────────────────────────────────────────

function finalize(raw, stats, idOf) {
  const out = []
  let total = 0
  let active = 0
  for (const s of raw) {
    if (!s.tasks.length) {
      if (s.fromHeading) stats.skipped_lines += 1
      continue
    }
    if (out.length >= WIKI_CAPS.sections) {
      stats.capped.sections += 1
      stats.capped.tasks += s.tasks.length
      continue
    }
    const title = cutTitle(s.title, WIKI_CAPS.sectionTitle, () => { stats.truncated_titles += 1 }) || '…'
    const tasks = []
    for (const r of s.tasks) {
      if (total >= WIKI_CAPS.tasks || tasks.length >= WIKI_CAPS.sectionTasks) { stats.capped.tasks += 1; continue }
      const seen = new Set()
      const links = []
      for (const l of r.links) {
        if (seen.has(l.url)) continue
        seen.add(l.url)
        if (links.length >= WIKI_CAPS.links) { stats.capped.links += 1; continue }
        links.push({ label: (l.label || hostPath(l.url)).slice(0, DEFAULT_LIMITS.label), url: l.url })
      }
      let description = ''
      for (const line of r.desc) {
        const next = description ? `${description}\n• ${line}` : `• ${line}`
        if (next.length > WIKI_CAPS.description) { stats.capped.description += 1; continue }
        description = next
      }
      const freq = inferFrequency(s.title, r.title)
      const isActive = active < WIKI_CAPS.active
      if (isActive) active += 1
      else stats.capped.active += 1
      tasks.push({
        id: idOf('t'),
        title: cutTitle(r.title, WIKI_CAPS.title, () => { stats.truncated_titles += 1 }),
        description,
        links,
        frequency: freq.frequency,
        weekday: freq.weekday,
        monthly_rule: freq.monthly_rule,
        month_day: null,
        nth: null,
        required: true,
        active: isActive,
        signals: [],
        window_from: null,
        window_to: null,
      })
      total += 1
      stats.links += links.length
    }
    if (!tasks.length) continue
    out.push({ id: idOf('s'), title, window_from: null, window_to: null, tasks })
  }
  stats.sections = out.length
  stats.tasks = total
  return out
}

/**
 * Wiki / markdown / HTML metnini şablon bölümlerine çevirir. `html` doluysa o, yoksa `text` okunur.
 * @param {{html?: string, text?: string}} input
 * @param {{lang?: 'tr'|'en', taken?: Set<string>}} [opts] `taken`: kullanılmaması gereken kimlikler (taslak + asıl)
 * @returns {{sections: object[], stats: object, error: null|'TOO_LARGE'}}
 */
export function parseWikiImport({ html = '', text = '' } = {}, { lang = 'tr', taken = new Set() } = {}) {
  const stats = emptyStats()
  const h = typeof html === 'string' ? html : ''
  const tx = typeof text === 'string' ? text : ''
  const useHtml = h.trim() !== '' && typeof DOMParser !== 'undefined'
  const source = useHtml ? h : tx
  if (source.length > WIKI_MAX_INPUT) return { sections: [], stats, error: 'TOO_LARGE' }
  if (!source.trim()) return { sections: [], stats, error: null }
  const b = createBuilder(lang === 'en' ? 'en' : 'tr', stats)
  const ok = useHtml ? parseHtml(source, b, stats) : false
  if (!ok) parseText(tx || source, b, stats)
  const sections = finalize(b.sections, stats, makeIdFactory(new Set(taken)))
  return { sections, stats, error: null }
}
