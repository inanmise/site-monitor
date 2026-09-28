import { useEffect, useId, useRef, useState } from 'react'
import { Asterisk, Check, ChevronDown, Copy, Globe, Search, SearchX, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { copyText } from '../../utils/copyText.js'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { cn } from '@/lib/utils'
import { DetailSection } from './CertDetailsParts.jsx'
import { SAN_COLLAPSE, filterSan } from './certDetailsModel.js'

/**
 * "Sertifika Detayları" → Alternatif adlar (SAN) bölümü (2026-09-28). Sayaç, "tümünü kopyala" (satır sonlarıyla, HAM
 * adlar), alan adını kapsayan girdi EN ÖNDE ve vurgulu (birebir ✓ / joker kapsaması), joker girdiler işaretli.
 * {@link SAN_COLLAPSE}'ın üstünde: arama kutusu (InputGroup, telefonda 16 px) + "Tümünü göster / Daralt"; arama
 * sonucu yoksa boş durum (ui/StatusBlock). Uzun adlar kırpılmaz, sarılır.
 * Test kancaları: `data-section="san"`, liste `data-slot="cert-san-list"`, girdi `data-slot="cert-san"` + `data-match`
 * (exact|wildcard) + `data-wildcard`.
 */

const TOUCH_BTN = 'pointer-coarse:h-10'

function CopyAll({ names }) {
  const t = useT()
  const [copied, setCopied] = useState(false)
  const timer = useRef(null)
  useEffect(() => () => clearTimeout(timer.current), [])
  async function onCopy() {
    if (!(await copyText(names.join('\n')))) return   // sessiz: kullanıcı metni elle seçebilir
    setCopied(true)
    clearTimeout(timer.current)
    timer.current = setTimeout(() => setCopied(false), 2000)
  }
  return (
    <Button type="button" variant="outline" size="sm" data-slot="cert-san-copy-all" onClick={onCopy}
      aria-label={copied ? t('sslv.copied') : t('cdp.san.copyAllAria', names.length)}
      className={cn('gap-1.5', TOUCH_BTN)}>
      {copied ? <Check aria-hidden="true" className="size-4 text-success" /> : <Copy aria-hidden="true" className="size-4" />}
      <span aria-hidden="true">{copied ? t('sslv.copied') : t('cdp.san.copyAll')}</span>
    </Button>
  )
}

function SanChip({ entry, t }) {
  const matchText = entry.match === 'exact' ? t('cdp.san.match') : entry.match === 'wildcard' ? t('cdp.san.wildcardMatch') : null
  return (
    <li className="min-w-0 max-w-full">
      <Badge variant="outline" data-slot="cert-san" data-match={entry.match || undefined} data-wildcard={entry.wildcard ? 'true' : undefined}
        title={matchText || undefined}
        className={cn('h-auto min-h-7 max-w-full gap-1.5 rounded-md px-2 py-1 font-mono text-[12.5px] font-normal whitespace-normal [overflow-wrap:anywhere]',
          entry.match && 'border-primary/45 bg-primary/10 font-semibold text-primary dark:bg-primary/20')}>
        {entry.match && <Check aria-hidden="true" className="size-3.5 shrink-0" />}
        <span className="min-w-0">{entry.name}</span>
        {entry.wildcard && (
          <span className="inline-flex shrink-0 items-center gap-0.5 rounded-sm bg-muted px-1 font-sans text-[10.5px] font-semibold text-muted-foreground">
            <Asterisk aria-hidden="true" className="size-3" />{t('cdp.san.wildcard')}
          </span>
        )}
        {matchText && <span className="sr-only"> ({matchText})</span>}
      </Badge>
    </li>
  )
}

export default function CertSanList({ entries }) {
  const t = useT()
  const searchId = useId()
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState(false)
  if (!entries.length) return null

  const big = entries.length > SAN_COLLAPSE
  const q = query.trim()
  const found = big ? filterSan(entries, q) : entries
  const collapsed = big && !q && !expanded
  const shown = collapsed ? found.slice(0, SAN_COLLAPSE) : found

  return (
    <DetailSection slot="san" icon={Globe} title={t('modal.san')} className="lg:col-span-2"
      meta={<>
        <Badge variant="secondary" data-slot="cert-san-count" className="tabular-nums">{entries.length}</Badge>
        <CopyAll names={entries.map((e) => e.name)} />
      </>}>
      <div className="flex min-w-0 flex-col gap-3">
        {big && (
          <div className="flex min-w-0 flex-col gap-1.5 sm:flex-row sm:items-center sm:justify-between">
            <InputGroup data-slot="cert-san-search" className="h-11 w-full sm:h-9 sm:max-w-xs pointer-coarse:h-11">
              <InputGroupInput id={searchId} type="search" value={query} placeholder={t('cdp.san.search')} aria-label={t('cdp.san.search')}
                autoComplete="off" spellCheck={false} onChange={(e) => setQuery(e.target.value)}
                className="h-full [&::-webkit-search-cancel-button]:hidden"
                onKeyDown={(e) => { if (e.key === 'Escape' && query) { e.preventDefault(); e.stopPropagation(); setQuery('') } }} />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
              {query && (
                <InputGroupAddon align="inline-end">
                  <InputGroupButton size="icon-xs" className="pointer-coarse:size-10" onClick={() => setQuery('')} aria-label={t('app.clearFilter')}>
                    <X aria-hidden="true" />
                  </InputGroupButton>
                </InputGroupAddon>
              )}
            </InputGroup>
            {q && (
              <p role="status" data-slot="cert-san-results" className="m-0 text-xs text-muted-foreground tabular-nums">
                {t('cdp.san.results', found.length, entries.length)}
              </p>
            )}
          </div>
        )}

        {shown.length > 0 ? (
          <ul data-slot="cert-san-list" aria-label={t('modal.san')} className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0">
            {shown.map((e, i) => <SanChip key={`${e.name}-${i}`} entry={e} t={t} />)}
          </ul>
        ) : (
          <StatusBlock tone="neutral" icon={SearchX} title={t('cdp.san.noMatch', q)} description={t('cdp.san.noMatchHint')}
            className="rounded-md border border-dashed py-6 text-sm md:py-6" />
        )}

        {big && !q && (
          <Button type="button" variant="ghost" size="sm" data-slot="cert-san-toggle" aria-expanded={expanded}
            onClick={() => setExpanded((v) => !v)}
            className={cn('-mx-2 w-fit gap-1.5 px-2 text-[13px] font-medium', TOUCH_BTN)}>
            <ChevronDown aria-hidden="true" className={cn('size-4 transition-transform motion-reduce:transition-none', expanded && 'rotate-180')} />
            {expanded ? t('cdp.san.showLess') : t('cdp.san.showAll', entries.length)}
          </Button>
        )}
      </div>
    </DetailSection>
  )
}
