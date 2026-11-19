import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { readJavaMain } from './helpers/javaTable.js'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { INTERVAL_PRESETS } from '../components/dbquery/scheduleModel.js'
import {
  ACTIONS, INTERVALS_BY_TYPE, LEVELS, MAX_ITEMS, MAX_TAGS, TAG_MAX_CHARS, TYPES, TYPE_OF_NOC, buildBody, commonTeam, formProblem, intervalOptions,
  isUnsupportedAnswer, maxInterval, mergeTags, minInterval, planRows, sameTags, tagProblem, tagsOf, EMPTY_FORM,
} from '../components/monitoring/bulkedit/monitorBulkEditModel.js'

/**
 * Genişletilmiş toplu düzenleme (2026-11-19) — istemci modeli. Sabitler ve kurallar Java `MonitorBulkEdit` ile aynı olmalı
 * (kaynak okunur); önizleme sunucunun `plan()` kararıyla aynı nedeni verir.
 */
const java = readJavaMain('service/monitorbulk/MonitorBulkEdit.java')
const listOf = (name) => {
  const m = new RegExp(`${name}\\s*=\\s*List\\.of\\(([^)]*)\\)`).exec(java)
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1])
}
const intOf = (name) => Number(new RegExp(`static final int ${name} = (\\d+)`).exec(java)[1])
const form = (over) => ({ ...EMPTY_FORM, ...over })

describe('monitorBulkEditModel — Java ile aynı sabitler', () => {
  it('türler, eylemler, seviyeler ve sınırlar sunucuyla eşleşir', () => {
    expect([...TYPES]).toEqual(listOf('TYPES'))
    expect([...ACTIONS]).toEqual(listOf('ACTIONS'))
    expect([...LEVELS]).toEqual(listOf('LEVELS'))
    expect(MAX_ITEMS).toBe(intOf('MAX_ITEMS'))
    expect(MAX_TAGS).toBe(intOf('MAX_TAGS'))
    expect(TAG_MAX_CHARS).toBe(intOf('TAG_MAX_CHARS'))
    expect(Object.values(TYPE_OF_NOC).sort()).toEqual([...TYPES].sort())
  })

  it('aralık sınırları sunucunun minInterval / maxInterval kuralıyla aynı', () => {
    const minBody = /static int minInterval\(String type\) \{([\s\S]*?)\n {4}\}/.exec(java)[1]
    expect(minBody).toContain('case "page", "scripted", "dbquery" -> 60')
    expect(minBody).toContain('case "pagespeed" -> 300')
    expect(minBody).toContain('case "domain" -> 3600')
    expect(minBody).toContain('default -> 30')
    expect(java).toContain('"dbquery".equals(type) ? 7 * 86_400 : 86_400')
    expect(minInterval('http')).toBe(30)
    expect(minInterval('page')).toBe(60)
    expect(minInterval('pagespeed')).toBe(300)
    expect(minInterval('domain')).toBe(3600)
    expect(maxInterval('dbquery')).toBe(604800)
    expect(maxInterval('ping')).toBe(86400)
    for (const t of TYPES) {
      const opts = intervalOptions(t)
      expect(opts.length).toBeGreaterThan(0)
      expect(Math.min(...opts)).toBeGreaterThanOrEqual(minInterval(t))
      expect(Math.max(...opts)).toBeLessThanOrEqual(maxInterval(t))
    }
    expect(intervalOptions('domain')).toEqual([3600, 21600, 43200, 86400])
  })

  it('aralık seçenekleri türün KENDİ formundakilerle aynı (bağımsız inceleme 2026-11-19: birleşim listesi formda olmayan değer yazdırıyordu)', () => {
    const page = (f) => readFileSync(resolve(__dirname, `../components/${f}.jsx`), 'utf8')
    const formIntervals = (f) => {
      const src = page(f)
      const block = /const INTERVALS = \[([\s\S]*?)\r?\n\]/.exec(src)[1]
      return [...block.matchAll(/value:\s*([\d_]+)/g)].map((m) => Number(m[1].replace(/_/g, '')))
    }
    const files = { http: 'HttpMonitorPage', keyword: 'KeywordMonitorPage', port: 'PortMonitorPage', ping: 'PingMonitorPage',
      dns: 'DnsMonitorPage', page: 'PageMonitorPage', domain: 'DomainMonitorPage' }
    for (const [type, f] of Object.entries(files)) expect([...INTERVALS_BY_TYPE[type]], type).toEqual(formIntervals(f))
    // zamanlama düzenleyicili türler (sentetik / sorgu / Sayfa Hızı): düzenleyicinin hazır aralıkları, tür tabanından itibaren
    expect([...INTERVALS_BY_TYPE.scripted]).toEqual(INTERVAL_PRESETS.filter((x) => x >= 60))
    expect([...INTERVALS_BY_TYPE.dbquery]).toEqual(INTERVAL_PRESETS.filter((x) => x >= 60))
    expect([...INTERVALS_BY_TYPE.pagespeed]).toEqual(INTERVAL_PRESETS.filter((x) => x >= 300))
    expect(Object.keys(INTERVALS_BY_TYPE).sort()).toEqual([...TYPES].sort())
  })

  it('sunucunun her neden kodunun TR + EN metni var', () => {
    const reasons = listOf('REASONS')
    expect(reasons).toEqual(expect.arrayContaining(['routing_locked', 'time_budget', 'rejected']))
    for (const r of reasons) {
      expect(TR[`adm.bulk.reason.${r}`], r).toBeTruthy()
      expect(EN[`adm.bulk.reason.${r}`], r).toBeTruthy()
    }
  })
})

describe('monitorBulkEditModel — etiket kuralları (Java tagsOf / mergeTags)', () => {
  it('ayrıştırma, birleştirme ve küme karşılaştırması', () => {
    const cur = tagsOf(' web, Prod ,web,, api ')
    expect(cur).toEqual(['web', 'Prod', 'api'])
    expect(mergeTags(cur, ['PROD', 'new'], ['API'])).toEqual(['web', 'Prod', 'new'])
    expect(sameTags(cur, ['API', 'prod', 'WEB'])).toBe(true)
    expect(sameTags(cur, ['web'])).toBe(false)
    expect(mergeTags(['a'], [], ['A'])).toEqual([])
    expect(tagsOf(null)).toEqual([])
    expect(tagProblem(['x'.repeat(51)])).toBe('tagInvalid')
    expect(tagProblem(Array.from({ length: 21 }, (_, i) => `t${i}`))).toBe('tagsTooMany')
    expect(tagProblem(['ok'])).toBeNull()
  })
})

describe('monitorBulkEditModel — önizleme (sunucu plan() ile aynı nedenler)', () => {
  const rows = [
    { id: 1, name: 'A', team_id: 7, interval_seconds: 300, alert_level: 'WARNING', notify_email: true, notify_webhook: null,
      notification_group_id: null, tags: 'web' },
    { id: 2, name: 'B', team_id: 7, interval_seconds: 600, alert_level: 'HIGH', notify_email: false, notify_webhook: false,
      notification_group_id: 9, tags: 'web, api' },
    { id: 3, name: 'C', team_id: 8, interval_seconds: 600, schedule_mode: 'CRON', routing_locked: true, tags: 'api' },
    { id: 4, name: 'D', team_id: 7, tags: '' },
  ]

  it('aralık: aynı değer atlanır, CRON atlanır, değeri bilinmeyen sunucuya bırakılır', () => {
    const p = planRows(rows, form({ action: 'interval', interval: '600' }))
    const by = Object.fromEntries(p.rows.map((r) => [r.id, r]))
    expect(by[1]).toMatchObject({ state: 'change', from: 300, to: 600 })
    expect(by[2]).toMatchObject({ state: 'skip', reason: 'unchanged' })
    expect(by[3]).toMatchObject({ state: 'skip', reason: 'schedule_cron' })
    expect(by[4]).toMatchObject({ state: 'check' })
    expect([p.change, p.skip, p.check]).toEqual([1, 2, 1])
  })

  it('alarm seviyesi: boş seviye Uyarı sayılır; paylaşımla yazılan satır yönlendirme kilitli', () => {
    const p = planRows(rows, form({ action: 'alert_level', level: 'HIGH' }))
    const by = Object.fromEntries(p.rows.map((r) => [r.id, r]))
    expect(by[1]).toMatchObject({ state: 'change', from: 'WARNING', to: 'HIGH' })
    expect(by[2]).toMatchObject({ state: 'skip', reason: 'unchanged' })
    expect(by[3]).toMatchObject({ state: 'skip', reason: 'routing_locked' })
    expect(by[4]).toMatchObject({ state: 'change', from: 'WARNING' })
    expect(planRows([rows[0]], form({ action: 'alert_level', level: 'WARNING' })).rows[0].reason).toBe('unchanged')
  })

  it('kanallar: null = açık; varsayılan grup yalnız grubu olan satırı değiştirir; başka takımın grubu atlanır', () => {
    const p = planRows(rows, form({ action: 'channels', email: 'off', groupMode: 'default' }))
    const by = Object.fromEntries(p.rows.map((r) => [r.id, r]))
    expect(by[1]).toMatchObject({ state: 'change', parts: ['email'] })
    expect(by[2]).toMatchObject({ state: 'change', parts: ['group'] })
    expect(by[3]).toMatchObject({ state: 'skip', reason: 'routing_locked' })
    expect(by[4]).toMatchObject({ state: 'change', parts: ['email'] })

    const webhookOn = planRows([rows[0]], form({ action: 'channels', webhook: 'on' }))
    expect(webhookOn.rows[0]).toMatchObject({ state: 'skip', reason: 'unchanged' })

    const set = planRows(rows, form({ action: 'channels', groupMode: 'set', groupId: '9', groupTeamId: '7' }))
    const bySet = Object.fromEntries(set.rows.map((r) => [r.id, r]))
    expect(bySet[1]).toMatchObject({ state: 'change', parts: ['group'] })
    expect(bySet[2]).toMatchObject({ state: 'skip', reason: 'unchanged' })
    expect(bySet[3].reason).toBe('routing_locked')
    const other = planRows([{ ...rows[0], team_id: 8 }], form({ action: 'channels', groupMode: 'set', groupId: '9', groupTeamId: '7' }))
    expect(other.rows[0]).toMatchObject({ state: 'skip', reason: 'group_other_team' })
  })

  it('etiketler: paylaşım kilidi yok; son etiket atlanır; değişmeyen atlanır', () => {
    const p = planRows(rows, form({ action: 'tags', tagsAdd: ['prod'], tagsRemove: ['api'] }))
    const by = Object.fromEntries(p.rows.map((r) => [r.id, r]))
    expect(by[1]).toMatchObject({ state: 'change', from: ['web'], to: ['web', 'prod'] })
    expect(by[2]).toMatchObject({ state: 'change', to: ['web', 'prod'] })
    expect(by[3]).toMatchObject({ state: 'change', to: ['prod'] })
    const last = planRows([rows[2]], form({ action: 'tags', tagsRemove: ['API'] }))
    expect(last.rows[0]).toMatchObject({ state: 'skip', reason: 'last_tag' })
    const same = planRows([rows[1]], form({ action: 'tags', tagsAdd: ['WEB'] }))
    expect(same.rows[0]).toMatchObject({ state: 'skip', reason: 'unchanged' })
  })

  it('satırın adı yoksa tanımlayıcı alanlar, o da yoksa kimlik', () => {
    const p = planRows([{ id: 5, url: 'https://x.example', interval_seconds: 60 }, { id: 6, interval_seconds: 60 }],
      form({ action: 'interval', interval: '300' }))
    expect(p.rows.map((r) => r.name)).toEqual(['https://x.example', '#6'])
  })
})

describe('monitorBulkEditModel — form ve gövde', () => {
  it('formProblem: eksik ve hatalı seçimler', () => {
    expect(formProblem('http', form({ action: 'interval' }))).toBe('interval')
    expect(formProblem('domain', form({ action: 'interval', interval: '60' }))).toBe('interval')
    expect(formProblem('domain', form({ action: 'interval', interval: '3600' }))).toBeNull()
    expect(formProblem('http', form({ action: 'alert_level', level: '' }))).toBe('level')
    expect(formProblem('http', form({ action: 'channels' }))).toBe('channelsNone')
    expect(formProblem('http', form({ action: 'channels', groupMode: 'set' }))).toBe('group')
    expect(formProblem('http', form({ action: 'channels', email: 'on' }))).toBeNull()
    expect(formProblem('http', form({ action: 'tags' }))).toBe('tagsNone')
    expect(formProblem('http', form({ action: 'tags', tagsAdd: ['a'], tagsRemove: ['A'] }))).toBe('tagsBoth')
    expect(formProblem('http', form({ action: 'tags', tagsAdd: ['a,b'] }))).toBe('tagInvalid')
    expect(formProblem('http', form({ action: 'bogus' }))).toBe('action')
  })

  it('buildBody: eyleme göre yalnız gereken alanlar; 200 sınırı', () => {
    expect(buildBody('http', [1, 2], form({ action: 'interval', interval: '600' })))
      .toEqual({ type: 'http', action: 'interval', ids: [1, 2], interval_seconds: 600 })
    expect(buildBody('ping', [1], form({ action: 'alert_level', level: 'CRITICAL' })))
      .toEqual({ type: 'ping', action: 'alert_level', ids: [1], alert_level: 'CRITICAL' })
    expect(buildBody('dns', [1], form({ action: 'channels', email: 'off', webhook: 'keep', groupMode: 'set', groupId: '9' })))
      .toEqual({ type: 'dns', action: 'channels', ids: [1], notify_email: false, group_mode: 'set', notification_group_id: 9 })
    expect(buildBody('dns', [1], form({ action: 'channels', webhook: 'on', groupMode: 'keep' })))
      .toEqual({ type: 'dns', action: 'channels', ids: [1], notify_webhook: true, group_mode: 'keep' })
    expect(buildBody('http', [1], form({ action: 'tags', tagsAdd: ['a'], tagsRemove: [] })))
      .toEqual({ type: 'http', action: 'tags', ids: [1], tags_add: ['a'], tags_remove: [] })
    const many = Array.from({ length: 250 }, (_, i) => i + 1)
    expect(buildBody('http', many, form({ action: 'interval', interval: '60' })).ids).toHaveLength(MAX_ITEMS)
  })

  it('commonTeam ve eski sunucu yanıtı', () => {
    expect(commonTeam([{ team_id: 7 }, { team_id: '7' }])).toBe('7')
    expect(commonTeam([{ team_id: 7 }, { team_id: 8 }])).toBeNull()
    expect(commonTeam([{ team_id: null }])).toBeNull()
    expect(commonTeam([])).toBeNull()
    expect(isUnsupportedAnswer({ success: false, errorInfo: { status: 405 } })).toBe(true)
    expect(isUnsupportedAnswer({ success: false, errorInfo: { status: 404, code: 'NOT_FOUND' } })).toBe(true)
    expect(isUnsupportedAnswer({ success: false, errorInfo: { status: 404 } })).toBe(true)
    expect(isUnsupportedAnswer({ success: false, errorInfo: { status: 404, code: 'OTHER' } })).toBe(false)
    expect(isUnsupportedAnswer({ success: false, errorInfo: { status: 400, code: 'VALIDATION_FAILED' } })).toBe(false)
  })
})
