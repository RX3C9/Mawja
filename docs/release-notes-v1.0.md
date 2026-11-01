# Mawja v1.0 — release notes (DRAFT, not published)

> Draft for Omar's review. Not posted anywhere. Items marked † live on feature branches (ratings, widgets, visual pack) and must be merged and device-tested before this text is used. Version numbers, download links and screenshots are filled in at release time.

---

## English

**Mawja 1.0 — موجة** is the first stable release of Mawja, an Arabic-first video and music app built on Flow.

### Accessibility for everyone
- Full TalkBack pass: every control is labelled; the seek bar is adjustable and speaks time naturally; cards announce duration and how much you watched.
- Larger text on top of the system font size, and a high-contrast colour scheme.
- Colour-blind modes (red-green and blue-yellow) for SponsorBlock segments and status colours.
- Reduce motion: follows the system "remove animations" setting or Mawja's own switch, and turns on with the battery saver.

### Sound: equalizer presets
- New Arabic-friendly presets: **Tarab**, **Quran & speech clarity** and **Mahraganat**, next to Flat and the existing presets.
- Bass boost and preamp sliders with a gentle limiter, all readable with TalkBack.

### Dual subtitles
- Show a second subtitle language above or below the main one, in a distinct style. Uses an existing track or YouTube's automatic translation.
- Settings > Player > Second subtitle language, plus a quick picker inside the player.

### Offline mode
- Mawja notices airplane mode or a lost connection, tells you once, and keeps everything that works offline: downloads, local files, library, history.

### Islamic section (optional, off by default)
- On-device prayer times, no internet needed: Egyptian General Authority of Survey by default in Egypt, plus Umm al-Qura, MWL, ISNA and Karachi; Asr standard/Hanafi; per-prayer minute adjustments.
- Location from the phone's last known position, a city list, or typed coordinates. Nothing leaves the device.
- Qibla compass, with a text bearing when the phone has no compass.
- Optional notification, or pause playback, at adhan time. Hijri date with ±2-day adjustment.

### Personal ratings †
- Rate any video or song from 1 to 5 stars. "Rated by you" in History, sorted by stars. Ratings are included in backups.

### Home-screen widgets †
- New and refreshed widgets, including a **Continue watching** quick action and new default shortcuts (Downloads, Library, Continue watching).

### Visual pack †
- Live player backgrounds that take their colours from the cover art (static when reduce motion or the battery saver is on).
- New premium themes inspired by the region: Sahara, Andalus, Midnight Oud, Delta and Red Sea Reef, contrast-checked.
- New themed launcher icons and an optional, quiet UI sound pack.
- Premium visuals are part of Mawja Plus/Pro; all of Flow's original features stay free.

### Under the hood
- Release builds are shrunk with R8, keep readable stack traces, and are signed with one Mawja key shared by the app, Mawja Downloader and Mawja Admin.
- Stability fixes: Android 15 limits on long downloads in Mawja Downloader, fewer recompositions while dual subtitles play, safer notification and link handling.

### Credits and licence
Mawja is a fork of **[Flow](https://github.com/Flow-Tube/Flow)** by **A-EDev** and the Flow contributors, and is licensed under the **GNU GPL-3.0**, the same licence as Flow. The full source code is available at https://github.com/RX3C9/Mawja. Every original copyright notice is kept. Please consider supporting the original Flow project too.

Mawja is not affiliated with YouTube or Google.

---

## العربية

**موجة 1.0** هو أول إصدار مستقر من موجة، تطبيق فيديو وموسيقى بالعربي أولاً، مبني على Flow.

### إتاحة للجميع
- دعم كامل لقارئ الشاشة TalkBack: كل زر له اسم واضح، وشريط التقديم قابل للتعديل وبينطق الوقت بشكل طبيعي، والكروت بتقول المدة ونسبة المشاهدة.
- تكبير الخط فوق حجم خط النظام، ووضع تباين عالي.
- أوضاع لعمى الألوان (أحمر-أخضر وأزرق-أصفر) لمقاطع SponsorBlock وألوان الحالة.
- تقليل الحركة: بيتبع إعداد "إزالة الحركات" في النظام أو مفتاح موجة، وبيشتغل تلقائياً مع موفر البطارية.

### الصوت: إعدادات الإيكولايزر
- إعدادات جديدة تناسب الذوق العربي: **طرب**، **وضوح القرآن والكلام**، و**مهرجانات**، جنب الإعداد المسطّح والإعدادات الموجودة.
- تحكم في الباس والـ preamp مع محدد صوت هادي، وكلها مقروءة بـ TalkBack.

### ترجمة مزدوجة
- اعرض لغة ترجمة تانية فوق أو تحت الترجمة الأساسية بشكل مميز. بتستخدم ترجمة موجودة أو الترجمة التلقائية من يوتيوب.
- من الإعدادات > المشغل > لغة الترجمة الثانية، أو من داخل المشغل مباشرة.

### وضع بدون إنترنت
- موجة بتعرف إن وضع الطيران شغال أو النت فصل، وبتنبهك مرة واحدة، وكل اللي بيشتغل بدون نت يفضل شغال: التحميلات والملفات المحلية والمكتبة والسجل.

### القسم الإسلامي (اختياري ومقفول افتراضياً)
- مواقيت الصلاة محسوبة على الجهاز بدون إنترنت: طريقة الهيئة المصرية العامة للمساحة افتراضياً في مصر، وكمان أم القرى ورابطة العالم الإسلامي وISNA وكراتشي؛ العصر شافعي/حنفي؛ وتعديل بالدقيقة لكل صلاة.
- الموقع من آخر مكان معروف للموبايل، أو من قائمة المدن، أو بإحداثيات يدوية. مافيش أي بيانات بتخرج من الجهاز.
- بوصلة القبلة، ولو الموبايل مافيهوش بوصلة بيظهر الاتجاه كنص.
- تنبيه أو إيقاف التشغيل وقت الأذان (اختياري). التاريخ الهجري مع تعديل ±يومين.

### تقييماتك الشخصية †
- قيّم أي فيديو أو أغنية من 1 لـ 5 نجوم. قائمة "قيّمتها أنت" في السجل مرتبة بالنجوم، والتقييمات بتتحفظ في النسخة الاحتياطية.

### ويدجتس الشاشة الرئيسية †
- ويدجتس جديدة ومحسّنة، منها إجراء سريع **تابع المشاهدة**، واختصارات افتراضية جديدة (التحميلات، المكتبة، تابع المشاهدة).

### الباقة البصرية †
- خلفيات حية للمشغل بتاخد ألوانها من غلاف الأغنية (بتثبت مع تقليل الحركة أو موفر البطارية).
- ثيمات مميزة مستوحاة من المنطقة: الصحراء، الأندلس، عود منتصف الليل، الدلتا، وشعاب البحر الأحمر، ومراجَعة التباين.
- أيقونات جديدة للتطبيق، وأصوات واجهة هادية واختيارية.
- المؤثرات المميزة جزء من موجة Plus/Pro؛ وكل مميزات Flow الأصلية تفضل مجانية.

### تحسينات داخلية
- نسخ الإصدار مضغوطة بـ R8، وبتحتفظ بتقارير أخطاء مقروءة، وموقّعة بمفتاح موجة واحد للتطبيق وموجة Downloader وموجة Admin.
- إصلاحات استقرار: حدود أندرويد 15 للتحميلات الطويلة في موجة Downloader، وتقليل إعادة الرسم أثناء الترجمة المزدوجة، وتعامل أأمن مع الإشعارات والروابط.

### الإسناد والترخيص
موجة نسخة مشتقة (fork) من **[Flow](https://github.com/Flow-Tube/Flow)** من تطوير **A-EDev** والمساهمين في Flow، ومرخّصة بترخيص **GNU GPL-3.0** نفس ترخيص Flow. الكود المصدري الكامل متاح على https://github.com/RX3C9/Mawja، وكل حقوق النشر الأصلية محفوظة في الملفات. ندعوك لدعم مشروع Flow الأصلي كمان.

موجة غير تابعة ليوتيوب أو جوجل.
