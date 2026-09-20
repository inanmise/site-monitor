import { memo } from 'react'
import { BellRing, BellOff } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { formatDate } from '../api/client'
import { navigateTo } from '../utils/navigate.js'

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
    <button type="button" className={`cc-live cc-live--${tone}`} title={title}
      onClick={(e) => { e.stopPropagation(); navigateTo('uptime', { q: domain }) }}>
      {uptime && (
        <span className="cc-live-up">
          <span className={`cc-live-dot${down ? ' is-down' : up ? ' is-up' : ''}`} aria-hidden="true" />
          {down ? t('live.down') : up ? t('live.up') : t('live.unknown')}
          {uptime.last_ms != null && up && <span className="cc-live-muted"> {uptime.last_ms}ms</span>}
        </span>
      )}
      {/* Açık alarm = zil ikonu + seviye (2026-09-20): "alarm AÇIK · CRITICAL" metni dar footer'da kesiliyordu (K harfi). */}
      <span className={`cc-live-alert${open ? ' is-open' : ''}`}>
        {uptime ? <span className="cc-live-sep" aria-hidden="true">·</span> : null}
        {!alert ? t('live.noAlert')
          : open ? <><BellRing size={11} className="cc-live-bell" aria-label={t('live.alertOpen')} /> {levelShort(alert.level, t)}</>
          : <><BellOff size={11} className="cc-live-bell cc-live-bell--off" aria-hidden="true" /> {t('live.alertAgo', ago(alert.resolved_at || alert.at, t) || '—')}</>}
      </span>
    </button>
  )
}

export default memo(CertificateLiveStrip)
