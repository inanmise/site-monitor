import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowLeft, ArrowRight, Eye, FileSearch, FileUp, Pencil, RotateCcw, Square, Upload } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { formatPercent } from '../../i18n/dateLocale.js'
import { useFormErrors } from '../../hooks/useFormErrors.js'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ErrorDetails from '../ui/ErrorDetails.jsx'
import { Spinner } from '../ui/Progress.jsx'
import FileStep from './wizard/FileStep.jsx'
import ReviewStep from './wizard/ReviewStep.jsx'
import TrackStep from './wizard/TrackStep.jsx'
import ResultStep from './wizard/ResultStep.jsx'
import UploadProgress from './wizard/UploadProgress.jsx'
import {
  EMPTY_TRACKING, MAX_UPLOAD_BYTES, MAX_UPLOAD_MB, NOTE_MAX, compareWithCurrent, defaultRef, extractedFormData, extractionErrorKey,
  inventoryPayload, trackingErrors, trackingKeyError,
} from './manualCertModel.js'
import { describeUploadFailure, technicalDetail } from './manualCertErrors.js'
import {
  applyExtractProgress, applyUploadProgress, canCancel, coarsePercent, enterStage, failRun, stageLabelKey, stagePercent, startRun,
} from './wizard/uploadProgressModel.js'
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
/** Kayıt türü → hata eşlemesindeki işlem adı. */
const SAVE_OP = { created: 'create', batch: 'batch', renewed: 'renew' }

/** İstek fırlattıysa (ağ hatası) — kullanıcıya hazır metin istemcinin; künye (durum 0 + kod) "Teknik ayrıntı"da. */
const thrownResult = (e) => ({ success: false, thrown: true, status: 0, code: e?.code || 'NETWORK_ERROR', error: e?.message })

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
 * <p><b>Yükleme durumu (2026-10-08, kullanıcı isteği):</b> her uzun iş bir KOŞUDUR (`run`, `wizard/uploadProgressModel.js`):
 * analiz = Dosya okunuyor → Sertifikalar tarayıcınızda ayıklanıyor (PKCS#12 notu, ZIP "n / N dosya") → Sunucuya
 * gönderiliyor (GERÇEK yüzde — XMLHttpRequest `upload.onprogress`) → Sunucu analiz ediyor; kayıt = Sunucuya gönderiliyor →
 * Kaydediliyor. Panel (`wizard/UploadProgress`) tüm aşamaları baştan listeler; tek `aria-live` satırı aşama değişimini
 * duyurur. "Vazgeç" Worker'ı / isteği keser ve adıma TEMİZ döner (meşgul durum kalmaz); kayıt istek sunucuya ulaştıktan
 * sonra durdurulamaz (panel söyler). `busy` ayrı bir bayrak değil, koşunun durumudur — koşu her yolda `endRun` ile kapanır.
 *
 * <p><b>Hata iletileri (2026-10-08):</b> metin `manualCertErrors.describeUploadFailure`'dan — ne oldu + neden + ne yapmalı;
 * alana ait olan alanın altında, olmayan bantta ("Teknik ayrıntı": durum / kod / istek kimliği).
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
  // Yükleme durumu koşusu (null | { status: running|failed, … }) — meşgul durum buradan türetilir
  const [run, setRun] = useState(null)
  const runRef = useRef(null)                              // { ctrl: AbortController } — etkin koşunun jetonu
  const [liveNote, setLiveNote] = useState('')             // koşu bitince/iptal edilince duyurulan son ileti
  const busy = run?.status === 'running'
  const [banner, setBanner] = useState(null)   // { tone, title?, text, hint?, reason?, detail?, code?, target?, action? }
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
  const actionsRef = useRef(null)
  const cancelRef = useRef(null)
  useEffect(() => { if (bodyRef.current) bodyRef.current.scrollTop = 0 }, [step])
  // Adım değişince başarısız koşunun özeti kalkar (yeni adımın hatası değil)
  useEffect(() => { setRun((r) => (r && r.status === 'failed' ? null : r)) }, [step])
  // Pencere kapanınca (bileşen sökülünce) süren ayıklama / istek kesilir
  useEffect(() => () => { try { runRef.current?.ctrl?.abort() } catch { /* yok */ } runRef.current = null }, [])
  // Koşu başlayınca: panel görünsün (gövde başa), odak devre dışı kalan düğmeden "Vazgeç"e geçsin
  useEffect(() => {
    if (!busy) return
    if (bodyRef.current) bodyRef.current.scrollTop = 0
    const a = typeof document !== 'undefined' ? document.activeElement : null
    if (!a || a === document.body || actionsRef.current?.contains(a)) cancelRef.current?.focus?.()
  }, [busy])
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

  // ── Koşu (yükleme durumu) ─────────────────────────────────────────────────────────────────────────────────────
  /** Yeni koşu: öncekini keser, jeton döner. Jeton yalnız etkin koşununsa ilerleme / sonuç işlenir (`isLive`). */
  function beginRun(opts) {
    try { runRef.current?.ctrl?.abort() } catch { /* yok */ }
    const tok = { ctrl: typeof AbortController !== 'undefined' ? new AbortController() : null }
    runRef.current = tok
    setLiveNote('')
    setRun(startRun({ ...opts, now: Date.now() }))
    return tok
  }
  const isLive = (tok) => runRef.current === tok
  /** Etkin koşunun ilerlemesi (eski / iptal edilmiş koşunun geç gelen bildirimi yok sayılır). */
  const progress = (tok, fn) => { if (isLive(tok)) setRun((r) => (r && r.status === 'running' ? fn(r, Date.now()) : r)) }
  /**
   * Koşuyu kapatır — HER yolda (başarı, hata, oturum bitti, fırlatma) `finally`den çağrılır; meşgul durum asılı kalmaz.
   * `failed`: hangi aşamada durduğu panelde kalır; diğerleri: panel kalkar.
   */
  function endRun(tok, outcome) {
    if (runRef.current !== tok) return                     // iptal edildi ya da yeni koşu başladı
    runRef.current = null
    if (outcome === 'failed') { setRun((r) => failRun(r)); return }
    setRun(null)
    if (outcome === 'done') setLiveNote(t('mcert.prog.live.done'))
  }
  /** "Vazgeç": Worker / istek kesilir, adıma temiz dönülür. Kayıt istek sunucuya ulaştıysa durdurulamaz (düğme kapalı). */
  function cancelRun() {
    const tok = runRef.current
    if (!tok || !canCancel(run)) return
    runRef.current = null
    try { tok.ctrl?.abort() } catch { /* yok */ }
    const msg = run.kind === 'save' ? t('mcert.prog.cancelledSave') : t('mcert.prog.cancelledAnalyze')
    setRun(null)
    setBanner({ tone: 'info', text: msg })
    setLiveNote(msg)
    const focusPrimary = () => actionsRef.current?.querySelector('[data-slot="mcert-analyze"], [data-slot="mcert-submit"]')?.focus?.()
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(focusPrimary); else focusPrimary()
  }
  const stageLabel = (r, stage) => { const [k, ...a] = stageLabelKey(r, stage); return t(k, ...a) }
  /** Tek canlı bölgenin metni: aşama adı (+ kaba yüzde) — her bayt güncellemesinde konuşmasın. */
  const liveText = run?.status === 'running'
    ? [stageLabel(run, run.active), stagePercent(run) != null ? formatPercent(coarsePercent(stagePercent(run))) : null].filter(Boolean).join(' ')
    : run?.status === 'failed' ? t('mcert.prog.failedAt', stageLabel(run, run.failedStage)) : liveNote

  /** Başarısız yanıt → alan hataları (bu adımda görünenler) / satır hataları / açıklamalı bant. */
  function showFailure(res, op) {
    const available = op === 'analyze' ? new Set([source === 'file' ? 'file' : 'text', 'password']) : TRACK_FIELDS
    const d = describeUploadFailure(res, op, t, { fields: available, textSource: source === 'text' })
    if (Object.keys(d.rows).length) setRowErrors(d.rows)
    if (Object.keys(d.fields).length) {
      if (d.fields.password) setPasswordNeeded(true)
      fe.check(d.fields)
    }
    if (d.banner) setBanner({ ...d.banner, code: res?.code || null })
  }

  async function analyze() {
    const errs = source === 'file'
      ? { file: !file ? t('mcert.file.required') : file.size > MAX_UPLOAD_BYTES ? t('mcert.file.tooLarge', MAX_UPLOAD_MB) : null }
      : { text: !text.trim() && t('mcert.text.required') }
    if (fe.check(errs)) return
    setBanner(null); setExtractIssue(null)
    const tok = beginRun({ kind: 'analyze', source })
    let outcome = 'failed'
    try {
      outcome = await runAnalyze(tok)
    } catch (e) {
      if (isLive(tok)) showFailure(thrownResult(e), 'analyze')
    } finally {
      endRun(tok, outcome)
    }
  }

  /** Analiz koşusu: 1) TARAYICIDA ayıkla (özel anahtar ve şifre buradan çıkmaz) 2) sunucu analizi YALNIZ açık sertifikalarla. */
  async function runAnalyze(tok) {
    const signal = tok.ctrl?.signal
    let ex = null
    try {
      ex = await extractCertificates(source === 'file' ? { file, password } : { text, password },
        { onProgress: (p) => progress(tok, (r, now) => applyExtractProgress(r, p, now)), signal })
    } catch {
      ex = { unsupported: { reason: 'UNREADABLE' } }
    }
    if (!isLive(tok) || ex?.unsupported?.reason === 'CANCELLED') return 'cancelled'
    if (!ex || ex.unsupported || ex.needs_password || ex.password_error) {
      setExtraction(null)
      if (ex?.needs_password || ex?.password_error) {
        setPasswordNeeded(true)
        fe.check({ password: ex.password_error ? t('mcert.pw.wrong') : t('mcert.pw.required') })
        return 'failed'
      }
      const [key, ...args] = extractionErrorKey(ex?.unsupported)
      setExtractIssue(ex?.unsupported?.reason || 'UNKNOWN')
      fe.check({ [source === 'file' ? 'file' : 'text']: t(key, ...args) })
      return 'failed'
    }
    setExtraction(ex)
    progress(tok, (r, now) => enterStage(r, 'upload', now))
    let res = null
    try {
      res = await api.manualCerts.analyze(extractedFormData(ex), { onProgress: (p) => progress(tok, (r, now) => applyUploadProgress(r, p, now)), signal })
    } catch (e) {
      res = thrownResult(e)
    }
    if (!isLive(tok) || res?.cancelled) return 'cancelled'
    if (res == null) return 'ended'          // oturum bitti / bakım / pasif hesap — istemci yönlendirdi ya da pencereyi açtı
    if (!res.success) { showFailure(res, 'analyze'); return 'failed' }
    const data = res.data || {}
    if (data.needs_password || data.password_error) {
      setPasswordNeeded(true)
      fe.check({ password: data.password_error ? t('mcert.pw.wrong') : t('mcert.pw.required') })
      return 'failed'
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
    return 'done'
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

  /** Kayıt başarısızlığı: sihirbaza özgü 409'lar burada, geri kalan her şey ortak eşlemede (`showFailure`). */
  function showSaveFailure(res, kind) {
    const code = res.code
    if (code === 'KEY_EXISTS') {
      if (kind === 'batch') {
        const i = picked.findIndex((e) => (batchKeys[e.ref] || '') === res.domain)
        if (i >= 0) { setRowErrors((r) => ({ ...r, [i]: t('mcert.err.keyExists') })); return }
        setBanner({ tone: 'danger', title: t('mcert.err.keyExistsTitle'), text: t('mcert.err.keyExists'), detail: technicalDetail(res), code })
      } else fe.check({ domain: t('mcert.err.keyExists') })
      return
    }
    if (code === 'ALREADY_TRACKED') {
      setBanner({ tone: 'warning', title: t('mcert.match.trackedTitle'), text: t('mcert.match.tracked', res.domain || '—'), code,
        detail: technicalDetail(res), target: res.domain ? { domain: res.domain, inventory_id: res.inventory_id } : null })
      return
    }
    if (code === 'OLDER_THAN_CURRENT') { setServerOlder(true); setConfirmOlder(false); fe.check({ confirm: t('mcert.renew.confirmRequired') }); return }
    // Aynı sertifika: engel değil — Takip adımında uyarı + "Yine de yükle" (allow_same ile yeniden gönderir)
    if (code === 'SAME_CERTIFICATE') { setServerSame(true); return }
    showFailure(res, SAVE_OP[kind] || 'create')
  }

  /**
   * @param {{ allowSame?: boolean }} [opts] `allowSame` — "Yine de yükle": güncel sürümle AYNI sertifika yeni sürüm olarak
   *   kaydedilir (`allow_same=true`; bitiş tarihi değişmez). Yalnız yenileme kipinde anlamlı.
   */
  async function submit(opts = {}) {
    const allowSame = opts.allowSame === true
    setBanner(null)
    const noteErr = note.length > NOTE_MAX && t('mcert.track.noteTooLong', NOTE_MAX)
    let kind = 'created'
    let call = null
    if (mode === 'renew' && !multi) {
      kind = 'renewed'
      if (fe.check({
        target: !target && t('mcert.renew.targetRequired'),
        confirm: needsConfirm && !confirmOlder && t('mcert.renew.confirmRequired'),
        note: noteErr,
      })) return
      if (sameCert && !allowSame) return   // uyarı bandı ekranda; ilerlemek için "Yine de yükle"
      const fd = extractedFormData(extraction, {
        ref: picked[0]?.ref, note: note.trim() || undefined, confirm: confirmOlder ? 'true' : undefined,
        allow_same: allowSame ? 'true' : undefined,
      })
      const id = target.inventory_id
      call = (o) => api.manualCerts.renew(id, fd, o)
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
      const fd = extractedFormData(extraction, {
        items: picked.map((e, i) => ({ ref: e.ref, domain: keys[i] })), inventory: inventoryPayload(form), note: note.trim() || undefined,
      })
      call = (o) => api.manualCerts.createBatch(fd, o)
    } else {
      const keyErr = trackingKeyError(keyValue)
      if (fe.check({ domain: keyErr && t(keyErr), ...trackingErrors(form, t), note: noteErr })) return
      const fd = extractedFormData(extraction, {
        ref: picked[0]?.ref, domain: keyValue, inventory: inventoryPayload(form), note: note.trim() || undefined,
      })
      call = (o) => api.manualCerts.create(fd, o)
    }
    const tok = beginRun({ kind: 'save', saveKind: kind, count: picked.length })
    let outcome = 'failed'
    try {
      outcome = await runSave(tok, kind, call)
    } catch (e) {
      if (isLive(tok)) showFailure(thrownResult(e), SAVE_OP[kind])
    } finally {
      endRun(tok, outcome)
    }
  }

  /** Kayıt koşusu: Sunucuya gönderiliyor (gerçek yüzde) → Kaydediliyor → Sonuç. */
  async function runSave(tok, kind, call) {
    let res = null
    try {
      res = await call({ onProgress: (p) => progress(tok, (r, now) => applyUploadProgress(r, p, now)), signal: tok.ctrl?.signal })
    } catch (e) {
      res = thrownResult(e)
    }
    if (!isLive(tok) || res?.cancelled) return 'cancelled'
    if (res == null) return 'ended'
    if (!res.success) { showSaveFailure(res, kind); return 'failed' }
    const out = { kind, data: res.data || {}, domain: kind === 'renewed' ? target?.domain : (res.data?.domain || keyValue) }
    setResult(out)
    setStep('result')
    onDone?.(out)
    return 'done'
  }

  function restart() {
    try { runRef.current?.ctrl?.abort() } catch { /* yok */ }
    runRef.current = null
    setRun(null)
    setStep('file'); setFile(null); setText(''); setPassword(''); setPasswordNeeded(false); setAnalysis(null); setBanner(null)
    setExtraction(null); setExtractIssue(null)
    setMulti(false); setSelected(null); setSelectedSet(new Set()); setPrefilled(null); setKeyValue(''); setBatchKeys({}); setRowErrors({})
    setNote(''); setConfirmOlder(false); setServerOlder(false); setServerSame(false); setResult(null)
    setMode(renewTarget ? 'renew' : 'new'); setTarget(renewTarget)
  }

  /** Girdi değişince eski hatanın (başarısız koşu özeti + bant) anlamı kalmaz. */
  const clearFailure = () => { setRun((r) => (r && r.status === 'failed' ? null : r)); setBanner(null) }

  const openCert = (tgt) => { if (tgt && onOpenCert) { onClose?.(); onOpenCert(tgt) } }
  const resultTarget = result
    ? (result.kind === 'renewed' ? target : result.kind === 'batch' ? (result.data.created?.[0] || null) : { domain: result.data.domain || result.domain, inventory_id: result.data.inventory_id })
    : null

  const title = renewFixed ? t('mcert.wizard.renewTitle', renewTarget.domain) : t('mcert.wizard.title')
  const stepSelectedOk = multi ? selectedSet.size > 0 : !!selected
  const trackedBlocked = !multi && mode === 'new' && !!picked[0]?.matches?.already_tracked

  const cancelButton = (
    <Button ref={cancelRef} type="button" variant="secondary" data-slot="mcert-cancel-run" className={NAV_BTN}
      onClick={cancelRun} disabled={!canCancel(run)}>
      <Square aria-hidden="true" />{t('mcert.prog.cancel')}
    </Button>
  )

  const footer = (
    <div ref={actionsRef} data-slot="mcert-wizard-actions" className="flex w-full flex-wrap items-center justify-end gap-2">
      {step === 'file' && (
        <>
          {busy ? cancelButton : (
            <Button type="button" variant="secondary" className={NAV_BTN} onClick={onClose}>{t('inv.cancel')}</Button>
          )}
          <Button type="button" data-slot="mcert-analyze" className={NAV_BTN} onClick={analyze} disabled={busy} aria-busy={busy || undefined}>
            {busy ? <Spinner size={16} decorative /> : <FileSearch aria-hidden="true" />}{t('mcert.wizard.analyze')}
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
          {busy ? cancelButton : (
            <Button type="button" variant="secondary" className={NAV_BTN} onClick={() => { setBanner(null); setStep('review') }}>
              <ArrowLeft aria-hidden="true" />{t('mcert.wizard.back')}
            </Button>
          )}
          <Button type="button" data-slot="mcert-submit" className={NAV_BTN} onClick={() => submit()} disabled={busy || trackedBlocked || (mode === 'renew' && !multi && sameCert)}
            aria-busy={busy || undefined}>
            {busy ? <Spinner size={16} decorative /> : <Upload aria-hidden="true" />}
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

  const bannerActions = banner && (banner.target && onOpenCert ? (
    <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10" onClick={() => openCert(banner.target)}>{t('mcert.match.openRecord')}</Button>
  ) : banner.action === 'review' && entries.length ? (
    <Button type="button" variant="outline" size="sm" data-slot="mcert-banner-review" className="pointer-coarse:h-10"
      onClick={() => { setBanner(null); setStep('review') }}>{t('mcert.err.backToReview')}</Button>
  ) : null)

  return (
    <ModalShell open onClose={onClose} icon={FileUp} size="lg" scrollBody bodyRef={bodyRef} busy={busy} dismissOnBackdrop={false}
      className={PHONE_FULLSCREEN} title={<span data-slot="mcert-wizard-title" className="min-w-0 truncate">{title}</span>} footer={footer}>
      <div data-slot="mcert-wizard" data-step={step} data-busy={busy ? 'true' : undefined} aria-busy={busy || undefined} className="flex min-w-0 flex-col">
        <StepIndicator step={step} />
        {/* Tek canlı bölge: aşama değişimi / iptal / bitiş duyurusu (panelde ikinci bir canlı bölge yok) */}
        <p data-slot="mcert-progress-live" role="status" aria-live="polite" className="sr-only">{liveText}</p>
        {run && <UploadProgress run={run} onDismiss={() => setRun(null)} />}
        {banner && (
          <div data-slot="mcert-banner" data-code={banner.code || undefined}>
            <AlertBanner tone={banner.tone} role={banner.tone === 'info' ? 'status' : 'alert'} title={banner.title} onDismiss={() => setBanner(null)}
              dismissLabel={t('app.close')} actions={bannerActions}>
              <span className="flex min-w-0 flex-col gap-1">
                <span className="block">{banner.text}</span>
                {banner.hint && <span data-slot="mcert-banner-hint" className="block">{banner.hint}</span>}
                {banner.detail && <ErrorDetails info={banner.detail} />}
              </span>
            </AlertBanner>
          </div>
        )}
        {renewFixed && step === 'file' && (
          <AlertBanner tone="info" className="mb-3">{t('mcert.wizard.renewIntro', renewTarget.domain)}</AlertBanner>
        )}
        {step === 'file' && (
          <FileStep source={source} onSource={(v) => { setSource(v); setExtraction(null); setExtractIssue(null); clearFailure() }} file={file}
            onFile={(f) => { setFile(f); setPasswordNeeded(false); setExtraction(null); setExtractIssue(null); clearFailure() }}
            text={text} onText={(v) => { setText(v); setExtraction(null); setExtractIssue(null) }}
            password={password} onPassword={(v) => { setPassword(v); setExtraction(null) }} passwordNeeded={passwordNeeded} fe={fe}
            extraction={extraction} issue={extractIssue} disabled={busy} />
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
