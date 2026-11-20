import { foldSearchText } from '../../utils/monitorFilters.js'

/**
 * GÖRSEL YUVALARINI DOLDURMA YARDIMCILARI (2026-11-20, C2 / C4) — saf işlevler, tablo sınamalı.
 *
 * C2 "Sıralı doldur": açıkken sıradaki BOŞ yuva şablon sırasıyla bulunur (yalnız raporun görsel bölümlerinin yuvaları; konu
 * görselleri değil); pencere düzeyindeki Ctrl/⌘+V oraya yükler, yükleme bitince odak sıradaki boş yuvaya geçer. Dolu yuva
 * hiçbir zaman hedef olmaz (değiştirme yok).
 *
 * C4 toplu bırakma: bölüme bırakılan dosyalar yuvalara eşlenir — önce dosya adındaki yuva kimliği (ekrandan / panodan alınan
 * dosyanın adı `<yuva>-ekran-…` / `<yuva>-pano-…`), sonra yuva adıyla (Türkçe katlanmış) eşleşme, sonra sıradaki boş yuva.
 * Varsayılan YALNIZ BOŞ yuva seçer; eşleşen yuva doluysa dosya "atla" ile gelir ve neden söylenir (kullanıcı açıkça dolu
 * yuvayı seçebilir — değiştirme onayı sorulur).
 */

/** "Sıralı doldur" tercihi (kişisel kayıt — çıkışta silinir: utils/personalStorage PERSONAL_PREFIXES). */
export const SEQFILL_KEY = 'sm.te.seqfill'

export function readSeqFill() {
  try { return globalThis.localStorage?.getItem(SEQFILL_KEY) === '1' } catch { return false }
}

export function writeSeqFill(on) {
  try {
    if (on) globalThis.localStorage?.setItem(SEQFILL_KEY, '1')
    else globalThis.localStorage?.removeItem(SEQFILL_KEY)
  } catch { /* depolama yok / dolu */ }
}

/** Raporun görsel yuvaları şablon sırasıyla: `{ id, label, sectionId, width }` (aynı kimlik bir kez). */
export function reportSlotOrder(sections) {
  const out = []
  const seen = new Set()
  for (const s of sections || []) {
    if (s?.kind !== 'images') continue
    for (const slot of s.slots || []) {
      if (!slot?.id || seen.has(slot.id)) continue
      seen.add(slot.id)
      out.push({ id: slot.id, label: slot.label, sectionId: s.id, width: slot.width })
    }
  }
  return out
}

/**
 * Sıradaki boş yuva: `afterId`'den SONRAKİ ilk boş yuva (yoksa baştan dolanarak); `afterId` yoksa / listede değilse baştan.
 * `isEmpty(id)` yuvanın görselsiz ve meşgul olmadığını söyler. Boş yuva yoksa null.
 */
export function nextEmptySlot(order, isEmpty, afterId = null) {
  const list = order || []
  if (!list.length) return null
  let start = 0
  if (afterId != null) {
    const i = list.findIndex((o) => o.id === afterId)
    if (i >= 0) start = i + 1
  }
  for (let k = 0; k < list.length; k++) {
    const o = list[(start + k) % list.length]
    if (isEmpty(o.id)) return o
  }
  return null
}

/** Yuvanın sırası: `{ pos (1'den), total }`; yoksa null. */
export function slotPosition(order, id) {
  const i = (order || []).findIndex((o) => o.id === id)
  return i < 0 ? null : { pos: i + 1, total: order.length }
}

// ── C4: toplu bırakma ─────────────────────────────────────────────────────────────────────────────────────────────

/** Bir bırakmada en çok bu kadar dosya eşlenir (fazlası sayılır, söylenir). */
export const BULK_MAX_FILES = 40
/** Tarayıcının açıp hazırlayabildiği görsel uzantıları (imagePrep türü yine baytlardan okur). */
export const IMAGE_EXTENSIONS = Object.freeze(['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'avif'])
/** Eşleme nedeni (kalıcı; arayüz `te.bulk.how.<kod>`). */
export const BULK_HOWS = Object.freeze(['name', 'label', 'next', 'filled', 'taken', 'kept', 'notImage', 'noSlot'])

/** Yuva kimliğinin dosya adındaki biçimi — captureFileName'in yuva kısmıyla AYNI kural. */
export function slotFileKey(slotId) {
  return String(slotId || '').toLowerCase().replace(/[:_]+/g, '-').replace(/[^a-z0-9-]+/g, '').slice(0, 60)
}

/** Dosya adının uzantısız, küçük harfli gövdesi. */
export function fileBase(name) {
  const n = String(name || '').trim()
  const dot = n.lastIndexOf('.')
  return (dot > 0 ? n.slice(0, dot) : n).toLowerCase()
}

/** Dosya bir görsel mi (MIME `image/*` ya da bilinen uzantı)? */
export function looksLikeImage(file) {
  if (String(file?.type || '').toLowerCase().startsWith('image/')) return true
  const n = String(file?.name || '').toLowerCase()
  const dot = n.lastIndexOf('.')
  return dot > 0 && IMAGE_EXTENSIONS.includes(n.slice(dot + 1))
}

/**
 * Katlanmış metinde `needle` TAM SÖZCÜKLER olarak geçiyor mu (`foldName` sözcükleri tek boşlukla ayırır). Ham alt dize değil
 * (inceleme 2026-11-20: 3 harflik "SLA" yuva adı `translation.png` dosyasıyla eşleşiyordu).
 */
export function hasWords(hay, needle) {
  if (!hay || !needle) return false
  return ` ${hay} `.includes(` ${needle} `)
}

/** Ad karşılaştırması için katlanmış metin: Türkçe harfler eşit, ayraçlar tek boşluk. */
export function foldName(s) {
  return foldSearchText(s).replace(/[^a-z0-9]+/g, ' ').trim()
}

/**
 * Bırakılan dosyaları yuvalara eşler. `order` = {@link reportSlotOrder} (etiketleri ÇÖZÜLMÜŞ: `label`), `isFilled(id)` yuvanın
 * dolu olup olmadığı, `isKept(id)` yuvada gönderilemeyen (bekletilen) görsel var mı (hedef olamaz — yeni yükleme onu bırakırdı),
 * `startSectionId` bırakılan bölüm (sıradaki boş yuva aramasının başı). Dönüş `{ rows, over }`:
 * `rows[i]` = `{ index, name, size, target (yuva kimliği | null), how, match (eşleşen yuva — dolu olsa da) }`.
 */
export function mapDroppedFiles(files, order, { isFilled = () => false, isKept = () => false, startSectionId = null } = {}) {
  const all = Array.from(files || [])
  const list = all.slice(0, BULK_MAX_FILES)
  const over = Math.max(0, all.length - list.length)
  const slots = order || []
  const used = new Set()
  const rows = list.map((f, index) => ({ index, name: String(f?.name || ''), size: Number(f?.size) || 0, target: null, how: null, match: null }))

  const assign = (r, slot, how) => {
    r.match = slot.id
    if (isKept(slot.id)) { r.how = 'kept'; return }
    if (isFilled(slot.id)) { r.how = 'filled'; return }
    if (used.has(slot.id)) { r.how = 'taken'; return }
    r.target = slot.id
    r.how = how
    used.add(slot.id)
  }

  for (const r of rows) if (!looksLikeImage(list[r.index])) r.how = 'notImage'

  // 1) Dosya adındaki yuva kimliği — en uzun eşleşen anahtar ("tech-ratio" ile "tech-ratio-monthly" karışmasın)
  const keys = slots.map((o) => ({ o, key: slotFileKey(o.id) })).filter((x) => x.key)
  for (const r of rows) {
    if (r.how) continue
    const base = fileBase(r.name)
    let best = null
    for (const x of keys) {
      if ((base === x.key || base.startsWith(`${x.key}-`)) && (!best || x.key.length > best.key.length)) best = x
    }
    if (best) assign(r, best.o, 'name')
  }

  // 2) Yuva adıyla (katlanmış): tam eşit → adın içinde geçen en uzun yuva adı → adı içinde geçtiği TEK yuva
  const folded = slots.map((o) => ({ o, f: foldName(o.label) })).filter((x) => x.f.length >= 3)
  for (const r of rows) {
    if (r.how) continue
    const base = foldName(fileBase(r.name))
    if (!base) continue
    let hit = folded.find((x) => x.f === base) || null
    if (!hit) {
      for (const x of folded) if (hasWords(base, x.f) && (!hit || x.f.length > hit.f.length)) hit = x
    }
    if (!hit && base.length >= 4) {
      const inside = folded.filter((x) => hasWords(x.f, base))
      if (inside.length === 1) hit = inside[0]
    }
    if (hit) assign(r, hit.o, 'label')
  }

  // 3) Sıradaki boş yuva (bırakılan bölümün ilk yuvasından başlayarak, dolanarak)
  const start = Math.max(0, slots.findIndex((o) => o.sectionId === startSectionId))
  for (const r of rows) {
    if (r.how) continue
    let slot = null
    for (let k = 0; k < slots.length; k++) {
      const o = slots[(start + k) % slots.length]
      if (!isFilled(o.id) && !isKept(o.id) && !used.has(o.id)) { slot = o; break }
    }
    if (slot) { r.target = slot.id; r.how = 'next'; used.add(slot.id) } else r.how = 'noSlot'
  }
  return { rows, over }
}

/** Aynı yuvayı seçen birden çok dosya (ikincisi birincinin görselini ezerdi): çakışan satırların `index` kümesi. */
export function bulkConflicts(targets) {
  const seen = new Map()
  const bad = new Set()
  for (const [index, target] of Object.entries(targets || {})) {
    if (!target) continue
    if (seen.has(target)) { bad.add(Number(index)); bad.add(seen.get(target)) } else seen.set(target, Number(index))
  }
  return bad
}
