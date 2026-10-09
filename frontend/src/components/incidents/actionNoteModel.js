import { SEVERITY_ORDER } from './incidentsModel.js'

/**
 * Gerekçeli eylem penceresinin (ActionNoteDialog) SAF modeli — React'siz, test edilebilir.
 *
 * <p>İçerik: gerekçe kuralı (sunucu `AlertActionNote`'un birebir aynası, `utils/actionNoteRule.js`), eylem başına
 * hazır gerekçe şablonları, şablonu nota ekle/çıkar, Alarm Geçmişi satırı ve Olaylar satırı → ORTAK bağlam biçimi,
 * toplu seçimin önem dağılımı. Bileşen yalnız bu biçimi çizer; iki ekranın farklı alan adları burada birleşir.
 */
export { NOTE_RULE, javaTrim, noteRuleState, isNoteValid } from '../../utils/actionNoteRule.js'

/** Eylemler — sunucu uçları: `/admin/alerts/{id}/acknowledge|resolve` ve `/admin/alerts/bulk`. */
export const ACTIONS = ['ack', 'resolve']
export const normalizeAction = (a) => (a === 'resolve' ? 'resolve' : 'ack')

/**
 * Hazır gerekçeler — `label` çipte görünen KISA ad, `text` nota eklenen cümle (i18n anahtarları). Metinler kuralı TEK
 * BAŞINA sağlayacak uzunlukta yazıldı (test: `actionNoteModel.test.js`); kullanıcı yine de üzerine yazabilir.
 * Sahiplen: mevcut `alh.note.chip1..4` + olay sırasında en sık üç durum (inceleniyor / sağlayıcı / bakım).
 * Çöz: kapanış cümleleri — `alh.note.chip5` ("düzeltme doğrulandı") buraya taşındı (sahiplenmede anlamsızdı).
 */
export const ACK_CHIPS = Object.freeze([
  { id: 'investigating', label: 'actnote.chip.investigating', text: 'actnote.tpl.investigating' },
  { id: 'provider',      label: 'actnote.chip.provider',      text: 'actnote.tpl.provider' },
  { id: 'known',         label: 'actnote.chip.known',         text: 'alh.note.chip1' },
  { id: 'maintenance',   label: 'actnote.chip.maintenance',   text: 'alh.note.chip2' },
  { id: 'thirdParty',    label: 'actnote.chip.thirdParty',    text: 'alh.note.chip4' },
  { id: 'falseAlarm',    label: 'actnote.chip.falseAlarm',    text: 'alh.note.chip3' },
])
export const RESOLVE_CHIPS = Object.freeze([
  { id: 'fixed',             label: 'actnote.chip.fixed',             text: 'actnote.tpl.fixed' },
  { id: 'verified',          label: 'actnote.chip.verified',          text: 'alh.note.chip5' },
  { id: 'falseAlarm',        label: 'actnote.chip.falseAlarm',        text: 'actnote.tpl.falseAlarmResolved' },
  { id: 'maintenanceDone',   label: 'actnote.chip.maintenanceDone',   text: 'actnote.tpl.maintenanceDone' },
  { id: 'providerRecovered', label: 'actnote.chip.providerRecovered', text: 'actnote.tpl.providerRecovered' },
])
export const chipsFor = (action) => (normalizeAction(action) === 'resolve' ? RESOLVE_CHIPS : ACK_CHIPS)

/** Şablon notta var mı (çip "basılı" görünür). */
export function hasTemplate(note, text) {
  const tpl = String(text ?? '').trim()
  return tpl !== '' && String(note ?? '').includes(tpl)
}

// Sondaki boşluk / ayraç — sondan geriye tek geçiş (2026-10-09): eski `/[\s.;,]+$/` ve `/\s+$/` ifadeleri ortasında
// uzun boşluk dizisi olan notta O(N²) geri izliyordu. Sonuç aynı (`\s` ile `trimEnd` aynı karakter kümesidir).
const TRAILING_SEP = /[\s.;,]/
function stripTrailingSep(s) {
  let e = s.length
  while (e > 0 && TRAILING_SEP.test(s[e - 1])) e--
  return e === s.length ? s : s.slice(0, e)
}

/**
 * Çip tıklaması: şablon notta YOKSA eklenir (boşsa tek başına; doluysa noktalamaya göre ". " / " " ile sona), VARSA
 * çıkarılır (yanlış çipe basan geri alabilsin). Çıkarırken birleştirici ayraç da temizlenir, kullanıcının kendi
 * yazdığı metne dokunulmaz.
 */
export function applyTemplate(note, text) {
  const cur = String(note ?? '')
  const tpl = String(text ?? '').trim()
  if (!tpl) return cur
  const at = cur.indexOf(tpl)
  if (at >= 0) {
    let before = cur.slice(0, at)
    let after = cur.slice(at + tpl.length).replace(/^[.;,]?[ \t]*/, '')
    if (!after.trim()) { before = stripTrailingSep(before); after = '' }
    return before + after
  }
  const base = cur.trimEnd()
  if (!base) return tpl
  return base + (/[.!?…;:,]$/.test(base) ? ' ' : '. ') + tpl
}

const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v))

/**
 * Teslim sayıları — iki ekranın ORTAK okuyucusu: Alarm Geçmişi ve Olaylar satırı aynı adları taşır
 * (`email_sent_count` / `email_failed_count`, push için `{sent, failed, …}` özeti). Alan yoksa (sunucu döndürmedi,
 * ya da başka ekibin olayı — IncidentsController yalnız kendi satıra yazar) `null`: uydurma sıfır yok, satır çizilmez.
 */
function mailOf(row) {
  const sent = num(row.email_sent_count)
  const failed = num(row.email_failed_count)
  return sent == null && failed == null ? null : { sent: sent ?? 0, failed: failed ?? 0 }
}
function pushOf(p) {
  return p && typeof p === 'object' && (p.sent != null || p.failed != null)
    ? { sent: Number(p.sent ?? 0), failed: Number(p.failed ?? 0) }
    : null
}

/**
 * ORTAK bağlam biçimi — pencere yalnız bunu çizer.
 * @typedef {{ id, kind: 'alert'|'incident', title: string, subtitle: ?string, alertType: ?string, level: ?string,
 *   teamId: ?number, teamName: ?string, openedAt: ?string, message: ?string, acknowledged: boolean, ackBy: ?string,
 *   ackAt: ?string, mail: ?{sent:number, failed:number}, push: ?{sent:number, failed:number}, nocCalls: number }} ActionContext
 */

/**
 * Alarm Geçmişi satırı → bağlam. `teamName` sayfanın takım dizininden (yoksa envanterin SY takımı), `push` sayfanın
 * `push_summary`'sinden. Sayılar yalnız sunucu döndürdüyse (uydurma sıfır yok).
 */
export function contextFromAlert(a, { teamName = null, push = null } = {}) {
  if (!a) return null
  const teamId = a.team_id ?? (a.sy_team_id ?? null)
  return {
    id: a.id,
    kind: 'alert',
    title: a.domain || '—',
    subtitle: null,
    alertType: a.alert_type || null,
    level: a.alert_level || null,
    teamId,
    teamName: teamName || (a.team_id == null ? a.sy_team_name || null : null),
    openedAt: a.created_at || null,
    message: a.message || null,
    acknowledged: Boolean(a.acknowledged),
    ackBy: a.acknowledged_by || null,
    ackAt: a.acknowledged_at || null,
    mail: mailOf(a),
    push: pushOf(push),
    nocCalls: Math.max(0, num(a.noc_call_count) ?? 0),
  }
}

/**
 * Olaylar satırı → bağlam. Kendi olayında sunucu Alarm Geçmişi'yle AYNI alanları döndürür (sahip, e-posta sayıları,
 * 7/24 arama sayısı; push özeti satırın `push_summary`'sinde — Alarm Geçmişi'nde sayfanınkinde); başka ekibin olayında
 * bu alanlar yok → o satırlar çizilmez. İzleme adı alan adından farklıysa alan adı ikinci satırda.
 */
export function contextFromIncident(inc) {
  if (!inc) return null
  const name = inc.monitor?.name || inc.domain || '—'
  return {
    id: inc.id,
    kind: 'incident',
    title: name,
    subtitle: inc.domain && inc.domain !== name ? inc.domain : null,
    alertType: inc.alert_type || null,
    level: inc.alert_level || null,
    teamId: inc.team_id ?? null,
    teamName: inc.team_name || null,
    openedAt: inc.started_at || null,
    message: inc.message || null,
    acknowledged: Boolean(inc.acknowledged),
    ackBy: inc.acknowledged_by || null,
    ackAt: inc.acknowledged_at || null,
    mail: mailOf(inc),
    push: pushOf(inc.push_summary),
    nocCalls: Math.max(0, num(inc.noc_call_count) ?? 0),
  }
}

/** Toplu seçimin önem dağılımı — KRİTİK → YÜKSEK → UYARI, sonra bilinmeyenler; sayısı 0 olan çizilmez. */
export function levelMix(items) {
  const counts = new Map()
  for (const it of items || []) {
    const lv = String(it?.level || '').toUpperCase() || 'UNKNOWN'
    counts.set(lv, (counts.get(lv) ?? 0) + 1)
  }
  const rank = (lv) => { const i = SEVERITY_ORDER.indexOf(lv); return i < 0 ? SEVERITY_ORDER.length : i }
  return [...counts.entries()].map(([level, count]) => ({ level, count })).sort((a, b) => rank(a.level) - rank(b.level))
}

/** Toplu pencerede adıyla listelenen ilk kayıt sayısı; kalanı "+N daha". */
export const BULK_PREVIEW = 4

/** Mesaj uzun mu (tek satıra sığmayacak) — "tamamını göster" düğmesi yalnız o zaman. jsdom ölçemez; uzunluk sezgisi. */
export const isLongMessage = (msg) => typeof msg === 'string' && (msg.length > 90 || msg.includes('\n'))

/**
 * Pencere kapanınca odağın döneceği yer. Açılıştaki odak bir menü öğesindeyse (KebabMenu portal'ı) menünün TETİĞİ;
 * tetik başarıdan sonra DOM'dan kalkmışsa (onaylanınca "Onayla" düğmesi kaybolur) aynı eylem satırının / kartın ilk
 * düğmesi. Açılışta hesaplanır — kapanışta menü artık yok.
 */
export function focusReturnPlan(active, doc = typeof document === 'undefined' ? null : document) {
  if (!active || !doc || active === doc.body) return { trigger: null, containers: [] }
  let trigger = active
  const menu = active.closest?.('[role="menu"]')
  if (menu?.id) {
    const byControls = [...doc.querySelectorAll('[aria-controls]')].find((el) => el.getAttribute('aria-controls') === menu.id)
    if (byControls) trigger = byControls
  }
  const containers = []
  for (const sel of ['[data-alert-actions]', '[data-slot="incident-actions"]', '[data-slot="alert-detail-actions"]', '[data-alert-id]', '[data-incident-id]']) {
    const c = trigger.closest?.(sel)
    if (c && !containers.includes(c)) containers.push(c)
  }
  return { trigger, containers }
}

/** Planı uygula: tetik hâlâ belgedeyse ona, değilse ilk canlı kabın ilk etkin düğmesine/bağlantısına. */
export function returnFocus(plan, doc = typeof document === 'undefined' ? null : document) {
  if (!plan || !doc) return false
  const alive = (el) => el && typeof el.focus === 'function' && doc.contains(el)
  if (alive(plan.trigger)) { plan.trigger.focus(); return true }
  for (const c of plan.containers || []) {
    if (!doc.contains(c)) continue
    const target = c.querySelector('[data-alert-open], button:not([disabled]), a[href]')
    if (alive(target)) { target.focus(); return true }
  }
  return false
}
