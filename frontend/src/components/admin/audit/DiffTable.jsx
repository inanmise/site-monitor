import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Alan farkı tablosu (alan · eski → yeni) — shadcn Table. Denetim Kaydı ayrıntısı ve Yönetim
 * "Değişiklik Geçmişi" satır açılımı AYNI tabloyu çizer. Eski `.audit-diff-table` ailesinin karşılığı:
 * amber başlık, eski değer kırmızı + üstü çizili, yeni değer yeşil.
 *
 * rows: [[anahtar, alanEtiketi, eskiMetin, yeniMetin], …]. Test kancası: hücrelerde `data-diff`.
 */
export default function DiffTable({ rows, fieldLabel, fromLabel, toLabel, className = '' }) {
  return (
    <div className={cn('w-fit max-w-full min-w-[min(480px,100%)] overflow-hidden rounded-md border shadow-xs border-border', className)}>
      <Table className="text-[0.82em]">
        <TableHeader className="bg-amber-500/15 [&_tr]:border-amber-500/30">
          <TableRow className="hover:bg-transparent">
            <TableHead className="h-8 px-3.5 font-semibold text-amber-800 dark:text-amber-200">{fieldLabel}</TableHead>
            <TableHead className="h-8 px-3.5 font-semibold text-amber-800 dark:text-amber-200">{fromLabel}</TableHead>
            <TableHead className="h-8 px-3.5 font-semibold text-amber-800 dark:text-amber-200">{toLabel}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map(([key, field, from, to]) => (
            <TableRow key={key}>
              <TableCell data-diff="field" className="px-3.5 py-1 align-top font-semibold">{field}</TableCell>
              <TableCell data-diff="from" className="max-w-[300px] px-3.5 py-1 align-top break-all whitespace-normal text-destructive line-through">{from}</TableCell>
              <TableCell data-diff="to" className="max-w-[300px] px-3.5 py-1 align-top break-all whitespace-normal text-success">{to}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
