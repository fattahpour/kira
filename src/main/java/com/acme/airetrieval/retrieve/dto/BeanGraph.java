package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record BeanGraph(
    String root,
    List<BeanDep> dependencies
) {}
