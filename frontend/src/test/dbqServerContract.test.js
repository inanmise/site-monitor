import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { REPO_ROOT } from './helpers/javaTable.js'
import { GUARD_CODES, GUARD_TIMEOUT_TOKEN, isGuardTimeout } from '../components/dbquery/sqlEditorModel.js'
import { MAX_COLUMN, MAX_LABEL, MAX_RULES, MAX_TEXT } from '../components/dbquery/ruleModel.js'
import { RESULT_NOTE_CODES } from '../components/dbquery/RuleResultsList.jsx'
import { SCHEDULE_CODES } from '../components/dbquery/ScheduleEditor.jsx'
import { MAX_SKIP_DATES, MAX_WINDOWS } from '../components/dbquery/scheduleModel.js'

// Veritabanı Sorgu İzleme — yapı taşlarının sunucu sabitleriyle sözleşmesi (2026-10-31). Ön yüz listeleri Java
// kaynağındaki sabitlerle AYNI olmalı (bilinmeyen kod genel metne düşer ama yanlış ipucu verir). İskelet dosyaları
// ("İSKELET —" işaretli imzalar) atlanır; motor dalıyla birleşince gerçek kaynağa karşı koşar. DBQ_JAVA_ROOT ile başka
// bir ağaçtaki kaynağa karşı da koşulabilir.
const ROOT = process.env.DBQ_JAVA_ROOT || REPO_ROOT
const DIR = path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/dbquery')
const read = (f) => fs.readFileSync(path.join(DIR, f), 'utf8')
/** Gerçek (iskelet olmayan) kaynak var mı. */
const real = (f) => fs.existsSync(path.join(DIR, f)) && !read(f).includes('İSKELET —')

/** `NAME = "değer"` atamaları → { NAME: değer } (tek satırda birden çok atama olabilir). */
function stringConsts(src) {
  const out = {}
  for (const m of src.matchAll(/\b([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"/g)) out[m[1]] = m[2]
  return out
}
/** `NAME = 123` (ya da `7 * 24 * 3600`) tam sayı atamaları. */
function intConst(src, name) {
  const m = src.match(new RegExp(`\\b${name}\\s*=\\s*([0-9_ *]+)[,;]`))
  if (!m) throw new Error(`sabit yok: ${name}`)
  return m[1].split('*').map((x) => Number(x.replace(/[_\s]/g, ''))).reduce((a, b) => a * b, 1)
}

describe('sunucu sabitleriyle eşitlik (Java kaynağı)', () => {
  it.skipIf(!real('DbQueryRules.java'))('kural sınırları = DbQueryRules', () => {
    const src = read('DbQueryRules.java')
    expect({ MAX_RULES, MAX_LABEL, MAX_COLUMN, MAX_TEXT }).toEqual({
      MAX_RULES: intConst(src, 'MAX_RULES'), MAX_LABEL: intConst(src, 'MAX_LABEL'),
      MAX_COLUMN: intConst(src, 'MAX_COLUMN'), MAX_TEXT: intConst(src, 'MAX_TEXT'),
    })
  })

  it.skipIf(!real('DbQuerySqlGuard.java'))('güvenlik ihlali kodları = DbQuerySqlGuard.CODES (aynı sıra) ve zaman aşımı belirteci', () => {
    const src = read('DbQuerySqlGuard.java')
    const c = stringConsts(src)
    const list = src.match(/List<String>\s+CODES\s*=\s*List\.of\(([^)]*)\)/)[1].split(',').map((x) => c[x.trim()])
    expect(GUARD_CODES).toEqual(list)
    expect(GUARD_TIMEOUT_TOKEN).toBe(c.TOKEN_TIMEOUT)
  })

  it.skipIf(!real('DbQueryEvaluator.java'))('kural sonucu not kodları = DbQueryEvaluator.NOTE_*', () => {
    const c = stringConsts(read('DbQueryEvaluator.java'))
    const notes = Object.entries(c).filter(([k]) => k.startsWith('NOTE_')).map(([, v]) => v)
    expect([...RESULT_NOTE_CODES].sort()).toEqual([...new Set(notes)].sort())
  })

  it.skipIf(!real('DbQuerySchedule.java'))('zamanlama hata kodları ve sınırları = DbQuerySchedule', () => {
    const src = read('DbQuerySchedule.java')
    const c = stringConsts(src)
    const codes = Object.entries(c).filter(([k]) => k.startsWith('CODE_')).map(([, v]) => v)
    expect(Object.values(SCHEDULE_CODES).sort()).toEqual([...new Set(codes)].sort())
    expect({ MAX_WINDOWS, MAX_SKIP_DATES }).toEqual({ MAX_WINDOWS: intConst(src, 'MAX_WINDOWS'), MAX_SKIP_DATES: intConst(src, 'MAX_SKIP_DATES') })
  })
})

describe('kodların metinleri (TR + EN)', () => {
  it('her güvenlik kodunun başlığı ve düzeltmesi var; eski SQL_DBLINK yok', () => {
    for (const code of GUARD_CODES) {
      for (const d of [TR, EN]) {
        expect(d[`dbq.guard.${code}.title`], code).toBeTruthy()
        expect(d[`dbq.guard.${code}.fix`], code).toBeTruthy()
      }
    }
    expect(GUARD_CODES).not.toContain('SQL_DBLINK')
    expect(TR['dbq.guard.SQL_DBLINK.title']).toBeUndefined()
  })

  it('her not kodunun metni var', () => {
    for (const code of RESULT_NOTE_CODES) {
      expect(TR[`dbq.rr.note.${code}`], code).toBeTruthy()
      expect(EN[`dbq.rr.note.${code}`], code).toBeTruthy()
    }
  })

  it('isGuardTimeout: tek ihlal + SQL_UNPARSABLE + TIMEOUT; başka her şey değil', () => {
    expect(isGuardTimeout([{ code: 'SQL_UNPARSABLE', token: 'TIMEOUT' }])).toBe(true)
    expect(isGuardTimeout([{ code: 'SQL_UNPARSABLE', token: 'SELEC' }])).toBe(false)
    expect(isGuardTimeout([{ code: 'SQL_UNPARSABLE', token: 'TIMEOUT' }, { code: 'SQL_INTO', token: 'INTO' }])).toBe(false)
    expect(isGuardTimeout([])).toBe(false)
    expect(isGuardTimeout(null)).toBe(false)
  })
})
