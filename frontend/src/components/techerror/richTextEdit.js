/*
 * Zengin metin düzenleme işlemleri (2026-10-14) — saf işlevler: metin + seçim → yeni metin + yeni seçim. Araç
 * çubuğunun "Kalın", "Madde", "Alt madde", "Girintiyi azalt" düğmeleri ve Ctrl+B bunları çağırır. İşaretleme
 * sunucudaki TechErrorRichText ile aynıdır (`**kalın**`, `- madde`, her 2 boşluk bir alt düzey, en çok 3 düzey).
 */

import { RICH_MAX_LEVEL, isRichSpace, richBlank, richInline } from './techErrorModel.js'

// İşaretten sonra boşluk YA DA sekme: Word / Outlook'tan yapıştırılan "•\tmadde" taslakta kayda kadar sekmeli kalır,
// ayrıştırıcı da onu madde okur (richTabs) — araç çubuğu da tanımalı (inceleme 2026-10-14)
const BULLET_RE = /^([ \t]*)(?:[-*•][ \t])/

/** Seçimin kapsadığı satırların [başlangıç, bitiş) aralığı (satır başından satır sonuna). */
function lineRange(value, start, end) {
  const from = value.lastIndexOf('\n', Math.max(0, start - 1)) + 1
  const stop = end > start && value[end - 1] === '\n' ? end - 1 : end
  let to = value.indexOf('\n', stop)
  if (to < 0) to = value.length
  return [start === 0 ? 0 : from, to]
}

function mapLines(value, start, end, fn) {
  const [from, to] = lineRange(value, start, end)
  const lines = value.slice(from, to).split('\n')
  const next = fn(lines)
  const block = next.join('\n')
  const out = value.slice(0, from) + block + value.slice(to)
  return { value: out, start: from, end: from + block.length }
}

const MARKER_RE = /^([-*•][ \t]|\d{1,3}[.)][ \t])/
const MARKER_ONLY_RE = /^([-*•]|\d{1,3}[.)])$/
const isWrapped = (r) => r.length >= 5 && r.startsWith('**') && r.endsWith('**')
/** İçindeki kalın çiftleri açar: seçim zaten kalın parça içeriyorsa işaret iç içe geçmesin ("**Kart **hata** arttı**"). */
const unbold = (t) => {
  const runs = richInline(t)
  return runs.some((r) => r.bold) ? runs.map((r) => r.text).join('') : t
}

/**
 * Kalın: seçili metni `**` ile sarar; seçim yoksa `****` ekler ve imleci araya koyar. Zaten sarılıysa kaldırır.
 * İşaret kuralı (TechErrorRichText): açılıştan sonra / kapanıştan önce boşluk OLMAZ — seçimin başındaki ve sonundaki
 * boşluk işaretlerin dışında kalır (çift tıklama kelimenin ardındaki boşluğu da seçer). Madde / numara işareti hep
 * dışarıda kalır ("- **metin**"; içine alınsa satır madde olmaktan çıkardı). İşaret satır aşmaz: birden çok satırlı
 * seçimde her dolu satır AYRI sarılır ve ikinci basış hepsini geri açar (inceleme 2026-10-14: ikinci basış "foo**\n**bar"
 * bırakıyordu).
 */
export function toggleBold(value, start, end) {
  const v = String(value ?? '')
  let s = Math.max(0, Math.min(start ?? v.length, v.length))
  let e = Math.max(s, Math.min(end ?? s, v.length))
  // Kenardaki boşluk seçimden çıkar (yalnız boşluk seçildiyse imleç seçim sonunda sayılır)
  while (s < e && isRichSpace(v[s])) s++
  while (e > s && isRichSpace(v[e - 1])) e--
  if (s === e) {
    const at = Math.max(0, Math.min(end ?? s, v.length))
    return { value: `${v.slice(0, at)}****${v.slice(at)}`, start: at + 2, end: at + 2 }
  }
  if (v.slice(s, e).includes('\n')) return toggleBoldLines(v, s, e)
  // Tek satır: seçim satırın içeriğiyle başlıyorsa (önünde yalnız boşluk) madde / numara işareti dışarıda kalır
  const lineStart = v.lastIndexOf('\n', s - 1) + 1
  if (richBlank(v.slice(lineStart, s))) {
    const m = MARKER_RE.exec(v.slice(s, e))
    if (m && s + m[0].length < e) {
      s += m[0].length
      while (s < e && isRichSpace(v[s])) s++
    }
  }
  const sel = v.slice(s, e)
  if (sel.length >= 4 && sel.startsWith('**') && sel.endsWith('**')) {
    const inner = sel.slice(2, -2)
    return { value: v.slice(0, s) + inner + v.slice(e), start: s, end: s + inner.length }
  }
  if (v.slice(s - 2, s) === '**' && v.slice(e, e + 2) === '**') {
    return { value: v.slice(0, s - 2) + sel + v.slice(e + 2), start: s - 2, end: e - 2 }
  }
  const inner = unbold(sel)
  return { value: `${v.slice(0, s)}**${inner}**${v.slice(e)}`, start: s + 2, end: s + 2 + inner.length }
}

/** Çok satırlı seçim: dolu satırların hepsi zaten kalınsa hepsi açılır, değilse kalın olmayanlar sarılır. */
function toggleBoldLines(v, s, e) {
  const parts = v.slice(s, e).split('\n').map((line) => {
    const a = line.length - line.trimStart().length
    const body = line.trim()
    const m = MARKER_RE.exec(body)
    const lead = m ? m[0] : ''
    return { line, a, body, lead, rest: body.slice(lead.length) }
  })
  const content = parts.filter((p) => p.rest && !MARKER_ONLY_RE.test(p.body))
  const allWrapped = content.length > 0 && content.every((p) => isWrapped(p.rest))
  const out = parts.map((p) => {
    // Yalnız işaretten ibaret satır ("- " madde kabuğu) kalın yapılmaz: "**-**" madde olmaktan çıkardı
    if (!p.rest || MARKER_ONLY_RE.test(p.body)) return p.line
    const rest = allWrapped ? p.rest.slice(2, -2) : isWrapped(p.rest) ? p.rest : `**${unbold(p.rest)}**`
    return `${p.line.slice(0, p.a)}${p.lead}${rest}${p.line.slice(p.a + p.body.length)}`
  }).join('\n')
  return { value: v.slice(0, s) + out + v.slice(e), start: s, end: s + out.length }
}

/** Madde: seçili satırların hepsi madde ise işareti kaldırır; değilse madde olmayanlara `- ` ekler (boş satır atlanır). */
export function toggleBullets(value, start, end) {
  const v = String(value ?? '')
  return mapLines(v, start ?? 0, end ?? start ?? 0, (lines) => {
    const content = lines.filter((l) => l.trim() !== '')
    const all = content.length > 0 && content.every((l) => BULLET_RE.test(l))
    if (content.length === 0) return lines.map(() => '- ')
    return lines.map((l) => {
      if (l.trim() === '') return l
      if (all) return l.replace(BULLET_RE, '$1')
      if (BULLET_RE.test(l)) return l
      const m = /^(\s*)(.*)$/.exec(l)
      return `${m[1]}- ${m[2]}`
    })
  })
}

/**
 * Girinti: `delta` +1 → satır başına 2 boşluk (alt madde; en çok {@link RICH_MAX_LEVEL} düzey), -1 → 2 boşluk
 * eksiltir. Madde olmayan satır girintilenirken madde yapılır (alt madde düğmesi tek başına işe yarasın). Satır
 * başındaki sekme 2 boşluk sayılır ve boşluğa çevrilir (ayrıştırıcı ve kayıt da öyle okur).
 */
export function indentLines(value, start, end, delta) {
  const v = String(value ?? '')
  return mapLines(v, start ?? 0, end ?? start ?? 0, (lines) => lines.map((l) => {
    if (l.trim() === '') return l
    const ws = /^[ \t]*/.exec(l)[0]
    let width = 0
    for (const ch of ws) width += ch === '\t' ? 2 : 1
    const norm = ' '.repeat(width) + l.slice(ws.length)
    if (delta < 0) return norm.slice(Math.min(2, width))
    if (Math.floor(width / 2) >= RICH_MAX_LEVEL) return l
    const body = BULLET_RE.test(norm) ? norm : norm.replace(/^( *)/, '$1- ')
    return `  ${body}`
  }))
}
