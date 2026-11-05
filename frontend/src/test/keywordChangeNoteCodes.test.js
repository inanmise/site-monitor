import { describe, it, expect } from 'vitest'
import { readJavaMain } from './helpers/javaTable.js'
import { CHANGE_NOTE_CODES, changeNoteOf } from '../components/keyword/keywordCardModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Keyword içerik değişimi notları (2026-11-05 son inceleme) — ön yüz listesi `CHANGE_NOTE_CODES`, sunucunun
 * `KeywordCheckerService.CHANGE_SKIP_CODES` listesiyle (sıra dahil) aynı olmalı: sunucu yeni bir not eklerse ekran onu genel
 * "OTHER" metniyle gösterir ama kapı burada kırmızı olur, metin eklenir. Ayrıca 403 `KEYWORD_SAVED_SECRET_ORIGIN` kodunun adı.
 */
const SRC = readJavaMain('service/KeywordCheckerService.java')

function javaSkipCodes() {
  const consts = {}
  for (const m of SRC.matchAll(/public static final String (CHANGE_SKIP_[A-Z_]+)\s*=\s*"([A-Z_]+)"/g)) consts[m[1]] = m[2]
  const list = SRC.match(/CHANGE_SKIP_CODES\s*=\s*java\.util\.List\.of\(([^)]*)\)/)
  if (!list) throw new Error('CHANGE_SKIP_CODES Java kaynağında yok')
  return list[1].split(',').map((s) => s.trim()).filter(Boolean).map((name) => {
    if (!(name in consts)) throw new Error(`sabit çözülemedi: ${name}`)
    return consts[name]
  })
}

describe('keyword değişim notları — sunucu listesiyle eşlik', () => {
  it('CHANGE_NOTE_CODES = Java CHANGE_SKIP_CODES (sıra dahil)', () => {
    expect(javaSkipCodes()).toEqual([...CHANGE_NOTE_CODES])
  })

  it('her kodun kendi metni var; tanınmayan kod OTHER (ham kod asla)', () => {
    for (const code of CHANGE_NOTE_CODES) expect(changeNoteOf(code)).toBe(code)
    expect(changeNoteOf('YENI_KOD')).toBe('OTHER')
    const keys = Object.keys(TR).filter((k) => /changeNote|change\.note|changeSkip/i.test(k))
    expect(keys.length).toBeGreaterThan(0)
    for (const code of [...CHANGE_NOTE_CODES, 'OTHER']) {
      const hits = keys.filter((k) => k.endsWith(`.${code}`))
      expect(hits.length, `TR metni yok: ${code}`).toBeGreaterThan(0)
      for (const k of hits) expect(EN[k], `EN metni yok: ${k}`).toBeTruthy()
    }
  })

  it('403 kod adı sunucuyla aynı', () => {
    const ctrl = readJavaMain('controller/MonitoringController.java')
    expect(ctrl).toMatch(/KEYWORD_SAVED_SECRET_ORIGIN\s*=\s*"KEYWORD_SAVED_SECRET_ORIGIN"/)
  })
})
