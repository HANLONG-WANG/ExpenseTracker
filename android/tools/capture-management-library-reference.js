// Serve repository root on 127.0.0.1:8769; this adds only the new UI26 management-state design.
async page => {
  await page.setViewportSize({width:390,height:844});
  const rows=[];
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']) {
    await page.goto(`http://127.0.0.1:8769/design/management.html?theme=${theme}&language=${language}&state=library`);
    const frame=page.frameLocator('#reference');
    await frame.locator('.management-state').waitFor();
    await frame.locator('body').evaluate(()=>document.fonts.ready);
    await frame.locator('.app-shell').screenshot({path:`android/verification/phase-e-management-reference/${theme}-${language}-library.png`});
    rows.push({theme,language,state:await frame.locator('.management-state').evaluate(e=>{const s=getComputedStyle(e);return {font:s.fontSize,line:s.lineHeight,padding:s.padding,bg:s.backgroundColor,radius:s.borderRadius}})});
  }
  return rows;
}
