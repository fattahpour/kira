package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record KafkaFlow(String topic, List<String> producers, List<String> consumers) {}
