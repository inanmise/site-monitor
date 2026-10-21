import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  DEFAULT_LIMITS, changedPrefixFields, exemptProblem, foldName, prefixProblem, splitTokens, tightensRule, validatePrefixForm,
} from '../components/admin/loginmethods/usernamePrefixModel.js'
import {
  isUsernamePrefixPayload, prefixesOf, resetUsernamePrefixSignal, signalUsernamePrefix, usernamePrefixSignaled,
} from '../utils/usernamePrefix.js'

/** Kullanıcı adı ön eki kartının saf modeli + istemci sinyali (2026-10-21). Sınırlar Java tarafından okunur (tek kaynak). */
const RULE = path.resolve(__dirname,
  '../../../backend/src/main/java/com/sitemonitor/service/loginpolicy/UsernamePrefixRule.java')
const javaInt = (name) => Number(new RegExp(`int ${name} = (\\d+);`).exec(fs.readFileSync(RULE, 'utf8'))?.[1])

const t = (k, ...a) => `${k}${a.length ? `:${a.join('|')}` : ''}`

describe('usernamePrefixModel', () => {
  it('varsayılan sınırlar Java UsernamePrefixRule sabitleriyle aynı', () => {
    expect(DEFAULT_LIMITS.prefix_max_len).toBe(javaInt('PREFIX_MAX_LEN'))
    expect(DEFAULT_LIMITS.max_prefixes).toBe(javaInt('MAX_PREFIXES'))
    expect(DEFAULT_LIMITS.exempt_max_len).toBe(javaInt('EXEMPT_MAX_LEN'))
    expect(DEFAULT_LIMITS.max_exempt).toBe(javaInt('MAX_EXEMPT'))
  })

  it('foldName: Türkçe i tuzağı yok (ı, i, İ → I), kırpar', () => {
    expect(foldName('  kişi ')).toBe('KIŞI')
    expect(foldName('İSTANBUL')).toBe('ISTANBUL')
    expect(foldName('ırmak')).toBe('IRMAK')
    expect(foldName('i̇')).toBe('I')
  })

  it('prefixProblem: harf + rakam, en çok 10; Türkçe karakter / tire / boşluk / 11 karakter hata', () => {
    expect(prefixProblem('N')).toBeNull()
    expect(prefixProblem('mp')).toBeNull()
    expect(prefixProblem('A1B2C3D4E5')).toBeNull()
    expect(prefixProblem('Ç')).toMatchObject({ key: 'lm.prefix.err.prefixFormat' })
    expect(prefixProblem('N-1')).not.toBeNull()
    expect(prefixProblem('ABCDEFGHIJK')).not.toBeNull()
  })

  it('exemptProblem: boşluk / virgül / denetim karakteri / 100+ hata; nokta ve alt çizgi serbest', () => {
    expect(exemptProblem('servis.hesap_1')).toBeNull()
    expect(exemptProblem('a b')).not.toBeNull()
    expect(exemptProblem('a,b')).not.toBeNull()
    expect(exemptProblem('a\u0007b')).not.toBeNull()
    expect(exemptProblem('x'.repeat(101))).not.toBeNull()
  })

  it('splitTokens: virgül, noktalı virgül, boşluk, satır sonu', () => {
    expect(splitTokens(' n, mp;t2\nX9  ')).toEqual(['n', 'mp', 't2', 'X9'])
    expect(splitTokens('')).toEqual([])
  })

  it('changedPrefixFields: yalnız DEĞİŞEN alanlar; sunucu bloğu yoksa hiç', () => {
    const saved = { username_prefix_enabled: false, username_prefix_allowed: ['N'], username_prefix_exempt: [] }
    expect(changedPrefixFields({ ...saved }, saved)).toEqual({})
    expect(changedPrefixFields({ ...saved, username_prefix_enabled: true }, saved)).toEqual({ username_prefix_enabled: true })
    expect(changedPrefixFields({ ...saved, username_prefix_exempt: ['OPS'] }, saved)).toEqual({ username_prefix_exempt: ['OPS'] })
    expect(changedPrefixFields({ ldap_enabled: true }, {})).toEqual({})
  })

  it('validatePrefixForm: açıkken boş liste hata; kapalıyken boş liste serbest; sınır aşımı', () => {
    expect(validatePrefixForm({ username_prefix_enabled: true, username_prefix_allowed: [] }, null, t))
      .toEqual({ username_prefix_allowed: 'lm.prefix.err.emptyAllowed' })
    expect(validatePrefixForm({ username_prefix_enabled: false, username_prefix_allowed: [] }, null, t)).toEqual({})
    const many = Array.from({ length: 21 }, (_, i) => `P${i}`)
    expect(validatePrefixForm({ username_prefix_enabled: true, username_prefix_allowed: many }, null, t))
      .toEqual({ username_prefix_allowed: 'lm.prefix.err.tooManyPrefixes:20' })
    expect(validatePrefixForm({}, null, t)).toEqual({})
  })

  it('tightensRule: açılış ve açıkken liste değişimi onay ister; kapatma ve değişmeyen açık kural istemez', () => {
    const off = { username_prefix_enabled: false, username_prefix_allowed: ['N'], username_prefix_exempt: [] }
    const on = { ...off, username_prefix_enabled: true }
    expect(tightensRule(on, off)).toBe(true)
    expect(tightensRule({ ...on, username_prefix_allowed: ['N', 'MP'] }, on)).toBe(true)
    expect(tightensRule({ ...on, username_prefix_exempt: ['OPS'] }, on)).toBe(true)
    expect(tightensRule(on, on)).toBe(false)
    expect(tightensRule(off, on)).toBe(false)
  })
})

describe('usernamePrefix sinyali', () => {
  it('gövde tanıma + ön ekler (bozuk değer süzülür)', () => {
    expect(isUsernamePrefixPayload({ code: 'USERNAME_PREFIX_NOT_ALLOWED' })).toBe(true)
    expect(isUsernamePrefixPayload({ error_code: 'USERNAME_PREFIX_NOT_ALLOWED' })).toBe(true)
    expect(isUsernamePrefixPayload({ code: 'ACCOUNT_INACTIVE' })).toBe(false)
    expect(isUsernamePrefixPayload(null)).toBe(false)
    expect(prefixesOf({ allowed_prefixes: ['N', '', 7, 'X'.repeat(11), 'MP'] })).toEqual(['N', 'MP'])
    expect(prefixesOf({})).toEqual([])
  })

  it('sinyal sayfa ömründe bir kez yayılır ve öteki sekmelere localStorage ile duyurulur', () => {
    resetUsernamePrefixSignal()
    localStorage.clear()
    expect(signalUsernamePrefix(['N'])).toBe(true)
    expect(signalUsernamePrefix(['N'])).toBe(false)
    expect(usernamePrefixSignaled()).toBe(true)
    expect(JSON.parse(localStorage.getItem('sm.account.usernamePrefix')).prefixes).toEqual(['N'])
    resetUsernamePrefixSignal()
  })
})
