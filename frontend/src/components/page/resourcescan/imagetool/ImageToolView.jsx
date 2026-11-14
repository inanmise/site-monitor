import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, ChevronDown, Columns2, Download, FileArchive, ImagePlus, Images, MonitorSmartphone, RefreshCw, RotateCw, ShieldCheck,
  SlidersHorizontal, Trash2, TriangleAlert, Upload, X,
} from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { useElementWidth } from '../../../../hooks/useElementWidth.js'
import { clientFailure } from '../../../../utils/errorMessages.js'
import { useToast } from '../../../ui/Toast.jsx'
import { useDialog } from '../../../ui/Dialog.jsx'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import { ProgressBar, Spinner } from '../../../ui/Progress.jsx'
import ToneBadge from '../../../admin/ToneBadge.jsx'
import { formatNumber, formatPct, formatSize, plural } from '../resourceScanModel.js'
import { CHIP, Section, SmallBadge, TOUCH, WRAP } from '../ResourceScanParts.jsx'
import { ResultLine, TlsUnverifiedNote } from './ImageActions.jsx'
import ImageSettingsFields from './ImageSettingsFields.jsx'
import { useImageTool, useTopic } from './ImageToolContext.jsx'
import {
  TOOL_MAX_FILES, dimsText, metadataAvailable, pctText, phaseText, problemLine, settingsSummary, typeLabel, validateSettings, zipFileName,
} from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'

/** Ayar paneli bu kap genişliğinden itibaren kuyruğun YANINDA (altında katlanır). jsdom 0 → yan yana. */
export const TOOL_WIDE_MIN_PX = 900
const ACCEPT = 'image/png,image/jpeg,image/webp,image/gif,image/bmp,image/avif'

/** Sürükleme / yapıştırma verisindeki dosyalar (yalnız dosya; metin yapıştırma yok sayılır). */
function filesOf(dt) {
  const out = []
  const list = dt?.files
  if (list && list.length) { for (let i = 0; i < list.length && i < 200; i += 1) out.push(list[i]); return out }
  const items = dt?.items
  if (items && items.length) {
    for (let i = 0; i < items.length && i < 200; i += 1) {
      const f = items[i]?.kind === 'file' ? items[i].getAsFile?.() : null
      if (f) out.push(f)
    }
  }
  return out
}

/**
 * Kaynak analizi → GÖRSEL ARACI (2026-11-14, kullanıcı kararı: yüklenen görseller de optimize edilebilsin). Sürükle-bırak /
 * dosya seç / yapıştır ile en çok 30 görsel; ayar paneli (çıktı biçimi, kalite: otomatik / elle / hedef boyut, yeniden
 * boyutlandırma yöntemi, saydamlık için arka plan rengi, üst veri anahtarları); dosya başına durum (sırada / işleniyor +
 * aşama / hazır / başarısız + neden), önce → sonra boyut, ölçü, biçim, kazanç ve motorun notları; tek tek ya da toplu (ZIP)
 * indirme, kaldır, yeniden dene, temizle. Taramadan "Görsel aracında aç" ile gönderilen görseller de aynı kuyruğa düşer.
 *
 * Her şey TARAYICIDA işlenir — yüklenen dosya sunucuya gönderilmez (dürüstçe söylenir; taramadan gelen görseli sunucu
 * izlenen siteden alır). Kuyruk ve ayarlar depoda yaşar: bölüm değiştirilince kaybolmaz, sekme kapanınca bırakılır.
 * Görünüm, sekmeyi gören HERKESE açıktır (yükleme için ek yetki yok).
 */
export default function ImageToolView({ onBack }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const ctx = useImageTool()
  const store = ctx.store
  const queue = useTopic(store, 'queue', store.queue)
  const settings = useTopic(store, 'settings', store.settings)
  const support = useTopic(store, 'support', store.support)
  const zip = useTopic(store, 'zip', store.zip)
  const [ref, width] = useElementWidth()
  const [dragging, setDragging] = useState(false)
  const [notice, setNotice] = useState(null)          // { rejected } — sınır nedeniyle eklenmeyen dosya sayısı
  const [touched, setTouched] = useState(() => new Set())
  const [showAll, setShowAll] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const inputRef = useRef(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => { store.ensureSupport() }, [store])

  const add = (files) => {
    const list = [...(files || [])]
    if (!list.length) return
    const res = store.addFiles(list)
    setNotice(res.rejected > 0 ? { rejected: res.rejected } : null)
  }

  // Yapıştırma (Ctrl+V): görünüm açıkken belgedeki GÖRSEL yapıştırmaları kuyruğa düşer; metin yapıştırma (ayar alanına
  // sayı) olağan davranışında kalır — yalnız dosya taşıyan yapıştırma ele alınır.
  useEffect(() => {
    const onPaste = (e) => {
      const files = filesOf(e.clipboardData)
      if (!files.length) return
      e.preventDefault()
      const res = store.addFiles(files)
      if (alive.current) setNotice(res.rejected > 0 ? { rejected: res.rejected } : null)
    }
    document.addEventListener('paste', onPaste)
    return () => document.removeEventListener('paste', onPaste)
  }, [store])

  const allErrors = useMemo(() => validateSettings(settings, support.limits), [settings, support.limits])
  const waitingBlocked = queue.filter((e) => e.state === 'waiting' && !e.own).length
  // Hata, alana dokunulduktan ya da bekleyen dosya ayar yüzünden işlenemediğinde gösterilir (ilk açılışta kırmızı alan yok)
  const errors = useMemo(() => {
    if (!allErrors) return null
    if (showAll || waitingBlocked > 0) return allErrors
    const out = {}
    for (const k of Object.keys(allErrors)) if (touched.has(k)) out[k] = allErrors[k]
    return Object.keys(out).length ? out : null
  }, [allErrors, touched, showAll, waitingBlocked])
  const change = (patch) => {
    store.setSettings(patch)
    setTouched((prev) => {
      const next = new Set(prev)
      for (const k of Object.keys(patch)) next.add(k)
      if ('method' in patch) { next.delete('width'); next.delete('height') }
      if ('qualityMode' in patch) { next.delete('targetKb'); next.delete('quality') }
      return next
    })
  }

  const done = queue.filter((e) => e.state === 'done' && e.result)
  const stale = queue.filter((e) => store.isStale(e)).length
  const failed = queue.filter((e) => e.state === 'failed' && e.problem?.code !== 'tooLargeFile').length
  const active = queue.some((e) => e.state === 'processing' || e.state === 'waiting')
  const before = done.reduce((a, e) => a + (e.result.original?.bytes ?? 0), 0)
  const after = done.reduce((a, e) => a + (e.result.bytes ?? 0), 0)
  const savedPct = before > 0 && after < before ? ((before - after) / before) * 100 : 0
  // Üst veri anahtarları: kuyruktaki İŞLENMİŞ görsellerde hangi türler var (hiç işlenmediyse bilinmiyor). Depo da
  // seçenekleri AYNI kuraldan kurar (metadataAvailable): ekranda kapalı / devre dışı görünen "koru" etkisizdir
  const metadata = useMemo(() => (queue.some((e) => e.result) ? metadataAvailable(queue) : null), [queue])
  const wide = width === 0 || width >= TOOL_WIDE_MIN_PX
  const full = queue.length >= TOOL_MAX_FILES

  async function downloadZip() {
    try {
      const res = await store.zipQueue({ t, name: zipFileName(ctx.monitorName || t('prs.it.zip.toolBase'), t('prs.it.zip.suffix')) })
      if (!alive.current || res.aborted || res.busy) return
      if (res.empty) toast.info(t('prs.it.tool.zipEmpty'))
      else if (res.downloadFailed) toast.error(t('prs.it.srv.download'))
      else toast.success(plural(t, 'prs.it.tool.zipDone', res.files, formatNumber(res.files, locale)))
    } catch (e) {
      if (!alive.current) return
      const f = clientFailure(e, { fallback: t('prs.it.zip.unexpected'), chunk: t('prs.export.pageUpdated') })
      toast.error(f.message, { details: f.details })
    }
  }
  /** Tek dosyayı indirir; tarayıcı indirmeyi başlatamadıysa söylenir (başarı sayılmaz). */
  function downloadOne(entry) {
    if (!store.downloadEntry(entry.id)) toast.error(t('prs.it.srv.download'))
  }
  async function clearAll() {
    if (done.length > 0 || active) {
      const ok = await showConfirm({
        title: t('prs.it.tool.clearTitle'), message: t('prs.it.tool.clearBody'), confirmText: t('prs.it.tool.clearConfirm'),
        cancelText: t('app.dismiss'), variant: 'danger',
      })
      if (!ok) return
    }
    store.clearQueue()
    setNotice(null)
  }

  const panel = (
    <ImageSettingsFields value={settings} onChange={change} errors={errors} support={support} limits={support.limits} metadata={metadata} />
  )

  return (
    <div ref={ref} data-slot="prs-it-view" data-layout={wide ? 'wide' : 'narrow'} className="flex min-w-0 flex-col gap-3">
      <Section slot="prs-it-tool" icon={Images} title={t('prs.it.tool.title')} titleFocusable description={t('prs.it.tool.desc')}
        actions={onBack && (
          <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="prs-it-back" onClick={onBack}>
            <ArrowLeft aria-hidden="true" />{t('prs.it.tool.back')}
          </Button>
        )}>
        <p data-slot="prs-it-privacy" className={cn('m-0 flex items-start gap-2 rounded-lg bg-muted/40 px-3 py-2 text-xs text-muted-foreground', WRAP)}>
          <ShieldCheck aria-hidden="true" className="mt-px size-4 shrink-0" />
          <span className={WRAP}>{t('prs.it.tool.privacy')} {t('prs.it.tool.privacyScan')}</span>
        </p>

        <div className={cn('grid min-w-0 gap-3', wide && 'grid-cols-[minmax(0,1fr)_minmax(0,21rem)] items-start')}>
          <div className="flex min-w-0 flex-col gap-3">
            {/* Bırakma alanı: sürükle-bırak; dosya seçimi gerçek bir düğme (klavye), yapıştırma belge düzeyinde */}
            <div data-slot="prs-it-drop" data-dragging={dragging ? 'true' : undefined} data-full={full ? 'true' : undefined}
              onDragOver={(e) => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'; if (!dragging) setDragging(true) }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => { e.preventDefault(); setDragging(false); add(filesOf(e.dataTransfer)) }}
              className={cn('flex min-w-0 flex-col items-center gap-2 rounded-xl border border-dashed px-4 py-6 text-center transition-colors',
                dragging ? 'border-primary bg-primary/5' : 'bg-muted/20')}>
              <span className="flex size-11 items-center justify-center rounded-full bg-primary/10 text-primary"><ImagePlus aria-hidden="true" className="size-5" /></span>
              <p className={cn('m-0 text-sm font-medium text-foreground', WRAP)}>{t('prs.it.tool.drop')}</p>
              <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
                {t('prs.it.tool.limits', formatNumber(TOOL_MAX_FILES, locale), formatSize(support.limits?.maxInputBytes, { locale }))}
              </p>
              <Input ref={inputRef} type="file" accept={ACCEPT} multiple hidden tabIndex={-1} aria-hidden="true" data-slot="prs-it-file-input"
                onChange={(e) => { add(e.target.files); e.target.value = '' }} />
              <Button type="button" className={TOUCH} disabled={full} data-slot="prs-it-pick" onClick={() => inputRef.current?.click()}>
                <Upload aria-hidden="true" />{t('prs.it.tool.pick')}
              </Button>
              {full && <p data-slot="prs-it-full" className="m-0 text-xs text-muted-foreground">{t('prs.it.tool.full', formatNumber(TOOL_MAX_FILES, locale))}</p>}
            </div>

            {notice?.rejected > 0 && (
              <div data-slot="prs-it-rejected">
                <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert} onDismiss={() => setNotice(null)} dismissLabel={t('app.dismiss')}>
                  {plural(t, 'prs.it.tool.rejected', notice.rejected, formatNumber(TOOL_MAX_FILES, locale), formatNumber(notice.rejected, locale))}
                </AlertBanner>
              </div>
            )}

            {!wide && (
              <Collapsible open={settingsOpen} onOpenChange={setSettingsOpen} data-slot="prs-it-settings-collapsible">
                <CollapsibleTrigger asChild>
                  <Button type="button" variant="outline" aria-expanded={settingsOpen} data-slot="prs-it-settings-toggle"
                    className={cn('group h-auto min-h-9 w-full justify-between gap-2 py-1.5 whitespace-normal', TOUCH)}>
                    <span className="flex min-w-0 items-center gap-1.5 text-left">
                      <SlidersHorizontal aria-hidden="true" />
                      <span className={WRAP}>{t('prs.it.set.title')} — {t(`prs.it.set.format.${settings.format}`)} · {settingsSummary(settings, t, locale)}</span>
                    </span>
                    <ChevronDown aria-hidden="true" className="shrink-0 transition-transform group-data-[state=open]:rotate-180" />
                  </Button>
                </CollapsibleTrigger>
                <CollapsibleContent className="pt-3">{panel}</CollapsibleContent>
              </Collapsible>
            )}

            {waitingBlocked > 0 && allErrors && (
              <div data-slot="prs-it-blocked">
                <AlertBanner tone="warning" className="mb-0" icon={TriangleAlert}
                  actions={!wide && !settingsOpen ? (
                    <Button type="button" variant="outline" size="sm" className={TOUCH} onClick={() => { setSettingsOpen(true); setShowAll(true) }}>{t('prs.it.tool.openSettings')}</Button>
                  ) : undefined}>
                  {plural(t, 'prs.it.tool.blocked', waitingBlocked, formatNumber(waitingBlocked, locale))}
                </AlertBanner>
              </div>
            )}

            <div data-slot="prs-it-queue" data-count={queue.length} className="flex min-w-0 flex-col gap-2">
              <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
                <p data-slot="prs-it-summary" role="status" aria-live="polite" className={cn('m-0 text-xs text-muted-foreground tabular-nums', WRAP)}>
                  {queue.length === 0 ? t('prs.it.tool.empty')
                    : done.length > 0
                      ? (savedPct > 0
                        ? plural(t, 'prs.it.tool.summaryDone', queue.length, formatNumber(queue.length, locale), formatNumber(done.length, locale),
                          formatSize(before, { locale }), formatSize(after, { locale }), pctText(savedPct, locale))
                        : plural(t, 'prs.it.tool.summaryDoneNoSaving', queue.length, formatNumber(queue.length, locale), formatNumber(done.length, locale),
                          formatSize(before, { locale }), formatSize(after, { locale })))
                      : plural(t, 'prs.it.tool.summary', queue.length, formatNumber(queue.length, locale))}
                </p>
                {queue.length > 0 && (
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    {(stale > 0 || failed > 0) && (
                      <Button type="button" variant="outline" size="sm" className={TOUCH} data-slot="prs-it-reprocess" disabled={Boolean(allErrors)}
                        onClick={() => { setShowAll(true); store.reprocess() }}>
                        <RefreshCw aria-hidden="true" />{t('prs.it.tool.reprocess', formatNumber(stale + failed, locale))}
                      </Button>
                    )}
                    <Button type="button" size="sm" className={TOUCH} data-slot="prs-it-zip" disabled={done.length === 0 || Boolean(zip?.running)}
                      aria-busy={zip?.running || undefined} onClick={downloadZip}>
                      {zip?.running ? <Spinner size={14} inline decorative /> : <FileArchive aria-hidden="true" />}
                      {zip?.running ? t('prs.it.tool.zipRunning', formatNumber(zip.done, locale), formatNumber(zip.total, locale)) : t('prs.it.tool.zip')}
                    </Button>
                    <Button type="button" variant="ghost" size="sm" className={TOUCH} data-slot="prs-it-clear" onClick={clearAll}>
                      <Trash2 aria-hidden="true" />{t('prs.it.tool.clear')}
                    </Button>
                  </div>
                )}
              </div>
              {queue.length > 0 && (
                <ul className="m-0 flex list-none flex-col gap-2 p-0">
                  {queue.map((e) => <QueueRow key={e.id} entry={e} store={store} ctx={ctx} stale={store.isStale(e)} blocked={e.state === 'waiting' && !e.own && Boolean(allErrors)}
                    panelFormat={settings.format} onDownload={downloadOne} t={t} locale={locale} />)}
                </ul>
              )}
            </div>
          </div>

          {wide && (
            <aside data-slot="prs-it-settings-panel" className="flex min-w-0 flex-col gap-2 rounded-xl border bg-card px-4 py-3">
              <h4 className="m-0 flex items-center gap-2 text-sm font-semibold"><SlidersHorizontal aria-hidden="true" className="size-4 text-muted-foreground" />{t('prs.it.set.title')}</h4>
              {panel}
            </aside>
          )}
        </div>
      </Section>
    </div>
  )
}

/** Kuyruk satırı — ilerleme (aşama / yüzde) yalnız bu satırı yeniden çizer (`qp:<id>` konusu). */
function QueueRow({ entry, store, ctx, stale, blocked, panelFormat, onDownload, t, locale }) {
  const prog = useTopic(store, `qp:${entry.id}`, () => store.entryProgress(entry.id))
  const info = entry.info || (entry.result ? entry.result.original : null)
  const processing = entry.state === 'processing'
  const pct = prog.fraction != null && Number.isFinite(prog.fraction) ? Math.round(Math.max(0, Math.min(1, prog.fraction)) * 100) : null
  const natural = info?.width ?? null
  const blobs = () => store.entryBlobs(entry.id)
  const stateTone = { waiting: 'muted', processing: 'info', done: stale ? 'warning' : 'success', failed: 'danger' }[entry.state] || 'muted'
  const stateText = entry.state === 'done' && stale ? t('prs.it.q.stale') : blocked ? t('prs.it.q.blocked') : t(`prs.it.q.${entry.state === 'failed' ? 'problem' : entry.state}`)
  return (
    <li data-slot="prs-it-entry" data-state={entry.state} data-source={entry.source} data-stale={stale ? 'true' : undefined}
      className="@container flex min-w-0 flex-col gap-2 rounded-lg border bg-card px-3 py-2.5">
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
        <div className="flex min-w-0 flex-1 basis-56 flex-col gap-1">
          <span data-slot="prs-it-entry-name" className={cn('text-sm font-medium text-foreground', WRAP)}>{entry.name}</span>
          <span className={cn('text-[11px] text-muted-foreground tabular-nums', WRAP)}>
            {[formatSize(info?.bytes ?? entry.bytes, { locale }), dimsText(info?.width, info?.height), info?.type ? typeLabel(info.type) : null,
              entry.source === 'scan' ? t('prs.it.q.fromScan') : null].filter(Boolean).join(' · ')}
          </span>
        </div>
        <ToneBadge tone={stateTone} data-slot="prs-it-entry-state" className={cn(CHIP, 'gap-1')}>
          {processing && <Spinner size={11} inline decorative />}{stateText}
        </ToneBadge>
      </div>

      {entry.own && (
        <div data-slot="prs-it-entry-own" className="flex min-w-0 flex-wrap items-center gap-1.5 text-xs">
          <SmallBadge tone="info">{t('prs.it.q.own', settingsSummary(entry.own, t, locale))}</SmallBadge>
          <Button type="button" variant="ghost" size="sm" className={cn('h-6 px-1.5 text-xs', TOUCH)} disabled={processing}
            aria-label={t('prs.it.q.ownClearFor', entry.name)} onClick={() => store.setOwnSettings(entry.id, null)}>
            <X aria-hidden="true" />{t('prs.it.q.ownClear')}
          </Button>
        </div>
      )}

      {processing && (
        <div data-slot="prs-it-entry-progress" className="flex min-w-0 flex-col gap-1">
          <span className="text-xs text-muted-foreground tabular-nums">
            {pct != null ? t('prs.it.st.processingPct', phaseText(prog.phase, t), formatPct(pct, locale, 0)) : t('prs.it.st.processing', phaseText(prog.phase, t))}
          </span>
          <ProgressBar value={pct ?? undefined} max={100} size="sm" decorative />
        </div>
      )}
      {entry.state === 'failed' && entry.problem && (
        <p data-slot="prs-it-entry-problem" className={cn('m-0 text-xs text-destructive', WRAP)}>{problemLine(entry.problem, t, locale)}</p>
      )}
      {entry.result && !processing && <ResultLine result={entry.result} t={t} locale={locale} className="text-xs" />}
      {entry.tlsVerified === false && <TlsUnverifiedNote t={t} className="text-xs" />}

      <div data-slot="prs-it-entry-actions" className="flex min-w-0 flex-wrap items-center gap-1.5">
        {entry.state === 'done' && (
          <Button type="button" variant="outline" size="sm" className={cn('h-7 px-2 text-xs', TOUCH)} data-slot="prs-it-entry-download"
            aria-label={t('prs.it.q.downloadFor', entry.result.name)}
            onClick={() => onDownload(entry)}>
            <Download aria-hidden="true" />{t('prs.it.q.download')}
          </Button>
        )}
        {entry.state === 'done' && !entry.result.kept && (
          <Button type="button" variant="ghost" size="sm" className={cn('h-7 px-2 text-xs', TOUCH)} data-slot="prs-it-entry-compare"
            aria-label={t('prs.it.cmp.openFor', entry.name)}
            onClick={() => { const b = blobs(); if (b?.original && b.result) ctx.openCompare({ title: entry.name, original: b.original, result: b.result, summary: entry.result }) }}>
            <Columns2 aria-hidden="true" />{t('prs.it.cmp.open')}
          </Button>
        )}
        {natural != null && entry.state !== 'failed' && (
          <Button type="button" variant="ghost" size="sm" className={cn('h-7 px-2 text-xs', TOUCH)} data-slot="prs-it-entry-responsive"
            aria-label={t('prs.it.rs.openFor', entry.name)}
            onClick={() => { const b = blobs(); if (b?.original) ctx.openResponsive({ name: entry.name, base: entry.base, blob: b.original, info, format: panelFormat }) }}>
            <MonitorSmartphone aria-hidden="true" />{t('prs.it.rs.open')}
          </Button>
        )}
        {entry.state === 'failed' && entry.problem?.code !== 'tooLargeFile' && (
          <Button type="button" variant="outline" size="sm" className={cn('h-7 px-2 text-xs', TOUCH)} data-slot="prs-it-entry-retry"
            aria-label={t('prs.it.q.retryFor', entry.name)} onClick={() => store.retryEntry(entry.id)}>
            <RotateCw aria-hidden="true" />{t('prs.it.q.retry')}
          </Button>
        )}
        <Button type="button" variant="ghost" size="sm" className={cn('h-7 px-2 text-xs text-muted-foreground', TOUCH)} data-slot="prs-it-entry-remove"
          aria-label={t('prs.it.q.removeFor', entry.name)} onClick={() => store.removeEntry(entry.id)}>
          <Trash2 aria-hidden="true" />{t('prs.it.q.remove')}
        </Button>
      </div>
    </li>
  )
}
