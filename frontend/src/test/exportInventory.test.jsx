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
vi.mock('../utils/pdfBrand.js', () => ({ drawBrandHeader: vi.fn() }))

const { exportInventoryCsv } = await import('../utils/exportInventory')
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
})
