// Veritabanı Sorgu İzleme — YERLEŞİK başlangıç şablonları (2026-10-31, 2.C) ve hazır DBA şablonları (2026-11-05, B5).
// İstemcide sabit, salt okunur, veritabanı türüne göre SQL (PostgreSQL / SQL Server / Oracle). Takım şablonlarıyla aynı
// biçim (`sql_text`, `purpose`, `rules`, `rule_logic`, `db_type`) + `builtin: true`. Ad / açıklama / amaç / kural adı
// sözlükten (`dbq.btpl.<anahtar>.*`).
//
// SQL metinleri `builtinTemplatesSql.json`'da: aynı dosyayı sunucu testi (`DbQueryBuiltinTemplatesGuardTest`) okuyup HER
// şablonu her türde gerçek güvenlik denetiminden (`DbQuerySqlGuard`) geçirir — burada yazılan sorgu kayıtta reddedilemez.
// Kurallar: yalnız SELECT, SQL Server'da HER tablo / sistem görünümünde `WITH (NOLOCK)`, yalnız yerleşik işlevler; tablo /
// kolon adları ÖRNEK (uygulama şablonları — kullanıcı kendi şemasına göre değiştirir). Kural biçimi sunucuyla aynı
// (dbquery-api.md §4); tazelik eşiği SANİYE.
//
// DBA şablonları (`category: 'dba'`): sistem görünümlerini okur (replikasyon gecikmesi, bloklanan oturum, uzun işlem,
// veritabanı boyutu, başarısız iş) — her biri gereken yetkiyi (`privilege`, türe göre) ve önerilen çalışma aralığını
// (`interval_seconds`) açıkça taşır.

import SQL from './builtinTemplatesSql.json'

export const BUILTIN_KEYS = Object.freeze(['recent_problems', 'stuck_queue', 'freshness', 'daily_job'])
export const DBA_KEYS = Object.freeze(['replica_lag', 'blocked_sessions', 'long_transactions', 'database_size', 'failed_jobs'])
const DB = ['POSTGRESQL', 'MSSQL', 'ORACLE']

/** DBA şablonlarının önerilen çalışma aralığı (saniye). */
const DBA_INTERVAL = { replica_lag: 300, blocked_sessions: 300, long_transactions: 300, database_size: 3600, failed_jobs: 900 }

/** Şablonun kuralları — kimlikler sabit (`r-b-<anahtar>`); izleme oluşturulunca sunucu aynen saklar. */
function rulesOf(key, t, type) {
  const rule = (over) => ({
    id: `r-b-${key}`, label: t(`dbq.btpl.${key}.rule`), column: null, aggregate: null, operator: 'GT', scope: null,
    warning: null, critical: null, change: null, ...over,
  })
  const value = (column, warning, critical, operator = 'GT') => rule({ kind: 'COLUMN_VALUE', column, aggregate: 'FIRST', operator, warning, critical })
  switch (key) {
    case 'recent_problems': return [value('error_count', 10, 50)]
    case 'stuck_queue': return [value('stuck_count', 0, 25)]
    case 'freshness': return [rule({ kind: 'FRESHNESS', column: 'last_record_at', aggregate: 'MAX', warning: 900, critical: 3600 })]
    case 'daily_job': return [value('completed_count', null, 1, 'LT')]
    case 'replica_lag': {
      const lag = [value('replica_lag_seconds', 30, 300)]
      // Oracle Data Guard görünümü tek satır döner (yedekte çalışır) — "bağlı yedek yok" kuralı yalnız birincilde
      if (type === 'ORACLE') return lag
      return [...lag, { ...value('replica_count', null, 1, 'LT'), id: 'r-b-replica_lag-count', label: t('dbq.btpl.replica_lag.rule2') }]
    }
    case 'blocked_sessions': return [value('blocked_sessions', 2, 10)]
    case 'long_transactions': return [value('long_tx_count', 0, 5)]
    case 'database_size': return [value('database_size_mb', 500000, 750000)]
    case 'failed_jobs': return [value('failed_job_count', 0, 3)]
    default: return []
  }
}

function templateOf(key, t, type, extra = {}) {
  return {
    id: `builtin:${key}`, key, builtin: true, can_edit: false,
    name: t(`dbq.btpl.${key}.name`), description: t(`dbq.btpl.${key}.desc`), purpose: t(`dbq.btpl.${key}.purpose`),
    db_type: type, sql_text: SQL[key][type].join('\n'), rules: rulesOf(key, t, type), rule_logic: 'ANY', ...extra,
  }
}

/**
 * Yerleşik başlangıç (uygulama) şablonları — `dbType` verilmezse (bağlantı seçilmedi) PostgreSQL sürümü. Her öğe takım
 * şablonu biçiminde + `builtin: true`, `id: 'builtin:<anahtar>'`, `key`.
 */
export function builtinTemplates(t, dbType = null) {
  const type = DB.includes(dbType) ? dbType : 'POSTGRESQL'
  return BUILTIN_KEYS.map((key) => templateOf(key, t, type))
}

/**
 * Hazır DBA şablonları (B5) — sistem görünümlerinden okur. Ek alanlar: `category: 'dba'`, `privilege` (bu türde gereken
 * yetki — metin), `interval_seconds` (önerilen aralık). `dbType` verilmezse PostgreSQL sürümü.
 */
export function dbaTemplates(t, dbType = null) {
  const type = DB.includes(dbType) ? dbType : 'POSTGRESQL'
  return DBA_KEYS.map((key) => templateOf(key, t, type, {
    category: 'dba', privilege: t(`dbq.btpl.${key}.priv.${type}`), interval_seconds: DBA_INTERVAL[key],
  }))
}

/** Testler ve sunucu denetimi için: anahtar × tür → SQL metni. */
export function builtinSql(key, dbType) {
  return SQL[key]?.[dbType]?.join('\n') ?? null
}
