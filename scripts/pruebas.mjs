// Pruebas de la app en Electron sin ventana y con perfiles temporales: no tocan los datos ni el llavero del usuario y no salen a internet.
// Uso: npm test (Linux, con desktop/node_modules instalado). Sale con 1 si algo falla.
import {spawn,execFileSync} from 'node:child_process';import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),app=path.join(root,'desktop'),enlace=path.join(app,'www');
const sleep=ms=>new Promise(r=>setTimeout(r,ms)),temporales=[],abiertas=[];let fallos=0;
const ok=(c,n,d)=>{console.log((c?'✓ ':'✗ ')+n+(c||d===undefined?'':' → '+JSON.stringify(d)));if(!c)fallos++};

async function abre(puerto,[w,h],movil=false){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'temvra-prueba-'));temporales.push(dir);
  /* La app sigue el idioma del sistema y las pruebas buscan sus textos en español: se fija el español aunque el equipo esté en otro idioma.
     No basta con --lang: en Linux LANGUAGE y LC_ALL mandan sobre él */
  const proc=spawn(path.join(app,'node_modules/.bin/electron'),[app,'--ozone-platform=headless','--password-store=basic','--host-rules=MAP * ~NOTFOUND',`--user-data-dir=${dir}`,`--remote-debugging-port=${puerto}`],{stdio:'ignore',env:{...process.env,LANGUAGE:'es_ES',LC_ALL:'es_ES.UTF-8',LANG:'es_ES.UTF-8'}});
  let t=null;for(let i=0;i<80&&!t;i++){await sleep(250);try{const l=await (await fetch(`http://127.0.0.1:${puerto}/json/list`)).json();t=l.find(x=>x.type==='page'&&/index\.html/.test(x.url))}catch(e){}}
  if(!t)throw new Error('la app no ha arrancado');
  const ws=new WebSocket(t.webSocketDebuggerUrl);await new Promise((r,k)=>{ws.onopen=r;ws.onerror=k});
  let id=0;const P=new Map(),errores=[];
  ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id&&P.has(m.id)){P.get(m.id)(m);P.delete(m.id)}if(m.method==='Runtime.exceptionThrown'){const d=m.params.exceptionDetails;errores.push((d.exception&&d.exception.description||d.text).split('\n')[0])}};
  const send=(method,params={})=>new Promise(r=>{const i=++id;P.set(i,r);ws.send(JSON.stringify({id:i,method,params}))});
  const ev=async x=>{const r=await send('Runtime.evaluate',{expression:x,awaitPromise:true,returnByValue:true});if(r.result.exceptionDetails)throw new Error(r.result.exceptionDetails.exception?.description||r.result.exceptionDetails.text);return r.result.result.value};
  const raton=(type,x,y,buttons=0,button='none')=>send('Input.dispatchMouseEvent',{type,x,y,button,buttons,clickCount:type==='mouseMoved'?0:1});
  const rueda=(x,y,dy,mods=0)=>send('Input.dispatchMouseEvent',{type:'mouseWheel',x,y,deltaX:0,deltaY:dy,modifiers:mods});
  /* mods: 2 = Ctrl, 8 = Mayús */
  const VK={'+':187,'-':189,'0':48,Delete:46,F2:113,ArrowUp:38,ArrowDown:40,Escape:27};
  const tecla=async(key,mods=0)=>{const code=VK[key]||key.toUpperCase().charCodeAt(0),p={key,code:key.length===1?(/[a-z]/i.test(key)?'Key'+key.toUpperCase():/[0-9]/.test(key)?'Digit'+key:key==='+'?'Equal':'Minus'):key,modifiers:mods,windowsVirtualKeyCode:code,nativeVirtualKeyCode:code};
    await send('Input.dispatchKeyEvent',Object.assign({type:'rawKeyDown'},p));await send('Input.dispatchKeyEvent',Object.assign({type:'keyUp'},p))};
  await send('Runtime.enable');await send('Page.enable');await send('Emulation.setDeviceMetricsOverride',{width:w,height:h,deviceScaleFactor:1,mobile:movil});
  const listo=async()=>{for(let i=0;i<60;i++){try{if(await ev(`typeof VTAPI==='object'&&!!document.querySelector('.nav [data-view=cal]')`))return}catch(e){}await sleep(150)}throw new Error('la app no termina de cargar')};
  const recarga=async()=>{await send('Page.reload');await sleep(400);await listo();await sleep(500)};
  const a={ev,raton,rueda,tecla,send,errores,recarga,cierra(){try{ws.close()}catch(e){}proc.kill()}};abiertas.push(a);await listo();await sleep(700);return a}

const agenda=`(async()=>{const a=document.querySelector('.nav [data-view=cal]');if(a.getAttribute('aria-current')!=='page')a.click();await new Promise(r=>setTimeout(r,1000));document.querySelector('#cal-vseg [data-cv=month]').click();await new Promise(r=>setTimeout(r,1300))})()`;
const toque=`document.querySelector('#cal-vseg [aria-pressed="false"]').click()`;

async function vacio(a,pc){
  const r=JSON.parse(await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state(),v=[];for(const n of ['time','notes','reg','cal']){document.querySelector('.nav [data-view='+n+']').click();await w(800)}if(${pc})for(const c of ['day','3d','week','month','year','sched','month']){document.querySelector('#cal-vseg [data-cv="'+c+'"]').click();await w(1250);v.push(c)}
    const panel=document.body.classList.contains('deskcal');document.querySelector('.nav [data-view=time]').click();await w(600);document.querySelector('#tabs .tab[data-tab=circ]').click();await w(600);
    return JSON.stringify({porDefecto:{categorias:S.blockCats.length,registros:S.regs.length,pasos:S.circuit.steps.length,plantillas:document.querySelectorAll('#tpl [data-tpl]').length,festivos:!!S.calShow.hol},vistas:v.length,creados:['notes','folders','atts','events','appts','timers','tfolders','myCircuits','myTpl','alarms','habits','dayTpls','log','trash','cities'].filter(k=>S[k]&&S[k].length),imgs:Object.keys(localStorage).filter(k=>/img_/.test(k)).length,panel})})()`));
  ok(!r.creados.length,'perfil vacío: ningún elemento creado',r.creados);ok(!r.imgs,'perfil vacío: ninguna imagen',r.imgs);const d=r.porDefecto;ok(!d.categorias&&!d.registros&&!d.pasos&&!d.plantillas&&!d.festivos,'perfil vacío: nada creado por defecto',d);return r}

/* La visita guiada de la primera vez tapa la pantalla: las pulsaciones reales le llegarían a ella */
async function sinVisita(a){await a.ev(`(async()=>{VTAPI.state().tourDone=1;${toque};await new Promise(r=>setTimeout(r,500))})()`);await a.recarga()}

async function centrar(a){
  await a.ev(agenda);
  const r=JSON.parse(await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),g=document.getElementById('cal-grid'),m=()=>g.style.gridTemplateRows,i=m();document.getElementById('cal-next').click();await w(1000);document.getElementById('cal-next').click();await w(1000);document.getElementById('cal-nowb').click();await w(1500);return JSON.stringify({antes:i,despues:m()})})()`));
  const fila=t=>+t.match(/repeat\(\d+,\s*(\d+)px/)[1];ok(Math.abs(fila(r.antes)-fila(r.despues))<=3,'calendario: al avanzar meses y centrar conserva la altura',r)}

/* El aro de arcoíris que se ve dentro de la esfera es el halo curvado: el recorte junto a un borde no puede llegar a él */
async function aro(a,vertical){
  const [cx,cy]=await a.ev(`(()=>{const r=document.querySelector('.nav-in button[data-view=cal]').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()`);
  await a.raton('mouseMoved',cx,cy);await a.raton('mousePressed',cx,cy,1,'left');await sleep(900);
  const r=await a.ev(`(()=>{const g=document.querySelector('.lg-hglow');if(!g)return null;const R=parseFloat(g.style.getPropertyValue('--hR')),c=(g.style.clipPath.match(/-?[\\d.]+px|0(?= |\\))/g)||[]).map(parseFloat);return {R,c,esfera:g.classList.contains('sph')}})()`);
  ok(r&&r.esfera,'gota: al pulsar se forma la esfera',r);
  if(r){const anillo=r.R*.26-.01,cortes=r.c.filter(v=>v>-anillo);ok(!cortes.length,'gota: el recorte del halo no corta el aro',r)}
  const sc=await a.ev(`(()=>{const L=document.querySelector('.nav-in .lg-lens');return {will:getComputedStyle(L).willChange,tr:L.style.transform}})()`);ok(sc.will==='auto'&&!/3d/.test(sc.tr),'gota: sin capa propia que deforme los iconos de al lado',sc);
  /* Se suelta tras moverla, para que no cuente como tocar la pestaña activa */
  const dx=vertical?0:-14,dy=vertical?-14:0;await a.raton('mouseMoved',cx+dx,cy+dy,1,'left');await sleep(50);await a.raton('mouseMoved',cx,cy,1,'left');await sleep(50);await a.raton('mouseReleased',cx,cy,0,'left');await sleep(800)}

async function guardado(a){
  const idb=`new Promise((ok,ko)=>{const r=indexedDB.open('vt-state',1);r.onupgradeneeded=()=>r.result.createObjectStore('s');r.onsuccess=()=>{const q=r.result.transaction('s').objectStore('s').get('state');q.onsuccess=()=>{ok(q.result?q.result.length:0);r.result.close()};q.onerror=()=>ko(q.error)};r.onerror=()=>ko(r.error)})`;
  await a.ev(agenda);
  await a.ev(`(async()=>{const t='x'.repeat(1<<19);for(let n=0;n<400;n++){try{localStorage.setItem('vt_lleno_'+n,t)}catch(e){break}}const p='x'.repeat(4096);for(let m=0;m<600;m++){try{localStorage.setItem('vt_llenop_'+m,p)}catch(e){break}}const S=VTAPI.state();S.pruebaMarca='uno';S.pruebaRelleno='y'.repeat(4e5);${toque};await new Promise(r=>setTimeout(r,1600))})()`);
  const r=await a.ev(`(async()=>({bandera:localStorage.getItem('vt2_stidb'),copia:localStorage.getItem('vt2_state')!==null,idb:await ${idb}}))()`);
  ok(r.bandera==='1'&&!r.copia&&r.idb>4e5,'guardado: si no cabe en localStorage pasa a IndexedDB',r);
  await a.ev(`(async()=>{VTAPI.state().pruebaMarca2='dos';${toque};await new Promise(r=>setTimeout(r,800))})()`);
  await a.recarga();
  const s=await a.ev(`({m:VTAPI.state().pruebaMarca,m2:VTAPI.state().pruebaMarca2,r:(VTAPI.state().pruebaRelleno||'').length})`);
  ok(s.m==='uno'&&s.m2==='dos'&&s.r===4e5,'guardado: los datos siguen ahí al reiniciar',s);
  await a.ev(`(()=>{for(let i=localStorage.length-1;i>=0;i--){const k=localStorage.key(i);if(k.startsWith('vt_llen'))localStorage.removeItem(k)}})()`)}

async function token(a){
  await a.ev(`(async()=>{const S=VTAPI.state();S.gh=Object.assign(S.gh||{links:{}},{token:'de_prueba',login:'qa'});${toque};await new Promise(r=>setTimeout(r,700))})()`);
  await a.recarga();
  ok(await a.ev(`VTAPI.state().gh.token==='de_prueba'`),'token: sin llavero en el sistema se conserva como antes')}

/* Encabezado del día en una nota que ya existe: se pide desde el menú ⋮ de la nota abierta, va tras las propiedades, con la agenda de ese día fija, y no se repite */
async function encabezado(a){
  await a.ev(`(async()=>{VTAPI.state().notes.unshift({id:'nqa1',title:'Prueba',folder:'',pinned:false,created:Date.now(),updated:Date.now(),body:'---\\ntags: qa\\n---\\nTexto de prueba para seleccionar con el ratón. Es una línea larga que ocupa buena parte del ancho de la nota.\\n\\nOtro párrafo con más texto.\\n'});${toque};await new Promise(r=>setTimeout(r,500))})()`);
  await a.recarga();
  await a.ev(`(async()=>{document.querySelector('.nav [data-view=notes]').click();await new Promise(r=>setTimeout(r,900));const b=document.querySelector('[data-note="nqa1"],[data-id="nqa1"],[data-nid="nqa1"]');b&&b.click();await new Promise(r=>setTimeout(r,900))})()`);
  const hoy=`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms));document.getElementById('ne-more').click();await w(600);const m=[...document.querySelectorAll('#ob-sheet button')].find(x=>x.textContent.includes('Encabezado del día'));if(!m)return false;m.click();await w(600);const b=[...document.querySelectorAll('.sheet-back:not([hidden]) button')].find(x=>/^Hoy/.test(x.textContent.trim()));b&&b.click();await w(800);return !!b})()`;
  ok(await a.ev(hoy),'notas: «Encabezado del día» está en el menú ⋮ de la nota abierta');
  const r=await a.ev(`(()=>{const n=VTAPI.state().notes.find(x=>x.id==='nqa1'),p=v=>String(v).padStart(2,'0'),d=new Date(),k=d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate()),b=document.querySelector('#ne-read .agenda-blk');return {tras:/^---\\ntags: qa\\n---\\n# \\S/.test(n.body),agenda:n.body.includes('\`\`\`agenda '+k),bloque:!!b&&b.dataset.k===k}})()`);
  ok(r.tras&&r.agenda&&r.bloque,'notas: encabezado del día tras las propiedades, con la agenda de ese día',r);
  await a.ev(hoy);
  ok(await a.ev(`(VTAPI.state().notes.find(x=>x.id==='nqa1').body.match(/^# /gm)||[]).length===1`),'notas: el encabezado del mismo día no se repite')}

/* Dentro de la nota abierta no sale la burbuja: ni sobre el texto, ni al seleccionarlo, ni en los huecos. En la lista de carpetas y en la barra de formato sí (sigue en la nota abierta por encabezado()) */
async function burbujaNota(a){
  const p=await a.ev(`(()=>{const ps=[...document.querySelectorAll('#ne-read p')],q=ps.find(x=>/Texto de prueba/.test(x.textContent)),q2=ps.find(x=>/Otro párrafo/.test(x.textContent)).getBoundingClientRect(),r=document.createRange();r.selectNodeContents(q.firstChild);const b=r.getClientRects()[0],c=document.getElementById('ne-read').getBoundingClientRect(),f=document.querySelector('[data-note="nqa1"]').getBoundingClientRect();return {tx:b.left+40,ty:b.top+b.height/2,vx:c.right-30,vy:q2.bottom+60,fx:f.left+f.width/2,fy:f.top+f.height/2}})()`);
  const huecos=()=>a.ev(`document.querySelectorAll('.lg-hole').length`),pulsa=async(x,y)=>{await a.raton('mouseMoved',x,y);await a.raton('mousePressed',x,y,1,'left');await sleep(350);const n=await huecos();await a.raton('mouseReleased',x,y,0,'left');await sleep(800);return n};
  await a.raton('mouseMoved',p.tx,p.ty);await a.raton('mousePressed',p.tx,p.ty,1,'left');await sleep(350);const enTexto=await huecos();
  for(let i=1;i<=6;i++){await a.raton('mouseMoved',p.tx+i*25,p.ty,1,'left');await sleep(30)}await sleep(250);const arrastrando=await huecos(),sel=await a.ev(`getSelection().toString().length`);
  await a.raton('mouseReleased',p.tx+150,p.ty,0,'left');await sleep(600);await a.ev(`getSelection().removeAllRanges()`);
  const hueco=await pulsa(p.vx,p.vy),lista=await pulsa(p.fx,p.fy);
  const t=await a.ev(`(async()=>{if(document.getElementById('ne-edit').hidden){document.getElementById('ne-mode').click();await new Promise(r=>setTimeout(r,500))}const b=document.querySelector('#ne-tools [data-tool=undo]').getBoundingClientRect();return [b.left+b.width/2,b.top+b.height/2]})()`);
  const barra=await pulsa(t[0],t[1]);
  ok(!enTexto&&!arrastrando&&sel>0&&!hueco,'notas: sin burbuja dentro de la nota abierta, ni al pulsar ni al seleccionar texto',{enTexto,arrastrando,sel,hueco});
  ok(lista===1&&barra===1,'notas: la burbuja sigue en la lista de carpetas y en la barra de formato',{lista,barra})}

/* «Fecha de hoy» de la barra de formato y la fecha automática de una nota (sigue en la nota abierta) */
async function fechaHoy(a){
  const r=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state(),n=S.notes.find(x=>x.id==='nqa1'),ta=document.getElementById('ne-text'),hoja=document.getElementById('ob-sheet'),edita=async e=>{if(document.getElementById('ne-edit').hidden===e){document.getElementById('ne-mode').click();await w(450)}},fecha=()=>{document.querySelector('#ne-tools [data-tool=date]').click();return w(500)},
      boton=t=>hoja.hidden?null:[...hoja.querySelectorAll('button')].find(x=>x.textContent.includes(t)),pulsa=async t=>{const b=boton(t);if(b){b.click();await w(500)}return !!b},alFinal=()=>{ta.focus();ta.setSelectionRange(ta.value.length,ta.value.length)},
      limpia=async()=>{hoja.hidden=true;await edita(false);n.body='Uno\\nDos\\n';delete n.dayAuto;delete n.daySkip;delete n.dayAsked;delete S.dayAskOff;await edita(true)};
    await limpia();
    const orden=[...document.querySelectorAll('#ne-tools [data-tool]')].slice(0,4).map(b=>b.dataset.tool).join(',');
    alFinal();await fecha();const enc=/^Uno\\nDos\\n## \\S[^\\n]*\\d{4}\\n$/.test(ta.value),pregunta=!!boton('Sí, cada día');
    await pulsa('No, solo cuando');const dijoNo=n.dayAsked===1&&!n.dayAuto&&hoja.hidden&&document.activeElement===ta;
    ta.setSelectionRange(1,1);await fecha();const dentro=/^U[^#\\n]+\\d{4}no\\n/.test(ta.value),dentroSinHoja=hoja.hidden;
    alFinal();await fecha();const noRepite=hoja.hidden&&(ta.value.match(/^## /gm)||[]).length===2;
    await limpia();alFinal();await fecha();await pulsa('No volver a preguntar');const nunca=S.dayAskOff===1&&!n.dayAsked&&!n.dayAuto;alFinal();await fecha();const nuncaSinHoja=hoja.hidden;
    await limpia();alFinal();await fecha();await pulsa('Sí, cada día');const dijoSi=n.dayAuto===true&&(ta.value.match(/^## /gm)||[]).length===1;
    document.getElementById('ne-more').click();await w(600);let m=boton('Fecha automática cada día');const marcada=!!(m&&m.querySelector('.obg-ck'));if(m){m.click();await w(600)}const quitada=!n.dayAuto;
    await limpia();await edita(false);n.body='Uno\\nDos\\n';
    document.getElementById('ne-more').click();await w(600);m=boton('Fecha automática cada día');if(!m)return {menu:false};const sinMarca=!m.querySelector('.obg-ck');m.click();await w(600);
    await edita(true);const enEditor=/^Uno\\nDos\\n\\n## \\S[^\\n]*\\n$/.test(ta.value),sinGuardar=n.body==='Uno\\nDos\\n';
    ta.focus();ta.setSelectionRange(0,0);document.execCommand('insertText',false,'X');await w(700);const corregir=n.body==='XUno\\nDos\\n';
    ta.setSelectionRange(ta.value.length,ta.value.length);document.execCommand('insertText',false,'hoy');await w(700);const escrito=/^XUno\\nDos\\n\\n## \\S[^\\n]*\\nhoy$/.test(n.body);
    await edita(false);await edita(true);const unaVez=(ta.value.match(/^## /gm)||[]).length===1;
    await edita(false);delete n.dayAuto;delete n.dayAsked;return {menu:true,orden,enc,dentro,pregunta,dijoNo,dentroSinHoja,noRepite,nunca,nuncaSinHoja,dijoSi,marcada,quitada,sinMarca,enEditor,sinGuardar,corregir,escrito,unaVez}})()`);
  ok(r.orden==='undo,redo,head,date'&&r.enc&&r.dentro,'notas: «Fecha de hoy» está al principio de la barra; en una línea vacía es un encabezado y dentro de un texto va tal cual',r);
  ok(r.pregunta&&r.dijoNo&&r.dentroSinHoja&&r.noRepite&&r.nunca&&r.nuncaSinHoja,'notas: al poner una fecha pregunta si se quiere cada día; con «No» no insiste en esa nota y se puede callar del todo',r);
  ok(r.dijoSi&&r.marcada&&r.quitada&&r.sinMarca,'notas: con «Sí, cada día» queda activada sin repetir la fecha, y el menú ⋮ la marca y la quita',r);
  ok(r.menu&&r.enEditor&&r.sinGuardar&&r.corregir,'notas: la fecha automática aparece al editar y no se guarda mientras no se escriba debajo',r);
  ok(r.escrito&&r.unaVez,'notas: al escribir debajo se guarda con su fecha, una sola vez por día',r)}

/* El tamaño de letra de los widgets viaja en sus datos; el ajuste solo sale en Android */
async function letraWidgets(a){
  const r=await a.ev(`(async()=>{const s=document.getElementById('wfs-sel'),antes=VTAPI.widgetData().fs;s.value='1.3';s.dispatchEvent(new Event('change'));await new Promise(r=>setTimeout(r,300));const con=VTAPI.widgetData().fs;s.value='1';s.dispatchEvent(new Event('change'));return {antes,con,oculto:document.getElementById('wfs-row').hidden}})()`);
  ok(r.antes===1&&r.con===1.3&&r.oculto,'widgets: el tamaño de letra elegido llega a sus datos y el ajuste solo sale en Android',r)}

/* En el móvil los emojis de una categoría caben todos en la hoja (con 10 columnas fijas los últimos se salían). Sigue al bloque creado por bloque() */
async function emojis(a){
  const r=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),b=document.querySelector('#v-blocks .bk-b');if(!b)return null;b.click();await w(800);const nv=document.querySelector('#bks-cats [data-bks="_new"]');if(!nv)return null;nv.click();await w(800);const g=document.querySelector('.cat-em');if(!g)return null;
    const R=g.getBoundingClientRect(),cs=[...g.querySelectorAll('button')].map(x=>x.getBoundingClientRect()),o={total:cs.length,fuera:cs.filter(c=>c.right>Math.min(R.right,innerWidth)+.5||c.left<R.left-.5).length,min:Math.round(Math.min(...cs.map(c=>c.width)))};document.getElementById('ob-sheet').hidden=true;document.getElementById('bk-sheet').hidden=true;return o})()`);
  ok(r&&r.total===20&&!r.fuera&&r.min>=40,'móvil: los emojis de la categoría caben en la hoja',r)}

/* Un aviso que sale tarde (PC dormido, app cerrada) dice cuánto hace que empezó, no lo que faltaba */
async function avisoTarde(a){
  const r=await a.ev(`(async()=>{const S=VTAPI.state(),p=v=>String(v).padStart(2,'0'),st=new Date(Date.now()-20*6e4);st.setSeconds(0,0);const hm=d=>p(d.getHours())+':'+p(d.getMinutes()),dk=d=>d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate());
    S.events.push({id:'eqa2',kind:'event',title:'Reunión',date:dk(st),start:hm(st),end:hm(new Date(st.getTime()+36e5)),allDay:false,remind:30,done:false});await new Promise(r=>setTimeout(r,2500));
    return {texto:document.getElementById('bn-sub').textContent,visible:!document.getElementById('banner').hidden,fila:document.getElementById('avrf-row').hidden}})()`);
  ok(r.visible&&/^Hace 2[01] min · a las \d\d:\d\d$/.test(r.texto),'avisos: si salen tarde dicen cuánto hace que empezó',r);
  ok(r.fila,'avisos: «Actualizar avisos cada» solo sale en Android')}

/* Sincronizar con GitHub varias veces seguidas. scripts/github-falso.js hace de GitHub y de caché del navegador. Antes, una segunda
   sincronización en menos de un minuto leía la rama de la caché, partía de un estado viejo y GitHub la rechazaba («422 · Update is not
   a fast forward»); y la comprobación periódica volvía a traer la versión anterior de la nota recién subida */
async function sincroniza(a){
  await a.ev(fs.readFileSync(path.join(root,'scripts/github-falso.js'),'utf8'));
  const r=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state(),G=window.__gh,vn=document.getElementById('vn-sync'),ap=document.getElementById('ap-now'),t=document.getElementById('toast'),avisos=[];await G.listo;
    const mo=new MutationObserver(()=>{if(!t.hidden&&t.textContent&&avisos[avisos.length-1]!==t.textContent)avisos.push(t.textContent)});mo.observe(t,{childList:true,characterData:true,subtree:true,attributes:true});
    const quieto=async()=>{for(let k=0,q=0;k<200&&q<4;k++){await w(100);q=!vn.classList.contains('spin')&&!window.__apBusy?q+1:0}},notas=async()=>{await quieto();vn.click();await w(200);await quieto()},agenda=async()=>{await quieto();ap.disabled=false;ap.click();await w(200);await quieto()};
    if(!document.getElementById('ne-edit').hidden){document.getElementById('ne-mode').click();await w(400)}
    S.gh.token='de_prueba';S.gh.links=S.gh.links||{};S.gh.links[S.curVault]={repo:'qa/boveda',branch:'main'};
    const n=S.notes.find(x=>x.id==='nqa1'),o={};
    await notas();o.primera=G.texto('Prueba.md')===n.body;
    await agenda();o.errAgenda=S.gh.app.err;o.agenda=o.errAgenda===''&&G.archivos().some(p=>p.startsWith('.temvra/'))&&S.gh.app.head===G.head;
    n.body+='\\nSegunda subida.\\n';n.updated=Date.now();await notas();o.segunda=G.texto('Prueba.md')===n.body;
    /* La comprobación periódica (aquí, al volver a la app) no debe traer de vuelta la versión anterior */
    const cuerpo=n.body;Object.defineProperty(document,'hidden',{get:()=>false,configurable:true});
    document.dispatchEvent(new Event('visibilitychange'));await w(900);await quieto();await w(600);await quieto();delete document.hidden;
    o.sinVueltaAtras=n.body===cuerpo&&G.texto('Prueba.md')===cuerpo;
    /* Otro dispositivo sube algo justo mientras este sube una nota: se repite sobre lo nuevo, sin volver a subir el archivo */
    const antes=G.log.length;G.alCommit=()=>G.ajeno('.otros/ajeno.txt','de otro dispositivo');n.body+='\\nTercera.\\n';n.updated=Date.now();await notas();
    const tramo=G.log.slice(antes);o.carrera=G.texto('Prueba.md')===n.body&&G.texto('.otros/ajeno.txt')==='de otro dispositivo';o.rechazos=tramo.filter(x=>x.m==='PATCH'&&x.st===422).length;o.blobs=tramo.filter(x=>x.m==='POST'&&x.ruta==='/git/blobs').length;
    o.conCache=G.log.filter(x=>x.m==='GET'&&x.modo!=='no-store').length;o.viejas=G.log.filter(x=>x.cache).length;o.copias=S.notes.filter(x=>/\\(GitHub\\)/.test(x.title)).length;
    mo.disconnect();delete S.gh.links[S.curVault];G.quita();o.avisos=avisos.filter(x=>/GitHub|Sincroniz/.test(x)).slice(-12);return o})()`);
  ok(r.primera&&r.agenda&&r.segunda,'sincronizar: notas, agenda y otra vez notas en el mismo minuto suben bien',r);
  ok(r.sinVueltaAtras&&!r.copias,'sincronizar: la comprobación periódica no trae de vuelta la versión anterior de una nota recién subida',r);
  ok(r.carrera&&r.rechazos===1&&r.blobs===1,'sincronizar: si otro dispositivo sube a la vez, se repite sobre lo nuevo sin volver a subir el archivo',r);
  ok(!r.conCache&&!r.viejas&&!r.avisos.some(x=>/422|fast forward/i.test(x)),'sincronizar: las lecturas a GitHub van sin caché y no sale el error 422',r)}

/* Notas en el PC: ordenar arrastrando con orden alfabético (el nivel pasa a orden propio), una carpeta abierta arrastra su contenido,
   Ctrl+Z / Ctrl+Y deshacen y rehacen, F2 y Supr, pegar texto en un hueco crea una nota sin nombre resaltada, soltar archivos desde fuera
   lleva a Notas y los mete en la carpeta de debajo, y una nota arrastrada fuera de la ventana sale como archivo */
const NBASE=`const t=Date.now();S.folders=['Alfa','Beta','Gamma'];S.notes=[];const add=(id,title,folder)=>S.notes.push({id,title,folder,pinned:false,created:t-S.notes.length*1000,updated:t-S.notes.length*1000,body:'Texto de '+title});
  add('k1','Uno','Alfa');add('k2','Dos','Alfa');add('k3','Tres','Beta');add('k5','Cinco','Gamma');add('k6','Seis','');add('k7','Siete','');S.atts=[];
  S.obOpen={Alfa:true,Beta:true,Gamma:true};S.obPanel='files';S.obOrder={};S.obFolderSort={};S.obSort='az';S.obFSort='az';`;
async function notasPC(a){
  /* Con dos columnas (menos de 1100 px) las notas están en el árbol; al final se prueba también la columna central de tres */
  await a.send('Emulation.setDeviceMetricsOverride',{width:1000,height:800,deviceScaleFactor:1,mobile:false});await sleep(300);
  /* El aviso de avisoTarde() tapa las primeras filas: se quita con su evento */
  await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state();${NBASE};S.events=S.events.filter(e=>e.id!=='eqa2');document.getElementById('banner').hidden=true;document.querySelector('.nav [data-view=cal]').click();await w(400);document.querySelector('.nav [data-view=notes]').click();await w(800);if(!document.getElementById('noteview').hidden)document.getElementById('ne-close').click();await w(300)})()`);
  const tapa=await a.ev(`(()=>{const r=document.querySelector('#ob-list [data-folder="Alfa"]').getBoundingClientRect(),e=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return e&&e.closest('#ob-list')?'':(e.id||e.className||e.tagName)})()`);if(tapa)console.log('  (algo tapa la lista:',tapa,')');
  const filas=()=>a.ev(`[...document.querySelectorAll('#ob-list .ob-row')].map(r=>r.dataset.folder?'['+r.dataset.folder+']':(VTAPI.state().notes.find(n=>n.id===r.dataset.note)||{}).title).join(' ')`);
  const centro=sel=>a.ev(`(()=>{const r=document.querySelector(${JSON.stringify(sel)}).getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,r.height]})()`);
  const arrastra=async([x0,y0],[x1,y1],medio)=>{await a.raton('mouseMoved',x0,y0);await a.raton('mousePressed',x0,y0,1,'left');await sleep(80);let m=null;for(let i=1;i<=14;i++){await a.raton('mouseMoved',x0+(x1-x0)*i/14,y0+(y1-y0)*i/14,1,'left');await sleep(45);if(i===12&&medio)m=await a.ev(medio)}await sleep(300);await a.raton('mouseReleased',x1,y1,0,'left');await sleep(800);return m};
  const lista=()=>a.ev(`document.getElementById('ob-list').focus()`),aviso=()=>a.ev(`document.getElementById('toast').textContent`);
  const ini=await filas();let u=await centro('#ob-list [data-note="k1"]'),d=await centro('#ob-list [data-note="k2"]');await arrastra([d[0],d[1]],[u[0],u[1]+u[2]*.45]);
  const r1={ini,fin:await filas(),modo:await a.ev(`VTAPI.state().obFolderSort.Alfa`)};
  ok(r1.ini.startsWith('[Alfa] Dos Uno')&&r1.fin.startsWith('[Alfa] Uno Dos')&&r1.modo==='custom','notas: con orden alfabético se ordena arrastrando y la carpeta pasa a orden propio',r1);
  const f=await centro('#ob-list [data-folder="Alfa"]'),g=await centro('#ob-list [data-folder="Gamma"]');
  const medio=await arrastra([f[0],f[1]],[g[0],g[1]+g[2]*.9],`(()=>{const y=s=>document.querySelector(s).getBoundingClientRect().top;return {carpeta:y('#ob-list [data-folder="Alfa"]'),dentro:y('#ob-list [data-note="k1"]'),beta:y('#ob-list [data-folder="Beta"]')}})()`);
  const r2={medio,fin:await filas()};
  ok(r2.medio.dentro>r2.medio.carpeta&&r2.medio.carpeta>r2.medio.beta&&r2.fin==='[Beta] Tres [Gamma] Cinco [Alfa] Uno Dos Seis Siete','notas: una carpeta abierta se mueve con todo su contenido',r2);
  await lista();await a.tecla('z',2);await sleep(500);const z1=await filas(),az1=await aviso();await a.tecla('y',2);await sleep(500);const y1=await filas();await a.tecla('z',2);await sleep(400);
  ok(z1.startsWith('[Alfa] Uno Dos [Beta]')&&/^Deshecho: /.test(az1)&&y1.startsWith('[Beta]'),'notas: Ctrl+Z y Ctrl+Y deshacen y rehacen lo hecho con carpetas',{z1,az1,y1});
  /* Hueco vacío + pegar texto */
  const h=await a.ev(`(()=>{const L=document.getElementById('ob-list'),r=L.getBoundingClientRect(),rows=[...L.querySelectorAll('.ob-row')],b=rows[rows.length-1].getBoundingClientRect();return [r.left+r.width/2,Math.min(r.bottom-8,b.bottom+40)]})()`);
  await a.raton('mouseMoved',h[0],h[1]);await a.raton('mousePressed',h[0],h[1],1,'left');await a.raton('mouseReleased',h[0],h[1],0,'left');await sleep(300);
  const r3=await a.ev(`(async()=>{const o={foco:document.activeElement.id};const dt=new DataTransfer();dt.setData('text/plain','Pegado desde fuera');document.activeElement.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));await new Promise(r=>setTimeout(r,600));
    const n=VTAPI.state().notes.find(x=>x.body==='Pegado desde fuera');o.titulo=n?n.title:null;o.resaltada=!!(n&&document.querySelector('#ob-list [data-note="'+n.id+'"].ob-new'));return o})()`);
  ok(r3.foco==='ob-list'&&r3.titulo===''&&r3.resaltada,'notas: un clic en un hueco y Ctrl+V con texto crea una nota sin nombre, resaltada',r3);
  await a.tecla('F2');await sleep(300);const f2=await a.ev(`!!document.getElementById('ob-edit')`);await a.ev(`(()=>{const e=document.getElementById('ob-edit');if(e){e.value='Pegada';e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}))}})()`);await sleep(400);
  await a.ev(`document.querySelector('#ob-list [data-note="k7"]').focus()`);const s=await centro('#ob-list [data-note="k7"]');await a.raton('mouseMoved',s[0],s[1]);await a.raton('mousePressed',s[0],s[1],1,'left');await a.raton('mouseReleased',s[0],s[1],0,'left');await sleep(600);
  await a.ev(`(()=>{if(!document.getElementById('noteview').hidden)document.getElementById('ne-close').click();const r=document.querySelector('#ob-list [data-note="k7"]');if(r)r.focus()})()`);await sleep(300);
  await a.tecla('Delete');await sleep(500);const r4=await a.ev(`(async()=>{const b=[...document.querySelectorAll('.sheet-back:not([hidden]) button')].find(x=>/Eliminar/.test(x.textContent));if(b){b.click();await new Promise(r=>setTimeout(r,400))}return {renombrada:VTAPI.state().notes.some(x=>x.title==='Pegada'),borrada:!VTAPI.state().notes.some(x=>x.id==='k7')}})()`);
  await lista();await a.tecla('z',2);await sleep(500);r4.vuelve=await a.ev(`VTAPI.state().notes.some(x=>x.id==='k7')`);r4.f2=f2;
  ok(r4.f2&&r4.renombrada&&r4.borrada&&r4.vuelve,'notas: F2 renombra, Supr borra y Ctrl+Z lo devuelve',r4);
  /* Soltar dos archivos desde fuera estando en la Agenda */
  await a.ev(`(async()=>{document.querySelector('.nav [data-view=cal]').click();await new Promise(r=>setTimeout(r,500))})()`);
  const r5=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),dt=new DataTransfer();dt.items.add(new File(['# Hola'],'Soltada.md',{type:'text/markdown'}));dt.items.add(new File([new Uint8Array([37,80,68,70])],'informe.pdf',{type:'application/pdf'}));
    document.body.dispatchEvent(new DragEvent('dragover',{dataTransfer:dt,bubbles:true,cancelable:true,clientX:300,clientY:300}));await w(800);const o={vista:document.querySelector('.nav [aria-current=page]').dataset.view};
    const b=document.querySelector('#ob-list [data-folder="Beta"]').getBoundingClientRect(),x=b.left+b.width/2,y=b.top+b.height/2,el=document.elementFromPoint(x,y);el.dispatchEvent(new DragEvent('dragover',{dataTransfer:dt,bubbles:true,cancelable:true,clientX:x,clientY:y}));await w(80);o.marcada=!!document.querySelector('#ob-list .drop');
    el.dispatchEvent(new DragEvent('drop',{dataTransfer:dt,bubbles:true,cancelable:true,clientX:x,clientY:y}));await w(1000);const S=VTAPI.state(),n=S.notes.find(z=>z.title==='Soltada'),p=S.atts.find(z=>z.name==='informe.pdf');o.nota=n&&n.folder+'|'+n.body;o.pdf=p&&p.folder+'|'+p.kind;o.resaltados=document.querySelectorAll('#ob-list .ob-new').length;return o})()`);
  ok(r5.vista==='notes'&&r5.marcada&&r5.nota==='Beta|# Hola'&&r5.pdf==='Beta|file'&&r5.resaltados>=2,'notas: soltar archivos desde fuera lleva a Notas y los mete en la carpeta de debajo, resaltados',r5);
  await lista();await a.tecla('z',2);await sleep(500);const r6=await a.ev(`({nota:VTAPI.state().notes.some(z=>z.title==='Soltada'),pdf:VTAPI.state().atts.some(z=>z.name==='informe.pdf')})`);
  ok(!r6.nota&&!r6.pdf,'notas: Ctrl+Z quita de una vez todo lo soltado',r6);
  /* Fuera de la ventana: el archivo temporal que se le da al sistema */
  const tmp=path.join(os.tmpdir(),'temvra-arrastre','Seis.md');try{fs.unlinkSync(tmp)}catch(e){}
  const q=await centro('#ob-list [data-note="k6"]');await a.raton('mouseMoved',q[0],q[1]);await a.raton('mousePressed',q[0],q[1],1,'left');for(let i=1;i<=10;i++){await a.raton('mouseMoved',q[0]-q[0]*i/9-3,q[1],1,'left');await sleep(40)}await a.raton('mouseReleased',0,q[1],0,'left');await sleep(800);
  const sale=fs.existsSync(tmp)?fs.readFileSync(tmp,'utf8'):null;try{fs.unlinkSync(tmp)}catch(e){}
  ok(sale==='Texto de Seis','notas: una nota arrastrada fuera de la ventana sale como archivo .md',{sale});
  /* Tres columnas: en la columna central también se ordena arrastrando, también lo que no está en ninguna carpeta */
  await a.send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});await sleep(500);
  await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms));VTAPI.state().obFolder='';document.querySelector('.nav [data-view=cal]').click();await w(400);document.querySelector('.nav [data-view=notes]').click();await w(800)})()`);
  const col=()=>a.ev(`[...document.querySelectorAll('#vn-list .vl-row[data-note]')].map(r=>(VTAPI.state().notes.find(n=>n.id===r.dataset.note)||{}).title).join(' ')`);
  const c0=await col(),s6=await centro('#vn-list [data-note="k6"]'),s7=await centro('#vn-list [data-note="k7"]');await arrastra([s7[0],s7[1]],[s6[0],s6[1]-s6[2]*.4]);
  const r7={tres:await a.ev(`document.body.classList.contains('notes3')`),antes:c0,despues:await col(),modo:await a.ev(`VTAPI.state().obFolderSort['']`)};
  ok(r7.tres&&/Seis.*Siete/.test(r7.antes)&&/Siete.*Seis/.test(r7.despues)&&r7.modo==='custom','notas: en la columna central también se ordena arrastrando',r7)}

/* PC: Ctrl+rueda y Ctrl + / − / 0 cambian el zoom de la ventana */
async function zoomPC(a){const z=()=>a.ev(`Math.round(devicePixelRatio*100)`);await a.ev(`document.getElementById('ob-list').focus()`);
  const r={ini:await z()};await a.tecla('+',2);await sleep(400);r.mas=await z();await a.tecla('-',2);await sleep(300);await a.tecla('-',2);await sleep(400);r.menos=await z();await a.rueda(600,400,-120,2);await sleep(400);r.rueda=await z();await a.tecla('0',2);await sleep(400);r.cero=await z();r.aviso=await a.ev(`document.getElementById('toast').textContent`);
  ok(r.ini===100&&r.mas===110&&r.menos===90&&r.rueda===100&&r.cero===100&&r.aviso==='Zoom 100 %','PC: zoom con Ctrl + / − / 0 y con Ctrl+rueda',r)}

/* Registros: repetir las filas ciertos días de la semana o del mes; se rellenan también los días pasados sin abrir la app */
async function repeticion(a){
  const r=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state();S.regs.push({id:'rqa',name:'Estudio',type:'estudio',tag:'',color:'#8b6cf6',icon:'📚',cols:['Tema','Minutos'],rows:[]});S.regLast='rqa';
    document.querySelector('.nav [data-view=cal]').click();await w(400);document.querySelector('.nav [data-view=reg]').click();await w(800);if(!document.querySelector('[data-reprow]')){const p=document.querySelector('[data-pick="rqa"],[data-rcard="rqa"]');if(p)p.click();await w(600)}
    const b=document.querySelector('[data-reprow]'),o={boton:b&&b.textContent.trim()};b.click();await w(600);const h=document.getElementById('ob-sheet');h.querySelector('[data-rmode="w"]').click();await w(200);
    for(const d of ['0','1','2','3','4','5','6']){const x=h.querySelector('[data-rw="'+d+'"]'),on=x.getAttribute('aria-pressed')==='true';if(on!==['1','3','5'].includes(d)){x.click();await w(80)}}
    const R=h.querySelector('.sheet').getBoundingClientRect();o.fuera=[...h.querySelectorAll('[data-rw]')].filter(x=>{const q=x.getBoundingClientRect();return q.right>Math.min(R.right,innerWidth)+.5||q.left<R.left-.5}).length;
    h.querySelector('#rep-ok').click();await w(600);const g=S.regs.find(x=>x.id==='rqa');o.rep=JSON.stringify(g.rep.w);o.boton2=document.querySelector('[data-reprow]').textContent.trim();
    const p=v=>String(v).padStart(2,'0'),k=d=>d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate()),d0=new Date();d0.setDate(d0.getDate()-14);g.rep={w:[1,3,5],since:k(d0)};g.rows=[];
    document.querySelector('.nav [data-view=cal]').click();await w(300);document.querySelector('.nav [data-view=reg]').click();await w(600);o.dias=[...new Set(g.rows.map(x=>new Date(x.d+'T12:00').getDay()))].sort().join();o.filas=g.rows.length;
    g.rep={m:[31],since:'2026-11-01',last:'2026-11-29'};g.rows=[];document.querySelector('.nav [data-view=cal]').click();await w(300);document.querySelector('.nav [data-view=reg]').click();await w(500);o.nov30=g.rows.some(x=>x.d==='2026-11-30')||k(new Date())<'2026-11-30';
    S.regs=S.regs.filter(x=>x.id!=='rqa');return o})()`);
  ok(r.boton==='Cada día'&&!r.fuera&&r.rep==='[1,3,5]'&&r.boton2==='lun, mié, vie'&&r.dias==='1,3,5'&&r.filas>=6&&r.nov30,'registros: repetir filas los días elegidos, también los pasados sin abrir la app',r)}

/* Móvil: al tocar en mitad de una nota larga y escribir, la nota no salta y el cursor queda por encima de la barra de formato */
async function salto(a){
  const largo=Array.from({length:45},(_,i)=>'Línea '+(i+1)+' de una nota de prueba bastante larga para que haga falta desplazarse.').join('\\n');
  await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state();S.ob.bottomUp=true;S.notes.unshift({id:'nqaJ',title:'Salto',folder:'',pinned:false,created:Date.now(),updated:Date.now(),body:'${largo}'});
    document.querySelector('.nav [data-view=cal]').click();await w(400);document.querySelector('.nav [data-view=notes]').click();await w(800);document.querySelector('[data-note="nqaJ"]').click();await w(900);if(document.getElementById('ne-edit').hidden){document.getElementById('ne-mode').click();await w(600)}
    document.getElementById('ne-text').blur();await w(300);document.querySelector('#noteview .ne-body').scrollTop=700;await w(300)})()`);
  const mide=`(()=>{const ta=document.getElementById('ne-text'),b=document.querySelector('#noteview .ne-body'),tl=document.getElementById('ne-tools').getBoundingClientRect(),m=document.createElement('div'),cs=getComputedStyle(ta);['fontFamily','fontSize','fontWeight','lineHeight','letterSpacing','paddingTop','paddingLeft','paddingRight','boxSizing','wordSpacing','tabSize'].forEach(k=>m.style[k]=cs[k]);
    m.style.cssText+=';position:absolute;visibility:hidden;white-space:pre-wrap;overflow-wrap:break-word;width:'+ta.clientWidth+'px;left:0;top:0';m.textContent=ta.value.slice(0,ta.selectionStart);const s=document.createElement('span');s.textContent='|';m.appendChild(s);document.body.appendChild(m);const y=ta.getBoundingClientRect().top+s.offsetTop;m.remove();return {scroll:Math.round(b.scrollTop),cursor:Math.round(y),barra:Math.round(tl.top),pos:ta.selectionStart,largo:ta.value.length}})()`;
  await a.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:200,y:430,id:1}]});await sleep(60);await a.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await sleep(120);
  await a.send('Emulation.setDeviceMetricsOverride',{width:412,height:520,deviceScaleFactor:2,mobile:true});await sleep(800);const t0=await a.ev(mide);
  await a.send('Input.insertText',{text:'a'});await sleep(300);await a.send('Input.insertText',{text:'b'});await sleep(300);const t1=await a.ev(mide);
  await a.send('Emulation.setDeviceMetricsOverride',{width:412,height:915,deviceScaleFactor:2,mobile:true});
  await a.ev(`(async()=>{const S=VTAPI.state();document.getElementById('ne-text').blur();document.getElementById('ne-close').click();S.notes=S.notes.filter(x=>x.id!=='nqaJ');S.ob.bottomUp=false;await new Promise(r=>setTimeout(r,300))})()`);
  ok(t0.pos<t0.largo-400&&t0.cursor<t0.barra&&Math.abs(t1.scroll-t0.scroll)<80&&t1.cursor<t1.barra,'móvil: al escribir en mitad de una nota larga no salta y el cursor queda sobre la barra',{t0,t1})}

/* Buscar en la nota: la lupa y Ctrl+F, sin distinguir mayúsculas ni acentos; Intro y Mayús+Intro recorren; Esc cierra sin salir de la nota;
   editando, los resultados se marcan en la capa de detrás y al cerrar queda elegida la palabra; sin nota abierta, Ctrl+F busca en todas */
async function buscarNota(a){
  const body='El árbol del patio es un Arbol enorme.\\n\\nOtros ARBOLES: **arbolito** y olivo.\\n'+Array.from({length:30},(_,i)=>'Línea '+(i+1)).join('\\n')+'\\nAl final, otro árbol.';
  await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),S=VTAPI.state();S.notes.unshift({id:'nbq',title:'Plantas',folder:'',pinned:false,created:Date.now(),updated:Date.now(),body:'${body}'});S.obFolder='';
    document.querySelector('.nav [data-view=cal]').click();await w(400);document.querySelector('.nav [data-view=notes]').click();await w(800);document.querySelector('[data-note="nbq"]').click();await w(800);if(!document.getElementById('ne-edit').hidden){document.getElementById('ne-mode').click();await w(400)}})()`);
  const r1=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms));document.getElementById('ne-find-b').click();await w(150);const inp=document.getElementById('nf-in'),o={foco:document.activeElement===inp};inp.value='arbol';inp.dispatchEvent(new Event('input'));await w(350);
    o.cuenta=document.getElementById('nf-n').textContent;o.marcas=document.querySelectorAll('#ne-read mark.nf').length;inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));await w(150);o.intro=document.getElementById('nf-n').textContent;
    inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',shiftKey:true,bubbles:true}));await w(150);o.mayus=document.getElementById('nf-n').textContent;return o})()`);
  await a.tecla('Escape');await sleep(300);r1.esc=await a.ev(`!document.getElementById('noteview').hidden&&document.getElementById('ne-find').hidden&&!document.querySelector('#ne-read mark.nf')`);
  ok(r1.foco&&r1.cuenta==='1/5'&&r1.marcas===5&&r1.intro==='2/5'&&r1.mayus==='1/5'&&r1.esc,'notas: la lupa busca en la nota sin distinguir mayúsculas ni acentos y Esc cierra sin salir',r1);
  await a.ev(`(async()=>{document.getElementById('ne-mode').click();await new Promise(r=>setTimeout(r,400));const ta=document.getElementById('ne-text'),i=ta.value.indexOf('olivo');ta.focus();ta.setSelectionRange(i,i+5)})()`);
  await a.tecla('f',2);await sleep(400);
  const r2=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),inp=document.getElementById('nf-in'),o={texto:inp.value,cuenta:document.getElementById('nf-n').textContent};inp.value='árbol';inp.dispatchEvent(new Event('input'));await w(350);
    const f=document.getElementById('ne-fhl'),ta=document.getElementById('ne-text'),cf=getComputedStyle(f),ct=getComputedStyle(ta);o.marcas=f.querySelectorAll('mark').length;o.letra=cf.fontFamily===ct.fontFamily&&cf.fontSize===ct.fontSize&&cf.lineHeight===ct.lineHeight;
    inp.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));await w(150);return o})()`);
  await a.tecla('Escape');await sleep(300);r2.cierre=await a.ev(`(()=>{const ta=document.getElementById('ne-text');return document.activeElement===ta&&ta.value.slice(ta.selectionStart,ta.selectionEnd)})()`);
  ok(r2.texto==='olivo'&&r2.cuenta==='1/1'&&r2.marcas===5&&r2.letra&&r2.cierre==='Arbol','notas: editando, Ctrl+F parte de lo elegido, marca los resultados y al cerrar deja elegida la palabra',r2);
  await a.ev(`(async()=>{const S=VTAPI.state();document.getElementById('ne-text').blur();document.getElementById('ne-close').click();S.notes=S.notes.filter(x=>x.id!=='nbq');await new Promise(r=>setTimeout(r,400))})()`);
  await a.tecla('f',2);await sleep(500);const r3=await a.ev(`VTAPI.state().obPanel`);await a.ev(`(()=>{const S=VTAPI.state();S.obPanel='files'})()`);
  ok(r3==='search','notas: sin nota abierta, Ctrl+F abre la búsqueda en todas las notas',{panel:r3})}

/* Sin categorías también se puede crear un bloque: se toca un hueco de la línea de tiempo y se elige Bloque.
   El hueco se busca en pantalla: la vista Día se coloca en la hora actual y un punto fijo, por la noche, cae en la cabecera del día siguiente */
async function bloque(a){
  await a.ev(`(async()=>{document.querySelector('.nav [data-view=cal]').getAttribute('aria-current')==='page'||document.querySelector('.nav [data-view=cal]').click();await new Promise(r=>setTimeout(r,900));document.querySelector('#cal-vseg [data-cv=day]').click();await new Promise(r=>setTimeout(r,1300))})()`);
  const p=await a.ev(`(()=>{const v=document.getElementById('v-blocks').getBoundingClientRect(),x=v.left+v.width/2,m=v.top+v.height/2;for(let d=0;d<v.height/2;d+=20)for(const y of [m+d,m-d]){const e=document.elementFromPoint(x,y);if(e&&e.closest('.bk-grid')&&!e.closest('[data-bk],[data-ev]'))return [x,y]}return null})()`);
  if(!p){ok(false,'sin categorías: se puede crear un bloque y se ve',{hueco:null});return}
  const [x,y]=p;await a.raton('mouseMoved',x,y);await a.raton('mousePressed',x,y,1,'left');await sleep(60);await a.raton('mouseReleased',x,y,0,'left');await sleep(900);
  const r=await a.ev(`(async()=>{const w=ms=>new Promise(r=>setTimeout(r,ms)),bs=[...document.querySelectorAll('.sheet-back:not([hidden]) button')],b=bs.find(x=>/Bloque/.test(x.textContent));if(!b)return {menu:false,botones:bs.map(x=>x.textContent.trim().slice(0,30))};b.click();await w(700);document.getElementById('bks-save').click();await w(900);const e=VTAPI.state().events.at(-1);return {menu:true,cat:e&&e.cat,enPantalla:document.querySelectorAll('#v-blocks .bk-b').length}})()`);
  ok(r.menu&&r.cat&&r.enPantalla>0,'sin categorías: se puede crear un bloque y se ve',r)}

/* Instalación antigua con copias repetidas y objetos de ejemplo sin usar */
async function migracion(a){
  await a.ev(`(()=>{const st=JSON.parse(localStorage.getItem('vt2_state')),ev=(id,t,s,x)=>Object.assign({id,done:false,kind:'event',cat:'gym',title:t,date:'2026-11-01',start:s,end:'23:00',allDay:false,notes:'',remind:-1,ag:'a1'},x||{});st.uiV=10;delete st.noDiary;st.blockCats=[{id:'gym',name:'Gimnasio',emoji:'x',color:'#f2622e'},{id:'rest',name:'Descanso',emoji:'x',color:'#2fb35b'}];st.regs=[{id:'diario',kind:'diary',name:'Diario',days:{}}];st.circuit={name:'Abdominales',rounds:2,roundRest:45,steps:[['work','Crunch',30],['rest','Descanso',15],['work','Elevación de piernas',30],['rest','Descanso',15],['work','Plancha lateral',30],['rest','Descanso',15],['work','Bicicleta',30]].map(([type,name,sec],i)=>({id:'s'+i,type,name,sec}))};st.events=[ev('bkmw4e533v','Gimnasio','02:45'),ev('bkmw4e59pt65','Gimnasio','01:15'),ev('bkmw4e59qs76','Gimnasio','01:15'),ev('bkmw2v8qcw','Gimnasio','04:45',{date:'2026-10-30'}),ev('bkmw277m12','Pierna','10:00'),ev('bkmw277m13','Pierna','12:00')];localStorage.setItem('vt2_state',JSON.stringify(st))})()`);
  await a.recarga();
  const r=await a.ev(`(S=>({uiV:S.uiV,eventos:S.events.map(e=>e.title+e.start),cats:S.blockCats.map(c=>c.id),regs:S.regs.length,pasos:S.circuit.steps.length}))(VTAPI.state())`);
  ok(r.uiV===11&&JSON.stringify(r.eventos)===JSON.stringify(['Gimnasio02:45','Gimnasio04:45','Pierna10:00','Pierna12:00']),'actualización: quita las copias repetidas y conserva lo demás',r);
  ok(JSON.stringify(r.cats)==='["gym"]'&&!r.regs&&!r.pasos,'actualización: quita lo de ejemplo que no se ha usado',r)}

execFileSync(process.execPath,[path.join(root,'scripts/build-www.mjs')],{stdio:'ignore'});
const yaHabia=fs.existsSync(enlace);if(!yaHabia)fs.symlinkSync('../www',enlace);
try{
  console.log('— PC 1440×900');
  const pc=await abre(9701,[1440,900]);
  const r=await vacio(pc,true);ok(r.vistas===7&&r.panel,'PC: todas las vistas de la Agenda y panel de días junto al Mes',r);
  await sinVisita(pc);await centrar(pc);await aro(pc,true);await guardado(pc);await token(pc);
  await encabezado(pc);await burbujaNota(pc);await fechaHoy(pc);await letraWidgets(pc);await avisoTarde(pc);await sincroniza(pc);await notasPC(pc);await zoomPC(pc);await buscarNota(pc);
  ok(!pc.errores.length,'PC: sin errores de JavaScript',pc.errores);pc.cierra();
  console.log('— Móvil 412×915');
  const mv=await abre(9702,[412,915],true);
  await vacio(mv,false);ok(!await mv.ev(`document.body.classList.contains('deskcal')`),'móvil: sin panel de días');
  await sinVisita(mv);await centrar(mv);await aro(mv,false);await bloque(mv);await emojis(mv);await repeticion(mv);await salto(mv);await migracion(mv);
  ok(!mv.errores.length,'móvil: sin errores de JavaScript',mv.errores);mv.cierra();
}catch(e){fallos++;console.error('✗ Prueba interrumpida:',e.message)}
finally{abiertas.forEach(a=>a.cierra());await sleep(400);if(!yaHabia)try{fs.unlinkSync(enlace)}catch(e){}for(const d of temporales)try{fs.rmSync(d,{recursive:true,force:true})}catch(e){}}
console.log(fallos?`\n${fallos} prueba(s) fallida(s)`:'\nTodo correcto');process.exit(fallos?1:0);
