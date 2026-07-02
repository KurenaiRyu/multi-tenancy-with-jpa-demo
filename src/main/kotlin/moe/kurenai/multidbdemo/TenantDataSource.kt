package moe.kurenai.multidbdemo

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource

class TenantDataSource: AbstractRoutingDataSource() {

    override fun determineCurrentLookupKey(): TenantId {
        return TenantContext.resolveCurrentTenantIdentifier()
    }
}