import { describe, it, expect } from 'vitest'
import {
  QUICK_FIELDS, hasHiddenErrors, nowMinuteIso, quickInitial, nextStatus, statusChoices, statusChangeForm,
  statusChangeRequest, COPY_KEYS, stripIncidentImages, duplicateForm, markdownOmitted, mergeFull, keepMarkdown,
  pageKeyEligible,
} from '../components/incidenthistory/quickEntryModel.js'
import { EMPTY_FORM, MARKDOWN_KEYS, STATUSES } from '../components/incidenthistory/incidentHistoryModel.js'

/** "Hızlı Giriş" (2026-11-20) saf modeli — hızlı kayıt, tek tıkla durum, çoğaltma, özet satırlar, sayfa kısayolları. */

const REC = Object.freeze({
  id: 41, version: 3, title: 'Ödeme gecikmesi', occurred_at: '2026-11-18T10:00:00', detected_at: '2026-11-18T10:05:00',
  resolved_at: '', severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', team_id: 7, team_name: 'Ödeme Ekibi',
  description: 'Açıklama', rca_summary: 'Kök', resolution_steps: 'Adım', business_impact: 'Etki',
  runbook_url: 'https://wiki.example.com/r', can_edit: true,
})

describe('hızlı kayıt', () => {
  it('görünen alanlar sabit; gizli alan hatası tam görünüme geçirir', () => {
    expect(QUICK_FIELDS).toEqual(['title', 'occurred_at', 'team_id', 'severity', 'status'])
    expect(hasHiddenErrors({ title: 'x', team_id: 'y' })).toBe(false)
    expect(hasHiddenErrors({ title: 'x', runbook_url: 'y' })).toBe(true)
    expect(hasHiddenErrors(null)).toBe(false)
  })

  it('şimdi dakikaya AŞAĞI yuvarlanır (oluş zamanı gelecekte olamaz)', () => {
    expect(nowMinuteIso(new Date('2026-11-20T09:41:59.900Z'))).toBe('2026-11-20T09:41:00')
    expect(nowMinuteIso(new Date('2026-11-20T09:41:00.000Z'))).toBe('2026-11-20T09:41:00')
    expect(nowMinuteIso(Number.NaN)).toBe('')
  })

  it('başlangıç formu: tek takım varsa seçili, birden çoksa boş', () => {
    const one = quickInitial([{ id: 7, name: 'Ödeme' }], '2026-11-20T09:41:00')
    expect(one).toMatchObject({ occurred_at: '2026-11-20T09:41:00', team_id: '7', team_name: 'Ödeme', status: 'OPEN' })
    const many = quickInitial([{ id: 7, name: 'A' }, { id: 8, name: 'B' }], '2026-11-20T09:41:00')
    expect(many.team_id).toBe('')
    expect(quickInitial(null, '').team_id).toBe('')
    expect(quickInitial([], 'x').title).toBe('')
  })
})

describe('tek tıkla durum', () => {
  it('sıradaki durum akıştan gelir; şimdiki seçeneklerde yok', () => {
    expect(nextStatus('OPEN')).toBe('INVESTIGATING')
    expect(nextStatus('INVESTIGATING')).toBe('MITIGATED')
    expect(nextStatus('MITIGATED')).toBe('RESOLVED')
    expect(nextStatus('RESOLVED')).toBe(null)
    expect(nextStatus('???')).toBe(null)
    expect(statusChoices('OPEN')).toEqual(STATUSES.filter((s) => s !== 'OPEN'))
  })

  it('basit geçiş: gövde yalnız durum + kimlik + sürüm', () => {
    const { body, changed, errors } = statusChangeRequest(REC, 'INVESTIGATING')
    expect(errors).toEqual({})
    expect(changed).toEqual(['status'])
    expect(body).toEqual({ status: 'INVESTIGATING', id: 41, version: 3 })
  })

  it('e-posta isteğe bağlı: seçilince gövdeye eklenir', () => {
    expect(statusChangeRequest(REC, 'MITIGATED', { sendMail: true }).body.send_notification).toBe(true)
    expect(statusChangeRequest(REC, 'MITIGATED').body.send_notification).toBeUndefined()
  })

  it('Çözüldü: verilen zaman, yoksa kayıttaki, o da yoksa şimdi (oluş / tespitten önce değil)', () => {
    const now = new Date('2026-11-18T12:00:20Z')
    expect(statusChangeForm(REC, 'RESOLVED', { resolvedAt: '2026-11-18T11:00:00' }, now).resolved_at).toBe('2026-11-18T11:00:00')
    expect(statusChangeForm({ ...REC, resolved_at: '2026-11-18T11:30:00' }, 'RESOLVED', {}, now).resolved_at).toBe('2026-11-18T11:30:00')
    // şimdi yukarı yuvarlanır (12:00:20 → 12:01)
    expect(statusChangeForm(REC, 'RESOLVED', {}, now).resolved_at).toBe('2026-11-18T12:01:00')
    // saat kaymış: şimdi tespit zamanından önceyse çözülme zamanı tespit zamanı olur
    const early = new Date('2026-11-18T09:00:00Z')
    expect(statusChangeForm(REC, 'RESOLVED', {}, early).resolved_at).toBe('2026-11-18T10:05:00')
    const req = statusChangeRequest(REC, 'RESOLVED', { resolvedAt: '2026-11-18T11:00:00' }, now)
    expect(req.body).toMatchObject({ status: 'RESOLVED', resolved_at: '2026-11-18T11:00:00', version: 3 })
  })

  it('Çözüldü: oluş zamanından önceki çözülme zamanı hata verir (yalnız durum / zaman alanları)', () => {
    const req = statusChangeRequest(REC, 'RESOLVED', { resolvedAt: '2026-11-18T09:00:00' })
    expect(Object.keys(req.errors)).toEqual(['resolved_at'])
  })

  it('ilgisiz eski değer (bozuk runbook) durum değişikliğini kilitlemez', () => {
    const req = statusChangeRequest({ ...REC, runbook_url: 'javascript:alert(1)' }, 'INVESTIGATING')
    expect(req.errors).toEqual({})
    expect(req.body).toEqual({ status: 'INVESTIGATING', id: 41, version: 3 })
  })

  it('geri açış: çözülme zamanı temizlenir (seçiliyse), hesaplanmış süre gönderilmez', () => {
    const resolved = { ...REC, status: 'RESOLVED', resolved_at: '2026-11-18T11:00:00', duration_minutes: 60 }
    const clear = statusChangeRequest(resolved, 'INVESTIGATING', { clearResolved: true })
    expect(clear.body).toEqual({ status: 'INVESTIGATING', resolved_at: null, id: 41, version: 3 })
    const keep = statusChangeRequest(resolved, 'INVESTIGATING', { clearResolved: false })
    expect(keep.body).toEqual({ status: 'INVESTIGATING', id: 41, version: 3 })
  })

  it('özet satır (markdown gelmemiş): markdown alanları gövdeye girmez', () => {
    const summary = { ...REC, markdown_omitted: true }
    for (const k of MARKDOWN_KEYS) delete summary[k]
    const { body } = statusChangeRequest(summary, 'INVESTIGATING')
    for (const k of MARKDOWN_KEYS) expect(body).not.toHaveProperty(k)
  })

  it('sürümü olmayan kayıt (eski sunucu): gövdede sürüm yok', () => {
    const { body } = statusChangeRequest({ ...REC, version: undefined }, 'INVESTIGATING')
    expect(body).toEqual({ status: 'INVESTIGATING', id: 41 })
  })
})

describe('çoğaltma', () => {
  it('kopyalanan alan listesi sabit; zamanlar, durum, kök neden, sayılar kopyalanmaz', () => {
    for (const k of ['occurred_at', 'detected_at', 'resolved_at', 'status', 'rca_summary', 'resolution_steps',
      'business_impact', 'affected_customers', 'affected_transactions', 'sla_breached', 'error_budget_burn_pct',
      'duration_minutes', 'tech_error_count', 'outage_related', 'outage_id', 'team_id', 'team_name']) {
      expect(COPY_KEYS).not.toContain(k)
    }
    const { form } = duplicateForm({ ...REC, status: 'RESOLVED', resolved_at: '2026-11-18T11:00:00', affected_customers: 5 }, [{ id: 7, name: 'Ödeme Ekibi' }])
    expect(form).toMatchObject({ title: REC.title, description: 'Açıklama', runbook_url: REC.runbook_url, status: 'OPEN',
      occurred_at: '', resolved_at: '', rca_summary: '', affected_customers: '', team_id: '7', team_name: 'Ödeme Ekibi' })
    expect(form).not.toHaveProperty('id')
    expect(form).not.toHaveProperty('version')
  })

  it('takım yalnız seçilebiliyorsa kopyalanır', () => {
    const r = duplicateForm(REC, [{ id: 8, name: 'Başka' }])
    expect(r.teamDropped).toBe(true)
    expect(r.form.team_id).toBe(EMPTY_FORM.team_id)
    const noTeam = duplicateForm({ ...REC, team_id: null }, [])
    expect(noTeam.teamDropped).toBe(false)
  })

  it('takım listesi BİLİNMİYORSA (yükleniyor / okunamadı) kaynak takım korunur, düşürülmez (inceleme L4)', () => {
    const r = duplicateForm(REC, [], { known: false })
    expect(r.teamDropped).toBe(false)
    expect(r.form).toMatchObject({ team_id: '7', team_name: 'Ödeme Ekibi' })
    // bilinmeyen liste takımı içeriyorsa listedeki ad kullanılır
    const listed = duplicateForm(REC, [{ id: 7, name: 'Ödeme Ekibi (yeni ad)' }], { known: false })
    expect(listed.form.team_name).toBe('Ödeme Ekibi (yeni ad)')
    // BİLİNEN ve takımı içermeyen liste düşürür (varsayılan known = true)
    const dropped = duplicateForm(REC, [], { known: true })
    expect(dropped.teamDropped).toBe(true)
    expect(dropped.form.team_id).toBe(EMPTY_FORM.team_id)
    // takımsız kayıtta bilinmeyen liste bir şey eklemez
    expect(duplicateForm({ ...REC, team_id: '' }, [], { known: false }).form.team_id).toBe(EMPTY_FORM.team_id)
  })

  it('geçersiz önem / kategori varsayılana döner', () => {
    const { form } = duplicateForm({ ...REC, severity: 'X', category: 'Y' }, [])
    expect(form.severity).toBe(EMPTY_FORM.severity)
    expect(form.category).toBe(EMPTY_FORM.category)
  })

  it('kaydın görselleri (başlık satırıyla) çıkarılır; dış görsel kalır', () => {
    const md = 'Önce\n\n**Grafik**\n![Grafik](/api/incidents/images/12)\n\nArada ![dış](https://cdn.example.com/x.png) son\n'
      + '![tam](https://site.example.com/api/incidents/images/13)'
    const { text, removed } = stripIncidentImages(md)
    expect(removed).toBe(2)
    expect(text).not.toContain('/api/incidents/images/')
    expect(text).not.toContain('**Grafik**')
    expect(text).toContain('![dış](https://cdn.example.com/x.png)')
    expect(text.startsWith('Önce')).toBe(true)
    const dup = duplicateForm({ ...REC, description: md }, [])
    expect(dup.imagesRemoved).toBe(2)
  })

  it('görsel temizliği doğrusal ve bozuk girdide güvenli', () => {
    expect(stripIncidentImages('![a](/api/incidents/images/1').removed).toBe(0)
    expect(stripIncidentImages('![a').removed).toBe(0)
    expect(stripIncidentImages(null)).toEqual({ text: '', removed: 0 })
    const big = '![x](/api/incidents/images/1)\n'.repeat(20000)
    const t0 = Date.now()
    expect(stripIncidentImages(big).removed).toBe(20000)
    expect(Date.now() - t0).toBeLessThan(2000)
  })

  it('çok "![" ve uzakta tek "]": doğrusal (eski sürüm her "![" için aynı uzak "]"ı yeniden arıyordu) (inceleme L5)', () => {
    const hostile = '!['.repeat(60000) + ']' + '(/api/incidents/images/1)'
    const t0 = Date.now()
    const r = stripIncidentImages(hostile)
    expect(Date.now() - t0).toBeLessThan(3000)
    // ilk "![" uzaktaki "](" ile kapanan TEK görseldir (alt metni öteki "![" lar): tamamı çıkarılır
    expect(r.removed).toBe(1)
    expect(r.text).toBe('')
    // eski sürüm burada ~300 000 × 600 000 karakter tarardı (saniyeler); yeni sürüm milisaniyeler
    const noParen = '!['.repeat(300000) + ']x'
    const t1 = Date.now()
    expect(stripIncidentImages(noParen)).toEqual({ text: noParen, removed: 0 })
    expect(Date.now() - t1).toBeLessThan(3000)
  })

  it('sonuç eski (karesel) sürümle aynı — rastgele girdiler (inceleme L5, kâhin)', () => {
    // Eski sürüm (her "![" için `]` baştan aranır) — yalnız küçük girdilerde kâhin olarak kullanılır
    const oldStrip = (md) => {
      const s = String(md ?? '')
      let out = ''
      let from = 0
      let removed = 0
      let p = s.indexOf('![', 0)
      while (p >= 0) {
        const close = s.indexOf(']', p + 2)
        if (close < 0) break
        if (s[close + 1] !== '(') { p = s.indexOf('![', p + 2); continue }
        const paren = s.indexOf(')', close + 2)
        if (paren < 0) break
        const url = s.slice(close + 2, paren).trim()
        const lower = url.toLowerCase()
        const scheme = lower.startsWith('https://') ? 8 : lower.startsWith('http://') ? 7 : -1
        const slash = scheme < 0 ? -1 : url.indexOf('/', scheme)
        const isIncident = url.startsWith('/api/incidents/images/')
          || (scheme >= 0 && slash > scheme && url.startsWith('/api/incidents/images/', slash))
        if (!isIncident) { p = s.indexOf('![', paren + 1); continue }
        let chunk = s.slice(from, p)
        const caption = `**${s.slice(p + 2, close)}**`
        let end = chunk.length
        while (end > 0 && chunk.charCodeAt(end - 1) === 10) end--
        if (chunk.slice(0, end).endsWith(caption)) chunk = chunk.slice(0, end - caption.length)
        out += chunk
        from = paren + 1
        removed++
        p = s.indexOf('![', from)
      }
      return { text: out + s.slice(from), removed }
    }
    const parts = ['![', ']', '(', ')', '/api/incidents/images/3', 'https://h.example/api/incidents/images/4',
      'https://cdn.example/x.png', '**a**', 'a', '\n', ' ', '![a](/api/incidents/images/5)', '**a**\n']
    let seed = 20261120
    const rnd = (n) => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed % n }
    for (let i = 0; i < 3000; i++) {
      let s = ''
      const len = 1 + rnd(24)
      for (let j = 0; j < len; j++) s += parts[rnd(parts.length)]
      expect(stripIncidentImages(s)).toEqual(oldStrip(s))
    }
  })
})

describe('özet liste satırları', () => {
  const summary = { id: 41, version: 3, title: 'x', markdown_omitted: true }
  it('işaret okunur', () => {
    expect(markdownOmitted(summary)).toBe(true)
    expect(markdownOmitted(REC)).toBe(false)
    expect(markdownOmitted(null)).toBe(false)
  })

  it('tam kayıt işareti kaldırır; özet kayıt korur', () => {
    expect(mergeFull(summary, REC)).not.toHaveProperty('markdown_omitted')
    expect(mergeFull(REC, { ...summary }).markdown_omitted).toBe(true)
  })

  it('yenilemede aynı sürümün markdown\'ı korunur, farklı sürümünki korunmaz', () => {
    const next = [{ ...summary }, { id: 42, version: 1, markdown_omitted: true }]
    const out = keepMarkdown([REC], next)
    expect(out[0]).not.toHaveProperty('markdown_omitted')
    expect(out[0].description).toBe('Açıklama')
    expect(out[1].markdown_omitted).toBe(true)
    const newer = keepMarkdown([REC], [{ ...summary, version: 4 }])
    expect(newer[0].markdown_omitted).toBe(true)
    const unknown = keepMarkdown([{ ...REC, version: undefined }], [{ ...summary }])
    expect(unknown[0].markdown_omitted).toBe(true)
    // eski sunucu (tam satır) olduğu gibi
    expect(keepMarkdown([REC], [REC])[0]).toBe(REC)
    expect(keepMarkdown([], next)).toBe(next)
  })
})

describe('sayfa kısayolu uygunluğu', () => {
  const docWith = (overlays = [], active = null) => ({
    activeElement: active,
    querySelectorAll: () => overlays.map((slot) => ({ getAttribute: (n) => (n === 'data-slot' ? slot : null) })),
  })
  const key = (k, extra = {}) => ({ key: k, target: { tagName: 'BODY', closest: () => null }, ...extra })

  it('sade tuş, açık pencere yokken çalışır', () => {
    expect(pageKeyEligible(key('n'), docWith())).toBe(true)
  })
  it('değiştirici, IME, işlenmiş olay ve yazı alanı engeller', () => {
    expect(pageKeyEligible(key('n', { ctrlKey: true }), docWith())).toBe(false)
    expect(pageKeyEligible(key('n', { isComposing: true }), docWith())).toBe(false)
    expect(pageKeyEligible(key('n', { defaultPrevented: true }), docWith())).toBe(false)
    expect(pageKeyEligible(key('n', { target: { tagName: 'INPUT', closest: () => null } }), docWith())).toBe(false)
  })
  it('ayrıntı Sheet\'i engellemez; başka pencere / menü engeller', () => {
    expect(pageKeyEligible(key('e'), docWith(['ih-detail']))).toBe(true)
    expect(pageKeyEligible(key('e'), docWith(['ih-detail', 'ih-qs']))).toBe(false)
    expect(pageKeyEligible(key('e'), docWith(['dialog-content']))).toBe(false)
  })
})
