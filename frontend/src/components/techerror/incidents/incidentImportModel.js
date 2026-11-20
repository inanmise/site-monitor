import { LIMITS, topicOpenId } from '../techErrorModel.js'

/**
 * OLAY KAYDINDAN ANOMALİ KONUSU — saf model (2026-11-20, kullanıcı kararı: "olay kaydı rapora alınabilsin ve CANLI bağlı
 * kalsın; kullanıcı her alanı değiştirebilsin, değiştirdiği alan olayı izlemeyi bıraksın").
 *
 * <p>Sunucu sözleşmesi `olay-ice-alma-api.md`: alan grupları (`topic`, `description`, `date`, `time`, `errors`, `outage`,
 * `impact`), açıklama parçaları (`rca`, `impact`, `resolution`, `technical`), not kodları ve bağ durumları KALICI adlardır —
 * ekran metni her zaman `t()` ile (ham kod gösterilmez; bilinmeyen kod genel metne düşer).
 *
 * <p>React'e ve `api/client.js`'e bağlı değildir (birim testi doğrudan çağırır).
 */

/** Konunun olaydan beslenen alan grupları — sunucunun kanonik sırası (iğneli grupların gösterim sırası da budur). */
export const INCIDENT_GROUPS = Object.freeze(['topic', 'description', 'date', 'time', 'errors', 'outage', 'impact'])
/** Açıklamaya alınabilecek olay bölümleri (sunucunun yazma sırası). */
export const DESCRIPTION_PARTS = Object.freeze(['rca', 'impact', 'resolution', 'technical'])
/** Varsayılan parçalar: kök neden, iş etkisi, çözüm (teknik açıklama isteğe bağlı). */
export const DEFAULT_PARTS = Object.freeze(['rca', 'impact', 'resolution'])
/** Sunucunun içe alma / eşitleme not kodları (UI `te.inc.note.<kod>`). */
export const NOTE_CODES = Object.freeze([
  'topic_clipped', 'description_clipped', 'started_before_month', 'date_outside_month', 'time_unknown', 'outage_id_missing',
  'will_be_hidden', 'value_refused', 'image_skipped',
])
/** Kopyalanmayan görselin nedeni (UI `te.inc.imgSkip.<neden>`). */
export const IMAGE_SKIP_REASONS = Object.freeze(['format', 'too_large', 'total', 'unreadable'])
/** Bağ durumları: eşit, olay değişti (eşitlenecek), olay değişti ama rapor dondurulmuş, olay silindi, olay okunamıyor. */
export const LINK_STATES = Object.freeze(['ok', 'stale', 'frozen_stale', 'deleted', 'hidden'])

/** Bir istekte en çok kaç olay alınır (sunucu kuralı 1–10). */
export const MAX_IMPORT = 10
/** Arama gecikmesi (yazarken her tuşta istek gitmesin). */
export const SEARCH_DEBOUNCE_MS = 300
/** Arama metninin üst sınırı (sunucu `q` ≤ 200). */
export const SEARCH_MAX = 200
/** Aday listesi bu KAP genişliğinden itibaren tablo, altında kart. */
export const CANDIDATE_TABLE_MIN_PX = 640
/** Kapanmış ay seçeneği sayısı (ters yöndeki giriş: olay kaydından rapora). */
export const CLOSED_MONTH_COUNT = 24

const GROUP_LABEL_KEY = Object.freeze({
  topic: 'te.inc.grp.topic',
  description: 'te.inc.grp.description',
  date: 'te.inc.grp.date',
  time: 'te.inc.grp.time',
  // Anahtar adında "error" yok: hata iletisi kapısı (errorMessageQuality) adı "error" geçen anahtarı hata metni sayar
  errors: 'te.inc.grp.count',
  outage: 'te.inc.grp.outage',
  impact: 'te.inc.grp.impact',
})

const PART_LABEL_KEY = Object.freeze({
  rca: 'te.inc.part.rca',
  impact: 'te.inc.part.impact',
  resolution: 'te.inc.part.resolution',
  technical: 'te.inc.part.technical',
})

/** Not kodunun i18n anahtarı; bilinmeyen kod (daha yeni sunucu) genel metne düşer — ham kod yazılmaz. */
export function noteKey(code) {
  return NOTE_CODES.includes(code) ? `te.inc.note.${code}` : 'te.inc.note.other'
}

/** Görsel atlama nedeninin i18n anahtarı (bilinmeyen → genel). */
export function imageSkipKey(reason) {
  return IMAGE_SKIP_REASONS.includes(reason) ? `te.inc.imgSkip.${reason}` : 'te.inc.imgSkip.other'
}

/** Alan grubunun i18n anahtarı (bilinmeyen grup → null; çağıran çizmez). */
export function groupLabelKey(group) {
  return GROUP_LABEL_KEY[group] || null
}

/** Açıklama parçasının i18n anahtarı. */
export function partLabelKey(part) {
  return PART_LABEL_KEY[part] || null
}

/** Dizi ya da virgüllü metin → kırpılmış, boşsuz, tekrarsız metin listesi (bozuk değer → boş liste). */
export function parseList(v) {
  const raw = Array.isArray(v) ? v : typeof v === 'string' ? v.split(',') : []
  const out = []
  for (const x of raw) {
    if (typeof x !== 'string') continue
    const s = x.trim()
    if (s && !out.includes(s)) out.push(s)
  }
  return out
}

/** Grup listesini kanonik sıraya dizer; bilinmeyen grupları atar. */
export function orderGroups(list) {
  const set = new Set(parseList(list))
  return INCIDENT_GROUPS.filter((g) => set.has(g))
}

/** Pozitif tam sayı kimlik (sayı ya da sayı metni) → sayı; değilse null. */
export function positiveId(v) {
  if (v === null || v === undefined || v === '' || typeof v === 'boolean') return null
  const n = Number(v)
  return Number.isInteger(n) && n > 0 ? n : null
}

/** Bağ anahtarı (bölüm + konu) — konu kimliği `:` içermez (techErrorModel.topicOpenId ile aynı biçim). */
export function linkKey(sectionId, issueId) {
  return topicOpenId(sectionId, issueId)
}

/**
 * Sunucunun bir `incident_links` satırını normalleştirir. Bölüm ya da konu kimliği yoksa null (çizilmez). Bilinmeyen durum
 * 'unknown' (UI "durum bilinmiyor" — "eşit" sayılmaz). `hidden` durumunda başlık / durum / tarih sunucu gönderse de atılır.
 */
export function normalizeLink(raw) {
  if (!raw || typeof raw !== 'object') return null
  const sectionId = typeof raw.section_id === 'string' && raw.section_id ? raw.section_id : null
  const issueId = typeof raw.issue_id === 'string' && raw.issue_id ? raw.issue_id : null
  if (!sectionId || !issueId) return null
  const state = LINK_STATES.includes(raw.state) ? raw.state : 'unknown'
  const hidden = state === 'hidden'
  const text = (v) => (typeof v === 'string' && v.trim() ? v : null)
  return {
    sectionId,
    issueId,
    incidentId: positiveId(raw.incident_id),
    state,
    title: hidden ? null : text(raw.title),
    status: hidden ? null : text(raw.status),
    occurredAt: hidden ? null : text(raw.occurred_at),
    pinned: orderGroups(raw.pinned),
    notes: parseList(raw.notes),
    syncedAt: text(raw.synced_at),
    incidentUpdatedAt: text(raw.incident_updated_at),
  }
}

const EMPTY_INDEX = Object.freeze({ known: 'absent', list: Object.freeze([]), byTopic: new Map(), bySection: new Map(), incidentIds: new Set() })

/**
 * Rapor ayrıntısındaki bağların dizini. `known`:
 * - 'ok'     — sunucu listeyi gönderdi (boş liste = bağ YOK, kesin);
 * - 'error'  — sunucu listeyi okuyamadı (`incident_links_error: true` ya da `null`) → bağlı konular "bilinmiyor";
 * - 'absent' — alan hiç yok (eski pod) → hiçbir şey iddia edilmez, içe alma düğmesi de çizilmez.
 *
 * `bySection`: bölüm kimliği → (konu kimliği → bağ). `incidentIds`: bu raporda bağlı olay kimlikleri.
 */
export function linksIndex(data) {
  if (!data || typeof data !== 'object') return EMPTY_INDEX
  if (data.incident_links_error === true) return { ...EMPTY_INDEX, known: 'error' }
  if (!('incident_links' in data)) return EMPTY_INDEX
  if (!Array.isArray(data.incident_links)) return { ...EMPTY_INDEX, known: 'error' }
  const list = []
  const byTopic = new Map()
  const bySection = new Map()
  const incidentIds = new Set()
  for (const raw of data.incident_links) {
    const l = normalizeLink(raw)
    if (!l) continue
    list.push(l)
    byTopic.set(linkKey(l.sectionId, l.issueId), l)
    if (!bySection.has(l.sectionId)) bySection.set(l.sectionId, new Map())
    bySection.get(l.sectionId).set(l.issueId, l)
    if (l.incidentId != null) incidentIds.add(l.incidentId)
  }
  return { known: 'ok', list, byTopic, bySection, incidentIds }
}

/** Eşitlenmeyi bekleyen (dondurulmamış raporda olayı değişmiş) bağ var mı? */
export function hasStaleLink(index) {
  return !!index?.list?.some((l) => l.state === 'stale')
}

/**
 * Rapor dondurulmuş mu (kendiliğinden değişmez; elle güncellemede kullanıcıya sorulur): gönderildi / kuyrukta / gönderiliyor
 * ya da onay bekliyor / onaylandı (eskimemiş). Sunucu kuralının aynası — karar yine sunucunun; bu yalnız onay sorusu içindir.
 */
export function reportFrozen(report) {
  if (!report) return false
  if (['sent', 'queued', 'sending'].includes(report.status)) return true
  const st = report.approval?.state
  return st === 'pending' || st === 'approved'
}

/** Bağ durumunun rozeti: i18n anahtarı + ton (`syncing`: düzenleyici şu an eşitliyor). */
export function linkStateMeta(state, { syncing = false } = {}) {
  switch (state) {
    case 'ok': return { key: 'te.inc.state.ok', tone: 'ok' }
    case 'stale': return syncing ? { key: 'te.inc.state.staleSyncing', tone: 'info' } : { key: 'te.inc.state.stale', tone: 'warn' }
    case 'frozen_stale': return { key: 'te.inc.state.frozenStale', tone: 'warn' }
    case 'deleted': return { key: 'te.inc.state.deleted', tone: 'danger' }
    case 'hidden': return { key: 'te.inc.state.hidden', tone: 'muted' }
    default: return { key: 'te.inc.state.unknown', tone: 'muted' }
  }
}

/** "Güncelle" düğmesi gösterilir mi (olay değişmiş ve yazılabilir bir durum). */
export function canRefreshLink(state) {
  return state === 'stale' || state === 'frozen_stale'
}

/**
 * Bağı yenileyen eylemler ("Güncelle", "Olaydaki değere dön", "Tüm alanları olaydan yeniden al") çizilir mi: sunucunun
 * yenileme ucu olay kaydını OKUR (`incidents.view` gerekir); silinmiş / erişilemeyen olaydan değer alınamaz (sunucu 404).
 */
export function canWriteFromIncident(state, { readOnly = false, canViewIncidents = false } = {}) {
  return !readOnly && canViewIncidents && state !== 'deleted' && state !== 'hidden'
}

/** "Olay kaydını aç" gösterilir mi (silinmiş / erişilemeyen olay açılmaz). */
export function canOpenLinkedIncident(state, incidentId) {
  return incidentId != null && state !== 'deleted' && state !== 'hidden'
}

// ── Aday listesi ─────────────────────────────────────────────────────────────

const CANDIDATE_STATUSES = new Set(['OPEN', 'INVESTIGATING', 'MITIGATED', 'RESOLVED'])

/** Sunucunun aday satırı → görünüm satırı (kimliksiz satır null). */
export function candidateRow(raw) {
  if (!raw || typeof raw !== 'object') return null
  const id = positiveId(raw.id)
  if (id == null) return null
  const num = (v) => (v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))
  return {
    id,
    title: typeof raw.title === 'string' ? raw.title : '',
    occurredAt: typeof raw.occurred_at === 'string' ? raw.occurred_at : null,
    resolvedAt: typeof raw.resolved_at === 'string' ? raw.resolved_at : null,
    status: CANDIDATE_STATUSES.has(raw.status) ? raw.status : (typeof raw.status === 'string' ? raw.status : null),
    severity: typeof raw.severity === 'string' ? raw.severity : null,
    channel: typeof raw.channel === 'string' ? raw.channel : null,
    techErrorCount: num(raw.tech_error_count),
    outageRelated: raw.outage_related === true ? true : raw.outage_related === false ? false : null,
    affectedCustomers: num(raw.affected_customers),
    imported: raw.imported === true,
  }
}

/** Sunucunun aradığı en çok olay kaydı (ayın ilk 2000'i; fazlası `truncated` ile söylenir — sunucu MAX_CANDIDATE_ROWS). */
export const CANDIDATE_SCAN_MAX = 2000

/**
 * Aday yanıtını ayrıştırır: `{ items, total, period, truncated }` ya da null (bozuk yanıt — çağıran "okunamadı" der, ASLA
 * "olay yok"). `truncated`: sunucu ayın yalnız ilk 2000 olay kaydında aradı (sonuç eksik olabilir — ekranda not).
 */
export function candidatesFrom(res) {
  const d = res?.data
  if (!res?.success || !d || typeof d !== 'object' || !Array.isArray(d.items)) return null
  const items = d.items.map(candidateRow).filter(Boolean)
  const total = Number.isFinite(Number(d.total)) ? Number(d.total) : items.length
  const p = d.period
  const period = p && Number.isInteger(Number(p.year)) && Number(p.month) >= 1 && Number(p.month) <= 12
    ? { year: Number(p.year), month: Number(p.month) } : null
  return { items, total, period, truncated: d.truncated === true }
}

/** Bölümde kalan konu yeri (30 tavanı) ile bir istekteki üst sınırın küçüğü (≥ 0). */
export function importCapacity(sectionCount) {
  const used = Number.isFinite(Number(sectionCount)) ? Number(sectionCount) : 0
  return Math.max(0, Math.min(MAX_IMPORT, LIMITS.issues - used))
}

/**
 * Satır seçilebilir mi: zaten bu raporda → 'imported'; seçili satır her zaman bırakılabilir (null); seçim istek üst sınırına
 * ulaştı → 'max'; bölümde yer kalmadı → 'capacity'; aksi null.
 */
export function selectionBlock(row, selected, { max = MAX_IMPORT, capacity = null } = {}) {
  if (!row) return 'imported'
  if (row.imported) return 'imported'
  if (selected?.has?.(row.id)) return null
  const n = selected?.size || 0
  if (capacity != null && n >= capacity) return capacity < max ? 'capacity' : 'max'
  if (n >= max) return 'max'
  return null
}

/** Seçim kümesinde aç / kapat (yeni Set döner — React durumu). */
export function toggleId(selected, id) {
  const next = new Set(selected)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  return next
}

/** Parça seçimi kanonik sırada (bilinmeyenler atılır). */
export function orderParts(parts) {
  const set = new Set(parseList(parts))
  return DESCRIPTION_PARTS.filter((p) => set.has(p))
}

// ── Yanıt sınıflandırma ───────────────────────────────────────────────────────

const NETWORK_CODES = new Set(['REQUEST_TIMEOUT', 'NETWORK_ERROR', 'OFFLINE', 'TIMEOUT', 'GATEWAY_TIMEOUT', 'NO_RESPONSE'])

/** Ağ / zaman aşımı / araya giren ağ geçidi (sonucu bilinmeyen) yanıtı mı? */
export function isNetworkish(res) {
  if (res == null || typeof res !== 'object') return true
  const st = Number(res.status ?? res.errorInfo?.status)
  if ([0, 502, 503, 504].includes(st)) return true
  return NETWORK_CODES.has(String(res.code ?? res.errorInfo?.code ?? ''))
}

/**
 * 404'ün sunucu KODLARI (sözleşme): olay ya da bağ GERÇEKTEN yok / görülemiyor — eski pod DEĞİL. Kodsuz 404 eski pod'dur;
 * genel `NOT_FOUND` ise iki şeyden biri olabilir (raporun kendisi yok ya da bu sürüm ucu tanımıyor — SPA 404'ü de bu kodu
 * verir), o yüzden ikisini birlikte söyler ('missing').
 */
const GONE_CODES = Object.freeze({ INCIDENT_NOT_FOUND: 'incident_gone', INCIDENT_LINK_NOT_FOUND: 'link_gone' })

const resStatus = (res) => Number(res?.status ?? res?.errorInfo?.status)
const resCode = (res) => {
  const c = res?.code ?? res?.errorInfo?.code
  return typeof c === 'string' && c ? c : null
}

/**
 * OKUMA hatasının türü (aday listesi, kullanım listesi, rapor / kanal okumaları):
 * - 'unsupported' — 405 / 501 ya da KODSUZ 404 (bu sürüm ucu tanımıyor);
 * - 'incident_gone' / 'link_gone' — 404 + `INCIDENT_NOT_FOUND` / `INCIDENT_LINK_NOT_FOUND` (kayıt yok ya da görülemiyor);
 * - 'missing' — 404 + başka kod (`NOT_FOUND`: rapor yok YA DA eski sürüm — ayrılamaz);
 * - 'forbidden' (403), 'invalid' (400 — sunucunun alan metni), 'network', 'error'.
 */
export function readFailureKind(res) {
  if (res == null || typeof res !== 'object') return 'network'
  const st = resStatus(res)
  const code = resCode(res)
  if (st === 405 || st === 501) return 'unsupported'
  if (st === 404) {
    if (code && GONE_CODES[code]) return GONE_CODES[code]
    return code ? 'missing' : 'unsupported'
  }
  if (st === 403) return 'forbidden'
  if (st === 400) return 'invalid'
  if (isNetworkish(res)) return 'network'
  return 'error'
}

/** Sunucu bekleme süresi söylemezse (429 IMPORT_BUSY) önerilen bekleme — sunucunun RETRY_AFTER_SECONDS'ı. */
const BUSY_RETRY_DEFAULT_S = 5

/** Sunucu kodu → hata türü (yazma ve önizleme yanıtları — önizleme de içe almanın doğrulamasını koşar). */
const CODE_KINDS = Object.freeze({
  CONCURRENT_MODIFICATION: 'conflict',
  INCIDENT_ALREADY_IMPORTED: 'already',
  SECTION_FULL: 'full',
  INCIDENT_NOT_FOUND: 'incident_gone',
  INCIDENT_OTHER_TEAM: 'other_team',
  REPORT_TOO_LARGE: 'too_large',
  INCIDENT_LINK_NOT_FOUND: 'link_gone',
  // 429: görselli içe alma kişi başına tek / pod başına sınırlı (TechErrorIncidentImportGate) — biraz sonra yeniden denenir
  IMPORT_BUSY: 'busy',
})

/** `VALIDATION_FAILED` alanı → hata türü (sözleşmenin alanları). */
const FIELD_KINDS = Object.freeze({
  section_id: 'section',
  incident_ids: 'selection',
  parts: 'parts',
  images: 'images_option',
  version: 'version',
  unpin: 'unpin',
  q: 'query',
  incident_id: 'incident_id',
})

/**
 * Her hata türünün kullanıcı cümlesi (TR + EN — ham kod ASLA gösterilmez). `unsure` / `error` için çağıranın kendi metni
 * kullanılır (işleme göre: içe alma / güncelleme / bağ koparma).
 */
export const FAILURE_KEYS = Object.freeze({
  conflict: 'te.inc.err.conflict',
  already: 'te.inc.err.already',
  full: 'te.inc.err.sectionFull',
  incident_gone: 'te.inc.err.incidentGone',
  other_team: 'te.inc.err.otherTeam',
  too_large: 'te.inc.err.tooLarge',
  link_gone: 'te.inc.err.linkGone',
  busy: 'te.inc.err.busy',
  section: 'te.inc.err.section',
  selection: 'te.inc.err.selection',
  parts: 'te.inc.err.parts',
  images_option: 'te.inc.err.imagesOption',
  version: 'te.inc.err.version',
  unpin: 'te.inc.err.unpin',
  query: 'te.inc.err.query',
  incident_id: 'te.inc.err.incidentId',
  forbidden: 'te.inc.err.forbidden',
  unsupported: 'te.inc.unsupported',
  missing: 'te.inc.err.missing',
})

/**
 * Yazma / önizleme hatasının türü. Sıra: sunucu kodu → `VALIDATION_FAILED` alanı → durum. Dönüş `{ kind, code, field,
 * incidentId, key }`; `key` = `FAILURE_KEYS[kind]` (yoksa null — çağıran kendi genel metnini yazar).
 * - 'already' → `incidentId` (sunucu gönderdiyse; eşzamanlı yarışta gelmez);
 * - 'unsure' → sonuç bilinmiyor (ağ / zaman aşımı / araya giren geçit) — çağıran raporu yeniden okur, körlemesine denemez.
 */
export function importFailure(res) {
  const code = resCode(res)
  const field = typeof res?.field === 'string' ? res.field : null
  const st = resStatus(res)
  let kind
  if (code && CODE_KINDS[code]) kind = CODE_KINDS[code]
  else if (code === 'VALIDATION_FAILED' && field && FIELD_KINDS[field]) kind = FIELD_KINDS[field]
  else if (st === 405 || st === 501 || (st === 404 && !code)) kind = 'unsupported'
  else if (st === 404) kind = 'missing'
  else if (st === 403) kind = 'forbidden'
  else if (isNetworkish(res)) kind = 'unsure'
  else kind = 'error'
  const out = { kind, code, field, key: FAILURE_KEYS[kind] || null }
  if (kind === 'already') out.incidentId = positiveId(res?.incident_id)
  if (kind === 'busy') {
    const wait = Number(res?.retry_after_seconds)
    out.retryAfter = Number.isFinite(wait) && wait > 0 ? Math.min(Math.ceil(wait), 600) : BUSY_RETRY_DEFAULT_S
  }
  return out
}

/**
 * Hatanın kullanıcı cümlesi `{ key, params }` ya da null (tür kendi cümlesini taşımıyor — çağıran sunucunun metnini ya da
 * işleme göre kendi genel metnini yazar). 'already': olay numarası biliniyorsa onu söyler, bilinmiyorsa (eşzamanlı yarış)
 * "seçilenlerden biri".
 */
export function failureMessage(f) {
  if (!f) return null
  if (f.kind === 'already') {
    return f.incidentId != null ? { key: 'te.inc.err.already', params: [f.incidentId] } : { key: 'te.inc.err.alreadyRace', params: [] }
  }
  if (f.kind === 'busy') return { key: 'te.inc.err.busy', params: [f.retryAfter ?? BUSY_RETRY_DEFAULT_S] }
  return f.key ? { key: f.key, params: [] } : null
}

/** Hata türü kullanıcıya YENİDEN DENE önerir mi (aynı istek yeniden denenince düzelebilir: ağ / beklenmedik sunucu hatası). */
export function failureRetryable(kind) {
  return kind === 'unsure' || kind === 'error' || kind === 'network' || kind === 'busy'
}

/** Hata türü "bu sürümde yok" mu (nötr ton, yeniden dene yok). */
export function failureUnsupported(kind) {
  return kind === 'unsupported'
}

// ── Önizleme ──────────────────────────────────────────────────────────────────

/**
 * Önizleme yanıtının görsel özeti → `{ copy, skipped: [reason], skippedIds: [image_id|null], allowed, remaining }`.
 * - `copy` — kopyalanacak görsel sayısı (sunucunun TAHMİNİ: tür + boyuttan; içe alma piksel sınırını da denetler);
 * - `allowed: false` — görsel istendi ama bölüm konu görseline izin vermiyor (sunucu yalnız o durumda gönderir);
 * - `remaining` — konunun iki görsel yeri dolduktan sonra alınmayan görsel sayısı (yoksa 0).
 */
export function proposalImages(raw) {
  const imgs = raw && typeof raw === 'object' ? raw : {}
  const copy = Number.isFinite(Number(imgs.copy)) ? Math.max(0, Math.trunc(Number(imgs.copy))) : 0
  const skipped = []
  const skippedIds = []
  for (const s of Array.isArray(imgs.skipped) ? imgs.skipped : []) {
    const reason = s && typeof s === 'object' ? s.reason : s
    if (typeof reason !== 'string' || !reason) continue
    skipped.push(reason)
    skippedIds.push(s && typeof s === 'object' ? positiveId(s.image_id) : null)
  }
  const rem = Number(imgs.remaining)
  return {
    copy,
    skipped,
    skippedIds,
    allowed: imgs.allowed !== false,
    remaining: Number.isFinite(rem) && rem > 0 ? Math.trunc(rem) : 0,
  }
}

/**
 * Önizleme yanıtı → `[{ incidentId, title, fields, description, notes, hidden, images }]` ya da null (bozuk yanıt).
 * `images` = {@link proposalImages}.
 */
export function proposalsFrom(res) {
  const list = res?.data?.proposals
  if (!res?.success || !Array.isArray(list)) return null
  return list.map((p) => {
    if (!p || typeof p !== 'object') return null
    const f = p.fields && typeof p.fields === 'object' ? p.fields : {}
    return {
      incidentId: positiveId(p.incident_id),
      title: typeof p.title === 'string' ? p.title : '',
      fields: f,
      description: typeof p.description_preview === 'string' ? p.description_preview : '',
      notes: parseList(p.notes),
      hidden: p.hidden === true,
      images: proposalImages(p.images),
    }
  }).filter((p) => p && p.incidentId != null)
}

const filled = (v) => v !== null && v !== undefined && !(typeof v === 'string' && v.trim() === '')

/**
 * Bir önerinin satırları: hangi grup DOLACAK, hangisi BOŞ kalacak. Her satır `{ group, filled, value }`; değeri metne
 * dönüştürmek bileşenin işi (dil). `time` yalnız iki saat varken dolu; `outage` / `impact` cevapsızsa (null) boş.
 */
export function proposalRows(p) {
  const f = p?.fields || {}
  return [
    { group: 'topic', filled: filled(f.topic), value: f.topic ?? null },
    { group: 'date', filled: filled(f.date), value: f.date ?? null },
    { group: 'time', filled: filled(f.start) && filled(f.end), value: { start: f.start ?? null, end: f.end ?? null, nextDay: f.end_next_day === true } },
    { group: 'errors', filled: filled(f.errors) && Number.isFinite(Number(f.errors)), value: filled(f.errors) ? Number(f.errors) : null },
    { group: 'outage', filled: f.outage === true || f.outage === false, value: { outage: f.outage === true ? true : f.outage === false ? false : null, id: filled(f.outage_id) ? String(f.outage_id) : null } },
    { group: 'impact', filled: f.impact === true || f.impact === false || filled(f.customers),
      value: { impact: f.impact === true ? true : f.impact === false ? false : null, customers: filled(f.customers) && Number.isFinite(Number(f.customers)) ? Number(f.customers) : null } },
    { group: 'description', filled: filled(p?.description), value: p?.description || null },
  ]
}

// ── Zaman (İstanbul, sabit UTC+3) ──────────────────────────────────────────────

const ISTANBUL_OFFSET_MS = 3 * 60 * 60 * 1000

/**
 * Olay zaman damgası (UTC, bölgesiz `yyyy-MM-ddTHH:mm:ss` metni; bölgeli de olabilir) → epoch ms ya da null. Bölgesiz metin
 * UTC sayılır (tarayıcının yerel saati DEĞİL).
 */
export function parseUtcText(text) {
  if (typeof text !== 'string' || !text.trim()) return null
  const s = text.trim()
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(s)
  const ms = Date.parse(zoned ? s : `${s.slice(0, 19)}Z`)
  return Number.isFinite(ms) ? ms : null
}

/** Epoch ms → İstanbul `{ year, month }`. */
export function istanbulYearMonthOf(ms) {
  const d = new Date(ms + ISTANBUL_OFFSET_MS)
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1 }
}

/** Olay zaman damgasının İstanbul ayı (okunamazsa null). */
export function incidentYearMonth(text) {
  const ms = parseUtcText(text)
  return ms == null ? null : istanbulYearMonthOf(ms)
}

/** `{year, month}` → 'YYYY-MM' (seçici değeri). */
export function monthValue(p) {
  return p?.year && p?.month ? `${p.year}-${String(p.month).padStart(2, '0')}` : ''
}

/** 'YYYY-MM' → `{year, month}` (geçersiz → null). */
export function parseMonthValue(v) {
  const m = /^(\d{4})-(\d{2})$/.exec(String(v || ''))
  if (!m) return null
  const month = Number(m[2])
  return month >= 1 && month <= 12 ? { year: Number(m[1]), month } : null
}

/** Ay karşılaştırması (a < b → negatif). */
export function compareMonths(a, b) {
  return (a.year - b.year) * 12 + (a.month - b.month)
}

/** Şimdiden önceki KAPANMIŞ İstanbul ayları, yeniden eskiye (`count` adet; içinde bulunulan ay dahil değil). */
export function closedMonths(nowMs, count = CLOSED_MONTH_COUNT) {
  const cur = istanbulYearMonthOf(nowMs)
  const out = []
  let y = cur.year
  let m = cur.month
  for (let i = 0; i < count; i++) {
    m -= 1
    if (m === 0) { m = 12; y -= 1 }
    out.push({ year: y, month: m })
  }
  return out
}

/**
 * Ters girişte (olay kaydından rapora) varsayılan ay: olay BİTTİYSE (çözülme zamanı var) olayın BAŞLADIĞI ay — yalnız o ay
 * kapanmışsa (içinde bulunulan ayın raporu hazırlanmaz). Aksi null (kullanıcı seçer).
 */
export function incidentDefaultMonth(incident, nowMs) {
  if (!incident || !parseUtcText(incident.resolved_at)) return null
  const ym = incidentYearMonth(incident.occurred_at)
  if (!ym) return null
  return compareMonths(ym, istanbulYearMonthOf(nowMs)) < 0 ? ym : null
}

/** Olay zaman damgası → İstanbul "gg.aa.yyyy SS:dd" (okunamazsa null). */
export function incidentTimeLabel(text, lang) {
  const ms = parseUtcText(text)
  if (ms == null) return null
  try {
    return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', {
      day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul',
    }).format(new Date(ms))
  } catch {
    return null
  }
}

// ── Ters giriş: olay kaydından rapora ─────────────────────────────────────────

/** Rapor oluşturulabilen kanal mı (yazma izni + etkin). */
export function canReportOn(ch) {
  if (!ch || ch.active === false) return false
  return ch.can_report != null ? !!ch.can_report : !!ch.can_edit
}

/**
 * Olayın alınabileceği kanallar: rapor yazılabilen etkin kanallardan YALNIZ olayın takımınkiler (sunucu adayları kanalın
 * takımındaki olaylarla sınırlar). Takımı olmayan olay hiçbir kanala alınamaz (sunucu her zaman INCIDENT_OTHER_TEAM der —
 * inceleme 2026-11-20: eskiden her takımın kanalı listeleniyor, yeni rapor boşuna oluşturulup sonra reddediliyordu) → [].
 * Ad sırası Türkçe harmanlamayla.
 */
export function reportableChannels(channels, teamId) {
  const team = positiveId(teamId)
  if (team == null) return []
  const list = (Array.isArray(channels) ? channels : []).filter(canReportOn)
    .filter((ch) => positiveId(ch.team_id) === team)
  const coll = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
  return list.slice().sort((a, b) => coll.compare(String(a.name || ''), String(b.name || '')) || (a.id - b.id))
}

/** Rapor listesinde o ayın raporu (yoksa null). */
export function reportOfMonth(reports, ym) {
  if (!ym) return null
  return (Array.isArray(reports) ? reports : []).find((r) => Number(r?.year) === ym.year && Number(r?.month) === ym.month) || null
}

/** Şablonun anomali (issues) bölümleri `{ id, title }` (başlık ham — çağıran yer tutucuları çözer). */
export function issueSections(template) {
  return (template?.sections || []).filter((s) => s && s.kind === 'issues' && typeof s.id === 'string')
    .map((s) => ({ id: s.id, title: s.title || '' }))
}

/** Rapora gitmenin parametreleri (`?tab=channelreports&te_view=report&te_ch=…&te_rep=…`). */
export function reportParams(reportId, channelId) {
  const out = { te_view: 'report', te_rep: String(reportId) }
  if (channelId != null) out.te_ch = String(channelId)
  return out
}

/** Olay kaydına gitmenin parametreleri (`?tab=incident-history&incident=…`). */
export function incidentParams(incidentId) {
  return { incident: String(incidentId) }
}

const USAGE_STATES = new Set(LINK_STATES)

/** Kullanım yanıtı → satırlar ya da null (okunamadı — "kullanılmıyor" denmez). */
export function usageRows(res) {
  const items = res?.data?.items
  if (!res?.success || !Array.isArray(items)) return null
  return items.map((r) => {
    if (!r || typeof r !== 'object') return null
    const reportId = positiveId(r.report_id)
    if (reportId == null) return null
    return {
      reportId,
      channelId: positiveId(r.channel_id),
      channelName: typeof r.channel_name === 'string' ? r.channel_name : '',
      teamName: typeof r.team_name === 'string' ? r.team_name : '',
      year: Number(r.year) || null,
      month: Number(r.month) || null,
      status: typeof r.status === 'string' ? r.status : null,
      topic: typeof r.topic === 'string' ? r.topic : '',
      state: USAGE_STATES.has(r.state) ? r.state : 'unknown',
    }
  }).filter(Boolean)
}
