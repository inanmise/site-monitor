import { describe, it, expect } from 'vitest'
import {
  MAX_TYPED, floorMinute, formatLocal, liveReady, minuteOptions, hourOptions, parseIso, parseTime, parseTyped, presetsFor, stepValue, toIso,
} from '../utils/smartDateTime.js'

// Yerel saatle kurulan Date'ler — testler çalışma ortamının saat diliminden bağımsız (CI UTC, yerel İstanbul).
const L = (y, mo, d, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi, 0, 0)
const NOW = L(2026, 11, 16, 15, 42)
const BASE = L(2026, 11, 10, 8, 20)
const fmt = (r) => (r.date ? formatLocal(r.date) : r.empty ? 'EMPTY' : 'ERROR')

describe('parseTyped — yazım kuralları', () => {
  // [girdi, base var mı, beklenen yerel metin, complete]
  const TABLE = [
    ['16.11.2026 14:30', false, '16.11.2026 14:30', true],
    ['16/11/2026 14:30', false, '16.11.2026 14:30', true],
    ['16-11-26 1430', false, '16.11.2026 14:30', true],
    ['  16.11.2026   14.30 ', false, '16.11.2026 14:30', true],
    ['1.2.2026 9:05', false, '01.02.2026 09:05', true],
    ['16.11 14:30', false, '16.11.2026 14:30', true],
    ['16.11.2026', true, '16.11.2026 08:20', false],      // saat yok → mevcut değerin saati
    ['16.11.2026', false, '16.11.2026 00:00', false],     // mevcut değer de yok → 00:00
    ['16.11', false, '16.11.2026 00:00', false],          // geçerli gün → gün (saat değil)
    ['14.30', true, '10.11.2026 14:30', false],           // geçersiz gün (30. ay) → saat olarak, mevcut günde
    ['2026-11-16 14:30', false, '16.11.2026 14:30', true],
    ['2026-11-16T14:30:00', false, '16.11.2026 14:30', true],
    ['2026-11-16', true, '16.11.2026 08:20', false],
    ['14:30', true, '10.11.2026 14:30', false],           // yalnız saat → mevcut değerin günü
    ['14:30', false, '16.11.2026 14:30', false],          // mevcut değer yok → bugün
    ['1430', false, '16.11.2026 14:30', false],
    ['930', false, '16.11.2026 09:30', false],
    ['9', false, '16.11.2026 09:00', false],
    ['şimdi', false, '16.11.2026 15:42', true],
    ['ŞİMDİ', false, '16.11.2026 15:42', true],
    ['Now', false, '16.11.2026 15:42', true],
    ['bugün 09:15', false, '16.11.2026 09:15', true],
    ['today 9:15', false, '16.11.2026 09:15', true],
    ['dün 23:50', false, '15.11.2026 23:50', true],
    ['yesterday', true, '15.11.2026 08:20', false],       // gün sözcüğü yalnız → mevcut saat
    ['DÜN', false, '15.11.2026 00:00', false],
    ['', false, 'EMPTY', undefined],
    ['   ', false, 'EMPTY', undefined],
    ['31.02.2026 10:00', false, 'ERROR', undefined],     // takvimde yok
    ['29.02.2026', false, 'ERROR', undefined],
    ['29.02.2028', false, '29.02.2028 00:00', false],   // artık yıl
    ['16.11.2026 25:00', false, 'ERROR', undefined],
    ['16.11.2026 14:60', false, 'ERROR', undefined],
    ['16.13.2026', false, 'ERROR', undefined],
    ['16.11.1999 10:00', false, 'ERROR', undefined],     // yıl aralığı 2000–2100
    ['yarın', false, 'ERROR', undefined],
    ['abc', false, 'ERROR', undefined],
    ['14:3', false, 'ERROR', undefined],
    ['bugün 25:00', false, 'ERROR', undefined],
  ]
  for (const [input, withBase, expected, complete] of TABLE) {
    it(`"${input}"${withBase ? ' (mevcut değer var)' : ''} → ${expected}`, () => {
      const r = parseTyped(input, { now: NOW, base: withBase ? BASE : null })
      expect(fmt(r)).toBe(expected)
      if (complete !== undefined) expect(r.complete).toBe(complete)
    })
  }

  it('sınırlı girdi: MAX_TYPED üstü ayrıştırılmaz; uzun ve karmaşık metin hızlı döner', () => {
    expect(parseTyped('1'.repeat(MAX_TYPED + 1), { now: NOW }).error).toBe(true)
    const t0 = Date.now()
    for (let i = 0; i < 2000; i++) parseTyped(`${'1.'.repeat(19)}x`, { now: NOW })
    expect(Date.now() - t0).toBeLessThan(1000)
  })

  it('parseTime: biçimler ve aralık', () => {
    expect(parseTime('14:30')).toEqual([14, 30])
    expect(parseTime('14.30')).toEqual([14, 30])
    expect(parseTime('1430')).toEqual([14, 30])
    expect(parseTime('14:30:59')).toEqual([14, 30])
    expect(parseTime('24:00')).toBeNull()
    expect(parseTime('7')).toEqual([7, 0])
    expect(parseTime('x7')).toBeNull()
  })
})

describe('ISO dönüşümü ve biçim', () => {
  it('toIso UTC yazar, parseIso UTC okur — gidiş dönüş aynı an (saniye sıfır)', () => {
    const d = L(2026, 11, 16, 14, 30)
    const iso = toIso(d)
    expect(iso).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00$/)
    expect(parseIso(iso).getTime()).toBe(d.getTime())
    expect(parseIso('2026-11-16T11:30:00Z').toISOString()).toBe('2026-11-16T11:30:00.000Z')
    expect(parseIso('')).toBeNull()
    expect(parseIso('bozuk')).toBeNull()
    expect(toIso(null)).toBe('')
    expect(formatLocal(null)).toBe('')
  })
})

describe('presetsFor — hızlı seçimler', () => {
  it('çapa yoksa: Şimdi, 15 / 30 / 60 / 120 dk önce, dün — hepsi geçmişte, seçilebilir', () => {
    const p = presetsFor({ now: NOW })
    expect(p.map((x) => `${x.key}${x.minutes ? `-${x.minutes}` : ''}`)).toEqual(['now', 'ago-15', 'ago-30', 'ago-60', 'ago-120', 'yesterday'])
    expect(formatLocal(p[0].at)).toBe('16.11.2026 15:42')
    expect(formatLocal(p[1].at)).toBe('16.11.2026 15:27')
    expect(formatLocal(p[5].at)).toBe('15.11.2026 15:42')
    expect(p.every((x) => !x.disabled)).toBe(true)
  })

  it('çapa varsa: Şimdi + oluştan +5 / 15 / 30 / 60 / 120 dk; gelecekteki an pasif', () => {
    const p = presetsFor({ anchor: L(2026, 11, 16, 15, 0), now: NOW })
    expect(p.map((x) => `${x.key}${x.minutes ? `-${x.minutes}` : ''}`)).toEqual(['now', 'after-5', 'after-15', 'after-30', 'after-60', 'after-120'])
    expect(formatLocal(p[2].at)).toBe('16.11.2026 15:15')
    expect(p.filter((x) => x.disabled).map((x) => x.minutes)).toEqual([60, 120])   // 16:00 ve 17:00 henüz gelmedi
    expect(p.find((x) => x.minutes === 60).future).toBe(true)
  })

  it('min öncesi an pasif (tespit zamanından önceki çözüm)', () => {
    const p = presetsFor({ anchor: L(2026, 11, 16, 14, 0), min: L(2026, 11, 16, 14, 20), now: NOW })
    const off = p.filter((x) => x.disabled).map((x) => x.minutes)
    expect(off).toEqual([5, 15, 120])                                   // 5 / 15: min öncesi; 120 (16:00): gelecek
    expect(p.find((x) => x.minutes === 5).future).toBe(false)
  })
})

describe('saat / dakika listeleri ve klavye adımı', () => {
  it('saat 00–23, dakika 5 dk adım; adımda olmayan seçili dakika listeye sıralı eklenir', () => {
    expect(hourOptions()).toHaveLength(24)
    expect(minuteOptions()).toEqual([0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55])
    expect(minuteOptions(42)).toEqual([0, 5, 10, 15, 20, 25, 30, 35, 40, 42, 45, 50, 55])
    expect(minuteOptions(45)).toHaveLength(12)
  })

  it('↑ / ↓ 5 dk, Shift 1 saat; değer yoksa null', () => {
    const d = L(2026, 11, 16, 23, 58)
    expect(formatLocal(stepValue(d, 'ArrowUp', false))).toBe('17.11.2026 00:03')
    expect(formatLocal(stepValue(d, 'ArrowDown', true))).toBe('16.11.2026 22:58')
    expect(stepValue(null, 'ArrowUp', false)).toBeNull()
    expect(stepValue(d, 'Enter', false)).toBeNull()
    expect(floorMinute(new Date(2026, 0, 1, 1, 1, 59, 999)).getSeconds()).toBe(0)
  })
})

describe('inceleme düzeltmeleri (2026-11-17)', () => {
  it('yıl yazılmadıysa şimdiye en yakın yıl: ocakta "31.12" geçen yıl; kasımda "20.11" bu yıl', () => {
    expect(formatLocal(parseTyped('31.12 10:00', { now: L(2027, 1, 5, 9, 0) }).date)).toBe('31.12.2026 10:00')
    expect(formatLocal(parseTyped('20.11 10:00', { now: NOW }).date)).toBe('20.11.2026 10:00')
    expect(formatLocal(parseTyped('1.6', { now: NOW }).date)).toBe('01.06.2026 00:00')
    expect(formatLocal(parseTyped('29.02 08:00', { now: L(2029, 1, 10) }).date)).toBe('29.02.2028 08:00')   // yalnız biri takvimde
  })

  it('liveReady: dakikası tam saat + gün; yarım saat / yarım yıl / yalnız gün / yalnız saat anında kaydedilmez', () => {
    const TABLE = [
      ['16.11.2026 14:30', true], ['16.11 14:30', true], ['16.11.2026 1430', true], ['dün 09:15', true],
      ['2026-11-16 14:30', true], ['2026-11-16T14:30:00', true], ['şimdi', true], ['Now', true],
      ['16.11.2026 9', false], ['16.11.2026 143', false], ['16.11.2026 14:3', false], ['16.11.20 14:30', false],
      ['16-11-26 1430', false], ['16.11.2026', false], ['14:30', false], ['dün', false], ['', false], ['abc', false],
      ['1'.repeat(MAX_TYPED + 1), false],
    ]
    for (const [text, ok] of TABLE) expect([text, liveReady(text)]).toEqual([text, ok])
  })
})
