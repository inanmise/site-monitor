import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Çift-kopya senkron kilidi: kök WHITEPAPER.md, tek doğruluk kaynağı
 * frontend/src/assets/whitepaper.md'den ÜRETİLEN kopyadır (scripts/sync-whitepaper.mjs).
 * Bu test sapmanın sessizce birikmesini engeller — kırmızıysa script'i çalıştırın,
 * kök kopyayı elle düzenlemeyin.
 */
const ROOT = path.resolve(__dirname, '..', '..', '..')

describe('whitepaper çift-kopya senkronu', () => {
  it('kök WHITEPAPER.md = vitrin header + assets/whitepaper.md (birebir)', () => {
    const asset = fs.readFileSync(path.join(ROOT, 'frontend', 'src', 'assets', 'whitepaper.md'), 'utf8')
    const root = fs.readFileSync(path.join(ROOT, 'WHITEPAPER.md'), 'utf8')
    expect(root).toContain('ÜRETİLEN KOPYA')
    const body = root.slice(root.indexOf('-->') + 4).replace(/^\n/, '')
    expect(body === asset, 'Kopyalar sapmış — node scripts/sync-whitepaper.mjs çalıştırın').toBe(true)
  })
})
