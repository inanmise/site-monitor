import { useState, useRef, useEffect } from 'react'
import { Copy, Check } from 'lucide-react'
import { copyText } from '../../utils/copyText.js'
import { Button, buttonVariants } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Keyfi bir metni kopyalayan salt-ikon buton.
 *
 * <p>{@link CopyableRef} değeri EKRANDA da gösterir (referans numarası gibi kısa kodlar için);
 * bu ise uzun metinler (k6 çıktısı, stack trace) için sadece butondur.
 *
 * Toast KULLANMAZ — geri bildirim yerinde verilir (ikon 2 sn onaya döner). Gerekçe: useToast()
 * provider yoksa throw ediyor ve bu buton hata yüzeylerinde de kullanılıyor; kopyalama çoğu zaman
 * kullanıcının bozuk bir ekrandan bilgi çıkarmasının tek yolu, yeni bir çökme riski taşımamalı.
 *
 * Saf sunum: etiketler prop olarak gelir. Görünüm shadcn Button: `variant` / `buttonSize`
 * (`size` geriye uyum için İKON pikselidir).
 */
export default function CopyButton({ value, label, copiedLabel, className, variant = 'outline', buttonSize = 'icon-sm', size = 13, as = 'button' }) {
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)

  useEffect(() => () => clearTimeout(timer.current), [])

  async function onCopy(e) {
    // `as="span"`: kapsayıcı bir <button>/satır başlığının içindeyiz — tıklama onu tetiklemesin.
    if (e && as === 'span') e.stopPropagation()
    if (!(await copyText(value))) return   // sessiz: kullanıcı metni elle seçebilir
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 2000)
  }

  const text = copied ? copiedLabel : label
  const icon = copied ? <Check size={size} /> : <Copy size={size} />
  // as="span": <button> içinde <button> geçersiz DOM'dur (validateDOMNesting — CertHealthPanel satır
  // başlığındaki cipher çipi, 2026-09-11 QA ISSUE-003). TeamBadge ile aynı desen: span role=button,
  // Enter/Space, stopPropagation.
  if (as === 'span') {
    return (
      <span role="button" tabIndex={0} className={cn(buttonVariants({ variant, size: buttonSize }), className)} onClick={onCopy} aria-label={text} title={text}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); e.stopPropagation(); onCopy(e) } }}>
        {icon}
      </span>
    )
  }
  return (
    <Button type="button" variant={variant} size={buttonSize} className={className} onClick={onCopy} aria-label={text} title={text}>
      {icon}
    </Button>
  )
}
