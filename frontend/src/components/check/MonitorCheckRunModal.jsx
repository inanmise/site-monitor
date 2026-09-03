import { useMemo } from 'react'
import { useT } from '../../i18n/index.jsx'
import CheckRunShell from './CheckRunShell.jsx'
import { monitorCheckColumns } from './monitorCheckColumns.jsx'

/**
 * "Şimdi Kontrol Et" akan ilerleme tablosu — İZLEME sürümü.
 *
 * <p>Sertifika ikizi ({@code CheckRunModal}) ile AYNI iskeleti kullanır, dolayısıyla ilerleme
 * çubuğu, özet şeridi, akış kaydırması ve Durdur davranışı birebir aynıdır. Değişen yalnız
 * kolonlar: alan adı/tier/kalan gün yerine izleme adı, hedefi ve türe özgü ölçüler.
 *
 * @param {Object|null} run  {@code useCheckRun} koşum durumu; null → hiçbir şey çizilmez
 * @param {string} type      kanonik izleme türü — kolonları o belirler
 */
export default function MonitorCheckRunModal({ run, type, onClose, onCancel }) {
  const t = useT()
  const { targetOf, columns } = useMemo(() => monitorCheckColumns(type, t), [type, t])

  const allColumns = useMemo(() => [
    { key: 'target', label: t('mon.checkColTarget'),
      thClassName: 'chk-th-target', tdClassName: 'chk-td-target',
      render: r => targetOf(r.monitor) || '—' },
    ...columns,
  ], [t, targetOf, columns])

  return (
    <CheckRunShell
      run={run}
      nameHeader={t('mon.checkColMonitor')}
      nameOf={r => r.monitor?.name || '—'}
      columns={allColumns}
      onClose={onClose}
      onCancel={onCancel}
    />
  )
}
