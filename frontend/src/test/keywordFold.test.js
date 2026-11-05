import { describe, it, expect } from 'vitest'
import { MAX_COUNT, countMatches, fold, foldChar, matchParts } from '../utils/keywordFold.js'
import { javaStringTable, readJavaTest } from './helpers/javaTable.js'

/**
 * Türkçe harf katlama (kw sözleşmesi §4.1) — sunucunun `KeywordFold`'u ile AYNI doğruluk tablosu. Satırlar: metin, iğne,
 * harf duyarlı mı, adet, ilk konum. Arka uç birleşince `KeywordFoldTest.TABLE` kaynaktan okunur (iki taraf ayrışamaz);
 * dosya yokken (ön yüz dalı tek başına) sözleşmedeki satırlar satır içi.
 */
const INLINE = [
  ['IŞIK', 'ışık', 'false', '1', '0'],
  ['iletişim', 'İletişim', 'false', '1', '0'],
  ['İstanbul', 'ISTANBUL', 'false', '1', '0'],
  ['ılık ılık', 'ILIK', 'false', '2', '0'],
  ['Hoş geldiniz', 'HOŞ GELDİNİZ', 'false', '1', '0'],
  ['Hoş geldiniz', 'HOŞ GELDİNİZ', 'true', '0', '-1'],
  ['aaaa', 'aa', 'false', '2', '0'],
  ['x İ y', 'i', 'false', '1', '2'],
  ['şeker', 'seker', 'false', '0', '-1'],
  ['ÇAĞRI çağrı', 'çağrı', 'false', '2', '0'],
  ['', 'a', 'false', '0', '-1'],
  ['abc', '', 'false', '0', '-1'],
]

// Arka uç birleşti (Aşama 2): tablo Java kaynağından okunur — dosya / tablo yoksa test KIRMIZI (iki taraf ayrışamaz)
const javaTable = () => javaStringTable(readJavaTest('service/keyword/KeywordFoldTest.java'), 'TABLE')

describe('keywordFold — Java KeywordFold aynası', () => {
  const java = javaTable()
  const table = java

  it.each(table.map((r) => [r.join(' | '), r]))('%s', (_, [hay, needle, cs, count, first]) => {
    expect(countMatches(hay, needle, cs === 'true', MAX_COUNT)).toEqual({ count: Number(count), firstIndex: Number(first) })
  })

  it('Java tablosu sözleşmedeki satırların hepsini içerir (tablo küçülmesin)', () => {
    expect(java.length).toBeGreaterThanOrEqual(INLINE.length)
    const keys = new Set(java.map((r) => r.join('|')))
    for (const r of INLINE) expect(keys.has(r.join('|')), r.join('|')).toBe(true)
  })

  it('fold: İ/ı/I/i → i, diğerleri tek tek küçülür, uzunluk HER ZAMAN korunur', () => {
    expect(fold('İıIi')).toBe('iiii')
    expect(fold('ŞĞÜÖÇ')).toBe('şğüöç')
    for (const s of ['İSTANBUL', 'ÇAĞRI', 'ΣΑΣ', '🙂İ', 'aİb', '']) expect(fold(s)).toHaveLength(s.length)
    expect(fold(null)).toBeNull()
    expect(foldChar('İ')).toBe('i')
    // Yunanca son sigma: Java karakter karakter σ verir — tam metin toLowerCase'in bağlam kuralı uygulanmaz
    expect(fold('ΑΣ')).toBe('ασ')
  })

  it('sayım üst sınırı ve geçersiz girdi', () => {
    expect(countMatches('aaaaaa', 'a', false, 3)).toEqual({ count: 3, firstIndex: 0 })
    expect(countMatches('abc', 'b', false, 0)).toEqual({ count: 0, firstIndex: -1 })
    expect(countMatches(null, 'a')).toEqual({ count: 0, firstIndex: -1 })
  })

  it('matchParts özgün metni dilimler (İstanbul vurgusu yazıldığı gibi), örtüşmesiz, harf kuralına uyar', () => {
    expect(matchParts('Hoş geldiniz İSTANBUL', 'istanbul')).toEqual([
      { text: 'Hoş geldiniz ', match: false }, { text: 'İSTANBUL', match: true },
    ])
    expect(matchParts('aaa', 'aa').filter((p) => p.match).map((p) => p.text)).toEqual(['aa'])
    expect(matchParts('Foo foo', 'foo', true).filter((p) => p.match)).toHaveLength(1)
    expect(matchParts('abc', '')).toEqual([{ text: 'abc', match: false }])
    expect(matchParts('', 'a')).toEqual([])
    expect(matchParts('a'.repeat(50), 'a', false, 5).filter((p) => p.match)).toHaveLength(5)
  })
})
