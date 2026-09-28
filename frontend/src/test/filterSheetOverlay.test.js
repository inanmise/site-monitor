import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * KAPI — yükseltilmiş Sheet'in örtüsü de yükselir (Ek 3/8, 2026-09-28).
 *
 * <p>Süzgeç çekmeceleri içeriği `z-[1001]`e çıkarır (yüzen düğmelerin üstünde kalsın). shadcn `SheetContent`'in örtüsü ise
 * varsayılan `z-50`: aradaki 50–1000 katmanındaki öğeler (tur çipi z-890, sabit eylem çubukları) karartılmadan örtünün
 * ÜSTÜNDE ve tıklanabilir kalıyordu — kip dışı etkileşim. Kural: içeriği `z-[N]` (N > 50) ile yükseltilen her MODAL
 * `SheetContent` `overlayClassName` da verir. `modal={false}` Sheet'ler Radix örtüsü çizmez (kendi örtülerini kurarlar) —
 * kapsam dışı.
 */
const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'assets') continue
      walk(p, out)
    } else if (/\.jsx$/.test(entry.name)) {
      out.push(p)
    }
  }
  return out
}

/** `<Tag …>` açılış etiketinin metni — `{…}` ve tırnak içindeki `>` (ok fonksiyonları) sayılmaz. */
function openingTag(src, start) {
  let depth = 0
  let quote = null
  for (let i = start; i < src.length; i++) {
    const ch = src[i]
    if (quote) { if (ch === quote && src[i - 1] !== '\\') quote = null; continue }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; continue }
    if (ch === '{') depth++
    else if (ch === '}') depth--
    else if (ch === '>' && depth === 0) return src.slice(start, i + 1)
  }
  return src.slice(start)
}

function raisedSheets(src) {
  const out = []
  const re = /<SheetContent\b/g
  let m
  while ((m = re.exec(src))) {
    const tag = openingTag(src, m.index)
    const z = [...tag.matchAll(/\bz-\[(\d+)\]/g)].map((x) => Number(x[1]))
    if (!z.some((n) => n > 50)) continue
    // En yakın önceki <Sheet …> açılışı (SheetContent'in sahibi)
    const before = src.slice(0, m.index)
    const opens = [...before.matchAll(/<Sheet(?![A-Za-z])/g)]
    const sheetAt = opens.length ? opens[opens.length - 1].index : -1
    const sheetTag = sheetAt >= 0 ? openingTag(src, sheetAt) : ''
    out.push({ line: before.split('\n').length, tag, modal: !/modal=\{false\}/.test(sheetTag) })
  }
  return out
}

describe('Yükseltilmiş Sheet örtüsü', () => {
  it('içeriği z-[N>50] olan her modal SheetContent overlayClassName verir', () => {
    const bad = []
    let modalRaised = 0
    for (const file of walk(SRC)) {
      const src = fs.readFileSync(file, 'utf8')
      for (const s of raisedSheets(src)) {
        if (!s.modal) continue
        modalRaised++
        if (!/\boverlayClassName=/.test(s.tag)) bad.push(`${path.relative(SRC, file)}:${s.line}`)
      }
    }
    expect(modalRaised).toBeGreaterThanOrEqual(5)   // tarayıcı gerçekten buluyor (boş tarama sahte yeşil olmasın)
    expect(bad).toEqual([])
  })
})
