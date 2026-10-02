package buildingblocks.infrastructure.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@ConditionalOnBean(RedisCacheProvider.class)
public class HybridCacheProvider implements CacheProvider {

    private final CaffeineCacheProvider l1;
    private final RedisCacheProvider l2;

    public HybridCacheProvider(
            CaffeineCacheProvider l1,
            RedisCacheProvider l2) {

        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public void put(String key, byte[] value, long ttlSeconds) {

        l1.put(key, value, ttlSeconds);
        l2.put(key, value, ttlSeconds);
    }

    @Override
    public byte[] get(String key) {

        byte[] value = l1.get(key);

        if (value != null) {
            return value;
        }

        value = l2.get(key);

        if (value != null) {
            l1.put(key, value, 30);
        }

        return value;
    }

    @Override
    public void evict(String key) {

        l1.evict(key);
        l2.evict(key);
    }

    @Override
    public void clear() {

        l1.clear();
        l2.clear();
    }
}