package moe.kurenai.multidbdemo

import jakarta.persistence.EntityManagerFactory
import org.hibernate.context.spi.CurrentTenantIdentifierResolver

object TenantContext : CurrentTenantIdentifierResolver<TenantId> {

    private val currentTenantId = ThreadLocal<TenantId?>()

    lateinit var tenantEmf: TenantEntityManagerFactorBean

    override fun resolveCurrentTenantIdentifier(): TenantId {
        return getCurrentTenantId() ?: TenantId.CLUSTER1
    }

    override fun validateExistingCurrentSessions(): Boolean {
        return true
    }

    fun getCurrentTenantId(): TenantId? = currentTenantId.get()
    fun setCurrentTenantId(tenantId: TenantId) {
        currentTenantId.set(tenantId)
    }
    fun clearCurrentTenantId() = currentTenantId.remove()
}

enum class TenantId {
    CLUSTER1, CLUSTER2, CLUSTER3, CLUSTER4, CLUSTER5
}
