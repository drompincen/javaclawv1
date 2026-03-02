(function(){
const PAGES = [
  {title:'Business Value & Purpose', desc:'Why JavaClaw exists and who benefits'},
  {title:'Architecture', desc:'5 modules, MongoDB, Spring AI'},
  {title:'The 15 Agents', desc:'Controller, specialists, checker'},
  {title:'The Intake Pipeline', desc:'7 phases: raw paste to intelligence'},
  {title:'How Agents Communicate', desc:'3 paths: in-memory, polling, shared DB'},
  {title:'Agent Orchestration Loop', desc:'Controller → Specialist → Checker'},
  {title:'MongoDB Overview', desc:'18 collections in 4 groups'},
  {title:'MongoDB: agents', desc:'Agent definitions — runtime config'},
  {title:'MongoDB: sessions & messages', desc:'Execution contexts, chat history'},
  {title:'MongoDB: events', desc:'Event sourcing — why & how'},
  {title:'MongoDB: Change Streams', desc:'Real-time push — why no broker'},
  {title:'checkpoints, locks, approvals', desc:'State, locking, human-in-loop'},
  {title:'projects & threads', desc:'Containers and knowledge threads'},
  {title:'MongoDB: memories', desc:'Scoped knowledge, TTL, lifecycle'},
  {title:'MongoDB: logs', desc:'Centralized session-aware logging'},
  {title:'things (16 entity types)', desc:'Unified polymorphic entity store'},
  {title:'scheduling & execution', desc:'CRON, leasing, execution history'},
  {title:'llm_interactions', desc:'LLM cost tracking & observability'},
  {title:'On-Demand & Scheduled', desc:'7 schedules, instant triggers'},
  {title:'46 Built-in Tools', desc:'File, code, PM, planning, recon'},
  {title:'Testing Framework', desc:'53 scenarios, 8 tutorials, 272 unit'},
  {title:'Future Roadmap', desc:'Agent dispatch, Jira, SaaS, ML'},
  {title:'Quick Start', desc:'3 steps to get running'},
  {title:'Seq: Gateway Chat Flow', desc:'REST → MongoDB → WebSocket'},
  {title:'Seq: Intake Pipeline', desc:'7-phase async orchestration'},
  {title:'Seq: Multi-Agent Loop', desc:'Controller → Specialist → Checker'},
  {title:'Seq: Forced Agent', desc:'Pipeline mode — no controller'},
];

const PAGE_FILES = [
  'pages/01-business-value.html',
  'pages/02-architecture.html',
  'pages/03-agents.html',
  'pages/04-intake-pipeline.html',
  'pages/05-agent-communication.html',
  'pages/06-orchestration.html',
  'pages/07-mongodb-overview.html',
  'pages/08-mongodb-agents.html',
  'pages/09-mongodb-sessions-messages.html',
  'pages/10-mongodb-events-why.html',
  'pages/11-mongodb-change-streams.html',
  'pages/10-checkpoints-locks-approvals.html',
  'pages/11-projects-threads.html',
  'pages/14-mongodb-memories.html',
  'pages/15-mongodb-logs.html',
  'pages/13-things.html',
  'pages/14-scheduling.html',
  'pages/15-llm-interactions.html',
  'pages/16-on-demand-scheduled.html',
  'pages/17-tools.html',
  'pages/18-testing.html',
  'pages/19-roadmap.html',
  'pages/20-quick-start.html',
  'pages/24-seq-gateway-chat.html',
  'pages/25-seq-gateway-intake.html',
  'pages/26-seq-langgraph-multi-agent.html',
  'pages/27-seq-langgraph-forced.html',
];

const pageCache = new Map();
let current = -1;
const visited = new Set();
const landing = document.getElementById('landingPage');
const bottombar = document.getElementById('bottombar');
const prevBtn = document.getElementById('prevBtn');
const nextBtn = document.getElementById('nextBtn');
const dots = document.getElementById('progressDots');
const indicator = document.getElementById('stepIndicator');
const grid = document.getElementById('topicGrid');
const container = document.getElementById('pageContainer');

PAGES.forEach((p,i)=>{
  const c = document.createElement('div');
  c.className = 'topic-card';
  c.innerHTML = `<div class="topic-num">${i+1}</div><div class="topic-title">${p.title}</div><div class="topic-desc">${p.desc}</div>`;
  c.onclick = ()=> goToPage(i);
  grid.appendChild(c);
});

PAGES.forEach((_,i)=>{
  const d = document.createElement('div');
  d.className = 'progress-dot';
  d.title = PAGES[i].title;
  d.onclick = ()=> goToPage(i);
  dots.appendChild(d);
});

function goHome(){
  current = -1;
  container.classList.remove('active');
  container.innerHTML = '';
  landing.classList.add('active');
  bottombar.style.display = 'none';
  indicator.textContent = '';
}
window.goHome = goHome;

async function goToPage(idx){
  if(idx < 0 || idx >= PAGES.length) return;
  current = idx;
  visited.add(idx);

  if(!pageCache.has(idx)){
    const resp = await fetch(PAGE_FILES[idx]);
    pageCache.set(idx, await resp.text());
  }

  landing.classList.remove('active');
  container.innerHTML = `<div class="page-inner">${pageCache.get(idx)}</div>`;
  container.classList.add('active');

  bottombar.style.display = 'flex';
  prevBtn.disabled = idx === 0;
  nextBtn.textContent = idx === PAGES.length-1 ? '⌂ Home' : 'Next →';
  indicator.textContent = `${idx+1} / ${PAGES.length}: ${PAGES[idx].title}`;
  dots.querySelectorAll('.progress-dot').forEach((d,i)=>{
    d.classList.toggle('active', i===idx);
    d.classList.toggle('visited', visited.has(i) && i!==idx);
  });
  if(idx === 3) animatePipeline();

  if(typeof mermaid !== 'undefined'){
    const mermaids = container.querySelectorAll('.mermaid');
    if(mermaids.length > 0){
      mermaids.forEach(el => { el.removeAttribute('data-processed'); });
      mermaid.run({ nodes: mermaids });
    }
  }
}
window.goToPage = goToPage;

function prevPage(){ if(current > 0) goToPage(current-1); }
window.prevPage = prevPage;
function nextPage(){
  if(current === PAGES.length-1) goHome();
  else if(current >= 0) goToPage(current+1);
}
window.nextPage = nextPage;

document.addEventListener('keydown', e=>{
  if(e.key==='ArrowRight'||e.key==='ArrowDown'){e.preventDefault();if(current>=0)nextPage();}
  if(e.key==='ArrowLeft'||e.key==='ArrowUp'){e.preventDefault();if(current>0)prevPage();}
  if(e.key==='Escape'||e.key==='Home'){e.preventDefault();goHome();}
});

function animatePipeline(){
  const steps = document.querySelectorAll('.pl-step');
  steps.forEach((s,i)=>{s.classList.remove('on');setTimeout(()=>s.classList.add('on'),i*300);});
}

const canvas = document.getElementById('particles');
const ctx = canvas.getContext('2d');
let particles = [];
function resize(){canvas.width=window.innerWidth;canvas.height=window.innerHeight}
resize();window.addEventListener('resize',resize);
class P{
  constructor(){this.reset()}
  reset(){this.x=Math.random()*canvas.width;this.y=Math.random()*canvas.height;this.vx=(Math.random()-.5)*.25;this.vy=(Math.random()-.5)*.25;this.s=Math.random()*1.5+.5;this.a=Math.random()*.2+.05}
  update(){this.x+=this.vx;this.y+=this.vy;if(this.x<0||this.x>canvas.width)this.vx*=-1;if(this.y<0||this.y>canvas.height)this.vy*=-1}
  draw(){ctx.beginPath();ctx.arc(this.x,this.y,this.s,0,Math.PI*2);ctx.fillStyle=`rgba(255,153,0,${this.a})`;ctx.fill()}
}
for(let i=0;i<50;i++)particles.push(new P());
function animate(){
  ctx.clearRect(0,0,canvas.width,canvas.height);
  particles.forEach(p=>{p.update();p.draw()});
  for(let i=0;i<particles.length;i++)for(let j=i+1;j<particles.length;j++){
    const dx=particles[i].x-particles[j].x,dy=particles[i].y-particles[j].y,d=Math.sqrt(dx*dx+dy*dy);
    if(d<120){ctx.beginPath();ctx.moveTo(particles[i].x,particles[i].y);ctx.lineTo(particles[j].x,particles[j].y);ctx.strokeStyle=`rgba(255,153,0,${.05*(1-d/120)})`;ctx.lineWidth=.5;ctx.stroke()}
  }
  requestAnimationFrame(animate);
}
animate();
})();
