import { useState, useRef, useEffect } from 'react'
import { Copy, Check } from 'lucide-react'
import { copyText } from '../../utils/copyText.js'

/**
 * Kopyalanabilir referans/kimlik değeri — "LIR-2026-000012" gibi kullanıcının bir yere
 * yazması gereken kodlar için. Değer TEK bir metin düğümünde durur; kopya butonu ayrı bir
 * elemandır (metin sorguları ve seçim değeri bölmez).
 *
 * Toast KULLANMAZ: ErrorBoundary fallback'inde de çalışması gerekiyor ve useToast() provider
 * yoksa throw ediyor. Geri bildirim yerinde verilir (ikon 2 sn onaya döner).
 *
 * Saf sunum: etiketler prop olarak gelir.
 */
export default function CopyableRef({ value, copyLabel, copiedLabel, className = '' }) {
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)

  useEffect(() => () => clearTimeout(timer.current), [])

  async function onCopy() {
    const ok = await copyText(value)
    if (!ok) return                     // sessiz: kullanıcı metni elle seçebilir
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 2000)
  }

  return (
    <span className={['copy-ref', className].filter(Boolean).join(' ')}>
      <code className="copy-ref-value">{value}</code>
      <button
        type="button"
        className="copy-ref-btn"
        onClick={onCopy}
        aria-label={copied ? copiedLabel : copyLabel}
        title={copied ? copiedLabel : copyLabel}
      >
        {copied ? <Check size={13} /> : <Copy size={13} />}
      </button>
    </span>
  )
}
