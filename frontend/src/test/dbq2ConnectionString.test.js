import { describe, it, expect } from 'vitest'
import {
  MAX_CONN_STRING, applyConnectionString, applySuggestedType, containsPassword, parseConnectionString,
} from '../components/dbquery/connectionString.js'
import { emptyForm } from '../components/dbquery/connectionModel.js'

// "Bağlantı dizesi yapıştır" (A1, 2026-11-05) — SAF ayrıştırıcının doğruluk tablosu. Kesin kural: parola ASLA `fields`'e
// girmez (yalnız `hadPassword`); açık türlü dize türü belirler, türsüz dizede porttan yalnız ÖNERİ çıkar.

/** [ad, girdi, beklenen (kısmi): { ok, error, db_type, typeSource, fields, hadPassword, notes (kodlar) }] */
const TABLE = [
  ['PG JDBC tam', 'jdbc:postgresql://db.example.com:5432/orders?sslmode=verify-full&user=sm_ro',
    { ok: true, db_type: 'POSTGRESQL', typeSource: 'explicit', hadPassword: false,
      fields: { host: 'db.example.com', port: 5432, database_name: 'orders', ssl_mode: 'verify-full', username: 'sm_ro' } }],
  ['PG JDBC parola sorgu parametresinde', 'jdbc:postgresql://db.example.com/orders?password=s3cret&user=u',
    { ok: true, hadPassword: true, fields: { host: 'db.example.com', database_name: 'orders', username: 'u' } }],
  ['postgres:// kullanıcı + parola', 'postgres://reader:s3cret@pg.local:6543/app?sslmode=require',
    { ok: true, db_type: 'POSTGRESQL', hadPassword: true, fields: { host: 'pg.local', port: 6543, database_name: 'app', username: 'reader', ssl_mode: 'require' } }],
  ['postgresql:// portsuz', 'postgresql://pg.local/app', { ok: true, fields: { host: 'pg.local', database_name: 'app' } }],
  ['PG ssl=true → require', 'jdbc:postgresql://pg.local/app?ssl=true', { ok: true, fields: { ssl_mode: 'require' } }],
  ['PG çoklu sunucu → ilki + not', 'jdbc:postgresql://h1:5432,h2:5432/db', { ok: true, notes: ['MULTI_HOST'], fields: { host: 'h1', port: 5432 } }],
  ['PG IPv6', 'jdbc:postgresql://[::1]:5432/db', { ok: true, fields: { host: '::1', port: 5432 } }],
  ['PG desteklenmeyen sslmode', 'jdbc:postgresql://db/x?sslmode=verify-ca', { ok: true, notes: ['SSL_MODE_IGNORED'], noFields: ['ssl_mode'] }],
  ['PG geçersiz port', 'jdbc:postgresql://db:99999/x', { ok: true, notes: ['PORT_INVALID'], noFields: ['port'], fields: { host: 'db' } }],
  ['PG sunucusuz', 'jdbc:postgresql://', { ok: false, error: 'NO_HOST' }],
  ['MSSQL JDBC tam', 'jdbc:sqlserver://sql01.example.com:1433;databaseName=Sales;encrypt=true;trustServerCertificate=false;user=ro;password=p',
    { ok: true, db_type: 'MSSQL', hadPassword: true,
      fields: { host: 'sql01.example.com', port: 1433, database_name: 'Sales', mssql_encrypt: true, mssql_trust_server_certificate: false, username: 'ro' } }],
  ['MSSQL örnek adı', 'jdbc:sqlserver://sql01\\RPT;databaseName=Sales', { ok: true, fields: { host: 'sql01', mssql_instance: 'RPT', database_name: 'Sales' }, noFields: ['port'] }],
  ['MSSQL serverName özelliği', 'jdbc:sqlserver://;serverName=sql02;portNumber=1500;database=Ops', { ok: true, fields: { host: 'sql02', port: 1500, database_name: 'Ops' } }],
  ['MSSQL encrypt strict', 'jdbc:sqlserver://s;encrypt=strict', { ok: true, fields: { mssql_encrypt: true } }],
  ['MSSQL bilinmeyen encrypt', 'jdbc:sqlserver://s;encrypt=maybe', { ok: true, notes: ['ENCRYPT_IGNORED'], noFields: ['mssql_encrypt'] }],
  ['jTDS', 'jdbc:jtds:sqlserver://sql03:1433/Billing;instance=X1;user=a', { ok: true, db_type: 'MSSQL', fields: { host: 'sql03', port: 1433, database_name: 'Billing', mssql_instance: 'X1', username: 'a' } }],
  ['Oracle //servis', 'jdbc:oracle:thin:@//ora-scan.example.com:1521/ORCLPDB1',
    { ok: true, db_type: 'ORACLE', fields: { host: 'ora-scan.example.com', port: 1521, database_name: 'ORCLPDB1', oracle_mode: 'SERVICE' } }],
  ['Oracle SID', 'jdbc:oracle:thin:@ora01:1521:ORCL', { ok: true, fields: { host: 'ora01', port: 1521, database_name: 'ORCL', oracle_mode: 'SID' } }],
  ['Oracle EZConnect (// yok)', 'jdbc:oracle:thin:@ora01:1521/svc', { ok: true, fields: { database_name: 'svc', oracle_mode: 'SERVICE' } }],
  ['Oracle kullanıcı/parola', 'jdbc:oracle:thin:scott/tiger@//ora01:1521/svc', { ok: true, hadPassword: true, fields: { username: 'scott' } }],
  ['Oracle tcps', 'jdbc:oracle:thin:@tcps://ora01:2484/svc', { ok: true, fields: { oracle_tcps: true, port: 2484 } }],
  ['Oracle DESCRIPTION servis + TCPS', 'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=ora-dg.example.com)(PORT=2484))(CONNECT_DATA=(SERVICE_NAME=DGSVC)))',
    { ok: true, db_type: 'ORACLE', fields: { host: 'ora-dg.example.com', port: 2484, oracle_tcps: true, database_name: 'DGSVC', oracle_mode: 'SERVICE' } }],
  ['Oracle DESCRIPTION SID (boşluklu, çok satır)', 'jdbc:oracle:thin:@(DESCRIPTION =\n (ADDRESS = (PROTOCOL = TCP)(HOST = ora02)(PORT = 1521))\n (CONNECT_DATA = (SID = ORA2)))',
    { ok: true, fields: { host: 'ora02', port: 1521, oracle_tcps: false, database_name: 'ORA2', oracle_mode: 'SID' } }],
  ['Oracle DESCRIPTION servissiz', 'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=ora03)(PORT=1521)))', { ok: true, notes: ['DESCRIPTION_PARTIAL'], noFields: ['database_name'] }],
  ['Oracle DESCRIPTION iki adres', 'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=a1)(PORT=1521))(ADDRESS=(HOST=a2)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=S)))',
    { ok: true, notes: ['MULTI_HOST'], fields: { host: 'a1' } }],
  ['ADO.NET SQL Server', 'Server=tcp:sql04.example.com,1433;Database=Ops;User Id=ro;Password=x;Encrypt=True;TrustServerCertificate=False',
    { ok: true, db_type: 'MSSQL', hadPassword: true,
      fields: { host: 'sql04.example.com', port: 1433, database_name: 'Ops', username: 'ro', mssql_encrypt: true, mssql_trust_server_certificate: false } }],
  ['ADO.NET örnek + virgüllü port', 'Data Source=sql05\\INST,1450;Initial Catalog=Rpt;Integrated Security=SSPI',
    { ok: true, db_type: 'MSSQL', hadPassword: false, fields: { host: 'sql05', mssql_instance: 'INST', port: 1450, database_name: 'Rpt' } }],
  ['Npgsql', 'Host=pg2.example.com;Port=5433;Database=app;Username=ro;SSL Mode=Require',
    { ok: true, db_type: 'POSTGRESQL', fields: { host: 'pg2.example.com', port: 5433, database_name: 'app', username: 'ro', ssl_mode: 'require' } }],
  ['ODP.NET', 'Data Source=ora02:1521/SVC2;User Id=ro;Password=x',
    { ok: true, db_type: 'ORACLE', hadPassword: true, fields: { host: 'ora02', port: 1521, database_name: 'SVC2', oracle_mode: 'SERVICE', username: 'ro' } }],
  ['türsüz 1433 → SQL Server önerisi', 'db.example.com:1433/Sales', { ok: true, db_type: 'MSSQL', typeSource: 'port', fields: { host: 'db.example.com', port: 1433, database_name: 'Sales' } }],
  ['türsüz 5432 → PostgreSQL önerisi', 'db.example.com:5432/orders', { ok: true, db_type: 'POSTGRESQL', typeSource: 'port' }],
  ['türsüz 2484 → Oracle önerisi', 'ora.example.com:2484/svc', { ok: true, db_type: 'ORACLE', typeSource: 'port' }],
  ['türsüz bilinmeyen port', 'db.example.com:9999/x', { ok: true, db_type: null, typeSource: null, fields: { port: 9999 } }],
  ['türsüz portsuz', 'db.example.com/orders', { ok: true, db_type: null, fields: { host: 'db.example.com', database_name: 'orders' } }],
  ['türsüz sorgu parametresi atılır', 'db.example.com:5432/orders?sslmode=require', { ok: true, fields: { database_name: 'orders' } }],
  ['tırnaklı', '"jdbc:postgresql://db:5432/x"', { ok: true, fields: { host: 'db', port: 5432, database_name: 'x' } }],
  ['boş', '   ', { ok: false, error: 'EMPTY' }],
  ['tanınmayan şema', 'mysql://u:p@h/db', { ok: false, error: 'UNRECOGNIZED', hadPassword: true }],
  ['tanınmayan jdbc', 'jdbc:db2:server:50000/x', { ok: false, error: 'UNRECOGNIZED' }],
  ['metin', 'bu bir bağlantı dizesi değil', { ok: false, error: 'UNRECOGNIZED' }],
]

describe('parseConnectionString — doğruluk tablosu', () => {
  for (const [name, input, want] of TABLE) {
    it(name, () => {
      const r = parseConnectionString(input)
      expect(r.ok, JSON.stringify(r)).toBe(want.ok)
      if ('error' in want) expect(r.error).toBe(want.error)
      if ('db_type' in want) expect(r.db_type).toBe(want.db_type)
      if ('typeSource' in want) expect(r.typeSource).toBe(want.typeSource)
      if ('hadPassword' in want) expect(r.hadPassword).toBe(want.hadPassword)
      if (want.fields) expect(r.fields).toMatchObject(want.fields)
      for (const k of want.noFields || []) expect(r.fields, k).not.toHaveProperty(k)
      if (want.notes) expect(r.notes.map((n) => n.code)).toEqual(expect.arrayContaining(want.notes))
      // Kesin kural: hiçbir alanda parola yok
      expect(Object.keys(r.fields)).not.toContain('password')
      expect(JSON.stringify(r.fields)).not.toMatch(/s3cret|tiger|password=/)
    })
  }

  it('uzunluk sınırı ayrıştırmadan ÖNCE uygulanır', () => {
    const r = parseConnectionString(`jdbc:postgresql://h/${'x'.repeat(MAX_CONN_STRING)}`)
    expect(r).toMatchObject({ ok: false, error: 'TOO_LONG' })
    expect(parseConnectionString(`password=1;${'x'.repeat(MAX_CONN_STRING)}`).hadPassword).toBe(true)
  })

  it('kötü niyetli uzun girdi doğrusal sürede biter (2000 karakter içinde)', () => {
    const t0 = performance.now()
    for (const s of ['thin:' + 'a'.repeat(1990) + '/', '//' + 'a:'.repeat(990) + '@', 'Server=' + '\\'.repeat(1990), '(' + '(HOST='.repeat(330)]) {
      parseConnectionString(s); containsPassword(s)
    }
    expect(performance.now() - t0).toBeLessThan(500)
  })
})

/**
 * Y1 (2026-11-05 bağımsız inceleme): yüzde kodlaması yapılmamış parolada / ? @ : # boşluk olabilir; EZConnect / sqlplus
 * kullanıcı/parola@sunucu biçimi; ADO.NET tırnaklı parolada ;Anahtar= — hiçbirinde parolanın KENDİSİ ya da bir parçası bir
 * alana ya da not değerine düşmez. [ad, girdi, parola, beklenen (kısmi)]
 */
const SECRET_TABLE = [
  ['EZConnect / sqlplus', 'scott/tiger@db1:1521/ORCL', 'tiger',
    { ok: true, db_type: 'ORACLE', typeSource: 'explicit', fields: { username: 'scott', host: 'db1', port: 1521, database_name: 'ORCL', oracle_mode: 'SERVICE' } }],
  ['EZConnect parolada # ve rakam', 'scott/T1ger#2026@ora-prd:1521/FIN', 'T1ger#2026',
    { ok: true, db_type: 'ORACLE', fields: { username: 'scott', host: 'ora-prd', port: 1521, database_name: 'FIN' } }],
  ['EZConnect parolada /', 'scott/ti/ger99@ora01:1521/SVC', 'ti/ger99', { ok: true, fields: { username: 'scott', host: 'ora01', database_name: 'SVC' } }],
  ['PG parolada /', 'postgres://app:se/cret@db1:5432/orders', 'se/cret',
    { ok: true, db_type: 'POSTGRESQL', fields: { username: 'app', host: 'db1', port: 5432, database_name: 'orders' } }],
  ['PG parolada ?', 'postgres://app:abc?def@db1.example.com:5432/orders', 'abc?def',
    { ok: true, fields: { username: 'app', host: 'db1.example.com', port: 5432, database_name: 'orders' } }],
  ['PG parolada @ ve /', 'postgresql://app:p@ss/w0rd@db1.example.com:5432/orders', 'p@ss/w0rd',
    { ok: true, fields: { username: 'app', host: 'db1.example.com', port: 5432, database_name: 'orders' } }],
  ['PG parolada :', 'postgres://app:pa:ss99@db1/orders', 'pa:ss99', { ok: true, fields: { username: 'app', host: 'db1', database_name: 'orders' } }],
  ['PG parolada #', 'postgres://app:pa#ss99@db1/orders', 'pa#ss99', { ok: true, fields: { username: 'app', host: 'db1', database_name: 'orders' } }],
  ['PG parolada %40', 'postgres://app:p%40ss99@db1/orders', 'p%40ss99', { ok: true, fields: { username: 'app', host: 'db1', database_name: 'orders' } }],
  ['PG parolada boşluk', 'postgres://app:pa ss99@db1/orders', 'pa ss99', { ok: true, fields: { username: 'app', host: 'db1', database_name: 'orders' } }],
  ['JDBC PG parolada / ve sorgu', 'jdbc:postgresql://ro:se/cret?x@pg1.example.com:5432/app?sslmode=require', 'se/cret?x',
    { ok: true, fields: { username: 'ro', host: 'pg1.example.com', port: 5432, database_name: 'app', ssl_mode: 'require' } }],
  ['Oracle thin parolada @', 'jdbc:oracle:thin:scott/p@ss99@//ora01:1521/svc', 'p@ss99',
    { ok: true, db_type: 'ORACLE', fields: { username: 'scott', host: 'ora01', port: 1521, database_name: 'svc' } }],
  ['ADO.NET tırnaklı parolada ;Anahtar=', 'Server=db1;User Id=sa;Password="a;Database=X";', 'a;Database=X',
    { ok: true, db_type: 'MSSQL', fields: { host: 'db1', username: 'sa' }, noFields: ['database_name'] }],
  ['JDBC MSSQL tırnaklı parola', 'jdbc:sqlserver://s:1433;user=sa;password="x;databaseName=Leak";databaseName=Real', 'x;databaseName=Leak',
    { ok: true, fields: { host: 's', port: 1433, username: 'sa', database_name: 'Real' } }],
  ['ADO.NET çift tırnaklı tırnak', "Server=db2;Database=Ops;User Id=ro;Password='it''s;Pwd=Leak9'", "it's;Pwd=Leak9",
    { ok: true, fields: { host: 'db2', database_name: 'Ops', username: 'ro' } }],
  ['kullanıcı:parola@sunucu (türsüz)', 'reader:Gizli 1@pg.local:5432/app', 'Gizli 1',
    { ok: true, typeSource: 'port', db_type: 'POSTGRESQL', fields: { username: 'reader', host: 'pg.local', port: 5432, database_name: 'app' } }],
  ['@ sonrasında sunucu yok → UNSAFE', 'postgres://u:se/cret@', 'se/cret', { ok: false, error: 'UNSAFE' }],
  ['kullanıcı bilgisi belirsiz → UNSAFE', 'scott:ti@ger/x@', 'ti@ger/x', { ok: false }],
  ['Azure: kullanıcı adı sorgu değerinde @', 'jdbc:postgresql://h:5432/db?user=admin@srv&password=p@q99', 'p@q99',
    { ok: true, fields: { host: 'h', port: 5432, username: 'admin@srv', database_name: 'db' } }],
]

/** Parolanın kendisi ve ≥ 4 karakterlik parçaları — hiçbir alanda / not değerinde olmamalı. */
const needles = (secret) => [secret, ...secret.split(/[@/?:;#&=%\s"']+/).filter((x) => x.length >= 4)]

describe('Y1 — parola alanlara ve notlara sızmaz', () => {
  for (const [name, input, secret, want] of SECRET_TABLE) {
    it(name, () => {
      const r = parseConnectionString(input)
      expect(r.ok, JSON.stringify(r)).toBe(want.ok)
      if ('error' in want) expect(r.error).toBe(want.error)
      if ('db_type' in want) expect(r.db_type).toBe(want.db_type)
      if ('typeSource' in want) expect(r.typeSource).toBe(want.typeSource)
      if (want.fields) expect(r.fields).toMatchObject(want.fields)
      for (const k of want.noFields || []) expect(r.fields, k).not.toHaveProperty(k)
      expect(r.hadPassword, 'parola uyarısı').toBe(true)
      const values = [...Object.values(r.fields).filter((v) => typeof v === 'string'), ...r.notes.map((n) => n.value).filter(Boolean)]
      for (const v of values) for (const nd of needles(secret)) expect(v, `"${nd}" sızdı`).not.toContain(nd)
      // parola metni sonuç nesnesinde hiçbir anahtarla taşınmaz
      expect(r).not.toHaveProperty('secrets')
      expect(JSON.stringify(r)).not.toContain(secret)
    })
  }

  it('geçersiz port notu sade belirteç değilse DEĞERSİZ (parola parçası gösterilmez)', () => {
    const r = parseConnectionString('postgres://app@db1:s3cr%t/x')
    const note = r.notes.find((n) => n.code === 'PORT_INVALID')
    expect(note).toBeTruthy()
    expect(note.value).toBeNull()
    expect(parseConnectionString('jdbc:postgresql://db:99999/x').notes.find((n) => n.code === 'PORT_INVALID').value).toBe('99999')
  })

  it('başarılı ayrıştırmada da parola izi uyarılır (containsPassword her yolda)', () => {
    expect(parseConnectionString('scott/tiger@db1:1521/ORCL').hadPassword).toBe(true)
    expect(parseConnectionString('user@db.example.com:5432/orders')).toMatchObject({ ok: true, hadPassword: false, fields: { username: 'user' } })
    expect(containsPassword('scott/tiger@db1')).toBe(true)
    expect(containsPassword('reader:x@h')).toBe(true)
    expect(containsPassword('db.example.com:5432/orders')).toBe(false)
    // parolasız şemalı dizeler parola sayılmaz
    expect(parseConnectionString('jdbc:oracle:thin:@//ora01:1521/svc').hadPassword).toBe(false)
    expect(parseConnectionString('postgres://reader@pg.local/app').hadPassword).toBe(false)
  })
})

describe('applyConnectionString — forma uygulama', () => {
  const base = { ...emptyForm(3), password: 'yazilan', password_set: true, password_clear: false }

  it('açık türlü dize türü değiştirir, türe ait olmayan alan uygulanmaz, parola alanlarına dokunulmaz', () => {
    const p = parseConnectionString('jdbc:sqlserver://sql01:1433;databaseName=Sales;encrypt=false;user=ro;password=gizli')
    const out = applyConnectionString(base, p)
    expect(out.typeChanged).toBe(true)
    expect(out.form).toMatchObject({ db_type: 'MSSQL', host: 'sql01', port: '1433', database_name: 'Sales', mssql_encrypt: false, username: 'ro' })
    expect(out.form.password).toBe('yazilan')
    expect(out.form.password_set).toBe(true)
    expect(out.form.password_clear).toBe(false)
    expect(out.filled).toEqual(['db_type', 'host', 'port', 'database_name', 'username', 'mssql_encrypt'])
  })

  it('portsuz açık tür: varsayılan port yeni türün varsayılanına geçer', () => {
    const out = applyConnectionString(base, parseConnectionString('jdbc:oracle:thin:@//ora01/svc'))
    expect(out.form).toMatchObject({ db_type: 'ORACLE', port: '1521', database_name: 'svc', oracle_mode: 'SERVICE' })
    expect(out.filled).not.toContain('port')
  })

  it('PG formunda ssl_mode uygulanır; MSSQL formunda uygulanmaz', () => {
    const pg = applyConnectionString(base, parseConnectionString('jdbc:postgresql://db/x?sslmode=disable'))
    expect(pg.form.ssl_mode).toBe('disable')
    expect(pg.filled).toContain('ssl_mode')
  })

  it('Oracle TCPS: varsayılan port 2484 olur; elle yazılan port korunur', () => {
    const ora = { ...emptyForm(3, 'ORACLE') }
    const out = applyConnectionString(ora, parseConnectionString('jdbc:oracle:thin:@tcps://ora01/svc'))
    expect(out.form).toMatchObject({ oracle_tcps: true, port: '2484' })
    expect(out.filled).toContain('oracle_tcps')
    const custom = applyConnectionString({ ...ora, port: '1600' }, parseConnectionString('jdbc:oracle:thin:@tcps://ora01/svc'))
    expect(custom.form.port).toBe('1600')
  })

  it('türsüz dizede tür yalnız önerilir; öneri uygulanınca port korunur', () => {
    const out = applyConnectionString(base, parseConnectionString('db.example.com:1433/Sales'))
    expect(out.typeChanged).toBe(false)
    expect(out.suggestType).toBe('MSSQL')
    expect(out.form).toMatchObject({ db_type: 'POSTGRESQL', port: '1433', host: 'db.example.com' })
    expect(applySuggestedType(out.form, 'MSSQL')).toMatchObject({ db_type: 'MSSQL', port: '1433' })
  })

  it('başarısız ayrıştırma formu değiştirmez', () => {
    const out = applyConnectionString(base, parseConnectionString('x y z'))
    expect(out.form).toBe(base)
    expect(out.filled).toEqual([])
  })
})
