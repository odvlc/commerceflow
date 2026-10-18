import http from 'k6/http';
import {check,sleep} from 'k6';
import {Trend,Rate} from 'k6/metrics';
const base=__ENV.BASE_URL||'http://localhost:8080',ollama=__ENV.OLLAMA_BASE_URL||'http://127.0.0.1:11434';
for(const address of [base,ollama])if(!/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(address))throw new Error('Only loopback development targets are allowed');
const scenario=__ENV.AI_SCENARIO||'commerce';
if(!['commerce','retrieval','generation','tool','investigation','mixed'].includes(scenario))throw new Error('Unknown AI_SCENARIO');
const duration=__ENV.AI_DURATION||'30s';
export const options={scenarios:scenario==='mixed'?{commerce:{executor:'constant-vus',vus:1,duration,exec:'commerce'},generation:{executor:'constant-vus',vus:1,duration,exec:'generation'}}:{selected:{executor:'shared-iterations',vus:1,iterations:Number(__ENV.AI_ITERATIONS||10),maxDuration:'30m'}},systemTags:['status','method','name','scenario','error_code','check'],thresholds:scenario==='investigation'?{copilot_completed:['rate==1']}:{checks:['rate==1']},summaryTrendStats:['avg','med','p(95)','p(99)','max']};
const catalogue=new Trend('catalog_request_millis',true),generated=new Trend('generation_request_millis',true),semantic=new Rate('retrieval_semantic');
const latency=new Trend('copilot_run_millis',true),completed=new Rate('copilot_completed');
function headers(){if(!__ENV.AI_LOAD_TOKEN)throw new Error('Authenticated scenarios require AI_LOAD_TOKEN in the process environment');return {Authorization:`Bearer ${__ENV.AI_LOAD_TOKEN}`,'Content-Type':'application/json','X-Correlation-ID':`load-${__VU}-${__ITER}`};}
export function commerce(){const response=http.get(`${base}/api/v1/products?size=10`,{tags:{name:'catalog baseline'}});catalogue.add(response.timings.duration);check(response,{'catalog available':r=>r.status===200});sleep(.1);}
export function generation(){const response=http.post(`${ollama}/api/chat`,JSON.stringify({model:'llama3.1:8b',stream:false,keep_alive:'5m',messages:[{role:'user',content:'Explain in two sentences what an idempotency key does.'}],options:{temperature:0,num_ctx:4096,num_predict:128}}),{headers:{'Content-Type':'application/json'},timeout:'60s',tags:{name:'local Llama generation'}});generated.add(response.timings.duration);check(response,{'local generation acknowledged':r=>r.status===200&&!!r.json('message.content')});}
export default function(){
 if(scenario==='commerce')return commerce();if(scenario==='generation')return generation();
 if(scenario==='tool'){const response=http.get(`${base}/api/v1/cart`,{headers:headers(),tags:{name:'authorized cart read tool target'}});check(response,{'cart tool target available':r=>r.status===200&&Array.isArray(r.json('items'))});return;}
 if(scenario==='retrieval'){const response=http.get(`${base}/api/v1/ai/knowledge/search?query=demo%20return%20window`,{headers:headers(),tags:{name:'authorized hybrid retrieval'}});semantic.add(response.status===200&&response.json('semantic')===true);check(response,{'evidence retrieved':r=>r.status===200&&r.json('items').length>0});return;}
 const key=`load-${Date.now()}-${__VU}-${__ITER}`,start=Date.now();const conversation=http.post(`${base}/api/v1/ai/conversations`,JSON.stringify({mode:'CUSTOMER'}),{headers:{...headers(),'Idempotency-Key':`conversation-${key}`},tags:{name:'create conversation'}});if(conversation.status!==201){completed.add(false);return;}
 const run=http.post(`${base}/api/v1/ai/conversations/${conversation.json('id')}/messages`,JSON.stringify({content:__ENV.AI_QUESTION||'What is the synthetic demo return window?'}),{headers:{...headers(),'Idempotency-Key':key},tags:{name:'send message'}});if(run.status!==202){completed.add(false);return;}
 const id=run.json('id');while(Date.now()-start<130000){const response=http.get(`${base}/api/v1/ai/runs/${id}`,{headers:headers(),tags:{name:'read run'}});if(response.status!==200){completed.add(false);return;}const state=response.json('state');if(['SUCCEEDED','FAILED','CANCELLED','WAITING_CONFIRMATION','AUTHENTICATION_REQUIRED'].includes(state)){latency.add(Date.now()-start);completed.add(state==='SUCCEEDED');return;}sleep(.25);}completed.add(false);
}
