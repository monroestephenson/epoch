package dev.epoch.hibernate

import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder
import org.hibernate.boot.registry.StandardServiceRegistry
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService
import org.hibernate.boot.registry.classloading.spi.ClassLoadingException
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver
import org.hibernate.engine.jdbc.dialect.spi.DialectResolver
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.MalformedURLException
import java.net.URL
import java.util.ServiceLoader

/** Hibernate's default aggregator also consults the tool's system loader, outside the application runtime. */
internal class ExtractionClassLoaderService(private val loader: ClassLoader) : ClassLoaderService {
    @Suppress("UNCHECKED_CAST")
    override fun <T> classForName(className: String): Class<T> = try {
        Class.forName(className, true, loader) as Class<T>
    } catch (failure: ClassNotFoundException) {
        throw ClassLoadingException("Unable to load application class $className", failure)
    } catch (failure: LinkageError) {
        throw ClassLoadingException("Unable to link application class $className", failure)
    }

    override fun locateResource(name: String): URL? = try { URL(name) }
        catch (_: MalformedURLException) { loader.getResource(resourceName(name)) }

    override fun locateResourceStream(name: String): InputStream? = try { locateResource(name)?.openStream() }
        catch (_: IOException) { null }

    override fun locateResources(name: String): List<URL> = loader.getResources(resourceName(name)).asSequence().distinct().toList()

    override fun <S> loadJavaServices(serviceContract: Class<S>): Collection<S> =
        ServiceLoader.load(serviceContract, loader).toList()

    @Suppress("UNCHECKED_CAST")
    override fun <T> generateProxy(handler: InvocationHandler, vararg interfaces: Class<*>): T =
        Proxy.newProxyInstance(loader, interfaces, handler) as T

    override fun packageForNameOrNull(packageName: String): Package? = try {
        Class.forName("$packageName.package-info", true, loader).`package`
    } catch (_: ClassNotFoundException) { null }
      catch (_: LinkageError) { null }

    override fun <T> workWithClassLoader(work: ClassLoaderService.Work<T>): T = work.doWork(loader)

    // The extraction owns and closes its loader after all registries and the SessionFactory have stopped.
    override fun stop() = Unit

    private fun resourceName(name: String) = name.removePrefix("classpath:").removePrefix("/")

    companion object {
        fun registry(settings: Map<String, Any>): StandardServiceRegistry {
            val loader = Thread.currentThread().contextClassLoader ?: ExtractionClassLoaderService::class.java.classLoader
            val bootstrap = BootstrapServiceRegistryBuilder().applyClassLoaderService(ExtractionClassLoaderService(loader)).build()
            return try {
                StandardServiceRegistryBuilder(bootstrap).applySettings(settings)
                    // Offline product facts must not enable application resolver callbacks via ServiceLoader.
                    .addService(DialectResolver::class.java, StandardDialectResolver())
                    .build()
            }
            catch (failure: Throwable) {
                BootstrapServiceRegistryBuilder.destroy(bootstrap)
                throw failure
            }
        }
    }
}
