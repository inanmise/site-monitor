/**
 * Bağlantı örnekleri (Veritabanı Sorgu İzleme, 2026-11-05) — bağlantı formunda "Bağlantı örnekleri" bölümü ve alan
 * yer tutucuları için TEK kaynak. Değerler gerçek DEĞİL (example.com); parola hiçbir örnekte yok.
 *
 * Her örneğin `conn` dizesi gerçek ayrıştırıcıdan ({@link ./connectionString.js}) geçer ve `fields` ile AYNI alanları
 * doldurur — kapı `dbqConnectionExamples.test.js`. Alan değerleri sunucunun KAYIT kurallarına uyar
 * (`DbConnectionProfileService` sınırları ve desenleri; aşağıdaki `RULES`).
 *
 * SQL Server adlandırılmış örnek: sunucu sürücüye portu HER ZAMAN verir ve sürücü port verildiğinde örnek adını kullanmaz
 * (portNumber önceliklidir; SQL Browser / UDP 1434 sorgusu yapılmaz) — bu yüzden örnek o örneğin KENDİ TCP portunu taşır.
 */

/**
 * Sunucu kuralları — kayıt doğrulamasının (`DbConnectionProfileService`: `MAX_*`, `HOSTNAME`, `IPV6`, `DATABASE`,
 * `INSTANCE` desenleri) ön yüz eşi. Kaydın kendisi esastır: çalışma anı denetimi (`DbJdbcUrls.requireName` /
 * `requireHostName`) daha gevşektir (örnek adında `.`, sunucu adında `_` kabul eder) ama o değerler kayıtta zaten reddedilir
 * — örnekler kaydedilebilir olmalı (2026-11-05 hata avı D5).
 */
export const RULES = Object.freeze({
  nameMax: 120,
  hostMax: 253,
  databaseMax: 128,
  instanceMax: 64,
  usernameMax: 128,
  passwordMax: 512,
  descriptionMax: 500,
  /** Veritabanı / servis / SID adı karakterleri (`DATABASE`: `[A-Za-z0-9_.$#-]`). */
  objectName: /^[A-Za-z0-9_.$#-]+$/,
  /** SQL Server örnek adı karakterleri (`INSTANCE`: `[A-Za-z0-9_$#-]` — nokta YOK). */
  instance: /^[A-Za-z0-9_$#-]+$/,
  /** Sunucu adı (`HOSTNAME`: harf / rakamla başlar ve biter, arada harf, rakam, `.` `-`; `_` YOK; `..` yok). */
  host: /^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$/,
  /** IPv6 (`IPV6`: iki nokta içeren adres, 2–45 karakter). */
  ipv6: /^[0-9A-Fa-f:.]{2,45}$/,
  /** Kullanıcı adında olamayanlar. */
  usernameForbidden: /[;{}\u0000-\u001f]/,
})

export const CONNECTION_EXAMPLES = Object.freeze({
  POSTGRESQL: Object.freeze([
    Object.freeze({
      id: 'pg-standard',
      fields: Object.freeze({ host: 'pg-rapor-01.example.com', port: 5432, database_name: 'odeme', ssl_mode: 'require',
        username: 'sitemonitor_ro' }),
      conn: 'jdbc:postgresql://pg-rapor-01.example.com:5432/odeme?sslmode=require&user=sitemonitor_ro',
    }),
    Object.freeze({
      id: 'pg-verify',
      fields: Object.freeze({ host: 'pg-rapor-01.example.com', port: 5432, database_name: 'odeme', ssl_mode: 'verify-full',
        username: 'sitemonitor_ro' }),
      conn: 'postgresql://sitemonitor_ro@pg-rapor-01.example.com:5432/odeme?sslmode=verify-full',
    }),
  ]),
  MSSQL: Object.freeze([
    Object.freeze({
      id: 'mssql-default',
      fields: Object.freeze({ host: 'sql-prod-01.example.com', port: 1433, database_name: 'OdemeDB', mssql_encrypt: true,
        mssql_trust_server_certificate: false, username: 'sitemonitor_ro' }),
      conn: 'jdbc:sqlserver://sql-prod-01.example.com:1433;databaseName=OdemeDB;encrypt=true;trustServerCertificate=false;user=sitemonitor_ro',
    }),
    Object.freeze({
      id: 'mssql-named',
      fields: Object.freeze({ host: 'sql-prod-01.example.com', port: 14331, mssql_instance: 'SQL01', database_name: 'RaporDB',
        mssql_encrypt: true, username: 'sitemonitor_ro' }),
      conn: 'Server=sql-prod-01.example.com\\SQL01,14331;Database=RaporDB;User Id=sitemonitor_ro;Encrypt=True',
    }),
  ]),
  ORACLE: Object.freeze([
    Object.freeze({
      id: 'ora-service',
      fields: Object.freeze({ host: 'ora-scan.example.com', port: 1521, oracle_mode: 'SERVICE', database_name: 'ORCLPDB1.example.com',
        username: 'SITEMONITOR_RO' }),
      conn: 'jdbc:oracle:thin:SITEMONITOR_RO@//ora-scan.example.com:1521/ORCLPDB1.example.com',
    }),
    Object.freeze({
      id: 'ora-sid',
      fields: Object.freeze({ host: 'ora-db01.example.com', port: 1521, oracle_mode: 'SID', database_name: 'ORCL' }),
      conn: 'jdbc:oracle:thin:@ora-db01.example.com:1521:ORCL',
    }),
    Object.freeze({
      id: 'ora-tcps',
      fields: Object.freeze({ host: 'ora-scan.example.com', port: 2484, oracle_mode: 'SERVICE', oracle_tcps: true,
        database_name: 'ORCLPDB1.example.com' }),
      conn: 'jdbc:oracle:thin:@tcps://ora-scan.example.com:2484/ORCLPDB1.example.com',
    }),
  ]),
})

/** Örnek alanlarının gösterim sırası (formdaki sırayla). */
export const EXAMPLE_FIELD_ORDER = Object.freeze(['host', 'port', 'mssql_instance', 'oracle_mode', 'oracle_tcps', 'database_name',
  'ssl_mode', 'mssql_encrypt', 'mssql_trust_server_certificate', 'username'])

/** Türün örnekleri (bilinmeyen tür → boş liste). */
export function examplesFor(type) {
  return CONNECTION_EXAMPLES[type] || []
}

/**
 * Formdaki alan yer tutucuları — türün ilk örneğinden; Oracle veritabanı alanı kipe göre (servis adı / SID).
 * Yalnız teknik değerler (çeviri gerekmez); ad ve açıklama yer tutucuları i18n'dedir.
 */
export function placeholdersFor(type, oracleMode = 'SERVICE') {
  const first = examplesFor(type)[0]
  if (!first) return {}
  const f = first.fields
  const sid = examplesFor('ORACLE').find((e) => e.fields.oracle_mode === 'SID')
  const named = examplesFor('MSSQL').find((e) => e.fields.mssql_instance)
  return {
    host: f.host,
    database_name: type === 'ORACLE' && oracleMode === 'SID' ? sid?.fields.database_name : f.database_name,
    username: f.username,
    mssql_instance: type === 'MSSQL' ? named?.fields.mssql_instance : undefined,
  }
}

/** Alanın sunucu kuralına uyup uymadığı (yalnız örnek değerlerin doğrulanması için — form doğrulaması sunucuda). */
export function valueFitsRule(field, value) {
  if (value == null) return true
  const s = String(value)
  switch (field) {
    case 'host':
      if (s.length > RULES.hostMax) return false
      return s.includes(':') ? RULES.ipv6.test(s) : RULES.host.test(s) && !s.includes('..')
    case 'port': return Number.isInteger(value) && value >= 1 && value <= 65535
    case 'database_name': return s.length <= RULES.databaseMax && RULES.objectName.test(s)
    case 'mssql_instance': return s.length <= RULES.instanceMax && RULES.instance.test(s)
    case 'username': return s.length <= RULES.usernameMax && !RULES.usernameForbidden.test(s)
    default: return true
  }
}
