import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import CheckRunShell, { fmtDay, daysClass, httpClass } from './CheckRunShell.jsx'

/**
 * "Şimdi Kontrol Et" akan ilerleme tablosu — SERTİFİKA sürümü. Her satır tek bir kontrolün
 * SONUCUNU taşır: durum · alan adı · takım · tier · port · HTTP · kalan gün · bitiş · süre.
 *
 * Durum kendi kolonunda (eskiden alan adıyla aynı hücredeydi ve satırlar oynuyordu);
 * alan adı sola, sayısal kolonlar sağa yaslı monospace.
 *
 * <p>İskelet (ilerleme çubuğu, özet, akış kaydırması, duvar saati, Durdur/Kapat)
 * {@link CheckRunShell}'e taşındı; burada kalan YALNIZCA sertifikaya özgü kolonlardır.
 * İzleme sayfalarının karşılığı {@code MonitorCheckRunModal} aynı iskeleti kullanır —
 * ikisi bu yüzden birebir aynı görünür.
 */
export default function CheckRunModal({ run, certIndex, onClose, onCancel }) {
  const t = useT()

  const columns = useMemo(() => [
    { key: 'team', label: t('app.checkColTeam'), thClassName: 'chk-th-team', tdClassName: 'chk-td-team',
      render: r => (certIndex?.[r.domain] || {}).team_name || '—' },
    { key: 'tier', label: t('app.checkColTier'), tdClassName: '',
      render: r => {
        const tier = (certIndex?.[r.domain] || {}).tier ?? null
        return tier ? <span className={`chk-tier chk-tier-${tier}`}>T{tier}</span> : '—'
      } },
    { key: 'port', label: t('app.checkColPort'),
      render: r => (r.data || {}).port ?? (certIndex?.[r.domain] || {}).port ?? '—' },
    { key: 'http', label: t('app.checkColHttp'),
      tdClassName: r => `chk-mono ${httpClass((r.data || {}).http_status ?? null)}`,
      render: r => (r.data || {}).http_status ?? '—' },
    { key: 'days', label: t('app.checkColDays'),
      tdClassName: r => `chk-mono chk-days ${daysClass((r.data || {}).days_remaining ?? null)}`,
      render: r => {
        const days = (r.data || {}).days_remaining ?? null
        return days == null ? '—' : t('app.checkDaysUnit', days)
      } },
    { key: 'expiry', label: t('app.checkColExpiry'), render: r => fmtDay((r.data || {}).not_after) },
  ], [t, certIndex])

  return (
    <CheckRunShell
      run={run}
      nameHeader={t('app.checkColDomain')}
      nameOf={r => r.domain}
      columns={columns}
      onClose={onClose}
      onCancel={onCancel}
    />
  )
}
