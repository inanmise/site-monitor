import { describe, it, expect } from 'vitest'
import {
  suggestThresholds, suggestionIsPartial, roundUpTo,
  LOAD_FACTOR, TTFB_FACTOR, TTFB_MIN_HEADROOM_MS,
} from '../utils/pageSpeedThresholds.js'

/**
 * Eşik önerisi — ürünün en kolay yanlış kullanılan yeri.
 *
 * <p>Öneri ölçülen değerin KENDİSİNE yaklaşırsa her normal dalgalanmada alarm çalar; çok
 * yükseğe kaçarsa alarm hiç çalmaz. İkisi de sessiz hatadır: ekran doğru görünür, yalnız
 * alarm ya sürekli öter ya hiç ötmez. Bu yüzden çarpanlar ve yuvarlama yönü pinlenir.
 */
describe('roundUpTo', () => {
  it('HEP yukarı yuvarlar — aşağı yuvarlamak eşiği ölçülene yaklaştırır (yanlış alarm)', () => {
    expect(roundUpTo(6594, 500)).toBe(7000)
    expect(roundUpTo(7000, 500)).toBe(7000)     // tam katsa büyütmez
    expect(roundUpTo(7001, 500)).toBe(7500)
    expect(roundUpTo(210.45, 10)).toBe(220)
  })

  it('anlamsız girdide 0 döner (uydurma eşik üretmez)', () => {
    expect(roundUpTo(0, 500)).toBe(0)
    expect(roundUpTo(-5, 500)).toBe(0)
    expect(roundUpTo(null, 500)).toBe(0)
    expect(roundUpTo('abc', 500)).toBe(0)
  })

  it('adım verilmezse yukarı tam sayıya yuvarlar', () => {
    expect(roundUpTo(12.1, 0)).toBe(13)
  })
})

describe('suggestThresholds', () => {
  // example.com ana sayfasından gelen gerçek ölçüm (2026-08-23) — sayılar bu vakadan.
  const real = { response_ms: 3297, ttfb_ms: 90, total_bytes: 46.4 * 1024 * 1024, request_count: 183 }

  it('gerçek bir ölçümden okunur ve savunulabilir dört değer üretir', () => {
    const s = suggestThresholds(real)

    expect(s.maxLoadMs).toBe(7000)        // 3297 × 2 = 6594 → yukarı 500
    expect(s.maxTtfbMs).toBe(300)         // max(90×3, 90+200) = 290 → yukarı 50
    expect(s.maxPageKb).toBe(55000)       // 47513.6 KB × 1.15 = 54641 → yukarı 1000
    expect(s.maxRequests).toBe(220)       // 183 × 1.15 = 210.45 → yukarı 10
  })

  it('SÜRE en geniş, BOYUT/İSTEK en dar payı alır — metrikler aynı oynaklıkta değil', () => {
    const s = suggestThresholds(real)

    // Süre payı (×2) boyut payından (%15) belirgin biçimde geniş olmalı.
    const loadRatio = s.maxLoadMs / real.response_ms
    const sizeRatio = s.maxPageKb / (real.total_bytes / 1024)
    expect(loadRatio).toBeGreaterThan(1.8)
    expect(sizeRatio).toBeLessThan(1.25)
    expect(loadRatio).toBeGreaterThan(sizeRatio)
  })

  it('TTFB küçükken MUTLAK pay devreye girer — salt oran çok dar kalırdı', () => {
    // 20 ms'lik bir TTFB'de ×3 = 60 ms; tek bir GC duraklaması bile onu aşar.
    const s = suggestThresholds({ ...real, ttfb_ms: 20 })

    expect(s.maxTtfbMs).toBe(250)                       // 20 + 200 = 220 → yukarı 50
    expect(s.maxTtfbMs).toBeGreaterThan(20 * TTFB_FACTOR)
  })

  it('TTFB büyükken ORANSAL pay devreye girer', () => {
    // 400 ms'de ×3 = 1200, mutlak pay (600) bunun altında kalır.
    const s = suggestThresholds({ ...real, ttfb_ms: 400 })

    expect(s.maxTtfbMs).toBe(1200)
    expect(s.maxTtfbMs).toBe(400 * TTFB_FACTOR)
  })

  it('ölçülemeyen metrik için eşik ÖNERİLMEZ (null) — uydurma sayı yazılmaz', () => {
    const s = suggestThresholds({ response_ms: 1000 })

    expect(s.maxLoadMs).toBe(2000)
    expect(s.maxTtfbMs).toBeNull()
    expect(s.maxPageKb).toBeNull()
    expect(s.maxRequests).toBeNull()
  })

  it('boş/bozuk yanıtta hepsi null — çökmez', () => {
    for (const bad of [null, undefined, {}, { response_ms: 'abc', ttfb_ms: -1 }]) {
      const s = suggestThresholds(bad)
      expect(Object.values(s).every(v => v === null)).toBe(true)
    }
  })

  it('çarpanlar belgelenen değerlerde — sessizce değişirse bu satır kırılır', () => {
    expect(LOAD_FACTOR).toBe(2)
    expect(TTFB_FACTOR).toBe(3)
    expect(TTFB_MIN_HEADROOM_MS).toBe(200)
  })
})

describe('suggestionIsPartial', () => {
  it('kaynak SAYISI ya da BAYT tavanına takılan ölçüm kısmi sayılır', () => {
    expect(suggestionIsPartial({ capped: true })).toBe(true)
    expect(suggestionIsPartial({ bytes_truncated: true })).toBe(true)
    expect(suggestionIsPartial({ capped: false, bytes_truncated: false })).toBe(false)
    expect(suggestionIsPartial(null)).toBe(false)
  })
})
