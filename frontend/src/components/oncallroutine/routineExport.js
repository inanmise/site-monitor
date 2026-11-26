import { api } from '../../api/client'
import { downloadBlob } from '../../utils/csvExport.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { buildXlsx, XLSX_MIME } from '../../utils/xlsxWriter.js'
import { DAY_STATUSES, ITEM_STATES, PERIOD_KINDS, SEVERITIES, isIsoDate, istanbulParts } from './routineModel.js'

/**
 * Nöbet Rutinleri — Excel dışa aktarımı (2026-11-26). `RoutineStats` bu modülü TEMBEL yükler (`importWithDeadline`): Excel
 * yazıcısı (fflate) yalnız "Excel'e aktar"a basılınca iner. Veri `GET /teams/{id}/export` (≤ 20 000 madde satırı; fazlası
 * sunucuda 400 `EXPORT_TOO_LARGE`).
 *
 * Sayfalar (arayüz dilinde): Özet, Günler, Görevler (madde satırları), Sorunlar, Görev istatistiği, Kişi istatistiği. Sayılar
 * SAYI hücresi; bilinmeyen değer BOŞ hücre (asla uydurma 0); durum / önem / dönem kodları çevrilir — tanınmayan kod boş
 * kalır, ham kod yazılmaz. Tarihler `yyyy-MM-dd`, zamanlar İstanbul `yyyy-MM-dd HH:mm` (sıralanabilir, iki dilde aynı).
 * Bağlantı adresi yazılmaz (sunucu da göndermez). Hücreler değer olarak yazılır (formül yok — xlsxWriter).
 *
 * `exportRoutineXlsx` sonucu: `{ ok: true, name, truncated, res }` ya da `{ ok: false, code, res }` — tostu çağıran seçer
 * (`RoutineStats.exportOutcome`). Kodlar: sunucu kodu (`EXPORT_TOO_LARGE`, `EXPORT_BUSY`, `RATE_LIMITED`, …),
 * `SESSION_ENDED` (401 — oturum yolu işledi), `REQUEST_ABORTED` / `CANCELLED` (iptal), `DOWNLOAD_FAILED` (tarayıcı indirmeyi
 * başlatamadı), `NETWORK_ERROR`.
 */

const FREQUENCIES = ['DAILY', 'WEEKLY', 'MONTHLY']
const PROBLEM_STATUSES = ['OPEN', 'RESOLVED']

const finite = (v) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))
/** Sayı hücresi; bilinmeyen → boş. */
export const num = (v) => (finite(v) ? Number(v) : null)
/** Metin hücresi; boş / eksik → boş. */
const text = (v) => (v === null || v === undefined ? null : String(v))
/** Bilinen koddan çeviri; tanınmayan kod BOŞ (ham kod yazılmaz). */
const coded = (t, list, prefix, code) => (list.includes(code) ? t(`${prefix}${code}`) : null)
const yesNo = (t, v) => (v === true ? t('or.xl.yes') : v === false ? t('or.xl.no') : null)

/** UTC damgası → İstanbul `yyyy-MM-dd HH:mm`; okunamazsa boş. */
export function stamp(iso) {
  if (typeof iso !== 'string' || !iso) return null
  const ms = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`)
  if (!Number.isFinite(ms)) return null
  const p = istanbulParts(ms)
  return `${p.date} ${p.hm}`
}

const TR_FOLD = { ç: 'c', ğ: 'g', ı: 'i', ö: 'o', ş: 's', ü: 'u' }

/** Takım adından dosya adı parçası: Türkçe harfler sadeleşir, harf / rakam dışı → "-", ≤ 40 karakter; boşsa "takim". */
export function routineFileSlug(name) {
  const lower = String(name ?? '').toLocaleLowerCase('tr-TR')
  let out = ''
  for (const ch of lower) out += TR_FOLD[ch] ?? ch
  const slug = out.normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40).replace(/-+$/g, '')
  return slug || 'takim'
}

/** `nobet-rutini-<takım>-<başlangıç>-<bitiş>.xlsx`. */
export function routineExportFileName(teamName, from, to) {
  const f = isIsoDate(from) ? from : 'baslangic'
  const tt = isIsoDate(to) ? to : 'bitis'
  return `nobet-rutini-${routineFileSlug(teamName)}-${f}-${tt}.xlsx`
}

function summaryRows(data, { t, teamName, from, to }) {
  const stats = data?.stats && typeof data.stats === 'object' ? data.stats : {}
  const days = stats.days && typeof stats.days === 'object' ? stats.days : {}
  const prob = stats.problems && typeof stats.problems === 'object' ? stats.problems : {}
  const sev = prob.by_severity && typeof prob.by_severity === 'object' ? prob.by_severity : {}
  const now = istanbulParts(Date.now())
  return [
    [t('or.xl.col.field'), t('or.xl.col.value')],
    [t('or.xl.sum.team'), text(teamName)],
    [t('or.xl.sum.from'), text(from)],
    [t('or.xl.sum.to'), text(to)],
    [t('or.xl.sum.workdays'), num(stats.workdays)],
    [t('or.status.COMPLETED'), num(days.completed)],
    [t('or.status.COMPLETED_PARTIAL'), num(days.partial)],
    [t('or.status.INCOMPLETE'), num(days.incomplete)],
    [t('or.status.MISSED'), num(days.missed)],
    [t('or.status.OPEN'), num(days.open)],
    [t('or.status.NONE'), num(days.none)],
    [t('or.xl.sum.onTimePct'), num(stats.on_time_pct)],
    [t('or.xl.sum.problemsOpened'), num(prob.opened)],
    [t('or.xl.sum.problemsResolved'), num(prob.resolved)],
    [t('or.xl.sum.problemsOpenNow'), num(prob.open_now)],
    ...SEVERITIES.map((s) => [t('or.xl.sum.problemsBySeverity', t(`or.stats.sev.${s}`)), num(sev[s])]),
    [t('or.xl.sum.medianResolveHours'), num(prob.median_resolve_hours)],
    [t('or.xl.sum.items'), Array.isArray(data?.items) ? data.items.length : null],
    [t('or.xl.sum.truncated'), data?.truncated ? t('or.xl.truncatedYes', num(data.max_rows) ?? '') : t('or.xl.no')],
    [t('or.xl.sum.generatedAt'), `${now.date} ${now.hm}`],
  ]
}

function dayRows(data, t) {
  const head = [t('or.xl.col.date'), t('or.xl.col.workday'), t('or.xl.col.holiday'), t('or.xl.col.status'), t('or.xl.col.required'),
    t('or.xl.col.done'), t('or.xl.col.skipped'), t('or.xl.col.problemsOpened'), t('or.xl.col.responsible'),
    t('or.xl.col.completedAt'), t('or.xl.col.late')]
  const rows = (Array.isArray(data?.days) ? data.days : []).filter((d) => d && isIsoDate(d.date)).map((d) => [
    d.date, yesNo(t, d.workday), text(d.holiday_name), coded(t, DAY_STATUSES, 'or.status.', d.status),
    num(d.required), num(d.done), num(d.skipped), num(d.problems_opened), text(d.responsible_name),
    stamp(d.completed_at), yesNo(t, d.completed_late),
  ])
  return [head, ...rows]
}

function itemRows(data, t) {
  const head = [t('or.xl.col.date'), t('or.xl.col.period'), t('or.xl.col.periodKey'), t('or.xl.col.section'), t('or.xl.col.task'),
    t('or.xl.col.state'), t('or.xl.col.stateBy'), t('or.xl.col.stateAt'), t('or.xl.col.skipReason'), t('or.xl.col.note')]
  const rows = (Array.isArray(data?.items) ? data.items : []).filter(Boolean).map((it) => [
    text(it.date), coded(t, PERIOD_KINDS, 'or.xl.kind.', it.period_kind), text(it.period_key), text(it.section_title),
    text(it.task_title), coded(t, ITEM_STATES, 'or.xl.state.', it.state), text(it.state_by_name), stamp(it.state_at),
    text(it.skip_reason), text(it.note),
  ])
  return [head, ...rows]
}

function incidentText(t, inc) {
  if (!inc || typeof inc !== 'object' || inc.id == null) return null
  if (inc.hidden) return t('or.xl.incidentHidden', inc.id)
  return inc.title ? `#${inc.id} ${inc.title}` : `#${inc.id}`
}

function problemRows(data, t) {
  const head = [t('or.xl.col.problemId'), t('or.xl.col.day'), t('or.xl.col.severity'), t('or.xl.col.problemStatus'),
    t('or.xl.col.section'), t('or.xl.col.task'), t('or.xl.col.note'), t('or.xl.col.resolutionNote'), t('or.xl.col.createdBy'),
    t('or.xl.col.createdAt'), t('or.xl.col.resolvedBy'), t('or.xl.col.resolvedAt'), t('or.xl.col.ageDays'), t('or.xl.col.incident')]
  const rows = (Array.isArray(data?.problems) ? data.problems : []).filter(Boolean).map((p) => [
    num(p.id), text(p.day), coded(t, SEVERITIES, 'or.stats.sev.', p.severity), coded(t, PROBLEM_STATUSES, 'or.xl.pstatus.', p.status),
    text(p.section_title), text(p.task_title), text(p.note), text(p.resolution_note), text(p.created_by_name),
    stamp(p.created_at), text(p.resolved_by_name), stamp(p.resolved_at), num(p.age_days), incidentText(t, p.incident),
  ])
  return [head, ...rows]
}

function taskStatRows(data, t) {
  const head = [t('or.xl.col.task'), t('or.xl.col.section'), t('or.xl.col.frequency'), t('or.xl.col.occurrences'),
    t('or.xl.col.done'), t('or.xl.col.skipped'), t('or.xl.col.missed'), t('or.xl.col.pending'), t('or.xl.col.donePct'),
    t('or.xl.col.problems'), t('or.xl.col.lastDoneBy'), t('or.xl.col.lastDoneAt'), t('or.xl.col.medianTime')]
  const tasks = Array.isArray(data?.stats?.tasks) ? data.stats.tasks : []
  const rows = tasks.filter(Boolean).map((x) => [
    text(x.title), text(x.section_title), coded(t, FREQUENCIES, 'or.freq.', x.frequency), num(x.occurrences), num(x.done),
    num(x.skipped), num(x.missed), num(x.pending), num(x.done_pct), num(x.problems), text(x.last_done_by_name),
    stamp(x.last_done_at), text(x.median_done_time),
  ])
  return [head, ...rows]
}

function peopleStatRows(data, t) {
  const head = [t('or.xl.col.name'), t('or.xl.col.daysResponsible'), t('or.xl.col.daysCompleted'), t('or.xl.col.daysOnTime'),
    t('or.xl.col.itemsDone'), t('or.xl.col.problemsOpened')]
  const people = Array.isArray(data?.stats?.people) ? data.stats.people : []
  const rows = people.filter(Boolean).map((p) => [
    text(p.name), num(p.days_responsible), num(p.days_completed), num(p.days_completed_on_time), num(p.items_done),
    num(p.problems_opened),
  ])
  return [head, ...rows]
}

/** Altı Excel sayfası (xlsxWriter biçimi). Saf — testler doğrudan çağırır. */
export function buildRoutineSheets(data, { t, teamName, from, to }) {
  const sheet = (nameKey, rows, widths, opts = {}) => ({
    name: t(nameKey), rows, widths, freezeHeader: true, autoFilter: rows.length > 1 && opts.filter !== false,
  })
  return [
    sheet('or.xl.sheet.summary', summaryRows(data, { t, teamName, from, to }), [36, 28], { filter: false }),
    sheet('or.xl.sheet.days', dayRows(data, t), [12, 10, 22, 22, 10, 10, 10, 12, 28, 18, 8]),
    sheet('or.xl.sheet.items', itemRows(data, t), [12, 12, 12, 26, 40, 14, 26, 18, 32, 40]),
    sheet('or.xl.sheet.problems', problemRows(data, t), [8, 12, 10, 12, 24, 32, 48, 36, 24, 18, 24, 18, 8, 32]),
    sheet('or.xl.sheet.taskStats', taskStatRows(data, t), [40, 24, 12, 10, 10, 10, 10, 10, 12, 10, 26, 18, 12]),
    sheet('or.xl.sheet.peopleStats', peopleStatRows(data, t), [30, 12, 14, 12, 14, 12]),
  ]
}

/**
 * Veriyi okur, kitabı kurar ve indirir. `opts`: `{ teamId, teamName, from, to, t, lang, signal }` (`lang` metinler `t`
 * üzerinden zaten o dilde — sözleşme için kabul edilir).
 */
export async function exportRoutineXlsx(opts = {}) {
  const { teamId, teamName, from, to, t, signal } = opts
  let res
  try {
    res = await api.oncallRoutines.exportRows(teamId, { from, to, signal })
  } catch (e) {
    return { ok: false, code: errorInfoOf(e)?.code || 'NETWORK_ERROR', res: e }
  }
  if (res === null) return { ok: false, code: 'SESSION_ENDED', res: null }
  if (signal?.aborted) return { ok: false, code: 'CANCELLED', res }
  if (!res || res.success !== true || !res.data || typeof res.data !== 'object') {
    return { ok: false, code: errorInfoOf(res)?.code || null, res }
  }
  const data = res.data
  const f = isIsoDate(data.from) ? data.from : from
  const tt = isIsoDate(data.to) ? data.to : to
  const bytes = buildXlsx(buildRoutineSheets(data, { t, teamName, from: f, to: tt }))
  const name = routineExportFileName(teamName, f, tt)
  const ok = downloadBlob(name, new Blob([bytes], { type: XLSX_MIME }))
  return ok ? { ok: true, name, truncated: !!data.truncated, res } : { ok: false, code: 'DOWNLOAD_FAILED', name, res }
}
