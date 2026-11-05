/**
 * Anahtar Kelime izleme KARTI modeli — saf yardımcılar (React yok; kart ve testleri paylaşır).
 *
 * <p>Satır alanları (GET /monitoring/keyword → MonitoringController.enrichKeyword): `keyword` (TEK arama metni — sunucu
 * `indexOf` ile arar; regex YOK), `operator` GTE|LTE|EQ|GT|LT + `match_count` (sağlıklı = geçiş adedi [operatör] N),
 * `case_sensitive`, `use_proxy` AUTO|ON|OFF + `proxy_effective`/`proxy_bypassed`, `slow_response_enabled` +
 * `slow_threshold_ms`, `timeout_ms`, `interval_seconds`; son sonuç: `status` up|down|error|unknown, `found`,
 * `occurrences`, `ok`, `http_status`, `response_ms`, `snippet` (ilk eşleşmenin ±50 karakterlik çevresi — boşluklar
 * tek boşluğa indirilmiş ham gövde, en fazla 200 karakter; yalnız bulunduysa), `error` (istisna metni), `checked_at`.
 *
 * <p>kw zenginleştirmesi (2026-11-05) ek alanları: `rules` (etkin kural listesi — eski izlemede tek kural), `rule_logic`,
 * `rule_results` / `breach_level` (son kontrol), `min_bytes` / `max_bytes` / `body_bytes` (boyut kuralı), `change_detect` /
 * `change_baseline` / `content_changed` (içerik değişimi), `expected_status` (kabul edilen durum kodları; boş = kapalı),
 * `same_url_count` / `same_url_hidden_count`. Alan yoksa (eski sunucu) ilgili çip çizilmez — kart uydurmaz.
 */

import { matchParts } from '../../utils/keywordFold.js'
import { isSimpleRule, logicOf, rulesFromRow } from './rules/ruleModel.js'

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

const OPERATORS = new Set(['GTE', 'LTE', 'EQ', 'GT', 'LT'])

/**
 * Kural: `{ kind, op, n }`. `kind`:
 * - `contains` — sayfada EN AZ BİR KEZ geçmeli (GTE 1 / GT 0; formun varsayılanı).
 * - `absent` — sayfada HİÇ geçmemeli (LTE 0 / EQ 0 / LT 1; sunucunun eski `CONTAINS` alarm koşulu).
 * - `count` — diğer adet kuralları ("en az 10 kez", "tam 1 kez" …).
 */
export function ruleOf(m) {
  const raw = String(m?.operator || 'GTE').toUpperCase()
  const op = OPERATORS.has(raw) ? raw : 'GTE'
  const n = Math.max(0, num(m?.match_count) ?? 1)
  let kind = 'count'
  if ((op === 'GTE' && n === 1) || (op === 'GT' && n === 0)) kind = 'contains'
  else if (((op === 'LTE' || op === 'EQ') && n === 0) || (op === 'LT' && n === 1)) kind = 'absent'
  return { kind, op, n }
}

/**
 * Kural rozetinin metni: "Must contain" / "Must NOT contain" / "At least 10 times" / "Exactly once" … (`t` = useT).
 * Tekil biçimler ayrı anahtar ("Exactly 1 times" yazılmaz).
 */
export function ruleLabel(rule, t) {
  if (rule.kind === 'contains') return t('keyword.card.ruleContains')
  if (rule.kind === 'absent') return t('keyword.card.ruleAbsent')
  if (rule.n === 1 && (rule.op === 'LTE' || rule.op === 'EQ' || rule.op === 'GT')) return t(`keyword.card.rule1.${rule.op}`)
  return t(`keyword.card.rule.${rule.op}`, rule.n)
}

/**
 * Alarmın HANGİ durumda tetiklendiği ("if « X » does not appear on the page at all") — formdaki canlı açıklamayla
 * (KeywordMonitorPage `triggerPhrase`) aynı sözlük anahtarları, aynı dallar.
 */
export function alertTrigger(rule, keyword, t) {
  const kw = String(keyword ?? '').trim()
  const k = kw ? `« ${kw} »` : t('keyword.theKeyword')
  const { op, n } = rule
  switch (op) {
    case 'LTE': return n === 0 ? t('keyword.trig.LTE0', k) : t('keyword.trig.LTE', k, n)
    case 'EQ': return t('keyword.trig.EQ', k, n)
    case 'GT': return t('keyword.trig.GT', k, n)
    case 'LT': return t('keyword.trig.LT', k, n)
    default: return n <= 1 ? t('keyword.trig.GTE1', k) : t('keyword.trig.GTE', k, n)
  }
}

/** Sonuç türü → panel tonu (ok | bad | neutral). */
const VERDICT_TONE = {
  found: 'ok', absent: 'ok', countOk: 'ok', rulesOk: 'ok',
  missing: 'bad', forbidden: 'bad', countFail: 'bad', rulesFail: 'bad', error: 'bad', other: 'bad',
  pending: 'neutral',
}

/**
 * Sayfa geldi ve KURAL sağlanmadı — bu kodlarda hüküm kuraldan adlandırılır ("Bulunamadı", "2/3 kural sağlanmadı").
 * Başka bir kodla (HTTP durumu, son adres, yönlendirme, boyut …) kapalı izlemede kural TUTMUŞ olabilir; hüküm o nedenden
 * gelir (`other`), eşleşme sayacı gösterilmez (son inceleme O1). Hata modeli de bu kümeyi kullanır.
 */
export const CONDITION_CODES = Object.freeze(new Set(['KEYWORD_NOT_FOUND', 'KEYWORD_FOUND_FORBIDDEN', 'KEYWORD_COUNT_MISMATCH', 'RULES_NOT_MET']))

/** Kapalı izlemenin nedeni kural DIŞI mı (sunucu kodu var ve koşul ailesinden değil)? Kod yoksa (eski satır) false. */
export function nonRuleFailure(m) {
  const code = m?.failure_reason
  return typeof code === 'string' && code !== '' && !CONDITION_CODES.has(code)
}

/**
 * Son kontrolün HÜKMÜ — kartın kalbi. `{ kind, tone, count, rule }`; `kind`:
 * `pending` (hiç kontrol yok) · `error` (sayfa okunamadı: istek hatası, kural değerlendirilmedi) ·
 * `found` / `missing` (içermeli kuralı) · `absent` / `forbidden` (içermemeli kuralı) · `countOk` / `countFail` (adet kuralı) ·
 * `rulesOk` / `rulesFail` (birden çok kural; `failed` / `total` son kontrolün sonuçlarından — kapalı ama hiçbir kural
 * sağlanmamış görünmüyorsa neden kurallar değildir → genel `countFail`) · `other` (kapalı, sunucu kodu kural DIŞI bir
 * neden — `code`; sayaç yok).
 * Sağlıklı mı kararı SUNUCUNUNDUR (`status` up) — kart kuralı yeniden değerlendirmez, yalnız adlandırır.
 */
export function verdictOf(m) {
  const rule = ruleOf(m)
  const status = m?.status
  let kind
  if (status === 'error') kind = 'error'
  else if (status !== 'up' && status !== 'down') kind = 'pending'
  else {
    const ok = status === 'up'
    // Kural dışı neden (HTTP_STATUS, FINAL_URL_MISMATCH, REDIRECT_NOT_FOLLOWED, BODY_TOO_* …): kural tutmuş olabilir —
    // "Bulunamadı · 3 eşleşme" çelişkisi yerine hüküm nedenin kendisi, sayaç yok (O1)
    if (!ok && nonRuleFailure(m)) return { kind: 'other', tone: VERDICT_TONE.other, count: null, rule, code: m.failure_reason }
    const set = ruleSet(m)
    if (set.multi) {
      const verdict = ok ? 'rulesOk' : set.failed > 0 ? 'rulesFail' : 'countFail'
      return { kind: verdict, tone: VERDICT_TONE[verdict], count: null, rule, failed: set.failed, total: set.rules.length }
    }
    if (rule.kind === 'contains') kind = ok ? 'found' : 'missing'
    else if (rule.kind === 'absent') kind = ok ? 'absent' : 'forbidden'
    else kind = ok ? 'countOk' : 'countFail'
  }
  const count = kind === 'pending' || kind === 'error' ? null : (num(m?.occurrences) ?? (m?.found ? null : 0))
  return { kind, tone: VERDICT_TONE[kind], count, rule }
}

/**
 * Süre → okunur parçalar: 1 sn altı tam milisaniye ("212" + "ms"), üstü tek ondalıklı saniye, tam saniyede ondalıksız
 * ("1.9" / "10" + "s"). Ondalık ayırıcı nokta — Sayfa Hızı kartı ve detay penceresiyle aynı dil.
 */
export function msParts(ms) {
  const n = num(ms)
  if (n == null || n < 0) return null
  const r = Math.round(n)
  if (r < 1000) return { num: String(r), unit: 'ms' }
  const s = Math.round(r / 100) / 10
  return { num: Number.isInteger(s) ? String(s) : s.toFixed(1), unit: 's' }
}

/** msParts'ın düz metni ("212 ms", "3 s") ya da null. */
export function msText(ms) {
  const p = msParts(ms)
  return p ? `${p.num} ${p.unit}` : null
}

/** HTTP durum sınıfı: '2xx' | '3xx' | '4xx' | '5xx' | null. */
export function httpClass(code) {
  const c = num(code)
  if (c == null || c < 100) return null
  if (c < 300) return '2xx'
  if (c < 400) return '3xx'
  return c < 500 ? '4xx' : '5xx'
}

/** HTTP kutusunun tonu: 2xx ok · 3xx nötr · 4xx warn · 5xx bad; yanıt yoksa istek hatasında bad, yoksa nötr. */
export function httpTone(code, failed = false) {
  const cls = httpClass(code)
  if (!cls) return failed ? 'bad' : 'neutral'
  return { '2xx': 'ok', '3xx': 'neutral', '4xx': 'warn', '5xx': 'bad' }[cls]
}

// Java HttpClient / SsrfGuard / HttpBodies / MonitorUrls hata metinleri (TR sunucu metinleri dâhil).
const CONFIG_RE = /yapılandırma hatası|geçerli bir host yok|invalid url|illegal character|URI with undefined scheme/i
const BLOCKED_RE = /izin verilmeyen hedef|boş hedef host|not allowed target/i
const DNS_RE = /çözümlenemeyen host|unresolved ?address|unknown ?host|name or service not known|nodename nor servname|no address associated|could not resolve/i
const TIMEOUT_RE = /timed? ?out|timeout|zaman aşımı|tamamlanmadı/i
const TLS_RE = /\bssl|\btls|pkix|certificate|handshake|sertifika/i
const REFUSED_RE = /connection refused|connectexception|bağlantı reddedildi/i

/**
 * Kapalı izlemenin NEDENİ (kart panelinin kanıt satırı). null: gösterilecek ek neden yok — sağlıklı, bekleyen ya da
 * yalnız kuralın sağlanmadığı (hüküm satırı zaten söylüyor). Tür: `config` · `blocked` (SSRF kalkanı) · `dns` ·
 * `timeout` (`detail` = zaman aşımı ms) · `tls` · `refused` · `error` (sınıflanamayan metin, ilk satır) ·
 * `http4xx` / `http5xx` (istek başarılı ama sunucu hata sayfası döndü; `detail` = kod).
 */
export function failureReason(m) {
  const status = m?.status
  if (status !== 'down' && status !== 'error') return null
  const err = String(m?.error || '').trim()
  if (err) {
    if (CONFIG_RE.test(err)) return { kind: 'config', detail: null }
    if (BLOCKED_RE.test(err)) return { kind: 'blocked', detail: err }
    if (DNS_RE.test(err)) return { kind: 'dns', detail: err }
    if (TIMEOUT_RE.test(err)) return { kind: 'timeout', detail: num(m?.timeout_ms) ?? num(m?.response_ms) }
    if (TLS_RE.test(err)) return { kind: 'tls', detail: err.split(/\r?\n/)[0] }
    if (REFUSED_RE.test(err)) return { kind: 'refused', detail: err }
    return { kind: 'error', detail: err.split(/\r?\n/)[0] }
  }
  const cls = httpClass(m?.http_status)
  if (cls === '4xx' || cls === '5xx') return { kind: `http${cls}`, detail: num(m?.http_status) }
  return null
}

/**
 * Yanıt süresi değerlendirmesi: `{ tone, limit }`. Zaman aşımında bad; yavaşlık alarmı açıksa eşik izlemenin kendi
 * `slow_threshold_ms`'i (üstü warn, altı ok); kapalıysa nötr (eşik uydurulmaz).
 */
export function responseAssessment(m, reason = failureReason(m)) {
  if (reason?.kind === 'timeout') return { tone: 'bad', limit: null, timedOut: true }
  const ms = num(m?.response_ms)
  const limit = m?.slow_response_enabled && num(m?.slow_threshold_ms) > 0 ? num(m.slow_threshold_ms) : null
  if (ms == null || limit == null) return { tone: 'neutral', limit, timedOut: false }
  return { tone: ms > limit ? 'warn' : 'ok', limit, timedOut: false }
}

/**
 * Vekil KİPİ çipi: yalnız izleme kipi zorlanmışsa (`use_proxy` ON | OFF). AUTO (envanterle aynı) → null: yol rozeti
 * MonitorCardMeta'da zaten var. ON iken vekil tanımsız / NO_PROXY yüzünden doğrudan çıkıldıysa `bypassed`.
 */
export function proxyMode(m) {
  const mode = String(m?.use_proxy || 'AUTO').toUpperCase()
  if (mode === 'ON') return { mode, bypassed: m?.proxy_bypassed === true || m?.proxy_effective === 'direct' }
  if (mode === 'OFF') return { mode, bypassed: false }
  return null
}

/**
 * MonitorCardMeta'ya verilecek satır: kip zorlanmışsa yol rozeti ÇİZİLMEZ (aynı bilgiyi kip çipi daha açık taşır —
 * "Proxy" + "Always via proxy" iki rozet olmasın). Liste süzgeci `proxy_effective`'i asıl satırdan okumaya devam eder.
 */
export function metaRow(m) {
  return proxyMode(m) ? { ...m, proxy_effective: null } : m
}

/** Bu uzunluktan uzun anahtar kelime kartta iki satırda kırpılır; tamamı dokun-gör balonunda. */
export const LONG_KEYWORD = 48
/** Bu uzunluktan uzun eşleşme çevresi kırpılabilir (telefonda iki satır ≈ 60 eş aralıklı karakter) → dokun-gör. */
export const LONG_SNIPPET = 60

/**
 * Eşleşme çevresini vurgu parçalarına böler: `[{ text, match }]`. Sunucu çevreyi boşlukları tek boşluğa indirerek
 * verir → aranan metnin boşlukları da indirgenir. Harf duyarsız kuralda Türkçe katlama (`utils/keywordFold` — sunucunun
 * `KeywordFold`'u: İ/I/ı/i aynı harf, uzunluk korunur); `i` bayraklı RegExp KULLANILMAZ (Türkçe İ/ı'yı kaçırıyordu, B3).
 * Vurgu özgün metinden dilimlenir (yazım biçimi korunur).
 */
export function highlightParts(text, keyword, caseSensitive = false) {
  const needle = String(keyword ?? '').replace(/\s+/g, ' ').trim()
  return matchParts(text, needle, caseSensitive)
}

// ── Çoklu kural / içerik kontrolleri / bilgi çipleri (kw paket C–E, 2026-11-05) ─────────────────────────────────────

/**
 * Kural özeti: `{ rules, logic, multi, custom, results, failed, evaluated }`. `rules` etkin liste (sunucunun `rules`'u;
 * eski satırda eski alanlardan tek kural), `custom` = basit tek kural DEĞİL (düzenli ifade / kapsam / kritik / etiket ya da
 * birden çok kural), `results` son kontrolün `rule_results`'u (yoksa null — bilinmiyor), `failed` sağlanmayan kural sayısı.
 */
export function ruleSet(m) {
  const rules = rulesFromRow(m)
  const results = Array.isArray(m?.rule_results) ? m.rule_results.filter((r) => r && typeof r === 'object') : null
  const failed = results ? results.filter((r) => r.ok === false).length : null
  return {
    rules, logic: logicOf(m), multi: rules.length > 1, custom: rules.length > 1 || !isSimpleRule(rules[0]),
    results: results && results.length ? results : null, failed, evaluated: results ? results.length : 0,
  }
}

/** Kural kimliği → son kontrolün sonucu (true / false / null = bilinmiyor). */
export function ruleOutcome(set, id) {
  const r = set?.results?.find((x) => x.id === id)
  return r ? r.ok === true : null
}

const BREACH = new Set(['WARNING', 'CRITICAL'])

/** İhlal seviyesi rozeti: yalnız kapalı izlemede ve sunucu kural seviyesi yazdıysa (WARNING | CRITICAL); aksi null. */
export function breachLevel(m) {
  if (m?.status !== 'down') return null
  const lv = String(m?.breach_level || '').toUpperCase()
  return BREACH.has(lv) ? lv : null
}

/**
 * "Durum kodu denetlenmiyor" çipi (B5): izleme SAĞLIKLI ama sayfa HTTP ≥ 400 döndü ve kabul edilen durum kodları kuralı
 * kapalı (`expected_status` boş — eski sunucuda alan yoktur, o da kapalı demektir). Döner: durum kodu ya da null.
 */
export function statusUnchecked(m) {
  const code = num(m?.http_status)
  if (m?.status !== 'up' || code == null || code < 400) return null
  const rule = m?.expected_status
  return rule == null || String(rule).trim() === '' ? code : null
}

/** Aynı URL'yi izleyen diğer izlemeler: `{ visible, hidden, total }` ya da null (yok / eski sunucu). */
export function sameUrl(m) {
  const visible = Math.max(0, num(m?.same_url_count) ?? 0)
  const hidden = Math.max(0, num(m?.same_url_hidden_count) ?? 0)
  return visible + hidden > 0 ? { visible, hidden, total: visible + hidden } : null
}

/**
 * Değişimin bir kontrolde NEDEN ölçülmediği (sunucu `change_note` / `skipped.note`; kw son inceleme O-3 / D-7): hata sayfası,
 * boş gövde, tamamlanmayan istek, durum / son adres kuralının reddi, seçicinin öğe bulamaması. Bilinmeyen kod gösterilmez.
 */
export const CHANGE_NOTE_CODES = Object.freeze(['HTTP_ERROR', 'EMPTY_BODY', 'INCOMPLETE', 'RULE_REJECTED', 'SELECTOR_NOT_FOUND'])

/**
 * Satırın / yanıtın değişim notu: bilinen kod; tanınmayan (daha yeni sunucu) dolu kod → `OTHER` (genel metin — ham kod
 * ekrana ASLA düşmez); yoksa null.
 */
export function changeNoteOf(v) {
  if (typeof v !== 'string' || v.trim() === '') return null
  return CHANGE_NOTE_CODES.includes(v) ? v : 'OTHER'
}

/**
 * İçerik değişimi çipi: kapalıysa null; `changed` (son kontrol temelden farklı), `skipped` (son kontrolde değişim ÖLÇÜLMEDİ —
 * `change_note`; nötr, asla "değişim yok" değil), `noBaseline` (temel henüz alınmadı), `watching` (izleniyor — son kontrol
 * aynı ya da bilinmiyor).
 */
export function changeState(m) {
  if (m?.change_detect !== true) return null
  if (m?.content_changed === true) return 'changed'
  if (changeNoteOf(m?.change_note)) return 'skipped'
  if (m?.change_baseline == null && 'change_baseline' in (m || {})) return 'noBaseline'
  return 'watching'
}

/** Boyut kuralı açık mı: `{ min, max, bytes, bad }` ya da null. `bad` = son başarısızlık boyut kaynaklı. */
export function sizeRule(m) {
  const min = num(m?.min_bytes)
  const max = num(m?.max_bytes)
  if (min == null && max == null) return null
  const code = m?.failure_reason
  return { min, max, bytes: num(m?.body_bytes), bad: m?.status !== 'up' && (code === 'BODY_TOO_SMALL' || code === 'BODY_TOO_LARGE') }
}
