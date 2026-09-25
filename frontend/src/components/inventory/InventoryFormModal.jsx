import { useState, useEffect, useRef, useMemo } from 'react'
import { ChevronDown, FlaskConical, Trash2, RefreshCw } from 'lucide-react'
import { copyText } from '../../utils/copyText.js'   // değişiklik açıklaması kopyala (2026-09-22)
import MDEditor, { commands as mdCommands } from '@uiw/react-md-editor'
import { api, formatDateOnly } from '../../api/client'
import { useDialog } from '../ui/Dialog.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'
import { useTheme } from '../../i18n/theme.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import TagInput from '../ui/TagInput.jsx'
import NotificationGroupSelect from '../ui/NotificationGroupSelect.jsx'
import { INVENTORY_FLAGS, emptyFlags } from '../../utils/inventoryFlags.js'
import { CONTACT_FIELDS, looksLikeBrokenEmail } from '../../utils/inventoryContacts.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import { CheckRunningStrip } from '../ui/CheckRunning.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import DiagnosticsModal from '../admin/DiagnosticsModal.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import { Button } from '@/components/shadcn/button'

/**
 * Envanter (sertifika) kayıt formu — InventoryManager'dan ÇIKARILDI ki dashboard kartındaki
 * "Düzenle"/"Kopyala" butonları da aynı formu açabilsin. Aynı klasördeki InventoryDetails.jsx
 * deseni izleniyor: saf bileşen + aynı dosyada kendi verisini çeken sarmalayıcı.
 *
 * Form state'inin TAMAMI burada; çağıran yalnız hangi modda açılacağını söyler ve kaydedilince
 * kendi listesini tazeler.
 */

const EMPTY = {
  domain: '', port: 443, owner: '', description: '', active: true,
  team_id: '', group_name: '', tags: '', notification_group_id: '', tier: null,
  ...emptyFlags(),          // 13 operasyonel bayrak — tek kaynak: utils/inventoryFlags.js
  tls_mode: '',
  timeout_seconds: '',
  check_interval_hours: '',   // '' = genel zamanlama; 1/6/12/24/168 saat
  purchased_by: '',
  platform: '', platform_detail: '',   // sitenin koştuğu ortam (2026-09-22)
  svc_mgmt_contact: '', app_dev_contact: '', iis_admin_contact: '', waf_admin_contact: '',
  change_description: '',
  expected_fingerprint: '', expected_subject: '',
}

function YesNo({ value, onChange }) {
  const t = useT()   // "Evet/Hayır" sabitti: İngilizce arayüzde her envanter boolean'ı Türkçe kalıyordu
  const isYes = value === true
  return (
    <div className="yn-group">
      <button type="button" className={`yn-btn${isYes ? ' yn-active' : ''}`}
        onClick={() => onChange(true)}>{t('inv.yes')}</button>
      <button type="button" className={`yn-btn${!isYes ? ' yn-active' : ''}`}
        onClick={() => onChange(false)}>{t('inv.no')}</button>
    </div>
  )
}

/** Araç çubuğu pano ikonu — MDEditor komutları lucide bileşeni değil düz SVG ister (WeeklyReportsPage deseni). */
const COPY_ICON = (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
    <rect x="9" y="9" width="13" height="13" rx="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
)

function SectionHeader({ label }) {
  return <div className="form-section-header">{label}</div>
}

/** Kayıt → form state eşlemesi. Düzenle ve Kopyala AYNI eşlemeyi kullanır → alan kaçmaz. */
function formFrom(item) {
  return {
    ...EMPTY,
    ...item,
    team_id:            String(item.team_id ?? ''),
    notification_group_id: item.notification_group_id != null ? String(item.notification_group_id) : '',
    external_vendor:    item.external_vendor  ?? false,
    action_required:    item.action_required  ?? false,
    openshift:          item.openshift        ?? false,
    ssl_pinning:        item.ssl_pinning      ?? false,
    internal_cert:      item.internal_cert    ?? false,
    jks_keystore:       item.jks_keystore     ?? false,
    server_update:      item.server_update    ?? false,
    netscaler:          item.netscaler        ?? false,
    waf_enabled:        item.waf_enabled      ?? false,
    in_use:             item.in_use           ?? false,
    ev_certificate:     item.ev_certificate   ?? false,
    transferred_to_sy:  item.transferred_to_sy ?? false,
    use_proxy:          item.use_proxy        ?? false,
    tls_mode:           item.tls_mode         ?? '',
    timeout_seconds:    item.timeout_seconds != null ? String(item.timeout_seconds) : '',
    check_interval_hours: item.check_interval_hours != null ? String(item.check_interval_hours) : '',
    purchased_by:       item.purchased_by     ?? '',
    platform:           item.platform         ?? '',
    platform_detail:    item.platform_detail  ?? '',
    change_description: item.change_description ?? '',
    expected_fingerprint: item.expected_fingerprint ?? '',
    expected_subject:   item.expected_subject ?? '',
    tier:               item.tier ?? null,
  }
}

/** Modun başlangıç form durumu. */
function initialForm(mode, record) {
  if (mode === 'add' || !record) return EMPTY
  const base = formFrom(record)
  if (mode !== 'duplicate') return base
  // Kopyada taşınMAyan iki alan — expected_* : o domain'in BEKLENEN sertifika parmak izi/subject'i. Kopyaya
  // taşınırsa yeni domain sürekli DEPLOYMENT_INCOMPLETE alarmı üretir (ScriptedMonitorPage'in gizli env'leri
  // sıfırlamasıyla aynı mantık). Yenileme planı (renewal_planned_*) formda yok, dolayısıyla zaten taşınmaz.
  // change_description ARTIK KOPYALANIR (kullanıcı kararı 2026-09-22): aynı süreç/ekip notu kardeş
  // domainlerde ortaktır; kullanıcı gerekirse düzenler. Geri kalan HER alan formFrom ile birebir taşınır.
  return { ...base, expected_fingerprint: '', expected_subject: '' }
}

/**
 * @param {'add'|'edit'|'duplicate'} mode
 * @param {object|null} record            edit/duplicate kaynağı
 * @param {Array}  [teams]                verilmezse bileşen kendisi çeker (dashboard yolu)
 * @param {boolean} [canManage=true]      yönetici (admin / takım yöneticisi): tüm alanlar + takım değiştirme
 * @param {boolean} [canWrite=false]      ekleme yetkili kullanıcı (USER, inventory.crud): alanlar AÇIK, takım yalnız
 *                                        ekle/kopyala'da seçilir (düzenlemede sunucu mevcut takımı korur)
 * @param {boolean} [canMoveTeam=false]   DÜZENLEMEDE takım aktarımı. Sunucu kuralının aynası (AdminController
 *                                        updateInventory): takım yalnız rol ADMIN'de yazılır — global admin ya da
 *                                        kapsamlı müdür (yönetim kapsamı = görüş kapsamı, açabildiği her kayıt
 *                                        kapsamında); TEAM_ADMIN ve USER'da mevcut takıma SABİTLENİR. Varsayılan
 *                                        KAPALI: bilmeyen çağıran kutuyu açıp "Kaydedildi" yalanına yol açmasın.
 * @param {Function} onClose
 * @param {Function} onSaved              (savedResponse) => void — çağıran kapatır + tazeler
 */
export default function InventoryFormModal({ mode = 'add', record = null, teams: teamsProp,
                                             canManage = true, canWrite = false, canMoveTeam = false,
                                             onClose, onSaved, focus = null }) {
  const t = useT()
  const { theme } = useTheme()
  const toast = useToast()
  // Araç çubuğu "Panoya kopyala" komutu (2026-09-22): editörün API'sinden GÜNCEL metni alır (form state ile aynı);
  // boşken uyarır, "kopyalandı" yalanı söylemez.
  const copyCommand = useMemo(() => ({
    name: 'copy-all', keyCommand: 'copy-all',
    buttonProps: { 'aria-label': t('inv.copyChangeDesc'), title: t('inv.copyChangeDesc') },
    icon: COPY_ICON,
    execute: async (state) => {
      const text = state?.text ?? ''
      if (!text.trim()) { toast.info(t('inv.copyEmpty')); return }
      if (await copyText(text)) toast.success(t('inv.changeDescCopied')); else toast.error(t('inv.copyDescFailed'))
    },
  }), [t, toast])
  const { showConfirm } = useDialog()

  const [form, setForm]   = useState(() => initialForm(mode, record))
  const [teams, setTeams] = useState(() => teamsProp ?? [])
  // "Domain Ekle" her kullanıcı seviyesinde (2026-09-18 ürün kararı) ama seçiciler yalnız yöneticiye açıktı:
  // USER takım/grup/etiket seçemediği için kayıt açamıyordu (2026-09-25 kullanıcı bildirimi). Artık ekleme
  // yetkisi alanları açar; takım listesi zaten üyesi olduğu takımlar (çağıran geçer), sunucu üyeliği doğrular.
  const fieldsEnabled = canManage || canWrite
  // Düzenlemede takım değişikliği TEAM_ADMIN ve USER için sunucuda YOK SAYILIR (updateInventory mevcut takımı
  // yazar; seçilen takımın bildirim grubu düşer, grup adı eski takımın altında yaratılır) ama ekran "Kaydedildi"
  // diyordu → kutu yalnız sunucunun takımı gerçekten yazdığı rolde açık (R4, 2026-09-25).
  const teamPickable = mode === 'edit' ? (canMoveTeam && fieldsEnabled) : fieldsEnabled
  const [teamGroups, setTeamGroups] = useState([])   // seçili takımın "cert" grupları (sızıntısız, server-scoped)
  const [teamTags, setTeamTags] = useState([])   // takımın kullanımdaki etiketleri → TagInput önerileri (2026-09-22)
  const [platforms, setPlatforms] = useState([])   // Ayarlar → Platformlar kataloğu (aktifler); düzenlenen kayıttaki pasif kod da listede kalır
  useEffect(() => {
    let alive = true
    api.admin.listPlatforms().then(r => { if (alive) setPlatforms(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setPlatforms([]) })
    return () => { alive = false }
  }, [])
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState(null)
  const [showScrollHint, setShowScrollHint] = useState(false)
  const [showDiag, setShowDiag] = useState(false)
  const [running, setRunning]   = useState(false)
  // Kaydetmenin ardından koşan OTOMATİK ilk kontrol (elle "Çalıştır"dan ayrı bayrak: ikisi
  // farklı düğmeleri kilitliyor ve şeridin metni aynı olsa da tetikleyicisi farklı).
  const [firstRun, setFirstRun] = useState(false)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState(null)   // {status, issuer, not_after, days_remaining, ...} | {error}
  const [deleting, setDeleting] = useState(false)
  const formGridRef = useRef(null)
  const contactsRef = useRef(null)
  // Karttaki "Sorumlu kişi yok" çipinden gelince (2026-09-20) form Sorumlu Ekipler bölümünde açılır.
  useEffect(() => {
    if (focus !== 'contacts') return
    const id = setTimeout(() => { try { contactsRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' }); contactsRef.current?.classList.add('is-focus-target') } catch { /* jsdom */ } }, 50)
    return () => clearTimeout(id)
  }, [focus])

  const isDuplicate = mode === 'duplicate'
  // Silme yetkisi: CertificateModal ile AYNI kapı (inventory.crud) — ikinci bir yetki yolu açılmaz.
  const canDelete = usePermissions().canEdit('inventory.crud')
  // Tanilama BASKA bir kapidan gecer (diagnostics.run/execute); test sonucundaki eylem, yetkisi
  // olmayan kullaniciya 403 alacagi bir dugme gostermesin.
  const canDiagnose = usePermissions().canExecute('diagnostics.run')
  // Kayıtlı alan adı: "Çalıştır" bunu kullanır (formdaki HENÜZ KAYDEDİLMEMİŞ değeri değil).
  const savedDomain = mode === 'edit' ? (record?.domain || null) : null

  // Takım listesi: InventoryManager kendi listesini geçer (ekstra istek yok); dashboard geçmez.
  useEffect(() => {
    if (teamsProp) { setTeams(teamsProp); return }
    let alive = true
    api.admin.getTeams().then(res => { if (alive && res?.success) setTeams(res.data) })
    return () => { alive = false }
  }, [teamsProp])

  // Tek takımı olan kullanıcıda yeni kayıt o takımla açılır — seçilecek başka bir şey yok.
  useEffect(() => {
    if (mode === 'edit' || teams.length !== 1) return
    setForm(prev => (prev.team_id ? prev : { ...prev, team_id: String(teams[0].id) }))
  }, [mode, teams])

  useEffect(() => {
    const el = formGridRef.current
    if (!el) return
    const check = () => {
      const hasOverflow = el.scrollHeight > el.clientHeight + 2
      const atBottom    = el.scrollTop + el.clientHeight >= el.scrollHeight - 6
      setShowScrollHint(hasOverflow && !atBottom)
    }
    check()
    el.addEventListener('scroll', check, { passive: true })
    const ro = new ResizeObserver(check)
    ro.observe(el)
    return () => { el.removeEventListener('scroll', check); ro.disconnect() }
  }, [])

  // Seçili takımın "cert" gruplarını sunucudan getir (başka takım sızmaz).
  useEffect(() => {
    if (!form.team_id) { setTeamGroups([]); setTeamTags([]); return }
    let alive = true
    api.monitoring.listGroups(form.team_id, 'cert').then(r => { if (alive && r?.success) setTeamGroups(r.data || []) })
    api.monitoring.listTags(form.team_id).then(r => { if (alive) setTeamTags(r?.success ? (r.data || []) : []) }).catch(() => { if (alive) setTeamTags([]) })
    return () => { alive = false }
  }, [form.team_id])

  function f(field, val) { setForm(prev => ({ ...prev, [field]: val })); if (msg) setMsg(null) }

  // Meşgul evresi TEK yerde, başlıkta anlatılır (düğme metinleri sabit kalır — bkz. alt bar notu).
  const busyLabel = saving ? t('inv.saving') : firstRun ? t('inv.saveRunning')
    : testing ? t('inv.testing') : running ? t('inv.running') : null

  function validate() {
    if (!form.domain.trim()) return t('inv.formDomain') + ' zorunlu'
    if (!form.team_id) return t('inv.teamRequired')
    // Grup + etiket zorunlu (2026-09-18): envanter kaydı da bir izleme — dokuz türle aynı kural.
    if (!form.group_name?.trim()) return t('inv.groupRequired')
    if (!form.tags?.trim()) return t('inv.tagsRequired')
    return null
  }

  /**
   * Test et — YAZILAN değerlerle canlı el sıkışması. KAYDETMEZ, alarm ÜRETMEZ.
   *
   * <p><b>Eskiden ne yapıyordu.</b> Doğrudan {@code DiagnosticsModal}'ı açıyordu, yani
   * başlıktaki "Tanılama" ile BİREBİR aynı iş: iki etiketli tek eylem. Sertifikanın kendisi
   * (veren / bitiş / kalan gün) hiç test edilmiyordu — oysa dokuz izleme formunun sözleşmesi
   * "yazılan değerlerle gerçek kontrolü koştur, sonucu FORMDA göster". Kod yorumu bu
   * sözleşmeye atıf yapıyordu ama davranış onu tutmuyordu.
   *
   * <p>Test, KAYDEDİLECEK olan değerlerin aynısıyla koşar (port / TLS modu / proxy / zaman
   * aşımı) — yoksa testin doğruladığı şey kaydedilen şey olmaz. Bu yüzden envanterden okuyan
   * {@code check-preview} kullanılamadı: henüz kaydedilmemiş bir kayıtta formdaki 8443'ü
   * görmez, 443'ün sertifikasını gösterirdi.
   */
  async function runTest() {
    if (!form.domain.trim()) { setMsg(t('inv.formDomain') + ' zorunlu'); return }
    setMsg(null); setTesting(true); setTestResult(null)
    let res = null
    try {
      res = await api.testCertificate({
        domain: form.domain.trim(),
        port: parseInt(form.port) || 443,
        tlsMode: form.tls_mode || null,
        useProxy: !!form.use_proxy,
        timeoutSeconds: form.timeout_seconds && Number(form.timeout_seconds) > 0
          ? Number(form.timeout_seconds) : null,
      })
    } catch (e) {
      res = { success: false, error: e?.message || String(e) }
    }
    setTesting(false)
    setTestResult(res?.success ? res.data : { status: 'error', error: res?.error || t('inv.testError') })
  }

  /**
   * Çalıştır — KAYITLI kaydın alan adıyla gerçek bir kontrol koşturur (kalıcı yazılır,
   * alarm üretebilir). Bilinçli olarak formdaki değeri KULLANMAZ: kaydedilmemiş bir adresle
   * kalıcı kontrol yazmak, kullanıcının istemediği sessiz bir yazma işlemi olurdu.
   */
  async function runNow() {
    if (!savedDomain) return
    setRunning(true); setMsg(null)
    try {
      const res = await api.refreshCertificateHealth(savedDomain)
      if (res?.success) { toast.success(t('inv.runDone', savedDomain)); onSaved?.() }
      else toast.error(res?.error || t('inv.runError'))
    } finally {
      setRunning(false)
    }
  }

  /** Sil — mevcut DELETE /admin/inventory/{id}: denetim kaydı, soft-delete ve açık alarmların
   *  kapatılması kendiliğinden miras kalır. Yeni uç YOK. */
  async function del() {
    if (!record?.id) return
    const ok = await showConfirm({
      title: t('inv.deleteTitle'),
      message: t('inv.deleteMsg', record.domain),
      confirmText: t('inv.deleteConfirm'),
      cancelText: t('inv.deleteCancel'),
      variant: 'danger',
    })
    if (!ok) return
    setDeleting(true)
    try {
      const res = await api.admin.deleteInventory(record.id)
      if (res?.success) { toast.success(t('inv.deleted')); onSaved?.(); onClose?.() }
      else toast.error(res?.error || t('inv.deleteError'))
    } finally {
      setDeleting(false)
    }
  }

  async function save() {
    const err = validate()
    if (err) {
      // Mesaj Kaydet düğmesinin hemen üstünde YÜZER (gövdeyi itmez, başa kaydırmaz): gözün zaten
      // olduğu yerde belirir ve form bir piksel oynamaz.
      setMsg(err)
      return
    }

    // Domain rename uyarısı — geçmiş veri taşıma onay isteği. YALNIZ edit'te: kopyada kaynak
    // domain zaten dolu geliyor ve kullanıcının onu değiştirmesi BEKLENEN akış.
    if (mode === 'edit' && record?.domain && form.domain.trim() !== record.domain) {
      const confirmed = await showConfirm({
        title: t('inv.renameTitle'),
        message: t('inv.renameMessage', record.domain, form.domain.trim()),
        confirmText: t('inv.renameConfirm'),
        cancelText: t('inv.cancel'),
      })
      if (!confirmed) return
    }

    setSaving(true)
    try {
      setMsg(null)
      const payload = {
        domain:             form.domain.trim(),
        port:               parseInt(form.port) || 443,
        owner:              form.owner,
        description:        form.description,
        // Sorumlu Ekipler — bilgilendirme alanlari; alarm YONLENDIRMESINE girmez.
        svc_mgmt_contact:   form.svc_mgmt_contact?.trim() || null,
        app_dev_contact:    form.app_dev_contact?.trim() || null,
        iis_admin_contact:  form.iis_admin_contact?.trim() || null,
        waf_admin_contact:  form.waf_admin_contact?.trim() || null,
        active:             form.active,
        team_id:            form.team_id ? Number(form.team_id) : null,
        // Bos = takim varsayilani -> takim adresi (zincirin kalani).
        // Anahtar SNAKE_CASE olmak ZORUNDA: uc @RequestBody CertificateInventory ile baglaniyor ve
        // Jackson spring.jackson.property-naming-strategy=SNAKE_CASE altinda calisiyor. camelCase
        // gonderilen anahtar SESSIZCE yok sayilir (bilinmeyen alan) -> deger null baglanir ve
        // updateInventory onu mevcut kaydin UZERINE yazar. Monitor uclari farkli: onlar
        // @RequestBody Map alip anahtari duz okuyor, orada camelCase DOGRU.
        notification_group_id: form.notification_group_id ? Number(form.notification_group_id) : null,
        group_name:         form.group_name?.trim() || null,
        tags:               form.tags?.trim() || null,   // formda alan yoktu → düzenleme etiketleri SİLİYORDU (2026-09-18)
        ug_team_id:         null,   // tek takım modeli — UG ayrımı kaldırıldı
        external_vendor:    form.external_vendor,
        action_required:    form.action_required,
        openshift:          form.openshift,
        ssl_pinning:        form.ssl_pinning,
        internal_cert:      form.internal_cert,
        jks_keystore:       form.jks_keystore,
        server_update:      form.server_update,
        netscaler:          form.netscaler,
        waf_enabled:        form.waf_enabled,
        in_use:             form.in_use,
        ev_certificate:     form.ev_certificate,
        transferred_to_sy:  form.transferred_to_sy,
        use_proxy:          form.use_proxy,
        tls_mode:           form.tls_mode || null,
        // BOŞ = global ayar. 0/negatif GÖNDERİLMEZ: sunucu onu geçersiz sayıp global'e
        // düşüyor, ama burada da elemek "kaydettim ama olmadı" turunu engelliyor.
        timeout_seconds:    form.timeout_seconds && Number(form.timeout_seconds) > 0
                              ? Number(form.timeout_seconds) : null,
        // Kontrol sıklığı (2026-09-12): BOŞ = genel saatlik zamanlama; sunucu 1/6/12/24/168 dışını null sayar.
        check_interval_hours: form.check_interval_hours ? Number(form.check_interval_hours) : null,
        purchased_by:       form.purchased_by || null,
        platform:           form.platform || null,
        platform_detail:    form.platform_detail?.trim() || null,
        change_description: form.change_description || null,
        // DİKKAT: payload'ın tek camelCase çifti (entity Jackson adlarıyla eşleşsin diye).
        // snake_case'e "düzeltilirse" iki alan sessizce null gider.
        expected_fingerprint: form.expected_fingerprint || null,
        expected_subject:    form.expected_subject || null,
        tier:               form.tier ? Number(form.tier) : null,
      }
      const res = mode === 'edit'
        ? await api.admin.updateInventory(record.id, payload)
        : await api.admin.addInventory(payload)
      setSaving(false)
      if (res?.success) {
        toast.success(t('inv.saved'))
        if ((res.alertsClosed ?? 0) > 0) {
          toast.success(t('inv.deactivatedAlerts', res.alertsClosed))
        }
        // ── Kaydetmenin ARDINDAN otomatik ilk kontrol ────────────────────────────────────
        // Yeni eklenen domain, zamanlayıcı sırası gelene kadar kartta "kontrol edilmedi" diye
        // duruyordu; kullanıcı kaydedip ayrıca ▶'ye basmak zorundaydı. Düzenlemede de gerekli:
        // port / TLS modu / proxy değişince saklanan son sonuç ARTIK O AYARIN sonucu değil.
        //
        // Elle "Çalıştır" ile AYNI uç (`refreshCertificateHealth`) — ikinci bir kontrol yolu
        // üretilmiyor. Beklenir (fire-and-forget değil): sonucu görmeden kapatmak, kartın bir
        // an "kontrol edilmedi" gösterip sonra sessizce değişmesi demekti.
        //
        // Kontrol düşerse KAYIT YİNE BAŞARILIDIR: ayrı bir bildirimle söylenir, form kapanır.
        // Aksi hâlde ağ hatası kullanıcıya "kaydedilmedi" gibi görünürdü.
        const savedNow = form.domain.trim()
        setFirstRun(true)
        let chk = null
        try { chk = await api.refreshCertificateHealth(savedNow) } catch (e) { chk = { success: false, error: e?.message } }
        setFirstRun(false)
        if (!chk?.success) toast.error(t('inv.saveRunFailed', chk?.error || '—'))
        onSaved?.(res, savedNow)
      } else {
        // Sunucu hatası → tek bildirim (toast); modal AÇIK kalır (mükerrer domain 409'u burada görünür).
        // Inline setMsg yalnız form validation için.
        toast.error(res?.error || t('inv.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    // Dış (overlay) tıklamada KAPANMAZ — girilen veri kaybolmasın; yalnız İptal/Kaydet kapatır.
    <div className="modal-overlay">
      <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
        <h3 className="modal-wide-title">
          <span>
            {mode === 'edit' ? t('inv.editTitle') : t('inv.addTitle')}
            {isDuplicate && <span className="mon-dup-badge">{t('mon.duplicateBadge')}</span>}
          </span>
          {/* "Kaydediliyor… / İlk kontrol koşuyor… N sn" şeridi BAŞLIKTA (CertificateModal ile aynı yer):
              alt barda düğmelerin arasına girince satır taşıyor ve her düğme kayıyordu (2026-09-19). */}
          <CheckRunningStrip running={!!busyLabel} label={busyLabel} />
        </h3>
        {isDuplicate && <div className="mon-dup-hint">{t('inv.duplicateHint')}</div>}

        {/* form-grid--top (2026-09-19): ızgara varsayılanı alt-hizalı; ipuçlu alanlar (zaman aşımı) yanındaki alanı yukarı itiyordu */}
        <div className="form-grid form-grid--top" ref={formGridRef}>

          {/* ── Temel Bilgiler ── */}
          <SectionHeader label={t('inv.sectionBasic')} />

          <label className="checkbox-label full-width">
            <input type="checkbox" checked={form.active} onChange={e => f('active', e.target.checked)} />
            {t('inv.formActive')}
          </label>

          <label>
            <span>{t('inv.formDomain')} <span className="req-star">*</span></span>
            <input value={form.domain} onChange={e => f('domain', e.target.value)}
              placeholder={t('inv.formDomainPh')} autoFocus={isDuplicate} />
          </label>
          <label>
            {t('inv.formPort')}
            <input type="number" value={form.port} onChange={e => f('port', e.target.value)} />
          </label>

          <label>
            <span>{t('inv.formTeam')} <span className="req-star">*</span></span>
            <SearchableSelect
              value={form.team_id}
              onChange={v => f('team_id', v)}
              placeholder={t('inv.selectTeam')}
              disabled={!teamPickable}
              searchThreshold={2}
              options={[
                { value: '', label: t('inv.selectTeam') },
                ...teams.map(team => ({ value: team.id, label: team.name })),
              ]}
            />
          </label>

          <label>
            <span>{t('inv.formGroup')} <span className="req-star">*</span></span>
            <SearchableSelect
              value={form.group_name}
              onChange={v => f('group_name', v)}
              placeholder={t('inv.noGroup')}
              disabled={!fieldsEnabled || !form.team_id}
              creatable
              onCreate={() => {}}
              searchThreshold={2}
              options={[
                { value: '', label: t('inv.noGroup') },
                ...teamGroups.map(g => ({ value: g.name, label: g.name })),
              ]}
            />
          </label>

          <NotificationGroupSelect
            teamId={form.team_id}
            value={form.notification_group_id}
            onChange={v => f('notification_group_id', v)}
            disabled={!fieldsEnabled}
          />

          {/* Platform (2026-09-22, kullanıcı isteği): site nerede koşuyor — sertifikayı KİM/NEREYE kuracak sorusunun cevabı.
              Bildirim grubunun KARŞISINDA (aynı satır); seçici + ayrıntı tek hücrede. Katalog Ayarlar → Platformlar; kayıttaki kod
              pasife alınmışsa yine seçili görünür (sessizce düşmesin). */}
          <div className="inv-platform-field">
            <label>
              {t('inv.formPlatform')}
              <SearchableSelect value={form.platform || ''} onChange={v => f('platform', v)} searchThreshold={6} disabled={!fieldsEnabled}
                options={[{ value: '', label: t('inv.platformNone') },
                  ...platforms.map(p => ({ value: p.code, label: p.name, title: p.description || undefined, hint: p.description || undefined })),   // açıklama: satır altı + tooltip (kullanıcı isteği)
                  ...(form.platform && !platforms.some(p => p.code === form.platform) ? [{ value: form.platform, label: form.platform }] : [])]} />
            </label>
            <input className="input input-sm" value={form.platform_detail} onChange={e => f('platform_detail', e.target.value)} maxLength={160}
              placeholder={t('inv.formPlatformDetailPh')} aria-label={t('inv.formPlatformDetail')} disabled={!fieldsEnabled} />
            <span className="field-hint">{t('inv.formPlatformHint')}</span>
          </div>

          {/* Etiketler — zorunlu (2026-09-18). Tablo/CSV zaten okuyordu; form alanı yoktu. */}
          <div className="full-width http-tags-block">
            <div className="http-block-title">{t('inv.formTags')} <span className="req-star">*</span></div>
            <div className="field-hint" style={{ marginBottom: 6 }}>{t('inv.tagsHint')}</div>
            <TagInput value={form.tags} onChange={v => f('tags', v)} disabled={!fieldsEnabled} placeholder={t('mon.tagsPlaceholder')} suggestions={teamTags} />
          </div>

          <label>
            {t('inv.formTier')}
            <SearchableSelect
              value={form.tier ?? ''}
              onChange={v => f('tier', v ? Number(v) : null)}
              options={[
                { value: '', label: t('inv.tierNone') },
                { value: '1', label: t('inv.tier1') },
                { value: '2', label: t('inv.tier2') },
                { value: '3', label: t('inv.tier3') },
                { value: '4', label: t('inv.tier4') },
              ]}
            />
          </label>

          <label>
            {t('inv.formTimeout')}
            <input type="number" className="input" min="1" max="60"
              value={form.timeout_seconds}
              placeholder={t('inv.formTimeoutPlaceholder')}
              onChange={e => f('timeout_seconds', e.target.value)} />
            <span className="field-hint">{t('inv.formTimeoutHint')}</span>
          </label>

          <label>
            {t('inv.formInterval')}
            <SearchableSelect
              value={form.check_interval_hours}
              onChange={v => f('check_interval_hours', v)}
              options={[
                { value: '',    label: t('inv.intervalInherit') },
                { value: '1',   label: t('inv.interval1h') },
                { value: '6',   label: t('inv.interval6h') },
                { value: '12',  label: t('inv.interval12h') },
                { value: '24',  label: t('inv.interval24h') },
                { value: '168', label: t('inv.interval168h') },
              ]}
            />
            <span className="field-hint">{t('inv.formIntervalHint')}</span>
          </label>

          <label>
            {t('inv.formTlsMode')}
            <SearchableSelect
              value={form.tls_mode}
              onChange={v => f('tls_mode', v)}
              options={[
                { value: '',        label: t('inv.tlsModeInherit') },
                { value: 'browser', label: t('inv.tlsModeBrowser') },
                { value: 'default', label: t('inv.tlsModeDefault') },
              ]}
            />
          </label>

          <label>
            {t('inv.formPurchasedBy')}
            <input value={form.purchased_by} onChange={e => f('purchased_by', e.target.value)} />
          </label>


          {/* Vekil anahtarı (2026-09-22, kullanıcı isteği): 13 Evet/Hayır bayrağı arasında gömülüydü; sertifika kontrolünün
              yolunu belirleyen bu tercih Temel Bilgiler'de, Satın Alan'ın yanında AÇIK/KAPALI anahtarı olarak. Bayrak
              listesi (INVENTORY_FLAGS) değişmez — dışa aktarım/e-posta/backend senkronu aynı kalır. */}
          <div className="inv-proxy-field">
            <span className="inv-proxy-label" id="inv-proxy-label">{t('inv.formUseProxy')}</span>
            <div className="inv-proxy-switch-row">
              <button type="button" role="switch" aria-checked={!!form.use_proxy} aria-labelledby="inv-proxy-label"
                className={`perm-pill ${form.use_proxy ? 'perm-pill-on' : 'perm-pill-off'}`}
                onClick={() => f('use_proxy', !form.use_proxy)}>
                <span className="perm-pill-knob" />
              </button>
              <span className={`inv-proxy-state${form.use_proxy ? ' is-on' : ''}`}>{form.use_proxy ? t('mon.proxy.on') : t('mon.proxy.off')}</span>
            </div>
            <span className="field-hint">{t('inv.formUseProxyHint')}</span>
          </div>

          {/* ── Sorumlu Ekipler ── */}
          <div ref={contactsRef} className="full-width" data-testid="contacts-anchor" />
          <SectionHeader label={t('inv.sectionContacts')} />
          <div className="full-width">
            <span className="field-hint">{t('inv.contactsHint')}</span>
          </div>
          {CONTACT_FIELDS.map(({ key, labelKey }) => (
            <label key={key}>
              {t(labelKey)}
              <input value={form[key] ?? ''} maxLength={300}
                placeholder={t('inv.contactsPh')}
                onChange={e => f(key, e.target.value)} />
              {looksLikeBrokenEmail(form[key]) && (
                <span className="field-hint field-hint--warn">{t('inv.contactsWarn')}</span>
              )}
            </label>
          ))}

          {/* ── Operasyonel Bilgiler ── */}
          <SectionHeader label={t('inv.sectionOps')} />

          <div className="yn-grid">
            {INVENTORY_FLAGS.filter(({ key }) => key !== 'use_proxy').map(({ key, labelKey }) => (   /* vekil: Temel Bilgiler'de anahtar */
              <div key={key} className="yn-field-row">
                <span className="yn-field-label">{t(labelKey)}</span>
                <YesNo value={form[key]} onChange={v => f(key, v)} />
              </div>
            ))}
          </div>

          {/* Editör bilerek <label> ile SARILMAZ. MDEditor, görünen <pre> katmanının üstüne
              metni şeffaf, mutlak konumlu bir <textarea> koyar; `.form-grid label textarea`
              (0,2,1) kütüphanenin (0,1,0) kurallarını yenip o overlay'e OPAK arka plan verince
              <pre> tamamen örtülüyor ve kutu BOŞ görünüyordu. Field, etiketi htmlFor ile ayrı
              kurar — bağ korunur, seçici artık eşleşmez. */}
          {/* Kopyala, editörün KENDİ araç çubuğunda (kullanıcı seçimi 2026-09-22): MDEditor'ün şeffaf textarea katmanında
              fareyle seçim güvenilmez; sağdaki görünüm ikonlarının yanındaki pano ikonu metni tek tıkla kopyalar. */}
          <Field label={t('inv.formChangeDesc')} className="full-width">
            {({ id }) => (
              <div className="md-editor-box" data-color-mode={theme === 'dark' ? 'dark' : 'light'}>
                <MDEditor
                  value={form.change_description}
                  onChange={(v) => f('change_description', v ?? '')}
                  preview="edit"
                  height={260}
                  visibleDragbar={false}
                  extraCommands={[copyCommand, mdCommands.divider, mdCommands.codeEdit, mdCommands.codePreview, mdCommands.fullscreen]}
                  textareaProps={{ id }}
                />
              </div>
            )}
          </Field>

        </div>

        {msg && (
          <div className="modal-wide-float">
            <AlertBanner tone="danger" role="alert" onDismiss={() => setMsg(null)} dismissLabel={t('app.close')}>{msg}</AlertBanner>
          </div>
        )}

        {showScrollHint && !msg && (
          <button
            type="button"
            className="modal-scroll-hint"
            onClick={() => formGridRef.current?.scrollBy({ top: 200, behavior: 'smooth' })}
          >
            <ChevronDown size={14} />
            <span>{t('inv.scrollForMore')}</span>
          </button>
        )}

        {/* Test sonucu — dokuz izleme formunun sözleşmesinin ikinci yarısı: kontrol YAZILAN
            değerlerle koşar ve sonucu FORMDA gösterir (modal açmaz, kayıt bırakmaz).
            Kutuyu elle kurmak yerine AlertBanner: token'lı ve koyu-tema uyumlu tek yüzey —
            emsal sayfalardaki gömülü hex'li kutular koyu temada okunmuyor.
            Başarısızlıkta "Tanılama" eylemi BURAYA asılır: "neden başarısız" sorusunun cevabı
            orada ve form kapanmadan, yazılan host:port ile açılır. */}
        {testResult && (
          <AlertBanner
            tone={testResult.status === 'error' ? 'danger'
              : testResult.status === 'warning' ? 'warning' : 'success'}
            title={testResult.status === 'error' ? t('inv.testFailed')
              : testResult.status === 'warning' ? t('inv.testExpiring') : t('inv.testValid')}
            actions={testResult.status === 'error' && canDiagnose
              ? <Button variant="secondary" size="sm" onClick={() => setShowDiag(true)}>
                  {t('inv.diagnose')}
                </Button>
              : null}>
            {testResult.status === 'error'
              ? (testResult.error || t('inv.testError'))
              : (
                <>
                  {testResult.issuer && <>{t('inv.testIssuer')}: <b>{testResult.issuer}</b> · </>}
                  {t('inv.testExpiry')}: <b>{formatDateOnly(testResult.not_after)}</b>
                  {testResult.days_remaining != null && <> · {t('inv.testDaysLeft', testResult.days_remaining)}</>}
                </>
              )}
            {/* "Hangi portu, nasıl denedin" — testin kaydedilecek değerlerle koştuğunun kanıtı. */}
            <div className="field-hint" style={{ marginTop: 4 }}>
              {t('inv.testRanWith', testResult.port ?? (Number(form.port) || 443),
                testResult.via === 'proxy' ? t('inv.testViaProxy') : t('inv.testViaDirect'))}
            </div>
          </AlertBanner>
        )}

        {/* Alt bar — dokuz izleme formunun KANONİK düzeni (bkz. HttpMonitorPage):
            [Test et (solda)] … [Sil] [İptal] [Kaydet]. Envanter formu bu düzene uymayan
            tek düzenleme formuydu. */}
        {/* Düğme METİNLERİ SABİT (Kaydet / Test et / Çalıştır): "Kaydediliyor…" → "İlk kontrol
            koşuyor…" gibi uzayan metinler + araya giren şerit satırı 723 px'e taşırıyor ve Test et
            modalın dışına kayıyordu. Evre başlıktaki şeritte; düğme kilitli + aria-busy. */}
        <div className="modal-actions">
          <Button variant="secondary" style={{ marginRight: 'auto' }} onClick={runTest}
            disabled={testing || !form.domain.trim()} aria-busy={testing || undefined}>
            <FlaskConical size={14} />{t('inv.test')}
          </Button>
          {/* Çalıştır ve Sil YALNIZ kayıtlı kayıtta: yeni/kopya modunda henüz ortada bir kayıt yok. */}
          {savedDomain && (
            <Button variant="secondary" onClick={runNow} disabled={running} aria-busy={running || undefined}
              title={t('inv.runTitle', savedDomain)}>
              <RefreshCw size={14} />{t('inv.run')}
            </Button>
          )}
          {savedDomain && canDelete && (
            <Button variant="destructive" onClick={del} disabled={deleting}>
              <Trash2 size={14} />{t('inv.delete')}
            </Button>
          )}
          <Button variant="secondary" onClick={onClose} disabled={saving || firstRun}>{t('inv.cancel')}</Button>
          <Button onClick={save} aria-busy={(saving || firstRun) || undefined}
            disabled={saving || firstRun || !form.domain.trim() || !form.team_id}>
            {t('inv.save')}
          </Button>
        </div>
      </div>
      {/* Tanılama YAZILAN değerle koşar — kaydetmeden deneme. */}
      {showDiag && (
        <DiagnosticsModal domain={form.domain.trim()} port={Number(form.port) || 443}
          onClose={() => setShowDiag(false)} />
      )}
    </div>
  )
}

/**
 * Domain'den kaydı çözen sarmalayıcı — dashboard kartında envanter ID'si YOK (sertifikalar
 * domain-anahtarlı). Kayıt bulunamazsa (silinmiş / yetki kapsamı dışı) BOŞ FORM AÇILMAZ:
 * kullanıcı doldurup kaydeder ve mükerrer bir envanter kaydı doğardı.
 *
 * Yetki propları (R4, 2026-09-25): eskiden hiçbiri geçmiyordu, formun `canManage=true` varsayılanı yüzünden
 * pano yolunda HERKES için takım kutusu açıktı. Burada varsayılanlar KAPALI; `canWrite` verilmezse matristen
 * (inventory.crud/edit) okunur — sarmalayıcı PermissionsProvider'ın içinde çizilir, App gövdesi değil.
 */
export function InventoryFormModalForDomain({ domain, mode = 'edit', onClose, onSaved, focus = null,
                                              canManage = false, canWrite, canMoveTeam = false }) {
  const t = useT()
  const toast = useToast()
  const matrixCanWrite = usePermissions().canEdit('inventory.crud')
  const [record, setRecord] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let alive = true
    setLoading(true)
    api.admin.getInventoryByDomain(domain)
      .then(res => {
        if (!alive) return
        if (res?.success && res.data) { setRecord(res.data); setLoading(false) }
        else { toast.error(t('inv.notFoundForDomain', domain)); onClose?.() }
      })
      .catch(() => { if (alive) { toast.error(t('inv.loadError')); onClose?.() } })
    return () => { alive = false }
  }, [domain])   // eslint-disable-line react-hooks/exhaustive-deps

  if (loading || !record) {
    return (
      <div className="modal-overlay">
        <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
          <LoadingBlock label={t('modal.loading')} fullWidth />
        </div>
      </div>
    )
  }
  return <InventoryFormModal mode={mode} record={record} onClose={onClose} onSaved={onSaved} focus={focus}
    canManage={canManage} canWrite={canWrite ?? matrixCanWrite} canMoveTeam={canMoveTeam} />
}
