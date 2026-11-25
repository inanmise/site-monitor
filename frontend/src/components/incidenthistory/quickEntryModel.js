/**
 * "Hızlı Giriş" (2026-11-20) — Olay ve Hata Geçmişi'nin saf modeli: hızlı kayıt alanları, tek tıkla durum güncelleme,
 * kayıt çoğaltma, özet liste satırlarının markdown bilgisi ve sayfa kısayollarının uygunluğu. DOM'suz sınanır
 * (`quickEntryModel.test.js`).
 */
import { isTypingTarget } from '../../utils/keyboardShortcuts.js'
import { toIso } from '../../utils/smartDateTime.js'
import {
  EMPTY_FORM, MARKDOWN_KEYS, SEVERITIES, STATUSES, CATEGORIES, formFromRow, editPayload, validateIncident, resolvedNowIso,
} from './incidentHistoryModel.js'

// ── Hızlı kayıt ───────────────────────────────────────────────────────────────────────────────────────────

/** Hızlı kayıtta görünen alanlar (sıra = ekrandaki sıra). Gerisi "Tüm alanları göster" ile açılır. */
export const QUICK_FIELDS = Object.freeze(['title', 'occurred_at', 'team_id', 'severity', 'status'])

/** Hata nesnesinde hızlı görünümün GÖSTERMEDİĞİ bir alan var mı (varsa tam görünüme geçilir). */
export function hasHiddenErrors(errors) {
  return Object.keys(errors || {}).some((k) => !QUICK_FIELDS.includes(k))
}

/** Şimdi, dakikaya AŞAĞI yuvarlanmış UTC ISO (bölge eki yok) — oluş zamanı gelecekte olamaz. */
export function nowMinuteIso(now = new Date()) {
  const ms = now instanceof Date ? now.getTime() : Number(now)
  if (!Number.isFinite(ms)) return ''
  return toIso(new Date(Math.floor(ms / 60000) * 60000))
}

/**
 * Hızlı kayıt penceresinin başlangıç formu: oluş zamanı `nowIso` (çağıran ŞİMDİ'yi dakikaya aşağı yuvarlanmış UTC ISO
 * olarak verir), takım listesinde TEK takım varsa o takım seçili.
 */
export function quickInitial(teams, nowIso) {
  const out = { ...EMPTY_FORM, occurred_at: nowIso || '' }
  const list = Array.isArray(teams) ? teams.filter((tm) => tm && tm.id != null) : []
  if (list.length === 1) {
    out.team_id = String(list[0].id)
    out.team_name = list[0].name || ''
  }
  return out
}

// ── Tek tıkla durum güncelleme ────────────────────────────────────────────────────────────────────────────

/** Akıştaki sonraki durum (AÇIK → İNCELENİYOR → HAFİFLETİLDİ → ÇÖZÜLDÜ); son durumda / bilinmeyende null. */
export function nextStatus(status) {
  const i = STATUSES.indexOf(status)
  return i >= 0 && i < STATUSES.length - 1 ? STATUSES[i + 1] : null
}

/** Seçilebilecek durumlar: şimdiki HARİÇ hepsi, akış sırasında. */
export function statusChoices(status) {
  return STATUSES.filter((s) => s !== status)
}

/** Durum değişikliğinin dokunabileceği alanlar — doğrulama hataları yalnız bunlardan gösterilir. */
const STATUS_KEYS = ['status', 'resolved_at', 'detected_at', 'duration_minutes']

/**
 * Durum değişikliğinin formu: kayıt + yeni durum. ÇÖZÜLDÜ'ye geçişte çözülme zamanı `resolvedAt` (verilmezse kayıttaki,
 * o da yoksa `resolvedNowIso`); ÇÖZÜLDÜ'den başka duruma geçişte `clearResolved` seçiliyse çözülme zamanı silinir (süre
 * gönderilmez — sunucu hesapladığı süreyi kendisi siler, elle girilmiş süre kalır). `sendMail` → "Mail gönder".
 */
export function statusChangeForm(record, target, { resolvedAt, clearResolved = false, sendMail = false } = {}, now = new Date()) {
  const f = formFromRow(record)
  f.status = target
  if (target === 'RESOLVED') {
    f.resolved_at = resolvedAt || record?.resolved_at || resolvedNowIso(f, now)
  } else if (record?.status === 'RESOLVED' && clearResolved) {
    f.resolved_at = ''
  }
  if (sendMail) f.send_notification = true
  return f
}

/**
 * Durum değişikliği isteği: `{ body, changed, errors }`. `errors` yalnız durum / zaman alanlarından (kayıtta zaten duran ve
 * bu işlemle ilgisi olmayan eski değer işlemi kilitlemez). Gövde = yalnız DEĞİŞEN alanlar + `version` (`editPayload`).
 */
export function statusChangeRequest(record, target, opts = {}, now = new Date()) {
  const f = statusChangeForm(record, target, opts, now)
  const all = validateIncident(f, record)
  const errors = {}
  for (const k of STATUS_KEYS) if (all[k]) errors[k] = all[k]
  const { body, changed } = editPayload(f, record)
  return { body, changed, errors, form: f }
}

// ── Çoğalt ────────────────────────────────────────────────────────────────────────────────────────────────

/** Çoğaltmada kopyalanan alanlar. Zamanlar, süre, durum, kök neden, çözüm, iş etkisi, sayılar, SLA, hata bütçesi, teknik
 *  hata adedi, kesinti bilgisi, alarm bağı ve görseller KOPYALANMAZ. */
export const COPY_KEYS = Object.freeze([
  'title', 'severity', 'category', 'problem_types', 'channel', 'service', 'affected_services', 'affected_app',
  'affected_systems', 'error_code', 'function_code', 'channel_code', 'tags', 'description', 'runbook_url',
])

const INCIDENT_IMAGE_PATH = '/api/incidents/images/'

/** Görsel adresi bu uygulamanın olay görseli mi (göreli ya da aynı yolun tam adresi). */
function isIncidentImageUrl(url) {
  const u = String(url ?? '').trim()
  if (u.startsWith(INCIDENT_IMAGE_PATH)) return true
  const lower = u.toLowerCase()
  const scheme = lower.startsWith('https://') ? 8 : lower.startsWith('http://') ? 7 : -1
  if (scheme < 0) return false
  const slash = u.indexOf('/', scheme)
  return slash > scheme && u.startsWith(INCIDENT_IMAGE_PATH, slash)
}

/**
 * Markdown'dan bu kaydın görsellerini (`![ad](/api/incidents/images/<id>)`) çıkarır — başka bir kaydın görseli kopyaya
 * bağlanamaz. Görselin hemen üstündeki `**ad**` başlık satırı (araç çubuğunun eklediği) da silinir. Doğrusal tarama.
 * Dönüş `{ text, removed }`.
 */
export function stripIncidentImages(md) {
  const s = String(md ?? '')
  let out = ''
  let from = 0
  let removed = 0
  let p = s.indexOf('![', 0)
  // Bir önceki aramada bulunan `]`: arada `]` olmadığından, ondan önce başlayan sonraki `![` için de ilk `]` odur —
  // `![` çok, `]` uzakta olan metinde aynı uzak `]`'ı yeniden aramak (karesel) önlenir.
  let lastClose = -1
  while (p >= 0) {
    const close = lastClose >= p + 2 ? lastClose : s.indexOf(']', p + 2)
    if (close < 0) break
    lastClose = close
    if (s[close + 1] !== '(') { p = s.indexOf('![', p + 2); continue }
    const paren = s.indexOf(')', close + 2)
    if (paren < 0) break
    if (!isIncidentImageUrl(s.slice(close + 2, paren))) { p = s.indexOf('![', paren + 1); continue }
    let chunk = s.slice(from, p)
    const caption = `**${s.slice(p + 2, close)}**`
    let end = chunk.length
    while (end > 0 && chunk.charCodeAt(end - 1) === 10) end--          // sondaki satır sonları (doğrusal)
    if (chunk.slice(0, end).endsWith(caption)) chunk = chunk.slice(0, end - caption.length)
    out += chunk
    from = paren + 1
    removed++
    p = s.indexOf('![', from)
  }
  return { text: out + s.slice(from), removed }
}

/**
 * Kaydın çoğaltma formu (yeni kayıt). Takım yalnız formda seçilebilen takımlardan biriyse kopyalanır (`teamDropped`).
 * Takım listesi BİLİNMİYORSA (`known: false` — yükleniyor / okunamadı) kaynak takım korunur, ret yazılmaz (kaydederken
 * sunucu karar verir); yalnız BİLİNEN ve takımı içermeyen liste takımı düşürür.
 * Önem / kategori geçersizse varsayılan kalır; durum her zaman varsayılan (AÇIK). Dönüş `{ form, imagesRemoved, teamDropped }`.
 */
export function duplicateForm(record, teams, { known = true } = {}) {
  const r = record && typeof record === 'object' ? record : {}
  const out = { ...EMPTY_FORM }
  for (const k of COPY_KEYS) if (r[k] != null) out[k] = r[k]
  if (!SEVERITIES.includes(out.severity)) out.severity = EMPTY_FORM.severity
  if (!CATEGORIES.includes(out.category)) out.category = EMPTY_FORM.category
  const { text, removed } = stripIncidentImages(out.description)
  out.description = text
  const hasTeam = r.team_id != null && r.team_id !== ''
  const team = hasTeam ? (Array.isArray(teams) ? teams : []).find((tm) => String(tm?.id) === String(r.team_id)) : null
  if (team) {
    out.team_id = String(team.id)
    out.team_name = team.name || r.team_name || ''
  } else if (hasTeam && !known) {
    out.team_id = String(r.team_id)
    out.team_name = r.team_name || ''
  }
  return { form: out, imagesRemoved: removed, teamDropped: hasTeam && !team && known }
}

// ── Özet liste satırları (`fields=summary`) ──────────────────────────────────────────────────────────────

/** Satırın markdown metinleri sunucudan GELMEDİ mi (özet liste)? */
export const markdownOmitted = (r) => r?.markdown_omitted === true

/**
 * Tam kaydı elde tutulanın üzerine yazar; tam kayıt (GET / kayıt yanıtı) markdown taşıdığı için "eksik" işareti kalkar.
 * Gelen kayıt özet satırsa işaret korunur.
 */
export function mergeFull(held, rec) {
  const m = { ...(held || {}), ...(rec || {}) }
  if (!markdownOmitted(rec)) delete m.markdown_omitted
  return m
}

/**
 * Liste yeniden yüklendiğinde: özet satırın markdown'ı, ÖNCEKİ satırlardan aynı kimlik + AYNI sürümlü tam satırdan
 * korunur (kaydettikten sonraki yenileme ayrıntıyı yeniden "yükleniyor"a düşürmesin). Sürüm bilinmiyor / farklıysa işaret
 * kalır (ayrıntı taze okur).
 */
export function keepMarkdown(prevRows, nextRows) {
  if (!Array.isArray(nextRows)) return nextRows
  const byId = new Map()
  for (const p of Array.isArray(prevRows) ? prevRows : []) if (p && p.id != null && !markdownOmitted(p)) byId.set(String(p.id), p)
  if (byId.size === 0) return nextRows
  return nextRows.map((r) => {
    if (!markdownOmitted(r)) return r
    const p = byId.get(String(r.id))
    if (!p || p.version == null || r.version == null || String(p.version) !== String(r.version)) return r
    const m = { ...r }
    for (const k of MARKDOWN_KEYS) m[k] = p[k]
    delete m.markdown_omitted
    return m
  })
}

// ── Sayfa kısayolları (`n` yeni kayıt, `e` açık ayrıntıyı düzenle) ─────────────────────────────────────────

/**
 * Sayfa kısayolu bu tuş olayında çalışabilir mi: değiştirici yok, yazı alanında değil, IME bileşimi yok; açık bir pencere /
 * menü yok — olay AYRINTISI Sheet'i hariç (`data-slot="ih-detail"`; `e` ayrıntı açıkken çalışır).
 */
export function pageKeyEligible(e, doc = typeof document !== 'undefined' ? document : null) {
  if (!e || e.defaultPrevented || e.isComposing || e.keyCode === 229) return false
  if (e.ctrlKey || e.metaKey || e.altKey) return false
  if (isTypingTarget(e.target)) return false
  if (doc && isTypingTarget(doc.activeElement)) return false
  const overlays = doc && typeof doc.querySelectorAll === 'function'
    ? doc.querySelectorAll('[role="dialog"],[role="alertdialog"],[role="menu"]') : []
  for (const el of overlays) if (el.getAttribute?.('data-slot') !== 'ih-detail') return false
  return true
}
