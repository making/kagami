// Repository list filter (type and access). The repository table is rendered on the
// server and replaced by an htmx swap once the statistics arrive, so the selection is
// kept in memory and reapplied after every swap instead of living in the markup.
{
  const state = { format: '', visibility: '' };

  const apply = () => {
    const panel = document.getElementById('repository-panel');
    if (!panel) {
      return;
    }
    const rows = panel.querySelectorAll('tbody tr[data-format]');
    let visible = 0;
    rows.forEach((row) => {
      const matches = (!state.format || row.dataset.format === state.format)
        && (!state.visibility || row.dataset.visibility === state.visibility);
      row.hidden = !matches;
      if (matches) {
        visible += 1;
      }
    });
    const empty = document.getElementById('repository-filter-empty');
    if (empty) {
      empty.hidden = visible !== 0;
    }
    const count = document.getElementById('repository-count');
    if (count) {
      const total = panel.dataset.repositoryTotal || String(rows.length);
      count.textContent = (state.format || state.visibility)
        ? `${visible} of ${total} entries`
        : `${total} entries / select to browse`;
    }
    const format = document.getElementById('repository-filter-format');
    if (format) {
      format.value = state.format;
    }
    const visibility = document.getElementById('repository-filter-visibility');
    if (visibility) {
      visibility.value = state.visibility;
    }
  };

  document.addEventListener('change', (e) => {
    if (e.target.id === 'repository-filter-format') {
      state.format = e.target.value;
      apply();
    }
    else if (e.target.id === 'repository-filter-visibility') {
      state.visibility = e.target.value;
      apply();
    }
  });
  ['htmx:afterSwap', 'htmx:afterSettle', 'htmx:load'].forEach((name) => {
    document.addEventListener(name, apply);
  });
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', apply);
  }
  else {
    apply();
  }
}
