import { describe, it, expect } from 'vitest'
import { domainConflictOf, conflictActions, composeTransferRequest, JUSTIFICATION_MAX, DOMAIN_EXISTS } from '../components/inventory/domainConflictModel.js'
import { TR } from '../i18n/tr.js'

/**
 * Mükerrer alan adı deneyiminin SAF modeli (2026-09-28): 409 tanıma, eylem matrisi (görüntüle / aktar / talep) ve
 * Sorun Bildirimleri'ne düşen aktarım talebi iletisi. Silme KALICI (2026-10-07): çakışan kayıt her zaman CANLIDIR —
 * çöp kutusu dalı (geri yükle, "geri yükle ve aktar", yöneticiye yönlendir, talepteki çöp kutusu notu) kaldırıldı.
 */
const tr = (k, ...a) => String(TR[k] ?? k).replace(/\{(\d+)\}/g, (_, i) => a[Number(i)])
const base = { domain: 'shop.example.com', inventory_id: 2, team_id: 9, team_name: 'Takım B',
  same_team: false, can_view: true, can_transfer: false }

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
    expect(conflictActions(base, 5)).toMatchObject({ view: true, transfer: false, request: true, sameTeam: false })
  })
  it('global yönetici (can_transfer) → aktar; talep yok; hedef takım seçilmemişse ikisi de yok', () => {
    expect(conflictActions({ ...base, can_transfer: true }, 5)).toMatchObject({ transfer: true, request: false })
    expect(conflictActions({ ...base, can_transfer: true }, '')).toMatchObject({ transfer: false, request: false })
  })
  it('aynı takım → ne aktar ne talep (mevcut kaydı düzenle); sunucu bayrağı ya da kimlik eşitliği', () => {
    expect(conflictActions({ ...base, same_team: true, can_transfer: true }, 9)).toMatchObject({ sameTeam: true, transfer: false, request: false })
    expect(conflictActions({ ...base, can_transfer: true }, '9')).toMatchObject({ sameTeam: true, transfer: false })
  })
  it('çöp kutusu eylemleri YOK (silme kalıcı): restore / askManager / deleted anahtarları üretilmez', () => {
    const a = conflictActions({ ...base, deleted: true, can_restore: true }, 5)   // eski sunucu alanları yok sayılır
    expect(a).not.toHaveProperty('restore')
    expect(a).not.toHaveProperty('askManager')
    expect(a).not.toHaveProperty('deleted')
    expect(a).toMatchObject({ view: true, request: true })
  })
})

describe('composeTransferRequest', () => {
  it('yönetici tek bakışta işler: alan adı + kayıt no + mevcut / istenen ekip (#id) + gerekçe', () => {
    const msg = composeTransferRequest({ domain: 'shop.example.com', inventoryId: 2, fromTeam: { id: 9, name: 'Takım B' },
      toTeam: { id: 5, name: 'Takım A' } }, '  Uygulamanın sahibi artık biziz  ', tr)
    expect(msg.split('\n')).toEqual([
      'Alan adı aktarım talebi',
      'Alan adı: shop.example.com (envanter kaydı #2)',
      'Mevcut ekip: Takım B (#9)',
      'İstenen ekip: Takım A (#5)',
      '',
      'Gerekçe:',
      'Uygulamanın sahibi artık biziz',
    ])
    // Sunucu ileti sınırı (5000) gerekçe tavanıyla birlikte aşılmaz
    expect(composeTransferRequest({ domain: 'a'.repeat(253), inventoryId: 1, fromTeam: {}, toTeam: {} }, 'x'.repeat(JUSTIFICATION_MAX), tr).length)
      .toBeLessThan(5000)
  })
  it('ekibi atanmamış kayıt: "ekibi atanmamış" yazar; çöp kutusu satırı hiç yok', () => {
    const msg = composeTransferRequest({ domain: 'a.example.com', fromTeam: { id: null, name: null }, toTeam: { id: 5, name: 'Takım A' }, deleted: true }, 'neden', tr)
    expect(msg).toContain('Mevcut ekip: ekibi atanmamış')
    expect(msg).not.toContain('çöp kutusunda')
  })
})
