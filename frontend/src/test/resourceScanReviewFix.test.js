import { describe, it, expect } from 'vitest'
import {
  AREA_STATE_TONE, POLL_GRACE_MS, POLL_MIN_CAP_MS, SITE_POLL_DEFAULT_SECONDS, areaSummary, areaUnreadable, pollCapMs, topLosses,
} from '../components/page/resourcescan/resourceScanModel.js'
import {
  budgetBand, budgetBandText, budgetExportRows, budgetRowDetail, budgetRowState, categoryScoreRows, siteCountText, siteModel,
  sitePageErrorText, trendView,
} from '../components/page/resourcescan/resourceScanReportModel.js'
import { TR } from '../i18n/tr.js'
import {
  realData, realTrend, withBudgetCapped, withBudgetUnreadable, withHtmlUnread, withSecurityUnread, withSiteIncomplete, withTotals, withTrendBusy,
} from './helpers/resourceScanRealFixtures.js'

// prs3 inceleme düzeltmeleri (ön yüz FR O1–O7 + düşükler, sözleşme CT O1–O4 + düşükler, be1 / be2 tel değişiklikleri) —
// saf model kuralları, GERÇEK arka uç yanıtları üzerinde.
const t = (k, ...a) => {
  const v = TR[k]
  if (typeof v !== 'string') return k
  return v.replace(/\{(\d+)\}/g, (_, i) => (a[Number(i)] === undefined ? `{${i}}` : String(a[Number(i)])))
}
const L = 'tr-TR'

describe('O1 — site koşusunun takip tavanı', () => {
  it('site koşusu site_max_seconds ile; tek sayfa max_seconds ile; alan yoksa 900 sn', () => {
    const limits = { max_seconds: 300, site_max_seconds: 900 }
    expect(pollCapMs(limits)).toBe(POLL_MIN_CAP_MS)
    expect(pollCapMs(limits, { scope: 'page' })).toBe(POLL_MIN_CAP_MS)
    expect(pollCapMs(limits, { scope: 'site' })).toBe(900_000 + POLL_GRACE_MS)
    expect(pollCapMs({ max_seconds: 300 }, { scope: 'site' })).toBe(SITE_POLL_DEFAULT_SECONDS * 1000 + POLL_GRACE_MS)
    expect(pollCapMs(null, { scope: 'site' })).toBeGreaterThan(POLL_MIN_CAP_MS)
  })
})

describe('O2 / O3 / O9 / be2 — site modeli gerçek yanıtla', () => {
  it('sayfa hatası: sunucu cümlesi gösterilir; kod biçimindeki her kod çevrilir; bilinmeyen kod genel metin', () => {
    const real = realData(3).analysis.site.pages
    expect(sitePageErrorText(real[3], t)).toMatch(/HTTP 500 döndürdü/)
    expect(sitePageErrorText(real[0], t)).toBe('')
    const code = (error, extra = {}) => sitePageErrorText({ error, ...extra }, t)
    expect(code('PAGE_HTTP:503')).toBe(t('prs.site.err.http', '503'))
    expect(code('PAGE_HTTP')).toBe(t('prs.site.err.httpNoCode'))
    expect(code('PAGE_BLOCKED')).toBe(t('prs.site.err.blocked'))
    expect(code('PAGE_EMPTY')).toBe(t('prs.site.err.empty'))
    expect(code('PAGE_NOT_HTML')).toBe(t('prs.site.err.notHtml'))
    expect(code('PAGE_OFFSITE')).toBe(t('prs.site.err.offsite'))
    expect(code('PAGE_FAILED:TIMEOUT')).toMatch(new RegExp(`^${t('prs.site.err.failed')}`))
    expect(code('PAGE_FAILED')).toBe(t('prs.site.err.failed'))
    expect(code('NOT_SCANNED:TIME')).toBe(t('prs.site.skip.time'))
    expect(code('NOT_SCANNED:BYTES')).toBe(t('prs.site.skip.bytes'))
    expect(code('NOT_SCANNED:OBJECTS')).toBe(t('prs.site.skip.objects'))
    expect(code('NOT_SCANNED')).toBe(t('prs.site.skip.other'))
    expect(code('SOMETHING_NEW')).toBe(t('prs.site.err.unknown'))
    // `error` yok, yalnız `error_code` (+ neden)
    expect(sitePageErrorText({ error_code: 'PAGE_FAILED', error_reason: 'DNS_FAILURE' }, t)).toMatch(new RegExp(t('prs.site.err.failed')))
    for (const e of ['PAGE_HTTP:503', 'PAGE_OFFSITE', 'NOT_SCANNED:BYTES', 'SOMETHING_NEW']) expect(code(e)).not.toMatch(/[A-Z]+_[A-Z]+/)
  })

  it('toplamlar: ortak kaynak totals.shared (liste 50\'de kesik), taranmayan, eksiklik', () => {
    const m = siteModel(realData(3).analysis.site)
    expect(m.totals).toMatchObject({ pages: 5, scanned: 3, failed: 2, skipped: 0, sharedCount: 7 })
    expect(m.incomplete).toBeNull()                        // döküm alanı taşımıyor (eski yanıt)
    const inc = siteModel(withSiteIncomplete(realData(3)).analysis.site)
    expect(inc.totals).toMatchObject({ pages: 6, skipped: 1, sharedCount: 120 })
    expect(inc.incomplete).toBe(true)
  })

  it('sayfa sayısı cümlesi: hepsi analiz edildiyse tek sayı, değilse "3 / 5"', () => {
    expect(siteCountText({ pages: 5, scanned: 3 }, t, L)).toBe('3 / 5 sayfa analiz edildi')
    expect(siteCountText({ pages: 5, scanned: 5 }, t, L)).toBe('5 sayfa analiz edildi')
  })
})

describe('O4 — bilinmeyen / kısmi alan asla "Sorun yok"', () => {
  it('gerçek analiz: okunabilir alanlar; HTML okunamazsa yükleme / israf / görsel okunamadı', () => {
    const d = realData(1)
    for (const a of ['loading', 'waste', 'media', 'security']) expect(areaUnreadable(d.analysis[a], a)).toBe(false)
    const u = withHtmlUnread(d)
    expect(areaUnreadable(u.analysis.loading, 'loading')).toBe(true)
    expect(areaUnreadable(u.analysis.waste, 'waste')).toBe(true)
    expect(areaUnreadable(u.analysis.media, 'media')).toBe(true)
    expect(areaUnreadable(withSecurityUnread(d).analysis.security, 'security')).toBe(true)
  })

  it('öneri yoksa: okunamayan alan unknown, kısmi analiz partial (soluk); okunabilir + tam → ok', () => {
    const d = realData(1)
    const noRecs = (x) => ({ ...x, analysis: { ...x.analysis, recommendations: [] } })
    expect(areaSummary(noRecs(d).analysis, d.scan, 'loading').state).toBe('ok')
    const u = noRecs(withHtmlUnread(d))
    expect(areaSummary(u.analysis, u.scan, 'loading').state).toBe('unknown')
    expect(areaSummary(u.analysis, u.scan, 'waste').state).toBe('unknown')
    expect(areaSummary(u.analysis, u.scan, 'media').state).toBe('unknown')
    const s = noRecs(withSecurityUnread(d))
    expect(areaSummary(s.analysis, s.scan, 'security').state).toBe('unknown')
    expect(areaSummary(noRecs(d).analysis, { ...d.scan, capped: true }, 'loading').state).toBe('partial')
    expect(AREA_STATE_TONE.partial).toBe('muted')
    expect(AREA_STATE_TONE.unknown).toBe('muted')
  })

  it('be1 D-1: puan bileşeni null → "notu düşürenler" onu atlar (tam puan / 0 sayılmaz)', () => {
    const u = withHtmlUnread(realData(1))
    expect(topLosses(u.analysis.scores, 9).map((r) => r.key)).not.toContain('render_blocking')
  })
})

describe('O7 / be2 — bütçe nedeni ve okunamayan değerlendirme', () => {
  it('gerçek bütçe: aşılan / geçen kalemler, "Sınır içinde"', () => {
    const d = realData(3)
    const band = budgetBand(d.budget, d.scan)
    expect(band.state).toBe('exceeded')
    expect(band.exceeded).toBe(6)
    const load = band.rows.find((r) => r.key === 'max_load_ms')
    expect(budgetRowState(load)).toBe('ok')
    expect(t(`prs.budget.state.${budgetRowState(load)}`)).toBe('Sınır içinde')
    expect(budgetRowDetail(band.rows[0], t, L)).toBe('Sınır ≤ 200 KB · ölçülen 969 KB')
  })

  it('kısmi: ok null + reason capped → "Kısmi ölçüm" + değer; çelişki yok', () => {
    const d = withBudgetCapped(realData(2))
    const band = budgetBand(d.budget, d.scan)
    const load = band.rows.find((r) => r.key === 'max_load_ms')
    expect(load).toMatchObject({ ok: null, reason: 'capped' })
    expect(budgetRowState(load)).toBe('partial')
    expect(budgetRowDetail(load, t, L)).toBe('Sınır ≤ 200 ms · kısmi ölçümde 50 ms — kesin değil')
    const ex = budgetExportRows(d.budget, d.scan, t, L)
    const row = ex.rows.find((r) => r[0] === t('prs.budget.f.max_load_ms'))
    expect(row[3]).toMatch(/^Kısmi ölçüm — analiz sınıra takıldı/)
  })

  it('missing / not_done / bilinmeyen neden → değer GÖSTERİLMEZ, neden cümlesi', () => {
    for (const [reason, st] of [['missing', 'missing'], ['not_done', 'notDone'], ['weird', 'unknown'], [null, 'unknown']]) {
      const b = budgetBand({ defined: true, checks: [{ key: 'max_page_kb', limit: 200, actual: 900, actual_bytes: 921600, ok: null, reason }] },
        { page_bytes: 921600 })
      expect(b.rows[0].actual).toBeNull()
      expect(budgetRowState(b.rows[0])).toBe(st)
      expect(budgetRowDetail(b.rows[0], t, L)).toBe(`Sınır ≤ 200 KB · ${t(`prs.budget.reason.${st}`)}`)
    }
  })

  it('okunamayan değerlendirme: bant "okunamadı", satır yok, "aşılan yok" DEĞİL', () => {
    const d = withBudgetUnreadable(realData(1))
    const band = budgetBand(d.budget, d.scan)
    expect(band.state).toBe('unreadable')
    expect(band.rows).toEqual([])
    expect(budgetBandText(band, t, L)).toBe(t('prs.budget.band.unreadable'))
    expect(budgetExportRows(d.budget, d.scan, t, L).summary).toMatch(/okunamadı/)
  })
})

describe('düşükler — eğilim, kategori puanları', () => {
  it('eğilim: gerçek yanıt; büyüme meşgul (null) ≠ boş; kesilen pencere; büyüme dayanağı', () => {
    const v = trendView(realTrend().data)
    expect(v.points).toHaveLength(3)
    expect(v.growth).toHaveLength(1)
    expect(v.growthBusy).toBe(false)
    expect(v.basisScans).toBe(3)
    expect(v.truncated).toBe(false)
    const b = trendView(withTrendBusy(realTrend()).data)
    expect(b.growth).toBeNull()
    expect(b.growthBusy).toBe(true)
    expect(b.truncated).toBe(true)
    expect(b.maxPoints).toBe(200)
  })

  it('PDF kategori puanları ekranın kuralı: kısmi analizde güvenlik de soluk (partial)', () => {
    const d = realData(1)
    const rows = categoryScoreRows(d.analysis, { ...d.scan, capped: true })
    expect(rows.find((r) => r.key === 'security')).toMatchObject({ score: 27, tone: 'muted', partial: true })
    expect(rows.find((r) => r.key === 'performance')).toMatchObject({ tone: 'muted', partial: true })
    const full = categoryScoreRows(d.analysis, d.scan)
    expect(full.find((r) => r.key === 'security')).toMatchObject({ score: 27, letter: 'F', partial: false })
    expect(full.find((r) => r.key === 'security').tone).not.toBe('muted')
  })

  it('withTotals türevi: gerçek yanıtta *_total alanları', () => {
    const d = withTotals(realData(1))
    expect(d.analysis.media.images.missing_dimensions_total).toBe(130)
  })
})
