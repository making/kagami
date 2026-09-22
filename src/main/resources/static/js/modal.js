// Modal dialogs swapped in by htmx: [data-modal-close] removes the enclosing
// overlay, Escape removes the topmost one.
{
  document.addEventListener('click', (e) => {
    const button = e.target.closest('[data-modal-close]');
    if (button) {
      button.closest('.modal-overlay')?.remove();
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') {
      return;
    }
    const overlays = document.querySelectorAll('.modal-overlay');
    if (overlays.length > 0) {
      overlays[overlays.length - 1].remove();
    }
  });
}
