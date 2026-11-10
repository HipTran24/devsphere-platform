import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';

export default defineConfig([
    ...nextVitals,
    globalIgnores(['.next/**', '.next-e2e/**', 'playwright-report/**', 'test-results/**']),
    { rules: {
        // Restore browser-only demo storage after hydration; server output stays deterministic.
        'react-hooks/set-state-in-effect': 'off',
        // The existing fixed-size SVG image intentionally matches the approved auth layout.
        '@next/next/no-img-element': 'off',
    } },
]);
