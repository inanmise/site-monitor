import { createRoot } from 'react-dom/client'
import ThresholdMatrix from '../../src/components/admin/thresholds/ThresholdMatrix.jsx'
import { LangProvider } from '../../src/i18n/index.jsx'
import '../../src/styles/globals.css'
import '../../src/App.css'

// Eşik karşılaştırma matrisi — izole harness (yalnız Playwright). Gerçek bileşen sabit veriyle; jsdom düzen yapmaz.
const ROWS = [
  { id: 1, tier: null, critical_days: 7, high_days: 15, warning_days: 30, re_alert_interval_hours: 24 },
  { id: 2, tier: 1, critical_days: 10, high_days: 20, warning_days: 45, re_alert_interval_hours: 12, active: true },
  { id: 3, tier: 2, critical_days: 7, high_days: 15, warning_days: 30, re_alert_interval_hours: 24, active: true },
]
const IMPACT = { default: { scope_total: 40 }, 1: { scope_total: 12 }, 2: { scope_total: 8 }, 3: { scope_total: 5 }, 4: { scope_total: 6 } }
createRoot(document.getElementById('host')).render(
  <LangProvider><div style={{ padding: 16 }}><ThresholdMatrix rows={ROWS} impact={IMPACT} /></div></LangProvider>,
)
