import { useState, useRef, useEffect } from 'react'
import { Copy, Check } from 'lucide-react'
import { copyText } from '../../utils/copyText.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Kopyalanabilir referans/kimlik değeri — "LIR-2026-000012" gibi kullanıcının bir yere
 * yazması gereken kodlar için. Değer TEK bir metin düğümünde durur; kopya butonu ayrı bir
 * elemandır (metin sorguları ve seçim değeri bölmez). Düğme shadcn Button (ghost, ikon).
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
    <span data-slot="copyable-ref" className={cn('inline-flex items-center gap-1 align-middle', className)}>
      <code className="rounded-sm bg-muted px-1.5 py-px font-mono text-[.95em] font-bold text-foreground">{value}</code>
      <Button
        type="button"
        variant="ghost"
        size="icon-xs"
        className="text-muted-foreground hover:text-primary"
        onClick={onCopy}
        aria-label={copied ? copiedLabel : copyLabel}
        title={copied ? copiedLabel : copyLabel}
      >
        {copied ? <Check size={13} /> : <Copy size={13} />}
      </Button>
    </span>
  )
}
