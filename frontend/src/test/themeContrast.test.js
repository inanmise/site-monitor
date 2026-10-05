import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { THEMES } from '../theme/themes.js'

/**
 * TEMA KONTRAST KAPISI (2026-10-05) — WCAG 2.1, sekiz temanın HER biri.
 *
 * jsdom CSS uygulamaz; kapı jetonları KAYNAKTAN okur ve tarayıcının kaskadını taklit eder: main.jsx içe aktarma sırasıyla
 * (globals.css → App.css → themes.css) üst düzey bloklardan yalnız `:root`, `[data-scheme="light|dark"]` ve
 * `[data-theme="<id>"]` seçicilileri, belge sırasıyla uygulanır (hepsi aynı özgüllük: sonra gelen kazanır). Böylece
 * "App.css :root, globals.css koyu bloğundaki --primary'yi ezer" gibi gerçek kaskat sonuçları da görülür.
 *
 * Eşikler: metin çiftleri ≥ 4.5:1 (AA normal metin); odak halkası (--ring, yarı saydamsa zemine karıştırılarak) ≥ 3:1
 * (AA metin dışı öğe). Durum renkleri (başarı/uyarı/hata/bilgi) birbirinden ayırt edilebilir (ton farkı ≥ 30°).
 * Bir paleti değiştirirsen bu kapıyı koş; eşiği gevşetme — renk ayarla.
 */
const SRC = path.resolve(__dirname, '..')
const FILES = ['styles/globals.css', 'App.css', 'styles/themes.css']

/** Yorumları at; yalnız ÜST DÜZEY kuralları (iç içe gövdesi olmayan) {selectors, decls} olarak döndür. */
function topLevelRules(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const out = []
  let depth = 0
  let start = 0
  let selStart = 0
  let selector = ''
  let nested = false
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '{') {
      if (depth === 0) { selector = src.slice(selStart, i).trim(); start = i + 1; nested = false }
      else nested = true
      depth++
    } else if (c === '}') {
      depth--
      if (depth === 0) {
        if (!nested) out.push({ selectors: selector.split(',').map((s) => s.trim()), body: src.slice(start, i) })
        selStart = i + 1
      }
    } else if (c === ';' && depth === 0) {
      selStart = i + 1   // @import / @source / @custom-variant satırları
    }
  }
  return out
}

function matches(sel, theme, scheme) {
  if (sel === ':root') return true
  const s = /^\[data-scheme="(light|dark)"\]$/.exec(sel)
  if (s) return s[1] === scheme
  const t = /^\[data-theme="([a-z]+)"\]$/.exec(sel)
  if (t) return t[1] === theme
  return false
}

const RULES = FILES.flatMap((f) => topLevelRules(fs.readFileSync(path.join(SRC, f), 'utf8')))

/** Kaskat: belge sırasıyla, eşleşen kuralın özel değişkenleri öncekileri ezer. */
function tokensFor(theme, scheme) {
  const vars = {}
  for (const r of RULES) {
    if (!r.selectors.some((s) => matches(s, theme, scheme))) continue
    for (const m of r.body.matchAll(/(--[A-Za-z0-9_-]+)\s*:\s*([^;]+);?/g)) vars[m[1]] = m[2].trim()
  }
  return vars
}

// ── Renk çözümleme (hex, rgb/rgba, white/black/transparent, var(), color-mix(in srgb …)) ──
function splitTop(s) {
  const parts = []
  let depth = 0
  let cur = ''
  for (const c of s) {
    if (c === '(') depth++
    if (c === ')') depth--
    if (c === ',' && depth === 0) { parts.push(cur.trim()); cur = '' } else cur += c
  }
  if (cur.trim()) parts.push(cur.trim())
  return parts
}

function parseColor(raw, vars, seen = new Set()) {
  const v = raw.trim()
  const varM = /^var\(\s*(--[A-Za-z0-9_-]+)\s*(?:,\s*([\s\S]+))?\)$/.exec(v)
  if (varM) {
    const [, name, fallback] = varM
    if (vars[name] != null && !seen.has(name)) return parseColor(vars[name], vars, new Set([...seen, name]))
    if (fallback) return parseColor(fallback, vars, seen)
    throw new Error(`tanımsız değişken: ${name}`)
  }
  if (v === 'transparent') return { r: 0, g: 0, b: 0, a: 0 }
  if (v === 'white') return { r: 255, g: 255, b: 255, a: 1 }
  if (v === 'black') return { r: 0, g: 0, b: 0, a: 1 }
  const hex = /^#([0-9a-f]{3,8})$/i.exec(v)
  if (hex) {
    let h = hex[1]
    if (h.length === 3) h = h.split('').map((c) => c + c).join('')
    const n = (i) => parseInt(h.slice(i, i + 2), 16)
    return { r: n(0), g: n(2), b: n(4), a: h.length === 8 ? n(6) / 255 : 1 }
  }
  const rgb = /^rgba?\(([^)]+)\)$/.exec(v)
  if (rgb) {
    const nums = rgb[1].split(/[\s,/]+/).filter(Boolean).map(Number)
    return { r: nums[0], g: nums[1], b: nums[2], a: nums[3] ?? 1 }
  }
  const mix = /^color-mix\(\s*in\s+srgb\s*,([\s\S]+)\)$/.exec(v)
  if (mix) {
    const [p1, p2] = splitTop(mix[1])
    const part = (p) => {
      const m = /^([\s\S]+?)\s+([\d.]+)%$/.exec(p)
      return m ? { c: parseColor(m[1], vars, seen), w: Number(m[2]) / 100 } : { c: parseColor(p, vars, seen), w: null }
    }
    const a = part(p1)
    const b = part(p2)
    if (a.w == null && b.w == null) { a.w = 0.5; b.w = 0.5 } else if (a.w == null) a.w = 1 - b.w; else if (b.w == null) b.w = 1 - a.w
    const alpha = a.c.a * a.w + b.c.a * b.w
    if (alpha === 0) return { r: 0, g: 0, b: 0, a: 0 }
    const ch = (k) => (a.c[k] * a.c.a * a.w + b.c[k] * b.c.a * b.w) / alpha
    return { r: ch('r'), g: ch('g'), b: ch('b'), a: alpha }
  }
  throw new Error(`çözülemeyen renk: ${v}`)
}

const over = (fg, bg) => (fg.a >= 1 ? fg : {
  r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1,
})
const lin = (c) => { const s = c / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4 }
const lum = (c) => 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b)
function contrast(fg, bg) {
  const solidBg = over(bg, { r: 255, g: 255, b: 255, a: 1 })
  const f = over(fg, solidBg)
  const [hi, lo] = [lum(f), lum(solidBg)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}
function hue({ r, g, b }) {
  const [R, G, B] = [r / 255, g / 255, b / 255]
  const max = Math.max(R, G, B)
  const min = Math.min(R, G, B)
  const d = max - min
  if (d === 0) return 0
  let h
  if (max === R) h = ((G - B) / d) % 6
  else if (max === G) h = (B - R) / d + 2
  else h = (R - G) / d + 4
  return (h * 60 + 360) % 360
}

const TEXT_PAIRS = [
  ['--foreground', '--background'],
  ['--card-foreground', '--card'],
  ['--popover-foreground', '--popover'],
  ['--primary-foreground', '--primary'],
  ['--secondary-foreground', '--secondary'],
  ['--accent-foreground', '--accent'],
  ['--muted-foreground', '--background'],
  ['--muted-foreground', '--card'],
  ['--destructive', '--background'],
  ['--sidebar-foreground', '--sidebar'],
  ['--sidebar-accent-foreground', '--sidebar-accent'],
  // legacy gövde metni (body: color var(--text) on var(--bg)) ve kart metni
  ['--text', '--bg'],
  ['--text', '--bg-card'],
  ['--input-text', '--input-bg'],
]
const FOCUS_PAIRS = [['--ring', '--background'], ['--ring', '--card']]

/** Ek temaların bloğunda TAMAMI tanımlı olmalı: iç içe önizleme / başka şemada çizilen renk örneği tabandan değer sızdırmasın. */
const REQUIRED = [
  '--background', '--foreground', '--card', '--card-foreground', '--popover', '--popover-foreground',
  '--primary', '--primary-foreground', '--secondary', '--secondary-foreground', '--muted', '--muted-foreground',
  '--accent', '--accent-foreground', '--destructive', '--border', '--input', '--ring',
  '--chart-1', '--chart-2', '--chart-3', '--chart-4', '--chart-5',
  '--sidebar', '--sidebar-foreground', '--sidebar-primary', '--sidebar-primary-foreground', '--sidebar-accent',
  '--sidebar-accent-foreground', '--sidebar-border', '--sidebar-ring',
  '--bg', '--text', '--text-light', '--text-muted', '--shadow', '--bg-card', '--bg-surface', '--bg-hover',
  '--input-bg', '--input-text', '--ok', '--success', '--warning', '--danger', '--info',
  '--chart-grid', '--chart-label', '--chart-dot-bg',
]

describe('tema kontrast kapısı (WCAG 2.1)', () => {
  it('sekiz tema kaynakta bulunuyor; ek temaların her birinin kendi bloğu var', () => {
    expect(THEMES.map((t) => t.id)).toEqual(['light', 'dark', 'blueprint', 'parchment', 'alloy', 'obsidian', 'slag', 'crucible'])
    for (const t of THEMES.filter((x) => !x.base)) {
      const own = RULES.filter((r) => r.selectors.includes(`[data-theme="${t.id}"]`))
      expect(own.length, `${t.id}: themes.css'te [data-theme="${t.id}"] bloğu yok`).toBe(1)
      const declared = new Set([...own[0].body.matchAll(/(--[A-Za-z0-9_-]+)\s*:/g)].map((m) => m[1]))
      expect(REQUIRED.filter((k) => !declared.has(k)), `${t.id}: eksik jetonlar`).toEqual([])
    }
  })

  for (const t of THEMES) {
    describe(`${t.id} (${t.scheme})`, () => {
      const vars = tokensFor(t.id, t.scheme)
      const color = (k) => parseColor(`var(${k})`, vars)

      it('metin çiftleri ≥ 4.5:1', () => {
        const bad = []
        for (const [fg, bg] of TEXT_PAIRS) {
          const ratio = contrast(color(fg), color(bg))
          if (ratio < 4.5) bad.push(`${fg} / ${bg} = ${ratio.toFixed(2)}`)
        }
        expect(bad).toEqual([])
      })

      it('odak halkası (--ring) zemin ve kart üstünde ≥ 3:1', () => {
        const bad = []
        for (const [fg, bg] of FOCUS_PAIRS) {
          const ratio = contrast(color(fg), color(bg))   // yarı saydam halka zemine karıştırılarak ölçülür
          if (ratio < 3) bad.push(`${fg} / ${bg} = ${ratio.toFixed(2)}`)
        }
        expect(bad).toEqual([])
      })

      it('durum renkleri (başarı / uyarı / hata / bilgi) birbirinden ayırt edilebilir (ton farkı ≥ 30°)', () => {
        const keys = ['--success', '--warning', '--danger', '--info']
        const hues = keys.map((k) => hue(color(k)))
        const close = []
        for (let i = 0; i < keys.length; i++) {
          for (let j = i + 1; j < keys.length; j++) {
            const d = Math.abs(hues[i] - hues[j])
            if (Math.min(d, 360 - d) < 30) close.push(`${keys[i]} ~ ${keys[j]}`)
          }
        }
        expect(close).toEqual([])
      })

      it('şema doğru: koyu temada zemin koyu, açık temada açık', () => {
        const L = lum(color('--background'))
        if (t.scheme === 'dark') expect(L).toBeLessThan(0.1)
        else expect(L).toBeGreaterThan(0.6)
      })
    })
  }

  it('kapı gerçekten okuyor: Koyu temanın --primary değeri App.css kaskadından (#2563eb) gelir', () => {
    const v = tokensFor('dark', 'dark')
    expect(parseColor('var(--primary)', v)).toMatchObject({ r: 0x25, g: 0x63, b: 0xeb })
    expect(parseColor('var(--background)', v)).toMatchObject({ r: 9, g: 9, b: 11 })
  })
})
