import { useEffect, useRef, useState } from 'react'
import { AlertTriangle, CalendarClock, DatabaseZap, FileCode2, ListChecks, Target, Unplug } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { RuleResultsList } from './buildingBlocks.jsx'
import { dbTypeLabel, failureKey, istanbulTime, scheduleSummary, untilText } from './dbqueryPageModel.js'

const SECTION = 'flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/20 px-3.5 py-3'
const HEAD = 'flex items-center gap-1.5 text-[11px] font-bold tracking-[.05em] text-muted-foreground uppercase'

/** Bağlantı sağlık durumu → ton. Bilinmeyen / okunamayan asla yeşil değil. */
const HEALTH_VARIANT = { ok: 'secondary', failing: 'destructive', untested: 'outline', unused: 'outline', unknown: 'outline' }
/** Sağlık zamanı — İstanbul saatiyle (ayrıntıdaki tüm saatler aynı dilimde; DÜŞÜK 16). */
const at = (iso) => istanbulTime(iso, { seconds: true }) || '—'

/**
 * Ayrıntı penceresinin "Özet" sekmesi (2026-10-31) — amaç, bağlantı + sağlık özeti, sorgu (satır numaralı okunur kod
 * bloğu), kurallar ve son değerleri, son / sonraki çalışma, zamanlama özeti, son hata + nedenin açıklaması.
 *
 * <p>Bağlantı sağlığı takım belgesidir (api §2): bağlantılar paylaşımla açılmaz, 7/24 operatörü / paylaşımla okuyan
 * okuyamayabilir. Okunamazsa "okunamadı" yazılır (asla "bağlantı yok" değil); okunur ama listede yoksa "bulunamadı".
 * SATIR VERİSİ burada da yoktur — kural sonuçları yalnız ölçülen sayı / beklenen / seviye taşır.
 *
 * <p>Test kancaları: `dbq-detail-purpose`, `dbq-detail-connection` (+ `dbq-health` `data-state`
 * ok|failing|untested|unused|unreadable|missing|loading), `dbq-detail-sql` (satırlar `dbq-sql-line`), `dbq-detail-rules`,
 * `dbq-detail-run`, `dbq-detail-error`.
 */
export default function DbQueryDetailSummary({ monitor: m }) {
  const t = useT()
  const health = useConnectionHealth(m?.team_id, m?.profile_id)
  if (!m) return null
  const failKey = failureKey(m.failure_reason)
  const lines = String(m.sql_text || '').split('\n')
  const until = m.active === false ? null : untilText(m.next_run_at, t)
  return (
    <div data-slot="dbq-detail-summary" className="flex min-w-0 flex-col gap-3">
      {m.status === 'FAIL' && (m.error || failKey) && (
        <div data-slot="dbq-detail-error">
          <AlertBanner tone="danger" role="alert" icon={AlertTriangle} title={failKey ? t(failKey) : t('dbq.status.fail')}>
            <span className="flex flex-col gap-1 [overflow-wrap:anywhere]">
              {m.error && <span>{m.error}</span>}
              {failKey && <span className="text-xs">{t(`${failKey}.fix`)}</span>}
            </span>
          </AlertBanner>
        </div>
      )}

      <section data-slot="dbq-detail-purpose" className={SECTION}>
        <h3 className={HEAD}><Target aria-hidden="true" className="size-3.5" />{t('dbq.detail.purpose')}</h3>
        <p className="m-0 text-sm whitespace-pre-line [overflow-wrap:anywhere]">{m.purpose || '—'}</p>
      </section>

      <section data-slot="dbq-detail-connection" className={SECTION}>
        <h3 className={HEAD}><DatabaseZap aria-hidden="true" className="size-3.5" />{t('dbq.detail.connection')}</h3>
        {m.profile && !m.profile_missing ? (
          <div className="flex min-w-0 flex-col gap-1 text-sm">
            <span className="flex min-w-0 flex-wrap items-center gap-1.5">
              <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10.5px] font-semibold">{dbTypeLabel(m.profile.db_type)}</Badge>
              <strong className="min-w-0 [overflow-wrap:anywhere]">{m.profile.name}</strong>
            </span>
            {(m.profile.host || m.profile.database_name) && (
              <span data-slot="dbq-detail-endpoint" className="font-mono text-xs text-muted-foreground [overflow-wrap:anywhere]">
                {m.profile.host}{m.profile.port ? `:${m.profile.port}` : ''}{m.profile.database_name ? `/${m.profile.database_name}` : ''}
              </span>
            )}
            <HealthLine health={health} />
          </div>
        ) : (
          <p data-slot="dbq-health" data-state="missing" className="m-0 flex items-center gap-1.5 text-sm text-amber-700 dark:text-amber-300">
            <Unplug aria-hidden="true" className="size-3.5 shrink-0" />{t('dbq.detail.noConnection')}
          </p>
        )}
      </section>

      <section data-slot="dbq-detail-sql" className={SECTION}>
        <h3 className={HEAD}><FileCode2 aria-hidden="true" className="size-3.5" />{t('dbq.detail.query')}</h3>
        {/* Okunur kod bloğu (salt okunur): satır numarası + tam metin, YATAY kaydırmalı kap — metin kesilmez. Kap klavyeyle de
            kaydırılır (bölge adı + tabIndex; 10b). */}
        <div data-slot="dbq-detail-sql-scroll" role="region" aria-label={t('dbq.detail.query')} tabIndex={0}
          className="max-h-80 overflow-auto rounded-md border bg-background outline-none focus-visible:ring-2 focus-visible:ring-ring">
          <ol aria-label={t('dbq.detail.query')} className="m-0 list-none p-0 py-1.5 font-mono text-xs leading-5">
            {lines.map((line, i) => (
              <li key={i} data-slot="dbq-sql-line" className="flex min-w-0">
                <span aria-hidden="true" className="w-10 shrink-0 pr-2.5 text-right text-muted-foreground select-none tabular-nums">{i + 1}</span>
                <span className="pr-3 whitespace-pre">{line || ' '}</span>
              </li>
            ))}
          </ol>
        </div>
        <span className="text-xs text-muted-foreground">
          {t('dbq.detail.limits', m.query_timeout_seconds ?? '—', m.max_rows ?? '—')}
        </span>
      </section>

      <section data-slot="dbq-detail-rules" className={SECTION}>
        <h3 className={HEAD}><ListChecks aria-hidden="true" className="size-3.5" />{t('dbq.detail.rules')}</h3>
        <span className="text-xs text-muted-foreground">
          {t(m.rule_logic === 'ALL' ? 'dbq.detail.logicAll' : 'dbq.detail.logicAny')}
          {m.slow_query_ms ? ` · ${t('dbq.detail.slowAt', m.slow_query_ms)}` : ''}
        </span>
        {Array.isArray(m.rule_results) && m.rule_results.length > 0
          ? <RuleResultsList results={m.rule_results} />
          : <p className="m-0 text-sm text-muted-foreground">
              {Array.isArray(m.rules) && m.rules.length ? t('dbq.detail.rulesNotRun', m.rules.length) : t('dbq.detail.noRules')}
            </p>}
      </section>

      <section data-slot="dbq-detail-run" className={SECTION}>
        <h3 className={HEAD}><CalendarClock aria-hidden="true" className="size-3.5" />{t('dbq.detail.schedule')}</h3>
        <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-1.5 text-sm sm:grid-cols-[max-content_minmax(0,1fr)]">
          <dt className="text-muted-foreground">{t('dbq.detail.scheduleLabel')}</dt>
          <dd className="m-0 [overflow-wrap:anywhere]">{scheduleSummary(m, t)}</dd>
          <dt className="text-muted-foreground">{t('dbq.detail.lastRun')}</dt>
          <dd className="m-0 tabular-nums">{m.last_check ? at(m.last_check) : t('dbq.detail.never')}</dd>
          <dt className="text-muted-foreground">{t('dbq.detail.nextRun')}</dt>
          <dd className="m-0 tabular-nums">
            {m.active === false ? t('dbq.card.pausedShort')
              : m.next_run_at ? <>{istanbulTime(m.next_run_at) || '—'}{until ? <span className="text-muted-foreground"> · {until}</span> : null}</>
              : '—'}
          </dd>
        </dl>
        {/* Son ve sonraki çalışma AYNI saat diliminde (tarayıcısı UTC olan VDI kullanıcısı 3 saat fark görmesin) */}
        <span data-slot="dbq-detail-tz" className="text-xs text-muted-foreground">{t('dbq.sched.tzNote')}</span>
      </section>
    </div>
  )
}

/** Bağlantının sağlık satırı — durum rozeti + son deneme / son başarı / son hata. */
function HealthLine({ health }) {
  const t = useT()
  if (health.state === 'loading') {
    return <span data-slot="dbq-health" data-state="loading" className="text-xs text-muted-foreground">{t('dbq.detail.healthLoading')}</span>
  }
  if (health.state === 'unreadable' || health.state === 'notfound') {
    return (
      <span data-slot="dbq-health" data-state={health.state === 'notfound' ? 'missing' : 'unreadable'} className="text-xs text-muted-foreground">
        {t(health.state === 'notfound' ? 'dbq.detail.healthNotFound' : 'dbq.detail.healthUnreadable')}
      </span>
    )
  }
  const h = health.data || {}
  // Tanınmayan / eksik durum "bilinmiyor" — "Denenmedi" yanlış bilgi olurdu (DÜŞÜK 12); asla yeşil değil
  const state = ['ok', 'failing', 'untested', 'unused'].includes(h.state) ? h.state : 'unknown'
  return (
    <span data-slot="dbq-health" data-state={state} className="flex min-w-0 flex-col gap-0.5 text-xs">
      <span className="flex flex-wrap items-center gap-1.5">
        <Badge variant={HEALTH_VARIANT[state]} className={cn('px-1.5 py-0 text-[10.5px] font-semibold', state === 'ok' && 'bg-success/15 text-success dark:bg-success/20')}>
          {t(`dbq.detail.health_${state}`)}
        </Badge>
        {h.last_ok_at && <span className="text-muted-foreground">{t('dbq.detail.lastOk', at(h.last_ok_at))}</span>}
        {h.server_version && <span className="text-muted-foreground [overflow-wrap:anywhere]">{h.server_version}</span>}
      </span>
      {h.last_error && (
        <span className="text-destructive [overflow-wrap:anywhere]">
          {t('dbq.detail.lastProblemAt', h.last_error_at ? at(h.last_error_at) : '—')} {h.last_error}
        </span>
      )}
    </span>
  )
}

/**
 * İzlemenin bağlantısının sağlık alanları — takımın bağlantı listesinden. Sıra korumalı: başka izlemeye geçilince eski
 * yanıt yazılmaz. Okunamayan yanıt (403 / ağ / zarf hatası) `unreadable`; listede yoksa `notfound`.
 */
function useConnectionHealth(teamId, profileId) {
  const [state, setState] = useState({ state: 'loading', data: null })
  const seq = useRef(0)
  useEffect(() => {
    const my = ++seq.current
    if (profileId == null || teamId == null || typeof api.monitoring?.dbConnections !== 'function') {
      setState({ state: profileId == null ? 'notfound' : 'unreadable', data: null })
      return undefined
    }
    setState({ state: 'loading', data: null })
    Promise.resolve(api.monitoring.dbConnections(teamId))
      .then((res) => {
        if (my !== seq.current) return
        if (!res?.success || !Array.isArray(res.data)) { setState({ state: 'unreadable', data: null }); return }
        const p = res.data.find((x) => String(x?.id) === String(profileId))
        setState(p ? { state: 'ok', data: p.health || {} } : { state: 'notfound', data: null })
      })
      .catch(() => { if (my === seq.current) setState({ state: 'unreadable', data: null }) })
    return () => { seq.current++ }
  }, [teamId, profileId])
  return state
}
