import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * KAPI (2026-11-26, H paketi, sertifika B3): sözlüklerde değiştirme karakteri (U+FFFD) olmaz.
 * `inv.formFP` Türkçe metni bir düzenleme betiğinin bayt bozması yüzünden "tespiti i" + iki U+FFFD + "n" olarak yayına
 * çıkmıştı; ne parite ne kullanılan-anahtar kapısı görür (anahtar var, değer boş değil). Hem dosyanın HAM metni hem içe
 * aktarılan değerler taranır — biri satır içi kaçışla (ters bölü + uFFFD) yazılırsa da yakalanır. Bu dosyanın kendisi
 * karakteri yalnız kaçışla taşır (kaynakta U+FFFD baytı yok).
 */
const DIR = path.resolve(__dirname, '../i18n')
const REPLACEMENT = '\uFFFD'

function offenders(dict) {
  return Object.entries(dict).filter(([, v]) => typeof v === 'string' && v.includes(REPLACEMENT)).map(([k]) => k)
}

describe('sözlüklerde değiştirme karakteri (U+FFFD) yok', () => {
  for (const file of ['tr.js', 'en.js']) {
    it(`${file} ham metni temiz`, () => {
      const src = fs.readFileSync(path.join(DIR, file), 'utf8')
      const lines = src.split('\n')
        .map((l, i) => (l.includes(REPLACEMENT) ? `${file}:${i + 1}: ${l.trim().slice(0, 120)}` : null))
        .filter(Boolean)
      expect(lines).toEqual([])
    })
  }

  it('TR değerleri temiz', () => expect(offenders(TR)).toEqual([]))
  it('EN değerleri temiz', () => expect(offenders(EN)).toEqual([]))

  it('düzeltilen anahtar doğru metni taşır', () => {
    expect(TR['inv.formFP']).toContain('tespiti için')
  })
})
