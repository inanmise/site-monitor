/**
 * Nöbet Rutinleri — saf model (2026-11-26). React ve ağa bağlı değildir; gün sayfasının (sunucu `/teams/{id}/day`)
 * gruplanması, ilerleme, geciken / henüz başlamayan kuralları (sunucu saatine göre), iyimser uygulama / geri alma, klavye
 * sırası ve durum tonları burada. Bileşenler yalnız bu işlevleri çağırır; kural tek yerde.
 *
 * Saat: sunucu yanıtı `now` (UTC ISO) taşır. İstemci saati kayık olabilir → `serverClock` alım anındaki farkı saklar, sonraki
 * hesaplar `serverNowMs(clock)` ile sunucu saatini sürer. Gün sınırları Europe/Istanbul (sabit UTC+3, yaz saati yok).
 */

/** Gün durum kodları (sunucu §1.6; kalıcı). Wire-only kodlar dahil. */
export const DAY_STATUSES = Object.freeze([
  'OPEN', 'COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'NONE', 'NOT_WORKDAY', 'FUTURE', 'PAUSED',
])
/** Madde durumları (kalıcı). */
export const ITEM_STATES = Object.freeze(['PENDING', 'DONE', 'SKIPPED', 'MISSED'])
/** Dönem türleri — gün sayfasındaki bant sırası. */
export const PERIOD_KINDS = Object.freeze(['DAY', 'WEEK', 'MONTH'])
/** Sorun önemleri (düşükten yükseğe). */
export const SEVERITIES = Object.freeze(['LOW', 'MEDIUM', 'HIGH'])
/** Görünüm anahtarları (`or_view`; `today` varsayılan, adrese yazılmaz). */
export const VIEWS = Object.freeze(['today', 'week', 'history', 'stats', 'template', 'settings'])
/** Atlama gerekçesi sınırları (sunucu: 3–300). */
export const SKIP_REASON_MIN = 3
export const SKIP_REASON_MAX = 300
/** Not sınırları (sunucu). */
export const ITEM_NOTE_MAX = 1000
export const DAY_NOTE_MAX = 4000
export const PROBLEM_NOTE_MIN = 3
export const PROBLEM_NOTE_MAX = 4000
export const MAX_IMAGES_PER_PROBLEM = 4
/** Düzenleme penceresi (sunucu `EDIT_WINDOW_DAYS`). */
export const EDIT_WINDOW_DAYS = 7

const ISTANBUL_OFFSET_MS = 3 * 60 * 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const HM_RE = /^([01]\d|2[0-3]):([0-5]\d)$/

/** `DAY:2026-11-26:t-k3f9b2` — sunucunun ITEM `key` biçimi. */
export function itemKey(kind, periodKey, taskId) {
  return `${kind}:${periodKey}:${taskId}`
}

/** Madde anahtarı (sunucu `key`'i varsa o, yoksa üç parçadan). */
export function keyOf(item) {
  if (!item) return ''
  return item.key || itemKey(item.period_kind, item.period_key, item.task_id)
}

/** Geçerli `yyyy-MM-dd` mi (biçim + gerçek gün). */
export function isIsoDate(s) {
  if (typeof s !== 'string' || !DATE_RE.test(s)) return false
  const d = new Date(`${s}T00:00:00Z`)
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s
}

/** `HH:mm` → dakika; geçersizse null. */
export function hmMinutes(hm) {
  const m = typeof hm === 'string' ? HM_RE.exec(hm) : null
  return m ? Number(m[1]) * 60 + Number(m[2]) : null
}

/** Sunucu saatinin istemci saatine farkı (alım anında). `nowIso` okunamazsa fark 0 (istemci saati). */
export function serverClock(nowIso, receivedAtMs = Date.now()) {
  const t = typeof nowIso === 'string' ? Date.parse(nowIso) : NaN
  return { offsetMs: Number.isFinite(t) ? t - receivedAtMs : 0, known: Number.isFinite(t) }
}

/** Sunucu saatine göre şimdi (ms). */
export function serverNowMs(clock, clientNowMs = Date.now()) {
  return clientNowMs + (clock?.offsetMs || 0)
}

/** Bir an → İstanbul tarihi + saat:dakika + günün dakikası. */
export function istanbulParts(ms) {
  const d = new Date(Number(ms) + ISTANBUL_OFFSET_MS)
  const iso = d.toISOString()
  const date = iso.slice(0, 10)
  const hm = iso.slice(11, 16)
  return { date, hm, minutes: d.getUTCHours() * 60 + d.getUTCMinutes(), weekday: ((d.getUTCDay() + 6) % 7) + 1 }
}

/** `yyyy-MM-dd` + n gün. */
export function addDays(date, n) {
  if (!isIsoDate(date)) return date
  const d = new Date(Date.parse(`${date}T00:00:00Z`) + Math.trunc(n) * DAY_MS)
  return d.toISOString().slice(0, 10)
}

/** ISO haftanın Pazartesi'si. */
export function mondayOf(date) {
  if (!isIsoDate(date)) return date
  const wd = ((new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7) + 1
  return addDays(date, 1 - wd)
}

/** ISO hafta günü (1 = Pazartesi … 7 = Pazar). */
export function isoWeekday(date) {
  if (!isIsoDate(date)) return null
  return ((new Date(`${date}T00:00:00Z`).getUTCDay() + 6) % 7) + 1
}

/** İki tarih arasındaki gün farkı (b − a). */
export function daysBetween(a, b) {
  if (!isIsoDate(a) || !isIsoDate(b)) return null
  return Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / DAY_MS)
}

/** Madde çözüldü mü (DONE ya da SKIPPED). */
export function isResolved(state) {
  return state === 'DONE' || state === 'SKIPPED'
}

/**
 * Madde, `date` gününün tamamlanması için ZORUNLU mu (sunucu R(D)): zorunlu, şablondan kaldırılmamış ve son günü bu gün
 * ya da önce (haftalık / aylık maddeler son günden önce erken yapılabilir ama zorunlu değildir).
 */
export function countsForDay(item, date) {
  if (!item || !item.required || item.removed) return false
  if (!isIsoDate(date) || !isIsoDate(item.due_day)) return !!item.required && !item.removed
  return item.due_day <= date
}

/**
 * Geciken / henüz başlamayan — sunucu kuralının aynısı, canlı sunucu saatiyle (yoklama beklemeden rozet belirir).
 * Geciken: zorunlu + bekleyen (PENDING / MISSED) ve (son gün < bugün ya da son gün bugün ve saat pencere sonunu geçti).
 * Henüz başlamayan: bugün < son gün (erken yapılabilir değil, pencere başı) ya da saat pencere başından önce.
 */
export function itemTiming(item, { today, nowMs } = {}) {
  if (!item) return { overdue: false, notBefore: false }
  const pending = item.state === 'PENDING' || item.state === 'MISSED'
  const hasNow = Number.isFinite(nowMs)
  const due = item.due_day
  let overdue = false
  if (pending && item.required && !item.removed && isIsoDate(due) && isIsoDate(today)) {
    if (due < today) overdue = true
    else if (due === today && hasNow) {
      // Sunucu kuralı saniye hassasiyetinde: `now > gün @ pencere sonu` (10:30:01 gecikmiştir)
      const to = istanbulInstantMs(due, item.window_to)
      overdue = to != null && nowMs > to
    }
  }
  let notBefore = false
  if (pending && isIsoDate(due) && isIsoDate(today) && item.period_kind === 'DAY') {
    if (today < due) notBefore = true
    else if (due === today && hasNow) {
      const from = istanbulInstantMs(due, item.window_from)
      notBefore = from != null && nowMs < from
    }
  }
  return { overdue, notBefore }
}

/**
 * Sunucu anından saat farkı: sunucu − tarayıcı (ms). Yalnız bölgesi yazılı bir an kabul edilir (`…Z` / `±hh:mm`) —
 * bölgesiz değer tarayıcıda yerel saat sayılırdı. Okunamazsa null (çağıran farkı 0 sayar).
 */
export function clockOffsetOf(stamp, nowMs = Date.now()) {
  if (typeof stamp !== 'string' || stamp.length > 40 || !/(?:Z|[+-]\d{2}:?\d{2})$/i.test(stamp)) return null
  const ms = Date.parse(stamp)
  return Number.isFinite(ms) && Number.isFinite(nowMs) ? ms - nowMs : null
}

/** Sunucu anının İstanbul günü (`yyyy-MM-dd`); okunamazsa null. */
export function istanbulDayOfStamp(stamp) {
  const off = clockOffsetOf(stamp, 0)
  return off == null ? null : istanbulParts(off)?.date || null
}

/** `yyyy-MM-dd` günü İstanbul saatiyle `HH:mm` anı (UTC ms; İstanbul sabit UTC+3). Okunamazsa null. */
export function istanbulInstantMs(date, hm) {
  const min = hmMinutes(hm)
  if (min == null || !isIsoDate(date)) return null
  const [y, m, d] = date.split('-').map(Number)
  return Date.UTC(y, m - 1, d, 0, min) - 3 * 3600_000
}

/** Sayfadaki tüm maddeler (bant → bölüm → madde sırası). */
export function allItems(sheet) {
  const out = []
  for (const g of sheet?.groups || []) {
    for (const s of g.sections || []) for (const it of s.items || []) out.push(it)
  }
  return out
}

/**
 * İlerleme (sunucu `progress` biçimi) — iyimser değişiklikten sonra istemcide yeniden sayılır. `overdue` canlı saatle.
 * `problems_open` sunucudan gelir (sorun paneli ayrı sayar).
 */
export function computeProgress(sheet, { nowMs } = {}) {
  const date = sheet?.date
  const today = sheet?.today
  let required = 0
  let done = 0
  let skipped = 0
  let pending = 0
  let overdue = 0
  let optionalPending = 0
  for (const it of allItems(sheet)) {
    if (countsForDay(it, date)) {
      required += 1
      if (it.state === 'DONE') done += 1
      else if (it.state === 'SKIPPED') skipped += 1
      else {
        pending += 1
        if (itemTiming(it, { today, nowMs }).overdue) overdue += 1
      }
    } else if (!it.removed && !isResolved(it.state) && !(it.required && it.due_day > date)) {
      optionalPending += 1
    }
  }
  // Bilinmeyen (null) sayı 0'a dönmez: sunucu sayısı yoksa panelin toplamı, o da yoksa null ("—")
  const rawProblems = sheet?.progress?.problems_open
  const problemsOpen = rawProblems == null ? NaN : Number(rawProblems)
  return {
    required, done, skipped, pending, overdue,
    optional_pending: optionalPending,
    problems_open: Number.isFinite(problemsOpen) ? problemsOpen : (sheet?.open_problems_total ?? null),
  }
}

/** Bölümün bekleyen / çözülen / toplam sayısı (o günün zorunlu maddeleri üzerinden; zorunlu yoksa hepsi). */
export function sectionStats(section, date) {
  const items = (section?.items || []).filter((it) => !it.removed)
  const req = items.filter((it) => countsForDay(it, date))
  const base = req.length ? req : items
  let resolved = 0
  for (const it of base) if (isResolved(it.state)) resolved += 1
  const pendingItems = items.filter((it) => it.state === 'PENDING' || it.state === 'MISSED')
  return { total: base.length, resolved, pending: pendingItems.length, pendingKeys: pendingItems.map(keyOf) }
}

/** Bandın (DAY / WEEK / MONTH) çözülen / toplam sayısı. */
export function bandStats(group, date) {
  let total = 0
  let resolved = 0
  for (const s of group?.sections || []) {
    const st = sectionStats(s, date)
    total += st.total
    resolved += st.resolved
  }
  return { total, resolved }
}

/** Bir maddeyi anahtarına göre bulur. */
export function findItem(sheet, key) {
  for (const it of allItems(sheet)) if (keyOf(it) === key) return it
  return null
}

/**
 * Bir maddeyi değiştirir (değişmez kopya; dokunulmayan bölüm / bant / madde nesneleri AYNI kalır — memo'lu satırlar yeniden
 * çizilmez). `patch` nesne ya da (eski madde → yeni madde) işlevi. Madde yoksa aynı sayfa döner.
 */
export function updateItem(sheet, key, patch) {
  if (!sheet?.groups) return sheet
  let changed = false
  const groups = sheet.groups.map((g) => {
    let gChanged = false
    const sections = (g.sections || []).map((s) => {
      let sChanged = false
      const items = (s.items || []).map((it) => {
        if (keyOf(it) !== key) return it
        const next = typeof patch === 'function' ? patch(it) : { ...it, ...patch }
        if (next === it) return it
        sChanged = true
        return next
      })
      if (!sChanged) return s
      gChanged = true
      return { ...s, items }
    })
    if (!gChanged) return g
    changed = true
    return { ...g, sections }
  })
  return changed ? { ...sheet, groups } : sheet
}

/**
 * İyimser işaret: durumu hemen yazar (kim / ne zaman yerelde "siz · şimdi"). Sunucu yanıtı gelince `replaceItem` ile asıl
 * madde konur; hata olursa `replaceItem(prevItem)` ile geri alınır.
 */
export function applyOptimistic(sheet, key, state, { skipReason = null, me = null, nowIso = null } = {}) {
  return updateItem(sheet, key, (it) => ({
    ...it,
    state,
    skip_reason: state === 'SKIPPED' ? skipReason : null,
    state_by_me: state !== 'PENDING',
    // Ad / an bilinmiyorsa önceki kişinin adı ve saati kalmaz: boş kalır, "Siz" çipi state_by_me'den gelir
    state_by_name: state !== 'PENDING' ? (me || null) : null,
    state_at: state !== 'PENDING' ? (nowIso || null) : null,
    optimistic: true,
  }))
}

/** Sunucudan gelen maddeyi yerine koyar (anahtar eşleşmesiyle). */
export function replaceItem(sheet, item) {
  if (!item) return sheet
  const key = keyOf(item)
  return updateItem(sheet, key, (old) => (sameItem(old, item) ? old : { ...item }))
}

/** İki madde görünür alanlarda aynı mı (gereksiz yeniden çizimi önler). */
export function sameItem(a, b) {
  if (a === b) return true
  if (!a || !b || a.optimistic) return false
  const keys = ['state', 'state_by_name', 'state_at', 'skip_reason', 'note', 'note_version', 'version', 'problems_open',
    'title', 'description', 'removed', 'snapshot_differs', 'item_id', 'window_from', 'window_to', 'due_day', 'required']
  for (const k of keys) if ((a[k] ?? null) !== (b[k] ?? null)) return false
  return JSON.stringify(a.links || []) === JSON.stringify(b.links || [])
    && JSON.stringify(a.signals || []) === JSON.stringify(b.signals || [])
}

/** Madde sürümü (sunucunun iyimser kilit sayacı); satırı henüz olmayan madde −1. */
function itemVersion(it) {
  const v = it?.version
  return v == null || v === '' || !Number.isFinite(Number(v)) ? -1 : Number(v)
}

/**
 * Yoklama birleştirmesi: yeni sayfa esas alınır; şu maddeler ESKİ hâlinde kalır:
 *  - isteği uçuşta olanlar (tekli kuyruk ve toplu istek — `inFlightKeys`),
 *  - yoklama isteği çıktıktan SONRA yazımı tamamlananlar (`settledKeys` — yanıt yazımdan önceki durumu taşır),
 *  - sunucunun onayladığı eski hâli yoklamadakinden DAHA YENİ sürümde olanlar (iyimser olmayan, aynı `item_id`; sürüm
 *    geri gitmez).
 * Değişmeyen maddeler ESKİ nesne referansını korur (memo).
 */
export function mergePolled(prev, next, inFlightKeys = new Set(), settledKeys = null) {
  if (!next) return prev
  if (!prev || prev.team_id !== next.team_id || prev.date !== next.date) return next
  const prevByKey = new Map(allItems(prev).map((it) => [keyOf(it), it]))
  const groups = (next.groups || []).map((g) => ({
    ...g,
    sections: (g.sections || []).map((s) => ({
      ...s,
      items: (s.items || []).map((it) => {
        const k = keyOf(it)
        const old = prevByKey.get(k)
        if (old && inFlightKeys.has(k)) return old
        if (old && settledKeys?.has(k)) return old
        // Aynı satırın (aynı item_id) sürümü geri gitmez; satır yeniden oluştuysa (başka item_id) yeni olan esastır
        if (old && !old.optimistic && old.item_id != null && old.item_id === it.item_id
          && itemVersion(old) > itemVersion(it)) return old
        return old && sameItem(old, it) ? old : it
      }),
    })),
  }))
  return { ...next, groups }
}

/**
 * Klavye sırası: görünen satırların anahtarları (bant → bölüm → madde). Bekleyen süzgeci açıkken çözülmüş maddeler, kapalı
 * bölümlerin maddeleri atlanır.
 */
export function keyboardOrder(sheet, { pendingOnly = false, collapsed = null, keep = null } = {}) {
  const out = []
  for (const g of sheet?.groups || []) {
    for (const s of g.sections || []) {
      if (collapsed && collapsed.has(sectionKey(g.kind, s.id))) continue
      for (const it of s.items || []) {
        if (pendingOnly && !isPendingVisible(it, keep)) continue
        out.push(keyOf(it))
      }
    }
  }
  return out
}

/**
 * Bekleyen süzgecinde görünür mü: bekleyen (PENDING / MISSED) ya da bu görünümde az önce işaretlenip `keep` kümesine alınan
 * (satır elden kaybolup odak ve `j` sırası sıçramasın; süzgeç ya da gün değişince küme boşalır).
 */
export function isPendingVisible(item, keep = null) {
  if (!item) return false
  if (item.state === 'PENDING' || item.state === 'MISSED') return true
  return !!(keep && keep.has(keyOf(item)))
}

/** Bölüm anahtarı (açık / kapalı durumu için; aynı bölüm iki bantta olabilir). */
export function sectionKey(kind, sectionId) {
  return `${kind}:${sectionId}`
}

/** Sonraki / önceki anahtar (sınırda kalır). */
export function stepKey(order, current, dir) {
  if (!order.length) return null
  const i = order.indexOf(current)
  if (i < 0) return dir > 0 ? order[0] : order[order.length - 1]
  const j = Math.min(order.length - 1, Math.max(0, i + dir))
  return order[j]
}

/** Gün durumu → ton (rozet / hücre rengi). Bilinmeyen hiçbir zaman yeşil değildir. */
export function dayStatusTone(status) {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'COMPLETED_PARTIAL': return 'partial'
    case 'INCOMPLETE': return 'warning'
    case 'MISSED': return 'danger'
    case 'OPEN': return 'open'
    default: return 'muted'
  }
}

/** Gün tamamlandı mı (iki tamamlanma kodu). */
export function isCompletedStatus(status) {
  return status === 'COMPLETED' || status === 'COMPLETED_PARTIAL'
}

/** "Günü tamamla" düğmesi kullanılabilir mi. */
export function canComplete(sheet) {
  if (!sheet || !sheet.can_work || !sheet.editable || !sheet.workday) return false
  return !isCompletedStatus(sheet?.day?.status)
}

/** Tamamlama penceresindeki bekleyen zorunlu maddeler (sunucu `pending[]` biçiminde). */
export function pendingRequired(sheet) {
  const out = []
  for (const g of sheet?.groups || []) {
    for (const s of g.sections || []) {
      for (const it of s.items || []) {
        if (countsForDay(it, sheet?.date) && !isResolved(it.state)) {
          out.push({ key: keyOf(it), task_id: it.task_id, period_kind: it.period_kind, period_key: it.period_key,
            title: it.title, section_title: s.title })
        }
      }
    }
  }
  return out
}

/** Durum ilerlemesinin yüzdesi (null = bilinmiyor). Yuvarlama AŞAĞI — %100 ancak hepsi çözülünce. */
export function progressPct(progress) {
  const req = Number(progress?.required)
  if (!Number.isFinite(req) || req <= 0) return null
  const res = Number(progress?.done || 0) + Number(progress?.skipped || 0)
  if (res >= req) return 100
  return Math.floor((res / req) * 100)
}

/**
 * İlerleme kartının durumu: `done` (gün tamamlandı ve hepsi yapıldı), `partial` (tamamlandı, atlanan var), `open` (sürüyor),
 * `unknown` (sayfa yok / iş günü değil / bilinmiyor). Bilinmeyen asla yeşil değildir.
 */
export function progressState(sheet, progress) {
  const status = sheet?.day?.status
  if (status === 'COMPLETED') return 'done'
  if (status === 'COMPLETED_PARTIAL') return 'partial'
  if (!sheet?.workday || progress?.required == null || !Number.isFinite(Number(progress.required))) return 'unknown'
  return 'open'
}

/** Bugünün durumu "üzerinde çalışılan gün" mü (açık ya da henüz satırı yok, iş günü). */
export function isWorkingDay(sheet) {
  return !!sheet?.workday && !isCompletedStatus(sheet?.day?.status) && sheet?.date === sheet?.today
}

/** Önceki iş günü şeridi gösterilsin mi: tamamlanmamış ve bekleyen var. */
export function showYesterday(prev) {
  if (!prev || !isIsoDate(prev.date)) return false
  return ['INCOMPLETE', 'MISSED', 'OPEN', 'NONE'].includes(prev.status) && Number(prev.pending_total) > 0
}

/** Sorunları önem (HIGH önce) + yeniye göre sıralar (sunucu sırasına benzer, iyimser ekleme için). */
export function sortProblems(list) {
  const rank = { HIGH: 0, MEDIUM: 1, LOW: 2 }
  return [...(list || [])].sort((a, b) => (rank[a.severity] ?? 3) - (rank[b.severity] ?? 3)
    || String(b.created_at || '').localeCompare(String(a.created_at || '')) || (b.id || 0) - (a.id || 0))
}

/** Gün sayfası adresinden okunan / yazılan anahtarlar. */
export const URL_KEYS = Object.freeze({
  team: 'or_team', view: 'or_view', day: 'or_day', week: 'or_week', pending: 'or_pending',
  from: 'or_from', to: 'or_to', problem: 'or_problem',
})

/** Adresteki değerleri doğrular (geçersiz → varsayılan). */
export function parseUrlState(search) {
  const p = new URLSearchParams(search || '')
  const teamRaw = p.get(URL_KEYS.team)
  const team = teamRaw && /^\d{1,18}$/.test(teamRaw) ? teamRaw : null
  const viewRaw = p.get(URL_KEYS.view)
  const view = VIEWS.includes(viewRaw) ? viewRaw : 'today'
  const day = isIsoDate(p.get(URL_KEYS.day)) ? p.get(URL_KEYS.day) : null
  const weekRaw = p.get(URL_KEYS.week)
  const week = isIsoDate(weekRaw) && isoWeekday(weekRaw) === 1 ? weekRaw : null
  const pending = p.get(URL_KEYS.pending) === '1'
  const from = isIsoDate(p.get(URL_KEYS.from)) ? p.get(URL_KEYS.from) : null
  const to = isIsoDate(p.get(URL_KEYS.to)) ? p.get(URL_KEYS.to) : null
  const probRaw = p.get(URL_KEYS.problem)
  const problem = probRaw && /^\d{1,18}$/.test(probRaw) ? probRaw : null
  return { team, view, day, week, pending, from, to, problem }
}

/** Bir hatanın sunucu kodu (yoksa null) — `errorInfoOf` yerine burada da okunabilsin diye. */
export function codeOf(res) {
  if (!res || typeof res !== 'object') return null
  const c = res.errorInfo?.code ?? res.code ?? res.error_code
  return typeof c === 'string' && c ? c : null
}

/**
 * İstek FIRLATTIĞINDA (ağ hatası, zaman aşımı) çağıranın işleyeceği yanıt. İstemcinin kendi çevrilmiş metni (`ApiError`,
 * künyesi `errorInfo`) ve kodu korunur; künyesiz bir istisnanın ham tarayıcı metni ("Failed to fetch") taşınmaz — çağıran
 * kendi ağ metnine düşer (bilinmeyen kod = `NETWORK_ERROR`, durum 0 = sonuç belirsiz).
 */
export function thrownResult(e) {
  const info = e && typeof e === 'object' && e.errorInfo && typeof e.errorInfo === 'object' ? e.errorInfo : null
  const code = info && typeof info.code === 'string' && info.code ? info.code : 'NETWORK_ERROR'
  const out = { success: false, status: info && typeof info.status === 'number' ? info.status : 0, code }
  if (info && typeof e.message === 'string' && e.message.trim()) out.error = e.message.trim()
  return out
}

/** Sunucu hata kodları (§4.4) — her biri `or.err.<KOD>` metnine sahiptir. */
export const ERROR_CODES = Object.freeze([
  'ROUTINE_MODULE_HIDDEN', 'TEAM_NOT_FOUND', 'ROUTINE_NOT_CONFIGURED', 'ROUTINE_PAUSED', 'TASK_NOT_FOUND', 'PERIOD_MISMATCH',
  'ITEM_CHANGED', 'NOTE_CHANGED', 'DAY_COMPLETED', 'DAY_NOT_READY', 'DAY_LOCKED', 'DAY_IN_FUTURE', 'NOT_WORKDAY',
  'CONCURRENT_MODIFICATION', 'VERSION_NOT_FOUND', 'PROBLEM_NOT_FOUND', 'PROBLEM_IMAGE_LIMIT', 'IMAGE_NOT_FOUND',
  'INCIDENT_NOT_FOUND', 'HANDOVER_NOT_DUE', 'ASSIGN_NOT_ALLOWED', 'ASSIGNEE_NOT_MEMBER', 'EXPORT_TOO_LARGE', 'EXPORT_BUSY',
  'RATE_LIMITED', 'VALIDATION_FAILED',
])

/** Olay geçmişi türleri (§2.9) — her biri `or.ev.<TÜR>` metnine sahiptir. */
export const EVENT_KINDS = Object.freeze([
  'ITEM_DONE', 'ITEM_PENDING', 'ITEM_SKIPPED', 'ITEM_NOTE', 'SECTION_DONE', 'PROBLEM_OPEN', 'PROBLEM_UPDATE',
  'PROBLEM_RESOLVE', 'PROBLEM_REOPEN', 'PROBLEM_DELETE', 'PROBLEM_IMAGE', 'INCIDENT_LINK', 'DAY_COMPLETE', 'DAY_REOPEN',
  'DAY_NOTE', 'DAY_CLOSE', 'HANDOVER_NOTE', 'HANDOVER_ACK', 'TAKEOVER', 'TAKEOVER_CLEAR',
])

/** Canlı sayaç anahtarları (§6, sunucu `OnCallRoutineSignals.KEYS` sırası). */
export const SIGNAL_KEYS = Object.freeze([
  'open_alerts', 'down_monitors', 'stale_monitors', 'open_incidents', 'open_postmortems', 'overdue_actions', 'expiring_certs',
])

/** Sorumlunun kaynak kodları. */
export const RESPONSIBLE_SOURCES = Object.freeze(['SCHEDULE', 'OVERRIDE', 'TAKEOVER_DAY', 'TAKEOVER_WEEK', 'NONE'])

/** Sunucu hata metni yoksa / ham kodsa kullanılacak çeviri anahtarı. */
export function errorKey(code) {
  return ERROR_CODES.includes(code) ? `or.err.${code}` : null
}

/** Ad baş harfleri (avatar): ilk iki sözcüğün ilk harfi; parantez içi (bölüm / unvan) atlanır. */
export function initialsOf(name) {
  const clean = String(name || '').replace(/\([^)]*\)/g, ' ').trim()
  const parts = clean.split(/\s+/).filter(Boolean)
  if (!parts.length) return '?'
  const first = parts[0][0] || ''
  const last = parts.length > 1 ? parts[parts.length - 1][0] || '' : ''
  return (first + last).toLocaleUpperCase('tr-TR')
}

/** Saat:dakika gösterimi (İstanbul) bir UTC ISO damgadan. */
export function hmOf(iso) {
  const t = typeof iso === 'string' ? Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`) : NaN
  return Number.isFinite(t) ? istanbulParts(t).hm : null
}

/** `dd.MM` / `dd.MM.yyyy` kısa tarih. */
export function shortDate(date, withYear = false) {
  if (!isIsoDate(date)) return date || ''
  const [y, m, d] = date.split('-')
  return withYear ? `${d}.${m}.${y}` : `${d}.${m}`
}


/**
 * Hafta şeridinin gün hapı, gün sayfasındaki canlı sayıyla (tikten sonra): seçili günün `resolved` / `required` / durumu
 * `counts`'tan gelir. Değişen bir şey yoksa AYNI hafta nesnesi döner (memo).
 */
export function weekWithDayCounts(week, date, counts) {
  if (!week || !Array.isArray(week.days) || !date || !counts) return week
  let changed = false
  const days = week.days.map((d) => {
    if (d.date !== date || !d.workday) return d
    const next = {
      ...d,
      resolved: counts.resolved ?? d.resolved,
      required: counts.required ?? d.required,
      status: counts.status || d.status,
    }
    if (next.resolved === d.resolved && next.required === d.required && next.status === d.status) return d
    changed = true
    return next
  })
  return changed ? { ...week, days } : week
}

/** Sunucunun gün yazımını (not / sorun / tik / tamamlama) reddettiği kilit nedenleri (`checkDayWrite`). */
export const DAY_WRITE_LOCKS = Object.freeze(['NOT_CONFIGURED', 'NO_PERMISSION', 'FUTURE', 'TOO_OLD', 'PAUSED'])

/** Bu güne not / sorun yazılabilir mi (çalışma izni + gelecek, 7 günden eski ya da duraklatılmış değil). */
export function dayWritable(sheet) {
  return !!sheet?.can_work && !DAY_WRITE_LOCKS.includes(sheet?.locked_reason)
}

/**
 * Devir notu yazılabilir dönem mi? Sunucu (`periodForKey`) yalnız GEÇERLİ ya da bir ÖNCEKİ devir dönemine yazar. Sunucu
 * `writable` gönderirse o; yoksa dönemin `from` / `to` günü ve sunucunun bugünü ile: gelecek dönem hayır, bugünü içeren
 * dönem evet, geçmiş dönem yalnız hemen önceki ise (dönemler bitişik ve eşit uzunlukta: önceki dönemin son günü ≥ bugün −
 * dönem uzunluğu). Tarihler okunamıyorsa engel yok (sunucu karar verir, ret alan hatasıyla gösterilir).
 */
export function handoverWritable(hc, today) {
  if (!hc || typeof hc !== 'object') return false
  if (typeof hc.writable === 'boolean') return hc.writable
  const { from, to } = hc
  if (!isIsoDate(from) || !isIsoDate(to) || !isIsoDate(today) || to < from) return true
  if (from > today) return false
  if (to >= today) return true
  const len = Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / DAY_MS) + 1
  return to >= addDays(today, -len)
}
