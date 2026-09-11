import { useEffect, useState } from 'react'
import { Rocket, Tag, GitCommit, Clock, AlertTriangle, Sparkles, ArrowRight, Server } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import CopyableRef from './ui/CopyableRef.jsx'
import { fmtDuration, groupChanges, bumpIcon } from '../utils/releaseUi.js'

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

  return (
    <div className="sb-version-pop-body">
      <div className="sb-version-pop-hdr">
        <span className="sb-version-pop-ver">v{data?.version || appVersion}</span>
        {rel?.bump && (
          <span className={`sb-version-bump sb-version-bump--${rel.bump}`}>
            <BumpIcon size={11} /> {t('version.bump.' + rel.bump)}
          </span>
        )}
        {rel?.breaking && <span className="sb-version-breaking">{t('releases.breaking')}</span>}
      </div>

      {previousSeen && (notes == null || notes.length > 0) && (
        <div className="sb-version-new">
          <Sparkles size={13} />
          <span>{notes ? t('version.sinceLast', notes.length) : t('version.newInThis')}</span>
        </div>
      )}

      {error && !data && (
        <div className="sb-version-row sb-version-muted">{t('version.loadError')}</div>
      )}
      {!error && !data && (
        <div className="sb-version-row sb-version-muted">{t('version.loading')}</div>
      )}

      {data && (
        <dl className="sb-version-dl">
          <dt><Tag size={12} /> {t('version.released')}</dt>
          <dd>{rel?.releasedAt ? formatDateSec(rel.releasedAt) : '—'}</dd>

          <dt><Rocket size={12} /> {t('version.live')}</dt>
          <dd>
            {live?.since
              ? <>
                  {formatDateSec(live.since)}
                  <span className="sb-version-tag">{data.environment}</span>
                  {live.kind && <span className={`sb-version-kind sb-version-kind--${live.kind}`}>{kindLabel(live.kind)}</span>}
                  {data.releaseLagSeconds != null && data.releaseLagSeconds >= 60 && (
                    <span className="sb-version-muted"> · {t('version.lag', fmtDuration(data.releaseLagSeconds, units, t('version.justNow')))}</span>
                  )}
                </>
              : <span className="sb-version-muted">{t('version.liveNever')}</span>}
          </dd>

          <dt><GitCommit size={12} /> {t('version.commit')}</dt>
          <dd>
            {data.commitShort
              ? <CopyableRef value={data.commit} copyLabel={t('version.copy')} copiedLabel={t('version.copied')} />
              : '—'}
          </dd>

          <dt><Clock size={12} /> {t('version.uptime')}</dt>
          <dd>{fmtDuration(data.uptimeSeconds, units, t('version.justNow')) || '—'}</dd>

          {data.helm?.revision != null && (
            <>
              <dt><Server size={12} /> {t('version.helm')}</dt>
              <dd>{data.helm.release ? `${data.helm.release} · ` : ''}rev {data.helm.revision}{data.helm.chartVersion ? ` · chart ${data.helm.chartVersion}` : ''}</dd>
            </>
          )}
        </dl>
      )}

      {data?.mismatch && (
        <div className="sb-version-warn">
          <AlertTriangle size={13} />
          <span>{t('version.mismatch', data.version, data.imageVersion)}</span>
        </div>
      )}

      {rel?.changes?.length > 0 && (
        <ul className="sb-version-hl">
          {groupChanges(rel.changes, 3).slice(0, 1).flatMap(g => g.items).map(c => (
            <li key={c.sha || c.subject}>
              <span className={`sb-version-chg sb-version-chg--${c.type}`}>{c.type}</span>
              {c.scope ? <span className="sb-version-muted">{c.scope}: </span> : null}{c.subject}
            </li>
          ))}
        </ul>
      )}

      <div className="sb-version-actions">
        <button type="button" className="sb-version-link" onClick={() => onNavigate?.('help', { view: 'releases' })}>
          {t('version.whatsNew')} <ArrowRight size={12} />
        </button>
        {canView('release_history.read') && (
          <button type="button" className="sb-version-link" onClick={() => onNavigate?.('health', { sec: 'releases' })}>
            {t('version.deployments')} <ArrowRight size={12} />
          </button>
        )}
      </div>
    </div>
  )
}
