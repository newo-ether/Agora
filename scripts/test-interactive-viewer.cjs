/* Browser contract test for the packaged renderer. Requires Playwright and Chromium.
 * PLAYWRIGHT_MODULE / CHROMIUM_PATH may select an existing local installation.
 */
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '../app/src/main/assets/interactive');
const origin = 'https://appassets.androidplatform.net';

(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.CHROMIUM_PATH});
  try {
    const page = await browser.newPage({hasTouch:true});
    const errors = [];
    page.setDefaultTimeout(15000);
    if (process.env.DEBUG_VIEWER) page.on('console',message=>console.log(message.type(),message.text()));
    page.on('pageerror', e => errors.push(e.message));
    await page.route(origin+'/**', route => {
      const name = new URL(route.request().url()).pathname.split('/').pop();
      if (!['host.html','host.css','host.js','design-system.js','websandbox.js'].includes(name)) return route.abort();
      route.fulfill({path:path.join(root,name), contentType:name.endsWith('.html')?'text/html':name.endsWith('.css')?'text/css':'application/javascript'});
    });
    async function open(answer, inline = false, sameWindow = false) {
      await page.goto(origin+'/interactive/host.html');
      await page.evaluate(({answer,inline,sameWindow}) => {
        window.nativeEvents=[];
        // A foreign iframe with its own port cannot claim the native bootstrap channel.
        const foreign=document.createElement('iframe');document.body.append(foreign);
        const spoof=new MessageChannel();
        window.dispatchEvent(new MessageEvent('message',{data:'{}',ports:[spoof.port2],source:foreign.contentWindow}));
        foreign.remove();spoof.port1.close();
        const channel=new MessageChannel();
        window.nativePort=channel.port1;
        channel.port1.onmessage=e=>{
          const message=JSON.parse(e.data);window.nativeEvents.push(message);
          if (message.id) channel.port1.postMessage(JSON.stringify({id:message.id,ok:true}));
        };
        window.dispatchEvent(new MessageEvent('message',{data:JSON.stringify(inline ? {answer,inline:true} : answer),ports:[channel.port2],source:sameWindow ? window : null}));
      }, {answer,inline,sameWindow});
      await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='ready'||e.method==='error'));
      assert.equal(await page.evaluate(()=>window.nativeEvents.some(e=>e.method==='error')),false);
      return page.frameLocator('#frame iframe');
    }
    const base={title:'Calculator',description:'Local calculation',fallback:'Twice the selected number',css:'',html:'<input id="slider" type="range" min="0" max="10" value="5"><input id="n" type="number" value="3"><button id="calculate">Calculate</button><output id="out"></output><button id="follow">Follow up</button><button id="link">Link</button><button id="bad-link">Invalid link</button><p id="isolation"></p>',jsFunctions:'',jsExpressions:[
      'document.querySelector("#calculate").onclick=()=>document.querySelector("#out").textContent=Number(document.querySelector("#n").value)*2;',
      'document.querySelector("#follow").onclick=()=>Websandbox.connection.remote.sendPrompt({text:"Explain "+document.querySelector("#out").textContent});',
      'document.querySelector("#link").onclick=()=>Websandbox.connection.remote.openLink({url:"https://example.com"});',
      'document.querySelector("#bad-link").onclick=()=>Websandbox.connection.remote.openLink({url:"javascript:alert(1)"}).catch(()=>document.body.dataset.badLink="refused");',
      'try { parent.document.body.textContent="hacked"; } catch(e) { document.querySelector("#isolation").textContent="isolated"; }',
    ]};
    let frame=await open(base);
    await frame.locator('#slider').tap();
    await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='controlTouch'));
    assert.equal(await frame.locator('#slider').evaluate(el=>getComputedStyle(el).touchAction),'none');
    const claims=await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='controlTouch').length);
    await frame.locator('#isolation').click();
    await frame.locator('#slider').evaluate(el=>el.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true})));
    assert.equal(await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='controlTouch').length),claims);
    await frame.locator('#n').fill('7');await frame.locator('#calculate').click();
    assert.equal(await frame.locator('#out').textContent(),'14');
    assert.equal(await frame.locator('#isolation').textContent(),'isolated');
    assert.equal(await frame.locator('body').evaluate(el=>{try{localStorage.setItem('x','x');return false;}catch(e){return true;}}),true);
    assert.equal(await page.locator('iframe').getAttribute('sandbox'),'allow-scripts ');
    await frame.locator('#follow').click();
    await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='sendPrompt'));
    assert.equal(await page.evaluate(()=>window.nativeEvents.find(e=>e.method==='sendPrompt').value),'Explain 14');
    await frame.locator('#link').click();
    await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='openLink'));
    await frame.locator('#bad-link').click();
    await frame.locator('body[data-bad-link="refused"]').waitFor();
    assert.equal(await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='openLink').length),1);
    // A generated script cannot manufacture a user activation to send a prompt.
    await page.waitForFunction(()=>!navigator.userActivation.isActive);
    const count=await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='sendPrompt').length);
    await frame.locator('body').evaluate(()=>{setTimeout(async()=>{try{await Websandbox.connection.remote.sendPrompt({text:'automatic'});}catch(e){document.body.dataset.refused='true';}},6000);});
    await new Promise(resolve=>setTimeout(resolve,7000));
    if(process.env.DEBUG_VIEWER) console.log('automatic',await page.evaluate(()=>window.nativeEvents),await frame.locator('body').getAttribute('data-refused'));
    assert.equal(await frame.locator('body').getAttribute('data-refused'),'true');
    assert.equal(await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='sendPrompt').length),count);
    frame=await open({...base,title:'Diagram',html:'<svg viewBox="0 0 200 100"><rect class="box" width="180" height="80"/><text class="t" x="20" y="40">Diagram</text></svg>',jsExpressions:[]});
    assert.equal(await frame.locator('svg text').textContent(),'Diagram');
    // Persisted Kotlin artifacts omit optional fields; native bootstrap can use the main window source.
    frame=await open({title:'Simple',description:'Optional fields omitted',fallback:'Simple answer',html:'<div id="size" style="height:240px">Simple answer</div>'},true,true);
    assert.equal(await frame.locator('#size').textContent(),'Simple answer');
    await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='height'&&e.value>=240));
    const before=await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='height').length);
    await frame.locator('#size').evaluate(el=>el.style.height='480px');
    await page.waitForFunction(before=>window.nativeEvents.filter(e=>e.method==='height').length>before && window.nativeEvents.filter(e=>e.method==='height').at(-1).value>=480,before);
    frame=await open({title:'Simple full screen',description:'Defaults',fallback:'Simple',html:'<p>Full screen</p>'});
    assert.equal(await frame.locator('p').textContent(),'Full screen');
    // Reproduce Android WebView's collapsed root percentage-height containing block.
    await page.addStyleTag({content:'html{height:0px !important}'});
    assert.equal(await page.locator('#frame').evaluate(el=>el.getBoundingClientRect().height),page.viewportSize().height);
    assert.equal(await page.locator('#frame iframe').evaluate(el=>el.getBoundingClientRect().height),page.viewportSize().height);
    await page.setViewportSize({width:536,height:480});
    assert.equal(await page.locator('#frame iframe').evaluate(el=>el.getBoundingClientRect().height),480);
    await page.setViewportSize({width:1280,height:720});
    assert.equal(await page.evaluate(()=>window.nativeEvents.some(e=>e.method==='height')),false);
    // Live upstream import-map/library smoke test, enabled separately from deterministic checks.
    if (process.env.LIVE_LIBRARIES==='1') {
      frame=await open({...base,title:'Chart',html:'<canvas id="chart"></canvas>',jsFunctions:'async function chart(){const {Chart,registerables}=await import("chart.js");Chart.register(...registerables);new Chart(document.querySelector("#chart"),{type:"bar",data:{labels:["A","B"],datasets:[{data:[2,4]}]},options:{animation:false}});document.body.dataset.chart="ready";} ',jsExpressions:['chart();']});
      await frame.locator('body[data-chart="ready"]').waitFor({timeout:60000});
      assert.ok(await frame.locator('canvas').evaluate(el=>el.width>0&&el.height>0));
    }
    assert.equal(errors.length,0,errors.join('\n'));
    // Missing library: generated asynchronous rejection reaches the host error channel.
    await page.route('https://esm.sh/**',route=>route.abort());
    frame=await open({...base,html:'<p>Offline chart</p>',jsExpressions:['setTimeout(()=>import("chart.js"),50);']});
    await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='error'));
    console.log('PASS selective trusted slider touch claims, collapsed-root viewport layout, omitted optional fields, inline sizing, calculator, SVG, sandbox isolation, user-gesture bridges, reopen and offline failure'+(process.env.LIVE_LIBRARIES==='1'?', live Chart.js':''));
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
