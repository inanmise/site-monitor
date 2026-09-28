import { lazy, Suspense, useState } from 'react'
import { Eye, ArrowRightLeft, ArchiveRestore, Send } from 'lucide-react'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { api, formatDateOnly } from '../../api/client'
import { Button } from '@/components/shadcn/button'
import { conflictActions } from './domainConflictModel.js'

// İç içe pencereler yalnız istendiğinde yüklenir — form paketine sertifika penceresinin ağırlığını katmaz.
const CertificateModal = lazy(() => import('../CertificateModal.jsx'))
const IssueReportModal = lazy(() => import('../IssueReportModal.jsx'))

/**
 * Telefonda tam genişlik + ≥40 px dokunma hedefi; sm+ satır içi (alt çubuk düğmeleriyle aynı 36 px), dokunmatikte yine
 * 40 px. Uzun ekip adlı etiket ("'…' ekibine aktar") SARAR — nowrap düğme dar ekranda bandı görünüm alanından taşırıyordu.
 */
const ACTION_BTN = 'h-auto min-h-10 w-full justify-center py-2 whitespace-normal sm:min-h-9 sm:w-auto pointer-coarse:min-h-10'
/**
 * AlertBanner ızgarasının orta sütunu varsayılan `1fr` (= minmax(auto, 1fr)): içerikteki uzun, kırılmayan parça (ekip adı
 * rozeti) sütunu içerik genişliğine zorluyor, 390 px'te bant ekrandan taşıyordu (Playwright ölçümü). minmax(0,1fr) ile
 * sütun kaba sığar; rozet kırpılır, metin sarar. Paylaşılan AlertBanner'a dokunmadan yalnız bu bantta.
 */
const BANNER_GRID = 'mb-0 grid-cols-[0_minmax(0,1fr)_auto] has-[>svg]:grid-cols-[calc(var(--spacing)*4)_minmax(0,1fr)_auto]'

/**
 * Mükerrer alan adı bandı (2026-09-28, kullanıcı isteği) — envanter formunun alt çubuğunda, 409 `DOMAIN_EXISTS`
 * yanıtından çizilir. Ham bildirim (toast) yerine: sunucunun iletisi, kaydın SAHİBİ takımı (TeamBadge → üyeler
 * penceresi: kime başvurulacağı), yönlendirme ve eylemler.
 *
 * <p>Eylemler sunucunun `can_*` bayraklarına + formda seçili takıma göre (`conflictActions`):
 * <ul>
 *   <li>Kaydı görüntüle — salt okunur sertifika penceresi (org geneli görünürlükteki yabancı satır görünümüyle aynı),
 *       formun ÜSTÜNDE açılır; kapatınca form ve bant yerinde.</li>
 *   <li>'X' ekibine aktar — global yönetici + aktarım izni: iki takımı adıyla söyleyen onay → mevcut
 *       `POST /inventory/{id}/transfer` (çöp kutusundaysa ardından geri yükleme). Başarıda `onResolved`.</li>
 *   <li>Çöp kutusundan geri yükle — geri yükleme kapısı olan: mevcut `POST /inventory/{id}/restore`.</li>
 *   <li>Aktarım talebi oluştur — aktaramayan: "Sorun Bildir" penceresi aktarım kipinde (tür DOMAIN_TRANSFER,
 *       alan adı + mevcut / istenen ekip önceden dolu, gerekçe zorunlu) → global yöneticilerin Sorun Bildirimleri'ne.</li>
 * </ul>
 *
 * @param {{message:string, existing:object}} conflict  `domainConflictOf(res)` çıktısı
 * @param {{id:number|string, name:string}|null} targetTeam  formda seçili ekip
 * @param {(outcome:{kind:'transferred'|'restored', domain:string, record:object|null}) => void} onResolved
 * @param {() => void} [onDismiss]
 */
export default function DomainConflictBanner({ conflict, targetTeam, onResolved, onDismiss }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [busy, setBusy] = useState(null)            // 'transfer' | 'restore' | null
  const [viewing, setViewing] = useState(false)
  const [requesting, setRequesting] = useState(false)
  const [requestRef, setRequestRef] = useState('')  // gönderilen talebin referansı — ikinci talep düğmesi gizlenir

  if (!conflict) return null
  const ex = conflict.existing || {}
  const a = conflictActions(ex, targetTeam?.id)
  const owner = ex.team_name || null
  const ownerLabel = owner || t('dupx.noTeam')
  const guide = a.sameTeam
    ? (a.deleted ? (a.restore ? t('dupx.guideBinSame') : t('dupx.askManager')) : t('dupx.guideSame'))
    : (a.transfer ? t('dupx.guideAdmin') : t('dupx.guideTransfer'))

  async function transfer() {
    const ok = await showConfirm({
      title: t('dupx.transferTitle'),
      message: t(a.deleted ? 'dupx.restoreTransferMsg' : 'dupx.transferMsg', ex.domain, ownerLabel, targetTeam.name),
      confirmText: t('dupx.transferConfirm'),
      cancelText: t('inv.cancel'),
      variant: 'warning',
    })
    if (!ok) return
    setBusy('transfer')
    try {
      const res = await api.admin.transferCertSy(ex.inventory_id, Number(targetTeam.id))
      if (!res?.success) { toast.error(res?.error || t('dupx.transferError')); return }
      let record = res.data ?? null
      if (a.deleted) {
        // Çöp kutusundaki kayıt: aktarım silinmişliği kaldırmaz — ikinci adım geri yükleme (aynı yönetici kapısı).
        const back = await api.admin.restoreInventory(ex.inventory_id)
        if (!back?.success) toast.error(back?.error || t('dupx.restoreError'))
        else record = back.data ?? record
      }
      toast.success(t('dupx.transferred', ex.domain, targetTeam.name))
      onResolved?.({ kind: 'transferred', domain: ex.domain, record })
    } catch (e) {
      toast.error(e?.message || t('dupx.transferError'))
    } finally {
      setBusy(null)
    }
  }

  async function restore() {
    const ok = await showConfirm({
      title: t('inv.restoreTitle'),
      message: t('inv.restoreMsg', ex.domain),
      confirmText: t('inv.restoreConfirm'),
      cancelText: t('inv.cancel'),
      variant: 'warning',
    })
    if (!ok) return
    setBusy('restore')
    try {
      const res = await api.admin.restoreInventory(ex.inventory_id)
      if (!res?.success) { toast.error(res?.error || t('dupx.restoreError')); return }
      toast.success(t('inv.restored'))
      onResolved?.({ kind: 'restored', domain: ex.domain, record: res.data ?? null })
    } catch (e) {
      toast.error(e?.message || t('dupx.restoreError'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <>
      <AlertBanner tone="warning" role="alert" className={BANNER_GRID}
        title={a.deleted ? t('dupx.titleBin') : t('dupx.title')}
        onDismiss={onDismiss} dismissLabel={t('app.close')}>
        <div data-slot="domain-conflict" data-deleted={a.deleted || undefined} className="flex w-full min-w-0 flex-col gap-2">
          <p className="m-0">{conflict.message}</p>
          <div data-slot="domain-conflict-owner" className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span className="text-muted-foreground">{t('dupx.owner')}:</span>
            {owner
              ? <TeamBadge teamId={ex.team_id} teamName={owner} />
              : <span className="font-semibold">{ownerLabel}</span>}
            {a.deleted && ex.deleted_at && (
              <span className="text-xs text-muted-foreground">{t('dupx.deletedAt', formatDateOnly(ex.deleted_at))}</span>
            )}
          </div>
          <p className="m-0 text-xs text-muted-foreground">
            {guide}{owner && ex.team_id != null && !a.sameTeam ? ` ${t('dupx.contactHint')}` : ''}
          </p>
          {requestRef && <p data-slot="domain-conflict-requested" className="m-0 text-xs font-semibold">{t('dupx.requestSent', requestRef)}</p>}
          <div data-slot="domain-conflict-actions" className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap">
            {a.view && (
              <Button type="button" size="sm" variant="outline" className={ACTION_BTN} onClick={() => setViewing(true)}>
                <Eye aria-hidden="true" />{t('dupx.view')}
              </Button>
            )}
            {a.restore && (
              <Button type="button" size="sm" variant="outline" className={ACTION_BTN} onClick={restore}
                disabled={!!busy} aria-busy={busy === 'restore' || undefined}>
                {busy === 'restore' ? <Spinner decorative inline /> : <ArchiveRestore aria-hidden="true" />}{t('dupx.restore')}
              </Button>
            )}
            {a.transfer && (
              <Button type="button" size="sm" className={ACTION_BTN} onClick={transfer}
                disabled={!!busy} aria-busy={busy === 'transfer' || undefined}>
                {busy === 'transfer' ? <Spinner decorative inline /> : <ArrowRightLeft aria-hidden="true" />}
                {t(a.deleted ? 'dupx.restoreTransferTo' : 'dupx.transferTo', targetTeam?.name ?? '')}
              </Button>
            )}
            {a.request && !requestRef && (
              <Button type="button" size="sm" variant="outline" className={ACTION_BTN} onClick={() => setRequesting(true)}>
                <Send aria-hidden="true" />{t('dupx.request')}
              </Button>
            )}
          </div>
        </div>
      </AlertBanner>

      {viewing && (
        <Suspense fallback={null}>
          <CertificateModal domain={ex.domain} readOnly readOnlyTeam={{ id: ex.team_id, name: owner }}
            onClose={() => setViewing(false)} />
        </Suspense>
      )}
      {requesting && (
        <Suspense fallback={null}>
          <IssueReportModal open onClose={() => setRequesting(false)} onSubmitted={(ref) => setRequestRef(ref || '—')}
            domainTransfer={{
              domain: ex.domain,
              inventoryId: ex.inventory_id,
              fromTeam: { id: ex.team_id, name: owner },
              toTeam: { id: targetTeam?.id, name: targetTeam?.name },
              deleted: a.deleted,
            }} />
        </Suspense>
      )}
    </>
  )
}
