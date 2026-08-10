import { useState, useRef, useEffect } from 'react'
import { Copy, Check } from 'lucide-react'
import { copyText } from '../../utils/copyText.js'

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
 * Saf sunum: etiketler prop olarak gelir.
 */
export default function CopyButton({ value, label, copiedLabel, className = 'btn btn-sm', size = 13 }) {
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)

  useEffect(() => () => clearTimeout(timer.current), [])

  async function onCopy() {
    if (!(await copyText(value))) return   // sessiz: kullanıcı metni elle seçebilir
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 2000)
  }

  const text = copied ? copiedLabel : label
  return (
    <button type="button" className={className} onClick={onCopy} aria-label={text} title={text}>
      {copied ? <Check size={size} /> : <Copy size={size} />}
    </button>
  )
}
