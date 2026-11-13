import { describe, it, expect } from 'vitest'
import {
  buildSearchParams, hasActiveDirectoryFilter, nextSort, safeSort, EMPTY_FILTERS,
  SEARCH_FIELDS, STATUS_VALUES, SORT_FIELDS, viewFromFilters, filtersFromView,
} from '../components/admin/usersDirectoryModel.js'
import { sameParams } from '../hooks/userPrefsModel.js'

describe('usersDirectoryModel — buildSearchParams', () => {
  it('boş süzgeç: varsayılanlar atlanır (field all, sort username asc), codeLoginReady false', () => {
    const p = buildSearchParams(EMPTY_FILTERS)
    expect(p.field).toBe('')          // all → atlanır
    expect(p.sort).toBe('')           // username asc → atlanır
    expect(p.dir).toBe('')
    expect(p.codeLoginReady).toBe(false)
    expect(p.status).toBe('')
    expect(p.hasPhone).toBe('')
    expect(p.q).toBe('')
  })

  it('tüm süzgeçler UI durumundan API parametrelerine eşlenir', () => {
    const p = buildSearchParams({
      q: '  Ali ', field: 'email', role: 'USER', orgRole: 'PO', team: '3', dormant: '90',
      status: 'active', source: 'LDAP', phone: 'yes', email: 'no', otp: true, noc: 'yes', online: 'no',
      loginFrom: '2026-01-01', loginTo: '2026-06-30', createdFrom: '2025-12-01', createdTo: '2026-12-31',
      sort: 'lastLogin', dir: 'desc',
    })
    expect(p).toMatchObject({
      q: 'Ali', field: 'email', systemRole: 'USER', orgRole: 'PO', teamId: '3',
      dormantDays: 90, neverLoggedIn: false, status: 'active', source: 'LDAP',
      hasPhone: 'yes', hasEmail: 'no', codeLoginReady: true, noc: 'yes', online: 'no',
      loginFrom: '2026-01-01', loginTo: '2026-06-30', createdFrom: '2025-12-01', createdTo: '2026-12-31',
      sort: 'lastLogin', dir: 'desc',
    })
  })

  it('dormant never → neverLoggedIn true, dormantDays boş', () => {
    const p = buildSearchParams({ dormant: 'never' })
    expect(p.neverLoggedIn).toBe(true)
    expect(p.dormantDays).toBe('')
  })

  it('username DESC: sort username gönderilir (yön desc sunucuya iletilsin diye)', () => {
    const p = buildSearchParams({ sort: 'username', dir: 'desc' })
    expect(p.sort).toBe('username')
    expect(p.dir).toBe('desc')
  })

  it('lastLogin ASC: sort gönderilir, dir atlanır (asc sunucu varsayılanı)', () => {
    const p = buildSearchParams({ sort: 'lastLogin', dir: 'asc' })
    expect(p.sort).toBe('lastLogin')
    expect(p.dir).toBe('')
  })

  it('bilinmeyen değerler atılır (geçersiz status/source/field/tri)', () => {
    const p = buildSearchParams({ field: 'xyz', status: 'nope', source: 'OTHER', phone: 'maybe', sort: 'hacker' })
    expect(p.field).toBe('')          // geçersiz field → atlanır (sunucu 'all')
    expect(p.status).toBe('')
    expect(p.source).toBe('')
    expect(p.hasPhone).toBe('')
    expect(p.sort).toBe('')           // geçersiz sort → username (varsayılan) → atlanır
  })
})

describe('usersDirectoryModel — nextSort', () => {
  it('yeni alan: o alanın varsayılan yönü (isim asc, son-giriş desc, durum desc)', () => {
    expect(nextSort({ sort: 'username', dir: 'asc' }, 'lastLogin')).toEqual({ sort: 'lastLogin', dir: 'desc' })
    expect(nextSort({ sort: 'lastLogin', dir: 'desc' }, 'username')).toEqual({ sort: 'username', dir: 'asc' })
    expect(nextSort({ sort: 'username', dir: 'asc' }, 'status')).toEqual({ sort: 'status', dir: 'desc' })
  })
  it('aynı alan: yön döner', () => {
    expect(nextSort({ sort: 'username', dir: 'asc' }, 'username')).toEqual({ sort: 'username', dir: 'desc' })
    expect(nextSort({ sort: 'username', dir: 'desc' }, 'username')).toEqual({ sort: 'username', dir: 'asc' })
  })
})

describe('usersDirectoryModel — yardımcılar', () => {
  it('safeSort bilinmeyeni username yapar', () => {
    expect(safeSort('lastLogin')).toBe('lastLogin')
    expect(safeSort('garbage')).toBe('username')
    expect(safeSort(undefined)).toBe('username')
  })
  it('hasActiveDirectoryFilter: q ve field sayılmaz; süzgeçler sayılır', () => {
    expect(hasActiveDirectoryFilter({ q: 'ali', field: 'email' })).toBe(false)
    expect(hasActiveDirectoryFilter({ status: 'active' })).toBe(true)
    expect(hasActiveDirectoryFilter({ online: 'yes' })).toBe(true)
    expect(hasActiveDirectoryFilter({ loginFrom: '2026-01-01' })).toBe(true)
    expect(hasActiveDirectoryFilter({ otp: true })).toBe(true)
    expect(hasActiveDirectoryFilter({})).toBe(false)
  })
  it('sabit listeler beklenen değerleri içerir (sunucu ile hizalı)', () => {
    expect(SEARCH_FIELDS).toEqual(['all', 'name', 'username', 'email', 'phone', 'employee'])
    expect(STATUS_VALUES).toEqual(['active', 'passive', 'locked', 'mustchange'])
    expect(SORT_FIELDS).toEqual(['username', 'lastLogin', 'created', 'role', 'status'])
  })
})

describe('usersDirectoryModel — kayıtlı görünümler (B7)', () => {
  const FULL = {
    q: 'ali', field: 'email', role: 'USER', orgRole: 'PO', team: '3', dormant: '90',
    status: 'locked', source: 'LDAP', phone: 'yes', email: 'no', otp: true, noc: 'yes', online: 'no',
    loginFrom: '2026-01-01', loginTo: '2026-06-30', createdFrom: '2025-12-01', createdTo: '2026-12-31',
    sort: 'lastLogin', dir: 'desc',
  }

  it('viewFromFilters: yalnız dolu süzgeç anahtarları, otp açıkken "1"; geçici alanlar (onlineIds) girmez', () => {
    const snap = viewFromFilters({ ...FULL, onlineIds: new Set([1]), extra: 'x' })
    expect(Object.keys(snap).sort()).toEqual(Object.keys(EMPTY_FILTERS).sort())
    expect(snap).toMatchObject({ ...FULL, otp: '1' })
    expect(snap.onlineIds).toBeUndefined()
    expect(snap.extra).toBeUndefined()
    // boş değerler ve kapalı otp yazılmaz (sunucu boşları atar → "etkin görünüm" eşleşmesi tutarlı)
    expect(viewFromFilters(EMPTY_FILTERS)).toEqual({ field: 'all', sort: 'username', dir: 'asc' })
  })

  it('sunucu gidiş-dönüşü: kapalı otp "false" metni olarak dönse bile açılmaz; eski "true" / true açık kalır', () => {
    // UserPreferencesService.normalizeParams her değeri metne çevirip boşları atar — aynısı burada
    const serverRoundTrip = (v) => Object.fromEntries(Object.entries(v).map(([k, x]) => [k, String(x)]).filter(([, x]) => x !== ''))
    const saved = serverRoundTrip(viewFromFilters({ ...EMPTY_FILTERS, status: 'locked' }))
    expect(filtersFromView(saved).otp).toBe(false)
    expect(sameParams(viewFromFilters({ ...EMPTY_FILTERS, status: 'locked' }), saved)).toBe(true)
    expect(filtersFromView({ otp: 'false' }).otp).toBe(false)   // 20.195–20.196 kayıtları
    expect(filtersFromView({ otp: 'true' }).otp).toBe(true)
    expect(filtersFromView({ otp: true }).otp).toBe(true)
  })

  it('filtersFromView: tam + doğrulanmış durum; eksik/bozuk değerler güvenli varsayılana döner', () => {
    expect(filtersFromView(FULL)).toEqual(FULL)
    // bozuk görünüm → güvenli varsayılan
    const bad = filtersFromView({ field: 'xyz', status: 'nope', source: 'OTHER', phone: 'maybe', sort: 'hack', dir: 'sideways', otp: 1, q: 42 })
    expect(bad.field).toBe('all')
    expect(bad.status).toBe('')
    expect(bad.source).toBe('')
    expect(bad.phone).toBe('')
    expect(bad.sort).toBe('username')
    expect(bad.dir).toBe('asc')
    expect(bad.otp).toBe(true)      // 1 → true
    expect(bad.q).toBe('')          // sayı → boş string
    // boş görünüm → EMPTY_FILTERS
    expect(filtersFromView({})).toEqual(EMPTY_FILTERS)
    expect(filtersFromView(null)).toEqual(EMPTY_FILTERS)
  })

  it('gidiş-dönüş: viewFromFilters → filtersFromView aynı süzgeç durumunu verir', () => {
    expect(filtersFromView(viewFromFilters(FULL))).toEqual(FULL)
    expect(filtersFromView(viewFromFilters(EMPTY_FILTERS))).toEqual(EMPTY_FILTERS)
  })
})
