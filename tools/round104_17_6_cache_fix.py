from pathlib import Path

p = Path('web-admin/index.html')
s = p.read_text(encoding='utf-8')
old = 'receipt-date-rules.js?v=69'
new = 'receipt-date-rules.js?v=104176'
if new not in s:
    if old not in s:
        raise SystemExit('receipt-date-rules cache target not found')
    s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('Round104.17.6 shared rule cache updated')
