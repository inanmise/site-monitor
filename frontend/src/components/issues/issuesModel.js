/**
 * Sorun Bildirimleri ekranının SAF modeli (2026-09-27 yeniden tasarım) — React'siz, test edilebilir yardımcılar.
 *
 * <p>İki kaynak, tek ekran:
 * <ul>
 *   <li><b>mine</b> — `GET /api/issue-reports/mine`: kullanıcının kendi kayıtları. Sunucu yalnız durum süzer; kişinin
 *       kaydı az olduğundan liste bir kerede (≤200) çekilir, arama/kaynak/önem/tarih/sıralama İSTEMCİDE uygulanır
 *       (kesin sonuç; 200'ü aşan geçmiş için ekran not düşer).</li>
 *   <li><b>admin</b> — `GET /api/admin/login-issues`: durum/kaynak/önem/metin/tarih SUNUCUDA süzülür, sıra "bildirim
 *       tarihi (yeni → eski)"; "son etkinlik" sıralaması yalnız yüklenen sayfaya uygulanır (ekran bunu söyler).
 *       Referans kodu araması sunucuda yoktur → kodla doğrudan kayıt getirilir (`idFromRef`).</li>
 * </ul>
 * Zamanlar sunucuda ISO UTC (Z'siz); gösterim Europe/Istanbul.
 */

export const STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED']
export const SOURCES = ['USER_REPORT', 'CLIENT_ERROR', 'LOGIN']
export const CATEGORIES = ['BLOCKER', 'ANNOYANCE', 'SUGGESTION']
/** Yorum gövdesi üst sınırı — sunucuyla aynı (IssueReportComment.MAX_BODY). */
export const COMMENT_MAX = 4000
/** Çözüm notu üst sınırı — sunucuyla aynı (LoginIssueService.MAX_NOTE). */
export const NOTE_MAX = 2000
/** Kullanıcı kaynağı tek istekte en çok bu kadar kayıt çeker (sunucu sayfa tavanı 200). */
export const MINE_FETCH_SIZE = 200
/** Derin bağlantı anahtarları — InboxService.ISSUE_PARAM ile aynı; `ir_` öneki useUrlQuerySync'te kayıtlı. */
export const OPEN_PARAM = 'ir_id'
export const VIEW_PARAM = 'ir_view'
const TZ = 'Europe/Istanbul'

// ── Zaman ────────────────────────────────────────────────────────────────────

export function toMs(iso) {
  if (!iso) return NaN
  const hasTz = /[zZ]$|[+-]\d\d:?\d\d$/.test(iso)
  return new Date(hasTz ? iso : iso + 'Z').getTime()
}

/** `YYYY-MM-DD HH:mm` (Istanbul). */
export function fmtDate(iso) {
  if (!iso) return '—'
  const ms = toMs(iso)
  if (!Number.isFinite(ms)) return String(iso).replace('T', ' ').slice(0, 16)
  return new Date(ms).toLocaleString('sv-SE', {
    timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

/** `HH:mm` (Istanbul) — konuşma baloncuğu. */
export function fmtTime(iso) {
  const ms = toMs(iso)
  if (!Number.isFinite(ms)) return ''
  return new Date(ms).toLocaleTimeString('sv-SE', { timeZone: TZ, hour: '2-digit', minute: '2-digit' })
}

/** Istanbul takvim günü `YYYY-MM-DD` — gün ayırıcıları. */
export function istanbulDay(iso) {
  const ms = typeof iso === 'number' ? iso : toMs(iso)
  if (!Number.isFinite(ms)) return ''
  return new Date(ms).toLocaleDateString('sv-SE', { timeZone: TZ })
}

/** Göreli zaman ("3 sa önce"); kesin zaman çağıranın `title`/ipucunda. */
export function fmtRelative(iso, t, now = Date.now()) {
  const ms = now - toMs(iso)
  if (!Number.isFinite(ms)) return '—'
  const m = Math.floor(Math.max(0, ms) / 60000)
  if (m < 1) return t('myIssues.relNow')
  if (m < 60) return t('myIssues.relMin', m)
  const h = Math.floor(m / 60)
  if (h < 24) return t('myIssues.relHour', h)
  return t('myIssues.relDay', Math.floor(h / 24))
}

/** Gün ayırıcı etiketi: Bugün / Dün / "26 Eylül 2026". `locale` dil etiketi (tr-TR | en-GB). */
export function dayLabel(dayKey, t, locale, now = Date.now()) {
  if (!dayKey) return ''
  if (dayKey === istanbulDay(now)) return t('issues.today')
  if (dayKey === istanbulDay(now - 86400000)) return t('issues.yesterday')
  const [y, m, d] = dayKey.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d, 12)).toLocaleDateString(locale, { timeZone: 'UTC', day: 'numeric', month: 'long', year: 'numeric' })
}

/** Yerel gün (`YYYY-MM-DD`) → UTC ISO sınırı (sunucu süzgeci; kayıtlar UTC). endOfDay → 23:59:59. */
export function localDayToUtcIso(dateStr, endOfDay) {
  if (!dateStr) return undefined
  const [y, m, d] = String(dateStr).slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) return undefined
  const dt = new Date(y, m - 1, d, endOfDay ? 23 : 0, endOfDay ? 59 : 0, endOfDay ? 59 : 0)
  return dt.toISOString().slice(0, 19)
}

// ── Etiketler ────────────────────────────────────────────────────────────────

export function statusKey(s) {
  if (s === 'IN_PROGRESS') return 'InProgress'
  if (s === 'RESOLVED') return 'Resolved'
  return 'Open'
}
export function sourceKey(s) {
  if (s === 'CLIENT_ERROR') return 'ClientError'
  if (s === 'USER_REPORT') return 'UserReport'
  return 'Login'
}
export function categoryLabel(c, t) {
  if (c === 'BLOCKER') return t('issue.catBlocker')
  if (c === 'ANNOYANCE') return t('issue.catAnnoyance')
  if (c === 'SUGGESTION') return t('issue.catSuggestion')
  return ''
}

/** `LIR-2026-000041` → 41 (LoginIssueService.refCode biçimi); `#41` / `41` de kabul. Geçersiz → null. */
export function idFromRef(ref) {
  const m = /^\s*(?:LIR-\d{4}-|#)?0*(\d{1,9})\s*$/i.exec(String(ref ?? ''))
  if (!m) return null
  const n = Number(m[1])
  return Number.isInteger(n) && n > 0 ? n : null
}
/** Arama metni bir referans kodu mu? (yalnız rakam da referans sayılır — "41" yazan kaydı arıyordur). */
export function isRefQuery(q) {
  return /^\s*(?:LIR-\d{4}-\d{1,9}|#?\d{1,9})\s*$/i.test(String(q ?? ''))
}

// ── Süzgeçler ────────────────────────────────────────────────────────────────

export const FILTER_DEFAULTS = Object.freeze({
  status: '', source: '', category: '', q: '', since: '', until: '', sort: 'activity', awaiting: false,
})
/** Süzgeç ↔ URL (hepsi `ir_` önekli sayfa-durumu anahtarı; uygulama düzeyi tab/domain/monitor/incident'a DOKUNULMAZ). */
export const URL_KEYS = {
  status: 'ir_status', source: 'ir_src', category: 'ir_cat', q: 'ir_q', since: 'ir_from', until: 'ir_to', sort: 'ir_sort', awaiting: 'ir_new',
}

export function filtersFromUrl(read) {
  const f = { ...FILTER_DEFAULTS }
  const status = read(URL_KEYS.status)
  if (STATUSES.includes(status)) f.status = status
  const source = read(URL_KEYS.source)
  if (SOURCES.includes(source)) f.source = source
  const category = read(URL_KEYS.category)
  if (CATEGORIES.includes(category)) f.category = category
  f.q = read(URL_KEYS.q) || ''
  const since = read(URL_KEYS.since); if (/^\d{4}-\d{2}-\d{2}$/.test(since || '')) f.since = since
  const until = read(URL_KEYS.until); if (/^\d{4}-\d{2}-\d{2}$/.test(until || '')) f.until = until
  if (read(URL_KEYS.sort) === 'reported') f.sort = 'reported'
  f.awaiting = read(URL_KEYS.awaiting) === '1'
  return f
}

/** URL eşlemesi — varsayılan değerler `null` (adres temiz kalır). */
export function filtersToUrl(f) {
  return {
    [URL_KEYS.status]: f.status || null,
    [URL_KEYS.source]: f.source || null,
    [URL_KEYS.category]: f.category || null,
    [URL_KEYS.q]: f.q ? f.q : null,
    [URL_KEYS.since]: f.since || null,
    [URL_KEYS.until]: f.until || null,
    [URL_KEYS.sort]: f.sort === 'reported' ? 'reported' : null,
    [URL_KEYS.awaiting]: f.awaiting ? '1' : null,
  }
}

/** Etkin süzgeç çipleri — her biri kendini kaldıran yamayla. Sıralama süzgeç değildir (çip yok). */
export function activeFilters(f) {
  const out = []
  if (f.status) out.push({ key: 'status', value: f.status, patch: { status: '' } })
  if (f.awaiting) out.push({ key: 'awaiting', value: true, patch: { awaiting: false } })
  if (f.source) out.push({ key: 'source', value: f.source, patch: { source: '' } })
  if (f.category) out.push({ key: 'category', value: f.category, patch: { category: '' } })
  if (f.q) out.push({ key: 'q', value: f.q, patch: { q: '' } })
  if (f.since) out.push({ key: 'since', value: f.since, patch: { since: '' } })
  if (f.until) out.push({ key: 'until', value: f.until, patch: { until: '' } })
  return out
}

/**
 * İstemci süzgeci ("mine" kaynağı). Arama referans kodu, özet ve önem etiketi içinde (büyük/küçük harf duyarsız,
 * Türkçe İ/ı güvenli); tarih sınırları Istanbul günüyle karşılaştırılır (bildirim tarihi).
 */
export function matchesFilters(row, f, t) {
  if (f.status && row.status !== f.status) return false
  if (f.awaiting && !row.unread) return false
  if (f.source && (row.source || 'LOGIN') !== f.source) return false
  if (f.category && row.category !== f.category) return false
  if (f.since || f.until) {
    const day = istanbulDay(row.reportedAt)
    if (f.since && day < f.since) return false
    if (f.until && day > f.until) return false
  }
  const q = String(f.q || '').trim().toLocaleLowerCase('tr')
  if (q) {
    const refId = isRefQuery(q) ? idFromRef(q) : null
    if (refId != null && Number(row.id) === refId) return true
    const hay = [row.refCode, row.messageSummary, t ? categoryLabel(row.category, t) : row.category, row.username]
      .filter(Boolean).join(' \u0001 ').toLocaleLowerCase('tr')
    if (!hay.includes(q)) return false
  }
  return true
}

/** Sıralama: `activity` (son etkinlik, yeni → eski) | `reported` (bildirim tarihi, yeni → eski). Kararlı. */
export function sortRows(rows, sort) {
  const key = sort === 'reported' ? 'reportedAt' : 'lastActivityAt'
  return [...rows].sort((a, b) => {
    const av = toMs(a[key] || a.reportedAt), bv = toMs(b[key] || b.reportedAt)
    return (Number.isFinite(bv) ? bv : 0) - (Number.isFinite(av) ? av : 0) || (Number(b.id) - Number(a.id))
  })
}

/** Yönetici listesi sunucu parametreleri (arama referans koduysa metin araması gönderilmez — kod ayrı çözülür). */
export function serverParams(f) {
  return {
    status: f.status || undefined,
    source: f.source || undefined,
    category: f.category || undefined,
    q: f.q && !isRefQuery(f.q) ? f.q.trim() : undefined,
    since: localDayToUtcIso(f.since, false),
    until: localDayToUtcIso(f.until, true),
  }
}

/** Ayrıntı yanıtını liste satırı biçimine çevirir (yönetici referans kodu araması tek satır gösterir). */
export function detailToRow(d) {
  if (!d) return null
  const msg = String(d.message || '').replace(/\s+/g, ' ').trim()
  return {
    id: d.id, refCode: d.refCode, status: d.status, source: d.source, category: d.category, username: d.username,
    reportedAt: d.reportedAt, resolvedAt: d.resolvedAt, lastActivityAt: d.lastActivityAt || d.reportedAt,
    messageSummary: msg.length > 120 ? msg.slice(0, 120) + '…' : msg, imageCount: d.imageCount ?? (d.images || []).length,
  }
}

// ── Ayrıntı: durum adımları + konuşma ────────────────────────────────────────

/**
 * Durum adımları (Açıldı → İşlemde → Çözümlendi). Kaynak: zaman çizelgesi (STATUS satırları); özellik öncesi eski
 * kayıtlarda satır yoktur → çözümde `resolvedAt/resolvedBy`. Her adım: `state` done | current | todo | skipped
 * (doğrudan çözülen kayıtta "İşlemde" atlanır), `at`, `by`, `byReporter`. `reopened`: bildirenin yeniden açması.
 */
export function buildSteps(detail) {
  const tl = Array.isArray(detail?.timeline) ? detail.timeline : []
  const status = detail?.status || 'OPEN'
  const opened = tl[0] || { status: 'OPEN', at: detail?.reportedAt, by: detail?.username, byReporter: true }
  const rows = tl.slice(1)   // durum geçişleri (ilk girdi "açıldı")
  const lastOf = (st) => { for (let i = rows.length - 1; i >= 0; i--) if (rows[i].status === st) return rows[i]; return null }
  const inProg = lastOf('IN_PROGRESS')
  const resolved = lastOf('RESOLVED') || (status === 'RESOLVED' ? { at: detail?.resolvedAt, by: detail?.resolvedBy } : null)
  // Yeniden açma: son RESOLVED satırından SONRAKİ ilk geçiş (rapor şu an çözülmüş değilse).
  let lastResolvedIdx = -1
  rows.forEach((e, i) => { if (e.status === 'RESOLVED') lastResolvedIdx = i })
  const reopened = status !== 'RESOLVED' && lastResolvedIdx >= 0 ? rows[lastResolvedIdx + 1] || null : null
  const rank = { OPEN: 0, IN_PROGRESS: 1, RESOLVED: 2 }[status] ?? 0
  return {
    steps: [
      { key: 'OPEN', state: rank === 0 ? 'current' : 'done', at: opened.at, by: opened.by, byReporter: true },
      {
        key: 'IN_PROGRESS',
        state: rank === 1 ? 'current' : rank === 2 ? (inProg ? 'done' : 'skipped') : 'todo',
        at: rank >= 1 ? inProg?.at : null, by: rank >= 1 ? inProg?.by : null, byReporter: !!inProg?.byReporter,
      },
      { key: 'RESOLVED', state: rank === 2 ? 'done' : 'todo', at: rank === 2 ? resolved?.at : null, by: rank === 2 ? resolved?.by : null },
    ],
    reopened: reopened ? { at: reopened.at, by: reopened.by, byReporter: !!reopened.byReporter } : null,
  }
}

/**
 * Konuşma akışı: yorumlar + durum geçişleri TEK zaman sırasında, Istanbul gününe göre gruplu.
 * İlk zaman çizelgesi girdisi ("açıldı") akışın başına sistem satırı olarak girer. İç notlar yalnız yönetici
 * yanıtında vardır — model süzmez, gösterim işaretler.
 */
export function buildThread(detail) {
  const tl = Array.isArray(detail?.timeline) ? detail.timeline : []
  const items = []
  tl.forEach((e, i) => items.push({ kind: i === 0 ? 'opened' : 'status', key: `s${i}`, status: e.status, at: e.at, by: e.by, byReporter: !!e.byReporter }))
  if (!tl.length && detail?.reportedAt) items.push({ kind: 'opened', key: 's0', status: 'OPEN', at: detail.reportedAt, by: detail.username, byReporter: true })
  for (const c of detail?.comments || []) items.push({ kind: 'comment', key: `c${c.id}`, ...c, at: c.createdAt })
  items.sort((a, b) => (toMs(a.at) || 0) - (toMs(b.at) || 0) || (a.kind === 'comment') - (b.kind === 'comment'))
  const groups = []
  for (const it of items) {
    const day = istanbulDay(it.at)
    const g = groups[groups.length - 1]
    if (g && g.day === day) g.items.push(it)
    else groups.push({ day, items: [it] })
  }
  return groups
}

/** Yönetici triyajı: yüklenen sayfayı hata imzasına göre gruplar (imza sunucudan — LoginIssueController.signatureOf). */
export function groupBySignature(rows) {
  const map = new Map()
  for (const r of rows) {
    const sig = r.signature || ''
    if (!map.has(sig)) map.set(sig, { sig, count: 0, users: new Set(), sources: new Set(), latestAt: '', latestId: r.id })
    const g = map.get(sig)
    g.count++
    if (r.username) g.users.add(r.username)
    g.sources.add(r.source || 'LOGIN')
    if (!g.latestAt || (r.reportedAt || '') > g.latestAt) { g.latestAt = r.reportedAt || ''; g.latestId = r.id }
  }
  return [...map.values()]
    .map((g) => ({ ...g, users: [...g.users], sources: [...g.sources] }))
    .sort((a, b) => b.count - a.count)
}

/** Otomatik bağlam JSON'unu okunur biçime çevirir (bozuksa olduğu gibi). */
export function prettyJson(s) {
  try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s }
}

/** Mail gövdesindeki `cid:shotN` referanslarını raporun data-URL'leriyle değiştirir (uygulama içi önizleme). */
export function mailBodyWithImages(body, images) {
  if (!body || !images || images.length === 0) return body
  return body.replace(/src=(['"])cid:shot(\d+)\1/gi, (m, quote, idx) => {
    const url = images[Number(idx)]
    return url ? `src=${quote}${url}${quote}` : m
  })
}

/** Mail durumu → rozet tonu + etiket anahtarı. */
export function mailStatusInfo(status) {
  const s = status || ''
  if (s === 'SENT') return { key: 'loginIssues.mailSent', tone: 'success' }
  if (s.startsWith('FAILED')) return { key: 'loginIssues.mailFailed', tone: 'danger' }
  if (s.startsWith('SKIPPED')) return { key: 'loginIssues.mailSkipped', tone: 'muted' }
  if (s.startsWith('QUEUED')) return { key: 'loginIssues.mailQueued', tone: 'warning' }
  return { key: null, label: s || '—', tone: 'muted' }
}

const MAIL_TYPE_KEY = {
  REPORT_ADMIN: 'loginIssues.mailTypeReport', REPORTER_ACK: 'loginIssues.mailTypeAck', RESOLVED: 'loginIssues.mailTypeResolved',
  CLIENT_ERROR_ADMIN: 'loginIssues.mailTypeClientError', USER_REPORT_ADMIN: 'loginIssues.mailTypeUserReport',
  DIGEST: 'loginIssues.mailTypeDigest', ADMIN_REPLY: 'loginIssues.mailTypeAdminReply', STATUS_CHANGE: 'loginIssues.mailTypeStatusChange',
}
export function mailTypeLabel(type, t) {
  return MAIL_TYPE_KEY[type] ? t(MAIL_TYPE_KEY[type]) : (type || '—')
}
