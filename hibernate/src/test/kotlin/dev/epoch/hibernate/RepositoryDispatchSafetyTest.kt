package dev.epoch.hibernate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.epoch.core.AccessPathStatus
import dev.epoch.repositoryfixture.RepositoryCustomer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.*
import java.net.URLClassLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class RepositoryDispatchSafetyTest {
    @TempDir lateinit var directory: Path

    @Test fun `application EntityManager override cannot publish rewritten query as static input`() {
        val manager = "audit/CustomManager"
        write(manager, ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, manager, null, "java/lang/Object", arrayOf("jakarta/persistence/EntityManager"))
            visitMethod(Opcodes.ACC_PUBLIC, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", null, null).apply {
                visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 2); visitEnd()
            }
            visitEnd()
        })
        write("audit/Caller", caller(manager))
        val result = discover()
        val paths = result.accessPaths.filter { it.className == "audit.Caller" }
        assertEquals(2, paths.size, result.toString())
        assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED }, paths.toString())
        assertTrue(result.hqlQueries.isEmpty(), result.hqlQueries.toString())
        assertTrue(paths.any { it.reason?.contains("override", ignoreCase = true) == true }, paths.toString())
    }

    @Test fun `standard JPA interface factory and execution remain recognized`() {
        write("audit/Caller", caller("jakarta/persistence/EntityManager"))
        val result = discover()
        val paths = result.accessPaths.filter { it.className == "audit.Caller" }
        assertEquals(2, paths.size, result.toString())
        assertTrue(paths.all { it.status == AccessPathStatus.STATIC }, paths.toString())
        assertEquals(1, result.hqlQueries.size)
    }

    @Test fun `query array alias is unresolved because later alias mutation is not tracked`() {
        write("audit/ArrayAlias", ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/ArrayAlias", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(Ljakarta/persistence/EntityManager;)V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitLdcInsn("from Customer")
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
                visitVarInsn(Opcodes.ASTORE, 1)
                visitInsn(Opcodes.ICONST_1); visitTypeInsn(Opcodes.ANEWARRAY, "jakarta/persistence/Query"); visitVarInsn(Opcodes.ASTORE, 2)
                visitVarInsn(Opcodes.ALOAD, 2); visitInsn(Opcodes.ICONST_0); visitVarInsn(Opcodes.ALOAD, 1); visitInsn(Opcodes.AASTORE)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/Query", "getResultList", "()Ljava/util/List;", true)
                visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(3, 3); visitEnd()
            }; visitEnd()
        })
        val result = discover()
        assertEquals(2, result.accessPaths.size, result.toString())
        assertTrue(result.accessPaths.all { it.status == AccessPathStatus.UNRESOLVED }, result.accessPaths.toString())
        assertTrue(result.hqlQueries.isEmpty(), result.hqlQueries.toString())
    }

    @Test fun `query captured by application lambda is unresolved because its alias effects are untracked`() {
        val owner = "audit/LambdaAlias"
        write(owner, ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "use", "(Ljakarta/persistence/Query;)V", null, null).apply {
                visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 1); visitEnd()
            }
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(Ljakarta/persistence/EntityManager;)V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitLdcInsn("from Customer")
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
                visitVarInsn(Opcodes.ASTORE, 1); visitVarInsn(Opcodes.ALOAD, 1)
                visitInvokeDynamicInsn("run", "(Ljakarta/persistence/Query;)Ljava/lang/Runnable;",
                    Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
                    Type.getMethodType("()V"), Handle(Opcodes.H_INVOKESTATIC, owner, "use", "(Ljakarta/persistence/Query;)V", false), Type.getMethodType("()V"))
                visitInsn(Opcodes.POP); visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/Query", "getResultList", "()Ljava/util/List;", true)
                visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(2, 2); visitEnd()
            }; visitEnd()
        })
        val result = discover()
        assertTrue(result.accessPaths.size >= 2, result.toString())
        assertTrue(result.accessPaths.all { it.status == AccessPathStatus.UNRESOLVED }, result.accessPaths.toString())
        assertTrue(result.hqlQueries.isEmpty(), result.hqlQueries.toString())
    }

    @Test fun `application query modifier override remains unresolved instead of shape neutral binding`() {
        val query = "audit/CustomQuery"
        write(query, ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, query, null, "java/lang/Object", arrayOf("jakarta/persistence/Query"))
            visitMethod(Opcodes.ACC_PUBLIC, "setParameter", "(Ljava/lang/String;Ljava/lang/Object;)Ljakarta/persistence/Query;", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitInsn(Opcodes.ARETURN); visitMaxs(1, 3); visitEnd()
            }
            visitEnd()
        })
        write("audit/Caller", ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/Caller", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(L$query;)V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitLdcInsn("email"); visitLdcInsn("value")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, query, "setParameter", "(Ljava/lang/String;Ljava/lang/Object;)Ljakarta/persistence/Query;", false)
                visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(3, 1); visitEnd()
            }; visitEnd()
        })
        val result = discover()
        val paths = result.accessPaths.filter { it.className == "audit.Caller" }
        assertEquals(1, paths.size, result.toString())
        assertEquals(AccessPathStatus.UNRESOLVED, paths.single().status)
    }

    @Test fun `failed receiver data flow marks inventory denominator incomplete`() {
        write("audit/Broken", ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/Broken", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "broken", "()V", null, null).apply {
                visitCode(); visitInsn(Opcodes.POP)
                visitInsn(Opcodes.ACONST_NULL); visitLdcInsn("from Customer")
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
                visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(2, 0); visitEnd()
            }; visitEnd()
        })
        val result = discover()
        val inventory = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(result).path("apiInventory")
        assertFalse(inventory.isMissingNode)
        assertFalse(inventory.path("completeWithinDeclaredScope").asBoolean(), inventory.toString())
        assertTrue(inventory.path("failedSources").any { it.asText().contains("#broken()V:analysis") }, inventory.toString())
        assertTrue(result.unresolved.any { it.id.contains("analysis") || it.reason.contains("data flow") }, result.toString())
    }

    @Test fun `unknown framework method declaration keeps dispatch and denominator unresolved`() {
        write("audit/UnknownCall", ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/UnknownCall", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(Ljakarta/persistence/EntityManager;)V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitLdcInsn("from Customer"); visitInsn(Opcodes.ICONST_0)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery", "(Ljava/lang/String;I)Ljakarta/persistence/Query;", true)
                visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(3, 1); visitEnd()
            }; visitEnd()
        })
        val result = discover()
        val inventory = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(result).path("apiInventory")
        assertFalse(inventory.path("completeWithinDeclaredScope").asBoolean(), inventory.toString())
        assertTrue(result.hqlQueries.isEmpty())
        assertEquals(AccessPathStatus.UNRESOLVED, result.accessPaths.single().status)
    }

    @Test fun `child repository field hiding parent uses child generic entity binding`() {
        val repository = "io/quarkus/hibernate/orm/panache/PanacheRepository"
        val query = "io/quarkus/hibernate/orm/panache/PanacheQuery"
        val entity = RepositoryCustomer::class.java.name.replace('.', '/')
        write("audit/ABase", ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/ABase", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC, "repository", "L$repository;", "L$repository<Ljava/lang/String;>;", null).visitEnd()
            visitEnd()
        })
        write("audit/ZChild", ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/ZChild", null, "audit/ABase", null)
            visitField(Opcodes.ACC_PUBLIC, "repository", "L$repository;", "L$repository<L$entity;>;", null).visitEnd()
            visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(Opcodes.GETFIELD, "audit/ZChild", "repository", "L$repository;")
                visitLdcInsn("email is not null"); visitInsn(Opcodes.ICONST_0); visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                visitMethodInsn(Opcodes.INVOKEINTERFACE, repository, "find", "(Ljava/lang/String;[Ljava/lang/Object;)L$query;", true)
                visitMethodInsn(Opcodes.INVOKEINTERFACE, query, "list", "()Ljava/util/List;", true)
                visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(3, 1); visitEnd()
            }; visitEnd()
        })
        val runtime = requireNotNull(System.getProperty("epoch.panache.classpath")).split(File.pathSeparator)
        URLClassLoader((runtime.map { Path.of(it).toUri().toURL() } + directory.toUri().toURL()).toTypedArray(), javaClass.classLoader).use { loader ->
            val result = RepositoryDiscovery.discover(listOf(directory.toString()), listOf("audit"), loader)
            assertEquals(1, result.hqlQueries.size, result.toString())
            assertTrue(result.hqlQueries.values.single().contains("RepositoryCustomer"), result.hqlQueries.toString())
            assertTrue(result.accessPaths.all { it.status == AccessPathStatus.STATIC }, result.accessPaths.toString())
        }
    }

    @Test fun `merged receiver alternatives retain a visible application override`() {
        val manager = "audit/MergedCustomManager"
        writeManager(manager, overrideQuery = true)
        write("audit/MergedCaller", mergedCaller(manager))
        val result = discover()
        val paths = result.accessPaths.filter { it.className == "audit.MergedCaller" }
        assertEquals(2, paths.size, result.toString())
        assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED }, paths.toString())
        assertTrue(paths.any { it.reason?.contains("Receiver alternatives") == true }, paths.toString())
        assertTrue(result.hqlQueries.keys.none { it.startsWith("audit.MergedCaller#") }, result.hqlQueries.toString())
    }

    @Test fun `merged receivers that inherit identical framework dispatch retain their supported query`() {
        val manager = "audit/MergedInheritedManager"
        writeManager(manager, overrideQuery = false)
        write("audit/MergedCaller", mergedCaller(manager))
        val result = discover()
        val paths = result.accessPaths.filter { it.className == "audit.MergedCaller" }
        assertEquals(2, paths.size, result.toString())
        assertTrue(paths.all { it.status == AccessPathStatus.STATIC }, paths.toString())
        assertEquals(1, result.hqlQueries.keys.count { it.startsWith("audit.MergedCaller#") })
    }

    @Test fun `merged receivers belonging to different database families remain explicitly unresolved`() {
        val manager = "audit/MultiFamilyManager"
        writeManager(manager, overrideQuery = false, extraInterface = "io/quarkus/hibernate/orm/panache/PanacheRepository")
        write("audit/MergedCaller", mergedCaller(manager))
        val runtime = requireNotNull(System.getProperty("epoch.panache.classpath")).split(File.pathSeparator)
        URLClassLoader((runtime.map { Path.of(it).toUri().toURL() } + directory.toUri().toURL()).toTypedArray(), javaClass.classLoader).use { loader ->
            val result = RepositoryDiscovery.discover(listOf(directory.toString()), listOf("audit"), loader)
            val paths = result.accessPaths.filter { it.className == "audit.MergedCaller" }
            assertEquals(2, paths.size, result.toString())
            assertTrue(paths.all { it.status == AccessPathStatus.UNRESOLVED }, paths.toString())
            assertTrue(paths.any { it.reason?.contains("Receiver alternatives") == true }, paths.toString())
            assertTrue(result.hqlQueries.keys.none { it.startsWith("audit.MergedCaller#") }, result.hqlQueries.toString())
        }
    }

    private fun writeManager(name: String, overrideQuery: Boolean, extraInterface: String? = null) = write(name, ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object",
            listOfNotNull("jakarta/persistence/EntityManager", extraInterface).toTypedArray())
        if (overrideQuery) visitMethod(Opcodes.ACC_PUBLIC, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", null, null).apply {
            visitCode(); visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN); visitMaxs(1, 2); visitEnd()
        }
        visitEnd()
    })

    private fun mergedCaller(manager: String) = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/MergedCaller", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(Ljakarta/persistence/EntityManager;L$manager;Z)V", null, null).apply {
            val custom = Label()
            val joined = Label()
            visitCode(); visitVarInsn(Opcodes.ILOAD, 2); visitJumpInsn(Opcodes.IFEQ, custom)
            visitVarInsn(Opcodes.ALOAD, 0); visitJumpInsn(Opcodes.GOTO, joined)
            visitLabel(custom); visitFrame(Opcodes.F_SAME, 0, null, 0, null); visitVarInsn(Opcodes.ALOAD, 1)
            visitLabel(joined); visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf("jakarta/persistence/EntityManager"))
            visitLdcInsn("from Customer")
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/EntityManager", "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", true)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/Query", "getResultList", "()Ljava/util/List;", true)
            visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(2, 3); visitEnd()
        }
        visitEnd()
    }

    private fun caller(manager: String) = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "audit/Caller", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "(L$manager;)V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitLdcInsn("from Customer")
            val api = manager == "jakarta/persistence/EntityManager"
            visitMethodInsn(if (api) Opcodes.INVOKEINTERFACE else Opcodes.INVOKEVIRTUAL, manager, "createQuery", "(Ljava/lang/String;)Ljakarta/persistence/Query;", api)
            visitMethodInsn(Opcodes.INVOKEINTERFACE, "jakarta/persistence/Query", "getResultList", "()Ljava/util/List;", true)
            visitInsn(Opcodes.POP); visitInsn(Opcodes.RETURN); visitMaxs(2, 1); visitEnd()
        }; visitEnd()
    }
    private fun write(name: String, writer: ClassWriter) {
        val path = directory.resolve("$name.class")
        Files.createDirectories(path.parent); Files.write(path, writer.toByteArray())
    }
    private fun discover() = URLClassLoader(arrayOf(directory.toUri().toURL()), javaClass.classLoader).use {
        RepositoryDiscovery.discover(listOf(directory.toString()), listOf("audit"), it)
    }
}
