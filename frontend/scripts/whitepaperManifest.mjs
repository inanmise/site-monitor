// Kılavuz PDF'lerinin tazelik manifesti — ÜRETİCİ ve TEST bu dosyayı ortak kullanır.
//
// Neden ayrı bir modül: tazelik testi üreticiyi (gen-whitepaper-pdf.mjs) import ederse
// `playwright` vitest'in modül grafiğine girer. Burada YALNIZ node:fs/crypto/path vardır,
// yani test tarayıcısız çalışır ve mevcut `frontend` CI işine bedelsiz girer.
import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

export const FRONTEND_DIR = join(here, '..')
export const ASSETS_DIR = join(FRONTEND_DIR, 'src', 'assets')
export const PUBLIC_DIR = join(FRONTEND_DIR, 'public')
export const MANIFEST_PATH = join(here, 'whitepaper.manifest.json')
export const PRINT_CSS_PATH = join(here, 'whitepaper-print.css')

/**
 * Üretici mantığı değiştiğinde elle artırılır. Test bu sabiti manifest'teki değerle
 * karşılaştırır — içerik değişmese bile PDF'lerin yeniden üretilmesini zorlar.
 */
export const MANIFEST_VERSION = 1

/** `whitepaper.md` (tr) ve `whitepaper.<dil>.md` dosyaları — diskteki gerçek liste. */
const GUIDE_FILE_RE = /^whitepaper(?:\.([a-z]{2}))?\.md$/

/** [{ lang, file, absPath, relKey }] — dil koduna göre sıralı. */
export function guideSources() {
  return readdirSync(ASSETS_DIR)
    .map((file) => {
      const m = file.match(GUIDE_FILE_RE)
      if (!m) return null
      return {
        lang: m[1] ?? 'tr',
        file,
        absPath: join(ASSETS_DIR, file),
        relKey: `src/assets/${file}`,
      }
    })
    .filter(Boolean)
    .sort((a, b) => a.lang.localeCompare(b.lang))
}

/**
 * Yerleşim de çıktının parçasıdır: print CSS değişip PDF üretilmezse kapı kırmızıya döner.
 * Üretici script'inin kendisi BİLİNÇLİ olarak hash'lenmez — bir yorum düzeltmesi 2 MB'lık
 * ikili dosyaları yeniden üretmeyi zorunlu kılmamalı.
 */
export function toolchainFiles() {
  return [{ absPath: PRINT_CSS_PATH, relKey: 'scripts/whitepaper-print.css' }]
}

/** Bir dil için beklenen PDF çıktısı. */
export function outputFor(lang) {
  return {
    lang,
    absPath: join(PUBLIC_DIR, `whitepaper.${lang}.pdf`),
    relKey: `public/whitepaper.${lang}.pdf`,
  }
}

export function expectedOutputs() {
  return guideSources().map((s) => outputFor(s.lang))
}

export function sha256(absPath) {
  return createHash('sha256').update(readFileSync(absPath)).digest('hex')
}

export function fileEntry(absPath) {
  return { sha256: sha256(absPath), bytes: statSync(absPath).size }
}

export function readManifest() {
  if (!existsSync(MANIFEST_PATH)) return null
  return JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'))
}

/** PDF'in gerçekten bir PDF olduğunu ve boş olmadığını ucuzca doğrular. */
export function inspectPdf(absPath) {
  const buf = readFileSync(absPath)
  const head = buf.subarray(0, 8).toString('latin1')
  const tail = buf.subarray(Math.max(0, buf.length - 2048)).toString('latin1')
  const pages = (buf.toString('latin1').match(/\/Type\s*\/Page[^s]/g) ?? []).length
  return { isPdf: head.startsWith('%PDF-'), hasEof: tail.includes('%%EOF'), pages, bytes: buf.length }
}

export const REGEN_HINT =
  'Kılavuz kaynağı değişti ama PDF yeniden üretilmedi.\n' +
  'Çalıştırın:  cd frontend && npm run gen:guide-pdf\n' +
  '(whitepaper.<dil>.pdf dosyaları ve scripts/whitepaper.manifest.json BİRLİKTE commit edilmelidir.)'
