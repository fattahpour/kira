package com.acme.airetrieval.retrieve.dto;

public record BeanDep(
    String beanFqn,
    String signature,
    int depth
) {}
