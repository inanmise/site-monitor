import { useEffect, useState } from 'react'
import { ChevronDown, Columns2, Download, ImageDown, Info, RotateCw, Scaling, ShieldAlert, SlidersHorizontal, Wrench, X, Zap } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import { focusFormError } from '../../../../utils/formErrors.js'
import { useToast } from '../../../ui/Toast.jsx'
import ModalShell from '../../../ui/ModalShell.jsx'
import ErrorDetails from '../../../ui/ErrorDetails.jsx'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import { Spinner } from '../../../ui/Progress.jsx'
import { formatNumber, formatPct } from '../resourceScanModel.js'
import { SmallBadge, TOUCH, WRAP } from '../ResourceScanParts.jsx'
import ImageSettingsFields from './ImageSettingsFields.jsx'
import { useImageTool, useTopic } from './ImageToolContext.jsx'
import {
  imageUnavailable, isImageItem, isStaleBundleProblem, noteText, optimizeSettings, phaseText, problemLine, resizeSettings, resultLine,
  resultMeta, resultTone, savingPctOf, similarityText, suggestedWidth, suggestionText, validateSettings, visibleNotes,
} from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'

const ITEM = 'pointer-coarse:min-h-10 whitespace-normal [overflow-wrap:anywhere]'

/**
 * Tarama görselinin EYLEMLERİ (2026-11-14, kullanıcı kararı: taramanın bulduğu görselin özgününü ya da optimize /
 * yeniden boyutlandırılmış hâlini indir). Etiketli "İndir" menüsü: Özgün dosya · Optimize edilmiş (aynı boyut) · Küçült +
 * optimize et (önerilen genişlik biliniyorsa o; ayrıca özel boyut) · Görsel aracında aç. İşlem TARAYICIDA yapılır; sunucu
 * yalnız özgün baytları verir (izlenen siteden alır — kapısı "Şimdi kontrol et").
 *
 * Menü yalnız IMAGE nesnelerinde ve bağlam (ImageToolProvider) varken çizilir. İndirilemeyen görselde (yetki yok, adres
 * maskeli, SVG, nesne ayrıntısı saklanmıyor, analizde açılmamış, sınırdan büyük) menü açılır ama NEDENİNİ söyler.
 * İşlemden sonra gerçek önce → sonra boyutu ve kazanç yüzdesi yazılır; sonuç açık sekme boyunca bellekte kalır (aynı
 * seçeneğe ikinci tık anında indirir). İlerleme yalnız BU satırı yeniden çizer (depo konusu).
 */
export default function ImageActions({ item, className, showSuggestion = true }) {
  const ctx = useImageTool()
  if (!ctx || !isImageItem(item) || typeof item?.url !== 'string') return null
  return <Actions ctx={ctx} item={item} className={className} showSuggestion={showSuggestion} />
}

function Actions({ ctx, item, className, showSuggestion }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const { store, scanId } = ctx
  const entry = useTopic(store, `scan:${scanId}\n${item.url}`, () => store.scanEntry(scanId, item.url))
  const [custom, setCustom] = useState(false)
  // Sayfa eski sürümden (motor dosyaları sunucuda yok): menü "yenileyin" der, motor gerektiren seçenekler sunulmaz
  const stale = useTopic(store, 'support', () => store.support()).staleBundle === true
  const why = imageUnavailable(item, ctx)
  const suggestion = suggestedWidth(item)
  const busy = entry.state === 'fetching' || entry.state === 'processing'
  const pct = entry.fraction != null && Number.isFinite(entry.fraction) ? Math.round(Math.max(0, Math.min(1, entry.fraction)) * 100) : null

  const toTool = async (settings) => {
    const res = await store.sendToTool(scanId, item, settings)
    if (res.ok) ctx.openTool?.()
    else if (res.full) toast.info(t('prs.it.tool.fullToast'))
  }
  const compare = () => {
    const b = store.scanBlobs(scanId, item.url)
    if (!b) { toast.info(t('prs.it.cmp.evicted')); return }
    ctx.openCompare({ title: item.url, original: b.original, result: b.result, summary: b.summary })
  }
  // Toplu indirmede özgün / sonuç Blob'u bellekte TUTULMAZ — düğme yalnız ikisi de varken (yoksa her tık başarısız olurdu)
  const canCompare = entry.state === 'done' && entry.result && !entry.result.kept && store.hasScanBlobs(scanId, item.url)

  return (
    <div data-slot="prs-it-actions" data-state={entry.state} data-unavailable={why || undefined}
      className={cn('flex min-w-0 flex-col gap-1.5', className)}>
      {showSuggestion && suggestion && (
        <p data-slot="prs-it-suggest" data-reason={suggestion.reason} data-source={suggestion.source}
          className={cn('m-0 flex items-start gap-1.5 text-[11px] text-muted-foreground', WRAP)}>
          <Scaling aria-hidden="true" className="mt-px size-3.5 shrink-0" />
          <span className={WRAP}>{suggestionText(suggestion, t, locale)}</span>
        </p>
      )}
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" data-slot="prs-it-menu" aria-busy={busy || undefined}
              aria-label={t('prs.it.menu.openFor', item.url)} className={cn('h-7 w-fit gap-1.5 px-2 text-xs', TOUCH)}>
              {busy ? <Spinner size={12} inline decorative /> : <Download aria-hidden="true" />}
              {t('prs.it.menu.open')}
              <ChevronDown aria-hidden="true" className="size-3.5 opacity-70" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" collisionPadding={8} data-slot="prs-it-menu-content"
            className="z-(--z-menu) max-w-[min(22rem,calc(100vw-1rem))]">
            {stale && (
              <>
                <DropdownMenuLabel data-slot="prs-it-stale" className="flex items-start gap-2 text-xs font-normal whitespace-normal text-warning">
                  <RotateCw aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                  <span className={WRAP}>{t('prs.it.stale.menu')}</span>
                </DropdownMenuLabel>
                <DropdownMenuItem className={ITEM} data-action="reload" onSelect={reloadPage}>
                  <RotateCw aria-hidden="true" />{t('prs.it.reload')}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
              </>
            )}
            {why ? (
              <DropdownMenuLabel data-slot="prs-it-unavailable" data-reason={why}
                className="flex items-start gap-2 text-xs font-normal whitespace-normal text-muted-foreground">
                <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
                <span className={WRAP}>{t(`prs.it.na.${why}`)}</span>
              </DropdownMenuLabel>
            ) : (
              <>
                <DropdownMenuLabel className="text-xs font-normal whitespace-normal text-muted-foreground">{t('prs.it.menu.hint')}</DropdownMenuLabel>
                <DropdownMenuItem className={ITEM} disabled={busy} data-action="original" onSelect={() => { store.downloadOriginal(scanId, item) }}>
                  <ImageDown aria-hidden="true" />{t('prs.it.menu.original')}
                </DropdownMenuItem>
                {!stale && (<>
                <DropdownMenuItem className={ITEM} disabled={busy} data-action="optimize"
                  onSelect={() => { store.optimizeScanImage(scanId, item, optimizeSettings(), { mode: 'optimized' }) }}>
                  <Zap aria-hidden="true" />{t('prs.it.menu.optimize')}
                </DropdownMenuItem>
                {suggestion && (
                  <DropdownMenuItem className={ITEM} disabled={busy} data-action="resize" data-width={suggestion.width}
                    onSelect={() => { store.optimizeScanImage(scanId, item, resizeSettings(suggestion.width), { mode: 'width' }) }}>
                    <Scaling aria-hidden="true" />{t('prs.it.menu.resize', formatNumber(suggestion.width, locale))}
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem className={ITEM} disabled={busy} data-action="custom" onSelect={() => setCustom(true)}>
                  <SlidersHorizontal aria-hidden="true" />{t('prs.it.menu.custom')}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem className={ITEM} disabled={busy} data-action="tool"
                  onSelect={() => { toTool(suggestion ? resizeSettings(suggestion.width) : null) }}>
                  <Wrench aria-hidden="true" />{t('prs.it.menu.tool')}
                </DropdownMenuItem>
                </>)}
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
        {busy && (
          <Button type="button" variant="ghost" size="sm" data-slot="prs-it-cancel" className={cn('h-7 px-2 text-xs', TOUCH)}
            aria-label={t('prs.it.cancelFor', item.url)} onClick={() => store.cancelScan(scanId, item.url)}>
            <X aria-hidden="true" />{t('prs.it.cancel')}
          </Button>
        )}
        {canCompare && (
          <Button type="button" variant="ghost" size="sm" data-slot="prs-it-compare" className={cn('h-7 px-2 text-xs', TOUCH)}
            aria-label={t('prs.it.cmp.openFor', item.url)} onClick={compare}>
            <Columns2 aria-hidden="true" />{t('prs.it.cmp.open')}
          </Button>
        )}
      </div>
      <div data-slot="prs-it-status" role="status" aria-live="polite" className={cn('flex min-w-0 flex-col gap-1 text-xs empty:sr-only', WRAP)}>
        {entry.state === 'fetching' && <span className="text-muted-foreground">{t('prs.it.st.fetching')}</span>}
        {entry.state === 'processing' && (
          <span className="text-muted-foreground tabular-nums">
            {pct != null ? t('prs.it.st.processingPct', phaseText(entry.phase, t), formatPct(pct, locale, 0)) : t('prs.it.st.processing', phaseText(entry.phase, t))}
          </span>
        )}
        {entry.problem && !busy && (
          <span data-slot="prs-it-problem" className="flex min-w-0 flex-col gap-1 text-destructive">
            <span className={WRAP}>{problemLine(entry.problem, t, locale)}</span>
            {isStaleBundleProblem(entry.problem) && <ReloadPageButton t={t} className="w-fit" />}
            {entry.problem.kind === 'server' && <ErrorDetails info={entry.problem.res} />}
          </span>
        )}
        {entry.result && !busy && <ResultLine result={entry.result} t={t} locale={locale} />}
        {entry.original?.tlsVerified === false && !busy && <TlsUnverifiedNote t={t} />}
      </div>
      {custom && (
        <CustomSizeDialog item={item} suggestion={suggestion} store={store} scanId={scanId} onClose={() => setCustom(false)}
          onTool={(settings) => toTool(settings)} />
      )}
    </div>
  )
}

/** Sayfayı yeniden yükler (bayat paket — kullanıcı basınca; otomatik yeniden yükleme yok). */
export function reloadPage() {
  try { window.location.reload() } catch { /* test ortamı */ }
}

/** "Sayfayı yenile" düğmesi (bayat paket sorununun yanında). */
export function ReloadPageButton({ t, className }) {
  return (
    <Button type="button" variant="outline" size="sm" data-slot="prs-it-reload" className={cn('h-7 gap-1.5 px-2 text-xs', TOUCH, className)} onClick={reloadPage}>
      <RotateCw aria-hidden="true" />{t('prs.it.reload')}
    </Button>
  )
}

/** Sayfa eski sürümden kalmış (motor dosyaları sunucuda yok): araç ve menüler "tarayıcı desteklemiyor" değil bunu söyler. */
export function StaleBundleNotice({ t, className }) {
  return (
    <div data-slot="prs-it-stale-banner" className={className}>
      <AlertBanner tone="warning" icon={RotateCw} className="mb-0" title={t('prs.it.eng.BROWSER_UNSUPPORTED.stale')} actions={<ReloadPageButton t={t} />}>
        <span className={WRAP}>{t('prs.it.stale.body')}</span>
      </AlertBanner>
    </div>
  )
}

/**
 * Özgünün aktarımı doğrulanamadı (sunucu: `X-Image-Tls-Verified: false` — sertifika zinciri sistem / kurum deposunca
 * güvenilmiyor ya da adı kapsamıyor). İndirme ENGELLENMEZ (iç siteler) ama gizlenmez: bu baytlar yeniden yayınlanabilir.
 */
export function TlsUnverifiedNote({ t, className }) {
  return (
    <span data-slot="prs-it-tls-unverified" className={cn('flex min-w-0 items-start gap-1.5 text-warning', className)}>
      <ShieldAlert aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
      <span className={WRAP}>{t('prs.it.tls.unverified')}</span>
    </span>
  )
}

/** İşlem sonucu: gerçek önce → sonra boyutu + kazanç, ölçü / biçim, benzerlik (ölçüldüyse) ve motorun notları. */
export function ResultLine({ result, t, locale, className }) {
  const meta = resultMeta(result, t, locale)
  const sim = similarityText(result, locale)
  const tone = resultTone(result)
  const saving = savingPctOf(result)
  // Ölçü öznitelikleri motorun sonucundan (sınama ve inceleme için: ekrandaki sayı motorun sayısı mı?)
  return (
    <span data-slot="prs-it-result" data-kept={result.kept ? 'true' : undefined} data-tone={tone}
      data-bytes={result.bytes ?? undefined} data-before={result.original?.bytes ?? undefined} data-saving={saving ?? undefined}
      data-similarity={result.similarity ?? undefined} className={cn('flex min-w-0 flex-col gap-1', className)}>
      <span className={cn('font-semibold tabular-nums', tone === 'smaller' ? 'text-success' : tone === 'larger' ? 'text-warning' : 'text-muted-foreground', WRAP)}>
        {resultLine(result, t, locale)}
      </span>
      {(meta || sim) && (
        <span className={cn('text-[11px] text-muted-foreground tabular-nums', WRAP)}>
          {[meta, sim ? t('prs.it.res.similarity', sim) : null].filter(Boolean).join(' · ')}
        </span>
      )}
      {visibleNotes(result).length > 0 && (
        <span data-slot="prs-it-notes" className="flex min-w-0 flex-wrap gap-1">
          {visibleNotes(result).map((c) => <SmallBadge key={c} tone={c === 'TARGET_NOT_REACHED' ? 'warning' : 'muted'} data-note={c}>{noteText(c, t)}</SmallBadge>)}
        </span>
      )}
    </span>
  )
}

/**
 * "Özel boyut" penceresi: yöntem (ölçekle / sığdır / kapla / küçük resim), genişlik / yükseklik ve çıktı biçimi. Hata
 * alanın altında; geçerliyse görsel o ayarla işlenip indirilir. Varsayılan: önerilen genişlik (biliniyorsa) ile ölçekle.
 */
function CustomSizeDialog({ item, suggestion, store, scanId, onClose, onTool }) {
  const t = useT()
  const support = useTopic(store, 'support', () => store.support())
  const [form, setForm] = useState(() => (suggestion ? resizeSettings(suggestion.width) : resizeSettings('')))
  const [shown, setShown] = useState(null)
  useEffect(() => { store.ensureSupport() }, [store])
  // Düzenlenen alanın hatası hemen kalkar (öbür alanlarınki kalır)
  const change = (patch) => {
    setForm((f) => ({ ...f, ...patch }))
    setShown((cur) => {
      if (!cur) return cur
      const next = { ...cur }
      for (const k of Object.keys(patch)) delete next[k]
      if ('method' in patch) { delete next.width; delete next.height }
      return Object.keys(next).length ? next : null
    })
  }
  /** Hata varsa alanın altında gösterilir, ilk hatalı alana kaydırılıp odaklanılır (utils/formErrors). */
  const validate = () => {
    const errs = validateSettings(form, support.limits)
    setShown(errs)
    if (!errs) return true
    const run = () => focusFormError(errs)
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run); else run()
    return false
  }
  const submit = () => {
    if (!validate()) return
    store.optimizeScanImage(scanId, item, form, { mode: 'width' })
    onClose()
  }
  return (
    <ModalShell open onClose={onClose} title={t('prs.it.custom.title')} icon={SlidersHorizontal} size="sm" scrollBody closeLabel={t('app.dismiss')}
      footer={(
        <>
          <Button type="button" variant="outline" className={TOUCH} onClick={onClose}>{t('app.dismiss')}</Button>
          <Button type="button" variant="outline" className={TOUCH} data-slot="prs-it-custom-tool"
            onClick={() => { if (validate()) { onTool(form); onClose() } }}>
            <Wrench aria-hidden="true" />{t('prs.it.menu.tool')}
          </Button>
          <Button type="button" className={TOUCH} data-slot="prs-it-custom-go" onClick={submit}>
            <Download aria-hidden="true" />{t('prs.it.custom.go')}
          </Button>
        </>
      )}>
      <div data-slot="prs-it-custom" className="flex min-w-0 flex-col gap-2">
        <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{item.url}</p>
        <ImageSettingsFields value={form} onChange={change} errors={shown} support={support} limits={support.limits}
          sections={{ format: true, quality: false, resize: true, background: false, metadata: false }} />
      </div>
    </ModalShell>
  )
}
