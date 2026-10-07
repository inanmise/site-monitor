import { describe, it, expect, beforeEach } from 'vitest'
import {
  DELETED_TTL_MS, markDeleted, markManyDeleted, unmarkDeleted, isRecentlyDeleted, filterDeleted, countDeleted,
  monitorKind, markMonitorDeleted, subscribeDeleted, getDeletedVersion, __resetDeletedMarks,
} from '../utils/recentlyDeleted.js'

/**
 * "Yakın zamanda silindi" deposu (2026-10-07) — silinen kart tam liste yüklemesini beklemeden düşer ve başka pod'un bayat
 * önbelleğinden gelen yanıt onu TTL boyunca geri getiremez; aynı ad yeniden eklenince işaret kalkar.
 */
describe('recentlyDeleted deposu', () => {
  beforeEach(() => __resetDeletedMarks())

  const certs = [{ domain: 'a.example.com' }, { domain: 'B.Example.com' }, { domain: 'c.example.com' }]
  const byDomain = (c) => c.domain

  it('işaretlenen kayıt süzülür (harf duyarsız); hiçbir şey düşmezse AYNI dizi döner', () => {
    expect(filterDeleted('cert', certs, byDomain)).toBe(certs)   // işaret yok → kimlik korunur
    markDeleted('cert', 'b.example.com')
    expect(filterDeleted('cert', certs, byDomain).map(byDomain)).toEqual(['a.example.com', 'c.example.com'])
    expect(isRecentlyDeleted('cert', 'B.EXAMPLE.COM')).toBe(true)
    expect(countDeleted('cert', certs, byDomain)).toBe(1)
    // Başka türün işareti bu listeye dokunmaz
    expect(filterDeleted(monitorKind('http'), certs, byDomain)).toBe(certs)
  })

  it('TTL dolunca işaret düşer (sunucu artık doğruyu söyler)', () => {
    const t0 = 1_000_000
    markDeleted('cert', 'a.example.com', DELETED_TTL_MS, t0)
    expect(isRecentlyDeleted('cert', 'a.example.com', t0 + DELETED_TTL_MS - 1)).toBe(true)
    expect(isRecentlyDeleted('cert', 'a.example.com', t0 + DELETED_TTL_MS + 1)).toBe(false)
    markDeleted('cert', 'c.example.com', 5_000, t0)
    expect(filterDeleted('cert', certs, byDomain, t0 + 1).map(byDomain)).toEqual(['a.example.com', 'B.Example.com'])
    expect(filterDeleted('cert', certs, byDomain, t0 + 10_000)).toBe(certs)
    expect(DELETED_TTL_MS).toBeGreaterThanOrEqual(5 * 60 * 1000)   // sunucu önbelleklerinin (≤ 5 dk) üstünde
  })

  it('aynı ad yeniden eklenince unmark → kayıt hemen görünür', () => {
    markDeleted('cert', 'a.example.com')
    expect(filterDeleted('cert', certs, byDomain)).toHaveLength(2)
    unmarkDeleted('cert', 'A.example.com')
    expect(filterDeleted('cert', certs, byDomain)).toBe(certs)
  })

  it('toplu işaret + abonelik: dinleyici uyarılır, sürüm artar; boş anahtar yok sayılır', () => {
    let hits = 0
    const off = subscribeDeleted(() => { hits++ })
    const v0 = getDeletedVersion()
    markManyDeleted('cert', ['a.example.com', '', null, 'c.example.com'])
    expect(hits).toBe(1)
    expect(getDeletedVersion()).toBeGreaterThan(v0)
    expect(filterDeleted('cert', certs, byDomain).map(byDomain)).toEqual(['B.Example.com'])
    markDeleted('cert', '   ')   // boş → no-op
    expect(hits).toBe(1)
    off()
    markDeleted('cert', 'b.example.com')
    expect(hits).toBe(1)
  })

  it('izleme silme yanıtı: kalıcıysa işaretlenir; Port/DNS türev satırda (permanent:false) duraklatma — işaretlenmez', () => {
    const rows = [{ id: 1 }, { id: 2 }, { id: 3 }]
    expect(markMonitorDeleted('PORT', 1, { success: true, data: { deleted: true, permanent: true } })).toBe(true)
    expect(markMonitorDeleted('port', 2, { success: true, data: { deleted: true, permanent: false } })).toBe(false)
    expect(markMonitorDeleted('http', 3, { success: true, data: { deleted: true } })).toBe(true)   // diğer yedi tür: alan yok → kalıcı
    expect(filterDeleted(monitorKind('port'), rows, (m) => m.id).map((m) => m.id)).toEqual([2, 3])
    expect(filterDeleted(monitorKind('HTTP'), rows, (m) => m.id).map((m) => m.id)).toEqual([1, 2])
    expect(monitorKind('PORT')).toBe(monitorKind('port'))
  })

  it('dizi olmayan girdi olduğu gibi döner', () => {
    markDeleted('cert', 'x')
    expect(filterDeleted('cert', null, byDomain)).toBeNull()
    expect(countDeleted('cert', undefined, byDomain)).toBe(0)
  })
})
