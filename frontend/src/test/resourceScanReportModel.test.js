import { describe, it, expect } from 'vitest'
import {
  BUDGET_FIELDS, BUDGET_KEYS, CATEGORY_SCORE_KEYS, algoChanges, budgetActual, budgetBand, budgetChanges, budgetErrorField, budgetExportRows,
  budgetFormFrom, budgetStatusOf, categoryBytesChart, categoryScoreRows, categoryScoreText, checkLabel, compareExportRows, compareRows,
  deepSheets, defaultSitePages, executiveSections, executiveSheetRows, formatBudgetActual, formatBudgetLimit, growthRows, hasSeries,
  isBudgetUnsupported, isSiteScan, isTrendUnsupported, normalizeBudget, pageStatus, parseBudgetForm, priorityActions, recShortLabel,
  scanMarkerScores, seriesSummary, siteLimits, siteModel, sitePageChoices, sitePageSheetRows, siteScopeIgnored, siteSharedSheetRows,
  sortSitePages, trendSeries, sectionTabCols, OBJECT_DEEP_COLUMNS, httpVersionLabel, objectDeepColumns, tpCategoryLabel, withObjectDeepColumns,
  compareNotes, isEndpointUnsupported, siteRowText, trendDirection,
} from '../components/page/resourcescan/resourceScanReportModel.js'
import { runProgressPct } from '../components/page/resourcescan/resourceScanModel.js'
import { KB, MB, budgetEval, deepAnalysis, deepDetail, siteBlock, trendData } from './helpers/resourceScanFgFixtures.js'
import { scanAnalysis, scanSummary } from './helpers/resourceScanFixtures.js'
import { TR } from '../i18n/tr.js'

// Kaynak analizi prs3 (fe2) — bütçe / eğilim / site / yönetici raporu SAF modeli. Metinler gerçek TR sözlüğünden (eksik
// anahtar anahtarın kendisini döner — "ham anahtar" sızıntısı testte görünür).
const t = (k, ...a) => {
  const v = TR[k]
  if (typeof v !== 'string') return k
  return v.replace(/\{(\d+)\}/g, (_, i) => (a[Number(i)] === undefined ? `{${i}}` : String(a[Number(i)])))
}
const L = 'tr-TR'

describe('F1 bütçe — okuma, form, fark', () => {
  it('sunucu gövdesi: düz alanlar ya da { budget }; kesirli / bozuk değer null; can_edit yalnız boolean', () => {
    expect(normalizeBudget({ max_page_kb: 2048, min_grade: '80', max_requests: 1.5, can_edit: true }))
      .toEqual({ values: { max_page_kb: 2048, max_requests: null, max_js_kb: null, max_image_kb: null, max_load_ms: null, min_grade: 80, min_security_score: null }, canEdit: true, known: true, fields: true })
    expect(normalizeBudget({ budget: { max_load_ms: 3000 }, can_edit: 'evet' }).values.max_load_ms).toBe(3000)
    expect(normalizeBudget({ budget: { max_load_ms: 3000 }, can_edit: 'evet' }).canEdit).toBeNull()
    // tanınmayan gövde: okunamadı (sınır yok diye GÖSTERİLMEZ)
    expect(normalizeBudget([]).known).toBe(false)
    expect(normalizeBudget(null).known).toBe(false)
    expect(normalizeBudget({ saved: true }).fields).toBe(false)
    expect(normalizeBudget({ max_page_kb: null }).fields).toBe(true)
  })

  it('form doğrulaması: boş = sınır yok; tamsayı ve aralık (sunucuyla aynı); ayraçlı sayı reddedilir', () => {
    const r = parseBudgetForm({ max_page_kb: ' 2048 ', max_requests: '4', max_js_kb: '1.024', min_grade: '101', max_load_ms: '', min_security_score: '0' })
    expect(r.values).toMatchObject({ max_page_kb: 2048, max_load_ms: null, min_security_score: 0, max_image_kb: null })
    expect(r.errors).toEqual({ max_requests: { code: 'range', args: [5, 2000] }, max_js_kb: { code: 'int', args: [] }, min_grade: { code: 'range', args: [0, 100] } })
    for (const f of BUDGET_FIELDS) {
      expect(parseBudgetForm({ [f.key]: String(f.min) }).errors[f.key]).toBeUndefined()
      expect(parseBudgetForm({ [f.key]: String(f.max) }).errors[f.key]).toBeUndefined()
      expect(parseBudgetForm({ [f.key]: String(f.max + 1) }).errors[f.key]?.code).toBe('range')
    }
  })

  it('yalnız değişen alanlar gönderilir; sınırı kaldırmak null; değişiklik yoksa boş', () => {
    const base = { ...Object.fromEntries(BUDGET_KEYS.map((k) => [k, null])), max_page_kb: 2048, min_grade: 80 }
    expect(budgetChanges({ ...base }, base)).toEqual({})
    expect(budgetChanges({ ...base, max_page_kb: null, max_load_ms: 3000 }, base)).toEqual({ max_page_kb: null, max_load_ms: 3000 })
    expect(budgetFormFrom(base)).toMatchObject({ max_page_kb: '2048', max_requests: '', min_grade: '80' })
  })

  it('eski sunucu: 404 / 405; sunucu alan hatası yalnız bilinen bütçe alanıysa', () => {
    expect(isBudgetUnsupported({ status: 404, code: 'NOT_FOUND' })).toBe(true)
    expect(isBudgetUnsupported({ status: 405 })).toBe(true)
    expect(isBudgetUnsupported({ status: 500 })).toBe(false)
    expect(budgetErrorField({ field: 'max_js_kb' })).toBe('max_js_kb')
    expect(budgetErrorField({ field: 'teamId' })).toBeNull()
  })

  it('sınır ve gerçek değer metinleri birimiyle; gerçek değer ÖNCE analiz özetinden (bayt alanı bayt)', () => {
    expect(formatBudgetLimit('max_page_kb', 2048, L)).toBe('2 MB')
    expect(formatBudgetLimit('max_load_ms', 3000, L)).toBe('3 sn')
    expect(formatBudgetLimit('min_grade', 80, L)).toBe('80 / 100')
    expect(formatBudgetLimit('max_requests', null, L)).toBe('—')
    const scan = scanSummary(1)
    expect(budgetActual('max_page_kb', scan, { actual: 1 })).toEqual({ value: scan.page_bytes, kind: 'bytes' })
    // özet satırında yoksa sunucunun alan birimindeki değeri (KB)
    expect(formatBudgetActual('max_js_kb', budgetActual('max_js_kb', scan, { actual: 640 }), L)).toBe('640 KB')
    expect(budgetActual('min_security_score', scan, { actual: null })).toBeNull()
  })

  it('bant: aşılan > 0 → exceeded; aşılan yok ama ölçülemeyen → partial (yeşil DEĞİL); tanımsız → none; alan yoksa çizilmez', () => {
    const scan = scanSummary(1, { grade: 72 })
    const band = budgetBand(budgetEval(), scan)
    expect(band.state).toBe('exceeded')
    expect(band.exceeded).toBe(2)
    expect(band.unknown).toBe(1)
    expect(band.rows.map((r) => r.key)).toEqual(['max_page_kb', 'max_requests', 'min_grade', 'min_security_score'])
    // Ölçülemeyen kalem: özet satırında puan olsa da "ölçülen" yazılmaz (rozet "Ölçülemedi" ile çelişmesin)
    const unk = budgetBand(budgetEval(), scanSummary(1, { grade: 72, security_score: 58 })).rows.find((r) => r.key === 'min_security_score')
    expect(unk.ok).toBeNull()
    expect(unk.actual).toBeNull()
    const okRow = budgetBand(budgetEval(), scanSummary(1, { grade: 72 })).rows.find((r) => r.key === 'min_grade')
    expect(okRow.actual).toEqual({ value: 72, kind: 'score' })
    const partial = budgetBand({ defined: true, checks: [{ key: 'max_requests', limit: 80, ok: true }, { key: 'min_grade', limit: 80, ok: null }] }, scan)
    expect(partial.state).toBe('partial')
    expect(budgetBand({ defined: true, checks: [{ key: 'max_requests', limit: 80, ok: true }] }, scan).state).toBe('ok')
    expect(budgetBand({ defined: false, checks: [] }, scan).state).toBe('none')
    expect(budgetBand(undefined, scan)).toBeNull()
  })

  it('geçmiş işareti: none / alan yok → işaret yok; bilinmeyen değer "unknown" (asla "geçti")', () => {
    expect(budgetStatusOf({ budget_status: 'ok' })).toBe('ok')
    expect(budgetStatusOf({ budget_status: 'exceeded' })).toBe('exceeded')
    expect(budgetStatusOf({ budget_status: 'unknown' })).toBe('unknown')
    expect(budgetStatusOf({ budget_status: 'tuhaf' })).toBe('unknown')
    expect(budgetStatusOf({ budget_status: 'none' })).toBeNull()
    expect(budgetStatusOf({})).toBeNull()
  })

  it('dışa aktarım satırları: kalem, sınır (yönlü), ölçülen, durum; bütçe alanı yoksa null', () => {
    const r = budgetExportRows(budgetEval(), scanSummary(1, { grade: 72 }), t, L)
    expect(r.state).toBe('exceeded')
    expect(r.summary).toBe('Bütçe aşıldı: 2 kalem')
    expect(r.rows[0]).toEqual(['Sayfa boyutu (en çok)', '≤ 2 MB', '3,4 MB', 'Aşıldı'])
    expect(r.rows[2]).toEqual(['Performans notu (en az)', '≥ 80 / 100', '72 / 100', 'Aşıldı'])
    expect(r.rows[3][3]).toBe('Ölçülemedi — değer okunamadı')
    expect(budgetExportRows(null, scanSummary(1), t, L)).toBeNull()
  })
})

describe('F2 eğilim', () => {
  it('noktalar eskiden yeniye; eski analizde kategori yığını BOŞLUK (sıfır değil), sayfa boyutu var; "Diğer" = toplam − kategoriler', () => {
    const pts = trendSeries(trendData().points)
    expect(pts.map((p) => p.id)).toEqual([6, 7, 9, 12, 30])
    expect(pts[0].stackKnown).toBe(false)
    expect(pts[0].js).toBeNull()
    expect(pts[0].size).toBe(Math.round(3.4 * MB))
    const p = pts[3]
    expect(p.stackKnown).toBe(true)
    expect(p.other).toBe(Math.round(3.4 * MB) - (640 * KB + 120 * KB + Math.round(2.1 * MB) + 96 * KB))
    expect(pts[0].security).toBeNull()
    expect(pts[4].security).toBe(58)
    // zamanı okunamayan nokta atılır; sıra bozuk gelse de sıralanır
    expect(trendSeries([{ scan_id: 1, finished_at: 'bozuk' }, ...trendData().points.slice().reverse()]).map((x) => x.id)).toEqual([6, 7, 9, 12, 30])
  })

  it('algoritma değişimi işaretleri; seri özeti; hiç bilinmeyen seri', () => {
    const pts = trendSeries(trendData().points)
    expect(algoChanges(pts)).toEqual([{ t: pts[4].t, id: 30, from: 2, to: 3 }])
    expect(seriesSummary(pts, 'load')).toEqual({ first: 3400, last: 2450, diff: -950, count: 5 })
    expect(seriesSummary(pts.slice(0, 2), 'security')).toBeNull()
    expect(hasSeries(pts.slice(0, 2), ['security', 'seo', 'a11y'])).toBe(false)
    expect(hasSeries(pts, ['security'])).toBe(true)
  })

  it('büyüyen nesneler: yüzdeye göre; yüzde yoksa ilk / son bayttan; ilk 0 → yüzde yok (sonsuz uydurulmaz); en çok 20', () => {
    const rows = growthRows([
      { url: 'https://a.example/x.js', category: 'JS', first_bytes: 100, last_bytes: 150 },
      { url: 'https://a.example/y.png', category: 'IMAGE', first_bytes: 0, last_bytes: 5000 },
      { url: '', first_bytes: 1, last_bytes: 2 },
      ...trendData().growth,
    ])
    expect(rows.map((r) => r.pct)).toEqual([100, 50, 33.3, null])
    expect(rows[3].url).toBe('https://a.example/y.png')
    expect(growthRows(Array.from({ length: 30 }, (_, i) => ({ url: `https://a.example/${i}`, first_bytes: 10, last_bytes: 10 + i })))).toHaveLength(20)
    expect(isTrendUnsupported({ status: 405 })).toBe(true)
  })
})

describe('G çok sayfalı analiz', () => {
  it('sınırlar: sunucu değeri 2–20 içinde; yoksa 10 sayfa / 900 sn; seçenekler 2 … en çok; varsayılan 5', () => {
    expect(siteLimits({ max_pages: 12, site_max_seconds: 600 })).toEqual({ maxPages: 12, maxSeconds: 600 })
    expect(siteLimits({})).toEqual({ maxPages: 10, maxSeconds: 900 })
    expect(siteLimits({ max_pages: 99 }).maxPages).toBe(20)
    expect(siteLimits({ max_pages: 1 }).maxPages).toBe(2)
    expect(sitePageChoices(4)).toEqual([2, 3, 4])
    expect(defaultSitePages(10)).toBe(5)
    expect(defaultSitePages(3)).toBe(3)
  })

  it('site analizi tanınır; eski sunucu scope bilmezse işaretlenir', () => {
    expect(isSiteScan({ scope: 'site' }, null)).toBe(true)
    expect(isSiteScan({}, { site: siteBlock() })).toBe(true)
    expect(isSiteScan({}, {})).toBe(false)
    expect(siteScopeIgnored(true, { id: 5 })).toBe(true)
    expect(siteScopeIgnored(true, { id: 5, scope: 'site' })).toBe(false)
    expect(siteScopeIgnored(false, { id: 5 })).toBe(false)
  })

  it('sayfa durumu: ok / failed / skipped / bilinmeyen (asla "ok" uydurulmaz)', () => {
    expect(pageStatus({ status: 'ok' })).toBe('ok')
    expect(pageStatus({ status: 'DONE' })).toBe('ok')
    expect(pageStatus({ status: 'failed', error: 'x' })).toBe('failed')
    expect(pageStatus({ error: 'PAGE_HTTP:503' })).toBe('failed')
    expect(pageStatus({ status: 'capped' })).toBe('skipped')
    expect(pageStatus({ grade: 70 })).toBe('ok')
    expect(pageStatus({ status: 'tuhaf' })).toBe('unknown')
  })

  it('model: en kötü sayfalar yalnız taranmışlardan; ortak kaynaklar bayta göre; toplamlar sunucudan', () => {
    const m = siteModel(siteBlock())
    expect(m.pages).toHaveLength(5)
    expect(m.pages[0].main).toBe(true)
    expect(m.worst.map((p) => p.grade)).toEqual([48, 72])
    expect(m.shared.map((s) => s.category)).toEqual(['JS', 'CSS', 'FONT'])
    expect(m.totals).toEqual({ pages: 5, scanned: 3, failed: 1, skipped: 1, avgGrade: 67, uniqueBytes: Math.round(8.4 * MB), sharedCount: 3 })
    expect(m.incomplete).toBe(true)
    expect(m.pages[0].a11y).toBeNull()
    expect(siteModel(null)).toBeNull()
  })

  it('sıralama: not artan (notsuz sona); yüklenme / boyut azalan; adres', () => {
    const m = siteModel(siteBlock())
    expect(sortSitePages(m.pages, 'grade_asc', L).map((p) => p.grade)).toEqual([48, 72, 81, null, null])
    expect(sortSitePages(m.pages, 'load_desc', L)[0].load).toBe(6100)
    expect(sortSitePages(m.pages, 'size_desc', L).at(-1).size).toBeNull()
    expect(sortSitePages(m.pages, 'url', L)[0].url).toBe('https://www.example.com/')
  })

  it('öneri kısa adı: bilinen kod çevrilir; bilinmeyen kod genel metin (ham kod gösterilmez)', () => {
    expect(recShortLabel('LARGE_IMAGES', t)).toBe('Büyük görseller')
    expect(recShortLabel('RENDER_BLOCKING_RESOURCES', t)).toBe('Oluşturmayı engelleyen kaynak')
    expect(recShortLabel('BILINMEYEN_KOD', t)).toBe('Diğer öneri')
    expect(recShortLabel('<script>', t)).toBe('Diğer öneri')
  })

  it('Excel sayfaları: sayfalar (durum çevrilmiş, öneriler kısa ad) ve ortak kaynaklar', () => {
    const m = siteModel(siteBlock())
    const rows = sitePageSheetRows(m, t)
    expect(rows).toHaveLength(6)
    expect(rows[3][1]).toBe('Açılamadı')
    expect(rows[2][12]).toBe('Ağır sayfa, Büyük görseller, srcset eksik, Diğer öneri')
    expect(siteSharedSheetRows(m, t)[1]).toEqual(['https://www.example.com/static/app.bundle.js', 'JavaScript', 640 * KB, 3])
  })
})

describe('F3 yönetici raporu', () => {
  it('kategori puanları: category_scores esas; yoksa bölüm / özet; hesaplanamayan null (0 değil) ve soluk', () => {
    const rows = categoryScoreRows(deepAnalysis(), scanSummary(1, { grade: 72, grade_letter: 'C' }))
    expect(rows.map((r) => r.key)).toEqual(CATEGORY_SCORE_KEYS)
    expect(rows.map((r) => r.score)).toEqual([72, 58, 86, null])
    expect(rows.map((r) => r.tone)).toEqual(['warning', 'danger', 'success', 'muted'])
    expect(categoryScoreText(rows[1], L)).toBe('58 / 100 · E')
    expect(categoryScoreText(rows[3], L)).toBe('—')
    // eski analiz (algoritma 2): yalnız performans
    const old = categoryScoreRows(scanAnalysis(), scanSummary(1, { grade: 72, capped: true }))
    expect(old.map((r) => r.score)).toEqual([72, null, null, null])
    expect(old[0].tone).toBe('muted')
  })

  it('öncelikli eylemler: önem, sonra kazanç (süre, bayt); ALL_GOOD eylem değil; en çok n', () => {
    const recs = [
      { code: 'NO_CACHE', severity: 'low' }, { code: 'ALL_GOOD', severity: 'low' },
      { code: 'UNCOMPRESSED_TEXT', severity: 'medium', saving_bytes: 450 * KB },
      { code: 'THIRD_PARTY_HEAVY', severity: 'medium', saving_ms: 900 },
      { code: 'LARGE_IMAGES', severity: 'high', saving_ms: 1400 },
    ]
    expect(priorityActions(recs).map((r) => r.code)).toEqual(['LARGE_IMAGES', 'THIRD_PARTY_HEAVY', 'UNCOMPRESSED_TEXT', 'NO_CACHE'])
    expect(priorityActions(recs, 2)).toHaveLength(2)
  })

  it('kategori bayt grafiği: yalnız sayfa yükü kategorileri, büyükten küçüğe, paylar toplam 100', () => {
    const c = categoryBytesChart(scanAnalysis().categories)
    expect(c.map((r) => r.category)).toEqual(['IMAGE', 'JS', 'CSS', 'FONT', 'HTML'])
    expect(Math.round(c.reduce((s, r) => s + r.pct, 0))).toBe(100)
  })

  it('karşılaştırma: iki tarafta bilinen değerler; yön; %1 altı "aynı"', () => {
    const now = scanSummary(2, { grade: 72, load_ms: 2450, security_score: 58 })
    const old = scanSummary(1, { grade: 64, load_ms: 2460, security_score: null })
    const rows = compareRows(now, old)
    expect(rows.map((r) => [r.key, r.direction])).toEqual([['grade', 'better'], ['load', 'same'], ['size', 'same'], ['requests', 'same']])
    expect(compareRows(now, null)).toEqual([])
    expect(compareExportRows(now, old, t, L)[0]).toEqual(['Performans notu', '64', '72', '+8 · iyileşti'])
  })

  it('A–E özet satırları: yalnız analizde OLAN bölümler; eski analizde hiçbiri (uydurulmaz)', () => {
    const secs = executiveSections(deepAnalysis(), t, L)
    expect(secs.map((s) => s.key)).toEqual(['loading', 'waste', 'media', 'security', 'html_audit'])
    const all = secs.flatMap((s) => s.lines).join('\n')
    expect(all).toContain('Oluşturmayı engelleyen 2 kaynak (760 KB).')
    expect(all).toContain('Birden çok sürümü yüklenen kütüphane: jQuery (1.12.4, 3.6.0).')
    expect(all).toContain('Eksik güvenlik başlıkları: Content-Security-Policy.')
    expect(all).toContain('Bilinen açığı olan kütüphaneler: jQuery 1.12.4.')
    expect(all).toContain('izin alınmadan çalışıyor OLABİLİR (sezgisel)')
    expect(all).toContain('Başarısız: Açıklama (meta description)')   // fe1'in pano metni (prs.check.*) önceliklidir
    expect(all).toContain('Erişilebilirlik puanı: —')
    expect(all).not.toMatch(/prs\.|\{\d\}/)
    expect(executiveSections(scanAnalysis(), t, L)).toEqual([])
  })

  it('denetim adı: pano metni varsa o, yoksa raporun kendi metni; bilinmeyen anahtar genel ad', () => {
    expect(checkLabel(t, 'seo', 'meta_description')).toBe('Açıklama (meta description)')   // pano metni (fe1, prs.check.*)
    const tFe1 = (k) => (k === 'prs.check.seo.meta_description' ? 'Açıklama etiketi' : t(k))
    expect(checkLabel(tFe1, 'seo', 'meta_description')).toBe('Açıklama etiketi')
    expect(checkLabel(t, 'seo', 'yeni_denetim')).toBe('Diğer denetim')
    expect(checkLabel(t, 'seo', '../x')).toBe('Diğer denetim')
  })

  it('Excel A–E sayfaları: bölüm başına sayfa, blok başlıkları; eski analizde sayfa yok', () => {
    const sheets = deepSheets(deepAnalysis(), t, L)
    expect(sheets.map((s) => s.key)).toEqual(['loading', 'waste', 'media', 'security', 'html_audit'])
    const sec = sheets.find((s) => s.key === 'security').rows
    expect(sec[0]).toEqual(['Güvenlik başlıkları'])
    expect(sec[2]).toEqual(['Content-Security-Policy', 'Eksik', '', ''])
    // çerez sorunları ÇEVRİLİR (ham kod yok)
    expect(sec.flat().join(' ')).toContain(t('prs.check.cookieIssue.no_http_only'))
    expect(sec.flat().join(' ')).not.toMatch(/no_http_only/)
    // çerez DEĞERİ hiçbir hücrede yok (sunucu göndermez; sayfa yalnız ad ve bayraklar)
    expect(sec.flat().join(' ')).toContain('SESSIONID')
    const loading = sheets[0].rows
    expect(loading.some((r) => r[0] === 'analytics.example.net' && r[8] === `Hayır (${t('prs.deep.hosts.reason.proxy')})`)).toBe(true)
    // vekilli host'un HTTP sürümü bilinmiyor — "HTTP/2" uydurulmaz
    expect(loading.find((r) => r[0] === 'analytics.example.net')[4]).toBe(t('prs.deep.hosts.httpProxy'))
    expect(deepSheets(scanAnalysis(), t, L)).toEqual([])
  })

  it('yönetici Excel sayfası: puanlar, bütçe, eylemler, karşılaştırma, A–E; eksik veri cümleyle (sorun yok denmez)', () => {
    const d = deepDetail(12)
    const rows = executiveSheetRows({ scan: d.scan, analysis: d.analysis, budget: d.budget, prev: scanSummary(9, { grade: 64, algorithm_version: 3 }), t, locale: L })
    const flat = rows.map((r) => r.join(' | '))
    expect(flat).toContain('Güvenlik | 58 / 100 · E')
    expect(flat).toContain('Erişilebilirlik | —')
    expect(flat).toContain('Bütçe aşıldı: 2 kalem')
    expect(flat.some((l) => l.startsWith('Yüksek | '))).toBe(true)
    expect(flat).toContain('Performans notu | 64 | 72 | +8 · iyileşti')
    const old = executiveSheetRows({ scan: scanSummary(1), analysis: scanAnalysis(), budget: null, prev: null, t, locale: L }).map((r) => r.join(' | '))
    expect(old).toContain(TR['prs.pdf.budgetMissing'])
    expect(old).toContain(TR['prs.pdf.compareNone'])
    expect(old).toContain(TR['prs.pdf.deepMissing'])
  })

  it('karşılaştırma işaretleri: kategori puanları iki tarafta bilinmiyorsa satır yok', () => {
    expect(scanMarkerScores({ security_score: 50 }, { security_score: 58, seo_score: 80 })).toEqual([
      { key: 'security', old: 50, now: 58 }, { key: 'seo', old: null, now: 80 },
    ])
    expect(scanMarkerScores({}, {})).toEqual([])
  })
})

describe('tembel yükleme (paket boyutu)', () => {
  it('eğilim görünümü (recharts) sekmede yalnız import() ile; rapor modeli grafik / PDF kütüphanesi içe aktarmaz', async () => {
    const fs = await import('node:fs')
    const path = await import('node:path')
    const dir = path.resolve(__dirname, '../components/page/resourcescan')
    const tab = fs.readFileSync(path.join(dir, 'ResourceScanTab.jsx'), 'utf8')
    expect(tab).toMatch(/lazy\(\(\) => import\('\.\/ResourceScanTrendView\.jsx'\)\)/)
    expect(tab).not.toMatch(/^import .*ResourceScanTrendView/m)
    const model = fs.readFileSync(path.join(dir, 'resourceScanReportModel.js'), 'utf8')
    expect(model).not.toMatch(/from ['"](recharts|jspdf|jspdf-autotable|@\/components\/shadcn\/chart)['"]/)
  })
})

describe('Nesneler sayfası — derin alan sütunları (algoritma 3, fe1 beklentisi)', () => {
  const deepItem = {
    url: 'https://cdn.example.net/jquery.js', http_version: '2', render_blocking: true, minified: false, integrity: null,
    library: { name: 'jQuery', version: '1.12.4' }, source_map: 'https://cdn.example.net/jquery.map', third_party_category: 'cdn',
  }
  const plain = { url: 'https://www.example.com/a.css' }

  it('eski analiz (anahtar yok) → sütun eklenmez; dosya bugünküyle aynı', () => {
    expect(objectDeepColumns([plain, { url: 'b' }], t)).toBeNull()
    const sheet = [['h1', 'h2'], ['x', 'y'], ['z', 'w']]
    expect(withObjectDeepColumns(sheet, [plain, plain], t)).toBe(sheet)
  })

  it('anahtarı taşıyan sütunlar eklenir; sıra objectSheetRows ile aynı; null boş hücre (Hayır değil)', () => {
    const out = objectDeepColumns([deepItem, plain], t)
    expect(out.head).toEqual(OBJECT_DEEP_COLUMNS.map(([, k]) => TR[k]))
    expect(out.rows[0]).toEqual(['HTTP/2', 'Evet', 'Hayır', '', 'jQuery 1.12.4', 'https://cdn.example.net/jquery.map', 'CDN'])
    expect(out.rows[1]).toEqual(['', '', '', '', '', '', ''])
    const merged = withObjectDeepColumns([['URL', 'Tür'], ['a', 'JS'], ['b', 'CSS']], [deepItem, plain], t)
    expect(merged[0]).toEqual(['URL', 'Tür', ...out.head])
    expect(merged[1].slice(0, 2)).toEqual(['a', 'JS'])
    expect(merged[1][2]).toBe('HTTP/2')
    expect(merged.every((r) => r.length === merged[0].length)).toBe(true)
  })

  it('yalnız bazı anahtarlar varsa yalnız o sütunlar', () => {
    const out = objectDeepColumns([{ url: 'x', http_version: '1.1' }], t)
    expect(out.head).toEqual([TR['prs.pdf.ocol.httpVersion']])
    expect(out.rows).toEqual([['HTTP/1.1']])
  })

  it('üçüncü taraf kategorisi: fe1 anahtarı varsa etiket, yoksa sunucu kodu; ham anahtar asla basılmaz', () => {
    const withKey = (k) => (k === 'prs.deep.tpc.analytics' ? 'Analitik' : k)
    expect(tpCategoryLabel('analytics', withKey)).toBe('Analitik')
    expect(tpCategoryLabel('tag_manager', withKey)).toBe('tag_manager')
    expect(tpCategoryLabel('', withKey)).toBe('')
    expect(tpCategoryLabel(null, withKey)).toBe('')
    expect(tpCategoryLabel('Weird Code!', withKey)).toBe('Weird Code!')
  })

  it('HTTP sürümü metni', () => {
    expect(httpVersionLabel('1.1')).toBe('HTTP/1.1')
    expect(httpVersionLabel('h2')).toBe('HTTP/h2')
    expect(httpVersionLabel('HTTP/3')).toBe('HTTP/3')
    expect(httpVersionLabel(2)).toBe('HTTP/2')
    expect(httpVersionLabel(null)).toBe('')
    expect(httpVersionLabel('x'.repeat(40))).toBe('')
  })
})

describe('bölüm sekmesi telefon ızgarası', () => {
  it('en çok 4 tek satır; 5+ iki satır; geçersiz sayı → 4', () => {
    expect([1, 2, 3, 4, 5, 6, 7, 8].map(sectionTabCols)).toEqual([1, 2, 3, 4, 3, 3, 4, 4])
    expect(sectionTabCols(0)).toBe(4)
    expect(sectionTabCols(null)).toBe(4)
    expect(sectionTabCols(2.5)).toBe(4)
    expect(sectionTabCols(1000)).toBe(6)
  })
})

// Hata avı bh-prs3 (2026-11-05): eski pod'un eğilim yanıtı, site ilerlemesi, Excel / PDF'te bilinmeyen ve kısmi, eğilim
// yönü, yönetici özeti sayıları, geçmişte sayfa sayısı.
describe('bh-prs3 hata avı — model', () => {
  it('O-1: eski pod tür uyuşmazlığı 400 (`…/{scanId}`ye düşen istek) "desteklenmiyor"; başka 400 gerçek hata', () => {
    const oldPod = { success: false, status: 400, code: 'INVALID_PARAMETER',
      error: "'scanId' değeri geçersiz: 'trend' beklenen biçimde değil (beklenen: sayı). Adresi ya da formu kontrol edip tekrar deneyin." }
    expect(isTrendUnsupported(oldPod)).toBe(true)
    expect(isBudgetUnsupported(oldPod)).toBe(true)
    expect(isEndpointUnsupported({ ...oldPod, error: "The value of 'scanId' is invalid: 'trend' isn’t in the expected format (expected: a number)." })).toBe(true)
    expect(isEndpointUnsupported({ status: 400, code: 'INVALID_PARAMETER' })).toBe(true)
    expect(isEndpointUnsupported({ status: 400, error: "'scanId' değeri geçersiz: 'trend'" })).toBe(true)
    // başka parametrenin tür hatası, doğrulama hatası, kodlu başka 400: gerçek hata (desteklenmiyor denmez)
    expect(isEndpointUnsupported({ status: 400, code: 'INVALID_PARAMETER', error: "'id' değeri geçersiz: 'x' beklenen biçimde değil" })).toBe(false)
    expect(isEndpointUnsupported({ status: 400, code: 'VALIDATION_FAILED', field: 'max_js_kb', error: "'scanId'" })).toBe(false)
    expect(isEndpointUnsupported({ status: 400, code: 'RATE_LIMITED' })).toBe(false)
    expect(isEndpointUnsupported({ status: 400 })).toBe(false)
    expect(isEndpointUnsupported({ status: 404, code: 'NOT_FOUND' })).toBe(true)
    expect(isEndpointUnsupported({ status: 405 })).toBe(true)
    expect(isEndpointUnsupported({ status: 500 })).toBe(false)
    expect(isEndpointUnsupported(null)).toBe(false)
  })

  it('O-2: site koşusunun yüzdesi sayfa ilerlemesinden; iç sayfalar toplamı büyütünce %100\'de takılmaz; geri gitmez', () => {
    const run = (progress) => ({ id: 51, status: 'RUNNING', scope: 'site', progress })
    // ana sayfa ölçülürken sayfa sayısı bilinmiyor: belirsiz (nesne oranı sitenin oranı değil)
    expect(runProgressPct(run({ done: 20, total: 27 })).pct).toBeNull()
    // ana sayfa bitti (nesne sayacı 27 / 27 = %100) → 1 / 5 sayfa = %20
    let r = runProgressPct(run({ done: 27, total: 27, pages_done: 1, pages_total: 5 }), null)
    expect(r.pct).toBe(20)
    // 2. sayfa sürüyor: toplam 42'ye büyüdü, 30 ölçüldü → 1 + 3/15 = %24 (eski kural %71 → %100'de takılıyordu)
    r = runProgressPct(run({ done: 30, total: 42, pages_done: 1, pages_total: 5 }), r.memo)
    expect(r.pct).toBe(24)
    // sayfanın payı en çok %99 — sayfa bitmeden bir sonrakinin payı verilmez
    r = runProgressPct(run({ done: 42, total: 42, pages_done: 1, pages_total: 5 }), r.memo)
    expect(r.pct).toBe(40)
    r = runProgressPct(run({ done: 42, total: 42, pages_done: 2, pages_total: 5 }), r.memo)
    expect(r.pct).toBe(40)
    expect(r.memo).toEqual({ pd: 2, base: 42 })
    r = runProgressPct(run({ done: 50, total: 58, pages_done: 2, pages_total: 5 }), r.memo)
    expect(r.pct).toBe(50)
    // son sayfa dışında %100 gösterilmez; hepsi bitince %100
    expect(runProgressPct(run({ done: 99, total: 100, pages_done: 1, pages_total: 2 }), { pd: 1, base: 0 }).pct).toBe(99)
    expect(runProgressPct(run({ done: 70, total: 70, pages_done: 5, pages_total: 5 })).pct).toBe(100)
    // tek sayfa analizi bugünkü kural
    expect(runProgressPct({ status: 'RUNNING', progress: { done: 9, total: 40 } }).pct).toBe(23)
    expect(runProgressPct({ status: 'RUNNING', progress: { done: 0, total: 0 } }).pct).toBeNull()
  })

  it('D-2: Excel A–E — HTML okunamadı: engelleyenler / ipuçları / görsel listeleri "Kayıt yok" değil "bilinmiyor"', () => {
    const a = deepAnalysis()
    a.loading = { ...a.loading, render_blocking: null, hints: null }
    a.media = { ...a.media, images: { missing_dimensions: [], missing_srcset: [], not_lazy: [], animated_gif: [], html_checked: false } }
    const sheets = deepSheets(a, t, L)
    const after = (rows, title) => rows[rows.findIndex((r) => r.length === 1 && r[0] === title) + 1]
    const loading = sheets.find((s) => s.key === 'loading').rows
    expect(after(loading, t('prs.pdf.xl.renderBlocking'))).toEqual([t('prs.pdf.load.htmlUnknown')])
    expect(after(loading, t('prs.pdf.xl.missingPreconnect'))).toEqual([t('prs.pdf.load.htmlUnknown')])
    const media = sheets.find((s) => s.key === 'media').rows
    for (const k of ['missingDimensions', 'missingSrcset', 'notLazy']) expect(after(media, t(`prs.pdf.xl.${k}`))).toEqual([t('prs.pdf.media.htmlUnknown')])
    // hareketli GIF nesneden ölçülür (HTML'den değil): boşsa "Kayıt yok"
    expect(after(media, t('prs.pdf.xl.animatedGif'))).toEqual([t('prs.pdf.xl.none')])
    expect([...loading, ...media].flat()).not.toContain(undefined)
    // HTML okunduysa boş liste yine "Kayıt yok" (bugünkü davranış)
    const okA = deepAnalysis()
    okA.loading = { ...okA.loading, render_blocking: { items: [], count: 0, bytes: 0 } }
    const okLoading = deepSheets(okA, t, L).find((s) => s.key === 'loading').rows
    expect(after(okLoading, t('prs.pdf.xl.renderBlocking'))).toEqual([t('prs.pdf.xl.none')])
  })

  it('D-3: site sayfasının kısmi ölçümü modelde ve Excel durum hücresinde', () => {
    const block = siteBlock()
    block.pages[1] = { ...block.pages[1], capped: true }
    const m = siteModel(block)
    expect(m.pages.map((p) => p.capped)).toEqual([false, true, false, false, false])   // taranmayan sayfanın capped'i "kısmi" değil
    const rows = sitePageSheetRows(m, t)
    expect(rows[2][1]).toBe(`${t('prs.site.status.ok')} · ${t('prs.pdf.partialScore')}`)
    expect(rows[1][1]).toBe(t('prs.site.status.ok'))
  })

  it('D-4: karşılaştırma — algoritma farklıysa not / güvenlik satırı yok; kısmi analizde yön yok; uyarı satırları', () => {
    const now = scanSummary(2, { grade: 72, load_ms: 2000, security_score: 58, algorithm_version: 3 })
    const v2 = scanSummary(1, { grade: 64, load_ms: 3000, security_score: 50, algorithm_version: 2 })
    expect(compareRows(now, v2).map((r) => r.key)).toEqual(['load', 'size', 'requests'])
    expect(compareNotes(now, v2, t)).toEqual([t('prs.pdf.compareAlgo', 'v2', 'v3')])
    // analiz ayrıntısındaki sürüm öncelikli
    expect(compareRows(scanSummary(2, { grade: 72 }), scanSummary(1, { grade: 64, algorithm_version: 3 }), { algorithm_version: 3 })
      .map((r) => r.key)).toContain('grade')
    // kısmi: değerler ve fark var, yön yok; metinde "iyileşti" yok
    const capped = { ...now, capped: true }
    const v3 = { ...v2, algorithm_version: 3 }
    const rows = compareRows(capped, v3)
    expect(rows.map((r) => [r.key, r.direction])).toEqual([['grade', null], ['load', null], ['size', null], ['requests', null], ['security', null]])
    const text = compareExportRows(capped, v3, t, L)
    expect(text[0]).toEqual(['Performans notu', '64', '72', `+8 · ${t('prs.pdf.dir.partial')}`])
    expect(text.flat().join(' ')).not.toMatch(/iyileşti|kötüleşti/)
    expect(text.find((r) => r[0] === t('prs.pdf.cmp.size'))[3]).toBe(t('prs.delta.same'))
    expect(compareNotes(capped, v3, t)).toEqual([t('prs.cmp.capped')])
    expect(compareNotes(now, null, t)).toEqual([])
    // Excel yönetici sayfası aynı kuralı söyler
    const d = deepDetail(12)
    const flat = executiveSheetRows({ scan: d.scan, analysis: d.analysis, budget: d.budget, prev: scanSummary(9, { grade: 64, algorithm_version: 2 }), t, locale: L })
      .map((r) => r.join(' | '))
    expect(flat).toContain(t('prs.pdf.compareAlgo', 'v2', 'v3'))
    expect(flat.some((l) => l.startsWith('Performans notu |'))).toBe(false)
  })

  it('D-5: Excel yönetici özeti — kısmi analizin performans / güvenlik puanı "Kısmi ölçüm" notuyla', () => {
    const d = deepDetail(12, { scan: { capped: true } })
    const flat = executiveSheetRows({ scan: d.scan, analysis: d.analysis, budget: d.budget, prev: null, t, locale: L }).map((r) => r.join(' | '))
    expect(flat).toContain(`Performans | 72 / 100 · C · ${t('prs.pdf.partialScore')}`)
    expect(flat).toContain(`Güvenlik | 58 / 100 · E · ${t('prs.pdf.partialScore')}`)
    expect(flat).toContain('SEO | 86 / 100')                 // SEO yalnız HTML'den — sınırdan etkilenmez
    expect(flat).toContain('Erişilebilirlik | —')            // hesaplanamayan puan "kısmi" değil
  })

  it('D-6: eğilim yönü — algoritmaya bağlı seride yalnız son sürüm; son nokta kısmiyse yön yok; kısmi noktalar kıyasa girmez', () => {
    const pts = trendSeries([
      { scan_id: 1, finished_at: '2026-10-01T09:00:00', algorithm_version: 2, grade: 92, load_ms: 3000 },
      { scan_id: 2, finished_at: '2026-10-08T09:00:00', algorithm_version: 3, grade: 81, load_ms: 2500 },
    ])
    expect(pts.map((p) => p.capped)).toEqual([false, false])
    expect(trendDirection(pts, 'grade', { higherIsBetter: true, algoDependent: true })).toBeNull()
    expect(trendDirection(pts, 'grade', { higherIsBetter: true })).toBe('worse')          // sürüme bağlı değilse eski kural
    expect(trendDirection(pts, 'load', {})).toBe('better')                                // süre ölçümdür: sürüm sınırı geçerli
    const more = trendSeries([
      { scan_id: 1, finished_at: '2026-10-01T09:00:00', algorithm_version: 2, grade: 92 },
      { scan_id: 2, finished_at: '2026-10-08T09:00:00', algorithm_version: 3, grade: 70 },
      { scan_id: 3, finished_at: '2026-10-15T09:00:00', algorithm_version: 3, grade: 78 },
    ])
    expect(trendDirection(more, 'grade', { higherIsBetter: true, algoDependent: true })).toBe('better')   // 70 → 78 (v3 dilimi)
    const lastCapped = trendSeries([
      { scan_id: 1, finished_at: '2026-10-01T09:00:00', algorithm_version: 3, grade: 60, load_ms: 4000 },
      { scan_id: 2, finished_at: '2026-10-08T09:00:00', algorithm_version: 3, grade: 90, load_ms: 1000, capped: true },
    ])
    expect(trendDirection(lastCapped, 'grade', { higherIsBetter: true, algoDependent: true })).toBeNull()
    expect(trendDirection(lastCapped, 'load', {})).toBeNull()
    const middleCapped = trendSeries([
      { scan_id: 1, finished_at: '2026-10-01T09:00:00', algorithm_version: 3, grade: 60, capped: true },
      { scan_id: 2, finished_at: '2026-10-08T09:00:00', algorithm_version: 3, grade: 70 },
      { scan_id: 3, finished_at: '2026-10-15T09:00:00', algorithm_version: 3, grade: 65 },
    ])
    expect(trendDirection(middleCapped, 'grade', { higherIsBetter: true, algoDependent: true })).toBe('worse')   // 70 → 65
    expect(trendDirection([], 'grade', {})).toBeNull()
  })

  it('D-8: PDF yönetici özeti sayıları önerinin TAM değerinden; kesilmiş çerez listesinde "listelenen ilk N"', () => {
    const a = deepAnalysis()
    const cookies = Array.from({ length: 50 }, (_, i) => ({ name: `c${i}`, host: 'www.example.com', third_party: false, secure: true,
      http_only: i >= 10, same_site: 'Lax', issues: i < 10 ? ['no_http_only'] : [] }))
    a.security = { ...a.security, cookies, cookies_total: 120 }
    a.loading = { ...a.loading, hints: { ...a.loading.hints, preload: [{ url: 'https://www.example.com/x.woff2', used: false }], preload_total: 80 } }
    a.waste = { ...a.waste, duplicates_total: 70, unminified_total: 60, source_maps: [{ url: 'https://www.example.com/a.js', map_url: 'https://www.example.com/a.js.map', exposed: true }], source_maps_total: 90 }
    a.recommendations = [
      { code: 'UNUSED_PRELOAD', severity: 'low', params: { count: 12 } },
      { code: 'DUPLICATE_RESOURCES', severity: 'low', params: { count: 70, bytes: 5 * 1024 * 1024 }, saving_bytes: 5 * 1024 * 1024 },
      { code: 'UNMINIFIED_ASSETS', severity: 'low', params: { count: 60, bytes: 9 * 1024 * 1024 }, saving_bytes: 3 * 1024 * 1024 },
      { code: 'SOURCE_MAP_EXPOSED', severity: 'low', params: { count: 33 } },
    ]
    const lines = executiveSections(a, t, L).flatMap((s) => s.lines)
    expect(lines).toContain(t('prs.pdf.secu.cookiesListed', '120', '10', '50'))
    expect(lines).toContain(t('prs.pdf.load.unusedPreload', '12'))
    expect(lines).toContain(t('prs.pdf.waste.duplicates', '70', '5 MB'))
    expect(lines).toContain(t('prs.pdf.waste.unminified', '60', '3 MB'))
    expect(lines).toContain(t('prs.pdf.waste.sourceMaps', '33'))
    // liste kesilmediyse bugünkü cümle
    const b = deepAnalysis()
    const plain = executiveSections(b, t, L).flatMap((s) => s.lines)
    expect(plain).toContain(t('prs.pdf.secu.cookies', '1', '1'))
    expect(plain.join('\n')).not.toMatch(/prs\.|\{\d\}/)
  })

  it('Ş-3: geçmiş / karşılaştırma çipi Sayfalar ile aynı: "Site · 3 / 5 sayfa analiz edildi"', () => {
    expect(siteRowText({ pages_count: 3, progress: { done: 9, total: 9, pages_done: 5, pages_total: 5 } }, t, L)).toBe('Site · 3 / 5 sayfa analiz edildi')
    expect(siteRowText({ pages_count: 5, progress: { pages_total: 5 } }, t, L)).toBe('Site · 5 sayfa analiz edildi')
    expect(siteRowText({ pages_count: 3, progress: { done: 9, total: 9 } }, t, L)).toBe('Site · 3 sayfa analiz edildi')   // eski satır: payda yok
    expect(siteRowText({ pages_count: null }, t, L)).toBe('Site')
    expect(siteRowText({ pages_count: 0 }, t, L)).toBe('Site')
  })
})
