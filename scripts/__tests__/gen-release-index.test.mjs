// node --test scripts/__tests__/  — üreteç saf fonksiyonları (git gerekmez)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  parseSubject, inferBump, scrub, sortReleases, foldPatches, buildRelease, indexDiffers, wrap, serialize, MAX_CHANGES,
} from '../gen-release-index.mjs'
import { promote, fixLinks, hasContent, unreleasedBlock, REPO_URL } from '../promote-changelog.mjs'

test('parseSubject: conventional başlık → type/scope/breaking; uymayan → other', () => {
  assert.deepEqual(parseSubject('feat(settings): mudur Ayarlar'), { type: 'feat', scope: 'settings', breaking: false, subject: 'mudur Ayarlar' })
  assert.deepEqual(parseSubject('fix!: kir'), { type: 'fix', scope: null, breaking: true, subject: 'kir' })
  assert.equal(parseSubject('Merge branch x').type, 'other')
  assert.equal(parseSubject('').subject, '')
})

test('inferBump: sürüm farkından; commit metnine bakmaz', () => {
  assert.equal(inferBump('20.0.0', '19.95.0'), 'major')
  assert.equal(inferBump('20.53.0', '20.52.1'), 'minor')
  assert.equal(inferBump('20.53.2', '20.53.1'), 'patch')
  assert.equal(inferBump('1.0.0', null), 'initial')
})

test('scrub: yasak token, e-posta, URL temizlenir; uzun başlık kısalır', () => {
  const bad = 'akb' + 'ank'
  assert.equal(scrub(`www.${bad}.com hedefi`), 'www.[redacted].com hedefi')
  assert.equal(scrub('AKB' + 'ANK'), '[redacted]')
  assert.equal(scrub('ops@example.com yazdı'), '[email] yazdı')
  assert.equal(scrub('bkz https://example.com/x?y=1 sonra'), 'bkz [url] sonra')
  assert.ok(scrub('a'.repeat(500)).length <= 200)
})

test('sortReleases: azalan semver (20.10.0 > 20.9.9)', () => {
  const s = sortReleases([{ version: '20.9.9' }, { version: '20.10.0' }, { version: '1.0.0' }])
  assert.deepEqual(s.map((r) => r.version), ['20.10.0', '20.9.9', '1.0.0'])
})

test('foldPatches: yamalar taban minor altına katlanır; dağıtılmış yama açık kalır; hepsi yamaysa hepsi gösterilir', () => {
  const rows = [
    { version: '20.53.2', bump: 'patch' }, { version: '20.53.1', bump: 'patch' }, { version: '20.53.0', bump: 'minor' },
    { version: '20.52.1', bump: 'patch' }, { version: '20.52.0', bump: 'minor' },
  ]
  const out = foldPatches(rows, new Set(['20.52.1']))
  assert.deepEqual(out.map((r) => [r.version, r.collapsedPatches]), [
    ['20.53.0', ['20.53.2', '20.53.1']], ['20.52.1', []], ['20.52.0', []],
  ])
  const onlyPatches = foldPatches([{ version: '1.0.2', bump: 'patch' }, { version: '1.0.1', bump: 'patch' }])
  assert.equal(onlyPatches.length, 2)
})

test('buildRelease: sayaçlar, tavan ve truncated/omitted', () => {
  const changes = Array.from({ length: MAX_CHANGES + 5 }, (_, i) => ({ type: i % 2 ? 'feat' : 'fix', scope: null, breaking: false, sha: 'x', subject: 's' + i }))
  const r = buildRelease({ version: '2.0.0', tag: 'v2.0.0', releasedAt: '2026-01-01T00:00:00Z', commit: 'c', prevVersion: '1.0.0', changes, breakingAny: true })
  assert.equal(r.bump, 'major'); assert.equal(r.breaking, true)
  assert.equal(r.changes.length, MAX_CHANGES); assert.equal(r.truncated, true); assert.equal(r.omitted, 5)
  assert.equal(r.counts.feat + r.counts.fix, MAX_CHANGES + 5)
})

test('indexDiffers: generatedAt/count farkı sayılmaz, sürüm farkı sayılır', () => {
  const a = wrap([{ version: '1.0.0' }], '2026-01-01T00:00:00Z')
  const b = wrap([{ version: '1.0.0' }], '2026-02-02T00:00:00Z')
  assert.equal(indexDiffers(a, b), false)
  assert.equal(indexDiffers(a, wrap([{ version: '1.0.1' }])), true)
  assert.ok(serialize(a).endsWith('\n'))
})

test('promote: dolu Unreleased tarihlenir, boşsa dokunulmaz, aynı sürüm ikinci kez no-op, CRLF korunur', () => {
  const src = '# Changelog\r\n\r\n## [Unreleased]\r\n\r\n### Added\r\n- yeni şey\r\n\r\n## [1.0.0] — 2026-05-16\r\n- ilk\r\n\r\n[1.0.0]: https://github.com/your-org/cert-monitor/releases/tag/v1.0.0\r\n'
  const r = promote(src, '1.1.0', '2026-09-11')
  assert.equal(r.changed, true)
  assert.ok(r.text.includes('## [1.1.0] — 2026-09-11\r\n\r\n### Added\r\n- yeni şey'))
  assert.ok(r.text.includes(`[1.1.0]: ${REPO_URL}/releases/tag/v1.1.0`))
  assert.ok(r.text.includes(`[Unreleased]: ${REPO_URL}/compare/v1.1.0...HEAD`))
  assert.ok(!r.text.includes('your-org/cert-monitor'))
  const again = promote(r.text, '1.1.0', '2026-09-11')
  assert.equal(again.changed, false)
  const empty = promote('## [Unreleased]\n\n### Added\n\n## [1.0.0] — 2026-05-16\n- ilk\n', '1.0.1', '2026-09-11')
  assert.equal(empty.changed, false)
  assert.ok(!empty.text.includes('## [1.0.1]'))
})

test('hasContent/unreleasedBlock: yalnız alt başlık ve ayraç içerik değildir', () => {
  assert.equal(hasContent(['', '### Added', '---', '']), false)
  assert.equal(hasContent(['- madde']), true)
  const b = unreleasedBlock(['# x', '## [Unreleased]', '- a', '## [1.0.0]'])
  assert.deepEqual(b.body, ['- a'])
  assert.ok(fixLinks('## [2.0.0] — x\n\n## [1.0.0] — y\n', '2.0.0').includes('[1.0.0]: ' + REPO_URL))
})
