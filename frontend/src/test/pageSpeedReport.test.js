import { describe, it, expect, vi, beforeEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

// Sayfa Hızı 2. sürüm (2026-11-15, madde 19): performans raporu. PDF'e basılan HER karakter gömülü Roboto'da var (eksik glif
// boş kutu basar); Excel / PDF ham anahtar ("pspd.") ya da "undefined" / "NaN" içermez; indirme başlamazsa false döner.
const calls = { text: [], tables: [] }
vi.mock('jspdf', () => {
  class FakeDoc {
    constructor() { this.internal = { pageSize: { getWidth: () => 595, getHeight: () => 842 } }; this.lastAutoTable = null }
    setFont() { return this }
    setFontSize() { return this }
    setTextColor() { return this }
    addPage() { return this }
    splitTextToSize(txt) { return [String(txt)] }
    text(t) { (Array.isArray(t) ? t : [t]).forEach((x) => calls.text.push(String(x))); return this }
    output() { return new Blob(['%PDF']) }
  }
  return { jsPDF: FakeDoc }
})
vi.mock('jspdf-autotable', () => ({
  default: (doc, opts) => { calls.tables.push(opts); doc.lastAutoTable = { finalY: (opts.startY || 0) + 40 } },
}))
vi.mock('../api/client', () => ({ formatDate: (s) => String(s ?? '') }))
vi.mock('../utils/pdfBrand.js', () => ({ drawBrandHeader: vi.fn(async (_d, title) => { calls.text.push(String(title)); return 60 }) }))
const dl = vi.hoisted(() => ({ fail: false, files: [] }))
vi.mock('../utils/exportInventory.js', () => ({
  triggerDownload: vi.fn((blob, name) => { if (dl.fail) throw new Error('DOWNLOAD_FAILED'); dl.files.push(name) }),
  registerRobotoFont: vi.fn(async () => {}),
}))
const xl = vi.hoisted(() => ({ sheets: null }))
vi.mock('../utils/xlsxWriter.js', () => ({ XLSX_MIME: 'x', buildXlsx: (s) => { xl.sheets = s; return new Uint8Array([1]) } }))

const { exportPageSpeedPdf, exportPageSpeedXlsx } = await import('../components/pagespeed/deep/pageSpeedReport.js')
const { gradeSnapshot, buildWaterfall } = await import('../components/pagespeed/deep/pageSpeedDeepModel.js')
const { TR } = await import('../i18n/tr.js')
const { EN } = await import('../i18n/en.js')
const { ttfCodePoints, missingGlyphs } = await import('./helpers/ttfCmap.js')

const tOf = (D) => (k, ...a) => {
  const v = D[k]
  if (typeof v !== 'string') return k
  return v.replace(/\{(\d+)\}/g, (_, i) => (a[Number(i)] === undefined ? `{${i}}` : String(a[Number(i)])))
}
const FONT = path.resolve(process.cwd(), 'public/fonts')
const REGULAR = ttfCodePoints(fs.readFileSync(path.join(FONT, 'Roboto-Regular.ttf')))
const BOLD = ttfCodePoints(fs.readFileSync(path.join(FONT, 'Roboto-Bold.ttf')))

const KB = 1024
const monitor = { id: 3, name: 'Ödeme sayfası — Kurumsal İnternet Şubesi (Bölge 1)', url: 'https://www.example.com.tr/odeme?x=1' }
function data(over = {}) {
  return {
    check_id: 77, has_start: true, truncated: false, total: 4,
    check: { checked_at: '2026-11-15T10:00:00', ttfb_ms: 820, html_ms: 300, response_ms: 5200, total_bytes: 3200 * KB, html_bytes: 50 * KB,
      request_count: 95, failed_count: 1, dns_ms: 12, connect_ms: 30, tls_ms: 40, server_ms: 700, time_truncated: false, ...over },
    resources: [
      { url: 'https://www.example.com.tr/hero-büyük.jpg', type: 'IMG', start_ms: 400, duration_ms: 1300, bytes: 900 * KB, http_status: 200 },
      { url: 'https://cdn.üçüncü.example/lib.js', type: 'JS', start_ms: 320, duration_ms: 200, bytes: 700 * KB, http_status: 200, third_party: true },
      { url: 'https://www.example.com.tr/eksik.css', type: 'CSS', start_ms: 310, duration_ms: 20, bytes: null, http_status: 404, failed: true },
      { url: 'https://www.example.com.tr/font.woff2', type: 'FONT', start_ms: null, duration_ms: 60, bytes: 30 * KB, http_status: 200 },
    ],
  }
}

beforeEach(() => { calls.text = []; calls.tables = []; xl.sheets = null; dl.fail = false; dl.files = [] })

const cells = (rows) => rows.flat().filter((c) => c !== null && c !== undefined).map(String)

for (const [lang, D] of [['tr', TR], ['en', EN]]) {
  describe(`rapor (${lang})`, () => {
    const t = tOf(D)
    it('Excel: dört sayfa, ham anahtar / undefined / NaN yok, tüm kaynaklar en ağırdan', async () => {
      const d = data()
      const ok = await exportPageSpeedXlsx({ monitor, data: d, grade: gradeSnapshot(d), waterfall: buildWaterfall(d), t })
      expect(ok).toBe(true)
      expect(xl.sheets.map((s) => s.name)).toEqual([D['pspd.rep.sheet.summary'], D['pspd.rep.sheet.grade'], D['pspd.rep.sheet.types'], D['pspd.rep.sheet.resources']])
      const all = xl.sheets.flatMap((s) => cells(s.rows))
      expect(all.filter((c) => /pspd\.|undefined|NaN/.test(c))).toEqual([])
      const res = xl.sheets[3].rows
      expect(res.length).toBe(5)                         // başlık + 4 kaynak (başlangıcı bilinmeyen de listelenir)
      expect(res[1][0]).toContain('hero-büyük.jpg')       // en ağır ilk
      expect(res.find((r) => String(r[0]).includes('eksik.css'))[7]).toBe(D['pspd.rep.yes'])
      expect(dl.files[0]).toMatch(/\.xlsx$/)
    })

    it('PDF: her karakter gömülü Roboto\'da var; öneriler ve kısmi uyarısı basılır', async () => {
      const d = data({ time_truncated: true })
      const grade = gradeSnapshot(d)
      const ok = await exportPageSpeedPdf({ monitor, data: d, grade, waterfall: buildWaterfall(d), t })
      expect(ok).toBe(true)
      const printed = [...calls.text, ...calls.tables.flatMap((o) => [...o.head.flat(), ...o.body.flat()]).map(String)]
      expect(printed.filter((c) => /pspd\.|undefined|NaN/.test(c))).toEqual([])
      const joined = printed.join('\n')
      expect(missingGlyphs(joined, REGULAR)).toEqual([])
      expect(missingGlyphs(joined, BOLD)).toEqual([])
      expect(joined).toContain(D['pspd.grade.partialNote'])
      for (const code of ['FAILED_RESOURCES', 'SLOW_TTFB', 'HEAVY_PAGE', 'LARGE_IMAGES', 'HEAVY_JS', 'TOO_MANY_REQUESTS', 'SLOW_RESOURCES']) {
        expect(grade.recs.some((r) => r.code === code), code).toBe(true)
      }
      expect(dl.files[0]).toMatch(/\.pdf$/)
    })

    it('kaynak tavanında kesilen ölçüm "Kısmi ölçüm: Evet" (yalnız süre tavanı değil)', async () => {
      const d = data({ capped: true })
      await exportPageSpeedXlsx({ monitor, data: d, grade: gradeSnapshot(d), t })
      expect(xl.sheets[0].rows.find((r) => r[0] === D['pspd.rep.partial'])[1]).toBe(D['pspd.rep.yes'])
    })

    it('kısmi notu PDF\'te sonraki başlıkla üst üste binmez', async () => {
      const d = data({ time_truncated: true })
      await exportPageSpeedPdf({ monitor, data: d, grade: gradeSnapshot(d), t })
      const recsTable = calls.tables[2]   // özet, bileşenler, öneriler
      const compTable = calls.tables[1]
      expect(recsTable.startY).toBeGreaterThan(compTable.startY + 40 + 12 + 11)
    })

    it('not hesaplanamadıysa "hesaplanamadı" yazar, sahte 0 değil', async () => {
      const d = data({ response_ms: null })
      await exportPageSpeedXlsx({ monitor, data: d, grade: gradeSnapshot(d), t })
      const row = xl.sheets[0].rows.find((r) => r[0] === D['pspd.rep.grade'])
      expect(row[1]).toBe(D['pspd.grade.unknownShort'])
    })

    it('indirme başlamazsa false (başarı bildirimi verilmez)', async () => {
      dl.fail = true
      const d = data()
      expect(await exportPageSpeedXlsx({ monitor, data: d, grade: gradeSnapshot(d), t })).toBe(false)
      expect(await exportPageSpeedPdf({ monitor, data: d, grade: gradeSnapshot(d), t })).toBe(false)
    })
  })
}

describe('dosya adı', () => {
  it('Türkçe / boşluklu adlar güvenli ASCII, tarih ölçüm gününden', async () => {
    const d = data()
    await exportPageSpeedXlsx({ monitor, data: d, grade: gradeSnapshot(d), t: tOf(TR) })
    expect(dl.files[0]).toMatch(/^[\w.-]+-sayfa-hizi-2026-11-15\.xlsx$/)
  })
})
