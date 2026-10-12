import { describe, it, expect } from 'vitest'
import {
  addMember, anchorToUtc, buildPayload, defaultAnchor, isDirty, move, toForm, utcToAnchor, validate, validateOverride,
} from '../components/admin/oncall/oncallModel.js'

/** Nöbet çizelgesi saf modeli (2026-10-11): İstanbul ↔ UTC, form ↔ gövde, doğrulama, sıra işlemleri. */
describe('oncallModel', () => {
  it('İstanbul devir anı ↔ UTC (sabit +3); bozuk girdi boş', () => {
    expect(anchorToUtc('2026-10-05T09:00')).toBe('2026-10-05T06:00:00')
    expect(utcToAnchor('2026-10-05T06:00:00')).toBe('2026-10-05T09:00')
    expect(utcToAnchor(anchorToUtc('2026-12-31T23:30'))).toBe('2026-12-31T23:30')
    expect(anchorToUtc('05.10.2026')).toBe('')
    expect(utcToAnchor('')).toBe('')
  })

  it('varsayılan devir: önümüzdeki pazartesi 09:00 İstanbul', () => {
    // Çarşamba 2026-10-07 12:00 İstanbul → pazartesi 2026-10-12
    expect(defaultAnchor(Date.parse('2026-10-07T09:00:00Z'))).toBe('2026-10-12T09:00')
    // Pazartesi → bir sonraki pazartesi
    expect(defaultAnchor(Date.parse('2026-10-12T05:00:00Z'))).toBe('2026-10-19T09:00')
  })

  it('görünüm → form → gövde; kirli takibi; kimlikler olduğu gibi (opak dahil)', () => {
    const view = { enabled: true, rotation: 'DAILY', anchor: '2026-10-05T09:00', notify_oncall: true,
      members: [{ user_id: 'a1b2', name: 'Ayşe' }, { user_id: 7, name: 'Mehmet', active: false }] }
    const f = toForm(view)
    expect(f.members.map((m) => m.id)).toEqual(['a1b2', 7])
    expect(f.members[1].active).toBe(false)
    expect(buildPayload(f)).toEqual({ enabled: true, rotation: 'DAILY', anchor: '2026-10-05T09:00', notify_oncall: true, members: ['a1b2', 7] })
    expect(isDirty(f, f)).toBe(false)
    expect(isDirty({ ...f, notify: false }, f)).toBe(true)
    expect(toForm(null).rotation).toBe('WEEKLY')
  })

  it('doğrulama: devir anı biçimi, açıkken boş sıra, üst sınır; yer değiştirme sırası', () => {
    expect(validate({ anchor: 'x', enabled: false, members: [] })).toEqual({ anchor: 'oncall.err.anchor' })
    expect(validate({ anchor: '2026-10-05T09:00', enabled: true, members: [] })).toEqual({ members: 'oncall.err.noMembers' })
    expect(validate({ anchor: '2026-10-05T09:00', enabled: true, members: Array.from({ length: 51 }, (_, i) => ({ id: i })) }))
      .toEqual({ members: 'oncall.err.tooMany' })
    expect(validateOverride({ userId: '', start: '', end: '' })).toEqual({ user: 'oncall.err.ovrUser', start: 'oncall.err.ovrStart', end: 'oncall.err.ovrEnd' })
    expect(validateOverride({ userId: 3, start: '2026-10-10T10:00:00', end: '2026-10-10T09:00:00' })).toEqual({ end: 'oncall.err.ovrOrder' })
    expect(validateOverride({ userId: 3, start: '2026-10-10T10:00:00', end: '2026-10-11T09:00:00' })).toEqual({})
  })

  it('sıra: taşı (sınırda değişmez), aynı kişiyi iki kez ekleme', () => {
    const l = [{ id: 1 }, { id: 2 }, { id: 3 }]
    expect(move(l, 0, 1).map((x) => x.id)).toEqual([2, 1, 3])
    expect(move(l, 0, -1)).toBe(l)
    expect(move(l, 2, 1)).toBe(l)
    expect(addMember(l, { id: '2' })).toBe(l)
    expect(addMember(l, { id: 4 }).map((x) => x.id)).toEqual([1, 2, 3, 4])
  })
})
