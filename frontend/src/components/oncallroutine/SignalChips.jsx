/**
 * Nöbet Rutinleri — görev satırındaki canlı sayaç çipleri (açık alarm, düşen izleme, açık olay …). Değer sunucunun
 * `/signals` yanıtından gelir; okunamayan değer "—" (gri), kısmi değer "≥ n", izni olmayana çip çizilmez. Çip tıklanınca
 * ilgili ekrana aynı sekmede gidilir. Bilinmeyen hiçbir zaman yeşil değildir.
 */
import { memo } from 'react'
import { ArrowUpRight } from 'lucide-react'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'

const TONE = {
  ok: 'border-success/30 bg-success/10 text-success hover:bg-success/15',
  warn: 'border-amber-500/40 bg-amber-500/10 text-amber-800 hover:bg-amber-500/15 dark:text-amber-200',
  bad: 'border-destructive/40 bg-destructive/10 text-destructive hover:bg-destructive/15',
  neutral: 'border-border bg-muted text-muted-foreground hover:bg-muted/80',
}

/** Bir sinyalin gösterim değeri: null → "—", kısmi → "≥ n". */
export function signalText(sig) {
  if (!sig || sig.value == null) return '—'
  return sig.partial ? `≥ ${sig.value}` : String(sig.value)
}

/** Sinyal tonu (sunucu `tone`; yoksa değerden). Bilinmeyen → nötr. */
export function signalTone(sig) {
  if (!sig || sig.value == null || sig.error) return 'neutral'
  // Kısmi sayım ("≥ n") bir ALT SINIR: n > 0 sorunu kanıtlar, ama "0 sorun"u kanıtlamaz → kısmi iken asla yeşil değil
  const partial = sig.partial === true
  if (TONE[sig.tone]) return partial && sig.tone === 'ok' ? 'neutral' : sig.tone
  if (Number(sig.value) > 0) return 'warn'
  return partial ? 'neutral' : 'ok'
}

function SignalChipsImpl({ keys, signals, loading = false }) {
  const t = useT()
  if (!keys?.length) return null
  return (
    <ul data-slot="or-task-signals" className="m-0 flex list-none flex-wrap gap-1.5 p-0">
      {keys.map((k) => {
        const sig = signals ? signals[k] : undefined
        if (sig?.hidden) return null
        const tone = loading && sig === undefined ? 'neutral' : signalTone(sig)
        const text = loading && sig === undefined ? '…' : signalText(sig)
        const label = t(`or.sig.${k}.label`)
        const link = sig?.link
        const aria = sig?.error ? t('or.sig.unreadable', label) : t('or.sig.aria', label, text)
        return (
          <li key={k}>
            <Button type="button" variant="outline" size="sm" data-slot="or-task-signal" data-signal={k} data-tone={tone}
              disabled={!link?.tab} aria-label={aria} title={t(`or.sig.${k}.desc`)}
              onClick={() => link?.tab && navigateTo(link.tab, link.params || {})}
              className={cn('h-auto min-h-7 gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium whitespace-normal pointer-coarse:min-h-10 disabled:opacity-100', TONE[tone])}>
              <span className="[overflow-wrap:anywhere]">{label}</span>
              <span className="font-semibold tabular-nums">{text}</span>
              {link?.tab && <ArrowUpRight aria-hidden="true" className="size-3 opacity-70" />}
            </Button>
          </li>
        )
      })}
    </ul>
  )
}

export default memo(SignalChipsImpl)
