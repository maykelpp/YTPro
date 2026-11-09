/*PURE_START*/
const normS=s=>String(s||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();
const fmtT=s=>{s=Math.max(0,Math.floor(s||0));const h=Math.floor(s/3600),m=Math.floor(s/60)%60,x=s%60;return h?`${h}:${String(m).padStart(2,'0')}:${String(x).padStart(2,'0')}`:`${m}:${String(x).padStart(2,'0')}`};
function lrcParse(t){const L=[];for(const ln of String(t||'').split(/\r?\n/)){const ts=[...ln.matchAll(/\[(\d+):(\d+(?:[.:]\d+)?)\]/g)];if(!ts.length)continue;const tx=ln.replace(/\[[^\]]*\]/g,'').trim();if(!tx)continue;for(const m of ts)L.push([+m[1]*60+ +m[2].replace(':','.'),tx])}return L.sort((a,b)=>a[0]-b[0])}
function dupGroups(tr){const g=new Map(),out=[];for(const t of tr){const k=normS(t.title)+'|'+normS(t.artist);if(!g.has(k))g.set(k,[]);g.get(k).push(t)}
 for(const a0 of g.values()){if(a0.length<2)continue;const a=a0.slice().sort((x,y)=>x.dur-y.dur);let cl=[a[0]];for(let i=1;i<a.length;i++){if(Math.abs(a[i].dur-cl[cl.length-1].dur)<=3)cl.push(a[i]);else{if(cl.length>1)out.push(cl);cl=[a[i]]}}if(cl.length>1)out.push(cl)}
 const seen=new Set(out.flat().map(t=>t.id)),bs=new Map();for(const t of tr){if(!t.size||seen.has(t.id))continue;const k=t.size+'|'+Math.round(t.dur);if(!bs.has(k))bs.set(k,[]);bs.get(k).push(t)}
 for(const a of bs.values())if(a.length>1)out.push(a);return out}
function smartShuffle(tr,plays,rnd){plays=plays||{};rnd=rnd||Math.random;const k=tr.map(t=>({t,k:Math.pow(rnd(),1+(plays[t.id]||0))}));k.sort((a,b)=>b.k-a.k);const out=k.map(x=>x.t);
 for(let i=1;i<out.length;i++){const prev=normS(out[i-1].artist);if(normS(out[i].artist)===prev){let j=i+1;while(j<out.length&&normS(out[j].artist)===prev)j++;if(j<out.length){const x=out[i];out[i]=out[j];out[j]=x}}}return out}
function mergeLists(lists,mode){const ids=new Set(),keys=new Set(),out=[];const add=t=>{const k=normS(t.title)+'|'+normS(t.artist);if(ids.has(t.id)||keys.has(k))return;ids.add(t.id);keys.add(k);out.push(t)};
 if(mode==='weave'){const n=Math.max(0,...lists.map(l=>l.length));for(let i=0;i<n;i++)for(const l of lists)if(i<l.length)add(l[i])}else for(const l of lists)for(const t of l)add(t);return out}
function moveItem(a,i,j){if(i<0||i>=a.length||j<0||j>=a.length)return a.slice();const b=a.slice(),x=b.splice(i,1)[0];b.splice(j,0,x);return b}
function normGain(db,target,maxBoost,maxCut){if(db==null||!isFinite(db))return 0;return Math.max(-maxCut,Math.min(maxBoost,target-db))}
const dbLin=d=>Math.pow(10,d/20);
function statsOf(h,now){const d0=new Date(now);d0.setHours(0,0,0,0);const day=d0.getTime(),wk=now-7*864e5;let t=0,td=0,w=0,pl=0;const tt=new Map(),aa=new Map();
 for(const r of h){t+=r.ms;if(r.ts>=day)td+=r.ms;if(r.ts>=wk)w+=r.ms;if(r.counted)pl++;
  const a=tt.get(r.id)||{id:r.id,title:r.title,artist:r.artist,plays:0,ms:0};a.plays+=r.counted?1:0;a.ms+=r.ms;tt.set(r.id,a);
  const b=aa.get(r.artist)||{artist:r.artist,plays:0,ms:0};b.plays+=r.counted?1:0;b.ms+=r.ms;aa.set(r.artist,b)}
 const top=m=>[...m.values()].sort((x,y)=>y.plays-x.plays||y.ms-x.ms).slice(0,10);return{total:t,today:td,week:w,plays:pl,tracks:top(tt),artists:top(aa)}}
const EQ_F=[60,230,910,3600,14000],EQ_L=['60 Hz','230 Hz','910 Hz','3,6 kHz','14 kHz'];
const EQ_PRESETS={'Plano':[0,0,0,0,0],'Graves+':[6,4,0,0,0],'Vocal':[-2,0,4,3,-1],'Rock':[4,2,-1,3,4],'Electrónica':[5,3,0,2,5],'Acústico':[3,1,2,3,2],'Agudos+':[-2,-1,0,4,6]};
/*PURE_END*/
