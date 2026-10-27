const TODAY = '2026-10-12';
const CURRENT_MONTH = '2026-10';
const categoriesTemplate = () => [
 {id:'food',name:['餐饮','食費'],icon:'tools-kitchen-2',budget:50000},
 {id:'shopping',name:['购物','買い物'],icon:'shopping-bag',budget:40000},
 {id:'transport',name:['交通','交通費'],icon:'train',budget:20000},
 {id:'housing',name:['居住与账单','住まい・請求'],icon:'home',budget:50000},
 {id:'other',name:['其他与手续费','その他・手数料'],icon:null,budget:20000}
];
const sampleAccounts = () => [
 {id:'cash',name:'随身现金',ja:'手持ちの現金',type:'cash',balance:8600},
 {id:'bank-m',name:'三菱银行',ja:'三菱銀行',type:'bank',balance:18400},
 {id:'bank-s',name:'三井住友银行',ja:'三井住友銀行',type:'bank',balance:48000},
 {id:'ic',name:'Suica',type:'ic',balance:2400},
 ...[['sm','三井住友',92000,32000,'2026-10-26'],['pp','PayPay',48600,18600,'2026-10-27'],['m','三菱',20000,10000,'2026-10-27'],['sa','Saison',30000,24000,'2026-11-04'],['j','JCB',24000,16000,'2026-11-10']].map(([id,name,debt,next,date])=>({id,name,type:'credit',balance:debt,limit:300000,next,date,bank:'bank-m',deadline:'2026-10-18T18:00',confirmed:id==='sm',rules:null})),
 {id:'loan',name:'网贷 · 自定义',ja:'ローン・カスタム',type:'loan',balance:50000,next:5000,date:'2026-11-01',bank:'bank-s',rules:null}
];
function seedBook(id,name,budget=180000) {
 const records = [
 ['f1','2026-10-01',5200,'food','ファミリーマート','pp'],['f2','2026-10-03',8500,'food','ライフ','bank-s'],['f3','2026-10-05',7200,'food','ライフ','bank-s'],['f4','2026-10-09',7000,'food','焼肉きんぐ','sm'],['f5','2026-10-11',6200,'food','京都のごはん','cash','kyoto'],['f6',TODAY,680,'food','喫茶と余白','pp'],['f7',TODAY,2400,'food','和食さと','sm'],['f8','2026-10-04',1440,'food','ファミリーマート','pp'],
 ['s1','2026-10-06',16500,'shopping','無印良品','sm'],['s2','2026-10-08',4500,'shopping','ユニクロ','pp'],['s3','2026-10-11',3500,'shopping','京都のおみやげ','cash','kyoto'],
 ['t1','2026-10-10',8760,'transport','JR 京都往復','sm','kyoto'],['t2','2026-10-02',3760,'transport','JR 定期外','ic'],['t3',TODAY,180,'transport','JR 山手線','cash'],
 ['o1','2026-10-04',1200,'other','分期手续费','sm'],['h1','2026-10-05',6800,'housing','インターネット','bank-m'],['o2','2026-10-07',10000,'other','クリニック','bank-s'],
 ['old','2026-09-20',10000,'shopping','無印良品','sm'],['oldfood','2026-09-06',18000,'food','ライフ','bank-s'],['oldshop','2026-09-10',26000,'shopping','ヨドバシカメラ','sm'],['oldtrain','2026-09-08',4200,'transport','JR','ic']
 ].map(([id,date,amount,category,merchant,account,activity=''])=>({id,date,amount,category,merchant,account,activity,type:'expense',note:'',payment:id==='s1'?'split':id==='o1'?'fee':'once',count:id==='s1'?3:1}));
 records.push({id:'refund-seed',type:'refund',date:'2026-10-08',amount:6000,original:'old',account:'sm',category:'shopping',activity:'',merchant:'無印良品',confirmed:true});
 records.push({id:'transfer-seed',type:'transfer',date:'2026-10-08',amount:20000,account:'bank-s',to:'bank-m',merchant:'银行间转账',category:'',activity:''});
 return {id,name,ja:'日々の暮らし',budget,categories:categoriesTemplate(),accounts:sampleAccounts(),records,
 activities:[{id:'kyoto',name:'京都，慢慢走',ja:'京都を、ゆっくり',start:'2026-10-10',end:'2026-10-11',budget:25000,friends:['kobayashi']},{id:'autumn',name:'秋日跨月旅行',ja:'秋の旅',start:'2026-10-30',end:'2026-11-02',budget:40000,friends:['kobayashi','chen']}],
 friends:[{id:'kobayashi',name:'小林'},{id:'chen',name:'小陈',ja:'陳'}],
 treats:[{id:'treat1',date:'2026-10-01',payer:'me',amount:6400,alloc:{kobayashi:6400},merchant:'焼肉',activity:''},{id:'treat2',date:'2026-10-09',payer:'me',amount:6000,alloc:{kobayashi:3000,chen:3000},merchant:'週末のごはん',activity:''},{id:'treat3',date:TODAY,payer:'me',amount:2400,alloc:{kobayashi:2400},merchant:'和食さと',activity:'',record:'f7'},{id:'treat4',date:'2026-10-11',payer:'kobayashi',amount:9400,alloc:{me:9400},merchant:'京都の夕食',activity:'kyoto'}],
 recurring:[{id:'rent',name:'房租',ja:'家賃',amount:36000,date:'2026-10-20',category:'housing',account:'bank-m',estimated:false,frequency:'monthly',done:false},{id:'utility',name:'水电',ja:'水道光熱費',amount:3000,date:'2026-10-22',category:'housing',account:'bank-m',estimated:true,frequency:'monthly',done:false},{id:'mobile',name:'手机费',ja:'携帯料金',amount:1000,date:'2026-10-25',category:'housing',account:'bank-m',estimated:false,frequency:'monthly',done:false}],
 expected:[{id:'income-next',name:'兼职预计收入',ja:'アルバイト予定',amount:18000,date:'2026-10-23',account:'bank-m'}],activeActivity:'',
 merchants:[{id:'cafe',name:'喫茶と余白',place:'駅前 · 35m',ja:'駅前・35m'},{id:'family',name:'ファミリーマート',place:'交差路口 · 80m',ja:'交差点・80m'}],sources:[],matches:[],theme:null,language:'zh'};
}
const dailySeed=seedBook('daily','日常生活');
const hobbySeed=seedBook('hobby','兴趣生活',20000);
hobbySeed.ja='趣味の時間';hobbySeed.accounts=[{id:'hobby-cash',name:'兴趣现金',ja:'趣味用の現金',type:'cash',balance:12000}];hobbySeed.records=[{id:'hobby1',date:'2026-10-10',amount:3500,category:'shopping',merchant:'画材の店',account:'hobby-cash',type:'expense',activity:'',payment:'once'}];hobbySeed.activities=[];hobbySeed.friends=[];hobbySeed.treats=[];hobbySeed.recurring=[];hobbySeed.expected=[];hobbySeed.merchants=[];hobbySeed.categories.forEach(c=>c.budget=c.id==='shopping'?15000:1000);
const books = [dailySeed,hobbySeed];
let currentBook='daily';
const book = () => books.find(b=>b.id===currentBook);
const account = id => book().accounts.find(a=>a.id===id);
const category = id => book().categories.find(c=>c.id===id);
const uid = prefix => `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2,6)}`;
const isDebt = a => ['credit','loan'].includes(a.type);
function expenseNet(r) {return r.amount-book().records.filter(x=>x.type==='refund'&&x.confirmed&&x.original===r.id).reduce((s,x)=>s+x.amount,0);}
function expenses(month=CURRENT_MONTH,activity='') {return book().records.filter(r=>r.type==='expense'&&r.date.startsWith(month)&&(!activity||r.activity===activity));}
const spending = (month=CURRENT_MONTH,activity='')=>expenses(month,activity).filter(r=>!book().activities.find(a=>a.id===r.activity)?.excludeMonthly).reduce((s,r)=>s+expenseNet(r),0);
let reserve = () => book().recurring.filter(r=>!r.done&&r.date.startsWith(CURRENT_MONTH)).reduce((s,r)=>s+r.amount,0);
const activitySpent = id => book().records.filter(r=>r.type==='expense'&&r.activity===id).reduce((s,r)=>s+expenseNet(r),0);
const friendBalance = id => book().treats.reduce((s,r)=>s+(r.payer==='me'?(r.alloc[id]||0):r.payer===id?-(r.alloc.me||0):0),0);
function balanceChange(r,undo=false){let n=undo?-1:1,a=account(r.account),amount=r.accountAmount??r.amount;if(!a)return;if(r.type==='expense')a.balance+=n*(isDebt(a)?amount:-amount);if(r.type==='income')a.balance+=n*amount;if(r.type==='refund'&&r.confirmed)a.balance+=n*(isDebt(a)?-amount:amount);if(r.type==='transfer'&&r.transferState!=='planned'){a.balance-=n*amount;if(r.transferState!=='inTransit')account(r.to).balance+=n*(r.toAmount??amount);}if(r.type==='repayment'){a.balance-=n*amount;account(r.to).balance-=n*(r.principal??amount);}}
function events(){return [...book().recurring.filter(r=>!r.done).map(r=>({...r,kind:'bill',out:r.amount})),...book().accounts.filter(a=>isDebt(a)&&a.next>0).map(a=>({id:a.id,name:a.name,date:a.date,account:a.bank,kind:'repayment',out:a.next,confirmed:a.confirmed})),...book().expected.map(r=>({...r,kind:'income',out:-r.amount,estimated:true}))].sort((a,b)=>a.date.localeCompare(b.date));}
function cashTimeline(){const balances=Object.fromEntries(book().accounts.filter(a=>!isDebt(a)).map(a=>[a.id,a.balance]));return events().map(e=>{const before=balances[e.account]??0;balances[e.account]=before-e.out;return {...e,before,after:balances[e.account]};});}
function sampleSources(){return {version:1,book:book().id,account:'pp',from:'2026-10-01',to:'2026-10-12',complete:true,rows:[{id:'pp1',date:TODAY,amount:680,direction:'out',summary:'喫茶と余白'},{id:'pp2',date:TODAY,amount:1280,direction:'out',summary:'ファミリーマート'},{id:'pp3',date:'2026-10-04',amount:1440,direction:'out',summary:'ファミリーマート'},{id:'pp4',date:'2026-10-01',amount:5200,direction:'out',summary:'ファミリーマート'}]};}
function recordsForSource(source){return book().records.flatMap(r=>{if(r.transferState==='planned'||r.includedInTransfer)return [];let sides=[];if(r.account===source.account){const direction=['income','refund'].includes(r.type)?'in':'out';if(r.type==='refund'&&!r.confirmed)return [];sides.push({record:r,side:'from',direction,amount:r.accountAmount??r.amount,date:r.date});}if(['transfer','repayment'].includes(r.type)&&r.to===source.account&&r.transferState!=='inTransit')sides.push({record:r,side:'to',direction:'in',amount:r.toAmount??r.accountAmount??r.amount,date:r.arrivalDate||r.date});return sides;}).filter(x=>x.date>=source.from&&x.date<=source.to);}

function reconcile(source){const candidates=recordsForSource(source),used=new Set();const rows=source.rows.map(row=>{const manual=book().matches.find(x=>x.source===source.id&&x.row===row.id);let match=manual?candidates.find(x=>x.record.id===manual.record):candidates.find(x=>!used.has(`${x.record.id}-${x.side}`)&&x.record.settlementState!=='pending'&&x.date===row.date&&x.amount===row.amount&&x.direction===row.direction);if(match)used.add(`${match.record.id}-${match.side}`);return {...row,match};});return {rows,unmatched:candidates.filter(x=>!used.has(`${x.record.id}-${x.side}`))};}
