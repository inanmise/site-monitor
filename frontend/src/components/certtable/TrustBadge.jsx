import { useState } from 'react'
import { CircleCheck, CircleHelp, CircleX, HeartPulse } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { cn } from '@/lib/utils'
import { revocationReason } from '../../utils/revocationInfo.js'
import { trustChecks, trustOf } from './certTableModel.js'

const TRUST_TONE = {
  ok: 'bg-success/15 text-success dark:bg-success/20',
  partial: 'bg-muted text-muted-foreground',
  unknown: 'border-border bg-transparent text-muted-foreground',
  bad: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
}
const STATE_ICON = { ok: CircleCheck, bad: CircleX, unknown: CircleHelp }
const STATE_TONE = { ok: 'text-success', bad: 'text-destructive', unknown: 'text-muted-foreground' }
const stop = (e) => e.stopPropagation()

/**
 * Tüm Sertifikalar — "Güven" rozeti + NEDEN açıklaması (2026-10-09, kullanıcı: "Kısmen doğrulandı için ek bir açıklama
 * yok; neden kısmen doğrulandığı bilinmiyor").
 *
 * <p>Rozet artık bir düğme: dokununca / tıklayınca (fare, klavye ve dokunmatikte — yalnız-hover bilgi YOK) üç denetimin
 * her birinin durumu açılır: zincir bütünlüğü, CA güveni, iptal (OCSP/CRL). Sonuçlanmayan ya da sorunlu denetimin altında
 * nedeni yazar; iptal satırı sunucunun `revocation_reason` kodunu Sağlık sekmesiyle AYNI metinle açıklar
 * (`hlth.revReason.*` — ör. "Sertifika bir OCSP ya da CRL adresi yayımlamıyor … Yapılacak bir şey yok"). Hüküm kuralı
 * {@link trustOf} / {@link trustChecks} (sunucuda `CertTrustVerdict`) — rozet ve açıklama ayrışamaz.
 *
 * <p>Satır tıklaması sertifika penceresini açar: tetik ve içerik tıklaması satıra taşınmaz (React olayları portal
 * içinden de satıra kabarır). Test kancaları: `data-slot="cert-trust"` (+ `data-tone`), `cert-trust-detail`,
 * `trust-check` (+ `data-check`, `data-state`).
 *
 * @param onOpenHealth sertifikanın Sağlık sekmesini açar (verilmezse düğme çizilmez)
 */
export default function TrustBadge({ cert, onOpenHealth }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const trust = trustOf(cert)
  const label = trust.tone === 'bad' ? trust.issues.map((i) => t(`tbl.trust.${i}`)).join(', ') : t(`tbl.trust.${trust.tone}`)
  const checks = trustChecks(cert, revocationReason(cert))
  const known = checks.filter((c) => c.state !== 'unknown').length
  const summary = t(`tbl.trustDetail.sum.${trust.tone}`, known)

  const hintOf = (c) => {
    if (c.state === 'ok') return null
    if (c.key === 'rev' && c.state === 'unknown' && c.reason) return t(`hlth.revReason.${c.reason}`)
    return t(`tbl.trustDetail.hint.${c.key}.${c.state}`)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="ghost" onClick={stop} onKeyDown={stop}
          aria-label={t('tbl.trustDetail.trigger', label)} data-slot="cert-trust-trigger"
          className="h-auto min-h-0 rounded-full p-0 font-[inherit] hover:bg-transparent dark:hover:bg-transparent max-md:min-h-10 pointer-coarse:min-h-10">
          <Badge variant="outline" data-slot="cert-trust" data-tone={trust.tone}
            className={cn('cursor-pointer border-transparent text-[.85em] font-semibold', TRUST_TONE[trust.tone])}>
            {label}
            <CircleHelp aria-hidden="true" className="size-3 opacity-70" />
          </Badge>
        </Button>
      </PopoverTrigger>
      <PopoverContent side="bottom" align="start" sideOffset={6} collisionPadding={8}
        aria-label={t('tbl.trustDetail.title')} data-slot="cert-trust-detail" data-tone={trust.tone}
        onClick={stop} onKeyDown={stop}
        className="z-(--z-menu) w-[min(22rem,calc(100vw-1.5rem))] p-0 text-left">
        <div className="border-b px-3 py-2.5">
          <p className="text-sm font-semibold">{t('tbl.trustDetail.title')}</p>
          <p data-slot="trust-summary" className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{summary}</p>
        </div>
        <ul className="flex flex-col gap-2.5 px-3 py-2.5">
          {checks.map((c) => {
            const Icon = STATE_ICON[c.state]
            const hint = hintOf(c)
            return (
              <li key={c.key} data-slot="trust-check" data-check={c.key} data-state={c.state} className="flex min-w-0 gap-2">
                <Icon aria-hidden="true" className={cn('mt-0.5 size-4 shrink-0', STATE_TONE[c.state])} />
                <div className="min-w-0">
                  <div className="flex flex-wrap items-baseline gap-x-1.5 text-xs">
                    <span className="font-semibold">{t(`tbl.trustDetail.${c.key}`)}</span>
                    <span className={STATE_TONE[c.state]}>{t(`tbl.trustDetail.state.${c.key}.${c.state}`)}</span>
                  </div>
                  {hint && <p data-slot="trust-check-hint" className="mt-0.5 text-xs leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">{hint}</p>}
                </div>
              </li>
            )
          })}
        </ul>
        {onOpenHealth && (
          <div className="border-t px-3 py-2">
            <Button type="button" variant="outline" size="sm" className="max-md:h-10 pointer-coarse:h-10" data-slot="trust-open-health"
              onClick={(e) => { stop(e); setOpen(false); onOpenHealth() }}>
              <HeartPulse aria-hidden="true" /> {t('tbl.trustDetail.openHealth')}
            </Button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  )
}
