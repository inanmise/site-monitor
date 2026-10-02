import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  BEAT_MS, STALE_MS, TABS_KEY, beat, installLeaveBeacon, othersAlive, removeTab,
} from '../utils/tabPresence.js'

/**
 * "Ayrıldım" sinyali (2026-10-02): çevrimiçi sayımı o an açık olanları göstersin. Kullanıcının SON açık sekmesi kapanınca
 * beacon gider; başka TAZE sekme varsa gitmez; arka plan sekmesi (dakikada bir vuruş) 3 dk boyunca açık sayılır;
 * çıkış/kullanıcı değişimi (kaldırma) sinyal göndermez.
 */
let beacon

beforeEach(() => {
  localStorage.removeItem(TABS_KEY)
  beacon = vi.fn(() => true)
  Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true, writable: true })
})

afterEach(() => {
  vi.useRealTimers()
  localStorage.removeItem(TABS_KEY)
})

describe('tabPresence', () => {
  it('othersAlive: kendisi sayılmaz; 3 dk içindeki vuruş canlı, daha eskisi bayat', () => {
    const now = 1_000_000_000
    expect(othersAlive({ me: now }, 'me', now)).toBe(false)
    expect(othersAlive({ me: now, other: now - STALE_MS + 1 }, 'me', now)).toBe(true)
    expect(othersAlive({ me: now, other: now - STALE_MS - 1 }, 'me', now)).toBe(false)
  })

  it('beat/removeTab: kayıt tutulur, 12 sa\'ten eskisi temizlenir', () => {
    const now = Date.now()
    localStorage.setItem(TABS_KEY, JSON.stringify({ ancient: now - 13 * 3600_000 }))
    beat('a', now)
    expect(Object.keys(JSON.parse(localStorage.getItem(TABS_KEY)))).toEqual(['a'])
    expect(removeTab('a')).toEqual({})
  })

  it('tek sekme kapanınca (pagehide) /api/session/leave beacon\'ı gider', () => {
    const off = installLeaveBeacon()
    window.dispatchEvent(new Event('pagehide'))
    expect(beacon).toHaveBeenCalledTimes(1)
    expect(String(beacon.mock.calls[0][0])).toMatch(/\/api\/session\/leave$/)
    off()
  })

  it('başka taze sekme açıkken kapanış sinyal GÖNDERMEZ', () => {
    localStorage.setItem(TABS_KEY, JSON.stringify({ otherTab: Date.now() }))
    const off = installLeaveBeacon()
    window.dispatchEvent(new Event('pagehide'))
    expect(beacon).not.toHaveBeenCalled()
    off()
  })

  it('öteki sekme bayatsa (çökmüş) kapanış sinyal gönderir', () => {
    localStorage.setItem(TABS_KEY, JSON.stringify({ crashed: Date.now() - STALE_MS - 5_000 }))
    const off = installLeaveBeacon()
    window.dispatchEvent(new Event('pagehide'))
    expect(beacon).toHaveBeenCalledTimes(1)
    off()
  })

  it('arka plan sekmesi dakikada bir vurur → açık sayılmaya devam eder', () => {
    vi.useFakeTimers()
    const off = installLeaveBeacon()
    const id = Object.keys(JSON.parse(localStorage.getItem(TABS_KEY)))[0]
    vi.advanceTimersByTime(BEAT_MS * 2)
    const stamp = JSON.parse(localStorage.getItem(TABS_KEY))[id]
    expect(Date.now() - stamp).toBeLessThan(BEAT_MS)
    off()
  })

  it('kaldırma (çıkış / kullanıcı değişimi) sinyal göndermez, kaydı temizler', () => {
    const off = installLeaveBeacon()
    off()
    expect(beacon).not.toHaveBeenCalled()
    expect(JSON.parse(localStorage.getItem(TABS_KEY))).toEqual({})
    window.dispatchEvent(new Event('pagehide'))   // dinleyici kaldırıldı
    expect(beacon).not.toHaveBeenCalled()
  })
})
