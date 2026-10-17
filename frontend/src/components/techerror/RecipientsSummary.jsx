import { Users } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'
import { APPROVAL_TONE, approvalStateLabelKey, approvalStateOf } from './ApprovalCard.jsx'

const BADGE = 'rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]'

/**
 * Rapor başlığının altındaki alıcı özeti (R13 / B6, 2026-10-17): takım adresi SAYISI (0 → "yok", kırmızı), onay akışı olan
 * kanalda yönetici alıcı sayısı ve onay durumu çipi (ApprovalCard'ın etiket ve ton kaynağı). Yalnız sayılar — paylaşımla
 * okuyan da görür. Sayı bilinmiyorsa (eski sunucu: `team_email_count` yok) takım çipi çizilmez: bilinmeyen ≠ yok.
 * Telefonda sarar; çipler kesilmez (`rounded-md overflow-visible`).
 */
export default function RecipientsSummary({ teamEmailCount, approvalDetail, approval }) {
  const t = useT()
  const n = teamEmailCount == null || teamEmailCount === '' || !Number.isFinite(Number(teamEmailCount)) ? null : Number(teamEmailCount)
  const required = !!approvalDetail?.required
  if (n == null && !required) return null
  const state = approvalStateOf(approval)
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-sm" data-slot="te-recipients">
      <span className="inline-flex items-center gap-1 text-muted-foreground">
        <Users aria-hidden="true" className="size-3.5" /> {t('te.rcp.label')}
      </span>
      {n != null && (
        <Badge variant={n === 0 ? 'destructive' : 'secondary'} className={BADGE} data-slot="te-rcp-team" data-count={n}>
          {n === 0 ? t('te.rcp.teamNone') : t('te.rcp.team', n)}
        </Badge>
      )}
      {required && (
        <Badge variant="secondary" className={BADGE} data-slot="te-rcp-exec" data-count={Number(approvalDetail?.executive_count) || 0}>
          {t('te.rcp.exec', Number(approvalDetail?.executive_count) || 0)}
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
