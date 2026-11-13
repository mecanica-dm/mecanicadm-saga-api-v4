package com.mecanicadm.saga_api.shared.usecase;

public interface VoidUseCase<I> {
    void execute(I input);
}
