import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Tailwind v4 paletinin sRGB DIŞI renkleri globals.css'te sRGB hex ile ezilmiş olmalı (2026-09-27).
 *
 * Neden: Chromium/Edge, gam dışı bir oklch rengini yarı saydam kenarlık + yarı saydam zeminle aynı yuvarlak köşede
 * karıştırırken köşe kenar yumuşatmasında ters renkli pikseller çizer (turuncu → turkuaz, mavi/mor → sarı). jsdom
 * renk çizmediği için hiçbir bileşen testi bunu yakalamaz; bu kapı kaynağı metin düzeyinde pinler. Tailwind
 * güncellenip yeni bir gam dışı renk gelirse burası kırmızıya döner: listeyi globals.css'teki `@theme` bloğuna ekle
 * (değer = kırpılmış sRGB hex; aşağıdaki `clippedHex` onu üretir).
 */
const root = path.join(__dirname, '..', '..')
const theme = fs.readFileSync(path.join(root, 'node_modules', 'tailwindcss', 'theme.css'), 'utf8')
const globals = fs.readFileSync(path.join(root, 'src', 'styles', 'globals.css'), 'utf8')

function oklchToLinearSrgb(L, C, H) {
  const h = (H * Math.PI) / 180
  const a = C * Math.cos(h)
  const b = C * Math.sin(h)
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ]
}

function clippedHex(linear) {
  return '#' + linear.map((x) => {
    const c = Math.min(1, Math.max(0, x))
    const e = c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055
    return Math.round(e * 255).toString(16).padStart(2, '0')
  }).join('')
}

/** Tailwind'in varsayılan paleti: { 'orange-500': [L, C, H] } */
const palette = [...theme.matchAll(/--color-([a-z]+-\d+):\s*oklch\(([\d.]+)%\s+([\d.]+)\s+([\d.]+)\)/g)]
  .map((m) => [m[1], [Number(m[2]) / 100, Number(m[3]), Number(m[4])]])

const outOfGamut = palette.filter(([, lch]) => oklchToLinearSrgb(...lch).some((x) => x < -0.0005 || x > 1.0005))

/** globals.css'teki `@theme { … }` (inline OLMAYAN) blok(lar)ındaki `--color-x-N: #hex` ezmeleri. */
function overrides() {
  const map = new Map()
  for (const block of globals.matchAll(/@theme\s*\{([^}]*)\}/g)) {
    for (const m of block[1].matchAll(/--color-([a-z]+-\d+):\s*(#[0-9a-f]{6})\s*;/gi)) map.set(m[1], m[2].toLowerCase())
  }
  return map
}

describe('Tailwind paleti — sRGB dışı renkler globals.css\'te ezilmiş', () => {
  it('palet okunuyor ve gam dışı renkler bulunuyor (kapı boşa dönmüyor)', () => {
    expect(palette.length).toBeGreaterThan(200)
    expect(outOfGamut.map(([n]) => n)).toContain('orange-500')
  })

  it('her gam dışı renk kırpılmış sRGB hex ile ezilmiş', () => {
    const o = overrides()
    const missing = outOfGamut.filter(([n]) => !o.has(n)).map(([n]) => n)
    expect(missing, `globals.css @theme bloğuna ekle: ${missing.join(', ')}`).toEqual([])
    const drift = outOfGamut
      .filter(([n, lch]) => o.get(n) !== clippedHex(oklchToLinearSrgb(...lch)))
      .map(([n, lch]) => `${n}: ${o.get(n)} ≠ ${clippedHex(oklchToLinearSrgb(...lch))}`)
    expect(drift).toEqual([])
  })

  it('ezme yalnız hex (oklch/var ile yazılırsa sorun geri gelir)', () => {
    for (const block of globals.matchAll(/@theme\s*\{([^}]*)\}/g)) {
      for (const m of block[1].matchAll(/--color-([a-z]+-\d+):\s*([^;]+);/g)) expect(m[2].trim(), m[1]).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })
})
