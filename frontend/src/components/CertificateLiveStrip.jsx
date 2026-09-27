import { memo } from 'react'
import { BellRing, BellOff } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { formatDate } from '../api/client'
import { navigateTo } from '../utils/navigate.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/**
 * Genel Bakış kartı "şu an" şeridi (2026-09-19, kullanıcı seçimi) — footer'da eylem düğmelerinin karşısında:
 * son erişilebilirlik kontrolü (ayakta/erişilemiyor · ms) + alanın SON alarm olayı (açıksa seviyesi, kapalıysa ne
 * zaman çözüldüğü). Kompakt ve zengin görünümde de çizilir (tek satır). Veri: /card-extras {uptime, last_alert}.
 * Erişilebilirlik saatlik → "son kontrol X dk önce" tooltip'i; yalnız sorun varsa renkli (100 kartta gürültü olmasın).
 * Tıklama: Durum İzleme'de o alan (?q=). Kart onClick'i yutulur.
 */
function ago(iso, t) {
  if (!iso) return ''
  const ms = Date.now() - new Date(iso + (iso.endsWith('Z') ? '' : 'Z')).getTime()
  if (!Number.isFinite(ms) || ms < 0) return ''
  const m = Math.floor(ms / 60000)
  if (m < 60) return t('live.minAgo', m)
  const h = Math.floor(m / 60)
  if (h < 48) return t('live.hourAgo', h)
  return t('live.dayAgo', Math.floor(h / 24))
}

/** Seviye kısa etiketi (KRİTİK/YÜKSEK/UYARI); bilinmeyen seviye ham adıyla. */
function levelShort(level, t) {
  const k = `sim.level.${String(level || '').toUpperCase()}`
  const v = t(k)
  return v === k ? String(level || '') : v
}

function CertificateLiveStrip({ domain, uptime, alert }) {
  const t = useT()
  if (!uptime && !alert) return null
  const up = uptime?.last_status === 'up'
  const down = uptime?.last_status && uptime.last_status !== 'up'
  const open = alert && !alert.resolved
  const tone = down || open ? 'bad' : 'ok'
  const title = [
    uptime?.last_at ? t('live.lastCheck', ago(uptime.last_at, t) || formatDate(uptime.last_at)) : null,
    alert ? `${alert.type || ''} · ${alert.level || ''} · ${open ? t('live.alertOpen') : t('live.alertResolved', alert.resolved_at ? formatDate(alert.resolved_at) : '')}` : t('live.noAlert'),
  ].filter(Boolean).join('\n')
  return (
    // shadcn Button (hap biçimli, outline). Ton `data-tone` (ok|bad); sorun yoksa nötr — 100 kartta gürültü olmasın.
    <Button type="button" variant="outline" size="xs" data-cert-live="true" data-tone={tone} title={title}
      onClick={(e) => { e.stopPropagation(); navigateTo('uptime', { q: domain }) }}
      className={cn(
        'h-auto min-w-0 max-w-full shrink-0 gap-0.5 rounded-full px-1.5 py-0.5 text-[10.5px] font-semibold whitespace-nowrap shadow-none has-[>svg]:px-1.5',
        tone === 'bad'
          ? 'border-destructive/40 bg-destructive/10 text-destructive hover:bg-destructive/15 hover:text-destructive dark:border-destructive/50 dark:bg-destructive/20 dark:hover:bg-destructive/30'
          : 'text-muted-foreground hover:border-primary hover:bg-background hover:text-primary dark:hover:bg-input/30',
      )}>
      {uptime && (
        <span className="inline-flex items-center gap-1">
          <span data-live-dot={down ? 'down' : up ? 'up' : 'unknown'} aria-hidden="true"
            className={cn('size-[7px] shrink-0 rounded-full bg-muted-foreground/60',
              up && 'bg-success', down && 'bg-destructive ring-[3px] ring-destructive/20')} />
          {down ? t('live.down') : up ? t('live.up') : t('live.unknown')}
          {uptime.last_ms != null && up && <span className="font-medium opacity-85"> {uptime.last_ms}ms</span>}
        </span>
      )}
      {/* Açık alarm = zil ikonu + seviye (2026-09-20): "alarm AÇIK · CRITICAL" metni dar footer'da kesiliyordu (K harfi). */}
      <span className={cn('inline-flex items-center gap-0.5 whitespace-nowrap', open && 'font-bold')}>
        {uptime ? <span className="mx-px opacity-60" aria-hidden="true">·</span> : null}
        {!alert ? t('live.noAlert')
          : open ? <><BellRing data-bell="open" className="size-[11px] shrink-0 text-destructive" aria-label={t('live.alertOpen')} /> {levelShort(alert.level, t)}</>
          : <><BellOff data-bell="off" className="size-[11px] shrink-0 text-muted-foreground opacity-70" aria-hidden="true" /> {t('live.alertAgo', ago(alert.resolved_at || alert.at, t) || '—')}</>}
      </span>
    </Button>
  )
}

export default memo(CertificateLiveStrip)
