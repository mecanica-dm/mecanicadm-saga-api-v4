package com.mecanicadm.saga_api.infra.features.saga.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;

public interface SagaMongoRepository extends MongoRepository<SagaDocument, UUID> {
}
