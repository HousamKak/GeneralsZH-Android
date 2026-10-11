// GeneralsX @feature ZH Commander 11/10/2026 The /pc page: how a PC player sets up GeneralsOnline so
// phones can join their games, in English or Arabic. The language is the one chosen on the other
// pages (localStorage "zh-lang"), and the toggle here changes both.
(function () {
	"use strict";

	var PHONE = "<bdi class=\"phone\" dir=\"ltr\">+961 71 406 981</bdi>";
	var GO = "<a href=\"https://www.playgenerals.online/\" rel=\"noopener\">playgenerals.online</a>";
	var EA = "<a href=\"https://www.ea.com/ea-app\" rel=\"noopener\">EA app</a>";
	var STEAM = "<a href=\"https://store.steampowered.com/search/?term=command+and+conquer+generals\" rel=\"noopener\">Steam</a>";
	var LAUNCHER = "<bdi dir=\"ltr\"><code>EAC_LaunchGeneralsOnline.exe</code></bdi>";
	var PATCH_DIR = "<bdi dir=\"ltr\"><code>GeneralsOnlineGameData</code></bdi>";

	// The one layout that matters: the community patch belongs in Documents only. A copy inside the
	// game folder is loaded too early, and the PC's games then report game files the phones refuse.
	var TREE =
		"<pre class=\"tree\" dir=\"ltr\">" +
		"Documents\\Command and Conquer Generals Zero Hour Data\\\n" +
		"  GeneralsOnlineGameData\\500_900_CommunityPatch_CoreINI.big   <span class=\"tree-ok\">KEEP</span>\n\n" +
		"&lt;game folder&gt;\\Command and Conquer Generals Zero Hour\\\n" +
		"  GeneralsOnlineGameData\\                                      <span class=\"tree-bad\">DELETE</span>\n" +
		"</pre>";

	var TEXT = {
		en: {
			nav_buy: "Get a key",
			eyebrow: "Cross-play",
			title: "Play with PC friends",
			lede: "ZH Commander plays online through GeneralsOnline, the same service PC players use. A PC friend needs the game and GeneralsOnline set up as below. It takes about 15 minutes, and then phones and PCs can join each other's games.",
			s_own: "<h2>1. Get the game on PC</h2><p>Install <em>Command &amp; Conquer: Generals and Zero Hour</em> from the " + EA + " or " + STEAM + ". It is often a few dollars on sale. Old CD copies updated to version 1.04 work too.</p>",
			s_install: "<h2>2. Install GeneralsOnline</h2><p>Download the installer from " + GO + " and point it at the Zero Hour folder. Use the installer rather than unzipping files into the game folder by hand. Then create a GeneralsOnline account.</p><p>Each player needs their own account. One account can be signed in on one device at a time, so the PC and the phone cannot share it.</p>",
			s_folder: "<h2>3. Check one folder</h2><p>GeneralsOnline keeps its community patch in Documents. If a " + PATCH_DIR + " folder is also inside the game folder (this happens when files are unzipped by hand), the PC's games report game files that phones refuse to join. Delete that one; the copy in Documents stays.</p>" + TREE,
			s_play: "<h2>4. Play</h2><ul>" +
				"<li>On the PC, start the game with " + LAUNCHER + ".</li>" +
				"<li>In ZH Commander, keep <strong>Play with PC players</strong> on and the game at <strong>60 Hz</strong>. Both are the default.</li>" +
				"<li>Either side can host. The other finds the game in the GeneralsOnline lobby list and joins.</li></ul>",
			s_trouble: "<h2>If the phone cannot join</h2><ul>" +
				"<li><strong>It says the host's game files are different:</strong> on the PC, do step 3, then restart the game and host again.</li>" +
				"<li><strong>Signing in stops on the other device:</strong> the same GeneralsOnline account is in use on both. Use separate accounts.</li>" +
				"<li><strong>Could not connect to all players:</strong> try again on Wi-Fi, or switch between Wi-Fi and mobile data.</li>" +
				"<li>Still stuck? Send a report from the app's Support button and message " + PHONE + " on WhatsApp.</li></ul>",
			back: "Back to ZH Commander",
			disclaimer: "ZH Commander is an unofficial fan project, not affiliated with or endorsed by Electronic Arts. Command &amp; Conquer and Generals are trademarks of Electronic Arts.",
			privacy_link: "Privacy",
			doc_title: "ZH Commander: Play with PC friends",
			toggle: "عربي"
		},
		ar: {
			nav_buy: "احصل على مفتاح",
			eyebrow: "اللعب المشترك",
			title: "العب مع أصدقائك على الكمبيوتر",
			lede: "يلعب ZH Commander عبر الإنترنت من خلال GeneralsOnline، وهي نفس الخدمة التي يستخدمها لاعبو الكمبيوتر. يحتاج صديقك على الكمبيوتر إلى اللعبة وإلى إعداد GeneralsOnline كما هو موضّح أدناه. يستغرق ذلك نحو 15 دقيقة، وبعدها يمكن للهواتف وأجهزة الكمبيوتر الانضمام إلى ألعاب بعضها.",
			s_own: "<h2>1. احصل على اللعبة على الكمبيوتر</h2><p>ثبّت <em>Command &amp; Conquer: Generals and Zero Hour</em> من " + EA + " أو " + STEAM + ". غالبًا ما يكون سعرها بضعة دولارات في التخفيضات. تعمل أيضًا نسخ الأقراص القديمة المحدّثة إلى الإصدار 1.04.</p>",
			s_install: "<h2>2. ثبّت GeneralsOnline</h2><p>نزّل برنامج التثبيت من " + GO + " ووجّهه إلى مجلد Zero Hour. استخدم برنامج التثبيت بدلًا من فك ضغط الملفات يدويًا داخل مجلد اللعبة. ثم أنشئ حسابًا على GeneralsOnline.</p><p>يحتاج كل لاعب إلى حسابه الخاص. لا يمكن تسجيل الدخول بالحساب نفسه إلا على جهاز واحد في كل مرة، لذلك لا يمكن للكمبيوتر والهاتف مشاركته.</p>",
			s_folder: "<h2>3. تحقّق من مجلد واحد</h2><p>يحفظ GeneralsOnline الرقعة المجتمعية في مجلد المستندات (Documents). إذا وُجد مجلد " + PATCH_DIR + " داخل مجلد اللعبة أيضًا (يحدث ذلك عند فك ضغط الملفات يدويًا)، فإن ألعاب الكمبيوتر تُظهر ملفات لعبة ترفض الهواتف الانضمام إليها. احذف ذلك المجلد، وتبقى النسخة الموجودة في المستندات.</p>" + TREE,
			s_play: "<h2>4. العب</h2><ul>" +
				"<li>على الكمبيوتر، شغّل اللعبة عبر " + LAUNCHER + ".</li>" +
				"<li>في ZH Commander، أبقِ خيار <strong>اللعب مع لاعبي الكمبيوتر</strong> مفعّلًا واللعبة على <strong>60 هرتز</strong>. كلاهما مفعّل افتراضيًا.</li>" +
				"<li>يمكن لأي طرف أن يستضيف اللعبة. يجدها الطرف الآخر في قائمة ردهات GeneralsOnline وينضم إليها.</li></ul>",
			s_trouble: "<h2>إذا لم يتمكن الهاتف من الانضمام</h2><ul>" +
				"<li><strong>تظهر رسالة بأن ملفات لعبة المضيف مختلفة:</strong> على الكمبيوتر، نفّذ الخطوة 3، ثم أعد تشغيل اللعبة واستضف من جديد.</li>" +
				"<li><strong>يتوقف تسجيل الدخول على الجهاز الآخر:</strong> حساب GeneralsOnline نفسه مستخدم على الجهازين. استخدم حسابين منفصلين.</li>" +
				"<li><strong>تعذّر الاتصال بجميع اللاعبين:</strong> حاول مجددًا على الواي فاي، أو بدّل بين الواي فاي وبيانات الهاتف.</li>" +
				"<li>ما زالت المشكلة قائمة؟ أرسل تقريرًا من زر الدعم في التطبيق وراسل " + PHONE + " على واتساب.</li></ul>",
			back: "العودة إلى ZH Commander",
			disclaimer: "ZH Commander مشروع غير رسمي من المعجبين، غير تابع لشركة Electronic Arts ولا معتمد منها. Command &amp; Conquer وGenerals علامتان تجاريتان لشركة Electronic Arts.",
			privacy_link: "الخصوصية",
			doc_title: "ZH Commander: العب مع أصدقائك على الكمبيوتر",
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
