package buildingblocks.infrastructure.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConditionalOnBean(RedisConnectionFactory.class)
@ConditionalOnProperty(
        prefix = "buildingblocks.redis",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class RedisCacheProvider implements CacheProvider {

    private final RedisTemplate<String, byte[]> redisTemplate;
    private final ValueOperations<String, byte[]> ops;

    private static final String PREFIX = "grpc-cache:";

    public RedisCacheProvider(RedisTemplate<String, byte[]> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.ops = redisTemplate.opsForValue();
    }

    @Override
    public void put(String key, byte[] value, long ttlSeconds) {
        ops.set(PREFIX + key, value, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public byte[] get(String key) {
        return ops.get(PREFIX + key);
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(PREFIX + key);
    }

    @Override
    public void clear() {

        redisTemplate.execute((RedisCallback<Void>) connection -> {

            ScanOptions options = ScanOptions.scanOptions()
                    .match(PREFIX + "*")
                    .count(1000)
                    .build();

            connection.openPipeline();

            try (Cursor<byte[]> cursor = connection.scan(options)) {

                while (cursor.hasNext()) {
                    connection.keyCommands().del(cursor.next());
                }

            }

            connection.closePipeline();

            return null;
        });
    }
}