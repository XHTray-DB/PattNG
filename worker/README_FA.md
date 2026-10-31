# راه‌اندازی ورکر V2GET (با گوشی)

1. داشبورد Cloudflare → Workers & Pages → Create → Create Worker. محتوای `worker.js` را در ادیتور بچسبان و Deploy کن.
2. KV: Storage & Databases → KV → Create namespace (مثلاً `v2get`). بعد داخل ورکر: Settings → Bindings → Add → KV namespace، با **Variable name = `V2GET_KV`**.
3. Settings → Variables and Secrets → Add: نوع **Secret**، اسم `ADMIN_SECRET`، مقدار یه رمز طولانی. (کانفیگ Aether از ورکر داده نمی‌شود؛ برنامه آن را از `conf.json` مخزن Pattconf می‌خواند.)
4. آدرس ورکر (مثل `https://v2get-api.xxx.workers.dev`) را در گیت‌هاب بگذار: Settings → Secrets and variables → Actions → Variables → `V2GET_API_URL`.

## ساخت کاربر (Termux)
```bash
pkg install -y curl
curl -X POST https://WORKER_URL/api/v1/admin/user \
  -H "X-Admin-Secret: ADMIN_SECRET_TO" -H "Content-Type: application/json" \
  -d '{"username":"sara","password":"یک-رمز-حداقل-8-حرفی","quotaGB":50,"days":30}'
```
همین دستور برای تمدید هم کار می‌کند (`days`، `quotaGB`، `"resetUsage":true`، `"disabled":true`).

اگر لاگین خطای «Exceeded CPU» داد، پلن Workers Free برای هش PBKDF2 کم می‌آورد؛ پلن Paid (۵ دلار) مشکل را حل می‌کند.
