#!/usr/bin/env node
// CHANGELOG terfi (K6, Keep-a-Changelog): `## [Unreleased]` altında GERÇEK içerik (bullet) varsa
// onu `## [X.Y.Z] — YYYY-MM-DD` başlığı altına taşır; yoksa DOKUNMAZ (600 boş başlık üretilmez).
// Kürasyon insan işi kalır, tarihleme makine işi olur. Alt bağlantı bloğunu da doğru repoya yazar.
//
//   node scripts/promote-changelog.mjs --version 20.54.0 [--date 2026-09-11] [--file CHANGELOG.md]
//
// Kenar durumlar: yalnız "### Added" gibi boş alt başlıklar = boş; aynı sürüm başlığı zaten varsa
// no-op; CRLF korunur; `## [Unreleased]` yoksa oluşturulur.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const REPO_URL = 'https://github.com/inanmise/site-monitor'
const UNRELEASED_RE = /^## \[Unreleased\]\s*$/m
const VERSION_HEADING_RE = /^## \[(\d+\.\d+\.\d+)\]/m

/** Unreleased bloğunun satırları (başlık hariç, bir sonraki `## ` başlığına kadar). */
export function unreleasedBlock(lines) {
  const start = lines.findIndex((l) => /^## \[Unreleased\]/.test(l))
  if (start < 0) return { start: -1, end: -1, body: [] }
  let end = lines.length
  for (let i = start + 1; i < lines.length; i++) {
    if (/^## /.test(lines[i])) { end = i; break }
  }
  return { start, end, body: lines.slice(start + 1, end) }
}

/** İçerik var mı: bullet, tablo satırı ya da düz paragraf (boş satır ve `###` alt başlıkları sayılmaz). */
export function hasContent(body) {
  return body.some((l) => l.trim() && !/^#{3,}\s/.test(l) && !/^-{3,}\s*$/.test(l))
}

export function promote(text, version, date) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n'
  const lines = text.split(/\r?\n/)
  if (new RegExp('^## \\[' + version.replace(/\./g, '\\.') + '\\]', 'm').test(text)) {
    return { text, changed: false, reason: `## [${version}] zaten var` }
  }
  let { start, end, body } = unreleasedBlock(lines)
  if (start < 0) {
    // Unreleased başlığı yok → ilk `## ` başlığından önce oluştur
    const firstH2 = lines.findIndex((l) => /^## /.test(l))
    const at = firstH2 < 0 ? lines.length : firstH2
    lines.splice(at, 0, '## [Unreleased]', '')
    ;({ start, end, body } = unreleasedBlock(lines))
  }
  if (!hasContent(body)) {
    return { text: fixLinks(lines.join(eol), version), changed: false, reason: 'Unreleased boş — başlık açılmadı' }
  }
  // Sondaki boş satırları/ayraçları gövdeden ayır, yeni sürüm bloğunu kur
  const trimmed = [...body]
  while (trimmed.length && !trimmed[0].trim()) trimmed.shift()
  while (trimmed.length && !trimmed[trimmed.length - 1].trim()) trimmed.pop()
  const out = [
    ...lines.slice(0, start + 1),
    '',
    `## [${version}] — ${date}`,
    '',
    ...trimmed,
    '',
    ...lines.slice(end),
  ]
  return { text: fixLinks(out.join(eol), version), changed: true, reason: `Unreleased → ## [${version}]` }
}

/** Alt bağlantı bloğu: her `## [X.Y.Z]` başlığı için doğru repo linki; [Unreleased] compare linki. */
export function fixLinks(text, latestVersion) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n'
  const versions = [...text.matchAll(/^## \[(\d+\.\d+\.\d+)\]/gm)].map((m) => m[1])
  const uniq = [...new Set(versions)]
  const body = text.split(/\r?\n/).filter((l) => !/^\[(Unreleased|\d+\.\d+\.\d+(?: → \d+\.\d+\.\d+)?)\]: https?:\/\//.test(l))
  while (body.length && !body[body.length - 1].trim()) body.pop()
  const links = []
  const newest = uniq[0] || latestVersion
  if (newest) links.push(`[Unreleased]: ${REPO_URL}/compare/v${newest}...HEAD`)
  for (const v of uniq) links.push(`[${v}]: ${REPO_URL}/releases/tag/v${v}`)
  return [...body, '', ...links].join(eol) + eol
}

function parseArgs(argv) {
  const a = { file: 'CHANGELOG.md', date: new Date().toISOString().slice(0, 10) }
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i]
    if (k === '--version') a.version = argv[++i]
    else if (k === '--date') a.date = argv[++i]
    else if (k === '--file') a.file = argv[++i]
    else throw new Error('bilinmeyen argüman: ' + k)
  }
  if (!a.version || !/^\d+\.\d+\.\d+$/.test(a.version)) throw new Error('--version X.Y.Z gerekli')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(a.date)) throw new Error('--date YYYY-MM-DD olmalı')
  return a
}

export function main(argv = process.argv.slice(2)) {
  const here = path.dirname(fileURLToPath(import.meta.url))
  const cwd = path.resolve(here, '..')
  const args = parseArgs(argv)
  const file = path.resolve(cwd, args.file)
  const before = fs.readFileSync(file, 'utf8')
  const { text, changed, reason } = promote(before, args.version, args.date)
  if (text !== before) fs.writeFileSync(file, text)
  console.log(`changelog: ${reason}${text !== before && !changed ? ' (bağlantılar düzeltildi)' : ''}`)
  return 0
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exit(main()) } catch (e) { console.error(e.message); process.exit(1) }
}
