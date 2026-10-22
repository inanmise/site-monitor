import { describe, it, expect } from 'vitest'
import { canCheckCertRow, inCheckScope } from '../utils/certCheckScope.js'

/**
 * Sertifika "Şimdi kontrol et" kapısı (2026-10-22, hata avı FE-1) — sunucunun `/check/{domain}` kuralının
 * (`CertificateController.requireViewableDomain`: SY ya da UG görüş kapsamında, ya da SY / UG'de EXECUTE+ paylaşımı) aynası.
 * Sunucunun kabul edeceği hiçbir satırda düğme gizlenmez; satır sahip takımları taşımıyorsa 20.165.0 kuralı.
 */
const row = (team_id, owners) => ({ domain: 'x.example.com', team_id, ...(owners ? { owner_team_ids: owners } : {}) })

describe('canCheckCertRow', () => {
  it('global görüntüleyici her satırı kontrol eder', () => {
    expect(canCheckCertRow(row(9, [9]), { globalViewer: true })).toBe(true)
    expect(canCheckCertRow(row(9, []), { globalViewer: true })).toBe(true)
  })

  it.each([
    ['SY görüş kapsamında', row(1, [1]), { viewTeamIds: [1] }, true],
    ['UG görüş kapsamında (kullanıcının takımı UG)', row(2, [2, 1]), { viewTeamIds: [1] }, true],
    ['müdürün astının takımı (view_team_ids)', row(3, [3]), { viewTeamIds: [1, 3], memberTeamIds: [1] }, true],
    ['SY EXECUTE paylaşımı', row(4, [4]), { viewTeamIds: [1], grantLevels: { 4: 'EXECUTE' } }, true],
    ['UG EDIT paylaşımı', row(4, [4, 5]), { viewTeamIds: [1], grantLevels: { 5: 'EDIT' } }, true],
    ['yalnız VIEW paylaşımı', row(4, [4, 5]), { viewTeamIds: [1], grantLevels: { 4: 'VIEW', 5: 'VIEW' } }, false],
    ['hiçbir yol yok', row(6, [6, 7]), { viewTeamIds: [1] }, false],
    ['sahipsiz kayıt (boş liste)', row(null, []), { viewTeamIds: [1] }, false],
  ])('sahip takımlar biliniyor: %s', (_n, cert, ctx, expected) => {
    expect(canCheckCertRow(cert, ctx)).toBe(expected)
  })

  it('view_team_ids yoksa (eski sunucu) görüş kapsamı üyelik ∪ yönetim kapsamından türetilir; ikisi de bilinmiyorsa yönetici rolünde düğme görünür', () => {
    expect(canCheckCertRow(row(2, [2, 1]), { memberTeamIds: [1] })).toBe(true)
    expect(canCheckCertRow(row(3, [3]), { memberTeamIds: [1], manageTeamIds: [3], systemRole: 'TEAM_ADMIN' })).toBe(true)
    expect(canCheckCertRow(row(3, [3]), { memberTeamIds: [1], manageTeamIds: [], systemRole: 'USER' })).toBe(false)
    expect(canCheckCertRow(row(3, [3]), { memberTeamIds: [1], manageTeamIds: null, systemRole: 'USER' })).toBe(false)
    expect(canCheckCertRow(row(3, [3]), { memberTeamIds: [1], manageTeamIds: null, systemRole: 'ADMIN' })).toBe(true)
  })

  it('satır sahip takımları taşımıyorsa 20.165.0 kuralı: paylaşımla görülen kartta EXECUTE düzeyi, aksi hâlde görünür', () => {
    expect(canCheckCertRow(row(2), { viewTeamIds: [1], memberTeamIds: [1] })).toBe(true)          // 7/24 operatörü, UG bilinmiyor
    expect(canCheckCertRow({ domain: 'y' }, { viewTeamIds: [1] })).toBe(true)                    // pano dışı pencere {domain}
    expect(canCheckCertRow(row(4), { memberTeamIds: [1], grantLevels: { 4: 'VIEW' } })).toBe(false)
    expect(canCheckCertRow(row(4), { memberTeamIds: [1], grantLevels: { 4: 'EXECUTE' } })).toBe(true)
  })
})

describe('inCheckScope', () => {
  it('view_team_ids varsa yalnız o; kimlikler metin/sayı fark etmez', () => {
    expect(inCheckScope('3', { viewTeamIds: [3] })).toBe(true)
    expect(inCheckScope(3, { viewTeamIds: ['1'], memberTeamIds: [3] })).toBe(false)
    expect(inCheckScope(null, { viewTeamIds: [1] })).toBe(false)
  })
})
