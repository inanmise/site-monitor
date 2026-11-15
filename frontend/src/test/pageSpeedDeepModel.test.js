import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  WF_TYPES, wfType, buildWaterfall, niceTicks, barBox, typeTotals, CMP_METRICS, compareSnapshots, GRADE_COMPONENTS, GRADE_RECS,
  gradeLetterOf, curveScore, gradeSnapshot, imageItemOf, imageScopeKey, withDeadline, DEADLINE,
} from '../components/pagespeed/deep/pageSpeedDeepModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/** Sayfa Hızı 2. sürüm (2026-11-15): şelale / karşılaştırma / hız notu modeli — saf, doğruluk tabloları. */

const KB = 1024
const MB = 1024 * 1024

function snap(over = {}, resources = []) {
  return {
    check_id: 7, has_start: true, total: resources.length, truncated: false,
    check: { checked_at: '2026-11-15T10:00:00', ttfb_ms: 120, html_ms: 300, response_ms: 900, total_bytes: 900 * KB,
      html_bytes: 40 * KB, request_count: 20, failed_count: 0, dns_ms: 10, connect_ms: 20, tls_ms: 30, server_ms: 90, ...over },
    resources,
  }
}
const res = (url, type, start, dur, bytes, extra = {}) => ({ url, type, start_ms: start, duration_ms: dur, bytes, ...extra })

describe('wfType', () => {
  it.each([
    ['CSS', 'css'], ['js', 'js'], ['IMG', 'img'], ['FAVICON', 'img'], ['FONT', 'font'], ['IFRAME', 'other'], ['OTHER', 'other'],
    [null, 'other'], ['', 'other'],
  ])('%s → %s', (raw, out) => expect(wfType(raw)).toBe(out))
  it('türler sabit sırada', () => expect([...WF_TYPES]).toEqual(['html', 'css', 'js', 'img', 'font', 'other']))
})

describe('buildWaterfall', () => {
  it('kontrol kimliği yoksa / boşsa "none"', () => {
    expect(buildWaterfall(null).reason).toBe('none')
    expect(buildWaterfall({ check_id: null, resources: [] }).reason).toBe('none')
    expect(buildWaterfall({ check_id: 1, resources: [], check: null }).reason).toBe('none')
  })

  it('başlangıç anı olmayan eski ölçüm "no_start" (HTML fazları korunur, satır yok)', () => {
    const wf = buildWaterfall({ ...snap({}, [res('https://a/x.css', 'CSS', null, 40, 100)]), has_start: false })
    expect(wf.reason).toBe('no_start')
    expect(wf.rows).toEqual([])
    expect(wf.html.end).toBe(300)
    expect(wf.unknownStart).toBe(1)
  })

  it('satırlar başlangıca göre sıralı; HTML çubuğu 0 → html_ms, ilk bayt içinde; fazlar ayrı', () => {
    const wf = buildWaterfall(snap({}, [
      res('https://a/late.js', 'JS', 500, 100, 2000),
      res('https://a/early.css', 'CSS', 310, 50, 1000),
      res('https://a/u.png', 'IMG', null, 10, 5),
    ]))
    expect(wf.ok).toBe(true)
    expect(wf.rows.map((r) => r.url)).toEqual(['https://a/early.css', 'https://a/late.js'])
    expect(wf.rows[0]).toMatchObject({ type: 'css', start: 310, end: 360, duration: 50 })
    expect(wf.html).toMatchObject({ start: 0, end: 300, ttfb: 120, bytes: 40 * KB })
    expect(wf.html.phases.map((p) => p.key)).toEqual(['dns', 'connect', 'tls', 'server'])
    expect(wf.totalMs).toBe(600)
    expect(wf.unknownStart).toBe(1)
    expect(wf.shown).toBe(2)
  })

  it('ilk bayt HTML süresinden büyükse çizilmez (tutarsız ölçüm)', () => {
    expect(buildWaterfall(snap({ ttfb_ms: 900, html_ms: 300 }, [res('https://a/x', 'CSS', 0, 1, 1)])).html.ttfb).toBeNull()
  })

  it('satır sınırı: fazlası kesilir ve "truncated"', () => {
    const many = Array.from({ length: 12 }, (_, i) => res(`https://a/${i}.js`, 'JS', i * 10, 5, 1))
    const wf = buildWaterfall(snap({}, many), 5)
    expect(wf.shown).toBe(5)
    expect(wf.truncated).toBe(true)
  })

  it('negatif süre / başlangıç sıfıra çekilir', () => {
    const wf = buildWaterfall(snap({}, [res('https://a/x.js', 'JS', -5, -10, 1)]))
    expect(wf.rows[0]).toMatchObject({ start: 0, duration: 0, end: 0 })
  })
})

describe('niceTicks / barBox', () => {
  it.each([[1000, [0, 200, 400, 600, 800, 1000]], [600, [0, 200, 400, 600]], [1, [0, 1]]])('niceTicks(%s)', (t, out) => {
    expect(niceTicks(t)).toEqual(out)
  })
  it('en çok 12 işaret (sonsuz döngü yok)', () => {
    for (const t of [0, -5, NaN, 1e12, 3.3]) expect(niceTicks(t).length).toBeLessThanOrEqual(12)
  })
  it('barBox sınırlar içinde, en az 0,4 %', () => {
    expect(barBox(0, 50, 100)).toEqual({ left: 0, width: 50 })
    expect(barBox(100, 100, 100).width).toBeGreaterThanOrEqual(0)
    expect(barBox(10, 10, 1000).width).toBe(0.4)
    expect(barBox(-10, 2000, 1000)).toEqual({ left: 0, width: 100 })
  })
})

describe('typeTotals', () => {
  it('bayt bilinmeyen satır sayıya girer, bayta girmez', () => {
    const tt = typeTotals(snap({}, [res('a', 'IMG', 0, 1, 100), res('b', 'IMG', 0, 1, null), res('c', 'FONT', 0, 1, 7)]))
    expect(tt.img).toEqual({ bytes: 100, count: 2 })
    expect(tt.font).toEqual({ bytes: 7, count: 1 })
    expect(tt.html).toEqual({ bytes: 40 * KB, count: 1 })
  })
})

describe('compareSnapshots', () => {
  const a = snap({ response_ms: 1000, total_bytes: 1 * MB }, [
    res('https://a/app.js', 'JS', 0, 300, 100 * KB),
    res('https://a/hero.jpg', 'IMG', 0, 100, 200 * KB),
    res('https://a/old.css', 'CSS', 0, 50, 10 * KB),
  ])
  const b = snap({ response_ms: 1500, total_bytes: 1 * MB }, [
    res('https://a/app.js', 'JS', 0, 900, 104 * KB),        // %4 büyüme: büyüyen DEĞİL; 600 ms yavaşlama
    res('https://a/hero.jpg', 'IMG', 0, 100, 400 * KB),     // büyüyen
    res('https://a/new.woff2', 'FONT', 0, 30, 50 * KB),     // eklenen
  ])

  it('satır başına fark, yüzde ve yön (küçük olan iyi; eşitlik kötü değil)', () => {
    const c = compareSnapshots(a, b)
    expect(c.rows.map((r) => r.key)).toEqual([...CMP_METRICS])
    const load = c.rows.find((r) => r.key === 'load')
    expect(load).toMatchObject({ a: 1000, b: 1500, delta: 500, pct: 50, worse: true })
    expect(c.rows.find((r) => r.key === 'bytes')).toMatchObject({ delta: 0, worse: false })
  })

  it('kaynak farkları: eklenen, kalkan, büyüyen (≥ %10 ve ≥ 5 KB), yavaşlayan (≥ %50 ve ≥ 200 ms)', () => {
    const c = compareSnapshots(a, b)
    expect(c.added.items.map((x) => x.url)).toEqual(['https://a/new.woff2'])
    expect(c.removed.items.map((x) => x.url)).toEqual(['https://a/old.css'])
    expect(c.grown.items.map((x) => x.url)).toEqual(['https://a/hero.jpg'])
    expect(c.slower.items.map((x) => x.url)).toEqual(['https://a/app.js'])
    expect(c.resourcesKnown).toBe(true)
  })

  it('bilinmeyen değerde yön yok; kaynak saklanmamışsa resourcesKnown=false; kesik ölçüm partial', () => {
    const c = compareSnapshots(snap({ ttfb_ms: null }), { ...snap({}), truncated: true })
    expect(c.rows.find((r) => r.key === 'ttfb')).toMatchObject({ delta: null, worse: null })
    expect(c.rows.find((r) => r.key === 'img').a).toBeNull()
    expect(c.resourcesKnown).toBe(false)
    expect(c.partial).toBe(true)
  })
})

describe('hız notu', () => {
  it('harf eşikleri', () => {
    expect([95, 90, 85, 75, 65, 55, 10, null].map(gradeLetterOf)).toEqual(['A', 'A', 'B', 'C', 'D', 'E', 'F', null])
  })
  it('eğri enterpolasyonu ve uçlar', () => {
    const c = [[100, 100], [200, 50], [300, 0]]
    expect(curveScore(50, c)).toBe(100)
    expect(curveScore(150, c)).toBe(75)
    expect(curveScore(999, c)).toBe(0)
    expect(curveScore(null, c)).toBeNull()
  })
  it('ağırlıklar 100 eder', () => expect(GRADE_COMPONENTS.reduce((n, g) => n + g.weight, 0)).toBe(100))

  it('temiz, hızlı sayfa: A ve ALL_GOOD', () => {
    const g = gradeSnapshot(snap({ response_ms: 800, ttfb_ms: 100 }, [res('https://a/x.css', 'CSS', 0, 50, 10 * KB)]))
    expect(g.letter).toBe('A')
    expect(g.partial).toBe(false)
    expect(g.recs.map((r) => r.code)).toEqual(['ALL_GOOD'])
  })

  it('yük süresi yoksa not yok; kesik ölçüm partial ve ALL_GOOD verilmez', () => {
    expect(gradeSnapshot(snap({ response_ms: null })).score).toBeNull()
    const g = gradeSnapshot(snap({ time_truncated: true }, [res('https://a/x.css', 'CSS', 0, 50, 1)]))
    expect(g.partial).toBe(true)
    expect(g.recs.some((r) => r.code === 'ALL_GOOD')).toBe(false)
  })

  it('kaynak okunmadıysa (boş liste) ALL_GOOD yok — bilinmeyen ≠ temiz', () => {
    expect(gradeSnapshot(snap({ response_ms: 500 }, [])).recs).toEqual([])
  })

  it('öneriler önem sırasında ve adresleri ≤ 5', () => {
    const imgs = Array.from({ length: 7 }, (_, i) => res(`https://a/big${i}.jpg`, 'IMG', 0, 1200, 300 * KB))
    const g = gradeSnapshot(snap({ response_ms: 6000, ttfb_ms: 900, total_bytes: 3 * MB, request_count: 120, failed_count: 2 }, [
      ...imgs, res('https://a/bad.js', 'JS', 0, 10, 600 * KB, { failed: true }),
    ]))
    const codes = g.recs.map((r) => r.code)
    expect(codes).toEqual(['FAILED_RESOURCES', 'SLOW_TTFB', 'HEAVY_PAGE', 'LARGE_IMAGES', 'HEAVY_JS', 'TOO_MANY_REQUESTS', 'SLOW_RESOURCES'])
    expect(codes.every((c) => GRADE_RECS.includes(c))).toBe(true)
    expect(g.recs.find((r) => r.code === 'LARGE_IMAGES').urls).toHaveLength(5)
    expect(g.letter).not.toBe('A')
  })

  it('her öneri / bileşen / metrik / tür için TR ve EN metni var', () => {
    const keys = [
      ...GRADE_RECS.map((c) => `pspd.grade.rec.${c}`), ...GRADE_COMPONENTS.map((g) => `pspd.grade.comp.${g.key}`),
      ...CMP_METRICS.map((m) => `pspd.cmp.m.${m}`), ...WF_TYPES.map((k) => `pspd.wf.type.${k}`),
      ...['added', 'removed', 'grown', 'slower'].map((k) => `pspd.cmp.list.${k}`),
    ]
    for (const d of [TR, EN]) for (const k of keys) expect(d[k], k).toBeTruthy()
  })

  it('PDF\'e giden metinlerde gömülü yazı tipinde olmayan ok / büyük-eşit işareti yok', () => {
    const pdfKeys = Object.keys(TR).filter((k) => /^pspd\.(rep|grade)\./.test(k) || /^pspd\.cmp\.m\./.test(k) || /^pspd\.wf\.(type|first|third|start|duration|status|party)/.test(k))
    for (const d of [TR, EN]) for (const k of pdfKeys) expect(d[k], k).not.toMatch(/[→≥≤−]/)
  })
})

describe('görsel aracı köprüsü', () => {
  it('yalnız IMG satırı; alınamayan "broken"', () => {
    expect(imageItemOf({ url: 'https://a/x.png', type: 'IMG', bytes: 10, http_status: 200 }))
      .toMatchObject({ url: 'https://a/x.png', category: 'IMAGE', bytes: 10, verdict: 'ok' })
    expect(imageItemOf({ url: 'https://a/x.png', type: 'IMG', failed: true }).verdict).toBe('broken')
    expect(imageItemOf({ url: 'https://a/f.ico', type: 'FAVICON' })).toBeNull()
    expect(imageItemOf({ url: 'https://a/x.css', type: 'CSS' })).toBeNull()
    expect(imageItemOf({ type: 'IMG' })).toBeNull()
  })
  it('depo anahtarı ayrı ad alanı', () => {
    expect(imageScopeKey(42)).toBe('ps-42')
    expect(imageScopeKey(null)).toBe('ps-latest')
  })
})

describe('withDeadline', () => {
  afterEach(() => vi.useRealTimers())
  it('süre dolunca DEADLINE, söz bitince değeri; zamanlayıcı temizlenir', async () => {
    vi.useFakeTimers()
    const never = new Promise(() => {})
    const p = withDeadline(never, 1000)
    vi.advanceTimersByTime(1000)
    await expect(p).resolves.toBe(DEADLINE)
    await expect(withDeadline(Promise.resolve(5), 1000)).resolves.toBe(5)
    expect(vi.getTimerCount()).toBe(0)
  })
  it('ret aynen geçer', async () => {
    await expect(withDeadline(Promise.reject(new Error('x')), 1000)).rejects.toThrow('x')
  })
})
