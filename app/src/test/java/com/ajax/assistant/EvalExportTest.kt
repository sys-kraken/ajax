package com.ajax.assistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Builds the CI model check the way the app works: phrases the regex router handles on its own are
 * scored here; only the rest are exported (exact prefix, suffix and grammar the phone uses) for the
 * host tool `ajax_eval`, which runs the real model on them.
 *
 * Outputs build/eval/<set>/: router.txt (scored lines + "ROUTER pass=N total=M"), cases.tsv,
 * prefix.txt, grammar.gbnf, suffix_<id>.txt. Also checks every expected answer routes.
 */
class EvalExportTest {
    private val c = IntentModel.Catalog

    /** Open-app / app-info depend on what is installed, so the eval treats them as model-bound. */
    private fun routerHandles(m: CommandRouter.Match?) = m != null && !m.hintOnly && m.id != "open-app" && m.id != "app-info"

    private fun export(set: String, resource: String): Int {
        val src = javaClass.classLoader!!.getResource(resource)!!.readText()
        val out = File("build/eval/$set").apply { deleteRecursively(); mkdirs() }
        File(out, "prefix.txt").writeText(c.prefix)
        File(out, "grammar.gbnf").writeText(c.grammar)
        val cases = StringBuilder()
        val router = StringBuilder()
        var n = 0
        var routed = 0
        var routedPass = 0
        for (line in src.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val cols = line.split('\t')
            assertTrue("bad line: $line", cols.size == 2)
            val (request, answers) = cols
            val wanted = answers.split('|')
            for (a in wanted) if (a != "none") assertNotNull("router rejects expected answer '$a'", CommandRouter.route(a))

            val hit = CommandRouter.route(request)
            if (routerHandles(hit)) {
                routed++
                val ok = wanted.any { it != "none" && CommandRouter.route(it)?.id == hit!!.id }
                if (ok) routedPass++
                router.append(if (ok) "PASS" else "FAIL").append("  [rules] ").append(request)
                    .append(" -> ").append(hit!!.id).append(" (want ").append(answers).append(")\n")
                continue
            }
            val id = "%03d".format(n++)
            File(out, "suffix_$id.txt").writeText(c.suffix(CommandRouter.normalize(request)))
            cases.append(id).append('\t').append(request).append('\t').append(answers).append('\n')
        }
        router.append("ROUTER pass=").append(routedPass).append(" total=").append(routed).append('\n')
        File(out, "router.txt").writeText(router.toString())
        File(out, "cases.tsv").writeText(cases.toString())
        return routed + n
    }

    @Test fun exportEvalSets() {
        assertTrue("main eval set too small", export("main", "intent_eval.tsv") >= 50)
        assertTrue("holdout set too small", export("holdout", "intent_eval_holdout.tsv") >= 25)
    }
}
