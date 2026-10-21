import { useRef, useState } from 'react'
import { X } from 'lucide-react'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'
import { splitTokens } from './usernamePrefixModel.js'

/** Sınır aşıldı sorunu: `onEntryError({ key: TOO_MANY, params: [max] })` — çağıran alana özgü metne çevirir. */
export const TOO_MANY = 'tooMany'

/**
 * Kısa değer çip girişi (2026-10-21, kullanıcı adı ön eki kartı: izinli ön ekler + muaf kullanıcı adları).
 *
 * Yazıp Enter / virgül / noktalı virgül / boşluk / odak kaybı ya da bir liste YAPIŞTIRMA → her parça `validate` ile denetlenir;
 * geçerliler `normalize` edilip çip olur (tekrar sessizce atlanmaz: `onEntryError(null)` + liste değişmez), İLK geçersiz parça
 * kutuda kalır ve `onEntryError(ileti)` alanın altında gösterilir (çağıran `Field error`'a bağlar). `max` dolunca fazlası da
 * kutuda kalır ve `onEntryError({ key: TOO_MANY, params: [max] })` gelir (hiçbir parça sessizce düşmez). Denetimli: `values` →
 * `onChange(values)`. Çipler shadcn Badge (ad SARAR, kesilmez — `rounded-md whitespace-normal`), kaldır düğmeleri Button
 * (dokunmatikte ≥ 40 px vuruş alanı), giriş shadcn Input. Test kancaları: `data-slot="token-chips" | "token-chip"`.
 */
export default function TokenChipsInput({
  id, values = [], onChange, validate, normalize = (s) => s, onEntryError, removeLabel, placeholder, describedBy, invalid,
  disabled = false, max = 50, slot = 'token-chips', inputMode = 'text',
}) {
  const [text, setText] = useState('')
  const inputRef = useRef(null)

  /** Metni listeye katar; ilk geçersiz parça kutuda kalır. */
  function commit(raw) {
    const parts = splitTokens(raw)
    if (parts.length === 0) { setText(''); return }
    const next = [...values]
    let bad = null
    const rest = []
    for (const p of parts) {
      if (bad) { rest.push(p); continue }
      const problem = validate ? validate(p) : null
      if (problem) { bad = problem; rest.push(p); continue }
      const v = normalize(p)
      if (next.includes(v)) continue
      // Sınır dolu (inceleme 2026-10-21): fazlası SESSİZCE düşmez — kutuda kalır ve alanın altında söylenir
      if (next.length >= max) { bad = { key: TOO_MANY, params: [max] }; rest.push(p); continue }
      next.push(v)
    }
    if (next.length !== values.length) onChange(next)
    onEntryError?.(bad)
    setText(rest.join(' '))
  }

  const remove = (v) => {
    onChange(values.filter((x) => x !== v))
    requestAnimationFrame(() => inputRef.current?.focus())
  }

  return (
    <div data-slot={slot} aria-invalid={invalid || undefined}
      className={cn('flex min-h-10 w-full min-w-0 flex-wrap items-center gap-1.5 rounded-md border border-input px-2 py-1.5 shadow-xs transition-[color,box-shadow] dark:bg-input/30',
        'focus-within:border-ring focus-within:ring-[3px] focus-within:ring-ring/50',
        'aria-[invalid=true]:border-destructive aria-[invalid=true]:ring-destructive/20',
        disabled && 'pointer-events-none opacity-50')}>
      {values.map((v) => (
        <Badge key={v} variant="secondary" data-slot="token-chip" data-value={v}
          className="h-auto min-h-8 max-w-full gap-0.5 rounded-md py-0.5 pr-0.5 pl-2.5 text-sm font-medium whitespace-normal sm:min-h-7">
          <span className="min-w-0 [overflow-wrap:anywhere]">{v}</span>
          {!disabled && (
            <Button type="button" variant="ghost" size="icon-xs" aria-label={removeLabel ? removeLabel(v) : v}
              className="relative size-7 shrink-0 rounded-full text-muted-foreground hover:bg-foreground/10 sm:size-6 pointer-coarse:after:absolute pointer-coarse:after:-inset-1.5 sm:pointer-coarse:after:-inset-2"
              onClick={() => remove(v)}>
              <X aria-hidden="true" />
            </Button>
          )}
        </Badge>
      ))}
      <Input ref={inputRef} id={id} type="text" inputMode={inputMode} autoComplete="off" autoCapitalize="characters"
        spellCheck={false} value={text} disabled={disabled} aria-describedby={describedBy} aria-invalid={invalid || undefined}
        placeholder={placeholder}
        className="h-10 min-w-[12ch] flex-1 border-0 bg-transparent px-1 shadow-none sm:h-8 sm:pointer-coarse:h-10 focus-visible:ring-0 aria-invalid:ring-0 dark:bg-transparent"
        onChange={(e) => {
          const v = e.target.value
          onEntryError?.(null)
          if (/[,;\s]/.test(v)) commit(v); else setText(v)
        }}
        onPaste={(e) => {
          const pasted = e.clipboardData?.getData('text') ?? ''
          if (!/[,;\s]/.test(pasted.trim())) return
          e.preventDefault()
          commit(`${text} ${pasted}`)
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit(text) }
          else if (e.key === 'Backspace' && !text && values.length) remove(values[values.length - 1])
        }}
        onBlur={() => { if (text.trim()) commit(text) }} />
    </div>
  )
}
