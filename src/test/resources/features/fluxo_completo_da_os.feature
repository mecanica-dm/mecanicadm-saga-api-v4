# language: pt
Funcionalidade: Fluxo completo de uma Ordem de Serviço
  Como oficina, quero que a saga conduza a OS pelos microsserviços (Workorder e Finance)
  para que cada etapa só avance quando o serviço responsável confirmar o que aconteceu,
  e para que cada passo gere o command certo para o próximo serviço.

  Cenário: A OS percorre o fluxo da abertura até a entrega do veículo
    Dado que uma OS foi aberta
    Então a saga está no estado "WORK_ORDER_OPEN"
    E a saga não enviou nenhum command

    Quando a Workorder publica o evento "WorkOrderDiagnosedEvent"
    Então a saga está no estado "AWAITING_BUDGET"
    E a saga enviou o command "GenerateBudgetIntegrationCommand"

    Quando o Finance publica o evento "BudgetGeneratedEvent"
    Então a saga está no estado "BUDGET_GENERATED"
    E a saga guardou o total do orçamento de "720.00"
    E a saga não enviou nenhum command

    Quando o Finance publica o evento "BudgetSentForDecisionEvent"
    Então a saga está no estado "AWAITING_CUSTOMER_DECISION"

    Quando o Finance publica o evento "BudgetApprovedEvent"
    Então a saga está no estado "AWAITING_EXECUTION"
    E a saga enviou o command "AuthorizeWorkOrderIntegrationCommand"

    Quando a Workorder publica o evento "WorkOrderExecutionStartedEvent"
    Então a saga está no estado "IN_EXECUTION"

    Quando a Workorder publica o evento "WorkOrderExecutionFinishedEvent"
    Então a saga está no estado "AWAITING_PAYMENT_LINK"
    E a saga enviou o command "GeneratePaymentIntegrationCommand"

    Quando o Finance publica o evento "PaymentLinkGeneratedEvent"
    Então a saga está no estado "AWAITING_PAYMENT"

    Quando o Finance publica o evento "PaymentConfirmedEvent"
    Então a saga está no estado "AWAITING_DELIVERY"
    E a saga enviou o command "ReleaseWorkOrderVehicleIntegrationCommand"

    Quando a Workorder publica o evento "WorkOrderVehicleDeliveredEvent"
    Então a saga está no estado "COMPLETED"
    E a saga não enviou nenhum command
    E o histórico da saga registra 10 transições

  Cenário: Eventos repetidos depois da conclusão são ignorados
    Dado que a saga da OS já chegou ao estado "COMPLETED"
    Quando o Finance publica o evento "PaymentConfirmedEvent"
    Então a saga está no estado "COMPLETED"
    E a saga não enviou nenhum command
