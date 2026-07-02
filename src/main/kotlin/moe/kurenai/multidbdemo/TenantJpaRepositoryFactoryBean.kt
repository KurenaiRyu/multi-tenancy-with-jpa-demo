package moe.kurenai.multidbdemo

import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean
import org.springframework.data.repository.Repository

class TenantJpaRepositoryFactoryBean<T: Repository<S, ID>, S, ID>(
    repositoryInterface: Class<T>,
) : JpaRepositoryFactoryBean<T, S, ID>(repositoryInterface) {

    override fun afterPropertiesSet() {
        setEntityManager(TenantEntityManagerCreator.createTenantEntityManager(null))
        super.afterPropertiesSet()
    }

}