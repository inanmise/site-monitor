import { ArrowRight, Forward } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { usePermissions } from '../../../contexts/PermissionsProvider.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Button } from '@/components/shadcn/button'
import { ItemGroup } from '@/components/shadcn/item'
import { ChannelCard, PersonAvatar, RecipientItem } from './ChannelCards.jsx'
import { FORWARD_TONE } from '../../forwards/ForwardBadge.jsx'
import { simAppliesPartly, simAppliesText } from '../../forwards/forwardModel.js'
import { cn } from '@/lib/utils'

/**
 * "Kim bilgilendirilir?" — İLETİM ALICILARI (2026-11-05). Simülasyon yanıtının `forwards` listesi
 * (`EscalationService.simulateRecipients`): senaryonun takımının alarm iletimleri, seçilen türe uyanlar (ALL, TYPES ∋ tür).
 * Alıcı takım / üyeler sahip takımın aldığı bildirimlerin AYNISINI AYRI bir postayla (+ push) alır; eskalasyon kişileri,
 * nöbetçi ve 7/24 iletilmez. Kapsam (`applies`): `all` — seçilen türe uyan; `types_only` — yalnız SEÇİLEN türler
 * (senaryo bir tür belirlemiyor) → tür adlarıyla; `monitors_only` — yalnız seçilen izlemeler (senaryo izleme seçmez). Son
 * ikisi "yalnız bir bölüm için" notuyla listelenir — takımın her alarmına gider sanılmasın.
 *
 * <p>Sunucu alanı göndermiyorsa (eski sunucu) ya da liste boşsa kart çizilmez (çağıran karar verir). Adresler kesilmez,
 * sarar; adresi olmayan alıcı "yalnız push" notuyla görünür (posta gitmez). Test kancaları: `data-channel="forward"`,
 * satır `data-slot="wn-recipient"` + `data-source="forward"` + `data-applies`.
 *
 * @param forwards   sunucunun `forwards` dizisi
 * @param onNavigate (isteğe bağlı) AdminPanel.jump — "Alarm iletimi ekranında yönet"; düğme YALNIZ `alerts.forwards`
 *                   görme izni varken çizilir (sekme o izinle görünür — izinsiz kullanıcı boş / reddedilen sekmeye gitmesin)
 * @param teamId     senaryonun takımı (yönetim ekranının sahip takım süzgeci)
 */
export default function ForwardsCard({ forwards, onNavigate, teamId, className }) {
  const t = useT()
  const canOpenForwards = usePermissions().canView('alerts.forwards')
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
            const applies = String(f.applies ?? '').toLowerCase()
            const partly = simAppliesPartly(f)
            const name = f.recipient_team_name || t('fwdx.teamUnnamed')
            return (
              <RecipientItem key={f.forward_id ?? `${f.recipient_team_id ?? 'x'}:${i}`} source="forward" data-applies={applies || 'all'}
                media={<PersonAvatar name={name} icon={Forward} />}
                title={name}
                badges={<>
                  <ToneBadge tone="neutral" className="h-auto overflow-visible rounded-md whitespace-normal">{kindText}</ToneBadge>
                  <ToneBadge tone={partly ? 'warning' : 'info'} data-wn="applies"
                    className="h-auto overflow-visible rounded-md whitespace-normal">{simAppliesText(t, f)}</ToneBadge>
                </>}
                description={emails.length ? emails.join(', ') : (members ? t('fwdx.sim.noMemberEmail') : t('fwdx.sim.noTeamEmail'))}
                mono={emails.length > 0}
                extra={partly && (
                  <p data-slot="wn-forward-partly" data-applies={applies} className="text-xs leading-relaxed text-muted-foreground">
                    {applies === 'types_only' ? t('fwdx.sim.typesOnlyNote') : t('fwdx.sim.monitorsOnlyNote')}
                  </p>
                )}
              />
            )
          })}
        </ItemGroup>
        <p data-slot="wn-forward-summary" className={cn('rounded-md border px-3 py-2 text-xs leading-relaxed', FORWARD_TONE)}>
          {addressCount === 0 ? t('fwdx.sim.summaryNone') : addressCount === 1 ? t('fwdx.sim.summary.one') : t('fwdx.sim.summary', addressCount)}
        </p>
        {onNavigate && canOpenForwards && (
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
