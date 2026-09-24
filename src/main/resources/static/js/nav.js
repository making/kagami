// Row navigation: elements with a data-navigate attribute navigate on click,
// unless the click started inside an interactive element (link, button, form
// control) that handles the click itself.
{
  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-navigate]');
    if (!el) {
      return;
    }
    if (e.target.closest('a, button, input, select, label')) {
      return;
    }
    window.location.href = el.dataset.navigate;
  });
}

// Header nav highlight: mark the link matching the current path with
// nav-link-accent, so the highlighted entry always reflects the page the
// user is on (no highlight when the path matches none of the links).
{
  const markActive = () => {
    document.querySelectorAll('.header-nav a.nav-link').forEach((link) => {
      const href = link.getAttribute('href');
      const matches = href === '/'
        ? window.location.pathname === '/'
        : window.location.pathname === href
            || window.location.pathname.startsWith(href.endsWith('/') ? href : href + '/');
      link.classList.toggle('nav-link-accent', matches);
    });
  };
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', markActive);
  }
  else {
    markActive();
  }
}
