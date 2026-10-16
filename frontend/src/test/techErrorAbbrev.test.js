import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  LIMITS, NUMBER_ABBREVIATIONS, hasAbbreviation, isAmbiguousB, isIncompleteNumber, parseNumber,
} from '../components/techerror/techErrorModel.js'

// Kısaltmalı rakam girişi (2026-10-16, kullanıcı kararı: raporlama aracı rakamları "7,42G · 130,1M · 681,6k" diye
// verir; Qlik + Türkçe kısaltmalar, tek başına "B" YOK — Türkçede bin, İngilizcede milyar demek).
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const VALUES = fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/techerror/TechErrorValues.java'), 'utf8')

describe('kısaltmalı sayı', () => {
  it('Qlik ve Türkçe / İngilizce kısaltmalar tam sayıya çevrilir — ondalık kaydırmayla (kayan nokta artığı yok)', () => {
    const cases = [
      ['7,42G', 'tr', 7_420_000_000], ['130,1M', 'tr', 130_100_000], ['681,6k', 'tr', 681_600], ['16,98k', 'tr', 16_980],
      ['29,1k', 'tr', 29_100], ['129,4M', 'tr', 129_400_000], ['7.42G', 'en', 7_420_000_000], ['681.6K', 'en', 681_600],
      ['2 milyon', 'tr', 2_000_000], ['3 MİLYAR', 'tr', 3_000_000_000], ['3 MILYAR', 'tr', 3_000_000_000],
      ['5 bin', 'tr', 5_000], ['1,5 Mn', 'tr', 1_500_000], ['2,25Mr', 'tr', 2_250_000_000], ['4 mlr', 'tr', 4_000_000_000],
      ['12bn', 'en', 12_000_000_000], ['3 million', 'en', 3_000_000], ['1,2T', 'tr', 1_200_000_000_000],
      ['0,5k', 'tr', 500], ['1.250,5M', 'tr', 1_250_500_000], ['-1,5M', 'tr', -1_500_000],
      // sonekli rakamda TEK ayraç her zaman ondalıktır (TR'de bile): 1.5M = 1,5 milyon
      ['1.5M', 'tr', 1_500_000], ['1.250M', 'tr', 1_250_000],
    ]
    for (const [raw, lang, want] of cases) expect(parseNumber(raw, lang), raw).toBe(want)
    // Kısaltmasız yazım değişmedi
    expect(parseNumber('1.250.000', 'tr')).toBe(1_250_000)
    expect(parseNumber('1.250', 'tr')).toBe(1250)
    expect(parseNumber('0,42', 'tr')).toBe(0.42)
  })

  it('yüzde alanında kısaltma yok; tek başına "B" ve bilinmeyen sonek geçersiz', () => {
    expect(parseNumber('5k', 'tr', { percent: true })).toBeNaN()
    expect(parseNumber('7,42B', 'tr')).toBeNaN()
    expect(parseNumber('7,42 b', 'tr')).toBeNaN()
    expect(parseNumber('7,42x', 'tr')).toBeNaN()
    expect(parseNumber('k', 'tr')).toBeNaN()
    expect(isAmbiguousB('7,42B')).toBe(true)
    expect(isAmbiguousB('12 b')).toBe(true)
    expect(isAmbiguousB('5 bin')).toBe(false)
    expect(isAmbiguousB('12bn')).toBe(false)
    expect(NUMBER_ABBREVIATIONS.flatMap((a) => a.words)).not.toContain('b')
  })

  it('yazılırken yarım kalan sonek hata sayılmaz ("7,42mi" → milyon / milyar); bilinmeyen harf yarım değildir', () => {
    expect(isIncompleteNumber('7,42mi')).toBe(true)
    expect(isIncompleteNumber('7,42mily')).toBe(true)
    expect(isIncompleteNumber('2 tri')).toBe(true)
    expect(isIncompleteNumber('7b')).toBe(true)              // "bin" / "bn" yazılıyor olabilir; bırakılınca hata
    expect(isIncompleteNumber('7,42x')).toBe(false)
    expect(isIncompleteNumber('7,42G')).toBe(false)
    expect(hasAbbreviation('7,42G')).toBe(true)
    expect(hasAbbreviation('1.250.000')).toBe(false)
    expect(hasAbbreviation('7,42B')).toBe(false)
  })

  it('en büyük sayı sunucuyla aynı (TechErrorValues.MAX_NUMBER)', () => {
    expect(LIMITS.maxNumber).toBe(Number(/MAX_NUMBER\s*=\s*([0-9.e]+);/.exec(VALUES)[1]))
    expect(parseNumber('1000T', 'tr')).toBe(LIMITS.maxNumber)
  })
})
