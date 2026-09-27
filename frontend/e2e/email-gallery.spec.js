// E-posta galerisi — ekran görüntüleri + yatay taşma kapısı (e-posta yeniden tasarımı 2026-09-26).
//
// Backend EmailGalleryTest her e-posta türünü <dir>/<slug>.html olarak yazar (manifest.txt = slug listesi):
//   mvn test -Dtest=EmailGalleryTest -Demail.gallery.dir=<dir>
// Bu test yalnız EMAIL_GALLERY_DIR verilince koşar (CI'da atlanır):
//   EMAIL_GALLERY_DIR=<dir> npx playwright test e2e/email-gallery.spec.js
// Her mail 390×844 (telefon) ve 640×900 (masaüstü önizleme) genişlikte açılır; tam sayfa ekran görüntüsü
// EMAIL_SHOTS_DIR'e yazılır ve sayfa YATAY TAŞMAMALI (scrollWidth <= innerWidth) — jsdom bunu göremez.
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const DIR = process.env.EMAIL_GALLERY_DIR
const OUT = process.env.EMAIL_SHOTS_DIR || 'D:/site-monitor-shadcn/.migration/emails'
const SIZES = [
  { w: 390, h: 844, tag: 'phone' },
  { w: 640, h: 900, tag: 'desk' },
]

function readSlugs() {
  if (!DIR) return []
  const manifest = path.join(DIR, 'manifest.txt')
  if (!fs.existsSync(manifest)) return []
  return fs.readFileSync(manifest, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
}

const slugs = readSlugs()

test.describe('e-posta galerisi', () => {
  if (slugs.length === 0) {
    test('galeri yok — atlandı', () => {
      test.skip(true, 'EMAIL_GALLERY_DIR verilmedi ya da manifest.txt yok')
    })
    return
  }
  for (const slug of slugs) {
    for (const s of SIZES) {
      test(`${slug} ${s.tag}`, async ({ page }) => {
        await page.setViewportSize({ width: s.w, height: s.h })
        await page.goto(pathToFileURL(path.join(DIR, `${slug}.html`)).href)
        const m = await page.evaluate(() => ({
          sw: document.documentElement.scrollWidth,
          iw: window.innerWidth,
        }))
        fs.mkdirSync(OUT, { recursive: true })
        await page.screenshot({ path: `${OUT}/${slug}-${s.tag}.png`, fullPage: true })
        expect(m.sw, `${slug} @${s.w}px yatay taşma (scrollWidth ${m.sw} > ${m.iw})`).toBeLessThanOrEqual(m.iw)
      })
    }
  }
})
