import { useContext, useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlert, CircleCheck, CircleMinus, Images } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import { PHONE_FULLSCREEN } from '../ui/modalClasses.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { Button } from '@/components/shadcn/button'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Spinner } from '@/components/shadcn/spinner'
import { cn } from '@/lib/utils'
import { formatBytes } from './techErrorModel.js'
import { BULK_MAX_FILES, bulkConflicts, mapDroppedFiles } from './imageFillModel.js'
import { ImageFillContext } from './ImageFill.jsx'

/** Tekil / çoğul (projenin deseni: `<anahtar>.one` yalnız 1 için). */
const plural = (t, key, n, ...rest) => t(Number(n) === 1 ? `${key}.one` : key, ...rest)

/** Yükleme sonucunun atlama nedeni → cümle anahtarı (bilinmeyen neden genel "atlandı"). */
const SKIP_KEYS = Object.freeze({
  filled: 'te.bulk.res.filled', noSlot: 'te.bulk.res.noSlot', busy: 'te.bulk.res.busy', readOnly: 'te.bulk.res.readOnly',
  notUploaded: 'te.bulk.res.notUploaded', kept: 'te.bulk.res.kept', none: 'te.bulk.res.skipped',
})

/**
 * TOPLU BIRAKMA (2026-11-20, C4): bir görsel bölümüne bırakılan dosyalar yuvalara eşlenir — varsayılan önce dosya adındaki
 * yuva kimliği, sonra yuva adı (Türkçe katlanmış), sonra sıradaki boş yuva; varsayılan YALNIZ boş yuva seçer (eşleşen yuva
 * doluysa "atla" ile gelir, neden söylenir). Kullanıcı her dosyanın yuvasını seçer (shadcn NativeSelect, "Atla" dahil); dolu
 * yuvayı açıkça seçerse başlamadan önce değiştirme onayı sorulur. Yüklemeler SIRAYLA, her biri yuvanın NORMAL yolundan
 * (hazırlama, doğrulama, 40 MB rapor sınırı, kilit çakışması yeniden denemesi, öteki pencerelere duyuru); dosya başına sonuç
 * yazılır; "Durdur" kalanları yüklemez (süren yükleme biter). En çok {@link BULK_MAX_FILES} dosya.
 */
export default function BulkDropDialog({ files, startSectionId, resolve = (s) => s, onClose }) {
  const t = useT()
  const { lang: uiLang } = useLanguage()
  const { showConfirm } = useDialog()
  const fill = useContext(ImageFillContext)
  // Yuvalar ve eşleme pencere açıldığında BİR KEZ (açıkken dolan yuva varsayılanı değiştirmez — yükleme yine denetler)
  // Bekletilen (gönderilemeyen) görseli olan yuva hedef olamaz: yeni yükleme o görseli sessizce bırakırdı (inceleme 2026-11-20)
  const [slots] = useState(() => (fill?.order?.() || []).map((o) => ({ ...o, label: resolve(o.label) || o.id, filled: !!fill?.isFilled?.(o.id),
    kept: !!fill?.isKept?.(o.id) })))
  const [plan] = useState(() => mapDroppedFiles(files, slots, { isFilled: (id) => slots.find((s) => s.id === id)?.filled,
    isKept: (id) => slots.find((s) => s.id === id)?.kept, startSectionId }))
  const list = useMemo(() => Array.from(files || []).slice(0, BULK_MAX_FILES), [files])
  const [targets, setTargets] = useState(() => Object.fromEntries(plan.rows.map((r) => [r.index, r.target || ''])))
  const [touched, setTouched] = useState(() => new Set())
  const [phase, setPhase] = useState('map')          // map | running | done
  const [results, setResults] = useState({})
  const cancelled = useRef(false)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; cancelled.current = true }
  }, [])

  const slotById = useMemo(() => new Map(slots.map((s) => [s.id, s])), [slots])
  const conflicts = useMemo(() => bulkConflicts(targets), [targets])
  const chosen = plan.rows.filter((r) => targets[r.index])
  const canStart = phase === 'map' && chosen.length > 0 && conflicts.size === 0

  const setResult = (index, r) => { if (alive.current) setResults((m) => ({ ...m, [index]: r })) }

  async function start() {
    if (!canStart || !fill) return
    // Dolu yuvayı açıkça seçen dosyalar: başlamadan TEK onay (adlarıyla)
    const replacing = chosen.filter((r) => slotById.get(targets[r.index])?.filled)
    if (replacing.length) {
      const names = replacing.map((r) => `“${slotById.get(targets[r.index])?.label}”`).join(', ')
      const ok = await showConfirm({ title: t('te.bulk.replaceTitle'), message: plural(t, 'te.bulk.replaceBody', replacing.length, replacing.length, names),
        variant: 'warning', confirmText: t('te.bulk.replaceConfirm'), cancelText: t('te.cancel') })
      if (!ok || !alive.current) return
    }
    cancelled.current = false
    setPhase('running')
    for (const r of plan.rows) {
      if (!targets[r.index]) setResult(r.index, { status: 'skipped', reason: 'none' })
    }
    for (const r of chosen) {
      if (!alive.current) return
      if (cancelled.current) { setResult(r.index, { status: 'cancelled' }); continue }
      const target = targets[r.index]
      setResult(r.index, { status: 'running' })
      let res
      try {
        res = await fill.uploadTo(target, list[r.index], { mode: slotById.get(target)?.filled ? 'replace' : 'empty', quiet: true })
      } catch (e) {
        res = { status: 'failed', message: e?.message || '' }
      }
      setResult(r.index, res || { status: 'failed', message: '' })
    }
    if (alive.current) setPhase('done')
  }

  const counts = useMemo(() => {
    const c = { done: 0, skipped: 0, failed: 0, cancelled: 0 }
    for (const v of Object.values(results)) if (c[v?.status] != null) c[v.status]++
    return c
  }, [results])

  const resultLine = (r) => {
    const res = results[r.index]
    if (!res) return <span className="text-muted-foreground">{t('te.bulk.res.waiting')}</span>
    if (res.status === 'running') {
      return <span className="inline-flex items-center gap-1.5"><Spinner className="size-3.5" /> {t('te.bulk.res.uploading')}</span>
    }
    if (res.status === 'done') {
      return (
        <span className="inline-flex items-start gap-1.5 text-success">
          <CircleCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('te.bulk.res.done', slotById.get(targets[r.index])?.label || '')}</span>
        </span>
      )
    }
    if (res.status === 'failed') {
      return (
        <span className="inline-flex items-start gap-1.5 text-destructive">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{res.message ? t('te.bulk.res.failedMsg', res.message) : t('te.bulk.res.failed')}</span>
        </span>
      )
    }
    if (res.status === 'cancelled') return <span className="text-muted-foreground">{t('te.bulk.res.cancelled')}</span>
    return (
      <span className="inline-flex items-start gap-1.5 text-muted-foreground">
        <CircleMinus aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span className="[overflow-wrap:anywhere]">{t(SKIP_KEYS[res.reason] || SKIP_KEYS.none)}</span>
      </span>
    )
  }

  /** Eşlemenin neden böyle önerildiği (kullanıcı seçimi değiştirdiyse yalnız dolu yuva uyarısı). */
  const howLine = (r) => {
    const target = targets[r.index]
    if (touched.has(r.index)) {
      return target && slotById.get(target)?.filled ? <span className="font-medium text-foreground">{t('te.bulk.willReplace')}</span> : null
    }
    const match = slotById.get(r.match)?.label || ''
    switch (r.how) {
      case 'name': return t('te.bulk.how.name')
      case 'label': return t('te.bulk.how.label')
      case 'next': return t('te.bulk.how.next')
      case 'filled': return t('te.bulk.how.filled', match)
      case 'taken': return t('te.bulk.how.taken', match)
      case 'kept': return t('te.bulk.how.kept', match)
      case 'notImage': return t('te.bulk.how.notImage')
      case 'noSlot': return t('te.bulk.how.noSlot')
      default: return null
    }
  }

  const footer = phase === 'map' ? (
    <div className="flex flex-wrap justify-end gap-2">
      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" onClick={onClose}>{t('te.cancel')}</Button>
      <Button type="button" className="pointer-coarse:min-h-10" disabled={!canStart} onClick={start} data-slot="te-bulk-start">
        {t('te.bulk.start', chosen.length)}
      </Button>
    </div>
  ) : phase === 'running' ? (
    <div className="flex flex-wrap justify-end gap-2">
      <Button type="button" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-bulk-stop"
        onClick={() => { cancelled.current = true }}>{t('te.bulk.stop')}</Button>
    </div>
  ) : (
    <div className="flex flex-wrap justify-end gap-2">
      <Button type="button" className="pointer-coarse:min-h-10" onClick={onClose} data-slot="te-bulk-close">{t('te.bulk.close')}</Button>
    </div>
  )

  return (
    <ModalShell open onClose={onClose} title={t('te.bulk.title')} icon={Images} size="lg" scrollBody busy={phase === 'running'}
      className={PHONE_FULLSCREEN} footer={footer}>
      <div className="flex flex-col gap-3" data-slot="te-bulk" data-phase={phase}>
        <p className="m-0 text-sm text-muted-foreground [overflow-wrap:anywhere]">
          {phase === 'map' ? plural(t, 'te.bulk.intro', list.length, list.length) : null}
          {phase === 'done' ? t('te.bulk.summary', counts.done, counts.skipped + counts.cancelled, counts.failed) : null}
          {phase === 'running' ? t('te.bulk.running') : null}
        </p>
        {plan.over > 0 && (
          <AlertBanner tone="warning" title={t('te.bulk.overTitle')}>
            <span data-slot="te-bulk-over">{plural(t, 'te.bulk.over', plan.over, plan.over, BULK_MAX_FILES)}</span>
          </AlertBanner>
        )}
        <ol className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('te.bulk.listLabel')}>
          {plan.rows.map((r) => {
            const selId = `te-bulk-sel-${r.index}`
            const conflict = conflicts.has(r.index)
            const target = targets[r.index]
            const how = phase === 'map' ? howLine(r) : null
            return (
              <li key={r.index} className={cn('flex min-w-0 flex-col gap-2 rounded-lg border p-3', conflict && 'border-destructive')}
                data-slot="te-bulk-row" data-index={r.index} data-target={target || ''} data-how={r.how || ''}
                data-state={results[r.index]?.status || ''}>
                <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                  <span id={`${selId}-name`} className="min-w-0 text-sm font-medium [overflow-wrap:anywhere]">{r.name}</span>
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{formatBytes(r.size, uiLang)}</span>
                </div>
                {phase === 'map' ? (
                  <>
                    <div className="min-w-0 [&>[data-slot=native-select-wrapper]]:w-full">
                      <NativeSelect id={selId} value={target || ''} className="w-full min-w-0" aria-label={t('te.bulk.selectFor', r.name)}
                        aria-invalid={conflict || undefined} aria-describedby={conflict ? `${selId}-err` : undefined}
                        onChange={(e) => {
                          const v = e.target.value
                          setTargets((m) => ({ ...m, [r.index]: v }))
                          setTouched((s) => (s.has(r.index) ? s : new Set([...s, r.index])))
                        }}>
                        <NativeSelectOption value="">{t('te.bulk.skip')}</NativeSelectOption>
                        {slots.map((s) => (
                          <NativeSelectOption key={s.id} value={s.id} disabled={s.kept}>
                            {s.kept ? t('te.bulk.optKept', s.label) : s.filled ? t('te.bulk.optFilled', s.label) : s.label}
                          </NativeSelectOption>
                        ))}
                      </NativeSelect>
                    </div>
                    {/* Seçili yuvanın TAM adı (kapalı açılır liste uzun adı kırpar) */}
                    {target && (
                      <p className="m-0 text-xs [overflow-wrap:anywhere]" data-slot="te-bulk-target">
                        {t('te.bulk.toSlot', slotById.get(target)?.label || target)}
                      </p>
                    )}
                    {how && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-bulk-how">{how}</p>}
                    {conflict && (
                      <p id={`${selId}-err`} className="m-0 text-xs text-destructive" role="alert" data-slot="te-bulk-conflict">
                        {t('te.bulk.conflict')}
                      </p>
                    )}
                  </>
                ) : (
                  <p className="m-0 text-sm" data-slot="te-bulk-result">{resultLine(r)}</p>
                )}
              </li>
            )
          })}
        </ol>
      </div>
    </ModalShell>
  )
}
