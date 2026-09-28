import { describe, it, expect } from 'vitest'
import {
  compareToOlder, daysDomain, daysTone, detectRenewals, findOlder, formatRate, latestDays, paramsKey, rangeParams,
  renewalWindow, shapeDaysSeries, successRate, techLine, isFailed, BUCKET_MS,
} from '../components/certmodal/certHistoryModel.js'
import { setDateLocale } from '../i18n/dateLocale.js'

/**
 * Sertifika Kontrol Geçmişi saf modeli. Model "şimdi"ye göre hiçbir şey hesaplamaz (kayan pencere yok) — sabit
 * damgalar burada zaman bombası DEĞİL: her karşılaştırma verinin kendi damgaları arasında.
 * Tel biçimi gerçek: `CertificateCheck` (snake_case) ve `/ssl/response-series` zarfı (`series[].days`, `bucket`, `down_total`).
 */
const H = BUCKET_MS.hour
const pt = (ts, days, over = {}) => ({ ts, count: 1, down: 0, avg: 300, min: 300, max: 300, p95: 300, days, ...over })

describe('certHistoryModel — kalan gün tonu ve durum', () => {
  it('daysTone: süresi dolmuş/≤7 danger · uyarı bayrağı warning · diğer ok · değer yok none', () => {
    expect(daysTone(null)).toBe('none')
    expect(daysTone('')).toBe('none')
    expect(daysTone(-3)).toBe('danger')
    expect(daysTone(7)).toBe('danger')
    expect(daysTone(8, true)).toBe('warning')
    expect(daysTone(8, false)).toBe('ok')
    expect(daysTone(400)).toBe('ok')
  })

  it('isFailed yalnız status "error"; valid ve warning başarılı kontroldür', () => {
    expect(isFailed({ status: 'error' })).toBe(true)
    expect(isFailed({ status: 'ERROR' })).toBe(true)
    expect(isFailed({ status: 'warning' })).toBe(false)
    expect(isFailed({ status: 'valid' })).toBe(false)
  })
})

describe('certHistoryModel — önceki kontrole göre değişim', () => {
  const ok = (days, over = {}) => ({ status: 'valid', days_remaining: days, fingerprint: 'AA11', serial_number: '01', ...over })

  it('yenileme: kalan gün ≥ 2 arttı → renewed + delta', () => {
    expect(compareToOlder(ok(377, { fingerprint: 'BB22' }), ok(12))).toMatchObject({ delta: 365, renewed: true, certChanged: true })
  })

  it('olağan azalış: delta −1, yenileme yok, sertifika aynı', () => {
    expect(compareToOlder(ok(29), ok(30))).toEqual({ delta: -1, renewed: false, certChanged: false, olderSerial: '01' })
  })

  it('+1 gün yuvarlama oynaması yenileme SAYILMAZ', () => {
    expect(compareToOlder(ok(31), ok(30)).renewed).toBe(false)
  })

  it('parmak izi değişti ama kalan gün aynı → certChanged (yeni sertifika), renewed değil', () => {
    const r = compareToOlder(ok(30, { fingerprint: 'CC33', serial_number: '02' }), ok(30))
    expect(r).toMatchObject({ delta: 0, renewed: false, certChanged: true, olderSerial: '01' })
  })

  it('parmak izi yoksa seri no. ile karşılaştırılır; iki nokta üst üste/boşluk/harf farkı değişim sayılmaz', () => {
    expect(compareToOlder(ok(30, { fingerprint: null, serial_number: '0a:1b' }), ok(30, { fingerprint: null, serial_number: '0A1B' })).certChanged).toBe(false)
    expect(compareToOlder(ok(30, { fingerprint: null, serial_number: '0A1C' }), ok(30, { fingerprint: null, serial_number: '0A1B' })).certChanged).toBe(true)
  })

  it('başarısız kontrol ya da komşu yoksa karşılaştırma yok (null)', () => {
    expect(compareToOlder({ status: 'error', days_remaining: null }, ok(30))).toBeNull()
    expect(compareToOlder(ok(30), null)).toBeNull()
  })

  it('findOlder başarısız (değersiz) kontrolleri atlar; sayfa sonunda null', () => {
    const items = [ok(29), { status: 'error', days_remaining: null, fingerprint: null, serial_number: null }, ok(30)]
    expect(findOlder(items, 0)).toBe(items[2])
    expect(findOlder(items, 2)).toBeNull()
    expect(findOlder(null, 0)).toBeNull()
  })
})

describe('certHistoryModel — seriden yenileme algılama', () => {
  it('olağan azalan seri: yenileme yok', () => {
    const pts = [30, 30, 29, 29, 28].map((d, i) => ({ t: i * H, ts: `x${i}`, days: d }))
    expect(detectRenewals(pts)).toEqual([])
  })

  it('kova ortalaması yenilemeyi iki kovaya bölse de TEK olay; `to` koşunun sonu', () => {
    const pts = [12, 11, 205, 399, 398].map((d, i) => ({ t: i * H, ts: `x${i}`, days: d }))
    expect(detectRenewals(pts)).toEqual([{ t: 2 * H, ts: 'x2', from: 11, to: 399, prevT: H }])
  })

  it('değersiz kova (tamamı başarısız) karşılaştırmayı kırmaz; ayrı iki yenileme ayrı sayılır', () => {
    const pts = [
      { t: 0, ts: 'a', days: 5 }, { t: H, ts: 'b', days: null }, { t: 2 * H, ts: 'c', days: 90 },
      { t: 3 * H, ts: 'd', days: 89 }, { t: 4 * H, ts: 'e', days: 400 },
    ]
    const r = detectRenewals(pts)
    expect(r).toHaveLength(2)
    expect(r[0]).toMatchObject({ ts: 'c', from: 5, to: 90 })
    expect(r[1]).toMatchObject({ ts: 'e', from: 89, to: 400 })
  })
})

describe('certHistoryModel — shapeDaysSeries', () => {
  const env = {
    bucket: 'hour', unit: 'ms', from: '2026-09-20T00:00:00', to: '2026-09-20T12:00:00', total: 6, down_total: 2,
    series: [
      pt('2026-09-20T05:00:00', 400), pt('2026-09-20T01:00:00', 13),   // sırasız gelse de sıralanır
      pt('2026-09-20T02:00:00', 12), pt('2026-09-20T03:00:00', null, { down: 2, count: 2, avg: null }),
      pt('2026-09-20T04:00:00', 12),
      pt('2026-09-20T09:00:00', 399),                                  // 05→09 arası 3 kova boş → boşluk noktası
    ],
  }

  it('sıralar, boş dilime boşluk noktası koyar, yenilemeyi işaretler, uçları ve hata sayısını verir', () => {
    const s = shapeDaysSeries(env)
    const real = s.points.filter((p) => !p.gap)
    expect(real.map((p) => p.ts)).toEqual([
      '2026-09-20T01:00:00', '2026-09-20T02:00:00', '2026-09-20T03:00:00', '2026-09-20T04:00:00',
      '2026-09-20T05:00:00', '2026-09-20T09:00:00'])
    expect(s.points.filter((p) => p.gap)).toHaveLength(1)
    expect(s.points.find((p) => p.gap).days).toBeNull()
    expect(s.renewals).toEqual([{ t: Date.parse('2026-09-20T05:00:00Z'), ts: '2026-09-20T05:00:00', from: 12, to: 400,
      prevT: Date.parse('2026-09-20T04:00:00Z') }])
    expect(s.points.find((p) => p.ts === '2026-09-20T05:00:00').renewed).toBe(true)
    expect(s.first.days).toBe(13)
    expect(s.last.days).toBe(399)
    expect(s.min).toBe(12)
    expect(s.max).toBe(400)
    expect(s.failed).toBe(2)
    expect(s.bucketMs).toBe(H)
    expect(s.domain).toEqual([Date.parse('2026-09-20T00:00:00Z'), Date.parse('2026-09-20T12:00:00Z')])
  })

  it('boş/bozuk zarf: nokta yok, uçlar null', () => {
    const s = shapeDaysSeries({})
    expect(s.points).toEqual([])
    expect(s.first).toBeNull()
    expect(s.renewals).toEqual([])
    expect(shapeDaysSeries(null).failed).toBe(0)
  })

  it('daysDomain veri aralığına pay ekler; negatif (süresi dolmuş) değeri kapsar', () => {
    expect(daysDomain(100, 120)).toEqual([97, 123])
    expect(daysDomain(-6, 10)).toEqual([-9, 13])
    expect(daysDomain(null, null)).toEqual([0, 1])
  })
})

describe('certHistoryModel — kutucuk değerleri ve istek parametreleri', () => {
  const items = [
    { status: 'error', days_remaining: null, checked_at: '2026-09-20T10:00:00' },
    { status: 'warning', warning: true, days_remaining: 21, not_after: '2026-10-11T00:00:00', checked_at: '2026-09-20T09:00:00' },
  ]
  const shape = { last: { days: 20, ts: '2026-09-20T10:00:00' } }

  it('latestDays: 1. sayfa + süzgeçsiz → satırdan KESİN değer (başarısız en yeni atlanır); aksi hâlde seri', () => {
    expect(latestDays({ items, status: 'all', page: 1 }, shape)).toEqual({
      days: 21, notAfter: '2026-10-11T00:00:00', warning: true, at: '2026-09-20T09:00:00', exact: true })
    expect(latestDays({ items, status: 'fail', page: 1 }, shape)).toMatchObject({ days: 20, exact: false })
    expect(latestDays({ items, status: 'all', page: 2 }, shape)).toMatchObject({ days: 20, exact: false })
    expect(latestDays({ items: [], status: 'all', page: 1 }, null)).toBeNull()
  })

  it('successRate + formatRate', () => {
    expect(successRate({ total: 200, fail: 1 })).toBeCloseTo(99.5)
    expect(successRate({ total: 0, fail: 0 })).toBeNull()
    expect(formatRate(99.5)).toBe('99.50%')
    expect(formatRate(99.999)).toBe('100%')
    expect(formatRate(null)).toBe('—')
  })

  it('formatRate yerel (E5, 2026-09-28e): TR "%97,50" / "%100", EN "97.50%" — ondalık ayırıcı ve yüzde sırası dile göre', () => {
    try {
      setDateLocale('tr')
      expect(formatRate(97.5)).toBe('%97,50')
      expect(formatRate(99.999)).toBe('%100')
      expect(formatRate(null)).toBe('—')
      setDateLocale('en')
      expect(formatRate(97.5)).toBe('97.50%')
    } finally {
      setDateLocale('en')
    }
  })

  it('rangeParams: ön ayar → days; uygulanmamış özel aralık → null (istek atılmaz); özel → from/to UTC', () => {
    expect(rangeParams({ preset: 7 })).toEqual({ days: 7 })
    expect(rangeParams({ preset: 'custom', customFrom: null, customTo: null })).toBeNull()
    const f = new Date(Date.UTC(2026, 8, 20, 1, 0, 0)), to = new Date(Date.UTC(2026, 8, 20, 4, 0, 0))
    expect(rangeParams({ preset: 'custom', customFrom: f, customTo: to })).toEqual({ from: '2026-09-20T01:00:00', to: '2026-09-20T04:00:00' })
    expect(paramsKey({ days: 7 })).toBe('d7')
    expect(paramsKey({ from: 'a', to: 'b' })).toBe('a~b')
    expect(paramsKey(null)).toBe('')
  })

  it('rangeParams: kontrollü aralık (Uptime tek seçici) ön ayarı YOK SAYAR — geçmiş isteğiyle aynı from/to; bozuk uç → null', () => {
    const f = new Date(Date.UTC(2026, 8, 20, 0, 0, 0)), to = new Date(Date.UTC(2026, 8, 20, 9, 30, 0))
    expect(rangeParams({ preset: 7, fixedRange: { from: f, to } })).toEqual({ from: '2026-09-20T00:00:00', to: '2026-09-20T09:30:00' })
    expect(rangeParams({ preset: 'custom', customFrom: null, fixedRange: { from: f, to } })).toEqual({ from: '2026-09-20T00:00:00', to: '2026-09-20T09:30:00' })
    expect(rangeParams({ preset: 7, fixedRange: { from: null, to } })).toBeNull()
    expect(rangeParams({ preset: 7, fixedRange: { from: new Date('x'), to } })).toBeNull()
    expect(rangeParams({ preset: 7, fixedRange: null })).toEqual({ days: 7 })
  })

  it('renewalWindow: kova + iki yanında bir kova payı; günlük kovada pay 6 sa ile sınırlı (tek sayfa)', () => {
    const [a, b] = renewalWindow({ t: 10 * H }, H)
    expect(a.getTime()).toBe(9 * H)
    expect(b.getTime()).toBe(12 * H)
    const [c, d] = renewalWindow({ t: 10 * BUCKET_MS.day }, BUCKET_MS.day)
    expect(c.getTime()).toBe(10 * BUCKET_MS.day - 6 * H)
    expect(d.getTime()).toBe(11 * BUCKET_MS.day + 6 * H)
    expect(renewalWindow(null)).toBeNull()
  })

  it('renewalWindow seyrek kontrolde önceki ölçümü kapsar (E3): 24 sa aralıklı kayıtta pencere önceki kovadan başlar; en çok 7 gün geri', () => {
    // Saatlik kova, kontrol her 24 saatte bir: yenileme 48. saatte, önceki değerli kova 24. saatte.
    const pts = [{ t: 0, ts: 'a', days: 12 }, { t: 24 * H, ts: 'b', days: 11 }, { t: 48 * H, ts: 'c', days: 376 }]
    const [r] = detectRenewals(pts)
    expect(r).toMatchObject({ t: 48 * H, prevT: 24 * H })
    const [a, b] = renewalWindow(r, H)
    expect(a.getTime()).toBe(24 * H)          // önceki kontrol pencerede → satırın "Yenilendi" rozeti aynı sayfada
    expect(b.getTime()).toBe(50 * H)
    // Önceki ölçüm çok eski (uzun hata dönemi) → pencere 7 günle sınırlı
    const [c] = renewalWindow({ t: 30 * BUCKET_MS.day, prevT: 10 * BUCKET_MS.day }, H)
    expect(c.getTime()).toBe(23 * BUCKET_MS.day)
    // Sık kontrolde (önceki kova zaten payın içinde) pencere eskisi gibi
    const [d] = renewalWindow({ t: 10 * H, prevT: 9 * H }, H)
    expect(d.getTime()).toBe(9 * H)
  })

  it('techLine: TLS + süre; ikisi de yoksa boş', () => {
    expect(techLine({ tls_version: 'TLSv1.3', response_ms: 412 })).toBe('TLSv1.3 · 412 ms')
    expect(techLine({})).toBe('')
  })
})
