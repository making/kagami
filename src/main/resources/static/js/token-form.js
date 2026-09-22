// Token form client validation: the Generate Token button stays disabled until
// at least one repository and one scope are checked, and the long-lived token
// warning appears for durations over six months.
{
  const SIX_MONTHS_IN_HOURS = 24 * 30 * 6;

  const update = () => {
    const form = document.getElementById('token-form');
    if (!form) {
      return;
    }
    const repositories = form.querySelectorAll('input[name="repositories"]:checked').length;
    const scopes = form.querySelectorAll('input[name="scope"]:checked').length;
    const button = document.getElementById('generate-button');
    if (button) {
      button.disabled = repositories === 0 || scopes === 0;
    }
    const duration = parseInt(form.querySelector('input[name="duration"]')?.value ?? '0', 10) || 0;
    const unit = form.querySelector('select[name="unit"]')?.value ?? 'hours';
    const hours = unit === 'days' ? duration * 24 : unit === 'months' ? duration * 24 * 30 : duration;
    const warning = document.getElementById('long-lived-warning');
    if (warning) {
      warning.classList.toggle('alert-hidden', !(hours > SIX_MONTHS_IN_HOURS));
    }
  };

  document.addEventListener('change', (e) => {
    if (e.target.closest('#token-form')) {
      update();
    }
  });
  document.addEventListener('input', (e) => {
    if (e.target.matches('#token-form input[name="duration"]')) {
      update();
    }
  });
}
