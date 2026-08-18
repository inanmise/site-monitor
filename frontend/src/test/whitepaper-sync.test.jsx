import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { MIRRORS, HEADER_FOR } from '../../../scripts/sync-whitepaper.mjs'

/**
 * Çift-kopya senkron kilidi: kök WHITEPAPER.md ve WHITEPAPER.en.md, tek doğruluk kaynağı
 * frontend/src/assets/whitepaper*.md dosyalarından ÜRETİLEN kopyalardır
 * (scripts/sync-whitepaper.mjs). Bu test sapmanın sessizce birikmesini engeller —
 * kırmızıysa script'i çalıştırın, kök kopyaları elle düzenlemeyin.
 *
 * Kopya listesi script'ten import edilir: yeni bir dil eklendiğinde test kendiliğinden kapsar.
 */
const ROOT = path.resolve(__dirname, '..', '..', '..')

describe('whitepaper çift-kopya senkronu', () => {
  it('üretilecek kopya listesi boş değil', () => {
    expect(MIRRORS.length).toBeGreaterThan(0)
  })

  it.each(MIRRORS.map((m) => [m.out, m]))('kök %s = vitrin header + kaynak (birebir)', (out, m) => {
    const asset = fs.readFileSync(path.join(ROOT, m.src), 'utf8')
    const root = fs.readFileSync(path.join(ROOT, m.out), 'utf8')
    expect(root, `${out} üretilen-kopya işaretini taşımıyor`).toContain('ÜRETİLEN KOPYA')
    expect(root.startsWith(HEADER_FOR(m)), `${out} başlığı beklenen kaynağı göstermiyor`).toBe(true)
    const body = root.slice(HEADER_FOR(m).length)
    expect(body === asset, `${out} kaynaktan sapmış — node scripts/sync-whitepaper.mjs çalıştırın`).toBe(true)
  })
})
