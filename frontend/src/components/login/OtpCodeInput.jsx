import { forwardRef, useCallback, useState } from 'react'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'

export const OTP_LENGTH = 6

/** Yapıştırılan / yazılan metinden yalnız rakamlar, en çok 6 (boşluk, tire, "Kod: 123 456" gibi süsler atılır). */
export function sanitizeOtp(raw) {
  return String(raw ?? '').replace(/[^0-9]/g, '').slice(0, OTP_LENGTH)
}

/**
 * 6 kutulu tek kullanımlık kod girişi (2026-10-02, kodla giriş). Projede `input-otp` paketi YOK (shadcn InputOTP onu
 * ister — yeni bağımlılık kullanıcı kararıdır): TEK gerçek shadcn `Input` (görünmez, kutuların tamamını kaplar —
 * dokunma hedefi kutuların hepsi) + altında 6 görsel kutu. Böylece tarayıcının ve işletim sisteminin
 * `autocomplete="one-time-code"` önerisi, yapıştırma, ok tuşları (imleç), geri silme ve ekran okuyucu TEK bir alanla
 * doğal çalışır; kutular yalnız görüntüdür (`aria-hidden`).
 *
 * - `inputMode="numeric"` + `pattern` → telefonda rakam klavyesi; yazı boyutu 16 px (iOS yakınlaştırmasın).
 * - Rakam dışı karakterler atılır; 6 rakam tamamlanınca `onComplete(kod)` çağrılır (otomatik doğrulama).
 * - Etkin kutu = imlecin bulunduğu konum (odaktayken); hata durumunda kutular kırmızı kenar alır.
 *
 * Test kancaları: `data-slot="otp-input"` (kök), `otp-box` (`data-active`, `data-filled`), giriş `aria-label`.
 */
const OtpCodeInput = forwardRef(function OtpCodeInput(
  { value, onChange, onComplete, disabled = false, invalid = false, label, describedBy, id, autoFocus = false, className },
  ref,
) {
  const [focused, setFocused] = useState(false)
  const [caret, setCaret] = useState(0)
  const digits = sanitizeOtp(value)

  const syncCaret = useCallback((el) => {
    if (!el) return
    const pos = typeof el.selectionStart === 'number' ? el.selectionStart : digits.length
    setCaret(Math.min(pos, OTP_LENGTH - 1))
  }, [digits.length])

  function handleChange(e) {
    const next = sanitizeOtp(e.target.value)
    onChange?.(next)
    setCaret(Math.min(next.length, OTP_LENGTH - 1))
    if (next.length === OTP_LENGTH && next !== digits) onComplete?.(next)
  }

  // Yapıştırma: panodaki metin ("Kod: 123 456", "123-456") süzülür; tarayıcının kendi eklemesi engellenir ki imleç
  // konumuna göre karışık bir değer oluşmasın.
  function handlePaste(e) {
    const next = sanitizeOtp(e.clipboardData?.getData('text'))
    if (!next) return
    e.preventDefault()
    onChange?.(next)
    setCaret(Math.min(next.length, OTP_LENGTH - 1))
    if (next.length === OTP_LENGTH) onComplete?.(next)
  }

  const activeIndex = focused ? Math.min(Math.max(caret, 0), OTP_LENGTH - 1) : -1

  return (
    <div data-slot="otp-input" className={cn('relative w-full max-w-xs', className)}>
      <div aria-hidden="true" className="grid grid-cols-6 gap-1.5 sm:gap-2">
        {Array.from({ length: OTP_LENGTH }, (_, i) => {
          const ch = digits[i] || ''
          const active = i === activeIndex
          return (
            <div key={i} data-slot="otp-box" data-active={active ? 'true' : undefined} data-filled={ch ? 'true' : undefined}
              className={cn(
                'flex h-12 min-w-0 items-center justify-center rounded-md border bg-background font-mono text-xl font-semibold tabular-nums text-foreground shadow-xs transition-[box-shadow,border-color]',
                active && 'border-ring ring-[3px] ring-ring/40',
                invalid && 'border-destructive',
                invalid && active && 'ring-destructive/30',
                disabled && 'opacity-50',
              )}>
              {ch}
              {active && !ch && <span className="h-6 w-px animate-pulse bg-foreground motion-reduce:animate-none" />}
            </div>
          )
        })}
      </div>
      <Input ref={ref} id={id} value={digits} onChange={handleChange} onPaste={handlePaste}
        onFocus={(e) => { setFocused(true); syncCaret(e.target) }}
        onBlur={() => setFocused(false)}
        onSelect={(e) => syncCaret(e.target)}
        onKeyUp={(e) => syncCaret(e.target)}
        disabled={disabled}
        autoFocus={autoFocus}
        inputMode="numeric"
        autoComplete="one-time-code"
        pattern="[0-9]*"
        spellCheck={false}
        aria-label={label}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        name="one-time-code"
        data-slot="otp-input-field"
        className="absolute inset-0 h-full w-full cursor-text border-0 bg-transparent text-transparent caret-transparent opacity-0 shadow-none selection:bg-transparent focus-visible:ring-0" />
    </div>
  )
})

export default OtpCodeInput
