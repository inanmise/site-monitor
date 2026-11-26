// Kapı (2026-11-26, Nöbet Rutinleri sağ ray hatası): Tailwind v4 kapsayıcı sorgularını birime göre sıralar — px cinsinden
// keyfi değerler (`@min-[900px]`, `@[720px]`) rem cinsinden adlandırılmış boyutlardan (`@md` = 28rem, `@2xl` = 42rem …)
// ÖNCE üretilir (rem cinsinden keyfi değerler adlandırılmışlarla değerine göre sıralanır — onlar sorun değildir). Aynı
// öğede aynı özelliği hem adlandırılmış hem px gibi rem dışı keyfi bir kapsayıcı sorgusuyla yazmak, "geniş ekranda şu
// kural geçsin" niyetini sessizce tersine çevirir: `@md/page:grid-cols-2 @min-[900px]/page:grid-cols-1` geniş ekranda iki
// sütun verdi ve kartlar yan yana sıkıştı. Kural: aynı sınıf dizgesinde aynı kapsayıcı adı + aynı yardımcı kökü için
// adlandırılmış ve keyfi `@min-` / `@[` sorgularını KARIŞTIRMA; gerekiyorsa aralık yaz (`@min-[42rem]/page:@max-[900px]/page:`).
import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const SRC = join(__dirname, '..')
const NAMED = /^@(3xs|2xs|xs|sm|md|lg|xl|[2-7]xl)(\/[\w-]+)?$/
// rem cinsinden keyfi değerler adlandırılmışlarla değerine göre sıralanır (sorun yok); yalnız rem DIŞI birimler önce gelir
const ARBITRARY = /^@(min-)?\[(?![\d.]+rem\])[^\]]+\](\/[\w-]+)?$/

function* files(dir) {
  for (const name of readdirSync(dir)) {
    if (name === 'test' || name === 'node_modules') continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) yield* files(p)
    else if (/\.(jsx?|mjs)$/.test(name)) yield p
  }
}

/** Yardımcının özellik kökü: `grid-cols-2` → `grid-cols`, `col-start-auto` → `col-start`, `flex` → `display`. */
function rootOf(utility) {
  if (['flex', 'grid', 'block', 'hidden', 'inline', 'inline-flex', 'table-cell', 'contents'].includes(utility)) return 'display'
  const i = utility.lastIndexOf('-')
  return i > 0 ? utility.slice(0, i) : utility
}

/** Bir sınıf dizgesindeki çakışmalar: aynı kapsayıcı adı + kök için hem adlandırılmış hem keyfi TEK yönlü sorgu. */
function conflicts(classString) {
  const groups = new Map()
  for (const token of classString.split(/\s+/)) {
    if (!token.startsWith('@')) continue
    const parts = token.split(/:(?![^[]*\])/)
    if (parts.length < 2) continue
    const utility = parts.pop()
    const variants = parts
    if (variants.some((v) => v.startsWith('@max-'))) continue          // aralık — sıra bağımsız
    if (variants.length !== 1) continue
    const v = variants[0]
    const kind = NAMED.test(v) ? 'named' : ARBITRARY.test(v) ? 'arbitrary' : null
    if (!kind) continue
    const name = (v.match(/\/([\w-]+)$/) || [])[1] || ''
    const key = `${name}|${rootOf(utility)}`
    const g = groups.get(key) || new Set()
    g.add(kind)
    groups.set(key, g)
  }
  return [...groups.entries()].filter(([, kinds]) => kinds.size > 1).map(([k]) => k)
}

describe('kapsayıcı sorgusu sırası', () => {
  it('kural hatayı yakalar, aralığı ve tek türü geçirir', () => {
    expect(conflicts('grid gap-3 @md/page:grid-cols-2 @min-[900px]/page:grid-cols-1')).toEqual(['page|grid-cols'])
    expect(conflicts('flex @sm:flex-row @[600px]:flex-col')).toEqual(['|flex'])
    expect(conflicts('grid @min-[42rem]/page:@max-[900px]/page:grid-cols-2')).toEqual([])
    expect(conflicts('grid @md:grid-cols-2 @xl:grid-cols-3')).toEqual([])
    expect(conflicts('grid @min-[16rem]:flex-row @min-[16rem]:items-center')).toEqual([])
    expect(conflicts('@md/a:grid-cols-2 @min-[900px]/b:grid-cols-1')).toEqual([])
    // rem keyfi değer adlandırılmışlarla değerine göre sıralanır: 14rem < @sm (24rem) — doğru sıra
    expect(conflicts('grid-cols-1 @[14rem]:grid-cols-2 @sm:grid-cols-4')).toEqual([])
  })

  it('kaynakta adlandırılmış + keyfi tek yönlü kapsayıcı sorgusu aynı öğede aynı özelliği yazmaz', () => {
    const bad = []
    for (const f of files(SRC)) {
      const text = readFileSync(f, 'utf8')
      if (!text.includes('@')) continue
      for (const m of text.matchAll(/(["'`])((?:(?!\1)[^\n\\])*)\1/g)) {
        if (!m[2].includes('@')) continue
        const found = conflicts(m[2])
        if (found.length) bad.push(`${relative(SRC, f)}: ${found.join(', ')} — "${m[2].slice(0, 120)}"`)
      }
    }
    expect(bad).toEqual([])
  })
})
