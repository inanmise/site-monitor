import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Envanter CSV dışa aktarımı — özellikle BAŞLIK ↔ DEĞER HİZASI.
 *
 * Bu dosya %3.7 satır kapsamıyla duruyordu: sütun ekleyen herkes hizayı gözle doğrulamak
 * zorundaydı. Başlık listesine bir giriş eklenip değer listesine eklenmezse (ya da tersi)
 * CSV bir sütun KAYAR: "Takım" sütununda kritiklik, "Kritiklik" sütununda satın alan görünür.
 * Hiçbir yerde hata çıkmaz, dosya açılır — yalnız veriler yanlış sütundadır.
 *
 * Dışa aktarım tarayıcı indirmesi tetikliyor; Blob içeriğini yakalayıp doğruluyoruz.
 */

vi.mock('../api/client', () => ({ formatDate: (s) => (s ? `D:${s}` : '') }))
vi.mock('../utils/pdfBrand.js', () => ({ drawBrandHeader: vi.fn(async () => 60) }))

// ── PDF yolu icin sahte jsPDF ──────────────────────────────────────────────
// Amac jsPDF'i test etmek DEGIL: PDF disa aktariminin INVENTORY_FLAGS'in TAMAMINI tasidigini
// dogrulamak. CSV tarafindaki "bir sutun eklenip digeri unutulur" hatasinin ikizi burada da
// var (exportInventory.js:221-222 uclu satirlar halinde bayrak geziyor) ve PDF yolu tamamen
// test disiydi — dosyanin kapsanmayan kismi neredeyse butunuyle burasi.
const pdfCalls = { text: [], autoTable: [] }
vi.mock('jspdf', () => {
  class FakeDoc {
    constructor() {
      this.internal = { pageSize: { getWidth: () => 595, getHeight: () => 842 }, getNumberOfPages: () => 1 }
      this.lastAutoTable = { finalY: 100 }
    }
    setFont() { return this }
    setFontSize() { return this }
    setTextColor() { return this }
    setDrawColor() { return this }
    setFillColor() { return this }
    setPage() { return this }
    addPage() { this.lastAutoTable = { finalY: 100 }; return this }
    addFileToVFS() { return this }
    addFont() { return this }
    line() { return this }
    roundedRect() { return this }
    getTextWidth() { return 10 }
    splitTextToSize(txt) { return [String(txt)] }
    text(t) { pdfCalls.text.push(String(t)); return this }
    save() { return this }
  }
  return { jsPDF: FakeDoc }
})
// autotable v5: eklenti metodu (doc.autoTable) kaldirildi; tablo default export'a
// dokuman verilerek cizilir. Mock GERCEK sozlesmeyi taklit etmeli, yoksa uretim kodu
// degisirken test yesil kalir ve gocu kacirir (bu goc tam da bu testlerle yakalandi).
vi.mock('jspdf-autotable', () => ({
  default: (doc, opts) => { pdfCalls.autoTable.push(opts); doc.lastAutoTable = { finalY: 100 } },
}))

const { exportInventoryCsv, exportInventoryPdf } = await import('../utils/exportInventory')
const { INVENTORY_FLAGS } = await import('../utils/inventoryFlags.js')

/** Etiketi anahtarın kendisi yapan t(): başlıklar okunabilir ve karşılaştırılabilir kalır. */
const t = (k) => k

const TEAMS = [{ id: 5, name: 'Takım A' }]

const ITEM = {
  domain: 'a.example.com', port: 8443, team_id: 5, tier: 2, purchased_by: 'ACME',
  svc_mgmt_contact: 'Ad Soyad - ad.soyad@example.com',
  app_dev_contact: 'ekip@example.com',
  iis_admin_contact: 'iis@example.com',
  waf_admin_contact: 'waf@example.com',
  active: true,
  change_description: 'ilk satır\n  ikinci satır',
  expected_fingerprint: 'AA:BB', expected_subject: 'CN=a.example.com',
  created_at: '2026-01-01T00:00:00', updated_at: '2026-02-02T00:00:00',
}

/** Son tetiklenen indirmenin CSV metnini döndürür. */
let lastCsv = null

beforeEach(() => {
  lastCsv = null
  vi.restoreAllMocks()
  global.URL.createObjectURL = vi.fn(() => 'blob:x')
  global.URL.revokeObjectURL = vi.fn()
  // Blob.text() jsdom'da async; içerik yakalamak için yapıcıyı sarmalıyoruz.
  const RealBlob = global.Blob
  global.Blob = class extends RealBlob {
    constructor(parts, opts) { super(parts, opts); lastCsv = parts.join('') }
  }
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

/** CSV satırını alanlara böler — tırnaklı alanlar ve içindeki virgüller korunur. */
function splitCsvLine(line) {
  const out = []
  let cur = '', inQ = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (inQ) {
      if (c === '"' && line[i + 1] === '"') { cur += '"'; i++ }
      else if (c === '"') inQ = false
      else cur += c
    } else if (c === '"') inQ = true
    else if (c === ',') { out.push(cur); cur = '' }
    else cur += c
  }
  out.push(cur)
  return out
}

describe('exportInventoryCsv', () => {
  it('BAŞLIK ve DEĞER sayısı BİREBİR eşit — bir sütun eklenip diğeri unutulursa CSV kayar', () => {
    exportInventoryCsv([ITEM], TEAMS, t)

    const [head, row] = lastCsv.replace(/^﻿/, '').split('\r\n')
    expect(splitCsvLine(head)).toHaveLength(splitCsvLine(row).length)
    // Sabit sütunlar + bayraklar: sayı değişirse bu iddia da bilinçli güncellenmeli.
    expect(splitCsvLine(head)).toHaveLength(15 + INVENTORY_FLAGS.length)
  })

  it('Sorumlu Ekipler sütunları DOĞRU sırada ve doğru değerle çıkar', () => {
    exportInventoryCsv([ITEM], TEAMS, t)

    const [head, row] = lastCsv.replace(/^﻿/, '').split('\r\n')
    const cols = splitCsvLine(head)
    const vals = splitCsvLine(row)
    const at = (key) => vals[cols.indexOf(key)]

    expect(at('inv.formSvcMgmt')).toBe('Ad Soyad - ad.soyad@example.com')
    expect(at('inv.formAppDev')).toBe('ekip@example.com')
    expect(at('inv.formIisAdmin')).toBe('iis@example.com')
    expect(at('inv.formWafAdmin')).toBe('waf@example.com')
    // Komşu sütunlar kaymamış: sorumlu ekipler satın-alan ile durum arasına girdi.
    expect(at('inv.formPurchasedBy')).toBe('ACME')
    expect(at('inv.colActive')).toBe('inv.active')
  })

  it('Sorumlu ekip alanı boşsa hücre BOŞ kalır (undefined yazılmaz)', () => {
    exportInventoryCsv([{ ...ITEM, waf_admin_contact: null }], TEAMS, t)

    const [head, row] = lastCsv.replace(/^﻿/, '').split('\r\n')
    const cols = splitCsvLine(head)
    expect(splitCsvLine(row)[cols.indexOf('inv.formWafAdmin')]).toBe('')
  })

  it('UTF-8 BOM ile başlar (Excel TR karakterleri bozmasın) ve satır sayısını döner', () => {
    const n = exportInventoryCsv([ITEM, { ...ITEM, domain: 'b.example.com' }], TEAMS, t)

    expect(n).toBe(2)
    expect(lastCsv.startsWith('﻿')).toBe(true)
  })

  it('Çok satırlı açıklama TEK satıra indirilir — CSV satır yapısı bozulmaz', () => {
    exportInventoryCsv([ITEM], TEAMS, t)

    // Başlık + tek veri satırı: açıklamadaki satır sonu kaçmış olsaydı 3 satır olurdu.
    expect(lastCsv.replace(/^﻿/, '').split('\r\n')).toHaveLength(2)
  })

  // ── Denetim eklemeleri: bayrak listesinin TAMAMI + kacis sozlesmesi ────────

  it('INVENTORY_FLAGS listesinin TAMAMI baslikta yer alir', () => {
    exportInventoryCsv([ITEM], TEAMS, t)
    const [head] = lastCsv.replace(/^﻿/, '').split('\r\n')
    for (const f of INVENTORY_FLAGS) {
      expect(head, `baslikta eksik bayrak: ${f.key}`).toContain(f.labelKey)
    }
  })

  it('bayraklarin hepsi true iken satirda da hepsi tasinir (hizali)', () => {
    const allTrue = Object.fromEntries(INVENTORY_FLAGS.map(f => [f.key, true]))
    exportInventoryCsv([{ ...ITEM, ...allTrue }], TEAMS, t)
    const [head, row] = lastCsv.replace(/^﻿/, '').split('\r\n')
    const cells = splitCsvLine(row)
    expect(cells.filter(c => c === 'inv.yes')).toHaveLength(INVENTORY_FLAGS.length)
    expect(cells).toHaveLength(splitCsvLine(head).length)
  })

  it('use_proxy REGRESYONU: bayrak listesinde VE disa aktarimda', () => {
    // Gecmiste tam bu alan envantere eklenip disa aktarima baglanmamisti.
    const flag = INVENTORY_FLAGS.find(f => f.key === 'use_proxy')
    expect(flag, 'use_proxy INVENTORY_FLAGS listesinden dusmus').toBeTruthy()
    exportInventoryCsv([{ ...ITEM, use_proxy: true }], TEAMS, t)
    expect(lastCsv).toContain(flag.labelKey)
  })

  it('formul enjeksiyonu notrlenir: "=" ile baslayan domain FORMUL olarak acilmaz', () => {
    // csvCell sozlesmesi (CWE-1236). Disa aktarimin onu gercekten kullandigini pinler:
    // kendi kacisini yazan bir disa aktarim burada kirilir.
    exportInventoryCsv([{ ...ITEM, domain: '=cmd|calc!A1' }], TEAMS, t)
    expect(lastCsv).not.toMatch(/(^|,)=cmd/m)
  })

  it('bos envanterde yalniz baslik satiri uretilir (cokmez)', () => {
    expect(exportInventoryCsv([], TEAMS, t)).toBe(0)
    expect(lastCsv.replace(/^﻿/, '').split('\r\n')).toHaveLength(1)
  })
})

describe('exportInventoryPdf', () => {
  beforeEach(() => {
    pdfCalls.text.length = 0
    pdfCalls.autoTable.length = 0
    global.fetch = vi.fn(async () => ({ ok: true, arrayBuffer: async () => new ArrayBuffer(8) }))
  })

  it('PDF yolu da INVENTORY_FLAGS listesinin TAMAMINI tasir (CSV ile ayni sozlesme)', async () => {
    await exportInventoryPdf([ITEM], TEAMS, t)

    // Bayraklar uclu satirlar halinde bir autoTable govdesine yaziliyor (exportInventory.js:221).
    const opsCells = pdfCalls.autoTable.flatMap(o => (o.body ?? []).flat())
    for (const f of INVENTORY_FLAGS) {
      expect(opsCells, `PDF operasyonel tabloda eksik bayrak: ${f.key}`).toContain(f.labelKey)
    }
  })

  it('bayrak degeri PDF hucresine yansir (evet/hayir ayrimi kayboluyor mu)', async () => {
    const flag = INVENTORY_FLAGS[0]
    await exportInventoryPdf([{ ...ITEM, [flag.key]: true }], TEAMS, t)
    const cells = pdfCalls.autoTable.flatMap(o => (o.body ?? []).flat())
    expect(cells.some(c => String(c).includes('inv.yes'))).toBe(true)
  })

  it('her envanter satiri PDF dosyasina yazilir (biri sessizce dusmez)', async () => {
    await exportInventoryPdf(
      [{ ...ITEM, domain: 'bir.example.com' }, { ...ITEM, domain: 'iki.example.com' }], TEAMS, t)
    const printed = pdfCalls.text.join(' ')
    expect(printed).toContain('bir.example.com')
    expect(printed).toContain('iki.example.com')
  })

  it('bos envanterde cokmez', async () => {
    await expect(exportInventoryPdf([], TEAMS, t)).resolves.not.toThrow()
  })
})
