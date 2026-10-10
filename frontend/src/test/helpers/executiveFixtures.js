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

/** Gelecekte eklenecek bölüm (ör. TLS notu) — i18n anahtarı yok: ekran sunucu metnine düşmeli. */
const future = {
  key: 'tls-grade', order: 50, title: 'TLS yapılandırma notu', status: 'ok', snapshot: false, as_of: null, headline_kpi: null,
  verdicts: [{ code: 'GRADE_A', tone: 'ok', text: 'Sertifikaların %90\'ı A notunda.', params: [] }],
  kpis: [], tables: [], notes: [], data: {},
}

export function summary(overrides = {}) {
  const sections = [availability, noise, expirations, renewals, future]
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

export function response(overrides = {}, extra = {}) {
  return { success: true, data: summary(overrides), months: MONTHS, default_month: '2026-09', can_configure: true, ...extra }
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
