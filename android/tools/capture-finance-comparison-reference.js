async page => {
  const root='android/verification/phase-c-frozen-finance';
  const output=[];
  await page.setViewportSize({width:390,height:844});
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']) {
    await page.goto(`http://127.0.0.1:4183/design/app.html?theme=${theme}`);
    await page.evaluate(language=>{
      book().language=language;render();navigate('credit','sm',false);
      account('sm').rules={...account('sm').rules,splitMode:'table',splitTable:'3:2.01',rounding:'floor'};
      simulation('sm',10000);
    },language);
    await page.locator('[data-action="simulation-mode"][data-mode="split"]').click();
    await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}'});
    await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
    await page.locator('.plan-table').last().scrollIntoViewIfNeeded();
    await page.screenshot({path:`${root}/${theme}-${language}-credit-comparison.png`});
    output.push({theme,language,table:await page.locator('.plan-table').last().innerText()});
  }
  return output;
}
