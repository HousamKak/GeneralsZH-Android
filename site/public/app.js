// Language switching (English in the markup, Arabic here) and the live version/size.
(function () {
	"use strict";

	var AR = {
		nav_start: "كيف تبدأ",
		nav_faq: "أسئلة شائعة",
		nav_buy: "احصل على مفتاح",
		buy_title: "احصل على مفتاح",
		buy_lede: "كل مفتاح يفعّل هاتفًا واحدًا، وبشكل دائم. السعر 5 دولارات.",
		pp_title: "ادفع عبر PayPal أو البطاقة",
		pp_body: "يظهر مفتاحك هنا فور إتمام الدفع.",
		pp_soon: "الدفع عبر البطاقة وPayPal سيتوفر قريبًا. استخدم Whish حتى ذلك الحين.",
		wh_title: "ادفع عبر Whish",
		wh_body: "أرسل 5 دولارات عبر Whish ورسالة إلى <bdi class=\"phone\" dir=\"ltr\">+961 71 406 981</bdi>. ستصلك رسالة فيها مفتاحك.",
		key_label: "مفتاح التفعيل الخاص بك",
		key_copy: "نسخ المفتاح",
		key_help: "افتح ZH Commander وأدخل هذا المفتاح. احتفظ به في مكان آمن؛ ويمكنك استرجاعه أيضًا برقم المعاملة من إيصال PayPal.",
		key_txn: "المعاملة",
		lookup_title: "دفعت عبر PayPal وأضعت مفتاحك؟",
		lookup_label: "رقم المعاملة من إيصال PayPal",
		lookup_button: "ابحث عن مفتاحي",
		eyebrow: "استراتيجية في الوقت الحقيقي · أندرويد",
		lede: "لعبة Command &amp; Conquer Generals: Zero Hour تعمل مباشرة على هاتفك. محرّك اللعبة الأصلي من 2003، مُعاد بناؤه للّمس.",
		crate_title: "صندوق الإمداد",
		spec_contents: "المحتوى",
		spec_contents_v: "ZH Commander لأندرويد",
		spec_platform: "المنصّة",
		spec_platform_v: "أندرويد 9 أو أحدث، 64 بت (arm64)",
		spec_size: "الحجم",
		spec_version: "الإصدار",
		spec_requires: "المتطلبات",
		spec_requires_v: "نسختك الخاصة من Zero Hour، ومفتاح تفعيل",
		download: "تحميل التطبيق",
		crate_note: "التحميل مجاني. <a href=\"#buy\">مفتاح التفعيل بـ 5 دولارات.</a>",
		stamp: "يتطلب التفعيل",
		shots_title: "من أرض المعركة",
		shot_soon: "لقطة الشاشة قريبًا",
		start_title: "كيف تبدأ",
		s1_t: "حمّل وثبّت",
		s1_b: "حمّل ملف APK على هاتفك وافتحه. سيطلب أندرويد السماح بالتثبيت من المتصفح؛ اسمح بذلك مرة واحدة.",
		s2_t: "احصل على مفتاحك",
		s2_b: "سعر المفتاح 5 دولارات. <a href=\"#buy\">ادفع عبر PayPal أو البطاقة</a> واحصل عليه فورًا، أو ادفع عبر Whish. شكل المفتاح مثل <code dir=\"ltr\">GZH-7K3P-Q9XM-A2BC</code>، وكل مفتاح يفعّل هاتفًا واحدًا، وبشكل دائم.",
		s3_t: "أضف ملفات اللعبة",
		s3_b: "اللعبة نفسها غير مضمّنة. اشترِ <a href=\"https://store.steampowered.com/app/2732960/\" rel=\"noopener\">Command &amp; Conquer Generals Zero Hour من Steam</a>، ثم انسخ مجلد اللعبة إلى هاتفك واختره من التطبيق.",
		s4_t: "العب",
		s4_b: "الحملة والمعارك ضد الكمبيوتر وتحدي الجنرالات تعمل بدون إنترنت. والمباريات الأونلاين، ومنها ضد لاعبي الكمبيوتر، تتم عبر GeneralsOnline.",
		features_title: "ماذا ستحصل",
		f1_t: "المحرّك الحقيقي",
		f1_b: "كود اللعبة الأصلي من 2003 مُترجم لهواتف ARM، بدون أي محاكي.",
		f2_t: "مصمّم للّمس",
		f2_b: "انقر للتحديد، اسحب لتحديد مجموعة، حرّك الكاميرا بإصبعين، وقرّب بالقرص.",
		f3_t: "30 أو 60 هرتز",
		f3_b: "سرعة اللعبة الأصلية، أو 60 هرتز لتطابق نسخة GeneralsOnline على الكمبيوتر.",
		f4_t: "أونلاين وشبكة محلية",
		f4_b: "غرف GeneralsOnline واللعب السريع واللعب مع الكمبيوتر، ومباريات بين الهواتف على نفس شبكة الواي فاي.",
		f5_t: "محرّكا رسوميات",
		f5_b: "OpenGL ES افتراضيًا، وVulkan إذا كان هاتفك يدعمه.",
		f6_t: "13 لغة منها العربية",
		f6_b: "حزم نصوص للعبة، مع اتجاه من اليمين لليسار للعربية.",
		faq_title: "أسئلة شائعة",
		q1: "لماذا اللعبة غير مضمّنة؟",
		a1: "رسوم اللعبة وأصواتها وخرائطها ملك لشركة EA. تحتاج إلى نسختك الخاصة؛ سعرها على Steam غالبًا نحو 5 دولارات.",
		q2: "هل أحتاج إلى إنترنت؟",
		a2: "مرة واحدة فقط للتفعيل. بعدها تعمل الحملة والمعارك بدون إنترنت.",
		q3: "هل تعمل على هاتفي؟",
		a3: "تحتاج أندرويد 9 أو أحدث على هاتف 64 بت، ونحو 2.5 غيغابايت مساحة لملفات اللعبة، ويُفضّل 4 غيغابايت ذاكرة. هواتف Snapdragon هي الأفضل؛ هواتف Mali الأقدم تعمل لكن أبطأ.",
		q4: "مقابل ماذا أدفع ثمن المفتاح؟",
		a4: "لبناء هذه النسخة ودعمها. الكود المصدري يبقى مفتوحًا بترخيص GPL: <a href=\"https://github.com/HousamKak/GeneralsZH-Android\" rel=\"noopener\" dir=\"ltr\">github.com/HousamKak/GeneralsZH-Android</a>.",
		disclaimer: "ZH Commander مشروع غير رسمي من المعجبين، غير تابع لشركة Electronic Arts ولا معتمد منها. Command &amp; Conquer وGenerals علامتان تجاريتان لشركة Electronic Arts.",
		credits: "مبني على الكود المصدري الذي نشرته EA بترخيص GPL v3، وعلى GeneralsX وTheSuperHackers ونسخة GeneralsZH لأندرويد من MYSOREZ. <a href=\"https://github.com/HousamKak/GeneralsZH-Android\" rel=\"noopener\">الكود المصدري (GPL v3)</a>"
	};

	var root = document.documentElement;
	var toggle = document.getElementById("lang-toggle");
	var nodes = document.querySelectorAll("[data-i18n], [data-i18n-html]");
	var english = [];
	nodes.forEach(function (el) { english.push(el.innerHTML); });

	function apply(lang) {
		var ar = lang === "ar";
		root.lang = ar ? "ar" : "en";
		root.dir = ar ? "rtl" : "ltr";
		nodes.forEach(function (el, i) {
			var key = el.getAttribute("data-i18n") || el.getAttribute("data-i18n-html");
			el.innerHTML = ar && AR[key] ? AR[key] : english[i];
		});
		toggle.textContent = ar ? "English" : "عربي";
		toggle.lang = ar ? "en" : "ar";
		document.title = ar ? "ZH Commander: لعبة Zero Hour على أندرويد" : "ZH Commander: Zero Hour on Android";
		try { localStorage.setItem("zh-lang", root.lang); } catch (e) { /* storage unavailable */ }
		renderStatuses();
	}

	// ---- messages that change while buying, in both languages ----

	var MSG = {
		en: {
			pp_soon: "Card and PayPal checkout opens soon. Until then, use Whish.",
			pp_ready: "",
			pp_working: "Confirming your payment…",
			pp_pending: "PayPal is still processing your payment. Once it clears, find your key below with your transaction ID.",
			pp_failed: "The payment didn't go through, and you weren't charged. Try again, or pay with Whish.",
			pp_error: "Checkout couldn't load. Refresh the page, or pay with Whish.",
			copied: "Copied",
			lookup_working: "Looking it up…",
			lookup_missing: "No key found for that ID. Check the transaction ID on your PayPal receipt.",
			lookup_error: "Couldn't check right now. Try again in a minute."
		},
		ar: {
			pp_soon: "الدفع عبر البطاقة وPayPal سيتوفر قريبًا. استخدم Whish حتى ذلك الحين.",
			pp_ready: "",
			pp_working: "جارٍ تأكيد الدفع…",
			pp_pending: "ما زال PayPal يعالج دفعتك. عند اكتمالها، ابحث عن مفتاحك في الأسفل برقم المعاملة.",
			pp_failed: "لم تتم عملية الدفع، ولم يُخصم منك أي مبلغ. حاول مجددًا أو ادفع عبر Whish.",
			pp_error: "تعذّر تحميل صفحة الدفع. حدّث الصفحة أو ادفع عبر Whish.",
			copied: "تم النسخ",
			lookup_working: "جارٍ البحث…",
			lookup_missing: "لا يوجد مفتاح بهذا الرقم. تحقّق من رقم المعاملة في إيصال PayPal.",
			lookup_error: "تعذّر التحقق الآن. حاول بعد دقيقة."
		}
	};
	var statuses = {};

	function setStatus(id, key, isError) {
		statuses[id] = { key: key, isError: !!isError };
		renderStatuses();
	}

	function renderStatuses() {
		if (!statuses) return; // apply() can run before the declarations below have executed
		Object.keys(statuses).forEach(function (id) {
			var el = document.getElementById(id);
			var s = statuses[id];
			el.textContent = MSG[root.lang === "ar" ? "ar" : "en"][s.key] || "";
			el.classList.toggle("is-error", s.isError);
		});
	}

	function showKey(result) {
		document.getElementById("key-value").textContent = result.key;
		document.getElementById("key-txn").textContent = result.transaction || "";
		var box = document.getElementById("key-result");
		box.hidden = false;
		box.scrollIntoView({ behavior: "smooth", block: "center" });
	}

	document.getElementById("key-copy").addEventListener("click", function () {
		var btn = this;
		var text = document.getElementById("key-value").textContent;
		if (!navigator.clipboard) return;
		navigator.clipboard.writeText(text).then(function () {
			var label = btn.textContent;
			btn.textContent = MSG[root.lang === "ar" ? "ar" : "en"].copied;
			setTimeout(function () { btn.textContent = label; }, 1500);
		});
	});

	function postJson(url, body) {
		return fetch(url, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(body || {})
		}).then(function (r) { return r.json(); });
	}

	function loadScript(src) {
		return new Promise(function (resolve, reject) {
			var s = document.createElement("script");
			s.src = src;
			s.onload = resolve;
			s.onerror = reject;
			document.head.appendChild(s);
		});
	}

	setStatus("paypal-status", "pp_soon");
	fetch("/api/paypal/config")
		.then(function (r) { return r.json(); })
		.then(function (cfg) {
			if (!cfg.enabled) return;
			return loadScript("https://www.paypal.com/sdk/js?client-id=" + encodeURIComponent(cfg.clientId) +
				"&currency=" + cfg.currency + "&intent=capture&components=buttons")
				.then(function () {
					setStatus("paypal-status", "pp_ready");
					return window.paypal.Buttons({
						style: { layout: "vertical", shape: "rect", color: "gold", label: "pay" },
						createOrder: function () {
							return postJson("/api/paypal/order").then(function (d) {
								if (!d.id) throw new Error(d.error || "order failed");
								return d.id;
							});
						},
						onApprove: function (data) {
							setStatus("paypal-status", "pp_working");
							return postJson("/api/paypal/capture", { orderID: data.orderID }).then(function (d) {
								if (d.key) {
									setStatus("paypal-status", "pp_ready");
									showKey(d);
								} else {
									setStatus("paypal-status", d.error === "payment_pending" ? "pp_pending" : "pp_failed", true);
								}
							});
						},
						onError: function () { setStatus("paypal-status", "pp_failed", true); }
					}).render("#paypal-buttons");
				});
		})
		.catch(function () { setStatus("paypal-status", "pp_error", true); });

	document.getElementById("lookup-form").addEventListener("submit", function (e) {
		e.preventDefault();
		var id = document.getElementById("lookup-id").value.trim();
		if (!id) return;
		setStatus("lookup-status", "lookup_working");
		fetch("/api/paypal/key?id=" + encodeURIComponent(id))
			.then(function (r) { return r.json().then(function (d) { return { ok: r.ok, d: d }; }); })
			.then(function (res) {
				if (res.ok && res.d.key) {
					setStatus("lookup-status", "pp_ready");
					showKey(res.d);
				} else {
					setStatus("lookup-status", "lookup_missing", true);
				}
			})
			.catch(function () { setStatus("lookup-status", "lookup_error", true); });
	});

	var saved = null;
	try { saved = localStorage.getItem("zh-lang"); } catch (e) { /* storage unavailable */ }
	var initial = saved || ((navigator.language || "").toLowerCase().indexOf("ar") === 0 ? "ar" : "en");
	if (initial === "ar") apply("ar");

	toggle.addEventListener("click", function () {
		apply(root.lang === "ar" ? "en" : "ar");
	});

	fetch("/api/latest")
		.then(function (r) { return r.ok ? r.json() : null; })
		.then(function (info) {
			if (!info) return;
			document.getElementById("apk-version").textContent = info.version;
			document.getElementById("apk-size").textContent = Math.round(info.size / 1048576) + " MB";
		})
		.catch(function () { /* keep the built-in values */ });
})();
