import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  applyCalc, calcFieldsUsing, calcRefIssues, calcResult, calcResults, calcShapeError, canonCalc, canonicalTemplate,
  derivedRates, formatPercent, roundCalc, sanitizeSkipRefs, templateDiff, templateErrors,
} from '../components/techerror/techErrorModel.js'

// Otomatik oranlar (2026-10-16, kullanıcı isteği: "kullanıcı adetleri girer, yüzdeleri sistem hesaplar"). İstemci
// yazarken gösterir; kaydedilen değeri sunucu hesaplar — iki taraf AYNI doğruluk tablosunu okur (TechErrorCalcTest.TABLE).
const ROOT = path.resolve(__dirname, '../../..')

const calcOf = (spec) => {
  const [kind, rest] = spec.split(':')
  if (kind !== 'ratio') return { kind, of: rest }
  const [numPart, den] = rest.split('/')
  const [num, minus] = numPart.split('-')
  return { kind, num, ...(minus ? { minus } : {}), den }
}
const numbers = (spec) => {
  if (spec === '-') return null
  const out = {}
  if (!spec) return out
  for (const kv of spec.split(',')) {
    const [k, v] = kv.split('=')
    out[k] = Number(v)
  }
  return out
}
const RATES = {
  lang: 'tr',
  title: 'x',
  sections: [
    { id: 'in', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
      { id: 'error-count', label: 'Teknik hata adedi', type: 'number' },
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'had-outage', label: 'Kesinti oldu mu?', type: 'yesno' },
      { id: 'outage-errors', label: 'Kesinti kaynaklı hata', type: 'number', skip_if_no: 'had-outage' },
    ] },
    { id: 'rates', kind: 'metrics', title: 'Oranlar', fields: [
      { id: 'error-rate', label: 'Teknik hata oranı', type: 'percent', calc: { kind: 'ratio', num: 'error-count', den: 'total-tx' } },
      { id: 'error-rate-excl', label: 'Kesinti hariç', type: 'percent', skip_if_no: 'had-outage',
        calc: { kind: 'ratio', num: 'error-count', minus: 'outage-errors', den: 'total-tx' } },
      { id: 'unmapped-rate', label: 'Map oranı', type: 'percent', calc: { kind: 'ratio', num: 'unmapped-count', den: 'error-count' } },
      { id: 'tx-increase', label: 'Artış var mı?', type: 'yesno', calc: { kind: 'increase', of: 'total-tx' } },
      { id: 'tx-increase-pct', label: 'Değişim', type: 'percent', calc: { kind: 'change', of: 'total-tx' } },
    ] },
  ],
}

describe('otomatik hesap (TechErrorCalc aynası)', () => {
  it('doğruluk tablosu Java testindekinin AYNISI (TechErrorCalcTest.TABLE kaynaktan okunur — iki taraf ayrışamaz)', () => {
    const java = fs.readFileSync(path.join(ROOT, 'backend/src/test/java/com/sitemonitor/service/techerror/TechErrorCalcTest.java'), 'utf8')
    const block = /TABLE = \{([\s\S]*?)\n\s*\};/.exec(java)[1]
    const rows = [...block.matchAll(/\{\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)"\s*\}/g)]
      .map((m) => m.slice(1))
    expect(rows.length).toBeGreaterThanOrEqual(20)
    for (const [spec, types, fields, prev, status, value] of rows) {
      const calc = calcOf(spec)
      // Alan "kimlik=tür[@hedef[@daha-iyi]]" (4b: hedef karşılaştırması)
      const byId = new Map(types.split(',').map((kv) => {
        const [id, spec] = kv.split('=')
        const [type, target, better] = spec.split('@')
        const f = { id, type }
        if (target != null) f.target = Number(target)
        if (better) f.better = better
        return [id, f]
      }))
      const field = { id: 'x', type: calc.kind === 'increase' || calc.kind === 'compare' ? 'yesno' : 'percent', calc }
      byId.set('x', field)
      const r = calcResult(field, byId, numbers(fields), numbers(prev))
      const label = [spec, types, fields, prev].join(' | ')
      expect(r.status, label).toBe(status)
      if (!value) expect(r.value, label).toBeNull()
      else if (value === 'true' || value === 'false') expect(r.value, label).toBe(value === 'true')
      else expect(r.value, label).toBe(Number(value))
    }
  })

  it('calcResults / applyCalc: oranlar adetlerden; geçen ay yoksa değişim ve artış elle (girilen değer kalır); "Hayır" cevabında muaf', () => {
    const fields = { 'total-tx': 1250000, 'error-count': 5250, 'unmapped-count': 120, 'had-outage': true, 'outage-errors': 250,
      'error-rate': 9.9, 'tx-increase': false, 'tx-increase-pct': 3.5 }
    const r = calcResults(RATES, fields, { 'total-tx': 1000000 })
    expect(r['error-rate'].value).toBe(0.42)
    expect(r['error-rate-excl'].value).toBe(0.4)
    expect(r['unmapped-rate'].value).toBe(2.29)
    expect(r['tx-increase']).toEqual({ status: 'OK', value: true, missing: [] })
    expect(r['tx-increase-pct'].value).toBe(25)
    const shown = applyCalc(RATES, fields, { 'total-tx': 1000000 })
    expect(shown['error-rate']).toBe(0.42)
    // Geçen ayın raporu yok → değişim ve artış ELLE: girilen değer kalır
    const manual = applyCalc(RATES, fields, null)
    expect(manual['tx-increase-pct']).toBe(3.5)
    expect(manual['tx-increase']).toBe(false)
    // Teknik hata adedi silinince ona bağlı oranlar BOŞ (eski / elle değer kalmaz)
    const missing = applyCalc(RATES, { ...fields, 'error-count': null }, null)
    expect(missing).not.toHaveProperty('error-rate')
    expect(missing).not.toHaveProperty('unmapped-rate')
    expect(calcResults(RATES, { ...fields, 'error-count': null }, null)['error-rate'].missing).toEqual(['error-count'])
    // "Kesinti oldu mu?" Hayır → hariç oran muaf (sonuç yok), kesinti hatası girdi sayılmaz
    const noOutage = calcResults(RATES, { ...fields, 'had-outage': false }, null)
    expect(noOutage).not.toHaveProperty('error-rate-excl')
    expect(noOutage['error-rate'].status).toBe('OK')
    // Hesabı olmayan şablon: AYNI nesne
    const plain = { 'total-tx': 5 }
    expect(applyCalc({ sections: [] }, plain, null)).toBe(plain)
  })

  it('adetten türeyen oranlar (kota / map yapılmamış hata girişinin altında): adeti PAY olarak kullanan oranlar', () => {
    const all = RATES.sections.flatMap((s) => s.fields)
    expect(derivedRates(all, 'unmapped-count').map((f) => f.id)).toEqual(['unmapped-rate'])
    expect(derivedRates(all, 'error-count').map((f) => f.id)).toEqual(['error-rate', 'error-rate-excl'])
    expect(derivedRates(all, 'total-tx')).toEqual([])                              // payda — türeyen oran değil
  })

  it('şablon: biçim hatası (tür, alan türü, zorunlu alan, kendine başvuru); başvuru uyarıları; formülde kullanılan alan', () => {
    const f = (calc, type = 'percent') => ({ id: 'r', label: 'R', type, calc })
    expect(calcShapeError(f({ kind: 'ratio', num: 'a', den: 'b' }))).toBeNull()
    expect(calcShapeError(f(undefined))).toBeNull()
    expect(calcShapeError(f({ kind: 'sum', num: 'a', den: 'b' }))).toBe('te.err.calcKind')
    expect(calcShapeError(f({ kind: 'ratio', num: 'a', den: 'b' }, 'number'))).toBe('te.err.calcType')
    expect(calcShapeError(f({ kind: 'increase', of: 'a' }))).toBe('te.err.calcType')
    expect(calcShapeError(f({ kind: 'ratio', num: 'a' }))).toBe('te.err.calcRequired')
    expect(calcShapeError(f({ kind: 'change' }))).toBe('te.err.calcRequired')
    expect(calcShapeError(f({ kind: 'ratio', num: 'r', den: 'b' }))).toBe('te.err.calcSelf')
    const tpl = { sections: [{ kind: 'metrics', id: 'm', title: 'M', fields: [
      { id: 'a', label: 'A', type: 'number' }, { id: 'p', label: 'P', type: 'percent' },
      { id: 'r', label: 'R', type: 'percent', calc: { kind: 'ratio', num: 'a', minus: 'p', den: 'gone' } }] }] }
    expect(templateErrors(tpl)['calc:r']).toBeUndefined()                         // başvuru sorunu kaydı engellemez
    expect(calcRefIssues(tpl, tpl.sections[0].fields[2]).map((x) => [x.id, x.status])).toEqual([['p', 'notNumber'], ['gone', 'missing']])
    expect(calcFieldsUsing(tpl, 'a').map((x) => x.id)).toEqual(['r'])
    const bad = { sections: [{ kind: 'metrics', id: 'm', title: 'M', fields: [{ id: 'r', label: 'R', type: 'percent', calc: { kind: 'ratio' } }] }] }
    expect(templateErrors(bad)['calc:r']).toBe('te.err.calcRequired')
  })

  it('2026-10-16 (kullanıcı kararı): 2 hane yarım yukarı, 0,01 altı 2 anlamlı hane — sunucuyla aynı basamaklardan; ekran ve PDF aynı', () => {
    expect(roundCalc(8529 / 1234567 * 100)).toBe(0.69)
    expect(formatPercent(roundCalc(8529 / 1234567 * 100), 'tr')).toBe('%0,69')
    expect(roundCalc(2.675)).toBe(2.68)                 // ondalık gösterimden (ikili 2,67499… değil)
    // inceleme 2026-10-16: 59 / 4000 × 100 = 1,4749999999999999 → 1,47 (×100 + Math.round 1,48 veriyordu, PDF 1,47)
    expect(59 / 4000 * 100).toBe(1.4749999999999999)
    expect(roundCalc(59 / 4000 * 100)).toBe(1.47)
    // 0,01'in altı: 2 anlamlı hane, en çok 6 ondalık — hata varken sahte %0 yok
    expect(roundCalc(0.005)).toBe(0.005)
    expect(roundCalc(0.0049)).toBe(0.0049)
    expect(roundCalc(28 / 1000000 * 100)).toBe(0.0028)
    expect(roundCalc(0.00405)).toBe(0.0041)
    expect(roundCalc(0.000042)).toBe(0.000042)
    expect(roundCalc(1e-7)).toBe(0)                     // 6 ondalık (saklama hassasiyeti) altı
    expect(roundCalc(33.333333)).toBe(33.33)
    expect(roundCalc(25)).toBe(25)
    expect(roundCalc(0)).toBe(0)
    // Gösterim: küçük yüzde 2 anlamlı haneyle (en çok 6 ondalık) — eskiden 4 ondalık sınırı %0 gösteriyordu
    expect(formatPercent(0.0028, 'tr')).toBe('%0,0028')
    expect(formatPercent(0.00004, 'en')).toBe('0.00004%')
    expect(formatPercent(0.0000025, 'tr')).toBe('%0,000003')
    expect(formatPercent(0.42, 'tr')).toBe('%0,42')
    expect(formatPercent(0, 'tr')).toBe('%0')
    const byId = new Map([['a', { id: 'a', type: 'number' }], ['b', { id: 'b', type: 'number' }]])
    const r = calcResult({ id: 'x', type: 'percent', calc: { kind: 'ratio', num: 'a', den: 'b' } }, byId, { a: 8529, b: 1234567 }, null)
    expect(r.value).toBe(0.69)
  })

  it('inceleme 2026-10-16: otomatik hesaplanan evet/hayır sorusu "Hayır ise sorma" hedefi olamaz (bağ temizlenir)', () => {
    const tpl = { sections: [{ kind: 'metrics', id: 'm', title: 'M', fields: [
      { id: 'a', label: 'A', type: 'number' },
      { id: 'q', label: 'Q', type: 'yesno', calc: { kind: 'increase', of: 'a' } },
      { id: 'h', label: 'H', type: 'yesno' },
      { id: 'd', label: 'D', type: 'number', skip_if_no: 'q' },
      { id: 'e', label: 'E', type: 'number', skip_if_no: 'h' }] }] }
    const clean = sanitizeSkipRefs(tpl)
    expect(clean.sections[0].fields.find((f) => f.id === 'd').skip_if_no).toBeUndefined()
    expect(clean.sections[0].fields.find((f) => f.id === 'e').skip_if_no).toBe('h')
  })

  it('kanonik hesap sunucunun yazdığı gibi (boş anahtarlar düşer); şablon farkında "hesap değişti" satırı', () => {
    expect(canonCalc({ kind: 'ratio', num: 'a', minus: '', den: 'b', of: 'x' })).toEqual({ kind: 'ratio', num: 'a', den: 'b' })
    expect(canonCalc({ kind: '' })).toBeUndefined()
    const base = { lang: 'tr', title: 'x', sections: [{ id: 'm', kind: 'metrics', title: 'M', fields: [
      { id: 'a', label: 'A', type: 'number' }, { id: 'r', label: 'R', type: 'percent', calc: { kind: 'ratio', num: 'a', den: 'a' } }] }] }
    const sameButEmpty = JSON.parse(JSON.stringify(base))
    sameButEmpty.sections[0].fields[1].calc.minus = ''
    expect(JSON.stringify(canonicalTemplate(sameButEmpty))).toBe(JSON.stringify(canonicalTemplate(base)))
    const changed = JSON.parse(JSON.stringify(base))
    delete changed.sections[0].fields[1].calc
    expect(templateDiff(base, changed).filter((d) => d.op === 'calc')).toHaveLength(1)
    expect(templateDiff(base, base).filter((d) => d.op === 'calc')).toHaveLength(0)
  })
})
