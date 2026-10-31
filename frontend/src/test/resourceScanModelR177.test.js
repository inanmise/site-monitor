import { describe, it, expect, vi, afterEach } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  DEFAULT_FILTERS, FINDING_CODES, GRADE_TICKS, KB, MB, URL_OTHER, URL_SCAN, URL_VIEW, VIEWS, algorithmVersion, categoryRows,
  categorySheetRows, compareScans, comparableScans, countsBy, elapsedAtSighting, filterItems, filtersForNewScan, findingLabel,
  foundInText, isCancelled, isFailed, isUnverified, latestDoneId, lostPoints, monotonicPct, niceByteTicks, niceMsTicks, objectSheetRows,
  optimizedImage, parseUrlState, recFiltersByFinding, recItemCount, resourceScanLink, scoreRows, scoreSheetRows, scoreTarget,
  scoreTargetText, sectionOfView, statusText, summarySheetRows, thirdPartyRows, topLosses, urlStateValues, verdictOf, verdictText,
  verdictTone, viewOf, waterfallRows, waterfallSegments, withFindings,
} from '../components/page/resourcescan/resourceScanModel.js'
import { forgetStarted, handOff, isWatching, markStartedHere, resetWatchers, startedHere, takeBack } from '../components/page/resourcescan/resourceScanWatcher.js'
import { scanDetail, scanDetailV2, scanItems, scanItemsV2 } from './helpers/resourceScanFixtures.js'

// Kaynak analizi — r177 (inceleme sonrası) model kuralları: bulgular (sunucu `findings` / eski kayıtta sunucu kuralıyla
// istemci), karar (verdict), süzgeçler, kategori çubuğu ölçeği, kayıp puan + hedef, yuvarlak eksenler, şelale,
// karşılaştırma, URL durumu, geçen süre, ilerleme, üçüncü taraflar, dışa aktarım sütunları ve bitiş bildirimi izleyicisi.

const t = (k, ...a) => `${k}${a.length ? `(${a.join('|')})` : ''}`
const tr = (k, ...a) => String(TR[k] ?? k).replace(/\{(\d)\}/g, (_, i) => String(a[Number(i)] ?? ''))
const TRL = 'tr-TR'

const base = { status: 200, page_load: true, third_party: false, bytes: 50 * KB, duration_ms: 100, start_ms: 10, redirects: 0 }

describe('bulgular — eski kayıtta sunucu kuralıyla (r177 H2)', () => {
  it('önbellek bulgusu YALNIZ birinci taraf, 2xx, durağan türde ve 1 sa altı; HTML / 3. taraf / 404 / sayfa dışı asla', () => {
    const rows = withFindings([
      { ...base, url: 'h', category: 'HTML', cache_ttl_s: null },
      { ...base, url: 'a.js', category: 'JS', cache_ttl_s: null },
      { ...base, url: 'b.js', category: 'JS', cache_ttl_s: 60 },          // 1 dk → önbelleksiz (< 1 sa)
      { ...base, url: 'c.js', category: 'JS', cache_ttl_s: 3600 },        // tam 1 sa → değil
      { ...base, url: 'd.js', category: 'JS', cache_ttl_s: -1 },          // no-store → önbelleksiz
      { ...base, url: 'e.js', category: 'JS', cache_ttl_s: null, third_party: true },
      { ...base, url: 'f.png', category: 'IMAGE', cache_ttl_s: null, status: 404 },
      { ...base, url: 'g.pdf', category: 'DOCUMENT', cache_ttl_s: null, page_load: false },
      { ...base, url: 'i.png', category: 'IMAGE', cache_ttl_s: null, status: 304 },
    ], [])
    expect(rows.filter((r) => r.findings.includes('NO_CACHE')).map((r) => r.url)).toEqual(['a.js', 'b.js', 'd.js'])
    expect(rows.every((r) => r.findings_source === 'client')).toBe(true)
  })

  it('sıkıştırma bulgusu: birinci taraf, ≤ 1 KB değil, metin türü (SVG / JSON / XML dahil), sıkıştırılmamış', () => {
    const rows = withFindings([
      { ...base, url: 'a.css', category: 'CSS', compressed: false },
      { ...base, url: 'b.css', category: 'CSS', compressed: false, bytes: KB },            // tam 1 KB → değil
      { ...base, url: 'c.css', category: 'CSS', compressed: false, third_party: true },
      { ...base, url: 'd.svg', category: 'IMAGE', content_type: 'image/svg+xml', compressed: false },
      { ...base, url: 'e.json', category: 'OTHER', content_type: 'application/json', compressed: false },
      { ...base, url: 'f.css', category: 'CSS', compressed: true },
      { ...base, url: 'g.css', category: 'CSS', compressed: false, status: 404 },
    ], [])
    expect(rows.filter((r) => r.findings.includes('UNCOMPRESSED_TEXT')).map((r) => r.url)).toEqual(['a.css', 'd.svg', 'e.json'])
  })

  it('görsel, yavaş, yönlendirme, kırık, büyük belge; katkı bulguları yalnız öneri varken', () => {
    const items = [
      { ...base, url: 'big.jpg', category: 'IMAGE', content_type: 'image/jpeg', bytes: 300 * KB, width: 3000, duration_ms: 1500 },
      { ...base, url: 'mid.png', category: 'IMAGE', content_type: 'image/png', bytes: 80 * KB },
      { ...base, url: 'mid.webp', category: 'IMAGE', content_type: 'image/webp', bytes: 80 * KB },
      { ...base, url: 'r.js', category: 'JS', redirects: 2, cache_ttl_s: 86_400 },
      { ...base, url: 'x.png', category: 'IMAGE', status: 404, cache_ttl_s: 86_400, bytes: 2 * KB },
      { ...base, url: 'l', category: 'LINK', status: 0, page_load: false },
      { ...base, url: 'doc.pdf', category: 'DOCUMENT', page_load: false, bytes: 6 * MB },
      { ...base, url: 'cdn.js', category: 'JS', third_party: true },
    ]
    const f = (rows, url) => rows.find((r) => r.url === url).findings
    let rows = withFindings(items, [])
    expect(f(rows, 'big.jpg')).toEqual(['LARGE_IMAGES', 'SLOW_OBJECTS', 'OVERSIZED_IMAGE_DIMENSIONS', 'NO_CACHE'].filter((c) => FINDING_CODES.includes(c))
      .sort((a, b) => FINDING_CODES.indexOf(a) - FINDING_CODES.indexOf(b)))
    expect(f(rows, 'mid.png')).toContain('MODERN_IMAGE_FORMAT')
    expect(f(rows, 'mid.webp')).not.toContain('MODERN_IMAGE_FORMAT')
    expect(f(rows, 'r.js')).toEqual(['REDIRECTED_OBJECTS'])
    expect(f(rows, 'x.png')).toEqual(['BROKEN_RESOURCES'])
    expect(f(rows, 'l')).toEqual(['BROKEN_LINKS'])
    expect(f(rows, 'doc.pdf')).toEqual(['LARGE_DOCUMENTS'])
    expect(f(rows, 'cdn.js')).toEqual([])
    rows = withFindings(items, [{ code: 'HEAVY_JS' }, { code: 'THIRD_PARTY_HEAVY' }])
    expect(f(rows, 'r.js')).toContain('HEAVY_JS')
    expect(f(rows, 'cdn.js')).toEqual(['HEAVY_JS', 'THIRD_PARTY_HEAVY'])
  })

  it('sunucunun `findings`i esastır (bilinmeyen kod atılır, sabit sıra); istemci hesaplamaz', () => {
    const rows = withFindings([{ ...base, url: 'a', category: 'JS', cache_ttl_s: null, findings: ['YENI_KOD', 'NO_CACHE', 'BROKEN_RESOURCES'] },
      { ...base, url: 'b', category: 'JS', cache_ttl_s: null, findings: [] }], [])
    expect(rows[0].findings).toEqual(['BROKEN_RESOURCES', 'NO_CACHE'])
    expect(rows[0].findings_source).toBe('server')
    expect(rows[1].findings).toEqual([])     // sunucu "yok" dedi → istemci NO_CACHE uydurmaz
  })

  it('fikstür: eski kayıtta NO_CACHE yalnız app.bundle.js (HTML ve 3. taraf görsel değil)', () => {
    const rows = withFindings(scanItems(), scanDetail().analysis.recommendations)
    expect(rows.filter((r) => r.findings.includes('NO_CACHE')).map((r) => r.url)).toEqual(['https://www.example.com/static/app.bundle.js'])
  })

  it('öneri nesne sayısı: sunucu item_count; yoksa bulgu; o da yoksa adres sayısı; adres listesiyle bağlanan öneriler', () => {
    const items = withFindings(scanItemsV2(), [])
    expect(recItemCount({ code: 'NO_CACHE', item_count: 37 }, items)).toBe(37)
    expect(recItemCount({ code: 'NO_CACHE' }, items)).toBe(items.filter((i) => i.findings.includes('NO_CACHE')).length)
    expect(recItemCount({ code: 'SLOW_SERVER', item_urls: ['u'] }, items)).toBe(1)
    expect(recFiltersByFinding({ code: 'NO_CACHE' })).toBe(true)
    expect(recFiltersByFinding({ code: 'SLOW_SERVER' })).toBe(false)
    expect(recFiltersByFinding({ code: 'OVERSIZED_PAGE' })).toBe(false)
  })
})

describe('karar (verdict) ve durum metni', () => {
  it('sunucu kararı esastır; eski kayıtta durumdan; doğrulanamayan / engellenen kırık değil ve nötr', () => {
    expect(verdictOf({ status: 403, verdict: 'unverified' })).toBe('unverified')
    expect(isFailed({ status: 403, verdict: 'unverified' })).toBe(false)
    expect(isUnverified({ status: 0, verdict: 'blocked' })).toBe(true)
    expect(verdictTone({ verdict: 'blocked' })).toBe('muted')
    expect(verdictTone({ verdict: 'broken' })).toBe('danger')
    expect(verdictOf({ status: 404 })).toBe('broken')
    expect(verdictOf({ status: 200, error: 'x' })).toBe('broken')
    expect(verdictOf({ status: 200, verdict: 'yeni' })).toBe('ok')
    expect(verdictText({ verdict: 'ok' }, t)).toBe('')
    expect(verdictText({ verdict: 'skipped' }, t)).toBe('prs.verdict.obj.skipped')
  })
  it('durum 0 → "Yanıt yok" (r177 H6 — nedeni sorun satırı söyler)', () => {
    expect(statusText({ status: 0, error: 'READ_TIMEOUT' }, tr)).toBe('Yanıt yok')
    expect(statusText({ status: 503 }, tr)).toBe('503')
    expect(statusText({ status: null }, tr)).toBe('—')
  })
})

describe('süzgeçler', () => {
  const items = withFindings(scanItemsV2(), [])
  it('bulgu süzgeci (herhangi biri), doğrulanamayanlar, kapsam / durum sayaçları', () => {
    expect(filterItems(items, { scope: 'all', findings: ['BROKEN_RESOURCES'] }).map((i) => i.url)).toEqual(['https://www.example.com/missing/logo.png'])
    const both = filterItems(items, { scope: 'all', findings: ['NO_CACHE', 'LARGE_IMAGES'] })
    expect(both.length).toBe(items.filter((i) => i.findings.some((c) => c === 'NO_CACHE' || c === 'LARGE_IMAGES')).length)
    expect(filterItems(items, { scope: 'all', status: 'unverified' }).map((i) => i.category)).toEqual(['LINK'])
    expect(filterItems(items, { scope: 'all', status: 'failed' }).map((i) => i.url)).toEqual(['https://www.example.com/missing/logo.png'])
    const sc = countsBy(items, DEFAULT_FILTERS, 'scope')
    expect(sc).toMatchObject({ all: 40, page: 38, documents: 1, links: 1 })
    expect(countsBy(items, { ...DEFAULT_FILTERS, scope: 'all' }, 'status')).toMatchObject({ all: 40, failed: 1, unverified: 1 })
    expect(countsBy(items, { ...DEFAULT_FILTERS, scope: 'all' }, 'findings').BROKEN_RESOURCES).toBe(1)
  })
  it('analiz değişince önerinin süzgeci taşınmaz (H5); tür / kapsam / sıralama kalır; değişiklik yoksa aynı nesne', () => {
    const f = { ...DEFAULT_FILTERS, types: ['JS'], scope: 'all', sort: 'url', urls: ['u'], rec: 'NO_CACHE', findings: ['NO_CACHE'] }
    expect(filtersForNewScan(f)).toEqual({ ...f, urls: [], rec: null, findings: [] })
    const plain = { ...DEFAULT_FILTERS, types: ['JS'] }
    expect(filtersForNewScan(plain)).toBe(plain)
  })
})

describe('kategori çubuğu, kayıp puan, hedefler', () => {
  it('çubuk sayfa yükünde PAY (yüzdeyle aynı); belge çubuğu kendi ölçeğinde (H3)', () => {
    const rows = categoryRows(scanDetail().analysis.categories, Math.round(3.4 * MB))
    const img = rows.find((r) => r.category === 'IMAGE')
    expect(img.bar).toBeCloseTo(img.share, 6)
    expect(rows.find((r) => r.category === 'DOCUMENT').bar).toBe(100)
    expect(categoryRows([{ category: 'JS', bytes: 10 }], null)[0].bar).toBe(100)   // sayfa boyutu bilinmiyor → en büyüğe göre
  })
  it('kayıp puan: sunucu `lost`; yoksa ağırlık × (100 − puan) / 100; en çok düşüren 3', () => {
    expect(lostPoints({ lost: 4.2, score: 0, weight: 50 })).toBe(4.2)
    expect(lostPoints({ score: 62, weight: 25 })).toBe(9.5)
    expect(lostPoints({ score: null, weight: 25 })).toBeNull()
    const top = topLosses(scanDetail().analysis.scores)
    expect(top.map((r) => r.key)).toEqual(['load_time', 'page_weight', 'images'])
    expect(top[0].lost).toBe(9.5)
    expect(topLosses([{ key: 'load_time', score: 100, weight: 25 }])).toEqual([])
  })
  it('hedefler yalnız sunucu verince (eski analizde UYDURULMAZ); yön eşiklerden', () => {
    const [load] = scoreRows([{ key: 'load_time', score: 62, weight: 25, value: 2450, unit: 'ms', full_at: 1000, zero_at: 12000 }])
    expect(scoreTarget(load, TRL)).toEqual({ full: '1 sn', zero: '12 sn', lowerIsBetter: true })
    expect(scoreTargetText(load, tr, TRL)).toBe('Tam puan: ≤ 1 sn · sıfır puan: ≥ 12 sn')
    const [cmp] = scoreRows([{ key: 'compression', score: 70, weight: 7, value: 70, unit: 'pct', full_at: 100, zero_at: 0 }])
    expect(scoreTarget(cmp, TRL).lowerIsBetter).toBe(false)
    expect(scoreTarget(scoreRows([{ key: 'load_time', score: 62, weight: 25, value: 2450, unit: 'ms' }])[0], TRL)).toBeNull()
    expect(scoreSheetRows(scanDetailV2().analysis.scores, t, TRL)[0]).toHaveLength(6)
  })
})

describe('yuvarlak eksenler ve şelale', () => {
  it('ms adımları yuvarlak (5,5 sn değil), tavan adımın katı', () => {
    expect(niceMsTicks(5500, 4)).toEqual({ step: 2000, top: 6000, ticks: [0, 2000, 4000, 6000] })
    expect(niceMsTicks(2450, 5).ticks).toEqual([0, 500, 1000, 1500, 2000, 2500])
    expect(niceMsTicks(0, 4).top).toBeGreaterThan(0)
  })
  it('bayt adımları 1024 tabanında yuvarlak (4,77 MB / 2,38 MB değil)', () => {
    expect(niceByteTicks(4.2 * MB, 3)).toEqual({ step: 2 * MB, top: 6 * MB, ticks: [0, 2 * MB, 4 * MB, 6 * MB] })
    expect(niceByteTicks(120 * KB, 3).step).toBe(50 * KB)
    expect(GRADE_TICKS).toEqual([0, 25, 50, 75, 100])
  })
  it('şelale: yalnız tahmine girenler, başlangıç / süre / bitiş sırası, dışarıda kalan sayısı; bekleme + indirme parçaları', () => {
    const items = [
      { url: 'a', start_ms: 100, duration_ms: 300, ttfb_ms: 100 }, { url: 'b', start_ms: 0, duration_ms: 50 },
      { url: 'c', start_ms: null, duration_ms: 10 }, { url: 'd', start_ms: 50, duration_ms: 1000 },
    ]
    expect(waterfallRows(items, 'start')).toEqual({ rows: [items[1], items[3], items[0]], excluded: 1 })
    expect(waterfallRows(items, 'duration').rows.map((r) => r.url)).toEqual(['d', 'a', 'b'])
    expect(waterfallRows(items, 'end').rows.map((r) => r.url)).toEqual(['b', 'a', 'd'])
    const seg = waterfallSegments(items[0], 1000)
    expect(seg.left).toBe(10)
    expect(seg.total).toBe(30)
    expect(seg.wait).toBeCloseTo(10, 6)
    expect(seg.download).toBeCloseTo(20, 6)
    expect(waterfallSegments(items[1], 1000).wait).toBe(0)   // TTFB yok → tek parça
    expect(waterfallSegments(items[2], 1000)).toBeNull()
  })
})

describe('karşılaştırma (istemcide)', () => {
  const det = (id, started, over = {}) => ({ ...scanDetail(id, { scan: { started_at: started, ...over.scan } }), ...over.rest })
  it('yön her zaman ESKİ → YENİ; KPI farkları; algoritma sürümü farkı', () => {
    const a = det(12, '2026-10-31T09:00:00', { scan: { grade: 80, load_ms: 2000 } })
    const b = det(9, '2026-10-25T09:00:00', { scan: { grade: 70, load_ms: 3000 } })
    const c = compareScans(a, b)
    expect(c.older.scan.id).toBe(9)
    expect(c.newer.scan.id).toBe(12)
    expect(c.kpis.find((k) => k.key === 'grade').d.direction).toBe('better')
    expect(c.kpis.find((k) => k.key === 'load').d).toMatchObject({ diff: -1000, direction: 'better' })
    expect(c.algoMismatch).toBe(false)
    const v2 = compareScans(scanDetailV2(12), b)
    expect(v2.algoMismatch).toBe(true)
    expect([v2.algoOld, v2.algoNew]).toEqual([1, 2])
  })
  it('eklenen / kaldırılan / büyüyen nesneler (%10 ve 10 KB eşiği); öneriler: yeni çıkan / çözülen', () => {
    const items = scanItems()
    const older = { scan: { id: 1, started_at: '2026-10-01T00:00:00' }, analysis: { recommendations: [{ code: 'NO_CACHE' }, { code: 'ALL_GOOD' }] },
      items: [...items.slice(0, 5), { url: 'gone.js', bytes: 10 }] }
    const newerItems = items.slice(0, 5).map((it, i) => (i === 2 ? { ...it, bytes: it.bytes + 200 * KB } : i === 3 ? { ...it, bytes: it.bytes + KB } : it))
    const newer = { scan: { id: 2, started_at: '2026-10-02T00:00:00' }, analysis: { recommendations: [{ code: 'LARGE_IMAGES' }] },
      items: [...newerItems, { url: 'new.js', bytes: 50 * KB }] }
    const c = compareScans(newer, older)
    expect(c.objects.added.map((i) => i.url)).toEqual(['new.js'])
    expect(c.objects.removed.map((i) => i.url)).toEqual(['gone.js'])
    expect(c.objects.grown.map((i) => i.url)).toEqual([items[2].url])       // 1 KB büyüme gürültü sayılır
    expect(c.objects.grown[0].diff).toBe(200 * KB)
    expect(c.recNew.map((r) => r.code)).toEqual(['LARGE_IMAGES'])
    expect(c.recResolved.map((r) => r.code)).toEqual(['NO_CACHE'])        // ALL_GOOD sayılmaz
  })
  it('birinde nesne ayrıntısı yoksa yalnız özet (`objects: null`); karşılaştırılabilenler yalnız tamamlanmış analizler', () => {
    const c = compareScans({ scan: { id: 1, started_at: '2026-10-01T00:00:00' }, items: null }, { scan: { id: 2, started_at: '2026-10-02T00:00:00' }, items: [] })
    expect(c.objects).toBeNull()
    expect(compareScans(null, {})).toBeNull()
    expect(comparableScans([{ id: 1, status: 'DONE' }, { id: 2, status: 'FAILED' }, { id: 3, status: 'DONE' }]).map((s) => s.id)).toEqual([1, 3])
    expect(latestDoneId([{ id: 5, status: 'RUNNING' }, { id: 4, status: 'FAILED' }, { id: 3, status: 'DONE' }])).toBe(3)
    expect(algorithmVersion({}, {})).toBe(1)
    expect(algorithmVersion({ algorithm_version: 2 }, null)).toBe(2)
  })
})

describe('URL durumu (paylaşılabilir bağlantı)', () => {
  it('okuma: geçersiz değerler düşer; görünüm ↔ bölüm', () => {
    expect(parseUrlState('?prs=12&prs_v=waterfall&prs_b=9')).toEqual({ scanId: 12, view: 'waterfall', otherId: 9 })
    expect(parseUrlState('?prs=abc&prs_v=bilinmeyen&prs_b=-3')).toEqual({ scanId: null, view: 'dash', otherId: null })
    expect(VIEWS.map(sectionOfView)).toEqual(['overview', 'objects', 'objects', 'history', 'history'])
    expect(viewOf('objects', 'waterfall', 'list')).toBe('waterfall')
    expect(viewOf('history', 'list', 'compare')).toBe('compare')
    expect(viewOf('overview', 'waterfall', 'compare')).toBe('dash')
  })
  it('yazma: varsayılan görünüm ve karşılaştırma dışı ikinci analiz yazılmaz; mutlak bağlantı', () => {
    expect(urlStateValues({ scanId: 12, view: 'dash', otherId: 9 })).toEqual({ [URL_SCAN]: 12, [URL_VIEW]: null, [URL_OTHER]: null })
    expect(urlStateValues({ scanId: 12, view: 'compare', otherId: 9 })).toEqual({ prs: 12, prs_v: 'compare', prs_b: 9 })
    expect(resourceScanLink({ origin: 'https://sm.example', monitorId: 100, scanId: 12, view: 'objects' }))
      .toBe('https://sm.example/?tab=page&monitor=100&mtab=resources&prs=12&prs_v=objects')
  })
})

describe('geçen süre, ilerleme, iptal', () => {
  it('geçen süre sunucu damgasıyla (tarayıcı saati kaysa da) — H13; eksiye düşmez', () => {
    const scan = { started_at: '2026-10-31T09:00:00' }
    expect(elapsedAtSighting(scan, '2026-10-31T09:00:30Z', Date.parse('2020-01-01T00:00:00Z'))).toBe(30_000)
    expect(elapsedAtSighting(scan, null, Date.parse('2026-10-31T09:01:00Z'))).toBe(60_000)
    expect(elapsedAtSighting(scan, '2026-10-31T08:00:00Z')).toBe(0)
    expect(elapsedAtSighting({}, 'x')).toBeNull()
  })
  it('ilerleme geri gitmez (H16); belirsiz → önceki', () => {
    expect(monotonicPct(null, 40)).toBe(40)
    expect(monotonicPct(77, 52)).toBe(77)
    expect(monotonicPct(52, 77)).toBe(77)
    expect(monotonicPct(30, null)).toBe(30)
  })
  it('iptal: bayrak ya da hata kodu', () => {
    expect(isCancelled({ cancelled: true })).toBe(true)
    expect(isCancelled({ error_code: 'CANCELLED' })).toBe(true)
    expect(isCancelled({ status: 'INTERRUPTED', error_code: 'SHUTDOWN' })).toBe(false)
  })
})

describe('üçüncü taraflar, görsel kazancı, dışa aktarım', () => {
  it('sunucu listesi esastır; eski analizde nesnelerden (yalnız sayfa yükü, bayta göre)', () => {
    const srv = thirdPartyRows(scanDetailV2().analysis, null)
    expect(srv.source).toBe('server')
    expect(srv.rows[1]).toEqual({ host: 'analytics.example.net', count: 3, bytes: 90 * KB, failed: 1, categories: ['JS', 'IMAGE'] })
    const cli = thirdPartyRows({}, scanItems())
    expect(cli.source).toBe('client')
    expect(cli.rows.map((r) => r.host)).toEqual(['cok-uzun-bir-icerik-dagitim-agi-adresi-1.example.test'])
    expect(thirdPartyRows({}, null)).toBeNull()
    expect(thirdPartyRows({}, []).rows).toEqual([])
  })
  it('ölçülmüş optimize boyut ve kazanç yüzdesi', () => {
    expect(optimizedImage({ bytes: 1000, optimized_bytes: 250 })).toEqual({ bytes: 1000, optimized: 250, savedPct: 75 })
    expect(optimizedImage({ bytes: 1000 })).toBeNull()
    expect(optimizedImage({ bytes: 1000, optimized_bytes: 1200 }).savedPct).toBe(0)
  })
  it('nesne sayfasına bulgu / karar / yol / bulunduğu yer / son adres / başlık sütunları; eski kayıtta boş', () => {
    const rows = objectSheetRows(withFindings(scanItemsV2(), []), tr, TRL)
    const js = rows.find((r) => r[0] === 'https://www.example.com/static/app.bundle.js')
    expect(js[22]).toBe('Sıkıştırılmamış, Önbelleğe alınmıyor, Yönlendirmeli, Ağır JavaScript yüküne katkı')
    expect(js[24]).toBe('doğrudan bağlantı')
    expect(js[25]).toBe("Sayfanın HTML'i")
    expect(js[26]).toBe('https://www.example.com/static/app.bundle.v2.js')
    expect(js[28]).toBe('no-store')
    const link = rows.find((r) => r[0] === 'https://www.example.com/iletisim')
    expect(link[21]).toBe('Doğrulanamadı (kırık sayılmaz)')
    const old = objectSheetRows(scanItems(), tr, TRL).find((r) => r[0] === 'https://www.example.com/static/app.bundle.js')
    expect([old[24], old[25], old[26], old[28]]).toEqual(['', '', '', ''])
    expect(foundInText({ found_in: 'https://x/s.css' }, tr)).toBe('Stil dosyası: https://x/s.css')
    expect(foundInText({ from_css: true }, tr)).toBe('Bir stil dosyası (adresi kaydedilmemiş)')
    expect(findingLabel('YOK', tr)).toBe('Diğer bulgu')
  })
  it('özet: yönlendirme ve algoritma sürümü; türler sayfası', () => {
    const sum = summarySheetRows({ name: 'X' }, { redirect_count: 2, redirect_ms: 480, algorithm_version: 2 }, {}, t, TRL)
    expect(sum.find((r) => r[0] === 'prs.export.redirects')[1]).toBe('prs.export.redirectsValue(2|480 ms)')
    expect(sum.find((r) => r[0] === 'prs.export.algorithm')[1]).toBe('2')
    expect(summarySheetRows({}, {}, {}, t, TRL).find((r) => r[0] === 'prs.export.redirects')[1]).toBe('')
    const cats = categorySheetRows(scanDetail().analysis.categories, Math.round(3.4 * MB), t, TRL)
    expect(cats[0]).toHaveLength(6)
    expect(cats.slice(1).map((r) => r[0]).at(-1)).toBe('prs.cat.LINK')
  })
})

describe('bitiş bildirimi izleyicisi (bu sekmede başlatılan, sekme sökülünce)', () => {
  afterEach(() => { resetWatchers(); vi.useRealTimers() })

  it('işaretlenen analiz devredilir, RUNNING dışına çıkınca BİR KEZ bildirir ve unutulur', async () => {
    vi.useFakeTimers()
    markStartedHere(100, 50)
    expect(startedHere(100, 50)).toBe(true)
    const states = ['RUNNING', 'RUNNING', 'DONE']
    const poll = vi.fn(async () => ({ scan: { id: 50, status: states.shift() ?? 'DONE' } }))
    const onDone = vi.fn()
    handOff({ monitorId: 100, scanId: 50, poll, onDone, pollMs: 100 })
    expect(isWatching(100, 50)).toBe(true)
    await vi.advanceTimersByTimeAsync(1000)
    expect(onDone).toHaveBeenCalledTimes(1)
    expect(onDone.mock.calls[0][0].status).toBe('DONE')
    expect(isWatching(100, 50)).toBe(false)
    expect(startedHere(100, 50)).toBe(false)
  })

  it('art arda okuma hatası ya da süre tavanı → durur, bildirim YOK (sonuç uydurulmaz); geri alma çift sorguyu keser', async () => {
    vi.useFakeTimers()
    const onDone = vi.fn()
    handOff({ monitorId: 1, scanId: 2, poll: async () => null, onDone, pollMs: 50, maxFailures: 3 })
    await vi.advanceTimersByTimeAsync(2000)
    expect(onDone).not.toHaveBeenCalled()
    expect(isWatching(1, 2)).toBe(false)

    let now = 0
    handOff({ monitorId: 1, scanId: 3, poll: async () => ({ scan: { status: 'RUNNING' } }), onDone, pollMs: 50, capMs: 120, now: () => now })
    now = 500
    await vi.advanceTimersByTimeAsync(200)
    expect(isWatching(1, 3)).toBe(false)

    const poll = vi.fn(async () => ({ scan: { status: 'RUNNING' } }))
    handOff({ monitorId: 1, scanId: 4, poll, onDone, pollMs: 50 })
    expect(takeBack(1, 4)).toBe(true)
    await vi.advanceTimersByTimeAsync(500)
    expect(poll).not.toHaveBeenCalled()
    expect(takeBack(1, 4)).toBe(false)
    forgetStarted(1, 4)
  })
})

describe('sözlük — r177 metinleri iki dilde', () => {
  it('bulgu etiketleri ve yeni metinler TR + EN', () => {
    for (const c of FINDING_CODES) {
      expect(TR[`prs.finding.${c}`], c).toBeTruthy()
      expect(EN[`prs.finding.${c}`], c).toBeTruthy()
    }
    for (const k of ['prs.method.notLighthouse', 'prs.loss.title', 'prs.cancel.confirmBody', 'prs.cmp.algo', 'prs.od.notRecorded']) {
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
    }
  })
})
