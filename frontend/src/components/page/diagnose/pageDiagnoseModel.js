/**
 * Sayfa Bütünlüğü uçtan uca tanılama MODELİ — saf fonksiyonlar, React yok (2026-10-05). Sonucun genel kısmı (hüküm, yollar,
 * adımlar, zamanlama, döküm) HTTP tanılamasının modelini kullanır (`httpDiagnoseModel.js`; kod çevirisi `pgdx` ad alanını
 * bilir); burada yalnız KAYNAK çözümlemesine özgü olanlar: kayıt durumu, sayaçlar, sorun satırları, rapor bölümü.
 */
import { formatBytes } from '../../http/diagnose/httpDiagnoseModel.js'

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)
const arr = (v) => (Array.isArray(v) ? v : [])

/** Sorun türleri — sunucunun sırasıyla (alarma sayılan önce, sonra bu sıra). */
export const ISSUE_KINDS = ['BROKEN', 'TIMEOUT', 'MIXED_CONTENT', 'BLOCKED', 'SLOW']

/** Sorun türü → ton (rozet rengi; metin ayrıca). */
export const ISSUE_TONE = { BROKEN: 'danger', TIMEOUT: 'warning', MIXED_CONTENT: 'warning', BLOCKED: 'muted', SLOW: 'info' }

/** Sorun türü → mevcut etiket anahtarı (izleme sayfasının sorun tablosuyla AYNI dil). */
export function issueLabelKey(kind) {
  return ISSUE_KINDS.includes(kind) ? `page.issue_${kind}` : null
}

/** İzlemenin kaydedeceği durum → mevcut etiket anahtarı. */
export const STATUS_KEY = {
  OK: 'page.statusOk', DEGRADED: 'page.statusDegraded', DOWN: 'page.statusDown', CONFIG_ERROR: 'page.statusConfigError',
}

/** Kayıt durumu → ton. DEGRADED ama alarm yoksa (alarm dışı sorunlar) uyarı; alarm varsa tehlike. */
export function recordedTone(page) {
  const s = page?.recorded_status
  if (s === 'OK') return 'success'
  if (s === 'DEGRADED') return page?.monitor_ok ? 'warning' : 'danger'
  if (s === 'DOWN' || s === 'CONFIG_ERROR') return 'danger'
  return 'muted'
}

/** Sayaç satırı (Metric) — sıra sabit; `bad` sayının kırmızı çizilmesi. */
export function counterRows(page) {
  const tt = page?.totals || {}
  const n = (v) => (isNum(v) ? v : null)
  return [
    { key: 'resources', value: n(tt.resources), bad: false },
    { key: 'broken', value: n(tt.broken), bad: (tt.broken || 0) > 0 },
    { key: 'timeouts', value: n(tt.timeouts), bad: (tt.timeouts || 0) > 0 },
    { key: 'mixed', value: n(tt.mixed), bad: (tt.mixed || 0) > 0 },
    { key: 'blocked', value: n(tt.blocked), bad: false },
    { key: 'slow', value: n(tt.slow), bad: false },
    { key: 'alarm', value: n(tt.alarm), bad: (tt.alarm || 0) > 0 },
  ]
}

/** Sorun satırları — çizime hazır (sunucu sırası korunur). */
export function issueRows(page) {
  return arr(page?.issues).filter(Boolean).map((r, i) => ({
    i,
    url: r.url == null ? '' : String(r.url),
    type: r.resource_type || null,
    kind: r.kind || null,
    status: isNum(r.status) ? r.status : null,
    ms: isNum(r.ms) ? r.ms : null,
    firstParty: r.first_party === true,
    alarm: r.alarm === true,
    via: r.via || null,
    sourcePage: r.source_page || null,
  }))
}

/** Satırın kısa meta metni (kartta / raporda): "IMG · HTTP 404 · 12 ms · Kendi". */
export function issueMeta(row, t) {
  return [
    row.type,
    row.status != null ? `HTTP ${row.status}` : null,
    row.ms != null ? `${row.ms} ms` : null,
    t(row.firstParty ? 'pgdx.party.first' : 'pgdx.party.third'),
  ].filter(Boolean).join(' · ')
}

/** Alarm kuralları özeti — `[{ key, on }]` + yavaşlık eşiği. */
export function ruleRows(page) {
  const tg = page?.toggles || {}
  return [
    { key: 'thirdParty', on: tg.alert_third_party === true },
    { key: 'mixed', on: tg.alert_mixed_content !== false },
    { key: 'timeout', on: tg.alert_timeout !== false },
  ]
}

/** Raporun Sayfa Bütünlüğü bölümü (`buildReport` `extra`). Ekranda ne varsa: kayıt durumu, sayaçlar, kurallar, sorunlar. */
export function pageReportExtra({ w, data, t }) {
  const p = data?.page
  if (!p) return
  w.h2(t('pgdx.analysis.title'))
  if (p.recorded_status) w.kv(t('pgdx.analysis.recorded'), t(STATUS_KEY[p.recorded_status] || 'page.statusUnknown'))
  if (p.analyzed === false) {
    w.p(t('pgdx.analysis.unanalyzed'))
    return
  }
  w.kv(t('pgdx.analysis.page'), [
    p.http_status != null ? `HTTP ${p.http_status}` : null,
    isNum(p.response_ms) ? `${p.response_ms} ms` : null,
    isNum(p.body_bytes) ? formatBytes(p.body_bytes) : null,
  ].filter(Boolean).join(' · ') || '—')
  w.kv(t('pgdx.analysis.counters'), counterRows(p).map((c) => `${t(`pgdx.count.${c.key}`)}: ${c.value ?? '—'}`).join(' · '))
  const tt = p.totals || {}
  w.kv(t('pgdx.analysis.parties'), t('pgdx.analysis.partiesValue', tt.first_party ?? 0, tt.third_party ?? 0))
  w.kv(t('pgdx.analysis.rules'), [
    ...ruleRows(p).map((r) => `${t(`pgdx.rule.${r.key}`)}: ${t(r.on ? 'pgdx.rule.on' : 'pgdx.rule.off')}`),
    t('pgdx.rule.slow', p.toggles?.slow_resource_ms ?? '—'),
  ].join(' · '))
  const rows = issueRows(p)
  if (rows.length) {
    w.h3(t('pgdx.analysis.issues'))
    for (const r of rows) {
      const kind = issueLabelKey(r.kind) ? t(issueLabelKey(r.kind)) : (r.kind || '—')
      w.li(`[${kind}${r.alarm ? ` · ${t('pgdx.alarm.yes')}` : ''}] ${r.url} — ${issueMeta(r, t)}`)
    }
    if ((p.issues_total || 0) > rows.length) w.p(t('pgdx.analysis.issuesShown', p.issues_total, rows.length))
  }
}
