import { dateLocale } from '../i18n/dateLocale.js'

// Incident root-cause meta — sunucu {code, category} döndürür; burada kategori → renk + i18n etiket anahtarı eşlenir.
// Renkler AlertHistory.typeMeta paletiyle uyumlu (kırmızı=down, mavi=client/dns, turuncu=slow, amber=ssl/expiry).
export const RC_META = {
  server_error: { color: '#dc2626', key: 'incov.rc.serverError' },
  not_found:    { color: '#2563eb', key: 'incov.rc.notFound' },
  client_error: { color: '#2563eb', key: 'incov.rc.clientError' },
  down:         { color: '#dc2626', key: 'incov.rc.down' },
  refused:      { color: '#dc2626', key: 'incov.rc.refused' },
  timeout:      { color: '#dc2626', key: 'incov.rc.timeout' },
  unreachable:  { color: '#dc2626', key: 'incov.rc.unreachable' },
  slow:         { color: '#d97706', key: 'incov.rc.slow' },
  ssl:          { color: '#b45309', key: 'incov.rc.ssl' },
  expiry:       { color: '#b45309', key: 'incov.rc.expiry' },
  dns_failure:  { color: '#dc2626', key: 'incov.rc.dnsFailure' },
  dns:          { color: '#2563eb', key: 'incov.rc.dns' },
  domain:       { color: '#7c3aed', key: 'incov.rc.domain' },
  content:      { color: '#7c3aed', key: 'incov.rc.content' },
  cert:         { color: '#be123c', key: 'incov.rc.cert' },
  unknown:      { color: '#64748b', key: 'incov.rc.unknown' },
}

export function rcMeta(category) {
  return RC_META[category] || RC_META.unknown
}

// ISO (UTC) parse — sunucu 'yyyy-MM-ddTHH:mm:ss' (zone'suz UTC) döndürür → 'Z' ekle. Zone varsa dokunma.
export function parseUtc(s) {
  if (!s) return null
  const iso = /[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : s + 'Z'
  const d = new Date(iso)
  return isNaN(d.getTime()) ? null : d
}

// Süre (ms): resolved ise resolvedAt−startedAt, aksi halde now−startedAt (canlı).
export function durationMs(startedAt, resolvedAt, nowMs) {
  const start = parseUtc(startedAt)
  if (!start) return null
  const end = resolvedAt ? parseUtc(resolvedAt) : new Date(nowMs || Date.now())
  return end ? end.getTime() - start.getTime() : null
}

// Otomatik "Süre (dk)": olayın gerçek toplam süresi = OLUŞ → çözülme farkı (tam dakika).
// Tespit değil oluş tabanlı (tespit gecikmesi süreden düşmemeli). Geçersiz/negatif/eksik → null.
export function autoDurationMinutes(occurredAt, resolvedAt) {
  if (!occurredAt || !resolvedAt) return null
  const ms = durationMs(occurredAt, resolvedAt)
  if (ms == null || ms < 0) return null
  return Math.round(ms / 60000)
}

// Süre (ms) → insan-okur ("45 sn", "5 dk", "2 sa 3 dk", "3 g 4 sa"). Birimler i18n (t) ile.
export function formatDuration(ms, t) {
  if (ms == null || ms < 0) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s} ${t('incov.unit.sec')}`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} ${t('incov.unit.min')}`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} ${t('incov.unit.hour')}${m % 60 ? ' ' + (m % 60) + ' ' + t('incov.unit.min') : ''}`
  const d = Math.floor(h / 24)
  return `${d} ${t('incov.unit.day')}${h % 24 ? ' ' + (h % 24) + ' ' + t('incov.unit.hour') : ''}`
}

// Tam yerel timestamp + timezone (ör. "16 Ağu 2023 14:44:23 GMT+3").
// locale: uygulama dilinden ('tr-TR'/'en-GB', useDateLocale). undefined bırakılırsa çalışma-ortamı
// dili kullanılır → İngilizce Windows'ta "Jul 12" gibi görünür; bu yüzden çağıran locale geçmeli.
export function formatIncidentTime(s, locale = dateLocale()) {
  const d = parseUtc(s)
  if (!d) return '—'
  try {
    return d.toLocaleString(locale, {
      year: 'numeric', month: 'short', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
      timeZoneName: 'short', hour12: false,
    })
  } catch { return d.toISOString() }
}
