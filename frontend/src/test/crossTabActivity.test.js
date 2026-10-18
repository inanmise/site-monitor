import { describe, it, expect, vi, beforeEach } from 'vitest'
import {
  ACTIVITY_EVENT, ACTIVITY_PREFIX, ACTIVITY_THROTTLE_MS, latestOtherActivity, markActivity, onOtherActivity, ownActivityKey,
  resetActivityThrottleForTests, writeActivityStamp,
} from '../utils/crossTabActivity.js'

// Sekmeler arası etkinlik damgası (2026-10-18, Görsel paneli): her sekme kendi anahtarına en çok 15 sn'de bir yazar;
// öteki sekmenin damgası okunurken kısma payı eklenir (erken çıkış yok), "şimdi"yi geçmez; kendi damgası sayılmaz.
beforeEach(() => {
  localStorage.clear()
  resetActivityThrottleForTests()
})

describe('sekmeler arası etkinlik', () => {
  it('kendi damgası 15 sn kısılır ve öteki-sekme okumasına girmez (tek sekmede davranış aynı)', () => {
    const t0 = 1_000_000
    expect(writeActivityStamp(t0)).toBe(true)
    expect(localStorage.getItem(ownActivityKey())).toBe(String(t0))
    expect(writeActivityStamp(t0 + 5000)).toBe(false)
    expect(localStorage.getItem(ownActivityKey())).toBe(String(t0))
    expect(writeActivityStamp(t0 + ACTIVITY_THROTTLE_MS)).toBe(true)
    expect(latestOtherActivity(t0 + 20_000)).toBe(0)
  })

  it('öteki sekmelerin en yenisi + kısma payı; şimdiyi geçmez', () => {
    const now = 5_000_000
    localStorage.setItem(`${ACTIVITY_PREFIX}panel`, String(now - 60_000))
    localStorage.setItem(`${ACTIVITY_PREFIX}diger`, String(now - 20_000))
    expect(latestOtherActivity(now)).toBe(now - 20_000 + ACTIVITY_THROTTLE_MS)
    localStorage.setItem(`${ACTIVITY_PREFIX}panel`, String(now - 2000))
    expect(latestOtherActivity(now)).toBe(now)
    localStorage.setItem(`${ACTIVITY_PREFIX}bozuk`, 'x')
    expect(latestOtherActivity(now)).toBe(now)
  })

  it('yazarken bir günden eski / bozuk damgalar temizlenir', () => {
    const now = 9_000_000_000
    localStorage.setItem(`${ACTIVITY_PREFIX}eski`, String(now - 25 * 3600_000))
    localStorage.setItem(`${ACTIVITY_PREFIX}bozuk`, 'abc')
    localStorage.setItem(`${ACTIVITY_PREFIX}taze`, String(now - 1000))
    localStorage.setItem('baska.anahtar', '1')
    writeActivityStamp(now, true)
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}eski`)).toBeNull()
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}bozuk`)).toBeNull()
    expect(localStorage.getItem(`${ACTIVITY_PREFIX}taze`)).not.toBeNull()
    expect(localStorage.getItem('baska.anahtar')).toBe('1')
  })

  it('onOtherActivity: yalnız öteki sekmenin damga olayı (storage); kendi anahtarı ve başka anahtar sayılmaz', () => {
    const cb = vi.fn()
    const off = onOtherActivity(cb)
    window.dispatchEvent(new StorageEvent('storage', { key: `${ACTIVITY_PREFIX}panel`, newValue: '123' }))
    window.dispatchEvent(new StorageEvent('storage', { key: ownActivityKey(), newValue: '124' }))
    window.dispatchEvent(new StorageEvent('storage', { key: 'sm.prefs.rev', newValue: '125' }))
    window.dispatchEvent(new StorageEvent('storage', { key: `${ACTIVITY_PREFIX}panel`, newValue: null }))
    expect(cb).toHaveBeenCalledTimes(1)
    expect(cb).toHaveBeenCalledWith(123)
    off()
    window.dispatchEvent(new StorageEvent('storage', { key: `${ACTIVITY_PREFIX}panel`, newValue: '126' }))
    expect(cb).toHaveBeenCalledTimes(1)
  })

  it('markActivity kendi sekmesine olay yayar (uygulama içi eylem: ekrandan alma, yapıştırma, yükleme)', () => {
    const h = vi.fn()
    window.addEventListener(ACTIVITY_EVENT, h)
    markActivity()
    window.removeEventListener(ACTIVITY_EVENT, h)
    expect(h).toHaveBeenCalledTimes(1)
  })
})
