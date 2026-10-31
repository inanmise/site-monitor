import { useState, useEffect, useMemo } from 'react'
import { api } from '../../api/client'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import CodeEditor from '../ui/CodeEditor.jsx'
import VersionTimeline from './VersionTimeline.jsx'
import { lineDiff, collapseContext, envNameDiff } from '../../utils/lineDiff.js'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { VersionChip } from './VersionTimeline.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

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
  // Okuma hatası (2026-10-22, UIM-45): `success:false` "Henüz sürüm yok", ağ hatası sonsuz bekleme gösteriyordu
  const [listError, setListError] = useState(null)
  const [listTry, setListTry] = useState(0)

  useEffect(() => {
    let alive = true
    setRows(null); setListError(null)
    Promise.resolve(api.monitoring.getScriptedVersions?.(monitor.id)).then(r => {
      if (!alive) return
      if (r?.success) setRows(r.data?.versions || [])
      else setListError(r?.error || t('scripted.versionsLoadFailed'))
    }).catch(e => { if (alive) setListError(e?.message || t('scripted.versionsLoadFailed')) })
    return () => { alive = false }
  }, [monitor.id, listTry]) // eslint-disable-line react-hooks/exhaustive-deps

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
      // Gövde okunamazsa işaret düşülür (yeniden istek döngüsü olmaz) — önizleme "yükleniyor"da asılı kalmaz
      Promise.resolve(api.monitoring.getScriptedVersion?.(monitor.id, id)).then(r => {
        if (alive) setBodies(b => ({ ...b, [id]: r?.success ? r.data : { __error: true } }))
      }).catch(() => { if (alive) setBodies(b => ({ ...b, [id]: { __error: true } })) })
    })
    return () => { alive = false }
  }, [monitor.id, sel, prev, bodies])

  if (rows === null && listError) {
    return (
      <div data-slot="sc-versions-error">
        <StatusBlock tone="danger" title={t('scripted.versionsLoadFailed')} description={String(listError)}
          actions={<Button size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => setListTry(n => n + 1)}>{t('hist.retry')}</Button>} />
      </div>
    )
  }
  if (rows === null) return <LoadingBlock label={t('modal.loading')} className="upt-modal-loading" />
  if (rows.length === 0) {
    return <StatusBlock tone="neutral" title={t('scripted.versionNone')} description={t('scripted.versionEmptyHint')} />
  }

  const eventLabel = (ev) => {
    if (ev === 'CREATE') return t('scripted.versionEventCREATE')
    if (ev === 'RESTORE') return t('scripted.versionEventRESTORE')
    return t('scripted.versionEventEDIT')
  }

  const failedBody = (b) => b?.__error === true
  const detailRaw = sel ? bodies[sel.id] : null
  const prevRaw = prev ? bodies[prev.id] : null
  const bodyFailed = failedBody(detailRaw) || failedBody(prevRaw)
  const detail = failedBody(detailRaw) ? null : detailRaw
  const prevDetail = failedBody(prevRaw) ? null : prevRaw
  const retryBodies = () => setBodies(b => {
    const next = { ...b }
    for (const id of [sel?.id, prev?.id]) if (id != null && failedBody(next[id])) delete next[id]
    return next
  })

  return (
    <div className="sc-versions">
      {/* Şablon modalıyla AYNI zaman çizelgesi — ikisi de aynı veriyi gösteriyor, ikiye ayrılmış
          bir tasarım "aynı şey mi, farklı şey mi?" sorusu üretirdi. */}
      <VersionTimeline
        rows={rows} selId={sel?.id ?? null}
        currentLabel={t('scripted.versionCurrent')}
        eventLabel={eventLabel}
        renderExtra={v => <VersionRuns t={t} version={v} />}
        onPick={v => { setSel(sel?.id === v.id ? null : v); setView('diff') }} />

      {sel && (
        <div className="sc-ver-preview">
          <div className="sc-ver-preview-head">
            <span className="sc-ver-preview-id">
              <VersionChip>v{sel.version}</VersionChip>
              {prev && <span className="sc-ver-vs">v{prev.version} → v{sel.version}</span>}
            </span>
            <span className="sc-ver-preview-tools">
              <SegmentedControl ariaLabel={t('scripted.verViewLabel')} value={view} onChange={setView}
                options={[
                  { value: 'diff', label: t('scripted.verTabDiff'), disabled: !prev },
                  { value: 'script', label: t('scripted.verTabScript') },
                ]} />
              {canEdit && detail && (
                <Button size="sm" onClick={() => onLoadIntoEditor(sel, detail)}>
                  {t('scripted.versionLoad')}
                </Button>
              )}
            </span>
          </div>

          <div className="sc-ver-preview-body">
            {bodyFailed && (
              <div data-slot="sc-version-body-error" className="mb-2 flex flex-wrap items-center gap-2 text-sm text-destructive">
                <span>{t('scripted.versionBodyLoadFailed')}</span>
                <Button size="sm" variant="outline" className="pointer-coarse:h-10" onClick={retryBodies}>{t('hist.retry')}</Button>
              </div>
            )}
            {bodyFailed ? null : view === 'script' || !prev
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
    <SimpleTooltip content={t('scripted.verRunsHint')}>
      {/* Sarılır (E6, 2026-10-31): telefonda tek satır rozet sağdaki aç / kapa okunun üstüne taşıyordu */}
      <Badge variant="secondary" data-slot="version-runs" data-bad={fails > 0 ? 'true' : undefined}
        className={cn('h-auto max-w-full overflow-visible rounded-md text-left whitespace-normal [overflow-wrap:anywhere]', fails > 0
          ? 'bg-destructive/10 text-destructive dark:bg-destructive/20'
          : 'bg-success/15 text-success dark:bg-success/20')}>
        {fails > 0 ? t('scripted.verRunsBad', runs, fails) : t('scripted.verRunsOk', runs)}
      </Badge>
    </SimpleTooltip>
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
    // Ortam değişkeni farkı notu (E6 / O8, 2026-10-31): sol renk şeridi YOK (kalıcı kural), uzun değişken adları SARAR.
    <div data-slot="version-diff-env" className="mt-1 mb-2 rounded-md border border-amber-500/30 bg-amber-500/10 px-2.5 py-1.5 text-[.8em] text-foreground [overflow-wrap:anywhere]">
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
