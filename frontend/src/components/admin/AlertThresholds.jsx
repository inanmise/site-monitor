import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Gauge, Info, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import AdminChangeHistory from './AdminChangeHistory.jsx'
import { SettingsSection } from './SettingsControls.jsx'
import ThresholdCard from './thresholds/ThresholdCard.jsx'
import ThresholdEditor from './thresholds/ThresholdEditor.jsx'
import TierCoverage from './thresholds/TierCoverage.jsx'
import { BUILTIN, TIERS, daysOf, tierOf } from './thresholds/thresholdModel.js'
import { Button } from '@/components/shadcn/button'
import { Skeleton } from '@/components/shadcn/skeleton'

/** Önizleme yanıtı kullanılabilir mi (sahte/boş yanıt "undefined alan" yazdırmasın). */
function usableImpact(d) {
  return d && typeof d === 'object' && !Array.isArray(d) && Number.isFinite(Number(d.scope_total))
}

/**
 * Uyarı Eşik Değerleri — VARSAYILAN satır + tier satırları (2026-09-20; shadcn yeniden tasarım 2026-09-26).
 *
 * <p>Tier satırı o tier'daki alanlar için varsayılanın YERİNE geçer (Tier 1 müşteri yüzü → daha erken uyarı).
 * Her kart sayıların ANLAMINI gösterir: kalan gün ekseninde renkli ölçek (Kritik · Yüksek · Uyarı · Alarm yok),
 * seviye başına "şu an N alan" (önizleme ucu, satırın kendi değerleriyle) ve yeniden uyarı aralığı. Düzenleme
 * penceresi canlı etki önizlemesi verir: körlemesine eşik değişimi bir gecede onlarca KRİTİK alarm üretebilir.
 *
 * <p>Yetki: ekran yalnız ADMIN'e açılır (AdminPanel); düzenleme/silme/ekleme ayrıca `thresholds.edit`
 * izni ister — sunucu kapısıyla aynı (AdminController.requirePerm). İzin anlık görüntüsü henüz gelmemişse
 * düzenleme denetimleri gizli kalır ama "salt okunur" bandı çizilmez (ilk açılışta yanıp sönmesin).
 */
export default function AlertThresholds() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const { perms, canEdit } = usePermissions()
  const editable = canEdit('thresholds.edit')
  const permsKnown = Boolean(perms) && Object.keys(perms).length > 0
  const phone = useIsMobile()

  const [thresholds, setThresholds] = useState(null)   // null = ilk yükleme sürüyor
  // Yükleme hatası GÖRÜNÜR olmalı: eskiden liste boş kalıyor, yönetici "eşikler silinmiş" sanıp elle
  // yeniden giriyordu (adminPanelLoadError.test.jsx).
  const [loadError, setLoadError] = useState(null)
  const [impact, setImpact] = useState({})             // { default | 1..4: önizleme yanıtı }
  const [editor, setEditor] = useState(null)           // { mode, row, title }
  const seq = useRef(0)

  /** Kapsam başına "bugün kaç alan hangi seviyede" — satırın KENDİ değerleriyle önizleme (5 istek, paralel). */
  const loadImpact = useCallback(async (rows, my) => {
    if (typeof api.admin.previewThreshold !== 'function') return
    const def = rows.find(r => tierOf(r) == null) || BUILTIN
    const byTier = new Map(rows.filter(r => tierOf(r) != null && r.active !== false).map(r => [tierOf(r), r]))
    const scopes = [null, ...TIERS]
    const results = await Promise.allSettled(scopes.map(tier => {
      const d = daysOf(tier == null ? def : (byTier.get(tier) || def))
      return api.admin.previewThreshold({ tier, warning: d.warning, high: d.high, critical: d.critical })
    }))
    if (my !== seq.current) return
    const next = {}
    results.forEach((r, i) => {
      if (r.status === 'fulfilled' && r.value?.success && usableImpact(r.value.data)) next[scopes[i] ?? 'default'] = r.value.data
    })
    setImpact(next)
  }, [])

  const load = useCallback(async () => {
    const my = ++seq.current
    try {
      const res = await api.admin.getThresholds()
      if (my !== seq.current) return
      if (res?.success) {
        const rows = Array.isArray(res.data) ? res.data : []
        setThresholds(rows)
        setLoadError(null)
        if (rows.length > 0) loadImpact(rows, my)
        else setImpact({})
      } else {
        setLoadError(res?.error || t('settings.loadError'))
      }
    } catch (e) {
      if (my === seq.current) setLoadError(e?.message || t('settings.loadError'))
    }
  }, [t, loadImpact])

  useEffect(() => { load() }, [load])

  // Varsayılan önce, sonra tier 1..4.
  const rows = useMemo(() => [...(thresholds || [])].sort((a, b) => (tierOf(a) ?? 0) - (tierOf(b) ?? 0)), [thresholds])
  const savedDefault = rows.find(r => tierOf(r) == null) || null
  // Tier satırları var ama varsayılan yoksa sunucu yerleşik değerleri kullanır → kartta açıkça göster.
  const defaultRow = savedDefault || { ...BUILTIN, tier: null, _builtin: true }
  const tierRows = rows.filter(r => tierOf(r) != null)
  const overrides = new Set(tierRows.map(tierOf))
  const defaultReAlert = Number(defaultRow.re_alert_interval_hours ?? BUILTIN.re_alert_interval_hours)

  const tierCounts = {}
  for (const n of TIERS) if (impact[n]) tierCounts[n] = Number(impact[n].scope_total)
  // Sınıflandırılmamış alan = varsayılan kapsamı − kendi satırı olmayan tier'lar (hepsi biliniyorsa).
  const freeTiers = TIERS.filter(n => !overrides.has(n))
  const unclassified = impact.default && freeTiers.every(n => tierCounts[n] != null)
    ? Math.max(0, Number(impact.default.scope_total) - freeTiers.reduce((a, n) => a + tierCounts[n], 0))
    : null

  const titleOf = (row) => (tierOf(row) ? t(`inv.tier${tierOf(row)}`) : t('thr.defaultTitle'))

  function openEdit(row) {
    setEditor({ mode: row._builtin ? 'create' : 'edit', row, title: titleOf(row) })
  }

  function openAdd(tier) {
    const d = daysOf(defaultRow)
    setEditor({
      mode: 'create',
      row: {
        tier, warning_days: d.warning, high_days: d.high, critical_days: d.critical,
        re_alert_interval_hours: defaultReAlert,
      },
      title: t(`inv.tier${tier}`),
    })
  }

  function onSaved(mode) {
    setEditor(null)
    toast.success(mode === 'create' ? t('thr.created') : t('thr.saved'))
    load()
  }

  async function remove(row) {
    const d = daysOf(defaultRow)
    const affected = impact[tierOf(row)]?.scope_total
    const ok = await showConfirm({
      title: t('thr.deleteTitle'),
      message: [
        t('thr.deleteFallback', titleOf(row), d.warning, d.high, d.critical),
        affected != null ? t('thr.deleteAffected', affected) : null,
      ].filter(Boolean).join('\n'),
      confirmText: t('thr.delete'),
      variant: 'danger',
    })
    if (!ok) return
    try {
      const res = await api.admin.deleteThreshold(row.id)
      if (res?.success) { toast.success(t('thr.deleted')); load() }
      else toast.error(res?.error || t('thr.deleteError'))
    } catch (e) {
      toast.error(e?.message || t('thr.deleteError'))
    }
  }

  const description = (
    <span className="block max-w-prose">
      {t('thr.lead')}{' '}
      <HintPopover
        triggerClassName="inline-flex items-center gap-1 align-baseline text-primary underline-offset-4 hover:underline pointer-coarse:min-h-10"
        className="max-w-[min(24rem,calc(100vw-1rem))] whitespace-normal"
        content={(
          <ul className="flex list-disc flex-col gap-1.5 pl-4">
            {['thr.how1', 'thr.how2', 'thr.how3', 'thr.how4'].map(k => <li key={k}>{t(k)}</li>)}
          </ul>
        )}>
        <Info size={14} aria-hidden="true" /> {t('thr.howTitle')}
      </HintPopover>
    </span>
  )

  const retry = (
    <Button type="button" variant="outline" size="sm" onClick={load}>
      <RefreshCw aria-hidden="true" /> {t('thr.retry')}
    </Button>
  )

  let body
  if (thresholds == null && loadError) {
    body = <AlertBanner tone="danger" role="alert" title={t('settings.loadError')} actions={retry} className="mb-0">{String(loadError)}</AlertBanner>
  } else if (thresholds == null) {
    body = (
      <div role="status" aria-label={t('app.loading')} data-slot="threshold-loading" className="flex flex-col gap-3">
        <Skeleton className="h-14 w-full rounded-lg" />
        <Skeleton className="h-44 w-full rounded-xl" />
        <Skeleton className="h-44 w-full rounded-xl" />
      </div>
    )
  } else if (rows.length === 0) {
    body = (
      <StatusBlock tone="info" icon={Gauge} className="rounded-xl border border-dashed"
        title={t('thr.emptyTitle')}
        description={t('thr.emptyDesc', BUILTIN.warning_days, BUILTIN.high_days, BUILTIN.critical_days, BUILTIN.re_alert_interval_hours)}
        actions={editable ? (
          <Button type="button" onClick={() => openEdit(defaultRow)}>{t('thr.setUpDefault')}</Button>
        ) : null} />
    )
  } else {
    body = (
      <>
        <TierCoverage overrides={overrides} counts={tierCounts} unclassified={unclassified}
          canEdit={editable} phone={phone} onAdd={openAdd} />
        <div data-slot="threshold-list" className="grid grid-cols-[repeat(auto-fit,minmax(min(420px,100%),1fr))] gap-3">
          {[defaultRow, ...tierRows].map(row => {
            const tier = tierOf(row)
            return (
              <ThresholdCard key={row.id ?? 'builtin'} row={row} title={titleOf(row)}
                impact={impact[tier ?? 'default']} defaultReAlert={defaultReAlert}
                canEdit={editable} phone={phone}
                onEdit={() => openEdit(row)} onDelete={() => remove(row)} />
            )
          })}
        </div>
      </>
    )
  }

  // Kök kart tam içerik genişliğinde (kullanıcı geri bildirimi 2026-09-26: "Yönetim Paneli dar görünüyor"):
  // kökte max-w YOK; kartlar geniş ekranda yan yana (auto-fit ızgara), yalnız açıklama metni max-w-prose.
  return (
    <SettingsSection level={3} title={t('thr.title')} description={description}
      contentClassName="flex flex-col gap-4">
      {permsKnown && !editable && <AlertBanner tone="info" className="mb-0">{t('thr.readOnly')}</AlertBanner>}
      {thresholds != null && loadError && (
        <AlertBanner tone="warning" title={t('thr.staleTitle')} actions={retry} className="mb-0">{String(loadError)}</AlertBanner>
      )}
      {body}

      <AdminChangeHistory resource="ALERT_THRESHOLD" />

      {editor && (
        <ThresholdEditor target={editor} defaultRow={savedDefault}
          scopeTotal={impact[tierOf(editor.row) ?? 'default']?.scope_total ?? null}
          onClose={() => setEditor(null)} onSaved={onSaved} />
      )}
    </SettingsSection>
  )
}
