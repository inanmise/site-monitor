/**
 * Olay ve Hata Geçmişi Excel dışa aktarımı (2026-11-26, "Bul ve Raporla") — SAF yardımcılar. Satırlar sunucunun JSON dışa
 * aktarımından gelir (`/incidents/export?format=json`: listenin süzgeçleri + sıralaması + okuma kapsamı, en çok 10 000 satır;
 * zamanlar İstanbul yereli, kişiler görünen ad, markdown düz metin). Dosya tarayıcıda `utils/xlsxWriter` ile kurulur (tembel
 * parça — açılış paketine girmez).
 *
 * <p>Sütunlar sunucunun `columns` listesinin SIRASIYLA (`IncidentExportRows.COLUMNS`); başlıklar çevrilir (`inc.xcol.<ad>`;
 * bilinmeyen sütun adıyla). Ham kod basılmaz: önem, durum, kategori, postmortem durumu ve evet / hayır çevrilir. Bilinmeyen
 * değer boş hücre — uydurma sayı yok.
 */

/** Sunucu sütunları — `IncidentExportRows.COLUMNS` ile AYNI sıra (test Java kaynağını okur). */
export const EXPORT_COLUMNS = Object.freeze([
  'id', 'title', 'severity', 'status', 'category', 'occurred_at', 'detected_at', 'resolved_at', 'duration_minutes',
  'team', 'channel', 'service', 'problem_types', 'error_code', 'function_code', 'channel_code',
  'affected_services', 'affected_app', 'affected_systems', 'affected_customers', 'affected_transactions',
  'tech_error_count', 'outage_related', 'outage_id', 'sla_breached', 'error_budget_burn_pct', 'tags', 'runbook_url',
  'alert_event_id', 'postmortem', 'created_by', 'created_at', 'updated_by', 'updated_at',
  'rca_summary', 'description', 'resolution_steps', 'business_impact',
])

const NUMERIC = new Set(['id', 'duration_minutes', 'affected_customers', 'affected_transactions', 'tech_error_count',
  'error_budget_burn_pct', 'alert_event_id'])
const BOOLEAN = new Set(['outage_related', 'sla_breached'])
const WIDE_TEXT = new Set(['rca_summary', 'description', 'resolution_steps', 'business_impact'])

const tr = (t, key, fallback) => {
  const v = t(key)
  return v && v !== key ? v : fallback
}

/** Tek hücre — çevrilmiş metin, sayı ya da boş. */
export function cellOf(col, value, t) {
  if (value == null || value === '') return ''
  if (BOOLEAN.has(col)) {
    if (value === true) return t('inc.sum.yes')
    if (value === false) return t('inc.sum.no')
    return ''
  }
  if (NUMERIC.has(col)) {
    const n = Number(value)
    return Number.isFinite(n) ? n : ''
  }
  if (col === 'severity') return tr(t, 'inc.sev' + value, String(value))
  if (col === 'status') return tr(t, 'inc.st' + value, String(value))
  if (col === 'category') return tr(t, 'inc.cat' + value, String(value))
  if (col === 'postmortem') return tr(t, 'pm.status.' + String(value).toUpperCase(), String(value))
  return String(value)
}

/**
 * Başlık anahtarı adı farklı olan sütunlar — `error` içeren anahtar adları hata iletisi kapısına (errorMessageQuality) takılır;
 * bu sütunların başlığı ileti değil, ETİKETTİR.
 */
const XCOL_KEY = Object.freeze({ error_code: 'ecode', tech_error_count: 'techErrCount', error_budget_burn_pct: 'budgetBurn' })

/** Başlık satırı + veri satırları (xlsxWriter sayfası `rows`). `columns`: sunucunun sırası (yoksa {@link EXPORT_COLUMNS}). */
export function historySheetRows(columns, rows, t) {
  const cols = Array.isArray(columns) && columns.length ? columns : EXPORT_COLUMNS
  const out = [cols.map((c) => tr(t, 'inc.xcol.' + (XCOL_KEY[c] || c), c))]
  for (const r of rows || []) out.push(cols.map((c) => cellOf(c, r?.[c], t)))
  return out
}

/** Excel sayfası (xlsxWriter biçimi) — dondurulmuş başlık + otomatik süzgeç. */
export function historySheet(columns, rows, t) {
  const cols = Array.isArray(columns) && columns.length ? columns : EXPORT_COLUMNS
  return {
    name: t('inc.export.sheet'),
    rows: historySheetRows(cols, rows, t),
    headerRows: 1, freezeHeader: true, autoFilter: true,
    widths: cols.map((c) => (WIDE_TEXT.has(c) ? 60 : c === 'title' ? 40 : NUMERIC.has(c) ? 10 : 18)),
  }
}

/** Excel dosya adı: `olay-gecmisi-yyyyAAgg-SSdd.xlsx` (yerel saat). */
export function historyExportName(now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  return `olay-gecmisi-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}.xlsx`
}
