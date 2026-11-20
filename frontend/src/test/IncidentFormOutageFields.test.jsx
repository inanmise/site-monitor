import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'

// Olay formu — Etki bölümündeki üç isteğe bağlı alan (2026-11-20): "Teknik hata adedi", "Kesinti kaynaklı mı?" (üç durumlu:
// Evet / Hayır / seçilmemiş = bilinmiyor) ve yalnız Evet'te görünen "Kesinti ID".
//  • Bilinmeyen cevap asla "Hayır" değildir: seçili cevaba yeniden basmak seçimi kaldırır, gövdeye null yazılır.
//  • Kesinti ID yalnız Evet'te çizilir; cevap Evet değilken gövdeye null gider (alanda kalan metin gönderilmez).
//  • Sunucunun alan hatası (400 + fields) üstteki bantta değil ALANIN ALTINDA gösterilir; alan düzenlenince kalkar.
vi.mock('../api/client', () => ({
  api: new Proxy({}, { get: () => new Proxy({}, { get: () => vi.fn(() => Promise.resolve({ success: true, data: [] })) }) }),
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))

import IncidentFormModal from '../components/incidenthistory/IncidentFormModal.jsx'

const HINT = 'Bu olaydan kaynaklanan toplam teknik hata adedi; bilinmiyorsa boş bırakın. Teknik hata raporuna içe alınırken konunun hata adedine yazılır.'
/** Zorunlu alanları dolu, kayıtlı bir olay (düzenleme) — testler yalnız yeni alanlarla uğraşır. */
const BASE = { id: 9, title: 'Ödeme kesintisi', occurred_at: '2026-11-16T10:00:00', team_id: 1, team_name: 'Takım A' }

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.removeItem('site-monitor-lang') })

/** aria-describedby ile bağlı metinlerin tamamı (ipucu + hata). */
const describedText = (el) => (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean)
  .map((id) => document.getElementById(id)?.textContent ?? '').join(' ')

/** Üç isteğe bağlı gövde anahtarı. */
const KEYS = ['tech_error_count', 'outage_related', 'outage_id']
/** Gövdede bu üç anahtardan hangileri VAR (düzenlemede yalnız değişen gönderilir). */
const sentKeys = (payload) => KEYS.filter((k) => Object.prototype.hasOwnProperty.call(payload, k))

async function open({ initial = {}, onSubmit = vi.fn(async () => ({ ok: true })), impact = true, mode = 'edit' } = {}) {
  render(<IncidentFormModal mode={mode} initial={{ ...BASE, ...initial }} teams={[{ id: 1, name: 'Takım A' }]}
    onSubmit={onSubmit} onClose={() => {}} />)
  const dlg = screen.getByRole('dialog')
  const tab = (key) => dlg.querySelector(`[data-section="${key}"]`)
  const saveBtn = () => dlg.querySelector('[data-action="save"]')
  if (impact) {
    fireEvent.mouseDown(tab('impact'))                       // Radix Tabs jsdom'da mousedown ile değişir
    await waitFor(() => expect(tab('impact')).toHaveAttribute('aria-selected', 'true'))
  }
  return {
    dlg, onSubmit, tab,
    save: () => fireEvent.click(saveBtn()),
    /** n. kayıt denemesi sunucuya gitti VE düğme yeniden kullanılabilir (meşgul bayrağı söndü). */
    saved: async (n) => {
      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(n))
      await waitFor(() => expect(saveBtn()).not.toBeDisabled())
    },
    count: () => within(dlg).getByRole('textbox', { name: 'Teknik hata adedi' }),
    group: () => within(dlg).getByRole('radiogroup', { name: 'Kesinti kaynaklı mı?' }),
    yes: () => within(within(dlg).getByRole('radiogroup', { name: 'Kesinti kaynaklı mı?' })).getByRole('radio', { name: 'Evet' }),
    no: () => within(within(dlg).getByRole('radiogroup', { name: 'Kesinti kaynaklı mı?' })).getByRole('radio', { name: 'Hayır' }),
    outageId: () => within(dlg).queryByRole('textbox', { name: 'Kesinti ID' }),
    field: (name) => dlg.querySelector(`[data-field="${name}"]`),
    payload: (n = 0) => onSubmit.mock.calls[n][0],
  }
}

describe('olay formu — teknik hata adedi ve kesinti alanları (Etki bölümü)', () => {
  it('alanlar Etki bölümünde: adet kutusu (sayısal tuş takımı + ipucu), üç durumlu seçim; Kesinti ID başta yok', async () => {
    const f = await open()
    await waitFor(() => expect(f.tab('impact')).toHaveAttribute('aria-selected', 'true'))
    const count = f.count()
    expect(count).toHaveAttribute('inputmode', 'numeric')
    expect(count).toHaveAttribute('type', 'text')                    // yazılan metin olduğu gibi tutulur (sessizce boşa dönmez)
    expect(count).toHaveValue('')
    expect(describedText(count)).toContain(HINT)
    expect(TR['inc.techFaultCountHint']).toBe(HINT)
    // "Etkilenen İşlem Adedi"nin hemen ardından gelir
    const order = [...f.dlg.querySelectorAll('label')].map((l) => l.textContent.trim())
    expect(order.indexOf('Teknik hata adedi')).toBe(order.indexOf('Etkilenen İşlem Adedi') + 1)

    const group = f.group()
    expect(group).toHaveAttribute('data-answer', 'unknown')
    expect(f.yes()).toHaveAttribute('aria-checked', 'false')
    expect(f.no()).toHaveAttribute('aria-checked', 'false')         // bilinmiyor: "Hayır" seçili DEĞİL
    expect(describedText(group)).toContain(TR['inc.outageRelatedHint'])
    expect(f.outageId()).toBeNull()
    // dokunma hedefi sınıfları (ölçüm e2e'de): telefonda ve dokunmatikte ≥ 40 px
    for (const item of [f.yes(), f.no()]) {
      expect(item.className).toContain('pointer-coarse:min-h-10')
      expect(item.className).toContain('max-sm:min-h-10')
    }
    // Açılışta kirli değil
    expect(f.dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeNull()
  })

  it('üç durum: Evet → Kesinti ID açılır ve boşken odak ona gider; Hayır → gizlenir; seçiliye yeniden basmak seçimi KALDIRIR', async () => {
    const f = await open()
    fireEvent.click(f.yes())
    expect(f.yes()).toHaveAttribute('aria-checked', 'true')
    expect(f.group()).toHaveAttribute('data-answer', 'yes')
    const id = f.outageId()
    expect(id).not.toBeNull()
    expect(describedText(id)).toContain(TR['inc.outageIdHint'])
    await waitFor(() => expect(document.activeElement).toBe(id))

    fireEvent.click(f.no())
    expect(f.no()).toHaveAttribute('aria-checked', 'true')
    expect(f.yes()).toHaveAttribute('aria-checked', 'false')
    expect(f.group()).toHaveAttribute('data-answer', 'no')
    expect(f.outageId()).toBeNull()

    fireEvent.click(f.no())                                          // seçili cevaba yeniden bas → bilinmiyor
    expect(f.no()).toHaveAttribute('aria-checked', 'false')
    expect(f.yes()).toHaveAttribute('aria-checked', 'false')
    expect(f.group()).toHaveAttribute('data-answer', 'unknown')
    expect(f.outageId()).toBeNull()

    fireEvent.click(f.yes())
    fireEvent.click(f.yes())                                         // Evet'i de kaldırır
    expect(f.group()).toHaveAttribute('data-answer', 'unknown')
    expect(f.outageId()).toBeNull()
  })

  it('gövde (YENİ kayıt): üç anahtar hep gövdede — dokunulmamışsa null; adet SAYI, Evet + kırpılmış kimlik; Hayır / bilinmiyor iken kimlik null', async () => {
    const f = await open({ mode: 'create', initial: { id: undefined } })
    f.save()
    await f.saved(1)
    expect(f.payload(0)).toMatchObject({ title: 'Ödeme kesintisi', tech_error_count: null, outage_related: null, outage_id: null })
    expect(sentKeys(f.payload(0))).toEqual(KEYS)

    fireEvent.change(f.count(), { target: { value: ' 1250 ' } })
    fireEvent.click(f.yes())
    fireEvent.change(f.outageId(), { target: { value: '  INC-2026-0915 ' } })
    expect(f.dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeTruthy()
    f.save()
    await f.saved(2)
    expect(f.payload(1)).toMatchObject({ tech_error_count: 1250, outage_related: true, outage_id: 'INC-2026-0915' })

    // Hayır: alanda kalan kimlik GÖNDERİLMEZ
    fireEvent.click(f.no())
    f.save()
    await f.saved(3)
    expect(f.payload(2)).toMatchObject({ tech_error_count: 1250, outage_related: false, outage_id: null })

    // Yanlışlıkla Hayır'a basan kullanıcı Evet'e dönünce yazdığı kimlik yerindedir (aynı form oturumunda)
    fireEvent.click(f.yes())
    expect(f.outageId()).toHaveValue('  INC-2026-0915 ')

    // Bilinmiyor (Evet'e yeniden bas) + adet boşaltıldı → üçü de null
    fireEvent.click(f.yes())
    fireEvent.change(f.count(), { target: { value: '' } })
    f.save()
    await f.saved(4)
    expect(f.payload(3)).toMatchObject({ tech_error_count: null, outage_related: null, outage_id: null })

    // 0 girilmiş bir değerdir (bilinmiyor değil); Evet + boş kimlik geçerlidir (zorunlu değil)
    fireEvent.change(f.count(), { target: { value: '0' } })
    fireEvent.click(f.yes())
    fireEvent.change(f.outageId(), { target: { value: '   ' } })
    f.save()
    await f.saved(5)
    expect(f.payload(4)).toMatchObject({ tech_error_count: 0, outage_related: true, outage_id: null })
  })

  it('gövde (DÜZENLEME): yalnız DEĞİŞEN anahtar gönderilir; dokunulmamış ESKİ kayıt (üçü de boş) üçünü de GÖNDERMEZ', async () => {
    const f = await open()                                           // eski kayıt: üç anahtar satırda hiç yok
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()                        // "Sağlam Kayıt": değişiklik yok → istek yok

    fireEvent.change(f.count(), { target: { value: '1250' } })       // yalnız adet değişti
    f.save()
    await f.saved(1)
    expect(sentKeys(f.payload(0))).toEqual(['tech_error_count'])
    expect(f.payload(0).tech_error_count).toBe(1250)
    expect(f.payload(0)).toMatchObject({ id: 9 })
    expect(f.payload(0)).not.toHaveProperty('title')                 // değişmeyen öteki alanlar da gönderilmez

    fireEvent.click(f.yes())                                         // + cevap + kimlik
    fireEvent.change(f.outageId(), { target: { value: ' INC-2026-0915 ' } })
    f.save()
    await f.saved(2)
    expect(sentKeys(f.payload(1))).toEqual(KEYS)
    expect(f.payload(1)).toMatchObject({ tech_error_count: 1250, outage_related: true, outage_id: 'INC-2026-0915' })

    fireEvent.click(f.yes())                                         // hepsi açılıştaki hâline döndü → istek yok
    fireEvent.change(f.count(), { target: { value: '' } })
    f.save()
    expect(f.onSubmit).toHaveBeenCalledTimes(2)
    expect(f.dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeNull()
  })

  it('düzenleme: kayıtlı değerler formda görünür, açılışta kirli değil, odak Kesinti ID\'ye taşınmaz; dokunulmamış kayıt üç anahtarı GÖNDERMEZ', async () => {
    const f = await open({ initial: { tech_error_count: 1250, outage_related: true, outage_id: 'INC-7' } })
    await waitFor(() => expect(f.tab('impact')).toHaveAttribute('aria-selected', 'true'))
    expect(f.count()).toHaveValue('1250')
    expect(f.yes()).toHaveAttribute('aria-checked', 'true')
    expect(f.outageId()).toHaveValue('INC-7')
    expect(document.activeElement).not.toBe(f.outageId())
    expect(f.dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeNull()
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()                        // değişiklik yok → istek yok

    // "01250" aynı sayıdır → değişiklik değil → yine istek yok
    fireEvent.change(f.count(), { target: { value: '01250' } })
    f.save()
    expect(f.onSubmit).not.toHaveBeenCalled()
    fireEvent.change(f.count(), { target: { value: '1250' } })

    // Evet → Hayır: cevap false VE kimlik null gönderilir (adet değişmedi → yok); Kesinti ID alanı gizlenir
    fireEvent.click(f.no())
    expect(f.outageId()).toBeNull()
    f.save()
    await f.saved(1)
    expect(sentKeys(f.payload(0))).toEqual(['outage_related', 'outage_id'])
    expect(f.payload(0)).toMatchObject({ outage_related: false, outage_id: null })

    // Evet → bilinmiyor (seçimi kaldır): cevap null VE kimlik null
    fireEvent.click(f.no())
    expect(f.group()).toHaveAttribute('data-answer', 'unknown')
    f.save()
    await f.saved(2)
    expect(sentKeys(f.payload(1))).toEqual(['outage_related', 'outage_id'])
    expect(f.payload(1)).toMatchObject({ outage_related: null, outage_id: null })

    // Yeniden Evet: aynı oturumda kimlik yerinde → kayıtla aynı → istek yok
    fireEvent.click(f.yes())
    expect(f.outageId()).toHaveValue('INC-7')
    f.save()
    expect(f.onSubmit).toHaveBeenCalledTimes(2)

    // Yalnız kimlik silindi → yalnız outage_id: null; adet 0 → yalnız girilen değer
    fireEvent.change(f.outageId(), { target: { value: '' } })
    fireEvent.change(f.count(), { target: { value: '0' } })
    f.save()
    await f.saved(3)
    expect(sentKeys(f.payload(2))).toEqual(['tech_error_count', 'outage_id'])
    expect(f.payload(2)).toMatchObject({ tech_error_count: 0, outage_id: null })
  })

  it('düzenleme: "Hayır" kayıtlı olay Hayır seçili açılır (kimlik alanı yok); 0 adet "0" görünür', async () => {
    const f = await open({ initial: { tech_error_count: 0, outage_related: false, outage_id: null } })
    await waitFor(() => expect(f.tab('impact')).toHaveAttribute('aria-selected', 'true'))
    expect(f.count()).toHaveValue('0')
    expect(f.no()).toHaveAttribute('aria-checked', 'true')
    expect(f.yes()).toHaveAttribute('aria-checked', 'false')
    expect(f.outageId()).toBeNull()
    expect(f.dlg.querySelector('[data-slot="ih-form-dirty"]')).toBeNull()
  })

  it('istemci doğrulaması: geçersiz adet / uzun kimlik kaydı durdurur, ileti ALANIN altında; düzeltince kalkar', async () => {
    const f = await open()
    fireEvent.change(f.count(), { target: { value: '1.250' } })
    fireEvent.click(f.yes())
    fireEvent.change(f.outageId(), { target: { value: 'x'.repeat(61) } })
    f.save()
    await waitFor(() => expect(f.field('tech_error_count').textContent).toContain(TR['inc.errTechCountWhole']))
    expect(f.onSubmit).not.toHaveBeenCalled()
    expect(f.count()).toHaveAttribute('aria-invalid', 'true')
    expect(describedText(f.count())).toContain(TR['inc.errTechCountWhole'])
    expect(f.field('outage_id').textContent).toContain(TR['inc.errOutageIdLong'])
    expect(f.outageId()).toHaveAttribute('aria-invalid', 'true')
    expect(f.tab('impact')).toHaveAttribute('data-invalid', 'true')
    expect(f.count()).toHaveValue('1.250')                           // yazılan metin korunur

    fireEvent.change(f.count(), { target: { value: '-3' } })
    expect(f.field('tech_error_count').textContent).toContain(TR['inc.errTechCountNegative'])
    fireEvent.change(f.count(), { target: { value: '1000000000000001' } })
    expect(f.field('tech_error_count').textContent).toContain(TR['inc.errTechCountMax'])

    fireEvent.change(f.count(), { target: { value: '1250' } })
    fireEvent.change(f.outageId(), { target: { value: 'x'.repeat(60) } })
    expect(f.count()).not.toHaveAttribute('aria-invalid', 'true')
    expect(f.outageId()).not.toHaveAttribute('aria-invalid', 'true')
    expect(f.tab('impact')).not.toHaveAttribute('data-invalid')
    f.save()
    await f.saved(1)
    expect(f.payload(0)).toMatchObject({ tech_error_count: 1250, outage_related: true, outage_id: 'x'.repeat(60) })
  })

  it('uzun kimlik cevap Hayır iken hata DEĞİLDİR (alan gizli; alanda kalan metin gövdeye YAZILMAZ)', async () => {
    // Düzenleme: kimlik kayıtta da boştu → anahtar hiç gönderilmez (sunucu Hayır'da kimliği zaten null tutar)
    const f = await open()
    fireEvent.click(f.yes())
    fireEvent.change(f.outageId(), { target: { value: 'x'.repeat(80) } })
    fireEvent.click(f.no())
    f.save()
    await f.saved(1)
    expect(sentKeys(f.payload(0))).toEqual(['outage_related'])
    expect(f.payload(0).outage_related).toBe(false)
    expect(JSON.stringify(f.payload(0))).not.toContain('xxxx')
  })

  it('uzun kimlik cevap Hayır iken (YENİ kayıt): gövdede kimlik null', async () => {
    const f = await open({ mode: 'create', initial: { id: undefined } })
    fireEvent.click(f.yes())
    fireEvent.change(f.outageId(), { target: { value: 'x'.repeat(80) } })
    fireEvent.click(f.no())
    f.save()
    await f.saved(1)
    expect(f.payload(0)).toMatchObject({ outage_related: false, outage_id: null })
    expect(JSON.stringify(f.payload(0))).not.toContain('xxxx')
  })

  it('sunucu alan hatası alanın ALTINDA (bant yok); alan düzenlenince kalkar', async () => {
    const msg = 'Teknik hata adedi çok büyük: en çok 1.000.000.000.000.000 (10^15) olabilir. Değeri kontrol edip yeniden yazın.'
    const onSubmit = vi.fn(async () => ({ ok: false, error: msg, fields: { tech_error_count: msg } }))
    const f = await open({ onSubmit })
    fireEvent.change(f.count(), { target: { value: '5' } })
    f.save()
    await waitFor(() => expect(f.field('tech_error_count').textContent).toContain(msg))
    expect(within(f.dlg).queryByRole('alert')).toBeNull()            // üstteki "kaydedilemedi" bandı YOK
    expect(f.count()).toHaveAttribute('aria-invalid', 'true')
    expect(describedText(f.count())).toContain(msg)
    expect(f.tab('impact')).toHaveAttribute('data-invalid', 'true')
    await waitFor(() => expect(document.activeElement).toBe(f.count()))
    expect(f.count()).toHaveValue('5')                               // form kaybolmaz

    fireEvent.change(f.count(), { target: { value: '6' } })          // düzenlenince o alanın sunucu iletisi kalkar
    expect(f.field('tech_error_count').textContent).not.toContain(msg)
    expect(f.count()).not.toHaveAttribute('aria-invalid', 'true')
    expect(f.tab('impact')).not.toHaveAttribute('data-invalid')
  })

  it('sunucu alan hatası başka bölümdeyken: Etki bölümüne geçilir, ileti Kesinti ID altında, odak alanda', async () => {
    const msg = 'Kesinti ID en çok 60 karakter olabilir (girilen: 61). Yalnız kesinti kaydının kimliğini yazın.'
    const onSubmit = vi.fn(async () => ({ ok: false, error: msg, fields: { outage_id: msg } }))
    const f = await open({ onSubmit, impact: false, initial: { outage_related: true, outage_id: 'INC-1' } })
    expect(f.tab('summary')).toHaveAttribute('aria-selected', 'true')
    fireEvent.change(within(f.dlg).getByRole('textbox', { name: /^Başlık/ }), { target: { value: 'Ödeme kesintisi (düzeltildi)' } })
    f.save()
    await waitFor(() => expect(f.tab('impact')).toHaveAttribute('aria-selected', 'true'))
    expect(f.field('outage_id').textContent).toContain(msg)
    expect(within(f.dlg).queryByRole('alert')).toBeNull()
    await waitFor(() => expect(document.activeElement).toBe(f.outageId()))

    // Cevap değişince kimliğin sunucu iletisi de geçersizdir
    fireEvent.click(f.no())
    expect(f.tab('impact')).not.toHaveAttribute('data-invalid')
    fireEvent.click(f.yes())
    expect(f.field('outage_id').textContent).not.toContain(msg)
  })

  it('"Kesinti kaynaklı mı?" için sunucu alan hatası seçimin altında görünür ve cevap değişince kalkar', async () => {
    const msg = '"Kesinti kaynaklı mı?" cevabı yalnız evet (true), hayır (false) ya da boş (bilinmiyor) olabilir.'
    const onSubmit = vi.fn(async () => ({ ok: false, error: msg, fields: { outage_related: msg } }))
    const f = await open({ onSubmit })
    fireEvent.change(f.count(), { target: { value: '5' } })          // değişiklik yoksa istek gitmez
    f.save()
    await waitFor(() => expect(f.field('outage_related').textContent).toContain(msg))
    expect(describedText(f.group())).toContain(msg)
    expect(within(f.dlg).queryByRole('alert')).toBeNull()
    fireEvent.click(f.yes())
    expect(f.field('outage_related').textContent).not.toContain(msg)
  })

  it('formda yuvası olmayan alanın hatası (ve alansız hata) eskisi gibi üstteki bantta kalır', async () => {
    const onSubmit = vi.fn()
      .mockResolvedValueOnce({ ok: false, error: 'Kaynak alarm bulunamadı.', fields: { alert_event_id: 'Kaynak alarm bulunamadı.' } })
      .mockResolvedValueOnce({ ok: false, error: 'Bu olayı düzenleme yetkiniz yok.' })
      .mockResolvedValueOnce({ ok: false, error: 'İki hata', fields: { alert_event_id: 'Kaynak alarm bulunamadı.', tech_error_count: 'Adet geçersiz, yalnız rakam yazın.' } })
    const f = await open({ onSubmit })
    fireEvent.change(f.count(), { target: { value: '7' } })          // değişiklik yoksa istek gitmez
    f.save()
    await waitFor(() => expect(within(f.dlg).getByRole('alert').textContent).toContain('Kaynak alarm bulunamadı.'))
    expect(f.tab('impact')).not.toHaveAttribute('data-invalid')

    f.save()
    await waitFor(() => expect(within(f.dlg).getByRole('alert').textContent).toContain('Bu olayı düzenleme yetkiniz yok.'))

    // Karışık: gösterilebilen alan altında + gösterilemeyen için bant
    f.save()
    await waitFor(() => expect(f.field('tech_error_count').textContent).toContain('Adet geçersiz, yalnız rakam yazın.'))
    expect(within(f.dlg).getByRole('alert').textContent).toContain('İki hata')
  })
})
