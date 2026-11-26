import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { readJavaMain } from './helpers/javaTable.js'
import {
  FILTER_DEFAULTS, filtersFromUrl, filtersToUrl, serverParams, trendParams, advancedCount, activeFilters, patchFilters,
  sortFromUrl, SORTS, DEFAULT_SORT, SORT_KEY, POSTMORTEM_FILTERS, MAX_FILTER_TEXT, CREATED_BY_ME, isCardActive, applyCardFilter,
} from '../components/incidenthistory/incidentHistoryModel.js'
import {
  incidentMarkdown, summaryFacts, summaryFileName, mdCell, absolutizeAppLinks, istanbulStamp, SUMMARY_SECTIONS,
} from '../components/incidenthistory/incidentSummary.js'
import { EXPORT_COLUMNS, historySheetRows, historySheet, cellOf, historyExportName } from '../components/incidenthistory/historyExport.js'

/**
 * Olay ve Hata Geçmişi — "Bul ve Raporla" (2026-11-26, Paket 3) saf kurallar: gelişmiş süzgeçlerin URL ↔ süzgeç ↔ sunucu
 * eşlemesi, sıralama, eğilim süzgeci (kartların boyutları gitmez, süzgeçsiz istek bugünkü biçim), Markdown özeti ve Excel
 * satırları. Sunucuyla ortak sabitler Java kaynağından okunur.
 */

const reader = (qs) => (k, fb = null) => new URLSearchParams(qs).get(k) ?? fb
const tr = (k, ...a) => {
  const v = TR[k]
  if (v == null) return k
  return a.reduce((s, x, i) => s.split(`{${i}}`).join(String(x)), v)
}

/** Java kaynağındaki `List.of("a", "b", …)` (adla) → dizi. */
function javaList(src, name) {
  const i = src.indexOf(name)
  if (i < 0) throw new Error(`${name} yok`)
  const open = src.indexOf('(', src.indexOf('List.of', i))
  const close = src.indexOf(');', open)
  return [...src.slice(open, close).matchAll(/"([^"]*)"/g)].map((m) => m[1])
}

describe('süzgeç modeli — gelişmiş süzgeçler', () => {
  it('URL gidiş-dönüş: altı gelişmiş süzgeç ih_* anahtarlarıyla', () => {
    const f = { ...FILTER_DEFAULTS, service: 'web.example.com', problem_type: 'Kesinti', error_code: 'E-1', tag: 'prod',
      mine: true, postmortem: 'in_review' }
    const qs = new URLSearchParams(Object.entries(filtersToUrl(f)).filter(([, v]) => v != null)).toString()
    expect(qs).toContain('ih_svc=web.example.com')
    expect(qs).toContain('ih_mine=1')
    expect(qs).toContain('ih_pm=in_review')
    expect(filtersFromUrl(reader(qs))).toEqual(f)
  })

  it('bozuk değerler yok sayılır; uzun metin 200 karaktere kısalır', () => {
    const long = 'x'.repeat(MAX_FILTER_TEXT + 50)
    const f = filtersFromUrl(reader(`ih_pm=yayında&ih_mine=evet&ih_tag=${long}`))
    expect(f.postmortem).toBe('')
    expect(f.mine).toBeUndefined()
    expect(f.tag).toHaveLength(MAX_FILTER_TEXT)
  })

  it('sunucu adları: alan → service_exact, benim → created_by=@me; boşlar gönderilmez (bugünkü istek)', () => {
    expect(serverParams(FILTER_DEFAULTS)).toEqual({})
    const p = serverParams({ ...FILTER_DEFAULTS, service: ' web.example.com ', problem_type: 'Kesinti', error_code: 'E-1',
      tag: ' prod ', mine: true, postmortem: 'none' })
    expect(p).toEqual({ service_exact: 'web.example.com', problem_type: 'Kesinti', error_code: 'E-1', tag: 'prod',
      created_by: CREATED_BY_ME, postmortem: 'none' })
  })

  it('çipler: her gelişmiş süzgeç bir çip; yaması kaldırır; sayaç', () => {
    const f = { ...FILTER_DEFAULTS, service: 's', problem_type: 'p', error_code: 'e', tag: 't', mine: true, postmortem: 'draft' }
    expect(advancedCount(f)).toBe(6)
    const chips = activeFilters(f)
    expect(chips.map((c) => c.key)).toEqual(['service', 'problem_type', 'error_code', 'tag', 'mine', 'postmortem'])
    let g = f
    for (const c of chips) g = patchFilters(g, c.patch)
    expect(activeFilters(g)).toEqual([])
    expect(advancedCount(g)).toBe(0)
  })

  it('kartlar yalnız kendi boyutlarını sıfırlar: gelişmiş süzgeç ve arama kalır; "Dönem toplamı" öteki süzgeçlerle de etkin', () => {
    const f = { ...FILTER_DEFAULTS, q: 'dns', tag: 'prod', severity: 'LOW' }
    const a = applyCardFilter(f, 'sla')
    expect(a).toMatchObject({ q: 'dns', tag: 'prod', severity: '', slaBreached: true })
    expect(isCardActive('total', { ...FILTER_DEFAULTS, q: 'dns', tag: 'prod' })).toBe(true)
    expect(isCardActive('total', { ...FILTER_DEFAULTS, severity: 'LOW' })).toBe(false)
  })
})

describe('kanal tam öğe — channel_exact (inceleme 2026-11-26)', () => {
  it('URL gidiş-dönüş ih_chx; "Daha fazla süzgeç" sayacına girmez (döküm süzgeci)', () => {
    const f = { ...FILTER_DEFAULTS, channel_exact: 'Mobil Şube' }
    const url = filtersToUrl(f)
    expect(url.ih_chx).toBe('Mobil Şube')
    const qs = new URLSearchParams(Object.entries(url).filter(([, v]) => v != null)).toString()
    expect(filtersFromUrl(reader(qs))).toEqual(f)
    expect(advancedCount(f)).toBe(0)
    expect(filtersToUrl(FILTER_DEFAULTS).ih_chx ?? null).toBeNull()
  })

  it('sunucu: channel_exact + aynı değer channel (eski pod alt dize yedeği); araç çubuğu kanalı varsa o kalır', () => {
    expect(serverParams({ ...FILTER_DEFAULTS, channel_exact: ' ATM ' })).toEqual({ channel_exact: 'ATM', channel: 'ATM' })
    expect(serverParams({ ...FILTER_DEFAULTS, channel: 'Mobil', channel_exact: 'Mobil Şube' }))
      .toEqual({ channel: 'Mobil', channel_exact: 'Mobil Şube' })
    expect(serverParams({ ...FILTER_DEFAULTS, channel_exact: '   ' })).toEqual({})
  })

  it('eğilim: channel_exact süzgeçtir (extra) — özet listeyle aynı kümeyi sayar', () => {
    const { params, extra } = trendParams({ ...FILTER_DEFAULTS, channel_exact: 'ATM' })
    expect(extra).toBe(true)
    expect(params).toEqual({ channel_exact: 'ATM', channel: 'ATM' })
  })

  it('çip: tek çip, yaması süzgeci kaldırır (istekten de çıkar)', () => {
    const f = { ...FILTER_DEFAULTS, channel_exact: 'ATM' }
    const chips = activeFilters(f)
    expect(chips.map((c) => c.key)).toEqual(['channel_exact'])
    const g = patchFilters(f, chips[0].patch)
    expect(activeFilters(g)).toEqual([])
    expect(serverParams(g)).toEqual({})
  })
})

describe('sıralama', () => {
  it('sunucunun SORTS anahtarlarıyla AYNI sıra; bilinmeyen değer varsayılan', () => {
    const src = readJavaMain('service/IncidentService.java')
    const java = [...src.matchAll(/s\.put\("([a-z_]+)", " ORDER BY/g)].map((m) => m[1])
    expect(SORTS).toEqual(java)
    expect(DEFAULT_SORT).toBe('occurred_desc')
    expect(src).toContain(`DEFAULT_SORT = "${DEFAULT_SORT}"`)
    expect(sortFromUrl(reader(`${SORT_KEY}=severity`))).toBe('severity')
    expect(sortFromUrl(reader(`${SORT_KEY}=x`))).toBe(DEFAULT_SORT)
    for (const s of SORTS) expect(TR[`inc.sort.${s}`] && EN[`inc.sort.${s}`]).toBeTruthy()
  })

  it('postmortem süzgeci ve metin sınırı sunucuyla aynı', () => {
    const src = readJavaMain('service/IncidentService.java')
    expect(javaList(src, 'POSTMORTEM_FILTERS =').map((v) => v.toLowerCase())).toEqual([...POSTMORTEM_FILTERS])
    expect(src).toContain(`MAX_FILTER_TEXT = ${MAX_FILTER_TEXT};`)
    expect(readJavaMain('controller/IncidentController.java')).toContain(`CREATED_BY_ME = "${CREATED_BY_ME}"`)
  })
})

describe('eğilim süzgeci', () => {
  it('tarih dışında süzgeç yoksa extra=false (istek bugünkü iki tarihli biçimde gider)', () => {
    expect(trendParams(FILTER_DEFAULTS)).toEqual({ params: {}, extra: false })
    const { params, extra } = trendParams({ ...FILTER_DEFAULTS, since: '2026-11-01' })
    expect(extra).toBe(false)
    expect(params.since).toMatch(/^2026-1[01]-\d\dT/)
  })

  it('kartların boyutları (önem, durum, SLA, açık) gitmez; öteki süzgeçler gider', () => {
    const { params, extra } = trendParams({ ...FILTER_DEFAULTS, severity: 'HIGH', status: 'OPEN', slaBreached: true,
      open: true, team_id: '3', tag: 'prod' })
    expect(extra).toBe(true)
    expect(params).toEqual({ team_id: '3', tag: 'prod' })
  })

  it('günlük pencere için tarihler atılır', () => {
    const { params } = trendParams({ ...FILTER_DEFAULTS, since: '2026-11-01', q: 'x' }, { withDates: false })
    expect(params).toEqual({ q: 'x' })
  })
})

describe('Markdown özeti', () => {
  const rec = {
    id: 42, title: 'Ödeme | servisinde gecikme', severity: 'CRITICAL', status: 'RESOLVED', category: 'APPLICATION',
    occurred_at: '2026-11-10T07:00:00', detected_at: '2026-11-10T07:15:00', resolved_at: '2026-11-10T09:00:00',
    duration_minutes: 120, team_name: 'Takım A', channel: 'Mobil Şube, ATM', service: 'odeme.example.com',
    problem_types: '', error_code: 'E-500', function_code: '', channel_code: '', affected_customers: 1200,
    affected_transactions: null, tech_error_count: null, outage_related: true, outage_id: 'INC-1', sla_breached: false,
    tags: 'db, havuz', runbook_url: '', alert_event_id: 9, created_by: 'kisi.a', created_at: '2026-11-10T09:30:00',
    updated_by: null, updated_at: null,
    business_impact: '', rca_summary: 'Havuz tükendi ![g](/api/incidents/images/3)', description: 'Satır 1\nSatır 2',
    resolution_steps: '1. Havuz büyütüldü',
  }
  const now = new Date(Date.UTC(2026, 10, 26, 9, 0, 0))

  it('başlık, kayıt no, olgu tablosu (yalnız dolu satırlar), dört bölüm; boş bölüm "girilmedi"', () => {
    const md = incidentMarkdown(rec, tr, { nameOf: (u) => (u === 'kisi.a' ? 'Kişi A' : null), origin: 'https://sm.example.com', now })
    expect(md.startsWith('# Ödeme \\| servisinde gecikme\n')).toBe(true)
    expect(md).toContain(tr('inc.sum.recordNo', 42))
    expect(md).toContain('2026-11-26 12:00')
    expect(md).toContain(`| ${TR['inc.colSeverity']} | ${TR['inc.sevCRITICAL']} |`)
    expect(md).toContain(`| ${TR['inc.sum.occurred']} | 2026-11-10 10:00 |`)
    expect(md).toContain(`| ${TR['inc.sum.outage']} | ${TR['inc.sum.yes']} (INC-1) |`)
    expect(md).toContain(`| ${TR['inc.sum.sla']} | ${TR['inc.sum.no']} |`)
    expect(md).toContain(`| ${TR['inc.sum.alert']} | #9 |`)
    expect(md).toContain('Kişi A · 2026-11-10 12:30')
    expect(md).not.toContain(TR['inc.dTransactions'])   // girilmemiş satır yazılmaz
    expect(md).not.toContain(TR['inc.sum.techErrCount'])
    expect(md).toContain('](https://sm.example.com/api/incidents/images/3)')
    for (const [key] of SUMMARY_SECTIONS) expect(md).toContain(`## ${TR[key]}`)
    expect(md).toContain(`_${TR['inc.notEntered']}_`)
    expect(md).toContain('Satır 1\nSatır 2')
  })

  it('bilinmeyen kesinti cevabı ve SLA satırı yazılmaz; kişi dizinde yoksa kullanıcı adı', () => {
    const facts = summaryFacts({ ...rec, outage_related: null, sla_breached: null }, tr)
    const labels = facts.map(([k]) => k)
    expect(labels).not.toContain(TR['inc.sum.outage'])
    expect(labels).not.toContain(TR['inc.sum.sla'])
    expect(facts.find(([k]) => k === TR['inc.sum.createdBy'])[1]).toContain('kisi.a')
  })

  it('tablo hücresi: | kaçışlı, satır sonu boşluk; adres çevirisi yalnız ](/api/ biçimi', () => {
    expect(mdCell('a|b\nc')).toBe('a\\|b c')
    expect(absolutizeAppLinks('x ](/api/a) y (/api/b)', 'https://o')).toBe('x ](https://o/api/a) y (/api/b)')
    expect(absolutizeAppLinks('x', '')).toBe('x')
    expect(istanbulStamp('bozuk')).toBe('')
  })

  it('dosya adı: olay-<id>-<türkçe harfsiz kısaltma>.md, en çok 60 karakter', () => {
    expect(summaryFileName({ id: 7, title: 'Ödeme Şube çağrı / gecikme!!' })).toBe('olay-7-odeme-sube-cagri-gecikme.md')
    expect(summaryFileName({ id: 8, title: '' })).toBe('olay-8.md')
    const long = summaryFileName({ id: 1, title: 'a'.repeat(200) })
    expect(long.length).toBeLessThanOrEqual('olay-1-'.length + 60 + '.md'.length)
  })
})

describe('Excel satırları', () => {
  it('sütunlar sunucunun IncidentExportRows.COLUMNS listesiyle AYNI; her başlığın TR + EN çevirisi var', () => {
    const java = javaList(readJavaMain('service/incident/IncidentExportRows.java'), 'COLUMNS =')
    expect([...EXPORT_COLUMNS]).toEqual(java)
    const [header] = historySheetRows(EXPORT_COLUMNS, [], tr)
    header.forEach((h, i) => expect(h, EXPORT_COLUMNS[i]).not.toBe(EXPORT_COLUMNS[i]))
    const enT = (k) => EN[k] ?? k
    const [enHeader] = historySheetRows(EXPORT_COLUMNS, [], enT)
    enHeader.forEach((h, i) => expect(h, EXPORT_COLUMNS[i]).not.toBe(EXPORT_COLUMNS[i]))
  })

  it('hücreler: önem / durum / kategori / postmortem çevrilir, evet-hayır, sayı; bilinmeyen boş', () => {
    expect(cellOf('severity', 'HIGH', tr)).toBe(TR['inc.sevHIGH'])
    expect(cellOf('status', 'OPEN', tr)).toBe(TR['inc.stOPEN'])
    expect(cellOf('category', 'NETWORK', tr)).toBe(TR['inc.catNETWORK'])
    expect(cellOf('postmortem', 'published', tr)).toBe(TR['pm.status.PUBLISHED'])
    expect(cellOf('sla_breached', true, tr)).toBe(TR['inc.sum.yes'])
    expect(cellOf('outage_related', false, tr)).toBe(TR['inc.sum.no'])
    expect(cellOf('outage_related', null, tr)).toBe('')
    expect(cellOf('affected_customers', 12, tr)).toBe(12)
    expect(cellOf('affected_customers', 'x', tr)).toBe('')
    expect(cellOf('title', '=cmd', tr)).toBe('=cmd')   // xlsxWriter satır içi metin yazar (formül değil)
  })

  it('sayfa: dondurulmuş başlık + otomatik süzgeç; dosya adı yerel saatle', () => {
    const sheet = historySheet(null, [{ id: 1, title: 'a' }], tr)
    expect(sheet).toMatchObject({ name: TR['inc.export.sheet'], headerRows: 1, freezeHeader: true, autoFilter: true })
    expect(sheet.rows).toHaveLength(2)
    expect(sheet.rows[1][0]).toBe(1)
    expect(historyExportName(new Date(2026, 10, 26, 9, 5))).toBe('olay-gecmisi-20261126-0905.xlsx')
  })

  it('eşleşen alan ipuçları: sunucunun her anahtarının TR + EN etiketi var', () => {
    const keys = javaList(readJavaMain('service/IncidentService.java'), 'MATCH_FIELD_KEYS =')
    for (const k of [...keys, 'id', 'other']) {
      const key = `inc.match.field.${k === 'error_code' ? 'ecode' : k}`
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
    }
  })
})
