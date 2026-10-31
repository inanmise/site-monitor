import { useEffect, useMemo, useState } from 'react'
import { History } from 'lucide-react'
import { api } from '../../api/client'
import ModalShell from '../ui/ModalShell.jsx'
import CodeEditor from '../ui/CodeEditor.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import VersionTimeline from './VersionTimeline.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { lineDiff, collapseContext, envNameDiff } from '../../utils/lineDiff.js'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import { VersionChip } from './VersionTimeline.jsx'
import { Button } from '@/components/shadcn/button'
import { useToast } from '../ui/Toast.jsx'

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
  const toast = useToast()
  const [rows, setRows] = useState(null)
  const [sel, setSel] = useState(null)
  const [bodies, setBodies] = useState({})
  const [view, setView] = useState('diff')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  // Okuma hatası (O2, 2026-10-31): eskiden `success:false` / ağ hatası "Henüz sürüm yok" gösteriyordu — izleme sürüm
  // sekmesinin (UIM-45) deseni: hata + Yeniden dene; gövde okunamazsa sonsuz "yükleniyor" yerine hata + Yeniden dene.
  const [listError, setListError] = useState(null)
  const [listTry, setListTry] = useState(0)

  useEffect(() => {
    let alive = true
    setRows(null); setListError(null)
    Promise.resolve(api.monitoring.getScriptedTemplateVersions(template.id)).then(r => {
      if (!alive) return
      if (r?.success) setRows(r.data?.versions || [])
      else setListError(r?.error || t('tpl.versionsLoadFailed'))
    }).catch(e => { if (alive) setListError(e?.message || t('tpl.versionsLoadFailed')) })
    return () => { alive = false }
  }, [template.id, listTry]) // eslint-disable-line react-hooks/exhaustive-deps

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
      // Gövde okunamazsa işaret düşülür (yeniden istek döngüsü olmaz) — önizleme "yükleniyor"da asılı kalmaz
      Promise.resolve(api.monitoring.getScriptedTemplateVersion(template.id, id)).then(r => {
        if (alive) setBodies(b => ({ ...b, [id]: r?.success ? r.data : { __error: true } }))
      }).catch(() => { if (alive) setBodies(b => ({ ...b, [id]: { __error: true } })) })
    })
    return () => { alive = false }
  }, [template.id, sel, prev, bodies])

  async function restore() {
    const body = bodies[sel.id]
    if (!body || body.__error) return
    setBusy(true); setError(null)
    try {
      const res = await api.monitoring.updateScriptedTemplate(template.id, {
        script: body.script,
        env: (body.env || []).map(e => ({ name: e.name, secret: !!e.secret, desc: e.desc || null })),
        restoredFrom: `v${sel.version}`,
      })
      if (!res?.success) { setError(res?.error || t('tpl.restoreError')); return }
      onRestored?.(res.data, sel.version)
    } catch (e) {
      // request() ağ hatasında THROW eder: kullanıcı sessiz kalan bir düğme görmesin (O2)
      const msg = e?.message || t('tpl.restoreError')
      setError(msg); toast.error(msg)
    } finally {
      setBusy(false)
    }
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
    // scrollBody: geçmiş uzadıkça (seed + her düzenleme + kapsam olayları) liste ekranı aşıyor;
    // başlık ve kapatma sabit kalsın, yalnız içerik kaysın.
    <ModalShell open onClose={onClose} title={t('tpl.versionsTitle', template.name)} icon={History}
      size="lg" busy={busy} scrollBody>
      {error && <StatusBlock tone="danger" title={error} />}
      {rows === null && listError
        ? (
          <div data-slot="tpl-versions-error">
            <StatusBlock tone="danger" title={t('tpl.versionsLoadFailed')} description={String(listError)}
              actions={<Button size="sm" variant="outline" className="pointer-coarse:h-10" onClick={() => setListTry(n => n + 1)}>{t('hist.retry')}</Button>} />
          </div>
        )
        : rows === null
        ? <LoadingBlock label={t('modal.loading')} />
        : rows.length === 0
          ? <StatusBlock tone="neutral" title={t('tpl.versionNone')} />
          : (<div className="sc-versions">
            <VersionTimeline
              rows={rows} selId={sel?.id ?? null}
              currentLabel={t('scripted.versionCurrent')}
              eventLabel={ev => eventLabel(t, ev)}
              onPick={v => { setSel(sel?.id === v.id ? null : v); setView('diff') }} />

            {sel && (
              <div className="sc-ver-preview">
                <div className="sc-ver-preview-head">
                  <span className="sc-ver-preview-id">
                    <VersionChip>v{sel.version}</VersionChip>
                    {prev && <span className="sc-ver-vs">v{prev.version} → v{sel.version}</span>}
                  </span>
                  <span className="sc-ver-preview-tools">
                    {/* Uygulamanın standart segment denetimi (ui/SegmentedControl) — eskiden iki ayrı
                        btn/btn-primary yan yanaydı ve "seçili" hâli bir eylem düğmesinden
                        ayırt edilemiyordu. */}
                    <SegmentedControl ariaLabel={t('scripted.verViewLabel')} value={view} onChange={setView}
                      options={[
                        { value: 'diff', label: t('scripted.verTabDiff'), disabled: !prev },
                        { value: 'script', label: t('scripted.verTabScript') },
                      ]} />
                    {/* Geri yükleme geçmişi EZMEZ: yeni bir RESTORE sürümü olarak eklenir. */}
                    {canEdit && detail && !sel.current &&
                      <Button size="sm" onClick={restore} disabled={busy}>
                        {t('tpl.versionRestore')}
                      </Button>}
                  </span>
                </div>

                <div className="sc-ver-preview-body">
                  {bodyFailed && (
                    <div data-slot="tpl-version-body-error" className="mb-2 flex flex-wrap items-center gap-2 text-sm text-destructive">
                      <span>{t('scripted.versionBodyLoadFailed')}</span>
                      <Button size="sm" variant="outline" className="pointer-coarse:h-10" onClick={retryBodies}>{t('hist.retry')}</Button>
                    </div>
                  )}
                  {bodyFailed ? null : view === 'script' || !prev
                    ? (detail
                        ? <CodeEditor value={detail.script || ''} onChange={() => {}} readOnly
                            textareaId={`k6-tplver-${sel.id}`} />
                        : <LoadingBlock label={t('modal.loading')} />)
                    : (detail && prevDetail
                        ? <VersionDiff t={t} oldDetail={prevDetail} newDetail={detail} />
                        : <LoadingBlock label={t('modal.loading')} />)}
                </div>
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
    // Ortam değişkeni farkı notu (E6 / O8, 2026-10-31): sol renk şeridi YOK (kalıcı kural), uzun değişken adları SARAR.
    <div data-slot="version-diff-env" className="mt-1 mb-2 rounded-md border border-amber-500/30 bg-amber-500/10 px-2.5 py-1.5 text-[.8em] text-foreground [overflow-wrap:anywhere]">
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
