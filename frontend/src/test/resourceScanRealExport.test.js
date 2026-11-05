import { describe, it, expect, vi, beforeEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

// Kaynak analizi — PDF ve Excel GERÇEK arka uç yanıtıyla (prs3 sözleşme incelemesi D9 / O4, ön yüz incelemesi O5 / O6):
//  - Excel'de ham sunucu kodu yok (ekranın çevirileri kullanılır);
//  - PDF'e basılan HER karakter gömülü Roboto'da var (eksik glif boş kutu basar — "→" böyleydi);
//  - "küçültülmemiş dosya" önerisinin gövdesi kazancı söyler (toplam boyutu değil);
//  - CSP sorun cümleleri birleşirken ".," / ".." çıkmaz.
const calls = { text: [], tables: [] }
vi.mock('jspdf', () => {
  class FakeDoc {
    constructor() { this.internal = { pageSize: { getWidth: () => 842, getHeight: () => 595 } }; this.lastAutoTable = null }
    setFont() { return this }
    setFontSize() { return this }
    setTextColor() { return this }
    setDrawColor() { return this }
    setFillColor() { return this }
    addPage() { return this }
    roundedRect() { return this }
    rect() { return this }
    line() { return this }
    getTextWidth() { return 10 }
    splitTextToSize(txt) { return [String(txt)] }
    text(t) { (Array.isArray(t) ? t : [t]).forEach((x) => calls.text.push(String(x))); return this }
    output() { return new Blob(['%PDF']) }
  }
  return { jsPDF: FakeDoc }
})
vi.mock('jspdf-autotable', () => ({
  default: (doc, opts) => { calls.tables.push(opts); doc.lastAutoTable = { finalY: (opts.startY || 0) + 40 } },
}))
vi.mock('../utils/pdfBrand.js', () => ({ drawBrandHeader: vi.fn(async (_d, title) => { calls.text.push(String(title)); return 60 }) }))
vi.mock('../utils/exportInventory.js', () => ({ triggerDownload: vi.fn(), registerRobotoFont: vi.fn(async () => {}) }))
const xl = vi.hoisted(() => ({ sheets: null }))
vi.mock('../utils/xlsxWriter.js', () => ({ XLSX_MIME: 'x', buildXlsx: (s) => { xl.sheets = s; return new Uint8Array([1]) } }))

const { exportResourceScanPdf, exportResourceScanXlsx } = await import('../components/page/resourcescan/resourceScanExport.js')
const { recommendationArgs, recTextKey } = await import('../components/page/resourcescan/resourceScanModel.js')
const { TR } = await import('../i18n/tr.js')
const { EN } = await import('../i18n/en.js')
const { ttfCodePoints, missingGlyphs } = await import('./helpers/ttfCmap.js')
const real = await import('./helpers/resourceScanRealFixtures.js')

const tOf = (D) => (k, ...a) => {
  const v = D[k]
  if (typeof v !== 'string') return k
  return v.replace(/\{(\d+)\}/g, (_, i) => (a[Number(i)] === undefined ? `{${i}}` : String(a[Number(i)])))
}
const FONT = path.resolve(process.cwd(), 'public/fonts')
const REGULAR = ttfCodePoints(fs.readFileSync(path.join(FONT, 'Roboto-Regular.ttf')))
const BOLD = ttfCodePoints(fs.readFileSync(path.join(FONT, 'Roboto-Bold.ttf')))

/** Bilinen ham kod listesi (sunucu kod katalogları) — Excel hücresinde ÇIPLAK görünmemeli. */
const RAW_CODES = new Set([
  'script', 'stylesheet', 'css_import', 'no_async_defer', 'no_media', 'import', 'same_content', 'same_path', 'proxy', 'blocked',
  'dns_failed', 'connect_failed', 'tls_failed', 'not_measured', 'cancelled', 'unsafe_inline', 'unsafe_eval', 'not_https',
  'no_referrer_when_downgrade', 'short_max_age', 'report_only', 'no_http_only', 'no_same_site', 'not_secure', 'none_without_secure',
  'long_lived', 'PAGE_HTTP', 'PAGE_FAILED', 'PAGE_BLOCKED', 'PAGE_EMPTY', 'PAGE_NOT_HTML',
  'PAGE_OFFSITE', 'NOT_SCANNED',
])
const CODE_SHAPE = /^[a-z]+(_[a-z0-9]+)+$/

const SCANS = [
  { name: 'tek sayfa #1 (önceki #2 değil, bütçe aşıldı)', data: () => real.realData(1), prev: () => real.realData(2).scan },
  { name: 'tek sayfa #2', data: () => real.realData(2), prev: () => real.realData(1).scan },
  { name: 'site #3', data: () => real.realData(3), prev: () => real.realData(2).scan },
  { name: 'site #3 eksik + kodlar', data: () => real.withSiteIncomplete(real.realData(3), { asCodes: true }), prev: () => null },
  { name: 'HTML okunamadı', data: () => real.withHtmlUnread(real.realData(1)), prev: () => null },
  { name: 'güvenlik okunamadı + bütçe okunamadı', data: () => real.withBudgetUnreadable(real.withSecurityUnread(real.realData(1))), prev: () => null },
  { name: 'kısmi bütçe + toplamlar', data: () => real.withTotals(real.withBudgetCapped(real.realData(2))), prev: () => null },
]

beforeEach(() => { calls.text = []; calls.tables = []; xl.sheets = null })

function cellsOf(sheets) {
  const out = []
  for (const s of sheets) for (const r of s.rows) for (const c of r) if (typeof c === 'string') out.push([s.name, c])
  return out
}

describe('Excel — gerçek yanıtla ham kod / anahtar yok', () => {
  for (const [lang, D] of [['tr', TR], ['en', EN]]) {
    for (const sc of SCANS) {
      it(`${lang}: ${sc.name}`, () => {
        const d = sc.data()
        exportResourceScanXlsx({ monitor: real.REAL_MONITOR, scan: d.scan, analysis: d.analysis, items: d.items, budget: d.budget,
          prev: sc.prev(), t: tOf(D), locale: lang === 'tr' ? 'tr-TR' : 'en-GB' })
        const bad = []
        for (const [sheet, c] of cellsOf(xl.sheets)) {
          for (const part of c.split(/\n|, |; | · /)) {
            const v = part.trim()
            if (RAW_CODES.has(v) || CODE_SHAPE.test(v)) bad.push(`${sheet}: ${v}`)
          }
          if (/\bprs\.[a-z]+\.|\{\d\}|\bundefined\b|\bNaN\b|\[object Object\]/.test(c)) bad.push(`${sheet}: ${c.slice(0, 80)}`)
        }
        expect(bad).toEqual([])
      })
    }
  }

  it('A–E sayfalarında çeviriler: engelleyen tür / neden, tekrar türü, başlık sorunu, çerez sorunu, açık önemi, HTTP sürümü', () => {
    const d = real.realData(1)
    exportResourceScanXlsx({ monitor: real.REAL_MONITOR, ...d, t: tOf(TR), locale: 'tr-TR' })
    const flat = (name) => xl.sheets.find((s) => s.name === name).rows.flat().filter((c) => typeof c === 'string').join('\n')
    expect(flat('Yükleme ve ağ')).toMatch(new RegExp(TR['prs.deep.rb.reason.no_media']))
    expect(flat('Yükleme ve ağ')).toMatch(/HTTP\/1\.1/)
    expect(flat('İsraf ve önbellek')).toMatch(new RegExp(TR['prs.deep.dup.kind.same_content']))
    expect(flat('Güvenlik')).toContain(TR['prs.check.cookieIssue.no_http_only'])
    expect(flat('Güvenlik')).toContain(TR['prs.deep.sec.sev.medium'])
  })

  it('site sayfaları: hata sütunu sunucunun cümlesi; kod biçimindeki hata çevrilir', () => {
    exportResourceScanXlsx({ monitor: real.REAL_MONITOR, ...real.withSiteIncomplete(real.realData(3), { asCodes: true }), t: tOf(TR), locale: 'tr-TR' })
    const rows = xl.sheets.find((s) => s.name === 'Sayfalar').rows
    const errs = rows.slice(1).map((r) => r.at(-1)).filter(Boolean)
    expect(errs).toEqual(expect.arrayContaining([expect.stringMatching(/başka bir siteye yönlendirdi/), expect.stringMatching(/Sayfaya ulaşılamadı/),
      expect.stringMatching(/indirme sınırı doldu/)]))
  })
})

describe('PDF — gerçek yanıtla her karakter Roboto\'da, ham kod yok', () => {
  for (const [lang, D] of [['tr', TR], ['en', EN]]) {
    for (const sc of SCANS) {
      it(`${lang}: ${sc.name}`, async () => {
        const d = sc.data()
        await exportResourceScanPdf({ monitor: real.REAL_MONITOR, scan: d.scan, analysis: d.analysis, items: d.items, budget: d.budget,
          prev: sc.prev(), t: tOf(D), locale: lang === 'tr' ? 'tr-TR' : 'en-GB' })
        const tableText = calls.tables.flatMap((o) => [...(o.head || []).flat(), ...(o.body || []).flat()]).map(String)
        const all = [...calls.text, ...tableText]
        const missing = new Set()
        for (const s of all) for (const ch of missingGlyphs(s, REGULAR)) missing.add(ch)
        for (const s of all) for (const ch of missingGlyphs(s, BOLD)) missing.add(ch)
        // Adresler (test sunucusu) ve sunucu içeriği Latin; eksik glif = arayüz metninden
        expect([...missing]).toEqual([])
        const bad = all.filter((s) => /\bprs\.[a-z]+\.|\{\d\}|\bundefined\b|\bNaN\b|\[object Object\]/.test(s))
        expect(bad).toEqual([])
        expect(all.join('\n')).not.toMatch(/\.,|(?<!\.)\.\.(?!\.)/)
      })
    }
  }

  it('güvenlik başlıkları okunamadı: "bulgu yok" yazılmaz, "okunamadı" yazılır', async () => {
    const d = real.withSecurityUnread(real.realData(1))
    d.analysis.security = { ...d.analysis.security, cookies: [], cookies_total: 0, sri_missing: [], sri_missing_total: 0, libraries: [], tracking_on_load: [] }
    await exportResourceScanPdf({ monitor: real.REAL_MONITOR, ...d, t: tOf(TR), locale: 'tr-TR' })
    const text = calls.tables.flatMap((o) => (o.body || []).flat()).map(String).join('\n')
    expect(text).toContain(TR['prs.pdf.secu.headersUnknown'])
    expect(text).not.toContain(TR['prs.pdf.secu.noFindings'])
  })

  it('PDF yönetici özetinde sayılar *_total\'dan (50 satır kesiği değil)', async () => {
    const d = real.withTotals(real.realData(1))
    await exportResourceScanPdf({ monitor: real.REAL_MONITOR, ...d, t: tOf(TR), locale: 'tr-TR' })
    const text = calls.tables.flatMap((o) => (o.body || []).flat()).map(String).join('\n')
    expect(text).toMatch(/130 görsel/)
  })
})

describe('öneri metinleri — gerçek parametrelerle', () => {
  const recs = real.realData(1).analysis.recommendations
  const body = (code, D = TR) => {
    const r = recs.find((x) => x.code === code)
    return tOf(D)(recTextKey(r, 'body'), ...recommendationArgs(r, 'tr-TR', 'body', tOf(D)))
  }
  it('UNMINIFIED_ASSETS gövdesi kazancı (saving_bytes 9 KB) söyler, toplam boyutu (36 KB) değil', () => {
    expect(body('UNMINIFIED_ASSETS')).toMatch(/9(,0)? KB/)
    expect(body('UNMINIFIED_ASSETS')).not.toMatch(/36(,2)? KB/)
  })
  it('WEAK_CSP: sorun cümleleri ".," / ".." olmadan birleşir', () => {
    for (const D of [TR, EN]) {
      const b = body('WEAK_CSP', D)
      expect(b).not.toMatch(/\.,|(?<!\.)\.\.(?!\.)|\.;/)
      expect(b).toMatch(/; /)
    }
  })
})

describe('Roboto kapsamı — PDF\'e basılabilen bütün prs.* metinleri', () => {
  const FAMILIES = /^prs\.(pdf|rec|budget|site|export|note|check|sev|cat|score|deep\.tpc|grade|kpi|status|verdict|bucket|delta|via|finding|objProblem|chkfail)\./
  for (const [lang, D] of [['tr', TR], ['en', EN]]) {
    it(`${lang}: eksik glif yok`, () => {
      const bad = {}
      for (const [k, v] of Object.entries(D)) {
        if (!FAMILIES.test(k) || typeof v !== 'string') continue
        const m = [...new Set([...missingGlyphs(v, REGULAR), ...missingGlyphs(v, BOLD)])]
        if (m.length) bad[k] = m.join('')
      }
      expect(bad).toEqual({})
    })
  }
})
