package ru.yole.jitwatch

import org.adoptopenjdk.jitwatch.model.IMetaMember
import org.adoptopenjdk.jitwatch.model.MetaClass
import org.adoptopenjdk.jitwatch.model.bytecode.BytecodeInstruction
import org.adoptopenjdk.jitwatch.model.bytecode.MemberBytecode

class BytecodeTextBuilder() {
    private class MemberBytecodeMap(val startLine: Int) {
        val instructionToLineMap = mutableMapOf<BytecodeInstruction, Int>()
    }

    private val builder = StringBuilder()
    private var currentLine = 0
    private val memberIndex = mutableMapOf<IMetaMember, MemberBytecodeMap>()

    fun appendClass(metaClass: MetaClass) {
        for (member in metaClass.metaMembers) {
            val bytecodeMap = MemberBytecodeMap(currentLine)
            memberIndex[member] = bytecodeMap

            val memberBytecode = member.memberBytecode
            appendLine(member.toStringUnqualifiedMethodName(false))
            if (memberBytecode == null) {
                appendLine("NO BYTECODE FOUND")
            }
            else {
                appendBytecode(memberBytecode, bytecodeMap)
            }
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

    fun findLine(member: IMetaMember): Int? {
        return memberIndex[member]?.startLine
    }

    fun findLine(member: IMetaMember, bytecodeOffset: Int): Int? {
        val instruction = member.memberBytecode.instructions.firstOrNull { it.offset >= bytecodeOffset } ?: return null
        return memberIndex[member]?.instructionToLineMap?.get(instruction)
    }
}
