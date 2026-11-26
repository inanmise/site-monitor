// Nöbet Rutinleri (2026-11-26) — e2e için DURUMLU sahte sunucu: `/api/oncall-routines/**` sözleşmenin biçimiyle
// (`{ success: true, data, timestamp }`, hata `{ success: false, code, error, …ekler }`) yanıt verir ve her isteği gövdesiyle
// kaydeder. Veriler gerçekçi: uzun AD adları, uzun Türkçe görev başlıkları, 6 bağlantılı görev, canlı sayaçlar, GÜN + HAFTA +
// AY bantları, görselli açık sorun, önceki nöbetin devri. Bütün anlar UTC ve sonunda `Z`; takvim günleri `yyyy-MM-dd`.
//
// Kullanım: önce `mockApi(page)` (monitorMocks.js), SONRA `const st = await mockOncallRoutines(page)` — sonra bağlanan
// rota önce çalışır. `st.requests` = [{ method, path, query, body, png }], `st.unknown` = tanınmayan uçlar (boş kalmalı).
// Duyarlı (responsive) taramalar `mockOncallRoutinesMinimal(page)` ile izin anlık görüntüsüne dokunmadan sayfayı doldurur.
import { deflateSync } from 'node:zlib'

export const OR_TEAM_ID = 41
export const OR_TEAM_NAME = 'Dijital Kanallar Uygulama Destek Takımı (7/24 Nöbet Grubu)'
export const OR_OTHER_TEAM_ID = 42
export const OR_OTHER_TEAM_NAME = 'Ödeme Sistemleri Altyapı ve Entegrasyon Takımı'
export const OR_TODAY = '2026-11-26'                 // Perşembe
export const OR_NOW = '2026-11-26T08:41:02Z'         // İstanbul 11:41 — "Gün başlangıcı" (10:30) gecikmiş
export const OR_WEEK_KEY = '2026-11-23'
export const OR_PREV_WEEK_KEY = '2026-11-16'
export const OR_MONTH_KEY = '2026-11'
export const OR_ROUTINE_SINCE = '2026-03-02'
export const OR_EXPORT_SLUG = 'dijital-kanallar-uygulama-destek-takimi'
const NOW_MINUTES = 11 * 60 + 41

export const OR_ME = { user_id: 3051, name: 'Demo Kullanıcı (Platform Mühendisliği / Kıdemli Sistem Uzmanı)', username: 'demo' }
export const OR_AYSE = { user_id: 2210, name: 'Ayşe Yılmaz (Dijital Kanallar / Uygulama Destek Uzmanı)', username: 'N22100' }
export const OR_MEHMET = { user_id: 2214, name: 'Mehmet Kaya Öztürk (Dijital Kanallar / Kıdemli Uygulama Destek Uzmanı)', username: 'N22140' }
export const OR_SELIN = { user_id: 2002, name: 'Selin Arslan Demirtaş (Dijital Kanallar / Takım Lideri)', username: 'N20020' }
const PEOPLE = [OR_AYSE, OR_ME, OR_MEHMET, OR_SELIN]

export const OR_TITLES = Object.freeze({
  alarm: 'SiteMonitor üzerinden açık alarmların, kapalı izlemelerin ve gece boyunca oluşan kesinti kayıtlarının kontrol edilmesi',
  batch: 'Gece çalışan batch işlerinin (EOD, mutabakat ve raporlama) sonuçlarının kontrol edilmesi',
  mail: 'Ortak posta kutusundaki müşteri şikâyeti ve otomatik uyarı e-postalarının okunması',
  disk: 'Uygulama sunucularında disk, bellek ve bağlantı havuzu doluluk oranlarının kontrol edilmesi',
  cert: 'Süresi 30 gün içinde dolacak sertifikaların listelenmesi ve yenileme kayıtlarının açılması',
  backup: 'Veritabanı yedeklerinin alındığının ve arşiv kopyasının ikinci veri merkezine taşındığının doğrulanması',
  access: 'Kritik sistemlerdeki ayrıcalıklı hesap erişim listelerinin gözden geçirilmesi (Her Cuma)',
  dr: 'Felaket kurtarma (DR) ortamına aylık geçiş tatbikatının planlanması ve sonuç raporunun paylaşılması',
})

export const OR_SIGNAL_KEYS = Object.freeze([
  'open_alerts', 'down_monitors', 'stale_monitors', 'open_incidents', 'open_postmortems', 'overdue_actions', 'expiring_certs',
])

/** `/api/me/permissions` — modül açık, ekip üyesi + yönetici. */
export const OR_PERMS = Object.freeze({
  'oncall_routines.read': { view: true },
  'oncall_routines.work': { view: true, edit: true },
  'oncall_routines.manage': { view: true, edit: true },
  'incidents.view': { view: true },
  'incidents.manage': { view: true, edit: true },
  'oncall.read': { view: true },
  'oncall.manage': { view: true, edit: true },
  'monitoring.read': { view: true },
  'alerts.read': { view: true },
})
export const OR_SCOPE = Object.freeze({
  oncall_routines: true, monitoring_all: false, noc_operator: false, noc_console: false, global_admin: true, channel_reports: true,
})

const HOLIDAYS = Object.freeze({ '2026-10-29': 'Cumhuriyet Bayramı' })
/** Açıkça verilen geçmiş gün durumları; listede olmayan geçmiş iş günü COMPLETED. */
const EXPLICIT_STATUS = Object.freeze({
  '2026-11-25': 'INCOMPLETE', '2026-11-20': 'COMPLETED_PARTIAL', '2026-11-12': 'MISSED',
  '2026-11-04': 'INCOMPLETE', '2026-10-28': 'COMPLETED_PARTIAL',
})

// ── Tarih yardımcıları (UTC günleri) ─────────────────────────────────────────────────────────────────────────────────
const DAY_MS = 86_400_000
const toMs = (d) => Date.parse(`${d}T00:00:00Z`)
const fromMs = (ms) => new Date(ms).toISOString().slice(0, 10)
export const orAddDays = (d, n) => fromMs(toMs(d) + n * DAY_MS)
const weekdayOf = (d) => { const w = new Date(toMs(d)).getUTCDay(); return w === 0 ? 7 : w }
const mondayOf = (d) => orAddDays(d, 1 - weekdayOf(d))
const isDate = (s) => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && fromMs(toMs(s)) === s
const isWorkday = (d) => weekdayOf(d) <= 5 && !HOLIDAYS[d]
const hmMin = (hm) => (typeof hm === 'string' && /^\d{2}:\d{2}$/.test(hm) ? Number(hm.slice(0, 2)) * 60 + Number(hm.slice(3)) : null)
const clone = (v) => JSON.parse(JSON.stringify(v))

// ── PNG (Node, gerçek ve çözülebilir) ────────────────────────────────────────────────────────────────────────────────
const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()
function crc32(buf) {
  let c = -1
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/** `w × h` RGB PNG (renk geçişli; görsel hazırlığının çözme denetiminden geçer). */
export function orPng(w = 64, h = 48) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8           // bit derinliği
  ihdr[9] = 2           // RGB
  const stride = w * 3 + 1
  const raw = Buffer.alloc(stride * h)
  for (let y = 0; y < h; y++) {
    raw[y * stride] = 0
    for (let x = 0; x < w; x++) {
      const p = y * stride + 1 + x * 3
      raw[p] = (x * 4) & 0xff
      raw[p + 1] = (y * 5) & 0xff
      raw[p + 2] = 0x99
    }
  }
  return Buffer.concat([PNG_SIG, pngChunk('IHDR', ihdr), pngChunk('IDAT', deflateSync(raw)), pngChunk('IEND', Buffer.alloc(0))])
}

/** Tampondaki (çok parçalı gövde de olur) ilk PNG'nin imzası, boyutu ve bayt sayısı; yoksa null. */
export function orPngInfo(buf) {
  if (!buf || !buf.length) return null
  const i = buf.indexOf(PNG_SIG)
  if (i < 0 || buf.length < i + 24) return null
  const end = buf.indexOf(Buffer.from('IEND', 'ascii'), i)
  return {
    signature: true,
    ihdr: buf.toString('ascii', i + 12, i + 16) === 'IHDR',
    width: buf.readUInt32BE(i + 16),
    height: buf.readUInt32BE(i + 20),
    bytes: end > 0 ? end + 8 - i : null,
  }
}

// ── Şablon ───────────────────────────────────────────────────────────────────────────────────────────────────────────
function task(id, title, extra = {}) {
  return {
    id, title, description: '', links: [], signals: [], required: true, active: true, frequency: 'DAILY',
    weekday: null, monthly_rule: null, month_day: null, nth: null, window_from: null, window_to: null, ...extra,
  }
}
const ALARM_LINKS = Object.freeze([
  { label: 'SiteMonitor — açık alarmlar (Alarm Geçmişi, açık görünüm)', url: 'https://sitemonitor.example.internal/?tab=alerthistory&view=open' },
  { label: 'İzleme Panosu — sorunlu ve gecikmiş izlemeler', url: 'https://sitemonitor.example.internal/?tab=monitoring&mo_status=down,stale' },
  { label: 'Grafana — Dijital Kanallar uçtan uca işlem gecikmesi panosu', url: 'https://grafana.example.internal/d/dk-e2e/dijital-kanallar-islem-gecikmesi' },
  { label: 'Kibana — gece hata günlükleri (son 12 saat)', url: 'https://kibana.example.internal/app/discover#/view/gece-hatalari' },
  { label: 'Olay ve Hata Geçmişi — açık kayıtlar', url: 'https://sitemonitor.example.internal/?tab=incident-history' },
  { label: '', url: 'https://status.example.internal/incidents/open' },
])
const TEMPLATE = Object.freeze({
  v: 1,
  sections: [
    {
      id: 's-gun-basi', title: 'Gün başlangıcı', window_from: null, window_to: '10:30', tasks: [
        task('t-alarm', OR_TITLES.alarm, {
          description: 'Kritik ve yüksek seviyeli açık alarmları sahiplen; kapalı izlemelerin bakım penceresinde olup olmadığını kontrol et.',
          links: clone(ALARM_LINKS), signals: ['open_alerts', 'down_monitors'],
        }),
        task('t-batch', OR_TITLES.batch, {
          links: [{ label: 'Batch izleme ekranı — gece çalışan işler', url: 'https://batch.example.internal/jobs?run=nightly' }],
        }),
        task('t-mail', OR_TITLES.mail, { required: false }),
      ],
    },
    {
      id: 's-gun-ici', title: 'Gün içi', window_from: null, window_to: '17:30', tasks: [
        task('t-disk', OR_TITLES.disk),
        task('t-sertifika', OR_TITLES.cert, {
          signals: ['expiring_certs'],
          links: [{ label: 'Sertifika Panosu — 30 gün içinde dolacaklar', url: 'https://sitemonitor.example.internal/?tab=all&c_win=30' }],
        }),
        task('t-yedek', OR_TITLES.backup),
      ],
    },
    {
      id: 's-haftalik', title: 'Haftalık periyotta', window_from: null, window_to: null, tasks: [
        task('t-erisim', OR_TITLES.access, { frequency: 'WEEKLY', weekday: 5 }),
      ],
    },
    {
      id: 's-aylik', title: 'Aylık Geçiş', window_from: null, window_to: null, tasks: [
        task('t-dr', OR_TITLES.dr, { frequency: 'MONTHLY', monthly_rule: 'LAST_WORKDAY' }),
      ],
    },
  ],
})
const DEFAULT_SETTINGS = Object.freeze({
  enabled: true, workdays: [1, 2, 3, 4, 5], skip_holidays: true, day_start: '09:00',
  reminders: { enabled: true, times: ['16:30', '17:30'], section_deadlines: true, due_morning: true },
  completion_notice: { enabled: true, mail: true, push: true },
  handover_notice: { enabled: true, mail: true, push: true },
})

// ── Durum ────────────────────────────────────────────────────────────────────────────────────────────────────────────
function newState() {
  return {
    requests: [],
    unknown: [],
    templateConflictNext: false,
    lastExport: null,
    template: clone(TEMPLATE),
    templateVersion: 14,
    settings: clone(DEFAULT_SETTINGS),
    settingsVersion: 5,
    items: new Map(),           // anahtar → durum alanları (değişen maddeler)
    itemIds: new Map(),
    days: new Map(),            // tarih → gün kaydı
    dayNotes: new Map(),
    handoverNotes: new Map(),
    handoverAck: null,
    assignments: new Map(),     // `${scope}:${key}` → { scope, key, user_id, by_name, at, note }
    problems: [{
      id: 901, version: 1, team_id: OR_TEAM_ID, severity: 'HIGH', status: 'OPEN', day: '2026-11-24',
      task_id: 't-alarm', period_kind: 'DAY', period_key: '2026-11-24',
      task_title: OR_TITLES.alarm, section_title: 'Gün başlangıcı',
      note: 'Ödeme ekranında gece 02:10–02:40 arası 502 hatası; CDN kaynaklı olabilir, çağrı kaydı açıldı.',
      created_by_name: OR_AYSE.name, created_at: '2026-11-24T06:05:00Z', updated_by_name: null, updated_at: null,
      resolved_by_name: null, resolved_at: null, resolution_note: null, age_days: 2, can_delete: false, can_edit: true,
      images: [{ id: 7001, width: 64, height: 48, size_bytes: orPng().length, content_type: 'image/png',
        created_at: '2026-11-24T06:06:00Z', created_by_name: OR_AYSE.name }],
      incident: { id: 812, title: 'Ödeme ekranında gece 502 hatası (CDN)', status: 'OPEN' },
    }],
    nextProblemId: 950,
    nextImageId: 7100,
  }
}

function locateTask(st, taskId) {
  for (const sec of st.template.sections || []) {
    for (const tk of sec.tasks || []) if (tk.id === taskId) return { sec, tk }
  }
  return null
}

function scheduleOf(date) {
  const wk = mondayOf(date)
  if (wk >= OR_WEEK_KEY) return OR_AYSE
  if (wk === OR_PREV_WEEK_KEY) return OR_MEHMET
  return Number(wk.slice(8, 10)) % 2 ? OR_SELIN : OR_MEHMET
}

function defaultDayStatus(date) {
  if (date > OR_TODAY) return 'FUTURE'
  if (!isWorkday(date)) return 'NOT_WORKDAY'
  if (date < OR_ROUTINE_SINCE) return 'NONE'
  if (date === OR_TODAY) return 'OPEN'
  return EXPLICIT_STATUS[date] || 'COMPLETED'
}

function dayRec(st, date) {
  let d = st.days.get(date)
  if (!d) {
    const status = defaultDayStatus(date)
    const completed = status === 'COMPLETED' || status === 'COMPLETED_PARTIAL'
    const who = scheduleOf(date)
    d = {
      status, version: date === OR_TODAY ? 3 : 2,
      completed_by_name: completed ? who.name : null,
      completed_at: completed ? `${date}T${date === '2026-11-20' ? '15:52' : '14:05'}:00Z` : null,
      completed_late: date === '2026-10-28',
    }
    st.days.set(date, d)
  }
  return d
}
const isCompleted = (s) => s === 'COMPLETED' || s === 'COMPLETED_PARTIAL'

function doneBy(p, at) {
  return { state: 'DONE', state_by_name: p.name, state_at: at, skip_reason: null, version: 2 }
}
const PENDING = Object.freeze({ state: 'PENDING', state_by_name: null, state_at: null, skip_reason: null, version: 1 })
const NO_NOTE = Object.freeze({ note: null, note_version: 0, note_by_name: null, note_at: null })

function baseItemState(st, kind, pk, tk) {
  if (kind === 'WEEK') return pk >= OR_WEEK_KEY ? { ...PENDING, ...NO_NOTE } : { ...doneBy(scheduleOf(pk), `${orAddDays(pk, 4)}T13:12:00Z`), ...NO_NOTE }
  if (kind === 'MONTH') return pk >= OR_MONTH_KEY ? { ...PENDING, ...NO_NOTE } : { ...doneBy(OR_SELIN, `${pk}-27T12:00:00Z`), ...NO_NOTE }
  if (pk === OR_TODAY) {
    if (tk.id === 't-batch') {
      return {
        ...doneBy(OR_AYSE, '2026-11-26T05:48:00Z'),
        note: 'EOD, mutabakat ve raporlama işleri sorunsuz tamamlandı.', note_version: 1,
        note_by_name: OR_AYSE.name, note_at: '2026-11-26T05:49:30Z',
      }
    }
    return { ...PENDING, ...NO_NOTE }
  }
  const status = dayRec(st, pk).status
  const who = scheduleOf(pk)
  const done = { ...doneBy(who, `${pk}T06:30:00Z`), ...NO_NOTE }
  if (status === 'COMPLETED') return done
  if (status === 'COMPLETED_PARTIAL') {
    return tk.id === 't-yedek'
      ? { state: 'SKIPPED', state_by_name: who.name, state_at: `${pk}T15:50:00Z`, version: 2, ...NO_NOTE,
        skip_reason: 'Yedek sunucusu bakımdaydı; arşiv kopyası ertesi gün alındı.' }
      : done
  }
  if (status === 'INCOMPLETE') return ['t-disk', 't-yedek'].includes(tk.id) ? { ...PENDING, ...NO_NOTE } : done
  if (status === 'MISSED') return tk.required ? { ...PENDING, ...NO_NOTE, state: 'MISSED' } : { ...PENDING, ...NO_NOTE }
  return { ...PENDING, ...NO_NOTE }
}

function dueOf(kind, pk, tk) {
  if (kind === 'DAY') return pk
  if (kind === 'WEEK') return orAddDays(pk, (tk.weekday || 5) - 1)
  return '2026-11-30'                                  // Kasım 2026'nın son iş günü (Pazartesi)
}

function itemState(st, kind, pk, tk) {
  return st.items.get(`${kind}:${pk}:${tk.id}`) || baseItemState(st, kind, pk, tk)
}

function makeItem(st, sec, tk, kind, pk) {
  const key = `${kind}:${pk}:${tk.id}`
  if (!st.itemIds.has(key)) st.itemIds.set(key, 5000 + st.itemIds.size)
  const s = itemState(st, kind, pk, tk)
  const problemsOpen = st.problems.filter((p) => p.status === 'OPEN' && p.task_id === tk.id
    && p.period_kind === kind && p.period_key === pk).length
  return {
    key, item_id: st.itemIds.get(key), task_id: tk.id, period_kind: kind, period_key: pk, due_day: dueOf(kind, pk, tk),
    title: tk.title, description: tk.description || '', links: clone(tk.links || []), signals: [...(tk.signals || [])],
    required: tk.required !== false, removed: false,
    window_from: kind === 'DAY' ? (tk.window_from ?? sec.window_from ?? null) : null,
    window_to: kind === 'DAY' ? (tk.window_to ?? sec.window_to ?? null) : null,
    state: s.state, state_by_name: s.state_by_name, state_by_me: s.state_by_name === OR_ME.name, state_at: s.state_at,
    skip_reason: s.skip_reason, note: s.note, note_version: s.note_version, note_by_name: s.note_by_name, note_at: s.note_at,
    problems_open: problemsOpen, version: s.version, snapshot_differs: false,
  }
}

function sectionsOf(st, freq, kind, pk, filter = () => true) {
  return (st.template.sections || []).map((sec) => ({
    id: sec.id, title: sec.title, window_from: sec.window_from ?? null, window_to: sec.window_to ?? null,
    items: (sec.tasks || []).filter((tk) => tk.active !== false && (tk.frequency || 'DAILY') === freq && filter(tk))
      .map((tk) => makeItem(st, sec, tk, kind, pk)),
  })).filter((s) => s.items.length)
}

function groupsFor(st, date, { periods = true } = {}) {
  const groups = []
  if (isWorkday(date)) {
    const day = sectionsOf(st, 'DAILY', 'DAY', date)
    if (day.length) groups.push({ kind: 'DAY', sections: day })
  }
  if (periods && mondayOf(date) === OR_WEEK_KEY) {
    const wk = sectionsOf(st, 'WEEKLY', 'WEEK', OR_WEEK_KEY)
    if (wk.length) groups.push({ kind: 'WEEK', sections: wk })
    const mo = sectionsOf(st, 'MONTHLY', 'MONTH', OR_MONTH_KEY)
    if (mo.length) groups.push({ kind: 'MONTH', sections: mo })
  }
  return groups
}
const itemsOf = (groups) => groups.flatMap((g) => g.sections.flatMap((s) => s.items.map((it) => ({ ...it, section_title: s.title }))))

function isOverdue(it, date) {
  if (!['PENDING', 'MISSED'].includes(it.state) || !it.required) return false
  if (it.due_day < OR_TODAY) return true
  if (it.due_day === OR_TODAY && date <= OR_TODAY) {
    const to = hmMin(it.window_to)
    return to != null && NOW_MINUTES > to
  }
  return false
}

function progressOf(st, date, groups) {
  let required = 0
  let done = 0
  let skipped = 0
  let pending = 0
  let overdue = 0
  let optional = 0
  for (const it of itemsOf(groups)) {
    if (it.required && it.due_day <= date) {
      required += 1
      if (it.state === 'DONE') done += 1
      else if (it.state === 'SKIPPED') skipped += 1
      else { pending += 1; if (isOverdue(it, date)) overdue += 1 }
    } else if (!['DONE', 'SKIPPED'].includes(it.state) && !(it.required && it.due_day > date)) optional += 1
  }
  const problemsOpen = st.problems.filter((p) => p.status === 'OPEN').length
  return { required, done, skipped, pending, overdue, optional_pending: optional, problems_open: problemsOpen }
}

function dayCounts(st, date) {
  if (!isWorkday(date) || date < OR_ROUTINE_SINCE || date > OR_TODAY) {
    return { required: null, done: 0, skipped: 0, resolved: 0, overdue: 0 }
  }
  const p = progressOf(st, date, groupsFor(st, date))
  return { required: p.required, done: p.done, skipped: p.skipped, resolved: p.done + p.skipped, overdue: p.overdue }
}

function publicDay(st, date) {
  const d = dayRec(st, date)
  const note = st.dayNotes.get(date) || { ...NO_NOTE }
  return {
    status: d.status, version: d.version, note: note.note, note_version: note.note_version, note_by_name: note.note_by_name,
    note_at: note.note_at, completed_by_name: d.completed_by_name, completed_at: d.completed_at, completed_late: d.completed_late,
  }
}

function wireStatus(st, date) {
  if (date > OR_TODAY) return 'FUTURE'
  if (!isWorkday(date)) return 'NOT_WORKDAY'
  if (date < OR_ROUTINE_SINCE) return 'NONE'
  return dayRec(st, date).status
}

function personOf(id) {
  return PEOPLE.find((p) => p.user_id === Number(id)) || null
}

function responsibleFor(st, date) {
  const a = st.assignments.get(`DAY:${date}`) || st.assignments.get(`WEEK:${mondayOf(date)}`) || null
  const p = (a && personOf(a.user_id)) || scheduleOf(date)
  return {
    user_id: p.user_id, name: p.name, username: p.username, active: true, is_me: p.user_id === OR_ME.user_id,
    source: a ? (a.scope === 'DAY' ? 'TAKEOVER_DAY' : 'TAKEOVER_WEEK') : 'SCHEDULE',
    schedule_configured: true, schedule_enabled: true,
    assignment: a ? { scope: a.scope, key: a.key, note: a.note || null, created_by_name: a.by_name, created_at: a.at || OR_NOW } : null,
  }
}

function previousHandover(st) {
  return {
    show: true, period_key: OR_PREV_WEEK_KEY, from: OR_PREV_WEEK_KEY, to: orAddDays(OR_PREV_WEEK_KEY, 6),
    responsible_names: [OR_MEHMET.name],
    note: 'Ödeme ekranındaki 502 hatası için açık sorun takipte; CDN önbelleği pazartesi yenilendi.',
    note_by_name: OR_MEHMET.name, note_at: '2026-11-20T15:40:00Z',
    summary: {
      workdays: 5, completed: 4, partial: 1, incomplete: 0, missed: 0, skipped_items: 2, missed_items: 0,
      problems_opened: 3, problems_open: 1,
      day_notes: [{ date: '2026-11-18', by: OR_MEHMET.name, note: 'Mutabakat işi 40 dk gecikti; ilgili ekibe bilgi verildi.' }],
    },
    summary_ready: true,
    acknowledged: !!st.handoverAck, ack_by_name: st.handoverAck?.by ?? null, ack_at: st.handoverAck?.at ?? null, can_ack: true,
  }
}

function handoverCurrent(st, wk) {
  const n = st.handoverNotes.get(wk) || { ...NO_NOTE, note: '' }
  return {
    period_key: wk, from: wk, to: orAddDays(wk, 6),
    note: n.note ?? '', note_version: n.note_version, note_by_name: n.note_by_name, note_at: n.note_at,
    // Sunucu kuralı (OnCallRoutineService.handoverNoteWritable): yalnız bugünün ve bir önceki devir dönemi
    writable: wk === OR_WEEK_KEY || wk === OR_PREV_WEEK_KEY,
  }
}

function openProblems(st) {
  const rank = { HIGH: 0, MEDIUM: 1, LOW: 2 }
  return clone(st.problems.filter((p) => p.status === 'OPEN')
    .sort((a, b) => (rank[a.severity] ?? 3) - (rank[b.severity] ?? 3) || String(b.created_at).localeCompare(String(a.created_at))))
}

function previousWorkday(st) {
  let d = orAddDays(OR_TODAY, -1)
  for (let i = 0; i < 10 && !isWorkday(d); i++) d = orAddDays(d, -1)
  const pending = itemsOf(groupsFor(st, d, { periods: false }))
    .filter((it) => it.required && ['PENDING', 'MISSED'].includes(it.state))
    .map((it) => ({ task_id: it.task_id, period_kind: it.period_kind, period_key: it.period_key, title: it.title, section_title: it.section_title }))
  return { date: d, status: dayRec(st, d).status, pending_total: pending.length, pending }
}

function weekStrip(st, date) {
  const wk = mondayOf(date)
  return {
    key: wk, from: wk, to: orAddDays(wk, 6),
    days: Array.from({ length: 7 }, (_, i) => {
      const d = orAddDays(wk, i)
      const c = dayCounts(st, d)
      return { date: d, workday: isWorkday(d), status: wireStatus(st, d), required: c.required, resolved: c.resolved }
    }),
  }
}

function daySheet(st, date) {
  const workday = isWorkday(date)
  const recorded = date >= OR_ROUTINE_SINCE
  const d = dayRec(st, date)
  const completed = isCompleted(d.status)
  const inWindow = date <= OR_TODAY && date >= orAddDays(OR_TODAY, -7)
  let locked = null
  if (!workday) locked = 'NOT_WORKDAY'
  else if (date > OR_TODAY) locked = 'FUTURE'
  else if (completed) locked = 'COMPLETED'
  else if (!inWindow) locked = 'TOO_OLD'
  const groups = groupsFor(st, date)
  const open = openProblems(st)
  return {
    team_id: OR_TEAM_ID, date, today: OR_TODAY, now: OR_NOW, workday, holiday_name: HOLIDAYS[date] || null, recorded,
    configured: true, locked_reason: locked, editable: workday && recorded && locked == null, can_work: true,
    can_reopen: completed && inWindow, can_edit_template: true,
    // Sunucu gün sayfasına tamamlama bildirimini ekler (OnCallRoutineSettings.noticeWire) — pencere /template istemez
    settings: { completion_notice: clone(st.settings.completion_notice) },
    day: { ...publicDay(st, date), status: !workday ? 'NOT_WORKDAY' : (recorded ? d.status : 'NONE') },
    week: weekStrip(st, date),
    groups,
    previous_workday: date === OR_TODAY ? previousWorkday(st) : null,
    responsible: responsibleFor(st, date),
    handover: mondayOf(date) === OR_WEEK_KEY ? previousHandover(st) : { show: false },
    handover_current: handoverCurrent(st, mondayOf(date)),
    open_problems: open, open_problems_total: open.length,
    progress: progressOf(st, date, groups),
  }
}

function weekSheet(st, weekParam) {
  const wk = isDate(weekParam) ? mondayOf(weekParam) : OR_WEEK_KEY
  const days = Array.from({ length: 7 }, (_, i) => {
    const date = orAddDays(wk, i)
    const status = wireStatus(st, date)
    const c = dayCounts(st, date)
    const rec = date <= OR_TODAY && isWorkday(date) && date >= OR_ROUTINE_SINCE ? dayRec(st, date) : null
    return {
      date, workday: isWorkday(date), holiday_name: HOLIDAYS[date] || null, status,
      required: c.required, resolved: c.resolved, done: c.done, skipped: c.skipped,
      problems_opened: st.problems.filter((p) => p.day === date).length,
      completed_by_name: rec?.completed_by_name ?? null, completed_at: rec?.completed_at ?? null,
      completed_late: !!rec?.completed_late, today: date === OR_TODAY,
      responsible_name: isWorkday(date) ? responsibleFor(st, date).name : null,
    }
  })
  let groups
  if (wk === OR_WEEK_KEY) groups = groupsFor(st, OR_TODAY).filter((g) => g.kind !== 'DAY')
  else {
    const w = sectionsOf(st, 'WEEKLY', 'WEEK', wk)
    groups = w.length ? [{ kind: 'WEEK', sections: w }] : []
  }
  return {
    team_id: OR_TEAM_ID, today: OR_TODAY, now: OR_NOW, can_work: true,
    week: { key: wk, from: wk, to: orAddDays(wk, 6) }, days,
    responsible_by_day: Object.fromEntries(days.filter((x) => x.responsible_name).map((x) => {
      const r = responsibleFor(st, x.date)
      return [x.date, { name: r.name, source: r.source, is_me: r.is_me }]
    })),
    groups,
    handover_current: handoverCurrent(st, wk),
    handover_previous: wk === OR_WEEK_KEY ? previousHandover(st) : null,
  }
}

function overview(st) {
  const today = dayCounts(st, OR_TODAY)
  return {
    module_visible: true, default_team_id: OR_TEAM_ID, truncated: false,
    teams: [
      {
        team_id: OR_TEAM_ID, team_name: OR_TEAM_NAME, member: true, can_manage: true, can_work: true, can_edit_template: true,
        configured: true, module_visible: true,
        today: { date: OR_TODAY, workday: true, status: dayRec(st, OR_TODAY).status, required: today.required, resolved: today.resolved, overdue: today.overdue },
      },
      {
        team_id: OR_OTHER_TEAM_ID, team_name: OR_OTHER_TEAM_NAME, member: false, can_manage: true, can_work: false,
        can_edit_template: true, configured: false, module_visible: true,
        today: { date: OR_TODAY, workday: true, status: 'NONE', required: null, resolved: null, overdue: 0 },
      },
    ],
  }
}

function templateView(st) {
  return {
    configured: true, template: clone(st.template), template_version: st.templateVersion,
    template_updated_by_name: OR_SELIN.name, template_updated_at: '2026-11-19T10:12:00Z', template_unreadable: false,
    can_edit_template: true, signal_defs: OR_SIGNAL_KEYS.map((key) => ({ key, perm: null, hidden: false })),
    settings: clone(st.settings), settings_version: st.settingsVersion,
    settings_updated_by_name: OR_SELIN.name, settings_updated_at: '2026-11-02T09:00:00Z',
  }
}

function calendarDays(st, from, to) {
  const out = []
  for (let d = from, i = 0; d <= to && i < 800; d = orAddDays(d, 1), i++) {
    const c = dayCounts(st, d)
    const rec = d <= OR_TODAY && isWorkday(d) && d >= OR_ROUTINE_SINCE ? dayRec(st, d) : null
    out.push({
      date: d, workday: isWorkday(d), holiday_name: HOLIDAYS[d] || null, status: wireStatus(st, d),
      required: c.required, done: c.done, skipped: c.skipped, resolved: c.resolved,
      problems_opened: st.problems.filter((p) => p.day === d).length,
      completed_late: !!rec?.completed_late, responsible_name: isWorkday(d) ? scheduleOf(d).name : null,
      completed_by_name: rec?.completed_by_name ?? null, completed_at: rec?.completed_at ?? null,
    })
  }
  return out
}

function statsFor(st, from, to) {
  const days = calendarDays(st, from, to).filter((d) => d.workday && d.date >= OR_ROUTINE_SINCE && d.date <= OR_TODAY)
  const count = (s) => days.filter((d) => d.status === s).length
  const sections = st.template.sections || []
  const tasks = sections.flatMap((sec) => (sec.tasks || []).map((tk, i) => ({
    task_id: tk.id, title: tk.title, section_title: sec.title, frequency: tk.frequency || 'DAILY',
    done_pct: [96.4, 88.2, 71.5, 92.0, 100, 84.6][i % 6], skipped: i % 3, missed: tk.id === 't-yedek' ? 2 : 0,
    problems: tk.id === 't-alarm' ? 3 : 0,
  })))
  return {
    from, to, workdays: days.length,
    days: { completed: count('COMPLETED'), partial: count('COMPLETED_PARTIAL'), incomplete: count('INCOMPLETE'), missed: count('MISSED') },
    on_time_pct: 86.4, tasks,
    people: [OR_AYSE, OR_MEHMET, OR_SELIN].map((p, i) => ({
      user_id: p.user_id, name: p.name, days_responsible: [11, 7, 4][i], days_completed: [10, 6, 4][i],
      days_completed_on_time: [9, 5, 4][i], items_done: [61, 37, 22][i], problems_opened: [3, 2, 1][i],
    })),
    problems: { opened: 6, resolved: 4, open_now: st.problems.filter((p) => p.status === 'OPEN').length, median_resolve_hours: 5.25, by_severity: { HIGH: 1, MEDIUM: 3, LOW: 2 } },
    truncated: false,
  }
}

function defaultRange(query, days) {
  const to = isDate(query.to) ? query.to : OR_TODAY
  const from = isDate(query.from) ? query.from : orAddDays(to, -(days - 1))
  return { from, to }
}

function pubSettings(st) {
  return {
    settings: clone(st.settings), settings_version: st.settingsVersion,
    settings_updated_by_name: OR_ME.name, settings_updated_at: OR_NOW, changed: true,
  }
}

function setItem(st, kind, pk, tk, patch) {
  const key = `${kind}:${pk}:${tk.id}`
  const cur = itemState(st, kind, pk, tk)
  st.items.set(key, { ...cur, ...patch, version: (cur.version || 1) + 1 })
}

function stateChange(st, kind, pk, tk, state, skipReason = null) {
  setItem(st, kind, pk, tk, state === 'PENDING'
    ? { state, state_by_name: null, state_at: null, skip_reason: null }
    : { state, state_by_name: OR_ME.name, state_at: OR_NOW, skip_reason: state === 'SKIPPED' ? skipReason : null })
}

// ── Test kancaları (2026-11-26, inceleme düzeltmeleri e2e) ───────────────────────────────────────────────────────────
// Hiçbiri verilmezse yanıtlar BAYT BAYT önceki gibidir (dönüştürücü yok → aynı nesne; hata kuyruğu boş; kanca yok).

/** `st.transform[name](data, ctx)` varsa yanıt verisini dönüştürür (undefined dönerse veri olduğu gibi kalır). */
function applyTransform(st, name, data, ctx = {}) {
  const fn = st.transform?.[name]
  if (typeof fn !== 'function') return data
  const out = fn(data, { st, ...ctx })
  return out === undefined ? data : out
}

/** Durumdaki bir maddeyi zorla değiştirir (başkası işaretledi / not yazdı). Anahtar `DAY:2026-11-26:t-disk` biçiminde. */
export function orForceItem(st, key, patch) {
  const parts = String(key).split(':')
  if (parts.length !== 3) throw new Error(`orForceItem: bad key ${key}`)
  const [kind, pk, taskId] = parts
  const loc = locateTask(st, taskId)
  if (!loc) throw new Error(`orForceItem: unknown task ${taskId}`)
  setItem(st, kind, pk, loc.tk, patch)
  return makeItem(st, loc.sec, loc.tk, kind, pk)
}

/** Sunucunun şu anki gün sayfası (dönüştürücüsüz, kopya) — "bayat yoklama yanıtı" için önceden alınır. */
export function orDaySheet(st, date = OR_TODAY) {
  return clone(daySheet(st, date))
}

// ── Yönlendirici ─────────────────────────────────────────────────────────────────────────────────────────────────────
const json = (route, status, body) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
const ok = (route, data, extra = {}) => json(route, 200, { success: true, data, timestamp: OR_NOW, ...extra })
const fail = (route, status, code, error, extra = {}) => json(route, status, { success: false, code, error, timestamp: OR_NOW, ...extra })

function parseBody(req) {
  const ct = req.headers()['content-type'] || ''
  if (ct.includes('multipart/')) return null
  const raw = req.postData()
  if (!raw) return null
  try { return JSON.parse(raw) } catch { return raw }
}

/**
 * Durumlu sahte sunucuyu bağlar. `permissions: false` → `/api/me/permissions` mockApi'de kalır (duyarlı taramalar).
 *
 * Ek seçenekler (hepsi isteğe bağlı; verilmezse davranış değişmez):
 *  - `perms`, `scope` — `/api/me/permissions` yanıtı (varsayılan `OR_PERMS` / `OR_SCOPE`).
 *  - `transform` — `{ overview, day, week, template, templateVersions, templateVersion, problems, stats, signals }`:
 *    `(data, ctx) => yeniVeri` (ctx: `{ st, query, date?, version? }`). `st.transform` ile test sırasında da değiştirilebilir.
 *  - `failures` — `{ 'GET /teams/41/day': [{ status, code, error, extra }] }`: anahtar = yöntem + rutin önekisiz yol (sorgusuz);
 *    her istek kuyruktan BİR hata alır, kuyruk boşalınca normal yanıt. `st.failures` ile sonradan da eklenebilir.
 *  - `hooks` — `[async (ctx) => boolean]`: ctx `{ method, path, query, body, entry, route, st, ok(data, extra), fail(status,
 *    code, error, extra) }`; `true` dönen kanca isteği kendisi yanıtlamıştır. Kanca bekleyebilir (yanıtı geciktirmek için);
 *    `false` dönerse sıradaki adım (hata kuyruğu → normal yanıt) çalışır. `st.hooks` ile sonradan da eklenebilir.
 * @returns {Promise<object>} durum (istek kaydı + değiştirilebilir bayraklar: `templateConflictNext`, `transform`, `failures`, `hooks`).
 */
export async function mockOncallRoutines(page, {
  permissions = true, perms = OR_PERMS, scope = OR_SCOPE, transform = {}, failures = {}, hooks = [],
} = {}) {
  const st = newState()
  st.transform = { ...transform }
  st.failures = Object.fromEntries(Object.entries(failures).map(([k, v]) => [k, Array.isArray(v) ? [...v] : [v]]))
  st.hooks = [...hooks]

  if (permissions) {
    await page.route((u) => new URL(u).pathname === '/api/me/permissions',
      (route) => json(route, 200, { success: true, data: perms, scope }))
  }

  await page.route((u) => new URL(u).pathname.startsWith('/api/oncall-routines/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const method = req.method()
    const p = url.pathname.slice('/api/oncall-routines'.length)
    const query = Object.fromEntries(url.searchParams)
    const body = parseBody(req)
    const buf = (req.headers()['content-type'] || '').includes('multipart/') ? req.postDataBuffer() : null
    const entry = { method, path: url.pathname, query, body, png: buf ? orPngInfo(buf) : null }
    st.requests.push(entry)
    let m

    for (const hook of st.hooks || []) {
      const handled = await hook({
        method, path: p, query, body, entry, route, st,
        ok: (data, extra) => ok(route, data, extra),
        fail: (status, code, error, extra) => fail(route, status, code, error, extra),
      })
      if (handled === true) return undefined
    }
    const failQueue = st.failures?.[`${method} ${p}`]
    if (Array.isArray(failQueue) && failQueue.length) {
      const f = failQueue.shift() || {}
      return fail(route, f.status ?? 500, f.code ?? 'INTERNAL_ERROR', f.error ?? 'mock: zorlanan sunucu hatası', f.extra ?? {})
    }
    const tf = (name, data, ctx = {}) => applyTransform(st, name, data, { query, ...ctx })

    if (p === '/overview' && method === 'GET') return ok(route, tf('overview', overview(st)))
    if (p === '/me' && method === 'GET') {
      const c = dayCounts(st, OR_TODAY)
      return ok(route, {
        generated_at: OR_NOW, badge: { count: c.overdue, level: c.overdue > 0 ? 'warning' : 'none' },
        teams: [{ team_id: OR_TEAM_ID, team_name: OR_TEAM_NAME, status: dayRec(st, OR_TODAY).status, required: c.required, resolved: c.resolved, overdue: c.overdue }],
      })
    }
    if (p === '/sample-template' && method === 'GET') return ok(route, { template: clone(TEMPLATE), lang: query.lang || 'tr' })

    // Görsel / sorun uçları (takımsız yol)
    if ((m = /^\/problems\/(\d+)\/images\/(\d+)$/.exec(p))) {
      const pr = st.problems.find((x) => x.id === Number(m[1]))
      const img = pr?.images?.find((x) => x.id === Number(m[2]))
      if (!img) return fail(route, 404, 'IMAGE_NOT_FOUND', 'Görsel bulunamadı.')
      if (method === 'DELETE') { pr.images = pr.images.filter((x) => x !== img); return ok(route, { deleted: true }) }
      return route.fulfill({ status: 200, contentType: 'image/png', body: orPng(img.width || 64, img.height || 48) })
    }
    if ((m = /^\/problems\/(\d+)\/images$/.exec(p)) && method === 'POST') {
      const pr = st.problems.find((x) => x.id === Number(m[1]))
      if (!pr) return fail(route, 404, 'PROBLEM_NOT_FOUND', 'Sorun bulunamadı.')
      if (pr.images.length >= 4) return fail(route, 409, 'PROBLEM_IMAGE_LIMIT', 'Bir soruna en fazla 4 görsel eklenebilir.')
      const info = entry.png
      if (!info) return fail(route, 400, 'VALIDATION_FAILED', 'Dosya bir PNG / JPEG görsel değil.', { field: 'file' })
      const image = {
        id: st.nextImageId++, width: info.width, height: info.height, size_bytes: info.bytes ?? 0, content_type: 'image/png',
        created_at: OR_NOW, created_by_name: OR_ME.name,
      }
      pr.images.push(image)
      return ok(route, { image })
    }
    if ((m = /^\/problems\/(\d+)\/incident$/.exec(p)) && method === 'PUT') {
      const pr = st.problems.find((x) => x.id === Number(m[1]))
      if (!pr) return fail(route, 404, 'PROBLEM_NOT_FOUND', 'Sorun bulunamadı.')
      pr.incident = body?.incident_id ? { id: Number(body.incident_id), title: 'Bağlanan olay kaydı', status: 'OPEN' } : null
      if (Number.isInteger(pr.version)) pr.version += 1
      return ok(route, { problem: clone(pr) })
    }
    if ((m = /^\/problems\/(\d+)$/.exec(p))) {
      const pr = st.problems.find((x) => x.id === Number(m[1]))
      if (!pr) return fail(route, 404, 'PROBLEM_NOT_FOUND', 'Sorun bulunamadı.')
      if (method === 'DELETE') { st.problems = st.problems.filter((x) => x !== pr); return ok(route, { deleted: true }) }
      if (method === 'PATCH') {
        if (body?.severity) pr.severity = body.severity
        if (typeof body?.note === 'string') pr.note = body.note
        if (body?.status === 'RESOLVED') {
          Object.assign(pr, { status: 'RESOLVED', resolved_by_name: OR_ME.name, resolved_at: OR_NOW, resolution_note: body.resolution_note ?? null })
        } else if (body?.status === 'OPEN') {
          Object.assign(pr, { status: 'OPEN', resolved_by_name: null, resolved_at: null, resolution_note: null })
        }
        Object.assign(pr, { updated_by_name: OR_ME.name, updated_at: OR_NOW })
        if (Number.isInteger(pr.version)) pr.version += 1
        return ok(route, { problem: clone(pr) })
      }
    }

    m = /^\/teams\/(\d+)(\/.*)$/.exec(p)
    if (m) {
      const teamId = Number(m[1])
      const sub = m[2]
      if (teamId !== OR_TEAM_ID) return fail(route, 404, 'TEAM_NOT_FOUND', 'Takım bulunamadı ya da görme izniniz yok.')
      let mm

      if (sub === '/day' && method === 'GET') {
        const date = isDate(query.date) ? query.date : OR_TODAY
        return ok(route, tf('day', daySheet(st, date), { date }))
      }
      if (sub === '/week' && method === 'GET') return ok(route, tf('week', weekSheet(st, query.week)))
      if (sub === '/template' && method === 'GET') return ok(route, tf('template', templateView(st)))
      if (sub === '/template' && method === 'PUT') {
        if (st.templateConflictNext || Number(body?.version) !== st.templateVersion) {
          st.templateConflictNext = false
          return fail(route, 409, 'CONCURRENT_MODIFICATION', 'Şablon siz düzenlerken başka biri tarafından kaydedildi.', {
            current_version: st.templateVersion + 1, updated_by_name: OR_SELIN.name, updated_at: '2026-11-26T08:39:00Z',
          })
        }
        st.template = clone(body.template)
        st.templateVersion += 1
        return ok(route, {
          template: clone(st.template), template_version: st.templateVersion, changed: true,
          template_updated_by_name: OR_ME.name, template_updated_at: OR_NOW,
        })
      }
      if (sub === '/template/versions' && method === 'GET') {
        return ok(route, tf('templateVersions', [
          { version: st.templateVersion, saved_at: '2026-11-19T10:12:00Z', saved_by_name: OR_SELIN.name, source: 'EDITOR', sections: 4, tasks: 8, active: 8 },
          { version: st.templateVersion - 1, saved_at: '2026-11-05T08:40:00Z', saved_by_name: OR_SELIN.name, source: 'IMPORT', sections: 4, tasks: 7, active: 7 },
        ]))
      }
      if ((mm = /^\/template\/versions\/(\d+)$/.exec(sub)) && method === 'GET') {
        const version = Number(mm[1])
        return ok(route, tf('templateVersion',
          { version, template: clone(TEMPLATE), saved_at: '2026-11-05T08:40:00Z', saved_by_name: OR_SELIN.name, source: 'IMPORT' },
          { version }))
      }
      if (sub === '/settings' && method === 'PUT') {
        if (Number(body?.version) !== st.settingsVersion) {
          return fail(route, 409, 'CONCURRENT_MODIFICATION', 'Ayarlar başka biri tarafından değiştirildi.', {
            current_version: st.settingsVersion, updated_by_name: OR_SELIN.name, updated_at: '2026-11-26T08:39:00Z',
          })
        }
        const patch = body?.settings || {}
        const next = clone(st.settings)
        for (const [k, v] of Object.entries(patch)) {
          next[k] = v && typeof v === 'object' && !Array.isArray(v) ? { ...next[k], ...v } : v
        }
        st.settings = next
        st.settingsVersion += 1
        return ok(route, pubSettings(st))
      }
      if (sub === '/people' && method === 'GET') {
        return ok(route, PEOPLE.map((x) => ({ ...x, active: true, is_me: x.user_id === OR_ME.user_id })))
      }
      if (sub === '/signals' && method === 'GET') {
        return ok(route, tf('signals', {
          generated_at: OR_NOW,
          signals: {
            open_alerts: { value: 3, tone: 'warn', link: { tab: 'alerthistory', params: { view: 'open' } } },
            down_monitors: { value: 1, tone: 'bad', link: { tab: 'monitoring', params: { mo_status: 'down' } } },
            expiring_certs: { value: 2, tone: 'warn', link: { tab: 'all', params: { c_win: '30' } } },
          },
        }))
      }
      if (sub === '/items/state' && method === 'POST') {
        const loc = locateTask(st, body?.task_id)
        if (!loc) return fail(route, 404, 'TASK_NOT_FOUND', 'Görev şablonda yok.')
        const kind = body.period_kind
        const pk = body.period_key
        const date = body.date
        if (isCompleted(dayRec(st, date).status) && kind === 'DAY') return fail(route, 409, 'DAY_COMPLETED', 'Gün tamamlanmış.', { day: publicDay(st, date) })
        const cur = itemState(st, kind, pk, loc.tk)
        if (cur.state !== body.expected_state) {
          return fail(route, 409, 'ITEM_CHANGED', 'Görev bu arada değişti.', { current: makeItem(st, loc.sec, loc.tk, kind, pk) })
        }
        stateChange(st, kind, pk, loc.tk, body.state, body.skip_reason)
        const groups = groupsFor(st, date)
        return ok(route, { item: makeItem(st, loc.sec, loc.tk, kind, pk), progress: progressOf(st, date, groups), day: publicDay(st, date) })
      }
      if (sub === '/items/bulk-state' && method === 'POST') {
        const date = body?.date
        const results = (body?.items || []).map((r) => {
          const loc = locateTask(st, r.task_id)
          if (!loc) return { task_id: r.task_id, period_kind: r.period_kind, period_key: r.period_key, status: 'failed', item: null }
          const cur = itemState(st, r.period_kind, r.period_key, loc.tk)
          if (cur.state === body.state) {
            return { ...r, status: 'unchanged', item: makeItem(st, loc.sec, loc.tk, r.period_kind, r.period_key) }
          }
          if (cur.state !== r.expected_state) {
            return { ...r, status: 'failed', item: makeItem(st, loc.sec, loc.tk, r.period_kind, r.period_key) }
          }
          stateChange(st, r.period_kind, r.period_key, loc.tk, body.state)
          return {
            task_id: r.task_id, period_kind: r.period_kind, period_key: r.period_key, status: 'done',
            item: makeItem(st, loc.sec, loc.tk, r.period_kind, r.period_key),
          }
        })
        return ok(route, { results, progress: progressOf(st, date, groupsFor(st, date)), day: publicDay(st, date) })
      }
      if (sub === '/items/note' && method === 'PUT') {
        const loc = locateTask(st, body?.task_id)
        if (!loc) return fail(route, 404, 'TASK_NOT_FOUND', 'Görev şablonda yok.')
        const kind = body.period_kind
        const pk = body.period_key
        const cur = itemState(st, kind, pk, loc.tk)
        const flat = () => {
          const s = itemState(st, kind, pk, loc.tk)
          return { note: s.note, note_version: s.note_version, note_by_name: s.note_by_name, note_at: s.note_at }
        }
        if (Number(body.note_version) !== Number(cur.note_version || 0)) {
          return fail(route, 409, 'NOTE_CHANGED', 'Not bu arada değişti.', { current: flat() })
        }
        const text = String(body.note ?? '').trim()
        st.items.set(`${kind}:${pk}:${loc.tk.id}`, {
          ...cur, note: text || null, note_version: (cur.note_version || 0) + 1, note_by_name: OR_ME.name, note_at: OR_NOW,
        })
        return ok(route, { ...flat(), changed: true, item: makeItem(st, loc.sec, loc.tk, kind, pk) })
      }
      if (sub === '/problems' && method === 'POST') {
        const note = String(body?.note ?? '').trim()
        if (note.length < 3) return fail(route, 400, 'VALIDATION_FAILED', 'Not en az 3 karakter olmalı.', { field: 'note', fields: { note: 'Not en az 3 karakter olmalı.' } })
        const loc = body?.task_id ? locateTask(st, body.task_id) : null
        const problem = {
          id: st.nextProblemId++, team_id: OR_TEAM_ID, severity: body.severity || 'MEDIUM', status: 'OPEN', day: body.date,
          task_id: loc ? loc.tk.id : null, period_kind: loc ? body.period_kind : null, period_key: loc ? body.period_key : null,
          task_title: loc ? loc.tk.title : null, section_title: loc ? loc.sec.title : null, note,
          created_by_name: OR_ME.name, created_at: OR_NOW, updated_by_name: null, updated_at: null,
          resolved_by_name: null, resolved_at: null, resolution_note: null, age_days: 0, can_delete: true, can_edit: true,
          images: [], incident: null,
        }
        st.problems.push(problem)
        let item = null
        if (loc) {
          const cur = itemState(st, body.period_kind, body.period_key, loc.tk)
          const dayDone = body.period_kind === 'DAY' && isCompleted(dayRec(st, body.date).status)
          if (body.mark_done && !dayDone && ['PENDING', 'MISSED'].includes(cur.state)) stateChange(st, body.period_kind, body.period_key, loc.tk, 'DONE')
          item = makeItem(st, loc.sec, loc.tk, body.period_kind, body.period_key)
        }
        return ok(route, {
          problem: clone(problem), item, progress: progressOf(st, body.date, groupsFor(st, body.date)), day: publicDay(st, body.date),
        })
      }
      if (sub === '/problems' && method === 'GET') {
        const status = query.status || 'open'
        const list = st.problems.filter((x) => status === 'all' || (status === 'open' ? x.status === 'OPEN' : x.status === 'RESOLVED'))
        return ok(route, tf('problems', { items: clone(list), total: list.length, page: Number(query.page || 0), size: Number(query.size || 25) }))
      }
      if (sub === '/events' && method === 'GET') return ok(route, { items: [], has_more: false })
      if ((mm = /^\/days\/(\d{4}-\d{2}-\d{2})\/complete$/.exec(sub)) && method === 'POST') {
        const date = mm[1]
        const d = dayRec(st, date)
        if (Number(body?.version) !== d.version) {
          return fail(route, 409, 'CONCURRENT_MODIFICATION', 'Gün bu arada değişti.', { current_version: d.version })
        }
        if (isCompleted(d.status)) return fail(route, 409, 'DAY_COMPLETED', 'Gün zaten tamamlanmış.', { completed_by_name: d.completed_by_name })
        const pending = itemsOf(groupsFor(st, date)).filter((it) => it.required && it.due_day <= date && !['DONE', 'SKIPPED'].includes(it.state))
        if (pending.length && !body?.skip_pending?.reason) {
          return fail(route, 409, 'DAY_NOT_READY', 'Bekleyen zorunlu görevler var.', {
            pending: pending.map((it) => ({ task_id: it.task_id, period_kind: it.period_kind, period_key: it.period_key, title: it.title, section_title: it.section_title })),
          })
        }
        for (const it of pending) {
          const loc = locateTask(st, it.task_id)
          if (loc) stateChange(st, it.period_kind, it.period_key, loc.tk, 'SKIPPED', body.skip_pending.reason)
        }
        Object.assign(d, {
          status: pending.length ? 'COMPLETED_PARTIAL' : 'COMPLETED', version: d.version + 1,
          completed_by_name: OR_ME.name, completed_at: OR_NOW, completed_late: false,
        })
        if (typeof body?.note === 'string') st.dayNotes.set(date, { note: body.note, note_version: 1, note_by_name: OR_ME.name, note_at: OR_NOW })
        return ok(route, {
          day: publicDay(st, date), notice: { enabled: true, channels: ['MAIL', 'PUSH'], reason: null },
          progress: progressOf(st, date, groupsFor(st, date)),
        })
      }
      if ((mm = /^\/days\/(\d{4}-\d{2}-\d{2})\/reopen$/.exec(sub)) && method === 'POST') {
        const date = mm[1]
        const d = dayRec(st, date)
        if (Number(body?.version) !== d.version) {
          return fail(route, 409, 'CONCURRENT_MODIFICATION', 'Gün bu arada değişti.', { current_version: d.version })
        }
        Object.assign(d, { status: 'OPEN', version: d.version + 1, completed_by_name: null, completed_at: null })
        return ok(route, { day: publicDay(st, date) })
      }
      if ((mm = /^\/days\/(\d{4}-\d{2}-\d{2})\/note$/.exec(sub)) && method === 'PUT') {
        const cur = st.dayNotes.get(mm[1]) || { ...NO_NOTE }
        const next = { note: String(body?.note ?? '').trim() || null, note_version: (cur.note_version || 0) + 1, note_by_name: OR_ME.name, note_at: OR_NOW }
        st.dayNotes.set(mm[1], next)
        return ok(route, { ...next, changed: true })
      }
      if ((mm = /^\/handovers\/([^/]+)\/note$/.exec(sub)) && method === 'PUT') {
        const key = decodeURIComponent(mm[1])
        const cur = st.handoverNotes.get(key) || { ...NO_NOTE }
        const next = { note: String(body?.note ?? '').trim(), note_version: (cur.note_version || 0) + 1, note_by_name: OR_ME.name, note_at: OR_NOW }
        st.handoverNotes.set(key, next)
        return ok(route, { ...next, changed: true })
      }
      if ((mm = /^\/handovers\/([^/]+)\/ack$/.exec(sub)) && method === 'POST') {
        if (decodeURIComponent(mm[1]) !== OR_PREV_WEEK_KEY) return fail(route, 409, 'HANDOVER_NOT_DUE', 'Bu dönemin devri henüz yok.')
        const changed = !st.handoverAck
        if (!st.handoverAck) st.handoverAck = { by: OR_ME.name, at: OR_NOW }
        return ok(route, { changed, acknowledged: true, ack_by_name: st.handoverAck.by, ack_at: st.handoverAck.at })
      }
      if (sub === '/assignments' && method === 'POST') {
        if (!personOf(body?.user_id)) return fail(route, 400, 'ASSIGNEE_NOT_MEMBER', 'Seçilen kişi takımın etkin üyesi değil.')
        const scope = body.scope === 'WEEK' ? 'WEEK' : 'DAY'
        st.assignments.set(`${scope}:${body.key}`, { scope, key: body.key, user_id: Number(body.user_id), by_name: OR_ME.name, at: OR_NOW, note: body.note || null })
        return ok(route, { responsible: responsibleFor(st, scope === 'DAY' ? body.key : body.key) })
      }
      if ((mm = /^\/assignments\/(DAY|WEEK)\/([^/]+)$/.exec(sub)) && method === 'DELETE') {
        const key = decodeURIComponent(mm[2])
        const deleted = st.assignments.delete(`${mm[1]}:${key}`)
        return ok(route, { deleted, responsible: responsibleFor(st, key) })
      }
      if (sub === '/calendar' && method === 'GET') {
        const { from, to } = defaultRange(query, 365)
        return ok(route, { from, to, today: OR_TODAY, routine_since: OR_ROUTINE_SINCE, days: calendarDays(st, from, to) })
      }
      if (sub === '/stats' && method === 'GET') {
        const { from, to } = defaultRange(query, 30)
        return ok(route, tf('stats', statsFor(st, from, to)))
      }
      if (sub === '/export' && method === 'GET') {
        const { from, to } = defaultRange(query, 30)
        st.lastExport = { from, to }
        const today = itemsOf(groupsFor(st, OR_TODAY)).map((it) => ({
          date: OR_TODAY, period_kind: it.period_kind, period_key: it.period_key, section_title: it.section_title, task_title: it.title,
          state: it.state, state_by_name: it.state_by_name, state_at: it.state_at, skip_reason: it.skip_reason, note: it.note,
        }))
        return ok(route, {
          from, to, days: calendarDays(st, from, to).filter((d) => d.workday), items: today, problems: clone(st.problems),
          stats: statsFor(st, from, to), truncated: false, max_rows: 20000,
        })
      }
    }

    st.unknown.push(`${method} ${url.pathname}${url.search}`)
    return fail(route, 404, 'NOT_FOUND', `mock: tanınmayan uç ${method} ${url.pathname}`)
  })

  return st
}

/** Duyarlı (responsive) taramalar: yalnız rutin uçları; izin anlık görüntüsü mockApi'deki gibi kalır. */
export function mockOncallRoutinesMinimal(page) {
  return mockOncallRoutines(page, { permissions: false })
}
