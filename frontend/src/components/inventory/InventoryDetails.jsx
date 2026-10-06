import { useEffect, useState } from 'react'
import {
  AppWindow, ArrowUpRight, CalendarClock, ExternalLink, FolderOpen, ListChecks, NotebookText, Package, PackageSearch, Pencil,
  RefreshCw, Server, TriangleAlert, Users,
} from 'lucide-react'
import { api, formatDate, formatDateOnly } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { navigateTo } from '../../utils/navigate.js'
import { daysAgoText, expiredAgoText, expiresInText } from '../../utils/dayPhrases.js'
import { CONTACT_FIELDS } from '../../utils/inventoryContacts.js'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { ActiveBadge, DeletedBadge, TierBadge } from './InventoryTable.jsx'
import ManualCertBadge from '../manualcert/ManualCertBadge.jsx'
import { isManualCert } from '../manualcert/manualCertModel.js'
import {
  CHIP, ContactList, DetailSection, Fact, FactGrid, FlagBoard, LinkifiedText, MarkdownNotes, TONE, TimeAgo,
} from './InventoryDetailParts.jsx'
import {
  MISSING_LABEL_KEY, contactsTone, daysFromNow, filledContactFields, hasRenewalInfo, intervalLabelKey, inventoryDeepLinkParams,
  missingFields, notificationGroupView, siteUrl, splitFlags, tagsOf, tlsModeLabelKey,
} from './inventoryDetailModel.js'

// Çekmecenin "Sertifika" sekmesi bu adlarla içe aktarıyor (tek kaynak InventoryDetailParts).
export { DetailSection, Fact, FactGrid }

const ICON_BTN = '-my-1 shrink-0 text-muted-foreground hover:text-primary pointer-coarse:size-10'

/** Alan adı araçları: kopyala + (joker değilse) siteyi yeni sekmede aç — dokunmatikte 40 px hedefler. */
function DomainTools({ record }) {
  const t = useT()
  // Manuel kayıtta alan "takip adı" — bir web adresi değil, "siteyi aç" bağlantısı verilmez (2026-10-06)
  const url = isManualCert(record) ? null : siteUrl(record)
  return (
    <span className="inline-flex shrink-0 items-center">
      <CopyButton value={record.domain} label={t('inv.det.copyDomain')} copiedLabel={t('err.copied')} variant="ghost" buttonSize="icon-sm" className={ICON_BTN} />
      {url && (
        <Button asChild variant="ghost" size="icon-sm" className={ICON_BTN}>
          <a href={url} target="_blank" rel="noopener noreferrer" aria-label={t('inv.det.openSite')} title={t('inv.det.openSite')}>
            <ExternalLink aria-hidden="true" />
          </a>
        </Button>
      )}
    </span>
  )
}

/** Alan adı değeri (çekmecenin Uygulama bölümü): metin + araçlar. */
function DomainValue({ record }) {
  return (
    <span className="inline-flex min-w-0 max-w-full items-start gap-0.5">
      <span className="min-w-0 self-center break-all">{record.domain}</span>
      <DomainTools record={record} />
    </span>
  )
}

/** "Planlayan · not" satırı (yalnız dolu parçalar). */
function PlanHint({ record }) {
  const t = useT()
  const by = record.renewal_planned_by_name || record.renewal_planned_by
  const note = record.renewal_planned_note
  if (!by && !note) return null
  return (
    <>
      {by && t('inv.det.plannedBy', by)}
      {by && note && ' · '}
      {note && <LinkifiedText text={note} />}
    </>
  )
}

/** Gün farkını okunur metne çevirir (geçmiş/bugün/gelecek) — yenileme planı ve alan adı bitişi. */
function relDays(d, t, { expiry = false } = {}) {
  if (d == null) return null
  if (d === 0) return expiry ? t('inv.expiresToday') : t('inv.det.today')
  if (d < 0) return expiry ? expiredAgoText(t, -d) : daysAgoText(t, -d)
  return expiresInText(t, d)
}

/**
 * Özet başlığı: kimlik (alan adı, açıklama), kritiklik + durum + platform + grup çipleri, sahiplik şeridi (takım · UG
 * takımı · sorumlu sayısı · son güncelleme) ve eksik alan uyarısı (hijyen bandının kodları). `compact` (çekmece):
 * başlık satırı ve durum rozeti çizilmez — çekmecenin kendi başlığı alan adını, takımı ve durumu zaten taşıyor.
 * Eylemler (Envanterde aç · Kaydı düzenle) yalnız çağıran verirse; düzenleme ayrıca kaydın yazılabilir olmasına bağlı.
 */
function InventorySummary({ record, teamName, ugTeamName, platformName, compact, onEdit, onOpenInventory }) {
  const t = useT()
  const port = Number(record.port) || 443
  const contactsN = filledContactFields(record).length
  const total = CONTACT_FIELDS.length
  const missing = missingFields(record)
  const deleted = !!record.deleted_at
  const actions = (onOpenInventory || onEdit) && (
    <div data-slot="inv-summary-actions" className="flex w-full min-w-0 gap-2 @xl:w-auto @xl:shrink-0">
      {onOpenInventory && (
        <Button type="button" variant="outline" size="sm" className="h-10 flex-1 @xl:h-9 @xl:flex-none pointer-coarse:h-10"
          onClick={onOpenInventory} title={t('inv.det.openInInventoryHint')}>
          <ArrowUpRight aria-hidden="true" />{t('inv.det.openInInventory')}
        </Button>
      )}
      {onEdit && (
        <Button type="button" size="sm" className="h-10 flex-1 @xl:h-9 @xl:flex-none pointer-coarse:h-10" onClick={onEdit}>
          <Pencil aria-hidden="true" />{t('inv.det.editRecord')}
        </Button>
      )}
    </div>
  )
  return (
    <Card data-slot="inv-summary" data-compact={compact ? 'true' : undefined} className="@container min-w-0 gap-4 px-4 py-4 shadow-none sm:px-5">
      {!compact && (
        <div className="flex min-w-0 flex-col gap-3 @xl:flex-row @xl:items-start">
          <div className="flex min-w-0 flex-1 items-start gap-3">
            {/* Simge kutusu dar kapta (telefon) gizli: alan adı + araçları tek satıra sığsın */}
            <span aria-hidden="true" className="hidden size-10 shrink-0 items-center justify-center rounded-lg border bg-muted text-muted-foreground @md:flex">
              <Package className="size-5" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="m-0 text-[11px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t('inv.det.recordEyebrow')}</p>
              <div className="mt-0.5 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1">
                <h3 data-slot="inv-summary-title" className="m-0 min-w-0 text-base leading-snug font-semibold break-all">{record.domain}</h3>
                {port !== 443 && <Badge variant="outline" className="font-mono text-muted-foreground">:{port}</Badge>}
                <DomainTools record={record} />
              </div>
            </div>
          </div>
          {actions}
        </div>
      )}
      {compact && actions}
      {!compact && record.description && (
        <p data-slot="inv-summary-desc" className="m-0 text-sm leading-relaxed whitespace-pre-line text-foreground/90 [overflow-wrap:anywhere]">
          <LinkifiedText text={record.description} />
        </p>
      )}
      <div data-slot="inv-summary-badges" className="flex min-w-0 flex-wrap items-center gap-1.5">
        {record.tier
          ? (
            <span data-slot="inv-tier" data-tier={record.tier} className="inline-flex min-w-0 items-center gap-1.5 text-[13px] font-medium">
              <TierBadge tier={record.tier} /><span className="min-w-0">{t(`inv.tier${record.tier}`)}</span>
            </span>
          )
          : <Badge variant="outline" data-slot="inv-tier" data-tier="none" className={cn(CHIP, TONE.warn)}>{t('inv.det.tierNone')}</Badge>}
        {!compact && !deleted && <ActiveBadge r={record} t={t} />}
        {!compact && deleted && <DeletedBadge r={record} t={t} />}
        {isManualCert(record) && <ManualCertBadge version={record.manual_version ?? null} uploadedAt={record.manual_uploaded_at ?? null} rowLabel={record.domain} />}
        {platformName && <Badge variant="outline" data-slot="inv-platform-chip" className={cn(CHIP, TONE.muted)}><Server aria-hidden="true" />{platformName}</Badge>}
        {record.group_name && <Badge variant="outline" data-slot="inv-group-chip" className={cn(CHIP, TONE.muted)}><FolderOpen aria-hidden="true" />{record.group_name}</Badge>}
      </div>
      <dl data-slot="inv-summary-meta" className="m-0 grid min-w-0 grid-cols-2 gap-x-4 gap-y-3 border-t pt-3 @2xl:grid-cols-4">
        {/* Takım adları dar kapta (telefon) tam satır: iki sütunda uzun ad kırpılıyordu */}
        <Fact className="col-span-2 @md:col-span-1" label={t('inv.colTeam')} value={teamName
          ? <TeamBadge teamId={record.team_id} teamName={teamName} className="-ml-1" />
          : <span className="font-medium text-amber-700 dark:text-amber-400">{t('inv.det.noTeam')}</span>} />
        <Fact className="col-span-2 @md:col-span-1" label={t('inv.colUgTeam')}
          value={ugTeamName ? <TeamBadge teamId={record.ug_team_id} teamName={ugTeamName} className="-ml-1" /> : null} />
        <Fact label={t('inv.colContacts')} value={
          <Badge variant="outline" data-slot="inv-contacts-count" data-tone={contactsTone(contactsN, total)} data-count={contactsN}
            className={cn(CHIP, TONE[contactsTone(contactsN, total)])}>
            <Users aria-hidden="true" />{t('inv.contactsCount', contactsN, total)}
          </Badge>} />
        <Fact label={t('inv.det.lastUpdated')} value={record.updated_at ? (
          <span className="flex min-w-0 flex-col">
            <TimeAgo iso={record.updated_at} className="font-medium" />
            <span className="text-xs text-muted-foreground tabular-nums">
              {formatDate(record.updated_at)}{record.updated_by_name ? ` · ${record.updated_by_name}` : ''}
            </span>
          </span>
        ) : null} />
      </dl>
      {missing.length > 0 && (
        <div data-slot="inv-completeness" data-missing={missing.join(' ')}>
          <AlertBanner tone="warning" icon={TriangleAlert} title={t('inv.det.incompleteTitle')} className="mb-0">
            {t('inv.det.incompleteBody', missing.map((k) => t(MISSING_LABEL_KEY[k])).join(', '))}
          </AlertBanner>
        </div>
      )}
    </Card>
  )
}

/**
 * Envanter kaydının salt-okunur detay gövdesi — CertificateModal'ın "Envanter Bilgileri" sekmesi ve Envanter
 * çekmecesinin "Genel bakış" sekmesi (tek kaynak). 2026-09-28 yeniden tasarım (shadcn, mobil duyarlı):
 *   Özet (InventorySummary) → Uygulama · Sorumlu Ekipler · Altyapı ve izleme · Sertifika ve yenileme (bilgi yoksa
 *   YOK) · Operasyonel bilgiler (13 bayrak) · Notlar (boşsa YOK) · künye (oluşturma / güncelleme).
 * Kap ≥ 48rem (`@container`) iki sütun, altında tek sütun; kartlarda sol renk şeridi yok.
 *
 * teamMap verilirse team_id → ad ondan çözülür (Envanter ekranı); verilmezse sunucunun döndürdüğü team_name /
 * ug_team_name kullanılır (kart penceresi — USER rolü tüm takım listesini çekemez). `platformNames` kod → ad,
 * `platformDescriptions` kod → katalog açıklaması (ikisi de isteğe bağlı; yoksa kod gösterilir).
 * `compact`: çekmece (başlık satırı yok, alan adı + açıklama Uygulama bölümünde). `onEdit` / `onOpenInventory`: özet
 * eylemleri — verilmezse düğme yok.
 */
export function InventoryDetails({ record, teamMap, platformNames, platformDescriptions, compact = false, onEdit, onOpenInventory }) {
  const t = useT()
  if (!record) return null
  const teamName = teamMap
    ? (teamMap[String(record.team_id)] ?? record.team_name ?? null)
    : (record.team_name ?? null)
  const ugTeamName = record.ug_team_id != null
    ? ((teamMap ? teamMap[String(record.ug_team_id)] : null) ?? record.ug_team_name ?? null)
    : null
  const platformName = record.platform ? (platformNames?.[record.platform] || record.platform) : null
  const platformDesc = record.platform ? platformDescriptions?.[record.platform] : null
  const tags = tagsOf(record)
  const contactsN = filledContactFields(record).length
  const flags = splitFlags(record)
  const renewalIn = daysFromNow(record.renewal_planned_at)
  const domainExpIn = daysFromNow(record.domain_expiry)
  const timeout = Number(record.timeout_seconds) || null
  const notifGroup = notificationGroupView(record)
  const manual = isManualCert(record)
  return (
    <div data-slot="inv-details" className="@container flex min-w-0 flex-col gap-3">
      <InventorySummary record={record} teamName={teamName} ugTeamName={ugTeamName} platformName={platformName} compact={compact}
        onEdit={onEdit} onOpenInventory={onOpenInventory} />

      {/* items-start: kısa kart komşusunun boyuna uzayıp boş kutu çizmesin */}
      <div className="grid min-w-0 items-start gap-3 @3xl:grid-cols-2">
        <DetailSection id="app" icon={AppWindow} title={t('inv.det.secApp')}>
          <FactGrid>
            {compact && <Fact label={t('inv.formDomain')} value={<DomainValue record={record} />} mono full />}
            {/* Manuel (dosyadan yüklenen) kayıtta ağ portu anlamsız → yerine kaynak: yüklenen dosya · sürüm (2026-10-06) */}
            {manual
              ? <Fact label={t('mcert.det.source')} value={t('mcert.det.sourceValue', record.manual_version ?? '—')} />
              : <Fact label={t('inv.formPort')} value={Number(record.port) || 443} />}
            <Fact label={t('inv.formGroup')} value={record.group_name} />
            <Fact label={t('inv.formTags')} value={tags.length ? (
              <span className="flex flex-wrap gap-1">{tags.map((tag) => <Badge key={tag} variant="secondary" className="font-normal">{tag}</Badge>)}</span>
            ) : null} />
            <Fact label={t('inv.formPurchasedBy')} value={record.purchased_by ? <LinkifiedText text={record.purchased_by} /> : null} />
            {compact && record.description && (
              <Fact label={t('inv.formDesc')} value={<span className="whitespace-pre-line"><LinkifiedText text={record.description} /></span>} full />
            )}
          </FactGrid>
        </DetailSection>

        {/* Sorumlu Ekipler — sertifikayı kimin yenileyeceği (yönlendirme DEĞİL, bilgilendirme) */}
        <DetailSection id="contacts" icon={Users} title={t('inv.sectionContacts')}
          meta={<Badge variant="outline" className={cn(CHIP, TONE[contactsTone(contactsN)])}>{contactsN}/{CONTACT_FIELDS.length}</Badge>}>
          <ContactList record={record} />
        </DetailSection>

        <DetailSection id="infra" icon={Server} title={t('inv.det.secInfra')}>
          <FactGrid>
            <Fact label={t('inv.formPlatform')} value={platformName} hint={platformDesc || null} />
            <Fact label={t('inv.formPlatformDetail')} value={record.platform_detail ? <LinkifiedText text={record.platform_detail} /> : null} mono />
            {!manual && <Fact label={t('inv.formTlsMode')} value={t(tlsModeLabelKey(record.tls_mode))} />}
            {!manual && <Fact label={t('inv.formInterval')} value={t(intervalLabelKey(record.check_interval_hours))} />}
            {!manual && <Fact label={t('inv.formTimeout')} value={timeout ?? t('inv.det.timeoutGlobal')} />}
            {/* Alarm e-postalarının gideceği grup — sunucu adı çözer; kimlik var ama ad yoksa "bulunamadı" (silinmiş / başka
                ekibin / görme yetkisi olmayan grup: sunucu üçünü ayırt ETTİRMEZ — grup ucunun 404 deseni) */}
            <Fact data-slot="inv-notif-group" data-state={notifGroup.kind} label={t('ng.selectorLabel')}
              value={notifGroup.kind === 'named' ? notifGroup.name
                : notifGroup.kind === 'missing' ? t('inv.det.notifGroupId', notifGroup.id)
                  : t('ng.selectorDefault')}
              hint={notifGroup.kind === 'missing' ? t('inv.det.notifGroupMissing') : null} />
          </FactGrid>
        </DetailSection>

        {hasRenewalInfo(record) && (
          <DetailSection id="renewal" icon={CalendarClock} title={t('inv.det.secRenewal')}>
            <FactGrid>
              {record.renewal_planned_at && (
                <Fact label={t('inv.det.renewalPlanned')} full
                  value={<span className="inline-flex flex-wrap items-baseline gap-x-2"><span className="font-medium tabular-nums">{formatDateOnly(record.renewal_planned_at)}</span>
                    {renewalIn != null && <span className="text-xs text-muted-foreground">{relDays(renewalIn, t)}</span>}</span>}
                  hint={record.renewal_planned_by_name || record.renewal_planned_by || record.renewal_planned_note ? <PlanHint record={record} /> : null} />
              )}
              {record.domain_expiry && (
                <Fact label={t('inv.colDomainExpiry')} full
                  value={<span className="inline-flex flex-wrap items-baseline gap-x-2"><span className="font-medium tabular-nums">{formatDateOnly(record.domain_expiry)}</span>
                    {domainExpIn != null && (
                      <span data-slot="inv-domain-expiry" data-days={domainExpIn}
                        className={cn('text-xs font-semibold', domainExpIn < 0 ? 'text-destructive' : domainExpIn <= 30 ? 'text-amber-700 dark:text-amber-400' : 'text-muted-foreground')}>
                        {relDays(domainExpIn, t, { expiry: true })}
                      </span>
                    )}</span>}
                  hint={[record.domain_registrar ? `${t('dreg.registrarName')}: ${record.domain_registrar}` : null,
                    record.domain_expiry_checked_at ? `${t('domdet.lookedUp')}: ${formatDate(record.domain_expiry_checked_at)}` : null].filter(Boolean).join(' · ') || null} />
              )}
              {record.expected_fingerprint && (
                <Fact label={t('inv.det.expectedFp')} full mono hint={t('inv.det.expectedHint')}
                  value={<span className="flex min-w-0 items-start gap-1"><span className="min-w-0 self-center break-all">{record.expected_fingerprint}</span>
                    <CopyButton value={record.expected_fingerprint} label={t('inv.det.copyFp')} copiedLabel={t('err.copied')} variant="ghost" buttonSize="icon-sm"
                      className="-my-1 shrink-0 text-muted-foreground hover:text-primary pointer-coarse:size-10" /></span>} />
              )}
              {record.expected_subject && <Fact label={t('inv.det.expectedSubject')} value={record.expected_subject} mono full />}
            </FactGrid>
          </DetailSection>
        )}

        <DetailSection id="ops" icon={ListChecks} title={t('inv.det.secOps')} className="@3xl:col-span-2"
          meta={<Badge variant="outline" className={cn(CHIP, TONE.muted)}>{t('inv.det.flagsCount', flags.on.length, flags.on.length + flags.off.length)}</Badge>}>
          <FlagBoard record={record} />
        </DetailSection>

        {record.change_description && (
          <DetailSection id="notes" icon={NotebookText} title={t('inv.drawerNotes')} className="@3xl:col-span-2">
            <MarkdownNotes text={record.change_description} />
          </DetailSection>
        )}
      </div>

      {/* Künye */}
      {(record.created_at || record.updated_at) && (
        <p data-slot="inv-record-meta" className="m-0 flex flex-wrap gap-x-5 gap-y-1 px-1 text-xs text-muted-foreground">
          {record.created_at && <span>{t('inv.metaCreated')}: <span className="tabular-nums">{formatDate(record.created_at)}</span>{record.created_by_name ? ` · ${record.created_by_name}` : ''}</span>}
          {record.updated_at && <span>{t('inv.metaUpdated')}: <span className="tabular-nums">{formatDate(record.updated_at)}</span>{record.updated_by_name ? ` · ${record.updated_by_name}` : ''}</span>}
        </p>
      )}
    </div>
  )
}

/**
 * CertificateModal'daki "Envanter Bilgileri" sekmesi — domain'e göre envanter kaydını kendisi çeker. İzin/kapsam
 * backend'de (by-domain: listInventory ile aynı kapı; org geneli görünürlükte başka takımın kaydı salt okunur).
 * Dört hâl AYRI çizilir: yükleniyor · kayıt yok (StatusBlock + neden) · HATA (StatusBlock + Tekrar dene — eskiden
 * hata "kayıt yok" gibi görünüyordu) · kayıt. Platform kataloğu (ad + açıklama) isteğe bağlı ikinci istek; okunamazsa
 * kod gösterilir.
 *
 * `onEdit`: pencerenin Düzenle işleyicisi (App envanter formu) — kayıt yazılabilir (`can_manage`) ve silinmemişse
 * "Kaydı düzenle". `onLeave`: pencereyi kapatır — verilirse "Envanterde aç" (Envanter ekranı + kaydın paneli).
 */
export function InventoryTab({ domain, onEdit, onLeave }) {
  const t = useT()
  const [state, setState] = useState({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [catalogue, setCatalogue] = useState({ names: {}, descriptions: {} })

  useEffect(() => {
    // Hedef değişince hâl KOŞULSUZ sıfırlanır (önceki alanın kaydı/hatası yeni alana taşınmasın).
    setState({ status: 'loading' })
    if (!domain) return undefined
    let alive = true
    ;(async () => {
      try {
        const res = await api.admin.getInventoryByDomain(domain)
        if (!alive) return
        if (res?.success) setState(res.data ? { status: 'ready', record: res.data } : { status: 'missing' })
        else setState({ status: 'error', message: res?.error || '' })
      } catch {
        if (alive) setState({ status: 'error', message: '' })
      }
    })()
    return () => { alive = false }
  }, [domain, attempt])

  useEffect(() => {
    let alive = true
    Promise.resolve(api.admin.listPlatforms?.())
      .then((r) => {
        if (!alive || !r?.success || !Array.isArray(r.data)) return
        setCatalogue({
          names: Object.fromEntries(r.data.map((p) => [p.code, p.name])),
          descriptions: Object.fromEntries(r.data.filter((p) => p.description).map((p) => [p.code, p.description])),
        })
      })
      .catch(() => { /* katalog yok → kod gösterilir */ })
    return () => { alive = false }
  }, [])

  if (state.status === 'loading') return <LoadingBlock label={t('modal.loading')} fullWidth />
  if (state.status === 'error') {
    return (
      <StatusBlock tone="danger" icon={TriangleAlert} role="alert" title={t('inv.det.loadError')}
        description={state.message || t('inv.det.loadErrorHint')}
        actions={<Button type="button" variant="outline" className="h-10" onClick={() => setAttempt((n) => n + 1)}>
          <RefreshCw aria-hidden="true" />{t('inv.det.retry')}
        </Button>} />
    )
  }
  if (state.status === 'missing') {
    return <StatusBlock tone="neutral" icon={PackageSearch} title={t('modal.inventoryEmpty')} description={t('inv.det.notFoundHint')} />
  }
  const record = state.record
  const canEdit = !!onEdit && record.can_manage !== false && !record.deleted_at
  const openInventory = onLeave
    ? () => { onLeave(); navigateTo('domains', inventoryDeepLinkParams(record)) }
    : undefined
  return (
    <div className="pt-1 pb-2">
      <InventoryDetails record={record} platformNames={catalogue.names} platformDescriptions={catalogue.descriptions}
        onEdit={canEdit ? () => onEdit() : undefined} onOpenInventory={openInventory} />
    </div>
  )
}
