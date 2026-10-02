import { CalendarClock, CheckCircle2, Wrench } from 'lucide-react'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { messageFor, windowText } from '../../utils/systemMaintenance.js'
import AlertBanner from '../ui/AlertBanner.jsx'

/**
 * Durum Sayfası (?tab=status) SİSTEM BAKIMI notu (2026-10-02, onaylı zenginleştirme b) — "Planlı bakım: 22:00–23:00".
 * Kaynak: `GET /api/status-page` yanıtının EK `system_maintenance` alanı (giriş sayfasının public bloğuyla aynı içerik:
 * durum, saatler, TR/EN mesaj, iletişim). Duyuru penceresi dışında ya da bakım yokken çizilmez. Bakım bittikten sonra
 * sunucunun bildirim süresince (`ended`) başarı tonunda "Planlı bakım tamamlandı (başlangıç – gerçek bitiş)" (2026-10-02
 * kullanıcı isteği).
 * Test kancası: `data-slot="sp-system-maintenance"` (`data-state`).
 */
export default function MaintenanceStatusNote({ note }) {
  const t = useT()
  const { lang } = useLanguage()
  const state = note?.state
  if (!state || state === 'none') return null
  const active = state === 'active'
  const msg = messageFor(note, lang)
  if (state === 'ended') {
    return (
      <div data-slot="sp-system-maintenance" data-state={state}>
        <AlertBanner tone="success" icon={CheckCircle2} className="mb-0" title={t('sysmaint.status.noteEnded', windowText(note))}>
          {t('sysmaint.status.noteEndedBody')}
          {msg ? ` · ${msg}` : ''}
        </AlertBanner>
      </div>
    )
  }
  return (
    <div data-slot="sp-system-maintenance" data-state={state}>
      <AlertBanner tone={active ? 'warning' : 'info'} icon={active ? Wrench : CalendarClock} className="mb-0"
        title={active ? t('sysmaint.status.noteActive') : t('sysmaint.status.notePlanned')}>
        {t('sysmaint.windowLine', windowText(note))}
        {msg ? ` · ${msg}` : ''}
        {note.contact ? ` · ${t('sysmaint.contact', note.contact)}` : ''}
      </AlertBanner>
    </div>
  )
}
