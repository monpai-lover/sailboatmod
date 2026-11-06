package com.monpai.sailboatmod.route.water.debug;

/** Minimal gallery page for the route-debug tool: two dock pickers, a generate button, stage images + node log. */
final class RouteDebugHtml {
    private RouteDebugHtml() {
    }

    static final String PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><title>Water Route Debug</title>
            <style>body{font-family:sans-serif;margin:16px}img{max-width:100%;border:1px solid #ccc}
            .stage{margin:12px 0}select{min-width:240px;padding:4px}button{padding:6px 14px;margin-left:8px}
            pre{background:#111;color:#0f0;padding:10px;overflow:auto;max-height:480px;font-size:12px;white-space:pre-wrap}</style></head>
            <body><h3>Water Route Debug</h3>
            <div>From <select id="a"></select> To <select id="b"></select>
            Mode <select id="mode">
              <option value="tellus">TELLUS (全球水域导向＋真实精寻)</option>
              <option value="nbt" selected>NBT (当前生产求解；不保存航线)</option>
              <option value="hybrid">HYBRID (噪声导向+真实精寻)</option>
              <option value="noise">NOISE (纯噪声)</option>
            </select>
            <button id="generate" onclick="go()" disabled>Generate</button>
            <button id="cancel" onclick="cancelJob()" disabled>Cancel</button>
            <span id="msg"></span></div>
            <p id="modeHelp">正在识别世界和加载码头……</p>
            <p>TELLUS / NBT 显示当前生产算法的最终结果，不保存航线。底图只作定位示意，不代表真实方块或船宽校验；青点为起点，紫点为终点。</p>
            <div id="imgs"></div><h4>Route result / diagnostic log</h4><pre id="log"></pre>
            <script>
            // NBT only exposes the final production result; uncollected intermediate stages are skipped.
            const STAGES_NBT=[['verified','生产求解最终结果（概览底图仅定位示意）']];
            const STAGES_SEG=[['berth','Berth resolve'],['coarse','三段编排原始线 (HYBRID/NOISE 中段产物)'],
              ['smooth','Smooth (平滑)'],['verified','NBT verify (校验删点,红点=NBT判陆)'],['overview','Overview (原始线 vs 校验后)']];
            let ready=false,busy=false,activeJob=null,cancelled=false;
            const generate=document.getElementById('generate');
            const cancel=document.getElementById('cancel');
            async function readJson(url,method='GET'){
              const controller=new AbortController();
              const timeout=setTimeout(()=>controller.abort(),15000);
              let r,data;
              try{
                try{r=await fetch(url,{method,signal:controller.signal});}
                catch(e){e.retryable=true;throw e;}
                try{data=await r.json();}catch(e){const invalid=new Error('HTTP '+r.status+'：服务返回了无效数据，请检查服务器日志');invalid.retryable=r.status>=500;throw invalid;}
              }finally{clearTimeout(timeout);}
              if(!r.ok){const e=new Error(data.message||data.reason||data.error||('HTTP '+r.status));e.retryable=r.status>=500;throw e;}
              return data;
            }
            const pause=()=>new Promise(resolve=>setTimeout(resolve,1000));
            async function cancelJob(){
              if(!activeJob)return;
              cancel.disabled=true;
              try{
                const job=await readJson('/api/route/jobs/'+encodeURIComponent(activeJob)+'/cancel','POST');
                cancelled=job.status==='CANCELLED';
                document.getElementById('msg').textContent=' '+job.status+(job.message?'：'+job.message:'');
              }catch(e){
                document.getElementById('msg').textContent='取消失败：'+e.message+'；任务仍可查询';
                cancel.disabled=false;
              }
            }
            async function load(){
              generate.disabled=true;
              try{
                const [cap,d]=await Promise.all([readJson('/api/capabilities'),readJson('/api/docks')]);
                const modes=document.getElementById('mode');
                modes.querySelector('option[value="tellus"]').disabled=!cap.tellusAvailable;
                modes.value=cap.tellusAvailable?'tellus':'nbt';
                document.getElementById('modeHelp').textContent=cap.tellusAvailable
                  ?'当前为 Tellus 地球世界，默认使用 TELLUS：全球海陆遮罩导向、港口真实地形与船宽检查。HYBRID / NOISE 为旧诊断流程。'
                  :(cap.tellusUnavailableReason||'当前世界不支持 Tellus 模式，默认使用 NBT。');
                const a=document.getElementById('a'),b=document.getElementById('b');
                for(const x of d){a.add(new Option(x.name,x.index));b.add(new Option(x.name,x.index));}
                if(d.length>1)b.selectedIndex=1;
                ready=d.length>1;
                document.getElementById('msg').textContent=ready?' ready':'至少需要两个码头才能生成路线';
                generate.disabled=!ready;
              }catch(e){
                ready=false;
                document.getElementById('msg').textContent='初始化失败：'+e.message;
              }
            }
            async function go(){
              if(!ready||busy)return;
              busy=true;
              cancelled=false;
              generate.disabled=true;
              const a=document.getElementById('a').value,b=document.getElementById('b').value;
              const mode=document.getElementById('mode').value;
              document.getElementById('msg').textContent=' generating ('+mode+')...';
              document.getElementById('imgs').innerHTML='';
              document.getElementById('log').textContent='';
              try{
                let job=await readJson('/api/route/start?a='+a+'&b='+b+'&mode='+mode,'POST');
                activeJob=job.jobId;
                if(!activeJob)throw new Error('服务未返回任务编号');
                cancel.disabled=false;
                let reconnecting=false;
                while(job.status==='QUEUED'||job.status==='RUNNING'){
                  if(!reconnecting)document.getElementById('msg').textContent=' '+job.status+' ('+mode+', '+Math.floor((job.elapsedMillis||0)/1000)+'s)，后台处理中，可取消';
                  await pause();
                  if(cancelled)throw new Error('CANCELLED：任务已取消');
                  try{
                    job=await readJson('/api/route/jobs/'+encodeURIComponent(activeJob));
                    reconnecting=false;
                  }catch(e){
                    if(!e.retryable)throw e;
                    reconnecting=true;
                    document.getElementById('msg').textContent='连接暂时中断：'+e.message+'；正在重连原任务，可取消';
                  }
                }
                if(cancelled)throw new Error('CANCELLED：任务已取消');
                const j=job.result||{};
                document.getElementById('log').textContent=j.log||'';
                if(job.status!=='SUCCEEDED')throw new Error(job.message||job.reason||job.status||'Route failed');
                if(j.status==='FAILED')throw new Error(j.message||j.reason||'Route failed');
                const stages=(j.pipeline==='production-nbt'||j.mode==='tellus'||j.mode==='nbt'||!j.mode)?STAGES_NBT:STAGES_SEG;
                const box=document.getElementById('imgs');box.innerHTML='';
                for(const [k,t] of stages){if(!j.images||!j.images[k])continue;
                  const dv=document.createElement('div');dv.className='stage';
                  const title=document.createElement('h4');title.textContent=t;
                  const picture=document.createElement('img');picture.src=j.images[k];
                  dv.appendChild(title);dv.appendChild(picture);box.appendChild(dv);}
                document.getElementById('msg').textContent=' done (mode='+(j.mode||mode)+')';
              }catch(e){document.getElementById('msg').textContent=' failed: '+e.message;}
              finally{busy=false;activeJob=null;cancel.disabled=true;generate.disabled=!ready;}
            }
            load();
            </script></body></html>
            """;
}
