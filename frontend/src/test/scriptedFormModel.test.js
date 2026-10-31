import { describe, it, expect } from 'vitest'
import {
  scanEnvUsage, looksSecretName, envLint, mergeEnv, testEnvPayload, usesSavedSecret, keptSecretsIgnored,
  templateEnvDescs, emptyEnvNames, targetHostOf, stopPoint, stripContextLine, runContextChips,
  DEFAULT_SCRIPTED_SCHEDULE, scheduleFromScriptedRow, scheduleEditorValue, schedulePayload, scheduleErrorsOf,
  hasTimingError, scheduleProblems, MAX_ENV_SCAN_CHARS,
} from '../components/scripted/scriptedFormModel.js'
import { intervalProblem, windowProblems } from '../components/dbquery/scheduleModel.js'
import { optionHint, buildScriptSourceOptions } from '../utils/scriptSourceOptions.js'

describe('scanEnvUsage — betikte okunan __ENV adları', () => {
  it('nokta, isteğe bağlı zincir ve köşeli parantez erişimleri; ilk görülme sırası, tekil', () => {
    const s = "const a = __ENV.BASE_URL\nconst b = __ENV?.TOKEN\nconst c = __ENV['CLIENT_ID'] + __ENV[\"API_KEY\"]\nconst d = __ENV.BASE_URL"
    expect(scanEnvUsage(s)).toEqual({ names: ['BASE_URL', 'TOKEN', 'CLIENT_ID', 'API_KEY'], dynamic: false, partial: false })
  })

  it('yorum ve sıradan dizge atlanır; şablon dizgesinin ${…} ifadesi taranır, düz kısmı atlanır', () => {
    const s = [
      '// __ENV.LINE_COMMENT',
      '/* __ENV.BLOCK */',
      "const s1 = '__ENV.SINGLE'",
      'const s2 = "__ENV.DOUBLE"',
      'const u = `__ENV.IN_TEMPLATE ${__ENV.IN_EXPR}/x/${ { a: __ENV.NESTED_OBJ }.a }`',
      'const v = `a ${ `inner ${__ENV.DEEP}` } b`',
    ].join('\n')
    expect(scanEnvUsage(s).names).toEqual(['IN_EXPR', 'NESTED_OBJ', 'DEEP'])
  })

  it('başka bir nesnenin __ENV özelliği sayılmaz; değişkenle / yapısal okuma = dinamik', () => {
    expect(scanEnvUsage('obj.__ENV.X').names).toEqual([])
    expect(scanEnvUsage('const k = "A"; __ENV[k]').dynamic).toBe(true)
    expect(scanEnvUsage('const { A, B } = __ENV').dynamic).toBe(true)
    expect(scanEnvUsage('log(__ENV)').dynamic).toBe(true)
    expect(scanEnvUsage('__ENV.A').dynamic).toBe(false)
  })

  it('kapanmayan yorum / dizge sona kadar sürer — çöker ya da döngüye girmez', () => {
    expect(scanEnvUsage('/* hiç kapanmaz __ENV.X').names).toEqual([])
    expect(scanEnvUsage("'kapanmaz\n__ENV.Y").names).toEqual(['Y'])   // tırnak satır sonunda biter
    expect(scanEnvUsage('`kapanmaz ${__ENV.Z').names).toEqual(['Z'])
    expect(scanEnvUsage(null)).toEqual({ names: [], dynamic: false, partial: false })
  })

  it('DOĞRUSAL ve tavanlı: dev girdi (kapanmayan blok yorum, iç içe şablon) hızla biter', () => {
    const t0 = Date.now()
    const huge = '/*' + 'a'.repeat(MAX_ENV_SCAN_CHARS + 10_000)
    const r = scanEnvUsage(huge)
    expect(r.partial).toBe(true)
    const nest = '`${'.repeat(200) + '__ENV.X' + '}`'.repeat(200)
    expect(scanEnvUsage(nest).partial).toBe(true)   // derinlik tavanı → kısmi (kullanılmıyor çipi çizilmez)
    const many = '__ENV.A + '.repeat(40_000)
    expect(scanEnvUsage(many).names).toEqual(['A'])
    expect(Date.now() - t0).toBeLessThan(2000)
  })
})

describe('looksSecretName — gizli olması gereken adlar', () => {
  it.each([
    ['PASSWORD', true], ['DB_PASSWORD', true], ['CLIENT_SECRET', true], ['ACCESS_TOKEN', true], ['API_KEY', true],
    ['PRIVATE_KEY', true], ['APIKEY', true], ['PWD', true], ['client_secret', true],
    ['TOKEN_URL', false], ['PASSWORD_FIELD', false], ['CLIENT_ID', false], ['CACHE_KEY', false], ['BASE_URL', false],
    ['USERNAME', false], ['', false], [null, false],
  ])('%s → %s', (name, want) => { expect(looksSecretName(name)).toBe(want) })
})

describe('envLint — betik ↔ env satırları', () => {
  it('tanımsız, kullanılmıyor ve gizli işaretli değil', () => {
    const r = envLint('http.get(__ENV.BASE_URL + __ENV.MISSING)', [
      { name: 'BASE_URL', value: 'x' }, { name: 'UNUSED' }, { name: 'DB_PASSWORD', secret: false }, { name: 'TOKEN', secret: true }, { name: '  ' },
    ])
    expect(r.undefinedNames).toEqual(['MISSING'])
    expect(r.unusedNames).toEqual(['UNUSED', 'DB_PASSWORD', 'TOKEN'])
    expect(r.notSecret).toEqual(['DB_PASSWORD'])
  })

  it('dinamik okuma varsa "kullanılmıyor" denmez (bilinemez)', () => {
    const r = envLint('const k = "X"; http.get(__ENV[k])', [{ name: 'X' }])
    expect(r.unusedNames).toEqual([])
    expect(r.dynamic).toBe(true)
  })
})

describe('mergeEnv — kaynak değişiminde env birleştirme (H1)', () => {
  const cur = [
    { name: 'BASE_URL', secret: false, value: 'https://a' },
    { name: 'PASSWORD', secret: true, value: '', value_set: true },
    { name: 'MINE', secret: false, value: 'el' },
  ]

  it('aynı ad OLDUĞU GİBİ kalır, eksikler sona eklenir, fazlalar silinmez', () => {
    const r = mergeEnv(cur, [{ name: 'BASE_URL', secret: true }, { name: 'TOKEN', secret: true, value_set: true }, { name: 'X', secret: false, value: 'v' }])
    expect(r.env).toEqual([...cur, { name: 'TOKEN', secret: true, value: '', value_set: false }, { name: 'X', secret: false, value: '' }])
    expect(r.added).toEqual(['TOKEN', 'X'])
    expect(r.addedSecret).toEqual(['TOKEN'])
    expect(r.env[0]).toBe(cur[0])   // nesne değişmedi
  })

  it('withValues: gizli olmayanın değeri taşınır; keepSecret yalnız kendi kaydına dönüşte value_set korur', () => {
    const r = mergeEnv([], [{ name: 'X', value: 'v' }, { name: 'S', secret: true, value_set: true }], { withValues: true, keepSecret: () => true })
    expect(r.env).toEqual([{ name: 'X', secret: false, value: 'v' }, { name: 'S', secret: true, value: '', value_set: true }])
  })

  it('boş / adsız girdi zararsız', () => {
    expect(mergeEnv(null, null)).toEqual({ env: [], added: [], addedSecret: [] })
    expect(mergeEnv(cur, [{ name: '' }, null]).added).toEqual([])
  })
})

describe('testEnvPayload — kayıtlı gizli değerle test (B1)', () => {
  const env = [
    { name: ' BASE_URL ', value: 'https://a', secret: false },
    { name: 'PASSWORD', value: '', secret: true, value_set: true },
    { name: 'NEWPW', value: 'yeni', secret: true, value_set: true },
    { name: 'EMPTY_SECRET', value: '', secret: true, value_set: false },
    { name: '', value: 'adsız' },
  ]

  it('düzenlemede değeri boş + value_set gizli satır keep:true', () => {
    expect(testEnvPayload(env, { editMode: true })).toEqual({
      env: [
        { name: 'BASE_URL', value: 'https://a', secret: false },
        { name: 'PASSWORD', value: '', secret: true, keep: true },
        { name: 'NEWPW', value: 'yeni', secret: true },
        { name: 'EMPTY_SECRET', value: '', secret: true },
      ],
      kept: ['PASSWORD'],
    })
  })

  it('yeni izlemede / kopyada keep gönderilmez', () => {
    const r = testEnvPayload(env, { editMode: false })
    expect(r.kept).toEqual([])
    expect(r.env.some(e => 'keep' in e)).toBe(false)
  })

  it('usesSavedSecret ve keptSecretsIgnored', () => {
    expect(usesSavedSecret(env[1], true)).toBe(true)
    expect(usesSavedSecret(env[1], false)).toBe(false)
    expect(usesSavedSecret(env[2], true)).toBe(false)
    expect(keptSecretsIgnored(['PASSWORD'], { status: 'PASS' })).toBe(true)          // eski pod
    expect(keptSecretsIgnored(['PASSWORD'], { kept_secrets: ['PASSWORD'] })).toBe(false)
    expect(keptSecretsIgnored([], { status: 'PASS' })).toBe(false)
  })
})

describe('şablon değişken sihirbazı', () => {
  it('templateEnvDescs dile göre; açıklamasız ad haritada yok', () => {
    const tpl = { env: [{ name: 'A', desc: 'tr-a', desc_en: 'en-a' }, { name: 'B', desc: 'tr-b' }, { name: 'C' }] }
    expect([...templateEnvDescs(tpl, 'en')]).toEqual([['A', 'en-a'], ['B', 'tr-b']])
    expect([...templateEnvDescs(tpl, 'tr')]).toEqual([['A', 'tr-a'], ['B', 'tr-b']])
    expect(templateEnvDescs(null).size).toBe(0)
  })

  it('emptyEnvNames: kayıtlı gizli dolu sayılır', () => {
    expect(emptyEnvNames([{ name: 'A', value: '' }, { name: 'B', value: ' x ' }, { name: 'S', secret: true, value_set: true }, { name: 'T', secret: true }, { name: 'A', value: '' }]))
      .toEqual(['A', 'T'])
  })

  it('targetHostOf yalnız BASE_URL / *_URL ve http(s)', () => {
    expect(targetHostOf('BASE_URL', 'https://ibank.example.com/login')).toBe('ibank.example.com')
    expect(targetHostOf('api_url', 'http://h:8443/x')).toBe('h:8443')
    expect(targetHostOf('TOKEN', 'https://x')).toBeNull()
    expect(targetHostOf('BASE_URL', 'ftp://x')).toBeNull()
    expect(targetHostOf('BASE_URL', 'yarım')).toBeNull()
    expect(targetHostOf('BASE_URL', 'https://' + 'a'.repeat(3000))).toBeNull()
  })

  it('seçici ipucu: ilk cümle, en çok 140 karakter; açıklama yoksa ipucu yok', () => {
    expect(optionHint('Token alır. Sonra çağırır.')).toBe('Token alır.')
    expect(optionHint('x'.repeat(300)).length).toBe(140)
    expect(optionHint('')).toBeUndefined()
    const t = (k) => k
    const opts = buildScriptSourceOptions({ t, lang: 'en', savedScripts: [{ id: 1, name: 'm', description: 'Benim izlemem. Ayrıntı' }],
      templates: [{ id: 2, select_token: 'x', name: 'Ad', name_en: 'Name', description: 'TR açıklama', description_en: 'EN text.', scope: 'team' }] })
    expect(opts.find(o => o.value === 'saved:1').hint).toBe('Benim izlemem.')
    expect(opts.find(o => o.value === 'tpl:x').hint).toBe('EN text.')
  })
})

describe('koşum sonucu yardımcıları', () => {
  it('stopPoint: adımlardan son başarılı + durduğu adım', () => {
    const steps = [
      { name: 'A', checks_failed: 0, failed_requests: 0 },
      { name: 'B', checks_failed: 1, failed_requests: 0 },
      { name: 'C', checks_failed: 0, failed_requests: 0 },
    ]
    expect(stopPoint({ status: 'ERROR', steps })).toEqual({ after: 'A', at: 'B' })
    expect(stopPoint({ status: 'TIMEOUT', steps: [{ name: '', failed_requests: 1 }] })).toEqual({ after: null, at: '' })
    expect(stopPoint({ status: 'TIMEOUT', steps: [{ name: 'A' }, { name: 'B' }] })).toEqual({ after: 'B', at: null })
    expect(stopPoint({ status: 'FAIL', steps })).toBeNull()   // yalnız ERROR / TIMEOUT
  })

  it('stopPoint: adım yoksa checks_json; bozuk / boş → null', () => {
    const cj = JSON.stringify([{ name: 'a', passed: true }, { name: 'b', passed: true }, { name: 'c', passed: false }])
    expect(stopPoint({ status: 'ERROR', checks_json: cj })).toEqual({ after: 'b', at: 'c' })
    expect(stopPoint({ status: 'ERROR', checks_json: '{bozuk' })).toBeNull()
    expect(stopPoint({ status: 'ERROR' })).toBeNull()
  })

  it('stripContextLine yalnız run_context varken Türkçe bağlam satırını düşürür', () => {
    const err = 'k6 hatası\nsüreç bütçesi=60s · çıkış=vekil'
    expect(stripContextLine(err, { process_budget_s: 60 })).toBe('k6 hatası')
    expect(stripContextLine(err, null)).toBe(err)
    expect(stripContextLine(null, {})).toBeNull()
  })

  it('runContextChips', () => {
    expect(runContextChips({ process_budget_s: 60, request_timeout_s: null, egress: 'proxy', corporate_ca: false, inverted_budget: true }))
      .toEqual([
        { key: 'budget', tone: 'muted', args: [60] },
        { key: 'reqTimeoutDefault', tone: 'muted', args: [] },
        { key: 'egressProxy', tone: 'muted', args: [] },
        { key: 'caNo', tone: 'muted', args: [] },
        { key: 'inverted', tone: 'warning', args: [] },
      ])
    expect(runContextChips({ request_timeout_s: 20, egress: 'direct', corporate_ca: true })).toEqual([
      { key: 'reqTimeout', tone: 'muted', args: [20] }, { key: 'egressDirect', tone: 'muted', args: [] }, { key: 'caYes', tone: 'muted', args: [] }])
    expect(runContextChips(undefined)).toEqual([])
  })
})

describe('zamanlama alanları', () => {
  it('eski sunucu satırı (alan yok) → varsayılan INTERVAL; dolu satır aynen', () => {
    expect(scheduleFromScriptedRow({ interval_seconds: 300 })).toEqual(DEFAULT_SCRIPTED_SCHEDULE)
    const row = { schedule_mode: 'CRON', cron_expression: '0 9 * * *', windows: [{ days: [1], from: '09:00', to: '18:00' }], blackouts: [], skip_holidays: true, skip_dates: ['2026-12-31'] }
    expect(scheduleFromScriptedRow(row)).toEqual(row)
  })

  it('düzenleyici değeri aralığı formdan alır; gövde snake_case ve normalize', () => {
    expect(scheduleEditorValue({ intervalSeconds: 600, schedule: { schedule_mode: 'CRON', cron_expression: 'x' } }))
      .toMatchObject({ schedule_mode: 'CRON', cron_expression: 'x', interval_seconds: 600, windows: [] })
    expect(schedulePayload({ schedule_mode: 'BOZUK', windows: 'x' })).toEqual({
      schedule_mode: 'INTERVAL', cron_expression: '', windows: [], blackouts: [], skip_holidays: false, skip_dates: [] })
  })

  it('hata eşlemesi ve bölüm anahtarları', () => {
    expect(scheduleErrorsOf({ intervalSeconds: 'x', cron_expression: 'c', name: 'n' })).toEqual({ cron_expression: 'c', interval_seconds: 'x' })
    expect(hasTimingError({ name: 'n' })).toBe(false)
    expect(hasTimingError({ timeoutSeconds: 'x' })).toBe(true)
    expect(hasTimingError({ windows: 'x' })).toBe(true)
  })

  it('scheduleProblems: boş cron, aralık, eksik pencere', () => {
    const deps = { intervalProblem, windowProblems, minInterval: 60 }
    expect(scheduleProblems({ intervalSeconds: 300, schedule: DEFAULT_SCRIPTED_SCHEDULE }, deps)).toEqual({})
    expect(scheduleProblems({ intervalSeconds: 30, schedule: DEFAULT_SCRIPTED_SCHEDULE }, deps)).toEqual({ interval_seconds: 'interval_min' })
    expect(scheduleProblems({ intervalSeconds: null, schedule: { ...DEFAULT_SCRIPTED_SCHEDULE, schedule_mode: 'CRON', cron_expression: ' ' } }, deps))
      .toEqual({ cron_expression: 'cron_required' })
    expect(scheduleProblems({ intervalSeconds: 300, schedule: { ...DEFAULT_SCRIPTED_SCHEDULE, blackouts: [{ days: [], from: '01:00', to: '02:00' }] } }, deps))
      .toEqual({ blackouts: 'days_required' })
  })
})
