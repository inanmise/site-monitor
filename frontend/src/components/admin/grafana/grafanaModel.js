/**
 * Ayarlar → Platform → Grafana panosu (2026-10-18) — SAF yardımcılar: form ↔ sunucu biçimi, yalnız değişen alanlar,
 * istemci doğrulaması (sunucu `GrafanaDashboardSettings` / `MetricsExportSettings` ile AYNI kurallar; garanti sunucuda),
 * aktarıcının canlı durumu ve Prometheus kazıma örneği. React yok — `grafanaModel.test.js`.
 */

/** Pano bölümleri — sunucu `GrafanaDashboardGenerator.SECTIONS` ile aynı sıra (test Java kaynağını okur). */
export const SECTION_IDS = ['overview', 'alerts', 'monitors', 'certificates', 'notifications', 'health', 'runtime']

/** Formun alanları (sunucu `GrafanaDashboardController.FIELDS` ile aynı ad ve sıra). */
export const FIELD_KEYS = [
  'title', 'uid', 'link_base_url', 'selector', 'time_from', 'refresh', 'language', 'sections', 'per_monitor',
  'default_team', 'top_n', 'export_enabled', 'export_per_monitor', 'export_max_entities', 'export_refresh_seconds',
]
const BOOL_KEYS = new Set(['per_monitor', 'export_enabled', 'export_per_monitor'])
const INT_KEYS = new Set(['top_n', 'export_max_entities', 'export_refresh_seconds'])

export const UID_RE = /^[A-Za-z0-9_-]{1,40}$/
export const LIMITS = Object.freeze({ title: 120, url: 300, selector: 300, team: 200 })

/** Sunucu değerleri → form durumu (sayılar metin — kullanıcı yazarken ara değer tutulabilsin). */
export function toForm(values) {
  const v = values || {}
  const form = {}
  for (const k of FIELD_KEYS) {
    if (BOOL_KEYS.has(k)) form[k] = v[k] !== false
    else if (INT_KEYS.has(k)) form[k] = v[k] == null ? '' : String(v[k])
    else if (k === 'sections') form[k] = orderSections(Array.isArray(v[k]) ? v[k] : SECTION_IDS)
    else form[k] = v[k] == null ? '' : String(v[k])
  }
  return form
}

/** Bölüm kimlikleri katalog sırasında, tekrarsız, bilinmeyen atılır. */
export function orderSections(ids) {
  const set = new Set((ids || []).map((x) => String(x)))
  return SECTION_IDS.filter((s) => set.has(s))
}

/** Form → sunucu gövdesi (tür dönüşümü). */
export function formToBody(form) {
  const out = {}
  for (const k of FIELD_KEYS) out[k] = bodyValue(k, form[k])
  return out
}

function bodyValue(k, v) {
  if (BOOL_KEYS.has(k)) return v === true
  if (k === 'sections') return orderSections(v)
  if (INT_KEYS.has(k)) {
    const s = String(v ?? '').trim()
    if (s === '') return ''
    return /^-?\d+$/.test(s) ? Number(s) : s
  }
  return String(v ?? '').trim()
}

function same(k, a, b) {
  if (k === 'sections') return orderSections(a).join(',') === orderSections(b).join(',')
  if (BOOL_KEYS.has(k)) return Boolean(a) === Boolean(b)
  return String(a ?? '').trim() === String(b ?? '').trim()
}

/** YALNIZ değişen alanlar (sunucu gönderilmeyen alana dokunmaz; boş değişiklik istek değildir). */
export function changedFields(savedForm, form) {
  const out = {}
  if (!savedForm || !form) return out
  for (const k of FIELD_KEYS) if (!same(k, savedForm[k], form[k])) out[k] = bodyValue(k, form[k])
  return out
}

export function isDirty(savedForm, form) {
  return Object.keys(changedFields(savedForm, form)).length > 0
}

/**
 * Yalnız DEĞİŞEN alanların hataları (sunucuyla aynı kural: değişmeyen değer yeniden doğrulanmaz). Kayıtlı bir değer
 * sonradan geçersizleşirse (ör. varsayılan takım silindi / yeniden adlandırıldı) başka bir alanın kaydı engellenmez.
 */
export function errorsForChanges(errors, changes) {
  const out = {}
  if (!errors || !changes) return out
  for (const k of Object.keys(errors)) if (Object.prototype.hasOwnProperty.call(changes, k)) out[k] = errors[k]
  return out
}

// ── Doğrulama (sunucuyla aynı kurallar) ───────────────────────────────────────────────────────────────

/**
 * Prometheus etiket eşleyicisi: virgülle ayrılmış `ad işleç "değer"`; işleç = != =~ !~; süslü ayraç YOK. Doğrusal tarama
 * (düzenli ifade geri izlemesi yok — CLAUDE.md "No unbounded loop, regex"); önce uzunluk.
 */
export function selectorOk(value) {
  const v = String(value ?? '')
  if (v.length > LIMITS.selector) return false
  const s = v.trim()
  if (s === '') return true
  const n = s.length
  let i = 0
  const isNameStart = (c) => /[A-Za-z_]/.test(c)
  const isName = (c) => /[A-Za-z0-9_]/.test(c)
  while (i < n) {
    while (i < n && s[i] === ' ') i++
    if (i >= n || !isNameStart(s[i])) return false
    while (i < n && isName(s[i])) i++
    while (i < n && s[i] === ' ') i++
    if (i >= n) return false
    if (s.startsWith('=~', i) || s.startsWith('!=', i) || s.startsWith('!~', i)) i += 2
    else if (s[i] === '=') i += 1
    else return false
    while (i < n && s[i] === ' ') i++
    if (i >= n || s[i] !== '"') return false
    i++
    let closed = false
    while (i < n) {
      const c = s[i]
      if (c === '\\') { i += 2; continue }
      if (c === '"') { closed = true; i++; break }
      if (c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127) return false
      i++
    }
    if (!closed) return false
    while (i < n && s[i] === ' ') i++
    if (i < n) {
      if (s[i] !== ',') return false
      i++
      if (i >= n || s.slice(i).trim() === '') return false
    }
  }
  return true
}

/** http(s) kök adresi; sorgu / parça / boşluk / tırnak yok (sunucu `urlProblem` ile aynı). */
export function urlOk(value) {
  const u = String(value ?? '').trim()
  if (u === '') return true
  if (u.length > LIMITS.url) return false
  const lower = u.toLowerCase()
  const start = lower.startsWith('https://') ? 8 : lower.startsWith('http://') ? 7 : -1
  if (start < 0 || u.length <= start) return false
  for (const c of u) {
    const code = c.charCodeAt(0)
    if (code <= 32 || code === 127 || '"\'<>`\\?#{}|^'.includes(c)) return false
  }
  return !'/.:@'.includes(u[start])
}

function hasControl(s) {
  for (const c of String(s ?? '')) {
    const code = c.charCodeAt(0)
    if (code < 32 || code === 127) return true
  }
  return false
}

function intIn(v, min, max) {
  const s = String(v ?? '').trim()
  if (!/^\d+$/.test(s)) return false
  const n = Number(s)
  return n >= min && n <= max
}

/**
 * İstemci kuralları → `{ alan: { key, params } }` (boş = geçerli). `options` sunucunun `options` bloğu (aralıklar);
 * `teams` bilinen takım adları (boş liste = denetlenmez — kapsamlı müdür takım dizini almaz).
 */
export function validateForm(form, options = {}, teams = []) {
  const errs = {}
  const title = String(form.title ?? '')
  if (title.length > LIMITS.title) errs.title = { key: 'grafana.v.titleLong', params: [LIMITS.title] }
  else if (hasControl(title)) errs.title = { key: 'grafana.v.titleControl', params: [] }
  const uid = String(form.uid ?? '').trim()
  if (uid !== '' && !UID_RE.test(uid)) errs.uid = { key: 'grafana.v.uid', params: [] }
  if (!urlOk(form.link_base_url)) errs.link_base_url = { key: 'grafana.v.url', params: [] }
  if (!selectorOk(form.selector)) errs.selector = { key: 'grafana.v.selector', params: [] }
  if (!orderSections(form.sections).length) errs.sections = { key: 'grafana.v.sections', params: [] }
  const top = options.top_n || { min: 3, max: 100 }
  if (String(form.top_n ?? '').trim() !== '' && !intIn(form.top_n, top.min, top.max)) {
    errs.top_n = { key: 'grafana.v.range', params: [top.min, top.max] }
  }
  const ent = options.export_max_entities || { min: 100, max: 50000 }
  if (String(form.export_max_entities ?? '').trim() !== '' && !intIn(form.export_max_entities, ent.min, ent.max)) {
    errs.export_max_entities = { key: 'grafana.v.range', params: [ent.min, ent.max] }
  }
  const ref = options.export_refresh_seconds || { min: 30, max: 600 }
  if (String(form.export_refresh_seconds ?? '').trim() !== '' && !intIn(form.export_refresh_seconds, ref.min, ref.max)) {
    errs.export_refresh_seconds = { key: 'grafana.v.range', params: [ref.min, ref.max] }
  }
  const team = String(form.default_team ?? '').trim()
  if (team.length > LIMITS.team) errs.default_team = { key: 'grafana.v.teamLong', params: [LIMITS.team] }
  else if (team && teams.length && !teams.includes(team)) errs.default_team = { key: 'grafana.v.team', params: [] }
  return errs
}

// ── Aktarıcı durumu ───────────────────────────────────────────────────────────────────────────────────

/** Sunucu damgası (UTC, bölgesiz) → epoch ms; boş / bozuk → NaN. */
export function utcMs(iso) {
  if (!iso) return NaN
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`
  return new Date(s).getTime()
}

/**
 * Canlı kontrol durumu: `disabled` (kapalı) · `waiting` (açılıştan beri tur yok) · `stale` (son tur aralığın 3 katından
 * eski — iş parçacığı takılmış olabilir) · `degraded` (bazı kaynaklar okunamadı) · `running`. Bilinmeyen durum asla
 * "running" sayılmaz.
 */
export function exporterState(exp, nowMs = Date.now()) {
  if (!exp || typeof exp !== 'object') return 'unknown'
  // Belirteç yoksa aktarıcı hiç koşmaz (uç 404) — "kapalı"dan önce söylenir: düzeltme ayarda değil, ortam değişkeninde
  if (exp.state === 'no_token' || exp.token_configured === false) return 'no_token'
  if (exp.state === 'disabled' || exp.enabled === false) return 'disabled'
  if (exp.state === 'waiting') return 'waiting'
  if (exp.state !== 'running') return 'unknown'
  const at = utcMs(exp.refreshed_at)
  const now = Number.isFinite(utcMs(exp.server_now)) ? utcMs(exp.server_now) : nowMs
  const every = Number(exp.refresh_seconds) > 0 ? Number(exp.refresh_seconds) : 60
  // Sunucuyla aynı eşik: 3 × aralık, en az 3 dk (MonitoringMetricsExporter.staleAfterMs — o sürede uç iş değerlerini gizler)
  if (!Number.isFinite(at) || now - at > Math.max(180, every * 3) * 1000) return 'stale'
  if ((exp.sources || []).some((s) => s && s.up === false)) return 'degraded'
  return 'running'
}

/**
 * Bu pod son turda kurum ailelerini (izleme, alarm, sertifika, alan adı…) yaydı mı (çok pod R3-1, inceleme L8): `false` =
 * yalnız kendi pod ölçülerini yaydı (tarama lideri; kurum ailelerini başka pod yayıyor — kayıt sayıları o turda yok),
 * `true` = yaydı, `null` = bilinmiyor (eski sunucu alanı göndermez / tur yok) → eski görünüm.
 */
export function exportsOrgFamilies(exp) {
  return typeof exp?.org_families === 'boolean' ? exp.org_families : null
}

/** Okunamayan kaynak adları. */
export function failedSources(exp) {
  return (exp?.sources || []).filter((s) => s && s.up === false).map((s) => String(s.source))
}

// ── Prometheus kazıma örneği ──────────────────────────────────────────────────────────────────────────

/** Eşleyicideki `namespace="x"` değeri (varsa) — örnek yapılandırmaya yazılır. */
export function namespaceOf(selector) {
  const m = /(?:^|[\s,])namespace\s*=\s*"([^"\\]{1,100})"/.exec(String(selector ?? ''))
  return m ? m[1] : null
}

/** Prometheus'ta belirtecin okunduğu dosya (yer tutucu — Prometheus pod'una bağlanan Secret). */
export const TOKEN_FILE_PLACEHOLDER = '/etc/prometheus/secrets/site-monitor/metrics-export-token'

/**
 * Pod anotasyonlarıyla (Helm `podAnnotations`: prometheus.io/scrape|port|path) kazıyan Prometheus için İKİ örnek iş:
 * `site-monitor` (kimliksiz /metrics: JVM / HTTP / uygulama sağlığı) ve `site-monitor-inventory` (iş ölçümleri; taşıyıcı
 * belirteç `credentials_file`'dan — değer asla örneğe yazılmaz). Değerler yer tutucu; ad alanı eşleyiciden okunur.
 */
export function scrapeSnippet(selector, inventoryPath = '/metrics/sitemonitor') {
  const ns = namespaceOf(selector) || '<site-monitor-namespace>'
  const discovery = [
    '    kubernetes_sd_configs:',
    '      - role: pod',
    '        namespaces:',
    `          names: [${ns}]`,
    '    relabel_configs:',
    '      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]',
    '        action: keep',
    '        regex: "true"',
    '      - source_labels: [__meta_kubernetes_namespace]',
    '        target_label: namespace',
    '      - source_labels: [__meta_kubernetes_pod_name]',
    '        target_label: pod',
  ]
  return [
    'scrape_configs:',
    '  - job_name: site-monitor',
    '    metrics_path: /metrics',
    '    scrape_interval: 30s',
    ...discovery,
    '  - job_name: site-monitor-inventory',
    `    metrics_path: ${inventoryPath}`,
    '    scrape_interval: 60s',
    '    authorization:',
    '      type: Bearer',
    `      credentials_file: ${TOKEN_FILE_PLACEHOLDER}`,
    ...discovery,
  ].join('\n')
}
