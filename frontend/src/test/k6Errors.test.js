import { describe, it, expect } from 'vitest'
import { parseK6Markers, collectK6Markers } from '../utils/k6Errors.js'

/**
 * k6 hata metninden satır:sütun çıkarımı. Aşağıdaki metinler UYDURMA DEĞİL — backend testinde
 * (`ScriptedCheckerServiceTest`) pinlenmiş gerçek k6 v0.49 çıktısının işlenmiş hâlidir.
 */
const BABEL_FRAME = `script: Unexpected token (46:29)
  44 |       try {
  45 |         const body = JSON.parse(r.body);
> 46 |         const content = body?.choices?.[0]?.message?.content || '';
     |                              ^
  47 |         return content.toLowerCase().includes('paris');`

const STACK = 'GoError: bir şey patladı\n\tat script:34:12(24)\n\tat native'

describe('parseK6Markers', () => {
  it('Babel derleme hatasından satır ve sütunu çıkarır', () => {
    const m = parseK6Markers(BABEL_FRAME)
    expect(m).toHaveLength(1)
    expect(m[0]).toMatchObject({ line: 46, column: 29, type: 'error' })
  })

  it('k6 yığın izindeki `script:satır:sütun` biçimini de okur', () => {
    // sanitizeScriptPath temp yolu `script`e çevirdiği için bu biçim oluşuyor.
    expect(parseK6Markers(STACK)[0]).toMatchObject({ line: 34, column: 12 })
  })

  it('aynı satır birden çok geçse TEK marker üretir (ilk eşleşme kazanır)', () => {
    // Babel çerçevesinde satır hem "(46:29)" hem "> 46 |" olarak geçiyor; ikincisi zaten
    // desene uymuyor ama metinde başka bir "(46:x)" olursa da çoğaltmamalı.
    const m = parseK6Markers('hata (46:29) ve yine (46:3)')
    expect(m).toHaveLength(1)
    expect(m[0].column).toBe(29)
  })

  it('k6 2.x (sobek) biçimi: `script: Line 4:1 Unexpected token` (2026-10-29, gerçek 2.3 çıktısı)', () => {
    // backend K6UpgradeEngineTest.syntaxErrorBothVersions bu metni gerçek k6 v2.3.0 çıktısından üretiyor
    const m = parseK6Markers('script: Line 4:1 Unexpected token ; (and 1 more errors)')
    expect(m).toHaveLength(1)
    expect(m[0]).toMatchObject({ line: 4, column: 1, type: 'error' })
  })

  it('desen backend K6_LINE_COL ile birebir aynı (kaynaktan okunur)', async () => {
    const fs = await import('node:fs')
    const path = await import('node:path')
    const java = fs.readFileSync(path.resolve(__dirname,
      '../../../backend/src/main/java/com/sitemonitor/service/ScriptedCheckerService.java'), 'utf8')
    const m = /K6_LINE_COL = Pattern\.compile\("((?:[^"\\]|\\.)*)"\)/.exec(java)
    expect(m).toBeTruthy()
    const javaRegex = m[1].replace(/\\\\/g, '\\')
    const js = fs.readFileSync(path.resolve(__dirname, '../utils/k6Errors.js'), 'utf8')
    const jsRegex = /return \/(.*)\/g/.exec(js)[1]
    expect(jsRegex).toBe(javaRegex)
    expect(javaRegex).toBe(String.raw`\((\d+):(\d+)\)|script:(\d+):(\d+)|\bLine (\d+):(\d+)`)
  })

  it('birden çok FARKLI satır sırayla döner', () => {
    const m = parseK6Markers('ilk (12:1) sonra script:34:5')
    expect(m.map(x => x.line)).toEqual([12, 34])
  })

  it('satır bilgisi olmayan metin → boş (yanlış satır işaretlenmesin)', () => {
    expect(parseK6Markers('Request Failed — request timeout')).toEqual([])
    expect(parseK6Markers('')).toEqual([])
    expect(parseK6Markers(null)).toEqual([])
    expect(parseK6Markers(undefined)).toEqual([])
  })

  it('0 veya negatif satır yok sayılır', () => {
    expect(parseK6Markers('bozuk (0:5)')).toEqual([])
  })

  it('tip parametresi marker\'a geçer', () => {
    expect(parseK6Markers('(3:1)', 'warning')[0].type).toBe('warning')
  })
})

describe('collectK6Markers', () => {
  it('üç kaynağı birleştirir; aynı satırda ŞİDDETLİ olan kazanır', () => {
    const out = collectK6Markers({
      saveError: 'Script derlenemedi: (10:2)',
      warnings: ['uyarı (10:9)', 'başka uyarı (20:1)'],
      runError: 'GoError\n\tat script:30:4(1)',
    })
    expect(out.map(m => [m.line, m.type]))
      .toEqual([[10, 'error'], [20, 'warning'], [30, 'error']])
  })

  it('boş girdi → boş liste (bayat marker kalmasın)', () => {
    expect(collectK6Markers({})).toEqual([])
    expect(collectK6Markers()).toEqual([])
    expect(collectK6Markers({ saveError: null, warnings: null, runError: null })).toEqual([])
  })
})
