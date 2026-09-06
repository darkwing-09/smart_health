/**
 * HealthAgent — Interactive Client Engine & Telemetry Controller
 * Connects to FastAPI REST endpoints and real-time WebSocket stream.
 */

// Application State
const AppState = {
  token: null,
  userId: 'a0000000-0000-0000-0000-000000000001',
  sourceId: '11111111-2222-3333-4444-555555555555',
  ws: null,
  findings: [],
  activeFilter: 'all',
  currentModalFinding: null,
  vitals: {
    heartRate: 72,
    steps: 6840,
    spo2: 98,
    sleepHours: 7.4
  }
};

// Auto-initialize on DOM ready
document.addEventListener('DOMContentLoaded', async () => {
  initTabs();
  await authenticateClient();
  initWebSocket();
  loadVitals();
  loadFindings();
  setupEventListeners();
});

/* ==========================================================================
   Navigation Tabs
   ========================================================================== */

function initTabs() {
  const tabs = document.querySelectorAll('.tab-item');
  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      const targetId = tab.getAttribute('data-tab');
      
      // Update active tab button
      tabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      // Update visible section
      document.querySelectorAll('.tab-content').forEach(content => {
        content.classList.remove('active');
      });
      const targetContent = document.getElementById(targetId);
      if (targetContent) {
        targetContent.classList.add('active');
      }
    });
  });
}

/* ==========================================================================
   Authentication
   ========================================================================== */

async function authenticateClient() {
  try {
    const res = await fetch('/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        email: 'demo@healthos.me',
        password: 'SecurePilotPassword123!'
      })
    });
    if (res.ok) {
      const data = await res.json();
      AppState.token = data.access_token;
      AppState.userId = data.user_id;
      logStream('info', `[AUTH] Authenticated as ${data.user_id.slice(0, 8)}... (JWT acquired)`);
    } else {
      logStream('warn', '[AUTH] Using unauthenticated public mode for telemetry inspection.');
    }
  } catch (err) {
    logStream('warn', `[AUTH] Direct login bypass: ${err.message}`);
  }
}

function getAuthHeaders() {
  const headers = { 'Content-Type': 'application/json' };
  if (AppState.token) {
    headers['Authorization'] = `Bearer ${AppState.token}`;
  }
  return headers;
}

/* ==========================================================================
   Real-Time WebSocket Stream
   ========================================================================== */

function initWebSocket() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${protocol}//${window.location.host}/v1/ws`;
  
  try {
    const ws = new WebSocket(wsUrl);
    AppState.ws = ws;

    ws.onopen = () => {
      document.getElementById('wsProbePill')?.classList.add('ok');
      const dot = document.getElementById('streamStatusDot');
      if (dot) dot.className = 'status-dot green';
      const statusText = document.getElementById('streamConnectionStatus');
      if (statusText) statusText.textContent = 'Connected (Active)';
      logStream('success', `[WS] Connected to ${wsUrl} — Live Telemetry Listening`);
    };

    ws.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data);
        handleIncomingTelemetry(payload);
      } catch {
        logStream('info', `[RAW] ${event.data}`);
      }
    };

    ws.onerror = (err) => {
      logStream('error', `[WS_ERROR] WebSocket transport error`);
    };

    ws.onclose = () => {
      const dot = document.getElementById('streamStatusDot');
      if (dot) dot.className = 'status-dot';
      const statusText = document.getElementById('streamConnectionStatus');
      if (statusText) statusText.textContent = 'Reconnecting...';
      logStream('warn', `[WS] Disconnected. Retrying in 5s...`);
      setTimeout(initWebSocket, 5000);
    };
  } catch (e) {
    logStream('error', `[WS_INIT_FAILED] ${e.message}`);
  }
}

function handleIncomingTelemetry(data) {
  logStream('info', `[EVENT] ${data.type || 'finding'}: ${JSON.stringify(data.payload || data)}`);
  
  // If it is a finding notification, prepend to findings feed
  if (data.type === 'finding_created' || data.severity) {
    loadFindings();
  }
  // If live vitals update
  if (data.metric_type) {
    updateVitalCard(data.metric_type, data.value);
  }
}

function logStream(type, message) {
  const consoleEl = document.getElementById('streamConsole');
  if (!consoleEl) return;
  const line = document.createElement('div');
  line.className = `console-line ${type}`;
  const now = new Date().toLocaleTimeString();
  line.textContent = `[${now}] ${message}`;
  consoleEl.appendChild(line);
  consoleEl.scrollTop = consoleEl.scrollHeight;
}

function clearStreamLog() {
  const consoleEl = document.getElementById('streamConsole');
  if (consoleEl) {
    consoleEl.innerHTML = '<div class="console-line info">[SYSTEM] Console cleared. Listening for stream events...</div>';
  }
}

/* ==========================================================================
   Vitals & Metrics Controller
   ========================================================================== */

async function loadVitals() {
  const metrics = ['heart_rate', 'steps', 'spo2'];
  for (const metric of metrics) {
    try {
      const res = await fetch(`/v1/measurements/timeline?metric_type=${metric}&limit=1`, {
        headers: getAuthHeaders()
      });
      if (res.ok) {
        const data = await res.json();
        if (data.measurements && data.measurements.length > 0) {
          updateVitalCard(data.measurements[0].metric_type, data.measurements[0].value);
        }
      }
    } catch (err) {
      // Fallback to initial display values for this metric
    }
  }

  // Set default initial values for any metrics not loaded from API
  renderVitalsUI();
}

function updateVitalCard(metricType, value) {
  if (metricType === 'heart_rate') {
    AppState.vitals.heartRate = Math.round(value);
    document.getElementById('valHeartRate').textContent = AppState.vitals.heartRate;
    document.getElementById('timeHeartRate').textContent = 'Just now via Noise Watch';
  } else if (metricType === 'steps') {
    AppState.vitals.steps = Math.round(value);
    document.getElementById('valSteps').textContent = AppState.vitals.steps.toLocaleString();
  } else if (metricType === 'spo2') {
    AppState.vitals.spo2 = Math.round(value);
    document.getElementById('valSpO2').textContent = AppState.vitals.spo2;
  }
}

function renderVitalsUI() {
  document.getElementById('valHeartRate').textContent = AppState.vitals.heartRate;
  document.getElementById('valSteps').textContent = AppState.vitals.steps.toLocaleString();
  document.getElementById('valSpO2').textContent = AppState.vitals.spo2;
  document.getElementById('valSleep').textContent = AppState.vitals.sleepHours;
}

/* ==========================================================================
   Findings Feed & 7-Part Explainability
   ========================================================================== */

async function loadFindings() {
  const feed = document.getElementById('findingsFeed');
  const miniList = document.getElementById('miniFindingsList');
  
  try {
    const res = await fetch('/v1/findings?limit=20', {
      headers: getAuthHeaders()
    });
    if (res.ok) {
      const findings = await res.json();
      if (Array.isArray(findings) && findings.length > 0) {
        AppState.findings = findings;
        renderFindingsFeed();
        renderMiniFindings();
        updateFindingsBadges();
        return;
      }
    }
  } catch (e) {
    logStream('warn', `Findings query: ${e.message}`);
  }

  // Default Mock Findings for demonstration if empty
  AppState.findings = getDemoFindings();
  renderFindingsFeed();
  renderMiniFindings();
  updateFindingsBadges();
}

function updateFindingsBadges() {
  const badge = document.getElementById('findingsCountBadge');
  const miniCount = document.getElementById('miniFindingsCount');
  const totalLabel = document.getElementById('findingsTotalLabel');
  
  const count = AppState.findings.length;
  if (badge) badge.textContent = count;
  if (miniCount) miniCount.textContent = `${count} Observations`;
  if (totalLabel) totalLabel.textContent = `Showing ${count} longitudinal observations`;
}

function renderFindingsFeed() {
  const feed = document.getElementById('findingsFeed');
  if (!feed) return;

  const filtered = AppState.findings.filter(f => {
    if (AppState.activeFilter === 'all') return true;
    return f.severity === AppState.activeFilter;
  });

  if (filtered.length === 0) {
    feed.innerHTML = '<div class="panel-glass"><p style="color: var(--text-muted);">No findings in this category. All vitals nominal.</p></div>';
    return;
  }

  feed.innerHTML = filtered.map((finding, idx) => {
    const tierMap = {
      urgent: { label: 'LEVEL 4: URGENT', class: 'urgent' },
      important: { label: 'LEVEL 3: IMPORTANT', class: 'important' },
      attention: { label: 'LEVEL 2: ATTENTION', class: 'attention' },
      insight: { label: 'LEVEL 1: INSIGHT', class: 'insight' }
    };
    const meta = tierMap[finding.severity] || { label: 'INFO', class: 'nominal' };
    const dateStr = finding.detected_at ? new Date(finding.detected_at).toLocaleString() : 'Recent Window';

    return `
      <div class="finding-card ${meta.class}">
        <div class="finding-card-header">
          <span class="tier-badge ${meta.class}">${meta.label}</span>
          <span class="finding-time">${dateStr}</span>
        </div>
        <h4 class="finding-title">${finding.title || finding.finding_type || 'Physiological Observation'}</h4>
        <p class="finding-summary">${finding.summary || finding.explanation?.what_changed || 'Statistically significant deviation from your rolling 30-day baseline.'}</p>
        <div class="finding-card-actions">
          <button class="btn-explain" onclick="openExplainModal(${idx})">
            🔍 View 7-Part Explainability
          </button>
          <button class="btn-ack" onclick="acknowledgeFindingById('${finding.id}')">
            ✓ Acknowledge
          </button>
        </div>
      </div>
    `;
  }).join('');
}

function renderMiniFindings() {
  const miniList = document.getElementById('miniFindingsList');
  if (!miniList) return;

  miniList.innerHTML = AppState.findings.slice(0, 4).map((f, idx) => `
    <div class="finding-mini-item ${f.severity}" onclick="openExplainModal(${idx})">
      <div class="finding-mini-title">${f.title || 'Physiological Observation'}</div>
      <div class="finding-mini-sub">${f.severity.toUpperCase()} • ${f.metric_type || 'Biometrics'}</div>
    </div>
  `).join('');
}

function openExplainModal(idx) {
  const finding = AppState.findings[idx];
  if (!finding) return;
  AppState.currentModalFinding = finding;

  const modal = document.getElementById('explainModal');
  const badge = document.getElementById('modalSeverityBadge');
  const title = document.getElementById('modalFindingTitle');

  badge.textContent = (finding.severity || 'ATTENTION').toUpperCase();
  badge.className = `severity-badge ${finding.severity}`;
  title.textContent = finding.title || 'Physiological Deviation Analysis';

  const exp = finding.explanation || {};
  document.getElementById('partWhatChanged').textContent = exp.what_changed || `Significant deviation recorded in ${finding.metric_type || 'heart rate'}.`;
  document.getElementById('partMeasurements').textContent = exp.measurements || `Trigger value = ${finding.value || '162'} ${finding.unit || 'bpm'}, Steps = 0.`;
  document.getElementById('partBaselineDiff').textContent = exp.baseline_diff || `Compared to your 30-day baseline of 66.4 bpm (Z = +4.8σ).`;
  document.getElementById('partHistorical').textContent = exp.historical_context || `Occurred 2 times in the previous 45 days.`;
  document.getElementById('partConfidence').textContent = exp.confidence || `Optical PPG contact nominal (98% signal clarity).`;
  document.getElementById('partPhysiology').textContent = exp.physiological_meaning || `Resting tachycardia without concurrent exertion signals sympathetic activation or acute recovery demand.`;
  document.getElementById('partNextSteps').textContent = exp.next_steps || `Rest in a comfortable posture, drink water, and observe. If symptomatic, seek professional medical care.`;

  modal.style.display = 'flex';
}

function closeExplainModal() {
  document.getElementById('explainModal').style.display = 'none';
}

function acknowledgeCurrentFinding() {
  if (AppState.currentModalFinding) {
    acknowledgeFindingById(AppState.currentModalFinding.id);
    closeExplainModal();
  }
}

async function acknowledgeFindingById(findingId) {
  logStream('info', `[FINDING_ACK] Acknowledged finding ${findingId}`);
  try {
    await fetch(`/v1/findings/${findingId}/acknowledge`, {
      method: 'POST',
      headers: getAuthHeaders()
    });
  } catch (e) {
    // Local state fallback
  }
  AppState.findings = AppState.findings.filter(f => f.id !== findingId);
  renderFindingsFeed();
  renderMiniFindings();
  updateFindingsBadges();
}

/* ==========================================================================
   Noise Watch Telemetry Simulator
   ========================================================================== */

async function sendSimulatedBatch(preset) {
  const feedbackBox = document.getElementById('simFeedbackBox');
  const feedbackStatus = document.getElementById('simFeedbackStatus');
  const feedbackLog = document.getElementById('simFeedbackLog');
  const feedbackTime = document.getElementById('simFeedbackTime');

  feedbackBox.style.display = 'block';
  feedbackStatus.textContent = '⏳ Formulating batch & sending to /v1/sync/batch...';
  feedbackStatus.style.color = 'var(--cyan-primary)';

  const now = new Date();
  const nowIso = now.toISOString();

  let hrVal = 68;
  let stepsVal = 0;
  let spo2Val = 99;
  let label = 'Normal Resting';

  if (preset === 'exertion') {
    hrVal = 138;
    stepsVal = 140;
    spo2Val = 98;
    label = 'Exercise Exertion';
  } else if (preset === 'bradycardia') {
    hrVal = 42;
    stepsVal = 0;
    spo2Val = 97;
    label = 'Nocturnal Bradycardia';
  } else if (preset === 'urgent_tachycardia') {
    hrVal = 165;
    stepsVal = 0;
    spo2Val = 96;
    label = 'Acute Resting Tachycardia Spike';
  }

  const batchPayload = {
    source_id: AppState.sourceId,
    client_sync_timestamp: nowIso,
    measurements: [
      {
        source_record_id: `noise_rec_${Date.now()}_hr`,
        metric_type: 'heart_rate',
        value: hrVal,
        unit: 'bpm',
        recorded_at: nowIso,
        confidence: 0.98,
        data_quality_flag: 'nominal'
      },
      {
        source_record_id: `noise_rec_${Date.now()}_steps`,
        metric_type: 'steps',
        value: stepsVal,
        unit: 'count',
        recorded_at: nowIso,
        confidence: 1.0,
        data_quality_flag: 'nominal'
      },
      {
        source_record_id: `noise_rec_${Date.now()}_spo2`,
        metric_type: 'spo2',
        value: spo2Val,
        unit: '%',
        recorded_at: nowIso,
        confidence: 0.95,
        data_quality_flag: 'nominal'
      }
    ]
  };

  try {
    const res = await fetch('/v1/sync/batch', {
      method: 'POST',
      headers: {
        ...getAuthHeaders(),
        'Idempotency-Key': crypto.randomUUID()
      },
      body: JSON.stringify(batchPayload)
    });

    const data = await res.json();
    feedbackStatus.textContent = `✓ Successfully Ingested (${label})`;
    feedbackStatus.style.color = 'var(--emerald-success)';
    feedbackTime.textContent = new Date().toLocaleTimeString();
    feedbackLog.textContent = JSON.stringify(data, null, 2);

    logStream('success', `[SIMULATOR] Ingested 3 records from Noise Watch (${label}): HR=${hrVal} bpm, Steps=${stepsVal}`);

    // Update local card values
    updateVitalCard('heart_rate', hrVal);
    updateVitalCard('steps', AppState.vitals.steps + stepsVal);
    updateVitalCard('spo2', spo2Val);

    // If urgent tachycardia, inject finding locally
    if (preset === 'urgent_tachycardia') {
      const urgentFinding = {
        id: crypto.randomUUID(),
        severity: 'urgent',
        title: 'Acute Nocturnal Tachycardia (HR = 165 bpm)',
        value: 165,
        unit: 'bpm',
        metric_type: 'heart_rate',
        detected_at: nowIso,
        summary: 'Sustained resting elevation of +14.8 standard deviations with zero concurrent motion.',
        explanation: {
          what_changed: 'Acute resting heart rate jump to 165 bpm while inactive.',
          measurements: 'Resting Heart Rate: 165 bpm. Steps: 0. Optical fit: Nominal.',
          baseline_diff: 'Personal circadian baseline is 64.2 bpm. Deviation is +14.8σ.',
          historical_context: 'First acute spike detected this calendar month.',
          confidence: 'Signal quality 98% with zero motion artifacts.',
          physiological_meaning: 'Elevated sympathetic tone or acute compensatory physiological response.',
          next_steps: 'Rest quietly in a comfortable position and hydrate. Seek emergency care if symptomatic.'
        }
      };
      AppState.findings.unshift(urgentFinding);
      renderFindingsFeed();
      renderMiniFindings();
      updateFindingsBadges();
      logStream('error', `🚨 [ALERT] Level 4 Urgent Anomaly Triggered: Resting HR = 165 bpm!`);
    }

  } catch (err) {
    feedbackStatus.textContent = `❌ Transmission Failed: ${err.message}`;
    feedbackStatus.style.color = 'var(--crimson-urgent)';
    feedbackLog.textContent = err.stack || err.message;
    logStream('error', `[SIM_ERROR] ${err.message}`);
  }
}

/* ==========================================================================
   Care Navigation & PDF Export
   ========================================================================== */

function downloadLatestPdf() {
  logStream('info', `[PDF] Initiating ReportLab vector PDF generation...`);
  window.open('/v1/reports/latest/pdf', '_blank');
}

async function generateNewReport() {
  logStream('info', `[REPORT] Compiling 24-Hour Health Narrative...`);
  try {
    const res = await fetch('/v1/reports/compile', {
      method: 'POST',
      headers: getAuthHeaders()
    });
    if (res.ok) {
      logStream('success', `[REPORT] Daily Report compiled successfully with dynamic stoic quote.`);
      alert('✓ Fresh Daily Health Digest compiled successfully!');
    }
  } catch (e) {
    logStream('warn', `Report compilation trigger: ${e.message}`);
  }
}

/* ==========================================================================
   Filter and Event Wiring
   ========================================================================== */

function setupEventListeners() {
  // Sync now button
  document.getElementById('btnSyncNow')?.addEventListener('click', () => {
    sendSimulatedBatch('nominal');
  });

  // Finding filter buttons
  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      AppState.activeFilter = btn.getAttribute('data-filter');
      renderFindingsFeed();
    });
  });
}

function getDemoFindings() {
  return [
    {
      id: 'f-001',
      severity: 'urgent',
      title: 'Nocturnal Heart Rate Elevation (154 bpm)',
      metric_type: 'heart_rate',
      detected_at: new Date(Date.now() - 3600000).toISOString(),
      summary: 'Resting heart rate reached 154 bpm at 03:22 AM with zero accelerometer movement.',
      explanation: {
        what_changed: 'Heart rate spiked to 154 bpm during expected deep sleep window.',
        measurements: 'HR = 154 bpm, Steps = 0, Motion Index = 0.02.',
        baseline_diff: 'Expected baseline 62 bpm ± 5.8 bpm (+15.8 standard deviations).',
        historical_context: 'Previous occurrence recorded 18 days ago.',
        confidence: 'PPG sensor fit rated excellent (99% confidence).',
        physiological_meaning: 'Acute autonomic activation during rest.',
        next_steps: 'Rest quietly and observe. If accompanied by shortness of breath, consult emergency services.'
      }
    },
    {
      id: 'f-002',
      severity: 'important',
      title: 'Persistent Nocturnal Tachycardia',
      metric_type: 'heart_rate',
      detected_at: new Date(Date.now() - 86400000).toISOString(),
      summary: 'Resting heart rate remained +3.9σ above circadian mean for 3 consecutive hours.',
      explanation: {
        what_changed: 'Elevated resting pulse across hours 01:00 to 04:00.',
        measurements: 'Mean HR = 88 bpm vs expected 62 bpm.',
        baseline_diff: 'Deviation of +3.9 standard deviations.',
        historical_context: 'Occurred 3 times over past 30 days.',
        confidence: 'Nominal sensor contact throughout night.',
        physiological_meaning: 'Elevated sympathetic tone, potential mild dehydration or late meal.',
        next_steps: 'Review evening routine and ensure optimal hydration.'
      }
    },
    {
      id: 'f-003',
      severity: 'attention',
      title: 'Step Count Deficit vs. 30-Day Trend',
      metric_type: 'steps',
      detected_at: new Date(Date.now() - 172800000).toISOString(),
      summary: 'Cumulative activity at 18:00 was 3,200 steps (baseline typical: 8,500 steps).',
      explanation: {
        what_changed: 'Marked reduction in daytime movement volume.',
        measurements: 'Steps = 3,200 vs 30-day hourly mean of 8,500.',
        baseline_diff: '-2.8σ lower than user baseline.',
        historical_context: 'Sedentary pattern occurs on approximately 2 days per month.',
        confidence: 'Step counter nominal.',
        physiological_meaning: 'Prolonged stationary desk work or recovery period.',
        next_steps: 'Consider a light 15-minute evening stroll.'
      }
    }
  ];
}
