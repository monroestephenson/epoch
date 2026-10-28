package dev.epoch.hibernate

import kotlin.metadata.KmClassifier
import kotlin.metadata.isNullable
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.TypeReference
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.TypeAnnotationNode
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method

/** Declaration facts only: neither ORM nullability nor constructor initialization proves a type. */
internal object DeclaredNullability {
    private data class Signature(val name: String, val descriptor: String, val field: Boolean)
    private data class Facts(val declarations: Map<Signature, Boolean?>)
    private val facts = object : ClassValue<Facts>() {
        override fun computeValue(type: Class<*>): Facts = inspect(type)
    }

    fun of(member: Member?): Boolean? {
        val signature = when (member) {
            is Field -> {
                if (member.type.isPrimitive) return true
                Signature(member.name, Type.getDescriptor(member.type), true)
            }
            is Method -> {
                if (member.parameterCount != 0) return null
                if (member.returnType.isPrimitive) return true
                Signature(member.name, Type.getMethodDescriptor(member), false)
            }
            else -> return null
        }
        return facts.get(member.declaringClass).declarations[signature]
    }

    private fun inspect(type: Class<*>): Facts {
        val metadata = type.getAnnotation(Metadata::class.java)
        if (metadata != null) {
            // Do not replace unsupported Kotlin types/metadata with emitted Java annotations.
            val parsed = try { KotlinClassMetadata.readLenient(metadata) as? KotlinClassMetadata.Class }
                catch (_: IllegalArgumentException) { null }
            val declarations = linkedMapOf<Signature, Boolean?>()
            parsed?.kmClass?.properties?.forEach { property ->
                val returnType = property.returnType
                val value = if (returnType.flexibleTypeUpperBound != null || returnType.classifier !is KmClassifier.Class) null
                    else !returnType.isNullable
                property.fieldSignature?.let { declarations[Signature(it.name, it.descriptor, true)] = value }
                property.getterSignature?.let { declarations[Signature(it.name, it.descriptor, false)] = value }
            }
            return Facts(declarations)
        }
        val bytes = ClassBytes.read(type) ?: return Facts(emptyMap())
        val node = ClassNode()
        try { ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES) }
        catch (_: IllegalArgumentException) { return Facts(emptyMap()) }
        if (node.name != type.name.replace('.', '/')) return Facts(emptyMap())
        val declarations = linkedMapOf<Signature, Boolean?>()
        node.fields.forEach { field ->
            declarations[Signature(field.name, field.desc, true)] = annotations(
                field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty() +
                    topLevel(field.visibleTypeAnnotations.orEmpty() + field.invisibleTypeAnnotations.orEmpty(), TypeReference.FIELD),
            )
        }
        node.methods.filter { Type.getArgumentTypes(it.desc).isEmpty() }.forEach { method ->
            declarations[Signature(method.name, method.desc, false)] = annotations(
                method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty() +
                    topLevel(method.visibleTypeAnnotations.orEmpty() + method.invisibleTypeAnnotations.orEmpty(), TypeReference.METHOD_RETURN),
            )
        }
        return Facts(declarations)
    }

    private fun topLevel(annotations: List<TypeAnnotationNode>, sort: Int): List<AnnotationNode> = annotations.filter {
        TypeReference(it.typeRef).sort == sort && (it.typePath == null || it.typePath.length == 0)
    }

    private fun annotations(annotations: List<AnnotationNode>): Boolean? = annotations.filter {
        it.desc in nonNull || it.desc in nullable
    }.map { annotation ->
        when (annotation.desc) {
            in nonNull -> {
                // JSR-305 @Nonnull(when=MAYBE/NEVER/UNKNOWN) is not an unconditional non-null declaration.
                val values = annotation.values.orEmpty().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
                val whenValue = (values["when"] as? Array<*>)?.getOrNull(1)?.toString()
                when (whenValue) {
                    null, "ALWAYS" -> true
                    "MAYBE", "NEVER" -> false
                    else -> null
                }
            }
            in nullable -> false
            else -> null
        }
    }.distinct().singleOrNull()

    private val nonNull = setOf(
        "Lorg/jetbrains/annotations/NotNull;", "Ljavax/annotation/Nonnull;", "Ljakarta/annotation/Nonnull;",
        "Lorg/jspecify/annotations/NonNull;", "Lorg/eclipse/jdt/annotation/NonNull;",
        "Ledu/umd/cs/findbugs/annotations/NonNull;", "Lorg/springframework/lang/NonNull;", "Llombok/NonNull;",
    )
    private val nullable = setOf(
        "Lorg/jetbrains/annotations/Nullable;", "Ljavax/annotation/Nullable;", "Ljavax/annotation/CheckForNull;",
        "Ljakarta/annotation/Nullable;", "Ljakarta/annotation/CheckForNull;", "Lorg/jspecify/annotations/Nullable;",
        "Lorg/eclipse/jdt/annotation/Nullable;", "Ledu/umd/cs/findbugs/annotations/Nullable;",
        "Ledu/umd/cs/findbugs/annotations/CheckForNull;", "Lorg/springframework/lang/Nullable;",
    )
}
