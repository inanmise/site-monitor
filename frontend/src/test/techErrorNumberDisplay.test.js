import { describe, it, expect } from 'vitest'
import {
  ABBREV_MAX_DECIMALS, abbreviable, abbreviationExp, canonicalValues, compactQlik, formatAbbreviated, formatValue, issueUnitKey,
  normalizeValues, parseNumber, rateBaseOnly, revertInvalidValues, sameValues, stableJson, summaryAutoText, summaryBlocks, summaryLine,
  summaryTiles, tableUnitKey, UNIT_EXPONENTS, zeroCount, zeroOk,
} from '../components/techerror/techErrorModel.js'
import { javaStringTable, readJavaMain, readJavaTest } from './helpers/javaTable.js'

// Kısaltılmış sayı gösterimi + sıfır işareti (2026-10-18): sunucudaki TechErrorFormat.abbreviated / compactQlik / zeroOk /
// zeroCount, TechErrorSummary.line ve TechErrorTiles.build ile AYNI doğruluk tabloları (TechErrorNumberDisplayTest, Java
// kaynağından okunur) ve istemcinin kanonik belge / geri alma kuralları.
const JAVA = readJavaTest('service/techerror/TechErrorNumberDisplayTest.java')
const table = (name) => javaStringTable(JAVA, name)

/** Java testindeki template() ile elle aynı. */
const TPL = {
  lang: 'tr',
  title: '{kanal}',
  sections: [
    { id: 'exec', kind: 'summary', title: 'Yönetici Özeti', lines: ['{kanal}: {f:total}.'], tiles: ['total', 'count', 'denied', 'rate'] },
    { id: 'm', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'count', label: 'Teknik hata adedi', type: 'number', better: 'lower' },
      { id: 'denied', label: 'Başarılı işlem adedi', type: 'number', better: 'higher' },
      { id: 'rate', label: 'Teknik hata oranı', type: 'percent' },
    ] },
  ],
}
const BY_ID = new Map(TPL.sections[1].fields.map((f) => [f.id, f]))

function cell(spec) {
  if (spec == null) return null
  const raw = spec.slice(2)
  if (spec[0] === 'n') return Number(raw)
  if (spec[0] === 'b') return raw === 'true'
  return raw
}

function values(spec) {
  if (spec === '-') return null
  const out = {}
  if (!spec) return out
  for (const part of spec.split(';')) {
    const eq = part.indexOf('=')
    out[part.slice(0, eq)] = Number(part.slice(eq + 1))
  }
  return out
}

const lit = (x) => x.replace('{kanal}', 'Mobil')

/** Java testindeki rateBaseTemplate() — alan listesi RATE_BASE_FIELDS tablosundan kurulur. */
function rateBaseFields() {
  return table('RATE_BASE_FIELDS').map(([id, type, calc]) => {
    const f = { id, label: id, type }
    if (calc !== '-') {
      const c = calc.split(':')
      f.calc = c[0] === 'ratio'
        ? { kind: 'ratio', num: c[1], ...(c[2] === '-' ? {} : { minus: c[2] }), den: c[3] }
        : { kind: c[0], of: c[1] }
    }
    return f
  })
}

describe('kısaltılmış sayı — Java ile ortak doğruluk tabloları', () => {
  it('ABBREV_TABLE: yazılan rakam aynen geri gelir (gruplamasız, harf; 6 ondalığa sığmayan ve geçersiz üs tam)', () => {
    const rows = table('ABBREV_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(16)
    for (const [v, exp, lang, want] of rows) {
      expect(formatAbbreviated(Number(v), Number(exp), lang), `${v} ${exp} ${lang}`).toBe(want)
    }
    expect(ABBREV_MAX_DECIMALS).toBe(Number(/ABBREV_MAX_DECIMALS = (\d+);/.exec(readJavaMain('service/techerror/TechErrorFormat.java'))[1]))
  })

  it('geri dönüş (inceleme 2026-10-18): her kısaltmalı çıktı İKİ dilde parseNumber ile saklı değere döner — "4.400M" tuzağı yok', () => {
    const outputs = []
    for (const [v, exp, lang, want] of table('ABBREV_TABLE')) outputs.push([Number(v), Number(exp), lang, want])
    // Tablo dışında da: her üs, iki dil, tam sayılar / ondalıklar / büyük değerler
    const values = [0, 1, 999, 1000, 1234, 4.4e9, 4.123456789e9, 1.25e12, 999_999_999_999_999, 1e15, 681_600, 16_980, 7_420_000_000,
      130_100_000, 2500, 1_234_567.891, 2.5, 12_345_678_901_234]
    for (const n of values) for (const exp of UNIT_EXPONENTS) for (const lang of ['tr', 'en']) outputs.push([n, exp, lang, formatAbbreviated(n, exp, lang)])
    let abbreviated = 0
    for (const [n, exp, lang, text] of outputs) {
      if (!/[kMGT]$/.test(text)) {
        // Kısaltılamayan değer tam sayı biçiminde: kendi dilinde geri okunur
        if (Number.isInteger(n) && abbreviable(n, exp) === false && UNIT_EXPONENTS.includes(exp)) expect(parseNumber(text, lang), `${text} ${lang}`).toBe(n)
        continue
      }
      abbreviated++
      expect(abbreviable(n, exp), `${n} ${exp}`).toBe(true)
      expect(text, `${text}: harften önce binlik ayracı yok`).toMatch(/^-?\d+([.,]\d{1,6})?[kMGT]$/)
      for (const parseLang of ['tr', 'en']) {
        expect(parseNumber(text, parseLang), `${text} (${lang} çıktı, ${parseLang} okuma)`).toBe(n)
        expect(abbreviationExp(text), text).toBe(exp)
      }
    }
    expect(abbreviated).toBeGreaterThan(60)
  })

  it('abbreviable: kaydırılan değer en çok 6 ondalıkla tam; geçersiz üs / boş hayır (TechErrorFormat.abbreviable)', () => {
    expect(abbreviable(4.4e9, 9)).toBe(true)
    expect(abbreviable(4_123_456_789, 9)).toBe(false)
    expect(abbreviable(4_123_456_789, 6)).toBe(true)
    expect(abbreviable(0, 12)).toBe(false)               // sıfırın büyüklüğü yok: her yerde "0" (inceleme 2026-10-18)
    expect(abbreviable(-0, 9)).toBe(false)
    expect(abbreviable(0.5, 3)).toBe(true)
    expect(abbreviable(1.25e12, 3)).toBe(true)
    expect(abbreviable(1.5, 6)).toBe(false)
    expect(abbreviable(4.4e9, 7)).toBe(false)
    expect(abbreviable(null, 3)).toBe(false)
    expect(abbreviable(Number.NaN, 3)).toBe(false)
  })

  it('QLIK_TABLE: farkın kısa yazımı (büyüklüğe göre birim, 2 ondalık, 1000 → üst birim)', () => {
    const rows = table('QLIK_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(8)
    for (const [v, lang, want] of rows) expect(compactQlik(Number(v), lang), `${v} ${lang}`).toBe(want)
  })

  it('ZERO_TABLE / COUNT_TABLE: sıfır işareti yalnız sayı alanı, tam 0, yüksek-iyi değil, hesapsız', () => {
    const rows = table('ZERO_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(10)
    for (const [type, better, calc, v, want] of rows) {
      const f = { id: 'x', label: 'X', type, ...(better === '-' ? {} : { better }), ...(calc === '-' ? {} : { calc: { kind: calc, num: 'a', den: 'b' } }) }
      expect(zeroOk(f, cell(v)), `${type} ${better} ${calc} ${v}`).toBe(want === 'true')
    }
    for (const [v, want] of table('COUNT_TABLE')) expect(zeroCount(cell(v)), String(v)).toBe(want === 'true')
    expect(zeroOk(null, 0)).toBe(false)
  })

  it('RATE_BASE_TABLE: yalnız oran tabanı olan sayı alanı (payda, hiçbir oranın payı / çıkarılanı değil) işaretsiz', () => {
    const fields = rateBaseFields()
    const byId = new Map(fields.map((f) => [f.id, f]))
    expect(byId.get('rate').calc).toEqual({ kind: 'ratio', num: 'count', den: 'total' })
    const rows = table('RATE_BASE_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(6)
    for (const [id, want] of rows) {
      expect(zeroOk(byId.get(id), 0, fields), id).toBe(want === 'true')
      expect(zeroOk(byId.get(id), 5, fields), `${id} = 5`).toBe(false)
      expect(rateBaseOnly(fields, id), id).toBe(want === 'false')
    }
    // Alan listesi yoksa alan kuralı (sunucuda şablonsuz çağrı)
    expect(zeroOk(byId.get('total'), 0)).toBe(true)
    expect(rateBaseOnly(null, 'total')).toBe(false)
  })

  it('UNIT_SUMMARY_TABLE: {f:} kısaltmasıyla, {d:} kısa fark; üssüz değer eskisi gibi', () => {
    const rows = table('UNIT_SUMMARY_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(6)
    for (const [name, line, fields, prev, units, want] of rows) {
      const u = values(units)
      expect(summaryLine(line, BY_ID, values(fields), 'tr', lit, values(prev), null, u), name).toBe(want)
    }
  })

  it('UNIT_TILES_TABLE: kutucuk değeri / farkı kısaltmalı, sıfır işareti', () => {
    const rows = table('UNIT_TILES_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(2)
    for (const [name, fields, prev, units, ...expected] of rows) {
      const tiles = summaryTiles(TPL, TPL.sections[0], values(fields), {}, values(prev), 'Mobil', 2026, 9, values(units))
      expect(tiles.map((x) => [x.id, x.value, x.delta ?? '-', String(x.zero)].join('|')), name).toEqual(expected)
    }
  })
})

describe('kısaltma girişi ve gösterimi', () => {
  it('abbreviationExp: Qlik / Türkçe / İngilizce sonekler üsse; soneksiz, belirsiz "B" ve yarım yazım null', () => {
    expect(abbreviationExp('4.4G')).toBe(9)
    expect(abbreviationExp('4,4 milyar')).toBe(9)
    expect(abbreviationExp('681,6k')).toBe(3)
    expect(abbreviationExp('130,1M')).toBe(6)
    expect(abbreviationExp('2 Tn')).toBe(12)
    expect(abbreviationExp('4400000000')).toBeNull()
    expect(abbreviationExp('7,42B')).toBeNull()
    expect(abbreviationExp('7,42mi')).toBeNull()
    expect(abbreviationExp('')).toBeNull()
    expect(UNIT_EXPONENTS).toEqual([3, 6, 9, 12])
  })

  it('kısaltmalı metin her yerde gruplamasız, geri okununca aynı sayı + aynı üs — "4.400M" tuzağı yok', () => {
    for (const [n, exp] of [[4.4e9, 9], [4.4e9, 6], [1.25e12, 9], [681600, 3], [1234.5, 3], [500, 3], [7.42e9, 9], [130.1e6, 6]]) {
      for (const lang of ['tr', 'en']) {
        const text = formatAbbreviated(n, exp, lang)
        expect(abbreviationExp(text), text).toBe(exp)
        expect(parseNumber(text, lang), text).toBe(n)
      }
    }
    expect(formatAbbreviated(4.4e9, 6, 'tr')).toBe('4400M')
    expect(formatAbbreviated(1.25e12, 9, 'en')).toBe('1250G')
    expect(parseNumber('4400M', 'tr')).toBe(4.4e9)
    expect(formatAbbreviated(4.4e9, 9, 'tr')).toBe('4,4G')
    expect(formatAbbreviated(null, 9, 'tr')).toBe('—')
    // Sıfır hiçbir üsle harf almaz ("0G" tikin yanında basılıyordu — inceleme 2026-10-18)
    for (const exp of UNIT_EXPONENTS) for (const lang of ['tr', 'en']) {
      expect(formatAbbreviated(0, exp, lang)).toBe('0')
      expect(formatAbbreviated(-0, exp, lang)).toBe('0')
      expect(formatValue({ type: 'number' }, 0, lang, (b) => String(b), exp)).toBe('0')
    }
    // Geçen ayın ipucu / salt okunur hücre de aynı biçimden (formatValue)
    expect(formatValue({ type: 'number' }, 4.4e9, 'tr', (b) => String(b), 6)).toBe('4400M')
    expect(formatValue({ type: 'number' }, 4_123_456_789, 'tr', (b) => String(b), 9)).toBe('4.123.456.789')
  })

  it('formatValue: sayı alanı üssü kullanır, yüzde kullanmaz; üssüz eski çıktı', () => {
    const yn = (b) => (b ? 'Evet' : 'Hayır')
    expect(formatValue({ type: 'number' }, 4.4e9, 'tr', yn, 9)).toBe('4,4G')
    expect(formatValue({ type: 'number' }, 4.4e9, 'tr', yn)).toBe('4.400.000.000')
    expect(formatValue({ type: 'percent' }, 0.42, 'tr', yn, 9)).toBe('%0,42')
  })

  it('özet önizlemesi ve otomatik metin üsleri taşır (PDF ile aynı cümle)', () => {
    const sec = { ...TPL.sections[0], lines: ['{kanal} işlem {f:total}.'] }
    const tpl = { ...TPL, sections: [sec, TPL.sections[1]] }
    expect(summaryAutoText(sec, tpl, { total: 4.4e9 }, 'Mobil', 2026, 9, null, null, { total: 9 })).toBe('Mobil işlem **4,4G**.')
    expect(summaryAutoText(sec, tpl, { total: 4.4e9 }, 'Mobil', 2026, 9)).toBe('Mobil işlem **4.400.000.000**.')
    expect(summaryBlocks(tpl, sec, { total: 4.4e9 }, {}, 'Mobil', 2026, 9, null, null, { total: 9 })[0].text).toBe('Mobil işlem **4,4G**.')
  })
})

describe('kanonik belge: units', () => {
  const T = {
    lang: 'tr', title: 'x', sections: [
      { id: 'm', kind: 'metrics', title: 'M', fields: [
        { id: 'total', label: 'T', type: 'number' },
        { id: 'rate', label: 'R', type: 'percent' },
        { id: 'q', label: 'Q?', type: 'yesno' },
        { id: 'hidden', label: 'H', type: 'number', skip_if_no: 'q' },
      ] },
      { id: 'iss', kind: 'issues', title: 'I', item_title: '{konu}', description_label: 'D', customers_label: 'C', errors_label: 'E' },
      { id: 'tb', kind: 'table', title: 'TB', columns: [{ id: 'n', label: 'N', type: 'number' }, { id: 'p', label: 'P', type: 'percent' }],
        rows: [{ id: 'r1', label: 'R1' }] },
    ],
  }

  it('yalnız değeri olan sayı yerleri kalır; boşsa anahtar hiç yok (kısaltmasız raporun belgesi aynı)', () => {
    const draft = normalizeValues({
      fields: { total: 4.4e9, rate: 0.42, q: false, hidden: 5 },
      issues: { iss: [{ id: 'i-a', topic: 'A', customers: 1250000, errors: null }] },
      tables: { tb: { r1: { n: 2000, p: 1.5 } } },
      units: {
        total: 9, rate: 9, hidden: 3, ghost: 6, [issueUnitKey('iss', 'i-a', 'customers')]: 6, [issueUnitKey('iss', 'i-a', 'errors')]: 3,
        [issueUnitKey('iss', 'gone', 'customers')]: 6, [tableUnitKey('tb', 'r1', 'n')]: 3, [tableUnitKey('tb', 'r1', 'p')]: 3, bad: 7,
      },
    })
    const c = canonicalValues(draft, T)
    expect(c.units).toEqual({ total: 9, 'issues.iss.i-a.customers': 6, 'tables.tb.r1.n': 3 })
    // 6 ondalığa sığmayan değerin üssü sunucudaki gibi düşer (TechErrorValues.units — inceleme 2026-10-18)
    expect(canonicalValues(normalizeValues({ fields: { total: 4_123_456_789 }, units: { total: 9 } }), T).units).toBeUndefined()
    expect(canonicalValues(normalizeValues({ fields: { total: 4_123_456_789 }, units: { total: 6 } }), T).units).toEqual({ total: 6 })
    // Kısaltmayla yazılan sıfırın ("0G") üssü sunucudaki gibi düşer — rapor tikin yanında "0" basar, "0G" değil
    expect(canonicalValues(normalizeValues({ fields: { total: 0 }, units: { total: 9 } }), T).units).toBeUndefined()
    expect(canonicalValues(normalizeValues({ issues: { iss: [{ id: 'i-a', topic: 'A', customers: 0 }] },
      units: { [issueUnitKey('iss', 'i-a', 'customers')]: 3 } }), T).units).toBeUndefined()
    expect(canonicalValues(normalizeValues({ tables: { tb: { r1: { n: 0 } } }, units: { [tableUnitKey('tb', 'r1', 'n')]: 6 } }), T).units)
      .toBeUndefined()
    expect('units' in canonicalValues(normalizeValues({ fields: { total: 5 } }), T)).toBe(false)
    expect(stableJson(canonicalValues(normalizeValues({ fields: { total: 5 } }), T)))
      .toBe(stableJson({ fields: { total: 5 }, issues: {}, texts: {} }))
  })

  it('kısaltma değişikliği "kaydedilmemiş değişiklik"tir; aynı üs değildir', () => {
    const base = canonicalValues(normalizeValues({ fields: { total: 4.4e9 }, units: { total: 9 } }), T)
    const same = canonicalValues(normalizeValues({ fields: { total: 4.4e9 }, units: { total: 9 } }), T)
    const full = canonicalValues(normalizeValues({ fields: { total: 4.4e9 } }), T)
    expect(sameValues(base, same)).toBe(true)
    expect(sameValues(base, full)).toBe(false)
  })

  it('geri alma (otomatik kayıt): geçersiz alan değeri VE üssüyle kayıtlı hâline döner', () => {
    const base = { fields: { total: 4.4e9 }, issues: { iss: [{ id: 'i-a', customers: 1e6 }] }, tables: { tb: { r1: { n: 2000 } } },
      units: { total: 9, 'issues.iss.i-a.customers': 6, 'tables.tb.r1.n': 3 } }
    const draft = { fields: { total: 4.4e9 }, issues: { iss: [{ id: 'i-a', customers: 1e6 }] }, tables: { tb: { r1: { n: 2000 } } },
      units: {} }
    const out = revertInvalidValues(draft, base, ['values.total', 'issue:iss:i-a:customers', 'tables.tb.r1.n'])
    expect(out.units).toEqual(base.units)
    expect(revertInvalidValues(base, base, ['values.total'])).toBe(base)
  })
})
