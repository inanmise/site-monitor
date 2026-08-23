import { describe, it, expect } from 'vitest'
import { formatBytes, formatBytesAxis } from '../utils/formatBytes.js'

/**
 * Boyut biçimi — kart, kaynak tablosu, grafik ekseni ve ipucu HEP buradan geçer.
 * Ayrışırlarsa aynı sayı iki yerde farklı okunur; birleştirmenin tek sebebi buydu.
 */
describe('formatBytes', () => {
  it('bayt eşiklerinde okunur birime geçer', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(1023)).toBe('1023 B')
    expect(formatBytes(1024)).toBe('1 KB')
    expect(formatBytes(1024 * 1024 - 1)).toBe('1024 KB')
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB')
    expect(formatBytes(46.4 * 1024 * 1024)).toBe('46.4 MB')
  })

  it('kırpılmış değer "≥" ile gösterilir — çıplak sayı toplamı olduğundan küçük gösterirdi', () => {
    expect(formatBytes(10 * 1024 * 1024, true)).toBe('≥ 10.0 MB')
    expect(formatBytes(2048, true)).toBe('≥ 2 KB')
    expect(formatBytes(10 * 1024 * 1024, false)).toBe('10.0 MB')
  })

  it('null/anlamsız değerde tire döner (uydurma 0 yazmaz)', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes('abc')).toBe('—')
  })
})

describe('formatBytesAxis', () => {
  it('eksen dar olduğu için ondalık TAŞIMAZ', () => {
    expect(formatBytesAxis(46.4 * 1024 * 1024)).toBe('46 MB')
    expect(formatBytesAxis(1024 * 1024)).toBe('1 MB')
    expect(formatBytesAxis(512 * 1024)).toBe('512 KB')
    expect(formatBytesAxis(900)).toBe('900 B')
  })

  it('anlamsız değerde boş döner — eksende "NaN" görünmesin', () => {
    expect(formatBytesAxis(null)).toBe('')
    expect(formatBytesAxis('abc')).toBe('')
  })
})
