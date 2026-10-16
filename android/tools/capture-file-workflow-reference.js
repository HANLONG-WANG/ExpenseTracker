async page => {
  const root='android/verification/phase-d-frozen-reference';
  const measurements=[];
  await page.setViewportSize({width:390,height:844});
  await page.emulateMedia({reducedMotion:'reduce'});
  for(const theme of ['magazine','journal','objects'])for(const language of ['zh','ja']) {
    await page.goto(`http://127.0.0.1:4184/design/app.html?theme=${theme}`);
    await page.evaluate(language=>{
      book().language=language;
      account("pp").name="銀行";account("pp").ja="銀行";
      const canvas=document.createElement('canvas');canvas.width=100;canvas.height=180;
      const image=(id,name,color)=>{const context=canvas.getContext('2d');context.fillStyle=color;context.fillRect(0,0,100,180);return{id,name,type:'image/png',size:1000,url:canvas.toDataURL()};};
      const group={id:'00000000-0000-4000-8000-000000000008',status:'pending',revision:1,images:[image('image-1','original-1.png','#fff'),image('image-2','original-2.png','#ff0')],items:[]};
      const record={id:'00000000-0000-4000-8000-000000000007',date:'2026-10-14',amount:1000,category:'food',merchant:'小票店',account:'pp',activity:'',type:'expense',note:'',payment:'once',count:1,currency:'JPY',accountAmount:1000,settlementState:'confirmed',attachments:[],receipts:[group]};
      book().records=[record];window.phaseDVisualRecord=record;
      window.phaseDVisualSource={id:'phase-d-statement',account:'pp',from:'2026-10-01',to:'2026-10-31',complete:true,issues:[],rows:['1000','1000','950','500'].map((amount,index)=>({id:`row-${index}`,date:'2026-10-14',direction:'out',amount:Number(amount),summary:'原始摘要'}))};
      render();
    },language);
    for(const target of ['statement-import','statement-preview','reconcile','receipt-import','receipt-export','receipt-review','receipt-replace','receipt-editor']) {
      await page.evaluate(target=>{
        closeSheet();
        const record=window.phaseDVisualRecord,group=record.receipts[0];
        group.status='pending';group.items=[];
        if(target==='statement-import'||target==='statement-preview'){
          navigate('import','',false);
          document.querySelectorAll('[data-action="preview-sample"]').forEach(e=>e.remove());
          if(target==='statement-preview')previewImport(window.phaseDVisualSource);
        }else if(target==='reconcile'){
          book().records=[1000,1000,1200,500].map((amount,index)=>({...record,id:`ledger-${index}`,amount,accountAmount:amount,receipts:[]}));
          book().matches=[{source:window.phaseDVisualSource.id,row:window.phaseDVisualSource.rows[2].id,record:'ledger-2'}];
          book().sources=[window.phaseDVisualSource];navigate('reconcile',window.phaseDVisualSource.id,false);
          document.querySelectorAll('[data-action="load-sample"],[data-action="load-transfer-sample"]').forEach(e=>e.remove());
        }else{
          book().records=[record];book().matches=[];navigate('receipt-inbox','',false);
          const result={kind:'yohaku.receipt-items',version:1,book_id:book().id,records:[{record_id:record.id,receipt_id:group.id,currency:'JPY',items:[{name:'商品',quantity:1,unit:'個',unit_price:950,total:950}],receipt_total:950}]};
          if(target==='receipt-import'){
            receiptImportPanel(record.id);
            document.querySelectorAll('[data-action="receipt-preview-sample"],[data-action="receipt-preview-difference"]').forEach(e=>e.remove());
          }else if(target==='receipt-export'){
            receiptSelection.clear();receiptSelection.add(group.id);exportReceiptPreview();
            document.querySelectorAll('#sheet-content .disclaimer,[data-action="receipt-mark-exported"]').forEach(e=>e.remove());
            const save=document.querySelector('.download-package');if(save){save.removeAttribute('href');save.textContent=t('选择保存目录并导出','保存フォルダを選んで書出し');}
          }else if(target==='receipt-review')previewReceiptResult(result);
          else if(target==='receipt-replace'){
            group.status='ready';group.items=[{name:'手工商品',quantity:1,unit:'個',unit_price:950,total:950}];
            previewReceiptResult(result);captureAction('receipt-confirm-import',{dataset:{}});
          }else{
            productEditor={group:group.id,items:result.records[0].items.map(item=>({...item}))};renderProductEditor();
          }
        }
        document.querySelectorAll('.status').forEach(e=>e.style.visibility='hidden');
        if(target==='statement-preview')document.querySelector('#import-preview').scrollIntoView({block:'start',behavior:'instant'});
        else window.scrollTo({top:0,behavior:'instant'});
        const sheet=document.querySelector('.sheet');if(sheet)sheet.scrollTop=0;
      },target);
      await page.addStyleTag({content:'*,*::before,*::after{animation:none!important;transition:none!important;caret-color:transparent!important}'});
      await page.evaluate(async()=>{await document.fonts.ready;await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});
      await page.screenshot({path:`${root}/${theme}-${language}-${target}.png`});
      const nodes=await page.evaluate(()=>[...document.querySelectorAll('.page-title,.sheet-top,#sheet-content,.receipt-import-cover,.capture-upload,.capture-format-link,.receipt-import-boundary,.receipt-export-cover,.package-tree,.receipt-result-summary,.receipt-result-card,.receipt-result-record,.receipt-items,.receipt-item,.item-editor-row,.item-editor-fields,.full-button,.audit-panel')].filter(e=>e.getClientRects().length&&e.closest('[hidden]')===null).map(e=>{const css=getComputedStyle(e);return{selector:e.className||e.id,text:e.textContent.slice(0,80),rect:e.getBoundingClientRect().toJSON(),font:css.fontFamily,fontSize:css.fontSize,lineHeight:css.lineHeight,padding:css.padding,margin:css.margin,border:css.border,radius:css.borderRadius,display:css.display,columns:css.gridTemplateColumns};}));
      measurements.push({theme,language,target,width:390,height:844,nodes});
    }
  }
  return {source:'Frozen v55: verified archive extracted unchanged; runtime trusted fixture; explicit demo controls removed; ZIP sample download relabelled to real save action only.',screens:measurements.length,measurements};
}
