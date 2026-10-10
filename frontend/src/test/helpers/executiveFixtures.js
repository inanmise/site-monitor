/**
 * Yönetici Özeti test verisi — sunucunun `/api/executive-summary` JSON'unun birebir biçimi (ExecutiveSummary +
 * SectionResult kayıtları, snake_case alanlar). Vitest ve Playwright (e2e/executive-summary.spec.js) aynı veriyi kullanır.
 * Uzun alan adı ve Türkçe takım adı bilerek (taşma / kırpma zorlaması).
 */
export const LONG_HOST = 'raporlama-ve-analitik-platformu.ic-servisler.example.com'
const pct = (v) => ({ value: v, format: 'pct' })
const num = (v) => ({ value: v, format: 'num' })

const availability = {
  key: 'availability', order: 10, title: 'Erişilebilirlik hedefi uyumu', status: 'attention', snapshot: false, as_of: null,
  headline_kpi: 'org_availability',
  verdicts: [
    { code: 'ORG_MET', tone: 'ok', text: 'Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.', params: [pct(99.95), pct(99.9)] },
    { code: 'TEAMS_BELOW', tone: 'warn', text: '4 takımdan 1 tanesi hedefin altında.', params: [4, 1] },
  ],
  kpis: [
    { code: 'org_availability', label: 'Kurum erişilebilirliği', value: 99.95, format: 'pct', tone: 'ok', hint: 'Hedef %99,9', hint_params: [pct(99.9)], delta: -0.02, delta_format: 'pp', delta_tone: 'bad' },
    { code: 'target', label: 'Erişilebilirlik hedefi', value: 99.9, format: 'pct', tone: 'neutral', hint: 'Kurum hedefi (Ayarlar)', hint_params: [] },
    { code: 'teams_meeting', label: 'Hedefi karşılayan takım', value: 3, format: 'int', tone: 'warn', hint: '4 takımdan', hint_params: [4] },
    { code: 'services_meeting', label: 'Hedefi karşılayan hizmet', value: 11, format: 'int', tone: 'warn', hint: '12 hizmetten', hint_params: [12] },
    { code: 'budget_used', label: 'Hata bütçesi kullanımı', value: 50, format: 'pct', tone: 'ok', hint: 'x', hint_params: [] },
    { code: 'downtime_equiv', label: 'Eşdeğer kesinti', value: 21.6, format: 'minutes', tone: 'neutral', hint: 'x', hint_params: [] },
  ],
  tables: [
    {
      code: 'teams', title: 'Takımlara göre', total: 2, empty: 'Bu ay ölçülen takım yok.',
      columns: [
        { code: 'team', label: 'Takım', type: 'team' }, { code: 'monitors', label: 'İzleme', type: 'int' },
        { code: 'checks', label: 'Kontrol', type: 'int' }, { code: 'availability', label: 'Erişilebilirlik', type: 'pct' },
        { code: 'prev', label: 'Önceki ay', type: 'pct' }, { code: 'delta', label: 'Fark', type: 'pp' },
        { code: 'met', label: 'Hedef', type: 'status' },
      ],
      rows: [
        { team: 'Ödeme Ağ Geçidi Takımı', team_id: 1, monitors: 12, checks: 518400, availability: 99.71, prev: 99.95, delta: -0.24, met: 'bad' },
        { team: null, team_id: null, monitors: 3, checks: 129600, availability: 99.99, prev: null, delta: null, met: 'ok' },
      ],
    },
    {
      code: 'worst_services', title: 'En düşük erişilebilirlikli hizmetler', total: 14, empty: 'x',
      columns: [
        { code: 'service', label: 'Hizmet', type: 'service' }, { code: 'team', label: 'Takım', type: 'team' },
        { code: 'availability', label: 'Erişilebilirlik', type: 'pct' }, { code: 'met', label: 'Hedef', type: 'status' },
      ],
      rows: [
        { service: 'Ödeme', ungrouped: false, team: 'Ödeme Ağ Geçidi Takımı', availability: 99.5, met: 'bad' },
        { service: null, ungrouped: true, team: 'Takım B', availability: 99.92, met: 'ok' },
      ],
    },
  ],
  notes: [
    { code: 'METHOD', text: 'Erişilebilirlik = başarılı kontrol / toplam kontrol.', params: [] },
    { code: 'UNMAPPED', text: '2 silinmiş izlemenin kontrolleri hesaba katılmadı.', params: [2] },
  ],
  data: {
    target: 99.9, source: 'hourly_rollup', approximate: false, org_availability: 99.95, prev_availability: 99.97,
    daily: Array.from({ length: 30 }, (_, i) => ({
      date: `2026-09-${String(i + 1).padStart(2, '0')}`, availability: i === 11 ? 99.4 : 99.96, checks: 17280,
    })),
  },
}

const noise = {
  key: 'noise', order: 20, title: 'En gürültülü alarmlar', status: 'attention', snapshot: false, as_of: null, headline_kpi: 'total_alarms',
  verdicts: [
    { code: 'TOTAL_UP', tone: 'warn', text: 'Bu ay 120 alarm açıldı; geçen aya (80) göre %50 artış.', params: [120, 80, num(50)] },
    { code: 'TOP_TARGET', tone: 'neutral', text: 'x', params: [LONG_HOST, { value: 'http', format: 'monitor_type' }, 40, pct(33.3)] },
  ],
  kpis: [
    { code: 'total_alarms', label: 'Açılan alarm', value: 120, format: 'int', tone: 'warn', hint: 'x', hint_params: [num(4)], delta: 50, delta_format: 'pct_change', delta_tone: 'bad' },
    { code: 'mtta', label: 'MTTA', value: 75, format: 'minutes', tone: 'neutral', hint: 'x', hint_params: [60] },
  ],
  tables: [{
    code: 'top_targets', title: 'En çok alarm üreten hedefler', total: 1, empty: 'x',
    columns: [
      { code: 'target', label: 'Hedef', type: 'text' }, { code: 'monitor_type', label: 'Tür', type: 'monitor_type' },
      { code: 'count', label: 'Alarm', type: 'int' }, { code: 'flapping', label: 'Dalgalanma', type: 'status' },
    ],
    rows: [{ target: LONG_HOST, monitor_type: 'http', count: 40, flapping: 'warn' }],
  }],
  notes: [{ code: 'METHOD', text: 'x', params: [5, 10] }],
  data: {},
}

const expirations = {
  key: 'expirations', order: 30, title: 'Yaklaşan sertifika bitişleri', status: 'critical', snapshot: true,
  as_of: '2026-10-01T06:00:00', headline_kpi: 'within30',
  verdicts: [{ code: 'EXPIRED', tone: 'bad', text: '1 sertifikanın süresi dolmuş ve hâlâ izlemede.', params: [1] }],
  kpis: [{ code: 'within30', label: '30 gün içinde', value: 4, format: 'int', tone: 'warn', hint: 'bitecek sertifika', hint_params: [] }],
  tables: [{
    code: 'soonest', title: 'En yakın bitişler', total: 2, empty: 'x',
    columns: [
      { code: 'domain', label: 'Sertifika', type: 'text' }, { code: 'tier', label: 'Seviye', type: 'tier' },
      { code: 'days', label: 'Kalan', type: 'days' }, { code: 'expiry', label: 'Bitiş', type: 'date' },
    ],
    rows: [
      { domain: 'expired.example.com', tier: 1, days: -3, expiry: '2026-09-28' },
      { domain: LONG_HOST, tier: null, days: 9, expiry: '2026-10-10' },
    ],
  }],
  notes: [{ code: 'ASOF', text: 'x', params: [{ value: '2026-10-01T06:00:00', format: 'datetime' }] }],
  data: {},
}

const renewals = {
  key: 'renewals', order: 40, title: 'Yenileme süresine uyum', status: 'critical', snapshot: false, as_of: null, headline_kpi: 'on_time_pct',
  verdicts: [{ code: 'COMPLIANCE', tone: 'warn', text: 'x', params: [5, 2, pct(40)] }],
  kpis: [{ code: 'on_time_pct', label: 'Zamanında yenileme', value: 40, format: 'pct', tone: 'warn', hint: 'Hedef: bitişten en az 30 gün önce', hint_params: [30] }],
  tables: [],
  notes: [],
  data: { by_class: { ON_TIME: 2, LATE: 1, LAST_MINUTE: 1, AFTER_EXPIRY: 1 }, total: 5 },
}

const code = (value, format) => ({ value, format })

const tlsGrade = {
  key: 'tls-grade', order: 50, title: 'TLS yapılandırma notu', status: 'critical', snapshot: true,
  as_of: '2026-10-01T06:00:00', headline_kpi: 'top_share',
  verdicts: [
    { code: 'DISTRIBUTION', tone: 'neutral', text: 'Notlanan 40 uç nokta: A/A+ payı %70, C ve altı %7,5.', params: [40, pct(70), pct(7.5)] },
    { code: 'F_GRADES', tone: 'bad', text: '1 uç nokta F notunda; 1 tanesi Seviye 1 (müşteriye açık üretim).', params: [1, 1] },
    { code: 'TOP_REASON', tone: 'neutral', text: 'Notu A\'nın altına çeken en sık neden: TLS 1.0 açık (6 uç nokta).', params: [code('TLS10_ENABLED', 'tls_reason'), 6] },
  ],
  kpis: [
    { code: 'top_share', label: 'A / A+ payı', value: 70, format: 'pct', tone: 'neutral', hint: 'x', hint_params: [40, 28] },
    { code: 'f_count', label: 'F notu', value: 1, format: 'int', tone: 'bad', hint: 'x', hint_params: [1] },
    { code: 'probe_coverage', label: 'TLS profili taranan', value: null, format: 'pct', tone: 'neutral', hint: null, hint_params: [] },
  ],
  tables: [
    {
      code: 'lowest', title: 'En düşük notlu uç noktalar (C ve altı)', total: 3, empty: 'x',
      columns: [
        { code: 'domain', label: 'Sertifika', type: 'text' }, { code: 'tier', label: 'Seviye', type: 'tier' },
        { code: 'grade', label: 'Not', type: 'text' }, { code: 'reason', label: 'Neden', type: 'tls_reason' },
        { code: 'state', label: 'Durum', type: 'status' },
      ],
      rows: [
        { domain: LONG_HOST, tier: 1, grade: 'F', reason: 'CERT_EXPIRED', state: 'bad' },
        { domain: 'eski.example.com', tier: 3, grade: 'C', reason: 'NO_TLS12', state: 'warn' },
      ],
    },
  ],
  notes: [{ code: 'METHOD', text: 'x', params: [] }],
  data: { grades: { 'A+': 8, A: 20, B: 9, C: 2, D: 0, F: 1 }, graded: 40 },
}

const cryptoReadiness = {
  key: 'crypto-readiness', order: 60, title: 'Kripto envanteri ve PQC hazırlığı', status: 'attention', snapshot: true,
  as_of: '2026-10-01T05:59:00', headline_kpi: 'broken',
  verdicts: [
    { code: 'NO_BROKEN', tone: 'ok', text: 'Bugün zayıf algoritma kullanan uç nokta yok.', params: [] },
    { code: 'LEGACY', tone: 'warn', text: 'x', params: [12, pct(30), 2] },
  ],
  kpis: [
    { code: 'broken', label: 'Bugün zayıf', value: 0, format: 'int', tone: 'ok', hint: 'x', hint_params: [] },
    { code: 'p1', label: 'Öncelik P1', value: 2, format: 'int', tone: 'warn', hint: 'x', hint_params: [5, 10, 13] },
  ],
  tables: [
    {
      code: 'migration', title: 'Öncelikli geçiş listesi', total: 30, empty: 'x',
      columns: [
        { code: 'domain', label: 'Sertifika', type: 'text' }, { code: 'category', label: 'Kategori', type: 'crypto_category' },
        { code: 'band', label: 'Öncelik', type: 'pqc_band' }, { code: 'score', label: 'Puan', type: 'int' },
        { code: 'days', label: 'Kalan', type: 'days' },
      ],
      rows: [{ domain: 'odeme.example.com', category: 'LEGACY', band: 'P1', score: 75, days: 20 }],
    },
  ],
  notes: [{ code: 'KEX', text: 'x', params: [] }],
  data: { by_category: { BROKEN: 0, LEGACY: 12, MODERN: 26, PQC_READY: 0, UNKNOWN: 2 }, total: 40 },
}

const dataQuality = {
  key: 'data-quality', order: 70, title: 'Takım veri kalitesi puanı', status: 'attention', snapshot: true,
  as_of: '2026-10-01T05:58:00', headline_kpi: 'org_score',
  verdicts: [
    { code: 'SCORE', tone: 'warn', text: 'x', params: [71, code('NEEDS_ATTENTION', 'dq_band'), 17] },
    { code: 'MONTH_DOWN', tone: 'warn', text: 'x', params: [78, 71, 7] },
  ],
  kpis: [
    { code: 'org_score', label: 'Kurum puanı', value: 71, format: 'int', tone: 'warn', hint: 'x', hint_params: [code('NEEDS_ATTENTION', 'dq_band'), 17] },
    { code: 'month_end', label: 'Ay sonu puanı', value: 71, format: 'int', tone: 'warn', hint: 'x', hint_params: [{ value: '2026-09-30', format: 'date' }], delta: -7, delta_format: 'pp', delta_tone: 'bad' },
  ],
  tables: [
    {
      code: 'costly_rules', title: 'En çok puan kaybettiren kurallar', total: 2, empty: 'x',
      columns: [
        { code: 'rule', label: 'Kural', type: 'dq_rule' }, { code: 'failing', label: 'Kusurlu', type: 'int' },
        { code: 'points_lost', label: 'Kaybettirdiği puan', type: 'num' },
      ],
      rows: [
        { rule: 'INV_NO_TEAM', failing: 4, points_lost: 6.2 },
        { rule: 'YENI_KURAL', failing: 1, points_lost: 0.5 },
      ],
    },
  ],
  notes: [],
  data: {},
}

/** Gelecekte eklenecek bölüm — i18n anahtarı yok: ekran sunucu metnine düşmeli (genişleme noktası). */
const future = {
  key: 'future-section', order: 80, title: 'Gelecek bölüm', status: 'ok', snapshot: false, as_of: null, headline_kpi: null,
  verdicts: [{ code: 'GRADE_A', tone: 'ok', text: 'Gelecek bölümün sunucu metni.', params: [] }],
  kpis: [], tables: [], notes: [], data: {},
}

export function summary(overrides = {}) {
  const sections = [availability, noise, expirations, renewals, tlsGrade, cryptoReadiness, dataQuality, future]
  return {
    month: '2026-09', month_label: 'Eylül 2026', from: '2026-08-31T21:00:00', to: '2026-09-30T21:00:00', complete: true,
    generated_at: '2026-10-01T06:00:00', source: 'live', status: 'critical',
    headline: sections.map((s) => s.verdicts[0]),
    headline_kpis: sections.filter((s) => s.headline_kpi).map((s) => ({ section: s.key, kpi: s.kpis.find((k) => k.code === s.headline_kpi) })),
    sections,
    settings: { availability_target: 99.9, renewal_target_days: 30, timezone: 'Europe/Istanbul' },
    ...overrides,
  }
}

export const MONTHS = [
  { month: '2026-10', label: 'Ekim 2026', current: true, status: null },
  { month: '2026-09', label: 'Eylül 2026', current: false, status: 'SENT', sent_at: '2026-10-01T06:00:12' },
  { month: '2026-08', label: 'Ağustos 2026', current: false, status: null },
]

/** Seçilebilir kapsamlar (kurum + iki takım) — takım adları uydurma. */
export const SCOPES = {
  org: true,
  teams: [
    { id: 5, name: 'Ödeme Ağ Geçidi Takımı', can_configure: true },
    { id: 6, name: 'Ağ Operasyon', can_configure: true },
  ],
}

export function response(overrides = {}, extra = {}) {
  return { success: true, data: summary(overrides), months: MONTHS, default_month: '2026-09', can_configure: true,
    can_configure_team: false, can_configure_any_team: true, scopes: SCOPES, ...extra }
}

/** Takım kapsamlı özet yanıtı (takım 5). */
export function teamResponse(overrides = {}, extra = {}) {
  return response({ scope: { kind: 'team', team_id: 5, team_name: 'Ödeme Ağ Geçidi Takımı' }, ...overrides },
    { can_configure_team: true, ...extra })
}

/** Gönderim görünümü: takım listesi (`GET /api/executive-summary/teams`). */
export const TEAMS = {
  teams: [
    { team_id: 6, team_name: 'Ağ Operasyon', team_active: true, enabled: false, recipient_count: 0, notes: ['NO_MANAGER'],
      last_status: null, last_sent_at: null, updated_at: null },
    { team_id: 5, team_name: 'Ödeme Ağ Geçidi Takımı', team_active: true, enabled: true, recipient_count: 3, notes: [],
      last_status: 'SENT', last_sent_at: '2026-10-01T06:00:20', updated_at: '2026-09-20T08:00:00' },
  ],
  report_month: '2026-09', next_runs: ['2026-11-01T06:00:00', '2026-12-01T06:00:00'], org_enabled: false, bcc_chunk: 100,
}

/** Takım ayrıntısı (`GET /api/executive-summary/teams/5`) — kullanıcı kimlikleri opak (global olmayan görüntüleyici). */
export const TEAM_DETAIL = {
  team_id: 5, team_name: 'Ödeme Ağ Geçidi Takımı', team_active: true, enabled: true, include_manager: true,
  include_team_admins: true, extra_emails: '', updated_at: '2026-09-20T08:00:00', updated_by: 'yonetici',
  manager: { user_id: 'u-100', name: 'Müdür Örnek', title: 'Birim Müdürü', email: 'mudur@example.com', active: true },
  team_admins: [{ user_id: 'u-101', name: 'Kapsamlı Müdür', title: null, email: 'kapsamli@example.com', active: true }],
  members: [
    { user_id: 'u-201', name: 'Ayşe Örnek', title: 'Kıdemli Uzman', email: 'ayse@example.com', active: true, selected: true },
    { user_id: 'u-202', name: 'Mehmet Örnek', title: null, email: 'mehmet@example.com', active: true, selected: false },
    { user_id: 'u-203', name: 'Epostasız Üye', title: 'Stajyer', email: null, active: true, selected: false },
  ],
  recipient_count: 3, recipient_preview: ['mudur@example.com', 'kapsamli@example.com', 'ayse@example.com'],
  recipient_counts: { manager: 1, team_admins: 1, members: 1, extra: 0, dropped_inactive: 0 },
  notes: [],
  history: [{ month: '2026-09', status: 'SENT', recipients: 3, sent_at: '2026-10-01T06:00:20', detail: 'SENT ×3' }],
  next_runs: ['2026-11-01T06:00:00', '2026-12-01T06:00:00'],
}

export const SETTINGS = {
  enabled: false, cron: '0 0 9 1 * *', recipients: 'yonetim@example.com', include_global_admins: true,
  availability_target: 99.9, renewal_target_days: 30,
  recipient_count: 3, recipient_explicit: 1, recipient_admins: 2, recipient_dropped_inactive: 1, recipient_preview: [],
  next_runs: ['2026-11-01T06:00:00', '2026-12-01T06:00:00'], bcc_chunk: 100,
  history: [
    { month: '2026-09', status: 'SENT', recipients: 3, sent_at: '2026-10-01T06:00:12', detail: 'SENT ×3' },
    { month: '2026-08', status: 'SKIPPED_DISABLED', recipients: null, sent_at: '2026-09-01T06:00:00', detail: 'kapalı' },
  ],
}
