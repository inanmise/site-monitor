import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import LoginHeatmap from '../components/admin/LoginHeatmap.jsx'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'

/**
 * Giriş ısı haritası hücre ipucu (20.166 bugfix, UIA-11): "başarısız" ve "mesai dışı" kodda Türkçe sabitti — İngilizce
 * arayüzde "Mon 09:00–09:59 — 12 (3 başarısız) · mesai dışı" yazıyordu. Metin artık arayüz dilinden.
 */
const zero = () => Array.from({ length: 7 }, () => Array(24).fill(0))

describe('LoginHeatmap — ipucu dili', () => {
  it('başarısız sayısı ve mesai dışı arayüz dilinde; Türkçe sabit sızmaz', () => {
    const matrix = zero(); matrix[0][9] = 12
    const failed = zero(); failed[0][9] = 3
    const { container } = render(<LoginHeatmap matrix={matrix} failed={failed} max={12} dayLabels={['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']}
      isOff={(r, c) => r === 0 && c === 9} />)
    const titles = [...container.querySelectorAll('title')].map((n) => n.textContent)
    const cell = titles.find((x) => x.startsWith('Mon 09:00'))
    const dictWords = [EN, TR].map((d) => d['heatmap.offHours'])
    expect(dictWords.some((w) => cell.includes(w))).toBe(true)
    expect(cell).toMatch(/\(3 (unsuccessful|başarısız)\)/)
    // arayüz İngilizceyse Türkçe kelime YOK
    if (cell.includes(EN['heatmap.offHours'])) {
      expect(cell).not.toContain('başarısız')
      expect(cell).not.toContain('mesai dışı')
    }
  })
})
