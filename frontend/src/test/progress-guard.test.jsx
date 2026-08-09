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

  it('Progress ailesi prefers-reduced-motion kapsamında', () => {
    const css = fs.readFileSync(path.join(SRC, 'App.css'), 'utf8')
    // Seçici listesi ".pg-spinner," ile başlayan reduced-motion bloğunu bul.
    const at = css.indexOf('.pg-spinner,')
    expect(at, 'Progress reduced-motion seçici listesi bulunamadı').toBeGreaterThan(-1)
    expect(css.slice(Math.max(0, at - 200), at)).toContain('@media (prefers-reduced-motion: reduce)')
    const block = css.slice(at, at + 400)
    for (const sel of ['.pg-spinner', '.spin', '.chk-spin', '.lp-spinner', '.page-confirm-spin']) {
      expect(block, `${sel} hareket azaltma kapsamında değil`).toContain(sel)
    }
  })
})
