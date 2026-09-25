import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * BEKÇİ: yükleme/ilerleme göstergesi elle yazılmaz, components/ui/Progress.jsx'ten gelir.
 *
 * Gerekçe: standarttan önce projede 4 ayrı spinner CSS kopyası, ~53 elle yazılmış spinner ve
 * 80 düz metin "yükleniyor" bloğu vardı; hiçbiri role/aria taşımıyordu ve hiçbiri
 * prefers-reduced-motion kapsamında değildi. Yeni bir ham gösterge eklenirse bu test kırılır
 * ve erişilebilirlik sözleşmesinin dışına çıkılmasını engeller.
 */
const SRC = path.resolve(__dirname, '..')
const UI_PROGRESS = path.join('components', 'ui', 'Progress.jsx')

/** Bilinçli istisnalar — gerekçesi kodda yazılı. */
const EXEMPT = new Set([
  path.join('pages', 'Login.jsx'),            // .lp-spinner: login sayfası kendi CSS-only halkasını kullanır
  path.join('components', 'PageMonitorPage.jsx'), // .page-confirm-spin: onay akışında ikon dönüşü
])

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (entry.name !== 'test') walk(path.join(dir, entry.name), acc)
    } else if (/\.jsx?$/.test(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

const files = walk(SRC).filter(f => !f.endsWith(UI_PROGRESS))
const rel = (f) => path.relative(SRC, f)

/** Yorumları ayıklar — bir sınıfı ANLATAN yorum, o sınıfın kullanımı sayılmamalı. */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

/** `@media (prefers-reduced-motion: reduce) { … }` bloklarının GÖVDELERİ (iç içe süslü parantez sayılarak). */
function reducedMotionBlocks(css) {
  const out = []
  const head = '@media (prefers-reduced-motion: reduce)'
  for (let at = css.indexOf(head); at !== -1; at = css.indexOf(head, at + head.length)) {
    const open = css.indexOf('{', at)
    let depth = 0
    for (let i = open; i < css.length; i++) {
      if (css[i] === '{') depth++
      else if (css[i] === '}' && --depth === 0) { out.push(css.slice(open + 1, i)); break }
    }
  }
  return out
}

describe('progress göstergesi bekçileri', () => {
  it('kaynak ağacı taranabiliyor', () => {
    expect(files.length).toBeGreaterThan(50)
  })

  it('ham <div className="...loading"> bloğu kalmadı (LoadingBlock kullanılmalı)', () => {
    const hits = []
    for (const f of files) {
      fs.readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
        if (/<div className="[a-z0-9-]*loading">/.test(line)) hits.push(`${rel(f)}:${i + 1}`)
      })
    }
    expect(hits, 'Bölüm yükleme göstergesi için <LoadingBlock> kullanın').toEqual([])
  })

  it('elle yazılmış yüzde çubuğu kalmadı (ProgressBar/native <progress> kullanılmalı)', () => {
    // style={{ width: `${...}%` }} deseni — doluluk çubuğunun elle çizildiğinin işareti.
    const hits = []
    for (const f of files) {
      if (EXEMPT.has(rel(f))) continue
      fs.readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
        if (/style=\{\{\s*width:\s*`\$\{[^`]*\}%`/.test(line)) hits.push(`${rel(f)}:${i + 1} → ${line.trim()}`)
      })
    }
    expect(hits, 'Doluluk çubuğu için <ProgressBar> kullanın (role + aria-valuenow bedava gelir)').toEqual([])
  })

  it('Loader2 artık spinner olarak kullanılmıyor', () => {
    const hits = []
    for (const f of files) {
      if (EXEMPT.has(rel(f))) continue
      fs.readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
        if (/<Loader2\b[^>]*spin/.test(line)) hits.push(`${rel(f)}:${i + 1}`)
      })
    }
    expect(hits, 'Belirsiz gösterge için <Spinner> kullanın').toEqual([])
  })

  it('Progress ailesi prefers-reduced-motion kapsamında (shadcn Spinner/Progress + halka)', () => {
    // Progress ailesi artık shadcn: dönme/geçiş App.css'teki .pg-* kurallarında değil, bileşen
    // sınıflarında (Tailwind `animate-spin`, `transition-*`). Kapsam da ORADA olmalı: dönen ya da
    // geçiş yapan her sınıf dizesi aynı dizede bir `motion-reduce:` karşılığı taşır.
    const sources = [
      path.join('components', 'ui', 'Progress.jsx'),
      path.join('components', 'shadcn', 'spinner.jsx'),
      path.join('components', 'shadcn', 'progress.jsx'),
    ].map((rel) => [rel, stripComments(fs.readFileSync(path.join(SRC, rel), 'utf8'))])
    let spinning = 0
    for (const [rel, src] of sources) {
      const strings = [...src.matchAll(/(["'`])((?:(?!\1)[^\n])*)\1/g)].map((m) => m[2])
      for (const s of strings.filter((x) => /\banimate-spin\b/.test(x))) {
        spinning++
        expect(s, `${rel}: dönen öğe reduced-motion kapsamında değil`).toMatch(/motion-reduce:animate-(none|pulse)\b/)
      }
      for (const s of strings.filter((x) => /(^|\s)transition(-[\w[\]-]+)?(\s|$)/.test(x))) {
        expect(s, `${rel}: geçiş reduced-motion kapsamında değil`).toContain('motion-reduce:transition-none')
      }
    }
    expect(spinning, 'animate-spin bulunamadı (tarama vakumda)').toBeGreaterThan(1)
  })

  it('CSS tabanlı kalan dönen göstergeler de prefers-reduced-motion kapsamında', () => {
    // Progress ailesinin DIŞINDA kalan, App.css'te dönen sınıflar (ikon döndürme, login halkası…).
    const css = fs.readFileSync(path.join(SRC, 'App.css'), 'utf8')
    const blocks = reducedMotionBlocks(css)
    expect(blocks.length, 'reduced-motion bloğu bulunamadı (tarama vakumda)').toBeGreaterThan(0)
    for (const sel of ['.spin', '.chk-spin', '.lp-spinner', '.page-confirm-spin']) {
      const covered = blocks.some((b) => new RegExp(`\\${sel}(?![\\w-])`).test(b))
      expect(covered, `${sel} hareket azaltma kapsamında değil`).toBe(true)
    }
  })
})
