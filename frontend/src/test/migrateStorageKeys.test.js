import { describe, it, expect, beforeEach } from 'vitest'
import { migrateStorageKeys } from '../utils/migrateStorageKeys.js'

/** Rename storage göçü: eski cert-monitor/cm.* anahtarları yeni site-monitor/sm.* adlarına
 *  kayıpsız taşınır; yeni anahtar doluysa üzerine yazılmaz; ikinci çağrı no-op (idempotent). */
describe('migrateStorageKeys', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('eski anahtarları yeni adlara taşır ve eskileri siler', () => {
    localStorage.setItem('cert-monitor-lang', 'tr')
    localStorage.setItem('cert-monitor-theme', 'dark')
    localStorage.setItem('cert-monitor-remembered-user', 'N12345')
    localStorage.setItem('cm.pageSize.keyword', '100')
    localStorage.setItem('cm.banner.dismissedVersion', '3')
    sessionStorage.setItem('cm.session.active', '1')

    migrateStorageKeys()

    expect(localStorage.getItem('site-monitor-lang')).toBe('tr')
    expect(localStorage.getItem('site-monitor-theme')).toBe('dark')
    expect(localStorage.getItem('site-monitor-remembered-user')).toBe('N12345')
    expect(localStorage.getItem('sm.pageSize.keyword')).toBe('100')
    expect(localStorage.getItem('sm.banner.dismissedVersion')).toBe('3')
    expect(sessionStorage.getItem('sm.session.active')).toBe('1')
    // eskiler temizlendi
    expect(localStorage.getItem('cert-monitor-lang')).toBeNull()
    expect(localStorage.getItem('cm.pageSize.keyword')).toBeNull()
    expect(sessionStorage.getItem('cm.session.active')).toBeNull()
  })

  it('yeni anahtar doluysa üzerine yazmaz (yeni tercih kazanır), eskiyi yine siler', () => {
    localStorage.setItem('cert-monitor-lang', 'en')
    localStorage.setItem('site-monitor-lang', 'tr')

    migrateStorageKeys()

    expect(localStorage.getItem('site-monitor-lang')).toBe('tr')
    expect(localStorage.getItem('cert-monitor-lang')).toBeNull()
  })

  it('idempotent: ikinci çağrı hiçbir değeri değiştirmez', () => {
    localStorage.setItem('cert-monitor-theme', 'light')
    migrateStorageKeys()
    migrateStorageKeys()
    expect(localStorage.getItem('site-monitor-theme')).toBe('light')
    expect(localStorage.getItem('cert-monitor-theme')).toBeNull()
  })

  it('eski anahtar yokken çalışmak güvenlidir (boş storage no-op)', () => {
    expect(() => migrateStorageKeys()).not.toThrow()
    expect(localStorage.length).toBe(0)
  })
})
