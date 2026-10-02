/**
 * Alarmdan olay kaydı açma (2026-10-01) — saf model: alarm satırı → Olay & Hata Geçmişi formunun ÖN DOLGUSU.
 *
 * <p>Form, Olay & Hata Geçmişi'nin kendi formudur (`incidenthistory/IncidentFormModal`); burada yalnız başlangıç değerleri
 * kurulur, kullanıcı kaydetmeden önce her alanı değiştirebilir, açık bir "Kaydet" olmadan hiçbir kayıt oluşmaz. Sunucu
 * `alert_event_id`'yi doğrular (alarm var mı → 400, kullanıcı görebiliyor mu → 403).
 *
 * <p>Eşlemeler:
 * - önem = alarm seviyesi (KRİTİK → CRITICAL, YÜKSEK → HIGH, UYARI → MEDIUM); olay formunun önem seçenekleri bunlardır.
 * - kategori = alarm ailesi (DNS/Port/Ping/Erişim → NETWORK, alan adı → INFRASTRUCTURE, HTTP/içerik/sayfa/sentetik →
 *   APPLICATION, sertifika → CERTIFICATE).
 * - oluş = alarmın açılışı, çözülme = (çözülmüşse) alarmın kapanışı; durum = OPEN | RESOLVED.
 * - takım = alarmın sahibi: damgalı takım → SY → UG (alarm detayındaki takım bilgisiyle aynı sıra).
 */
import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { alertLink, alertSourceTab } from './alertHistoryModel.js'

/** Alarm seviyesi → olay önemi. Bilinmeyen seviye formun varsayılanına (HIGH) düşer. */
export function severityFromAlertLevel(level) {
  switch (String(level || '').toUpperCase()) {
    case 'CRITICAL': return 'CRITICAL'
    case 'HIGH': return 'HIGH'
    case 'WARNING': case 'MEDIUM': return 'MEDIUM'
    case 'LOW': case 'INFO': return 'LOW'
    default: return 'HIGH'
  }
}

/** Alarm türü → olay kategorisi. */
export function categoryFromAlertType(type) {
  const tab = alertSourceTab(type)
  if (tab === 'dns' || tab === 'port' || tab === 'ping') return 'NETWORK'
  if (tab === 'domain') return 'INFRASTRUCTURE'
  if (tab) return 'APPLICATION'
  return type === 'ACCESSIBILITY' ? 'NETWORK' : 'CERTIFICATE'
}

/** Sunucu zaman damgası → formun UTC ISO biçimi (`yyyy-MM-ddTHH:mm:ss`, ek yok); ayrıştırılamazsa ''. */
export function toFormIso(s) {
  if (!s) return ''
  const v = String(s).trim().replace(/Z$/, '').replace(/\.\d+$/, '')
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(v)) return v
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(v)) return `${v}:00`
  return ''
}

/** Alarmın sahip takımı — damgalı takım, yoksa envanter SY, o da yoksa UG. */
export function alertOwnerTeam(a, teamName) {
  if (!a) return { id: null, name: '' }
  if (a.team_id != null) return { id: a.team_id, name: teamName || a.team_name || '' }
  if (a.sy_team_id != null) return { id: a.sy_team_id, name: a.sy_team_name || '' }
  if (a.ug_team_id != null) return { id: a.ug_team_id, name: a.ug_team_name || '' }
  return { id: null, name: '' }
}

const clip = (s, n) => {
  const v = String(s ?? '')
  return v.length > n ? v.slice(0, n) : v
}

/**
 * Olay formunun ön dolgusu (IncidentFormModal `initial` — EMPTY_FORM ile birleştirilir). `alert_event_id` kayıtla
 * sunucuya gider (payloadFromForm tüm alanları yollar). Açıklama alarm mesajı + alarma bağlantıdır (markdown).
 */
export function incidentPrefillFromAlert(a, t, { teamName } = {}) {
  if (!a) return null
  const owner = alertOwnerTeam(a, teamName)
  const target = a.domain || '—'
  const typeLabel = alertTypeLabel(t, a.alert_type) || a.alert_type || ''
  const ref = `${t('alh.incident.descRef', a.id)}: ${alertLink(a)}`
  return {
    title: clip(typeLabel ? `${target} · ${typeLabel}` : target, 255),
    occurred_at: toFormIso(a.created_at),
    resolved_at: a.resolved ? toFormIso(a.resolved_at) : '',
    severity: severityFromAlertLevel(a.alert_level),
    status: a.resolved ? 'RESOLVED' : 'OPEN',
    category: categoryFromAlertType(a.alert_type),
    service: clip(a.domain || '', 500).replace(/,/g, ' '),
    team_id: owner.id != null ? String(owner.id) : '',
    team_name: owner.name || '',
    description: a.message ? `${a.message}\n\n${ref}` : ref,
    alert_event_id: a.id,
  }
}
