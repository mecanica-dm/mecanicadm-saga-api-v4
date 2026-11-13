package com.mecanicadm.saga_api.infra.messaging.consumer;

import com.mecanicadm.saga_api.infra.messaging.inbox.InboxStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MessageConsumerSupport {
    private final InboxStore inbox;
    private final TransactionTemplate transactionTemplate;

    public MessageConsumerSupport(InboxStore inbox,
                                  PlatformTransactionManager transactionManager) {
        this.inbox = inbox;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    InboxStore inbox() {
        return inbox;
    }

    TransactionTemplate transactionTemplate() {
        return transactionTemplate;
    }
}
