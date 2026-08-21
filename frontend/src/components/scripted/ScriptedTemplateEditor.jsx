import { useEffect, useState } from 'react'
import { Plus, Trash2, FileCode2 } from 'lucide-react'
import { api } from '../../api/client'
import ModalShell from '../ui/ModalShell.jsx'
import CodeEditor from '../ui/CodeEditor.jsx'
import TagInput from '../ui/TagInput.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import CopyButton from '../ui/CopyButton.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'

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
  t, k6Version, template, meta, teams = [], teamName, onClose, onSaved, readOnly = false,
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
    const payload = {
      name: form.name.trim(),
      nameEn: nullIfBlank(form.nameEn),
      description: nullIfBlank(form.description),
      descriptionEn: nullIfBlank(form.descriptionEn),
      whenToUse: nullIfBlank(form.whenToUse),
      whenToUseEn: nullIfBlank(form.whenToUseEn),
      script: form.script,
      tags: form.tags,
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
    setSaving(false)
    if (!res?.success) { setError(res?.error || t('tpl.saveError')); return }
    // Uyarılar KAYDETMEYİ engellemez ama modal kapanmadan gösterilir: kullanıcı isterse düzeltir.
    const warn = res.data?.warnings || []
    if (warn.length > 0) { setWarnings(warn); onSaved?.(res.data, { keepOpen: true }); return }
    onSaved?.(res.data)
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
          <button className="btn btn-secondary" onClick={onClose}>
            {readOnly ? t('tpl.close') : t('tpl.cancel')}
          </button>
          {!readOnly &&
            <button className="btn btn-primary" onClick={save} disabled={!canSave} aria-busy={saving}>
              {saving ? '…' : t('tpl.save')}
            </button>}
        </>
      }>
      {loading
        ? <LoadingBlock label={t('modal.loading')} />
        : (<>
          {error && <AlertBanner tone="danger" title={t('tpl.saveBlockedTitle')}>{error}</AlertBanner>}
          {warnings.length > 0 &&
            <AlertBanner tone="warning" title={t('tpl.saveWarnTitle')}>
              <ul className="sc-warn-list">{warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
            </AlertBanner>}

          <div className="form-grid form-grid--top">
            <label className="full-width"><span>{t('tpl.name')} <span className="req-star">*</span></span>
              <input value={form.name} disabled={readOnly} autoFocus={!readOnly}
                onChange={e => set({ name: e.target.value })} /></label>

            <label className="full-width"><span>{t('tpl.description')}</span>
              <textarea rows={2} value={form.description} disabled={readOnly}
                onChange={e => set({ description: e.target.value })} /></label>

            <label className="full-width"><span>{t('tpl.whenToUse')}</span>
              <textarea rows={2} value={form.whenToUse} disabled={readOnly}
                onChange={e => set({ whenToUse: e.target.value })} />
              <span className="field-hint">{t('tpl.whenToUseHint')}</span></label>

            {/* Kapsam YALNIZ oluşturmada seçilir: sonrasında değişimi promote/demote yapar —
                aksi hâlde bir USER kendi şablonunu Genel'e taşıyıp yetki yükseltebilirdi. */}
            {isNew
              ? <label className="full-width"><span>{t('tpl.scope')} <span className="req-star">*</span></span>
                  <SearchableSelect value={form.scope} onChange={v => set({ scope: v })}
                    options={[{ value: '', label: t('tpl.scopePick') }, ...scopeOptions]} searchThreshold={4} />
                  <span className="field-hint">{t('tpl.scopeHint')}</span></label>
              : <label className="full-width"><span>{t('tpl.scope')}</span>
                  <input disabled value={template.scope === 'general'
                    ? t('tpl.scopeGeneral')
                    : (template.team_name || teamName || t('tpl.scopeTeam'))} /></label>}

            <div className="full-width sc-tpl-block">
              <div className="kw-block-title">{t('tpl.tags')}</div>
              <TagInput value={form.tags} onChange={v => set({ tags: v })} disabled={readOnly}
                placeholder={t('tpl.tagsPlaceholder')} />
            </div>

            {/* İngilizce alanlar — katlanmış (K6: kullanıcıya çeviri yükü bindirme). */}
            <div className="full-width sc-tpl-block">
              <button type="button" className="btn btn-sm btn-secondary" onClick={() => setShowEn(v => !v)}>
                {showEn ? t('tpl.enHide') : t('tpl.enShow')}
              </button>
              {showEn && (
                <div className="form-grid form-grid--top sc-tpl-en">
                  <label className="full-width"><span>{t('tpl.nameEn')}</span>
                    <input value={form.nameEn} disabled={readOnly}
                      onChange={e => set({ nameEn: e.target.value })} /></label>
                  <label className="full-width"><span>{t('tpl.descriptionEn')}</span>
                    <textarea rows={2} value={form.descriptionEn} disabled={readOnly}
                      onChange={e => set({ descriptionEn: e.target.value })} /></label>
                  <label className="full-width"><span>{t('tpl.whenToUseEn')}</span>
                    <textarea rows={2} value={form.whenToUseEn} disabled={readOnly}
                      onChange={e => set({ whenToUseEn: e.target.value })} /></label>
                </div>
              )}
            </div>

            <div className="full-width sc-tpl-block">
              <div className="kw-block-title sc-script-title">
                <span>{t('tpl.script')} <span className="req-star">*</span></span>
                <span className="sc-script-tools">
                  {k6Version && <span className="sc-k6ver-chip">k6 {k6Version}</span>}
                  {/* Şablonun asıl işi kopyalanmak: salt-okunur görünümde de dursun. */}
                  {(form.script || '').trim() &&
                    <CopyButton value={form.script} className="btn btn-sm btn-secondary"
                      label={t('tpl.scriptCopy')} copiedLabel={t('tpl.scriptCopied')} />}
                </span>
              </div>
              <CodeEditor value={form.script} onChange={code => set({ script: code })}
                readOnly={readOnly} textareaId={`k6-template-${template?.id || 'new'}`}
                placeholder={t('tpl.scriptPlaceholder')} />
              <span className="field-hint">{t('tpl.scriptHint')}</span>
            </div>

            {/* Env TANIMLARI — değer alanı bilinçli olarak YOK. */}
            <div className="full-width sc-tpl-block">
              <div className="kw-block-title">{t('tpl.env')}</div>
              {form.env.length > 0 &&
                <div className="env-list">
                  {form.env.map((e, i) => (
                    <div key={i} className="env-row sc-tpl-env-row">
                      <input className="input env-name" placeholder={t('tpl.envName')} value={e.name}
                        disabled={readOnly} onChange={ev => setEnvRow(i, { name: ev.target.value })} />
                      <input className="input sc-tpl-env-desc" placeholder={t('tpl.envDesc')} value={e.desc || ''}
                        disabled={readOnly} onChange={ev => setEnvRow(i, { desc: ev.target.value })} />
                      <label className="checkbox-label env-secret" title={t('tpl.envSecret')}>
                        <input type="checkbox" checked={!!e.secret} disabled={readOnly}
                          onChange={ev => setEnvRow(i, { secret: ev.target.checked })} />
                        {t('tpl.envSecret')}
                      </label>
                      {!readOnly &&
                        <button type="button" className="icon-btn env-del" title={t('tpl.delete')}
                          onClick={() => delEnvRow(i)}><Trash2 size={15} /></button>}
                    </div>
                  ))}
                </div>}
              {!readOnly &&
                <button type="button" className="btn btn-secondary btn-sm env-add" onClick={addEnvRow}>
                  <Plus size={13} /> {t('tpl.envAdd')}
                </button>}
              <span className="field-hint">{t('tpl.envHint')}</span>
            </div>

            {/* Sürüm artışı yalnız mevcut şablonda anlamlı (yeni kayıt daima 1.0.0 ile doğar). */}
            {!isNew && !readOnly && (
              <label><span>{t('tpl.bumpTitle')}</span>
                <select value={form.bumpType} onChange={e => set({ bumpType: e.target.value })}>
                  <option value="patch">{t('tpl.bumpPatch')}</option>
                  <option value="minor">{t('tpl.bumpMinor')}</option>
                  <option value="major">{t('tpl.bumpMajor')}</option>
                </select>
                <span className="field-hint">{t('tpl.bumpHint')}</span></label>
            )}
          </div>
        </>)}
    </ModalShell>
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
