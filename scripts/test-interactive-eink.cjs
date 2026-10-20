/* Tests the real packaged opaque-origin renderer. No provider credentials or native actions. */
module.exports = async function testEink({page, open, assert}) {
  const send = data => page.evaluate(data => window.nativePort.postMessage(JSON.stringify(data)), data);
  const profile = {title:'Adaptive calculation',description:'Same state in two modes',fallback:'2 × 5 = 10',eInkAware:true,
    css:'body{background:rgb(20,30,40);color:white;padding:24px;box-sizing:border-box}h1{font-size:28px}.controls,.actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:16px}button{background:orange;box-shadow:0 2px 8px black;transition:background 1s;padding:8px 16px}button[aria-pressed=true]{background:#426;color:white}#plot{display:block;width:320px;height:180px;background:white;border:1px solid black;margin:16px 0}#long{height:1800px}',
    html:'<h1>Local calculation</h1><p>Adjust a value, then Apply. Step advances the static simulation.</p><div class="controls"><label for="slider">Value</label><input id="slider" type="range" min="0" max="10" value="5"><button id="minus">−</button><button id="plus">+</button><output id="value">5</output></div><div class="actions"><button id="apply">Apply</button><button id="step">Step</button><button disabled>Unavailable</button><button aria-pressed="true">Selected</button></div><svg id="plot" viewBox="0 0 320 180" aria-label="Result"><path id="line" d="M20 150L290 50" fill="none" stroke="black" stroke-width="2" stroke-dasharray="4 2"/><text x="20" y="30" id="out" fill="black">10</text><text x="20" y="170" fill="black">Input → result</text></svg><div id="long"></div>',
    jsFunctions:'function paper(){return document.documentElement.dataset.agoraDisplay==="eink";} function draw(){window.draws++;document.querySelector("#out").textContent=2*Number(document.querySelector("#slider").value);}',
    jsExpressions:[
      'window.initialMode=document.documentElement.dataset.agoraDisplay;window.initializations=(window.initializations||0)+1;window.draws=0;window.steps=0;window.displayEvents=[];window.documentEvents=[];window.addEventListener("agora:displaychange",e=>window.displayEvents.push(e.detail.eInkMode));document.addEventListener("agora:displaychange",e=>window.documentEvents.push(e.detail.eInkMode));',
      'const slider=document.querySelector("#slider");slider.oninput=()=>{document.querySelector("#value").textContent=slider.value;if(!paper())draw();};slider.onchange=()=>{if(paper())draw();};',
      'document.querySelector("#apply").onclick=draw;document.querySelector("#step").onclick=()=>{window.steps++;draw();};',
      'document.querySelector("#plus").onclick=()=>{slider.value=Number(slider.value)+1;slider.dispatchEvent(new Event("input"));slider.dispatchEvent(new Event("change"));};document.querySelector("#minus").onclick=()=>{slider.value=Number(slider.value)-1;slider.dispatchEvent(new Event("input"));slider.dispatchEvent(new Event("change"));};',
    ]};
  let frame = await open(profile,false,false,{eInkMode:true,fontScale:1.5});
  assert.equal(await frame.locator('html').getAttribute('data-agora-display'),'eink');
  assert.equal(await frame.locator('body').evaluate(()=>window.initialMode),'eink');
  assert.equal(await frame.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(255, 255, 255)');
  assert.equal(await frame.locator('body').evaluate(el=>getComputedStyle(el).fontSize),'24px');
  assert.equal(await frame.locator('button').first().evaluate(el=>getComputedStyle(el).boxShadow),'none');
  assert.equal(await frame.locator('button').first().evaluate(el=>getComputedStyle(el).transitionDuration),'0s');
  assert.equal(await frame.locator('button[disabled]').evaluate(el=>getComputedStyle(el).borderStyle),'dashed');
  assert.equal(await frame.locator('button[aria-pressed]').evaluate(el=>getComputedStyle(el).color),'rgb(255, 255, 255)');
  await frame.locator('#slider').evaluate(el=>{el.value='8';el.dispatchEvent(new Event('input'));});
  assert.equal(await frame.locator('#value').textContent(),'8');
  assert.equal(await frame.locator('body').evaluate(()=>window.draws),0);
  await frame.locator('#slider').evaluate(el=>el.dispatchEvent(new Event('change')));
  assert.equal(await frame.locator('#out').textContent(),'16');
  await frame.locator('#apply').click();
  assert.equal(await frame.locator('body').evaluate(()=>window.draws),2);
  assert.equal(await frame.locator('body').evaluate(()=>window.steps),0);
  await frame.locator('#step').click();
  assert.equal(await frame.locator('body').evaluate(()=>window.steps),1);
  await send({type:'page',direction:'next'});
  await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='pageState'&&e.value.canPrevious));
  const position = await frame.locator('body').evaluate(()=>window.scrollY);
  const expected = await frame.locator('body').evaluate(()=>innerHeight-Math.min(innerHeight/2,parseFloat(getComputedStyle(document.body).lineHeight)*3));
  assert.ok(Math.abs(position-expected)<=1);
  await send({type:'display',eInkMode:false,fontScale:1.5});
  await frame.locator('html[data-agora-display="default"]').waitFor();
  assert.equal(await frame.locator('body').evaluate(()=>window.initializations),1);
  assert.deepEqual(await frame.locator('body').evaluate(()=>window.displayEvents),[false]);
  assert.deepEqual(await frame.locator('body').evaluate(()=>window.documentEvents),[false]);
  assert.equal(await frame.locator('#slider').inputValue(),'8');
  assert.ok(Math.abs(await frame.locator('body').evaluate(()=>scrollY)-position)<=1);
  assert.equal(await frame.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(20, 30, 40)');
  assert.notEqual(await frame.locator('#apply').evaluate(el=>getComputedStyle(el).boxShadow),'none');
  await frame.locator('#slider').evaluate(el=>{el.value='7';el.dispatchEvent(new Event('input'));});
  assert.equal(await frame.locator('#out').textContent(),'14');
  await send({type:'display',eInkMode:true,fontScale:1.5});
  await frame.locator('html[data-agora-display="eink"]').waitFor();
  assert.deepEqual(await frame.locator('body').evaluate(()=>window.displayEvents),[false,true]);
  assert.deepEqual(await frame.locator('body').evaluate(()=>window.documentEvents),[false,true]);
  await send({type:'display',eInkMode:false,fontScale:1.5});
  await frame.locator('html[data-agora-display="default"]').waitFor();
  assert.deepEqual(await frame.locator('body').evaluate(()=>window.displayEvents),[false,true,false]);
  assert.deepEqual(await frame.locator('body').evaluate(()=>window.documentEvents),[false,true,false]);
  if (process.env.SCREENSHOT_DIR) {
    const path=require('node:path'), fs=require('node:fs');fs.mkdirSync(process.env.SCREENSHOT_DIR,{recursive:true});
    await frame.locator('body').evaluate(()=>scrollTo(0,0));
    await frame.locator('body').evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    await page.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'interactive-normal.png')});
    await send({type:'display',eInkMode:true,fontScale:1});
    await frame.locator('html[data-agora-display="eink"]').waitFor();
    assert.equal(await frame.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(255, 255, 255)');
    await frame.locator('body').evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
    await page.screenshot({path:path.join(process.env.SCREENSHOT_DIR,'interactive-eink.png')});
  }
  // The profile must preserve meaningful CSS background imagery as well as <img> elements.
  const image = 'url("data:image/svg+xml,%3Csvg xmlns=\'http://www.w3.org/2000/svg\' width=\'20\' height=\'20\'%3E%3Cpath d=\'M0 0L20 20\' stroke=\'black\'/%3E%3C/svg%3E")';
  frame = await open({...profile, html:'<div id="image">Image</div>', css:'#image,body{background-image:'+image+'}', jsFunctions:'', jsExpressions:[]},false,false,{eInkMode:true,fontScale:1});
  const background = await frame.locator('#image').evaluate(el=>getComputedStyle(el).backgroundImage);
  assert.notEqual(background,'none');
  assert.notEqual(await frame.locator('body').evaluate(el=>getComputedStyle(el).backgroundImage),'none');
  await send({type:'display',eInkMode:false,fontScale:1});
  await frame.locator('html[data-agora-display="default"]').waitFor();
  assert.equal(await frame.locator('#image').evaluate(el=>getComputedStyle(el).backgroundImage),background);
  if (process.env.LIVE_LIBRARIES === '1') {
    frame = await open({...profile,html:'<canvas id="chart" width="320" height="180"></canvas>',
      jsFunctions:'async function chart(){const {Chart,registerables}=await import("chart.js");Chart.register(...registerables);window.chart=new Chart(document.querySelector("#chart"),{type:"line",data:{labels:["A","B"],datasets:[{label:"Result",data:[2,4],borderColor:"black",borderDash:[4,2]}]},options:{responsive:false,animation:document.documentElement.dataset.agoraDisplay==="eink"?false:{duration:300}}});window.addEventListener("agora:displaychange",()=>{window.chart.options.animation=document.documentElement.dataset.agoraDisplay==="eink"?false:{duration:300};window.chart.update("none");});document.body.dataset.chart="ready";}',
      jsExpressions:['chart();']},false,false,{eInkMode:true,fontScale:1});
    await frame.locator('body[data-chart="ready"]').waitFor({timeout:60000});
    assert.equal(await frame.locator('body').evaluate(()=>window.chart.options.animation),false);
    await send({type:'display',eInkMode:false,fontScale:1});
    await frame.locator('html[data-agora-display="default"]').waitFor();
    assert.equal(await frame.locator('body').evaluate(()=>window.chart.options.animation.duration),300);
    await send({type:'display',eInkMode:true,fontScale:1});
    await frame.locator('html[data-agora-display="eink"]').waitFor();
    assert.equal(await frame.locator('body').evaluate(()=>window.chart.options.animation),false);
  }
  // Legacy HTML is never adapted, even when the application setting is enabled.
  frame = await open({...profile,eInkAware:false},false,false,{eInkMode:true,fontScale:2});
  assert.equal(await frame.locator('html').getAttribute('data-agora-display'),null);
  assert.equal(await frame.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(20, 30, 40)');
  assert.equal(await frame.locator('body').evaluate(()=>typeof window.AgoraEinkPresentation),'undefined');
  await send({type:'display',eInkMode:true,fontScale:2});
  assert.equal(await frame.locator('html').getAttribute('data-agora-display'),null);
  // A mode change during iframe setup reaches generated initialization, not only the ready UI.
  frame=await open(profile,false,false,{eInkMode:false,fontScale:1,earlyEInkMode:true});
  assert.equal(await frame.locator('body').evaluate(()=>window.initialMode),'eink');
  // New renderers keep marking optional and reject arbitrary native-page command values.
  frame=await open(profile,false,false,{eInkMode:true,fontScale:1});
  await send({type:'page',direction:'arbitrary-code'});
  assert.equal(await frame.locator('body').evaluate(()=>scrollY),0);
  await send({type:'page',direction:'next'});
  await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='pageState'&&e.value.canPrevious));
  await send({type:'page',direction:'previous'});
  await frame.locator('body').evaluate(async()=>{for(let i=0;i<10&&scrollY>0;i++)await new Promise(r=>setTimeout(r,20));});
  assert.equal(await frame.locator('body').evaluate(()=>scrollY),0);
  console.log('PASS e-ink initial profile, release/Apply, static Step, mode-off styling/live input, value/scroll retention, overlapped paging and legacy isolation');
};
