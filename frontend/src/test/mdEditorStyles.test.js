import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * BEKÇİ (2026-10-02, performans önerisi 22): Markdown editörünün CSS'i açılış CSS'inde, ESKİ yerinde kalmalı.
 *
 * Editörün JS'i lazy chunk'a taşındı; CSS'i de o chunk'la sonradan (globals.css + App.css'ten SONRA) gelseydi kaskad
 * sırası tersine döner, App.css'in editör ezmeleri kütüphaneye yenilebilirdi. `styles/mdEditorStyles.js` kütüphanenin
 * KENDİ içe aktardığı CSS dosyalarını açılış grafiğine alır. Bir @uiw sürüm yükseltmesi YENİ bir CSS dosyası eklerse o
 * dosya yine lazy chunk'a düşer ve sıra sessizce bozulur — bu test onu yakalar: kütüphanedeki her `import "./….css"`
 * hedefi listede olmalı (fazlası da olmamalı), dosyalar gerçekten var olmalı ve main.jsx listeyi globals.css'ten ÖNCE
 * içe aktarmalı. (Bayt düzeyindeki eşitlik bölme sırasında build çıktısıyla doğrulandı; bu test yapıyı kilitler.)
 */
const SRC = path.resolve(__dirname, '..')
const FRONT = path.resolve(SRC, '..')
const PKGS = ['@uiw/react-md-editor', '@uiw/react-markdown-preview']

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.name.endsWith('.js')) out.push(p)
  }
  return out
}

/** Kütüphanenin esm ağacındaki JS dosyalarının içe aktardığı CSS dosyaları (mutlak, normalize). */
function libraryCss() {
  const found = new Set()
  for (const pkg of PKGS) {
    const esm = path.join(FRONT, 'node_modules', pkg, 'esm')
    for (const file of walk(esm)) {
      const src = fs.readFileSync(file, 'utf8')
      for (const m of src.matchAll(/import\s+["']([^"']+\.css)["']/g)) {
        found.add(path.resolve(path.dirname(file), m[1]))
      }
    }
  }
  return found
}

const STYLES = path.join(SRC, 'styles', 'mdEditorStyles.js')

function listedCss() {
  const src = fs.readFileSync(STYLES, 'utf8')
  return [...src.matchAll(/^import\s+'([^']+\.css)'/gm)].map((m) => path.resolve(path.dirname(STYLES), m[1]))
}

describe('Markdown editörü CSS sırası (öneri 22)', () => {
  it('kütüphanenin içe aktardığı HER CSS dosyası mdEditorStyles.js\'te — eksik ya da fazla yok', () => {
    const lib = libraryCss()
    expect(lib.size, 'kütüphane taranamadı (yol/sürüm değişti mi?)').toBeGreaterThanOrEqual(5)
    const listed = listedCss()
    expect(new Set(listed).size).toBe(listed.length)
    expect([...lib].filter((f) => !listed.includes(f)).map((f) => path.relative(FRONT, f)), 'listede eksik').toEqual([])
    expect(listed.filter((f) => !lib.has(f)).map((f) => path.relative(FRONT, f)), 'listede fazla').toEqual([])
    for (const f of listed) expect(fs.existsSync(f), f).toBe(true)
  })

  it('main.jsx editör CSS\'ini globals.css ve App.css\'ten ÖNCE içe aktarır', () => {
    const main = fs.readFileSync(path.join(SRC, 'main.jsx'), 'utf8')
    const at = (s) => main.indexOf(s)
    expect(at("import './styles/mdEditorStyles.js'")).toBeGreaterThan(-1)
    expect(at("import './styles/mdEditorStyles.js'")).toBeLessThan(at("import './styles/globals.css'"))
    expect(at("import './styles/globals.css'")).toBeLessThan(at("import './App.css'"))
  })
})
