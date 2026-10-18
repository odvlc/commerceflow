package com.commerceflow.cart;

import com.commerceflow.support.*;

import java.time.Duration;
import java.util.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CartService {

    public record CartItem(UUID productId, int quantity) {

    }

    public record CartView(List<CartItem> items, long version, long ttlSeconds, String revision) {

    }

    private record Document(Map<String, Integer> items, long version, String incarnation) {

    }

    // Replay the memo before checking the revision: a successful write already advanced that revision.
    // Lua commits the cart and its replay result atomically, including when the HTTP reply is lost.
    private static final DefaultRedisScript<String> MUTATE =
            new DefaultRedisScript<>(
                    """
                            local previous=redis.call('HGET',KEYS[2],ARGV[4])
                            if previous then
                              local memo=cjson.decode(previous)
                              if memo.hash~=ARGV[5] then return 'ERROR:IDEMPOTENCY_CONFLICT' end
                              return memo.result
                            end
                            if redis.call('HLEN',KEYS[2])>=1000 then return 'ERROR:CART_OPERATION_LIMIT' end
                            local raw=redis.call('GET',KEYS[1])
                            local cart=raw and cjson.decode(raw) or {items={},version=0,incarnation=ARGV[7]}
                            if not cart.incarnation then cart.incarnation=ARGV[7] end
                            local revision=cart.incarnation..':'..tostring(cart.version)
                            if ARGV[8]~='' and ARGV[8]~=revision then return 'ERROR:CART_REVISION_CONFLICT' end
                            local current=cart.items[ARGV[2]] or 0
                            local quantity=tonumber(ARGV[3])
                            if ARGV[1]=='ADD' then quantity=current+quantity end
                            if ARGV[1]=='DELETE' then quantity=0 end
                            if quantity>1000 then return 'ERROR:QUANTITY_LIMIT' end
                            local count=0
                            for _,_ in pairs(cart.items) do count=count+1 end
                            if current==0 and quantity>0 and count>=100 then return 'ERROR:CART_ITEM_LIMIT' end
                            if quantity==0 then cart.items[ARGV[2]]=nil else cart.items[ARGV[2]]=quantity end
                            cart.version=cart.version+1
                            local result=cjson.encode(cart)
                            local memo=cjson.encode({hash=ARGV[5],result=result})
                            redis.call('SET',KEYS[1],result,'EX',ARGV[6])
                            redis.call('HSET',KEYS[2],ARGV[4],memo)
                            redis.call('EXPIRE',KEYS[2],ARGV[6])
                            return result
                            """,
                    String.class);
    private static final DefaultRedisScript<String> READ = new DefaultRedisScript<>("""
            local raw=redis.call('GET',KEYS[1])
            if raw then
              local cart=cjson.decode(raw)
              if cart.incarnation then return raw end
              local ttl=redis.call('PTTL',KEYS[1])
              cart.incarnation=ARGV[1]
              raw=cjson.encode(cart)
              redis.call('SET',KEYS[1],raw,'PX',math.max(1,ttl))
              return raw
            end
            local raw=cjson.encode({items={},version=0,incarnation=ARGV[1]})
            redis.call('SET',KEYS[1],raw,'EX',ARGV[2])
            return raw
            """,String.class);
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final long ttl;

    public CartService(
            StringRedisTemplate redis, JsonMapper json, @Value("${cart.ttl:PT24H}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl.toSeconds();
        if (this.ttl < 1) throw new IllegalArgumentException("Cart TTL must be at least one second");
    }

    public CartView get(UUID customer) {
        try {
            String raw = redis.execute(READ,List.of(key(customer)),UUID.randomUUID().toString(),Long.toString(ttl));
            return view(raw, customer);
        } catch (DataAccessException error) {
            throw unavailable();
        }
    }

    public CartView mutate(
            UUID customer, String operation, UUID product, int quantity, String idempotencyKey) {
        return mutate(customer,operation,product,quantity,idempotencyKey,null);
    }

    public CartView mutate(UUID customer,String operation,UUID product,int quantity,String idempotencyKey,String expectedRevision) {
        if(expectedRevision!=null && !expectedRevision.matches("\"?[a-f0-9-]{36}:[0-9]{1,19}\"?"))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_CART_REVISION","Invalid cart revision");
        String revision=expectedRevision==null?"":expectedRevision.replace("\"","");
        if (!Set.of("ADD", "SET", "DELETE").contains(operation) || quantity < 0 || quantity > 1000)
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST, "INVALID_CART_OPERATION", "Invalid cart operation");
        try {
            String hash = RequestHash.sha256(operation + ":" + product + ":" + quantity);
            var raw =
                    redis.execute(
                            MUTATE,
                            List.of(key(customer), "cf:cart:{" + customer + "}:requests"),
                            operation,
                            product.toString(),
                            Integer.toString(quantity),
                            idempotencyKey,
                            hash,
                            Long.toString(ttl),UUID.randomUUID().toString(),revision);
            if (raw == null) throw unavailable();
            if (raw.startsWith("ERROR:"))
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        raw.substring(6),
                        "Cart operation conflicts with current state or limits");
            return view(raw, customer);
        } catch (DataAccessException error) {
            throw unavailable();
        }
    }

    private CartView view(String raw, UUID customer) {
        if (raw == null) throw unavailable();
        var document = json.readValue(raw, Document.class);
        var items =
                document.items().entrySet().stream()
                        .map(e -> new CartItem(UUID.fromString(e.getKey()), e.getValue()))
                        .sorted(Comparator.comparing(CartItem::productId))
                        .toList();
        var remaining = redis.getExpire(key(customer));
        return new CartView(items, document.version(), remaining == null ? 0 : Math.max(0, remaining), document.incarnation()==null?"":document.incarnation()+":"+document.version());
    }

    private static String key(UUID customer) {
        return "cf:cart:{" + customer + "}:items";
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CART_UNAVAILABLE",
                "Cart storage unavailable; retry with the same idempotency key");
    }
}
