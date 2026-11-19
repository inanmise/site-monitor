import { useEffect, useId, useRef, useState } from 'react'
import { CircleCheck, CircleSlash, CircleX, FilePlus2, FileSpreadsheet, KeyRound, CirclePause, Pencil, TriangleAlert, Upload } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api } from '../../../api/client.js'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import PaginationBar from '../../ui/PaginationBar.jsx'
import ToneBadge from '../../admin/ToneBadge.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { usePagination } from '../../../hooks/usePagination.js'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { cn } from '@/lib/utils'
import {
  CSV_MAX_ROWS, changedText, classifyImportResponse, decodeCsvBytes, fileProblem, filterRows, pausedRows, previewCounts,
  resultCounts, rowsNeedingSecrets, secretLabel,
} from './monitorCsvModel.js'

/** Dosya baytlarını oku — `arrayBuffer` yoksa (eski ortam) `text()` (UTF-8 varsayımı). */
async function readFileText(file) {
  if (typeof file?.arrayBuffer === 'function') return decodeCsvBytes(await file.arrayBuffer())
  return { text: await file.text(), encoding: 'utf-8' }
}

/**
 * İzleme CSV içe aktarım penceresi (2026-11-19, kullanıcı isteği: "UAT'tan prod'a izleme taşıma, toplu kurulum").
 *
 * Evreler: dosya seç → ÖNİZLEME (sunucuda `dry_run=true`: satır satır oluşturulacak / güncellenecek (hangi sütunlar) /
 * değişiklik yok / hatalı — HİÇBİR şey yazılmaz) → "Uygula (N)" → SONUÇ (satır satır uygulandı / atlandı / uygulanamadı).
 *  - Uygulanacak satır yoksa (0) "Uygula" kapalı. İstek düşerse önizleme YERİNDE kalır, hata şeritte (önizleme kaybolmaz).
 *  - Gerçek koşumun yanıtı gelmezse (zaman aşımı / ağ) sonuç BİLİNMİYOR denir — "başarılı" denmez; pencere kapanınca liste
 *    yine yenilenir (bir kısmı uygulanmış olabilir).
 *  - Sırlar taşınmaz: her satır sonradan girilmesi gereken sırları sayar (Basic auth parolası, özel başlıklar, k6 gizli
 *    değişkenleri).
 * Adlar KESİLMEZ (sarar); satırlar her genişlikte kart listesidir (tablo yok — telefonda da aynı okunur).
 * Bağımsız inceleme (2026-11-19):
 *  - Dosya baytları kodlamasına göre okunur (UTF-8 / UTF-16 / Windows-1254 — `decodeCsvBytes`); Windows-1254 okunduysa
 *    önizleme bunu söyler (Türkçe harfleri denetleyin). Sunucuya hep UTF-8 gider.
 *  - "Uygula" önizlemenin `plan_token`'ını yollar; 409 `CSV_PLAN_CHANGED` → hiçbir şey yazılmadı, önizleme KENDİLİĞİNDEN
 *    yenilenir ve bir uyarı "güncel önizlemeyi inceleyip yeniden uygulayın" der.
 *  - Sırrı taşınmayan yeni izleme DURAKLATILMIŞ oluşturulur (satırda not + şeritte cümle).
 *  - Gerçek koşum iptal EDİLMEZ (pencere zaten kapanmaz; sayfa değişse de istek sürer) — yanıt sayfadan çıkıldıktan sonra
 *    gelirse sonuç bildirim (toast) olarak söylenir. Gerçek koşumda her 5xx "sonuç bilinmiyor"dur.
 *  - Uygulanan satırın uyarısı (k6 / dbquery kayıt uyarısı) satırda görünür.
 * `onClose(applied)` — `applied` true ise gerçek koşum yapıldı (ya da sonucu bilinmiyor): çağıran listeyi yeniler.
 * Test kancaları: `data-slot="mcsv-dialog|mcsv-file|mcsv-summary|mcsv-tile|mcsv-row|mcsv-apply|mcsv-close|mcsv-error"`,
 * `data-phase`, `data-action`, `data-status`.
 */
export default function MonitorCsvImportDialog({ open, type, typeLabel, onClose }) {
  const t = useT()
  const toast = useToast()
  const [phase, setPhase] = useState('pick')   // pick | preview | result
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)   // 'planChanged' — önizleme kendiliğinden yenilendi
  const [encoding, setEncoding] = useState('utf-8')
  const [fileName, setFileName] = useState(null)
  const [preview, setPreview] = useState(null)
  const [result, setResult] = useState(null)
  const [filter, setFilter] = useState('all')
  const [fileKey, setFileKey] = useState(0)
  const textRef = useRef(null)
  const planTokenRef = useRef(null)
  const seq = useRef(0)
  const abortRef = useRef(null)
  const appliedRef = useRef(false)
  const mountedRef = useRef(true)
  const inputId = useId()
  const listHeadingId = useId()

  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  // Her açılışta temiz evre (önceki dosyanın / sonucun kalıntısı yok)
  useEffect(() => {
    if (!open) return undefined
    setPhase('pick'); setBusy(false); setError(null); setNotice(null); setEncoding('utf-8'); setFileName(null)
    setPreview(null); setResult(null); setFilter('all'); textRef.current = null; planTokenRef.current = null
    appliedRef.current = false
    return () => { seq.current += 1; abortRef.current?.abort() }
  }, [open])

  const close = () => {
    if (busy) return
    abortRef.current?.abort()
    onClose?.(appliedRef.current)
  }

  const describe = (res, kind) => {
    if (kind === 'unsupported') return t('mcsv.err.oldServer')
    if (kind === 'unsure') return null
    return res?.error || t('mcsv.dlg.requestFailed')
  }

  /** Önizlemeyi uygula (yeni dosya ya da 409 sonrası yenileme). */
  function showPreview(data) {
    planTokenRef.current = typeof data?.plan_token === 'string' ? data.plan_token : null
    setPreview(data)
    setFilter('all')
    setFileKey((k) => k + 1)
    setPhase('preview')
  }

  async function pick(file) {
    setError(null)
    const problem = fileProblem(file)
    if (problem) { setError(t(`mcsv.err.${problem}`)); return }
    const mine = ++seq.current
    abortRef.current?.abort()
    const ac = new AbortController()
    abortRef.current = ac
    setBusy(true)
    try {
      let decoded
      try { decoded = await readFileText(file) } catch { if (mine === seq.current) setError(t('mcsv.err.read')); return }
      if (mine !== seq.current) return
      let res
      try {
        res = await api.monitoring.csvImport(type, decoded.text, { dryRun: true, signal: ac.signal })
      } catch (e) {
        if (mine === seq.current) setError(e?.message || t('mcsv.dlg.requestFailed'))
        return
      }
      if (mine !== seq.current) return
      const kind = classifyImportResponse(res)
      if (kind !== 'ok') { setError(describe(res, kind) || t('mcsv.dlg.requestFailed')); return }
      textRef.current = decoded.text
      setEncoding(decoded.encoding)
      setNotice(null)
      setFileName(file.name || null)
      showPreview(res.data)
    } finally {
      if (mine === seq.current) setBusy(false)
    }
  }

  /** 409 CSV_PLAN_CHANGED: hiçbir şey yazılmadı — aynı metinle önizlemeyi yeniden iste. */
  async function refreshPreview(mine) {
    const ac = new AbortController()
    abortRef.current = ac
    let res = null
    try {
      res = await api.monitoring.csvImport(type, textRef.current, { dryRun: true, signal: ac.signal })
    } catch {
      res = null
    }
    if (mine !== seq.current || !mountedRef.current) return
    if (classifyImportResponse(res) === 'ok') {
      showPreview(res.data)
      setNotice('planChanged')
    } else {
      setError(t('mcsv.dlg.planChangedNoPreview'))
    }
  }

  /** Sayfadan çıkıldıktan sonra gelen gerçek koşum yanıtı: sonuç bildirimle söylenir (sessiz kalmaz). */
  function announceAway(res, kind) {
    if (kind === 'ok') {
      const rc = resultCounts(res.data.rows)
      const msg = t('mcsv.toast.finished', rc.done, rc.skipped, rc.failed)
      if (rc.failed > 0) toast.error(msg, { duration: 15000 })
      else toast.success(msg, 10000)
      return
    }
    if (kind === 'planChanged') { toast.info(t('mcsv.toast.planChanged'), 15000); return }
    if (kind === 'unsure') { toast.info(t('mcsv.toast.unknown'), 15000); return }
    toast.error(describe(res, kind) || t('mcsv.dlg.requestFailed'), { duration: 15000 })
  }

  async function apply() {
    if (busy || !textRef.current) return
    const mine = ++seq.current
    // Gerçek koşum İPTAL EDİLMEZ: yarıda kesilen istek bir kısmı uygulanmış bir dosya bırakır (bağımsız inceleme 2026-11-19)
    abortRef.current = null
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      let res
      try {
        res = await api.monitoring.csvImport(type, textRef.current, { dryRun: false, planToken: planTokenRef.current })
      } catch (e) {
        // Ağ hatası: istek sunucuya ulaşıp uygulanmış olabilir — sonuç bilinmiyor, liste kapanışta yenilenir
        if (!mountedRef.current) { toast.info(t('mcsv.toast.unknown'), 15000); return }
        if (mine === seq.current) { appliedRef.current = true; setError(e?.message ? `${e.message} ${t('mcsv.dlg.applyUnknown')}` : t('mcsv.dlg.applyUnknown')) }
        return
      }
      const kind = classifyImportResponse(res, { applying: true })
      if (!mountedRef.current) { announceAway(res, kind); return }
      if (mine !== seq.current) return
      if (kind === 'ok') {
        appliedRef.current = true
        setResult(res.data)
        setFilter('all')
        setPhase('result')
        return
      }
      if (kind === 'planChanged') { await refreshPreview(mine); return }
      if (kind === 'unsure') { appliedRef.current = true; setError(t('mcsv.dlg.applyUnknown')); return }
      setError(describe(res, kind))
    } finally {
      if (mine === seq.current) setBusy(false)
    }
  }

  function pickAnother() {
    if (busy) return
    seq.current += 1
    setPhase('pick'); setPreview(null); setError(null); setNotice(null); setEncoding('utf-8'); setFileName(null)
    setFilter('all')
    textRef.current = null
    planTokenRef.current = null
  }

  const data = phase === 'result' ? result : preview
  const rows = data?.rows ?? []
  const pc = previewCounts(preview?.rows)
  const rc = resultCounts(result?.rows)
  const shown = filterRows(rows, filter, phase)
  const pager = usePagination(shown, { listKey: 'monitor-csv-rows', preset: 'modal', resetDeps: [filter, phase, fileKey] })
  const secretRows = rowsNeedingSecrets(rows, phase)
  const paused = pausedRows(rows, phase)

  const tiles = phase === 'result'
    ? [
        { key: 'done', n: rc.done, label: t('mcsv.status.done'), sub: t('mcsv.dlg.doneSub', rc.created, rc.updated), tone: 'success' },
        { key: 'skipped', n: rc.skipped, label: t('mcsv.status.skipped'), tone: 'muted' },
        { key: 'failed', n: rc.failed, label: t('mcsv.status.notApplied'), tone: 'danger' },
      ]
    : [
        { key: 'create', n: pc.create, label: t('mcsv.action.create'), tone: 'info' },
        { key: 'update', n: pc.update, label: t('mcsv.action.update'), tone: 'warning' },
        { key: 'unchanged', n: pc.unchanged, label: t('mcsv.action.unchanged'), tone: 'muted' },
        { key: 'error', n: pc.error, label: t('mcsv.action.invalid'), tone: 'danger' },
      ]

  const footer = phase === 'result' ? (
    <Button type="button" onClick={close} data-slot="mcsv-close">{t('mcsv.dlg.close')}</Button>
  ) : (
    <>
      <Button type="button" variant="secondary" onClick={close} disabled={busy}>{t('mcsv.dlg.cancel')}</Button>
      {phase === 'preview' && (
        <>
          <Button type="button" variant="outline" onClick={pickAnother} disabled={busy} data-slot="mcsv-pick-another">
            {t('mcsv.dlg.pickAnother')}
          </Button>
          <Button type="button" onClick={apply} disabled={busy || pc.actionable === 0} aria-busy={busy || undefined}
            data-slot="mcsv-apply">
            <Upload aria-hidden="true" />
            {busy ? t('mcsv.dlg.applying') : t('mcsv.dlg.apply', pc.actionable)}
          </Button>
        </>
      )}
    </>
  )

  return (
    <ModalShell open={open} onClose={close} busy={busy} size="lg" scrollBody dismissOnBackdrop={false}
      icon={FileSpreadsheet} title={t('mcsv.dlg.title', typeLabel || type)} footer={footer}>
      <div data-slot="mcsv-dialog" data-phase={phase} data-busy={busy || undefined} className="flex min-w-0 flex-col gap-3">
        {phase === 'pick' && (
          <>
            <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{t('mcsv.dlg.intro')}</p>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-muted-foreground">
              <li className="[overflow-wrap:anywhere]">{t('mcsv.dlg.rules.blank')}</li>
              <li className="[overflow-wrap:anywhere]">{t('mcsv.dlg.rules.secrets')}</li>
              <li className="[overflow-wrap:anywhere]">{t('mcsv.dlg.rules.limits', CSV_MAX_ROWS)}</li>
            </ul>
            <div className="flex min-w-0 flex-col gap-1.5">
              <Label htmlFor={inputId}>{t('mcsv.dlg.file')}</Label>
              <Input id={inputId} type="file" accept=".csv,text/csv,.txt,text/plain" disabled={busy} data-slot="mcsv-file"
                className="h-auto min-h-10 cursor-pointer py-2"
                onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; if (f) pick(f) }} />
            </div>
            {busy && <p role="status" className="text-sm text-muted-foreground">{t('mcsv.dlg.checking')}</p>}
          </>
        )}

        {error && (
          <AlertBanner tone="danger" role="alert" className="mb-0">
            <span data-slot="mcsv-error">{error}</span>
          </AlertBanner>
        )}

        {phase !== 'pick' && data && (
          <>
            <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">
              {phase === 'result' ? t('mcsv.dlg.resultTitle') : t('mcsv.dlg.previewTitle')}
              {fileName && <> · <span className="font-medium text-foreground">{t('mcsv.dlg.fileName', fileName)}</span></>}
            </p>
            <div role="group" aria-label={t('mcsv.dlg.filterLabel')} data-slot="mcsv-summary"
              className={cn('grid gap-2', phase === 'result' ? 'grid-cols-3' : 'grid-cols-2 sm:grid-cols-4')}>
              {tiles.map((x) => {
                const active = filter === x.key
                return (
                  <Button key={x.key} type="button" variant="outline" aria-pressed={active} data-slot="mcsv-tile" data-key={x.key}
                    onClick={() => setFilter(active ? 'all' : x.key)}
                    className={cn('h-auto min-h-14 flex-col items-start justify-start gap-0.5 whitespace-normal px-3 py-2 text-left',
                      active && 'border-primary ring-1 ring-primary')}>
                    <span className={cn('text-xl font-bold tabular-nums',
                      x.n > 0 && x.tone === 'danger' && 'text-destructive')}>{x.n}</span>
                    <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">{x.label}</span>
                    {x.sub && x.n > 0 && <span className="text-xs font-normal text-muted-foreground [overflow-wrap:anywhere]">{x.sub}</span>}
                  </Button>
                )
              })}
            </div>

            {notice === 'planChanged' && phase === 'preview' && (
              <AlertBanner tone="warning" className="mb-0">
                <span data-slot="mcsv-plan-changed">{t('mcsv.dlg.planChanged')}</span>
              </AlertBanner>
            )}
            {encoding === 'windows-1254' && phase === 'preview' && (
              <AlertBanner tone="info" className="mb-0">
                <span data-slot="mcsv-encoding">{t('mcsv.dlg.encoding1254')}</span>
              </AlertBanner>
            )}
            {phase === 'preview' && pc.error > 0 && (
              <AlertBanner tone="warning" className="mb-0">{t('mcsv.dlg.errorsNote')}</AlertBanner>
            )}
            {phase === 'preview' && pc.actionable === 0 && rows.length > 0 && (
              <AlertBanner tone="info" className="mb-0"><span data-slot="mcsv-nothing">{t('mcsv.dlg.nothing')}</span></AlertBanner>
            )}
            {phase === 'result' && data.budget_exceeded && (
              <AlertBanner tone="warning" className="mb-0">{t('mcsv.dlg.budget')}</AlertBanner>
            )}
            {phase === 'result' && data.stopped === true && (
              <AlertBanner tone="warning" className="mb-0"><span data-slot="mcsv-stopped">{t('mcsv.dlg.stopped')}</span></AlertBanner>
            )}
            {secretRows.length > 0 && (
              <AlertBanner tone="warning" icon={KeyRound} className="mb-0" title={t('mcsv.dlg.secretsTitle')}>
                <span data-slot="mcsv-secrets">
                  {secretRows.length === 1 ? t('mcsv.dlg.secretsBodyOne') : t('mcsv.dlg.secretsBody', secretRows.length)}
                  {paused.length > 0 && (
                    <> {paused.length === 1
                      ? t(phase === 'result' ? 'mcsv.dlg.pausedDoneOne' : 'mcsv.dlg.pausedOne')
                      : t(phase === 'result' ? 'mcsv.dlg.pausedDone' : 'mcsv.dlg.paused', paused.length)}</>
                  )}
                </span>
              </AlertBanner>
            )}

            <section aria-labelledby={listHeadingId} className="flex min-w-0 flex-col gap-2">
              <h4 id={listHeadingId} className="text-sm font-semibold">
                {filter === 'all' ? t('mcsv.dlg.rowsAll', shown.length) : t('mcsv.dlg.rowsFiltered', shown.length, rows.length)}
              </h4>
              {shown.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('mcsv.dlg.noRows')}</p>
              ) : (
                <ul className="flex min-w-0 list-none flex-col divide-y rounded-lg border p-0">
                  {pager.pageItems.map((r) => <RowItem key={`${phase}-${r.row}`} r={r} phase={phase} t={t} />)}
                </ul>
              )}
              <PaginationBar {...pager} />
            </section>
          </>
        )}
      </div>
    </ModalShell>
  )
}

/** Wrapping rozet: uzun metin hapta kırpılmasın (rounded-md + overflow-visible + whitespace-normal). */
const BADGE = 'h-auto max-w-full shrink-0 rounded-md overflow-visible whitespace-normal text-left'

const ACTION_TONE = { create: 'info', update: 'warning', unchanged: 'muted', error: 'danger' }
const ACTION_ICON = { create: FilePlus2, update: Pencil, unchanged: CircleSlash, error: CircleX }
const STATUS_TONE = { done: 'success', skipped: 'muted', failed: 'danger' }
const STATUS_ICON = { done: CircleCheck, skipped: CircleSlash, failed: CircleX }

function RowItem({ r, phase, t }) {
  const action = ACTION_TONE[r.action] ? r.action : 'error'
  const status = phase === 'result' ? (STATUS_TONE[r.status] ? r.status : 'failed') : null
  const tone = status ? STATUS_TONE[status] : ACTION_TONE[action]
  const Icon = status ? STATUS_ICON[status] : ACTION_ICON[action]
  const badge = status
    ? (status === 'done' ? t(action === 'create' ? 'mcsv.status.created' : 'mcsv.status.updated')
      : status === 'skipped' ? t('mcsv.status.skipped') : t('mcsv.status.notApplied'))
    : t(action === 'error' ? 'mcsv.action.invalid' : `mcsv.action.${action}`)
  const changed = Array.isArray(r.changed) ? r.changed : []
  const secrets = Array.isArray(r.secrets) ? r.secrets : []
  const showChanged = action === 'update' && changed.length > 0
  const showSecrets = secrets.length > 0 && (status ? status === 'done' : action === 'create' || action === 'update')
  return (
    <li data-slot="mcsv-row" data-action={action} data-status={status || undefined} data-row={r.row}
      className="flex min-w-0 flex-wrap items-start justify-between gap-x-3 gap-y-1 px-3 py-2">
      <span className="flex min-w-0 flex-[1_1_14rem] flex-col gap-0.5">
        <span className="text-xs text-muted-foreground tabular-nums">{t('mcsv.dlg.row', r.row)}</span>
        <span className="font-medium [overflow-wrap:anywhere]">{r.name || '—'}</span>
        <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{r.team || t('mcsv.dlg.noTeam')}</span>
        {showChanged && (
          <span className="text-xs [overflow-wrap:anywhere]" data-slot="mcsv-changed">
            {t(status ? 'mcsv.dlg.changedDone' : 'mcsv.dlg.changed', changedText(changed, (n) => t('mcsv.dlg.more', n)))}
          </span>
        )}
        {showSecrets && (
          <span className="flex items-start gap-1 text-xs text-amber-700 dark:text-amber-300 [overflow-wrap:anywhere]" data-slot="mcsv-row-secrets">
            <KeyRound aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            {t('mcsv.dlg.secretsRow', secrets.map((s) => secretLabel(t, s)).join(', '))}
          </span>
        )}
        {r.paused === true && action === 'create' && (status ? status === 'done' : true) && (
          <span className="flex items-start gap-1 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="mcsv-row-paused">
            <CirclePause aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            {t(status ? 'mcsv.dlg.rowPausedDone' : 'mcsv.dlg.rowPaused')}
          </span>
        )}
        {r.message && status === 'done' && (
          <span className="flex items-start gap-1 text-xs text-amber-700 dark:text-amber-300 [overflow-wrap:anywhere]" data-slot="mcsv-row-warning">
            <TriangleAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
            {t('mcsv.dlg.rowWarning', r.message)}
          </span>
        )}
        {r.message && (action === 'error' || status === 'failed' || status === 'skipped') && (
          <span className={cn('text-xs [overflow-wrap:anywhere]', status === 'skipped' ? 'text-muted-foreground' : 'text-destructive')}
            data-slot="mcsv-row-message">
            {r.field && !String(r.message).includes(r.field) ? `${t('mcsv.dlg.column', r.field)} ` : ''}{r.message}
          </span>
        )}
      </span>
      <ToneBadge tone={tone} className={BADGE}><Icon aria-hidden="true" />{badge}</ToneBadge>
    </li>
  )
}
