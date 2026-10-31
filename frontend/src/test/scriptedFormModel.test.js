import { describe, it, expect } from 'vitest'
import {
  scanEnvUsage, looksSecretName, envLint, mergeEnv, testEnvPayload, usesSavedSecret, keptSecretsIgnored,
  templateEnvDescs, emptyEnvNames, targetHostOf, stopPoint,
  DEFAULT_SCRIPTED_SCHEDULE, scheduleFromScriptedRow, scheduleEditorValue, schedulePayload, scheduleErrorsOf,
  hasTimingError, scheduleProblems, MAX_ENV_SCAN_CHARS,
  savedSecretNames, hasSavedSecret, scheduleBodyKeys, testResultMayTakeFocus, draftForm, DRAFT_EXCLUDED_KEYS,
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


})

describe('stopPoint — kırpılmış doğrulama listesi', () => {
  it('sunucunun adsız truncated öğesi son adım sayılmaz', () => {
    const checks_json = JSON.stringify([{ name: 'Giriş › durum 200', passed: true }, { truncated: true, omitted: 40 }])
    expect(stopPoint({ status: 'ERROR', checks_json })).toEqual({ after: 'Giriş › durum 200', at: null })
    expect(stopPoint({ status: 'TIMEOUT', checks_json: JSON.stringify([{ truncated: true, omitted: 3 }]) })).toBeNull()
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

describe('inceleme düzeltmeleri (2026-10-31) — saf kurallar', () => {
  const saved = savedSecretNames([
    { name: 'PASSWORD', secret: true, value_set: true },
    { name: 'TOKEN', secret: true, value_set: false },
    { name: 'BASE_URL', secret: false, value: 'x', value_set: true },
    { name: ' SPACED ', secret: true, value_set: true },
  ])

  it('savedSecretNames: yalnız gizli + değeri kayıtlı adlar (kırpılmış); boş girdi = boş küme', () => {
    expect([...saved].sort()).toEqual(['PASSWORD', 'SPACED'])
    expect(savedSecretNames(null).size).toBe(0)
  })

  it('3: karar ADA göre — adı değişen satırın value_set bayrağı yok sayılır, eski ada dönünce geçerli', () => {
    const renamed = { name: 'PASSWORD_NEW', secret: true, value_set: true, value: '' }
    expect(hasSavedSecret(renamed, saved)).toBe(false)
    expect(usesSavedSecret(renamed, true, saved)).toBe(false)
    expect(usesSavedSecret({ ...renamed, name: 'PASSWORD' }, true, saved)).toBe(true)
    // Gizli değil / değer yazılmış → kayıtlı değer kullanılmaz
    expect(usesSavedSecret({ name: 'PASSWORD', secret: false, value: '' }, true, saved)).toBe(false)
    expect(usesSavedSecret({ name: 'PASSWORD', secret: true, value: 'yeni' }, true, saved)).toBe(false)
    // Küme verilmezse (eski çağıran) satır bayrağı
    expect(hasSavedSecret(renamed)).toBe(true)
  })

  it('3: test gövdesi ve boş değer uyarısı aynı kurala uyar', () => {
    const env = [{ name: 'PASSWORD_NEW', secret: true, value_set: true, value: '' }, { name: 'PASSWORD', secret: true, value: '' }]
    const { env: body, kept } = testEnvPayload(env, { editMode: true, savedNames: saved })
    expect(body).toEqual([{ name: 'PASSWORD_NEW', value: '', secret: true }, { name: 'PASSWORD', value: '', secret: true, keep: true }])
    expect(kept).toEqual(['PASSWORD'])
    expect(emptyEnvNames(env, saved)).toEqual(['PASSWORD_NEW'])
  })

  it('1: zamanlama anahtarları — yeni kayıt / alan taşıyan satır / değişiklik varsa; eski pod satırı dokunulmadıysa boş', () => {
    const plain = { ...DEFAULT_SCRIPTED_SCHEDULE }
    const cron = { ...DEFAULT_SCRIPTED_SCHEDULE, schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5' }
    expect(scheduleBodyKeys({ row: null, schedule: plain })).toEqual(schedulePayload(plain))
    expect(scheduleBodyKeys({ row: {}, schedule: plain })).toEqual(schedulePayload(plain))       // kopya / yeni
    const oldPodRow = { id: 7, interval_seconds: 300 }
    expect(scheduleBodyKeys({ row: oldPodRow, schedule: scheduleFromScriptedRow(oldPodRow) })).toEqual({})
    expect(scheduleBodyKeys({ row: oldPodRow, schedule: cron })).toEqual(schedulePayload(cron))
    const newPodRow = { id: 7, schedule_mode: null, cron_expression: null, windows: null }
    expect(scheduleBodyKeys({ row: newPodRow, schedule: scheduleFromScriptedRow(newPodRow) })).toEqual(schedulePayload(plain))
  })

  it('2: odak yalnız test çubuğunda / önceki sonuç panelinde / body iken alınabilir', () => {
    document.body.innerHTML = '<div data-slot="sc-test-bar"><button id="b">Test</button></div><div data-slot="test-run" tabindex="-1" id="p"></div><input id="i">'
    document.activeElement?.blur?.()
    expect(testResultMayTakeFocus(document)).toBe(true)
    document.getElementById('b').focus()
    expect(testResultMayTakeFocus(document)).toBe(true)
    document.getElementById('p').focus()
    expect(testResultMayTakeFocus(document)).toBe(true)
    document.getElementById('i').focus()
    expect(testResultMayTakeFocus(document)).toBe(false)
    expect(testResultMayTakeFocus(null)).toBe(true)
    document.body.innerHTML = ''
  })

  it('8: taslak betiğin ikinci kopyasını ve ad önerisi işaretini yazmaz; gizli değerler boş', () => {
    const f = { name: 'A', script: 'x'.repeat(10), scriptBase: 'x'.repeat(10), nameAuto: 'A', env: [{ name: 'P', secret: true, value: 'gizli' }, { name: 'U', value: 'u' }] }
    const d = draftForm(f)
    for (const k of DRAFT_EXCLUDED_KEYS) expect(d).not.toHaveProperty(k)
    expect(d.env).toEqual([{ name: 'P', secret: true, value: '' }, { name: 'U', value: 'u' }])
    expect(d.script).toBe(f.script)
    expect(f.scriptBase).toBe('x'.repeat(10))   // girdi değişmez
  })
})
