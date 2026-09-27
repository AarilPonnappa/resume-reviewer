// the last analysis shown on the page
let lastResult = null;

// the resume file the user picked (from the file input or drag & drop)
let selectedFile = null;

// rotating messages while Gemini works (a full analysis usually takes 20-60 seconds)
const PROGRESS_MESSAGES = [
    'Reading your resume...',
    'Scoring it against the rubric...',
    'Finding your weakest bullets...',
    'Restructuring your resume...',
    'Fact-checking every line against your original...',
    'Building your roadmap...',
    'Almost there...'
];
let progressTimer = null;

const MAX_FILE_BYTES = 5 * 1024 * 1024;

// On page load: show the Gemini key status, wire up the file picker, and open a saved review if ?id= is in the URL
document.addEventListener('DOMContentLoaded', () => {
    refreshGeminiKeyStatus();
    setupFilePicker();

    // the PDF preview is only generated when the "Improved resume" tab is opened
    document.getElementById('resumeTabBtn').addEventListener('shown.bs.tab', loadPdfPreview);

    const id = new URLSearchParams(window.location.search).get('id');
    if (id) {
        loadSavedReview(id);
    }
});


// Ask the server whether a key is available. Only returns booleans, never the key itself
async function refreshGeminiKeyStatus() {
    const statusEl = document.getElementById('geminiKeyStatus');
    try {
        const res = await fetch('/gemini-key/status');
        const data = await res.json();
        if (data.hasKey) {
            statusEl.textContent = 'Using the key you saved for this session.';
        } else if (data.serverKey) {
            statusEl.textContent = 'Using the server\'s key. You can paste your own if you prefer.';
        } else {
            statusEl.textContent = 'No key yet. Click "Manage key", paste your Gemini API key and click "Save key".';
        }
    } catch (err) {
        statusEl.textContent = 'Could not check Gemini API key status.';
    }
}

// triggered by the "Save key" button
async function saveGeminiKey() {
    const input = document.getElementById('geminiApiKey');
    const geminiApiKey = input.value.trim();

    if (!geminiApiKey) {
        setStatus('Paste a Gemini API key first.');
        return;
    }

    try {
        const res = await fetch('/gemini-key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ geminiApiKey: geminiApiKey })
        });
        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.error || 'Could not save Gemini API key.');
        }

        // clear the field now that the key is in the session
        input.value = '';
        setStatus('Gemini API key saved for this session.');
        refreshGeminiKeyStatus();
    } catch (err) {
        setStatus('Error: ' + err.message);
        console.error(err);
    }
}

// triggered by the "Clear key" button
async function clearGeminiKey() {
    document.getElementById('geminiApiKey').value = '';
    try {
        await fetch('/gemini-key/clear', { method: 'POST' });
    } catch (err) {
        console.error('Failed to clear Gemini API key on the server: ', err);
    }
    setStatus('Gemini API key cleared.');
    refreshGeminiKeyStatus();
}


function setupFilePicker() {
    const input = document.getElementById('resumeFile');
    const dropZone = document.getElementById('dropZone');

    input.onchange = () => pickFile(input.files[0]);

    dropZone.addEventListener('dragover', (event) => {
        event.preventDefault();
        dropZone.classList.add('dragging');
    });
    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragging'));
    dropZone.addEventListener('drop', (event) => {
        event.preventDefault();
        dropZone.classList.remove('dragging');
        pickFile(event.dataTransfer.files[0]);
    });
}

function pickFile(file) {
    if (!file) return;

    const name = file.name.toLowerCase();
    const allowed = ['.pdf', '.docx', '.txt', '.md'];
    if (!allowed.some(ext => name.endsWith(ext))) {
        setStatus('Please choose a PDF, DOCX or TXT file.');
        return;
    }
    if (file.size > MAX_FILE_BYTES) {
        setStatus('That file is over 5 MB.');
        return;
    }

    selectedFile = file;
    document.getElementById('fileLabel').textContent = file.name;
    setStatus('');
}



// triggered by the "Analyze resume" button
async function analyzeResume() {
    if (!selectedFile) {
        setStatus('Choose a resume file first.');
        return;
    }

    // multipart/form-data: the file plus the two optional text fields
    const formData = new FormData();
    formData.append('file', selectedFile);
    formData.append('targetRole', document.getElementById('targetRole').value.trim());
    formData.append('jobDescription', document.getElementById('jobDescription').value.trim());

    setBusy(true);

    try {
        const res = await fetch('/analyze', {
            method: 'POST',
            body: formData
        });

        // parse response body as JSON
        const data = await res.json();

        // if request fails throw to catch block below
        if (!res.ok) {
            if (res.status === 401) {
                window.location.href = '/login.html';
                return;
            }
            throw new Error(data.error || 'Request failed.');
        }

        lastResult = data;
        renderResults(data);

        // put the review id in the URL so a refresh (or a bookmark) reopens this review
        history.replaceState(null, '', '/review?id=' + data.id);
        setStatus('Done! Scroll down for your results.');
        document.getElementById('results').scrollIntoView({ behavior: 'smooth' });
    } catch (err) {
        setStatus('Error: ' + err.message);
        console.error(err);
    } finally {
        setBusy(false);
    }
}

// opens a review from the history page (review.html?id=5)
async function loadSavedReview(id) {
    setStatus('Loading saved review...');
    try {
        const res = await fetch('/reviews/' + encodeURIComponent(id));
        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.error || 'Could not load that review.');
        }
        lastResult = data;
        renderResults(data);
        setStatus('');
    } catch (err) {
        setStatus('Error: ' + err.message);
    }
}

// disables the button and cycles progress messages while the request runs
function setBusy(busy) {
    document.getElementById('analyzeBtn').disabled = busy;
    document.getElementById('analyzeSpinner').classList.toggle('d-none', !busy);

    clearInterval(progressTimer);
    if (busy) {
        let step = 0;
        setStatus(PROGRESS_MESSAGES[0]);
        progressTimer = setInterval(() => {
            step = Math.min(step + 1, PROGRESS_MESSAGES.length - 1);
            setStatus(PROGRESS_MESSAGES[step]);
        }, 7000);
    }
}


function renderResults(data) {
    const review = data.review || {};

    document.getElementById('results').classList.remove('d-none');
    document.getElementById('resultFileName').textContent = data.fileName || 'Resume';
    document.getElementById('resultMeta').textContent =
        (data.targetRole ? 'Target role: ' + data.targetRole + ' · ' : '') + formatDate(data.createdAt);

    renderScore(review);
    renderCategories(review.categoryScores || []);
    renderSuggestions(review.suggestions || []);
    renderStrengths(review.strengths || []);
    renderKeywords(review.missingKeywords || []);
    renderRewrites(review.bulletRewrites || []);
    renderFuture(review.futureRecommendations || []);
    renderResumeTab(data);
}

function renderScore(review) {
    const score = review.overallScore || 0;
    document.getElementById('overallScore').textContent = score;

    const grade = document.getElementById('letterGrade');
    grade.textContent = review.letterGrade || '-';
    grade.className = 'grade-pill ' + scoreClass(score, 'grade');

    document.getElementById('detectedRole').textContent = review.detectedRole || '';
    document.getElementById('verdict').textContent = review.verdict || '';

    // the ring is a circle whose visible length is proportional to the score
    const ring = document.getElementById('scoreRing');
    const circumference = 2 * Math.PI * 64;
    ring.setAttribute('stroke', score >= 80 ? '#16a34a' : score >= 65 ? '#d97706' : '#dc2626');
    ring.setAttribute('stroke-dasharray', circumference.toFixed(1));
    ring.setAttribute('stroke-dashoffset', (circumference * (1 - score / 100)).toFixed(1));
}

function renderCategories(categories) {
    const list = document.getElementById('categoryList');
    list.innerHTML = '';

    categories.forEach(category => {
        const row = el('div', 'category-row py-2');

        const header = el('div', 'd-flex justify-content-between align-items-baseline mb-1');
        header.appendChild(el('span', 'fw-semibold', category.category + ' '));
        const right = el('span', 'small text-secondary');
        right.appendChild(el('span', 'fw-bold text-dark', String(category.score)));
        right.appendChild(document.createTextNode(' / 100 · weight ' + category.weight + '%'));
        header.appendChild(right);

        const bar = el('div', 'category-bar mb-1');
        const fill = el('div', scoreClass(category.score, 'bar'));
        fill.style.width = Math.max(2, category.score) + '%';
        bar.appendChild(fill);

        row.appendChild(header);
        row.appendChild(bar);
        if (category.feedback) {
            row.appendChild(el('div', 'small text-secondary', category.feedback));
        }
        list.appendChild(row);
    });
}

function renderSuggestions(suggestions) {
    const list = document.getElementById('suggestionsList');
    list.innerHTML = '';
    if (suggestions.length === 0) {
        list.appendChild(el('p', 'text-secondary', 'No suggestions.'));
        return;
    }

    suggestions.forEach(suggestion => {
        const card = el('div', 'suggestion ' + suggestion.priority);

        const top = el('div', 'd-flex align-items-center gap-2 mb-1');
        top.appendChild(el('span', 'priority-badge priority-' + suggestion.priority, suggestion.priority));
        if (suggestion.section) {
            top.appendChild(el('span', 'small fw-semibold text-secondary', suggestion.section));
        }
        card.appendChild(top);

        if (suggestion.issue) {
            card.appendChild(el('div', 'fw-semibold', suggestion.issue));
        }
        if (suggestion.recommendation) {
            card.appendChild(el('div', 'small', suggestion.recommendation));
        }
        list.appendChild(card);
    });
}

function renderStrengths(strengths) {
    const list = document.getElementById('strengthsList');
    list.innerHTML = '';
    strengths.forEach(strength => list.appendChild(el('div', 'strength-item', strength)));
}

function renderKeywords(keywords) {
    const list = document.getElementById('keywordsList');
    list.innerHTML = '';
    if (keywords.length === 0) {
        list.appendChild(el('p', 'small text-secondary', 'None: your resume already covers the key terms.'));
        return;
    }
    keywords.forEach(keyword => list.appendChild(el('span', 'keyword-chip', keyword)));
}

function renderRewrites(rewrites) {
    const list = document.getElementById('rewritesList');
    list.innerHTML = '';
    if (rewrites.length === 0) {
        list.appendChild(el('p', 'text-secondary', 'No bullet rewrites were suggested.'));
        return;
    }

    rewrites.forEach(rewrite => {
        const card = el('div', 'rewrite-card');

        const before = el('div', 'rewrite-before');
        before.appendChild(el('div', 'rewrite-tag', 'Before' + (rewrite.section ? ' · ' + rewrite.section : '')));
        before.appendChild(el('div', '', rewrite.original));

        const after = el('div', 'rewrite-after');
        after.appendChild(el('div', 'rewrite-tag', 'After'));
        after.appendChild(withPlaceholders(rewrite.improved));

        card.appendChild(before);
        card.appendChild(after);
        if (rewrite.reason) {
            card.appendChild(el('div', 'small text-secondary px-3 py-2', 'Why: ' + rewrite.reason));
        }
        list.appendChild(card);
    });
}

// highlights [placeholders] like [X%] so it's obvious they need a real number
function withPlaceholders(text) {
    const container = el('div', '');
    const pieces = (text || '').split(/(\[[^\]]+\])/);
    pieces.forEach(piece => {
        if (/^\[[^\]]+\]$/.test(piece)) {
            container.appendChild(el('span', 'placeholder-mark', piece));
        } else if (piece) {
            container.appendChild(document.createTextNode(piece));
        }
    });
    return container;
}

function renderFuture(recommendations) {
    const list = document.getElementById('futureList');
    list.innerHTML = '';

    recommendations.forEach(recommendation => {
        const col = el('div', 'col-md-6');
        const card = el('div', 'future-card');

        card.appendChild(el('span', 'type-badge', recommendation.type.replace('_', ' ')));
        card.appendChild(el('h6', 'fw-bold mt-2 mb-1', recommendation.title));
        if (recommendation.description) {
            card.appendChild(el('p', 'small mb-2', recommendation.description));
        }
        if (recommendation.impact) {
            card.appendChild(el('p', 'small text-secondary mb-0', 'Why it helps: ' + recommendation.impact));
        }
        col.appendChild(card);
        list.appendChild(col);
    });
}

function renderResumeTab(data) {
    const pdfUrl = '/reviews/' + data.id + '/pdf';
    document.getElementById('downloadPdfBtn').href = pdfUrl;
    document.getElementById('openPdfBtn').href = pdfUrl + '?inline=true';

    // reset the preview so it reloads for this review
    const frame = document.getElementById('pdfPreview');
    frame.removeAttribute('src');
    frame.dataset.src = pdfUrl + '?inline=true';
    if (document.getElementById('tabResume').classList.contains('active')) {
        loadPdfPreview();
    }

    renderIntegrity(data.integrity || {});

    const notes = document.getElementById('notesList');
    notes.innerHTML = '';
    const notesList = (data.resume && data.resume.restructuringNotes) || [];
    notesList.forEach(note => notes.appendChild(el('li', 'mb-1', note)));
}

function loadPdfPreview() {
    const frame = document.getElementById('pdfPreview');
    if (frame.dataset.src && frame.getAttribute('src') !== frame.dataset.src) {
        frame.src = frame.dataset.src;
    }
}

function renderIntegrity(integrity) {
    const panel = document.getElementById('integrityPanel');
    panel.innerHTML = '';

    const removed = integrity.removedItems || [];
    const warnings = integrity.warnings || [];
    const clean = integrity.checked && removed.length === 0 && warnings.length === 0;

    const box = el('div', 'integrity-box small ' + (clean ? 'integrity-ok' : 'integrity-warn'));
    box.appendChild(el('div', 'fw-semibold mb-1', clean ? '✓ Nothing invented' : 'Please double-check'));
    box.appendChild(el('div', '', integrity.note || ''));

    if (removed.length > 0) {
        box.appendChild(el('div', 'fw-semibold mt-2', 'Removed:'));
        const ul = el('ul', 'mb-0 ps-3');
        removed.forEach(item => ul.appendChild(el('li', '', item)));
        box.appendChild(ul);
    }
    warnings.forEach(warning => box.appendChild(el('div', 'mt-2', warning)));
    panel.appendChild(box);
}



// creates an element with a class and (safe) text content
function el(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text != null) element.textContent = text;
    return element;
}

// green / amber / red depending on the score
function scoreClass(score, prefix) {
    if (score >= 80) return prefix + '-good';
    if (score >= 65) return prefix + '-ok';
    return prefix + '-bad';
}

function formatDate(isoString) {
    if (!isoString) return '';
    const date = new Date(isoString);
    return isNaN(date) ? '' : date.toLocaleString();
}

// update status message shown to the user
function setStatus(msg) {
    document.getElementById('status').textContent = msg;
}

// logout
async function logout() {
    try {
        await fetch('/logout', { method: 'POST' });
        window.location.href = '/login.html';
    } catch (err) {
        console.error('Logout failed:', err);
        setStatus('Logout failed. Try again.');
    }
}
