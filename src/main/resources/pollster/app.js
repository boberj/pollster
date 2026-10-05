// Copies the text in a clicked element's data-copy attribute to the clipboard.
//
// This is Pollster's only client-side code. The clipboard API exists only in the browser, so the
// server can't do this part. The same click also reaches the server, which shows the "Copied"
// toast. The listener is on the document, so it keeps working for buttons that the server adds
// later, without being attached to each one.
document.addEventListener("click", (event) => {
  const target = event.target instanceof Element ? event.target.closest("[data-copy]") : null;
  if (target) navigator.clipboard?.writeText(target.getAttribute("data-copy") ?? "");
});
