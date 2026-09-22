// Tab switching for the build tool / authentication method configuration
// panes. Inactive panes are parked in <template> elements so that exactly one
// pane exists in the live DOM at any time. Buttons declare:
//   data-panes            id of the pane container they control
//   data-pane-dimension   logical axis ("tool" / "auth"), one active button per axis
//   data-pane-pattern     glob-ish pane id filter ("maven-*", "*-bearer")
{
  const matches = (paneId, pattern) => {
    if (pattern.startsWith('*')) {
      return paneId.endsWith(pattern.slice(1));
    }
    if (pattern.endsWith('*')) {
      return paneId.startsWith(pattern.slice(0, -1));
    }
    return paneId === pattern;
  };

  document.addEventListener('click', (e) => {
    const button = e.target.closest('button[data-pane-pattern]');
    if (!button) {
      return;
    }
    const container = document.getElementById(button.dataset.panes);
    if (!container) {
      return;
    }
    // One active button per dimension
    document
      .querySelectorAll(`button[data-panes="${button.dataset.panes}"][data-pane-dimension="${button.dataset.paneDimension}"]`)
      .forEach((b) => b.classList.toggle('active', b === button));
    // The effective selection is the intersection of every active dimension
    const dimensions = [...document.querySelectorAll(`button[data-panes="${button.dataset.panes}"].active`)]
      .map((b) => b.dataset.panePattern);
    const show = (paneId) => dimensions.every((pattern) => matches(paneId, pattern));
    // Park visible panes that must hide into templates
    container.querySelectorAll(':scope > .tab-pane').forEach((pane) => {
      if (show(pane.dataset.paneId)) {
        pane.classList.add('active');
        return;
      }
      pane.classList.remove('active');
      const template = document.createElement('template');
      template.dataset.paneId = pane.dataset.paneId;
      template.content.append(pane);
      container.append(template);
    });
    // Revive templates that must show
    container.querySelectorAll(':scope > template[data-pane-id]').forEach((template) => {
      if (!show(template.dataset.paneId)) {
        return;
      }
      const pane = template.content.firstElementChild.cloneNode(true);
      pane.classList.add('active');
      template.replaceWith(pane);
    });
  });
}
