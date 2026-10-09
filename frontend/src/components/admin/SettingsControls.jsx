import { useId } from 'react'
import { Check, Save, Undo2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import HelpTip from '../ui/HelpTip.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { Button } from '@/components/shadcn/button'
import { Label } from '@/components/shadcn/label'
import { Switch } from '@/components/shadcn/switch'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'

/**
 * Ayar formlarının (SMTP / LDAP / Push / Genel …) ortak shadcn parçaları. Eski `.threshold-grid` /
 * `.threshold-field` / `.ldap-toggle` kalıplarının yerine geçer: alanlar ui/Field, aç/kapa
 * ayarları shadcn Switch. Legacy sınıf YOK — `.threshold-field input` gibi katmansız App.css
 * kuralları shadcn Input'un görünümünü ezerdi.
 *
 * 2026-09-27 (tam sayfa yeniden tasarım): her bölüm AYNI iskeleti kullanır —
 *   SettingsHeader (ikon + başlık + amaç + meta) → kartlar (SettingsSection) → SettingsSaveBar
 * (kirli durumda yapışkan "Kaydedilmemiş değişiklikler · Vazgeç / Kaydet"). Genişlik tavanı yok:
 * bölüm kökü `SETTINGS_STACK` ile kalan alanın tamamını kullanır.
 */

/** Bölüm kökü — kartlar alt alta, tam genişlik (eski `max-w-[920px]` tavanı kalktı, kullanıcı isteği 2026-09-27). */
export const SETTINGS_STACK = 'flex min-w-0 w-full flex-col gap-5'

/** Alan ızgarası — telefonda tek sütun, ≥640 px'te iki sütun (kısa alan çiftleri: sunucu/port, kullanıcı/parola). */
export const FIELD_GRID = 'grid grid-cols-1 gap-x-4 sm:grid-cols-2'
/** Üç sütunlu ızgara (≥1280 px) — çok sayıda kısa sayısal alan (zaman aşımı, eşik listesi). */
export const FIELD_GRID_3 = 'grid grid-cols-1 gap-x-4 sm:grid-cols-2 xl:grid-cols-3'

/** Etiket + yardım balonu (HelpTip etiketin içinde; tıklaması etiket davranışını keser). */
export function helpLabel(text, helpKey) {
  return <>{text}<HelpTip helpKey={helpKey} label={text} /></>
}

/**
 * Bölüm başlığı — ikon kutusu + başlık (h3 düzeyi) + tek satır amaç + isteğe bağlı ipucu / meta çipleri / eylemler.
 * ui/PageHeader'ın bölüm ölçeğindeki karşılığı: sayfa başlığı h2 (kabuk), bölüm h3, kart başlıkları h4.
 * Test kancaları: data-slot="settings-header|settings-title|settings-description|settings-meta|settings-actions".
 */
export function SettingsHeader({ icon: Icon, title, description, hint, meta, actions, level = 3, className = '', rowClassName = '', children, ...rest }) {
  // rowClassName (isteğe bağlı, 2026-10-09 Veritabanı Bilgileri): başlık + eylem satırının yan yana geçtiği eşiği çağıran
  // belirler (ör. kap sorgusu `sm:flex-col @2xl/x:flex-row` — tablette kenar çubuğu açıkken içerik ~400 px kalıyordu).
  // Verilmezse görünüm aynı.
  return (
    <header data-slot="settings-header" className={cn('flex min-w-0 flex-col gap-3', className)} {...rest}>
      <div className={cn('flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:gap-4', rowClassName)}>
        <div className="flex min-w-0 items-start gap-3">
          {Icon && (
            <span aria-hidden="true"
              className="hidden size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary sm:inline-flex">
              <Icon className="size-[18px]" />
            </span>
          )}
          <div className="flex min-w-0 flex-col gap-1">
            <div role="heading" aria-level={level} data-slot="settings-title"
              className="flex flex-wrap items-center gap-2 text-lg leading-tight font-semibold tracking-tight">
              {title}
            </div>
            {description && <p data-slot="settings-description" className="max-w-[78ch] text-sm text-muted-foreground">{description}</p>}
            {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
            {meta && <div data-slot="settings-meta" className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">{meta}</div>}
          </div>
        </div>
        {actions && (
          <div data-slot="settings-actions" className="flex flex-wrap items-center gap-2 sm:shrink-0 sm:justify-end">{actions}</div>
        )}
      </div>
      {children}
    </header>
  )
}

/**
 * Ayar bölümü — shadcn Card. Başlık (h4 düzeyinde) + açıklama CardHeader'da, alanlar CardContent'te.
 * Eski `.admin-section` + `.ldap-subhdr` + `.section-desc` üçlüsünün yerine.
 */
export function SettingsSection({ title, description, level = 4, className = '', contentClassName = '', children }) {
  return (
    <Card className={className}>
      {(title || description) && (
        <CardHeader className="border-b [.border-b]:pb-4 border-border px-4 sm:px-6">
          {title && <CardTitle role="heading" aria-level={level}>{title}</CardTitle>}
          {description && <CardDescription>{description}</CardDescription>}
        </CardHeader>
      )}
      {/* Telefonda daha dar yan boşluk (mweb 2026-09-26): iç içe kartlarda içerik sıkışmasın */}
      <CardContent className={cn('px-4 sm:px-6', contentClassName)}>{children}</CardContent>
    </Card>
  )
}

/**
 * Ana anahtar kartı — bölümün "açık/kapalı" anahtarı + kısa açıklama + (kapalıyken) uyarı. Her bölümde aynı
 * görünüm: SMTP, LDAP, Storm, Login Anomali, Haftalık E-posta, Envanter Raporu.
 */
export function MasterToggleCard({ checked, onChange, label, helpKey, hint, disabled = false, touch = false, children, className = '' }) {
  return (
    <Card data-slot="master-toggle" className={cn('gap-2 py-4', className)}>
      <CardContent className="flex flex-col gap-2 px-4 sm:px-6">
        <ToggleRow major checked={checked} onChange={onChange} label={label} helpKey={helpKey} disabled={disabled} touch={touch} />
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        {children}
      </CardContent>
    </Card>
  )
}

/**
 * Kaydet çubuğu — bölümün altında TEK eylem satırı. `dirty` boolean verilirse durum İZLENİR: temizken "kaydedildi"
 * + pasif Kaydet; değişiklik varken yapışkan, vurgulu şerit + Vazgeç + etkin Kaydet (UserPush/Veri Saklama deseni,
 * telefonda güvenli alan payı). `dirty` verilmezse yalnız Kaydet (+ `children` ek düğmeler, ör. "Bağlantıyı test et").
 * Test kancası: data-slot="settings-save-bar" + data-dirty.
 * `dirtyLabel` (isteğe bağlı, 2026-09-28 Login Anomali): kirliyken genel "Kaydedilmemiş değişiklikler" yerine sayılı
 * metin ("3 kaydedilmemiş değişiklik"); verilmezse davranış aynı.
 */
export function SettingsSaveBar({
  dirty, saving = false, onSave, onDiscard, saveLabel, disabled = false, children, result = null, className = '', dirtyLabel,
}) {
  const t = useT()
  const tracked = typeof dirty === 'boolean'
  const stuck = tracked && dirty
  const unsavedText = dirtyLabel || t('settings.unsaved')
  return (
    <div data-slot="settings-save-bar" data-dirty={dirty ? 'true' : undefined}
      role={tracked ? 'region' : undefined}
      aria-label={tracked ? (dirty ? unsavedText : t('settings.allSaved')) : undefined}
      className={cn('flex flex-wrap items-center gap-2.5 rounded-xl border bg-card px-4 py-3 shadow-xs',
        stuck && 'sticky bottom-0 z-[60] border-primary bg-card/95 pb-[max(0.75rem,env(safe-area-inset-bottom))] shadow-[0_-4px_18px_rgba(0,0,0,.10)] backdrop-blur',
        className)}>
      {tracked && (
        <span className={cn('mr-auto inline-flex items-center gap-2 text-[13px] font-semibold', dirty ? 'text-foreground' : 'text-muted-foreground')}>
          {dirty
            ? <span aria-hidden="true" className="size-2.5 rounded-full bg-amber-500 ring-3 ring-amber-500/25" />
            : <Check size={15} aria-hidden="true" />}
          {dirty ? unsavedText : t('settings.allSaved')}
        </span>
      )}
      {children}
      {stuck && onDiscard && (
        <Button type="button" variant="outline" onClick={onDiscard} disabled={saving} className="pointer-coarse:h-10">
          <Undo2 size={15} aria-hidden="true" /> {t('settings.discard')}
        </Button>
      )}
      <Button type="button" onClick={onSave} disabled={disabled || saving || (tracked && !dirty)} aria-busy={saving || undefined} className="pointer-coarse:h-10">
        {saving ? <Spinner size={15} inline decorative /> : <Save size={15} aria-hidden="true" />}
        {' '}{saving ? t('settings.saving') : (saveLabel || t('settings.save'))}
      </Button>
      {/* Test sonucu vb. — düğmelerin ALTINDA tam satır (TestResult `basis-full`) */}
      {result}
    </div>
  )
}

/**
 * Test sonucu (bağlantı testi, test gönderimi) — AlertBanner (başarı yeşil, hata kırmızı) + sonucu panoya kopyala.
 * Kök `data-slot="test-result"` + `data-tone` (test kancası); kaydet çubuğunda tam satır kaplar (`basis-full`).
 */
export function TestResult({ ok, children, className = '', copyClassName }) {
  const t = useT()
  const text = typeof children === 'string' ? children : null
  return (
    <div data-slot="test-result" data-tone={ok ? 'success' : 'danger'} className={cn('min-w-0 basis-full', className)}>
      <AlertBanner tone={ok ? 'success' : 'danger'} className="mb-0"
        actions={text ? (
          // copyClassName (isteğe bağlı, 2026-09-28): ör. dokunmatikte 40 px hedef — verilmezse görünüm aynı
          <CopyButton value={text} variant="ghost" buttonSize="icon-sm" className={copyClassName} label={t('settings.copyResult')} copiedLabel={t('err.copied')} />
        ) : undefined}>
        {children}
      </AlertBanner>
    </div>
  )
}

/** Form seçeneği (Kaydet'le gider) — shadcn Checkbox + bağlı etiket. */
export function CheckboxRow({ checked, onChange, label, disabled = false, className = '' }) {
  const id = useId()
  return (
    <div className={cn('flex items-center gap-2', className)}>
      <Checkbox id={id} checked={checked} disabled={disabled} onCheckedChange={(v) => onChange(v === true)} />
      <Label htmlFor={id} className="cursor-pointer font-normal">{label}</Label>
    </div>
  )
}

/**
 * Aç/kapa ayarı — shadcn Switch + bağlı etiket + yardım balonu.
 * `touch` (isteğe bağlı, 2026-09-28): telefonda / dokunmatikte satır en az 40 px, etiket satır boyu uzanır — küçük
 * anahtarın yanında etiket de tam yükseklikte dokunma hedefi olur. Verilmezse görünüm aynı.
 */
export function ToggleRow({ checked, onChange, label, helpKey, major = false, disabled = false, touch = false, className = '' }) {
  const id = useId()
  return (
    <div className={cn('inline-flex items-center gap-2', touch && 'max-sm:min-h-10 pointer-coarse:min-h-10', className)}>
      <Switch id={id} checked={checked} onCheckedChange={onChange} disabled={disabled} />
      <Label htmlFor={id} className={cn('cursor-pointer', major ? 'text-base font-bold' : 'font-normal', touch && 'self-stretch')}>{label}</Label>
      {helpKey && <HelpTip helpKey={helpKey} label={label} />}
    </div>
  )
}
