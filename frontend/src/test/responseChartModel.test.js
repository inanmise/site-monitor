import { describe, it, expect } from 'vitest'
import {
  BUCKET_MS, PRESETS, buildSummary, bucketEndLabel, computeStats, formatAvailability, formatTick, isLiveRange, kindMeta,
  makeFormat, maxTicksFor, nearestRank, nextWiderPreset, niceTimeTicks, requestParams, resolveUnit, shapeSeries, shortDateTime,
  tones, validThreshold,
} from '../components/responsechart/responseChartModel.js'

/**
 * Yanıt süresi grafiği modeli (2026-09-28 yeniden tasarım) — saf işlevler. Zaman damgaları `now`dan türetilir
 * (kayan pencereye karşı sabit tarih yazılmaz); yalnız biçim testlerinde (pencereyle karşılaştırılmayan) yerel
 * `Date` kurulur.
 */
const H = 3_600_000
const M = 60_000
// Sunucu biçimi: UTC, saniyeye kadar, Z'siz.
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
// Saat başına hizalı taban — kova sayısı iddialarında iki damga aynı saate düşsün diye (bkz. kova sınırı tuzağı).
const BASE = Math.floor((Date.now() - 48 * H) / H) * H
const pt = (msFromBase, fields) => ({ ts: iso(BASE + msFromBase), count: 1, down: 0, ...fields })
const env = (series, extra = {}) => ({ series, bucket: 'hour', unit: 'ms', from: iso(BASE - H), to: iso(BASE + 30 * H), ...extra })

describe('shapeSeries — tel biçimi → çizilecek noktalar', () => {
  it('bozuk kayıtları (dizi elemanı, ts yok, geçersiz tarih) düşürür, geçerlileri zamana göre sıralar', () => {
    const s = shapeSeries(env([
      pt(2 * H, { avg: 30, min: 30, max: 30, p95: 30 }),
      ['2026-08-06T21:43:00', 0, false],
      { avg: 1, count: 1 },
      { ts: 'bozuk', avg: 1, count: 1 },
      pt(0, { avg: 10, min: 10, max: 10, p95: 10 }),
      pt(H, { avg: 20, min: 20, max: 20, p95: 20 }),
    ]))
    expect(s.real.map((p) => p.avg)).toEqual([10, 20, 30])
    expect(s.points.every((p) => !p.gap)).toBe(true)
  })

  it('kontrol yapılmayan dilim çizgiyle BİRLEŞTİRİLMEZ: araya boşluk noktası girer', () => {
    const s = shapeSeries(env([0, 1, 2, 6, 7].map((h) => pt(h * H, { avg: 100 + h, count: 12 }))))
    const gaps = s.points.filter((p) => p.gap)
    expect(gaps).toHaveLength(1)
    // Boşluk, son dolu kovanın hemen ardında (2. saat + 1 kova) ve sonraki dolu kovadan önce.
    expect(gaps[0].t).toBe(BASE + 3 * H)
    expect(s.points.findIndex((p) => p.gap)).toBe(3)
  })

  it('düzenli ama iki farklı adımlı kadans (15 dk izleme, 10 dk kova → 10/20 dk) SAHTE boşluk üretmez', () => {
    const offsets = [0, 10, 30, 40, 60, 70, 90, 100, 120]
    const s = shapeSeries(env(offsets.map((m) => pt(m * M, { avg: 50, count: 1 })), { bucket: '10m' }))
    expect(s.points.some((p) => p.gap)).toBe(false)
  })

  it('iki yanı boşluk olan tek nokta "isolated" işaretlenir (çizgide görünmez, nokta çizilir)', () => {
    const hours = [0, 1, 2, 3, 4, 5, 12, 18, 19, 20, 21, 22, 23]
    const s = shapeSeries(env(hours.map((h) => pt(h * H, { avg: h === 12 ? 5 : 1, count: 2 }))))
    const lone = s.points.find((p) => p.avg === 5)
    expect(lone.isolated).toBe(true)
    expect(s.points.filter((p) => p.isolated)).toHaveLength(1)
  })

  it('ardışık başarısız kovalar TEK kesinti koşusunda birleşir; araya başarılı kova girince yeni koşu', () => {
    const s = shapeSeries(env([
      pt(0, { avg: 10, count: 4, down: 1 }), pt(H, { avg: 11, count: 4, down: 4 }), pt(2 * H, { avg: 12, count: 4 }),
      pt(3 * H, { avg: null, count: 4, down: 4 }),
    ]))
    expect(s.downRuns).toEqual([
      { x1: BASE, x2: BASE + 2 * H, down: 5 },
      { x1: BASE + 3 * H, x2: BASE + 4 * H, down: 4 },
    ])
    // Değeri olmayan başarısız kova işaretini tabana (0) koyar; değerli olan kendi değerinde.
    expect(s.points.map((p) => p.downY)).toEqual([10, 11, null, 0])
  })

  it('görünen aralık isteğin GERÇEK penceresidir (from–to), veri olmayan uçlar dahil', () => {
    const s = shapeSeries(env([pt(5 * H, { avg: 1 })]))
    expect(s.domain).toEqual([BASE - H, BASE + 30 * H])
  })

  it('tüm kontrolleri başarısız pencere "veri yok" DEĞİLDİR (hasData); değer olmadığı da ayrıca bilinir', () => {
    const s = shapeSeries(env([pt(0, { avg: null, count: 3, down: 3 })]))
    expect(s.hasData).toBe(true)
    expect(s.hasValues).toBe(false)
  })

  it('sertifikada süre yok ama kalan gün varsa veri sayılır (aux)', () => {
    const s = shapeSeries(env([pt(0, { avg: null, days: 64 })]), { aux: 'days' })
    expect(s.hasData).toBe(true)
    expect(s.hasAux).toBe(true)
  })

  it('her kova tek kontrolse "raw" (birebir ölçüm) — min–maks bandı yok; çok kontrollü kovada bant var', () => {
    expect(shapeSeries(env([pt(0, { avg: 1, min: 1, max: 1 }), pt(H, { avg: 2, min: 2, max: 2 })])).raw).toBe(true)
    const agg = shapeSeries(env([pt(0, { avg: 5, min: 1, max: 9, count: 3 })]))
    expect(agg.raw).toBe(false)
    expect(agg.points[0].band).toEqual([1, 9])
  })

  it('zarf dizi değilse (ör. DNS test taklidi data: []) çökmez, boş döner', () => {
    expect(shapeSeries([]).hasData).toBe(false)
    expect(shapeSeries(null).points).toEqual([])
  })
})

describe('computeStats — özet ölçümler', () => {
  it('kovalı pencere: kontrol sayısıyla ağırlıklı ortalama, p95 TEPE olarak işaretli, min/max tam, erişilebilirlik', () => {
    const s = shapeSeries(env([
      pt(0, { avg: 120, min: 90, max: 150, p95: 145, count: 3, down: 0 }),
      pt(H, { avg: 130, min: 100, max: 160, p95: 155, count: 1, down: 1 }),
    ]))
    const st = computeStats(s)
    expect(st.avg).toBeCloseTo(122.5)          // (120·3 + 130·1) / 4
    expect(st.p95).toBe(155)
    expect(st.p95Peak).toBe(true)
    expect(st.median).toBeNull()               // kovalardan medyan hesaplanamaz → gösterilmez
    expect([st.min, st.max]).toEqual([90, 160])
    expect([st.samples, st.failed, st.passed]).toEqual([4, 1, 3])
    expect(st.availability).toBe(75)
    expect(st.lastFailTs).toBe(iso(BASE + H))
  })

  it('raw pencere: ortalama/medyan/p95 birebir ölçümlerden (en yakın sıra, sunucunun kova p95 tanımı)', () => {
    const vals = [300, 100, 1000, 200, 400]
    const s = shapeSeries(env(vals.map((v, i) => pt(i * H, { avg: v, min: v, max: v, p95: v }))))
    const st = computeStats(s)
    expect(st.avg).toBe(400)
    expect(st.median).toBe(300)
    expect(st.p95).toBe(1000)
    expect(st.p95Peak).toBe(false)
  })

  it('ping paket kaybı kontrol sayısıyla ağırlıklı; sertifika kalan gün SON kontrolden', () => {
    const ping = shapeSeries(env([pt(0, { avg: 5, count: 3, loss: 0 }), pt(H, { avg: 5, count: 1, loss: 100 })]), { aux: 'loss' })
    expect(computeStats(ping, { aux: 'loss' }).loss).toBe(25)
    const ssl = shapeSeries(env([pt(0, { avg: 5, days: 70 }), pt(H, { avg: 5, days: 69 }), pt(2 * H, { avg: 5 })]), { aux: 'days' })
    expect(computeStats(ssl, { aux: 'days' }).days).toBe(69)
  })

  it('eşik üstü kova sayısı yalnız geçerli eşikte', () => {
    const s = shapeSeries(env([pt(0, { avg: 100, count: 2 }), pt(H, { avg: 300, count: 2 })]))
    expect(computeStats(s, { threshold: 200 }).overThreshold).toBe(1)
    expect(computeStats(s, { threshold: 0 }).overThreshold).toBe(0)
  })

  it('tones: erişilebilirlik / başarısız / eşik / kalan gün aciliyeti', () => {
    const base = { samples: 1000, failed: 0, availability: 100, avg: 100, p95: 150, loss: null, days: null }
    expect(tones(base)).toMatchObject({ availability: 'ok', failed: 'ok', avg: 'neutral' })
    expect(tones({ ...base, failed: 5, availability: 99.5 })).toMatchObject({ availability: 'warn', failed: 'warn' })
    expect(tones({ ...base, failed: 50, availability: 95 })).toMatchObject({ availability: 'crit', failed: 'crit' })
    expect(tones(base, 120)).toMatchObject({ avg: 'neutral', p95: 'warn' })
    expect(tones(base, 90)).toMatchObject({ avg: 'crit' })
    expect(tones({ ...base, days: 10 }).days).toBe('crit')
  })
})

describe('biçimler', () => {
  it('süre: 1 sn altı "ms", üstü saniye (yerel ondalık); eksen kısa', () => {
    const f = makeFormat('ms', { locale: 'en-GB', ms: 'ms', sec: 's' })
    expect(f.value(212.4)).toBe('212 ms')
    expect(f.value(1234)).toBe('1.23 s')
    expect(f.value(12_345)).toBe('12.3 s')
    expect(f.value(null)).toBe('—')
    // Eksende bölünmez boşluk (recharts dar eksende "700 / ms" diye iki satıra kırmasın)
    expect(f.axis(1500)).toBe('1.5 s')
    expect(f.axis(700)).toBe('700 ms')
    expect(makeFormat('B', { locale: 'en-GB' }).axis(2_500_000)).toBe('2 MB')
    const tr = makeFormat('ms', { locale: 'tr-TR', ms: 'ms', sec: 'sn' })
    expect(tr.value(1200)).toBe('1,2 sn')
  })

  it('boyut formatBytes ile, sayı birimsiz (sayfa bütünlüğü kırık kaynak)', () => {
    expect(makeFormat('B', { locale: 'en-GB' }).value(2_500_000)).toBe('2.4 MB')
    expect(makeFormat('', { locale: 'en-GB' }).value(0.44)).toBe('0.4')
  })

  it('erişilebilirlik: başarısız kontrol varken yuvarlama %100 GÖSTERMEZ', () => {
    expect(formatAvailability(99.9996, 1, 'en-GB')).toBe('99.99%')
    expect(formatAvailability(100, 0, 'en-GB')).toBe('100%')
    expect(formatAvailability(null)).toBe('—')
  })

  it('tür → başlık/yardımcı seri/birim; sayfa bütünlüğü SAYI birimli', () => {
    expect(kindMeta('ping')).toMatchObject({ aux: 'loss', titleKey: 'rtc.title.ping' })
    expect(kindMeta('ssl').aux).toBe('days')
    expect(kindMeta('pagespeed', 'size').titleKey).toBe('pspd.metricSize')
    expect(resolveUnit('page', undefined)).toBe('')
    expect(resolveUnit('http', undefined)).toBe('ms')
    expect(resolveUnit('pagespeed', 'B')).toBe('B')
    expect(validThreshold('3000')).toBe(3000)
    expect(validThreshold(0)).toBeNull()
    expect(validThreshold(null)).toBeNull()
  })

  it('nearestRank', () => {
    expect(nearestRank([1, 2, 3, 4], 0.5)).toBe(2)
    expect(nearestRank([], 0.5)).toBeNull()
  })
})

describe('zaman ekseni', () => {
  it('telefon genişliğinde (≈ 300 px) en fazla 4 etiket, geniş ekranda en fazla 8', () => {
    expect(maxTicksFor(300)).toBe(4)
    expect(maxTicksFor(900)).toBe(8)
    expect(maxTicksFor(0)).toBeGreaterThanOrEqual(2)
  })

  it('etiketler YEREL saate hizalı ve sınırı aşmaz — her pencere ve genişlikte', () => {
    const now = Date.now()
    for (const p of PRESETS) {
      const span = (p.hours ?? p.days * 24) * H
      for (const max of [2, 4, 8]) {
        const { ticks, step } = niceTimeTicks(now - span, now, max)
        expect(ticks.length, `${p.key}/${max}`).toBeLessThanOrEqual(max)
        expect(ticks.length, `${p.key}/${max}`).toBeGreaterThanOrEqual(1)
        for (const t of ticks) {
          expect(t).toBeGreaterThanOrEqual(now - span)
          expect(t).toBeLessThanOrEqual(now)
          const d = new Date(t)
          const minuteOfDay = d.getHours() * 60 + d.getMinutes()
          expect(d.getSeconds()).toBe(0)
          if (step >= 24 * H) expect(minuteOfDay).toBe(0)
          else expect(minuteOfDay % (step / M), `${p.key}/${max} ${d.toString()}`).toBe(0)
        }
      }
    }
  })

  it('pencere tam sınıra denk gelince (00:00 → 00:00) bir FAZLA etiket üretmez — adım büyür', () => {
    const d = new Date()
    d.setHours(0, 0, 0, 0)
    const x0 = d.getTime()
    // 6 sa adımı 00/06/12/18/24 = 5 etiket üretir → 4 sınırında bir üst adıma (12 sa) geçilmeli.
    const { ticks, step } = niceTimeTicks(x0, x0 + 24 * H, 4)
    expect(ticks.length).toBeLessThanOrEqual(4)
    expect(step).toBe(12 * H)
  })

  it('kısa etiket: gün adımında tarih, gece yarısında tarih, 26 saati aşan pencerede tarih + saat, yoksa saat', () => {
    const noon = new Date(2026, 8, 28, 12, 30).getTime()
    const midnight = new Date(2026, 8, 28, 0, 0).getTime()
    expect(formatTick(noon, { step: 24 * H, span: 7 * 24 * H }, 'en-GB')).toBe('28 Sept')
    expect(formatTick(midnight, { step: 6 * H, span: 24 * H }, 'en-GB')).toBe('28 Sept')
    expect(formatTick(noon, { step: 6 * H, span: 24 * H }, 'en-GB')).toBe('12:30')
    expect(formatTick(noon, { step: 12 * H, span: 48 * H }, 'en-GB')).toBe('28 Sept 12:30')
    // Türkçe: ay adı ("28 Eyl"), ICU'nun "28/09" biçimi DEĞİL
    expect(formatTick(noon, { step: 24 * H, span: 7 * 24 * H }, 'tr-TR')).toBe('28 Eyl')
  })

  it('kutucuk alt satırı için kısa tarih-saat: sunucu damgası UTC okunur, yerel saatte yazılır', () => {
    const local = new Date(2026, 8, 28, 6, 50)
    const serverTs = local.toISOString().slice(0, 19)            // UTC, Z'siz (sunucu biçimi)
    expect(shortDateTime(serverTs, 'en-GB')).toBe('28 Sept 06:50')
    expect(shortDateTime(null)).toBe('—')
  })

  it('kova bitişi: aynı gün saat, gün aşarsa tarih + saat', () => {
    const t = new Date(2026, 8, 28, 14, 30).getTime()
    expect(bucketEndLabel(t, BUCKET_MS['10m'], 'en-GB')).toBe('14:40')
    const late = new Date(2026, 8, 28, 3, 0).getTime()
    expect(bucketEndLabel(late, BUCKET_MS.day, 'en-GB')).toBe('29 Sept 03:00')
  })
})

describe('aralık parametreleri', () => {
  it('saatlik aralık açık from/to (≈ pencere), günlük aralık days, özel aralık aynen; metric eklenir', () => {
    const now = Date.now()
    const p = requestParams({ preset: '1h' }, now)
    expect(new Date(p.to + 'Z') - new Date(p.from + 'Z')).toBe(H)
    expect(requestParams({ preset: '24h' }, now)).toEqual({ days: 1 })
    expect(requestParams({ preset: '24h', metric: 'size' }, now)).toEqual({ days: 1, metric: 'size' })
    expect(requestParams({ preset: '7d', custom: { from: 'a', to: 'b' } }, now)).toEqual({ from: 'a', to: 'b' })
  })

  it('canlı yalnız ≤ 24 sa hazır aralıkta; genişletme önerisi bir sonraki hazır aralık', () => {
    expect(isLiveRange({ preset: '24h' })).toBe(true)
    expect(isLiveRange({ preset: '7d' })).toBe(false)
    expect(isLiveRange({ preset: '1h', custom: { from: 'a', to: 'b' } })).toBe(false)
    expect(nextWiderPreset('24h')).toBe('7d')
    expect(nextWiderPreset('90d')).toBeNull()
  })
})

describe('buildSummary — erişilebilir özet', () => {
  const t = (k, ...a) => `${k}(${a.join('|')})`
  const fmt = makeFormat('ms', { locale: 'en-GB', ms: 'ms', sec: 's' })
  it('değerli pencere: aralık + ortalama + en yüksek + erişilebilirlik + başarısız sayısı', () => {
    const out = buildSummary({ t, rangeKey: '24h', fmt, stats: { avg: 212, max: 1200, availability: 99.5, failed: 3 } })
    expect(out).toBe('rtc.summary(rtc.span.24h()|212 ms|1.2 s|99.5%|rtc.summaryFailed(3))')
  })
  it('değersiz pencere ve tekil/sıfır başarısız', () => {
    expect(buildSummary({ t, rangeKey: 'custom', fmt, stats: { avg: null, availability: 100, failed: 0 } }))
      .toBe('rtc.summaryNoValues(rtc.span.custom()|100%|rtc.summaryNoFail())')
    expect(buildSummary({ t, rangeKey: '1h', fmt, stats: { avg: 1, max: 1, availability: 50, failed: 1 } }))
      .toContain('rtc.summaryFailedOne()')
  })
})
