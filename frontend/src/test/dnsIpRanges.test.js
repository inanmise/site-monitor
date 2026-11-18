import { describe, it, expect } from 'vitest'
import { readJavaTest, readJavaMain, javaStringTable } from './helpers/javaTable.js'
import {
  MAX_TEXT, MIN_PREFIX_V4, MIN_PREFIX_V6, CODE_INVALID, CODE_FAMILY, CODE_TOO_BROAD, CODE_RECORD_TYPE,
  ipAddress, parseRange, contains, sameAddress, expectedValueProblem, expectedValueProblemText,
} from '../components/dns/ipRanges.js'
import { unexpectedValues } from '../components/dns/dnsValue.js'

// DNS beklenen-değer aralığı (CIDR) — Java `IpRanges` / `DnsExpectedValues` ikizi (2026-11-18). Tablolar Java test
// kaynağından okunur: iki taraf aynı satırlarla sınanır, kurallar ayrışamaz.
const JAVA_TEST = readJavaTest('service/dns/IpRangesTest.java')
const lines = (s) => String(s).split('\n').map((x) => x.trim()).filter(Boolean)

function javaConst(rel, name) {
  const m = new RegExp(`${name}\\s*=\\s*("?)([^";]+)\\1;`).exec(readJavaMain(rel))
  if (!m) throw new Error(`${name} Java kaynağında yok`)
  return m[1] ? m[2] : Number(m[2])
}

describe('ipRanges — Java ile aynı kurallar', () => {
  it('sabitler Java ile aynı', () => {
    expect(MAX_TEXT).toBe(javaConst('service/dns/IpRanges.java', 'MAX_TEXT'))
    expect(MIN_PREFIX_V4).toBe(javaConst('service/dns/DnsExpectedValues.java', 'MIN_PREFIX_V4'))
    expect(MIN_PREFIX_V6).toBe(javaConst('service/dns/DnsExpectedValues.java', 'MIN_PREFIX_V6'))
    expect(CODE_INVALID).toBe(javaConst('service/dns/DnsExpectedValues.java', 'CODE_INVALID'))
    expect(CODE_FAMILY).toBe(javaConst('service/dns/DnsExpectedValues.java', 'CODE_FAMILY'))
    expect(CODE_TOO_BROAD).toBe(javaConst('service/dns/DnsExpectedValues.java', 'CODE_TOO_BROAD'))
    expect(CODE_RECORD_TYPE).toBe(javaConst('service/dns/DnsExpectedValues.java', 'CODE_RECORD_TYPE'))
  })

  it('PARSE_TABLE: aralık / tek adres / reddedilen biçimler', () => {
    const rows = javaStringTable(JAVA_TEST, 'PARSE_TABLE')
    expect(rows.length).toBeGreaterThan(30)
    for (const [text, want] of rows) {
      const kind = parseRange(text) ? 'range' : ipAddress(text) ? 'address' : 'none'
      expect(kind, JSON.stringify(text)).toBe(want)
    }
  })

  it('MATCH_TABLE: aralık içerme', () => {
    const rows = javaStringTable(JAVA_TEST, 'MATCH_TABLE')
    expect(rows.length).toBeGreaterThan(15)
    for (const [range, value, want] of rows) {
      expect(contains(range, value), `${range} ∋ ${value}`).toBe(want === 'true')
    }
  })

  it('EXPECTED_TABLE: beklenen-değer aralık kuralı', () => {
    const rows = javaStringTable(JAVA_TEST, 'EXPECTED_TABLE')
    expect(rows.length).toBeGreaterThan(15)
    for (const [joined, type, code] of rows) {
      const p = expectedValueProblem(joined, type)
      expect(p ? p.code : null, `${JSON.stringify(joined)} (${type})`).toBe(code)
    }
  })

  it('UNEXPECTED_TABLE: beklenmeyen değerler (dnsValue.unexpectedValues)', () => {
    const rows = javaStringTable(JAVA_TEST, 'UNEXPECTED_TABLE')
    expect(rows.length).toBeGreaterThan(8)
    for (const [expected, live, want] of rows) {
      expect(unexpectedValues(expected, lines(live)), `${expected} / ${live}`).toEqual(lines(want))
    }
  })

  it('aynı adres: farklı IPv6 yazımı ve eşlenmiş IPv4', () => {
    expect(sameAddress('2a06:98c1:3120::3', '2a06:98c1:3120:0::3')).toBe(true)
    expect(sameAddress('::ffff:1.2.3.4', '1.2.3.4')).toBe(true)
    expect(sameAddress('1.2.3.4', '1.2.3.5')).toBe(false)
    expect(sameAddress('bozuk', 'bozuk')).toBe(false)
  })

  it('sorun metni: koda göre çeviri anahtarı, uzun satır 80 karakterde kısaltılır', () => {
    const t = (key, ...args) => `${key}|${args.join('|')}`
    expect(expectedValueProblemText(null, t)).toBeNull()
    expect(expectedValueProblemText({ code: CODE_FAMILY, line: 'x' }, t)).toBe('dns.cidrFamily|x')
    expect(expectedValueProblemText({ code: CODE_TOO_BROAD, line: 'x' }, t)).toBe('dns.cidrTooBroad|x|8|16')
    expect(expectedValueProblemText({ code: CODE_RECORD_TYPE, line: 'x' }, t)).toBe('dns.cidrRecordType|x')
    expect(expectedValueProblemText({ code: CODE_INVALID, line: 'x' }, t)).toBe('dns.cidrInvalid|x')
    const long = 'a'.repeat(100)
    expect(expectedValueProblemText({ code: CODE_INVALID, line: long }, t)).toBe(`dns.cidrInvalid|${'a'.repeat(80)}…`)
  })

  it('doğrusal ve sınırlı: çok uzun / çok satırlı girdi hızlı', () => {
    const t0 = Date.now()
    expect(parseRange(`${'1'.repeat(200000)}/8`)).toBeNull()
    expect(ipAddress(':'.repeat(100000))).toBeNull()
    const many = Array(20000).fill('188.114.96.0/20').join('\n')
    expect(expectedValueProblem(many, 'A')).toBeNull()
    expect(unexpectedValues(many, ['188.114.96.3'])).toEqual([])
    expect(Date.now() - t0).toBeLessThan(5000)
  })
})
