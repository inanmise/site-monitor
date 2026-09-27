import { Save, Undo2, ListChecks, X, CheckCircle2 } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Sheet, SheetClose, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { cn } from '@/lib/utils'
import { ROLES, actionOf } from './permissionModel.js'

const pendingText = (t, n) => (n === 1 ? t('perm.pendingOne') : t('perm.pendingN', n))

/** Kaydet düğmesi — kaydederken spinner + aria-busy; metin sabit (düğme adı değişmez). */
function SaveButton({ saving, disabled, onSave, className }) {
  const t = useT()
  return (
    <Button type="button" onClick={onSave} disabled={disabled || !!saving} aria-busy={!!saving || undefined} className={className}>
      {saving ? <Spinner decorative /> : <Save aria-hidden="true" />}
      {t('perm.save')}
    </Button>
  )
}
export { SaveButton }

/** Kaydetme ilerlemesi (toplu kaydetme hücre başına sıralı PUT — N/M). */
function SavingProgress({ saving, className }) {
  const t = useT()
  if (!saving) return null
  return (
    <div className={cn('min-w-0', className)} role="status" aria-live="polite">
      <ProgressBar value={saving.done} max={saving.total} size="sm" label={t('perm.saving', saving.done, saving.total)} />
    </div>
  )
}

/**
 * Yüzen alt çubuk — "N değişiklik · Vazgeç · Gözden geçir · Kaydet". Yalnız kaydedilmemiş değişiklik varken (ya da
 * kaydederken) görünür.
 *
 * <p>NEDEN `fixed`, `sticky` değil: uygulamanın `.app-main` kabı `overflow-y: auto` taşıyor ama yüksekliği SINIRSIZ —
 * belge kayıyor, `main` hiç kaymıyor. `sticky bottom-0` en yakın taşma kabına (main) göre çalıştığı için çubuk hiç
 * yapışmıyor, sayfanın en altında kalıyordu (Playwright ölçümü 2026-09-27). Telefonda tam genişlik alt kenar
 * (güvenli alan boşluklu, yardım düğmesinin üstünde); geniş ekranda bileşenin KENDİ kutusuna hizalı (`box`, ölçülür) ve
 * sağ alttaki yardım düğmesine değmez. Telefonda iki satır: bilgi + Vazgeç / Gözden geçir + Kaydet (40 px).
 */
export function ChangesBar({ count, failedCount, saving, onReview, onDiscard, onSave, phone, box }) {
  const t = useT()
  return (
    <div data-slot="perm-changes-bar" role="region" aria-label={t('perm.changesRegion')}
      style={!phone && box ? { left: box.left, width: box.width } : undefined}
      className={cn('fixed z-[950] flex flex-wrap items-center gap-2 border bg-card/95 p-3 shadow-lg backdrop-blur print:hidden',
        phone
          ? 'inset-x-0 bottom-0 rounded-t-xl border-x-0 border-b-0 pb-[max(0.75rem,env(safe-area-inset-bottom))]'
          : cn('bottom-4 gap-3 rounded-xl', !box && 'inset-x-4'))}>
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2 text-sm">
        <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-amber-500" />
        <strong className="font-semibold" aria-live="polite">{pendingText(t, count)}</strong>
        {failedCount > 0 && <ToneBadge tone="danger" className="rounded-full">{t('perm.failedCount', failedCount)}</ToneBadge>}
      </div>
      <Button type="button" variant="ghost" className="h-10 px-3 md:h-9" onClick={onDiscard} disabled={!!saving}>
        <X aria-hidden="true" />{t('perm.discard')}
      </Button>
      <div className={cn('flex gap-2', phone && 'grid w-full grid-cols-2')}>
        <Button type="button" variant="outline" className="h-10 min-w-0 md:h-9" onClick={onReview}>
          <ListChecks aria-hidden="true" />{t('perm.review')}
        </Button>
        <SaveButton saving={saving} onSave={onSave} className="h-10 min-w-0 md:h-9" />
      </div>
      <SavingProgress saving={saving} className="basis-full" />
    </div>
  )
}

/**
 * Gözden geçirme paneli (Sheet): her değişiklik — rol · kaynak · tür · eski → yeni, hassas rozeti, tek tek GERİ AL.
 * Kısmi hata: kaydedilemeyenler listede kalır, satırda sunucunun mesajı. Masaüstünde sağdan, telefonda alttan.
 */
export function ReviewSheet({ open, onOpenChange, phone, changes, failures, saving, onUndo, onSave }) {
  const t = useT()
  const failedCount = changes.filter((c) => failures.has(c.key)).length
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      {/* z-[960]: sağ alttaki yardım düğmesi (z 900) panelin Kaydet düğmesini örtmesin (Playwright 2026-09-27) */}
      <SheetContent side={phone ? 'bottom' : 'right'} showCloseButton={false} data-slot="perm-review"
        className={cn('z-[960] gap-0 p-0', phone ? 'max-h-[88dvh] rounded-t-2xl' : 'w-full sm:max-w-md')}>
        <SheetHeader className="flex-row items-start gap-3 border-b p-4">
          <div className="flex min-w-0 flex-1 flex-col gap-1">
            <SheetTitle className="text-base">{t('perm.reviewTitle')}</SheetTitle>
            <SheetDescription>{t('perm.reviewDesc')}</SheetDescription>
          </div>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-1 shrink-0" aria-label={t('app.close')}>
              <X aria-hidden="true" />
            </Button>
          </SheetClose>
        </SheetHeader>

        {failedCount > 0 && (
          <div className="px-4 pt-3">
            <AlertBanner tone="danger" title={t('perm.saveFailedTitle', failedCount, changes.length)} className="mb-0">
              {t('perm.saveFailedText')}
            </AlertBanner>
          </div>
        )}

        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-3">
          {changes.length === 0 ? (
            <StatusBlock tone="success" icon={CheckCircle2} title={t('perm.reviewEmpty')} className="py-8" />
          ) : (
            <ul className="flex flex-col gap-2" aria-label={t('perm.reviewTitle')}>
              {changes.map((c) => {
                const action = actionOf(c.action)
                const role = ROLES.find((r) => r.key === c.role)
                const label = t('perm.cellLabel', c.role, c.resource_key, t(action.labelKey))
                const err = failures.get(c.key)
                return (
                  <li key={c.key} data-slot="perm-change" data-key={c.key} data-failed={err ? 'true' : undefined}
                    className={cn('flex items-start gap-3 rounded-lg border p-3', err && 'border-destructive/50 bg-destructive/5')}>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Badge variant="outline" className="gap-1.5 font-semibold">
                          <span aria-hidden="true" className={cn('size-2 rounded-full', role?.dot)} />{c.role}
                        </Badge>
                        <span className="font-mono text-[0.82rem] font-semibold break-all">{c.resource_key}</span>
                      </div>
                      <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                        <span className="inline-flex items-center gap-1"><action.Icon aria-hidden="true" className="size-3.5" />{t(action.labelKey)}</span>
                        <span aria-hidden="true">·</span>
                        <StateBadge on={!c.next} />
                        <span aria-hidden="true">→</span>
                        <span className="sr-only">{t('perm.becomes')}</span>
                        <StateBadge on={c.next} />
                        {c.sensitive && <Badge variant="warning" className="rounded-full">{t('perm.sensitiveBadge')}</Badge>}
                      </div>
                      {err && <p className="mt-1.5 text-xs text-destructive">{t('perm.failed')}: {err}</p>}
                    </div>
                    <Button type="button" variant="ghost" size="sm" className="h-10 shrink-0 sm:h-8" disabled={!!saving}
                      aria-label={t('perm.undoLabel', label)} onClick={() => onUndo(c.key)}>
                      <Undo2 aria-hidden="true" /><span>{t('perm.undo')}</span>
                    </Button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>

        <SheetFooter className="mt-0 gap-2 border-t p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <SavingProgress saving={saving} />
          <div className="flex gap-2 [&>*]:flex-1">
            <SheetClose asChild>
              <Button type="button" variant="outline" className="h-10">{t('perm.keepEditing')}</Button>
            </SheetClose>
            <SaveButton saving={saving} disabled={changes.length === 0} onSave={onSave} className="h-10" />
          </div>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}

function StateBadge({ on }) {
  const t = useT()
  return on
    ? <ToneBadge tone="success" className="rounded-full">{t('perm.stateOn')}</ToneBadge>
    : <ToneBadge tone="muted" className="rounded-full">{t('perm.stateOff')}</ToneBadge>
}
