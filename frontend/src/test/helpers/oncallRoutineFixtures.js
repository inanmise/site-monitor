/**
 * Nöbet Rutinleri — ortak test verisi (2026-11-26). Sözleşme §4 biçimleri: gün sayfası (`/teams/{id}/day`), ITEM, PROBLEM,
 * HANDOVER, hafta (`/week`), genel bakış (`/overview`), canlı sayaçlar (`/signals`), kişi listesi (`/people`), şablon +
 * ayar (`/template`). Zaman damgaları UTC ve sonunda `Z` (sunucunun gerçek biçimi).
 *
 * Kurallar: her kurucu YENİ nesne döner (testler birbirinin verisini değiştiremez); gerçek uzunlukta AD adları ve uzun görev
 * başlıkları (adlar kesilmez kuralı); bağlantılar `example.com` (gerçek şirket adresi yok). Sayfa sunucu saatine göre
 * 2026-11-26 Perşembe İstanbul 13:41 — "10:30'a kadar" bölümündeki bekleyen zorunlu görevler GECİKMİŞTİR.
 */

/** Takım (varsayılan) — kısa ad. */
export const TEAM_ID = 12
export const TEAM_NAME = 'Dijital Kanallar SY'
/** İkinci takım — gerçek uzunlukta ad (seçicide sarar). */
export const OTHER_TEAM_ID = 34
export const OTHER_TEAM_NAME = 'Kurumsal Çözümler ve Kanal Entegrasyonu Uygulama Destek Takımı'

/** Bugün (İstanbul) — Perşembe. */
export const TODAY = '2026-11-26'
export const YESTERDAY = '2026-11-25'
/** Sunucu saati: 10:41:02Z = İstanbul 13:41. */
export const NOW = '2026-11-26T10:41:02Z'
/** Bu haftanın Pazartesi'si (hafta / devir dönemi anahtarı). */
export const WEEK_KEY = '2026-11-23'
export const WEEK_TO = '2026-11-29'
export const MONTH_KEY = '2026-11'
/** Önceki devir dönemi. */
export const PREV_WEEK_KEY = '2026-11-16'

/** Oturumdaki kişi (gerçek uzunlukta AD görünen adı). */
export const ME = Object.freeze({
  user_id: 'u-4f1c2a9e', name: 'Ayşe Yılmaz (Dijital Kanallar / Uygulama Destek Uzmanı)', username: 'N12345',
})
/** Takım arkadaşı. */
export const OTHER = Object.freeze({
  user_id: 'u-7b3d0e51', name: 'Mehmet Demir (Dijital Kanallar / Kıdemli Sistem Uzmanı)', username: 'N67890',
})
/** Üçüncü kişi (atama penceresi). */
export const THIRD = Object.freeze({
  user_id: 'u-91aa02c7', name: 'Zeynep Kaya (Dijital Kanallar / Uygulama Destek Uzmanı Yardımcısı)', username: 'N24680',
})

/** Uzun görev başlığı (sarar, kesilmez). */
export const LONG_TITLE = 'Gece boyunca biriken kritik ve yüksek önemli alarmları, çalışmayan izlemeleri ve gecikmiş '
  + 'sertifika taramalarını kontrol et; gerekiyorsa ilgili takımlara haber ver'

/** Altı bağlantı (biri etiketsiz — sunucu + yol gösterilir). */
const SIX_LINKS = [
  { label: 'Alarm panosu', url: 'https://grafana.example.com/d/alarms?orgId=1' },
  { label: 'Günlük arama', url: 'https://kibana.example.com/app/discover' },
  { label: '', url: 'https://status.example.com/incidents' },
  { label: 'Kuyruk izleme', url: 'https://mq.example.com/queues' },
  { label: 'Sertifika listesi', url: 'https://sitemonitor.example.com/?tab=all' },
  { label: 'Nöbet çizelgesi', url: 'https://wiki.example.com/oncall/schedule' },
]
export function sixLinks() {
  return SIX_LINKS.map((l) => ({ ...l }))
}

/** Görev anahtarları (sunucunun ITEM `key` biçimi). */
export const KEY = Object.freeze({
  dash: `DAY:${TODAY}:t-dash`,
  errors: `DAY:${TODAY}:t-errors`,
  incidents: `DAY:${TODAY}:t-incidents`,
  queue: `DAY:${TODAY}:t-queue`,
  optional: `DAY:${TODAY}:t-capacity`,
  weekly: `WEEK:${WEEK_KEY}:t-weekly-report`,
  monthly: `MONTH:${MONTH_KEY}:t-certs`,
})

const clone = (v) => JSON.parse(JSON.stringify(v))

/** Başarılı yanıt (`api.*` sözleşmesi). */
export function ok(data) {
  return { success: true, status: 200, data }
}

/** Hata yanıtı: `{success:false, status, code, …ek}` (sunucu metni `error` ile verilirse o gösterilir). */
export function fail(status, code, extras = {}) {
  return { success: false, status, code, ...extras }
}

/** Bekletilebilir söz (yanıtı test sırasında elle bırakmak için). */
export function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function defaultPeriodKey(kind) {
  return kind === 'WEEK' ? WEEK_KEY : kind === 'MONTH' ? MONTH_KEY : TODAY
}
function defaultDueDay(kind, periodKey) {
  if (kind === 'WEEK') return '2026-11-27'
  if (kind === 'MONTH') return `${periodKey}-02`
  return periodKey
}

/** ITEM (§4.1). `over` alanları üzerine yazar; `key` (verilmediyse) üç parçadan kurulur. */
export function buildItem(over = {}) {
  const kind = over.period_kind || 'DAY'
  const periodKey = over.period_key || defaultPeriodKey(kind)
  const taskId = over.task_id || 't-task'
  const base = {
    task_id: taskId, item_id: null,
    period_kind: kind, period_key: periodKey, due_day: defaultDueDay(kind, periodKey),
    title: 'Görev', description: '', links: [], signals: [], required: true,
    frequency: kind === 'WEEK' ? 'WEEKLY' : kind === 'MONTH' ? 'MONTHLY' : 'DAILY',
    weekday: null, monthly_rule: null, month_day: null, nth: null,
    window_from: null, window_to: null,
    not_before: false, overdue: false, due_today: kind === 'DAY', due_later: false,
    state: 'PENDING', state_by_name: null, state_by_me: false, state_at: null, skip_reason: null,
    note: '', note_version: 0, note_by_name: null, note_at: null,
    problems_open: 0, removed: false, snapshot_differs: false, version: 1,
  }
  const item = { ...base, ...clone(over) }
  item.period_kind = kind
  item.period_key = periodKey
  item.task_id = taskId
  item.key = over.key || `${kind}:${periodKey}:${taskId}`
  return item
}

/** Bölüm. */
export function buildSection(over = {}, items = []) {
  return {
    id: 's-sec', title: 'Bölüm', window_from: null, window_to: null, overdue: false,
    pending: items.filter((it) => it.state === 'PENDING' || it.state === 'MISSED').length,
    ...clone(over),
    items: items.map((it) => ({ ...it })),
  }
}

/** Bant (DAY / WEEK / MONTH). */
export function buildGroup(kind, sections, periodKey = defaultPeriodKey(kind)) {
  return { kind, period_key: periodKey, sections }
}

/** Günün sorumlusu. */
export function buildResponsible(over = {}) {
  return {
    user_id: ME.user_id, name: ME.name, username: ME.username, active: true, is_me: true,
    source: 'SCHEDULE', schedule_configured: true, schedule_enabled: true, assignment: null, ignored_assignment: null,
    ...clone(over),
  }
}

/** Sorumlunun `assignment` alanı (devir; OnCallRoutineService.responsibleWire ile aynı anahtarlar). */
export function buildAssignment(over = {}) {
  return {
    scope: 'WEEK', key: WEEK_KEY, note: null, created_by_name: OTHER.name, created_at: '2026-11-24T07:10:00Z',
    ...clone(over),
  }
}

/** Önceki dönemin devri (HANDOVER). */
export function buildHandover(over = {}) {
  return {
    period_key: PREV_WEEK_KEY, from: PREV_WEEK_KEY, to: '2026-11-22', show: true,
    responsible_names: [OTHER.name],
    note: 'Ödeme kuyruğunda gece yarısı birikme oldu; sabah ilk iş kontrol edin.', note_by_name: OTHER.name,
    note_at: '2026-11-22T14:30:00Z',
    summary: {
      workdays: 5, completed: 4, partial: 1, incomplete: 0, missed: 0, skipped_items: 2, missed_items: 0,
      problems_opened: 3, problems_open: 2,
      day_notes: [{ date: '2026-11-20', note: 'Bakım penceresi uzadı.', by: OTHER.name }],
    },
    summary_ready: true, acknowledged: false, ack_by_name: null, ack_at: null, can_ack: true,
    ...clone(over),
  }
}

/** Geçerli devir dönemi ("Sonraki nöbetçiye not"). */
export function buildHandoverCurrent(over = {}) {
  return {
    period_key: WEEK_KEY, from: WEEK_KEY, to: WEEK_TO, note: '', note_version: 0, note_by_name: null, note_at: null,
    ...clone(over),
  }
}

/** PROBLEM (§4.1). */
export function buildProblem(over = {}) {
  return {
    id: 55, status: 'OPEN', severity: 'HIGH', note: 'Ödeme kuyruğunda 1.240 mesaj bekliyor; tüketici yeniden başlatıldı.',
    resolution_note: null, task_id: 't-dash', task_title: LONG_TITLE, section_title: 'Gün başlangıcı',
    day: '2026-11-24', age_days: 2, created_by_name: OTHER.name, created_at: '2026-11-24T06:42:00Z',
    updated_by_name: null, updated_at: null, resolved_by_name: null, resolved_at: null, incident: null, images: [],
    version: 1, can_edit: true, can_delete: false,
    ...clone(over),
  }
}

/** Hafta şeridi (gün sayfasındaki `week`). */
export function buildWeekStrip(over = {}) {
  return {
    key: WEEK_KEY, from: WEEK_KEY, to: WEEK_TO,
    days: [
      { date: '2026-11-23', workday: true, status: 'COMPLETED', required: 5, resolved: 5, problems_opened: 0, today: false },
      { date: '2026-11-24', workday: true, status: 'COMPLETED_PARTIAL', required: 5, resolved: 5, problems_opened: 1, today: false },
      { date: '2026-11-25', workday: true, status: 'INCOMPLETE', required: 5, resolved: 1, problems_opened: 2, today: false },
      { date: TODAY, workday: true, status: 'OPEN', required: 5, resolved: 3, problems_opened: 0, today: true },
      { date: '2026-11-27', workday: true, status: 'FUTURE', required: null, resolved: null, problems_opened: 0, today: false },
      { date: '2026-11-28', workday: false, status: 'NOT_WORKDAY', required: null, resolved: null, problems_opened: 0, today: false },
      { date: WEEK_TO, workday: false, status: 'NOT_WORKDAY', required: null, resolved: null, problems_opened: 0, today: false },
    ],
    ...clone(over),
  }
}

/** Gün sayfasının görev bantları (DAY + WEEK + MONTH). */
export function buildDayGroups() {
  const start = buildSection({ id: 's-start', title: 'Gün başlangıcı', window_to: '10:30', overdue: true }, [
    buildItem({
      task_id: 't-dash', item_id: 991, title: LONG_TITLE,
      description: 'Grafana panosundaki kırmızı paneller ve Alarm Geçmişi\'ndeki açık alarmlar.',
      links: sixLinks(), signals: ['open_alerts', 'down_monitors', 'stale_monitors'], window_to: '10:30', overdue: true,
      problems_open: 1,
    }),
    buildItem({ task_id: 't-errors', item_id: 992, title: 'Gece hata kayıtlarını incele', window_to: '10:30', overdue: true }),
    buildItem({
      task_id: 't-incidents', item_id: 993, title: 'Açık olay kayıtlarını gözden geçir', window_to: '10:30',
      signals: ['open_incidents'], state: 'DONE', state_by_name: ME.name, state_by_me: true, state_at: '2026-11-26T06:42:00Z',
      version: 2,
    }),
  ])
  const day = buildSection({ id: 's-day', title: 'Gün içi' }, [
    buildItem({
      task_id: 't-queue', item_id: 994, title: 'Mesaj kuyruklarını kontrol et', state: 'SKIPPED',
      state_by_name: OTHER.name, state_at: '2026-11-26T08:05:00Z', skip_reason: 'Bakım penceresi nedeniyle kuyruk kapalı',
      version: 2,
    }),
    buildItem({ task_id: 't-capacity', title: 'Kapasite notlarını güncelle', required: false }),
  ])
  const week = buildSection({ id: 's-week', title: 'Haftalık kontroller' }, [
    buildItem({ task_id: 't-weekly-report', period_kind: 'WEEK', title: 'Haftalık erişilebilirlik raporunu onaya gönder', due_today: false, due_later: true }),
  ])
  const month = buildSection({ id: 's-month', title: 'Aylık kontroller' }, [
    buildItem({
      task_id: 't-certs', period_kind: 'MONTH', title: 'Süresi 30 gün içinde dolacak sertifikaları listele',
      signals: ['expiring_certs'], state: 'DONE', state_by_name: OTHER.name, state_at: '2026-11-02T07:30:00Z', version: 2,
      due_today: false,
    }),
  ])
  return [buildGroup('DAY', [start, day]), buildGroup('WEEK', [week]), buildGroup('MONTH', [month])]
}

/** Gün sayfası (§4.1 `/teams/{id}/day`). Varsayılan: bugün, açık, düzenlenebilir, nöbetçi = ben. */
export function buildDaySheet(over = {}) {
  const sheet = {
    team_id: TEAM_ID, team_name: TEAM_NAME, configured: true, enabled: true, template_unreadable: false,
    date: TODAY, today: TODAY, now: NOW, weekday: 4,
    workday: true, non_workday_reason: null, holiday_name: null,
    recorded: true, editable: true, locked_reason: null,
    can_work: true, can_edit_template: true, can_manage: false, can_reopen: false,
    template_version: 14, settings: { day_start: '09:00', reminders: { times: ['16:30', '17:30'] } },
    responsible: buildResponsible(),
    day: {
      status: 'OPEN', version: 3, completed_by_name: null, completed_at: null, completion_seq: 0, completed_late: false,
      note: '', note_version: 0, note_by_name: null, note_at: null,
    },
    progress: { required: 5, done: 2, skipped: 1, pending: 2, overdue: 2, optional_pending: 1, problems_open: 1 },
    week: buildWeekStrip(),
    groups: buildDayGroups(),
    previous_workday: {
      date: YESTERDAY, status: 'INCOMPLETE', pending_total: 4,
      pending: [
        { task_id: 't-dash', title: 'Alarm panosunu kontrol et' },
        { task_id: 't-errors', title: 'Gece hata kayıtlarını incele' },
        { task_id: 't-queue', title: 'Mesaj kuyruklarını kontrol et' },
      ],
    },
    open_problems: [buildProblem()], open_problems_total: 1,
    handover: null,
    handover_current: buildHandoverCurrent(),
  }
  const o = clone(over)
  const out = { ...sheet, ...o }
  if (o.day) out.day = { ...sheet.day, ...o.day }
  return out
}

/** Tamamlanmış gün (kilitli). */
export function buildCompletedSheet(over = {}) {
  return buildDaySheet({
    editable: false, locked_reason: 'COMPLETED', can_reopen: true,
    day: { status: 'COMPLETED', version: 4, completed_by_name: OTHER.name, completed_at: '2026-11-26T10:40:00Z' },
    ...over,
  })
}

/** `/day` yanıtı. */
export function dayResponse(over = {}) {
  return ok(buildDaySheet(over))
}

/** Sayfadaki maddeyi görev kimliğiyle bulur. */
export function sheetItem(sheet, taskId) {
  for (const g of sheet?.groups || []) {
    for (const s of g.sections || []) for (const it of s.items || []) if (it.task_id === taskId) return it
  }
  return null
}

/** Maddesi değişmiş KOPYA sayfa. */
export function withItem(sheet, taskId, patch) {
  const copy = clone(sheet)
  for (const g of copy.groups || []) {
    for (const s of g.sections || []) {
      s.items = (s.items || []).map((it) => (it.task_id === taskId ? { ...it, ...clone(patch) } : it))
    }
  }
  return copy
}

/** Sunucunun işaret yanıtındaki madde (`/items/state` → `{item}`). */
export function serverItem(taskId, patch = {}, sheet = buildDaySheet()) {
  return { ...clone(sheetItem(sheet, taskId)), ...clone(patch) }
}

/** `/week` yanıtının verisi (§4.1). */
export function buildWeekResponse(over = {}) {
  const groups = buildDayGroups().filter((g) => g.kind !== 'DAY')
  const data = {
    week: { key: WEEK_KEY, from: WEEK_KEY, to: WEEK_TO },
    days: [
      {
        date: '2026-11-23', workday: true, holiday_name: null, status: 'COMPLETED', required: 5, resolved: 5, done: 5, skipped: 0,
        problems_opened: 0, responsible_name: OTHER.name, completed_by_name: OTHER.name, completed_at: '2026-11-23T13:58:00Z',
        completed_late: false, today: false,
      },
      {
        date: '2026-11-24', workday: true, holiday_name: null, status: 'COMPLETED_PARTIAL', required: 5, resolved: 5, done: 4,
        skipped: 1, problems_opened: 1, responsible_name: ME.name, completed_by_name: ME.name, completed_at: '2026-11-24T15:20:00Z',
        completed_late: true, today: false,
      },
      {
        date: YESTERDAY, workday: true, holiday_name: null, status: 'INCOMPLETE', required: 5, resolved: 1, done: 1, skipped: 0,
        problems_opened: 2, responsible_name: ME.name, completed_by_name: null, completed_at: null, completed_late: false,
        today: false,
      },
      {
        date: TODAY, workday: true, holiday_name: null, status: 'OPEN', required: 5, resolved: 3, done: 2, skipped: 1,
        problems_opened: 0, responsible_name: ME.name, completed_by_name: null, completed_at: null, completed_late: false,
        today: true,
      },
      {
        date: '2026-11-27', workday: true, holiday_name: null, status: 'FUTURE', required: null, resolved: null, done: null,
        skipped: null, problems_opened: 0, responsible_name: ME.name, completed_by_name: null, completed_at: null,
        completed_late: false, today: false,
      },
      {
        date: '2026-11-28', workday: false, holiday_name: null, status: 'NOT_WORKDAY', required: null, resolved: null, done: null,
        skipped: null, problems_opened: 0, responsible_name: null, completed_by_name: null, completed_at: null,
        completed_late: false, today: false,
      },
      {
        date: WEEK_TO, workday: false, holiday_name: null, status: 'NOT_WORKDAY', required: null, resolved: null, done: null,
        skipped: null, problems_opened: 0, responsible_name: null, completed_by_name: null, completed_at: null,
        completed_late: false, today: false,
      },
    ],
    groups,
    handover_current: buildHandoverCurrent(),
    handover_previous: null,
    responsible_by_day: {
      '2026-11-23': { name: OTHER.name, source: 'SCHEDULE', is_me: false },
      '2026-11-24': { name: ME.name, source: 'TAKEOVER_DAY', is_me: true },
      [YESTERDAY]: { name: ME.name, source: 'SCHEDULE', is_me: true },
      [TODAY]: { name: ME.name, source: 'SCHEDULE', is_me: true },
      '2026-11-27': { name: ME.name, source: 'SCHEDULE', is_me: true },
    },
    can_work: true,
  }
  return { ...data, ...clone(over) }
}

/** `/overview` takım satırı. */
export function buildOverviewTeam(over = {}) {
  return {
    team_id: TEAM_ID, team_name: TEAM_NAME, member: true, can_work: true, can_edit_template: true, can_manage: false,
    configured: true, enabled: true, tasks_active: 7,
    today: { date: TODAY, workday: true, status: 'OPEN', required: 5, resolved: 3, overdue: 2, problems_open: 1 },
    responsible: { user_id: ME.user_id, name: ME.name, is_me: true, active: true, source: 'SCHEDULE' },
    ...clone(over),
  }
}

/** `/overview` verisi: iki takım (varsayılan benim takımım). */
export function buildOverview(over = {}) {
  return {
    module_visible: true,
    default_team_id: TEAM_ID,
    teams: [
      buildOverviewTeam(),
      buildOverviewTeam({
        team_id: OTHER_TEAM_ID, team_name: OTHER_TEAM_NAME, member: false, can_work: false, can_edit_template: false,
        today: { date: TODAY, workday: true, status: 'COMPLETED', required: 8, resolved: 8, overdue: 0, problems_open: 0 },
        responsible: { user_id: OTHER.user_id, name: OTHER.name, is_me: false, active: true, source: 'SCHEDULE' },
      }),
    ],
    ...clone(over),
  }
}

/** `/signals` verisi (§6): açık alarm 3 (uyarı), düşen izleme okunamadı, bayat izleme gizli, açık olay kısmi ≥ 12. */
export function buildSignals(over = {}) {
  return {
    team_id: TEAM_ID, generated_at: NOW,
    signals: {
      open_alerts: {
        value: 3, tone: 'warn', partial: false, hidden: false, error: null,
        link: { tab: 'alerthistory', params: { view: 'open', team: String(TEAM_ID) } },
      },
      down_monitors: { value: null, tone: 'neutral', partial: false, hidden: false, error: 'unavailable', link: null },
      stale_monitors: { value: null, partial: false, hidden: true, error: null, link: null },
      open_incidents: {
        value: 12, tone: 'warn', partial: true, hidden: false, error: null,
        link: { tab: 'incident-history', params: { ih_team: String(TEAM_ID), ih_open: '1' } },
      },
      expiring_certs: {
        value: 0, tone: 'ok', partial: false, hidden: false, error: null, link: { tab: 'all', params: { c_win: '30' } },
      },
    },
    ...clone(over),
  }
}

/** `/people` (devralma seçicisi). */
export function buildPeople() {
  return [
    { user_id: ME.user_id, name: ME.name, username: ME.username, is_me: true },
    { user_id: OTHER.user_id, name: OTHER.name, username: OTHER.username, is_me: false },
    { user_id: THIRD.user_id, name: THIRD.name, username: THIRD.username, is_me: false },
  ]
}

/** `/template` yanıtının verisi; `settingsOver` takım ayarının üzerine yazar (ör. `completion_notice`). */
export function buildTemplateResponse(settingsOver = {}) {
  return {
    configured: true, template: { sections: [] }, template_version: 14, template_updated_by_name: OTHER.name,
    template_updated_at: '2026-11-20T09:00:00Z', template_unreadable: false,
    settings: {
      enabled: true, workdays: [1, 2, 3, 4, 5], skip_holidays: true, day_start: '09:00',
      reminders: { enabled: true, times: ['16:30', '17:30'], section_deadlines: true, due_morning: true },
      completion_notice: { enabled: true, mail: true, push: true },
      handover_notice: { enabled: true, mail: true, push: true },
      ...clone(settingsOver),
    },
    settings_version: 2, can_edit_template: true,
  }
}
