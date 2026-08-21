import { useState, useEffect, useMemo } from 'react'
import { api, formatDateSec } from '../../api/client'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import CodeEditor from '../ui/CodeEditor.jsx'
import { lineDiff, collapseContext, envNameDiff } from '../../utils/lineDiff.js'

/**
 * Sentetik izleme — "Sürümler" sekmesi.
 *
 * <p>ScriptedMonitorPage.jsx'ten ÇIKARILDI (2026-08-20): o dosya ~1600 satır ve dosya-bazlı
 * kapsam tabanı kuralına tabi (≥500 satır → ≥%40 satır kapsamı); sürüm sekmesine eklenen üç
 * yeni yetenek (ad-soyad+fotoğraf, diff, koşum rozeti) hep aynı bileşene yığılıyordu.
 * Ayrı dosya hem doğrudan test edilebilir hem ana dosyayı büyütmez.
 *
 * <p><b>Neden diff var:</b> 2026-08-20'de bir monitör, kaydedilen yeni sürümdeki tanımsız bir
 * sabit yüzünden 25 dakika kör kaldı. "Hangi düzenleme bozdu?" sorusu o an iki sürümü elle
 * karşılaştırmayı gerektiriyordu; artık varsayılan görünüm doğrudan farkı gösteriyor.
 */
export default function ScriptedVersionsTab({ t, monitor, canEdit, onLoadIntoEditor }) {
  const [rows, setRows] = useState(null)      // null = yükleniyor
  const [sel, setSel] = useState(null)        // seçili sürüm (liste satırı)
  const [bodies, setBodies] = useState({})    // versionId -> gövde (script + env). Memoize: seçim ileri-geri gezinmesi bedava.
  const [view, setView] = useState('diff')    // 'diff' | 'script'

  useEffect(() => {
    let alive = true
    api.monitoring.getScriptedVersions?.(monitor.id).then(r => {
      if (alive) setRows(r?.success ? (r.data?.versions || []) : [])
    })
    return () => { alive = false }
  }, [monitor.id])

  // Seçili sürüm VE bir öncekinin gövdesi gerekiyor (diff için). İkisi de yalnız bir kez çekilir.
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
      api.monitoring.getScriptedVersion?.(monitor.id, id).then(r => {
        if (alive && r?.success) setBodies(b => ({ ...b, [id]: r.data }))
      })
    })
    return () => { alive = false }
  }, [monitor.id, sel, prev, bodies])

  if (rows === null) return <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
  if (rows.length === 0) {
    return <StatusBlock tone="neutral" title={t('scripted.versionNone')} description={t('scripted.versionEmptyHint')} />
  }

  const eventLabel = (ev) => {
    if (ev === 'CREATE') return t('scripted.versionEventCREATE')
    if (ev === 'RESTORE') return t('scripted.versionEventRESTORE')
    return t('scripted.versionEventEDIT')
  }

  const detail = sel ? bodies[sel.id] : null
  const prevDetail = prev ? bodies[prev.id] : null

  return (
    <div className="sc-versions">
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
            <tr key={v.id} className={`uact-row-click${sel?.id === v.id ? ' is-sel' : ''}`} style={{ cursor: 'pointer' }}
                onClick={() => { setSel(sel?.id === v.id ? null : v); setView('diff') }}>
              <td>
                <span className="sc-ver-chip">v{v.version}</span>
                {v.current && <span className="sc-ver-current">{t('scripted.versionCurrent')}</span>}
                <VersionRuns t={t} version={v} />
              </td>
              <td className="sys-mono sys-small">{formatDateSec(v.created_at)}</td>
              {/* Sicil DEĞİL ad-soyad + AD fotoğrafı: UserBadge uygulamanın diğer tüm
                  "kim yaptı" yüzeylerinde (denetim kaydı, alarm geçmişi) zaten standart. */}
              <td className="sys-small"><UserBadge username={v.created_by} size="sm" inline nameOnly /></td>
              <td className="sys-small">{eventLabel(v.event_type)}</td>
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
            {canEdit && detail && (
              <button className="btn btn-sm btn-primary" onClick={() => onLoadIntoEditor(sel, detail)}>
                {t('scripted.versionLoad')}
              </button>
            )}
          </div>

          {view === 'script' || !prev
            ? (detail
                ? <CodeEditor value={detail.script || ''} onChange={() => {}} readOnly textareaId={`k6-version-${sel.id}`} />
                : <LoadingBlock label={t('modal.loading')} />)
            : (detail && prevDetail
                ? <VersionDiff t={t} oldDetail={prevDetail} newDetail={detail} />
                : <LoadingBlock label={t('modal.loading')} />)}

          {!prev && view === 'diff' && (
            <StatusBlock tone="neutral" title={t('scripted.verDiffFirst')} />
          )}
        </div>
      )}
    </div>
  )
}

/** Sürüm satırındaki koşum sonucu rozeti — "bu sürüm sahada ne yaptı?". */
function VersionRuns({ t, version }) {
  const runs = Number(version.run_count || 0)
  if (!runs) return null
  const fails = Number(version.fail_count || 0)
  return (
    <span className={`sc-ver-runs${fails > 0 ? ' sc-ver-runs--bad' : ''}`} title={t('scripted.verRunsHint')}>
      {fails > 0 ? t('scripted.verRunsBad', runs, fails) : t('scripted.verRunsOk', runs)}
    </span>
  )
}

function VersionDiff({ t, oldDetail, newDetail }) {
  const d = useMemo(() => lineDiff(oldDetail?.script || '', newDetail?.script || ''),
    [oldDetail, newDetail])
  const env = useMemo(() => envNameDiff(oldDetail?.env, newDetail?.env), [oldDetail, newDetail])
  const shown = useMemo(() => (d.truncated ? [] : collapseContext(d.rows, 3)), [d])

  if (d.truncated) {
    return <StatusBlock tone="neutral" title={t('scripted.verDiffTooBig', Math.max(d.oldLines, d.newLines))} />
  }

  const envNote = (env.added.length > 0 || env.removed.length > 0) && (
    <div className="sc-diff-env">
      {env.added.length > 0 && <div>{t('scripted.verDiffEnvAdded', env.added.join(', '))}</div>}
      {env.removed.length > 0 && <div>{t('scripted.verDiffEnvRemoved', env.removed.join(', '))}</div>}
    </div>
  )

  if (d.added === 0 && d.removed === 0) {
    return (<>
      <StatusBlock tone="neutral" title={t('scripted.verDiffNone')} />
      {envNote}
    </>)
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
