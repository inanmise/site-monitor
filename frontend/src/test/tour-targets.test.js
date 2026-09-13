import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { MAIN_STEPS, PAGE_TOURS } from '../components/tour/tourSteps.js'
import { TR, EN } from '../i18n/index.jsx'

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * HAYALET HEDEF KAPISI (2026-09-13) — cssClasses kapısının tur karşılığı.
 * Tur adımı `data-tour="x"` hedefi arar; hedef bir refaktörde silinirse adım sessizce atlanır ve tur
 * "eksik" görünür (kırılma değil, sessiz erime). Her hedef bir JSX'te düz ya da şablon literal olarak
 * geçmeli; her adımın TR/EN başlık+gövde anahtarı olmalı.
 */
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'test') walk(path.join(dir, e.name), out) }
    else if (/\.jsx$/.test(e.name)) out.push(path.join(dir, e.name))
  }
  return out
}
const sources = walk(SRC).map((f) => fs.readFileSync(f, 'utf8')).join('\n')

function hasTarget(id) {
  if (sources.includes(`data-tour="${id}"`)) return true
  // Şablon: data-tour={`nav-tab-${id}`} → nav-tab-<sekme>; tourId={... 'first-card' ...} gibi prop yoluyla verilenler
  if (/^nav-tab-/.test(id) && sources.includes('data-tour={`nav-tab-${id}`}')) return true
  if (sources.includes(`'${id}'`) && sources.includes('data-tour={tourId}')) return true
  return false
}

describe('tour-targets kapısı', () => {
  const steps = [...MAIN_STEPS, ...Object.values(PAGE_TOURS).flat()]

  it('kapsam boş değil', () => { expect(steps.length).toBeGreaterThan(15) })

  it('her adımın data-tour hedefi bir JSX\'te tanımlı (merkez adımlar hariç)', () => {
    const missing = steps.filter((s) => !s.center && !hasTarget(s.target)).map((s) => `${s.id} → ${s.target}`)
    expect(missing).toEqual([])
  })

  it('her adımın TR/EN başlık + gövde metni var; adım kimlikleri tekil', () => {
    const missing = []
    for (const s of steps) for (const k of [`tour.s.${s.id}.title`, `tour.s.${s.id}.body`]) {
      if (!TR[k]) missing.push('tr:' + k)
      if (!EN[k]) missing.push('en:' + k)
    }
    expect(missing).toEqual([])
    const ids = steps.map((s) => s.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('sekme adımları geçerli sekme kimliklerine işaret eder (VALID_TABS sözleşmesi)', () => {
    const app = fs.readFileSync(path.join(SRC, 'App.jsx'), 'utf8')
    for (const s of MAIN_STEPS) {
      if (s.tab) expect(app, `tab ${s.tab}`).toMatch(new RegExp(`'${s.tab}'`))
      if (s.before?.reveal) expect(sources, `reveal ${s.before.reveal}`).toMatch(new RegExp(`id: '${s.before.reveal}'`))
    }
  })
})
