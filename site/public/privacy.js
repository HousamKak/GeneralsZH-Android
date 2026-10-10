// GeneralsX @feature ZH Commander 10/10/2026 The /privacy page in English or Arabic. The language
// is the one chosen on the other pages (localStorage "zh-lang"), and the toggle here changes both.
(function () {
	"use strict";

	var PHONE = "<bdi class=\"phone\" dir=\"ltr\">+961 71 406 981</bdi>";

	var TEXT = {
		en: {
			nav_buy: "Get a key",
			eyebrow: "Privacy",
			title: "What we collect",
			lede: "ZH Commander sends anonymous usage and crash data to a monitoring service run by its developer. It is used only to find bugs and to see which phones, features and versions need work. It is never sold or shared, and never used for advertising.",
			s_app: "<h2>In the app</h2><ul>" +
				"<li>A random install identifier created when the app is first run (not your device id, phone number or advertising id).</li>" +
				"<li>The app version and build, the platform, the Android or iOS version, the phone model and the language setting.</li>" +
				"<li>Usage events such as app opens, sessions (start, end, length) and feature use, with non-personal details.</li>" +
				"<li>Error and crash reports: the error, where in the code it happened, and the recent steps in the app leading to it.</li>" +
				"<li>The country, worked out from the network connection when the data arrives.</li></ul>",
			s_game: "<h2>Game statistics</h2><ul>" +
				"<li>For each match: the mode (skirmish, LAN, online, campaign), the map's file name, your side, the number of players and the AI difficulty, the result, how long it lasted, and the frame rate.</li>" +
				"<li>Your graphics and game settings (renderer, game speed, interface size, languages), once per start.</li>" +
				"<li>Why an online sign-in or a lobby join failed (for example no network, or game files that differ from the host's).</li>" +
				"<li>No player names, GeneralsOnline names or ids, chat, lobby names or account details are ever included.</li></ul>",
			s_never: "<h2>Never sent or stored</h2><ul>" +
				"<li>Your name, email, phone number, contacts, files, photos or precise location.</li>" +
				"<li>Your IP address: it is used only to look up the country and is not stored.</li>" +
				"<li>Hardware identifiers (IMEI, serial numbers, MAC addresses) or advertising identifiers.</li></ul>",
			s_site: "<h2>This website</h2><ul>" +
				"<li>The site counts page visits, APK downloads and presses of the buy options, without cookies and without storing anything in your browser.</li>" +
				"<li>To tell visitors apart for one day, your IP address and browser are combined with a key that changes every day into a one-way code. The IP address itself is not stored, and the code cannot be linked to another day or to the app.</li>" +
				"<li>Only the name of the site that sent you here is kept (for example youtube.com), never the full address.</li></ul>",
			s_keys: "<h2>Activation keys and support</h2><ul>" +
				"<li>Licenses: the key, the device it was activated on and the purchase details are kept to run activation and to help you. They are separate from usage data and never used for statistics.</li>" +
				"<li>Support reports contain the logs you choose to send from the app's Support button, and are kept until your report is handled.</li></ul>",
			s_apart: "<h2>Kept apart</h2><p>License records, support reports and usage data share the app's anonymous install identifier, so a support request about one phone can be looked up. They are never combined into profiles of players or reports about people.</p>",
			s_keep: "<h2>How long</h2><p>Raw usage events are deleted after 90 days. Only daily totals (counts with no identifiers) are kept after that.</p>",
			s_off: "<h2>Turning it off</h2><p>Turn off <strong>Telemetry</strong> on the ZH COMMANDER screen in the app. Nothing is sent while it is off, including match statistics and settings, and data waiting to be sent is deleted.</p>",
			s_contact: "<h2>Questions</h2><p>Message " + PHONE + " on WhatsApp.</p>",
			back: "Back to ZH Commander",
			disclaimer: "ZH Commander is an unofficial fan project, not affiliated with or endorsed by Electronic Arts. Command &amp; Conquer and Generals are trademarks of Electronic Arts.",
			doc_title: "ZH Commander: Privacy",
			toggle: "عربي"
		},
		ar: {
			nav_buy: "احصل على مفتاح",
			eyebrow: "الخصوصية",
			title: "ما الذي نجمعه",
			lede: "يرسل ZH Commander بيانات استخدام وأعطال مجهولة الهوية إلى خدمة مراقبة يديرها مطوّره. تُستخدم فقط لإيجاد الأخطاء ومعرفة الهواتف والميزات والإصدارات التي تحتاج إلى تحسين. لا تُباع ولا تُشارك مع أحد، ولا تُستخدم للإعلانات أبدًا.",
			s_app: "<h2>داخل التطبيق</h2><ul>" +
				"<li>معرّف تثبيت عشوائي يُنشأ عند تشغيل التطبيق لأول مرة (ليس معرّف جهازك ولا رقم هاتفك ولا معرّف الإعلانات).</li>" +
				"<li>إصدار التطبيق ورقم البناء، والمنصة، وإصدار أندرويد أو iOS، وطراز الهاتف، وإعداد اللغة.</li>" +
				"<li>أحداث الاستخدام مثل فتح التطبيق والجلسات (البداية والنهاية والمدة) واستخدام الميزات، بتفاصيل غير شخصية.</li>" +
				"<li>تقارير الأخطاء والأعطال: الخطأ، ومكان حدوثه في الشيفرة، والخطوات الأخيرة في التطبيق قبله.</li>" +
				"<li>الدولة، وتُستنتج من الاتصال بالشبكة لحظة وصول البيانات.</li></ul>",
			s_game: "<h2>إحصاءات اللعب</h2><ul>" +
				"<li>لكل مباراة: النمط (مناوشة، شبكة محلية، عبر الإنترنت، حملة)، واسم ملف الخريطة، والجهة التي تلعب بها، وعدد اللاعبين ومستوى صعوبة الذكاء الاصطناعي، والنتيجة، والمدة، ومعدل الإطارات.</li>" +
				"<li>إعدادات الرسوميات واللعب (محرّك الرسوم، سرعة اللعبة، حجم الواجهة، اللغات)، مرة واحدة عند كل تشغيل.</li>" +
				"<li>سبب فشل تسجيل الدخول عبر الإنترنت أو الانضمام إلى ردهة (مثل عدم وجود شبكة، أو اختلاف ملفات اللعبة عن ملفات المضيف).</li>" +
				"<li>لا تُضمَّن أبدًا أسماء اللاعبين، ولا أسماء أو معرّفات GeneralsOnline، ولا الدردشة، ولا أسماء الردهات، ولا بيانات الحساب.</li></ul>",
			s_never: "<h2>ما لا يُرسل ولا يُخزَّن أبدًا</h2><ul>" +
				"<li>اسمك، وبريدك الإلكتروني، ورقم هاتفك، وجهات الاتصال، والملفات، والصور، وموقعك الدقيق.</li>" +
				"<li>عنوان IP الخاص بك: يُستخدم فقط لمعرفة الدولة ولا يُخزَّن.</li>" +
				"<li>معرّفات العتاد (IMEI والأرقام التسلسلية وعناوين MAC) ومعرّفات الإعلانات.</li></ul>",
			s_site: "<h2>هذا الموقع</h2><ul>" +
				"<li>يحصي الموقع زيارات الصفحات وتنزيلات ملف APK والضغط على خيارات الشراء، دون ملفات تعريف الارتباط ودون تخزين أي شيء في متصفحك.</li>" +
				"<li>للتمييز بين الزوار خلال يوم واحد، يُدمج عنوان IP ونوع المتصفح مع مفتاح يتغيّر كل يوم في رمز باتجاه واحد. لا يُخزَّن عنوان IP نفسه، ولا يمكن ربط الرمز بيوم آخر أو بالتطبيق.</li>" +
				"<li>يُحفظ فقط اسم الموقع الذي أحالك إلينا (مثل youtube.com)، وليس العنوان الكامل أبدًا.</li></ul>",
			s_keys: "<h2>مفاتيح التفعيل والدعم</h2><ul>" +
				"<li>التراخيص: يُحفظ المفتاح والجهاز الذي فُعّل عليه وتفاصيل الشراء لتشغيل التفعيل ومساعدتك. وهي منفصلة عن بيانات الاستخدام ولا تُستخدم للإحصاءات أبدًا.</li>" +
				"<li>تحتوي تقارير الدعم على السجلات التي تختار إرسالها من زر الدعم في التطبيق، وتُحفظ حتى تتم معالجة تقريرك.</li></ul>",
			s_apart: "<h2>منفصلة دائمًا</h2><p>تشترك سجلات التراخيص وتقارير الدعم وبيانات الاستخدام في معرّف التثبيت المجهول للتطبيق، كي يمكن البحث عن طلب دعم يخص هاتفًا واحدًا. ولا تُجمع أبدًا في ملفات تعريف للاعبين أو تقارير عن أشخاص.</p>",
			s_keep: "<h2>مدة الاحتفاظ</h2><p>تُحذف أحداث الاستخدام الخام بعد 90 يومًا. بعد ذلك لا يُحتفظ إلا بالمجاميع اليومية (أعداد بلا معرّفات).</p>",
			s_off: "<h2>الإيقاف</h2><p>أوقف خيار <strong>Telemetry</strong> في شاشة ZH COMMANDER داخل التطبيق. لا يُرسل أي شيء ما دام متوقفًا، بما في ذلك إحصاءات المباريات والإعدادات، وتُحذف البيانات التي تنتظر الإرسال.</p>",
			s_contact: "<h2>أسئلة</h2><p>راسلنا عبر واتساب على " + PHONE + ".</p>",
			back: "العودة إلى ZH Commander",
			disclaimer: "ZH Commander مشروع غير رسمي من المعجبين، غير تابع لشركة Electronic Arts ولا معتمد منها. Command &amp; Conquer وGenerals علامتان تجاريتان لشركة Electronic Arts.",
			doc_title: "ZH Commander: الخصوصية",
			toggle: "English"
		}
	};

	var root = document.documentElement;
	var toggle = document.getElementById("lang-toggle");

	function lang() {
		return root.lang === "ar" ? "ar" : "en";
	}

	function render() {
		var t = TEXT[lang()];
		document.querySelectorAll("[data-i18n]").forEach(function (el) {
			el.innerHTML = t[el.getAttribute("data-i18n")];
		});
		document.title = t.doc_title;
		toggle.textContent = t.toggle;
		toggle.lang = lang() === "ar" ? "en" : "ar";
	}

	function apply(next) {
		root.lang = next;
		root.dir = next === "ar" ? "rtl" : "ltr";
		try { localStorage.setItem("zh-lang", next); } catch (e) { /* storage unavailable */ }
		render();
	}

	var saved = null;
	try { saved = localStorage.getItem("zh-lang"); } catch (e) { /* storage unavailable */ }
	var initial = saved || ((navigator.language || "").toLowerCase().indexOf("ar") === 0 ? "ar" : "en");
	root.lang = initial;
	root.dir = initial === "ar" ? "rtl" : "ltr";
	render();

	toggle.addEventListener("click", function () {
		apply(lang() === "ar" ? "en" : "ar");
	});
})();
