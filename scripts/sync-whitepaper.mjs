#!/usr/bin/env node
// Kök WHITEPAPER.md / WHITEPAPER.en.md, tek doğruluk kaynağı frontend/src/assets/whitepaper*.md
// dosyalarından ÜRETİLİR (repo vitrini kopyası). Elle düzenleme sapma yaratır —
// whitepaper-sync.test.jsx eşitliği kilitler.
//
// Kullanım: node scripts/sync-whitepaper.mjs      (herhangi bir dizinden)
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

const header = (srcRel) => `<img src="branding/assets/logo-ok-512.png" width="200" alt="Site Monitor logo">

<!-- ÜRETİLEN KOPYA — elle düzenlemeyin. Kaynak: ${srcRel} ; senkron: node scripts/sync-whitepaper.mjs -->

`

/** Üretilen kopyaların tek listesi — test de bunu tüketir, yeniden yazmaz. */
export const MIRRORS = [
  { lang: 'tr', src: 'frontend/src/assets/whitepaper.md', out: 'WHITEPAPER.md' },
  { lang: 'en', src: 'frontend/src/assets/whitepaper.en.md', out: 'WHITEPAPER.en.md' },
]

export const HEADER_FOR = (m) => header(m.src)

function sync() {
  for (const m of MIRRORS) {
    const src = readFileSync(join(ROOT, m.src), 'utf8')
    writeFileSync(join(ROOT, m.out), HEADER_FOR(m) + src)
    console.log(`${m.out} kaynaktan uretildi (${src.length} bayt + vitrin header)`)
  }
}

// Import edildiğinde yan etki üretmesin (test MIRRORS listesini okumak için import eder).
if (import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href
    || process.argv[1]?.endsWith('sync-whitepaper.mjs')) {
  sync()
}
