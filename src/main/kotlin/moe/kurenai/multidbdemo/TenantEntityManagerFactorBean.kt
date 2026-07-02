package moe.kurenai.multidbdemo

import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean

class TenantEntityManagerFactorBean(
    val map: MutableMap<TenantId, LocalContainerEntityManagerFactoryBean>
): AbstractEntityManagerFactoryBean() {

    override fun getNativeEntityManagerFactory(): EntityManagerFactory {
        return map.getOrDefault(TenantContext.resolveCurrentTenantIdentifier(), map.values.first()).nativeEntityManagerFactory
    }

    override fun createNativeEntityManagerFactory(): EntityManagerFactory {
        return nativeEntityManagerFactory
    }
}