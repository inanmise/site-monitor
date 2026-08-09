import { useId } from 'react'

/**
 * Form alanı sarmalayıcısı — etiket ↔ kontrol bağını, ipucunu ve alan hatasını TEK yerde kurar.
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
 *       <textarea id={id} aria-describedby={describedBy} aria-invalid={invalid} className="input" … />
 *     )}
 *   </Field>
 *
 * Zorunluluk yıldızı i18n metnine GÖMÜLMEZ; ayrı bir <span className="req-star"> olarak eklenir.
 */
export function FieldHint({ tone = 'muted', id, children }) {
  return (
    <span className={`field-hint${tone === 'warn' ? ' field-hint--warn' : ''}`} id={id}>
      {children}
    </span>
  )
}

/**
 * Alan-bazlı hata metni. Bilinçli olarak role="alert" TAŞIMAZ: kontrol zaten
 * aria-invalid + aria-describedby ile bu metne bağlı, ekran okuyucu odaklanınca okur.
 * Her alana bir alert koymak formu duyuru gürültüsüne boğardı.
 */
export function FieldError({ id, children }) {
  return <span className="field-error" id={id}>{children}</span>
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

  return (
    <div className={['form-field', className].filter(Boolean).join(' ')}>
      {label && (
        <label className="form-field-label" htmlFor={id}>
          {label}{required && <> <span className="req-star">*</span></>}
        </label>
      )}
      {control}
      {hint && <FieldHint id={hintId} tone={hintTone}>{hint}</FieldHint>}
      {error && <FieldError id={errorId}>{error}</FieldError>}
    </div>
  )
}
