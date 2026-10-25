import {inflateRawSync} from 'node:zlib';
const enc=new TextEncoder();
const table=Array.from({length:256},(_,n)=>{for(let j=0;j<8;j++)n=n&1?0xedb88320^(n>>>1):n>>>1;return n>>>0;});
function header(size:number){const b=new Uint8Array(size);return {b,v:new DataView(b.buffer)};}
export function zipStream(entries:{name:string;body:()=>Promise<ReadableStream<Uint8Array>>}[]){
 async function* generate(){let offset=0;const central:Uint8Array[]=[];
  for(const entry of entries){const name=enc.encode(entry.name);const at=offset;const h=header(30);h.v.setUint32(0,0x04034b50,true);h.v.setUint16(4,20,true);h.v.setUint16(6,0x808,true);h.v.setUint16(26,name.length,true);offset+=h.b.length+name.length;yield h.b;yield name;let crc=0xffffffff,size=0;const reader=(await entry.body()).getReader();let finished=false;
   try{for(;;){const {done,value}=await reader.read();if(done){finished=true;break;}size+=value.length;if(size>0xffffffff)throw Error('ZIP 文件过大');for(const b of value)crc=table[(crc^b)&255]^(crc>>>8);offset+=value.length;yield value;}}finally{if(!finished)await reader.cancel();reader.releaseLock();}crc=(crc^0xffffffff)>>>0;
   const d=header(16);d.v.setUint32(0,0x08074b50,true);d.v.setUint32(4,crc,true);d.v.setUint32(8,size,true);d.v.setUint32(12,size,true);offset+=d.b.length;yield d.b;
   const z=header(46+name.length);z.v.setUint32(0,0x02014b50,true);z.v.setUint16(4,20,true);z.v.setUint16(6,20,true);z.v.setUint16(8,0x808,true);z.v.setUint32(16,crc,true);z.v.setUint32(20,size,true);z.v.setUint32(24,size,true);z.v.setUint16(28,name.length,true);z.v.setUint32(42,at,true);z.b.set(name,46);central.push(z.b);
  }const start=offset;for(const b of central){offset+=b.length;yield b;}const e=header(22);e.v.setUint32(0,0x06054b50,true);e.v.setUint16(8,entries.length,true);e.v.setUint16(10,entries.length,true);e.v.setUint32(12,offset-start,true);e.v.setUint32(16,start,true);yield e.b;
 }
 const iterator=generate();return new ReadableStream<Uint8Array>({async pull(c){try{const next=await iterator.next();if(next.done)c.close();else c.enqueue(next.value);}catch(e){c.error(e);}},async cancel(){await iterator.return(undefined);}});
}
export async function zipManifest(bucket:R2Bucket,key:string,size:number):Promise<any>{
 const read=async(offset:number,length:number)=>{const o=await bucket.get(key,{range:{offset,length}});if(!o)throw Error('原文件不存在');return new Uint8Array(await o.arrayBuffer());};
 const tail=await read(Math.max(0,size-65557),Math.min(size,65557));const v=new DataView(tail.buffer);let end=-1;for(let i=tail.length-22;i>=0;i--)if(v.getUint32(i,true)===0x06054b50){end=i;break;}if(end<0)throw Error('ZIP 目录无效');const length=v.getUint32(end+12,true),offset=v.getUint32(end+16,true);if(length>2_000_000||offset+length>size)throw Error('ZIP 目录过大或越界');
 const dir=await read(offset,length);const dv=new DataView(dir.buffer);for(let p=0;p+46<=dir.length;){if(dv.getUint32(p,true)!==0x02014b50)throw Error('ZIP 目录损坏');const n=dv.getUint16(p+28,true),extra=dv.getUint16(p+30,true),comment=dv.getUint16(p+32,true);const name=new TextDecoder().decode(dir.slice(p+46,p+46+n));if(name==='manifest.json'){
  const compressed=dv.getUint32(p+20,true),plain=dv.getUint32(p+24,true),local=dv.getUint32(p+42,true),method=dv.getUint16(p+10,true);if(compressed>2_000_000||plain>2_000_000||(dv.getUint16(p+8,true)&1))throw Error('manifest 太大或已加密');const lh=await read(local,30);const lv=new DataView(lh.buffer);if(lv.getUint32(0,true)!==0x04034b50)throw Error('ZIP 文件头无效');const data=await read(local+30+lv.getUint16(26,true)+lv.getUint16(28,true),compressed);const output=method===0?data:method===8?inflateRawSync(data,{maxOutputLength:2_000_000}):null;if(!output)throw Error('ZIP 压缩格式不支持');return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(output));
 }p+=46+n+extra+comment;}return null;
}
