import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  MAX_RULES, changeKind, changeOperator, formatInputNumber, formatNumber, moveRule, newRule, newRuleId, parseNumber,
  ruleSummary, setTextLevel, strictnessHint, textLevel, trAblative, validateRule, validateRules, valueText,
} from '../components/dbquery/ruleModel.js'

/** Gerçek sözlükle t (yer tutucular {0}…). */
const mkT = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const tTR = mkT(TR)
const tEN = mkT(EN)

describe('sayı biçimi — TR "1.250,5" / EN "1,250.5"', () => {
  it.each([
    ['1.250,5', 'tr', 1250.5], ['1250,5', 'tr', 1250.5], ['1.250', 'tr', 1250], ['1.250.000', 'tr', 1250000], ['1.5', 'tr', 1.5],
    ['-3,25', 'tr', -3.25], ['  42 ', 'tr', 42], ['1,250.5', 'en', 1250.5], ['1,250', 'en', 1250], ['1.5', 'en', 1.5], ['1,5', 'en', 1.5],
    ['0', 'tr', 0],
  ])('%s (%s) → %s', (text, lang, n) => expect(parseNumber(text, lang)).toBe(n))

  it('boş → null; çözülemeyen → NaN', () => {
    expect(parseNumber('', 'tr')).toBeNull()
    expect(parseNumber(null, 'tr')).toBeNull()
    for (const bad of ['abc', '1,2,3', '1.25,5.0', '1,', '12e3', '--1', 'x'.repeat(50)]) expect(Number.isNaN(parseNumber(bad, 'tr'))).toBe(true)
  })

  it('biçimleme dilin ayraçlarıyla; düzenleme kutusu gruplamasız', () => {
    expect(formatNumber(1250.5, 'tr')).toBe('1.250,5')
    expect(formatNumber(1250.5, 'en')).toBe('1,250.5')
    expect(formatInputNumber(1250.5, 'tr')).toBe('1250,5')
    expect(formatInputNumber(1250.5, 'en')).toBe('1250.5')
    expect(formatInputNumber(null, 'tr')).toBe('')
  })
})

describe('Türkçe çıkma eki — "10\'dan", "100\'den", "3\'ten"', () => {
  it.each([
    [0, 'dan'], [1, 'den'], [2, 'den'], [3, 'ten'], [4, 'ten'], [5, 'ten'], [6, 'dan'], [7, 'den'], [8, 'den'], [9, 'dan'],
    [10, 'dan'], [20, 'den'], [30, 'dan'], [40, 'tan'], [50, 'den'], [60, 'tan'], [70, 'ten'], [80, 'den'], [90, 'dan'],
    [100, 'den'], [1000, 'den'], [2500, 'den'], [1000000, 'dan'], [2500000, 'den'], [1.5, 'ten'], [10.25, 'ten'], [0.06, 'dan'],
  ])('%s → %s', (n, s) => expect(trAblative(n)).toBe(s))

  it('değer metni: TR ekli, EN yalın; yüzde biçimi dile göre', () => {
    expect(valueText(10, 'tr', { ablative: true })).toBe("10'dan")
    expect(valueText(10, 'en', { ablative: true })).toBe('10')
    expect(valueText(20, 'tr', { percent: true, ablative: true })).toBe("%20'den")
    expect(valueText(20, 'en', { percent: true })).toBe('20%')
  })
})

describe('insan dilinde özet', () => {
  const r = (patch) => ({ ...newRule('ROW_COUNT', 'r-test'), ...patch })

  it('kullanıcının örneği birebir: satır sayısı > 10 UYARI, > 100 KRİTİK', () => {
    expect(ruleSummary(r({ warning: 10, critical: 100 }), tTR, 'tr')).toBe("Satır sayısı 10'dan büyükse UYARI, 100'den büyükse KRİTİK.")
    expect(ruleSummary(r({ warning: 10, critical: 100 }), tEN, 'en')).toBe('WARNING if the row count is greater than 10; CRITICAL if greater than 100.')
  })

  it('tek seviye, aralık, değişim ve metin özetleri', () => {
    expect(ruleSummary(r({ critical: 0, operator: 'EQ' }), tTR, 'tr')).toBe('Satır sayısı tam 0 ise KRİTİK.')
    const range = { ...newRule('COLUMN_VALUE', 'r1'), column: 'TUTAR', aggregate: 'SUM', operator: 'OUTSIDE', warning: [1000, 5000] }
    expect(ruleSummary(range, tTR, 'tr')).toBe('TUTAR kolonunun toplamı 1.000–5.000 aralığının dışındaysa UYARI.')
    const ch = { ...newRule('CHANGE', 'r2'), change: { of: 'ROW_COUNT', mode: 'PERCENT', direction: 'INCREASE' }, warning: 20, critical: 50 }
    expect(ruleSummary(ch, tTR, 'tr')).toBe("Satır sayısındaki artış %20'den büyükse UYARI, %50'den büyükse KRİTİK. (önceki başarılı çalışmaya göre)")
    const txt = { ...newRule('TEXT_MATCH', 'r3'), column: 'DURUM', critical: 'HATA' }
    expect(ruleSummary(txt, tTR, 'tr')).toBe('Herhangi bir satırda DURUM değeri “HATA” içeriyorsa KRİTİK.')
    expect(ruleSummary(txt, tEN, 'en')).toBe('CRITICAL if any row’s DURUM contains “HATA”.')
  })

  it('eşik yoksa "eşik girilmedi"; kolon yoksa yer tutucu', () => {
    expect(ruleSummary(r({}), tTR, 'tr')).toContain('eşik girilmedi')
    expect(ruleSummary({ ...newRule('COLUMN_VALUE', 'x'), warning: 1 }, tTR, 'tr')).toContain('(kolon seçilmedi)')
  })
})

describe('kural düzenleme yardımcıları', () => {
  it('kimlik r-<zaman><rastgele>; iki üretim farklı', () => {
    expect(newRuleId(1730000000000, () => 0.5)).toMatch(/^r-[0-9a-z]+$/)
    expect(newRuleId()).not.toBe(newRuleId())
  })

  it('tür değişimi: kimlik + etiket + sayısal eşik korunur; metne geçişte düşer', () => {
    const a = { ...newRule('ROW_COUNT', 'r-1'), label: 'Bekleyen', warning: 5, critical: 9, operator: 'GTE' }
    const b = changeKind(a, 'COLUMN_VALUE')
    expect(b).toMatchObject({ id: 'r-1', label: 'Bekleyen', kind: 'COLUMN_VALUE', warning: 5, critical: 9, operator: 'GTE', aggregate: 'FIRST' })
    const c = changeKind(b, 'TEXT_MATCH')
    expect(c).toMatchObject({ id: 'r-1', kind: 'TEXT_MATCH', operator: 'CONTAINS', warning: null, critical: null, scope: 'ANY_ROW' })
  })

  it('işleç değişimi tek değer ↔ aralık taşır; değersiz işleç seçili seviyede boş metin', () => {
    const a = { ...newRule('ROW_COUNT', 'r'), warning: 5 }
    expect(changeOperator(a, 'BETWEEN').warning).toEqual([5, null])
    expect(changeOperator(changeOperator(a, 'BETWEEN'), 'GT').warning).toBe(5)
    const t = { ...newRule('TEXT_MATCH', 't'), warning: 'X' }
    const e = changeOperator(t, 'EMPTY')
    expect(e).toMatchObject({ warning: '', critical: null })
    expect(textLevel(e)).toBe('warning')
    expect(setTextLevel(e, 'critical')).toMatchObject({ warning: null, critical: '' })
  })

  it('taşıma sınırda aynı diziyi döndürür', () => {
    const list = [newRule('ROW_COUNT', 'a'), newRule('ROW_COUNT', 'b')]
    expect(moveRule(list, 0, -1)).toBe(list)
    expect(moveRule(list, 0, 1).map((x) => x.id)).toEqual(['b', 'a'])
  })

  it('sıkılık ipucu: GT\'de kritik uyarıdan küçükse uyarı hiç tetiklenmez', () => {
    expect(strictnessHint({ ...newRule('ROW_COUNT', 'x'), warning: 100, critical: 10 })).toBe('critical_looser')
    expect(strictnessHint({ ...newRule('ROW_COUNT', 'x'), operator: 'LT', warning: 10, critical: 100 })).toBe('critical_looser')
    expect(strictnessHint({ ...newRule('ROW_COUNT', 'x'), warning: 10, critical: 100 })).toBeNull()
  })
})

describe('doğrulama — sunucu alan adlarıyla', () => {
  it('eksik eşik, geçersiz sayı, aralık, kolon', () => {
    expect(validateRule(newRule('ROW_COUNT', 'x'))).toEqual({ threshold: 'missing_threshold' })
    expect(validateRule({ ...newRule('ROW_COUNT', 'x'), warning: 'abc' })).toMatchObject({ warning: 'number' })
    expect(validateRule({ ...newRule('ROW_COUNT', 'x'), operator: 'BETWEEN', warning: [5, null] })).toMatchObject({ warning: 'range_both' })
    expect(validateRule({ ...newRule('ROW_COUNT', 'x'), operator: 'BETWEEN', warning: [9, 5] })).toMatchObject({ warning: 'range_order' })
    expect(validateRule({ ...newRule('COLUMN_VALUE', 'x'), warning: 1 })).toMatchObject({ column: 'column' })
    expect(validateRule({ ...newRule('CHANGE', 'x'), warning: -5 })).toMatchObject({ warning: 'negative' })
    expect(validateRule({ ...newRule('TEXT_MATCH', 'x'), column: 'A', warning: '' })).toEqual({ threshold: 'missing_text' })
    expect(validateRule({ ...changeOperator({ ...newRule('TEXT_MATCH', 'x'), column: 'A' }, 'EMPTY') })).toEqual({})
  })

  it('liste: rules[N].<alt> anahtarları; > 10 kural "rules"', () => {
    const list = [{ ...newRule('ROW_COUNT', 'a'), warning: 1 }, newRule('COLUMN_VALUE', 'b')]
    expect(validateRules(list)).toEqual({ 'rules[1].column': 'column', 'rules[1].warning': 'missing_threshold' })
    const many = Array.from({ length: MAX_RULES + 1 }, (_, i) => ({ ...newRule('ROW_COUNT', `r${i}`), warning: 1 }))
    expect(validateRules(many)).toEqual({ rules: 'too_many' })
    expect(validateRules([])).toEqual({})
  })
})
