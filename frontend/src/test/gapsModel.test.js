import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  CLOSE_QUIET_MS, DEADLINE, GAP_CATEGORIES, MAX_DISMISSED, MAX_ITEMS_PER_GAP, MAX_TEAMS, RECHECK_MS, SNOOZE_MS, collectGaps,
  dismissState, hashKey, ownTeamIds, profileGaps, readSession, readState, shouldAutoCheck, shouldPrompt, snoozeState, teamGaps,
  withDeadline,
} from '../components/gaps/gapsModel.js'
import { LOCAL_PREF_KEYS } from '../hooks/userPrefsModel.js'
import { PERSONAL_PREFIXES } from '../utils/personalStorage.js'
import { TR as tr } from '../i18n/tr.js'
import { EN as en } from '../i18n/en.js'

const rule = (code, scope, failing, items = [], severity = 'HIGH') => ({ code, scope, severity, failing, eligible: failing + 1, items })

describe('eksikler penceresi — model', () => {
  it('profil: e-posta yok / bozuk ve telefon yok eksik; alan HİÇ yoksa bilinmiyor (eksik denmez)', () => {
    expect(profileGaps({ email: null, phone: '' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
    expect(profileGaps({ email: '', phone: ' ' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
    expect(profileGaps({ email: 'a b@x.com', phone: '05551112233' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL'])
    expect(profileGaps({ email: 'ayse@', phone: '1' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL'])
    // Sunucudan katı değil: tek parçalı alan adı (iç posta sunucusu) eksik sayılmaz
    expect(profileGaps({ email: 'ayse@localhost', phone: '1' })).toEqual([])
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
    expect(mon.teamName).toBe('Dijital Kanallar')
  })

  it('collectGaps: kategoriler, önem sırası, okunamayan takım ayrı (işarete girmez), uygulanamayan takım atlanır, profil bayrakları', () => {
    const r = collectGaps({
      profile: { email: null, phone: '1' }, profileFailed: false,
      teams: [
        { id: 1, data: { team: { name: 'B Takımı' }, rules: [rule('MON_PAUSED_LONG', 'MONITOR', 1, [{ kind: 'monitor', id: 9, name: 'x' }], 'LOW'),
          rule('MON_NO_TEAM', 'MONITOR', 1, [{ kind: 'monitor', id: 8, name: 'y' }], 'HIGH')] } },
        { id: 2, failed: true },
        { id: 4, skipped: true },
        { id: 3, data: { team: { name: 'A Takımı' }, rules: [rule('INV_NO_CONTACTS', 'INVENTORY', 1, [{ kind: 'inventory', name: 'a.example' }], 'LOW')] } },
      ],
    })
    expect(Object.keys(r.byCategory)).toEqual(GAP_CATEGORIES)
    expect(r.byCategory.profile.map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL'])
    expect(r.byCategory.monitors.map((g) => g.code)).toEqual(['MON_NO_TEAM', 'MON_PAUSED_LONG'])
    expect(r.byCategory.inventory).toHaveLength(1)
    expect(r.total).toBe(4)
    expect(r.failedTeams).toEqual([{ id: 2, name: null }])
    // İşaretler EKSİK düzeyinde (kalem değil) ve kayıt sayısıyla
    expect(r.marks).toEqual([
      { key: 'PROFILE_NO_EMAIL', n: 1, scope: 'p' },
      { key: '1:MON_PAUSED_LONG', n: 1, scope: 't1' },
      { key: '1:MON_NO_TEAM', n: 1, scope: 't1' },
      { key: '3:INV_NO_CONTACTS', n: 1, scope: 't3' },
    ])
    expect(r.profileFailed).toBe(false)
    expect(r.profileKnown).toBe(true)
    expect(collectGaps({ profile: { email: 'a@b.c' } }).profileKnown).toBe(false)   // telefon alanı yok → bilinmiyor
    expect(collectGaps({ profile: null, profileFailed: true }).profileFailed).toBe(true)
  })

  it('kendiliğinden gösterme: eksik yoksa hayır; erteleme 1 gün; "gösterme" yeni eksik / artan sayı çıkana dek', () => {
    const now = 1_800_000_000_000
    const marks = [{ key: 'PROFILE_NO_EMAIL', n: 1, scope: 'p' }, { key: '4:MON_NO_GROUP', n: 2, scope: 't4' }]
    expect(shouldPrompt(null, [], now)).toBe(false)
    expect(shouldPrompt(null, marks, now)).toBe(true)
    const snoozed = snoozeState(null, now)
    expect(snoozed.snoozeUntil).toBe(now + SNOOZE_MS)
    expect(shouldPrompt(snoozed, marks, now + SNOOZE_MS - 1)).toBe(false)
    expect(shouldPrompt(snoozed, marks, now + SNOOZE_MS + 1)).toBe(true)
    const dismissed = dismissState(snoozed, marks)
    expect(dismissed.snoozeUntil).toBeNull()
    expect(shouldPrompt(dismissed, marks, now)).toBe(false)
    expect(shouldPrompt(dismissed, marks.slice(0, 1), now)).toBe(false)                      // bir eksik düzeldi
    expect(shouldPrompt(dismissed, [marks[0], { ...marks[1], n: 1 }], now)).toBe(false)      // kayıt azaldı
    expect(shouldPrompt(dismissed, [marks[0], { ...marks[1], n: 3 }], now)).toBe(true)       // kayıt arttı → yeni eksik
    expect(shouldPrompt(dismissed, [...marks, { key: '4:INV_NO_TIER', n: 1, scope: 't4' }], now)).toBe(true)   // yeni kural
    expect(shouldPrompt(JSON.stringify(dismissed), marks, now)).toBe(false)                  // saklanan JSON
  })

  it('çok kalemli eksik (kural başına 500+ kayıt): "gösterme" yine çalışır — eskiden kalem düzeyi 500 sınırını aşıyordu', () => {
    const now = 1_800_000_000_000
    const many = [{ key: '7:INV_NO_CONTACTS', n: 300, scope: 't7' }, { key: '7:MON_NO_GROUP', n: 250, scope: 't7' }]
    const d = dismissState(null, many)
    expect(d.dismissed).toHaveLength(2)
    expect(shouldPrompt(d, many, now)).toBe(false)
    expect(shouldPrompt(d, [{ ...many[0], n: 301 }, many[1]], now)).toBe(true)
  })

  it('okunamayan takımın eski "gösterme" kaydı korunur; okunan takımlarınki şimdiki eksiklerle değişir', () => {
    const now = 1_800_000_000_000
    const first = dismissState(null, [{ key: '1:A', n: 2, scope: 't1' }, { key: '2:B', n: 1, scope: 't2' }])
    // Takım 2 bu kez okunamadı: kaydı korunur; takım 1'in eski kaydı (1:A) yerini yeni eksiğe bıraktı
    const second = dismissState(first, [{ key: '1:C', n: 1, scope: 't1' }], [2])
    expect(second.dismissed.map((e) => e[0]).sort()).toEqual([hashKey('1:C'), hashKey('2:B')].sort())
    expect(shouldPrompt(second, [{ key: '2:B', n: 1, scope: 't2' }], now)).toBe(false)
    expect(shouldPrompt(second, [{ key: '1:A', n: 2, scope: 't1' }], now)).toBe(true)   // düzelip geri gelen eksik yeni sayılır
  })

  it('saklanan durum: bozuk / sahte değer atılır; özetler kısa ve sınırlı; ham kimlik / ad yazılmaz; eski biçim yok sayılır', () => {
    expect(readState('{bozuk')).toEqual({ v: 2, snoozeUntil: null, dismissed: [] })
    expect(readState({ snoozeUntil: 'x', dismissed: ['abcdef12', ['zz', 1, 'abcdef12'], ['abcdef12', -1, 'abcdef12'],
      ['abcdef12', 2, 'abcdef12'], ['abcdef12', 1.5, 'abcdef12']] }))
      .toEqual({ v: 2, snoozeUntil: null, dismissed: [['abcdef12', 2, 'abcdef12']] })
    const marks = [...Array(MAX_DISMISSED + 50)].map((_, i) => ({ key: `${i}:MON_NO_GROUP`, n: 1, scope: `t${i}` }))
    const d = dismissState(null, marks)
    expect(d.dismissed).toHaveLength(MAX_DISMISSED)
    expect(JSON.stringify(d)).not.toMatch(/MON_NO_GROUP/)
    expect(hashKey('a')).toMatch(/^[0-9a-f]{8}$/)
    expect(hashKey('a')).not.toBe(hashKey('b'))
  })

  it('kendiliğinden okuma kararı (ağa çıkmadan): erteleme, bu oturumda kapatma (12 sa), başka sekmenin yakın denetimi (30 dk)', () => {
    const now = 1_800_000_000_000
    expect(shouldAutoCheck(null, null, now)).toBe(true)
    expect(shouldAutoCheck(snoozeState(null, now - 1000), null, now)).toBe(false)
    expect(shouldAutoCheck(null, { closedAt: now - 1000 }, now)).toBe(false)
    expect(shouldAutoCheck(null, { closedAt: now - CLOSE_QUIET_MS - 1 }, now)).toBe(true)
    expect(shouldAutoCheck(null, { checkedAt: now - 1000 }, now)).toBe(false)
    expect(shouldAutoCheck(null, { checkedAt: now - RECHECK_MS - 1 }, now)).toBe(true)
    // Saat geri alındı (gelecekte damga): kayıt yok sayılır
    expect(shouldAutoCheck(null, { checkedAt: now + 60_000, closedAt: now + 60_000 }, now)).toBe(true)
    expect(readSession('{x')).toEqual({ checkedAt: null, closedAt: null })
  })

  it('tercih anahtarı sunucuya aynalanır ve çıkışta yerelden silinir; oturum kaydı aynalanmaz ama çıkışta silinir; metinler iki dilde', () => {
    expect(LOCAL_PREF_KEYS).toContain('sm.gaps.state')
    expect(LOCAL_PREF_KEYS).not.toContain('sm.gaps.session')
    expect(PERSONAL_PREFIXES).toContain('sm.gaps.state')
    expect(PERSONAL_PREFIXES).toContain('sm.gaps.session')
    const keys = ['gaps.menu', 'gaps.title', 'gaps.desc', 'gaps.none', 'gaps.snooze', 'gaps.dismiss', 'gaps.close', 'gaps.profileFailed',
      'gaps.permUnknown',
      ...GAP_CATEGORIES.map((c) => `gaps.cat.${c}`),
      'gaps.profile.PROFILE_NO_EMAIL.title', 'gaps.profile.PROFILE_NO_EMAIL.why', 'gaps.profile.PROFILE_NO_PHONE.title', 'gaps.profile.PROFILE_NO_PHONE.why']
    for (const k of keys) { expect(tr[k], k).toBeTruthy(); expect(en[k], k).toBeTruthy() }
  })

  it('üst süre: söz zamanında sonuçlanmazsa DEADLINE, zamanında sonuçlanırsa değeri; zamanlayıcı temizlenir', async () => {
    vi.useFakeTimers()
    try {
      const never = new Promise(() => {})
      const p1 = withDeadline(never, 1000)
      vi.advanceTimersByTime(1000)
      await expect(p1).resolves.toBe(DEADLINE)
      const p2 = withDeadline(Promise.resolve(7), 1000)
      await expect(p2).resolves.toBe(7)
      expect(vi.getTimerCount()).toBe(0)
      await expect(withDeadline(Promise.reject(new Error('x')), 1000)).rejects.toThrow('x')
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })
})
