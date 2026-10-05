import { useCallback, useEffect, useId, useState, useRef, lazy, Suspense } from 'react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { useDialog } from './ui/Dialog.jsx'
import { useToast } from './ui/Toast.jsx'
import { Trash2, Globe, X, Pencil, History, Stethoscope, Play, RefreshCw, StickyNote,
  ShieldCheck, ShieldX, HeartPulse, FileText, Bell, LineChart, Package, FileClock } from 'lucide-react'
import AlertHistory from './admin/AlertHistory'
import SslCheckerPanel from './SslCheckerPanel.jsx'
import CertNotesTab from './certmodal/CertNotesTab.jsx'
import DiagnosticsModal from './admin/DiagnosticsModal.jsx'
import { deleteInventoryByDomain } from '../utils/deleteInventory.js'
import { usePermissions } from '../contexts/PermissionsProvider.jsx'
import { isInsecure, securityTitle } from '../utils/certSecurity.js'
import { InventoryTab } from './inventory/InventoryDetails.jsx'
import ReadOnlyBadge from './ui/ReadOnlyBadge.jsx'
import { LoadingBlock, Spinner } from './ui/Progress.jsx'
import { CheckRunningStrip, MON_ACT, MON_ACT_TONE } from './ui/CheckRunning.jsx'
import CertCheckHistory from './certmodal/CertCheckHistory.jsx'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'
import ModalShell from './ui/ModalShell.jsx'
import NocStatus from './noc/NocStatus.jsx'
import CertDetailsPanel from './certmodal/CertDetailsPanel.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import SimpleTooltip from './ui/SimpleTooltip.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Separator } from '@/components/shadcn/separator'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/shadcn/tabs'
import { cn } from '@/lib/utils'

/** Medya sorgusu — yalnız DAVRANIŞ farkı için (sekme ipucu, etiket gizliyken); görünüm CSS'te (max-xl:sr-only). */
function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => { try { return window.matchMedia(query).matches } catch { return false } })
  useEffect(() => {
    let mql
    try { mql = window.matchMedia(query) } catch { return undefined }
    const onChange = () => setMatches(mql.matches)
    onChange()
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [query])
  return matches
}

/*
 * Sertifika DETAY penceresi — ui/ModalShell (shadcn Dialog) + Tabs + Card/Badge (eski elle kurulu `.modal.show`,
 * `.modal-tabs`, `.modal-field`, `.alert-history-card`+`.ahc-stripe` ailesinin yerine). Test kancaları: sekmeler
 * role="tab" (data-state="active"), başlık durum rozeti `data-slot="cert-modal-status"`, notlar `data-slot="cert-note"`,
 * başlıktaki 7/24 göstergesi `data-slot="noc-status"` (noc/NocStatus — kartlarla aynı).
 * SSL Kontrol sekmesi: SslCheckerPanel (+ certmodal/Ssl*); Notlar sekmesi: certmodal/CertNotesTab (2026-09-28).
 */

// Grafik recharts çekiyor; diğer izleme sayfalarındaki gibi (PingMonitorPage) tembel yüklenir.
const ResponseTimeChart = lazy(() => import('./ResponseTimeChart.jsx'))
const CertHealthPanel = lazy(() => import('./CertHealthPanel.jsx'))
// Değişiklik geçmişi (2026-10-05): envanter kaydının kim / ne zaman / ne değişti günlüğü — ilk açılışta yüklenir.
const CertChangesTab = lazy(() => import('./certmodal/CertChangesTab.jsx'))

/** Başlık durum rozeti tonu (eski .modal-status-*). */
const STATUS_TONE = {
  valid:    'bg-success/15 text-success dark:bg-success/20',
  warning:  'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  high:     'bg-orange-500/15 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300',
  critical: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  error:    'bg-destructive/10 text-destructive dark:bg-destructive/20',
  expired:  'bg-muted text-muted-foreground',
}

export default function CertificateModal({ domain, alertLevel, onClose, initialData, previewMode, currentUser, currentUserRole,
  // Org geneli görünürlük (2026-09-26): başka takımın kaydı — kontrol/düzenle/tanıla/sil/not ekle YOK, alarm sekmesi YOK,
  // başlıkta salt okunur rozet + sahibi takım (`readOnlyTeam: { id, name }`). Okuma sekmeleri (SSL, sağlık, geçmiş, envanter, notlar) açık.
  readOnly = false, readOnlyTeam = null,
                                          onCheckNow, checking = false, onEdit, refreshSignal = 0, initialTab,
  // "Envanterde aç" (Envanter Bilgileri sekmesi) pencereden AYRILIR: varsayılan pencereyi kapatır. Pencereyi açan bir
  // form ise (mükerrer alan adı bandı) formu da kapatan işleyici verir — yoksa hedef kayıt formun arkasında açılırdı (Ek 3/4).
  onLeave }) {
  const t = useT()
  // Escape: ModalShell (Radix katman yığını) — önizleme modunda kapalı (dismissOnEscape); eski useEscapeKey gereksiz.
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [certData, setCertData]       = useState(null)
  // 7/24 göstergesi (2026-09-28): kaydın GÜNCEL `{ noc_notify, noc_group_ids }`'i — /history zarfından (uç envanter satırını
  // yetki kapısında zaten okuyor; ek istek yok). null = bilinmiyor (önizleme / eski sunucu / henüz gelmedi) → gösterge yok.
  const [noc, setNoc]                 = useState(null)
  // O3/D12: modal kalıcı mount'lu, yalnız domain prop'u değişiyor — uçuşan yanıt guard'ları
  // "istek anındaki domain hâlâ ekranda mı" sorusunu bu ref'ten okur (her render'da tazelenir).
  const domainRef = useRef(null)
  domainRef.current = domain
  const initialTabRef = useRef(initialTab)   // açılış sekmesi (tablo satır menüsü); ref: domain effect'inin bağımlılığı olmasın
  initialTabRef.current = initialTab
  // Canlı SSL probe'unun tur sayacı. domainRef TEK BAŞINA yetmiyordu: uçuşan yanıt "artık
  // ekranda değilim" deyip sslLoading'i TEMİZLEMEDEN dönüyor, bayrak true kalıyordu. Modal
  // kalıcı mount'lu olduğu için o bayrak bir sonraki domain'e taşınıyor ve SSL sekmesi
  // sonsuza kadar yükleniyor görünüyordu (bkz. sıfırlama: domain effect'i).
  const sslSeq = useRef(0)
  const [sslData, setSslData]         = useState(null)
  const [sslLoading, setSslLoading]   = useState(false)
  // Canlı kontrol başarısız (ağ hatası / 403 / boş yanıt): null = hata yok; dize (boş olabilir) = hata. Eskiden hata
  // dalında sslData null + sslLoading false kalıyor, effect koşulu yeniden sağlanıyor ve istek DÖNGÜYE giriyordu.
  const [sslError, setSslError]       = useState(null)
  const bodyRef = useRef(null)   // ModalShell kaydırılan gövdesi — sekme değişince başa sarılır
  const [activeTab, setActiveTab]     = useState('ssl')
  const [showDiag, setShowDiag]       = useState(false)
  const isAdmin = !readOnly && (currentUserRole === 'ADMIN' || currentUserRole === 'TEAM_ADMIN')
  const perms = usePermissions()
  const canViewInventory = perms.canView('inventory.list')
  // Silme yetkisi backend'deki kapinin AYNISI: inventory.crud/edit (uc ayrica takim kapsami arar).
  // Yetkisi olmayana dugme HIC cizilmez — gorunup 403 vermek kullaniciyi bosuna umutlandirir.
  const canDeleteCert = !readOnly && perms.canEdit('inventory.crud')
  // Tanıla (2026-10-05): rol (ADMIN/TEAM_ADMIN) değil `diagnostics.run` (execute) izni — sunucu (/api/admin/diagnostics/*)
  // aynı izni + takım kapsamlı envanteri zorlar. Başka takımın (salt okunur) kaydında ve önizlemede yok. Kontrol geçmişindeki
  // hata panelinin "Bu kontrolü tanıla" düğmesi de aynı kapıyla aynı pencereyi açar.
  const canDiagnose = !readOnly && !previewMode && perms.canExecute('diagnostics.run')
  const [deleting, setDeleting] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  // Bağımlı sekmeler (Sağlık / Alarm / Envanter) kendi verilerini bir kez çekip tutuyor. Bu
  // sayaç `key` olarak verilir: değişince o sekmeler yeniden kurulur = tazelenir. Kontrol
  // Geçmişi sekmesi BİLEREK dışarıda — kendi 30 sn'lik canlı yenilemesi var ve remount
  // kullanıcının seçtiği tarih aralığını/sayfasını sıfırlardı.
  const [reloadKey, setReloadKey] = useState(0)
  // En son görülen kontrol damgası. Yeni bir kontrol geçmişi kaydı düştüğünü BUNDAN anlıyoruz.
  const lastCheckedRef = useRef(null)
  // `refreshCert` MUTLAK KARARLI olmalı: domain effect'inin bağımlılığı ve kimliği değişirse
  // effect yeniden koşup sekmeyi 'ssl'e sıfırlıyor. `toast` ToastProvider'ın her render'ında
  // YENİ bir nesne (context değeri memo'lu değil) — yani dependency yapılsaydı ekranda beliren
  // HERHANGİ bir toast kullanıcıyı bulunduğu sekmeden atardı. İkisi de ref üzerinden okunur.
  const toastRef = useRef(toast); toastRef.current = toast
  const tRef = useRef(t); tRef.current = t
  const tabPickId = useId()
  // Sekme etiketleri yalnız xl+ (≥1280) görünür: ölçüm (2026-09-27) — 8 sekme TR etiketleriyle 1069 px; 1200 px pencere
  // 1280'de sığdırır, 1024'te 96vw bile 933 px verir. Altında ikon + sayaç + ipucu; etiket ekran okuyucuya kalır (sr-only).
  const wideTabs = useMediaQuery('(min-width: 1280px)')
  // Sekme sayaçları (2026-09-26 sekme yeniden tasarımı): açık alarm + silinmemiş not. Pencere açılışında ve
  // tazelemede (reloadKey) bir kez; önizlemede hiç (envanterde olmayan alan), salt okunurda alarm sorulmaz.
  const [tabCounts, setTabCounts] = useState({ alerts: null, notes: null })
  useEffect(() => {
    if (!domain || previewMode) { setTabCounts({ alerts: null, notes: null }); return undefined }
    let alive = true
    const safe = (p, pick) => p.then((r) => (r?.success && Array.isArray(r.data) ? pick(r.data) : null)).catch(() => null)
    Promise.all([
      readOnly ? Promise.resolve(null) : safe(api.getDomainAlerts(domain), (l) => l.filter((a) => !a.resolved).length),
      safe(api.admin.getNotes(domain), (l) => l.filter((n) => !n.deleted_at).length),
    ]).then(([alerts, notes]) => { if (alive) setTabCounts({ alerts, notes }) })
    return () => { alive = false }
  }, [domain, previewMode, readOnly, reloadKey])

  /**
   * Kart verisini yeniden çeker.
   *
   * <p>Sekmeyi ve mevcut içeriği KORUR (boşaltmaz): tazeleme kullanıcıyı bulunduğu yerden
   * koparmamalı. `silent` (poll) turunda hiçbir yükleme göstergesi çizilmez; yalnız GERÇEKTEN
   * yeni bir kontrol kaydı geldiyse bağımlı sekmeler tazelenir ve kullanıcı bilgilendirilir.
   * Elle tazelemede ise sonuç aynı olsa bile sekmeler yeniden kurulur — düğmeye basan kişi
   * "hiçbir şey olmadı" hissi almamalı.
   */
  const refreshCert = useCallback(async (silent = false) => {
    const reqDomain = domainRef.current
    if (!reqDomain) return
    if (!silent) setRefreshing(true)
    let res = null
    try { res = await api.getHistory(reqDomain) } catch { /* ağ hatası: mevcut veri ekranda kalsın */ }
    // Bayrak guard'DAN ÖNCE düşer. Elenen dalda bırakılırsa `refreshing` true kalır ve Yenile
    // düğmesi kalıcı olarak devre dışı olurdu — `sslLoading`in v20.44.1'de düştüğü tuzağın aynısı.
    if (!silent) setRefreshing(false)
    // D12 guard: A'nın geç dönen yanıtı B'nin modalını doldurmasın.
    if (reqDomain !== domainRef.current) return
    // 7/24 durumu kaydın kendisinden (geçmiş boş olsa da); başarısız tazelemede eski (gerçek) değer kalır. Envanter
    // formunda 7/24 değişince refreshSignal → bu yol → başlık hemen güncel.
    if (res?.success && typeof res.noc_notify === 'boolean') {
      setNoc({ noc_notify: res.noc_notify, noc_group_ids: Array.isArray(res.noc_group_ids) ? res.noc_group_ids : undefined })
    }
    const latest = res?.success && Array.isArray(res.data) && res.data.length > 0 ? res.data[0] : null
    let isNew = false
    if (latest) {
      const stamp = latest.checked_at ?? null
      isNew = lastCheckedRef.current !== null && stamp !== lastCheckedRef.current
      lastCheckedRef.current = stamp
      setCertData(latest)
    }
    // Elle tazeleme bağımlı sekmeleri KOŞULSUZ tazeler — kart verisi boş dönse bile ("henüz
    // kontrol edilmemiş" domain) kullanıcı düğmeye bastı; Sağlık/Alarm/Envanter/Kontrol Geçmişi
    // yine de yeniden okunmalı. Önce `latest` yoksa erken dönüyordu: "Çalıştır"ın ardından
    // geçmiş sekmesi tazelenmiyordu, çünkü kart geçmişi henüz boştu.
    if (isNew || !silent) setReloadKey(k => k + 1)
    if (isNew && silent) toastRef.current.success(tRef.current('modal.newCheck'))
  }, [])

  // Envanter formu kaydedince (başlıktaki Düzenle) modal verisi hemen tazelenir. 0 = ilk mount,
  // tazeleme yok.
  useEffect(() => { if (refreshSignal) refreshCert(false) }, [refreshSignal, refreshCert])

  // Modal açıkken yeni bir kontrol geçmişi kaydı düşerse kendiliğinden tazelenir. Cadence ve
  // görünürlük kuralı Kontrol Geçmişi sekmesinin canlı yenilemesiyle AYNI (30 sn, gizli sekmede
  // durur). immediate=false: ilk yüklemeyi zaten aşağıdaki domain effect'i yapıyor.
  useVisibleInterval(() => refreshCert(true),
    domain && !previewMode ? 30000 : 0, false)

  useEffect(() => {
    // Domain değişimi (modalı KAPATMAK dahil) uçuşan probe'u geçersizler ve yükleme bayrağını
    // sıfırlar — `!domain` dalından ÖNCE, çünkü kapanış tam da bayrağın sızdığı yoldu.
    sslSeq.current++
    setSslLoading(false)
    setSslError(null)
    setRefreshing(false)
    lastCheckedRef.current = null   // yeni domain = yeni damga çizgisi; ilk yükleme "yeni kontrol" sayılmaz
    setNoc(null)   // önceki alanın 7/24 durumu yeni alanın başlığında görünmesin (kapanış dâhil)
    if (!domain) return
    setCertData(null)
    setSslData(null)
    setActiveTab(initialTabRef.current || 'ssl')   // satır menüsünden doğrudan sekmeye (alarm/kontrol geçmişi)

    if (initialData) {
      setCertData(initialData)
      // Yalnız GERÇEK bir kontrol sonucu (status taşıyan) SSL sekmesine gider; paylaşılan sertifika penceresinden gelen
      // `{ domain, _preview }` saplaması boş/kırmızı bir panel çiziyordu → saplamada canlı kontrol koşar.
      setSslData(initialData.status ? initialData : null)
      return
    }

    // Details / Alerts / Notes için geçmiş veri — elle tazeleme ve poll ile AYNI yol.
    refreshCert(true)

  }, [domain, refreshCert])

  /**
   * SSL sekmesindeki CANLI kontrol yalnız o sekme açıldığında koşar (K2).
   *
   * <p>Önceden her modal açılışında koşuyordu: kullanıcı yalnız "Detaylar"a bakacak olsa bile
   * izlenen sunucuyla TLS el sıkışması yapılıyor, modal onu bekliyordu. Sağlık sekmesi zaten
   * kalıcı son kontrolle anında çiziliyor; canlı probe artık istemli.
   */
  // O3: canlı TLS probe'u yavaştır; kullanıcı A'yı kapatıp B'yi açtığında A'nın geç dönen
  // probe'u B modalında A'nın SSL verisini gösterebiliyordu. Yanıt yalnız İSTEK ANINDAKİ tur
  // hâlâ güncelse yazılır (sslSeq) — ve hangi dalda dönerse dönsün yükleme bayrağı, turu
  // geçersizleyen domain effect'i tarafından sıfırlanır.
  // "Yeniden kontrol et" de aynı yoldan geçer: eski sonuç ekranda KALIR (yükleniyor ekranına düşmez), düğme döner;
  // başarısızsa eski sonuç + uyarı bandı. Hata dalında sslError dolar → effect yeniden tetiklemez (döngü yok).
  const probeSsl = useCallback((reqDomain) => {
    setSslLoading(true)
    setSslError(null)
    const mySeq = ++sslSeq.current
    api.checkDomainPreview(reqDomain).then((res) => {
      if (mySeq !== sslSeq.current) return          // daha yeni bir tur var → bu yanıtı AT
      if (res?.data) setSslData(res.data)
      else setSslError(res?.error || res?.message || '')
      setSslLoading(false)
    }).catch((e) => { if (mySeq === sslSeq.current) { setSslError(e?.message || ''); setSslLoading(false) } })
  }, [])

  useEffect(() => {
    if (!domain || activeTab !== 'ssl' || sslData || sslLoading || sslError != null) return
    probeSsl(domain)
  }, [domain, activeTab, sslData, sslLoading, sslError, probeSsl])

  // Sekme değişince kaydırılan gövde başa sarılır: pencere boyu SABİT (sekme içeriğine göre değişmez), önceki
  // sekmede aşağı kaydırılmış konum yeni sekmenin ortasından başlatıyordu.
  function switchTab(tab) {
    setActiveTab(tab)
    if (bodyRef.current) bodyRef.current.scrollTop = 0
  }

  if (!domain) return null

  /**
   * Başlıktaki "Çalıştır" — kartın ▶ düğmesiyle AYNI iş (`runSingleCheck`, App.jsx'ten prop).
   * Yeni bir uç ya da ikinci bir kontrol yolu YOK: koşu bitince modal kendi verisini de tazeler,
   * yoksa kullanıcı kontrolü modalın içinden başlatıp sonucunu modalın dışında görürdü.
   */
  async function handleCheckNow() {
    if (!onCheckNow || checking) return
    await onCheckNow()
    await refreshCert(false)
  }

  // Sertifikayi envanterden sil — YENI uc YOK, mevcut DELETE /admin/inventory/{id} cagrilir:
  // denetim kaydi, soft-delete ve acik alarmlarin kapatilmasi kendiliginden miras kalir.
  async function deleteCertificate() {
    setDeleting(true)
    // Akışın kendisi ORTAK: Genel Bakış kartındaki kısayol da aynı onayı ve aynı uçları kullanır.
    // Bayrak try/finally ile temizlenir: aksi halde tek bir ağ hatası çöp kutusunu modal
    // kapanana kadar kilitler (SystemHealth'te bugün düzeltilen kusurun aynısı).
    let deleted = false
    try {
      deleted = await deleteInventoryByDomain({ domain, showConfirm, toast, t })
    } finally {
      setDeleting(false)
    }
    if (deleted) onClose?.({ deleted: true, domain })
  }

  const d = certData
  const statusK = d ? (alertLevel ?? statusKey(d)) : null

  // Sekme tanımları — TEK kaynak: sm+ sekme çubuğu ve telefon seçicisi aynı listeden çizilir (sıra = eski sıra).
  const tabDefs = [
    { value: 'ssl', label: t('ssl.tab'), Icon: ShieldCheck },
    !previewMode && { value: 'health', label: t('hlth.tab'), Icon: HeartPulse },
    { value: 'details', label: t('modal.detailsTab'), Icon: FileText, count: Array.isArray(d?.san) && d.san.length > 0 ? d.san.length : null },
    !previewMode && { value: 'history', label: t('hist.tab'), Icon: History },
    !previewMode && !readOnly && { value: 'alerts', label: t('modal.alertsTab'), Icon: Bell, count: tabCounts.alerts },
    !previewMode && { value: 'chart', label: t('modal.chartTab'), Icon: LineChart },
    !previewMode && canViewInventory && { value: 'inventory', label: t('modal.inventoryTab'), Icon: Package },
    // Değişiklik geçmişi (2026-10-05): Envanter çekmecesindeki "Değişiklikler" ile aynı kural — başka takımın kaydında yok.
    !previewMode && canViewInventory && !readOnly && { value: 'changes', label: t('chg.tab'), Icon: FileClock },
    !previewMode && { value: 'notes', label: t('modal.notesTab'), Icon: StickyNote, count: tabCounts.notes },
  ].filter(Boolean)

  // Başlık eylemleri — ikon düğmeleri (shadcn Button + Tooltip; adlar aria-label'da). Kart eylemleriyle (MON_ACT)
  // aynı dil; yıkıcı "Sil" en sonda kırmızı vurgulu; kapatma X'i ince bir ayraçla kendi bölmesinde.
  // Dokunmatikte 40 px (pointer-coarse). Telefonda grup başlığın altına kendi satırına iner.
  const act = (tone) => cn(MON_ACT, MON_ACT_TONE[tone], 'pointer-coarse:size-10')
  // 7/24 göstergesi (2026-09-28) — izleme detay pencereleriyle (MonitorDetailModal `noc`) AYNI bileşen ve AYNI yer: başlığın
  // hemen ardında, eylem grubunun solunda; pencere adına (DialogTitle) KARIŞMAZ. Düzenleme eylemi başlıktaki Düzenle ile
  // aynı işleyici (Genel Bakış kartının yolu: envanter formu, 7/24 alanına kaydırılmış); salt okunurda / Düzenle yetkisi
  // yoksa "7/24 Kapsamı'nda gör". Önizlemede (envanterde olmayan alan) ve alan bilinmezken çizilmez.
  const nocEdit = !previewMode && !readOnly && onEdit ? onEdit : undefined
  const nocIndicator = !previewMode && noc
    ? <NocStatus type="SSL" monitor={noc} rowLabel={domain} canEdit={!!nocEdit} onEdit={nocEdit} />
    : null
  const headerActions = (
    <div data-slot="cert-modal-actions" className="ml-auto flex shrink-0 flex-wrap items-center justify-end gap-1">
      {/* "Kontrol ediliyor… N sn" şeridi — kartlardakiyle AYNI bileşen. */}
      {!previewMode && <CheckRunningStrip running={!!checking} />}
      {!previewMode && !readOnly && onCheckNow && (
        <Button title={t('inv.runTitle', domain)} type="button" variant="outline" size="icon-sm" className={act('check')} onClick={handleCheckNow}
            disabled={checking} aria-label={checking ? t('mon.checkRunning') : t('inv.run')}
            aria-busy={checking || undefined}>
            {checking ? <Spinner size={12} inline decorative /> : <Play size={14} aria-hidden="true" />}
          </Button>
      )}
      {!previewMode && !readOnly && onEdit && (
        <Button title={t('inv.edit')} type="button" variant="outline" size="icon-sm" className={act('edit')} onClick={onEdit} aria-label={t('inv.edit')}>
            <Pencil size={14} aria-hidden="true" />
          </Button>
      )}
      {!previewMode && (
        <Button title={t('modal.refreshTitle')} type="button" variant="outline" size="icon-sm" className={act('copy')} onClick={() => refreshCert(false)}
            disabled={refreshing} aria-busy={refreshing || undefined} aria-label={t('modal.refresh')}>
            {refreshing ? <Spinner size={12} inline decorative /> : <RefreshCw size={14} aria-hidden="true" />}
          </Button>
      )}
      {canDiagnose && (
        <Button title={t('inv.diagnose')} type="button" variant="outline" size="icon-sm" className={act('edit')} data-slot="cert-diagnose" onClick={() => setShowDiag(true)} aria-label={t('inv.diagnose')}>
            <Stethoscope size={14} aria-hidden="true" />
          </Button>
      )}
      {!previewMode && canDeleteCert && (
        <Button title={t('inv.delete')} type="button" variant="outline" size="icon-sm" className={act('danger')}
            onClick={deleteCertificate} disabled={deleting} aria-label={t('inv.delete')}>
            <Trash2 size={14} aria-hidden="true" />
          </Button>
      )}
      {/* Ayraç YALNIZ solunda düğme varken çizilir; önizleme modunda tek başına kalan dikey çizgi olurdu. */}
      {!previewMode && <Separator orientation="vertical" className="mx-1 data-[orientation=vertical]:h-5" />}
      <Button type="button" variant="ghost" size="icon-sm" className="text-muted-foreground pointer-coarse:size-10"
        onClick={() => onClose()} aria-label={t('app.close')}>
        <X size={16} aria-hidden="true" />
      </Button>
    </div>
  )

  return (
    <>
    {/* Detay penceresi — ui/ModalShell (shadcn Dialog): odak tuzağı + iade, Escape (önizlemede kapalı), örtü tıklaması
        kapatır; üstte açık bir iç pencere (düzenleme formu, tanılama) varsa Escape yalnız onu kapatır. SOL RENK
        KENARI YOK — durum başlıktaki rozetle. Telefonda başlık satırı sarar; eylemler kendi satırına iner.
        Başlık telefonda YIĞILIR (DialogTitle tek satırlık flex): 1) alan adı + durum rozetleri, 2) salt okunur rozeti +
        sahibi takım kendi satırında tam genişlik (sm+ tek satır, eski düzen) — 390'da hiçbir şey kırpılmaz. */}
    <ModalShell open={!!domain} onClose={() => onClose()} size="lg" hideClose dismissOnEscape={!previewMode}
      // Ek 3/2 (2026-09-28): pencere App düzeyinde yaşar (sekmeden bağımsız) — içindeki bir bağlantı (7/24 göstergesinin
      // "7/24 Kapsamı'nda gör"ü, kesinti çizelgesi / dokunmatik listesi, alarm olay kartı, takım üyeleri penceresi) sekmeyi
      // pencerenin ARKASINDA değiştiriyor, pencere yeni ekranın üstünde açık kalıyordu. İçeriden gezinmede kapanır; dışarıdan
      // gelen gezinme (derin bağlantı useCertDeepLink, palet, Geri) ona dokunmaz (ModalShell `closeOnNavigate`).
      closeOnNavigate
      icon={Globe}
      // Genişlik (2026-09-27, kullanıcı: "sekmeler ikinci satıra düşüyor"): 8 sekme (ikon + etiket + sayaç, TR daha uzun)
      // md+'da TEK satırda dursun diye pencere 96vw / 1200px'e çıktı.
      // SABİT BOYUT (2026-09-28, kullanıcı: "sekmeler arasında gezinince pencere küçülüyor, titriyor" — MonitorDetailModal ile
      // aynı çözüm): kutu yüksekliği sekme içeriğine göre değişiyor, dikey ortalı pencere her geçişte yeniden konumlanıyordu.
      // Artık yükseklik sabit (sm+ 88vh tavanı; telefonda neredeyse tam ekran), başlık + eylemler sabit, sekme şeridi gövdenin
      // tepesine yapışık (sticky), YALNIZ içerik kayar (`scrollBody`); kaydırma çubuğuna yer ayrılır (genişlik oynamaz).
      scrollBody bodyRef={bodyRef}
      className={cn('grid-cols-[minmax(0,1fr)] sm:max-w-[min(96vw,1200px)] [&>[data-slot=dialog-header]]:flex-wrap',
        'h-[calc(100dvh-2rem)] max-h-[calc(100dvh-2rem)] sm:h-[min(88vh,calc(100dvh-2rem))] sm:max-h-[min(88vh,calc(100dvh-2rem))] sm:w-full',
        // scroll-pt: odak/scrollIntoView yapışık sekme şeridinin ALTINA kaydırsın (öğe şeridin arkasında kalmasın).
        '[&_[data-slot=modal-shell-body]]:[scrollbar-gutter:stable] [&_[data-slot=modal-shell-body]]:scroll-pt-14')}
      title={<span data-slot="cert-modal-title" className="flex min-w-0 flex-1 flex-col gap-1 sm:flex-row sm:flex-wrap sm:items-center sm:gap-2">
        <span className="flex min-w-0 items-center gap-2">
          <span className="min-w-0 truncate text-lg font-bold tracking-[-.02em]" title={domain}>{domain}</span>
          {d && (
            <Badge variant="secondary" data-slot="cert-modal-status" data-status={statusK}
              className={cn('shrink-0 font-bold', STATUS_TONE[statusK] ?? STATUS_TONE.valid)}>
              {statusLabel(statusK, t)}
            </Badge>
          )}
          {d && isInsecure(d) && (
            <Badge variant="secondary" data-slot="cert-insecure" title={securityTitle(d, t)}
              className={cn('shrink-0 font-bold', STATUS_TONE.error)}>
              {t('cert.sec.insecure')}
            </Badge>
          )}
        </span>
        {readOnly && <ReadOnlyBadge className="w-full text-sm font-normal sm:w-auto" teamId={readOnlyTeam?.id} teamName={readOnlyTeam?.name} />}
      </span>}
      headerExtra={<>{nocIndicator}{headerActions}</>}>

      {/*
        Sekme çubuğu (2026-09-26 yeniden tasarım — kullanıcı: "menüler sığmıyor, kaydırmak gerekiyor"): YATAY KAYDIRMA YOK.
        sm+: ikon + etiket + sayaç rozetli (açık alarm / not / SAN) sekmeler SARAR (iki satıra iner, kaydırmaz);
        telefon: aynı bölümler tek bir seçici (NativeSelect, 16 px yazı) — Tabs değeri aynı kaynaktan sürülür.
        Sekme içerikleri, derin bağlantı (`_tab`), önizleme ve salt okunur kuralları değişmedi:
        · Sağlık, SSL'in hemen yanında (kardeş yüzey); önizlemede gizli (envanterde olmayan alan için kalıcı kayıt yok).
        · Kontrol Geçmişi + Grafik önizlemede gizli; Alarmlar takım kapsamlı → başka takımın kaydında yok.
      */}
      <Tabs value={activeTab} onValueChange={switchTab} className="min-w-0 gap-3">
        {/* Seçici ve sekme şeridi kaydırılan gövdenin tepesine YAPIŞIK (sticky + opak zemin): içerik altından kayar. */}
        <div className="sticky top-0 z-10 bg-background pb-1 sm:hidden">
          <Label htmlFor={tabPickId} className="sr-only">{t('modal.sectionPicker')}</Label>
          <NativeSelect id={tabPickId} data-slot="cert-modal-section-picker" className="h-10" value={activeTab} onChange={(e) => switchTab(e.target.value)}>
            {tabDefs.map((td) => (
              <NativeSelectOption key={td.value} value={td.value}>{td.label}{td.count ? ` (${td.count})` : ''}</NativeSelectOption>
            ))}
          </NativeSelect>
        </div>
        {/* sm+ (şerit görünür olduğu her genişlikte): TEK satır, sarma yok (kullanıcı isteği 2026-09-27: "sekmeler alt
            satıra inmesin"). xl+ (≥1280, pencere 1200px) ikon + etiket + sayaç; altında ikon + sayaç (8 sekme 640'ta da
            sığar), etiket ekran okuyucuda (sr-only) + ipucu. İpucu TETİĞİN İÇİNDEKİ span'e bağlanır:
            TooltipTrigger asChild çocuğuna kendi data-state'ini yazar — TabsTrigger'a sarılırsa "active" ezilir. */}
        <TabsList variant="line" data-tour="cert-modal-tabs"
          className="hidden h-auto w-full flex-nowrap justify-start gap-x-0.5 gap-y-1 border-b bg-background pb-1 sm:sticky sm:top-0 sm:z-10 sm:flex group-data-[orientation=horizontal]/tabs:h-auto">
          {tabDefs.map((td) => (
            <TabsTrigger key={td.value} value={td.value} className="flex-none gap-1.5">
              <SimpleTooltip content={wideTabs ? null : td.label}>
                <span className="inline-flex items-center gap-1.5">
                  <td.Icon aria-hidden="true" className="size-3.5" />
                  <span data-slot="cert-tab-label" className="max-xl:sr-only">{td.label}</span>
                </span>
              </SimpleTooltip>
              {td.count ? (
                <Badge variant="secondary" data-slot="cert-tab-count" className="h-4 min-w-4 px-1 text-[10px] font-semibold tabular-nums">{td.count}</Badge>
              ) : null}
            </TabsTrigger>
          ))}
        </TabsList>

        {!previewMode && (
          <TabsContent value="health">
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} fullWidth />}>
              {/* key'e DOMAIN de girer: modal kalıcı mount'lu, domain değişince panel
                  remount olmazsa önceki kaydın satırları ekranda kalıyordu. */}
              <CertHealthPanel key={`${domain}:${reloadKey}`} domain={domain} canRefresh={!readOnly} />
            </Suspense>
          </TabsContent>
        )}

        {/* SSL Kontrol (2026-09-28 shadcn yeniden tasarım): hüküm + gruplu kontroller + zincir — SslCheckerPanel.
            "Yeniden kontrol et" eski sonucu ekranda tutar; ilk kontrol başarısızsa "Yeniden dene"li hata bloğu. */}
        <TabsContent value="ssl">
          {sslData ? (
            <SslCheckerPanel data={sslData} onRecheck={() => probeSsl(domain)} rechecking={sslLoading} recheckError={sslError} />
          ) : sslError != null ? (
            <StatusBlock tone="danger" icon={ShieldX} title={t('sslv.loadFailed')} description={sslError || t('sslv.loadFailedHint')}
              actions={(
                <Button type="button" variant="outline" className="gap-1.5 max-sm:h-10" onClick={() => probeSsl(domain)}>
                  <RefreshCw aria-hidden="true" className="size-4" />{t('sslv.retry')}
                </Button>
              )} />
          ) : (
            <LoadingBlock label={t('sslv.loading')} fullWidth />
          )}
        </TabsContent>

        {/* Sertifika Detayları (2026-09-28 shadcn + mobil web yeniden tasarım) — certmodal/CertDetailsPanel: hata bandı
            tepede, özet (durum + kalan gün + geçerlilik zaman çizelgesi), hızlı bakış karoları, Kimlik · Geçerlilik ·
            Anahtar · Güvenlik · Altyapı · SAN bölümleri. `d` yokken panel kendi iskeletini çizer; ton başlık rozetiyle aynı. */}
        <TabsContent value="details">
          <CertDetailsPanel d={d} tone={statusK} />
        </TabsContent>

        {!previewMode && (
          <TabsContent value="history">
            {/* Kontrol Geçmişi (2026-09-28 shadcn + mobil web yeniden tasarım) — certmodal/CertCheckHistory: paylaşılan
                CheckHistoryTab kabuğu (kind "uptime-ssl", monitorId = DOMAIN) + SSL özet kutucukları, kalan gün eğilimi
                (yenileme anları), shadcn satırlar / telefon kartları ve açılır ayrıntı. Başlıktaki "Çalıştır"/"Yenile" ve
                yeni kontrol yakalayan yoklama burayı da tazeler: `key` YERİNE sinyal — remount seçili aralığı, sayfayı ve
                süzgeci sıfırlardı. */}
            {/* runInHeader: boş aralık açıklaması "başlıktaki Çalıştır"ı YALNIZ o düğme gerçekten varsa anar — salt okunur
                pencerede / Çalıştır işleyicisi verilmeyen açılışta (mükerrer alan adı bandı) var olmayan düğmeye yönlendirmesin
                (Ek 3/3). Düğmenin koşuluyla AYNI: !previewMode && !readOnly && onCheckNow (geçmiş sekmesi önizlemede yok). */}
            <CertCheckHistory domain={domain} reloadSignal={reloadKey} runInHeader={!readOnly && !!onCheckNow}
              onDiagnose={canDiagnose ? () => setShowDiag(true) : undefined} />
          </TabsContent>
        )}

        {!previewMode && !readOnly && (
          <TabsContent value="alerts">
            <AlertHistory key={reloadKey} domain={domain} />
          </TabsContent>
        )}

        {!previewMode && (
          <TabsContent value="chart">
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} fullWidth />}>
              <ResponseTimeChart monitorId={domain} kind="ssl" />
            </Suspense>
          </TabsContent>
        )}

        {!previewMode && canViewInventory && (
          <TabsContent value="inventory">
            {/* Envanter Bilgileri (2026-09-28 yeniden tasarım): "Kaydı düzenle" başlıktaki Düzenle ile AYNI işleyici (salt
                okunurda yok); "Envanterde aç" pencereyi kapatıp Envanter ekranında kaydın panelini açar. */}
            <InventoryTab key={reloadKey} domain={domain} onEdit={!readOnly ? onEdit : undefined} onLeave={onLeave ?? (() => onClose())} />
          </TabsContent>
        )}

        {!previewMode && canViewInventory && !readOnly && (
          <TabsContent value="changes">
            {/* Kim ekledi / kim ne zaman neyi değiştirdi — certmodal/CertChangesTab (envanter değişiklik günlüğü) */}
            <Suspense fallback={<LoadingBlock label={t('modal.loading')} />}>
              <CertChangesTab key={reloadKey} t={t} domain={domain} />
            </Suspense>
          </TabsContent>
        )}

        {!previewMode && (
          <TabsContent value="notes">
            {/* Notlar (2026-09-28 shadcn yeniden tasarım) — certmodal/CertNotesTab; ekle/sil/geri yükle sekme sayacını tazeler. */}
            <CertNotesTab domain={domain} currentUser={currentUser} isAdmin={isAdmin} readOnly={readOnly} readOnlyTeam={readOnlyTeam}
              onCountChange={(n) => setTabCounts((c) => (c.notes === n ? c : { ...c, notes: n }))} />
          </TabsContent>
        )}
      </Tabs>
    </ModalShell>
    {showDiag && (
      <DiagnosticsModal domain={domain} port={d?.port || 443} onClose={() => setShowDiag(false)} />
    )}
    </>
  )
}

function statusKey(cert) {
  if (cert.alert_level) return cert.alert_level
  if (cert.status === 'error') return 'error'
  if (cert.days_remaining != null && cert.days_remaining < 0) return 'expired'
  if (cert.days_remaining != null && cert.days_remaining <= 7) return 'critical'
  if (cert.days_remaining != null && cert.days_remaining <= 15) return 'high'
  if (cert.warning) return 'warning'
  return 'valid'
}

function statusLabel(key, t) {
  const map = {
    valid:    () => t('card.valid'),
    warning:  () => t('card.warning'),
    high:     () => t('card.high'),
    critical: () => t('card.critical'),
    error:    () => t('card.error'),
    // Süresi dolmuş sertifika başlıkta "Hata" yazıyordu (Detaylar paneli "Süresi doldu" derken) — 2026-09-28.
    expired:  () => t('tbl.statusExpired'),
  }
  return (map[key] ?? map.valid)()
}
