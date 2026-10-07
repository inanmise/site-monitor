import { useMemo, useState } from 'react'
import { BarChart3, Layers, ShieldCheck, CalendarClock, CalendarX, AlertOctagon, UserX, ServerOff, Star, PauseCircle, CheckCircle2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import MonitorStatsBar from '../MonitorStatsBar.jsx'
import CollapsibleSection from '../ui/CollapsibleSection.jsx'
import { TILES, tileCounts, activeTile } from './inventoryModel.js'

const ICON = {
  total: Layers, active: CheckCircle2, valid: ShieldCheck, expiring: CalendarClock, expired: CalendarX, errors: AlertOctagon,
  noContacts: UserX, noPlatform: ServerOff, tier1: Star, inactive: PauseCircle,
}
const TONE = {
  total: 'total', active: 'valid', valid: 'valid', expiring: 'warning', expired: 'expired', errors: 'error',
  noContacts: 'high', noPlatform: 'paused', tier1: 'critical', inactive: 'paused',
}
const LABEL = {
  total: 'inv.tileTotal', active: 'inv.tileActive', valid: 'inv.tileValid', expiring: 'inv.tileExpiring', expired: 'inv.tileExpired', errors: 'inv.tileErrors',
  noContacts: 'inv.tileNoContacts', noPlatform: 'inv.tileNoPlatform', tier1: 'inv.tileTier1', inactive: 'inv.tileInactive',
}
const OPEN_KEY = 'inv-stats-open'

/**
 * Özet kartları (2026-09-27): katlanır "Özet" şeridi (ui/CollapsibleSection — projenin tek katlanır şeridi) + tıklanabilir
 * sayım kartları (MonitorStatsBar; `aria-pressed`, dengeli tam genişlik). Her kart mevcut bir süzgece eşlenir
 * (inventoryModel TILES): basınca süzgeç uygulanır, basılı hâli süzgeç durumundan türer. Veri desteklemeyen kart
 * çizilmez: hiçbir kayıtta platform yoksa "Platformsuz", hiç katman yoksa "T1", pasif yoksa o kart yok. ("Silinmiş" kartı
 * 2026-10-07'de kalktı: silme kalıcı, çöp kutusu yok.)
 * Test kancaları: MonitorStatsBar'ın `data-slot="stat-item"` + `data-key`.
 */
export default function InventoryStats({ items, statusFilter, filters, onTile }) {
  const t = useT()
  // Kayıtlı tercih yoksa telefonda KAPALI başlar (11 kart ~600 px — listeyi ekranın altına itiyordu), geniş ekranda açık.
  const [open, setOpen] = useState(() => {
    try {
      const v = localStorage.getItem(OPEN_KEY)
      if (v != null) return v !== 'false'
    } catch { /* depolama yok */ }
    try { return !window.matchMedia('(max-width: 767px)').matches } catch { return true }
  })
  const counts = useMemo(() => tileCounts(items), [items])
  const supported = useMemo(() => ({
    noPlatform: (items || []).some((r) => r.platform),
    tier1: (items || []).some((r) => r.tier != null),
    inactive: counts.inactive > 0,
  }), [items, counts])
  const tiles = TILES.filter((tile) => supported[tile.key] !== false).map((tile) => ({
    key: tile.key, Icon: ICON[tile.key], cls: TONE[tile.key], value: counts[tile.key], label: t(LABEL[tile.key]), hint: t(`${LABEL[tile.key]}Hint`),
    // "Toplam" süzgeç değil EYLEM kartı: basınca kart süzgeci kalkar (aria-pressed taşımaz); ad kısa etiket, açıklama ipucunda.
    ...(tile.key === 'total' ? { onClick: () => onTile('total'), tip: t('inv.tileTotal') } : {}),
  }))
  const active = activeTile(statusFilter, filters)
  const toggle = (next) => { setOpen(next); try { localStorage.setItem(OPEN_KEY, String(next)) } catch { /* yoksay */ } }
  if (!items?.length) return null
  return (
    <CollapsibleSection open={open} onOpenChange={toggle} icon={BarChart3} label={t('inv.summary')} hint={t('inv.summaryHint')}
      toggleLabel={open ? t('app.collapseStats') : t('app.expandStats')} triggerClassName="mb-2" data-slot="inv-stats">
      <MonitorStatsBar items={tiles} activeFilter={active} onStatClick={onTile} />
    </CollapsibleSection>
  )
}
