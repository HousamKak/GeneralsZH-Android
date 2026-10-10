// GeneralsX @feature ZH Commander 10/10/2026 Count this page view for App Monitor (src/hit.ts).
// No cookies and nothing stored in the browser: the page sends its path, language and the referrer
// (the server keeps only the referrer's host name). window.zhHit sends other site events.
(function () {
	"use strict";
	function send(data) {
		var body = JSON.stringify(data);
		try {
			if (navigator.sendBeacon && navigator.sendBeacon("/api/hit", body)) return;
		} catch (e) { /* fall back to fetch */ }
		try {
			fetch("/api/hit", { method: "POST", body: body, keepalive: true }).catch(function () { /* best effort */ });
		} catch (e) { /* best effort */ }
	}
	window.zhHit = function (name, props) {
		var data = { name: name, lang: document.documentElement.lang };
		Object.keys(props || {}).forEach(function (k) { data[k] = props[k]; });
		send(data);
	};
	send({ name: "site_visit", page: location.pathname, lang: document.documentElement.lang, ref: document.referrer });
})();
