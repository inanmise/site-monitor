import { useState, useEffect } from 'react'
import { SlidersHorizontal } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import Field from '../ui/Field.jsx'
import { SETTINGS_STACK, helpLabel, SettingsHeader, SettingsSaveBar, SettingsSection } from './SettingsControls.jsx'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Switch } from '@/components/shadcn/switch'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'

/**
 * Genel Ayarlar — küratörlü, tipli proje config'leri (key/value). Backend kataloğundan
 * gruplu yüklenir; değişiklik kaydedilince CANLI yansır (yeniden başlatma gerekmez).
 * Yalnız değiştirilen key'ler gönderilir; boş bırakmak override'ı kaldırır (varsayılana döner).
 * SMTP/LDAP deseniyle aynı shadcn dili: SettingsHeader + SettingsSection (Card) + ui/Field + alt kayıt çubuğu
 * (kirli = dokunulan anahtar var; Vazgeç dokunulanları atar).
 */
export default function GeneralSettings({ focusKey = null }) {
  const t = useT()
  const toast = useToast()

  const [items, setItems] = useState(null)   // backend kataloğu
  // Yapılandırma sağlığı kartından gelen alan (ISSUE-012): liste yüklenince kaydır, odakla, 2 sn vurgula.
  useEffect(() => {
    if (!focusKey?.key || !items) return
    const el = document.querySelector(`[data-setting-key="${focusKey.key}"]`)
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.setAttribute('data-focus-target', '')
    // Odaklanacak kontrol: alan, SearchableSelect tetiği (shadcn Button, combobox) ya da shadcn Switch.
    el.querySelector('input, select, textarea, button[aria-haspopup], [data-slot="switch"]')?.focus?.({ preventScroll: true })
    const id = setTimeout(() => el.removeAttribute('data-focus-target'), 2000)
    return () => clearTimeout(id)
  }, [focusKey, items])
  const [loadError, setLoadError] = useState(null)
  const [edited, setEdited] = useState({})    // yalnız dokunulan key'ler
  const [saving, setSaving] = useState(false)

  useEffect(() => { load() }, [])

  async function load() {
    // AG HATASI DA GORUNUR OLMALI: api/client.js request() ag hatasinda {success:false}
    // DONDURMEZ, throw eder. try/catch olmadan promise reject oluyor ve ekran sonsuza
    // kadar yukleniyor durumunda kaliyordu (yalnizca konsolda unhandled rejection).
    try {
      const res = await api.admin.getGeneralSettings()
      if (res?.success) { setItems(res.data || []); setEdited({}); setLoadError(null) }
      else {
        const msg = res?.error || t('settings.loadError')
        toast.error(msg); setLoadError(msg)
      }
    } catch (e) {
      setLoadError(e?.message || t('settings.loadError'))
    }
  }

  function set(key, val) { setEdited((e) => ({ ...e, [key]: val })) }
  function valueOf(it) { return edited[it.key] ?? (it.value ?? '') }

  async function save() {
    if (Object.keys(edited).length === 0) { toast.success(t('settings.saved')); return }
    setSaving(true)
    try {
      const res = await api.admin.saveGeneralSettings({ values: edited })
      if (res?.success) {
        toast.success(res.message || t('settings.saved'))
        setItems(res.data || [])
        setEdited({})
      } else {
        toast.error(res?.error || t('settings.saveError'))
      }
    } finally {
      setSaving(false)
    }
  }

  function renderInput(it, { id, describedBy }) {
    const v = valueOf(it)
    // read_only: sunucu, kapsamlı müdür (AD ADMIN) için GLOBAL_ONLY kalemleri işaretler — kilit
    // yalnız görsel; AppSettingsService.save aynı anahtarı 403 ile reddeder.
    const ro = !!it.read_only
    if (it.type === 'BOOL') {
      const on = String(v) === 'true'
      return (
        <div className="flex items-center gap-2">
          <Switch id={id} aria-describedby={describedBy} checked={on} disabled={ro}
            onCheckedChange={(c) => set(it.key, c ? 'true' : 'false')} />
          <span className="text-sm">{on ? t('general.on') : t('general.off')}</span>
        </div>
      )
    }
    if (it.type === 'ENUM') {
      return (
        <NativeSelect id={id} aria-describedby={describedBy} value={v} disabled={ro} onChange={(e) => set(it.key, e.target.value)}>
          {(it.options || []).map((o) => <NativeSelectOption key={o} value={o}>{o}</NativeSelectOption>)}
        </NativeSelect>
      )
    }
    if (it.type === 'TEXT') {
      return (
        <Textarea id={id} aria-describedby={describedBy} value={v} rows={8} spellCheck={false} disabled={ro}
          className="resize-y font-mono" onChange={(e) => set(it.key, e.target.value)} />
      )
    }
    const numeric = it.type === 'INT' || it.type === 'DOUBLE'
    return (
      <Input id={id} aria-describedby={describedBy} type={numeric ? 'number' : 'text'} value={v} disabled={ro}
        step={it.type === 'DOUBLE' ? '0.01' : undefined}
        onChange={(e) => set(it.key, e.target.value)} />
    )
  }

  if (!items) {
    // Yukleme BASARISIZ olduysa spinner sonsuza kadar donerdi: load() try/catch tasimadigi
    // icin ag hatasinda promise reject oluyor, hicbir durum guncellenmiyordu. Artik ayni
    // yerde hatanin KENDISI gosteriliyor (SystemHealth.jsx:163 loadErrors deseninin esdegeri).
    if (loadError) {
      return <AlertBanner tone="danger" title={t('settings.loadError')} role="alert">{String(loadError)}</AlertBanner>
    }
    return <LoadingBlock label={t('settings.loading')} className="justify-start px-0 py-6" />
  }

  // Grupları ilk görülme sırasına göre koru. branding ve retention'ın KENDİ sayfaları var —
  // burada göstermek çevrilmemiş ham anahtar adları üretiyordu (retention: 7 satır, 2026-08).
  // KENDI sayfasi olan gruplar burada GOSTERILMEZ: ayni ayar icin ikinci bir yuzey,
  // sifreli blob'lari duz metin kutusunda bozulmaya acar ve etiketsiz ham anahtar dizer.
  const SKIP_GROUPS = new Set(['branding', 'retention', 'userpush', 'storm', 'login-anomaly'])
  const order = []
  const byGroup = {}
  for (const it of items) {
    // userpush: kendi ÖZEL sayfası var (Webhook Bildirimleri) — burada ham anahtar listesi
    // olarak İKİNCİ bir yönetim yüzeyi açmak şifreli headers blob'unu ve role-groups JSON'unu
    // düz metin kutusunda bozulmaya açardı.
    // storm ve login-anomaly de KENDI panellerine sahip (AdminSettings.jsx:110/112 →
    // StormSettings, LoginAnomalySettings). Atlama listesine girmedikleri icin burada
    // etiketsiz, ham anahtarli IKINCI bir yonetim yuzeyi aciliyorlardi — yukarida
    // retention icin anlatilan hatanin ta kendisi.
    if (SKIP_GROUPS.has(it.group)) continue
    if (!byGroup[it.group]) { byGroup[it.group] = []; order.push(it.group) }
    byGroup[it.group].push(it)
  }

  // APP_BASE_URL hâlâ localhost ise (sıfır kurulum) e-posta linkleri çalışmaz → uyar
  const baseItem = items.find((i) => i.key === 'site.monitor.app.base-url')
  const baseVal = baseItem ? (edited[baseItem.key] ?? baseItem.value ?? '') : ''
  const baseLocal = /localhost|127\.0\.0\.1/i.test(String(baseVal))
  const dirty = Object.keys(edited).length > 0

  return (
    <div className={SETTINGS_STACK} data-testid="general-settings">
      <SettingsHeader icon={SlidersHorizontal} title={t('general.title')} description={t('general.desc')} hint={t('general.liveHint')}>
        {baseLocal && <AlertBanner tone="warning" className="mb-0">{t('general.baseUrlWarn')}</AlertBanner>}
      </SettingsHeader>

      {order.map((g) => (
        // İsteğe bağlı grup açıklaması (general.grpDesc.<grup>) — useT eksik anahtarda anahtarın kendisini
        // döndürür; yalnız gerçekten tanımlı olanlar çizilir (executor gibi kural taşıyanlar).
        <SettingsSection key={g} title={t('general.grp.' + g)}
          description={t('general.grpDesc.' + g) !== 'general.grpDesc.' + g ? t('general.grpDesc.' + g) : null}
          contentClassName="grid grid-cols-1 gap-x-6 gap-y-1 xl:grid-cols-2">
          {byGroup[g].map((it) => (
            // Açıklama metni SAYFADA durmaz: 200+ ayarlı bu liste okunamaz hâle gelirdi. Anahtar
            // katalogtan türetilir — aynı metin özel sayfalarda da paylaşılır. `data-focus-target`:
            // yapılandırma sağlığı kartından gelinince 2 sn tam çerçeve vurgusu (sol şerit DEĞİL).
            // Uzun metin (TEXT) alanları geniş ekranda iki sütunu birden kaplar.
            <div key={it.key} data-setting-key={it.key}
              className={'min-w-0 rounded-lg transition-[outline-color] data-[focus-target]:outline-2 data-[focus-target]:outline-offset-[6px] data-[focus-target]:outline-primary'
                + (it.type === 'TEXT' ? ' xl:col-span-2' : '')}>
              <Field label={helpLabel(t('general.lbl.' + it.key), 'help.set.' + it.key)}
                className="mb-3 [&>[data-slot=native-select-wrapper]]:w-full"
                hint={<>
                  <code className="font-mono">{it.key}</code>
                  {it.default != null && it.default !== ''
                    ? ' · ' + t('general.defaultHint', it.default) : ''}
                  {it.read_only ? ' · ' + t('general.globalOnlyHint') : ''}
                </>}>
                {(bind) => renderInput(it, bind)}
              </Field>
            </div>
          ))}
        </SettingsSection>
      ))}

      <SettingsSaveBar dirty={dirty} saving={saving} onSave={save} onDiscard={() => setEdited({})} />
    </div>
  )
}
