import { navigateTo } from '../utils/navigate.js'
import TeamBadge from './ui/TeamBadge.jsx'

/**
 * "Sizin için — bugün" İZLEME kartlarının (2026-09-19: kararsız · yavaşlayan · sessiz/bayat · alan adı kaydı)
 * satır çizimi — kart (ilk 5) ve "Tümünü gör" pop-up'ı (tamamı) AYNI satırı kullanır; tek yerde tanımlı.
 * Satır tıklaması izlemenin sayfasına `?monitor=<id>` derin bağlantısıyla gider (useMonitorDeepLink);
 * Sentetik'te derin bağlantı yok → yalnız sekme.
 */
export const MONITOR_TAB = {
  HTTP: 'http', PORT: 'port', PING: 'ping', DNS: 'dns', KEYWORD: 'keyword',
  PAGE: 'page', PAGESPEED: 'pagespeed', SCRIPTED: 'scripted', DOMAIN: 'domain',
}

export function openMonitor(item) {
  const tab = MONITOR_TAB[item?.type]
  if (!tab) return
  navigateTo(tab, tab === 'scripted' ? undefined : { monitor: item.monitor_id })
}

/** Kart/pop-up "Sayfaya git" hedefi — bölüm başına en yakın sayfa. */
export const MONITOR_SECTION_TAB = { flapping: 'incidents', slow: 'activity', stale: 'health', domains: 'domain', health: 'weakalgo' }   // notifications: SMTP Gönderim Logu (health?view=smtp) — TodayPanel'de

/** Bildirim satırı tıklaması: alarmı varsa Alarm Geçmişi'nde o olayı açar; yoksa günlüğe gider. */
export function openNotification(item) {
  navigateTo('alerthistory', item?.alert_event_id ? { incident: item.alert_event_id } : undefined)
}

/** Yaş metni: kontrol varsa "X dk/sa önce"; yoksa pencerede yaratılmışsa "hiç kontrol edilmedi", daha eskiyse "7+ gündür kontrol yok". */
function ageText(t, x) {
  if (!x.last_check) return x.never ? t('today.staleNever') : t('today.staleOverWindow')
  const min = x.age_min
  return min >= 120 ? t('today.staleHoursAgo', Math.round(min / 60)) : t('today.staleMinutesAgo', min)
}

/** @returns JSX satır içeriği (li sarmalayıcı çağıranda — kartta düz li, pop-up'ta .today-modal-row) */
export function MonitorRowBody({ section, item: x, t, onOpen }) {
  const link = (
    <button type="button" className="today-link" title={x.target || undefined} onClick={() => onOpen?.(x)}>
      {section === 'domains' ? (x.domain || x.name) : x.name}
    </button>
  )
  const type = <span className="today-type">{x.type}</span>
  const team = x.team_name ? <TeamBadge teamId={x.team_id} teamName={x.team_name} /> : null
  if (section === 'flapping') return (<>
    {type}{link}
    <span className={`today-days ${x.last_status === 'DOWN' ? 'is-bad' : 'is-warn'}`}>
      {t('today.flapRow', x.transitions)} · {x.last_status === 'DOWN' ? t('today.nowDown') : t('today.nowUp')}
    </span>
    {team}
  </>)
  if (section === 'slow') return (<>
    {type}{link}
    <span className={`today-days ${x.ratio >= 3 ? 'is-bad' : 'is-warn'}`}>{t('today.slowRow', x.today_ms, x.baseline_ms)} · ×{x.ratio}</span>
    {team}
  </>)
  if (section === 'stale') return (<>
    {type}{link}
    <span className={`today-days ${x.last_check ? 'is-warn' : 'is-bad'}`}>
      {ageText(t, x)}{x.last_check ? ` · ${t('today.staleExpected', x.expected_min)}` : ''}
    </span>
    {team}
  </>)
  // domains
  return (<>
    {link}
    <span className={`today-days${x.days < 0 ? ' is-bad' : x.days <= 7 ? ' is-warn' : ''}`}>{x.days < 0 ? t('today.daysPast', -x.days) : t('today.daysLeft', x.days)}</span>
    {x.registrar && <span className="today-muted">{x.registrar}</span>}
    {team}
  </>)
}

/** Bildirim: "kanal:olay:hedef:zaman" — aynı olay için e-posta ve webhook ayrı satır. Sağlık: alan adı. */
export function monitorRowKey(x) {
  if (x.channel) return `${x.channel}:${x.alert_event_id ?? ''}:${x.target ?? ''}:${x.at ?? ''}`
  if (x.findings) return `h:${x.domain}`
  return `${x.type}:${x.monitor_id}`
}

/** Teslim edilemeyen bildirim satırı: [KANAL] hedef · hata · saat (alan/izleme adı başlıkta). */
export function NotificationRowBody({ item: x, t, onOpen }) {
  const time = x.at ? String(x.at).replace('T', ' ').slice(5, 16) : ''
  return (<>
    <span className={`today-type today-type--${(x.channel || '').toLowerCase()}`}>{t(`today.ch.${x.channel}`)}</span>
    <button type="button" className="today-link" title={x.subject || x.monitor_name || undefined} onClick={() => onOpen?.(x)}>
      {x.domain || x.monitor_name || x.target}
    </button>
    {(x.domain || x.monitor_name) && x.target && <span className="today-muted">{x.target}</span>}
    <span className="today-days is-bad" title={x.error}>{x.error}</span>
    {time && <span className="today-muted">{time}</span>}
    {x.team_name ? <TeamBadge teamId={x.team_id} teamName={x.team_name} /> : null}
  </>)
}

/** Sağlık bulgusu satırı: alan · bulgu rozetleri (hlth.val.* değer metniyle) · takım. */
export function HealthRowBody({ item: x, t, onOpen }) {
  return (<>
    <button type="button" className="today-link" onClick={() => onOpen?.(x)}>{x.domain}</button>
    {(x.findings || []).map((f) => (
      <span key={f.key} className={`today-finding${x.critical && HEALTH_CRITICAL.has(f.key) ? ' is-bad' : ''}`}
        title={t(`hlth.val.${f.value_key}`, ...(f.value_args || []))}>
        {t(`today.hf.${f.key}`)}
      </span>
    ))}
    {x.silenced && <span className="today-muted" title={t('today.healthSilencedHint')}>{t('today.healthSilenced')}</span>}
    {x.team_name ? <TeamBadge teamId={x.team_id} teamName={x.team_name} /> : null}
  </>)
}
const HEALTH_CRITICAL = new Set(['revocation', 'trust', 'sanMatch', 'chain'])
