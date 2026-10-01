// İzleme Panosu — üst bölümler AKORDİYONU (2026-10-01, kullanıcı isteği): özet göstergeler (KPI kartları), filo sağlığı,
// takım sağlığı ve izleme türleri tek kapta, her biri açılır/kapanır bir shadcn Accordion öğesi. Varsayılan: yalnız en
// üstteki özet göstergeler açık, diğerleri kapalı; kabın üst şeridindeki tek düğme hepsini açar ya da kapatır.
//
// Kapalı bir bölüm de bilgi verir: başlık satırında tonlu ikon kutusu (bölümün en kötü durumu) ve özet çipleri
// ("3 sorunlu", "2 türde sorun" …) durur; bölüm açılınca çipler gizlenir (içerik aynı bilgiyi zaten gösterir).
// Kapta SOL ŞERİT YOK (2026-09-26 kararı) — ton ikon kutusunda ve `data-tone`'da.
//
// Açık bölümler URL'de (`mo_open`): varsayılan (yalnız `kpis`) URL'ye yazılmaz, hiçbiri açık değilse `none`.
// Tarayıcı belleğine YAZILMAZ (2026-09-27 kararı: görünüm tercihleri tarayıcıya kaydedilmez).
//
// Test kancaları: `data-slot="mo-sections"`, `mo-sections-toggle` (`data-all-open`), `mo-sections-count`,
// `mo-section` (`data-section`, `data-tone`, `data-state`), `mo-section-trigger`, `mo-section-summary`, `mo-chip` (`data-tone`).
import { ChevronsDownUp, ChevronsUpDown, PanelsTopLeft } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from '@/components/shadcn/accordion'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Bölüm anahtarları — sayfadaki sıra. */
export const SECTION_ORDER = ['kpis', 'health', 'teams', 'types']
/** Varsayılan açık bölümler: yalnız en üstteki özet göstergeler. */
export const SECTION_DEFAULT = ['kpis']

/** URL değerinden açık bölümler: boş → varsayılan; `none` → hiçbiri; aksi virgüllü liste (bilinmeyenler atılır). */
export function parseOpenSections(raw) {
  const s = String(raw ?? '').trim()
  if (!s) return [...SECTION_DEFAULT]
  if (s === 'none') return []
  const want = new Set(s.split(',').map((x) => x.trim()))
  return SECTION_ORDER.filter((k) => want.has(k))
}

/** Açık bölümlerin URL değeri: varsayılansa null (URL temiz kalır), boşsa `none`. */
export function openSectionsParam(open) {
  const list = SECTION_ORDER.filter((k) => (open || []).includes(k))
  if (list.length === SECTION_DEFAULT.length && SECTION_DEFAULT.every((k) => list.includes(k))) return null
  return list.length ? list.join(',') : 'none'
}

const TILE = {
  ok: 'bg-success/10 text-success',
  bad: 'bg-destructive/10 text-destructive',
  warn: 'bg-amber-500/10 text-amber-700 dark:text-amber-300',
  neutral: 'bg-muted text-muted-foreground',
  primary: 'bg-primary/10 text-primary',
}

const CHIP = {
  ok: 'border-success/30 bg-success/10 text-success',
  bad: 'border-destructive/30 bg-destructive/10 text-destructive',
  warn: 'border-amber-500/30 bg-amber-500/10 text-amber-800 dark:text-amber-300',
  alert: 'border-orange-500/30 bg-orange-500/10 text-orange-700 dark:text-orange-300',
  neutral: 'border-border bg-muted/50 text-muted-foreground',
}

/** Başlık satırındaki kısa özet çipi (shadcn Badge). */
export function SummaryChip({ tone = 'neutral', Icon, children, className }) {
  return (
    <Badge variant="outline" data-slot="mo-chip" data-tone={tone}
      className={cn('h-6 gap-1 rounded-full px-2 text-[11px] font-medium tabular-nums', CHIP[tone] ?? CHIP.neutral, className)}>
      {Icon && <Icon aria-hidden="true" className="size-3" />}{children}
    </Badge>
  )
}

/**
 * @param sections [{ key, Icon, title, desc?, tone?, summary?: node, content: node, contentClassName? }]
 * @param open     açık bölüm anahtarları
 * @param onOpenChange (keys[]) => void
 */
export default function OverviewSections({ sections, open, onOpenChange, className }) {
  const t = useT()
  const keys = sections.map((s) => s.key)
  const openShown = keys.filter((k) => open.includes(k))
  const allOpen = keys.length > 0 && openShown.length === keys.length
  const toggleAll = () => onOpenChange(allOpen ? [] : keys)
  return (
    <section data-slot="mo-sections" aria-label={t('mo.sec.label')}
      className={cn('min-w-0 overflow-hidden rounded-xl border bg-card text-card-foreground shadow-xs', className)}>
      {/* Tek nokta: tüm bölümleri aç / kapat */}
      <div className="flex min-h-11 items-center justify-between gap-2 border-b bg-muted/30 px-3 py-1 sm:px-4">
        <span className="flex min-w-0 items-center gap-2 text-xs">
          <PanelsTopLeft aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          <span className="hidden truncate font-semibold sm:inline">{t('mo.sec.label')}</span>
          <span data-slot="mo-sections-count" className="shrink-0 text-muted-foreground tabular-nums">{t('mo.sec.openCount', openShown.length, keys.length)}</span>
        </span>
        <Button type="button" variant="ghost" size="sm" data-slot="mo-sections-toggle" data-all-open={allOpen}
          onClick={toggleAll} className="-mr-2 shrink-0 text-xs font-medium pointer-coarse:h-10">
          {allOpen ? <ChevronsDownUp aria-hidden="true" /> : <ChevronsUpDown aria-hidden="true" />}
          {allOpen ? t('mo.sec.collapseAll') : t('mo.sec.expandAll')}
        </Button>
      </div>
      <Accordion type="multiple" value={openShown} onValueChange={onOpenChange}>
        {sections.map((s) => (
          <AccordionItem key={s.key} value={s.key} data-slot="mo-section" data-section={s.key} data-tone={s.tone || 'neutral'}>
            <AccordionTrigger data-slot="mo-section-trigger"
              className="group/sec items-center gap-3 rounded-none px-3 py-2.5 hover:bg-muted/40 hover:no-underline focus-visible:ring-inset sm:px-4 pointer-coarse:min-h-12 [&>svg]:translate-y-0">
              <span className="flex min-w-0 flex-1 flex-col gap-1.5 sm:flex-row sm:items-center sm:gap-3">
                <span className="flex min-w-0 items-center gap-2.5">
                  <span aria-hidden="true" className={cn('grid size-8 shrink-0 place-items-center rounded-lg', TILE[s.tone] ?? TILE.neutral)}>
                    <s.Icon className="size-4" />
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-semibold">{s.title}</span>
                    {s.desc && <span className="hidden truncate text-xs font-normal text-muted-foreground md:block">{s.desc}</span>}
                  </span>
                </span>
                {s.summary && (
                  <span data-slot="mo-section-summary"
                    className="flex min-w-0 flex-wrap items-center gap-1.5 pl-[42px] font-normal group-data-[state=open]/sec:hidden sm:ml-auto sm:justify-end sm:pl-0">
                    {s.summary}
                  </span>
                )}
              </span>
            </AccordionTrigger>
            <AccordionContent className={cn('min-w-0 px-3 pb-3 sm:px-4 sm:pb-4', s.contentClassName)}>
              {s.content}
            </AccordionContent>
          </AccordionItem>
        ))}
      </Accordion>
    </section>
  )
}
