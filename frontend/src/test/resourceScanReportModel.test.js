import { describe, it, expect } from 'vitest'
import {
  BUDGET_FIELDS, BUDGET_KEYS, CATEGORY_SCORE_KEYS, algoChanges, budgetActual, budgetBand, budgetChanges, budgetErrorField, budgetExportRows,
  budgetFormFrom, budgetStatusOf, categoryBytesChart, categoryScoreRows, categoryScoreText, checkLabel, compareExportRows, compareRows,
  deepSheets, defaultSitePages, executiveSections, executiveSheetRows, formatBudgetActual, formatBudgetLimit, growthRows, hasSeries,
  isBudgetUnsupported, isSiteScan, isTrendUnsupported, normalizeBudget, pageStatus, parseBudgetForm, priorityActions, recShortLabel,
  scanMarkerScores, seriesSummary, siteLimits, siteModel, sitePageChoices, sitePageSheetRows, siteScopeIgnored, siteSharedSheetRows,
  sortSitePages, trendSeries,
} from '../components/page/resourcescan/resourceScanReportModel.js'
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
    expect(r.rows[3][3]).toBe('Ölçülemedi')
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
    expect(m.totals).toEqual({ pages: 5, scanned: 3, failed: 1, avgGrade: 67, uniqueBytes: Math.round(8.4 * MB), sharedCount: 3 })
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
    expect(all).toContain('Başarısız: Meta açıklama')
    expect(all).toContain('Erişilebilirlik puanı: —')
    expect(all).not.toMatch(/prs\.|\{\d\}/)
    expect(executiveSections(scanAnalysis(), t, L)).toEqual([])
  })

  it('denetim adı: pano metni varsa o, yoksa raporun kendi metni; bilinmeyen anahtar genel ad', () => {
    expect(checkLabel(t, 'seo', 'meta_description')).toBe('Meta açıklama')
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
    expect(sec[2]).toEqual(['Content-Security-Policy', 'Eksik', '', 'Content-Security-Policy yok'])
    // çerez DEĞERİ hiçbir hücrede yok (sunucu göndermez; sayfa yalnız ad ve bayraklar)
    expect(sec.flat().join(' ')).toContain('SESSIONID')
    const loading = sheets[0].rows
    expect(loading.some((r) => r[0] === 'analytics.example.net' && /Hayır \(proxy\)/.test(r[8]))).toBe(true)
    expect(deepSheets(scanAnalysis(), t, L)).toEqual([])
  })

  it('yönetici Excel sayfası: puanlar, bütçe, eylemler, karşılaştırma, A–E; eksik veri cümleyle (sorun yok denmez)', () => {
    const d = deepDetail(12)
    const rows = executiveSheetRows({ scan: d.scan, analysis: d.analysis, budget: d.budget, prev: scanSummary(9, { grade: 64 }), t, locale: L })
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
