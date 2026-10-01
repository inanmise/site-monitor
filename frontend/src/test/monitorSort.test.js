import { describe, it, expect } from 'vitest'
import { sortMonitorsDefault, tierOf, TIER_DOWN, TIER_WARN, TIER_REST, STATUS_TIER } from '../utils/monitorSort.js'

const ids = (list) => list.map((m) => m.id)

describe('İzleme türü sayfaları — varsayılan kart sırası (2026-10-01)', () => {
  it('sorunlu önce; sonra grup adı A→Z (aynı grup art arda, grup içinde ad A→Z); grupsuzlar en sonda ada göre', () => {
    const list = [
      { id: 1, name: 'zeta', status: 'up' },                                   // grupsuz
      { id: 2, name: 'alfa', status: 'up', group_name: 'Ödeme' },
      { id: 3, name: 'beta', status: 'down', group_name: 'Kart' },              // sorunlu
      { id: 4, name: 'ali', status: 'up' },                                    // grupsuz
      { id: 5, name: 'çam', status: 'up', group_name: 'Kart' },
      { id: 6, name: 'bal', status: 'up', group_name: 'Kart' },
      { id: 7, name: 'aaa', status: 'down' },                                  // sorunlu, grupsuz
      { id: 8, name: 'ece', status: 'up', group_name: 'çağrı' },
    ]
    // sorunlular (grubu olan önce: Kart/beta, sonra grupsuz aaa) → çağrı (ece) → Kart (bal, çam) → Ödeme (alfa) → grupsuz (ali, zeta)
    // Türkçe alfabe: ç, c'den sonra k'dan önce; ö, o'dan sonra.
    expect(ids(sortMonitorsDefault(list, 'http'))).toEqual([3, 7, 8, 6, 5, 2, 4, 1])
  })

  it('Türkçe harf düzeni ve büyük/küçük harf duyarsız; sayılar doğal sırada; eşitlikte kimlik', () => {
    const list = [
      { id: 3, name: 'web-10', status: 'up' }, { id: 1, name: 'Web-2', status: 'up' },
      { id: 2, name: 'şube', status: 'up' }, { id: 4, name: 'Sube', status: 'up' }, { id: 5, name: 'web-2', status: 'up' },
    ]
    expect(ids(sortMonitorsDefault(list, 'ping'))).toEqual([4, 2, 1, 5, 3])
  })

  it('boş / boşluk grup adı grupsuz sayılır; girdi dizisi değişmez', () => {
    const list = [{ id: 1, name: 'b', status: 'up', group_name: '  ' }, { id: 2, name: 'a', status: 'up', group_name: 'G' }]
    const copy = [...list]
    expect(ids(sortMonitorsDefault(list, 'http'))).toEqual([2, 1])
    expect(list).toEqual(copy)
  })

  it('duraklatılmış izleme sorunlu sayılmaz (son durumu kırmızı olsa bile)', () => {
    expect(tierOf({ status: 'down', active: false }, 'http')).toBe(TIER_REST)
    expect(tierOf({ active_alarm: true, active: false }, 'dns')).toBe(TIER_REST)
  })

  it('her türün "sorunlu" eşlemesi kartın statusKey\'i ile aynı (kırmızı → 0, sarı → 1)', () => {
    const cases = [
      ['http', { status: 'down' }, TIER_DOWN], ['http', { status: 'error' }, TIER_DOWN], ['http', { status: 'up' }, TIER_REST], ['http', { status: 'unknown' }, TIER_REST],
      ['keyword', { status: 'down' }, TIER_DOWN], ['keyword', { status: 'up' }, TIER_REST],
      ['ping', { status: 'down' }, TIER_DOWN], ['ping', { status: 'unknown' }, TIER_REST],
      ['port', { status: 'closed' }, TIER_DOWN], ['port', { status: 'open' }, TIER_REST],
      ['dns', { active_alarm: true }, TIER_DOWN], ['dns', { active_alarm: false }, TIER_REST],
      ['domain', { status: 'CRITICAL' }, TIER_DOWN], ['domain', { status: 'WARNING' }, TIER_WARN], ['domain', { status: 'OK' }, TIER_REST],
      ['page', { status: 'DOWN' }, TIER_DOWN], ['page', { status: 'DEGRADED' }, TIER_WARN], ['page', { status: 'OK' }, TIER_REST],
      ['pagespeed', { status: 'DOWN' }, TIER_DOWN], ['pagespeed', { status: 'SLOW' }, TIER_WARN], ['pagespeed', { status: 'OK' }, TIER_REST],
      ['scripted', { status: 'FAIL' }, TIER_DOWN], ['scripted', { status: 'TIMEOUT' }, TIER_DOWN], ['scripted', { status: 'NO_CHECKS' }, TIER_WARN], ['scripted', { status: 'PASS' }, TIER_REST],
    ]
    for (const [type, m, tier] of cases) expect(tierOf(m, type), `${type} ${JSON.stringify(m)}`).toBe(tier)
    expect(Object.keys(STATUS_TIER).sort()).toEqual(['dns', 'domain', 'http', 'keyword', 'page', 'pagespeed', 'ping', 'port', 'scripted'])
  })

  it('sarı (warn) kartlar kırmızılardan sonra, sağlıklılardan önce', () => {
    const list = [{ id: 1, name: 'a', status: 'OK' }, { id: 2, name: 'b', status: 'SLOW' }, { id: 3, name: 'c', status: 'DOWN' }]
    expect(ids(sortMonitorsDefault(list, 'pagespeed'))).toEqual([3, 2, 1])
  })

  it('Alan Adı: ad yoksa alan adına göre sıralanır', () => {
    const list = [{ id: 1, domain: 'zz.example.com', status: 'OK' }, { id: 2, domain: 'aa.example.com', status: 'OK' }]
    expect(ids(sortMonitorsDefault(list, 'domain'))).toEqual([2, 1])
  })
})
