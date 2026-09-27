document.addEventListener('DOMContentLoaded', loadHistory);

// GET /reviews -> { "data": [ { id, fileName, targetRole, createdAt, overallScore, letterGrade } ] }
async function loadHistory() {
    try {
        const res = await fetch('/reviews');
        const result = await res.json();
        if (!res.ok) {
            if (res.status === 401) {
                window.location.href = '/login.html';
                return;
            }
            throw new Error(result.error || 'Could not load your reviews.');
        }
        showHistory((result && result.data) || []);
    } catch (err) {
        setStatus('Error: ' + err.message);
    }
}

// builds one table row per saved review
function showHistory(reviews) {
    const body = document.getElementById('historyTable');
    body.innerHTML = '';
    document.getElementById('emptyState').classList.toggle('d-none', reviews.length > 0);

    reviews.forEach(review => {
        const row = document.createElement('tr');

        row.appendChild(cell(new Date(review.createdAt).toLocaleString(), 'ps-4 small text-secondary'));
        row.appendChild(cell(review.fileName, 'fw-semibold'));
        row.appendChild(cell(review.targetRole || '-', 'small'));

        const scoreCell = document.createElement('td');
        const badge = document.createElement('span');
        badge.className = 'grade-pill ' + gradeClass(review.overallScore);
        badge.style.fontSize = '.85rem';
        badge.textContent = (review.letterGrade || '-') + ' · ' + (review.overallScore != null ? review.overallScore : '-');
        scoreCell.appendChild(badge);
        row.appendChild(scoreCell);

        const actions = document.createElement('td');
        actions.className = 'text-end pe-4 text-nowrap';
        actions.appendChild(linkButton('View', '/review?id=' + review.id, 'btn btn-sm btn-primary me-1'));
        actions.appendChild(linkButton('PDF', '/reviews/' + review.id + '/pdf', 'btn btn-sm btn-outline-secondary me-1'));

        const del = document.createElement('button');
        del.type = 'button';
        del.className = 'btn btn-sm btn-outline-danger';
        del.textContent = 'Delete';
        del.onclick = () => deleteReview(review.id, row);
        actions.appendChild(del);
        row.appendChild(actions);

        body.appendChild(row);
    });
}

async function deleteReview(id, row) {
    if (!confirm('Delete this review? This cannot be undone.')) return;
    try {
        const res = await fetch('/reviews/' + id, { method: 'DELETE' });
        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.error || 'Could not delete the review.');
        }
        row.remove();
        const left = document.getElementById('historyTable').children.length;
        document.getElementById('emptyState').classList.toggle('d-none', left > 0);
        setStatus('');
    } catch (err) {
        setStatus('Error: ' + err.message);
    }
}

function cell(text, className) {
    const td = document.createElement('td');
    td.className = className || '';
    td.textContent = text;
    return td;
}

function linkButton(text, href, className) {
    const a = document.createElement('a');
    a.className = className;
    a.href = href;
    a.textContent = text;
    return a;
}

function gradeClass(score) {
    if (score >= 80) return 'grade-good';
    if (score >= 65) return 'grade-ok';
    return 'grade-bad';
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
