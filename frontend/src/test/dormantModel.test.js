import { describe, it, expect } from 'vitest'
import {
  BUCKETS, DEFAULT_SORT, EMPTY_FILTERS, NO_TEAM, bucketOf, bulkWizardParams, daysSince, dormantCsv, dormantMatches,
  dormantStats, enrichDormant, facetCount, facetOptions, hasAnyFilter, inactiveDaysOf, minDaysOf, pctOf, sortDormant,
  sourceOf, teamKeyOf, toggleValue, usersPageParams,
} from '../components/admin/useractivity/dormant/dormantModel.js'
import { formFromParams, buildCriteria } from '../components/admin/bulkDeactivateModel.js'

/**
 * Atıl hesaplar görünümünün saf modeli (2026-10-09): kova sınırları, sunucu gün sayısı önceliği, istatistikler,
 * süzgeç + faset sayıları, sıralama, CSV ve toplu pasife alma / Kullanıcılar sayfası derin bağlantı paramları.
 */
const NOW = Date.parse('2026-10-09T12:00:00Z')
const ago = (d) => new Date(NOW - d * 86_400_000).toISOString().slice(0, 19)

const ROWS = [
  { username: 'old400', display_name: 'Zeynep Kaya (Bölüm X)', system_role: 'USER', team_id: 5, team_name: 'Takım A', auth_source: 'LDAP', last_login_at: ago(400), inactive_days: 400, created_at: ago(900), has_email: true },
  { username: 'old200', display_name: 'Ali Veli', system_role: 'AUDIT', team_id: 9, team_name: 'Takım B', auth_source: 'LOCAL', last_login_at: ago(200), inactive_days: 200, has_email: false, permanent_lock: true },
  { username: 'old100', display_name: 'Çağla Demir', system_role: 'USER', team_id: 5, team_name: 'Takım A', auth_source: 'LDAP', last_login_at: ago(100) },
  { username: 'old45', display_name: 'Ömer Şahin', system_role: 'TEAM_ADMIN', team_id: null, team_name: null, auth_source: null, last_login_at: ago(45), inactive_days: 45 },
  { username: 'neverOld', display_name: 'Bora Ak', system_role: 'USER', team_id: 9, team_name: 'Takım B', auth_source: 'LDAP', last_login_at: null, account_age_days: 300 },
  { username: 'neverNew', display_name: 'Ece Su', system_role: 'USER', team_id: 5, team_name: 'Takım A', auth_source: 'LOCAL', last_login_at: null, account_age_days: 3 },
]
const items = () => enrichDormant(ROWS, NOW)

describe('dormantModel — gün ve kova', () => {
  it('kova sınırları: 30–89 / 90–179 / 180–364 / 365+ / hiç', () => {
    expect(BUCKETS).toEqual(['d30', 'd90', 'd180', 'd365', 'never'])
    expect([null, 0, 29, 30, 89, 90, 179, 180, 364, 365, 4000].map(bucketOf))
      .toEqual(['never', 'd30', 'd30', 'd30', 'd30', 'd90', 'd90', 'd180', 'd180', 'd365', 'd365'])
  })

  it('sunucu inactive_days önceliklidir; yoksa last_login_at\'ten hesaplanır; hiç girmemişte null', () => {
    expect(inactiveDaysOf({ last_login_at: ago(10), inactive_days: 77 }, NOW)).toBe(77)
    expect(inactiveDaysOf({ last_login_at: ago(100) }, NOW)).toBe(100)
    expect(inactiveDaysOf({ last_login_at: null, inactive_days: 5 }, NOW)).toBeNull()
    expect(daysSince('2026-10-09T12:00:00Z', NOW)).toBe(0)
    expect(daysSince('2026-10-10T00:00:00', NOW)).toBe(0)            // gelecek → 0
    expect(daysSince('bozuk', NOW)).toBeNull()
    expect(daysSince('', NOW)).toBeNull()
  })

  it('zenginleştirme: kaynak (boş → LOCAL), takım anahtarı (kimlik / ad / takımsız), yeni hesap, sıralama ölçütü', () => {
    const byName = Object.fromEntries(items().map((r) => [r.username, r]))
    expect(byName.old45.source).toBe('LOCAL')
    expect(byName.old400.source).toBe('LDAP')
    expect(sourceOf({ auth_source: 'saml' })).toBe('SAML')
    expect(byName.old45.team_key).toBe(NO_TEAM)
    expect(teamKeyOf({ team_name: 'Eski Takım' })).toBe('name:Eski Takım')
    expect(teamKeyOf({ team_id: 0 })).toBe('0')
    expect(byName.neverNew.is_new).toBe(true)
    expect(byName.neverOld.is_new).toBe(false)
    expect(byName.neverOld.idle).toBe(300)                            // hiç girmemiş → hesap yaşı
    expect(enrichDormant([{ username: 'x', last_login_at: null }], NOW)[0].idle).toBe(Number.POSITIVE_INFINITY)
    expect(byName.old100.bucket).toBe('d90')
    // asıl alanlar korunur
    expect(byName.old400.display_name).toBe('Zeynep Kaya (Bölüm X)')
  })
})

describe('dormantModel — istatistikler', () => {
  it('toplam, hiç girmemiş (+yeni), uzun süreli, kovalar, kaynak/rol/takım dağılımı, pay, ortanca ve en uzun', () => {
    const s = dormantStats(items(), 24)
    expect(s.total).toBe(6)
    expect(s.never).toBe(2)
    expect(s.neverNew).toBe(1)
    expect(s.longTerm).toBe(2)                                       // 200 + 400
    expect(s.buckets).toEqual({ d30: 1, d90: 1, d180: 1, d365: 1, never: 2 })
    expect(s.bySource).toEqual([{ key: 'LDAP', count: 3 }, { key: 'LOCAL', count: 3 }])
    expect(s.byRole[0]).toEqual({ key: 'USER', count: 4 })
    expect(s.teams.map((x) => [x.label, x.count])).toEqual([['Takım A', 3], ['Takım B', 2]])
    expect(s.noTeam).toBe(1)
    expect(s.share).toBe(25)                                          // 6 / 24
    expect(s.totalUsers).toBe(24)
    expect(s.medianDays).toBe(150)                                    // [45,100,200,400] → (100+200)/2
    expect(s.maxDays).toBe(400)
    expect(s.noEmail).toBe(1)
    expect(s.locked).toBe(1)
  })

  it('boş liste ve bilinmeyen toplam: sıfırlar, pay null, ortanca null', () => {
    const s = dormantStats([], undefined)
    expect(s.total).toBe(0)
    expect(s.share).toBeNull()
    expect(s.medianDays).toBeNull()
    expect(s.maxDays).toBeNull()
    expect(s.teams).toEqual([])
  })

  it('pctOf: 0 < pay < 1 → 1 (çubuk görünsün), sınır 100, payda 0 → 0', () => {
    expect(pctOf(1, 1000)).toBe(1)
    expect(pctOf(0, 10)).toBe(0)
    expect(pctOf(5, 0)).toBe(0)
    expect(pctOf(12, 10)).toBe(100)
    expect(pctOf(1, 3)).toBe(33)
  })
})

describe('dormantModel — süzgeç, faset sayıları, sıralama', () => {
  it('arama ad / kullanıcı adı / takım / bölüm üzerinde, Türkçe harf duyarsız', () => {
    const list = items()
    const hit = (q) => list.filter((r) => dormantMatches(r, { ...EMPTY_FILTERS, q })).map((r) => r.username)
    expect(hit('ÇAĞLA')).toEqual(['old100'])
    expect(hit('takım b')).toEqual(['old200', 'neverOld'])
    expect(hit('bölüm x')).toEqual(['old400'])
    expect(hit('OLD4')).toEqual(['old400', 'old45'])
  })

  it('fasetler VE ile birleşir; faset içi seçenekler VEYA', () => {
    const list = items()
    const f = { ...EMPTY_FILTERS, bucket: ['never', 'd365'], source: ['LDAP'] }
    expect(list.filter((r) => dormantMatches(r, f)).map((r) => r.username)).toEqual(['old400', 'neverOld'])
    expect(list.filter((r) => dormantMatches(r, { ...EMPTY_FILTERS, team: [NO_TEAM] })).map((r) => r.username)).toEqual(['old45'])
    expect(facetCount(f)).toBe(3)
    expect(hasAnyFilter(EMPTY_FILTERS)).toBe(false)
    expect(hasAnyFilter({ ...EMPTY_FILTERS, q: '  ' })).toBe(false)
    expect(hasAnyFilter({ ...EMPTY_FILTERS, q: 'a' })).toBe(true)
    expect(toggleValue(['a'], 'b')).toEqual(['a', 'b'])
    expect(toggleValue(['a', 'b'], 'a')).toEqual(['b'])
  })

  it('faset sayısı = diğer süzgeçler uygulanmışken o seçenekle kalacak satır; kovalar sabit sırada, takımsız sonda', () => {
    const opts = facetOptions(items(), { ...EMPTY_FILTERS, source: ['LDAP'] })
    expect(opts.bucket).toEqual([
      { value: 'd30', count: 0 }, { value: 'd90', count: 1 }, { value: 'd180', count: 0 }, { value: 'd365', count: 1 }, { value: 'never', count: 1 },
    ])
    // kaynak fasetinin sayıları kendi seçiminden bağımsız (tüm kaynaklar görünür)
    expect(opts.source.map((o) => [o.value, o.count])).toEqual([['LDAP', 3], ['LOCAL', 3]])
    const all = facetOptions(items(), EMPTY_FILTERS)
    expect(all.team.map((o) => o.value)).toEqual(['5', '9', NO_TEAM])
    expect(all.team[0].name).toBe('Takım A')
    // seçili ama artık satırı olmayan değer listede kalır (kaldırılabilsin)
    expect(facetOptions(items(), { ...EMPTY_FILTERS, role: ['GHOST'] }).role.some((o) => o.value === 'GHOST' && o.count === 0)).toBe(true)
  })

  it('varsayılan sıralama en uzun süredir girmeyen önce (hiç girmemişte hesap yaşı); diğer sıralamalar kararlı', () => {
    expect(DEFAULT_SORT).toBe('idle_desc')
    const names = (sort) => sortDormant(items(), sort).map((r) => r.username)
    expect(names('idle_desc')).toEqual(['old400', 'neverOld', 'old200', 'old100', 'old45', 'neverNew'])
    expect(names('idle_asc')).toEqual(['neverNew', 'old45', 'old100', 'old200', 'neverOld', 'old400'])
    // Türkçe harmanlama: Ali < Bora < Çağla < Ece < Ömer < Zeynep (bölüm eki ayıklanmış ad)
    expect(names('name_asc')).toEqual(['old200', 'neverOld', 'old100', 'neverNew', 'old45', 'old400'])
    expect(names('team_asc')).toEqual(['old400', 'old100', 'neverNew', 'neverOld', 'old200', 'old45'])
  })
})

describe('dormantModel — CSV ve derin bağlantılar', () => {
  it('CSV: BOM, başlık, kova etiketi, gün sayıları; kimlik izi (IP) sütunu YOK; formül nötrlenir', () => {
    const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
    const rows = enrichDormant([...ROWS, { username: '=cmd', display_name: '@x', last_login_at: ago(31), ip: '10.0.0.1', last_login_ip: '10.0.0.2' }], NOW)
    const csv = dormantCsv(rows, t)
    expect(csv.charCodeAt(0)).toBe(0xFEFF)
    const lines = csv.trim().split('\r\n')
    expect(lines).toHaveLength(rows.length + 1)
    expect(lines[0]).toContain('dorm.colBucket')
    expect(lines[0].split(',')).toHaveLength(13)
    expect(csv).not.toContain('10.0.0.')
    expect(lines[1]).toContain('dorm.bucket.d365')
    expect(lines[1]).toContain(',400,')
    expect(csv).toContain("'=cmd")
    expect(csv).toContain("'@x")
  })

  it('toplu pasife alma paramları: en küçük kova eşiği, hiç girmemiş, tek kaynak/rol, yalnız kimlikli takımlar', () => {
    expect(minDaysOf([])).toBe(30)
    expect(minDaysOf(['d365', 'd90'])).toBe(90)
    expect(minDaysOf(['never'])).toBe(30)
    expect(bulkWizardParams(EMPTY_FILTERS)).toEqual({ g_tab: 'users', g_bd: '1', g_bd_days: '30', g_bd_never: '1' })
    expect(bulkWizardParams({ ...EMPTY_FILTERS, bucket: ['d180', 'd365'], source: ['LDAP'], role: ['USER'], team: ['5', '9'] }))
      .toEqual({ g_tab: 'users', g_bd: '1', g_bd_days: '180', g_bd_never: '0', g_bd_src: 'LDAP', g_bd_role: 'USER', g_bd_teams: '5,9' })
    // ADMIN rolü sihirbazda seçilemez; iki kaynak; takımsız karışık takım seçimi → kapsam geniş kalır
    expect(bulkWizardParams({ ...EMPTY_FILTERS, role: ['ADMIN'], source: ['LDAP', 'LOCAL'], team: ['5', NO_TEAM] }))
      .toEqual({ g_tab: 'users', g_bd: '1', g_bd_days: '30', g_bd_never: '1' })
  })

  it('üretilen paramlar sihirbaz formuna birebir döner (uçtan uca sözleşme)', () => {
    const p = bulkWizardParams({ ...EMPTY_FILTERS, bucket: ['d90', 'never'], source: ['LOCAL'], team: ['9'] })
    const form = formFromParams((k, fb = null) => p[k] ?? fb)
    expect(buildCriteria(form)).toEqual({
      scope: 'teams', auth_source: 'LOCAL', team_ids: [9], inactive_days: 90, include_never_logged_in: true,
    })
  })

  it('Kullanıcılar sayfası süzgeci: 30 / 90 / 180 / hiç (365 → 180, karışık → en küçük)', () => {
    expect(usersPageParams(EMPTY_FILTERS)).toEqual({ g_tab: 'users', g_dormant: '30' })
    expect(usersPageParams({ ...EMPTY_FILTERS, bucket: ['never'] })).toEqual({ g_tab: 'users', g_dormant: 'never' })
    expect(usersPageParams({ ...EMPTY_FILTERS, bucket: ['d365'] })).toEqual({ g_tab: 'users', g_dormant: '180' })
    expect(usersPageParams({ ...EMPTY_FILTERS, bucket: ['d90', 'd365'] })).toEqual({ g_tab: 'users', g_dormant: '90' })
    expect(usersPageParams({ ...EMPTY_FILTERS, bucket: ['never', 'd180'] })).toEqual({ g_tab: 'users', g_dormant: '180' })
  })
})
