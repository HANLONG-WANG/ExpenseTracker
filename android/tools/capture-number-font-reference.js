async page => {
 await page.setViewportSize({width:1000,height:950});
 await page.goto('http://127.0.0.1:4173/design/app.html?theme=magazine');
 const samples=[
  {text:'¥86,180',size:49,spacing:-2},
  {text:'¥650',size:45,spacing:0},
  {text:'¥9,700',size:23,spacing:-1},
  {text:'CN¥12,345.67',size:21,spacing:0},
  {text:'US$1,280.50',size:21,spacing:0},
  {text:'−¥8,600',size:21,spacing:0},
  {text:'0123456789',size:30,spacing:0},
  {text:'¥999,999,999',size:23,spacing:-1}
 ];
 await page.addStyleTag({content:"@font-face{font-family:YohakuNumbers;src:url('/android/app/src/main/res/font/editorial_number.ttf?line-metrics=1')}"});
 await page.evaluate(async(samples)=>{
  await document.fonts.load('30px YohakuNumbers');await document.fonts.ready;
  document.body.className='magazine';
  document.body.innerHTML='<main style="padding:24px;width:952px"><h1 style="font-size:22px">杂志数字字形对照 · Georgia / YohakuNumbers</h1><p>左侧：冻结HTML实际字体；右侧：Gelasio校准行度量后的安卓字体。相同字号和字距。</p><div id="font-comparison"></div></main>';
  const target=document.getElementById('font-comparison');
  for(const sample of samples){
   const row=document.createElement('div');row.style.cssText='display:grid;grid-template-columns:1fr 1fr;border-top:1px solid #d2dacc;padding:12px 0;align-items:baseline';
   for(const family of ['Georgia','YohakuNumbers']){
    const cell=document.createElement('div');const label=document.createElement('small');label.textContent=`${family} / ${sample.size}px / ${sample.spacing}px`;label.style.cssText='display:block;font:11px sans-serif;color:#788577';
    const text=document.createElement('span');text.textContent=sample.text;text.dataset.family=family;text.style.cssText=`font-family:${family};font-size:${sample.size}px;letter-spacing:${sample.spacing}px;line-height:1.3;display:inline-block`;
    const marker=document.createElement('i');marker.style.cssText='display:inline-block;width:0;height:0;vertical-align:baseline';text.append(marker);
    cell.append(label,text);row.append(cell);
   }
   target.append(row);
  }
 },samples);
 await page.screenshot({path:'android/verification/reference/magazine-number-font-comparison.png',fullPage:true});
 const rows=await page.evaluate(()=>[...document.querySelectorAll('[data-family]')].map(e=>{
  const s=getComputedStyle(e),canvas=document.createElement('canvas'),context=canvas.getContext('2d');context.font=`${s.fontSize} ${s.fontFamily}`;
  const m=context.measureText(e.textContent);
  return {family:e.dataset.family,text:e.textContent,size:parseFloat(s.fontSize),spacing:parseFloat(s.letterSpacing),width:e.getBoundingClientRect().width,height:e.getBoundingClientRect().height,baseline:e.querySelector('i').getBoundingClientRect().top-e.getBoundingClientRect().top,inkAscent:m.actualBoundingBoxAscent,inkDescent:m.actualBoundingBoxDescent};
 }));
 const cdp=await page.context().newCDPSession(page);await cdp.send('DOM.enable');await cdp.send('CSS.enable');
 const {root}=await cdp.send('DOM.getDocument');
 const {nodeIds}=await cdp.send('DOM.querySelectorAll',{nodeId:root.nodeId,selector:'[data-family]'});
 for(let i=0;i<nodeIds.length;i++)rows[i].fonts=(await cdp.send('CSS.getPlatformFontsForNode',{nodeId:nodeIds[i]})).fonts;
 await cdp.detach();return rows;
}
