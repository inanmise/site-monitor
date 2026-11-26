import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { REPO_ROOT, readJavaMain } from './helpers/javaTable.js'
import { buildAssignment, buildResponsible } from './helpers/oncallRoutineFixtures.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  DAY_STATUSES, DAY_NOTE_MAX, EDIT_WINDOW_DAYS, ERROR_CODES, EVENT_KINDS, ITEM_NOTE_MAX, ITEM_STATES,
  MAX_IMAGES_PER_PROBLEM, PERIOD_KINDS, PROBLEM_NOTE_MAX, PROBLEM_NOTE_MIN, RESPONSIBLE_SOURCES, SEVERITIES,
  SIGNAL_KEYS, SKIP_REASON_MAX, SKIP_REASON_MIN,
} from '../components/oncallroutine/routineModel.js'
import {
  DEFAULT_LIMITS, FREQUENCIES, ID_RE, MONTHLY_RULES, SAVE_SOURCES, SETTINGS_MAX_TIMES,
} from '../components/oncallroutine/templateEditorModel.js'

/**
 * Nöbet Rutinleri — SUNUCU SÖZLEŞMESİ (2026-11-26). Ön yüz listeleri / sınırları Java kaynağındaki TEK kaynakla iki yönde
 * eşit olmalı (biri değişip öteki unutulursa kırmızı): hata kodları, olay türleri, gün / görev durumları, sabitler, canlı
 * sayaç anahtarları, şablon sınırları. Her kodun / türün / durumun / sayacın TR + EN metni vardır (sözleşme §9.10) ve
 * kod tabanında karşılığı olmayan yetim metin yoktur.
 */

const CODES_SRC = readJavaMain('service/oncallroutine/OnCallRoutineCodes.java')
const SIGNALS_SRC = readJavaMain('service/oncallroutine/OnCallRoutineSignals.java')
const TEMPLATE_SRC = readJavaMain('service/oncallroutine/OnCallRoutineTemplate.java')
const SETTINGS_SRC = readJavaMain('service/oncallroutine/OnCallRoutineSettings.java')
const ERRORS_SRC = readJavaMain('service/oncallroutine/OnCallRoutineErrors.java')
const SERVICE_SRC = readJavaMain('service/oncallroutine/OnCallRoutineService.java')
const CONTROLLER_SRC = readJavaMain('controller/OnCallRoutineController.java')

/** `NAME = List.of("a", "b", …);` → ['a', 'b', …]. Başka bir adın soneki eşleşmez (WIRE_DAY_STATUSES ≠ DAY_STATUSES). */
function javaList(src, name) {
  const m = new RegExp(`(?<![A-Za-z0-9_])${name}\\s*=\\s*List\\.of\\(([^;]*)\\);`).exec(src)
  if (!m) throw new Error(`${name} = List.of(…) Java kaynağında yok`)
  const out = [...m[1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1])
  if (!out.length) throw new Error(`${name} boş okundu`)
  return out
}

/** `static final int NAME = 123;` → 123 (çarpım / ifade içeren tanımlar burada okunmaz). */
function javaInt(src, name) {
  const m = new RegExp(`(?<![A-Za-z0-9_])${name}\\s*=\\s*(\\d+)\\s*;`).exec(src)
  if (!m) throw new Error(`${name} = <sayı>; Java kaynağında yok`)
  return Number(m[1])
}

/** Bir Java yönteminin gövdesi (imzadan ilk `return` satırının sonuna kadar). */
function javaMethod(src, signature) {
  const start = src.indexOf(signature)
  if (start < 0) throw new Error(`${signature} Java kaynağında yok`)
  const end = src.indexOf('return', start)
  return src.slice(start, end < 0 ? start + 2000 : end)
}

/** `s.length() < 3 || s.length() > 300` → [3, 300]. */
function lengthBounds(body) {
  const m = /length\(\)\s*<\s*(\d+)\s*\|\|\s*\w+\.length\(\)\s*>\s*(\d+)/.exec(body)
  if (!m) throw new Error('uzunluk sınırı okunamadı')
  return [Number(m[1]), Number(m[2])]
}

/** Sözlükte `<önek><AD>` biçimli anahtarların AD kümesi (`or.err.` → kodlar). */
function keysWithPrefix(dict, prefix, tail = /^[A-Z][A-Z0-9_]*$/) {
  return Object.keys(dict).filter((k) => k.startsWith(prefix)).map((k) => k.slice(prefix.length)).filter((s) => tail.test(s))
}

const DICTS = [['TR', TR], ['EN', EN]]

/** Ön yüz kaynağının metni (`frontend/src` altından). */
const readFrontend = (rel) => fs.readFileSync(path.join(REPO_ROOT, 'frontend/src', rel), 'utf8')

function expectText(dict, lang, key) {
  const v = dict[key]
  expect(typeof v === 'string' && v.trim().length > 0, `${lang} ${key} metni yok / boş`).toBe(true)
}

describe('nöbet rutinleri — sunucu kodları iki yönde eşit', () => {
  it('ERROR_CODES = OnCallRoutineCodes.ERROR_CODES (sıra dahil)', () => {
    expect([...ERROR_CODES]).toEqual(javaList(CODES_SRC, 'ERROR_CODES'))
  })

  it('sunucunun fırlattığı her kodlu hata (OnCallRoutineErrors) ERROR_CODES içinde — ön yüz her birini çevirir', () => {
    const thrown = [...new Set([...ERRORS_SRC.matchAll(/(?:Coded[A-Za-z]+Exception|FieldValidationException)\("([A-Z][A-Z0-9_]+)"/g)]
      .map((m) => m[1]))]
    expect(thrown.length).toBeGreaterThan(10)
    const missing = thrown.filter((c) => !ERROR_CODES.includes(c))
    expect(missing, 'ön yüzün tanımadığı sunucu kodu').toEqual([])
  })

  it('EVENT_KINDS = OnCallRoutineCodes.EVENT_KINDS (sıra dahil) ve her EV_* sabiti listede', () => {
    expect([...EVENT_KINDS]).toEqual(javaList(CODES_SRC, 'EVENT_KINDS'))
    const evConsts = [...CODES_SRC.matchAll(/String EV_[A-Z_]+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1])
    expect(evConsts.length).toBe(EVENT_KINDS.length)
    expect([...evConsts].sort()).toEqual([...EVENT_KINDS].sort())
  })

  it('DAY_STATUSES (ön yüz) = Java DAY_STATUSES ∪ WIRE_DAY_STATUSES (önce satır, sonra yalnız-tel kodları)', () => {
    const stored = javaList(CODES_SRC, 'DAY_STATUSES')
    const wire = javaList(CODES_SRC, 'WIRE_DAY_STATUSES')
    expect(stored).toContain('OPEN')
    expect(wire).toContain('NONE')
    expect(stored.filter((s) => wire.includes(s)), 'iki Java listesi ayrık olmalı').toEqual([])
    expect([...DAY_STATUSES]).toEqual([...stored, ...wire])
  })

  it('ITEM_STATES / PERIOD_KINDS / SEVERITIES / RESPONSIBLE_SOURCES = Java', () => {
    expect([...ITEM_STATES]).toEqual(javaList(CODES_SRC, 'ITEM_STATES'))
    expect([...PERIOD_KINDS]).toEqual(javaList(CODES_SRC, 'PERIOD_KINDS'))
    expect([...SEVERITIES]).toEqual(javaList(CODES_SRC, 'SEVERITIES'))
    expect([...RESPONSIBLE_SOURCES]).toEqual(javaList(CODES_SRC, 'RESPONSIBLE_SOURCES'))
  })

  it('şablon sıklıkları / aylık kurallar / kayıt kaynakları = Java', () => {
    expect([...FREQUENCIES]).toEqual(javaList(CODES_SRC, 'FREQUENCIES'))
    expect([...MONTHLY_RULES]).toEqual(javaList(CODES_SRC, 'MONTHLY_RULES'))
    expect([...SAVE_SOURCES]).toEqual(javaList(CODES_SRC, 'TEMPLATE_SOURCES'))
  })

  it('EDIT_WINDOW_DAYS ve MAX_IMAGES_PER_PROBLEM = Java', () => {
    expect(EDIT_WINDOW_DAYS).toBe(javaInt(CODES_SRC, 'EDIT_WINDOW_DAYS'))
    expect(MAX_IMAGES_PER_PROBLEM).toBe(javaInt(CODES_SRC, 'MAX_IMAGES_PER_PROBLEM'))
  })

  it('SIGNAL_KEYS = OnCallRoutineSignals.KEYS (sıra dahil) ve her anahtarın izin eşlemesi var', () => {
    expect([...SIGNAL_KEYS]).toEqual(javaList(SIGNALS_SRC, 'KEYS'))
    for (const k of SIGNAL_KEYS) expect(SIGNALS_SRC, k).toContain(`PERMS.put("${k}"`)
  })

  it('not / atlama / sorun metni sınırları = sunucu (controller sabitleri + doğrulama yöntemleri)', () => {
    expect(ITEM_NOTE_MAX).toBe(javaInt(CONTROLLER_SRC, 'ITEM_NOTE_MAX'))
    expect(DAY_NOTE_MAX).toBe(javaInt(CONTROLLER_SRC, 'DAY_NOTE_MAX'))
    expect([SKIP_REASON_MIN, SKIP_REASON_MAX]).toEqual(lengthBounds(javaMethod(SERVICE_SRC, 'public static String skipReason(')))
    expect([PROBLEM_NOTE_MIN, PROBLEM_NOTE_MAX]).toEqual(lengthBounds(javaMethod(SERVICE_SRC, 'public static String problemNote(')))
  })
})

describe('nöbet rutinleri — şablon sınırları = OnCallRoutineTemplate', () => {
  // Ön yüz anahtarı → Java sabiti. MAX_JSON_BYTES'in ön yüz ikizi yok (gövde sınırı yalnız sunucuda).
  const LIMIT_MAP = {
    sections: 'MAX_SECTIONS',
    tasks: 'MAX_TASKS',
    active_tasks: 'MAX_ACTIVE_TASKS',
    tasks_per_section: 'MAX_TASKS_PER_SECTION',
    links_per_task: 'MAX_LINKS_PER_TASK',
    links_total: 'MAX_LINKS',
    title: 'MAX_TITLE',
    section_title: 'MAX_SECTION_TITLE',
    description: 'MAX_DESCRIPTION',
    signals_per_task: 'MAX_SIGNALS_PER_TASK',
    url: 'MAX_URL',
    label: 'MAX_LABEL',
  }

  it('her DEFAULT_LIMITS anahtarının Java eşi var ve değeri aynı', () => {
    expect(Object.keys(DEFAULT_LIMITS).sort()).toEqual(Object.keys(LIMIT_MAP).sort())
    for (const [k, javaName] of Object.entries(LIMIT_MAP)) {
      expect(DEFAULT_LIMITS[k], `${k} ↔ ${javaName}`).toBe(javaInt(TEMPLATE_SRC, javaName))
    }
  })

  it('Java şablonundaki her MAX_* sınırının ön yüz eşi var (gövde baytı hariç)', () => {
    const javaMax = [...TEMPLATE_SRC.matchAll(/public static final int (MAX_[A-Z_]+)\s*=/g)].map((m) => m[1])
    const unmapped = javaMax.filter((n) => n !== 'MAX_JSON_BYTES' && !Object.values(LIMIT_MAP).includes(n))
    expect(unmapped, 'ön yüzde ikizi olmayan şablon sınırı').toEqual([])
  })

  it('görev / bölüm kimlik deseni = Java `ID` deseni (tam eşleşme)', () => {
    const m = /\bID\s*=\s*Pattern\.compile\("([^"]+)"\)/.exec(TEMPLATE_SRC)
    expect(m, 'Java ID deseni okunamadı').not.toBeNull()
    expect(ID_RE.source).toBe(`^${m[1]}$`)
  })

  it('hatırlatma saati sayısı = OnCallRoutineSettings.MAX_REMINDER_TIMES', () => {
    expect(SETTINGS_MAX_TIMES).toBe(javaInt(SETTINGS_SRC, 'MAX_REMINDER_TIMES'))
  })
})

describe('nöbet rutinleri — her kodun TR + EN metni (yetim metin yok)', () => {
  it('her hata kodunun `or.err.<KOD>` metni var; sözlükte listede olmayan `or.err.*` yok', () => {
    for (const [lang, dict] of DICTS) {
      for (const c of ERROR_CODES) expectText(dict, lang, `or.err.${c}`)
      expect(keysWithPrefix(dict, 'or.err.').sort(), `${lang} or.err.* = ERROR_CODES`).toEqual([...ERROR_CODES].sort())
    }
  })

  it('her olay türünün `or.ev.<TÜR>` metni var; yetim yok', () => {
    for (const [lang, dict] of DICTS) {
      for (const k of EVENT_KINDS) expectText(dict, lang, `or.ev.${k}`)
      expect(keysWithPrefix(dict, 'or.ev.').sort(), `${lang} or.ev.* = EVENT_KINDS`).toEqual([...EVENT_KINDS].sort())
    }
  })

  it('her gün durumunun (tel kodları dahil) `or.status.<DURUM>` metni var; yetim yok', () => {
    for (const [lang, dict] of DICTS) {
      for (const s of DAY_STATUSES) expectText(dict, lang, `or.status.${s}`)
      expect(keysWithPrefix(dict, 'or.status.').sort(), `${lang} or.status.* = DAY_STATUSES`).toEqual([...DAY_STATUSES].sort())
    }
  })

  it('her canlı sayacın `or.sig.<anahtar>.label|desc` metni var; tanımsız sayaç metni yok', () => {
    for (const [lang, dict] of DICTS) {
      for (const k of SIGNAL_KEYS) {
        expectText(dict, lang, `or.sig.${k}.label`)
        expectText(dict, lang, `or.sig.${k}.desc`)
      }
      const labelled = Object.keys(dict).filter((k) => /^or\.sig\.[a-z_]+\.label$/.test(k)).map((k) => k.split('.')[2])
      expect(labelled.sort(), `${lang} or.sig.*.label = SIGNAL_KEYS`).toEqual([...SIGNAL_KEYS].sort())
    }
  })

  it('kilit nedenleri (Java LOCKED_REASONS), sorumlu kaynakları ve önemlerin metni var', () => {
    const locked = javaList(CODES_SRC, 'LOCKED_REASONS')
    for (const [lang, dict] of DICTS) {
      for (const r of locked) expectText(dict, lang, `or.lock.${r}`)
      for (const s of RESPONSIBLE_SOURCES) expectText(dict, lang, `or.resp.source.${s}`)
      for (const s of SEVERITIES) expectText(dict, lang, `or.sev.${s}`)
    }
  })
})

describe('nöbet rutinleri — sorumlu / devir biçimi = OnCallRoutineService.responsibleWire', () => {
  /** `responsibleWire` gövdesindeki `<değişken>.put("anahtar"` anahtarları (m = sorumlu, a = devir). */
  function wireKeys(variable) {
    const start = SERVICE_SRC.indexOf('public Map<String, Object> responsibleWire(')
    expect(start, 'responsibleWire Java kaynağında yok').toBeGreaterThan(0)
    const end = SERVICE_SRC.indexOf('return m;', start)
    const body = SERVICE_SRC.slice(start, end)
    return [...body.matchAll(new RegExp(String.raw`\b${variable}\.put\("([a-z_]+)"`, 'g'))].map((x) => x[1])
  }

  it('test fikstürü sorumlu anahtarları sunucununkilerle aynı (uydurma anahtar yok)', () => {
    const server = wireKeys('m')
    expect(server).toContain('assignment')
    expect(Object.keys(buildResponsible()).sort()).toEqual([...server].sort())
  })

  it('devir (assignment) anahtarları sunucununkilerle aynı — atayanın adı `created_by_name`', () => {
    const server = wireKeys('a')
    expect(server).toContain('created_by_name')
    expect(Object.keys(buildAssignment()).sort()).toEqual([...server].sort())
  })

  it('ResponsibleCard yalnız sunucunun gönderdiği devir alanlarını okur', () => {
    const card = readFrontend('components/oncallroutine/ResponsibleCard.jsx')
    // `ignored_assignment.` ayrı nesne (aşağıda); `(?<![\w])` onu bu kümeye karıştırmaz
    const used = [...card.matchAll(/(?<![\w])assignment\??\.([a-z_]+)/g)].map((x) => x[1])
    const server = new Set(wireKeys('a'))
    expect(used.length).toBeGreaterThan(0)
    for (const k of used) expect(server.has(k), `ResponsibleCard assignment.${k} okuyor, sunucu göndermiyor`).toBe(true)
  })

  it('ResponsibleCard yok sayılan devirde yalnız sunucunun gönderdiği alanları okur (`ignored_assignment` = `ig.put`)', () => {
    const card = readFrontend('components/oncallroutine/ResponsibleCard.jsx')
    const used = [...card.matchAll(/ignored_assignment\??\.([a-z_]+)/g)].map((x) => x[1])
    const server = new Set(wireKeys('ig'))
    expect(server).toEqual(new Set(['scope', 'key', 'name', 'reason']))
    expect(used.length).toBeGreaterThan(0)
    for (const k of used) expect(server.has(k), `ResponsibleCard ignored_assignment.${k} okuyor, sunucu göndermiyor`).toBe(true)
  })
})
