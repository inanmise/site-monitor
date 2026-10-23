#!/usr/bin/env node
// Taşıma çıktısının bağımsız denetimi (2026-10-23, çok pod sürüm 2b · MP-47) — ci.yml "image-assets" işi, Dockerfile'ın
// asset-carry-check hedefinin dışa aktardığı iki dizinle çağırır:
//   <out>       carry-previous-assets.mjs çıktısı (assets/, own-assets.txt, carried-assets.txt, asset-history.json, carry-report.json)
//   <previous>  önceki imajdan alınan girdiler (assets.txt = önceki dist/assets listesi, varsa asset-history.json / own-assets.txt)
// Beklenen taşıma kümesi boyutsuz yeniden hesaplanır: (önceki geçmişin ilk K girdisinin adları ∩ önceki imajda duranlar)
// − bu derlemenin adları. Taşınan tam bu küme olmalı (sınır aşıldıysa alt kümesi + uyarı). Beklenen boşsa — arayüz paketi
// son K sürümden beri değişmemiş — bu bir BİLGİ notudur, hata DEĞİL (yalnız arka uç değişen PR / sürüm kırmızı olmasın).
//
// Kullanım: node scripts/check-carry-output.mjs --out <dir> --previous <dir> [--expect-previous]
// Çıkış kodu: 0 = tamam (::notice:: / ::warning:: satırları olabilir), 1 = ::error:: satırları var.
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const lines = (file) => (existsSync(file) ? readFileSync(file, 'utf8').split(/\r?\n/).map((s) => s.trim()).filter(Boolean) : null)
const json = (file) => { try { return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : null } catch { return undefined } }
const sameSet = (a, b) => a.size === b.size && [...a].every((x) => b.has(x))

/** Önceki imajın geçmişi (carry-previous-assets.mjs readHistory ile aynı öncelik): json → own-assets.txt → tüm liste. */
function previousEntries(previous) {
  const doc = json(join(previous, 'asset-history.json'))
  if (doc && Array.isArray(doc.releases)) return doc.releases.filter((e) => e && Array.isArray(e.files)).map((e) => ({ release: e.release, files: e.files }))
  const own = lines(join(previous, 'own-assets.txt'))
  if (own) return [{ release: null, files: own }]
  const all = lines(join(previous, 'assets.txt'))
  return all && all.length ? [{ release: null, files: all }] : null
}

/** @returns {{ errors: string[], warnings: string[], notices: string[] }} */
export function checkCarry({ out, previous, expectPrevious = false }) {
  const errors = []; const warnings = []; const notices = []
  const own = lines(join(out, 'own-assets.txt')) || []
  const carried = lines(join(out, 'carried-assets.txt'))
  const report = json(join(out, 'carry-report.json'))
  const history = json(join(out, 'asset-history.json'))
  if (!own.length) errors.push('own-assets.txt boş ya da yok — bu derlemenin varlık listesi yazılmadı (sonraki sürüm taşıyamaz)')
  if (carried == null) errors.push('carried-assets.txt yok')
  if (!report || typeof report !== 'object') errors.push('carry-report.json yok ya da bozuk')
  if (!history || !Array.isArray(history.releases) || !history.releases.length) errors.push('asset-history.json yok ya da bozuk')
  if (errors.length) return { errors, warnings, notices }

  const ownSet = new Set(own)
  const carriedSet = new Set(carried)
  const onDisk = new Set(existsSync(join(out, 'assets')) ? readdirSync(join(out, 'assets')) : [])
  if (!sameSet(new Set(history.releases[0].files), ownSet)) errors.push('asset-history.json ilk girdisi bu derlemenin listesi değil')
  if (history.releases.length > report.releases + 1) errors.push(`asset-history.json ${history.releases.length} girdi (en çok ${report.releases + 1})`)
  if (!sameSet(onDisk, carriedSet)) errors.push(`assets/ dizini (${onDisk.size}) ile carried-assets.txt (${carriedSet.size}) aynı değil`)
  for (const n of carried) if (ownSet.has(n)) errors.push(`bu derlemede de olan ad taşındı: ${n}`)
  if (carried.length > 5000) errors.push(`taşınan dosya sayısı ${carried.length} > 5000`)

  const prevListing = new Set(lines(join(previous, 'assets.txt')) || [])
  const entries = previousEntries(previous)
  if (!entries || !prevListing.size) {
    if (carried.length) errors.push('önceki imaj yok ama dosya taşındı')
    if (report.reason !== 'no-previous') errors.push(`önceki imaj yok ama rapor "${report.reason}"`)
    if (expectPrevious) errors.push('önceki sürüm imajı verildi ama okunamadı / boş (Dockerfile previous-release aşaması)')
    else notices.push('önceki sürüm yok — yalnız ilk derleme yolu')
    return { errors, warnings, notices }
  }
  for (const n of carried) if (!prevListing.has(n)) errors.push(`önceki imajda olmayan ad taşındı: ${n}`)
  const expected = new Set()
  for (const e of entries.slice(0, report.releases)) for (const n of e.files) if (prevListing.has(n) && !ownSet.has(n)) expected.add(n)
  if (report.reason === 'ok') {
    if (!sameSet(carriedSet, expected)) {
      const missing = [...expected].filter((n) => !carriedSet.has(n)).slice(0, 5)
      const extra = [...carriedSet].filter((n) => !expected.has(n)).slice(0, 5)
      errors.push(`taşınan küme beklenenden farklı (beklenen ${expected.size}, taşınan ${carriedSet.size}; eksik ör. ${missing.join(', ') || '-'}; fazla ör. ${extra.join(', ') || '-'})`)
    }
  } else if (report.reason === 'cap') {
    for (const n of carried) if (!expected.has(n)) errors.push(`sınırlı taşımada beklenmeyen ad: ${n}`)
    warnings.push(`sınır aşıldı — en eski sürümler taşınmadı: ${(report.dropped || []).join(', ')}`)
  } else errors.push(`önceki imaj var ama rapor "${report.reason}"`)
  if (!expected.size) notices.push(`arayüz paketi son ${report.releases} sürümden beri değişmemiş — taşınacak dosya yok (hata değil)`)
  const prevLabels = entries.slice(0, (report.included || []).length).map((e) => e.release)
  const kept = history.releases.slice(1).map((e) => e.release)
  if (entries[0].release != null && JSON.stringify(kept) !== JSON.stringify(prevLabels)) {
    errors.push(`asset-history.json önceki sürümleri sırayla taşımıyor (${kept.join(', ')} ≠ ${prevLabels.join(', ')})`)
  }
  return { errors, warnings, notices }
}

function arg(argv, name) {
  const i = argv.indexOf(name)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null
}

export function main(argv = process.argv.slice(2), log = console.log) {
  const out = arg(argv, '--out')
  const previous = arg(argv, '--previous')
  if (!out || !previous) { log('kullanım: check-carry-output.mjs --out <dir> --previous <dir> [--expect-previous]'); return 2 }
  const r = checkCarry({ out, previous, expectPrevious: argv.includes('--expect-previous') })
  for (const n of r.notices) log(`::notice::${n}`)
  for (const w of r.warnings) log(`::warning::${w}`)
  for (const e of r.errors) log(`::error::${e}`)
  const rep = json(join(out, 'carry-report.json'))
  if (rep) log(`taşıma: ${rep.carried} dosya, sürümler ${(rep.included || []).join(', ') || '-'}, neden ${rep.reason}`)
  return r.errors.length ? 1 : 0
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  process.exitCode = main()
}
