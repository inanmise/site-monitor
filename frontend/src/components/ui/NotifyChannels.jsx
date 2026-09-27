import { useId } from 'react'
import { Mail, MessageSquare, Phone, Smartphone } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import NotificationGroupSelect from './NotificationGroupSelect.jsx'
import SimpleTooltip from './SimpleTooltip.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Checkbox } from '@/components/shadcn/checkbox'
import {
  Field, FieldContent, FieldDescription, FieldLabel, FieldTitle,
} from '@/components/shadcn/field'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * "Bildirimler ve Uyarılar" bloğu — HER izleme türünde AYNI. Çizim shadcn: Field ailesi — kanallar
 * "seçim kartı" (FieldLabel içinde Checkbox), alarm seviyesi ToggleGroup. Kap `role="group"` +
 * başlığa bağlı ad (çerçeveli fieldset'te legend kenar çizgisinin üstüne oturuyordu).
 *
 * <p><b>Neden ortak bileşen:</b> aynı iş dokuz formda üç farklı biçimde yazılmıştı. HTTP/Keyword/
 * Port/Page/PageSpeed dört döşemeli bir ızgara çiziyor, DNS/Ping/Domain/Scripted ise düz
 * {@code checkbox-label} satırları kullanıyordu; üstelik DNS/Ping/Domain formlarında "E-posta"
 * kutusu HİÇ yoktu. Kullanıcı her ekranda farklı bir dille aynı soruyu cevaplıyordu.
 *
 * <p><b>Hedef satırı yalan söylemez:</b> bir bildirim grubu seçiliyse alarm ORAYA gider; seçili
 * değilse takımın varsayılan grubuna, o da yoksa takım adresine düşer. Blok bu zinciri olduğu
 * gibi yazar — "takım adresine gidecek" deyip başka yere göndermek en kötü hata olurdu.
 *
 * <p>SMS ve Sesli Arama kanalları bilinçli olarak PASİF ("yakında"): ürün kararı, eksiklik değil.
 * Webhook döşemesi ÇALIŞIR ve pasif GÖRÜNMEZ (eski görsel yalan: kutu çalışıyor ama soluk çiziliyordu —
 * kapı notifyBlockStandard.test.js).
 *
 * @param notifyEmail   e-posta kanalı açık mı (backend {@code notifyEmail} — artık GERÇEKTEN uygulanıyor)
 * @param notifyWebhook kişi-webhook (push) kanalı açık mı
 * @param onChange      kısmi form yaması döndürür: {@code {notifyEmail}} / {@code {notifyWebhook}}
 * @param teamLabel     hedef takımın görünen adı (grup seçili değilken gösterilir)
 * @param teamId        bildirim grubu listesini kapsamlayan takım; boşsa seçici pasif olur
 * @param groupId       seçili bildirim grubu (boş = takım varsayılanı)
 */

// Alarm seviyesi (2026-09-19, ürün kararı): süre-bitişi dışındaki her alarm varsayılan WARNING ile açılır;
// kullanıcı HIGH/CRITICAL seçerse eskalasyon kontakları alıcıya eklenir. Sertifika/alan adı süre-bitişi
// alarmları gün eşiğiyle kademelenir, bu seçimden etkilenmez.
const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
// Seçili seviyenin tonu (eski .notify-level-btn--*): uyarı amber, yüksek turuncu, kritik kırmızı.
const LEVEL_ON = {
  WARNING: 'data-[state=on]:border-amber-500 data-[state=on]:bg-amber-500 data-[state=on]:text-white',
  HIGH: 'data-[state=on]:border-orange-600 data-[state=on]:bg-orange-600 data-[state=on]:text-white',
  CRITICAL: 'data-[state=on]:border-red-700 data-[state=on]:bg-red-700 data-[state=on]:text-white',
}

export default function NotifyChannels({
  notifyEmail, notifyWebhook, onChange,
  teamLabel, teamId, groupId, onGroupChange, groupName,
  alertLevel, onAlertLevelChange,
}) {
  const t = useT()
  const levelId = useId()
  const titleId = useId()
  const level = LEVELS.includes(alertLevel) ? alertLevel : 'WARNING'
  // Hedef etiketi: grup seçiliyse GRUP, değilse takım. Seçici grup ADINI bilmiyorsa (henüz
  // yüklenmediyse) takım adına düşeriz — boş bir etiket göstermektense doğru olan bilinen bilgi.
  const target = groupId ? (groupName || t('notify.groupTarget')) : (teamLabel || '—')

  return (
    <div role="group" aria-labelledby={titleId} data-slot="notify-channels" className="flex min-w-0 flex-col gap-2 rounded-lg border bg-muted/30 px-3.5 py-3 sm:col-span-2">
      <FieldTitle id={titleId} className="font-semibold">{t('notify.title')}</FieldTitle>
      <FieldDescription className="text-xs">
        {groupId ? t('notify.infoGroup', target) : t('notify.infoTeam', target)}
      </FieldDescription>

      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <ChannelTile icon={Mail} label={t('notify.chEmail')} checked={!!notifyEmail}
          onCheckedChange={(v) => onChange({ notifyEmail: v })}
          end={<span data-slot="notify-target" className="ml-auto max-w-[45%] truncate text-xs font-medium text-primary">{target}</span>} />

        <ChannelTile icon={MessageSquare} label={t('notify.chSms')} disabled hint={t('notify.soonHint')}
          end={<Badge variant="warning" className="ml-auto text-[10px] font-bold tracking-[.04em] uppercase">{t('notify.soon')}</Badge>} />

        <ChannelTile icon={Phone} label={t('notify.chVoice')} disabled hint={t('notify.soonHint')}
          end={<Badge variant="warning" className="ml-auto text-[10px] font-bold tracking-[.04em] uppercase">{t('notify.soon')}</Badge>} />

        <ChannelTile icon={Smartphone} label={t('userpush.monitorToggle')} checked={!!notifyWebhook}
          hint={t('userpush.monitorToggleHint')}
          onCheckedChange={(v) => onChange({ notifyWebhook: v })} />
      </div>

      {onAlertLevelChange && (
        <div data-slot="notify-level" className="mt-3 flex flex-col gap-1.5 border-t border-dashed pt-2.5">
          <div className="flex flex-col gap-0.5">
            <span id={levelId} className="text-sm font-semibold">{t('notify.levelTitle')}</span>
            <span className="text-xs text-muted-foreground">{t('notify.levelHint')}</span>
          </div>
          {/* Eski sözleşme korunur: role="group" + aria-pressed'li düğmeler (ui/SegmentedControl ile aynı). */}
          <ToggleGroup type="single" variant="outline" role="group" aria-labelledby={levelId}
            value={level} onValueChange={(v) => { if (v) onAlertLevelChange(v) }}>
            {LEVELS.map((lv) => (
              <ToggleGroupItem key={lv} value={lv} role="button" aria-pressed={level === lv} aria-checked={undefined}
                data-level={lv} className={cn('h-8 px-3 text-xs font-semibold', LEVEL_ON[lv])}>
                {t('notify.level.' + lv)}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <p className="text-xs text-muted-foreground">{t('notify.levelNote.' + level)}</p>
        </div>
      )}

      {/* Bildirim grubu AYNI blokta: "kime gidecek" sorusunun cevabı tek yerde toplansın —
          kanal seçimiyle hedef seçimi ayrı bölümlerdeyken kullanıcı ikisini ilişkilendiremiyordu.
          Seçici kendi <label>'ını çiziyor: dikey düzen burada (eski .form-grid label kuralının yerine). */}
      {onGroupChange && (
        <div className="mt-2.5 min-w-0 [&>label]:flex [&>label]:flex-col [&>label]:gap-1.5 [&>label]:text-sm [&>label]:font-medium">
          {/* Etiket seçicinin kendisinde (ng.selectorLabel) — burada ikinci kez yazılmaz (2026-09-12: "Bildirim grubu" çift görünüyordu). */}
          <NotificationGroupSelect teamId={teamId} value={groupId} onChange={onGroupChange} />
        </div>
      )}
    </div>
  )
}

/**
 * Tek kanal döşemesi — shadcn "seçim kartı": FieldLabel içinde yatay Field + Checkbox. Etikete
 * (döşemenin herhangi bir yerine) tıklamak kutuyu değiştirir; seçiliyken kenar/zemin vurgulanır.
 * `hint` ipucudur (eski title) — döşemenin üzerine gelince görünür.
 */
function ChannelTile({ icon: Icon, label, checked = false, onCheckedChange, disabled = false, hint, end }) {
  const id = useId()
  const tile = (
    <FieldLabel htmlFor={id} data-slot="notify-channel"
      className={cn('bg-card text-[13px]', disabled && 'cursor-not-allowed')}>
      <Field orientation="horizontal" role={undefined} data-disabled={disabled ? 'true' : undefined}
        className="gap-2 px-3! py-2.5!">
        <Checkbox id={id} checked={checked} disabled={disabled}
          onCheckedChange={onCheckedChange ? (v) => onCheckedChange(v === true) : undefined} />
        <FieldContent className="min-w-0 flex-row items-center gap-2">
          <Icon size={14} aria-hidden="true" className="shrink-0 text-muted-foreground" />
          <FieldTitle className="font-semibold">{label}</FieldTitle>
          {end}
        </FieldContent>
      </Field>
    </FieldLabel>
  )
  // İpucu tetiği DOM kabı: FieldLabel ref iletmez (Tooltip asChild konum için ref ister).
  return hint ? <SimpleTooltip content={hint}><div className="min-w-0">{tile}</div></SimpleTooltip> : tile
}
