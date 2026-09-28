import { describe, it, expect } from 'vitest'
import { monitorDeepLink, domainDeepLink, tabDeepLink, certDeepLink, DEEP_OPEN } from '../utils/monitorDeepLink.js'

/**
 * Paylaşılabilir derin bağlantı. Sözleşmenin iki kritik yanı var:
 *  - MUTLAK olmalı (Slack/e-postaya yapıştırılıyor, göreli metin tıklanabilir olmaz)
 *  - kullanıcının o anki FİLTRELERİNİ taşımamalı (linki alan boş liste görmemeli)
 */
describe('monitorDeepLink', () => {
  it('kanonik ?tab=&monitor= biçimini MUTLAK URL olarak üretir', () => {
    const url = monitorDeepLink('scripted', 2)
    expect(url).toContain('?tab=scripted&monitor=2')
    expect(url.startsWith('http')).toBe(true)
  })

  it('adres çubuğundaki filtre/arama parametrelerini TAŞIMAZ', () => {
    window.history.replaceState({}, '', '/?tab=scripted&q=ödeme&status=fail&page=3')
    const url = monitorDeepLink('scripted', 7)
    expect(url).not.toContain('q=')
    expect(url).not.toContain('status=')
    expect(url).not.toContain('page=')
    expect(url).toContain('monitor=7')
  })

  it('tab ve id kaçışlanır', () => {
    expect(monitorDeepLink('scripted', 'a b')).toContain('monitor=a%20b')
  })

  it('eksik girdide null döner (çıplak "?tab=undefined" bağlantısı üretilmez)', () => {
    expect(monitorDeepLink('scripted', null)).toBeNull()
    expect(monitorDeepLink('scripted', undefined)).toBeNull()
    expect(monitorDeepLink(null, 3)).toBeNull()
  })

  it('id=0 geçerli bir id\'dir, null sayılmaz', () => {
    expect(monitorDeepLink('http', 0)).toContain('monitor=0')
  })

  it('domainDeepLink alan adı anahtarlı yüzeyler için ?domain= üretir', () => {
    const url = domainDeepLink('uptime', 'ödeme.example.com')
    expect(url).toContain('?tab=uptime&domain=')
    expect(url).toContain(encodeURIComponent('ödeme.example.com'))
    expect(domainDeepLink('uptime', '')).toBeNull()
  })

  it('tabDeepLink: monitorDeepLink ile AYNI metin; boş değer yazılmaz, sıra korunur, kaçışlanır', () => {
    expect(tabDeepLink('http', { monitor: 5 })).toBe(monitorDeepLink('http', 5))
    expect(tabDeepLink('http', { monitor: 5, open: null, x: '' })).toBe(monitorDeepLink('http', 5))
    expect(tabDeepLink('dns', { monitor: 7, open: DEEP_OPEN.NOC })).toMatch(/[?]tab=dns&monitor=7&open=noc$/)
    expect(tabDeepLink('dashboard', { domain: 'a b.example.com' })).toContain('domain=a%20b.example.com')
    expect(tabDeepLink(null, { monitor: 1 })).toBeNull()
  })

  it('certDeepLink: Pano + alan + open=cert (varsayılan) / open=noc; alan yoksa null', () => {
    expect(certDeepLink('shop.example.com')).toMatch(/^http.*[?]tab=dashboard&domain=shop[.]example[.]com&open=cert$/)
    expect(certDeepLink('shop.example.com', DEEP_OPEN.NOC)).toMatch(/&open=noc$/)
    expect(certDeepLink('')).toBeNull()
  })
})
