import { useState } from 'react'
import { BrushCleaning, Globe, KeyRound, Mail, Network, Send, Webhook } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { dateLocale, formatPercent } from '../../../i18n/dateLocale.js'
import { Button } from '@/components/shadcn/button'
import AlertBanner from '../../ui/AlertBanner.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { integrationList, formatDurationShort, cfgDetailText } from './healthModel.js'
import { CARD_GRID, ERR_T, LEVEL_T, OK_T, WARN_T, CardCta, KvList, LevelBadge, SysCard } from './HealthParts.jsx'

/** ISO yıl+hafta → "31 August – 6 September 2026" (arayüz yerelinde; sunucu etiketi yalnız Türkçe). */
export function isoWeekRange(year, weekNo) {
  if (!year || !weekNo) return null
  const jan4 = new Date(Date.UTC(year, 0, 4))
  const mon = new Date(jan4); mon.setUTCDate(jan4.getUTCDate() - ((jan4.getUTCDay() + 6) % 7) + (weekNo - 1) * 7)
  const sun = new Date(mon); sun.setUTCDate(mon.getUTCDate() + 6)
  const loc = dateLocale(), tz = 'UTC'
  const dm = (d) => d.toLocaleDateString(loc, { day: 'numeric', month: 'long', timeZone: tz })
  const dmy = (d) => d.toLocaleDateString(loc, { day: 'numeric', month: 'long', year: 'numeric', timeZone: tz })
  return `${mon.getUTCFullYear() === sun.getUTCFullYear() ? dm(mon) : dmy(mon)} – ${dmy(sun)}`
}

const ICON = { smtp: Mail, push: Webhook, weekly: Send, domain: Globe, network: Network, ldap: KeyRound, cleanup: BrushCleaning }
const TITLE_KEY = { smtp: 'health.smtpTitle', push: 'pl.cardTitle', weekly: 'waSched.title', domain: 'health.domTitle', network: 'health.intNetwork', ldap: 'cfg.check.ldap', cleanup: 'health.intCleanup' }

/** Kart içi periyot seçici — kartın açma tıklamasına sızmaz. */
function PeriodPicker({ t, value, onChange, keys, labelOf }) {
  return (
    <div onClick={(e) => e.stopPropagation()}>
      <SegmentedControl value={value} onChange={onChange} ariaLabel={t('sml.rangeLabel')}
        options={keys.map((p) => ({ value: p, label: labelOf(p) }))} className="flex w-full [&>button]:flex-1" />
    </div>
  )
}

/**
 * Entegrasyonlar: SMTP, webhook push, haftalık erişilebilirlik e-postası, alan adı süre kaynağı (RDAP), ağ erişimi,
 * LDAP (yalnız yapılandırma sağlığı ucu geldiyse — global yönetici) ve gece temizliği. Her kart: seviye rozeti, son
 * başarı/başarısızlık, eylem (günlük görünümü, "Şimdi test et"). Test kancaları: `data-integration` + `data-level`.
 */
export default function IntegrationsSection({
  t, health, pushKpi, configChecks, globalAdmin,
  smtpPeriod, onSmtpPeriod, pushPeriod, onPushPeriod, onOpenSmtp, onOpenPush, onOpenWeeklyLogs,
}) {
  const toast = useToast()
  const [testing, setTesting] = useState(null)   // 'smtp' | 'ldap'
  const list = integrationList({ health, pushKpi, smtpPeriod, configChecks })
  const rateTone = { ok: OK_T, warn: WARN_T, down: ERR_T }

  async function runTest(kind) {
    setTesting(kind)
    try {
      const res = kind === 'smtp' ? await api.admin.testSmtp() : await api.admin.testLdap()
      if (res?.success) toast.success(res.message || t(kind === 'smtp' ? 'smtp.testOk' : 'ldap.testOk'))
      else toast.error(res?.error || t(kind === 'smtp' ? 'smtp.testFail' : 'ldap.testFail'))
    } catch (e) {
      toast.error(e?.message || t(kind === 'smtp' ? 'smtp.testFail' : 'ldap.testFail'))
    } finally {
      setTesting(null)
    }
  }
  const testBtn = (kind) => globalAdmin && (
    <Button type="button" variant="secondary" size="sm" className="mt-1 h-10 sm:h-9" disabled={testing === kind} aria-busy={testing === kind || undefined}
      onClick={(e) => { e.stopPropagation(); runTest(kind) }}>
      {testing === kind ? t('health.testing') : t('health.testNow')}
    </Button>
  )

  function body(it) {
    const d = it.data
    switch (it.key) {
      case 'smtp': return (
        <>
          {it.level !== 'ok' && <AlertBanner tone={it.level === 'down' ? 'danger' : 'warning'} className="mb-0">{t('health.smtpAlarmFor', t(`health.smtpPeriod${smtpPeriod}`))}</AlertBanner>}
          <PeriodPicker t={t} value={smtpPeriod} onChange={onSmtpPeriod} keys={['1d', '7d', '15d', '30d']} labelOf={(p) => t(`health.smtpPeriod${p}`)} />
          <KvList>
            <dt>{t('health.smtpSent')}</dt><dd>{d.sent} / {d.attempted}</dd>
            <dt>{t('health.smtpRate')}</dt><dd className={rateTone[d.rateTone]}>{formatPercent(d.rate)}</dd>
          </KvList>
          <p className="text-xs text-muted-foreground">{t('health.smtpPeriodActive', t(`health.smtpPeriod${smtpPeriod}`))}</p>
          <CardCta icon={Mail} label={t('health.smtpClickHint')} badge={t(`health.smtpPeriod${smtpPeriod}`)} onClick={onOpenSmtp} />
          {testBtn('smtp')}
        </>
      )
      case 'push': return (
        <>
          <PeriodPicker t={t} value={pushPeriod} onChange={onPushPeriod} keys={['24h', '7d', '30d']} labelOf={(p) => t(`sml.range.${p}`)} />
          <KvList>
            <dt>{t('health.smtpSent')}</dt><dd>{d.sent} / {d.attempted}</dd>
            <dt>{t('health.statusFailed')}</dt><dd className={d.failed > 0 ? ERR_T : undefined}>{d.failed}</dd>
            <dt>{t('pl.cardQueued')}</dt><dd className={d.queued > 0 ? WARN_T : undefined}>{d.queued}</dd>
            <dt>{t('health.smtpRate')}</dt><dd className={LEVEL_T[it.level]}>{d.rate == null ? '—' : formatPercent(d.rate)}</dd>
          </KvList>
          <CardCta icon={Webhook} label={t('pl.cardHint')} badge={t(`sml.range.${pushPeriod}`)} onClick={onOpenPush} />
        </>
      )
      case 'weekly': return (
        <>
          <KvList>
            <dt>{t('waSched.schedule')}</dt><dd>{t('waSched.scheduleVal')}</dd>
            <dt>{t('sys.nextRun')}</dt><dd>{d.enabled ? (d.next_run ? formatDate(d.next_run) : '—') : t('waSched.pausedShort')}</dd>
            <dt>{t('sys.lastRun')}</dt><dd>{d.last_run_at ? formatDate(d.last_run_at) : t('sys.never')}</dd>
            {d.last_run_at && (
              <>
                <dt>{t('waSched.lastResult')}</dt>
                <dd>
                  <span className={OK_T}>{d.last_run_sent ?? 0}/{d.last_run_teams ?? 0} {t('waSched.sent')}</span>
                  {(d.last_run_failed ?? 0) > 0 && <span className={ERR_T}> · {d.last_run_failed} {t('waSched.failed')}</span>}
                  {(d.last_run_no_recipient ?? 0) > 0 && <span className={WARN_T}> · {d.last_run_no_recipient} {t('waSched.noRecipient')}</span>}
                </dd>
                <dt>{t('waSched.reportedWeek')}</dt>
                <dd>{isoWeekRange(d.last_run_year, d.last_run_week_no) || d.last_run_week || '—'}</dd>
              </>
            )}
          </KvList>
          <CardCta icon={Mail} label={t('waLogs.clickHint')} onClick={onOpenWeeklyLogs} />
        </>
      )
      case 'domain': {
        const src = d.source || 'IDLE'
        const label = src === 'RDAP' ? t('health.domSrcRdap') : src === 'FALLBACK' ? t('health.domSrcFallback') : src === 'NONE' ? t('health.domSrcNone') : t('health.domSrcIdle')
        return (
          <>
            {d.alarm && <AlertBanner tone="danger" className="mb-0">{t('health.domAlarm')}</AlertBanner>}
            <KvList>
              <dt>{t('health.domSource')}</dt><dd className={LEVEL_T[it.level]}>{label}</dd>
              <dt>{t('health.domLastOk')}</dt><dd>{d.last_success ? formatDate(d.last_success) : t('sys.never')}</dd>
              {d.reason && (<><dt>{t('health.domReason')}</dt><dd className={ERR_T}>{d.reason}</dd></>)}
            </KvList>
          </>
        )
      }
      case 'network': return (
        <>
          {d.alarm && <AlertBanner tone="danger" className="mb-0">{t('health.networkAlarm')}</AlertBanner>}
          <KvList>
            <dt>{t('health.netErrors')}</dt><dd>{d.last_network_errors ?? 0} / {d.last_total ?? 0}</dd>
            <dt>{t('health.netLastRate')}</dt><dd>{d.last_error_rate != null ? formatPercent(Math.round(d.last_error_rate * 1000) / 10) : '—'}</dd>
            <dt>{t('health.netThreshold')}</dt><dd>{d.min_errors ?? '—'} · {d.threshold != null ? formatPercent(Math.round(d.threshold * 100)) : '—'}</dd>
            {d.detected_at && (<><dt>{t('health.netDetected')}</dt><dd className={ERR_T}>{formatDate(d.detected_at)}</dd></>)}
            {d.resolved_at && (<><dt>{t('health.netResolved')}</dt><dd>{formatDate(d.resolved_at)}</dd></>)}
          </KvList>
        </>
      )
      case 'ldap': return (
        <>
          <KvList>
            <dt>{t('health.hbDetailStatus')}</dt><dd className={LEVEL_T[it.level]}>{cfgDetailText(d.detail, t)}</dd>
          </KvList>
          {testBtn('ldap')}
        </>
      )
      case 'cleanup': return (
        <>
          {it.level === 'warn' && !d.never_run && <AlertBanner tone="warning" className="mb-0">{t('health.reasonCleanup')}</AlertBanner>}
          <KvList>
            <dt>{t('sys.lastRun')}</dt>
            <dd>{d.never_run || !d.last_run ? t('sys.never') : <>{formatDate(d.last_run)}{d.hours_since >= 0 && <span className="text-muted-foreground"> · {t('health.ago', formatDurationShort(d.hours_since * 3600, t))}</span>}</>}</dd>
            {d.total_deleted != null && (<><dt>{t('health.cleanupDeleted')}</dt><dd className="tabular-nums">{Number(d.total_deleted).toLocaleString()}</dd></>)}
            {d.failed_count != null && (<><dt>{t('health.statusFailed')}</dt><dd className={d.failed_count > 0 ? ERR_T : undefined}>{d.failed_count}</dd></>)}
            {d.duration_ms != null && (<><dt>{t('health.scanDuration')}</dt><dd>{formatDurationShort(d.duration_ms / 1000, t)}</dd></>)}
            {d.hold_active && (<><dt>{t('health.hbDetailStatus')}</dt><dd className={WARN_T}>{t('health.cleanupHold')}</dd></>)}
            {d.error && (<><dt>{t('health.statusFailed')}</dt><dd className={`${ERR_T} break-words`}>{d.error}</dd></>)}
          </KvList>
        </>
      )
      default: return null
    }
  }

  return (
    <div className={CARD_GRID} data-slot="integrations-grid">
      {list.map((it) => (
        <SysCard key={it.key} icon={ICON[it.key]} iconClass={LEVEL_T[it.level]} title={t(TITLE_KEY[it.key])} cardKey={it.key}
          alarm={it.level === 'down'} testId={it.key === 'push' ? 'push-card' : undefined}
          onOpen={it.key === 'smtp' ? onOpenSmtp : it.key === 'push' ? onOpenPush : it.key === 'weekly' ? onOpenWeeklyLogs : undefined}
          right={<span data-integration={it.key} data-level={it.level} className="inline-flex"><LevelBadge level={it.level} t={t} /></span>}>
          {body(it)}
        </SysCard>
      ))}
    </div>
  )
}
