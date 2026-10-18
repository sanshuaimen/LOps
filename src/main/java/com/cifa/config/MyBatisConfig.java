package com.cifa.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the MyBatis mappers. Kept in a dedicated @Configuration (not on the
 * @SpringBootApplication class) so @WebMvcTest slices — which load the
 * application class as their context root but no regular @Configuration beans —
 * stay free of MyBatis requirements.
 */
@Configuration
@MapperScan("com.cifa.persistence.mapper")
public class MyBatisConfig {
}
