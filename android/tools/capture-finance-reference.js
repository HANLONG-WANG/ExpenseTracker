async page => {
  const root='android/verification/phase-c-frozen-finance';
  const measurements=[];
  await page.setViewportSize({width:390,height:844});
  await page.emulateMedia({reducedMotion:'reduce'});
  const settle=async()=>{
    await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}'});
    await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
  };
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']) {
    await page.goto(`http://127.0.0.1:4183/design/app.html?theme=${theme}`);
    await page.evaluate(language=>{book().language=language;render();},language);
    for(const target of ['credit-overview','credit-statement','credit-plans','credit-rules','loan-revolving','loan-fixed','forecast','calendar','reminders']) {
      await page.evaluate(target=>{
        closeSheet();
        if(target.startsWith('credit-')){navigate('credit','sm',false);creditTab=target.slice(7);render();}
        else if(target.startsWith('loan-'))navigate('loan',target==='loan-fixed'?'loan-cn':'loan',false);
        else navigate(target,'',false);
        window.scrollTo({top:0,behavior:'instant'});
      },target);
      await settle();
      await page.screenshot({path:`${root}/${theme}-${language}-${target}.png`,fullPage:true});
      const nodes=await page.evaluate(()=>[...document.querySelectorAll('.app-header,.bottom-nav,main,.exp-lead,.experience-lead,.page-title,.audit-panel,.loan-plan-table,.plan-table,.future-account-timeline,.form-section')].filter(e=>e.getClientRects().length).map(e=>{
        const c=getComputedStyle(e);
        return {selector:e.className||e.tagName,text:e.textContent.slice(0,70),rect:e.getBoundingClientRect().toJSON(),font:c.fontFamily,fontSize:c.fontSize,lineHeight:c.lineHeight,padding:c.padding,margin:c.margin,background:c.backgroundColor,border:c.border,borderRadius:c.borderRadius};
      }));
      measurements.push({theme,language,target,width:390,height:844,nodes});
    }
  }
  return {source:'docs/reference/design-v55-source.tar.gz extracted without edits',screens:measurements.length,measurements};
}
