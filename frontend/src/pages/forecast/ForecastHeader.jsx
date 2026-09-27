import { CalendarClock, CalendarDays, Clock, Download, FileDown, Link2, Printer, RefreshCw } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'

/**
 * Sayfa başlığı (2026-09-27): marka ("Sertifika Takvimi" — yazdırma başlığı da budur), ortam rozeti, kısa açıklama,
 * veri damgası + kendiliğinden tazeleme çipi; sağda Tazele ve Dışa aktar menüsü (ICS · CSV · bağlantı · yazdır).
 * Telefonda başlık üstte, eylemler alta sarar; yazdırmada eylemler gizli.
 */
export default function ForecastHeader({ environment, dataAsOf, refreshing, onRefresh, exportDisabled, onExportIcs, onExportCsv, onCopyLink, onPrint }) {
  const t = useT()
  return (
    // flex-wrap + esnek taban: eylemler başlığın yanına sığmazsa (tablette kenar çubuğu açıkken) ALTA sarar,
    // düğmeler tek tek alt alta dizilmez
    <div data-slot="fc-header" className="flex min-w-0 flex-wrap items-start justify-between gap-x-4 gap-y-3">
      <div className="min-w-0 flex-[1_1_24rem]">
        <h1 className="m-0 flex flex-wrap items-center gap-2 text-xl leading-tight font-semibold tracking-tight">
          <CalendarDays aria-hidden="true" className="size-5 shrink-0 text-primary" />
          <span>{t('forecast.brand')}</span>
          {environment && <Badge variant="outline" data-slot="fc-env" className="tracking-wide uppercase">{String(environment).toUpperCase()}</Badge>}
        </h1>
        <p className="mt-1 max-w-[78ch] text-sm text-muted-foreground">{t('forecast.pageDesc')}</p>
        <p data-slot="fc-asof" className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
          <span className="inline-flex items-center gap-1.5"><Clock aria-hidden="true" className="size-3.5" />{t('forecast.dataAsOf')} · {dataAsOf ? formatDate(dataAsOf) : '—'}</span>
          <Badge variant="secondary" className="gap-1 font-normal print:hidden"><RefreshCw aria-hidden="true" />{t('forecast.autoRefresh', 5)}</Badge>
          <span className="hidden print:inline">{t('forecast.printedAt', formatDate(new Date().toISOString()))}</span>
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2 print:hidden">
        <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={onRefresh} disabled={refreshing} aria-busy={refreshing || undefined}>
          <RefreshCw aria-hidden="true" className={refreshing ? 'animate-spin motion-reduce:animate-none' : undefined} />{t('forecast.refresh')}
        </Button>
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" data-slot="fc-export"><Download aria-hidden="true" />{t('forecast.export')}</Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="z-(--z-menu)">
            <DropdownMenuItem onSelect={onExportIcs} disabled={exportDisabled} title={t('renewal.icsTip')}><CalendarClock aria-hidden="true" />{t('forecast.exportIcs')}</DropdownMenuItem>
            <DropdownMenuItem onSelect={onExportCsv} disabled={exportDisabled}><FileDown aria-hidden="true" />{t('forecast.exportCsv')}</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={onCopyLink}><Link2 aria-hidden="true" />{t('inv.copyLink')}</DropdownMenuItem>
            <DropdownMenuItem onSelect={onPrint}><Printer aria-hidden="true" />{t('forecast.print')}</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}
