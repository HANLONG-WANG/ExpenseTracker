import receipt from './formats/receipt-items.v1.schema.json';
import statement from './formats/statement.v1.schema.json';
export const TYPES={receipt:{name:'小票整理',kind:'yohaku.receipt-items',description:'从原图提取商品，保留原记账关联与版本。'},statement:{name:'银行流水',kind:'yohaku.statement',description:'把原始流水转换为统一格式，疑点与缺页明确保留。'},general:{name:'通用分析',kind:'general',description:'按任务要求整理资料，交回文件和分析说明。'}};
export const STATUS:Record<string,string>={uploading:'上传中',queued:'待分析',analyzing:'分析中',needs_info:'待补资料',review:'结果待检查',ready:'结果可领取',archived:'已归档'};
export function schemaErrors(schema:any,data:any,path='$'):string[]{
 const e:string[]=[]; const fail=(m:string)=>e.push(`${path}: ${m}`);
 if(schema.anyOf&&!schema.anyOf.some((s:any)=>schemaErrors(s,data,path).length===0))fail('字段类型或格式不符合协议');
 if('const' in schema&&data!==schema.const)fail('应为 '+schema.const);
 if(schema.enum&&!schema.enum.includes(data))fail('不支持的值');
 if(schema.type){const valid=schema.type==='null'?data===null:schema.type==='array'?Array.isArray(data):schema.type==='integer'?Number.isInteger(data):schema.type==='object'?data!==null&&typeof data==='object'&&!Array.isArray(data):typeof data===schema.type;if(!valid){fail('需要 '+schema.type);return e;}}
 if(typeof data==='string'){
  if(schema.pattern&&!new RegExp(schema.pattern).test(data))fail('格式无效');
  if(schema.format==='date'&&(!/^\d{4}-\d{2}-\d{2}$/.test(data)||!Number.isFinite(Date.parse(data))||new Date(data).toISOString().slice(0,10)!==data))fail('日期无效');
 }
 if(typeof data==='number'&&schema.minimum!==undefined&&data<schema.minimum)fail('低于最小值');
 if(Array.isArray(data)){if(schema.minItems&&data.length<schema.minItems)fail('内容不能为空');if(schema.items)data.forEach((v,i)=>e.push(...schemaErrors(schema.items,v,`${path}[${i}]`)));}
 if(data&&typeof data==='object'&&!Array.isArray(data)){
  for(const k of schema.required??[])if(!(k in data))e.push(`${path}.${k}: 缺少必填字段`);
  for(const [k,s] of Object.entries(schema.properties??{}))if(k in data)e.push(...schemaErrors(s,data[k],`${path}.${k}`));
 }
 for(const s of schema.allOf??[]){if(s.if){if(schemaErrors(s.if,data,path).length===0)e.push(...schemaErrors(s.then??{},data,path));}else e.push(...schemaErrors(s,data,path));}
 return e.slice(0,100);
}
export function validateResult(type:string,data:any,context:any){
 const errors:string[]=[];const warnings:string[]=[];
 if(type==='general')return {errors,warnings:['通用结果仅检查可读取性；请按任务要求人工检查内容。']};
 errors.push(...schemaErrors(type==='receipt'?receipt:statement,data));if(errors.length)return {errors,warnings};
 const unique=(values:any[],path:string)=>{if(new Set(values).size!==values.length)errors.push(path+': 标识重复');};
 const money=(amount:any,currency:string,path:string)=>{if(amount==null)return;const re=currency==='JPY'?/^(0|[1-9]\d*)$/:/^(0|[1-9]\d*)\.\d{2}$/;if(typeof amount!=='string'||!re.test(amount)){errors.push(path+': 金额精度错误');return;}if(BigInt(amount.replace('.',''))>9223372036854775807n)errors.push(path+': 金额超出范围');};
 if(type==='receipt'){
  const m=context.manifest;
  if(!m||!Array.isArray(m.records))errors.push('缺少原始小票 manifest.json，无法检查关联');
  else if(data.bundle_id!==m.bundle_id||data.book_id!==m.book_id)errors.push('账本或导出批次与原资料不符');
  unique(data.records.map((r:any)=>r.receipt_id),'$.records');
  for(const [i,r] of data.records.entries()){
   const p=`$.records[${i}]`;const source=m?.records?.find((s:any)=>s.receipt_id===r.receipt_id&&s.record_id===r.record_id);
   if(m&&!source)errors.push(p+': 不属于原导出批次');
   if(source){if(source.record_revision!==r.record_revision||source.receipt_revision!==r.receipt_revision)errors.push(p+': 导出修订号不符');if(source.original.currency!==r.currency)errors.push(p+': 原币币种不符');if(r.image_ids.some((id:string)=>!source.images.some((im:any)=>im.image_id===id)))errors.push(p+': 图片不属于原小票');}
   unique(r.image_ids,p+'.image_ids');unique(r.items.map((x:any)=>x.item_id),p+'.items');
   for(const [j,it] of r.items.entries()){unique(it.image_ids,`${p}.items[${j}].image_ids`);if(it.image_ids.some((id:string)=>!r.image_ids.includes(id)))errors.push(`${p}.items[${j}]: 商品图片越界`);money(it.line_amount,r.currency,`${p}.items[${j}].line_amount`);if(it.uncertain_fields.length)warnings.push(p+': 有不确定商品字段');}
   r.adjustments.forEach((a:any)=>money(a.amount,r.currency,p+'.adjustments'));money(r.receipt_total,r.currency,p+'.receipt_total');
   if(!r.complete||r.issues.length||r.items.some((x:any)=>x.line_amount==null))warnings.push(p+': 明细不完整或存在疑点');
   if(r.items.every((x:any)=>x.line_amount!=null)){
    const minor=(s:string)=>BigInt(s.replace('.',''));const total=r.items.reduce((a:bigint,it:any)=>a+minor(it.line_amount),0n)+r.adjustments.filter((a:any)=>!a.included_in_items).reduce((s:bigint,a:any)=>s+(a.type==='discount'?-1n:1n)*minor(a.amount),0n);
    if(source&&total!==minor(source.original.amount))warnings.push(p+': 商品合计与原消费金额不同');if(r.receipt_total!=null&&total!==minor(r.receipt_total))warnings.push(p+': 商品合计与票面总额不同');
   }
  }
 }else{
  const ids=context.identifiers;if(!ids)errors.push('缺少 yohaku.import-identifiers JSON，无法确认账本与账户');
  if(ids){if(data.book_id!==ids.book_id)errors.push('账本标识不符');const a=ids.accounts?.find((a:any)=>a.account_id===data.account_id);if(!a||a.currency!==data.account_currency)errors.push('账户标识或币种不符');}
  if(data.from>data.to)errors.push('日期范围顺序错误');unique(data.rows.map((r:any)=>r.row_id),'$.rows');
  data.rows.forEach((r:any,i:number)=>{const p=`$.rows[${i}]`;if(r.date<data.from||r.date>data.to)errors.push(p+': 日期超出范围');if(r[data.date_basis]&&r[data.date_basis]!==r.date)errors.push(p+': 日期语义冲突');if(r.settlement.currency!==data.account_currency)errors.push(p+': 账户币种不符');for(const k of ['settlement','original','fees'])if(r[k])money(r[k].amount,r[k].currency,p+'.'+k);if(r.exchange_rate!=null&&!(Number(r.exchange_rate)>0))errors.push(p+': 汇率必须大于零');if(r.occurred_at&&(!/(Z|[+-]\d{2}:\d{2})$/.test(r.occurred_at)||!Number.isFinite(Date.parse(r.occurred_at))))errors.push(p+': 需要带偏移的有效时间');if(r.time_zone){try{new Intl.DateTimeFormat('en',{timeZone:r.time_zone});}catch{errors.push(p+': 时区无效');}}});
  if(data.statement_info)for(const k of ['principal','fees','total'])if(data.statement_info[k])money(data.statement_info[k].amount,data.statement_info[k].currency,'$.statement_info.'+k);
  if(!data.complete||data.issues.length)warnings.push('流水资料不完整或存在转换疑点，不能据此断定漏记或多记');warnings.push('未提供应用账目时，此结果仅为流水转换，不代表已完成账务核对。');
 }
 return {errors:[...new Set(errors)].slice(0,100),warnings:[...new Set(warnings)].slice(0,100)};
}
