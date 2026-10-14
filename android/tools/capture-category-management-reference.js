async page => {
 const rows=[];
 await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(()=>{book().language='zh';book().categories=[{id:'food',name:['餐饮','餐饮'],icon:'wallet',budget:0},{id:'coffee',parent:'food',name:['咖啡','咖啡'],icon:'wallet',budget:0}];navigate('categories');});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  await page.screenshot({path:`android/verification/reference/category-management-${theme}.png`,fullPage:true});
  rows.push(await page.evaluate(theme=>({theme,kind:'management',nodes:[...document.querySelectorAll('.hierarchy-manage-group,.hierarchy-manage-parent,.hierarchy-manage-children')].map(e=>({selector:e.className,rect:e.getBoundingClientRect().toJSON(),padding:getComputedStyle(e).padding}))}),theme));
  await page.evaluate(()=>categoryEditor('coffee'));
  await page.screenshot({path:`android/verification/reference/category-editor-${theme}.png`});
  rows.push(await page.evaluate(theme=>({theme,kind:'editor',nodes:[...document.querySelectorAll('.hierarchy-form-note,#hierarchy-category-form .field')].map(e=>{const c=getComputedStyle(e);return{selector:e.className,rect:e.getBoundingClientRect().toJSON(),fontSize:c.fontSize,lineHeight:c.lineHeight,margin:c.margin};})}),theme));
 }
 return rows;
}
