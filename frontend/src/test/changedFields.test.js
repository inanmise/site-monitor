import { describe, it, expect } from 'vitest'
import { changedFields } from '../utils/changedFields.js'

describe('changedFields (2026-10-12) — kayıt gövdesi yalnız değişen alanlar', () => {
  it('değişmeyen alan gitmez; dizi / nesne değer düzeyinde karşılaştırılır; atlanan alan hiç gitmez', () => {
    const base = { a: 1, list: [1, 2], obj: { x: 1 }, stamp: '2026-10-12T09:00:00' }
    expect(changedFields({ ...base }, base)).toEqual({})
    expect(changedFields({ ...base, list: [1, 2] }, base)).toEqual({})
    expect(changedFields({ ...base, list: [2, 1] }, base)).toEqual({ list: [2, 1] })
    expect(changedFields({ ...base, obj: { x: 2 } }, base)).toEqual({ obj: { x: 2 } })
    expect(changedFields({ ...base, a: 2, stamp: 'yeni' }, base, ['stamp'])).toEqual({ a: 2 })
  })

  it('yüklenmiş hâl yoksa (ilk kayıt) tam gövde; boş / null güvenli', () => {
    expect(changedFields({ a: 1, b: null }, null)).toEqual({ a: 1, b: null })
    expect(changedFields(null, { a: 1 })).toEqual({})
    expect(changedFields({ a: '' }, { a: null })).toEqual({ a: '' })
  })
})
