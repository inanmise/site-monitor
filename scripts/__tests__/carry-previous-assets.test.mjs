// node --test scripts/__tests__/carry-previous-assets.test.mjs — önceki sürümlerin varlıklarını taşıma (çok pod R2b · MP-47)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, existsSync, symlinkSync, cpSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { carry, planCarry, readList, readHistory, listFiles, main, MAX_FILES, NAME, DEFAULT_RELEASES } from '../carry-previous-assets.mjs'
import { checkCarry } from '../check-carry-output.mjs'

function tree(files) {
  const root = mkdtempSync(join(tmpdir(), 'carry-'))
  for (const [path, content] of Object.entries(files)) {
    const full = join(root, path)
    mkdirSync(join(full, '..'), { recursive: true })
    writeFileSync(full, content)
  }
  return root
}
const lines = (file) => readFileSync(file, 'utf8').split('\n').filter(Boolean)
const history = (out) => JSON.parse(readFileSync(join(out, 'asset-history.json'), 'utf8')).releases
const outDir = () => join(tree({}), 'out')

/** Bir carry çıktısını "imaj" yap: o derlemenin dist/assets'i = kendi + taşıdıkları, listeler /app/frontend'de. */
function imageFrom(out, ownDir) {
  const img = tree({})
  mkdirSync(join(img, 'dist', 'assets'), { recursive: true })
  cpSync(join(out, 'assets'), join(img, 'dist', 'assets'), { recursive: true })
  cpSync(ownDir, join(img, 'dist', 'assets'), { recursive: true })
  for (const f of ['own-assets.txt', 'carried-assets.txt', 'asset-history.json']) cpSync(join(out, f), join(img, f))
  return img
}

/** check-carry-output.mjs'in beklediği "previous" dizini: önceki imajın assets listesi + varsa listeleri. */
function checkInputs(prevImage) {
  const dir = tree({})
  writeFileSync(join(dir, 'assets.txt'), existsSync(join(prevImage, 'dist', 'assets')) ? readdirSync(join(prevImage, 'dist', 'assets')).join('\n') : '')
  for (const f of ['asset-history.json', 'own-assets.txt']) if (existsSync(join(prevImage, f))) cpSync(join(prevImage, f), join(dir, f))
  return dir
}

test('önceki sürüm yok: hiçbir şey taşınmaz, kendi listesi + tek girdilik geçmiş yazılır (ilk derleme bugünkü gibi)', () => {
  const prev = tree({ 'NO_PREVIOUS_RELEASE': '' })
  const own = tree({ 'index-B.js': 'b', 'vendor-1.js': 'v' })
  const out = outDir()
  const r = carry({ previous: prev, own, out, release: '20.167.0' })
  assert.equal(r.reason, 'no-previous')
  assert.deepEqual(readdirSync(join(out, 'assets')), [])
  assert.deepEqual(lines(join(out, 'own-assets.txt')), ['index-B.js', 'vendor-1.js'])
  assert.equal(readFileSync(join(out, 'carried-assets.txt'), 'utf8'), '')
  assert.deepEqual(history(out), [{ release: '20.167.0', files: ['index-B.js', 'vendor-1.js'] }])
  assert.deepEqual(checkCarry({ out, previous: checkInputs(prev) }).errors, [])
})

test('listesiz (20.166.0 ve öncesi) imaj: assets dizininin tamamı o sürümündür — yenide olmayanlar taşınır, etiketi geçmişe yazılır', () => {
  const prev = tree({ 'dist/assets/index-A.js': 'a', 'dist/assets/Lazy-A.js': 'la', 'dist/assets/vendor-1.js': 'v', 'dist/index.html': '<html>' })
  const own = tree({ 'index-B.js': 'b', 'vendor-1.js': 'v' })
  const out = outDir()
  const r = carry({ previous: prev, own, out, release: '20.167.0', previousRelease: '20.166.0' })
  assert.deepEqual(r.carry, ['Lazy-A.js', 'index-A.js'])
  assert.equal(r.own, 1)                                                // ortak ad yeni derlemeden gelir
  assert.equal(readFileSync(join(out, 'assets', 'index-A.js'), 'utf8'), 'a')
  assert.deepEqual(history(out).map((e) => e.release), ['20.167.0', '20.166.0'])
  assert.deepEqual(checkCarry({ out, previous: checkInputs(prev), expectPrevious: true }).errors, [])
})

test('SON K SÜRÜM: tek önceki imajla K sürüm geriye gidilir, K+1. sürüm düşer (büyüyen yığın asla)', () => {
  // v1 → v2 → … → v7, her sürüm kendi index-vN.js'sini üretir; K = 3
  let prevImage = tree({ 'NO_PREVIOUS_RELEASE': '' })
  let last
  for (let v = 1; v <= 7; v++) {
    const own = tree({ [`index-v${v}.js`]: `v${v}`, 'vendor-1.js': 'v' })
    const out = outDir()
    last = carry({ previous: prevImage, own, out, release: `v${v}`, releases: 3 })
    assert.deepEqual(checkCarry({ out, previous: checkInputs(prevImage), expectPrevious: v > 1 }).errors, [], `v${v}`)
    if (v === 7) {
      assert.deepEqual(last.carry, ['index-v4.js', 'index-v5.js', 'index-v6.js'])
      assert.deepEqual(history(out).map((e) => e.release), ['v7', 'v6', 'v5', 'v4'])
    }
    prevImage = imageFrom(out, own)
  }
  assert.deepEqual(last.included, ['v6', 'v5', 'v4'])
})

test('yalnız arka uç değişen sürüm: hiçbir şey taşınmaz ama GEÇMİŞ taşınır — sonraki sürüm yine eski sürümün dosyalarını taşır', () => {
  const v1own = tree({ 'index-v1.js': '1' })
  const out1 = outDir()
  carry({ previous: tree({ 'x': '' }), own: v1own, out: out1, release: 'v1' })
  const img1 = imageFrom(out1, v1own)
  const v2own = tree({ 'index-v2.js': '2' })
  const out2 = outDir()
  carry({ previous: img1, own: v2own, out: out2, release: 'v2' })
  const img2 = imageFrom(out2, v2own)
  // v3 = v2 ile AYNI arayüz paketi (yalnız arka uç değişti)
  const out3 = outDir()
  const r3 = carry({ previous: img2, own: v2own, out: out3, release: 'v3' })
  assert.deepEqual(r3.carry, ['index-v1.js'])                           // v2'nin dosyaları zaten bu derlemede
  const img3 = imageFrom(out3, v2own)
  const out4 = outDir()
  const r4 = carry({ previous: img3, own: v2own, out: out4, release: 'v4' })
  assert.deepEqual(r4.carry, ['index-v1.js'], 'prod v1 → v4 de korunur')
  // Aynı paket + taşınacak bir şey kalmadığında denetim HATA değil not verir (D-1)
  const out5 = outDir()
  const img4 = imageFrom(out4, v2own)
  const r5 = carry({ previous: img4, own: tree({ 'index-v1.js': '1', 'index-v2.js': '2' }), out: out5, release: 'v5' })
  assert.deepEqual(r5.carry, [])
  const c5 = checkCarry({ out: out5, previous: checkInputs(img4), expectPrevious: true })
  assert.deepEqual(c5.errors, [])
  assert.match(c5.notices.join(' '), /değişmemiş/)
})

test('sınır: en yeni sürüm sığmazsa hepsi, sonrakiler sığmazsa yalnız onlar bırakılır (en eski önce düşer)', () => {
  const big = Array.from({ length: 30 }, (_, i) => `n-${i}.js`)
  const old = Array.from({ length: 30 }, (_, i) => `o-${i}.js`)
  const prev = tree({})
  mkdirSync(join(prev, 'dist', 'assets'), { recursive: true })
  for (const n of [...big, ...old]) writeFileSync(join(prev, 'dist', 'assets', n), 'x')
  const plan = planCarry({
    prevAssetsDir: join(prev, 'dist', 'assets'),
    history: [{ release: 'v9', files: big }, { release: 'v8', files: old }],
    ownNames: ['own.js'],
    maxFiles: 50,                                                         // gerçek sınır 5000 — testte küçültülür
  })
  assert.equal(plan.reason, 'cap')
  assert.deepEqual(plan.included, ['v9'])
  assert.deepEqual(plan.dropped, ['v8'])
  assert.equal(plan.carry.length, 30)
  const bytes = planCarry({ prevAssetsDir: join(prev, 'dist', 'assets'), history: [{ release: 'v9', files: big }], ownNames: [], maxBytes: 10 })
  assert.deepEqual([bytes.reason, bytes.carry.length], ['cap', 0])        // bayt sınırı da aynı kural
  const none = planCarry({ prevAssetsDir: join(prev, 'dist', 'assets'), history: [{ release: 'x', files: Array.from({ length: MAX_FILES + 5 }, (_, i) => `f-${i}.js`) }], ownNames: [] })
  assert.equal(none.reason, 'cap')
  assert.deepEqual(none.carry, [])
})

test('listede olup diskte olmayan / dizin / sembolik bağ olan ad atlanır; geçersiz adlar (yol, gizli) hiç denenmez', () => {
  const prev = tree({
    'dist/assets/ok-1.js': 'x', 'dist/assets/sub/inner.js': 'y',
    'own-assets.txt': ['ok-1.js', 'gone-2.js', 'sub', '../etc/passwd', '.env', 'a/b.js', '', 'ok-1.js'].join('\n'),
  })
  let symlinked = false
  try { symlinkSync(join(prev, 'dist/assets/ok-1.js'), join(prev, 'dist/assets/link-3.js')); symlinked = true } catch { /* Windows: yetki yok */ }
  if (symlinked) writeFileSync(join(prev, 'own-assets.txt'), readFileSync(join(prev, 'own-assets.txt'), 'utf8') + '\nlink-3.js\n')
  const r = carry({ previous: prev, own: tree({ 'new.js': 'n' }), out: outDir() })
  assert.deepEqual(r.carry, ['ok-1.js'])
  assert.deepEqual(r.invalid, ['../etc/passwd', '.env', 'a/b.js'])
  assert.equal(r.missing, symlinked ? 3 : 2)                              // gone-2, sub (dizin) [, link-3]
})

test('bozuk asset-history.json bir sonraki kaynağa düşer; readList / listFiles / NAME', () => {
  const prev = tree({ 'asset-history.json': '{bozuk', 'own-assets.txt': 'a.js\n', 'dist/assets/a.js': 'a' })
  assert.deepEqual(readHistory(prev, 'v0'), [{ release: 'v0', files: ['a.js'] }])
  assert.equal(readHistory(tree({}), 'v0'), null)
  const dir = tree({ 'l.txt': 'a.js\r\nb.js\r\n\r\na.js\r\n' })
  assert.deepEqual(readList(join(dir, 'l.txt')), ['a.js', 'b.js'])
  assert.equal(readList(join(dir, 'yok.txt')), null)
  assert.deepEqual(listFiles(join(dir, 'yok')), [])
  assert.ok(NAME.test('index-CBxJA6rJ.js') && NAME.test('Roboto-Regular-abc.ttf') && !NAME.test('../x') && !NAME.test('.hidden'))
  assert.equal(DEFAULT_RELEASES, 5)
})

test('denetim yanlış çıktıyı yakalar: eksik taşıma, kendi adının taşınması, önceki imaj verilip okunmaması', () => {
  const prev = tree({ 'dist/assets/index-A.js': 'a', 'dist/assets/Lazy-A.js': 'la' })
  const own = tree({ 'index-B.js': 'b' })
  const out = outDir()
  carry({ previous: prev, own, out, release: 'v2', previousRelease: 'v1' })
  writeFileSync(join(out, 'carried-assets.txt'), 'Lazy-A.js\n')                       // index-A eksik
  const c = checkCarry({ out, previous: checkInputs(prev), expectPrevious: true })
  assert.ok(c.errors.some((e) => /beklenenden farklı/.test(e)), c.errors.join('; '))
  const none = outDir()
  carry({ previous: tree({ 'x': '' }), own, out: none, release: 'v2' })
  assert.ok(checkCarry({ out: none, previous: checkInputs(tree({})), expectPrevious: true }).errors.some((e) => /okunamadı/.test(e)))
})

test('yeni derlemenin varlık dizini boşsa derleme DURUR (yanlış bağlam — sessizce boş imaj üretilmez)', () => {
  assert.throws(() => carry({ previous: tree({}), own: tree({}), out: outDir() }), /boş ya da yok/)
})

test('main: eksik argüman → 2; normal çalışma özet yazar, --releases sınırlanır', () => {
  const out = []
  assert.equal(main([], (m) => out.push(m)), 2)
  const prev = tree({ 'dist/assets/old-1.js': 'o' })
  const o = outDir()
  assert.equal(main(['--previous', prev, '--own', tree({ 'new-1.js': 'n' }), '--out', o, '--release', 'v2', '--previous-release', 'v1', '--releases', '99'], (m) => out.push(m)), 0)
  assert.match(out.at(-1), /1 dosya/)
  assert.equal(JSON.parse(readFileSync(join(o, 'carry-report.json'), 'utf8')).releases, 10)
})
