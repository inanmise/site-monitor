import { useRef, useState } from 'react'
import { AlertCircle, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { cn } from '@/lib/utils'
import { MAX_EMAILS, parseEmailInput } from './nocModel.js'

/**
 * Çoklu e-posta çip girişi (7/24 grupları, 2026-09-27).
 *
 * <p>Yazıp Enter / virgül / noktalı virgül / odak kaybı ya da bir liste YAPIŞTIRMA (virgül, noktalı virgül, satır
 * sonu; Outlook'un `Kişi A <kisi-a@example.com>; …` biçimi de) → her aday doğrulanır, küçük harfe çevrilir, tekilleşir.
 * Geçerli adresler çip olur; geçersizler kırmızı çip olarak kalır ve altta HER BİRİ için ayrı hata satırı çizilir —
 * kullanıcı çipe basıp adresi kutuya geri alır, düzeltir. Yinelenenler sessizce yutulmaz: "N yinelenen atlandı"
 * (kibar duyuru).
 *
 * <p>Denetimli: `emails` (geçerli, normalize) + `invalid` (yazıldığı gibi) → `onChange({ emails, invalid })`.
 * Giriş kutusu shadcn Input (telefonda 16 px — iOS yakınlaştırmasın); çipler Badge, kaldır düğmeleri Button.
 * Test kancaları: `data-slot="email-chips" | "email-chip" (data-invalid) | "email-errors" | "email-notice"`.
 */
export default function EmailChipsInput({
  id, emails = [], invalid = [], onChange, disabled = false, describedBy, ariaInvalid, placeholder,
}) {
  const t = useT()
  const [text, setText] = useState('')
  const [notice, setNotice] = useState('')
  const inputRef = useRef(null)

  /** Metni mevcut listeye katar → yeni değer + yinelenen sayısı (saf; `onChange` çağırmaz). */
  function merged(raw, baseInvalid) {
    const r = parseEmailInput(String(raw ?? ''), emails)
    const nextInvalid = [...baseInvalid]
    for (const x of r.invalid) if (!nextInvalid.includes(x)) nextInvalid.push(x)
    return { value: { emails: [...emails, ...r.added], invalid: nextInvalid }, duplicates: r.duplicates }
  }

  function commit(raw) {
    const src = String(raw ?? '')
    if (!src.trim()) return
    const { value, duplicates } = merged(src, invalid)
    onChange(value)
    setNotice(duplicates > 0 ? t('noc.dupSkipped', duplicates) : '')
    setText('')
  }

  const removeEmail = (e) => onChange({ emails: emails.filter((x) => x !== e), invalid })
  const removeInvalid = (e) => onChange({ emails, invalid: invalid.filter((x) => x !== e) })
  /**
   * Geçersiz çipi kutuya geri al (düzeltmek için); kutudaki yarım metin önce işlenir. TEK `onChange`: iki ayrı çağrı
   * (önce işle, sonra çipi çıkar) ikincisinde bayat `emails` taşıyıp yazılan adresi siliyordu.
   */
  function editInvalid(e) {
    const rest = invalid.filter((x) => x !== e)
    if (text.trim()) {
      const { value, duplicates } = merged(text, rest)
      onChange({ ...value, invalid: value.invalid.filter((x) => x !== e) })
      setNotice(duplicates > 0 ? t('noc.dupSkipped', duplicates) : '')
    } else {
      onChange({ emails, invalid: rest })
    }
    setText(e)
    requestAnimationFrame(() => inputRef.current?.focus())
  }

  const over = emails.length > MAX_EMAILS
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <div data-slot="email-chips" aria-invalid={ariaInvalid || undefined}
        className={cn('flex min-h-10 w-full min-w-0 flex-wrap items-center gap-1.5 rounded-md border border-input px-2 py-1.5 shadow-xs transition-[color,box-shadow] dark:bg-input/30',
          'focus-within:border-ring focus-within:ring-[3px] focus-within:ring-ring/50',
          'aria-[invalid=true]:border-destructive aria-[invalid=true]:ring-destructive/20',
          disabled && 'pointer-events-none opacity-50')}>
        {emails.map((e) => (
          <Badge key={e} variant="secondary" data-slot="email-chip"
            className="h-8 max-w-full gap-0.5 py-0 pr-0.5 pl-2.5 font-normal sm:h-7">
            <span className="min-w-0 truncate" title={e}>{e}</span>
            {!disabled && (
              <Button type="button" variant="ghost" size="icon-xs" aria-label={t('noc.removeEmail', e)}
                className="relative size-7 rounded-full text-muted-foreground hover:bg-foreground/10 sm:size-6 pointer-coarse:after:absolute pointer-coarse:after:-inset-1.5 sm:pointer-coarse:after:-inset-2"
                onClick={() => removeEmail(e)}>
                <X aria-hidden="true" />
              </Button>
            )}
          </Badge>
        ))}
        {invalid.map((e) => (
          <Badge key={`!${e}`} variant="outline" data-slot="email-chip" data-invalid="true"
            className="h-8 max-w-full gap-0.5 border-destructive/50 bg-destructive/10 py-0 pr-0.5 pl-1 font-normal text-destructive sm:h-7 dark:bg-destructive/20">
            <Button type="button" variant="ghost" size="xs" aria-label={t('noc.editInvalid', e)} title={t('noc.editInvalid', e)}
              className="relative h-7 min-w-0 max-w-full gap-1 px-1.5 font-normal text-inherit hover:bg-destructive/10 hover:text-destructive sm:h-6 pointer-coarse:after:absolute pointer-coarse:after:-inset-y-1.5 sm:pointer-coarse:after:-inset-y-2"
              onClick={() => editInvalid(e)}>
              <AlertCircle aria-hidden="true" /><span className="min-w-0 truncate">{e}</span>
            </Button>
            <Button type="button" variant="ghost" size="icon-xs" aria-label={t('noc.removeEmail', e)}
              className="relative size-7 rounded-full text-inherit hover:bg-destructive/15 hover:text-destructive sm:size-6 pointer-coarse:after:absolute pointer-coarse:after:-inset-1.5 sm:pointer-coarse:after:-inset-2"
              onClick={() => removeInvalid(e)}>
              <X aria-hidden="true" />
            </Button>
          </Badge>
        ))}
        <Input ref={inputRef} id={id} type="text" inputMode="email" autoComplete="off" spellCheck={false}
          value={text} disabled={disabled} aria-describedby={describedBy} aria-invalid={ariaInvalid || undefined}
          placeholder={emails.length || invalid.length ? t('noc.emailsMorePh') : (placeholder || t('noc.emailsPh'))}
          className="h-10 min-w-[14ch] flex-1 border-0 bg-transparent px-1 shadow-none sm:h-8 sm:pointer-coarse:h-10 focus-visible:ring-0 aria-invalid:ring-0 dark:bg-transparent"
          onChange={(e) => {
            const v = e.target.value
            // Ayırıcı yazılınca (klavyede virgül/noktalı virgül) öncesi hemen çip olur
            if (/[,;\n]/.test(v)) commit(v); else setText(v)
          }}
          onPaste={(e) => {
            const pasted = e.clipboardData?.getData('text') ?? ''
            if (!/[,;\s]/.test(pasted.trim())) return   // tek adres: normal yapıştırma, Enter ile işlenir
            e.preventDefault()
            commit(`${text} ${pasted}`)
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); commit(text) }
            else if (e.key === 'Backspace' && !text && emails.length) removeEmail(emails[emails.length - 1])
          }}
          onBlur={() => commit(text)} />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-xs">
        <span data-slot="email-notice" role="status" className="text-muted-foreground">{notice}</span>
        <span data-slot="email-count" className={cn('tabular-nums', over ? 'font-semibold text-destructive' : 'text-muted-foreground')}>
          {t('noc.emailCount', emails.length, MAX_EMAILS)}
        </span>
      </div>

      {invalid.length > 0 && (
        <ul data-slot="email-errors" className="flex list-none flex-col gap-0.5 text-xs text-destructive">
          {invalid.map((e) => (
            <li key={e} className="flex min-w-0 items-start gap-1.5 [overflow-wrap:anywhere]">
              <AlertCircle aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('noc.invalidEmail', e)}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
