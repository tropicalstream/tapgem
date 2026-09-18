package com.tapgem.app.core.apps

/**
 * Makes a vibe-coded app's state reachable without its cooperation.
 *
 * Generated apps keep their state in `let` / `const` variables — at the top of
 * a script or, just as often, inside an IIFE / DOMContentLoaded wrapper — and
 * redraw through zero-argument functions (`render()`, `updateStats()`). Nothing
 * outside that scope can see those variables, so before the page is loaded the
 * inline scripts are rewritten: at the end of the scope that owns the
 * declarations, a registration call hands `window.__tgReg` getters/setters for
 * every variable and a reference to every function. The runtime side
 * (WidgetView.APP_STATE_JS) then snapshots the values as JSON, and after a
 * reload puts them back and calls the renderers — a checkers board comes back
 * mid-game whether or not the author thought of persistence.
 *
 * The rewrite never changes what the app does: it only appends code.
 */
object AppInstrumenter {

    class Plan(val vars: List<Pair<String, String>>, val fns: List<String>, val insertAt: Int)

    private val SCRIPT = Regex("<script(?![^>]*\\bsrc=)([^>]*)>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)
    private val RESERVED = setOf("window", "document", "console", "undefined", "null", "true", "false", "this")

    fun instrument(html: String): String {
        if (html.contains("window.__tgReg")) return html   // already done
        val rewritten = SCRIPT.replace(html) { m ->
            val attrs = m.groupValues[1]; val body = m.groupValues[2]
            if (attrs.contains("type=", ignoreCase = true) && !attrs.contains("javascript", ignoreCase = true) && !attrs.contains("module", ignoreCase = true)) return@replace m.value
            val plan = plan(body) ?: return@replace m.value
            val reg = registration(plan)
            "<script$attrs>" + body.substring(0, plan.insertAt) + reg + body.substring(plan.insertAt) + "</script>"
        }
        // The registry must exist before the app's own scripts run.
        val runtime = "<script>$RUNTIME</script>"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(rewritten)
        return if (head != null) rewritten.substring(0, head.range.last + 1) + runtime + rewritten.substring(head.range.last + 1)
        else runtime + rewritten
    }

    /**
     * Page side: collects registrations, snapshots the registered variables as JSON
     * (functions, DOM nodes and cycles dropped; Map/Set kept), restores them —
     * `let`/`var` through the setter, `const` arrays and objects in place — and
     * calls the zero-argument render-ish functions so the screen matches again.
     */
    private const val RUNTIME = """
(function(){ if(window.__tgState) return; var regs=[]; window.__tgReg=function(r){ regs.push(r); };
 function safe(v){ var seen=[]; return JSON.stringify(v,function(k,val){ if(typeof val==='function'||typeof val==='symbol') return undefined; if(val&&typeof val==='object'){ if(typeof Node!=='undefined'&&val instanceof Node) return undefined; if(val instanceof Map) return {__map:Array.from(val.entries())}; if(val instanceof Set) return {__set:Array.from(val.values())}; if(seen.indexOf(val)>=0) return undefined; seen.push(val); } return val; }); }
 function revive(v){ if(v&&typeof v==='object'){ if(v.__map) return new Map(v.__map.map(function(e){ return [e[0],revive(e[1])]; })); if(v.__set) return new Set(v.__set.map(revive)); for(var k in v) v[k]=revive(v[k]); } return v; }
 var RENDER=/^(render|draw|redraw|update|refresh|paint|repaint|show|display|layout|rebuild|sync)/i;
 /* Transient handles and clocks must not come back from a snapshot: a stale timestamp or timer id is a bug, not state. */
 var SKIP=/^(raf|rafId|frameId|timer|timerId|interval|intervalId|timeout|timeoutId|loop|loopId|anim|animId|noteTimer|toastTimer|audioCtx|audioContext|ctx|context|canvas|cv|bg|lastT|lastTime|lastFrame|lastTick|prevT|prevTime|now|running|animating|playing|ws|socket|worker)$/i;
 window.__tgState={
  plan:function(){ return JSON.stringify(regs.map(function(r){ return {vars:Object.keys(r.vars),fns:Object.keys(r.fns)}; })); },
  snapshot:function(){ if(!regs.length) return null; var out=[]; regs.forEach(function(r){ var o={}; for(var nm in r.vars){ try{ var val=r.vars[nm].get(); if(val===undefined||typeof val==='function') continue; if(typeof Node!=='undefined'&&val instanceof Node) continue; var js=safe(val); if(js!==undefined) o[nm]=JSON.parse(js); }catch(e){} } out.push(o); }); return JSON.stringify({r:out}); },
  restore:function(json){ var data; try{ data=JSON.parse(json).r||[]; }catch(e){ return 'bad json'; } var n=0,called=[]; regs.forEach(function(r,idx){ var o=data[idx]||{}; for(var nm in o){ var d=r.vars[nm]; if(!d||SKIP.test(nm)) continue; var val=revive(o[nm]); try{ if(d.set){ d.set(val); n++; } else { var cur=d.get(); if(Array.isArray(cur)&&Array.isArray(val)){ cur.length=0; val.forEach(function(x){ cur.push(x); }); n++; } else if(cur&&typeof cur==='object'&&val&&typeof val==='object'){ for(var k in cur) delete cur[k]; Object.assign(cur,val); n++; } } }catch(e){} } for(var fn in r.fns){ if(!RENDER.test(fn)) continue; try{ var f=r.fns[fn](); if(typeof f==='function'&&f.length===0){ f(); called.push(fn); } }catch(e){} } for(var vn in r.vars){ if(!RENDER.test(vn)) continue; try{ var fv=r.vars[vn].get(); if(typeof fv==='function'&&fv.length===0){ fv(); called.push(vn); } }catch(e){} } }); return 'restored '+n+' vars; called '+called.join(','); }
 }; })();
"""

    /** Where the app's state lives and what it is called; null when nothing declared. */
    fun plan(src: String): Plan? {
        val b = blank(src)
        val top = scan(b, 0, b.length)
        if (top.vars.isNotEmpty()) return Plan(top.vars, top.fns, src.length)
        // Everything wrapped in one function ((function(){…})(), onload, DOMContentLoaded): look inside its body.
        val block = largestBlock(b) ?: return null
        if (block.second - block.first < b.length * 0.5) return null
        val inner = scan(b, block.first + 1, block.second)
        if (inner.vars.isEmpty()) return null
        return Plan(inner.vars, inner.fns, block.second)
    }

    private fun registration(p: Plan): String {
        val vars = p.vars.joinToString(",") { (name, kind) ->
            val get = "get:function(){return typeof $name==='undefined'?undefined:$name}"
            val set = if (kind == "const") "" else ",set:function(v){$name=v}"
            "$name:{$get$set}"
        }
        val fns = p.fns.joinToString(",") { f -> "$f:function(){return typeof $f==='function'?$f:undefined}" }
        return "\n;try{if(window.__tgReg)window.__tgReg({vars:{$vars},fns:{$fns}});}catch(e){}\n"
    }

    private class Scan(val vars: List<Pair<String, String>>, val fns: List<String>)

    /** Declarations and function names at brace depth 0 within [from, to) of blanked source. */
    private fun scan(b: String, from: Int, to: Int): Scan {
        val vars = ArrayList<Pair<String, String>>(); val kinds = HashSet<String>(); val fns = ArrayList<String>()
        var depth = 0; var i = from
        fun ident(at: Int): String? { var j = at; while (j < to && b[j].isWhitespace()) j++; val s = j; while (j < to && (b[j].isLetterOrDigit() || b[j] == '_' || b[j] == '$')) j++; return if (j > s && !b[s].isDigit()) b.substring(s, j) else null }
        while (i < to) {
            val c = b[i]
            if (c == '{' || c == '(' || c == '[') { depth++; i++; continue }
            if (c == '}' || c == ')' || c == ']') { depth--; i++; continue }
            if (depth == 0 && (i == from || !(b[i - 1].isLetterOrDigit() || b[i - 1] == '_' || b[i - 1] == '$' || b[i - 1] == '.'))) {
                val kw = listOf("let", "const", "var").firstOrNull { k -> b.startsWith(k, i) && i + k.length < to && b[i + k.length].isWhitespace() }
                if (kw != null) {
                    i += kw.length
                    while (true) {
                        val name = ident(i) ?: break
                        i = b.indexOf(name, i) + name.length
                        if (name !in RESERVED && kinds.add(name)) vars += name to kw
                        // skip the initializer up to , ; or newline at this depth
                        var dd = 0
                        while (i < to) { val ch = b[i]; if (ch == '{' || ch == '(' || ch == '[') dd++ else if (ch == '}' || ch == ')' || ch == ']') { if (dd == 0) break; dd-- } else if (dd == 0 && (ch == ';' || ch == '\n' || ch == ',')) break; i++ }
                        if (i < to && b[i] == ',') { i++; continue }
                        break
                    }
                    continue
                }
                if (b.startsWith("function", i) && i + 8 < to && (b[i + 8].isWhitespace() || b[i + 8] == '*')) {
                    val name = ident(i + 8)
                    if (name != null) { fns += name; i = b.indexOf(name, i) + name.length; continue }
                }
            }
            i++
        }
        return Scan(vars, fns)
    }

    /**
     * The widest outermost `{ … }` (brace depth only — an IIFE's body sits inside
     * parentheses), as (openIndex, closeIndex).
     */
    private fun largestBlock(b: String): Pair<Int, Int>? {
        var depth = 0; var open = -1; var best: Pair<Int, Int>? = null
        for (i in b.indices) {
            when (b[i]) {
                '{' -> { if (depth == 0) open = i; depth++ }
                '}' -> { depth--; if (depth == 0 && open >= 0) { if (best == null || i - open > best.second - best.first) best = open to i; open = -1 } }
            }
            if (depth < 0) depth = 0
        }
        return best
    }

    /** Comments and string contents replaced by spaces (newlines kept) so braces inside them don't count; same length as [src]. */
    fun blank(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0; val n = src.length
        fun sp(c: Char) = if (c == '\n') '\n' else ' '
        while (i < n) {
            val c = src[i]; val d = if (i + 1 < n) src[i + 1] else ' '
            when {
                c == '/' && d == '/' -> { while (i < n && src[i] != '\n') { out.append(' '); i++ } }
                c == '/' && d == '*' -> { out.append("  "); i += 2; while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) { out.append(sp(src[i])); i++ }; if (i < n) { out.append("  "); i += 2 } }
                c == '"' || c == '\'' || c == '`' -> {
                    out.append(c); i++
                    while (i < n && src[i] != c) {
                        if (src[i] == '\\' && i + 1 < n) { out.append("  "); i += 2; continue }
                        if (c != '`' && src[i] == '\n') break
                        out.append(sp(src[i])); i++
                    }
                    if (i < n) { out.append(src[i]); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
