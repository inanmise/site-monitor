import { describe, it, expect, vi, beforeEach } from 'vitest'

// Kaynak analizi prs3 F3 (fe2) — yönetici PDF'i ve Excel'in A–E / site sayfaları. jsPDF'in kendisi sınanmaz: sahte belge
// hangi metinlerin ve tabloların hangi sırayla çizildiğini kaydeder (exportInventory.test.jsx deseni — autotable v5
// sözleşmesi: `autoTable(doc, opts)`). Excel'de buildXlsx'e giden sayfalar yakalanır.
const calls = { text: [], tables: [], pages: 0, rects: 0 }
vi.mock('jspdf', () => {
  class FakeDoc {
    constructor() {
      this.internal = { pageSize: { getWidth: () => 842, getHeight: () => 595 } }
      this.lastAutoTable = null
      calls.pages = 1
    }
    setFont() { return this }
    setFontSize() { return this }
    setTextColor() { return this }
    setDrawColor() { return this }
    setFillColor() { return this }
    addPage() { calls.pages += 1; return this }
    roundedRect() { return this }
    rect() { calls.rects += 1; return this }
    line() { return this }
    getTextWidth() { return 10 }
    splitTextToSize(txt) { return [String(txt)] }
    text(t) { (Array.isArray(t) ? t : [t]).forEach((x) => calls.text.push(String(x))); return this }
    output() { return new Blob(['%PDF']) }
  }
  return { jsPDF: FakeDoc }
})
vi.mock('jspdf-autotable', () => ({
  default: (doc, opts) => { calls.tables.push(opts); doc.lastAutoTable = { finalY: (opts.startY || 0) + 40 } },
}))
vi.mock('../utils/pdfBrand.js', () => ({ drawBrandHeader: vi.fn(async () => 60) }))
const dl = vi.hoisted(() => ({ triggerDownload: vi.fn(), registerRobotoFont: vi.fn(async () => {}) }))
vi.mock('../utils/exportInventory.js', () => dl)
const xl = vi.hoisted(() => ({ sheets: null }))
vi.mock('../utils/xlsxWriter.js', () => ({ XLSX_MIME: 'x', buildXlsx: (s) => { xl.sheets = s; return new Uint8Array([1]) } }))

const { exportResourceScanPdf, exportResourceScanXlsx } = await import('../components/page/resourcescan/resourceScanExport.js')
const { TR } = await import('../i18n/tr.js')
const { deepDetail, siteDetail } = await import('./helpers/resourceScanFgFixtures.js')
const { scanDetail, scanSummary } = await import('./helpers/resourceScanFixtures.js')

const t = (k, ...a) => {
  const v = TR[k]
  if (typeof v !== 'string') return k
  return v.replace(/\{(\d+)\}/g, (_, i) => (a[Number(i)] === undefined ? `{${i}}` : String(a[Number(i)])))
}
const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/' }
const allText = () => [...calls.text, ...calls.tables.flatMap((o) => [...(o.head || []).flat(), ...(o.body || []).flat()])].join('\n')

beforeEach(() => {
  calls.text = []
  calls.tables = []
  calls.pages = 0
  calls.rects = 0
  xl.sheets = null
  dl.triggerDownload.mockClear()
})

describe('yönetici PDF\'i', () => {
  it('ilk sayfa yönetici özeti: kategori puanları, bütçe, öncelikli eylemler, kategori grafiği, karşılaştırma, A–E, site; sonra ayrıntı', async () => {
    const d = siteDetail(30)
    await exportResourceScanPdf({ monitor: MONITOR, ...d, t, locale: 'tr-TR', prev: scanSummary(12, { grade: 64, security_score: 50 }) })
    const text = calls.text
    expect(text.indexOf('Yönetici özeti')).toBeGreaterThanOrEqual(0)
    // sıra: yönetici özeti ayrıntıdan önce, ayrıntı yeni sayfada
    expect(text.indexOf('Yönetici özeti')).toBeLessThan(text.indexOf('Ayrıntılı sonuç'))
    expect(calls.pages).toBeGreaterThanOrEqual(2)
    for (const s of ['Kategori puanları', 'Performans bütçesi', 'Öncelikli eylemler', 'Sayfa yükünün kategorilere dağılımı',
      'Önceki analizle karşılaştırma', 'Ayrıntılı denetim özetleri', 'Site analizi']) expect(text).toContain(s)
    expect(text).toContain('58 / 100 · E')
    expect(text).toContain('—')                                  // erişilebilirlik hesaplanmadı
    expect(text).toContain('Bu analizde hesaplanmadı')
    const all = allText()
    expect(all).toContain('Bütçe aşıldı: 2 kalem')
    expect(all).toContain('Sayfa boyutu (en çok)')
    expect(all).toContain('Ölçülemedi')
    expect(all).toContain('Eksik güvenlik başlıkları: Content-Security-Policy.')
    expect(all).toContain('+8 · iyileşti')
    expect(all).toMatch(/hakkimizda-ve-yonetim-kurulu/)        // en kötü sayfa tablosu
    expect(calls.rects).toBeGreaterThanOrEqual(5)                // yığılı çubuk + açıklama kareleri
    // ham anahtar ya da doldurulmamış yer tutucu yok
    expect(all).not.toMatch(/\bprs\.[a-z]+\.|\{\d\}/)
    expect(dl.triggerDownload).toHaveBeenCalledTimes(1)
    expect(dl.triggerDownload.mock.calls[0][1]).toMatch(/\.pdf$/)
  })

  it('eski analiz (algoritma 1–2, bütçe alanı yok, önceki yok): bölümler "yok — yeniden analiz edin" der, uydurmaz', async () => {
    const d = scanDetail(10)
    await exportResourceScanPdf({ monitor: MONITOR, ...d, t, locale: 'tr-TR', budget: null, prev: null })
    const all = allText()
    expect(all).toContain(TR['prs.pdf.budgetMissing'])
    expect(all).toContain(TR['prs.pdf.compareNone'])
    expect(all).toContain(TR['prs.pdf.deepMissing'])
    expect(all).toContain('Bu analiz eski algoritmayla (v1) yapıldı')
    expect(all).not.toContain('Site analizi')
    expect(all).not.toMatch(/\bprs\.[a-z]+\.|\{\d\}/)
  })
})

describe('Excel A–E ve site sayfaları', () => {
  it('sürüm 3 site analizi: Yönetici özeti, A–E sayfaları, Sayfalar, Ortak kaynaklar', () => {
    const d = siteDetail(30)
    exportResourceScanXlsx({ monitor: MONITOR, ...d, t, locale: 'tr-TR', prev: scanSummary(12) })
    const names = xl.sheets.map((s) => s.name)
    expect(names).toEqual(['Özet', 'Yönetici özeti', 'Not', 'Türler', 'Nesneler', 'Öneriler', 'Görseller', 'Üçüncü taraflar',
      'Yükleme ve ağ', 'İsraf ve önbellek', 'Görsel ve yazı tipi', 'Güvenlik', 'SEO ve erişilebilirlik', 'Sayfalar', 'Ortak kaynaklar', 'Notlar'])
    const exec = xl.sheets[1].rows.map((r) => r.join(' | '))
    expect(exec).toContain('Bütçe aşıldı: 2 kalem')
    const pages = xl.sheets.find((s) => s.name === 'Sayfalar').rows
    expect(pages).toHaveLength(6)
    expect(xl.sheets.every((s) => s.rows.flat().every((c) => typeof c !== 'string' || !/\bprs\.[a-z]+\.|\{\d\}/.test(c)))).toBe(true)
  })

  it('eski analiz: A–E ve site sayfası YAZILMAZ (yanıltıcı boş sayfa yok); yönetici özeti eksikleri söyler', () => {
    const d = scanDetail(10)
    exportResourceScanXlsx({ monitor: MONITOR, ...d, t, locale: 'tr-TR' })
    const names = xl.sheets.map((s) => s.name)
    expect(names).not.toContain('Yükleme ve ağ')
    expect(names).not.toContain('Sayfalar')
    expect(xl.sheets[1].rows.map((r) => r.join(' | '))).toContain(TR['prs.pdf.deepMissing'])
  })

  it('sürüm 3 tek sayfa: A–E var, site yok', () => {
    exportResourceScanXlsx({ monitor: MONITOR, ...deepDetail(12), t, locale: 'tr-TR' })
    const names = xl.sheets.map((s) => s.name)
    expect(names).toContain('Güvenlik')
    expect(names).not.toContain('Ortak kaynaklar')
  })
})
