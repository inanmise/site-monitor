import { describe, it, expect } from 'vitest'
import {
  NOC_STATUS, nocCoverageTarget, nocEditTarget, nocStatusOf, targetGroupNames, usableNocState,
} from '../components/noc/nocStatusModel.js'

/**
 * 7/24 durum göstergesinin SAF modeli (2026-09-28). Tek kaynak sunucunun kapsam kuralıdır (NocCoverageService.reason +
 * NocGroupService.resolveTargets); model yalnız paylaşılan durum (useNocState) GERÇEKTEN geldiyse "iletilmiyor" der.
 */
const G = (id, name, { def = false, active = true } = {}) => ({ id, name, is_default: def, active })
const GROUPS = [G(1, 'NOC Ana', { def: true }), G(2, 'Hafta Sonu'), G(3, 'Eski Liste', { active: false })]
/** Paylaşılan durum — `loadNocFormOptions` biçimi (camelCase: istemci içi). */
const ready = (over = {}) => ({
  status: 'ready',
  data: { groups: GROUPS, disabledTypes: [], hasActiveGroup: true, minLevel: 'CRITICAL', ...over },
})

describe('nocStatusOf — üç durum', () => {
  it('açık + tür açık + kullanılabilir grup → on (doğrulanmış), seviye ve alıcı gruplar', () => {
    const v = nocStatusOf({ notify: true, type: 'HTTP', groupIds: [] }, ready())
    expect(v).toMatchObject({ state: NOC_STATUS.ON, reason: null, verified: true, minLevel: 'CRITICAL', groups: ['NOC Ana'], paused: false })
  })

  it('kapalı → off (paylaşılan durum ne olursa olsun; hata/yükleniyor da)', () => {
    for (const noc of [ready(), null, { status: 'error', data: null }, { status: 'idle', data: null }]) {
      expect(nocStatusOf({ notify: false, type: 'PING' }, noc)).toMatchObject({ state: 'off', reason: null })
    }
  })

  it('tür Ayarlar\'da kapalı → blocked TYPE_DISABLED (başka türü etkilemez)', () => {
    const noc = ready({ disabledTypes: ['PING'] })
    expect(nocStatusOf({ notify: true, type: 'PING' }, noc)).toMatchObject({ state: 'blocked', reason: 'TYPE_DISABLED', verified: true })
    expect(nocStatusOf({ notify: true, type: 'HTTP' }, noc)).toMatchObject({ state: 'on', verified: true })
  })

  it('kullanılabilir grup yok (sunucu hükmü) → blocked NO_ACTIVE_GROUP; tür kapalıysa neden önceliği TYPE_DISABLED (sunucu sırası)', () => {
    expect(nocStatusOf({ notify: true, type: 'DNS' }, ready({ hasActiveGroup: false }))).toMatchObject({ state: 'blocked', reason: 'NO_ACTIVE_GROUP' })
    expect(nocStatusOf({ notify: true, type: 'DNS' }, ready({ hasActiveGroup: false, disabledTypes: ['DNS'] })))
      .toMatchObject({ state: 'blocked', reason: 'TYPE_DISABLED' })
  })

  it('sunucu "aktif grup yok" derse `active` bayraklı grup listesi hükmü DEĞİŞTİRMEZ (aktif ama adressiz grup)', () => {
    expect(nocStatusOf({ notify: true, type: 'HTTP' }, ready({ hasActiveGroup: false, groups: [G(9, 'Boş', { def: true })] })))
      .toMatchObject({ state: 'blocked', reason: 'NO_ACTIVE_GROUP' })
  })

  it('seçilen grupların HEPSİ pasif → engel DEĞİL: sunucu varsayılanlara düşer, alıcı gruplar varsayılanlar', () => {
    const v = nocStatusOf({ notify: true, type: 'HTTP', groupIds: [3] }, ready())
    expect(v).toMatchObject({ state: 'on', verified: true, groups: ['NOC Ana'] })
  })
})

describe('nocStatusOf — geri düşüş (durum okunamadı / eksik)', () => {
  it('durum yok / hata / henüz gelmedi → iki durumlu: açık (doğrulanmamış), "iletilmiyor" İDDİASI YOK, grup yok', () => {
    for (const noc of [null, { status: 'error', data: null }, { status: 'idle', data: null }]) {
      expect(nocStatusOf({ notify: true, type: 'PING', groupIds: [] }, noc))
        .toEqual({ state: 'on', reason: null, verified: false, groups: [], minLevel: null, paused: false })
    }
  })

  it('tür listesi bilinmiyorsa (eksik yanıt) tür engeli iddia edilmez ve "etkin" de doğrulanmaz', () => {
    const v = nocStatusOf({ notify: true, type: 'PING', groupIds: [] }, ready({ disabledTypes: null }))
    expect(v).toMatchObject({ state: 'on', verified: false, groups: [] })
  })

  it('eski sunucu has_active_group göndermezse grup listesinden çıkarılır (aktif grup var → açık; hiç yok → iletilmiyor)', () => {
    expect(nocStatusOf({ notify: true, type: 'HTTP' }, ready({ hasActiveGroup: null }))).toMatchObject({ state: 'on', verified: true })
    expect(nocStatusOf({ notify: true, type: 'HTTP' }, ready({ hasActiveGroup: null, groups: [G(3, 'Pasif', { active: false })] })))
      .toMatchObject({ state: 'blocked', reason: 'NO_ACTIVE_GROUP' })
  })

  it('noc_notify boolean değilse (alanı taşımayan satır) null — "bilinmiyor" hiçbir zaman "kapalı" gösterilmez', () => {
    for (const notify of [undefined, null, 'true', 1]) expect(nocStatusOf({ notify, type: 'SSL' }, ready())).toBeNull()
  })

  it('bilinmeyen seviye yazılmaz (minLevel null)', () => {
    expect(nocStatusOf({ notify: true, type: 'HTTP', groupIds: [] }, ready({ minLevel: 'LOUD' }))).toMatchObject({ minLevel: null })
  })

  it('duraklatılmış izleme durumu DEĞİŞTİRMEZ; yalnız not düşer', () => {
    expect(nocStatusOf({ notify: true, type: 'HTTP', active: false, groupIds: [] }, ready())).toMatchObject({ state: 'on', paused: true })
    expect(nocStatusOf({ notify: false, type: 'HTTP', active: false }, ready())).toMatchObject({ state: 'off', paused: true })
  })

  it('satır noc_group_ids taşımıyorsa (sertifika kartı) alıcı grup YAZILMAZ; `[]` taşıyorsa varsayılanlar yazılır', () => {
    expect(nocStatusOf({ notify: true, type: 'SSL' }, ready()).groups).toEqual([])
    expect(nocStatusOf({ notify: true, type: 'SSL', groupIds: [] }, ready()).groups).toEqual(['NOC Ana'])
  })
})

describe('targetGroupNames — sunucunun resolveTargets sırası', () => {
  it('açık seçimin AKTİF grupları; hiçbiri yoksa varsayılanlar; varsayılan yoksa tüm aktifler; gruplar bilinmiyorsa []', () => {
    expect(targetGroupNames([2, 3], GROUPS)).toEqual(['Hafta Sonu'])
    expect(targetGroupNames('2,1', GROUPS)).toEqual(['Hafta Sonu', 'NOC Ana'])
    expect(targetGroupNames([3], GROUPS)).toEqual(['NOC Ana'])
    expect(targetGroupNames([], [G(4, 'A'), G(5, 'B'), G(6, 'C', { active: false })])).toEqual(['A', 'B'])
    expect(targetGroupNames([99], GROUPS)).toEqual(['NOC Ana'])
    expect(targetGroupNames([1], null)).toEqual([])
  })
})

describe('usableNocState', () => {
  it('veri yoksa null; varsa parçalar tek tek (bilinmeyen parça null)', () => {
    expect(usableNocState(null)).toBeNull()
    expect(usableNocState({ status: 'error', data: null })).toBeNull()
    expect(usableNocState({ status: 'ready', data: { groups: null, disabledTypes: ['PING'], hasActiveGroup: null, minLevel: null } }))
      .toEqual({ groups: null, disabledTypes: ['PING'], hasActiveGroup: null, minLevel: null })
  })
})

describe('eylem hedefleri', () => {
  it('düzenle: izleme türü → ?tab=<tür>&monitor=<id>&open=noc; SSL ve kimliksiz → null', () => {
    expect(nocEditTarget('HTTP', 7)).toEqual({ tab: 'http', params: { monitor: 7, open: 'noc' } })
    expect(nocEditTarget('PAGESPEED', '12')).toEqual({ tab: 'pagespeed', params: { monitor: '12', open: 'noc' } })
    expect(nocEditTarget('SSL', 3)).toBeNull()
    expect(nocEditTarget('PING', null)).toBeNull()
  })

  it('kapsam: sekme noc, tür + hedef süzgeci (boş hedef yazılmaz)', () => {
    expect(nocCoverageTarget('PORT', ' db.example.com:5432 ')).toEqual({ tab: 'noc', params: { n_type: 'PORT', n_q: 'db.example.com:5432' } })
    expect(nocCoverageTarget('SSL', '')).toEqual({ tab: 'noc', params: { n_type: 'SSL' } })
  })
})
