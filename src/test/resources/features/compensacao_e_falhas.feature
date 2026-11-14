# language: pt
Funcionalidade: Compensação e tratamento de falhas da saga
  Como oficina, quero que a saga desfaça o que já foi feito quando o fluxo é interrompido
  e que ela pare, com o motivo registrado, quando precisa de intervenção.

  Contexto:
    Dado que uma OS foi aberta

  Cenário: O cliente rejeita o orçamento e a OS é cancelada
    Dado que a saga da OS já chegou ao estado "AWAITING_CUSTOMER_DECISION"
    Quando o Finance publica o evento "BudgetRejectedEvent"
    Então a saga está no estado "CANCELING"
    E a saga enviou o command "CancelWorkOrderIntegrationCommand"
    Quando a Workorder publica o evento "WorkOrderCanceledEvent"
    Então a saga está no estado "CANCELED"

  Cenário: O orçamento expira sem decisão do cliente e a OS é cancelada
    Dado que a saga da OS já chegou ao estado "AWAITING_CUSTOMER_DECISION"
    Quando o Finance publica o evento "BudgetExpiredEvent"
    Então a saga está no estado "CANCELING"
    E a saga enviou o command "CancelWorkOrderIntegrationCommand"
    Quando a Workorder publica o evento "WorkOrderCanceledEvent"
    Então a saga está no estado "CANCELED"

  Cenário: Cancelar a OS depois do pagamento estorna o valor e cancela o orçamento
    Dado que a saga da OS já chegou ao estado "AWAITING_DELIVERY"
    Quando a Workorder publica o evento "WorkOrderCanceledEvent"
    Então a saga está no estado "CANCELING"
    E a saga enviou os commands, nesta ordem:
      | RefundPaymentIntegrationCommand |
      | CancelBudgetIntegrationCommand  |
    E a saga aguarda a confirmação do command "RefundPaymentIntegrationCommand"
    E a saga aguarda a confirmação do command "CancelBudgetIntegrationCommand"

    Quando o Finance publica o evento "PaymentRefundedEvent"
    Então a saga está no estado "CANCELING"

    Quando o Finance publica o evento "BudgetCanceledEvent"
    Então a saga está no estado "CANCELED"

  Cenário: A Workorder recusa a autorização e a saga compensa o orçamento
    Dado que a saga da OS já chegou ao estado "AWAITING_EXECUTION"
    Quando a Workorder recusa o command "AuthorizeWorkOrderIntegrationCommand"
    Então a saga está no estado "CANCELING"
    E a saga enviou os commands, nesta ordem:
      | CancelBudgetIntegrationCommand    |
      | CancelWorkOrderIntegrationCommand |

  Cenário: Alterar a OS com orçamento ativo cancela o orçamento e reabre a OS
    Dado que a saga da OS já chegou ao estado "AWAITING_CUSTOMER_DECISION"
    Quando a Workorder publica o evento "WorkOrderUpdatedEvent"
    Então a saga está no estado "WORK_ORDER_OPEN"
    E a saga enviou o command "CancelBudgetIntegrationCommand"
    E a saga descartou o total do orçamento

  Cenário: Falha ao gerar o link de pagamento pausa a saga, e o link gerado depois a retoma
    Dado que a saga da OS já chegou ao estado "AWAITING_PAYMENT_LINK"
    Quando o Finance publica o evento "PaymentLinkGenerationFailedEvent"
    Então a saga está no estado "PAUSED"
    E a saga está pausada com o motivo contendo "PaymentLinkGenerationFailed"
    Quando o Finance publica o evento "PaymentLinkGeneratedEvent"
    Então a saga está no estado "AWAITING_PAYMENT"

  Cenário: Evento repetido de uma etapa já passada é ignorado
    Dado que a saga da OS já chegou ao estado "AWAITING_BUDGET"
    Quando a Workorder publica o evento "WorkOrderDiagnosedEvent"
    Então a saga está no estado "AWAITING_BUDGET"
    E a saga não enviou nenhum command

  Cenário: Evento à frente do ponto em que a saga está pausa a saga
    Dado que a saga da OS já chegou ao estado "AWAITING_CUSTOMER_DECISION"
    Quando o Finance publica o evento "PaymentConfirmedEvent"
    Então a saga está no estado "PAUSED"
    E a saga está pausada com o motivo contendo "UnexpectedEvent"
