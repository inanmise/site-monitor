import { describe, it, expect } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'
import { buildXlsx, colName, sheetName, xmlText, XLSX_MIME } from '../utils/xlsxWriter.js'

/**
 * Bağımlılıksız .xlsx yazıcısı (2026-10-08, paylaşılan sertifika Excel dışa aktarımı). Paket gerçek bir OOXML iş kitabıdır:
 * zorunlu parçalar, iyi biçimli XML, metin/sayı hücreleri, başlık stili, dondurma, otomatik süzgeç, kaçış.
 */
const parse = (xml) => new DOMParser().parseFromString(xml, 'application/xml')
const wellFormed = (xml) => parse(xml).getElementsByTagName('parsererror').length === 0

describe('xlsxWriter', () => {
  it('sütun adları ve sayfa adı kuralı', () => {
    expect([0, 25, 26, 27, 51, 52, 701, 702].map(colName)).toEqual(['A', 'Z', 'AA', 'AB', 'AZ', 'BA', 'ZZ', 'AAA'])
    const used = new Set()
    expect(sheetName('Alan adları', used)).toBe('Alan adları')
    expect(sheetName('alan ADLARI', used)).toBe('alan ADLARI (2)')
    expect(sheetName('a/b:c*d?[e]', new Set())).toBe('a b c d  e')
    expect(sheetName('x'.repeat(40), new Set())).toHaveLength(31)
    expect(sheetName('', new Set())).toBe('Sheet')
  })

  it('XML kaçışı + yasak denetim karakterleri atılır', () => {
    expect(xmlText('a<b>&"c"\u0001\u0007d\te')).toBe('a&lt;b&gt;&amp;&quot;c&quot;d\te')
  })

  it('paket: zorunlu parçalar, iyi biçimli XML, başlık kalın, sayı <v>, metin inlineStr, dondurma + süzgeç', () => {
    const bytes = buildXlsx([
      { name: 'Alan adları', rows: [['Alan adı', 'Kalan gün'], ['a.example.com', 12], ['b.example.com', -3], ['=1+1 & <tag>', null]],
        freezeHeader: true, autoFilter: true, widths: [30, 10] },
      { name: 'Sertifika', rows: [['Bilgi', 'Değer'], ['Veren', 'Örnek CA — İç Ağ']] },
    ])
    expect(XLSX_MIME).toBe('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
    const files = unzipSync(bytes)
    for (const p of ['[Content_Types].xml', '_rels/.rels', 'xl/workbook.xml', 'xl/_rels/workbook.xml.rels', 'xl/styles.xml',
      'xl/worksheets/sheet1.xml', 'xl/worksheets/sheet2.xml']) {
      expect(files[p], p).toBeDefined()
      expect(wellFormed(strFromU8(files[p])), `${p} iyi biçimli`).toBe(true)
    }
    const s1 = strFromU8(files['xl/worksheets/sheet1.xml'])
    expect(s1).toContain('<c r="A1" s="1" t="inlineStr"><is><t xml:space="preserve">Alan adı</t></is></c>')
    expect(s1).toContain('<c r="B2"><v>12</v></c>')
    expect(s1).toContain('<c r="B3"><v>-3</v></c>')
    expect(s1).toContain('=1+1 &amp; &lt;tag&gt;')   // formül değil, kaçışlı metin
    expect(s1).not.toContain('<f>')
    expect(s1).toContain('<c r="B4"/>')
    expect(s1).toContain('state="frozen"')
    expect(s1).toContain('<autoFilter ref="A1:B4"/>')
    expect(s1).toContain('<col min="1" max="1" width="30" customWidth="1"/>')
    const wb = strFromU8(files['xl/workbook.xml'])
    expect(wb).toContain('<sheet name="Alan adları" sheetId="1" r:id="rId1"/>')
    expect(wb).toContain("'Alan adları'!$A$1:$B$4")
    expect(strFromU8(files['xl/worksheets/sheet2.xml'])).toContain('Örnek CA — İç Ağ')
  })

  it('boş iş kitabı da geçerli (tek boş sayfa)', () => {
    const files = unzipSync(buildXlsx([]))
    expect(wellFormed(strFromU8(files['xl/worksheets/sheet1.xml']))).toBe(true)
  })
})
