import { useId, useState } from 'react'
import { AlertTriangle, Check, Copy, ExternalLink, FileText, GitBranch, Link2, MoreHorizontal, Pencil, Ticket, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { copyText } from '../../utils/copyText.js'
import { isHttpLink, linkHost, linkKind, linkLabel, linkLabelParts, normaliseLink } from './weeklyLinks.js'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Label } from '@/components/shadcn/label'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'

/*
 * Takip bağlantıları — kompakt çip (2026-09-27, kullanıcı isteği: "linki girer, sonra tıklanabilir öğe olarak kalır,
 * açık URL görünmez"). Veri biçimi DEĞİŞMEDİ (alan başına tek URL dizesi); etiket/tür istemcide türetilir
 * (weeklyLinks.js). Tam adres yalnız "Bağlantı ayrıntısı" açılır penceresinde (dokunmatikte de) + "Bağlantıyı kopyala".
 * Test kancaları: data-slot="wr-link" (data-kind, data-valid), "wr-link-input", "wr-link-details", "wr-link-more".
 */
const KIND_ICON = { ticket: Ticket, repo: GitBranch, doc: FileText, generic: Link2 }
/** 32 px masaüstü, 40 px dokunmatik. */
const ICON_BTN = 'size-8 shrink-0 rounded-none text-muted-foreground hover:text-foreground pointer-coarse:size-10'

function LinkDetails({ url, label, fieldLabel, valid }) {
  const t = useT()
  const [copied, setCopied] = useState(false)
  const name = fieldLabel ? `${fieldLabel}: ${label}` : label
  async function copy() {
    if (await copyText(url)) { setCopied(true); setTimeout(() => setCopied(false), 1800) }
  }
  return (
    <Popover onOpenChange={(o) => { if (!o) setCopied(false) }}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" size="icon" className={ICON_BTN}
          aria-label={t('wr.link.details', name)} title={t('wr.link.details', name)}>
          <MoreHorizontal aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" data-slot="wr-link-details"
        className="z-(--z-menu) w-80 max-w-[calc(100vw-2rem)] p-3">
        <p className="text-xs font-semibold text-muted-foreground">{fieldLabel || t('wr.link.fullUrl')}</p>
        <p className="mt-1 font-mono text-xs break-all select-all">{url}</p>
        {!valid && <p className="mt-1.5 text-xs text-amber-700 dark:text-amber-400">{t('wr.link.invalidHint')}</p>}
        <div className="mt-2.5 flex flex-wrap gap-2">
          <Button type="button" size="sm" variant="secondary" onClick={copy} aria-label={t('wr.link.copy', name)}>
            {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />} {copied ? t('wr.link.copied') : t('wr.link.copyShort')}
          </Button>
          {valid && (
            <Button asChild size="sm" variant="outline">
              <a href={url} target="_blank" rel="noopener noreferrer"><ExternalLink aria-hidden="true" /> {t('wr.link.openShort')}</a>
            </Button>
          )}
        </div>
      </PopoverContent>
    </Popover>
  )
}

/**
 * Tek bağlantı çipi: tür ikonu + türetilmiş etiket + dış bağlantı ikonu (yeni sekmede açar, `noopener noreferrer`),
 * ayrıntı düğmesi (tam adres + kopyala); düzenlemede kalem (girdiyi URL ile yeniden açar) ve × (kaldırır).
 * Geçersiz (şemasız eski) değer tıklanmaz, uyarı tonuyla çizilir. Yazdırmada etiketin yanına kısa alan adı basılır.
 */
export function LinkChip({ url, fieldLabel, editable = false, onEdit, onRemove, className }) {
  const t = useT()
  const valid = isHttpLink(url)
  const label = linkLabel(url)
  const host = linkHost(url)
  const kind = valid ? linkKind(url) : 'invalid'
  const Icon = valid ? KIND_ICON[kind] : AlertTriangle
  const name = fieldLabel ? `${fieldLabel}: ${label}` : label
  const parts = linkLabelParts(url)
  const face = (
    <>
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      {/* Dar alanda alan adı kırpılır, ayırt edici son parça (anahtar / yol) görünür kalır */}
      {parts.key ? <span className="min-w-0 truncate">{parts.key}</span> : (
        <span className="flex min-w-0 items-baseline">
          <span className="min-w-0 truncate">{parts.host}</span>
          {parts.tail && <span className="max-w-[65%] shrink-0 truncate whitespace-pre"> › {parts.tail}</span>}
        </span>
      )}
      {host && <span className="hidden text-muted-foreground print:inline">({host})</span>}
      {valid && <ExternalLink aria-hidden="true" className="size-3.5 shrink-0 opacity-60 print:hidden" />}
    </>
  )
  return (
    <span data-slot="wr-link" data-kind={kind} data-valid={valid}
      className={cn('inline-flex h-8 max-w-full min-w-0 items-center overflow-hidden rounded-md border bg-card align-middle shadow-xs pointer-coarse:h-10',
        !valid && 'border-amber-500/60 bg-amber-500/5', className)}>
      {valid ? (
        <Button asChild variant="ghost" size="sm"
          className="h-full min-w-0 shrink gap-1.5 rounded-none px-2.5 font-medium text-primary hover:text-primary">
          <a href={url} target="_blank" rel="noopener noreferrer" aria-label={t('wr.link.open', name)}>{face}</a>
        </Button>
      ) : (
        <span className="inline-flex h-full min-w-0 items-center gap-1.5 px-2.5 text-sm font-medium text-amber-700 dark:text-amber-400">
          {face}<span className="sr-only">{t('wr.link.invalidName')}</span>
        </span>
      )}
      <span aria-hidden="true" className="h-4 w-px shrink-0 bg-border print:hidden" />
      <span className="inline-flex shrink-0 print:hidden">
        <LinkDetails url={url} label={label} fieldLabel={fieldLabel} valid={valid} />
        {editable && (
          <>
            <Button type="button" variant="ghost" size="icon" className={ICON_BTN} onClick={onEdit}
              aria-label={t('wr.link.edit', name)} title={t('wr.link.edit', name)}>
              <Pencil aria-hidden="true" />
            </Button>
            <Button type="button" variant="ghost" size="icon" className={cn(ICON_BTN, 'hover:text-destructive')} onClick={onRemove}
              aria-label={t('wr.link.remove', name)} title={t('wr.link.remove', name)}>
              <X aria-hidden="true" />
            </Button>
          </>
        )}
      </span>
    </span>
  )
}

/**
 * Bağlantı alanı: boşken (ya da kalemle açılınca) "Bağlantı ekle" girdisi — Enter / odak kaybı / ✓ doğrular,
 * geçerliyse çipe kapanır; geçersizse girdi açık kalır ve hata görünür (saklanan değer DEĞİŞMEZ). Esc vazgeçer.
 * Salt-okunurda çip (ya da "Bağlantı yok").
 */
export default function LinkField({ label, value, onChange, editable }) {
  const t = useT()
  const inputId = useId()
  const labelId = useId()
  const errId = useId()
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState(null)
  const current = String(value ?? '').trim()
  const showInput = editable && (editing || !current)

  function startEdit() {
    setDraft(current); setError(null); setEditing(true)   // girdi autoFocus ile açılır (InputGroupInput ref iletmez)
  }
  function cancel() { setDraft(''); setError(null); setEditing(false) }
  function commit() {
    const d = draft.trim()
    if (!d) { cancel(); return }
    const res = normaliseLink(d)
    if (!res.ok) { setError(res.error); return }
    if (res.url !== current) onChange(res.url)
    setDraft(''); setError(null); setEditing(false)
  }

  return (
    <div role="group" aria-labelledby={labelId} data-slot="wr-link-field" className="flex min-w-0 flex-col gap-1.5">
      {showInput
        ? <Label id={labelId} htmlFor={inputId} className="text-[.82em] font-semibold text-muted-foreground">{label}</Label>
        : <span id={labelId} className="text-[.82em] font-semibold text-muted-foreground">{label}</span>}
      {showInput ? (
        <>
          <InputGroup data-slot="wr-link-input" className="pointer-coarse:h-10">
            <InputGroupInput autoFocus={editing} id={inputId} type="url" inputMode="url" autoComplete="off" spellCheck={false}
              value={draft} placeholder={t('wr.link.placeholder')}
              aria-invalid={error ? true : undefined} aria-describedby={error ? errId : undefined}
              onChange={(e) => { setDraft(e.target.value); if (error) setError(null) }}
              onBlur={commit}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); commit() }
                else if (e.key === 'Escape' && (editing || draft)) { e.preventDefault(); e.stopPropagation(); cancel() }
              }} />
            <InputGroupAddon><Link2 aria-hidden="true" /></InputGroupAddon>
            <InputGroupAddon align="inline-end">
              <InputGroupButton size="icon-xs" onMouseDown={(e) => e.preventDefault()} onClick={commit}
                aria-label={t('wr.link.add', label)} title={t('wr.link.add', label)}>
                <Check aria-hidden="true" />
              </InputGroupButton>
            </InputGroupAddon>
          </InputGroup>
          {error
            ? <p id={errId} data-slot="wr-link-error" className="text-xs text-destructive">{t(error)}</p>
            : <p className="text-xs text-muted-foreground">{t('wr.link.hint')}</p>}
        </>
      ) : current ? (
        <div className="flex min-w-0"><LinkChip url={current} fieldLabel={label} editable={editable} onEdit={startEdit}
          onRemove={() => { onChange(''); setDraft(''); setError(null) }} /></div>
      ) : (
        <span className="text-sm text-muted-foreground">{t('wr.link.none')}</span>
      )}
    </div>
  )
}

/**
 * Yoğun görünümler için bağlantı listesi: en çok `max` çip, fazlası "+N daha" açılır penceresinde.
 * items: [{ key, label (alan adı), url }]
 */
export function LinkChipList({ items, max = 5, className }) {
  const t = useT()
  const list = (items || []).filter((x) => String(x.url ?? '').trim())
  if (!list.length) return null
  const head = list.slice(0, max)
  const rest = list.slice(max)
  return (
    <div data-slot="wr-link-list" className={cn('flex min-w-0 flex-wrap items-center gap-1.5', className)}>
      {head.map((x) => <LinkChip key={x.key} url={x.url} fieldLabel={x.label} />)}
      {rest.length > 0 && (
        <Popover>
          <PopoverTrigger asChild>
            <Button type="button" variant="outline" size="sm" data-slot="wr-link-more" className="pointer-coarse:h-10">
              {t('wr.link.more', rest.length)}
            </Button>
          </PopoverTrigger>
          <PopoverContent align="start" className="z-(--z-menu) flex w-auto max-w-[calc(100vw-2rem)] flex-col items-start gap-1.5 p-2">
            {rest.map((x) => <LinkChip key={x.key} url={x.url} fieldLabel={x.label} />)}
          </PopoverContent>
        </Popover>
      )}
    </div>
  )
}
