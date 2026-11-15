import { describe, it, expect, vi } from 'vitest'
import {
  AUTO_DEFER_MAX_MS, AUTO_DEFER_POLL_MS, AUTO_QUIET_MS, CLOSE_QUIET_MS, DEADLINE, GAP_CATEGORIES, MAX_DISMISSED, MAX_HIDDEN,
  MAX_ITEMS_PER_GAP, MAX_SEEN, MAX_TEAMS, RECHECK_MS,
  SNOOZE_MS, SNOOZE_WEEK_MS, collectGaps, dismissState, formatSeen, groupByTeam, hashKey, hideState, markSeen, ownTeamIds,
  parseSeen, profileGaps, readSession, readState, seenDiff, shouldAutoCheck, shouldPrompt, snoozeState, teamGaps, unhideState,
  userInteracting, withDeadline,
} from '../components/gaps/gapsModel.js'
import { LOCAL_PREF_KEYS, MAX_LOCAL_VALUE } from '../hooks/userPrefsModel.js'
import { PERSONAL_PREFIXES } from '../utils/personalStorage.js'
import { TR as tr } from '../i18n/tr.js'
import { EN as en } from '../i18n/en.js'

const rule = (code, scope, failing, items = [], severity = 'HIGH', points) => ({ code, scope, severity, failing, eligible: failing + 1, items,
  ...(points != null ? { points } : {}) })
const EMPTY = { v: 2, snoozeUntil: null, dismissed: [], hidden: [], seen: '' }

describe('eksikler penceresi — model', () => {
  it('profil: e-posta yok / bozuk, telefon yok ya da kodla girişe uygun değil; alan HİÇ yoksa bilinmiyor (eksik denmez)', () => {
    expect(profileGaps({ email: null, phone: '' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
    expect(profileGaps({ email: '', phone: ' ' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_NO_PHONE'])
    expect(profileGaps({ email: 'a b@x.com', phone: '05551112233' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL'])
    // 2026-11-15: "1" gibi bir numara kodla girişte eşleşmez → geçersiz telefon (sunucunun OtpContactMatcher kuralı)
    expect(profileGaps({ email: 'ayse@', phone: '1' }).map((g) => g.code)).toEqual(['PROFILE_NO_EMAIL', 'PROFILE_PHONE_INVALID'])
    // Sunucudan katı değil: tek parçalı alan adı (iç posta sunucusu) eksik sayılmaz
    expect(profileGaps({ email: 'ayse@localhost', phone: '+90 555 111 22 33' })).toEqual([])
    expect(profileGaps({ email: 'ayse@kurum.com.tr', phone: '05551112233' })).toEqual([])
    expect(profileGaps({ display_name: 'Ayşe' })).toEqual([])            // giriş yanıtı: alan yok → bilinmiyor
    expect(profileGaps(null)).toEqual([])
  })

  it('kendi takımları: üyesi + yönettiği, tekil, geçersiz atılır, ilk 10 (+kalanlar ayrı)', () => {
    expect(ownTeamIds([3, 1, 3], [1, 7])).toEqual({ ids: [3, 1, 7], more: 0, rest: [] })
    expect(ownTeamIds(null, null)).toEqual({ ids: [], more: 0, rest: [] })
    expect(ownTeamIds(['x', 0, -2, '5'], undefined)).toEqual({ ids: [5], more: 0, rest: [] })
    const many = [...Array(14)].map((_, i) => i + 1)
    expect(ownTeamIds(many, [])).toEqual({ ids: many.slice(0, MAX_TEAMS), more: 4, rest: many.slice(MAX_TEAMS) })
  })

  it('takım bulguları: kapsamdan kategori, kusursuz kural yok, kalemler 5 ile sınırlı (+kalan), puan etkisi taşınır', () => {
    const items = [...Array(8)].map((_, i) => ({ kind: 'monitor', type: 'http', id: i + 1, name: `İzleme ${i + 1}` }))
    const gaps = teamGaps(4, { team: { id: 4, name: 'Dijital Kanallar' }, rules: [
      rule('TEAM_NO_ESCALATION', 'TEAM', 1, [{ kind: 'team', id: 4, name: 'Dijital Kanallar' }], 'HIGH', 12),
      rule('MON_NO_GROUP', 'MONITOR', 8, items, 'LOW'),
      rule('INV_NO_TIER', 'INVENTORY', 0, []),
      rule('UNKNOWN_RULE', 'ELSEWHERE', 3, []),
    ] })
    expect(gaps.map((g) => [g.code, g.category])).toEqual([['TEAM_NO_ESCALATION', 'team'], ['MON_NO_GROUP', 'monitors']])
    expect(gaps[0].points).toBe(12)
    expect(gaps[1].points).toBe(0)
    const mon = gaps[1]
    expect(mon.items).toHaveLength(MAX_ITEMS_PER_GAP)
    expect(mon.more).toBe(3)
    expect(mon.teamName).toBe('Dijital Kanallar')
    expect(mon.scope).toBe('t4')
  })

  it('collectGaps: kategoriler, önem + puan sırası, okunamayan takım ayrı (işarete girmez), uygulanamayan takım atlanır, profil bayrakları', () => {
    const r = collectGaps({
      profile: { email: null, phone: '05551112233' }, profileFailed: false,
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
    expect(r.byCategory.tasks).toEqual([])
    expect(r.total).toBe(4)
    expect(r.failedTeams).toEqual([{ id: 2, name: null }])
    // İşaretler EKSİK düzeyinde (kalem değil), kayıt sayısı ve kapsamla; kendiliğinden yol listesi verilmediyse hepsi "auto"
    expect(r.marks).toEqual([
      { key: 'PROFILE_NO_EMAIL', n: 1, scope: 'p', auto: true },
      { key: '1:MON_PAUSED_LONG', n: 1, scope: 't1', auto: true },
      { key: '1:MON_NO_TEAM', n: 1, scope: 't1', auto: true },
      { key: '3:INV_NO_CONTACTS', n: 1, scope: 't3', auto: true },
    ])
    expect([...r.readScopes].sort()).toEqual(['p', 't1', 't3'])
    expect(r.profileFailed).toBe(false)
    expect(r.profileKnown).toBe(true)
    expect(collectGaps({ profile: { email: 'a@b.c' } }).profileKnown).toBe(false)   // telefon alanı yok → bilinmiyor
    expect(collectGaps({ profile: null, profileFailed: true }).profileFailed).toBe(true)
  })

  it('aynı önemde PUANA göre sıralanır (en çok puan götüren üstte); puan başlığı takım satırında', () => {
    const r = collectGaps({
      profile: { email: 'a@b.c', phone: '05551112233' },
      teams: [{ id: 5, dq: { status: 'ok', data: { team: { id: 5, name: 'X', score: 72, band: 'NEEDS_ATTENTION', delta_7d: -3 }, rules: [
        rule('TEAM_NO_MANAGER', 'TEAM', 1, [], 'MEDIUM', 4),
        rule('TEAM_NO_ESCALATION', 'TEAM', 1, [], 'HIGH', 2),
        rule('TEAM_NO_NOTIFY_ADDRESS', 'TEAM', 1, [], 'HIGH', 9),
      ] } } }],
    })
    expect(r.byCategory.team.map((g) => g.code)).toEqual(['TEAM_NO_NOTIFY_ADDRESS', 'TEAM_NO_ESCALATION', 'TEAM_NO_MANAGER'])
    expect(r.teamScores).toEqual([{ id: 5, name: 'X', score: 72, band: 'NEEDS_ATTENTION', delta: -3 }])
  })

  it('kaynak durumları: okunamayan denetim failedChecks, yetkisiz denetim deniedChecks; okunan kapsamlar ayrı ayrı', () => {
    const r = collectGaps({
      profile: { email: 'a@b.c', phone: '05551112233' },
      prefsStatus: 'failed',
      teams: [{ id: 7, name: 'Yedi', dq: { status: 'ok', data: { team: { name: 'Yedi' }, rules: [] } }, oncall: { status: 'failed' } }],
      groups: { status: 'denied' }, forwards: { status: 'ok', data: [] }, channels: { status: 'unsupported' }, actions: { status: 'ok', data: { items: [], total: 0 } },
    })
    expect(r.failedChecks).toEqual([{ check: 'prefs', teamId: null, teamName: null }, { check: 'oncall', teamId: 7, teamName: 'Yedi' }])
    expect(r.deniedChecks).toEqual(['groups'])
    expect(r.unsupportedChecks).toEqual(['channels'])          // bu sürümde yapılamayan denetim yetki eksiği değildir
    expect(r.prefsFailed).toBe(true)
    expect([...r.readScopes].sort()).toEqual(['f7', 'kt', 'p', 't7'])
  })

  it('gizlenen eksik listeden ve işaretlerden çıkar, gizlenenler listesinde durur; allMarks gizlenenleri de taşır', () => {
    const input = { profile: { email: null, phone: '05551112233' }, teams: [{ id: 1, data: { rules: [rule('MON_NO_GROUP', 'MONITOR', 2)] } }] }
    const hidden = hideState(null, '1:MON_NO_GROUP').hidden
    const r = collectGaps({ ...input, hidden })
    expect(r.byCategory.monitors).toEqual([])
    expect(r.hiddenList.map((g) => g.key)).toEqual(['1:MON_NO_GROUP'])
    expect(r.marks.map((m) => m.key)).toEqual(['PROFILE_NO_EMAIL'])
    expect(r.allMarks.map((m) => m.key)).toEqual(['PROFILE_NO_EMAIL', '1:MON_NO_GROUP'])
    expect(r.total).toBe(1)
    expect(unhideState({ hidden }, '1:MON_NO_GROUP').hidden).toEqual([])
    // en çok MAX_HIDDEN, en yeniler kalır
    let s = null
    for (let i = 0; i < MAX_HIDDEN + 5; i++) s = hideState(s, `k${i}`)
    expect(s.hidden).toHaveLength(MAX_HIDDEN)
    expect(s.hidden.at(-1)).toBe(hashKey(`k${MAX_HIDDEN + 4}`))
    expect(s.hidden).not.toContain(hashKey('k0'))
  })

  it('takıma göre görünüm: profil dışı eksikler takım başına, takımsız işler en sonda, eksiksiz takım da puanıyla görünür', () => {
    const r = collectGaps({
      profile: { email: 'a@b.c', phone: '05551112233' },
      teams: [
        { id: 1, name: 'Bir', dq: { status: 'ok', data: { team: { name: 'Bir', score: 95, band: 'EXCELLENT' }, rules: [] } } },
        { id: 2, name: 'İki', dq: { status: 'ok', data: { team: { name: 'İki', score: 60, band: 'POOR' }, rules: [rule('TEAM_NO_ESCALATION', 'TEAM', 1)] } } },
      ],
      actions: { status: 'ok', data: { items: [{ id: 1, title: 'Aksiyon', due_date: '2026-11-01' }], total: 1 } },
    })
    const groups = groupByTeam(r)
    expect(groups.map((g) => g.teamId)).toEqual([2, 1, null])
    expect(groups[0].score.score).toBe(60)
    expect(groups[1].gaps).toEqual([])
    expect(groups[2].gaps.map((g) => g.code)).toEqual(['TASK_ACTIONS_OVERDUE'])
  })

  it('kendiliğinden gösterme: eksik yoksa hayır; erteleme 1 gün / 1 hafta; "gösterme" yeni eksik / artan sayı çıkana dek', () => {
    const now = 1_800_000_000_000
    const marks = [{ key: 'PROFILE_NO_EMAIL', n: 1, scope: 'p' }, { key: '4:MON_NO_GROUP', n: 2, scope: 't4' }]
    expect(shouldPrompt(null, [], now)).toBe(false)
    expect(shouldPrompt(null, marks, now)).toBe(true)
    const snoozed = snoozeState(null, now)
    expect(snoozed.snoozeUntil).toBe(now + SNOOZE_MS)
    expect(snoozeState(null, now, SNOOZE_WEEK_MS).snoozeUntil).toBe(now + SNOOZE_WEEK_MS)
    expect(snoozeState(null, now, 999).snoozeUntil).toBe(now + SNOOZE_MS)            // bilinmeyen süre → 1 gün
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

  it('gizlenen ve kendiliğinden yolun dışındaki (sonradan denetlenen takım) eksikler pencereyi açmaz, "gösterme" kaydına girmez', () => {
    const now = 1_800_000_000_000
    const outside = { key: '30:MON_NO_GROUP', n: 1, scope: 't30', auto: false }
    expect(shouldPrompt(null, [outside], now)).toBe(false)
    const hidden = hideState(null, '4:X')
    expect(shouldPrompt(hidden, [{ key: '4:X', n: 1, scope: 't4' }], now)).toBe(false)
    expect(dismissState(null, [outside, { key: 'P', n: 1, scope: 'p' }]).dismissed.map((e) => e[0])).toEqual([hashKey('P')])
  })

  it('çok kalemli eksik (kural başına 500+ kayıt): "gösterme" yine çalışır — eskiden kalem düzeyi 500 sınırını aşıyordu', () => {
    const now = 1_800_000_000_000
    const many = [{ key: '7:INV_NO_CONTACTS', n: 300, scope: 't7' }, { key: '7:MON_NO_GROUP', n: 250, scope: 't7' }]
    const d = dismissState(null, many)
    expect(d.dismissed).toHaveLength(2)
    expect(shouldPrompt(d, many, now)).toBe(false)
    expect(shouldPrompt(d, [{ ...many[0], n: 301 }, many[1]], now)).toBe(true)
  })

  it('okunamayan takımın / denetimin eski "gösterme" kaydı korunur; okunan kapsamlarınki şimdiki eksiklerle değişir', () => {
    const now = 1_800_000_000_000
    const first = dismissState(null, [{ key: '1:A', n: 2, scope: 't1' }, { key: '2:B', n: 1, scope: 't2' }, { key: '3:TEAM_ONCALL_MEMBERS', n: 1, scope: 'o3' }])
    // Takım 2 ve takım 3'ün nöbet çizelgesi bu kez okunamadı: kayıtları korunur; takım 1'in eski kaydı yerini yeni eksiğe bıraktı
    const second = dismissState(first, [{ key: '1:C', n: 1, scope: 't1' }], [2], ['o3'])
    expect(second.dismissed.map((e) => e[0]).sort()).toEqual([hashKey('1:C'), hashKey('2:B'), hashKey('3:TEAM_ONCALL_MEMBERS')].sort())
    expect(shouldPrompt(second, [{ key: '2:B', n: 1, scope: 't2' }], now)).toBe(false)
    expect(shouldPrompt(second, [{ key: '1:A', n: 2, scope: 't1' }], now)).toBe(true)   // düzelip geri gelen eksik yeni sayılır
  })

  it('Yeni / düzeldi: taban yoksa hiçbiri; okunan kapsamda yeni eksik "yeni", kaybolan "düzeldi"; okunamayan kapsam düzelmiş sayılmaz', () => {
    const m1 = [{ key: 'p:A', n: 1, scope: 'p' }, { key: '1:X', n: 2, scope: 't1' }, { key: '2:Y', n: 1, scope: 't2' }]
    const first = seenDiff(null, m1, new Set(['p', 't1', 't2']))
    expect(first.first).toBe(true)
    expect([...first.newKeys]).toEqual([])
    expect(first.fixed).toBe(0)
    const seen = markSeen(null, m1, new Set(['p', 't1', 't2', 'o1']))
    // ikinci okuma: 1:X düzeldi, 1:Z yeni, takım 2 okunamadı (2:Y yok ama düzelmiş sayılmaz), nöbet kapsamında yeni eksik
    const m2 = [{ key: 'p:A', n: 1, scope: 'p' }, { key: '1:Z', n: 1, scope: 't1' }, { key: '1:TEAM_ONCALL_MEMBERS', n: 1, scope: 'o1' }]
    const d = seenDiff(seen, m2, new Set(['p', 't1', 'o1']))
    expect(d.first).toBe(false)
    expect([...d.newKeys].sort()).toEqual(['1:TEAM_ONCALL_MEMBERS', '1:Z'])
    expect(d.fixed).toBe(1)
    // daha önce hiç okunmamış kapsam (yeni takım) "yeni" denmez
    expect([...seenDiff(seen, [{ key: '9:Q', n: 1, scope: 't9' }], new Set(['t9'])).newKeys]).toEqual([])
    // okunamayan kapsamın eski kaydı korunur
    const after = markSeen(seen, m2, new Set(['p', 't1', 'o1']))
    expect(parseSeen(readState(after).seen).has(hashKey('t2'))).toBe(true)
  })

  it('saklanan durum: bozuk / sahte değer atılır; özetler kısa ve sınırlı; ham kimlik / ad yazılmaz; eklemeli alanlar', () => {
    expect(readState('{bozuk')).toEqual(EMPTY)
    expect(readState({ snoozeUntil: 'x', dismissed: ['abcdef12', ['zz', 1, 'abcdef12'], ['abcdef12', -1, 'abcdef12'],
      ['abcdef12', 2, 'abcdef12'], ['abcdef12', 1.5, 'abcdef12']], hidden: ['xyz', 'abcdef12', 'abcdef12'], seen: 'bozuk;zz:aa' }))
      .toEqual({ ...EMPTY, dismissed: [['abcdef12', 2, 'abcdef12']], hidden: ['abcdef12'] })
    // önceki sürümün kaydı (hidden / seen yok) okunur
    expect(readState({ v: 2, snoozeUntil: null, dismissed: [['abcdef12', 1, 'abcdef12']] }).dismissed).toHaveLength(1)
    const marks = [...Array(MAX_DISMISSED + 50)].map((_, i) => ({ key: `${i}:MON_NO_GROUP`, n: 1, scope: `t${i}` }))
    const d = dismissState(null, marks)
    expect(d.dismissed).toHaveLength(MAX_DISMISSED)
    expect(JSON.stringify(d)).not.toMatch(/MON_NO_GROUP/)
    expect(hashKey('a')).toMatch(/^[0-9a-f]{8}$/)
    expect(hashKey('a')).not.toBe(hashKey('b'))
    expect(formatSeen(parseSeen('abcdef12:0000000a.3,0000000b.0'))).toBe('abcdef12:0000000a.3,0000000b.0')
    // Kapsam bütün yazılır ya da hiç: sığmayan kapsam yarım kalmaz (yarım kapsamın eksik kalanları her açılışta "yeni" olurdu)
    const big = new Map([['aaaaaaaa', new Map([...Array(MAX_SEEN - 1)].map((_, i) => [i.toString(16).padStart(8, '0'), 1]))]])
    big.set('bbbbbbbb', new Map([['0000ffff', 1], ['0000fffe', 1]]))
    big.set('cccccccc', new Map([['0000eeee', 2]]))
    const back = parseSeen(formatSeen(big))
    expect(back.has('bbbbbbbb')).toBe(false)
    expect(back.get('cccccccc')?.size).toBe(1)
    expect(back.get('aaaaaaaa')?.size).toBe(MAX_SEEN - 1)
  })

  it('EN KÖTÜ durum kullanıcı tercihi anahtar sınırına sığar (aşarsa sunucu bütün tercih belgesini 400 ile reddeder)', () => {
    let s = null
    const big = [...Array(MAX_DISMISSED + 100)].map((_, i) => ({ key: `${i}:RULE_${i}`, n: 99_999, scope: `t${i % 40}` }))
    s = dismissState(s, big)
    for (let i = 0; i < MAX_HIDDEN + 20; i++) s = hideState(s, `gizli-${i}`)
    s = markSeen(s, big, new Set(big.map((m) => m.scope)))
    s = snoozeState(s, 1_800_000_000_000, SNOOZE_WEEK_MS)
    const json = JSON.stringify(s)
    expect(readState(json).dismissed).toHaveLength(MAX_DISMISSED)
    expect([...parseSeen(s.seen).values()].reduce((n, m) => n + m.size, 0)).toBeLessThanOrEqual(MAX_SEEN)
    expect(json.length).toBeLessThan(MAX_LOCAL_VALUE - 512)
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
    expect(readSession('{x')).toEqual({ checkedAt: null, closedAt: null, count: null })
    expect(readSession({ count: 3 }).count).toBe(3)
    expect(readSession({ count: -1 }).count).toBeNull()
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

  it('kullanıcı uğraşıyor mu: açık menü / açılır kutu ya da son girdiden AUTO_QUIET_MS geçmediyse evet; girdi yoksa hayır', () => {
    expect(userInteracting({ now: 10_000, lastInputAt: null, popupOpen: false })).toBe(false)
    expect(userInteracting({ now: 10_000, lastInputAt: null, popupOpen: true })).toBe(true)
    expect(userInteracting({ now: 10_000, lastInputAt: 10_000 - AUTO_QUIET_MS + 1, popupOpen: false })).toBe(true)
    expect(userInteracting({ now: 10_000, lastInputAt: 10_000 - AUTO_QUIET_MS, popupOpen: false })).toBe(false)
    expect(userInteracting({ now: 10_000, lastInputAt: 10_000 - AUTO_QUIET_MS - 5000, popupOpen: true })).toBe(true)
    // saat geri gitmiş gibi (son girdi "gelecekte"): temkinli — uğraşıyor sayılır
    expect(userInteracting({ now: 10_000, lastInputAt: 12_000, popupOpen: false })).toBe(true)
    expect(userInteracting({ now: Number.NaN, lastInputAt: 1, popupOpen: false })).toBe(false)
    // bekleme sınırlı ve aralıklı: sınır, sessizlik süresinden çok uzun; bakış aralığı sessizlikten kısa
    expect(AUTO_DEFER_MAX_MS).toBeGreaterThan(AUTO_QUIET_MS * 10)
    expect(AUTO_DEFER_POLL_MS).toBeLessThan(AUTO_QUIET_MS)
  })
})
