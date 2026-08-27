package com.pico.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@Profile("redis")
public class RedisCoordinationConfiguration {
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${pico.redis.host:${PICO_REDIS_HOST:localhost}}") String host,
            @Value("${pico.redis.port:${PICO_REDIS_PORT:6379}}") int port,
            @Value("${pico.redis.password:${PICO_REDIS_PASSWORD:}}") String password) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
        return new LettuceConnectionFactory(configuration);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
