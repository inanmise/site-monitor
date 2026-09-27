import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * styles/globals.css `@layer base` kuralları — projede Tailwind preflight YOK, shadcn'in preflight'tan beklediği
 * birkaç sıfırlama elle burada. Kaybolurlarsa jsdom testleri yeşil kalır (jsdom yerleşim/renk hesaplamaz) ama ekran
 * bozulur; bu yüzden metin düzeyinde pinlenir.
 *  - `[hidden]` her zaman gizler (2026-09-25: Bildirimler paneli yarım yükseklik; e2e/inbox-panel.spec.js).
 *  - Varsayılan kenar rengi (2026-09-26): Tailwind v4'te currentColor → data-slot'suz `border` SİYAH çiziyordu.
 */
const css = fs.readFileSync(path.join(__dirname, '..', 'styles', 'globals.css'), 'utf8')

/** `@layer base { … }` bloğunun gövdesi (iç içe süslü parantezleri sayarak). */
function baseLayer(src) {
  const start = src.indexOf('@layer base {')
  expect(start, '@layer base bloğu yok').toBeGreaterThanOrEqual(0)
  let depth = 0
  for (let i = src.indexOf('{', start); i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1)
  }
  throw new Error('@layer base kapanmıyor')
}

describe('globals.css base katmanı', () => {
  const base = baseLayer(css)

  it('varsayılan kenar rengi TÜM öğelere base katmanında verilir (yalnız renk)', () => {
    const rule = base.match(/\*\s*,\s*::before\s*,\s*::after[^{]*\{([^}]*)\}/)
    expect(rule, '`*, ::before, ::after { border-color: … }` kuralı yok').not.toBeNull()
    expect(rule[1]).toMatch(/border-color\s*:\s*var\(--color-border\)/)
    // Yalnız renk: genişlik/stil verirse kenarı olmayan her öğeye kenar çizerdi
    expect(rule[1]).not.toMatch(/border(-width|-style)?\s*:\s*\d/)
  })

  it('sınıf taşıyan listelerde madde işareti yok (ul[class] list-style: none) — sınıfsız Markdown listeleri hariç', () => {
    const rule = base.match(/ul\[class\][^{]*\{([^}]*)\}/)
    expect(rule, '`ul[class] { list-style: none }` kuralı yok').not.toBeNull()
    expect(rule[1]).toMatch(/list-style\s*:\s*none/)
    // Yalnız işaret: dolgu/kenar boşluğu sıfırlanırsa legacy listeler UA dolgusunu kaybeder
    expect(rule[1]).not.toMatch(/padding|margin/)
    const noComments = base.replace(/\/\*[\s\S]*?\*\//g, '')   // yorumlar preflight kuralını alıntılıyor
    expect(noComments).not.toMatch(/(^|[^\w\[.-])ul\s*[,{]/)   // çıplak `ul {` yok → sınıfsız listeler dokunulmaz
  })

  it('[hidden] özniteliği her zaman gizler', () => {
    expect(base).toMatch(/\[hidden\][^{]*\{\s*display\s*:\s*none\s*!important/)
  })

  it('bağlantı olarak çizilen shadcn öğeleri (a[data-slot]) tarayıcı bağlantı rengini/alt çizgisini almaz', () => {
    // Seçici başında `a[` — `textarea[data-slot]` içindeki "a[data-slot]" alt dizesi eşleşmesin
    const rule = base.match(/(^|[\s,{};])a\[data-slot\][^{]*\{([^}]*)\}/)
    expect(rule, '`a[data-slot] { color: inherit; text-decoration: inherit }` kuralı yok').not.toBeNull()
    expect(rule[2]).toMatch(/color\s*:\s*inherit/)
    expect(rule[2]).toMatch(/text-decoration\s*:\s*inherit/)
    // Yalnız data-slot'lu bağlantılar: çıplak `a {` Markdown/yardım metnindeki bağlantıları da renksizleştirirdi
    const noComments = base.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(noComments).not.toMatch(/(^|[\s,}])a\s*[,{]/)
  })
})
