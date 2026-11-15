import { describe, it, expect } from 'vitest'
import {
  GAP_CATEGORIES, MAX_DISMISSED, MAX_ITEMS_PER_GAP, MAX_TEAMS, SNOOZE_MS, collectGaps, dismissState, hashKey, ownTeamIds,
  profileGaps, readState, shouldPrompt, snoozeState, teamGaps,
} from '../components/gaps/gapsModel.js'
import { LOCAL_PREF_KEYS } from '../hooks/userPrefsModel.js'
import { PERSONAL_PREFIXES } from '../utils/personalStorage.js'
import { TR as tr } from '../i18n/tr.js'
import { EN as en } from '../i18n/en.js'

const rule = (code, scope, failing, items = [], severity = 'HIGH') => ({ code, scope, severity, failing, eligible: failing + 1, items })

describe('eksikler penceresi — model', () => {
  it('profil: e-posta yok / bozuk ve telefon yok eksik; alan HİÇ yoksa bilinmiyor (eksik denmez)', () => {
    expect(profileGaps({ email: null, phone: '' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
    expect(profileGaps({ email: 'ayse@kurum', phone: ' ' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
    expect(profileGaps({ email: 'a b@x.com', phone: '05551112233' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL'])
    expect(profileGaps({ email: 'ayse@kurum.com.tr', phone: '05551112233' })).toEqual([])
    expect(profileGaps({ display_name: 'Ayşe' })).toEqual([])            // giriş yanıtı: alan yok → bilinmiyor
    expect(profileGaps(null)).toEqual([])
  })

  it('kendi takımları: üyesi + yönettiği, tekil, geçersiz atılır, en çok 10 (+fazlası sayılır)', () => {
    expect(ownTeamIds([3, 1, 3], [1, 7])).toEqual({ ids: [3, 1, 7], more: 0 })
    expect(ownTeamIds(null, null)).toEqual({ ids: [], more: 0 })
    expect(ownTeamIds(['x', 0, -2, '5'], undefined)).toEqual({ ids: [5], more: 0 })
    const many = [...Array(14)].map((_, i) => i + 1)
    expect(ownTeamIds(many, [])).toEqual({ ids: many.slice(0, MAX_TEAMS), more: 4 })
  })

  it('takım bulguları: kapsamdan kategori, kusursuz kural yok, kalemler 5 ile sınırlı (+kalan)', () => {
    const items = [...Array(8)].map((_, i) => ({ kind: 'monitor', type: 'http', id: i + 1, name: `İzleme ${i + 1}` }))
    const gaps = teamGaps(4, { team: { id: 4, name: 'Dijital Kanallar' }, rules: [
      rule('TEAM_NO_ESCALATION', 'TEAM', 1, [{ kind: 'team', id: 4, name: 'Dijital Kanallar' }]),
      rule('MON_NO_GROUP', 'MONITOR', 8, items, 'LOW'),
      rule('INV_NO_TIER', 'INVENTORY', 0, []),
      rule('UNKNOWN_RULE', 'ELSEWHERE', 3, []),
    ] })
    expect(gaps.map((g) => [g.code, g.category])).toEqual([['TEAM_NO_ESCALATION', 'team'], ['MON_NO_GROUP', 'monitors']])
    const mon = gaps[1]
    expect(mon.items).toHaveLength(MAX_ITEMS_PER_GAP)
    expect(mon.more).toBe(3)
    expect(mon.itemKeys).toHaveLength(8)
    expect(mon.teamName).toBe('Dijital Kanallar')
  })

  it('collectGaps: kategoriler, önem sırası, okunamayan takım ayrı (anahtara girmez), profil okunamadı bayrağı', () => {
    const r = collectGaps({
      profile: { email: null, phone: '1' }, profileFailed: false,
      teams: [
        { id: 1, data: { team: { name: 'B Takımı' }, rules: [rule('MON_PAUSED_LONG', 'MONITOR', 1, [{ kind: 'monitor', id: 9, name: 'x' }], 'LOW'),
          rule('MON_NO_TEAM', 'MONITOR', 1, [{ kind: 'monitor', id: 8, name: 'y' }], 'HIGH')] } },
        { id: 2, failed: true },
        { id: 3, data: { team: { name: 'A Takımı' }, rules: [rule('INV_NO_CONTACTS', 'INVENTORY', 1, [{ kind: 'inventory', name: 'a.example' }], 'LOW')] } },
      ],
    })
    expect(Object.keys(r.byCategory)).toEqual(GAP_CATEGORIES)
    expect(r.byCategory.profile.map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL'])
    expect(r.byCategory.monitors.map((g) => g.code)).toEqual(['MON_NO_TEAM', 'MON_PAUSED_LONG'])
    expect(r.byCategory.inventory).toHaveLength(1)
    expect(r.total).toBe(4)
    expect(r.failedTeams).toEqual([{ id: 2, name: null }])
    expect(r.keys).toContain('PROFILE_NO_EMAIL')
    expect(r.keys.some((k) => k.startsWith('MON_NO_TEAM|monitor|'))).toBe(true)
    expect(r.profileFailed).toBe(false)
    expect(collectGaps({ profile: null, profileFailed: true }).profileFailed).toBe(true)
  })

  it('kendiliğinden gösterme: eksik yoksa hayır; erteleme 1 gün; "gösterme" yeni eksik çıkana dek', () => {
    const now = 1_800_000_000_000
    const keys = ['PROFILE_NO_EMAIL', 'MON_NO_GROUP|monitor|http|5']
    expect(shouldPrompt(null, [], now)).toBe(false)
    expect(shouldPrompt(null, keys, now)).toBe(true)
    const snoozed = snoozeState(null, now)
    expect(snoozed.snoozeUntil).toBe(now + SNOOZE_MS)
    expect(shouldPrompt(snoozed, keys, now + SNOOZE_MS - 1)).toBe(false)
    expect(shouldPrompt(snoozed, keys, now + SNOOZE_MS + 1)).toBe(true)
    const dismissed = dismissState(snoozed, keys)
    expect(dismissed.snoozeUntil).toBeNull()
    expect(shouldPrompt(dismissed, keys, now)).toBe(false)
    expect(shouldPrompt(dismissed, keys.slice(0, 1), now)).toBe(false)              // bir eksik düzeldi: yine gösterilmez
    expect(shouldPrompt(dismissed, [...keys, 'INV_NO_TIER|inventory||b.example'], now)).toBe(true)   // yeni eksik
    expect(shouldPrompt(JSON.stringify(dismissed), keys, now)).toBe(false)          // saklanan JSON
  })

  it('saklanan durum: bozuk / sahte değer atılır; özetler kısa ve sınırlı; ham kimlik / ad yazılmaz', () => {
    expect(readState('{bozuk')).toEqual({ v: 1, snoozeUntil: null, dismissed: [] })
    expect(readState({ snoozeUntil: 'x', dismissed: ['zz', 'abcdef12', 5] })).toEqual({ v: 1, snoozeUntil: null, dismissed: ['abcdef12'] })
    const keys = [...Array(MAX_DISMISSED + 50)].map((_, i) => `MON_NO_GROUP|monitor|http|${i}`)
    const d = dismissState(null, keys)
    expect(d.dismissed).toHaveLength(MAX_DISMISSED)
    expect(JSON.stringify(d)).not.toMatch(/MON_NO_GROUP|monitor/)
    expect(hashKey('a')).toMatch(/^[0-9a-f]{8}$/)
    expect(hashKey('a')).not.toBe(hashKey('b'))
  })

  it('tercih anahtarı sunucuya aynalanır ve çıkışta yerelden silinir; metinler iki dilde', () => {
    expect(LOCAL_PREF_KEYS).toContain('sm.gaps.state')
    expect(PERSONAL_PREFIXES).toContain('sm.gaps.state')
    const keys = ['gaps.menu', 'gaps.title', 'gaps.desc', 'gaps.none', 'gaps.snooze', 'gaps.dismiss', 'gaps.close', 'gaps.profileFailed',
      ...GAP_CATEGORIES.map((c) => `gaps.cat.${c}`),
      'gaps.profile.PROFILE_NO_EMAIL.title', 'gaps.profile.PROFILE_NO_EMAIL.why', 'gaps.profile.PROFILE_NO_PHONE.title', 'gaps.profile.PROFILE_NO_PHONE.why']
    for (const k of keys) { expect(tr[k], k).toBeTruthy(); expect(en[k], k).toBeTruthy() }
  })
})
