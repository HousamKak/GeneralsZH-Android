// The /changes page: every release's notes from /api/notes, in English or Arabic. The language
// is the one chosen on the main page (localStorage "zh-lang"), and the toggle here changes both.
(function () {
	"use strict";

	var TEXT = {
		en: {
			nav_buy: "Get a key",
			eyebrow: "Release notes",
			title: "What's new",
			lede: "Every release of ZH Commander, newest first. Updates are offered inside the app.",
			back: "Back to ZH Commander",
			disclaimer: "ZH Commander is an unofficial fan project, not affiliated with or endorsed by Electronic Arts. Command &amp; Conquer and Generals are trademarks of Electronic Arts.",
			loading: "Loading the release notes…",
			empty: "No release notes yet.",
			error: "The release notes could not be loaded. Try again in a minute.",
			version: "Version",
			latest: "Latest",
			doc_title: "ZH Commander: What's new",
			privacy_link: "Privacy",
			toggle: "عربي"
		},
		ar: {
			nav_buy: "احصل على مفتاح",
			eyebrow: "ملاحظات الإصدار",
			title: "ما الجديد",
			lede: "كل إصدارات ZH Commander، من الأحدث إلى الأقدم. تُعرض التحديثات داخل التطبيق.",
			back: "العودة إلى ZH Commander",
			disclaimer: "ZH Commander مشروع غير رسمي من المعجبين، غير تابع لشركة Electronic Arts ولا معتمد منها. Command &amp; Conquer وGenerals علامتان تجاريتان لشركة Electronic Arts.",
			loading: "جارٍ تحميل ملاحظات الإصدار…",
			empty: "لا توجد ملاحظات إصدار بعد.",
			error: "تعذّر تحميل ملاحظات الإصدار. حاول بعد دقيقة.",
			version: "الإصدار",
			latest: "الأحدث",
			doc_title: "ZH Commander: ما الجديد",
			privacy_link: "الخصوصية",
			toggle: "English"
		}
	};

	var root = document.documentElement;
	var toggle = document.getElementById("lang-toggle");
	var list = document.getElementById("release-list");
	var status = document.getElementById("changes-status");
	var notes = null;
	var state = "loading";

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
		status.textContent = state === "ready" ? "" : t[state];
		list.textContent = "";
		if (!notes) return;
		notes.forEach(function (n, i) {
			var section = n[lang()];
			var li = document.createElement("li");
			li.className = "release";
			li.id = "v" + n.version;

			var head = document.createElement("div");
			head.className = "release-head";
			var tag = document.createElement("span");
			tag.className = "release-tag";
			var bdi = document.createElement("bdi");
			bdi.textContent = n.version;
			tag.append(t.version + " ", bdi);
			head.append(tag);
			if (i === 0) {
				var latest = document.createElement("span");
				latest.className = "release-latest";
				latest.textContent = t.latest;
				head.append(latest);
			}
			if (n.date) {
				var time = document.createElement("time");
				time.dateTime = n.date;
				time.textContent = new Date(n.date + "T12:00:00Z").toLocaleDateString(lang() === "ar" ? "ar" : "en-GB",
					{ year: "numeric", month: "long", day: "numeric" });
				head.append(time);
			}

			var h2 = document.createElement("h2");
			h2.textContent = section.title;
			var ul = document.createElement("ul");
			section.items.forEach(function (item) {
				var bullet = document.createElement("li");
				bullet.textContent = item;
				ul.append(bullet);
			});
			li.append(head, h2, ul);
			list.append(li);
		});
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

	fetch("/api/notes")
		.then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
		.then(function (data) {
			notes = data.notes || [];
			state = notes.length ? "ready" : "empty";
			render();
			if (location.hash) {
				var target = document.getElementById(location.hash.slice(1));
				if (target) target.scrollIntoView();
			}
		})
		.catch(function () {
			state = "error";
			render();
		});
})();
