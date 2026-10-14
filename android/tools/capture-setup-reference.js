async page => {
 const rows=[];
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(language=>{book().language=language;render();setupState={step:1,name:'初期の生活',base:'JPY',budget:50000,date:'2026-10-01',accounts:[{id:'cash',name:'お財布',type:'cash',currency:'JPY',balance:10000},{id:'bank',name:'外貨口座',type:'bank',currency:'CNY',balance:200},{id:'card',name:'カード',type:'credit',currency:'JPY',balance:3000},{id:'loan',name:'借入',type:'loan',currency:'USD',balance:400}]};navigate('onboarding');},language);
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}.bottom-nav{display:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  for(const step of [1,2,3,4]){
   await page.evaluate(step=>{setupState.step=step;render();document.querySelector('.back-bar')?.remove();document.querySelectorAll('[data-action="setup-sample"],.exp-demo-note').forEach(e=>e.remove());window.scrollTo(0,0);},step);
   await page.screenshot({path:`android/verification/reference/setup-${theme}-${language}-${step}.png`,fullPage:true});
   rows.push(await page.evaluate(({theme,language,step})=>({theme,language,step,nodes:[...document.querySelectorAll('main,.setup-steps,.exp-lead,.setup-account-list,.setup-account,.outline-button,.setup-note,.setup-review,.setup-next-pages,.full-button,.setup-back-link')].map(e=>{const c=getComputedStyle(e),rect=e.getBoundingClientRect().toJSON(),old=e.style.getPropertyValue('transform'),priority=e.style.getPropertyPriority('transform');e.style.setProperty('transform','none','important');const layoutRect=e.getBoundingClientRect().toJSON();if(old)e.style.setProperty('transform',old,priority);else e.style.removeProperty('transform');return{selector:e.className,rect,layoutRect,fontSize:c.fontSize,padding:c.padding,margin:c.margin};})}),{theme,language,step}));
  }
 }
 return rows;
}
