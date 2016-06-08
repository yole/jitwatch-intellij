package ru.yole.jitwatch

import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.BytecodeInstruction
import org.adoptopenjdk.jitwatch.model.bytecode.MemberBytecode

class BytecodeTextBuilder(val metaClass: MetaClass) {
    private class MemberBytecodeMap(val startLine: Int) {
        val instructionToLineMap = mutableMapOf<BytecodeInstruction, Int>()
    }

    private val builder = StringBuilder()
    private var currentLine = 0
    private val memberIndex = mutableMapOf<IMetaMember, MemberBytecodeMap>()

    init {
        for (member in metaClass.metaMembers) {
            val bytecodeMap = MemberBytecodeMap(currentLine)
            memberIndex[member] = bytecodeMap
            appendLine(member.memberBytecode.memberSignatureParts.toStringSingleLine())
            appendBytecode(member.memberBytecode, bytecodeMap)
        }
    }

    val text: String
        get() = builder.toString()

    private fun appendLine(text: String) {
        builder.append(text).append("\n")
        currentLine++
    }

    private fun appendBytecode(memberBC: MemberBytecode, bytecodeMap: MemberBytecodeMap) {
        val maxOffset = memberBC.instructions.lastOrNull()?.offset ?: 0
        for (instruction in memberBC.instructions) {
            bytecodeMap.instructionToLineMap[instruction] = currentLine
            for (line in 0 until instruction.labelLines.coerceAtLeast(1)) {
                appendLine("    " + instruction.toString(maxOffset, line))
            }
        }
    }

    fun findLine(member: IMetaMember, bytecodeOffset: Int): Int? {
        val instruction = member.memberBytecode.instructions.firstOrNull { it.offset >= bytecodeOffset } ?: return null
        return memberIndex[member]?.instructionToLineMap?.get(instruction)
    }
}
