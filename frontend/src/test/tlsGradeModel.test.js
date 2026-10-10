import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { TLS_GRADE_REASONS, TLS_GRADES, TLS_STATE_CODES } from '../components/tlsgrade/tlsGradeCodes.js'
import {
  REASON_CAP, decisiveCodes, distribution, dropOfRow, gradeFilterOptions, gradeLabelKey, gradeOfRow, gradeRank,
  otherCodes, protocolRows, reasonTexts, triTone,
} from '../components/tlsgrade/tlsGradeModel.js'

/**
 * TLS notu sunum modeli (2026-10-10): not SUNUCUDA hesaplanır; burada yalnız "hangi neden notu belirledi" (tavan = not),
 * düşüş göstergesi, süzgeç seçenekleri ve protokol satırları. Katalog backend enum'uyla aynı olmalı (Java kaynağı okunur).
 */
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/tlsgrade/TlsGradeRules.java')
const t = (k, ...a) => a.reduce((s, x, i) => s.split(`{${i}}`).join(String(x)), EN[k] ?? k)

describe('tlsGradeCodes ↔ TlsGradeRules.java', () => {
  it('neden kodları ve tavanları Java enum’uyla aynı sırada', () => {
    const src = fs.readFileSync(JAVA, 'utf8')
    const body = src.slice(src.indexOf('public enum Reason'), src.indexOf('private final String cap'))
    const java = [...body.matchAll(/([A-Z0-9_]+)\((null|"([A-F+]{1,2})")\)/g)].map((m) => [m[1], m[3] ?? null])
    expect(java.length).toBeGreaterThan(20)
    expect(TLS_GRADE_REASONS).toEqual(java)
  })

  it('her kodun, durumun ve notun TR + EN metni var', () => {
    for (const [code] of TLS_GRADE_REASONS) {
      for (const part of ['title', 'why', 'fix']) {
        expect(TR[`tlsg.reason.${code}.${part}`], `TR ${code}.${part}`).toBeTruthy()
        expect(EN[`tlsg.reason.${code}.${part}`], `EN ${code}.${part}`).toBeTruthy()
      }
      // başlık parametresizdir (rozet satırında parametresiz okunur)
      expect(TR[`tlsg.reason.${code}.title`]).not.toMatch(/\{\d\}/)
      expect(EN[`tlsg.reason.${code}.title`]).not.toMatch(/\{\d\}/)
    }
    for (const s of TLS_STATE_CODES) expect(EN[`tlsg.state.${s}`]).toBeTruthy()
    for (const g of TLS_GRADES) {
      expect(TR[gradeLabelKey(g)]).toBeTruthy()
      expect(EN[gradeLabelKey(g)]).toBeTruthy()
    }
  })
})

describe('tlsGradeModel', () => {
  it('not sırası ve belirleyici nedenler (tavan = not)', () => {
    expect(gradeRank('A+')).toBeGreaterThan(gradeRank('A'))
    expect(gradeRank('F')).toBe(1)
    expect(gradeRank('X')).toBe(0)
    const codes = ['TLS10_ENABLED', 'NO_PFS', 'NO_TLS13', 'HSTS_MISSING']
    expect(decisiveCodes('B', codes)).toEqual(['TLS10_ENABLED', 'NO_PFS'])
    expect(otherCodes('B', codes)).toEqual(['NO_TLS13', 'HSTS_MISSING'])
    expect(otherCodes('B', ['KEY_2030', 'BOGUS'])).toEqual([])   // bilgi notu ve tanınmayan kod sınırlayıcı değil
    expect(REASON_CAP.KEY_2030).toBeNull()
  })

  it('satır özeti: not yoksa null; düşüş yalnız gerçekten aşağıysa', () => {
    expect(gradeOfRow({ domain: 'x' })).toBeNull()
    expect(gradeOfRow({ tls_grade: 'Z' })).toBeNull()
    const g = gradeOfRow({ tls_grade: 'C', tls_grade_reasons: ['NO_TLS12', 'TLS10_ENABLED'], tls_grade_drop: { from: 'A', to: 'C', at: '2026-10-09T10:00:00' } })
    expect(g.grade).toBe('C')
    expect(g.decisive).toEqual(['NO_TLS12'])
    expect(g.others).toEqual(['TLS10_ENABLED'])
    expect(g.drop).toMatchObject({ from: 'A', to: 'C' })
    expect(dropOfRow({ tls_grade_drop: { from: 'B', to: 'A' } })).toBeNull()
    expect(dropOfRow({ tls_grade_drop: { from: 'A' } })).toBeNull()
  })

  it('süzgeç seçenekleri facet sayılı; notsuz seçenek sonda', () => {
    const opts = gradeFilterOptions(t, { grades: { 'A+': 2, A: 5, B: 1, C: 0, D: 0, F: 1, none: 3 } })
    expect(opts.map((o) => o.value)).toEqual(['A+', 'A', 'B', 'C', 'D', 'F', 'none'])
    expect(opts[0].label).toBe('A+ (2)')
    expect(opts.at(-1).label).toBe('Not graded (3)')
    expect(distribution({ grades: { A: 4 } }).find((d) => d.grade === 'A').count).toBe(4)
  })

  it('protokol satırları: 1.3/1.2 açık iyi, 1.1/1.0 açık kötü, bilinmeyen gri', () => {
    const rows = protocolRows({ protocols: { tls13: 'NO', tls12: 'YES', tls11: 'UNKNOWN', tls10: 'YES' } })
    expect(rows.map((r) => [r.key, r.tone])).toEqual([['tls13', 'bad'], ['tls12', 'ok'], ['tls11', 'unknown'], ['tls10', 'bad']])
    expect(triTone('YES', 'YES')).toBe('ok')
    expect(triTone('NO', 'YES')).toBe('bad')
    expect(triTone(null, 'YES')).toBe('unknown')
  })

  it('neden metinleri sunucunun parametreleriyle kurulur (başlık parametresiz)', () => {
    const x = reasonTexts(t, { code: 'KEY_WEAK', cap: 'F', params: ['RSA', 1024] })
    expect(x.title).toBe('Weak key')
    expect(x.why).toContain('RSA 1024 bits')
    expect(x.cap).toBe('F')
  })
})
