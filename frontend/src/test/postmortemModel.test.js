import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  ACTION_STATUSES, KINDS, PRIORITIES, SECTIONS, STATUSES, actionCounts, actionForm, actionPayload, buildMarkdown, changedFields,
  daysLate, dueState, formFromPostmortem, formatDay, isDirty, markdownFileName, sectionErrors, timelineText, MAX_SECTION_CHARS,
} from '../components/incidenthistory/postmortem/postmortemModel.js'

/**
 * Postmortem modeli (2026-10-11): sözlük sıraları sunucuyla aynı (Java kaynağından okunur), son tarih sınıfları,
 * kaydetme gövdesi (yalnız değişenler; rapor yokken ön dolu bölümler), Markdown dışa aktarım (kaçış), dosya adı.
 */
const JAVA = readFileSync(resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/incident/IncidentPostmortemService.java'), 'utf8')
const javaList = (name) => {
  const m = JAVA.match(new RegExp(`List<String> ${name} = List\\.of\\(([^)]*)\\)`))
  return m[1].split(',').map((s) => s.trim().replace(/"/g, '')).filter(Boolean)
}
const t = (k, ...a) => `${k}${a.length ? '(' + a.join(',') + ')' : ''}`

describe('sunucu ile eşitlik', () => {
  it('bölüm, durum, aksiyon durumu, tür ve öncelik sıraları Java ile aynı', () => {
    expect(SECTIONS).toEqual(javaList('SECTIONS'))
    expect(STATUSES).toEqual(javaList('STATUSES'))
    expect(ACTION_STATUSES).toEqual(javaList('ACTION_STATUSES'))
    expect(KINDS).toEqual(javaList('KINDS'))
    expect(PRIORITIES).toEqual(javaList('PRIORITIES'))
    expect(JAVA).toContain(`MAX_SECTION_CHARS = ${MAX_SECTION_CHARS.toLocaleString('en-US').replace(/,/g, '_')}`)
  })
})

describe('son tarih', () => {
  const TODAY = '2026-10-11'
  it('gecikmiş / bugün / yakında (≤3 gün) / sonra / tarihsiz / kapalı', () => {
    const a = (due, status = 'OPEN') => ({ due_date: due, status })
    expect(dueState(a('2026-10-01'), TODAY)).toBe('overdue')
    expect(dueState(a('2026-10-11'), TODAY)).toBe('today')
    expect(dueState(a('2026-10-14'), TODAY)).toBe('soon')
    expect(dueState(a('2026-10-15'), TODAY)).toBe('later')
    expect(dueState(a(null), TODAY)).toBe('none')
    expect(dueState(a('2026-10-01', 'DONE'), TODAY)).toBe('closed')
    expect(dueState(a('2026-10-01', 'IN_PROGRESS'), TODAY)).toBe('overdue')
    expect(daysLate(a('2026-10-01'), TODAY)).toBe(10)
    expect(daysLate(a('2026-10-20'), TODAY)).toBe(0)
    expect(formatDay('2026-10-01')).toBe('01.10.2026')
    expect(formatDay('bozuk')).toBe('bozuk')
  })
  it('sayılar', () => {
    expect(actionCounts([{ status: 'OPEN', due_date: '2026-10-01' }, { status: 'IN_PROGRESS' }, { status: 'DONE' }, { status: 'CANCELLED' }], '2026-10-11'))
      .toEqual({ total: 4, open: 2, overdue: 1, done: 1 })
  })
})

describe('rapor formu', () => {
  it('rapor yokken ön dolu bölümler ve Taslak dışı durum gönderilir; varken yalnız değişenler', () => {
    const pm = { exists: false, status: 'DRAFT', summary: 'Ön dolu özet', impact: '', root_cause: 'Kök' }
    const f = formFromPostmortem(pm)
    expect(f.lessons).toBe('')
    expect(changedFields(f, f, false)).toEqual({ summary: 'Ön dolu özet', root_cause: 'Kök' })
    const edited = { ...f, lessons: 'Ders', status: 'IN_REVIEW' }
    expect(isDirty(edited, f)).toBe(true)
    expect(changedFields(edited, f, true)).toEqual({ lessons: 'Ders', status: 'IN_REVIEW' })
    expect(isDirty(f, { ...f })).toBe(false)
    expect(sectionErrors({ ...f, impact: 'x'.repeat(MAX_SECTION_CHARS + 1) })).toEqual({ impact: true })
  })
  it('aksiyon formu ↔ gövde: boş sahip null, açıklama kırpılır', () => {
    const f = actionForm({ title: 'İş', owner_user_id: 'b6c1-uuid', due_date: '2026-10-20', kind: 'DETECT', priority: 'HIGH', status: 'OPEN' })
    expect(f.owner_user_id).toBe('b6c1-uuid')
    expect(actionPayload({ ...f, title: '  İş  ', description: '  ', owner_user_id: '', due_date: '' }))
      .toEqual({ title: 'İş', description: null, kind: 'DETECT', priority: 'HIGH', status: 'OPEN', owner_user_id: null, due_date: null })
  })
})

describe('dışa aktarım', () => {
  it('Markdown: künye, tüm bölümler (boş "—"), aksiyon tablosu kaçışlı, zaman çizelgesi', () => {
    const md = buildMarkdown({
      incident: { id: 7, title: 'Ödeme | kesintisi', severity: 'HIGH', occurred_at: '2026-10-10T08:00:00' },
      postmortem: { status: 'PUBLISHED', summary: 'Özet metni', published_at: '2026-10-11T08:00:00' },
      actions: [{ title: 'Alarm | eşiği', owner_name: 'Ayşe\nYılmaz', due_date: '2026-10-20', priority: 'HIGH', status: 'OPEN' }],
      timeline: [{ at: '2026-10-10T08:00:00', kind: 'occurred' }],
      t, fmt: (s) => `F(${s})`,
    })
    expect(md).toContain('# pm.mdTitle: Ödeme | kesintisi')
    expect(md).toContain('- pm.status: pm.status.PUBLISHED')
    expect(md).toContain('## pm.sec.summary\n\nÖzet metni')
    expect(md).toContain('## pm.sec.lessons\n\n—')
    expect(md).toContain('| Alarm \\| eşiği | Ayşe Yılmaz | 20.10.2026 | pm.prio.HIGH | pm.astatus.OPEN |')
    expect(md).toContain('- F(2026-10-10T08:00:00) — pm.tl.occurred')
    expect(buildMarkdown({ incident: { id: 1 }, postmortem: {}, actions: [], timeline: [], t })).toContain('pm.noActions')
  })
  it('zaman çizelgesi metinleri', () => {
    expect(timelineText({ kind: 'alert_opened', domain: 'a.example.com', alert_level: 'CRITICAL' }, t)).toBe('pm.tl.alert_opened(a.example.com,CRITICAL)')
    expect(timelineText({ kind: 'alert_resolved' }, t)).toBe('pm.tl.alert_resolved')
    expect(timelineText({ kind: 'alert_resolved', actor: 'b' }, t)).toBe('pm.tl.alert_resolvedBy(b)')
    expect(timelineText({ kind: 'alert_notified', count: 3 }, t)).toBe('pm.tl.alert_notified(3)')
    expect(timelineText({ kind: 'bilinmeyen' }, t)).toBe('bilinmeyen')
  })
  it('dosya adı Türkçe karaktersiz, güvenli ve kısa', () => {
    expect(markdownFileName({ id: 7, title: 'Ödeme Ağ Geçidi — Şubat Çöküşü!' })).toBe('postmortem-7-odeme-ag-gecidi-subat-cokusu.md')
    expect(markdownFileName({ id: 8, title: '' })).toBe('postmortem-8.md')
    expect(markdownFileName({ id: 9, title: 'x'.repeat(5000) }).length).toBeLessThanOrEqual(80)
  })
})
