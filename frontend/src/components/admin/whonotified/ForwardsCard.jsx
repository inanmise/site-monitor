import { ArrowRight, Forward } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { ItemGroup } from '@/components/shadcn/item'
import { ChannelCard, PersonAvatar, RecipientItem } from './ChannelCards.jsx'
import { FORWARD_TONE } from '../../forwards/ForwardBadge.jsx'
import { cn } from '@/lib/utils'

/**
 * "Kim bilgilendirilir?" — İLETİM ALICILARI (2026-11-05). Simülasyon yanıtının `forwards` listesi
 * (`EscalationService.simulateRecipients`): senaryonun takımının alarm iletimleri, seçilen türe uyanlar (ALL, TYPES ∋ tür).
 * Alıcı takım / üyeler sahip takımın aldığı bildirimlerin AYNISINI AYRI bir postayla (+ push) alır; eskalasyon kişileri,
 * nöbetçi ve 7/24 iletilmez. Yalnız SEÇİLEN izlemelere tanımlı iletim (`applies: "monitors_only"`) senaryo bir izleme
 * seçmediği için "yalnız seçilen izlemeler" notuyla listelenir — bu türün her izlemesine gider sanılmasın.
 *
 * <p>Sunucu alanı göndermiyorsa (eski sunucu) ya da liste boşsa kart çizilmez (çağıran karar verir). Adresler kesilmez,
 * sarar; adresi olmayan alıcı "yalnız push" notuyla görünür (posta gitmez). Test kancaları: `data-channel="forward"`,
 * satır `data-slot="wn-recipient"` + `data-source="forward"` + `data-applies`.
 *
 * @param forwards   sunucunun `forwards` dizisi
 * @param onNavigate (isteğe bağlı) AdminPanel.jump — "Alarm iletimi ekranında yönet"
 * @param teamId     senaryonun takımı (yönetim ekranının sahip takım süzgeci)
 */
export default function ForwardsCard({ forwards, onNavigate, teamId, className }) {
  const t = useT()
  const rows = (Array.isArray(forwards) ? forwards : []).filter((f) => f && typeof f === 'object')
  const addressCount = new Set(rows.flatMap((f) => (Array.isArray(f.emails) ? f.emails : []).map((e) => String(e).trim().toLowerCase()))
    .filter(Boolean)).size
  return (
    <ChannelCard channel="forward" icon={Forward} title={t('fwdx.sim.title')} count={rows.length}
      description={t('fwdx.sim.desc')} className={className}>
      <div className="flex min-w-0 flex-col gap-3">
        <ItemGroup className="gap-2">
          {rows.map((f, i) => {
            const emails = (Array.isArray(f.emails) ? f.emails : []).map((e) => String(e).trim()).filter(Boolean)
            const members = String(f.recipient_kind ?? '').toUpperCase() === 'MEMBERS'
            const n = Number(f.member_count) || 0
            const kindText = members ? (n === 1 ? t('fwdx.recipient.members.one') : t('fwdx.recipient.members', n)) : t('fwdx.recipient.team')
            const monitorsOnly = f.applies === 'monitors_only'
            const scope = String(f.scope_kind ?? '').toUpperCase()
            const appliesText = monitorsOnly ? t('fwdx.sim.appliesMonitors') : scope === 'TYPES' ? t('fwdx.sim.appliesType') : t('fwdx.scope.all')
            const name = f.recipient_team_name || t('fwdx.teamUnnamed')
            return (
              <RecipientItem key={`${f.recipient_team_id ?? 'x'}:${i}`} source="forward" data-applies={monitorsOnly ? 'monitors_only' : 'all'}
                media={<PersonAvatar name={name} icon={Forward} />}
                title={name}
                badges={<>
                  <ToneBadge tone="neutral" className="h-auto overflow-visible rounded-md whitespace-normal">{kindText}</ToneBadge>
                  <ToneBadge tone={monitorsOnly ? 'warning' : 'info'} data-wn="applies"
                    className="h-auto overflow-visible rounded-md whitespace-normal">{appliesText}</ToneBadge>
                </>}
                description={emails.length ? emails.join(', ') : (members ? t('fwdx.sim.noMemberEmail') : t('fwdx.sim.noTeamEmail'))}
                mono={emails.length > 0}
                extra={monitorsOnly && (
                  <p data-slot="wn-forward-monitors-only" className="text-xs leading-relaxed text-muted-foreground">{t('fwdx.sim.monitorsOnlyNote')}</p>
                )}
              />
            )
          })}
        </ItemGroup>
        <p data-slot="wn-forward-summary" className={cn('rounded-md border px-3 py-2 text-xs leading-relaxed', FORWARD_TONE)}>
          {addressCount === 0 ? t('fwdx.sim.summaryNone') : addressCount === 1 ? t('fwdx.sim.summary.one') : t('fwdx.sim.summary', addressCount)}
        </p>
        {onNavigate && (
          <div>
            <Button type="button" variant="outline" size="sm" data-action="wn-open-forwards"
              className="h-10 w-full sm:w-auto md:h-8 pointer-coarse:h-10"
              onClick={() => onNavigate('forwards', teamId != null ? { g_fteam: String(teamId) } : undefined)}>
              {t('fwdx.sim.manage')} <ArrowRight aria-hidden="true" />
            </Button>
          </div>
        )}
      </div>
    </ChannelCard>
  )
}
