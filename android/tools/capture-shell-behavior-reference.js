async page=>{
 const shells=[],menus=[];await page.setViewportSize({width:390,height:844});
 for(const theme of ['magazine','journal','objects']){
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);await page.evaluate(()=>navigate('accounts','',false));await page.evaluate(()=>document.fonts.ready);
  await page.addStyleTag({content:'html{scroll-behavior:auto!important}'});
  shells.push(await page.evaluate(theme=>{
   const header=document.querySelector('.app-header'),nav=document.querySelector('.bottom-nav');
   const read=()=>({headerTop:header.getBoundingClientRect().top,navTop:nav.getBoundingClientRect().top,scroll:window.scrollY});
   const before=read();window.scrollTo(0,300);return{theme,position:getComputedStyle(header).position,before,after:read()};
  },theme));
  await page.goto(`http://127.0.0.1:4173/design/app.html?theme=${theme}`);
  await page.evaluate(()=>{setupState.step=2;navigate('onboarding');beginAccountEditor('',true,false);accountDraft.values.type='cash';renderAccountEditor();});
  menus.push(await page.evaluate(theme=>{
   const triggers=[...document.querySelectorAll('#experience-account-form .select-trigger')];
   const read=()=>triggers.map(e=>({open:e.getAttribute('aria-expanded'),arrow:e.querySelector('b').textContent}));
   const before=read();triggers[0].click();const one=read();triggers[1].click();const two=read();document.querySelector('.sheet-top h2').click();return{theme,before,one,two,outside:read()};
  },theme));
 }
 return {shells,menus};
}
