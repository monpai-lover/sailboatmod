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
              <option value="nbt" selected>NBT (两阶段:粗渐进+走廊精寻)</option>
              <option value="hybrid">HYBRID (噪声导向+真实精寻)</option>
              <option value="noise">NOISE (纯噪声)</option>
            </select>
            <button onclick="go()">Generate</button>
            <span id="msg"></span></div>
            <div id="imgs"></div><h4>Per-node log</h4><pre id="log"></pre>
            <script>
            // NBT 模式阶段标签;HYBRID/NOISE 下 coarse=三段编排原始线、fine 为空跳过。
            const STAGES_NBT=[['berth','Berth resolve'],['coarse','Coarse corridor (粗走廊)'],['fine','Corridor refine (走廊精寻)'],
              ['smooth','Smooth (平滑)'],['verified','NBT verify (校验删点,红点=NBT判陆)'],['overview','Overview (各阶段叠加)']];
            const STAGES_SEG=[['berth','Berth resolve'],['coarse','三段编排原始线 (HYBRID/NOISE 中段产物)'],
              ['smooth','Smooth (平滑)'],['verified','NBT verify (校验删点,红点=NBT判陆)'],['overview','Overview (原始线 vs 校验后)']];
            async function load(){
              const r=await fetch('/api/docks');const d=await r.json();
              const a=document.getElementById('a'),b=document.getElementById('b');
              for(const x of d){a.add(new Option(x.name,x.index));b.add(new Option(x.name,x.index));}
              if(d.length>1)b.selectedIndex=1;
            }
            async function go(){
              const a=document.getElementById('a').value,b=document.getElementById('b').value;
              const mode=document.getElementById('mode').value;
              document.getElementById('msg').textContent=' generating ('+mode+')...';
              try{
                const r=await fetch('/api/route?a='+a+'&b='+b+'&mode='+mode);const j=await r.json();
                const stages=(j.mode==='nbt'||!j.mode)?STAGES_NBT:STAGES_SEG;
                const box=document.getElementById('imgs');box.innerHTML='';
                for(const [k,t] of stages){if(!j.images||!j.images[k])continue;
                  const dv=document.createElement('div');dv.className='stage';
                  dv.innerHTML='<h4>'+t+'</h4><img src="'+j.images[k]+'">';box.appendChild(dv);}
                document.getElementById('log').textContent=j.log||'';
                document.getElementById('msg').textContent=' done (mode='+(j.mode||'nbt')+')';
              }catch(e){document.getElementById('msg').textContent=' failed: '+e;}
            }
            load();
            </script></body></html>
            """;
}
