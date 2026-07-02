/*
 * Copyright 2002-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package moe.kurenai.multidbdemo

import jakarta.persistence.*
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.springframework.lang.Nullable
import org.springframework.orm.jpa.EntityManagerFactoryInfo
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.orm.jpa.EntityManagerProxy
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.CollectionUtils
import org.springframework.util.ConcurrentReferenceHashMap
import java.io.IOException
import java.io.ObjectInputStream
import java.io.Serializable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.concurrent.Volatile
import kotlin.jvm.Transient
import kotlin.jvm.java
import kotlin.jvm.javaClass

/**
 * Delegate for creating a shareable JPA [EntityManager]
 * reference for a given [EntityManagerFactory].
 * 
 * 
 * A shared EntityManager will behave just like an EntityManager fetched from
 * an application server's JNDI environment, as defined by the JPA specification.
 * It will delegate all calls to the current transactional EntityManager, if any;
 * otherwise it will fall back to a newly created EntityManager per operation.
 * 
 * 
 * For a behavioral definition of such a shared transactional EntityManager,
 * see [jakarta.persistence.PersistenceContextType.TRANSACTION] and its
 * discussion in the JPA spec document. This is also the default being used
 * for the annotation-based [jakarta.persistence.PersistenceContext.type].
 * 
 * @author Juergen Hoeller
 * @author Rod Johnson
 * @author Oliver Gierke
 * @author Mark Paluch
 * @author Sam Brannen
 * @since 2.0
 * @see jakarta.persistence.PersistenceContext
 * 
 * @see jakarta.persistence.PersistenceContextType.TRANSACTION
 * 
 * @see JpaTransactionManager
 * 
 * @see ExtendedEntityManagerCreator
 */
object TenantEntityManagerCreator {
    private val NO_ENTITY_MANAGER_INTERFACES = arrayOfNulls<Class<*>>(0)

    private val cachedQueryInterfaces: MutableMap<Class<*>, Array<Class<*>?>> =
        ConcurrentReferenceHashMap(4)

    private val transactionRequiringMethods = mutableSetOf<String?>(
        "joinTransaction",
        "flush",
        "persist",
        "merge",
        "remove",
        "refresh"
    )

    private val queryTerminatingMethods = mutableSetOf<String?>(
        "execute",  // jakarta.persistence.StoredProcedureQuery.execute()
        "executeUpdate",  // jakarta.persistence.Query.executeUpdate()
        "getSingleResult",  // jakarta.persistence.Query.getSingleResult()
        "getSingleResultOrNull",  // jakarta.persistence.Query.getSingleResultOrNull()
        "getResultStream",  // jakarta.persistence.Query.getResultStream()
        "getResultList",  // jakarta.persistence.Query.getResultList()
        "list",  // org.hibernate.query.Query.list()
        "scroll",  // org.hibernate.query.Query.scroll()
        "stream",  // org.hibernate.query.Query.stream()
        "uniqueResult",  // org.hibernate.query.Query.uniqueResult()
        "uniqueResultOptional" // org.hibernate.query.Query.uniqueResultOptional()
    )

    fun createTenantEntityManager(@Nullable properties: MutableMap<*, *>?, vararg entityManagerInterfaces: Class<*>?): EntityManager {
        return createTenantEntityManager(properties, true, *entityManagerInterfaces)
    }

    fun createTenantEntityManager(
        @Nullable properties: MutableMap<*, *>?,
        synchronizedWithTransaction: Boolean, vararg entityManagerInterfaces: Class<*>?
    ): EntityManager {
        val ifcs = arrayOfNulls<Class<*>>(entityManagerInterfaces.size + 1)
        System.arraycopy(entityManagerInterfaces, 0, ifcs, 0, entityManagerInterfaces.size)
        ifcs[entityManagerInterfaces.size] = EntityManagerProxy::class.java
        return Proxy.newProxyInstance(
            TenantEntityManagerCreator::class.java.classLoader,
            ifcs, TenantEntityManagerInvocationHandler(properties, synchronizedWithTransaction)
        ) as EntityManager
    }


    /**
     * Invocation handler that delegates all calls to the current
     * transactional EntityManager, if any; else, it will fall back
     * to a newly created EntityManager per operation.
     */
    private class TenantEntityManagerInvocationHandler(
        @field:Nullable @param:Nullable private val properties: MutableMap<*, *>?,
        private val synchronizedWithTransaction: Boolean
    ) : InvocationHandler, Serializable {
        @Nullable
        @Volatile
        @Transient
        private var proxyClassLoader: ClassLoader? = null

        init {
            initProxyClassLoader()
        }

        fun initProxyClassLoader() {
            val targetFactory = TenantContext.tenantEmf.nativeEntityManagerFactory
            if (targetFactory is EntityManagerFactoryInfo) {
                this.proxyClassLoader = targetFactory.beanClassLoader
            } else {
                this.proxyClassLoader = targetFactory.javaClass.classLoader
            }
        }

        @Nullable
        @Throws(Throwable::class)
        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            // Invocation on EntityManager interface coming in...

            val targetFactory = TenantContext.tenantEmf.nativeEntityManagerFactory

            when (method.name) {
                "equals" -> {
                    // Only consider equal when proxies are identical.
                    return (proxy === args!![0])
                }

                "hashCode" -> {
                    // Use hashCode of EntityManager proxy.
                    return hashCode()
                }

                "toString" -> {
                    // Deliver toString without touching a target EntityManager.
                    return "Tenant EntityManager proxy for target factory [" + targetFactory + "]"
                }

                "getEntityManagerFactory" -> {
                    // JPA 2.0: return EntityManagerFactory without creating an EntityManager.
                    return targetFactory
                }

                "getCriteriaBuilder", "getMetamodel" -> {
                    // JPA 2.0: return EntityManagerFactory's CriteriaBuilder/Metamodel (avoid creation of EntityManager)
                    try {
                        return EntityManagerFactory::class.java.getMethod(method.getName()).invoke(targetFactory)
                    } catch (ex: InvocationTargetException) {
                        throw ex.getTargetException()
                    }
                }

                "unwrap" -> {
                    // JPA 2.0: handle unwrap method - could be a proxy match.
                    val targetClass = args!![0] as Class<*>?
                    if (targetClass != null && targetClass.isInstance(proxy)) {
                        return proxy
                    }
                }

                "isOpen" -> {
                    // Handle isOpen method: always return true.
                    return true
                }

                "close" -> {
                    // Handle close method: suppress, not valid.
                    return null
                }

                "getTransaction" -> {
                    throw IllegalStateException(
                        "Not allowed to create transaction on shared EntityManager - " +
                                "use Spring transactions or EJB CMT instead"
                    )
                }
            }

            // Determine current EntityManager: either the transactional one
            // managed by the factory or a temporary one for the given invocation.
            var target = EntityManagerFactoryUtils.doGetTransactionalEntityManager(
                targetFactory, this.properties?:targetFactory.properties, this.synchronizedWithTransaction
            )

            when (method.name) {
                "getTargetEntityManager" -> {
                    // Handle EntityManagerProxy interface.
                    checkNotNull(target) { "No transactional EntityManager available" }
                    return target
                }

                "unwrap" -> {
                    val targetClass = args!![0] as Class<*>?
                    if (targetClass == null) {
                        return (if (target != null) target else proxy)
                    }
                    // We need a transactional target now.
                    checkNotNull(target) { "No transactional EntityManager available" }
                }
            }

            if (transactionRequiringMethods.contains(method.getName())) {
                // We need a transactional target now, according to the JPA spec.
                // Otherwise, the operation would get accepted but remain unflushed...
                if (target == null || (!TransactionSynchronizationManager.isActualTransactionActive() &&
                            !target.getTransaction().isActive())
                ) {
                    throw TransactionRequiredException(
                        "No EntityManager with actual transaction available " +
                                "for current thread - cannot reliably process '" + method.getName() + "' call"
                    )
                }
            }

            // Regular EntityManager operations.
            var isNewEm = false
            if (target == null) {
                logger.debug("Creating new EntityManager for shared EntityManager invocation")
                target =
                    (if (!CollectionUtils.isEmpty(this.properties?:targetFactory.properties)) targetFactory.createEntityManager(this.properties) else targetFactory.createEntityManager())
                isNewEm = true
            }

            // Invoke method on current EntityManager.
            try {
                var result = method.invoke(target, args)
                if (result is Query) {
                    if (isNewEm) {
                        val ifcs = cachedQueryInterfaces.computeIfAbsent(result.javaClass) { key: Class<*> ->
                            org.springframework.util.ClassUtils.getAllInterfacesForClass(
                                key,
                                this.proxyClassLoader
                            )
                        }
                        result = Proxy.newProxyInstance(
                            this.proxyClassLoader, ifcs,
                            DeferredQueryInvocationHandler(result, target)
                        )
                        isNewEm = false
                    } else {
                        EntityManagerFactoryUtils.applyTransactionTimeout(result, targetFactory)
                    }
                }
                return result
            } catch (ex: InvocationTargetException) {
                throw ex.getTargetException()
            } finally {
                if (isNewEm) {
                    EntityManagerFactoryUtils.closeEntityManager(target)
                }
            }
        }

        @Throws(IOException::class, ClassNotFoundException::class)
        fun readObject(ois: ObjectInputStream) {
            // Rely on default serialization, just initialize state after deserialization.
            ois.defaultReadObject()
            // Initialize transient fields.
            initProxyClassLoader()
        }

        companion object {
            private val logger: Log = LogFactory.getLog(TenantEntityManagerInvocationHandler::class.java)
        }
    }


    /**
     * Invocation handler that handles deferred Query objects created by
     * non-transactional createQuery invocations on a shared EntityManager.
     * 
     * Includes deferred output parameter access for JPA 2.1 StoredProcedureQuery,
     * retrieving the corresponding values for all registered parameters on query
     * termination and returning the locally cached values for subsequent access.
     */
    private class DeferredQueryInvocationHandler(
        private val target: Query,
        @field:Nullable private var entityManager: EntityManager?
    ) : InvocationHandler {
        @Nullable
        private var outputParameters: MutableMap<Any?, Any?>? = null

        @Throws(Throwable::class)
        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>): Any? {
            // Invocation on Query interface coming in...

            when (method.getName()) {
                "equals" -> {
                    // Only consider equal when proxies are identical.
                    return (proxy === args[0])
                }

                "hashCode" -> {
                    // Use hashCode of EntityManager proxy.
                    return hashCode()
                }

                "unwrap" -> {
                    // Handle JPA 2.0 unwrap method - could be a proxy match.
                    val targetClass = args[0] as Class<*>?
                    if (targetClass == null) {
                        return this.target
                    } else if (targetClass.isInstance(proxy)) {
                        return proxy
                    } else {
                        return this.target.unwrap(targetClass)
                    }
                }

                "getOutputParameterValue" -> {
                    if (this.entityManager == null) {
                        val key = args[0]
                        require(!(this.outputParameters == null || !this.outputParameters!!.containsKey(key))) { "OUT/INOUT parameter not available: " + key }
                        val value = this.outputParameters!!.get(key)
                        if (value is IllegalArgumentException) {
                            throw value
                        }
                        return value
                    }
                }
            }

            // Invoke method on actual Query object.
            try {
                val retVal = method.invoke(this.target, *args)
                if (method.getName() == "registerStoredProcedureParameter" && args.size == 3 &&
                    (args[2] === ParameterMode.OUT || args[2] === ParameterMode.INOUT)
                ) {
                    if (this.outputParameters == null) {
                        this.outputParameters = LinkedHashMap<Any?, Any?>()
                    }
                    this.outputParameters!!.put(args[0], null)
                }
                return (if (retVal === this.target) proxy else retVal)
            } catch (ex: InvocationTargetException) {
                throw ex.getTargetException()
            } finally {
                if (queryTerminatingMethods.contains(method.getName())) {
                    // Actual execution of the query: close the EntityManager right
                    // afterwards, since that was the only reason we kept it open.
                    if (this.outputParameters != null && this.target is StoredProcedureQuery) {
                        for (entry in this.outputParameters!!.entries) {
                            try {
                                val key: Any = entry.key!!
                                if (key is Int) {
                                    entry.setValue(target.getOutputParameterValue(key))
                                } else {
                                    entry.setValue(target.getOutputParameterValue(key.toString()))
                                }
                            } catch (ex: RuntimeException) {
                                entry.setValue(ex)
                            }
                        }
                    }
                    EntityManagerFactoryUtils.closeEntityManager(this.entityManager)
                    this.entityManager = null
                }
            }
        }
    }
}
