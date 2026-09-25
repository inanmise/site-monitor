import { useId } from 'react'
import {
  Field as ShadcnField,
  FieldLabel,
  FieldDescription,
  FieldError as ShadcnFieldError,
} from '@/components/shadcn/field'
import { cn } from '@/lib/utils'

/**
 * Form alanı sarmalayıcısı — etiket ↔ kontrol bağını, ipucunu ve alan hatasını TEK yerde kurar.
 * Çizim shadcn Field (FieldLabel / FieldDescription / FieldError).
 *
 * Neden var: projedeki admin formlarında <label>'ların çoğu ne htmlFor taşıyor ne de kontrolü
 * sarıyor; ekran okuyucu için alanların erişilebilir adı yok. Ayrıca ipuçları görsel olarak
 * bağlı ama aria-describedby ile bağlı değil.
 *
 * Bağlar `children` RENDER-PROP'u ile verilir, cloneElement ile DEĞİL: cloneElement sarmalayıcı
 * bir <div> ya da <>…</> ile karşılaştığında prop'ları sessizce yanlış elemana yapıştırır ve
 * hata hiçbir yerde görünmez. Render-prop çağıranı bağları nereye koyacağını söylemeye zorlar.
 *
 *   <Field label={t('issue.describe')} required error={errors.message}>
 *     {({ id, describedBy, invalid }) => (
 *       <Textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} … />
 *     )}
 *   </Field>
 *
 * Zorunluluk yıldızı i18n metnine GÖMÜLMEZ; etiketin içinde ayrı bir eleman olarak eklenir
 * (`data-slot="field-required"`).
 */
export function FieldHint({ tone = 'muted', id, children }) {
  return (
    <FieldDescription id={id} className={cn('text-xs [overflow-wrap:break-word]', tone === 'warn' && 'text-warning')}>
      {children}
    </FieldDescription>
  )
}

/**
 * Alan-bazlı hata metni. Bilinçli olarak role="alert" TAŞIMAZ (shadcn FieldError varsayılanı
 * ezilir): kontrol zaten aria-invalid + aria-describedby ile bu metne bağlı, ekran okuyucu
 * odaklanınca okur. Her alana bir alert koymak formu duyuru gürültüsüne boğardı.
 */
export function FieldError({ id, children }) {
  return <ShadcnFieldError id={id} role={undefined} className="text-xs">{children}</ShadcnFieldError>
}

export default function Field({
  label, required = false, hint, hintTone, error, className = '', children,
}) {
  const base = useId()
  const id = `${base}-c`
  const hintId = hint ? `${base}-h` : null
  const errorId = error ? `${base}-e` : null
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined

  let control
  if (typeof children === 'function') {
    control = children({ id, describedBy, invalid: error ? true : undefined })
  } else {
    if (import.meta.env?.DEV) {
      console.warn('[Field] children bir fonksiyon olmalı — aksi halde etiket/ipucu bağları kurulamaz.')
    }
    control = children
  }

  // role: shadcn Field kökü role="group" basar; burada tek bir kontrolü saran alan için grup rolü
  // (adsız) ekran okuyucuya boş bir "grup" duyurusu ekler — eski sarmalayıcıda rol yoktu.
  // mb: eski .form-field alt boşluğu (formlar alanları üst üste dizerken ona güveniyor).
  return (
    <ShadcnField role={undefined} data-invalid={error ? true : undefined}
      className={cn('mb-3.5 gap-1.5', className)}>
      {label && (
        <FieldLabel htmlFor={id} className="gap-1 font-semibold">
          {label}{required && <> <span data-slot="field-required" className="text-destructive">*</span></>}
        </FieldLabel>
      )}
      {control}
      {hint && <FieldHint id={hintId} tone={hintTone}>{hint}</FieldHint>}
      {error && <FieldError id={errorId}>{error}</FieldError>}
    </ShadcnField>
  )
}
