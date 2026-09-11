import { useEffect, useMemo, useRef, useState } from 'react'
import { Rocket, Tag, ChevronDown, ChevronRight, Sparkles } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useAppVersion } from '../contexts/BrandingProvider.jsx'
import SegmentedControl from './ui/SegmentedControl.jsx'
import PaginationBar from './ui/PaginationBar.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import VersionTimeline from './scripted/VersionTimeline.jsx'
import { DEPLOY_KIND_STYLE, bumpIcon, groupChanges, readLastSeenVersion } from '../utils/releaseUi.js'

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

function ReleaseChangeList({ changes = [], truncated, omitted, t }) {
  const [showAll, setShowAll] = useState(false)
  const groups = groupChanges(changes, showAll ? Infinity : 6)
  const hidden = groups.reduce((n, g) => n + g.more, 0)
  return (
    <div className="rel-changes">
      {groups.map(g => (
        <div key={g.key} className={`rel-group rel-group--${g.key}`}>
          <div className="rel-group-title">{t('releases.changes.' + g.key)} <span className="rel-group-count">{g.items.length + g.more}</span></div>
          <ul className="rel-list">
            {g.items.map((c, i) => (
              <li key={c.sha || i} className="rel-item">
                {c.breaking && <span className="rel-breaking">{t('releases.breaking')}</span>}
                {c.scope && <span className="rel-scope">{c.scope}</span>}
                <span className="rel-subject">{c.subject}</span>
                {c.sha && <span className="rel-sha sys-mono">{String(c.sha).slice(0, 8)}</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
      {hidden > 0 && (
        <button type="button" className="btn btn-sm btn-secondary rel-more" onClick={() => setShowAll(true)}>
          {t('releases.more', hidden)}
        </button>
      )}
      {truncated && <div className="field-hint">{t('releases.truncated', omitted ?? 0)}</div>}
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
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(25)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [openId, setOpenId] = useState(null)
  const loadSeq = useRef(0)
  const lastSeen = useMemo(() => readLastSeenVersion(), [])

  useEffect(() => { const id = setTimeout(() => setQTerm(q.trim()), 300); return () => clearTimeout(id) }, [q])
  useEffect(() => { setPage(1) }, [density, type, qTerm, size])

  useEffect(() => {
    const seq = ++loadSeq.current
    setError(null)
    Promise.resolve(api.system?.getReleases?.({ page, size, density, type, q: qTerm || null }))
      .then(res => {
        if (seq !== loadSeq.current) return
        if (res?.success) setData(res.data)
        else setError(res?.error || t('releases.loadError'))
      })
      .catch(e => { if (seq === loadSeq.current) setError(e?.message || t('releases.loadError')) })
  }, [page, size, density, type, qTerm])   // eslint-disable-line react-hooks/exhaustive-deps

  const items = data?.items ?? []
  const total = data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil(total / size))
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
    return (
      <>
        <span className={`rel-bump rel-bump--${r.bump}`}><BumpIcon size={11} /> {t('version.bump.' + (r.bump || 'patch'))}</span>
        {r.breaking && <span className="rel-breaking">{t('releases.breaking')}</span>}
        {r.deployedHere
          ? <span className="rel-deployed" title={formatDateSec(r.deployedHere)}><Rocket size={11} /> {t('releases.deployedHere', formatDateSec(r.deployedHere))}</span>
          : <span className="rel-notdeployed">{t('releases.notDeployed')}</span>}
        {r.collapsedPatches?.length > 0 && (
          <button type="button" className="rel-collapsed"
            title={t('releases.showPatches')}
            onClick={(e) => { e.stopPropagation(); setDensity('all'); setQ(r.collapsedPatches[0]) }}>
            {t('releases.collapsed', r.collapsedPatches.length)}
          </button>
        )}
        <span className="rel-counts">
          {r.counts?.feat ? <span className="rel-count rel-count--feat">+{r.counts.feat}</span> : null}
          {r.counts?.fix ? <span className="rel-count rel-count--fix">{r.counts.fix}</span> : null}
        </span>
      </>
    )
  }

  function renderMeta(v) {
    const r = v._r
    return (
      <span className="sc-vt-meta">
        <span className="sys-mono">{v.created_at ? formatDateSec(v.created_at) : '—'}</span>
        {r.commitShort && <><span className="sc-vt-sep" aria-hidden="true">·</span><span className="sys-mono rel-sha">{r.commitShort}</span></>}
        {r.prevVersion && <><span className="sc-vt-sep" aria-hidden="true">·</span><span className="sys-muted">v{r.prevVersion} → v{r.version}</span></>}
      </span>
    )
  }

  return (
    <div className="rel-panel">
      <div className="rel-toolbar">
        <SegmentedControl value={density} onChange={setDensity} ariaLabel={t('releases.title')}
          options={[
            { value: 'deployed', label: t('releases.density.deployed'), icon: Rocket },
            { value: 'all', label: t('releases.density.all'), icon: Tag },
          ]} />
        <SegmentedControl value={type} onChange={setType} ariaLabel={t('releases.type.all')} className="rel-type-seg"
          options={TYPES.map(k => ({ value: k, label: t('releases.type.' + k) }))} />
        <input className="input rel-search" value={q} onChange={e => setQ(e.target.value)} placeholder={t('releases.search')} />
      </div>

      {lastSeen && current && lastSeen !== current && (
        <AlertBanner tone="info" icon={Sparkles} className="rel-since">
          {t('releases.sinceStrip', `v${lastSeen}`, `v${current}`)}
        </AlertBanner>
      )}

      {error && <AlertBanner tone="danger" title={t('releases.loadError')}>{String(error)}</AlertBanner>}
      {!error && !data && <LoadingBlock />}
      {data && !indexLoaded && <AlertBanner tone="warning">{t('releases.noIndex')}</AlertBanner>}
      {data && indexLoaded && items.length === 0 && <div className="rel-empty">{t('releases.empty')}</div>}

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

      <PaginationBar page={page} totalPages={totalPages} totalItems={total}
        rangeStart={total ? (page - 1) * size + 1 : 0} rangeEnd={Math.min(total, page * size)}
        pageSize={size} onPageChange={setPage} onPageSizeChange={(n) => setSize(Number(n))} compact />

      {data?.releaseIndex?.loaded && (
        <div className="field-hint rel-index-meta">
          {t('releases.indexMeta', data.releaseIndex.count ?? '?', data.releaseIndex.generatedAt ? formatDateSec(data.releaseIndex.generatedAt) : '—')}
        </div>
      )}
    </div>
  )
}
