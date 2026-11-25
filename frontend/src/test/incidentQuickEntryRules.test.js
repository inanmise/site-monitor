import { describe, it, expect, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  runbookUrlProblem, validateIncident, EMPTY_FORM, MARKDOWN_KEYS, payloadFromForm, editPayload,
} from '../components/incidenthistory/incidentHistoryModel.js'
import { draftScope } from '../components/incidenthistory/incidentDraft.js'
import {
  UPLOAD_SCHEME, uploadToken, safeAlt, uploadPlaceholder, hasUploadPlaceholder, replaceUploadPlaceholder,
  stripUploadPlaceholders, beginUpload, endUpload, uploadInFlight, hasPendingUpload, uploadTokensIn, removeUploadPlaceholders,
  resetUploadsForTests,
} from '../utils/mdUpload.js'
import { trapTab, focusablesIn } from '../utils/focusTrap.js'
import { incidentPrepKey, INCIDENT_PREP_KEYS } from '../components/incidenthistory/formFields.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * "Hızlı Giriş" (2026-11-20) istemci kuralları: runbook bağlantısı (sunucuyla AYNI tablo — Java testinin dizileri okunur),
 * yükleme yer tutucuları, çoğaltma taslak kapsamı ve ayrıntı Sheet'inin odak tuzağı.
 */

const JAVA_TEST = path.resolve(__dirname, '../../../backend/src/test/java/com/sitemonitor/service/IncidentServiceQuickEntryTest.java')

/** Java kaynağındaki `for (String <ad> : new String[]{ … })` dizisini okur (basit kaçışlar çözülür). */
function javaStrings(src, varName) {
  const head = `for (String ${varName} : new String[]{`
  const at = src.indexOf(head)
  if (at < 0) throw new Error('dizi bulunamadı: ' + varName)
  const end = src.indexOf('})', at)
  const body = src.slice(at + head.length, end)
  const out = []
  let i = 0
  while (i < body.length) {
    const q = body.indexOf('"', i)
    if (q < 0) break
    let s = ''
    let j = q + 1
    for (; j < body.length && body[j] !== '"'; j++) {
      if (body[j] === '\\') {
        const n = body[++j]
        if (n === 'u') {
          s += String.fromCharCode(parseInt(body.slice(j + 1, j + 5), 16))
          j += 4
        } else s += n === 't' ? '\t' : n === 'n' ? '\n' : n
      } else s += body[j]
    }
    out.push(s)
    i = j + 1
  }
  return out
}

describe('runbook bağlantısı — sunucuyla aynı tablo', () => {
  const src = fs.readFileSync(JAVA_TEST, 'utf8')
  const ok = javaStrings(src, 'ok')
  const bad = javaStrings(src, 'bad')

  it('tablo okunabildi', () => {
    expect(ok.length).toBeGreaterThanOrEqual(5)
    expect(bad.length).toBeGreaterThanOrEqual(10)
  })
  it.each(ok)('geçerli: %s', (v) => expect(runbookUrlProblem(v)).toBe(false))
  it.each(bad)('geçersiz: %s', (v) => expect(runbookUrlProblem(v)).toBe(true))

  it('boş değer serbest; yalnız DEĞİŞEN değer denetlenir', () => {
    expect(runbookUrlProblem('')).toBe(false)
    expect(runbookUrlProblem('   ')).toBe(false)
    const base = { ...EMPTY_FORM, id: 1, title: 'a', occurred_at: '2026-11-18T10:00:00', team_id: 7, runbook_url: 'javascript:x' }
    expect(validateIncident({ ...base }, base).runbook_url).toBeUndefined()
    expect(validateIncident({ ...base, runbook_url: 'ftp://x/y' }, base).runbook_url).toBe('inc.errRunbookUrl')
    expect(validateIncident({ ...base, runbook_url: 'https://wiki/x' }, base).runbook_url).toBeUndefined()
  })
})

describe('yükleme yer tutucusu', () => {
  it('belirteç yalnız [a-z0-9] ve tekil', () => {
    const a = uploadToken(1)
    const b = uploadToken(1)
    expect(a).toMatch(/^[a-z0-9]+$/)
    expect(a).not.toBe(b)
  })

  it('alt metin güvenli: köşeli ayraç / satır sonu yok, en çok 120 karakter', () => {
    expect(safeAlt('a[b]c\nd')).toBe('a b c d')
    expect(safeAlt('')).toBe('image')
    expect(safeAlt('x'.repeat(500))).toHaveLength(120)
  })

  it('yer tutucu değiştirilir; arada yazılan metin korunur', () => {
    const ph = uploadPlaceholder('Grafik', 'tok1')
    expect(ph).toBe(`![Grafik](${UPLOAD_SCHEME}tok1)`)
    const typed = `Önce\n${ph}\nSonra yazılan`
    expect(hasUploadPlaceholder(typed)).toBe(true)
    const r = replaceUploadPlaceholder(typed, 'tok1', '![Grafik](/api/incidents/images/9)')
    expect(r).toEqual({ found: true, text: 'Önce\n![Grafik](/api/incidents/images/9)\nSonra yazılan' })
    expect(hasUploadPlaceholder(r.text)).toBe(false)
  })

  it('kullanıcı yer tutucuyu sildiyse metin aynen kalır; boş değiştirme siler', () => {
    expect(replaceUploadPlaceholder('metin', 'yok', 'x')).toEqual({ text: 'metin', found: false })
    const ph = uploadPlaceholder('a', 't2')
    expect(replaceUploadPlaceholder(`x ${ph} y`, 't2', '').text).toBe('x  y')
  })

  it('alt metni düzenlenmiş yer tutucu da (satır başından) bulunur', () => {
    const text = `ilk satır\nbozuk alt](${UPLOAD_SCHEME}t3)\nson`
    expect(replaceUploadPlaceholder(text, 't3', 'IMG').text).toBe('ilk satır\nIMG\nson')
  })

  it('tüm yer tutucular silinir (taslak); kapanmamış olan bırakılır; doğrusal', () => {
    const t = `a ${uploadPlaceholder('x', 'p1')} b ${uploadPlaceholder('y', 'p2')} c`
    expect(stripUploadPlaceholders(t)).toBe('a  b  c')
    expect(stripUploadPlaceholders('düz metin')).toBe('düz metin')
    expect(stripUploadPlaceholders(`a](${UPLOAD_SCHEME}eksik`)).toBe(`a](${UPLOAD_SCHEME}eksik`)
    const big = `${uploadPlaceholder('x', 'q')}\n`.repeat(20000)
    const t0 = Date.now()
    expect(stripUploadPlaceholders(big)).toBe('\n'.repeat(20000))
    expect(Date.now() - t0).toBeLessThan(2000)
  })

  afterEach(() => resetUploadsForTests())

  it('yalnız yüklemesi SÜREN yer tutucu kaydı durdurur (değişmemiş değerde de); sahipsiz olan durdurmaz', () => {
    beginUpload('z')
    for (const k of MARKDOWN_KEYS) {
      const f = { ...EMPTY_FORM, title: 'a', occurred_at: '2026-11-18T10:00:00', team_id: 7, [k]: `x ${uploadPlaceholder('g', 'z')}` }
      expect(validateIncident(f)[k]).toBe('inc.errUploadPending')
      expect(validateIncident(f, f)[k]).toBe('inc.errUploadPending')
    }
    endUpload('z')
    for (const k of MARKDOWN_KEYS) {
      const f = { ...EMPTY_FORM, title: 'a', occurred_at: '2026-11-18T10:00:00', team_id: 7, [k]: `x ${uploadPlaceholder('g', 'z')}` }
      expect(validateIncident(f)[k]).toBeUndefined()
    }
  })

  it('kayıt: süren yükleme kaydı; belirteç listesi ve süren yükleme denetimi doğrusal', () => {
    const t = `a ${uploadPlaceholder('x', 'p1')} b ${uploadPlaceholder('y', 'p2')}`
    expect(uploadTokensIn(t)).toEqual(['p1', 'p2'])
    expect(hasPendingUpload(t)).toBe(false)
    beginUpload('p2')
    expect(uploadInFlight('p2')).toBe(true)
    expect(hasPendingUpload(t)).toBe(true)
    expect(stripUploadPlaceholders(t, uploadInFlight)).toBe(`a  b ${uploadPlaceholder('y', 'p2')}`)
    expect(removeUploadPlaceholders(t, ['p2'])).toBe(`a ${uploadPlaceholder('x', 'p1')} b `)
    endUpload('p2')
    expect(hasPendingUpload(t)).toBe(false)
    const big = `${uploadPlaceholder('x', 'q')}
`.repeat(20000)
    const t0 = Date.now()
    expect(hasPendingUpload(big)).toBe(false)
    beginUpload('zz')
    expect(hasPendingUpload(big)).toBe(false)
    expect(Date.now() - t0).toBeLessThan(2000)
  })

  it('gövde (yeni ve düzenleme) hiçbir zaman yer tutucu taşımaz; yalnız sahipsiz yer tutucu eklenmiş metin "değişmedi" sayılır', () => {
    const base = { ...EMPTY_FORM, id: 1, version: 2, title: 'a', occurred_at: '2026-11-18T10:00:00', team_id: 7, description: 'Metin' }
    const stale = { ...base, description: `Metin${uploadPlaceholder('g', 'eski')}` }
    expect(payloadFromForm(stale).description).toBe('Metin')
    const { changed } = editPayload(stale, base)
    expect(changed).not.toContain('description')
    expect(validateIncident(stale, base).description).toBeUndefined()
  })
})

describe('görsel hazırlama hataları olay kaydının kendi metniyle (inceleme L6)', () => {
  const src = fs.readFileSync(path.resolve(__dirname, '../components/techerror/imagePrep.js'), 'utf8')
  const prepKeys = [...new Set(src.match(/te\.img\.err[A-Za-z]+/g) || [])]
  const placeholders = (v) => [...new Set(String(v).match(/\{\d\}/g) || [])].sort()

  it('imagePrep\'in her hata anahtarı inc.img.* karşılığına eşlenir; TR + EN metni var, yer tutucular aynı', () => {
    expect(prepKeys.length).toBeGreaterThanOrEqual(10)
    for (const k of prepKeys) {
      const inc = incidentPrepKey(k)
      expect(inc).toBe('inc.img.' + k.slice('te.img.'.length))
      expect(INCIDENT_PREP_KEYS).toContain(k.slice('te.img.'.length))
      for (const dict of [TR, EN]) {
        expect(typeof dict[inc], `${inc} eksik`).toBe('string')
        expect(placeholders(dict[inc]), inc).toEqual(placeholders(dict[k]))
        // kanal raporu dili ("yuva", "raporlama aracı", "slot") olay kullanıcısına ulaşmaz
        expect(dict[inc]).not.toMatch(/yuva|raporlama aracı|\bslot\b/i)
      }
    }
  })

  it('bilinmeyen / boş anahtar genel tarayıcı hatasına düşer', () => {
    expect(incidentPrepKey('te.img.errYeniBirSey')).toBe('inc.img.errBrowser')
    expect(incidentPrepKey('baska.anahtar')).toBe('inc.img.errBrowser')
    expect(incidentPrepKey(undefined)).toBe('inc.img.errBrowser')
    for (const n of INCIDENT_PREP_KEYS) expect(typeof TR['inc.img.' + n]).toBe('string')
  })
})

describe('çoğaltma taslak kapsamı', () => {
  it('kaynak kimliği sayıysa kendi kapsamı; değilse yeni kayıt kapsamı', () => {
    expect(draftScope('create', {}, 41)).toBe('new.copy.41')
    expect(draftScope('create', {}, '41')).toBe('new.copy.41')
    expect(draftScope('create', {}, 'x/../y')).toBe(draftScope('create', {}))
    expect(draftScope('create', {})).not.toContain('copy')
  })
})

describe('odak tuzağı', () => {
  function setup() {
    document.body.innerHTML = `
      <button id="outside">dış</button>
      <div id="root" tabindex="-1">
        <button id="first">1</button>
        <button id="hidden" hidden>gizli</button>
        <div aria-hidden="true"><button id="aria">a</button></div>
        <input id="mid" />
        <button id="disabled" disabled>x</button>
        <a id="last" href="#">son</a>
      </div>`
    return document.getElementById('root')
  }
  const tab = (target, shiftKey = false) => {
    const e = { key: 'Tab', shiftKey, target, prevented: false, preventDefault() { this.prevented = true } }
    return e
  }

  it('gizli / kapalı öğeler sayılmaz', () => {
    const root = setup()
    expect(focusablesIn(root).map((el) => el.id)).toEqual(['first', 'mid', 'last'])
  })

  it('sondan Tab başa, baştan Shift+Tab sona döner', () => {
    const root = setup()
    const last = document.getElementById('last')
    last.focus()
    const e = tab(last)
    expect(trapTab(e, root)).toBe(true)
    expect(e.prevented).toBe(true)
    expect(document.activeElement.id).toBe('first')
    const e2 = tab(document.activeElement, true)
    expect(trapTab(e2, root)).toBe(true)
    expect(document.activeElement.id).toBe('last')
  })

  it('ortadaki öğede tarayıcıya bırakır; hedef dışarıdaysa (portal pencere) karışmaz', () => {
    const root = setup()
    const mid = document.getElementById('mid')
    mid.focus()
    expect(trapTab(tab(mid), root)).toBe(false)
    const outside = document.getElementById('outside')
    outside.focus()
    const e = tab(outside)
    expect(trapTab(e, root)).toBe(false)
    expect(e.prevented).toBe(false)
  })

  it('başka tuş ve değiştirici yok sayılır', () => {
    const root = setup()
    expect(trapTab({ key: 'Enter', target: root }, root)).toBe(false)
    expect(trapTab({ key: 'Tab', ctrlKey: true, target: root }, root)).toBe(false)
    expect(trapTab(null, root)).toBe(false)
  })
})
