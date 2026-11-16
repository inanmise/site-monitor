// node --test scripts/__tests__/ci-gates.test.mjs — CI güvenlik kapıları (2026-11-17, inceleme bulguları):
//   • OWASP Dependency-Check sınıflandırması: NVD güncellemesi düşüp tarama ESKİ veriyle bulgusuz biterse `clean` DEĞİL
//     `stale_clean` ve kapı KIRMIZI (önceden yeşil kalıyordu).
//   • Trivy kapısı: Trivy'nin kendi hatası (adım başarısız / rapor yok) bulgudan AYRI anlatılır; ikisi de durdurur.
//   • release.yml imajı BİR KEZ derler; taranan imaj itilen imajdır (ikinci derleme yok).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as depcheck from '../depcheck-classify.mjs'
import * as trivy from '../trivy-gate.mjs'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const workflow = (name) => readFileSync(join(ROOT, '.github', 'workflows', name), 'utf8')

const NVD_FAIL = '[WARNING] Unable to update 1 or more Cached Web DataSource, using local Data instead. Results may not include recent vulnerabilities.\n'
const NVD_ERR = '[ERROR] Error updating the NVD Data\n'
const FINDINGS = "[ERROR] One or more dependencies were identified with vulnerabilities that have a CVSS score greater than or equal to '7.0':\n"

test('depcheck: güncelleme başarılı + rc 0 → clean (önbellek kaydedilir); bulgu → findings; başka hata → error', () => {
  assert.deepEqual(depcheck.classify({ log: '[INFO] BUILD SUCCESS\n', rc: 0 }), { result: 'clean', stale: false, dbOk: true })
  assert.deepEqual(depcheck.classify({ log: FINDINGS, rc: 1 }), { result: 'findings', stale: false, dbOk: true })
  assert.deepEqual(depcheck.classify({ log: '[ERROR] Failed to execute goal\n', rc: 1 }), { result: 'error', stale: false, dbOk: false })
})

test('depcheck: NVD güncellemesi düştü ama ESKİ veriyle tarama bulgusuz bitti → stale_clean, önbellek KAYDEDİLMEZ, kapı KIRMIZI', () => {
  for (const log of [NVD_FAIL, NVD_ERR, NVD_ERR + NVD_FAIL]) {
    const c = depcheck.classify({ log, rc: 0 })
    assert.deepEqual(c, { result: 'stale_clean', stale: true, dbOk: false }, log)
    const g = depcheck.gate(c.result)
    assert.equal(g.pass, false)
    assert.match(g.message, /ESKİ veri/)
  }
})

test('depcheck: güncelleme düştü + bulgu → findings (bayat, önbellek yok); güncelleme düştü + tarama bitmedi → update_failed', () => {
  assert.deepEqual(depcheck.classify({ log: NVD_ERR + FINDINGS, rc: 1 }), { result: 'findings', stale: true, dbOk: false })
  assert.deepEqual(depcheck.classify({ log: NVD_ERR + 'NoDataException: No documents exist\n', rc: 1 }),
    { result: 'update_failed', stale: false, dbOk: false })
  assert.deepEqual(depcheck.classify({ log: '[ERROR] Unable to continue dependency-check analysis.\n', rc: 1 }),
    { result: 'update_failed', stale: false, dbOk: false })
})

test('depcheck: kapı yalnız clean ile geçer; bilinmeyen / boş sonuç da kırmızı', () => {
  for (const r of depcheck.RESULTS) assert.equal(depcheck.gate(r).pass, r === 'clean', r)
  assert.equal(depcheck.gate('').pass, false)
  assert.equal(depcheck.gate(undefined).pass, false)
  assert.equal(depcheck.gate('stale').pass, false)
  for (const r of depcheck.RESULTS) assert.ok(depcheck.summaryLine(r).length > 0)
  assert.match(depcheck.summaryLine('stale_clean'), /GEÇMEZ/)
})

test('depcheck CLI: --log/--rc çıktısı GITHUB_OUTPUT biçiminde; --gate çıkış kodu', () => {
  const dir = mkdtempSync(join(tmpdir(), 'depcheck-'))
  const log = join(dir, 'depcheck.log')
  writeFileSync(log, NVD_FAIL + '[INFO] BUILD SUCCESS\n')
  let text = ''
  const out = { write: (s) => { text += s } }
  assert.equal(depcheck.main(['--log', log, '--rc', '0'], out), 0)
  assert.equal(text, 'result=stale_clean\nstale=true\ndb_ok=false\n')
  text = ''
  assert.equal(depcheck.main(['--gate', 'stale_clean'], out), 1)
  assert.match(text, /^::error::/)
  assert.equal(depcheck.main(['--gate', 'clean'], out), 0)
  // Okunamayan günlük + rc yok → error (temiz sayılmaz)
  text = ''
  depcheck.main(['--log', join(dir, 'yok.log')], out)
  assert.match(text, /^result=error\n/)
})

test('dependency-check.yml: sınıflandırma ve kapı betikten gelir (satır içi "clean" kararı yok)', () => {
  const y = workflow('dependency-check.yml')
  assert.match(y, /depcheck-classify\.mjs --log "\$RUNNER_TEMP\/depcheck\.log" --rc "\$rc"/)
  assert.match(y, /depcheck-classify\.mjs --gate "\$RESULT"/)
  assert.match(y, /depcheck-classify\.mjs --summary "\$RESULT"/)
  assert.doesNotMatch(y, /echo "result=clean"/)
  assert.match(y, /steps\.depcheck\.outputs\.db_ok == 'true'/)
})

// ── Trivy ──────────────────────────────────────────────────────────────────────────────────────────

const report = (results) => JSON.stringify({ SchemaVersion: 2, ArtifactName: 'site-monitor:release-scan', Results: results })
const VULN = {
  Target: 'usr/bin/k6', Type: 'gobinary',
  Vulnerabilities: [{ VulnerabilityID: 'CVE-2026-0001', PkgName: 'golang.org/x/net', Severity: 'HIGH', InstalledVersion: 'v0.58.0', FixedVersion: '0.62.0' }],
}

test('trivy: adım başarılı + bulgu yok → clean; Results yok (Trivy boşken alanı atlar) da clean', () => {
  assert.equal(trivy.classify({ outcome: 'success', reportText: report([{ Target: 'x', Class: 'os-pkgs' }]) }).result, 'clean')
  assert.equal(trivy.classify({ outcome: 'success', reportText: JSON.stringify({ SchemaVersion: 2 }) }).result, 'clean')
})

test('trivy: bulgu (açık + gizli bilgi) → findings, tabloda kimlik / paket / düzeltilmiş sürüm', () => {
  const c = trivy.classify({
    outcome: 'success',
    reportText: report([VULN, { Target: 'app/x.env', Secrets: [{ RuleID: 'aws-access-key-id', Title: 'AWS', Severity: 'CRITICAL' }] }]),
  })
  assert.equal(c.result, 'findings')
  assert.equal(c.findings.length, 2)
  const t = trivy.findingsTable(c.findings, 50)
  assert.match(t, /CVE-2026-0001/)
  assert.match(t, /golang\.org\/x\/net/)
  assert.match(t, /0\.62\.0/)
  assert.match(t, /aws-access-key-id/)
  const r = trivy.report(c, 'release')
  assert.match(r.annotation, /^::error::Trivy: 2 düzeltmesi olan HIGH\/CRITICAL bulgu/)
  assert.match(r.summary, /Yayın DURDU/)
  assert.doesNotMatch(r.summary, /tarama hatası/)
})

test('trivy: adım başarısız (Trivy KENDİSİ çalışamadı) → error, BULGU OLARAK ANLATILMAZ — rapor bulgulu olsa bile', () => {
  for (const outcome of ['failure', 'cancelled', '', undefined]) {
    const c = trivy.classify({ outcome, reportText: report([VULN]) })
    assert.equal(c.result, 'error', String(outcome))
    const r = trivy.report(c, 'release')
    assert.match(r.annotation, /TARAMASI ÇALIŞAMADI \(bulgu değil, tarama hatası\)/)
    assert.doesNotMatch(r.annotation, /HIGH\/CRITICAL bulgu/)
    assert.match(r.summary, /bulgu DEĞİL/)
  }
})

test('trivy: rapor yok / boş / bozuk / beklenmeyen biçim → error (bulgu yok SANILMAZ)', () => {
  for (const text of [null, '', '   ', '{bozuk', '[]', JSON.stringify({ Results: [] }), JSON.stringify({ SchemaVersion: 2, Results: {} })]) {
    assert.equal(trivy.classify({ outcome: 'success', reportText: text }).result, 'error', String(text))
  }
})

test('trivy CLI: çıkış kodu yalnız clean ile 0; özet ve result çıktısı yazılır; tablo hücresi | kaçışlı', () => {
  const dir = mkdtempSync(join(tmpdir(), 'trivy-'))
  const summary = join(dir, 'summary.md')
  const output = join(dir, 'output.txt')
  writeFileSync(summary, '')
  writeFileSync(output, '')
  const env = { GITHUB_STEP_SUMMARY: summary, GITHUB_OUTPUT: output }
  const file = join(dir, 'trivy.json')
  let text = ''
  const out = { write: (s) => { text += s } }

  writeFileSync(file, report([]))
  assert.equal(trivy.main(['--report', file, '--outcome', 'success', '--context', 'release'], { out, env }), 0)

  writeFileSync(file, report([{ ...VULN, Target: 'a|b' }]))
  assert.equal(trivy.main(['--report', file, '--outcome', 'success', '--context', 'release'], { out, env }), 1)
  assert.match(text, /a\\\|b/)

  text = ''
  assert.equal(trivy.main(['--report', join(dir, 'yok.json'), '--outcome', 'failure', '--context', 'branch'], { out, env }), 1)
  assert.match(text, /::error::Trivy TARAMASI ÇALIŞAMADI/)
  assert.match(text, /İmaj itildi/)
  const s = readFileSync(summary, 'utf8')
  assert.match(s, /bulgu yok/)
  assert.match(s, /1 düzeltmesi olan HIGH\/CRITICAL bulgu/)
  assert.match(s, /taraması çalışamadı/)
  assert.deepEqual(readFileSync(output, 'utf8').trim().split('\n'), ['result=clean', 'result=findings', 'result=error'])
})

test('release.yml: tek derleme — taranan imaj itilen imajdır; tarama hatası ayrı kapıda; kapıdan önce hiçbir şey itilmez', () => {
  const y = workflow('release.yml')
  const builds = y.match(/uses: docker\/build-push-action@/g) || []
  assert.equal(builds.length, 1, 'release.yml imajı BİR KEZ derlemeli')
  assert.doesNotMatch(y, /push: true/, 'derleme yerel kalır; itme taranan imajın docker push\'udur')
  const iBuild = y.indexOf('name: Build Docker image (local, scanned before push)')
  const iScan = y.indexOf('name: Trivy image scan (release gate)')
  const iGate = y.indexOf('name: Trivy gate (release)')
  const iPush = y.indexOf('name: Push the scanned image')
  const iHelm = y.indexOf('name: Package and push Helm chart')
  const iTag = y.indexOf('name: Create and push git tag')
  assert.ok(iBuild > 0 && iBuild < iScan && iScan < iGate && iGate < iPush && iPush < iHelm && iHelm < iTag)
  const scan = y.slice(iScan, iGate)
  assert.match(scan, /continue-on-error: true/)
  assert.match(scan, /exit-code: '0'/)
  assert.match(scan, /format: json/)
  assert.match(scan, /image-ref: site-monitor:release-scan/)
  assert.match(y.slice(iGate, iPush), /trivy-gate\.mjs --report trivy-results\.json --outcome "\$OUTCOME" --context release/)
  const push = y.slice(iPush, iHelm)
  assert.match(push, /docker image inspect --format '\{\{\.Id\}\}' site-monitor:release-scan/)
  assert.match(push, /timeout 900 docker push/)
  // Tek derleme hem yerel tarama adını hem iki ghcr etiketini taşır.
  const build = y.slice(iBuild, iScan)
  assert.match(build, /site-monitor:release-scan/)
  assert.match(build, /site-monitor:\$\{\{ steps\.ver\.outputs\.tag \}\}/)
  assert.match(build, /site-monitor:latest/)
  assert.match(build, /load: true/)
})

test('docker-build.yml: Trivy JSON + kapı betiği (bulgu ile tarama hatası ayrı)', () => {
  const y = workflow('docker-build.yml')
  const iScan = y.indexOf('name: Trivy image scan')
  const iGate = y.indexOf('name: Trivy gate')
  assert.ok(iScan > 0 && iGate > iScan)
  const scan = y.slice(iScan, iGate)
  assert.match(scan, /exit-code: '0'/)
  assert.match(scan, /format: json/)
  assert.match(y.slice(iGate), /trivy-gate\.mjs --report trivy-results\.json --outcome "\$OUTCOME" --context branch/)
  assert.doesNotMatch(y, /if: steps\.trivy\.outcome == 'failure'/)
})
