import { describe, it, expect, vi } from 'vitest'
import {
  INVENTORY_ROUTING_DEFAULTS, ROUTING_DEFAULTS, formTeamSelect, grantWriteTeamsFromMe, isGrantTeam, isRoutingLockedRow, mergeWriteTeams,
  routingLockOf, withRoutingDefaults,
} from '../hooks/useMonitorTeamPick.js'
import { freshestRow, reloadAndSyncDetail, withRowFlags } from '../utils/monitorDetailSync.js'

/**
 * Bildirim yönlendirmesi kilidi (inceleme 5, 2026-10-12): takım erişim paylaşımıyla YAZILAN kayıtta ve paylaşan takıma eklenen
 * YENİ kayıtta bildirim kanalları / seviye / grup / 7/24 (envanterde kademe) değiştirilemez — sunucu farkı 403 ile reddeder.
 * Form kilidi ve varsayılana çekme bu yardımcılarla kurulur; sunucunun "aynı değer" kuralıyla birebir.
 */
const PICK = [{ id: 1, name: 'Kendi' }, { id: 7, name: 'Ödeme', grant: true }]

describe('routingLockOf / isGrantTeam / isRoutingLockedRow', () => {
  it('yeni kayıt: yalnız paylaşımla yazılabilen takım seçiliyse kilitli; metin / sayı kimlik aynı', () => {
    expect(routingLockOf('new', '7', PICK)).toBe(true)
    expect(routingLockOf('new', 7, PICK)).toBe(true)
    expect(routingLockOf('new', '1', PICK)).toBe(false)
    expect(routingLockOf('new', '', PICK)).toBe(false)
    expect(routingLockOf('new', null, PICK)).toBe(false)
    expect(isGrantTeam('7', null)).toBe(false)
  })

  it('düzenleme: sunucu bayrağı karar verir (routing_locked ya da shared_access); seçili takım bakılmaz', () => {
    expect(routingLockOf({ id: 3, team_id: 7, routing_locked: true }, '7', [])).toBe(true)
    expect(routingLockOf({ id: 3, team_id: 7, shared_access: true }, '7', [])).toBe(true)
    expect(routingLockOf({ id: 3, team_id: 7 }, '7', PICK)).toBe(false)   // bayrak yok = kendi kapsamı
    expect(routingLockOf(null, '7', PICK)).toBe(false)
    expect(isRoutingLockedRow({ routing_locked: false, shared_access: false })).toBe(false)
    expect(isRoutingLockedRow('new')).toBe(false)
  })
})

describe('withRoutingDefaults', () => {
  it('farklı değerler varsayılana çekilir; formda olmayan anahtar EKLENMEZ', () => {
    const f = { name: 'x', notifyEmail: false, notifyWebhook: false, alertLevel: 'CRITICAL', notificationGroupId: '44', nocNotify: true, nocGroupIds: [3] }
    expect(withRoutingDefaults(f)).toEqual({ name: 'x', notifyEmail: true, notifyWebhook: true, alertLevel: 'WARNING', notificationGroupId: '', nocNotify: false, nocGroupIds: [] })
    expect(withRoutingDefaults({ name: 'y', alertLevel: 'HIGH' })).toEqual({ name: 'y', alertLevel: 'WARNING' })
    expect(ROUTING_DEFAULTS.nocGroupIds).toEqual([])
  })

  it('zaten varsayılansa (sunucu eşdeğerleri dâhil) AYNI nesne döner — setState döngüsü olmaz', () => {
    const f = { notifyEmail: true, notifyWebhook: null, alertLevel: 'warning', notificationGroupId: null, nocNotify: false, nocGroupIds: [] }
    expect(withRoutingDefaults(f)).toBe(f)
    expect(withRoutingDefaults(null)).toBeNull()
  })

  it('envanter anahtarları: kademe null, grup boş, 7/24 kapalı', () => {
    const f = { domain: 'a.example.com', tier: 1, notification_group_id: '5', noc_notify: true, noc_group_ids: ['2'] }
    expect(withRoutingDefaults(f, INVENTORY_ROUTING_DEFAULTS)).toEqual({ domain: 'a.example.com', tier: null, notification_group_id: '', noc_notify: false, noc_group_ids: [] })
    const same = { domain: 'b', tier: null, notification_group_id: '', noc_notify: false, noc_group_ids: [] }
    expect(withRoutingDefaults(same, INVENTORY_ROUTING_DEFAULTS)).toBe(same)
  })
})

describe('formTeamSelect — paylaşımla yazılan kayıt', () => {
  const options = [{ value: '1', label: 'Kendi' }]
  it('routing_locked satırda seçici kilitli; kayıtlı takım seçeneklerde yoksa ADIYLA eklenir (çıplak kimlik değil)', () => {
    const out = formTeamSelect({ id: 4, team_id: 7, team_name: 'Ödeme Takımı', routing_locked: true }, options, PICK)
    expect(out.disabled).toBe(true)
    expect(out.options).toEqual([...options, { value: '7', label: 'Ödeme Takımı' }])
    // ad bilinmiyorsa kimlik — ama seçenek yine var (seçici boş görünmez)
    expect(formTeamSelect({ id: 4, team_id: 9, shared_access: true }, options, PICK).options.at(-1)).toEqual({ value: '9', label: '9' })
    // seçenek zaten varsa aynı dizi
    expect(formTeamSelect({ id: 4, team_id: 1, routing_locked: true }, options, PICK).options).toBe(options)
  })
})

describe('inceleme 6: kendi kapsamıyla GÖRÜLEN ama yalnız paylaşımla YAZILAN takım (müdür / AUDIT)', () => {
  it('/me grant_write_teams paylaşım işaretiyle eklenir; access_grants ile aynı takım tekrar etmez', () => {
    const res = {
      access_grants: [{ team_id: 4, team_name: 'D', level: 'EDIT' }, { team_id: 6, team_name: 'F', level: 'VIEW' }],
      grant_write_teams: [{ team_id: 4, team_name: 'D' }, { team_id: 9, team_name: 'Müdürün görüş alanındaki takım' }],
    }
    expect(grantWriteTeamsFromMe(res)).toEqual([
      { id: 4, name: 'D', grant: true }, { id: 9, name: 'Müdürün görüş alanındaki takım', grant: true }])
    expect(grantWriteTeamsFromMe({ access_grants: [] })).toEqual([])
  })

  it('mergeWriteTeams: listede zaten olan takım (yönetici listesi) paylaşım işaretini alır → yeni kayıt kilitlenir', () => {
    const admin = [{ id: 1, name: 'Kendi' }, { id: 9, name: 'Görüş alanı' }]
    const merged = mergeWriteTeams(admin, [{ id: 9, name: '9', grant: true }], { flagExisting: true })
    expect(merged).toEqual([{ id: 1, name: 'Kendi' }, { id: 9, name: 'Görüş alanı', grant: true }])
    expect(admin[1].grant).toBeUndefined()                      // girdi değişmez
    expect(routingLockOf('new', '9', merged)).toBe(true)
    // değişecek bir şey yoksa AYNI dizi (memo bağımlılıkları)
    expect(mergeWriteTeams(admin, [], { flagExisting: true })).toBe(admin)
    expect(mergeWriteTeams(admin, [{ id: 1, name: 'Kendi' }], { flagExisting: true })).toBe(admin)
    // üyelik tabanı (App) işaretlenmez — üyelik kendi kapsamıdır
    expect(mergeWriteTeams(admin, [{ id: 9, name: '9', grant: true }])).toBe(admin)
  })
})

describe('monitorDetailSync — paylaşım bayrakları', () => {
  it('tetik yanıtı bayrak taşımaz: açık kopyadaki shared_access / routing_locked korunur', () => {
    const prev = { id: 1, can_edit: true, shared_access: true, routing_locked: true }
    expect(withRowFlags(prev, { id: 1, last_status: 'UP' })).toEqual({ id: 1, last_status: 'UP', can_edit: true, shared_access: true, routing_locked: true })
  })

  it('liste satırı tek doğru kaynak: satırda yoksa kopyadan DÜŞER (paylaşım kalktı / takıma katıldı)', () => {
    const copy = { id: 1, url: 'https://a/', shared_access: true, routing_locked: true }
    const out = freshestRow(copy, [{ id: 1, url: 'https://b/', can_edit: true }])
    expect(out).toEqual({ id: 1, url: 'https://b/', can_edit: true })
    expect(freshestRow(copy, [{ id: 1, routing_locked: true }])).toMatchObject({ routing_locked: true })
    expect('shared_access' in freshestRow(copy, [{ id: 1, routing_locked: true }])).toBe(false)
  })

  it('geri alma sonrası tazeleme de bayrakları liste satırından alır', async () => {
    let state = { id: 2, shared_access: true, name: 'eski' }
    const setDetail = vi.fn((fn) => { state = fn(state) })
    await reloadAndSyncDetail(vi.fn().mockResolvedValue([{ id: 2, name: 'yeni' }]), 2, setDetail)
    expect(state).toEqual({ id: 2, name: 'yeni' })
  })
})
