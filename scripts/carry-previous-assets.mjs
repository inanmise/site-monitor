#!/usr/bin/env node
// Önceki sürümün sayfa dosyalarını yeni imaja taşır (2026-10-23, çok pod sürüm 2b · MP-47) — Dockerfile "asset-carry"
// aşaması çağırır; CI'da `node --test scripts/__tests__/carry-previous-assets.test.mjs` sınar.
//
// Neden: yuvarlanan dağıtımda (maxSurge 1) eski ve yeni pod birlikte sunar. Eski podun index.html'i eski hash'li
// /assets/<ad>-<hash>.js dosyalarını ister; istek yeni poda düşerse dosya yoktur → düz metin 404 → boş sayfa. Dağıtımdan
// önce açılmış sekme (TV duvar ekranı, uzun oturum) tembel bir parçayı geç yüklediğinde de aynı. Yeni imaj, ÖNCEKİ
// sürümün KENDİ varlıklarını da taşırsa iki yön de çalışır (hash'li adlar çakışmaz: aynı ad = aynı içerik).
//
// Sınır — asla büyüyen bir yığın değil: yalnız bir önceki sürümün KENDİ varlıkları taşınır. Her imaj kendi listesini
// /app/frontend/own-assets.txt'ye yazar (sunulan dist'in DIŞINDA); sonraki derleme yalnız o listedekileri alır, yani
// önceki imajın kendisinin taşıdıkları (N−2) gelmez. Liste yoksa (bu değişiklikten önceki imaj — hiç taşımamıştı)
// önceki imajın assets dizinindeki dosyaların hepsi o sürümün kendi dosyalarıdır.
//
// Kullanım: node carry-previous-assets.mjs --previous <önceki imajın /app/frontend'i> --own <yeni dist/assets> --out <dizin>
// Çıktı: <out>/assets/<taşınan dosyalar>, <out>/own-assets.txt (bu derlemenin kendi listesi), <out>/carried-assets.txt.
// Önceki sürüm yok (--previous'ta dist/assets yok) → hiçbir şey taşınmaz, derleme bugünkü gibi sürer.
import { copyFileSync, existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

/** Taşınan dosya sayısı / bayt üst sınırı (bir sürüm bugün ~310 dosya, ~14 MB). Aşılırsa HİÇBİR şey taşınmaz. */
export const MAX_FILES = 5000
export const MAX_BYTES = 200 * 1024 * 1024
/** Geçerli varlık adı: düz dosya adı (dizin ayırıcı yok, gizli dosya yok). Listeden gelen her ad buna uymalı. */
export const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/

/** Dizindeki DÜZ dosyaların adları (alt dizin / sembolik bağ yok), sıralı; dizin yoksa boş. */
export function listFiles(dir) {
  if (!existsSync(dir)) return []
  const out = []
  for (const name of readdirSync(dir)) {
    let st
    try { st = lstatSync(join(dir, name)) } catch { continue }
    if (st.isFile()) out.push(name)
    if (out.length > MAX_FILES) break                       // sınırın bir fazlası yeter: "aşıldı" bilinir
  }
  return out.sort()
}

/** Ad listesi dosyası (satır başına bir ad); yoksa null. Boş satırlar ve tekrarlar atlanır; en çok MAX_FILES + 1 ad. */
export function readList(file) {
  if (!existsSync(file)) return null
  const seen = new Set()
  for (const raw of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const name = raw.trim()
    if (!name || seen.has(name)) continue
    seen.add(name)
    if (seen.size > MAX_FILES) break
  }
  return [...seen]
}

/**
 * Ne taşınır: önceki sürümün listesindeki (yoksa assets dizinindeki) adlardan geçerli olan, önceki imajda düz dosya
 * olarak duran ve YENİ derlemede bulunmayanlar. Sınır aşılırsa liste boşalır (reason "cap").
 * @returns {{ carry: string[], bytes: number, invalid: string[], missing: number, own: number, legacy: boolean, reason: string }}
 */
export function planCarry({ prevAssetsDir, prevList, ownNames }) {
  const ownSet = new Set(ownNames)
  const legacy = prevList == null
  const candidates = legacy ? listFiles(prevAssetsDir) : prevList
  const plan = { carry: [], bytes: 0, invalid: [], missing: 0, own: 0, legacy, reason: 'ok' }
  if (!existsSync(prevAssetsDir)) { plan.reason = 'no-previous'; return plan }
  for (const name of candidates) {
    if (!NAME.test(name)) { plan.invalid.push(name); continue }
    if (ownSet.has(name)) { plan.own++; continue }          // aynı ad = aynı içerik: yeni derlemeninki kullanılır
    let st
    try { st = lstatSync(join(prevAssetsDir, name)) } catch { plan.missing++; continue }
    if (!st.isFile()) { plan.missing++; continue }
    plan.carry.push(name)
    plan.bytes += st.size
  }
  if (candidates.length > MAX_FILES || plan.carry.length > MAX_FILES || plan.bytes > MAX_BYTES) {
    return { ...plan, carry: [], bytes: 0, reason: 'cap' }
  }
  return plan
}

/** Planı uygular: dosyaları kopyalar, iki listeyi yazar. Dönen değer planın kendisidir. */
export function carry({ previous, own, out }) {
  const ownNames = listFiles(own)
  if (ownNames.length === 0) throw new Error(`carry: yeni derlemenin varlık dizini boş ya da yok: ${own}`)
  const prevAssetsDir = join(previous, 'dist', 'assets')
  const plan = planCarry({ prevAssetsDir, prevList: readList(join(previous, 'own-assets.txt')), ownNames })
  mkdirSync(join(out, 'assets'), { recursive: true })
  for (const name of plan.carry) copyFileSync(join(prevAssetsDir, name), join(out, 'assets', name))
  writeFileSync(join(out, 'own-assets.txt'), ownNames.join('\n') + '\n')
  writeFileSync(join(out, 'carried-assets.txt'), plan.carry.length ? plan.carry.join('\n') + '\n' : '')
  return { ...plan, ownCount: ownNames.length }
}

function arg(argv, name) {
  const i = argv.indexOf(name)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : null
}

export function main(argv = process.argv.slice(2), log = console.log) {
  const previous = arg(argv, '--previous')
  const own = arg(argv, '--own')
  const out = arg(argv, '--out')
  if (!previous || !own || !out) {
    log('kullanım: carry-previous-assets.mjs --previous <dir> --own <dist/assets> --out <dir>')
    return 2
  }
  const r = carry({ previous, own, out })
  const mb = (r.bytes / 1024 / 1024).toFixed(1)
  if (r.reason === 'no-previous') log(`carry: önceki sürüm yok — yalnız bu derlemenin ${r.ownCount} varlığı`)
  else if (r.reason === 'cap') log(`::warning::carry: önceki sürümden taşınacak dosya sınırı aştı (${MAX_FILES} dosya / ${MAX_BYTES} bayt) — hiçbir şey taşınmadı`)
  else log(`carry: önceki sürümden ${r.carry.length} dosya (${mb} MB) taşındı${r.legacy ? ' (önceki imajın listesi yok — assets dizininin tamamı o sürümündü)' : ''}; bu derlemenin ${r.ownCount} varlığı, ${r.own} ortak ad`)
  if (r.invalid.length) log(`::warning::carry: geçersiz ${r.invalid.length} ad atlandı (ör. ${JSON.stringify(r.invalid[0]).slice(0, 80)})`)
  return 0
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  process.exitCode = main()
}
