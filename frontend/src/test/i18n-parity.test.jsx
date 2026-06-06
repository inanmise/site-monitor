import { describe, it, expect } from 'vitest'
import { TR, EN } from '../i18n/index.jsx'

/**
 * Localization regression guard.
 *
 * Catches the common mistake of adding a translation key to one language
 * dictionary and forgetting the other -- previously this only surfaced when
 * a Turkish operator stumbled across an English string in production.
 */
describe('i18n parity (TR ↔ EN)', () => {
  it('every TR key has an EN counterpart', () => {
    const missing = Object.keys(TR).filter((k) => !(k in EN))
    expect(missing, `EN translations missing for keys: ${missing.join(', ')}`).toEqual([])
  })

  it('every EN key has a TR counterpart', () => {
    const missing = Object.keys(EN).filter((k) => !(k in TR))
    expect(missing, `TR translations missing for keys: ${missing.join(', ')}`).toEqual([])
  })

  it('no empty translation values', () => {
    const trEmpty = Object.entries(TR)
      .filter(([, v]) => typeof v !== 'string' || !v.trim())
      .map(([k]) => k)
    const enEmpty = Object.entries(EN)
      .filter(([, v]) => typeof v !== 'string' || !v.trim())
      .map(([k]) => k)
    expect({ trEmpty, enEmpty }).toEqual({ trEmpty: [], enEmpty: [] })
  })

  it('placeholder counts match between TR and EN', () => {
    // If a TR key uses {0} and {1}, the EN counterpart must too (otherwise
    // formatting is silently lossy and user-visible text loses substitutions).
    function placeholders(str) {
      if (typeof str !== 'string') return new Set()
      const matches = str.match(/\{\d+\}/g) || []
      return new Set(matches)
    }
    const mismatches = []
    for (const key of Object.keys(TR)) {
      if (!(key in EN)) continue
      const trSet = placeholders(TR[key])
      const enSet = placeholders(EN[key])
      const trArr = [...trSet].sort()
      const enArr = [...enSet].sort()
      if (trArr.join(',') !== enArr.join(',')) {
        mismatches.push(`${key}: TR=${trArr.join(',') || '(none)'} EN=${enArr.join(',') || '(none)'}`)
      }
    }
    expect(mismatches, `Placeholder mismatch:\n  ${mismatches.join('\n  ')}`).toEqual([])
  })
})
