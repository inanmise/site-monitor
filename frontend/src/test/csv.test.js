import { describe, it, expect } from 'vitest'
import { csvCell, csvRows } from '../utils/csv.js'

/**
 * CSV hücresi kaçışı — arayüz dışa aktarımlarının ortak kuralı.
 *
 * <p>Backend'deki {@code com.sitemonitor.util.Csv} ile AYNI davranışı bekler; iki taraf ayrışırsa
 * aynı veri iki dosyada farklı kaçışlanır. 2026-08-23 denetiminde arayüzdeki dört dışa aktarımın
 * hiçbirinde formül nötrlemesi olmadığı, üçünde de CR'nin kaçırıldığı bulundu.
 */
describe('csvCell', () => {
  it('formül karakteriyle BAŞLAYAN hücreyi metne sabitler', () => {
    expect(csvCell("=cmd|'/c calc'!A1")).toBe("'=cmd|'/c calc'!A1")
    expect(csvCell('+1+1')).toBe("'+1+1")
    expect(csvCell('-2+3')).toBe("'-2+3")
    expect(csvCell('@SUM(A1)')).toBe("'@SUM(A1)")
    expect(csvCell('\tsekme')).toBe("'\tsekme")
  })

  it('formül karakteri ORTADA ise dokunmaz', () => {
    expect(csvCell('a=b')).toBe('a=b')
    expect(csvCell('mail@akbank.com')).toBe('mail@akbank.com')
  })

  it('ayraç/tırnak/satır sonu içeren değeri tırnaklar, tırnakları ikiler', () => {
    expect(csvCell('a,b')).toBe('"a,b"')
    expect(csvCell('a;b')).toBe('"a;b"')
    expect(csvCell('de"mek')).toBe('"de""mek"')
    expect(csvCell('iki\nsatır')).toBe('"iki\nsatır"')
  })

  it('tek başına CR de tırnaklanır — satırlar CRLF ile birleşiyor', () => {
    expect(csvCell('a\rb')).toBe('"a\rb"')
  })

  it('nötrleme ve tırnaklama birlikte uygulanır', () => {
    expect(csvCell('=1,2')).toBe('"\'=1,2"')
  })

  it('null/undefined/boş → boş hücre', () => {
    expect(csvCell(null)).toBe('')
    expect(csvCell(undefined)).toBe('')
    expect(csvCell('')).toBe('')
  })

  it('sayı ve boolean da kabul edilir; negatif sayı formül gibi başladığı için nötrlenir', () => {
    expect(csvCell(443)).toBe('443')
    expect(csvCell(true)).toBe('true')
    expect(csvCell(-5)).toBe("'-5")
  })
})

describe('csvRows', () => {
  it('satırları CRLF ile birleştirir ve her hücreyi kaçışlar', () => {
    expect(csvRows([['a', 'b,c'], ['=x', 2]])).toBe('a,"b,c"\r\n\'=x,2')
  })

  it('boş liste boş gövde döner', () => {
    expect(csvRows([])).toBe('')
  })
})
