import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Rename bekçisi (CertMonitor → SiteMonitor): frontend üretim ağacına eski marka
 * kimliklerinin ve eski depolama öneklerinin geri sızmasını engeller.
 * scripts/check-brand.(sh|ps1)'in test kapısına bağlanmış halidir.
 *
 * Bilinçli istisnalar (sınıf A):
 *  - src/test/            → göç/legacy senaryolarını test eden fixture'lar meşru.
 *  - migrateStorageKeys.js → cm.→sm. tek seferlik göç mekanizmasının kendisi.
 * NOT: assets/whitepaper.md (User Guide) /kilavuz-denetim ile temizlendi ve ARTIK taranır;
 * TR "cert monitör" yazımı da yasak desenlere dahildir.
 */
const SRC = path.resolve(__dirname, '..')
const SKIP_DIRS = new Set(['test'])
const SKIP_FILES = new Set(['migrateStorageKeys.js'])
const BANNED_BRAND = ['certmonitor', 'cert-monitor', 'cert_monitor', 'cert.monitor', 'cert monitör', 'cert monitor']
const BANNED_STORAGE = ["'cm.", '"cm.', '`cm.']

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), acc)
    } else if (/\.(jsx?|css|md|html|json)$/.test(entry.name) && !SKIP_FILES.has(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

describe('naming consistency (rename bekçisi)', () => {
  const files = walk(SRC)

  it('kaynak ağacı taranabiliyor (boş liste = yanlış kök)', () => {
    expect(files.length).toBeGreaterThan(50)
  })

  it('üretim kodunda eski marka kimliği yok (certmonitor / cert-monitor / cert_monitor / cert.monitor)', () => {
    const violations = []
    for (const f of files) {
      const lines = fs.readFileSync(f, 'utf8').split('\n')
      lines.forEach((line, i) => {
        const lower = line.toLowerCase()
        if (BANNED_BRAND.some((b) => lower.includes(b))) {
          violations.push(`${path.relative(SRC, f)}:${i + 1} → ${line.trim()}`)
        }
      })
    }
    expect(violations, violations.join('\n')).toEqual([])
  })

  it('User Guide dosyaları bitişik "SiteMonitor" yazımı içermez — kılavuzda marka "Site Monitor" (BRAND.md §5.1)', () => {
    // Kapsam bilinçli olarak yalnız kılavuz: UI kod/i18n\'de "SiteMonitor" yazımı ayrı bir üründür kararıdır.
    // Dil dosyaları readdir ile bulunur; ileride eklenecek bir whitepaper.<dil>.md indiği gün kapsama girer.
    const assets = path.join(SRC, 'assets')
    const guides = fs.readdirSync(assets).filter((f) => /^whitepaper(\.[a-z]{2})?\.md$/.test(f))
    expect(guides.length, 'kılavuz dosyası bulunamadı — yanlış kök?').toBeGreaterThan(0)
    const hits = guides.flatMap((f) =>
      fs.readFileSync(path.join(assets, f), 'utf8').split('\n')
        .map((l, i) => (l.includes('SiteMonitor') ? `${f}:${i + 1} → ${l.trim()}` : null))
        .filter(Boolean)
    )
    expect(hits, hits.join('\n')).toEqual([])
  })

  it("üretim kodunda eski depolama öneki yok ('cm.' — göç sonrası 'sm.' kullanılır)", () => {
    const violations = []
    for (const f of files) {
      const lines = fs.readFileSync(f, 'utf8').split('\n')
      lines.forEach((line, i) => {
        if (BANNED_STORAGE.some((b) => line.includes(b))) {
          violations.push(`${path.relative(SRC, f)}:${i + 1} → ${line.trim()}`)
        }
      })
    }
    expect(violations, violations.join('\n')).toEqual([])
  })
})
