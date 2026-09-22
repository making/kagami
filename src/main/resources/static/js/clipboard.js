// Copy-to-clipboard buttons: elements with a data-copy attribute copy that
// value and show "Copied!" for two seconds. Event delegation keeps working
// across htmx partial swaps.
{
  document.addEventListener('click', async (e) => {
    const button = e.target.closest('[data-copy]');
    if (!button) {
      return;
    }
    try {
      await navigator.clipboard.writeText(button.dataset.copy);
    }
    catch (err) {
      console.error('Failed to copy to clipboard:', err);
      return;
    }
    const label = button.querySelector('[data-copy-label]') ?? button;
    // innerHTML (not textContent) so that icon-only buttons keep their SVG icon
    if (!button.dataset.originalLabel) {
      button.dataset.originalLabel = label.innerHTML;
    }
    label.innerHTML = 'Copied!';
    setTimeout(() => {
      label.innerHTML = button.dataset.originalLabel;
    }, 2000);
  });
}
