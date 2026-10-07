import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Eye, FileSearch, FileUp, Pencil, RotateCcw, Upload } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { Spinner } from '../ui/Progress.jsx'
import FileStep from './wizard/FileStep.jsx'
import ReviewStep from './wizard/ReviewStep.jsx'
import TrackStep from './wizard/TrackStep.jsx'
import ResultStep from './wizard/ResultStep.jsx'
import {
  EMPTY_TRACKING, MAX_UPLOAD_BYTES, MAX_UPLOAD_MB, NOTE_MAX, compareWithCurrent, defaultRef, extractedFormData, extractionErrorKey,
  inventoryPayload, splitServerErrors, trackingErrors, trackingKeyError,
} from './manualCertModel.js'
import { extractCertificates } from './extract/index.js'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'

/** Telefonda tam ekran (tanılama pencereleriyle aynı). */
const PHONE_FULLSCREEN = 'max-sm:top-0 max-sm:left-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-full max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0 max-sm:px-4 max-sm:pt-4 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]'
const STEPS = ['file', 'review', 'track', 'result']
const NAV_BTN = 'max-sm:h-10 max-sm:flex-1 pointer-coarse:h-10'
/** Takip adımında alanı olan sunucu hata anahtarları (diğerleri pencere bandında gösterilir). */
const TRACK_FIELDS = new Set(['domain', 'team_id', 'group_name', 'tags', 'note'])
/** Dosya düzeyi şifre uyarıları (JKS/JCEKS/BKS'de sertifikalar yine okunur). */
const PASSWORD_CODES = new Set(['PASSWORD_WRONG', 'PASSWORD_REQUIRED'])

/** Adım göstergesi — sıralı liste, etkin adım `aria-current="step"`; telefonda yalnız numara + etkin adın adı. */
function StepIndicator({ step }) {
  const t = useT()
  const idx = STEPS.indexOf(step)
  return (
    <ol data-slot="mcert-steps" aria-label={t('mcert.steps')} className="m-0 mb-4 flex list-none items-center gap-1.5 p-0 sm:gap-2">
      {STEPS.map((s, i) => {
        const state = i < idx ? 'done' : i === idx ? 'current' : 'todo'
        return (
          <li key={s} data-step={s} data-state={state} aria-current={state === 'current' ? 'step' : undefined}
            className={cn('flex min-w-0 items-center gap-1.5 text-xs', state === 'current' ? 'flex-1 font-semibold text-foreground sm:flex-none' : 'text-muted-foreground')}>
            <span aria-hidden="true" className={cn('flex size-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold',
              state === 'current' ? 'bg-primary text-primary-foreground' : state === 'done' ? 'bg-success text-white' : 'bg-muted text-muted-foreground')}>{i + 1}</span>
            <span className={cn('min-w-0 truncate', state !== 'current' && 'max-sm:sr-only')}>{t(`mcert.step.${s}`)}</span>
            {i < STEPS.length - 1 && <span aria-hidden="true" className="ml-0.5 hidden h-px w-6 bg-border sm:block" />}
          </li>
        )
      })}
    </ol>
  )
}

/**
 * MANUEL SERTİFİKA YÜKLEME SİHİRBAZI (2026-10-06). Dört adım: Dosya → İnceleme → Takip → Sonuç. Hiçbir adım kendiliğinden
 * gönderilmez; her geçiş kullanıcının düğmesiyle. Pencere ui/ModalShell (telefonda tam ekran), örtü tıklaması KAPATMAZ
 * (emek birikiyor), gönderim sürerken kapatma kapalı.
 *
 * <p>Akış: dosya / metin (+ şifre) → `POST /manual-certs/analyze` (yazmaz) → girdi seçimi (tek / çoklu) → kip: yeni kayıt
 * (takip adı + envanter alanları) ya da mevcut kaydı yenile (yeni sürüm, karşılaştırma, eski bitişte onay) → oluştur /
 * toplu oluştur / yeni sürüm. 400 alan hataları alanların altına, 409 kodları açıklamalı bant ya da alan hatası olur.
 * Şifre yalnız bu bileşenin belleğinde, pencere kapanınca (bileşen sökülünce) gider.
 *
 * <p><b>Özel anahtar tarayıcıdan çıkmaz (2026-10-08, kullanıcı isteği):</b> "Analiz et" dosyayı / metni önce TARAYICIDA açar
 * (`extract/` — PKCS#12 / JKS / JCEKS / PEM / ZIP; mümkünse Web Worker'da). Sunucuya giden her yükleme isteği (analiz,
 * oluştur, toplu, yeni sürüm) yalnız `extracted` taşır: açık sertifikalar (Base64 DER) + CSR PEM + sayaçlar. Orijinal dosya,
 * yapıştırılan metin ve şifre hiçbir istekte yoktur. Şifre gerekli / yanlış, BKS, tanınmayan biçim → sunucuya hiç gitmeden
 * alanın altında hata. Tarayıcının notları (ZIP atlanan girdi, JKS bütünlük uyumsuzluğu …) sunucu uyarılarının önüne eklenir.
 *
 * @param {object}   [renewTarget]   `{ inventory_id, domain }` — satırın "Yeni sürüm yükle"sinden: kip sabit "yenile"
 * @param {Array}    [renewCandidates] liste satırları (yenilenebilecek manuel kayıtlar; `can_manage === false` hariç)
 * @param {Array}    [teams]         takım seçici (USER = üyesi olduğu takımlar)
 * @param {Function} [onDone]        (result) — başarıdan sonra (liste tazelensin)
 * @param {Function} [onOpenCert]    ({ domain, inventory_id }) — sertifika penceresini aç (pencere kapanır)
 * @param {Function} [onEditInventory] (domain) — oluşturulan kaydın envanter formu (ek alanlar)
 */
export default function UploadWizard({
  onClose, renewTarget = null, renewCandidates = [], teams = [], canOpenSettings = false, onDone, onOpenCert, onEditInventory,
}) {
  const t = useT()
  const [step, setStep] = useState('file')
  const [source, setSource] = useState('file')
  const [file, setFile] = useState(null)
  const [text, setText] = useState('')
  const [password, setPassword] = useState('')
  const [passwordNeeded, setPasswordNeeded] = useState(false)
  const [analysis, setAnalysis] = useState(null)
  // Tarayıcıdaki ayıklama sonucu (yalnız AÇIK sertifikalar + sayaçlar) — oluştur / toplu / yeni sürüm aynısını gönderir
  const [extraction, setExtraction] = useState(null)
  const [extractIssue, setExtractIssue] = useState(null)   // unsupported.reason (BKS → keytool yönergesi)
  const [phase, setPhase] = useState(null)                 // 'extract' | 'analyze' — meşgul metni
  const [busy, setBusy] = useState(false)
  const [banner, setBanner] = useState(null)   // { tone, title?, text, target? }
  const fe = useFormErrors(step)
  const [multi, setMulti] = useState(false)
  const [selected, setSelected] = useState(null)
  const [selectedSet, setSelectedSet] = useState(() => new Set())
  const [mode, setMode] = useState(renewTarget ? 'renew' : 'new')
  const [target, setTarget] = useState(renewTarget)
  const [current, setCurrent] = useState(null)   // { status: loading|ready|error, version }
  const [prefilled, setPrefilled] = useState(null)
  const [keyValue, setKeyValue] = useState('')
  const [batchKeys, setBatchKeys] = useState({})
  const [rowErrors, setRowErrors] = useState({})
  const [form, setForm] = useState(() => ({ ...EMPTY_TRACKING }))
  const [note, setNote] = useState('')
  const [confirmOlder, setConfirmOlder] = useState(false)
  // Sunucu OLDER_THAN_CURRENT dediyse (güncel sürüm okunamadığı için istemci karşılaştıramadıysa da) onay kutusu çıkar.
  const [serverOlder, setServerOlder] = useState(false)
  // Sunucu SAME_CERTIFICATE dediyse (istemci karşılaştıramadıysa da) "aynı sertifika" uyarısı + "Yine de yükle" (2026-10-07)
  const [serverSame, setServerSame] = useState(false)
  const [result, setResult] = useState(null)
  const renewFixed = !!renewTarget
  // Adım değişince kaydırılan gövde başa sarılır (önceki adımda aşağı kaydırılmış konum yeni adımın ortasından başlatmasın)
  const bodyRef = useRef(null)
  useEffect(() => { if (bodyRef.current) bodyRef.current.scrollTop = 0 }, [step])
  // İnceleme → "Şifreyi düzelt": Dosya adımına dönülür, şifre alanı hatasıyla işaretlenir (adım hataları sıfırlandıktan SONRA)
  const [pwFix, setPwFix] = useState(false)
  useEffect(() => {
    if (!pwFix || step !== 'file') return
    setPwFix(false)
    fe.check({ password: t('mcert.pw.wrong') })
  }, [pwFix, step]) // eslint-disable-line react-hooks/exhaustive-deps -- fe/t kararlı değil; yalnız istek + adım

  const entries = useMemo(() => (Array.isArray(analysis?.entries) ? analysis.entries : []), [analysis])
  const byRef = (ref) => entries.find((e) => e.ref === ref)
  const picked = useMemo(() => (multi ? entries.filter((e) => selectedSet.has(e.ref)) : entries.filter((e) => e.ref === selected)),
    [entries, multi, selected, selectedSet])

  // Yenileme hedefinin güncel sürümü (karşılaştırma için) — Takip adımına gelince okunur (dosya seçilmeden istek yok)
  const wantCurrent = step === 'track' && mode === 'renew' && target?.inventory_id != null
  useEffect(() => {
    if (!wantCurrent) { setCurrent(null); return undefined }
    let alive = true
    setCurrent({ status: 'loading' })
    Promise.resolve(api.manualCerts.get(target.inventory_id)).then((res) => {
      if (!alive) return
      const versions = Array.isArray(res?.data?.versions) ? res.data.versions : []
      const v = versions.find((x) => x.current) || versions[0] || null
      setCurrent(res?.success && v ? { status: 'ready', version: v } : { status: 'error' })
    }).catch(() => { if (alive) setCurrent({ status: 'error' }) })
    return () => { alive = false }
  }, [wantCurrent, target?.inventory_id])

  const cmp = useMemo(() => (mode === 'renew' && current?.status === 'ready' && picked[0]
    ? compareWithCurrent(current.version, picked[0]) : null), [mode, current, picked])
  useEffect(() => { setConfirmOlder(false); setServerOlder(false); setServerSame(false) }, [target?.inventory_id, selected])
  const needsConfirm = (!!cmp?.older && !cmp?.same) || serverOlder
  const sameCert = !!cmp?.same || serverSame

  /** Yenilenebilecek kayıtlar: seçilen girdinin aynı konu adaylarını önde, sonra yönetilebilen diğer manuel kayıtlar. */
  const renewOptions = useMemo(() => {
    const same = (picked[0]?.matches?.same_subject || []).map((s) => ({ ...s, same: true }))
    const seen = new Set(same.map((s) => String(s.inventory_id)))
    const others = (renewCandidates || []).filter((r) => r?.can_manage !== false && !seen.has(String(r.inventory_id)))
      .map((r) => ({ inventory_id: r.inventory_id, domain: r.domain, not_after: r.not_after, same: false }))
    return [...same, ...others]
  }, [picked, renewCandidates])

  async function analyze() {
    const errs = source === 'file'
      ? { file: !file ? t('mcert.file.required') : file.size > MAX_UPLOAD_BYTES ? t('mcert.file.tooLarge', MAX_UPLOAD_MB) : null }
      : { text: !text.trim() && t('mcert.text.required') }
    if (fe.check(errs)) return
    setBusy(true); setBanner(null); setExtractIssue(null); setPhase('extract')
    // 1) TARAYICIDA ayıkla — özel anahtar ve şifre buradan çıkmaz
    let ex = null
    try {
      ex = await extractCertificates(source === 'file' ? { file, password } : { text, password })
    } catch {
      ex = { unsupported: { reason: 'UNREADABLE' } }
    }
    if (!ex || ex.unsupported || ex.needs_password || ex.password_error) {
      setBusy(false); setPhase(null); setExtraction(null)
      if (ex?.needs_password || ex?.password_error) {
        setPasswordNeeded(true)
        fe.check({ password: ex.password_error ? t('mcert.pw.wrong') : t('mcert.pw.required') })
        return
      }
      const [key, arg] = extractionErrorKey(ex?.unsupported)
      setExtractIssue(ex?.unsupported?.reason || 'UNKNOWN')
      fe.check({ [source === 'file' ? 'file' : 'text']: t(key, arg) })
      return
    }
    setExtraction(ex)
    // 2) Sunucu analizi YALNIZ açık sertifikalarla
    setPhase('analyze')
    let res = null
    try { res = await api.manualCerts.analyze(extractedFormData(ex)) } catch (e) { res = { success: false, error: e?.message } } finally { setBusy(false); setPhase(null) }
    if (res == null) return
    if (!res.success) {
      if (transientFailure(res)) return
      const { fields, rest } = splitServerErrors(res.errors)
      if (fields.password) setPasswordNeeded(true)
      if (source === 'text' && fields.file) { fields.text = fields.file; delete fields.file }
      if (Object.keys(fields).length) fe.check(fields)
      if (rest.length || !Object.keys(fields).length) setBanner({ tone: 'danger', title: t('mcert.err.analyzeTitle'), text: rest.join(' ') || res.error || t('mcert.err.analyze') })
      return
    }
    const data = res.data || {}
    if (data.needs_password || data.password_error) {
      setPasswordNeeded(true)
      fe.check({ password: data.password_error ? t('mcert.pw.wrong') : t('mcert.pw.required') })
      return
    }
    // Tarayıcının notları (ZIP atlanan girdi, JKS bütünlük uyumsuzluğu …) sunucu uyarılarının önünde
    const merged = { ...data, warnings: [...(Array.isArray(ex.notes) ? ex.notes : []), ...(Array.isArray(data.warnings) ? data.warnings : [])] }
    // JKS/JCEKS: yanlış şifrede sertifikalar yine okunur (yalnız PASSWORD_WRONG notu) — şifre alanı görünür kalsın;
    // İnceleme'de "Şifreyi düzelt" geri götürür.
    if (merged.warnings.some((w) => PASSWORD_CODES.has(w?.code))) setPasswordNeeded(true)
    setAnalysis(merged)
    setPrefilled(null)   // yeni dosya: takip adları ve kip yeniden önerilsin
    const def = defaultRef(data)
    setSelected(def)
    setSelectedSet(new Set(def ? [def] : []))
    setMulti(false)
    setStep('review')
  }

  /** İnceleme → Takip: seçim değiştiyse takip adları sunucunun önerisiyle yeniden doldurulur; kip önerilir. */
  function toTrack(opts = {}) {
    const refs = multi ? [...selectedSet] : (opts.ref ?? selected) ? [opts.ref ?? selected] : []
    if (!refs.length) { setBanner({ tone: 'warning', text: t('mcert.review.pickOne') }); return }
    setBanner(null)
    const sig = `${multi ? 'm' : 's'}:${refs.join(',')}`
    if (prefilled !== sig) {
      if (multi) {
        setBatchKeys(Object.fromEntries(refs.map((r) => [r, byRef(r)?.suggested_key || ''])))
        setRowErrors({})
      } else {
        const e = byRef(refs[0])
        setKeyValue(e?.suggested_key || '')
      }
      setPrefilled(sig)
    }
    if (multi) setMode('new')
    else if (opts.target) { setMode('renew'); setTarget(opts.target) }
    else if (!renewFixed && prefilled !== sig) {
      const same = byRef(refs[0])?.matches?.same_subject || []
      if (same.length) { setMode('renew'); setTarget(same[0]) } else setMode('new')
    }
    setStep('track')
  }

  /** İnceleme kartındaki "Bu kaydı yenile": girdiyi seç + hedefi kur + Takip adımına geç. */
  function renewFromEntry(ref, tgt) {
    setMulti(false)
    setSelected(ref)
    setSelectedSet(new Set([ref]))
    toTrack({ ref, target: tgt })
  }

  function onField(k, v) { setForm((f) => ({ ...f, [k]: v })) }

  /**
   * Geçici sunucu durumları — 429 RATE_LIMITED (dakikada 30 analiz) / BUSY (çözümleyici meşgul), 422 PARSE_TIMEOUT (dosya
   * 10 sn'de çözümlenemedi): bilgilendirici bant, sunucunun iletisi (dil başlığıyla) varsa o. İşlendiyse true.
   */
  function transientFailure(res) {
    const code = res.code
    if (res.status === 429 || code === 'RATE_LIMITED' || code === 'BUSY') {
      setBanner({ tone: 'warning', text: res.error || t(code === 'BUSY' ? 'mcert.err.busy' : 'mcert.err.rateLimit') })
      return true
    }
    if (res.status === 422 || code === 'PARSE_TIMEOUT') {
      setBanner({ tone: 'warning', title: t('mcert.err.timeoutTitle'), text: res.error || t('mcert.err.timeout') })
      return true
    }
    return false
  }

  function showServerFailure(res, kind) {
    if (transientFailure(res)) return
    const code = res.code
    if (code === 'KEY_EXISTS') {
      if (kind === 'batch') {
        const i = picked.findIndex((e) => (batchKeys[e.ref] || '') === res.domain)
        if (i >= 0) { setRowErrors((r) => ({ ...r, [i]: t('mcert.err.keyExists') })); return }
        setBanner({ tone: 'danger', text: res.error || t('mcert.err.keyExists') })
      } else fe.check({ domain: t('mcert.err.keyExists') })
      return
    }
    if (code === 'ALREADY_TRACKED') {
      setBanner({ tone: 'warning', title: t('mcert.match.trackedTitle'), text: t('mcert.match.tracked', res.domain || '—'),
        target: res.domain ? { domain: res.domain, inventory_id: res.inventory_id } : null })
      return
    }
    if (code === 'OLDER_THAN_CURRENT') { setServerOlder(true); setConfirmOlder(false); fe.check({ confirm: t('mcert.renew.confirmRequired') }); return }
    // Aynı sertifika: engel değil — Takip adımında uyarı + "Yine de yükle" (allow_same ile yeniden gönderir)
    if (code === 'SAME_CERTIFICATE') { setServerSame(true); return }
    const split = splitServerErrors(res.errors)
    // Bu adımda alanı olmayan hatalar (şifre, dosya, seçim) alan yerine bantta — görünmeyen alana hata yazılmasın.
    const fields = {}
    const rest = [...split.rest]
    for (const [k, v] of Object.entries(split.fields)) {
      if (TRACK_FIELDS.has(k)) fields[k] = v
      else rest.push(v)
    }
    if (Object.keys(split.rows).length) setRowErrors(split.rows)
    if (Object.keys(fields).length) fe.check(fields)
    if (rest.length || (!Object.keys(fields).length && !Object.keys(split.rows).length)) {
      setBanner({ tone: 'danger', title: t('mcert.err.saveTitle'), text: rest.join(' ') || res.error || t('mcert.err.save') })
    }
  }

  /**
   * @param {{ allowSame?: boolean }} [opts] `allowSame` — "Yine de yükle": güncel sürümle AYNI sertifika yeni sürüm olarak
   *   kaydedilir (`allow_same=true`; bitiş tarihi değişmez). Yalnız yenileme kipinde anlamlı.
   */
  async function submit(opts = {}) {
    const allowSame = opts.allowSame === true
    setBanner(null)
    const noteErr = note.length > NOTE_MAX && t('mcert.track.noteTooLong', NOTE_MAX)
    let res = null
    let kind = 'created'
    if (mode === 'renew' && !multi) {
      kind = 'renewed'
      if (fe.check({
        target: !target && t('mcert.renew.targetRequired'),
        confirm: needsConfirm && !confirmOlder && t('mcert.renew.confirmRequired'),
        note: noteErr,
      })) return
      if (sameCert && !allowSame) return   // uyarı bandı ekranda; ilerlemek için "Yine de yükle"
      setBusy(true)
      try {
        res = await api.manualCerts.renew(target.inventory_id,
          extractedFormData(extraction, {
            ref: picked[0]?.ref, note: note.trim() || undefined, confirm: confirmOlder ? 'true' : undefined,
            allow_same: allowSame ? 'true' : undefined,
          }))
      } catch (e) { res = { success: false, error: e?.message } }
    } else if (multi) {
      kind = 'batch'
      const keys = picked.map((e) => batchKeys[e.ref] || '')
      const errs = { ...trackingErrors(form, t), note: noteErr }
      const rowErr = {}
      keys.forEach((k, i) => {
        const err = trackingKeyError(k)
        if (err) rowErr[`item_${i}`] = t(err)
        else if (keys.indexOf(k) !== i) rowErr[`item_${i}`] = t('mcert.key.duplicate')
      })
      setRowErrors({})
      if (fe.check({ ...rowErr, ...errs })) return
      setBusy(true)
      try {
        res = await api.manualCerts.createBatch(extractedFormData(extraction, {
          items: picked.map((e, i) => ({ ref: e.ref, domain: keys[i] })), inventory: inventoryPayload(form), note: note.trim() || undefined,
        }))
      } catch (e) { res = { success: false, error: e?.message } }
    } else {
      const keyErr = trackingKeyError(keyValue)
      if (fe.check({ domain: keyErr && t(keyErr), ...trackingErrors(form, t), note: noteErr })) return
      setBusy(true)
      try {
        res = await api.manualCerts.create(extractedFormData(extraction, {
          ref: picked[0]?.ref, domain: keyValue, inventory: inventoryPayload(form), note: note.trim() || undefined,
        }))
      } catch (e) { res = { success: false, error: e?.message } }
    }
    setBusy(false)
    if (res == null) return
    if (!res.success) { showServerFailure(res, kind); return }
    const out = { kind, data: res.data || {}, domain: kind === 'renewed' ? target?.domain : (res.data?.domain || keyValue) }
    setResult(out)
    setStep('result')
    onDone?.(out)
  }

  function restart() {
    setStep('file'); setFile(null); setText(''); setPassword(''); setPasswordNeeded(false); setAnalysis(null); setBanner(null)
    setExtraction(null); setExtractIssue(null)
    setMulti(false); setSelected(null); setSelectedSet(new Set()); setPrefilled(null); setKeyValue(''); setBatchKeys({}); setRowErrors({})
    setNote(''); setConfirmOlder(false); setServerOlder(false); setServerSame(false); setResult(null)
    setMode(renewTarget ? 'renew' : 'new'); setTarget(renewTarget)
  }

  const openCert = (tgt) => { if (tgt && onOpenCert) { onClose?.(); onOpenCert(tgt) } }
  const resultTarget = result
    ? (result.kind === 'renewed' ? target : result.kind === 'batch' ? (result.data.created?.[0] || null) : { domain: result.data.domain || result.domain, inventory_id: result.data.inventory_id })
    : null

  const title = renewFixed ? t('mcert.wizard.renewTitle', renewTarget.domain) : t('mcert.wizard.title')
  const busyLabel = phase === 'extract' ? t('mcert.wizard.extracting') : step === 'file' ? t('mcert.wizard.analyzing') : t('mcert.wizard.saving')
  const stepSelectedOk = multi ? selectedSet.size > 0 : !!selected
  const trackedBlocked = !multi && mode === 'new' && !!picked[0]?.matches?.already_tracked

  const footer = (
    <div data-slot="mcert-wizard-actions" className="flex w-full flex-wrap items-center justify-end gap-2">
      {busy && <span role="status" className="mr-auto inline-flex items-center gap-1.5 text-xs text-muted-foreground"><Spinner size={12} inline decorative />{busyLabel}</span>}
      {step === 'file' && (
        <>
          <Button type="button" variant="secondary" className={NAV_BTN} onClick={onClose} disabled={busy}>{t('inv.cancel')}</Button>
          <Button type="button" data-slot="mcert-analyze" className={NAV_BTN} onClick={analyze} disabled={busy} aria-busy={busy || undefined}>
            <FileSearch aria-hidden="true" />{t('mcert.wizard.analyze')}
          </Button>
        </>
      )}
      {step === 'review' && (
        <>
          <Button type="button" variant="secondary" className={NAV_BTN} onClick={() => { setBanner(null); setStep('file') }}>
            <ArrowLeft aria-hidden="true" />{t('mcert.wizard.back')}
          </Button>
          <Button type="button" data-slot="mcert-next" className={NAV_BTN} onClick={() => toTrack()} disabled={!entries.length || !stepSelectedOk}>
            {t('mcert.wizard.next')}<ArrowRight aria-hidden="true" />
          </Button>
        </>
      )}
      {step === 'track' && (
        <>
          <Button type="button" variant="secondary" className={NAV_BTN} onClick={() => { setBanner(null); setStep('review') }} disabled={busy}>
            <ArrowLeft aria-hidden="true" />{t('mcert.wizard.back')}
          </Button>
          <Button type="button" data-slot="mcert-submit" className={NAV_BTN} onClick={() => submit()} disabled={busy || trackedBlocked || (mode === 'renew' && !multi && sameCert)}
            aria-busy={busy || undefined}>
            <Upload aria-hidden="true" />
            {mode === 'renew' && !multi ? t('mcert.wizard.saveVersion') : multi ? t('mcert.wizard.trackMany', picked.length) : t('mcert.wizard.track')}
          </Button>
        </>
      )}
      {step === 'result' && (
        <>
          <Button type="button" variant="secondary" className={NAV_BTN} onClick={restart}>
            <RotateCcw aria-hidden="true" />{t('mcert.wizard.another')}
          </Button>
          {result?.kind === 'created' && onEditInventory && (
            <Button type="button" variant="outline" className={NAV_BTN} onClick={() => { onClose?.(); onEditInventory(resultTarget?.domain) }}>
              <Pencil aria-hidden="true" />{t('mcert.wizard.editInventory')}
            </Button>
          )}
          {resultTarget?.domain && onOpenCert ? (
            <Button type="button" data-slot="mcert-open-result" className={NAV_BTN} onClick={() => openCert(resultTarget)}>
              <Eye aria-hidden="true" />{t('mcert.wizard.openCert')}
            </Button>
          ) : (
            <Button type="button" className={NAV_BTN} onClick={onClose}>{t('app.close')}</Button>
          )}
        </>
      )}
    </div>
  )

  return (
    <ModalShell open onClose={onClose} icon={FileUp} size="lg" scrollBody bodyRef={bodyRef} busy={busy} dismissOnBackdrop={false}
      className={PHONE_FULLSCREEN} title={<span data-slot="mcert-wizard-title" className="min-w-0 truncate">{title}</span>} footer={footer}>
      <div data-slot="mcert-wizard" data-step={step} className="flex min-w-0 flex-col">
        <StepIndicator step={step} />
        {banner && (
          <AlertBanner tone={banner.tone} role="alert" title={banner.title} onDismiss={() => setBanner(null)} dismissLabel={t('app.close')}
            actions={banner.target && onOpenCert ? (
              <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => openCert(banner.target)}>{t('mcert.match.openRecord')}</Button>
            ) : null}>
            {banner.text}
          </AlertBanner>
        )}
        {renewFixed && step === 'file' && (
          <AlertBanner tone="info" className="mb-3">{t('mcert.wizard.renewIntro', renewTarget.domain)}</AlertBanner>
        )}
        {step === 'file' && (
          <FileStep source={source} onSource={(v) => { setSource(v); setExtraction(null); setExtractIssue(null) }} file={file}
            onFile={(f) => { setFile(f); setPasswordNeeded(false); setExtraction(null); setExtractIssue(null) }}
            text={text} onText={(v) => { setText(v); setExtraction(null); setExtractIssue(null) }}
            password={password} onPassword={(v) => { setPassword(v); setExtraction(null) }} passwordNeeded={passwordNeeded} fe={fe}
            extraction={extraction} issue={extractIssue} />
        )}
        {step === 'review' && (
          <ReviewStep analysis={analysis} multi={multi} onMulti={(v) => { setMulti(v); if (!v && !selected) setSelected(defaultRef(analysis)) }}
            selected={selected} onSelect={setSelected} selectedSet={selectedSet}
            onToggle={(ref) => setSelectedSet((s) => { const n = new Set(s); if (n.has(ref)) n.delete(ref); else n.add(ref); return n })}
            renewMode={renewFixed} renewTargetId={renewTarget?.inventory_id ?? null}
            onOpenCert={onOpenCert ? openCert : undefined} onRenewTarget={renewFromEntry}
            onFixPassword={() => { setBanner(null); setPasswordNeeded(true); setPwFix(true); setStep('file') }} extraction={extraction} />
        )}
        {step === 'track' && (
          <>
            {trackedBlocked && (
              <AlertBanner tone="warning" className="mb-3" title={t('mcert.match.trackedTitle')}
                actions={onOpenCert ? <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => openCert(picked[0].matches.already_tracked)}>{t('mcert.match.openRecord')}</Button> : null}>
                {t('mcert.match.tracked', picked[0].matches.already_tracked.domain)}
              </AlertBanner>
            )}
            <TrackStep entries={picked} multi={multi} mode={mode} onMode={setMode} renewFixed={renewFixed} renewOptions={renewOptions}
              target={target} onTarget={setTarget} current={current} cmp={cmp} needsConfirm={needsConfirm}
              keyValue={keyValue} onKey={setKeyValue} batchKeys={batchKeys}
              onBatchKey={(ref, v, i) => { setBatchKeys((b) => ({ ...b, [ref]: v })); setRowErrors((r) => { if (!r[i]) return r; const n = { ...r }; delete n[i]; return n }) }}
              rowErrors={rowErrors} form={form} onField={onField} teams={teams} canOpenSettings={canOpenSettings}
              note={note} onNote={setNote} confirmOlder={confirmOlder} onConfirmOlder={setConfirmOlder} fe={fe}
              sameDetected={serverSame} onUploadAnyway={() => submit({ allowSame: true })} busy={busy} />
          </>
        )}
        {step === 'result' && <ResultStep result={result} />}
      </div>
    </ModalShell>
  )
}
