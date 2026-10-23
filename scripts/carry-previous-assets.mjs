#!/usr/bin/env node
// Önceki sürümlerin sayfa dosyalarını yeni imaja taşır (2026-10-23, çok pod sürüm 2b · MP-47) — Dockerfile "asset-carry"
// aşaması çağırır; CI'da `node --test scripts/__tests__/carry-previous-assets.test.mjs` sınar.
//
// Neden: yuvarlanan dağıtımda (maxSurge 1) eski ve yeni pod birlikte sunar. Eski podun index.html'i eski hash'li
// /assets/<ad>-<hash>.js dosyalarını ister; istek yeni poda düşerse dosya yoktur → düz metin 404 → boş sayfa. Dağıtımdan
// önce açılmış sekme (TV duvar ekranı, uzun oturum) tembel bir parçayı geç yüklediğinde de aynı. Yeni imaj, son
// birkaç sürümün KENDİ varlıklarını da taşırsa "eski index → yeni pod" yönü çalışır (hash'li adlar çakışmaz: aynı ad =
// aynı içerik). Ters yön (yeni index → eski pod) taşımayla çözülemez — onu açılış koruması (boot-guard.js) bir kez yeniler.
//
// Son K sürüm (varsayılan 5, --releases): prod elle dağıtılır ve çoğu kez birkaç sürüm atlar; yalnız arka uç değişen bir
// sürüm (arayüz paketi aynı) hiçbir şey taşımaz ama geçmişi taşır. Her imaj /app/frontend/asset-history.json'a (sunulan
// dist'in DIŞINDA) [kendi sürümü + taşıdığı sürümler] listesini, en yeni önce, yazar; sonraki derleme ÖNCEKİ imajın bu
// listesinin ilk K girdisinin dosyalarını — önceki imajda duranları — taşır. Yani tek önceki imajla K sürüm geriye gidilir;
// daha eskisi düşer (büyüyen yığın asla). Sınır (5000 dosya / 200 MB) aşılırsa EN ESKİ sürümler bırakılır.
// Liste yoksa: own-assets.txt (bu değişikliğin ilk biçimi) tek girdi; o da yoksa (20.166.0 ve öncesi — hiç taşımamıştı)
// önceki imajın assets dizininin tamamı o sürümün kendi dosyalarıdır.
//
// Kullanım: node carry-previous-assets.mjs --previous <önceki imajın /app/frontend'i> --own <yeni dist/assets> --out <dizin>
//             [--release <bu sürüm>] [--previous-release <önceki sürüm (listesiz eski imaj etiketi)>] [--releases K]
// Çıktı (<out>): assets/<taşınan>, own-assets.txt, carried-assets.txt, asset-history.json, carry-report.json.
import { copyFileSync, existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

/** Taşınan dosya sayısı / bayt üst sınırı (bir sürüm bugün ~310 dosya, ~14 MB). Aşan EN ESKİ sürümler bırakılır. */
export const MAX_FILES = 5000
export const MAX_BYTES = 200 * 1024 * 1024
/** Kaç önceki sürümün dosyaları taşınır (varsayılan) ve en çok kaç. */
export const DEFAULT_RELEASES = 5
export const MAX_RELEASES = 10
/** Geçerli varlık adı: düz dosya adı (dizin ayırıcı yok, gizli dosya yok). Listeden gelen her ad buna uymalı. */
export const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,199}$/
const LABEL = /^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$/

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
  return uniqueNames(readFileSync(file, 'utf8').split(/\r?\n/))
}

function uniqueNames(raw) {
  const seen = new Set()
  for (const r of raw) {
    const name = typeof r === 'string' ? r.trim() : ''
    if (!name || seen.has(name)) continue
    seen.add(name)
    if (seen.size > MAX_FILES) break
  }
  return [...seen]
}

function label(v, fallback) {
  const s = typeof v === 'string' ? v.trim() : ''
  return LABEL.test(s) ? s : fallback
}

/**
 * Önceki imajın sürüm geçmişi, en yeni önce: [{release, files}]. asset-history.json → own-assets.txt (tek girdi) →
 * dist/assets'in tamamı (listesiz eski imaj, tek girdi) → önceki sürüm yok (null). Bozuk geçmiş dosyası bir sonraki
 * kaynağa düşer. En çok MAX_RELEASES + 1 girdi okunur.
 */
export function readHistory(previous, previousRelease = 'previous') {
  const file = join(previous, 'asset-history.json')
  if (existsSync(file)) {
    try {
      const doc = JSON.parse(readFileSync(file, 'utf8'))
      if (doc && Array.isArray(doc.releases)) {
        return doc.releases.slice(0, MAX_RELEASES + 1)
          .filter((e) => e && Array.isArray(e.files))
          .map((e, i) => ({ release: label(e.release, `previous-${i}`), files: uniqueNames(e.files) }))
      }
    } catch { /* bozuk → aşağıdaki kaynaklar */ }
  }
  const own = readList(join(previous, 'own-assets.txt'))
  if (own) return [{ release: label(previousRelease, 'previous'), files: own }]
  const legacyDir = join(previous, 'dist', 'assets')
  if (existsSync(legacyDir)) return [{ release: label(previousRelease, 'previous'), files: listFiles(legacyDir) }]
  return null
}

/**
 * Ne taşınır: geçmişin ilk `releases` girdisinin (en yeni önce) adlarından geçerli olan, önceki imajda düz dosya olarak
 * duran ve YENİ derlemede bulunmayanlar. Bir girdinin yeni adları sınırı aşarsa o girdi ve DAHA ESKİLERİ bırakılır.
 * @returns {{ carry: string[], bytes: number, included: string[], dropped: string[], invalid: string[], missing: number,
 *             own: number, reason: 'ok' | 'no-previous' | 'cap' }}
 */
export function planCarry({ prevAssetsDir, history, ownNames, releases = DEFAULT_RELEASES, maxFiles = MAX_FILES, maxBytes = MAX_BYTES }) {
  const plan = { carry: [], bytes: 0, included: [], dropped: [], invalid: [], missing: 0, own: 0, reason: 'ok' }
  if (!history || !existsSync(prevAssetsDir)) { plan.reason = 'no-previous'; return plan }
  const ownSet = new Set(ownNames)
  const chosen = new Set()
  const entries = history.slice(0, Math.max(1, Math.min(MAX_RELEASES, releases)))
  for (let i = 0; i < entries.length; i++) {
    const add = []
    let bytes = 0
    for (const name of entries[i].files) {
      if (!NAME.test(name)) { plan.invalid.push(name); continue }
      if (ownSet.has(name)) { plan.own++; continue }         // aynı ad = aynı içerik: yeni derlemeninki kullanılır
      if (chosen.has(name) || add.includes(name)) continue
      let st
      try { st = lstatSync(join(prevAssetsDir, name)) } catch { plan.missing++; continue }
      if (!st.isFile()) { plan.missing++; continue }
      add.push(name)
      bytes += st.size
    }
    if (entries[i].files.length > MAX_FILES || plan.carry.length + add.length > maxFiles || plan.bytes + bytes > maxBytes) {
      plan.reason = 'cap'
      plan.dropped = entries.slice(i).map((e) => e.release)
      break
    }
    for (const n of add) chosen.add(n)
    plan.carry.push(...add)
    plan.bytes += bytes
    plan.included.push(entries[i].release)
  }
  plan.carry.sort()
  return plan
}

/** Planı uygular: dosyaları kopyalar, listeleri ve raporu yazar. */
export function carry({ previous, own, out, release = 'current', previousRelease = 'previous', releases = DEFAULT_RELEASES }) {
  const ownNames = listFiles(own)
  if (ownNames.length === 0) throw new Error(`carry: yeni derlemenin varlık dizini boş ya da yok: ${own}`)
  const k = Math.max(1, Math.min(MAX_RELEASES, Number.parseInt(releases, 10) || DEFAULT_RELEASES))
  const prevAssetsDir = join(previous, 'dist', 'assets')
  const history = readHistory(previous, previousRelease)
  const plan = planCarry({ prevAssetsDir, history, ownNames, releases: k })
  mkdirSync(join(out, 'assets'), { recursive: true })
  for (const name of plan.carry) copyFileSync(join(prevAssetsDir, name), join(out, 'assets', name))
  writeFileSync(join(out, 'own-assets.txt'), ownNames.join('\n') + '\n')
  writeFileSync(join(out, 'carried-assets.txt'), plan.carry.length ? plan.carry.join('\n') + '\n' : '')
  // Bu imajın geçmişi: kendisi + gerçekten taşıdığı sürümler, sırayla (bırakılanlar yok — dosyaları bu imajda değil).
  const kept = (history || []).slice(0, plan.included.length)
  const self = { release: label(release, 'current'), files: ownNames }
  writeFileSync(join(out, 'asset-history.json'), JSON.stringify({ v: 1, releases: [self, ...kept] }) + '\n')
  const report = {
    release: self.release, releases: k, reason: plan.reason, included: plan.included, dropped: plan.dropped,
    carried: plan.carry.length, bytes: plan.bytes, own: ownNames.length, invalid: plan.invalid.length, missing: plan.missing,
  }
  writeFileSync(join(out, 'carry-report.json'), JSON.stringify(report) + '\n')
  return { ...plan, ownCount: ownNames.length, releases: k }
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
    log('kullanım: carry-previous-assets.mjs --previous <dir> --own <dist/assets> --out <dir> [--release v] [--previous-release v] [--releases K]')
    return 2
  }
  const r = carry({
    previous, own, out,
    release: arg(argv, '--release') || 'current',
    previousRelease: arg(argv, '--previous-release') || 'previous',
    releases: arg(argv, '--releases') || DEFAULT_RELEASES,
  })
  const mb = (r.bytes / 1024 / 1024).toFixed(1)
  if (r.reason === 'no-previous') log(`carry: önceki sürüm yok — yalnız bu derlemenin ${r.ownCount} varlığı`)
  else log(`carry: son ${r.releases} sürümden ${r.carry.length} dosya (${mb} MB) taşındı — sürümler: ${r.included.join(', ') || '-'}; bu derlemenin ${r.ownCount} varlığı, ${r.own} ortak ad${r.carry.length === 0 ? ' (arayüz paketi değişmemiş)' : ''}`)
  if (r.reason === 'cap') log(`::warning::carry: sınır (${MAX_FILES} dosya / ${MAX_BYTES} bayt) — en eski sürümler taşınmadı: ${r.dropped.join(', ')}`)
  if (r.invalid.length) log(`::warning::carry: geçersiz ${r.invalid.length} ad atlandı (ör. ${JSON.stringify(r.invalid[0]).slice(0, 80)})`)
  return 0
}

if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  process.exitCode = main()
}
