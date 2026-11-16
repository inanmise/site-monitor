// node --test scripts/__tests__/image-boot-check.test.mjs — imaj açılış denetimi adım 9 (2026-11-17, inceleme bulgusu):
// `docker exec` çağrıları zaman aşımıyla sınırlı; zaman aşımı ve izin reddi DIŞINDAKİ bir docker hatası (konteyner durmuş)
// "okunamıyor = sertleştirme çalışıyor" diye GEÇMEZ. Sahte `docker` / `curl` ile koşar (ağ, gerçek docker yok).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { delimiter, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), '..', 'image-boot-check.sh')

// Sahte docker: davranış STUB_MODE ile seçilir. `exec sleep` — zaman aşımı doğrudan bekleyen süreci öldürsün.
const DOCKER_STUB = `#!/usr/bin/env bash
mode="\${STUB_MODE:-ok}"
cmd="$1"; shift
if [ "$cmd" = inspect ]; then
  case "$mode" in
    hang-inspect) exec sleep 20 ;;
    none) echo "Error: No such object: $1" >&2; exit 1 ;;
    *) echo '[{}]'; exit 0 ;;
  esac
fi
if [ "$cmd" = exec ]; then
  shift            # konteyner adı
  case "$mode" in
    hang-exec) exec sleep 20 ;;
    notrunning) echo "Error response from daemon: container abc is not running" >&2; exit 1 ;;
  esac
  if [ "$1" = cat ]; then
    if [ "$mode" = readable ]; then echo "SECRET=1"; exit 0; fi
    echo "cat: can't open '$2': Permission denied" >&2; exit 1
  fi
  if [ "$2" = version ]; then
    [ "$mode" = noversion ] && { echo "exec format error" >&2; exit 126; }
    echo "k6 v2.4.0"; exit 0
  fi
fi
echo "beklenmeyen docker çağrısı: $cmd $*" >&2; exit 2
`
const CURL_STUB = '#!/usr/bin/env bash\nexit 7\n'

function run(mode) {
  const dir = mkdtempSync(join(tmpdir(), 'bootcheck-'))
  const bin = join(dir, 'bin')
  const work = join(dir, 'work')
  for (const d of [bin, work]) mkdirSync(d, { recursive: true })
  writeFileSync(join(bin, 'docker'), DOCKER_STUB)
  writeFileSync(join(bin, 'curl'), CURL_STUB)
  chmodSync(join(bin, 'docker'), 0o755)
  chmodSync(join(bin, 'curl'), 0o755)
  // Aday ikili denetimi de koşsun (betik Dockerfile'ı çalışma dizininden okur).
  writeFileSync(join(work, 'Dockerfile'), 'ARG K6_CANDIDATE_VERSION=2.4.0\n')
  const log = join(work, 'boot.log')
  writeFileSync(log, '2026-11-17 10:00:00.000  INFO Şema yamaları: 1 uygulandı\n'
    + '2026-11-17 10:00:01.000  INFO İşlem sertleştirmesi uygulandı (JVM dump-edilemez)\n')
  const started = Date.now()
  const r = spawnSync('bash', [SCRIPT, 'http://127.0.0.1:9', log], {
    cwd: work,
    encoding: 'utf8',
    timeout: 90_000,
    env: { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}`, STUB_MODE: mode, SM_EXEC_TIMEOUT: '1', SM_CONTAINER: 'sm' },
  })
  const out = `${r.stdout ?? ''}${r.stderr ?? ''}`
  // Yalnız adım 9 (sertleştirme / docker) satırları — önceki adımlar sahte curl yüzünden zaten hata verir.
  const step9 = out.split('\n').filter((l) => /sertleştirme|konteynerde|docker inspect|konteyner 'sm'/.test(l))
  return { out, step9, errors: step9.filter((l) => l.startsWith('::error::')), ms: Date.now() - started, signal: r.signal }
}

test('izin reddi → dört okunamazlık / çalışma denetimi geçer, adım 9 hatası yok', () => {
  const r = run('ok')
  assert.equal(r.signal, null, r.out)
  assert.deepEqual(r.errors, [], r.step9.join('\n'))
  assert.ok(r.step9.some((l) => l.includes('/proc/1/environ okunamıyor (izin reddi)')), r.out)
  assert.ok(r.step9.some((l) => l.includes('/usr/bin/k6 okunamıyor')), r.out)
  assert.ok(r.step9.some((l) => l.includes('/opt/k6-candidate/k6 okunamıyor')), r.out)
})

test('takılan docker exec zaman aşımıyla kesilir ve AÇIK HATA olur (geçmez, saatlerce beklemez)', () => {
  const r = run('hang-exec')
  assert.equal(r.signal, null, 'betik kendi zaman aşımıyla bitmeliydi')
  assert.ok(r.ms < 60_000, `süre ${r.ms} ms`)
  assert.ok(r.errors.some((l) => l.includes("'cat /proc/1/environ' 1 sn'de bitmedi")), r.step9.join('\n'))
  assert.ok(r.errors.some((l) => l.includes("'/usr/bin/k6 version' 1 sn'de bitmedi")), r.step9.join('\n'))
  assert.ok(!r.step9.some((l) => l.includes('okunamıyor')), 'zaman aşımı "okunamıyor" sayılmamalı')
})

test('docker hatası (konteyner durmuş) izin reddi sayılmaz — denetim yapılamadı hatası', () => {
  const r = run('notrunning')
  assert.ok(r.errors.some((l) => l.includes('izin reddi dışında bir hatayla bitti') && l.includes('not running')), r.step9.join('\n'))
  assert.ok(!r.step9.some((l) => l.includes('okunamıyor')), r.step9.join('\n'))
})

test('okunabilen dosya ve çalışmayan ikili hata; takılan docker inspect hata (atlanmaz); konteyner yoksa uyarıyla atlanır', () => {
  const readable = run('readable')
  assert.ok(readable.errors.some((l) => l.includes('/proc/1/environ') && l.includes('OKUYABİLİYOR')), readable.step9.join('\n'))
  const noversion = run('noversion')
  assert.ok(noversion.errors.some((l) => l.includes('/usr/bin/k6 version çalışmıyor')), noversion.step9.join('\n'))
  const hang = run('hang-inspect')
  assert.ok(hang.errors.some((l) => l.includes('docker inspect sm 1 sn\'de bitmedi')), hang.step9.join('\n'))
  const none = run('none')
  assert.deepEqual(none.errors, [], none.step9.join('\n'))
  assert.ok(none.out.includes("::warning::konteyner 'sm' bulunamadı"), none.out)
})
