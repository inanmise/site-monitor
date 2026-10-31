import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  BUCKET_KEYS, CATEGORIES, DEFAULT_FILTERS, KB, MB, NOTE_ARGS, NOTE_PLURAL, REC_CODES, REC_PLURAL, SCORE_KEYS, SORTS, activeFilterCount, cacheState,
  categoryRows, countsBy, delta, deltasComparable, elapsedMs, estImageMs, exportFileName, filterItems, formatBytesExact, formatMs, formatPct, formatSize,
  formatTtl, gradeLetter, gradeTone, imageSheetRows, isCapped, isFailed, isPageLoad, noteRows, objectSheetRows, pickDefaultScan, plural, pluralKey,
  pollCapMs, pollCapReached, previousDone, problemText, progressPct, scanTone, recHostList, recItemUrls, recSaving, recTextKey, recommendationArgs, recommendationSheetRows, scoreLabelKey, scoreRows, scoreTone,
  sizeBucket, slugify, sortItems, sortRecommendations, summarySheetRows, trendDomain, trendPoints, waterfallBar, waterfallTotal,
  weightPct,
} from '../components/page/resourcescan/resourceScanModel.js'

// Kaynak analizi modeli (2026-10-31) — boyut kovaları, süzgeç / sayaç / sıralama, biçimleme, fark, grafik noktaları,
// öneri parametreleri ve dışa aktarım satırları. Tek kaynak: ekran, Excel ve PDF bu fonksiyonları kullanır.

const t = (k, ...a) => `${k}${a.length ? `(${a.join('|')})` : ''}`
const TRL = 'tr-TR'
const ENL = 'en-GB'

const ITEMS = [
  { url: 'https://ex.com/', category: 'HTML', bytes: 40 * KB, duration_ms: 300, start_ms: 0, status: 200, page_load: true },
  { url: 'https://ex.com/hero.jpg', category: 'IMAGE', bytes: 2 * MB, duration_ms: 1800, start_ms: 300, status: 200, width: 3840, height: 2160 },
  { url: 'https://ex.com/a.js', category: 'JS', bytes: 600 * KB, duration_ms: 900, start_ms: 320, status: 200, compressed: false },
  { url: 'https://cdn.ex.net/b.css', category: 'CSS', bytes: 150 * KB, duration_ms: 400, start_ms: 310, status: 404, third_party: true },
  { url: 'https://ex.com/doc.pdf', category: 'DOCUMENT', bytes: 5 * MB, duration_ms: 2200, start_ms: null, status: 200, page_load: false },
  { url: 'https://ex.com/about', category: 'LINK', bytes: 8 * KB, duration_ms: 120, start_ms: null, status: 0, error: 'timeout', page_load: false },
  { url: 'https://ex.com/lazy.png', category: 'IMAGE', bytes: 20 * KB, duration_ms: 50, start_ms: null, status: 200, lazy: true },
]

describe('boyut kovaları ve nesne kuralları', () => {
  it('1024 tabanlı kova sınırları', () => {
    expect(sizeBucket(MB)).toBe('xl')
    expect(sizeBucket(MB - 1)).toBe('lg')
    expect(sizeBucket(500 * KB)).toBe('lg')
    expect(sizeBucket(500 * KB - 1)).toBe('md')
    expect(sizeBucket(100 * KB)).toBe('md')
    expect(sizeBucket(10 * KB)).toBe('sm')
    expect(sizeBucket(10 * KB - 1)).toBe('xs')
    expect(sizeBucket(0)).toBe('xs')
    expect(sizeBucket(null)).toBeNull()
    expect(sizeBucket(-5)).toBeNull()
    expect(BUCKET_KEYS).toEqual(['xl', 'lg', 'md', 'sm', 'xs'])
  })
  it('sayfa yükü bayrağı yoksa kategoriden; başarısız = 0 / ≥ 400 / hata metni', () => {
    expect(isPageLoad({ category: 'DOCUMENT' })).toBe(false)
    expect(isPageLoad({ category: 'LINK' })).toBe(false)
    expect(isPageLoad({ category: 'IMAGE' })).toBe(true)
    expect(isPageLoad({ category: 'IMAGE', page_load: false })).toBe(false)
    expect(isFailed({ status: 0 })).toBe(true)
    expect(isFailed({ status: 404 })).toBe(true)
    expect(isFailed({ status: 301 })).toBe(false)
    expect(isFailed({ status: 200, error: 'tls' })).toBe(true)
    expect(isFailed({ status: null })).toBe(false)
  })
})

describe('süzgeç / sayaç / sıralama', () => {
  it('varsayılan süzgeç her şeyi geçirir; sıra büyükten küçüğe', () => {
    const out = sortItems(filterItems(ITEMS, DEFAULT_FILTERS), 'size_desc', TRL)
    expect(out.map((x) => x.category)).toEqual(['DOCUMENT', 'IMAGE', 'JS', 'CSS', 'HTML', 'IMAGE', 'LINK'])
  })
  it('tür (çoklu), kova, kapsam, durum, arama (Türkçe katlama), öneri adresleri', () => {
    expect(filterItems(ITEMS, { types: ['IMAGE', 'CSS'] })).toHaveLength(3)
    expect(filterItems(ITEMS, { buckets: ['xl'] }).map((x) => x.category)).toEqual(['IMAGE', 'DOCUMENT'])
    expect(filterItems(ITEMS, { scope: 'page' })).toHaveLength(5)
    expect(filterItems(ITEMS, { scope: 'documents' }).map((x) => x.category)).toEqual(['DOCUMENT'])
    expect(filterItems(ITEMS, { scope: 'links' }).map((x) => x.category)).toEqual(['LINK'])
    expect(filterItems(ITEMS, { status: 'failed' }).map((x) => x.category)).toEqual(['CSS', 'LINK'])
    expect(filterItems(ITEMS, { q: 'CDN.EX' })).toHaveLength(1)
    expect(filterItems([{ url: 'https://ex.com/İletişim' }], { q: 'iletisim' })).toHaveLength(1)
    expect(filterItems(ITEMS, { urls: ['https://ex.com/a.js', 'https://ex.com/doc.pdf'] })).toHaveLength(2)
  })
  it('sayaçlar kendi boyutunu dışlar, ötekileri uygular', () => {
    const f = { ...DEFAULT_FILTERS, types: ['IMAGE'], scope: 'page' }
    expect(countsBy(ITEMS, f, 'types')).toEqual({ HTML: 1, IMAGE: 2, JS: 1, CSS: 1 })
    expect(countsBy(ITEMS, f, 'buckets')).toEqual({ xl: 1, sm: 1 })
  })
  it('etkin süzgeç sayısı (sıralama sayılmaz)', () => {
    expect(activeFilterCount(DEFAULT_FILTERS)).toBe(0)
    expect(activeFilterCount({ ...DEFAULT_FILTERS, sort: 'url' })).toBe(0)
    expect(activeFilterCount({ q: ' x ', types: ['JS'], buckets: ['xs'], scope: 'page', status: 'failed', urls: ['u'] })).toBe(6)
  })
  it('her sıralama boş değeri SONA koyar; eşitlikte kararlı', () => {
    const rows = [{ url: 'b', bytes: null, duration_ms: 5 }, { url: 'a', bytes: 10, duration_ms: null }, { url: 'c', bytes: 30, duration_ms: 1 }]
    expect(sortItems(rows, 'size_desc').map((r) => r.url)).toEqual(['c', 'a', 'b'])
    expect(sortItems(rows, 'size_asc').map((r) => r.url)).toEqual(['a', 'c', 'b'])
    expect(sortItems(rows, 'time_desc').map((r) => r.url)).toEqual(['b', 'c', 'a'])
    expect(sortItems(rows, 'time_asc').map((r) => r.url)).toEqual(['c', 'b', 'a'])
    expect(sortItems(rows, 'url').map((r) => r.url)).toEqual(['a', 'b', 'c'])
    expect(sortItems(ITEMS, 'type').map((r) => r.category)).toEqual(['HTML', 'CSS', 'JS', 'IMAGE', 'IMAGE', 'DOCUMENT', 'LINK'])
    expect(sortItems(rows, 'bilinmeyen').map((r) => r.url)).toEqual(['c', 'a', 'b'])
    expect(SORTS[0]).toBe('size_desc')
  })
})

describe('biçimleme', () => {
  it('boyut 1024 tabanlı, yerel ondalık, kırpılan "≥"', () => {
    expect(formatSize(512, { locale: TRL })).toBe('512 B')
    expect(formatSize(1536, { locale: TRL })).toBe('1,5 KB')
    expect(formatSize(1536, { locale: ENL })).toBe('1.5 KB')
    expect(formatSize(150 * KB, { locale: TRL })).toBe('150 KB')
    expect(formatSize(1.25 * MB, { locale: TRL })).toBe('1,25 MB')
    expect(formatSize(12.34 * MB, { locale: ENL })).toBe('12.3 MB')
    expect(formatSize(2 * MB, { truncated: true, locale: ENL })).toBe('≥ 2 MB')
    expect(formatSize(null)).toBe('—')
    expect(formatBytesExact(1234567, TRL)).toBe('1.234.567 B')
    expect(formatBytesExact(1234567, ENL)).toBe('1,234,567 B')
  })
  it('süre ms / sn / dk, iki dil', () => {
    expect(formatMs(820, TRL)).toBe('820 ms')
    expect(formatMs(2450, TRL)).toBe('2,45 sn')
    expect(formatMs(2450, ENL)).toBe('2.45 s')
    expect(formatMs(12_340, ENL)).toBe('12.3 s')
    expect(formatMs(65_000, TRL)).toBe('1 dk 05 sn')
    expect(formatMs(65_000, ENL)).toBe('1 min 05 s')
    expect(formatMs(null)).toBe('—')
    expect(formatPct(45.26, TRL)).toBe('%45,3')
    expect(formatPct(45.26, ENL)).toBe('45.3%')
  })
  it('önbellek durumu ve süresi', () => {
    expect(cacheState(null)).toBe('none')
    expect(cacheState(-1)).toBe('nostore')
    expect(cacheState(3600)).toBe('ttl')
    expect(formatTtl(45, TRL)).toBe('45 sn')
    expect(formatTtl(600, ENL)).toBe('10 min')
    expect(formatTtl(7 * 86_400, TRL)).toBe('7 g')
  })
})

describe('not, ton, fark', () => {
  it('sunucu harfi esas; yoksa puandan; bilinmeyen null ve soluk (asla yeşil değil)', () => {
    expect(gradeLetter(95, 'b')).toBe('B')
    expect(gradeLetter(95)).toBe('A')
    expect(gradeLetter(55)).toBe('E')
    expect(gradeLetter(10)).toBe('F')
    expect(gradeLetter(null)).toBeNull()
    expect(['A', 'B'].map(gradeTone)).toEqual(['success', 'success'])
    expect(['C', 'D'].map(gradeTone)).toEqual(['warning', 'warning'])
    expect(['E', 'F'].map(gradeTone)).toEqual(['danger', 'danger'])
    expect(gradeTone(null)).toBe('muted')
    expect(scoreTone(85)).toBe('success')
    expect(scoreTone(60)).toBe('warning')
    expect(scoreTone(10)).toBe('danger')
    expect(scoreTone(null)).toBe('muted')
  })
  it('fark: düşük iyi / yüksek iyi, %1 altı aynı, bilinmeyen null', () => {
    expect(delta(1800, 2400)).toMatchObject({ diff: -600, direction: 'better' })
    expect(delta(2400, 1800)).toMatchObject({ direction: 'worse' })
    expect(delta(80, 72, { lowerIsBetter: false })).toMatchObject({ diff: 8, direction: 'better' })
    expect(delta(1000, 1005).direction).toBe('same')
    expect(delta(5, 5).direction).toBe('same')
    expect(delta(null, 5)).toBeNull()
    expect(delta(5, undefined)).toBeNull()
  })
  it('önceki tamamlanmış koşu ve varsayılan seçim', () => {
    const hist = [{ id: 9, status: 'RUNNING' }, { id: 8, status: 'FAILED' }, { id: 7, status: 'DONE' }, { id: 6, status: 'DONE' }]
    expect(previousDone(hist, 7)?.id).toBe(6)
    expect(previousDone(hist, 8)?.id).toBe(7)
    expect(previousDone(hist, 6)).toBeNull()
    expect(previousDone(hist, 99)).toBeNull()
    expect(pickDefaultScan(hist)).toBe(7)
    expect(pickDefaultScan([{ id: 3, status: 'FAILED' }])).toBe(3)
    expect(pickDefaultScan([{ id: 3, status: 'RUNNING' }])).toBeNull()
    expect(pickDefaultScan(null)).toBeNull()
  })
  it('ilerleme, geçen süre, izleme tavanı', () => {
    expect(progressPct({ progress: { done: 30, total: 120 } })).toBe(25)
    expect(progressPct({ progress: { done: 0, total: 0 } })).toBeNull()
    const now = Date.parse('2026-10-31T10:00:30Z')
    expect(elapsedMs({ started_at: '2026-10-31T10:00:00' }, now)).toBe(30_000)
    expect(elapsedMs({ started_at: '2026-10-31T10:01:00' }, now)).toBe(0)   // saat kayması eksiye düşmez
    expect(elapsedMs({ started_at: '2026-10-31T10:00:00', finished_at: '2026-10-31T10:00:10' }, now)).toBe(10_000)
    expect(pollCapMs({ max_seconds: 900 })).toBe(900_000 + 120_000)
    expect(pollCapMs(null)).toBe(600_000)
  })
})

describe('grafik noktaları', () => {
  const hist = [
    { id: 4, status: 'DONE', started_at: '2026-10-30T10:00:00', load_ms: 2100, page_bytes: 3 * MB, grade: 72, grade_letter: 'C' },
    { id: 3, status: 'FAILED', started_at: '2026-10-29T10:00:00' },
    { id: 5, status: 'RUNNING', started_at: '2026-10-31T10:00:00' },
    { id: 2, status: 'DONE', started_at: '2026-10-28T10:00:00', load_ms: 2600, page_bytes: 4 * MB, grade: 64 },
    { id: 1, status: 'DONE', started_at: '2026-10-27T10:00:00', load_ms: 2500, page_bytes: 4 * MB, grade: 66 },
  ]
  it('eskiden yeniye; süren koşu yok; başarısız koşu BOŞLUK (sıfır değil); komşusuz değer yalnız', () => {
    const pts = trendPoints(hist)
    expect(pts.map((p) => p.id)).toEqual([1, 2, 3, 4])
    expect(pts.find((p) => p.id === 3)).toMatchObject({ gap: true, load: null, size: null })
    expect(pts.find((p) => p.id === 4)).toMatchObject({ load: 2100, letter: 'C', isolated: true })
    expect(pts.find((p) => p.id === 2).isolated).toBe(false)
    expect(pts.find((p) => p.id === 2).letter).toBe('D')
  })
  it('tek nokta ±1 sa genişler; boş liste null', () => {
    const one = trendPoints([hist[0]])
    const d = trendDomain(one)
    expect(d[1] - d[0]).toBe(2 * 3_600_000)
    expect(trendDomain([])).toBeNull()
  })
})

describe('analiz blokları', () => {
  it('kategori kırılımı: sayfa yükü önce (bayta göre), belge / bağlantı sonra; pay yalnız sayfa yükünde', () => {
    const rows = categoryRows([
      { category: 'LINK', count: 40, bytes: 900 * KB }, { category: 'IMAGE', count: 12, bytes: 3 * MB },
      { category: 'JS', count: 8, bytes: 1 * MB }, { category: 'DOCUMENT', count: 2, bytes: 6 * MB },
    ], 4 * MB)
    expect(rows.map((r) => r.category)).toEqual(['IMAGE', 'JS', 'DOCUMENT', 'LINK'])
    expect(rows[0].share).toBe(75)
    expect(rows[2].share).toBeNull()
    expect(rows[2].bar).toBe(100)
    expect(rows[0].bar).toBe(50)
  })
  it('puan bileşenleri sırası, ağırlık (kesir ya da yüzde), bilinmeyen anahtar sonda', () => {
    const rows = scoreRows([{ key: 'errors', score: 100, weight: 0.1 }, { key: 'yeni', score: 50, weight: 5 }, { key: 'load_time', score: 40, weight: 30 }])
    expect(rows.map((r) => r.key)).toEqual(['load_time', 'errors', 'yeni'])
    expect(rows[1].weight).toBe(10)
    expect(rows[0].weight).toBe(30)
    expect(rows[2].known).toBe(false)
    expect(weightPct(null)).toBeNull()
  })
  it('öneri parametreleri biçimli; host_list / item_urls ayrı; önem sırası', () => {
    const large = { code: 'LARGE_IMAGES', params: { count: 3, bytes: 3 * MB, limit_kb: 200 } }
    expect(recommendationArgs(large, TRL)).toEqual(['3', '3 MB'])
    expect(recommendationArgs(large, TRL, 'body')).toEqual(['200 KB'])
    const slow = { code: 'SLOW_SERVER', params: { ttfb_ms: 1450, limit_ms: 600 } }
    expect(recommendationArgs(slow, ENL)).toEqual(['1.45 s'])
    expect(recommendationArgs(slow, ENL, 'body')).toEqual(['1.45 s', '600 ms'])
    const third = { code: 'THIRD_PARTY_HEAVY', params: { bytes: MB, share_pct: 42.5, hosts: 7 } }
    expect(recommendationArgs(third, TRL)).toEqual(['1 MB'])
    expect(recommendationArgs(third, TRL, 'body')).toEqual(['%42,5', '7'])
    expect(recommendationArgs({ code: 'OVERSIZED_IMAGE_DIMENSIONS', params: { count: 2, max_width: 1920 } }, ENL, 'body')).toEqual(['1,920 px'])
    expect(recommendationArgs({ code: 'BROKEN_LINKS', params: { count: 4 } }, TRL)).toEqual(['4'])
    expect(recommendationArgs({ code: 'LARGE_IMAGES', params: {} }, TRL)).toEqual(['—', '—'])
    expect(recommendationArgs({ code: 'YOK', params: { count: 1 } }, TRL)).toEqual([])
    expect(recHostList({ params: { host_list: ['a.com', '', 'b.com', 1] } })).toEqual(['a.com', 'b.com'])
    expect(recItemUrls({ item_urls: Array.from({ length: 12 }, (_, i) => `u${i}`) })).toHaveLength(10)
    expect(recItemUrls({})).toEqual([])
    const sorted = sortRecommendations([{ code: 'A', severity: 'low' }, { code: 'B', severity: 'high' }, { code: 'C', severity: 'medium' }, { code: 'D', severity: 'high' }])
    expect(sorted.map((r) => r.code)).toEqual(['B', 'D', 'C', 'A'])
    expect(recSaving({ saving_bytes: 2 * MB, saving_ms: 1200 }, ENL)).toEqual({ bytes: '2 MB', ms: '1.2 s' })
    expect(recSaving({ saving_bytes: 0, saving_ms: null }, ENL)).toBeNull()
  })
  it('ölçüm notları: parametreler biçimli, bilinmeyen kod bayraklı (ham kod gösterilmez)', () => {
    const rows = noteRows([{ code: 'JS_NOT_EXECUTED' }, { code: 'CAPPED_BYTES', params: { mb: 50 } }, { code: 'YENI' }], TRL)
    expect(rows[0]).toEqual({ code: 'JS_NOT_EXECUTED', known: true, key: 'prs.note.JS_NOT_EXECUTED', args: [] })
    expect(rows[1].args).toEqual(['50 MB'])
    expect(rows[2].known).toBe(false)
    expect(rows[2].key).toBe('prs.note.other')
    const one = noteRows([{ code: 'EXCLUDES_APPLIED', params: { count: 1 } }, { code: 'EXCLUDES_APPLIED', params: { count: 4 } }], ENL)
    expect(one.map((n) => n.key)).toEqual(['prs.note.EXCLUDES_APPLIED.one', 'prs.note.EXCLUDES_APPLIED'])
  })
  it('tek görselin 4G süresi toplam tahmin oranından', () => {
    expect(estImageMs(MB, { bytes: 4 * MB, est_ms_4g: 4000 })).toBe(1000)
    expect(estImageMs(MB, { bytes: 0, est_ms_4g: 4000 })).toBeNull()
  })
  it('şelale: ölçek = max(yüklenme, en geç bitiş); başlangıcı olmayan nesneye çubuk yok', () => {
    const total = waterfallTotal(ITEMS, 2000)
    expect(total).toBe(2100)   // hero.jpg 300 + 1800
    expect(waterfallBar(ITEMS[1], total)).toEqual({ left: (300 / 2100) * 100, width: (1800 / 2100) * 100 })
    expect(waterfallBar(ITEMS[4], total)).toBeNull()
    expect(waterfallBar(ITEMS[6], total)).toBeNull()   // lazy görsel: start_ms null
    expect(waterfallBar(ITEMS[0], 0)).toBeNull()
  })
})

describe('dışa aktarım', () => {
  it('dosya adı: Türkçe harfler sadeleşir, yerel saat damgası', () => {
    expect(slugify('Ödeme Şubesi — Çağrı Merkezi')).toBe('odeme-subesi-cagri-merkezi')
    expect(slugify('')).toBe('page')
    const iso = '2026-10-31T09:05:00'
    const d = new Date(Date.parse(`${iso}Z`))
    const p = (n) => String(n).padStart(2, '0')
    const stamp = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`
    expect(exportFileName({ name: 'Ana Sayfa İzleme' }, 'kaynak-analizi', 'xlsx', iso)).toBe(`ana-sayfa-izleme-kaynak-analizi-${stamp}.xlsx`)
    expect(exportFileName({ url: 'https://www.example.com/x' }, 'resource-analysis', 'pdf', iso)).toBe(`www-example-com-resource-analysis-${stamp}.pdf`)
  })
  it('nesne satırları: başlık + sayılar sayı hücresi, lazy / kırpma / sorun sütunları', () => {
    const rows = objectSheetRows(ITEMS, t, TRL)
    expect(rows[0]).toHaveLength(21)
    expect(rows).toHaveLength(ITEMS.length + 1)
    const hero = rows.find((r) => r[0] === 'https://ex.com/hero.jpg')
    expect(hero[4]).toBe(2 * MB)
    expect(hero[5]).toBe('2 MB')
    expect(hero[6]).toBe('prs.bucket.xl')
    const link = rows.find((r) => r[0] === 'https://ex.com/about')
    expect(link[3]).toBe('prs.obj.transport')
    expect(link[10]).toBe('prs.export.no')
    expect(link[20]).toBe('prs.objProblem.connection')   // ham kod / metin asla sütuna yazılmaz
    const lazy = rows.find((r) => r[0] === 'https://ex.com/lazy.png')
    expect(lazy[18]).toBe('prs.export.yes')
  })
  it('özet, öneri ve görsel satırları', () => {
    const scan = { url: 'https://ex.com/', grade: 72, grade_letter: 'C', load_ms: 2100, page_bytes: 3 * MB, request_count: 54,
      image_bytes: 2 * MB, image_count: 12, document_count: 2, document_bytes: 5 * MB, link_count: 30, link_bytes: MB, failed_count: 1,
      via: 'proxy', started_at: '2026-10-31T09:00:00', requested_by: 'Ayşe Yılmaz' }
    const sum = summarySheetRows({ name: 'X' }, scan, { timing: { parallel: 6 } }, t, TRL)
    expect(sum[0]).toEqual(['prs.export.field', 'prs.export.value'])
    expect(sum.find((r) => r[0] === 'prs.grade')[1]).toBe('72 / 100 (C)')
    expect(sum.find((r) => r[0] === 'prs.export.via')[1]).toBe('prs.via.proxy')
    const recs = recommendationSheetRows([
      { code: 'THIRD_PARTY_HEAVY', severity: 'medium', params: { bytes: MB, share_pct: 40, hosts: 3, host_list: ['a.com', 'b.com'] }, item_urls: ['u1', 'u2'] },
      { code: 'YENI_KOD', severity: 'high' },
    ], t, TRL)
    expect(recs[1][1]).toBe('prs.rec.other.title')   // bilinmeyen kod önce (high) ve ham kod değil
    expect(recs[2][2]).toContain('a.com, b.com')
    expect(recs[2][4]).toBe('u1\nu2')
    const imgs = imageSheetRows({ bytes: 2 * MB, est_ms_4g: 2000, top: [{ url: 'i.jpg', bytes: MB, width: 1920, height: 1080 }] }, 4 * MB, t, ENL)
    expect(imgs[1]).toEqual(['i.jpg', MB, '1 MB', '1920×1080', '1 s', '25%'])
  })
})

describe('sözlük kapısı — dinamik anahtarlar iki dilde de var', () => {
  const keys = [
    ...CATEGORIES.map((c) => `prs.cat.${c}`), ...BUCKET_KEYS.map((b) => `prs.bucket.${b}`), ...SCORE_KEYS.map(scoreLabelKey),
    ...REC_CODES.flatMap((c) => [`prs.rec.${c}.title`, `prs.rec.${c}.body`]), ...Object.keys(NOTE_ARGS).map((c) => `prs.note.${c}`),
    ...SORTS.map((s) => `prs.sort.${s}`), ...['A', 'B', 'C', 'D', 'E', 'F'].map((l) => `prs.verdict.${l}`),
    ...['DONE', 'RUNNING', 'FAILED', 'INTERRUPTED', 'UNKNOWN'].map((s) => `prs.status.run.${s}`),
    'prs.rec.other.title', 'prs.rec.other.body', 'prs.note.other', 'prs.score.other',
  ]
  it.each(keys)('%s', (k) => {
    expect(TR[k], `TR ${k}`).toBeTruthy()
    expect(EN[k], `EN ${k}`).toBeTruthy()
  })
  it('öneri başlığı / gövdesi: kullanılan her yer tutucu için parametre var ve her parametre kullanılıyor (iki dil)', () => {
    for (const code of REC_CODES) {
      for (const part of ['title', 'body']) {
        const n = recommendationArgs({ code, params: {} }, TRL, part).length
        for (const dict of [TR, EN]) {
          const used = new Set([...String(dict[`prs.rec.${code}.${part}`]).matchAll(/\{(\d)\}/g)].map((m) => Number(m[1])))
          expect([...used].sort(), `${code}.${part}`).toEqual(Array.from({ length: n }, (_, i) => i))
        }
      }
    }
  })
  it('puan bileşeni etiketleri (errors → "açılmayan nesneler"; bilinmeyen → genel)', () => {
    expect(scoreLabelKey('errors')).toBe('prs.score.brokenObjects')
    expect(scoreLabelKey('load_time')).toBe('prs.score.load_time')
    expect(scoreLabelKey('yeni')).toBe('prs.score.other')
  })
})

describe('İngilizce tekil biçim (sayı tam 1 → `.one`)', () => {
  // Gerçek sözlükle biçimleyen t — useT ile aynı yer tutucu doldurma
  const tOf = (dict) => (k, ...a) => a.reduce((str, v, i) => str.split(`{${i}}`).join(String(v)), dict[k] ?? k)
  const tEn = tOf(EN)

  it('pluralKey: yalnız tam 1 tekil; 0, 2, boş, "1,5" çoğul', () => {
    expect(pluralKey('prs.x', 1)).toBe('prs.x.one')
    expect(pluralKey('prs.x', '1')).toBe('prs.x.one')
    for (const n of [0, 2, 1.5, null, undefined, '', 'abc']) expect(pluralKey('prs.x', n)).toBe('prs.x')
    expect(plural(t, 'prs.kpi.brokenSub', 1, '1')).toBe('prs.kpi.brokenSub.one(1)')
  })

  it('EN öneri başlığı: 1 büyük görsel tekil, 3 çoğul; gövde de tekile uyar', () => {
    const one = { code: 'LARGE_IMAGES', params: { count: 1, bytes: Math.round(1.6 * MB), limit_kb: 200 } }
    expect(recTextKey(one, 'title')).toBe('prs.rec.LARGE_IMAGES.title.one')
    expect(tEn(recTextKey(one, 'title'), ...recommendationArgs(one, ENL, 'title'))).toBe('1 large image (1.6 MB)')
    expect(tEn(recTextKey(one, 'body'), ...recommendationArgs(one, ENL, 'body'))).toMatch(/^This image is above the 200 KB limit\./)
    const many = { ...one, params: { ...one.params, count: 3 } }
    expect(tEn(recTextKey(many, 'title'), ...recommendationArgs(many, ENL, 'title'))).toBe('3 large images (1.6 MB)')
    const links = { code: 'BROKEN_LINKS', params: { count: 1 } }
    expect(tEn(recTextKey(links, 'title'), ...recommendationArgs(links, ENL, 'title'))).toBe('1 link does not work')
    // üçüncü taraf sunucu sayısı: sayı ya da eski liste biçimi
    expect(recTextKey({ code: 'THIRD_PARTY_HEAVY', params: { hosts: 1 } }, 'body')).toBe('prs.rec.THIRD_PARTY_HEAVY.body.one')
    expect(recTextKey({ code: 'THIRD_PARTY_HEAVY', params: { hosts: ['a.com'] } }, 'body')).toBe('prs.rec.THIRD_PARTY_HEAVY.body.one')
    expect(recTextKey({ code: 'THIRD_PARTY_HEAVY', params: { hosts: 3 } }, 'body')).toBe('prs.rec.THIRD_PARTY_HEAVY.body')
    expect(recTextKey({ code: 'OVERSIZED_PAGE', params: { count: 1 } }, 'title')).toBe('prs.rec.OVERSIZED_PAGE.title')   // tekil biçimi yok
    expect(recTextKey({ code: 'YENI', params: { count: 1 } }, 'title')).toBe('prs.rec.other.title')
  })

  it('EN kutucuk alt satırları: 1 nesne / görsel / belge / bağlantı tekil', () => {
    expect(plural(tEn, 'prs.kpi.brokenSub', 1, '1')).toBe('1 object did not load')
    expect(plural(tEn, 'prs.kpi.brokenSub', 2, '2')).toBe('2 objects did not load')
    expect(plural(tEn, 'prs.kpi.imagesCount', 1, '1')).toBe('1 image')
    const docs = tEn('prs.kpi.docsSub', plural(tEn, 'prs.kpi.docsPart', 1, '1', '2 MB'), plural(tEn, 'prs.kpi.linksPart', 7, '7'))
    expect(docs).toBe('1 document (2 MB) · 7 links · not counted in page size')
    expect(plural(tEn, 'prs.hero.lead', 1, '1 s', '20 KB', '1')).toBe('Estimated load 1 s · page size 20 KB · 1 request')
  })

  it('dışa aktarım aynı seçimi kullanır (öneri sayfası, tahmin notu)', () => {
    const recs = recommendationSheetRows([{ code: 'UNCOMPRESSED_TEXT', severity: 'medium', params: { count: 1, bytes: 640 * KB } }], tEn, ENL)
    expect(recs[1][1]).toBe('1 text file is sent uncompressed')
    expect(recs[1][2]).toMatch(/^Compressing this HTML, CSS or JavaScript file/)
    const sum = summarySheetRows({ name: 'X' }, { grade: 80 }, { timing: { parallel: 1 } }, tEn, ENL)
    expect(sum.find((r) => r[0] === EN['prs.export.estimateNote'])[1]).toBe(EN['prs.method.estimate.one'].replace('{0}', '1'))
  })

  it('her `.one` anahtarı iki dilde var, tabanı var ve tabanla aynı yer tutucuları kullanır', () => {
    const ph = (s) => [...String(s).matchAll(/\{(\d)\}/g)].map((m) => m[1]).sort().join(',')
    const ones = Object.keys(EN).filter((k) => k.startsWith('prs.') && k.endsWith('.one'))
    expect(ones.length).toBeGreaterThanOrEqual(40)
    for (const k of ones) {
      const base = k.slice(0, -4)
      for (const [name, dict] of [['TR', TR], ['EN', EN]]) {
        expect(dict[k], `${name} ${k}`).toBeTruthy()
        expect(dict[base], `${name} ${base}`).toBeTruthy()
        expect(ph(dict[k]), `${name} ${k}`).toBe(ph(dict[base]))
      }
    }
    // REC_PLURAL / NOTE_PLURAL'ın seçebildiği her metnin tekil biçimi var
    const needed = [
      ...Object.entries(REC_PLURAL).flatMap(([code, parts]) => Object.keys(parts).map((p) => `prs.rec.${code}.${p}.one`)),
      ...Object.keys(NOTE_PLURAL).map((c) => `prs.note.${c}.one`),
    ]
    for (const k of needed) expect(ones, k).toContain(k)
  })
})

describe('sınırlı (kısmi) ölçüm, sorun kodları ve izleme tavanı (2026-10-31 son inceleme)', () => {
  const tOf = (dict) => (k, ...a) => a.reduce((str, v, i) => str.split(`{${i}}`).join(String(v)), dict[k] ?? k)
  const tTr = tOf(TR)
  const tEn = tOf(EN)

  it('sınırlı koşunun notu asla başarı tonu almaz; tam ölçüm harf notunun tonunu alır', () => {
    expect(isCapped({ capped: true })).toBe(true)
    expect(isCapped({ capped: 'true' })).toBe(false)
    expect(isCapped(null)).toBe(false)
    expect(scanTone({ grade: 96, grade_letter: 'A', capped: true })).toBe('muted')
    expect(scanTone({ grade: 96, grade_letter: 'A', capped: false })).toBe('success')
    expect(scanTone({ grade: 40, capped: true })).toBe('muted')
    expect(scanTone({ grade: 40 })).toBe('danger')
    expect(scanTone(null)).toBe('muted')
  })

  it('fark yalnız iki tam ölçüm arasında: biri sınırlıysa ya da önceki yoksa gösterilmez', () => {
    expect(deltasComparable({ capped: false }, { capped: false })).toBe(true)
    expect(deltasComparable({ capped: true }, { capped: false })).toBe(false)
    expect(deltasComparable({ capped: false }, { capped: true })).toBe(false)
    expect(deltasComparable({ capped: false }, null)).toBe(false)
    expect(deltasComparable(null, { capped: false })).toBe(false)
  })

  it('grafik noktası sınırlı koşuyu işaretler; özet sayfası notun kısmi olduğunu yazar', () => {
    const pts = trendPoints([
      { id: 2, status: 'DONE', started_at: '2026-10-31T10:00:00', load_ms: 2000, page_bytes: MB, grade: 90, capped: true },
      { id: 1, status: 'DONE', started_at: '2026-10-30T10:00:00', load_ms: 2100, page_bytes: MB, grade: 88 },
      { id: 0, status: 'FAILED', started_at: '2026-10-29T10:00:00', capped: true },
    ])
    expect(pts.find((p) => p.id === 2).capped).toBe(true)
    expect(pts.find((p) => p.id === 1).capped).toBe(false)
    expect(pts.find((p) => p.id === 0).capped).toBe(false)   // tamamlanmamış koşu boşluk, "sınırlı" değil
    const sum = summarySheetRows({ name: 'X' }, { grade: 90, grade_letter: 'A', capped: true }, null, tTr, TRL)
    expect(sum.find((r) => r[0] === TR['prs.grade'])[1]).toBe(`90 / 100 (A) · ${TR['prs.grade.partial']}`)
    const full = summarySheetRows({ name: 'X' }, { grade: 90, grade_letter: 'A', capped: false }, null, tTr, TRL)
    expect(full.find((r) => r[0] === TR['prs.grade'])[1]).toBe('90 / 100 (A)')
  })

  it('sorun kodu çevrilir: BLOCKED güvenlik kuralı, bilinen kod kısa metni, TRANSPORT_ERROR / bilinmeyen genel metin', () => {
    expect(problemText('BLOCKED', tTr)).toBe('Güvenlik kuralı engelledi')
    expect(problemText('BLOCKED', tEn)).toBe('Blocked by a security rule')
    expect(problemText('READ_TIMEOUT', tEn)).toBe(EN['chkfail.READ_TIMEOUT.short'])
    expect(problemText('CONNECT_REFUSED', tTr)).toBe(TR['chkfail.CONNECT_REFUSED.short'])
    expect(problemText(' TLS_TRUST ', tEn)).toBe(EN['chkfail.TLS_TRUST.short'])
    expect(problemText('TRANSPORT_ERROR', tTr)).toBe('Bağlantı hatası')
    expect(problemText('TRANSPORT_ERROR', tEn)).toBe('Connection error')
    expect(problemText('YENI_BILINMEYEN_KOD', tEn)).toBe('Connection error')
    // adlı yer tutuculu kısa metinler ({status}, {broken} …) ve UNKNOWN nesne bağlamında anlamsız → genel metin
    for (const c of ['HTTP_STATUS', 'RESOURCES_BROKEN', 'RESOURCES_TIMEOUT', 'MIXED_CONTENT', 'UNKNOWN']) {
      expect(problemText(c, tEn), c).toBe('Connection error')
    }
    expect(problemText('', tEn)).toBe('')
    expect(problemText(null, tEn)).toBe('')
    expect(problemText(42, tEn)).toBe('')
  })

  it('Excel "sorun" sütunu ham kodu değil çevrilmiş metni yazar', () => {
    const rows = objectSheetRows([
      { url: 'https://ex.com/a.png', category: 'IMAGE', status: 0, error: 'BLOCKED' },
      { url: 'https://ex.com/b.png', category: 'IMAGE', status: 0, error: 'READ_TIMEOUT' },
      { url: 'https://ex.com/c.png', category: 'IMAGE', status: 0, error: 'TRANSPORT_ERROR' },
      { url: 'https://ex.com/d.png', category: 'IMAGE', status: 200, error: null },
    ], tEn, ENL)
    expect(rows.slice(1).map((r) => r[20])).toEqual([
      'Blocked by a security rule', EN['chkfail.READ_TIMEOUT.short'], 'Connection error', '',
    ])
    for (const r of rows.slice(1)) expect(r[20]).not.toMatch(/BLOCKED|READ_TIMEOUT|TRANSPORT_ERROR/)
  })

  it('izleme tavanı bu istemcinin başladığı andan ölçülür (sunucu saati karışmaz)', () => {
    expect(pollCapReached(1_000, 1_000 + 600_000, 600_000)).toBe(false)
    expect(pollCapReached(1_000, 1_000 + 600_001, 600_000)).toBe(true)
    expect(pollCapReached(5_000, 4_000, 600_000)).toBe(false)
    expect(pollCapReached(null, 10, 5)).toBe(false)
    expect(pollCapReached(0, 10, null)).toBe(false)
  })
})
