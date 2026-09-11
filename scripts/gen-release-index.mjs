#!/usr/bin/env node
// Yayın indeksi üreteci — "hangi sürüm ne zaman çıktı, ne içeriyordu".
//
// Kaynak: git tag'leri (hepsi annotated, taggerdate = yayın anı). Çıktı: docs/releases/index.json.
// Uygulama ÇALIŞMA ANINDA GitHub'a çıkamaz (kurum ağında egress yok); indeks build zamanında
// üretilir, Dockerfile imaja /app/releases.json olarak kopyalar, backend ReleaseIndexService okur.
//
// Kullanım:
//   node scripts/gen-release-index.mjs --full                       # tüm tag'lerden sıfırdan
//   node scripts/gen-release-index.mjs --append --version 20.54.0 --prev-tag v20.53.2 \
//        [--date 2026-09-11T08:00:00Z] [--commit <sha>]                # CI release adımı (bump commit'inden ÖNCE)
//   node scripts/gen-release-index.mjs --check                      # --full ile fark var mı (generatedAt/count hariç) → çıkış 1
//
// Kurallar:
//   • `commit` = İÇERİK başı: tag hedefi "chore(release): bump" ise tag^, değilse hedef. Pod'daki
//     APP_GIT_COMMIT (= github.sha, bump ÖNCESİ) ile birebir eşleşsin diye.
//   • chore(release) başlıkları düşer (skip-ci belirteci indekse hiç girmez).
//   • Bump türü sürüm FARKINDAN (geçmişte elle workflow_dispatch major'lar var; commit'lere güvenilmez).
//   • Gövde yalnız BREAKING CHANGE bayrağı için okunur, hiç yazılmaz. Başlıklar scrub'lanır:
//     IdentityLeakGuardTest'in yasak token'ları (aynı bölünmüş-literal hilesi), e-posta ve URL'ler.
//   • Deterministik: sabit anahtar sırası, 2 boşluk, sonda tek satır sonu.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const SCHEMA = 1
export const MAX_CHANGES = 60
export const MAX_SUBJECT = 200
export const DEFAULT_OUT = 'docs/releases/index.json'
export const REPO = 'inanmise/site-monitor'

// Gerçek kimlik yasakları — IdentityLeakGuardTest.FORBIDDEN ile aynı liste, aynı bölünmüş-literal
// hilesi (bu dosya .mjs olduğu için taranmaz, ama kural aynı: literal yazılmaz).
export const REDACT = [
  'akb' + 'ank', 'ak' + 'net', 'ocp' + 'int',
  'SY-Dij' + 'ital', 'SY-K' + 'art', 'Dijital Ban' + 'kacılık', 'Dijital Ban' + 'kacilik',
  'dijit' + 'alsy', 'Kav' + 'ruk', 'Ekme' + 'kçi',
]

const SUBJECT_RE = /^(?<type>[a-z]+)(\((?<scope>[^)]*)\))?(?<bang>!)?:\s*(?<subject>.+)$/
const RELEASE_COMMIT_RE = /^chore\(release\)/
const SEMVER_TAG_RE = /^v(\d+)\.(\d+)\.(\d+)$/
const COUNT_TYPES = ['feat', 'fix', 'perf', 'refactor', 'docs', 'other']

export function parseVersion(v) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)/.exec(String(v || '').trim())
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null
}

export function compareVersions(a, b) {
  const pa = parseVersion(a) || [0, 0, 0]
  const pb = parseVersion(b) || [0, 0, 0]
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pa[i] - pb[i]
  return 0
}

/** Conventional Commits başlığı → {type, scope, breaking, subject}; uymayan → type 'other'. */
export function parseSubject(raw) {
  const s = String(raw || '').trim()
  const m = SUBJECT_RE.exec(s)
  if (!m) return { type: 'other', scope: null, breaking: false, subject: s }
  const type = COUNT_TYPES.includes(m.groups.type) ? m.groups.type : m.groups.type
  return { type, scope: m.groups.scope || null, breaking: !!m.groups.bang, subject: m.groups.subject.trim() }
}

/** Bump türü SÜRÜM FARKINDAN: major↑ → major, minor↑ → minor, aksi → patch; öncekisi yoksa initial. */
export function inferBump(version, prevVersion) {
  const cur = parseVersion(version)
  const prev = parseVersion(prevVersion)
  if (!cur || !prev) return 'initial'
  if (cur[0] !== prev[0]) return 'major'
  if (cur[1] !== prev[1]) return 'minor'
  return 'patch'
}

/** Gerçek kimlik / e-posta / URL temizliği. Büyük-küçük harf duyarsız. */
export function scrub(text) {
  let t = String(text || '')
  // CI belirteci başlıklarda kalmasın (eski "ci: … [skip ci]" başlıkları): indeks bir commit mesajı
  // değildir ama belirtecin depo içinde çoğalması istenmez.
  t = t.replace(/\[skip[ -]ci\]/gi, '').replace(/\s{2,}/g, ' ').trim()
  t = t.replace(/https?:\/\/\S+/g, '[url]')
  t = t.replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, '[email]')
  for (const bad of REDACT) {
    const re = new RegExp(bad.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi')
    t = t.replace(re, '[redacted]')
  }
  if (t.length > MAX_SUBJECT) t = t.slice(0, MAX_SUBJECT - 1) + '…'
  return t
}

export function sortReleases(releases) {
  return [...releases].sort((a, b) => compareVersions(b.version, a.version))
}

/**
 * Görünüm katlama (K5.1): minor/major her zaman kalır; `deployedVersions` kümesindeki yamalar kalır;
 * diğer yamalar en yakın ESKİ tutulan sürümün `collapsedPatches` listesine katlanır.
 * Girdi azalan sıralı olmalı. Backend'de aynı kural Java'da; burada üretici testleri için.
 */
export function foldPatches(releasesDesc, deployedVersions = new Set()) {
  const out = []
  let pending = [] // katlanacak yamalar — kendilerinden ESKİ ilk tutulan sürüme (taban minor) bağlanır
  for (const r of releasesDesc) {
    const keep = r.bump !== 'patch' || deployedVersions.has(r.version)
    if (keep) {
      out.push({ ...r, collapsedPatches: pending })
      pending = []
    } else {
      pending.push(r.version)
    }
  }
  if (pending.length) {
    if (out.length) out[out.length - 1].collapsedPatches.push(...pending)
    else return releasesDesc.map((r) => ({ ...r, collapsedPatches: [] })) // hepsi yama: hepsini göster
  }
  return out
}

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).replace(/\r/g, '')
}

function toUtcIso(s) {
  const d = new Date(String(s).trim())
  if (Number.isNaN(d.getTime())) throw new Error('geçersiz tarih: ' + s)
  return d.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

/** Tag listesi: [{tag, version, releasedAt, tagObj, target}] semver artan. */
export function listTags(cwd) {
  const raw = git(['for-each-ref', '--format=%(refname:short)%09%(taggerdate:iso-strict)%09%(objectname)%09%(*objectname)', 'refs/tags'], cwd)
  const rows = []
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue
    const [tag, date, obj, target] = line.split('\t')
    const m = SEMVER_TAG_RE.exec(tag)
    if (!m) continue
    if (!date) continue // lightweight tag — yayın anı bilinmiyor, atla (bugün yok: 628/628 annotated)
    rows.push({ tag, version: tag.slice(1), releasedAt: toUtcIso(date), tagObj: obj, target: target || obj })
  }
  return rows.sort((a, b) => compareVersions(a.version, b.version))
}

function contentHead(target, cwd) {
  const subject = git(['log', '-1', '--format=%s', target], cwd).trim()
  return RELEASE_COMMIT_RE.test(subject) ? git(['rev-parse', target + '^'], cwd).trim() : target
}

function rangeChanges(range, cwd) {
  const raw = git(['log', '--no-merges', '--format=%H%x1f%s%x1f%b%x1e', range], cwd)
  const changes = []
  let breakingAny = false
  for (const rec of raw.split('\x1e')) {
    if (!rec.trim()) continue
    const [sha, subject, body] = rec.replace(/^\n/, '').split('\x1f')
    if (!sha || RELEASE_COMMIT_RE.test(subject || '')) continue
    const p = parseSubject(subject)
    const breaking = p.breaking || /^BREAKING[- ]CHANGE:/m.test(body || '')
    if (breaking) breakingAny = true
    changes.push({ type: p.type, scope: p.scope, breaking, sha: sha.slice(0, 8), subject: scrub(p.subject) })
  }
  return { changes, breakingAny }
}

export function buildRelease({ version, tag, releasedAt, commit, prevVersion, changes, breakingAny }) {
  const counts = Object.fromEntries(COUNT_TYPES.map((t) => [t, 0]))
  for (const c of changes) counts[COUNT_TYPES.includes(c.type) ? c.type : 'other']++
  const truncated = changes.length > MAX_CHANGES
  return {
    version, tag, releasedAt, commit, prevVersion: prevVersion || null,
    bump: inferBump(version, prevVersion), breaking: !!breakingAny, counts,
    changes: truncated ? changes.slice(0, MAX_CHANGES) : changes,
    truncated, omitted: truncated ? changes.length - MAX_CHANGES : 0,
  }
}

export function buildIndexFull(cwd) {
  const tags = listTags(cwd)
  const releases = []
  for (let i = 0; i < tags.length; i++) {
    const t = tags[i]
    const prev = i > 0 ? tags[i - 1] : null
    const range = prev ? `${prev.tag}..${t.tag}` : t.tag
    const { changes, breakingAny } = rangeChanges(range, cwd)
    releases.push(buildRelease({
      version: t.version, tag: t.tag, releasedAt: t.releasedAt,
      commit: contentHead(t.target, cwd), prevVersion: prev ? prev.version : null, changes, breakingAny,
    }))
  }
  return wrap(sortReleases(releases))
}

export function wrap(releasesDesc, generatedAt = new Date().toISOString().replace(/\.\d{3}Z$/, 'Z')) {
  return { schema: SCHEMA, generatedAt, repo: REPO, count: releasesDesc.length, releases: releasesDesc }
}

export function serialize(index) {
  return JSON.stringify(index, null, 2) + '\n'
}

export function readIndex(file) {
  if (!fs.existsSync(file)) return wrap([])
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
  return wrap(sortReleases(parsed.releases || []), parsed.generatedAt)
}

export function appendRelease(cwd, file, { version, prevTag, date, commit }) {
  const idx = readIndex(file)
  const prevVersion = prevTag ? prevTag.replace(/^v/, '') : (idx.releases[0] ? idx.releases[0].version : null)
  const range = prevTag ? `${prevTag}..HEAD` : 'HEAD'
  const { changes, breakingAny } = rangeChanges(range, cwd)
  const rel = buildRelease({
    version, tag: 'v' + version,
    releasedAt: toUtcIso(date || new Date().toISOString()),
    commit: commit || git(['rev-parse', 'HEAD'], cwd).trim(),
    prevVersion, changes, breakingAny,
  })
  const rest = idx.releases.filter((r) => r.version !== version) // yeniden koşum güvenli
  return wrap(sortReleases([rel, ...rest]))
}

/** --check: generatedAt/count dışında fark var mı. */
export function indexDiffers(a, b) {
  const norm = (x) => serialize({ ...x, generatedAt: '', count: x.releases.length })
  return norm(a) !== norm(b)
}

function parseArgs(argv) {
  const a = { mode: null, out: DEFAULT_OUT }
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i]
    if (k === '--full' || k === '--append' || k === '--check') a.mode = k.slice(2)
    else if (k === '--version') a.version = argv[++i]
    else if (k === '--prev-tag') a.prevTag = argv[++i]
    else if (k === '--date') a.date = argv[++i]
    else if (k === '--commit') a.commit = argv[++i]
    else if (k === '--out') a.out = argv[++i]
    else throw new Error('bilinmeyen argüman: ' + k)
  }
  return a
}

export function main(argv = process.argv.slice(2)) {
  const here = path.dirname(fileURLToPath(import.meta.url))
  const cwd = path.resolve(here, '..')
  const args = parseArgs(argv)
  const file = path.resolve(cwd, args.out)
  if (args.mode === 'full') {
    const idx = buildIndexFull(cwd)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, serialize(idx))
    console.log(`release index: ${idx.count} sürüm → ${path.relative(cwd, file)} (${(serialize(idx).length / 1024).toFixed(0)} KB)`)
    return 0
  }
  if (args.mode === 'append') {
    if (!args.version) throw new Error('--append için --version gerekli')
    const idx = appendRelease(cwd, file, args)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, serialize(idx))
    console.log(`release index: v${args.version} eklendi (toplam ${idx.count})`)
    return 0
  }
  if (args.mode === 'check') {
    const fresh = buildIndexFull(cwd)
    const current = readIndex(file)
    if (indexDiffers(fresh, current)) {
      console.error(`release index BAYAT: ${path.relative(cwd, file)} — node scripts/gen-release-index.mjs --full`)
      return 1
    }
    console.log('release index güncel')
    return 0
  }
  console.error('kullanım: --full | --append --version X [--prev-tag vY] [--date ISO] [--commit SHA] | --check')
  return 2
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { process.exit(main()) } catch (e) { console.error(e.message); process.exit(1) }
}
