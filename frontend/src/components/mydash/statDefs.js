/**
 * Renkli panel kutularının TANIMLARI (2026-10-13) — bağımlılıksız modül: hem katalog (myDashModel) hem okuma modeli
 * (statModel) buradan okur (döngüsel içe aktarma olmasın).
 */

/**
 * Kutu tanımları — SIRA katalog ve "Renkli panel duvarı" sırasıdır. `source` = okunan uç (aynı kaynak tek istek),
 * `perm` = görmek için gereken izin(ler) (myDashModel.WIDGET_CATALOG'a eklenir), `tab` = "Sayfayı aç".
 */
export const STAT_DEFS = Object.freeze([
  { type: 'stat_alarms_critical', source: 'alerts',   perm: 'alerts.read',     tab: 'alerthistory' },
  // `tabParams`: başlıktaki "sayfayı aç" görünümü (7/24 sekmesinin varsayılanı operatör olmayana Kapsam — konsol açılsın)
  { type: 'stat_noc_overdue',     source: 'noc',      perm: ['alerts.read', '@noc_console'], tab: 'noc',
    tabParams: { n_view: 'console', n_cnoc: 'sent', n_ccall: 'no', n_cst: 'open' } },
  { type: 'stat_alarm_oldest',    source: 'alerts',   perm: 'alerts.read',     tab: 'alerthistory' },
  { type: 'stat_alarms_unacked',  source: 'alerts',   perm: 'alerts.read',     tab: 'alerthistory' },
  { type: 'stat_monitors_down',   source: 'overview', perm: 'monitoring.read', tab: 'monitoring' },
  { type: 'stat_monitors_stale',  source: 'overview', perm: 'monitoring.read', tab: 'monitoring' },
  { type: 'stat_success_rate',    source: 'overview', perm: 'monitoring.read', tab: 'monitoring' },
  { type: 'stat_teams_failing',   source: 'overview', perm: 'monitoring.read', tab: 'monitoring' },
  { type: 'stat_service_outages', source: 'status',   perm: null,              tab: 'status' },
  { type: 'stat_incidents_severe', source: 'status',  perm: 'incidents.view',  tab: 'incident-history' },
  { type: 'stat_certs_expired',   source: 'stats',    perm: null,              tab: 'all' },
  { type: 'stat_certs_week',        source: 'stats',    perm: null,              tab: 'all' },
  { type: 'stat_certs_month',       source: 'stats',    perm: null,              tab: 'all' },
  { type: 'stat_cert_errors',     source: 'stats',    perm: null,              tab: 'all' },
  // Kanal teknik hata raporu (R14, 2026-10-17): bu dönem raporu başlanmamış AKTİF kanal — bir görevdir, kesinti değil
  // (varsa turuncu). Kaynak mevcut `GET /tech-error-reports/overview` (403 → yetki yok, gri). `preset: false`: "Renkli
  // panel duvarı" hazır düzeni DEĞİŞMEZ (kullanıcı kararı — mevcut duvarlar aynı kalır); "Kutu ekle"den eklenir.
  { type: 'stat_channel_reports_missing', source: 'channels', perm: 'tech_error_reports.read', tab: 'channelreports',
    tabParams: { te_st: 'missing' }, preset: false },
])

export const STAT_TYPES = Object.freeze(STAT_DEFS.map((d) => d.type))
/** "Renkli panel duvarı" hazır düzenine giren tanımlar (`preset: false` olmayanlar) — SIRA korunur. */
export const STAT_PRESET_DEFS = Object.freeze(STAT_DEFS.filter((d) => d.preset !== false))
const BY_TYPE = new Map(STAT_DEFS.map((d) => [d.type, d]))
export function statDef(type) { return BY_TYPE.get(type) || null }
