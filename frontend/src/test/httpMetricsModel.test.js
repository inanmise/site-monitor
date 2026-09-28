import { describe, it, expect } from 'vitest'
import {
  normOverview, normEndpoint, filterEndpoints, sortEndpoints, endpointCsv, breaches, errTone, msTone, sectionModel,
  widerRange, isLiveRange, countForClasses, splitEndpoint, classOf, activeFilterCount, makeMs, fmtPct, downsample, clockLabel,
} from '../components/admin/httpmetrics/httpMetricsModel.js'
import { setDateLocale } from '../i18n/dateLocale.js'

/**
 * İstek Gezgini / HTTP istekleri bölümü SAF modeli (2026-09-28). Fixture'lar GERÇEK tel biçiminde (snake_case) —
 * camelCase fixture var olmayan bir sözleşmeyi doğrular (cert-monitor-wire-format-snake-case).
 */
const t = (k, ...a) => (a.length ? `${k}(${a.join('|')})` : k)

const EP = (endpoint, extra = {}) => ({
  endpoint, method: endpoint.split(' ')[0], path: endpoint.split(' ').slice(1).join(' '),
  count: 10, errors: 0, error_rate_pct: 0, avg_ms: 40, max_ms: 90, p50_ms: 30, p95_ms: 80, p99_ms: 88,
  status_2xx: 10, status_3xx: 0, status_4xx: 0, status_5xx: 0, status_other: 0, unclassified: 0,
  last_seen: '2026-09-28T10:00:00', ...extra,
})

describe('normOverview / normEndpoint — tel biçimi → model', () => {
  it('snake_case alanları okur; sınıf sayıları + sınıfsız; noktalar t (epoch) ile sıralı', () => {
    const m = normOverview({
      granularity: 'hour', capped: false, clamped: true, endpoints_total: 2, endpoints_truncated: false,
      summary: { total: 30, errors: 3, error_rate_pct: 10, avg_ms: 55, max_ms: 900, min_ms: 2, p50_ms: 40, p95_ms: 300, p99_ms: 800,
        req_per_min: 0.5, status_2xx: 25, status_3xx: 1, status_4xx: 2, status_5xx: 1, status_other: 0, unclassified: 1 },
      status_codes: [{ code: 404, count: 2 }, { code: 200, count: 25 }, { code: 0, count: 0 }],
      data: [
        { ts: '2026-09-28T13:00:00', t: 2000, count: 0, errors: 0, avg_ms: null, p95_ms: null },
        { ts: '2026-09-28T12:00:00', t: 1000, count: 30, errors: 3, avg_ms: 55, p95_ms: 300, status_2xx: 25, status_4xx: 2, status_5xx: 1, status_3xx: 1 },
      ],
      endpoints: [EP('GET /api/x'), { bogus: true }, EP('(overflow)', { method: '', path: '(overflow)' })],
    })
    expect(m.granularity).toBe('hour')
    expect(m.clamped).toBe(true)
    expect(m.summary).toMatchObject({ total: 30, errors: 3, errorRate: 10, p95: 300, reqPerMin: 0.5, unclassified: 1 })
    expect(m.summary.classes).toMatchObject({ '2xx': 25, '3xx': 1, '4xx': 2, '5xx': 1 })
    expect(m.codes.map((c) => c.code)).toEqual([200, 404])        // sıfır adet atıldı, adete göre azalan
    expect(m.points.map((p) => p.t)).toEqual([1000, 2000])
    expect(m.points[0]).toMatchObject({ count: 30, '2xx': 25, '5xx': 1, unclassified: 1 })   // 30 − 29 sınıflı
    expect(m.points[1].avg).toBeNull()
    expect(m.endpoints.map((e) => e.endpoint)).toEqual(['GET /api/x', '(overflow)'])   // bozuk öğe düştü
  })

  it('eski sunucu (sınıf alanı yok): tüm istekler "sınıfsız"; t yoksa ts yerel okunur; boş/bozuk yanıt boş model', () => {
    const p = normOverview({ data: [{ ts: '2026-09-28T12:00:00', count: 4, errors: 1, avg_ms: 10 }] }).points[0]
    expect(p.unclassified).toBe(4)
    expect(p.t).toBe(Date.parse('2026-09-28T12:00:00'))
    expect(normOverview(null)).toMatchObject({ points: [], endpoints: [], codes: [] })
    expect(normOverview([]).summary.total).toBe(0)
    expect(normEndpoint({ endpoint: 'POST /api/y', count: 3 })).toMatchObject({ method: 'POST', path: '/api/y', unclassified: 3 })
  })

  it('splitEndpoint / classOf', () => {
    expect(splitEndpoint('DELETE /api/x/{id}')).toEqual({ method: 'DELETE', path: '/api/x/{id}' })
    expect(splitEndpoint('(overflow)')).toEqual({ method: '', path: '(overflow)' })
    expect([204, 301, 429, 503, 101, 0].map(classOf)).toEqual(['2xx', '3xx', '4xx', '5xx', 'other', 'other'])
  })
})

describe('süzme / sıralama', () => {
  const list = [
    normEndpoint(EP('GET /api/a', { count: 5, errors: 1, error_rate_pct: 20, p95_ms: 2000, status_2xx: 4, status_5xx: 1 })),
    normEndpoint(EP('POST /api/b', { count: 50, p95_ms: null, last_seen: null })),
    normEndpoint(EP('GET /api/c', { count: 50, p95_ms: 10, status_2xx: 48, status_4xx: 2 })),
  ]

  it('uç / yöntem / durum sınıfı / arama birlikte', () => {
    expect(filterEndpoints(list, { methods: ['GET'] }).map((e) => e.endpoint)).toEqual(['GET /api/a', 'GET /api/c'])
    expect(filterEndpoints(list, { classes: ['5xx'] }).map((e) => e.endpoint)).toEqual(['GET /api/a'])
    expect(filterEndpoints(list, { classes: ['4xx', '5xx'] }).map((e) => e.endpoint)).toEqual(['GET /api/a', 'GET /api/c'])
    expect(filterEndpoints(list, { q: '  API/B ' }).map((e) => e.endpoint)).toEqual(['POST /api/b'])
    expect(filterEndpoints(list, { endpoint: 'GET /api/c', methods: ['POST'] })).toEqual([])
  })

  it('sıralama: yön, eşitlikte yol A→Z, boş değer HER İKİ yönde en sonda', () => {
    expect(sortEndpoints(list, { key: 'count', dir: 'desc' }).map((e) => e.endpoint)).toEqual(['GET /api/c', 'POST /api/b', 'GET /api/a'])
    expect(sortEndpoints(list, { key: 'p95', dir: 'desc' }).map((e) => e.endpoint)).toEqual(['GET /api/a', 'GET /api/c', 'POST /api/b'])
    expect(sortEndpoints(list, { key: 'p95', dir: 'asc' }).map((e) => e.endpoint)).toEqual(['GET /api/c', 'GET /api/a', 'POST /api/b'])
    expect(sortEndpoints(list, { key: 'lastSeen', dir: 'desc' }).at(-1).endpoint).toBe('POST /api/b')
    expect(sortEndpoints(list, { key: 'endpoint', dir: 'asc' }).map((e) => e.path)).toEqual(['/api/a', '/api/b', '/api/c'])
    expect(sortEndpoints(list, { key: 'nonsense' })[0].count).toBe(50)
  })

  it('durum süzgecinde sayılan istek = seçili sınıfların toplamı', () => {
    expect(countForClasses(list[0], ['5xx'])).toBe(1)
    expect(countForClasses(list[0], [])).toBe(5)
    expect(activeFilterCount({ endpoint: 'x', methods: ['GET', 'POST'], classes: ['5xx'] })).toBe(4)
  })
})

describe('CSV', () => {
  it('BOM + başlık + görünen satırlar; formül nötrleme; son görülme UTC Z', () => {
    const rows = [normEndpoint(EP('GET /api/x', { count: 1234, errors: 2, error_rate_pct: 0.2 })),
      normEndpoint({ ...EP('POST =HYPERLINK("x")'), method: 'POST', path: '=HYPERLINK("x")' })]
    const csv = endpointCsv(rows, t)
    expect(csv.charCodeAt(0)).toBe(0xfeff)
    const lines = csv.slice(1).split('\r\n')
    expect(lines[0].split(',').slice(0, 3)).toEqual(['hreq.csv.method', 'hreq.csv.path', 'hreq.col.requests'])
    expect(lines[1]).toBe('GET,/api/x,1234,2,0.2,40,30,80,88,90,10,0,0,0,2026-09-28T10:00:00Z')
    expect(lines[2]).toContain(`"'=HYPERLINK(""x"")"`)   // hücre formül olarak çalışmaz
    expect(lines).toHaveLength(3)
  })
})

describe('tonlar / eşikler / biçim', () => {
  it('hata oranı %1 uyarı, %5 kritik; veri yoksa nötr', () => {
    expect([0.5, 1, 4.9, 5].map((v) => errTone(v, 10))).toEqual(['ok', 'warn', 'warn', 'crit'])
    expect(errTone(0, 0)).toBe('neutral')
  })
  it('süre 1 sn uyarı, 3 sn kritik', () => {
    expect([null, 999, 1000, 3000].map(msTone)).toEqual(['neutral', 'neutral', 'warn', 'crit'])
  })
  it('breaches: uyarı ve kritik noktalar ayrı sayılır (MiniChart "N ihlal" ile aynı)', () => {
    expect(breaches([0, 1, 4, 5, 9, null], { warn: 1, crit: 5 })).toEqual({ warn: 2, crit: 2, total: 4, tone: 'crit' })
    expect(breaches([0, 0], { warn: 1, crit: 5 }).tone).toBe('ok')
  })
  it('süre biçimi ms / s, yüzde yerel', () => {
    const f = makeMs({ ms: 'ms', sec: 's', locale: 'en-GB' })
    expect(f.value(212.4)).toBe('212 ms')
    expect(f.value(1240)).toBe('1.24 s')
    expect(f.value(null)).toBe('—')
    expect(fmtPct(0.84, 'en-GB')).toMatch(/0\.8/)
  })
})

describe('aralık', () => {
  it('canlı yalnız ≤ 24 sa göreli; daha geniş aralık önerisi sıradaki hızlı aralık', () => {
    expect(isLiveRange({ type: 'rel', minutes: 60, key: '1h' })).toBe(true)
    expect(isLiveRange({ type: 'rel', minutes: 10080, key: '7d' })).toBe(false)
    expect(isLiveRange({ type: 'abs', from: '2026-09-28T10:00', to: '2026-09-28T11:00' })).toBe(false)
    expect(widerRange({ type: 'rel', minutes: 60, key: '1h' })).toMatchObject({ key: '3h', minutes: 180 })
    expect(widerRange({ type: 'rel', minutes: 10080, key: '7d' })).toBeNull()
  })
})

describe('downsample (bölüm küçük grafikleri)', () => {
  const minutes = Array.from({ length: 1440 }, (_, i) => ({
    t: i * 60_000, count: 10, ok: i === 7 ? 3 : 10, errors: i === 7 ? 7 : 0, avg: i === 7 ? null : 100, p95: i === 7 ? null : (i === 5 ? 900 : 200),
  }))

  it('1440 dakika → 240 dilim (6 dk); hacim dakika ortalaması, hata dilimin EN YOĞUN dakikası, p95 dilimin en yükseği', () => {
    const d = downsample(minutes)
    expect(d).toHaveLength(240)
    expect(d[0]).toMatchObject({ t: 0, span: 6 * 60_000, count: 10, p95: 900, errPeak: 0 })
    expect(d[1]).toMatchObject({ t: 6 * 60_000, errPeak: 7, errors: 1.2, ok: 8.8, avg: 100 })   // 7 hata tek dakikada
    expect(d[1].ok + d[1].errors).toBeCloseTo(d[1].count, 5)                                     // yığın toplamı = hacim
  })

  it('az noktada seyreltme yok (dilim = 1 dk, errPeak = errors)', () => {
    const d = downsample(minutes.slice(0, 12))
    expect(d).toHaveLength(12)
    expect(d[7]).toMatchObject({ span: 60_000, errPeak: 7, errors: 7 })
    expect(downsample([])).toEqual([])
  })
})

describe('sectionModel (bellek içi 24 sa)', () => {
  it('özet + dakikalık noktalar (UTC Z\'siz ts) + en yavaş / en çok hata listeleri', () => {
    const m = sectionModel({
      summary: { total_requests: 100, total_errors: 4, error_rate_pct: 4, avg_ms: 120, max_ms: 5000, p95_ms: 400, p99_ms: 2000,
        req_per_min: 12.5, peak_req_per_min: 40 },
      history: [{ ts: '2026-09-28T10:01:00', count: 10, errors: 2, avg_ms: 80, p95_ms: 150 },
        { ts: '2026-09-28T10:00:00', count: 0, errors: 0, avg_ms: 0 }],
      top_endpoints: { slowest: [EP('GET /api/slow', { p95_ms: 2400 })], errors: [] },
    })
    expect(m.summary).toMatchObject({ total: 100, errorRate: 4, p95: 400, reqPerMin: 12.5, peak: 40 })
    expect(m.points.map((p) => p.t)).toEqual([Date.parse('2026-09-28T10:00:00Z'), Date.parse('2026-09-28T10:01:00Z')])
    expect(m.points[0].avg).toBeNull()                       // boş dakika: süre çizgisi kopar
    expect(m.points[1]).toMatchObject({ ok: 8, errors: 2, p95: 150 })
    expect(m.top.slowest[0].p95).toBe(2400)
    expect(sectionModel({ summary: {}, history: [] }).top).toBeNull()   // eski sunucu: liste yok
  })
})

describe('2026-09-28c düzeltmeleri', () => {
  it('ek-2: normOverview sunucunun (kırpılmış) from/to değerini taşır; yoksa null', () => {
    const m = normOverview({ from: '2026-05-18T00:00:00', to: '2026-06-18T00:00:00', clamped: true, summary: {}, data: [], endpoints: [] })
    expect(m).toMatchObject({ from: '2026-05-18T00:00:00', to: '2026-06-18T00:00:00', clamped: true })
    expect(normOverview({}).from).toBeNull()
    expect(normOverview({ from: 5 }).from).toBeNull()
  })

  it('ek-8: saat etiketi UYGULAMA yereliyle 24 saat (tarayıcı yereli değil)', () => {
    const ms = new Date(2026, 8, 28, 14, 5).getTime()   // yerel 14:05
    expect(clockLabel(ms, 'en-GB')).toBe('14:05')
    expect(clockLabel(ms, 'tr-TR')).toBe('14:05')
    try {
      setDateLocale('en')
      expect(clockLabel(ms)).toBe('14:05')             // EN arayüz = en-GB → 24 saat ("2:05 PM" değil)
    } finally {
      setDateLocale('en')
    }
  })
})
