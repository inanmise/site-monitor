import { ShieldAlert, ArrowRight } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { fmtNum } from './PolicyRow.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import ModalShell from '../../ui/ModalShell.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'

/**
 * Kaydetmeden ÖNCE "ne değişecek" özeti. Kullanıcı hangi tabloda hangi değerden hangi değere
 * geçtiğini ve bunun SONUCUNU (uzatma mı, kalıcı silme mi) görmeden kaydetmez.
 *
 * Neden kendi penceresi: showConfirm'in kutusu dar ve mesajı bir <p> içinde render ediliyor —
 * tablo geçersiz DOM üretirdi. Kabuk ui/ModalShell (shadcn Dialog): odak tuzağı, Escape, scrim;
 * kaydederken (`busy`) kapatma yolları kilitli. Kısaltma varsa pencere "danger" tonuna geçer
 * (`data-tone`), kalıcı silme uyarısı AlertBanner (role=alert) ile verilir.
 */
export default function RetentionReviewModal({ changes, onCancel, onConfirm, saving }) {
  const t = useT()
  const shortened = changes.filter(c => c.to < c.from)
  const danger = shortened.length > 0

  return (
    <ModalShell open onClose={onCancel} busy={saving} size="lg" scrollBody icon={ShieldAlert}
      title={t('ret.reviewTitle', changes.length)}
      className={cn(danger && 'border-destructive/60')}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={saving}>
            {t('app.cancel')}
          </Button>
          <Button variant={danger ? 'destructive' : 'default'} onClick={onConfirm} disabled={saving}
            aria-busy={saving || undefined}>
            {saving ? <Spinner size={15} inline decorative /> : null}
            {danger ? t('ret.reviewConfirmDanger') : t('ret.reviewConfirm')}
          </Button>
        </>
      }>
      <div className="flex flex-col gap-3" data-slot="retention-review" data-tone={danger ? 'danger' : 'info'}>
        {danger && (
          <AlertBanner tone="danger" role="alert" className="mb-0">
            {t('ret.reviewWarn', shortened.length)}
          </AlertBanner>
        )}

        <div className="overflow-hidden rounded-lg border">
          <Table>
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead>{t('ret.colTable')}</TableHead>
                <TableHead>{t('audit.diffFrom')}</TableHead>
                <TableHead><span className="sr-only">→</span></TableHead>
                <TableHead>{t('audit.diffTo')}</TableHead>
                <TableHead>{t('ret.reviewEffect')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {changes.map(c => {
                const down = c.to < c.from
                const delta = c.to - c.from
                return (
                  <TableRow key={c.id} data-direction={down ? 'down' : 'up'} className={cn(down && 'bg-destructive/5')}>
                    <TableCell className="font-mono text-xs font-semibold">
                      {c.table}
                      <span className="block font-sans text-[11px] font-normal text-muted-foreground">{t(`ret.class.${c.dataClass}`)}</span>
                    </TableCell>
                    <TableCell className="text-muted-foreground line-through decoration-muted-foreground/50">{c.from} {t('ret.daysShort')}</TableCell>
                    <TableCell className="text-muted-foreground"><ArrowRight size={13} aria-hidden="true" /></TableCell>
                    <TableCell className="font-semibold">{c.to} {t('ret.daysShort')}</TableCell>
                    <TableCell className="min-w-[180px] whitespace-normal">
                      {down
                        ? <span className="font-semibold text-destructive">
                            {t('ret.effectShorten', Math.abs(delta), fmtNum(c.purgeable ?? 0))}
                          </span>
                        : <span className="font-semibold text-success">
                            {t('ret.effectExtend', delta)}
                          </span>}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>

        <p className="text-xs text-muted-foreground">{t('ret.reviewHint')}</p>
      </div>
    </ModalShell>
  )
}
