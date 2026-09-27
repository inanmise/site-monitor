import { useId } from 'react'
import ModalShell from '../ui/ModalShell.jsx'
import ModalScrollHint from '../ui/ModalScrollHint.jsx'
import Field from '../ui/Field.jsx'
import { CheckRunningStrip } from '../ui/CheckRunning.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { UsersRound } from 'lucide-react'
import { useModalScrollHint } from '../../hooks/useModalScrollHint.js'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import {
  Field as ShadcnField, FieldContent, FieldDescription, FieldLabel, FieldTitle,
} from '@/components/shadcn/field'
import { cn } from '@/lib/utils'

/**
 * İzleme EKLE/DÜZENLE formu ailesi — dokuz izleme sayfasının form penceresi ve alan düzeni TEK kopya
 * (eski `.modal-box.modal-sticky-actions` + `.form-grid` + `.checkbox-label` + `.http-block-title`).
 *
 * <p><b>Pencere sözleşmesi (eski elle kurulu kutuyla aynı):</b> başlık ve alt eylem çubuğu SABİT, yalnız
 * gövde kayar; gövde taşınca "Devamı için kaydırın" ipucu çubuğun üstünde belirir (useModalScrollHint —
 * tek kopya burada). Örtüye tıklama ve Escape formu KAPATMAZ (emek biriken form; yanlışlıkla kenara
 * basmak yazılanların hepsini götürüyordu); kapatma yolları İptal ve başlıktaki X. Gönderim sürerken
 * (`busy`) X de kilitlidir. Meşgul evresi (Kaydediliyor… / Test ediliyor… N sn) BAŞLIKTA: alt çubuktaki
 * düğme metinleri sabit kalır, hiçbir düğme kaymaz.
 *
 * <p>Kapı: modalScroll.test.jsx (dokuz sayfanın hepsi bu pencereyi kullanır).
 */

// Genişlikler eski satır içi `maxWidth`lerle aynı (tür başına farklı); dar ekranda 1rem kenar payı.
const WIDTH = {
  640: 'sm:max-w-[min(640px,calc(100%-2rem))]',
  720: 'sm:max-w-[min(720px,calc(100%-2rem))]',
  760: 'sm:max-w-[min(760px,calc(100%-2rem))]',
  860: 'sm:max-w-[min(860px,calc(100%-2rem))]',
}

export function MonitorFormModal({
  open = true, onClose, icon, title, duplicate = false, busy = false, busyLabel = null,
  width = 720, footer, children,
}) {
  const t = useT()
  const scrollHint = useModalScrollHint()
  return (
    <ModalShell
      open={open}
      onClose={onClose}
      icon={icon}
      busy={busy}
      dismissOnBackdrop={false}
      dismissOnEscape={false}
      scrollBody
      bodyRef={scrollHint.ref}
      className={WIDTH[width] ?? WIDTH[720]}
      title={<>
        <span className="min-w-0 truncate">{title}</span>
        {duplicate && <Badge variant="secondary" data-slot="duplicate-badge" className="shrink-0">{t('mon.duplicateBadge')}</Badge>}
      </>}
      headerExtra={busyLabel
        ? <div className="ml-auto flex shrink-0 items-center"><CheckRunningStrip running label={busyLabel} /></div>
        : null}
      footer={
        <div className="relative flex w-full flex-wrap items-center justify-end gap-2">
          <ModalScrollHint show={scrollHint.show} scrollMore={scrollHint.scrollMore} />
          {footer}
        </div>
      }
    >
      {children}
    </ModalShell>
  )
}

/** İki sütunlu form ızgarası (dar ekranda tek sütun). Eski `.form-grid form-grid--top`. */
export function FormGrid({ className, children }) {
  return (
    <div data-slot="form-grid" className={cn('grid grid-cols-1 items-start gap-x-3 gap-y-3.5 sm:grid-cols-2', className)}>
      {children}
    </div>
  )
}

/**
 * Etiketli alan — `ui/Field` (shadcn Field; etiket ↔ kontrol ↔ ipucu bağı render-prop ile).
 * `full` iki sütunu kaplar. Zorunluluk yıldızı `data-slot="field-required"`.
 */
export function FormField({ full = false, className, ...props }) {
  return <Field {...props} className={cn('mb-0 min-w-0', full && 'sm:col-span-2', className)} />
}

/** Onay kutulu ayar satırı (eski `.checkbox-label`) — shadcn Field (yatay) + Checkbox + FieldLabel. */
export function CheckField({ checked, onCheckedChange, label, hint, disabled = false, full = false, className }) {
  const id = useId()
  const hintId = hint ? `${id}-h` : undefined
  return (
    <ShadcnField orientation="horizontal" role={undefined} data-disabled={disabled ? 'true' : undefined}
      className={cn('min-w-0 gap-2', hint && 'items-start', full && 'sm:col-span-2', className)}>
      <Checkbox id={id} checked={!!checked} disabled={disabled} aria-describedby={hintId}
        onCheckedChange={(v) => onCheckedChange(v === true)} className={hint ? 'mt-0.5' : undefined} />
      {hint ? (
        <FieldContent className="gap-1">
          <FieldLabel htmlFor={id} className="font-normal">{label}</FieldLabel>
          <FieldDescription id={hintId} className="text-xs">{hint}</FieldDescription>
        </FieldContent>
      ) : (
        <FieldLabel htmlFor={id} className="font-normal">{label}</FieldLabel>
      )}
    </ShadcnField>
  )
}

/**
 * Satır içi küçük alan (eski `.http-days-row`/`.kw-days-row`: "Hatırlatma günleri [30,14,7]") —
 * shadcn Field (yatay) + FieldLabel; kontrol render-prop ile `{ id }` alır. Onay kutusunun altında
 * girintili durur.
 */
export function InlineField({ label, className, children }) {
  const id = useId()
  return (
    <ShadcnField orientation="horizontal" role={undefined}
      className={cn('ml-6 w-auto self-start gap-2 text-[13px] text-muted-foreground', className)}>
      <FieldLabel htmlFor={id} className="flex-none font-normal">{label}</FieldLabel>
      {children({ id })}
    </ShadcnField>
  )
}

/** Zorunluluk yıldızı — i18n metnine GÖMÜLMEZ (ui/Field ile aynı kanca). */
export function RequiredMark() {
  return <span data-slot="field-required" className="text-destructive">*</span>
}

/**
 * Başlıklı alan grubu (eski `.http-block-title` + çerçeveli blok `.http-*-section`/`.sc-tpl-block`).
 * Izgarada iki sütunu kaplar. `hint` başlığın altındaki açıklama, `action` başlık satırının sağındaki
 * araçlar (ör. kopyala düğmesi), `boxed` çerçeveli zemin.
 *
 * Neden `<fieldset>/<legend>` DEĞİL: çerçeveli bir fieldset'te tarayıcı legend'ı ÇERÇEVE ÇİZGİSİNİN
 * ÜSTÜNE oturtur (Tailwind preflight yok) — başlık kutunun kenarını keserdi. Aynı anlam `role="group"`
 * + `aria-labelledby` ile verilir; görünüm shadcn Field ailesinin (FieldTitle/FieldDescription).
 */
export function FormSection({ title, icon: Icon, required = false, hint, action, boxed = true, className, children }) {
  const titleId = useId()
  return (
    <div role="group" aria-labelledby={titleId} data-slot="form-section"
      className={cn('flex min-w-0 flex-col gap-2 sm:col-span-2', boxed && 'rounded-lg border bg-muted/30 px-3.5 py-3', className)}>
      <div className="flex w-full flex-wrap items-center gap-1.5">
        <FieldTitle id={titleId} className="w-auto gap-1.5 font-semibold">
          {Icon && <Icon size={15} aria-hidden="true" className="shrink-0" />}
          {title}
          {required && <RequiredMark />}
        </FieldTitle>
        {action && <span className="ml-auto flex items-center gap-2">{action}</span>}
      </div>
      {hint && <FieldDescription className="text-xs">{hint}</FieldDescription>}
      {children}
    </div>
  )
}

/** Serbest açıklama satırı (eski `.field-hint`). `full` iki sütunu kaplar; `tone="warn"` uyarı rengi. */
export function FormHint({ full = true, tone, className, children }) {
  return (
    // Uyarı tonu: `text-warning` (#f59e0b) beyaz zeminde 2.1:1 kontrast veriyordu → amber-700 / koyu temada amber-300.
    <p className={cn('text-xs text-muted-foreground', tone === 'warn' && 'text-amber-700 dark:text-amber-300', full && 'sm:col-span-2', className)}>
      {children}
    </p>
  )
}

/**
 * Takımsız kullanıcı uyarısı (2026-09-26, kullanıcı isteği "USER rolü izleme ekleyebilmeli"): hiçbir takıma üye
 * olmayan kullanıcı için form eskiden SESSİZCE kilitliydi (takım alanı "Takımsız", Kaydet gri, neden yazmıyordu).
 * Kaydet kapalı kalır (sunucu takımsız izlemeyi reddeder) ama sebep ve çözüm yolu açıkça yazılır.
 */
export function FormNoTeamAlert({ className }) {
  const t = useT()
  return (
    <AlertBanner tone="warning" icon={UsersRound} title={t('mon.noTeamTitle')} className={className}>
      {t('mon.noTeamAlert')}
    </AlertBanner>
  )
}

/**
 * GEÇİŞ SARMALAYICISI — kendi `<label>`'ını çizen, henüz shadcn Field'a taşınmamış ortak alanlar
 * (MonitorProxyField, NotificationGroupSelect) eskiden `.form-grid label` kuralından dikey düzen
 * alıyordu. Izgara artık Tailwind; aynı düzen burada (katmanlı yardımcılar) verilir. O bileşenler
 * Field'a taşınınca bu sarmalayıcının seçicileri boşa düşer, zararsızdır.
 */
export function LabelSlot({ full = false, className, children }) {
  return (
    <div className={cn(
      'min-w-0 [&>label]:flex [&>label]:min-w-0 [&>label]:flex-col [&>label]:gap-1.5 [&>label]:text-sm [&>label]:font-medium',
      full && 'sm:col-span-2', className)}>
      {children}
    </div>
  )
}
