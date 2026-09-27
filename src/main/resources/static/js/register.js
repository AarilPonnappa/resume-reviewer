// triggered by the "Create account" button (form submit)
async function register(event) {
    event.preventDefault();

    const name = document.getElementById('name').value.trim();
    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;
    const confirmPassword = document.getElementById('confirmPassword').value;

    // quick checks here so the user doesn't wait for the server to tell them
    if (!name || !email || !password) {
        setStatus('Fill in every field.');
        return;
    }
    if (password.length < 8) {
        setStatus('Password must be at least 8 characters.');
        return;
    }
    // same limit the server uses (BCrypt hashes at most 72 bytes)
    if (new TextEncoder().encode(password).length > 72) {
        setStatus('Password is too long (72 bytes max).');
        return;
    }
    if (password !== confirmPassword) {
        setStatus('Passwords do not match.');
        return;
    }

    const button = document.getElementById('registerBtn');
    button.disabled = true;
    setStatus('');

    try {
        const res = await fetch('/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name: name, email: email, password: password })
        });
        const data = await res.json();

        if (!res.ok) {
            throw new Error(data.error || 'Registration failed.');
        }

        if (data.verificationRequired) {
            // email verification is on: tell them to check their inbox
            document.getElementById('registerForm').classList.add('d-none');
            showNotice(data.message);
        } else {
            // no verification needed: go straight to the login page
            window.location.href = '/login.html?registered=true';
        }
    } catch (err) {
        setStatus(err.message);
        button.disabled = false;
    }
}

function showNotice(msg) {
    const notice = document.getElementById('notice');
    notice.textContent = msg;
    notice.classList.remove('d-none');
}

// update status message shown to the user
function setStatus(msg) {
    document.getElementById('status').textContent = msg;
}
