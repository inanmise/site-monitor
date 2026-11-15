// Kayıtta 7/24 onayı (2026-11-15) — saf kurallar.
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addNoAsk, defaultGroupNames, isNoAsk, noAskKey, nocAvailable, NOC_NOASK_KEY, NOC_NOASK_MAX, readNoAsk, shouldAskNoc, withTimeout,
} from '../components/noc/forms/nocSaveConfirmModel.js'
import { LOCAL_PREF_KEYS } from '../hooks/userPrefsModel.js'

function memStore(init = {}) {
  const m = new Map(Object.entries(init))
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)), _m: m }
}

describe('nocSaveConfirmModel', () => {
  afterEach(() => vi.useRealTimers())

  it('noAskKey: tür + kimlik; kimliksiz (yeni kayıt) ya da bozuk kimlik → null', () => {
    expect(noAskKey('PING', 12)).toBe('PING:12')
    expect(noAskKey('ssl', '7')).toBe('SSL:7')
    expect(noAskKey('PING', null)).toBeNull()
    expect(noAskKey('PING', '')).toBeNull()
    expect(noAskKey('PING', 'x1')).toBeNull()
    expect(noAskKey(null, 3)).toBeNull()
  })

  it('liste: ekler, tekrar etmez, en yeniler kalır; bozuk değer boş liste', () => {
    const s = memStore()
    expect(readNoAsk(s)).toEqual([])
    addNoAsk(s, 'PING:1')
    addNoAsk(s, 'HTTP:2')
    addNoAsk(s, 'PING:1')
    expect(readNoAsk(s)).toEqual(['HTTP:2', 'PING:1'])
    expect(isNoAsk(s, 'PING:1')).toBe(true)
    expect(isNoAsk(s, 'DNS:9')).toBe(false)
    expect(isNoAsk(s, null)).toBe(false)
    expect(readNoAsk(memStore({ [NOC_NOASK_KEY]: '{bozuk' }))).toEqual([])
    expect(readNoAsk(memStore({ [NOC_NOASK_KEY]: '{"a":1}' }))).toEqual([])
    expect(readNoAsk(memStore({ [NOC_NOASK_KEY]: '["PING:1","<script>",5]' }))).toEqual(['PING:1'])
  })

  it(`liste tavanı ${NOC_NOASK_MAX} — en eskiler düşer; değer 16 KB altında kalır`, () => {
    const s = memStore()
    for (let i = 1; i <= NOC_NOASK_MAX + 25; i++) addNoAsk(s, `SCRIPTED:${100000000 + i}`)
    const list = readNoAsk(s)
    expect(list).toHaveLength(NOC_NOASK_MAX)
    expect(list[0]).toBe(`SCRIPTED:${100000000 + 26}`)
    expect(s.getItem(NOC_NOASK_KEY).length).toBeLessThan(16 * 1024)
  })

  it('yazılamayan depo sessizce geçer (gizli pencere)', () => {
    const s = { getItem: () => { throw new Error('x') }, setItem: () => { throw new Error('y') } }
    expect(readNoAsk(s)).toEqual([])
    expect(addNoAsk(s, 'PING:1')).toBe(false)
  })

  it('nocAvailable: tür kapalı → false; sunucu hükmü; grup listesi; bilinmiyor → null', () => {
    expect(nocAvailable(null, 'PING')).toBeNull()
    expect(nocAvailable({ groups: null, disabledTypes: null }, 'PING')).toBeNull()
    expect(nocAvailable({ disabledTypes: ['PING'], hasActiveGroup: true }, 'PING')).toBe(false)
    expect(nocAvailable({ disabledTypes: [], hasActiveGroup: false }, 'PING')).toBe(false)
    expect(nocAvailable({ hasActiveGroup: true }, 'PING')).toBe(true)
    expect(nocAvailable({ groups: [{ id: 1, active: false }] }, 'PING')).toBe(false)
    expect(nocAvailable({ groups: [{ id: 1, active: true }] }, 'PING')).toBe(true)
  })

  it('shouldAskNoc: yalnız kapalı ∧ kilitsiz ∧ kesin kullanılabilir ∧ "sorma" yok', () => {
    const base = { nocNotify: false, locked: false, available: true, noAsk: false }
    expect(shouldAskNoc(base)).toBe(true)
    expect(shouldAskNoc({ ...base, nocNotify: true })).toBe(false)
    expect(shouldAskNoc({ ...base, locked: true })).toBe(false)
    expect(shouldAskNoc({ ...base, available: null })).toBe(false)
    expect(shouldAskNoc({ ...base, available: false })).toBe(false)
    expect(shouldAskNoc({ ...base, noAsk: true })).toBe(false)
  })

  it('defaultGroupNames: aktif varsayılanlar, yoksa tüm aktifler', () => {
    const g = [{ id: 1, name: 'A', active: true, is_default: true }, { id: 2, name: 'B', active: true }, { id: 3, name: 'C', active: false, is_default: true }]
    expect(defaultGroupNames(g)).toEqual(['A'])
    expect(defaultGroupNames([{ id: 2, name: 'B', active: true }, { id: 4, name: 'D', active: true }])).toEqual(['B', 'D'])
    expect(defaultGroupNames(null)).toEqual([])
  })

  it('withTimeout: söz gelmezse süre dolunca yedek değer; hata yedek değer; zamanlayıcı temizlenir', async () => {
    vi.useFakeTimers()
    const hang = new Promise(() => {})
    const p = withTimeout(hang, 3000, 'yedek')
    await vi.advanceTimersByTimeAsync(3001)
    await expect(p).resolves.toBe('yedek')
    await expect(withTimeout(Promise.reject(new Error('x')), 3000, null)).resolves.toBeNull()
    await expect(withTimeout(Promise.resolve(5), 3000, null)).resolves.toBe(5)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('"bir daha sorma" anahtarı kullanıcı tercihleriyle eşitlenir (aynalanan anahtarlar listesinde)', () => {
    expect(LOCAL_PREF_KEYS).toContain(NOC_NOASK_KEY)
  })
})
