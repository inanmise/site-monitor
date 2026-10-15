import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  KINDS, LIMITS, TREND_MIN_POINTS, TREND_MONTHS, TREND_SUFFIX, compactNumber, isTrendable, newSection, pruneRemovedTrendRefs,
  shortMonth, templateDiff, templateErrors, trendLabelMode, trendPoints, trendRefs, trendSectionsUsing, trendTicks, trendableFields,
} from '../components/techerror/techErrorModel.js'

// Otomatik trend (2026-10-15, Faz C): istemci aynası — sınırlar, kısaltılmış çubuk etiketi ve grafik başlığı eki sunucudaki
// TechErrorTemplate / TechErrorTrend / TechErrorFormat.compact / TechErrorReportPdfWriter ile AYNI (TechErrorTrendTest).
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const JAVA = (f) => fs.readFileSync(path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service', f), 'utf8')
const javaInt = (src, name) => Number(new RegExp(`${name}\\s*=\\s*([0-9_]+)`).exec(src)[1].replace(/_/g, ''))

const TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'm1', kind: 'metrics', title: 'Özet', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent' },
      { id: 'had', label: 'Kesinti oldu mu?', type: 'yesno' },
      { id: 'outage', label: 'Kesinti hatası', type: 'number', skip_if_no: 'had' },
      { id: 'note', label: 'Not', type: 'text' },
    ] },
    { id: 'm2', kind: 'metrics', title: 'Adetler', fields: [
      { id: 'total-tx', label: 'Toplam işlem', type: 'number' },
      { id: 'unmapped', label: 'Maplenmemiş', type: 'number' },
      { id: 'quota', label: 'Kota', type: 'number' },
    ] },
    { id: 'tr', kind: 'trend', title: 'Otomatik Trendler (Son 12 Ay)', series: ['error-rate', 'total-tx'] },
  ],
}

const months = (endYear, endMonth) => Array.from({ length: 12 }, (_, i) => {
  const d = new Date(Date.UTC(endYear, endMonth - 1 - (11 - i), 1))
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 }
})

describe('sunucuyla aynı sabitler', () => {
  it('ay sayısı, en az nokta, seri sınırı ve tür listesi Java kaynağından', () => {
    const trend = JAVA('techerror/TechErrorTrend.java')
    expect(TREND_MONTHS).toBe(javaInt(trend, 'MONTHS'))
    expect(TREND_MIN_POINTS).toBe(javaInt(trend, 'MIN_POINTS'))
    const tpl = JAVA('techerror/TechErrorTemplate.java')
    expect(LIMITS.trendSeries).toBe(javaInt(tpl, 'MAX_TREND_SERIES'))
    expect(KINDS).toContain('trend')
  })

  it('grafik başlığı eki PDF ile aynı', () => {
    const pdf = JAVA('report/TechErrorReportPdfWriter.java')
    expect(pdf).toContain(`en ? "${TREND_SUFFIX.en.trim()}" : "${TREND_SUFFIX.tr.trim()}"`)
  })

  it('kısaltılmış çubuk etiketi: TechErrorTrendTest.compactLabels ile aynı tablo', () => {
    const test = fs.readFileSync(path.join(ROOT,
      'backend/src/test/java/com/sitemonitor/service/techerror/TechErrorTrendTest.java'), 'utf8')
    const rows = [...test.matchAll(/compact\(([-0-9_.d]+), "(number|percent)", (true|false)\)\)\.isEqualTo\("([^"]+)"\)/g)]
    expect(rows.length).toBeGreaterThanOrEqual(8)
    for (const [, num, type, en, want] of rows) {
      const v = Number(num.replace(/_/g, '').replace(/d$/, ''))
      expect(compactNumber(v, type, en === 'true' ? 'en' : 'tr'), `${num} ${type} ${en}`).toBe(want)
    }
  })

  it('kısaltma sınırları: 999 tam, 1000 → 1B; 99.950 yarım yukarı → 100B; boş değer tire', () => {
    expect(compactNumber(999, 'number', 'tr')).toBe('999')
    expect(compactNumber(1000, 'number', 'tr')).toBe('1B')
    expect(compactNumber(1000, 'number', 'en')).toBe('1K')
    expect(compactNumber(99_950, 'number', 'tr')).toBe('100B')
    expect(compactNumber(150_000, 'number', 'en')).toBe('150K')
    expect(compactNumber(-0, 'percent', 'tr')).toBe('%0')
    expect(compactNumber(-0, 'number', 'en')).toBe('0')
    expect(compactNumber(null, 'number', 'tr')).toBe('—')
    expect(compactNumber(Number.NaN, 'number', 'tr')).toBe('—')
  })
})

describe('trend başvuruları', () => {
  it('yalnız sayı / yüzde alanları seçilebilir, bölüm adıyla, şablon sırasıyla', () => {
    expect(isTrendable({ type: 'number' })).toBe(true)
    expect(isTrendable({ type: 'percent' })).toBe(true)
    expect(isTrendable({ type: 'yesno' })).toBe(false)
    expect(isTrendable(null)).toBe(false)
    expect(trendableFields(TPL).map((f) => [f.id, f.section])).toEqual([
      ['error-rate', 'Özet'], ['outage', 'Özet'], ['total-tx', 'Adetler'], ['unmapped', 'Adetler'], ['quota', 'Adetler'],
    ])
  })

  it('silinen ya da türü değişen alan kaydı engellemez; durumu söylenir', () => {
    const s = { id: 't', kind: 'trend', title: 'Trend', series: ['error-rate', 'gone', 'note'] }
    expect(trendRefs(TPL, s).map((r) => [r.id, r.status])).toEqual([['error-rate', 'ok'], ['gone', 'missing'], ['note', 'notNumeric']])
    const tpl = { ...TPL, sections: [...TPL.sections, s] }
    expect(templateErrors(tpl)['section:t']).toBeUndefined()
  })

  it('yeni trend bölümü ilk dört sayı / yüzde alanıyla başlar; boş ya da fazla seri hata', () => {
    const s = newSection('trend', TPL, { title: 'T' })
    expect(s.kind).toBe('trend')
    expect(s.series).toEqual(['error-rate', 'outage', 'total-tx', 'unmapped'])
    const none = newSection('trend', { sections: [] }, { title: 'T' })
    expect(none.series).toEqual([])
    const withSec = (sec) => ({ ...TPL, sections: [...TPL.sections, sec] })
    expect(templateErrors(withSec({ ...s, series: [] }))[`section:${s.id}`]).toBe('te.err.noSeries')
    const nine = Array.from({ length: LIMITS.trendSeries + 1 }, (_, i) => `f${i}`)
    expect(templateErrors(withSec({ ...s, series: nine }))[`section:${s.id}`]).toBe('te.err.tooManySeries')
  })

  it('sürüm farkı: seri değişikliği önce / sonra sayısıyla', () => {
    const cur = { ...TPL, sections: TPL.sections.map((x) => (x.id === 'tr' ? { ...x, series: ['error-rate', 'total-tx', 'quota'] } : x)) }
    expect(templateDiff(TPL, cur)).toEqual([{ group: 'trend', op: 'change', label: 'Otomatik Trendler (Son 12 Ay)', before: 2, after: 3 }])
    const reordered = { ...TPL, sections: TPL.sections.map((x) => (x.id === 'tr' ? { ...x, series: ['total-tx', 'error-rate'] } : x)) }
    expect(templateDiff(TPL, reordered).map((d) => d.group)).toEqual(['trend'])
    expect(templateDiff(TPL, structuredClone(TPL))).toEqual([])
  })
})

describe('alan silinince trend seçimi', () => {
  const tr = (tpl) => tpl.sections.find((x) => x.kind === 'trend').series
  it('silinen alan düşer; önceden olmayan başvuruya ve taşınan alana dokunulmaz; değişiklik yoksa aynı nesne', () => {
    const prev = { ...TPL, sections: TPL.sections.map((x) => (x.kind === 'trend' ? { ...x, series: ['error-rate', 'total-tx', 'gone'] } : x)) }
    expect(trendSectionsUsing(prev, 'total-tx').map((x) => x.id)).toEqual(['tr'])
    expect(trendSectionsUsing(prev, 'quota')).toEqual([])
    // Alan silindi
    const removed = { ...prev, sections: prev.sections.map((x) => (x.id === 'm2' ? { ...x, fields: x.fields.filter((f) => f.id !== 'total-tx') } : x)) }
    expect(tr(pruneRemovedTrendRefs(prev, removed))).toEqual(['error-rate', 'gone'])
    // Bölüm silindi
    const noSection = { ...prev, sections: prev.sections.filter((x) => x.id !== 'm1') }
    expect(tr(pruneRemovedTrendRefs(prev, noSection))).toEqual(['total-tx', 'gone'])
    // Alan başka bölüme taşındı: kimlik sürer, düşmez
    const f = prev.sections[1].fields[0]
    const moved = { ...prev, sections: prev.sections.map((x) => (x.id === 'm2' ? { ...x, fields: x.fields.slice(1) }
      : x.id === 'm1' ? { ...x, fields: [...x.fields, f] } : x)) }
    expect(pruneRemovedTrendRefs(prev, moved)).toBe(moved)
    // Başlık değişikliği: aynı nesne
    const renamed = { ...prev, title: 'x' }
    expect(pruneRemovedTrendRefs(prev, renamed)).toBe(renamed)
    // Bölümün SON alanı silinirse seçim kalır (boş seçim kaydı kilitlerdi; düzenleyici "şablonda yok" der)
    const only = { ...TPL, sections: TPL.sections.map((x) => (x.kind === 'trend' ? { ...x, series: ['quota'] } : x)) }
    const quotaGone = { ...only, sections: only.sections.map((x) => (x.id === 'm2' ? { ...x, fields: x.fields.filter((y) => y.id !== 'quota') } : x)) }
    expect(pruneRemovedTrendRefs(only, quotaGone)).toBe(quotaGone)
    expect(templateErrors(quotaGone)['section:tr']).toBeUndefined()
  })
})

describe('trendPoints', () => {
  const ms = months(2026, 9)
  const vals = (last) => [null, null, null, null, null, null, null, null, 0.5, 0.48, 0.4, last]
  const trend = { months: ms, values: { 'error-rate': vals(0.3), outage: vals(10) } }

  it('kayıtlı ayları verir, bu ay düzenleyicideki taslaktan (kaydedilmemiş değişiklik görünür)', () => {
    const f = TPL.sections[0].fields[0]
    const pts = trendPoints(trend, 'error-rate', { 'error-rate': 0.42 }, f)
    expect(pts).toHaveLength(12)
    expect(pts[0]).toEqual({ year: 2025, month: 10, value: null, current: false })
    expect(pts[10]).toEqual({ year: 2026, month: 8, value: 0.4, current: false })
    expect(pts[11]).toEqual({ year: 2026, month: 9, value: 0.42, current: true })
    // Taslakta silinen değer: bu ay boş (sunucudaki eski değer gösterilmez)
    expect(trendPoints(trend, 'error-rate', { 'error-rate': null }, f)[11].value).toBeNull()
    expect(trendPoints(trend, 'error-rate', {}, f)[11].value).toBeNull()
    // Taslak verilmezse sunucunun değeri
    expect(trendPoints(trend, 'error-rate', null, f)[11].value).toBe(0.3)
  })

  it('muaf alan (soru "Hayır") bu ay boş; 0 sıfırdır, boş değildir', () => {
    const f = TPL.sections[0].fields[2]
    expect(trendPoints(trend, 'outage', { outage: 7, had: false }, f)[11].value).toBeNull()
    expect(trendPoints(trend, 'outage', { outage: 7, had: true }, f)[11].value).toBe(7)
    expect(trendPoints(trend, 'outage', { outage: 0 }, f)[11].value).toBe(0)
  })

  it('bozuk / eksik veri boş liste ya da boş nokta — sıfır değil', () => {
    const f = TPL.sections[1].fields[0]
    expect(trendPoints(null, 'total-tx', {}, f)).toEqual([])
    expect(trendPoints({ months: ms, values: {} }, 'total-tx', {}, f)).toEqual([])
    expect(trendPoints({ months: ms, values: { 'total-tx': [1, 2] } }, 'total-tx', {}, f)).toEqual([])
    const odd = trendPoints({ months: ms, values: { 'total-tx': [...Array(11).fill('x'), 5] } }, 'total-tx', null, f)
    expect(odd.slice(0, 11).every((p) => p.value === null)).toBe(true)
    expect(odd[11].value).toBe(5)
  })
})

describe('çubuk etiketleri', () => {
  it('çubuk başına genişlik en uzun etikete yetmiyorsa yalnız bu ay; genişlik bilinmiyorsa hepsi', () => {
    const texts = ['', '', '98,2Mn', '101Mn', '125Mn', '', '', '', '', '', '120Mn', '125Mn']
    expect(trendLabelMode(0, texts)).toBe('all')
    expect(trendLabelMode(260, texts)).toBe('current')   // telefon: 21,7 px / çubuk < 6 × 5,6 + 4
    expect(trendLabelMode(480, texts)).toBe('all')       // 40 px / çubuk ≥ 37,6
    expect(trendLabelMode(260, ['%0,4', '%0,5'])).toBe('all')
    expect(trendLabelMode(100, [])).toBe('all')
    // Gerçek ölçüm varsa o kullanılır (Linux'ta "%0,49" ≈ 30 px: 32 px çubukta 4 px boşluk kalmaz → yalnız bu ay)
    const pct = Array(12).fill('%0,49')
    expect(trendLabelMode(384, pct, () => 30)).toBe('current')
    expect(trendLabelMode(384, pct, () => 25)).toBe('all')
    // Ölçüm null dönerse (canvas yok) temkinli tahmine düşer: 5 × 6 + 4 = 34 px gerekir
    expect(trendLabelMode(384, pct, () => null)).toBe('current')
    expect(trendLabelMode(480, pct, () => null)).toBe('all')
  })
})

describe('ay ekseni', () => {
  const pts = Array.from({ length: 12 }, (_, i) => (i < 3 ? { year: 2025, month: 10 + i } : { year: 2026, month: i - 2 }))
  it('geniş grafikte her ay; yıl ilk ayın ve Ocak’ın altında', () => {
    const t = trendTicks(pts, 'tr', 600)
    expect(t.map((x) => x.label)).toEqual(['Eki', 'Kas', 'Ara', 'Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz', 'Tem', 'Ağu', 'Eyl'])
    expect(t.map((x) => x.year).filter(Boolean)).toEqual(['2025', '2026'])
    expect(t[3].year).toBe('2026')
    expect(trendTicks(pts, 'tr', 0)).toEqual(t)        // genişlik bilinmiyor → hepsi
    // Ölçülen ay adı genişliği: 300 px / 12 = 25 px; 22 px adlar sığmaz (22 + 4) → bir atlanır
    expect(trendTicks(pts, 'tr', 300, () => 22).filter((x) => x.label)).toHaveLength(6)
    expect(trendTicks(pts, 'tr', 300, () => 18).filter((x) => x.label)).toHaveLength(12)
  })
  it('dar grafikte bir atlanarak, raporun ayı hep görünür; yıl görünen ilk ayda ve yıl değişince', () => {
    const t = trendTicks(pts, 'en', 260)
    expect(t.map((x) => x.label)).toEqual(['', 'Nov', '', 'Jan', '', 'Mar', '', 'May', '', 'Jul', '', 'Sep'])
    expect(t[1].year).toBe('2025')
    expect(t[3].year).toBe('2026')
    expect(t.filter((x) => x.year).length).toBe(2)
    // Yıl değişimi gizli bir aya denk gelse de sonraki görünen ayda yazılır (Ocak gizli)
    const shifted = Array.from({ length: 12 }, (_, i) => (i < 4 ? { year: 2025, month: 9 + i } : { year: 2026, month: i - 3 }))
    const s = trendTicks(shifted, 'tr', 260)
    expect(s[4]).toEqual({ label: '', year: '' })
    expect(s[5]).toEqual({ label: 'Şub', year: '2026' })
    expect(trendTicks([], 'tr', 100)).toEqual([])
    // Çok dar: üçer atlanır, raporun ayı yine görünür
    expect(trendTicks(pts, 'tr', 100).map((x) => x.label)).toEqual(['', '', 'Ara', '', '', 'Mar', '', '', 'Haz', '', '', 'Eyl'])
  })
})

describe('ay kısa adları şablon dilinde', () => {
  it('TR / EN', () => {
    expect(shortMonth(1, 'tr')).toBe('Oca')
    expect(shortMonth(8, 'tr')).toBe('Ağu')
    expect(shortMonth(5, 'tr')).toBe('May')
    expect(shortMonth(9, 'en')).toBe('Sep')
  })
})
