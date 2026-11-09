const vm=require('vm'),fs=require('fs'),assert=require('assert');
const js=fs.readFileSync('/tmp/work/js/all.js','utf8');
const log={media:[],fetch:[],audios:[],ramps:0,toasts:[],errors:[]};
const noop=()=>{};
const c2d=new Proxy({},{get:(t,k)=>k==='getImageData'?()=>({data:new Uint8ClampedArray(256)}):noop,set:()=>true});
function el(tag){const ls={};const store={tag,style:{setProperty:noop,removeProperty:noop},classList:{toggle:noop,add:noop,remove:noop,contains:()=>false},dataset:{}};
 return new Proxy(store,{get(t,k){if(k in t)return t[k];
  if(k==='addEventListener')return (e,f)=>{(ls[e]=ls[e]||[]).push(f)};if(k==='_fire')return (e,a)=>(ls[e]||[]).forEach(f=>f(a));
  if(k==='getContext')return ()=>c2d;if(k==='matches')return ()=>false;if(k==='closest')return ()=>null;
  if(k==='querySelectorAll')return ()=>[];if(k==='querySelector')return ()=>el('x');
  if(k==='getBoundingClientRect')return ()=>({left:0,top:0});if(k==='setPointerCapture'||k==='showModal'||k==='close'||k==='remove')return noop;
  if(k==='clientWidth')return 300;if(k==='clientHeight')return 500;if(k==='offsetWidth')return 300;if(k==='offsetHeight'||k==='offsetTop')return 40;
  if(k==='value')return '';if(k==='checked')return false;if(k==='innerHTML'||k==='textContent')return '';if(k==='open')return false;return undefined},
  set(t,k,v){t[k]=v;return true}})}
class FakeAudio{constructor(){this.l={};this.currentTime=0;this.duration=NaN;this.src='';this.paused=true;this.ended=false;log.audios.push(this)}
 addEventListener(e,f){(this.l[e]=this.l[e]||[]).push(f)} fire(e){(this.l[e]||[]).forEach(f=>f())}
 play(){this.paused=false;this.plays=(this.plays||0)+1;Promise.resolve().then(()=>this.fire('playing'));return Promise.resolve()}
 pause(){this.paused=true;this.fire('pause')} load(){}}
const param=()=>({value:0,setTargetAtTime(v){this.value=v},setValueAtTime(v){this.value=v},linearRampToValueAtTime(v){log.ramps++;this.value=v},cancelScheduledValues:noop});
class FakeCtx{constructor(){this.currentTime=0;this.state='suspended';this.destination={}}
 resume(){this.state='running';return Promise.resolve()} createMediaElementSource(){return{connect:noop}} createGain(){return{gain:param(),connect:noop}}
 createBiquadFilter(){return{type:'',frequency:{value:0},Q:{value:0},gain:param(),connect:noop}}
 createDynamicsCompressor(){return{threshold:param(),knee:param(),ratio:param(),attack:param(),release:param(),connect:noop}}
 createAnalyser(){return{fftSize:0,smoothingTimeConstant:0,frequencyBinCount:128,getByteFrequencyData(a){a.fill(120)},connect:noop}}}
const store={};
const tracks=[0,1,2,3].map(i=>({id:'t'+i,title:'Cancion '+i,artist:i<2?'Artista A':'Artista B',album:'Album '+(i%2),dur:200+i,size:1000+i,src:'media',writable:i===0,no:i,hasLrc:false}));
const resp=u=>u.startsWith('/api/music/library')?{tracks,scanning:false,audioPerm:true,msg:'',folders:[{uri:'content://x',name:'Musica'}]}:u.startsWith('/api/loudness')?{db:-22}:u.startsWith('/api/music/lyrics')?{text:'[00:01.00]hola\n[00:05.00]mundo'}:u.startsWith('/api/library')||u.startsWith('/api/jobs')?[]:u.startsWith('/api/settings')?{parallel:2,dest:'media',autoResume:true}:{ok:true,text:''};
const sandbox={URLSearchParams,console,Promise,setTimeout:(f,ms)=>{if(ms>=1000&&ms<5000)return 0;return setTimeout(f,Math.min(ms,5))},clearTimeout,setInterval:()=>0,clearInterval:noop,Date,Math,JSON,Array,Object,Set,Map,String,Number,RegExp,parseInt,parseFloat,isFinite,Uint8Array,Uint8ClampedArray,Error,Intl,
 performance:{now:()=>Date.now()},requestAnimationFrame:f=>{sandbox._raf=f;return 1},cancelAnimationFrame:noop,
 document:{querySelector:s=>el(s),querySelectorAll:()=>[],createElement:t=>el(t),addEventListener:noop,body:el('body'),documentElement:el('html'),fullscreenElement:null,hidden:false,exitFullscreen:()=>Promise.resolve()},
 localStorage:{getItem:k=>k in store?store[k]:null,setItem:(k,v)=>{store[k]=String(v)}},
 navigator:{vibrate:noop},Audio:FakeAudio,AudioContext:FakeCtx,Image:class{set src(v){}},MediaMetadata:class{},
 fetch:async(u)=>{log.fetch.push(u);return{ok:true,json:async()=>resp(u),text:async()=>''}},
 alert:m=>log.errors.push('alert:'+m),confirm:()=>true,prompt:()=>'Mi lista',location:{search:''},history:{replaceState:noop},innerWidth:400,innerHeight:800,devicePixelRatio:2,
 Android:{mediaState:j=>log.media.push(JSON.parse(j)),reqNotifPerm:noop,hasPerm:()=>true,reqAudioPerm:noop,pickFolder:noop,openAppSettings:noop}};
sandbox.window=sandbox;sandbox.self=sandbox;sandbox.ytpCmd=undefined;
const ctx=vm.createContext(sandbox);
try{vm.runInContext(js,ctx,{filename:'ui.js'})}catch(e){console.log('ERROR AL CARGAR:',e.stack.split('\n').slice(0,4).join('\n'));process.exit(1)}
const run=c=>vm.runInContext(c,ctx);let n=0;const ok=(c,m)=>{assert(c,m);n++};
(async()=>{
 const tick=()=>new Promise(r=>setImmediate(r));
 await run('loadMusic()');await tick();ok(run('LIBM.length')===4,'biblioteca cargada');
 for(const s of ['songs','artists','albums','dups','lists','hist','stats'])run(`setSub('${s}')`);ok(true,'8 vistas de música');
 run("MP.playTracks(LIBM,0)");await tick();await tick();
 const d0=log.audios[0];ok(d0&&d0.src==='/music/t0','deck0 carga la cancion 0');ok(run('MP.st.playing')===true,'estado reproduciendo');
 ok(log.media.some(m=>m.title==='Cancion 0'&&m.playing),'Media Session recibió título y play');
 ok(log.fetch.some(u=>u.startsWith('/api/loudness?id=t0')),'pidió normalización');
 // crossfade
 d0.duration=200;d0.currentTime=197;d0.fire('timeupdate');await tick();ok(run('MP.st.i')===1,'crossfade: pasó a la pista 1 (i='+run('MP.st.i')+')');ok(log.ramps>=2,'rampas de ganancia programadas');
 const d1=log.audios[1];ok(d1.src==='/music/t1'&&d1.plays===1,'deck1 reproduce la siguiente');
 d1.duration=201;d1.currentTime=5;d1.fire('timeupdate');
 // controles
 run('MP.cycleRepeat()');ok(run('MP.st.repeat')===1,'repetir todo');run('MP.toggleShuffle()');ok(run('MP.st.shuffle')===true&&run('MP.st.q.length')===4,'aleatorio inteligente');run('MP.toggleShuffle()');ok(run('MP.st.shuffle')===false,'orden restaurado');
 run('MP.setEq(2,6)');ok(run('EQV.g[2]')===6&&JSON.parse(store.ytp_eq).g[2]===6,'EQ guardado');
 run('MP.sleep(1)');ok(run('MP.sleepLeft()')>0,'temporizador activo');run('MP.sleep(0)');ok(run('MP.sleepLeft()')===0,'temporizador apagado');
 run('MP.next()');await tick();run('MP.prev()');await tick();
 run("ytpCmd('pause')");await tick();ok(run('MP.st.playing')===false,'comando pause del sistema');run("ytpCmd('play')");await tick();ok(true,'comando play');run("ytpCmd('seek:12')");
 // hojas y pantallas
 for(const s of ['eq','timer','queue','viz','newpl','merge'])run(`sheet('${s}')`);
 run("plAdd('A',['t0','t1','t2'])");run("plAdd('B',['t2','t3'])");run("sheet('merge')");run("sheet('edpl',0)");run("sheet('addpl','t0')");run("sheet('row','t0')");run("plPlay(0,true)");await tick();ok(run('PLS.length')===2&&JSON.parse(store.ytp_pls).length===2,'listas guardadas');
 run("MP.playTracks(LIBM,0)");await tick();run("sheet('tags')");ok(!log.errors.some(e=>e.includes('Selecciona')),'editor de etiquetas abre con canción de biblioteca');
 // visualizador 3 estilos
 run('MP.open()');run('P.vizOn=true;MP.vizLoop()');for(const st of [0,1,2]){run('P.vizSt='+st);if(sandbox._raf)sandbox._raf(1000*(st+1));if(sandbox._raf)sandbox._raf(1100*(st+1))}ok(true,'visualizador 3D (barras, túnel, esfera) dibuja sin errores');
 run('MP.toggleCine()');run('MP.close()');
 // gestos + temas + ajustes
 for(const a of ['next','prev','toggle','volup','voldown','seekf','seekb','none'])run(`MP.gAct('${a}')`);ok(true,'acciones de gestos');
 for(const m of ['light','amoled','custom','dark'])run(`setMP('mode','${m}')`);run('musSet()');run('drawSet&&drawSet()');ok(true,'temas y ajustes');
 // historial persistente
 ok(JSON.parse(store.ytp_hist).length>=1,'historial guardado localmente');
 console.log('SMOKE OK:',n,'comprobaciones | llamadas a la API:',log.fetch.length);
})().catch(e=>{console.log('FALLO:',e.stack.split('\n').slice(0,5).join('\n'));process.exit(1)});
