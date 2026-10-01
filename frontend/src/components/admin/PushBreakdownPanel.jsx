import { useState } from 'react'
import { ChevronDown, ChevronRight, Filter } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate } from '../../api/client'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { cn } from '@/lib/utils'

/**
 * Gönderim logu kırılım paneli (2026-09-21, kullanıcı bildirimi: "kartları daha iyi sunalım, rakamlara tıklayınca liste
 * gelsin"). Her satır: ad hücresi + oran çubuğu (gönderildi / başarısız / diğer) + üç tıklanır sayı (Toplam → yalnız o
 * boyut; Gönderildi → boyut + SENT; Başarısız → boyut + FAILED) + son hata. Tıklama üstteki süzgeci uygular ve ana tabloya
 * kaydırır; aynı seçime ikinci tıklama süzgeci kaldırır. Boyut süzülemiyorsa (takımsız satır) sayı yalnız durumu süzer.
 * Çizim shadcn: Collapsible + Card kabı, sayılar outline Button (aria-pressed), sayaç Badge.
 *
 * @param rows        [{ total, sent, failed, last_failed_at, ... }]
 * @param keyOf       satır anahtarı
 * @param label       (row) → ad hücresi (JSX)
 * @param dim         (row) → boyut süzgeç yaması ({ teamId: '5' } gibi) ya da null (süzülemez)
 * @param isDimActive (row) → boyut süzgeci bu satırda mı
 * @param status      etkin durum süzgeci ('' | 'SENT' | 'FAILED' | …)
 * @param onFilter    (patch) → süzgeci uygula (durum dâhil) ve tabloya kaydır
 * @param bare        (2026-10-01) kart / açılır başlık OLMADAN yalnız liste — push logu kırılım sekmelerinin içinde
 */
export default function PushBreakdownPanel({ title, rows = [], keyOf, label, dim, isDimActive, status = '', onFilter, defaultOpen = true, bare = false }) {
  const t = useT()
  const [open, setOpen] = useState(defaultOpen)
  const max = Math.max(1, ...rows.map((r) => Number(r.total) || 0))
  const activeRows = rows.filter((r) => isDimActive?.(r)).length
  const apply = (r, st) => {
    const d = dim?.(r)
    const dimOn = isDimActive?.(r)
    const same = dimOn && (status || '') === (st || '')
    // aynı seçime ikinci tıklama → o boyut + durum süzgeci kalkar
    const patch = { status: same ? '' : (st || '') }
    if (d) for (const k of Object.keys(d)) patch[k] = same ? '' : d[k]
    onFilter?.(patch)
  }
  const num = (r, st, val, tone) => {
    const on = isDimActive?.(r) && (status || '') === (st || '')
    return (
      <Button type="button" variant="outline" size="sm" aria-pressed={on} onClick={() => apply(r, st)}
        title={st ? t('pl.bdFilterStatus', st === 'SENT' ? t('health.statusSent') : t('health.statusFailed')) : t('pl.bdFilterAll')}
        className={cn('h-auto min-w-16 flex-col gap-0 px-2 py-0.5 leading-tight font-normal shadow-none hover:border-primary',
          on && 'border-primary bg-primary/10')}>
        <b className={cn('tabular-nums', tone === 'ok' && 'text-success', tone === 'bad' && 'text-destructive')}>{val ?? 0}</b>
        <span className="text-[0.68em] whitespace-nowrap text-muted-foreground">{st === 'SENT' ? t('health.statusSent') : st === 'FAILED' ? t('health.statusFailed') : t('sml.kpiTotal')}</span>
      </Button>
    )
  }

  const list = rows.length === 0 ? <div className="px-3.5 py-2.5 text-[0.85em] text-muted-foreground">{t('sml.noData')}</div> : (
    <ul className="m-0 list-none p-0">
      {rows.map((r) => {
        const total = Number(r.total) || 0, sent = Number(r.sent) || 0, failed = Number(r.failed) || 0
        const other = Math.max(0, total - sent - failed)
        // genişlikler CSS özel değişkeniyle (--w): çok parçalı çubuk ProgressBar'a sığmaz; progress-guard kapısı inline width istemez
        const w = (n) => `${total ? (n / total) * 100 : 0}%`
        const active = isDimActive?.(r)
        return (
          <li key={keyOf(r)} data-pbp-row="" data-state={active ? 'selected' : undefined}
            className={cn('grid grid-cols-1 items-center gap-x-3.5 gap-y-1.5 border-b px-3.5 py-2 last:border-b-0 hover:bg-muted/40 sm:grid-cols-[minmax(0,1fr)_auto] lg:grid-cols-[minmax(180px,1.3fr)_minmax(120px,1fr)_auto_150px] border-border',
              active && 'bg-primary/5')}>
            <div className="min-w-0 truncate font-semibold">{label(r)}</div>
            <div data-pbp-bar="" className="hidden h-2.5 w-(--w) min-w-6 overflow-hidden rounded-full bg-muted lg:flex"
              title={`${t('health.statusSent')} ${sent} · ${t('health.statusFailed')} ${failed}${other ? ` · ${t('pl.bdOther')} ${other}` : ''}`}
              style={{ '--w': `${(total / max) * 100}%` }}>
              <span className="block h-full w-(--w) bg-emerald-600" style={{ '--w': w(sent) }} />
              <span className="block h-full w-(--w) bg-red-600" style={{ '--w': w(failed) }} />
              <span className="block h-full w-(--w) bg-zinc-400" style={{ '--w': w(other) }} />
            </div>
            <div className="inline-flex gap-1.5">
              {num(r, '', total)}
              {num(r, 'SENT', sent, 'ok')}
              {num(r, 'FAILED', failed, failed > 0 ? 'bad' : '')}
            </div>
            <div className="hidden text-right text-[0.8em] whitespace-nowrap lg:block" title={r.last_failed_at ? formatDate(r.last_failed_at) : ''}>
              <span className="block text-[0.8em] text-muted-foreground">{t('sml.lastFailed')}</span>{r.last_failed_at ? formatDate(r.last_failed_at) : '—'}
            </div>
          </li>
        )
      })}
    </ul>
  )

  if (bare) return <section data-testid="pbp" aria-label={title} className="min-w-0">{list}</section>

  return (
    <Collapsible open={open} onOpenChange={setOpen} asChild>
      <section data-testid="pbp">
        <Card className="min-w-0 gap-0 overflow-hidden p-0 shadow-none">
          <CollapsibleTrigger className={cn('flex w-full cursor-pointer items-center gap-2 px-3.5 py-2.5 text-left hover:bg-muted/40', open && 'border-b border-border')}>
            {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            <span className="font-semibold">{title}</span>
            <Badge variant="secondary" className="rounded-full px-1.5 text-[0.78em] font-bold text-muted-foreground">{rows.length}</Badge>
            {activeRows > 0 && <span className="inline-flex items-center gap-1 text-[0.74em] font-semibold text-primary"><Filter size={11} /> {t('pl.bdActive')}</span>}
            <span className="ml-auto text-[0.74em] text-muted-foreground">{t('pl.bdHint')}</span>
          </CollapsibleTrigger>
          <CollapsibleContent>
            {list}
          </CollapsibleContent>
        </Card>
      </section>
    </Collapsible>
  )
}
