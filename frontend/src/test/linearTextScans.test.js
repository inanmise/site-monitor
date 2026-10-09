import { describe, it, expect } from 'vitest'
import { safeHttpUrl, splitLinks } from '../components/inventory/inventoryDetailModel.js'
import { scenarioTarget } from '../components/scripted/scriptedCardModel.js'
import { isTechnicalMessage, isVagueMessage } from '../utils/errorMessages.js'
import { applyTemplate } from '../components/incidents/actionNoteModel.js'
import { markdownImageCaptions, uniqueCaption } from '../components/incidenthistory/incidentHistoryModel.js'

/**
 * Sonsuz döngü / ReDoS denetimi (2026-10-09): ana iş parçacığında çizilen sunucu / kayıt metnine uygulanan `x+$` ve
 * `.*` ifadeleri O(N²) geri izliyordu (20 000 karakterlik bir dizi ≈ 1 sn; 300 000 ≈ dakikalar — sekme donar). Yerlerine
 * doğrusal taramalar kondu. Her yardımcı için: (1) ESKİ ifade burada kâhin olarak durur ve normal + rastgele girdilerde
 * çıktı BİREBİR aynı; (2) uzun patolojik girdi dar zaman aşımında biter.
 */

const LONG = 300_000
const FAST_MS = 3000

/** Tohumlu (tekrarlanabilir) rastgele: parçalardan metin üretir. */
function fuzz(tokens, count, maxLen, seed = 7) {
  let s = seed >>> 0
  const rnd = (n) => { s = (Math.imul(s, 1103515245) + 12345) >>> 0; return s % n }
  const out = []
  for (let i = 0; i < count; i++) {
    let t = ''
    const len = rnd(maxLen) + 1
    for (let j = 0; j < len; j++) t += tokens[rnd(tokens.length)]
    out.push(t)
  }
  return out
}

describe('envanter açıklaması — splitLinks (sondaki noktalama)', () => {
  // ESKİ uygulama (kâhin)
  const OLD_URL = /https?:\/\/[^\s<>"'`]+/gi
  const OLD_TRAIL = /[.,;:!?)\]}'"]+$/
  function oldSplitLinks(text) {
    const src = String(text ?? '')
    const out = []
    let last = 0
    for (const m of src.matchAll(OLD_URL)) {
      let value = m[0]
      const trail = value.match(OLD_TRAIL)
      if (trail) value = value.slice(0, value.length - trail[0].length)
      const href = safeHttpUrl(value)
      if (!href) continue
      if (m.index > last) out.push({ type: 'text', value: src.slice(last, m.index) })
      out.push({ type: 'url', value, href })
      last = m.index + value.length
    }
    if (last < src.length) out.push({ type: 'text', value: src.slice(last) })
    if (out.length === 0) out.push({ type: 'text', value: src })
    return out
  }

  it('normal metinlerde eskisiyle birebir aynı', () => {
    const samples = [
      'bkz. https://wiki.example.com/x.', 'Runbook: (https://a.example.com/b?c=1).', 'https://x.example.com/"\'}])!?',
      'iki adres https://a.example.com, https://b.example.com; son', 'adres yok', '', 'http://a.example.com/x...y.',
      'https://', 'https://.', 'metin https://a.example.com/yol/ devam',
    ]
    for (const s of samples) expect(splitLinks(s), s).toEqual(oldSplitLinks(s))
  })

  it('rastgele girdilerde eskisiyle birebir aynı', () => {
    for (const s of fuzz(['https://a.example.com', '/x', '.', ',', ')', ']', '"', "'", '!', '?', ' ', 'y', ';', ':'], 400, 12)) {
      expect(splitLinks(s), JSON.stringify(s)).toEqual(oldSplitLinks(s))
    }
  })

  it('ortasında uzun noktalama dizisi olan adres hızla çözülür', { timeout: FAST_MS }, () => {
    const text = `bkz. https://a.example.com/${'.'.repeat(LONG)}x)`
    const parts = splitLinks(text)
    expect(parts.find((p) => p.type === 'url')?.value.endsWith('x')).toBe(true)
  })
})

describe('sentetik kart — scenarioTarget (adres sonu noktalama)', () => {
  it('normal ve rastgele adreslerde eskisiyle aynı hedef', () => {
    const OLD_URL = /https?:\/\/[^\s'"`<>)\]}]+/gi
    const old = (u) => u.replace(/[.,;:]+$/, '')
    for (const s of ['https://api.example.com/v1.', 'https://api.example.com/a;b,:', ...fuzz(['https://h.example.com', '/p', '.', ',', ';', ':', 'q'], 200, 8)]) {
      const script = `http.get('${s}')`
      const expected = [...script.matchAll(OLD_URL)].map((m) => old(m[0]))[0]
      const got = scenarioTarget({ script })
      if (got) expect(got.url, s).toBe(expected)
    }
  })

  it('uzun noktalama dizisi hızla atlanır', { timeout: FAST_MS }, () => {
    const t = scenarioTarget({ script: `http.get("https://h.example.com/${'.'.repeat(LONG)}x")` })
    expect(t.host).toBe('h.example.com')
  })
})

describe('hata metni — isVagueMessage / isTechnicalMessage', () => {
  const oldNormalize = (msg) => String(msg).trim().toLowerCase().replace(/̇/g, '').replace(/[\s.!…:;]+$/u, '')
  const OLD_SPRING = /Whitelabel Error Page|No message available|"timestamp"\s*:.*"status"\s*:/i

  it('sondaki noktalama / boşluk atma eskisiyle aynı (normal + rastgele)', () => {
    const VAGUE_LIKE = ['Hata.', 'Sunucu hatası!!!', 'İşlem başarısız…', 'error: ', 'Forbidden\u00a0.', 'Not found;:', 'Hata: alan boş']
    const tokens = ['hata', 'İşlem başarısız', 'error', '.', '!', '…', ':', ';', ' ', '\u00a0', '\n', 'x']
    for (const s of [...VAGUE_LIKE, ...fuzz(tokens, 400, 6)]) {
      // isVagueMessage = normalize sonucu boş ya da kümede — normalize'ı dolaylı karşılaştır
      const viaOld = oldNormalize(s)
      expect(isVagueMessage(s), JSON.stringify(s)).toBe(isVagueMessage(viaOld))
    }
  })

  it('Spring hata gövdesi algısı eskisiyle aynı (aynı satırda "timestamp": … "status":)', () => {
    const samples = [
      '{"timestamp":"2026-10-09T10:00:00Z","status":500,"error":"Internal Server Error"}',
      '{"timestamp" : 1, \n "status": 500}', '{"status":500,"timestamp":1}', '{"TIMESTAMP":1,"Status" :2}',
      '"timestamp"\n:"x" "status":1', '"timestamp":1\u2028"status":2', '"timestamp":1\r"status":2', 'Whitelabel Error Page',
      '"timestamp":"timestamp":"status"\n:', 'düz metin',
    ]
    const tokens = ['"timestamp"', '"status"', ':', ' ', '\n', '\r', '\u2028', 'x', '"TIMESTAMP"', '"', 'status', '\t']
    // Örnek ve parçalar diğer TECHNICAL kalıplarına uymaz — sonuç yalnız bu kalıba bağlı
    for (const s of [...samples, ...fuzz(tokens, 600, 10)]) {
      expect(isTechnicalMessage(s), JSON.stringify(s)).toBe(OLD_SPRING.test(s))
    }
  })

  it('uzun girdiler hızla sınıflanır', { timeout: FAST_MS }, () => {
    expect(isTechnicalMessage('"timestamp":'.repeat(LONG / 12))).toBe(false)
    expect(isVagueMessage(`hata${'.'.repeat(LONG)}x`)).toBe(false)
    expect(isVagueMessage(`hata${'.'.repeat(LONG)}`)).toBe(true)
  })
})

describe('gerekçe notu — applyTemplate (sondaki boşluk / ayraç)', () => {
  function oldApply(note, text) {
    const cur = String(note ?? '')
    const tpl = String(text ?? '').trim()
    if (!tpl) return cur
    const at = cur.indexOf(tpl)
    if (at >= 0) {
      let before = cur.slice(0, at)
      let after = cur.slice(at + tpl.length).replace(/^[.;,]?[ \t]*/, '')
      if (!after.trim()) { before = before.replace(/[\s.;,]+$/, ''); after = '' }
      return before + after
    }
    const base = cur.replace(/\s+$/, '')
    if (!base) return tpl
    return base + (/[.!?…;:,]$/.test(base) ? ' ' : '. ') + tpl
  }

  it('normal ve rastgele notlarda eskisiyle aynı', () => {
    const TPL = 'Sağlayıcı tarafında arıza'
    const samples = ['', 'Not', 'Not.', `Not. ${TPL}`, `Not, ${TPL}.`, `${TPL}`, `a \t\n${TPL} ; sonra`, 'x\u00a0\u3000']
    for (const s of [...samples, ...fuzz(['a', ' ', '.', ';', ',', '\n', '\t', '\u00a0', TPL], 400, 8)]) {
      expect(applyTemplate(s, TPL), JSON.stringify(s)).toBe(oldApply(s, TPL))
    }
  })

  it('ortasında uzun boşluk dizisi olan not hızla işlenir', { timeout: FAST_MS }, () => {
    const note = `a${' '.repeat(LONG)}b`
    expect(applyTemplate(note, 'Şablon metni')).toBe(`${note}. Şablon metni`)
    expect(applyTemplate(`a${' ;'.repeat(LONG / 2)}b Şablon metni`, 'Şablon metni').endsWith('b')).toBe(true)
  })
})

describe('olay formu — markdown görsel adları', () => {
  const OLD_IMG = /!\[([^\]]*)\]\([^)]*\)/g
  const oldCaptions = (s) => [...s.matchAll(OLD_IMG)].map((m) => m[1])

  it('normal ve rastgele metinlerde eskisiyle aynı adlar', () => {
    const samples = ['![a.png](x)', 'önce ![b](y) sonra ![c d](z)', '![a![b](c)', '![x] (y)', '![x](y', '!![q](r)', '![](u)', '![a]\n(b)']
    for (const s of [...samples, ...fuzz(['![', ']', '(', ')', 'a', '!', '[', '\n', ' '], 800, 14)]) {
      expect(markdownImageCaptions(s), JSON.stringify(s)).toEqual(oldCaptions(s))
    }
  })

  it('uniqueCaption davranışı aynı', () => {
    const form = { description: '![rapor.png](/api/x) ve ![rapor (2).png](/api/y)', rca_summary: '![grafik](/api/z)' }
    expect(uniqueCaption('rapor.png', form, [])).toBe('rapor (3).png')
    expect(uniqueCaption('grafik', form, [])).toBe('grafik (2)')
    expect(uniqueCaption('yeni.png', form, [])).toBe('yeni.png')
  })

  it('çok sayıda kapanışsız `![` hızla taranır', { timeout: FAST_MS }, () => {
    expect(markdownImageCaptions('!['.repeat(LONG))).toEqual([])
    expect(markdownImageCaptions('![a]('.repeat(LONG / 5))).toEqual([])
    expect(markdownImageCaptions(`${'![a]x'.repeat(LONG / 5)}![son](u)`)).toEqual(['son'])
  })
})
