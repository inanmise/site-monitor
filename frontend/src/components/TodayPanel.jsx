import { useCallback, useState } from 'react'
import { CalendarClock, Siren, CalendarDays, ArrowRight, ChevronDown, Sparkles, Activity, Gauge, BellOff, Globe, MailX, ShieldAlert, PauseCircle, CheckCircle2 } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import { navigateTo } from '../utils/navigate.js'
import { alertNavParams } from './admin/alerts/alertHistoryModel.js'
import TeamBadge from './ui/TeamBadge.jsx'
import TodayListModal from './TodayListModal.jsx'
import { MonitorRowBody, NotificationRowBody, HealthRowBody, QuietRowBody, MONITOR_SECTION_TAB, MONITOR_TAB, monitorRowKey, openMonitor, openNotification, openQuiet } from './todayMonitorRows.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card as UiCard } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

// Kart tonu (ok|warn|bad|info) — başlık simgesi ve sayı rozetinin rengi. Kartta SOL RENK ŞERİDİ YOK (kullanıcı kuralı
// 2026-09-26; eski .today-card--* border-left'i). Test kancası: `data-slot="today-card"` + `data-tone`.
const TONE_INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', bad: 'text-destructive', info: 'text-primary' }
const TONE_BADGE = {
  ok: 'bg-success/15 text-success dark:bg-success/20',
  warn: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  bad: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  info: 'bg-primary/10 text-primary dark:bg-primary/20',
}

/**
 * "Sizin için — bugün" (2026-09-12, zenginleştirme #3): dashboard'un üstünde kartlar —
 * 30 gün altı sertifika · açık alarm · bu haftanın raporu · ve dört İZLEME kartı (2026-09-19, zayıf-algoritma
 * istisnası kartının yerine, kullanıcı seçimi): kararsız · yavaşlayan · sessiz/bayat · alan adı kaydı dolan;
 * ikinci tur: teslim edilemeyen bildirim (24 sa) · sertifika sağlık bulguları. Her kart doğru sayfaya süzülmüş bağlantı. Hepsi sıfırsa tek satır yeşil "bugün ilgilenilecek bir şey yok".
 * 2 dk'da bir görünürken tazelenir; VARSAYILAN KAPALI, açık/kapalı tercihi localStorage'da.
 * 2026-09-23: "Susturulmuş ve bakımda" kartı (süren/yaklaşan bakım · duraklatılmış izleme · dolacak istisna) ve
 * "dünden bugüne": kart sayısının yanında dün bu saate göre ▲/▼ fark (sunucu `prev`), başlığın altında son 24 saat şeridi.
 */
export default function TodayPanel({ onOpenDomain }) {
  const t = useT()
  const [data, setData] = useState(null)
  // "Tümünü gör" pop-up'ı (2026-09-18): { section, title, icon, onOpen, onGo }
  const [listModal, setListModal] = useState(null)
  // Varsayılan KAPALI (2026-09-12, kullanıcı: "otomatik kapalı olsun"); açan kullanıcı tercihi saklanır.
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('today-panel-open') === 'true' } catch { return false } })

  const load = useCallback(async () => {
    try { const r = await api.me.today(); if (r?.success && r.data) setData(r.data) } catch { /* panel süs */ }
  }, [])
  useVisibleInterval(load, 120_000, true)

  if (!data) return null
  const certs = data.certs || {}, alerts = data.alerts || {}, weekly = data.weekly || {}
  const flapping = data.flapping || {}, slow = data.slow || {}, stale = data.stale || {}, domains = data.domains || {}
  const notif = data.notifications || {}, health = data.health || {}, quiet = data.quiet || {}, recent = data.recent || null
  const total = (certs.count || 0) + (alerts.count || 0) + (weekly.missing || 0)
    + (flapping.count || 0) + (slow.count || 0) + (stale.count || 0) + (domains.count || 0)
    + (notif.count || 0) + (health.count || 0) + (quiet.count || 0)

  function toggle() {
    setOpen((o) => { try { localStorage.setItem('today-panel-open', String(!o)) } catch { /* yoksay */ } return !o })
  }

  // prev: ~24 saat önceki görünür sayı (sunucu; görüntü yoksa alan hiç gelmez → gösterge yok). Artış kötü (▲ kırmızı),
  // azalış iyi (▼ yeşil) — kartların hepsi "ilgilenilecek şey" sayar. Renk tek sinyal değil: ok + sayı + başlık metni.
  const delta = (count, prev) => {
    const d = typeof prev === 'number' ? count - prev : 0
    if (d === 0) return null
    const trend = t(d > 0 ? 'today.trendUp' : 'today.trendDown', Math.abs(d), prev)
    return <span className={`today-delta ${d > 0 ? 'is-worse' : 'is-better'}`} title={trend} aria-label={trend}>{d > 0 ? '▲' : '▼'}{Math.abs(d)}</span>
  }

  // Sayısı 0 olan kart ÇİZİLMEZ (2026-09-23, kullanıcı: "üst satırda çok fazla kart, yazılar sığmıyor") — adı alttaki
  // tek satırlık "Sorun yok" şeridine gider; üstte yalnız ilgilenilecek kartlar kalır ve geniş yer bulur.
  const Card = ({ icon: Icon, tone, title, count, prev, sub, onGo, section, onOpenItem, children }) => {
    if (!count) return null
    return (
    // shadcn Card — eşit yükseklikli ızgarada "Tümünü gör" kartın dibinde (mt-auto)
    <UiCard data-slot="today-card" data-tone={tone} className="min-w-0 gap-1.5 rounded-[10px] px-3 py-2.5 shadow-none">
      <div className="flex min-w-0 items-center gap-2">
        <Icon size={16} aria-hidden="true" className={cn('shrink-0', TONE_INK[tone])} />
        <span className="min-w-0 flex-1 text-[.88em] font-bold [overflow-wrap:anywhere]">{title}</span>
        {delta(count, prev)}
        <Badge variant="secondary" data-slot="today-count" className={cn('font-extrabold tabular-nums', TONE_BADGE[tone])}>{count}</Badge>
      </div>
      {sub && <div className="text-[.78em] text-muted-foreground">{sub}</div>}
      {children}
      {/* "Tümünü gör" → sayfaya gitmek yerine POP-UP: listenin tamamı sayfalı (2026-09-18); "Sayfaya git" pop-up'ın altında */}
      {onGo && (
        <Button type="button" variant="link" size="sm" className="mt-auto h-auto self-start p-0 text-[.8em]"
          onClick={() => setListModal({ section, title, icon: Icon, onOpen: onOpenItem, onGo })}>{t('today.go')} <ArrowRight size={12} aria-hidden="true" /></Button>
      )}
    </UiCard>
    )
  }

  // "Susturulmuş ve bakımda": alt satır parçaları; "Sayfaya git" en anlamlı sayfaya (bakım → istisna → ilk duraklatılmışın türü).
  const maintCount = (quiet.maint_active || 0) + (quiet.maint_soon || 0)
  const quietSub = [
    quiet.maint_active > 0 && t('today.quietMaintActive', quiet.maint_active),
    quiet.maint_soon > 0 && t('today.quietMaintSoon', quiet.maint_soon),
    quiet.paused > 0 && (quiet.paused_long > 0 ? t('today.quietPausedLong', quiet.paused, quiet.paused_long) : t('today.quietPaused', quiet.paused)),
    quiet.exceptions > 0 && t('today.quietExceptions', quiet.exceptions),
  ].filter(Boolean).join(' · ') || t('today.quietSub')
  const firstPaused = (quiet.items || []).find((x) => x.kind === 'PAUSED')
  const quietGo = !quiet.count ? null
    : maintCount > 0 ? () => navigateTo('maintenance')
    : quiet.exceptions > 0 ? () => navigateTo('weakalgo')
    : () => navigateTo(MONITOR_TAB[firstPaused?.type] || 'http')
  // "Sorun yok" şeridi — kart sırasıyla aynı; dün doluyken bugün 0'a düşen kartın ▼ göstergesi şeritte kalır (iyi haber kaybolmaz).
  const clearCards = [
    ['certs', t('today.certs'), certs], ['alerts', t('today.alerts'), alerts], ['flapping', t('today.flapping'), flapping],
    ['slow', t('today.slow'), slow], ['stale', t('today.stale'), stale], ['domains', t('today.domains'), domains],
    ['notifications', t('today.notif'), notif], ['health', t('today.health'), health], ['quiet', t('today.quiet'), quiet],
  ].filter(([, , b]) => !b.count).map(([key, title, b]) => ({ key, title, prev: b.prev }))
  if (weekly.count > 0 && !weekly.missing) clearCards.push({ key: 'weekly', title: t('today.weekly', weekly.week) })
  // Tekil ayrı anahtar (QA 2026-09-24, ISSUE-003): EN "alerts opened: 3" etiketin kendi iki noktasıyla
  // "Last 24 hours: alerts opened: 3" diye okunuyordu; artık "3 alerts opened" / "1 alert opened".
  const count = (key, n) => (n === 1 ? t(`${key}1`) : t(key, n))
  const recentParts = recent ? [
    recent.opened > 0 && count('today.recentOpened', recent.opened),
    recent.resolved > 0 && count('today.recentResolved', recent.resolved),
    recent.renewed > 0 && count('today.recentRenewed', recent.renewed),
  ].filter(Boolean) : []

  return (
    <section className={`today${open ? ' is-open' : ''}${total === 0 ? ' today--clear' : ''}`} aria-label={t('today.title')}>
      {/* Aç/kapa başlığı — shadcn Button (tam genişlik); telefonda özet alt satıra sarar */}
      <Button type="button" variant="ghost" data-slot="today-head" aria-expanded={open} onClick={toggle}
        className="h-auto w-full flex-wrap justify-start gap-x-2.5 gap-y-1 rounded-[10px] px-3.5 py-2.5 text-left font-semibold whitespace-normal hover:bg-muted/50 has-[>svg]:px-3.5 sm:flex-nowrap">
        <Sparkles size={16} aria-hidden="true" className={total === 0 ? 'text-success' : 'text-primary'} />
        <span className="flex-1 sm:flex-none">{t('today.title')}</span>
        <span className="order-3 w-full min-w-0 text-[.88em] font-medium text-muted-foreground sm:order-none sm:w-auto sm:flex-1">
          {total === 0 ? t('today.clear') : t('today.summary', total)}
        </span>
        <ChevronDown size={16} aria-hidden="true" className={cn('transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
      </Button>
      {/* Son 24 saat şeridi (2026-09-23): panel temizken de görünür — çözülen alarm / yenilenen sertifika iyi haberdir. */}
      {open && recent && (
        <div className="today-recent">
          <span className="today-recent-lbl">{t('today.recent', recent.hours || 24)}:</span>
          <span>{recentParts.length ? recentParts.join(' · ') : t('today.recentNone')}</span>
          {data.trend_at && <span className="today-recent-legend">{t('today.trendLegend')}</span>}
        </div>
      )}
      {open && total > 0 && (
        <div data-slot="today-grid" className="today-grid">
          <Card icon={CalendarClock} tone={certs.expired > 0 ? 'bad' : certs.count > 0 ? 'warn' : 'ok'} title={t('today.certs')} count={certs.count || 0} prev={certs.prev}
            sub={certs.expired > 0 ? t('today.certsExpired', certs.expired) : t('today.certsSub')}
            onGo={certs.count ? () => navigateTo('renewal') : null} section="certs" onOpenItem={(c) => onOpenDomain?.(c.domain)}>
            <ul className="today-list">
              {(certs.items || []).map((c) => (
                <li key={c.domain}>
                  <Button type="button" variant="link" className="h-auto min-w-0 max-w-full justify-start p-0 font-semibold" onClick={() => onOpenDomain?.(c.domain)}><span className="truncate">{c.domain}</span></Button>
                  <span className={`today-days${c.days < 0 ? ' is-bad' : c.days <= 7 ? ' is-warn' : ''}`}>{c.days < 0 ? t('today.daysPast', -c.days) : t('today.daysLeft', c.days)}</span>
                  {c.team_name && <TeamBadge teamId={c.team_id} teamName={c.team_name} />}
                </li>
              ))}
            </ul>
          </Card>

          <Card icon={Siren} tone={alerts.critical > 0 ? 'bad' : alerts.count > 0 ? 'warn' : 'ok'} title={t('today.alerts')} count={alerts.count || 0} prev={alerts.prev}
            sub={alerts.critical > 0 ? t('today.alertsCritical', alerts.critical) : t('today.alertsSub')}
            onGo={alerts.count ? () => navigateTo('warnings') : null} section="alerts" onOpenItem={(a) => navigateTo('alerthistory', alertNavParams({ id: a.id, alert_type: a.type, domain: a.domain }))}>
            <ul className="today-list">
              {(alerts.items || []).map((a) => (
                <li key={a.id}>
                  <span className={`today-level today-level--${(a.level || '').toLowerCase()}`}>{a.level}</span>
                  <Button type="button" variant="link" className="h-auto min-w-0 max-w-full justify-start p-0 font-semibold" onClick={() => navigateTo('alerthistory', alertNavParams({ id: a.id, alert_type: a.type, domain: a.domain }))}><span className="truncate">{a.domain || a.type}</span></Button>
                  <span className="today-muted">{a.type}{a.acknowledged ? ` · ${t('today.acked')}` : ''}</span>
                </li>
              ))}
            </ul>
          </Card>

          {/* İzleme kartları (2026-09-19): satır çizimi todayMonitorRows.jsx'te (pop-up ile ortak). */}
          <Card icon={Activity} tone={flapping.count > 0 ? 'warn' : 'ok'} title={t('today.flapping')} count={flapping.count || 0} prev={flapping.prev}
            sub={t('today.flappingSub')}
            onGo={flapping.count ? () => navigateTo(MONITOR_SECTION_TAB.flapping) : null} section="flapping" onOpenItem={openMonitor}>
            <ul className="today-list">
              {(flapping.items || []).map((x) => <li key={monitorRowKey(x)}><MonitorRowBody section="flapping" item={x} t={t} onOpen={openMonitor} /></li>)}
            </ul>
          </Card>

          <Card icon={Gauge} tone={slow.count > 0 ? 'warn' : 'ok'} title={t('today.slow')} count={slow.count || 0} prev={slow.prev}
            sub={t('today.slowSub')}
            onGo={slow.count ? () => navigateTo(MONITOR_SECTION_TAB.slow) : null} section="slow" onOpenItem={openMonitor}>
            <ul className="today-list">
              {(slow.items || []).map((x) => <li key={monitorRowKey(x)}><MonitorRowBody section="slow" item={x} t={t} onOpen={openMonitor} /></li>)}
            </ul>
          </Card>

          <Card icon={BellOff} tone={stale.count > 0 ? 'warn' : 'ok'} title={t('today.stale')} count={stale.count || 0} prev={stale.prev}
            sub={t('today.staleSub')}
            onGo={stale.count ? () => navigateTo(MONITOR_SECTION_TAB.stale) : null} section="stale" onOpenItem={openMonitor}>
            <ul className="today-list">
              {(stale.items || []).map((x) => <li key={monitorRowKey(x)}><MonitorRowBody section="stale" item={x} t={t} onOpen={openMonitor} /></li>)}
            </ul>
          </Card>

          <Card icon={Globe} tone={domains.expired > 0 ? 'bad' : domains.count > 0 ? 'warn' : 'ok'} title={t('today.domains')} count={domains.count || 0} prev={domains.prev}
            sub={domains.expired > 0 ? t('today.domainsExpired', domains.expired) : t('today.domainsSub')}
            onGo={domains.count ? () => navigateTo(MONITOR_SECTION_TAB.domains) : null} section="domains" onOpenItem={openMonitor}>
            <ul className="today-list">
              {(domains.items || []).map((x) => <li key={monitorRowKey(x)}><MonitorRowBody section="domains" item={x} t={t} onOpen={openMonitor} /></li>)}
            </ul>
          </Card>

          <Card icon={MailX} tone={notif.count > 0 ? 'bad' : 'ok'} title={t('today.notif')} count={notif.count || 0} prev={notif.prev}
            sub={notif.count > 0 ? t('today.notifBreakdown', notif.email || 0, notif.webhook || 0, notif.push || 0) : t('today.notifSub')}
            onGo={notif.count ? () => navigateTo('health', { view: 'smtp', m_status: 'FAILED', m_range: '24h' }) : null} section="notifications" onOpenItem={openNotification}>
            <ul className="today-list">
              {(notif.items || []).map((x) => <li key={monitorRowKey(x)}><NotificationRowBody item={x} t={t} onOpen={openNotification} /></li>)}
            </ul>
          </Card>

          <Card icon={ShieldAlert} tone={health.critical > 0 ? 'bad' : health.count > 0 ? 'warn' : 'ok'} title={t('today.health')} count={health.count || 0} prev={health.prev}
            sub={health.critical > 0 ? t('today.healthCritical', health.critical) : t('today.healthSub')}
            onGo={health.count ? () => navigateTo(MONITOR_SECTION_TAB.health) : null} section="health" onOpenItem={(x) => onOpenDomain?.(x.domain)}>
            <ul className="today-list">
              {(health.items || []).map((x) => <li key={monitorRowKey(x)}><HealthRowBody item={x} t={t} onOpen={(h) => onOpenDomain?.(h.domain)} /></li>)}
            </ul>
          </Card>

          {/* Susturulmuş ve bakımda (2026-09-23): diğer kartlar sorun OLDUĞUNDA uyarır; bu kart sistemin bilerek
              sustuğu yerleri gösterir. 7+ gündür duraklatılmış izleme ya da dolacak istisna varsa uyarı tonu. */}
          <Card icon={PauseCircle} tone={quiet.paused_long > 0 || quiet.exceptions > 0 ? 'warn' : quiet.count > 0 ? 'info' : 'ok'}
            title={t('today.quiet')} count={quiet.count || 0} prev={quiet.prev} sub={quietSub}
            onGo={quietGo} section="quiet" onOpenItem={openQuiet}>
            <ul className="today-list">
              {(quiet.items || []).map((x) => <li key={monitorRowKey(x)}><QuietRowBody item={x} t={t} onOpen={openQuiet} /></li>)}
            </ul>
          </Card>

          {/* Haftalık rapor kartı YALNIZ modülü açık takımlarda (2026-09-16): sunucu kapalı takımı hiç
              saymaz → count 0 gelir; sayfayı görmeyen takıma "raporun eksik" demenin karşılığı yok. */}
          {weekly.count > 0 && (
          <Card icon={CalendarDays} tone={weekly.missing > 0 ? 'warn' : 'ok'} title={t('today.weekly', weekly.week)} count={weekly.missing || 0}
            sub={weekly.count === 0 ? t('today.weeklyNoTeam') : weekly.missing > 0 ? t('today.weeklyMissing') : t('today.weeklyDone')}
            onGo={weekly.count ? () => navigateTo('weeklyreports') : null} section="weekly" onOpenItem={() => navigateTo('weeklyreports')}>
            <ul className="today-list">
              {(weekly.items || []).map((w) => (
                <li key={w.team_id}>
                  {w.team_name ? <TeamBadge teamId={w.team_id} teamName={w.team_name} /> : <span>#{w.team_id}</span>}
                  <span className={`today-status today-status--${(w.status || '').toLowerCase()}`}>{t(`today.wr.${w.status}`)}</span>
                </li>
              ))}
            </ul>
          </Card>
          )}
        </div>
      )}
      {open && total > 0 && clearCards.length > 0 && (
        <div data-slot="today-ok" className="today-ok">
          <CheckCircle2 size={14} aria-hidden="true" />
          <span className="today-ok-lbl">{t('today.okLabel')}</span>
          <ul className="today-ok-list">
            {clearCards.map((c) => <li key={c.key}>{c.title}{delta(0, c.prev)}</li>)}
          </ul>
        </div>
      )}
      {listModal && (
        <TodayListModal section={listModal.section} title={listModal.title} icon={listModal.icon}
          onClose={() => setListModal(null)}
          onOpen={(x) => { setListModal(null); listModal.onOpen?.(x) }}
          onGo={listModal.onGo} />
      )}
    </section>
  )
}
