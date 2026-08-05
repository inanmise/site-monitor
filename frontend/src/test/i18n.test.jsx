import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { LangProvider, useT, useLanguage } from '../i18n/index.jsx'

const STORAGE_KEY = 'site-monitor-lang'

function wrapper({ children }) {
  return <LangProvider>{children}</LangProvider>
}

beforeEach(() => {
  localStorage.removeItem(STORAGE_KEY)
})

afterEach(() => {
  localStorage.removeItem(STORAGE_KEY)
})

describe('useT', () => {
  it('default lang=en → returns English value for stat.total', () => {
    const { result } = renderHook(() => useT(), { wrapper })
    expect(result.current('stat.total')).toBe('Total')
  })

  it('Turkish lang → returns Turkish value for stat.total', () => {
    localStorage.setItem(STORAGE_KEY, 'tr')
    const { result } = renderHook(() => useT(), { wrapper })
    expect(result.current('stat.total')).toBe('Toplam')
  })

  it('single placeholder {0} is replaced', () => {
    // login.rateLimited = 'Too many failed attempts. Please wait {0} seconds.'
    const { result } = renderHook(() => useT(), { wrapper })
    const out = result.current('login.rateLimited', 30)
    expect(out).toContain('30')
    expect(out).not.toContain('{0}')
  })

  it('multiple placeholders are all replaced', () => {
    // pg.range (EN) = '{0}–{1} of {2} records'
    const { result } = renderHook(() => useT(), { wrapper })
    const out = result.current('pg.range', 1, 5, 100)
    expect(out).toContain('1')
    expect(out).toContain('5')
    expect(out).toContain('100')
    expect(out).not.toContain('{0}')
    expect(out).not.toContain('{1}')
    expect(out).not.toContain('{2}')
  })

  it('missing key → returns key as-is', () => {
    const { result } = renderHook(() => useT(), { wrapper })
    expect(result.current('no.such.key')).toBe('no.such.key')
  })

  it('null arg → does not throw, replaces with empty string', () => {
    const { result } = renderHook(() => useT(), { wrapper })
    expect(() => result.current('login.rateLimited', null)).not.toThrow()
  })
})

describe('useLanguage', () => {
  it('toggle switches lang from en to tr', () => {
    const { result } = renderHook(() => useLanguage(), { wrapper })
    expect(result.current.lang).toBe('en')

    act(() => {
      result.current.toggle()
    })

    expect(result.current.lang).toBe('tr')
  })
})
