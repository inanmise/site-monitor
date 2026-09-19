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
export const MONITOR_SECTION_TAB = { flapping: 'incidents', slow: 'activity', stale: 'health', domains: 'domain' }

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

export function monitorRowKey(x) { return `${x.type}:${x.monitor_id}` }
