import { useCallback, useEffect, useState } from 'react'
import { ServerCog } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useVisibleInterval } from '../../hooks/useVisibleInterval.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Button } from '@/components/shadcn/button'
import { serverOffset, windowText } from '../../utils/systemMaintenance.js'
import { SETTINGS_STACK, SettingsHeader } from './SettingsControls.jsx'
import SysMaintStatusCard from './sysmaint/SysMaintStatusCard.jsx'
import { SysMaintPlanDialog, SysMaintStartNowDialog } from './sysmaint/SysMaintFormDialogs.jsx'
import SysMaintExtendDialog from './sysmaint/SysMaintExtendDialog.jsx'
import SysMaintPreview from './sysmaint/SysMaintPreview.jsx'
import SysMaintHistory from './sysmaint/SysMaintHistory.jsx'
import { DEFAULT_OPTIONS, phaseMeta } from './sysmaint/sysmaintModel.js'

/** Sayfa açıkken durum yoklaması (ms) — sekme gizliyken durur. */
const POLL_MS = 15_000

/**
 * Ayarlar → Platform → "Sistem Bakımı" (2026-10-02, kullanıcı kararı) — global yönetici SiteMonitor'ün KENDİSİNİ bakıma
 * alır. YALNIZ global yönetici (kabuk kapsamlı müdüre "yalnız global yönetici" notu çizer; sunucu her uçta 403).
 * İzleme hedeflerinin "Bakım Pencereleri" sayfası AYRI bir özelliktir.
 *
 * Düzen (mobil-önce, shadcn): bölüm başlığı → DURUM kartı (rozet + geri sayım + etki özeti + eylemler) → diğer planlı
 * bakımlar → ÖNİZLEME (giriş kartı / duyuru şeridi / uyarı şeridi / geri sayım penceresi / bitiş şeridi, TR/EN) → GEÇMİŞ
 * (tablo/kart + ayrıntı; duyuru ve "bakım tamamlandı" e-postası durumu). Eylemler: Bakım planla / Düzenle (form penceresi, alan yanında doğrulama), Hemen bakıma al (form + onay),
 * Uzat (+15/+30/+60 / yeni saat), Hemen bitir ve İptal et (onay penceresi, etki özetiyle).
 *
 * Saat: geri sayımlar ve varsayılan form saatleri SUNUCU saatine göre (`server_now` farkı).
 * Test kancası: `data-testid="sysmaint-settings"`.
 */
export default function SystemMaintenanceSettings() {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [data, setData] = useState(null)
  const [offset, setOffset] = useState(0)
  const [error, setError] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState(null)   // {kind: 'plan'|'edit'|'start'|'extend', window?}
  const [historyKey, setHistoryKey] = useState(0)

  const load = useCallback(async () => {
    setRefreshing(true)
    try {
      const r = await api.systemMaintenance.overview()
      if (r?.success) {
        setData(r.data)
        setOffset(serverOffset(r.data?.server_now))
        setError(null)
      } else {
        setError(r?.error || t('sysmaint.err.generic'))
      }
    } catch (e) {
      // Ağ hatası (request() throw eder): eskiden yakalanmıyor, ilk yüklemede sayfa sonsuza dek "yükleniyor" kalıyordu.
      setError(e?.message || t('sysmaint.err.generic'))
    } finally {
      setRefreshing(false)
    }
  }, [t])
  useEffect(() => { load() }, [load])
  useVisibleInterval(load, POLL_MS, false)

  const serverNowMs = Date.now() + offset
  const options = data?.options || DEFAULT_OPTIONS
  const current = data?.current || null
  const others = (data?.windows || []).filter((w) => w.id !== current?.id)

  const changed = () => { setDialog(null); setHistoryKey((k) => k + 1); load() }

  async function confirmAndRun(kind, w) {
    const impact = data?.impact
    const cfg = kind === 'end'
      ? { title: t('sysmaint.confirm.endTitle'), message: t('sysmaint.confirm.endBody', windowText(w)), confirmText: t('sysmaint.confirm.endOk'), variant: 'warning' }
      : { title: t('sysmaint.confirm.cancelTitle'),
          message: [t('sysmaint.confirm.cancelBody', windowText(w)),
            (w.email_all_users || (w.email_team_ids || []).length) && w.email_corrections ? t('sysmaint.confirm.cancelMail') : null]
            .filter(Boolean).join('\n\n'),
          confirmText: t('sysmaint.confirm.cancelOk'), variant: 'danger' }
    if (kind === 'end' && impact) cfg.message += '\n\n' + t('sysmaint.confirm.endImpact')
    const ok = await showConfirm({ ...cfg, cancelText: t('app.cancel') })
    if (!ok) return
    setBusy(true)
    try {
      const r = kind === 'end' ? await api.systemMaintenance.endNow(w.id) : await api.systemMaintenance.cancel(w.id)
      if (r?.success) {
        toast.success(kind === 'end' ? t('sysmaint.toast.ended') : t('sysmaint.toast.cancelled'))
        changed()
      } else {
        toast.error(r?.error || t('sysmaint.err.generic'))
        load()
      }
    } catch (e) {
      toast.error(e?.message || t('sysmaint.err.generic'))
      load()
    } finally {
      setBusy(false)
    }
  }

  const phase = current?.phase || 'none'
  return (
    <div className={SETTINGS_STACK} data-testid="sysmaint-settings">
      <SettingsHeader icon={ServerCog} title={t('sysmaint.title')} description={t('sysmaint.desc')} hint={t('sysmaint.notTargets')}
        meta={data ? <span data-status={phase}>{t(phaseMeta(phase).key)}</span> : null} />

      {!data && !error && <LoadingBlock label={t('app.loading')} />}
      {error && !data && (
        <StatusBlock tone="danger" title={t('sysmaint.err.load')} description={error}
          actions={<Button type="button" variant="outline" onClick={load} className="min-h-10">{t('sysmaint.retry')}</Button>} />
      )}
      {error && data && <AlertBanner tone="warning" className="mb-0">{t('sysmaint.err.stale', error)}</AlertBanner>}

      {data && (
        <>
          <SysMaintStatusCard current={current} impact={data.impact} offsetMs={offset} busy={busy}
            refreshing={refreshing} onRefresh={load}
            onPlan={() => setDialog({ kind: 'plan' })}
            onStartNow={() => setDialog({ kind: 'start' })}
            onEdit={(w) => setDialog({ kind: 'edit', window: w })}
            onExtend={(w) => setDialog({ kind: 'extend', window: w })}
            onCancel={(w) => confirmAndRun('cancel', w)}
            onEndNow={(w) => confirmAndRun('end', w)} />

          {current && phase !== 'active' && (
            <div className="flex flex-wrap items-center justify-end gap-2">
              <Button type="button" variant="outline" onClick={() => setDialog({ kind: 'plan' })} className="min-h-10" data-slot="sysmaint-plan-another">
                {t('sysmaint.action.planAnother')}
              </Button>
            </div>
          )}

          {others.length > 0 && (
            <ul data-slot="sysmaint-upcoming" className="m-0 flex list-none flex-col gap-2 p-0" aria-label={t('sysmaint.upcoming')}>
              {others.map((w) => (
                <li key={w.id} className="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-lg border bg-card px-3 py-2">
                  <span className="min-w-0 text-sm [overflow-wrap:anywhere]">{t('sysmaint.windowLine', windowText(w))}</span>
                  <span className="flex flex-wrap gap-2">
                    <Button type="button" variant="outline" size="sm" className="min-h-10 sm:min-h-9" onClick={() => setDialog({ kind: 'edit', window: w })}>
                      {t('sysmaint.action.edit')}
                    </Button>
                    <Button type="button" variant="ghost" size="sm" className="min-h-10 sm:min-h-9" onClick={() => confirmAndRun('cancel', w)}>
                      {t('sysmaint.action.cancel')}
                    </Button>
                  </span>
                </li>
              ))}
            </ul>
          )}

          <SysMaintPreview current={current} serverNowMs={serverNowMs} />
          <SysMaintHistory refreshKey={historyKey} />
        </>
      )}

      <SysMaintPlanDialog open={dialog?.kind === 'plan' || dialog?.kind === 'edit'} onClose={() => setDialog(null)}
        window={dialog?.kind === 'edit' ? dialog.window : null} serverNowMs={serverNowMs} options={options}
        recipients={data?.recipients} impact={data?.impact} windows={data?.windows || []} onSaved={changed} />
      <SysMaintStartNowDialog open={dialog?.kind === 'start'} onClose={() => setDialog(null)} options={options}
        impact={data?.impact} recipients={data?.recipients} onSaved={changed} />
      <SysMaintExtendDialog open={dialog?.kind === 'extend'} onClose={() => setDialog(null)} window={dialog?.window}
        options={options} onSaved={changed} />
    </div>
  )
}
