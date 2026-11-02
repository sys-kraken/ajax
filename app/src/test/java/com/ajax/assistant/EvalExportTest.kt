package com.ajax.assistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the model test set for CI (build/eval): the exact prefix, suffixes and grammar the phone
 * uses. The host tool `ajax_eval` then runs the real model on them. Also checks every expected
 * answer is a command the router accepts, so the test set can't drift from the app.
 */
class EvalExportTest {
    @Test fun exportEvalSet() {
        val src = javaClass.classLoader!!.getResource("intent_eval.tsv")!!.readText()
        val out = File("build/eval").apply { deleteRecursively(); mkdirs() }
        val c = IntentModel.Catalog
        File(out, "prefix.txt").writeText(c.prefix)
        File(out, "grammar.gbnf").writeText(c.grammar)
        val cases = StringBuilder()
        var n = 0
        for (line in src.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val (request, answers) = line.split('\t').also { assertTrue("bad line: $line", it.size == 2) }
            for (a in answers.split('|')) {
                if (a != "none") assertNotNull("router rejects expected answer '$a'", CommandRouter.route(a))
            }
            val id = "%03d".format(n++)
            File(out, "suffix_$id.txt").writeText(c.suffix(CommandRouter.normalize(request)))
            cases.append(id).append('\t').append(request).append('\t').append(answers).append('\n')
        }
        File(out, "cases.tsv").writeText(cases.toString())
        assertTrue("eval set too small", n >= 50)
    }
}
