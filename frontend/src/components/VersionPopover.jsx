import { useEffect, useState } from 'react'
import { Rocket, Tag, GitCommit, Clock, AlertTriangle, Sparkles, ArrowRight, Server } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import CopyableRef from './ui/CopyableRef.jsx'
import { fmtDuration, groupChanges, bumpIcon } from '../utils/releaseUi.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'

/** Popover verisi 60 sn modül önbelleği: aynı oturumda tekrar tekrar açmak istek üretmesin. */
const CACHE_MS = 60_000
let cache = { at: 0, data: null }
export function _resetVersionCache() { cache = { at: 0, data: null } }

async function loadVersion() {
  if (cache.data && Date.now() - cache.at < CACHE_MS) return cache.data
  const res = await api.system.getVersion()
  if (!res?.success || !res.data) throw new Error(res?.error || 'version')
  cache = { at: Date.now(), data: res.data }
  return res.data
}

/**
 * Sürüm çipinin gövdesi. Sunucu hatasında YALNIZ sürüm satırı kalır (bugünkü davranış — asla boş).
 * Satırlar: sürüm + bump · yayın · devreye alma (ortam + tür) · commit · çalışma süresi · helm.
 * "Yenilikler →" Yardım'a, "Dağıtım geçmişi →" (release_history.read varsa) Sistem Sağlığı'na götürür.
 */
export default function VersionPopover({ appVersion, previousSeen = '', onNavigate }) {
  const t = useT()
  const { canView } = usePermissions()
  const [data, setData] = useState(cache.data && Date.now() - cache.at < CACHE_MS ? cache.data : null)
  const [error, setError] = useState(false)
  const [notes, setNotes] = useState(null)

  useEffect(() => {
    let alive = true
    loadVersion().then(d => { if (alive) setData(d) }).catch(() => { if (alive) setError(true) })
    return () => { alive = false }
  }, [])

  // E1: son ziyaretten beri çıkan sürümler (yalnız damga farklıysa).
  useEffect(() => {
    if (!previousSeen) return undefined
    let alive = true
    api.system.getReleaseNotes(previousSeen)
      .then(r => { if (alive && r?.success) setNotes(r.data?.items ?? []) })
      .catch(() => {})
    return () => { alive = false }
  }, [previousSeen])

  const units = { d: t('version.dur.d'), h: t('version.dur.h'), m: t('version.dur.m') }
  const rel = data?.release
  const live = data?.live
  const BumpIcon = rel ? bumpIcon(rel.bump) : Tag
  const kindLabel = (k) => (k ? t('version.kind.' + k) : '')

  // Ton eşlemeleri (shadcn Badge üzerine): sürüm artışı, devreye alma türü, değişiklik türü
  const BUMP_TONE = { major: 'border-destructive/40 text-destructive', minor: 'border-primary/40 text-primary' }
  const KIND_TONE = {
    UPGRADE: 'border-success/40 text-success', FIRST_SEEN: 'border-success/40 text-success',
    ROLLBACK: 'border-destructive/40 text-destructive', CHANGED: 'text-amber-600', UNKNOWN: 'border-dashed',
  }
  const CHG_TONE = { feat: 'text-success', fix: 'text-primary' }

  return (
    <div className="flex min-w-0 flex-col text-sm">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span data-version="" className="font-mono text-lg font-bold">v{data?.version || appVersion}</span>
        {rel?.bump && (
          <Badge variant="outline" className={BUMP_TONE[rel.bump]}>
            <BumpIcon /> {t('version.bump.' + rel.bump)}
          </Badge>
        )}
        {rel?.breaking && <Badge variant="destructive" className="text-[10px] font-extrabold tracking-wide">{t('releases.breaking')}</Badge>}
      </div>

      {previousSeen && (notes == null || notes.length > 0) && (
        <div className="mb-2 flex items-center gap-1.5 font-semibold text-success">
          <Sparkles className="size-3.5" />
          <span>{notes ? t('version.sinceLast', notes.length) : t('version.newInThis')}</span>
        </div>
      )}

      {error && !data && <p className="py-1.5 text-muted-foreground">{t('version.loadError')}</p>}
      {!error && !data && <p className="py-1.5 text-muted-foreground">{t('version.loading')}</p>}

      {data && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-2.5 gap-y-1.5 [&_dd]:min-w-0 [&_dd]:break-words [&_dt]:inline-flex [&_dt]:items-center [&_dt]:gap-1 [&_dt]:text-xs [&_dt]:font-medium [&_dt]:whitespace-nowrap [&_dt]:text-muted-foreground [&_dt_svg]:size-3">
          <dt><Tag /> {t('version.released')}</dt>
          <dd>{rel?.releasedAt ? formatDateSec(rel.releasedAt) : '—'}</dd>

          <dt><Rocket /> {t('version.live')}</dt>
          <dd>
            {live?.since
              ? <>
                  {formatDateSec(live.since)}
                  <Badge variant="secondary" className="ml-1.5 text-[10px] uppercase">{data.environment}</Badge>
                  {live.kind && <Badge variant="outline" className={cn('ml-1.5 text-[10px]', KIND_TONE[live.kind])}>{kindLabel(live.kind)}</Badge>}
                  {data.releaseLagSeconds != null && data.releaseLagSeconds >= 60 && (
                    <span className="text-muted-foreground"> · {t('version.lag', fmtDuration(data.releaseLagSeconds, units, t('version.justNow')))}</span>
                  )}
                </>
              : <span className="text-muted-foreground">{t('version.liveNever')}</span>}
          </dd>

          <dt><GitCommit /> {t('version.commit')}</dt>
          <dd>
            {data.commitShort
              ? <CopyableRef value={data.commit} copyLabel={t('version.copy')} copiedLabel={t('version.copied')} />
              : '—'}
          </dd>

          <dt><Clock /> {t('version.uptime')}</dt>
          <dd>{fmtDuration(data.uptimeSeconds, units, t('version.justNow')) || '—'}</dd>

          {data.helm?.revision != null && (
            <>
              <dt><Server /> {t('version.helm')}</dt>
              <dd>{data.helm.release ? `${data.helm.release} · ` : ''}rev {data.helm.revision}{data.helm.chartVersion ? ` · chart ${data.helm.chartVersion}` : ''}</dd>
            </>
          )}
        </dl>
      )}

      {data?.mismatch && (
        <div className="mt-2 flex items-start gap-1.5 font-semibold text-amber-600">
          <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
          <span>{t('version.mismatch', data.version, data.imageVersion)}</span>
        </div>
      )}

      {rel?.changes?.length > 0 && (
        <>
          <Separator className="mt-2.5 mb-2" />
          <ul className="flex flex-col gap-1">
            {groupChanges(rel.changes, 3).slice(0, 1).flatMap(g => g.items).map(c => (
              <li key={c.sha || c.subject} className="flex items-baseline gap-1.5">
                <Badge variant="secondary" className={cn('shrink-0 px-1.5 text-[10px]', CHG_TONE[c.type])}>{c.type}</Badge>
                <span>{c.scope ? <span className="text-muted-foreground">{c.scope}: </span> : null}{c.subject}</span>
              </li>
            ))}
          </ul>
        </>
      )}

      <Separator className="mt-2.5 mb-1.5" />
      <div className="flex flex-wrap gap-3">
        <Button type="button" variant="link" size="sm" className="h-auto p-0" onClick={() => onNavigate?.('help', { view: 'releases' })}>
          {t('version.whatsNew')} <ArrowRight />
        </Button>
        {canView('release_history.read') && (
          <Button type="button" variant="link" size="sm" className="h-auto p-0" onClick={() => onNavigate?.('health', { sec: 'releases' })}>
            {t('version.deployments')} <ArrowRight />
          </Button>
        )}
      </div>
    </div>
  )
}
