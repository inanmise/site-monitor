import { Users } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { APPROVAL_TONE, approvalStateLabelKey, approvalStateOf } from './ApprovalCard.jsx'

const BADGE = 'rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]'

/** Sayı mı (bilinmeyen = null — eski sunucu alanı göndermez): bilinmeyen asla 0 yazılmaz. */
function countOf(v) {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? n : null
}

/**
 * Rapor başlığının altındaki alıcı özeti (R13 / B6, 2026-10-17): takım adresi SAYISI (0 → "yok", kırmızı), onay akışı olan
 * kanalda yönetici alıcı sayısı ve onay durumu çipi (ApprovalCard'ın etiket ve ton kaynağı). Yalnız sayılar — paylaşımla
 * okuyan da görür. Bilinmeyen ≠ yok ≠ 0 (inceleme 2026-10-17): `team_email_count` yoksa takım çipi, `executive_count` yoksa
 * yönetici çipi çizilmez. Takım adresinde 20'den fazla teslim edilebilir adres varsa (`team_email_dropped` > 0) çip
 * "20 (+N gitmez)" der ve uyarı tonu alır — posta en çok 20 adrese gider. Telefonda sarar; çipler kesilmez.
 */
export default function RecipientsSummary({ teamEmailCount, teamEmailDropped, approvalDetail, approval }) {
  const t = useT()
  const n = countOf(teamEmailCount)
  const dropped = countOf(teamEmailDropped) || 0
  const required = !!approvalDetail?.required
  const execCount = countOf(approvalDetail?.executive_count)
  if (n == null && !required) return null
  const state = approvalStateOf(approval)
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-sm" data-slot="te-recipients">
      <span className="inline-flex items-center gap-1 text-muted-foreground">
        <Users aria-hidden="true" className="size-3.5" /> {t('te.rcp.label')}
      </span>
      {n != null && (
        <Badge variant={n === 0 ? 'destructive' : 'secondary'} className={cn(BADGE, n > 0 && dropped > 0 && APPROVAL_TONE.pending)}
          data-slot="te-rcp-team" data-count={n} data-dropped={dropped > 0 ? dropped : undefined}>
          {n === 0 ? t('te.rcp.teamNone') : dropped > 0 ? t('te.rcp.teamDropped', n, dropped) : t('te.rcp.team', n)}
        </Badge>
      )}
      {required && execCount != null && (
        <Badge variant="secondary" className={BADGE} data-slot="te-rcp-exec" data-count={execCount}>
          {t('te.rcp.exec', execCount)}
        </Badge>
      )}
      {required && (
        <Badge className={cn(BADGE, APPROVAL_TONE[state])} data-slot="te-rcp-approval" data-state={state}>
          {t('te.rcp.approval', t(approvalStateLabelKey(approval)))}
        </Badge>
      )}
    </div>
  )
}
