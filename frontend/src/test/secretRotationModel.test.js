import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  PHASE_TONE, ROTATION_FILTERS, locationTotal, normFilter, rotationPhase, splitLocations, countIncomplete,
} from '../components/admin/secret/secretRotationModel.js'

/** Gizli anahtar döndürme — saf model (2026-10-11): aşama, konum süzgeci, aşama metinlerinin iki dilde varlığı. */
const st = (key, totals, extra = {}) => ({ key, totals, locations: [], ...extra })

describe('rotationPhase', () => {
  it('güncel anahtar yoksa önce o', () => {
    expect(rotationPhase(st({ current_configured: false, previous_configured: true }, { previous: 3 }))).toBe('dev_key')
  })
  it('önceki anahtar + bekleyen değer → pending; kalmadı + sayım tam → done; sayım eksik → incomplete', () => {
    const key = { current_configured: true, previous_configured: true }
    expect(rotationPhase(st(key, { previous: 2 }))).toBe('pending')
    expect(rotationPhase(st(key, { previous: 0 }, { previous_removable: true }))).toBe('done')
    expect(rotationPhase(st(key, { previous: 0 }, { previous_removable: false }))).toBe('incomplete')
    // 2026-10-12: hiçbir anahtarla çözülemeyen değer varken "tüm değerler güncel anahtarda" (done) denmez
    expect(rotationPhase(st(key, { previous: 0, unreadable: 2 }, { previous_removable: true }))).toBe('done_unreadable')
    // sayım eksikse (tavan / okunamayan konum) önce o: kaldırma önerilmez
    expect(rotationPhase(st(key, { previous: 0, unreadable: 2 }, { previous_removable: false }))).toBe('incomplete')
  })
  it('önceki anahtar yokken okunamayan değer → unreadable; yoksa steady; durum yoksa null', () => {
    const key = { current_configured: true, previous_configured: false }
    expect(rotationPhase(st(key, { unreadable: 1 }))).toBe('unreadable')
    expect(rotationPhase(st(key, { current: 4 }))).toBe('steady')
    expect(rotationPhase(null)).toBeNull()
  })
  it('her aşamanın tonu ve TR + EN başlık/gövde metni var', () => {
    for (const p of Object.keys(PHASE_TONE)) {
      for (const dict of [TR, EN]) {
        expect(dict[`secret.rot.phase.${p}.title`], p).toBeTruthy()
        expect(dict[`secret.rot.phase.${p}.body`], p).toBeTruthy()
      }
    }
  })
})

describe('splitLocations', () => {
  const locs = [
    { id: 'ldap', label: 'LDAP', current: 0, previous: 1, unreadable: 0 },
    { id: 'smtp', label: 'SMTP', current: 1, previous: 0, unreadable: 0 },
    { id: 'http', label: 'HTTP', current: 0, previous: 0, unreadable: 2 },
    { id: 'push', label: 'Push', current: 0, previous: 0, unreadable: 0 },
    { id: 'ver', label: 'Sürümler', current: 0, previous: 0, unreadable: 0, error: 'okunamadı' },
  ]
  it('all: değeri ya da hatası olan konumlar; değersizler adlarıyla ayrı', () => {
    const { shown, empty } = splitLocations(locs, 'all')
    expect(shown.map((l) => l.id)).toEqual(['ldap', 'smtp', 'http', 'ver'])
    expect(empty).toEqual(['Push'])
  })
  it('yuva süzgeci yalnız o yuvada değeri olanları bırakır', () => {
    expect(splitLocations(locs, 'previous').shown.map((l) => l.id)).toEqual(['ldap'])
    expect(splitLocations(locs, 'unreadable').shown.map((l) => l.id)).toEqual(['http'])
    expect(splitLocations(undefined, 'all')).toEqual({ shown: [], empty: [] })
  })
  it('locationTotal + normFilter', () => {
    expect(locationTotal(locs[2])).toBe(2)
    expect(normFilter('previous')).toBe('previous')
    expect(normFilter('bogus')).toBe('all')
    expect(ROTATION_FILTERS).toContain('unreadable')
  })
})

describe('sayım eksik — önceki anahtar yok (2026-10-12)', () => {
  it('okunamayan ya da tavana takılan konum varken "her şey güncel" (steady) DENMEZ', () => {
    const base = { key: { current_configured: true, previous_configured: false }, totals: { current: 3, previous: 0, unreadable: 0 } }
    expect(rotationPhase({ ...base, locations: [{ id: 'a', current: 3 }] })).toBe('steady')
    expect(rotationPhase({ ...base, locations: [{ id: 'a', current: 3, truncated: true }] })).toBe('count_incomplete')
    expect(rotationPhase({ ...base, locations: [{ id: 'a', error: 'okunamadı' }] })).toBe('count_incomplete')
    expect(rotationPhase({ ...base, totals: { ...base.totals, unreadable: 1 }, locations: [{ id: 'a', error: 'x' }] })).toBe('unreadable')
    expect(PHASE_TONE.count_incomplete).toBe('warning')
  })

describe('tavanda kesik sayım + önceki 0 (2026-10-12)', () => {
  it('countIncomplete kesik konumu görür (onay metni "0 değer" demesin diye)', () => {
    expect(countIncomplete({ locations: [{ id: 'a', previous: 0, truncated: true }] })).toBe(true)
    expect(countIncomplete({ locations: [{ id: 'a', previous: 0 }] })).toBe(false)
    expect(countIncomplete(null)).toBe(false)
  })
})
})
