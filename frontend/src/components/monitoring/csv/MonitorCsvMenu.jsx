import { useRef, useState } from 'react'
import { Download, FileSpreadsheet, Upload } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { api } from '../../../api/client.js'
import { useToast } from '../../ui/Toast.jsx'
import { importWithDeadline } from '../../../utils/importWithDeadline.js'
import { clientFailure } from '../../../utils/errorMessages.js'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { cn } from '@/lib/utils'
import { MONITOR_LABEL_KEY } from '../../palette/paletteModel.js'
import { isCsvType } from './monitorCsvModel.js'

/** İçe aktarım penceresi tembel parça — yalnız "CSV'den içe aktar" seçilince indirilir (süre sınırlı). */
const loadImportDialog = () => importWithDeadline(() => import('./MonitorCsvImportDialog.jsx'))

/**
 * İzleme sayfası başlığındaki "İçe / dışa aktar" menüsü (2026-11-19, kullanıcı isteği: "UAT'tan prod'a izleme taşıma, toplu
 * kurulum"). On izleme türünde (`MONITOR_CSV_TYPES`) çizilir:
 *  - "CSV olarak dışa aktar": türün GÖRDÜĞÜNÜZ izlemeleri sabit sütunlu CSV olarak iner (sırlar yazılmaz).
 *  - "CSV'den içe aktar…" (yalnız `canImport`): önizleme → onay → satır satır sonuç penceresi; kapanınca `onImported`
 *    (liste yenilemesi) — yalnız gerçek koşum yapıldıysa.
 * Düğme lg altında yalnız ikon (ad aria-label'da), dokunmatikte 40 px. Test kancaları: `data-slot="monitor-csv-menu|
 * monitor-csv-export|monitor-csv-import"`.
 */
export default function MonitorCsvMenu({ type, canImport = false, onImported, className, labelClassName = 'hidden lg:inline' }) {
  const t = useT()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [Dialog, setDialog] = useState(null)
  const [open, setOpen] = useState(false)
  const loading = useRef(false)
  if (!isCsvType(type)) return null
  const typeLabel = MONITOR_LABEL_KEY[type] ? t(MONITOR_LABEL_KEY[type]) : type

  async function exportCsv() {
    if (busy) return
    setBusy(true)
    try {
      const res = await api.monitoring.csvExport(type)
      if (res?.success) {
        if (res.rows === 0) toast.info(t('mcsv.exportEmpty'), 7000)
        else if (res.truncated) toast.info(t('mcsv.exportTruncated', res.maxRows ?? res.rows ?? ''), 8000)
        else if (res.rows === 1) toast.success(t('mcsv.exportDoneOne'))
        else toast.success(res.rows != null ? t('mcsv.exportDone', res.rows) : t('mcsv.exportDoneUnknown'))
        return
      }
      if (res?.code === 'DOWNLOAD_FAILED') { toast.error(t('mcsv.downloadFailed')); return }
      const status = Number(res?.status ?? 0)
      if (status === 405 || (status === 404 && (res?.code == null || res?.code === 'NOT_FOUND'))) {
        toast.error(t('mcsv.err.oldServer'))
        return
      }
      toast.error(res?.error || t('mcsv.exportFailed'))
    } catch (e) {
      toast.error(e?.message || t('mcsv.exportFailed'))
    } finally {
      setBusy(false)
    }
  }

  async function openImport() {
    if (loading.current) return
    if (Dialog) { setOpen(true); return }
    loading.current = true
    try {
      const mod = await loadImportDialog()
      setDialog(() => mod.default)
      setOpen(true)
    } catch (e) {
      const f = clientFailure(e, { fallback: t('mcsv.err.dialogLoad'), chunk: t('mcsv.err.dialogLoad') })
      toast.error(f.message || t('mcsv.err.dialogLoad'), f.details ? { details: f.details } : undefined)
    } finally {
      loading.current = false
    }
  }

  return (
    <>
      <DropdownMenu modal={false}>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="outline" data-slot="monitor-csv-menu" aria-busy={busy || undefined}
            aria-label={t('mcsv.menuAria')} title={t('mcsv.menuAria')} className={cn('shrink-0', className)}>
            <FileSpreadsheet aria-hidden="true" />
            <span className={labelClassName}>{t('mcsv.menu')}</span>
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" collisionPadding={8} className="z-(--z-menu) w-[min(22rem,calc(100vw-2rem))]">
          <DropdownMenuItem className="min-h-10 items-start" disabled={busy} data-slot="monitor-csv-export"
            onSelect={() => { exportCsv() }}>
            <Download aria-hidden="true" className="mt-0.5" />
            <span className="flex min-w-0 flex-col">
              <span className="font-medium">{busy ? t('mcsv.exporting') : t('mcsv.export')}</span>
              <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('mcsv.exportHint')}</span>
            </span>
          </DropdownMenuItem>
          {canImport && (
            <DropdownMenuItem className="min-h-10 items-start" data-slot="monitor-csv-import" onSelect={() => { openImport() }}>
              <Upload aria-hidden="true" className="mt-0.5" />
              <span className="flex min-w-0 flex-col">
                <span className="font-medium">{t('mcsv.import')}</span>
                <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('mcsv.importHint')}</span>
              </span>
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {Dialog && (
        <Dialog open={open} type={type} typeLabel={typeLabel}
          onClose={(applied) => { setOpen(false); if (applied) onImported?.() }} />
      )}
    </>
  )
}
