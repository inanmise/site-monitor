import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR, EN } from '../i18n/index.jsx'

/**
 * Kullanılan-anahtar denetimi: parity testi yalnız TR↔EN eşliğine bakar; kodda t('...')
 * ile çağrılan bir anahtarın sözlükte HİÇ olmaması durumunu yakalamaz (useT eksik anahtarda
 * anahtarın kendisini döner → UI'da ham anahtar görünür, test yeşil kalır).
 * Bu test üretim ağacındaki tüm LİTERAL t('x.y') çağrılarını toplar ve iki sözlükte de arar.
 * Dinamik anahtarlar (t('general.lbl.' + key) gibi) statik çözülemez — kapsam dışı.
 */
const SRC = path.resolve(__dirname, '..')
const SKIP_DIRS = new Set(['test', 'assets'])

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

// t('key') / t('key', arg) — yalnız nokta içeren, anahtar görünümlü TAM literaller.
// Kapanış tırnağını ')' ya da ',' izlemeli: t('prefix.' + x) gibi birleştirmeler (dinamik
// anahtarlar) bilinçli olarak kapsam dışı — statik çözülemezler.
const CALL_RE = /\bt\(\s*(['"])([a-z][A-Za-z0-9]*\.[A-Za-z0-9._-]+)\1\s*[),]/g

describe('i18n used-keys (kodda çağrılan her literal anahtar sözlükte var)', () => {
  it('t(<literal>) çağrılarının tamamı TR ve EN sözlüklerinde mevcut', () => {
    const missing = []
    let found = 0
    for (const f of walk(SRC)) {
      const src = fs.readFileSync(f, 'utf8')
      for (const m of src.matchAll(CALL_RE)) {
        const key = m[2]
        found++
        if (!(key in TR) || !(key in EN)) {
          missing.push(`${path.relative(SRC, f)} → ${key}`)
        }
      }
    }
    expect(found).toBeGreaterThan(500) // tarama gerçekten çalışıyor (regresyon: 0 bulgu = bozuk regex)
    expect([...new Set(missing)], [...new Set(missing)].join('\n')).toEqual([])
  })
})
