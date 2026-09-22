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
