// Marka logolarını mail önizlemesi için base64 sabitlerine çevirir.
// Çalıştır: npm run gen:mail-logos   (kaynak: public/brand/logo-*-32.png)
//
// Neden sabit base64: mail önizleme iframe'i sandbox'ta allow-same-origin taşımaz → opak origin'de
// çalışır ve devraldığı CSP "img-src 'self' data:" içinde 'self' opak origin'de eşleşmez, yani
// /brand/... URL'i yüklenemez. Vite'ın `?inline` sorgusu ise vitest'in SSR dönüşümünde data URI
// yerine yol döndürüyor (dev/test/prod ayrışması). Sabit base64 üç ortamda da aynı sonucu verir.
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const publicBrand = join(here, '..', 'public', 'brand')
const target = join(here, '..', 'src', 'assets', 'brand', 'mailLogos.js')

const VARIANTS = ['ok', 'warning', 'critical']

const header = `// ÜRETİLMİŞ İÇERİK — elle düzenlemeyin.
// Kaynak: frontend/public/brand/logo-{${VARIANTS.join(',')}}-32.png
// Marka otoritesi: branding/ + BRAND.md §5.1. Yeniden üretmek için: npm run gen:mail-logos
//
// Bu sabitler mail önizlemesindeki cid:brand-logo referansını değiştirir (utils/mailPreview.js).
// mail-preview-guard testi, sabitlerin PNG'lerle bayt-eş olduğunu her koşuda doğrular.
`

const body = VARIANTS.map((v) => {
  const b64 = readFileSync(join(publicBrand, `logo-${v}-32.png`)).toString('base64')
  return `export const LOGO_${v.toUpperCase()} = 'data:image/png;base64,${b64}'`
}).join('\n')

writeFileSync(target, `${header}\n${body}\n`, 'utf8')
console.log(`mailLogos.js yazıldı (${VARIANTS.length} varyant) → ${target}`)
