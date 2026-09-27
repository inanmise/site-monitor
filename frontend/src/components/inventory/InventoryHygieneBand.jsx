import { useMemo, useState } from 'react'
import { ShieldCheck, AlertTriangle, ChevronDown, Sparkles } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { Toggle } from '@/components/shadcn/toggle'
import { cn } from '@/lib/utils'

/** Bandın ton mürekkebi (başlık simgesi). */
const TONE_INK = { ok: 'text-success', warn: 'text-amber-600 dark:text-amber-400', bad: 'text-destructive' }
/** Kırmızı sayılı (ciddi) hijyen kodları; kalanlar amber (eski .invhy-chip--* b renkleri). */
const BAD_CODES = new Set(['expired', 'revoked', 'error', 'chain'])

/**
 * Hijyen bandı (2026-09-12, #2): e-posta raporunun analizi sayfada — sayaçlar tıklanınca ilgili
 * süzgeç uygulanır (hygiene kodu). #12 çakışma sezgisi aynı bandın altında (yalnız öneri).
 *
 * @param data   /admin/inventory/hygiene yanıtı {total, scanned, groups:[{key,total,findings:[{domain,codes}]}]}
 * @param active seçili hijyen kodu (filters.hygiene)
 */
export const HYGIENE_CODES = ['no_team', 'no_tier', 'no_contacts', 'never_checked', 'stale', 'error', 'expired', 'revoked', 'chain', 'deployment', 'weak']

/** domain → Set(codes) — süzgeç bunu okur. */
export function hygieneIndex(data) {
  const idx = {}
  for (const g of data?.groups || []) for (const f of g.findings || []) {
    if (!idx[f.domain]) idx[f.domain] = new Set()
    for (const c of f.codes || []) idx[f.domain].add(c)
  }
  return idx
}

export default function InventoryHygieneBand({ data, overlaps, active, onSelect, onOpenDomain }) {
  const t = useT()
  const [open, setOpen] = useState(() => { try { return localStorage.getItem('inv-hygiene-open') !== 'false' } catch { return true } })
  const counts = useMemo(() => {
    const c = {}
    for (const g of data?.groups || []) for (const f of g.findings || []) for (const code of f.codes || []) c[code] = (c[code] || 0) + 1
    return c
  }, [data])
  if (!data) return null
  const total = data.total || 0
  const toggle = () => setOpen((o) => { try { localStorage.setItem('inv-hygiene-open', String(!o)) } catch { /* yoksay */ } return !o })
  const codes = HYGIENE_CODES.filter((k) => counts[k] > 0)
  const tone = total === 0 ? 'ok' : (counts.expired || counts.revoked || counts.error) ? 'bad' : 'warn'

  return (
    // shadcn Card + Collapsible. Ton (ok|warn|bad) başlık simgesinin rengi + `data-tone` — kartta SOL RENK ŞERİDİ YOK
    // (kullanıcı kuralı 2026-09-26; eski .invhy border-left'i).
    <Collapsible open={open} onOpenChange={toggle} className="mb-3">
      <Card data-slot="inv-hygiene" data-tone={tone} aria-label={t('inv.hyTitle')} role="region"
        className="gap-0 rounded-[10px] py-0 shadow-none">
        <CollapsibleTrigger asChild>
          <Button type="button" variant="ghost"
            className="h-auto w-full flex-wrap justify-start gap-x-2.5 gap-y-1 rounded-[10px] px-3.5 py-2.5 text-left font-semibold whitespace-normal hover:bg-muted/50 sm:flex-nowrap">
            {total === 0
              ? <ShieldCheck aria-hidden="true" className={cn('size-4', TONE_INK[tone])} />
              : <AlertTriangle aria-hidden="true" className={cn('size-4', TONE_INK[tone])} />}
            <span className="flex-1 sm:flex-none">{t('inv.hyTitle')}</span>
            <span className="order-3 w-full min-w-0 text-[.88em] font-medium text-muted-foreground sm:order-none sm:w-auto sm:flex-1">
              {total === 0 ? t('inv.hyClean', data.scanned ?? 0) : t('inv.hySummary', total, data.scanned ?? 0)}
            </span>
            <ChevronDown aria-hidden="true" className={cn('ml-auto size-4 transition-transform motion-reduce:transition-none sm:ml-0', open && 'rotate-180')} />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          {total > 0 && (
            <div className="px-3.5 pb-2.5">
              <div className="flex flex-wrap items-center gap-1.5">
                {codes.map((k) => (
                  <Toggle key={k} variant="outline" size="sm" data-hygiene-code={k} pressed={active === k}
                    onPressedChange={() => onSelect(active === k ? '' : k)} title={t('inv.hyShow')}
                    className="h-auto gap-1.5 rounded-full px-2.5 py-1 text-[.85em] font-normal text-foreground data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-foreground">
                    <b className={cn('text-[1.05em]', BAD_CODES.has(k) ? 'text-destructive' : 'text-amber-700 dark:text-amber-400')}>{counts[k]}</b> {t(`inv.hy.${k}`)}
                  </Toggle>
                ))}
                {active && <Button type="button" variant="secondary" size="sm" onClick={() => onSelect('')}>{t('inv.filterClear')}</Button>}
              </div>
              <p className="mt-2 text-[.8em] text-muted-foreground">{t('inv.hyHint')}</p>
            </div>
          )}
          {overlaps?.total > 0 && (
            <div className="border-t border-dashed px-3.5 pt-2 pb-3">
              <div className="mb-1 flex items-center gap-1.5 text-[.82em] font-bold tracking-[.03em] text-muted-foreground uppercase">
                <Sparkles size={13} aria-hidden="true" /> {t('inv.hyOverlapTitle', overlaps.total)}
              </div>
              <ul className="m-0 flex list-disc flex-col gap-0.5 pl-[18px] text-[.86em] text-muted-foreground">
                {overlaps.wildcard.slice(0, 5).map((o) => (
                  <li key={'w' + o.domain}>{t('inv.hyOverlapWildcard', o.domain, o.by)}{' '}
                    <Button type="button" variant="link" className="h-auto p-0 font-semibold" onClick={() => onOpenDomain?.(o.domain)}>{o.domain}</Button>
                  </li>
                ))}
                {overlaps.www.slice(0, 5).map((o) => <li key={'x' + o.domain}>{t('inv.hyOverlapWww', o.domain, o.by)}</li>)}
                {overlaps.port.slice(0, 5).map((o) => <li key={'p' + o.domain}>{t('inv.hyOverlapPort', o.domain, o.by)}</li>)}
                {overlaps.total > 15 && <li className="list-none">+{overlaps.total - 15}</li>}
              </ul>
            </div>
          )}
        </CollapsibleContent>
      </Card>
    </Collapsible>
  )
}
