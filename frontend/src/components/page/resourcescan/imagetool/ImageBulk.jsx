import { useEffect, useMemo, useState } from 'react'
import { FileArchive, Info, TriangleAlert, X } from 'lucide-react'
import { useT, useDateLocale } from '../../../../i18n/index.jsx'
import ModalShell from '../../../ui/ModalShell.jsx'
import AlertBanner from '../../../ui/AlertBanner.jsx'
import Field from '../../../ui/Field.jsx'
import { Spinner } from '../../../ui/Progress.jsx'
import { formatNumber, formatSize, plural } from '../resourceScanModel.js'
import { TOUCH, WRAP } from '../ResourceScanParts.jsx'
import { useImageTool, useTopic } from './ImageToolContext.jsx'
import { BULK_MAX_IMAGES, UNAVAILABLE_REASONS, bulkPlan, suggestedWidth } from './imageToolModel.js'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Switch } from '@/components/shadcn/switch'
import { cn } from '@/lib/utils'

const BULK_FORMATS = ['keep', 'webp', 'smallest']

/**
 * "Büyük görselleri optimize edip indir (ZIP)" düğmesi (2026-11-14, kullanıcı kararı 4) — görsel etkisi kartında ve
 * LARGE_IMAGES önerisinde. Önce küçük bir pencere neyin ineceğini söyler (kaç görsel, alınamayacaklar ve NEDENLERİ, isteğe
 * bağlı "önerilen genişliğe küçült" ve çıktı biçimi); "Başlat" toplu işi DEPODA başlatır — durum çubuğu sekmenin
 * üstündedir (`ImageBulkStatus`), bölüm değiştirilse de iş sürer. Bağlam yoksa düğme çizilmez.
 */
export function ImageBulkButton({ items, variant = 'outline', size = 'sm', className }) {
  const t = useT()
  const ctx = useImageTool()
  const [open, setOpen] = useState(false)
  if (!ctx) return null
  return (
    <>
      <Button type="button" variant={variant} size={size} data-slot="prs-it-bulk-open" className={cn(TOUCH, 'h-auto min-h-8 py-1 whitespace-normal', className)}
        onClick={() => setOpen(true)}>
        <FileArchive aria-hidden="true" />{t('prs.it.bulk.open')}
      </Button>
      {open && <BulkDialog ctx={ctx} items={items} onClose={() => setOpen(false)} />}
    </>
  )
}

function BulkDialog({ ctx, items, onClose }) {
  const t = useT()
  const locale = useDateLocale()
  const { store, scanId, canRun, itemsKept } = ctx
  const support = useTopic(store, 'support', () => store.support())
  const bulk = useTopic(store, 'bulk', () => store.bulk())
  const [resize, setResize] = useState(true)
  const [format, setFormat] = useState('keep')
  useEffect(() => { store.ensureSupport() }, [store])
  const { plan, skipped, truncated } = useMemo(() => bulkPlan(items, { canRun, itemsKept }), [items, canRun, itemsKept])
  const withSuggestion = useMemo(() => plan.filter((it) => suggestedWidth(it)).length, [plan])
  const totalBytes = plan.reduce((a, it) => a + (Number(it.bytes) || 0), 0)
  const running = bulk?.state === 'running'
  // Alınamayacaklar nedenine göre sayılır (hepsi `missing.txt`'ye yazılır — sessizce atlanmaz)
  const reasons = UNAVAILABLE_REASONS.map((r) => [r, skipped.filter((s) => s.reason === r).length]).filter(([, n]) => n > 0)
  const webp = support.webp === true
  const supportKnown = support.state === 'ready'
  const blocker = !canRun ? 'no_permission' : !itemsKept ? 'items_gone' : null

  const start = () => {
    store.startBulk({ scanId, items, canRun, itemsKept, resize, format, t, locale, baseName: ctx.monitorName || t('prs.it.zip.toolBase') })
    onClose()
  }
  return (
    <ModalShell open onClose={onClose} title={t('prs.it.bulk.title')} icon={FileArchive} size="md" scrollBody closeLabel={t('app.dismiss')}
      footer={(
        <>
          <Button type="button" variant="outline" className={TOUCH} onClick={onClose}>{t('app.dismiss')}</Button>
          <Button type="button" className={TOUCH} data-slot="prs-it-bulk-start" disabled={!plan.length || running} onClick={start}>
            <FileArchive aria-hidden="true" />{t('prs.it.bulk.start')}
          </Button>
        </>
      )}>
      <div data-slot="prs-it-bulk-dialog" data-count={plan.length} className="flex min-w-0 flex-col gap-3 text-sm">
        {blocker ? (
          <p data-slot="prs-it-bulk-blocked" data-reason={blocker} className={cn('m-0 flex items-start gap-2 text-muted-foreground', WRAP)}>
            <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />{t(`prs.it.na.${blocker}`)}
          </p>
        ) : plan.length === 0 ? (
          <p data-slot="prs-it-bulk-none" className={cn('m-0 text-muted-foreground', WRAP)}>{t(skipped.length ? 'prs.it.bulk.noneFetchable' : 'prs.it.bulk.none')}</p>
        ) : (
          <p data-slot="prs-it-bulk-count" className={cn('m-0 text-foreground', WRAP)}>
            {plural(t, 'prs.it.bulk.count', plan.length, formatNumber(plan.length, locale), formatSize(totalBytes, { locale }))}
          </p>
        )}
        {running && <p data-slot="prs-it-bulk-busy" className="m-0 text-xs text-muted-foreground">{t('prs.it.bulk.alreadyRunning')}</p>}
        {truncated > 0 && (
          <p data-slot="prs-it-bulk-truncated" className={cn('m-0 text-xs text-muted-foreground', WRAP)}>
            {t('prs.it.bulk.truncated', formatNumber(BULK_MAX_IMAGES, locale), formatNumber(truncated, locale))}
          </p>
        )}
        {!blocker && reasons.length > 0 && (
          <div data-slot="prs-it-bulk-skipped" className="flex min-w-0 flex-col gap-1 rounded-lg bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
            <span className="font-semibold text-foreground">{plural(t, 'prs.it.bulk.skipped', skipped.length, formatNumber(skipped.length, locale))}</span>
            <ul className="m-0 flex list-disc flex-col gap-0.5 pl-4">
              {reasons.map(([r, n]) => <li key={r} data-reason={r} className={WRAP}>{formatNumber(n, locale)} × {t(`prs.it.na.${r}`)}</li>)}
            </ul>
          </div>
        )}
        {plan.length > 0 && (
          <>
            <div className="flex min-w-0 items-start gap-3">
              <Switch id="prs-it-bulk-resize" checked={resize && withSuggestion > 0} disabled={withSuggestion === 0} onCheckedChange={(v) => setResize(v === true)}
                className="mt-0.5" aria-describedby="prs-it-bulk-resize-h" />
              <div className="flex min-w-0 flex-col gap-0.5">
                <Label htmlFor="prs-it-bulk-resize" className={cn('text-sm font-medium', WRAP)}>{t('prs.it.bulk.resize')}</Label>
                <span id="prs-it-bulk-resize-h" className={cn('text-xs text-muted-foreground', WRAP)}>
                  {withSuggestion > 0 ? plural(t, 'prs.it.bulk.resizeHint', withSuggestion, formatNumber(withSuggestion, locale)) : t('prs.it.bulk.resizeNone')}
                </span>
              </div>
            </div>
            <Field label={t('prs.it.set.format')} name="bulkFormat" className="mb-0"
              hint={supportKnown && !webp ? t('prs.it.set.webpOff') : t(`prs.it.set.formatHint.${format}`)}>
              {({ id, describedBy }) => (
                <NativeSelect id={id} aria-describedby={describedBy} value={format} data-slot="prs-it-bulk-format" className="pointer-coarse:h-10"
                  onChange={(e) => setFormat(e.target.value)}>
                  {BULK_FORMATS.map((f) => <NativeSelectOption key={f} value={f} disabled={f === 'webp' && supportKnown && !webp}>{t(`prs.it.set.format.${f}`)}</NativeSelectOption>)}
                </NativeSelect>
              )}
            </Field>
            <p className={cn('m-0 text-xs text-muted-foreground', WRAP)}>{t('prs.it.bulk.how')}</p>
          </>
        )}
      </div>
    </ModalShell>
  )
}

/**
 * Toplu indirmenin durum çubuğu — sekmenin üstünde, bölümden bağımsız. Sürerken: "3 / 12 görsel hazırlandı" (+ sunucu
 * sınırı beklemesi, paketleme), "Vazgeç"; bitince: sonucu söyleyen şerit (tamam / kısmi + eksik sayısı / hiçbiri / iptal /
 * indirme başlatılamadı) — kapatılana dek durur. Canlı bölge yalnız metni taşır.
 */
export function ImageBulkStatus() {
  const t = useT()
  const locale = useDateLocale()
  const ctx = useImageTool()
  if (!ctx) return null
  return <BulkStatus store={ctx.store} t={t} locale={locale} />
}

function BulkStatus({ store, t, locale }) {
  const bulk = useTopic(store, 'bulk', () => store.bulk())
  if (!bulk) return null
  if (bulk.state === 'running') {
    const n = (v) => formatNumber(v, locale)
    const text = bulk.cancelling ? t('prs.it.bulk.cancelling')
      : bulk.packing ? t('prs.it.bulk.packing')
        : bulk.waiting ? t('prs.it.bulk.waiting', n(bulk.done), n(bulk.total), n(bulk.waitSeconds ?? 2))
          : t('prs.it.bulk.progress', n(bulk.done), n(bulk.total))
    return (
      <div data-slot="prs-it-bulk-bar" data-state="running" className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm">
        <Spinner decorative size={14} />
        <p role="status" aria-live="polite" data-slot="prs-it-bulk-status" className={cn('m-0 flex-1', WRAP)}>{text}</p>
        {!bulk.cancelling && !bulk.packing && (
          <Button type="button" variant="ghost" size="sm" className={TOUCH} data-slot="prs-it-bulk-cancel" onClick={() => store.cancelBulk()}>
            <X aria-hidden="true" />{t('prs.it.cancel')}
          </Button>
        )}
      </div>
    )
  }
  const r = bulk.result || {}
  const n = (v) => formatNumber(v ?? 0, locale)
  let tone = 'info'
  let text = ''
  if (r.kind === 'done') { tone = 'success'; text = plural(t, 'prs.it.bulk.done', r.files, n(r.files), formatSize(r.savedBytes ?? 0, { locale })) }
  else if (r.kind === 'partial') { tone = 'warning'; text = t('prs.it.bulk.partial', n(r.files), n(r.missing)) }
  else if (r.kind === 'cancelled') text = t('prs.it.bulk.cancelled')
  else if (r.kind === 'downloadFailed') { tone = 'danger'; text = t('prs.it.srv.download') }
  else if (r.kind === 'none') {
    tone = 'danger'
    text = r.stopped === 401 ? t('prs.it.srv.session') : r.stopped === 403 ? t('prs.it.srv.forbidden')
      : r.first ? t('prs.it.bulk.noneWhy', r.first) : t('prs.it.bulk.noneDone')
  } else { tone = 'danger'; text = t('prs.it.zip.unexpected') }
  return (
    <div data-slot="prs-it-bulk-bar" data-state="finished" data-kind={r.kind}>
      <AlertBanner tone={tone} className="mb-0" icon={tone === 'warning' || tone === 'danger' ? TriangleAlert : undefined}
        onDismiss={() => store.dismissBulk()} dismissLabel={t('app.dismiss')}
        details={r.kind === 'crashed' && r.technical ? { code: String(r.technical).slice(0, 300) } : undefined}>
        {text}
        {(r.kind === 'partial' || r.kind === 'done') && r.truncated > 0 ? ` ${t('prs.it.bulk.truncatedShort', n(BULK_MAX_IMAGES))}` : ''}
      </AlertBanner>
    </div>
  )
}
