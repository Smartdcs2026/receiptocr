from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
html = ROOT / "web-admin/review.html"
text = html.read_text(encoding="utf-8")
old_css = '<link rel="stylesheet" href="review-workspace-v2.css?v=104161">'
new_css = old_css + '<link rel="stylesheet" href="review-fast-round10428.css?v=104280">'
if new_css not in text:
    if old_css not in text:
        raise SystemExit("review workspace stylesheet hook not found")
    text = text.replace(old_css, new_css, 1)
old_js = '<script src="review-workspace-v2.js?v=104161"></script>'
new_js = old_js + '<script src="review-fast-round10428.js?v=104280"></script>'
if new_js not in text:
    if old_js not in text:
        raise SystemExit("review workspace script hook not found")
    text = text.replace(old_js, new_js, 1)
text = text.replace('data-review-round="104161"', 'data-review-round="104280"', 1)
html.write_text(text, encoding="utf-8")
print("Round104.28 review fast mode hooks applied")
