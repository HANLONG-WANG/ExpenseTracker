async page => {
 const root='android/verification/reference';
 const measurements=[];
 await page.setViewportSize({width:390,height:844});
 await page.emulateMedia({reducedMotion:'reduce'});
 const settle=async()=>{
   await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}'});
   await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
 };
 for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']) {
   await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
   await page.evaluate(language=>{book().language=language;render();},language);
   await settle();
   for(const route of ['home','onboarding','accounts','categories','ledger','settings']) {
     await page.evaluate(route=>navigate(route,'',false),route);
     await settle();
     await page.screenshot({path:`${root}/${theme}-${language}-${route}.png`,fullPage:true});
     const nodes=await page.evaluate(()=>[...document.querySelectorAll('.app-header,.bottom-nav,main,h1,h2,.home-money,.exp-home-cover,.sheet')].filter(e=>e.getClientRects().length).map(e=>{
       const c=getComputedStyle(e);return {selector:e.className||e.tagName,text:e.textContent.slice(0,90),rect:e.getBoundingClientRect().toJSON(),font:c.fontFamily,fontSize:c.fontSize,fontWeight:c.fontWeight,letterSpacing:c.letterSpacing,lineHeight:c.lineHeight,padding:c.padding,margin:c.margin,color:c.color,background:c.backgroundColor,border:c.border,borderRadius:c.borderRadius};
     }));
     measurements.push({theme,language,route,width:390,height:844,nodes});
   }
 }

 await page.goto('http://127.0.0.1:4173/design/app.html?theme=magazine');await page.evaluate(()=>document.fonts.ready);
 const session=await page.context().newCDPSession(page);await session.send('DOM.enable');await session.send('CSS.enable');
 const {root:dom}=await session.send('DOM.getDocument');
 const {nodeId}=await session.send('DOM.querySelector',{nodeId:dom.nodeId,selector:'.home-money .money-value'});
 const fonts=await session.send('CSS.getPlatformFontsForNode',{nodeId});
 return {screens:measurements.length,fonts,measurements};
}
