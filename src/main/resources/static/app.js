let state = null;

const $ = (id) => document.getElementById(id);

async function loadState() {
  const response = await fetch('/api/state');
  state = await response.json();
  renderControls();
  renderProfiles();
  if (state.latestRun) {
    renderRun(state.latestRun);
  } else {
    setMessage('尚未生成裁决运行。选择方案后点击“重放裁决”。');
  }
}

function renderControls() {
  const profileSelect = $('profileSelect');
  profileSelect.innerHTML = '';
  state.profiles.forEach((profile) => {
    const option = document.createElement('option');
    option.value = profile.id;
    option.textContent = `${profile.id}｜${profile.name}｜${profile.validFrom} 至 ${profile.validTo}`;
    profileSelect.appendChild(option);
  });
  const attitudeSelect = $('attitudeSelect');
  attitudeSelect.innerHTML = '';
  state.attitudeVersions.forEach((version) => {
    const option = document.createElement('option');
    option.value = version.id;
    option.textContent = `${version.name}（${version.delayMillis} ms）`;
    attitudeSelect.appendChild(option);
  });
}

function selectedProfile() {
  return state.profiles.find((profile) => profile.id === $('profileSelect').value);
}

function renderProfiles() {
  const profile = selectedProfile();
  const svg = $('profileSvg');
  svg.innerHTML = '';
  const top = 34;
  const left = 70;
  const width = 130;
  const maxDepth = profile.maxDepth;
  const colors = ['#d9edf7', '#dff0d8', '#fcf8e3'];
  profile.layers.forEach((layer, index) => {
    const y1 = top + (layer.depthTop / maxDepth) * 250;
    const y2 = top + (layer.depthBottom / maxDepth) * 250;
    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', left);
    rect.setAttribute('y', y1);
    rect.setAttribute('width', width);
    rect.setAttribute('height', y2 - y1);
    rect.setAttribute('fill', colors[index % colors.length]);
    rect.setAttribute('stroke', '#2f5870');
    svg.appendChild(rect);
    text(svg, left + width + 12, (y1 + y2) / 2,
      `第${layer.ordinal}层 ${layer.depthTop}-${layer.depthBottom} m｜${layer.speedTop}-${layer.speedBottom} m/s`, 12);
    line(svg, left - 8, y1, left, y1, '#506070');
    text(svg, 8, y1 + 4, `${layer.depthTop} m`, 12);
  });
  const bottom = top + 250;
  line(svg, left - 8, bottom, left, bottom, '#506070');
  text(svg, 8, bottom + 4, `${maxDepth} m`, 12);
  text(svg, 70, 18, `${profile.id} 有效：${profile.validFrom} → ${profile.validTo}`, 13, true);
  text(svg, 230, 312, '分层面折射点只归上方一层', 12);

  const legend = $('profileLegend');
  legend.innerHTML = '';
  profile.layers.forEach((layer, index) => {
    const item = document.createElement('span');
    item.innerHTML = `<span class="swatch" style="background:${colors[index % colors.length]}"></span>${layer.id}: ${layer.speedTop} m/s`;
    legend.appendChild(item);
  });
}

function renderRun(data) {
  const run = data.run;
  const metrics = [
    ['总样本', run.totalSamples], ['有效', run.validPoints], ['角度排除', run.excludedPoints],
    ['失败', run.failedPoints], ['时段空档', run.profileGapPoints]
  ];
  $('statusSummary').innerHTML = metrics.map(([label, value]) =>
    `<div class="metric">${label}<strong>${value}</strong></div>`).join('');
  $('profileSelect').value = run.profileId;
  $('attitudeSelect').value = run.attitudeVersionId;
  $('maxAngle').value = run.maxBeamAngleDeg;
  renderProfiles();
  renderRays(data);
  renderCrossTable(data.crossDifferences);
  renderSampleTable(data.results);
  const link = $('exportLink');
  link.href = `/api/runs/${run.id}/export.csv`;
  link.classList.remove('disabled');
  link.setAttribute('aria-disabled', 'false');
  setMessage(`运行 #${run.id}：${run.profileId} + ${run.attitudeVersionId}，排除角 ${run.maxBeamAngleDeg}°。失败样本未使用平均声速替代。`);
}

function renderRays(data) {
  const svg = $('raySvg');
  svg.innerHTML = '';
  const valid = data.results.filter((result) => result.status === 'VALID');
  const depths = valid.map((item) => item.depthM).concat(selectedProfile().maxDepth);
  const across = valid.flatMap((item) => item.vertices.map((vertex) => vertex.acrossM));
  const minAcross = Math.min(-5, ...across);
  const maxAcross = Math.max(5, ...across);
  const maxDepth = Math.max(...depths) * 1.08;
  const margin = 55;
  const width = 1120 - margin * 2;
  const height = 330;
  const sx = (x) => margin + ((x - minAcross) / (maxAcross - minAcross)) * width;
  const sy = (z) => 20 + (z / maxDepth) * height;

  [0, 0.25, 0.5, 0.75, 1].forEach((fraction) => {
    const z = maxDepth * fraction;
    line(svg, margin, sy(z), 1120 - margin, sy(z), '#e8eef3');
    text(svg, 8, sy(z) + 4, `${z.toFixed(0)}m`, 11);
  });

  const byPing = new Map();
  data.results.forEach((result) => {
    if (!byPing.has(result.pingId)) byPing.set(result.pingId, []);
    byPing.get(result.pingId).push(result);
  });

  const colors = ['#1d6b8f', '#d35400', '#7d3c98', '#229954', '#7f8c8d'];
  let colorIndex = 0;
  byPing.forEach((results, pingId) => {
    const color = colors[colorIndex++ % colors.length];
    const originX = results[0].pingEastM;
    const first = results[0];
    text(svg, 20 + colorIndex * 10, 16 + colorIndex * 13, `${pingId}`, 12, true, color);
    results.forEach((result) => {
      if (result.status === 'VALID') {
        const points = result.vertices.map((vertex) => [result.pingEastM + vertex.acrossM, vertex.depthM]);
        polyline(svg, points.map(([x, z]) => `${sx(x)},${sy(z)}`).join(' '), color, 1.6, false);
        circle(svg, sx(result.eastM), sy(result.depthM), 4, color);
      } else {
        const angle = result.correctedAngleDeg || 0;
        const x2 = originX + Math.sin(angle * Math.PI / 180) * 22;
        const z2 = Math.cos(angle * Math.PI / 180) * 22;
        line(svg, sx(originX), sy(0), sx(x2), sy(z2), '#b03a2e', 1, '6,4');
      }
    });
  });
  text(svg, margin, 390, '实线：数值积分求解成功；红色虚线：角度排除、空档、越深或射线受阻。点为保留的海底点。', 12);
}

function renderCrossTable(cross) {
  const body = document.querySelector('#crossTable tbody');
  body.innerHTML = cross.length ? '' : '<tr><td colspan="6">当前方案没有 25 m 阈值内的相邻条带交叉点。</td></tr>';
  cross.forEach((item) => {
    const row = document.createElement('tr');
    row.innerHTML = `<td>${item.leftSampleId}</td><td>${item.rightSampleId}</td>
      <td>${item.leftPingId}</td><td>${item.rightPingId}</td>
      <td>${item.eastGapM.toFixed(3)}</td><td class="${Math.abs(item.depthDeltaM) > 0.1 ? 'status-RAY_BLOCKED' : ''}">${item.depthDeltaM.toFixed(3)}</td>`;
    body.appendChild(row);
  });
}

function renderSampleTable(results) {
  const body = document.querySelector('#sampleTable tbody');
  body.innerHTML = '';
  results.forEach((result) => {
    const row = document.createElement('tr');
    row.innerHTML = `<td>${result.beamSampleId}</td><td>${result.pingId}</td>
      <td class="status-${result.status}">${result.status}</td>
      <td>${result.launchAngleDeg}</td><td>${fmt(result.correctedAngleDeg)}</td>
      <td>${fmt(result.depthM, 4)}</td><td>${fmt(result.eastM, 3)}</td>
      <td>${fmt(result.numericalTolerance, 12)}</td><td>${result.iterations ?? ''}</td>
      <td>${result.layerIds.join(';')}</td><td>${result.attitudeInputSampleIds || ''}</td>`;
    body.appendChild(row);
  });
}

async function executeRun() {
  setMessage('正在用分层声速重放射线积分...');
  const response = await fetch('/api/runs', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({
      profileId: $('profileSelect').value,
      attitudeVersionId: $('attitudeSelect').value,
      maxBeamAngleDeg: Number($('maxAngle').value)
    })
  });
  if (!response.ok) {
    const error = await response.json();
    setMessage(error.error || '裁决失败', true);
    return;
  }
  const data = await response.json();
  renderRun(data);
}

async function replayFixtures() {
  setMessage('正在清空业务数据并重新导入固定 fixture...');
  const response = await fetch('/api/replay', {method: 'POST'});
  const result = await response.json();
  await loadState();
  setMessage(`重新导入完成：${result.profiles} 个剖面、${result.attitudeSamples} 个姿态样本、${result.beams} 个波束样本。`);
}

function fmt(value, digits = 3) {
  return value === null || value === undefined ? '' : Number(value).toFixed(digits);
}

function setMessage(message, isError = false) {
  const element = $('statusMessage');
  element.textContent = message;
  element.style.color = isError ? '#b03a2e' : '#5c6b77';
}

function svgElement(name, attrs) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', name);
  Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, value));
  return element;
}
function line(svg, x1, y1, x2, y2, stroke, width = 1, dash = null) {
  const attrs = {x1, y1, x2, y2, stroke, 'stroke-width': width};
  if (dash) attrs['stroke-dasharray'] = dash;
  svg.appendChild(svgElement('line', attrs));
}
function text(svg, x, y, content, size = 12, bold = false, fill = '#263746') {
  const element = svgElement('text', {x, y, 'font-size': size, fill, 'font-weight': bold ? 700 : 400});
  element.textContent = content;
  svg.appendChild(element);
}
function polyline(svg, points, stroke, width, dashed) {
  svg.appendChild(svgElement('polyline', {
    points, fill: 'none', stroke, 'stroke-width': width,
    'stroke-dasharray': dashed ? '5,4' : 'none'
  }));
}
function circle(svg, cx, cy, r, fill) {
  svg.appendChild(svgElement('circle', {cx, cy, r, fill}));
}

$('runButton').addEventListener('click', executeRun);
$('replayButton').addEventListener('click', replayFixtures);
$('profileSelect').addEventListener('change', renderProfiles);
loadState().catch((error) => setMessage(error.message, true));
