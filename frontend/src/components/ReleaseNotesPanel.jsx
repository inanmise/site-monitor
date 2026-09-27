import { useEffect, useMemo, useRef, useState } from 'react'
import { Rocket, Tag, ChevronDown, ChevronRight, Sparkles, Search, Inbox } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useAppVersion } from '../contexts/BrandingProvider.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import { useServerPagination } from '../hooks/useServerPagination.js'
import AlertBanner from './ui/AlertBanner.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import VersionTimeline from './scripted/VersionTimeline.jsx'
import { DEPLOY_KIND_STYLE, bumpIcon, groupChanges, readLastSeenVersion } from '../utils/releaseUi.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import StatusBlock from './ui/StatusBlock.jsx'

/**
 * Yardım → Yenilikler (K1/K5): imaja gömülü yayın dizini (docs/releases/index.json → /app/releases.json)
 * + bu ortamın dağıtım kayıtları. ReactMarkdown DIŞINDA — kılavuz markdown'ına bölüm eklemek
 * whitepaper-pdf-freshness kapısını tetiklerdi.
 *
 * <p>Varsayılan görünüm KATLANMIŞ ("Dağıtılanlar"): minor/major + bu ortamda dağıtılmış her sürüm;
 * araya sıkışan yamalar bir çip olarak katlanır, tıklanınca "Tüm sürümler" + arama ile açılır.
 * Satır gövdesi paylaşılan VersionTimeline; genişletince feat/fix/diğer gruplu değişiklik listesi.
 */
const TYPES = ['all', 'feat', 'fix', 'other']

// shadcn (2026-09-26, D2): eski `.rel-*` rozet/liste sınıfları yerine Badge + Tailwind jetonları.
const GROUP_INK = { feat: 'text-success', fix: 'text-primary', other: 'text-muted-foreground' }
const PILL = 'h-5 rounded-full px-1.5 text-[11px] font-bold'
/** Kırıcı değişiklik rozeti (dolgulu kırmızı). */
function BreakingBadge({ t }) {
  return <Badge variant="destructive" data-breaking="" className={cn(PILL, 'tracking-wide uppercase')}>{t('releases.breaking')}</Badge>
}

function ReleaseChangeList({ changes = [], truncated, omitted, t }) {
  const [showAll, setShowAll] = useState(false)
  const groups = groupChanges(changes, showAll ? Infinity : 6)
  const hidden = groups.reduce((n, g) => n + g.more, 0)
  return (
    <div data-slot="rel-changes" className="flex min-w-0 flex-col gap-2.5 rounded-lg border bg-muted/40 px-3 py-2.5">
      {groups.map(g => (
        <div key={g.key} data-group={g.key}>
          <div className={cn('mb-1 text-[0.78em] font-bold tracking-wide uppercase', GROUP_INK[g.key])}>
            {t('releases.changes.' + g.key)} <span className="ml-1 font-semibold text-muted-foreground">{g.items.length + g.more}</span>
          </div>
          <ul className="flex list-none flex-col gap-1 p-0">
            {g.items.map((c, i) => (
              <li key={c.sha || i} className="flex min-w-0 flex-wrap items-baseline gap-1.5 text-[0.9em]">
                {c.breaking && <BreakingBadge t={t} />}
                {c.scope && <Badge variant="secondary" className={cn(PILL, 'rounded-md font-semibold text-muted-foreground')}>{c.scope}</Badge>}
                <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{c.subject}</span>
                {c.sha && <span className="font-mono text-xs text-muted-foreground">{String(c.sha).slice(0, 8)}</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {hidden > 0 && (
        <Button type="button" variant="secondary" size="sm" className="self-start" onClick={() => setShowAll(true)}>
          {t('releases.more', hidden)}
        </Button>
      )}
      {truncated && <p className="text-xs text-muted-foreground">{t('releases.truncated', omitted ?? 0)}</p>}
    </div>
  )
}

export default function ReleaseNotesPanel() {
  const t = useT()
  const appVersion = useAppVersion()
  const [density, setDensity] = useState('deployed')
  const [type, setType] = useState('all')
  const [q, setQ] = useState('')
  const [qTerm, setQTerm] = useState('')
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [openId, setOpenId] = useState(null)
  const loadSeq = useRef(0)
  const lastSeen = useMemo(() => readLastSeenVersion(), [])

  useEffect(() => { const id = setTimeout(() => setQTerm(q.trim()), 300); return () => clearTimeout(id) }, [q])
  // Sayfalama standardı (2026-09-26): panel ön ayarı, süzgeç değişince sayfa 1 (mount'ta değil); API 1-tabanlı.
  const sp = useServerPagination({ listKey: 'release-notes', preset: 'panel', resetDeps: [density, type, qTerm], apiBase: 1 })
  const { apiPage: page, pageSize: size } = sp

  useEffect(() => {
    const seq = ++loadSeq.current
    setError(null)
    Promise.resolve(api.system?.getReleases?.({ page, size, density, type, q: qTerm || null }))
      .then(res => {
        if (seq !== loadSeq.current) return
        if (res?.success) { setData(res.data); sp.bind(res.data) }
        else setError(res?.error || t('releases.loadError'))
      })
      .catch(e => { if (seq === loadSeq.current) setError(e?.message || t('releases.loadError')) })
  }, [page, size, density, type, qTerm])   // eslint-disable-line react-hooks/exhaustive-deps

  const items = data?.items ?? []
  const indexLoaded = !!data?.releaseIndex?.loaded
  const current = data?.currentVersion || appVersion

  const rows = items.map(r => ({
    id: r.version, version: r.version, event_type: 'RELEASE', created_at: r.releasedAt,
    current: r.version === current, note: null, _r: r,
  }))

  const eventStyles = {
    RELEASE: DEPLOY_KIND_STYLE.RELEASE,
  }

  function renderExtra(v) {
    const r = v._r
    const BumpIcon = bumpIcon(r.bump)
    const bumpInk = r.bump === 'major' ? 'text-destructive' : r.bump === 'minor' ? 'text-primary' : 'text-muted-foreground'
    return (
      <>
        <Badge variant="outline" data-bump={r.bump || 'patch'} className={cn(PILL, 'gap-1 font-bold', bumpInk)}>
          <BumpIcon aria-hidden="true" className="size-3" /> {t('version.bump.' + (r.bump || 'patch'))}
        </Badge>
        {r.breaking && <BreakingBadge t={t} />}
        {r.deployedHere
          ? <span className="inline-flex items-center gap-1 text-xs font-semibold text-success" title={formatDateSec(r.deployedHere)}><Rocket aria-hidden="true" className="size-3" /> {t('releases.deployedHere', formatDateSec(r.deployedHere))}</span>
          : <span className="text-xs text-muted-foreground">{t('releases.notDeployed')}</span>}
        {r.collapsedPatches?.length > 0 && (
          // Satırın (role=button) tıklamasına sızmasın: stopPropagation (yamalar "Tüm sürümler"de açılır)
          <Button type="button" variant="outline" size="xs" data-collapsed=""
            title={t('releases.showPatches')}
            className="h-5 rounded-full border-dashed px-2 text-[11px] font-bold text-muted-foreground hover:border-primary hover:text-primary"
            onKeyDown={(e) => e.stopPropagation()}
            onClick={(e) => { e.stopPropagation(); setDensity('all'); setQ(r.collapsedPatches[0]) }}>
            {t('releases.collapsed', r.collapsedPatches.length)}
          </Button>
        )}
        <span className="ml-auto inline-flex gap-1">
          {r.counts?.feat ? <Badge variant="secondary" data-count="feat" className={cn(PILL, 'text-success')}>+{r.counts.feat}</Badge> : null}
          {r.counts?.fix ? <Badge variant="secondary" data-count="fix" className={cn(PILL, 'text-primary')}>{r.counts.fix}</Badge> : null}
        </span>
      </>
    )
  }

  function renderMeta(v) {
    const r = v._r
    return (
      <span className="sc-vt-meta">
        <span className="font-mono">{v.created_at ? formatDateSec(v.created_at) : '—'}</span>
        {r.commitShort && <><span className="sc-vt-sep" aria-hidden="true">·</span><span className="font-mono text-muted-foreground">{r.commitShort}</span></>}
        {r.prevVersion && <><span className="sc-vt-sep" aria-hidden="true">·</span><span className="text-muted-foreground">v{r.prevVersion} → v{r.version}</span></>}
      </span>
    )
  }

  return (
    <div data-slot="release-notes" className="flex min-w-0 flex-col gap-1">
      {/* Araç çubuğu — telefonda sarar; arama telefonda tam genişlik */}
      <div className="mb-3 flex flex-wrap items-center gap-2.5">
        <SegmentedControl value={density} onChange={setDensity} ariaLabel={t('releases.title')}
          options={[
            { value: 'deployed', label: t('releases.density.deployed'), icon: Rocket },
            { value: 'all', label: t('releases.density.all'), icon: Tag },
          ]} />
        <SegmentedControl value={type} onChange={setType} ariaLabel={t('releases.type.all')} className="max-w-full flex-wrap"
          options={TYPES.map(k => ({ value: k, label: t('releases.type.' + k) }))} />
        <InputGroup className="w-full sm:w-auto sm:max-w-xs">
          <InputGroupInput type="search" value={q} onChange={e => setQ(e.target.value)} placeholder={t('releases.search')}
            aria-label={t('releases.search')} />
          <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
      </div>

      {lastSeen && current && lastSeen !== current && (
        <AlertBanner tone="info" icon={Sparkles}>
          {t('releases.sinceStrip', `v${lastSeen}`, `v${current}`)}
        </AlertBanner>
      )}

      {error && <AlertBanner tone="danger" title={t('releases.loadError')}>{String(error)}</AlertBanner>}
      {!error && !data && <LoadingBlock />}
      {data && !indexLoaded && <AlertBanner tone="warning">{t('releases.noIndex')}</AlertBanner>}
      {data && indexLoaded && items.length === 0 && <StatusBlock tone="neutral" icon={Inbox} title={t('releases.empty')} />}

      {items.length > 0 && (
        <VersionTimeline
          rows={rows}
          selId={openId}
          onPick={(v) => setOpenId(prev => prev === v.id ? null : v.id)}
          eventLabel={() => t('releases.title')}
          currentLabel={t('releases.current')}
          renderExtra={renderExtra}
          renderMeta={renderMeta}
          eventStyles={eventStyles}
          caret={(v) => (openId === v.id ? <ChevronDown size={15} className="sc-vt-caret" aria-hidden="true" /> : <ChevronRight size={15} className="sc-vt-caret" aria-hidden="true" />)}
          renderBelow={(v) => (openId === v.id
            ? <ReleaseChangeList changes={v._r.changes} truncated={v._r.truncated} omitted={v._r.omitted} t={t} />
            : null)}
        />
      )}

      <PaginationBar {...sp.bar} />

      {data?.releaseIndex?.loaded && (
        <p className="mt-2.5 text-xs text-muted-foreground">
          {t('releases.indexMeta', data.releaseIndex.count ?? '?', data.releaseIndex.generatedAt ? formatDateSec(data.releaseIndex.generatedAt) : '—')}
        </p>
      )}
    </div>
  )
}
