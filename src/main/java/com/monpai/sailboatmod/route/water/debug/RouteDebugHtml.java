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
            <span id="msg"></span></div>
            <p id="modeHelp">正在识别世界和加载码头……</p>
            <p>TELLUS / NBT 显示当前生产算法的最终结果，不保存航线。底图只作定位示意，不代表真实方块或船宽校验；青点为起点，紫点为终点。</p>
            <div id="imgs"></div><h4>Route result / diagnostic log</h4><pre id="log"></pre>
            <script>
            // NBT only exposes the final production result; uncollected intermediate stages are skipped.
            const STAGES_NBT=[['verified','生产求解最终结果（概览底图仅定位示意）']];
            const STAGES_SEG=[['berth','Berth resolve'],['coarse','三段编排原始线 (HYBRID/NOISE 中段产物)'],
              ['smooth','Smooth (平滑)'],['verified','NBT verify (校验删点,红点=NBT判陆)'],['overview','Overview (原始线 vs 校验后)']];
            let ready=false,busy=false;
            const generate=document.getElementById('generate');
            async function readJson(url){
              const r=await fetch(url);
              let data;
              try{data=await r.json();}catch(e){throw new Error('HTTP '+r.status+'：服务返回了无效数据，请检查服务器日志');}
              if(!r.ok)throw new Error(data.message||data.reason||data.error||('HTTP '+r.status));
              return data;
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
              generate.disabled=true;
              const a=document.getElementById('a').value,b=document.getElementById('b').value;
              const mode=document.getElementById('mode').value;
              document.getElementById('msg').textContent=' generating ('+mode+')...';
              document.getElementById('imgs').innerHTML='';
              document.getElementById('log').textContent='';
              try{
                const j=await readJson('/api/route?a='+a+'&b='+b+'&mode='+mode);
                document.getElementById('log').textContent=j.log||'';
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
              finally{busy=false;generate.disabled=!ready;}
            }
            load();
            </script></body></html>
            """;
}
