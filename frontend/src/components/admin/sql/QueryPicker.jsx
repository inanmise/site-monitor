import { useState } from 'react'
import { BookOpen, CheckCircle2, ChevronDown, History, XCircle } from 'lucide-react'
import { useDateLocale, useT } from '../../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'
import { SAMPLE_CATEGORIES, groupSamples } from './sqlLibraryModel.js'
import { formatDuration, relativeFrom } from './sqlUtils.js'

/** Geçmiş satırı alanları — tel biçimi snake_case; eski camelCase yanıtla da çalışır. */
function historyFields(h) {
  return {
    sql: h.sql_text ?? h.sqlText ?? '',
    rows: h.row_count ?? h.rowCount,
    ms: h.duration_ms ?? h.durationMs,
    at: h.executed_at ?? h.executedAt,
    ok: h.success !== false,
    error: h.error_message ?? h.errorMessage ?? null,
  }
}

/**
 * Örnek sorgular / sorgu geçmişi seçici — shadcn Popover + Command (aranabilir, klavyeyle gezilir).
 * Geçmiş SUNUCUDAN gelir (kullanıcının son 50 sorgusu; başarısızlar dâhil) — tarayıcı deposu değil: cihazdan bağımsız,
 * süre ve satır sayısıyla. Seçim düzenleyiciye yazar (tarayıcının geri alma yığını korunur, bkz. SqlEditor).
 *
 * Örnekler (2026-11-13, D2): sunucu `category` + `description` gönderirse kategoriye göre GRUPLU, açıklama etiketin altında
 * (sararak); bilinen kategori kodu `sql.sampleCat.<kod>` ile çevrilir, bilinmeyen kodda sunucunun `category_label`ı (yoksa kod).
 * Eski sunucu (`category` yok) → tek grup (önceki görünüm).
 *
 * @param kind 'samples' | 'history'
 */
export default function QueryPicker({ kind, items, onPick, className }) {
  const t = useT()
  const locale = useDateLocale()
  const [open, setOpen] = useState(false)
  const samples = kind === 'samples'
  const Icon = samples ? BookOpen : History
  const label = samples ? t('sql.samples') : t('sql.history')
  const list = items || []

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" size="sm" className={cn('min-w-0', className)} aria-label={t('sql.pick.trigger', label, list.length)}>
          <Icon /> <span className="min-w-0 truncate">{label}</span>
          {list.length > 0 && <Badge variant="secondary" className="h-4 rounded-full px-1.5 text-[10px] tabular-nums">{list.length}</Badge>}
          <ChevronDown aria-hidden="true" className="size-3.5 opacity-60" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" collisionPadding={8} data-slot={`sql-${kind}`}
        className="z-(--z-menu) w-[min(32rem,calc(100vw-1rem))] p-0">
        <Command>
          <CommandInput className="text-base md:text-sm"
            placeholder={samples ? t('sql.pick.searchSamples') : t('sql.pick.searchHistory')} />
          <CommandList className="max-h-[min(26rem,60dvh)]">
            <CommandEmpty>{samples ? t('sql.noSamples') : t('sql.noHistory')}</CommandEmpty>
            {samples && groupSamples(list).map((g, gi) => (
              <CommandGroup key={g.category ?? '_'} data-slot="sql-sample-group" data-category={g.category ?? undefined}
                heading={g.category == null ? t('sql.pick.samplesHeading')
                  : SAMPLE_CATEGORIES.includes(g.category) ? t(`sql.sampleCat.${g.category}`) : (g.items[0]?.category_label || g.category)}>
                {g.items.map((s, i) => (
                  <CommandItem key={i} value={`s${gi}-${i} ${s.label} ${s.description ?? ''} ${s.sql}`} onSelect={() => { setOpen(false); onPick(s.sql) }}
                    className="flex-col items-start gap-1 py-2">
                    <span className="font-medium [overflow-wrap:anywhere]">{s.label}</span>
                    {s.description && <span data-slot="sql-sample-desc" className="w-full text-xs [overflow-wrap:anywhere] text-muted-foreground">{s.description}</span>}
                    <span className="line-clamp-2 w-full font-mono text-[11px] leading-snug whitespace-pre-wrap text-muted-foreground">{s.sql}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            ))}
            {!samples && (
            <CommandGroup heading={t('sql.pick.historyHeading')}>
              {list.map((h, i) => {
                  const f = historyFields(h)
                  const when = relativeFrom(f.at, locale)
                  return (
                    <CommandItem key={h.id ?? i} value={`h${i} ${f.sql}`} onSelect={() => { setOpen(false); onPick(f.sql) }}
                      data-state={f.ok ? undefined : 'failed'} className="flex-col items-start gap-1 py-2">
                      <span className="line-clamp-2 w-full font-mono text-[11px] leading-snug whitespace-pre-wrap">{f.sql}</span>
                      <span className="flex w-full flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-muted-foreground">
                        {f.ok
                          ? <span className="inline-flex items-center gap-1 text-success"><CheckCircle2 aria-hidden="true" className="size-3 text-success" />{t('sql.rowsCount', f.rows ?? 0)}</span>
                          : <span className="inline-flex items-center gap-1 text-destructive"><XCircle aria-hidden="true" className="size-3 text-destructive" />{t('sql.pick.failed')}</span>}
                        <span className="tabular-nums">{formatDuration(f.ms)}</span>
                        {when && <span>{when}</span>}
                        {!f.ok && f.error && <span className="w-full truncate text-destructive/80">{f.error}</span>}
                      </span>
                    </CommandItem>
                  )
                })}
            </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
