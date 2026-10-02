import { describe, it, expect } from 'vitest'
import { fmtFileSize } from '../components/weekly/WeeklyMdField.jsx'
import * as bytes from '../utils/formatBytes.js'

/**
 * KARAKTERİZASYON (2026-10-02, öneri 29): Markdown düzenleyicilerdeki (haftalık rapor alanı + ortak MarkdownEditor)
 * birebir aynı `fmtFileSize` kopyaları utils/formatBytes.js'e taşınırken çıktı pinlenir. KB'de en az 1, MB'de 1 ondalık;
 * B birimi YOK (`formatBytes`'tan bilinçli farklı — görsel yükleme bildirimi). Kâhin = taşıma öncesi gövde.
 */
function oracle(bytes) {
  if (bytes == null) return '—'
  return bytes >= 1024 * 1024
    ? (bytes / 1024 / 1024).toFixed(1) + ' MB'
    : Math.max(1, Math.round(bytes / 1024)) + ' KB'
}
const INPUTS = [null, undefined, 0, 1, 511, 512, 1023, 1024, 1535, 1536, 10_000, 1024 * 1024 - 1, 1024 * 1024, 1_500_000, 46.4 * 1024 * 1024, -5]

describe('fmtFileSize — dosya boyutu metni', () => {
  it('haftalık rapor alanının dışa aktardığı işlev kâhinle birebir', () => {
    for (const v of INPUTS) expect(fmtFileSize(v), String(v)).toBe(oracle(v))
    expect(fmtFileSize(0)).toBe('1 KB')
    expect(fmtFileSize(1024 * 1024)).toBe('1.0 MB')
  })

  it('ortak kaynak utils/formatBytes.formatFileSize aynı işlev (iki düzenleyici de onu kullanır)', () => {
    expect(typeof bytes.formatFileSize).toBe('function')
    for (const v of INPUTS) expect(bytes.formatFileSize(v), String(v)).toBe(oracle(v))
    expect(fmtFileSize).toBe(bytes.formatFileSize)
  })
})
