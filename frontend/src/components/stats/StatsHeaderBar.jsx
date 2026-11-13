import { Download, FileSpreadsheet, FileText, RefreshCw } from 'lucide-react'
import { formatDate } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { VIEW_SPECS } from '../../hooks/userPrefsModel.js'
import SavedViewsMenu from '../ui/SavedViewsMenu.jsx'
import { Button } from '@/components/shadcn/button'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/shadcn/dropdown-menu'

/**
 * İstatistikler üst çubuğu (2026-11-13, #15 + #16): son güncelleme (sayfa parçalarının EN ESKİSİ — tazelik abartılmaz),
 * Yenile (App'in sertifika dalgası + sayfanın kendi kaynakları; sunucu belleği en fazla 5 sn'de bir atlanır), kayıtlı
 * görünümler (`st_*` URL durumu — `VIEW_SPECS.stats`) ve dışa aktarım menüsü (Excel: özet · matris · tablo · karne; PDF:
 * tek sayfa özet). Dokunmatikte her denetim 40 px. Test kancaları: `data-slot="stats-header-bar"`, `stats-updated`,
 * `stats-refresh`, `stats-export-menu`.
 */
export default function StatsHeaderBar({ updatedAt, busy, onRefresh, onExport, exporting = null, canExport = true }) {
  const t = useT()
  return (
    <div data-slot="stats-header-bar" className="flex min-w-0 flex-wrap items-center gap-2 print:hidden">
      <span data-slot="stats-updated" role="status" className="min-w-0 text-xs text-muted-foreground">
        {busy ? t('stx.updating') : updatedAt ? t('stx.updatedAt', formatDate(new Date(updatedAt).toISOString())) : t('stx.notUpdated')}
      </span>
      <div className="ml-auto flex min-w-0 flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" data-slot="stats-refresh" disabled={busy} onClick={onRefresh}
          className="max-sm:h-10 pointer-coarse:h-10">
          <RefreshCw aria-hidden="true" className={busy ? 'animate-spin motion-reduce:animate-none' : undefined} />{t('stx.refresh')}
        </Button>
        <SavedViewsMenu listKey="stats" tab="stats" {...VIEW_SPECS.stats} size="sm" className="max-sm:h-10 pointer-coarse:h-10" />
        <DropdownMenu modal={false}>
          <DropdownMenuTrigger asChild>
            <Button type="button" variant="outline" size="sm" data-slot="stats-export-menu" disabled={!canExport || exporting != null}
              className="max-sm:h-10 pointer-coarse:h-10">
              <Download aria-hidden="true" />{exporting ? t('stx.exporting') : t('stx.exportMenu')}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="z-(--z-menu) w-60">
            <DropdownMenuLabel>{t('stx.exportMenu')}</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem data-slot="stats-export-xlsx" className="pointer-coarse:min-h-10" onSelect={() => onExport('xlsx')}>
              <FileSpreadsheet aria-hidden="true" />{t('stx.exportXlsx')}
            </DropdownMenuItem>
            <DropdownMenuItem data-slot="stats-export-pdf" className="pointer-coarse:min-h-10" onSelect={() => onExport('pdf')}>
              <FileText aria-hidden="true" />{t('stx.exportPdf')}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}
