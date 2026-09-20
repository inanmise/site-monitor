import { describe, it, expect } from 'vitest'
import { PAGE_STATE_PARAMS } from '../hooks/useUrlQuerySync.js'

// Regression: ISSUE-003 — Aktivite Logu süzgeç paramları (atype/astatus/arange/aq) sekme değişiminde temizlenmiyor,
// ?tab=port&atype=PORT gibi bayat param başka sekmelere taşınıyordu.
// Found by /qa on 2026-09-20
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-20.md
describe('URL ad alanı — Aktivite Logu paramları sayfa durumu sayılır', () => {
  it('atype / astatus / arange / aq PAGE_STATE_PARAMS içinde (App.handleTabChange sekme değişince siler)', () => {
    for (const k of ['atype', 'astatus', 'arange', 'aq']) expect(PAGE_STATE_PARAMS).toContain(k)
  })
})
