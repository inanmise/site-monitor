import { useEffect, useState, lazy, Suspense } from 'react'
import { X, Pencil, Play, ChevronLeft, ChevronRight, Trash2, ExternalLink, ShieldCheck } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { formatDate, formatDateOnly } from '../../api/client'
import { navigateTo } from '../../utils/navigate.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { InventoryDetails, DetailSection, Fact, FactGrid } from './InventoryDetails.jsx'
import { ActiveBadge, CertCell, ExpiryCell, TierBadge, platformLabel } from './InventoryTable.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetTitle } from '@/components/shadcn/sheet'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'

const ChangeHistoryTab = lazy(() => import('../history/ChangeHistoryTab.jsx'))
// Kontroller sekmesi = sertifika penceresinin zengin Kontrol Geçmişi (2026-09-28; eski düz 4 sütunlu upt-rt-* satırlar kalktı).
const CertCheckHistory = lazy(() => import('../certmodal/CertCheckHistory.jsx'))

/**
 * "Sertifika" sekmesi: kayıttaki son kontrol özeti (durum, kalan gün, bitiş, veren, son kontrol, hata, beklenen parmak
 * izi/konu, TLS modu, sıklık, vekil) + "Sertifikayı aç" (Tüm Sertifikalar ekranında bu alan adının penceresi). Hiç kontrol
 * yoksa boş durum + "Şimdi kontrol et" (salt okunurda yok). Test kancası `data-slot="inv-cert-summary"`.
 */
function CertificateSummary({ record, readOnly, onCheckNow, platformNames }) {
  const t = useT()
  const openCert = () => navigateTo('all', { domain: record.domain })
  if (!record.cert_status) {
    return (
      <StatusBlock tone="info" icon={ShieldCheck} title={t('inv.certNever')} description={t('inv.certNoData')}
        actions={<>
          {!readOnly && !record.deleted_at && <Button type="button" size="sm" onClick={() => onCheckNow(record)}><Play aria-hidden="true" /> {t('inv.checkNow')}</Button>}
          <Button type="button" variant="outline" size="sm" onClick={openCert} title={t('inv.openCertHint')}><ExternalLink aria-hidden="true" /> {t('inv.openCert')}</Button>
        </>} />
    )
  }
  return (
    <div data-slot="inv-cert-summary" className="flex min-w-0 flex-col gap-3">
      <DetailSection id="cert" icon={ShieldCheck} title={t('inv.drawerCert')}>
        <FactGrid>
          <Fact label={t('inv.certStatus')} value={<CertCell r={record} t={t} />} />
          <Fact label={t('inv.certDaysLeft')} value={<ExpiryCell r={record} t={t} showDate={false} />} />
          <Fact label={t('inv.certExpiry')} value={record.cert_not_after ? formatDateOnly(record.cert_not_after) : '—'} />
          <Fact label={t('inv.certIssuer')} value={record.cert_issuer || '—'} />
          <Fact label={t('inv.certLastCheck')} value={record.cert_checked_at ? formatDate(record.cert_checked_at) : t('inv.certNever')} />
          <Fact label={t('inv.formPlatform')} value={record.platform ? platformLabel(record.platform, platformNames) : '—'} />
          {record.cert_error && <Fact label={t('inv.certErrorLabel')} value={<span className="text-destructive">{record.cert_error}</span>} full />}
          <Fact label={t('inv.formTlsMode')} value={record.tls_mode === 'browser' ? t('inv.tlsModeBrowser') : record.tls_mode === 'default' ? t('inv.tlsModeDefault') : t('inv.tlsModeInherit')} />
          <Fact label={t('inv.formUseProxy')} value={record.use_proxy ? t('mon.proxy.on') : t('mon.proxy.off')} />
          {record.expected_fingerprint && <Fact label={t('inv.formFP')} value={record.expected_fingerprint} mono full />}
          {record.expected_subject && <Fact label={t('inv.formSubject')} value={record.expected_subject} mono full />}
        </FactGrid>
      </DetailSection>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" size="sm" onClick={openCert} title={t('inv.openCertHint')}><ExternalLink aria-hidden="true" /> {t('inv.openCert')}</Button>
      </div>
    </div>
  )
}

/**
 * Kayıt çekmecesi (2026-09-12, #8; 2026-09-27 yeniden tasarım): sağdan panel — başlık (alan adı + port + durum + katman +
 * takım + aktif + salt okunur rozeti), sekmeler Genel bakış / Sertifika / Değişiklikler / Kontroller, altlıkta eylemler
 * (Şimdi kontrol et · Düzenle · Sil) — salt okunur ya da silinmiş kayıtta altlık yok. Önceki/sonraki kayıt okları
 * (Alt+← / Alt+→). Telefonda tam genişlik; gövde kayar, altlık sabit (safe-area).
 *
 * Çizim shadcn: Sheet + Tabs + Badge + Button. Escape / örtü tıklaması Sheet'in kendisinde; kapat düğmesi i18n'li
 * (yerleşik X kapalı). Rol `dialog`, ad = alan adı (`aria-label`).
 *
 * `readOnly` (org geneli görünürlük, 2026-09-26): başka takımın kaydı — kontrol et / düzenle / sil yok, "Değişiklikler"
 * sekmesi yok (geçmiş takım kapsamlı kalır), başlıkta salt okunur rozet + sahibi takım.
 */
export default function InventoryDrawer({
  record, records = [], teamMap, teamNameById, canManage, canEditRow = () => canManage, readOnly = false, platformNames = {},
  onClose, onEdit, onCheckNow, onDelete, onNavigate,
}) {
  const t = useT()
  const [tab, setTab] = useState('details')
  const idx = record ? records.findIndex((r) => r.id === record.id) : -1
  function step(d) { const n = records[idx + d]; if (n) onNavigate(n) }
  useEffect(() => {
    const onKey = (e) => {
      if (e.altKey && e.key === 'ArrowRight') step(1)
      if (e.altKey && e.key === 'ArrowLeft') step(-1)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })
  if (!record) return null
  const teamName = record.team_name || teamMap?.[String(record.team_id)]
  const del = !!record.deleted_at
  const canAct = !readOnly && !del
  const port = record.port || 443

  return (
    <Sheet open onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side="right" showCloseButton={false} aria-label={record.domain} data-slot="inv-drawer"
        className="flex w-full flex-col gap-0 p-0 sm:max-w-[min(820px,100vw)]">
        {/* Başlık: gezinme okları · alan adı + port · kapat */}
        <div className="flex items-start gap-1.5 border-b px-3 py-2.5 sm:px-4">
          <div className="flex shrink-0 items-center">
            <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10"
              disabled={idx <= 0} onClick={() => step(-1)} aria-label={t('inv.prevRow')}><ChevronLeft aria-hidden="true" /></Button>
            <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10"
              disabled={idx < 0 || idx >= records.length - 1} onClick={() => step(1)} aria-label={t('inv.nextRow')}><ChevronRight aria-hidden="true" /></Button>
          </div>
          <div className="min-w-0 flex-1">
            <SheetTitle className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-base leading-tight">
              <span className="min-w-0 break-all">{record.domain}</span>
              {port !== 443 && <Badge variant="outline" className="font-mono text-muted-foreground">:{port}</Badge>}
            </SheetTitle>
            <SheetDescription className="sr-only">{t('inv.drawerOverview')}</SheetDescription>
            <div data-slot="inv-drawer-badges" className="mt-1.5 flex flex-wrap items-center gap-1.5">
              <CertCell r={record} t={t} />
              <TierBadge tier={record.tier} />
              {teamName && !readOnly && <TeamBadge teamId={record.team_id} teamName={teamName} />}
              {readOnly && <ReadOnlyBadge teamId={record.team_id} teamName={teamName} />}
              {!del && <ActiveBadge r={record} t={t} />}
              {del && <Badge variant="secondary">{t('inv.deletedBadge')}</Badge>}
            </div>
          </div>
          <Button type="button" variant="ghost" size="icon-sm" className="shrink-0 text-muted-foreground pointer-coarse:size-10"
            onClick={onClose} aria-label={t('app.close')}><X aria-hidden="true" /></Button>
        </div>

        <Tabs value={tab} onValueChange={setTab} className="min-h-0 flex-1 gap-0">
          <TabsList variant="line" className="w-full justify-start overflow-x-auto border-b px-3 sm:px-4 pointer-coarse:h-11">
            <TabsTrigger value="details" className="flex-none pointer-coarse:h-10">{t('inv.drawerOverview')}</TabsTrigger>
            <TabsTrigger value="cert" className="flex-none pointer-coarse:h-10">{t('inv.drawerCert')}</TabsTrigger>
            {!readOnly && <TabsTrigger value="changes" className="flex-none pointer-coarse:h-10">{t('chg.tab')}</TabsTrigger>}
            <TabsTrigger value="checks" className="flex-none pointer-coarse:h-10">{t('inv.drawerChecks')}</TabsTrigger>
          </TabsList>
          <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-4 pb-6 text-[.92em] sm:px-5">
            <TabsContent value="details"><InventoryDetails record={record} teamMap={teamMap} platformNames={platformNames} compact /></TabsContent>
            <TabsContent value="cert"><CertificateSummary record={record} readOnly={readOnly} onCheckNow={onCheckNow} platformNames={platformNames} /></TabsContent>
            {!readOnly && (
              <TabsContent value="changes">
                <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
                  <ChangeHistoryTab t={t} kind="inventory" monitorId={record.id} teamNames={teamNameById} />
                </Suspense>
              </TabsContent>
            )}
            <TabsContent value="checks">
              <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
                {/* Çekmece URL'ye yazmaz (tablo sayfasının kendi parametreleri var) ve canlı yenileme yapmaz — eski davranış. */}
                <CertCheckHistory domain={record.domain} listKey="inventory-checks" urlSync={false} live={false} runInHeader={false} />
              </Suspense>
            </TabsContent>
          </div>
        </Tabs>

        {/* Altlık eylemleri — salt okunur ya da silinmiş kayıtta YOK; telefonda safe-area */}
        {canAct && (
          // Telefonda tek satır: [▶][🗑][Düzenle ———] (ikon düğmeleri 40 px, ad aria-label'da); sm+ metinli. Sağ dolgu (pe-16)
          // uygulamanın sabit Yardım düğmesinin altında düğme kalmasın diye.
          <SheetFooter data-slot="inv-drawer-actions" className="mt-0 flex-row flex-nowrap items-center gap-2 border-t py-3 ps-3 pe-16 pb-[max(.75rem,env(safe-area-inset-bottom))] sm:ps-4">
            <SimpleTooltip content={t('inv.checkNow')}>
              <Button type="button" variant="outline" className="size-10 sm:h-10 sm:w-auto" onClick={() => onCheckNow(record)}
                aria-label={t('a11y.rowAction', record.domain, t('inv.checkNow'))}><Play aria-hidden="true" /><span className="hidden sm:inline">{t('inv.checkNow')}</span></Button>
            </SimpleTooltip>
            <span className="hidden flex-1 sm:block" />
            {canManage && onDelete && (
              <Button type="button" variant="destructive" className="size-10 sm:h-10 sm:w-auto" onClick={() => onDelete(record.id)}
                aria-label={t('a11y.rowAction', record.domain, t('inv.delete'))}><Trash2 aria-hidden="true" /><span className="hidden sm:inline">{t('inv.deleteBtn')}</span></Button>
            )}
            {canEditRow(record) && (
              <Button type="button" className="h-10 flex-1 sm:flex-none" onClick={() => onEdit(record)}
                aria-label={t('a11y.rowAction', record.domain, t('inv.edit'))}><Pencil aria-hidden="true" /> {t('inv.edit')}</Button>
            )}
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  )
}
