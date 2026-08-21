import { useEffect, useMemo, useState } from 'react'
import { History } from 'lucide-react'
import { api, formatDateSec } from '../../api/client'
import ModalShell from '../ui/ModalShell.jsx'
import CodeEditor from '../ui/CodeEditor.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { lineDiff, collapseContext, envNameDiff } from '../../utils/lineDiff.js'

/**
 * Şablon sürüm geçmişi — "kim, ne zaman, ne yaptı" TEK zaman çizelgesinde.
 *
 * <p>Monitör sürüm sekmesinin ({@code ScriptedVersionsTab}) şablon ikizi. İki fark var:
 * <ul>
 *   <li>Olay sözlüğü daha geniş: içerik düzenlemesinin yanında yaşam döngüsü olayları
 *       (SEED/PROMOTE/DEMOTE/DELETE/UNDELETE) da satır üretir — bir şablonun kapsamı
 *       değiştiğinde "bunu kim genele açtı?" sorusunun cevabı denetim kaydını açmadan görünür.</li>
 *   <li>Geri yükleme ayrı bir uç DEĞİL: {@code PUT + restoredFrom} ile yapılır, yani geri
 *       yükleme de geçmişi EZMEZ, yeni bir sürüm satırı olarak eklenir.</li>
 * </ul>
 */
export default function ScriptedTemplateVersions({ t, template, canEdit, onClose, onRestored }) {
  const [rows, setRows] = useState(null)
  const [sel, setSel] = useState(null)
  const [bodies, setBodies] = useState({})
  const [view, setView] = useState('diff')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let alive = true
    api.monitoring.getScriptedTemplateVersions(template.id).then(r => {
      if (alive) setRows(r?.success ? (r.data?.versions || []) : [])
    }).catch(() => { if (alive) setRows([]) })
    return () => { alive = false }
  }, [template.id])

  // Seçili sürüm VE bir önceki gerekiyor (diff için); her gövde yalnız bir kez çekilir.
  const prev = useMemo(() => {
    if (!sel || !rows) return null
    const i = rows.findIndex(r => r.id === sel.id)
    return i >= 0 && i + 1 < rows.length ? rows[i + 1] : null   // liste sequence_no DESC
  }, [sel, rows])

  useEffect(() => {
    if (!sel) return
    let alive = true
    const want = [sel.id, prev?.id].filter(id => id != null && bodies[id] === undefined)
    want.forEach(id => {
      api.monitoring.getScriptedTemplateVersion(template.id, id).then(r => {
        if (alive && r?.success) setBodies(b => ({ ...b, [id]: r.data }))
      }).catch(() => {})
    })
    return () => { alive = false }
  }, [template.id, sel, prev, bodies])

  async function restore() {
    const body = bodies[sel.id]
    if (!body) return
    setBusy(true); setError(null)
    const res = await api.monitoring.updateScriptedTemplate(template.id, {
      script: body.script,
      env: (body.env || []).map(e => ({ name: e.name, secret: !!e.secret, desc: e.desc || null })),
      restoredFrom: `v${sel.version}`,
    })
    setBusy(false)
    if (!res?.success) { setError(res?.error || t('tpl.restoreError')); return }
    onRestored?.(res.data, sel.version)
  }

  const detail = sel ? bodies[sel.id] : null
  const prevDetail = prev ? bodies[prev.id] : null

  return (
    <ModalShell open onClose={onClose} title={t('tpl.versionsTitle', template.name)} icon={History}
      size="lg" busy={busy}>
      {error && <StatusBlock tone="danger" title={error} />}
      {rows === null
        ? <LoadingBlock label={t('modal.loading')} />
        : rows.length === 0
          ? <StatusBlock tone="neutral" title={t('tpl.versionNone')} />
          : (<div className="sc-versions">
            <table className="health-dbtable">
              <thead><tr>
                <th className="dbtcol-th">{t('scripted.versionColVersion')}</th>
                <th className="dbtcol-th">{t('scripted.versionColWhen')}</th>
                <th className="dbtcol-th">{t('scripted.versionColWho')}</th>
                <th className="dbtcol-th">{t('scripted.versionColEvent')}</th>
                <th className="dbtcol-th">{t('scripted.versionColNote')}</th>
              </tr></thead>
              <tbody>
                {rows.map(v => (
                  <tr key={v.id} className={`uact-row-click${sel?.id === v.id ? ' is-sel' : ''}`}
                      style={{ cursor: 'pointer' }}
                      onClick={() => { setSel(sel?.id === v.id ? null : v); setView('diff') }}>
                    <td>
                      <span className="sc-ver-chip">v{v.version}</span>
                      {v.current && <span className="sc-ver-current">{t('scripted.versionCurrent')}</span>}
                    </td>
                    <td className="sys-mono sys-small">{formatDateSec(v.created_at)}</td>
                    <td className="sys-small"><UserBadge username={v.created_by} size="sm" inline nameOnly /></td>
                    <td className="sys-small">{eventLabel(t, v.event_type)}</td>
                    <td className="sys-small">{v.note || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>

            {sel && (
              <div className="sc-ver-preview">
                <div className="sc-ver-preview-head">
                  <span className="sc-ver-chip">v{sel.version}</span>
                  {prev && <span className="sc-ver-vs">v{prev.version} → v{sel.version}</span>}
                  <span className="sc-ver-viewtabs">
                    <button type="button" className={`btn btn-sm${view === 'diff' ? ' btn-primary' : ''}`}
                      onClick={() => setView('diff')} disabled={!prev}>{t('scripted.verTabDiff')}</button>
                    <button type="button" className={`btn btn-sm${view === 'script' ? ' btn-primary' : ''}`}
                      onClick={() => setView('script')}>{t('scripted.verTabScript')}</button>
                  </span>
                  {/* Geri yükleme geçmişi EZMEZ: yeni bir RESTORE sürümü olarak eklenir. */}
                  {canEdit && detail && !sel.current &&
                    <button className="btn btn-sm btn-primary" onClick={restore} disabled={busy}>
                      {t('tpl.versionRestore')}
                    </button>}
                </div>

                {view === 'script' || !prev
                  ? (detail
                      ? <CodeEditor value={detail.script || ''} onChange={() => {}} readOnly
                          textareaId={`k6-tplver-${sel.id}`} />
                      : <LoadingBlock label={t('modal.loading')} />)
                  : (detail && prevDetail
                      ? <VersionDiff t={t} oldDetail={prevDetail} newDetail={detail} />
                      : <LoadingBlock label={t('modal.loading')} />)}
              </div>
            )}
          </div>)}
    </ModalShell>
  )
}

/** Şablon olay sözlüğü — monitörünkinden geniş; bilinmeyen olay ham kalır (sessiz boşluk olmaz). */
function eventLabel(t, ev) {
  const key = `tpl.event${ev}`
  const label = t(key)
  return label === key ? ev : label
}

function VersionDiff({ t, oldDetail, newDetail }) {
  const d = useMemo(() => lineDiff(oldDetail?.script || '', newDetail?.script || ''), [oldDetail, newDetail])
  const env = useMemo(() => envNameDiff(oldDetail?.env, newDetail?.env), [oldDetail, newDetail])
  const shown = useMemo(() => (d.truncated ? [] : collapseContext(d.rows, 3)), [d])

  if (d.truncated) return <StatusBlock tone="neutral" title={t('scripted.verDiffTooBig', Math.max(d.oldLines, d.newLines))} />

  const envNote = (env.added.length > 0 || env.removed.length > 0) && (
    <div className="sc-diff-env">
      {env.added.length > 0 && <div>{t('scripted.verDiffEnvAdded', env.added.join(', '))}</div>}
      {env.removed.length > 0 && <div>{t('scripted.verDiffEnvRemoved', env.removed.join(', '))}</div>}
    </div>
  )

  if (d.added === 0 && d.removed === 0) {
    return (<><StatusBlock tone="neutral" title={t('scripted.verDiffNone')} />{envNote}</>)
  }

  return (<>
    <div className="sc-diff-summary">{t('scripted.verDiffSummary', d.added, d.removed)}</div>
    {envNote}
    <div className="sc-diff">
      {shown.map((r, i) => r.type === 'gap'
        ? <div key={`g${i}`} className="sc-diff-row sc-diff-row--gap">{t('scripted.verDiffGap', r.count)}</div>
        : (
          <div key={i} className={`sc-diff-row${r.type === 'add' ? ' sc-diff-row--add' : r.type === 'del' ? ' sc-diff-row--del' : ''}`}>
            <span className="sc-diff-no">{r.oldNo ?? ''}</span>
            <span className="sc-diff-no">{r.newNo ?? ''}</span>
            <span className="sc-diff-gutter">{r.type === 'add' ? '+' : r.type === 'del' ? '−' : ' '}</span>
            <span className="sc-diff-text">{r.text}</span>
          </div>
        ))}
    </div>
  </>)
}
