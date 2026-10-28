package dev.epoch.hibernate

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import org.objectweb.asm.tree.ClassNode

internal sealed interface RepositoryType {
    data class ClassType(val name: String, val arguments: List<RepositoryType> = emptyList()) : RepositoryType
    data class Variable(val name: String) : RepositoryType
    data class Unknown(val reason: String) : RepositoryType
}
internal data class RepositorySignature(val formals: List<String>, val parents: List<RepositoryType>)
internal data class RepositoryDomain(val className: String?, val reasons: List<String>)

internal class RepositoryDomains(private val headers: RepositoryHeaders) {
    private val signatures = mutableMapOf<String, RepositorySignature>()
    private val markers = setOf("io/quarkus/hibernate/orm/panache/PanacheRepository",
        "io/quarkus/hibernate/orm/panache/PanacheRepositoryBase", "org/springframework/data/repository/Repository")

    fun resolve(receiver: RepositoryType.ClassType): RepositoryDomain {
        val results = sortedSetOf<String>()
        val reasons = sortedSetOf<String>()
        val pending = ArrayDeque<Pair<RepositoryType.ClassType, List<String>>>()
        pending += receiver to emptyList()
        var visited = 0
        while (pending.isNotEmpty()) {
            val (type, path) = pending.removeFirst()
            if (++visited > 256 || path.size >= 64) { reasons += "Generic ancestry bound exceeded"; break }
            if (type.name in path) { reasons += "Cyclic generic ancestry at ${type.name}"; continue }
            if (type.name in markers) {
                val entity = type.arguments.firstOrNull()
                if (entity is RepositoryType.ClassType) results += entity.name
                else reasons += "Entity argument at ${type.name} is erased, wildcard or unresolved: $entity"
                continue
            }
            val header = headers.read(type.name)
            if (header.node == null) { reasons += "${type.name}: ${header.reason}"; continue }
            val signature = signatures.getOrPut(type.name) { parse(header.node) }
            val bindings = signature.formals.mapIndexed { index, name -> name to
                (type.arguments.getOrNull(index) ?: RepositoryType.Variable(name)) }.toMap()
            signature.parents.forEach { parent ->
                when (val bound = substitute(parent, bindings)) {
                    is RepositoryType.ClassType -> pending += bound to (path + type.name)
                    else -> reasons += "Unresolved generic parent of ${type.name}: $bound"
                }
            }
        }
        if (results.size != 1) reasons += "No unique entity argument at a supported repository API marker: ${results.joinToString()}"
        return RepositoryDomain(results.singleOrNull().takeIf { reasons.isEmpty() }, reasons.toList())
    }

    private fun substitute(type: RepositoryType, bindings: Map<String, RepositoryType>): RepositoryType = when (type) {
        is RepositoryType.ClassType -> type.copy(arguments = type.arguments.map { substitute(it, bindings) })
        is RepositoryType.Variable -> bindings[type.name] ?: type
        is RepositoryType.Unknown -> type
    }

    private fun parse(node: ClassNode): RepositorySignature {
        if (node.signature == null) return RepositorySignature(emptyList(),
            (listOfNotNull(node.superName) + node.interfaces.orEmpty()).map { RepositoryType.ClassType(it) })
        val formals = mutableListOf<String>()
        val parents = mutableListOf<RepositoryType>()
        SignatureReader(node.signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
            override fun visitFormalTypeParameter(name: String) { formals += name }
            override fun visitClassBound() = object : SignatureVisitor(Opcodes.ASM9) {}
            override fun visitInterfaceBound() = object : SignatureVisitor(Opcodes.ASM9) {}
            private fun parent() = TypeVisitor { parents += it }
            override fun visitSuperclass() = parent()
            override fun visitInterface() = parent()
        })
        return RepositorySignature(formals, parents)
    }

    companion object {
        fun descriptor(type: Type?): Set<RepositoryType.ClassType>? = type?.takeIf { it.sort == Type.OBJECT && it.internalName != "null" }
            ?.let { setOf(RepositoryType.ClassType(it.internalName)) }
        fun fieldType(signature: String?, fieldDescriptor: String): Set<RepositoryType.ClassType>? {
            if (signature == null) return descriptor(Type.getType(fieldDescriptor))
            var result: RepositoryType? = null
            SignatureReader(signature).acceptType(TypeVisitor { result = it })
            return (result as? RepositoryType.ClassType)?.let(::setOf)
        }
        fun parameterTypes(signature: String?): List<RepositoryType>? {
            if (signature == null) return null
            val result = mutableListOf<RepositoryType>()
            SignatureReader(signature).accept(object : SignatureVisitor(Opcodes.ASM9) {
                override fun visitParameterType(): SignatureVisitor = TypeVisitor { result += it }
            })
            return result
        }
    }

    private class TypeVisitor(private val complete: (RepositoryType) -> Unit) : SignatureVisitor(Opcodes.ASM9) {
        private var name: String? = null
        private var inner = false
        private val arguments = mutableListOf<RepositoryType>()
        override fun visitClassType(name: String) { this.name = name }
        override fun visitInnerClassType(name: String) { this.name = this.name + "\$" + name; inner = true }
        override fun visitTypeVariable(name: String) = complete(RepositoryType.Variable(name))
        override fun visitBaseType(descriptor: Char) = complete(RepositoryType.Unknown("Primitive type $descriptor"))
        override fun visitArrayType(): SignatureVisitor = TypeVisitor { complete(RepositoryType.Unknown("Array domain $it")) }
        override fun visitTypeArgument() { arguments += RepositoryType.Unknown("Unbounded wildcard") }
        override fun visitTypeArgument(wildcard: Char): SignatureVisitor = TypeVisitor {
            arguments += if (wildcard == '=') it else RepositoryType.Unknown("Variant generic argument $wildcard $it")
        }
        override fun visitEnd() { complete(if (inner && arguments.isNotEmpty()) RepositoryType.Unknown("Parameterized inner class binding is unsupported")
            else RepositoryType.ClassType(requireNotNull(name), arguments.toList())) }
    }
}
