/**
 * Sentetik izleme KARTI modeli — saf yardımcılar (React yok; kart ve testleri paylaşır).
 *
 * <p>Satır alanları (GET /monitoring/scripted, `enrichScripted`): `status` PASS|FAIL|ERROR|TIMEOUT|NO_CHECKS|unknown,
 * `checks_passed` / `checks_failed` (k6 `checks` metriğinin geçen/kalan sayaçları — koşmamışsa null),
 * `checks_json` (check ADI başına `{name, passed}` listesi, grup içleri "grup › ad"), `duration_ms` (koşumun toplam
 * süresi), `http_req_avg_ms` (istek başına ortalama), `exit_code`, `error` (sunucu metni: ilk satır etiket, `:` ile
 * bitiyorsa asıl sebep ikinci satırda), `phases` (takılınan faz), `script` + `env` (hedef adresin kaynağı),
 * `script_version` (güncel) / `run_script_version` (son koşumun sürümü), `slow_response_enabled` + `slow_threshold_ms`,
 * `timeout_seconds`.
 *
 * <p>Listede OLMAYANLAR (kart uydurmaz): p95 (`http_req_p95_ms` veritabanında var, liste yanıtına konmuyor),
 * iterasyon süresi, eşik (threshold) ADLARI (yalnız çıkış kodu 99 "eşik aşıldı" bilgisi var), sürümün değişme zamanı.
 * k6 her zaman `--vus 1 --iterations 1` koşar — VU/iterasyon sabit olduğu için kartta gösterilmez.
 */

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

// Script'in İÇE AKTARDIĞI kütüphane adresleri hedef değildir.
const LIB_HOSTS = new Set(['jslib.k6.io', 'cdn.jsdelivr.net', 'unpkg.com', 'raw.githubusercontent.com'])
const URL_IN_TEXT = /https?:\/\/[^\s'"`<>)\]}]+/gi
const BROWSER_IMPORT = /from\s+['"]k6\/(?:experimental\/)?browser['"]/
// Adres sonundaki noktalama — sondan geriye tek geçiş (2026-10-09): eski `/[.,;:]+$/` ortasında uzun noktalama
// dizisi olan adreste O(N²) geri izliyordu. Sonuç aynı: en uzun noktalama soneki atılır.
const TRAILING_PUNCT = new Set(['.', ',', ';', ':'])
const stripTrailingPunct = (s) => {
  let e = s.length
  while (e > 0 && TRAILING_PUNCT.has(s[e - 1])) e--
  return e === s.length ? s : s.slice(0, e)
}

function hostOf(raw) {
  try {
    const u = new URL(raw)
    return u.host || null
  } catch {
    return null
  }
}

/**
 * Senaryonun HEDEFİ — kartta adın altında soluk yazılır. Kaynak sırası: (1) gizli OLMAYAN ortam değişkenlerinden
 * değeri http(s) adresi olan ilki (BASE_URL deseni; script adresi `__ENV` ile kurar), (2) script metnindeki adres
 * sabitleri (import satırları ve kütüphane CDN'leri hariç). Farklı host sayısı `hosts` ile döner ("+2").
 * Hiç adres yoksa null — hedef uydurulmaz.
 *
 * @returns {{url: string, host: string, hosts: number} | null}
 */
export function scenarioTarget(m) {
  const found = []
  for (const e of Array.isArray(m?.env) ? m.env : []) {
    const v = typeof e?.value === 'string' ? e.value.trim() : ''
    if (!e?.secret && /^https?:\/\//i.test(v) && hostOf(v)) found.push(v)
  }
  const code = String(m?.script || '')
    .split(/\r?\n/)
    .filter((line) => !/^\s*import\b/.test(line) && !/^\s*\/\//.test(line))
    .join('\n')
  for (const match of code.matchAll(URL_IN_TEXT)) {
    const url = stripTrailingPunct(match[0])
    const host = hostOf(url)
    if (host && !LIB_HOSTS.has(host)) found.push(url)
  }
  if (!found.length) return null
  const hosts = [...new Set(found.map(hostOf))]
  return { url: found[0], host: hosts[0], hosts: hosts.length }
}

/** Tarayıcı senaryosu mu (k6 browser modülü)? Kartın koşucu etiketi "k6 · browser" olur. */
export function isBrowserScript(script) {
  return BROWSER_IMPORT.test(String(script || ''))
}

/** Doğrulama sayaçları — koşum hiç sayaç üretmediyse null (0/0 ile karıştırılmaz). */
export function checkCounts(m) {
  const p = num(m?.checks_passed)
  const f = num(m?.checks_failed)
  if (p == null && f == null) return null
  const passed = Math.max(0, p ?? 0)
  const failed = Math.max(0, f ?? 0)
  return { passed, failed, total: passed + failed }
}

/** Başarısız check ADLARI (checks_json) — bozuk/boş JSON boş liste. */
export function failedCheckNames(m) {
  const raw = m?.checks_json
  if (!raw) return []
  try {
    const list = JSON.parse(raw)
    return Array.isArray(list) ? list.filter((c) => c && c.passed === false && c.name).map((c) => String(c.name)) : []
  } catch {
    return []
  }
}

/**
 * Sunucu hata metninin ÖZÜ — tek satır. Sunucu metni "etiket:\nasıl sebep…" biçiminde (k6 çıktısından ayıklanmış satırlar);
 * etiket `:` ile bitiyorsa ikinci satır gösterilir (asıl sebep, ör. "TypeError: …" / "Request Failed — …"), yoksa ilk satır.
 */
export function errorGist(error) {
  const lines = String(error ?? '').split(/\r?\n/).map((s) => s.trim()).filter(Boolean)
  if (!lines.length) return null
  return lines[0].endsWith(':') && lines[1] ? lines[1] : lines[0]
}

/**
 * Başarısız koşumun NEDENİ — kartın sonuç panelinde tek satır. null: gösterilecek neden yok (geçti / koşmadı / NO_CHECKS
 * paneli kendi cümlesini taşır).
 * - `check`: düşen check'in adı (+ `more` kaç tane daha) — kullanıcının kendi yazdığı ad, en açıklayıcı bilgi.
 * - `timeout`: süreç zaman aşımıyla sonlandırıldı (`limit` sn); k6 sebep bıraktıysa `detail`.
 * - `threshold`: çıkış 99 — script'in eşiklerinden biri aşıldı (eşik adı listede yok).
 * - `error`: script/k6 hatası, `detail` hata özü.
 * - `fail`: sebepsiz başarısızlık.
 */
export function runFailure(m) {
  const s = m?.status
  if (s !== 'FAIL' && s !== 'ERROR' && s !== 'TIMEOUT') return null
  const names = failedCheckNames(m)
  if (names.length) return { kind: 'check', name: names[0], more: names.length - 1 }
  const lines = String(m?.error ?? '').split(/\r?\n/).map((x) => x.trim()).filter(Boolean)
  const cause = lines.length > 1 && lines[0].endsWith(':') ? lines[1] : null
  if (s === 'TIMEOUT') return { kind: 'timeout', limit: num(m?.timeout_seconds), detail: cause }
  if (num(m?.exit_code) === 99) return { kind: 'threshold', detail: cause }
  if (s === 'ERROR') return { kind: 'error', detail: errorGist(m?.error) }
  return cause ? { kind: 'error', detail: cause } : { kind: 'fail', detail: null }
}

/**
 * Süre → okunur parçalar: 1 sn altı tam milisaniye ("820" + "ms"), üstü tek ondalıklı saniye ("2.4" + "s").
 * Önce yuvarlanır: 999.6 ms "1000 ms" değil "1.0 s" okunur.
 */
export function humanizeMs(ms) {
  const n = num(ms)
  if (n == null || n < 0) return null
  const r = Math.round(n)
  if (r < 1000) return { num: String(r), unit: 'ms' }
  return { num: (r / 1000).toFixed(1).replace(/\.0$/, ''), unit: 's' }
}

/**
 * Koşum süresinin tonu ve karşılaştırıldığı sınır (ms):
 * - `bad`: zaman aşımı (sınır = süreç zaman aşımı).
 * - `warn` / `ok`: YALNIZ yavaş koşum alarmı açıkken — sınır izlemenin kendi `slow_threshold_ms`'i (alarmla aynı kural).
 * - `neutral`: eşik tanımsız ya da ölçüm yok.
 */
export function durationAssessment(m) {
  const d = num(m?.duration_ms)
  if (m?.status === 'TIMEOUT') {
    const s = num(m?.timeout_seconds)
    return { tone: 'bad', limitMs: s != null && s > 0 ? s * 1000 : null }
  }
  if (d == null) return { tone: 'neutral', limitMs: null }
  const limit = num(m?.slow_threshold_ms)
  if (!m?.slow_response_enabled || !(limit > 0)) return { tone: 'neutral', limitMs: null }
  return { tone: d > limit ? 'warn' : 'ok', limitMs: limit }
}

/** Son koşum güncel sürümle mi yapıldı? Farklıysa son koşumun sürümü (drift), değilse null. */
export function versionDrift(m) {
  const cur = m?.script_version
  const ran = m?.run_script_version
  return cur && ran && String(cur) !== String(ran) ? String(ran) : null
}
