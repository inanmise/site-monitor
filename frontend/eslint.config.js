// ESLint flat config (ESLint 9) — package.json "type": "module" olduğu için ESM.
//
// NEDEN VAR: 2026-08-19'da üretimde Sistem Sağlığı ekranı
// `ReferenceError: ProgressBar is not defined` ile çöktü — bileşen `<ProgressBar>`
// kullanıyordu ama import listesinde yoktu. Depoda o gün HİÇ statik analiz yoktu;
// Vite/Rollup çözülmemiş bir tanımlayıcıyı çalışma-zamanı global'i sayıp build'i yeşil
// geçirir, dolayısıyla hata ancak kullanıcı o sekmeyi açınca ortaya çıktı.
// `react/jsx-no-undef` bu hatayı commit anında yakalardı.
//
// KURAL POLİTİKASI (bilinçli):
//   error → yalnız ÜRETİMİ DÜŞÜREN sınıflar. CI bunlarda kırmızıya döner.
//   warn  → geri kalan recommended/react/hooks kuralları. Görünür olur, CI'ı KİLİTLEMEZ.
// Böylece bug sınıfı hemen kapanır, mevcut teknik borç kademeli temizlenir.
// Bir kuralı susturmak gerekiyorsa gerekçesini yazın; error setini gevşetmeyin.
import js from '@eslint/js'
import react from 'eslint-plugin-react'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'

/** recommended setlerini toptan "warn"a indirger (error seti aşağıda elle geri açılır). */
function asWarnings(rules) {
  return Object.fromEntries(
    Object.entries(rules ?? {}).map(([name, level]) => {
      const opts = Array.isArray(level) ? level.slice(1) : []
      return [name, opts.length ? ['warn', ...opts] : 'warn']
    })
  )
}

/** Üretimi düşüren sınıflar — bunlar HER ZAMAN error. */
const CRASH_RULES = {
  'no-undef': 'error',
  'react/jsx-no-undef': 'error',
  'react-hooks/rules-of-hooks': 'error',
}

export default [
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules*/**', 'playwright-report/**', 'test-results/**'],
  },

  // ── Uygulama kaynağı ────────────────────────────────────────────────────────
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      parserOptions: { ecmaFeatures: { jsx: true } },
      globals: {
        ...globals.browser,
        // vite.config.js `define` ile enjekte ediyor — bildirilmezse no-undef yanlış alarm verir.
        __APP_VERSION__: 'readonly',
      },
    },
    settings: { react: { version: 'detect' } },
    plugins: { react, 'react-hooks': reactHooks },
    rules: {
      ...asWarnings(js.configs.recommended.rules),
      ...asWarnings(react.configs.flat.recommended.rules),
      ...asWarnings(reactHooks.configs['recommended-latest']?.rules ?? {}),
      // Yeni JSX transform kullanılıyor (dosyalarda `import React` YOK) → bu ikisi kapalı
      // olmazsa her dosya hata verir.
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      // Türkçe metinlerde ' ve " serbest; kaçış zorunluluğu gürültü üretir.
      'react/no-unescaped-entities': 'off',
      // propTypes bu projede kullanılmıyor (tip sözleşmesi testlerle korunuyor).
      'react/prop-types': 'off',
      ...CRASH_RULES,
    },
  },

  // ── Testler ─────────────────────────────────────────────────────────────────
  // vite.config.js `test.globals: true` → describe/it/expect/vi ambient.
  // Bildirilmezse ~90 test dosyası no-undef ile kırmızıya döner.
  {
    files: ['src/test/**/*.{js,jsx}', '**/*.test.{js,jsx}', 'e2e/**/*.{js,jsx}'],
    languageOptions: {
      globals: {
        ...globals.node,
        ...globals.browser,
        describe: 'readonly', it: 'readonly', test: 'readonly', expect: 'readonly',
        vi: 'readonly', beforeEach: 'readonly', afterEach: 'readonly',
        beforeAll: 'readonly', afterAll: 'readonly',
        // vitest CJS köprüsü sağlıyor; bekçi testleri kaynak kökünü bununla çözüyor.
        __dirname: 'readonly', __filename: 'readonly',
      },
    },
  },

  // ── Build/üretim script'leri ve yapılandırma ────────────────────────────────
  {
    files: ['scripts/**/*.{js,mjs}', '*.config.{js,mjs}'],
    languageOptions: { globals: { ...globals.node } },
  },
]
