import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import CheckRunShell, { fmtDay, daysClass, httpClass, CELL } from './CheckRunShell.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

// Tier rozeti tonları (eski .chk-tier-1..4): 1 kırmızı, 2 turuncu, 3 mavi, 4 nötr — koyu karşılıklarıyla.
const TIER_TONE = {
  1: 'bg-red-100 text-red-700 dark:bg-red-950 dark:text-red-200',
  2: 'bg-orange-100 text-orange-700 dark:bg-orange-950 dark:text-orange-200',
  3: 'bg-sky-100 text-sky-700 dark:bg-sky-950 dark:text-sky-200',
  4: 'bg-muted text-muted-foreground',
}

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
    { key: 'team', label: t('app.checkColTeam'), thClassName: CELL.left, tdClassName: CELL.team,
      render: r => (certIndex?.[r.domain] || {}).team_name || '—' },
    { key: 'tier', label: t('app.checkColTier'), tdClassName: '',
      render: r => {
        const tier = (certIndex?.[r.domain] || {}).tier ?? null
        return tier
          ? <Badge variant="secondary" data-tier={tier} className={cn('rounded-[5px] px-1.5 py-px text-[.82em] font-extrabold tracking-[.02em]', TIER_TONE[tier])}>T{tier}</Badge>
          : '—'
      } },
    { key: 'port', label: t('app.checkColPort'),
      render: r => (r.data || {}).port ?? (certIndex?.[r.domain] || {}).port ?? '—' },
    { key: 'http', label: t('app.checkColHttp'),
      tdClassName: r => `${CELL.mono} ${httpClass((r.data || {}).http_status ?? null)}`,
      render: r => (r.data || {}).http_status ?? '—' },
    { key: 'days', label: t('app.checkColDays'),
      tdClassName: r => `${CELL.mono} ${CELL.days} ${daysClass((r.data || {}).days_remaining ?? null)}`,
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
