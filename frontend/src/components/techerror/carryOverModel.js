import { foldSearchText } from '../../utils/monitorFilters.js'
import { LIMITS, allFields, conformIssue, fieldExempt, issueDisplayTitle, newIssue, resolveTextPlaceholders, richBlank } from './techErrorModel.js'

/**
 * GEÇEN AYDAN GETİR (2026-11-20, C1 — "her ay aynı metni yeniden yazmayalım"): önceki ayın raporundan bu aya TAŞINABİLECEK
 * olanların planı ve uygulaması — saf işlevler (tablo sınamalı). Kurallar:
 *   - Metin bölümleri ile gösterge bölümlerinin kısa metin / açıklama (textarea) alanları: AYNI kimlik ve AYNI tür bu
 *     şablonda varsa ve bu ayın alanı ŞU AN BOŞSA getirilir; dolu alana ASLA yazılmaz (yazılmış metin ezilmez) — getirilemeyen
 *     her öğe nedeniyle listelenir. Metin bölümünde "boş" = anahtar YOK ya da şablonun (bu ayla çözülmüş) varsayılan metni;
 *     KAYITLI boş metin (`''`) bilerek boşaltılmış bölümdür (PDF / e-postada basılmaz) — getirilmez (`emptied`).
 *   - Anomali konuları seçmeli liste: yalnız ad, başlık, açıklama, kesinti cevabı + Kesinti ID ve müşteri etkisi cevabı
 *     taşınır. Adetler, tarih / saat, pay, görseller ve olay kaydı bağları ASLA taşınmaz. Yeni kimlik (`newIssue` kuralı),
 *     bu bölümün sorularına uydurulur (`conformIssue`), bölüm sınırına (30) kadar eklenir — sığmayan sayılır. Bu ayın
 *     taslağında aynı ad + açıklamalı konu (Türkçe katlanmış) varsa önerilmez (`exists` — ikinci kez eklenmesin).
 *   - Sayılar, tablo hücreleri, özet metni (elle düzenleme), hedefler ve birimler hiç getirilmez.
 * Sonuç taslağa gider; olağan otomatik kayıt / Kaydet onu kaydeder.
 */

/** Taşınan konu alanları (sırası sabit). Başka anahtar (adet, tarih, saat, pay, açıklama görseli…) asla taşınmaz. */
export const CARRY_TOPIC_KEYS = Object.freeze(['topic', 'title', 'title_auto', 'description', 'outage', 'outage_id', 'impact'])
/** Getirilebilen gösterge alanı türleri. */
export const CARRY_FIELD_TYPES = Object.freeze(['text', 'textarea'])
/** Getirilemeyen öğenin nedenleri (kalıcı; arayüz `te.carry.reason.<kod>`). */
export const CARRY_REASONS = Object.freeze(['filled', 'emptied', 'exempt', 'typeMismatch', 'notInTemplate', 'noSection', 'exists'])

const isBlank = (v) => v == null || (typeof v === 'string' && richBlank(v))
const has = (o, k) => o != null && Object.prototype.hasOwnProperty.call(o, k)

/**
 * Getirilecek raporun kimliği: tam önceki ay (`previous`), yoksa aynı kanalın en yakın önceki raporu (`neighbors.prev`).
 * `state`: `ok` (kimlik var), `none` (sunucu ikisini de söyledi, önceki rapor yok), `unknown` (eski sunucu / alan yok —
 * "yok" denmez).
 */
export function carrySource(data) {
  if (!data) return { state: 'unknown' }
  const p = data.previous
  if (p && p.id != null) return { state: 'ok', id: p.id, year: p.year ?? null, month: p.month ?? null, exact: true }
  const n = data.neighbors?.prev
  if (n && n.id != null) return { state: 'ok', id: n.id, year: n.year ?? null, month: n.month ?? null, exact: false }
  return { state: has(data, 'previous') && has(data, 'neighbors') ? 'none' : 'unknown' }
}

/**
 * Metin bölümü şu an boş mu: anahtar hiç yok ya da şablonun (bu ayla çözülmüş) varsayılan metni. KAYITLI boş / boşluk metin
 * bilerek boşaltılmış bölümdür (basılmaz) — boş SAYILMAZ (inceleme 2026-11-20).
 */
export function textSectionEmpty(stored, resolvedDefault) {
  return stored == null || (resolvedDefault != null && stored === resolvedDefault)
}

/** Konunun eşlik anahtarı: Türkçe katlanmış ad + açıklama (ikisi de boşsa elle başlık); hiçbiri yoksa null. */
export function topicKey(issue) {
  if (!issue || typeof issue !== 'object') return null
  const f = (v) => (typeof v === 'string' ? foldSearchText(v).replace(/\s+/g, ' ').trim() : '')
  const name = f(issue.topic)
  const desc = f(issue.description)
  if (name || desc) return JSON.stringify(['n', name, desc])
  const title = issue.title_auto === false ? f(issue.title) : ''
  return title ? JSON.stringify(['t', title]) : null
}

/** Konunun taşınan kısmı (yalnız {@link CARRY_TOPIC_KEYS}, tür denetimli). */
export function carryTopicPart(issue) {
  const x = {}
  if (!issue || typeof issue !== 'object') return x
  for (const k of ['topic', 'title', 'description']) if (typeof issue[k] === 'string') x[k] = issue[k]
  if (typeof issue.title_auto === 'boolean') x.title_auto = issue.title_auto
  if (typeof issue.outage === 'boolean') x.outage = issue.outage
  if (x.outage === true && typeof issue.outage_id === 'string') x.outage_id = issue.outage_id
  if (typeof issue.impact === 'boolean') x.impact = issue.impact
  return x
}

/** Taşınacak bir şeyi var mı (ad, elle başlık ya da açıklama)? Yalnız cevaplı boş konu getirilmez. */
function topicHasContent(part) {
  return !isBlank(part.topic) || (part.title_auto === false && !isBlank(part.title)) || !isBlank(part.description)
}

/**
 * Plan. `prev` = `{ template, values, year, month }` (önceki raporun KENDİ şablon kopyası), `cur` = `{ template, values, year,
 * month }`, `channelName` = varsayılan metinlerin yer tutucuları için. Dönüş:
 *   `texts` / `fields`: getirilebilecekler `{ id, label, value, type? }`; `topics`: `{ key, prevSectionId, targetSectionId,
 *   name, part, status: 'ok'|'skip', reason }`; `skipped`: `{ kind, id, label, reason }` (önceki ayda içerik VARDI ama
 *   getirilemiyor).
 */
export function planCarryOver({ prev, cur, channelName = '' }) {
  const prevTpl = prev?.template || {}
  const curTpl = cur?.template || {}
  const pv = prev?.values || {}
  const cv = cur?.values || {}
  const out = { texts: [], fields: [], topics: [], skipped: [] }
  const curSections = curTpl.sections || []
  const curById = new Map(curSections.map((s) => [s.id, s]))

  // Metin bölümleri
  for (const ps of prevTpl.sections || []) {
    if (ps.kind !== 'text') continue
    const v = pv.texts?.[ps.id]
    if (typeof v !== 'string' || isBlank(v)) continue
    // Geçen ayın VARSAYILAN metni (kimse yazmamış) getirilmez — ay adları eski kalırdı
    const prevDefault = resolveTextPlaceholders(ps.text, channelName, prev?.year, prev?.month, prevTpl.lang)
    if (v === prevDefault) continue
    const cs = curById.get(ps.id)
    if (!cs) { out.skipped.push({ kind: 'text', id: ps.id, label: ps.title, reason: 'notInTemplate' }); continue }
    if (cs.kind !== 'text') { out.skipped.push({ kind: 'text', id: ps.id, label: cs.title ?? ps.title, reason: 'typeMismatch' }); continue }
    const curDefault = resolveTextPlaceholders(cs.text, channelName, cur?.year, cur?.month, curTpl.lang)
    if (!textSectionEmpty(cv.texts?.[cs.id], curDefault)) {
      const stored = cv.texts?.[cs.id]
      out.skipped.push({ kind: 'text', id: cs.id, label: cs.title, reason: typeof stored === 'string' && isBlank(stored) ? 'emptied' : 'filled' })
      continue
    }
    out.texts.push({ id: cs.id, label: cs.title, value: v })
  }

  // Gösterge bölümlerinin kısa metin / açıklama alanları
  const curFields = new Map(allFields(curTpl).map((f) => [f.id, f]))
  for (const pf of allFields(prevTpl)) {
    if (!CARRY_FIELD_TYPES.includes(pf.type)) continue
    const v = pv.fields?.[pf.id]
    if (typeof v !== 'string' || isBlank(v)) continue
    const cf = curFields.get(pf.id)
    if (!cf) { out.skipped.push({ kind: 'field', id: pf.id, label: pf.label, reason: 'notInTemplate' }); continue }
    if (cf.type !== pf.type) { out.skipped.push({ kind: 'field', id: cf.id, label: cf.label, reason: 'typeMismatch' }); continue }
    if (fieldExempt(cf, cv.fields)) { out.skipped.push({ kind: 'field', id: cf.id, label: cf.label, reason: 'exempt' }); continue }
    if (!isBlank(cv.fields?.[cf.id])) { out.skipped.push({ kind: 'field', id: cf.id, label: cf.label, reason: 'filled' }); continue }
    out.fields.push({ id: cf.id, label: cf.label, value: v, type: cf.type })
  }

  // Anomali konuları: aynı kimlikli konu bölümü, yoksa bu şablonda TEK konu bölümü varsa o
  const curIssueSections = curSections.filter((s) => s.kind === 'issues')
  for (const ps of prevTpl.sections || []) {
    if (ps.kind !== 'issues') continue
    const target = curIssueSections.find((s) => s.id === ps.id) || (curIssueSections.length === 1 ? curIssueSections[0] : null)
    // Bu ayın aynı bölümündeki konular (ad + açıklama, katlanmış) — aynısı ikinci kez önerilmez
    const present = new Set((Array.isArray(cv.issues?.[target?.id]) ? cv.issues[target.id] : []).map(topicKey).filter(Boolean))
    for (const pi of Array.isArray(pv.issues?.[ps.id]) ? pv.issues[ps.id] : []) {
      const part = carryTopicPart(pi)
      if (!topicHasContent(part)) continue
      const exists = !!target && present.has(topicKey(part))
      out.topics.push({
        key: `${ps.id}:${pi?.id ?? out.topics.length}`,
        prevSectionId: ps.id,
        targetSectionId: target?.id ?? null,
        name: issueDisplayTitle(pi, ps.item_title) || (typeof pi?.topic === 'string' ? pi.topic.trim() : ''),
        part,
        status: target && !exists ? 'ok' : 'skip',
        reason: !target ? 'noSection' : exists ? 'exists' : null,
      })
    }
  }
  return out
}

/** Getirilecek bir şey var mı (metin, alan ya da hedefi olan konu)? */
export function planHasItems(plan) {
  return !!plan && (plan.texts.length > 0 || plan.fields.length > 0 || plan.topics.some((x) => x.status === 'ok'))
}

/**
 * Seçilen konular için YENİ kimlikler (taslağa yazmadan ÖNCE, bir kez üretilir — işlevsel güncelleme iki kez çağrılsa da
 * aynı kimlikler kullanılır). `Map<konu anahtarı, kimlik>`.
 */
export function carryTopicIds(values, plan, topicKeys) {
  const ids = new Map()
  const used = []
  for (const list of Object.values(values?.issues || {})) for (const i of Array.isArray(list) ? list : []) if (i?.id) used.push({ id: i.id })
  for (const tp of plan?.topics || []) {
    if (tp.status !== 'ok' || !topicKeys.has(tp.key)) continue
    const fresh = newIssue(used)
    used.push({ id: fresh.id })
    ids.set(tp.key, fresh.id)
  }
  return ids
}

/**
 * Seçilenleri `values` üzerine uygular — boşluk denetimi BURADA yeniden yapılır (plan ile uygulama arasında yazılmış metin
 * ezilmez). `picks` = `{ texts: Set, fields: Set, topics: Set }`, `ids` = {@link carryTopicIds}. Dönüş `{ values, counts:
 * { texts, fields, topics, notFit, stale }, added: [{ sectionId, issueId }] }`; hiçbir şey değişmezse AYNI `values`.
 */
export function applyCarryOver(values, plan, picks, { template, ids, year, month, channelName = '' } = {}) {
  const counts = { texts: 0, fields: 0, topics: 0, notFit: 0, stale: 0 }
  const added = []
  if (!values || !plan) return { values, counts, added }
  const sections = template?.sections || []
  const byId = new Map(sections.map((s) => [s.id, s]))
  let texts = values.texts
  let fields = values.fields
  let issues = values.issues

  for (const it of plan.texts) {
    if (!picks?.texts?.has(it.id)) continue
    const cs = byId.get(it.id)
    const curDefault = cs ? resolveTextPlaceholders(cs.text, channelName, year, month, template?.lang) : null
    if (!cs || cs.kind !== 'text' || !textSectionEmpty(texts?.[it.id], curDefault)) { counts.stale++; continue }
    texts = { ...(texts || {}), [it.id]: it.value }
    counts.texts++
  }

  const curFields = new Map(allFields(template).map((f) => [f.id, f]))
  for (const it of plan.fields) {
    if (!picks?.fields?.has(it.id)) continue
    const cf = curFields.get(it.id)
    if (!cf || cf.type !== it.type || fieldExempt(cf, fields) || !isBlank(fields?.[it.id])) { counts.stale++; continue }
    fields = { ...(fields || {}), [it.id]: it.value }
    counts.fields++
  }

  for (const tp of plan.topics) {
    if (tp.status !== 'ok' || !picks?.topics?.has(tp.key)) continue
    const section = byId.get(tp.targetSectionId)
    const id = ids?.get(tp.key)
    if (!section || section.kind !== 'issues' || !id) { counts.stale++; continue }
    const list = Array.isArray(issues?.[section.id]) ? issues[section.id] : []
    if (list.length >= LIMITS.issues) { counts.notFit++; continue }
    if (list.some((i) => i?.id === id)) { counts.stale++; continue }
    // Aynı konu arada eklendiyse (ikinci pencere, aynı seçim iki kez) ikinci kez eklenmez
    const k = topicKey(tp.part)
    if (k && list.some((i) => topicKey(i) === k)) { counts.stale++; continue }
    const base = { id, topic: '', title: '', title_auto: true, description: '', customers: null, errors: null }
    const issue = conformIssue(section, { ...base, ...tp.part })
    issues = { ...(issues || {}), [section.id]: [...list, issue] }
    added.push({ sectionId: section.id, issueId: id })
    counts.topics++
  }

  if (texts === values.texts && fields === values.fields && issues === values.issues) return { values, counts, added }
  return { values: { ...values, texts, fields, issues }, counts, added }
}

/** Bölüm başına seçilen konulardan kaçı sınıra (30) sığmaz — pencerede önceden söylenir. */
export function carryOverflow(values, plan, topicKeys) {
  const room = new Map()
  let over = 0
  for (const tp of plan?.topics || []) {
    if (tp.status !== 'ok' || !topicKeys?.has(tp.key)) continue
    const sid = tp.targetSectionId
    if (!room.has(sid)) {
      const list = Array.isArray(values?.issues?.[sid]) ? values.issues[sid] : []
      room.set(sid, Math.max(0, LIMITS.issues - list.length))
    }
    const r = room.get(sid)
    if (r > 0) room.set(sid, r - 1)
    else over++
  }
  return over
}
