import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { REPO_ROOT } from './helpers/javaTable.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  ANOMALY_DIRECTIONS, ANOMALY_LIMITS, FROZEN_LIMITS, RULE_KINDS, SEASONS,
} from '../components/dbquery/ruleModel.js'
import { RESULT_NOTE_CODES, BASELINE_NOTES } from '../components/dbquery/RuleResultsList.jsx'
import { CHANGE_EVENTS } from '../components/responsechart/chartEvents.js'

// dbq2 (2026-11-05) — akıllı kuralların (ANOMALY / FROZEN) ön yüz sabitleri sunucuyla (DbQueryRules) AYNI olmalı. Java
// kaynağında akıllı kurallar yoksa (arka uç dalıyla birleşmeden önce) sınır testi atlanır; birleşince gerçek kaynağa karşı
// koşar. DBQ_JAVA_ROOT ile başka bir ağaçtaki kaynağa karşı da koşulabilir.
const ROOT = process.env.DBQ_JAVA_ROOT || REPO_ROOT
const FILE = path.join(ROOT, 'backend/src/main/java/com/sitemonitor/service/dbquery/DbQueryRules.java')
const src = fs.existsSync(FILE) ? fs.readFileSync(FILE, 'utf8') : ''
const smart = src.includes('KIND_ANOMALY')

function stringConsts(s) {
  const out = {}
  for (const m of s.matchAll(/\b([A-Z][A-Z0-9_]*)\s*=\s*"([^"]*)"/g)) out[m[1]] = m[2]
  return out
}
function numConst(s, name) {
  const m = s.match(new RegExp(`\\b${name}\\s*=\\s*([0-9.]+)`))
  if (!m) throw new Error(`sabit yok: ${name}`)
  return Number(m[1])
}
function listOf(s, name, consts) {
  const m = s.match(new RegExp(`\\b${name}\\s*=\\s*List\\.of\\(([^)]*)\\)`))
  if (!m) throw new Error(`liste yok: ${name}`)
  return m[1].split(',').map((x) => x.trim()).filter(Boolean).map((x) => (x.startsWith('"') ? x.slice(1, -1) : consts[x]))
}

describe('akıllı kurallar — sunucu sabitleriyle eşitlik (DbQueryRules)', () => {
  it.skipIf(!smart)('tür listesi (aynı sıra), yön / mevsim değerleri, sınırlar ve varsayılanlar', () => {
    const c = stringConsts(src)
    expect(RULE_KINDS).toEqual(listOf(src, 'KINDS', c))
    expect(ANOMALY_DIRECTIONS).toEqual(listOf(src, 'ANOMALY_DIRECTIONS', c))
    expect(SEASONS).toEqual(listOf(src, 'SEASONS', c))
    for (const k of Object.keys(ANOMALY_LIMITS)) expect(ANOMALY_LIMITS[k], k).toBe(numConst(src, k))
    for (const k of Object.keys(FROZEN_LIMITS)) expect(FROZEN_LIMITS[k], k).toBe(numConst(src, k))
  })
})

describe('akıllı kurallar — metinler (TR + EN)', () => {
  it('her türün adı, açıklaması ve örneği; her yön / mevsim; taban notu', () => {
    for (const d of [TR, EN]) {
      for (const k of RULE_KINDS) expect(d[`dbq.rule.kindName.${k}`], k).toBeTruthy()
      for (const k of ['ANOMALY', 'FROZEN']) {
        for (const part of ['title', 'body', 'example']) expect(d[`dbq.rule.smart.${k}.${part}`], `${k}.${part}`).toBeTruthy()
        expect(d[`dbq.test.addRule.${k}`], k).toBeTruthy()
      }
      for (const dir of ANOMALY_DIRECTIONS) {
        expect(d[`dbq.rule.anomaly.dir.${dir}`]).toBeTruthy()
        expect(d[`dbq.rule.anomaly.dirHint.${dir}`]).toBeTruthy()
        expect(d[`dbq.rule.sum.anomaly.${dir}`]).toBeTruthy()
      }
      for (const s of SEASONS) {
        for (const p of ['seasonName', 'seasonHint', 'window']) expect(d[`dbq.rule.anomaly.${p}.${s}`], `${p}.${s}`).toBeTruthy()
        expect(d[`dbq.rule.sum.season.${s}`]).toBeTruthy()
      }
      for (const e of CHANGE_EVENTS) expect(d[`chart.ev.event.${e}`], e).toBeTruthy()
    }
    expect(RESULT_NOTE_CODES).toContain('building_baseline')
    expect(BASELINE_NOTES).toContain('building_baseline')
  })
})
