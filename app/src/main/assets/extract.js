// Injected into a WebView to detect manga series/chapter pages.
// Returns a plain object; evaluateJavascript serializes it to JSON for Kotlin.
// __RULE__ is replaced with the site rule JSON (see SiteRule.kt).
(function (rule) {
  rule = rule || {};

  function canon(u) {
    try {
      var x = new URL(u, location.href);
      x.hash = '';
      return x.origin + x.pathname.replace(/\/+$/, '') + x.search;
    } catch (e) { return null; }
  }
  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  }
  function re(p) {
    if (!p) return null;
    try { return new RegExp(p, 'i'); } catch (e) { return null; }
  }
  function pathOf(u) {
    try { return new URL(u).pathname; } catch (e) { return ''; }
  }

  var chapterRe = re(rule.chapterNumberRegex) ||
    /(?:^|[^a-z])(?:chapter|chap|ch|episode|ep)[\s._\/-]*(\d+(?:\.\d+)?)/i;
  function num(s) {
    var m = chapterRe.exec(s || '');
    return m ? parseFloat(m[1]) : null;
  }

  function bestSrc(img) {
    var c = [
      img.getAttribute('data-src'),
      img.getAttribute('data-lazy-src'),
      img.getAttribute('data-original'),
      img.getAttribute('data-url')
    ];
    var ss = img.getAttribute('data-srcset') || img.getAttribute('srcset');
    if (ss) {
      var best = null, bw = -1;
      ss.split(',').forEach(function (p) {
        var parts = p.trim().split(/\s+/);
        var w = parseFloat(parts[1]) || 1;
        if (parts[0] && w > bw) { bw = w; best = parts[0]; }
      });
      c.push(best);
    }
    c.push(img.currentSrc, img.getAttribute('src'));
    for (var i = 0; i < c.length; i++) {
      var s = c[i];
      if (s && !/^(data|blob|about):/i.test(s.trim())) return abs(s.trim());
    }
    return null;
  }

  function textOf(sel) {
    try {
      var e = document.querySelector(sel);
      if (!e) return null;
      var t = e.getAttribute('content') || e.innerText || e.textContent || '';
      t = t.trim();
      return t || null;
    } catch (e2) { return null; }
  }

  var here = canon(location.href) || location.href;

  // ---- title & cover ----
  var title = (rule.titleSelector && textOf(rule.titleSelector)) ||
    textOf('meta[property="og:title"]') || textOf('h1') || document.title;

  // Pages often show covers of other series too (promos, "related"), so prefer
  // the cover whose URL contains this series' slug, then og:image.
  var slug = (pathOf(here).split('/').filter(Boolean).pop() || '')
    .replace(/-[0-9a-f]{6,}$/i, '').toLowerCase();
  var cover = null, firstCover = null;
  if (rule.coverSelector) {
    try {
      Array.prototype.forEach.call(document.querySelectorAll(rule.coverSelector), function (ce) {
        var u = ce.tagName === 'IMG' ? bestSrc(ce) : abs(ce.getAttribute('content') || ce.getAttribute('href'));
        if (!u) return;
        if (!firstCover) firstCover = u;
        if (!cover && slug.length > 2 && u.toLowerCase().indexOf(slug) >= 0) cover = u;
      });
    } catch (e) {}
  }
  if (!cover) {
    var og = document.querySelector('meta[property="og:image"]');
    if (og) cover = abs(og.getAttribute('content'));
  }
  if (!cover) cover = firstCover;

  // ---- chapter links ----
  if (rule.expandSelector) {
    try {
      document.querySelectorAll(rule.expandSelector).forEach(function (b) { b.click(); });
    } catch (e) {}
  }
  var anchors;
  try { anchors = document.querySelectorAll(rule.chapterLinkSelector || 'a[href]'); }
  catch (e) { anchors = document.querySelectorAll('a[href]'); }
  var chapterUrlRe = re(rule.chapterUrlPattern);
  var seen = {}, chapters = [];
  Array.prototype.forEach.call(anchors, function (a) {
    var u = canon(a.getAttribute('href'));
    if (!u || u === here || seen[u]) return;
    if (!/^https?:/i.test(u)) return;
    var p = pathOf(u);
    if (chapterUrlRe) { if (!chapterUrlRe.test(u)) return; }
    else if (num(p) === null) return;
    var raw = (a.innerText || a.textContent || '').trim();
    var label = raw.split('\n').map(function (s) { return s.trim(); }).filter(Boolean)[0] || '';
    label = label.replace(/\s+/g, ' ').slice(0, 80);
    var n = num(p);
    if (n === null) n = num(' ' + label);
    seen[u] = true;
    chapters.push({ url: u, label: label, number: n });
  });
  // Prefer chapters that live under the current page's path (ignores sidebars linking other series).
  var scoped = chapters.filter(function (c) { return c.url.indexOf(here + '/') === 0; });
  if (scoped.length >= 2) chapters = scoped;

  // ---- page images ----
  var root = document;
  if (rule.imageContainerSelector) {
    try { root = document.querySelector(rule.imageContainerSelector) || document; } catch (e) {}
  }
  var f = rule.imageFilter || {};
  var minW = f.minWidth || 300;
  var altRe = re(f.altRegex);
  var pageAltRe = rule.pageNumberFrom ? re(rule.pageNumberFrom.alt) : null;
  var pageUrlRe = rule.pageNumberFrom ? re(rule.pageNumberFrom.url) : null;
  var hasRule = !!(f.urlContains || altRe);
  var images = [], seenImg = {};
  Array.prototype.forEach.call(root.querySelectorAll('img'), function (img) {
    var src = bestSrc(img);
    if (!src || seenImg[src]) return;
    var alt = img.getAttribute('alt') || '';
    if (hasRule) {
      var ok = (f.urlContains && src.indexOf(f.urlContains) >= 0) || (altRe && altRe.test(alt));
      if (!ok) return;
    } else {
      if (img.closest && img.closest('header,footer,nav,aside')) return;
      var w = img.naturalWidth || parseInt(img.getAttribute('width') || '0', 10) || 0;
      if (w > 0 && w < minW) return;
      if (w === 0 && img.complete && !img.getAttribute('data-src')) return;
    }
    var page = null, m;
    if (pageAltRe && (m = pageAltRe.exec(alt))) page = parseInt(m[1], 10);
    else if (pageUrlRe && (m = pageUrlRe.exec(src))) page = parseInt(m[1], 10);
    seenImg[src] = true;
    images.push({ url: src, page: page });
  });

  // Fallback: image URLs embedded in the HTML/scripts (common with JS-rendered readers).
  if (images.length === 0 && f.urlContains) {
    var html = document.documentElement.innerHTML
      .replace(/\\u002[fF]/g, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
    var urlRe = /https?:\/\/[^"'\s<>\\)]+?\.(?:webp|jpe?g|png|avif|gif)(?:\?[^"'\s<>\\)]*)?/gi;
    var mm;
    while ((mm = urlRe.exec(html)) !== null) {
      var u2 = mm[0];
      if (u2.indexOf(f.urlContains) < 0 || seenImg[u2]) continue;
      seenImg[u2] = true;
      images.push({ url: u2, page: null });
    }
  }

  if (images.length > 1 && images.every(function (i) { return i.page !== null; })) {
    images.sort(function (a, b) { return a.page - b.page; });
  }

  // ---- page type ----
  var isChapterUrl = chapterUrlRe ? chapterUrlRe.test(here) : num(pathOf(here)) !== null;
  var seriesRe = re(rule.seriesUrlPattern);
  var isSeriesUrl = seriesRe ? seriesRe.test(here) : false;
  var type = 'none';
  if (isSeriesUrl && chapters.length > 0) type = 'series';
  else if (isChapterUrl && images.length > 0) type = 'chapter';
  else if (chapters.length >= 3 && images.length < 3) type = 'series';
  else if (images.length >= 3) type = 'chapter';
  else if (chapters.length >= 3) type = 'series';

  var seriesUrl = here.replace(/\/(?:chapter|chap|ch|episode|ep)[\/_-]?[\d.]+.*$/i, '');
  if (seriesUrl === here) seriesUrl = null;

  var d = document.documentElement;
  return {
    type: type,
    title: title,
    cover: cover,
    url: here,
    seriesUrl: seriesUrl,
    chapters: chapters,
    images: images,
    challenge: /just a moment|attention required|checking your browser|verify you are human/i.test(document.title || ''),
    scrollY: window.scrollY,
    scrollMax: Math.max(d.scrollHeight, document.body ? document.body.scrollHeight : 0) - window.innerHeight
  };
})(__RULE__);
