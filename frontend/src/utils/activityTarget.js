/**
 * Aktivite satırı → ilgili izleme sekmesi (2026-09-20, kullanıcı bildirimi: "ping logunu görünce o ping izlemesine
 * gitmeliyim"). Backend MonitorRefResolver ile aynı kural: sertifika → Sertifika Panosu (?domain=alan), uptime → Durum
 * İzleme (?q=hedef), diğer türler kendi sekmesine ?monitor=id ile (scripted param taşımaz). Bilinmeyen tür → null.
 *
 * Tek kaynak (2026-10-13): Etkinlik sayfası ve Kişisel Panom'un "Son etkinlik" kutusu aynı hedefi kullanır.
 */
const MONITOR_TABS = { DOMAIN: 'domain', HTTP: 'http', PORT: 'port', PING: 'ping', DNS: 'dns', KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed', SCRIPTED: 'scripted' }

export function activityTarget(row) {
  if (!row) return null
  const type = String(row.monitor_type || '').toUpperCase()
  const host = row.target ? String(row.target).replace(/^https?:\/\//i, '').split('/')[0].split(':')[0] : ''
  // Sertifika Panosu canlı geçişte `domain` paramını dinler (App onNav → setSearch); `q` yalnız ilk yüklemede okunur (QA ISSUE-001).
  if (type === 'CERT') return { tab: 'dashboard', params: host ? { domain: host } : undefined }
  if (type === 'UPTIME') return { tab: 'uptime', params: host ? { q: host } : undefined }
  const tab = MONITOR_TABS[type]
  if (!tab) return null
  return { tab, params: row.monitor_id != null && type !== 'SCRIPTED' ? { monitor: row.monitor_id } : undefined }
}
