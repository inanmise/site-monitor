import { useState } from 'react'
import { Download, FileText, FileSpreadsheet } from 'lucide-react'
import { api } from '../../api/client'
import { useT, useLanguage } from '../../i18n/index.jsx'
import { downloadBlob } from '../../utils/csvExport.js'
import { clientFailure, errorInfoOf } from '../../utils/errorMessages.js'
import { useToast } from '../ui/Toast.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'
import { EXPORT_MAX, EXPORT_RANGE, excelSheet, exportFileName, exportRangeProblem, unwrapExportJson } from './activityExport.js'

/** xlsxWriter yalnız Excel istenince yüklenir (açılış parçasına girmez — lazyChunks kapısı). */
const loadXlsxWriter = () => import('../../utils/xlsxWriter.js')

/** Eski pod (bu uç yok): `GET /api/activity/{id}` `export`'u kimlik sanıp 400 `INVALID_PARAMETER` döner (inceleme FR D2). */
const isOldServer = (res) => {
  const status = Number(res?.status ?? errorInfoOf(res)?.status)
  return status === 405 || status === 404 || (status === 400 && (res?.code ?? errorInfoOf(res)?.code) === 'INVALID_PARAMETER')
}
/** 90 günü aşan aralık: sunucu 400 `VALIDATION_FAILED` alan `from` — ileti tarih alanının altında söylenir. */
const isRangeRefusal = (res) => {
  const status = Number(res?.status ?? errorInfoOf(res)?.status)
  const field = res?.field ?? (Array.isArray(res?.fields) ? res.fields[0]?.field : null)
  return status === 400 && (res?.code ?? errorInfoOf(res)?.code) === 'VALIDATION_FAILED' && field === 'from'
}

/**
 * "Dışa aktar" menüsü (2026-11-05, E) — o anki süzgeçlerle TÜM sayfalar: CSV (sunucunun dosyası, en çok 50 000 satır)
 * ya da Excel (en çok 20 000 satır, tarayıcıda kurulur). Bir dışa aktarım sürerken menü kilitli (çift tıklama iki dosya
 * indirmez).
 *
 * <p>İstek parametreleri TIKLAMA ANINDA kurulur (`getParams()` — inceleme FR O2: listenin "son 24 saat"i her istekte
 * şimdiye göre; açılış anında dondurulan parametre 8 saat sonra dünü de aktarıyordu). Aralık en çok
 * {@link EXPORT_RANGE.maxDays} gün: istemci önceden denetler (istek gitmez), sunucunun 400 `from` reddi de aynı yola
 * düşer → `onRangeError(ileti)` (tarih alanının altında). Aralık seçilmediyse sunucu son 30 günü verir
 * (`range_defaulted` / `X-Export-Range-Defaulted`) → sonuçta `rangeDefaulted`. Eski pod "bu sürümde yok" der.
 *
 * Props: `{ getParams, disabled, onResult({ format, truncated, max, rangeDefaulted, defaultDays }), onRangeError(msg) }`.
 * Test kancaları: `data-slot="act-export-trigger"`, öğeler `act-export-csv` / `act-export-xlsx`.
 */
export default function ActivityExportMenu({ getParams, params, disabled = false, onResult, onRangeError }) {
  const t = useT()
  const { lang } = useLanguage()
  const toast = useToast()
  const [busy, setBusy] = useState(null)   // null | 'csv' | 'xlsx'
  const fmt = (n) => Number(n).toLocaleString(lang === 'en' ? 'en-GB' : 'tr-TR')
  const currentParams = () => (typeof getParams === 'function' ? getParams() : (params || {}))

  /** Aralık önden denetimi: sorun varsa ileti alanın altına gider ve istek GİTMEZ. */
  const preflight = (p) => {
    const problem = exportRangeProblem(p)
    if (!problem) { onRangeError?.(null); return true }
    onRangeError?.(t('act.ex.rangeTooLong', EXPORT_RANGE.maxDays, EXPORT_RANGE.defaultDays))
    return false
  }
  /** Başarısız yanıt: eski pod / aralık reddi / sunucunun metni. */
  const fail = (res) => {
    if (isRangeRefusal(res)) { onRangeError?.(res?.error || t('act.ex.rangeTooLong', EXPORT_RANGE.maxDays, EXPORT_RANGE.defaultDays)); return }
    if (isOldServer(res)) { toast.error(t('act.ex.unsupported')); return }
    toast.error(res?.code === 'DOWNLOAD_FAILED' ? t('act.ex.downloadFailed') : (res?.error || t('act.ex.failed')), { details: res })
  }
  const done = (format, { truncated, max, rangeDefaulted, defaultDays }) => {
    onResult?.({ format, truncated, max, rangeDefaulted, defaultDays })
    toast.success(truncated ? t('act.ex.doneTruncated', fmt(max)) : t('act.ex.done'))
  }

  async function runCsv() {
    const p = currentParams()
    if (!preflight(p)) return
    setBusy('csv')
    try {
      const res = await api.exportActivity(p, 'csv')
      if (res?.success) {
        done('csv', { truncated: !!res.truncated, max: res.maxRows || EXPORT_MAX.csv, rangeDefaulted: res.rangeDefaulted === true,
          defaultDays: EXPORT_RANGE.defaultDays })
      } else {
        fail(res)
      }
    } catch (e) {
      const f = clientFailure(e, { fallback: t('act.ex.failed'), chunk: t('te.pageUpdated') })
      toast.error(f.message, { details: f.details })
    } finally {
      setBusy(null)
    }
  }

  async function runXlsx() {
    const p = currentParams()
    if (!preflight(p)) return
    setBusy('xlsx')
    try {
      const [res, mod] = await Promise.all([api.exportActivity(p, 'json'), loadXlsxWriter()])
      if (!res?.success) { fail(res); return }
      const body = unwrapExportJson(res)
      if (!body) { toast.error(t('act.ex.failed')); return }
      const bytes = mod.buildXlsx([excelSheet(body.rows, t, lang)])
      if (!downloadBlob(exportFileName('xlsx'), new Blob([bytes], { type: mod.XLSX_MIME }))) {
        toast.error(t('act.ex.downloadFailed'))
        return
      }
      done('xlsx', { truncated: body.truncated, max: body.maxRows || EXPORT_MAX.json, rangeDefaulted: body.rangeDefaulted,
        defaultDays: body.rangeDefaultDays || EXPORT_RANGE.defaultDays })
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
        <DropdownMenuLabel className="text-xs font-normal whitespace-normal text-muted-foreground">
          {t('act.ex.scope')} {t('act.ex.rangeNote', EXPORT_RANGE.defaultDays, EXPORT_RANGE.maxDays)}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem data-slot="act-export-csv" className="items-start pointer-coarse:min-h-10" onSelect={runCsv}>
          <FileText aria-hidden="true" className="mt-0.5" />
          <span className="flex min-w-0 flex-col">
            <span className="font-medium">{t('act.ex.csv')}</span>
            <span className="text-xs whitespace-normal text-muted-foreground">{t('act.ex.csvHint', fmt(EXPORT_MAX.csv))}</span>
          </span>
        </DropdownMenuItem>
        <DropdownMenuItem data-slot="act-export-xlsx" className="items-start pointer-coarse:min-h-10" onSelect={runXlsx}>
          <FileSpreadsheet aria-hidden="true" className="mt-0.5" />
          <span className="flex min-w-0 flex-col">
            <span className="font-medium">{t('act.ex.xlsx')}</span>
            <span className="text-xs whitespace-normal text-muted-foreground">{t('act.ex.xlsxHint', fmt(EXPORT_MAX.json))}</span>
          </span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
