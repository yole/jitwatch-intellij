package ru.yole.jitwatch

import org.adoptopenjdk.jitwatch.model.bytecode.ClassBC
import org.adoptopenjdk.jitwatch.model.bytecode.MemberBytecode

class BytecodeTextBuilder(val classBC: ClassBC) {
    private val builder = StringBuilder()

    init {
        for (memberBC in classBC.memberBytecodeList) {
            appendLine(memberBC.memberSignatureParts.toStringSingleLine())
            appendBytecode(memberBC)
        }
    }

    val text: String
        get() = builder.toString()

    private fun appendLine(text: String) {
        builder.append(text).append("\n")
    }

    private fun appendBytecode(memberBC: MemberBytecode) {
        val maxOffset = memberBC.instructions.lastOrNull()?.offset ?: 0
        for (instruction in memberBC.instructions) {
            for (line in 0 until instruction.labelLines.coerceAtLeast(1)) {
                appendLine("    " + instruction.toString(maxOffset, line))
            }
        }
    }
}
