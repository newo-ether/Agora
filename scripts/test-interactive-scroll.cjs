/* The actual sandbox must report inner root and nested-container bounds, including legacy answers. */
module.exports = async ({page,open,assert}) => {
  const latest = () => page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='scrollBounds').at(-1)?.value);
  let frame = await open({title:'Scrollable',description:'Root',fallback:'Scroll',html:'<main style="height:2400px">Long content</main>'});
  await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='scrollBounds'&&e.value.canNext));
  assert.deepEqual(await latest(),{canPrevious:false,canNext:true,activeTouch:false});
  await frame.locator('body').evaluate(()=>window.scrollTo(0,document.scrollingElement.scrollHeight));
  await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='scrollBounds'&&e.value.canPrevious&&!e.value.canNext));
  assert.equal((await latest()).canNext,false);
  frame = await open({title:'Nested',description:'Nested scroll',fallback:'Nested',html:'<div id="inner" style="height:160px;overflow:auto"><div style="height:900px">Scrollable area</div></div>'});
  await page.waitForFunction(()=>window.nativeEvents.some(e=>e.method==='scrollBounds'));
  assert.equal((await latest()).canNext,false);
  // A trusted pointer identifies which nested scroll chain owns this touch.
  await frame.locator('#inner').click({position:{x:30,y:40}});
  assert.ok(await page.evaluate(()=>window.nativeEvents.some(e=>e.method==='scrollBounds'&&e.value.activeTouch&&e.value.canNext)));
  const box = await frame.locator('#inner').boundingBox();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:box.x+50,y:box.y+130}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:box.x+50,y:box.y+70}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:box.x+50,y:box.y+30}]});
  await page.waitForTimeout(100);
  assert.ok((await latest()).activeTouch,'Pointer cancellation during panning must retain nested scroll ownership');
  await frame.locator('#inner').evaluate(el=>{el.scrollTop=el.scrollHeight;});
  await page.waitForFunction(()=>window.nativeEvents.filter(e=>e.method==='scrollBounds').at(-1)?.value.activeTouch && !window.nativeEvents.filter(e=>e.method==='scrollBounds').at(-1)?.value.canNext);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await cdp.detach();
  assert.equal(await page.evaluate(()=>window.nativeEvents.filter(e=>e.method==='sendPrompt').length),0);
};
