package com.mecanicadm.saga_api.infra.config;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

public final class TransactionalProxy {
    private TransactionalProxy() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T of(T target, PlatformTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new MatchAlwaysTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
