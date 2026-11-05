import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { FACT_CODES, SUSPECT_CODES } from '../components/activity/related/relatedModel.js'
import { GROUPS } from '../components/activity/activityModel.js'
import { EXCEL_COLUMNS } from '../components/activity/activityExport.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * KAPI (2026-11-05, F): "aynı anda ne oldu?" panelinin şüpheli neden ve olgu KOD listeleri sunucunun
 * `ActivityRelatedService.SUSPECT_CODES` / `FACT_CODES` sabitleriyle AYNI (sıra dahil), her kodun TR + EN metni var.
 * Sunucu yeni bir kod eklerse ön yüz onu süzüp atar (ham kod ekrana düşmez) — bu test kodun metnini eklemeyi hatırlatır.
 *
 * Java kaynağı ağaçta yoksa (paralel geliştirme: be2 dalı henüz birleşmedi) sözleşmedeki liste karşılaştırılır.
 */
const JAVA_ROOT = path.resolve(__dirname, '../../../backend/src/main/java')
const CONTRACT_SUSPECTS = ['DNS_WIDE', 'NETWORK_WIDE', 'TLS_WIDE', 'PROXY_WIDE', 'SAME_HOST', 'SAME_TEAM', 'SINGLE']
const CONTRACT_FACTS = ['FIRST_FAILURE_IN_DAYS', 'FAILURES_24H', 'TIMEOUTS_24H', 'LAST_SUCCESS', 'RECOVERED_AFTER', 'STILL_FAILING']

function findJava(dir, name, depth = 0) {
  if (depth > 12 || !fs.existsSync(dir)) return null
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) { const r = findJava(p, name, depth + 1); if (r) return r } else if (e.name === name) return p
  }
  return null
}

/** `NAME` sabitinin tanımından ilk `;`'ye kadarki dize sabitleri (List.of / Set.of / dizi biçimi). */
function codesOf(src, name, re = /"([A-Z][A-Z0-9_]*)"/g) {
  const at = src.search(new RegExp(`\\b${name}\\s*=`))
  if (at < 0) return null
  const end = src.indexOf(';', at)
  return [...src.slice(at, end < 0 ? undefined : end).matchAll(re)].map((m) => m[1])
}

const javaFile = findJava(JAVA_ROOT, 'ActivityRelatedService.java')
const javaSrc = javaFile ? fs.readFileSync(javaFile, 'utf8') : null
const serverSuspects = (javaSrc && codesOf(javaSrc, 'SUSPECT_CODES')) || CONTRACT_SUSPECTS
const serverFacts = (javaSrc && codesOf(javaSrc, 'FACT_CODES')) || CONTRACT_FACTS

const filterFile = findJava(JAVA_ROOT, 'ActivityFilter.java')
const exportFile = findJava(JAVA_ROOT, 'ActivityExportService.java')
const serverGroups = (filterFile && codesOf(fs.readFileSync(filterFile, 'utf8'), 'GROUPS', /"([a-z]+)"/g))
  || ['check', 'alarm', 'notification', 'change', 'other']
// Excel JSON'dan kurulur: satır dizileri sunucunun sütun adlarıyla nesneye çevrilir (`time` → time_local)
const CONTRACT_COLUMNS = ['time', 'monitor_type', 'monitor_name', 'target', 'team_name', 'action', 'result_status', 'result_summary',
  'error_message', 'failure_reason', 'http_status', 'response_ms', 'actor', 'request_id', 'alert_event_id', 'id']
const serverColumns = (exportFile && codesOf(fs.readFileSync(exportFile, 'utf8'), 'COLUMNS', /"([a-z_]+)"/g)) || CONTRACT_COLUMNS

describe('aktivite olay grupları ve dışa aktarım sütunları — sunucuyla eşit', () => {
  it('olay grubu anahtarları ActivityFilter.GROUPS ile aynı', () => {
    expect(GROUPS).toEqual(serverGroups)
  })

  it('Excel\'in okuduğu sütun adları sunucunun COLUMNS listesinde (yoksa hücre boş kalırdı)', () => {
    const used = ['time', 'monitor_type', 'monitor_name', 'target', 'team_name', 'action', 'result_status', 'result_summary',
      'error_message', 'failure_reason', 'http_status', 'response_ms', 'actor', 'request_id', 'alert_event_id']
    for (const c of used) expect(serverColumns, c).toContain(c)
    expect(EXCEL_COLUMNS).toHaveLength(used.length)
  })
})

describe('ilişkili aktivite kodları — sunucuyla eşit', () => {
  it(`şüpheli neden kodları ${javaFile ? 'ActivityRelatedService' : 'sözleşme'} listesiyle aynı`, () => {
    expect(SUSPECT_CODES).toEqual(serverSuspects)
  })

  it(`olgu kodları ${javaFile ? 'ActivityRelatedService' : 'sözleşme'} listesiyle aynı`, () => {
    expect(FACT_CODES).toEqual(serverFacts)
  })

  it('her kodun TR ve EN metni (şüphe: başlık + ipucu) var ve dilleri farklı', () => {
    const missing = []
    for (const c of SUSPECT_CODES) {
      for (const k of [`act.rel.suspect.${c}`, `act.rel.suspect.${c}.hint`]) {
        if (!TR[k]) missing.push('TR ' + k)
        if (!EN[k]) missing.push('EN ' + k)
        if (TR[k] && TR[k] === EN[k]) missing.push('aynı ' + k)
      }
    }
    for (const c of FACT_CODES) {
      const k = `act.rel.fact.${c}`
      if (!TR[k]) missing.push('TR ' + k)
      if (!EN[k]) missing.push('EN ' + k)
    }
    expect(missing).toEqual([])
  })
})
