import { describe, it, expect } from 'vitest'
import {
  KW_EMPTY_FORM, KW_LEGACY_KEYS, affectsCheck, clampTimeoutMs, finalUrlError, hasRenderableField, keywordFormFrom,
  keywordPayload, keywordTestPayload, matchActiveCount, renderSubject, requestActiveCount, sectionsForErrors,
  serverFieldErrors, stableRules, statusModeOf, statusPatternError, storedSecretsOf, subjectError, subjectParts,
  unknownPlaceholders, userAgentError, validateKeywordForm,
} from '../components/keyword/keywordFormModel.js'

/**
 * Keyword formu SAF modeli (kw sözleşmesi §14.1, 2026-11-05): yeni kayıtta TAM yük (yeni alanlar yalnız varsayılandan farklıysa),
 * düzenlemede YALNIZ değişen alanlar (+ birlikte giden kümeler), sırlar write-only, `rules` normalize karşılaştırması, test
 * yükü + `useSaved`, sunucu kurallarının istemci aynası, posta konusu önizlemesi (sunucunun döngüsünün aynası).
 */

/** Sayfanın bildirim yönlendirmesi gövdesi (sayfadaki routingBody'nin aynısı). */
const routing = (f) => ({
  notificationGroupId: f.notificationGroupId === '' || f.notificationGroupId == null ? null : Number(f.notificationGroupId),
  nocNotify: !!f.nocNotify, nocGroupIds: f.nocGroupIds && f.nocGroupIds.length ? f.nocGroupIds : null,
})
const withRouting = (m) => ({ ...keywordFormFrom(m), notificationGroupId: m.notification_group_id != null ? String(m.notification_group_id) : '', nocNotify: !!m.noc_notify, nocGroupIds: m.noc_group_ids || [] })

const ROW = {
  id: 7, name: 'Portal', url: 'https://www.example.com/', keyword: 'Hoş geldiniz', operator: 'GTE', match_count: 1,
  group_name: 'Kurumsal', team_id: 5, tags: 'prod', case_sensitive: false, use_proxy: 'AUTO', notify_email: true,
  alert_level: 'WARNING', notify_webhook: true, interval_seconds: 300, timeout_ms: 10000, confirm_attempts: 3,
  confirm_interval_seconds: 30, recovery_checks: 3, recovery_interval_seconds: 30, active: true,
  notification_group_id: 9, noc_notify: true, noc_group_ids: [2],
  // kw fe1 alanları
  match_scope: 'RAW', normalize_ws: false, method: 'GET', request_body: null, request_content_type: null,
  basic_auth_user: 'izleme', has_basic_auth_pass: true, user_agent_mode: 'DEFAULT', user_agent: null,
  expected_status: null, expected_final_url: null, follow_redirects: true, mail_subject_template: null,
  has_custom_headers: true, custom_header_names: ['X-Api-Key'],
}

describe('keywordFormFrom — satır → form', () => {
  it('yeni alanlar satırdan; sırlar BOŞ; eski sunucunun satırı = bugünkü varsayılanlar', () => {
    const f = keywordFormFrom({ ...ROW, match_scope: 'VISIBLE', normalize_ws: true, method: 'POST', request_body: '{"q":1}',
      user_agent_mode: 'CUSTOM', user_agent: 'Bot/1', expected_status: '2xx,3xx', expected_final_url: '/hesabim',
      follow_redirects: false, mail_subject_template: '[{seviye}] {ad}' })
    expect(f).toMatchObject({ matchScope: 'VISIBLE', normalizeWs: true, method: 'POST', requestBody: '{"q":1}',
      basicAuthUser: 'izleme', basicAuthPass: '', clearBasicAuthPass: false, userAgentMode: 'CUSTOM', userAgent: 'Bot/1',
      expectedStatus: '2xx,3xx', expectedFinalUrl: '/hesabim', followRedirects: false, mailSubjectTemplate: '[{seviye}] {ad}',
      customHeaders: '', clearHeaders: false })
    const old = keywordFormFrom({ id: 1, url: 'https://a.example.com/', keyword: 'x' })
    for (const k of ['matchScope', 'normalizeWs', 'method', 'userAgentMode', 'followRedirects', 'expectedStatus', 'rules', 'ruleLogic', 'changeMask']) {
      expect(old[k], k).toEqual(KW_EMPTY_FORM[k])
    }
  })

  it('saklı > 60 sn zaman aşımı formda 60 sn (B13); tanınmayan kip / kapsam varsayılana düşer', () => {
    expect(keywordFormFrom({ timeout_ms: 120000 }).timeoutMs).toBe(60000)
    expect(clampTimeoutMs(500)).toBe(1000)
    expect(clampTimeoutMs('x')).toBe(10000)
    expect(keywordFormFrom({ user_agent_mode: 'WEIRD', match_scope: 'X', method: 'HEAD' })).toMatchObject({ userAgentMode: 'DEFAULT', matchScope: 'RAW', method: 'GET' })
  })

  it('fe2 alanları: rules yalnız rules_custom iken; maske CSV ya da dizi', () => {
    const rules = [{ id: 'r1', kind: 'TEXT', pattern: 'a' }]
    expect(keywordFormFrom({ rules, rules_custom: false }).rules).toBeNull()
    expect(keywordFormFrom({ rules, rules_custom: true }).rules).toBe(rules)
    expect(keywordFormFrom({ change_mask: 'DIGITS, DATES,DIGITS' }).changeMask).toEqual(['DIGITS', 'DATES'])
    expect(keywordFormFrom({ change_mask: ['DATES'] }).changeMask).toEqual(['DATES'])
  })

  it('storedSecretsOf: kayıtlı parola / başlık + adlar', () => {
    expect(storedSecretsOf(ROW)).toEqual({ hasPass: true, hasHeaders: true, headerNames: ['X-Api-Key'] })
    expect(storedSecretsOf(null)).toEqual({ hasPass: false, hasHeaders: false, headerNames: [] })
  })
})

describe('keywordPayload — yeni kayıt', () => {
  const NEW = { ...KW_EMPTY_FORM, notificationGroupId: '', nocNotify: false, nocGroupIds: [], url: 'x.example.com', keyword: 'ok', teamId: '5', groupName: 'G', tags: 'prod' }

  it('dokunulmayan yeni alanlar yüke GİRMEZ — bugünkü anahtarlar + yönlendirme', () => {
    const p = keywordPayload(NEW, null, { isNew: true, routingBody: routing })
    expect(Object.keys(p).sort()).toEqual([...KW_LEGACY_KEYS, 'notificationGroupId', 'nocNotify', 'nocGroupIds'].sort())
    expect(p.url).toBe('https://x.example.com')
    expect(p.teamId).toBe(5)
  })

  it('değiştirilen yeni alanlar (ve yazılmış parola) eklenir; başlık yalnız global admin', () => {
    const f = { ...NEW, method: 'POST', requestBody: '{"a":1}', basicAuthUser: ' u ', basicAuthPass: 'gizli', userAgentMode: 'CUSTOM',
      userAgent: 'Bot/1', expectedStatus: '2xx,3xx', expectedFinalUrl: ' /x ', followRedirects: false, matchScope: 'VISIBLE',
      mailSubjectTemplate: '{ad}', customHeaders: 'X-A: 1' }
    const p = keywordPayload(f, null, { isNew: true, routingBody: routing })
    expect(p).toMatchObject({ method: 'POST', requestBody: '{"a":1}', basicAuthUser: 'u', basicAuthPass: 'gizli', userAgentMode: 'CUSTOM',
      userAgent: 'Bot/1', expectedStatus: '2xx,3xx', expectedFinalUrl: '/x', followRedirects: false, matchScope: 'VISIBLE',
      mailSubjectTemplate: '{ad}' })
    expect(p).not.toHaveProperty('customHeaders')
    expect(p).not.toHaveProperty('clearBasicAuthPass')
    expect(keywordPayload(f, null, { isNew: true, globalAdmin: true }).customHeaders).toBe('X-A: 1')
  })

  it('özel kural listesi yeni kayıtta liste + mantıkla (tam yük — eski koşul alanları da durur)', () => {
    const rules = [{ id: 'r1', kind: 'REGEX', pattern: 'a+' }]
    const p = keywordPayload({ ...NEW, rules, ruleLogic: 'OR' }, null, { isNew: true })
    expect(p.rules).toBe(rules)
    expect(p.ruleLogic).toBe('OR')
    expect(p.keyword).toBe('ok')
  })
})

describe('keywordPayload — düzenleme (yalnız değişenler, B14)', () => {
  const base = withRouting(ROW)
  const edit = (patch, opts = {}) => keywordPayload({ ...base, ...patch }, base, { routingBody: routing, ...opts })

  it('değişiklik yoksa BOŞ yük (istek gitmez)', () => {
    expect(edit({})).toEqual({})
    // saklı 120 sn → form 60 sn kırpması tek başına değişiklik değil
    const b2 = withRouting({ ...ROW, timeout_ms: 120000 })
    expect(keywordPayload({ ...b2 }, b2, { routingBody: routing })).toEqual({})
  })

  it('tek alan → yalnız o alan; active / ad gibi alanlar geri yazılmaz', () => {
    expect(edit({ tags: 'prod,web' })).toEqual({ tags: 'prod,web' })
    expect(edit({ name: 'Yeni' })).toEqual({ name: 'Yeni' })
  })

  it('birlikte giden kümeler: takım + grup, 7/24, bildirim, koşul, yöntem + gövde, UA kipi + değeri, kapsam', () => {
    expect(edit({ teamId: '6' })).toEqual({ teamId: 6, groupName: 'Kurumsal' })
    expect(edit({ nocGroupIds: [2, 3] })).toEqual({ nocNotify: true, nocGroupIds: [2, 3] })
    expect(edit({ alertLevel: 'HIGH' })).toEqual({ notifyEmail: true, notifyWebhook: true, alertLevel: 'HIGH', notificationGroupId: 9 })
    expect(edit({ matchCount: 3 })).toEqual({ keyword: 'Hoş geldiniz', operator: 'GTE', matchCount: 3, caseSensitive: false })
    expect(edit({ method: 'POST' })).toEqual({ method: 'POST', requestBody: null, requestContentType: null })
    expect(edit({ userAgentMode: 'BROWSER' })).toEqual({ userAgentMode: 'BROWSER', userAgent: null })
    expect(edit({ normalizeWs: true })).toEqual({ matchScope: 'RAW', normalizeWs: true })
  })

  it('kapatılan durum kuralı / son adres / konu → null (sunucuda NULL = kapalı)', () => {
    const b = withRouting({ ...ROW, expected_status: '200', expected_final_url: '/a', mail_subject_template: 'X' })
    expect(keywordPayload({ ...b, expectedStatus: '', expectedFinalUrl: ' ', mailSubjectTemplate: '' }, b, { routingBody: routing }))
      .toEqual({ expectedStatus: null, expectedFinalUrl: null, mailSubjectTemplate: null })
  })

  it('sırlar: parola yalnız yazılınca; "kaldır" → clearBasicAuthPass; başlık kaldırma customHeaders:null (yalnız global admin)', () => {
    expect(edit({ basicAuthPass: 'yeni' })).toEqual({ basicAuthPass: 'yeni' })
    expect(edit({ clearBasicAuthPass: true, basicAuthPass: 'x' })).toEqual({ clearBasicAuthPass: true })
    expect(edit({ clearHeaders: true })).toEqual({})
    expect(edit({ clearHeaders: true }, { globalAdmin: true })).toEqual({ customHeaders: null })
    expect(edit({ customHeaders: '  X-B: 2 ' }, { globalAdmin: true })).toEqual({ customHeaders: 'X-B: 2' })
    expect(edit({ basicAuthUser: '' })).toEqual({ basicAuthUser: null })
  })

  it('kurallar: normalize karşılaştırma; özel liste değişince liste + mantık, eski koşul alanları GİTMEZ; özelden basite dönüş', () => {
    const r1 = [{ id: 'r1', kind: 'TEXT', pattern: 'a', operator: 'GTE', count: 1, label: null }]
    const b = { ...base, rules: r1, ruleLogic: 'AND' }
    // Aynı kural, farklı anahtar sırası + null alan yok → değişiklik değil
    expect(keywordPayload({ ...b, rules: [{ count: 1, operator: 'GTE', pattern: 'a', kind: 'TEXT', id: 'r1' }] }, b, { routingBody: routing })).toEqual({})
    const r2 = [...r1, { id: 'r2', kind: 'TEXT', pattern: 'b', operator: 'LT', count: 1 }]
    const p = keywordPayload({ ...b, rules: r2, keyword: 'degisti' }, b, { routingBody: routing })
    expect(p).toEqual({ rules: r2, ruleLogic: 'AND' })
    expect(keywordPayload({ ...b, ruleLogic: 'OR' }, b, { routingBody: routing })).toEqual({ rules: r1, ruleLogic: 'OR' })
    const back = keywordPayload({ ...b, rules: null }, b, { routingBody: routing })
    expect(back).toEqual({ rules: null, keyword: 'Hoş geldiniz', operator: 'GTE', matchCount: 1, caseSensitive: false })
    expect(stableRules(null)).toBeNull()
  })

  it('affectsCheck: kontrolü etkileyen yeni alan yükteyse true', () => {
    expect(affectsCheck({ name: 'x' })).toBe(false)
    expect(affectsCheck({ expectedStatus: '200' })).toBe(true)
    expect(affectsCheck({ basicAuthPass: 'p' })).toBe(true)
    expect(affectsCheck(null)).toBe(false)
  })
})

describe('keywordTestPayload', () => {
  const f = { ...keywordFormFrom(ROW) }

  it('dokunulmamış formun yükü bugünkü anahtarlarla; başlık global admin değilse null', () => {
    const p = keywordTestPayload({ ...KW_EMPTY_FORM, url: 'a.example.com', keyword: 'x' })
    expect(Object.keys(p).sort()).toEqual(['caseSensitive', 'customHeaders', 'keyword', 'matchCount', 'operator', 'timeoutMs', 'url', 'useProxy'].sort())
    expect(p.customHeaders).toBeNull()
  })

  it('kayıtlı izlemede yazılmamış sırlar useSaved ile; yazılan / kaldırılan sır istenmez', () => {
    const stored = storedSecretsOf(ROW)
    expect(keywordTestPayload(f, { monitorId: 7, stored, globalAdmin: true })).toMatchObject({
      monitorId: 7, useSaved: ['basicAuthPass', 'customHeaders'], basicAuthUser: 'izleme' })
    expect(keywordTestPayload({ ...f, basicAuthPass: 'p' }, { monitorId: 7, stored }).useSaved).toBeUndefined()
    expect(keywordTestPayload({ ...f, basicAuthPass: 'p' }, { monitorId: 7, stored }).basicAuthPass).toBe('p')
    expect(keywordTestPayload({ ...f, clearBasicAuthPass: true }, { monitorId: 7, stored }).useSaved).toBeUndefined()
    // Başlık yalnız global admin; kullanıcı adı silinmişse parola istenmez
    expect(keywordTestPayload(f, { monitorId: 7, stored }).useSaved).toEqual(['basicAuthPass'])
    expect(keywordTestPayload({ ...f, basicAuthUser: '' }, { monitorId: 7, stored }).useSaved).toBeUndefined()
    // Kopya / yeni kayıt (monitorId yok) → kayıtlı sır istenmez
    expect(keywordTestPayload(f, { stored })).not.toHaveProperty('monitorId')
  })

  it('yeni alanlar yalnız kullanılıyorsa; gövde yalnız POST', () => {
    const p = keywordTestPayload({ ...f, method: 'POST', requestBody: 'q=1', requestContentType: 'application/x-www-form-urlencoded',
      matchScope: 'VISIBLE', userAgentMode: 'BROWSER', expectedStatus: '200', expectedFinalUrl: '/x', followRedirects: false })
    expect(p).toMatchObject({ method: 'POST', requestBody: 'q=1', requestContentType: 'application/x-www-form-urlencoded',
      matchScope: 'VISIBLE', userAgentMode: 'BROWSER', expectedStatus: '200', expectedFinalUrl: '/x', followRedirects: false })
    expect(keywordTestPayload({ ...f, method: 'GET', requestBody: 'q=1' })).not.toHaveProperty('requestBody')
  })
})

describe('istemci doğrulaması (sunucu aynası)', () => {
  it('durum kodu dilbilgisi: sınıf / kod / aralık, 20 öğe, 100 karakter, boş öğe', () => {
    for (const ok of ['', '200', '2xx', '2XX,3xx', '200-399', '301 - 302', ' 200 , 404 ', '399-200']) expect(statusPatternError(ok), ok).toBeNull()
    expect(statusPatternError('6xx')).toMatchObject({ key: 'keyword.req.err.statusFormat', args: ['6xx'] })
    expect(statusPatternError('99')).toMatchObject({ key: 'keyword.req.err.statusFormat' })
    expect(statusPatternError('600')).toMatchObject({ key: 'keyword.req.err.statusFormat' })
    expect(statusPatternError('200,')).toMatchObject({ key: 'keyword.req.err.statusEmptyItem' })
    expect(statusPatternError(Array.from({ length: 21 }, () => '200').join(','))).toMatchObject({ key: 'keyword.req.err.statusTooMany' })
    expect(statusPatternError('2'.repeat(101))).toMatchObject({ key: 'keyword.req.err.statusTooLong' })
  })

  it('ön ayar kipi değerden (boşluk / harf duyarsız)', () => {
    expect(statusModeOf('')).toBe('OFF')
    expect(statusModeOf(' 2XX, 3xx ')).toBe('RECOMMENDED')
    expect(statusModeOf('200-399')).toBe('RECOMMENDED')
    expect(statusModeOf('200')).toBe('ONLY_200')
    expect(statusModeOf('200,301')).toBe('CUSTOM')
  })

  it('son adres: http(s) mutlak ya da / ile yol; boşluk / şemasız / ftp reddedilir', () => {
    for (const ok of ['', '/hesabim', 'https://www.example.com/a', 'http://x.example.com']) expect(finalUrlError(ok), ok).toBeNull()
    for (const bad of ['hesabim', 'ftp://x.example.com', 'https://a b.com', 'javascript:alert(1)']) expect(finalUrlError(bad), bad).toMatchObject({ key: 'keyword.req.err.finalUrl' })
    expect(finalUrlError(`https://x.example.com/${'a'.repeat(2001)}`)).toMatchObject({ key: 'keyword.req.err.finalUrlTooLong' })
  })

  it('özel User-Agent: zorunlu, ≤ 300, yazdırılabilir ASCII; diğer kiplerde denetim yok', () => {
    expect(userAgentError('DEFAULT', '')).toBeNull()
    expect(userAgentError('CUSTOM', ' ')).toMatchObject({ key: 'keyword.req.err.uaRequired' })
    expect(userAgentError('CUSTOM', 'Bot/1.0 (+https://x)')).toBeNull()
    expect(userAgentError('CUSTOM', 'İzleme')).toMatchObject({ key: 'keyword.req.err.uaInvalid' })
    expect(userAgentError('CUSTOM', 'a\nb')).toMatchObject({ key: 'keyword.req.err.uaInvalid' })
    expect(userAgentError('CUSTOM', 'a'.repeat(301))).toMatchObject({ key: 'keyword.req.err.uaTooLong' })
  })

  it('posta konusu: kontrol karakteri / 200 üstü hata; boş serbest', () => {
    expect(subjectError('')).toBeNull()
    expect(subjectError('a\tb')).toMatchObject({ key: 'keyword.subject.err.control' })
    expect(subjectError('a'.repeat(201))).toMatchObject({ key: 'keyword.subject.err.tooLong' })
  })

  it('validateKeywordForm: anahtarlar istek anahtarı; gövde 64 KB yalnız POST; başlık yalnız global admin; zaman aşımı 1–60 sn', () => {
    const f = { ...KW_EMPTY_FORM, method: 'POST', requestBody: 'x'.repeat(65 * 1024), requestContentType: 'bozuk', basicAuthUser: 'a:b',
      customHeaders: 'satır', userAgentMode: 'CUSTOM', userAgent: '', expectedStatus: '7xx', expectedFinalUrl: 'x', timeoutMs: 120000,
      mailSubjectTemplate: 'a\nb' }
    const errs = validateKeywordForm(f, { globalAdmin: true })
    expect(Object.keys(errs).sort()).toEqual(['basicAuthUser', 'customHeaders', 'expectedFinalUrl', 'expectedStatus', 'mailSubjectTemplate',
      'requestBody', 'requestContentType', 'timeoutMs', 'userAgent'].sort())
    expect(validateKeywordForm({ ...f, method: 'GET' })).not.toHaveProperty('requestBody')
    expect(validateKeywordForm(f)).not.toHaveProperty('customHeaders')
    expect(validateKeywordForm(KW_EMPTY_FORM)).toEqual({})
  })

  it('hatalı alanın bölümü açılır (kapalıyken içerik DOM\'da değil)', () => {
    expect([...sectionsForErrors({ expectedStatus: 'x', url: 'y' })]).toEqual(['request'])
    expect([...sectionsForErrors({ matchScope: 'x', timeoutMs: 'y', basicAuthPass: '' })].sort()).toEqual(['advanced', 'match'])
  })
})

describe('sunucu alan hataları → form', () => {
  it('field / fields eşlenir; doğrulama yanıtı değilse boş', () => {
    expect(serverFieldErrors({ success: false, code: 'VALIDATION_FAILED', field: 'expectedStatus', error: 'Geçersiz' })).toEqual({ expectedStatus: 'Geçersiz' })
    expect(serverFieldErrors({ success: false, fields: { userAgent: 'kötü', x: '' }, error: 'E' })).toEqual({ userAgent: 'kötü', x: 'E' })
    expect(serverFieldErrors({ success: true })).toEqual({})
    expect(serverFieldErrors({ success: false, error: 'Yetki yok' })).toEqual({})
  })

  it('alanın altına düşebilen anahtar var mı (fe2 kural anahtarları dahil)', () => {
    expect(hasRenderableField({ expectedStatus: 'x' })).toBe(true)
    expect(hasRenderableField({ 'rules[2].pattern': 'x' })).toBe(true)
    expect(hasRenderableField({ notificationGroupId: 'x' })).toBe(false)
  })
})

describe('bölüm rozetleri', () => {
  it('istek: POST, başlık (kayıtlı ya da yazılı, kaldırılmadıkça), auth, UA, durum, son adres, takip kapalı', () => {
    expect(requestActiveCount(KW_EMPTY_FORM)).toBe(0)
    expect(requestActiveCount(KW_EMPTY_FORM, { hasHeaders: true })).toBe(1)
    expect(requestActiveCount({ ...KW_EMPTY_FORM, clearHeaders: true }, { hasHeaders: true })).toBe(0)
    expect(requestActiveCount({ ...KW_EMPTY_FORM, method: 'POST', basicAuthUser: 'u', userAgentMode: 'BROWSER', expectedStatus: '200',
      expectedFinalUrl: '/a', followRedirects: false })).toBe(6)
  })
  it('eşleşme: görünür metin ya da normalleştirme', () => {
    expect(matchActiveCount(KW_EMPTY_FORM)).toBe(0)
    expect(matchActiveCount({ ...KW_EMPTY_FORM, normalizeWs: true })).toBe(1)
    expect(matchActiveCount({ ...KW_EMPTY_FORM, matchScope: 'VISIBLE', normalizeWs: true })).toBe(1)
  })
})

describe('posta konusu (sunucu döngüsünün aynası)', () => {
  const V = { name: 'Portal', url: 'https://x', keyword: 'Giriş', count: '0', reason: 'Kelime bulunamadı', level: 'UYARI', status: '503', time: '05.11.2026 09:00' }

  it('TR ve EN yer tutucular aynı değeri verir; ad kırpılıp küçük harfle eşlenir; bilinmeyen olduğu gibi kalır', () => {
    expect(renderSubject('[{seviye}] {ad} — {neden}', V)).toBe('[UYARI] Portal — Kelime bulunamadı')
    expect(renderSubject('[{level}] {name} — {reason}', V)).toBe('[UYARI] Portal — Kelime bulunamadı')
    expect(renderSubject('{ AD } {Kelime} {adet}/{durum} @{zaman} {url}', V)).toBe('Portal Giriş 0/503 @05.11.2026 09:00 https://x')
    expect(renderSubject('{foo} {ad}', V)).toBe('{foo} Portal')
    expect(unknownPlaceholders('{foo} {ad} {foo} {bar}')).toEqual(['{foo}', '{bar}'])
  })

  it('kapanmayan / uzak süslü parantez metin kalır; iç içe parantezde içteki yer tutucu çözülür', () => {
    expect(renderSubject('{ad', V)).toBe('{ad')
    expect(renderSubject('{bu çok uzun bir ad}', V)).toBe('{bu çok uzun bir ad}')
    expect(renderSubject('{a{ad}', V)).toBe('{aPortal')
    expect(subjectParts('a{ad}b').map((p) => p.text)).toEqual(['a', '{ad}', 'b'])
  })

  it('tek satır: kontrol / boşluk koşuları teke; 200 üstü 199 + "…"; boş sonuç ""', () => {
    expect(renderSubject('a\t\tb  c ', { })).toBe('a b c')
    expect(renderSubject('{ad}', { name: 'x'.repeat(300) })).toHaveLength(200)
    expect(renderSubject('{ad}', { name: 'x'.repeat(300) }).endsWith('…')).toBe(true)
    expect(renderSubject('{neden}', { reason: '' })).toBe('')
  })

  it('doğrusal: 4 × 200 karakterden sonrası işlenmez, süslü parantez dolu girdi hızlı', () => {
    const big = '{'.repeat(100000)
    const t0 = Date.now()
    expect(renderSubject(big, V).length).toBeLessThanOrEqual(200)
    expect(Date.now() - t0).toBeLessThan(500)
  })
})
