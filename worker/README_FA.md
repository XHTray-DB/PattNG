# راه‌اندازی ورکر V2GET (با گوشی)

1. داشبورد Cloudflare → Workers & Pages → Create → Create Worker. محتوای `worker.js` را در ادیتور بچسبان و Deploy کن.
2. D1: Storage & Databases → D1 SQL database → Create (اسم `v2get`). بعد داخل ورکر: Settings → Bindings → Add → D1 database، دیتابیس `v2get` را انتخاب کن و **Variable name = `DB`** بگذار. جدول‌ها را خود ورکر در اولین درخواست می‌سازد و لازم نیست چیزی اجرا کنی (همان دستورها در `schema.sql` هم هست).
   - **انتقال از KV:** باینیدینگ قدیمی `V2GET_KV` را نگه دار. هر کاربر و نشستی که هنوز توی D1 نیست، اولین بار که استفاده شد از KV خوانده و در D1 کپی می‌شود؛ رمز و مصرف و نشست‌ها همان می‌مانند و کسی لازم نیست دوباره وارد شود. وقتی همه کاربرها یک بار استفاده کردند (مثلاً یک هفته بعد، چون نشست‌ها ۷ روزه‌اند) باینیدینگ KV را پاک کن.
   - سقف رایگان D1: ۱۰۰٬۰۰۰ ردیف نوشتن و ۵ میلیون ردیف خواندن در روز، و شمارش مصرف اتمیک است.
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

## آپدیت اجباری برنامه
بعد از هر بیلد جدید (وقتی لینک APK آماده شد) یک بار این را بزن؛ نسخه‌ی قدیمی‌تر از `version` دیگر نه لاگین می‌شود نه وصل می‌شود و صفحه‌ی آپدیت با لینک می‌آید:
```bash
curl -X POST https://WORKER_URL/api/v1/admin/release \
  -H "X-Admin-Secret: ADMIN_SECRET_TO" -H "Content-Type: application/json" \
  -d '{"version":"2.3.14","url":"https://github.com/USER/REPO/releases/download/2.3.14/V2GET_2.3.14_arm64-v8a.apk","notes":"رفع باگ اتصال"}'
```
- `version` باید همان عددی باشد که به بیلد می‌دهی (`release_tag`، مثل `2.3.14`)، چون اپ `versionName` خودش را می‌فرستد.
- `"force":false` فقط ثبت می‌کند و کسی را متوقف نمی‌کند (برای وقتی هنوز فایل آپلود نشده).
- نسخه‌ی ثبت‌شده را بدون ورود می‌شود دید: `curl https://WORKER_URL/api/v1/app/latest`
- نسخه‌های خیلی قدیمی که این چک را ندارند، خطای «سرور ۴۲۶» می‌بینند و باید دستی آپدیت شوند.
