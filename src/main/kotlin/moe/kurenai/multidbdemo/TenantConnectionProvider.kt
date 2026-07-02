package moe.kurenai.multidbdemo

import org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl
import org.hibernate.engine.jdbc.connections.spi.AbstractMultiTenantConnectionProvider
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider
import java.sql.Connection
import javax.sql.DataSource

class TenantConnectionProvider (
    dsMap: Map<TenantId, DataSource>,
): AbstractMultiTenantConnectionProvider<TenantId>() {

    val map: Map<TenantId, ConnectionProvider> = dsMap.mapValues { (k,v) ->
        DatasourceConnectionProviderImpl().apply {
            dataSource = v
        }
    }

    override fun getAnyConnectionProvider(): ConnectionProvider {
        return map[TenantId.CLUSTER1]?:map.values.first()
    }

    override fun selectConnectionProvider(tenantIdentifier: TenantId): ConnectionProvider {
        return map[tenantIdentifier]?: anyConnectionProvider
    }

}
