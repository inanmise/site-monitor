import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Activity, BarChart3, Cable, Clock, Database, HardDrive, HeartPulse, Layers, Lock, LockOpen, RefreshCw, Server, ShieldCheck,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { formatDurationShort, relTime } from './health/healthModel.js'
import { SETTINGS_STACK, SettingsHeader } from './SettingsControls.jsx'
import ToneBadge from './ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { DbInfoSkeleton, InfoCard, InfoList, InfoRow, KpiTile, PoolBar, StatusBadge } from './dbinfo/DbInfoParts.jsx'
import {
  CHECK_LABEL, clockText, driverText, fmtNum, hasMaskedSecret, healthModel, hostPort, isBlank, isNum, maskJdbcUrl,
  parseIntervalSeconds, poolModel, publicHealthUrl, PUBLIC_HEALTH_PATH, STATUS_KEY, usage,
} from './dbinfo/dbInfoModel.js'

/** Başlık / hata düğmeleri: telefonda ve dokunmatikte 40 px dokunma hedefi (RESPONSIVE.md §4). */
const TOUCH = 'max-md:h-10 pointer-coarse:h-10'

/** Süre (ms) → "30 sn" / "10 dk"; 0 = Hikari'de "sınırsız" (boşta bağlantı kapatılmaz / ömür sınırı yok). */
function msText(ms, t) {
  if (!isNum(ms)) return null
  return Number(ms) === 0 ? t('dbinfo.unlimited') : formatDurationShort(Number(ms) / 1000, t)
}

/**
 * Settings → Veritabanı Bilgileri (yalnız GLOBAL yönetici — kapsamlı müdür AdminSettings'te "yalnız global yönetici"
 * notunu görür, bu bileşen hiç bağlanmaz; sunucu `requireNotScopedAdmin`).
 *
 * <p>2026-10-09 yeniden tasarım (kullanıcı isteği: "shadcn ile, telefon/tablet uyumlu, profesyonel; mevcut özellikleri
 * koruyarak zenginleştir"). Tek istek (`GET /api/admin/database/info`) — sağlık özeti aynı yanıtta (`health`, dış
 * izleme ucunun gövdesiyle aynı). Yerleşim kabın KENDİ genişliğine göre (`@container/dbinfo`):
 *   • Özet kutucukları: durum · boyut · sunucu bağlantıları · uygulama havuzu (telefonda 1–2, genişte 4 sütun).
 *   • Kartlar: Bağlantı · Sağlık denetimleri · Sunucu · Bağlantı Havuzu · JDBC / Sürücü (tam genişlik — uzun URL).
 * Eski ekrandaki HER alan korunur (db adı, kullanıcı, host/port, sürüm, boyut, çalışma süresi, başlangıç, kodlama,
 * azami/aktif bağlantı, havuz adı/aktif/boşta/toplam/bekleyen/azami/asgari, JDBC URL, sürücü) + oturum kullanıcısı,
 * tam sürüm metni, collation, sunucu saati, saat dilimi, tablo sayısı, SSL, havuz süreleri, şema yamaları.
 * Hata: ilk yüklemede tam bölüm hata bloğu + Tekrar dene; yenileme hatasında son değerler kalır + uyarı şeridi.
 */
export default function DatabaseInfo() {
  const t = useT()
  const toast = useToast()
  const [info, setInfo] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [updatedAt, setUpdatedAt] = useState(null)
  // Sıra koruması: üst üste Yenile'de yalnız SON yanıt yazılır; ayrılan bileşene geç yanıt yazılmaz.
  const seq = useRef(0)
  const hasData = useRef(false)

  const load = useCallback(async () => {
    const my = ++seq.current
    setLoading(true)
    let failure = null
    try {
      const res = await api.admin.getDatabaseInfo()
      if (my !== seq.current) return
      if (res?.success) {
        setInfo(res.data && typeof res.data === 'object' && !Array.isArray(res.data) ? res.data : {})
        hasData.current = true
        setError(null)
        setUpdatedAt(new Date())
      } else {
        failure = res?.error || t('db.loadError')
      }
    } catch (e) {
      if (my !== seq.current) return
      failure = e?.message || t('db.loadError')
    } finally {
      if (my === seq.current) setLoading(false)
    }
    if (failure) {
      setError(failure)
      // Yenilemede son değerler ekranda kalır; kullanıcı aşağı kaydırmış olabilir → ayrıca bildirim.
      if (hasData.current) toast.error(failure)
    }
  }, [t, toast])

  useEffect(() => {
    load()
    return () => { seq.current += 1 }
  }, [])   // eslint-disable-line react-hooks/exhaustive-deps

  const d = info || {}
  const pool = poolModel(d.pool)
  const health = info ? healthModel(d.health, d.schema_patches) : null
  const conn = usage(d.active_connections, d.max_connections)
  const url = maskJdbcUrl(d.jdbc_url)
  const uptimeSec = parseIntervalSeconds(d.uptime)
  const unknown = t('dba.unknown')
  const stamp = clockText(updatedAt)
  const showSession = !isBlank(d.session_user) && d.session_user !== d.user
  const healthUrl = publicHealthUrl(typeof window !== 'undefined' ? window.location.origin : '')

  const refreshBtn = (
    <Button type="button" variant="outline" onClick={load} aria-busy={loading || undefined} className={TOUCH}
      data-slot="dbinfo-refresh">
      <RefreshCw aria-hidden="true" className={cn(loading && 'animate-spin motion-reduce:animate-none')} />
      {loading ? t('db.loading') : t('db.refresh')}
    </Button>
  )

  return (
    <div className={cn(SETTINGS_STACK, '@container/dbinfo')} data-testid="database-info" aria-busy={loading || undefined}>
      <SettingsHeader icon={Database} title={t('db.title')} description={t('db.desc')}
        // Başlık + eylemler kabın genişliğine göre yan yana (tablette kenar çubuğu açıkken içerik ~400 px)
        rowClassName="sm:flex-col @xl/dbinfo:flex-row"
        meta={info ? (
          <>
            <StatusBadge t={t} status={health?.status} data-slot="dbinfo-status" />
            {!isBlank(d.version) && <Badge variant="outline" className="font-normal text-muted-foreground">{d.version}</Badge>}
            {stamp && (
              <Badge variant="outline" data-slot="dbinfo-updated" className="gap-1.5 font-normal text-muted-foreground tabular-nums">
                <Clock aria-hidden="true" />{t('db.updatedAt', stamp)}
              </Badge>
            )}
          </>
        ) : null}
        actions={(
          <>
            <Button type="button" variant="ghost" className={TOUCH} onClick={() => navigateTo('health', { sec: 'db' })}
              data-slot="dbinfo-analytics">
              <BarChart3 aria-hidden="true" />{t('dbinfo.openAnalytics')}
            </Button>
            {refreshBtn}
          </>
        )} />

      {/* İlk yükleme */}
      {!info && loading && <DbInfoSkeleton label={t('db.loading')} />}

      {/* İlk yükleme başarısız: tam bölüm hata bloğu + sonraki adım */}
      {!info && !loading && error && (
        <StatusBlock tone="danger" icon={Database} role="alert" title={t('dbinfo.loadErrorTitle')} description={error}
          actions={(
            <Button type="button" onClick={load} className={TOUCH} data-slot="dbinfo-retry">
              <RefreshCw aria-hidden="true" />{t('db.retry')}
            </Button>
          )} />
      )}

      {info && (
        <>
          {/* Yenileme başarısız: son değerler kalır */}
          {error && (
            <AlertBanner tone="danger" title={t('dbinfo.staleTitle')} className="mb-0"
              actions={(
                <Button type="button" variant="outline" size="sm" onClick={load} className={TOUCH} data-slot="dbinfo-retry">
                  <RefreshCw aria-hidden="true" />{t('db.retry')}
                </Button>
              )}>
              {error}
            </AlertBanner>
          )}

          {/* Özet kutucukları */}
          <section aria-label={t('dbinfo.kpiAria')} data-slot="dbinfo-kpis"
            className="grid grid-cols-1 gap-3 @xs/dbinfo:grid-cols-2 @3xl/dbinfo:grid-cols-4">
            <KpiTile id="status" icon={HeartPulse} label={t('dbinfo.kpiStatus')} tone={health?.tone || 'muted'} unknownLabel={unknown}
              value={health?.status ? t(STATUS_KEY[health.status]) : null}
              sub={health?.queryMs != null ? t('dbinfo.kpiStatusSub', fmtNum(health.queryMs)) : null} />
            <KpiTile id="size" icon={HardDrive} label={t('db.size')} value={d.size} unknownLabel={unknown}
              sub={isNum(d.table_count) ? t('db.kpiTables', fmtNum(d.table_count)) : null} />
            <KpiTile id="connections" icon={Cable} label={t('dbinfo.kpiServerConn')} unknownLabel={unknown}
              tone={conn?.tone || 'muted'}
              value={isNum(d.active_connections) ? `${fmtNum(d.active_connections)} / ${fmtNum(d.max_connections)}` : null}
              bar={conn ? { value: Number(d.active_connections), max: Number(d.max_connections), label: t('db.connUsage') } : null}
              sub={conn ? t('db.kpiUsage', conn.pct) : null} />
            <KpiTile id="pool" icon={Layers} label={t('dbinfo.kpiPool')} unknownLabel={unknown} tone={pool?.tone || 'muted'}
              value={pool && pool.active != null ? `${fmtNum(pool.active)} / ${fmtNum(pool.max)}` : null}
              bar={pool?.max > 0 ? { value: pool.active ?? 0, max: pool.max, label: t('dbinfo.poolUsage') } : null}
              sub={pool && pool.active != null ? t('dbinfo.kpiPoolSub', fmtNum(pool.idle), fmtNum(pool.waiting)) : null} />
          </section>

          <div className="grid min-w-0 grid-cols-1 gap-4 @3xl/dbinfo:grid-cols-2">
            {/* Bağlantı */}
            <InfoCard id="connection" icon={Database} title={t('db.secConnection')} description={t('dbinfo.connDesc')}>
              <InfoList>
                <InfoRow t={t} k="database" label={t('db.database')} value={d.database} mono copy />
                <InfoRow t={t} k="user" label={t('db.user')} value={d.user} mono copy
                  secondary={showSession ? t('dbinfo.sessionUser', d.session_user) : null} />
                <InfoRow t={t} k="host" label={t('db.host')} value={hostPort(d)} mono wrap copy help="dbinfo.help.host" />
                <InfoRow t={t} k="version" label={t('db.version')} value={d.version} copy copyValue={d.version_full || d.version}
                  secondary={!isBlank(d.version_full) && d.version_full !== d.version ? d.version_full : null} />
                <InfoRow t={t} k="ssl" label={t('dbinfo.ssl')} help="dbinfo.help.ssl">
                  {d.ssl === true && (
                    <ToneBadge tone="success" className="gap-1 font-semibold">
                      <Lock aria-hidden="true" />{isBlank(d.ssl_version) ? t('dbinfo.sslOn') : t('dbinfo.sslOnVer', d.ssl_version)}
                    </ToneBadge>
                  )}
                  {d.ssl === false && (
                    <ToneBadge tone="warning" className="gap-1 font-semibold"><LockOpen aria-hidden="true" />{t('dbinfo.sslOff')}</ToneBadge>
                  )}
                  {d.ssl !== true && d.ssl !== false && <span className="text-sm text-muted-foreground">—</span>}
                </InfoRow>
              </InfoList>
            </InfoCard>

            {/* Sağlık denetimleri */}
            <InfoCard id="health" icon={HeartPulse} title={t('dbinfo.secHealth')} description={t('dbinfo.healthDesc')}
              badge={<StatusBadge t={t} status={health?.status} />}>
              {health ? (
                <InfoList>
                  {health.checks.map((c) => (
                    <InfoRow key={c.key} t={t} k={`check-${c.key}`} label={t(CHECK_LABEL[c.key])}>
                      <span data-slot="dbinfo-check" data-check={c.key} data-status={c.status || 'UNKNOWN'}
                        className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                        <StatusBadge t={t} status={c.status} />
                        {c.detail && <span className="min-w-0 text-sm break-words">{t(c.detail.key, ...c.detail.args)}</span>}
                      </span>
                    </InfoRow>
                  ))}
                  <InfoRow t={t} k="checked" label={t('dbinfo.checkedAt')}
                    value={relTime(health.checkedAt, t)}
                    secondary={[
                      health.durationMs != null ? t('dbinfo.checkDuration', fmtNum(health.durationMs)) : null,
                      health.cached ? t('dbinfo.checkCached') : null,
                    ].filter(Boolean).join(' · ') || null} />
                  <InfoRow t={t} k="endpoint" label={t('dbinfo.endpoint')} value={PUBLIC_HEALTH_PATH} mono wrap
                    copy copyValue={healthUrl} help="dbinfo.help.endpoint" secondary={t('dbinfo.endpointHint')} />
                </InfoList>
              ) : (
                <p data-slot="dbinfo-health-none" className="m-0 py-3 text-sm text-muted-foreground">{t('dbinfo.healthNone')}</p>
              )}
            </InfoCard>

            {/* Sunucu */}
            <InfoCard id="server" icon={Server} title={t('db.secServer')} description={t('dbinfo.serverDesc')}>
              <InfoList cols={2}>
                <InfoRow t={t} k="size" label={t('db.size')} value={d.size}
                  secondary={isNum(d.table_count) ? t('db.kpiTables', fmtNum(d.table_count)) : null} />
                <InfoRow t={t} k="uptime" label={t('db.uptime')}
                  value={uptimeSec != null ? formatDurationShort(uptimeSec, t) : d.uptime}
                  secondary={uptimeSec != null && !isBlank(d.uptime) ? d.uptime : null} />
                <InfoRow t={t} k="start_time" label={t('db.startTime')} value={d.start_time} mono />
                <InfoRow t={t} k="server_time" label={t('dbinfo.serverTime')} value={d.server_time} mono />
                <InfoRow t={t} k="timezone" label={t('dbinfo.timezone')} value={d.timezone} help="dbinfo.help.timezone" />
                <InfoRow t={t} k="encoding" label={t('db.encoding')} value={d.encoding} />
                <InfoRow t={t} k="collation" label={t('dbinfo.collation')} value={d.collation} mono />
                <InfoRow t={t} k="max_connections" label={t('db.maxConnections')} value={isNum(d.max_connections) ? fmtNum(d.max_connections) : d.max_connections} />
                <InfoRow t={t} k="active_connections" label={t('db.activeConnections')} help="dbinfo.help.activeConnections"
                  value={isNum(d.active_connections) ? fmtNum(d.active_connections) : null}
                  badge={conn ? <ToneBadge tone={conn.tone} className="font-semibold">{t('db.kpiUsage', conn.pct)}</ToneBadge> : null} />
              </InfoList>
            </InfoCard>

            {/* Bağlantı havuzu */}
            <InfoCard id="pool" icon={Activity} title={t('db.secPool')} description={t('dbinfo.poolDesc')}
              badge={pool?.waiting > 0 ? <ToneBadge tone="warning" className="font-semibold">{t('dba.poolWaiting', pool.waiting)}</ToneBadge> : null}>
              {pool ? (
                <>
                  <PoolBar t={t} segments={pool.segments} />
                  <InfoList cols={2} at="2xs" className="border-t">
                    <InfoRow t={t} k="pool_name" label={t('db.poolName')} value={pool.name} mono copy wide />
                    <InfoRow t={t} k="pool_active" label={t('db.poolActive')} value={pool.active != null ? fmtNum(pool.active) : null}
                      badge={pool.pct != null ? <ToneBadge tone={pool.tone} className="font-semibold">{t('db.kpiUsage', pool.pct)}</ToneBadge> : null} />
                    <InfoRow t={t} k="pool_idle" label={t('db.poolIdle')} value={pool.idle != null ? fmtNum(pool.idle) : null} />
                    <InfoRow t={t} k="pool_total" label={t('db.poolTotal')} value={pool.total != null ? fmtNum(pool.total) : null} />
                    <InfoRow t={t} k="pool_waiting" label={t('db.poolWaiting')} value={pool.waiting != null ? fmtNum(pool.waiting) : null}
                      help="dbinfo.help.poolWaiting" />
                    <InfoRow t={t} k="pool_max" label={t('db.poolMax')} value={pool.max != null ? fmtNum(pool.max) : null} />
                    <InfoRow t={t} k="pool_min" label={t('db.poolMin')} value={pool.min != null ? fmtNum(pool.min) : null} />
                    <InfoRow t={t} k="pool_conn_timeout" label={t('dbinfo.connTimeout')} value={msText(pool.connectionTimeoutMs, t)}
                      help="dbinfo.help.connTimeout" />
                    <InfoRow t={t} k="pool_idle_timeout" label={t('dbinfo.idleTimeout')} value={msText(pool.idleTimeoutMs, t)} />
                    <InfoRow t={t} k="pool_max_lifetime" label={t('dbinfo.maxLifetime')} value={msText(pool.maxLifetimeMs, t)} />
                  </InfoList>
                </>
              ) : (
                <p data-slot="dbinfo-pool-none" className="m-0 py-3 text-sm text-muted-foreground">{t('dbinfo.poolNone')}</p>
              )}
            </InfoCard>

            {/* JDBC / sürücü — tam genişlik: uzun URL rahat okunsun */}
            <InfoCard id="jdbc" icon={ShieldCheck} title={t('db.secJdbc')} description={t('dbinfo.jdbcDesc')} className="@3xl/dbinfo:col-span-2"
              badge={hasMaskedSecret(url) ? (
                <ToneBadge tone="info" data-slot="dbinfo-masked" className="gap-1 font-semibold">
                  <ShieldCheck aria-hidden="true" />{t('dbinfo.masked')}
                </ToneBadge>
              ) : null}>
              <InfoList>
                <InfoRow t={t} k="jdbc_url" label={t('db.jdbcUrl')} value={url} mono wrap copy />
                <InfoRow t={t} k="driver" label={t('db.driver')} value={driverText(d)} copy />
              </InfoList>
            </InfoCard>
          </div>
        </>
      )}

      {/* Görünmez ama ekran okuyucuya: yenileme bittiğinde kibar duyuru */}
      <span role="status" aria-live="polite" className="sr-only">{info && !loading && stamp ? t('db.updatedAt', stamp) : ''}</span>
    </div>
  )
}
