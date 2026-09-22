// Modal dialogs swapped in by htmx: [data-modal-close] removes the enclosing
// overlay, clicking outside the dialog box or Escape removes the topmost one.
{
  document.addEventListener('click', (e) => {
    const button = e.target.closest('[data-modal-close]');
    if (button) {
      button.closest('.modal-overlay')?.remove();
      return;
    }
    if (e.target.classList?.contains('modal-overlay')) {
      e.target.remove();
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
