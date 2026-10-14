async page => {
 const rows=[];
 for(const width of [390,320])for(const theme of ['magazine','journal','objects']){
  await page.setViewportSize({width,height:844});
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(()=>{book().language='zh';render();openSheet('切换主题，保留这笔输入',`<div class="audit-theme-options"><button class="audit-link">现代生活杂志</button><button class="audit-link">纸质手账</button><button class="audit-link">日常小物</button></div>`);});
  await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important}'});
  await page.evaluate(()=>document.fonts.ready);
  await page.screenshot({path:`android/verification/reference/sheet-${theme}-${width}.png`});
  rows.push(await page.evaluate(({theme,width})=>({theme,width,nodes:[...document.querySelectorAll('.sheet,.sheet-grip,.sheet-top,.sheet-top h2,.sheet-edition,.sheet-note-caption,.sheet-receipt-header,.sheet-colophon,#close-sheet,.sheet-notebook-binding,.audit-theme-options,.audit-theme-options .audit-link')].map(e=>{const c=getComputedStyle(e);return{selector:e.className||e.id,rect:e.getBoundingClientRect().toJSON(),fontSize:c.fontSize,lineHeight:c.lineHeight,padding:c.padding,margin:c.margin,border:c.border,fontWeight:c.fontWeight,borderRadius:c.borderRadius};})}),{theme,width}));
 }
 return rows;
}
