// Veritabanı Sorgu İzleme — YERLEŞİK başlangıç şablonları (2026-10-31, 2.C). İstemcide sabit, salt okunur, veritabanı
// türüne göre SQL (PostgreSQL / SQL Server / Oracle). Takım şablonlarıyla aynı biçim (`sql_text`, `purpose`, `rules`,
// `rule_logic`, `db_type`) + `builtin: true`. Ad / açıklama / amaç / kural adı sözlükten (`dbq.btpl.<anahtar>.*`).
//
// Kurallar: yalnız SELECT, SQL Server'da HER tabloda `WITH (NOLOCK)`, yalnız yerleşik işlevler (güvenlik denetiminden ilk
// denemede geçer); tablo / kolon adları ÖRNEK — kullanıcı kendi şemasına göre değiştirir (açıklama bunu söyler). Kural
// biçimi sunucuyla aynı (dbquery-api.md §4); tazelik eşiği SANİYE.

export const BUILTIN_KEYS = Object.freeze(['recent_problems', 'stuck_queue', 'freshness', 'daily_job'])
const DB = ['POSTGRESQL', 'MSSQL', 'ORACLE']

const SQL = {
  recent_problems: {
    POSTGRESQL: ['SELECT COUNT(*) AS error_count', 'FROM app_errors', "WHERE created_at >= now() - interval '15 minutes'"],
    MSSQL: ['SELECT COUNT(*) AS error_count', 'FROM dbo.app_errors WITH (NOLOCK)', 'WHERE created_at >= DATEADD(MINUTE, -15, GETDATE())'],
    ORACLE: ['SELECT COUNT(*) AS error_count', 'FROM app_errors', "WHERE created_at >= SYSTIMESTAMP - INTERVAL '15' MINUTE"],
  },
  stuck_queue: {
    POSTGRESQL: ['SELECT COUNT(*) AS stuck_count', 'FROM job_queue', "WHERE status = 'PENDING'", "  AND created_at < now() - interval '30 minutes'"],
    MSSQL: ['SELECT COUNT(*) AS stuck_count', 'FROM dbo.job_queue WITH (NOLOCK)', "WHERE status = 'PENDING'", '  AND created_at < DATEADD(MINUTE, -30, GETDATE())'],
    ORACLE: ['SELECT COUNT(*) AS stuck_count', 'FROM job_queue', "WHERE status = 'PENDING'", "  AND created_at < SYSTIMESTAMP - INTERVAL '30' MINUTE"],
  },
  freshness: {
    POSTGRESQL: ['SELECT MAX(created_at) AS last_record_at', 'FROM orders'],
    MSSQL: ['SELECT MAX(created_at) AS last_record_at', 'FROM dbo.orders WITH (NOLOCK)'],
    ORACLE: ['SELECT MAX(created_at) AS last_record_at', 'FROM orders'],
  },
  daily_job: {
    POSTGRESQL: ['SELECT COUNT(*) AS completed_count', 'FROM batch_runs', "WHERE job_name = 'END_OF_DAY'", "  AND status = 'DONE'", "  AND finished_at >= date_trunc('day', now())"],
    MSSQL: ['SELECT COUNT(*) AS completed_count', 'FROM dbo.batch_runs WITH (NOLOCK)', "WHERE job_name = 'END_OF_DAY'", "  AND status = 'DONE'", '  AND finished_at >= CAST(GETDATE() AS date)'],
    ORACLE: ['SELECT COUNT(*) AS completed_count', 'FROM batch_runs', "WHERE job_name = 'END_OF_DAY'", "  AND status = 'DONE'", '  AND finished_at >= TRUNC(SYSDATE)'],
  },
}

/** Şablonun kuralları — kimlikler sabit (`r-b-<anahtar>`); izleme oluşturulunca sunucu aynen saklar. */
function rulesOf(key, t) {
  const rule = (over) => ({
    id: `r-b-${key}`, label: t(`dbq.btpl.${key}.rule`), column: null, aggregate: null, operator: 'GT', scope: null,
    warning: null, critical: null, change: null, ...over,
  })
  if (key === 'recent_problems') return [rule({ kind: 'COLUMN_VALUE', column: 'error_count', aggregate: 'FIRST', warning: 10, critical: 50 })]
  if (key === 'stuck_queue') return [rule({ kind: 'COLUMN_VALUE', column: 'stuck_count', aggregate: 'FIRST', warning: 0, critical: 25 })]
  if (key === 'freshness') return [rule({ kind: 'FRESHNESS', column: 'last_record_at', aggregate: 'MAX', warning: 900, critical: 3600 })]
  return [rule({ kind: 'COLUMN_VALUE', column: 'completed_count', aggregate: 'FIRST', operator: 'LT', critical: 1 })]
}

/**
 * Yerleşik şablonlar — `dbType` verilmezse (bağlantı seçilmedi) PostgreSQL sürümü. Her öğe takım şablonu biçiminde +
 * `builtin: true`, `id: 'builtin:<anahtar>'`, `key`.
 */
export function builtinTemplates(t, dbType = null) {
  const type = DB.includes(dbType) ? dbType : 'POSTGRESQL'
  return BUILTIN_KEYS.map((key) => ({
    id: `builtin:${key}`, key, builtin: true, can_edit: false,
    name: t(`dbq.btpl.${key}.name`), description: t(`dbq.btpl.${key}.desc`), purpose: t(`dbq.btpl.${key}.purpose`),
    db_type: type, sql_text: SQL[key][type].join('\n'), rules: rulesOf(key, t), rule_logic: 'ANY',
  }))
}

/** Testler ve sunucu denetimi için: anahtar × tür → SQL metni. */
export function builtinSql(key, dbType) {
  return SQL[key]?.[dbType]?.join('\n') ?? null
}
