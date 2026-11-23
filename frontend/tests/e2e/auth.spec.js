import { test, expect } from '@playwright/test';

const password = 'demo-password-123';
async function register(page) {
    await page.goto('/register');
    await page.getByLabel('Full name', {exact:true}).fill('Demo Student');
    await page.locator('input[name="email"]:visible').fill('demo@example.com');
    await page.locator('input[name="password"]:visible').fill(password);
    await page.locator('input[name="confirmPassword"]:visible').fill(password);
    await page.getByRole('checkbox', {name:'I agree to DevSphere’s Terms of Service'}).check();
    await page.getByRole('button', {name:'Create Account',exact:true}).click();
    await expect(page).toHaveURL(/\/check-email$/);
}
async function login(page, remember=false) {
    await page.goto('/login');
    await page.locator('input[name="email"]:visible').fill('demo@example.com');
    await page.locator('input[name="password"]:visible').fill(password);
    if (remember) await page.getByRole('checkbox', {name:'Remember me'}).check();
    await page.getByRole('button', {name:'Sign In',exact:true}).click();
    await expect(page).toHaveURL(/\/workspaces$/);
}

test('approved auth layout: submit-only error, red line, gray eye, no focus rectangle', async ({page}) => {
    await page.goto('/register');
    await expect(page.getByText('Complete all required fields.',{exact:true})).toBeHidden();
    await page.getByRole('button',{name:'Create Account',exact:true}).click();
    await expect(page.getByText('Complete all required fields.',{exact:true})).toBeVisible();
    await expect(page.locator('input[name="password"]:visible')).toHaveCSS('border-bottom-color','rgb(255, 90, 101)');
    await expect(page.getByRole('button',{name:'Show password',exact:true})).toHaveCSS('color','rgb(168, 171, 166)');
    await expect(page.getByLabel('Full name',{exact:true})).toHaveCSS('outline-style','none');
    await page.getByLabel('Full name',{exact:true}).fill('Demo');
    await expect(page.getByText('Complete all required fields.',{exact:true})).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth);
    expect(overflow).toBe(false);
});

test('registration uses shared OTP, rejects wrong code, survives reload and returns to login', async ({page}) => {
    await register(page);
    await page.reload();
    await page.getByLabel('Digit 1',{exact:true}).fill('000000');
    await expect(page.getByText('Incorrect code. Try again.',{exact:true})).toBeVisible();
    await expect(page).toHaveURL(/\/check-email$/);
    await page.getByLabel('Digit 1',{exact:true}).fill('123456');
    await expect(page.getByText('Email verified. Returning to Sign In…',{exact:true})).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Email verified. Sign in to continue.',{exact:true})).toBeVisible();
});

test('forgot password requires verified OTP before reset and consumes grant', async ({page}) => {
    await page.goto('/forgot-password');
    await page.locator('input[name="email"]:visible').fill('demo@example.com');
    await page.getByRole('button',{name:'Send Code',exact:true}).click();
    await expect(page).toHaveURL(/\/check-email$/);
    await page.getByLabel('Digit 1',{exact:true}).fill('123456');
    await expect(page).toHaveURL(/\/create-new-password$/);
    await page.reload();
    await page.getByLabel('New password',{exact:true}).fill(password);
    await page.locator('input[name="confirmPassword"]:visible').fill(password);
    await page.getByRole('button',{name:'Reset password',exact:true}).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Password reset complete. Sign in again.',{exact:true})).toBeVisible();
    await page.goto('/create-new-password');
    await page.getByLabel('New password',{exact:true}).fill(password);
    await page.locator('input[name="confirmPassword"]:visible').fill(password);
    await page.getByRole('button',{name:'Reset password',exact:true}).click();
    await expect(page.getByText('Verify your code first.',{exact:true})).toBeVisible();
});

test('Remember me persists demo session in another tab; logout revokes access', async ({page,context}) => {
    await login(page,true);
    await page.reload();
    await expect(page.getByRole('heading',{name:'My Workspaces',exact:true})).toBeVisible();
    const another = await context.newPage();
    await another.goto('/workspaces');
    await expect(another.getByRole('heading',{name:'My Workspaces',exact:true})).toBeVisible();
    await page.getByRole('button',{name:'Log out',exact:true}).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(another).toHaveURL(/\/login$/);
});

test('unchecked Remember me is tab-only; expired demo session cannot open workspace', async ({page,context}) => {
    await login(page);
    await page.reload();
    await expect(page.getByRole('heading',{name:'My Workspaces',exact:true})).toBeVisible();
    const another = await context.newPage();
    await another.goto('/workspaces');
    await expect(another).toHaveURL(/\/login$/);
    await page.evaluate(() => {
        const key='devsphere.demo.session';
        const session=JSON.parse(sessionStorage.getItem(key));
        session.expiresAt=Date.now()-1;
        sessionStorage.setItem(key,JSON.stringify(session));
    });
    await page.reload();
    await expect(page).toHaveURL(/\/login$/);
});

test('Google button starts only a clearly labeled frontend demo', async ({page}) => {
    await page.goto('/login');
    await page.getByRole('button',{name:'Continue with Google',exact:true}).click();
    await expect(page).toHaveURL(/\/workspaces$/);
    await expect(page.getByText('Frontend demo · No real Google sign-in, account or server session.',{exact:true})).toBeVisible();
    await expect(page.getByText('Google Demo',{exact:true})).toBeVisible();
});

test('terms link opens a separate page and preserves entered registration data', async ({page}) => {
    await page.goto('/register');
    await page.getByLabel('Full name',{exact:true}).fill('Demo');
    const popupPromise=page.waitForEvent('popup');
    await page.getByRole('link',{name:'Terms of Service',exact:true}).click();
    const popup=await popupPromise;
    await expect(popup.getByRole('heading',{name:'Terms of Service',exact:true})).toBeVisible();
    await expect(page.getByLabel('Full name',{exact:true})).toHaveValue('Demo');
});
