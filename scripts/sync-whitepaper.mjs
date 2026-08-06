#!/usr/bin/env node
// Kök WHITEPAPER.md, tek doğruluk kaynağı frontend/src/assets/whitepaper.md'den ÜRETİLİR
// (repo vitrini kopyası). Elle düzenleme sapma yaratır — whitepaper-sync.test.jsx eşitliği kilitler.
// Kullanım: node scripts/sync-whitepaper.mjs   (repo kökünden)
import { readFileSync, writeFileSync } from 'node:fs'

export const HEADER = `<img src="branding/assets/logo-ok-512.png" width="200" alt="Site Monitor logosu">

<!-- ÜRETİLEN KOPYA — elle düzenlemeyin. Kaynak: frontend/src/assets/whitepaper.md ; senkron: node scripts/sync-whitepaper.mjs -->

`

const src = readFileSync('frontend/src/assets/whitepaper.md', 'utf8')
writeFileSync('WHITEPAPER.md', HEADER + src)
console.log('WHITEPAPER.md kaynaktan uretildi (' + src.length + ' bayt + vitrin header)')
