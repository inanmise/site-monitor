import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { eventLabel } from '../components/admin/audit/auditFormat.js'
import { readJavaMain } from './helpers/javaTable.js'

/**
 * DENETİM OLAY ETİKETİ KAPISI (20.165.1 bugfix, UIA-5). Denetim günlüğünde 238 türün 60'ı kurala (SPECIAL / nesne + eylem)
 * uymuyor, iki dilde de İngilizce Title-Case yedeğine düşüyordu ("Session Terminate", "Platform Create" …); 5'inin
 * `audit.ev.*` anahtarı vardı ama SPECIAL'da olmadığı için okunmuyordu. Hiçbir kapı kataloğu i18n'le karşılaştırmıyordu.
 *
 * Kural: `AuditEventCatalog.TYPES`'taki HER tür, TR ve EN'de yedeğe düşmeden (Title-Case ya da ham anahtar değil) etiketlenir.
 * Yeni bir tür eklendiğinde ya noun/verb kuralına uymalı ya da `auditFormat.js` SPECIAL'a + TR/EN `audit.ev.<TÜR>` eklenmeli.
 */
const java = readJavaMain('service/AuditEventCatalog.java')
const start = java.indexOf('TYPES = List.of(')
const TYPES = [...java.slice(start, java.indexOf(');', start)).matchAll(/"([A-Z0-9_]+)"/g)].map((m) => m[1])

const titleCase = (type) => type.toLowerCase().split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')
const tOf = (dict) => (k) => (dict[k] == null ? k : dict[k])

describe('denetim olay etiketleri — katalog kapısı', () => {
  it('katalog okundu (en az 200 tür)', () => {
    expect(TYPES.length).toBeGreaterThan(200)
  })

  it.each([['TR', TR], ['EN', EN]])('%s: hiçbir tür Title-Case yedeğine ya da ham anahtara düşmez', (_lang, dict) => {
    const t = tOf(dict)
    const fallback = TYPES.filter((type) => {
      const label = eventLabel(type, t)
      return label === titleCase(type) || label.startsWith('audit.')
    })
    expect(fallback, `yedeğe düşen türler: ${fallback.join(', ')}`).toEqual([])
  })

  it('SPECIAL anahtarları sözlükte gerçekten var (okunmayan "ölü" anahtar kalmadı)', () => {
    for (const type of ['EXECUTIVE_SUMMARY_TEAM_SETTINGS', 'EXECUTIVE_SUMMARY_TEST', 'ISSUE_REPORT_COMMENT', 'LOGIN_ISSUE_COMMENT', 'TECH_ERROR_REPORT_TEST_MAIL']) {
      expect(eventLabel(type, tOf(TR))).toBe(TR[`audit.ev.${type}`])
      expect(eventLabel(type, tOf(EN))).toBe(EN[`audit.ev.${type}`])
    }
  })
})
