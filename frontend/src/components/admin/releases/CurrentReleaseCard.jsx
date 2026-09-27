import { ArrowRight, Rocket } from 'lucide-react'
import { formatDate, formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { bumpIcon } from '../../../utils/releaseUi.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { KvList } from '../health/HealthParts.jsx'
import { TONE_CLASS } from '../ToneBadge.jsx'
import { EnvBadge, KindBadge, ShaRef, fmtAgo, fmtDur } from './DeployBadges.jsx'
import { isDeployEvent, parseTs } from './releaseModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import { cn } from '@/lib/utils'

/**
 * Sürüm & Dağıtım → "Koşan sürüm" kartı (2026-09-27 yeniden tasarım). Kaynak `/api/system/version` (bu pod'un
 * derlemesi + `live`: bu ortamda canlıya geçiş). Büyük tek aralıklı sürüm, tür/bump rozetleri, "bu sürümde N gün",
 * iki sütunlu künye (commit + imaj kopyalanır), yayın öne çıkanları ve "Tüm sürüm notları" (→ Sürüm notları görünümü).
 * Test kancaları: `data-slot="deploy-current"`, `data-slot="deploy-current-version"`, `data-slot="deploy-highlights"`.
 */

const CHANGE_TONE = { feat: TONE_CLASS.success, fix: TONE_CLASS.info }

function Stat({ label, children }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="text-[10.5px] font-bold tracking-widest text-muted-foreground uppercase">{label}</span>
      <span className="min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">{children}</span>
    </div>
  )
}

export default function CurrentReleaseCard({ version, error, live, transitions = [], onOpenNotes, onRetry }) {
  const t = useT()
  if (!version && !error) {
    return (
      <Card data-slot="deploy-current" aria-busy="true" className="gap-3 px-4 py-4 shadow-xs sm:px-5">
        <span className="sr-only" role="status">{t('deploy.loading')}</span>
        <Skeleton className="h-5 w-40 motion-reduce:animate-none" />
        <Skeleton className="h-9 w-56 motion-reduce:animate-none" />
        <Skeleton className="h-20 w-full motion-reduce:animate-none" />
      </Card>
    )
  }
  const v = version || {}
  const since = live?.since
  const onVersionSec = since && Number.isFinite(parseTs(since)) ? Math.max(0, (Date.now() - parseTs(since)) / 1000) : null
  const liveRow = (Array.isArray(transitions) ? transitions : []).find((d) => d.version === (live?.version || v.version) && isDeployEvent(d.kind))
  const release = v.release
  const BumpIcon = release?.bump ? bumpIcon(release.bump) : null
  const helm = v.helm?.revision != null
    ? [v.helm.release, `rev ${v.helm.revision}`, v.helm.chartVersion && `chart ${v.helm.chartVersion}`].filter(Boolean).join(' · ')
    : null
  const highlights = Array.isArray(release?.highlights) ? release.highlights : []
  const indexLoaded = !!v.releaseIndex?.loaded

  return (
    <Card data-slot="deploy-current" className="@container/cur min-w-0 gap-3.5 px-4 py-4 shadow-xs sm:px-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2.5">
          <span aria-hidden="true" className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary/10 text-primary">
            <Rocket className="size-[18px]" />
          </span>
          <div className="min-w-0">
            <h3 className="text-[0.95em] font-semibold">{t('deploy.currentTitle')}</h3>
            <p className="text-xs text-muted-foreground">{t('deploy.currentSub')}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <EnvBadge env={v.environment} />
          {live?.version && (
            <Badge variant="outline" data-slot="deploy-live" className={cn('gap-1 rounded-full px-2 font-semibold', TONE_CLASS.success)}>
              <span aria-hidden="true" className="size-1.5 rounded-full bg-success motion-safe:animate-pulse" />{t('deploy.live')}
            </Badge>
          )}
        </div>
      </div>

      {error && (
        <AlertBanner tone="danger" className="mb-0" title={t('deploy.versionError')}
          actions={onRetry && <Button type="button" variant="secondary" size="sm" onClick={onRetry}>{t('deploy.retry')}</Button>}>
          {String(error)}
        </AlertBanner>
      )}

      {v.version && (
        <>
          <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5">
            <span data-slot="deploy-current-version" className="font-mono text-[28px] leading-none font-bold tracking-tight @md/cur:text-[32px]">
              v{v.version}
            </span>
            <span className="flex flex-wrap items-center gap-1.5">
              {live?.kind && <KindBadge kind={live.kind} />}
              {live?.previousVersion && <span className="text-xs text-muted-foreground">{t('deploy.fromVersion', `v${live.previousVersion}`)}</span>}
              {release?.bump && (
                <Badge variant="outline" data-bump={release.bump} className="gap-1 rounded-md px-1.5 font-semibold text-muted-foreground">
                  <BumpIcon aria-hidden="true" className="size-3" />{t('version.bump.' + release.bump)}
                </Badge>
              )}
              {release?.breaking && <Badge variant="destructive" className="rounded-md px-1.5 font-bold">{t('releases.breaking')}</Badge>}
            </span>
          </div>

          <div className="grid grid-cols-2 gap-3 rounded-lg border bg-muted/30 px-3 py-2.5 @xl/cur:grid-cols-4">
            <Stat label={t('deploy.onVersion')}>{onVersionSec != null ? fmtDur(onVersionSec, t) : '—'}</Stat>
            <Stat label={t('deploy.avgLagOne')}>{v.releaseLagSeconds != null ? fmtDur(v.releaseLagSeconds, t) : '—'}</Stat>
            <Stat label={t('deploy.podUptime')}>{v.uptimeSeconds != null ? fmtDur(v.uptimeSeconds, t) : '—'}</Stat>
            <Stat label={t('deploy.restarts')}>{live?.restartsSince ?? 0}</Stat>
          </div>
        </>
      )}

      {v.mismatch && <AlertBanner tone="warning" className="mb-0">{t('deploy.mismatch')}</AlertBanner>}

      {v.version && (
        <div className="grid min-w-0 gap-x-8 gap-y-1 @xl/cur:grid-cols-2">
          <KvList>
            <dt>{t('deploy.since')}</dt>
            <dd>
              {since ? <>{formatDateSec(since)} <span className="text-muted-foreground">· {fmtAgo(since)}</span></>
                : <span className="text-muted-foreground">{t('version.liveNever')}</span>}
            </dd>
            <dt>{t('version.released')}</dt>
            <dd>{release?.releasedAt ? formatDate(release.releasedAt) : '—'}</dd>
            <dt>{t('deploy.recordedBy')}</dt>
            <dd>{liveRow?.createdBy || t('deploy.autoRecorded')}</dd>
            <dt>{t('deploy.commit')}</dt>
            <dd>{v.commit ? <ShaRef value={v.commit} short={v.commitShort || v.commit.slice(0, 8)} /> : '—'}</dd>
          </KvList>
          <KvList>
            <dt>{t('deploy.image')}</dt>
            <dd>{v.imageRef ? <ShaRef value={v.imageRef} /> : (v.imageVersion || '—')}</dd>
            <dt>{t('deploy.helm')}</dt>
            <dd className="font-mono text-[0.95em]">{helm || '—'}</dd>
            <dt>{t('deploy.pod')}</dt>
            <dd className="font-mono text-[0.95em]">{v.instance?.pod || v.instance?.hostname || '—'}</dd>
            <dt>{t('deploy.f.node')}</dt>
            <dd className="font-mono text-[0.95em]">{v.instance?.node || '—'}</dd>
          </KvList>
        </div>
      )}

      {v.version && (highlights.length > 0 || indexLoaded) && (
        <div data-slot="deploy-highlights" className="flex min-w-0 flex-col gap-2 rounded-lg border px-3 py-2.5">
          {highlights.length > 0 && (
            <>
              <span className="text-[10.5px] font-bold tracking-widest text-muted-foreground uppercase">{t('deploy.highlights', `v${v.version}`)}</span>
              <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
                {highlights.map((h, i) => (
                  <li key={i} className="flex min-w-0 items-baseline gap-2 text-[0.88em]">
                    <Badge variant="outline" className={cn('shrink-0 rounded-md px-1.5 text-[11px] font-semibold', CHANGE_TONE[h.type] || TONE_CLASS.muted)}>
                      {t('releases.changes.' + (h.type === 'feat' || h.type === 'fix' ? h.type : 'other'))}
                    </Badge>
                    <span className="min-w-0 [overflow-wrap:anywhere]">
                      {h.scope && <span className="font-semibold">{h.scope}: </span>}{h.subject}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
          {onOpenNotes && (
            <Button type="button" variant="outline" size="sm" className="h-10 self-start @md/cur:h-8" onClick={onOpenNotes}>
              {t('deploy.allNotes')} <ArrowRight aria-hidden="true" />
            </Button>
          )}
        </div>
      )}
    </Card>
  )
}
