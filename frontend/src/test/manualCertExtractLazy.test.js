import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * KAPI (2026-10-08): tarayıcıdaki sertifika ayıklayıcısı ve kütüphaneleri (node-forge, fflate) AÇILIŞ PAKETİNE GİRMEZ.
 * Yalnız yükleme sihirbazının (React.lazy) yolundan, o da yalnız "Analiz et"te `import()` / Web Worker ile yüklenir.
 * Kaynak düzeyinde denetlenir (jsdom paket üretmez): hangi dosya neyi STATİK içe aktarıyor.
 *
 *  - `node-forge` / `fflate` üretim kaynağında YALNIZ `components/manualcert/extract/` altında içe aktarılır;
 *  - `extract/index.js` hiçbir şeyi statik içe aktarmaz — çekirdek `import('./core.js')` ya da Worker ile gelir;
 *  - `extract/` modüllerini yalnız `UploadWizard.jsx` (index) kullanır; sihirbaz her yerde `lazy(() => import(...))`;
 *  - açılış paketindeki `manualCertModel.js` (App.jsx statik alır) `extract/`'ı içe aktarmaz.
 */
const SRC = path.resolve(__dirname, '..')
const EXTRACT = path.join(SRC, 'components', 'manualcert', 'extract')
const rel = (f) => path.relative(SRC, f).replace(/\\/g, '/')

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'test' && e.name !== 'assets') walk(path.join(dir, e.name), acc) } else if (/\.(jsx?|mjs)$/.test(e.name)) acc.push(path.join(dir, e.name))
  }
  return acc
}
const FILES = walk(SRC).map((f) => ({ f, rel: rel(f), src: fs.readFileSync(f, 'utf8') }))
const staticImports = (src) => [...src.matchAll(/^\s*import\s+(?:[^'"]*?\s+from\s+)?['"]([^'"]+)['"]/gm)].map((m) => m[1])
const anyImports = (src) => [...staticImports(src), ...[...src.matchAll(/import\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1])]

describe('manuel sertifika ayıklayıcısı — tembel parça kapısı', () => {
  it('node-forge ve fflate yalnız extract/ altında içe aktarılır', () => {
    const offenders = FILES.filter(({ f, src }) => !f.startsWith(EXTRACT)
      && anyImports(src).some((s) => s === 'node-forge' || s.startsWith('node-forge/') || s === 'fflate'))
      .map((x) => x.rel)
    expect(offenders).toEqual([])
    const users = FILES.filter(({ src }) => anyImports(src).some((s) => s.startsWith('node-forge') || s === 'fflate')).map((x) => x.rel).sort()
    expect(users).toEqual(['components/manualcert/extract/core.js', 'components/manualcert/extract/crypto.js'])
  })

  it('extract/index.js statik içe aktarma YAPMAZ; çekirdek dinamik import ya da Worker ile gelir', () => {
    const index = FILES.find((x) => x.rel === 'components/manualcert/extract/index.js')
    expect(staticImports(index.src)).toEqual([])
    expect(index.src).toMatch(/import\(\s*'\.\/core\.js'\s*\)/)
    expect(index.src).toMatch(/new Worker\(new URL\('\.\/extract\.worker\.js', import\.meta\.url\), \{ type: 'module' \}\)/)
    const worker = FILES.find((x) => x.rel === 'components/manualcert/extract/extract.worker.js')
    expect(staticImports(worker.src)).toEqual(['./core.js'])
  })

  it('extract/ modüllerini dışarıdan yalnız UploadWizard (index.js üzerinden) kullanır; sihirbaz her yerde lazy', () => {
    const outside = FILES.filter(({ f, src }) => !f.startsWith(EXTRACT) && anyImports(src).some((s) => /(^|\/)extract\//.test(s) && /manualcert|^\.\/extract/.test(s)))
    expect(outside.map((x) => x.rel)).toEqual(['components/manualcert/UploadWizard.jsx'])
    expect(anyImports(outside[0].src).filter((s) => /extract\//.test(s))).toEqual(['./extract/index.js'])
    const wizardStatic = FILES.filter(({ src }) => staticImports(src).some((s) => /UploadWizard(\.jsx)?$/.test(s))).map((x) => x.rel)
    expect(wizardStatic).toEqual([])
    const lazyUsers = FILES.filter(({ src }) => /lazy\(\(\) => import\('\.\/UploadWizard\.jsx'\)\)/.test(src)).map((x) => x.rel).sort()
    expect(lazyUsers.length).toBeGreaterThan(0)
  })

  it('açılış paketindeki manualCertModel.js ve App.jsx extract/ ya da kripto kütüphanesi içe aktarmaz', () => {
    for (const r of ['components/manualcert/manualCertModel.js', 'App.jsx', 'main.jsx']) {
      const x = FILES.find((y) => y.rel === r)
      expect(x, r).toBeTruthy()
      expect(anyImports(x.src).filter((s) => /extract\/|node-forge|fflate/.test(s)), r).toEqual([])
    }
  })
})
