async page => {
 const rows=[];
 for(const width of [390,320])for(const theme of ['magazine','journal','objects']){
  await page.setViewportSize({width,height:844});
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(()=>{setupState.step=1;navigate('onboarding');});
  await page.evaluate(()=>document.fonts.ready);
  rows.push(await page.evaluate(({width,theme})=>({width,theme,nodes:[...document.querySelectorAll('.setup-steps,.setup-steps>span,.setup-steps b,.setup-steps small')].map(e=>{const c=getComputedStyle(e);return{selector:e.tagName+':'+e.className,rect:e.getBoundingClientRect().toJSON(),offsetHeight:e.offsetHeight,fontSize:c.fontSize,lineHeight:c.lineHeight,padding:c.padding,gap:c.gap,color:c.color};})}),{width,theme}));
 }
 return rows;
}
