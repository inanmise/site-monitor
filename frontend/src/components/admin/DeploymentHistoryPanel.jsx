import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Download, Rocket, PenLine, History, Grid3x3, RefreshCw, Trash2, GitCommit, Clock, Server, Box } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import PaginationBar from '../ui/PaginationBar.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import Field from '../ui/Field.jsx'
import CopyableRef from '../ui/CopyableRef.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import VersionTimeline from '../scripted/VersionTimeline.jsx'
import { readPageSize, writePageSize } from '../../hooks/usePagination.js'
import { useUrlQuerySync, readUrlParam, readUrlInt } from '../../hooks/useUrlQuerySync.js'
import { DEPLOY_KIND_STYLE, fmtDuration } from '../../utils/releaseUi.js'
import { Button } from '@/components/shadcn/button'

/**
 * Sistem Sağlığı → "Sürüm & Dağıtım" (K1, K8, K10). Dört blok: koşan sürüm kartı, özet şeridi,
 * geçiş zaman çizelgesi (yeniden başlatmalar katlı), sayfalı kayıt tablosu (+CSV) ve sürüm×ortam
 * matrisi. Yazma eylemleri (elle kayıt / geri doldurma / elle kaydı silme) yalnız `canEdit`.
 *
 * <p>URL param'ları `d_` önekiyle (PAGE_STATE_PREFIXES) — uygulamanın `tab`/`sec` anahtarlarına dokunmaz.
 * Boş/hatalı yanıtta çökmez: her istek `res?.success` ile okunur (jsdom testleri boş proxy verir).
 */
const SOURCES = ['all', 'STARTUP', 'BACKFILL', 'MANUAL']

function StatCard({ label, value, hint }) {
  return (
    <div className="deploy-stat">
      <span className="deploy-stat-value">{value ?? '—'}</span>
      <span className="deploy-stat-label">{label}</span>
      {hint && <span className="deploy-stat-hint">{hint}</span>}
    </div>
  )
}

function toIsoUtc(localValue) {
  if (!localValue) return ''
  const d = new Date(localValue)
  return Number.isNaN(d.getTime()) ? '' : d.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

function ManualEntryModal({ open, onClose, onSaved, defaultEnv, t }) {
  const toast = useToast()
  const [form, setForm] = useState({ environment: defaultEnv || '', version: '', at: '', note: '', commit: '' })
  const [saving, setSaving] = useState(false)
  const [err, setErr] = useState(null)
  useEffect(() => { if (open) { setForm({ environment: defaultEnv || '', version: '', at: '', note: '', commit: '' }); setErr(null) } }, [open, defaultEnv])
  const f = (k, v) => setForm(p => ({ ...p, [k]: v }))

  async function save() {
    setSaving(true); setErr(null)
    try {
      const res = await api.admin.createDeployment({
        environment: form.environment.trim(), version: form.version.trim(), started_at: toIsoUtc(form.at),
        note: form.note.trim(), commit: form.commit.trim() || null,
      })
      if (res?.success) { toast.success(t('deploy.saved')); onSaved?.(); onClose() }
      else setErr(res?.error || t('deploy.loadError'))
    } catch (e) { setErr(e?.message || String(e)) }
    finally { setSaving(false) }
  }

  return (
    <ModalShell open={open} onClose={onClose} title={t('deploy.manualTitle')} icon={PenLine} size="sm" busy={saving}
      footer={<>
        <Button variant="secondary" onClick={onClose} disabled={saving}>{t('deploy.cancel')}</Button>
        <Button onClick={save}
          disabled={saving || !form.environment.trim() || !form.version.trim() || !form.at || !form.note.trim()}>
          {t('deploy.save')}
        </Button>
      </>}>
      <div className="field-hint" style={{ marginBottom: 10 }}>{t('deploy.manualHint')}</div>
      {err && <AlertBanner tone="danger">{String(err)}</AlertBanner>}
      <Field label={t('deploy.manualEnv')} required>
        {({ id }) => <input id={id} className="input" value={form.environment} onChange={e => f('environment', e.target.value)} placeholder="prod" />}
      </Field>
      <Field label={t('deploy.manualVersion')} required>
        {({ id }) => <input id={id} className="input sys-mono" value={form.version} onChange={e => f('version', e.target.value)} placeholder="20.53.2" />}
      </Field>
      <Field label={t('deploy.manualAt')} required>
        {({ id }) => <input id={id} className="input" type="datetime-local" value={form.at} onChange={e => f('at', e.target.value)} />}
      </Field>
      <Field label={t('deploy.manualCommit')}>
        {({ id }) => <input id={id} className="input sys-mono" value={form.commit} onChange={e => f('commit', e.target.value)} maxLength={40} />}
      </Field>
      <Field label={t('deploy.manualNote')} required>
        {({ id }) => <textarea id={id} className="input" rows={3} maxLength={500} value={form.note} onChange={e => f('note', e.target.value)} />}
      </Field>
    </ModalShell>
  )
}

export default function DeploymentHistoryPanel({ canEdit = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const units = { d: t('version.dur.d'), h: t('version.dur.h'), m: t('version.dur.m') }

  const [version, setVersion] = useState(null)
  const [timeline, setTimeline] = useState(null)
  const [tlError, setTlError] = useState(null)
  const [env, setEnv] = useState(() => readUrlParam('d_env', ''))
  const [source, setSource] = useState(() => SOURCES.includes(readUrlParam('d_source', 'all')) ? readUrlParam('d_source', 'all') : 'all')
  const [q, setQ] = useState(() => readUrlParam('d_q', ''))
  const [qTerm, setQTerm] = useState(q)
  const [page, setPage] = useState(() => Math.max(1, readUrlInt('d_page', 1)))
  const [size, setSize] = useState(() => readPageSize('deployments', 20))
  const [rows, setRows] = useState(null)
  const [total, setTotal] = useState(0)
  const [environments, setEnvironments] = useState([])
  const [matrix, setMatrix] = useState(null)
  const [matrixOpen, setMatrixOpen] = useState(false)
  const [manualOpen, setManualOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const loadSeq = useRef(0)

  useEffect(() => { const id = setTimeout(() => setQTerm(q.trim()), 300); return () => clearTimeout(id) }, [q])
  useEffect(() => { setPage(1) }, [env, source, qTerm, size])
  useUrlQuerySync({ d_env: env || null, d_source: source === 'all' ? null : source, d_q: qTerm || null, d_page: page > 1 ? page : null })

  // Koşan sürüm + zaman çizelgesi (seçili ortam; boş → koşan ortam).
  useEffect(() => {
    let alive = true
    Promise.resolve(api.system?.getVersion?.()).then(r => { if (alive && r?.success) setVersion(r.data) }).catch(() => {})
    setTlError(null)
    Promise.resolve(api.admin?.getDeploymentTimeline?.(env || undefined))
      .then(r => {
        if (!alive) return
        if (r?.success) { setTimeline(r.data); if (Array.isArray(r.data?.environments)) setEnvironments(r.data.environments) }
        else setTlError(r?.error || t('deploy.loadError'))
      })
      .catch(e => { if (alive) setTlError(e?.message || t('deploy.loadError')) })
    return () => { alive = false }
  }, [env, refreshKey])   // eslint-disable-line react-hooks/exhaustive-deps

  const params = useMemo(() => ({
    env: env || null, source: source === 'all' ? null : source, q: qTerm || null, page, size, sort: 'started_at', dir: 'desc',
  }), [env, source, qTerm, page, size])

  useEffect(() => {
    const seq = ++loadSeq.current
    Promise.resolve(api.admin?.getDeployments?.(params))
      .then(r => {
        if (seq !== loadSeq.current) return
        if (r?.success) { setRows(Array.isArray(r.data) ? r.data : []); setTotal(r.total ?? 0); if (Array.isArray(r.environments)) setEnvironments(r.environments) }
        else setRows([])
      })
      .catch(() => { if (seq === loadSeq.current) setRows([]) })
  }, [params, refreshKey])

  const loadMatrix = useCallback((all = false) => {
    Promise.resolve(api.admin?.getDeploymentMatrix?.(all))
      .then(r => { if (r?.success) setMatrix(r.data) })
      .catch(() => {})
  }, [])
  useEffect(() => { if (matrixOpen && !matrix) loadMatrix(false) }, [matrixOpen, matrix, loadMatrix])

  const refresh = () => { setMatrix(null); setRefreshKey(k => k + 1) }

  async function backfill() {
    const n = timeline?.backfillCandidates ?? 0
    const targetEnv = env || timeline?.environment || version?.environment || ''
    if (!n) { toast.success(t('deploy.backfillNone')); return }
    const ok = await showConfirm({
      title: t('deploy.backfillConfirmTitle'), message: t('deploy.backfillConfirm', n, targetEnv),
      variant: 'primary', confirmText: t('deploy.backfill'), cancelText: t('deploy.cancel'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const r = await api.admin.backfillDeployments(targetEnv || null)
      if (r?.success) { toast.success(t('deploy.backfillDone', r.data?.inserted ?? 0, r.data?.skippedExisting ?? 0)); refresh() }
      else toast.error(r?.error || t('deploy.loadError'))
    } catch (e) { toast.error(e?.message || String(e)) }
    finally { setBusy(false) }
  }

  async function del(row) {
    const ok = await showConfirm({ title: t('deploy.delete'), message: t('deploy.deleteConfirm', row.environment, row.version), confirmText: t('deploy.delete'), cancelText: t('deploy.cancel') })
    if (!ok) return
    try {
      const r = await api.admin.deleteDeployment(row.id)
      if (r?.success) { toast.success(t('deploy.deleted')); refresh() }
      else toast.error(r?.error || t('deploy.loadError'))
    } catch (e) { toast.error(e?.message || String(e)) }
  }

  const summary = timeline?.summary
  const current = timeline?.current
  const transitions = timeline?.transitions ?? []
  const tlRows = transitions.map(d => ({
    id: d.id, version: d.version || '?', event_type: d.kind, created_at: d.startedAt, current: !!d.current, note: d.note, _d: d,
  }))
  const kindLabel = (k) => t('version.kind.' + (k || 'UNKNOWN'))
  const csvUrl = api.admin?.getDeploymentsCsvUrl?.({ env: env || null, source: source === 'all' ? null : source, q: qTerm || null }) || '#'
  const totalPages = Math.max(1, Math.ceil(total / size))

  return (
    <div className="deploy-panel">
      {tlError && <AlertBanner tone="danger" title={t('deploy.loadError')}>{String(tlError)}</AlertBanner>}

      {/* Koşan sürüm kartı */}
      <div className="sys-grid">
        <div className="sys-card deploy-current">
          <div className="sys-card-header">
            <div className="hb-title-row"><Rocket size={18} /><h3>{t('deploy.currentTitle')}</h3></div>
            {version?.version && <span className="sc-ver-chip sc-ver-chip--cell">v{version.version}</span>}
          </div>
          {version?.mismatch && <AlertBanner tone="warning">{t('deploy.mismatch')}</AlertBanner>}
          <dl className="sys-dl">
            <dt>{t('deploy.env')}</dt><dd>{version?.environment || '—'}</dd>
            <dt>{t('deploy.since')}</dt>
            <dd>
              {current?.since ? <>{formatDateSec(current.since)} <span className={`deploy-kind deploy-kind--${current.kind}`}>{kindLabel(current.kind)}</span></> : <span className="sys-muted">{t('version.liveNever')}</span>}
            </dd>
            <dt>{t('deploy.previous')}</dt><dd>{current?.previousVersion ? `v${current.previousVersion}` : '—'}</dd>
            <dt>{t('deploy.restarts')}</dt><dd>{current?.restartsSince ?? 0}</dd>
            <dt><GitCommit size={12} /> {t('deploy.commit')}</dt>
            <dd>{version?.commit ? <CopyableRef value={version.commit} copyLabel={t('version.copy')} copiedLabel={t('version.copied')} /> : '—'}</dd>
            <dt><Box size={12} /> {t('deploy.image')}</dt><dd className="sys-mono sys-small">{version?.imageRef || version?.imageVersion || '—'}</dd>
            <dt><Server size={12} /> {t('deploy.helm')}</dt>
            <dd>{version?.helm?.revision != null ? `${version.helm.release || ''} rev ${version.helm.revision}${version.helm.chartVersion ? ` · ${version.helm.chartVersion}` : ''}` : '—'}</dd>
            <dt>{t('deploy.pod')}</dt><dd className="sys-mono sys-small">{version?.instance?.pod || version?.instance?.hostname || '—'}{version?.instance?.node ? ` @ ${version.instance.node}` : ''}</dd>
            <dt><Clock size={12} /> {t('deploy.uptime')}</dt><dd>{fmtDuration(version?.uptimeSeconds, units, t('version.justNow')) || '—'}</dd>
          </dl>
        </div>
      </div>

      {/* Özet şeridi */}
      {summary && (
        <div className="deploy-stats">
          <StatCard label={t('deploy.last30')} value={summary.deploymentsLast30d} />
          <StatCard label={t('deploy.restarts7')} value={summary.restartsLast7d} />
          <StatCard label={t('deploy.rollbacks')} value={summary.rollbacks} />
          <StatCard label={t('deploy.avgLag')} value={summary.avgReleaseLagSeconds != null ? fmtDuration(summary.avgReleaseLagSeconds, units, t('version.justNow')) : '—'} />
          <StatCard label={t('deploy.skipped')} value={summary.skippedReleases ?? '—'} />
        </div>
      )}

      {/* Eylemler */}
      <div className="deploy-toolbar">
        <SegmentedControl value={env} onChange={setEnv} ariaLabel={t('deploy.env')}
          options={[{ value: '', label: t('deploy.envAll') }, ...environments.map(e => ({ value: e, label: e }))]} />
        <SegmentedControl value={source} onChange={setSource} ariaLabel={t('deploy.source')}
          options={SOURCES.map(s => ({ value: s, label: s === 'all' ? t('deploy.sourceAll') : t('version.source.' + s) }))} />
        <input className="input deploy-search" value={q} onChange={e => setQ(e.target.value)} placeholder={t('deploy.search')} />
        <div className="deploy-toolbar-right">
          <Button type="button" variant="secondary" size="sm" onClick={refresh} title={t('deploy.refresh')}><RefreshCw size={13} /></Button>
          <Button asChild variant="secondary" size="sm"><a href={csvUrl} download="deployment-history.csv"><Download size={13} /> {t('deploy.csv')}</a></Button>
          {canEdit && (
            <>
              <Button type="button" variant="secondary" size="sm" onClick={backfill} disabled={busy}>
                <History size={13} /> {t('deploy.backfill')}{timeline?.backfillCandidates ? ` (${timeline.backfillCandidates})` : ''}
              </Button>
              <Button type="button" size="sm" onClick={() => setManualOpen(true)}>
                <PenLine size={13} /> {t('deploy.manualAdd')}
              </Button>
            </>
          )}
        </div>
      </div>

      {/* Zaman çizelgesi */}
      <div className="deploy-block">
        <div className="deploy-block-title">{t('deploy.timelineTitle')}
          {timeline?.restartCount > 0 && <span className="field-hint"> · {t('deploy.restartsFolded', timeline.restartCount)}</span>}
        </div>
        {!timeline && !tlError && <LoadingBlock />}
        {timeline && tlRows.length === 0 && (
          <div className="deploy-empty"><strong>{t('deploy.empty')}</strong><div className="field-hint">{t('deploy.emptyHint')}</div></div>
        )}
        {tlRows.length > 0 && (
          <VersionTimeline rows={tlRows} selId={null} onPick={() => {}}
            eventLabel={kindLabel} currentLabel={t('releases.current')}
            eventStyles={DEPLOY_KIND_STYLE}
            renderExtra={(v) => (
              <>
                {v._d.previousVersion && <span className="sys-muted">← v{v._d.previousVersion}</span>}
                <span className={`deploy-src deploy-src--${v._d.source}`}>{t('version.source.' + v._d.source)}</span>
                {v._d.environment && env === '' && <span className="sb-version-tag">{v._d.environment}</span>}
              </>
            )}
            renderMeta={(v) => (
              <span className="sc-vt-meta">
                <span className="sys-mono">{formatDateSec(v.created_at)}</span>
                {v._d.commitShort && <><span className="sc-vt-sep" aria-hidden="true">·</span><span className="sys-mono">{v._d.commitShort}</span></>}
                {v._d.leadTimeSeconds != null && v._d.leadTimeSeconds >= 60 && <><span className="sc-vt-sep" aria-hidden="true">·</span><span>{t('version.lag', fmtDuration(v._d.leadTimeSeconds, units, t('version.justNow')))}</span></>}
              </span>
            )} />
        )}
      </div>

      {/* Kayıt tablosu */}
      <div className="deploy-block">
        <div className="deploy-block-title">{t('deploy.tableTitle')}</div>
        {rows == null && <LoadingBlock />}
        {rows && rows.length === 0 && <div className="deploy-empty">{t('deploy.empty')}</div>}
        {rows && rows.length > 0 && (
          <div className="admin-table-wrap">
            <table className="admin-table deploy-table">
              <thead>
                <tr>
                  <th>{t('deploy.col.started')}</th>
                  <th>{t('deploy.col.version')}</th>
                  <th>{t('deploy.col.kind')}</th>
                  <th>{t('deploy.env')}</th>
                  <th>{t('deploy.col.commit')}</th>
                  <th>{t('deploy.col.pod')}</th>
                  <th>{t('deploy.col.helm')}</th>
                  <th>{t('deploy.col.ended')}</th>
                  <th>{t('deploy.col.source')}</th>
                  {canEdit && <th className="um-col-actions">{t('deploy.col.actions')}</th>}
                </tr>
              </thead>
              <tbody>
                {rows.map(r => (
                  <tr key={r.id} className={r.current ? 'is-current' : ''}>
                    <td className="sys-mono sys-small">{formatDateSec(r.startedAt)}</td>
                    <td>{r.version ? <span className="sc-ver-chip sc-ver-chip--cell">v{r.version}</span> : <span className="sys-muted">?</span>}{r.current && <span className="sc-ver-current">{t('releases.current')}</span>}</td>
                    <td><span className={`deploy-kind deploy-kind--${r.kind}`}>{kindLabel(r.kind)}</span>{r.previousVersion && <span className="sys-muted sys-small"> ← v{r.previousVersion}</span>}</td>
                    <td>{r.environment}</td>
                    <td className="sys-mono sys-small">{r.commitShort || '—'}</td>
                    <td className="sys-mono sys-small" title={r.instanceId || ''}>{r.pod || r.hostname || '—'}</td>
                    <td className="sys-small">{r.helm?.revision != null ? `rev ${r.helm.revision}` : '—'}</td>
                    <td className="sys-small">
                      {r.endedAt ? <>{formatDateSec(r.endedAt)} <span className="sys-muted">({r.endReason})</span></>
                        : r.current ? <span className="badge badge-ok">{t('deploy.running')}</span>
                        : r.source === 'STARTUP' ? <span className="badge badge-err" title={t('deploy.endUnrecorded')}>{t('deploy.endUnrecorded')}</span>
                        : '—'}
                    </td>
                    <td><span className={`deploy-src deploy-src--${r.source}`}>{t('version.source.' + r.source)}</span>{r.note && <div className="field-hint deploy-note" title={r.note}>{r.note}</div>}</td>
                    {canEdit && (
                      <td className="um-col-actions">
                        {r.source === 'MANUAL' && (
                          <Button type="button" variant="destructive" size="sm" onClick={() => del(r)} title={t('deploy.delete')}
                            // Ad kaydı ayırır (sürüm + başlangıç); ipucu kısa kalır (2026-09-25, R15).
                            aria-label={t('a11y.rowAction', `${r.version ? `v${r.version}` : '?'} · ${formatDateSec(r.startedAt)}`, t('deploy.delete'))}><Trash2 size={13} /></Button>
                        )}
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <PaginationBar page={page} totalPages={totalPages} totalItems={total}
          rangeStart={total ? (page - 1) * size + 1 : 0} rangeEnd={Math.min(total, page * size)}
          pageSize={size} onPageChange={setPage}
          onPageSizeChange={(n) => { const v = Number(n); setSize(v); writePageSize('deployments', v) }} compact />
      </div>

      {/* Matris */}
      <div className="deploy-block">
        <button type="button" className="deploy-block-title deploy-block-toggle" onClick={() => setMatrixOpen(o => !o)} aria-expanded={matrixOpen}>
          <Grid3x3 size={14} /> {t('deploy.matrixTitle')}
        </button>
        {matrixOpen && !matrix && <LoadingBlock />}
        {matrixOpen && matrix && (
          <div className="admin-table-wrap">
            <table className="admin-table deploy-matrix">
              <thead>
                <tr><th>{t('deploy.col.version')}</th><th>{t('version.released')}</th>{(matrix.environments || []).map(e => <th key={e}>{e}</th>)}</tr>
              </thead>
              <tbody>
                {(matrix.releases || []).map(r => (
                  <tr key={r.version} className={r.neverDeployed ? 'is-never' : ''}>
                    <td><span className="sc-ver-chip sc-ver-chip--cell">v{r.version}</span> <span className="sys-muted sys-small">{r.bump}</span></td>
                    <td className="sys-mono sys-small">{r.releasedAt ? formatDateSec(r.releasedAt) : '—'}</td>
                    {(matrix.environments || []).map(e => (
                      <td key={e} className="sys-mono sys-small">{r.deployedIn?.[e] ? formatDateSec(r.deployedIn[e]) : <span className="sys-muted">{r.neverDeployed ? t('deploy.matrixNever') : '—'}</span>}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            {matrix.truncated && (
              <div className="field-hint">{t('deploy.matrixTruncated')} · <Button type="button" variant="secondary" size="sm" onClick={() => loadMatrix(true)}>{t('deploy.matrixShowAll')}</Button></div>
            )}
          </div>
        )}
      </div>

      <ManualEntryModal open={manualOpen} onClose={() => setManualOpen(false)} onSaved={refresh}
        defaultEnv={env || timeline?.environment || version?.environment || ''} t={t} />
    </div>
  )
}

