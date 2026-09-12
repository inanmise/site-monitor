import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * KAYNAK TARAYAN KAPI — anahtar (checkbox label) + yardım ipucu AYNI SATIRDA.
 *
 * <p>Neden var: `</label><HelpTip …/>` deseni 17 yerde; label `display:flex` olunca satır boyu
 * kaplıyor ve ipucu bir alt satıra düşüyor (Haftalık e-posta "etkin", 2026-09-12). jsdom yerleşim
 * yapmaz — ölçülebilen şey, ipucu taşıyan her label sınıfının CSS'te `inline-flex` beyan etmesidir.
 */
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const css = readFileSync(path.join(ROOT, 'App.css'), 'utf8')
const files = []
;(function walk(d) { for (const f of readdirSync(d, { withFileTypes: true })) { const p = path.join(d, f.name); if (f.isDirectory()) walk(p); else if (/\.jsx$/.test(f.name)) files.push(p) } })(path.join(ROOT, 'components'))

function inlineFlexDeclared(cls) {
  // `.cls { … display: inline-flex …}` ya da `.a.cls { display: inline-flex }` — ilk sınıf bloğu
  const re = new RegExp(String.raw`\.${cls.replace(/\./g, '\.')}\s*\{[^}]*display:\s*inline-flex`, 'm')
  return re.test(css)
}

describe('yardım ipucu taşıyan anahtar etiketleri', () => {
  it('</label><HelpTip deseni olan her label sınıfı CSS\'te inline-flex (ipucu alt satıra düşmez)', () => {
    const offenders = []
    let seen = 0
    for (const f of files) {
      const src = readFileSync(f, 'utf8')
      // Aradaki içerik başka bir <label açmamalı (aksi hâlde önceki label'ın sınıfı yanlış eşlenir)
      const re = /<label className="([^"]+)"(?:(?!<label)[\s\S])*?<\/label>\s*<HelpTip/g
      let m
      while ((m = re.exec(src))) {
        seen++
        const classes = m[1].split(/\s+/).filter(Boolean)
        // sınıflardan biri (tek başına ya da birleşik seçiciyle) inline-flex beyan etmeli
        const ok = classes.some((c) => inlineFlexDeclared(c)) || inlineFlexDeclared(classes.join('.'))
        if (!ok) offenders.push(`${path.relative(ROOT, f)} → <label className="${m[1]}">`)
      }
    }
    expect(seen).toBeGreaterThan(10)   // kapı vakum değil
    expect(offenders).toEqual([])
  })
})
