import { useEffect, useId, useState } from 'react'
import { Plus, Trash2, FileCode2, ChevronDown } from 'lucide-react'
import { api } from '../../api/client'
import ModalShell from '../ui/ModalShell.jsx'
import CodeEditor from '../ui/CodeEditor.jsx'
import TagInput from '../ui/TagInput.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import { categoryOptions } from '../../utils/templateCategories.js'
import { pickLang } from '../../utils/scriptSourceOptions.js'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import { FormGrid, FormField, FormSection } from '../monitoring/MonitorForm.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { FieldDescription } from '@/components/shadcn/field'
import { Input } from '@/components/shadcn/input'
import { Label } from '@/components/shadcn/label'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { Textarea } from '@/components/shadcn/textarea'
import { cn } from '@/lib/utils'

/**
 * Şablon editörü — oluşturma, düzenleme ve salt-okunur görüntüleme TEK bileşende.
 *
 * <p><b>Neden monitör formunun EditModal'ı kopyalanmadı:</b> o form bir monitörü tanımlar
 * (aralık, timeout, alarm eşikleri, env DEĞERLERİ); şablon ise bir metindir — kapsamı, sürümü ve
 * env TANIMLARI vardır. Ortak olan tek şey kod editörü ve o zaten paylaşılan bir primitif.
 *
 * <p><b>Gizli değer taşınmaz.</b> Env satırında değer alanı YOKTUR; yalnız ad + "gizli mi"
 * bayrağı + açıklama saklanır. Backend `value` anahtarı taşıyan girdiyi gürültülü reddeder
 * (`ScriptedTemplateRules`), yani bu kural iki tarafta da yazılıdır: şablon kopyalanmak için
 * vardır, sızan bir değer N monitöre çoğalır ve kaynağı geriye izlenemez.
 *
 * <p><b>Liste satırı script GÖVDESİ taşımaz</b> (yanıt şişmesin) — düzenlemeye girerken tekil uç
 * çağrılır. Bu yüzden açılışta kısa bir yükleme durumu vardır.
 *
 * <p><b>İngilizce alanlar opsiyonel</b> (K6): kullanıcıya çeviri yükü bindirilmez, ama yerleşik
 * şablonlar iki dilli olduğu için alanlar katlanmış hâlde durur ve isteyen doldurur.
 */
export default function ScriptedTemplateEditor({
  t, lang = 'tr', k6Version, template, meta, teams = [], teamName, onClose, onSaved, readOnly = false,
}) {
  const isNew = !template?.id
  const [form, setForm] = useState(() => formFrom(template, meta, teams))
  const [loading, setLoading] = useState(!isNew)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [warnings, setWarnings] = useState([])
  const [showEn, setShowEn] = useState(false)

  // Düzenlemede gövdeyi tekil uçtan çek; kopyalama akışında gövde zaten elde (`template.script`).
  useEffect(() => {
    if (isNew) return
    let alive = true
    api.monitoring.getScriptedTemplate(template.id).then(r => {
      if (!alive) return
      if (r?.success) setForm(formFrom(r.data, meta, teams))
      else setError(r?.error || t('tpl.loadError'))
      setLoading(false)
    }).catch(e => { if (alive) { setError(String(e?.message || e)); setLoading(false) } })
    return () => { alive = false }
  }, [isNew, template?.id])   // eslint-disable-line react-hooks/exhaustive-deps

  const set = (patch) => setForm(f => ({ ...f, ...patch }))

  /**
   * GÖRÜNTÜLEME modunda metinler ARAYÜZ DİLİNDE gösterilir.
   *
   * <p>Şablonun veri modeli Türkçe-asıl + İngilizce-ikincil: düzenlerken ikisi de görünmeli,
   * çünkü kullanıcı ikisini de yazar. Ama "Görüntüle" bir OKUMA yüzeyi — İngilizce arayüzde
   * kartta İngilizce adı okuyup pencereyi açınca Türkçe metin görmek (2026-08-22 kullanıcı
   * bildirimi) tutarsızdı: liste zaten {@code pickLang} kullanıyordu, bu pencere kullanmıyordu.
   *
   * <p>Karşılığı olmayan alanda {@code pickLang} diğer dile düşer; o durumda alanın altına
   * "bu dilde çeviri yok" notu konur — yoksa kullanıcı düzeltmenin çalışmadığını sanır.
   */
  const en = lang === 'en'
  const show = (primary, alternate) => (readOnly ? pickLang(primary, alternate, lang) : primary)
  const missingTranslation = (primary, alternate) => readOnly && en && !alternate && !!primary
  /** Görüntülemede katlanan blok DİĞER dili taşır; düzenlemede her zaman İngilizce alanlardır. */
  const otherLangLabel = (key) => (readOnly && en ? t(`tpl.${key}Tr`) : t(`tpl.${key}En`))
  const otherLangValue = (primary, alternate) => (readOnly && en ? primary : alternate)
  const setEnvRow = (i, patch) => setForm(f => ({ ...f, env: f.env.map((e, x) => x === i ? { ...e, ...patch } : e) }))
  const addEnvRow = () => setForm(f => ({ ...f, env: [...f.env, { name: '', secret: false, desc: '' }] }))
  const delEnvRow = (i) => setForm(f => ({ ...f, env: f.env.filter((_, x) => x !== i) }))

  /** Kapsam seçenekleri SUNUCUDAN gelen yazılabilir takım kümesiyle sınırlıdır — UI tahmin etmez. */
  const scopeOptions = [
    ...(meta?.can_create_general ? [{ value: 'general', label: t('tpl.scopeGeneral') }] : []),
    ...teams
      .filter(tm => (meta?.writable_team_ids || []).some(id => String(id) === String(tm.id)))
      .map(tm => ({ value: String(tm.id), label: tm.name })),
  ]

  async function save() {
    setSaving(true); setError(null); setWarnings([])
    try {
      const payload = {
        name: form.name.trim(),
        nameEn: nullIfBlank(form.nameEn),
        description: nullIfBlank(form.description),
        descriptionEn: nullIfBlank(form.descriptionEn),
        whenToUse: nullIfBlank(form.whenToUse),
        whenToUseEn: nullIfBlank(form.whenToUseEn),
        script: form.script,
        tags: form.tags,
        // Boş seçim null gider: sunucu bilinmeyen/boş anahtarı zaten null'a düşürüyor.
        category: form.category === '' ? null : form.category,
        // DEĞER YOK: yalnız tanım. `value` anahtarı sunucuda reddedilir.
        env: form.env.filter(e => (e.name || '').trim())
          .map(e => ({ name: e.name.trim(), secret: !!e.secret, desc: nullIfBlank(e.desc) })),
      }
      if (isNew) {
        // `teamId: null` AÇIK bir Genel talebidir; sunucu bunu yalnız admin'e verir ve sessizce
        // takıma düşürmez. Bu yüzden anahtar her iki dalda da gönderilir.
        payload.teamId = form.scope === 'general' ? null : Number(form.scope)
      } else {
        payload.bumpType = form.bumpType
        if (form.restoredFrom) payload.restoredFrom = form.restoredFrom
      }
      const res = isNew ? await api.monitoring.createScriptedTemplate(payload)
                        : await api.monitoring.updateScriptedTemplate(template.id, payload)
      if (!res?.success) { setError(res?.error || t('tpl.saveError')); return }
      // Uyarılar KAYDETMEYİ engellemez ama modal kapanmadan gösterilir: kullanıcı isterse düzeltir.
      const warn = res.data?.warnings || []
      if (warn.length > 0) { setWarnings(warn); onSaved?.(res.data, { keepOpen: true }); return }
      onSaved?.(res.data)
    } finally {
      setSaving(false)
    }
  }

  const title = readOnly ? t('tpl.modalView') : isNew ? t('tpl.modalNew') : t('tpl.modalEdit')
  const canSave = !saving && !readOnly && form.name.trim() && form.script.trim() &&
    (!isNew || form.scope !== '')

  return (
    // dismissOnBackdrop={false}: bu formda script + ad + kapsam + env satırları birikiyor ve
    // kenar boşluğuna kazara tıklamak hepsini geri dönülmez biçimde siliyordu (taslak yok).
    // Kapanış yalnız BİLİNÇLİ yollardan: İptal, X ve Escape.
    // scrollBody + footer: form kod editörüyle birlikte ekranı kolayca aşıyor; düğmeler gövdenin
    // içindeyken en alta düşüyor ve her kayıt için sonuna kadar kaydırmak gerekiyordu.
    <ModalShell open onClose={onClose} title={title} icon={FileCode2} size="lg" busy={saving}
      dismissOnBackdrop={false} scrollBody
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            {readOnly ? t('tpl.close') : t('tpl.cancel')}
          </Button>
          {!readOnly &&
            <Button onClick={save} disabled={!canSave} aria-busy={saving}>
              {saving ? '…' : t('tpl.save')}
            </Button>}
        </>
      }>
      {loading
        ? <LoadingBlock label={t('modal.loading')} />
        : (<>
          {error && <AlertBanner tone="danger" title={t('tpl.saveBlockedTitle')}>{error}</AlertBanner>}
          {warnings.length > 0 &&
            <AlertBanner tone="warning" title={t('tpl.saveWarnTitle')}>
              <ul className="m-0 list-disc pl-4">{warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
            </AlertBanner>}

          <FormGrid>
            <FormField full label={t('tpl.name')} required
              hint={missingTranslation(form.name, form.nameEn) ? t('tpl.noTranslation') : undefined}>
              {({ id, describedBy }) => (
                <Input id={id} aria-describedby={describedBy} value={show(form.name, form.nameEn)}
                  disabled={readOnly} autoFocus={!readOnly} onChange={e => set({ name: e.target.value })} />
              )}
            </FormField>

            <FormField full label={t('tpl.description')}
              hint={missingTranslation(form.description, form.descriptionEn) ? t('tpl.noTranslation') : undefined}>
              {({ id, describedBy }) => (
                <Textarea id={id} aria-describedby={describedBy} rows={2} className="min-h-[62px]"
                  value={show(form.description, form.descriptionEn)} disabled={readOnly}
                  onChange={e => set({ description: e.target.value })} />
              )}
            </FormField>

            <FormField full label={t('tpl.whenToUse')}
              hint={missingTranslation(form.whenToUse, form.whenToUseEn) ? t('tpl.noTranslation') : t('tpl.whenToUseHint')}>
              {({ id, describedBy }) => (
                <Textarea id={id} aria-describedby={describedBy} rows={2} className="min-h-[62px]"
                  value={show(form.whenToUse, form.whenToUseEn)} disabled={readOnly}
                  onChange={e => set({ whenToUse: e.target.value })} />
              )}
            </FormField>

            {/* Kapsam YALNIZ oluşturmada seçilir: sonrasında değişimi promote/demote yapar —
                aksi hâlde bir USER kendi şablonunu Genel'e taşıyıp yetki yükseltebilirdi. */}
            {isNew
              ? <FormField full label={t('tpl.scope')} required hint={t('tpl.scopeHint')}>
                  {({ id }) => (
                    <SearchableSelect id={id} value={form.scope} onChange={v => set({ scope: v })}
                      options={[{ value: '', label: t('tpl.scopePick') }, ...scopeOptions]} searchThreshold={4} />
                  )}
                </FormField>
              : <FormField full label={t('tpl.scope')}>
                  {({ id }) => (
                    <Input id={id} disabled value={template.scope === 'general'
                      ? t('tpl.scopeGeneral')
                      : (template.team_name || teamName || t('tpl.scopeTeam'))} />
                  )}
                </FormField>}

            {/* Kategori: kütüphanedeki ağaçta hangi dala düşeceğini belirler. Zorunlu DEĞİL —
                seçilmezse "Diğer" dalında görünür; kategori bir düzenleme kolaylığıdır. */}
            <FormField full label={t('tpl.category')} hint={t('tpl.categoryHint')}>
              {({ id }) => (
                <SearchableSelect id={id} value={form.category} onChange={v => set({ category: v })}
                  options={[{ value: '', label: t('tpl.categoryPick') }, ...categoryOptions(t)]}
                  searchThreshold={6} disabled={readOnly} />
              )}
            </FormField>

            <FormSection title={t('tpl.tags')}>
              <TagInput value={form.tags} onChange={v => set({ tags: v })} disabled={readOnly}
                placeholder={t('tpl.tagsPlaceholder')} />
            </FormSection>

            {/* Katlanan dil bloğu (shadcn Collapsible) — düzenlemede İNGİLİZCE alanlar (K6: kullanıcıya
                çeviri yükü bindirme), İngilizce arayüzde GÖRÜNTÜLERKEN ise Türkçe aslı. İçerik hiçbir modda
                kaybolmaz, yalnız hangisinin "asıl" hangisinin "diğer" olduğu yer değiştirir. */}
            <Collapsible open={showEn} onOpenChange={setShowEn}
              className="flex min-w-0 flex-col gap-3 rounded-lg border bg-muted/30 px-3.5 py-3 sm:col-span-2">
              <CollapsibleTrigger asChild>
                <Button type="button" variant="secondary" size="sm" className="self-start">
                  {readOnly && en
                    ? (showEn ? t('tpl.trHide') : t('tpl.trShow'))
                    : (showEn ? t('tpl.enHide') : t('tpl.enShow'))}
                  <ChevronDown aria-hidden="true" className={cn('transition-transform duration-150 motion-reduce:transition-none', showEn && 'rotate-180')} />
                </Button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <FormGrid>
                  <FormField full label={otherLangLabel('name')}>
                    {({ id }) => (
                      <Input id={id} value={otherLangValue(form.name, form.nameEn)} disabled={readOnly}
                        onChange={e => set({ nameEn: e.target.value })} />
                    )}
                  </FormField>
                  <FormField full label={otherLangLabel('description')}>
                    {({ id }) => (
                      <Textarea id={id} rows={2} className="min-h-[62px]" value={otherLangValue(form.description, form.descriptionEn)}
                        disabled={readOnly} onChange={e => set({ descriptionEn: e.target.value })} />
                    )}
                  </FormField>
                  <FormField full label={otherLangLabel('whenToUse')}>
                    {({ id }) => (
                      <Textarea id={id} rows={2} className="min-h-[62px]" value={otherLangValue(form.whenToUse, form.whenToUseEn)}
                        disabled={readOnly} onChange={e => set({ whenToUseEn: e.target.value })} />
                    )}
                  </FormField>
                </FormGrid>
              </CollapsibleContent>
            </Collapsible>

            <FormSection title={t('tpl.script')} required
              action={<>
                {k6Version && <Badge variant="outline" data-slot="k6-version" className="font-mono text-[11px]">k6 {k6Version}</Badge>}
                {/* Şablonun asıl işi kopyalanmak: salt-okunur görünümde de dursun. */}
                {(form.script || '').trim() &&
                  <CopyButton value={form.script} variant="secondary"
                    label={t('tpl.scriptCopy')} copiedLabel={t('tpl.scriptCopied')} />}
              </>}>
              <CodeEditor value={form.script} onChange={code => set({ script: code })}
                readOnly={readOnly} textareaId={`k6-template-${template?.id || 'new'}`}
                placeholder={t('tpl.scriptPlaceholder')} />
              <FieldDescription className="text-xs">{t('tpl.scriptHint')}</FieldDescription>
            </FormSection>

            {/* Env TANIMLARI — değer alanı bilinçli olarak YOK. */}
            <FormSection title={t('tpl.env')}>
              {form.env.length > 0 &&
                <div className="flex flex-col gap-2">
                  {form.env.map((e, i) => (
                    <EnvRow key={i} t={t} row={e} readOnly={readOnly}
                      onPatch={patch => setEnvRow(i, patch)} onDelete={() => delEnvRow(i)} />
                  ))}
                </div>}
              {!readOnly &&
                <Button type="button" variant="secondary" size="sm" className="self-start" onClick={addEnvRow}>
                  <Plus size={13} aria-hidden="true" /> {t('tpl.envAdd')}
                </Button>}
              <FieldDescription className="text-xs">{t('tpl.envHint')}</FieldDescription>
            </FormSection>

            {/* Sürüm artışı yalnız mevcut şablonda anlamlı (yeni kayıt daima 1.0.0 ile doğar). */}
            {!isNew && !readOnly && (
              <FormField label={t('tpl.bumpTitle')} hint={t('tpl.bumpHint')}>
                {({ id, describedBy }) => (
                  <NativeSelect id={id} aria-describedby={describedBy} value={form.bumpType}
                    onChange={e => set({ bumpType: e.target.value })}>
                    <NativeSelectOption value="patch">{t('tpl.bumpPatch')}</NativeSelectOption>
                    <NativeSelectOption value="minor">{t('tpl.bumpMinor')}</NativeSelectOption>
                    <NativeSelectOption value="major">{t('tpl.bumpMajor')}</NativeSelectOption>
                  </NativeSelect>
                )}
              </FormField>
            )}
          </FormGrid>
        </>)}
    </ModalShell>
  )
}

/**
 * Tek env TANIM satırı: ad + açıklama + "gizli" kutusu + sil. DEĞER alanı bilinçli olarak YOK.
 * shadcn Input / Checkbox + Label / ikon Button (ipucu Tooltip).
 */
function EnvRow({ t, row, readOnly, onPatch, onDelete }) {
  const secretId = useId()
  return (
    <div data-slot="env-row" className="flex items-center gap-2">
      <Input className="min-w-0 flex-1" placeholder={t('tpl.envName')} aria-label={t('tpl.envName')} value={row.name}
        disabled={readOnly} onChange={ev => onPatch({ name: ev.target.value })} />
      <Input className="min-w-0 flex-[2]" placeholder={t('tpl.envDesc')} aria-label={t('tpl.envDesc')} value={row.desc || ''}
        disabled={readOnly} onChange={ev => onPatch({ desc: ev.target.value })} />
      <div className="flex shrink-0 items-center gap-1.5">
        <Checkbox id={secretId} checked={!!row.secret} disabled={readOnly}
          onCheckedChange={v => onPatch({ secret: v === true })} />
        <Label htmlFor={secretId} className="text-xs font-semibold whitespace-nowrap text-muted-foreground">{t('tpl.envSecret')}</Label>
      </div>
      {!readOnly &&
        <SimpleTooltip content={t('tpl.delete')}>
          <Button type="button" variant="outline" size="icon-sm"
            className="shrink-0 hover:border-destructive hover:bg-destructive/10 hover:text-destructive"
            aria-label={t('a11y.rowAction', row.name || t('tpl.envName'), t('tpl.delete'))}
            onClick={onDelete}><Trash2 size={15} aria-hidden="true" /></Button>
        </SimpleTooltip>}
    </div>
  )
}

/** Sunucu satırı → form durumu. Kopyalama akışı da buradan geçer (`template.id` yoksa yeni kayıt). */
function formFrom(row, meta, teams) {
  const r = row || {}
  const writable = meta?.writable_team_ids || []
  // Yeni şablonda kapsam varsayılanı: yazılabilir TEK takım varsa o seçili gelir (tek tıkla kayıt).
  const onlyTeam = writable.length === 1 && teams.some(tm => String(tm.id) === String(writable[0]))
  return {
    name: r.name || '',
    nameEn: r.name_en || '',
    description: r.description || '',
    descriptionEn: r.description_en || '',
    whenToUse: r.when_to_use || '',
    whenToUseEn: r.when_to_use_en || '',
    script: r.script || '',
    tags: Array.isArray(r.tags) ? r.tags.join(', ') : (r.tags || ''),
    category: r.category || '',
    env: (r.env || []).map(e => ({ name: e.name || '', secret: !!e.secret, desc: e.desc || '' })),
    scope: r.id ? (r.team_id == null ? 'general' : String(r.team_id))
                : (onlyTeam ? String(writable[0]) : ''),
    bumpType: 'patch',
    restoredFrom: r._restoredFrom || null,
  }
}

function nullIfBlank(s) {
  const v = (s || '').trim()
  return v === '' ? null : v
}
