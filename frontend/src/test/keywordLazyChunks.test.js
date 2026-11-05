import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * KAPI (kw paket D3 / F1, 2026-11-05): anahtar kelime ayrıntısının ağır parçaları İHTİYAÇ ANINDA yüklenir.
 *   • Eşleşme sayısı / boyut grafiği (`KeywordContentChart`, grafik kitaplığı) yalnız "İçerik" sekmesinden `import()` ile.
 *   • "Sentetik izlemeye dönüştür" penceresi (`ConvertToSyntheticDialog`) ve k6 üreteci hiçbir yerden STATİK yüklenmez
 *     — keyword sayfası pencereyi `lazy(() => import(...))` ile açar (Aşama 2 bağlaması bu kapıyı geçmeli).
 * Kaynak düzeyinde denetlenir (jsdom paket üretmez) — `lazyChunks.test.js`'in yöntemi.
 */
const SRC = path.resolve(__dirname, '..')
const rel = (f) => path.relative(SRC, f).replace(/\\/g, '/')
function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'assets') walk(path.join(dir, e.name), acc) } else if (/\.(jsx?|mjs)$/.test(e.name)) acc.push(path.join(dir, e.name))
  }
  return acc
}
const FILES = walk(SRC).map((f) => ({ rel: rel(f), src: fs.readFileSync(f, 'utf8') }))
const read = (r) => FILES.find((x) => x.rel === r)?.src ?? ''
const staticImports = (src) => [...src.matchAll(/^\s*import\s+(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]/gm)].map((m) => m[1])
const dynamicImports = (src) => [...src.matchAll(/import\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1])
const staticUsers = (re) => FILES.filter(({ src }) => staticImports(src).some((s) => re.test(s))).map((x) => x.rel).sort()

describe('keyword tembel parça kapısı', () => {
  it('içerik grafiği yalnız İçerik sekmesinden import() ile', () => {
    expect(staticUsers(/KeywordContentChart(\.jsx)?$/)).toEqual([])
    expect(dynamicImports(read('components/keyword/detail/KeywordContentTab.jsx')).some((s) => /KeywordContentChart\.jsx$/.test(s))).toBe(true)
  })

  it('dönüştürme penceresi hiçbir yerden statik yüklenmez; k6 üreteci yalnız pencereden', () => {
    expect(staticUsers(/ConvertToSyntheticDialog(\.jsx)?$/)).toEqual([])
    expect(staticUsers(/k6\/keywordToK6(\.js)?$|\.\/keywordToK6(\.js)?$/)).toEqual(['components/keyword/k6/ConvertToSyntheticDialog.jsx'])
  })

  it('İçerik sekmesi grafik kitaplığını statik çekmez (recharts / shadcn chart)', () => {
    const tab = read('components/keyword/detail/KeywordContentTab.jsx')
    expect(staticImports(tab).some((s) => /recharts|components\/shadcn\/chart/.test(s))).toBe(false)
  })
})
