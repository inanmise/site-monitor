import { memo, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowUp, CalendarClock, ChevronRight, EyeOff, FileText, Hash, ImageIcon, Plus, ScrollText, Tag, Trash2, Users, Wand2, Zap,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/shadcn/collapsible'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { NumberInput, PendingInputContext, issueErrorKey } from './ReportSections.jsx'
import { RichText, RichTextarea } from './RichText.jsx'
import { CopyTextButton } from './TechErrorParts.jsx'
import { CollapsibleCard, ExpandAllButton, TopicOpenContext, supportsUntilFound, useUntilFound } from './SectionCollapse.jsx'
import { ImageSlotTile } from './ReportImages.jsx'
import { CaptureSessionChip, CaptureStopNotice, useCaptureScope } from './capture/CaptureParts.jsx'
import { postImagesChanged } from './capture/imageSync.js'
import {
  ISSUE_IMAGES, ISSUE_LIMITS, LIMITS, allFields, formatCount, formatPercent, issueDayLabel, issueDisplayTitle, issueHidden,
  issueLeadText, issueLegacy, issueOptions, issueRangeLabel, issueShare, issueSlotId, issueUnitKey, move, newIssue, reportMonthBounds,
  resolveIssueTitle, topicOpenId,
} from './techErrorModel.js'

const EMPTY = Object.freeze([])

/**
 * ANOMALİ KONULARI (2026-10-18, kullanıcı isteği: "her konu için başlık; NE ZAMAN — gün + saat aralığı; kaç teknik hata ve tüm
 * teknik hatalar içindeki payı (otomatik); kesinti kaynaklı mı — Evet ise Kesinti ID zorunlu; müşteri etkisi var mı — yoksa
 * gösterilmesin; isteğe bağlı görseller; rapor metni bunlara göre üretilsin").
 *
 * <p>Her konu bir KART: başlık + durum çipleri (zaman, adet + pay, kesinti + ID, müşteri etkisi, "rapora girmeyecek"), altında
 * gruplanmış alanlar — Konu, Ne zaman? (takvim + iki saat + "ertesi gün"), Teknik hata (adet + CANLI pay — taslağa yazılmaz,
 * sunucu kaydederken hesaplar), Kesinti (Evet / Hayır; Evet'te ID alanı açılır, zorunlu), Müşteri etkisi (Evet'te müşteri
 * sayısı açılır), rapordaki metnin önizlemesi (PDF / e-postadaki giriş metninin aynısı), Açıklama ve Görseller (en çok iki,
 * tam genişlik, açıklamalı). Hangi grubun sorulacağı şablondan gelir ({@code issueOptions}); hiçbir soru açık değilse eski
 * düzen (müşteri + hata adedi). Hata anahtarları konunun KİMLİĞİYLE: istemci `issue:<bölüm>:<konu>:<alan>`, sunucu
 * `issues.<bölüm>.<konu>.<alan>` — konu taşınınca hata başka konuya kaymaz.
 *
 * <p>Yazma maliyeti (inceleme 2026-10-18): kartlar `memo` — her tuşta düzenleyici yeniden çizilir, ama değişmeyen konunun kartı
 * (görsel yuvaları dahil) çizilmez. Geri çağrılar konu KİMLİĞİYLE ve sabit (`onIssuesUpdate` işlevsel güncelleme alır); her
 * kart yalnız KENDİ görsellerini ve KENDİ hata anahtarlarını alır. Rapor toplamı (40 MB) yükleme ANINDA okunur (`imagesRef`,
 * 2026-10-15 kuralı — hazırlama sürerken başka yuvaya yüklenen görsel de sayılır).
 *
 * <p>KATLANIR KONULAR (2026-10-18, kullanıcı isteği: "Anormal Artış Gösteren Hatalar altında eklenen konu ve başlıklar da kendi
 * içinde akordiyon gibi açılıp kapanabilsin"): her konu bölümler gibi açılır / kapanır — aynı yapı taşları (`TopicOpenContext`
 * = `useSectionOpenState` + `useSectionOpenValue`, `useUntilFound`). Var olan konular KAPALI başlar; başlık satırı konunun
 * başlığını ve özet çiplerini taşır (kapalı konu da ne olduğunu söyler). Yeni eklenen konu AÇIK gelir ve odak ilk alanına
 * (Konu) geçer. İçerik kapalıyken de kurulu kalır (yarım yazılmış metin, odak, yarım girdiler, görsel yuvaları korunur). Hata
 * kümesine YENİ anahtar eklenen konu kendiliğinden açılır (düzenleyici); kapalı konuda hatalı alan sayısı rozetle görünür.
 * "Tüm konuları aç / kapat" yalnız bu bölümün konularını değiştirir; bölümlerin "Tümünü aç"ı konulara dokunmaz. Salt okunur
 * kullanıcı da açıp kapatabilir. Açık durum kartlara DEĞER olarak (`open`, `invalid`) ve sabit `onToggle` ile verilir: bir
 * konuyu açmak ya da yazmak öbür kartları çizdirmez.
 */
export function IssuesBlock({ section, template, list = EMPTY, units = null, shownFields, resolve, uiLang, readOnly, errors, onIssuesUpdate,
  onFieldError, onUnit = null, reportId, images = EMPTY, onImagesChange, period, beforeIssueUpload, captureNotice = false }) {
  const t = useT()
  const sid = section.id
  const topics = useContext(TopicOpenContext)
  const setTopicOpen = topics?.setOpen
  const openTopics = topics?.openMany
  const closeTopics = topics?.closeMany
  // Sabit: kart memo'su açma / kapamada başka kartı çizmesin
  const onToggle = useCallback((iid, o) => setTopicOpen?.(topicOpenId(sid, iid), o), [setTopicOpen, sid])
  // Yeni konu: açık gelir, odak ilk alanına (Konu) — kart çizildikten sonra (açık durum aynı çizimde uygulanır)
  const [focusTopic, setFocusTopic] = useState(null)
  useEffect(() => {
    if (!focusTopic) return
    const el = globalThis.document?.getElementById(`te-iss-${sid}-${focusTopic}-topic`)
    if (!el) return
    setFocusTopic(null)
    el.focus?.({ preventScroll: false })
    try { el.scrollIntoView?.({ block: 'center' }) } catch { /* yok */ }
  }, [focusTopic, list, sid])
  const topicKeys = list.map((i) => topicOpenId(sid, i.id))
  const allTopicsOpen = !topics || topicKeys.every((k) => topics.isOpen(k))
  function addTopic() {
    const fresh = newIssue(list)
    onIssuesUpdate(sid, (l) => [...l, fresh])
    setTopicOpen?.(topicOpenId(sid, fresh.id), true)
    setFocusTopic(fresh.id)
  }
  const lang = template?.lang
  const opts = useMemo(() => issueOptions(section, lang), [section, lang])
  const legacy = issueLegacy(opts)
  const hidden = list.filter((i) => issueHidden(section, i, lang)).length
  // Rapor toplamı yükleme anında okunur (kapanış eski listeyi tutmasın)
  const imagesRef = useRef(images)
  imagesRef.current = images
  const otherBytes = useCallback((slotId) => (imagesRef.current || [])
    .reduce((a, i) => a + (i.slot_id === slotId ? 0 : (i.size_bytes || 0)), 0), [])
  // Her konuya yalnız kendi görselleri (kart memo'su başka konunun yüklemesinde yeniden çizilmesin)
  const byIssue = useMemo(() => {
    const m = new Map()
    const prefix = `iss:${sid}:`
    for (const img of images) {
      const s = String(img?.slot_id || '')
      if (!s.startsWith(prefix)) continue
      const iid = s.slice(prefix.length, s.lastIndexOf(':'))
      if (!m.has(iid)) m.set(iid, [])
      m.get(iid).push(img)
    }
    return m
  }, [images, sid])
  // Ekrandan görsel alma (2026-10-18, anomali konusu görselleri de): paylaşım oturumu bu görünümde tutulur (rapordan çıkınca
  // durur), her yükleme / silme öteki pencerelere (Görsel paneli) duyurulur — ImagesBlock ile aynı. Sabit: kart memo'su bozulmasın
  useCaptureScope()
  const notifyChange = useCallback((fn) => { onImagesChange(fn); postImagesChanged(reportId) }, [onImagesChange, reportId])
  const capture = !legacy && opts.allowImages && !readOnly
  const patch = useCallback((iid, p) => onIssuesUpdate(sid, (l) => l.map((i) => (i.id === iid
    ? { ...i, ...(typeof p === 'function' ? p(i) : p) } : i))), [onIssuesUpdate, sid])
  const moveBy = useCallback((iid, delta) => onIssuesUpdate(sid, (l) => {
    const idx = l.findIndex((i) => i.id === iid)
    return idx < 0 ? l : move(l, idx, delta)
  }), [onIssuesUpdate, sid])
  const remove = useCallback((iid) => {
    onIssuesUpdate(sid, (l) => l.filter((x) => x.id !== iid))
    // Silinen konunun açık kaydı da düşer (başka yoldan kalkan konular bir sonraki yazımda — TOPIC_OPEN_OPTS.prune)
    setTopicOpen?.(topicOpenId(sid, iid), false)
  }, [onIssuesUpdate, sid, setTopicOpen])
  return (
    <CollapsibleCard sectionId={sid} slot="te-issues" title={resolve(section.title)} contentClassName="flex flex-col gap-4"
      hint={<p className="text-sm text-muted-foreground">{t(legacy ? 'te.issues.hint' : 'te.iss.hint')}</p>}>
      {/* Paylaşım açıkken konu görsellerinin bölümünde de görünür (rapor yalnız konu görseli taşıyabilir); rapordaki ilk
          görsel alan bölümü kapanma bildirimini bağlar (ReportEditor: görsel bölümü yoksa ilk görselli konu bölümü) */}
      {capture && <CaptureSessionChip />}
      {capture && captureNotice && <CaptureStopNotice />}
      {list.length === 0 && <p className="m-0 text-sm text-muted-foreground" data-slot="te-issues-empty">{t('te.issues.empty')}</p>}
      {hidden > 0 && (
        <p className="m-0 flex items-start gap-2 text-sm text-muted-foreground" data-slot="te-issues-hidden-note" role="status">
          <EyeOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span className="[overflow-wrap:anywhere]">{t('te.iss.hiddenCount', hidden)}</span>
        </p>
      )}
      <ol className="m-0 flex list-none flex-col gap-3 p-0">
        {list.map((iss, idx) => {
          const tk = topicKeys[idx]
          const open = topics ? topics.isOpen(tk) : true
          return (
            <IssueCard key={iss.id} section={section} template={template} opts={opts} legacy={legacy} issue={iss} index={idx}
              count={list.length} shownFields={shownFields} resolve={resolve} uiLang={uiLang} readOnly={readOnly}
              errors={cardErrors(errors, sid, iss.id, idx)} onFieldError={onFieldError} reportId={reportId}
              images={byIssue.get(iss.id) || EMPTY} otherBytes={otherBytes} onImagesChange={notifyChange} period={period}
              beforeIssueUpload={beforeIssueUpload} onPatch={patch} onMove={moveBy} onRemove={remove}
              // Katlanır konu (2026-10-18): açık mı + kapalıyken hatalı alan sayısı DEĞER olarak; geri çağrı sabit (sağlayıcı
              // yoksa — eski kullanım — konu hep açık, düğmesiz)
              open={open} collapsible={!!topics} onToggle={onToggle} invalid={topics && !open ? topics.invalidCount(tk) : 0}
              // Kısaltma üsleri (2026-10-18) kartın KENDİ iki değeri — memo başka konunun üssünde yeniden çizmesin
              onUnit={onUnit} errorsUnit={units?.[issueUnitKey(sid, iss.id, 'errors')] ?? null}
              customersUnit={units?.[issueUnitKey(sid, iss.id, 'customers')] ?? null} />
          )
        })}
      </ol>
      {(!readOnly || (topics && list.length > 0)) && (
        <div className="flex flex-wrap items-center gap-2" data-slot="te-issues-toolbar">
          {!readOnly && (
            <Button type="button" variant="outline" className="w-fit pointer-coarse:min-h-10" disabled={list.length >= LIMITS.issues}
              onClick={addTopic} data-slot="te-add-issue">
              <Plus aria-hidden="true" /> {t('te.issues.add')}
            </Button>
          )}
          {/* Yalnız bu bölümün konuları (bölümlerin "Tümünü aç"ından bağımsız); salt okunurda da */}
          {topics && list.length > 0 && (
            <ExpandAllButton allOpen={allTopicsOpen} slot="te-issues-all" expandKey="te.iss.expandAll" collapseKey="te.iss.collapseAll"
              className="h-9 pointer-coarse:min-h-10" onToggle={(o) => (o ? openTopics?.(topicKeys) : closeTopics?.(topicKeys))} />
          )}
        </div>
      )}
    </CollapsibleCard>
  )
}

/**
 * Bir konunun hata anahtarları (istemci `issue:<b>:<k>:`, sunucu `issues.<b>.<k>.` ve eski sunucunun sıra numaralı
 * `issues.<b>.<n>`'i). Kart memo'su bu alt kümeyi değerce karşılaştırır: başka konunun hatası bu kartı çizdirmez.
 */
function cardErrors(errors, sid, iid, idx) {
  const out = {}
  const a = `issue:${sid}:${iid}:`
  const b = `issues.${sid}.${iid}.`
  const legacyKey = `issues.${sid}.${idx + 1}`
  for (const [k, v] of Object.entries(errors || {})) {
    if (v && (k.startsWith(a) || k.startsWith(b) || k === legacyKey)) out[k] = v
  }
  return out
}

function shallowObj(a, b) {
  if (a === b) return true
  const ka = Object.keys(a || {})
  if (ka.length !== Object.keys(b || {}).length) return false
  return ka.every((k) => a[k] === b[k])
}

function shallowArr(a, b) {
  if (a === b) return true
  if (!a || !b || a.length !== b.length) return false
  return a.every((x, i) => x === b[i])
}

/** Kart memo'su: hata alt kümesi ve konunun görselleri DEĞERCE, kalan her şey kimlikçe. */
function sameCardProps(prev, next) {
  for (const k of Object.keys(next)) {
    if (k === 'errors') { if (!shallowObj(prev.errors, next.errors)) return false } else if (k === 'images') {
      if (!shallowArr(prev.images, next.images)) return false
    } else if (prev[k] !== next[k]) return false
  }
  return Object.keys(prev).length === Object.keys(next).length
}

/**
 * Kart içi alan grubu: simgeli başlık + içerik; gruplar arasında ince çizgi. Bölge adı (aria-labelledby) BİLEREK yok: grup
 * başlığı alanın etiketiyle aynı olabilir (açıklama) — etiketle arama iki öğe bulmasın, sayfada 30 adsız bölge oluşmasın.
 * Başlık h5: kartın konu başlığı (h4) altında — "Ne zaman?" hangi konunun olduğu belli olsun (inceleme 2026-10-18).
 */
function Group({ icon: Icon, title, slot, children, first = false, copy = null }) {
  return (
    <section data-slot={slot} className={cn('flex min-w-0 flex-col gap-3', !first && 'border-t pt-4')}>
      <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
        <h5 className="inline-flex items-center gap-2 align-middle text-sm font-semibold">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
          <span>{title}</span>
        </h5>
        {copy ? <CopyTextButton text={copy} /> : null}
      </div>
      {children}
    </section>
  )
}

/** Alan altı hata (sunucu ya da istemci) — rol alert, kimliği girişin aria-describedby'ında. */
function FieldError({ id, text }) {
  if (!text) return null
  return <p id={id} className="m-0 text-sm text-destructive [overflow-wrap:anywhere]" role="alert">{text}</p>
}

/** Boşlukla ayrılmış açıklama kimlikleri (boşlar düşer); hiç yoksa undefined. */
const describedBy = (...ids) => ids.filter(Boolean).join(' ') || undefined

/** Evet / Hayır (shadcn ToggleGroup — rapordaki evet/hayır alanlarıyla aynı). Seçiliye yeniden basmak cevabı siler. */
function YesNo({ labelledBy, value, onChange, disabled, describedBy: desc, invalid, t }) {
  return (
    <ToggleGroup type="single" variant="outline" disabled={disabled} value={value === true ? 'yes' : value === false ? 'no' : ''}
      aria-labelledby={labelledBy} aria-describedby={desc} aria-invalid={invalid || undefined} className="w-fit"
      onValueChange={(x) => onChange(x === 'yes' ? true : x === 'no' ? false : null)}>
      <ToggleGroupItem value="yes" className="px-4 pointer-coarse:min-h-10">{t('te.yes')}</ToggleGroupItem>
      <ToggleGroupItem value="no" className="px-4 pointer-coarse:min-h-10">{t('te.no')}</ToggleGroupItem>
    </ToggleGroup>
  )
}

/**
 * Saat girişi (`type="time"`). Yarım yazılmış saat (tarayıcı değeri boş verir, `validity.badInput`) taslağa girmez: durumu
 * düzenleyiciye söylenir (`PendingInputContext` — otomatik kayıt o alanı kayıtlı değerinde gönderir, "tümü kaydedildi"
 * denmez) ve alan bırakılınca hata olur (inceleme 2026-10-18). Söküldüğünde bildirim kalkar.
 */
function TimeInput({ id, value, disabled, invalid, describedBy: desc, pendingKey, onChange, onIncomplete }) {
  const report = useContext(PendingInputContext)
  const ref = useRef({ report, key: pendingKey })
  ref.current = { report, key: pendingKey }
  useEffect(() => () => {
    const { report: r, key } = ref.current
    if (key) r?.(key, false)
  }, [])
  const check = (el, blur = false) => {
    const half = !!el?.validity?.badInput
    if (pendingKey) report?.(pendingKey, half)
    if (half && blur) onIncomplete(true)
    else if (!half) onIncomplete(false)
  }
  return (
    <Input id={id} type="time" step={60} className="tabular-nums pointer-coarse:h-10" disabled={disabled} value={value || ''}
      aria-invalid={invalid || undefined} aria-describedby={desc}
      onChange={(e) => { check(e.target); onChange(e.target.value || null) }}
      onInput={(e) => check(e.currentTarget)} onKeyUp={(e) => check(e.currentTarget)} onBlur={(e) => check(e.currentTarget, true)} />
  )
}

/** Payın notu: formül ya da neden hesaplanamadığı (sahte %0 yok). */
function shareNote(res, totalLabel, errorsLabel, t) {
  switch (res?.status) {
    case 'OK': return t('te.iss.shareFormula', errorsLabel, totalLabel)
    case 'MISSING': return res.missing === 'errors' ? t('te.iss.shareMissingCount') : t('te.iss.shareMissingTotal', totalLabel)
    case 'ZERO': return t('te.iss.shareZero', totalLabel)
    case 'OVER': return t('te.iss.shareOver', totalLabel)
    case 'EXEMPT': return t('te.iss.shareExempt', totalLabel)
    default: return t('te.iss.shareBadRef')
  }
}

/** Tekil / çoğul (projenin deseni: `<anahtar>.one` yalnız 1 için). */
const plural = (t, key, n, ...rest) => t(Number(n) === 1 ? `${key}.one` : key, ...rest)

const REVEAL = 'animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none'

/*
 * KONU BAŞLIĞI (2026-10-18, katlanır konular): bölüm kartının deseni bir kademe küçük — başlık (h4) içinde satırın tamamını
 * kaplayan aç / kapat düğmesi (WAI akordeon), ok önde; düğmenin erişilebilir adı konu başlığı (aria-labelledby), özet çipleri
 * açıklaması (aria-describedby) — kapalı konu da ne olduğunu söyler. Kopyala / taşı / sil düğmenin DIŞINDA, başlığın kardeşi;
 * çok satıra saran başlıkta ilk satırla hizalı (`TOPIC_SIDE`). Yalnız jeton renkleri.
 */
const TOPIC_TOGGLE = 'h-auto min-h-0 w-full items-start justify-start gap-2 whitespace-normal rounded-lg px-2 py-2 text-left'
  + ' text-sm font-semibold leading-snug text-foreground has-[>svg]:px-2 sm:text-base pointer-coarse:min-h-10'
/** Başlığın ilk satırının yüksekliği: `py-2` × 2 + `leading-snug` satırı (sm+: 16 × 1,375 = 22 px) = 38 px. */
const TOPIC_SIDE = 'flex min-h-[2.375rem] shrink-0 items-center pointer-coarse:min-h-10'

const IssueCard = memo(function IssueCard({ section, template, opts, legacy, issue: iss, index: idx, count, shownFields, resolve,
  uiLang, readOnly, errors, onFieldError, reportId, images, otherBytes, onImagesChange, period, beforeIssueUpload, onPatch, onMove,
  onRemove, onUnit = null, errorsUnit = null, customersUnit = null, open = true, collapsible = false, onToggle = null, invalid = 0 }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const sid = section.id
  const iid = iss.id
  const base = `te-iss-${sid}-${iid}`
  // Katlanır konu: içerik kapalıyken de kurulu kalır (hidden="until-found" — sayfada bul açar)
  const contentRef = useRef(null)
  const onFound = useMemo(() => (collapsible && onToggle ? () => onToggle(iid, true) : null), [collapsible, onToggle, iid])
  useUntilFound(contentRef, open, onFound)
  const pattern = section.item_title
  const shownTitle = issueDisplayTitle(iss, pattern)
  const tplLang = template?.lang === 'en' ? 'en' : 'tr'
  // Sunucu hatası konunun kimliği + alanıyla gelir; eski sunucu sıra numarasıyla (genel hata, açıklamanın altında)
  const err = (f) => errors[issueErrorKey(sid, iid, f)] || errors[`issues.${sid}.${iid}.${f}`] || null
  const legacyErr = errors[`issues.${sid}.${idx + 1}`] || null
  const fieldKey = (f) => issueErrorKey(sid, iid, f)
  const share = issueShare(section, iss, template, shownFields)
  const totalField = opts.ratioOf ? allFields(template).find((f) => f.id === opts.ratioOf) : null
  const totalLabel = resolve(totalField?.label || opts.ratioOf || '')
  // Kısaltmayla girilmiş adet (2026-10-18): "1,25M" çipte ve giriş metninde de "1,25M"; pay SAYIYLA (issueShare)
  const lead = legacy ? '' : issueLeadText(section, iss, share?.status === 'OK' ? share.value : null, tplLang, resolve, errorsUnit, customersUnit)
  const isHidden = issueHidden(section, iss, tplLang)
  const bounds = period ? reportMonthBounds(period.year, period.month) : null
  const myImages = opts.allowImages
    ? Array.from({ length: ISSUE_IMAGES }, (_, k) => images.find((i) => i.slot_id === issueSlotId(sid, iid, k + 1)) || null)
    : []
  const imageCount = images.length
  const patch = (p) => onPatch(iid, p)
  // Görselin erişilebilir adı konuyu da taşır ("Kart Limiti: Hata Artışı — Görsel 1") — iki konunun "Görsel 1"i karışmasın
  const topicLabel = shownTitle || t('te.iss.topicN', idx + 1)

  // Evet seçilince açılan zorunlu / ilgili alana odak (boşsa) — klavye kullanıcısı bir sonraki adımı aramasın
  const outageIdRef = useRef(null)
  const customersRef = useRef(null)
  const [focusNext, setFocusNext] = useState(null)
  useEffect(() => {
    if (!focusNext) return
    const el = focusNext === 'outage_id' ? outageIdRef.current : customersRef.current?.querySelector('input')
    el?.focus?.({ preventScroll: false })
    setFocusNext(null)
  }, [focusNext])

  async function remove() {
    // Görseli olan konu: kayıtta görselleri de kalıcı olarak silinir — sorulur
    if (imageCount > 0) {
      const ok = await showConfirm({ title: t('te.iss.removeTitle'), message: t('te.iss.removeBody', shownTitle || idx + 1, imageCount),
        variant: 'danger', confirmText: t('te.iss.removeConfirm'), cancelText: t('te.cancel') })
      if (!ok) return
    }
    for (const f of ['topic', 'title', 'description', 'customers', 'errors', 'date', 'start', 'end', 'outage_id', 'outage', 'impact',
      'caption1', 'caption2']) onFieldError(fieldKey(f), null)
    onRemove(iid)
  }

  const chips = []
  if (!legacy && opts.askTime) {
    const day = issueDayLabel(iss.date, uiLang)
    const range = issueRangeLabel(iss, uiLang)
    if (day) chips.push({ key: 'when', icon: CalendarClock, text: range ? `${day} · ${range}` : day })
  }
  if (iss.errors != null) {
    const n = formatCount(iss.errors, uiLang, errorsUnit)
    chips.push({ key: 'count', icon: Hash, text: share?.status === 'OK'
      ? plural(t, 'te.iss.chip.countShare', iss.errors, n, formatPercent(share.value, uiLang))
      : plural(t, 'te.iss.chip.count', iss.errors, n) })
  }
  if (!legacy && opts.askOutage && (iss.outage === true || iss.outage === false)) {
    const id = typeof iss.outage_id === 'string' ? iss.outage_id.trim() : ''
    chips.push({ key: 'outage', icon: Zap, tone: iss.outage ? 'warn' : null,
      text: iss.outage ? (id ? t('te.iss.chip.outage', id) : t('te.iss.chip.outageNoId')) : t('te.iss.chip.noOutage') })
  }
  if (!legacy && opts.askImpact && (iss.impact === true || iss.impact === false)) {
    chips.push({ key: 'impact', icon: Users,
      text: iss.impact ? (iss.customers != null ? plural(t, 'te.iss.chip.customers', iss.customers, formatCount(iss.customers, uiLang, customersUnit))
        : t('te.iss.chip.impactYes'))
        : t('te.iss.chip.noImpact') })
  } else if ((legacy || !opts.askImpact) && iss.customers != null) {
    chips.push({ key: 'impact', icon: Users, text: plural(t, 'te.iss.chip.customers', iss.customers, formatCount(iss.customers, uiLang, customersUnit)) })
  }
  if (isHidden) chips.push({ key: 'hidden', icon: EyeOff, tone: 'muted', text: t('te.iss.chip.hidden') })

  const numberField = (key, label, extra = {}) => {
    const fe = err(key)
    const errId = fe ? `${base}-${key}-e` : undefined
    return (
      <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey(key)} ref={key === 'customers' ? customersRef : undefined}
        {...extra}>
        <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
          <label htmlFor={`${base}-${key}`} className="inline text-sm font-medium">{resolve(label)}</label>
          <CopyTextButton text={resolve(label)} />
        </div>
        <NumberInput id={`${base}-${key}`} value={iss[key] ?? null} lang={uiLang} disabled={readOnly}
          invalid={fe || legacyErr ? true : undefined} describedBy={errId || (legacyErr ? `${base}-e` : undefined)}
          // Kısaltma üssü (2026-10-18): "1,25M" kayıtta da kısaltmasıyla kalır; sıfır işareti konu adedinde YOK (karar)
          unit={onUnit ? (key === 'errors' ? errorsUnit : customersUnit) : undefined}
          onChange={(n, exp) => {
            patch({ [key]: n })
            onUnit?.(issueUnitKey(sid, iid, key), n == null ? null : exp)
          }} pendingKey={fieldKey(key)}
          onError={(k) => onFieldError(fieldKey(key), k ? t(k) : null)} />
        <FieldError id={errId} text={fe} />
      </div>
    )
  }

  const shareBlock = opts.ratioOf ? (
    <div className="flex min-w-0 flex-col gap-1.5" data-slot="te-issue-share" data-status={share?.status || 'NONE'}>
      <span id={`${base}-share-l`} className="text-sm font-medium [overflow-wrap:anywhere]">{t('te.iss.share')}</span>
      <div className="flex min-h-9 min-w-0 flex-wrap items-center gap-2 rounded-md border border-dashed bg-muted/40 px-3 py-1.5 pointer-coarse:min-h-10">
        <output aria-labelledby={`${base}-share-l`} aria-describedby={`${base}-share-n`} className="text-base font-semibold tabular-nums"
          data-slot="te-issue-share-value">
          {share?.status === 'OK' ? formatPercent(share.value, uiLang) : '—'}
        </output>
        <Badge variant="secondary" className="rounded-md overflow-visible">{t('te.calc.auto')}</Badge>
      </div>
      <p id={`${base}-share-n`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-issue-share-note">
        {shareNote(share, totalLabel, resolve(section.errors_label), t)}
      </p>
    </div>
  ) : null

  const dateErr = err('date')
  const startErr = err('start')
  const endErr = err('end')
  const idErr = err('outage_id')
  const topicErr = err('topic')
  const titleErr = err('title')
  const hasRange = !!(iss.start && iss.end)
  const incomplete = (f) => (half) => onFieldError(fieldKey(f), half ? t('te.iss.err.timeIncomplete') : null)
  const titleId = `${base}-title-h`
  const chipsId = `${base}-chips`
  const titleText = `${idx + 1}. ${shownTitle || t('te.issues.untitled')}`
  // Özet çipleri: kapalı konunun "hikâyesi" — zaman, adet + pay, kesinti + ID, müşteri etkisi, rapora girmeyecek. Düğmenin içinde
  // (dokunmatikte satırın tamamı hedef) ama yalnız SÖZ İÇERİĞİ (span — düğmede liste olamaz); adları düğmenin açıklaması
  const chipList = chips.length > 0 ? (
    <span id={chipsId} className="flex min-w-0 flex-wrap gap-1.5" data-slot="te-issue-chips">
      <span className="sr-only">{`${t('te.iss.chipsLabel')}: `}</span>
      {chips.map((c) => (
        <Badge key={c.key} variant="outline" data-chip={c.key}
          className={cn('h-auto max-w-full justify-start gap-1 whitespace-normal rounded-md py-1 text-left text-xs font-normal leading-snug overflow-visible [overflow-wrap:anywhere]',
            c.tone === 'warn' && 'border-amber-300 text-amber-900 dark:border-amber-800 dark:text-amber-200',
            c.tone === 'muted' && 'text-muted-foreground')}>
          <c.icon aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="min-w-0">{c.text}</span>
        </Badge>
      ))}
    </span>
  ) : null
  const titleBody = (
    <span className="flex min-w-0 flex-1 flex-col gap-1.5">
      <span id={titleId} className="min-w-0 [overflow-wrap:anywhere]" data-slot="te-issue-title">{titleText}</span>
      {chipList}
    </span>
  )
  const actions = !readOnly && (
    <div className={cn(TOPIC_SIDE, 'ms-auto gap-0.5')} data-slot="te-issue-actions">
      <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" disabled={idx === 0}
        aria-label={t('te.moveUp', shownTitle || idx + 1)} onClick={() => onMove(iid, -1)}><ArrowUp aria-hidden="true" /></Button>
      <Button type="button" size="icon" variant="ghost" className="size-8 pointer-coarse:size-10" disabled={idx === count - 1}
        aria-label={t('te.moveDown', shownTitle || idx + 1)} onClick={() => onMove(iid, 1)}><ArrowDown aria-hidden="true" /></Button>
      <Button type="button" size="icon" variant="ghost" className="size-8 text-destructive pointer-coarse:size-10"
        aria-label={t('te.issues.remove', shownTitle || idx + 1)} onClick={remove} data-slot="te-issue-remove">
        <Trash2 aria-hidden="true" />
      </Button>
    </div>
  )
  // Başlık satırı: h4 (satırın kalanı) + kopyalama ikonu aynı `te-copy-row` kabında (ikon başlığın KARDEŞİ), sağda taşı / sil.
  // Dar kartta (telefon, 768 px'te iki sütunlu düzen — dokunmatikte dört 40 px düğme) taşı / sil ALT satıra sarar, sağa yaslı:
  // başlık düğmesi en az 16rem'i korur (e2e: telefonda başlık 122 px'e sıkışıyordu)
  const headerRow = (heading) => (
    <div className="flex flex-wrap items-start gap-1 p-1.5" data-slot="te-issue-header">
      <div data-slot="te-copy-row" className="flex min-w-0 grow basis-64 items-start">
        {heading}
        <CopyTextButton text={shownTitle} className={TOPIC_SIDE} />
      </div>
      {actions}
    </div>
  )
  const body = (
    <>
      <div className="flex flex-col gap-4 border-t px-3 pb-3 pt-4 sm:px-4 sm:pb-4">
        <Group icon={Tag} title={t('te.iss.groupTopic')} slot="te-issue-topic" first>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('topic')}>
              <label htmlFor={`${base}-topic`} className="text-sm font-medium">{t('te.issues.topic')}</label>
              <Input id={`${base}-topic`} className="pointer-coarse:h-10" value={iss.topic || ''} disabled={readOnly} maxLength={LIMITS.topic}
                placeholder={t('te.issues.topicPlaceholder')} aria-invalid={topicErr ? true : undefined}
                aria-describedby={describedBy(topicErr && `${base}-topic-e`)}
                onChange={(e) => {
                  const topic = e.target.value
                  patch((cur) => (cur.title_auto !== false ? { topic, title: resolveIssueTitle(pattern, topic), title_auto: true } : { topic }))
                }} />
              <FieldError id={`${base}-topic-e`} text={topicErr} />
            </div>
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('title')}>
              <div className="flex flex-wrap items-center justify-between gap-1">
                <label htmlFor={`${base}-title`} className="text-sm font-medium">{t('te.issues.title')}</label>
                {iss.title_auto !== false ? (
                  <Badge variant="secondary" className="rounded-md overflow-visible">{t('te.issues.auto')}</Badge>
                ) : !readOnly && (
                  <Button type="button" size="sm" variant="ghost" className="h-7 px-2 text-xs pointer-coarse:h-10"
                    onClick={() => patch((cur) => ({ title: resolveIssueTitle(pattern, cur.topic), title_auto: true }))}>
                    <Wand2 aria-hidden="true" /> {t('te.issues.fromTopic')}
                  </Button>
                )}
              </div>
              <Input id={`${base}-title`} className="pointer-coarse:h-10" disabled={readOnly} maxLength={LIMITS.label}
                value={iss.title_auto !== false ? resolveIssueTitle(pattern, iss.topic) : (iss.title || '')}
                aria-invalid={titleErr ? true : undefined} aria-describedby={describedBy(titleErr && `${base}-title-e`)}
                onChange={(e) => patch({ title: e.target.value, title_auto: false })} />
              <FieldError id={`${base}-title-e`} text={titleErr} />
            </div>
          </div>
        </Group>

        {!legacy && opts.askTime && (
          <Group icon={CalendarClock} title={resolve(opts.timeLabel)} copy={resolve(opts.timeLabel)} slot="te-issue-when">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_minmax(0,1fr)]">
              <div className="col-span-2 flex min-w-0 flex-col gap-1.5 sm:col-span-1" data-field={fieldKey('date')}>
                <label htmlFor={`${base}-date`} className="text-sm font-medium">{t('te.iss.date')}</label>
                <DateTimeField id={`${base}-date`} dateOnly clearable disabled={readOnly} value={iss.date || ''}
                  min={bounds?.first} max={bounds?.last} placeholder={t('te.iss.datePlaceholder')}
                  invalid={dateErr ? true : undefined} describedBy={dateErr ? `${base}-date-e` : `${base}-when-h`}
                  onChange={(v) => patch({ date: v || null })} />
                <FieldError id={`${base}-date-e`} text={dateErr} />
              </div>
              <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('start')}>
                <label htmlFor={`${base}-start`} className="text-sm font-medium">{t('te.iss.start')}</label>
                <TimeInput id={`${base}-start`} disabled={readOnly} value={iss.start} invalid={!!startErr}
                  describedBy={describedBy(startErr && `${base}-start-e`)} pendingKey={fieldKey('start')}
                  onIncomplete={incomplete('start')} onChange={(v) => patch({ start: v })} />
                <FieldError id={`${base}-start-e`} text={startErr} />
              </div>
              <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('end')}>
                <label htmlFor={`${base}-end`} className="text-sm font-medium">{t('te.iss.end')}</label>
                <TimeInput id={`${base}-end`} disabled={readOnly} value={iss.end} invalid={!!endErr}
                  describedBy={describedBy(endErr && `${base}-end-e`)} pendingKey={fieldKey('end')}
                  onIncomplete={incomplete('end')} onChange={(v) => patch({ end: v })} />
                <FieldError id={`${base}-end-e`} text={endErr} />
              </div>
            </div>
            <div className="flex items-start gap-2.5">
              {/* Ertesi gün yalnız bir saat ARALIĞIYLA anlamlı: iki saat girilene kadar kapalı (inceleme 2026-10-18) */}
              <Checkbox id={`${base}-next`} className="mt-0.5" checked={!!iss.end_next_day && hasRange} disabled={readOnly || !hasRange}
                aria-describedby={`${base}-next-h`} onCheckedChange={(c) => patch({ end_next_day: c === true })} />
              <div className="flex min-w-0 flex-col gap-0.5">
                <label htmlFor={`${base}-next`} className={cn('text-sm font-medium leading-snug', !hasRange && 'text-muted-foreground')}>
                  {t('te.iss.nextDay')}
                </label>
                <p id={`${base}-next-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
                  {hasRange ? t('te.iss.nextDayHint') : t('te.iss.nextDayNeedsTimes')}
                </p>
              </div>
            </div>
            <p id={`${base}-when-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {period ? t('te.iss.whenHint', `${period.monthName || ''} ${period.year || ''}`.trim()) : t('te.iss.whenHintPlain')}
            </p>
          </Group>
        )}

        <Group icon={Hash} title={t('te.iss.groupCount')} slot="te-issue-count">
          <div className={cn('grid gap-3', (shareBlock || (legacy || !opts.askImpact)) && 'sm:grid-cols-2')}>
            {numberField('errors', section.errors_label)}
            {shareBlock}
            {(legacy || !opts.askImpact) && numberField('customers', section.customers_label)}
          </div>
        </Group>

        {!legacy && opts.askOutage && (
          <Group icon={Zap} title={t('te.iss.groupOutage')} slot="te-issue-outage">
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('outage')}>
              <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                <span id={`${base}-outage-l`} className="inline text-sm font-medium">{resolve(opts.outageLabel)}</span>
                <CopyTextButton text={resolve(opts.outageLabel)} />
              </div>
              <YesNo labelledBy={`${base}-outage-l`} value={iss.outage} disabled={readOnly} t={t}
                invalid={err('outage') ? true : undefined} describedBy={err('outage') ? `${base}-outage-e` : undefined}
                onChange={(v) => {
                  patch({ outage: v })
                  if (v === true && !(iss.outage_id || '').trim()) setFocusNext('outage_id')
                  if (v !== true) onFieldError(fieldKey('outage_id'), null)
                }} />
              <FieldError id={`${base}-outage-e`} text={err('outage')} />
            </div>
            {iss.outage === true && (
              <div className={cn('flex min-w-0 flex-col gap-1.5 sm:max-w-md', REVEAL)} data-field={fieldKey('outage_id')} data-slot="te-issue-outage-id">
                <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                  <label htmlFor={`${base}-oid`} className="inline text-sm font-medium">{resolve(opts.outageIdLabel)}</label>
                  <span aria-hidden="true" className="req-star ms-0.5">*</span>
                  <CopyTextButton text={resolve(opts.outageIdLabel)} />
                </div>
                <Input ref={outageIdRef} id={`${base}-oid`} className="pointer-coarse:h-10" disabled={readOnly} maxLength={ISSUE_LIMITS.outageId}
                  value={iss.outage_id || ''} aria-required="true" aria-invalid={idErr ? true : undefined}
                  aria-describedby={describedBy(`${base}-oid-h`, idErr && `${base}-oid-e`)}
                  placeholder={t('te.iss.outageIdPlaceholder')}
                  onChange={(e) => patch({ outage_id: e.target.value === '' ? null : e.target.value })} />
                <p id={`${base}-oid-h`} className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.iss.outageIdHint', ISSUE_LIMITS.outageId)}</p>
                <FieldError id={`${base}-oid-e`} text={idErr} />
              </div>
            )}
          </Group>
        )}

        {!legacy && opts.askImpact && (
          <Group icon={Users} title={t('te.iss.groupImpact')} slot="te-issue-impact">
            <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('impact')}>
              <div data-slot="te-copy-row" className="text-sm leading-snug [overflow-wrap:anywhere]">
                <span id={`${base}-impact-l`} className="inline text-sm font-medium">{resolve(opts.impactLabel)}</span>
                <CopyTextButton text={resolve(opts.impactLabel)} />
              </div>
              <YesNo labelledBy={`${base}-impact-l`} value={iss.impact} disabled={readOnly} t={t}
                invalid={err('impact') ? true : undefined} describedBy={err('impact') ? `${base}-impact-e` : undefined}
                onChange={(v) => {
                  patch({ impact: v })
                  if (v === true && iss.customers == null) setFocusNext('customers')
                  if (v !== true) onFieldError(fieldKey('customers'), null)
                }} />
              <FieldError id={`${base}-impact-e`} text={err('impact')} />
            </div>
            {iss.impact === true && (
              <div className={cn('grid gap-3 sm:max-w-md', REVEAL)} data-slot="te-issue-customers">
                {numberField('customers', section.customers_label)}
              </div>
            )}
            {iss.impact === false && (
              <p className={cn('m-0 flex items-start gap-2 rounded-md border border-dashed p-2.5 text-sm', REVEAL,
                isHidden ? 'text-amber-900 dark:text-amber-200' : 'text-muted-foreground')}
                data-slot={isHidden ? 'te-issue-hidden-note' : 'te-issue-noimpact-note'} role="status">
                <EyeOff aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
                <span className="[overflow-wrap:anywhere]">{t(isHidden ? 'te.iss.noImpactTopic' : 'te.iss.noImpactCount')}</span>
              </p>
            )}
          </Group>
        )}

        {!legacy && (
          <div className="flex min-w-0 flex-col gap-1.5 rounded-lg border bg-muted/30 p-3" data-slot="te-issue-lead">
            <p className="m-0 inline-flex items-center gap-1.5 text-xs font-semibold text-muted-foreground">
              <ScrollText aria-hidden="true" className="size-3.5" /> {t('te.iss.lead')}
            </p>
            {lead ? <RichText text={lead} className="text-sm" slot="te-issue-lead-text" />
              : <p className="m-0 text-sm text-muted-foreground">{t('te.iss.leadEmpty')}</p>}
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {isHidden ? t('te.iss.leadHidden') : t('te.iss.leadHint')}
            </p>
          </div>
        )}

        <Group icon={FileText} title={resolve(section.description_label)} copy={resolve(section.description_label)} slot="te-issue-desc">
          <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey('description')}>
            <label htmlFor={`${base}-desc`} className="sr-only">{resolve(section.description_label)}</label>
            <RichTextarea id={`${base}-desc`} rows={4} value={iss.description || ''} disabled={readOnly} maxLength={LIMITS.longText}
              placeholder={t('te.issues.descriptionPlaceholder')} label={resolve(section.description_label)}
              invalid={err('description') || legacyErr ? true : undefined}
              describedBy={err('description') ? `${base}-description-e` : legacyErr ? `${base}-e` : undefined}
              onChange={(x) => patch({ description: x })} />
            <FieldError id={`${base}-description-e`} text={err('description')} />
          </div>
        </Group>

        {!legacy && opts.allowImages && (
          <Group icon={ImageIcon} title={t('te.iss.groupImages')} slot="te-issue-images">
            <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.iss.imagesHint')}</p>
            <div className="flex min-w-0 flex-col gap-3">
              {myImages.map((img, k) => {
                // İkinci yuva ilki dolunca açılır (boş iki kutu kartı uzatmasın); dolu ikinci yuva her zaman görünür
                if (k > 0 && !myImages[k - 1] && !img) return null
                const n = k + 1
                const slotId = issueSlotId(sid, iid, n)
                const label = t('te.iss.imageN', n)
                const capErr = err(`caption${n}`)
                const capKey = String(n)
                return (
                  <div key={slotId} className="flex min-w-0 flex-col gap-2" data-slot="te-issue-image" data-n={n}>
                    <ImageSlotTile slot={{ id: slotId, label, width: 'full' }} image={img} reportId={reportId} resolve={(x) => x}
                      uiLang={uiLang} readOnly={readOnly} onImagesChange={onImagesChange} hideWidth contextLabel={topicLabel}
                      otherBytes={() => otherBytes(slotId)}
                      // Görsel silinince açıklaması da taslaktan düşer (inceleme 2026-10-18: boş yuvanın açıklaması kayıtta kalıyordu)
                      onRemoved={() => {
                        onFieldError(fieldKey(`caption${n}`), null)
                        patch((cur) => {
                          if (!cur.captions || !(capKey in cur.captions)) return {}
                          const captions = { ...cur.captions }
                          delete captions[capKey]
                          return { captions: Object.keys(captions).length ? captions : null }
                        })
                      }}
                      beforeUpload={beforeIssueUpload ? () => beforeIssueUpload(sid, iid) : null} />
                    {img && (
                      <div className="flex min-w-0 flex-col gap-1.5" data-field={fieldKey(`caption${n}`)}>
                        <label htmlFor={`${base}-cap${n}`} className="text-sm font-medium">{t('te.iss.caption', n)}</label>
                        <Input id={`${base}-cap${n}`} className="pointer-coarse:h-10" disabled={readOnly} maxLength={ISSUE_LIMITS.caption}
                          value={iss.captions?.[capKey] || ''} placeholder={t('te.iss.captionPlaceholder')}
                          aria-invalid={capErr ? true : undefined} aria-describedby={describedBy(capErr && `${base}-cap${n}-e`)}
                          onChange={(e) => {
                            const value = e.target.value
                            patch((cur) => {
                              const captions = { ...(cur.captions || {}) }
                              if (value === '') delete captions[capKey]
                              else captions[capKey] = value
                              return { captions: Object.keys(captions).length ? captions : null }
                            })
                          }} />
                        <FieldError id={`${base}-cap${n}-e`} text={capErr} />
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </Group>
        )}
      </div>
      {legacyErr && <p id={`${base}-e`} className="m-0 px-3 pb-3 text-sm text-destructive sm:px-4" role="alert">{legacyErr}</p>}
    </>
  )
  const liProps = {
    className: cn('rounded-xl border bg-card', isHidden && 'border-dashed'),
    'data-slot': 'te-issue',
    'data-issue': iid,
    'data-hidden': isHidden ? 'true' : undefined,
    'data-field': `issues.${sid}.${idx + 1}`,
  }
  // Sağlayıcısız (katlanma yok): eski düzen — başlık h4, içerik hep açık
  if (!collapsible) {
    return (
      <li {...liProps}>
        {headerRow(
          <h4 className="m-0 flex min-w-0 flex-1 items-start px-2 py-2 text-sm font-semibold leading-snug sm:text-base">{titleBody}</h4>,
        )}
        {body}
      </li>
    )
  }
  return (
    <Collapsible open={open} onOpenChange={(o) => onToggle?.(iid, o)} asChild>
      <li {...liProps} data-open={open ? 'true' : 'false'}>
        {/* Konu başlığı bir BAŞLIK (h4): kart içindeki grup başlıkları (h5) bağlamını buradan alır — inceleme 2026-10-18 */}
        {headerRow(
          <h4 className="m-0 min-w-0 flex-1 text-sm font-semibold leading-snug sm:text-base">
            <CollapsibleTrigger asChild>
              <Button type="button" variant="ghost" className={TOPIC_TOGGLE} data-slot="te-issue-toggle"
                aria-labelledby={titleId} aria-describedby={chipList ? chipsId : undefined}>
                <ChevronRight aria-hidden="true"
                  className={cn('mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none sm:mt-[3px]',
                    open && 'rotate-90')} />
                {titleBody}
              </Button>
            </CollapsibleTrigger>
          </h4>,
        )}
        {/* Kapalı konunun hatalı alan sayısı (bölüm rozetinin aynısı); başlık METNİYLE hizalı: satır 6 + düğme 8 + ok 16 + aralık 8 px */}
        {invalid > 0 && !open && (
          <div className="pb-3 pe-3 ps-[2.375rem] sm:pe-4">
            <Badge variant="destructive" className="w-fit rounded-md overflow-visible whitespace-normal" data-slot="te-issue-invalid">
              {t('te.sec.invalid', invalid)}
            </Badge>
          </div>
        )}
        <CollapsibleContent forceMount ref={contentRef} data-slot="te-issue-content"
          className={supportsUntilFound() ? undefined : 'data-[state=closed]:hidden'}>
          {body}
        </CollapsibleContent>
      </li>
    </Collapsible>
  )
}, sameCardProps)
