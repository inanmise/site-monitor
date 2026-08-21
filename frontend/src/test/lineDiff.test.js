import { describe, it, expect } from 'vitest'
import { lineDiff, collapseContext, envNameDiff, MAX_DIFF_LINES } from '../utils/lineDiff.js'

describe('lineDiff', () => {
  it('değişmeyen metinde add/del ÜRETMEZ', () => {
    const d = lineDiff('a\nb\nc', 'a\nb\nc')
    expect(d.added).toBe(0)
    expect(d.removed).toBe(0)
    expect(d.rows.every(r => r.type === 'ctx')).toBe(true)
  })

  it('tek satır değişimini 1 ekleme + 1 silme olarak gösterir', () => {
    const d = lineDiff('a\nb\nc', 'a\nB\nc')
    expect(d.added).toBe(1)
    expect(d.removed).toBe(1)
    expect(d.rows.find(r => r.type === 'add').text).toBe('B')
    expect(d.rows.find(r => r.type === 'del').text).toBe('b')
  })

  /** Üretim olayının kendisi: tanımsız sabit içeren satır eklendi. */
  it('gerçek senaryo: bozuk satır ekleme olarak görünür ve satır numarası taşır', () => {
    const before = 'const params = {\n  headers: {},\n};'
    const after  = 'const params = {\n  headers: {},\n  timeout: REQUEST_TIMEOUT,\n};'
    const d = lineDiff(before, after)
    expect(d.added).toBe(1)
    expect(d.removed).toBe(0)
    const row = d.rows.find(r => r.type === 'add')
    expect(row.text).toContain('REQUEST_TIMEOUT')
    expect(row.newNo).toBe(3)
  })

  it('boş → dolu ve dolu → boş uçları', () => {
    expect(lineDiff('', 'x').added).toBe(1)
    expect(lineDiff('x', '').removed).toBe(1)
    expect(lineDiff(null, null).rows).toHaveLength(0)
  })

  it('CRLF ve LF aynı biçimde bölünür (satır sonu farkı sahte diff üretmez)', () => {
    const d = lineDiff('a\r\nb', 'a\nb')
    expect(d.added).toBe(0)
    expect(d.removed).toBe(0)
  })

  it('TAVAN: çok büyük girdide DP\'ye girilmez, truncated döner', () => {
    const big = Array.from({ length: MAX_DIFF_LINES + 1 }, (_, i) => `line ${i}`).join('\n')
    const d = lineDiff(big, big + '\nson')
    expect(d.truncated).toBe(true)
    expect(d.rows).toHaveLength(0)
    expect(d.newLines).toBe(MAX_DIFF_LINES + 2)
  })
})

describe('collapseContext', () => {
  it('değişiklikten uzak bağlam satırlarını gap olarak katlar', () => {
    const before = Array.from({ length: 40 }, (_, i) => `l${i}`).join('\n')
    const after = before.replace('l20', 'DEGISTI')
    const rows = collapseContext(lineDiff(before, after).rows, 3)

    expect(rows.some(r => r.type === 'gap')).toBe(true)
    expect(rows.filter(r => r.type === 'add' || r.type === 'del')).toHaveLength(2)
    // Katlanmış görünüm ham satır sayısından belirgin biçimde kısa olmalı.
    expect(rows.length).toBeLessThan(20)
  })

  it('küçük dosyada katlanacak bir şey yoksa gap üretmez', () => {
    const rows = collapseContext(lineDiff('a\nb', 'a\nB').rows, 3)
    expect(rows.some(r => r.type === 'gap')).toBe(false)
  })
})

describe('envNameDiff', () => {
  it('eklenen ve silinen değişken ADLARINI verir (değerlere BAKMAZ — maskeli geliyorlar)', () => {
    const d = envNameDiff(
      [{ name: 'API_KEY', value: '***' }, { name: 'BASE_URL', value: 'a' }],
      [{ name: 'BASE_URL', value: 'b' }, { name: 'TIMEOUT', value: '5' }],
    )
    expect(d.added).toEqual(['TIMEOUT'])
    expect(d.removed).toEqual(['API_KEY'])
  })

  it('null/eksik listelerde patlamaz', () => {
    expect(envNameDiff(null, undefined)).toEqual({ added: [], removed: [] })
  })
})
