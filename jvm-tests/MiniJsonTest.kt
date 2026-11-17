package com.splendor.blastbuster.companion

/**
 * JVM tests for MiniJson (DESIGN.md §7.1: 20 tests).
 * Standalone main() runner — no JUnit dependency in the offline sandbox.
 * Run via jvm-tests/run-tests.sh.
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) {
            passed++
            println("PASS: $name")
        } else {
            failed++
            println("FAIL: $name")
        }
    }
    fun expectParseFail(name: String, text: String) {
        try {
            MiniJson.parse(text)
            check(name, false)
        } catch (_: MiniJson.ParseException) {
            check(name, true)
        }
    }

    // 1. String round-trip with quotes, backslash, newline, tab.
    val tricky = "a\"b\\c\nd\te"
    check("string round-trip", MiniJson.parse(MiniJson.string(tricky)) == tricky)
    // 2. Control character escaped as \u00XX.
    check("control char escaped", MiniJson.string("\u0001") == "\"\\u0001\"")
    // 3. Object with every scalar type.
    val obj = MiniJson.obj("s" to "x", "n" to 42L, "b" to true, "z" to null)
    val parsed = MiniJson.parseObj(obj)
    check(
        "object all types",
        parsed != null && parsed["s"] == "x" && parsed["n"] == 42L &&
            parsed["b"] == true && parsed.containsKey("z") && parsed["z"] == null
    )
    // 4. Nested object + array (pre-built fragments nest via Raw, not as strings).
    val nested = MiniJson.obj("a" to MiniJson.Raw(MiniJson.obj("b" to listOf(1L, "two", false))))
    val nestedParsed = MiniJson.parseObj(nested)
    @Suppress("UNCHECKED_CAST")
    val inner = nestedParsed?.get("a") as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val arr = inner?.get("b") as? List<Any?>
    check("nested object/array", arr != null && arr.size == 3 && arr[0] == 1L && arr[1] == "two")
    // 5. Empty object and array.
    check("empty object", MiniJson.parse("{}") == emptyMap<String, Any?>())
    check("empty array", MiniJson.parse("[]") == emptyList<Any?>())
    // 6. Whole numbers parse as Long, fractions as Double.
    check("integer is Long", MiniJson.parse("3") is Long && MiniJson.parse("3") == 3L)
    check("fraction is Double", MiniJson.parse("3.5") is Double && MiniJson.parse("3.5") == 3.5)
    // 7. Literals.
    check("literals", MiniJson.parse("true") == true && MiniJson.parse("false") == false && MiniJson.parse("null") == null)
    // 8. Trailing garbage rejected.
    expectParseFail("trailing garbage rejected", "{\"a\":1} extra")
    // 9. Unterminated string rejected.
    expectParseFail("unterminated string rejected", "\"abc")
    // 10. Bad escape rejected.
    expectParseFail("bad escape rejected", "\"a\\q\"")
    // 11. Single quotes rejected.
    expectParseFail("single quotes rejected", "{'a':1}")
    // 12. Unicode escape decoded.
    check("unicode escape", MiniJson.parse("\"\\u0041\"") == "A")
    // 13. Nested arrays.
    val deep = MiniJson.parse("[[1,2],[3]]")
    @Suppress("UNCHECKED_CAST")
    val deepList = deep as? List<Any?>
    check("nested arrays", deepList != null && deepList.size == 2)
    // 14. Accessor helpers.
    val m = MiniJson.parseObj("{\"s\":\"v\",\"b\":true,\"n\":7}")!!
    check(
        "accessor helpers",
        MiniJson.getString(m, "s") == "v" && MiniJson.getBool(m, "b") == true &&
            MiniJson.getLong(m, "n") == 7L && MiniJson.getString(m, "missing") == null
    )
    // 15. parseObj returns null for non-object / malformed input.
    check("parseObj null cases", MiniJson.parseObj("[1,2]") == null && MiniJson.parseObj("{bad") == null)
    // 16. Whole double writes without a fraction.
    check("whole double writes as int", MiniJson.value(3.0) == "3")
    // 17. NaN refused by the writer.
    try {
        MiniJson.value(Double.NaN)
        check("NaN refused", false)
    } catch (_: IllegalArgumentException) {
        check("NaN refused", true)
    }
    // 18. Empty input rejected.
    expectParseFail("empty input rejected", "")
    // 19. Non-ASCII text survives untouched.
    check("unicode passthrough", MiniJson.parse(MiniJson.string("héllo→")) == "héllo→")
    // 20. Mixed-type array round-trip.
    val mixed = listOf<Any?>(1L, "two", 3.5, true, null)
    check("mixed array round-trip", MiniJson.parse(MiniJson.arr(mixed)) == mixed)

    println("MiniJsonTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
