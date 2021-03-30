import { chromium } from 'playwright'

describe('the landing page', () => {
  it('it has login options', async () => {
    let browser = await chromium.launch()
    let page = await browser.newPage()

    await page.goto(process.env.BASE_URL)

    expect(await page.textContent('html')).toContain('continue as guest')
  })
})