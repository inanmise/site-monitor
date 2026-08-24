import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Tanimsiz CSS degiskeni kapisi.
 *
 * Bir `var(--x)` YEDEKSIZ kullanilir ve `--x` hicbir yerde tanimli degilse, CSS ozelligi
 * "invalid at computed-value time" olur ve TAMAMEN DUSER — konsolda hata yok, derlemede uyari yok,
 * yalnizca yanlis ekran. Bu gercekten yasandi: `--hover`, `--bg-secondary`, `--accent`,
 * `--card-bg`, `--text-secondary`, `--bg-subtle` gibi 20 kadar isim kod tabaninda KULLANILIYORDU
 * ama HICBIR YERDE tanimli degildi. Sonuclari:
 *   • `.mon-row:hover`, `.toc-item:hover`, `.upt-filter-pill:hover` → hicbir vurgu vermiyordu
 *   • `.upt-filter-pill--active` → beyaz yazi + dusen arka plan = GORUNMEZ
 *   • `.show-markdown pre/code` → kod bloklari arka plansiz
 * YEDEKLI olanlar cokmuyordu ama sabit acik-tema hex'ine dusuyordu: `var(--surface, #fff)` KOYU
 * temada da BEYAZ boyuyordu. Ikisi de burada kapatildi.
 *
 * jsdom yerlesim yapmaz — bu yuzden gorsel dogrulama degil, KAYNAK butunlugu dogrulaniyor.
 */
describe('CSS token butunlugu', () => {
  const walk = (dir, out = []) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name)
      if (e.isDirectory()) walk(p, out)
      else out.push(p)
    }
    return out
  }
  const all = walk('src')
  const cssFiles = all.filter(f => f.endsWith('.css'))
  const codeFiles = all.filter(f => /\.(jsx?|tsx?)$/.test(f))

  const defined = new Set()
  // 1) CSS bildirimleri
  for (const f of cssFiles) {
    for (const m of readFileSync(f, 'utf8').matchAll(/(^|[;{\s])(--[A-Za-z0-9_-]+)\s*:/g)) defined.add(m[2])
  }
  // 2) Calisma zamaninda JS'ten atananlar (style={{'--x': …}} / setProperty('--x', …)) DA tanimlidir.
  for (const f of codeFiles) {
    for (const m of readFileSync(f, 'utf8').matchAll(/['"](--[A-Za-z0-9_-]+)['"]/g)) defined.add(m[1])
  }

  const scan = (re) => {
    const missing = {}
    for (const f of cssFiles) {
      readFileSync(f, 'utf8').split('\n').forEach((line, i) => {
        for (const m of line.matchAll(re)) {
          if (!defined.has(m[1])) (missing[m[1]] ??= []).push(`${f}:${i + 1}`)
        }
      })
    }
    return missing
  }

  it('YEDEKSIZ var(--x): tanimsizsa ozellik sessizce duser — hic olmamali', () => {
    expect(scan(/var\(\s*(--[A-Za-z0-9_-]+)\s*\)/g)).toEqual({})
  })

  it('YEDEKLI var(--x, …): tanimsizsa sabit renge duser, tema/marka dinlemez — hic olmamali', () => {
    expect(scan(/var\(\s*(--[A-Za-z0-9_-]+)\s*,/g)).toEqual({})
  })
})
