# Mawja v1.0 — real-device test checklist / قائمة اختبار على جهاز حقيقي

Use a **signed release build** (see `docs/release-signing.md`) unless a step says debug. Test on at least: one Android 14 or 15 phone, one Android 10–12 phone, and (if possible) an Android 8/9 device. Write the device, Android version and app build next to each failed item.

استخدم **نسخة release موقّعة** (راجع `docs/release-signing.md`) إلا لو الخطوة بتقول debug. اختبر على الأقل على: موبايل أندرويد 14 أو 15، وموبايل أندرويد 10–12، ولو أمكن جهاز أندرويد 8/9. اكتب اسم الجهاز ونسخة أندرويد ورقم البناء جنب أي بند فشل.

Legend / المفتاح: ☐ not tested / لم يُختبر · ✅ pass / نجح · ❌ fail / فشل

---

## 0. Install & first run / التثبيت وأول تشغيل

- ☐ Fresh install of app, Downloader, Admin from the release APKs; each opens without a crash.
  تثبيت جديد للتطبيق والـ Downloader والـ Admin من ملفات release؛ كل واحد يفتح بدون كراش.
- ☐ Android 13+: notification permission prompt appears once; denying it does not crash anything later (prayer alerts, smart notifications, download notification).
  أندرويد 13+: طلب إذن الإشعارات يظهر مرة واحدة؛ الرفض لا يسبب كراش لاحقاً (تنبيه الصلاة، الإشعارات الذكية، إشعار التحميل).
- ☐ Update over the previous release build keeps settings, history, playlists.
  التحديث فوق النسخة السابقة يحافظ على الإعدادات والسجل وقوائم التشغيل.
- ☐ Switch app language Arabic ⇄ English; layout flips to RTL/LTR correctly, no clipped text.
  تبديل اللغة عربي ⇄ إنجليزي؛ الاتجاه يتقلب صح ومافيش نص مقطوع.

## 1. TalkBack / قارئ الشاشة

- ☐ Home, search, video player, music player, library, settings: every button announces a meaningful label (no "unlabelled button").
  الرئيسية، البحث، مشغل الفيديو، مشغل الموسيقى، المكتبة، الإعدادات: كل زر بينطق اسم مفهوم.
- ☐ Seek bar is adjustable with volume-key swipe up/down and announces the position as spoken time.
  شريط التقديم قابل للتعديل بالسحب لأعلى/لأسفل وينطق الوقت بشكل مفهوم.
- ☐ Mini player announces open/played state; video cards announce duration and watched %.
  المشغل المصغّر ينطق حالته؛ كروت الفيديو تنطق المدة ونسبة المشاهدة.
- ☐ EQ bands, bass boost, preamp announce their values (e.g. "+3 dB").
  نطاقات الإيكولايزر والباس والـ preamp تنطق قيمها (مثلاً "+3 dB").
- ☐ Rating bar is one node "N of 5 stars", adjustable, with a clear action.
  شريط التقييم عنصر واحد "N من 5 نجوم"، قابل للتعديل، وفيه إجراء مسح.
- ☐ Widgets and Admin app main screens are readable with TalkBack.
  الويدجتس والشاشات الرئيسية في تطبيق الأدمن مقروءة بـ TalkBack.

## 2. Large text 200% / خط كبير 200%

- ☐ System font size at maximum + display size large, plus Mawja Settings > Accessibility text boost: no overlapping or cut text on Home, player, settings, account, upgrade, Islamic section, Downloader, Admin.
  أكبر حجم خط في النظام + حجم عرض كبير + تكبير الخط من إعدادات موجة: مافيش تداخل أو نص مقطوع في الرئيسية والمشغل والإعدادات والحساب والترقية والقسم الإسلامي والـ Downloader والأدمن.
- ☐ Dialogs (manual coordinates, redeem code, sign-in) still show their buttons.
  النوافذ (إحداثيات يدوية، استرداد كود، تسجيل الدخول) أزرارها لسه ظاهرة.

## 3. High contrast / تباين عالي

- ☐ Settings > Accessibility > High contrast: text and icons readable in light and dark; focus/selection still visible.
  تفعيل التباين العالي: النصوص والأيقونات واضحة في الفاتح والداكن؛ التحديد لسه ظاهر.

## 4. Colour-blind mode / وضع عمى الألوان

- ☐ Each mode (Red-green for protanopia/deuteranopia, Blue-yellow for tritanopia): SponsorBlock segments and state colours (download, error, success) are distinguishable. Best checked with a colour-blindness simulator app or a tester who has it.
  كل وضع (أحمر-أخضر، أزرق-أصفر): مقاطع SponsorBlock وألوان الحالة (تحميل، خطأ، نجاح) مميزة عن بعض.

## 5. Reduce motion / تقليل الحركة

- ☐ Mawja reduce-motion switch: no marquee, still waveform/skeletons, snapping transitions, live backgrounds static.
  مفتاح تقليل الحركة: مافيش نص متحرك، الموجة والهياكل ثابتة، الانتقالات فورية، الخلفيات الحية ثابتة.
- ☐ System "Remove animations" (animator scale 0) has the same effect without touching Mawja settings.
  "إزالة الحركات" من النظام لها نفس التأثير بدون تغيير إعدادات موجة.
- ☐ Qibla needle follows the sensor without easing when reduce motion is on.
  إبرة القبلة تتبع الحساس بدون تنعيم عند تفعيل تقليل الحركة.

## 6. Equalizer & limiter / الإيكولايزر والمحدد

- ☐ Presets Flat, Tarab, Quran & speech clarity, Mahraganat audibly change the sound; switching tracks keeps the preset.
  الإعدادات المسبقة (مسطح، طرب، وضوح القرآن والكلام، مهرجانات) بتغير الصوت فعلاً؛ تغيير الأغنية يحافظ على الإعداد.
- ☐ Bass boost + preamp at maximum on a loud track: no harsh clipping/distortion (the soft-clip limiter after the preamp works), no volume jump when toggling.
  الباس والـ preamp على الأقصى في أغنية عالية: مافيش تشويش (المحدد شغال)، ومافيش قفزة صوت عند التبديل.
- ☐ EQ survives app restart, Bluetooth headphones connect/disconnect, and background playback.
  الإيكولايزر يفضل بعد إعادة التشغيل وتوصيل/فصل سماعات بلوتوث والتشغيل في الخلفية.
- ☐ "System equalizer" entry opens an installed equalizer app, or is hidden when none exists.
  خيار "إيكولايزر النظام" يفتح تطبيق إيكولايزر مثبت، أو مخفي لو مافيش.

## 7. Dual subtitles / ترجمة مزدوجة

- ☐ Main Arabic + second English line (and the reverse) on a video with both tracks; amber second line above/below as set.
  ترجمة عربي أساسية + سطر إنجليزي ثاني (والعكس)؛ السطر الثاني بلون كهرماني فوق/تحت حسب الإعداد.
- ☐ Video with only one track: second line uses YouTube auto-translation.
  فيديو فيه ترجمة واحدة: السطر الثاني يستخدم الترجمة التلقائية.
- ☐ Sync: second line changes within ~0.3 s of the first after seeking, pausing, speed 1.5×/2×.
  التزامن: السطر الثاني يتغير خلال ~0.3 ثانية من الأول بعد التقديم والإيقاف والسرعة 1.5×/2×.
- ☐ Second line never repeats the same text as the main line; nothing leaves the picture in fullscreen, PiP, portrait.
  السطر الثاني ما يكررش نفس النص؛ ومافيش نص بيخرج برا الصورة في ملء الشاشة وPiP والوضع الطولي.

## 8. Offline / airplane mode / بدون إنترنت ووضع الطيران

- ☐ Turn on airplane mode with the app open: offline notice appears; downloads, local files, library, history still work.
  تشغيل وضع الطيران والتطبيق مفتوح: يظهر تنبيه عدم الاتصال؛ التحميلات والملفات المحلية والمكتبة والسجل شغالين.
- ☐ Cold start in airplane mode: no crash, no endless spinners; online screens show a clear message.
  فتح التطبيق من الصفر في وضع الطيران: مافيش كراش ولا تحميل لا نهائي؛ الشاشات الأونلاين تعرض رسالة واضحة.
- ☐ Prayer times and qibla work fully offline.
  مواقيت الصلاة والقبلة شغالين بالكامل بدون إنترنت.
- ☐ Turn network back on: notice disappears, feeds reload without restarting.
  رجوع الإنترنت: التنبيه يختفي والمحتوى يتحمل بدون إعادة تشغيل.

## 9. Islamic section / القسم الإسلامي

- ☐ Off by default; turning it on adds Settings > Prayer times & qibla and the Library row.
  مقفول افتراضياً؛ تفعيله يضيف "مواقيت الصلاة والقبلة" في الإعدادات وصف في المكتبة.
- ☐ **Prayer times vs official Egypt times:** for Cairo, Alexandria and your city, compare all six times for 3 different days with the Egyptian General Authority of Survey / Dar al-Ifta published timetable. Difference must be ≤ 1–2 minutes (write the differences).
  **مقارنة المواقيت بالمواقيت الرسمية في مصر:** للقاهرة والإسكندرية ومدينتك، قارن الست مواقيت لـ 3 أيام مختلفة بجدول الهيئة المصرية العامة للمساحة / دار الإفتاء. الفرق لازم يكون ≤ 1–2 دقيقة (اكتب الفروق).
- ☐ Location from device (coarse permission), from city list, and typed coordinates all give the same times for the same place.
  الموقع من الجهاز، ومن قائمة المدن، والإحداثيات اليدوية بيدوا نفس المواقيت لنفس المكان.
- ☐ Denying location permission falls back to the city list without a crash.
  رفض إذن الموقع يرجع لقائمة المدن بدون كراش.
- ☐ **Qibla:** with a known reference (e.g. a mosque's qibla line), the needle points within ~5°; calibration warning appears when accuracy is low; device without compass shows the static bearing text.
  **القبلة:** مقارنة بمرجع معروف (اتجاه قبلة مسجد)، الإبرة دقيقة في حدود ~5°؛ تحذير المعايرة يظهر لما الدقة قليلة؛ جهاز بدون بوصلة يعرض الاتجاه نصياً.
- ☐ Rotate the phone to landscape: qibla direction stays correct.
  تدوير الموبايل بالعرض: اتجاه القبلة يفضل صحيح.
- ☐ **Adhan alarms:** "Notify" posts a notification at prayer time (allow a few minutes in Doze); "Pause" pauses music/video then notifies. Test with screen off for 30+ minutes.
  **تنبيه الأذان:** "تنبيه" يبعت إشعار وقت الصلاة (ممكن يتأخر دقائق في وضع توفير الطاقة)؛ "إيقاف" يوقف الموسيقى/الفيديو ثم ينبه. اختبر والشاشة مقفولة 30 دقيقة أو أكتر.
- ☐ Reboot the phone, change time zone, change time manually: next alarm still fires at the right time.
  إعادة تشغيل الموبايل، تغيير المنطقة الزمنية، تغيير الوقت يدوياً: التنبيه الجاي لسه في وقته.
- ☐ Hijri date matches the official date (use ±day adjustment if not).
  التاريخ الهجري مطابق للرسمي (استخدم التعديل ±يوم لو لأ).

## 10. Ratings / التقييمات

- ☐ Rate a video and a song 1–5 from the menu and the note pane; rating persists after restart.
  قيّم فيديو وأغنية من 1 لـ 5 من القائمة ولوحة الملاحظات؛ التقييم يفضل بعد إعادة التشغيل.
- ☐ History "Rated by you" list is ordered by stars; clearing a rating removes it.
  قائمة "قيّمتها أنت" في السجل مرتبة بالنجوم؛ مسح التقييم يشيله.
- ☐ Backup export → import on another device restores ratings.
  تصدير النسخة الاحتياطية واستيرادها على جهاز تاني يرجّع التقييمات.

## 11. Widgets / الويدجتس

- ☐ Add every widget (Now playing, Turntable, Quick actions, Recognize, Recently played, On repeat, Playlist, Week, Downloads, and Next prayer if present) on Pixel launcher and Samsung One UI.
  أضف كل الويدجتس على لانشر Pixel وSamsung One UI.
- ☐ Controls work with the app killed (swipe away from recents), after reboot, and in dark mode; Arabic text not clipped.
  الأزرار شغالة والتطبيق مقفول من الـ recents، وبعد إعادة التشغيل، وفي الوضع الداكن؛ النص العربي مش مقطوع.
- ☐ Quick action "Continue watching" resumes the newest part-watched video, else opens History.
  إجراء "تابع المشاهدة" يكمل آخر فيديو اتشاف جزء منه، وإلا يفتح السجل.

## 12. Live backgrounds & battery saver / الخلفيات الحية وموفر البطارية

- ☐ Live player background follows cover colours, is smooth (no stutter while scrolling lyrics/queue).
  خلفية المشغل الحية بتاخد ألوان الغلاف وسلسة (مافيش تقطيع مع تمرير الكلمات/القائمة).
- ☐ Battery saver Auto at threshold (e.g. 20%) or system power-save: live backgrounds stop, motion reduced, video quality capped, prefetch off; returns when charging.
  موفر البطارية التلقائي عند الحد (مثلاً 20%) أو وضع توفير الطاقة في النظام: الخلفيات الحية تقف، الحركة تقل، جودة الفيديو تتحدد، التحميل المسبق يقف؛ ويرجع كل ده مع الشحن.
- ☐ Free account sees the upsell for Plus visuals; Plus/Pro code unlocks them.
  الحساب المجاني يشوف عرض الترقية للمؤثرات المميزة؛ كود Plus/Pro يفتحها.

## 13. Themes, icons, sounds / الثيمات والأيقونات والأصوات

- ☐ Every palette (Nile + premium: Sahara, Andalus, Midnight Oud, Delta, Red Sea Reef) in light, dark, black; text contrast OK.
  كل لوحات الألوان (النيل + المميزة) في الفاتح والداكن والأسود؛ تباين النص سليم.
- ☐ Change app icon to each variant incl. Plus icons; launcher updates; after an app update the icon is still there (not missing from launcher).
  تغيير أيقونة التطبيق لكل نوع بما فيها أيقونات Plus؛ اللانشر يتحدث؛ بعد تحديث التطبيق الأيقونة لسه موجودة.
- ☐ Optional UI sounds: off by default, respect silent/vibrate mode, quiet volume.
  أصوات الواجهة الاختيارية: مقفولة افتراضياً، تحترم وضع الصامت/الهزاز، وصوتها هادي.

## 14. Downloader install & permission / تثبيت الـ Downloader والصلاحية

- ☐ Without the Downloader: tapping Download shows the install prompt; "Install" opens the releases page.
  بدون الـ Downloader: زر التحميل يعرض رسالة التثبيت؛ "تثبيت" يفتح صفحة الإصدارات.
- ☐ Install order A: Downloader first, then app → downloads work.
  ترتيب أ: الـ Downloader الأول ثم التطبيق ← التحميل شغال.
- ☐ Install order B: app first, then Downloader → downloads work **without** reinstalling the app. (If not: note Android version; workaround is updating the app.)
  ترتيب ب: التطبيق الأول ثم الـ Downloader ← التحميل شغال **بدون** إعادة تثبيت التطبيق. (لو لأ: اكتب نسخة أندرويد؛ الحل المؤقت تحديث التطبيق.)
- ☐ Debug app + release Downloader (or reverse) must NOT connect (different key) and must not crash.
  تطبيق debug + Downloader release (أو العكس) لازم ما يتصلوش (مفتاح مختلف) ومن غير كراش.
- ☐ Large download (1 GB+) with screen off: progress notification, completes, file plays. Pause/cancel works. Android 15: a download running for hours does not crash the Downloader.
  تحميل كبير (1 جيجا+) والشاشة مقفولة: إشعار التقدم يظهر، يكمل، والملف يشتغل. الإيقاف/الإلغاء شغال. أندرويد 15: تحميل شغال لساعات ما يعملش كراش.
- ☐ Remote kill switch / maintenance from Admin: Downloader refuses new work with the right message; running item finishes.
  إيقاف عن بعد / صيانة من الأدمن: الـ Downloader يرفض شغل جديد برسالة صحيحة؛ التحميل الشغال يكمل.

## 15. Account sign-in / تسجيل الدخول

- ☐ Email sign-up, verification email arrives, sign-in, sign-out, password reset.
  إنشاء حساب بالإيميل، وصول إيميل التأكيد، تسجيل الدخول والخروج، استعادة كلمة المرور.
- ☐ Username sign-in resolves to the right account.
  تسجيل الدخول باسم المستخدم يوصل للحساب الصحيح.
- ☐ **Google sign-in on the release build** (needs the release SHA-1 in Firebase). Cancel the Google sheet → no error toast spam.
  **تسجيل الدخول بجوجل على نسخة release** (محتاج SHA-1 بتاع مفتاح release في Firebase). إلغاء نافذة جوجل ← من غير رسائل خطأ متكررة.
- ☐ Mawja ID appears within seconds of sign-up; tier and badges correct.
  رقم Mawja ID يظهر خلال ثواني من التسجيل؛ الباقة والشارات صحيحة.
- ☐ Offline sign-in attempt shows a network error, no crash. foss build has no account entry.
  محاولة دخول بدون إنترنت تعرض خطأ شبكة بدون كراش. نسخة foss مافيهاش حسابات.

## 16. Codes redeem / استرداد الأكواد

- ☐ Valid code upgrades the tier and shows the end date; reusing it fails with a clear message.
  كود صحيح يرقّي الباقة ويعرض تاريخ الانتهاء؛ استخدامه تاني يفشل برسالة واضحة.
- ☐ Invalid, expired, wrong format, and too-many-attempts cases each show the right message.
  كود غلط، منتهي، صيغة غلط، ومحاولات كتير: كل حالة برسالتها.
- ☐ Redeeming while already on a longer membership extends, not shortens it.
  الاسترداد وانت عندك اشتراك أطول يمدّه ومايقصّروش.

## 17. Admin app / تطبيق الأدمن

- ☐ Sign-in (email + Google on release key), biometric lock on reopen.
  تسجيل الدخول (إيميل + جوجل بمفتاح release)، وقفل البصمة عند إعادة الفتح.
- ☐ Generate codes (QR shows, share works), search users, change tier/badge, remote config & announcements publish and appear in the app.
  إنشاء أكواد (QR يظهر والمشاركة شغالة)، البحث عن المستخدمين، تغيير الباقة/الشارة، الإعدادات عن بعد والإعلانات تتنشر وتظهر في التطبيق.
- ☐ Non-admin account is refused.
  حساب مش أدمن يترفض.

## 18. Battery & memory / البطارية والذاكرة

Record numbers, not just pass/fail. / سجّل أرقام، مش بس نجح/فشل.

- ☐ 1 hour background music, screen off, EQ on: battery drop ___ % (target ≤ 5–6 %). Settings > Battery > Mawja shows no excessive "background" usage.
  ساعة موسيقى في الخلفية والشاشة مقفولة والإيكولايزر شغال: نزول البطارية ___ % (المستهدف ≤ 5–6%).
- ☐ 30 min 1080p video with dual subtitles: battery ___ %, device temperature acceptable.
  30 دقيقة فيديو 1080p بترجمة مزدوجة: البطارية ___ %، وحرارة الجهاز مقبولة.
- ☐ Overnight idle (8 h) with Islamic adhan on and smart notifications on: battery ___ % (target ≤ 2 %).
  ليلة كاملة بدون استخدام (8 ساعات) والأذان والإشعارات الذكية شغالين: البطارية ___ % (المستهدف ≤ 2%).
- ☐ Memory: `adb shell dumpsys meminfo app.mawja.android` after 20 min browsing + playing: TOTAL PSS ___ MB; no steady growth after repeating open/close player 20×.
  الذاكرة: بعد 20 دقيقة تصفح وتشغيل: TOTAL PSS ___ ميجا؛ ومافيش زيادة مستمرة بعد فتح وقفل المشغل 20 مرة.
- ☐ Cold start time: `adb shell am start -W app.mawja.android/.MainActivity` → TotalTime ___ ms (3 runs).
  وقت الفتح من الصفر: TotalTime ___ مللي ثانية (3 مرات).
- ☐ Low-end device (≤ 3 GB RAM): scrolling Home and live backgrounds stay smooth; no ANR dialogs.
  جهاز ضعيف (≤ 3 جيجا رام): تمرير الرئيسية والخلفيات الحية سلسة؛ مافيش رسائل "التطبيق لا يستجيب".

## 19. Crash reporting during the test / تسجيل الكراشات أثناء الاختبار

- ☐ After each session: `adb logcat -b crash -d > crash.txt` and send it with the failed items. Release builds keep class names (no obfuscation), so the stack trace is readable as-is.
  بعد كل جلسة: `adb logcat -b crash -d > crash.txt` وابعته مع البنود اللي فشلت. نسخ release محتفظة بأسماء الكلاسات، فالـ stack trace مقروء مباشرة.
