// Pollster's client-side code: two small things that only the browser can do.
//
// Both listeners are on the document, so they keep working for elements that the server adds
// later, without being attached to each one.

// Copies the text in a clicked element's data-copy attribute to the clipboard. The clipboard API
// exists only in the browser. The same click also reaches the server, which shows the "Copied"
// toast.
document.addEventListener("click", (event) => {
  const target = event.target instanceof Element ? event.target.closest("[data-copy]") : null;
  if (target) navigator.clipboard?.writeText(target.getAttribute("data-copy") ?? "");
});

// Makes Enter in an option field (data-option) move to the next option, so options can be typed
// one after another. In the last option, it clicks the form's "Add Option" button
// (data-add-option) and moves to the new field once the server has added it.
//
// This lives here because the browser has to decide it before the keypress does anything: without
// preventDefault, Enter submits the form. Moving focus is also something only the browser can do.
document.addEventListener("keydown", (event) => {
  if (event.key !== "Enter" || event.isComposing) return;
  const input = event.target instanceof Element ? event.target.closest("[data-option]") : null;
  const form = input?.closest("form");
  if (!form) return;
  event.preventDefault();

  const options = [...form.querySelectorAll("[data-option]")];
  const next = options[options.indexOf(input) + 1];
  if (next) {
    next.focus();
    return;
  }

  const add = form.querySelector("[data-add-option]");
  if (!add) return;
  // The new field arrives in a patch from the server. Focus it when it appears, and stop waiting
  // after a few seconds in case it never does, such as when the connection is down.
  const observer = new MutationObserver(() => {
    const added = [...form.querySelectorAll("[data-option]")].find((field) => !options.includes(field));
    if (added) {
      observer.disconnect();
      added.focus();
    }
  });
  observer.observe(form, { childList: true, subtree: true });
  setTimeout(() => observer.disconnect(), 5000);
  add.click();
});
