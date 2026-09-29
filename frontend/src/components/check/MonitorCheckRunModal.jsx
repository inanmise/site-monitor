import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CheckRunShell, { CELL } from './CheckRunShell.jsx'
import { monitorCheckColumns } from './monitorCheckColumns.jsx'

/** Sunucu "yürütülmedi: elle k6 kotası dolu" dediği satır (D-10 / O-b3). */
export const isPoolBusyRow = (r) => r?.data?.skipped_code === 'MANUAL_POOL_BUSY'

/**
 * "Şimdi Kontrol Et" akan ilerleme tablosu — İZLEME sürümü.
 *
 * <p>Sertifika ikizi ({@code CheckRunModal}) ile AYNI iskeleti kullanır, dolayısıyla ilerleme
 * çubuğu, özet şeridi, akış kaydırması ve Durdur davranışı birebir aynıdır. Değişen yalnız
 * kolonlar: alan adı/tier/kalan gün yerine izleme adı, hedefi ve türe özgü ölçüler.
 *
 * <p>Havuz dolu olduğu için YÜRÜTÜLMEYEN kontroller (sentetik elle k6 kotası) satırda ✕ ile kalır ve
 * özetin altında SAYIYLA söylenir: kart eski sonucu gösterdiği için kullanıcı onu "yeni" sanmasın.
 *
 * @param {Object|null} run  {@code useCheckRun} koşum durumu; null → hiçbir şey çizilmez
 * @param {string} type      kanonik izleme türü — kolonları o belirler
 */
export default function MonitorCheckRunModal({ run, type, onClose, onCancel }) {
  const t = useT()
  const { targetOf, columns } = useMemo(() => monitorCheckColumns(type, t), [type, t])

  const allColumns = useMemo(() => [
    { key: 'target', label: t('mon.checkColTarget'),
      thClassName: CELL.left, tdClassName: CELL.target,
      render: r => targetOf(r.monitor) || '—' },
    ...columns,
  ], [t, targetOf, columns])

  const poolBusy = run ? run.rows.filter(isPoolBusyRow).length : 0

  return (
    <CheckRunShell
      run={run}
      nameHeader={t('mon.checkColMonitor')}
      nameOf={r => r.monitor?.name || '—'}
      columns={allColumns}
      notice={poolBusy > 0
        ? <AlertBanner tone="warning" className="mb-2">{t('check.poolBusySkipped', poolBusy)}</AlertBanner>
        : null}
      onClose={onClose}
      onCancel={onCancel}
    />
  )
}
