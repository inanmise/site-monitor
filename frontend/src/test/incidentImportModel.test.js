import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  INCIDENT_GROUPS, DESCRIPTION_PARTS, DEFAULT_PARTS, NOTE_CODES, IMAGE_SKIP_REASONS, LINK_STATES, MAX_IMPORT,
  noteKey, imageSkipKey, groupLabelKey, partLabelKey, parseList, orderGroups, positiveId, linkKey,
  normalizeLink, linksIndex, hasStaleLink, reportFrozen, linkStateMeta, canRefreshLink, canOpenLinkedIncident,
  candidateRow, candidatesFrom, importCapacity, selectionBlock, toggleId, orderParts, CANDIDATE_SCAN_MAX,
  isNetworkish, readFailureKind, importFailure, proposalsFrom, proposalRows, proposalImages, FAILURE_KEYS, failureMessage,
  failureRetryable, canWriteFromIncident,
  parseUtcText, istanbulYearMonthOf, incidentYearMonth, monthValue, parseMonthValue, compareMonths, closedMonths,
  incidentDefaultMonth, incidentTimeLabel, canReportOn, reportableChannels, reportOfMonth, issueSections,
  reportParams, incidentParams, usageRows,
} from '../components/techerror/incidents/incidentImportModel.js'

/**
 * Olay kaydından anomali konusu — saf model (2026-11-20). Sözleşme `olay-ice-alma-api.md`: grup / parça / not / durum adları
 * KALICI; ekran metni hep `t()` ile, bilinmeyen kod genel metne düşer (ham kod gösterilmez).
 */
describe('incidentImportModel — sözleşme listeleri', () => {
  it('kalıcı adlar sözleşmedeki sırada', () => {
    expect(INCIDENT_GROUPS).toEqual(['topic', 'description', 'date', 'time', 'errors', 'outage', 'impact'])
    expect(DESCRIPTION_PARTS).toEqual(['rca', 'impact', 'resolution', 'technical'])
    expect(DEFAULT_PARTS).toEqual(['rca', 'impact', 'resolution'])
    expect(NOTE_CODES).toEqual(['topic_clipped', 'description_clipped', 'started_before_month', 'date_outside_month',
      'time_unknown', 'outage_id_missing', 'will_be_hidden', 'value_refused', 'image_skipped'])
    expect(IMAGE_SKIP_REASONS).toEqual(['format', 'too_large', 'total', 'unreadable'])
    expect(LINK_STATES).toEqual(['ok', 'stale', 'frozen_stale', 'deleted', 'hidden'])
    expect(MAX_IMPORT).toBe(10)
  })

  it('her kodun TR + EN metni var; bilinmeyen kod genel metne düşer', () => {
    const keys = [
      ...NOTE_CODES.map(noteKey), noteKey('brand_new_code'), noteKey(undefined),
      ...IMAGE_SKIP_REASONS.map(imageSkipKey), imageSkipKey('weird'),
      ...INCIDENT_GROUPS.map(groupLabelKey), ...DESCRIPTION_PARTS.map(partLabelKey),
      ...[...LINK_STATES, 'mystery'].map((s) => linkStateMeta(s).key), linkStateMeta('stale', { syncing: true }).key,
      'te.hist.kind.INCIDENT_IMPORT', 'te.hist.kind.INCIDENT_SYNC', 'te.hist.kind.INCIDENT_UNLINK',
    ]
    for (const k of keys) {
      expect(TR[k], `TR ${k}`).toBeTruthy()
      expect(EN[k], `EN ${k}`).toBeTruthy()
    }
    expect(noteKey('brand_new_code')).toBe('te.inc.note.other')
    expect(imageSkipKey('weird')).toBe('te.inc.imgSkip.other')
    // "errors" grubunun anahtarında "error" yok (hata iletisi kapısı)
    expect(groupLabelKey('errors')).toBe('te.inc.grp.count')
    expect(groupLabelKey('nope')).toBeNull()
    expect(partLabelKey('nope')).toBeNull()
  })

  it('sayılı metinlerin İngilizce tekili var', () => {
    for (const k of ['te.inc.selected', 'te.inc.import', 'te.inc.found', 'te.inc.done', 'te.inc.fact.count',
      'te.inc.fact.customers', 'te.inc.pv.images', 'te.inc.pinnedCount', 'te.inc.capacityReached']) {
      expect(EN[`${k}.one`], k).toBeTruthy()
      expect(TR[`${k}.one`], k).toBeTruthy()
    }
  })
})

describe('incidentImportModel — yardımcılar', () => {
  it('parseList / orderGroups / orderParts kırpar, tekrarsız ve kanonik sıraya dizer', () => {
    expect(parseList(' a, b ,a,, c')).toEqual(['a', 'b', 'c'])
    expect(parseList(['x', 1, ' y ', 'x'])).toEqual(['x', 'y'])
    expect(parseList(null)).toEqual([])
    expect(orderGroups(['impact', 'topic', 'bogus', 'date'])).toEqual(['topic', 'date', 'impact'])
    expect(orderGroups('outage,errors')).toEqual(['errors', 'outage'])
    expect(orderParts(['technical', 'rca', 'x'])).toEqual(['rca', 'technical'])
  })

  it('positiveId yalnız pozitif tam sayıyı kabul eder', () => {
    expect(positiveId(7)).toBe(7)
    expect(positiveId('12')).toBe(12)
    for (const v of [0, -1, 1.5, 'abc', '', null, undefined, true]) expect(positiveId(v)).toBeNull()
  })

  it('linkKey konu akordiyonu anahtarıyla aynı biçimde', () => {
    expect(linkKey('issues', 'k1')).toBe('issues:k1')
  })
})

describe('incidentImportModel — bağlar', () => {
  const raw = {
    section_id: 'issues', issue_id: 'i-1', incident_id: 42, state: 'stale', title: 'DB kesintisi', status: 'RESOLVED',
    occurred_at: '2026-09-12T11:00:00', pinned: ['impact', 'topic', 'zzz'], notes: ['time_unknown'],
    synced_at: '2026-09-20T08:00:00', incident_updated_at: '2026-09-21T08:00:00',
  }

  it('normalizeLink: iğneli gruplar kanonik sırada, bilinmeyen durum "unknown"', () => {
    const l = normalizeLink(raw)
    expect(l).toMatchObject({ sectionId: 'issues', issueId: 'i-1', incidentId: 42, state: 'stale', title: 'DB kesintisi',
      pinned: ['topic', 'impact'], notes: ['time_unknown'] })
    expect(normalizeLink({ ...raw, state: 'weird' }).state).toBe('unknown')
    expect(normalizeLink({ ...raw, section_id: '' })).toBeNull()
    expect(normalizeLink(null)).toBeNull()
  })

  it('hidden bağda başlık / durum / tarih sunucu gönderse de atılır', () => {
    const l = normalizeLink({ ...raw, state: 'hidden' })
    expect(l.title).toBeNull()
    expect(l.status).toBeNull()
    expect(l.occurredAt).toBeNull()
  })

  it('linksIndex: liste var → ok; boş liste = bağ yok (kesin); hata → error; alan yok (eski pod) → absent', () => {
    const idx = linksIndex({ incident_links: [raw, { ...raw, issue_id: 'i-2', incident_id: 43, state: 'ok' }] })
    expect(idx.known).toBe('ok')
    expect(idx.list).toHaveLength(2)
    expect(idx.byTopic.get('issues:i-1').incidentId).toBe(42)
    expect(idx.bySection.get('issues').get('i-2').state).toBe('ok')
    expect([...idx.incidentIds]).toEqual([42, 43])
    expect(hasStaleLink(idx)).toBe(true)

    const empty = linksIndex({ incident_links: [] })
    expect(empty.known).toBe('ok')
    expect(empty.list).toEqual([])
    expect(hasStaleLink(empty)).toBe(false)

    expect(linksIndex({ incident_links: null, incident_links_error: true }).known).toBe('error')
    expect(linksIndex({ incident_links: 'garbage' }).known).toBe('error')
    expect(linksIndex({ id: 1 }).known).toBe('absent')
    expect(linksIndex(null).known).toBe('absent')
  })

  it('reportFrozen: gönderilmiş / kuyrukta / onay bekleyen / onaylı rapor', () => {
    expect(reportFrozen({ status: 'sent' })).toBe(true)
    expect(reportFrozen({ status: 'queued' })).toBe(true)
    expect(reportFrozen({ status: 'sending' })).toBe(true)
    expect(reportFrozen({ status: 'draft', approval: { state: 'pending' } })).toBe(true)
    expect(reportFrozen({ status: 'changed', approval: { state: 'approved' } })).toBe(true)
    expect(reportFrozen({ status: 'changed', approval: { state: 'stale' } })).toBe(false)
    expect(reportFrozen({ status: 'draft' })).toBe(false)
    expect(reportFrozen(null)).toBe(false)
  })

  it('linkStateMeta / canRefreshLink / canOpenLinkedIncident', () => {
    expect(linkStateMeta('ok').tone).toBe('ok')
    expect(linkStateMeta('stale')).toEqual({ key: 'te.inc.state.stale', tone: 'warn' })
    expect(linkStateMeta('stale', { syncing: true })).toEqual({ key: 'te.inc.state.staleSyncing', tone: 'info' })
    expect(linkStateMeta('deleted').tone).toBe('danger')
    expect(linkStateMeta('unknown').tone).toBe('muted')
    expect(canRefreshLink('stale')).toBe(true)
    expect(canRefreshLink('frozen_stale')).toBe(true)
    for (const s of ['ok', 'deleted', 'hidden', 'unknown']) expect(canRefreshLink(s)).toBe(false)
    expect(canOpenLinkedIncident('ok', 5)).toBe(true)
    expect(canOpenLinkedIncident('deleted', 5)).toBe(false)
    expect(canOpenLinkedIncident('hidden', 5)).toBe(false)
    expect(canOpenLinkedIncident('ok', null)).toBe(false)
  })
})

describe('incidentImportModel — aday listesi ve seçim', () => {
  const res = {
    success: true,
    data: {
      items: [
        { id: 9, title: 'Ödeme API kesintisi', occurred_at: '2026-09-12T11:00:00', resolved_at: '2026-09-12T12:30:00',
          status: 'RESOLVED', severity: 'CRITICAL', channel: 'Mobil', tech_error_count: 1250, outage_related: true,
          affected_customers: 320, imported: false },
        { id: 10, title: 'Eski', imported: true, tech_error_count: null },
        { id: 'x' },
      ],
      total: 21, page: 0, size: 10, period: { year: 2026, month: 9 },
    },
  }

  it('candidatesFrom: kimliksiz satır atılır; sayısız alan null (0 değil)', () => {
    const c = candidatesFrom(res)
    expect(c.total).toBe(21)
    expect(c.period).toEqual({ year: 2026, month: 9 })
    expect(c.items.map((r) => r.id)).toEqual([9, 10])
    expect(c.items[0]).toMatchObject({ techErrorCount: 1250, outageRelated: true, affectedCustomers: 320, imported: false })
    expect(c.items[1]).toMatchObject({ techErrorCount: null, outageRelated: null, imported: true })
    expect(candidateRow({ id: 3, outage_related: 'yes' }).outageRelated).toBeNull()
  })

  it('candidatesFrom: sunucunun "yalnız ilk 2000 olayda arandı" bayrağı (yoksa false)', () => {
    expect(CANDIDATE_SCAN_MAX).toBe(2000)
    expect(candidatesFrom(res).truncated).toBe(false)
    expect(candidatesFrom({ success: true, data: { ...res.data, truncated: true } }).truncated).toBe(true)
    expect(candidatesFrom({ success: true, data: { ...res.data, truncated: 'true' } }).truncated).toBe(false)
  })

  it('bozuk yanıt "olay yok" sayılmaz (null)', () => {
    expect(candidatesFrom({ success: true, data: {} })).toBeNull()
    expect(candidatesFrom({ success: false, status: 500 })).toBeNull()
    expect(candidatesFrom(null)).toBeNull()
  })

  it('importCapacity: bölüm tavanı 30 ile istek tavanı 10 arasında küçük olan', () => {
    expect(importCapacity(0)).toBe(10)
    expect(importCapacity(25)).toBe(5)
    expect(importCapacity(30)).toBe(0)
    expect(importCapacity(40)).toBe(0)
    expect(importCapacity(undefined)).toBe(10)
  })

  it('selectionBlock: alınmış, üst sınır, yer yok; seçili satır hep bırakılabilir', () => {
    const row = { id: 1, imported: false }
    expect(selectionBlock({ id: 1, imported: true }, new Set())).toBe('imported')
    expect(selectionBlock(row, new Set())).toBeNull()
    const ten = new Set([2, 3, 4, 5, 6, 7, 8, 9, 10, 11])
    expect(selectionBlock(row, ten)).toBe('max')
    expect(selectionBlock(row, new Set([2, 3]), { capacity: 2 })).toBe('capacity')
    expect(selectionBlock({ id: 2 }, new Set([2, 3]), { capacity: 2 })).toBeNull()
    expect(selectionBlock(row, ten, { capacity: 10 })).toBe('max')
  })

  it('toggleId yeni Set döner', () => {
    const a = new Set([1])
    const b = toggleId(a, 2)
    expect(b).not.toBe(a)
    expect([...b]).toEqual([1, 2])
    expect([...toggleId(b, 1)]).toEqual([2])
  })
})

describe('incidentImportModel — hata sınıflandırma', () => {
  it('readFailureKind: kodlu 404 eski pod DEĞİL; yalnız 405 / kodsuz 404 "bu sürümde yok"', () => {
    expect(readFailureKind({ status: 405 })).toBe('unsupported')
    expect(readFailureKind({ status: 501 })).toBe('unsupported')
    expect(readFailureKind({ status: 404 })).toBe('unsupported')
    expect(readFailureKind({ status: 404, code: 'NOT_FOUND' })).toBe('missing')
    expect(readFailureKind({ status: 404, code: 'INCIDENT_NOT_FOUND' })).toBe('incident_gone')
    expect(readFailureKind({ status: 404, code: 'INCIDENT_LINK_NOT_FOUND' })).toBe('link_gone')
    expect(readFailureKind({ status: 404, errorInfo: { code: 'INCIDENT_NOT_FOUND' } })).toBe('incident_gone')
    expect(readFailureKind({ status: 403 })).toBe('forbidden')
    expect(readFailureKind({ status: 400, code: 'VALIDATION_FAILED', field: 'q' })).toBe('invalid')
    expect(readFailureKind({ status: 0, code: 'NETWORK_ERROR' })).toBe('network')
    expect(readFailureKind({ status: 504 })).toBe('network')
    expect(readFailureKind(null)).toBe('network')
    expect(readFailureKind({ status: 500, code: 'INTERNAL' })).toBe('error')
  })

  // Sunucunun gerçek yanıtları (TechErrorIncidentController / ImportService / SyncService): kod → tür → TR + EN cümle
  const CASES = [
    [{ status: 409, code: 'CONCURRENT_MODIFICATION' }, 'conflict'],
    [{ status: 409, code: 'INCIDENT_ALREADY_IMPORTED', incident_id: 9 }, 'already'],
    [{ status: 409, code: 'INCIDENT_ALREADY_IMPORTED' }, 'already'],
    [{ status: 400, code: 'SECTION_FULL', field: 'incident_ids' }, 'full'],
    [{ status: 400, code: 'INCIDENT_NOT_FOUND', field: 'incident_ids' }, 'incident_gone'],
    [{ status: 404, code: 'INCIDENT_NOT_FOUND' }, 'incident_gone'],
    [{ status: 400, code: 'INCIDENT_OTHER_TEAM', field: 'incident_ids' }, 'other_team'],
    [{ status: 400, code: 'REPORT_TOO_LARGE', field: 'incident_ids' }, 'too_large'],
    [{ status: 404, code: 'INCIDENT_LINK_NOT_FOUND' }, 'link_gone'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'section_id' }, 'section'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'incident_ids' }, 'selection'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'parts' }, 'parts'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'images' }, 'images_option'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'version' }, 'version'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'unpin' }, 'unpin'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'q' }, 'query'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'incident_id' }, 'incident_id'],
    [{ status: 429, code: 'IMPORT_BUSY', retry_after_seconds: 5 }, 'busy'],
    [{ status: 403, code: 'FORBIDDEN' }, 'forbidden'],
    [{ status: 405 }, 'unsupported'],
    [{ status: 404 }, 'unsupported'],
    [{ status: 404, code: 'NOT_FOUND' }, 'missing'],
    [{ status: 0, code: 'REQUEST_TIMEOUT' }, 'unsure'],
    [{ status: 502 }, 'unsure'],
    [null, 'unsure'],
    [{ status: 500, code: 'INTERNAL_ERROR', error: 'x' }, 'error'],
    [{ status: 400, code: 'VALIDATION_FAILED', field: 'bilinmeyen' }, 'error'],
  ]

  it('importFailure: sözleşmenin her kodu ve alanı', () => {
    for (const [res, kind] of CASES) expect(importFailure(res).kind, JSON.stringify(res)).toBe(kind)
    expect(importFailure({ status: 409, code: 'INCIDENT_ALREADY_IMPORTED', incident_id: 9 }).incidentId).toBe(9)
    expect(importFailure({ status: 409, code: 'INCIDENT_ALREADY_IMPORTED' }).incidentId).toBeNull()
    expect(isNetworkish({ status: 200, code: 'OFFLINE' })).toBe(true)
    expect(isNetworkish({ status: 400 })).toBe(false)
  })

  it('her kodun TR + EN cümlesi var ve ham kodu yazmaz (yalnız ağ / beklenmedik hata çağıranın metnine düşer)', () => {
    for (const [res, kind] of CASES) {
      const msg = failureMessage(importFailure(res))
      if (kind === 'unsure' || kind === 'error') { expect(msg, kind).toBeNull(); continue }
      expect(msg, kind).not.toBeNull()
      for (const dict of [TR, EN]) {
        const text = dict[msg.key]
        expect(typeof text === 'string' && text.length >= 25, `${kind} → ${msg.key}`).toBe(true)
        expect(text).not.toMatch(/[A-Z]{3,}_[A-Z_]+/)
      }
    }
    expect(failureMessage(importFailure({ status: 409, code: 'INCIDENT_ALREADY_IMPORTED', incident_id: 9 })))
      .toEqual({ key: 'te.inc.err.already', params: [9] })
    expect(failureMessage(importFailure({ status: 409, code: 'INCIDENT_ALREADY_IMPORTED' })))
      .toEqual({ key: 'te.inc.err.alreadyRace', params: [] })
    for (const key of Object.values(FAILURE_KEYS)) expect(TR[key] && EN[key], key).toBeTruthy()
    // 429 IMPORT_BUSY: sunucunun bekleme süresi cümleye girer (yoksa 5 sn)
    expect(failureMessage(importFailure({ status: 429, code: 'IMPORT_BUSY', retry_after_seconds: 7 }))).toEqual({ key: 'te.inc.err.busy', params: [7] })
    expect(failureMessage(importFailure({ status: 429, code: 'IMPORT_BUSY' }))).toEqual({ key: 'te.inc.err.busy', params: [5] })
  })

  it('failureRetryable: yalnız sonucu bilinmeyen / beklenmedik hata yeniden denenir', () => {
    expect(failureRetryable('unsure')).toBe(true)
    expect(failureRetryable('error')).toBe(true)
    expect(failureRetryable('busy')).toBe(true)
    for (const k of ['conflict', 'already', 'full', 'incident_gone', 'other_team', 'too_large', 'unsupported', 'missing', 'forbidden']) {
      expect(failureRetryable(k), k).toBe(false)
    }
  })

  it('canWriteFromIncident: olay okuma izni + silinmemiş / erişilebilir olay + yazılabilir görünüm', () => {
    expect(canWriteFromIncident('stale', { canViewIncidents: true })).toBe(true)
    expect(canWriteFromIncident('ok', { canViewIncidents: true })).toBe(true)
    expect(canWriteFromIncident('stale', { canViewIncidents: false })).toBe(false)
    expect(canWriteFromIncident('stale', { canViewIncidents: true, readOnly: true })).toBe(false)
    expect(canWriteFromIncident('deleted', { canViewIncidents: true })).toBe(false)
    expect(canWriteFromIncident('hidden', { canViewIncidents: true })).toBe(false)
  })
})

describe('incidentImportModel — önizleme', () => {
  const res = {
    success: true,
    data: {
      proposals: [
        {
          incident_id: 9, title: 'Ödeme API kesintisi',
          fields: { topic: 'Ödeme API kesintisi', date: '2026-09-12', start: '14:00', end: '15:30', end_next_day: false,
            errors: 1250, outage: true, outage_id: 'INC-1', impact: true, customers: 320 },
          description_preview: '**Kök neden**\nDB', notes: ['topic_clipped'], hidden: false,
          images: { copy: 1, skipped: [{ reason: 'format', image_id: 41 }], remaining: 2 },
        },
        { incident_id: 10, title: 'Boş', fields: { topic: 'Boş', date: null, start: null, end: '10:00', errors: null,
          outage: null, outage_id: null, impact: null, customers: null }, description_preview: '', notes: ['time_unknown'],
          hidden: true, images: { copy: 0, skipped: [] } },
        { title: 'kimliksiz' },
      ],
    },
  }

  it('proposalsFrom: kimliksiz öneri atılır, görsel nedenleri düz liste', () => {
    const ps = proposalsFrom(res)
    expect(ps.map((p) => p.incidentId)).toEqual([9, 10])
    expect(ps[0].images).toEqual({ copy: 1, skipped: ['format'], skippedIds: [41], allowed: true, remaining: 2 })
    expect(ps[1].images).toEqual({ copy: 0, skipped: [], skippedIds: [], allowed: true, remaining: 0 })
    expect(ps[1].hidden).toBe(true)
    expect(proposalsFrom({ success: true, data: {} })).toBeNull()
  })

  it('proposalImages: sunucunun görsel özeti (izin yok, kalan, kimliksiz atlama, bozuk değer)', () => {
    expect(proposalImages({ copy: 0, skipped: [], allowed: false })).toMatchObject({ copy: 0, allowed: false, remaining: 0 })
    expect(proposalImages({ copy: '2', skipped: ['total', { reason: 'unreadable' }, { reason: 7 }], remaining: -1 }))
      .toEqual({ copy: 2, skipped: ['total', 'unreadable'], skippedIds: [null, null], allowed: true, remaining: 0 })
    expect(proposalImages(null)).toEqual({ copy: 0, skipped: [], skippedIds: [], allowed: true, remaining: 0 })
  })

  it('proposalRows: dolacak ve boş kalacak gruplar', () => {
    const [full, empty] = proposalsFrom(res)
    const byGroup = (rows) => Object.fromEntries(rows.map((r) => [r.group, r]))
    const f = byGroup(proposalRows(full))
    expect(Object.values(f).every((r) => r.filled)).toBe(true)
    expect(f.time.value).toEqual({ start: '14:00', end: '15:30', nextDay: false })
    expect(f.outage.value).toEqual({ outage: true, id: 'INC-1' })
    expect(f.impact.value).toEqual({ impact: true, customers: 320 })
    const e = byGroup(proposalRows(empty))
    expect(e.topic.filled).toBe(true)
    for (const g of ['date', 'time', 'errors', 'outage', 'impact', 'description']) expect(e[g].filled, g).toBe(false)
  })
})

describe('incidentImportModel — zaman (İstanbul, UTC+3)', () => {
  it('bölgesiz metin UTC sayılır', () => {
    expect(parseUtcText('2026-09-30T21:30:00')).toBe(Date.UTC(2026, 8, 30, 21, 30))
    expect(parseUtcText('2026-09-30T21:30:00Z')).toBe(Date.UTC(2026, 8, 30, 21, 30))
    expect(parseUtcText('2026-09-30T21:30:00+03:00')).toBe(Date.UTC(2026, 8, 30, 18, 30))
    expect(parseUtcText('')).toBeNull()
    expect(parseUtcText('garbage')).toBeNull()
  })

  it('ay sınırı İstanbul saatiyle', () => {
    // 30 Eylül 21:30 UTC = 1 Ekim 00:30 İstanbul
    expect(incidentYearMonth('2026-09-30T21:30:00')).toEqual({ year: 2026, month: 10 })
    expect(incidentYearMonth('2026-09-30T20:59:00')).toEqual({ year: 2026, month: 9 })
    expect(istanbulYearMonthOf(Date.UTC(2026, 11, 31, 21, 0))).toEqual({ year: 2027, month: 1 })
  })

  it('monthValue / parseMonthValue / compareMonths', () => {
    expect(monthValue({ year: 2026, month: 3 })).toBe('2026-03')
    expect(monthValue(null)).toBe('')
    expect(parseMonthValue('2026-03')).toEqual({ year: 2026, month: 3 })
    expect(parseMonthValue('2026-13')).toBeNull()
    expect(parseMonthValue('x')).toBeNull()
    expect(compareMonths({ year: 2026, month: 1 }, { year: 2025, month: 12 })).toBe(1)
  })

  it('closedMonths içinde bulunulan ayı içermez, yeniden eskiye', () => {
    const now = Date.UTC(2026, 10, 20, 9, 0) // 20 Kasım 2026
    const list = closedMonths(now, 3)
    expect(list).toEqual([{ year: 2026, month: 10 }, { year: 2026, month: 9 }, { year: 2026, month: 8 }])
    expect(closedMonths(Date.UTC(2026, 0, 10), 1)).toEqual([{ year: 2025, month: 12 }])
  })

  it('incidentDefaultMonth: biten olayın başladığı ay, yalnız kapanmışsa', () => {
    const now = Date.UTC(2026, 10, 20, 9, 0)
    expect(incidentDefaultMonth({ occurred_at: '2026-09-12T11:00:00', resolved_at: '2026-09-12T12:00:00' }, now))
      .toEqual({ year: 2026, month: 9 })
    // Sürüyor → kullanıcı seçer
    expect(incidentDefaultMonth({ occurred_at: '2026-09-12T11:00:00', resolved_at: null }, now)).toBeNull()
    // İçinde bulunulan ay → kapanmamış
    expect(incidentDefaultMonth({ occurred_at: '2026-11-02T11:00:00', resolved_at: '2026-11-02T12:00:00' }, now)).toBeNull()
  })

  it('incidentTimeLabel İstanbul saatini yazar, tarayıcı bölgesinden bağımsız', () => {
    const tr = incidentTimeLabel('2026-09-12T11:00:00', 'tr')
    expect(tr).toMatch(/12\.09\.2026/)
    expect(tr).toMatch(/14:00/)
    expect(incidentTimeLabel('2026-09-12T11:00:00', 'en')).toMatch(/12\/09\/2026/)
    expect(incidentTimeLabel(null, 'tr')).toBeNull()
  })
})

describe('incidentImportModel — ters giriş (olay kaydından rapora)', () => {
  const channels = [
    { id: 3, name: 'web', team_id: 1, active: true, can_report: true },
    { id: 1, name: 'İnternet', team_id: 1, active: true, can_report: true },
    { id: 2, name: 'Pasif', team_id: 1, active: false, can_report: true },
    { id: 4, name: 'Başka takım', team_id: 2, active: true, can_report: true },
    { id: 5, name: 'Salt okunur', team_id: 1, active: true, can_report: false, can_edit: true },
    { id: 6, name: 'Eski sunucu', team_id: 1, active: true, can_edit: true },
  ]

  it('canReportOn: can_report öncelikli, yoksa can_edit; pasif kanal hayır', () => {
    expect(canReportOn(channels[0])).toBe(true)
    expect(canReportOn(channels[2])).toBe(false)
    expect(canReportOn(channels[4])).toBe(false)
    expect(canReportOn(channels[5])).toBe(true)
  })

  it('reportableChannels: olayın takımıyla sınırlı, Türkçe harmanlama', () => {
    expect(reportableChannels(channels, 1).map((c) => c.id)).toEqual([6, 1, 3])
    // Takımı olmayan olay hiçbir kanala alınamaz (sunucu INCIDENT_OTHER_TEAM der) — inceleme 2026-11-20
    expect(reportableChannels(channels, null)).toEqual([])
    expect(reportableChannels(channels, undefined)).toEqual([])
    expect(reportableChannels(channels, 0)).toEqual([])
    expect(reportableChannels(null, 1)).toEqual([])
  })

  it('reportOfMonth / issueSections / parametreler', () => {
    const reports = [{ id: 7, year: 2026, month: 9 }, { id: 8, year: 2026, month: 10 }]
    expect(reportOfMonth(reports, { year: 2026, month: 10 }).id).toBe(8)
    expect(reportOfMonth(reports, { year: 2026, month: 1 })).toBeNull()
    expect(reportOfMonth(reports, null)).toBeNull()
    expect(issueSections({ sections: [{ id: 'a', kind: 'metrics' }, { id: 'issues', kind: 'issues', title: 'Anomali' }] }))
      .toEqual([{ id: 'issues', title: 'Anomali' }])
    expect(reportParams(7, 3)).toEqual({ te_view: 'report', te_rep: '7', te_ch: '3' })
    expect(reportParams(7, null)).toEqual({ te_view: 'report', te_rep: '7' })
    expect(incidentParams(9)).toEqual({ incident: '9' })
  })

  it('usageRows: bozuk yanıt null (kullanılmıyor denmez); bilinmeyen durum unknown', () => {
    const rows = usageRows({ success: true, data: { items: [
      { report_id: 7, channel_id: 3, channel_name: 'Mobil', team_name: 'Dijital', year: 2026, month: 9, status: 'sent',
        topic: 'Ödeme', state: 'ok' },
      { report_id: 8, state: 'weird' },
      { channel_name: 'kimliksiz' },
    ] } })
    expect(rows.map((r) => r.reportId)).toEqual([7, 8])
    expect(rows[1].state).toBe('unknown')
    expect(usageRows({ success: true, data: { items: [] } })).toEqual([])
    expect(usageRows({ success: false, status: 500 })).toBeNull()
  })
})
