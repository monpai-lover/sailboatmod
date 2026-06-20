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
            <body><h3>Water Route Debug (pure NBT)</h3>
            <div>From <select id="a"></select> To <select id="b"></select><button onclick="go()">Generate</button>
            <span id="msg"></span></div>
            <div id="imgs"></div><h4>Per-node log</h4><pre id="log"></pre>
            <script>
            const STAGES=[['berth','Berth resolve'],['coarse','Coarse corridor'],['fine','Corridor refine'],
              ['smooth','Smooth'],['verified','NBT verify (numbered)'],['overview','Overview']];
            async function load(){
              const r=await fetch('/api/docks');const d=await r.json();
              const a=document.getElementById('a'),b=document.getElementById('b');
              for(const x of d){a.add(new Option(x.name,x.index));b.add(new Option(x.name,x.index));}
              if(d.length>1)b.selectedIndex=1;
            }
            async function go(){
              const a=document.getElementById('a').value,b=document.getElementById('b').value;
              document.getElementById('msg').textContent=' generating...';
              try{
                const r=await fetch('/api/route?a='+a+'&b='+b);const j=await r.json();
                const box=document.getElementById('imgs');box.innerHTML='';
                for(const [k,t] of STAGES){if(!j.images||!j.images[k])continue;
                  const dv=document.createElement('div');dv.className='stage';
                  dv.innerHTML='<h4>'+t+'</h4><img src="'+j.images[k]+'">';box.appendChild(dv);}
                document.getElementById('log').textContent=j.log||'';
                document.getElementById('msg').textContent=' done';
              }catch(e){document.getElementById('msg').textContent=' failed: '+e;}
            }
            load();
            </script></body></html>
            """;
}
