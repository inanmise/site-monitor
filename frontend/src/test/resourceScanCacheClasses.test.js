import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  CACHE_CLASSES, CACHE_CLASS_FINDING, CACHE_CLASS_TONE, CACHE_FINDINGS, FINDING_CODES, FINDING_TONE, REC_CODES, REC_PLURAL, SCORE_RECS,
  areaOfRec, cacheClassOf, cacheText, cachingView, recommendationArgs, withFindings,
} from '../components/page/resourcescan/resourceScanModel.js'
import { deepSheets, executiveSections } from '../components/page/resourcescan/resourceScanReportModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * Kaynak analizi algoritma 4 (2026-11-15) — önbellek sınıfları: istemci listeleri sunucunun Java kaynağıyla AYNI (sınıflar,
 * sınıf → kod eşlemesi, öneri / bulgu kodları), her dinamik anahtar iki dilde var, önbellek metni sınıftan (eski nesnede eski
 * ömür metni), israf bloğu ve rapor (PDF satırı + Excel bloğu) ham kod yazmaz.
 */
const JAVA = path.resolve(process.cwd(), '../backend/src/main/java/com/sitemonitor/service/page/scan')
const policy = fs.readFileSync(path.join(JAVA, 'CachePolicy.java'), 'utf8')
const analyzer = fs.readFileSync(path.join(JAVA, 'PageResourceAnalyzer.java'), 'utf8')

/** `NAME = "değer"` sabitleri (aynı satırda virgülle birden çok). */
function stringConsts(src) {
  const out = {}
  for (const m of src.matchAll(/\b([A-Z_]+) = "([a-z_]+)"/g)) out[m[1]] = m[2]
  return out
}
/** Java `Set.of(...)` / `List.of(...)` bloğundaki tırnaklı kodlar (yorumlar atlanır). */
function javaCodes(src, name) {
  const start = src.indexOf(name)
  const open = src.indexOf('(', start)
  let depth = 0
  let end = open
  for (let i = open; i < src.length; i++) {
    if (src[i] === '(') depth++
    else if (src[i] === ')') { depth--; if (depth === 0) { end = i; break } }
  }
  const body = src.slice(open, end).replace(/\/\/[^\n]*/g, '')
  return [...body.matchAll(/"([A-Z_]+)"/g)].map((m) => m[1])
}

const t = (D) => (k, ...a) => {
  const v = D[k]
  if (typeof v !== 'string') return k
  return v.replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)]))
}

describe('sunucuyla aynı listeler', () => {
  it('sınıflar: CachePolicy.CLASSES ile aynı sıra', () => {
    const consts = stringConsts(policy)
    const names = /CLASSES = List\.of\(([^)]*)\)/.exec(policy)[1].split(',').map((s) => s.trim())
    expect(names.map((n) => consts[n])).toEqual(CACHE_CLASSES)
  })
  it('sınıf → kod eşlemesi: PageResourceAnalyzer.CACHE_CLASS_CODE ile aynı', () => {
    const consts = stringConsts(policy)
    const block = /CACHE_CLASS_CODE = Map\.of\(([\s\S]*?)\);/.exec(analyzer)[1]
    const pairs = [...block.matchAll(/CachePolicy\.([A-Z_]+), "([A-Z_]+)"/g)].map((m) => [consts[m[1]], m[2]])
    expect(Object.fromEntries(pairs)).toEqual(CACHE_CLASS_FINDING)
  })
  it('sunucunun her öneri kodu istemcide metinli; her bulgu kodu istemcinin bulgu listesinde', () => {
    const recs = javaCodes(analyzer, 'RECOMMENDATION_CODES = ')
    expect(recs.length).toBeGreaterThan(40)
    for (const c of recs) expect(REC_CODES, c).toContain(c)
    const findings = javaCodes(analyzer, 'FINDING_CODES = Set.of')
    for (const c of findings) expect(FINDING_CODES, c).toContain(c)
    for (const c of Object.values(CACHE_CLASS_FINDING)) {
      expect(findings, c).toContain(c)
      expect(FINDING_TONE[c], c).toBeTruthy()
      expect(areaOfRec(c), c).toBe('waste')
      expect(SCORE_RECS.caching, c).toContain(c)
      expect(CACHE_FINDINGS.has(c), c).toBe(true)
    }
    expect(CACHE_FINDINGS.has('NO_CACHE')).toBe(true)
    // her ziyarette doğrulanan / sezgisel bilgi tonu; tamamı yeniden inenler uyarı
    expect(FINDING_TONE.CACHE_REVALIDATE).toBe('info')
    expect(FINDING_TONE.CACHE_HEURISTIC).toBe('info')
    expect(FINDING_TONE.CACHE_NO_STORE).toBe('warning')
    for (const c of CACHE_CLASSES) expect(CACHE_CLASS_TONE[c], c).toBeTruthy()
  })
})

describe('sözlük — dinamik anahtarlar iki dilde', () => {
  const keys = [
    ...CACHE_CLASSES.flatMap((c) => [`prs.cache.class.${c}`, `prs.cache.why.${c}`]),
    ...Object.values(CACHE_CLASS_FINDING).flatMap((c) => [`prs.finding.${c}`, `prs.site.rec.${c}`, `prs.rec.${c}.title`,
      `prs.rec.${c}.title.one`, `prs.rec.${c}.body`, `prs.rec.${c}.body.one`]),
    'prs.deep.cache.classCount.one', 'prs.pdf.xl.cacheClasses', 'prs.pdf.xl.cacheClass', 'prs.pdf.waste.cacheClasses',
  ]
  it.each(keys)('%s', (k) => {
    expect(TR[k], `TR ${k}`).toBeTruthy()
    expect(EN[k], `EN ${k}`).toBeTruthy()
  })
  it('öneri metinleri: başlık sayı, gövde parametresiz; tekil biçimler', () => {
    for (const c of Object.values(CACHE_CLASS_FINDING)) {
      expect(REC_PLURAL[c]).toEqual({ title: 'count', body: 'count' })
      expect(recommendationArgs({ code: c, params: { count: 3 } }, 'tr-TR', 'title')).toEqual(['3'])
      for (const D of [TR, EN]) {
        expect(D[`prs.rec.${c}.title`]).toContain('{0}')
        expect(D[`prs.rec.${c}.body`]).not.toMatch(/\{\d\}/)
      }
    }
    expect(EN['prs.rec.CACHE_REVALIDATE.title.one']).toBe('{0} object is checked with the server on every visit')
  })
})

describe('önbellek metni ve sınıf', () => {
  const tr = t(TR)
  const en = t(EN)
  it('algoritma 4 nesnesi: sınıftan (kısa / sezgisel ömrüyle); bilinmeyen sınıf değeri sınıf sayılmaz', () => {
    expect(cacheText({ cache_class: 'stored', cache_ttl_s: 86_400 }, tr, 'tr-TR')).toBe('Önbellek 1 g')
    expect(cacheText({ cache_class: 'short', cache_ttl_s: 300 }, tr, 'tr-TR')).toBe('Kısa ömür · 5 dk')
    expect(cacheText({ cache_class: 'heuristic', cache_ttl_s: null, cache_heuristic_s: 259_200 }, en, 'en-GB'))
      .toBe('No lifetime given · browser guesses ~3 d')
    expect(cacheText({ cache_class: 'heuristic', cache_ttl_s: null }, en, 'en-GB')).toBe(EN['prs.cache.class.heuristic'])
    expect(cacheText({ cache_class: 'revalidate', cache_ttl_s: -1 }, tr, 'tr-TR')).toBe(TR['prs.cache.class.revalidate'])
    expect(cacheText({ cache_class: 'no_store', cache_ttl_s: -1 }, en, 'en-GB')).toBe('Never stored (no-store)')
    expect(cacheText({ cache_class: 'none', cache_ttl_s: null }, tr, 'tr-TR')).toBe(TR['prs.cache.class.none'])
    expect(cacheClassOf({ cache_class: 'bogus' })).toBeNull()
    // eski (sürüm ≤ 3) nesne: eski metin (sınıf uydurulmaz)
    expect(cacheText({ cache_ttl_s: null }, tr, 'tr-TR')).toBe(TR['prs.obj.cacheNone'])
    expect(cacheText({ cache_ttl_s: -1 }, tr, 'tr-TR')).toBe(TR['prs.obj.cacheNoStore'])
    expect(cacheText({ cache_ttl_s: 600 }, tr, 'tr-TR')).toBe('Önbellek 10 dk')
  })

  it('bulgular: sunucunun CACHE_* kodları korunur; eski nesnede istemci kuralı NO_CACHE (değişmedi)', () => {
    const base = { status: 200, category: 'JS', page_load: true, third_party: false, verdict: 'ok', bytes: 1000 }
    const rows = withFindings([
      { ...base, url: 'a.js', cache_class: 'revalidate', findings: ['CACHE_REVALIDATE', 'NO_VALIDATOR'] },
      { ...base, url: 'b.js', cache_ttl_s: null },
    ], [])
    expect(rows[0].findings).toEqual(['NO_VALIDATOR', 'CACHE_REVALIDATE'].filter((c) => FINDING_CODES.includes(c))
      .sort((x, y) => FINDING_CODES.indexOf(x) - FINDING_CODES.indexOf(y)))
    expect(rows[1].findings).toEqual(['NO_CACHE'])
  })

  it('israf bloğu: sınıf sayıları + ikinci ziyaret isteği; eski blokta null (uydurulmaz)', () => {
    const v = cachingView({ caching: { buckets: { none: 2 }, classes: { stored: 3, short: 1, revalidate: 2, heuristic: 0, none: 1, no_store: 0 },
      repeat_visit_requests: 4, repeat_visit_bytes: 100, repeat_visit_pct: 10 } })
    expect(v.classes.map((c) => c.key)).toEqual(CACHE_CLASSES)
    expect(v.classes.find((c) => c.key === 'revalidate')).toEqual({ key: 'revalidate', count: 2, finding: 'CACHE_REVALIDATE' })
    expect(v.classes.find((c) => c.key === 'stored').finding).toBeNull()
    expect(v.classTotal).toBe(7)
    expect(v.repeatRequests).toBe(4)
    const old = cachingView({ caching: { buckets: { none: 2 } } })
    expect(old.classes).toBeNull()
    expect(old.repeatRequests).toBeNull()
  })
})

describe('rapor — sınıf satırı ve Excel bloğu çevrili', () => {
  const analysis = (classes) => ({
    algorithm_version: 4,
    waste: { caching: { buckets: { none: 1, lt_1h: 1, lt_1d: 0, lt_7d: 0, ge_7d: 2 }, ...(classes ? { classes } : {}), repeat_visit_bytes: 10, repeat_visit_pct: 5 } },
  })
  const classes = { stored: 2, short: 1, revalidate: 3, heuristic: 1, none: 1, no_store: 2 }
  for (const [lang, D, loc] of [['tr', TR, 'tr-TR'], ['en', EN, 'en-GB']]) {
    it(`${lang}: PDF satırı sayıları sırasıyla verir; Excel bloğu sınıf adı + sayı + anlam (ham kod yok)`, () => {
      const lines = executiveSections(analysis(classes), t(D), loc).find((s) => s.key === 'waste').lines
      const line = lines.find((l) => l.startsWith(D['prs.pdf.waste.cacheClasses'].split('{0}')[0]))
      expect(line).toBe(t(D)('prs.pdf.waste.cacheClasses', '2', '1', '3', '1', '1', '2'))
      const rows = deepSheets(analysis(classes), t(D), loc).find((s) => s.key === 'waste').rows
      const at = rows.findIndex((r) => r[0] === D['prs.pdf.xl.cacheClasses'])
      expect(at).toBeGreaterThanOrEqual(0)
      const flat = rows.flat().filter((c) => typeof c === 'string')
      for (const k of CACHE_CLASSES) {
        expect(flat).toContain(D[`prs.cache.class.${k}`])
        expect(flat).toContain(D[`prs.cache.why.${k}`])
      }
      for (const raw of ['no_store', 'revalidate', 'heuristic']) expect(flat).not.toContain(raw)
    })
  }
  it('eski analiz (sınıf yok): PDF satırı / Excel bloğu yok', () => {
    const lines = executiveSections(analysis(null), t(TR), 'tr-TR').find((s) => s.key === 'waste').lines
    expect(lines.some((l) => l.startsWith('Önbellek sınıfları'))).toBe(false)
    const rows = deepSheets(analysis(null), t(TR), 'tr-TR').find((s) => s.key === 'waste').rows
    expect(rows.some((r) => r[0] === TR['prs.pdf.xl.cacheClasses'])).toBe(false)
  })
})
