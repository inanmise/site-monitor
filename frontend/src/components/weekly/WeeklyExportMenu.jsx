import { Download, FileSpreadsheet, Printer, Table2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { downloadYearSummaryCsv, printYearSummary } from './yearSummaryActions.js'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'

/**
 * Sayfa başlığındaki "Dışa aktar" menüsü (2026-09-27): listelenen raporların CSV'si + (global admin / AUDIT'e veri
 * geliyorsa) yönetici yıl özeti — yazdır / PDF (bağımsız A4 belge) ve CSV. Eskiden bu düğmeler liste çubuğunda ve
 * tamamlama panosunun içindeydi (pano kapalıyken görünmüyordu).
 */
export default function WeeklyExportMenu({ onListCsv, listCount = 0, yearData }) {
  const t = useT()
  const hasYear = !!yearData?.teams?.length
  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button type="button" variant="outline" data-tour="wr-csv" data-slot="wr-export">
          <Download aria-hidden="true" /> {t('wr.export')}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="z-(--z-menu) w-64">
        <DropdownMenuLabel className="text-xs text-muted-foreground">{t('wr.exportList')}</DropdownMenuLabel>
        <DropdownMenuItem disabled={!listCount} onSelect={() => onListCsv?.()} title={t('wr.csvTitle')}>
          <Table2 aria-hidden="true" /> {t('wr.exportListCsv', listCount)}
        </DropdownMenuItem>
        {hasYear && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuLabel className="text-xs text-muted-foreground">{t('wr.yearSummary')} · {yearData.year}</DropdownMenuLabel>
            <DropdownMenuItem onSelect={() => printYearSummary(yearData, t)} title={t('wr.yearSummaryPrintTitle')}>
              <Printer aria-hidden="true" /> {t('wr.yearSummaryPrint')}
            </DropdownMenuItem>
            <DropdownMenuItem onSelect={() => downloadYearSummaryCsv(yearData, t)} title={t('wr.yearSummaryCsvTitle')}>
              <FileSpreadsheet aria-hidden="true" /> {t('wr.yearSummaryCsv')}
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
