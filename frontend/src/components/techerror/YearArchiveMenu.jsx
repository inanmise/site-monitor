import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronDown, FileArchive, X } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { downloadBlob } from '../../utils/csvExport.js'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { periodLabel } from './TechErrorParts.jsx'
import {
  archiveFileName, archiveYears, buildArchiveZip, missingText, namedEntries, runArchive, zipPlan,
} from './yearArchive.js'

/**
 * Kanalın yıl arşivi (ZIP) — durum + eylemler (2026-10-17, C12). Bir yılın PDF'leri sırayla alınır (`api.techErrors.pdfBlob`,
 * "PDF indir" ile aynı uç), tarayıcıda paketlenir ve tek dosya olarak indirilir. Raporları görebilen herkes kullanır
 * (paylaşımla okuyan da — her PDF'i zaten tek tek indirebiliyor). Görünüm yerinden ayrılırsa iş iptal edilir.
 *
 * @returns {{ run: null | { year, index, total, waiting, cancelling, packing }, start: (year) => void, cancel: () => void }}
 */
export function useYearArchive({ channelName, reports, resetKey = null }) {
  const t = useT()
  const toast = useToast()
  const [run, setRun] = useState(null)
  const ctrl = useRef(null)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; ctrl.current?.abort() }
  }, [])
  // Başka kanala geçilince süren arşiv iptal edilir (eski kanalın PDF'leri yeni kanalın adıyla paketlenmesin)
  useEffect(() => () => { ctrl.current?.abort() }, [resetKey])

  const start = useCallback(async (year) => {
    if (ctrl.current) return
    const plan = zipPlan(reports, year)
    if (!plan.length) return
    const c = new AbortController()
    ctrl.current = c
    setRun({ year, index: 0, total: plan.length, waiting: false, cancelling: false, packing: false })
    const labelOf = (item) => periodLabel(item, t)
    try {
      const res = await runArchive(plan, {
        fetchPdf: (item, signal) => api.techErrors.pdfBlob(item.id, { signal }),
        signal: c.signal,
        onProgress: (p) => { if (alive.current) setRun((r) => (r ? { ...r, index: p.index, waiting: p.waiting } : r)) },
      })
      if (!alive.current) return
      if (res.aborted || c.signal.aborted) { toast.info(t('te.zip.cancelled')); return }
      const months = res.missing.map((m) => labelOf(m.item)).join(', ')
      if (!res.files.length) {
        toast.error(t('te.zip.noneProblem', year, months), { duration: 12000 })
        return
      }
      setRun((r) => (r ? { ...r, packing: true } : r))
      const entries = namedEntries(res.files, channelName)
      const text = res.missing.length ? missingText(res.missing, { t, channelName, year, labelOf }) : null
      const bytes = await buildArchiveZip(entries, text)
      // Paketleme sırasında iptal edildiyse de indirme YOK
      if (!alive.current) return
      if (c.signal.aborted) { toast.info(t('te.zip.cancelled')); return }
      downloadBlob(archiveFileName(channelName, year), new Blob([bytes], { type: 'application/zip' }))
      if (res.missing.length) toast.error(t('te.zip.partial', res.files.length, plan.length, months), { duration: 12000 })
      else toast.success(t('te.zip.done', res.files.length, year), 8000)
    } catch (e) {
      if (alive.current) toast.error(e?.message || t('te.zip.unexpected'))
    } finally {
      if (ctrl.current === c) ctrl.current = null
      if (alive.current) setRun(null)
    }
  }, [reports, channelName, t, toast])

  const cancel = useCallback(() => {
    if (!ctrl.current) return
    ctrl.current.abort()
    setRun((r) => (r ? { ...r, cancelling: true } : r))
  }, [])

  return { run, start, cancel }
}

/**
 * "Yıl arşivi (ZIP)" menüsü: raporu olan her yıl bir öğe (rapor sayısıyla). İş sürerken tetik ODAKLANABİLİR kalır (menü
 * kapanınca odak ona döner — kapalı düğmeye dönemeyip sayfaya düşmesin) ama öğeler kapalıdır; durum satırı süreni söyler.
 */
export function YearArchiveMenu({ reports, run, onStart }) {
  const t = useT()
  const years = archiveYears(reports)
  if (!years.length) return null
  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" className="pointer-coarse:min-h-10" data-slot="te-year-zip" data-running={run ? 'true' : undefined}>
          <FileArchive aria-hidden="true" /> {t('te.zip.open')} <ChevronDown aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="z-(--z-menu) max-w-[calc(100vw-2rem)]" data-slot="te-year-zip-menu">
        <DropdownMenuLabel className="text-xs font-normal whitespace-normal text-muted-foreground">
          {run ? t('te.zip.busyHint') : t('te.zip.menuHint')}
        </DropdownMenuLabel>
        {years.map((y) => (
          <DropdownMenuItem key={y.year} className="pointer-coarse:min-h-10" data-slot="te-year-zip-item" data-year={y.year}
            disabled={!!run} onSelect={() => onStart(y.year)}>
            <FileArchive aria-hidden="true" /> {t('te.zip.yearItem', y.year, y.count)}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * Süren arşivin durum satırı — kapatılamaz; "PDF'ler hazırlanıyor… 3 / 11", sunucu meşgulse bekleme, paketleme, iptal.
 * Canlı bölge (`te-year-zip-status`) yalnız metni taşır ve her zaman çizilir (bölge duyurudan önce DOM'da olmalı; iş
 * yokken boş ve görünmez); "Vazgeç" düğmesi bölgenin yanında — düğme metni her güncellemede yeniden okunmasın.
 */
export function YearArchiveStatus({ run, onCancel }) {
  const t = useT()
  let text = ''
  if (run) {
    if (run.cancelling) text = t('te.zip.cancelling')
    else if (run.packing) text = t('te.zip.packing')
    else if (run.waiting) text = t('te.zip.waiting', run.index, run.total)
    else text = t('te.zip.progress', Math.max(1, run.index), run.total)
  }
  return (
    <div data-slot="te-year-zip-bar" data-state={run ? 'running' : 'idle'}
      className={run ? 'flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border bg-muted/40 px-3 py-2 text-sm' : 'contents'}>
      {run && <Spinner decorative size={14} />}
      <p role="status" aria-live="polite" data-slot="te-year-zip-status"
        className={run ? 'm-0 min-w-0 flex-1 [overflow-wrap:anywhere]' : 'sr-only'}>
        {run ? `${t('te.zip.statusYear', run.year)} · ${text}` : ''}
      </p>
      {run && !run.cancelling && !run.packing && (
        <Button type="button" variant="ghost" size="sm" onClick={onCancel} className="pointer-coarse:min-h-10" data-slot="te-year-zip-cancel">
          <X aria-hidden="true" /> {t('te.zip.cancel')}
        </Button>
      )}
    </div>
  )
}
