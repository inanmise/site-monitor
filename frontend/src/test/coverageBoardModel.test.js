import { describe, it, expect } from 'vitest'
import {
  BOARD_FILTERS, buildBoard, defaultGroupState, deliveryState, filterBoard, nocState, oncallState, sortBoard,
} from '../components/admin/teams/coverageBoardModel.js'

/**
 * Takımlar → kapsam panosu modeli (2026-11-13, (h)). Kural: okunamayan boyut BİLİNMİYOR'dur — eksik sayılmaz, yeşil
 * çizilmez; varsayılan grubun tüm adresleri pasifse grup uygulanır ama posta düşer → takım adresi olsa da "ulaşmıyor".
 */
const g = (id, team, over = {}) => ({ id, team_id: team, name: `G${id}`, emails: ['a@example.com'], inactive_emails: [], is_default: true, active: true, ...over })

describe('varsayılan grup + ulaşım', () => {
  it('grup durumları', () => {
    expect(defaultGroupState(1, null).state).toBe('unknown')
    expect(defaultGroupState(1, []).state).toBe('none')
    expect(defaultGroupState(1, [g(1, 1, { emails: ['bad'] })]).state).toBe('noEmails')
    expect(defaultGroupState(1, [g(1, 1, { inactive_emails: ['A@example.com'] })]).state).toBe('allPassive')
    expect(defaultGroupState(1, [g(1, 1, { emails: ['a@example.com', 'b@example.com'], inactive_emails: ['a@example.com'] })])).toMatchObject({ state: 'ok', delivered: 1 })
    // pasif listesi bilinmiyorsa (eski sunucu) "ok" ama teslim sayısı bilinmiyor
    expect(defaultGroupState(1, [g(1, 1, { inactive_emails: undefined })])).toMatchObject({ state: 'ok', delivered: null })
    // eski yumuşak silinmiş varsayılan sayılmaz
    expect(defaultGroupState(1, [g(1, 1, { active: false })]).state).toBe('none')
  })
  it('ulaşım: tüm adresleri pasif varsayılan takım adresi olsa da "none"; bilinmeyen varsayılan takım adresi olsa da unknown', () => {
    expect(deliveryState({ email: 'x@example.com' }, { state: 'allPassive' })).toBe('none')
    expect(deliveryState({ email: 'x@example.com' }, { state: 'none' })).toBe('ok')
    expect(deliveryState({ email: '' }, { state: 'ok', delivered: 2 })).toBe('ok')
    expect(deliveryState({ email: ' ' }, { state: 'noEmails' })).toBe('none')
    expect(deliveryState({ email: '' }, { state: 'unknown' })).toBe('unknown')
    // İnceleme #2: grup bilinmiyorsa takım adresi "yeşil" yapmaz — tüm-pasif bir varsayılan sonucu "ulaşmıyor"a çevirirdi
    expect(deliveryState({ email: 'x@example.com' }, { state: 'unknown' })).toBe('unknown')
    // Etkin adres sayısı bilinmeyen varsayılan (pasif listesi okunamadı) da bilinmiyor
    expect(deliveryState({ email: 'x@example.com' }, { state: 'ok', delivered: null })).toBe('unknown')
  })
})

describe('nöbet + 7/24', () => {
  it('nöbet: sorulmamış takım bilinmiyor, sorulup yanıtta yoksa nöbetçi yok', () => {
    const oc = { byTeam: new Map([['1', { name: 'Ayşe', until: '2026-11-14T06:00:00' }]]), known: new Set(['1', '2']) }
    expect(oncallState(1, oc)).toMatchObject({ state: 'on', name: 'Ayşe' })
    expect(oncallState(2, oc).state).toBe('none')
    expect(oncallState(3, oc).state).toBe('unknown')
    expect(oncallState(1, null).state).toBe('unknown')
  })
  it('7/24: duraklatılan sayılmaz; tamamı / kısmi / hiç; harita yok → bilinmiyor; takım yok → izleme yok', () => {
    const noc = { byTeam: { 1: { total: 4, covered: 3, paused: 1 }, 2: { total: 3, covered: 1, paused: 0 }, 3: { total: 2, covered: 0, paused: 0 } } }
    expect(nocState(1, noc)).toMatchObject({ state: 'all', covered: 3, active: 3 })
    expect(nocState(2, noc).state).toBe('partial')
    expect(nocState(3, noc).state).toBe('zero')
    expect(nocState(4, noc).state).toBe('noMonitors')
    expect(nocState(1, null).state).toBe('unknown')
  })
})

describe('pano', () => {
  const teams = [
    { id: 1, name: 'Ödeme', email: 'odeme@example.com', active: true },
    { id: 2, name: 'Arşiv', email: '', active: true },
    { id: 3, name: 'Çağrı', email: 'cagri@example.com', active: true },
  ]
  const stats = { 1: { monitors: 3 }, 2: { monitors: 0, domains: 0 }, 3: { monitors: 2 } }
  const contacts = [{ id: 1, team_id: 1, min_alert_level: 'WARNING', active: true }]
  const groups = [g(10, 1)]
  const oncall = { byTeam: new Map(), known: new Set(['1', '2', '3']) }

  it('boyutlar ve özet; kişiler okunamazsa eskalasyon null ve kritik boşluk SAYILMAZ', () => {
    const b = buildBoard(teams, { stats, contacts, groups, oncall, noc: { byTeam: {} } })
    const byId = Object.fromEntries(b.rows.map((r) => [r.teamId, r]))
    expect(byId[1].escalation.tone).toBe('ok')
    expect(byId[3].escalation.tone).toBe('gap')
    expect(byId[2].delivery).toBe('none')
    expect(b.summary).toMatchObject({ teams: 3, noRecipient: 1, critGap: 1, noDefault: 2, noOncall: 3, deliveryKnown: true })
    // Tek bir bilinmeyen ulaşım satırı (pasif listesi okunamayan varsayılan) "Alıcısız" KPI'sını "—"ye çevirir
    const partial = buildBoard(teams, { stats, contacts, groups: [g(10, 1, { inactive_emails: undefined })], oncall, noc: { byTeam: {} } })
    expect(partial.rows.find((r) => r.teamId === 1).delivery).toBe('unknown')
    expect(partial.summary.deliveryKnown).toBe(false)
    const unknown = buildBoard(teams, { stats, contacts: null, groups: null, oncall: null, noc: null })
    expect(unknown.rows.every((r) => r.escalation === null && r.defaultGroup.state === 'unknown' && r.oncall.state === 'unknown')).toBe(true)
    expect(unknown.summary).toMatchObject({ critGap: 0, noDefault: 0, noOncall: 0, escalationKnown: false, groupKnown: false,
      oncallKnown: false, deliveryKnown: false, noRecipient: 0 })
    // Grup bilinmiyorsa takım adresi olan satır da BİLİNMİYOR (tüm-pasif varsayılan sonucu çevirirdi) — asla yeşil değil
    expect(unknown.rows.find((r) => r.teamId === 2).delivery).toBe('unknown')
    expect(unknown.rows.find((r) => r.teamId === 1).delivery).toBe('unknown')
  })
  it('süzgeç + sıralama: en sorunlu takım önce', () => {
    const b = buildBoard(teams, { stats, contacts, groups, oncall, noc: { byTeam: {} } })
    expect(BOARD_FILTERS).toContain('noRecipient')
    expect(filterBoard(b.rows, 'noRecipient').map((r) => r.teamId)).toEqual([2])
    expect(filterBoard(b.rows, 'critGap').map((r) => r.teamId)).toEqual([3])
    expect(filterBoard(b.rows, 'all')).toHaveLength(3)
    expect(sortBoard(b.rows).map((r) => r.teamId)).toEqual([2, 3, 1])
  })
})
