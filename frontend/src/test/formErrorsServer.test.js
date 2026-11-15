import { describe, it, expect } from 'vitest'
import { serverFieldErrors } from '../utils/formErrors.js'

// Sunucunun 400 VALIDATION_FAILED gövdesinden alan → ileti haritası (2026-11-15, yayın öncesi e2e B)
describe('serverFieldErrors', () => {
  it('field + fields nesnesi: field iletisi gövdenin error metni', () => {
    expect(serverFieldErrors({ field: 'a', fields: { a: 'x', b: 'B iletisi' }, error: 'A iletisi' }))
      .toEqual({ a: 'A iletisi', b: 'B iletisi' })
  })
  it('fields dizi biçimi ({field, message})', () => {
    expect(serverFieldErrors({ fields: [{ field: 'from', message: 'Tarih geçersiz' }] })).toEqual({ from: 'Tarih geçersiz' })
  })
  it('resolve null dönen ad haritaya girmez; hiç alan yoksa null', () => {
    expect(serverFieldErrors({ field: 'gizli', error: 'x' }, () => null)).toBeNull()
    expect(serverFieldErrors({ field: 'a', error: 'x' }, (n) => `k.${n}`)).toEqual({ 'k.a': 'x' })
    expect(serverFieldErrors({ success: false, error: 'genel' })).toBeNull()
    expect(serverFieldErrors(null)).toBeNull()
  })
})
