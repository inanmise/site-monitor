import { describe, it, expect } from 'vitest'
import { eppKey, eppLabel, domainLife } from '../utils/domainEpp.js'

/** Alan adı kartı zenginleştirme (2026-09-22): EPP tek biçim + kayıt dönemi çubuğu. */
describe('eppLabel / eppKey', () => {
  it('RDAP boşluklu ve WHOIS camelCase aynı etikete iner', () => {
    expect(eppLabel('client transfer prohibited')).toBe('client transfer prohibited')
    expect(eppLabel('clientTransferProhibited')).toBe('client transfer prohibited')
    expect(eppLabel('serverDeleteProhibited')).toBe('server delete prohibited')
    expect(eppLabel('active')).toBe('active')
    expect(eppLabel('')).toBe('')
    expect(eppLabel(null)).toBe('')
  })
  it('eppKey karşılaştırma anahtarı: boşluksuz küçük harf', () => {
    expect(eppKey('client transfer prohibited')).toBe(eppKey('clientTransferProhibited'))
  })
})

describe('domainLife', () => {
  const now = Date.parse('2026-09-22T00:00:00Z')
  it('başlangıç→bitiş toplamı ve geçen gün; RDAP datetime ve WHOIS date-only', () => {
    const l = domainLife('2024-12-08T08:34:00Z', '2027-03-28T10:41:00Z', now)
    expect(l.total).toBe(840)
    expect(l.elapsed).toBe(653)
    expect(l.pct).toBe(78)
    const d = domainLife('2006-10-27', '2029-10-26', now)
    expect(d.total).toBe(8400)
  })
  it('geçen gün 0..toplam aralığına kırpılır (gelecekteki başlangıç / dolmuş kayıt)', () => {
    expect(domainLife('2027-01-01', '2028-01-01', now).elapsed).toBe(0)
    const past = domainLife('2020-01-01', '2021-01-01', now)
    expect(past.elapsed).toBe(past.total)
    expect(past.pct).toBe(100)
  })
  it('eksik/bozuk/ters tarihte null (sıfır uydurulmaz)', () => {
    expect(domainLife(null, '2027-01-01', now)).toBeNull()
    expect(domainLife('2027-01-01', null, now)).toBeNull()
    expect(domainLife('garbage', '2027-01-01', now)).toBeNull()
    expect(domainLife('2028-01-01', '2027-01-01', now)).toBeNull()
  })
})
