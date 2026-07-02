package moe.kurenai.multidbdemo

import com.zaxxer.hikari.HikariDataSource
import jakarta.persistence.EntityManagerFactory
import moe.kurenai.multidbdemo.entity.Loan
import moe.kurenai.multidbdemo.repository.LoanRepository
import org.hibernate.cfg.AvailableSettings
import org.hibernate.engine.jdbc.connections.spi.DataSourceBasedMultiTenantConnectionProviderImpl
import org.hibernate.hikaricp.internal.HikariCPConnectionProvider
import org.hibernate.hikaricp.internal.HikariConfigurationUtil
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.orm.jpa.HibernateProperties
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import java.util.Properties
import javax.sql.DataSource


@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
    repositoryFactoryBeanClass = TenantJpaRepositoryFactoryBean::class,
)
class DBConfig {

    @Bean
    @Primary
    fun tenantDataSource(): DataSource {
        val ds = TenantDataSource()
        val cluster1DS = cluster1DS()
        val targetDataSources: Map<Any, Any> = mutableMapOf(
            TenantId.CLUSTER1 to cluster1DS,
            TenantId.CLUSTER2 to cluster2DS(),
        )
        ds.setTargetDataSources(targetDataSources)
        ds.setDefaultTargetDataSource(cluster1DS)

        return ds
    }

    @Bean("entityManagerFactory")
    @Primary
    fun entityManagerFactoryBean (
        tenantDataSource: DataSource,
        @Qualifier("cluster1EMF") emf1: LocalContainerEntityManagerFactoryBean,
        @Qualifier("cluster2EMF") emf2: LocalContainerEntityManagerFactoryBean,): AbstractEntityManagerFactoryBean {
        val ds = tenantDataSource as TenantDataSource
        val provider = TenantConnectionProvider(ds.resolvedDataSources as MutableMap<TenantId, DataSource>)

        val emf = TenantEntityManagerFactorBean(mutableMapOf(TenantId.CLUSTER1 to emf1, TenantId.CLUSTER2 to emf2))
        emf.setJpaPropertyMap(mutableMapOf<String, Any>(
            AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER to TenantContext,
            AvailableSettings.CONNECTION_PROVIDER to provider
        ))

        TenantContext.tenantEmf = emf

        return emf
    }

    @Bean
    @Primary
    fun transactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager {
        return JpaTransactionManager(entityManagerFactory)
    }

    @Bean("cluster1EMF")
    fun cluster1EntityManagerFactory(builder: EntityManagerFactoryBuilder): LocalContainerEntityManagerFactoryBean? {
        return builder.dataSource(cluster1DS())
            .packages(Loan::class.java.packageName)
            .persistenceUnit("PU-${TenantId.CLUSTER1}")
            .build()
    }

    @Bean("cluster1DS")
    fun cluster1DS(): DataSource {
        return DataSourceBuilder.create().type(HikariDataSource::class.java).build().apply {
            jdbcUrl = "jdbc:postgresql://10.0.0.20:5432/multi_db_test?currentSchema=cluster_1"
            username = "kurenai"
            password = "test"
        }
    }

    @Bean("cluster2EMF")
    fun cluster2EntityManagerFactory(builder: EntityManagerFactoryBuilder): LocalContainerEntityManagerFactoryBean? {
        return builder.dataSource(cluster2DS())
            .packages(Loan::class.java.packageName)
            .persistenceUnit("PU-${TenantId.CLUSTER2}")
            .build()
    }

    @Bean("cluster2DS")
    fun cluster2DS(): DataSource {
        return DataSourceBuilder.create().type(HikariDataSource::class.java).build().apply {
            jdbcUrl = "jdbc:postgresql://10.0.0.20:5432/multi_db_test?currentSchema=cluster_2"
            username = "kurenai"
            password = "test"
        }
    }

}