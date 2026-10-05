import { describe, it, expect } from 'vitest'
import {
  THEME_IDS, DEFAULT_POLICY, sanitizePolicy, resolveTheme, counterpartTheme, defaultThemeOf, schemeOf, samePolicy,
} from '../theme/themes.js'

/** Saf tema kuralları (2026-10-05) — sunucu ThemeCatalog.effective ile aynı hoşgörülü politika. */
describe('theme/themes.js', () => {
  it('sanitizePolicy: bilinmeyen atılır, sıra katalog sırası, boş liste = sekizi', () => {
    expect(sanitizePolicy({ enabled: ['crucible', 'sepia', 'dark', 'light'], default: 'system' }))
      .toEqual({ enabled: ['light', 'dark', 'crucible'], default: 'system' })
    expect(sanitizePolicy({ enabled: [], default: 'system' }).enabled).toEqual([...THEME_IDS])
    expect(sanitizePolicy(null)).toEqual({ enabled: [...THEME_IDS], default: 'system' })
  })

  it('sanitizePolicy: geçersiz varsayılan güvenli değere düşer', () => {
    expect(sanitizePolicy({ enabled: ['light', 'dark'], default: 'crucible' }).default).toBe('system')
    expect(sanitizePolicy({ enabled: ['slag', 'parchment'], default: 'system' }).default).toBe('parchment')
    expect(sanitizePolicy({ enabled: ['slag', 'parchment'], default: 'slag' }).default).toBe('slag')
  })

  it('resolveTheme: açık seçim kazanır; kapalı / bilinmeyen / boş seçim → yönetici varsayılanı; system → işletim sistemi', () => {
    const p = { enabled: ['light', 'dark', 'crucible'], default: 'system' }
    expect(resolveTheme('crucible', p, false)).toBe('crucible')
    expect(resolveTheme('blueprint', p, false)).toBe('light')    // kapalı tema
    expect(resolveTheme('blueprint', p, true)).toBe('dark')
    expect(resolveTheme('sepia', p, false)).toBe('light')        // bilinmeyen
    expect(resolveTheme(null, p, true)).toBe('dark')
    expect(resolveTheme(null, { enabled: ['parchment', 'slag'], default: 'slag' }, false)).toBe('slag')
    expect(defaultThemeOf(DEFAULT_POLICY, false)).toBe('light')
  })

  it('counterpartTheme: şema karşılığı — önce temel tema, yoksa karşı şemadaki ilk açık tema, hiç yoksa null', () => {
    expect(counterpartTheme('light', THEME_IDS)).toBe('dark')
    expect(counterpartTheme('crucible', THEME_IDS)).toBe('light')
    expect(counterpartTheme('parchment', ['parchment', 'obsidian', 'slag'])).toBe('obsidian')
    expect(counterpartTheme('slag', ['slag', 'alloy'])).toBe('alloy')
    expect(counterpartTheme('slag', ['slag', 'crucible'])).toBeNull()
  })

  it('schemeOf / samePolicy', () => {
    expect(schemeOf('blueprint')).toBe('dark')
    expect(schemeOf('alloy')).toBe('light')
    expect(schemeOf('sepia')).toBe('light')
    expect(samePolicy({ enabled: ['light'], default: 'light' }, { enabled: ['light'], default: 'light' })).toBe(true)
    expect(samePolicy({ enabled: ['light'], default: 'light' }, { enabled: ['light', 'dark'], default: 'light' })).toBe(false)
  })
})
