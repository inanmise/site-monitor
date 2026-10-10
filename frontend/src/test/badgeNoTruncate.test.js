import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * KAPI (2026-10-10, kullanıcı bildirimi: "takımı yöneten müdürler kısmındaki kayma" — "bu sorunları bir daha yapmayalım"):
 * shadcn `Badge` içeriği ORTALAR ve taşanı gizler (`overflow-hidden`). Uzun bir kişi / takım adı sığmayınca İKİ yandan
 * taşıyor, adın BAŞI ve sonu kırpılıyordu ("…enk Çil (Teknoloji Servis Yönetimi Bölüm"); `truncate`'in üç noktası da
 * inline-flex'te çıkmaz. Kurallar:
 *  1. Rozet tabanı `justify-center-safe` (CSS `safe center`) — taşma yalnız sonda, adın başı daima görünür.
 *  2. Hiçbir `<Badge>` `truncate` taşımaz — ad / uzun metin rozette KESİLMEZ; ya düz metin (sarar) ya da rozet
 *     `whitespace-normal [overflow-wrap:anywhere]` ile sarar.
 */
const SRC = path.resolve(__dirname, '..')

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (e.name === 'test' || e.name === 'assets' || e.name === 'shadcn') continue
      walk(path.join(dir, e.name), acc)
    } else if (/\.jsx?$/.test(e.name)) acc.push(path.join(dir, e.name))
  }
  return acc
}

/** `<Badge …>` açılış etiketlerinin tam metni — çok satırlı ve `{…}` içeren öznitelikler dahil. */
export function badgeTags(src) {
  const out = []
  const re = /<Badge(?=[\s>/])/g
  let m
  while ((m = re.exec(src))) {
    let depth = 0
    let quote = null
    let i = m.index + 6
    for (; i < src.length; i++) {
      const c = src[i]
      if (quote) { if (c === quote && src[i - 1] !== '\\') quote = null; continue }
      if (c === '"' || c === "'" || c === '`') { quote = c; continue }
      if (c === '{') depth++
      else if (c === '}') depth--
      else if (c === '>' && depth === 0) break
    }
    out.push({ index: m.index, text: src.slice(m.index, i + 1) })
  }
  return out
}

describe('rozette ad kesilmez (Badge kapısı)', () => {
  it('Badge tabanı safe center — taşan içeriğin başı kırpılmaz', () => {
    // Yorum satırları sayılmaz (sapmanın gerekçesi `justify-center` adını anar)
    const code = fs.readFileSync(path.join(SRC, 'components/shadcn/badge.jsx'), 'utf8')
      .split('\n').filter((l) => !l.trim().startsWith('//')).join('\n')
    expect(code).toMatch(/justify-center-safe/)
    expect(code).not.toMatch(/\bjustify-center(?!-safe)\b/)
  })

  it('hiçbir <Badge> truncate taşımaz', () => {
    const hits = []
    for (const f of walk(SRC)) {
      const src = fs.readFileSync(f, 'utf8')
      for (const tag of badgeTags(src)) {
        if (/\btruncate\b/.test(tag.text)) {
          const line = src.slice(0, tag.index).split('\n').length
          hits.push(`${path.relative(SRC, f)}:${line}`)
        }
      }
    }
    expect(hits, 'Badge içinde truncate — adı kesmek yerine sarın (whitespace-normal [overflow-wrap:anywhere]) ya da düz metin kullanın').toEqual([])
  })

  it('etiket tarayıcı çok satırlı ve süslü parantezli etiketi bütün okur (kapının kendisi)', () => {
    const src = `<Badge variant="x"\n  className={cn('a', on && 'truncate')}\n  title={\`\${a > b}\`}>ad</Badge> <Badge>b</Badge>`
    const tags = badgeTags(src)
    expect(tags).toHaveLength(2)
    expect(tags[0].text).toContain("'truncate'")
    expect(tags[0].text.endsWith('>')).toBe(true)
    expect(tags[1].text).toBe('<Badge>')
  })
})
