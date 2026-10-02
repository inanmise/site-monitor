import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { csvCell, csvRows } from '../utils/csv.js'
import { downloadCsv } from '../utils/csvExport.js'
import { formatDate, formatDateOnly } from '../api/client'
import { attentionCsv, reasonLabel, nextText } from '../pages/warnings/AttentionParts.jsx'
import { analyze } from '../pages/warnings/warningsModel.js'
import { importTemplateCsv, IMPORT_COLUMNS } from '../components/inventory/inventoryModel.js'
import { downloadCsv as weeklyDownloadCsv } from '../components/weekly/yearSummaryActions.js'

/**
 * KARAKTERİZASYON (2026-10-02, öneri 29 — "indirilen dosyalar aynı kalır"): elle yazılmış CSV indirmeleri ortak
 * `utils/csvExport.downloadCsv` + `utils/csv.csvRows`'a taşınmadan ÖNCE dosya baytları pinlenir. Taşıma öncesi gövdeler
 * KELİMESİ KELİMESİNE kâhin olarak burada; yeni yol aynı içerik (BOM, ayırıcı, satır sonu, tırnaklama, formül nötrleme),
 * aynı MIME ve aynı dosya adını üretmeli. Zor hücre matrisi: formül önekleri, tırnak, virgül, noktalı virgül, CR/LF,
 * null/undefined, negatif sayı, mantıksal, Türkçe harf, dizi.
 */
const NASTY = ['=SUM(A1)', '+1', '-5', -5, '@cmd', '\tx', '\rx', 'a"b', 'a,b', 'a;b', 'a\nb', 'a\r\nb', null, undefined, '', 0, 12.5,
  true, false, 'İzleme Ğüşıöç', ['x', 'y'], ' boşluk ', '"', "'quoted'"]

// Blob içeriğini ve türünü yakala (jsdom Blob.text eşzamansız) + anchor tıklamasını kaydet.
let blobs, clicks, revoked, RealBlob, origCreate, origRevoke, clickSpy
beforeEach(() => {
  blobs = []; clicks = []; revoked = []
  RealBlob = global.Blob
  global.Blob = class extends RealBlob {
    constructor(parts, opts) { super(parts, opts); blobs.push({ text: parts.join(''), type: opts?.type }) }
  }
  origCreate = URL.createObjectURL; origRevoke = URL.revokeObjectURL
  URL.createObjectURL = vi.fn(() => `blob:${blobs.length}`)
  URL.revokeObjectURL = vi.fn((u) => revoked.push(u))
  clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() {
    clicks.push({ download: this.download, inDom: document.body.contains(this) })
  })
  vi.useFakeTimers()
})
afterEach(() => {
  global.Blob = RealBlob; URL.createObjectURL = origCreate; URL.revokeObjectURL = origRevoke
  clickSpy.mockRestore(); vi.useRealTimers()
})
const run = (fn) => {
  blobs = []; clicks = []; revoked = []
  fn()
  const revokedNow = revoked.length
  vi.advanceTimersByTime(1000)
  return { blobs, clicks, revokedNow, revokedAfter1s: revoked.length }
}

// ── Kâhinler: taşıma öncesi indirme kuyrukları ───────────────────────────────────────────────────────────
// Kuyruk A (Yenileme Planı, Dikkat Gerektirenler, Yenileme Önerileri, Envanter şablonu): DOM'a eklenir, 1 sn sonra iptal.
function legacyTailA(csv, name) {
  try {
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { /* jsdom */ }
}
// Kuyruk B (Tüm Sertifikalar seçili CSV, Kullanıcı/Oturum, Haftalık yıl özeti): A ile aynı, BOM'u Blob'da ekler.
function legacyTailB(csv, name) {
  try {
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }); const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch { /* jsdom */ }
}
// Kuyruk C (Sayfa izleme sorunları, Sayfa hızı kaynakları): DOM'a eklemez, hemen iptal eder.
function legacyTailC(csvWithBom, name) {
  const blob = new Blob([csvWithBom], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click(); URL.revokeObjectURL(a.href)
}
// Kuyruk D (Aktivite Logu): C gibi, MIME sonda ';'.
function legacyTailD(csvWithBom, name) {
  const blob = new Blob([csvWithBom], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

describe('CSV indirme — ortak downloadCsv kuyruğu', () => {
  it('kuyruk A ile birebir: içerik, MIME, dosya adı, DOM\'da tıklama, 1 sn sonra iptal', () => {
    const csv = '﻿' + csvRows([['a', 'b'], NASTY])
    const old = run(() => legacyTailA(csv, 'x.csv'))
    const now = run(() => downloadCsv('x.csv', csv))
    expect(now.blobs).toEqual(old.blobs)
    expect(now.clicks).toEqual(old.clicks)
    expect(now.clicks).toEqual([{ download: 'x.csv', inDom: true }])
    expect([now.revokedNow, now.revokedAfter1s]).toEqual([old.revokedNow, old.revokedAfter1s])
    expect([now.revokedNow, now.revokedAfter1s]).toEqual([0, 1])
  })

  it('kuyruk B (BOM Blob\'da) ≡ downloadCsv(ad, BOM + csv); haftalık yearSummaryActions.downloadCsv(csv, ad) aynı', () => {
    const csv = csvRows([['h1', 'h2'], NASTY])
    const old = run(() => legacyTailB(csv, 'y.csv'))
    const now = run(() => downloadCsv('y.csv', '﻿' + csv))
    const weekly = run(() => weeklyDownloadCsv(csv, 'y.csv'))
    expect(now.blobs).toEqual(old.blobs)
    expect(weekly.blobs).toEqual(old.blobs)
    expect(weekly.clicks).toEqual(old.clicks)
    expect([weekly.revokedNow, weekly.revokedAfter1s]).toEqual([old.revokedNow, old.revokedAfter1s])
  })

  it('kuyruk C/D: dosya baytları ve adı aynı (yalnız indirme mekaniği ortaklaşır; D\'nin MIME sondaki ";" düşer)', () => {
    const csv = '﻿' + csvRows([['a'], ['=1']])
    const oldC = run(() => legacyTailC(csv, 'c.csv'))
    const now = run(() => downloadCsv('c.csv', csv))
    expect(now.blobs).toEqual(oldC.blobs)
    expect(now.clicks.map((c) => c.download)).toEqual(oldC.clicks.map((c) => c.download))
    const lf = '﻿' + ['time,type', '"a,b",' + csvCell('=x')].join('\n')
    const oldD = run(() => legacyTailD(lf, 'activity-log.csv'))
    const nowD = run(() => downloadCsv('activity-log.csv', lf))
    expect(nowD.blobs.map((b) => b.text)).toEqual(oldD.blobs.map((b) => b.text))
    expect(nowD.clicks.map((c) => c.download)).toEqual(['activity-log.csv'])
  })
})

describe('CSV gövdeleri — taşıma öncesi kurucularla birebir', () => {
  it('Yenileme Planı / Dikkat Gerektirenler: rows.map(csvCell).join(",").join(CRLF) ≡ csvRows', () => {
    const rows = [['h1', 'h2', 'h3'], NASTY.slice(0, 3), NASTY.slice(3, 9), NASTY.slice(9), [], ['']]
    const legacy = '﻿' + rows.map((r) => r.map(csvCell).join(',')).join('\r\n')
    expect('﻿' + csvRows(rows)).toBe(legacy)
  })

  it('Sayfa izleme / Sayfa hızı: başlık + CRLF + gövde ≡ csvRows([başlık, ...satırlar])', () => {
    const head = ['url', 'type', 'bytes', 'third_party', 'checked_at']
    const items = [
      { url: '=HYPERLINK("x")', type: 'script', bytes: -1, third_party: true, checked_at: '2026-10-02T10:00:00' },
      { url: 'https://a.example.com/a,b;c', type: null, bytes: 0, third_party: false },
      { url: 'line\nbreak', type: '"q"', bytes: 12.5, third_party: undefined, checked_at: '' },
    ]
    const body = items.map((r) => head.map((k) => csvCell(r[k])).join(',')).join('\r\n')
    const legacy = '﻿' + head.map(csvCell).join(',') + '\r\n' + body
    expect('﻿' + csvRows([head, ...items.map((r) => head.map((k) => r[k]))])).toBe(legacy)
  })

  it('attentionCsv (Dikkat Gerektirenler) bugünkü kurucuyla birebir', () => {
    const t = (k, ...a) => (a.length ? `${k}(${a.join(',')})` : k)
    const ctx = { weak: null, silent: null, mail: null, plans: { 'b.example.com': { planned_at: '2026-10-05T09:00:00' } } }
    const items = [
      { domain: '=cmd.example.com', days_remaining: 2, status: 'warning', team_name: 'Takım "A"', tier: 1, not_after: '2026-10-04T00:00:00' },
      { domain: 'b.example.com', days_remaining: -3, status: 'expired', issuer_cn: 'CA, Inc', checked_at: '2026-10-01T08:00:00' },
      { domain: 'c.example.com', status: 'error', error: 'PKIX path\nfailed' },
    ].map((x) => analyze(x, ctx))
    const CSV_COLS = ['domain', 'section', 'reasons', 'next', 'days', 'expires', 'team', 'tier', 'plan', 'issuer', 'checked', 'error']
    const head = CSV_COLS.map((k) => t(`attn.csv.${k}`))
    const body = items.map(({ row, group, reasons, next, plan }) => [
      row.domain, t(`attn.group.${group}`), reasons.map((r) => reasonLabel(r, row, t)).join('; '), nextText(next, t),
      row.days_remaining ?? '', row.not_after ? formatDateOnly(row.not_after) : '', row.team_name || '', row.tier ? `T${row.tier}` : '',
      plan?.planned_at ? formatDateOnly(plan.planned_at) : '', row.issuer_cn || row.issuer || '', row.checked_at ? formatDate(row.checked_at) : '',
      row.error || '',
    ])
    const legacy = '﻿' + [head, ...body].map((r) => r.map(csvCell).join(',')).join('\r\n')
    expect(attentionCsv(items, t)).toBe(legacy)
    expect(legacy).toContain("'=cmd.example.com")
  })

  it('Envanter içe aktarma şablonu: bugünkü bayt dizisi (BOM indirmede eklenir)', () => {
    const example = { domain: 'www.example.com', port: '443', team: 'Takım A', tier: '1', active: 'evet', group: '', svc_mgmt_contact: 'ops@example.com', netscaler: 'evet', waf_enabled: 'hayır' }
    const legacy = IMPORT_COLUMNS.join(',') + '\r\n' + IMPORT_COLUMNS.map((k) => example[k] ?? '').join(',') + '\r\n'
    expect(importTemplateCsv()).toBe(legacy)
    expect(importTemplateCsv().endsWith('\r\n')).toBe(true)
  })
})
