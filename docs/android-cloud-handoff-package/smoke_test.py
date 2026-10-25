"""Explicitly invoked developer smoke test. Creates a real task and uploads selected files."""
import argparse,hashlib,json,mimetypes,os,sys,uuid
from pathlib import Path
from urllib import request,parse,error
ORIGIN='https://yohaku-analysis-desk.wanghanlong2003422.chatgpt.site'
class NoRedirect(request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl): return None
opener=request.build_opener(NoRedirect)
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()
def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--config',type=Path,required=True);ap.add_argument('--state',type=Path,required=True);ap.add_argument('--type',choices=['receipt','statement','general']);ap.add_argument('--title');ap.add_argument('--files',type=Path,nargs='*');ap.add_argument('--download',type=Path);a=ap.parse_args()
 cfg=json.loads(a.config.read_text());base=cfg.get('baseUrl','').rstrip('/')
 if base!=ORIGIN or cfg.get('apiVersion')!=1:raise ValueError('配置需指向交接文档的HTTPS站点且apiVersion=1')
 auth={'OAI-Sites-Authorization':'Bearer '+cfg['platformAccessToken'],'Authorization':'Bearer '+cfg['deviceToken']}
 def call(path,method='GET',data=None,extra=None):
  h={**auth,**(extra or {})}
  if isinstance(data,dict):data=json.dumps(data,ensure_ascii=False,separators=(',',':')).encode();h['Content-Type']='application/json'
  req=request.Request(base+path,data=data,headers=h,method=method)
  try:return opener.open(req,timeout=180)
  except error.HTTPError as e:
   body=e.read(800)
   try:msg=json.loads(body).get('error','请求失败')
   except Exception:msg='网关返回非JSON错误；检查登录、平台凭证或服务状态'
   raise RuntimeError(f'HTTP {e.code}: {msg}') from None
 def gettask(taskid):
  with call('/api/v1/tasks/'+taskid) as r:return json.load(r)
 def save(s):a.state.parent.mkdir(parents=True,exist_ok=True);temp=a.state.with_suffix(a.state.suffix+'.tmp');temp.write_text(json.dumps(s,ensure_ascii=False,indent=2));temp.replace(a.state)
 with call('/api/v1/types') as r:json.load(r)
 s=json.loads(a.state.read_text()) if a.state.exists() else {}
 if a.download:
  if not s.get('taskId'):raise ValueError('状态文件缺少taskId')
  t=gettask(s['taskId']);rs=[r for r in t['results'] if r['state']=='ready' and not r['stale'] and r['inputRevision']==t['inputRevision']]
  if t['status']!='ready' or len(rs)!=1:raise ValueError('当前无唯一可领取结果')
  result=rs[0];meta=next(f for f in t['files'] if f['id']==result['fileId']);a.download.parent.mkdir(parents=True,exist_ok=True);temp=a.download.with_suffix(a.download.suffix+'.partial')
  with call('/api/v1/files/'+result['fileId']) as r,temp.open('wb') as out:
   expected=r.headers.get('X-Content-SHA256');length=r.headers.get('Content-Length');h=hashlib.sha256();n=0
   for chunk in iter(lambda:r.read(65536),b''):out.write(chunk);h.update(chunk);n+=len(chunk)
  if expected!=meta['sha256'] or h.hexdigest()!=expected or n!=meta['size'] or (length and n!=int(length)):
   temp.unlink(missing_ok=True);raise ValueError('结果大小或SHA-256不一致')
  temp.replace(a.download);print(json.dumps({'taskId':t['id'],'resultId':result['id'],'downloaded':True,'imported':False},ensure_ascii=False));return
 files=a.files or []
 if not files:raise ValueError('上传模式需要 --files')
 if not s:
  if not a.type or not a.title:raise ValueError('首次创建需 --type 和 --title')
  s={'idempotencyKey':str(uuid.uuid4()),'payload':{'type':a.type,'title':a.title,'instructions':'开发联调测试，请按v1协议返回结果。','context':{}},'sources':[]}
  for f in files:
   size=f.stat().st_size
   if not 0<size<100_000_000:raise ValueError('每份源文件需大于0且严格小于100MB')
   s['sources'].append({'name':f.name,'size':size,'sha256':digest(f)})
  save(s)
 current=[{'name':f.name,'size':f.stat().st_size,'sha256':digest(f)} for f in files]
 if current!=s['sources']:raise ValueError('重试源文件已变化；不能用同状态文件上传不同快照')
 if not s.get('taskId'):
  with call('/api/v1/tasks','POST',s['payload'],{'Idempotency-Key':s['idempotencyKey']}) as r:t=json.load(r)
  s['taskId']=t['id'];save(s)
 t=gettask(s['taskId'])
 if t['status'] not in ['uploading','needs_info']:
  print(json.dumps({'taskId':t['id'],'status':t['status'],'message':'已有任务，无需重复上传或提交'},ensure_ascii=False));return
 for f,info in zip(files,s['sources']):
  t=gettask(s['taskId'])
  if any(x['role']=='input' and x['name']==info['name'] and x['sha256']==info['sha256'] for x in t['files']):continue
  with f.open('rb') as body,call('/api/v1/tasks/'+s['taskId']+'/files?name='+parse.quote(f.name,safe=''),'POST',body,{'Content-Length':str(info['size']),'Content-Type':mimetypes.guess_type(f.name)[0] or 'application/octet-stream','X-Content-SHA256':info['sha256']}) as r:json.load(r)
 with call('/api/v1/tasks/'+s['taskId']+'/submit','POST') as r:t=json.load(r)
 print(json.dumps({'taskId':t['id'],'status':t['status'],'message':'已提交，等待人工分析'},ensure_ascii=False))
if __name__=='__main__':
 try:main()
 except Exception as e:print(str(e),file=sys.stderr);sys.exit(1)
