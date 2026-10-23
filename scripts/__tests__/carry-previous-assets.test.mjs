// node --test scripts/__tests__/carry-previous-assets.test.mjs — önceki sürümün varlıklarını taşıma (çok pod R2b · MP-47)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, existsSync, symlinkSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { carry, planCarry, readList, listFiles, main, MAX_FILES, NAME } from '../carry-previous-assets.mjs'

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

test('önceki sürüm yok: hiçbir şey taşınmaz, bu derlemenin kendi listesi yazılır (ilk derleme bugünkü gibi)', () => {
  const prev = tree({ 'NO_PREVIOUS_RELEASE': '' })
  const own = tree({ 'index-B.js': 'b', 'vendor-1.js': 'v' })
  const out = join(tree({}), 'out')
  const r = carry({ previous: prev, own, out })
  assert.equal(r.reason, 'no-previous')
  assert.deepEqual(readdirSync(join(out, 'assets')), [])
  assert.deepEqual(lines(join(out, 'own-assets.txt')), ['index-B.js', 'vendor-1.js'])
  assert.equal(readFileSync(join(out, 'carried-assets.txt'), 'utf8'), '')
})

test('listesiz (bu değişiklikten önceki) imaj: assets dizininin tamamı o sürümündür — yenide olmayanlar taşınır', () => {
  const prev = tree({ 'dist/assets/index-A.js': 'a', 'dist/assets/Lazy-A.js': 'la', 'dist/assets/vendor-1.js': 'v', 'dist/index.html': '<html>' })
  const own = tree({ 'index-B.js': 'b', 'vendor-1.js': 'v' })
  const out = join(tree({}), 'out')
  const r = carry({ previous: prev, own, out })
  assert.equal(r.legacy, true)
  assert.deepEqual(r.carry, ['Lazy-A.js', 'index-A.js'])
  assert.equal(r.own, 1)                                                // ortak ad yeni derlemeden gelir
  assert.deepEqual(readdirSync(join(out, 'assets')).sort(), ['Lazy-A.js', 'index-A.js'])
  assert.equal(readFileSync(join(out, 'assets', 'index-A.js'), 'utf8'), 'a')
  assert.deepEqual(lines(join(out, 'carried-assets.txt')), ['Lazy-A.js', 'index-A.js'])
})

test('SINIRLI: listeli imajda yalnız o sürümün KENDİ varlıkları gelir — onun taşıdıkları (N−2) gelmez', () => {
  // N−1 imajı: kendi varlıkları index-B / Lazy-B, ayrıca N−2'den taşıdığı index-A diskte duruyor
  const prev = tree({
    'dist/assets/index-A.js': 'a', 'dist/assets/index-B.js': 'b', 'dist/assets/Lazy-B.js': 'lb',
    'own-assets.txt': 'index-B.js\nLazy-B.js\n', 'carried-assets.txt': 'index-A.js\n',
  })
  const own = tree({ 'index-C.js': 'c' })
  const out = join(tree({}), 'out')
  const r = carry({ previous: prev, own, out })
  assert.equal(r.legacy, false)
  assert.deepEqual(r.carry, ['index-B.js', 'Lazy-B.js'])
  assert.ok(!existsSync(join(out, 'assets', 'index-A.js')), 'N−2 varlığı taşınmamalı')
  // Zincir: bu çıktı bir sonraki derlemenin "önceki"si olduğunda yine yalnız index-C taşınır
  const next = join(tree({}), 'next')
  const prev2 = tree({ 'dist/assets/index-B.js': 'b', 'dist/assets/Lazy-B.js': 'lb', 'dist/assets/index-C.js': 'c' })
  writeFileSync(join(prev2, 'own-assets.txt'), readFileSync(join(out, 'own-assets.txt')))
  const r2 = carry({ previous: prev2, own: tree({ 'index-D.js': 'd' }), out: next })
  assert.deepEqual(r2.carry, ['index-C.js'])
})

test('listede olup diskte olmayan / dizin / sembolik bağ olan ad atlanır; geçersiz adlar (yol, gizli) hiç denenmez', () => {
  const prev = tree({
    'dist/assets/ok-1.js': 'x', 'dist/assets/sub/inner.js': 'y',
    'own-assets.txt': ['ok-1.js', 'gone-2.js', 'sub', '../etc/passwd', '.env', 'a/b.js', '', 'ok-1.js'].join('\n'),
  })
  let symlinked = false
  try { symlinkSync(join(prev, 'dist/assets/ok-1.js'), join(prev, 'dist/assets/link-3.js')); symlinked = true } catch { /* Windows: yetki yok */ }
  if (symlinked) writeFileSync(join(prev, 'own-assets.txt'), readFileSync(join(prev, 'own-assets.txt'), 'utf8') + '\nlink-3.js\n')
  const r = carry({ previous: prev, own: tree({ 'new.js': 'n' }), out: join(tree({}), 'out') })
  assert.deepEqual(r.carry, ['ok-1.js'])
  assert.deepEqual(r.invalid, ['../etc/passwd', '.env', 'a/b.js'])
  assert.equal(r.missing, symlinked ? 3 : 2)                              // gone-2, sub (dizin) [, link-3]
})

test('sınır: aday sayısı MAX_FILES\'ı aşarsa HİÇBİR şey taşınmaz (büyüyen yığın asla)', () => {
  const names = Array.from({ length: MAX_FILES + 5 }, (_, i) => `f-${i}.js`)
  const plan = planCarry({ prevAssetsDir: tree({ 'x.js': 'x' }), prevList: names, ownNames: ['own.js'] })
  assert.equal(plan.reason, 'cap')
  assert.deepEqual(plan.carry, [])
})

test('readList: tekrar ve boş satırları atlar, CRLF okur, yoksa null; listFiles dizin yoksa boş', () => {
  const dir = tree({ 'l.txt': 'a.js\r\nb.js\r\n\r\na.js\r\n' })
  assert.deepEqual(readList(join(dir, 'l.txt')), ['a.js', 'b.js'])
  assert.equal(readList(join(dir, 'yok.txt')), null)
  assert.deepEqual(listFiles(join(dir, 'yok')), [])
  assert.ok(NAME.test('index-CBxJA6rJ.js') && NAME.test('Roboto-Regular-abc.ttf') && !NAME.test('../x') && !NAME.test('.hidden'))
})

test('yeni derlemenin varlık dizini boşsa derleme DURUR (yanlış bağlam — sessizce boş imaj üretilmez)', () => {
  assert.throws(() => carry({ previous: tree({}), own: tree({}), out: join(tree({}), 'o') }), /boş ya da yok/)
})

test('main: eksik argüman → 2; normal çalışma özet yazar', () => {
  const out = []
  assert.equal(main([], (m) => out.push(m)), 2)
  const prev = tree({ 'dist/assets/old-1.js': 'o' })
  assert.equal(main(['--previous', prev, '--own', tree({ 'new-1.js': 'n' }), '--out', join(tree({}), 'o')], (m) => out.push(m)), 0)
  assert.match(out.at(-1), /1 dosya/)
  rmSync(prev, { recursive: true, force: true })
})
