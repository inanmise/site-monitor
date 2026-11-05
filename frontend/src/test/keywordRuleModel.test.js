import { describe, it, expect } from 'vitest'
import {
  RULE_LIMITS, alarmOf, applyMode, boundary, cleanRule, combinedLevel, criticalPossible, defaultCritical, describeRule, family,
  formPatchFromRules, healthyOf, holds, isSimpleRule, legacyRule, logicOf, modeOf, moveRule, newRule, newRuleId, normalizeRule,
  cssSelectorProblem, parseCount, regexMatchesEmpty, regexSyntaxError, ruleLevelFor, ruleProblemText, ruleSetBadge, rulesFromForm, rulesFromRow, rulesSignature,
  toPayload, unsupportedRegex, validateRule, validateRules,
} from '../components/keyword/rules/ruleModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

const rule = (over = {}) => ({ id: 'r1', kind: 'TEXT', pattern: 'Fiyat', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null, ...over })
const codeAt = (errs, key) => errs[key]?.code ?? null
/** Sahte çeviri: anahtar(arg,…) — cümle yapısı test edilir, sözlük metni değil. */
const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)

describe('ruleModel — biçim ve geriye uyum', () => {
  it('eski satır tek kural türetir (id r1); rules doluysa onu kullanır; mantık yoksa VE', () => {
    expect(rulesFromRow({ keyword: 'Hoş', operator: 'LT', match_count: 1, case_sensitive: true })).toEqual([
      { id: 'r1', kind: 'TEXT', pattern: 'Hoş', case_sensitive: true, operator: 'LT', count: 1, critical: null, scope: null, label: null },
    ])
    const rows = rulesFromRow({ keyword: 'x', rules: [rule({ id: 'a' }), rule({ id: 'b', kind: 'regex', pattern: 'v\\d+' })] })
    expect(rows.map((r) => [r.id, r.kind])).toEqual([['a', 'TEXT'], ['b', 'REGEX']])
    expect(logicOf({})).toBe('AND')
    expect(logicOf({ rule_logic: 'or' })).toBe('OR')
    expect(logicOf({ ruleLogic: 'OR' })).toBe('OR')
    expect(rulesFromForm({ rules: null, keyword: 'k', operator: 'LTE', matchCount: 0, caseSensitive: false })[0])
      .toMatchObject({ pattern: 'k', operator: 'LTE', count: 0 })
    expect(legacyRule({}).pattern).toBe('')
  })

  it('normalize: bilinmeyen tür / işleç varsayılana, PAGE kapsamı null, boş etiket null', () => {
    expect(normalizeRule({ kind: 'X', operator: '??', scope: { type: 'page', value: '' }, label: '' }, 'z'))
      .toEqual({ id: 'z', kind: 'TEXT', pattern: '', case_sensitive: false, operator: 'GTE', count: '', critical: null, scope: null, label: null })
    expect(normalizeRule({ scope: { type: 'css', value: '#a' } }).scope).toEqual({ type: 'CSS', value: '#a' })
  })

  it('basit tek kural eski alanlarla gider (rules: null); diğerleri rules + ruleLogic; sayılar sayıya çevrilir', () => {
    expect(toPayload([rule({ count: '3', operator: 'GTE' })])).toEqual({ rules: null, keyword: 'Fiyat', operator: 'GTE', matchCount: 3, caseSensitive: false })
    for (const r of [rule({ kind: 'REGEX' }), rule({ scope: { type: 'CSS', value: '#p' } }), rule({ critical: { operator: 'GTE', count: 1 }, count: 4 }), rule({ label: 'Fiyat etiketi' })]) {
      expect(isSimpleRule(normalizeRule(r))).toBe(false)
      expect(toPayload([r]).rules).toHaveLength(1)
    }
    const multi = toPayload([rule(), rule({ id: 'r2', pattern: 'Sepete ekle', critical: { operator: 'GTE', count: '1' }, count: '5', scope: { type: 'JSON', value: ' $.a ' } })], 'OR')
    expect(multi.ruleLogic).toBe('OR')
    expect(multi.rules[1]).toEqual({ id: 'r2', kind: 'TEXT', pattern: 'Sepete ekle', case_sensitive: false, operator: 'GTE', count: 5,
      critical: { operator: 'GTE', count: 1 }, scope: { type: 'JSON', value: '$.a' }, label: null })
    expect(Object.keys(multi)).toEqual(['rules', 'ruleLogic'])
  })

  it('form yaması: eski alan AYNASI (kural #1) her zaman; basit tekte rules null', () => {
    expect(formPatchFromRules([rule({ pattern: 'A', operator: 'LTE', count: 0 })])).toEqual({
      rules: null, ruleLogic: 'AND', keyword: 'A', operator: 'LTE', matchCount: 0, caseSensitive: false,
    })
    const p = formPatchFromRules([rule({ kind: 'REGEX', pattern: 'a+' }), rule({ id: 'r2' })], 'OR')
    expect(p.rules).toHaveLength(2)
    expect(p).toMatchObject({ ruleLogic: 'OR', keyword: 'a+', operator: 'GTE', matchCount: 1 })
  })

  it('imza aynı içerikte aynı (sayı / metin farkı yok), sıra değişince farklı', () => {
    expect(rulesSignature([rule({ count: '2' })])).toBe(rulesSignature([rule({ count: 2 })]))
    const a = [rule(), rule({ id: 'r2', pattern: 'B' })]
    expect(rulesSignature(a)).not.toBe(rulesSignature(moveRule(a, 0, 1)))
    expect(moveRule(a, 0, -1)).toBe(a)
  })

  it('newRuleId: sunucu biçimi [a-z0-9-]{1,20}, listede tekil', () => {
    const id = newRuleId([], 1_700_000_000_000, () => 0.5)
    expect(id).toMatch(/^[a-z0-9-]{1,20}$/)
    expect(newRuleId([id], 1_700_000_000_000, () => 0.5)).not.toBe(id)
    expect(newRule().id).toMatch(/^[a-z0-9-]{1,20}$/)
    expect(parseCount(' 12 ')).toBe(12)
    for (const bad of ['', '1.5', '-1', 'abc', 1.2]) expect(parseCount(bad)).toBeNaN()
  })
})

describe('ruleModel — koşul aileleri, ön ayar, kritik eşik', () => {
  it('aile / sınır / sağlanma doğruluk tablosu', () => {
    expect(['LTE', 'LT', 'GTE', 'GT', 'EQ'].map(family)).toEqual(['upper', 'upper', 'lower', 'lower', 'eq'])
    expect([['LTE', 5], ['LT', 5], ['GTE', 5], ['GT', 5], ['EQ', 5]].map(([o, n]) => boundary(o, n))).toEqual([5, 4, 5, 6, 5])
    const rows = [['GTE', 1, 0, false], ['GTE', 1, 1, true], ['GT', 0, 0, false], ['LTE', 0, 0, true], ['LTE', 0, 2, false],
      ['LT', 5, 4, true], ['LT', 5, 5, false], ['EQ', 2, 2, true], ['EQ', 2, 3, false]]
    for (const [op, n, c, ok] of rows) expect(holds(op, n, c), `${op} ${n} @ ${c}`).toBe(ok)
  })

  it('ön ayar: içermeli GTE1/GT0, içermemeli LTE0/EQ0/LT1, diğerleri adet; uygulama', () => {
    expect([['GTE', 1], ['GT', 0], ['LTE', 0], ['EQ', 0], ['LT', 1], ['GTE', 10], ['EQ', 1], ['LT', 5]]
      .map(([operator, count]) => modeOf({ operator, count }))).toEqual(['contains', 'contains', 'absent', 'absent', 'absent', 'count', 'count', 'count'])
    expect(applyMode(rule({ operator: 'LT', count: 9, critical: { operator: 'LT', count: 20 } }), 'contains')).toMatchObject({ operator: 'GTE', count: 1, critical: null })
    expect(applyMode(rule({ operator: 'LT', count: 9, critical: { operator: 'LT', count: 20 } }), 'absent')).toMatchObject({ operator: 'LTE', count: 0, critical: { operator: 'LT', count: 20 } })
    expect(applyMode(rule({ critical: { operator: 'GTE', count: 1 } }), 'absent').critical).toBeNull()
  })

  it('kritik mümkün mü: EQ hayır, alt sınır uyarı ≥ 2, üst sınır tavan altı; önerilen başlangıç sınır kuralına uyar', () => {
    expect(criticalPossible(rule({ operator: 'EQ', count: 3 }))).toBe(false)
    expect(criticalPossible(rule({ operator: 'GTE', count: 1 }))).toBe(false)
    expect(criticalPossible(rule({ operator: 'GT', count: 1 }))).toBe(true)
    expect(criticalPossible(rule({ operator: 'LTE', count: RULE_LIMITS.textCountMax }))).toBe(false)
    expect(criticalPossible(rule({ operator: 'LTE', count: 0 }))).toBe(true)
    for (const r of [rule({ operator: 'GTE', count: 2 }), rule({ operator: 'GTE', count: 10 }), rule({ operator: 'GT', count: 1 }),
      rule({ operator: 'LT', count: 5 }), rule({ operator: 'LTE', count: 0 }), rule({ operator: 'LT', count: 1 })]) {
      const withCrit = { ...r, critical: defaultCritical(r) }
      expect(validateRule(withCrit, 0), JSON.stringify(withCrit)).toEqual({})
    }
  })
})

describe('ruleModel — doğrulama (sunucu aynası)', () => {
  const cases = [
    ['boş desen', rule({ pattern: '  ' }), 'pattern', 'patternRequired'],
    ['satır sonu', rule({ pattern: 'a\nb' }), 'pattern', 'patternNewline'],
    ['TEXT 256', rule({ pattern: 'x'.repeat(256) }), 'pattern', 'patternTooLong'],
    ['REGEX 201', rule({ kind: 'REGEX', pattern: 'a'.repeat(201) }), 'pattern', 'patternTooLong'],
    ['geri bakış', rule({ kind: 'REGEX', pattern: '(?<=a)b' }), 'pattern', 'regexLookaround'],
    ['ileri bakış', rule({ kind: 'REGEX', pattern: 'a(?!b)' }), 'pattern', 'regexLookaround'],
    ['atomik', rule({ kind: 'REGEX', pattern: '(?>ab)' }), 'pattern', 'regexLookaround'],
    ['geri başvuru', rule({ kind: 'REGEX', pattern: '(a)\\1' }), 'pattern', 'regexBackref'],
    ['adlı geri başvuru', rule({ kind: 'REGEX', pattern: '(?P<x>a)\\k<x>' }), 'pattern', 'regexBackref'],
    ['boş eşleşme', rule({ kind: 'REGEX', pattern: 'a*' }), 'pattern', 'regexEmptyMatch'],
    ['boş eşleşme (bayraklı)', rule({ kind: 'REGEX', pattern: '(?i)x?' }), 'pattern', 'regexEmptyMatch'],
    // Yapı hatası (istemci yalnız uzunluk + sözdizimi; karmaşıklık reddi sunucudan alan hatası olarak gelir — be2)
    ['kapanmayan parantez', rule({ kind: 'REGEX', pattern: '(ab' }), 'pattern', 'regexSyntax'],
    ['fazla kapanış', rule({ kind: 'REGEX', pattern: 'ab)' }), 'pattern', 'regexSyntax'],
    ['kapanmayan sınıf', rule({ kind: 'REGEX', pattern: '[ab' }), 'pattern', 'regexSyntax'],
    ['sonda ters bölü', rule({ kind: 'REGEX', pattern: 'ab\\' }), 'pattern', 'regexSyntax'],
    ['başta tekrar', rule({ kind: 'REGEX', pattern: '*ab' }), 'pattern', 'regexSyntax'],
    ['VEYA ardında tekrar', rule({ kind: 'REGEX', pattern: 'a|+b' }), 'pattern', 'regexSyntax'],
    ['grup başında tekrar', rule({ kind: 'REGEX', pattern: '(?:+a)' }), 'pattern', 'regexSyntax'],
    ['kapanmayan ad', rule({ kind: 'REGEX', pattern: '(?P<ad' }), 'pattern', 'regexSyntax'],
    ['adet boş', rule({ count: '' }), 'count', 'countRequired'],
    ['adet ondalık', rule({ count: '1.5' }), 'count', 'countRequired'],
    ['adet tavan üstü', rule({ count: RULE_LIMITS.textCountMax + 1 }), 'count', 'countRange'],
    ['REGEX tavan üstü', rule({ kind: 'REGEX', pattern: 'a+', count: 10_001 }), 'count', 'countRange'],
    ['B10 GTE 0', rule({ operator: 'GTE', count: 0 }), 'count', 'alwaysHealthy'],
    ['B10 LT 0', rule({ operator: 'LT', count: 0 }), 'count', 'neverHealthy'],
    ['EQ kritik', rule({ operator: 'EQ', count: 2, critical: { operator: 'EQ', count: 3 } }), 'critical', 'criticalEq'],
    ['aile', rule({ operator: 'LT', count: 5, critical: { operator: 'GTE', count: 1 } }), 'critical', 'criticalFamily'],
    ['üst sıra eşit', rule({ operator: 'LT', count: 5, critical: { operator: 'LTE', count: 4 } }), 'critical', 'criticalOrderUpper'],
    ['üst sıra ters', rule({ operator: 'LT', count: 20, critical: { operator: 'LT', count: 5 } }), 'critical', 'criticalOrderUpper'],
    ['alt sıra eşit', rule({ operator: 'GTE', count: 5, critical: { operator: 'GT', count: 4 } }), 'critical', 'criticalOrderLower'],
    ['alt kritik hiç ateşlemez', rule({ operator: 'GTE', count: 5, critical: { operator: 'GTE', count: 0 } }), 'critical', 'criticalNeverFires'],
    ['kritik sayı', rule({ operator: 'LT', count: 5, critical: { operator: 'LT', count: 'x' } }), 'critical', 'criticalCount'],
    ['CSS boş', rule({ scope: { type: 'CSS', value: ' ' } }), 'scope', 'scopeRequired'],
    ['CSS uzun', rule({ scope: { type: 'CSS', value: 'a'.repeat(301) } }), 'scope', 'scopeTooLong'],
    ['JSON sözdizimi', rule({ scope: { type: 'JSON', value: '$..a' } }), 'scope', 'scopeJsonSyntax'],
    // CSS: jsoup düzenli ifade seçicileri ve birden çok :has( reddedilir (be2 Aşama 1)
    ['CSS :matches', rule({ scope: { type: 'CSS', value: 'p:matches(\\d+)' } }), 'scope', 'scopeCssRegex'],
    ['CSS :matchesOwn', rule({ scope: { type: 'CSS', value: 'div:MatchesOwn(Fiyat)' } }), 'scope', 'scopeCssRegex'],
    ['CSS ~=', rule({ scope: { type: 'CSS', value: 'span[class~=fiyat]' } }), 'scope', 'scopeCssRegex'],
    ['CSS iki :has', rule({ scope: { type: 'CSS', value: 'div:has(a):has(b)' } }), 'scope', 'scopeCssHasMany'],
    ['etiket uzun', rule({ label: 'e'.repeat(81) }), 'label', 'labelTooLong'],
    ['kimlik', rule({ id: 'Bad_ID' }), 'id', 'idInvalid'],
  ]
  it.each(cases)('%s', (_, r, field, code) => {
    expect(codeAt(validateRule(r, 2), `rules[2].${field}`)).toBe(code)
  })

  const okCases = [
    rule(),
    rule({ kind: 'REGEX', pattern: 'Sipariş no: \\d{6}' }),
    rule({ kind: 'REGEX', pattern: '(?i)v\\d+\\.\\d+' }),
    rule({ kind: 'REGEX', pattern: '\\12x' }),
    rule({ kind: 'REGEX', pattern: '(?P<ad>a)b' }),
    rule({ kind: 'REGEX', pattern: '\\p{Lu}+' }),
    rule({ operator: 'LT', count: 5, critical: { operator: 'LT', count: 20 } }),
    rule({ operator: 'GTE', count: 10, critical: { operator: 'GTE', count: 3 } }),
    rule({ operator: 'LTE', count: 0, critical: { operator: 'LTE', count: 5 } }),
    rule({ scope: { type: 'JSON', value: '$.items[0].id' } }),
    rule({ scope: { type: 'CSS', value: 'main h1:contains(Fiyat)' } }),
    rule({ scope: { type: 'CSS', value: 'div:has(> a.fiyat)' } }),
    rule({ scope: { type: 'CSS', value: 'a[title="x ~= y :matches(z) :has(a):has(b)"]' } }),
    rule({ kind: 'REGEX', pattern: '(?:ab)+c' }),
    rule({ kind: 'REGEX', pattern: 'a*?b' }),
    rule({ kind: 'REGEX', pattern: '(?i:Fiyat)+[*+?]x' }),
    rule({ kind: 'REGEX', pattern: '\\(ab\\)+' }),
    rule({ kind: 'REGEX', pattern: '\\Q(a\\E+' }),   // RE2 düz metin bloğu: içindeki parantez yapı değil
  ]
  it.each(okCases.map((r) => [JSON.stringify(r.pattern) + ' ' + r.operator, r]))('geçerli: %s', (_, r) => {
    expect(validateRule(r, 0)).toEqual({})
  })

  it('liste: boş / 10 üstü / mükerrer kimlik', () => {
    expect(validateRules([]).rules.code).toBe('empty')
    expect(validateRules(Array.from({ length: 11 }, (_, i) => rule({ id: `r${i}` }))).rules.code).toBe('tooMany')
    expect(codeAt(validateRules([rule(), rule()]), 'rules[1].id')).toBe('idInvalid')
    expect(validateRules([rule(), rule({ id: 'r2' })])).toEqual({})
  })

  it('yapı denetimi ve CSS seçici denetimi doğrusal ve tırnak / kaçış farkında', () => {
    expect(regexSyntaxError('[)]a')).toBeNull()          // sınıf içinde parantez düz harf
    expect(regexSyntaxError('[]]+')).toBeNull()          // sınıfın ilk karakteri ']'
    expect(regexSyntaxError('(?i)')).toBeNull()
    expect(regexSyntaxError('(?P<ad>x)(?<b>y)')).toBeNull()
    expect(regexSyntaxError('((a)')).toBe('regexSyntax')
    expect(cssSelectorProblem("a[title='x\\' ~= y']")).toBeNull()
    expect(cssSelectorProblem('a\\:matches')).toBeNull()  // kaçışlı iki nokta düz harf
    expect(cssSelectorProblem('div:has(a)')).toBeNull()
    // Büyük girdide süre doğrusal (geri izleme yok)
    const big = '('.repeat(50_000) + 'a' + ')'.repeat(50_000)
    const t0 = Date.now()
    expect(regexSyntaxError(big)).toBeNull()
    expect(cssSelectorProblem(':has('.repeat(40_000))).toBe('cssHasMany')
    expect(Date.now() - t0).toBeLessThan(2000)
  })

  it('düzenli ifade yardımcıları: desteklenmeyen yapı ve boş eşleşme (bilinmeyen → null)', () => {
    expect(unsupportedRegex('a(?=b)')).toBe('regexLookaround')
    expect(unsupportedRegex('[(?=b)]')).toBeNull()      // karakter sınıfının içinde düz harf
    expect(unsupportedRegex('\\(?=b')).toBeNull()       // kaçışlı parantez
    expect(unsupportedRegex('[]a]\\1')).toBe('regexBackref')
    expect(regexMatchesEmpty('a+')).toBe(false)
    expect(regexMatchesEmpty('^$')).toBe(true)
    expect(regexMatchesEmpty('\\Qa*\\E')).toBe(false)   // JS'te farklı anlam ama derlenir — boş eşleşmez
    expect(regexMatchesEmpty('(?U)a*')).toBeNull()       // açgözlü olmayan RE2 bayrağı: bilinmiyor
    expect(regexMatchesEmpty('(?i:a')).toBeNull()        // derlenemez: karar sunucunun
  })

  it('hata kodu metni sözlükten; kapsam türü parametresi kendi etiketine; sunucu dizgesi aynen', () => {
    expect(ruleProblemText({ code: 'scopeRequired', params: ['CSS'] }, t)).toBe('keyword.rule.v.scopeRequired(keyword.rule.scopeValue.CSS)')
    expect(ruleProblemText('Sunucu: geçersiz', t)).toBe('Sunucu: geçersiz')
    expect(ruleProblemText(null, t)).toBeNull()
    // Her kod iki dilde var
    const codes = new Set([...cases.map((c) => c[3]), 'empty', 'tooMany'])
    for (const c of codes) {
      expect(TR[`keyword.rule.v.${c}`], c).toBeTruthy()
      expect(EN[`keyword.rule.v.${c}`], c).toBeTruthy()
    }
  })
})

describe('ruleModel — seviye birleşimi (sunucu KeywordRuleEvaluator aynası)', () => {
  it('kural seviyesi: kritikli → CRITICAL / WARNING / NONE; kritiksiz → MONITOR / NONE', () => {
    const r = rule({ operator: 'LT', count: 5, critical: { operator: 'LT', count: 20 } })
    expect([0, 4, 5, 19, 20, 99].map((c) => ruleLevelFor(r, c))).toEqual(['NONE', 'NONE', 'WARNING', 'WARNING', 'CRITICAL', 'CRITICAL'])
    expect([0, 1, 3].map((c) => ruleLevelFor(rule(), c))).toEqual(['MONITOR', 'NONE', 'NONE'])
  })

  // [seviyeler, mantık, izleme seviyesi, beklenen]
  const table = [
    [[], 'AND', 'WARNING', 'NONE'],
    [['NONE', 'NONE'], 'AND', 'WARNING', 'NONE'],
    [['NONE', 'WARNING'], 'AND', 'WARNING', 'WARNING'],
    [['WARNING', 'CRITICAL'], 'AND', 'WARNING', 'CRITICAL'],
    [['MONITOR', 'WARNING'], 'AND', 'HIGH', 'HIGH'],
    [['MONITOR'], 'AND', 'CRITICAL', 'CRITICAL'],
    [['MONITOR'], 'AND', 'bogus', 'WARNING'],
    [['NONE', 'CRITICAL'], 'OR', 'WARNING', 'NONE'],
    [['WARNING', 'CRITICAL'], 'OR', 'WARNING', 'WARNING'],
    [['CRITICAL', 'MONITOR'], 'OR', 'HIGH', 'HIGH'],
    [['CRITICAL', 'CRITICAL'], 'OR', 'WARNING', 'CRITICAL'],
  ]
  it.each(table.map((r) => [`${r[0].join('+') || '∅'} ${r[1]} (${r[2]})`, r]))('%s', (_, [levels, logic, mon, expected]) => {
    expect(combinedLevel(levels, logic, mon)).toBe(expected)
  })
})

describe('ruleModel — insan dilinde açıklama (B11)', () => {
  it('alarm ↔ sağlıklı dönüşümü iki yönlü', () => {
    for (const op of ['GTE', 'GT', 'LTE', 'LT', 'EQ']) expect(healthyOf(alarmOf(op, 7))).toEqual({ op, n: 7 })
    expect(alarmOf('LT', 5)).toEqual({ op: 'GTE', n: 5 })
  })

  it('içermeli / içermemeli / adet cümleleri; kapsam ve düzenli ifade hedefi; kritikte iki seviye', () => {
    expect(describeRule(rule(), t, 'HIGH')).toEqual({
      healthy: 'keyword.rule.desc.healthyContains(« Fiyat »,keyword.rule.where.PAGE)',
      warning: 'keyword.rule.desc.alarm(keyword.rule.level.HIGH,keyword.rule.alarm.ABSENT(« Fiyat »,keyword.rule.where.PAGE))',
      critical: null,
    })
    expect(describeRule(rule({ operator: 'LTE', count: 0 }), t).warning).toContain('keyword.rule.alarm.PRESENT')
    const d = describeRule(rule({ kind: 'REGEX', pattern: 'hata\\d', operator: 'LT', count: 5, critical: { operator: 'LT', count: 20 }, scope: { type: 'CSS', value: '#log' } }), t)
    expect(d.healthy).toBe('keyword.rule.desc.healthy(keyword.rule.desc.regex(hata\\d),keyword.rule.where.CSS(#log),keyword.expect.LT(5))')
    expect(d.warning).toBe('keyword.rule.desc.warning(keyword.rule.alarm.GTE(keyword.rule.desc.regex(hata\\d),keyword.rule.where.CSS(#log),5))')
    expect(d.critical).toBe('keyword.rule.desc.critical(keyword.rule.alarm.GTE(keyword.rule.desc.regex(hata\\d),keyword.rule.where.CSS(#log),20))')
    expect(describeRule(rule({ operator: 'GTE', count: '' }), t)).toEqual({ healthy: null, warning: null, critical: null })
  })

  it('gerçek sözlükte cümleler TR ve EN — yer tutucu kalmaz, sabit Türkçe sızmaz', () => {
    const mk = (dict) => (k, ...a) => { let s = dict[k] ?? k; a.forEach((v, i) => { s = s.split(`{${i}}`).join(String(v)) }); return s }
    const r = rule({ operator: 'LT', count: 5, critical: { operator: 'LT', count: 20 }, pattern: 'hata' })
    for (const dict of [TR, EN]) {
      const d = describeRule(r, mk(dict))
      for (const s of [d.healthy, d.warning, d.critical]) {
        expect(s).toBeTruthy()
        expect(s).not.toMatch(/\{\d\}|keyword\./)
      }
    }
    expect(describeRule(r, mk(EN)).warning).not.toMatch(/bulun|tetikle/)
  })

  it('liste rozeti yalnız 2+ kuralda', () => {
    expect(ruleSetBadge([rule()], 'AND', t)).toBeNull()
    expect(ruleSetBadge([rule(), rule({ id: 'b' }), rule({ id: 'c' })], 'OR', t)).toBe('keyword.rule.badge(3,keyword.rule.logicShort.OR)')
  })

  it('temiz kural: kapsam değeri kırpılır, PAGE → null', () => {
    expect(cleanRule(rule({ scope: { type: 'CSS', value: '  #x ' }, label: '  ' }))).toMatchObject({ scope: { type: 'CSS', value: '#x' }, label: null })
  })
})
