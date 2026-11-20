import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests/e2e',
    timeout: 30000,
    fullyParallel: false,
    workers: 1,
    reporter: 'list',
    use: { baseURL: 'http://127.0.0.1:3200', trace: 'retain-on-failure', channel: process.env.CI ? undefined : 'chrome' },
    projects: [
        { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
        { name: 'mobile', use: { viewport: { width: 390, height: 844 } } },
    ],
    webServer: {
        command: 'npm run dev -- --hostname 127.0.0.1 --port 3200',
        url: 'http://127.0.0.1:3200',
        reuseExistingServer: !process.env.CI,
        env: { NEXT_DIST_DIR: '.next-e2e', NEXT_PUBLIC_AUTH_MOCK: 'true' },
        timeout: 120000,
    },
});
