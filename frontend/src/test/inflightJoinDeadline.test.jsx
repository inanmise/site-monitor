import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

/**
 * "Süren isteğe katıl" önbelleklerinin ikinci savunma hattı (2026-11-15, kullanıcı kuralı "zaman aşımı olmayan istek /
 * bekleme yok"): istemci her isteği süre sınırıyla bitirir, ama bir hata yüzünden HİÇ sonuçlanmayan bir söz modül düzeyindeki
 * ortak isteği sekmenin ömrü boyunca kilitlerdi (çevrimiçi sayısı, Panom kutuları, bakım rozeti, 7/24 durumu). Artık
 * INFLIGHT_JOIN_MAX_MS'den (2 × 90 sn) yaşlı süren isteğe katılınmaz; yeni istek başlar, geç gelen eski yanıt yazılmaz.
 */
const mocks = vi.hoisted(() => ({ online: vi.fn(), active: vi.fn() }))
vi.mock('../api/client.js', () => ({ api: { presence: { online: mocks.online }, monitoring: { maintenance: { active: mocks.active } } } }))
vi.mock('../api/client', () => ({ api: { presence: { online: mocks.online }, monitoring: { maintenance: { active: mocks.active } } } }))
const noc = vi.hoisted(() => ({ load: vi.fn() }))
vi.mock('../components/noc/forms/useNocFormOptions.js', () => ({ loadNocFormOptions: () => noc.load(), resetNocFormOptionsCache: () => {} }))

import { INFLIGHT_JOIN_MAX_MS } from '../utils/timedFetch.js'
import { refreshPresence, resetPresenceForTests } from '../hooks/usePresence.js'
import { clearSharedFetches, sharedFetch } from '../utils/sharedFetch.js'
import { refreshNocState, resetNocStateForTests } from '../components/noc/useNocState.js'

const never = () => new Promise(() => {})

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-11-15T09:00:00Z'))
  mocks.online.mockReset(); mocks.active.mockReset(); noc.load.mockReset()
  resetPresenceForTests()
  clearSharedFetches()
  resetNocStateForTests()
})
afterEach(() => { vi.useRealTimers() })

describe('yaşlı süren isteğe katılınmaz', () => {
  it('INFLIGHT_JOIN_MAX_MS okuma süresinin (90 sn) iki katı', () => {
    expect(INFLIGHT_JOIN_MAX_MS).toBe(180_000)
  })

  it('çevrimiçi sayısı: asılı ilk istek, son tarihten sonraki yoklamayı bekletmez', async () => {
    mocks.online.mockReturnValueOnce(never())
    const first = refreshPresence()
    expect(refreshPresence()).toBe(first)                          // taze: katılır, ikinci istek yok
    expect(mocks.online).toHaveBeenCalledTimes(1)
    vi.setSystemTime(Date.now() + INFLIGHT_JOIN_MAX_MS + 1000)
    mocks.online.mockResolvedValueOnce({ success: true, data: { total: 3, teams: [] } })
    const second = refreshPresence()
    expect(second).not.toBe(first)
    expect(mocks.online).toHaveBeenCalledTimes(2)
    await second
    // Bitti: süren istek bırakıldı — sonraki yoklama yeniden ister (asılı eski söz hâlâ bekliyor olsa da)
    mocks.online.mockResolvedValueOnce({ success: true, data: { total: 4, teams: [] } })
    await refreshPresence()
    expect(mocks.online).toHaveBeenCalledTimes(3)
  })

  it('Panom ortak isteği: asılı istek son tarihten sonra yenisine yol verir', async () => {
    const f = vi.fn(() => never())
    const a = sharedFetch(f)
    expect(sharedFetch(f)).toBe(a)
    expect(f).toHaveBeenCalledTimes(1)
    vi.setSystemTime(Date.now() + INFLIGHT_JOIN_MAX_MS + 1000)
    f.mockResolvedValueOnce({ success: true, data: 'taze' })
    expect(await sharedFetch(f)).toEqual({ success: true, data: 'taze' })
    expect(f).toHaveBeenCalledTimes(2)
  })

  it('7/24 durumu: asılı yükleme son tarihten sonra yeniden çekilir; geç gelen eski yanıt yazılmaz', async () => {
    let lateResolve
    noc.load.mockReturnValueOnce(new Promise((r) => { lateResolve = r }))
    const first = refreshNocState(true)
    expect(refreshNocState(true)).toBe(first)
    await new Promise((r) => setTimeout(r, 0))                     // yükleme bir mikro görevden sonra çağrılır
    expect(noc.load).toHaveBeenCalledTimes(1)
    vi.setSystemTime(Date.now() + INFLIGHT_JOIN_MAX_MS + 1000)
    noc.load.mockResolvedValueOnce({ groups: [{ id: 2 }] })
    const snap = await refreshNocState(true)
    expect(noc.load).toHaveBeenCalledTimes(2)
    expect(snap.status).toBe('ready')
    expect(snap.data).toEqual({ groups: [{ id: 2 }] })
    lateResolve({ groups: [{ id: 1 }] })                           // eski yanıt geç geldi
    const after = await first
    expect(after.data).toEqual({ groups: [{ id: 2 }] })
  })
})
