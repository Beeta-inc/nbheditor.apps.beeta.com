// Hamburger Menu
const hamburger = document.getElementById('hamburger');
const sideNav = document.getElementById('sideNav');
const closeBtn = document.getElementById('closeBtn');
const overlay = document.getElementById('menuOverlay');

hamburger.addEventListener('click', () => {
    sideNav.style.width = '250px';
    overlay.style.display = 'block';
});

closeBtn.addEventListener('click', () => {
    sideNav.style.width = '0';
    overlay.style.display = 'none';
});

overlay.addEventListener('click', () => {
    sideNav.style.width = '0';
    overlay.style.display = 'none';
});

// Tabs
document.querySelectorAll('.tab-btn').forEach(button => {
    button.addEventListener('click', () => {
        document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-panel').forEach(panel => panel.classList.remove('active'));

        button.classList.add('active');
        document.getElementById(button.getAttribute('data-tab')).classList.add('active');

        // Close side menu on tab click
        sideNav.style.width = '0';
        overlay.style.display = 'none';
    });
});

// Theme Toggle
const themeToggle = document.getElementById('themeToggle');
themeToggle.addEventListener('click', () => {
    document.body.classList.toggle('light-mode');
    themeToggle.textContent = document.body.classList.contains('light-mode') ? '🌙' : '☀️';

    // Save user preference
    localStorage.setItem('theme', document.body.classList.contains('light-mode') ? 'light' : 'dark');
});

// Load theme on page load
if (localStorage.getItem('theme') === 'light') {
    document.body.classList.add('light-mode');
    themeToggle.textContent = '🌙';
}

// Scroll Animation for Highlights
const highlights = document.querySelectorAll('.highlight');

function checkHighlights() {
    const triggerBottom = window.innerHeight / 1.1; // more aggressive for mobile
    highlights.forEach(highlight => {
        const rect = highlight.getBoundingClientRect();
        if (rect.top < triggerBottom) {
            highlight.classList.add('visible');
        }
    });
}

// Run on scroll
window.addEventListener('scroll', checkHighlights);

// Run on page load (important for mobile)
window.addEventListener('load', checkHighlights);
// JS from previous step (same as before)
