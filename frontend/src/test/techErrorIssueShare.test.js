import { describe, it, expect } from 'vitest'
import {
  ISSUE_IMAGES, ISSUE_LIMITS, ISSUE_OPTION_LABELS, NO_IMPACT_DISPLAY, DEFAULT_RATIO_OF, issueLeadText, issueOptions, issueShare,
} from '../components/techerror/techErrorModel.js'
import { javaStringTable, readJavaMain, readJavaTest } from './helpers/javaTable.js'

// Anomali konuları (2026-10-18): sunucudaki TechErrorIssueShare (pay) ve TechErrorIssueText (giriş metni) ile AYNI doğruluk
// tabloları — Java kaynağından okunur; ekran, PDF ve e-posta aynı payı ve aynı metni göstermeli.
const SHARE_JAVA = readJavaTest('service/techerror/TechErrorIssueShareTest.java')
const LEAD_JAVA = readJavaTest('service/techerror/TechErrorIssueTextTest.java')

/** TechErrorIssueShareTest.template ile elle aynı. */
function shareTemplate(ratioOf) {
  return {
    lang: 'tr',
    title: '{kanal}',
    sections: [
      { id: 'm', kind: 'metrics', title: 'Veriler', fields: [
        { id: 'q', label: 'Kesinti oldu mu?', type: 'yesno' },
        { id: 'total', label: 'Teknik hata adedi', type: 'number', skip_if_no: 'q' },
        { id: 'pct', label: 'Oran', type: 'percent' },
      ] },
      { id: 'iss', kind: 'issues', title: 'Anomaliler', ask_time: true, ...(ratioOf ? { ratio_of: ratioOf } : {}) },
    ],
  }
}

describe('anomali payı — Java ile ortak doğruluk tablosu', () => {
  it('SHARE_TABLE satırları Java ile aynı durumu, değeri ve eksiği verir', () => {
    const rows = javaStringTable(SHARE_JAVA, 'SHARE_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(12)
    for (const [name, ratioOf, q, total, errors, status, value, missing] of rows) {
      const tpl = shareTemplate(ratioOf === '-' ? null : ratioOf)
      const fields = {}
      if (q !== '-') fields.q = q === 'true'
      if (total !== '-') fields.total = Number(total)
      if (fields.q === false) delete fields.total                 // muaf alanın değeri saklanmaz
      const issue = { id: 'k', topic: 'Konu', errors: errors === '-' ? null : Number(errors) }
      const r = issueShare(tpl.sections[1], issue, tpl, fields)
      if (status === '-') { expect(r, name).toBeNull(); continue }
      expect(r.status, name).toBe(status)
      expect(r.value, name).toBe(value === '-' ? null : Number(value))
      expect(r.missing, name).toBe(missing === '-' ? null : missing)
    }
  })

  it('sabitler sunucuyla aynı: konu başına görsel, Kesinti ID / açıklama sınırı, gösterim seçenekleri, varsayılan pay alanı ve etiketler', () => {
    const tpl = readJavaMain('service/techerror/TechErrorTemplate.java')
    const vals = readJavaMain('service/techerror/TechErrorValues.java')
    expect(tpl).toContain(`MAX_ISSUE_IMAGES = ${ISSUE_IMAGES};`)
    expect(vals).toContain(`MAX_OUTAGE_ID = ${ISSUE_LIMITS.outageId};`)
    expect(vals).toContain(`MAX_CAPTION = ${ISSUE_LIMITS.caption};`)
    expect(tpl).toContain(`MAX_ISSUE_LABEL = ${ISSUE_LIMITS.label};`)
    expect(tpl).toContain(`NO_IMPACT_DISPLAY = List.of(${NO_IMPACT_DISPLAY.map((x) => `"${x}"`).join(', ')});`)
    expect(tpl).toContain(`DEFAULT_RATIO_OF = "${DEFAULT_RATIO_OF}";`)
    const javaLabel = (name) => new RegExp(`${name} = "([^"]*)";`).exec(tpl)?.[1]
    expect(ISSUE_OPTION_LABELS.tr.time_label).toBe(javaLabel('DEFAULT_TIME_LABEL_TR'))
    expect(ISSUE_OPTION_LABELS.en.time_label).toBe(javaLabel('DEFAULT_TIME_LABEL_EN'))
    expect(ISSUE_OPTION_LABELS.tr.outage_label).toBe(javaLabel('DEFAULT_OUTAGE_LABEL_TR'))
    expect(ISSUE_OPTION_LABELS.en.outage_label).toBe(javaLabel('DEFAULT_OUTAGE_LABEL_EN'))
    expect(ISSUE_OPTION_LABELS.tr.outage_id_label).toBe(javaLabel('DEFAULT_OUTAGE_ID_LABEL_TR'))
    expect(ISSUE_OPTION_LABELS.en.outage_id_label).toBe(javaLabel('DEFAULT_OUTAGE_ID_LABEL_EN'))
    expect(ISSUE_OPTION_LABELS.tr.impact_label).toBe(javaLabel('DEFAULT_IMPACT_LABEL_TR'))
    expect(ISSUE_OPTION_LABELS.en.impact_label).toBe(javaLabel('DEFAULT_IMPACT_LABEL_EN'))
  })
})

/** TechErrorIssueTextTest.section ile aynı: varsayılan anomali bölümü + satırın soruları. */
function leadSection(lang, opts) {
  const d = ISSUE_OPTION_LABELS[lang]
  const base = lang === 'en'
    ? { id: 'issues', kind: 'issues', title: 'Errors With an Abnormal Increase', item_title: '{topic}: Error Increase',
      description_label: 'What happened? (when, where, impact)', customers_label: 'Affected customers', errors_label: 'Technical errors caused by this topic' }
    : { id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
      description_label: 'Ne oldu? (zaman, yer, etki)', customers_label: 'Etkilenen müşteri sayısı', errors_label: 'Bu konudan kaynaklı teknik hata adedi' }
  const all = { ask_time: true, time_label: d.time_label, ask_outage: true, outage_label: d.outage_label, outage_id_label: d.outage_id_label,
    ask_impact: true, impact_label: d.impact_label, no_impact_display: 'hide_count', allow_images: true, ratio_of: 'error-count' }
  if (opts === 'all') return { ...base, ...all }
  const toks = opts.split(',').map((x) => x.trim())
  const label = toks.find((x) => x.startsWith('idlabel:'))
  return { ...base, ...all, ask_time: toks.includes('time'), ask_outage: toks.includes('outage'), ask_impact: toks.includes('impact'),
    outage_id_label: label ? label.slice('idlabel:'.length) : d.outage_id_label }
}

function leadIssue(spec) {
  const m = {}
  if (spec) for (const part of spec.split(';')) {
    const eq = part.indexOf('=')
    m[part.slice(0, eq)] = part.slice(eq + 1)
  }
  const num = (k) => (k in m ? Number(m[k]) : null)
  const bool = (k) => (k in m ? m[k] === 'true' : null)
  return { id: 'k', topic: 'Konu', title: '', title_auto: true, description: '', customers: num('customers'), errors: num('errors'),
    date: m.date ?? null, start: m.start ?? null, end: m.end ?? null, end_next_day: bool('next') === true, outage: bool('outage'),
    outage_id: m.id ?? null, impact: bool('impact') }
}

/** Konu tanımındaki kısaltma üssü (TechErrorIssueTextTest.unit): `eu` hata adedi, `cu` müşteri adedi; yoksa null. */
function leadUnit(spec, key) {
  if (!spec) return null
  for (const part of spec.split(';')) {
    const eq = part.indexOf('=')
    if (part.slice(0, eq) === key) return Number(part.slice(eq + 1))
  }
  return null
}

describe('anomali giriş metni — Java ile ortak doğruluk tablosu', () => {
  it('LEAD_TABLE satırları Java ile aynı metni verir (sorulmayan / cevapsız düşer, rakamlar ve ID kalın, şablon dili)', () => {
    const rows = javaStringTable(LEAD_JAVA, 'LEAD_TABLE')
    expect(rows.length).toBeGreaterThanOrEqual(15)
    for (const [name, lang, opts, spec, share, expected] of rows) {
      const s = leadSection(lang, opts)
      expect(issueOptions(s, lang).askTime, name).toBe(opts === 'all' || opts.includes('time'))
      expect(issueLeadText(s, leadIssue(spec), share === '-' ? null : Number(share), lang, (x) => x, leadUnit(spec, 'eu'),
        leadUnit(spec, 'cu')), name).toBe(expected)
    }
    // Kısaltmalı satırlar tabloda (2026-10-18: "1,25M" yazılan adet metinde de "1,25M")
    expect(rows.some(([, , , spec]) => leadUnit(spec, 'eu') != null)).toBe(true)
  })

  it('etiketin yer tutucusu çözülür (resolve); pay hesaplanmadıysa ya da adet yoksa pay cümlesi yok', () => {
    const s = { ...leadSection('tr', 'all'), outage_id_label: '{kanal} olay no' }
    expect(issueLeadText(s, leadIssue('outage=true;id=X-1'), null, 'tr', (x) => x.replace('{kanal}', 'Mobil')))
      .toBe('Kesinti kaynaklıdır (Mobil olay no: **X-1**).')
    expect(issueLeadText(s, leadIssue(''), 3.4, 'tr')).toBe('')
  })
})
