import { Mail, MessageSquare, Phone, Smartphone } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import NotificationGroupSelect from './NotificationGroupSelect.jsx'

/**
 * "Bildirimler ve Uyarılar" bloğu — HER izleme türünde AYNI.
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
 *
 * @param notifyEmail   e-posta kanalı açık mı (backend {@code notifyEmail} — artık GERÇEKTEN uygulanıyor)
 * @param notifyWebhook kişi-webhook (push) kanalı açık mı
 * @param onChange      kısmi form yaması döndürür: {@code {notifyEmail}} / {@code {notifyWebhook}}
 * @param teamLabel     hedef takımın görünen adı (grup seçili değilken gösterilir)
 * @param teamId        bildirim grubu listesini kapsamlayan takım; boşsa seçici pasif olur
 * @param groupId       seçili bildirim grubu (boş = takım varsayılanı)
 */
export default function NotifyChannels({
  notifyEmail, notifyWebhook, onChange,
  teamLabel, teamId, groupId, onGroupChange, groupName,
  alertLevel, onAlertLevelChange,
}) {
  const t = useT()
  // Alarm seviyesi (2026-09-19, ürün kararı): süre-bitişi dışındaki her alarm varsayılan WARNING ile açılır;
  // kullanıcı HIGH/CRITICAL seçerse eskalasyon kontakları alıcıya eklenir. Sertifika/alan adı süre-bitişi
  // alarmları gün eşiğiyle kademelenir, bu seçimden etkilenmez.
  const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']
  const level = LEVELS.includes(alertLevel) ? alertLevel : 'WARNING'
  // Hedef etiketi: grup seçiliyse GRUP, değilse takım. Seçici grup ADINI bilmiyorsa (henüz
  // yüklenmediyse) takım adına düşeriz — boş bir etiket göstermektense doğru olan bilinen bilgi.
  const target = groupId ? (groupName || t('notify.groupTarget')) : (teamLabel || '—')

  return (
    <div className="full-width http-notify-section">
      <div className="http-block-title">{t('notify.title')}</div>
      <div className="field-hint" style={{ marginBottom: 8 }}>
        {groupId ? t('notify.infoGroup', target) : t('notify.infoTeam', target)}
      </div>

      <div className="http-channels">
        <label className="http-channel">
          <input type="checkbox" checked={!!notifyEmail}
            onChange={e => onChange({ notifyEmail: e.target.checked })} />
          <Mail size={14} /><span>{t('notify.chEmail')}</span>
          <span className="http-ch-target">{target}</span>
        </label>

        <label className="http-channel http-channel--disabled" title={t('notify.soonHint')}>
          <input type="checkbox" disabled />
          <MessageSquare size={14} /><span>{t('notify.chSms')}</span>
          <span className="http-ch-soon">{t('notify.soon')}</span>
        </label>

        <label className="http-channel http-channel--disabled" title={t('notify.soonHint')}>
          <input type="checkbox" disabled />
          <Phone size={14} /><span>{t('notify.chVoice')}</span>
          <span className="http-ch-soon">{t('notify.soon')}</span>
        </label>

        <label className="http-channel" title={t('userpush.monitorToggleHint')}>
          <input type="checkbox" checked={!!notifyWebhook}
            onChange={e => onChange({ notifyWebhook: e.target.checked })} />
          <Smartphone size={14} /><span>{t('userpush.monitorToggle')}</span>
        </label>
      </div>

      {onAlertLevelChange && (
        <div className="notify-level">
          <div className="notify-level-head">
            <span className="notify-level-title">{t('notify.levelTitle')}</span>
            <span className="field-hint">{t('notify.levelHint')}</span>
          </div>
          <div className="seg-ctl notify-level-seg" role="group" aria-label={t('notify.levelTitle')}>
            {LEVELS.map((lv) => (
              <button key={lv} type="button" className={`seg-ctl-btn notify-level-btn notify-level-btn--${lv.toLowerCase()}${level === lv ? ' active' : ''}`}
                aria-pressed={level === lv} onClick={() => onAlertLevelChange(lv)}>{t('notify.level.' + lv)}</button>
            ))}
          </div>
          <div className="field-hint notify-level-note">{t('notify.levelNote.' + level)}</div>
        </div>
      )}

      {/* Bildirim grubu AYNI blokta: "kime gidecek" sorusunun cevabı tek yerde toplansın —
          kanal seçimiyle hedef seçimi ayrı bölümlerdeyken kullanıcı ikisini ilişkilendiremiyordu. */}
      {onGroupChange && (
        <div style={{ marginTop: 10 }}>
          {/* Etiket seçicinin kendisinde (ng.selectorLabel) — burada ikinci kez yazılmaz (2026-09-12: "Bildirim grubu" çift görünüyordu). */}
          <NotificationGroupSelect teamId={teamId} value={groupId} onChange={onGroupChange} />
        </div>
      )}
    </div>
  )
}
