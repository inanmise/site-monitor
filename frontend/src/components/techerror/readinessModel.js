// Rapor hazırlık rehberi (2026-11-20, B paketi — kullanıcı onayı): raporda neyin eksik / şüpheli olduğunu SAF olarak
// hesaplar. Düzenleyici bunu başlık çipinde ("Görsel 12/15 · 2 boş alan · 1 uyarı"), bölüm başlığı rozetinde, hazırlık
// panelinde ("Git" ile alana / yuvaya / konuya götürür) ve gönderim / onaya gönderme onayında gösterir. Eksikler YALNIZ
// UYARIR — kayıt, PDF, gönderim hiçbiri engellenmez. PDF / e-posta / API'ye dokunmaz.
//
// Kurallar:
//   - Boş rakam: sayı / yüzde / evet-hayır alanı, değeri yok ve muaf değil ("Hayır" cevabıyla gizlenen alan sayılmaz).
//     Hesaplanan alan sayılmaz — formülü elle girilmesini istiyorsa (MANUAL / BAD_REF) boş değeri sayılır; formül
//     kullanılabilir ama DÜZELTİLEBİLİR bir nedenle sonuç vermiyorsa (ZERO: bölen 0 iken pay 0 değil, NEGATIVE: çıkan sayı
//     büyük, RANGE: aralık dışı) "sonuç yok" diye sayılır. 0 ÷ 0 (hatasız ay: teknik hata 0 → map / kota / kesinti payı) ve
//     değişim / artış alanlarının ZERO'su (geçen ay 0) doğru girdinin sonucudur — sayılmaz (inceleme 2026-11-20).
//     MISSING (girdisi boş) sayılmaz: girdinin kendisi zaten boş alan sayılır.
//   - Boş görsel yuvası: şablonun görsel bölümlerindeki yuvalar (konu görselleri isteğe bağlı — sayılmaz).
//   - Anomali konusu: zorunlu cevabı boş (Kesinti ID, saat aralığının eksik ucu, aralığın tarihi) — issueValidation.
//   - Uyarılar: eksik rakam yüzünden özete girmeyen cümle, geçen aya göre ×10 / ÷10 farklı rakam (B2), konuların hata
//     adetleri toplamı ayın toplamını aşıyor (B2), kesinti cevabı konularla çelişiyor, takım adresi yok (onaysız kanal),
//     onay akışında PO yok.
//   - Gömülü (özet kartında çizilen) gösterge bölümünün alanı ÖZET bölümüne yazılır (oraya gidilir).
//   - Bilinmeyen (ör. görsel listesi / alıcı sayısı okunamadı, şablon yok) "hazır" sayılmaz: `known` false.

import {
  CALC_MANUAL, allFields, calcNumber, fieldExempt, hostOf, issueOptions, issueValidation, outageAnswerConflict, OUTAGE_FIELD,
  summaryLineStatus,
} from './techErrorModel.js'

/** Hazırlıkta sayılan rakam türleri (metin / uzun açıklama isteğe bağlı sayılır). */
export const READY_FIGURE_TYPES = Object.freeze(['number', 'percent', 'yesno'])
/** Hesaplanan alanın "düzeltilebilir neden"le sonuç vermediği durumlar (girdi değeri yanlış olabilir). */
export const READY_FIXABLE_CALC = Object.freeze(['ZERO', 'NEGATIVE', 'RANGE'])
/** B2: geçen aya göre bu kat (ya da 1 / bu kat) farklı rakam birim sorusu sorar. */
export const SCALE_FACTOR = 10
const EPS = 1e-9

/**
 * B2 — birim / büyüklük sorusu: sayı ya da yüzde alanının bu ayki değeri geçen ayınkinin ≥ 10 katı ya da ≤ 1/10'u mu?
 * İkisi de sıfırdan farklı olmalı (sayılar saklanan sayıdır — "4,4M" kısaltması 4 400 000 olarak karşılaştırılır).
 * Sonuç `{ dir: 'up' | 'down', ratio }` ya da null. Engellemez; yalnız soru sorar.
 */
export function scaleWarning(field, cur, prev) {
  if (!field || (field.type !== 'number' && field.type !== 'percent')) return null
  const a = calcNumber(cur)
  const b = calcNumber(prev)
  if (a == null || b == null || a === 0 || b === 0) return null
  const ratio = Math.abs(a / b)
  if (!Number.isFinite(ratio) || ratio === 0) return null
  if (ratio >= SCALE_FACTOR - EPS) return { dir: 'up', ratio }
  if (ratio * SCALE_FACTOR <= 1 + EPS) return { dir: 'down', ratio }
  return null
}

/**
 * Alanın B2 sorusu, düzenleyicideki kuralla: hesaplanan (formülü kullanılabilen) alan sorulmaz — girdisi sorulur; elle
 * girilen (MANUAL / BAD_REF) hesaplanan alan sorulur. Muaf alan sorulmaz.
 */
export function fieldScaleWarning(field, fields, previousFields, calc) {
  if (!field || fieldExempt(field, fields)) return null
  const res = calc?.[field.id]
  if (res && !CALC_MANUAL.has(res.status)) return null
  return scaleWarning(field, fields?.[field.id], previousFields?.[field.id])
}

/**
 * B2 — anomali konularının teknik hata adetleri toplamı, bölümün "tüm teknik hatalar" alanının (`ratio_of`) değerini aşıyor
 * mu? `{ sum, total, ref }` ya da null (pay sorulmuyor, toplam alanı yok / sayı değil / muaf / boş, hiçbir konuda adet yok).
 */
export function issueSumWarning(section, issues, template, fields) {
  if (!section || section.kind !== 'issues') return null
  const ref = issueOptions(section, template?.lang).ratioOf
  if (!ref) return null
  const total = allFields(template).find((f) => f.id === ref)
  if (!total || total.type !== 'number' || fieldExempt(total, fields)) return null
  const all = calcNumber(fields?.[ref])
  if (all == null) return null
  let sum = 0
  let n = 0
  for (const i of issues || []) {
    const e = calcNumber(i?.errors)
    if (e != null) { sum += e; n++ }
  }
  if (n === 0 || sum <= all) return null
  return { sum, total: all, ref }
}

const isBlank = (v) => v == null || (typeof v === 'string' && v.trim() === '')

/**
 * Hesaplanan alanın sonuçsuzluğu "düzeltilecek bir girdi" mi? ZERO yalnız oranda ve pay (num − minus) sıfırdan farklıyken
 * (bölen 0 ama hata var: girdi yanlış); 0 ÷ 0 ve değişim / artışın ZERO'su doğru girdinin sonucudur.
 */
export function fixableCalc(field, res, fields) {
  if (!res || !READY_FIXABLE_CALC.includes(res.status)) return false
  if (res.status !== 'ZERO') return true
  const c = field?.calc
  if (c?.kind !== 'ratio') return false
  const num = calcNumber(fields?.[c.num])
  if (num == null) return false
  const minus = c.minus ? calcNumber(fields?.[c.minus]) : 0
  return num - (minus ?? 0) !== 0
}

/**
 * B3 — onay kilidi: rapor yanıtı (`data`) için kilit durumu `'pending' | 'approved'` ya da null (kilit yok). Kilit yalnız
 * DÜZENLEYEBİLEN kullanıcıda (yetkisiz görüntüleyen bugünkü salt okunur görünümü görür), kanal hâlâ onay istiyorken
 * (`approval_detail.required`) ve onay bekliyor ya da onaylanmış-eskimemişken. Gönderilmiş / kuyrukta / gönderiliyor
 * rapor bugünkü gibi (kilit yok). Eski sunucu (`approval_detail` yok) → kilit yok.
 */
export function approvalLockState(data) {
  if (!data?.can_edit || data?.approval_detail?.required !== true) return null
  if (['sent', 'queued', 'sending'].includes(data?.report?.status)) return null
  const s = data?.report?.approval?.state
  return s === 'pending' || s === 'approved' ? s : null
}

/**
 * Kilidin parmak izi: durum + istek + karar anı. Kilit bu parmak izi için açılır; yeni bir onay isteği ya da karar (parmak
 * izi değişir) kilidi YENİDEN uygular — taslak korunur (inceleme 2026-11-20). Kilit yoksa null.
 */
export function approvalLockFingerprint(data) {
  const s = approvalLockState(data)
  if (!s) return null
  const a = data?.report?.approval || {}
  return `${s}|${a.requested_at ?? ''}|${a.decided_at ?? ''}`
}

/** Rapor hiç doldurulmamış mı (yeni rapor — "Sonraki adım" ipucu): rakam, metin, konu, tablo ve görsel yok. */
export function isBlankReport(values, images) {
  const v = values || {}
  if (Object.values(v.fields || {}).some((x) => !isBlank(x))) return false
  if (Object.values(v.texts || {}).some((x) => !isBlank(x))) return false
  if (Object.values(v.issues || {}).some((l) => Array.isArray(l) && l.length > 0)) return false
  for (const rows of Object.values(v.tables || {})) {
    for (const row of Object.values(rows || {})) if (Object.values(row || {}).some((x) => !isBlank(x))) return false
  }
  return !(Array.isArray(images) && images.length > 0)
}

/**
 * Raporun hazırlık durumu. Girdiler:
 *   - `template`: raporun kopyalanmış şablonu (yoksa / bölümsüzse `known` false, kalem yok)
 *   - `values`: taslak değerler; `fields`: hesap uygulanmış rakamlar (yoksa `values.fields`)
 *   - `calc`: calcResults çıktısı; `previousFields`: geçen ayın aynı kimlik + türdeki rakamları (yoksa null)
 *   - `images`: rapordaki görsellerin listesi (`slot_id`) — null = bilinmiyor
 *   - `issueChecks`: issueValidation çıktısı (verilmezse hesaplanır; `period` ay dışı tarih denetimi için)
 *   - `recipients`: `{ approvalRequired, approverCount, teamEmailCount, teamEmailSet }` — tanımsız alan = bilinmiyor
 *     (`approvalRequired` null = onay akışı yok sayılır: eski sunucu `approval_detail` göndermez); null = denetlenmez
 *     (gönderemeyen kullanıcı)
 * Sonuç: `{ known, unknown: [neden], items, empty (boş rakam / konu cevabı), emptySlots (boş görsel yuvası), warnings,
 * images: {filled, total}|null, bySection, ready }`. Kalemler
 * şablon sırasıyla; alıcı kalemleri en sonda (`sectionId` null). `ready` yalnız her girdi biliniyorken ve hiç kalem yokken.
 */
export function reportReadiness({ template, values, fields = null, calc = null, previousFields = null, images = null,
  issueChecks = null, period = null, recipients = null } = {}) {
  const items = []
  const unknown = []
  const sections = Array.isArray(template?.sections) ? template.sections : null
  if (!sections || sections.length === 0) {
    return { known: false, unknown: ['template'], items, empty: 0, emptySlots: 0, warnings: 0, images: null, bySection: {}, ready: false }
  }
  const v = values || {}
  const shown = fields || v.fields || {}
  const c = calc || {}
  const imageKnown = Array.isArray(images)
  if (!imageKnown) unknown.push('images')
  const filledSlots = new Set(imageKnown ? images.map((i) => i?.slot_id).filter(Boolean) : [])
  let slotTotal = 0
  let slotFilled = 0
  const checks = issueChecks || issueValidation(template, v.issues, period)
  // Alan → çizildiği bölüm (gömülü bölümün alanı özet kartında): özet cümlesinin eksik rakamına giderken kullanılır
  const navOfField = new Map()
  for (const s of sections) {
    if (s?.kind !== 'metrics') continue
    const nav = hostOf(template, s)?.id || s.id
    for (const f of s.fields || []) if (f?.id) navOfField.set(f.id, nav)
  }

  for (const s of sections) {
    if (!s || typeof s.id !== 'string') continue
    if (s.kind === 'metrics') {
      // Gömülü bölümün alanları özet kartında çizilir: oraya gidilir
      const nav = hostOf(template, s)?.id || s.id
      for (const f of s.fields || []) {
        if (!f || !READY_FIGURE_TYPES.includes(f.type) || fieldExempt(f, shown)) continue
        const res = c[f.id]
        const computed = !!res && !CALC_MANUAL.has(res.status)
        if (computed) {
          if (fixableCalc(f, res, v.fields)) {
            items.push({ kind: 'field', severity: 'empty', sectionId: nav, fieldId: f.id, label: f.label || '', reason: res.status })
          }
        } else if (isBlank(v.fields?.[f.id])) {
          items.push({ kind: 'field', severity: 'empty', sectionId: nav, fieldId: f.id, label: f.label || '', reason: 'empty' })
        } else {
          const w = fieldScaleWarning(f, v.fields, previousFields, c)
          if (w) {
            items.push({ kind: 'scale', severity: 'warning', sectionId: nav, fieldId: f.id, label: f.label || '', dir: w.dir,
              cur: calcNumber(v.fields?.[f.id]), prev: calcNumber(previousFields?.[f.id]) })
          }
        }
        if (f.id === OUTAGE_FIELD && f.type === 'yesno') {
          const n = outageAnswerConflict(template, v)
          if (n > 0) items.push({ kind: 'outage', severity: 'warning', sectionId: nav, fieldId: f.id, label: f.label || '', count: n })
        }
      }
    } else if (s.kind === 'summary') {
      // Elle yazılmış özet cümle kalıplarını kullanmaz
      if (!isBlank(v.texts?.[s.id])) continue
      for (const st of summaryLineStatus(s, template, shown, previousFields, v.targets)) {
        if (st.state === 'missing' && st.missing.length > 0) {
          // "Git" girilecek alana gider: eksik rakam hesaplanan bir alansa onun ilk boş GİRDİSİ (hesap çıktısına odak olmaz)
          const first = st.missing[0].id
          const targetFieldId = c[first]?.missing?.[0] || first
          items.push({ kind: 'line', severity: 'warning', sectionId: s.id, index: st.index,
            fieldIds: st.missing.map((f) => f.id), labels: st.missing.map((f) => f.label || ''),
            targetFieldId, fieldSectionId: navOfField.get(targetFieldId) || navOfField.get(first) || s.id })
        }
      }
    } else if (s.kind === 'images') {
      for (const sl of s.slots || []) {
        if (!sl || typeof sl.id !== 'string') continue
        slotTotal++
        if (filledSlots.has(sl.id)) slotFilled++
        else if (imageKnown) items.push({ kind: 'slot', severity: 'empty', sectionId: s.id, slotId: sl.id, label: sl.label || '' })
      }
    } else if (s.kind === 'issues') {
      const list = v.issues?.[s.id] || []
      list.forEach((issue, index) => {
        if (!issue?.id) return
        for (const part of ['date', 'start', 'end', 'outage_id']) {
          const chk = checks[`issue:${s.id}:${issue.id}:${part}`]
          if (chk?.required) items.push({ kind: 'topic', severity: 'empty', sectionId: s.id, issueId: issue.id, part, index })
        }
      })
      const sum = issueSumWarning(s, list, template, shown)
      if (sum) items.push({ kind: 'issueSum', severity: 'warning', sectionId: s.id, sum: sum.sum, total: sum.total, ref: sum.ref })
    }
  }

  // Alıcılar / onay önkoşulları (bölüme bağlı değil). `recipients` verilmezse (gönderemeyen kullanıcı) denetlenmez
  if (recipients) {
    const r = recipients
    if (r.approvalRequired === true) {
      if (r.approverCount == null) unknown.push('approvers')
      else if (Number(r.approverCount) === 0) items.push({ kind: 'noApprover', severity: 'warning', sectionId: null })
    } else {
      const count = typeof r.teamEmailCount === 'number' ? r.teamEmailCount : null
      if (count == null && typeof r.teamEmailSet !== 'boolean') unknown.push('recipients')
      else if (count === 0 || (count == null && r.teamEmailSet === false)) items.push({ kind: 'teamEmail', severity: 'warning', sectionId: null })
    }
  }

  // Boş görsel yuvaları ayrı sayılır: çip onları "Görsel 12/15" oranıyla söyler, "boş alan" sayısına katmaz
  const bySection = {}
  let empty = 0
  let emptySlots = 0
  let warnings = 0
  for (const it of items) {
    if (it.severity !== 'empty') warnings++
    else if (it.kind === 'slot') emptySlots++
    else empty++
    if (it.sectionId) bySection[it.sectionId] = (bySection[it.sectionId] || 0) + 1
  }
  const known = unknown.length === 0
  return {
    known, unknown, items, empty, emptySlots, warnings,
    images: slotTotal > 0 ? { filled: imageKnown ? slotFilled : null, total: slotTotal } : null,
    bySection, ready: known && items.length === 0,
  }
}

/** Bölüm → kalem sayısı eşlemesinin anahtar metni (Map kimliği yalnız sayılar değişince değişsin). */
export function readinessKey(bySection) {
  return Object.entries(bySection || {}).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([k, n]) => `${k}:${n}`).join('|')
}

/** Çipin tonu: `ok` yalnız her şey biliniyor ve kalem yokken; bilinmiyorsa `unknown` (asla yeşil değil); aksi `warn`. */
export function readinessTone(r) {
  if (!r) return 'unknown'
  if (r.ready) return 'ok'
  if (r.items.length > 0) return 'warn'
  return 'unknown'
}

/** Kalemleri bölüme göre gruplar (şablon sırası korunur; bölümsüz alıcı kalemleri `null` grubunda, sonda). */
export function groupReadiness(items) {
  const groups = []
  const at = new Map()
  for (const it of items || []) {
    const k = it.sectionId ?? null
    if (!at.has(k)) { at.set(k, groups.length); groups.push({ sectionId: k, items: [] }) }
    groups[at.get(k)].items.push(it)
  }
  const i = groups.findIndex((g) => g.sectionId == null)
  if (i >= 0 && i !== groups.length - 1) groups.push(groups.splice(i, 1)[0])
  return groups
}

/**
 * Kalemin hedefi (Git): `{ sectionId, topic: [bölüm, konu] | null, selector }` — bölüm açılır, konu açılır, seçiciye
 * odaklanılır. Alıcı kalemleri raporun alıcı özetine kaydırır.
 */
export function readinessTarget(item) {
  if (!item) return null
  switch (item.kind) {
    case 'field':
    case 'scale':
    case 'outage':
      return { sectionId: item.sectionId, topic: null, selector: `[data-field="values.${item.fieldId}"]` }
    case 'line':
      return { sectionId: item.fieldSectionId || item.sectionId, topic: null,
        selector: `[data-field="values.${item.targetFieldId || item.fieldIds?.[0]}"]` }
    case 'slot':
      return { sectionId: item.sectionId, topic: null, selector: `[data-slot="te-image-slot"][data-slot-id="${item.slotId}"]` }
    case 'topic':
      return { sectionId: item.sectionId, topic: [item.sectionId, item.issueId],
        selector: `[data-field="issue:${item.sectionId}:${item.issueId}:${item.part}"]` }
    case 'issueSum':
      return { sectionId: item.sectionId, topic: null, selector: `[data-slot="te-issue-sum-warn"][data-section="${item.sectionId}"]` }
    default:
      return { sectionId: null, topic: null, selector: '[data-slot="te-recipients"]' }
  }
}
