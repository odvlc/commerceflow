/*
 * Separates preview, user confirmation and external execution; retries recover the original intent
 * rather than ask the model again.
 */
package com.commerceflow.ai.application;

import com.commerceflow.ai.domain.Actor;
import com.commerceflow.ai.domain.AiModels.Claimed;
import com.commerceflow.ai.domain.AiModels.Confirmation;
import com.commerceflow.ai.domain.AiModels.Execution;
import com.commerceflow.ai.domain.AiModels.Proposal;
import com.commerceflow.ai.persistence.ActionStore;
import com.commerceflow.ai.persistence.AiStore;
import com.commerceflow.ai.persistence.RunStore;
import com.commerceflow.ai.retrieval.KnowledgeModels.Evidence;
import com.commerceflow.ai.tools.CommerceClient;
import com.commerceflow.support.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CartActions {

    private final CommerceClient commerce;
    private final ActionStore actions;
    private final AiStore views;
    private final RunStore runs;
    private final Credentials credentials;
    private final JsonMapper json;

    public CartActions(
            CommerceClient commerce,
            ActionStore actions,
            AiStore views,
            RunStore runs,
            Credentials credentials,
            JsonMapper json) {
        this.commerce = commerce;
        this.actions = actions;
        this.views = views;
        this.runs = runs;
        this.credentials = credentials;
        this.json = json;
    }

    public Proposal prepare(
            UUID run, long fence, Actor actor, String operation, UUID product, int quantity) {
        actor.requireMode("CUSTOMER");
        if (!Set.of("ADD", "SET", "DELETE").contains(operation)
                || quantity < 0
                || quantity > 1000
                || (!operation.equals("DELETE") && quantity == 0)
                || operation.equals("DELETE") && quantity != 0)
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "AI_PROPOSAL_INVALID", "Invalid exact cart change");
        String correlation = "ai-" + run;
        // Fetch the preview before opening the proposal transaction; If-Match guards later cart drift.
        var detail =
                commerce.get("catalog", "/api/v1/products/" + product, Map.of(), actor, correlation);
        if (detail.has("active") && !detail.path("active").asBoolean())
            throw AiStore.conflict("AI_PRODUCT_INACTIVE", "Product is not active");
        var cart = commerce.get("cart", "/api/v1/cart", Map.of(), actor, correlation);
        String revision = cart.path("revision").asText();
        if (revision.isBlank())
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI_CART_REVISION_UNAVAILABLE",
                    "Cart revision is unavailable");
        int before = 0;
        for (var item : cart.path("items"))
            if (item.path("productId").asText().equals(product.toString()))
                before = item.path("quantity").asInt();
        int after =
                operation.equals("ADD") ? before + quantity : operation.equals("DELETE") ? 0 : quantity;
        if (after > 1000)
            throw AiStore.conflict("AI_CART_QUANTITY_LIMIT", "Resulting quantity exceeds the cart limit");
        var preview =
                json.valueToTree(
                        Map.of("productName",
                                detail.path("name").asText(),
                                "productVersion",
                                detail.path("version").asLong(),
                                "unitPrice",
                                detail.path("price").asText(),
                                "currency",
                                detail.path("currency").asText(),
                                "beforeQuantity",
                                before,
                                "afterQuantity",
                                after,
                                "recoverySeconds",
                                Math.max(0, Math.min(82800, cart.path("ttlSeconds").asLong() - 5))));
        return actions.propose(run, fence, actor, operation, product, quantity, revision, preview);
    }

    public Execution confirm(UUID id, Actor actor, Confirmation input) {
        var execution = actions.confirm(id, actor, input);
        var p = actions.owned(id, actor);
        credentials.attach(p.runId(), actor);
        return execution;
    }

    /**
     * Recover a committed proposal or execute its durable intent under the same leased worker.
     */
    public boolean continueAction(Claimed c, Actor actor) {
        var proposals = views.proposals(c.id());
        if (proposals.isEmpty())
            return false;
        var proposal = proposals.getFirst();
        var found = actions.execution(proposal.id());
        if (found.isEmpty()) {
            // The proposal may have committed just before a crash prevented WAITING_CONFIRMATION.
            if (proposal.state().equals("PROPOSED") && proposal.expiresAt().isAfter(Instant.now())) {
                runs.stop(c, "WAITING_CONFIRMATION", null, 0);
                credentials.remove(c.id());
                return true;
            }
            runs.stop(c, "FAILED", "AI_PROPOSAL_EXPIRED", 0);
            credentials.remove(c.id());
            return true;
        }
        var execution = actions.start(c, proposal.id());
        JsonNode result = execution.result();
        if (!execution.state().equals("SUCCEEDED")) {
            if (execution.state().equals("FAILED")) {
                runs.stop(c, "FAILED", execution.errorCode(), 0);
                return true;
            }
            if (!execution.recoveryUntil().isAfter(Instant.now())
                    || execution.attempts() > 3
                    || execution.errorCode() != null
                    && execution.errorCode().equals("AI_RECOVERY_WINDOW_EXPIRED")) {
                runs.stop(c, "FAILED", "AI_OUTCOME_UNKNOWN", 0);
                return true;
            }
            try {
                // The exact original precondition and key are reused on every recovery attempt.
                // Recovery must reach Cart's idempotency memo even if the product changed after dispatch.
                if (execution.attempts() == 1) {
                    var product = commerce.get(
                            "catalog",
                            "/api/v1/products/" + proposal.productId(),
                            Map.of(),
                            actor,
                            "ai-" + c.id());
                    if (!product.path("active").asBoolean()
                            || product.path("version").asLong()
                            != proposal.preview().path("productVersion").asLong())
                        throw AiStore.conflict(
                                "AI_APPROVAL_STALE", "The product changed; review a new proposal");
                }
                result = commerce.mutateCart(
                        proposal.operation(),
                        proposal.productId(),
                        proposal.quantity(),
                        proposal.cartRevision(),
                        execution.requestKey(),
                        actor,
                        "ai-" + c.id());
                // Keep a real acknowledgment even if cancellation has since fenced this run.
                // The fenced finish below still prevents this worker from overwriting the run state.
                actions.record(proposal.id(), "SUCCEEDED", result, null);
            } catch (BusinessException error) {
                if (error.status().value() == 401)
                    throw error;
                // Once a request may have reached Cart, a later error cannot prove the write did not
                // happen.
                boolean uncertain = error.status().is5xxServerError() || execution.attempts() > 1;
                actions.record(
                        proposal.id(),
                        uncertain ? "OUTCOME_UNKNOWN" : "FAILED",
                        null,
                        uncertain ? "AI_OUTCOME_UNKNOWN" : error.code());
                if (uncertain && error.status().is5xxServerError())
                    throw error;
                runs.stop(c, "FAILED", uncertain ? "AI_OUTCOME_UNKNOWN" : error.code(), 0);
                return true;
            }
        }
        var evidence =
                new Evidence(
                        "cart-execution:" + execution.id(),
                        "cart/current",
                        result.path("version").asLong(),
                        "Confirmed cart result",
                        "Acknowledged Cart API response",
                        proposal.id().toString(),
                        json.writeValueAsString(result));
        // The execution audit is saved first; a lost fence can prevent run completion without
        // discarding the real Cart acknowledgment.
        runs.finish(c,
                "Cart acknowledged your confirmed change. The source below records that response; review"
                        + " the current cart for any subsequent changes.",
                List.of(evidence),
                0);
        credentials.remove(c.id());
        return true;
    }

    @Scheduled(fixedDelay = 10000)
    public void expire() {
        actions.expire();
    }
}
