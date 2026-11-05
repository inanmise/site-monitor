import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  A11Y_CHECK_KEYS, AREA_STATE_TONE, AUDIT_AREAS, CACHE_BUCKETS, CATEGORY_SCORE_KEYS, CHECK_ISSUES, CHECK_STATUSES, COOKIE_ISSUES,
  DUPLICATE_KINDS, FINDING_CODES, FINDING_TONE, HEADER_ISSUES, HEADER_STATUSES, HINT_TYPES, HOST_SKIP_REASONS, IMAGE_ISSUE_KEYS,
  NETWORK_PROFILES, NOTE_ARGS, REC_AREA, REC_CODES, RENDER_BLOCKING_KINDS, RENDER_BLOCKING_REASONS, SCORE_KEYS, SECURITY_HEADER_KEYS,
  SECURITY_HEADER_NAMES, SEO_CHECK_KEYS, THIRD_PARTY_CATEGORIES, VULN_KINDS, VULN_SEVERITIES,
  areaData, areaOfRec, areaRecs, areaSummary, cachingView, categoryScoreRows, checkCounts, checkRows, cookieRows, duplicateRows,
  findingOfRec, fontView, hasDeepAnalysis, hasDeepItem, headerCounts, hintRows, hostRows, htmlStats, httpVersionText, imageIssueGroups,
  isHttp1, itemLibrary, libraryVersionRows, listOf, mixedContentRows, noteRows, parseUrlState, parseViewValue, profileRows,
  recItemCount, recommendationArgs, recFiltersByFinding, recsOfFinding, renderBlockingView, resourceScanLink, scoreLabelKey,
  sectionOfView, securityHeaderRows, sourceMapRows, sriRows, thirdPartyCategoryRows, trackingRows, unminifiedRows, urlStateValues,
  viewOf, vulnLibraryRows, vulnLink, withFindings,
} from '../components/page/resourcescan/resourceScanModel.js'
import { scanAnalysisV2, scanSummary } from './helpers/resourceScanFixtures.js'
import { CDN_JQ, KB, scanAnalysisV3, scanDetailV3, scanItemsV3 } from './helpers/resourceScanFixturesV3.js'

// Kaynak analizi — prs3 (algoritma 3) derin analiz paketlerinin SAF mantığı: eski analizde "yok" (asla "sorun yok"),
// kategori puanları, öneri ↔ bulgu ↔ alan eşlemeleri, A–E blok görünümleri, URL'deki denetim alanı ve dinamik sözlük
// anahtarlarının iki dilde varlığı. Sunucu sözleşmesi: scratchpad prs3-contract.md + be1 kod kataloğu prs3-codes.md.

const TRL = 'tr-TR'
const t = (k, ...a) => (a.length ? `${k}(${a.join('|')})` : k)
const tTr = (k, ...a) => a.reduce((s, v, i) => s.split(`{${i}}`).join(String(v)), TR[k] ?? k)
const v3 = () => scanDetailV3()

describe('derin analiz var mı / eski analiz', () => {
  it('algoritma 3 → var; 2 / 1 / analiz yok → yok (asla "sorun yok")', () => {
    const d = v3()
    expect(hasDeepAnalysis(d.analysis, d.scan)).toBe(true)
    expect(hasDeepAnalysis(scanAnalysisV2(), scanSummary(1, { algorithm_version: 2 }))).toBe(false)
    expect(hasDeepAnalysis(null, d.scan)).toBe(false)
    for (const a of AUDIT_AREAS) {
      expect(areaSummary(scanAnalysisV2(), scanSummary(1), a)).toEqual({ state: 'old', count: null })
      expect(AREA_STATE_TONE.old).toBe('muted')
    }
  })

  it('bölüm üretilmemişse "bu analizde yok" (missing), değerlendirilemeyen denetim "unknown" — ikisi de soluk', () => {
    const d = v3()
    const a = { ...d.analysis, waste: undefined, html_audit: { seo: { score: null, checks: [] }, accessibility: null } }
    expect(areaSummary(a, d.scan, 'waste')).toEqual({ state: 'missing', count: null })
    expect(areaSummary(a, d.scan, 'seo')).toEqual({ state: 'unknown', count: null })
    expect(areaSummary(a, d.scan, 'a11y')).toEqual({ state: 'missing', count: null })
    expect([AREA_STATE_TONE.missing, AREA_STATE_TONE.unknown]).toEqual(['muted', 'muted'])
  })

  it('alan özeti: öneri sayısı (performans / güvenlik), başarısız + uyarı (SEO / erişilebilirlik)', () => {
    const d = v3()
    expect(areaSummary(d.analysis, d.scan, 'loading')).toMatchObject({ state: 'issues', count: 3 })   // THIRD_PARTY_HEAVY, RENDER_BLOCKING_RESOURCES, MISSING_PRECONNECT
    expect(areaSummary(d.analysis, d.scan, 'security').count).toBe(5)
    expect(areaSummary(d.analysis, d.scan, 'seo')).toEqual({ state: 'issues', count: 4, fail: 1, warn: 3 })
    expect(areaSummary(d.analysis, d.scan, 'a11y')).toEqual({ state: 'issues', count: 4, fail: 1, warn: 3 })
    // önerisi olmayan alan, veri varsa "sorun yok"
    const clean = { ...d.analysis, recommendations: [] }
    expect(areaSummary(clean, d.scan, 'media')).toEqual({ state: 'ok', count: 0 })
    expect(AREA_STATE_TONE.ok).toBe('success')
  })

  it('her öneri alanı bilinen alanlardan biri; kırık / belge / ALL_GOOD bir alana bağlanmaz', () => {
    for (const area of Object.values(REC_AREA)) expect(AUDIT_AREAS).toContain(area)
    for (const c of ['BROKEN_RESOURCES', 'BROKEN_LINKS', 'LARGE_DOCUMENTS', 'ALL_GOOD', 'YENI']) expect(areaOfRec(c)).toBeNull()
    for (const c of Object.keys(REC_AREA)) expect(REC_CODES).toContain(c)
    const d = v3()
    expect(areaRecs(d.analysis, 'security').map((r) => r.code)).toEqual(
      ['VULNERABLE_LIBRARY', 'SECURITY_HEADERS_MISSING', 'WEAK_CSP', 'INSECURE_COOKIES', 'TRACKING_ON_LOAD'])
  })
})

describe('kategori puanları (pano)', () => {
  it('algoritma 3: dört puan; null puan "—" ve soluk (0 / 100 uydurulmaz)', () => {
    const d = v3()
    const rows = categoryScoreRows(d.scan, d.analysis)
    expect(rows.map((r) => r.key)).toEqual(CATEGORY_SCORE_KEYS)
    expect(rows.map((r) => [r.score, r.letter, r.tone, r.area])).toEqual([
      [72, 'C', 'warning', 'loading'], [58, 'E', 'danger', 'security'], [85, null, 'success', 'seo'], [null, null, 'muted', 'a11y'],
    ])
    expect(rows.every((r) => r.available)).toBe(true)
  })
  it('eski analiz: performans notu var, diğerleri "bu analizde yok" (old) ve soluk', () => {
    const rows = categoryScoreRows(scanSummary(1, { algorithm_version: 2 }), scanAnalysisV2())
    expect(rows[0]).toMatchObject({ key: 'performance', score: 72, letter: 'C', available: true })
    for (const r of rows.slice(1)) expect(r).toMatchObject({ old: true, available: false, score: null, tone: 'muted' })
  })
  it('özet kolonları yedek; sınırlı (kısmi) ölçümde performans ve güvenlik soluk, SEO etkilenmez', () => {
    const d = v3()
    const a = { ...d.analysis, category_scores: undefined }
    const rows = categoryScoreRows({ ...d.scan, capped: true }, a)
    expect(rows.find((r) => r.key === 'security')).toMatchObject({ score: 58, tone: 'muted', partial: true })
    expect(rows.find((r) => r.key === 'performance')).toMatchObject({ tone: 'muted', partial: true })
    expect(rows.find((r) => r.key === 'seo')).toMatchObject({ score: 85, tone: 'success', partial: false })
  })
})

describe('öneri ↔ bulgu ↔ nesne', () => {
  it('öneri satırındaki `finding` esastır; yoksa statik eşleme; sayfa düzeyi null', () => {
    expect(findingOfRec({ code: 'RENDER_BLOCKING_RESOURCES', finding: 'RENDER_BLOCKING' })).toBe('RENDER_BLOCKING')
    expect(findingOfRec({ code: 'MISSING_PRECONNECT', finding: null })).toBeNull()
    expect(findingOfRec({ code: 'UNMINIFIED_ASSETS' })).toBe('UNMINIFIED')
    expect(findingOfRec('IMAGES_WITHOUT_DIMENSIONS')).toBe('MISSING_DIMENSIONS')
    expect(findingOfRec('OFFSCREEN_NOT_LAZY')).toBe('NOT_LAZY')
    expect(findingOfRec('DUPLICATE_LIBRARIES')).toBeNull()      // be1: kütüphane çok sürümü nesneye yazılmaz
    expect(findingOfRec('LARGE_IMAGES')).toBe('LARGE_IMAGES')
    expect(findingOfRec({ code: 'X', finding: 'BILINMEYEN' })).toBeNull()
    expect(recFiltersByFinding({ code: 'WEAK_CSP', finding: null })).toBe(false)
    expect(recsOfFinding('DUPLICATE')).toEqual(['DUPLICATE_RESOURCES'])
    expect(recsOfFinding('VULNERABLE_LIBRARY')).toEqual(['VULNERABLE_LIBRARY'])
  })
  it('nesne bulguları: yeni kodlar sunucudan geçer; öneri sayısı bulgu eşlemesiyle', () => {
    const items = withFindings(scanItemsV3(), scanAnalysisV3().recommendations)
    const jq = items.find((it) => it.url === CDN_JQ)
    expect(jq.findings).toEqual(['VULNERABLE_LIBRARY', 'MISSING_SRI', 'RENDER_BLOCKING', 'DUPLICATE'])
    expect(recItemCount({ code: 'RENDER_BLOCKING_RESOURCES', finding: 'RENDER_BLOCKING' }, items)).toBe(3)
    expect(recItemCount({ code: 'DUPLICATE_RESOURCES' }, items)).toBe(2)
    for (const c of FINDING_CODES) expect(FINDING_TONE[c], c).toBeTruthy()
  })
  it('öneri parametreleri: başlık anahtarları teknik adla, CSP / kategori / önem `t` ile çevrilir — ham kod gösterilmez', () => {
    const sh = { code: 'SECURITY_HEADERS_MISSING', params: { count: 4, keys: ['csp', 'hsts', 'coop', 'corp'] } }
    expect(recommendationArgs(sh, TRL, 'body')).toEqual(['Content-Security-Policy, Strict-Transport-Security, Cross-Origin-Opener-Policy +1'])
    const csp = { code: 'WEAK_CSP', params: { issues: ['unsafe_inline', 'bilinmeyen'] } }
    expect(recommendationArgs(csp, TRL, 'body', tTr)[0]).toMatch(/^'unsafe-inline' satır içi/)
    expect(recommendationArgs(csp, TRL, 'body', tTr)[0]).toMatch(/Başlıkta bir sorun var/)
    expect(recommendationArgs(csp, TRL, 'body')).toEqual(['—'])          // t yok → ham kod değil
    const tr = { code: 'TRACKING_ON_LOAD', params: { count: 2, categories: ['analytics', 'session_replay'] } }
    expect(recommendationArgs(tr, TRL, 'body', tTr)).toEqual(['Analitik, Oturum kaydı'])
    const vl = { code: 'VULNERABLE_LIBRARY', params: { count: 1, name_list: ['jquery 1.12.4'], max_severity: 'high' } }
    expect(recommendationArgs(vl, TRL, 'body', tTr)).toEqual(['jquery 1.12.4', 'Yüksek'])
    const rb = { code: 'RENDER_BLOCKING_RESOURCES', params: { count: 3, bytes: 855 * KB, est_first_paint_ms: 1640 } }
    expect(recommendationArgs(rb, TRL, 'body')).toEqual(['855 KB', '1,64 sn'])
  })
})

describe('A — yükleme ve ağ', () => {
  it('çizimi engelleyenler: bayta göre, bilinmeyen tür / neden genel; ilk boyama tahmini', () => {
    const v = renderBlockingView({ render_blocking: { items: [{ url: 'a.css', kind: 'x', reason: 'y', bytes: 1 }, { url: 'b.js', kind: 'script', reason: 'no_async_defer', bytes: 9 }],
      count: 2, bytes: 10, est_first_paint_ms: 900 } })
    expect(v.rows.map((r) => [r.url, r.kind, r.reason])).toEqual([['b.js', 'script', 'no_async_defer'], ['a.css', 'other', 'other']])
    expect([v.count, v.bytes, v.firstPaintMs, v.more]).toEqual([2, 10, 900, 0])
    expect(renderBlockingView({})).toBeNull()
    expect(RENDER_BLOCKING_KINDS).toContain('css_import')
  })
  it('ipuçları: eksik → kullanılmayan → bilinmeyen → kullanılan → bilgi', () => {
    const rows = hintRows(v3().analysis.loading)
    expect(rows.map((r) => `${r.type}:${r.state}`)).toEqual([
      'missing_preconnect:missing', 'preload:unused', 'preconnect:unused', 'preload:used', 'dns_prefetch:used', 'fetchpriority:info',
    ])
    expect(hintRows({ hints: { preload: [{ url: 'x', used: null }] } })[0].state).toBe('unknown')
    expect(hintRows({})).toBeNull()
    expect(HINT_TYPES).toHaveLength(5)
  })
  it('sunucular: istek sayısına göre; ölçülmeyen host nedenli ve toplamsız; HTTP sürümü metni', () => {
    const v = hostRows(v3().analysis.loading)
    expect(v.rows.map((r) => r.host)[0]).toBe('www.example.com')
    expect(v.rows[0]).toMatchObject({ totalMs: 61, httpVersion: 'HTTP/2', measured: true, reason: null })
    expect(v.rows.find((r) => r.reason === 'proxy')).toMatchObject({ measured: false, totalMs: null, httpVersion: 'HTTP/1.1' })
    expect(v.total).toBe(4)
    expect(httpVersionText('1.1')).toBe('HTTP/1.1')
    expect(httpVersionText(2)).toBe('HTTP/2')
    expect(httpVersionText('3')).toBe('HTTP/3')
    expect(httpVersionText('')).toBeNull()
    expect(httpVersionText('spdy')).toBeNull()
    expect(isHttp1('1.1')).toBe(true)
    expect(isHttp1('2')).toBe(false)
  })
  it('ağ profilleri: sabit sıra, bilinmeyen profil atılır, çubuk en yavaşa göre', () => {
    const rows = profileRows({ profiles: [{ key: '3g', load_ms: 5000 }, { key: 'fiber', load_ms: 500 }, { key: '5g', load_ms: 1 }] })
    expect(rows.map((r) => r.key)).toEqual(['fiber', '3g'])
    expect(rows.map((r) => Math.round(r.bar))).toEqual([10, 100])
    expect(NETWORK_PROFILES).toEqual(['fiber', '4g', '3g', 'slow3g'])
    expect(profileRows({})).toBeNull()
  })
})

describe('B — israf ve önbellek', () => {
  it('yinelenenler, kütüphane sürümleri, küçültülmemiş, kaynak haritaları (açık olan önce)', () => {
    const w = v3().analysis.waste
    expect(duplicateRows(w).rows.map((r) => r.kind)).toEqual(['same_content', 'same_path'])
    expect(libraryVersionRows(w).rows[0]).toMatchObject({ name: 'jquery', versions: ['1.12.4', '3.5.1'] })
    expect(unminifiedRows(w).rows[0].saving).toBe(210 * KB)
    const maps = sourceMapRows({ source_maps: [{ url: 'a', exposed: false }, { url: 'b', exposed: null }, { url: 'c', exposed: true }] })
    expect(maps.rows.map((r) => r.url)).toEqual(['c', 'b', 'a'])
    expect(duplicateRows({})).toBeNull()
    expect(DUPLICATE_KINDS).toEqual(['same_content', 'same_path'])
  })
  it('HTML istatistikleri ve önbellek kovaları (pay), CDN, ikinci ziyaret', () => {
    const w = v3().analysis.waste
    expect(htmlStats(w)).toMatchObject({ domNodes: 1850, maxDepth: 24, dataUriCount: 3 })
    const c = cachingView(w)
    expect(c.buckets.map((b) => b.key)).toEqual(CACHE_BUCKETS)
    expect(c.total).toBe(39)
    expect(Math.round(c.buckets.find((b) => b.key === 'lt_1h').share)).toBe(82)
    expect(c.cdn).toEqual({ hit: 1, miss: 0, unknown: 38 })
    expect([c.noValidator, c.repeatPct]).toEqual([5, 76.5])
    expect(c.versionedShort.rows[0].ttlS).toBe(600)
    expect(cachingView({})).toBeNull()
  })
})

describe('C — görsel ve yazı tipi', () => {
  it('görsel grupları: toplam > gönderilen ise "+N daha"; geç yüklenmeyen SEZGİSEL', () => {
    const g = imageIssueGroups(v3().analysis.media)
    expect(g.map((x) => x.key)).toEqual(IMAGE_ISSUE_KEYS)
    const md = g.find((x) => x.key === 'missing_dimensions')
    expect([md.rows.length, md.total, md.more]).toEqual([50, 60, 10])
    expect(g.find((x) => x.key === 'not_lazy').heuristic).toBe(true)
    expect(g.find((x) => x.key === 'animated_gif').rows[0].frames).toBe(48)
  })
  it('yazı tipleri: aileler dosya sayısına göre, bilinmeyen biçim `unknown`', () => {
    const f = fontView(v3().analysis.media)
    expect(f.families.rows.map((r) => r.files)).toEqual([3, 1])
    expect(f.nonWoff2.rows[0].format).toBe('ttf')
    expect(fontView({ fonts: { non_woff2: [{ url: 'x', format: 'pfb' }] } }).nonWoff2.rows[0].format).toBe('unknown')
    expect(fontView({})).toBeNull()
  })
})

describe('D — güvenlik ve uyumluluk', () => {
  it('başlıklar sözleşme sırasıyla, bilinmeyen sonda; durum sayıları', () => {
    const rows = securityHeaderRows({ headers: [{ key: 'yeni', status: 'ok' }, { key: 'corp', status: 'ok' }, { key: 'csp', status: 'weak', issue: 'unsafe_inline' }] })
    expect(rows.map((r) => [r.key, r.known])).toEqual([['csp', true], ['corp', true], ['yeni', false]])
    expect(headerCounts(securityHeaderRows(v3().analysis.security))).toEqual({ ok: 3, weak: 2, missing: 3, unknown: 0 })
    for (const k of SECURITY_HEADER_KEYS) expect(SECURITY_HEADER_NAMES[k], k).toBeTruthy()
    expect(securityHeaderRows({})).toBeNull()
  })
  it('çerezler: sorunlu önce, DEĞER alanı hiç taşınmaz', () => {
    const v = cookieRows({ cookies: [{ name: 'a', issues: [] }, { name: 'z', issues: ['not_secure'], value: 'GIZLI' }] })
    expect(v.rows.map((r) => r.name)).toEqual(['z', 'a'])
    expect(JSON.stringify(v)).not.toContain('GIZLI')
  })
  it('kütüphaneler: açıklı önce (en yüksek öneme göre), açıklar öneme göre; tür kodu', () => {
    const v = vulnLibraryRows(v3().analysis.security)
    expect(v.rows.map((r) => [r.version, r.vulns.length])).toEqual([['1.12.4', 2], ['3.5.1', 0]])
    expect(v.rows[0].vulns.map((x) => x.kind)).toEqual(['prototype_pollution', 'xss'])
    expect(v.catalogDate).toBe('2026-10-15')
    const s = vulnLibraryRows({ libraries: [{ name: 'x', vulns: [{ id: 'A', severity: 'low' }, { id: 'B', severity: 'critical', kind: 'yeni' }] }] })
    expect(s.rows[0].vulns.map((x) => [x.id, x.kind])).toEqual([['B', 'other'], ['A', null]])
    expect(VULN_SEVERITIES[0]).toBe('critical')
  })
  it('açık bağlantısı yalnız CVE / GHSA biçiminde; uzunluk önce denetlenir', () => {
    expect(vulnLink('CVE-2020-11022')).toBe('https://nvd.nist.gov/vuln/detail/CVE-2020-11022')
    expect(vulnLink('cve-2019-11358')).toBe('https://nvd.nist.gov/vuln/detail/CVE-2019-11358')
    expect(vulnLink('GHSA-gxr4-xjj5-5px2')).toBe('https://github.com/advisories/GHSA-gxr4-xjj5-5px2')
    for (const bad of ['javascript:alert(1)', 'CVE-20-1', '', null, `CVE-2020-${'1'.repeat(60)}`]) expect(vulnLink(bad)).toBeNull()
  })
  it('üçüncü taraf kategorileri (bayta göre, izleme işaretli), sayfa açılışı izleme, SRI, karışık içerik', () => {
    const s = v3().analysis.security
    const tp = thirdPartyCategoryRows(s)
    expect(tp.rows.map((r) => [r.category, r.tracking])).toEqual([['cdn', false], ['analytics', true], ['advertising', true]])
    expect(thirdPartyCategoryRows({ third_party_categories: [{ category: 'uzay', hosts: [] }] }).rows[0].category).toBe('other')
    expect(trackingRows(s).rows.map((r) => r.category)).toEqual(['analytics', 'advertising'])
    expect(sriRows(s).rows[0]).toEqual({ url: CDN_JQ, kind: 'script' })
    expect(mixedContentRows(s).rows).toHaveLength(1)
    expect(THIRD_PARTY_CATEGORIES).toHaveLength(14)
  })
})

describe('E — SEO + erişilebilirlik', () => {
  it('denetim satırları: başarısız → uyarı → geçti; bilinmeyen anahtar / durum sonda; sorun kodu', () => {
    const rows = checkRows(v3().analysis.html_audit.seo, 'seo')
    expect(rows.map((r) => r.status)).toEqual(['fail', 'warn', 'warn', 'warn', 'pass', 'pass', 'pass', 'pass', 'pass'])
    expect(rows[0]).toMatchObject({ key: 'meta_description', issue: 'missing', known: true })
    const odd = checkRows({ checks: [{ key: 'yeni', status: 'pass' }, { key: 'title', status: '???', issue: 'acayip' }] }, 'seo')
    expect(odd.map((r) => [r.key, r.status, r.known, r.issue])).toEqual([['yeni', 'pass', false, null], ['title', 'unknown', true, null]])
  })
  it('örnekler en çok 5 ve 80 karakter; sayılar', () => {
    const long = 'x'.repeat(200)
    const rows = checkRows({ checks: [{ key: 'img_alt', status: 'fail', count: 9, samples: ['a', 'b', 'c', 'd', 'e', 'f', long] }] }, 'a11y')
    expect(rows[0].samples).toHaveLength(5)
    expect(checkRows({ checks: [{ key: 'img_alt', status: 'fail', samples: [long] }] }, 'a11y')[0].samples[0]).toHaveLength(81)
    expect(checkCounts(v3().analysis.html_audit.accessibility)).toEqual({ pass: 3, warn: 3, fail: 1, unknown: 0, total: 7 })
    expect(checkRows(null, 'seo')).toBeNull()
  })
})

describe('nesne ayrıntısı + listeler + URL', () => {
  it('derin alanlar: anahtar varsa (değer null olsa bile) var; kütüphane', () => {
    const items = scanItemsV3()
    expect(hasDeepItem(items[0])).toBe(true)
    expect(hasDeepItem({ url: 'x' })).toBe(false)
    expect(itemLibrary(items.find((it) => it.url === CDN_JQ))).toEqual({ name: 'jquery', version: '1.12.4' })
    expect(itemLibrary({ library: { version: '1' } })).toBeNull()
  })
  it('liste + toplam: dizi yoksa null (bilinmiyor), toplam ≥ uzunluk, en çok 50', () => {
    expect(listOf({}, 'x')).toBeNull()
    expect(listOf({ x: [1, 2], x_total: 9 }, 'x')).toEqual({ rows: [1, 2], total: 9, more: 7 })
    expect(listOf({ x: Array.from({ length: 70 }, (_, i) => i) }, 'x')).toMatchObject({ total: 70, more: 20 })
  })
  it('denetim görünümü URL\'de `prs_v=audit[-<alan>]`; varsayılan alan yazılmaz', () => {
    expect(parseViewValue('audit')).toEqual({ view: 'audit', area: 'loading' })
    expect(parseViewValue('audit-security')).toEqual({ view: 'audit', area: 'security' })
    expect(parseViewValue('audit-bilinmeyen')).toBeNull()
    expect(parseUrlState('?prs=12&prs_v=audit-a11y')).toEqual({ scanId: 12, view: 'audit', otherId: null, area: 'a11y' })
    expect(urlStateValues({ scanId: 12, view: 'audit', area: 'seo' })).toEqual({ prs: 12, prs_v: 'audit-seo', prs_b: null })
    expect(urlStateValues({ scanId: 12, view: 'audit', area: 'loading' }).prs_v).toBe('audit')
    expect(urlStateValues({ scanId: 12, view: 'objects', area: 'seo' }).prs_v).toBe('objects')
    expect(resourceScanLink({ origin: 'https://sm.example', monitorId: 1, scanId: 3, view: 'audit', area: 'waste' }))
      .toBe('https://sm.example/?tab=page&monitor=1&mtab=resources&prs=3&prs_v=audit-waste')
    expect(sectionOfView('audit')).toBe('audit')
    expect(viewOf('audit', 'list', 'list')).toBe('audit')
  })
  it('yeni ölçüm notları biçimlenir (iki parametreli, tekil)', () => {
    const rows = noteRows([{ code: 'HOSTS_NOT_MEASURED', params: { count: 1, limit: 30 } }, { code: 'JS_COOKIES_NOT_SEEN' }], TRL)
    expect(rows.map((r) => [r.key, r.args])).toEqual([['prs.note.HOSTS_NOT_MEASURED.one', ['1', '30']], ['prs.note.JS_COOKIES_NOT_SEEN', []]])
  })
  it('areaData eski yapıda null', () => {
    expect(areaData({}, 'seo')).toBeNull()
    expect(areaData(v3().analysis, 'a11y')).toBeTruthy()
  })
})

describe('sözlük kapısı — prs3 dinamik anahtarlar iki dilde var', () => {
  const ph = (s) => [...String(s).matchAll(/\{(\d)\}/g)].map((m) => m[1]).sort().join(',')
  const keys = [
    'prs.sec.audit', scoreLabelKey('render_blocking'), 'prs.score.measured.render_blocking',
    ...AUDIT_AREAS.flatMap((a) => [`prs.deep.area.${a}`, `prs.deep.area.${a}.desc`]),
    ...['issues', 'ok', 'unknown', 'missing', 'old'].map((s) => `prs.deep.state.${s}`), 'prs.deep.state.issues.one',
    ...CATEGORY_SCORE_KEYS.map((k) => `prs.deep.cs.${k}`),
    ...[...RENDER_BLOCKING_KINDS, 'other'].map((k) => `prs.deep.rb.kind.${k}`), ...[...RENDER_BLOCKING_REASONS, 'other'].map((k) => `prs.deep.rb.reason.${k}`),
    ...HINT_TYPES.map((k) => `prs.deep.hints.type.${k}`), ...['missing', 'unused', 'used', 'info', 'unknown'].map((k) => `prs.deep.hints.state.${k}`),
    ...[...HOST_SKIP_REASONS, 'other'].map((k) => `prs.deep.hosts.reason.${k}`), ...NETWORK_PROFILES.map((k) => `prs.deep.profiles.${k}`),
    ...[...DUPLICATE_KINDS, 'other'].map((k) => `prs.deep.dup.kind.${k}`), ...CACHE_BUCKETS.map((k) => `prs.deep.cache.bucket.${k}`),
    ...IMAGE_ISSUE_KEYS.flatMap((k) => [`prs.deep.img.${k}`, `prs.deep.img.${k}.desc`]),
    ...[...HEADER_STATUSES, 'unknown'].map((k) => `prs.deep.sec.status.${k}`),
    ...[...SECURITY_HEADER_KEYS, 'other'].flatMap((k) => [`prs.check.header.${k}`, `prs.check.header.${k}.why`]),
    ...[...HEADER_ISSUES, 'other'].map((k) => `prs.check.headerIssue.${k}`), ...['script', 'stylesheet', 'other'].map((k) => `prs.deep.sec.sriKind.${k}`),
    ...[...COOKIE_ISSUES, 'other'].map((k) => `prs.check.cookieIssue.${k}`), ...[...VULN_SEVERITIES, 'unknown'].map((k) => `prs.deep.sec.sev.${k}`),
    ...[...VULN_KINDS, 'other'].map((k) => `prs.deep.sec.vulnKind.${k}`), ...THIRD_PARTY_CATEGORIES.map((k) => `prs.deep.tpc.${k}`),
    ...[...CHECK_STATUSES, 'unknown'].map((k) => `prs.check.status.${k}`), ...CHECK_ISSUES.map((k) => `prs.check.issue.${k}`),
    ...[...SEO_CHECK_KEYS, 'other'].flatMap((k) => [`prs.check.seo.${k}`, `prs.check.seo.${k}.why`]),
    ...[...A11Y_CHECK_KEYS, 'other'].flatMap((k) => [`prs.check.a11y.${k}`, `prs.check.a11y.${k}.why`]),
    ...['etag', 'last_modified', 'age', 'cdn_cache', 'alt_svc'].map((h) => `prs.od.h.${h}`),
    ...['seo', 'a11y'].flatMap((n) => [`prs.deep.${n}.scoreTitle`, `prs.deep.${n}.scoreDesc`, `prs.deep.${n}.checksTitle`]),
    ...['estimate', 'heuristic', 'estimateHint', 'heuristicHint'].map((k) => `prs.deep.kind.${k}`),
    ...['minifiedYes', 'minifiedNo', 'integrityYes', 'integrityNo', 'renderBlockingYes', 'renderBlockingNo'].map((k) => `prs.od.${k}`),
    ...['serverMore', 'recsCount', 'cs.findings', 'hosts.summary', 'hosts.http1', 'hints.missingDetail', 'img.frames', 'sec.missingCount',
      'sec.weakCount', 'sec.okCount', 'sec.vulnCount', 'sec.trackingMay', 'checks.failCount', 'checks.warnCount', 'checks.passCount', 'checks.count']
      .flatMap((k) => [`prs.deep.${k}`, `prs.deep.${k}.one`]),
  ]
  it.each(keys)('%s', (k) => {
    expect(TR[k], `TR ${k}`).toBeTruthy()
    expect(EN[k], `EN ${k}`).toBeTruthy()
    expect(ph(TR[k]), `yer tutucular ${k}`).toBe(ph(EN[k]))
  })
  it('yeni öneri / not / bulgu kodları gerçekten kayıtlı (be1 kataloğu)', () => {
    for (const c of ['RENDER_BLOCKING_RESOURCES', 'MISSING_PRECONNECT', 'UNUSED_PRELOAD', 'UNUSED_PRECONNECT', 'MANY_HOSTS', 'HTTP1_ONLY',
      'DUPLICATE_RESOURCES', 'DUPLICATE_LIBRARIES', 'UNMINIFIED_ASSETS', 'SOURCE_MAP_EXPOSED', 'HEAVY_HTML', 'LARGE_DOM', 'INLINE_DATA_URI',
      'SHORT_CACHE_VERSIONED', 'NO_VALIDATOR', 'IMAGES_WITHOUT_DIMENSIONS', 'MISSING_SRCSET', 'OFFSCREEN_NOT_LAZY', 'ANIMATED_GIF',
      'FONT_DISPLAY_MISSING', 'TOO_MANY_FONTS', 'NON_WOFF2_FONTS', 'SECURITY_HEADERS_MISSING', 'WEAK_CSP', 'MISSING_SRI', 'MIXED_CONTENT',
      'INSECURE_COOKIES', 'VULNERABLE_LIBRARY', 'TRACKING_ON_LOAD']) {
      expect(REC_CODES, c).toContain(c)
      expect(areaOfRec(c), c).toBeTruthy()
    }
    for (const c of ['RENDER_BLOCKING', 'DUPLICATE', 'UNMINIFIED', 'SOURCE_MAP_EXPOSED', 'NO_VALIDATOR', 'MISSING_DIMENSIONS', 'NOT_LAZY',
      'ANIMATED_GIF', 'FONT_DISPLAY_MISSING', 'MISSING_SRI', 'VULNERABLE_LIBRARY', 'MIXED_CONTENT']) expect(FINDING_CODES, c).toContain(c)
    for (const c of ['JS_COOKIES_NOT_SEEN', 'CONTRAST_NOT_CHECKED', 'ASSET_SAMPLES_UNAVAILABLE', 'HOSTS_NOT_MEASURED', 'SOURCE_MAPS_NOT_CHECKED']) {
      expect(Object.keys(NOTE_ARGS), c).toContain(c)
    }
    expect(SCORE_KEYS).toContain('render_blocking')
  })
  it('hiçbir metinde ham kod / çevrilmemiş anahtar yok (gerçek sözlükle biçimlenen örnek)', () => {
    const d = v3()
    for (const r of d.analysis.recommendations) {
      for (const part of ['title', 'body']) {
        const text = tTr(`prs.rec.${r.code}.${part}`, ...recommendationArgs(r, TRL, part, tTr))
        expect(text, `${r.code}.${part}`).not.toMatch(/\{\d\}|prs\.|undefined|null/)
      }
    }
    expect(t('x')).toBe('x')
  })
})
