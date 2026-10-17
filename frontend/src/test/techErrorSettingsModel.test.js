import { describe, it, expect } from 'vitest'
import {
  TES_FILTERS, TES_FLAG_FILTERS, TES_PERIOD_FILTERS, TES_SORTS, TES_TABLE_MIN_PX, channelPeriodStatus, countChannels, filterChannels,
  filtersActive, hasExecutives, isTeamHidden, matchesFilter, normalizeFilter, normalizeSort, searchChannels, sortChannels,
  teamEmailState, teamVisibleState, teamsOf,
} from '../components/admin/techErrorSettingsModel.js'

// Ayarlar → Teknik hata raporu — kanal özeti saf modeli (2026-10-17 yeniden tasarım). Sunucu satırı: id, name, active,
// team_id, team_name, team_visible, executive_count, approver_count, team_email_set, period_report.
const ch = (id, name, extra = {}) => ({
  id, name, active: true, team_id: 1, team_name: 'Dijital Kanallar', team_visible: true, executive_count: 0, approver_count: 0,
  team_email_set: true, period_report: null, ...extra,
})
const ROWS = [
  ch(1, 'Mobil', { executive_count: 2, approver_count: 1, period_report: { id: 100, status: 'sent' } }),
  ch(2, 'POS', { active: false, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, team_email_set: false }),
  ch(3, 'İnternet Şubesi', { team_id: 3, team_name: 'Bireysel Bankacılık', team_visible: false, team_email_set: false }),
  ch(4, 'ATM', { period_report: { id: 101, status: 'draft' } }),
  ch(5, 'Çağrı Merkezi', { team_id: 2, team_name: 'Ödeme Sistemleri', period_report: { id: 102, status: 'changed' } }),
]

describe('techErrorSettingsModel — durum ve bayraklar', () => {
  it('dönem durumu kanal listesi sayfasıyla aynı kural: pasif → inactive, gönderildi → sent, var ama gönderilmedi → open, yok → missing', () => {
    expect(channelPeriodStatus(ROWS[0])).toBe('sent')
    expect(channelPeriodStatus(ROWS[1])).toBe('inactive')
    expect(channelPeriodStatus(ROWS[2])).toBe('missing')
    expect(channelPeriodStatus(ROWS[3])).toBe('open')
    expect(channelPeriodStatus(ROWS[4])).toBe('open')
    expect(channelPeriodStatus(ch(9, 'x', { period_report: { status: 'queued' } }))).toBe('open')
  })

  it('bilinmeyen ≠ eksik: e-posta ve görünürlük yalnız AÇIK false ile eksik / kapalı; alan yoksa null (sayılmaz)', () => {
    expect(teamEmailState(ch(1, 'a'))).toBe(true)
    expect(teamEmailState(ch(1, 'a', { team_email_set: false }))).toBe(false)
    expect(teamEmailState(ch(1, 'a', { team_email_set: undefined }))).toBeNull()
    expect(teamEmailState({})).toBeNull()
    expect(teamVisibleState(ch(1, 'a'))).toBe(true)
    expect(teamVisibleState(ch(1, 'a', { team_visible: false }))).toBe(false)
    expect(teamVisibleState({})).toBeNull()
    expect(isTeamHidden({})).toBe(false)
    expect(isTeamHidden({ team_visible: null })).toBe(false)
    expect(hasExecutives(ch(1, 'a', { executive_count: '3' }))).toBe(true)
    expect(hasExecutives(ch(1, 'a', { executive_count: null }))).toBe(false)
    // Kısmi veri: eski sunucu satırlarında alan yoksa noEmail / hidden sayımı 0 kalır ama bu "hepsi tanımlı" demek değil —
    // arayüz rozeti "bilinmiyor" yazar (teamEmailState null)
    const counts = countChannels([{ id: 1, name: 'a', active: true }])
    expect(counts.noEmail).toBe(0)
    expect(counts.hidden).toBe(0)
    expect(teamEmailState({ id: 1, name: 'a', active: true })).toBeNull()
  })

  it('normalize: bilinmeyen süzgeç → all, bilinmeyen sıralama → name; sabit listeler', () => {
    expect(normalizeFilter('sent')).toBe('sent')
    expect(normalizeFilter('exec')).toBe('exec')
    expect(normalizeFilter('zzz')).toBe('all')
    expect(normalizeFilter(undefined)).toBe('all')
    expect(normalizeSort('team')).toBe('team')
    expect(normalizeSort('nope')).toBe('name')
    expect(TES_FILTERS).toEqual(['all', ...TES_PERIOD_FILTERS, ...TES_FLAG_FILTERS])
    expect(TES_SORTS).toEqual(['name', 'team', 'status'])
    expect(TES_TABLE_MIN_PX).toBeGreaterThan(0)
    expect(Object.isFrozen(TES_FILTERS)).toBe(true)
  })
})

describe('techErrorSettingsModel — sayım, arama, süzgeç', () => {
  it('sayımlar: sent / open / missing yalnız aktifler; inactive pasifler; exec / noEmail / hidden tüm kanallar', () => {
    expect(countChannels(ROWS)).toEqual({ total: 5, sent: 1, open: 2, missing: 1, inactive: 1, exec: 1, noEmail: 2, hidden: 2 })
    expect(countChannels([])).toEqual({ total: 0, sent: 0, open: 0, missing: 0, inactive: 0, exec: 0, noEmail: 0, hidden: 0 })
    expect(countChannels(null).total).toBe(0)
  })

  it('arama Türkçe katlar (kanal + takım adı): "internet" → İnternet Şubesi, "odeme" → Ödeme Sistemleri kanalları', () => {
    expect(searchChannels(ROWS, { q: 'internet' }).map((c) => c.id)).toEqual([3])
    expect(searchChannels(ROWS, { q: 'ODEME' }).map((c) => c.id)).toEqual([2, 5])
    expect(searchChannels(ROWS, { q: '  ' })).toHaveLength(5)
    expect(searchChannels(ROWS, { q: 'yok-böyle' })).toEqual([])
  })

  it('takım süzgeci kimlikle (sayı ya da metin), "all" / boş / null süzgeç yok', () => {
    expect(searchChannels(ROWS, { team: 2 }).map((c) => c.id)).toEqual([2, 5])
    expect(searchChannels(ROWS, { team: '2' }).map((c) => c.id)).toEqual([2, 5])
    expect(searchChannels(ROWS, { team: 'all' })).toHaveLength(5)
    expect(searchChannels(ROWS, { team: '' })).toHaveLength(5)
    expect(searchChannels(ROWS, { team: null })).toHaveLength(5)
    expect(searchChannels(ROWS, { team: 99 })).toEqual([])
  })

  it('matchesFilter: dönem durumları ve bayraklar; filterChannels üçünü VE ile birleştirir', () => {
    expect(ROWS.filter((c) => matchesFilter(c, 'sent')).map((c) => c.id)).toEqual([1])
    expect(ROWS.filter((c) => matchesFilter(c, 'open')).map((c) => c.id)).toEqual([4, 5])
    expect(ROWS.filter((c) => matchesFilter(c, 'missing')).map((c) => c.id)).toEqual([3])
    expect(ROWS.filter((c) => matchesFilter(c, 'inactive')).map((c) => c.id)).toEqual([2])
    expect(ROWS.filter((c) => matchesFilter(c, 'exec')).map((c) => c.id)).toEqual([1])
    expect(ROWS.filter((c) => matchesFilter(c, 'noEmail')).map((c) => c.id)).toEqual([2, 3])
    expect(ROWS.filter((c) => matchesFilter(c, 'hidden')).map((c) => c.id)).toEqual([2, 3])
    expect(ROWS.filter((c) => matchesFilter(c, 'all'))).toHaveLength(5)
    expect(ROWS.filter((c) => matchesFilter(c, 'garbage'))).toHaveLength(5)
    expect(filterChannels(ROWS, { team: 2, filter: 'noEmail' }).map((c) => c.id)).toEqual([2])
    expect(filterChannels(ROWS, { q: 'odeme', filter: 'open' }).map((c) => c.id)).toEqual([5])
    expect(filterChannels(ROWS, { q: 'odeme', team: 1 })).toEqual([])
    expect(filterChannels(ROWS)).toHaveLength(5)
  })

  it('filtersActive: boş arama / all takım / all süzgeç → kapalı; herhangi biri → açık', () => {
    expect(filtersActive({})).toBe(false)
    expect(filtersActive({ q: '   ', team: 'all', filter: 'all' })).toBe(false)
    expect(filtersActive({ q: 'a' })).toBe(true)
    expect(filtersActive({ team: '2' })).toBe(true)
    expect(filtersActive({ filter: 'hidden' })).toBe(true)
    expect(filtersActive({ filter: 'bilinmeyen' })).toBe(false)
  })
})

describe('techErrorSettingsModel — sıralama ve takımlar', () => {
  it('name: aktifler önce, Türkçe A→Z; girdi değişmez', () => {
    const input = [...ROWS]
    expect(sortChannels(input, 'name').map((c) => c.name)).toEqual(['ATM', 'Çağrı Merkezi', 'İnternet Şubesi', 'Mobil', 'POS'])
    expect(input.map((c) => c.id)).toEqual([1, 2, 3, 4, 5])
  })

  it('team: takım adı A→Z, aynı takımda aktifler önce, sonra ad', () => {
    expect(sortChannels(ROWS, 'team').map((c) => c.name)).toEqual(['İnternet Şubesi', 'ATM', 'Mobil', 'Çağrı Merkezi', 'POS'])
  })

  it('status: eksik → açık → gönderildi → pasif; aynı durumda takım e-postası eksik olan önce; sonra ad', () => {
    const rows = [...ROWS, ch(6, 'Şube', { team_email_set: false, period_report: { status: 'draft' } })]
    expect(sortChannels(rows, 'status').map((c) => c.name)).toEqual(['İnternet Şubesi', 'Şube', 'ATM', 'Çağrı Merkezi', 'Mobil', 'POS'])
    expect(sortChannels(rows, 'yok').map((c) => c.name)[0]).toBe('ATM')   // bilinmeyen → name
    expect(sortChannels(null)).toEqual([])
  })

  it('teamsOf: tekil takımlar, kimlik metin, ada göre Türkçe sıralı; takımsız satır atlanır', () => {
    expect(teamsOf([...ROWS, { id: 9, name: 'x' }])).toEqual([
      { id: '3', name: 'Bireysel Bankacılık' }, { id: '1', name: 'Dijital Kanallar' }, { id: '2', name: 'Ödeme Sistemleri' },
    ])
    expect(teamsOf([ch(1, 'a', { team_id: 7, team_name: null })])).toEqual([{ id: '7', name: '#7' }])
    expect(teamsOf(undefined)).toEqual([])
  })
})
