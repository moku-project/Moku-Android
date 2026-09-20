package dev.moku.mobile.novel

/**
 * The JS environment a Keiyoushi/LNReader-style novel plugin expects, reduced to what's
 * feasible as pure JS + one native bridge call — see NovelJsRuntime for why this is a
 * WebView (real Chromium JS engine) rather than a GraalVM-style approach: WebView already
 * provides DOMParser/Promise/TextEncoder/localStorage natively, so cheerio and dayjs can be
 * plain JS shims instead of a hand-marshalled host binding for every DOM operation (which is
 * what Tsunagu's GraalVM-based NovelJsBridge.kt has to do, JVM-side, since it has no browser
 * engine at all). Only network I/O crosses into Kotlin: a real fetch() would be blocked by
 * CORS from reading cross-origin response bodies in a browser context, so fetch is proxied
 * through the `AndroidBridge.nativeFetch` JavascriptInterface instead.
 *
 * Modules implemented: cheerio (a jQuery-lite subset over DOMParser), htmlparser2 (a SAX-style
 * Parser also built over DOMParser — buffered rather than truly streaming, since we have no
 * incremental parser, but callback-compatible), dayjs (format/add/subtract subset), @libs/fetch,
 * @libs/novelStatus, @libs/defaultCover, @libs/isAbsoluteUrl, @libs/utils, @libs/storage,
 * @libs/filterInputs.
 */
object NovelBootstrapJs {

    val SCRIPT = """
        window.__pendingCalls = {};
        window.__fetchCallbacks = {};

        function __uid() { return 'c' + Math.random().toString(36).slice(2) + Date.now(); }

        // ---- fetch, proxied through native OkHttp (avoids CORS opaque-response issues) ----
        function fetch(url, opts) {
            return new Promise(function(resolve, reject) {
                var id = __uid();
                window.__fetchCallbacks[id] = { resolve: resolve, reject: reject };
                try {
                    AndroidBridge.nativeFetch(id, String(url), JSON.stringify(opts || {}));
                } catch (e) {
                    delete window.__fetchCallbacks[id];
                    reject(e);
                }
            });
        }

        function __resolveFetch(id, ok, status, url, body, error) {
            var cb = window.__fetchCallbacks[id];
            delete window.__fetchCallbacks[id];
            if (!cb) return;
            if (error) { cb.reject(new Error(error)); return; }
            cb.resolve({
                ok: ok, status: status, url: url,
                text: function() { return Promise.resolve(body); },
                json: function() { return Promise.resolve(JSON.parse(body)); },
            });
        }

        // ---- module registry / require() ----
        var __modules = {};

        __modules['cheerio'] = (function() {
            function wrap(nodeList) {
                var arr = Array.prototype.slice.call(nodeList);
                var self = {
                    length: arr.length,
                    each: function(fn) { arr.forEach(function(el, i) { fn.call(el, i, el); }); return self; },
                    map: function(fn) { return wrap(arr.map(function(el, i) { return fn.call(el, i, el); }).filter(Boolean)); },
                    toArray: function() { return arr; },
                    first: function() { return wrap(arr.slice(0, 1)); },
                    last: function() { return wrap(arr.slice(-1)); },
                    eq: function(i) { return wrap(arr[i] ? [arr[i]] : []); },
                    text: function() { return arr.map(function(el) { return el.textContent || ''; }).join(''); },
                    html: function() { return arr[0] ? arr[0].innerHTML : null; },
                    attr: function(name) { return arr[0] ? (arr[0].getAttribute(name) || undefined) : undefined; },
                    find: function(sel) { return wrap(arr.length ? arr[0].querySelectorAll(sel) : []); },
                    remove: function() { arr.forEach(function(el) { el.parentNode && el.parentNode.removeChild(el); }); return self; },
                    next: function() { return wrap(arr[0] && arr[0].nextElementSibling ? [arr[0].nextElementSibling] : []); },
                    parent: function() { return wrap(arr[0] && arr[0].parentElement ? [arr[0].parentElement] : []); },
                    hasClass: function(c) { return arr[0] ? arr[0].classList.contains(c) : false; },
                };
                return self;
            }
            function load(html) {
                var doc = new DOMParser().parseFromString(html, 'text/html');
                var fn = function(sel) {
                    if (typeof sel !== 'string') return wrap([sel]);
                    return wrap(doc.querySelectorAll(sel));
                };
                fn.root = function() { return wrap([doc.documentElement]); };
                return fn;
            }
            return { load: load };
        })();

        __modules['dayjs'] = (function() {
            function parseInput(v) {
                if (v === undefined || v === null) return new Date();
                if (typeof v === 'number') return new Date(v);
                var d = new Date(v);
                return isNaN(d.getTime()) ? null : d;
            }
            function shift(date, n, unit) {
                var d = new Date(date.getTime());
                switch ((unit || 'millisecond').toLowerCase().replace(/s$/, '')) {
                    case 'year': d.setFullYear(d.getFullYear() + n); break;
                    case 'month': d.setMonth(d.getMonth() + n); break;
                    case 'week': d.setDate(d.getDate() + n * 7); break;
                    case 'day': d.setDate(d.getDate() + n); break;
                    case 'hour': d.setHours(d.getHours() + n); break;
                    case 'minute': d.setMinutes(d.getMinutes() + n); break;
                    case 'second': d.setSeconds(d.getSeconds() + n); break;
                    default: d.setMilliseconds(d.getMilliseconds() + n);
                }
                return d;
            }
            function pad(n) { return n < 10 ? '0' + n : '' + n; }
            var MONTHS = ['January','February','March','April','May','June','July','August','September','October','November','December'];
            function format(d, fmt) {
                if (!d) return 'Invalid Date';
                fmt = fmt || 'YYYY-MM-DDTHH:mm:ssZ';
                if (fmt === 'LL') return MONTHS[d.getMonth()] + ' ' + d.getDate() + ', ' + d.getFullYear();
                return fmt
                    .replace('YYYY', d.getFullYear())
                    .replace('MM', pad(d.getMonth() + 1))
                    .replace('DD', pad(d.getDate()))
                    .replace('HH', pad(d.getHours()))
                    .replace('mm', pad(d.getMinutes()))
                    .replace('ss', pad(d.getSeconds()));
            }
            function wrapDate(d) {
                return {
                    isValid: function() { return d !== null; },
                    valueOf: function() { return d ? d.getTime() : NaN; },
                    unix: function() { return d ? Math.floor(d.getTime() / 1000) : NaN; },
                    toISOString: function() { return d ? d.toISOString() : 'Invalid Date'; },
                    year: function() { return d ? d.getFullYear() : NaN; },
                    month: function() { return d ? d.getMonth() : NaN; },
                    date: function() { return d ? d.getDate() : NaN; },
                    format: function(fmt) { return format(d, fmt); },
                    subtract: function(n, unit) { return wrapDate(d ? shift(d, -n, unit) : null); },
                    add: function(n, unit) { return wrapDate(d ? shift(d, n, unit) : null); },
                };
            }
            var dayjs = function(v) { return wrapDate(parseInput(v)); };
            return { __esModule: true, default: dayjs };
        })();

        __modules['@libs/fetch'] = {
            fetchApi: function(url, init) { return fetch(url, init); },
            fetchText: function(url, init) { return fetch(url, init).then(function(r) { return r.text(); }).catch(function() { return ''; }); },
        };
        __modules['@libs/novelStatus'] = {
            NovelStatus: {
                Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed',
                PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus',
            },
        };
        __modules['@libs/defaultCover'] = { defaultCover: '' };
        __modules['@libs/isAbsoluteUrl'] = { isUrlAbsolute: function(u) { return /^https?:\/\//.test(u); } };
        __modules['@libs/utils'] = {
            utf8ToBytes: function(s) { return new TextEncoder().encode(s); },
            bytesToUtf8: function(b) { return new TextDecoder().decode(b); },
        };
        __modules['@libs/storage'] = (function(ns) {
            return {
                storage: {
                    get: function(k) { try { return JSON.parse(localStorage.getItem(ns + ':' + k) || 'null'); } catch (e) { return null; } },
                    set: function(k, v) { localStorage.setItem(ns + ':' + k, JSON.stringify(v)); },
                    delete: function(k) { localStorage.removeItem(ns + ':' + k); },
                },
            };
        })('__NOVEL_NAMESPACE__');
        __modules['@libs/filterInputs'] = {
            FilterTypes: { TextInput: 'Text', Picker: 'Picker', CheckboxGroup: 'Checkbox', ExcludableCheckboxGroup: 'XCheckbox', Switch: 'Switch' },
        };

        // htmlparser2's SAX-style Parser, over DOMParser (the same real-DOM trick as
        // cheerio above) — buffer everything until end() since we have no true streaming
        // parser, then walk the resulting tree emitting the same callbacks htmlparser2 does.
        __modules['htmlparser2'] = (function() {
            var VOID_ELEMENTS = { area:1, base:1, br:1, col:1, embed:1, hr:1, img:1, input:1, link:1, meta:1, param:1, source:1, track:1, wbr:1 };
            function walk(node, onopentag, ontext, onclosetag) {
                if (node.nodeType === 1) {
                    var attribs = {};
                    for (var i = 0; i < node.attributes.length; i++) {
                        attribs[node.attributes[i].name] = node.attributes[i].value;
                    }
                    if (onopentag) onopentag(node.tagName.toLowerCase(), attribs);
                    for (var c = 0; c < node.childNodes.length; c++) walk(node.childNodes[c], onopentag, ontext, onclosetag);
                    if (onclosetag) onclosetag(node.tagName.toLowerCase());
                } else if (node.nodeType === 3 && ontext) {
                    ontext(node.nodeValue);
                }
            }
            function Parser(options) {
                var buffer = '';
                this.write = function(chunk) { buffer += chunk; };
                this.end = function() {
                    var doc = new DOMParser().parseFromString('<body>' + buffer + '</body>', 'text/html');
                    var body = doc.body;
                    for (var i = 0; i < body.childNodes.length; i++) {
                        walk(body.childNodes[i], options && options.onopentag, options && options.ontext, options && options.onclosetag);
                    }
                    if (options && options.onend) options.onend();
                };
                this.isVoidElement = function(name) { return !!VOID_ELEMENTS[name.toLowerCase()]; };
            }
            return { Parser: Parser };
        })();

        function require(name) {
            var mod = __modules[name];
            if (mod === undefined) throw new Error('novel plugin requires unsupported module: ' + name);
            return mod;
        }

        // ---- plugin call dispatch (Kotlin -> JS -> plugin -> Kotlin, promise-aware) ----
        function __callPlugin(callId, methodName, argsJson) {
            try {
                var args = JSON.parse(argsJson);
                // popularNovels/searchNovels take an options object with a `filters` field
                // shaped like the plugin's own declared filter schema (each entry carrying
                // a `value`) — an empty {} makes plugins that destructure e.g. filters.genres.value
                // throw. Fill in the plugin's own defaults (window.__plugin.filters) rather
                // than requiring the host to already know this plugin's specific filter shape.
                if (methodName === 'popularNovels' && args[1] && typeof args[1] === 'object') {
                    args[1].filters = window.__plugin.filters || {};
                }
                var result = window.__plugin[methodName].apply(window.__plugin, args);
                Promise.resolve(result).then(function(value) {
                    AndroidBridge.onCallResult(callId, JSON.stringify(value === undefined ? null : value));
                }).catch(function(err) {
                    AndroidBridge.onCallError(callId, String(err && err.message ? err.message : err));
                });
            } catch (err) {
                AndroidBridge.onCallError(callId, String(err && err.message ? err.message : err));
            }
        }
    """.trimIndent()
}
