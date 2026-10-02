import { describe, it, expect } from 'vitest'
import { domainConflictOf, conflictActions, composeTransferRequest, JUSTIFICATION_MAX, DOMAIN_EXISTS } from '../components/inventory/domainConflictModel.js'
import { TR } from '../i18n/tr.js'

/**
 * Mükerrer alan adı deneyiminin SAF modeli (2026-09-28): 409 tanıma, eylem matrisi (görüntüle / geri yükle / aktar /
 * talep / yöneticiye yönlendir) ve Sorun Bildirimleri'ne düşen aktarım talebi iletisi.
 */
const tr = (k, ...a) => String(TR[k] ?? k).replace(/\{(\d+)\}/g, (_, i) => a[Number(i)])
const base = { domain: 'shop.example.com', inventory_id: 2, team_id: 9, team_name: 'Takım B', deleted: false,
  same_team: false, can_view: true, can_restore: false, can_transfer: false }

describe('domainConflictOf', () => {
  it('yalnız success=false + code=DOMAIN_EXISTS + existing nesnesi olan yanıtı tanır', () => {
    expect(domainConflictOf({ success: false, code: DOMAIN_EXISTS, error: 'x', existing: base }))
      .toEqual({ message: 'x', existing: base })
    expect(domainConflictOf({ success: false, status: 409, error: 'Bu domain envanterde zaten var.' })).toBeNull()
    expect(domainConflictOf({ success: false, code: DOMAIN_EXISTS })).toBeNull()
    expect(domainConflictOf({ success: true, code: DOMAIN_EXISTS, existing: base })).toBeNull()
    expect(domainConflictOf(null)).toBeNull()
  })
})

describe('conflictActions', () => {
  it('başka takımın kaydı, aktarım yetkisi YOK → görüntüle + aktarım TALEBİ; aktar yok', () => {
    expect(conflictActions(base, 5)).toMatchObject({ view: true, transfer: false, request: true, restore: false, sameTeam: false })
  })
  it('global yönetici (can_transfer) → aktar; talep yok; hedef takım seçilmemişse ikisi de yok', () => {
    expect(conflictActions({ ...base, can_transfer: true }, 5)).toMatchObject({ transfer: true, request: false })
    expect(conflictActions({ ...base, can_transfer: true }, '')).toMatchObject({ transfer: false, request: false })
  })
  it('aynı takım → ne aktar ne talep (mevcut kaydı düzenle); sunucu bayrağı ya da kimlik eşitliği', () => {
    expect(conflictActions({ ...base, same_team: true, can_transfer: true }, 9)).toMatchObject({ sameTeam: true, transfer: false, request: false })
    expect(conflictActions({ ...base, can_transfer: true }, '9')).toMatchObject({ sameTeam: true, transfer: false })
  })
  it('çöp kutusu: görüntüle yok; geri yükleme bayrağa bağlı; kendi takımında yetkisizse yöneticiye yönlendir', () => {
    const gone = { ...base, deleted: true, can_view: true }
    expect(conflictActions(gone, 5)).toMatchObject({ deleted: true, view: false, restore: false, request: true })
    expect(conflictActions({ ...gone, can_restore: true, same_team: true }, 9)).toMatchObject({ restore: true, askManager: false, request: false })
    expect(conflictActions({ ...gone, same_team: true }, 9)).toMatchObject({ restore: false, askManager: true })
    expect(conflictActions({ ...gone, can_transfer: true }, 5)).toMatchObject({ transfer: true, request: false })
  })
})

describe('composeTransferRequest', () => {
  it('yönetici tek bakışta işler: alan adı + kayıt no + mevcut / istenen ekip (#id) + çöp kutusu notu + gerekçe', () => {
    const msg = composeTransferRequest({ domain: 'shop.example.com', inventoryId: 2, fromTeam: { id: 9, name: 'Takım B' },
      toTeam: { id: 5, name: 'Takım A' }, deleted: true }, '  Uygulamanın sahibi artık biziz  ', tr)
    expect(msg.split('\n')).toEqual([
      'Alan adı aktarım talebi',
      'Alan adı: shop.example.com (envanter kaydı #2)',
      'Mevcut ekip: Takım B (#9)',
      'İstenen ekip: Takım A (#5)',
      'Durum: kayıt çöp kutusunda (geri yüklenmesi de gerekir)',
      '',
      'Gerekçe:',
      'Uygulamanın sahibi artık biziz',
    ])
    // Sunucu ileti sınırı (5000) gerekçe tavanıyla birlikte aşılmaz
    expect(composeTransferRequest({ domain: 'a'.repeat(253), inventoryId: 1, fromTeam: {}, toTeam: {} }, 'x'.repeat(JUSTIFICATION_MAX), tr).length)
      .toBeLessThan(5000)
  })
  it('ekibi atanmamış kayıt: "ekibi atanmamış" yazar, çöp kutusu satırı yok', () => {
    const msg = composeTransferRequest({ domain: 'a.example.com', fromTeam: { id: null, name: null }, toTeam: { id: 5, name: 'Takım A' } }, 'neden', tr)
    expect(msg).toContain('Mevcut ekip: ekibi atanmamış')
    expect(msg).not.toContain('çöp kutusunda')
  })
})
