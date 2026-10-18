package com.commerceflow.ai.api;

import com.commerceflow.ai.application.Credentials;
import com.commerceflow.ai.domain.AiModels.Proposal;
import com.commerceflow.ai.domain.AiModels.RunView;
import com.commerceflow.ai.domain.AiModels.SendMessage;
import com.commerceflow.ai.model.LocalModels;
import com.commerceflow.ai.persistence.AiStore;
import com.commerceflow.support.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelAdmissionTest {

    @Test
    void unavailableInferenceRejectsNewWorkWithoutPersistingMessagesOrCredentials() {
        var models = mock(LocalModels.class);
        when(models.chatName()).thenReturn("llama3.1:8b");
        var store = mock(AiStore.class);
        var credentials = mock(Credentials.class);
        doThrow(
                new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE, "AI_MODEL_UNAVAILABLE", "Local model unavailable"))
                .when(models)
                .requireReady();
        var api = new AiController(store, credentials, models, null, null);
        var jwt =
                Jwt.withTokenValue("test-only-not-persisted")
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                        .build();
        assertThatThrownBy(
                () ->
                        api.send(
                                jwt, UUID.randomUUID(), "message-outage", new SendMessage("policy question")))
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.code()).isEqualTo("AI_MODEL_UNAVAILABLE"));
        when(store.run(any(), any())).thenReturn(run(List.of()));
        assertThatThrownBy(() -> api.resume(jwt, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
        verify(store, never()).send(any(), any(), any(), any());
        verify(store, never()).resume(any(), any());
        verifyNoInteractions(credentials);
        assertThat(api.capabilities(jwt)).containsEntry("modelReady", false);
    }

    private static RunView run(List<Proposal> proposals) {
        return new RunView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "AUTHENTICATION_REQUIRED",
                "llama3.1:8b",
                1,
                null,
                1,
                1000,
                Instant.now(),
                Instant.now(),
                List.of(),
                proposals);
    }

    @Test
    void persistedCartIntentCanResumeWhenInferenceIsUnavailable() {
        var models = mock(LocalModels.class);
        var store = mock(AiStore.class);
        var credentials = mock(Credentials.class);
        var api = new AiController(store, credentials, models, null, null);
        var jwt =
                Jwt.withTokenValue("test-only-not-persisted")
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                        .build();
        var proposal =
                new Proposal(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ADD",
                        UUID.randomUUID(),
                        1,
                        "a".repeat(64),
                        UUID.randomUUID() + ":0",
                        "CONFIRMED",
                        1,
                        Instant.now().plusSeconds(300),
                        null);
        var paused = run(List.of(proposal));
        when(store.run(any(), any())).thenReturn(paused);
        when(store.resume(any(), any())).thenReturn(paused);
        assertThat(api.resume(jwt, paused.id())).isEqualTo(paused);
        verifyNoInteractions(models);
        verify(credentials).attach(eq(paused.id()), any());
    }
}
