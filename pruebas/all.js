
const $=s=>document.querySelector(s);
const esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
let P={acc:0,kind:'video',h:'',ac:'mp3',abr:'192',langs:'es,en',tpl:'title',rm:false,vib:true},S={parallel:2,dest:'media'},INFO=null,R=[],LIB=[],JOBS=[],LF='all',seen={},view='home';
try{Object.assign(P,JSON.parse(localStorage.getItem('ytp')||'{}'))}catch(e){}
const saveP=()=>{try{localStorage.setItem('ytp',JSON.stringify(P))}catch(e){}};
const ACC=[['Cian','#00e5ff','#8a5cff'],['Lima','#3dffa0','#00b3ff'],['Rosa','#ff4fd8','#7a5cff'],['Ámbar','#ffb02e','#ff4f5e'],['Hielo','#7aa2ff','#00e5ff']];
function setP(k,v){P[k]=v;saveP();theme();if(k==='acc')drawSet()}
async function api(u,o){const r=await fetch(u,o);if(!r.ok)throw new Error(await r.text());return r.json()}
const post=(u,b)=>api(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});
async function setS(k,v){S=await post('/api/settings',{[k]:v});drawSet()}
const urls=()=>$('#u').value.split(/\s+/).filter(x=>/^https?:\/\//.test(x));
const mb=n=>n>1e9?(n/1e9).toFixed(2)+' GB':(n/1e6).toFixed(1)+' MB';
const chips=(name,items,cur,fn)=>items.map(([v,t])=>`<label class=ch><input type=radio name="${name}" value="${v}" ${String(cur)===String(v)?'checked':''} onchange="${fn}('${v}')"><span>${t}</span></label>`).join('');
const dockIcons={mus:'<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',home:'<path d="M3 11l9-8 9 8v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',dl:'<path d="M12 3v12m0 0l-5-5m5 5l5-5M4 20h16"/>',lib:'<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',set:'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'};
function drawDock(){$('#dock').innerHTML=[['home','Inicio'],['dl','Descargas'],['mus','Música'],['lib','Biblioteca'],['set','Ajustes']].map(([v,t])=>`<button class="${view===v?'on':''}" onclick="go('${v}')" aria-label="${t}"><svg viewBox="0 0 24 24">${dockIcons[v]}</svg>${t}${v==='dl'?'<b id=bd></b>':''}</button>`).join('');badge()}
function badge(){const n=JOBS.filter(j=>j.status==='running'||j.status==='queued').length,b=$('#bd');if(b){b.textContent=n;b.style.display=n?'grid':'none'}}
function go(v){view=v;document.querySelectorAll('.view').forEach(e=>e.classList.toggle('on',e.id==='v-'+v));drawDock();if(v==='lib')loadLib();if(v==='mus')loadMusic();if(v==='set')loadSet();window.scrollTo(0,0)}
window.goBack=()=>{if($('#sh').open){closeSh();return true}if(MP.st.open){MP.close();return true}if($('#pv').open){closePv();return true}if(view!=='home'){go('home');return true}return false};
// ---------- Inicio
const PRE=[['mp3','MP3 320',{kind:'audio',acodec:'mp3',abr:'320'}],['m4a','M4A',{kind:'audio',acodec:'m4a',abr:'256'}],['v720','MP4 720p',{kind:'video',height:'720'}],['v1080','MP4 1080p',{kind:'video',height:'1080'}],['vmax','MP4 máx.',{kind:'video',height:''}],['subs','Subtítulos',{kind:'subs'}],['thumb','Miniatura',{kind:'thumb'}]];
function drawPre(){$('#pre').innerHTML=PRE.map(([i,t])=>`<button class="pre" onclick="quick('${i}')">${t}</button>`).join('')}
async function quick(id){const u=urls();if(!u.length){$('#res').innerHTML='<div class=gl><span class=err>Pega al menos un link para usar la descarga directa.</span></div>';return}
 const o=PRE.find(p=>p[0]===id)[2];let ok=0,bad='';
 for(const x of u){try{await post('/api/download',{url:x,h264:true,meta:true,sublangs:P.langs,tpl:P.tpl,...o});ok++}catch(e){bad=e.message}}
 $('#u').value='';$('#res').innerHTML=bad?`<div class=gl><span class=err>${esc(bad)}</span></div>`:'';if(ok){go('dl');poll()}}
async function analyze(){const t=$('#u').value.trim();if(!t)return;$('#opt').innerHTML='';$('#res').innerHTML='<div class=gl><span class=mut>Analizando…</span></div>';
 try{const u=urls();if(u.length){INFO=await post('/api/info',{url:u[0]});$('#res').innerHTML='';showOpt()}
  else{R=await api('/api/search?q='+encodeURIComponent(t));$('#res').innerHTML='<div class=gl>'+(R.map((v,i)=>`<div class=res><img src="${v.thumb}" alt=""><div><div class=t2>${esc(v.title)}</div><small>${esc(v.channel)} · ${v.dur}</small></div><button class="s neon" onclick="pick(${i})">Elegir</button></div>`).join('')||'<span class=mut>Sin resultados.</span>')+'</div>'}}
 catch(e){$('#res').innerHTML=`<div class=gl><span class=err>${esc(e.message)}</span></div>`}}
function pick(i){$('#u').value=R[i].url;analyze()}
async function paste(){try{$('#u').value=await navigator.clipboard.readText();analyze()}catch(e){$('#res').innerHTML='<div class=gl><span class=err>Mantén pulsado el cuadro y elige Pegar.</span></div>'}}
const T=(id,t,sub,on)=>`<label class=tg><span>${t}${sub?`<small>${sub}</small>`:''}</span><input type=checkbox id="${id}" ${on?'checked':''}><i></i></label>`;
function showOpt(){const i=INFO,hs=[['','Mejor'],...i.heights.map(h=>[h,h+'p'])];
 $('#opt').innerHTML=`<div class=gl><div class=info><img src="${esc(i.thumb)}" alt=""><div><div class=t2>${esc(i.title)}</div><small>${esc(i.channel)} · ${i.dur}</small></div></div>
<div class=seg style="margin-top:14px">${[['video','Video'],['audio','Audio'],['subs','Subs'],['thumb','Imagen']].map(([v,t])=>`<label><input type=radio name=k value=${v} ${P.kind===v?'checked':''} onchange="tog()"><span>${t}</span></label>`).join('')}</div>
<div id=vo><div class=lbl>Calidad</div><div class=chips>${chips('q',hs,P.h,'setQ')}</div>
<div class=lbl>Contenedor</div><div class=chips>${chips('ct',[['mp4','MP4'],['mkv','MKV']],'mp4','setCt')}</div>
${T('h264','Compatible con todos los reproductores','H.264 + AAC',true)}${T('subs','Incluir subtítulos','Idiomas: '+esc(P.langs),false)}</div>
<div id=ao hidden><div class=lbl>Formato</div><div class=chips>${chips('ac',[['mp3','MP3'],['m4a','M4A'],['opus','OPUS'],['flac','FLAC'],['wav','WAV']],P.ac,'setAc')}</div>
<div class=lbl>Calidad (kbps)</div><div class=chips>${chips('abr',[['320','320'],['256','256'],['192','192'],['128','128']],P.abr,'setAbr')}</div>${T('norm','Normalizar volumen','Mismo nivel en todas tus canciones',false)}${T('lyr','Letra incrustada','Sincronizada (LRCLIB): tu reproductor la muestra al reescanear',true)}</div>
<details><summary>Opciones avanzadas</summary><div id=avo>${T('meta','Portada y datos de la canción','Se incrusta en el archivo',true)}${T('sb','Quitar patrocinios e intros','SponsorBlock',false)}${T('chap','Dividir por capítulos','Un archivo por capítulo',false)}</div>
<div class=lbl>Límite de velocidad</div><div class=chips>${chips('rt',[['','Sin límite'],['500K','500 KB/s'],['1M','1 MB/s'],['2M','2 MB/s'],['5M','5 MB/s']],'','setRt')}</div>
<div class=lbl>Nombre del archivo</div><div class=chips>${chips('tp',[['title','Título'],['channel','Canal - Título'],['date','Fecha - Título']],P.tpl,'setTp')}</div>
${i.count>1?`${T('pl','Descargar toda la lista ('+i.count+')','',false)}<div class=lbl>Elementos (ej. 1-5,8)</div><input type=text id=items placeholder="vacío = todos">`:''}
<div class=lbl>Recortar (mm:ss)</div><div class=row><input type=text id=t0 placeholder="inicio" style="flex:1"><input type=text id=t1 placeholder="fin" style="flex:1"></div></details>
<div class=row><button class="neon grow" style="padding:15px" onclick="start()">DESCARGAR</button></div></div>`;tog()}
let CUR={q:P.h,ct:'mp4',rt:''};
const setQ=v=>{CUR.q=v;P.h=v;saveP()},setCt=v=>CUR.ct=v,setRt=v=>CUR.rt=v,setAc=v=>{P.ac=v;saveP()},setAbr=v=>{P.abr=v;saveP()},setTp=v=>{P.tpl=v;saveP()};
const kind=()=>document.querySelector('input[name=k]:checked').value,ck=id=>$('#'+id)?.checked;
function tog(){const k=kind();P.kind=k;saveP();$('#vo').hidden=k!=='video';$('#ao').hidden=k!=='audio';$('#avo').hidden=!(k==='video'||k==='audio')}
async function start(){try{await post('/api/download',{url:INFO.url,title:INFO.title,kind:kind(),height:CUR.q,container:CUR.ct,h264:ck('h264'),subs:ck('subs'),sublangs:P.langs,acodec:P.ac,abr:P.abr,norm:ck('norm'),lyrics:ck('lyr'),m:{artist:INFO.artist||'',track:INFO.track||'',dur:INFO.durSec||0},meta:ck('meta'),sb:ck('sb'),chapters:ck('chap'),rate:CUR.rt,tpl:P.tpl,playlist:ck('pl'),items:$('#items')?.value||'',t0:$('#t0').value,t1:$('#t1').value});
 $('#opt').innerHTML='';$('#u').value='';go('dl');poll()}catch(e){alert(e.message)}}
// ---------- Descargas
async function poll(){try{JOBS=await api('/api/jobs')}catch(e){return}let busy=false;
 for(const j of JOBS){if(j.status==='queued'||j.status==='running')busy=true;if(j.status==='done'&&!seen[j.id]){seen[j.id]=1;if(P.vib&&navigator.vibrate)navigator.vibrate([90,50,90]);loadLib()}}
 badge();$('#jobs').innerHTML=JOBS.map((j,i)=>{const act=j.status==='done'?j.files.map(f=>`<button class=s onclick="Android.open('${f.id}')">Abrir</button><button class=s onclick="Android.share('${f.id}')">Compartir</button>`).join('')+`<button class="s" onclick="rm('${j.id}')">Quitar</button>`
  :j.status==='error'?`<button class="s neon" onclick="retry(${i})">Reintentar</button><button class="s" onclick="rm('${j.id}')">Quitar</button>`:j.status==='paused'?`<button class="s neon" onclick="jobAct('${j.id}','resume')">Reanudar</button><button class="s bad" onclick="rm('${j.id}')">Cancelar</button>`:`<button class="s" onclick="jobAct('${j.id}','pause')">Pausar</button><button class="s bad" onclick="rm('${j.id}')">Cancelar</button>`;
  return `<div class="gl job ${j.status}"><div class=ring style="--p:${j.pct}"><span>${j.status==='done'?'✓':j.status==='error'?'!':Math.round(j.pct)+'%'}</span></div><div><div class=t2>${esc(j.title)}</div>
  <small class="${j.status==='error'?'err':'mut'}">${esc(j.step)} ${esc(j.speed)} ${j.eta?'· '+esc(j.eta):''}</small>${j.error?`<div class=err>${esc(j.error)}</div>`:''}<div class=row>${act}</div></div></div>`}).join('')||'<div class=gl><span class=mut>No hay descargas todavía.</span></div>';
 if(busy)setTimeout(poll,1000)}
const rm=async id=>{await api('/api/jobs/'+id,{method:'DELETE'});poll()};
async function retry(i){const j=JOBS[i];await post('/api/download',{url:j.url,title:j.title,...j.opts});await rm(j.id)}
// ---------- Biblioteca
const KL={video:'VID',audio:'MP3',image:'IMG',text:'SRT'};
async function loadLib(){try{LIB=await api('/api/library');drawLib()}catch(e){}}
function drawLib(){const q=($('#lq').value||'').toLowerCase();$('#lf').innerHTML=chips('lf',[['all','Todo'],['video','Video'],['audio','Audio'],['image','Imágenes'],['text','Subs']],LF,'setLf');
 const l=LIB.filter(f=>(LF==='all'||f.kind===LF)&&f.name.toLowerCase().includes(q));$('#lsum').textContent=l.length+' archivos · '+mb(l.reduce((a,f)=>a+f.size,0));
 $('#lib').innerHTML=l.map(f=>`<div class="gl job"><div class=k>${KL[f.kind]||'FILE'}</div><div><div class=t2>${esc(f.name)}</div><small>${mb(f.size)}${f.hasLyrics?'<span class=lb>♪ letra</span>':''}</small>
 <div class=row>${f.kind!=='text'?`<button class=s onclick="pv('${f.id}')">Reproducir</button>`:''}<button class=s onclick="Android.open('${f.id}')">Abrir con…</button><button class=s onclick="Android.share('${f.id}')">Compartir</button><button class="s bad" onclick="del('${f.id}')">Borrar</button></div></div></div>`).join('')||'<div class=gl><span class=mut>Nada por aquí todavía.</span></div>'}
const setLf=v=>{LF=v;drawLib()};
let LY=[],lyCur=-1,PLAYLIST=[],PLAYIDX=-1,shuffleOn=false,repeatOn=false;
const parseLrc=t=>{const lines=(t||'').split(/\r?\n/);let offset=0;for(const ln of lines){const om=ln.match(/^\[offset:([+-]?\d+)\]/i);if(om){offset=parseInt(om[1],10)/1000;break}}const L=[];for(const ln of lines){const tags=[...ln.matchAll(/\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?\]/g)];if(!tags.length)continue;const text=ln.replace(/(?:\[\d{1,3}:\d{2}(?:\.\d{1,3})?\])+/g,'').trim();if(!text)continue;for(const m of tags){const frac=m[3]?Number('0.'+m[3]):0;const sec=Number(m[1])*60+Number(m[2])+frac+offset;if(sec>=0)L.push([sec,text])}}return L.sort((a,b)=>a[0]-b[0])};
const fmtTime=t=>{if(!Number.isFinite(t))return '0:00';t=Math.max(0,Math.floor(t));return Math.floor(t/60)+':'+String(t%60).padStart(2,'0')};
function playerMarkup(f){const title=f.track||f.name.replace(/\.[^.]+$/,'');const artist=f.artist||'Tu biblioteca · YTPro';return `<div class="player-shell"><div class="player-top"><button onclick="closePv()" aria-label="Volver">⌄</button><span>Ahora en pantalla</span><button onclick="toggleLyrics()" aria-label="Letras">♫</button></div><div class="album-art"><div class="album-mark"><strong>YT<br>PRO</strong><span>NOW PLAYING</span><small>LOCAL MUSIC PLAYER</small></div></div><div class="track-meta"><h3 id="ptitle">${esc(title)}</h3><p id="partist">${esc(artist)}</p></div><div class="player-progress"><input id="seek" type="range" min="0" max="1000" value="0" oninput="seekPlayer(this.value)"><div class="player-times"><span id="ptime">0:00</span><span id="pduration">0:00</span></div></div><div class="audio-quality">♫ &nbsp; LOCAL AUDIO · YT PRO</div><div class="player-controls"><button onclick="playStep(-1)" aria-label="Anterior">|◀</button><button class="main-play" id="playbtn" onclick="togglePlay()" aria-label="Pausar">Ⅱ</button><button onclick="playStep(1)" aria-label="Siguiente">▶|</button></div><div class="volume-row"><span>◖</span><input id="vol" type="range" min="0" max="1" step=".01" value="1" oninput="setVolume(this.value)"><span>◗</span></div><div class="player-tools"><button id="lyricsbtn" onclick="toggleLyrics()" title="Letras">▤</button><div><button id="shufflebtn" onclick="toggleShuffle()" title="Aleatorio">⤨</button> <button id="repeatbtn" onclick="toggleRepeat()" title="Repetir">↻</button></div><button onclick="closePv()" title="Cerrar reproductor">▣</button></div><div id="lyp" class="player-lyrics" hidden></div><audio id="pa" src="/files/${encodeURIComponent(f.id)}" autoplay></audio></div>`}
async function pv(id){const f=LIB.find(x=>x.id===id);if(!f)return;if(f.kind!=='audio'){const u='/files/'+id;$('#pvb').innerHTML=f.kind==='video'?`<video src="${u}" controls autoplay playsinline></video>`:`<img src="${u}" alt="">`;$('#pv').showModal();return}
 const l=LIB.filter(x=>x.kind==='audio').map(eTrack);MP.playTracks(l,Math.max(0,l.findIndex(x=>x.id===id)));MP.open();return}
async function playCurrent(){const f=PLAYLIST[PLAYIDX];if(!f)return;lyCur=-1;LY=[];$('#pvb').innerHTML=playerMarkup(f);if(!$('#pv').open)$('#pv').showModal();const a=$('#pa');a.volume=Number($('#vol').value||1);a.addEventListener('timeupdate',()=>{if(!a.duration)return;$('#seek').value=String(Math.round(a.currentTime/a.duration*1000));$('#ptime').textContent=fmtTime(a.currentTime);lyTick(a.currentTime)});a.addEventListener('loadedmetadata',()=>{$('#pduration').textContent=fmtTime(a.duration)});a.addEventListener('play',()=>$('#playbtn').textContent='Ⅱ');a.addEventListener('pause',()=>$('#playbtn').textContent='▶');a.addEventListener('ended',()=>{if(repeatOn){a.currentTime=0;a.play()}else playStep(1)});loadLy(f.id);a.play().catch(()=>{$('#playbtn').textContent='▶'});}
function togglePlay(){const a=$('#pa');if(!a)return;if(a.paused)a.play().catch(()=>{});else a.pause()}
function seekPlayer(v){const a=$('#pa');if(a&&a.duration)a.currentTime=(Number(v)/1000)*a.duration}
function setVolume(v){const a=$('#pa');if(a)a.volume=Number(v)}
function playStep(dir){if(!PLAYLIST.length)return;if(shuffleOn&&dir>0){PLAYIDX=Math.floor(Math.random()*PLAYLIST.length)}else PLAYIDX=(PLAYIDX+dir+PLAYLIST.length)%PLAYLIST.length;playCurrent()}
function toggleShuffle(){shuffleOn=!shuffleOn;$('#shufflebtn')?.classList.toggle('active',shuffleOn)}
function toggleRepeat(){repeatOn=!repeatOn;$('#repeatbtn')?.classList.toggle('active',repeatOn)}
function toggleLyrics(){const p=$('#lyp');if(!p)return;p.hidden=!p.hidden;$('#lyricsbtn')?.classList.toggle('active',!p.hidden)}
async function loadLy(id){let t='';try{t=(await api('/api/lyrics?id='+id)).text}catch(e){}
 LY=parseLrc(t);const p=$('#lyp');if(!p)return;
 if(LY.length)p.innerHTML=LY.map((l,i)=>`<div class=ll id=ll${i}>${esc(l[1])}</div>`).join('');
 else if(t)p.innerHTML=t.split(/\r?\n/).map(l=>`<div class=ll style="color:#c8cbe0">${esc(l)}</div>`).join('');
 else{const f=LIB.find(x=>x.id===id)||{};p.innerHTML=`<div class=mut>Sin letra todavía. Búscala:</div><input type=text id=lfq value="${esc((f.name||'').replace(/\.\w+$/,''))}"><div class=row><button class="s neon" onclick="findLy('${id}')">Buscar letra</button></div><div id=lfm class=mut></div>`}const a=$('#pa');if(a)lyTick(a.currentTime)}
async function findLy(id){const msg=$('#lfm');if(msg)msg.textContent='Buscando…';try{const r=await post('/api/lyrics/find',{id,q:$('#lfq').value});if(r.ok){loadLy(id);loadLib()}else if(msg)msg.textContent='No encontré letra. Prueba con «artista canción».'}catch(e){if(msg)msg.textContent=e.message}}
function lyTick(t){if(!LY.length)return;let k=-1;for(let i=0;i<LY.length;i++){if(LY[i][0]<=t)k=i;else break}if(k===lyCur)return;lyCur=k;document.querySelectorAll('#lyp .ll').forEach((e,i)=>e.classList.toggle('on',i===k));const e=document.getElementById('ll'+k);if(e&& !$('#lyp').hidden)e.scrollIntoView({block:'center',behavior:'smooth'})}
function closePv(){const a=$('#pa');if(a)a.pause();if($('#pv').open)$('#pv').close();$('#pvb').innerHTML='';LY=[];lyCur=-1;PLAYLIST=[];PLAYIDX=-1}
async function del(id){if(!confirm('¿Borrar este archivo de tu galería?'))return;await api('/api/library?id='+id,{method:'DELETE'});loadLib()}
// ---------- Ajustes
async function loadSet(){try{S=await api('/api/settings')}catch(e){}drawSet();engine();checkApp(false)}
function drawSet(){$('#sw').innerHTML=ACC.map((a,i)=>`<button class="${P.acc===i?'on':''}" style="background:linear-gradient(135deg,${a[1]},${a[2]})" onclick="setP('acc',${i})" aria-label="${a[0]}"></button>`).join('');
 $('#s-par').innerHTML=chips('par',[[1,'1'],[2,'2'],[3,'3'],[4,'4']],S.parallel,'setPar');$('#s-dest').innerHTML=chips('dest',[['media','Galería y Música'],['downloads','Descargas']],S.dest,'setDest');
 $('#d-kind').innerHTML=chips('dk',[['video','Video'],['audio','Audio']],P.kind,'setDk');$('#d-h').innerHTML=chips('dh',[['','Mejor'],['2160','4K'],['1080','1080p'],['720','720p'],['480','480p']],P.h,'setDh');
 $('#d-ac').innerHTML=chips('dac',[['mp3','MP3'],['m4a','M4A'],['opus','OPUS'],['flac','FLAC'],['wav','WAV']],P.ac,'setDac');$('#d-abr').innerHTML=chips('dab',[['320','320'],['256','256'],['192','192'],['128','128']],P.abr,'setDab');
 $('#d-tpl').innerHTML=chips('dt',[['title','Título'],['channel','Canal - Título'],['date','Fecha - Título']],P.tpl,'setDt');$('#d-langs').value=P.langs;
 $('#s-upd').value=S.updUrl||'';$('#s-aria').checked=S.aria!==false;$('#s-lyr').checked=S.lyrics!==false;$('#s-rm').checked=!!P.rm;$('#s-vib').checked=!!P.vib;$('#s-wifi').checked=!!S.wifi;$('#s-anti').checked=!!S.anti;$('#s-au').checked=S.autoUpdate!==false}
const setPar=v=>setS('parallel',+v),setDest=v=>setS('dest',v),setDk=v=>setP('kind',v),setDh=v=>setP('h',v),setDac=v=>setP('ac',v),setDab=v=>setP('abr',v),setDt=v=>setP('tpl',v);
async function upd(){$('#vmsg').textContent='Actualizando…';try{await post('/api/update',{})}catch(e){}engine(true)}
async function engine(w){try{const s=await api('/api/status'),e=$('#eng');
 e.className='chip '+(s.error?'warn':s.ready&&!s.updating?'on':'');e.textContent=s.error?'Motor: error':s.updating?'Actualizando yt-dlp…':s.ready?'Motor listo · '+s.version:'Preparando motor…';
 if($('#ver')){$('#ver').textContent='yt-dlp '+(s.version||'…');if(s.msg)$('#vmsg').textContent=s.msg}
 if(!s.ready||s.updating||w)setTimeout(()=>engine(s.updating||!s.ready),1500)}catch(e){setTimeout(engine,2000)}}
// ---------- actualizaciones de la app (si tu servidor de mantenimiento esta apagado, no pasa nada)
let APPC=null,updTimer=null,bnX=false;
async function checkApp(force,manual){let c;try{c=await api('/api/app/check'+(force?'?force=1':''))}catch(e){c={reachable:false}}
 APPC=c;const blk=!!(c.reachable&&(c.paused||c.force));
 if($('#appv')&&c.installed)$('#appv').textContent='Versión instalada: '+c.installed.name+' (código '+c.installed.code+')';
 if(manual&&$('#appmsg'))$('#appmsg').textContent=!c.reachable?'No se pudo contactar al servidor de actualizaciones. La app sigue funcionando normal.':c.update?'Hay una versión nueva: '+c.remote.name:'Ya tienes la última versión.';
 if(blk)ovShow(c);else $('#ov').hidden=true;
 const bn=$('#bn');bn.hidden=!(c.reachable&&c.update&&!blk&&!bnX);if(!bn.hidden)$('#bnt').textContent='Nueva versión '+c.remote.name+(c.remote.notes?' · '+c.remote.notes:'');
 clearTimeout(updTimer);updTimer=setTimeout(()=>checkApp(false),blk?5000:60000)}
function ovShow(c){$('#ov').hidden=false;const d=c.dl||{},busy=['downloading','verifying','installing'].includes(d.state),pb=$('#ovb').parentElement;
 if(c.paused){$('#ovt').textContent='App en mantenimiento';$('#ovp').textContent=c.message||'Vuelve en unos minutos.';pb.hidden=true;$('#ova').innerHTML='<button class="neon" onclick="checkApp(true)">Reintentar</button>';return}
 $('#ovt').textContent='Actualización obligatoria';$('#ovp').textContent=(c.message||'Necesitas actualizar para seguir usando la app.')+(c.remote?' (versión '+c.remote.name+')':'');
 pb.hidden=!busy;$('#ovb').style.width=(d.pct||0)+'%';
 $('#ova').innerHTML=busy?'<span class=mut>'+(d.state==='downloading'?'Descargando '+Math.round(d.pct||0)+'%':d.state==='verifying'?'Verificando…':'Confirma la instalación en la ventana de Android')+'</span>':'<button class="neon" onclick="doUpdate()">Actualizar ahora</button>'+(d.error?'<div class=err style="width:100%">'+esc(d.error)+'</div>':'')}
async function doUpdate(){let s;try{s=await post('/api/app/update',{})}catch(e){alert(e.message);return}
 if(s.state==='need_permission'){alert('Android necesita tu permiso: activa «Permitir de esta fuente» para YT Pro y vuelve a pulsar Actualizar.');if(window.Android&&Android.allowInstall)Android.allowInstall();return}
 updPoll()}
async function updPoll(){let c;try{c=await api('/api/app/check')}catch(e){return}const d=c.dl||{};
 if(c.reachable&&(c.force||c.paused))ovShow(c);else{const bn=$('#bn');bn.hidden=false;$('#bnt').textContent=d.state==='downloading'?'Descargando '+Math.round(d.pct||0)+'%':d.state==='verifying'?'Verificando…':d.state==='installing'?'Confirma la instalación en Android':d.state==='error'?'Error: '+d.error:'Nueva versión disponible'}
 if(['downloading','verifying'].includes(d.state))setTimeout(updPoll,700)}
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

// ======================= Música: reproductor, biblioteca inteligente, listas, estadísticas =======================
const LS=(k,d)=>{try{const v=localStorage.getItem(k);return v?JSON.parse(v):d}catch(e){return d}},LW=(k,v)=>{try{localStorage.setItem(k,JSON.stringify(v))}catch(e){}};
Object.assign(P,{xf:4,norm:true,normT:-14,normB:6,gOn:true,gMin:90,gL:'next',gR:'prev',gU:'volup',gD:'voldown',gDL:'seekb',gDR:'seekf',vizOn:false,vizSt:0,vizFps:30,vol:1,mode:'dark',cA:'#00e5ff',cB:'#8a5cff',cBg:'#05060f'},LS('ytp_mp',{}));
const saveMP=()=>LW('ytp_mp',P);
let LIBM=[],MFOLD=[],MSTAT={},MSUB='songs',MQ='',MMORE=300,PLS=LS('ytp_pls',[]),HIST=LS('ytp_hist',[]),EQV=LS('ytp_eq',{g:[0,0,0,0,0],custom:{}});
const MP=(()=>{
 let ctx,decks=[],cur=0,eq=[],comp,an,master,fft,hist8=[],xfading=false,pre=false,errN=0,raf=0,lastDraw=0,lastTick=0,curH=null,lastSave=0;
 const st={q:[],orig:null,i:-1,shuffle:false,repeat:0,playing:false,sleepAt:0,sleepEos:false,open:false,cine:false,lyr:[],lyrFor:'',lyrIdx:-1,loud:{}};
 const tr=()=>st.q[st.i],dk=()=>decks[cur];
 function init(){if(ctx)return;const AC=window.AudioContext||window.webkitAudioContext;ctx=new AC();
  decks=[0,1].map(n=>{const el=new Audio();el.preload='auto';el.crossOrigin='anonymous';const src=ctx.createMediaElementSource(el),ng=ctx.createGain(),g=ctx.createGain();src.connect(ng);ng.connect(g);
   const d={el,ng,g,t:null,n};el.addEventListener('timeupdate',()=>{if(d===dk())onTime()});el.addEventListener('ended',()=>{if(d===dk())advance(true);else{try{el.pause()}catch(e){}}});
   el.addEventListener('error',()=>{if(d===dk())onError()});el.addEventListener('playing',()=>{if(d===dk())setPlaying(true)});el.addEventListener('pause',()=>{if(d===dk()&&!xfading&&!el.ended)setPlaying(false)});return d});
  eq=EQ_F.map((f,i)=>{const b=ctx.createBiquadFilter();b.type=i===0?'lowshelf':i===4?'highshelf':'peaking';b.frequency.value=f;b.Q.value=1;b.gain.value=EQV.g[i]||0;return b});
  comp=ctx.createDynamicsCompressor();an=ctx.createAnalyser();an.fftSize=256;an.smoothingTimeConstant=.8;master=ctx.createGain();master.gain.value=P.vol;fft=new Uint8Array(an.frequencyBinCount);
  decks.forEach(d=>d.g.connect(eq[0]));for(let i=0;i<4;i++)eq[i].connect(eq[i+1]);eq[4].connect(comp);comp.connect(an);an.connect(master);master.connect(ctx.destination);applyNorm()}
 function applyNorm(){if(!comp)return;const on=!!P.norm;comp.threshold.value=on?-8:0;comp.knee.value=on?6:0;comp.ratio.value=on?12:1;comp.attack.value=.003;comp.release.value=.25}
 function setEq(i,v){EQV.g[i]=+v;if(eq[i])eq[i].gain.setTargetAtTime(+v,ctx.currentTime,.02);LW('ytp_eq',EQV)}
 const T=(id)=>st.q.find(t=>t.id===id);
 async function loudFor(t,d){if(!P.norm){d.ng.gain.setTargetAtTime(1,ctx.currentTime,.2);return}let db=st.loud[t.id];if(db===undefined){try{db=(await api('/api/loudness?id='+t.id)).db}catch(e){db=null}st.loud[t.id]=db}
  if(d.t&&d.t.id===t.id)d.ng.gain.setTargetAtTime(dbLin(normGain(db,P.normT,P.normB,12)),ctx.currentTime,.4)}
 function setTrack(d,t){d.t=t;d.el.src=t.src;d.ng.gain.value=1;d.el.load();loudFor(t,d)}
 const nextIdx=()=>{if(!st.q.length)return -1;if(st.repeat===2)return st.i;const n=st.i+1;return n<st.q.length?n:(st.repeat===1?0:-1)};
 async function playIndex(i,{keep=false}={}){init();if(i<0||i>=st.q.length)return;await ctx.resume();xfading=false;pre=false;const o=decks[1-cur];try{o.el.pause()}catch(e){}o.g.gain.cancelScheduledValues(ctx.currentTime);o.g.gain.value=0;
  const d=dk();st.i=i;setTrack(d,st.q[i]);d.g.gain.cancelScheduledValues(ctx.currentTime);d.g.gain.value=1;d.el.currentTime=0;startHist();try{await d.el.play();errN=0}catch(e){onError();return}afterChange()}
 function playTracks(list,i,{shuffle=false}={}){init();st.q=list.slice();st.orig=null;st.shuffle=false;if(shuffle)toggleShuffle(true,i);else playIndex(i||0);notifOnce()}
 function startHist(){flushHist();const t=tr();if(!t)return;curH={id:t.id,title:t.title,artist:t.artist,ts:Date.now(),ms:0,counted:false,dur:t.dur||0};HIST.push(curH);if(HIST.length>1500)HIST=HIST.slice(-1000)}
 function flushHist(){if(curH){LW('ytp_hist',HIST)}lastSave=Date.now()}
 function onTime(){const d=dk(),el=d.el;if(!el.duration||!isFinite(el.duration))return;const now=performance.now();
  if(lastTick&&st.playing&&curH){const dt=Math.min(2000,now-lastTick);curH.ms+=dt;if(!curH.counted&&(curH.ms>=30000||curH.ms>=el.duration*500)){curH.counted=true}}lastTick=now;if(Date.now()-lastSave>15000)flushHist();
  const rem=el.duration-el.currentTime;if(!pre&&rem<20&&nextIdx()>=0){pre=true;const n=nextIdx(),nd=decks[1-cur];if(!nd.t||nd.t.id!==st.q[n].id){nd.t=st.q[n];nd.el.src=st.q[n].src;nd.el.load()}}
  if(!xfading&&P.xf>0&&rem<=P.xf+.05&&rem>.3&&nextIdx()>=0&&st.repeat!==2&&el.duration>P.xf*2.5)startXfade();
  sleepTick();ui(false)}
 function startXfade(){const n=nextIdx();if(n<0)return;xfading=true;const o=dk(),nd=decks[1-cur],t=st.q[n],x=P.xf,now=ctx.currentTime;if(!nd.t||nd.t.id!==t.id)setTrack(nd,t);else loudFor(t,nd);
  nd.g.gain.cancelScheduledValues(now);nd.g.gain.setValueAtTime(0,now);nd.g.gain.linearRampToValueAtTime(1,now+x);o.g.gain.cancelScheduledValues(now);o.g.gain.setValueAtTime(o.g.gain.value,now);o.g.gain.linearRampToValueAtTime(0,now+x);
  nd.el.currentTime=0;nd.el.play().catch(()=>{});cur=1-cur;st.i=n;pre=false;startHist();setTimeout(()=>{xfading=false;try{o.el.pause()}catch(e){}},x*1000+250);afterChange()}
 function advance(ended){if(xfading)return;const n=nextIdx();if(st.sleepEos){st.sleepEos=false;pause();return}if(n<0){setPlaying(false);flushHist();pushState(true);ui(true);return}playIndex(n)}
 function onError(){errN++;if(errN>3){setPlaying(false);toast('No se pudo reproducir');return}setTimeout(()=>advance(true),500)}
 function setPlaying(v){if(st.playing===v)return;st.playing=v;lastTick=0;if(!v)flushHist();pushState();ui(true);vizLoop()}
 const play=async()=>{init();await ctx.resume();if(!dk().t&&st.q.length){playIndex(Math.max(0,st.i));return}try{await dk().el.play()}catch(e){}};
 const pause=()=>{if(dk())dk().el.pause()};
 const toggle=()=>st.playing?pause():play();
 function next(){const n=st.repeat===2?(st.i+1)%st.q.length:nextIdx();if(n>=0)playIndex(n);else if(st.q.length)playIndex(0)}
 function prev(){const d=dk();if(d&&d.el.currentTime>4){d.el.currentTime=0;return}playIndex(st.i>0?st.i-1:(st.repeat===1?st.q.length-1:0))}
 const seek=s=>{const d=dk();if(d&&isFinite(s)){d.el.currentTime=Math.max(0,Math.min(d.el.duration||s,s));pushState()}};
 function toggleShuffle(force,startAt){const on=force!==undefined?force:!st.shuffle;if(on&&!st.shuffle){const c=tr()||st.q[startAt||0];st.orig=st.q.slice();const rest=st.q.filter(t=>t!==c);const plays={};HIST.forEach(h=>{if(h.counted)plays[h.id]=(plays[h.id]||0)+1});st.q=[c].concat(smartShuffle(rest,plays));st.shuffle=true;if(!tr()||!st.playing)playIndex(0);else st.i=0}
  else if(!on&&st.shuffle){const c=tr();st.q=st.orig||st.q;st.orig=null;st.shuffle=false;st.i=Math.max(0,st.q.indexOf(c))}ui(true)}
 const cycleRepeat=()=>{st.repeat=(st.repeat+1)%3;ui(true)};
 function queueMove(i,j){const c=tr();st.q=moveItem(st.q,i,j);st.i=st.q.indexOf(c);if(st.shuffle)st.orig=null}
 function queueRemove(i){if(i===st.i)return;const c=tr();st.q.splice(i,1);st.i=st.q.indexOf(c)}
 function enqueue(t,next){if(st.q.some(x=>x.id===t.id))return;if(next&&st.i>=0)st.q.splice(st.i+1,0,t);else st.q.push(t);if(st.i<0){st.i=0}ui(true)}
 // ---- temporizador
 function sleep(min){st.sleepEos=false;st.sleepAt=min>0?Date.now()+min*60000:0;if(master)master.gain.value=P.vol;ui(true)}
 function sleepTick(){if(st.sleepAt){const r=st.sleepAt-Date.now();if(r<=0){st.sleepAt=0;pause();master.gain.value=P.vol;toast('Temporizador: música detenida');ui(true)}else if(r<10000)master.gain.setTargetAtTime(P.vol*r/10000,ctx.currentTime,.3)}}
 const sleepLeft=()=>st.sleepAt?Math.max(0,Math.round((st.sleepAt-Date.now())/1000)):0;
 function setVol(v){P.vol=Math.max(0,Math.min(1,v));if(master)master.gain.value=P.vol;saveMP();const e=$('#mpvol');if(e)e.value=P.vol}
 // ---- Media Session del sistema
 function pushState(stop){const t=tr(),d=dk();const o=stop&&!t?{stop:true}:{title:t?t.title:'',artist:t?t.artist:'',album:t?t.album||'':'',dur:d&&isFinite(d.el.duration)?d.el.duration:(t?t.dur:0),pos:d?d.el.currentTime:0,playing:st.playing,art:t?t.id:''};
  try{if(window.Android&&Android.mediaState)Android.mediaState(JSON.stringify(o))}catch(e){}
  try{if(navigator.mediaSession&&t){navigator.mediaSession.metadata=new MediaMetadata({title:t.title,artist:t.artist,album:t.album||''});navigator.mediaSession.playbackState=st.playing?'playing':'paused'}}catch(e){}}
 function afterChange(){st.lyrFor='';st.lyr=[];st.lyrIdx=-1;loadLyr();pushState();ui(true);paintArt()}
 function notifOnce(){try{if(window.Android&&!LS('ytp_notif',0)){LW('ytp_notif',1);Android.reqNotifPerm()}}catch(e){}}
 window.ytpCmd=c=>{if(c==='play')play();else if(c==='pause')pause();else if(c==='next')next();else if(c==='prev')prev();else if(c.startsWith('seek:'))seek(parseFloat(c.slice(5)))};
 // ---- letras cinematograficas
 async function loadLyr(){const t=tr();if(!t||st.lyrFor===t.id)return;st.lyrFor=t.id;let txt='';try{txt=(await api((t.kind==='e'?'/api/lyrics?id=':'/api/music/lyrics?id=')+t.id)).text}catch(e){}
  if(tr()!==t)return;st.lyr=lrcParse(txt);st.plain=st.lyr.length?'':txt;drawLyr()}
 function drawLyr(){const p=$('#mply');if(!p)return;const t=tr();if(st.lyr.length)p.innerHTML='<div class=lyin id=lyin>'+st.lyr.map((l,i)=>`<div class=lyl id=ly${i}>${esc(l[1])}</div>`).join('')+'</div>';
  else if(st.plain)p.innerHTML='<div class=lyin style="transform:none;padding:20px 0">'+st.plain.split(/\r?\n/).map(l=>`<div class="lyl on">${esc(l)}</div>`).join('')+'</div>';
  else p.innerHTML=`<div class="lyin" style="transform:none;padding:24px 0"><div class=mut style="font-size:1rem;font-weight:400">Sin letra. Búscala:</div><input type=text id=lfq value="${esc((t?t.artist+' '+t.title:'').trim())}"><div class=row><button class="s neon" onclick="MP.findLyr()">Buscar letra</button></div><div id=lfm class=mut style="font-size:.85rem"></div></div>`;lyTick2(true)}
 async function findLyr(){const t=tr();if(!t)return;$('#lfm').textContent='Buscando…';try{const r=await post(t.kind==='e'?'/api/lyrics/find':'/api/music/lyrics/find',{id:t.id,q:$('#lfq').value});if(r.ok){st.lyrFor='';loadLyr()}else $('#lfm').textContent='No encontré letra. Prueba «artista canción».'}catch(e){$('#lfm').textContent=e.message}}
 function lyTick2(force){if(!st.lyr.length)return;const d=dk();if(!d)return;const tt=d.el.currentTime+.15;let k=-1;for(let i=0;i<st.lyr.length;i++){if(st.lyr[i][0]<=tt)k=i;else break}if(k===st.lyrIdx&&!force)return;st.lyrIdx=k;
  const box=$('#mply'),inn=$('#lyin');if(!inn||!box)return;inn.querySelectorAll('.lyl').forEach((e,i)=>{const dd=Math.abs(i-k);e.className='lyl'+(i===k?' on':dd===1?' d1':dd===2?' d2':' d3')});const a=$('#ly'+k);if(a)inn.style.transform=`translateY(${box.clientHeight/2-a.offsetTop-a.offsetHeight/2}px)`}
 // ---- visualizador 3D
 const VS=['Barras 3D','Túnel','Esfera'];
 function vizLoop(){cancelAnimationFrame(raf);if(!st.open||!P.vizOn||!st.playing||document.hidden)return;raf=requestAnimationFrame(drawViz)}
 function bands(n){an.getByteFrequencyData(fft);const out=new Array(n),per=Math.floor(fft.length*.75/n)||1;for(let i=0;i<n;i++){let s=0;for(let j=0;j<per;j++)s+=fft[i*per+j];out[i]=s/per/255}return out}
 function drawViz(ts){vizLoop();if(ts-lastDraw<1000/(P.vizFps||30))return;lastDraw=ts;const c=$('#viz');if(!c)return;const sc=Math.min(window.devicePixelRatio||1,1.5),w=c.clientWidth,h=c.clientHeight;if(c.width!==Math.round(w*sc)){c.width=Math.round(w*sc);c.height=Math.round(h*sc)}
  const g=c.getContext('2d');g.setTransform(sc,0,0,sc,0,0);g.clearRect(0,0,w,h);const A=P.cA,B=P.cB;
  if(P.vizSt===0){const b=bands(24);hist8.unshift(b);if(hist8.length>18)hist8.pop();for(let r=hist8.length-1;r>=0;r--){const z=r/18,s=1-.55*z,rw=w*s,x0=(w-rw)/2,y0=h*(.92-.5*z),bw=rw/24;g.globalAlpha=1-z*.8;g.fillStyle=r%2?A:B;for(let i=0;i<24;i++){const bh=hist8[r][i]*h*.45*s;g.fillRect(x0+i*bw+1,y0-bh,bw-2,bh)}}g.globalAlpha=1}
  else if(P.vizSt===1){const b=bands(16),bass=(b[0]+b[1]+b[2])/3,t=ts/1000;g.translate(w/2,h/2);for(let i=14;i>=0;i--){const k=i/14,rad=(Math.min(w,h)*.08)*(1+i*.38+bass*.5*(1-k));g.save();g.rotate(t*.3*(i%2?1:-1)+i*.2);g.strokeStyle=i%2?A:B;g.globalAlpha=1-k*.75;g.lineWidth=1+b[i%16]*6;g.beginPath();for(let a=0;a<6.2832;a+=.2){const rr=rad*(1+.12*Math.sin(a*3+t*2)*b[(i+3)%16]);a?g.lineTo(Math.cos(a)*rr,Math.sin(a)*rr):g.moveTo(rr,0)}g.closePath();g.stroke();g.restore()}g.setTransform(sc,0,0,sc,0,0)}
  else{const b=bands(32),t=ts/1000,N=260,R=Math.min(w,h)*.3,cy=Math.cos(t*.5),sy=Math.sin(t*.5),cx=Math.cos(t*.3),sx=Math.sin(t*.3),pts=[];for(let i=0;i<N;i++){const ph=Math.acos(1-2*(i+.5)/N),th=Math.PI*(1+Math.sqrt(5))*i,r=R*(.75+.7*b[i%32]);let x=r*Math.sin(ph)*Math.cos(th),y=r*Math.sin(ph)*Math.sin(th),z=r*Math.cos(ph);const x1=x*cy+z*sy,z1=-x*sy+z*cy,y2=y*cx-z1*sx,z2=y*sx+z1*cx,p=1/(1+z2/(R*3));pts.push([w/2+x1*p,h/2+y2*p,z2,p])}
   pts.sort((a,b)=>a[2]-b[2]);for(const p of pts){g.globalAlpha=.25+.75*(p[2]/R+1)/2;g.fillStyle=p[2]>0?A:B;g.beginPath();g.arc(p[0],p[1],1.2+2.6*p[3],0,6.2832);g.fill()}g.globalAlpha=1}}
 // ---- interfaz
 function paintArt(){const t=tr();if(!t)return;const src=t.art||'/api/art?id='+t.id;['#mpimg','#minimg'].forEach(s=>{const e=$(s);if(e){e.onerror=()=>{e.style.visibility='hidden'};e.onload=()=>{e.style.visibility='visible'};e.src=src}});
  const im=new Image();im.crossOrigin='anonymous';im.onload=()=>{try{const c=document.createElement('canvas');c.width=c.height=8;const x=c.getContext('2d');x.drawImage(im,0,0,8,8);const d=x.getImageData(0,0,8,8).data;let r=0,g=0,b=0;for(let i=0;i<d.length;i+=4){r+=d[i];g+=d[i+1];b+=d[i+2]}const n=d.length/4;$('#mp').style.setProperty('--cv',`rgb(${r/n|0},${g/n|0},${b/n|0})`)}catch(e){}};im.onerror=()=>$('#mp').style.removeProperty('--cv');im.src=src}
 function ui(full){const t=tr(),d=dk(),mini=$('#mini');if(!mini)return;mini.hidden=!(t&&!st.open);$('#mpt').textContent=t?t.title:'—';$('#mpa').textContent=t?t.artist+(t.album&&t.album!=='Desconocido'?' · '+t.album:''):'';$('#mint').textContent=t?t.title:'';$('#mina').textContent=t?t.artist:'';
  const dur=d&&isFinite(d.el.duration)?d.el.duration:(t?t.dur:0),pos=d?d.el.currentTime:0;if(!$('#mpseek').matches(':active'))$('#mpseek').value=dur?Math.round(pos/dur*1000):0;$('#mpcur').textContent=fmtT(pos);$('#mpdur').textContent=fmtT(dur);$('#minbar').style.width=(dur?pos/dur*100:0)+'%';
  if(full){const pl=st.playing;$('#mpplay').innerHTML=pl?'❚❚':'▶';$('#minplay').innerHTML=pl?'❚❚':'▶';$('#mpsh').classList.toggle('on',st.shuffle);$('#mprp').classList.toggle('on',st.repeat>0);$('#mprp').textContent=st.repeat===2?'🔂':'🔁';$('#mpvz').classList.toggle('on',P.vizOn);$('#mpst').classList.toggle('viz',P.vizOn);$('#mpst').classList.toggle('lyr',st.cine)}
  const sl=sleepLeft();$('#mpsl').textContent=sl?'⏱ '+fmtT(sl):(st.sleepEos?'⏱ fin':'⏱');lyTick2()}
 function open(){st.open=true;$('#mp').hidden=false;ui(true);paintArt();drawLyr();vizLoop()}
 function close(){st.open=false;$('#mp').hidden=true;if(document.fullscreenElement)document.exitFullscreen().catch(()=>{});ui(true);vizLoop()}
 function toggleCine(){st.cine=!st.cine;if(st.cine){drawLyr();if(P.vizOn){P.vizOn=false;saveMP()}}ui(true)}
 function toggleViz(){P.vizOn=!P.vizOn;if(P.vizOn)st.cine=false;saveMP();ui(true);vizLoop()}
 function fullscreen(){const e=$('#mp');if(document.fullscreenElement)document.exitFullscreen();else if(e.requestFullscreen)e.requestFullscreen().catch(()=>{})}
 // ---- gestos
 function gAct(a){const m={next,prev,toggle,volup:()=>setVol(P.vol+.1),voldown:()=>setVol(P.vol-.1),seekf:()=>seek(dk().el.currentTime+10),seekb:()=>seek(dk().el.currentTime-10),none:()=>{}};const ic={next:'⏭',prev:'⏮',toggle:'⏯',volup:'🔊',voldown:'🔉',seekf:'+10 s',seekb:'−10 s'};if(m[a]){m[a]();if(a!=='none')toast(ic[a])}}
 function gesture(el){let s=null,last=0;el.addEventListener('touchstart',e=>{if(!P.gOn||e.touches.length!==1||e.target.closest('input,button,select,.lyin input'))return;const t=e.touches[0];s={x:t.clientX,y:t.clientY,t:Date.now()}},{passive:true});
  el.addEventListener('touchend',e=>{if(!s)return;const t=e.changedTouches[0],dx=t.clientX-s.x,dy=t.clientY-s.y,dt=Date.now()-s.t,ax=Math.abs(dx),ay=Math.abs(dy);
   if(ax<12&&ay<12&&dt<250){const n=Date.now();if(n-last<300){const r=(t.clientX-el.getBoundingClientRect().left)/(el.clientWidth||1);gAct(r<.33?P.gDL:r>.66?P.gDR:'toggle');last=0}else last=n}
   else if(dt<700&&Math.max(ax,ay)>P.gMin){if(ax>ay*1.8)gAct(dx<0?P.gL:P.gR);else if(ay>ax*1.8)gAct(dy<0?P.gU:P.gD)}s=null},{passive:true})}
 function toast(m){const e=$('#toast');if(!e)return;e.textContent=m;e.classList.add('on');clearTimeout(toast.t);toast.t=setTimeout(()=>e.classList.remove('on'),1100)}
 document.addEventListener('visibilitychange',()=>vizLoop());
 return{dur:()=>{const d=dk();return d&&isFinite(d.el.duration)?d.el.duration:(tr()?tr().dur:0)},st,init,playTracks,playIndex,play,pause,toggle,next,prev,seek,toggleShuffle,cycleRepeat,queueMove,queueRemove,enqueue,sleep,sleepLeft,setVol,setEq,applyNorm,findLyr,open,close,toggleCine,toggleViz,fullscreen,gesture,toast,ui,VS,vizLoop,tr,eqs:()=>eq,T,pushState,afterChange,loadLyr,gAct};
})();

// ---------- Música: biblioteca inteligente, listas, hojas inferiores, ajustes
const tObj=x=>({id:x.id,title:x.title,artist:x.artist,album:x.album,dur:x.dur,size:x.size,no:x.no||0,writable:x.writable,origin:x.src,hasLrc:x.hasLrc,src:'/music/'+x.id,kind:'t',art:'/api/art?id='+x.id});
const eTrack=e=>({id:e.id,title:(e.track||e.name||'').replace(/\.\w+$/,''),artist:e.artist||'YT Pro',album:'',dur:e.duration||0,size:e.size,src:'/files/'+e.id,kind:'e',art:'/api/art?id='+e.id});
const TBY=id=>LIBM.find(t=>t.id===id);
async function loadMusic(){try{const j=await api('/api/music/library');LIBM=j.tracks.map(tObj);MFOLD=j.folders;MSTAT=j;if(j.scanning)setTimeout(loadMusic,1500)}catch(e){}drawMusic()}
const rescan=async()=>{try{await post('/api/music/scan',{})}catch(e){}setTimeout(loadMusic,800)};
window.ytpPerm=()=>{if(view==='mus')loadMusic();if(view==='set')loadSet()};
const pickFolder=()=>{if(window.Android&&Android.pickFolder)Android.pickFolder();else alert('El selector de carpetas solo existe en la app de Android.')};
const rmFolder=async u=>{await api('/api/music/folders?uri='+encodeURIComponent(u),{method:'DELETE'});setTimeout(()=>{loadMusic();loadSet()},600)};
const SUBS=[['songs','Canciones'],['artists','Artistas'],['albums','Álbumes'],['dups','Duplicados'],['lists','Listas'],['hist','Historial'],['stats','Estadísticas']];
function setSub(v){MSUB=v;MMORE=300;drawMusic()}
const filt=()=>{const q=normS(MQ);return LIBM.filter(t=>!q||normS(t.title+' '+t.artist+' '+t.album).includes(q)).sort((a,b)=>normS(a.title).localeCompare(normS(b.title)))};
function trow(t,i,list){return `<div class="res"><div class=k style="width:40px;height:40px"><img src="${t.art}" alt="" style="width:100%;height:100%;object-fit:cover;border-radius:12px" onerror="this.remove()"></div><div onclick="playFrom('${t.id}')" style="cursor:pointer"><div class=t2>${esc(t.title)}</div><small>${esc(t.artist)} · ${esc(t.album)} · ${fmtT(t.dur)}</small></div><button class="s" onclick="rowMenu('${t.id}')" aria-label="Más">⋯</button></div>`}
function playFrom(id){const l=filt(),i=l.findIndex(t=>t.id===id);if(i<0)return;MP.playTracks(l,i);MP.open()}
const playAll=sh=>{const l=filt();if(!l.length)return;MP.playTracks(l,0,{shuffle:sh});MP.open()};
function drawMusic(){const box=$('#mus');if(!box)return;let h='';const perm=window.Android&&Android.hasPerm?Android.hasPerm('audio'):true;
 $('#msub').innerHTML=chips('ms',SUBS,MSUB,'setSub');
 if(!perm)h+=`<div class=gl><b>Acceso a tu música</b><p class=mut>Para ver toda la música del teléfono, Android necesita tu permiso de lectura de audio. Se usa solo para listar y reproducir tus canciones, sin salir del teléfono.</p><div class=row><button class="neon" onclick="Android.reqAudioPerm()">Dar permiso</button><button onclick="pickFolder()">O elegir una carpeta</button></div></div>`;
 if(MSTAT.scanning)h+='<div class="gl mut">Actualizando biblioteca…</div>';else if(MSTAT.msg)h+=`<div class="gl mut">${esc(MSTAT.msg)}</div>`;
 if(MSUB==='songs'){const l=filt();h+=`<div class=row><button class=neon onclick="playAll(false)">▶ Reproducir</button><button onclick="playAll(true)">🔀 Aleatorio inteligente</button></div><small class=mut>${l.length} canciones · ${fmtT(l.reduce((a,t)=>a+t.dur,0))}</small>`+(l.length?'<div class=gl>'+l.slice(0,MMORE).map(trow).join('')+(l.length>MMORE?`<div class=row><button onclick="MMORE+=300;drawMusic()">Mostrar más</button></div>`:'')+'</div>':'<div class=gl><span class=mut>No hay canciones todavía. Da permiso de audio o añade una carpeta en Ajustes.</span></div>')}
 else if(MSUB==='artists'||MSUB==='albums'){const k=MSUB==='artists'?'artist':'album',m=new Map();LIBM.forEach(t=>{const a=m.get(t[k])||[];a.push(t);m.set(t[k],a)});h+='<div class=gl>'+[...m.entries()].sort((a,b)=>normS(a[0]).localeCompare(normS(b[0]))).map(([n,a])=>`<div class=res><div onclick="MQ='${esc(n).replace(/'/g,'&#39;')}';setSub('songs');$('#mq').value=MQ" style="cursor:pointer;flex:1"><div class=t2>${esc(n)}</div><small>${a.length} canciones · ${fmtT(a.reduce((s,t)=>s+t.dur,0))}</small></div></div>`).join('')+'</div>'}
 else if(MSUB==='dups'){const g=dupGroups(LIBM);h+=g.length?`<small class=mut>${g.length} grupos de archivos repetidos. Solo se pueden borrar los de YT Pro y los de carpetas con permiso de escritura.</small>`+g.map(a=>`<div class=gl>`+a.map(t=>`<div class=res><div style="flex:1"><div class=t2>${esc(t.title)}</div><small>${esc(t.artist)} · ${fmtT(t.dur)} · ${mb(t.size)} · ${t.origin==='saf'?'carpeta':'teléfono'}</small></div><button class=s onclick="playFrom('${t.id}')">▶</button>${t.writable?`<button class="s bad" onclick="delTrack('${t.id}')">Borrar</button>`:''}</div>`).join('')+'</div>').join(''):'<div class=gl><span class=mut>No se detectaron duplicados 🎉</span></div>'}
 else if(MSUB==='lists')h+=drawLists();
 else if(MSUB==='hist'){const r=HIST.slice().reverse().slice(0,100);h+='<div class=gl>'+(r.map(x=>`<div class=res><div style="flex:1"><div class=t2>${esc(x.title)}</div><small>${esc(x.artist)} · ${new Date(x.ts).toLocaleString()} · ${fmtT(x.ms/1000)}</small></div></div>`).join('')||'<span class=mut>Aún no has reproducido nada.</span>')+'</div>'}
 else{const s=statsOf(HIST,Date.now());h+=`<div class=gl><div class=row><div class=grow><b>${fmtT(s.today/1000)}</b><br><small class=mut>hoy</small></div><div class=grow><b>${fmtT(s.week/1000)}</b><br><small class=mut>7 días</small></div><div class=grow><b>${fmtT(s.total/1000)}</b><br><small class=mut>total</small></div><div class=grow><b>${s.plays}</b><br><small class=mut>reproducciones</small></div></div></div><div class=gl><b>Más escuchadas</b>`+(s.tracks.map((t,i)=>`<div class=res><div style="flex:1"><div class=t2>${i+1}. ${esc(t.title)}</div><small>${esc(t.artist)} · ${t.plays} veces · ${fmtT(t.ms/1000)}</small></div></div>`).join('')||'<br><span class=mut>Sin datos todavía.</span>')+`</div><div class=gl><b>Artistas</b>`+(s.artists.map(a=>`<div class=res><div style="flex:1"><div class=t2>${esc(a.artist)}</div><small>${a.plays} veces · ${fmtT(a.ms/1000)}</small></div></div>`).join('')||'<br><span class=mut>Sin datos todavía.</span>')+`</div><div class=row><button class=bad onclick="if(confirm('¿Borrar historial y estadísticas?')){HIST=[];LW('ytp_hist',HIST);drawMusic()}">Borrar historial</button></div>`}
 box.innerHTML=h}
async function delTrack(id){if(!confirm('¿Borrar este archivo del teléfono?'))return;try{await api('/api/music/delete?id='+id,{method:'DELETE'});setTimeout(loadMusic,700)}catch(e){alert(e.message)}}
// ---- listas
function drawLists(){let h=`<div class=row><button class=neon onclick="sheet('newpl')">+ Nueva lista</button><button onclick="sheet('merge')">Combinar listas</button><button onclick="plFromQueue()">Guardar la cola</button></div>`;
 h+=PLS.length?PLS.map((p,i)=>{const n=p.ids.filter(id=>TBY(id)).length;return `<div class=gl><b>${esc(p.name)}</b><br><small class=mut>${n} de ${p.ids.length} canciones</small><div class=row><button class="s neon" onclick="plPlay(${i},false)">▶</button><button class=s onclick="plPlay(${i},true)">🔀 Mezcla</button><button class=s onclick="sheet('edpl',${i})">Editar</button><button class="s bad" onclick="plDel(${i})">Borrar</button></div></div>`}).join(''):'<div class=gl><span class=mut>Todavía no tienes listas.</span></div>';return h}
const plSave=()=>LW('ytp_pls',PLS);
function plAdd(name,ids){PLS.push({id:'p'+Date.now(),name:name||'Lista '+(PLS.length+1),ids:[...new Set(ids)]});plSave()}
const plTracks=i=>PLS[i].ids.map(TBY).filter(Boolean);
function plPlay(i,sh){const l=plTracks(i);if(!l.length){alert('Esa lista no tiene canciones disponibles.');return}MP.playTracks(l,0,{shuffle:sh});MP.open()}
function plDel(i){if(confirm('¿Borrar la lista «'+PLS[i].name+'»?')){PLS.splice(i,1);plSave();drawMusic()}}
function plFromQueue(){if(!MP.st.q.length)return alert('La cola está vacía.');const n=prompt('Nombre de la lista','Mi cola');if(n===null)return;plAdd(n,MP.st.q.map(t=>t.id));drawMusic()}
function plMerge(){const sel=[...document.querySelectorAll('#shb .mg:checked')].map(e=>+e.value);if(sel.length<2)return alert('Elige al menos dos listas.');const mode=document.querySelector('#shb input[name=mm]:checked').value;
 const m=mergeLists(sel.map(plTracks),mode);plAdd($('#mgn').value||'Mezcla',m.map(t=>t.id));closeSh();drawMusic()}
// ---- hojas inferiores
function closeSh(){const d=$('#sh');if(d.open)d.close()}
function sheet(name,arg){const b=$('#shb');let h='';
 if(name==='eq'){const cu=Object.keys(EQV.custom||{});h=`<h3>Ecualizador</h3><div class=chips>`+Object.keys(EQ_PRESETS).concat(cu).map(n=>`<button class="pre" onclick="eqPreset('${esc(n)}')">${esc(n)}</button>`).join('')+`</div><div class=eqw>`+EQ_L.map((l,i)=>`<div class=eqc><input type=range orient=vertical min=-12 max=12 step=1 value="${EQV.g[i]||0}" oninput="MP.setEq(${i},this.value);this.nextElementSibling.textContent=this.value"><b>${EQV.g[i]||0}</b><small>${l}</small></div>`).join('')+`</div><div class=row><input type=text id=eqn placeholder="Nombre del preset" style="flex:1"><button class=s onclick="eqSave()">Guardar</button><button class="s bad" onclick="eqDel()">Borrar</button></div><small class=mut>Los cambios se guardan solos.</small>`}
 else if(name==='timer'){const l=MP.sleepLeft();h=`<h3>Temporizador</h3>${l?`<p class=mut>Se detiene en ${fmtT(l)}</p>`:''}<div class=chips>`+[5,10,15,30,45,60,90].map(m=>`<button class=pre onclick="MP.sleep(${m});closeSh()">${m} min</button>`).join('')+`<button class=pre onclick="MP.st.sleepEos=true;MP.sleep(0);MP.st.sleepEos=true;MP.ui(true);closeSh()">Al terminar la canción</button></div><div class=row><button class="bad" onclick="MP.st.sleepEos=false;MP.sleep(0);closeSh()">Desactivar</button></div><small class=mut>El volumen baja poco a poco los últimos 10 segundos.</small>`}
 else if(name==='queue'){const q=MP.st.q;h=`<h3>Cola (${q.length})</h3>`+(q.map((t,i)=>`<div class=res><div style="flex:1;min-width:0"><div class=t2 style="${i===MP.st.i?'color:var(--acc)':''}">${esc(t.title)}</div><small>${esc(t.artist)}</small></div><button class=s onclick="MP.queueMove(${i},${i-1});sheet('queue')">↑</button><button class=s onclick="MP.queueMove(${i},${i+1});sheet('queue')">↓</button><button class=s onclick="MP.playIndex(${i})">▶</button><button class="s bad" onclick="MP.queueRemove(${i});sheet('queue')">✕</button></div>`).join('')||'<span class=mut>Vacía</span>')+`<div class=row><button class=s onclick="plFromQueue()">Guardar como lista</button></div>`}
 else if(name==='viz'){h=`<h3>Visualizador 3D</h3><div class=chips>`+MP.VS.map((n,i)=>`<button class=pre onclick="P.vizSt=${i};saveMP();MP.vizLoop()">${n}</button>`).join('')+`</div><div class=lbl>Fotogramas por segundo</div><div class=chips>`+[20,30,45].map(f=>`<button class=pre onclick="P.vizFps=${f};saveMP()">${f}</button>`).join('')+`</div><div class=row><button class="neon" onclick="MP.toggleViz();closeSh()">${P.vizOn?'Desactivar':'Activar'} visualizador</button></div><small class=mut>Responde al audio real. A 20–30 fps gasta poca batería.</small>`}
 else if(name==='tags'){const t=MP.tr();if(!t||t.kind!=='t')return alert('Selecciona una canción de tu biblioteca para editar sus etiquetas.');h=`<h3>Editar etiquetas</h3><div id=tgf>`+[['title','Título',t.title],['artist','Artista',t.artist],['album','Álbum',t.album],['album_artist','Artista del álbum',''],['genre','Género',''],['date','Año',''],['track','Pista',t.no||''],['comment','Comentario','']].map(([k,l,v])=>`<div class=lbl>${l}</div><input type=text data-k="${k}" value="${esc(v==='Desconocido'?'':v)}">`).join('')+`</div><div class=row><button class="neon grow" onclick="tagSave('${t.id}')">Guardar en el archivo</button></div><div id=tgm class=mut></div><small class=mut>${t.writable?'Se reescribe el archivo sin recodificar el audio.':'Este archivo es de otra app: Android solo permite editarlo si añades su carpeta en Ajustes.'}</small>`}
 else if(name==='newpl'){h=`<h3>Nueva lista</h3><input type=text id=npn placeholder="Nombre"><div class=row><button class=neon onclick="plAdd($('#npn').value,[]);closeSh();drawMusic()">Crear vacía</button></div>`}
 else if(name==='merge'){h=`<h3>Combinar listas</h3>`+(PLS.length>1?PLS.map((p,i)=>`<label class=tg><span>${esc(p.name)}<small>${p.ids.length} canciones</small></span><input type=checkbox class=mg value="${i}"><i></i></label>`).join('')+`<div class=chips><label class=ch><input type=radio name=mm value=concat checked><span>Unir</span></label><label class=ch><input type=radio name=mm value=weave><span>Intercalar</span></label></div><input type=text id=mgn placeholder="Nombre de la nueva lista"><div class=row><button class=neon onclick="plMerge()">Combinar sin duplicados</button></div>`:'<span class=mut>Necesitas al menos dos listas.</span>')}
 else if(name==='edpl'){const p=PLS[arg];h=`<h3>${esc(p.name)}</h3><input type=text id=epn value="${esc(p.name)}" onchange="PLS[${arg}].name=this.value;plSave()">`+p.ids.map((id,i)=>{const t=TBY(id);return `<div class=res><div style="flex:1;min-width:0"><div class=t2>${esc(t?t.title:'(no disponible)')}</div><small>${t?esc(t.artist):''}</small></div><button class=s onclick="PLS[${arg}].ids=moveItem(PLS[${arg}].ids,${i},${i-1});plSave();sheet('edpl',${arg})">↑</button><button class=s onclick="PLS[${arg}].ids=moveItem(PLS[${arg}].ids,${i},${i+1});plSave();sheet('edpl',${arg})">↓</button><button class="s bad" onclick="PLS[${arg}].ids.splice(${i},1);plSave();sheet('edpl',${arg})">✕</button></div>`}).join('')+`<div class=row><button class=s onclick="PLS[${arg}].ids=smartShuffle(PLS[${arg}].ids.map(TBY).filter(Boolean),plays()).map(t=>t.id);plSave();sheet('edpl',${arg})">Mezclar inteligente</button></div>`}
 else if(name==='addpl'){h=`<h3>Añadir a una lista</h3>`+(PLS.map((p,i)=>`<div class=res><div style="flex:1">${esc(p.name)}</div><button class=s onclick="plPush(${i},'${arg}')">Añadir</button></div>`).join('')||'<span class=mut>Aún no tienes listas.</span>')+`<div class=row><input type=text id=npn placeholder="Nueva lista" style="flex:1"><button class="s neon" onclick="plAdd($('#npn').value,['${arg}']);closeSh()">Crear</button></div>`}
 else if(name==='row'){const t=TBY(arg);h=`<h3>${esc(t.title)}</h3><small class=mut>${esc(t.artist)}</small><div class=row><button class=neon onclick="playFrom('${arg}');closeSh()">▶ Reproducir</button><button onclick="MP.enqueue(TBY('${arg}'),true);MP.toast('Se reproducirá a continuación');closeSh()">A continuación</button><button onclick="MP.enqueue(TBY('${arg}'));MP.toast('Añadida a la cola');closeSh()">A la cola</button><button onclick="sheet('addpl','${arg}')">A una lista</button><button onclick="MP.playTracks([TBY('${arg}')],0);MP.open();setTimeout(()=>sheet('tags'),300)">Editar etiquetas</button></div>`}
 b.innerHTML=h;if(!$('#sh').open)$('#sh').showModal()}
const rowMenu=id=>sheet('row',id),plays=()=>{const o={};HIST.forEach(h=>{if(h.counted)o[h.id]=(o[h.id]||0)+1});return o};
function plPush(i,id){if(!PLS[i].ids.includes(id))PLS[i].ids.push(id);plSave();MP.toast('Añadida');closeSh()}
function eqPreset(n){const v=EQ_PRESETS[n]||(EQV.custom||{})[n];if(!v)return;v.forEach((x,i)=>MP.setEq(i,x));sheet('eq')}
function eqSave(){const n=($('#eqn').value||'').trim();if(!n)return;EQV.custom=EQV.custom||{};EQV.custom[n]=EQV.g.slice();LW('ytp_eq',EQV);sheet('eq')}
function eqDel(){const n=($('#eqn').value||'').trim();if(EQV.custom&&EQV.custom[n]){delete EQV.custom[n];LW('ytp_eq',EQV);sheet('eq')}}
async function tagSave(id){const o={id};document.querySelectorAll('#tgf input').forEach(e=>o[e.dataset.k]=e.value);$('#tgm').textContent='Guardando… (puede tardar unos segundos)';try{await post('/api/tags/write',o);$('#tgm').textContent='✓ Etiquetas guardadas en el archivo';setTimeout(loadMusic,1200)}catch(e){$('#tgm').textContent=e.message}}
// ---- descargas: pausar / reanudar
async function jobAct(id,a){try{await api('/api/jobs/'+id+'/'+a,{method:'POST'})}catch(e){}poll()}
// ---- temas
function theme(){const a=ACC[P.acc]||ACC[0],r=document.documentElement.style,b=document.body;let A=a[1],B=a[2];if(P.mode==='custom'){A=P.cA;B=P.cB}
 r.setProperty('--acc',A);r.setProperty('--acc2',B);if(P.mode==='custom')r.setProperty('--bg',P.cBg);else r.removeProperty('--bg');b.classList.toggle('light',P.mode==='light');b.classList.toggle('amoled',P.mode==='amoled');b.classList.toggle('rm',!!P.rm);
 const m=document.querySelector('meta[name=theme-color]');if(m)m.content=P.mode==='light'?'#f2f4fb':P.mode==='custom'?P.cBg:P.mode==='amoled'?'#000000':'#05060f'}
function setMP(k,v){P[k]=v;saveMP();theme();musSet()}
const gOpts=[['next','Siguiente'],['prev','Anterior'],['toggle','Play/Pausa'],['volup','Subir volumen'],['voldown','Bajar volumen'],['seekf','+10 s'],['seekb','−10 s'],['none','Nada']];
const gRows=[['gL','Deslizar ←'],['gR','Deslizar →'],['gU','Deslizar ↑'],['gD','Deslizar ↓'],['gDL','Doble toque izq.'],['gDR','Doble toque der.']];
function musSet(){if(!$('#v-xf'))return;$('#v-xf').textContent=P.xf?P.xf+' s':'Desactivado';$('#s-xf').value=P.xf;$('#s-norm').checked=!!P.norm;$('#v-nt').textContent=P.normT;$('#s-nt').value=P.normT;$('#v-nb').textContent=P.normB;$('#s-nb').value=P.normB;$('#s-gon').checked=!!P.gOn;$('#v-gm').textContent=P.gMin;$('#s-gm').value=P.gMin;
 $('#gmap').innerHTML=gRows.map(([k,l])=>`<div class=lbl>${l}</div><select onchange="setMP('${k}',this.value)">${gOpts.map(([v,t])=>`<option value=${v} ${P[k]===v?'selected':''}>${t}</option>`).join('')}</select>`).join('');
 $('#s-vfps').innerHTML=chips('vf',[[20,'20'],[30,'30'],[45,'45']],P.vizFps,'setVf');$('#s-mode').innerHTML=chips('tm',[['dark','Oscuro'],['light','Claro'],['amoled','AMOLED'],['custom','Personalizado']],P.mode,'setMode');
 $('#cust').hidden=P.mode!=='custom';$('#cA').value=P.cA;$('#cB').value=P.cB;$('#cBg').value=P.cBg;$('#s-ares').checked=S.autoResume!==false;
 $('#folders').innerHTML=MFOLD.length?MFOLD.map(f=>`<div class=res><div style="flex:1"><div class=t2>${esc(f.name)}</div></div><button class="s bad" onclick="rmFolder('${esc(f.uri).replace(/'/g,'&#39;')}')">Quitar</button></div>`).join(''):'<span class=mut>Ninguna carpeta añadida.</span>';
 const pa=window.Android&&Android.hasPerm?Android.hasPerm('audio'):false,pn=window.Android&&Android.hasPerm?Android.hasPerm('notif'):false;$('#perm').innerHTML=`Audio: ${pa?'✓ concedido':'<button class=s onclick="Android.reqAudioPerm()">Conceder</button>'} · Notificaciones: ${pn?'✓ concedido':'<button class=s onclick="Android.reqNotifPerm()">Conceder</button>'} · <button class=s onclick="Android.openAppSettings()">Ajustes de Android</button>`}
const setVf=v=>setMP('vizFps',+v),setMode=v=>setMP('mode',v);
{const _d=drawSet;drawSet=function(){_d();musSet()}}
// ---- arrastrar el mini reproductor
(()=>{const m=$('#mini');let st=null,moved=false;try{const p=LS('ytp_mini',null);if(p){m.style.left=p.x+'px';m.style.top=p.y+'px';m.style.bottom='auto';m.style.right='auto'}}catch(e){}
 m.addEventListener('pointerdown',e=>{if(e.target.closest('button'))return;const r=m.getBoundingClientRect();st={dx:e.clientX-r.left,dy:e.clientY-r.top,x:e.clientX,y:e.clientY};moved=false;m.setPointerCapture(e.pointerId)});
 m.addEventListener('pointermove',e=>{if(!st)return;if(Math.abs(e.clientX-st.x)+Math.abs(e.clientY-st.y)>10)moved=true;if(moved){const x=Math.max(0,Math.min(innerWidth-m.offsetWidth,e.clientX-st.dx)),y=Math.max(0,Math.min(innerHeight-m.offsetHeight,e.clientY-st.dy));m.style.left=x+'px';m.style.top=y+'px';m.style.bottom='auto';m.style.right='auto';m.style.margin='0'}});
 m.addEventListener('pointerup',e=>{if(!st)return;if(moved)LW('ytp_mini',{x:parseInt(m.style.left),y:parseInt(m.style.top)});else MP.open();st=null});
 MP.gesture(m);MP.gesture($('#mpst'));$('#mpvol').value=P.vol})();

// ---------- inicio
theme();drawDock();drawPre();engine();poll();setTimeout(()=>checkApp(false),1500);
(()=>{const q=new URLSearchParams(location.search),m=((q.get('u')||'')).match(/https?:\/\/\S+/);if(m){history.replaceState(null,'','/');$('#u').value=m[0];analyze()}})();
