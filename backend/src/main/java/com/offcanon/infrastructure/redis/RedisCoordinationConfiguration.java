package com.offcanon.infrastructure.redis;

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
            @Value("${offcanon.redis.host:${OFFCANON_REDIS_HOST:localhost}}") String host,
            @Value("${offcanon.redis.port:${OFFCANON_REDIS_PORT:6379}}") int port,
            @Value("${offcanon.redis.password:${OFFCANON_REDIS_PASSWORD:}}") String password) {
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
