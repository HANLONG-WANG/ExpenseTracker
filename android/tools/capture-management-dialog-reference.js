// Serve repository root on localhost:8769; supplemental UI26 fixed-fact dialog designs.
async page => {
  const measurements=[];
  await page.setViewportSize({width:390,height:844});
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja'])for(const state of ['book-trash-sheet','account-stop-sheet','category-stop-sheet','activity-delete-sheet','activity-expanded-sheet','entry-purge-sheet']) {
    await page.goto(`http://127.0.0.1:8769/design/management.html?theme=${theme}&language=${language}&state=${state}`);
    const frame=page.frameLocator('#reference');
    await frame.locator('.management-flow').waitFor();
    await frame.locator('body').evaluate(()=>document.fonts.ready);
    await frame.locator('body').evaluate(()=>{const style=document.createElement('style');style.textContent='*,*::before,*::after{animation:none!important;transition:none!important}.sheet{transform:none!important}';document.head.append(style);});
    await frame.locator('.sheet').evaluate(e=>{document.activeElement.blur();e.scrollTop=0;});
    await frame.locator('.sheet').screenshot({path:`android/verification/phase-e-dialog-reference/${theme}-${language}-${state}.png`});
    measurements.push({theme,language,state,sheet:await frame.locator('.sheet').boundingBox(),title:await frame.locator('#sheet-title').boundingBox(),nodes:await frame.locator('.management-flow').evaluate(e=>[e,e.querySelector('.management-name'),...e.querySelectorAll('.detail-row')].filter(Boolean).slice(0,4).map(n=>{const s=getComputedStyle(n);return {class:n.className,text:n.textContent,rect:n.getBoundingClientRect().toJSON(),font:s.fontSize,line:s.lineHeight,padding:s.padding,gap:s.gap}}))});
    await frame.locator('.sheet').evaluate(e=>e.scrollTop=e.scrollHeight);
    await frame.locator('.sheet').screenshot({path:`android/verification/phase-e-dialog-reference/${theme}-${language}-${state}-bottom.png`});
  }
  return measurements;
}
