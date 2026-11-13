package com.mecanicadm.saga_api.core.saga.domain;

import java.util.UUID;

public record ClientSnapshot(UUID id, String name, String email, String document, String phone) {
}
