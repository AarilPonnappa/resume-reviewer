// Show a message when we arrive here from the verification email or right after registering
document.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('verified') === 'true') {
        showNotice('Email verified! You can log in now.');
    } else if (params.get('registered') === 'true') {
        showNotice('Account created. Log in to get started.');
    }
});

// triggered by the "Log in" button (form submit)
async function login(event) {
    event.preventDefault();

    const email = document.getElementById('email').value.trim();
    const password = document.getElementById('password').value;

    if (!email || !password) {
        setStatus('Enter your email and password.');
        return;
    }

    const button = document.getElementById('loginBtn');
    button.disabled = true;
    setStatus('');

    try {
        // POST /login with a JSON body. The server puts our user id into the session on success
        const res = await fetch('/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: email, password: password })
        });
        const data = await res.json();

        if (!res.ok) {
            throw new Error(data.error || 'Login failed.');
        }

        window.location.href = data.redirect || '/review';
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
