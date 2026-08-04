import { describe, it, expect } from 'vitest'
import { normalizeUrl } from '../utils/normalizeUrl.js'

// Backend MonitorUrlsTest ile aynı vaka listesi — davranış paritesi (backend otorite, bu yalnız UX aynası).
describe('normalizeUrl', () => {
  it('şemasız girdiye https:// ekler', () => {
    expect(normalizeUrl('www.axess.com.tr')).toBe('https://www.axess.com.tr')
    expect(normalizeUrl('  www.axess.com.tr  ')).toBe('https://www.axess.com.tr')
    expect(normalizeUrl('x.com/a?b=1')).toBe('https://x.com/a?b=1')
  })

  it('mevcut şemayı ASLA değiştirmez (http:// tercihi korunur)', () => {
    expect(normalizeUrl('http://internal.host:8080/health')).toBe('http://internal.host:8080/health')
    expect(normalizeUrl('HTTPS://X.COM')).toBe('HTTPS://X.COM')
    expect(normalizeUrl('ftp://x.com')).toBe('ftp://x.com')
  })

  it('protokol-relatif ve boş girdi', () => {
    expect(normalizeUrl('//x.com/a')).toBe('https://x.com/a')
    expect(normalizeUrl('')).toBe('')
    expect(normalizeUrl('   ')).toBe('')
    expect(normalizeUrl(null)).toBe('')
    expect(normalizeUrl(undefined)).toBe('')
  })

  it('keyword {timestamp} yer tutucusunu bozmaz', () => {
    expect(normalizeUrl('x.com/a?t={timestamp}')).toBe('https://x.com/a?t={timestamp}')
  })
})
