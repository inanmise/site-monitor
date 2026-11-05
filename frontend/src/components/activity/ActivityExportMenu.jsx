import { useState } from 'react'
import { Download, FileText, FileSpreadsheet } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { downloadBlob } from '../../utils/csvExport.js'
import { clientFailure } from '../../utils/errorMessages.js'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { EXPORT_MAX, excelSheet, exportFileName, unwrapExportJson } from './activityExport.js'

/** xlsxWriter yalnız Excel istenince yüklenir (açılış parçasına girmez — lazyChunks kapısı). */
const loadXlsxWriter = () => import('../../utils/xlsxWriter.js')

/**
 * "Dışa aktar" menüsü (2026-11-05, E) — o anki süzgeçlerle TÜM sayfalar: CSV (sunucunun dosyası, en çok 50 000 satır)
 * ya da Excel (en çok 20 000 satır, tarayıcıda kurulur). Tavan aşıldıysa `onTruncated(format, max)` (liste üstünde
 * kalıcı uyarı) + bilgi tostu; hata sunucunun metniyle (429 bekleme süresi dahil), künye "Teknik ayrıntı"da.
 * Bir dışa aktarım sürerken menü kilitli (çift tıklama iki dosya indirmez).
 *
 * Props: `{ params, disabled, onTruncated }` — `params` = liste süzgeçlerinin istek parametreleri (sayfa YOK).
 * Test kancaları: `data-slot="act-export-trigger"`, öğeler `act-export-csv` / `act-export-xlsx`.
 */
export default function ActivityExportMenu({ params, disabled = false, onTruncated }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const [busy, setBusy] = useState(null)   // null | 'csv' | 'xlsx'

  async function runCsv() {
    setBusy('csv')
    try {
      const res = await api.exportActivity(params, 'csv')
      if (res?.success) {
        const max = res.maxRows || EXPORT_MAX.csv
        if (res.truncated) onTruncated?.('csv', max)
        toast.success(res.truncated ? t('act.ex.doneTruncated', max) : t('act.ex.done'))
      } else {
        toast.error(res?.code === 'DOWNLOAD_FAILED' ? t('act.ex.downloadFailed') : (res?.error || t('act.ex.failed')), { details: res })
      }
    } catch (e) {
      const f = clientFailure(e, { fallback: t('act.ex.failed'), chunk: t('te.pageUpdated') })
      toast.error(f.message, { details: f.details })
    } finally {
      setBusy(null)
    }
  }

  async function runXlsx() {
    setBusy('xlsx')
    try {
      const [res, mod] = await Promise.all([api.exportActivity(params, 'json'), loadXlsxWriter()])
      if (!res?.success) {
        toast.error(res?.error || t('act.ex.failed'), { details: res })
        return
      }
      const body = unwrapExportJson(res)
      if (!body) { toast.error(t('act.ex.failed')); return }
      const bytes = mod.buildXlsx([excelSheet(body.rows, t, lang)])
      if (!downloadBlob(exportFileName('xlsx'), new Blob([bytes], { type: mod.XLSX_MIME }))) {
        toast.error(t('act.ex.downloadFailed'))
        return
      }
      const max = body.maxRows || EXPORT_MAX.json
      if (body.truncated) onTruncated?.('xlsx', max)
      toast.success(body.truncated ? t('act.ex.doneTruncated', max) : t('act.ex.done'))
    } catch (e) {
      const f = clientFailure(e, { fallback: t('act.ex.failed'), chunk: t('te.pageUpdated') })
      toast.error(f.message, { details: f.details })
    } finally {
      setBusy(null)
    }
  }

  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" disabled={disabled || !!busy} data-slot="act-export-trigger"
          className="pointer-coarse:h-10" title={t('act.ex.title')} aria-busy={busy ? true : undefined}>
          {busy ? <Spinner size={16} decorative /> : <Download aria-hidden="true" />} {t('act.ex.button')}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="z-(--z-menu) w-72 max-w-[calc(100vw-2rem)]">
        <DropdownMenuLabel className="text-xs font-normal whitespace-normal text-muted-foreground">{t('act.ex.scope')}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem data-slot="act-export-csv" className="items-start pointer-coarse:min-h-10" onSelect={runCsv}>
          <FileText aria-hidden="true" className="mt-0.5" />
          <span className="flex min-w-0 flex-col">
            <span className="font-medium">{t('act.ex.csv')}</span>
            <span className="text-xs whitespace-normal text-muted-foreground">{t('act.ex.csvHint', EXPORT_MAX.csv.toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}</span>
          </span>
        </DropdownMenuItem>
        <DropdownMenuItem data-slot="act-export-xlsx" className="items-start pointer-coarse:min-h-10" onSelect={runXlsx}>
          <FileSpreadsheet aria-hidden="true" className="mt-0.5" />
          <span className="flex min-w-0 flex-col">
            <span className="font-medium">{t('act.ex.xlsx')}</span>
            <span className="text-xs whitespace-normal text-muted-foreground">{t('act.ex.xlsxHint', EXPORT_MAX.json.toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR'))}</span>
          </span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
