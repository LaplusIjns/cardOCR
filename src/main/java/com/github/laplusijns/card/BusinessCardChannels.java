package com.github.laplusijns.card;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.util.concurrent.Queues;

@Component
public class BusinessCardChannels {
    private final Map<String, Sinks.Many<BusinessCardDTO>> channels = new ConcurrentHashMap<>();

    public Flux<@NonNull BusinessCardDTO> subscription(final String sessionId) {
        return channel(sessionId).asFlux();
    }

    public EmitResult emit(final String sessionId, final BusinessCardDTO card) {
        return channel(sessionId).tryEmitNext(card);
    }

    private Sinks.Many<BusinessCardDTO> channel(final String sessionId) {
        return channels.computeIfAbsent(
                sessionId, _ -> Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false));
    }
}
