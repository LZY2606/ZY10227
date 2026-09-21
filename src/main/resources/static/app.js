let state = null;
let runData = null;
let selectedRun = null;

async function getState() {
  state = await (await fetch('/api/state')).json();
  renderProfiles();
  renderRunWindows();
  renderRunTabs();
  document.getElementById('desc').textContent = state.description;
}

function renderProfiles() {
  const box = document.getElementById('profiles');
  box.innerHTML = '';
  state.profiles.forEach((p, i) => {
    const nodes = typeof p.nodes === 'string' ? JSON.parse(p.nodes) : p.nodes;
    const div = document.createElement('div');
    div.className = 'prof';
    div.innerHTML = `<b>${p.name}（${p.id}）</b>
      <div class="muted">分层: ${nodes.map(n => `${n.depth}m@${n.c}m/s`).join(' → ')}</div>
      <div class="grid2" style="margin-top:5px">
        <div><label>有效起 (ms)</label><input type="number" data-p="${p.id}" data-k="s" value="${p.win_start ?? p.valid_start}"></div>
        <div><label>有效止 (ms)</label><input type="number" data-p="${p.id}" data-k="e" value="${p.win_end ?? p.valid_end}"></div>
      </div>`;
    box.appendChild(div);
  });
}

function renderRunWindows() {
  const box = document.getElementById('runWindows');
  box.innerHTML = '';
  state.profiles.forEach(p => {
    const div = document.createElement('div');
    div.className = 'grid2';
    div.innerHTML = `<div><label>${p.id} 起</label>
        <input type="number" data-w="${p.id}" data-k="s" value="${p.win_start ?? p.valid_start}"></div>
      <div><label>${p.id} 止</label>
        <input type="number" data-w="${p.id}" data-k="e" value="${p.win_end ?? p.valid_end}"></div>`;
    box.appendChild(div);
  });
}

function collectWindows(sel) {
  const out = [];
  document.querySelectorAll(`[data-w]`).forEach(inp => {
    const pid = inp.dataset.w;
    let w = out.find(x => x.profileId === pid);
    if (!w) { w = { profileId: pid, start: null, end: null }; out.push(w); }
    if (inp.dataset.k === 's') w.start = Number(inp.value); else w.end = Number(inp.value);
  });
  return out;
}

async function saveWindows() {
  for (const inp of document.querySelectorAll('[data-p]')) {
    const pid = inp.dataset.p;
  }
  const seen = new Set();
  for (const inp of document.querySelectorAll('[data-p]')) {
    if (seen.has(inp.dataset.p + inp.dataset.k)) continue;
  }
  const vals = {};
  document.querySelectorAll('[data-p]').forEach(inp => {
    vals[inp.dataset.p] = vals[inp.dataset.p] || {};
    vals[inp.dataset.p][inp.dataset.k] = Number(inp.value);
  });
  for (const [pid, v] of Object.entries(vals)) {
    await fetch(`/api/profiles/${pid}/window`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ profileId: pid, start: v.s, end: v.e })
    });
  }
  flash('剖面时段已保存');
  getState();
}

async function createRun(label, delay) {
  const body = {
    label, attitudeDelayMs: delay ?? Number(document.getElementById('delay').value),
    maxBeamAngle: Number(document.getElementById('maxAng').value),
    tolSec: Number(document.getElementById('tol').value),
    windows: collectWindows()
  };
  const r = await fetch('/api/runs', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  });
  const j = await r.json();
  if (!r.ok) { flash(j.error || '执行失败'); return; }
  await getState();
  await openRun(j.runId);
  flash('裁决完成：方案 #' + j.runId);
}

function flash(m) { document.getElementById('msg').textContent = m; }

async function openRun(id) {
  selectedRun = id;
  runData = await (await fetch(`/api/runs/${id}`)).json();
  renderRunTabs();
  renderSummary();
  renderTable();
  renderProfileSvg();
  renderPlanSvg();
  renderCross();
}

function renderRunTabs() {
  const tabs = document.getElementById('runTabs');
  tabs.innerHTML = '';
  (state?.runs || []).forEach(r => {
    const b = document.createElement('button');
    b.textContent = `#${r.id} ${r.label}`;
    if (r.id === selectedRun) b.className = 'on';
    b.onclick = () => openRun(r.id);
    tabs.appendChild(b);
  });
}

function statusColor(s) {
  return { OK:'#1565c0', SOLVE_FAILED:'#c62828', EXCLUDED_ANGLE:'#e8890c',
           NO_PROFILE:'#90a4ae', NO_ATTITUDE:'#90a4ae' }[s] || '#444';
}

function renderSummary() {
  const counts = {};
  runData.soundings.forEach(s => counts[s.status] = (counts[s.status] || 0) + 1);
  const text = Object.entries(counts).map(([k,v]) =>
    `<span class="stat tag ${k}">${k}: <b>${v}</b></span>`).join('');
  document.getElementById('summary').innerHTML = text;
}

function renderTable() {
  const t = document.getElementById('sndTable');
  t.innerHTML = `<tr><th class="l">样本身份</th><th class="l">测线</th><th>t(ms)</th><th>波束</th>
    <th>剖面</th><th class="l">姿态版本</th><th>安装角°</th><th>水中角°</th>
    <th>深度m</th><th>跨轨m</th><th>残差s</th><th class="l">经过分层</th><th class="l">状态/说明</th></tr>`;
  runData.soundings.forEach(s => {
    const layers = s.layers_json ? JSON.parse(s.layers_json).map(l =>
      `层${l.layer}(${l.z0}→${l.z1}m)`).join('，') : '';
    const tr = document.createElement('tr');
    tr.innerHTML = `<td class="l">${s.beam_sample_id}</td><td class="l">${s.line}</td>
      <td>${s.ping_t}</td><td>${s.beam_idx}</td><td>${s.profile_id ?? '—'}</td>
      <td class="l">${s.attitude_version ?? '—'}<br><span class="muted">${s.attitude_anchors ?? ''}</span></td>
      <td>${s.mount_angle ?? '—'}</td><td>${s.in_water_angle != null ? (+s.in_water_angle).toFixed(2) : '—'}</td>
      <td>${s.depth != null ? (+s.depth).toFixed(3) : '—'}</td>
      <td>${s.across != null ? (+s.across).toFixed(2) : '—'}</td>
      <td>${s.residual_sec != null ? (+s.residual_sec).toExponential(1) : '—'}</td>
      <td class="l">${layers}</td>
      <td class="l"><span class="tag ${s.status}">${s.status}</span>
      ${s.failure_code ? ' ['+s.failure_code+']' : ''}<br><span class="muted">${s.message ?? ''}</span></td>`;
    t.appendChild(tr);
  });
}

function renderCross() {
  const c = runData.crossovers?.[0];
  const box = document.getElementById('crossInfo');
  if (!c) { box.textContent = '无交叉信息'; return; }
  if (c.status === 'OK') {
    box.innerHTML = `交叉点 t=${c.ping_t}ms：${c.line1} 插值水深 ${(+c.z1).toFixed(3)} m，
      ${c.line2} 插值水深 ${(+c.z2).toFixed(3)} m，
      交叉差 <b style="color:${Math.abs(c.diff_m)>0.03?'#c62828':'#2e7d32'}">${(+c.diff_m).toFixed(4)} m</b>`;
  } else {
    box.innerHTML = `<span class="tag ${c.status}">${c.status}</span> ${c.message ?? ''}`;
  }
}

function renderProfileSvg() {
  const W=900,H=360, padL=60,padR=20,padT=20,padB=34;
  const prof = state.profiles[0];
  const nodes = typeof prof.nodes === 'string' ? JSON.parse(prof.nodes) : prof.nodes;
  const zmax = nodes[nodes.length-1].depth;
  const xmin=-110,xmax=110;
  const X=x=>padL+(x-xmin)/(xmax-xmin)*(W-padL-padR);
  const Z=z=>padT+z/zmax*(H-padT-padB);
  let svg = `<svg viewBox="0 0 ${W} ${H}">`;
  nodes.forEach(n=>{
    svg += `<line x1="${padL}" y1="${Z(n.depth)}" x2="${W-padR}" y2="${Z(n.depth)}" stroke="#b0bec5" stroke-dasharray="4 3"/>`;
    svg += `<text x="6" y="${Z(n.depth)+4}" font-size="11" fill="#546e7a">${n.depth}m/${n.c}m/s</text>`;
  });
  svg += `<text x="${padL}" y="14" font-size="11" fill="#546e7a">跨轨 (m)</text>`;
  [-100,-50,0,50,100].forEach(x=>{
    svg += `<line x1="${X(x)}" y1="${padT}" x2="${X(x)}" y2="${H-padB}" stroke="#eef2f6"/>`;
    svg += `<text x="${X(x)-10}" y="${H-padB+18}" font-size="11" fill="#546e7a">${x}</text>`;
  });
  const ok = runData.soundings.filter(s=>s.status==='OK');
  ok.forEach(s=>{
    const segs = JSON.parse(s.layers_json);
    let px=X(0), pz=Z(0);
    segs.forEach(l=>{
      const nx=X(l.dx), nz=Z(l.z1);
      svg += `<line x1="${px}" y1="${pz}" x2="${nx}" y2="${nz}" stroke="#1565c0" stroke-width="1.1" opacity="0.55"/>`;
      px=nx; pz=nz;
    });
    svg += `<circle cx="${X(+s.across)}" cy="${Z(+s.depth)}" r="2.2" fill="#7e57c2"/>`;
  });
  runData.soundings.filter(s=>s.status!=='OK' && s.in_water_angle!=null).forEach(s=>{
    const th = s.in_water_angle*Math.PI/180;
    const ext = s.status==='SOLVE_FAILED' ? zmax : 30;
    const hx = X(Math.sin(th)*ext/Math.cos(th));
    svg += `<line x1="${X(0)}" y1="${Z(0)}" x2="${hx}" y2="${Z(ext)}"
      stroke="${statusColor(s.status)}" stroke-width="1" stroke-dasharray="3 3" opacity="0.7"/>`;
  });
  svg += `</svg>`;
  document.getElementById('svgProfile').innerHTML = svg;
}

function renderPlanSvg() {
  const W=900,H=240,pad=40;
  const ts=[...new Set(runData.soundings.map(s=>s.ping_t))].sort((a,b)=>a-b);
  const t0=ts[0],t1=ts[ts.length-1];
  const T=t=>pad+(t-t0)/(t1-t0)*(W-2*pad);
  const Y={L1:H/2-46,L2:H/2+46};
  let svg=`<svg viewBox="0 0 ${W} ${H}">`;
  Object.entries(Y).forEach(([line,y])=>{
    svg += `<text x="8" y="${y+4}" font-size="12" fill="#37474f">${line}</text>`;
    svg += `<line x1="${pad}" y1="${y}" x2="${W-pad}" y2="${y}" stroke="#cfd8dc"/>`;
  });
  const byKey={};
  runData.soundings.forEach(s=>byKey[s.line+'|'+s.ping_t+'|'+s.beam_idx]=s);
  runData.soundings.filter(s=>s.status==='OK').forEach(s=>{
    const baseY=Y[s.line], dir=s.line==='L1'?-1:1;
    svg += `<circle cx="${T(s.ping_t)}" cy="${baseY+dir*Math.min(95,Math.abs(+s.across))*0.55}" r="2"
      fill="#7e57c2" opacity="0.7"/>`;
  });
  runData.soundings.filter(s=>s.status!=='OK').forEach(s=>{
    const baseY=Y[s.line], dir=s.line==='L1'?-1:1;
    svg += `<rect x="${T(s.ping_t)-2}" y="${baseY+dir*88-3}" width="4" height="4" fill="${statusColor(s.status)}"/>`;
  });
  const c=runData.crossovers?.[0];
  if(c && c.ping_t>0){
    svg += `<line x1="${T(c.ping_t)}" y1="${pad}" x2="${T(c.ping_t)}" y2="${H-pad}" stroke="#1565c0" stroke-dasharray="5 4"/>`;
    svg += `<text x="${T(c.ping_t)+6}" y="${pad+12}" font-size="11" fill="#1565c0">交叉 t=${c.ping_t}</text>`;
  }
  ts.forEach(t=>{
    svg += `<text x="${T(t)-12}" y="${H-10}" font-size="10" fill="#90a4ae">${t}</text>`;
  });
  svg += `</svg>`;
  document.getElementById('svgPlan').innerHTML=svg;
}

document.getElementById('saveWin').onclick=saveWindows;
document.getElementById('run').onclick=()=>createRun(null,null);
document.getElementById('pair').onclick=async()=>{
  await createRun('姿态对齐 A：延迟 0ms',0);
  await createRun('姿态对齐 B：延迟 +600ms',600);
};
document.getElementById('export').onclick=()=>{
  if(!selectedRun){flash('请先选择一个方案');return;}
  window.location='/api/runs/'+selectedRun+'/export';
};
document.getElementById('reimport').onclick=async()=>{
  if(!confirm('将清空所有方案与裁决结果，并用内置 fixture 重新导入。继续？'))return;
  await fetch('/api/admin/reimport',{method:'POST'});
  selectedRun=null; runData=null;
  await getState();
  flash('数据库已清空并重新导入 fixture，可重新裁决复核');
};

getState();
