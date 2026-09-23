import { navigateTo } from '../utils/navigate.js'
import { toUtc } from '../utils/localDay.js'
import { dateLocale } from '../i18n/dateLocale.js'
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
  if (x.window_id != null) return `mw:${x.window_id}`
  if (x.kind === 'EXCEPTION') return `ex:${x.domain}`
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

// ── Susturulmuş ve bakımda (2026-09-23) ────────────────────────────────────────────────────

/** Satır tıklaması: bakım → Bakım sayfası; istisna → Zayıf Algoritma; duraklatılmış → izlemenin kendisi. */
export function openQuiet(item) {
  if (item?.window_id != null) return navigateTo('maintenance')
  if (item?.kind === 'EXCEPTION') return navigateTo('weakalgo')
  return openMonitor(item)
}

/** UTC ISO → yerel "HH:mm" (bugünse) ya da "gg.aa HH:mm". */
export function whenText(iso) {
  if (!iso) return ''
  const d = new Date(toUtc(iso))
  if (Number.isNaN(d.getTime())) return String(iso)
  const loc = dateLocale()
  const time = d.toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit' })
  return d.toDateString() === new Date().toDateString()
    ? time
    : `${d.toLocaleDateString(loc, { day: '2-digit', month: '2-digit' })} ${time}`
}

function pausedText(t, x) {
  if (x.paused_days == null) return t('today.pausedUnknown')
  if (x.paused_days === 0) return t('today.pausedToday')
  if (x.paused_days === 1) return t('today.pausedOneDay')
  return t('today.pausedDays', x.paused_days)
}

/** Kart ve pop-up ortak satırı: [TÜR] ad · ne zaman/ne kadar · takım. */
export function QuietRowBody({ item: x, t, onOpen }) {
  const team = x.team_name ? <TeamBadge teamId={x.team_id} teamName={x.team_name} /> : null
  if (x.window_id != null) {
    const active = x.kind === 'MAINT_ACTIVE'
    return (<>
      <span className={`today-type today-type--${active ? 'maint' : 'soon'}`}>{t(`today.qk.${x.kind}`)}</span>
      <button type="button" className="today-link" onClick={() => onOpen?.(x)}>{x.name}</button>
      <span className="today-days">{active ? t('today.maintUntil', whenText(x.until)) : t('today.maintStarts', whenText(x.next_start))}</span>
      <span className="today-muted">{x.target_count < 0 ? t('today.maintAll') : t('today.maintTargets', x.target_count)}</span>
      {team}
    </>)
  }
  if (x.kind === 'EXCEPTION') return (<>
    <span className="today-type">{t('today.qk.EXCEPTION')}</span>
    <button type="button" className="today-link" title={x.reason || undefined} onClick={() => onOpen?.(x)}>{x.domain}</button>
    <span className={`today-days ${x.days_left <= 1 ? 'is-bad' : 'is-warn'}`}>
      {x.days_left === 0 ? t('today.exToday') : x.days_left === 1 ? t('today.exTomorrow') : t('today.exDays', x.days_left)}
    </span>
    {team}
  </>)
  // PAUSED — 7+ gündür duraklatılmış satır "unutulmuş olabilir" (uyarı rengi); yaklaşık tarih ~ ile işaretli
  const long = x.paused_days != null && x.paused_days >= 7
  return (<>
    <span className="today-type">{x.type}</span>
    <button type="button" className="today-link" title={x.target || undefined} onClick={() => onOpen?.(x)}>{x.name}</button>
    <span className={`today-days${long ? ' is-warn' : ''}`} title={x.paused_since_exact === false && x.paused_days != null ? t('today.pausedApprox') : undefined}>
      {x.paused_since_exact === false && x.paused_days != null ? '~' : ''}{pausedText(t, x)}
    </span>
    {team}
  </>)
}
