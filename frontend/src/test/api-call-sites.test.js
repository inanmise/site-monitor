import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { api } from '../api/client.js'

/**
 * API çağrı yeri bekçisi: üretim ağacındaki HER `api.<zincir>(...)` çağrısı, gerçek
 * `api/client.js` nesnesinde bir FONKSİYONA çözülmek zorundadır.
 *
 * NEDEN VAR: 2026-08-21'de şablon kütüphanesinin 14 çağrı yerinin tamamı uçları `api.*`
 * kökünden çağırıyordu; uçlar ise `api.monitoring.*` altında tanımlıydı. Tarayıcıda
 * "api.getScriptedTemplates is not a function" ile HİÇ çalışmadı, ama 1058 testin tamamı
 * yeşildi: `withApiFallback` (apiMock.js) bilinmeyen bir adı görünce onu otomatik üretip
 * "başarılı boş yanıt" döndürüyor. O yedek katman gerçek bir hatayı (SystemHealth'te eksik
 * mock) çözmek için var ve KALMALI — ama ad alanı yanlışını da yutuyor. Bu test tam o
 * boşluğu kapatır: mock'a değil, GERÇEK istemciye bakar.
 *
 * Kapsam sınırı (bilinçli): yalnız `api`'yi client'tan import eden dosyalar ve yalnız NOKTA
 * ile yazılmış statik zincirler taranır. `api.monitoring[dinamikAd]()` gibi hesaplanmış
 * erişim bu testin göremeyeceği bir yerdir — yeni kod yazarken statik zinciri tercih et.
 * Ayrıca `api` adını PARAMETRE olarak gölgeleyen fonksiyonların gövdesi atlanır (ör.
 * `transformSelectedLines(state, api, fn)` — orada `api` markdown editörünün kendi yüzeyi).
 * Gölgeleme mantığı sessizce genişleyip kapıyı boşaltmasın diye taranan çağrı sayısı da
 * alt sınırla pinlenir.
 */
const SRC = path.resolve(__dirname, '..')
const SKIP_DIRS = new Set(['test', 'assets'])
const IMPORTS_API = /import\s*\{[^}]*\bapi\b[^}]*\}\s*from\s*['"][^'"]*api\/client(\.js)?['"]/
// `api.a.b(` → zincir ".a.b" (satır sonu/boşluk toleranslı, çağrı parantezine kadar)
const CALL = /\bapi((?:\.[A-Za-z_$][\w$]*)+)\s*\(/g

function walk(dir, acc = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) walk(path.join(dir, entry.name), acc)
    } else if (/\.jsx?$/.test(entry.name)) {
      acc.push(path.join(dir, entry.name))
    }
  }
  return acc
}

/**
 * `api`'yi parametre olarak gölgeleyen fonksiyon gövdelerinin satır aralıkları.
 * Gövde sonu süslü parantez derinliğiyle bulunur — bu dosyalarda gövde içinde dengesiz
 * parantez taşıyan string/regex yok; olsaydı fazladan atlama yapar, o yüzden çağrı sayısı
 * ayrıca pinlenir.
 */
function shadowRanges(lines) {
  const DECL = /(?:function\s*[\w$]*\s*\(([^)]*)\)|\(([^)]*)\)\s*=>)/
  const ranges = []
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(DECL)
    if (!m) continue
    if (!/(^|[\s,])api([\s,=]|$)/.test(m[1] ?? m[2] ?? '')) continue
    let depth = 0, started = false, j = i
    for (; j < lines.length; j++) {
      for (const ch of lines[j]) {
        if (ch === '{') { depth++; started = true } else if (ch === '}') depth--
      }
      if (started && depth <= 0) break
    }
    ranges.push([i, j])
  }
  return ranges
}

/** Zinciri gerçek api nesnesinde yürüt; sonuç fonksiyon değilse sebebi anlat. */
function resolve(chain) {
  const parts = chain.split('.').filter(Boolean)
  let cur = api
  for (let i = 0; i < parts.length; i++) {
    if (cur == null || typeof cur !== 'object') return `api.${parts.slice(0, i).join('.')} bir nesne değil`
    if (!(parts[i] in cur)) return `api.${parts.slice(0, i + 1).join('.')} TANIMSIZ`
    cur = cur[parts[i]]
  }
  return typeof cur === 'function' ? null : `api.${parts.join('.')} fonksiyon değil (${typeof cur})`
}

describe('api çağrı yerleri gerçek istemcide çözülüyor', () => {
  const files = walk(SRC).filter(f => IMPORTS_API.test(fs.readFileSync(f, 'utf8')))

  it('taranacak dosya bulundu (boş liste = yanlış kök / bozuk import deseni)', () => {
    expect(files.length).toBeGreaterThan(20)
  })

  const scan = () => {
    const violations = []
    let checked = 0
    for (const f of files) {
      const lines = fs.readFileSync(f, 'utf8').split('\n')
      const shadows = shadowRanges(lines)
      lines.forEach((line, i) => {
        if (/^\s*(\/\/|\*)/.test(line)) return                        // yorum satırı
        if (shadows.some(([a, b]) => i >= a && i <= b)) return         // yerel `api` gölgesi
        for (const m of line.matchAll(CALL)) {
          checked++
          const problem = resolve(m[1])
          if (problem) violations.push(`${path.relative(SRC, f)}:${i + 1} → ${problem}`)
        }
      })
    }
    return { violations, checked }
  }

  it('hiçbir çağrı tanımsız uca gitmiyor', () => {
    const { violations } = scan()
    expect(violations, `Tanımsız API çağrısı:\n${violations.join('\n')}`).toEqual([])
  })

  it('kapı gerçekten çalışıyor (gölge mantığı taramayı boşaltmasın)', () => {
    expect(scan().checked).toBeGreaterThan(200)
  })
})
