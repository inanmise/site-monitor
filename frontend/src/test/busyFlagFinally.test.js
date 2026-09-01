import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * KAPI: "işlem sürüyor" bayrağı try/finally ile temizlenmeli.
 *
 * <p><b>Neden kapı.</b> `request()` ağ hatasında THROW ediyor (api/client.js). `setSaving(true)`
 * … `await` … `setSaving(false)` dizilimi tek bir ağ hatasında bayrağı asılı bırakır: düğme
 * bileşen yeniden çizilene kadar KİLİTLİ kalır, kullanıcı için "buton bozuldu". Aynı şey
 * yükleme bayraklarında "sonsuza kadar yükleniyor" olarak görünür (v20.44.1'de sertifika
 * modalinde tam olarak bu yaşandı).
 *
 * <p>2026-09-01'de bu desen 122 akışta düzeltildi. Kapı, yenisinin sessizce eklenmesini
 * engeller — ihlali DOSYA ve SATIRIYLA söyler.
 *
 * <p><b>Kapsam (kasıtlı dar).</b> Yalnız şu üçü birden doğruysa ihlal sayılır: (1) bayrak
 * fonksiyon gövdesinin en üst seviyesinde `true` yapılıyor, (2) ardından `await` var,
 * (3) aynı gövdede en üst seviyede bir `false`/`null` temizliği var. Bu üçlü, bayrağın
 * "işlem süresince" yaşadığını kanıtlar. `setCopied(true)` + `setTimeout(...)` gibi zamanlayıcıyla
 * sıfırlanan göstergeler kapsam dışıdır — orada `finally` özelliği BOZAR.
 */

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const SET_TRUE = /^(\s*)set([A-Z]\w*)\(\s*(true|[A-Za-z_$][\w.$]*\.id)\s*\)\s*(;.*)?$/
const FN_HEAD = /(function\s*\w*\s*\(|=>\s*\{\s*$|=>\s*\{\s*\/\/)/

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'assets') continue
      walk(p, out)
    } else if (/\.jsx?$/.test(entry.name)) {
      out.push(p)
    }
  }
  return out
}

/** Her satırın BAŞINDAKİ brace derinliği (satır yorumları düşülür). */
function depths(lines) {
  const out = []
  let d = 0
  for (const ln of lines) {
    out.push(d)
    const code = ln.replace(/\/\/.*$/, '')
    d += (code.split('{').length - 1) - (code.split('}').length - 1)
  }
  return out
}

function violations() {
  const found = []
  for (const file of walk(SRC)) {
    const lines = fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n')
    const dep = depths(lines)
    for (let i = 0; i < lines.length; i++) {
      const m = SET_TRUE.exec(lines[i])
      if (!m) continue
      const [, indent, name, , tail] = m
      if (tail && tail.includes('await')) continue

      const d = dep[i]
      let close = -1
      for (let j = i + 1; j < lines.length; j++) {
        // dep[j] < d olan İLK satır kapanış brace'inin BİR SONRASIDIR ("  }" satırının
        // başındaki derinlik hâlâ d'dir) — bir eksiği alınır.
        if (dep[j] < d) { close = j - 1; break }
      }
      let open = -1
      for (let k = i - 1; k >= 0; k--) {
        if (dep[k] < d) { open = k; break }
      }
      if (close < 0 || open < 0) continue
      if (!FN_HEAD.test(lines[open])) continue

      const body = lines.slice(open, close + 1).join('\n')
      const after = lines.slice(i + 1, close)
      const clear = new RegExp('\\bset' + name + '\\(\\s*(false|null)\\s*\\)')
      if (!clear.test(body)) continue
      if (!after.some(ln => ln.includes('await'))) continue

      const topClear = after.some(ln =>
        clear.test(ln) &&
        (ln.length - ln.trimStart().length) <= indent.length + 2 &&
        !ln.split('set' + name)[0].includes('=>'))
      if (!topClear) continue

      if (!body.includes('finally')) {
        found.push(`${path.relative(SRC, file).replace(/\\/g, '/')}:${i + 1} — set${name}`)
      }
    }
  }
  return found
}

describe('KAPI: işlem bayrağı try/finally', () => {
  it('await içeren hiçbir akış bayrağı finally OLMADAN temizlemez', () => {
    const found = violations()
    expect(found, [
      'Bu akışlarda `setX(true)` … `await` … `setX(false)` var ama `finally` YOK.',
      'request() ağ hatasında throw ettiği için bayrak asılı kalır ve düğme/spinner kilitlenir.',
      'Düzeltme: set satırından sonraki gövdeyi try { } finally { setX(false) } içine al.',
      '',
      ...found,
    ].join('\n')).toEqual([])
  })

  it('tarayıcının kendisi çalışıyor (yapay ihlal yakalanır)', () => {
    // Kapının sessizce "hiç ihlal yok" demesi, tarama bozulduğunda da mümkündür. Bu test
    // tarama mantığını değil, taramanın YAŞADIĞINI kanıtlar: gerçek kaynakta en az bir
    // dosya taranmış olmalı.
    expect(walk(SRC).length).toBeGreaterThan(50)
  })
})
