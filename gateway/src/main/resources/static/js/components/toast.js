let _timer = 0;

export function toast(msg) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.style.display = 'block';
  clearTimeout(_timer);
  _timer = setTimeout(() => el.style.display = 'none', 1800);
}
