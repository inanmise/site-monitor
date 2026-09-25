import { describe, it, expect } from 'vitest'
import {
  PLATFORM_NONE, PLATFORM_URL_KEY, parsePlatformParam, serializePlatformParam, platformKeyOf,
  matchesPlatform, countPlatforms, buildPlatformOptions,
} from '../utils/platformFilter.js'
import { PAGE_STATE_PARAMS, PAGE_STATE_PREFIXES } from '../hooks/useUrlQuerySync.js'

/** Genel Bakış platform süzgeci — saf yardımcılar (utils/platformFilter.js). */
describe('platformFilter — URL biçimi', () => {
  it('virgüllü değeri tekil kod listesine çevirir; boş/tekrar/boşluk atılır', () => {
    expect(parsePlatformParam('IIS,OPENSHIFT')).toEqual(['IIS', 'OPENSHIFT'])
    expect(parsePlatformParam(' IIS , ,IIS,__none__ ')).toEqual(['IIS', PLATFORM_NONE])
    expect(parsePlatformParam('')).toEqual([])
    expect(parsePlatformParam(null)).toEqual([])
  })

  it('boş seçim → null (param silinir); dolu seçim → virgüllü, gidiş-dönüş kayıpsız', () => {
    expect(serializePlatformParam([])).toBeNull()
    expect(serializePlatformParam(undefined)).toBeNull()
    const sel = ['IIS', PLATFORM_NONE, 'K8S_PROD']
    expect(parsePlatformParam(serializePlatformParam(sel))).toEqual(sel)
  })

  it('URL anahtarı uygulama parametreleriyle çakışmaz ve sekme değişiminde temizlenir', () => {
    expect(['tab', 'domain', 'monitor', 'incident']).not.toContain(PLATFORM_URL_KEY)
    expect(PAGE_STATE_PREFIXES.some((p) => PLATFORM_URL_KEY.startsWith(p))).toBe(false)
    expect(PAGE_STATE_PARAMS).toContain(PLATFORM_URL_KEY)
  })
})

describe('platformFilter — eşleşme ve sayım', () => {
  const iis = { domain: 'a.example.com', platform: 'IIS' }
  const ocp = { domain: 'b.example.com', platform: 'OPENSHIFT' }
  const none = { domain: 'c.example.com', platform: null }
  const blank = { domain: 'd.example.com', platform: '  ' }
  const missing = { domain: 'e.example.com' }

  it('null / boş / alanı olmayan platform "Belirtilmemiş" sayılır', () => {
    expect(platformKeyOf(none)).toBe(PLATFORM_NONE)
    expect(platformKeyOf(blank)).toBe(PLATFORM_NONE)
    expect(platformKeyOf(missing)).toBe(PLATFORM_NONE)
    expect(platformKeyOf(iis)).toBe('IIS')
  })

  it('boş seçim her kartı geçirir; seçim içinde VEYA', () => {
    for (const c of [iis, ocp, none]) expect(matchesPlatform(c, [])).toBe(true)
    expect(matchesPlatform(iis, ['IIS', 'OPENSHIFT'])).toBe(true)
    expect(matchesPlatform(ocp, ['IIS', 'OPENSHIFT'])).toBe(true)
    expect(matchesPlatform(none, ['IIS', 'OPENSHIFT'])).toBe(false)
  })

  it('"Belirtilmemiş" seçimi null/boş platformları yakalar, kodlu kartları almaz', () => {
    const all = [iis, ocp, none, blank, missing]
    expect(all.filter((c) => matchesPlatform(c, [PLATFORM_NONE])).map((c) => c.domain))
      .toEqual(['c.example.com', 'd.example.com', 'e.example.com'])
  })

  it('countPlatforms anahtar başına kart sayar', () => {
    const m = countPlatforms([iis, iis, ocp, none, missing])
    expect(m.get('IIS')).toBe(2)
    expect(m.get('OPENSHIFT')).toBe(1)
    expect(m.get(PLATFORM_NONE)).toBe(2)
  })
})

describe('platformFilter — seçenekler', () => {
  const catalog = [
    { code: 'IIS', name: 'IIS', description: 'Windows web sunucusu' },
    { code: 'OPENSHIFT', name: 'OpenShift' },
    { code: 'KUBERNETES', name: 'Kubernetes' },
  ]
  const certs = [
    { domain: 'a.example.com', platform: 'IIS' },
    { domain: 'b.example.com', platform: 'ZETA_OLD', platform_name: 'Zeta (pasif)' },
    { domain: 'c.example.com', platform: 'LEGACY_X' },
    { domain: 'd.example.com', platform: null },
  ]

  it('sıra: katalog (sunucu sırası) → veride olup katalogda olmayanlar (ada göre) → Belirtilmemiş en sonda', () => {
    const opts = buildPlatformOptions({ catalog, certs, counts: countPlatforms(certs), noneLabel: 'Not specified' })
    expect(opts.map((o) => o.value)).toEqual(['IIS', 'OPENSHIFT', 'KUBERNETES', 'LEGACY_X', 'ZETA_OLD', PLATFORM_NONE])
    expect(opts.map((o) => o.label)).toEqual(['IIS', 'OpenShift', 'Kubernetes', 'LEGACY_X', 'Zeta (pasif)', 'Not specified'])
  })

  it('sayılar verilen faset sayımından; hiç kartı olmayan katalog platformu 0 gösterir', () => {
    const opts = buildPlatformOptions({ catalog, certs, counts: countPlatforms(certs.slice(0, 1)), noneLabel: '-' })
    const byVal = Object.fromEntries(opts.map((o) => [o.value, o.count]))
    expect(byVal).toMatchObject({ IIS: 1, OPENSHIFT: 0, KUBERNETES: 0, LEGACY_X: 0, [PLATFORM_NONE]: 0 })
  })

  it('katalog açıklaması ipucu olur; URL\'den gelen bilinmeyen seçim listede kalır (kaldırılabilsin)', () => {
    const opts = buildPlatformOptions({ catalog, certs: [], selected: ['GONE', PLATFORM_NONE], noneLabel: '-' })
    expect(opts.find((o) => o.value === 'IIS').hint).toBe('Windows web sunucusu')
    expect(opts.map((o) => o.value)).toEqual(['IIS', 'OPENSHIFT', 'KUBERNETES', 'GONE', PLATFORM_NONE])
  })

  it('katalog okunamazsa (boş) seçenekler yalnız veriden türer', () => {
    const opts = buildPlatformOptions({ catalog: [], certs, counts: countPlatforms(certs), noneLabel: '-' })
    expect(opts.map((o) => o.value)).toEqual(['IIS', 'LEGACY_X', 'ZETA_OLD', PLATFORM_NONE])
  })
})
