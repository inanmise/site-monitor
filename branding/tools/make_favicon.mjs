#!/usr/bin/env node
/**
 * SiteMonitor favicon + PWA icon set generator (BRAND.md §7).
 *
 * Source of truth: branding/assets/favicon.svg — the turnip brand mark redrawn for small sizes (hand-authored).
 * This script RASTERISES that master with the Chromium that the frontend's Playwright install already ships
 * (no Python imaging stack needed), so every PNG/ICO frame is an exact render of the SVG:
 *
 *   favicon.ico            16 + 32 + 48 (PNG-compressed frames; 16 px uses the SVG's own small-size rules)
 *   favicon-16.png, favicon-32.png
 *   apple-touch-icon.png   180, opaque white background (iOS ignores transparency)
 *   icon-192.png, icon-512.png            PWA "any" icons, transparent
 *   icon-maskable-512.png                 PWA "maskable": full-bleed white, mark inside the 80 % safe zone
 *   site.webmanifest       name/short_name/colours/icons (served as application/manifest+json)
 *   favicon-sheet.png      visual check: light + dark backgrounds, 8x pixel zoom of 16/32 px (not served)
 *
 * Usage (repo root):  node branding/tools/make_favicon.mjs
 * Deterministic for a given Chromium build; re-run after editing favicon.svg. Served copies go to frontend/public/.
 */
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const ASSETS = path.join(ROOT, 'branding', 'assets')
const PUBLIC = path.join(ROOT, 'frontend', 'public')
const require = createRequire(path.join(ROOT, 'frontend', 'package.json'))
const { chromium } = require('playwright')

/** Brand palette (BRAND.md §2) — manifest/theme colours. */
const BRAND_PURPLE = '#813387'
const WHITE = '#FFFFFF'

/** Files copied to frontend/public (served at the site root). The sheet stays in branding/ only. */
const SERVED = [
  'favicon.svg', 'favicon.ico', 'favicon-16.png', 'favicon-32.png', 'apple-touch-icon.png',
  'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'site.webmanifest',
]

const svg = fs.readFileSync(path.join(ASSETS, 'favicon.svg'), 'utf8')
const svgUri = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`
const pngUri = (buf) => `data:image/png;base64,${buf.toString('base64')}`

/**
 * One square tile: optional background colour, the mark drawn at `mark` px centred in `size`.
 * The SVG is drawn at its OWN pixel size (not scaled after rasterising) so its small-size media rule applies.
 */
function tileHtml({ size, mark = size, bg = 'transparent', src = svgUri }) {
  return `<!doctype html><html><head><style>
    html,body{margin:0;padding:0;background:transparent}
    #t{width:${size}px;height:${size}px;background:${bg};display:flex;align-items:center;justify-content:center}
    #t img{width:${mark}px;height:${mark}px;display:block}
  </style></head><body><div id="t"><img src="${src}" alt=""></div></body></html>`
}

async function render(page, opts) {
  const bg = opts.bg ?? 'transparent'
  await page.setViewportSize({ width: opts.size, height: opts.size })
  await page.setContent(tileHtml({ ...opts, bg }))
  await page.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0))
  // Transparent tiles keep their alpha channel (RGBA PNG); opaque tiles (apple/maskable) are RGB on the given colour.
  return page.locator('#t').screenshot({ omitBackground: bg === 'transparent', type: 'png' })
}

/** ICO container with PNG-compressed frames (supported by every current browser and Windows Vista+). */
function ico(frames) {
  const head = Buffer.alloc(6)
  head.writeUInt16LE(0, 0)
  head.writeUInt16LE(1, 2)
  head.writeUInt16LE(frames.length, 4)
  const dir = Buffer.alloc(16 * frames.length)
  let offset = head.length + dir.length
  frames.forEach(({ size, buf }, i) => {
    const o = i * 16
    dir.writeUInt8(size >= 256 ? 0 : size, o)
    dir.writeUInt8(size >= 256 ? 0 : size, o + 1)
    dir.writeUInt8(0, o + 2)        // palette
    dir.writeUInt8(0, o + 3)        // reserved
    dir.writeUInt16LE(1, o + 4)     // colour planes
    dir.writeUInt16LE(32, o + 6)    // bits per pixel
    dir.writeUInt32LE(buf.length, o + 8)
    dir.writeUInt32LE(offset, o + 12)
    offset += buf.length
  })
  return Buffer.concat([head, dir, ...frames.map((f) => f.buf)])
}

function manifest() {
  return `${JSON.stringify({
    id: '/',
    name: 'SiteMonitor',
    short_name: 'SiteMonitor',
    description: 'SSL/TLS certificate, domain and service monitoring with team-based alerting.',
    lang: 'en',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    background_color: WHITE,
    theme_color: BRAND_PURPLE,
    icons: [
      { src: '/favicon.svg', sizes: 'any', type: 'image/svg+xml' },
      { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
      { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
      { src: '/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  }, null, 2)}\n`
}

function sheetHtml(png, darkSvg) {
  const cell = (label, inner, bg) =>
    `<figure style="margin:0;display:flex;flex-direction:column;align-items:center;gap:6px">
       <div style="background:${bg};padding:10px;border-radius:8px;display:flex;align-items:center;justify-content:center;min-width:40px;min-height:40px">${inner}</div>
       <figcaption style="font:12px system-ui;color:#555">${label}</figcaption></figure>`
  const img = (uri, w, extra = '') => `<img src="${uri}" width="${w}" height="${w}" style="display:block;${extra}">`
  const row = (title, bg, items) =>
    `<section style="margin:0 0 18px"><h2 style="font:600 14px system-ui;margin:0 0 8px">${title}</h2>
       <div style="display:flex;gap:16px;align-items:flex-end;flex-wrap:wrap">${items.map(([l, h]) => cell(l, h, bg)).join('')}</div></section>`
  const base = [
    ['16', img(pngUri(png.f16), 16)], ['32', img(pngUri(png.f32), 32)], ['48', img(pngUri(png.f48), 48)],
    ['apple 180', img(pngUri(png.apple), 90)], ['maskable 512', img(pngUri(png.mask512), 90, 'border-radius:50%')],
    ['icon 192', img(pngUri(png.i192), 96)],
  ]
  return `<!doctype html><html><body style="margin:0;padding:20px;background:#fff;width:760px">
    ${row('Light background (PNG set)', '#ffffff', base)}
    ${row('Dark background (PNG set)', '#202124', base)}
    ${row('Dark browser theme (SVG, prefers-color-scheme: dark)', '#202124',
      [['svg 16', img(pngUri(darkSvg.s16), 16)], ['svg 32', img(pngUri(darkSvg.s32), 32)], ['svg 64', img(pngUri(darkSvg.s64), 64)]])}
    ${row('8x pixel zoom (16 / 32 px frames)', '#f4f4f5',
      [['16 px', img(pngUri(png.f16), 128, 'image-rendering:pixelated')], ['32 px', img(pngUri(png.f32), 128, 'image-rendering:pixelated')]])}
  </body></html>`
}

async function main() {
  const browser = await chromium.launch()
  try {
    const light = await browser.newContext({ colorScheme: 'light', deviceScaleFactor: 1 })
    const page = await light.newPage()
    const png = {
      f16: await render(page, { size: 16 }),
      f32: await render(page, { size: 32 }),
      f48: await render(page, { size: 48 }),
      apple: await render(page, { size: 180, mark: 148, bg: WHITE }),
      i192: await render(page, { size: 192, mark: 180 }),
      i512: await render(page, { size: 512, mark: 480 }),
      // maskable: Android may crop to a circle of 80 % diameter — the mark (72 %, leaves included) stays inside it
      mask512: await render(page, { size: 512, mark: 368, bg: WHITE }),
    }
    const dark = await browser.newContext({ colorScheme: 'dark', deviceScaleFactor: 1 })
    const dpage = await dark.newPage()
    const darkSvg = {
      s16: await render(dpage, { size: 16 }),
      s32: await render(dpage, { size: 32 }),
      s64: await render(dpage, { size: 64 }),
    }

    const out = {
      'favicon.ico': ico([{ size: 16, buf: png.f16 }, { size: 32, buf: png.f32 }, { size: 48, buf: png.f48 }]),
      'favicon-16.png': png.f16,
      'favicon-32.png': png.f32,
      'apple-touch-icon.png': png.apple,
      'icon-192.png': png.i192,
      'icon-512.png': png.i512,
      'icon-maskable-512.png': png.mask512,
      'site.webmanifest': Buffer.from(manifest(), 'utf8'),
    }
    for (const [name, buf] of Object.entries(out)) fs.writeFileSync(path.join(ASSETS, name), buf)

    const sheet = await light.newPage()
    await sheet.setViewportSize({ width: 800, height: 600 })
    await sheet.setContent(sheetHtml(png, darkSvg))
    await sheet.waitForFunction(() => [...document.images].every((i) => i.complete && i.naturalWidth > 0))
    fs.writeFileSync(path.join(ASSETS, 'favicon-sheet.png'), await sheet.screenshot({ fullPage: true, type: 'png' }))

    fs.mkdirSync(PUBLIC, { recursive: true })
    for (const name of SERVED) fs.copyFileSync(path.join(ASSETS, name), path.join(PUBLIC, name))
    for (const name of [...Object.keys(out), 'favicon-sheet.png']) {
      console.log(`${name.padEnd(24)} ${String(fs.statSync(path.join(ASSETS, name)).size).padStart(7)} B`)
    }
    console.log(`copied ${SERVED.length} served files to frontend/public/`)
  } finally {
    await browser.close()
  }
}

main().catch((e) => { console.error(e); process.exit(1) })
