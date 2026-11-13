package com.mecanicadm.saga_api.infra.features.saga.persistence;

import com.mecanicadm.saga_api.core.saga.domain.SagaState;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaQueryGateway;
import com.mecanicadm.saga_api.core.saga.usecase.query.GetAllSagasQuery;
import com.mecanicadm.saga_api.core.shared.PageResult;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.mecanicadm.saga_api.core.saga.domain.SagaInstance;
import com.mecanicadm.saga_api.core.saga.domain.port.SagaGateway;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class SagaRepositoryImpl implements SagaGateway, SagaQueryGateway {

    private static final String STATE = "state";

    private final SagaMongoRepository repository;
    private final MongoTemplate mongoTemplate;

    public SagaRepositoryImpl(SagaMongoRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<SagaInstance> findById(UUID workOrderId) {
        return repository.findById(workOrderId).map(this::toDomain);
    }

    @Override
    public SagaInstance save(SagaInstance saga) {
        return toDomain(repository.save(toDocument(saga)));
    }

    @Override
    public PageResult<SagaInstance> findAll(GetAllSagasQuery query) {
        Query mongoQuery = new Query();
        Criteria filter = toCriteria(query);
        if (filter != null) {
            mongoQuery.addCriteria(filter);
        }
        long total = mongoTemplate.count(mongoQuery, SagaDocument.class);

        Sort.Direction direction = "ASC".equalsIgnoreCase(query.direction()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String property = "workOrderId".equals(query.sortBy()) ? "_id" : query.sortBy();
        // O desempate por _id mantem a paginacao estavel; ao ordenar pelo proprio _id ele nao pode repetir a chave, senao
        // a direcao pedida e sobrescrita.
        Sort sort = Sort.by(direction, property);
        if (!"_id".equals(property)) {
            sort = sort.and(Sort.by(Sort.Direction.ASC, "_id"));
        }
        mongoQuery.with(PageRequest.of(query.page(), query.size(), sort));

        List<SagaInstance> content = mongoTemplate.find(mongoQuery, SagaDocument.class).stream().map(this::toDomain).toList();
        return new PageResult<>(content, query.page(), query.size(), total);
    }

    private Criteria toCriteria(GetAllSagasQuery query) {
        List<Criteria> parts = new ArrayList<>();
        if (query.workOrderId() != null) {
            parts.add(Criteria.where("_id").is(query.workOrderId()));
        }
        if (query.state() != null) {
            parts.add(Criteria.where(STATE).is(query.state()));
        }
        if (query.pausedFrom() != null) {
            parts.add(Criteria.where("pausedFrom").is(query.pausedFrom()));
        }
        if (query.step() != null) {
            List<SagaState> statesOfStep = Arrays.stream(SagaState.values()).filter(state -> state.step() == query.step()).toList();
            parts.add(new Criteria().orOperator(
                    Criteria.where(STATE).in(statesOfStep),
                    Criteria.where(STATE).is(SagaState.PAUSED).and("pausedFrom").in(statesOfStep)));
        }
        addRange(parts, "createdAt", query.createdFrom(), query.createdTo());
        addRange(parts, "updatedAt", query.updatedFrom(), query.updatedTo());
        return parts.isEmpty() ? null : new Criteria().andOperator(parts);
    }

    private void addRange(List<Criteria> parts, String field, Instant from, Instant to) {
        if (from == null && to == null) {
            return;
        }
        Criteria range = Criteria.where(field);
        if (from != null) {
            range = range.gte(from);
        }
        if (to != null) {
            range = range.lte(to);
        }
        parts.add(range);
    }

    private SagaDocument toDocument(SagaInstance saga) {
        return new SagaDocument(saga.getWorkOrderId(), saga.getVersion(), saga.getState(), saga.getPausedFrom(),
                saga.getWorkOrder(), saga.getBudgetTotal(), saga.getPendingCompensations(), saga.getPauseReason(), saga.getPaymentLink(),
                saga.getCreatedAt(), saga.getUpdatedAt(), saga.getHistory());
    }

    private SagaInstance toDomain(SagaDocument document) {
        return SagaInstance.restore(document.getWorkOrderId(), document.getVersion(), document.getState(),
                document.getPausedFrom(), document.getWorkOrder(), document.getBudgetTotal(), document.getPendingCompensations(),
                document.getPauseReason(), document.getPaymentLink(), document.getCreatedAt(), document.getUpdatedAt(),
                document.getHistory());
    }
}
