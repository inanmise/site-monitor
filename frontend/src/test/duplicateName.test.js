import { describe, it, expect } from 'vitest'
import { duplicateName } from '../utils/duplicateName.js'

describe('duplicateName', () => {
  it('ilk kopya: sona " (Kopya)" ekler', () => {
    expect(duplicateName('Web Sunucu 1')).toBe('Web Sunucu 1 (Kopya)')
  })

  it('ardışık kopyalar: sayaç artar (Kopya → Kopya 2 → Kopya 3)', () => {
    expect(duplicateName('Web Sunucu 1 (Kopya)')).toBe('Web Sunucu 1 (Kopya 2)')
    expect(duplicateName('Web Sunucu 1 (Kopya 2)')).toBe('Web Sunucu 1 (Kopya 3)')
    expect(duplicateName('Web Sunucu 1 (Kopya 9)')).toBe('Web Sunucu 1 (Kopya 10)')
  })

  it('boş/null/whitespace ad → "(Kopya)"', () => {
    expect(duplicateName('')).toBe('(Kopya)')
    expect(duplicateName(null)).toBe('(Kopya)')
    expect(duplicateName(undefined)).toBe('(Kopya)')
    expect(duplicateName('   ')).toBe('(Kopya)')
  })

  it('ekleri yalnız SONDA yakalar (ad içindeki "(Kopya)" bozulmaz)', () => {
    expect(duplicateName('(Kopya) arşiv')).toBe('(Kopya) arşiv (Kopya)')
    expect(duplicateName('Kopya Merkezi')).toBe('Kopya Merkezi (Kopya)')
  })

  it('URL/host gibi adlarda da çalışır', () => {
    expect(duplicateName('callcenterfacewebmon1.example.com')).toBe('callcenterfacewebmon1.example.com (Kopya)')
  })
})
